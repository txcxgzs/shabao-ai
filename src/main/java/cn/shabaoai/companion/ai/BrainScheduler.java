/*
 * Copyright (C) 2026 txcxgzs
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package cn.shabaoai.companion.ai;

import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 每玩家 Brain 调度器：把「玩家主动消息」与「后台事件」两类唤醒放进
 * <b>双 Lane（前台/后台）独立调度</b>，实现"前台 Brain 真并发越过后台"。
 *
 * <p><b>设计意图</b>：此前玩家消息与后台事件共用单 running 串行——后台 COGNITION_PULSE
 * 正在 DeepSeek 时玩家说话，新请求要等旧 HTTP 返回才开始（响应慢）。本类拆成两个
 * 独立 Lane：
 * <ul>
 *   <li><b>前台 Lane</b>：只承载玩家消息（优先级 100）。FIFO 串行；玩家消息到达时
 *       <b>立即启动</b>，即使后台 DeepSeek 正在跑（真正的"说话即响应"）。</li>
 *   <li><b>后台 Lane</b>：只承载事件。按危险/重要程度分档串行；仅当前台 Lane 空闲时
 *       才启动——避免两个 LLM 决策并发 apply 互相覆盖 Goal。</li>
 * </ul>
 *
 * <p><b>优先级</b>（后台 Lane 内：越大越先执行；同优先级按到达顺序 FIFO，靠 seq 保证）：
 * <pre>
 *   PLAYER_MESSAGE 100  玩家说话（前台 Lane，可作废并丢弃全部未执行后台任务）
 *   PLAYER_HURT     95  玩家受伤
 *   DANGER          90  检测到危险
 *   WAIT_DONE       70  等待结束
 *   STALLED         60  卡住
 *   REPLAN_REQUIRED 50  目标长期无进展需重规划
 *   GOAL_DONE       30  目标完成
 *   SOCIAL_IDLE     10  社交空闲搭话（最低，玩家一说话即被丢弃）
 * </pre>
 *
 * <p><b>世代失效（epoch/generation）</b>：玩家消息入队时该玩家 epoch+1。
 * 排队中未启动的后台任务启动前检查自身 epoch（过期直接跳过，省一次模型调用）；
 * 正在请求中的旧后台任务结果返回后由 thinkOnce 的"stillCurrent 检查"在 apply 前丢弃——
 * 旧 SOCIAL_IDLE/REPLAN/COGNITION_PULSE 的决策绝不会在玩家说话之后还修改状态。
 *
 * <p><b>行为规则</b>：
 * <ul>
 *   <li>前台消息到达：epoch++、丢弃队列中未执行的后台任务；前台立即启动（不等后台）。
 *       正在跑的后台结果落地前 isStale 检查失败 → 丢弃（前台"越过"后台）</li>
 *   <li>后台任务启动条件：后台 Lane 空闲 且 前台 Lane 空闲（running 与队列都空）</li>
 *   <li>同类型合并：后台事件若已在队列中未执行（同 dedupKey），不重复入队，防队列爆炸</li>
 *   <li>前台消息间按 FIFO 串行，且互不作废（有效性只由 controlEpoch 控制，
 *       见 AgentRuntime.schedulePlayerMessage）——连发消息不会误杀前一条</li>
 * </ul>
 *
 * <p>线程安全：Slot 内 {@code fgQueue}/{@code bgQueue}/{@code fgRunning}/
 * {@code bgRunning}/{@code epoch} 通过 synchronized(slot) 保护；任务执行用
 * {@link CompletableFuture#runAsync}（LLM 决策不得阻塞调用线程，任务内部自行负责
 * 把 Minecraft 副作用回主线程）。
 */
public final class BrainScheduler {
    private static final ExecutorService FOREGROUND_EXECUTOR = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "shabao-ai-brain-foreground");
        t.setDaemon(true);
        return t;
    });
    private static final ExecutorService BACKGROUND_EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "shabao-ai-brain-background");
        t.setDaemon(true);
        return t;
    });
    /** 优先级常量（越大越优先） */
    public static final int P_PLAYER_MESSAGE = 100;
    public static final int P_PLAYER_HURT = 95;
    public static final int P_DANGER = 90;
    public static final int P_WAIT_DONE = 70;
    public static final int P_STALLED = 60;
    public static final int P_REPLAN = 50;
    public static final int P_GOAL_DONE = 30;
    public static final int P_COGNITION = 20; // 认知脉冲：高于社交搭话(10)、低于目标完成(30)
    public static final int P_SOCIAL_IDLE = 10;

    /** 全局任务序号：同优先级按入队顺序 FIFO */
    private static final AtomicLong SEQ = new AtomicLong();

    /**
     * 一个待执行 Brain 任务。
     * task 接收创建时刻的 epoch 快照（供 thinkOnce 构造 stillCurrent 校验），
     * 返回「主线程动作应用完成」的 future——调度器等到它完成才释放 slot。
     */
    private static final class Job {
        final int priority;
        /** 入队序号（FIFO 用） */
        final long seq;
        /** 创建时的 epoch 快照：过期（玩家有新输入）则跳过/丢弃结果 */
        final long epoch;
        final Function<Long, CompletableFuture<Void>> task;
        /** 同类型合并键（如事件类型名）；null 表示不合并 */
        final String dedupKey;

        Job(int priority, long seq, long epoch, Function<Long, CompletableFuture<Void>> task, String dedupKey) {
            this.priority = priority;
            this.seq = seq;
            this.epoch = epoch;
            this.task = task;
            this.dedupKey = dedupKey;
        }
    }

    /**
     * 单玩家调度槽：<b>双 Lane（前台/后台）独立 running + 独立队列</b>。
     * <ul>
     *   <li><b>前台 Lane</b>（fgRunning + fgQueue）：只承载玩家消息（优先级 100）。
     *       FIFO 串行；<b>永不等待后台</b>——玩家消息到达时即使后台 DeepSeek 正在跑，
     *       前台也立即启动（真正的"玩家说话立即响应"）。</li>
     *   <li><b>后台 Lane</b>（bgRunning + bgQueue）：只承载事件（DANGER/WAIT_DONE/
     *       COGNITION_PULSE/SOCIAL_IDLE 等）。按优先级+seq 串行；<b>仅在前台 Lane
     *       空闲时才启动</b>——防止两个 LLM 决策并发 apply 互相覆盖 Goal。</li>
     *   <li><b>epoch 世代</b>：玩家消息入队时 +1。正在跑/已排队后台任务在启动前与
     *       结果落地前校验自己创建时的 epoch——玩家消息一来，旧后台全部作废
     *       （前台"越过"后台，后台结果回来直接丢弃，绝不 apply）。</li>
     * </ul>
     * 并发安全：队列与 running 标志全部在 synchronized(slot) 内读写；
     * 任务执行体在锁外 runAsync（LLM 请求不阻塞调用线程）。
     */
    private static final class Slot {
        /** 前台 Lane：玩家消息是否正在执行 */
        final AtomicBoolean fgRunning = new AtomicBoolean(false);
        /** 后台 Lane：事件是否正在执行 */
        final AtomicBoolean bgRunning = new AtomicBoolean(false);
        String bgRunningKey;
        /** 世代号：玩家消息入队时 +1；后台任务按创建时的 epoch 校验是否过期 */
        long epoch;
        /** 前台队列：玩家消息按入队顺序 FIFO（同优先级 100，seq 升序） */
        final PriorityQueue<Job> fgQueue = new PriorityQueue<>(
                (a, b) -> Long.compare(a.seq, b.seq));
        /** 后台队列：高优先级在前、同优先级按 seq FIFO */
        final PriorityQueue<Job> bgQueue = new PriorityQueue<>((a, b) ->
                a.priority != b.priority ? b.priority - a.priority : Long.compare(a.seq, b.seq));
    }

    /** 玩家 UUID → 调度槽（长期驻留；玩家退出清理属 P2，暂不实现） */
    private final ConcurrentHashMap<UUID, Slot> slots = new ConcurrentHashMap<>();

    /** 构造包内可见：仅允许同包的 AgentRuntime 创建 */
    BrainScheduler() {}

    /**
     * 排队一个 Brain 任务（任意线程可调）。
     *
     * @param player   目标玩家
     * @param priority 优先级（见 P_* 常量）；≥P_PLAYER_MESSAGE 视为玩家主动消息 → 走前台 Lane：
     *                 该玩家 epoch+1（正在跑/已排队的旧后台任务全部作废）、丢弃未执行后台任务；
     *                 否则走后台 Lane（按优先级排队）。
     * @param task     执行体：接收创建时的 epoch 快照，进行 LLM 决策，返回
     *                 「主线程动作应用完成」的 future（thinkOnce 的实现契约）；
     *                 调度器等到该 future 完成才启动下一个同 Lane 任务
     * @param dedupKey 同类型合并键（后台队列中已有未执行的同键任务则跳过本次）；null 不合并
     */
    public void schedule(UUID player, int priority, Function<Long, CompletableFuture<Void>> task, String dedupKey) {
        if (player == null || task == null) return;
        Slot slot = slots.computeIfAbsent(player, k -> new Slot());
        boolean foreground;
        long epochSnapshot;
        synchronized (slot) {
            if (priority >= P_PLAYER_MESSAGE) {
                // 玩家消息：世代号 +1，正在跑的后台结果落地前会因 isStale 被丢弃；
                // 并丢弃队列中未执行的【后台任务】（玩家诉求优先）。
                // 已在排队/正在跑的前台消息保留——连发消息按 FIFO 串行处理，
                // 不能出现"后一条把前一条还没执行的消息清掉"导致玩家诉求丢失。
                foreground = true;
                slot.epoch++;
                slot.bgQueue.clear();
                epochSnapshot = slot.epoch;
                slot.fgQueue.add(new Job(priority, SEQ.incrementAndGet(), epochSnapshot, task, null));
            } else {
                foreground = false;
                if (dedupKey != null) {
                    if (dedupKey.equals(slot.bgRunningKey)) return;
                    for (Job j : slot.bgQueue) {
                        if (dedupKey.equals(j.dedupKey)) return;
                    }
                }
                epochSnapshot = slot.epoch;
                slot.bgQueue.add(new Job(priority, SEQ.incrementAndGet(), epochSnapshot, task, dedupKey));
            }
        }
        if (foreground) {
            startNextFg(player, slot);
        } else {
            startNextBg(player, slot);
        }
    }

    /**
     * 启动前台 Lane 的下一个玩家消息（FIFO，恒执行）。
     * 前台消息不校验 epoch——玩家连发两条普通消息，第一条落地前 epoch 已被第二条 +1，
     * 若按后台规则校验会误杀第一条（玩家诉求丢失）。前台消息的有效性由
     * AgentRuntime.controlEpoch（控制输入才递增）在 thinkOnce 落地前单独校验。
     */
    private void startNextFg(UUID player, Slot slot) {
        Job job;
        synchronized (slot) {
            if (slot.fgRunning.get()) return; // 已有玩家消息在跑
            job = slot.fgQueue.poll();
            if (job == null) return;
            slot.fgRunning.set(true);
        }
        runJob(player, slot, job, true);
    }

    /**
     * 启动后台 Lane 的下一个事件任务。
     * <ul>
     *   <li><b>世代校验</b>：后台任务若创建后玩家发过新消息（epoch 已变）→ 直接跳过
     *       （省一次模型调用），继续取下一个</li>
     *   <li><b>前台优先</b>：前台 Lane 有任务在跑或排队时后台不启动——避免两个 LLM 决策
     *       并发 apply 互相覆盖 Goal。前台完成后会触发本方法补启动。</li>
     * </ul>
     */
    private void startNextBg(UUID player, Slot slot) {
        Job job;
        synchronized (slot) {
            if (slot.bgRunning.get()) return;              // 已有后台任务在跑
            if (slot.fgRunning.get() || !slot.fgQueue.isEmpty()) return; // 前台活跃，后台等待
            job = slot.bgQueue.poll();
            while (job != null && job.epoch != slot.epoch) {
                AgentLogger.logInfo("Brain 后台任务已过期跳过: 玩家=" + player + " 优先级=" + job.priority);
                job = slot.bgQueue.poll();
            }
            if (job == null) return;
            // 后台任务世代过期：玩家已发新消息，跳过（继续取下一个）
            slot.bgRunning.set(true);
            slot.bgRunningKey = job.dedupKey;
        }
        runJob(player, slot, job, false);
    }

    /**
     * 执行一个 Brain 任务（Lane 由调用方标记）：runAsync 发 LLM 请求，等待
     * 「主线程动作应用完成」（thinkOnce 的实现契约）后才释放对应 Lane 并触发
     * 双 Lane 的补启动——前台完成后要让排队后台接力，后台完成后要让排队前台立即跑。
     */
    private void runJob(UUID player, Slot slot, Job toRun, boolean foreground) {
        CompletableFuture.runAsync(() -> {
            CompletableFuture<Void> applyDone;
            try {
                applyDone = toRun.task.apply(toRun.epoch); // 传 epoch 快照供 stillCurrent 校验
            } catch (Exception e) {
                AgentLogger.logError(0, "Brain 任务启动异常: 玩家=" + player + " 错误=" + e);
                applyDone = null;
            }
            if (applyDone == null) applyDone = CompletableFuture.completedFuture(null);
            applyDone.whenComplete((r, e) -> {
                if (e != null) {
                    AgentLogger.logError(0, "Brain 任务执行异常: 玩家=" + player + " 错误=" + e);
                }
                synchronized (slot) {
                    if (foreground) {
                        slot.fgRunning.set(false);
                    } else {
                        slot.bgRunning.set(false);
                        slot.bgRunningKey = null;
                    }
                }
                startNextFg(player, slot);
                startNextBg(player, slot);
            });
        }, foreground ? FOREGROUND_EXECUTOR : BACKGROUND_EXECUTOR);
    }

    /**
     * 使该玩家的全部未完成后台任务失效（本地意图直连操作时调用，如"别跟了"）。
     * 玩家消息的 schedule 内部也会递增 epoch；本方法供不经过 schedule 的
     * 本地意图路径使用，保证"任何玩家主动输入都会作废旧后台 Brain 的结果"。
     */
    public void invalidateBackground(UUID player) {
        if (player == null) return;
        Slot slot = slots.get(player);
        if (slot == null) return;
        synchronized (slot) {
            slot.epoch++;
        }
    }

    /** 该玩家的 epoch 是否已不是给定快照（任务过期）——thinkOnce 的 stillCurrent 校验用 */
    public boolean isStale(UUID player, long epochSnapshot) {
        Slot slot = slots.get(player);
        if (slot == null) return true; // 玩家从未调度过：视为过期（防御）
        synchronized (slot) {
            return slot.epoch != epochSnapshot;
        }
    }

    /** 该玩家当前是否正有 Brain 任务在跑（前台或后台任一 Lane 活跃即视为 busy） */
    public boolean isBusy(UUID player) {
        Slot slot = slots.get(player);
        return slot != null && (slot.fgRunning.get() || slot.bgRunning.get());
    }

    /**
     * 【stop 急停】清空该玩家未执行的前台/后台任务队列。
     *
     * <p>正在运行的 Brain 无法取消（LLM 请求已发出），靠 controlEpoch/epoch 在落地前
     * 作废结果；本方法只清<b>还没启动</b>的排队任务，防止 stop 后旧诉求继续被处理。
     */
    public void clearQueues(UUID player) {
        if (player == null) return;
        Slot slot = slots.get(player);
        if (slot == null) return;
        synchronized (slot) {
            slot.fgQueue.clear();
            slot.bgQueue.clear();
        }
    }

    /** 【玩家断开清理】移除该玩家的调度槽（未执行任务一并丢弃） */
    public void removePlayer(UUID player) {
        if (player == null) return;
        Slot slot = slots.remove(player);
        if (slot != null) {
            synchronized (slot) {
                slot.epoch++;
                slot.fgQueue.clear();
                slot.bgQueue.clear();
            }
        }
    }

    /** 【世界退出清理】清空全部调度槽（服务器关闭用） */
    public void clearAll() {
        for (Slot slot : slots.values()) {
            synchronized (slot) {
                slot.epoch++;
                slot.fgQueue.clear();
                slot.bgQueue.clear();
            }
        }
        slots.clear();
    }
}
