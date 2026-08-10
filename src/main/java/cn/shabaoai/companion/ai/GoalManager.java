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

import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 目标状态机：按玩家 UUID 维度管理<b>单个</b>当前 goal（骨架层）。
 *
 * <p><b>设计意图</b>：Agent 需要知道"现在在干什么"才能决定本地行为与是否唤醒 LLM。
 * 状态机流转：IDLE→RUNNING→WAITING→RUNNING→…→COMPLETED/CANCELLED/FAILED。
 * <ul>
 *   <li>RUNNING：目标执行中（技能层按它调度高频行为）</li>
 *   <li>WAITING：等待恢复（wait/wait_until），到期或条件满足后由 {@link #resume} 回到 RUNNING</li>
 *   <li>COMPLETED / CANCELLED / FAILED：终态（保留在 map 中供查询，不自动清理）</li>
 * </ul>
 *
 * <p>线程安全：底层 {@link ConcurrentHashMap}；lastActivityAt 用于 stall（卡住）检测。
 */
public final class GoalManager {
    /** 目标状态 */
    public enum Status { IDLE, RUNNING, WAITING, COMPLETED, CANCELLED, FAILED }

    /** 单个玩家的当前目标 */
    public static final class Goal {
        public final UUID player;
        /** 目标类型，如 "follow" / "build" / "mine_assist" */
        public volatile String type;
        /** 目标参数（LLM 或上层下发的附加数据，可为空对象） */
        public volatile JsonObject params;
        /** 当前状态 */
        public volatile Status status;
        /** wait/wait_until 恢复时间戳（epoch ms），非 WAITING 时无意义 */
        public volatile long waitUntil;
        /** wait_until 条件字符串（如 "player_distance>5"），可空；空表示纯超时等待 */
        public volatile String waitCondition;
        /** 最近一次活动时间戳（stall 检测用，epoch ms） */
        public volatile long lastActivityAt;
        /** 认知策略：决定"这个目标的大脑什么时候再想"（每个 Goal 必须持有，由 set 时按类型默认） */
        public volatile CognitionPolicy cognition;
        /** 【WAIT 急停】进入等待时记录原跟随开关：等待结束后由 Runtime 还原（WAITING→RUNNING 转换时）。 */
        public volatile boolean resumeFollowEnabled = true;

        Goal(UUID player) {
            this.player = player;
            this.type = null;
            this.params = new JsonObject();
            this.status = Status.RUNNING;
            this.waitUntil = 0;
            this.waitCondition = null;
            this.lastActivityAt = System.currentTimeMillis();
            this.cognition = null;
        }
    }

    /** 玩家 UUID → 当前目标 */
    private final ConcurrentHashMap<UUID, Goal> active = new ConcurrentHashMap<>();

    /** 构造包内可见：仅允许同包的 AgentRuntime 创建 */
    GoalManager() {}

    /** 新建/替换该玩家的 goal：status=RUNNING，刷新 lastActivityAt，按类型给默认认知策略 */
    public void set(UUID player, String type, JsonObject params) {
        Goal g = new Goal(player);
        g.type = type;
        if (params != null) g.params = params;
        // 每个 Goal 必须拥有 cognition policy（不能为空）：机械型（follow/build）EVENT，
        // 开放式（mine_assist/自定义）ADAPTIVE 低频自检
        g.cognition = CognitionPolicy.forType(type);
        active.put(player, g);
        AgentLogger.logInfo("goal 设置: 玩家=" + player + " type=" + type
                + " cognition=" + (g.cognition == null ? "null" : g.cognition.mode()));
    }

    /**
     * 应用 LLM 建议的认知策略（set_cognition 工具）。<b>Runtime Policy Validator</b>：
     * <ul>
     *   <li>mode 非法 → 忽略，保留当前策略</li>
     *   <li>开放式目标（非 follow/build）被建议 LOCAL/EVENT → 最低强制提升到 ADAPTIVE，
     *       防止 LLM 把"陪我自由探索"降级成"纯跟随 NPC"</li>
     *   <li>interval 经 CognitionPolicy.configure 强制 clamp</li>
     * </ul>
     */
    public void applyCognition(UUID player, String mode, int minSec, int maxSec, int nextAfterSec) {
        Goal g = active.get(player);
        if (g == null) return;
        CognitionPolicy.Mode m = parseMode(mode);
        if (m == null) return;
        // Runtime 校正：开放式目标不允许纯本地/纯事件（至少要能低频自检）
        boolean mechanical = "follow".equals(g.type) || "build".equals(g.type);
        if (!mechanical && (m == CognitionPolicy.Mode.LOCAL || m == CognitionPolicy.Mode.EVENT)) {
            AgentLogger.logInfo("cognition 校正: 开放式目标 " + g.type + " 建议 " + m
                    + " 强制提升为 ADAPTIVE");
            m = CognitionPolicy.Mode.ADAPTIVE;
        }
        // 【r6】机械目标（follow/build）反方向也不许烧爆：模型把它调成 ACTIVE（每 4 秒想一次）
        // 毫无必要——"跟着走/建房子"不需要持续认知，最高只允许 ADAPTIVE（8s+ 低频自检）
        if (mechanical && m == CognitionPolicy.Mode.ACTIVE) {
            AgentLogger.logInfo("cognition 校正: 机械目标 " + g.type + " 建议 ACTIVE"
                    + " 降级为 ADAPTIVE（follow/build 最高 adaptive）");
            m = CognitionPolicy.Mode.ADAPTIVE;
        }
        if (g.cognition == null) g.cognition = CognitionPolicy.forType(g.type);
        g.cognition.configure(m, minSec, maxSec, nextAfterSec);
        AgentLogger.logInfo("cognition 应用: 玩家=" + player + " goal=" + g.type
                + " mode=" + m + " min=" + minSec + "s max=" + maxSec + "s next=" + nextAfterSec + "s");
    }

    /** LLM 预约下次思考（schedule_think 工具）：按 mode clamp 后设置 pulse 时间 */
    public void scheduleThink(UUID player, int afterSec) {
        Goal g = active.get(player);
        if (g == null || g.cognition == null) return;
        g.cognition.scheduleNext(afterSec);
        AgentLogger.logInfo("cognition 预约: 玩家=" + player + " after=" + afterSec + "s");
    }

    /** 字符串 → 认知模式（大小写不敏感；非法返回 null） */
    private static CognitionPolicy.Mode parseMode(String mode) {
        if (mode == null || mode.isBlank()) return null;
        try {
            return CognitionPolicy.Mode.valueOf(mode.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 移除该玩家的 goal（CANCELLED 语义），返回被移除的 Goal（无则 null） */
    public Goal cancel(UUID player) {
        Goal g = active.remove(player);
        if (g != null) {
            AgentLogger.logInfo("goal 取消: 玩家=" + player + " type=" + g.type);
        }
        return g;
    }

    /** 返回该玩家的当前 goal（无则 null） */
    public Goal get(UUID player) {
        return active.get(player);
    }

    /** 该玩家是否持有 goal */
    public boolean has(UUID player) {
        return active.containsKey(player);
    }

    /**
     * 该玩家是否持有<b>进行中</b>的目标（RUNNING/WAITING）。
     * COMPLETED/FAILED/CANCELLED 等终态目标不算——任务做完后不应挡住后续
     * 主动陪伴（如 SOCIAL_IDLE 搭话）。
     */
    public boolean hasActiveGoal(UUID player) {
        Goal g = active.get(player);
        return g != null && (g.status == Status.RUNNING || g.status == Status.WAITING);
    }

    /** 置为 WAITING：waitUntil=now+waitMs，记录条件（可空=纯超时等待） */
    public void markWaiting(UUID player, long waitMs, String condition) {
        Goal g = active.get(player);
        if (g == null) return;
        g.status = Status.WAITING;
        g.waitUntil = System.currentTimeMillis() + Math.max(0, waitMs);
        g.waitCondition = condition;
        AgentLogger.logInfo("goal 等待: 玩家=" + player + " waitMs=" + waitMs
                + " condition=" + condition + " 到期=" + g.waitUntil);
    }

    /** 恢复为 RUNNING（wait 到期/条件满足后由调用方调用），刷新 lastActivityAt */
    public void resume(UUID player) {
        Goal g = active.get(player);
        if (g == null || g.status != Status.WAITING) return;
        g.status = Status.RUNNING;
        g.waitCondition = null;
        touch(player);
        AgentLogger.logInfo("goal 恢复: 玩家=" + player + " type=" + g.type);
    }

    /** 置为 COMPLETED（保留在 map 中供查询），why 仅记日志 */
    public void complete(UUID player, String why) {
        Goal g = active.get(player);
        if (g == null) return;
        g.status = Status.COMPLETED;
        AgentLogger.logInfo("goal 完成: 玩家=" + player + " type=" + g.type + " why=" + why);
    }

    /** 置为 FAILED（保留在 map 中供查询），why 仅记日志 */
    public void fail(UUID player, String why) {
        Goal g = active.get(player);
        if (g == null) return;
        g.status = Status.FAILED;
        AgentLogger.logError(0, "goal 失败: 玩家=" + player + " type=" + g.type + " why=" + why);
    }

    /** 刷新 lastActivityAt（每次实质进展调用，供 stall 检测） */
    public void touch(UUID player) {
        Goal g = active.get(player);
        if (g != null) g.lastActivityAt = System.currentTimeMillis();
    }

    /** 距最近一次活动的毫秒数（无 goal 返回 -1） */
    public long idleMillis(UUID player) {
        Goal g = active.get(player);
        return g == null ? -1 : System.currentTimeMillis() - g.lastActivityAt;
    }

    /**
     * 状态机推进（由 AgentRuntime 每秒调用一次）：
     * WAITING 且<b>无条件</b>（waitCondition 为空）的 goal，超时后自动 resume；
     * 带条件（wait_until）的 goal 不在此自动恢复，由调用方判定条件满足后调 {@link #resume}。
     */
    public void tick(long now) {
        for (Goal g : active.values()) {
            if (g.status == Status.WAITING && g.waitCondition == null && now >= g.waitUntil) {
                g.status = Status.RUNNING;
                g.lastActivityAt = now;
                AgentLogger.logInfo("goal 超时自动恢复: 玩家=" + g.player + " type=" + g.type);
            }
        }
    }

    /** 当前持有 goal 的玩家集合（副本，供 SkillManager 遍历调度） */
    public Set<UUID> players() {
        return new HashSet<>(active.keySet());
    }

    /** 【世界退出清理】清空全部 goal（服务器关闭用） */
    public void clearAll() {
        active.clear();
    }
}
