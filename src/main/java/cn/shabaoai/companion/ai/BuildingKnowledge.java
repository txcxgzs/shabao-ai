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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 建筑教程知识库：从 building_knowledge.json 加载建筑布局/结构教程。
 *
 * <p>与 {@link BlockKnowledge} 不同，本类加载的是"建筑布局指导"：
 * <ul>
 *   <li>楼梯间布局（直线/L型/U型/螺旋，尺寸、转角处理）</li>
 *   <li>矿洞结构（螺旋矿井、分支矿法）</li>
 *   <li>庇护所类型（温馨小屋、防御工事）</li>
 *   <li>房屋形状（坡屋顶、平顶、多层结构）</li>
 * </ul>
 *
 * <p><b>查询方式</b>：Agent 通过 query_building 动作查询建筑教程，
 * 传入建筑类型 ID（如 stairwell、spiral_mining），返回该建筑的布局指导供 LLM 决策参考。
 *
 * <p><b>加载策略</b>：
 * <ol>
 *   <li>优先从 mod 内置资源 /building_knowledge.json 加载（打包在 jar 里）</li>
 *   <li>如果游戏目录 shabao-ai-memory/building_knowledge.json 存在，覆盖加载（方便更新）</li>
 *   <li>建立教程 ID → 教程条目的映射，查询是 O(1)</li>
 * </ol>
 */
public final class BuildingKnowledge {
    /** 教程 ID → 教程条目的映射（如 stairwell → 楼梯间布局） */
    private static final Map<String, BuildingGuide> BY_ID = new ConcurrentHashMap<>();
    /** 所有教程条目列表 */
    private static final List<BuildingGuide> GUIDES = new ArrayList<>();
    /** 是否已加载 */
    private static volatile boolean loaded = false;

    /** 单个建筑教程条目 */
    public record BuildingGuide(String id, String name, String category, String guide) {}

    /** 确保知识库已加载（懒加载，首次查询时触发） */
    private static void ensureLoaded() {
        if (loaded) return;
        synchronized (BuildingKnowledge.class) {
            if (loaded) return;
            try {
                loadInternal();
            } catch (Exception e) {
                // 【M11】JSON 损坏抛的是 JsonParseException（RuntimeException），
                // 不 catch 会导致每次 query 都重新加载并抛异常；降级为空知识库并置位
                AgentLogger.logError(0, "建筑知识库：加载失败（JSON 损坏?） - " + e.getMessage());
            } finally {
                loaded = true;
            }
        }
    }

    /** 内部加载逻辑：先从 jar 资源加载，再用外部文件覆盖 */
    private static void loadInternal() {
        // 1. 从 mod 内置资源加载
        try (InputStream in = BuildingKnowledge.class.getResourceAsStream("/building_knowledge.json")) {
            if (in != null) {
                try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    loadFromJson(JsonParser.parseReader(reader).getAsJsonObject());
                    AgentLogger.logInfo("建筑知识库：从内置资源加载 " + GUIDES.size() + " 个教程");
                }
            }
        } catch (IOException e) {
            AgentLogger.logError(0, "建筑知识库：加载内置资源失败 - " + e.getMessage());
        }

        // 2. 外部文件覆盖（方便不重新打包就更新知识库）
        Path external = FabricLoader.getInstance().getGameDir()
                .resolve("shabao-ai-memory").resolve("building_knowledge.json");
        if (Files.exists(external)) {
            try {
                String json = Files.readString(external, StandardCharsets.UTF_8);
                loadFromJson(JsonParser.parseString(json).getAsJsonObject());
                AgentLogger.logInfo("建筑知识库：外部文件覆盖加载完成");
            } catch (IOException e) {
                AgentLogger.logError(0, "建筑知识库：加载外部文件失败 - " + e.getMessage());
            }
        }
    }

    /** 从 JSON 对象加载知识库到内存映射 */
    private static void loadFromJson(JsonObject root) {
        JsonArray guides = root.getAsJsonArray("guides");
        if (guides == null) return;
        for (JsonElement e : guides) {
            JsonObject g = e.getAsJsonObject();
            String id = g.get("id").getAsString();
            String name = g.get("name").getAsString();
            String category = g.get("category").getAsString();
            String guide = g.has("guide") ? g.get("guide").getAsString() : "";
            BuildingGuide entry = new BuildingGuide(id, name, category, guide);
            // 【v22·启动期一致性断言】教程里的螺旋 facing 表必须与代码 dirOf 推导逐行一致——
            // 上次代码与教程"自洽地一起翻 180°"且无任何交叉验证发现，这里把分叉变成启动即报错：
            // 不一致直接拒绝注入该教程，模型永远看不到错表（手写 blocks 也不会照着错表写）。
            try {
                verifySpiralFacingTable(entry);
            } catch (IllegalArgumentException ex) {
                AgentLogger.logError(0, "建筑知识库：教程 " + id + " 的螺旋 facing 表与代码推导不一致，"
                        + "拒绝注入该教程（请同步 building_knowledge.json 与 AgentAction.dirOf） - " + ex.getMessage());
                continue;
            }
            GUIDES.add(entry);
            BY_ID.put(id, entry);
        }
    }

    /**
     * 【v22·启动期断言】解析教程正文里 "(x,z) facing=方向" 形式的螺旋方向表（如
     * "(0,0) facing=north"），与代码唯一真源 {@link AgentAction#dirOf} 推导值逐行比对。
     * 位置循环 (0,0)->(1,0)->(1,1)->(0,1) 连续回绕，每级来向 = 上一位置→本位置；
     * 第一级来向取"上一圈末级"(0,1)→(0,0)。非螺旋表行（无 facing=）自动跳过。
     *
     * @throws IllegalArgumentException 教程表任一行与推导不一致
     */
    private static void verifySpiralFacingTable(BuildingGuide entry) {
        // 只对含螺旋 facing 表的教程做断言（螺旋矿井等无 "(x,z) facing=" 行，跳过）
        if (!entry.guide().contains("facing=north") && !entry.guide().contains("facing=east")
                && !entry.guide().contains("facing=south") && !entry.guide().contains("facing=west")) {
            return;
        }
        int[][] positions = {{0,0},{1,0},{1,1},{0,1}};
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\((\\d),(\\d)\\) facing=(north|south|east|west)");
        java.util.regex.Matcher m = p.matcher(entry.guide());
        boolean any = false;
        while (m.find()) {
            int x = Integer.parseInt(m.group(1));
            int z = Integer.parseInt(m.group(2));
            String got = m.group(3);
            int idx = -1;
            for (int i = 0; i < positions.length; i++) {
                if (positions[i][0] == x && positions[i][1] == z) { idx = i; break; }
            }
            if (idx < 0) continue; // 不在 2×2 位置循环上的行（如 3×3 矿井的坐标），不参与断言
            any = true;
            // 期望 facing = 位置增量推导（与代码 spiral/stairwell 分支同一条真源）
            String expect = AgentAction.dirOf(positions[idx][0] - positions[(idx + 3) % 4][0],
                    positions[idx][1] - positions[(idx + 3) % 4][1]);
            if (!expect.equals(got)) {
                throw new IllegalArgumentException("(" + x + "," + z + ") facing=" + got + " 应为 " + expect);
            }
        }
        if (!any) {
            AgentLogger.logInfo("建筑知识库：教程 " + entry.id() + " 含 facing 字样但无 (x,z) facing= 螺旋表行，跳过断言");
        }
    }

    /**
     * 按教程 ID 查询建筑教程。
     *
     * @param guideId 教程 ID（如 "stairwell"、"spiral_mining"、"branch_mining"）
     * @return 教程条目，或 null（未找到）
     */
    public static BuildingGuide query(String guideId) {
        ensureLoaded();
        return BY_ID.get(guideId);
    }

    /**
     * 按类别查询所有教程（如查所有"矿洞"类教程）。
     *
     * @param category 类别名（垂直交通/矿洞/庇护所/防御/建筑/农场）
     * @return 该类别的所有教程条目，或空列表
     */
    public static List<BuildingGuide> queryByCategory(String category) {
        ensureLoaded();
        List<BuildingGuide> result = new ArrayList<>();
        for (BuildingGuide g : GUIDES) {
            if (g.category().equals(category)) result.add(g);
        }
        return result;
    }

    /**
     * 把教程条目格式化成 LLM 可读的文本。
     *
     * @param guide 教程条目
     * @return 格式化文本（包含教程 ID、名称、类别、完整指导内容）
     */
    public static String format(BuildingGuide guide) {
        if (guide == null) return "未找到该建筑类型的教程。";
        StringBuilder sb = new StringBuilder();
        sb.append("【建筑教程：").append(guide.name()).append("】\n");
        sb.append("教程ID：").append(guide.id()).append("\n");
        sb.append("类别：").append(guide.category()).append("\n");
        sb.append("布局指导：\n").append(guide.guide()).append("\n");
        return sb.toString();
    }

    /**
     * 列出所有可查询的建筑教程（用于提示词，让 LLM 知道能查什么）。
     *
     * @return 教程列表文本，如"垂直交通(楼梯间布局)、矿洞(螺旋矿井/分支矿法)..."
     */
    public static String listGuides() {
        ensureLoaded();
        Map<String, List<String>> byCat = new HashMap<>();
        for (BuildingGuide g : GUIDES) {
            byCat.computeIfAbsent(g.category(), k -> new ArrayList<>()).add(g.name());
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : byCat.entrySet()) {
            if (sb.length() > 0) sb.append("、");
            sb.append(e.getKey()).append("(").append(String.join("/", e.getValue())).append(")");
        }
        return sb.toString();
    }

    /** 知识库是否已加载且有数据 */
    public static boolean isAvailable() {
        ensureLoaded();
        return !GUIDES.isEmpty();
    }

    /**
     * 【模板库】参数化楼梯模板：人工验证过的相对坐标表（含实体块、空气块、facing 全状态）。
     * 框架只做平移+旋转+换材质，AI 从"生成几何"降级成"选型+定位"，质量下限被锁死。
     */
    public record StairTemplate(String id, String name, int innerSize,
                                 String style, String description) {}

    /** 内置楼梯模板表（不经 JSON，直接硬编码保证可靠） */
    private static final java.util.Map<String, StairTemplate> TEMPLATES = new java.util.HashMap<>();
    static {
        TEMPLATES.put("stairwell_3x3_switchback", new StairTemplate(
                "stairwell_3x3_switchback", "3×3回转楼梯井", 3, "switchback",
                "两段直线楼梯+转角平台，适合3×3及以上内空，最像人建的楼梯"));
        TEMPLATES.put("stairwell_2x2_spiral", new StairTemplate(
                "stairwell_2x2_spiral", "2×2螺旋楼梯井", 2, "spiral",
                "紧凑螺旋，2×2 footprint，适合小户型，4级一圈升4格"));
        TEMPLATES.put("stairs_straight_wall", new StairTemplate(
                "stairs_straight_wall", "贴墙直线楼梯", 3, "straight",
                "单段直线贴墙楼梯，适合长走廊或靠墙上行"));
        TEMPLATES.put("ladder_shaft_1x1", new StairTemplate(
                "ladder_shaft_1x1", "1×1梯子竖井", 2, "ladder",
                "垂直梯子，1×1占地，沙包不会爬——仅作备用或玩家自爬"));
    }

    /** 【模板库】按 ID 查询楼梯模板 */
    public static StairTemplate queryTemplate(String templateId) {
        return TEMPLATES.get(templateId);
    }

    /** 【模板库】列出所有可用楼梯模板 */
    public static String listTemplates() {
        StringBuilder sb = new StringBuilder();
        for (StairTemplate t : TEMPLATES.values()) {
            if (sb.length() > 0) sb.append("、");
            sb.append(t.id()).append("(").append(t.name()).append(")");
        }
        return sb.toString();
    }
}
