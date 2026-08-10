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

import cn.shabaoai.companion.net.BuildPreviewPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * 建筑预览客户端渲染器。
 *
 * <p>当服务端需要玩家确认放置方块时，会发送 {@link BuildPreviewPayload} 到客户端，
 * 本类在世界中渲染绿色半透明线框，让玩家能立体看到 AI 即将放置方块的位置。
 *
 * <p>实现：
 * <ul>
 *   <li>注册 {@link WorldRenderEvents#AFTER_TRANSLUCENT} 回调</li>
 *   <li>用 {@link VertexConsumerProvider} 和 {@link RenderLayer#getLines()} 画线框</li>
 *   <li>位置是方块轮廓（从角到角），绿色（R=0, G=255, B=0），半透明</li>
 * </ul>
 */
public final class BuildPreviewRenderer {
    /** 当前待预览的方块位置列表 */
    private static final List<BlockPos> PREVIEW_POSITIONS = new ArrayList<>();

    private BuildPreviewRenderer() {}

    /** 注册网络包接收器和世界渲染事件 */
    public static void register() {
        // 接收服务端发来的预览数据
        ClientPlayNetworking.registerGlobalReceiver(BuildPreviewPayload.ID,
                (payload, context) -> {
                    PREVIEW_POSITIONS.clear();
                    if (!payload.clear()) {
                        PREVIEW_POSITIONS.addAll(payload.positions());
                    }
                });

        // 每帧渲染预览线框
        WorldRenderEvents.AFTER_TRANSLUCENT.register(BuildPreviewRenderer::render);
    }

    /**
     * 渲染所有预览方块的绿色线框。
     */
    private static void render(WorldRenderContext context) {
        if (PREVIEW_POSITIONS.isEmpty()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        // 获取摄像机位置，用于把世界坐标转换为相对摄像机坐标
        Vec3d camera = context.camera().getPos();
        MatrixStack matrices = context.matrixStack();
        VertexConsumerProvider.Immediate consumers = client.getBufferBuilders().getEntityVertexConsumers();
        VertexConsumer buffer = consumers.getBuffer(RenderLayer.getLines());

        matrices.push();
        // 先把矩阵原点移到摄像机位置
        matrices.translate(-camera.x, -camera.y, -camera.z);

        for (BlockPos pos : PREVIEW_POSITIONS) {
            renderBox(matrices, buffer, pos);
        }

        matrices.pop();
        consumers.draw();
    }

    /**
     * 渲染单个方块的绿色线框。
     * 使用 Minecraft 原生的 {@link WorldRenderer#drawBox}，稳定可靠。
     */
    private static void renderBox(MatrixStack matrices, VertexConsumer buffer, BlockPos pos) {
        // 稍微向外扩 0.005，避免与真实方块边缘 z-fighting
        double minX = pos.getX() - 0.005;
        double minY = pos.getY() - 0.005;
        double minZ = pos.getZ() - 0.005;
        double maxX = pos.getX() + 1.005;
        double maxY = pos.getY() + 1.005;
        double maxZ = pos.getZ() + 1.005;
        // 绿色：R=0, G=1, B=0, alpha=0.6
        WorldRenderer.drawBox(matrices, buffer, minX, minY, minZ, maxX, maxY, maxZ,
                0.0f, 1.0f, 0.0f, 0.6f);
    }
}
