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

package cn.shabaoai.companion.mixin.client;

import cn.shabaoai.companion.ai.InteractionManager;
import cn.shabaoai.companion.entity.CompanionEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 让玩家第三人称模型与伙伴同步抬手/拥抱/倾头；不修改玩家位置、朝向或镜头。 */
@Mixin(PlayerEntityModel.class)
public abstract class PlayerEntityModelMixin<T extends LivingEntity> {
    @Inject(method = "setAngles(Lnet/minecraft/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void shabaoAi$applyInteractionPose(T living, float limbAngle, float limbDistance,
                                                float animationProgress, float headYaw, float headPitch,
                                                CallbackInfo ci) {
        if (!(living instanceof PlayerEntity player)) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;
        @SuppressWarnings("unchecked")
        PlayerEntityModel<T> model = (PlayerEntityModel<T>) (Object) this;
        // PERFORM 切到 RECOVER 时 companion 仍存在，不能只在找不到 companion 时清理。
        if (Math.abs(Math.abs(model.head.roll) - 0.24F) < 0.001F) model.head.roll = 0.0F;
        if (Math.abs(model.body.pitch + 0.10F) < 0.001F) model.body.pitch = 0.0F;
        CompanionEntity companion = findCompanion(client, player.getId());
        if (companion == null) {
            return;
        }

        int type = Byte.toUnsignedInt(companion.getInteractionType());
        int phase = Byte.toUnsignedInt(companion.getInteractionPhase());
        if (type == InteractionManager.Type.HOLD_HAND.ordinal()
                && phase == InteractionManager.Phase.HOLDING.ordinal()) {
            // 伙伴在玩家右侧时用左手，玩家对应使用右手；反之亦然。
            ModelPart arm = companion.isInteractionLeftArm() ? model.rightArm : model.leftArm;
            arm.pitch = -0.82F;
            arm.yaw = companion.isInteractionLeftArm() ? 0.34F : -0.34F;
            arm.roll = companion.isInteractionLeftArm() ? 0.18F : -0.18F;
        } else if (type == InteractionManager.Type.HUG.ordinal()
                && phase == InteractionManager.Phase.PERFORM.ordinal()) {
            model.leftArm.pitch = -1.02F;
            model.leftArm.yaw = -0.30F;
            model.leftArm.roll = -0.10F;
            model.rightArm.pitch = -1.02F;
            model.rightArm.yaw = 0.30F;
            model.rightArm.roll = 0.10F;
            model.body.pitch = -0.04F;
        } else if (type == InteractionManager.Type.KISS.ordinal()
                && phase == InteractionManager.Phase.PERFORM.ordinal()) {
            model.head.roll = companion.isInteractionLeftArm() ? -0.24F : 0.24F;
            model.body.pitch = -0.10F;
        }
        // vanilla 在 setAngles 中更早复制了衣袖；覆盖手臂后再同步一次，避免皮肤外层留在原位。
        model.leftSleeve.copyTransform(model.leftArm);
        model.rightSleeve.copyTransform(model.rightArm);
        model.jacket.copyTransform(model.body);
    }

    private static CompanionEntity findCompanion(MinecraftClient client, int playerEntityId) {
        for (Entity entity : client.world.getEntities()) {
            if (entity instanceof CompanionEntity companion
                    && companion.getInteractionPartnerId() == playerEntityId
                    && companion.getInteractionType() != 0) {
                return companion;
            }
        }
        return null;
    }
}
