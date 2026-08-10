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

package cn.shabaoai.companion.ai.skill;

import cn.shabaoai.companion.ai.AgentLogger;
import cn.shabaoai.companion.ai.AgentRuntime;
import cn.shabaoai.companion.ai.EventBus;
import cn.shabaoai.companion.ai.GoalManager;
import cn.shabaoai.companion.ai.SkillManager;
import cn.shabaoai.companion.entity.CompanionEntity;
import cn.shabaoai.companion.entity.CompanionManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 持续跟随技能（0 次 LLM 调用）：玩家持有 follow 目标时，做<b>跟随模式管理</b>，
 * 全程不惊动 LLM。
 *
 * <p><b>职责划分</b>（避免两套导航互相抢占）：
 * <ul>
 *   <li><b>实际寻路</b>：交给实体原生 AI {@link CompanionEntity} 内部的
 *       FollowOwnerGoal（3 格启动 / 2 格停止 / 32 格传送 + 0.5s 寻路节流）——
 *       它由 followEnabled 总开关控制，玩家说"别跟了"立即急停。</li>
 *   <li><b>本技能</b>：只负责模式管理——① 玩家移动或距离保持时 touch goal
 *       （防止 maybeReplan 误判"长期无进展"而唤醒 LLM）；② 队友实体丢失时发
 *       {@link EventBus.Type#STALLED} 事件让上层处理（30 秒去重）。</li>
 * </ul>
 *
 * <p><b>进展上报</b>：玩家位置相对上次记录移动超过 1.5 格、或沙包已保持在
 * 主人 5 格内，都视为 follow 目标正常推进（"贴身站桩"不是卡住），刷新 goal
 * 活动时间，避免每 120 秒被 replan 误唤 LLM。
 *
 * <p>线程安全：{@code lastPlayerPos}/{@code lastStalledAt} 按玩家维度记录
 * （技能实例为单例、多玩家共享，不能用裸字段互相覆盖）；
 * run 由服务端主线程 tick 驱动。
 */
public final class FollowSkill implements SkillManager.Skill {
    /** 技能唯一 ID（GoalManager 目标类型 "follow" 与之对应） */
    private static final String ID = "follow";
    /** STALLED 事件去重间隔（ms）：同一玩家 30 秒内不重复发 */
    private static final long STALLED_COOLDOWN_MS = 30_000;
    /** 玩家位置移动多少格算"有实际进展"：触发 goal.touch 的阈值 */
    private static final double PROGRESS_MOVE_DIST = 1.5;

    /** 玩家 UUID → 上次发 STALLED 事件时间戳（去重用） */
    private final Map<UUID, Long> lastStalledAt = new ConcurrentHashMap<>();
    /** 玩家 UUID → 上次记录的玩家位置：有实际移动时 touch goal（防正常跟随被误判"卡住"） */
    private final Map<UUID, BlockPos> lastPlayerPos = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void clearPlayerState() {
        // 世界退出/服务器关闭：清空按玩家记录的缓存，防止旧世界状态残留到新世界
        lastPlayerPos.clear();
        lastStalledAt.clear();
    }

    @Override
    public void clearPlayerState(UUID player) {
        lastPlayerPos.remove(player);
        lastStalledAt.remove(player);
    }

    @Override
    public SkillManager.Channel channel() {
        // 移动通道：跟随控制寻路，与观察/便利通道并行（同一 tick 内可与插火把/危险扫描同时工作）
        return SkillManager.Channel.MOVEMENT;
    }

    @Override
    public int priority() {
        // 优先级约定：逃命100 > 保护90 > 战斗80 > 跟随50
        return 50;
    }

    @Override
    public boolean enabledByDefault() {
        // follow 是常驻技能：玩家一旦持有 follow 目标即自动生效，无需 start_skill
        return true;
    }

    @Override
    public boolean shouldRun(ServerWorld world, GoalManager goals, UUID player) {
        // follow：纯跟随；mine_assist：下矿帮手（跟随 + 自动火把 + 自适应认知），同样需要跟随。
        // 其余目标（build/companion 等）跟随由 FollowOwnerGoal 的 followEnabled 总开关另行管理。
        GoalManager.Goal g = goals.get(player);
        if (g == null || g.status != GoalManager.Status.RUNNING) return false;
        boolean isFollowLike = "follow".equals(g.type) || "mine_assist".equals(g.type);
        if (!isFollowLike) return false;
        // 玩家在线（不在线不调度）
        return world.getServer().getPlayerManager().getPlayer(player) != null;
    }

    @Override
    public void run(ServerWorld world, GoalManager goals, UUID player) {
        // 内部兜底：任何异常只记日志，不得抛给 SkillManager 的 tick 循环
        try {
            doRun(world, goals, player);
        } catch (Exception e) {
            AgentLogger.logError(0, "跟随技能异常: 玩家=" + player + " 原因=" + e);
        }
    }

    /** 跟随模式管理主逻辑：进展上报 + 队友缺失事件；实际寻路由 FollowOwnerGoal 负责 */
    private void doRun(ServerWorld world, GoalManager goals, UUID player) {
        ServerPlayerEntity p = world.getServer().getPlayerManager().getPlayer(player);
        if (p == null) return;
        // 记录玩家位置变化：用于"是否有实际移动"的进展判定
        BlockPos ppos = p.getBlockPos();
        BlockPos lastSeen = lastPlayerPos.get(player);
        boolean playerMoved = lastSeen == null || dist3d(ppos.getX() - lastSeen.getX(),
                ppos.getY() - lastSeen.getY(), ppos.getZ() - lastSeen.getZ()) > PROGRESS_MOVE_DIST;
        if (playerMoved) lastPlayerPos.put(player, ppos);
        CompanionEntity companion = CompanionManager.get(player);
        // 队友不存在（未被召唤/已移除）：发 STALLED 让上层处理（30 秒去重）
        if (companion == null || companion.isRemoved()) {
            long now = System.currentTimeMillis();
            Long last = lastStalledAt.get(player);
            if (last == null || now - last > STALLED_COOLDOWN_MS) {
                lastStalledAt.put(player, now);
                AgentRuntime.get().events().publish(EventBus.Type.STALLED, player, "companion_missing");
                AgentLogger.logInfo("跟随技能: 队友实体缺失 玩家=" + player + " 已发 STALLED");
            }
            return;
        }

        double dist = dist3d(p.getX() - companion.getX(),
                p.getY() - companion.getY(), p.getZ() - companion.getZ());
        // 【进展上报】玩家在移动 或 距离已保持成功（≤5 格）→ 刷新 goal 活动时间。
        // "沙包一直保持在主人身边"本身就是 follow 目标的正常完成状态——
        // 玩家站着聊天 2 分钟不该被 maybeReplan 误判为"长期无进展卡住"而唤醒 LLM。
        if (playerMoved || dist <= 5) {
            goals.touch(player);
        }
        // 【寻路职责】实际走向由 CompanionEntity$FollowOwnerGoal 负责：
        // 实体原生 AI 按 3 格启动 / 2 格停止 / 32 格传送 + 0.5s 寻路节流自行移动，
        // 由 followEnabled 总开关控制急停。本技能不再 navigateTo——
        // 避免 FollowSkill 与 FollowOwnerGoal 两套导航同时抢 Navigation。
    }

    /** 三维欧氏距离 */
    private static double dist3d(double dx, double dy, double dz) {
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
