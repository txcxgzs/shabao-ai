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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家与沙包之间的长期关系状态。
 *
 * <p>关系与 Goal/Skill 正交：切换到建造、陪挖矿或探索不会改变关系。关系快照单独
 * 持久化；EpisodicMemory 只记录关系变化经历，不能作为当前状态的唯一事实源。</p>
 */
public final class RelationshipManager {
    public enum Status { COMPANION, COURTING, PARTNER }
    public enum InteractionPolicy { OFF, PLAYER_ONLY, ASK_FIRST, AUTONOMOUS }
    public enum InitiativeLevel { LOW, NORMAL, HIGH }

    /** 至少三个不同的玩家前台回合表达后，才允许正式成为伴侣。 */
    public static final int REQUIRED_COURTSHIP_TURNS = 3;

    private static final RelationshipManager INSTANCE = new RelationshipManager();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Map<UUID, CachedProfile> profiles = new ConcurrentHashMap<>();

    private RelationshipManager() {}

    public static RelationshipManager get() {
        return INSTANCE;
    }

    /** 可持久化的玩家关系资料。字段保留给 Gson 反射。 */
    public static final class Profile {
        public int version = 1;
        public Status status = Status.COMPANION;
        public String playerNickname = "";
        public String companionNickname = "";
        public InteractionPolicy interactionPolicy = InteractionPolicy.PLAYER_ONLY;
        public InitiativeLevel initiative = InitiativeLevel.NORMAL;
        public int courtshipTurns = 0;
        public String lastCourtshipTurnId = "";
        public long courtingSince = 0L;
        public long partnerSince = 0L;
        public long lastHandHoldAt = 0L;
        public long lastHugAt = 0L;
        public long lastKissAt = 0L;

        private void normalize() {
            if (status == null) status = Status.COMPANION;
            if (interactionPolicy == null) interactionPolicy = InteractionPolicy.PLAYER_ONLY;
            if (initiative == null) initiative = InitiativeLevel.NORMAL;
            if (playerNickname == null) playerNickname = "";
            if (companionNickname == null) companionNickname = "";
            if (lastCourtshipTurnId == null) lastCourtshipTurnId = "";
            courtshipTurns = Math.max(0, Math.min(REQUIRED_COURTSHIP_TURNS, courtshipTurns));
        }

        public JsonObject toContextJson() {
            JsonObject out = new JsonObject();
            out.addProperty("status", status.name());
            out.addProperty("interaction_policy", interactionPolicy.name());
            out.addProperty("initiative", initiative.name());
            out.addProperty("courtship_turns", courtshipTurns);
            out.addProperty("courtship_required", REQUIRED_COURTSHIP_TURNS);
            if (!playerNickname.isBlank()) out.addProperty("player_nickname", playerNickname);
            if (!companionNickname.isBlank()) out.addProperty("companion_nickname", companionNickname);
            if (partnerSince > 0) out.addProperty("partner_since_epoch_ms", partnerSince);
            return out;
        }
    }

    public synchronized Profile profile(ServerPlayerEntity player) {
        if (player == null || player.getServer() == null) return new Profile();
        Path file = profilePath(player);
        CachedProfile cached = profiles.get(player.getUuid());
        if (cached != null && cached.file.equals(file)) return cached.profile;
        Profile loaded = load(file);
        profiles.put(player.getUuid(), new CachedProfile(file, loaded));
        return loaded;
    }

    /**
     * 记录一个独立的玩家追求回合。同一前台回合内重复调用只计一次，后台 Brain 永远不能计数。
     */
    public synchronized String recordCourtship(ServerPlayerEntity player, String foregroundTurnId) {
        if (player == null || player.getServer() == null) return "关系状态不可用";
        if (foregroundTurnId == null || foregroundTurnId.isBlank()) {
            return "只有玩家当前消息触发的前台回合才能推进关系";
        }
        Profile p = profile(player);
        if (p.status == Status.PARTNER) return "你们已经是伴侣";
        if (foregroundTurnId.equals(p.lastCourtshipTurnId)) {
            return progressText(p, "本回合已经记录过，不会重复累计");
        }
        long now = System.currentTimeMillis();
        if (p.status == Status.COMPANION) {
            p.status = Status.COURTING;
            p.courtingSince = now;
        }
        p.lastCourtshipTurnId = foregroundTurnId;
        p.courtshipTurns = Math.min(REQUIRED_COURTSHIP_TURNS, p.courtshipTurns + 1);
        save(player, p);
        return progressText(p, p.courtshipTurns >= REQUIRED_COURTSHIP_TURNS
                ? "已经达到最低了解门槛；仍应结合当前相处和表达，由你决定是否接受"
                : "关系仍在了解阶段，不能立即接受");
    }

    /** 达到多回合门槛后，由前台 Brain 明确调用；后台事件不能改变关系。 */
    public synchronized String acceptPartner(ServerPlayerEntity player, String foregroundTurnId) {
        if (player == null || player.getServer() == null) return "关系状态不可用";
        if (foregroundTurnId == null || foregroundTurnId.isBlank()) {
            return "后台思考不能自行建立伴侣关系";
        }
        Profile p = profile(player);
        if (p.status == Status.PARTNER) return "你们已经是伴侣";
        if (!foregroundTurnId.equals(p.lastCourtshipTurnId)) {
            return "当前玩家回合尚未通过 pursue_partner 记录新的明确追求，不能接受";
        }
        if (p.courtshipTurns < REQUIRED_COURTSHIP_TURNS) {
            return progressText(p, "了解还不够，拒绝建立伴侣关系");
        }
        p.status = Status.PARTNER;
        p.partnerSince = System.currentTimeMillis();
        save(player, p);
        return "关系已更新为 PARTNER";
    }

    public synchronized String endPartner(ServerPlayerEntity player, String foregroundTurnId) {
        if (player == null || player.getServer() == null) return "关系状态不可用";
        if (foregroundTurnId == null || foregroundTurnId.isBlank()) {
            return "后台思考不能自行解除伴侣关系";
        }
        Profile p = profile(player);
        p.status = Status.COMPANION;
        p.courtshipTurns = 0;
        p.lastCourtshipTurnId = "";
        p.courtingSince = 0L;
        p.partnerSince = 0L;
        save(player, p);
        return "关系已恢复为普通伙伴";
    }

    public synchronized String updateNames(ServerPlayerEntity player, String playerNickname,
                                           String companionNickname, String foregroundTurnId) {
        if (foregroundTurnId == null || foregroundTurnId.isBlank()) return "后台思考不能擅自修改称呼";
        Profile p = profile(player);
        if (playerNickname != null) p.playerNickname = cleanNickname(playerNickname);
        if (companionNickname != null) p.companionNickname = cleanNickname(companionNickname);
        save(player, p);
        return "称呼偏好已更新";
    }

    public synchronized String updateInteractionSettings(ServerPlayerEntity player, String policy,
                                                         String initiative, String foregroundTurnId) {
        if (foregroundTurnId == null || foregroundTurnId.isBlank()) return "后台思考不能擅自修改互动权限";
        Profile p = profile(player);
        try {
            if (policy != null && !policy.isBlank()) {
                p.interactionPolicy = InteractionPolicy.valueOf(policy.trim().toUpperCase());
            }
            if (initiative != null && !initiative.isBlank()) {
                p.initiative = InitiativeLevel.valueOf(initiative.trim().toUpperCase());
            }
        } catch (IllegalArgumentException e) {
            return "互动设置无效；policy=OFF/PLAYER_ONLY/ASK_FIRST/AUTONOMOUS，initiative=LOW/NORMAL/HIGH";
        }
        save(player, p);
        return "互动权限已更新为 " + p.interactionPolicy + "，主动程度 " + p.initiative;
    }

    public synchronized void markInteraction(ServerPlayerEntity player, String interaction, long at) {
        Profile p = profile(player);
        switch (interaction == null ? "" : interaction) {
            case "HOLD_HAND" -> p.lastHandHoldAt = at;
            case "HUG" -> p.lastHugAt = at;
            case "KISS" -> p.lastKissAt = at;
            default -> { return; }
        }
        save(player, p);
    }

    public synchronized void clearCache() {
        profiles.clear();
    }

    private static String progressText(Profile p, String prefix) {
        return prefix + "（独立表达 " + p.courtshipTurns + "/" + REQUIRED_COURTSHIP_TURNS + "）";
    }

    private static String cleanNickname(String value) {
        String cleaned = value == null ? "" : value.strip().replace('\n', ' ').replace('\r', ' ');
        return cleaned.length() <= 32 ? cleaned : cleaned.substring(0, 32);
    }

    private Path profilePath(ServerPlayerEntity player) {
        return player.getServer().getSavePath(WorldSavePath.ROOT)
                .resolve("shabao-ai-memory").resolve("relationships")
                .resolve(player.getUuidAsString() + ".json");
    }

    private static Profile load(Path file) {
        try {
            if (Files.exists(file)) {
                Profile p = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Profile.class);
                if (p != null) {
                    p.normalize();
                    return p;
                }
            }
        } catch (Exception e) {
            AgentLogger.logError(0, "关系状态读取失败，使用默认值: " + e);
        }
        return new Profile();
    }

    private void save(ServerPlayerEntity player, Profile profile) {
        Path file = profilePath(player);
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(profile), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            profiles.put(player.getUuid(), new CachedProfile(file, profile));
        } catch (IOException e) {
            AgentLogger.logError(0, "关系状态保存失败: " + e);
        }
    }

    private record CachedProfile(Path file, Profile profile) {}
}
