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
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 世界变化累积器（WorldChangeAccumulator）：记录<b>上次 Brain 思考之后</b>
 * 世界发生了什么，供认知循环（BrainPulse）计算 novelty score——"持续认知"
 * 不应该是固定频率烧 API，而是<b>变化足够大才唤醒</b>，没变化就继续等。
 *
 * <p><b>累积维度</b>（由 AgentRuntime 每秒 {@link #accumulate} 刷新）：
 * <ul>
 *   <li>玩家移动距离（累计，越界算变化）</li>
 *   <li>维度切换（去下界/末地）</li>
 *   <li>地面/地下切换（isSkyVisible 翻转，进入大型洞穴）</li>
 *   <li>血量骤降（玩家受伤）</li>
 *   <li>新敌对生物出现（24 格内敌对实体数量增加）</li>
 *   <li>玩家开口说话（有互动）</li>
 * </ul>
 *
 * <p><b>novelty score</b>（{@link #novelty}，0~9）：
 * 移动&gt;10 格 +1 / 维度或地下切换 +2 / 血量骤降&gt;2 +3 / 新敌对 +2 / 玩家说话 +1。
 * 阈值（如 ≥3）由 AgentRuntime 决定是否唤醒 Brain；分数不足则
 * CognitionPolicy.defer 延后下次检查——普通挖矿 10 分钟可以 0 次 LLM。
 *
 * <p>线程安全：{@link ConcurrentHashMap} 按玩家维度记录（单例、多玩家共享）。
 */
public final class WorldChangeAccumulator {
    /** 移动多少格算"有变化" */
    private static final double MOVE_NOTICE_DIST = 10.0;
    /** 敌对实体扫描半径（格）：玩家周围 24 格内出现新怪物才算变化 */
    private static final double HOSTILE_SCAN_RADIUS = 24.0;
    /** 血量骤降多少点算"受伤"（纳入评分） */
    private static final float HEALTH_DROP_NOTICE = 2.0f;

    /** 单玩家累积状态（上次思考后） */
    private static final class Accum {
        boolean initialized;             // 基准是否已建立（不能靠坐标判零——出生点就是 (0,0,0)）
        double lastX, lastY, lastZ;      // 上次累计时的玩家位置（移动距离基准）
        double movedDist;                // 累计移动距离
        boolean lastInCave;              // 上次地下状态（切换检测）
        float lastHealth;                // 上次血量（骤降检测）
        int lastHostiles;                // 上次敌对数量（新增检测）
        String lastDim;                  // 上次维度（切换检测）
        boolean undergroundChanged;      // 地面/地下切换过
        boolean dimChanged;              // 维度切换过
        float maxHealthDrop;             // 累计最大血量跌幅
        boolean playerTalked;            // 玩家说过话
        boolean hostilesAppeared;        // 出现过新敌对
    }

    /** 玩家 UUID → 累积状态 */
    private final Map<UUID, Accum> accums = new ConcurrentHashMap<>();

    /** 构造包内可见：仅允许同包的 AgentRuntime 创建 */
    WorldChangeAccumulator() {}

    /**
     * 每秒累积一次世界变化（由 AgentRuntime 的 1s 分支对每个在线玩家调用）。
     * 首次调用只建立基准（不产生变化）。
     */
    public void accumulate(UUID player, AgentRuntime.PlayerState st, ServerPlayerEntity p) {
        if (player == null || st == null || p == null || p.getServerWorld() == null) return;
        Accum a = accums.computeIfAbsent(player, k -> new Accum());
        ServerWorld world = p.getServerWorld();
        // 移动距离：与上次累计位置比较（首次建立基准，不能靠坐标判零——出生点就是 (0,0,0)）
        if (!a.initialized) {
            a.lastX = st.x(); a.lastY = st.y(); a.lastZ = st.z();
            a.initialized = true;
        } else {
            double dx = st.x() - a.lastX, dy = st.y() - a.lastY, dz = st.z() - a.lastZ;
            a.movedDist += Math.sqrt(dx * dx + dy * dy + dz * dz);
            a.lastX = st.x(); a.lastY = st.y(); a.lastZ = st.z();
        }
        // 维度切换
        String dim = world.getRegistryKey().getValue().toString();
        if (a.lastDim != null && !a.lastDim.equals(dim)) {
            a.dimChanged = true;
            JsonObject details = new JsonObject();
            details.addProperty("from", a.lastDim);
            details.addProperty("to", dim);
            EpisodicMemory.get().record(p, EpisodicMemory.Type.DIMENSION_CHANGED,
                    "dimension_changed", EpisodicMemory.Source.RUNTIME, null, "success", null, details);
        }
        a.lastDim = dim;
        // 地面/地下切换（isSkyVisible 翻转，进入大型洞穴）
        if (a.lastInCave != st.inCave()) a.undergroundChanged = true;
        a.lastInCave = st.inCave();
        // 血量骤降
        if (a.lastHealth > 0) {
            float drop = a.lastHealth - st.health();
            if (drop > a.maxHealthDrop) a.maxHealthDrop = drop;
        }
        a.lastHealth = st.health();
        // 敌对实体数量增加（24 格内）
        if (world.getServer() != null) {
            try {
                List<HostileEntity> hostiles = world.getEntitiesByClass(HostileEntity.class,
                        new Box(p.getBlockPos()).expand(HOSTILE_SCAN_RADIUS), e -> !e.isRemoved());
                if (hostiles.size() > a.lastHostiles) a.hostilesAppeared = true;
                a.lastHostiles = hostiles.size();
            } catch (Exception ignored) {
                // 扫描失败不影响累积（世界卸载等边界）
            }
        }
    }

    /** 记录玩家开口说话（onPlayerMessage 入口调用），计入 novelty */
    public void recordPlayerTalk(UUID player) {
        if (player == null) return;
        Accum a = accums.get(player);
        if (a != null) a.playerTalked = true;
    }

    /**
     * 计算 novelty score（0~9）：变化越大越值得唤醒 Brain。
     * 移动&gt;10 格 +1 / 维度或地下切换 +2 / 血量骤降&gt;2 +3 / 新敌对 +2 / 玩家说话 +1。
     */
    public int novelty(UUID player) {
        Accum a = accums.get(player);
        if (a == null) return 0;
        int score = 0;
        if (a.movedDist > MOVE_NOTICE_DIST) score += 1;
        if (a.dimChanged || a.undergroundChanged) score += 2;
        if (a.maxHealthDrop > HEALTH_DROP_NOTICE) score += 3;
        if (a.hostilesAppeared) score += 2;
        if (a.playerTalked) score += 1;
        return score;
    }

    /**
     * Brain 思考后重置：<b>只清"本轮累积的变化"，保留世界基线</b>。
     *
     * <p>【P0-假变化修复】绝不能 remove 整个 accumulator——否则基线（lastInCave/
     * lastHostiles/lastHealth/lastX 等）一起被删，下一轮 accumulate 重新建基准，
     * 相同世界（一直在洞穴、一直有 4 个僵尸）会被误判成"刚进洞穴/刚出现怪物"，
     * novelty 恒为 4 → 每 8 秒假唤醒一次 Brain，烧 API 还制造"no task"误判。
     * 只清变化标志：移动距离/维度切换/地下切换/掉血/新敌对/玩家说话。
     * 基线字段（lastX/Y/Z、lastDim、lastInCave、lastHealth、lastHostiles）保留，
     * accumulate 每秒在基线之上对比，只有真实变化才再次抬升 novelty。
     */
    public void reset(UUID player) {
        Accum a = accums.get(player);
        if (a == null) return;
        a.movedDist = 0;
        a.dimChanged = false;
        a.undergroundChanged = false;
        a.maxHealthDrop = 0;
        a.playerTalked = false;
        a.hostilesAppeared = false;
    }

    /** 玩家退出时清理（P2，暂由 removePlayer 调用预留） */
    public void removePlayer(UUID player) {
        accums.remove(player);
    }

    /** 【世界退出清理】清空全部累积状态（服务器关闭用） */
    public void clearAll() {
        accums.clear();
    }
}
