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

package cn.shabaoai.companion;

import cn.shabaoai.companion.ai.*;
import cn.shabaoai.companion.build.*;
import cn.shabaoai.companion.config.ModConfig;
import cn.shabaoai.companion.entity.CompanionManager;
import cn.shabaoai.companion.entity.ModEntities;
import cn.shabaoai.companion.protect.GuardCore;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import cn.shabaoai.companion.net.AiPromptPayload;
import cn.shabaoai.companion.net.AiReplyPayload;
import cn.shabaoai.companion.net.BuildPreviewPayload;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * 模组主入口。
 *
 * <p>核心设计：
 * <ul>
 *   <li>用 {@link CompanionManager} 管理 {@link cn.shabaoai.companion.entity.CompanionEntity}，
 *       在世界中刷出可见的"玩家样貌"队友实体（用 PlayerEntityModel 渲染）</li>
 *   <li>聊天框直接对话：玩家有队友时，聊天内容自动转发给 AI，AI 以队友身份在聊天框回复</li>
 *   <li>Agent 确认机制：危险操作询问玩家确认（见 {@link ConfirmationManager}）</li>
 *   <li>TTS 朗读：AI 回复时同时发 {@link AiReplyPayload} 给客户端触发语音合成</li>
 * </ul>
 */
public final class ShabaoAiMod implements ModInitializer {
    public static final String MOD_ID = "shabao_ai";
    public static final BuildManager BUILDS = new BuildManager();
    public static final LlmClient LLM = new LlmClient();
    /** Agent 执行器：让 LLM 自主多步循环（scan→place→finish） */
    public static final AgentExecutor AGENT = new AgentExecutor(LLM);

    @Override
    public void onInitialize() {
        // 【防篡改·内测版】启动即做完整性校验 + 版权声明（校验失败则置标记，AI 工作被锁定）
        GuardCore.check();
        ModConfig.get();
        // 【D2 示例即测试】启动自检提示词完整示例能否通过框架校验（失败会在日志 ERROR 报警）
        AgentExecutor.verifyPromptExamples();
        // 延迟注册实体类型（避免静态初始化触发 Registry 类加载问题）
        ModEntities.register();

        // 注册网络通信 payload
        PayloadTypeRegistry.playC2S().register(AiPromptPayload.ID, AiPromptPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(AiReplyPayload.ID, AiReplyPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(BuildPreviewPayload.ID, BuildPreviewPayload.CODEC);
        // 语音输入：客户端发来文本，走 Agent 模式
        ServerPlayNetworking.registerGlobalReceiver(AiPromptPayload.ID,
                (payload, context) -> askAgent(context.player(), payload.text()));

        // 聊天框监听：不拦截消息（return true），消息发送后检查是否需要转发给 AI
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            String text = message.getContent().getString().trim();
            // 先检查是否是确认回复（确认/取消）
            if (ConfirmationManager.handleReply(sender, text)) return false;
            // 检查发送者是否有 AI 队友，且未在思考中
            if (!CompanionManager.exists(sender.getUuid())) return true;
            // 【统一 Brain 入口】聊天框与语音/命令一样走 askAgent：
            // GuardCore 预检 → AgentRuntime.onPlayerMessage →
            // 本地意图（0 LLM）或 BrainScheduler 串行排队——后台 SOCIAL_IDLE 等
            // 正在思考时，玩家聊天消息排队在最高优先级，绝不另起一个并发 Brain
            askAgent(sender, text);
            return true;
        });

        // 每 tick 让建造系统推进（队友跟随由实体自身 AI goal 处理，无需这里 tick）
        ServerTickEvents.END_SERVER_TICK.register(server -> BUILDS.tick(server));
        // 【v22·Runtime 化】每 Server tick 驱动常驻 Agent Runtime 一次（分层心跳：状态缓存/
        // 技能/goal/事件消费）。按 server 粒度而非逐世界遍历——所有世界访问由 Runtime
        // 内部以玩家自身维度（player.getServerWorld()）取得，杜绝跨维度错判/错放方块。
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            AgentRuntime.get().tick(server);
        });

        // 【M9】实体加载时重新注册队友映射：服务器重启/chunk 重载后，
        // CompanionEntity 从 NBT 恢复 ownerUuid，但不重新注册 COMPANIONS 的话，
        // exists() 返回 false，玩家聊天不会转发给 AI
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof cn.shabaoai.companion.entity.CompanionEntity companion) {
                CompanionManager.register(companion);
            }
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (entity instanceof cn.shabaoai.companion.entity.CompanionEntity companion) {
                CompanionManager.unregister(companion);
            }
        });

        // 服务器关闭时清理所有队友实体，避免残留
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            CompanionManager.clearMappings();
            AGENT.clearAllSessions();
            // 【M3】立即落盘待写的建筑记忆（延迟写盘任务可能还没执行）
            cn.shabaoai.companion.ai.BuildMemory.flushAll();
            // 【世界退出清理】清空 AgentRuntime 全部状态（goal/技能/脑调度/世界变化累积/缓存）：
            // 同 JVM 退出到主菜单再进新世界，旧世界状态（尤其 torch_place 常驻）绝不残留
            cn.shabaoai.companion.ai.AgentRuntime.get().clearAll();
        });

        // 【世界退出清理·玩家断开】下线玩家离开游戏：清该玩家的 goal/技能/脑调度/缓存，
        // 避免同一 UUID 重进新世界时带旧 Runtime 状态（尤其 mine_assist 的 torch_place）
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity p = handler.getPlayer();
            if (p != null) {
                AGENT.cleanup(p.getUuid());
                cn.shabaoai.companion.ai.AgentRuntime.get().cleanup(p.getUuid());
            }
        });

        // 【自动召唤】玩家进入服务器时自动生成 AI 伙伴，无需每次手动 /ai spawn。
        // 已存在的伙伴（NBT 恢复 + ENTITY_LOAD 重新注册）不重复生成；
        // 延迟到主线程执行，确保世界/玩家状态就绪后再生成实体。
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            server.execute(() -> {
                if (player.isRemoved() || CompanionManager.exists(player.getUuid())) return;
                String name = ModConfig.get().companionName;
                CompanionManager.getOrCreate(player, name);
                CompanionManager.sendMessage(player, "我来啦！直接在聊天框跟我说话就行。");
            });
        });

        // 命令系统（保留作为管理/备用入口，聊天是主要交互方式）
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(literal("ai")
                .then(literal("spawn").executes(c -> { spawn(c.getSource().getPlayerOrThrow()); return 1; }))
                .then(literal("ask").then(argument("message", StringArgumentType.greedyString()).executes(c -> { askAgent(c.getSource().getPlayerOrThrow(), StringArgumentType.getString(c, "message")); return 1; })))
                .then(literal("agent").then(argument("message", StringArgumentType.greedyString()).executes(c -> { askAgent(c.getSource().getPlayerOrThrow(), StringArgumentType.getString(c, "message")); return 1; })))
                .then(literal("build").then(argument("type", StringArgumentType.word()).executes(c -> { build(c.getSource().getPlayerOrThrow(), StringArgumentType.getString(c, "type"), "oak", 9); return 1; })))
                .then(literal("clear").executes(c -> { if (!BUILDS.clearLast(c.getSource().getPlayerOrThrow())) c.getSource().sendFeedback(() -> Text.literal("没有上一次建筑"), false); return 1; }))
                .then(literal("remove").executes(c -> {
                    CompanionManager.remove(c.getSource().getPlayerOrThrow().getUuid());
                    c.getSource().sendFeedback(() -> Text.literal("队友已移除"), false);
                    return 1;
                }))
                .then(literal("reload").requires(s -> s.hasPermissionLevel(2)).executes(c -> {
                    ModConfig.reload();
                    // 【L6】重载后刷新所有会话的 system 提示词，避免提示词引用旧配置
                    AGENT.refreshSystemPrompts(ModConfig.get());
                    c.getSource().sendFeedback(() -> Text.literal("沙包 AI 配置已重载"), false);
                    return 1;
                }))
                .then(literal("log").executes(c -> { showLog(c.getSource().getPlayerOrThrow()); return 1; })
                        .then(literal("clear").executes(c -> { AgentLogger.clearLogFile(); c.getSource().sendFeedback(() -> Text.literal("沙包 AI 日志已清空"), false); return 1; })))
                .then(literal("memory").executes(c -> { showMemory(c.getSource().getPlayerOrThrow()); return 1; })
                        .then(literal("clear").executes(c -> { cn.shabaoai.companion.ai.BuildMemory.clear(c.getSource().getPlayerOrThrow().getUuid()); c.getSource().sendFeedback(() -> Text.literal("建筑记忆已清空"), false); return 1; })))
                .then(literal("stream").executes(c -> { toggleStream(c.getSource()); return 1; }))
        ));
    }

    /** 显示最近的 API 请求日志（游戏内查看，排查"思考慢"问题） */
    private static void showLog(ServerPlayerEntity player) {
        var recent = AgentLogger.getRecent();
        if (recent.isEmpty()) {
            player.sendMessage(Text.literal("§7暂无日志。完整日志见 logs/shabao-ai-agent.log"));
            return;
        }
        player.sendMessage(Text.literal("§6===== 沙包 AI 最近日志（共" + recent.size() + "条）====="));
        for (var entry : recent) {
            String elapsed = entry.elapsed() != null ? " §a[" + entry.elapsed() + "]" : "";
            String line = String.format("§7[%s] §b步骤%d §f%s %s%s",
                    entry.time(), entry.step(), entry.type(), entry.content(), elapsed);
            player.sendMessage(Text.literal(line));
        }
        player.sendMessage(Text.literal("§7完整日志：logs/shabao-ai-agent.log"));
    }

    /** 显示建筑记忆（游戏内查看 AI 记得什么） */
    private static void showMemory(ServerPlayerEntity player) {
        BlockPos pos = player.getBlockPos();
        String summary = cn.shabaoai.companion.ai.BuildMemory.getSummary(player.getUuid(),
                pos.getX(), pos.getY(), pos.getZ());
        player.sendMessage(Text.literal("§6===== 沙包的建筑记忆 ====="));
        for (String line : summary.split("\n")) {
            player.sendMessage(Text.literal("§f" + line));
        }
        player.sendMessage(Text.literal("§7记忆文件：shabao-ai-memory/" + player.getUuid() + ".json"));
    }

    /** 切换流式输出开关：开启后 ActionBar 实时显示 LLM 生成进度 */
    private static void toggleStream(net.minecraft.server.command.ServerCommandSource source) {
        ModConfig config = ModConfig.get();
        config.streamOutput = !config.streamOutput;
        ModConfig.save(config);
        boolean on = config.streamOutput;
        source.sendFeedback(() -> Text.literal(
                on ? "§a流式输出已开启 §7（ActionBar 实时显示生成进度）"
                        : "§c流式输出已关闭"), false);
    }

    /**
     * 生成假玩家队友。
     * 已存在则传送到主人身边刷新位置；不存在则创建并加入世界。
     */
    private static void spawn(ServerPlayerEntity player) {
        String name = ModConfig.get().companionName;
        // getOrCreate 内部处理已存在/新建两种情况
        CompanionManager.getOrCreate(player, name);
        String greet = "我来啦！直接在聊天框跟我说话就行。";
        // 聊天框显示（带队友名前缀，像真人发言）
        CompanionManager.sendMessage(player, greet);
    }

    /**
     * Agent 模式：LLM 自主多步循环。
     * 聊天框直接对话和 /ai agent 命令都走这里。
     */
    public static void askAgent(ServerPlayerEntity player, String message) {
        // 【v22·分散防篡改】检查点 #1（玩家入口）：篡改锁定后连入口都拒，不进入 Agent 循环
        if (GuardCore.isTampered()) {
            player.sendMessage(Text.literal("§c本程序为 txcxgzs 开发的沙包AI授权内测版，检测到程序被非法修改，AI 功能已锁定。"));
            return;
        }
        // 【v22·Runtime 化】消息当事件进常驻 Runtime。
        // 并发控制已由 BrainScheduler 全权接管（每玩家串行 + 优先级 + 世代失效），
        // 旧的 THINKING 集合锁已删除——"AI 是否在思考"以 AgentRuntime.isBrainBusy 为准，
        // 玩家连发消息会按 FIFO 排队串行处理，不再有虚假的"正在思考"拒绝。
        AgentRuntime.get().onPlayerMessage(player, message);
    }

    /** 取异常链最底层的消息 */
    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage();
    }

    /** 执行单轮模式的动作（模板建筑等） */
    private static void execute(ServerPlayerEntity p, AiIntent i) {
        switch (i.action()) {
            case "build" -> build(p, i.structure(), i.material(), i.size());
            case "rebuild" -> { BUILDS.clearLast(p); build(p, i.structure(), i.material(), i.size()); }
            case "clear" -> BUILDS.clearLast(p);
        }
    }

    /** 模板建筑 */
    private static void build(ServerPlayerEntity p, String type, String material, int size) {
        BlockPos origin = p.getBlockPos().offset(p.getHorizontalFacing(), 6);
        BUILDS.start(p, StructureGenerator.generate(type, material, size, origin));
    }
}
