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
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.ai.pathing.MobNavigation;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.world.World;

import java.util.EnumSet;
import java.util.UUID;

/**
 * AI 队友实体：在游戏世界里可见的"玩家样貌"实体。
 *
 * <p>为什么不用 Fabric API 的 FakePlayer：
 * FakePlayer 没有真实网络连接，服务端不会向客户端发送 spawn 包，
 * 玩家在游戏里根本看不见它。FakePlayer 只用于服务端逻辑模拟（如模组
 * 模拟玩家执行命令/破坏方块），不适合做可见的"队友"。
 *
 * <p>本实体继承 {@link MobEntity}，拥有完整的 AI 寻路系统，
 * 客户端通过 {@link cn.shabaoai.companion.client.CompanionRenderer}
 * 用 {@link net.minecraft.client.render.entity.model.PlayerEntityModel}
 * 渲染，看起来就是一个真实的玩家（Steve/Alex 皮肤）。
 *
 * <p>跟随逻辑：内置 {@link FollowOwnerGoal}，自动走向主人，
 * 太远时传送过来，近距离时停下。
 */
public class CompanionEntity extends MobEntity {
    private static final TrackedData<Byte> INTERACTION_TYPE =
            DataTracker.registerData(CompanionEntity.class, TrackedDataHandlerRegistry.BYTE);
    private static final TrackedData<Byte> INTERACTION_PHASE =
            DataTracker.registerData(CompanionEntity.class, TrackedDataHandlerRegistry.BYTE);
    private static final TrackedData<Integer> INTERACTION_PARTNER_ID =
            DataTracker.registerData(CompanionEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Boolean> INTERACTION_LEFT_ARM =
            DataTracker.registerData(CompanionEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Long> INTERACTION_STARTED_AT =
            DataTracker.registerData(CompanionEntity.class, TrackedDataHandlerRegistry.LONG);
    /** 主人 UUID，持久化用 */
    private UUID ownerUuid;
    /** 是否正在建造（建造时停止跟随主人，盯工地） */
    private boolean building = false;

    /**
     * 跟随开关：FollowOwnerGoal 的唯一总开关（与 building 并列）。
     * 默认 true（实体创建即跟随主人）；玩家说"别跟了/停"或 goal 不再处于
     * follow 状态时置 false 并立即 navigation.stop()——真正的物理急停。
     * 由 AgentExecutor 在本地意图/工具路径立即设置（无每秒轮询，避免误伤无 goal 玩家）。
     */
    private boolean followEnabled = true;

    /** NBT 键名：主人 UUID。实体卸载/服务器重启后从 NBT 恢复，否则 FollowOwnerGoal 找不到主人（M9） */
    private static final String NBT_OWNER = "CompanionOwner";
    private static final String NBT_FOLLOW_ENABLED = "CompanionFollowEnabled";

    public CompanionEntity(EntityType<? extends CompanionEntity> type, World world) {
        super(type, world);
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(INTERACTION_TYPE, (byte) 0);
        builder.add(INTERACTION_PHASE, (byte) 0);
        builder.add(INTERACTION_PARTNER_ID, -1);
        builder.add(INTERACTION_LEFT_ARM, true);
        builder.add(INTERACTION_STARTED_AT, 0L);
    }

    @Override
    public void tick() {
        super.tick();
        // Mod 管理界面保存角色名后，无需重召唤或先发一条消息；服务端最多 1 秒刷新名牌。
        // 仅在文本确实变化时才写 DataTracker，避免每 Tick 产生同步流量。
        if (!getWorld().isClient && age % 20 == 0) {
            CompanionManager.syncConfiguredName(this);
        }
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        // 【M9】持久化 ownerUuid：chunk 卸载重载、服务器重启后仍能找回主人
        if (ownerUuid != null) {
            nbt.putUuid(NBT_OWNER, ownerUuid);
        }
        nbt.putBoolean(NBT_FOLLOW_ENABLED, followEnabled);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        // 【M9】恢复 ownerUuid（旧实体没有该字段时保持 null，不报错）
        if (nbt.containsUuid(NBT_OWNER)) {
            ownerUuid = nbt.getUuid(NBT_OWNER);
        }
        if (nbt.contains(NBT_FOLLOW_ENABLED)) {
            followEnabled = nbt.getBoolean(NBT_FOLLOW_ENABLED);
        }
    }

    /** 注册实体属性：移动速度、血量等，让 navigation 能正常工作 */
    public static DefaultAttributeContainer.Builder createCompanionAttributes() {
        return MobEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.35)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 1.0)
                // 【W-2】GENERIC_FOLLOW_RANGE 设 48：land mob 默认 16，寻路搜索上限=该值，
                // 超 16 格的 walk 目标直接无路。设 48 覆盖 FollowOwnerGoal 的 32 格跟随距离，
                // 否则"跟随 32、寻路 16"从一开始就对不上
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 48.0);
    }

    @Override
    protected EntityNavigation createNavigation(World world) {
        // 用标准 MobNavigation，让实体能寻路走向主人/建造点
        return new MobNavigation(this, world);
    }

    @Override
    protected void initGoals() {
        // 唯一目标：跟随主人。priority 1，无其他行为干扰
        goalSelector.add(1, new FollowOwnerGoal(this));
    }

    /** 设置主人 UUID（创建时调用） */
    public void setOwner(UUID uuid) {
        this.ownerUuid = uuid;
    }

    /** 获取主人 UUID */
    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    /** 设置建造状态：建造中停止跟随主人 */
    public void setBuilding(boolean building) {
        this.building = building;
        if (building) {
            // 停止当前寻路
            getNavigation().stop();
        }
    }

    /** 是否正在建造 */
    public boolean isBuilding() {
        return building;
    }

    /**
     * 设置跟随开关（FollowOwnerGoal 总开关）。
     * 关闭时立即 navigation.stop()，下一游戏 tick 级物理急停——不等 FollowOwnerGoal
     * 自己的 shouldContinue 判定，玩家说"别跟了"立刻站住。
     */
    public void setFollowEnabled(boolean enabled) {
        this.followEnabled = enabled;
        if (!enabled) {
            getNavigation().stop();
        }
    }

    /** 跟随是否开启（FollowOwnerGoal.canStart/shouldContinue 检查它） */
    public boolean isFollowEnabled() {
        return followEnabled;
    }

    /** 服务端更新、客户端渲染读取的身体互动同步状态。 */
    public void setInteractionState(byte type, byte phase, int partnerEntityId,
                                    boolean companionUsesLeftArm, long startedAt) {
        getDataTracker().set(INTERACTION_TYPE, type);
        getDataTracker().set(INTERACTION_PHASE, phase);
        getDataTracker().set(INTERACTION_PARTNER_ID, partnerEntityId);
        getDataTracker().set(INTERACTION_LEFT_ARM, companionUsesLeftArm);
        getDataTracker().set(INTERACTION_STARTED_AT, startedAt);
    }

    public void clearInteractionState() {
        setInteractionState((byte) 0, (byte) 0, -1, true, 0L);
    }

    public byte getInteractionType() { return getDataTracker().get(INTERACTION_TYPE); }
    public byte getInteractionPhase() { return getDataTracker().get(INTERACTION_PHASE); }
    public int getInteractionPartnerId() { return getDataTracker().get(INTERACTION_PARTNER_ID); }
    public boolean isInteractionLeftArm() { return getDataTracker().get(INTERACTION_LEFT_ARM); }
    public long getInteractionStartedAt() { return getDataTracker().get(INTERACTION_STARTED_AT); }

    /**
     * 工厂方法：创建一个已配置好的队友实体（创造、无敌、显示名字）。
     * 调用方负责 spawnEntity 加入世界。
     */
    public static CompanionEntity create(ServerWorld world, ServerPlayerEntity owner, String name) {
        CompanionEntity e = cn.shabaoai.companion.entity.ModEntities.COMPANION.create(world);
        // 出生在主人旁边一格
        e.refreshPositionAndAngles(owner.getX() + 1, owner.getY(), owner.getZ() + 1,
                owner.getYaw(), 0.0F);
        e.setOwner(owner.getUuid());
        e.setCustomName(Text.literal(ModConfig.companionEntityName(name)));
        e.setCustomNameVisible(true);
        // 无敌，避免被怪打死影响体验
        e.setInvulnerable(true);
        // 永不自然消失
        e.setPersistent();
        return e;
    }

    /**
     * 让实体走向指定方块位置（建造时调用）。
     * 用 navigation 寻路，比手动 move 更自然。
     *
     * <p>【W-3】返回 startMovingTo 的 boolean：false 表示根本没找到路
     * （目标超 48 格 / 落在实心方块里 / 只有梯子没有楼梯——land mob 不会爬梯子）。
     * 调用方据此给 LLM 准确反馈，而不是"已前往"的假肯定句。
     */
    public boolean navigateTo(double x, double y, double z) {
        return getNavigation().startMovingTo(x, y, z, 1.0);
    }

    /**
     * 跟随主人的 AI goal。
     * 太远（>32 格）传送，近距离（<3 格）停下，中间用 navigation 走过去。
     */
    private static final class FollowOwnerGoal extends Goal {
        private final CompanionEntity companion;
        private final double followRange = 32 * 32;       // 平方距离阈值（>32 格传送）
        private final double startFollowRange = 3 * 3;   // 开始跟随距离（>3 格走过去）
        private final double stopFollowRange = 2 * 2;    // 停止跟随距离（<2 格停下）
        // 【W-6】寻路节流计数器：vanilla start() 应在此设节流，原为空方法 → 20Hz 满负荷重寻路
        private int pathRecalcCooldown = 0;

        FollowOwnerGoal(CompanionEntity companion) {
            this.companion = companion;
            setControls(EnumSet.of(Control.MOVE, Control.LOOK));
        }

        @Override
        public boolean canStart() {
            // 建造中不跟随 / 跟随总开关已关（玩家说"别跟了"）不跟随
            if (companion.building || !companion.followEnabled) return false;
            PlayerEntity owner = getOwner();
            if (owner == null || !owner.isAlive()) return false;
            // 距离够远才启动跟随
            return companion.squaredDistanceTo(owner) > startFollowRange;
        }

        @Override
        public boolean shouldContinue() {
            // 建造中不跟随 / 跟随总开关已关（急停后本 tick 立即退出并停止寻路）
            if (companion.building || !companion.followEnabled) return false;
            PlayerEntity owner = getOwner();
            if (owner == null || !owner.isAlive()) return false;
            // 【W-6】去掉 followRange 上界：原 dist<=followRange 在 >32 格时返回 false，
            // 导致 tick() 里的传送分支（d>1024 才触发）只在 canStart 命中的那一 tick 够得着，
            // 区间写反。现在只要 >stopFollowRange 就继续，传送判定留在 tick
            return companion.squaredDistanceTo(owner) > stopFollowRange;
        }

        @Override
        public void start() {
            // 【W-6】设 10 tick 节流：每 0.5 秒才重算一次寻路，而不是 20Hz 满负荷
            pathRecalcCooldown = 10;
        }

        @Override
        public void stop() {
            companion.getNavigation().stop();
        }

        @Override
        public void tick() {
            PlayerEntity owner = getOwner();
            if (owner == null) return;
            // 太远：直接传送到主人身边
            if (companion.squaredDistanceTo(owner) > followRange) {
                companion.refreshPositionAndAngles(
                        owner.getX() + 1, owner.getY(), owner.getZ() + 1,
                        owner.getYaw(), 0.0F);
                return;
            }
            // 【W-6】节流：未到重算时刻就只看向主人，不重发寻路（20Hz→2Hz）
            if (--pathRecalcCooldown > 0) {
                companion.getLookControl().lookAt(owner, 30.0F, 30.0F);
                return;
            }
            pathRecalcCooldown = 10;
            // 走向主人
            companion.getNavigation().startMovingTo(owner, 1.0);
            // 看向主人，让朝向更自然
            companion.getLookControl().lookAt(owner, 30.0F, 30.0F);
        }

        /** 获取主人玩家（仅服务端有效）。【L8】主人与队友不在同一世界（跨维度）时返回 null，
         * 否则 squaredDistanceTo 用两个不同世界的坐标算距离毫无意义，会导致反复传送/寻路异常。 */
        private PlayerEntity getOwner() {
            if (companion.getWorld().isClient) return null;
            UUID uuid = companion.getOwnerUuid();
            if (uuid == null) return null;
            PlayerEntity owner = companion.getWorld().getServer().getPlayerManager().getPlayer(uuid);
            if (owner != null && owner.getWorld() != companion.getWorld()) return null;
            return owner;
        }
    }
}
