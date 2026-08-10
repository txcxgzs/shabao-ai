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

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 技能注册与多通道调度：本地高频行为层（0 次 LLM 调用）。
 *
 * <p><b>设计意图</b>：技能是"不需要思考、按固定规则跑"的行为（观察危险/跟随/插火把/
 * 交互）。由 {@link AgentRuntime} 每秒对每个在线玩家 tick 一次。<b>按通道并行</b>——
 * 不同通道的技能互不阻塞、同一 tick 内都能执行；同一通道内按优先级抢占。
 *
 * <pre>
 *   OBSERVER  观察类（Safety：危险扫描）      → 必须常驻
 *   MOVEMENT  移动类（Follow：寻路）          → 一次只跑一个
 *   UTILITY   便利类（TorchAssist：插火把）   → 可并行于移动
 *   INTERACTION 交互类（预留：使用方块/对话）  → 可并行于移动
 * </pre>
 *
 * <p>这样"跟随 + 插火把 + 危险感知"三个行为可以在同一秒内同时工作：
 * Safety 扫岩浆、Follow 继续寻路、TorchAssist 检查照明，互不抢占。
 * 只有两个技能都要控制"移动"（同一通道）时才互斥。
 *
 * <p><b>执行对象</b>：由 AgentRuntime 按<b>在线玩家</b>遍历调用
 * {@link #tick(ServerPlayerEntity, GoalManager)}——在线玩家自然覆盖
 * 「持有 goal 的玩家」与「手动启用技能的玩家」两个来源，手动启用的技能
 * （如 start_skill 开的 torch_place）不再要求玩家必须有 goal 才会执行。
 * world 一律取 {@code player.getServerWorld()}，杜绝"跨维度拿错世界"。
 *
 * <p><b>启用机制</b>：技能默认不启用（除 follow/safety 等常驻技能外由
 * start_skill 显式启用）；实现方覆写 {@link Skill#enabledByDefault()}。
 */
public final class SkillManager {
    /** 技能通道：通道间并行、通道内按优先级抢占 */
    public enum Channel {
        /** 观察类：危险扫描等，必须常驻且优先级最高（Safety） */
        OBSERVER,
        /** 移动类：寻路/跟随，一次只跑一个（Follow） */
        MOVEMENT,
        /** 便利类：插火把等，可与移动并行（TorchAssist） */
        UTILITY,
        /** 交互类：使用方块/实体交互，预留 */
        INTERACTION
    }

    /** 技能接口：实现方给出 id、通道、优先级，并自行判断"现在该不该跑/怎么跑" */
    public interface Skill {
        /** 技能唯一 ID（如 "follow" / "torch_place"） */
        String id();

        /** 所属通道：决定与其他技能的并行关系（默认 UTILITY） */
        default Channel channel() {
            return Channel.UTILITY;
        }

        /** 通道内优先级：越大越优先（同通道竞争时生效） */
        int priority();

        /** 是否应当运行（world 恒为玩家所在维度，由调用方保证） */
        boolean shouldRun(ServerWorld world, GoalManager goals, UUID player);

        /** 执行技能（高频本地行为，不得阻塞/调用 LLM） */
        void run(ServerWorld world, GoalManager goals, UUID player);

        /** 是否默认启用：默认 false（需 start_skill 显式启用）；follow/safety 等常驻技能覆写为 true */
        default boolean enabledByDefault() {
            return false;
        }

        /** 清空该玩家的技能内部缓存（世界退出/服务器关闭时由 SkillManager.clearAll 调用） */
        default void clearPlayerState() {
        }

        /** 玩家断线时只清该玩家缓存，避免长期开服 UUID 状态泄漏。 */
        default void clearPlayerState(UUID player) {
        }
    }

    /** 已注册技能列表（读多写少，CopyOnWriteArrayList 免锁遍历） */
    private final CopyOnWriteArrayList<Skill> skills = new CopyOnWriteArrayList<>();
    /** 玩家 UUID → 该玩家【手动】启用的技能 ID 集合（玩家要求"以后一直插火把"→ 换 Goal 也继续） */
    private final ConcurrentHashMap<UUID, Set<String>> enabledByPlayer = new ConcurrentHashMap<>();
    /** 玩家 UUID → 该玩家【Goal 关联】启用的技能 ID 集合（mine_assist 自动开的 torch_place → 离开该 Goal 自动释放） */
    private final ConcurrentHashMap<UUID, Set<String>> goalOwnedByPlayer = new ConcurrentHashMap<>();

    /** 构造包内可见：仅允许同包的 AgentRuntime 创建 */
    SkillManager() {}

    /** 注册一个技能（重复注册同 id 时旧技能被替换） */
    public void register(Skill skill) {
        if (skill == null) return;
        skills.removeIf(s -> s.id().equals(skill.id()));
        skills.add(skill);
    }

    /** 显式启用该玩家的某技能（start_skill） */
    public void start(UUID player, String skillId) {
        enabledByPlayer.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet()).add(skillId);
    }

    /** 显式停用该玩家的某技能 */
    public void stop(UUID player, String skillId) {
        Set<String> set = enabledByPlayer.get(player);
        if (set != null) set.remove(skillId);
    }

    /** 该玩家某技能是否被显式启用（不含 enabledByDefault 判定） */
    public boolean isEnabled(UUID player, String skillId) {
        Set<String> set = enabledByPlayer.get(player);
        return set != null && set.contains(skillId);
    }

    /** 该玩家某技能是否被 Goal 关联启用（不含 enabledByDefault 判定） */
    public boolean isGoalOwned(UUID player, String skillId) {
        Set<String> set = goalOwnedByPlayer.get(player);
        return set != null && set.contains(skillId);
    }

    /**
     * 【Skill 泄漏修复】Goal 关联启用：mine_assist 自动开的 torch_place 走这里——
     * 与玩家手动 start_skill 分开，Goal 切换/取消时 {@link #releaseGoalSkills} 统一释放，
     * 不会污染"玩家以后一直要插火把"的手动偏好。
     */
    public void startGoalOwned(UUID player, String skillId) {
        goalOwnedByPlayer.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet()).add(skillId);
    }

    /** 释放该玩家全部 Goal 关联技能（离开 mine_assist / cancel_goal / complete_goal / 玩家断开） */
    public void releaseGoalSkills(UUID player) {
        goalOwnedByPlayer.remove(player);
    }

    /**
     * 【P2-只关单个】停用该玩家某个 Goal 关联技能（如"别插火把"只关 torch_place）。
     * 与 {@link #releaseGoalSkills} 不同：不清其他 Goal 关联技能；与 {@link #stop} 配合
     * 只关火把，绝不误伤玩家手动启用的其他技能（clearPlayer 会清掉全部，已弃用该用法）。
     */
    public void stopGoalOwned(UUID player, String skillId) {
        Set<String> set = goalOwnedByPlayer.get(player);
        if (set != null) set.remove(skillId);
    }

    /** 清空该玩家全部技能启用状态（手动 + Goal 关联；玩家断开/服务器关闭用） */
    public void clearPlayer(UUID player) {
        enabledByPlayer.remove(player);
        goalOwnedByPlayer.remove(player);
        for (Skill s : skills) {
            try { s.clearPlayerState(player); } catch (Exception ignored) {}
        }
    }

    /** 清空全部技能启用状态与技能缓存（服务器关闭用） */
    public void clearAll() {
        enabledByPlayer.clear();
        goalOwnedByPlayer.clear();
        for (Skill s : skills) {
            try {
                s.clearPlayerState();
            } catch (Exception ignored) {
                // 清缓存失败不影响关闭流程
            }
        }
    }

    /** 该玩家某技能是否在任一启用集内（手动 或 Goal 关联；不含 enabledByDefault） */
    private boolean enabledAny(UUID player, String skillId) {
        Set<String> manual = enabledByPlayer.get(player);
        if (manual != null && manual.contains(skillId)) return true;
        Set<String> goalOwned = goalOwnedByPlayer.get(player);
        return goalOwned != null && goalOwned.contains(skillId);
    }

    /**
     * 技能调度（由 AgentRuntime 每秒对每个在线玩家调用一次）：
     * <b>按通道分组、通道间并行</b>——每个通道各自选出 enabled 且 shouldRun 通过的
     * 最高优先级技能执行；一个通道的执行失败不影响其他通道。
     * world 取 {@code player.getServerWorld()}，保证技能操作的是玩家所在维度。
     *
     * @param player 目标玩家（必须在线，由调用方保证）
     * @param goals  目标状态机（技能按它判断当前目标/状态）
     */
    public void tick(ServerPlayerEntity player, GoalManager goals) {
        if (player == null || goals == null || player.getServerWorld() == null) return;
        ServerWorld world = player.getServerWorld();
        UUID uid = player.getUuid();
        // 按通道遍历：每个通道内选最高优先级技能执行（通道间天然并行，互不阻塞）
        for (Channel channel : Channel.values()) {
            Skill chosen = null;
            for (Skill s : skills) {
                if (s.channel() != channel) continue;
                boolean enabled = s.enabledByDefault() || enabledAny(uid, s.id());
                if (!enabled) continue;
                try {
                    if (!s.shouldRun(world, goals, uid)) continue;
                } catch (Exception e) {
                    AgentLogger.logError(0, "技能 " + s.id() + " shouldRun 异常: " + e);
                    continue;
                }
                if (chosen == null || s.priority() > chosen.priority()) {
                    chosen = s;
                }
            }
            if (chosen == null) continue;
            try {
                chosen.run(world, goals, uid);
            } catch (Exception e) {
                AgentLogger.logError(0, "技能 " + chosen.id() + " run 异常: " + e);
            }
        }
    }
}
