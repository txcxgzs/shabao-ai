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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 建筑记忆系统：持久化 AI 已建造的建筑，解决"失忆"问题。
 *
 * <p><b>关键设计：方块存世界绝对坐标，不是相对原点偏移。</b>
 * 这样玩家换地方后，AI 能看到"旧建筑在世界 (339,66,66)，你在 (-111,71,640)"，
 * 自己判断距离，不会误以为脚下还有建筑。旧记忆永远有效，不需要清空。
 *
 * <p>记录内容：
 * <ul>
 *   <li>每次建造的方块列表（世界绝对坐标 + 方块 ID）</li>
 *   <li>建筑描述（LLM 的 thought）</li>
 *   <li>任务 todo 列表</li>
 * </ul>
 *
 * <p>AI 启动新任务时，记忆注入到上下文，让 AI 知道"我之前在世界哪里建了什么"。
 */
public final class BuildMemory {
    /**
     * 【版权哨兵】记忆系统内嵌版权哨兵密文，由 GuardCore.check() 解密核对作者标识。
     * 明文：版权哨兵·记忆：沙包AI内测版记忆系统为 txcxgzs 原创。
     * 删除/篡改此方法会导致完整性校验失败、AI 功能锁定。
     */
    public static String licenseStamp() {
        return "A26iBdHNJQEIS5d1h14Ttj2LdBa1vpK79PCNS28ldSyF2ApQhIFjQECWNl4OnxgGnNOq6QRwM+fRldBzb6ICiFrC6P4UYkfOTBkCidmmlNNKOwhQqlltXPx9uqHrAALmoL2QihGsFklaCYN75iOpkgi7sGH0YV";
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path MEMORY_DIR = FabricLoader.getInstance().getGameDir().resolve("shabao-ai-memory");

    /** 每个玩家的建筑记忆 */
    private static final ConcurrentHashMap<UUID, PlayerMemory> MEMORIES = new ConcurrentHashMap<>();

    /** 单个玩家的记忆。origin 只用于参考，方块坐标已是世界绝对坐标。 */
    public record PlayerMemory(List<BuildRecord> builds, List<String> todos, List<Integer> floorYs) {
        public PlayerMemory() {
            this(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
    }

    /** 楼板识别阈值：同一 Y 值上方块数达到此值才认为是楼板（3×3=9 是最小楼板） */
    private static final int FLOOR_DETECT_THRESHOLD = 9;

    /** 同建筑合并判定阈值：本次建造中心与已有记录中心距离小于此值视为同一栋建筑（A5 聚合） */
    private static final int MERGE_DISTANCE = 12;
    /** 聚合记录的 stepName 累积长度上限（防止一栋楼建 100 次把名字撑爆） */
    private static final int MAX_NAME_LEN = 200;
    /** 【M3】单条建筑记录的方块数上限：A5 聚合会把同一栋楼的每次补建累积进一条记录，长期运行无界膨胀；超限截断保留最近部分 */
    private static final int MAX_BLOCKS_PER_BUILD = 20000;

    /** 【M3】脏标记：recordBuild 高频调用（每步一次）时只标记，延迟 2 秒合并写盘，避免每步全量写几百 KB */
    private static final Set<UUID> DIRTY = ConcurrentHashMap.newKeySet();
    /** 延迟写盘执行器：单线程串行，保证同一文件不会并发写 */
    private static final ScheduledExecutorService SAVER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "shabao-build-memory-saver");
        t.setDaemon(true);
        return t;
    });

    /**
     * 单次建造记录。blocks 存世界绝对坐标，origin 存这次建筑的世界原点（补建时复用）。
     * 【P2-K】components：成功落地的构件名清单（每步 placed>0 记录一次），
     * 摘要按构件输出，AI 能判断"这层建了什么/哪里还差"，不再只有包围盒。
     */
    public record BuildRecord(String thought, String stepName,
                               int originX, int originY, int originZ,
                               List<BlockRecord> blocks, List<String> components) {}

    /** 单个方块记录（世界绝对坐标） */
    public record BlockRecord(int x, int y, int z, String blockId) {}

    /**
     * 记录一次建造。
     *
     * <p>调用方传入的 coords 直接就是世界绝对坐标，本方法直接存储。
     * 记忆不依赖任何原点，玩家换地方后旧记忆仍然有效。
     *
     * <p><b>【A5 按建筑聚合】</b>本次建造中心与已有记录中心距离 < {@link #MERGE_DISTANCE} 时，
     * 视为同一栋建筑，合并进那条记录（而非新增一条）——一栋楼永远只占 1 条，
     * 不会因 100 条上限把地基/一层记录挤掉。合并时按坐标去重（同位置保留后写方块，
     * 实体优先于 air），stepName 累积成构件清单（A4 台账雏形）。
     *
     * @param playerUuid 玩家 UUID
     * @param thought LLM 的思考描述
     * @param stepName 步骤名
     * @param coords 方块的世界绝对坐标列表 [worldX, worldY, worldZ]
     * @param blockIds 对应的方块 ID 列表
     */
    public static void recordBuild(UUID playerUuid,
                                     String thought, String stepName,
                                     List<int[]> coords, List<String> blockIds,
                                     World world) {
        PlayerMemory memory = MEMORIES.computeIfAbsent(playerUuid, k -> new PlayerMemory());
        List<BlockRecord> blocks = new ArrayList<>();
        for (int i = 0; i < coords.size() && i < blockIds.size(); i++) {
            int[] c = coords.get(i);
            // 坐标已经是世界绝对坐标，直接存
            blocks.add(new BlockRecord(c[0], c[1], c[2], blockIds.get(i)));
        }
        if (blocks.isEmpty()) return;
        // 本次建造 bbox 中心
        int[] center = bboxCenter(blocks);

        // 【A5 聚合】找同一建筑（中心距离 < 阈值）的已有记录，合并
        int mergeIdx = -1;
        for (int i = 0; i < memory.builds().size(); i++) {
            BuildRecord b = memory.builds().get(i);
            if (b.blocks().isEmpty()) continue;
            int[] bc = bboxCenter(b.blocks());
            double dist = Math.sqrt((double)(bc[0] - center[0]) * (bc[0] - center[0])
                    + (double)(bc[2] - center[2]) * (bc[2] - center[2]));
            if (dist < MERGE_DISTANCE) { mergeIdx = i; break; }
        }

        if (mergeIdx >= 0) {
            // 合并：坐标去重（实体优先于 air），保留旧 origin（补建复用），累积构件名
            BuildRecord old = memory.builds().remove(mergeIdx);
            List<BlockRecord> merged = mergeBlocks(old.blocks(), blocks);
            // 【M3】聚合记录会随每次补建增长，超上限截断保留最近部分，防内存无界膨胀
            if (merged.size() > MAX_BLOCKS_PER_BUILD) {
                merged = new ArrayList<>(merged.subList(merged.size() - MAX_BLOCKS_PER_BUILD, merged.size()));
            }
            String combinedName = (old.stepName() == null || old.stepName().isBlank())
                    ? (stepName == null ? "" : stepName)
                    : (stepName == null || stepName.isBlank() ? old.stepName()
                       : old.stepName() + "、" + stepName);
            if (combinedName.length() > MAX_NAME_LEN) {
                combinedName = "…" + combinedName.substring(combinedName.length() - MAX_NAME_LEN);
            }
            // 【P2-K】构件清单累积（去重 + 上限 20 条防膨胀），thought 保留旧描述
            List<String> components = new ArrayList<>();
            if (old.components() != null) components.addAll(old.components());
            if (stepName != null && !stepName.isBlank() && !components.contains(stepName)) {
                components.add(stepName);
            }
            if (components.size() > 20) {
                components = new ArrayList<>(components.subList(components.size() - 20, components.size()));
            }
            memory.builds().add(new BuildRecord(old.thought(), combinedName,
                    old.originX(), old.originY(), old.originZ(), merged, components));
        } else {
            // 新建筑：origin 用本次 bbox 中心作参考点
            List<String> components = new ArrayList<>();
            if (stepName != null && !stepName.isBlank()) components.add(stepName);
            memory.builds().add(new BuildRecord(thought, stepName,
                    center[0], center[1], center[2], blocks, components));
        }

        // 限制记忆大小，避免无限增长（聚合后一条=一栋楼，100 条可容纳多栋楼）
        while (memory.builds().size() > 100) {
            memory.builds().remove(0);
        }
        // 自动识别楼层Y坐标：分析这次建造的方块Y分布，合并到 floorYs
        detectAndMergeFloors(playerUuid, memory, blocks, world);
        save(playerUuid);
    }

    /** 计算一组方块的世界 bbox 中心 [cx, cy, cz] */
    private static int[] bboxCenter(List<BlockRecord> blocks) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockRecord b : blocks) {
            minX = Math.min(minX, b.x()); maxX = Math.max(maxX, b.x());
            minY = Math.min(minY, b.y()); maxY = Math.max(maxY, b.y());
            minZ = Math.min(minZ, b.z()); maxZ = Math.max(maxZ, b.z());
        }
        return new int[]{(minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2};
    }

    /**
     * 合并两组方块记录（世界坐标），按位置去重。
     * <p>实体优先：同位置先放实体方块，后到的 air 不覆盖实体（与 preparePlace 同规则）；
     * 实体方块同位置后写胜出（保留最后一次的 id）。
     */
    private static List<BlockRecord> mergeBlocks(List<BlockRecord> oldBlocks, List<BlockRecord> newBlocks) {
        java.util.Map<Long, BlockRecord> solid = new java.util.HashMap<>();
        java.util.List<BlockRecord> airs = new ArrayList<>();
        for (BlockRecord b : oldBlocks) {
            if (isAir(b.blockId())) airs.add(b);
            else solid.put(BlockPos.asLong(b.x(), b.y(), b.z()), b);
        }
        for (BlockRecord b : newBlocks) {
            if (isAir(b.blockId())) airs.add(b);
            else solid.put(BlockPos.asLong(b.x(), b.y(), b.z()), b);
        }
        java.util.List<BlockRecord> merged = new ArrayList<>(solid.values());
        // air 只在无实体方块占据的位置生效
        for (BlockRecord b : airs) {
            long key = BlockPos.asLong(b.x(), b.y(), b.z());
            if (!solid.containsKey(key)) {
                merged.add(b);
                solid.put(key, b); // 防重复 air
            }
        }
        return merged;
    }

    private static boolean isAir(String id) {
        return "air".equals(id) || "minecraft:air".equals(id);
    }

    /**
     * 从本次建造的方块中识别楼板Y坐标，合并到玩家记忆的 floorYs 列表。
     *
     * <p>识别逻辑：统计每个 Y 值上的方块数量，达到 {@link #FLOOR_DETECT_THRESHOLD}
     * 的 Y 值认为是楼板（地基、楼层楼板都算）。去重合并后按 Y 升序排列。
     * 这样 AI 后续可用 {@code walk{"floor":2}} 语义化前往指定楼层。
     *
     * @param playerUuid 玩家 UUID（用于写回 MEMORIES 映射）
     * @param memory 玩家记忆（floorYs 字段会被替换）
     * @param blocks 本次建造的方块列表
     */
    private static void detectAndMergeFloors(UUID playerUuid, PlayerMemory memory, List<BlockRecord> blocks,
                                              World world) {
        if (blocks.isEmpty()) return;
        // 按 Y 分组：统计每层的方块列表（后续要算填充率，不能只数数量）
        java.util.Map<Integer, java.util.List<BlockRecord>> byY = new java.util.HashMap<>();
        for (BlockRecord b : blocks) {
            byY.computeIfAbsent(b.y(), k -> new ArrayList<>()).add(b);
        }
        // 【H5】楼板判定 = 数量阈值 + 填充率（方块数 / 该层 xz 包围盒面积）。
        // 楼板是"实心平面"（填充率接近 1），墙体/围墙只有周长（15×15 墙一层约 25%），
        // 只按数量阈值会把每层墙 Y 误判成楼层，导致 walk{"floor":N} 走到墙层。
        // 【W-5】再加 walkable 判据：楼板方块正上方 1~2 格须为空气（可站立）。
        // 屋顶 fill 81 块虽过填充率，但上面是露天/更高方块，会被误登记为"楼层"，
        // 导致 walk{"floor":N} 目标落在屋顶方块内部。地基同理（下方实心但上方若有墙也算）
        java.util.Set<Integer> newFloors = new java.util.TreeSet<>();
        for (var entry : byY.entrySet()) {
            java.util.List<BlockRecord> layer = entry.getValue();
            if (layer.size() < FLOOR_DETECT_THRESHOLD) continue;
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (BlockRecord b : layer) {
                minX = Math.min(minX, b.x()); maxX = Math.max(maxX, b.x());
                minZ = Math.min(minZ, b.z()); maxZ = Math.max(maxZ, b.z());
            }
            long area = (long) (maxX - minX + 1) * (maxZ - minZ + 1);
            if (area <= 0) continue;
            double fillRatio = (double) layer.size() / area;
            if (fillRatio >= 0.7) {
                // 【W-5】抽样检查上方 2 格是否空气：取该层若干方块，正上方 y+1/y+2 是 air
                // 才算可站立楼层。世界为空（理论不应）时退回原判定（宽容）
                if (world != null && !isWalkableLayer(world, layer)) continue;
                newFloors.add(entry.getKey());
            }
        }
        if (newFloors.isEmpty()) return;
        // 合并到已有 floorYs（去重）
        java.util.Set<Integer> merged = new java.util.TreeSet<>(memory.floorYs());
        boolean changed = merged.addAll(newFloors);
        if (!changed) return; // 没有新增楼层，无需重建
        // 用新列表替换（record 不可变，需重建 PlayerMemory）
        List<Integer> sortedFloors = new ArrayList<>(merged);
        Collections.sort(sortedFloors);
        MEMORIES.put(playerUuid,
                new PlayerMemory(memory.builds(), memory.todos(), sortedFloors));
    }

    /**
     * 【W-5】判断一个候选楼板层是否"可站立"：抽最多 12 个方块，
     * 正上方 y+1 和 y+2 须为空气。屋顶平面正上方是露天/空气也算可站立——
     * 但屋顶之上若还有实心方块（如多层屋顶）会被排除。
     * 抽样超半数方块上方连续空气即通过，避免逐块全查的开销。
     * 【P1-C】采样从 1/4 处起跳，绕开首行外墙——原从 0 起跳，前 12 个全在墙根，
     * y+1/y+2 正好是墙，真楼板被判 false → getFloorCount<2 → finish 自检形同虚设。
     */
    private static boolean isWalkableLayer(World world, java.util.List<BlockRecord> layer) {
        int step = Math.max(1, layer.size() / 12);
        int checked = 0, airAbove = 0;
        // 【P1-C】从 1/4 处起跳，避开首行外墙（fill 生成的列表按扫描顺序，首行贴墙）
        for (int i = layer.size() / 4; i < layer.size(); i += step) {
            BlockRecord b = layer.get(i);
            checked++;
            BlockState above1 = world.getBlockState(new BlockPos(b.x(), b.y() + 1, b.z()));
            BlockState above2 = world.getBlockState(new BlockPos(b.x(), b.y() + 2, b.z()));
            if (above1.isAir() && above2.isAir()) airAbove++;
        }
        if (checked == 0) return true; // 空层不该到这，防御
        // 超半数抽样方块上方连续空气 → 视为可站立楼层
        return airAbove * 2 >= checked;
    }

    /**
     * 根据楼层号获取楼板世界 Y 坐标。
     *
     * <p>楼层号从 1 开始：1=一楼（地基层），2=二楼，依此类推。
     * floorYs 按 Y 升序排列，所以 floor=1 对应 floorYs[0]。
     *
     * @param playerUuid 玩家 UUID
     * @param floor 楼层号（从 1 开始）
     * @return 楼板 Y 坐标，若不存在返回 Integer.MIN_VALUE
     */
    public static int getFloorY(UUID playerUuid, int floor) {
        PlayerMemory memory = MEMORIES.get(playerUuid);
        if (memory == null || memory.floorYs() == null || memory.floorYs().isEmpty()) {
            return Integer.MIN_VALUE;
        }
        if (floor < 1 || floor > memory.floorYs().size()) return Integer.MIN_VALUE;
        return memory.floorYs().get(floor - 1);
    }

    /** 获取玩家记录的楼层数量。 */
    public static int getFloorCount(UUID playerUuid) {
        PlayerMemory memory = MEMORIES.get(playerUuid);
        return memory == null || memory.floorYs() == null ? 0 : memory.floorYs().size();
    }

    /**
     * 【P2-B】获取最近一条建筑的 bbox 中心 [x, y, z]，作为 finish 自检的终点 XZ。
     * 避免用玩家坐标（玩家可能站在建筑外三十格 → BFS 误报不可达）。
     * 没有建筑记录返回 null。
     */
    public static int[] getLatestBuildCenter(UUID playerUuid) {
        PlayerMemory memory = MEMORIES.get(playerUuid);
        if (memory == null || memory.builds() == null || memory.builds().isEmpty()) return null;
        BuildRecord b = memory.builds().get(memory.builds().size() - 1);
        return new int[]{b.originX(), b.originY(), b.originZ()};
    }

    /**
     * 获取玩家的建筑记忆摘要（用于注入 LLM 上下文）。
     *
     * <p>所有坐标都是世界绝对坐标，和 LLM 返回的 blocks 坐标格式一致。
     * LLM 看到世界坐标后，返回的 blocks 坐标也用世界坐标，框架直接放置。
     *
     * <p>摘要包含：
     * <ul>
     *   <li>每次建造的世界坐标范围</li>
     *   <li>距离玩家当前位置的距离（AI 能判断旧建筑是否在脚下）</li>
     *   <li>方块数量、thought 描述</li>
     * </ul>
     *
     * @param playerUuid 玩家 UUID
     * @param playerX 玩家当前世界 X（用于计算距离）
     * @param playerY 玩家当前世界 Y
     * @param playerZ 玩家当前世界 Z
     */
    public static String getSummary(UUID playerUuid,
                                      int playerX, int playerY, int playerZ) {
        PlayerMemory memory = MEMORIES.get(playerUuid);
        if (memory == null || memory.builds().isEmpty()) return "无历史建筑记录。";

        // 【M4】快照拷贝：getSummary 可能被主线程的 /ai memory 命令调用，
        // 而 LLM 回调线程可能正 recordBuild（remove/add 同一 ArrayList）→ 并发 CME。
        // 遍历前拷贝一份，避免并发修改异常。
        List<BuildRecord> snapshot = new ArrayList<>(memory.builds());

        StringBuilder sb = new StringBuilder();
        sb.append("玩家当前位置：世界坐标 (").append(playerX).append(",").append(playerY).append(",").append(playerZ).append(")\n");
        // 【A5】记录已按建筑聚合（一条=一栋建筑），stepName 是累积构件清单
        sb.append("历史建造记录（共").append(snapshot.size()).append("栋建筑，坐标为世界绝对坐标）：\n");

        int totalBlocks = 0;
        for (int i = 0; i < snapshot.size(); i++) {
            BuildRecord build = snapshot.get(i);
            sb.append("  ").append(i + 1).append(". [").append(build.stepName()).append("] ");
            // 【L4】thought 可能为 null（旧记录），判空避免把 "null" 注入 LLM 上下文
            sb.append(build.thought() == null || build.thought().isBlank() ? "（无描述）"
                    : build.thought()).append(" (").append(build.blocks().size()).append("方块)");
            // 【P2-K】构件清单：已成功落地的 step 名，AI 据此判断"这层建了什么/哪里还差"
            if (build.components() != null && !build.components().isEmpty()) {
                sb.append("\n     已建构件: ").append(String.join("、", build.components()));
            }

            // 计算这次建造的世界坐标范围 + 距离玩家的距离
            if (!build.blocks().isEmpty()) {
                int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
                int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
                int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
                for (BlockRecord b : build.blocks()) {
                    minX = Math.min(minX, b.x()); maxX = Math.max(maxX, b.x());
                    minY = Math.min(minY, b.y()); maxY = Math.max(maxY, b.y());
                    minZ = Math.min(minZ, b.z()); maxZ = Math.max(maxZ, b.z());
                }
                // 建筑中心点（世界坐标）
                int cx = (minX + maxX) / 2, cy = (minY + maxY) / 2, cz = (minZ + maxZ) / 2;
                double dist = Math.sqrt((double)(cx - playerX) * (cx - playerX)
                        + (double)(cy - playerY) * (cy - playerY)
                        + (double)(cz - playerZ) * (cz - playerZ));
                sb.append(String.format("\n     世界坐标范围: x[%d~%d] y[%d~%d] z[%d~%d], 中心(%d,%d,%d) 距玩家%.0f格",
                        minX, maxX, minY, maxY, minZ, maxZ, cx, cy, cz, dist));
                if (dist > 16) {
                    sb.append("（旧建筑不在脚下，如需继续建请先走过去）");
                }
            }
            sb.append("\n");
            totalBlocks += build.blocks().size();
        }
        sb.append("累计已建造 ").append(totalBlocks).append(" 个方块。\n");
        sb.append("【提示】以上坐标都是世界绝对坐标，你返回的 blocks 坐标也用世界坐标。\n");

        // 列出已识别的楼层（供 AI 用 walk{"floor":N} 语义化前往）
        if (memory.floorYs() != null && !memory.floorYs().isEmpty()) {
            sb.append("已记录楼层（可用 walk{\"floor\":N} 前往，N 从1开始）：\n");
            for (int i = 0; i < memory.floorYs().size(); i++) {
                int fy = memory.floorYs().get(i);
                double floorDist = Math.abs((double) fy - playerY);
                sb.append("  ").append(i + 1).append("楼: y=").append(fy)
                        .append(" (距当前Y ").append((int) floorDist).append("格)\n");
            }
        } else {
            sb.append("【提示】尚未记录楼层，建造楼板（fill/floor shape）后会自动识别楼层Y坐标。\n");
        }

        // todo 已改为会话内作用域（见 AgentExecutor.executePlan），不再持久化展示，
        // 避免上个任务的规划污染无关会话的上下文。

        return sb.toString();
    }

    /**
     * 清空玩家记忆。
     *
     * <p>关键修复：不能只删文件，因为 Windows 文件锁可能导致删除失败（异常被吞掉），
     * 下次 load 又会重新加载旧数据。改为：
     * <ol>
     *   <li>内存中放入空记忆（不是 remove），阻止 load 重新加载</li>
     *   <li>文件覆盖写空 JSON，即使删除失败内容也已被清空</li>
     * </ol>
     */
    public static void clear(UUID playerUuid) {
        // 放空记忆到内存，load 时会跳过（已有条目不重复加载）
        MEMORIES.put(playerUuid, new PlayerMemory());
        try {
            // 覆盖写空 JSON，而不只是删除文件
            // 这样即使 deleteIfExists 失败（Windows 文件锁），内容也已被清空
            Path file = getMemoryFile(playerUuid);
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(new PlayerMemory()), StandardCharsets.UTF_8);
        } catch (IOException ignored) {}
    }

    /**
     * 从磁盘加载玩家记忆。
     *
     * <p>如果内存中已有条目（包括 clear 后的空记忆），不重复加载，避免覆盖清空操作。
     */
    public static void load(UUID playerUuid) {
        // 已有内存条目（包括 clear 后的空记忆），不重复加载
        if (MEMORIES.containsKey(playerUuid)) return;
        try {
            Path file = getMemoryFile(playerUuid);
            if (Files.exists(file)) {
                PlayerMemory memory = GSON.fromJson(Files.readString(file), PlayerMemory.class);
                if (memory != null) {
                    // 兼容旧JSON：floorYs 字段可能不存在（null），填充空列表避免 NPE
                    if (memory.floorYs() == null) {
                        memory = new PlayerMemory(memory.builds(), memory.todos(), new ArrayList<>());
                    }
                    if (memory.builds() == null) {
                        memory = new PlayerMemory(new ArrayList<>(), memory.todos(), memory.floorYs());
                    }
                    if (memory.todos() == null) {
                        memory = new PlayerMemory(memory.builds(), new ArrayList<>(), memory.floorYs());
                    }
                    // 【P2-K】兼容旧JSON：BuildRecord 的 components 字段可能不存在（null），
                    // 逐条修复为不变量（非空列表），否则 getSummary/合并时判空逻辑到处都是
                    if (!memory.builds().isEmpty()) {
                        List<BuildRecord> fixed = new ArrayList<>();
                        boolean changed = false;
                        for (BuildRecord b : memory.builds()) {
                            if (b.components() == null) {
                                List<String> comps = new ArrayList<>();
                                if (b.stepName() != null && !b.stepName().isBlank()) comps.add(b.stepName());
                                fixed.add(new BuildRecord(b.thought(), b.stepName(),
                                        b.originX(), b.originY(), b.originZ(), b.blocks(), comps));
                                changed = true;
                            } else {
                                fixed.add(b);
                            }
                        }
                        if (changed) {
                            memory = new PlayerMemory(fixed, memory.todos(), memory.floorYs());
                        }
                    }
                    MEMORIES.put(playerUuid, memory);
                }
            }
        } catch (Exception ignored) {}
    }

    /** 保存到磁盘。recordBuild 每步调用一次，改为延迟 2 秒合并写盘（M3），服务器停止时 flushAll 兜底 */
    private static void save(UUID playerUuid) {
        DIRTY.add(playerUuid);
        SAVER.schedule(() -> flush(playerUuid), 2, TimeUnit.SECONDS);
    }

    /** 写盘单个玩家记忆（幂等：非脏标记直接跳过） */
    private static void flush(UUID playerUuid) {
        if (!DIRTY.remove(playerUuid)) return;
        try {
            PlayerMemory memory = MEMORIES.get(playerUuid);
            if (memory == null) return;
            Path file = getMemoryFile(playerUuid);
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(memory), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    /** 【M3】立即落盘所有待写记忆（服务器停止时调用，保证延迟任务不丢数据） */
    public static void flushAll() {
        for (UUID uuid : new ArrayList<>(DIRTY)) {
            flush(uuid);
        }
    }

    private static Path getMemoryFile(UUID playerUuid) {
        return MEMORY_DIR.resolve(playerUuid.toString() + ".json");
    }
}
