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

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.Set;

/**
 * 方块 ID 与 BlockState 之间的转换工具。
 *
 * <p>LLM 输出形如 "minecraft:stone" 或 "stone" 的方块 ID，
 * 这里负责转成游戏内的 BlockState（默认状态）。
 *
 * <p>不再硬性禁止危险方块，改为用 {@link #isDangerous} 判定，
 * 危险方块在 Agent 执行时触发玩家确认流程（见 ConfirmationManager）。
 */
public final class BlockCodec {
    private BlockCodec() {}

    /**
     * 危险方块名单：放置这些方块前需要玩家确认。
     * 包括爆炸物、流体危险源、不可破坏方块、传送门等。
     * 不再禁止，改为询问玩家确认。
     */
    private static final Set<String> DANGEROUS = Set.of(
            "minecraft:tnt", "minecraft:lava", "minecraft:flowing_lava",
            "minecraft:fire", "minecraft:bedrock", "minecraft:barrier",
            "minecraft:command_block", "minecraft:repeating_command_block",
            "minecraft:chain_command_block", "minecraft:structure_block",
            "minecraft:jigsaw", "minecraft:spawner", "minecraft:end_portal",
            "minecraft:end_portal_frame", "minecraft:end_gateway",
            "minecraft:nether_portal", "minecraft:dragon_egg",
            // 【修U】床不再列入危险：床只在末地爆炸，主世界是正常家具。
            // 之前全量列入导致 AI 在主世界放床被替换成白羊毛（20:41 会话 2 块羊毛实为红床）
            // 水和流动水在大量放置时可能冲毁方块
            "minecraft:water", "minecraft:flowing_water"
    );

    /**
     * 根据 ID 解析方块为 BlockState，支持 Minecraft 原生属性语法。
     *
     * <p>支持格式：
     * <ul>
     *   <li>{@code "stone"} 或 {@code "minecraft:stone"} —— 默认状态</li>
     *   <li>{@code "oak_stairs[facing=south,half=top]"} —— 带属性的状态</li>
     * </ul>
     *
     * <p>属性语法用于楼梯、门、梯子等有方向/朝向的方块，避免放置后变成掉落物或朝向错误。
     *
     * @param id 方块 ID，可带方括号属性
     * @return 对应 BlockState；非法 ID 返回 null
     */
    public static BlockState parse(String id) {
        if (id == null || id.isBlank()) return null;
        String trimmed = id.trim();

        // 分离方块 ID 和属性 [prop=val,prop=val]
        String blockPart = trimmed;
        String propsPart = null;
        int bracket = trimmed.indexOf('[');
        if (bracket >= 0) {
            blockPart = trimmed.substring(0, bracket);
            int close = trimmed.indexOf(']', bracket);
            if (close > bracket) {
                propsPart = trimmed.substring(bracket + 1, close);
            }
        }

        String normalized = blockPart.startsWith("minecraft:") ? blockPart : "minecraft:" + blockPart;
        Identifier identifier = Identifier.tryParse(normalized);
        if (identifier == null) return null;
        Block block = Registries.BLOCK.get(identifier);
        // Registries.BLOCK.get 对未知 ID 返回 Blocks.AIR，需要单独判断
        if (block == Blocks.AIR && !normalized.equals("minecraft:air")) return null;
        BlockState state = block.getDefaultState();

        // 应用方括号里的属性
        if (propsPart != null && !propsPart.isEmpty()) {
            for (String pair : propsPart.split(",")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String name = pair.substring(0, eq).trim();
                String value = pair.substring(eq + 1).trim();
                var property = block.getStateManager().getProperty(name);
                if (property != null) {
                    state = applyProperty(state, property, value);
                }
            }
        }
        return state;
    }

    /**
     * 根据字符串值设置方块属性（泛型辅助方法）。
     * 例如楼梯的 facing=south 会调用 StairsBlock.FACING.parse("south") 解析成 Direction.SOUTH。
     */
    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> BlockState applyProperty(BlockState state,
                                                                       net.minecraft.state.property.Property<T> property,
                                                                       String value) {
        java.util.Optional<T> parsed = property.parse(value);
        return parsed.isPresent() ? state.with(property, parsed.get()) : state;
    }

    /** BlockState 转回 ID 字符串，如 "minecraft:stone" */
    public static String idOf(BlockState state) {
        return Registries.BLOCK.getId(state.getBlock()).toString();
    }

    /** 判断方块 ID 是否属于危险方块（需玩家确认） */
    public static boolean isDangerous(String id) {
        if (id == null || id.isBlank()) return false;
        // 【M8】先剥离方括号属性再匹配：door/stair 等 shape 生成 "[facing=...]" 格式，
        // 带属性的 "tnt[fuse=1]" 若不剥离会绕过 DANGEROUS 名单
        String base = id.trim();
        int bracket = base.indexOf('[');
        if (bracket >= 0) base = base.substring(0, bracket);
        String normalized = base.startsWith("minecraft:") ? base : "minecraft:" + base;
        return DANGEROUS.contains(normalized);
    }

    /**
     * 判断方块是否为"自然方块"（世界自然生成的，非玩家建造）。
     * 覆盖自然方块不需要确认，覆盖玩家建造的方块才需要确认。
     *
     * <p>包括：草地、泥土、石头、沙子、沙砾、各种原木/树叶、矿石、
     * 水/岩浆（自然生成）、雪、冰、基岩、黑曜石、各种装饰石等。
     */
    public static boolean isNatural(BlockState state) {
        if (state == null) return true;
        Block block = state.getBlock();
        // 空气也算自然，覆盖它等于放置
        if (state.isAir()) return true;
        // 用标签判断自然方块
        if (state.isIn(net.minecraft.registry.tag.BlockTags.DIRT)) return true;
        if (state.isIn(net.minecraft.registry.tag.BlockTags.STONE_ORE_REPLACEABLES)) return true;
        if (state.isIn(net.minecraft.registry.tag.BlockTags.BASE_STONE_OVERWORLD)) return true;
        if (state.isIn(net.minecraft.registry.tag.BlockTags.BASE_STONE_NETHER)) return true;
        if (state.isIn(net.minecraft.registry.tag.BlockTags.LEAVES)) return true;
        if (state.isIn(net.minecraft.registry.tag.BlockTags.LOGS)) return true;
        if (state.isIn(net.minecraft.registry.tag.BlockTags.SAND)) return true;
        if (state.isIn(net.minecraft.registry.tag.BlockTags.SNOW)) return true;
        if (state.isIn(net.minecraft.registry.tag.BlockTags.ICE)) return true;
        // 常见自然方块白名单（用 Set 判断，兼容 Java 21）
        return NATURAL_BLOCKS.contains(block);
    }

    /** 自然方块白名单：世界自然生成的方块，覆盖它们不需要玩家确认 */
    private static final java.util.Set<Block> NATURAL_BLOCKS = java.util.Set.of(
            Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.STONE, Blocks.COBBLESTONE,
            Blocks.SAND, Blocks.GRAVEL, Blocks.CLAY, Blocks.SANDSTONE,
            Blocks.OBSIDIAN, Blocks.BEDROCK, Blocks.WATER, Blocks.LAVA,
            Blocks.SNOW_BLOCK, Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE,
            Blocks.NETHERRACK, Blocks.BASALT, Blocks.END_STONE, Blocks.TERRACOTTA,
            Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE, Blocks.DEEPSLATE,
            Blocks.COAL_ORE, Blocks.IRON_ORE, Blocks.GOLD_ORE, Blocks.DIAMOND_ORE,
            Blocks.REDSTONE_ORE, Blocks.EMERALD_ORE, Blocks.LAPIS_ORE, Blocks.NETHER_QUARTZ_ORE,
            Blocks.ANCIENT_DEBRIS, Blocks.MOSS_BLOCK, Blocks.PODZOL, Blocks.MYCELIUM,
            Blocks.OAK_LOG, Blocks.SPRUCE_LOG, Blocks.BIRCH_LOG, Blocks.JUNGLE_LOG,
            Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG, Blocks.CHERRY_LOG, Blocks.MANGROVE_LOG,
            Blocks.OAK_LEAVES, Blocks.SPRUCE_LEAVES, Blocks.BIRCH_LEAVES, Blocks.JUNGLE_LEAVES,
            Blocks.ACACIA_LEAVES, Blocks.DARK_OAK_LEAVES, Blocks.CHERRY_LEAVES, Blocks.MANGROVE_LEAVES,
            Blocks.PUMPKIN, Blocks.MELON, Blocks.CACTUS, Blocks.BAMBOO
    );
}
