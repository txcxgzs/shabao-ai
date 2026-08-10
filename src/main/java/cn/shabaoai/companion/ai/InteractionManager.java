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

import cn.shabaoai.companion.entity.CompanionEntity;
import cn.shabaoai.companion.entity.CompanionManager;
import cn.shabaoai.companion.config.ModConfig;
import com.google.gson.JsonObject;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 服务器权威的身体互动状态机；不占用 Goal，但在运行期间独占伙伴实体的移动。 */
public final class InteractionManager {
    public enum Type { NONE, HOLD_HAND, HUG, KISS }
    public enum Phase { NONE, APPROACH, ALIGN, HOLDING, PERFORM, RECOVER }

    private static final InteractionManager INSTANCE = new InteractionManager();
    private static final int HOLDING_DURATION_TICKS = 100; // 约 5 秒，避免不主动松手时无限持续
    private static final double HOLD_SIDE_OFFSET = 0.62;
    private static final double HUG_FRONT_OFFSET = 0.70;
    private static final double KISS_FRONT_OFFSET = 0.60;
    private static final double ARRIVAL_TOLERANCE = 0.34;
    private static final double HOLD_REPOSITION_TOLERANCE = 0.20;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    private InteractionManager() {}

    public static InteractionManager get() { return INSTANCE; }

    public static final class Session {
        public final UUID id = UUID.randomUUID();
        public final UUID playerId;
        public final Type type;
        public Phase phase = Phase.APPROACH;
        public final boolean initiatedByPlayer;
        public final boolean resumeFollow;
        public final boolean companionUsesLeftArm;
        public final long startedAt = System.currentTimeMillis();
        public int ticks;
        public int phaseTicks;
        public int farTicks;

        private Session(UUID playerId, Type type, boolean initiatedByPlayer,
                        boolean resumeFollow, boolean companionUsesLeftArm) {
            this.playerId = playerId;
            this.type = type;
            this.initiatedByPlayer = initiatedByPlayer;
            this.resumeFollow = resumeFollow;
            this.companionUsesLeftArm = companionUsesLeftArm;
        }
    }

    public String start(ServerPlayerEntity player, Type type, boolean foregroundPlayerTurn) {
        if (player == null || player.getServer() == null || type == null || type == Type.NONE) {
            return "互动无法开始";
        }
        RelationshipManager.Profile relationship = RelationshipManager.get().profile(player);
        if (relationship.status != RelationshipManager.Status.PARTNER) {
            return "只有已经建立的 PARTNER 关系才能开始亲密互动";
        }
        switch (relationship.interactionPolicy) {
            case OFF -> { return "玩家已关闭亲密互动"; }
            case PLAYER_ONLY -> {
                if (!foregroundPlayerTurn) return "当前设置仅允许玩家主动发起亲密互动";
            }
            case ASK_FIRST -> {
                if (!foregroundPlayerTurn) return "需要先自然询问玩家，得到下一条明确同意后再发起";
            }
            case AUTONOMOUS -> { /* 允许后台自然发起 */ }
        }
        if (!foregroundPlayerTurn) {
            long lastAt = switch (type) {
                case HOLD_HAND -> relationship.lastHandHoldAt;
                case HUG -> relationship.lastHugAt;
                case KISS -> relationship.lastKissAt;
                default -> 0L;
            };
            long cooldown = switch (relationship.initiative) {
                case LOW -> 30 * 60_000L;
                case NORMAL -> 10 * 60_000L;
                case HIGH -> 3 * 60_000L;
            };
            long remaining = cooldown - (System.currentTimeMillis() - lastAt);
            if (lastAt > 0 && remaining > 0) {
                return "主动互动仍在冷却中，约 " + Math.max(1, remaining / 60_000L) + " 分钟后再自然考虑";
            }
        }
        CompanionEntity companion = CompanionManager.get(player.getUuid());
        if (companion == null || companion.isRemoved() || companion.getWorld() != player.getWorld()) {
            return "伙伴当前不在玩家身边";
        }
        GoalManager.Goal currentGoal = AgentRuntime.get().goals().get(player.getUuid());
        if (currentGoal != null && "build".equals(currentGoal.type)) {
            return "当前正在执行建造目标，先完成或暂停建造再开始身体互动";
        }
        if (companion.isBuilding() && !foregroundPlayerTurn) {
            return "伙伴当前正在执行需要身体控制的前台任务，暂不主动发起互动";
        }
        Session existing = sessions.get(player.getUuid());
        if (existing != null && existing.type == type) {
            return type == Type.HOLD_HAND && existing.phase == Phase.HOLDING
                    ? "已经牵着手" : "该互动已经在进行";
        }
        boolean resumeFollow = existing == null ? companion.isFollowEnabled() : existing.resumeFollow;
        if (existing != null) finish(player, existing, "replaced", false, false);

        // 普通 Agent 前台循环会临时设置 building=true；身体互动开始时明确交回身体控制。
        companion.setBuilding(false);
        companion.setFollowEnabled(false);
        Vec3d forward = Vec3d.fromPolar(0.0F, player.getYaw()).normalize();
        // Vec3d.fromPolar(0, yaw) 给出玩家正前方；右侧必须做逆时针垂线。
        // 旧公式符号相反，把左侧当右侧，导致双方伸出外侧手、视觉上永远牵不上。
        Vec3d right = new Vec3d(-forward.z, 0, forward.x);
        boolean companionOnRight = companion.getPos().subtract(player.getPos()).dotProduct(right) >= 0;
        Session session = new Session(player.getUuid(), type, foregroundPlayerTurn,
                resumeFollow, companionOnRight);
        sessions.put(player.getUuid(), session);
        sync(companion, player, session);

        JsonObject details = sessionDetails(session, "started");
        EpisodicMemory.get().record(player, EpisodicMemory.Type.INTERACTION_STARTED,
                type.name().toLowerCase(), EpisodicMemory.Source.BRAIN, null,
                "success", null, details);
        return switch (type) {
            case HOLD_HAND -> "正在自然走到玩家身侧准备牵手";
            case HUG -> "正在靠近玩家准备拥抱";
            case KISS -> "正在靠近玩家准备亲吻";
            default -> "互动已开始";
        };
    }

    public String releaseHand(ServerPlayerEntity player) {
        Session session = player == null ? null : sessions.get(player.getUuid());
        if (session == null || session.type != Type.HOLD_HAND) return "当前没有正在牵手";
        finish(player, session, "hand_released", true, true);
        return "已经自然松开手";
    }

    /** 玩家要求停止时终止任意互动，不要求模型猜当前是牵手、拥抱还是亲吻。 */
    public String cancelCurrent(ServerPlayerEntity player) {
        Session session = player == null ? null : sessions.get(player.getUuid());
        if (session == null) return "当前没有进行中的身体互动";
        finish(player, session, "player_cancelled", true, true);
        return "当前身体互动已停止";
    }

    /** 每个 server tick 驱动，移动重算内部有节流。 */
    public void tick(MinecraftServer server) {
        if (server == null || sessions.isEmpty()) return;
        for (Session session : new ArrayList<>(sessions.values())) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(session.playerId);
            if (player == null) {
                sessions.remove(session.playerId, session);
                continue;
            }
            CompanionEntity companion = CompanionManager.get(session.playerId);
            if (companion == null || companion.isRemoved() || companion.getWorld() != player.getWorld()) {
                finish(player, session, "partner_unavailable_or_dimension_changed", false, false);
                continue;
            }
            tickSession(player, companion, session);
        }
    }

    private void tickSession(ServerPlayerEntity player, CompanionEntity companion, Session s) {
        s.ticks++;
        s.phaseTicks++;
        double distanceSquared = companion.squaredDistanceTo(player);
        if (s.phase == Phase.APPROACH) {
            // 靠近阶段本来就可能从 4 格外起步，不能复用已接触后的 4 格断开规则。
            // 超出实体寻路/合理互动范围才立即拒绝，其余交给 12 秒 approach_timeout。
            s.farTicks = 0;
            if (distanceSquared > 32.0 * 32.0) {
                finish(player, s, "approach_too_far", false, true);
                return;
            }
        } else {
            if (distanceSquared > 16.0) s.farTicks++; else s.farTicks = 0;
            if (s.farTicks > 30) {
                finish(player, s, "distance_too_far", false, true);
                return;
            }
        }
        if (s.ticks > 240 && s.phase == Phase.APPROACH) {
            finish(player, s, "approach_timeout", false, true);
            return;
        }

        Vec3d forward = Vec3d.fromPolar(0.0F, player.getYaw()).normalize();
        Vec3d right = new Vec3d(-forward.z, 0, forward.x);
        Vec3d target = switch (s.type) {
            case HOLD_HAND -> player.getPos()
                    .add(right.multiply(s.companionUsesLeftArm ? HOLD_SIDE_OFFSET : -HOLD_SIDE_OFFSET));
            case HUG -> player.getPos().add(forward.multiply(HUG_FRONT_OFFSET));
            case KISS -> player.getPos().add(forward.multiply(KISS_FRONT_OFFSET));
            default -> player.getPos();
        };

        if (s.phase == Phase.APPROACH) {
            if (s.ticks % 6 == 1) {
                companion.getNavigation().startMovingTo(target.x, target.y, target.z, 0.92);
            }
            companion.getLookControl().lookAt(player, 30.0F, 30.0F);
            double threshold = ARRIVAL_TOLERANCE;
            if (companion.getPos().squaredDistanceTo(target) <= threshold * threshold) {
                companion.getNavigation().stop();
                changePhase(companion, player, s,
                        s.type == Type.HOLD_HAND ? Phase.HOLDING : Phase.ALIGN);
            }
            return;
        }

        if (s.phase == Phase.HOLDING) {
            if (s.phaseTicks >= HOLDING_DURATION_TICKS) {
                finish(player, s, "completed", true, true);
                return;
            }
            if (s.ticks % 4 == 1 && companion.getPos().squaredDistanceTo(target)
                    > HOLD_REPOSITION_TOLERANCE * HOLD_REPOSITION_TOLERANCE) {
                companion.getNavigation().startMovingTo(target.x, target.y, target.z, 0.88);
            }
            // 牵手采用并排同向姿态，不再让沙包转身面对玩家。
            companion.setYaw(player.getYaw());
            companion.setHeadYaw(player.getYaw());
            companion.setBodyYaw(player.getYaw());
            return;
        }

        companion.getNavigation().stop();
        companion.getLookControl().lookAt(player, 35.0F, 35.0F);
        if (s.phase == Phase.ALIGN && s.phaseTicks >= 10) {
            changePhase(companion, player, s, Phase.PERFORM);
            RelationshipManager.get().markInteraction(player, s.type.name(), System.currentTimeMillis());
            if (ModConfig.get().affectionParticles) {
                player.getServerWorld().spawnParticles(ParticleTypes.HEART,
                        (player.getX() + companion.getX()) * 0.5,
                        Math.max(player.getEyeY(), companion.getEyeY()) + 0.15,
                        (player.getZ() + companion.getZ()) * 0.5,
                        2, 0.12, 0.08, 0.12, 0.01);
            }
        } else if (s.phase == Phase.PERFORM
                && s.phaseTicks >= (s.type == Type.HUG ? 36 : 26)) {
            changePhase(companion, player, s, Phase.RECOVER);
        } else if (s.phase == Phase.RECOVER && s.phaseTicks >= 10) {
            finish(player, s, "completed", true, true);
        }
    }

    private void changePhase(CompanionEntity companion, ServerPlayerEntity player, Session session, Phase phase) {
        session.phase = phase;
        session.phaseTicks = 0;
        sync(companion, player, session);
        if (phase == Phase.HOLDING) {
            RelationshipManager.get().markInteraction(player, session.type.name(), System.currentTimeMillis());
        }
    }

    public void cancel(UUID playerId, String reason, boolean restoreFollow) {
        Session session = sessions.get(playerId);
        if (session == null) return;
        MinecraftServer server = CompanionManager.get(playerId) == null
                ? null : CompanionManager.get(playerId).getServer();
        ServerPlayerEntity player = server == null ? null : server.getPlayerManager().getPlayer(playerId);
        finish(player, session, reason, false, restoreFollow);
    }

    public void clearAll() {
        for (UUID player : new ArrayList<>(sessions.keySet())) cancel(player, "server_stopped", false);
        sessions.clear();
    }

    public JsonObject context(UUID playerId) {
        Session s = sessions.get(playerId);
        JsonObject out = new JsonObject();
        if (s == null) {
            out.addProperty("type", Type.NONE.name());
            return out;
        }
        out.addProperty("session_id", s.id.toString());
        out.addProperty("type", s.type.name());
        out.addProperty("phase", s.phase.name());
        out.addProperty("duration_seconds", (System.currentTimeMillis() - s.startedAt) / 1000L);
        return out;
    }

    private void finish(ServerPlayerEntity player, Session session, String reason,
                        boolean completed, boolean restoreFollow) {
        if (!sessions.remove(session.playerId, session)) return;
        CompanionEntity companion = CompanionManager.get(session.playerId);
        if (companion != null && !companion.isRemoved()) {
            companion.getNavigation().stop();
            companion.clearInteractionState();
            if (restoreFollow && session.resumeFollow) {
                companion.setFollowEnabled(true);
            }
        }
        if (player != null) {
            JsonObject details = sessionDetails(session, reason);
            details.addProperty("duration_seconds", (System.currentTimeMillis() - session.startedAt) / 1000L);
            EpisodicMemory.Type memoryType = completed
                    ? EpisodicMemory.Type.INTERACTION_COMPLETED
                    : "released".equals(reason) ? EpisodicMemory.Type.INTERACTION_CANCELLED
                    : EpisodicMemory.Type.INTERACTION_FAILED;
            EpisodicMemory.get().record(player, memoryType, session.type.name().toLowerCase(),
                    EpisodicMemory.Source.RUNTIME, null, completed ? "success" : "ended",
                    reason, details);
        }
    }

    private static void sync(CompanionEntity companion, ServerPlayerEntity player, Session s) {
        companion.setInteractionState((byte) s.type.ordinal(), (byte) s.phase.ordinal(),
                player.getId(), s.companionUsesLeftArm, s.startedAt);
    }

    private static JsonObject sessionDetails(Session session, String reason) {
        JsonObject details = new JsonObject();
        details.addProperty("session_id", session.id.toString());
        details.addProperty("interaction", session.type.name());
        details.addProperty("phase", session.phase.name());
        details.addProperty("initiated_by", session.initiatedByPlayer ? "player" : "brain");
        details.addProperty("reason", reason);
        return details;
    }
}
