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

import cn.shabaoai.companion.config.ModConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 可检索情景记忆：把对话、目标、技能、本地动作、世界事件与 Brain 决策统一写入世界存档。
 *
 * <p>原始事件使用 JSONL 追加保存；结束的 Episode 另存为 JSONL 索引。LLM 每次调用只注入经过
 * 聚合的最近上下文，需要更早或更细的内容时通过 recall 工具查询。记录事件本身绝不会唤醒 LLM，
 * 是否唤醒仍由 AgentRuntime/EventBus 决定。</p>
 */
public final class EpisodicMemory {
    public enum Type {
        USER_MESSAGE, AI_REPLY,
        GOAL_SET, GOAL_CHANGED, GOAL_CANCELLED, GOAL_COMPLETED, GOAL_FAILED,
        SKILL_STARTED, SKILL_STOPPED,
        ACTION, ACTION_FAILED,
        WORLD_EVENT, DANGER, PLAYER_HURT, OBSERVATION,
        WAIT_STARTED, WAIT_ENDED, DIMENSION_CHANGED,
        COGNITION_DECISION,
        COURTSHIP_PROGRESS, RELATIONSHIP_CHANGED,
        INTERACTION_STARTED, INTERACTION_COMPLETED, INTERACTION_CANCELLED, INTERACTION_FAILED
    }

    public enum Source { PLAYER, BRAIN, LOCAL_SKILL, RUNTIME, WORLD }

    private static final EpisodicMemory INSTANCE = new EpisodicMemory();
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final AtomicLong IDS = new AtomicLong();
    private static final ExecutorService READS = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "shabao-ai-memory-reader"); t.setDaemon(true); return t;
    });
    private static final ExecutorService LOADS = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "shabao-ai-memory-loader"); t.setDaemon(true); return t;
    });
    private static final ExecutorService WRITES = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "shabao-ai-memory-writer"); t.setDaemon(true); return t;
    });
    private static final ConcurrentLinkedQueue<WriteRequest> WRITE_QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean WRITE_SCHEDULED = new AtomicBoolean(false);
    private static final long RECENT_WINDOW_MS = 3 * 60_000L;
    private static final long EPISODE_IDLE_MS = 15 * 60_000L;
    private static final int MAX_LOADED_EVENTS = 100_000;
    private static final int MAX_RECENT_EVENTS = 256;
    private static final int MAX_RECALL_LIMIT = 100;

    private final Map<UUID, PlayerMemory> players = new ConcurrentHashMap<>();

    private EpisodicMemory() {}

    public static EpisodicMemory get() {
        return INSTANCE;
    }

    /** 统一事件写入入口。reason/details 可空；不会触发 Brain。 */
    public void record(ServerPlayerEntity player, Type type, String action, Source source,
                       JsonObject reason, String result, String text, JsonObject details) {
        if (player == null || player.getServer() == null || type == null) return;
        try {
            PlayerMemory memory = memory(player);
            synchronized (memory) {
                long now = System.currentTimeMillis();
                String dimension = player.getServerWorld().getRegistryKey().getValue().toString();
                String goal = currentGoal(player.getUuid());
                maybeRotateEpisode(memory, player, type, goal, dimension, now);
                if (memory.current == null) memory.current = newEpisode(player, goal, dimension, now);

                JsonObject event = new JsonObject();
                event.addProperty("id", eventId());
                event.addProperty("time", Instant.ofEpochMilli(now).atZone(ZONE).toOffsetDateTime().toString());
                event.addProperty("epoch_ms", now);
                event.addProperty("game_tick", player.getServerWorld().getTime());
                event.addProperty("type", type.name());
                if (!blank(action)) event.addProperty("action", action);
                event.addProperty("source", source == null ? Source.RUNTIME.name() : source.name());
                if (!blank(goal)) event.addProperty("goal", goal);
                event.addProperty("actor", source == Source.PLAYER ? "player" : "shabao");
                event.addProperty("player", player.getName().getString());
                event.addProperty("player_uuid", player.getUuidAsString());
                event.addProperty("dimension", dimension);
                BlockPos pos = player.getBlockPos();
                JsonArray position = new JsonArray();
                position.add(pos.getX()); position.add(pos.getY()); position.add(pos.getZ());
                event.add("position", position);
                if (reason != null && !reason.isEmpty()) event.add("reason", reason.deepCopy());
                if (!blank(result)) event.addProperty("result", result);
                if (!blank(text)) event.addProperty("text", text);
                if (details != null && !details.isEmpty()) event.add("details", details.deepCopy());
                event.addProperty("episode_id", memory.current.id);

                memory.events.add(event);
                memory.recent.addLast(event);
                while (memory.recent.size() > MAX_RECENT_EVENTS) memory.recent.removeFirst();
                if (memory.events.size() > MAX_LOADED_EVENTS) memory.events.remove(0);
                memory.current.accept(event);
                appendAsync(memory.eventsFile, event);
                if (type == Type.GOAL_CANCELLED || type == Type.GOAL_COMPLETED || type == Type.GOAL_FAILED) {
                    closeEpisode(memory, "目标结束", now);
                }
            }
        } catch (Exception e) {
            AgentLogger.logError(0, "EpisodicMemory 写入失败: " + e);
        }
    }

    public void recordMessage(ServerPlayerEntity player, Type type, String text, Source source) {
        record(player, type, type.name().toLowerCase(Locale.ROOT), source, null, "success", text, null);
    }

    /**
     * 每次 LLM 调用自动附带的短窗口。重复本地动作会聚合，但原始事件仍完整保存在 JSONL。
     */
    public String recentContext(ServerPlayerEntity player, AgentRuntime runtime) {
        if (player == null || player.getServer() == null) return "";
        PlayerMemory memory;
        try {
            memory = memory(player);
        } catch (Exception e) {
            return "";
        }
        long now = System.currentTimeMillis();
        List<JsonObject> recent = new ArrayList<>();
        synchronized (memory) {
            for (JsonObject event : memory.recent) {
                if (epoch(event) >= now - RECENT_WINDOW_MS) recent.add(event);
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("[当前状态]\n");
        GoalManager.Goal goal = runtime == null ? null : runtime.goals().get(player.getUuid());
        if (goal == null) {
            out.append("当前Goal=无\n");
        } else {
            out.append("当前Goal=").append(goal.type).append(" status=").append(goal.status);
            if (goal.cognition != null) out.append(" cognition=").append(goal.cognition.mode());
            out.append('\n');
            if (goal.status == GoalManager.Status.RUNNING || goal.status == GoalManager.Status.WAITING) {
                out.append("[未完成事项] ").append(goal.type).append(" 仍为 ").append(goal.status).append('\n');
            }
        }
        out.append("位置=").append(player.getBlockX()).append(',').append(player.getBlockY()).append(',')
                .append(player.getBlockZ()).append(" 维度=")
                .append(player.getServerWorld().getRegistryKey().getValue()).append('\n');
        RelationshipManager.Profile relationship = RelationshipManager.get().profile(player);
        out.append("[关系状态] status=").append(relationship.status)
                .append(" policy=").append(relationship.interactionPolicy)
                .append(" initiative=").append(relationship.initiative);
        if (relationship.status == RelationshipManager.Status.COURTING) {
            out.append(" progress=").append(relationship.courtshipTurns).append('/')
                    .append(RelationshipManager.REQUIRED_COURTSHIP_TURNS);
        }
        if (!relationship.playerNickname.isBlank()) {
            out.append(" player_nickname=").append(relationship.playerNickname);
        }
        if (!relationship.companionNickname.isBlank()) {
            out.append(" companion_nickname=").append(relationship.companionNickname);
        }
        out.append('\n');
        JsonObject interaction = InteractionManager.get().context(player.getUuid());
        out.append("[当前互动] type=").append(str(interaction, "type"));
        if (interaction.has("phase")) out.append(" phase=").append(str(interaction, "phase"));
        if (interaction.has("duration_seconds")) {
            out.append(" duration_seconds=").append(interaction.get("duration_seconds").getAsLong());
        }
        out.append('\n');

        if (recent.isEmpty()) {
            out.append("[最近经历]\n暂无已记录的重要经历");
            return out.toString();
        }
        out.append("[最近经历]\n");
        Map<String, Aggregate> repeated = new LinkedHashMap<>();
        List<JsonObject> important = new ArrayList<>();
        for (JsonObject event : recent) {
            String type = str(event, "type");
            String action = str(event, "action");
            if ("ACTION".equals(type) && !blank(action)) {
                repeated.computeIfAbsent(action, k -> new Aggregate()).add(event);
            } else {
                important.add(event);
            }
        }
        int start = Math.max(0, important.size() - 8);
        for (int i = start; i < important.size(); i++) {
            out.append("- ").append(relative(now, epoch(important.get(i))))
                    .append("：").append(eventLine(important.get(i), false)).append('\n');
        }
        for (Map.Entry<String, Aggregate> entry : repeated.entrySet()) {
            Aggregate a = entry.getValue();
            out.append("- 过去3分钟：").append(actionLabel(entry.getKey())).append(a.count).append("次");
            JsonArray pos = a.last.getAsJsonArray("position");
            if (pos != null && pos.size() >= 3) {
                out.append("，最近一次在 (").append(pos.get(0).getAsInt()).append(',')
                        .append(pos.get(1).getAsInt()).append(',').append(pos.get(2).getAsInt()).append(')');
            }
            out.append('\n');
        }
        return out.toString().trim();
    }

    /** recall 工具执行入口。 */
    public String recall(ServerPlayerEntity player, JsonObject args) {
        if (player == null || player.getServer() == null) return "[记忆检索失败] 玩家已离线";
        PlayerMemory memory;
        try {
            memory = memory(player);
            memory.loaded.join();
        } catch (Exception e) {
            return "[记忆检索失败] " + e.getMessage();
        }
        JsonObject safe = args == null ? new JsonObject() : args;
        TimeRange range = parseRange(safe.has("time") && safe.get("time").isJsonObject()
                ? safe.getAsJsonObject("time") : null);
        String query = string(safe, "query", "");
        String detail = string(safe, "detail", "normal").toLowerCase(Locale.ROOT);
        int limit = clamp(intValue(safe, "limit", 20), 1, MAX_RECALL_LIMIT);
        Set<String> types = stringSet(safe.get("types"), true);
        Set<String> memoryIds = stringSet(safe.get("memory_ids"), false);

        List<JsonObject> episodes;
        synchronized (memory) {
            episodes = new ArrayList<>(memory.episodes);
            if (memory.current != null) episodes.add(memory.current.toJson(false));
        }
        // 先建立写入屏障，再流式扫描完整 JSONL；检索规模不再受 100k 内存窗口限制，
        // 也不会为了查一次旧经历把整份长期记忆同时装进堆。
        flushWrites();
        boolean full = "full".equals(detail) || !memoryIds.isEmpty();
        Map<String, ScoredEvent> bestByEpisode = new HashMap<>();
        PriorityQueue<ScoredEvent> latest = new PriorityQueue<>(
                Comparator.comparingLong(s -> epoch(s.event)));
        forEachJsonLine(memory.eventsFile, event -> {
            long time = epoch(event);
            if (time < range.from || time > range.to) return;
            if (!memoryIds.isEmpty() && !memoryIds.contains(str(event, "episode_id"))) return;
            if (!types.isEmpty() && !types.contains(str(event, "type").toUpperCase(Locale.ROOT))) return;
            int score = semanticScore(event, query);
            if (!blank(query) && score <= 0) return;
            ScoredEvent candidate = new ScoredEvent(event, score);
            if (full) {
                latest.offer(candidate);
                if (latest.size() > limit) latest.poll();
            } else {
                String episodeId = str(event, "episode_id");
                bestByEpisode.merge(episodeId, candidate, (old, next) ->
                        next.score > old.score || (next.score == old.score
                                && epoch(next.event) > epoch(old.event)) ? next : old);
            }
        });

        List<ScoredEvent> matched = full ? new ArrayList<>(latest) : new ArrayList<>(bestByEpisode.values());
        matched.sort(full
                ? Comparator.comparingLong(s -> epoch(s.event))
                : Comparator.<ScoredEvent>comparingInt(s -> s.score).reversed()
                    .thenComparing(Comparator.comparingLong((ScoredEvent s) -> epoch(s.event)).reversed()));

        if (matched.isEmpty()) return "[记忆检索] 没有找到符合条件的经历。";
        if (full) {
            StringBuilder out = new StringBuilder("[原始经历明细]\n");
            for (int i = 0; i < matched.size(); i++) {
                JsonObject e = matched.get(i).event;
                out.append(formatTime(epoch(e))).append(" [").append(str(e, "type")).append("] ")
                        .append(eventLine(e, true)).append(" (episode=")
                        .append(str(e, "episode_id")).append(")\n");
            }
            return out.toString().trim();
        }

        LinkedHashSet<String> episodeIds = new LinkedHashSet<>();
        for (ScoredEvent s : matched) episodeIds.add(str(s.event, "episode_id"));
        Map<String, JsonObject> byId = new HashMap<>();
        for (JsonObject episode : episodes) byId.put(str(episode, "id"), episode);
        StringBuilder out = new StringBuilder("[找到的相关情景]\n");
        int count = 0;
        for (String id : episodeIds) {
            if (count++ >= limit) break;
            JsonObject ep = byId.get(id);
            if (ep == null) {
                out.append(id).append("：相关原始事件可用 detail=full 查询\n");
                continue;
            }
            out.append(id).append(" ").append(formatTime(longValue(ep, "started_at", 0)))
                    .append("~").append(formatTime(longValue(ep, "ended_at", System.currentTimeMillis())))
                    .append(" ").append(string(ep, "title", "一段共同经历")).append('\n')
                    .append("  ").append(string(ep, "summary", "暂无摘要")).append('\n');
        }
        out.append("需要细节时再次 recall，传 memory_ids 和 detail=full。");
        return out.toString().trim();
    }

    public CompletableFuture<String> recallAsync(ServerPlayerEntity player, JsonObject args) {
        JsonObject safeArgs = args == null ? new JsonObject() : args.deepCopy();
        return CompletableFuture.supplyAsync(() -> recall(player, safeArgs), READS);
    }

    /** 每秒由 Runtime 调用，关闭长时间无活动的 Episode。 */
    public void tick(MinecraftServer server) {
        if (server == null) return;
        long now = System.currentTimeMillis();
        for (PlayerMemory memory : players.values()) {
            synchronized (memory) {
                if (memory.current != null && now - memory.current.lastAt >= EPISODE_IDLE_MS) {
                    closeEpisode(memory, "长时间无活动", now);
                }
            }
        }
    }

    public void closePlayer(UUID player, String reason) {
        PlayerMemory memory = players.get(player);
        if (memory == null) return;
        synchronized (memory) {
            closeEpisode(memory, blank(reason) ? "玩家退出" : reason, System.currentTimeMillis());
        }
    }

    public void closeAll(String reason) {
        for (UUID uuid : new ArrayList<>(players.keySet())) closePlayer(uuid, reason);
        flushWrites();
        players.clear();
    }

    private PlayerMemory memory(ServerPlayerEntity player) throws IOException {
        Path root = player.getServer().getSavePath(WorldSavePath.ROOT).resolve("shabao-ai-memory")
                .resolve("episodic");
        synchronized (players) {
            PlayerMemory existing = players.get(player.getUuid());
            if (existing != null && existing.root.equals(root)) return existing;
            Files.createDirectories(root);
            PlayerMemory created = new PlayerMemory(root, player.getUuid());
            created.loaded = CompletableFuture.runAsync(() -> loadInto(created), LOADS);
            players.put(player.getUuid(), created);
            return created;
        }
    }

    private void loadInto(PlayerMemory memory) {
        List<JsonObject> oldEvents = new ArrayList<>();
        List<JsonObject> oldEpisodes = new ArrayList<>();
        try {
            loadJsonLines(memory.eventsFile, oldEvents, MAX_LOADED_EVENTS);
            loadJsonLines(memory.episodesFile, oldEpisodes, 10_000);
            synchronized (memory) {
                Set<String> currentIds = new HashSet<>();
                for (JsonObject event : memory.events) currentIds.add(str(event, "id"));
                oldEvents.removeIf(event -> currentIds.contains(str(event, "id")));
                memory.events.addAll(0, oldEvents);
                memory.episodes.addAll(0, oldEpisodes);
                memory.recent.clear();
                int from = Math.max(0, memory.events.size() - MAX_RECENT_EVENTS);
                for (int i = from; i < memory.events.size(); i++) memory.recent.addLast(memory.events.get(i));
            }
        } catch (Exception e) {
            AgentLogger.logError(0, "EpisodicMemory 异步加载失败: " + e);
        }
    }

    private static void loadJsonLines(Path file, List<JsonObject> target, int max) throws IOException {
        if (!Files.exists(file)) return;
        Deque<JsonObject> tail = new ArrayDeque<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonElement parsed = JsonParser.parseString(line);
                    if (!parsed.isJsonObject()) continue;
                    tail.addLast(parsed.getAsJsonObject());
                    if (tail.size() > max) tail.removeFirst();
                } catch (Exception ignored) {
                    // 单条损坏不应让整份长期记忆不可用。
                }
            }
        }
        target.addAll(tail);
    }

    private void maybeRotateEpisode(PlayerMemory memory, ServerPlayerEntity player, Type type,
                                    String goal, String dimension, long now) {
        Episode current = memory.current;
        if (current == null) return;
        boolean idle = now - current.lastAt >= EPISODE_IDLE_MS;
        boolean dimensionChanged = !dimension.equals(current.dimension);
        boolean goalBoundary = type == Type.GOAL_SET || type == Type.GOAL_CHANGED;
        if (idle || dimensionChanged || goalBoundary) {
            String reason = idle ? "长时间无活动" : dimensionChanged ? "维度切换" : "目标改变";
            closeEpisode(memory, reason, now);
        }
    }

    private Episode newEpisode(ServerPlayerEntity player, String goal, String dimension, long now) {
        String id = "memory_" + Long.toString(now, 36) + "_" + IDS.incrementAndGet();
        return new Episode(id, now, goal, dimension, !player.getServerWorld().isSkyVisible(player.getBlockPos()));
    }

    private void closeEpisode(PlayerMemory memory, String reason, long now) {
        if (memory.current == null || memory.current.count == 0) {
            memory.current = null;
            return;
        }
        memory.current.endedAt = now;
        memory.current.endReason = reason;
        JsonObject json = memory.current.toJson(true);
        memory.episodes.add(json);
        try {
            appendAsync(memory.episodesFile, json);
        } catch (Exception e) {
            AgentLogger.logError(0, "Episode 持久化排队失败: " + e);
        }
        memory.current = null;
    }

    private static void appendAsync(Path file, JsonObject object) {
        WRITE_QUEUE.add(new WriteRequest(file, GSON.toJson(object.deepCopy())));
        scheduleWriteDrain();
    }

    private static void scheduleWriteDrain() {
        if (WRITE_SCHEDULED.compareAndSet(false, true)) WRITES.execute(EpisodicMemory::drainWrites);
    }

    private static void drainWrites() {
        try {
            Map<Path, StringBuilder> batches = new LinkedHashMap<>();
            WriteRequest request;
            while ((request = WRITE_QUEUE.poll()) != null) {
                batches.computeIfAbsent(request.file, ignored -> new StringBuilder())
                        .append(request.line).append(System.lineSeparator());
            }
            for (Map.Entry<Path, StringBuilder> batch : batches.entrySet()) {
                try {
                    Files.createDirectories(batch.getKey().getParent());
                    Files.writeString(batch.getKey(), batch.getValue(), StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                } catch (Exception e) {
                    AgentLogger.logError(0, "EpisodicMemory 异步批量写入失败: " + e);
                }
            }
        } finally {
            WRITE_SCHEDULED.set(false);
            if (!WRITE_QUEUE.isEmpty()) scheduleWriteDrain();
        }
    }

    private static void flushWrites() {
        scheduleWriteDrain();
        try { WRITES.submit(() -> {}).get(10, TimeUnit.SECONDS); }
        catch (Exception e) { AgentLogger.logError(0, "EpisodicMemory 刷盘超时: " + e); }
    }

    private static void forEachJsonLine(Path file, Consumer<JsonObject> consumer) {
        if (!Files.exists(file)) return;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    JsonElement parsed = JsonParser.parseString(line);
                    if (parsed.isJsonObject()) consumer.accept(parsed.getAsJsonObject());
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            AgentLogger.logError(0, "EpisodicMemory 全量检索读取失败: " + e);
        }
    }

    private static String currentGoal(UUID player) {
        GoalManager.Goal g = AgentRuntime.get().goals().get(player);
        return g == null ? null : g.type;
    }

    private static String eventId() {
        return "evt_" + Long.toString(System.currentTimeMillis(), 36) + "_" + IDS.incrementAndGet();
    }

    private static TimeRange parseRange(JsonObject time) {
        long now = System.currentTimeMillis();
        if (time == null) return new TimeRange(0, now);
        if (time.has("last")) {
            long duration = durationMs(string(time, "last", ""), 5 * 60_000L);
            return new TimeRange(Math.max(0, now - duration), now);
        }
        if (time.has("around")) {
            long center = instantValue(string(time, "around", ""), now);
            long window = durationMs(string(time, "window", "5m"), 5 * 60_000L);
            return new TimeRange(Math.max(0, center - window / 2), center + window / 2);
        }
        long from = time.has("from") ? instantValue(string(time, "from", ""), now) : 0;
        long to = time.has("to") ? instantValue(string(time, "to", ""), now) : now;
        return new TimeRange(Math.min(from, to), Math.max(from, to));
    }

    private static long instantValue(String value, long now) {
        if (blank(value) || "now".equalsIgnoreCase(value)) return now;
        if ("today_start".equalsIgnoreCase(value)) {
            return LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant().toEpochMilli();
        }
        if (value.startsWith("-") || value.startsWith("+")) {
            long delta = durationMs(value.substring(1), 0);
            return value.startsWith("-") ? now - delta : now + delta;
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            try {
                return ZonedDateTime.parse(value).toInstant().toEpochMilli();
            } catch (DateTimeParseException ignoredAgain) {
                try {
                    return LocalDateTime.parse(value).atZone(ZONE).toInstant().toEpochMilli();
                } catch (DateTimeParseException ignoredLocal) {
                    try {
                        return LocalDate.parse(value).atStartOfDay(ZONE).toInstant().toEpochMilli();
                    } catch (DateTimeParseException ignoredDate) {
                        return now;
                    }
                }
            }
        }
    }

    private static long durationMs(String value, long fallback) {
        if (blank(value)) return fallback;
        String s = value.trim().toLowerCase(Locale.ROOT);
        try {
            long multiplier = 1000L;
            if (s.endsWith("ms")) { multiplier = 1; s = s.substring(0, s.length() - 2); }
            else if (s.endsWith("s")) { multiplier = 1000L; s = s.substring(0, s.length() - 1); }
            else if (s.endsWith("m")) { multiplier = 60_000L; s = s.substring(0, s.length() - 1); }
            else if (s.endsWith("h")) { multiplier = 3_600_000L; s = s.substring(0, s.length() - 1); }
            else if (s.endsWith("d")) { multiplier = 86_400_000L; s = s.substring(0, s.length() - 1); }
            return Math.max(0, Long.parseLong(s.trim()) * multiplier);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int semanticScore(JsonObject event, String query) {
        if (blank(query)) return 1;
        String haystack = GSON.toJson(event).toLowerCase(Locale.ROOT);
        String normalized = query.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s]+", "");
        int score = haystack.contains(normalized) ? 20 : 0;
        for (String term : terms(normalized)) {
            if (term.length() >= 2 && haystack.contains(term)) score += Math.min(6, term.length());
        }
        Map<String, List<String>> aliases = Map.of(
                "钻石", List.of("diamond", "钻石"),
                "火把", List.of("torch", "补光", "low_light"),
                "矿洞", List.of("mine_assist", "cave", "underground", "地下", "矿"),
                "建房", List.of("build", "house", "房", "建筑"),
                "下界", List.of("nether", "下界"),
                "受伤", List.of("hurt", "health_drop", "受伤"),
                "怪", List.of("danger", "hostile", "zombie", "creeper", "skeleton", "敌对")
        );
        for (Map.Entry<String, List<String>> alias : aliases.entrySet()) {
            if (!normalized.contains(alias.getKey())) continue;
            for (String word : alias.getValue()) if (haystack.contains(word)) score += 5;
        }
        return score;
    }

    private static Set<String> terms(String value) {
        Set<String> out = new LinkedHashSet<>();
        for (String part : value.split("[^\\p{L}\\p{N}_:.-]+")) {
            if (part.length() >= 2) out.add(part);
            if (containsHan(part) && part.length() > 2) {
                for (int size = 2; size <= Math.min(4, part.length()); size++) {
                    for (int i = 0; i + size <= part.length(); i++) out.add(part.substring(i, i + size));
                }
            }
        }
        return out;
    }

    private static boolean containsHan(String value) {
        for (int i = 0; i < value.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(value.charAt(i));
            if (script == Character.UnicodeScript.HAN) return true;
        }
        return false;
    }

    private static String eventLine(JsonObject event, boolean full) {
        String type = str(event, "type");
        String action = str(event, "action");
        String text = str(event, "text");
        String result = str(event, "result");
        String goal = str(event, "goal");
        String base;
        if (!blank(text)) base = switch (type) {
            case "USER_MESSAGE" -> "玩家说：“" + text + "”";
            case "AI_REPLY" -> ModConfig.normalizeCompanionName(ModConfig.get().companionName)
                    + "说：“" + text + "”";
            default -> text;
        };
        else if (type.startsWith("GOAL_")) base = "目标变化：" + (blank(goal) ? action : goal) + "（" + type + "）";
        else if (type.startsWith("SKILL_")) base = "技能变化：" + action + "（" + type + "）";
        else if (!blank(action)) base = actionLabel(action) + (blank(result) ? "" : "，结果=" + result);
        else base = type;
        if (full && event.has("reason")) base += "，原因=" + event.get("reason");
        if (full && event.has("details")) base += "，详情=" + event.get("details");
        return base;
    }

    private static String actionLabel(String action) {
        if (blank(action)) return "执行动作";
        return switch (action) {
            case "torch_place" -> "自动补光";
            case "follow" -> "继续跟随玩家";
            case "walk" -> "移动";
            case "build_plan", "place" -> "建造";
            default -> action;
        };
    }

    private static String relative(long now, long then) {
        long seconds = Math.max(0, (now - then) / 1000);
        if (seconds < 60) return seconds + "秒前";
        if (seconds < 3600) return (seconds / 60) + "分钟前";
        return (seconds / 3600) + "小时前";
    }

    private static String formatTime(long epoch) {
        if (epoch <= 0) return "未知时间";
        ZonedDateTime z = Instant.ofEpochMilli(epoch).atZone(ZONE);
        return String.format(Locale.ROOT, "%02d-%02d %02d:%02d:%02d",
                z.getMonthValue(), z.getDayOfMonth(), z.getHour(), z.getMinute(), z.getSecond());
    }

    private static Set<String> stringSet(JsonElement element, boolean uppercase) {
        Set<String> out = new HashSet<>();
        if (element == null || !element.isJsonArray()) return out;
        for (JsonElement e : element.getAsJsonArray()) {
            if (e.isJsonPrimitive()) {
                String value = e.getAsString();
                out.add(uppercase ? value.toUpperCase(Locale.ROOT) : value);
            }
        }
        return out;
    }

    private static String string(JsonObject o, String key, String fallback) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return fallback;
        try { return o.get(key).getAsString(); } catch (Exception e) { return fallback; }
    }

    private static String str(JsonObject o, String key) { return string(o, key, ""); }
    private static int intValue(JsonObject o, String key, int fallback) {
        try { return o.has(key) ? o.get(key).getAsInt() : fallback; } catch (Exception e) { return fallback; }
    }
    private static long longValue(JsonObject o, String key, long fallback) {
        try { return o.has(key) ? o.get(key).getAsLong() : fallback; } catch (Exception e) { return fallback; }
    }
    private static long epoch(JsonObject event) { return longValue(event, "epoch_ms", 0); }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private static final class PlayerMemory {
        final Path root;
        final Path eventsFile;
        final Path episodesFile;
        final List<JsonObject> events = new ArrayList<>();
        final List<JsonObject> episodes = new ArrayList<>();
        final Deque<JsonObject> recent = new ArrayDeque<>();
        Episode current;
        CompletableFuture<Void> loaded = CompletableFuture.completedFuture(null);

        PlayerMemory(Path root, UUID player) {
            this.root = root;
            this.eventsFile = root.resolve(player + ".events.jsonl");
            this.episodesFile = root.resolve(player + ".episodes.jsonl");
        }
    }

    private record WriteRequest(Path file, String line) {}

    private static final class Episode {
        final String id;
        final long startedAt;
        long endedAt;
        long lastAt;
        final String goal;
        final String dimension;
        final boolean underground;
        String endReason;
        int count;
        final Map<String, Integer> counters = new LinkedHashMap<>();
        final List<String> important = new ArrayList<>();
        final List<String> eventIds = new ArrayList<>();
        String firstEventId;
        String lastEventId;

        Episode(String id, long startedAt, String goal, String dimension, boolean underground) {
            this.id = id;
            this.startedAt = startedAt;
            this.lastAt = startedAt;
            this.goal = goal;
            this.dimension = dimension;
            this.underground = underground;
        }

        void accept(JsonObject event) {
            count++;
            lastAt = epoch(event);
            String eventId = str(event, "id");
            if (firstEventId == null) firstEventId = eventId;
            lastEventId = eventId;
            eventIds.add(eventId);
            String key = !str(event, "action").isBlank() ? str(event, "action") : str(event, "type");
            counters.merge(key, 1, Integer::sum);
            String type = str(event, "type");
            if (!"ACTION".equals(type) || !"follow".equals(str(event, "action"))) {
                important.add(eventLine(event, false));
                if (important.size() > 12) important.remove(0);
            }
        }

        JsonObject toJson(boolean closed) {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("started_at", startedAt);
            o.addProperty("ended_at", closed ? endedAt : lastAt);
            o.addProperty("title", title());
            if (!blank(goal)) o.addProperty("primary_goal", goal);
            o.addProperty("dimension", dimension);
            o.addProperty("underground", underground);
            o.addProperty("event_count", count);
            if (!blank(endReason)) o.addProperty("end_reason", endReason);
            if (firstEventId != null) o.addProperty("first_event_id", firstEventId);
            if (lastEventId != null) o.addProperty("last_event_id", lastEventId);
            JsonArray ids = new JsonArray();
            eventIds.forEach(ids::add);
            o.add("event_ids", ids);
            JsonObject counts = new JsonObject();
            counters.forEach(counts::addProperty);
            o.add("counts", counts);
            JsonArray items = new JsonArray();
            important.forEach(items::add);
            o.add("important_events", items);
            o.addProperty("summary", summary());
            return o;
        }

        private String title() {
            if ("mine_assist".equals(goal)) return "与玩家探索地下矿洞";
            if ("build".equals(goal)) return "与玩家一起建造";
            if ("explore".equals(goal)) return "与玩家探索世界";
            if ("companion".equals(goal)) return "陪伴玩家的一段经历";
            if ("follow".equals(goal)) return "跟随玩家同行";
            return underground ? "地下的一段共同经历" : "与玩家的一段共同经历";
        }

        private String summary() {
            StringBuilder s = new StringBuilder();
            if (!blank(goal)) s.append("主要目标为 ").append(goal).append("。");
            if (underground) s.append("场景位于地下。");
            int torch = counters.getOrDefault("torch_place", 0);
            if (torch > 0) s.append("自动补光 ").append(torch).append(" 次。");
            int hurt = counters.getOrDefault("PLAYER_HURT", 0);
            if (hurt > 0) s.append("玩家受伤 ").append(hurt).append(" 次。");
            int danger = counters.getOrDefault("DANGER", 0);
            if (danger > 0) s.append("遇到危险 ").append(danger).append(" 次。");
            int builds = counters.getOrDefault("build_plan", 0) + counters.getOrDefault("place", 0);
            if (builds > 0) s.append("进行了 ").append(builds).append(" 次建造动作。");
            if (!important.isEmpty()) s.append("关键经历：").append(String.join("；", important.subList(Math.max(0, important.size() - 4), important.size()))).append('。');
            return s.length() == 0 ? "记录了 " + count + " 条共同经历。" : s.toString();
        }
    }

    private static final class Aggregate {
        int count;
        JsonObject last;
        void add(JsonObject event) { count++; last = event; }
    }

    private record TimeRange(long from, long to) {}
    private record ScoredEvent(JsonObject event, int score) {}
}
