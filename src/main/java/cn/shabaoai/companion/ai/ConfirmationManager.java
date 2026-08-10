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
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Agent 确认管理器。
 *
 * <p>当 Agent 执行危险动作（放 TNT、破坏现有方块、生存模式建造等）时，
 * 暂停 Agent 循环，向玩家询问确认。玩家在聊天框回复"确认"/"取消"后，
 * 通过 {@link #handleReply} 完成等待的 Future。
 *
 * <p>设计为每个玩家最多一个待确认请求，避免并发混乱。
 * 每个请求有独立超时；替换、急停、断线和停服都会显式取消旧 Future。
 */
public final class ConfirmationManager {
    private static final long CONFIRM_TIMEOUT_SECONDS = 45;
    private static final ScheduledExecutorService TIMEOUTS = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "shabao-ai-confirm-timeouts");
        t.setDaemon(true);
        return t;
    });
    /** 每个玩家的待确认请求 */
    private static final Map<UUID, PendingConfirmation> PENDING = new ConcurrentHashMap<>();

    private ConfirmationManager() {}

    /**
     * 向玩家发起确认请求，返回 Future 等待回复。
     * @param player 要确认的玩家
     * @param prompt 询问内容（如"要放置 TNT 32 个，确认吗？"）
     * @return true=确认执行，false=取消
     */
    public static CompletableFuture<Boolean> request(ServerPlayerEntity player, String prompt) {
        // 【第2批·防御性断言】确认器只服务真实玩家实体（ServerPlayerEntity 由参数类型保证）。
        // 沙包是 CompanionEntity(MobEntity)，handleReply 按 UUID 比对永远匹配不上，进不了此通道——
        // 这里兜底防止未来有人把非玩家实体误传给 request 导致 Future 永久挂起。
        if (player.getServer() == null) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        UUID uuid = player.getUuid();
        // 如果已有待确认，先完成旧的（取消）
        PendingConfirmation pending = new PendingConfirmation(future, prompt);
        PendingConfirmation old = PENDING.put(uuid, pending);
        if (old != null) old.future.complete(false);
        // 发送询问消息
        String name = ModConfig.normalizeCompanionName(ModConfig.get().companionName);
        player.sendMessage(Text.literal("§e[" + name + "确认] §f" + prompt));
        player.sendMessage(Text.literal("§7聊天输入\"确认\"或\"取消\""));
        TIMEOUTS.schedule(() -> {
            if (PENDING.remove(uuid, pending)) {
                pending.future.complete(false);
                if (player.getServer() != null) {
                    player.getServer().execute(() -> player.sendMessage(Text.literal("§7确认已超时，本次操作已取消。")));
                }
            }
        }, CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return future;
    }

    /**
     * 处理玩家的聊天回复。由聊天监听器调用。
     * @param player 回复的玩家
     * @param message 聊天原文
     * @return true=这条消息是确认/取消回复，已被消费；false=与确认无关
     */
    public static boolean handleReply(ServerPlayerEntity player, String message) {
        UUID uuid = player.getUuid();
        PendingConfirmation pending = PENDING.get(uuid);
        if (pending == null) return false;
        String trimmed = message.trim();
        // 匹配各种确认/取消表达，其他消息不消费
        if (trimmed.equals("确认") || trimmed.equals("好的") || trimmed.equals("好") || trimmed.equals("ok")
                || trimmed.equals("可以") || trimmed.equals("行") || trimmed.equals("是")
                || trimmed.equalsIgnoreCase("yes") || trimmed.equalsIgnoreCase("y")) {
            if (!PENDING.remove(uuid, pending)) return false;
            pending.future.complete(true);
            player.sendMessage(Text.literal("§a已确认，继续执行。"));
            return true;
        }
        if (trimmed.equals("取消") || trimmed.equals("不了") || trimmed.equals("不")
                || trimmed.equals("算了") || trimmed.equals("不要") || trimmed.equals("否")
                || trimmed.equalsIgnoreCase("no") || trimmed.equalsIgnoreCase("n")) {
            if (!PENDING.remove(uuid, pending)) return false;
            pending.future.complete(false);
            player.sendMessage(Text.literal("§7已取消。"));
            return true;
        }
        return false;
    }

    /** 取消玩家的待确认请求（如 Agent 出错退出时） */
    public static void cancel(UUID uuid) {
        PendingConfirmation pending = PENDING.remove(uuid);
        if (pending != null) pending.future.complete(false);
    }

    /** 服务器停止时取消全部待确认，保证旧 Future 不会跨世界存活。 */
    public static void cancelAll() {
        for (UUID uuid : PENDING.keySet()) cancel(uuid);
    }

    /** 玩家是否有待确认请求 */
    public static boolean isPending(UUID uuid) {
        return PENDING.containsKey(uuid);
    }

    /** 单个待确认请求的记录 */
    private record PendingConfirmation(CompletableFuture<Boolean> future, String prompt) {}
}
