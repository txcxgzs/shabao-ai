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
import cn.shabaoai.companion.entity.CompanionManager;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 危险感知技能（0 次 LLM 调用）：常驻轻量危险检测，发现岩浆/血量骤降时
 * 发布事件并口头提醒玩家。
 *
 * <p><b>设计意图</b>：危险不能等 LLM 想完再报，必须秒级响应。本技能
 * 随 SkillManager 每秒节拍执行一次，只做<b>小范围</b>检测（玩家周围 3x3x3
 * 岩浆扫描 + 血量对比），成本极低。检测到的危险通过 {@link EventBus}
 * 发布 DANGER / PLAYER_HURT 事件（由上层决定是否唤醒 Brain），并口头
 * 提醒玩家；同一种危险 30 秒内不重复提醒（去重，避免刷屏）。
 *
 * <p>血量骤降判定：以本技能上次记录的玩家血量为基准，当前缓存血量
 * （{@link AgentRuntime.PlayerState#health()}）比基准低超过 4 点即视为受伤；
 * 每次检测都会刷新基准，保证恢复后再次受伤能重新触发。
 *
 * <p>线程安全：去重/基准状态按玩家维度记录在 {@link ConcurrentHashMap} 中
 * （技能实例为单例、多玩家共享，不能用裸字段互相覆盖）；
 * run 由服务端主线程 tick 驱动。
 */
public final class SafetySkill implements SkillManager.Skill {
    /** 技能唯一 ID */
    private static final String ID = "safety";
    /** 同一种危险的最短提醒间隔（ms）：30 秒内不重复提醒 */
    private static final long WARN_COOLDOWN_MS = 30_000;
    /** 血量骤降阈值：比上次记录低超过 4 点视为受伤 */
    private static final float HEALTH_DROP_THRESHOLD = 4.0f;
    /** 岩浆扫描半径（格）：玩家周围 3x3x3（y 上下各 1 格），检测范围刻意做小防性能问题 */
    private static final int SCAN_RADIUS = 1;

    /** 玩家 UUID → 上次记录的血量（血量骤降判定基准） */
    private final Map<UUID, Float> lastHealth = new ConcurrentHashMap<>();
    /** 玩家 UUID → 上次岩浆提醒时间戳（去重用） */
    private final Map<UUID, Long> lastLavaWarnAt = new ConcurrentHashMap<>();
    /** 玩家 UUID → 上次受伤提醒时间戳（去重用） */
    private final Map<UUID, Long> lastHurtWarnAt = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void clearPlayerState() {
        // 世界退出/服务器关闭：清空血量基准与警告去重缓存，防止旧世界状态残留
        lastHealth.clear();
        lastLavaWarnAt.clear();
        lastHurtWarnAt.clear();
    }

    @Override
    public void clearPlayerState(UUID player) {
        lastHealth.remove(player);
        lastLavaWarnAt.remove(player);
        lastHurtWarnAt.remove(player);
    }

    @Override
    public SkillManager.Channel channel() {
        // 观察通道：危险扫描独立于移动/便利，每 tick 常驻执行，不被跟随/插火把抢占
        return SkillManager.Channel.OBSERVER;
    }

    @Override
    public int priority() {
        // 优先级约定：逃命100 > 保护90（本技能） > 战斗80 > 跟随50
        return 90;
    }

    @Override
    public boolean enabledByDefault() {
        // 安全保护是常驻需求：默认开启，无需 start_skill
        return true;
    }

    @Override
    public boolean shouldRun(ServerWorld world, GoalManager goals, UUID player) {
        // 玩家在线才需要保护（离线无意义）
        return world.getServer().getPlayerManager().getPlayer(player) != null;
    }

    @Override
    public void run(ServerWorld world, GoalManager goals, UUID player) {
        // 内部兜底：任何异常只记日志，不得抛给 SkillManager 的 tick 循环
        try {
            doRun(world, player);
        } catch (Exception e) {
            AgentLogger.logError(0, "危险感知技能异常: 玩家=" + player + " 原因=" + e);
        }
    }

    /** 危险检测主逻辑：岩浆扫描 + 血量骤降，各自独立去重提醒 */
    private void doRun(ServerWorld world, UUID player) {
        ServerPlayerEntity p = world.getServer().getPlayerManager().getPlayer(player);
        if (p == null) return;
        long now = System.currentTimeMillis();
        // a) 岩浆检测：周围 3x3x3 内存在岩浆方块 → DANGER + 口头提醒
        if (hasLavaAround(world, p.getBlockPos())) {
            warn(p, now, lastLavaWarnAt, EventBus.Type.DANGER, "lava_nearby", "⚠ 附近有岩浆！");
        }
        // b) 血量骤降：比上次记录低超过 4 点 → PLAYER_HURT + 口头关切
        checkHealthDrop(p, now);
    }

    /** 血量骤降检测：基准血量低于阈值则提醒，并始终刷新基准 */
    private void checkHealthDrop(ServerPlayerEntity p, long now) {
        AgentRuntime.PlayerState st = AgentRuntime.get().state(p.getUuid());
        if (st == null) return;
        Float last = lastHealth.get(p.getUuid());
        if (last != null && last - st.health() > HEALTH_DROP_THRESHOLD) {
            warn(p, now, lastHurtWarnAt, EventBus.Type.PLAYER_HURT, "health_drop", "你受伤了！我来护着你");
        }
        // 每次检测都刷新基准：恢复后再次骤降能重新触发
        lastHealth.put(p.getUuid(), st.health());
    }

    /**
     * 统一去重提醒：同一玩家同一类危险 30 秒冷却内不重复发布事件与消息。
     *
     * <p>【r8 防双口水】事件 data 带 {@code already_alerted} 标记：本地已口头警告过玩家，
     * 唤醒的 Brain 读到后只在需要额外动作/判断时才说话（prompt 有对应引导），
     * 否则同一危险不会"本地说一遍 + DeepSeek 再说一遍"。
     *
     * @param lastWarnAt 该危险类别的上次提醒时间戳表（按玩家记录）
     */
    private void warn(ServerPlayerEntity p, long now, Map<UUID, Long> lastWarnAt,
                      EventBus.Type type, String eventData, String message) {
        UUID player = p.getUuid();
        Long last = lastWarnAt.get(player);
        if (last != null && now - last < WARN_COOLDOWN_MS) return;
        lastWarnAt.put(player, now);
        // 事件交给上层（决定是否唤醒 Brain），消息直接口头提醒玩家
        AgentRuntime.get().events().publish(type, player, eventData + ":already_alerted");
        CompanionManager.sendMessage(p, message, cn.shabaoai.companion.ai.EpisodicMemory.Source.LOCAL_SKILL);
    }

    /** 玩家周围 3x3x3（y 上下各 1 格）内是否检测到岩浆方块（含炼药锅岩浆） */
    private static boolean hasLavaAround(ServerWorld world, BlockPos center) {
        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dy = -SCAN_RADIUS; dy <= SCAN_RADIUS; dy++) {
                for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                    net.minecraft.block.BlockState state = world.getBlockState(center.add(dx, dy, dz));
                    if (state.isOf(Blocks.LAVA) || state.isOf(Blocks.LAVA_CAULDRON)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
