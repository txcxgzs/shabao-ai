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

import cn.shabaoai.companion.entity.CompanionEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.util.Identifier;

/**
 * AI 队友实体的渲染器：让它看起来像一个真实的玩家。
 *
 * <p>用 {@link PlayerEntityModel} 渲染，这是 Minecraft 原生玩家模型，
 * 包含完整的身体、四肢、头部，走路时会有摆臂动画。
 *
 * <p>皮肤纹理由 {@link CompanionSkinTexture} 从配置的本地 PNG 动态加载；
 * 默认使用 JAR 内置皮肤库；外部 PNG 未启用或加载失败时回退当前选中的内置皮肤。
 * 客户端注册见 {@link cn.shabaoai.companion.client.ShabaoAiClient}。
 */
public class CompanionRenderer extends LivingEntityRenderer<CompanionEntity, PlayerEntityModel<CompanionEntity>> {
    /** Steve 皮肤纹理路径（1.21 wide 模型） */
    private static final Identifier STEVE_TEXTURE =
            Identifier.of("minecraft", "textures/entity/player/wide/steve.png");

    public CompanionRenderer(EntityRendererFactory.Context ctx) {
        // 从上下文获取原生玩家模型（wide 宽臂版本），thinArms=false
        super(ctx, new CompanionPlayerModel(ctx.getPart(EntityModelLayers.PLAYER), false), 0.5f);
    }

    @Override
    public Identifier getTexture(CompanionEntity entity) {
        return CompanionSkinTexture.getOrFallback(STEVE_TEXTURE);
    }
}
