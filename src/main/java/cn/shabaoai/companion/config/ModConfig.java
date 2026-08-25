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

package cn.shabaoai.companion.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("shabao-ai.json");
    private static ModConfig instance;

    /**
     * 配置版本号：用于自动修复旧配置。
     * 升级版本号后，旧配置加载时会把有问题的字段重置为默认值并写回。
     * 当前 v8：新增 JAR 内置皮肤库选择；v7：新增本地自定义角色皮肤；v6：规范化可自定义的 AI 角色名。
     * v3：useToolCalling 原生 tool calling；v2：修复 thinkingMode=true 导致 DeepSeek-V4-Flash 不输出 content。
     */
    private static final int CURRENT_CONFIG_VERSION = 8;
    public static final String DEFAULT_COMPANION_NAME = "沙包";
    public static final String CORE_IDENTITY = "shabao";
    public static final String CREATOR_IDENTITY = "txcxgzs(ban)";
    public static final int MAX_COMPANION_NAME_LENGTH = 32;
    public static final String DEFAULT_BUILT_IN_SKIN = "lumen-skin-202608090622-429625e8.png";
    public static final java.util.List<String> BUILT_IN_SKINS = java.util.List.of(
            "lumen-skin-202608090619-002b1501.png",
            DEFAULT_BUILT_IN_SKIN,
            "lumen-skin-202608090635-f612efac.png",
            "lumen-skin-202608090636-77a41ad3.png");

    public Provider llm = new Provider("https://your-provider.example/v1", "", "your-model");
    public Speech asr = new Speech("https://api.xiaomimimo.com/v1", "", "mimo-v2.5-asr", "", "zh", "", "api-key");
    public Speech tts = new Speech("https://api.xiaomimimo.com/v1", "", "mimo-v2.5-tts", "mimo_default", "", "用自然、活泼、像游戏伙伴一样的中文语气说话。自信但不要夸张。", "api-key");
    public String companionName = DEFAULT_COMPANION_NAME;
    /** JAR 内置皮肤文件名；默认使用用户指定的 lumen 0622 版本。 */
    public String builtInSkin = DEFAULT_BUILT_IN_SKIN;
    /** 开启时用外部本地 PNG 覆盖内置皮肤。 */
    public boolean customSkinEnabled = false;
    /** 相对路径以 .minecraft/config 为基准，也允许绝对路径。 */
    public String companionSkinPath = "shabao-ai-skin.png";
    public int blocksPerTick = 12;
    public int maxBuildBlocks = 12000;
    public boolean allowSurvivalBuilding = false;
    public boolean directChatMode = true;
    // Agent 相关配置：控制 LLM 自主循环的边界
    public int agentMaxSteps = 200;     // 单次任务最大循环步数（200步可支持大型建筑）
    public int agentScanRadius = 32;    // scan 最大扫描半径（32格支持大范围扫描）
    public boolean confirmOverwriteOnly = true; // true=只在地形覆盖/破坏现有方块时确认；false=所有危险动作都确认
    public boolean thinkingMode = false;  // DeepSeek V4 深度思考模式：Flash 模型思考完不输出 content，默认关闭
    /** 流式输出开关：开启后 ActionBar 实时显示 LLM 生成进度（字符数+耗时+内容预览）。默认开启。 */
    public boolean streamOutput = true;
    /** 原生 tool calling 开关：开启后用 tools 数组代替散文 JSON 解析（需要端点支持）。不支持时自动降级到 JSON 解析。 */
    public boolean useToolCalling = true;
    /** 亲密互动完成时显示少量爱心粒子；姿态与移动不受此开关影响。 */
    public boolean affectionParticles = true;
    /**
     * 思考努力程度：控制 AI 思考强度（提示词级，deepseek-v4-flash 端点不接受
     * reasoning_effort 参数，改为按本值往 system prompt 注入【思考纪律】段落）。
     * 可选：low / medium / high / max。
     *  - low：绝对限制为一句极短判断，响应最快，复杂任务也只决定眼前一步
     *  - medium：最多两句短判断，保留当前判断与下一步动作
     *  - high：默认，不注入约束，保持充分思考（复杂建造规划）
     *  - max：深度检查目标、材质、朝向、支撑、洞口与步骤依赖，最慢
     */
    public String reasoningEffort = "high";
    /** 配置版本号（自动管理，用户无需修改）。低于 CURRENT_CONFIG_VERSION 时触发自动修复 */
    public int configVersion = 0;

    public static synchronized ModConfig get() {
        if (instance == null) instance = load();
        return instance;
    }

    public static synchronized void reload() { instance = load(); }

    /**
     * 【修T′-1】从磁盘重新加载配置（不更新内存缓存 instance）。
     * 会话构建 system 提示词用：UI 改配置写磁盘后，即使玩家没打 /ai reload，
     * 新会话也拿到磁盘最新值——消灭「磁盘配置 vs 会话旧实例」双实例翻转。
     * 读取失败回退当前内存缓存（get()），不因配置损坏崩掉会话。
     */
    public static ModConfig loadFromDisk() {
        try {
            return load();
        } catch (Exception e) {
            return get();
        }
    }

    /** Saves a complete replacement configuration and makes it active immediately. */
    public static synchronized void save(ModConfig config) {
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(config), StandardCharsets.UTF_8);
            instance = config;
        } catch (Exception e) {
            // 注意：用单 catch 而非 multi-catch（IOException | RuntimeException）——
            // multi-catch 的合成异常类型在 ProGuard 混淆后会产生非法 StackMapTable，
            // 导致客户端启动 VerifyError。Exception 在此处语义等价且混淆安全。
            throw new IllegalStateException("Cannot save " + PATH, e);
        }
    }

    private static ModConfig load() {
        try {
            if (Files.exists(PATH)) {
                ModConfig config = GSON.fromJson(Files.readString(PATH), ModConfig.class);
                if (config == null) config = new ModConfig();
                // 自动补全缺失字段：把当前配置重新序列化写回，缺失字段会用默认值补上
                // 这样旧配置升级时不用删文件重新填
                config.ensureDefaults();
                Files.writeString(PATH, GSON.toJson(config), StandardCharsets.UTF_8);
                return config;
            }
            ModConfig config = new ModConfig();
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(config), StandardCharsets.UTF_8);
            return config;
        } catch (Exception e) {
            // 同上：单 catch 规避 ProGuard multi-catch 的 VerifyError
            throw new IllegalStateException("Cannot load " + PATH, e);
        }
    }

    /**
     * 确保所有字段都有合理默认值，并按版本号自动修复旧配置的有问题字段。
     *
     * <p>自动修复策略（按版本号升级）：
     * <ul>
     *   <li>v2：thinkingMode=true 会导致 DeepSeek-V4-Flash 思考完不输出 content，
     *       旧配置（configVersion < 2）强制重置为 false</li>
     *   <li>未来版本升级时，在这里加新的修复逻辑</li>
     * </ul>
     *
     * <p>用户在 v2 之后手动改 thinkingMode=true 不会被覆盖（configVersion 已是 2）。
     */
    private void ensureDefaults() {
        if (llm == null) llm = new Provider("https://your-provider.example/v1", "", "your-model");
        if (asr == null) asr = new Speech("https://api.xiaomimimo.com/v1", "", "mimo-v2.5-asr", "", "zh", "", "api-key");
        if (tts == null) tts = new Speech("https://api.xiaomimimo.com/v1", "", "mimo-v2.5-tts", "mimo_default", "", "用自然、活泼、像游戏伙伴一样的中文语气说话。", "api-key");
        companionName = normalizeCompanionNameOrDefault(companionName);
        if (builtInSkin == null || !BUILT_IN_SKINS.contains(builtInSkin)) {
            builtInSkin = DEFAULT_BUILT_IN_SKIN;
        }
        if (companionSkinPath == null || companionSkinPath.isBlank()) {
            companionSkinPath = "shabao-ai-skin.png";
        } else {
            companionSkinPath = companionSkinPath.trim();
        }
        if (agentMaxSteps <= 0) agentMaxSteps = 200;
        if (agentScanRadius <= 0) agentScanRadius = 32;

        // 版本号自动修复：旧配置（configVersion < 2）强制重置 thinkingMode
        if (configVersion < 2) {
            thinkingMode = false;
        }
        // v3：原生 tool calling 默认开启（旧配置缺失该字段，GSON 反序列化为 false）
        if (configVersion < 3) {
            useToolCalling = true;
        }
        // v4：思考努力程度默认 high；非法/缺失值回退 high
        if (reasoningEffort == null || reasoningEffort.isBlank()) {
            reasoningEffort = "high";
        }
        String eff = reasoningEffort.trim().toLowerCase();
        if (!eff.equals("low") && !eff.equals("medium") && !eff.equals("high")
                && !eff.equals("max")) {
            reasoningEffort = "high";
        } else {
            reasoningEffort = eff;
        }
        // v5：旧配置缺少 primitive boolean 时 Gson 会给 false；升级时恢复产品默认 true。
        if (configVersion < 5) affectionParticles = true;
        // 升级到当前版本号，后续手动修改不会被覆盖
        configVersion = CURRENT_CONFIG_VERSION;
    }

    /** 校验并规范化玩家设置的角色称呼；返回值可安全用于名牌和提示词的数据字段。 */
    public static String normalizeCompanionName(String value) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("AI 角色名字不能为空");
        if (name.codePointCount(0, name.length()) > MAX_COMPANION_NAME_LENGTH) {
            throw new IllegalArgumentException("AI 角色名字最多 " + MAX_COMPANION_NAME_LENGTH + " 个字符");
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                throw new IllegalArgumentException("AI 角色名字不能包含控制字符");
            }
        }
        return name;
    }

    /** 默认名直接显示“沙包”；自定义名附加 shabao 核心标识，便于多人游戏辨认。 */
    public static String companionEntityName(String configuredName) {
        String displayName = normalizeCompanionNameOrDefault(configuredName);
        return DEFAULT_COMPANION_NAME.equals(displayName)
                ? displayName
                : displayName + "(" + CORE_IDENTITY + ")";
    }

    private static String normalizeCompanionNameOrDefault(String value) {
        try {
            return normalizeCompanionName(value);
        } catch (IllegalArgumentException ignored) {
            return DEFAULT_COMPANION_NAME;
        }
    }

    public record Provider(String baseUrl, String apiKey, String model) {}
    public record Speech(String baseUrl, String apiKey, String model, String voice, String language, String instruction, String authType) {}
}
