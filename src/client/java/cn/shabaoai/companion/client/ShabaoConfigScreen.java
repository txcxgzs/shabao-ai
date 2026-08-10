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

package cn.shabaoai.companion.client;

import cn.shabaoai.companion.config.ModConfig;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.Optional;

/**
 * 统一的配置屏构建器，使用 Cloth Config API 实现。
 * 玩家通过 ModMenu 的"配置"按钮进入本屏，渲染、布局、滚动由 Cloth Config 全权负责，
 * 因此不会出现按钮被输入框遮挡的布局问题。
 *
 * <p>按玩家任务分成四页：
 * <ol>
 *   <li>AI 角色：称呼与固定身份说明</li>
 *   <li>通用 LLM（兼容 OpenAI Chat Completions）</li>
 *   <li>语音 ASR / TTS（默认 MiMo 2.5，Base URL/模型可换任意兼容端点）</li>
 *   <li>游戏与建造行为</li>
 * </ol>
 */
public final class ShabaoConfigScreen {

    private ShabaoConfigScreen() {}

    /**
     * 构建配置屏，由 ModMenuApi 调用。
     * @param parent 调用方传入的父 Screen，关闭本屏时返回这里
     */
    public static Screen create(Screen parent) {
        ModConfig original = ModConfig.get();

        final String titleKey = "title.shabao_ai.config";
        final String roleKey = "category.shabao_ai.role";
        final String llmKey = "category.shabao_ai.llm";
        final String speechKey = "category.shabao_ai.speech";
        final String gameKey = "category.shabao_ai.game";

        // 用于在 setSavingRunnable 中暂存最新值的容器
        Holder holder = new Holder();
        holder.llmBase = original.llm.baseUrl();
        holder.llmModel = original.llm.model();
        holder.llmKey = "";
        holder.asrKey = "";
        holder.ttsKey = "";
        holder.asrBase = original.asr.baseUrl();
        holder.asrModel = original.asr.model();
        holder.ttsBase = original.tts.baseUrl();
        holder.ttsModel = original.tts.model();
        holder.asrLanguage = original.asr.language();
        holder.ttsVoice = original.tts.voice();
        holder.ttsInstruction = original.tts.instruction();
        holder.companionName = original.companionName;
        holder.builtInSkin = original.builtInSkin;
        holder.customSkinEnabled = original.customSkinEnabled;
        holder.companionSkinPath = original.companionSkinPath;
        holder.blocksPerTick = original.blocksPerTick;
        holder.maxBuildBlocks = original.maxBuildBlocks;
        holder.directChat = original.directChatMode;
        holder.allowSurvival = original.allowSurvivalBuilding;
        holder.affectionParticles = original.affectionParticles;
        holder.effort = EffortLevel.fromString(original.reasoningEffort);

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(text(titleKey, "沙包 AI 管理"));

        ConfigEntryBuilder eb = builder.entryBuilder();

        ConfigCategory role = builder.getOrCreateCategory(text(roleKey, "AI 角色"));
        buildRoleCategory(role, eb, holder);

        ConfigCategory llm = builder.getOrCreateCategory(text(llmKey, "通用 LLM"));
        buildLlmCategory(llm, eb, holder, original);

        ConfigCategory speech = builder.getOrCreateCategory(text(speechKey, "语音 (ASR/TTS)"));
        buildSpeechCategory(speech, eb, holder, original);

        ConfigCategory game = builder.getOrCreateCategory(text(gameKey, "游戏行为"));
        buildGameCategory(game, eb, holder);

        builder.setSavingRunnable(() -> persist(holder, original));

        return builder.build();
    }

    // ===== 四个分类的字段 =====

    private static void buildRoleCategory(ConfigCategory cat, ConfigEntryBuilder eb, Holder h) {
        cat.addEntry(eb.startTextDescription(text("desc.shabao_ai.roleIdentity",
                "你可以改变 AI 的称呼和对外角色名；它的核心身份始终是 shabao，由 txcxgzs(ban) 打造。"))
                .build());
        cat.addEntry(eb.startStrField(text("field.shabao_ai.companionName", "AI 角色名字"), h.companionName)
                .setDefaultValue(ModConfig.DEFAULT_COMPANION_NAME)
                .setTooltip(text("tooltip.shabao_ai.companionName",
                        "默认名称显示为“沙包”；自定义名称显示为“自定义名(shabao)”。新对话会使用这个称呼；已有对话下一轮会刷新身份提示。"))
                .setErrorSupplier(ShabaoConfigScreen::validateCompanionName)
                .setSaveConsumer(v -> h.companionName = v)
                .build());
        cat.addEntry(eb.startTextDescription(text("desc.shabao_ai.roleNameplate",
                "仅自定义名称附加 (shabao) 核心标识，便于多人游戏辨认。"))
                .build());
        cat.addEntry(eb.startTextDescription(text("section.shabao_ai.skin", "—— 角色皮肤 ——"))
                .build());
        cat.addEntry(eb.startDropdownMenu(text("field.shabao_ai.builtInSkin", "内置皮肤"),
                        h.builtInSkin, value -> value, ShabaoConfigScreen::skinDisplayName)
                .setSelections(ModConfig.BUILT_IN_SKINS)
                .setDefaultValue(ModConfig.DEFAULT_BUILT_IN_SKIN)
                .setTooltip(text("tooltip.shabao_ai.builtInSkin",
                        "四张皮肤已随 Mod 打包；保存后立即切换。皮肤 2 是默认皮肤。"))
                .setSaveConsumer(v -> h.builtInSkin = v)
                .build());
        cat.addEntry(eb.startBooleanToggle(text("field.shabao_ai.customSkinEnabled", "使用外部 PNG 覆盖"),
                        h.customSkinEnabled)
                .setDefaultValue(false)
                .setTooltip(text("tooltip.shabao_ai.customSkinEnabled",
                        "开启后读取下方本地 64×64 PNG；关闭或外部文件加载失败时使用上方选中的内置皮肤。"))
                .setSaveConsumer(v -> h.customSkinEnabled = v)
                .build());
        cat.addEntry(eb.startStrField(text("field.shabao_ai.companionSkinPath", "皮肤 PNG 路径"),
                        h.companionSkinPath)
                .setDefaultValue("shabao-ai-skin.png")
                .setTooltip(text("tooltip.shabao_ai.companionSkinPath",
                        "相对路径从 .minecraft/config 读取，例如 shabao-ai-skin.png；也支持绝对路径。替换同名文件后最多 1 秒热重载。"))
                .setErrorSupplier(ShabaoConfigScreen::validateSkinPath)
                .setSaveConsumer(v -> h.companionSkinPath = v)
                .build());
    }

    private static void buildLlmCategory(ConfigCategory cat, ConfigEntryBuilder eb, Holder h, ModConfig original) {
        cat.addEntry(eb.startStrField(text("field.shabao_ai.llmBase", "兼容 OpenAI 的 Base URL"), h.llmBase)
                .setDefaultValue(original.llm.baseUrl())
                .setTooltip(text("tooltip.shabao_ai.llmBase", "形如 https://api.openai.com/v1。修改后立即生效。"))
                .setSaveConsumer(v -> h.llmBase = v)
                .build());

        cat.addEntry(eb.startStrField(text("field.shabao_ai.llmModel", "模型名称"), h.llmModel)
                .setDefaultValue(original.llm.model())
                .setTooltip(text("tooltip.shabao_ai.llmModel", "例如 gpt-4o-mini、deepseek-chat 等。"))
                .setSaveConsumer(v -> h.llmModel = v)
                .build());

        // 密钥输入：留空=保持原值
        cat.addEntry(eb.startStrField(text("field.shabao_ai.llmKey", "API Key"), "")
                .setDefaultValue("")
                .setTooltip(text("tooltip.shabao_ai.llmKey", "留空则保持已保存的密钥；输入新值会覆盖。"))
                .setSaveConsumer(v -> h.llmKey = v)
                .build());

        // 【思考档位】以提示词纪律生效：low 最多一句，medium 最多两句，max 要求完整推演，
        // 不是请求参数（端点不接受 reasoning_effort，见 LlmClient 注释）。
        cat.addEntry(eb.startEnumSelector(text("field.shabao_ai.reasoningEffort", "AI 思考强度"),
                        EffortLevel.class, h.effort)
                .setDefaultValue(EffortLevel.HIGH)
                .setEnumNameProvider(v -> Text.literal(v.toString().toLowerCase()))
                .setTooltip(text("tooltip.shabao_ai.reasoningEffort",
                        "控制沙包思考的篇幅与深度：low=最多一句极短判断 / medium=最多两句短判断 / "
                                + "high=默认 / max=对每步坐标材质支撑做完整推演。"
                                + "对新建会话立即生效；已进行中的会话用 /ai reload 刷新提示词后对后续轮次生效。"))
                .setSaveConsumer(v -> h.effort = v)
                .build());
    }

    private static void buildSpeechCategory(ConfigCategory cat, ConfigEntryBuilder eb, Holder h, ModConfig original) {
        // 顶部说明：默认 MiMo，可换成任意兼容 chat/completions 的语音端点
        cat.addEntry(eb.startTextDescription(
                text("desc.shabao_ai.speechAddress",
                        "默认使用 MiMo（https://api.xiaomimimo.com/v1，模型 mimo-v2.5-asr / mimo-v2.5-tts）；"
                                + "也可填任意兼容 chat/completions 的语音端点——ASR 返回 choices[0].message.content，"
                                + "TTS 返回 choices[0].message.audio.data 的 base64 WAV。"))
                .build());

        // === ASR 段 ===
        cat.addEntry(eb.startTextDescription(text("section.shabao_ai.asr", "—— 语音识别 (ASR) ——")).build());
        cat.addEntry(eb.startStrField(text("field.shabao_ai.asrBase", "ASR Base URL"), h.asrBase)
                .setDefaultValue(original.asr.baseUrl())
                .setTooltip(text("tooltip.shabao_ai.asrBase",
                        "默认 https://api.xiaomimimo.com/v1；换端点时需保证其 chat/completions 返回 choices[0].message.content。"))
                .setSaveConsumer(v -> h.asrBase = v)
                .build());
        cat.addEntry(eb.startStrField(text("field.shabao_ai.asrModel", "ASR 模型"), h.asrModel)
                .setDefaultValue(original.asr.model())
                .setTooltip(text("tooltip.shabao_ai.asrModel", "默认 mimo-v2.5-asr；换端点时填对应模型名。"))
                .setSaveConsumer(v -> h.asrModel = v)
                .build());
        cat.addEntry(eb.startStrField(text("field.shabao_ai.asrKey", "ASR API Key"), "")
                .setDefaultValue("")
                .setTooltip(text("tooltip.shabao_ai.asrKey", "留空则保持已保存的密钥。"))
                .setSaveConsumer(v -> h.asrKey = v)
                .build());
        cat.addEntry(eb.startStrField(text("field.shabao_ai.asrLanguage", "ASR 语言，例如 zh"), h.asrLanguage)
                .setDefaultValue(original.asr.language())
                .setTooltip(text("tooltip.shabao_ai.asrLanguage", "符合 BCP-47 规范，例如 zh、en-US。"))
                .setSaveConsumer(v -> h.asrLanguage = v)
                .build());

        // === TTS 段 ===
        cat.addEntry(eb.startTextDescription(text("section.shabao_ai.tts", "—— 语音合成 (TTS) ——")).build());
        cat.addEntry(eb.startStrField(text("field.shabao_ai.ttsBase", "TTS Base URL"), h.ttsBase)
                .setDefaultValue(original.tts.baseUrl())
                .setTooltip(text("tooltip.shabao_ai.ttsBase",
                        "默认 https://api.xiaomimimo.com/v1；换端点时需保证其 chat/completions 返回 choices[0].message.audio.data 的 base64 WAV。"))
                .setSaveConsumer(v -> h.ttsBase = v)
                .build());
        cat.addEntry(eb.startStrField(text("field.shabao_ai.ttsModel", "TTS 模型"), h.ttsModel)
                .setDefaultValue(original.tts.model())
                .setTooltip(text("tooltip.shabao_ai.ttsModel", "默认 mimo-v2.5-tts；换端点时填对应模型名。"))
                .setSaveConsumer(v -> h.ttsModel = v)
                .build());
        cat.addEntry(eb.startStrField(text("field.shabao_ai.ttsKey", "TTS API Key"), "")
                .setDefaultValue("")
                .setTooltip(text("tooltip.shabao_ai.ttsKey", "留空则保持已保存的密钥。"))
                .setSaveConsumer(v -> h.ttsKey = v)
                .build());
        cat.addEntry(eb.startStrField(text("field.shabao_ai.ttsVoice", "TTS 音色"), h.ttsVoice)
                .setDefaultValue(original.tts.voice())
                .setSaveConsumer(v -> h.ttsVoice = v)
                .build());
        cat.addEntry(eb.startStrField(text("field.shabao_ai.ttsInstruction", "TTS 语气提示词"), h.ttsInstruction)
                .setDefaultValue(original.tts.instruction())
                .setTooltip(text("tooltip.shabao_ai.ttsInstruction", "用于控制合成时的语气、风格。"))
                .setSaveConsumer(v -> h.ttsInstruction = v)
                .build());
    }

    private static void buildGameCategory(ConfigCategory cat, ConfigEntryBuilder eb, Holder h) {
        cat.addEntry(eb.startIntField(text("field.shabao_ai.blocksPerTick", "每 Tick 建造方块数"), h.blocksPerTick)
                .setDefaultValue(12)
                .setMin(1).setMax(1000)
                .setTooltip(text("tooltip.shabao_ai.blocksPerTick", "数值越大沙包盖房子越快，但也越卡。"))
                .setSaveConsumer(v -> h.blocksPerTick = v)
                .build());

        cat.addEntry(eb.startIntField(text("field.shabao_ai.maxBuildBlocks", "单次最大方块数"), h.maxBuildBlocks)
                .setDefaultValue(12000)
                .setMin(1).setMax(1_000_000)
                .setTooltip(text("tooltip.shabao_ai.maxBuildBlocks", "防止一次性 OOM 的安全上限。"))
                .setSaveConsumer(v -> h.maxBuildBlocks = v)
                .build());

        cat.addEntry(eb.startBooleanToggle(text("field.shabao_ai.directChat", "直接输入聊天"), h.directChat)
                .setDefaultValue(true)
                .setTooltip(text("tooltip.shabao_ai.directChat", "开启时，沙包直接以玩家身份发送消息。"))
                .setSaveConsumer(v -> h.directChat = v)
                .build());

        cat.addEntry(eb.startBooleanToggle(text("field.shabao_ai.allowSurvival", "生存模式允许建造"), h.allowSurvival)
                .setDefaultValue(false)
                .setTooltip(text("tooltip.shabao_ai.allowSurvival", "默认仅创造模式可建造；开启后允许在生存中改地形。"))
                .setSaveConsumer(v -> h.allowSurvival = v)
                .build());

        cat.addEntry(eb.startBooleanToggle(text("field.shabao_ai.affectionParticles", "亲密互动爱心粒子"), h.affectionParticles)
                .setDefaultValue(true)
                .setTooltip(text("tooltip.shabao_ai.affectionParticles", "关闭后仍保留牵手、拥抱和亲吻动作，只隐藏少量爱心粒子。"))
                .setSaveConsumer(v -> h.affectionParticles = v)
                .build());
    }

    // ===== 保存 =====

    private static void persist(Holder h, ModConfig original) {
        try {
            ModConfig config = new ModConfig();
            config.llm = new ModConfig.Provider(
                    required(h.llmBase, "LLM Base URL"),
                    keyOrExisting(h.llmKey, original.llm.apiKey()),
                    required(h.llmModel, "模型名称"));
            config.asr = new ModConfig.Speech(
                    required(h.asrBase, "ASR Base URL"),
                    keyOrExisting(h.asrKey, original.asr.apiKey()),
                    required(h.asrModel, "ASR 模型"),
                    "",
                    required(h.asrLanguage, "ASR 语言"),
                    "");
            config.tts = new ModConfig.Speech(
                    required(h.ttsBase, "TTS Base URL"),
                    keyOrExisting(h.ttsKey, original.tts.apiKey()),
                    required(h.ttsModel, "TTS 模型"),
                    required(h.ttsVoice, "TTS 音色"),
                    "",
                    h.ttsInstruction);
            config.companionName = ModConfig.normalizeCompanionName(h.companionName);
            config.builtInSkin = ModConfig.BUILT_IN_SKINS.contains(h.builtInSkin)
                    ? h.builtInSkin : ModConfig.DEFAULT_BUILT_IN_SKIN;
            config.customSkinEnabled = h.customSkinEnabled;
            config.companionSkinPath = required(h.companionSkinPath, "皮肤 PNG 路径");
            config.blocksPerTick = positive(h.blocksPerTick, "每 Tick 建造方块数");
            config.maxBuildBlocks = positive(h.maxBuildBlocks, "单次最大方块数");
            config.directChatMode = h.directChat;
            config.allowSurvivalBuilding = h.allowSurvival;
            config.affectionParticles = h.affectionParticles;
            // 【修W】思考强度在菜单里有选项了，保存时写入（之前不保留会被重置回 high）
            config.reasoningEffort = h.effort.name().toLowerCase();
            // Agent 相关配置界面未暴露，保存时保留原值避免被重置为默认
            config.agentMaxSteps = original.agentMaxSteps;
            config.agentScanRadius = original.agentScanRadius;
            config.thinkingMode = original.thinkingMode;
            config.streamOutput = original.streamOutput;
            config.useToolCalling = original.useToolCalling;
            config.confirmOverwriteOnly = original.confirmOverwriteOnly;
            // 保留配置版本号，避免保存后触发不必要的自动修复
            config.configVersion = original.configVersion;
            ModConfig.save(config);
        } catch (RuntimeException ignored) {
            // 兜底防写入失败崩客户端。
            // 用单 catch（原 multi-catch 的合成异常类型在 ProGuard 混淆后会产生
            // 非法 StackMapTable，导致客户端启动 VerifyError，故此处统一收 RuntimeException）
        }
    }

    // ===== 工具 =====

    private static Text text(String key, String fallback) {
        // 翻译键缺失时直接用 fallback，避免空白分类名
        Text t = Text.translatable(key);
        return t.getString().isEmpty() || t.getString().equals(key) ? Text.literal(fallback) : t;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "不能为空");
        return value;
    }

    private static int positive(int value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + "必须是正整数");
        return value;
    }

    private static Optional<Text> validateCompanionName(String value) {
        try {
            ModConfig.normalizeCompanionName(value);
            return Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.of(Text.literal(e.getMessage()));
        }
    }

    private static Optional<Text> validateSkinPath(String value) {
        if (value == null || value.isBlank()) {
            return Optional.of(Text.literal("皮肤 PNG 路径不能为空"));
        }
        if (!value.trim().toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
            return Optional.of(Text.literal("皮肤文件必须是 .png"));
        }
        return Optional.empty();
    }

    private static Text skinDisplayName(String fileName) {
        int index = ModConfig.BUILT_IN_SKINS.indexOf(fileName);
        if (index < 0) return Text.literal(fileName == null ? "未知皮肤" : fileName);
        return Text.literal(index == 1 ? "皮肤 2（默认）" : "皮肤 " + (index + 1));
    }

    private static String keyOrExisting(String input, String existing) {
        return input == null || input.isBlank() ? existing : input;
    }

    /** 用于在 SaveConsumer 链式调用里暂存当前编辑值的简单 holder。 */
    private static final class Holder {
        String llmBase;
        String llmModel;
        String llmKey;
        String asrBase;
        String asrModel;
        String asrKey;
        String ttsBase;
        String ttsModel;
        String ttsKey;
        String asrLanguage;
        String ttsVoice;
        String ttsInstruction;
        String companionName;
        String builtInSkin;
        boolean customSkinEnabled;
        String companionSkinPath;
        int blocksPerTick;
        int maxBuildBlocks;
        boolean directChat;
        boolean allowSurvival;
        boolean affectionParticles;
        EffortLevel effort;
    }

    /** 【修W】AI 思考强度档位，与 ModConfig.reasoningEffort 字符串（low/medium/high/max）一一对应。 */
    private enum EffortLevel {
        LOW, MEDIUM, HIGH, MAX;

        /** 字符串（小写）转枚举；未知/空回退 high */
        static EffortLevel fromString(String s) {
            if (s == null) return HIGH;
            return switch (s.trim().toLowerCase()) {
                case "low" -> LOW;
                case "medium" -> MEDIUM;
                case "max" -> MAX;
                default -> HIGH;
            };
        }
    }
}
