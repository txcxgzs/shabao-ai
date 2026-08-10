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

import cn.shabaoai.companion.entity.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端初始化。
 *
 * <p>注册 {@link CompanionRenderer}：让 AI 队友实体用玩家模型渲染，
 * 这样在游戏里看起来就是一个真实的玩家。
 *
 * <p>保留 V 键语音输入功能和 TTS 播放。
 */
public final class ShabaoAiClient implements ClientModInitializer {
    private final VoiceController voice = new VoiceController();
    private boolean held;
    private boolean suppressUntilRelease;

    @Override
    public void onInitializeClient() {
        // 注册队友实体渲染器：用 PlayerEntityModel 渲染，看起来像真实玩家
        EntityRendererRegistry.register(ModEntities.COMPANION, CompanionRenderer::new);
        // 注册建筑绿色预览渲染器
        BuildPreviewRenderer.register();

        // V 键按住语音对话，松开发送
        KeyBinding key = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.shabao_ai.voice", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, "category.shabao_ai"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean now = key.isPressed();
            if (client.currentScreen != null) {
                if (voice.isRecording()) voice.stop(client);
                suppressUntilRelease = now;
            } else {
                if (now && !held && !suppressUntilRelease) voice.start(client);
                if (!now && held && voice.isRecording()) voice.stop(client);
                if (now && voice.hasTimedOut()) { voice.stop(client); suppressUntilRelease = true; }
            }
            if (!now) suppressUntilRelease = false;
            held = now;
        });
        // 收到 AI 回复时播放 TTS
        ClientPlayNetworking.registerGlobalReceiver(cn.shabaoai.companion.net.AiReplyPayload.ID,
                (payload, context) -> voice.speak(context.client(), payload.text()));
    }
}
