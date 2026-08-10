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

import cn.shabaoai.companion.ai.InteractionManager;
import cn.shabaoai.companion.entity.CompanionEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.PlayerEntityModel;

/** 原版玩家步行动画完成后，仅覆盖互动涉及的手臂/头部姿态。 */
public final class CompanionPlayerModel extends PlayerEntityModel<CompanionEntity> {
    public CompanionPlayerModel(ModelPart root, boolean thinArms) {
        super(root, thinArms);
    }

    @Override
    public void setAngles(CompanionEntity entity, float limbAngle, float limbDistance,
                          float animationProgress, float headYaw, float headPitch) {
        super.setAngles(entity, limbAngle, limbDistance, animationProgress, headYaw, headPitch);
        int type = Byte.toUnsignedInt(entity.getInteractionType());
        int phase = Byte.toUnsignedInt(entity.getInteractionPhase());
        // PlayerEntityModel 实例会跨帧复用，原版不保证重置 roll；先清掉本类上帧写入的残留。
        head.roll = 0.0F;
        if (Math.abs(body.pitch + 0.14F) < 0.001F || Math.abs(body.pitch + 0.06F) < 0.001F) {
            body.pitch = 0.0F;
        }
        if (type == InteractionManager.Type.HOLD_HAND.ordinal()
                && phase == InteractionManager.Phase.HOLDING.ordinal()) {
            ModelPart arm = entity.isInteractionLeftArm() ? leftArm : rightArm;
            arm.pitch = -0.82F;
            arm.yaw = entity.isInteractionLeftArm() ? -0.34F : 0.34F;
            arm.roll = entity.isInteractionLeftArm() ? -0.18F : 0.18F;
        } else if (type == InteractionManager.Type.HUG.ordinal()
                && phase == InteractionManager.Phase.PERFORM.ordinal()) {
            leftArm.pitch = -1.05F;
            leftArm.yaw = 0.34F;
            leftArm.roll = -0.12F;
            rightArm.pitch = -1.05F;
            rightArm.yaw = -0.34F;
            rightArm.roll = 0.12F;
            body.pitch = -0.06F;
        } else if (type == InteractionManager.Type.KISS.ordinal()
                && phase == InteractionManager.Phase.PERFORM.ordinal()) {
            head.roll = entity.isInteractionLeftArm() ? 0.24F : -0.24F;
            body.pitch = -0.14F;
        }
    }
}
