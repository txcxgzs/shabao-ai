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

import cn.shabaoai.companion.ai.skill.FollowSkill;
import cn.shabaoai.companion.ai.skill.SafetySkill;
import cn.shabaoai.companion.ai.skill.TorchAssistSkill;
import cn.shabaoai.companion.entity.CompanionManager;
import com.google.gson.JsonObject;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 常驻 Agent Runtime：服务端每 tick 驱动的常驻调度器（单例）。
 *
 * <p><b>设计意图</b>：AI 队友不是"只响应聊天"的请求-响应机器，而是一个
 * <b>常驻自治循环</b>——即使玩家不说话，它也在按节拍观察世界、执行本地技能、
 * 推进目标状态机，只有碰到值得惊动 LLM 的事件（危险/受伤/卡住/等待结束/
 * 目标完成/社交空闲/需要重新规划）才走 {@link EventBus} 唤醒 Brain
 * （AgentExecutor.thinkOnce，一次性 LLM 决策）。
 *
 * <p><b>维度安全</b>：本类只接收 {@link MinecraftServer}（每 Server tick 调用一次，
 * 由 ShabaoAiMod 接线），所有世界访问一律通过玩家实体 {@code player.getServerWorld()}
 * 取得——玩家在下界就在下界检测/放方块，绝不会拿主世界坐标去操作别的维度。
 *
 * <p><b>心跳分层</b>（节流时间戳防高频空转）：
 * <ul>
 *   <li>250ms：刷新在线玩家状态缓存（位置/血量/洞穴判定）——快，但只做观察</li>
 *   <li>1s：对每个在线玩家跑技能（多通道并行）+ 目标状态机推进 + WAIT_DONE/GOAL_DONE 检测</li>
 *   <li>20s：maybeReplan（目标长期无进展→REPLAN_REQUIRED；玩家长期无交流→SOCIAL_IDLE）</li>
 *   <li>每 tick：消费事件队列（事件才是唤醒 LLM 的触发器）</li>
 * </ul>
 */
public final class AgentRuntime {
    /** 全局单例 */
    private static final AgentRuntime INSTANCE = new AgentRuntime();

    /** 事件总线：本地循环产出的"要不要唤醒 LLM"触发器 */
    private final EventBus events = new EventBus();
    /** 目标状态机：按玩家维度管理当前 goal */
    private final GoalManager goals = new GoalManager();
    /** 技能调度：本地高频行为（0 次 LLM） */
    private final SkillManager skills = new SkillManager();
    /** 与 Goal 正交的身体互动状态机（牵手/拥抱/亲吻），每 server tick 驱动。 */
    private final InteractionManager interactions = InteractionManager.get();
    /** 玩家状态缓存：UUID → 最近一次采样的位置/血量/洞穴标记 */
    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();
    /** 【Task 5】事件去重：playerUuid|type → 上次唤醒时间戳（15 秒内同玩家同类型事件不重复唤醒 LLM） */
    private final Map<String, Long> lastWake = new ConcurrentHashMap<>();
    /** 【Task 5】wait_until(player_moved) 参考位置：UUID → markWaiting 时的玩家坐标 {x,y,z}，供条件检查对比 */
    private final Map<UUID, double[]> waitRefPos = new ConcurrentHashMap<>();
    /** 每玩家 Brain 调度器：统一「玩家消息 + 后台事件」的 LLM 决策，保证同玩家同时最多一个 Brain */
    private final BrainScheduler brain = new BrainScheduler();
    /** 世界变化累积器：认知循环（BrainPulse）的 novelty 数据源 */
    private final WorldChangeAccumulator worldChanges = new WorldChangeAccumulator();
    /** 玩家 UUID → 最近一次 Brain（LLM 决策）时间戳：社交搭话/重规划要避开刚思考完的玩家 */
    private final Map<UUID, Long> lastBrainAt = new ConcurrentHashMap<>();
    /** 玩家 UUID → 上次 SOCIAL_IDLE 触发时间戳（防 5 分钟一次的搭话频率被 20s 循环反复满足） */
    private final Map<UUID, Long> lastSocialAt = new ConcurrentHashMap<>();
    /** 玩家 UUID → 上次检测到的 goal 状态：用于 WAITING→RUNNING / →COMPLETED/FAILED 转变检测 */
    private final Map<UUID, GoalManager.Status> lastGoalStatus = new ConcurrentHashMap<>();
    /**
     * 【P0-急停世代】玩家控制输入世代号：任何控制类输入（stop 口令等）都 ++。
     * 所有 LLM 请求（前台玩家消息 + 后台事件）创建时快照它，落地前校验——
     * 校验失败说明玩家在请求期间下了新控制指令，旧 AI 决策（哪怕正在跑的玩家消息 Brain）
     * 一律丢弃。保证"玩家最新指令永远不可被旧 AI 决策覆盖"。
     */
    private final Map<UUID, Long> controlEpoch = new ConcurrentHashMap<>();

    /** 心跳节流时间戳（epoch ms；tick 仅主线程调用，无需 volatile） */
    private long lastFast, lastSkill, lastReplan, lastPulse;

    /** 玩家状态快照（技能层读取，避免每 tick 重复查世界） */
    public record PlayerState(double x, double y, double z, float health, boolean inCave, long updatedAt) {}

    /** 认知脉冲唤醒阈值：novelty score 达到该值才唤醒 Brain（变化不足则延后） */
    private static final int COGNITION_WAKE_THRESHOLD = 3;

    /** 重规划判定阈值（ms）：goal 连续无进展超过 2 分钟才考虑重新规划 */
    private static final long REPLAN_IDLE_MS = 120_000;
    /** 社交搭话最短间隔（ms）：距上次搭话 5 分钟以上才可能主动开口 */
    private static final long SOCIAL_IDLE_MS = 300_000;
    /** 社交搭话对 Brain 的冷却（ms）：距上次 LLM 思考不足 2 分钟的玩家不搭话 */
    private static final long SOCIAL_BRAIN_MIN_MS = 120_000;

    /** 私有构造：单例，注册本地技能 */
    private AgentRuntime() {
        registerSkills();
    }

    /**
     * 注册本地技能（0 次 LLM 调用）：实例化 {@code ai.skill} 包下的三个技能
     * 并注册进 {@link SkillManager}，由调度器按通道并行调度。
     */
    private void registerSkills() {
        skills.register(new FollowSkill());
        skills.register(new TorchAssistSkill());
        skills.register(new SafetySkill());
    }

    /** 获取全局单例 */
    public static AgentRuntime get() {
        return INSTANCE;
    }

    /**
     * 【急停/取消统一入口】取消当前 goal + 关闭跟随 + 释放 Goal 关联技能。
     *
     * <p>Fast Path（"别跟了"）与 Runtime Tool（cancel_goal）必须走同一套清理，
     * 不能写两套逻辑——否则"Fast Path 停了跟随但 torch_place 残留"这类不一致必然回归。
     */
    public void cancelGoalAndCleanup(UUID player) {
        if (player == null) return;
        interactions.cancel(player, "goal_cancelled", false);
        goals.cancel(player);
        // 关闭实体跟随：内部立即 navigation.stop()，下一 tick 级物理急停
        CompanionManager.setFollowEnabled(player, false);
        // 释放 Goal 关联技能（如 mine_assist 自动开的 torch_place）；手动技能不受影响
        skills.releaseGoalSkills(player);
    }

    /**
     * 【世界退出清理】服务器关闭时清空全部 Runtime 状态（目标/技能/脑调度/世界变化累积/缓存）。
     * 同 JVM 退出到主菜单再进新世界时，旧世界状态（尤其 torch_place 常驻）绝不残留。
     */
    public void clearAll() {
        events.clear();
        ConfirmationManager.cancelAll();
        for (UUID uid : new HashSet<>(controlEpoch.keySet())) bumpControlEpoch(uid);
        EpisodicMemory.get().closeAll("server_stopped");
        interactions.clearAll();
        RelationshipManager.get().clearCache();
        goals.clearAll();
        skills.clearAll();
        brain.clearAll();
        worldChanges.clearAll();
        waitRefPos.clear();
        states.clear();
        lastWake.clear();
        lastBrainAt.clear();
        lastSocialAt.clear();
        lastGoalStatus.clear();
        // 不清 controlEpoch：仍在飞行中的前台 Future 必须看到世代变化，不能跨世界落地。
        AgentLogger.logInfo("AgentRuntime 全量清理完成（服务器关闭）");
    }

    /** 【玩家断开清理】下线玩家离开游戏：清该玩家的 goal/技能/脑调度/缓存 */
    public void cleanup(UUID player) {
        if (player == null) return;
        interactions.cancel(player, "player_disconnected", false);
        invalidateBackgroundBrain(player);
        bumpControlEpoch(player);
        brain.clearQueues(player);
        ConfirmationManager.cancel(player);
        EpisodicMemory.get().closePlayer(player, "player_disconnected");
        goals.cancel(player);
        skills.clearPlayer(player);
        brain.removePlayer(player);
        worldChanges.removePlayer(player);
        waitRefPos.remove(player);
        states.remove(player);
        lastBrainAt.remove(player);
        lastSocialAt.remove(player);
        lastGoalStatus.remove(player);
        // 保留递增后的 controlEpoch；删除会让快照为 0 的旧任务重新变成有效。
        // lastWake 的 key 是 "playerUuid|type" 字符串：按前缀删掉该玩家的所有事件去重记录
        String prefix = player.toString() + "|";
        lastWake.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public EventBus events() {
        return events;
    }

    public GoalManager goals() {
        return goals;
    }

    public SkillManager skills() {
        return skills;
    }

    /** 返回玩家缓存状态（从未采样返回 null） */
    public PlayerState state(UUID player) {
        return states.get(player);
    }

    /** 记录一次 Brain 决策时间（thinkOnce 入口调用，社交搭话/重规划用它做冷却判定） */
    public void noteBrainActivity(UUID player) {
        if (player != null) lastBrainAt.put(player, System.currentTimeMillis());
    }

    /**
     * 服务端每 tick 调用（由 ShabaoAiMod 接线，每 Server tick 一次，不按世界遍历）。
     * 分层心跳：250ms 刷新状态缓存、1s 跑技能+目标状态机、20s 低频 replan/社交检查、
     * 每 tick 消费事件队列。所有世界访问以玩家自身维度为准。
     */
    public void tick(MinecraftServer server) {
        if (server == null) return;
        long now = System.currentTimeMillis();
        interactions.tick(server);
        List<ServerPlayerEntity> online = server.getPlayerManager().getPlayerList();
        // 250ms：刷新在线玩家状态缓存（位置/血量/头顶遮挡判断 inCave）
        if (now - lastFast > 250) {
            lastFast = now;
            for (ServerPlayerEntity p : online) refreshState(p);
        }
        // 1s：先做 wait_until 条件检查（player_moved / player_distance>N 等），
        // 再对每个在线玩家跑技能（多通道并行）+ 目标状态机推进 + 状态转变检测（WAIT_DONE/GOAL_DONE）
        // + 世界变化累积（认知循环数据源）+ 认知脉冲检查（ADAPTIVE/ACTIVE 周期性自检）
        if (now - lastSkill > 1000) {
            lastSkill = now;
            waitUntilCheck(server, now);
            for (ServerPlayerEntity p : online) {
                CompanionManager.ensureSameWorld(p);
                skills.tick(p, goals);
                // 累积"上次思考之后世界发生了什么"（BrainPulse 的 novelty 数据源）
                worldChanges.accumulate(p.getUuid(), states.get(p.getUuid()), p);
            }
            goals.tick(now);
            checkGoalTransitions(server, now);
            EpisodicMemory.get().tick(server);
            brainPulse(server, now);
        }
        // 20s：低频 replan 检查（goal 长期无进展→REPLAN_REQUIRED）+ 社交空闲检查（→SOCIAL_IDLE）
        if (now - lastReplan > 20000) {
            lastReplan = now;
            maybeReplan(server, now);
        }
        // 每 tick：消费事件（事件才是唤醒 LLM 的触发器）
        drainEvents(server);
    }

    /**
     * 事件消费：DANGER / PLAYER_HURT / STALLED / WAIT_DONE / GOAL_DONE /
     * SOCIAL_IDLE / REPLAN_REQUIRED 等 → 交给 {@link BrainScheduler} 唤醒 Brain（一次 LLM 决策）。
     *
     * <p>PLAYER_MESSAGE 跳过队列——玩家消息走 {@link #onPlayerMessage} 独立通道，
     * 不进事件队列，避免消息与本地事件互相挤占/双重唤醒。
     * 去重：同一玩家 + 同一事件类型 15 秒内只唤醒一次。
     * 并发：由 BrainScheduler 统一串行（同玩家同时最多一个 Brain；玩家消息最高优先级，
     * 到达时丢弃队列中未执行的低优先级后台任务——后台事件不会与玩家诉求并发打架）。
     */
    private void drainEvents(MinecraftServer server) {
        if (server == null) return;
        EventBus.Ev ev;
        while ((ev = events.poll()) != null) {
            try {
                // 玩家消息走 onPlayerMessage 通道，不在此处唤醒
                if (ev.type() == EventBus.Type.PLAYER_MESSAGE) {
                    AgentLogger.logInfo("事件[PLAYER_MESSAGE] 已跳过队列（消息走 onPlayerMessage 通道）");
                    continue;
                }
                // 15 秒去重：同玩家同类型事件不重复唤醒。
                // 认知脉冲（COGNITION_PULSE）豁免：其频率已由 CognitionPolicy 的
                // min_interval/next_think 精确控制（≥2s），再套 15s 去重会把
                // ADAPTIVE 的"重大变化立即醒"压制为每 15 秒最多一次。
                if (ev.type() != EventBus.Type.COGNITION_PULSE) {
                    String key = ev.player() + "|" + ev.type();
                    long now = System.currentTimeMillis();
                    Long last = lastWake.get(key);
                    if (last != null && now - last < 15_000) continue;
                    lastWake.put(key, now);
                }
                wakeBrain(server, ev);
            } catch (Exception e) {
                // 单条事件处理失败不能拖垮 tick 循环
                AgentLogger.logError(0, "drainEvents 处理事件异常: " + e);
            }
        }
    }

    /**
     * 唤醒一次 Brain：伙伴过滤后交给 {@link BrainScheduler} 按事件类型优先级排队。
     * 无伙伴的玩家直接跳过（不花 LLM 调用）；同类型事件未执行时自动合并防队列膨胀。
     */
    private void wakeBrain(MinecraftServer server, EventBus.Ev ev) {
        try {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(ev.player());
            if (player == null) return; // 离线跳过
            EpisodicMemory.Type memoryType = switch (ev.type()) {
                case DANGER -> EpisodicMemory.Type.DANGER;
                case PLAYER_HURT -> EpisodicMemory.Type.PLAYER_HURT;
                default -> EpisodicMemory.Type.WORLD_EVENT;
            };
            EpisodicMemory.get().record(player, memoryType, ev.type().name().toLowerCase(),
                    EpisodicMemory.Source.RUNTIME, null, "observed", ev.data(), null);
            // 伙伴过滤：没有召唤沙包的玩家不唤醒 LLM（本地技能可以跑，但 LLM 决策不花）
            if (!CompanionManager.exists(ev.player())) {
                AgentLogger.logInfo("事件[" + ev.type() + "] 玩家 " + ev.player() + " 没有沙包伙伴，跳过唤醒");
                return;
            }
            lastBrainAt.put(ev.player(), System.currentTimeMillis());
            EventBus.Type type = ev.type();
            String data = ev.data();
            // 【P0-后台禁 fallback·方案A】COGNITION_PULSE 唤醒时由 Runtime 直接附上轻量环境摘要，
            // 让后台认知一轮 LLM 就能掌握"位置/维度/地下/怪物/光照/伙伴距离"——
            // 大多数情况下根本不需要再调 query/scan（也就不会触发 legacy fallback）。
            // 只给 COGNITION_PULSE 附加：DANGER/WAIT_DONE 等事件自身已带足够上下文，省 token。
            if (type == EventBus.Type.COGNITION_PULSE) {
                data = data + "\n\n[当前环境摘要]\n" + backgroundEnvSummary(player);
            }
            // lambda 需要 effectively final：传给 thinkOnce 的摘要用独立 final 引用
            final String brainInput = data;
            final GoalManager.Goal goalSnapshot = goals.get(ev.player());
            // BrainScheduler 统一串行：同玩家同时最多一个 Brain，按事件类型分优先级排队。
            // 后台任务带世代校验（stillCurrent）：玩家发出新输入后，旧任务的决策结果不落地
            brain.schedule(ev.player(), priorityOf(type),
                    epoch -> cn.shabaoai.companion.ShabaoAiMod.AGENT.thinkOnce(player, brainInput, type, this,
                            () -> !brain.isStale(ev.player(), epoch)
                                    && eventStillRelevant(ev.player(), type, goalSnapshot)),
                    type.name());
        } catch (Exception e) {
            AgentLogger.logError(0, "wakeBrain 异常: " + e);
        }
    }

    private boolean eventStillRelevant(UUID player, EventBus.Type type, GoalManager.Goal goalSnapshot) {
        return switch (type) {
            case COGNITION_PULSE, REPLAN_REQUIRED, WAIT_DONE, GOAL_DONE ->
                    goalSnapshot != null && goals.get(player) == goalSnapshot;
            default -> true;
        };
    }

    /**
     * 【P0-后台禁 fallback·方案A】构造后台认知用的轻量环境摘要（主线程调用）。
     *
     * <p>字段刻意精简：位置 / 维度 / 地下地表 / 血量 / 附近敌对数量 / 光照 / 伙伴距离——
     * 足够认知决策，但比 EnvironmentScanner.snapshot 轻（不扫地形、不算地基、不探门位）。
     * 每 8~30 秒一次的后台 Pulse 不该背着完整环境快照的 token 开销。
     */
    private String backgroundEnvSummary(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        BlockPos pos = player.getBlockPos();
        StringBuilder sb = new StringBuilder();
        sb.append("位置=").append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ());
        sb.append(" 维度=").append(world.getRegistryKey().getValue().toString());
        sb.append(" 地下=").append(!world.isSkyVisible(pos));
        sb.append(" 血量=").append(Math.round(player.getHealth()));
        // 附近敌对数量（24 格内，与 WorldChangeAccumulator 同半径）
        try {
            List<HostileEntity> hostiles = world.getEntitiesByClass(HostileEntity.class,
                    new Box(pos).expand(24.0), e -> !e.isRemoved());
            sb.append(" 附近敌对=").append(hostiles.size());
        } catch (Exception ignored) {
            // 扫描失败不影响摘要（世界卸载等边界）
        }
        // 光照等级（方块光照：洞穴黑暗 / 地表白天一眼可辨）
        try {
            sb.append(" 光照=").append(world.getLightLevel(net.minecraft.world.LightType.BLOCK, pos));
        } catch (Exception ignored) {
        }
        // 伙伴距离（CompanionEntity 缺失时跳过）
        try {
            var companion = CompanionManager.get(player.getUuid());
            if (companion != null && !companion.isRemoved()) {
                sb.append(" 伙伴距离=").append(Math.round(player.distanceTo(companion)));
            }
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    /**
     * 【统一 Brain 调度】玩家消息需要 LLM 决策时走这里（最高优先级）。
     * BrainScheduler 会丢弃队列中未执行的 SOCIAL_IDLE/REPLAN 等低优先级后台任务
     * （玩家诉求优先）；正在执行的后台 Brain 无法中途取消，玩家任务排在它之后第一个执行。
     */
    public void schedulePlayerMessage(ServerPlayerEntity player, String message) {
        if (player == null || player.getServer() == null) return;
        UUID uid = player.getUuid();
        controlEpoch.putIfAbsent(uid, 0L);
        lastBrainAt.put(uid, System.currentTimeMillis());
        // 【P0-急停世代】玩家消息任务走前台 Lane，只校验 controlEpoch（控制输入才递增）：
        //  - 玩家控制输入（站住/别跟了/换目标）→ controlEpoch++ → 本消息落地前丢弃（急停优先）；
        //  - 普通连发消息不 bump controlEpoch → 按 FIFO 串行都正常执行（不互相误杀）。
        // 注意【不要】在这里查 brain.isStale(epoch)：玩家消息入队会使 slot.epoch++，
        // 连发两条普通消息时第一条落地前 epoch 已变，查 isStale 会把第一条误作废。
        long ceSnapshot = controlEpochSnapshot(uid);
        brain.schedule(uid, BrainScheduler.P_PLAYER_MESSAGE,
                epoch -> cn.shabaoai.companion.ShabaoAiMod.AGENT.thinkOnce(player, message, null, this,
                        () -> !controlEpochChanged(uid, ceSnapshot)),
                null);
    }

    /** 使该玩家的全部未完成后台 Brain 失效（hardStop 急停/目标清理时调用） */
    public void invalidateBackgroundBrain(UUID player) {
        brain.invalidateBackground(player);
    }

    /** 【P0-急停世代】递增该玩家的控制输入世代号：作废所有未落地的 LLM 决策（含正在跑的前台消息 Brain） */
    public void bumpControlEpoch(UUID player) {
        if (player == null) return;
        controlEpoch.merge(player, 1L, Long::sum);
    }

    /** 当前控制输入世代号（LLM 请求创建时快照用；无记录视为 0） */
    public long controlEpochSnapshot(UUID player) {
        return player == null ? 0L : controlEpoch.getOrDefault(player, 0L);
    }

    /** 快照后是否有新的控制输入（落地前校验：变了 → 旧决策必须丢弃） */
    public boolean controlEpochChanged(UUID player, long snapshot) {
        return player == null || controlEpoch.getOrDefault(player, 0L) != snapshot;
    }

    /**
     * 【框架唯一急停口令 stop】无条件硬抢断：作废所有未落地决策 + 停止身体动作。
     *
     * <p>这是框架层唯一认识的控制输入（玩家输入精确等于 "stop" 触发），其余自然语言
     * 全部交给 LLM 理解（"框架只认识 stop，AI 认识人话"）。相比关键词 Fast Path：
     * <ul>
     *   <li><b>前台 Brain</b>：controlEpoch++ → 正在跑/已排队的旧前台决策落地前被丢弃</li>
     *   <li><b>后台 Brain</b>：brain epoch++ → 后台事件任务全部作废</li>
     *   <li><b>legacy Agent</b>：stillValid 令牌检查 controlEpochChanged → 下一检查点中断</li>
     *   <li><b>队列</b>：清空未执行的 fg/bg 任务</li>
     *   <li><b>身体</b>：取消 goal + 关闭跟随（navigation.stop 物理急停）+ 释放 Goal 技能</li>
     * </ul>
     */
    public void hardStop(UUID player) {
        if (player == null) return;
        invalidateBackgroundBrain(player);
        bumpControlEpoch(player);
        brain.clearQueues(player);
        ConfirmationManager.cancel(player);
        // 取消 goal + 关跟随急停 + 释放 Goal 关联技能（与 cancel_goal 同一套清理）
        cancelGoalAndCleanup(player);
        AgentLogger.logInfo("HARD_STOP 执行: 玩家=" + player);
    }

    /**
     * 【Goal 切换统一入口】所有入口（LLM set_goal / 等待兜底建目标）必须走这里，
     * 保证生命周期一致：跟随开关按目标类型同步 + mine_assist 自动火把的"Goal 关联技能"随目标
     * 切换自动释放（否则玩家从 mine_assist 换到 follow 时，自动火把会残留）。
     *
     * @param player 目标玩家
     * @param type   目标类型（follow/mine_assist/companion/explore/build/...）
     * @param params 附加参数（可 null）
     */
    public void switchGoal(UUID player, String type, JsonObject params) {
        if (player == null || type == null) return;
        if ("build".equals(type)) interactions.cancel(player, "build_goal_started", false);
        GoalManager.Goal old = goals.get(player);
        String oldType = old == null ? null : old.type;
        goals.set(player, type, params);
        // 需要陪伴移动的目标 → 打开实体跟随；纯建造（build）→ 关闭跟随停下等本地行为调度
        boolean wantsFollow = "follow".equals(type) || "mine_assist".equals(type)
                || "companion".equals(type) || "explore".equals(type);
        CompanionManager.setFollowEnabled(player, wantsFollow);
        // mine_assist 自动开火把（Goal 关联）；离开 mine_assist → 自动释放（手动技能不受影响）
        if ("mine_assist".equals(type)) {
            skills.startGoalOwned(player, "torch_place");
        } else if ("mine_assist".equals(oldType)) {
            skills.releaseGoalSkills(player);
        }
    }

    /**
     * 【MANUAL_WAIT 解除】手动等待（"在这等/别动"）只有玩家说"继续/走吧/回来"才解除：
     * 恢复原 goal 为 RUNNING（跟随还原由 checkGoalTransitions 的 WAITING→RUNNING 统一处理，
     * 并会发 WAIT_DONE 唤醒 Brain 决定后续）。清理 player_moved 参考位置残留。
     */
    public void resumeGoalAfterWait(UUID player) {
        if (player == null) return;
        GoalManager.Goal g = goals.get(player);
        if (g == null || g.status != GoalManager.Status.WAITING) return;
        goals.resume(player);
        waitRefPos.remove(player);
    }

    /** 该玩家当前是否正有 Brain 任务在跑（真正的"AI 正在思考"状态以它为准） */
    public boolean isBrainBusy(UUID player) {
        return brain.isBusy(player);
    }

    /** 事件类型 → Brain 优先级（越大越先执行；玩家消息 100 到达时丢弃队列中未执行的更低优先级任务） */
    private static int priorityOf(EventBus.Type type) {
        return switch (type) {
            case PLAYER_HURT -> BrainScheduler.P_PLAYER_HURT;
            case DANGER -> BrainScheduler.P_DANGER;
            case WAIT_DONE -> BrainScheduler.P_WAIT_DONE;
            case STALLED -> BrainScheduler.P_STALLED;
            case REPLAN_REQUIRED -> BrainScheduler.P_REPLAN;
            case GOAL_DONE -> BrainScheduler.P_GOAL_DONE;
            case COGNITION_PULSE -> BrainScheduler.P_COGNITION; // 认知自检：高于社交搭话、低于目标完成
            case SOCIAL_IDLE -> BrainScheduler.P_SOCIAL_IDLE;
            default -> BrainScheduler.P_PLAYER_MESSAGE; // PLAYER_MESSAGE 不经过队列，防御性兜底
        };
    }

    /**
     * 认知脉冲（BrainPulse）：ADAPTIVE/ACTIVE 认知模式的周期性自检。
     * 每秒评估一次；<b>只有世界变化够大或到硬上限才真正唤醒 Brain</b>，
     * 变化不足就延后下次检查——"持续认知"不是固定频率烧 API。
     *
     * <p>novelty 评分来自 {@link WorldChangeAccumulator}（移动>10格/维度或地下切换/
     * 血量骤降/新敌对/玩家说话）；唤醒后重置累积并重新计时。LOCAL/EVENT 模式不参与。
     */
    private void brainPulse(MinecraftServer server, long now) {
        if (server == null) return;
        for (UUID uid : goals.players()) {
            GoalManager.Goal g = goals.get(uid);
            if (g == null || g.cognition == null) continue;
            if (g.status != GoalManager.Status.RUNNING) continue;
            CognitionPolicy c = g.cognition;
            if (!c.mayPulse()) continue; // LOCAL/EVENT 不主动自检（靠事件）
            if (!c.due(now)) continue;   // 未到下次检查时间
            // 玩家不在线则跳过（认知循环只服务在线玩家）
            if (server.getPlayerManager().getPlayer(uid) == null) continue;
            int score = worldChanges.novelty(uid);
            if (score >= COGNITION_WAKE_THRESHOLD || now >= c.hardMaxAt()) {
                // 世界变化足够大 / 到硬上限：唤醒 Brain 做一次认知思考
                events.publish(EventBus.Type.COGNITION_PULSE, uid, "pulse:score=" + score);
                c.markThought(now);      // 记录本次思考，重置节奏
                worldChanges.reset(uid); // 重新累积变化
                AgentLogger.logInfo("认知脉冲唤醒: 玩家=" + uid + " goal=" + g.type
                        + " mode=" + c.mode() + " novelty=" + score);
            } else {
                // 变化不足：延后到 min_interval 后再看（硬上限兜底，绝不静默丢）
                c.defer(now);
            }
        }
    }

    /**
     * 【Task 5】玩家消息当事件入口（聊天/语音等消息转发接入点）。
     *
     * <p>防篡改检查通过后交给 AgentExecutor.runtimeHandle 做「本地意图匹配 + 必要时
     * 一次 LLM 决策」。异步执行（runAsync）避免 LLM 决策阻塞调用方线程；
     * runtimeHandle 内部对 Minecraft 对象的操作统一回主线程（server.execute）。
     * 并发控制由 BrainScheduler 全权接管（每玩家串行 + 优先级 + 世代失效），
     * 返回的 future 仅代表"消息已进入处理流程"，不代表思考完成。
     * 全限定名引用 ShabaoAiMod.AGENT，避免与 ShabaoAiMod 循环依赖。
     *
     * @return 异步任务句柄（仅表示已受理）；防篡改/玩家为空等拒绝路径返回已完成的 future
     */
    public CompletableFuture<Void> onPlayerMessage(ServerPlayerEntity player, String message) {
        // 【防篡改】篡改锁定后消息入口直接拒绝（spec MODIFIED 需求）
        if (cn.shabaoai.companion.protect.GuardCore.isTampered()) return CompletableFuture.completedFuture(null);
        if (player == null || player.getServer() == null) return CompletableFuture.completedFuture(null);
        EpisodicMemory.get().recordMessage(player, EpisodicMemory.Type.USER_MESSAGE,
                message, EpisodicMemory.Source.PLAYER);
        // 记录玩家开口说话：认知循环的 novelty 评分里"玩家说话=有互动"（越活跃越该想）
        worldChanges.recordPlayerTalk(player.getUuid());
        CompletableFuture<Void> accepted = new CompletableFuture<>();
        Runnable ingest = () -> {
            try {
                cn.shabaoai.companion.ShabaoAiMod.AGENT.runtimeHandle(player, message, this);
                accepted.complete(null);
            } catch (Exception e) {
                AgentLogger.logError(0, "onPlayerMessage 处理异常: " + e);
                accepted.completeExceptionally(e);
            }
        };
        // 先串行进入服务端主线程，保证“任务 → stop”的到达顺序不被公共线程池打乱。
        if (player.getServer().isOnThread()) ingest.run();
        else player.getServer().execute(ingest);
        return accepted;
    }

    /**
     * 【Task 5】记录 wait_until(player_moved) 的参考位置（markWaiting 时调用）。
     *
     * <p>参考位置 = 玩家发出"等我/别动"那一刻的坐标，waitUntilCheck 每秒对比
     * 当前位置，任一轴移动超过 0.5 格即视为"动了"，恢复 goal。
     */
    public void recordWaitRef(ServerPlayerEntity player) {
        if (player == null || player.getServer() == null) return;
        waitRefPos.put(player.getUuid(), new double[]{player.getX(), player.getY(), player.getZ()});
    }

    /**
     * 【P0-WAIT】等待急停：进入 wait/wait_until 时暂停实体跟随。
     *
     * <p>FollowOwnerGoal 只看 followEnabled 不看 Goal.status——只 markWaiting 根本停不住
     * 实体，玩家一走远沙包还会追。本方法把「暂停移动」做成 Runtime 一级状态：
     * <ol>
     *   <li>记录当前跟随开关到 goal.resumeFollowEnabled（等待结束由 WAITING→RUNNING
     *       转换统一还原，不需要 LLM/Prompt 记得）</li>
     *   <li>setFollowEnabled(false) 立即 navigation.stop() 物理急停</li>
     * </ol>
     */
    public void pauseFollowForWait(UUID player) {
        if (player == null) return;
        interactions.cancel(player, "goal_paused", false);
        GoalManager.Goal g = goals.get(player);
        if (g != null) {
            // 只覆盖"进入等待"时的快照；无 goal 时（set_cognition 自动建过 companion）也安全
            g.resumeFollowEnabled = CompanionManager.isFollowEnabled(player);
        }
        CompanionManager.setFollowEnabled(player, false);
    }

    /**
     * 【Task 5】wait_until 条件检查（tick 的 1s 分支调用，goals.tick 之前）。
     *
     * <p>只处理 WAITING 且 waitCondition 非空的 goal（无条件等待由 goals.tick 超时恢复）：
     * <ul>
     *   <li><b>player_moved</b>：玩家位置与 markWaiting 时参考位置不同 → resume</li>
     *   <li><b>player_distance&gt;N</b>：玩家与 AI 距离超过 N 格 → resume</li>
     *   <li><b>其他未知条件</b>：不做条件判定，超过 waitUntil 时间戳则 resume</li>
     * </ul>
     * 恢复后的"继续行动"由 checkGoalTransitions 发 WAIT_DONE 事件唤醒 Brain 统一处理，
     * 这里不重复发消息。玩家离线跳过。单玩家检查异常只记日志，不影响其余玩家。
     */
    private void waitUntilCheck(MinecraftServer server, long now) {
        if (server == null) return;
        for (UUID uuid : goals.players()) {
            GoalManager.Goal g = goals.get(uuid);
            if (g == null || g.status != GoalManager.Status.WAITING) continue;
            String cond = g.waitCondition;
            if (cond == null || cond.isBlank()) continue; // 无条件等待交给 goals.tick
            // 【MANUAL_WAIT】pause_goal 进入的"等玩家发话"：不因玩家移动自动恢复，也不超时——
            // 只有玩家新指令或 LLM 判断后调用 resume_wait 工具（resumeGoalAfterWait）才解除。
            // 若放行到下方会走"未知条件 → 超时兜底恢复"，waitUntil=now 立即恢复，等于没等。
            if ("manual".equals(cond)) continue;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player == null) continue; // 玩家离线跳过
            try {
                if (cond.equals("player_moved")) {
                    // 对比 markWaiting 时记录的参考位置：任一轴移动 > 0.5 格视为"动了"
                    double[] ref = waitRefPos.get(uuid);
                    if (ref == null) {
                        // 没有参考位置（异常路径）：以当前为基准，下一 tick 起才有对比意义
                        waitRefPos.put(uuid, new double[]{player.getX(), player.getY(), player.getZ()});
                        continue;
                    }
                    if (Math.abs(player.getX() - ref[0]) > 0.5
                            || Math.abs(player.getY() - ref[1]) > 0.5
                            || Math.abs(player.getZ() - ref[2]) > 0.5) {
                        waitRefPos.remove(uuid);
                        goals.resume(uuid);
                    }
                    continue;
                }
                if (cond.startsWith("player_distance>")) {
                    // 玩家与 AI 队友距离超过阈值 N 格 → 恢复
                    double threshold;
                    try {
                        threshold = Double.parseDouble(cond.substring("player_distance>".length()).trim());
                    } catch (NumberFormatException e) {
                        // 非法数字：当作未知条件，交给下方超时恢复
                        timeoutResume(uuid, g, now);
                        continue;
                    }
                    var companion = CompanionManager.get(uuid);
                    if (companion != null && !companion.isRemoved()
                            && player.distanceTo(companion) > threshold) {
                        goals.resume(uuid);
                    }
                    continue;
                }
                // 其他未知条件：不做条件判定，超时兜底恢复
                timeoutResume(uuid, g, now);
            } catch (Exception e) {
                AgentLogger.logError(0, "waitUntilCheck 玩家=" + uuid + " 条件=" + cond + " 异常: " + e);
            }
        }
    }

    /** 【Task 5】未知/非法 wait_until 条件的超时兜底：超过 waitUntil 时间戳则恢复 goal */
    private void timeoutResume(UUID uuid, GoalManager.Goal g, long now) {
        if (now >= g.waitUntil) {
            goals.resume(uuid);
        }
    }

    /**
     * 目标状态转变检测（tick 的 1s 分支调用，goals.tick 之后）：
     * <ul>
     *   <li><b>WAITING → RUNNING</b>：wait/wait_until 到期或条件满足 → 发 WAIT_DONE，
     *       唤醒 Brain 决定"等完了接下来干什么"（补上恢复后没有续上 AI 的缺口）</li>
     *   <li><b>RUNNING/WAITING → COMPLETED/FAILED</b>：目标完成/失败 → 发 GOAL_DONE，
     *       唤醒 Brain 做"接下来该干嘛"的后续思考（陪伴感闭环）</li>
     * </ul>
     * 遍历在线玩家 ∪ 持有 goal 的玩家。
     */
    private void checkGoalTransitions(MinecraftServer server, long now) {
        Set<UUID> players = new HashSet<>(states.keySet());
        players.addAll(goals.players());
        for (UUID uid : players) {
            GoalManager.Goal g = goals.get(uid);
            // 无 goal：清除记录并跳过。注意 ConcurrentHashMap 禁止 null value——
            // 绝不能 put(uid, null)，否则每秒 tick 都 NPE 把服务端打崩
            if (g == null) {
                lastGoalStatus.remove(uid);
                continue;
            }
            GoalManager.Status cur = g.status;
            GoalManager.Status prev = lastGoalStatus.put(uid, cur);
            if (prev == null) continue; // 首次观测，无转变
            if (prev == GoalManager.Status.WAITING && cur == GoalManager.Status.RUNNING) {
                // 【P0-WAIT】等待结束还原跟随：进入等待时 pauseFollowForWait 暂停的
                // followEnabled 现在恢复（无条件超时/player_moved/player_distance 三条恢复路径
                // 统一走这里），不需要 LLM 记得"等完了继续跟"
                CompanionManager.setFollowEnabled(uid, g.resumeFollowEnabled);
                events.publish(EventBus.Type.WAIT_DONE, uid, "wait_done");
                ServerPlayerEntity onlinePlayer = server.getPlayerManager().getPlayer(uid);
                if (onlinePlayer != null) {
                    EpisodicMemory.get().record(onlinePlayer, EpisodicMemory.Type.WAIT_ENDED, "wait_done",
                            EpisodicMemory.Source.RUNTIME, null, "success", null, null);
                }
                AgentLogger.logInfo("目标等待结束 玩家=" + uid + " 已发 WAIT_DONE 事件");
            }
            if ((prev == GoalManager.Status.RUNNING || prev == GoalManager.Status.WAITING)
                    && (cur == GoalManager.Status.COMPLETED || cur == GoalManager.Status.FAILED)) {
                events.publish(EventBus.Type.GOAL_DONE, uid, "goal_" + (g == null ? "unknown" : g.type));
                AgentLogger.logInfo("目标" + cur + " 玩家=" + uid + " 已发 GOAL_DONE 事件");
            }
        }
    }

    /** 刷新单个玩家的状态缓存（供技能层/外部主动读取最新快照） */
    public void refreshState(ServerPlayerEntity p) {
        if (p == null || p.getServerWorld() == null) return;
        ServerWorld world = p.getServerWorld();
        // 洞穴判定：玩家位置看不到天空即认为处于遮挡环境（比"头顶一格有方块"更接近真实洞穴）
        boolean inCave = !world.isSkyVisible(p.getBlockPos());
        states.put(p.getUuid(), new PlayerState(
                p.getX(), p.getY(), p.getZ(), p.getHealth(), inCave, System.currentTimeMillis()));
    }

    /**
     * 低频 replan + 社交检查（tick 的 20s 分支调用）：
     * <ul>
     *   <li><b>REPLAN_REQUIRED</b>：goal 处于 RUNNING 但 idle 超过 2 分钟（实质进展停摆）→
     *       发事件唤醒 Brain 评估"目标是否过时/卡住/需要拆解"。触发后 touch 重置 idle，
     *       避免每 20s 无脑重复唤醒——下次要再累积 2 分钟无进展才再次触发。
     *       （正常跟随会由 FollowSkill 在有实际移动时 touch，不会误判为卡住）</li>
     *   <li><b>SOCIAL_IDLE</b>：玩家持有<b>进行中</b>目标时跳过；无目标玩家距上次搭话
     *       &gt;5 分钟、距上次 LLM 思考 &gt;2 分钟 → 发事件唤醒 Brain 主动说句话。
     *       首次进入时初始化计时（杜绝"上线 20 秒就被搭话"）；无沙包伙伴的玩家不搭话。
     *       不满足条件则静默，绝不固定频率无脑调 LLM。</li>
     * </ul>
     */
    private void maybeReplan(MinecraftServer server, long now) {
        if (server == null) return;
        // a) goal 长期无进展 → REPLAN_REQUIRED（仅对有沙包伙伴的玩家，不花无关玩家 LLM）
        for (UUID uuid : goals.players()) {
            GoalManager.Goal g = goals.get(uuid);
            if (g == null || g.status != GoalManager.Status.RUNNING) continue;
            // 开放陪伴目标（companion/explore）没有"任务进展"概念：挂 2 小时不 touch 也正常，
            // 唤醒职责已由认知脉冲（ACTIVE/ADAPTIVE）接管——豁免 idle 卡住检测，避免每
            // 2 分钟无谓 REPLAN 烧一次 LLM（玩家也没在等"进展"）
            if ("companion".equals(g.type) || "explore".equals(g.type)) continue;
            if (!CompanionManager.exists(uuid)) continue;
            long idle = goals.idleMillis(uuid);
            if (idle > REPLAN_IDLE_MS) {
                events.publish(EventBus.Type.REPLAN_REQUIRED, uuid, "goal_idle_" + g.type);
                goals.touch(uuid); // 重置 idle 计时：需再次累积 2 分钟无进展才会再触发
                AgentLogger.logInfo("goal 长期无进展 玩家=" + uuid + " type=" + g.type
                        + " idle=" + (idle / 1000) + "s 已发 REPLAN_REQUIRED");
            }
        }
        // b) 无进行中目标且长期无交流 → SOCIAL_IDLE（主动搭话，陪伴感）
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            UUID uid = p.getUuid();
            // 只有真正拥有沙包伙伴的玩家才享受陪伴（不花无关玩家 LLM）
            if (!CompanionManager.exists(uid)) continue;
            // 持有进行中目标（RUNNING/WAITING）的玩家不需要搭话；
            // COMPLETED/FAILED 等终态目标不在此列（任务完成后仍可主动搭话）
            if (goals.hasActiveGoal(uid)) continue;
            // 首次进入：从现在开始计时（防上线约 20 秒就被 SOCIAL_IDLE 触发，而非等 5 分钟）
            lastSocialAt.putIfAbsent(uid, now);
            if (now - lastSocialAt.get(uid) < SOCIAL_IDLE_MS) continue; // 5 分钟内搭过/刚上线
            Long lastBrain = lastBrainAt.get(uid);
            if (lastBrain != null && now - lastBrain < SOCIAL_BRAIN_MIN_MS) continue; // 刚聊过/刚思考过
            events.publish(EventBus.Type.SOCIAL_IDLE, uid, "idle");
            lastSocialAt.put(uid, now);
            AgentLogger.logInfo("社交空闲 玩家=" + uid + " 已发 SOCIAL_IDLE 事件");
        }
    }
}
