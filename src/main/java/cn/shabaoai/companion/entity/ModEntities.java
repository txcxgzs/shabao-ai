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

package cn.shabaoai.companion.entity;

import cn.shabaoai.companion.ShabaoAiMod;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;

/**
 * 实体类型注册。
 *
 * <p>注意：注册逻辑放在 {@link #register()} 方法里，由 {@link ShabaoAiMod#onInitialize()}
 * 在模组初始化阶段调用，而不是放在静态字段初始化里。
 *
 * <p>原因：之前曾因 Java 25 + Fabric Loader 0.19.3 在静态初始化阶段解析
 * {@code Registry} 类导致 {@code NoClassDefFoundError}，改为延迟注册后稳定。
 */
public final class ModEntities {
    /** AI 队友实体类型，0.6×1.8 的玩家尺寸碰撞箱 */
    public static EntityType<CompanionEntity> COMPANION;

    private ModEntities() {}

    /** 在 onInitialize 中调用，注册实体类型和属性 */
    public static void register() {
        // 用 FabricEntityTypeBuilder 注册实体类型
        COMPANION = Registry.register(
                Registries.ENTITY_TYPE,
                Identifier.of(ShabaoAiMod.MOD_ID, "companion"),
                FabricEntityTypeBuilder.create(SpawnGroup.CREATURE, CompanionEntity::new)
                        .dimensions(EntityDimensions.fixed(0.6f, 1.8f))
                        .trackRangeBlocks(80)   // 追踪范围，让远处也能看到
                        .build()
        );
        // 注册实体属性（移动速度、血量），让 navigation 能正常工作
        FabricDefaultAttributeRegistry.register(COMPANION, CompanionEntity.createCompanionAttributes());
    }
}
