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

import cn.shabaoai.companion.config.ModConfig;
import cn.shabaoai.companion.ai.EpisodicMemory;
import cn.shabaoai.companion.ai.InteractionManager;
import cn.shabaoai.companion.net.AiReplyPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 队友管理器：为每个主人维护一个 {@link CompanionEntity}。
 *
 * <p>替代旧的 CompanionFakePlayer。FakePlayer 不可见于客户端，
 * 这里改用可见的自定义实体，所有 spawn/移除/发消息/移动逻辑都封装在此。
 *
 * <p>调用方只需用主人 UUID 操作，不用直接接触实体对象。
 */
public final class CompanionManager {
    /** 按主人 UUID 管理的队友实体 */
    private static final Map<UUID, CompanionEntity> COMPANIONS = new ConcurrentHashMap<>();

    private CompanionManager() {}

    /**
     * 生成或重新定位主人的队友。
     * 已存在则传送到主人身边刷新位置；不存在则创建并加入世界。
     *
     * @return 队友实体（已 spawn 到世界）
     */
    public static CompanionEntity getOrCreate(ServerPlayerEntity owner, String name) {
        UUID ownerUuid = owner.getUuid();
        CompanionEntity existing = COMPANIONS.get(ownerUuid);
        ServerWorld world = owner.getServerWorld();

        if (existing != null && !existing.isRemoved() && existing.getWorld() == world) {
            syncConfiguredName(existing);
            // 已存在：传送到主人身边
            existing.refreshPositionAndAngles(
                    owner.getX() + 1, owner.getY(), owner.getZ() + 1,
                    owner.getYaw(), 0.0F);
            return existing;
        }
        if (existing != null && !existing.isRemoved()) existing.discard();
        // 创建新实体并加入世界
        CompanionEntity companion = CompanionEntity.create(world, owner, name);
        world.spawnEntity(companion);
        COMPANIONS.put(ownerUuid, companion);
        return companion;
    }

    /** 获取主人的队友实体，不存在返回 null */
    public static CompanionEntity get(UUID ownerUuid) {
        return COMPANIONS.get(ownerUuid);
    }

    /**
     * 【M9】注册已存在的队友实体（服务器重启/实体从 NBT 加载后调用）。
     *
     * <p>服务器重启后 COMPANIONS 映射清空，但世界中的队友实体（已持久化）会随
     * chunk 重新加载，ownerUuid 已从 NBT 恢复。若不重新注册，exists() 返回 false，
     * 玩家聊天不会转发给 AI。此方法按 ownerUuid 幂等注册。
     */
    public static void register(CompanionEntity companion) {
        UUID ownerUuid = companion.getOwnerUuid();
        if (ownerUuid == null || companion.isRemoved()) return;
        syncConfiguredName(companion);
        COMPANIONS.compute(ownerUuid, (id, current) ->
                current == null || current.isRemoved() ? companion : current);
    }

    /** chunk 卸载时只移除当前实体对应的映射，避免旧对象挡住重载后的新实体。 */
    public static void unregister(CompanionEntity companion) {
        if (companion == null || companion.getOwnerUuid() == null) return;
        COMPANIONS.remove(companion.getOwnerUuid(), companion);
    }

    /** 移除并销毁主人的队友 */
    public static void remove(UUID ownerUuid) {
        CompanionEntity companion = COMPANIONS.remove(ownerUuid);
        if (companion != null && !companion.isRemoved()) {
            companion.discard();
        }
    }

    /** 主人的队友是否存在且存活 */
    public static boolean exists(UUID ownerUuid) {
        CompanionEntity companion = COMPANIONS.get(ownerUuid);
        return companion != null && !companion.isRemoved();
    }

    /**
     * 以队友身份给<b>主人</b>发一条私聊消息（AI 队友的日常话术只对主人可见，
     * 不做全服广播——否则 A 玩家沙包说"小心岩浆"，全服务器的人都看到）。
     * 格式：&lt;队友名&gt; 消息
     *
     * <p>线程安全：内部通过 server.execute 提交，任意线程可调用；
     * 若调用方已在服务端主线程则立即执行，否则排队到主线程（防异步线程直接碰 MC 对象）。
     */
    public static void sendMessage(ServerPlayerEntity owner, String message) {
        sendMessage(owner, message, EpisodicMemory.Source.BRAIN);
    }

    /** 带来源的统一回复出口：聊天、TTS、情景记忆在同一个主线程事务中提交。 */
    public static void sendMessage(ServerPlayerEntity owner, String message, EpisodicMemory.Source source) {
        CompanionEntity companion = COMPANIONS.get(owner.getUuid());
        if (companion == null || companion.isRemoved() || owner.getServer() == null) return;
        MinecraftServer server = owner.getServer();
        server.execute(() -> {
            if (owner.getServer() == null || companion.isRemoved()) return;
            syncConfiguredName(companion);
            String name = ModConfig.normalizeCompanionName(ModConfig.get().companionName);
            owner.sendMessage(Text.literal("<" + name + "> " + message), false);
            EpisodicMemory.get().recordMessage(owner, EpisodicMemory.Type.AI_REPLY, message, source);
            if (ServerPlayNetworking.canSend(owner, AiReplyPayload.ID)) {
                ServerPlayNetworking.send(owner, new AiReplyPayload(message));
            }
        });
    }

    /** 玩家跨维度时重建伙伴到玩家所在世界，保留名字与跟随开关。 */
    public static void ensureSameWorld(ServerPlayerEntity owner) {
        CompanionEntity current = COMPANIONS.get(owner.getUuid());
        if (current == null || current.isRemoved() || current.getWorld() == owner.getServerWorld()) return;
        String name = ModConfig.get().companionName;
        boolean follow = current.isFollowEnabled();
        current.discard();
        CompanionEntity replacement = CompanionEntity.create(owner.getServerWorld(), owner, name);
        replacement.setFollowEnabled(follow);
        owner.getServerWorld().spawnEntity(replacement);
        COMPANIONS.put(owner.getUuid(), replacement);
    }

    /** 让已存在/从 NBT 恢复的实体及时跟随配置名，同时保留固定核心标识。 */
    static void syncConfiguredName(CompanionEntity companion) {
        if (companion == null || companion.isRemoved()) return;
        String expected = ModConfig.companionEntityName(ModConfig.get().companionName);
        if (companion.getCustomName() == null || !expected.equals(companion.getCustomName().getString())) {
            companion.setCustomName(Text.literal(expected));
            companion.setCustomNameVisible(true);
        }
    }

    /**
     * 让队友走向指定方块位置（建造时调用）。
     */
    public static void moveTo(ServerPlayerEntity owner, BlockPos target) {
        InteractionManager.get().cancel(owner.getUuid(), "scripted_walk_started", false);
        CompanionEntity companion = COMPANIONS.get(owner.getUuid());
        if (companion == null || companion.isRemoved()) return;
        companion.navigateTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
    }

    /**
     * 设置队友建造状态：建造中停止跟随主人，盯工地快速建造。
     * @param building true=正在建造（停止跟随），false=建造完成（恢复跟随）
     */
    public static void setBuilding(UUID ownerUuid, boolean building) {
        CompanionEntity companion = COMPANIONS.get(ownerUuid);
        if (companion != null && !companion.isRemoved()) {
            companion.setBuilding(building);
        }
    }

    /**
     * 设置队友跟随总开关：false 时实体立即 navigation.stop()（物理急停），
     * 且 FollowOwnerGoal 不再启动/继续。玩家说"别跟了/停"与 goal 离开 follow 状态时调用。
     * @param enabled true=恢复跟随，false=急停
     */
    public static void setFollowEnabled(UUID ownerUuid, boolean enabled) {
        CompanionEntity companion = COMPANIONS.get(ownerUuid);
        if (companion != null && !companion.isRemoved()) {
            companion.setFollowEnabled(enabled);
        }
    }

    /** 查询当前跟随总开关（实体缺失时返回 false，调用方按"没跟"处理） */
    public static boolean isFollowEnabled(UUID ownerUuid) {
        CompanionEntity companion = COMPANIONS.get(ownerUuid);
        return companion != null && !companion.isRemoved() && companion.isFollowEnabled();
    }

    /** 清理所有队友（服务器关闭时调用） */
    public static void clearAll() {
        for (CompanionEntity companion : COMPANIONS.values()) {
            if (companion != null && !companion.isRemoved()) {
                companion.discard();
            }
        }
        COMPANIONS.clear();
    }

    /** 停服只清 Java 映射，不 discard 世界实体；实体由世界保存负责跨重启恢复。 */
    public static void clearMappings() {
        COMPANIONS.clear();
    }
}
