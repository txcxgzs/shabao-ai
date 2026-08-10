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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Agent 日志系统：记录 LLM 请求/响应、执行步骤、耗时。
 *
 * <p>日志写入两个地方：
 * <ul>
 *   <li>{@code logs/shabao-ai-agent.log} —— 完整日志，每次请求/响应都记录</li>
 *   <li>内存队列 —— 供游戏内实时查看最近的请求</li>
 * </ul>
 *
 * <p>排查"思考慢"问题：看日志里每步 LLM 请求耗时，就知道是网络慢还是 LLM 推理慢。
 */
public final class AgentLogger {
    /** 日志文件路径 */
    private static final Path LOG_FILE = Paths.get("logs", "shabao-ai-agent.log");
    /** 时间格式 */
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    /** 内存中保留的最近日志条目，供游戏内查看 */
    private static final ConcurrentLinkedQueue<LogEntry> RECENT = new ConcurrentLinkedQueue<>();
    /** 内存队列最大条数 */
    private static final int MAX_RECENT = 50;

    private AgentLogger() {}

    /** 记录一条 LLM 请求 */
    public static void logRequest(int step, String requestBody) {
        String time = LocalDateTime.now().format(TIME_FMT);
        // 文件日志不截断：完整请求体方便排查 LLM 上下文问题
        String entry = String.format("[%s] [步骤%d] >>> 发送请求%n%s%n", time, step, requestBody);
        writeToFile(entry);
        // 内存队列（游戏内查看）只保留前 500 字，避免刷屏
        addRecent(new LogEntry(time, step, "REQUEST", truncate(requestBody, 500), null));
    }

    /** 记录一条 LLM 响应，含耗时 */
    public static void logResponse(int step, String responseBody, Duration elapsed) {
        String time = LocalDateTime.now().format(TIME_FMT);
        // 文件日志不截断：完整响应体方便排查（截断会导致看不到 JSON 被破坏在哪）
        String entry = String.format("[%s] [步骤%d] <<< 收到响应 (耗时 %dms)%n%s%n---%n",
                time, step, elapsed.toMillis(), responseBody);
        writeToFile(entry);
        addRecent(new LogEntry(time, step, "RESPONSE",
                truncate(responseBody, 500), elapsed.toMillis() + "ms"));
    }

    /**
     * 记录 LLM 返回的原生 tool_calls（每个调用的 name + 完整参数 JSON）。
     *
     * <p>流式路径的响应体是增量拼出来的，不会以完整 body 形式进日志；
     * 非流式虽进文件，但游戏内内存队列截断 500 字看不到 tool_calls。
     * 统一在这里记一份，保证"每次调用/具体参数"两处都能看见。
     */
    public static void logToolCalls(int step, java.util.List<LlmClient.ToolCall> calls) {
        if (calls == null || calls.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (LlmClient.ToolCall c : calls) {
            sb.append("\n  -> ").append(c.name()).append("(").append(c.arguments()).append(")");
        }
        String entry = String.format("[%s] [步骤%d] ### LLM 工具调用 %d 个:%s%n",
                LocalDateTime.now().format(TIME_FMT), step, calls.size(), sb);
        writeToFile(entry);
        // 内存队列（游戏内查看）：参数通常几百字内，完整保留
        addRecent(new LogEntry(LocalDateTime.now().format(TIME_FMT), step, "TOOL_CALL",
                "LLM 工具调用 " + calls.size() + " 个" + sb, null));
    }

    /** 记录一条错误 */
    public static void logError(int step, String error) {
        String time = LocalDateTime.now().format(TIME_FMT);
        String entry = String.format("[%s] [步骤%d] !!! 错误: %s%n", time, step, error);
        writeToFile(entry);
        addRecent(new LogEntry(time, step, "ERROR", error, null));
    }

    /** 记录一条动作执行 */
    public static void logAction(int step, String actionType, String summary) {
        String time = LocalDateTime.now().format(TIME_FMT);
        String entry = String.format("[%s] [步骤%d] === 执行动作: %s | %s%n", time, step, actionType, summary);
        writeToFile(entry);
        addRecent(new LogEntry(time, step, actionType, summary, null));
    }

    /** 记录一条普通信息 */
    public static void logInfo(String message) {
        String time = LocalDateTime.now().format(TIME_FMT);
        String entry = String.format("[%s] [信息] %s%n", time, message);
        writeToFile(entry);
        addRecent(new LogEntry(time, 0, "INFO", message, null));
    }

    /** 获取最近的日志条目（用于游戏内查看） */
    public static java.util.List<LogEntry> getRecent() {
        return new java.util.ArrayList<>(RECENT);
    }

    /** 清空日志文件 */
    public static void clearLogFile() {
        try {
            Files.deleteIfExists(LOG_FILE);
            logInfo("日志已清空");
        } catch (IOException e) {
            // 忽略
        }
    }

    private static void addRecent(LogEntry entry) {
        RECENT.add(entry);
        while (RECENT.size() > MAX_RECENT) {
            RECENT.poll();
        }
    }

    private static void writeToFile(String content) {
        try {
            Files.createDirectories(LOG_FILE.getParent());
            Files.writeString(LOG_FILE, content, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // 日志写入失败不影响主流程
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "...(截断)";
    }

    /** 日志条目记录 */
    public record LogEntry(String time, int step, String type, String content, String elapsed) {}
}
