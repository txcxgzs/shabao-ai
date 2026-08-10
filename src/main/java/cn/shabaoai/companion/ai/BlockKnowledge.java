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
 * 方块百科知识库：从 block_knowledge.json 加载建筑相关方块的百科内容。
 *
 * <p><b>数据来源</b>：Python 爬虫从中文 Minecraft Wiki 爬取，包含：
 * <ul>
 *   <li>用途说明（放置规则、红石行为、注意事项等，每个方块数百到数千字）</li>
 *   <li>方块状态属性（属性名、默认值、接受值、描述）</li>
 *   <li>获取方式摘要（合成、自然生成）</li>
 * </ul>
 *
 * <p><b>查询方式</b>：Agent 通过 query_block 动作查询方块知识，
 * 传入方块 ID（如 oak_door），返回该方块的百科内容供 LLM 决策参考。
 *
 * <p><b>加载策略</b>：
 * <ol>
 *   <li>优先从 mod 内置资源 /block_knowledge.json 加载（打包在 jar 里）</li>
 *   <li>如果游戏目录 shabao-ai-memory/block_knowledge.json 存在，覆盖加载（方便更新）</li>
 *   <li>建立方块 ID → 知识条目的映射，查询是 O(1)</li>
 * </ol>
 */
public final class BlockKnowledge {
    /** 方块 ID → 知识条目的映射（一个方块 ID 对应一个知识条目） */
    private static final Map<String, BlockEntry> BY_ID = new ConcurrentHashMap<>();
    /** 所有知识条目列表（按类别组织） */
    private static final List<BlockEntry> ENTRIES = new ArrayList<>();
    /** 是否已加载 */
    private static volatile boolean loaded = false;

    /** 单个方块的百科知识条目 */
    public record BlockEntry(List<String> ids, String name, String category,
                              String usage, String obtaining,
                              List<BlockProperty> properties) {}

    /** 方块状态属性 */
    public record BlockProperty(String name, String defaultValue,
                                 List<String> values, String desc) {}

    /** 确保知识库已加载（懒加载，首次查询时触发） */
    private static void ensureLoaded() {
        if (loaded) return;
        synchronized (BlockKnowledge.class) {
            if (loaded) return;
            try {
                loadInternal();
            } catch (Exception e) {
                // 【M11】JSON 损坏抛的是 JsonParseException（RuntimeException），
                // 不 catch 会导致每次 query 都重新加载并抛异常；降级为空知识库并置位
                AgentLogger.logError(0, "方块知识库：加载失败（JSON 损坏?） - " + e.getMessage());
            } finally {
                loaded = true;
            }
        }
    }

    /** 内部加载逻辑：先从 jar 资源加载，再用外部文件覆盖 */
    private static void loadInternal() {
        // 1. 从 mod 内置资源加载
        try (InputStream in = BlockKnowledge.class.getResourceAsStream("/block_knowledge.json")) {
            if (in != null) {
                try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    loadFromJson(JsonParser.parseReader(reader).getAsJsonObject());
                    AgentLogger.logInfo("方块知识库：从内置资源加载 " + ENTRIES.size() + " 个类别");
                }
            }
        } catch (IOException e) {
            AgentLogger.logError(0, "方块知识库：加载内置资源失败 - " + e.getMessage());
        }

        // 2. 外部文件覆盖（方便不重新打包就更新知识库）
        Path external = FabricLoader.getInstance().getGameDir()
                .resolve("shabao-ai-memory").resolve("block_knowledge.json");
        if (Files.exists(external)) {
            try {
                String json = Files.readString(external, StandardCharsets.UTF_8);
                loadFromJson(JsonParser.parseString(json).getAsJsonObject());
                AgentLogger.logInfo("方块知识库：外部文件覆盖加载完成");
            } catch (IOException e) {
                AgentLogger.logError(0, "方块知识库：加载外部文件失败 - " + e.getMessage());
            }
        }
    }

    /** 从 JSON 对象加载知识库到内存映射 */
    private static void loadFromJson(JsonObject root) {
        JsonArray blocks = root.getAsJsonArray("blocks");
        if (blocks == null) return;
        for (JsonElement e : blocks) {
            JsonObject b = e.getAsJsonObject();
            List<String> ids = parseStringList(b.getAsJsonArray("ids"));
            String name = b.get("name").getAsString();
            String category = b.get("category").getAsString();
            String usage = b.has("usage") ? b.get("usage").getAsString() : "";
            String obtaining = b.has("obtaining") ? b.get("obtaining").getAsString() : "";
            List<BlockProperty> props = new ArrayList<>();
            if (b.has("properties")) {
                for (JsonElement pe : b.getAsJsonArray("properties")) {
                    JsonObject po = pe.getAsJsonObject();
                    props.add(new BlockProperty(
                            po.get("name").getAsString(),
                            po.has("default") ? po.get("default").getAsString() : "",
                            parseStringList(po.getAsJsonArray("values")),
                            po.has("desc") ? po.get("desc").getAsString() : ""
                    ));
                }
            }
            BlockEntry entry = new BlockEntry(ids, name, category, usage, obtaining, props);
            ENTRIES.add(entry);
            for (String id : ids) {
                BY_ID.put(id, entry);
            }
        }
    }

    private static List<String> parseStringList(JsonArray arr) {
        List<String> list = new ArrayList<>();
        if (arr == null) return list;
        for (JsonElement e : arr) list.add(e.getAsString());
        return list;
    }

    /**
     * 按方块 ID 查询百科知识。
     *
     * @param blockId 方块 ID（如 "oak_door"、"iron_door"）
     * @return 知识条目，或 null（未找到）
     */
    public static BlockEntry query(String blockId) {
        ensureLoaded();
        return BY_ID.get(blockId);
    }

    /**
     * 按类别查询所有方块（如查所有"门"类方块）。
     *
     * @param category 类别名（门/楼梯/红石/光源/窗户/护栏/装饰/家具/台阶/梯子）
     * @return 该类别的所有知识条目，或空列表
     */
    public static List<BlockEntry> queryByCategory(String category) {
        ensureLoaded();
        List<BlockEntry> result = new ArrayList<>();
        for (BlockEntry e : ENTRIES) {
            if (e.category().equals(category)) result.add(e);
        }
        return result;
    }

    /**
     * 把知识条目格式式化成 LLM 可读的文本（用于注入 Agent 上下文）。
     *
     * <p>格式：
     * <pre>
     * 【方块百科：橡木门】
     * 类别：门
     * 用途：玩家可以开关木门...
     * 方块状态：
     *   facing（朝向）：默认north，可选 north/south/east/west
     *   half（半边）：默认lower，可选 upper/lower
     * </pre>
     *
     * @param entry 知识条目
     * @return 格式化文本
     */
    public static String format(BlockEntry entry) {
        if (entry == null) return "未找到该方块的百科知识。";
        StringBuilder sb = new StringBuilder();
        sb.append("【方块百科：").append(entry.name()).append("】\n");
        sb.append("类别：").append(entry.category()).append("\n");
        sb.append("方块ID：").append(String.join(", ", entry.ids())).append("\n");
        if (!entry.usage().isEmpty()) {
            sb.append("用途：").append(entry.usage()).append("\n");
        }
        if (!entry.properties().isEmpty()) {
            sb.append("方块状态属性：\n");
            for (BlockProperty p : entry.properties()) {
                sb.append("  ").append(p.name());
                if (!p.desc().isEmpty()) sb.append("（").append(p.desc()).append("）");
                sb.append("：默认").append(p.defaultValue());
                if (!p.values().isEmpty()) {
                    sb.append("，可选 ").append(String.join("/", p.values()));
                }
                sb.append("\n");
            }
        }
        if (!entry.obtaining().isEmpty()) {
            sb.append("获取：").append(entry.obtaining()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 列出所有可查询的方块类别（用于提示词，让 LLM 知道能查什么）。
     *
     * @return 类别列表文本，如"门(木门/铁门/活板门/栅栏门)、楼梯(楼梯/台阶/梯子)..."
     */
    public static String listCategories() {
        ensureLoaded();
        Map<String, List<String>> byCat = new HashMap<>();
        for (BlockEntry e : ENTRIES) {
            byCat.computeIfAbsent(e.category(), k -> new ArrayList<>()).add(e.name());
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
        return !ENTRIES.isEmpty();
    }
}
