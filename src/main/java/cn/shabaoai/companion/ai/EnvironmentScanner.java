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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 环境感知：收集玩家周围的环境信息供 LLM 决策。
 *
 * <p>提供两类信息：
 * <ol>
 *   <li>{@link #snapshot} —— 对话开始时的基础快照：生物群系、时间天气、
 *       玩家状态、附近实体、初始小范围地面扫描。</li>
 *   <li>{@link #scanArea} —— LLM 主动请求的区域扫描：返回指定中心点周围的
 *       高度图（每列最高实体方块 y 值）+ 表层方块类型统计。</li>
 * </ol>
 *
 * <p>所有坐标用世界绝对坐标，LLM 直接看到真实坐标，返回的方块坐标也是世界坐标，
 * 框架直接放置，不需要原点转换。
 */
public final class EnvironmentScanner {
    /**
     * 【版权哨兵】感知系统内嵌版权哨兵密文，由 GuardCore.check() 解密核对作者标识。
     * 明文：版权哨兵·感知：沙包AI内测版感知系统由 txcxgzs 构建。
     * 删除/篡改此方法会导致完整性校验失败、AI 功能锁定。
     */
    public static String licenseStamp() {
        return "A2/8yWfMwmdEw16uiU/hdE9iBQ2TdbgcGQkZRZIziLrX2c78jOgCw5xFJlnFHJ8FmSrk0S2V8+Xde6XbI8WHytbAt2TV0dohsFY7WWwJgZtQy4nYj8u4C4bpaBeIRlKRqvqDVrKuYWtD9yYh5k6AZcSqeAciUk";
    }

    private EnvironmentScanner() {}

    /**
     * 对话开始时的环境快照。
     * @param player 玩家
     */
    public static JsonObject snapshot(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        BlockPos playerPos = player.getBlockPos();
        JsonObject o = new JsonObject();
        // 【第2批·来源标注】快照来源头：LLM 一眼区分"这是框架提供的观察数据"
        o.addProperty("source", "environment_snapshot");

        // 世界信息
        o.addProperty("biome", biomeId(world, playerPos));
        o.addProperty("dimension", world.getRegistryKey().getValue().toString());
        o.addProperty("time_of_day", timeLabel(world));
        o.addProperty("weather", weatherLabel(world));
        o.addProperty("is_day", world.isDay());

        // 玩家世界坐标（所有建造坐标都基于此，LLM 直接用世界坐标）
        JsonObject p = new JsonObject();
        p.addProperty("x", player.getBlockX());
        p.addProperty("y", player.getBlockY());
        p.addProperty("z", player.getBlockZ());
        p.addProperty("facing", facingName(player));
        p.addProperty("health", Math.round(player.getHealth()));
        p.addProperty("game_mode", player.interactionManager.getGameMode().getName());
        o.add("player", p);

        // 附近实体（最多 10 种，按距离排序）
        o.add("nearby_entities", nearbyEntities(world, player, 24));

        // 【P1-L3①】地面基准：把"地基铺哪层 / 墙门放哪层"直接算好喂给 LLM，
        // 禁止 AI 从 Minecraft 坐标语义里自己推导（它一推导就自我推翻、烧几十秒）。
        // 【修D】surface_block_y 不再锚玩家脚底（玩家悬浮/站在建筑上时脚底不是地面）：
        // 取周围 9×9 高度图众数——真实地面，悬浮时照样得到正确草顶 Y（15:07 悬空屋根因）
        // 【修树】用 realGroundY（剔树叶+跳树干），山坡+树场景众数不再被树冠拉高到树顶
        int groundY = majorityGroundY(world, playerPos);
        // 【P1-L3②】地形统计：9×9 真实地表 Y 的中位数/min/max + 树数。
        // 中位数是"清障夷平的目标高度"（用户要求：取中位/平均，不用最低点，避免挖深）
        int[] terrainStat = terrainStats(world, playerPos); // {median, min, max, treeCount}
        JsonObject g = new JsonObject();
        g.addProperty("surface_block_y", groundY);      // 周围地面众数（真实地表方块 Y）
        g.addProperty("foundation_y", groundY);         // 地基铺设层 = 地面层
        g.addProperty("terrain_median_y", terrainStat[0]); // 地表 Y 中位数（clear 夷平推荐高度）
        g.addProperty("terrain_min_y", terrainStat[1]);    // 地表最低 Y（坡度下界）
        g.addProperty("terrain_max_y", terrainStat[2]);    // 地表最高 Y（坡度上界，不含树冠）
        g.addProperty("terrain_slope", terrainStat[2] - terrainStat[1]); // 高差：>2 视为坡地，建议先 clear
        g.addProperty("tree_count", terrainStat[3]);       // 周围 9×9 内的树数量（有树建房前先 clear）
        g.addProperty("first_wall_door_y", groundY + 1); // 第一层墙 / 门下半 Y = 地基上面一层
        g.addProperty("furniture_y", groundY + 1);      // 【P0-M6 修3】家具/装饰放置层 = 楼板上面一层（与门同层）
        g.addProperty("stand_space_y", (groundY + 1) + " ~ " + (groundY + 2)); // 站立空间（脚下~头顶）
        o.add("ground_baseline", g);

        // 【夷平数据】候选地基分析：玩家面朝方向前方 16×16 区域，算推荐地基高度与清理量
        o.add("build_site", analyzeBuildSite(world, playerPos, Math.round(player.getYaw())));

        // 【门前探测】4 个主方向，门前 1 格（y、y+1）有无方块，供 AI 选门位/判断是否需清障
        o.add("doorway_probe", probeDoorways(world, playerPos, Math.round(player.getYaw())));

        // 初始小范围地面扫描（半径 4），让 LLM 对玩家脚下地形有基本概念
        o.add("ground_scan", scanArea(world, playerPos, 4));

        return o;
    }

    /**
     * 【修D】取玩家周围 9×9 区域的 heightmap（MOTION_BLOCKING）众数作为真实地面 Y。
     *
     * <p>旧实现用 player.getBlockY()-1（脚底）当地面：玩家悬浮（飞行/站在楼板上）时
     * 脚底不是地面，baseline 跟着浮起来，AI 照抄 baseline 就把地基建到空中（15:07 悬空屋）。
     * 取众数能容忍悬浮：周围 81 格多数是真实地形，玩家自己悬着只贡献 1 格噪声。
     *
     * <p>注意：玩家站在已建好的楼板/屋顶上时，周围 9×9 若大部分是建筑楼板，
     * 众数会取到楼板层——这正是期望行为（AI 要在同一层继续建）。悬浮在地形上空
     * 而无建筑时众数=真实地表，两类场景都正确。
     *
     * @return 众数地面 Y（方块坐标）。异常/空时回退 player.getBlockY()-1。
     */
    private static int majorityGroundY(ServerWorld world, BlockPos center) {
        Map<Integer, Integer> freq = new HashMap<>();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                int cx = center.getX() + dx;
                int cz = center.getZ() + dz;
                // 【修树】用 realGroundY（MOTION_BLOCKING_NO_LEAVES + 跳过树干）——
                // 山坡+树场景下旧 MOTION_BLOCKING 会把树冠顶算进列高，众数被树拉高成"树顶"
                int ty = realGroundY(world, cx, cz);
                if (ty >= world.getBottomY()) {
                    freq.merge(ty, 1, Integer::sum);
                }
            }
        }
        // 取最高频；平局取较大 Y（宁可选地面层，不选地下挖空的层）
        int bestY = center.getY() - 1;
        int bestCount = -1;
        for (var e : freq.entrySet()) {
            if (e.getValue() > bestCount || (e.getValue() == bestCount && e.getKey() > bestY)) {
                bestY = e.getKey();
                bestCount = e.getValue();
            }
        }
        return bestY;
    }

    /**
     * 【修树】真实地表 Y：忽略树冠（MOTION_BLOCKING_NO_LEAVES），并从顶向下跳过
     * 树干（LOGS 标签）找到真实地面。树不再把"地面基准/地基高度"抬高到树顶。
     * @return 该列真实地表方块 Y（方块坐标）；无有效地面时返回 NO_LEAVES 顶
     */
    public static int realGroundY(ServerWorld world, int x, int z) {
        int top = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        if (top < world.getBottomY()) return top;
        // 树冠已被 NO_LEAVES 排除，但树干仍会作为最高遮挡；沿列向下跳过树干找地面
        while (top >= world.getBottomY() && isLog(world.getBlockState(new BlockPos(x, top, z)))) {
            top--;
        }
        return top;
    }

    private static boolean isLog(BlockState s) {
        return s.isIn(net.minecraft.registry.tag.BlockTags.LOGS);
    }

    /**
     * 【P1-L3②】周围 9×9 地形统计：真实地表 Y 的中位数 / min / max / 树数量。
     * 中位数即"clear 夷平"的推荐目标高度（削高不挖深）。
     * @return {medianY, minY, maxY, treeCount}
     */
    private static int[] terrainStats(ServerWorld world, BlockPos center) {
        List<Integer> ys = new java.util.ArrayList<>();
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int treeCount = 0;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                int cx = center.getX() + dx;
                int cz = center.getZ() + dz;
                int gy = realGroundY(world, cx, cz);
                ys.add(gy);
                minY = Math.min(minY, gy);
                maxY = Math.max(maxY, gy);
                // 树判断：MOTION_BLOCKING 顶（含树冠）比 NO_LEAVES 顶（不含树冠）高 ≥2 格 → 有树冠
                int withLeaves = world.getTopY(Heightmap.Type.MOTION_BLOCKING, cx, cz);
                int noLeaves = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, cx, cz);
                if (withLeaves - noLeaves >= 2) treeCount++;
            }
        }
        ys.sort(java.util.Comparator.naturalOrder());
        int median = ys.get(ys.size() / 2);
        return new int[]{median, minY, maxY, treeCount};
    }

    /**
     * 【夷平数据】玩家面朝方向前方 16×16 候选地基分析。
     * <ul>
     *   <li>矩形：以玩家位置向 facing 前方偏移 8 格为中心，覆盖 16×16</li>
     *   <li>recommended_foundation_y：矩形内真实地表 Y 中位数（clear 夷平后地基放这层）</li>
     *   <li>高差 / 需清理列数 / 树数 / 水体标记：AI 判断是否值得在此建房、要不要先 clear</li>
     * </ul>
     */
    private static JsonObject analyzeBuildSite(ServerWorld world, BlockPos playerPos, float yaw) {
        JsonObject site = new JsonObject();
        int[] delta = facingDelta(Math.round(yaw)); // 玩家面朝 (dx,dz)
        int cx = playerPos.getX() + delta[0] * 8;
        int cz = playerPos.getZ() + delta[1] * 8;
        int hw = 8; // 半宽：16×16
        List<Integer> ys = new java.util.ArrayList<>();
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE, treeCount = 0, waterCount = 0;
        int needClear = 0; // 高于中位数的非空气列数（估算清理量）
        for (int dx = -hw; dx <= hw; dx++) {
            for (int dz = -hw; dz <= hw; dz++) {
                int wx = cx + dx, wz = cz + dz;
                int gy = realGroundY(world, wx, wz);
                ys.add(gy);
                minY = Math.min(minY, gy);
                maxY = Math.max(maxY, gy);
                int withLeaves = world.getTopY(Heightmap.Type.MOTION_BLOCKING, wx, wz);
                int noLeaves = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, wx, wz);
                if (withLeaves - noLeaves >= 2) treeCount++;
                BlockState top = world.getBlockState(new BlockPos(wx, gy, wz));
                if (top.getBlock() == net.minecraft.block.Blocks.WATER) waterCount++;
            }
        }
        ys.sort(java.util.Comparator.naturalOrder());
        int median = ys.get(ys.size() / 2);
        for (int dx = -hw; dx <= hw; dx++) {
            for (int dz = -hw; dz <= hw; dz++) {
                int wx = cx + dx, wz = cz + dz;
                if (realGroundY(world, wx, wz) > median) needClear++;
            }
        }
        site.addProperty("site_center_x", cx);
        site.addProperty("site_center_z", cz);
        site.addProperty("size", "16×16");
        site.addProperty("recommended_foundation_y", median); // clear 夷平后地基推荐高度
        site.addProperty("terrain_median_y", median);
        site.addProperty("terrain_min_y", minY);
        site.addProperty("terrain_max_y", maxY);
        site.addProperty("slope", maxY - minY);               // 高差：>2 建议先 clear 夷平
        site.addProperty("tree_count", treeCount);
        site.addProperty("water_columns", waterCount);
        site.addProperty("columns_above_median", needClear);   // 需要削掉的列数（含树）
        site.addProperty("need_clear", treeCount > 0 || needClear > 0 || (maxY - minY) > 2);
        return site;
    }

    /** 【门前探测】4 个主方向，门前 1 格（门下半Y、门下半Y+1）方块 id，供 AI 选门/判断清障 */
    private static JsonArray probeDoorways(ServerWorld world, BlockPos playerPos, float yaw) {
        JsonArray arr = new JsonArray();
        String[] names = {"north", "south", "east", "west"};
        int[][] deltas = {{0, -1}, {0, 1}, {1, 0}, {-1, 0}};
        // 以玩家面朝方向为"前方"排序（便于 AI 关联 doorway_probe 与门 facing）
        int front = switch (((int) Math.round(yaw) + 45) / 90 % 4) {
            case 0 -> 1; // 朝南 → 前方=1(south)
            case 1 -> 3; // 朝西 → 前方=3(west)
            case 2 -> 0; // 朝北 → 前方=0(north)
            default -> 2; // 朝东 → 前方=2(east)
        };
        // 【改】探测层 = 门下半 Y（=first_wall_door_y=地基上面一层），与 ground_baseline 一致。
        // 旧实现用 player.getY()：玩家站在自己建的楼板/山上时探测的是"脚下"那层，与门洞对不上。
        int groundY = majorityGroundY(world, playerPos);
        int wy = groundY + 1;
        for (int k = 0; k < 4; k++) {
            int i = (front + k) % 4; // 前方优先排列
            int wx = playerPos.getX() + deltas[i][0];
            int wz = playerPos.getZ() + deltas[i][1];
            JsonObject d = new JsonObject();
            d.addProperty("direction", names[i]);
            d.addProperty("front", k == 0);
            d.addProperty("door_y", wy); // 本方向门下半 Y（放门用这层）
            d.addProperty("blocked_lower", BlockCodec.idOf(world.getBlockState(new BlockPos(wx, wy, wz))));
            d.addProperty("blocked_upper", BlockCodec.idOf(world.getBlockState(new BlockPos(wx, wy + 1, wz))));
            d.addProperty("clear", world.getBlockState(new BlockPos(wx, wy, wz)).isAir()
                    && world.getBlockState(new BlockPos(wx, wy + 1, wz)).isAir());
            arr.add(d);
        }
        return arr;
    }

    /** 玩家 yaw → 面朝方向 (dx, dz)（与 AgentAction.facingDelta 同规则：南=+z 北=-z 东=+x 西=-x） */
    private static int[] facingDelta(int yaw) {
        return switch ((yaw + 45) / 90 % 4) {
            case 0 -> new int[]{0, 1};  // 南
            case 1 -> new int[]{-1, 0}; // 西
            case 2 -> new int[]{0, -1}; // 北
            default -> new int[]{1, 0}; // 东
        };
    }

    /**
     * 扫描指定区域，返回高度图 + 表层方块统计。
     *
     * @param world  服务端世界
     * @param center 扫描中心（世界绝对坐标）
     * @param radius 半径（格子数），会被 clamp 到配置上限
     * @return JSON 对象，含高度图（世界坐标）和表层方块统计
     */
    public static JsonObject scanArea(ServerWorld world, BlockPos center, int radius) {
        int r = Math.max(1, Math.min(radius, 32));
        JsonObject result = new JsonObject();
        // 【第2批·来源标注】扫描结果来源头
        result.addProperty("source", "environment_scan");
        // 中心点用世界绝对坐标
        result.addProperty("center_x", center.getX());
        result.addProperty("center_y", center.getY());
        result.addProperty("center_z", center.getZ());
        result.addProperty("radius", r);
        result.addProperty("size", r * 2 + 1);

        // 高度图：每列最高实体方块的 y 值（世界绝对坐标）
        JsonArray heightmap = new JsonArray();
        Map<String, Integer> surfaceCounts = new HashMap<>();
        for (int dx = -r; dx <= r; dx++) {
            JsonArray row = new JsonArray();
            for (int dz = -r; dz <= r; dz++) {
                int cx = center.getX() + dx;
                int cz = center.getZ() + dz;
                int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING, cx, cz) - 1;
                // 直接用世界 y 坐标，LLM 能直接理解真实高度
                row.add(topY);
                if (topY >= world.getBottomY()) {
                    BlockState top = world.getBlockState(new BlockPos(cx, topY, cz));
                    String id = BlockCodec.idOf(top);
                    surfaceCounts.merge(id, 1, Integer::sum);
                }
            }
            heightmap.add(row);
        }
        result.add("heightmap", heightmap);

        // 表层方块类型统计（取前 8 种最多的）
        JsonArray blocks = new JsonArray();
        surfaceCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(8)
                .forEach(e -> {
                    JsonObject item = new JsonObject();
                    item.addProperty("block", e.getKey());
                    item.addProperty("count", e.getValue());
                    blocks.add(item);
                });
        result.add("surface_blocks", blocks);

        return result;
    }

    // ===== 辅助方法 =====

    private static String biomeId(ServerWorld world, BlockPos pos) {
        try {
            RegistryEntry<Biome> entry = world.getBiome(pos);
            return entry.getKey().map(RegistryKey::getValue).map(Object::toString).orElse("unknown");
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String timeLabel(ServerWorld world) {
        long t = world.getTimeOfDay() % 24000;
        if (t < 1000) return "黎明(" + t + ")";
        if (t < 6000) return "上午(" + t + ")";
        if (t < 12000) return "下午(" + t + ")";
        if (t < 13000) return "黄昏(" + t + ")";
        return "夜晚(" + t + ")";
    }

    private static String weatherLabel(ServerWorld world) {
        if (world.isThundering()) return "雷暴";
        if (world.isRaining()) return "雨";
        return "晴";
    }

    private static String facingName(ServerPlayerEntity player) {
        // 根据 yaw 角度转成八方位中文名，方便 LLM 理解
        int yaw = (int) ((player.getYaw() % 360 + 360) % 360);
        return switch ((yaw + 22) / 45 % 8) {
            case 0 -> "南";
            case 1 -> "西南";
            case 2 -> "西";
            case 3 -> "西北";
            case 4 -> "北";
            case 5 -> "东北";
            case 6 -> "东";
            default -> "东南";
        };
    }

    private static JsonArray nearbyEntities(ServerWorld world, ServerPlayerEntity player, double radius) {
        Box box = player.getBoundingBox().expand(radius);
        List<LivingEntity> entities = world.getEntitiesByClass(LivingEntity.class, box,
                e -> e != player && e.isAlive());
        // 按类型分组计数，取最近的若干种
        Map<String, int[]> counts = new HashMap<>(); // name -> [count, minDistance2]
        for (LivingEntity e : entities) {
            String name = e.getName().getString();
            if (name.isBlank()) name = e.getType().getName().getString();
            // 【第2批·来源标注】实体名转义：剥掉 § 颜色码与控制字符，
            // 防止玩家给实体起的怪名（§k 闪烁/换行/引号）破坏注入 LLM 上下文的 JSON 格式
            name = sanitizeEntityName(name);
            double d2 = e.squaredDistanceTo(player);
            int[] v = counts.computeIfAbsent(name, k -> new int[]{0, Integer.MAX_VALUE});
            v[0]++;
            v[1] = (int) Math.min(v[1], d2);
        }
        JsonArray arr = new JsonArray();
        counts.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> e.getValue()[1])) // 按最近距离排序
                .limit(10)
                .forEach(e -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("name", e.getKey());
                    o.addProperty("count", e.getValue()[0]);
                    o.addProperty("nearest_distance", (int) Math.sqrt(e.getValue()[1]));
                    arr.add(o);
                });
        return arr;
    }

    /**
     * 【第2批·来源标注】实体名消毒：去掉 § 颜色码（§k/§r 等）与控制字符，
     * 再替换引号/反斜杠/换行，保证注入 LLM 上下文的文本是干净 JSON 安全字符串。
     */
    private static String sanitizeEntityName(String raw) {
        if (raw == null) return "unknown";
        // 去掉 §x 颜色码（§ 后跟任意字符）
        String s = raw.replaceAll("§.", "");
        // 控制字符（换行/回车/制表等）统一成空格
        s = s.replaceAll("[\\p{Cntrl}]", " ");
        // 引号与反斜杠转义，避免破坏 JSON 字符串
        s = s.replace("\\", "\\\\").replace("\"", "\\\"");
        s = s.trim();
        return s.isEmpty() ? "unknown" : s;
    }
}
