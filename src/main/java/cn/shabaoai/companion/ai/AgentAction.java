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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 表示 LLM 在 Agent 循环中单步返回的动作。
 *
 * <p>核心改进：新增 {@code build_plan} 动作，LLM 一次返回完整建筑计划（多个 place 步骤），
 * 框架批量执行，不需要每步都请求 LLM。建一个屋子只需 2-3 次 LLM 请求。
 *
 * <p><b>shape 指令优化</b>：build_plan 的每个 step 支持用 shape 指令代替手写坐标，
 * 一个 25×25 地基只需 1 行 fill 指令，而非 625 个坐标，大幅减少 LLM 生成 token。
 *
 * <p>七种动作：
 * <ul>
 *   <li><b>scan</b>：扫描区域。args: {x,y,z,radius}</li>
 *   <li><b>query</b>：查询环境。</li>
 *   <li><b>query_block</b>：查询方块百科。args: {block_id:"oak_door"} 返回方块的用途、属性、放置规则</li>
 *   <li><b>place</b>：放置一批方块。args: {blocks:[[dx,dy,dz,"id"],...]}</li>
 *   <li><b>build_plan</b>：批量建造计划。args: {steps:[{name,shape|blocks},...]}
 *       每个 step 可用 shape 指令（fill/wall/box，高效）或 blocks 数组（精细），可混用。
 *       框架自动执行所有步骤，不需要每步都请求 LLM。</li>
 *   <li><b>walk</b>：AI 队友走向指定相对坐标。</li>
 *   <li><b>plan</b>：任务规划，生成 todo 列表。</li>
 *   <li><b>finish</b>：任务完成。</li>
 * </ul>
 */
public final class AgentAction {
    /**
     * 【版权哨兵】动作系统内嵌版权哨兵密文，由 GuardCore.check() 解密核对作者标识。
     * 明文：版权哨兵·行动：沙包AI内测版动作系统版权所有（C）2026 txcxgzs。
     * 删除/篡改此方法会导致完整性校验失败、AI 功能锁定。
     */
    public static String licenseStamp() {
        return "A2ZML0040G5EN6BjGPvZVuaBr9Pu47XlBvMH7w3TaBsMIBmai+PpebYok5r/UXvtkp4ttfiCE9GIlLU2Pk0DqWMkOhgg2CdHME6qeXpyg7/PMXqCSxxPutXG7wToDP/PuCTEr2iQ3S16oQcLpTVsuMyfWJbGGyHxFbuWo+ytYI+qncyw==";
    }

    // 【Task 4】agent-runtime 扩展：新增 7 个动作（目标/技能/等待/说话）
    // SET_GOAL/CANCEL_GOAL：设置/取消长时任务目标；START_SKILL/STOP_SKILL：启动/停止技能；
    // WAIT：原地等待指定秒数；WAIT_UNTIL：等待条件满足或超时；SPEAK：直接对玩家说话（不结束任务）
    // 【Cognition】SET_COGNITION：设置目标认知策略（mode/min/max/预约）；SCHEDULE_THINK：预约下次思考
    // 【长期目标语义】COMPLETE_GOAL：显式结束长期目标（与 FINISH 解耦——finish 只结束本轮思考）
    // 【Fast Path 退场】PAUSE_GOAL：暂停当前目标（等 resume_wait/新指令恢复）；RESUME_WAIT：恢复等待中的目标
    public enum Type { SCAN, QUERY, QUERY_BLOCK, QUERY_BUILDING, RECALL, PLACE, BUILD_PLAN, WALK, PLAN, COMMAND, VERIFY_PATH, OPEN_DOOR, CLOSE_DOOR, INTERACT, FINISH, SET_GOAL, CANCEL_GOAL, START_SKILL, STOP_SKILL, WAIT, WAIT_UNTIL, SPEAK, SET_COGNITION, SCHEDULE_THINK, COMPLETE_GOAL, PAUSE_GOAL, RESUME_WAIT, RELATIONSHIP, SOCIAL_INTERACTION }

    /** 单个 shape 展开的方块数上限，防止 LLM 返回超大坐标导致 OOM */
    private static final int MAX_SHAPE_BLOCKS = 50000;

    public final Type type;
    public final String reply;
    // SCAN/WALK：相对原点偏移
    // 【W-4】WALK 楼层模式用 hasX/hasZ 区分"AI 显式填了 x/z"和"未填"：
    // 世界坐标 0 是合法值，原来用 int + 0 当哨兵会把 AI 显式写的 x=0/z=0 静默改写成玩家坐标
    public final int x, y, z, radius;
    // 【W-4】x/z 是否由 AI 显式提供（仅 WALK 楼层模式有意义；其他动作恒 true）
    public final boolean hasX, hasZ;
    // PLACE：单批方块
    public final List<int[]> offsets;
    public final List<String> blockIds;
    // BUILD_PLAN：多个建造步骤
    public final List<BuildStep> steps;
    // PLAN：任务规划 todo 列表
    public final List<String> todos;
    // QUERY_BLOCK：要查询的方块 ID（如 "oak_door"）
    // QUERY_BUILDING：要查询的建筑教程 ID（如 "stairwell"）
    // COMMAND：要执行的 MC 指令（如 "give @s diamond 64"）
    public final String queryBlockId;
    // WALK: 楼层号（语义化移动，如 floor=2 前往2楼）。
    // null 表示用 x/y/z 坐标移动；非 null 表示用楼层号查询记忆中的Y坐标。
    public final Integer floor;
    // 解析错误（拒绝执行）
    public final String parseError;
    // 【P1-E】解析警告（不拒绝执行，仅提示 LLM 注意）。build_plan 几何校验中
    // "本次计划没有 floor 洞口"等分次建造场景属于警告而非硬错误，不能杀整份计划
    public final String parseWarning;
    // 【第2批】执行前必须真人确认（tp_player 传送玩家用）。其余参数化命令（give 等）为 false
    public final boolean confirmRequired;
    // 【修F】原生 tool calling 的工具调用 ID：工具结果回传 role:"tool" + tool_call_id 时使用。
    // 文本解析路径恒为 null；可变字段（非 final）是刻意为之——fromToolCalls 在解析后附加，
    // 避免给全部 15 处构造调用点加参数（工具 ID 只在 tool-calling 转录里有意义）
    public String toolCallId;
    // 【Task 4】agent-runtime 扩展字段。与 toolCallId 同为可变字段（非 final）：
    // 解析后附加赋值，避免给全部构造调用点加参数。默认 null/0，仅对应 Type 有意义。
    public String goalType;          // SET_GOAL：目标类型（如 "follow"/"build"/"mine_assist"）
    public JsonObject goalParams;    // SET_GOAL：附加目标参数（可选，null=无）
    public JsonObject recallArgs;    // RECALL：完整检索参数，交给 EpisodicMemory 解释
    public String skillId;           // START_SKILL/STOP_SKILL：技能 id（如 "torch_place"）
    public int waitSeconds;          // WAIT：等待秒数（默认 5）
    public String waitCondition;     // WAIT_UNTIL：条件字符串（如 "player_distance>5"）
    public int waitTimeout;          // WAIT_UNTIL：超时秒数（默认 30）
    // 【Cognition】认知策略字段（可变，解析后附加赋值，仅对应 Type 有意义）
    public String cognitionMode;     // SET_COGNITION：认知模式（local/event/adaptive/active）
    public int cognitionMin;         // SET_COGNITION：最短自检间隔（秒，0=默认）
    public int cognitionMax;         // SET_COGNITION：最长自检间隔（秒，0=默认）
    public int cognitionNextAfter;   // SET_COGNITION：预约下次思考秒数（0=不预约）
    public int thinkAfterSec;        // SCHEDULE_THINK：预约下次思考秒数（必须>0）
    public String relationshipAction; // RELATIONSHIP：pursue_partner/accept_partner/end_partner/update_names/update_interaction_settings
    public String playerNickname;     // RELATIONSHIP(update_names)：AI 对玩家的称呼
    public String companionNickname;  // RELATIONSHIP(update_names)：玩家对 AI 的称呼
    public String interactionPolicy;  // RELATIONSHIP(update_interaction_settings)：互动权限
    public String initiativeLevel;    // RELATIONSHIP(update_interaction_settings)：主动程度
    public String socialInteractionAction; // SOCIAL_INTERACTION：hold_hand/release_hand/hug/kiss/cancel_interaction

    /** 建造步骤：名称 + 方块列表 */
    public record BuildStep(String name, List<int[]> offsets, List<String> blockIds) {}

    /**
     * 【修F】附加 tool_call_id（原生 tool calling 回放历史用）。
     * 返回自身便于链式调用（fromToolCalls 里 `fromJson(wrapped).withToolCallId(id)`）。
     */
    public AgentAction withToolCallId(String id) {
        this.toolCallId = id;
        return this;
    }

    /** 原构造函数（保留，委托给带 floor/parseWarning 的构造函数，floor=null, parseWarning=null） */
    private AgentAction(Type type, String reply, int x, int y, int z, int radius,
                        List<int[]> offsets, List<String> blockIds,
                        List<BuildStep> steps, List<String> todos,
                        String queryBlockId, String parseError) {
        this(type, reply, x, y, z, radius, offsets, blockIds, steps, todos, queryBlockId, parseError,
                null, null, true, true, false);
    }

    /** 完整构造函数（含 floor 参数，供 parseWalk 使用） */
    private AgentAction(Type type, String reply, int x, int y, int z, int radius,
                        List<int[]> offsets, List<String> blockIds,
                        List<BuildStep> steps, List<String> todos,
                        String queryBlockId, String parseError, Integer floor, String parseWarning,
                        boolean hasX, boolean hasZ, boolean confirmRequired) {
        this.type = type;
        this.reply = reply;
        this.x = x; this.y = y; this.z = z; this.radius = radius;
        this.offsets = offsets;
        this.blockIds = blockIds;
        this.steps = steps;
        this.todos = todos;
        this.queryBlockId = queryBlockId;
        this.parseError = parseError;
        this.floor = floor;
        this.parseWarning = parseWarning;
        this.hasX = hasX;
        this.hasZ = hasZ;
        this.confirmRequired = confirmRequired;
    }

    /** 复制当前动作并附加警告（parseWarning 非空时由 runStep 注入 system 提示，不拒绝执行） */
    private AgentAction withWarning(String warning) {
        return new AgentAction(type, reply, x, y, z, radius, offsets, blockIds, steps, todos,
                queryBlockId, parseError, floor, warning, hasX, hasZ, confirmRequired);
    }

    /**
     * 【第2批】构造参数化命令动作（command 拆分的 give/set_time/set_weather/effect/kill_type/tp_self/tp_player/locate_structure）。
     * @param cmd 拼好的完整指令（如 "give @s diamond 64"）
     * @param confirmRequired 是否必须真人确认（tp_player 传 true）
     */
    public static AgentAction paramCommand(String cmd, boolean confirmRequired) {
        return new AgentAction(Type.COMMAND, null, 0, 0, 0, 0, null, null, null, null, cmd, null,
                null, null, true, true, confirmRequired);
    }

    public static AgentAction finish(String reply) {
        return new AgentAction(Type.FINISH, reply, 0, 0, 0, 0, null, null, null, null, null, null);
    }

    public static AgentAction error(String reason) {
        return new AgentAction(Type.FINISH, "（动作解析失败：" + reason + "）",
                0, 0, 0, 0, null, null, null, null, null, reason);
    }

    /** 【L3-2】供 AgentExecutor 构造 verify_path 动作（复用 BFS 自检） */
    public static AgentAction verifyPathForCheck(int fromX, int fromY, int fromZ,
                                                  int toX, int toY, int toZ,
                                                  int fromFloor, int toFloor) {
        return new AgentAction(Type.VERIFY_PATH, null,
                fromX, fromY, fromZ, 0,
                java.util.List.of(new int[]{toX, toY, toZ}),
                null, null, null,
                String.valueOf(toFloor), null, fromFloor, null, true, true, false);
    }

    /**
     * 【第1批】从原生 tool_calls 构造动作列表。
     *
     * <p>原生 tool calling 下模型不再输出散文 JSON，而是返回结构化工具调用数组。
     * 每个 tool_call 的 function.arguments 是合法 JSON 字符串，无需 repairJson/diagnose。
     * tool_calls 数组天然有序，模型可以在一条回复里 plan + build_plan + finish。
     *
     * @param toolCalls LLM 返回的工具调用列表（LlmClient.ChatResult.toolCalls）
     * @return 按顺序构造的动作列表（可能含 error 动作）
     */
    public static List<AgentAction> fromToolCalls(List<LlmClient.ToolCall> toolCalls) {
        List<AgentAction> out = new ArrayList<>();
        if (toolCalls == null || toolCalls.isEmpty()) return out;
        int tcIdx = 0; // 【修S】id 兜底计数器：少数端点不返回 tool_call id，
        // 缺失时用 call_<序号> 生成一致 id（fromToolCalls 与回放处必须用同一规则）。
        // 序号在 try 之前递增：解析失败 continue 也不能跳号，否则与回放处错位
        for (LlmClient.ToolCall tc : toolCalls) {
            int thisIdx = tcIdx++;
            // 【混淆安全】编译期直接调用 record 组件方法，不用 getMethod("name") 反射——
            // 混淆后 ToolCall 组件方法名被重命名，字符串反射 NoSuchMethodException，
            // 导致动作全部解析失败、回执 id 兜底为 call_<n>，与 assistant 回放的
            // 真实 tool_call_id 对不上 → LLM API 400 "tool_call_ids did not have response messages"
            String name = tc.name();
            String arguments = tc.arguments() == null ? "" : tc.arguments();
            String id = tc.id();
            if (id == null || id.isBlank()) id = "call_" + thisIdx;
            // tool name 对应 action name：finish -> finish, 其余同 action 字段
            JsonObject args;
            try {
                args = arguments.isBlank() ? new JsonObject()
                        : JsonParser.parseString(arguments).getAsJsonObject();
            } catch (Exception e) {
                out.add(error("tool_call " + name + " 参数 JSON 解析失败：" + e.getMessage())
                        .withToolCallId(id));
                continue;
            }
            // 从 fromJson 复用解析逻辑：构造一个 {action, args} 包装对象
            JsonObject wrapped = new JsonObject();
            wrapped.addProperty("action", name);
            wrapped.add("args", args);
            try {
                // 【修F】每个动作携带自己的 tool_call_id，执行结果回传时能精确对应
                out.add(fromJson(wrapped).withToolCallId(id));
            } catch (IllegalArgumentException e) {
                out.add(error("build_plan 几何校验失败：" + e.getMessage()).withToolCallId(id));
            } catch (Exception e) {
                out.add(error("tool_call " + name + " 解析异常：" + e.getMessage()).withToolCallId(id));
            }
        }
        if (out.isEmpty()) {
            out.add(finish(""));
        }
        return out;
    }

    /**
     * 从 LLM 文本中解析动作。增强容错：处理 markdown 包裹、blocks 为空等。
     */
    public static AgentAction parse(String text) {
        // 兼容入口：单动作解析（verifyPromptExamples 等），返回第一个动作
        List<AgentAction> actions = parseAll(text);
        return actions.isEmpty() ? finish(text == null ? "" : text) : actions.get(0);
    }

    /**
     * 【P0-M1 修1】解析同一条回复里的全部动作（允许模型一口气 plan + build_plan + finish）。
     *
     * <p>v12 实测：模型输出「散文 + plan JSON + 散文 + build_plan JSON」，旧实现只取第一个 JSON，
     * 16 步的 build_plan 被静默吞掉——模型以为建成了，实际一块砖都没放（哨塔事故）。
     * 这不是禁止双 JSON，而是允许它：模型想一口气规划+建造本来是好事，顺序执行即可。
     *
     * <p>每段先做括号配平截断（repairJson），一段一段解析；某段失败生成 error 动作（含诊断），
     * 不影响后续段。没有任何 JSON 时回退为 finish(原文)（闲聊回复直接播给玩家）。
     */
    public static List<AgentAction> parseAll(String raw) {
        List<AgentAction> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            out.add(finish(""));
            return out;
        }
        String s = raw.replace("```json", "").replace("```", "").trim();
        int i = 0;
        while ((i = s.indexOf('{', i)) >= 0) {
            // 每个 JSON 段从第一个 { 配平截断，尾巴丢弃
            String body = repairJson(s.substring(i));
            if (body.isEmpty()) break;
            try {
                out.add(fromJson(JsonParser.parseString(body).getAsJsonObject()));
            } catch (IllegalArgumentException e) {
                // 【P0-J3 修复3 前置】几何/坐标错误统一加"几何校验失败"前缀走独立计数器
                out.add(error("build_plan 几何校验失败：" + e.getMessage()));
            } catch (Exception e) {
                // JsonParser 解析失败抛 com.google.gson.JsonSyntaxException——该异常类在 Loom 编译
                // classpath 上不可见（javac 找不到符号，运行时实例存在），统一按 Exception 处理，
                // 交给 diagnose 做括号配平诊断（能照着改，而不是"line 8 column 5 path $"让模型猜）
                out.add(error(diagnose(raw, e)));
            }
            i += body.length();
        }
        if (out.isEmpty()) {
            // 没有任何 JSON：整条作为 finish 的回复（闲聊场景）
            out.add(finish(s));
        }
        return out;
    }

    /** 从单个 JSON 对象构造动作（parse/parseAll 共用）。 */
    private static AgentAction fromJson(JsonObject json) {
        // 【修C】兼容两种键名：原生 "action"，以及被 tools 训练过的模型在无 tools 时
        // 天然吐的 {"name":"build_plan","args":{...}}（OpenAI function calling 风格）。
        // 旧实现只认 "action"，模型输出 name 格式时默认 action="finish" → 5 个动作全丢，
        // FINISH 被误捞成 reply=空 → 兜底"完成。"（74 秒假成功事故的解析侧根因）
        String action = str(json, "action", str(json, "name", "finish"));
        JsonObject args = json.has("args") && json.get("args").isJsonObject()
                ? json.getAsJsonObject("args") : new JsonObject();
        return switch (action) {
            case "scan" -> parseScan(args);
            case "query" -> new AgentAction(Type.QUERY, null, 0, 0, 0, 0, null, null, null, null, null, null);
            case "recall" -> parseRecall(args);
            case "query_block" -> parseQueryBlock(args);
            case "query_building" -> parseQueryBuilding(args);
            case "command" -> parseCommand(args);
            // 【第2批】command 拆分参数化工具：give/set_time/set_weather/effect/kill_type/tp_self/tp_player/locate_structure
            // 各自带 schema，解析时拼成完整指令字符串。tp_player 必须真人确认（confirmRequired=true）
            case "give" -> parseParamCommand(args, "give", "give @s %s %d");
            case "set_time" -> parseParamCommand(args, "set_time", "time set %s");
            case "set_weather" -> parseParamCommand(args, "set_weather", "weather %s");
            case "effect" -> parseParamCommand(args, "effect", "effect give @s %s %d %d");
            case "kill_type" -> parseParamCommand(args, "kill_type", "kill @e[type=%s,distance=..%d]");
            case "tp_self" -> parseParamCommand(args, "tp_self", "tp @s %d %d %d");
            case "tp_player" -> parseTpPlayer(args);
            // 【locate_structure】定位最近结构（村庄/要塞/海底神殿等）：
            // 枚举参数限定结构类型，映射成 locate structure 指令（不直接放开 command 的 locate）
            case "locate_structure" -> parseLocateStructure(args);
            case "place" -> parsePlace(args);
            case "build_plan" -> parseBuildPlan(args);
            case "walk" -> parseWalk(args);
            case "plan" -> parsePlan(args);
            case "verify_path" -> parseVerifyPath(args);
            // 【v21.7】开门/关门：AI 走到门前打开门通行（而不是把门替换掉），走完关门
            case "open_door" -> parseDoor(args, true);
            case "close_door" -> parseDoor(args, false);
            // 【v21.7】通用交互：门/活板门/栅栏门/拉杆/按钮/箱子等
            case "interact" -> parseInteract(args);
            // 【Task 4】agent-runtime 扩展：目标/技能/等待/说话 7 个新动作（SPEAK 复用 reply 字段）
            case "set_goal" -> parseSetGoal(args);
            case "cancel_goal" -> parseCancelGoal(args);
            case "start_skill" -> parseStartSkill(args);
            case "stop_skill" -> parseStopSkill(args);
            case "wait" -> parseWait(args);
            case "wait_until" -> parseWaitUntil(args);
            case "speak" -> parseSpeak(args);
            // 【Cognition】认知策略：长期任务的大脑思考节奏
            case "set_cognition" -> parseSetCognition(args);
            case "schedule_think" -> parseScheduleThink(args);
            // 【长期目标语义】complete_goal：显式结束长期目标（区别于 finish 只结束本轮思考）
            case "complete_goal" -> parseCompleteGoal(args);
            // 【Fast Path 退场】pause_goal：暂停当前目标；resume_wait：恢复等待中的目标
            case "pause_goal" -> new AgentAction(Type.PAUSE_GOAL, null, 0, 0, 0, 0, null, null, null, null, null, null);
            case "resume_wait" -> new AgentAction(Type.RESUME_WAIT, null, 0, 0, 0, 0, null, null, null, null, null, null);
            case "relationship" -> parseRelationship(args);
            case "social_interaction" -> parseSocialInteraction(args);
            default -> finish(str(args, "reply", str(json, "thought", "")));
        };
    }

    /**
     * 【P0-J1 修复1】括号配平截断：从第一个 {@code {} 扫到配平的闭合符为止，把后面的尾巴全部丢弃。
     *
     * <p>LLM 生成长 JSON（尤其 build_plan 的 steps 数组）时经常在结尾多打/多贴一个字符，
     * 严格解析会整份拒绝。此函数是容错层，不改变语义——只去掉配平点之后的内容。
     * 字符串内的引号与转义会被跳过，不会把字符串值里的括号算进深度。
     */
    private static String repairJson(String s) {
        int start = s.indexOf('{');
        if (start < 0) return s;
        int depth = 0;
        boolean inStr = false;
        char prev = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '"' && prev != '\\') inStr = false;
            } else if (c == '"') {
                inStr = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                if (--depth == 0) return s.substring(start, i + 1);
            }
            prev = c;
        }
        return s.substring(start);
    }

    /**
     * 【P0-J2 修复2】括号配平诊断：数出 {@code {} 与 {@code []} 的差值，给出能照着改的错误信息。
     * 模型拿到"line 8 column 5 path $"猜四次都猜不中；拿到「你多了 1 个 }，你写成了 ]}}}」第二次就能改对。
     */
    private static String diagnose(String raw, Exception e) {
        if (raw == null) return "JSON 语法错误：" + e.getMessage();
        int b = countChar(raw, '{') - countChar(raw, '}');
        int k = countChar(raw, '[') - countChar(raw, ']');
        if (b < 0) {
            return "你的 JSON 多了 " + (-b) + " 个右花括号 }。build_plan 正确结尾是 ]}} "
                    + "（数组的 ]、args 的 }、根对象的 }）。你写成了 " + tail(raw, 6) + "。";
        }
        if (b > 0) return "你的 JSON 少了 " + b + " 个右花括号 }。";
        if (k != 0) return "方括号 [] 不配平，差 " + k + " 个。";
        return "JSON 语法错误：" + e.getMessage();
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) n++;
        }
        return n;
    }

    private static String tail(String s, int n) {
        return s.length() <= n ? s : "…" + s.substring(s.length() - n);
    }

    /** 解析 query_block 动作：查询方块百科知识。格式：{"block_id":"oak_door"} */
    private static AgentAction parseQueryBlock(JsonObject args) {
        String blockId = str(args, "block_id", "");
        if (blockId.isEmpty()) return error("query_block 缺少 block_id 参数");
        return new AgentAction(Type.QUERY_BLOCK, null, 0, 0, 0, 0, null, null, null, null, blockId, null);
    }

    /** 检索可持久化情景记忆；参数保持结构化原貌，由记忆层统一解析。 */
    private static AgentAction parseRecall(JsonObject args) {
        AgentAction out = new AgentAction(Type.RECALL, null, 0, 0, 0, 0,
                null, null, null, null, null, null);
        out.recallArgs = args == null ? new JsonObject() : args.deepCopy();
        return out;
    }

    /**
     * 解析 query_building 动作：查询建筑教程知识库。
     * 格式：{"building_id":"stairwell"} 或 {"building_id":"spiral_mining"}
     * 复用 queryBlockId 字段存 building_id（语义扩展，避免增加构造函数参数）。
     */
    private static AgentAction parseQueryBuilding(JsonObject args) {
        String buildingId = str(args, "building_id", "");
        if (buildingId.isEmpty()) return error("query_building 缺少 building_id 参数");
        return new AgentAction(Type.QUERY_BUILDING, null, 0, 0, 0, 0, null, null, null, null, buildingId, null);
    }

    /**
     * 解析 command 动作：执行 Minecraft 指令。
     * 格式：{"cmd":"give @s diamond 64"} 或 {"cmd":"time set day"}
     * 指令不带开头的 /（框架自动处理）。复用 queryBlockId 字段存指令内容。
     * 安全限制：在执行层过滤危险指令（op、deop、stop 等）。
     */
    private static AgentAction parseCommand(JsonObject args) {
        String cmd = str(args, "cmd", "");
        if (cmd.isEmpty()) return error("command 缺少 cmd 参数");
        // 去掉开头的 /（LLM 可能带也可能不带）
        if (cmd.startsWith("/")) cmd = cmd.substring(1).trim();
        return new AgentAction(Type.COMMAND, null, 0, 0, 0, 0, null, null, null, null, cmd, null);
    }

    /**
     * 【第2批】解析参数化命令工具（give/set_time/set_weather/effect/kill_type/tp_self）。
     *
     * <p>参数按工具 schema 严格校验，拼成完整指令字符串传给执行层（白名单仍兜底）。
     * 由 fromToolCalls 或散文 JSON 解析统一走这里，schema 的 enum/required 天然减少格式错误。
     *
     * @param args    工具参数
     * @param tool    工具名（日志用）
     * @param pattern 指令模板，%s 字符串 / %d 整数占位（按工具 schema 顺序）
     */
    private static AgentAction parseParamCommand(JsonObject args, String tool, String pattern) {
        // 按工具名提取参数并拼装指令。所有参数从 args 读取，缺省时报错让模型补。
        return switch (tool) {
            case "give" -> {
                String item = str(args, "item", "");
                int count = intOr(args, "count", 1);
                if (item.isEmpty()) yield error("give 缺少 item 参数（物品 ID，如 diamond）");
                if (count < 1 || count > 64) yield error("give count 必须是 1~64，当前=" + count);
                yield paramCommand("give @s " + item + " " + count, false);
            }
            case "set_time" -> {
                String t = str(args, "time", "");
                if (!t.equals("day") && !t.equals("night")) {
                    yield error("set_time 的 time 只能是 day 或 night，当前=" + (t.isEmpty() ? "(空)" : t));
                }
                yield paramCommand("time set " + t, false);
            }
            case "set_weather" -> {
                String w = str(args, "weather", "");
                if (!w.equals("clear") && !w.equals("rain") && !w.equals("thunder")) {
                    yield error("set_weather 的 weather 只能是 clear/rain/thunder，当前=" + (w.isEmpty() ? "(空)" : w));
                }
                yield paramCommand("weather " + w, false);
            }
            case "effect" -> {
                String eff = str(args, "effect", "");
                int sec = intOr(args, "seconds", 30);
                int amp = intOr(args, "amplifier", 1);
                if (eff.isEmpty()) yield error("effect 缺少 effect 参数（效果 ID，如 speed）");
                if (sec < 1 || sec > 600) yield error("effect seconds 必须是 1~600，当前=" + sec);
                yield paramCommand("effect give @s " + eff + " " + sec + " " + amp, false);
            }
            case "kill_type" -> {
                String type = str(args, "type", "");
                int dist = intOr(args, "distance", 40);
                if (type.isEmpty()) yield error("kill_type 缺少 type 参数（怪物英文 ID，如 zombie）");
                if (dist < 1 || dist > 64) yield error("kill_type distance 必须是 1~64，当前=" + dist);
                yield paramCommand("kill @e[type=" + type + ",distance=.." + dist + "]", false);
            }
            case "tp_self" -> {
                int x = intOr(args, "x", Integer.MIN_VALUE);
                int y = intOr(args, "y", Integer.MIN_VALUE);
                int z = intOr(args, "z", Integer.MIN_VALUE);
                if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
                    yield error("tp_self 缺少 x/y/z 坐标");
                }
                // tp_self 传送【沙包自己】到坐标（AI 过去查看/验收），不打断玩家
                yield paramCommand("tp @e[type=shabao_ai:companion,limit=1] " + x + " " + y + " " + z, false);
            }
            default -> error("未知参数化工具: " + tool);
        };
    }

    /**
     * 【第2批】解析 tp_player 工具：传送【玩家】到指定坐标。
     * 会打断玩家操作，必须真人确认后才执行（confirmRequired=true，执行层走 ConfirmationManager）。
     */
    private static AgentAction parseTpPlayer(JsonObject args) {
        int x = intOr(args, "x", Integer.MIN_VALUE);
        int y = intOr(args, "y", Integer.MIN_VALUE);
        int z = intOr(args, "z", Integer.MIN_VALUE);
        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
            return error("tp_player 缺少 x/y/z 坐标");
        }
        return paramCommand("tp @s " + x + " " + y + " " + z, true);
    }

    /**
     * 【locate_structure】解析定位结构工具：按枚举参数映射成 {@code locate structure} 指令。
     *
     * <p>设计动机：{@code locate} 本身是安全的只读指令，但若直接加进 command 白名单，
     * LLM 就能往 {@code locate} 后面塞任意结构/生物群系参数（虽然无害，但违背参数化路线，
     * 且容易生成非法结构 ID 浪费一轮）。这里用 schema enum 把结构类型锁死，
     * 执行层（executeCommand）再用同一集合做正向校验，command 直发 locate 一律拦截——
     * 定位结构只有本工具一条通道。多子结构的类型用 {@code #} 标签定位最近任意一种
     * （如 village → #minecraft:village 覆盖 5 种村庄，mineshaft → #minecraft:mineshaft）。
     */
    private static AgentAction parseLocateStructure(JsonObject args) {
        String structure = str(args, "structure", "");
        String cmd = switch (structure) {
            case "village" -> "locate structure #minecraft:village";
            case "mineshaft" -> "locate structure #minecraft:mineshaft";
            case "ruined_portal" -> "locate structure #minecraft:ruined_portal";
            case "stronghold" -> "locate structure minecraft:stronghold";
            case "monument" -> "locate structure minecraft:monument";
            case "mansion" -> "locate structure minecraft:mansion";
            case "ancient_city" -> "locate structure minecraft:ancient_city";
            case "trial_chambers" -> "locate structure minecraft:trial_chambers";
            default -> null; // 未知结构：进 error 分支
        };
        if (cmd == null) {
            return error("locate_structure 的 structure 只能是 village/stronghold/monument/mansion/"
                    + "ancient_city/trial_chambers/mineshaft/ruined_portal，当前="
                    + (structure.isEmpty() ? "(空)" : structure));
        }
        // 只读定位，无需玩家确认
        return paramCommand(cmd, false);
    }

    private static AgentAction parseScan(JsonObject args) {
        int radius = intOr(args, "radius", 8);
        return new AgentAction(Type.SCAN, null,
                intOr(args, "x", 0), intOr(args, "y", 0), intOr(args, "z", 0),
                radius, null, null, null, null, null, null);
    }

    /**
     * 【v21.7】解析开门/关门动作：对指定坐标的门方块切换 open 状态。
     * AI 走到门前 open_door 通行、走完 close_door，不再"把门替换掉"。
     */
    private static AgentAction parseDoor(JsonObject args, boolean open) {
        int x = intOr(args, "x", Integer.MIN_VALUE);
        int y = intOr(args, "y", Integer.MIN_VALUE);
        int z = intOr(args, "z", Integer.MIN_VALUE);
        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
            return error((open ? "open_door" : "close_door") + " 缺少 x/y/z 坐标（门的方块坐标）");
        }
        return new AgentAction(open ? Type.OPEN_DOOR : Type.CLOSE_DOOR, null,
                x, y, z, 0, null, null, null, null, null, null);
    }

    /**
     * 【v21.7】解析通用交互动作：interact{x,y,z}。
     * 框架按目标方块类型自动处理：门/活板门/栅栏门切换开合、拉杆切换、按钮按下（自动弹回）、
     * 箱子等容器读取内容。不要求 action 参数——方块类型决定行为。
     */
    private static AgentAction parseInteract(JsonObject args) {
        int x = intOr(args, "x", Integer.MIN_VALUE);
        int y = intOr(args, "y", Integer.MIN_VALUE);
        int z = intOr(args, "z", Integer.MIN_VALUE);
        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
            return error("interact 缺少 x/y/z 坐标（目标方块的坐标）");
        }
        return new AgentAction(Type.INTERACT, null,
                x, y, z, 0, null, null, null, null, null, null);
    }

    /**
     * 【Task 4】解析 set_goal 动作：设置当前长时任务目标（agent-runtime）。
     * 格式：{"type":"follow","params":{...}}。type 必填，params 可选对象（附加目标参数）。
     * 缺 type 返回 error 动作（不崩溃）。
     */
    private static AgentAction parseSetGoal(JsonObject args) {
        String type = str(args, "type", "");
        if (type.isEmpty()) return error("set_goal 必须传 type，如 follow/build/mine_assist");
        AgentAction a = new AgentAction(Type.SET_GOAL, null, 0, 0, 0, 0, null, null, null, null, null, null);
        a.goalType = type;
        if (args.has("params") && args.get("params").isJsonObject()) {
            a.goalParams = args.getAsJsonObject("params");
        }
        return a;
    }

    /** 【Task 4】解析 cancel_goal 动作：彻底取消并清理当前任务目标。格式：{}（无参数） */
    private static AgentAction parseCancelGoal(JsonObject args) {
        return new AgentAction(Type.CANCEL_GOAL, null, 0, 0, 0, 0, null, null, null, null, null, null);
    }

    /** 【Task 4】解析 start_skill 动作：启动技能（如 torch_place 自动插火把）。格式：{"skill":"torch_place"} */
    private static AgentAction parseStartSkill(JsonObject args) {
        String skill = str(args, "skill", "");
        if (skill.isEmpty()) return error("start_skill 必须传 skill，如 torch_place");
        AgentAction a = new AgentAction(Type.START_SKILL, null, 0, 0, 0, 0, null, null, null, null, null, null);
        a.skillId = skill;
        return a;
    }

    /** 【Task 4】解析 stop_skill 动作：停止正在运行的技能。格式：{"skill":"torch_place"} */
    private static AgentAction parseStopSkill(JsonObject args) {
        String skill = str(args, "skill", "");
        if (skill.isEmpty()) return error("stop_skill 必须传 skill，如 torch_place");
        AgentAction a = new AgentAction(Type.STOP_SKILL, null, 0, 0, 0, 0, null, null, null, null, null, null);
        a.skillId = skill;
        return a;
    }

    /**
     * 【Task 4】解析 wait 动作：原地等待指定秒数（同步等待）。
     * 格式：{"seconds":5,"reason":"等玩家"}。seconds 缺省默认 5，显式传 <1 视为非法返回 error。
     */
    private static AgentAction parseWait(JsonObject args) {
        int seconds = intOr(args, "seconds", 5);
        if (seconds < 1) return error("wait 的 seconds 必须 >=1，当前=" + seconds);
        AgentAction a = new AgentAction(Type.WAIT, null, 0, 0, 0, 0, null, null, null, null, null, null);
        a.waitSeconds = seconds;
        return a;
    }

    /**
     * 【Task 4】解析 wait_until 动作：等待条件满足或超时。
     * 格式：{"condition":"player_distance>5","timeout":30}。condition 必填，timeout 缺省默认 30 秒。
     */
    private static AgentAction parseWaitUntil(JsonObject args) {
        String condition = str(args, "condition", "");
        if (condition.isEmpty()) return error("wait_until 必须传 condition，如 player_distance>5");
        AgentAction a = new AgentAction(Type.WAIT_UNTIL, null, 0, 0, 0, 0, null, null, null, null, null, null);
        a.waitCondition = condition;
        a.waitTimeout = intOr(args, "timeout", 30);
        return a;
    }

    /**
     * 【Task 4】解析 speak 动作：直接对玩家说话（不结束任务）。
     * 格式：{"text":"对玩家说的话"}。text 必填，内容复用 reply 字段（与 FINISH 类似的"直接回复"）。
     */
    private static AgentAction parseSpeak(JsonObject args) {
        String text = str(args, "text", "");
        if (text.isEmpty()) return error("speak 必须传 text，对玩家说的话");
        return new AgentAction(Type.SPEAK, text, 0, 0, 0, 0, null, null, null, null, null, null);
    }

    /**
     * 【Cognition】解析 set_cognition 动作：设置当前目标的认知策略。
     * 格式：{"mode":"adaptive","min_interval":8,"max_interval":30,"next_after":12}。
     * mode 必填（local/event/adaptive/active）；interval/next_after 可选（0=默认）。
     * Runtime 会对 mode 与区间做强制校正（开放式目标不允许 LOCAL/EVENT）。
     */
    private static AgentAction parseSetCognition(JsonObject args) {
        String mode = str(args, "mode", "");
        if (mode.isBlank()) return error("set_cognition 必须传 mode（local/event/adaptive/active）");
        AgentAction a = new AgentAction(Type.SET_COGNITION, null, 0, 0, 0, 0, null, null, null, null, null, null);
        a.cognitionMode = mode.trim();
        a.cognitionMin = intOr(args, "min_interval", 0);
        a.cognitionMax = intOr(args, "max_interval", 0);
        a.cognitionNextAfter = intOr(args, "next_after", 0);
        return a;
    }

    /**
     * 【Cognition】解析 schedule_think 动作：预约"下次醒来"（不改变策略本身）。
     * 格式：{"after":12}。after 必填（秒，>0），由 CognitionPolicy 按 mode clamp。
     */
    private static AgentAction parseScheduleThink(JsonObject args) {
        int after = intOr(args, "after", 0);
        if (after <= 0) return error("schedule_think 必须传 after（秒，>0）");
        AgentAction a = new AgentAction(Type.SCHEDULE_THINK, null, 0, 0, 0, 0, null, null, null, null, null, null);
        a.thinkAfterSec = after;
        return a;
    }

    /**
     * 【长期目标语义】解析 complete_goal 动作：显式结束当前长期目标。
     * 格式：{}（无参数）。与 finish 的关键区别：finish 只结束"本轮思考"，goal 保持 RUNNING 继续常驻；
     * complete_goal 才真正把 goal 置为 COMPLETED（触发 GOAL_DONE 收尾闭环）。
     * 【r7】不带 reply：最后一句由 GOAL_DONE 唤醒的 Brain 自然说，避免"complete_goal 说一遍
     * + GOAL_DONE 又总结一遍"双口水。
     */
    private static AgentAction parseCompleteGoal(JsonObject args) {
        return new AgentAction(Type.COMPLETE_GOAL, null, 0, 0, 0, 0, null, null, null, null, null, null);
    }

    /** 关系状态工具。关系变更由 Runtime 再校验前台来源与多回合门槛。 */
    private static AgentAction parseRelationship(JsonObject args) {
        String action = str(args, "action", "").trim().toLowerCase();
        if (!action.equals("pursue_partner") && !action.equals("accept_partner")
                && !action.equals("end_partner") && !action.equals("update_names")
                && !action.equals("update_interaction_settings")) {
            return error("relationship.action 必须是 pursue_partner/accept_partner/end_partner/update_names/update_interaction_settings");
        }
        AgentAction a = new AgentAction(Type.RELATIONSHIP, null, 0, 0, 0, 0,
                null, null, null, null, null, null);
        a.relationshipAction = action;
        a.playerNickname = args.has("player_nickname") && !args.get("player_nickname").isJsonNull()
                ? args.get("player_nickname").getAsString() : null;
        a.companionNickname = args.has("companion_nickname") && !args.get("companion_nickname").isJsonNull()
                ? args.get("companion_nickname").getAsString() : null;
        a.interactionPolicy = args.has("interaction_policy") && !args.get("interaction_policy").isJsonNull()
                ? args.get("interaction_policy").getAsString() : null;
        a.initiativeLevel = args.has("initiative") && !args.get("initiative").isJsonNull()
                ? args.get("initiative").getAsString() : null;
        return a;
    }

    private static AgentAction parseSocialInteraction(JsonObject args) {
        String action = str(args, "action", "").trim().toLowerCase();
        if (!action.equals("hold_hand") && !action.equals("release_hand")
                && !action.equals("hug") && !action.equals("kiss")
                && !action.equals("cancel_interaction")) {
            return error("social_interaction.action 必须是 hold_hand/release_hand/hug/kiss/cancel_interaction");
        }
        AgentAction a = new AgentAction(Type.SOCIAL_INTERACTION, null, 0, 0, 0, 0,
                null, null, null, null, null, null);
        a.socialInteractionAction = action;
        return a;
    }

    /**
     * 解析 walk 动作：走向指定位置。
     *
     * <p>支持两种模式：
     * <ul>
     *   <li><b>坐标模式</b>（默认）：{"x":10,"y":0,"z":5}，AI 走向世界坐标 (x,y,z)。</li>
     *   <li><b>楼层模式</b>（语义化）：{"floor":2}，AI 前往2楼。框架从建筑记忆查询
     *       楼板Y坐标，X/Z 用玩家当前位置（同层移动）。
     *       也可 {"floor":2,"x":100,"z":-50} 指定楼层+XZ坐标。</li>
     * </ul>
     *
     * <p>floor 参数优先于 y 参数：若指定 floor，则 y 被忽略，由记忆中的楼层Y决定。
     */
    private static AgentAction parseWalk(JsonObject args) {
        Integer floor = null;
        if (args.has("floor") && args.get("floor").isJsonPrimitive()) {
            try {
                floor = args.get("floor").getAsInt();
            } catch (Exception ignored) {}
        }
        // floor 模式：y 用 0 占位（执行层会从记忆查询真实楼层Y）
        int y = floor != null ? 0 : intOr(args, "y", 0);
        // 【W-4】用 has() 而非"0 当哨兵"判断 x/z 是否显式提供
        boolean hasX = args.has("x") && args.get("x").isJsonPrimitive();
        boolean hasZ = args.has("z") && args.get("z").isJsonPrimitive();
        return new AgentAction(Type.WALK, null,
                intOr(args, "x", 0), y, intOr(args, "z", 0),
                0, null, null, null, null, null, null, floor, null, hasX, hasZ, false);
    }

    /**
     * 【L3-1】解析 verify_path 动作：检查两层楼板间的 3D 可达性。
     *
     * <p>支持两种参数模式：
     * <ul>
     *   <li>楼层模式：{"from_floor":1,"to_floor":2}，Y 从建筑记忆查询</li>
     *   <li>坐标模式：{"from_x":..,"from_y":..,"from_z":..,"to_x":..,"to_y":..,"to_z":..}</li>
     * </ul>
     * 执行层跑 3D BFS，返回断点坐标 + 修复建议（不是简单"失败"）。
     */
    private static AgentAction parseVerifyPath(JsonObject args) {
        // 【L3-1】verify_path：fromFloor→floor 字段，toFloor→queryBlockId(字符串)，
        // from 坐标→x/y/z，to 坐标→offsets(单个 int[])。执行层据此跑 BFS。
        Integer fromFloor = null, toFloor = null;
        try { if (args.has("from_floor")) fromFloor = args.get("from_floor").getAsInt(); } catch (Exception ignored) {}
        try { if (args.has("to_floor")) toFloor = args.get("to_floor").getAsInt(); } catch (Exception ignored) {}
        int fromX = intOr(args, "from_x", 0);
        int fromY = intOr(args, "from_y", 0);
        int fromZ = intOr(args, "from_z", 0);
        int toX = intOr(args, "to_x", 0);
        int toY = intOr(args, "to_y", 0);
        int toZ = intOr(args, "to_z", 0);
        List<int[]> toOffset = new ArrayList<>();
        toOffset.add(new int[]{toX, toY, toZ});
        return new AgentAction(Type.VERIFY_PATH, null,
                fromX, fromY, fromZ, 0, toOffset, null, null, null,
                toFloor == null ? null : String.valueOf(toFloor), null,
                fromFloor, null, true, true, false);
    }

    /**
     * 解析 plan 动作：LLM 规划任务 todo 列表。
     * 格式：{"todos":["步骤1","步骤2",...]} 或 {"todos":[]}（清空 todo）。
     *
     * <p><b>关键</b>：空数组是合法清空语义（提示词要求 AI 全部完成后用
     * plan{"todos":[]} 清空才能 finish），不能当错误拒绝！
     * 只有 todos 字段缺失或不是数组才是格式错误。
     */
    private static AgentAction parsePlan(JsonObject args) {
        List<String> todos = new ArrayList<>();
        if (args.has("todos")) {
            if (!args.get("todos").isJsonArray()) {
                return error("plan 动作的 todos 必须是数组，如 {\"todos\":[\"步骤1\"]} 或 {\"todos\":[]}（清空）");
            }
            for (JsonElement e : args.getAsJsonArray("todos")) {
                try { todos.add(e.getAsString()); } catch (Exception ignored) {}
            }
        } else {
            return error("plan 动作缺少 todos 参数，如 {\"todos\":[\"步骤1\"]} 或 {\"todos\":[]}（清空）");
        }
        return new AgentAction(Type.PLAN, null, 0, 0, 0, 0, null, null, null, todos, null, null);
    }

    /**
     * 解析 place 动作。容错：blocks 缺失或格式错误时返回 error 而非崩溃。
     */
    private static AgentAction parsePlace(JsonObject args) {
        List<int[]> offsets = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        java.util.List<String> warns = parseBlocksArray(args, offsets, ids);
        if (offsets.isEmpty()) {
            return error("place 动作的 blocks 为空或格式错误");
        }
        AgentAction action = new AgentAction(Type.PLACE, null, 0, 0, 0, 0, offsets, ids, null, null, null, null);
        // 【v22·软扫描】手写楼梯连跑 facing 不符等警告以 system 角色喂回模型（不阻塞放置）
        if (!warns.isEmpty()) {
            action = action.withWarning(String.join("\n", warns));
        }
        return action;
    }

    /**
     * 解析 build_plan 动作：包含多个建造步骤。
     *
     * <p>每个 step 支持两种写法（可混用，框架合并）：
     * <ul>
     *   <li><b>shape 指令</b>（推荐，token 极少）：
     *       {@code {"name":"地基","shape":"fill","x1":0,"y1":0,"z1":0,"x2":14,"y2":0,"z2":14,"id":"stone_bricks"}}
     *       <ul>
     *         <li><b>fill</b>：实心长方体填充（适合地基、楼板、屋顶）</li>
     *         <li><b>wall</b>：空心墙体（只画 4 个侧面，不画顶底，适合楼层墙壁）</li>
     *         <li><b>box</b>：空心外壳（6 个面，适合整栋建筑外框）</li>
     *       </ul>
     *       一个 25×25 地基只需 1 个 fill 指令，无需列出 625 个坐标。</li>
     *   <li><b>blocks 数组</b>（精细控制，适合门窗、楼梯、装饰）：
     *       {@code {"name":"门窗","blocks":[[dx,dy,dz,"id"],...]}}</li>
     * </ul>
     */
    private static AgentAction parseBuildPlan(JsonObject args) {
        if (!args.has("steps") || !args.get("steps").isJsonArray()) {
            return error("build_plan 缺少 steps 数组");
        }
        List<BuildStep> steps = new ArrayList<>();
        // 【第1批·几何校验增量化】失败 step 记录 {step_index, error_code, hint}，
        // 通过的步骤照常执行，不再整份拒绝。模型只需补失败项（patch_steps）
        List<String> stepErrors = new ArrayList<>();
        // 【P1-E】收集警告（不拒绝执行，仅提示 LLM）——分次建造等合法场景不能杀整份计划
        List<String> stepWarnings = new ArrayList<>();
        // 【E4 交叉校验】收集 floor 洞口矩形和 spiral 起点，循环结束后做跨 step 一致性校验
        // 【P1-E】洞/楼梯都带 Y：只有 Y 落在螺旋楼梯高度范围 [y, y+height] 内的洞口才算覆盖，
        // 否则 5 层楼里"任何一层开洞"都会让 1 楼楼梯误判通过（上层实心楼板照样撞头）
        java.util.List<int[]> floorHoles = new ArrayList<>(); // {minX, minZ, maxX, maxZ, y1}
        java.util.List<int[]> spiralOrigins = new ArrayList<>(); // {x, z, y, height}
        // 【L1-5】门入口 reserved volume 收集 + 楼梯/梯子起点收集，循环后做交叉校验
        // 门 reserved: {x, y, facing} —— 门占 (x,y,y+1)，内侧沿 -facing 2 格、外侧 +facing 1 格禁放
        java.util.List<int[]> doorReserved = new ArrayList<>();
        // 楼梯/梯子起点: {x, y, z, type} type=1 stairs, 2 ladder, 3 spiral
        java.util.List<int[]> circStarts = new ArrayList<>();
        int stepIdx = 0; // 【第1批】step 在 steps 数组中的真实位置（1-based，patch_steps 用它定位）
        for (JsonElement stepElem : args.getAsJsonArray("steps")) {
            stepIdx++;
            if (!stepElem.isJsonObject()) continue;
            JsonObject stepObj = stepElem.getAsJsonObject();
            String name = str(stepObj, "name", "未命名步骤");
            // 【E4】收集元数据（不依赖展开是否成功）
            String shape = str(stepObj, "shape", "");
            if (shape.equals("floor")) {
                int hx1 = intOr(stepObj, "hx1", Integer.MIN_VALUE);
                int hz1 = intOr(stepObj, "hz1", Integer.MIN_VALUE);
                int hx2 = intOr(stepObj, "hx2", Integer.MIN_VALUE);
                int hz2 = intOr(stepObj, "hz2", Integer.MIN_VALUE);
                int fy = intOr(stepObj, "y1", Integer.MIN_VALUE);
                if (hx1 != Integer.MIN_VALUE && hz1 != Integer.MIN_VALUE
                        && hx2 != Integer.MIN_VALUE && hz2 != Integer.MIN_VALUE) {
                    floorHoles.add(new int[]{Math.min(hx1, hx2), Math.min(hz1, hz2),
                            Math.max(hx1, hx2), Math.max(hz1, hz2), fy});
                }
            } else if (shape.equals("spiral")) {
                spiralOrigins.add(new int[]{intOr(stepObj, "x", 0), intOr(stepObj, "z", 0),
                        intOr(stepObj, "y", 0), intOr(stepObj, "height", 0)});
            } else if (shape.equals("stairwell")
                    && "spiral".equals(str(stepObj, "style", "spiral"))) {
                // 【v21.1】stairwell(style=spiral) 也纳入螺旋楼梯洞口交叉校验（E4）：
                // 旧逻辑只收集 shape=spiral，stairwell 螺旋楼梯绕过校验——楼板若是实心的
                // （fill/floor 没留 2×2 洞），楼梯嵌进楼板、第 1 级埋在楼板下，走不上去
                // （20 步打满事故的帮凶之一）。现在它和 shape=spiral 一样要求 floor 留 2×2 洞口。
                int stFromY = intOr(stepObj, "from_y", 0);
                int stToY = intOr(stepObj, "to_y", stFromY + 5);
                spiralOrigins.add(new int[]{intOr(stepObj, "x1", 0), intOr(stepObj, "z1", 0),
                        stFromY + 1, stToY - stFromY});
            }
            // 【L1-5】收集门 reserved volume 与楼梯/梯子起点
            if (shape.equals("door")) {
                String df = str(stepObj, "facing", null);
                if (df != null) {
                    // 门是单点 shape，存 {x1, y1, z1, facingCode}
                    doorReserved.add(new int[]{intOr(stepObj, "x1", 0),
                            intOr(stepObj, "y1", 0), intOr(stepObj, "z1", 0),
                            facingCode(df)});
                }
            } else if (shape.equals("stairs") || shape.equals("ladder") || shape.equals("spiral")
                    || shape.equals("stairwell")) {
                // 【v21.1】stairwell 一并收集起点（第一级 = from_y+1），参与门净空校验：
                // 旧逻辑漏掉 stairwell，楼梯起点挡门（挡内侧/外侧净空区）无人拦截
                int t;
                int sx, sz, sy;
                if (shape.equals("stairs")) {
                    t = 1;
                    sx = intOr(stepObj, "x1", 0); sz = intOr(stepObj, "z1", 0); sy = intOr(stepObj, "y1", 0);
                } else if (shape.equals("ladder")) {
                    t = 2;
                    sx = intOr(stepObj, "x1", 0); sz = intOr(stepObj, "z1", 0); sy = intOr(stepObj, "y1", 0);
                } else if (shape.equals("spiral")) {
                    t = 3;
                    sx = intOr(stepObj, "x", 0); sz = intOr(stepObj, "z", 0); sy = intOr(stepObj, "y", 0);
                } else {
                    t = 3;
                    sx = intOr(stepObj, "x1", 0); sz = intOr(stepObj, "z1", 0);
                    sy = intOr(stepObj, "from_y", 0) + 1;
                }
                circStarts.add(new int[]{sx, sy, sz, t});
            }
            List<int[]> offsets = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            // air 收集器：与实体方块分开，避免顺序性 bug（实体方块优先于 air）
            List<int[]> airOffsets = new ArrayList<>();
            List<String> airIds = new ArrayList<>();
            try {
                expandShape(stepObj, offsets, ids, airOffsets, airIds);
            } catch (IllegalArgumentException ex) {
                // 【第1批·增量】几何校验失败：该 step 不执行，记录 {step_index, error_code, hint}。
                // 不再整份拒绝——通过步骤照常执行，模型只需 patch 失败项
                String code = geomErrorCode(ex.getMessage());
                stepErrors.add("step" + stepIdx + "(" + name + ") [E_" + code + "] " + ex.getMessage());
                continue; // 跳过本 step 的 blocks 数组（它依附于失败的 shape 上下文）
            }
            parseBlocksArray(stepObj, offsets, ids).forEach(stepWarnings::add);
            // air 在实体方块之后追加，preparePlace 阶段会做去重（实体优先）
            if (!airOffsets.isEmpty()) {
                offsets.addAll(airOffsets);
                ids.addAll(airIds);
            }
            if (!offsets.isEmpty()) {
                steps.add(new BuildStep(name, offsets, ids));
            }
        }
        // 【E4】spiral 与 floor 洞口交叉校验：同一个 plan 里有 spiral 就必须有覆盖它的洞口，
        // 否则螺旋楼梯会嵌进楼板（每层留掉落洞或楼梯被楼板顶住）
        if (!spiralOrigins.isEmpty()) {
            // 【P1-F】洞口尺寸强制：任何覆盖了 spiral 2×2 footprint 的洞口必须【精确 2×2】。
            // expandShape 的 ">3×3 拒绝" 会同时放行 2×2 和 3×3，AI 若给 spiral 开 3×3，
            // 楼板上会留一圈 1 格宽掉落缝——这里按"是否覆盖 spiral"收紧到 2×2
            // 【P2-N1】必须带 Y 重叠条件：同一竖井里"低层 spiral + 高层直线(3×3)"是合法设计，
            // 3×3 洞口在 Y 上不覆盖 spiral 的高度范围时不得误判
            for (int[] h : floorHoles) {
                int hw = h[2] - h[0] + 1, hd = h[3] - h[1] + 1;
                if (hw != 2 || hd != 2) {
                    for (int[] sp : spiralOrigins) {
                        boolean yOverlap = h[4] == Integer.MIN_VALUE
                                || (sp[2] <= h[4] && h[4] <= sp[2] + sp[3]);
                        if (yOverlap && h[0] <= sp[0] && h[2] >= sp[0] + 1
                                && h[1] <= sp[1] && h[3] >= sp[1] + 1) {
                            stepErrors.add("[floor] 洞口 " + hw + "×" + hd + " 覆盖了 spiral 螺旋楼梯"
                                    + "（2×2 footprint），必须精确 2×2（hx/hz 各跨 2 格）——"
                                    + "开 3×3 会在楼板上留一圈 1 格宽掉落缝！"
                                    + "spiral 用 2×2；只有直线/L 型 stairs 才用 3×3。");
                            break;
                        }
                    }
                }
            }
            for (int[] sp : spiralOrigins) {
                int sx = sp[0], sz = sp[1], sy = sp[2], sHeight = sp[3];
                boolean covered = false;
                for (int[] h : floorHoles) {
                    int hx1 = h[0], hz1 = h[1], hx2 = h[2], hz2 = h[3], hy = h[4];
                    // spiral 2×2 占 (sx,sz)~(sx+1,sz+1)，必须整个落在洞口矩形内（XZ）
                    if (!(hx1 <= sx && hx2 >= sx + 1 && hz1 <= sz && hz2 >= sz + 1)) continue;
                    // 【P1-E】Y 配对：螺旋楼梯跨越 [sy, sy+sHeight]，只有位于该范围内的洞口才算覆盖。
                    // 否则 5 层楼里任何一层开洞都会让 1 楼楼梯误判通过，上层实心楼板照样撞头
                    if (hy == Integer.MIN_VALUE || (sy <= hy && hy <= sy + sHeight)) {
                        covered = true;
                        break;
                    }
                }
                if (!covered) {
                    if (floorHoles.isEmpty()) {
                        // 【P1-E】分次建造（上次已开洞、这次只发楼梯）时本次计划里没有 floor 洞口，
                        // 必然误报——降级为警告而非拒绝，避免整份计划被误杀
                        stepWarnings.add("[spiral] 本次计划里没有 floor 洞口，无法交叉校验楼梯覆盖"
                                + "（若洞口是上次建造的，忽略此警告；若是新建建筑，请先建 floor 留洞）");
                    } else {
                        stepErrors.add("[spiral] 螺旋楼梯起点 (" + sx + "," + sz + ") 的 2×2 footprint"
                                + " 没有被任何位于其高度范围 [y=" + sy + "~" + (sy + sHeight) + "] 的 floor 洞口覆盖"
                                + " ——楼梯会嵌进楼板/留掉落洞！"
                                + "请给该层 floor 的洞口加 hx/hz 使其覆盖 x[" + sx + "~" + (sx + 1) + "] z["
                                + sz + "~" + (sz + 1) + "]");
                    }
                }
            }
        }
        // 【L1-5】门入口 reserved volume 校验：楼梯/梯子起点距门 < 2 格 或 上行朝门 → 拒绝
        // 门 (dx,dy,dz) facing=D：内侧 -D 方向 2 格 × 高 2（dy,dy+1）绝对禁放，
        // 外侧 +D 方向 1 格 × 高 2 绝对禁放。楼梯/梯子起点落在禁放区会挡门。
        // 楼梯第 2、3 级正好落在门上半格和门楣位置——起点离门 1~2 格必堵。
        if (!doorReserved.isEmpty() && !circStarts.isEmpty()) {
            for (int[] door : doorReserved) {
                int dx = door[0], dy = door[1], dz = door[2];
                int fc = door[3];
                if (fc < 0) continue;
                int[] d = facingDelta(fc);
                // 内侧禁放区：门沿 -facing 方向 2 格，y=dy 和 dy+1
                // 外侧禁放区：门沿 +facing 方向 1 格，y=dy 和 dy+1
                for (int[] cs : circStarts) {
                    int cx = cs[0], cy = cs[1], cz = cs[2], ct = cs[3];
                    String ctype = ct == 1 ? "stairs" : (ct == 2 ? "ladder" : "spiral");
                    // 内侧 2 格：x=dx-d[0]*1..2, z=dz-d[1]*1..2, y=dy/dy+1
                    // 外侧 1 格：x=dx+d[0]*1, z=dz+d[1]*1, y=dy/dy+1
                    boolean inInner = false, inOuter = false;
                    for (int k = 1; k <= 2; k++) {
                        if (cx == dx - d[0] * k && cz == dz - d[1] * k
                                && (cy == dy || cy == dy + 1)) { inInner = true; break; }
                    }
                    if (!inInner && cx == dx + d[0] && cz == dz + d[1]
                            && (cy == dy || cy == dy + 1)) { inOuter = true; }
                    if (inInner || inOuter) {
                        stepErrors.add("[" + ctype + "] 起点 (" + cx + "," + cy + "," + cz
                                + ") 落在门 (" + dx + "," + dy + "," + dz + ") 的"
                                + (inInner ? "内侧" : "外侧") + "入口净空区，会挡门！"
                                + "楼梯/梯子起点距门至少 2 格，且上行方向不得朝门。");
                    }
                }
            }
        }
        if (!stepErrors.isEmpty()) {
            // 【第1批·增量】不再整份拒绝：通过步骤照常执行，失败步骤以 step_index+错误码
            // 注入 parseWarning（system 角色），模型只 patch 失败项，不再重发整份 16 步计划。
            // 跨 step 校验错误（spiral 洞口覆盖/门净空）没有具体 step_index，前置 "plan:"
            String errText = "【几何校验】以下步骤未执行，其余步骤照常执行。请用 build_plan 只重发这些失败步骤"
                    + "（patch_steps，step_index 按原数组位置）：\n  - " + String.join("\n  - ", stepErrors);
            if (steps.isEmpty()) {
                // 所有步骤都失败：没有可执行的，只能拒绝
                return error("build_plan 全部 " + stepErrors.size() + " 个步骤几何校验失败，无步骤可执行：\n  - "
                        + String.join("\n  - ", stepErrors));
            }
            stepWarnings.add(errText);
        }
        if (steps.isEmpty()) {
            return error("build_plan 的所有步骤都为空");
        }
        AgentAction action = new AgentAction(Type.BUILD_PLAN, null, 0, 0, 0, 0, null, null, steps, null, null, null);
        // 【P1-E】有警告（含增量校验的错误明细）但无致命错误：正常执行通过步骤，警告以 system 角色提示
        if (!stepWarnings.isEmpty()) {
            action = action.withWarning(String.join("\n", stepWarnings));
        }
        return action;
    }

    /**
     * 【V7-6】单个 build_plan step 的几何校验（供启动自检逐 step 复用）。
     * 通过返回 null，失败返回错误信息。
     * 只做 expandShape 内的几何校验（体积上限/单点豁免/spiral 尺寸/越界等），
     * 不做 E4 跨 step 交叉校验（那需要整份 plan 的上下文，只能在 parseBuildPlan 里做）。
     */
    public static String verifyStepGeometry(JsonObject stepObj) {
        List<int[]> offsets = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        List<int[]> airOffsets = new ArrayList<>();
        List<String> airIds = new ArrayList<>();
        try {
            expandShape(stepObj, offsets, ids, airOffsets, airIds);
        } catch (IllegalArgumentException ex) {
            return ex.getMessage();
        }
        return null;
    }

    /**
     * 把 shape 指令展开成方块坐标列表。
     *
     * <p>支持三种形状：
     * <ul>
     *   <li><b>fill</b>：实心填充长方体所有格子</li>
     *   <li><b>wall</b>：只画 4 个侧面（前后左右），不画顶和底，用于楼层墙体</li>
     *   <li><b>box</b>：画 6 个面（空心外壳），用于建筑整体框架</li>
     * </ul>
     *
     * <p>坐标自动归一化（x1/x2 大小关系不限），越界方块由执行层过滤。
     * 单个 shape 展开上限 {@value #MAX_SHAPE_BLOCKS} 个方块，防止 OOM。
     *
     * @param stepObj  JSON 步骤对象
     * @param offsets  展开后的相对坐标列表（输出）
     * @param ids      对应的方块 ID 列表（输出）
     */
    private static void expandShape(JsonObject stepObj, List<int[]> offsets, List<String> ids,
                                     List<int[]> airOffsets, List<String> airIds) {
        String shape = str(stepObj, "shape", "");
        if (shape.isEmpty()) return;
        // 【P0-L1】x2/y2/z2 缺省回落 x1/y1/z1（不是 0！）：
        // 单点 shape（door/ladder）JSON 里根本没有 x2/y2/z2，原实现取到默认 0，
        // 体积 = (|0-x1|+1)×(|0-y1|+1)×(|0-z1|+1)，建筑离原点 >37 格就必超 50000——
        // 提示词内置示例的门在 (278,64,-161) 算出 2937870，照抄必被拒（"毒提示词"实锤）
        int x1 = intOr(stepObj, "x1", 0), y1 = intOr(stepObj, "y1", 0), z1 = intOr(stepObj, "z1", 0);
        int x2 = stepObj.has("x2") ? intOr(stepObj, "x2", x1) : x1;
        int y2 = stepObj.has("y2") ? intOr(stepObj, "y2", y1) : y1;
        int z2 = stepObj.has("z2") ? intOr(stepObj, "z2", z1) : z1;
        // 【夷平】shape=clear：削高夷平矩形区域。清障不产生方块，【无材质要求】——
        // 必须在材质检查之前 return，否则缺 block 键会被 E_NO_MATERIAL 拒绝。
        // 生成两个哨兵角点偏移 {x1,y,z1}/{x2,y,z2}，id=#CLEAR#；执行层拦截后调 clearTerrain。
        // y（y1 或 y）为可选目标高度：0=执行期自动取区域内真实地表 Y 中位数（推荐，削高不挖深）。
        if (shape.equals("clear")) {
            int cX1 = Math.min(x1, x2), cZ1 = Math.min(z1, z2);
            int cX2 = Math.max(x1, x2), cZ2 = Math.max(z1, z2);
            int cY = stepObj.has("y1") ? y1 : intOr(stepObj, "y", 0);
            offsets.add(new int[]{cX1, cY, cZ1});
            ids.add("#CLEAR#");
            offsets.add(new int[]{cX2, cY, cZ2});
            ids.add("#CLEAR#");
            return;
        }
        // 【修M】shape 步骤材质键：block 权威 > id 兼容 > blocks(字符串) 兼容。
        // 历史遗留：schema 一直没告诉模型 shape 材质键叫什么，v15 猜 "block"（错）、
        // v16 猜 "blocks"（错），读不到就静默默认 stone——模型规划橡木屋、执行器放石头屋
        // （17:01 会话 361 块里 330 块 shape 步骤全变石头，且 ok:true 假成功）。
        // 现在显式兼容三个键；缺材质时抛 E_NO_MATERIAL 大声失败，不再静默兜底 stone
        String id = str(stepObj, "block", "");
        if (id.isBlank()) id = str(stepObj, "id", "");
        if (id.isBlank()) {
            JsonElement be = stepObj.get("blocks");
            if (be != null && be.isJsonPrimitive()) id = be.getAsString();
        }
        if (id.isBlank()) {
            // 【修M】shape 步骤缺材质不得静默默认 stone——抛异常让 parseBuildPlan
            // 捕获后回灌 E_NO_MATERIAL，模型知道该补材质而不是看着石头屋说"橡木盖好了"
            throw new IllegalArgumentException("E_NO_MATERIAL: shape=" + shape + " 步骤缺材质键。"
                    + "请用 block 键指定材质 ID（如 \"block\":\"oak_planks\"）；"
                    + "精细放置请用 blocks 数组 [[x,y,z,\"id\"],...]");
        }
        // 【P1-1】材质类型护栏：楼梯类 shape 必须用 *_stairs 方块。
        // v19 公寓实心柱真实链路：模型给 spiral/stairwell 传 quartz_block 等非楼梯方块，
        // 宽松解析把 [facing=…] 丢成非法属性丢掉 → 落地成实心方块（踩不出台阶）。
        // 一行护栏封死：stairs/spiral/stairwell 的材质必须以内置 _stairs 结尾。
        if ((shape.equals("stairs") || shape.equals("spiral") || shape.equals("stairwell"))
                && !baseBlockId(id).endsWith("_stairs")) {
            throw new IllegalArgumentException("E_BAD_STAIR_MATERIAL: shape=" + shape
                    + " 的 block 必须是楼梯方块（ID 以 _stairs 结尾，如 quartz_stairs/oak_stairs），"
                    + "当前=" + id + "——用非楼梯方块（如 quartz_block）会生成实心柱、踩不出台阶！"
                    + "请换成对应楼梯方块。");
        }
        // 归一化坐标，确保 min <= max
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);

        // 【P0-L1】单点 shape 完全跳过体积校验：door/ladder 只有 1 个起点，
        // 体积概念不适用（虽然 x2 回落 x1 后体积=1，但显式跳过更清晰、杜绝回归）
        boolean pointShape = shape.equals("door") || shape.equals("ladder");
        if (!pointShape) {
            // 体积上限检查：防止 LLM 返回超大坐标导致 OOM
            // 改为抛异常，让 parseBuildPlan 捕获后回灌给 LLM（原版静默 return 会导致整面墙凭空消失）
            long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
            if (volume > MAX_SHAPE_BLOCKS) {
                // 【P0-L2】报错回传【实际计算的包围盒】+ 按 shape 定制建议：
                // 原文案"请拆成多个小步骤"对门/单点 shape 完全不成立，AI 会花几十秒逆向工程这个 bug。
                // 把包围盒打出来，AI 一眼看见 (0,0,0) 之类的异常值就知道坐标写错了
                throw new IllegalArgumentException(String.format(
                        "shape=%s 体积 %d 超过上限 %d（计算用包围盒 (%d,%d,%d)-(%d,%d,%d)）。"
                                + "请检查 x1/x2、y1/y2、z1/z2 是否都填了真实世界坐标，拆成多个小步骤（如分楼层、分墙）",
                        shape, volume, MAX_SHAPE_BLOCKS, minX, minY, minZ, maxX, maxY, maxZ));
            }
        }

        switch (shape) {
            case "fill" -> {
                // 实心填充：所有格子都放置
                for (int y = minY; y <= maxY; y++) {
                    for (int x = minX; x <= maxX; x++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            offsets.add(new int[]{x, y, z});
                            ids.add(id);
                        }
                    }
                }
            }
            case "wall" -> {
                // 空心墙体：只画前后左右 4 面，不画顶和底
                for (int y = minY; y <= maxY; y++) {
                    // 前后两面（z = minZ 和 z = maxZ），整行放置
                    for (int x = minX; x <= maxX; x++) {
                        offsets.add(new int[]{x, y, minZ});
                        ids.add(id);
                        if (maxZ > minZ) {
                            offsets.add(new int[]{x, y, maxZ});
                            ids.add(id);
                        }
                    }
                    // 左右两面（x = minX 和 x = maxX），跳过角落已放置的格子
                    for (int z = minZ + 1; z < maxZ; z++) {
                        offsets.add(new int[]{minX, y, z});
                        ids.add(id);
                        if (maxX > minX) {
                            offsets.add(new int[]{maxX, y, z});
                            ids.add(id);
                        }
                    }
                }
            }
            case "box" -> {
                // 空心外壳：6 个面都画，内部不填
                for (int y = minY; y <= maxY; y++) {
                    boolean isYEdge = (y == minY || y == maxY);
                    for (int x = minX; x <= maxX; x++) {
                        boolean isXEdge = (x == minX || x == maxX);
                        for (int z = minZ; z <= maxZ; z++) {
                            boolean isZEdge = (z == minZ || z == maxZ);
                            // 在任一边缘上就放置，内部跳过
                            if (isYEdge || isXEdge || isZEdge) {
                                offsets.add(new int[]{x, y, z});
                                ids.add(id);
                            }
                        }
                    }
                }
            }
            case "stairs" -> {
                // 楼梯：支持单段（旧格式）和多段旋转楼梯（path 数组）。
                //
                // 【单段格式】（向后兼容）
                // {"shape":"stairs","x1":0,"y1":1,"z1":0,"facing":"south","count":5,"id":"oak_stairs"}
                //
                // 【多段格式 path】（旋转楼梯）
                // {"shape":"stairs","id":"oak_stairs","path":[
                //   {"x":0,"y":1,"z":0,"facing":"south","count":4},
                //   {"facing":"east","count":4},
                //   {"facing":"north","count":4}
                // ]}
                // 第一段必须给 x,y,z 起点；后续段自动从上一段终点继续。
                // 衔接逻辑：下一段第一级 = 上一段终点 + 新方向1格 + 1y（转角和第一级合并）
                // 转角只占1格，紧凑；4段旋转后位置在起点附近(螺旋上升)
                // 每级楼梯自动挖开头顶 2 格 air，玩家不会撞天花板。

                java.util.Set<Long> dug = new java.util.HashSet<>();
                int curX, curY, curZ;

                if (stepObj.has("path") && stepObj.get("path").isJsonArray()) {
                    // ===== 多段旋转楼梯 =====
                    // 衔接逻辑：下一段第一级 = 上一段终点 + 新方向1格 + 1y（转角和第一级合并）
                    // 玩家路径：踩到终点(朝旧方向楼梯) → 转身 → 踩到终点+新方向1格+1y(朝新方向楼梯)
                    // 转角只占1格，紧凑；4段旋转后位置在起点附近(螺旋上升)
                    JsonArray pathArr = stepObj.getAsJsonArray("path");
                    curX = 0; curY = 0; curZ = 0;
                    boolean firstSeg = true;
                    for (JsonElement segElem : pathArr) {
                        if (!segElem.isJsonObject()) continue;
                        JsonObject seg = segElem.getAsJsonObject();
                        String segFacing = str(seg, "facing", "north");
                        int segCount = Math.min(intOr(seg, "count", 5), 64);

                        if (firstSeg) {
                            // 第一段：从给定起点开始
                            curX = intOr(seg, "x", 0);
                            curY = intOr(seg, "y", 0);
                            curZ = intOr(seg, "z", 0);
                            firstSeg = false;
                            int[] endPos = generateStairSegment(offsets, ids, airOffsets, airIds, dug,
                                    curX, curY, curZ, segFacing, segCount, id);
                            curX = endPos[0]; curY = endPos[1]; curZ = endPos[2];
                        } else {
                            // 后续段：generateStairSegment 现在返回【landing 平台】位置
                            // （末级前进方向 +1 格，Y 与末级相同）。下一段第一级应从
                            // landing + 新方向 1 格 + 1y 起（铁律 A：平台顶面→下一级踏面差 0.5）
                            int newDx = 0, newDz = 0;
                            switch (segFacing) {
                                case "north" -> newDz = -1;
                                case "south" -> newDz = 1;
                                case "east" -> newDx = 1;
                                case "west" -> newDx = -1;
                            }
                            int nextStartX = curX + newDx;
                            int nextStartY = curY + 1;
                            int nextStartZ = curZ + newDz;
                            // 【L1-2】landing 已由 generateStairSegment 生成，此处不再重复铺 3 格条。
                            // 原 E3 转角条已被 landing + 下方填实取代，几何更贴合铁律 A。
                            int[] endPos = generateStairSegment(offsets, ids, airOffsets, airIds, dug,
                                    nextStartX, nextStartY, nextStartZ, segFacing, segCount, id);
                            curX = endPos[0]; curY = endPos[1]; curZ = endPos[2];
                        }
                    }
                } else {
                    // ===== 单段直线楼梯（旧格式，向后兼容） =====
                    curX = intOr(stepObj, "x1", 0);
                    curY = intOr(stepObj, "y1", 0);
                    curZ = intOr(stepObj, "z1", 0);
                    String facing = str(stepObj, "facing", "north");
                    int count = Math.min(intOr(stepObj, "count", 5), 64);
                    // 【L1-3】末级 Y 对齐：AI 可传 target_y（目标楼板方块 Y），
                    // 框架自动调整 count 使末级 Y = target_y（铁律 B：末级踏面→楼板顶面差 0.5）。
                    // 末级 Y = startY + count - 1 = target_y → count = target_y - startY + 1
                    if (stepObj.has("target_y") && stepObj.get("target_y").isJsonPrimitive()) {
                        int targetY = intOr(stepObj, "target_y", curY);
                        int aligned = targetY - curY + 1;
                        if (aligned < 1) {
                            throw new IllegalArgumentException("stairs target_y=" + targetY
                                    + " 低于起点 y1=" + curY + "，末级会在起点下方，方向反了");
                        } else if (aligned > 64) {
                            // 【P2-A】超 64 格原静默退化成 5 级残楼梯，现在显式报错
                            throw new IllegalArgumentException("stairs target_y=" + targetY
                                    + " 距起点 y1=" + curY + " 相差 " + (aligned - 1)
                                    + " 格，超过单段上限 64。请拆成多段 path 或分多层 build_plan。");
                        }
                        count = aligned;
                    }
                    generateStairSegment(offsets, ids, airOffsets, airIds, dug,
                            curX, curY, curZ, facing, count, id);
                }
            }
            case "ladder" -> {
                // 梯子：从 (x1,y1,z1) 起，垂直向上，共 height 格。
                //
                // 参数：x1,y1,z1=起点；height=高度；facing=梯子背靠墙的反方向；
                //       id=ladder。
                //
                // 【facing 含义（Wiki 严格定义）】
                //   facing = "从梯子附着的方块（墙）到梯子的方向"
                //   即：facing = 墙的反方向 = 玩家攀爬时【背朝】的方向（玩家面朝墙爬）
                //   例：梯子贴北墙（墙在 z-1），梯子 facing=south（朝向 +z，远离墙）
                //       玩家爬梯子时面朝北（墙），背朝南（facing 方向）
                //   口诀：墙在哪边，facing 就是那边的反方向
                //         facing=north → 墙在南边（z+1）；facing=south → 墙在北边（z-1）
                //         facing=east  → 墙在西边（x-1）；facing=west  → 墙在东边（x+1）
                //
                // 【L1-1】facing 现在强制必填（不传抛异常）：
                //   原默认 "north" 硬编码，AI 不传则整条梯子全朝北，模型贴到对面墙，
                //   玩家必须从墙那一侧绕一圈爬——"绕一圈"根因。
                //   背墙实心校验由 L3 verify_path（有世界访问）做，这里只保证 facing 非空。
                //
                // 【L1-1 顶端 Y 对齐】AI 可传 target_y（目标楼板方块 Y），
                //   梯子最高一格 Y 应 = target_y + 1（楼板上方一格，人爬到顶能站上楼板）。
                //   原 height 固定，顶端常停在楼板下方 → "爬到顶头还在楼板底下"。
                //   传了 target_y 就自动算 height = target_y + 1 - y1 + 1。
                String facing = str(stepObj, "facing", null);
                if (facing == null || facing.isBlank()) {
                    throw new IllegalArgumentException("ladder 必须传 facing（墙→梯子方向）。"
                            + "墙在北边(z-1)→facing=south；墙在南边(z+1)→facing=north；"
                            + "墙在东边(x+1)→facing=west；墙在西边(x-1)→facing=east。"
                            + "不传会导致整条梯子全朝北，模型贴反、玩家绕圈爬。");
                }
                int y1l = y1;
                int height;
                int topY; // 梯子顶端 Y（target_y+1 或 y1+height-1）
                if (stepObj.has("target_y") && stepObj.get("target_y").isJsonPrimitive()) {
                    int targetY = intOr(stepObj, "target_y", y1l);
                    // 顶端 Y = target_y + 1，height = (target_y+1) - y1 + 1
                    height = (targetY + 1) - y1l + 1;
                    topY = targetY + 1;
                    if (height < 1) {
                        throw new IllegalArgumentException("ladder target_y=" + targetY
                                + " 低于起点 y1=" + y1l + "，方向反了");
                    }
                } else {
                    height = Math.min(intOr(stepObj, "height", 10), 64);
                    topY = y1l + height - 1;
                }
                String ladderId = id + "[facing=" + facing + "]";
                // 【P1-A】facing = 墙→梯子方向，故墙在 facing 的反方向。
                //   ① 背后补墙：每格梯子背后补一块实心方块，梯子永不掉落
                //   ② 正前方挖空：梯子 facing 那一侧（玩家背朝方向）挖 2 格净空，人贴墙直上
                //   ③ 顶端出口净空：梯子顶格上方 2 格挖空，配合 target_y+1 让人站上楼板
                int[] d = facingDelta(facingCode(facing)); // facing 方向（墙的反方向）
                int wx = x1 - d[0], wz = z1 - d[1];         // 墙的位置（facing 反方向）
                String wallId = derivePlatformId(id);       // 背后墙用同材质或 planks 变体
                java.util.Set<Long> dug = new java.util.HashSet<>();
                for (int i = 0; i < height; i++) {
                    int y = y1l + i;
                    // 梯子方块
                    offsets.add(new int[]{x1, y, z1});
                    ids.add(ladderId);
                    // ① 背后补墙（保证梯子有支撑不掉落）
                    offsets.add(new int[]{wx, y, wz});
                    ids.add(wallId);
                    // ② 正前方挖空（玩家爬行方向，facing 那一侧）2 格净空
                    digAir(airOffsets, airIds, dug, x1 + d[0], y, z1 + d[1]);
                    digAir(airOffsets, airIds, dug, x1 + d[0], y + 1, z1 + d[1]);
                }
                // ③ 顶端出口净空：梯子顶格上方 2 格挖空（人爬到顶能站上楼板）
                digAir(airOffsets, airIds, dug, x1, topY + 1, z1);
                digAir(airOffsets, airIds, dug, x1, topY + 2, z1);
            }
            case "spiral" -> {
                // 【修S′-1】legacy spiral 生成器下线：shape:spiral 别名到 stairwell(style=spiral, inner=2)。
                // 语义转换：spiral{x,y,z,height} == stairwell{x1=x, z1=z, from_y=y-1, to_y=y+height-1,
                //   style=spiral, inner=2}。第一级 Y = y（与提示词一致：楼板Y+1 起），末级 = y+height-1。
                // 生成质量与 stairwell 完全一致：真楼梯方块带 [facing=方向]、
                // 每级头顶自动 digAir 净空、2×2 footprint 正交相邻（玩家不掉落）。
                // 【缺材质】统一走 expandShape 顶部材质检查抛 E_NO_MATERIAL（对齐 door），
                // 禁止用默认材质开工——无 block 键的 spiral 曾静默放成实心柱（坏柱事故根因）。
                int cx = intOr(stepObj, "x", 0);
                int cz = intOr(stepObj, "z", 0);
                int startY = intOr(stepObj, "y", 0);
                int height = Math.min(intOr(stepObj, "height", 12), 64);
                if (height < 1) {
                    throw new IllegalArgumentException("spiral height 必须 >= 1，当前=" + height);
                }
                // 【v22·删表改推导】不再维护 facing 常量表（历史翻车根因：方向表被手抄三份）。
                // 位置循环 (0,0)->(1,0)->(1,1)->(0,1) 连续回绕，每级来向 = 上一位置→本位置，
                // facing = dirOf(增量)（实心半格朝上行方向，矮半格在踩上来的那侧）。
                // 第一级来向取"上一圈末级"(0,1)→(0,0)：入口在南侧，facing=north。
                int[][] positions = {{0,0},{1,0},{1,1},{0,1}};
                java.util.Set<Long> dug = new java.util.HashSet<>();
                java.util.List<int[]> stairSteps = new java.util.ArrayList<>();
                java.util.List<String> stairFacings = new java.util.ArrayList<>();
                for (int i = 0; i < height; i++) {
                    int y = startY + i;
                    int[] pos = positions[i % 4];
                    int sx = cx + pos[0];
                    int sz = cz + pos[1];
                    // 唯一真源推导：无表可抄反，翻转 bug 类别从物理上消灭
                    String facing = dirOf(pos[0] - positions[(i + 3) % 4][0],
                            pos[1] - positions[(i + 3) % 4][1]);
                    offsets.add(new int[]{sx, y, sz});
                    ids.add(id + "[facing=" + facing + "]");
                    // 【v21.2】净空 3 格（原来 2 格）：玩家 1.8 格高站在楼梯高半格侧时
                    // 头顶到 y+2.8，2 格净空会顶到上一级楼梯（"头顶楼梯刚好 2 格"实测上不去）
                    digAir(airOffsets, airIds, dug, sx, y + 1, sz);
                    digAir(airOffsets, airIds, dug, sx, y + 2, sz);
                    digAir(airOffsets, airIds, dug, sx, y + 3, sz);
                    stairSteps.add(new int[]{sx, y, sz});
                    stairFacings.add(facing);
                }
                // 【v22·生成期自检】翻转/跳级/顶部没对齐当场炸（E_STAIR_FACING_MISMATCH 拦网）
                validateStairSequence(stairSteps, stairFacings, startY + height - 1, dug, "spiral");
            }
            case "stairwell" -> {
                // 【L2】stairwell 求解器：AI 只声明意图，框架按铁律 A/B 自动生成楼梯+平台+净空。
                //
                // 参数（语义 API）：
                //   x1,z1=楼梯井一角（2×2 或 3×3 起始角）；from_y/to_y=起止楼板方块 Y；
                //   style=straight(直线)/switchback(回转)/spiral(螺旋)；
                //   id=楼梯方块；inner=内空尺寸(2 或 3，默认 3)
                //
                // 框架做的事：按 style 选型 → 按铁律生成每段楼梯（需要时含 landing）
                //            → 末级 Y 对齐 to_y → 挖净空 → 输出 reserved 区
                // AI 不再给坐标，只给"从几楼到几楼、什么风格"。
                int fromY = intOr(stepObj, "from_y", y1);
                int toY = intOr(stepObj, "to_y", fromY + 5);
                // 【v21.5】stairwell 必须定位：x1/z1 = 楼梯井洞口一角。
                // 旧实现缺省 0 → 楼梯生成到世界原点 (0,0)，E4 校验拦下后 AI 才转 spiral
                //（日志实锤：AI 第一次 stairwell 没传坐标，报 "起点 (0,0) 未被洞口覆盖"）。
                if (!stepObj.has("x1") || !stepObj.has("z1")) {
                    throw new IllegalArgumentException("stairwell 必须传 x1/z1（楼梯井起始角坐标）——"
                            + "它是语义 API 但需要定位：x1/z1 填楼梯井洞口一角，如 \"x1\":64,\"z1\":25；"
                            + "不传会默认 (0,0) 建到世界原点，必定被几何校验拦下。");
                }
                // 【P2】默认 style 改 spiral：旧默认 straight 是唯一硬要 facing 的分支，
                // 提示词到处推荐"用 stairwell"，模型只传 from_y/to_y 就撞"必须传 facing"报错。
                // spiral 默认 + inner=2 恰好配 2×2 楼板洞口，不传也能一步生成可走楼梯
                String style = str(stepObj, "style", "spiral");
                int inner = intOr(stepObj, "inner", 2);
                if (toY <= fromY) {
                    throw new IllegalArgumentException("stairwell to_y 必须 > from_y");
                }
                if (inner != 2 && inner != 3) {
                    throw new IllegalArgumentException("stairwell inner 只能 2 或 3");
                }
                java.util.Set<Long> dug = new java.util.HashSet<>();
                switch (style) {
                    case "spiral" -> {
                        // 螺旋：2×2 footprint，inner 必须为 2
                        int cx = x1, cy = fromY + 1, cz = z1;
                        int height = toY - fromY; // 末级 Y = cy + height - 1 = toY
                        int[][] positions = {{0,0},{1,0},{1,1},{0,1}};
                        // 【v22·删表改推导】同 shape=spiral：facing = dirOf(位置增量)，无表可抄反
                        java.util.List<int[]> stairSteps = new java.util.ArrayList<>();
                        java.util.List<String> stairFacings = new java.util.ArrayList<>();
                        for (int i = 0; i < height; i++) {
                            int y = cy + i;
                            int[] pos = positions[i % 4];
                            int sx = cx + pos[0], sz = cz + pos[1];
                            String fc = dirOf(pos[0] - positions[(i + 3) % 4][0],
                                    pos[1] - positions[(i + 3) % 4][1]);
                            offsets.add(new int[]{sx, y, sz});
                            ids.add(id + "[facing=" + fc + "]");
                            // 【v21.2】净空 3 格（同 shape=spiral）：防玩家顶到上一级楼梯
                            digAir(airOffsets, airIds, dug, sx, y + 1, sz);
                            digAir(airOffsets, airIds, dug, sx, y + 2, sz);
                            digAir(airOffsets, airIds, dug, sx, y + 3, sz);
                            stairSteps.add(new int[]{sx, y, sz});
                            stairFacings.add(fc);
                        }
                        // 【v22·生成期自检】末级必须精确落在 toY（=上层楼板 Y），翻转当场炸
                        validateStairSequence(stairSteps, stairFacings, toY, dug, "stairwell(spiral)");
                    }
                    case "switchback" -> {
                        // 回转梯：两段 + 平台，inner=3。段A 走 south，平台转角，段B 走 north
                        // 段长 = (toY - fromY) / 2，不足整除时末段用 generateStairSegment 的 target_y 对齐
                        int halfH = (toY - fromY) / 2;
                        if (halfH < 1) halfH = 1;
                        // 段A：从 (x1, fromY+1, z1) 向 south 走 halfH 级
                        int[] endA = generateStairSegment(offsets, ids, airOffsets, airIds, dug,
                                x1, fromY + 1, z1, "south", halfH, id);
                        // 段B：从 landing + north + 1y 起，走剩余级数到 toY
                        int remain = toY - (endA[1]) ; // 末级应到 toY
                        if (remain < 1) remain = 1;
                        generateStairSegment(offsets, ids, airOffsets, airIds, dug,
                                endA[0], endA[1] + 1, endA[2], "north", remain, id);
                    }
                    default -> {
                        // straight：单段直线，target_y 对齐 to_y
                        // 【P0-B】facing 必填（原写死 south → 门在北墙时第2/3级顶门框）
                        String swFacing = str(stepObj, "facing", null);
                        if (swFacing == null || swFacing.isBlank()) {
                            throw new IllegalArgumentException("stairwell style=straight 必须传 facing（上行方向）。"
                                    + "门在北墙(z 小)→楼梯应 facing=south 从门口往里上；"
                                    + "反着会让第2、3级台阶顶在门框上。");
                        }
                        generateStairSegment(offsets, ids, airOffsets, airIds, dug,
                                x1, fromY + 1, z1, swFacing, toY - fromY, id);
                    }
                }
            }
            case "door" -> {
                // 门：在 (x1,y1,z1) 放置 2 格高的门（下半门 y1，上半门 y1+1）。
                //
                // 参数：x1,y1,z1=门底部位置；id=门方块（如 oak_door）；
                //       facing（可选）=门朝向 = 门内侧方向 = 玩家从外走入时面朝方向；
                //       hinge（可选）=门轴侧（left/right，默认 left）。
                //
                // 【facing 含义（Wiki）】
                //   facing = "门内侧（'inside'）朝向"
                //   放置时 = 玩家面朝方向。玩家从门外走入室内，面朝室内方向 = facing
                //   例：门在北墙 z=-161，玩家从室外(z<-161)走入室内(z>-161)，朝南走 → facing=south
                //       门在东墙 x=285，玩家从室外(x>285)走入室内(x<285)，朝西走 → facing=west
                //
                // 【不传 facing 的后果】
                //   facing 默认 north，门板与沿 x 走向的墙（东/西墙）垂直，关不严；
                //   沿 z 走向的墙（南/北墙）勉强能关但门轴全在左。
                //   强烈建议 LLM 传 facing 参数。
                //
                // 生成两个方块：y1 和 y1+1，都用同一个 door id（含 facing/hinge 属性）。
                String doorFacing = str(stepObj, "facing", null);
                String hinge = str(stepObj, "hinge", null);
                StringBuilder idBuilder = new StringBuilder(id);
                if (doorFacing != null) {
                    idBuilder.append("[facing=").append(doorFacing);
                    if (hinge != null) idBuilder.append(",hinge=").append(hinge);
                    idBuilder.append("]");
                } else if (hinge != null) {
                    idBuilder.append("[hinge=").append(hinge).append("]");
                }
                String doorId = idBuilder.toString();
                offsets.add(new int[]{x1, y1, z1});
                ids.add(doorId);
                offsets.add(new int[]{x1, y1 + 1, z1});
                ids.add(doorId);
                // 【门前清障】门外方向（facing 反方向）1 格、门洞上下 2 层置 air——
                // 用户要求：门前只清 1 格（可能建洞穴屋，清多了破坏设计）。
                // 树/山体等自然方块被 air 直接覆盖，不触发确认（air 不算实体覆盖）
                if (doorFacing != null) {
                    int[] dd = facingDelta(facingCode(doorFacing)); // 门内侧方向
                    int px = x1 - dd[0], pz = z1 - dd[1];           // 门外方向
                    airOffsets.add(new int[]{px, y1, pz});
                    airIds.add("minecraft:air");
                    airOffsets.add(new int[]{px, y1 + 1, pz});
                    airIds.add("minecraft:air");
                }
            }
            case "floor" -> {
                // 楼板+楼梯井：铺满 x1z1~x2z2 的 y1 层，但跳过 hx1~hx2, hz1~hz2 的矩形洞口。
                // 参数：x1,y1,z1,x2,z2=楼板范围（y2 忽略，固定用 y1）；hx1,hz1,hx2,hz2=洞口范围（可选）。
                // 注意：y2 默认 0，会导致 minY=0 错误，所以 floor 固定用 y1。
                //
                // 【几何校验】洞口必须严格落在室内 [x1+1,x2-1]×[z1+1,z2-1]，
                // 否则会破坏外墙（洞口压到墙线 → 外墙每层出现缺口）。
                // 不合法直接抛异常让 LLM 修正。
                int floorY = y1;
                int hx1 = intOr(stepObj, "hx1", Integer.MIN_VALUE);
                int hz1 = intOr(stepObj, "hz1", Integer.MIN_VALUE);
                int hx2 = intOr(stepObj, "hx2", Integer.MIN_VALUE);
                int hz2 = intOr(stepObj, "hz2", Integer.MIN_VALUE);
                // 归一化洞口坐标
                int hMinX = Math.min(hx1, hx2), hMaxX = Math.max(hx1, hx2);
                int hMinZ = Math.min(hz1, hz2), hMaxZ = Math.max(hz1, hz2);
                boolean hasHole = (hx1 != Integer.MIN_VALUE && hz1 != Integer.MIN_VALUE
                        && hx2 != Integer.MIN_VALUE && hz2 != Integer.MIN_VALUE);
                if (hasHole) {
                    // 洞口必须严格在室内（不能压外墙线）
                    if (hMinX < minX + 1 || hMaxX > maxX - 1
                            || hMinZ < minZ + 1 || hMaxZ > maxZ - 1) {
                        throw new IllegalArgumentException(
                                "floor 洞口 [" + hMinX + "~" + hMaxX + ", "
                                        + hMinZ + "~" + hMaxZ + "] 压到外墙线（室内应严格在 ["
                                        + (minX + 1) + "~" + (maxX - 1) + ", "
                                        + (minZ + 1) + "~" + (maxZ - 1) + "]），"
                                        + "请把洞口缩进室内至少 1 格");
                    }
                    // 洞口尺寸匹配楼梯类型（P1-N2）：spiral 2×2 / 直线 stairs 3×3 / L/U 多段 5×5。
                    // 只拒绝 >5×5（大掉落洞）和 4×4（不对应任何楼梯类型）；
                    // 2×2/3×3/5×5 的楼梯配对由 parseBuildPlan 的 E4 交叉校验负责。
                    int holeSx = hMaxX - hMinX + 1, holeSz = hMaxZ - hMinZ + 1;
                    if (holeSx > 5 || holeSz > 5) {
                        throw new IllegalArgumentException(
                                "floor 洞口过大（" + holeSx + "×" + holeSz + "），每层楼板会留大掉落洞！"
                                        + "标准洞口：spiral 2×2；直线 stairs 3×3；L/U 型多段 stairs 5×5。"
                                        + "如确需大洞口（中庭/天井），请改用 fill 铺满楼板，再手动用 air 挖洞。");
                    }
                    if (holeSx == 4 || holeSz == 4) {
                        throw new IllegalArgumentException(
                                "floor 洞口 " + holeSx + "×" + holeSz + " 不对应任何楼梯类型，"
                                        + "只允许 2×2（spiral）/ 3×3（直线 stairs）/ 5×5（L/U 多段 stairs）。");
                    }
                }
                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        // 跳过楼梯井洞口
                        if (hasHole && x >= hMinX && x <= hMaxX && z >= hMinZ && z <= hMaxZ) continue;
                        offsets.add(new int[]{x, floorY, z});
                        ids.add(id);
                    }
                }
            }
            default -> {
                // 未知 shape，不展开（parseBlocksArray 仍会处理 blocks 数组）
            }
        }
    }

    /**
     * 从 JSON 对象中解析 blocks 数组，填充到 offsets 和 ids 列表。
     * 容错：跳过格式错误的元素，不中断解析。
     * @return 软扫描警告列表（手写 stairs 连跑 facing 不符等；空 = 无需提示）
     */
    private static java.util.List<String> parseBlocksArray(JsonObject obj, List<int[]> offsets, List<String> ids) {
        if (!obj.has("blocks") || !obj.get("blocks").isJsonArray()) return new java.util.ArrayList<>();
        JsonArray arr = obj.getAsJsonArray("blocks");
        for (JsonElement e : arr) {
            try {
                if (!e.isJsonArray()) continue;
                JsonArray b = e.getAsJsonArray();
                if (b.size() < 4) continue;
                // 坐标必须是数字
                int dx = b.get(0).getAsInt();
                int dy = b.get(1).getAsInt();
                int dz = b.get(2).getAsInt();
                // 方块 ID 必须是字符串
                String id = b.get(3).getAsString();
                if (id == null || id.isBlank()) continue;
                offsets.add(new int[]{dx, dy, dz});
                ids.add(id);
            } catch (Exception ignored) {
                // 跳过格式错误的单个方块，继续解析下一个
            }
        }
        return scanRawStairRun(offsets, ids);
    }

    /**
     * 【v22·软扫描】识别手写 blocks 数组里"y 连续 +1 且正交相邻"的楼梯连跑，
     * facing 与行进方向不符时给出警告（不硬拦——装饰性/单级楼梯会被误伤，软反馈喂回模型）。
     * 与 skipped_reasons 同类的提示通道，模型读到后可自行修正，但不阻塞放置。
     * @return 警告文本列表（空 = 无需提示）
     */
    private static java.util.List<String> scanRawStairRun(List<int[]> offsets, List<String> ids) {
        java.util.List<String> warns = new java.util.ArrayList<>();
        if (offsets == null || offsets.size() < 2) return warns;
        // 收集带 facing 属性的 stairs 块：{x,y,z} 与对应 facing 字符串
        java.util.List<int[]> blocks = new java.util.ArrayList<>();
        java.util.List<String> facings = new java.util.ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            String raw = ids.get(i);
            if (raw.indexOf("_stairs") < 0) continue;
            int br = raw.indexOf('[');
            if (br < 0) continue; // 没写 facing 的楼梯块不参与连跑判定
            String props = raw.substring(br + 1);
            if (props.endsWith("]")) props = props.substring(0, props.length() - 1);
            String fc = null;
            for (String part : props.split(",")) {
                String t = part.trim();
                if (t.startsWith("facing=")) { fc = t.substring(7); break; }
            }
            if (fc == null || facingCode(fc) < 0) continue;
            int[] p = offsets.get(i);
            blocks.add(new int[]{p[0], p[1], p[2]});
            facings.add(fc);
        }
        if (blocks.size() < 2) return warns;
        // 按 Y 升序稳定排序，贪心切分"y 连续+1 且正交相邻"的连跑段（同 y 的多块会断开段）
        Integer[] order = new Integer[blocks.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, java.util.Comparator.comparingInt(i -> blocks.get(i)[1]));
        int start = 0;
        for (int i = 1; i <= order.length; i++) {
            boolean end = i == order.length;
            if (!end) {
                int[] prev = blocks.get(order[i - 1]);
                int[] cur = blocks.get(order[i]);
                boolean contiguous = cur[1] - prev[1] == 1
                        && Math.abs(cur[0] - prev[0]) + Math.abs(cur[2] - prev[2]) == 1;
                if (contiguous) continue;
            }
            // [start, i) 是一段连跑
            int runLen = i - start;
            if (runLen >= 2) {
                java.util.List<String> segWarns = new java.util.ArrayList<>();
                for (int k = start + 1; k < i; k++) {
                    int[] prev = blocks.get(order[k - 1]);
                    int[] cur = blocks.get(order[k]);
                    String expect = dirOf(cur[0] - prev[0], cur[2] - prev[2]);
                    String got = facings.get(order[k]);
                    if (!expect.equals(got)) {
                        segWarns.add("第 " + (k - start + 1) + " 级 (" + cur[0] + "," + cur[1] + "," + cur[2]
                                + ") facing=" + got + " 与行进方向 " + expect + " 不符");
                    }
                }
                if (!segWarns.isEmpty()) {
                    int[] a = blocks.get(order[start]);
                    int[] b = blocks.get(order[i - 1]);
                    warns.add("[stairs 软检] 手写 blocks 中有一段 " + runLen + " 级楼梯连跑（从 ("
                            + a[0] + "," + a[1] + "," + a[2] + ") 到 (" + b[0] + "," + b[1] + "," + b[2]
                            + ")）：" + String.join("；", segWarns)
                            + "。facing=实心半格朝向=上行方向（矮半格在踩上来的那侧），请修正后再发。");
                }
            }
            start = i;
        }
        return warns;
    }

    private static String str(JsonObject o, String k, String d) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : d;
    }
    private static int intOr(JsonObject o, String k, int d) {
        try { return o.has(k) ? o.get(k).getAsInt() : d; } catch (Exception e) { return d; }
    }

    /** 【P1-1】方块 ID 归一：剥掉 minecraft: 前缀与 [facing=…] 属性段，得到纯 ID（如 quartz_stairs） */
    private static String baseBlockId(String raw) {
        String s = raw == null ? "" : raw;
        int br = s.indexOf('[');
        if (br >= 0) s = s.substring(0, br);
        if (s.startsWith("minecraft:")) s = s.substring("minecraft:".length());
        return s.trim();
    }

    /** 【L1-5】facing 字符串转方向码：north=0 south=1 east=2 west=3，便于 reserved volume 计算 */
    private static int facingCode(String f) {
        return switch (f) {
            case "north" -> 0;
            case "south" -> 1;
            case "east" -> 2;
            case "west" -> 3;
            default -> -1;
        };
    }

    /** 【L1-5】方向码 → (dx, dz)。north: dz=-1; south: +1; east: dx=+1; west: -1 */
    private static int[] facingDelta(int code) {
        return switch (code) {
            case 0 -> new int[]{0, -1};
            case 1 -> new int[]{0, 1};
            case 2 -> new int[]{1, 0};
            case 3 -> new int[]{-1, 0};
            default -> new int[]{0, 0};
        };
    }

    /**
     * 【v22·唯一真源】facing 推导：facing = 实心半格朝向 = 上行位移方向
     * （玩家从上一级踩上本级的行进方向，Wiki: stairs 的 full-block side faces）。
     * <p>dx/dz = 当前位置 - 上一位置。dz&lt;0→north、dz&gt;0→south，其余 dx&gt;0→east、否则 west。
     * <p>【防抄表】spiral/stairwell 两个楼梯分支都只调它，不再维护任何 facing 常量表——
     * 历史上同一张方向表被手抄三份（spiral 分支/stairwell 分支/JSON 教程），抄反一次就整体
     * 翻转 180° 且两边"自洽地一起错"、无任何交叉验证发现。此函数消灭"表"这个 bug 类别：
     * 没有表可以写反，facing 永远是位置增量的函数。
     */
    public static String dirOf(int dx, int dz) {
        if (dz < 0) return "north";
        if (dz > 0) return "south";
        return dx > 0 ? "east" : "west";
    }

    /**
     * 【v22·生成期几何自检】对有序台阶序列做纯算术校验（微秒级、零 LLM、不耗步数），
     * 任一不符立即 throw（带索引）——朝向翻转/跳级/顶部没对齐在生成的一瞬间爆炸，
     * 而不是等玩家进游戏爬不上去才发现（比 walk 验收早整整一个回合）。
     * <ul>
     *   <li>1) 相邻正交：|dx|+|dz|==1，禁止对角/并排（玩家会掉下去）；</li>
     *   <li>2) 逐级 +1：y[i]-y[i-1]==1，不许并级/跳级；</li>
     *   <li>3) facing 匹配行进方向：facing[i]==dirOf(dx,dz)——v21 那次 180° 翻转在这里当场炸；</li>
     *   <li>4) 末级精确到达目标楼板 Y；</li>
     *   <li>5) 每级头顶 3 格净空已在 dug 集合内（防撞上一级/天花板）。</li>
     * </ul>
     *
     * @param steps   台阶本体序列 [x,y,z]（不含下方支撑块、转角平台）
     * @param facings 与 steps 对齐的 facing 列表
     * @param targetTopY 末级应到达的目标楼板方块 Y
     * @param dug     头顶净空集合（可 null=跳过净空检查）
     * @param what    错误前缀（如 "spiral"、"stairwell(spiral)"），用于定位
     */
    private static void validateStairSequence(java.util.List<int[]> steps, java.util.List<String> facings,
                                              int targetTopY, java.util.Set<Long> dug, String what) {
        if (steps.isEmpty()) {
            throw new IllegalArgumentException(what + " 台阶序列为空");
        }
        if (steps.size() != facings.size()) {
            throw new IllegalArgumentException(what + " 台阶数(" + steps.size() + ")与 facing 数("
                    + facings.size() + ")不一致");
        }
        // 1)+2)+3)：从第 2 级起逐级校验（第 1 级的来向由 dirOf 在生成时推导保证，无表可错）
        for (int i = 1; i < steps.size(); i++) {
            int[] p = steps.get(i), q = steps.get(i - 1);
            int dx = p[0] - q[0], dz = p[2] - q[2], dy = p[1] - q[1];
            if (Math.abs(dx) + Math.abs(dz) != 1) {
                throw new IllegalArgumentException(what + " step[" + i + "] 与上一级不正交相邻"
                        + "（dx=" + dx + " dz=" + dz + "，必须 |dx|+|dz|==1，禁止对角/并排——玩家会掉下去）");
            }
            if (dy != 1) {
                throw new IllegalArgumentException(what + " step[" + i + "] Y 跳变=" + dy
                        + "（必须逐级 +1，不许并级/跳级）");
            }
            String expect = dirOf(dx, dz);
            if (!expect.equals(facings.get(i))) {
                // 关键防线：v21 那次整体 180° 翻转在这里当场炸，jar 根本发不出去
                throw new IllegalArgumentException(what + " step[" + i + "] facing=" + facings.get(i)
                        + " 但行进方向=" + expect + "（E_STAIR_FACING_MISMATCH：楼梯朝向整体反了 180°）");
            }
        }
        // 4) 末级必须精确到达目标楼板 Y
        int lastY = steps.get(steps.size() - 1)[1];
        if (lastY != targetTopY) {
            throw new IllegalArgumentException(what + " 末级 Y=" + lastY + " 未对齐目标楼板 Y=" + targetTopY
                    + "（差 " + (targetTopY - lastY) + " 格，顶部接不上或插进楼板）");
        }
        // 5) 每级头顶 3 格净空必须已挖（玩家 1.8 格高 + 高半格，2 格会撞上一级）
        if (dug != null) {
            for (int i = 0; i < steps.size(); i++) {
                int[] s = steps.get(i);
                for (int o = 1; o <= 3; o++) {
                    if (!dug.contains(BlockPos.asLong(s[0], s[1] + o, s[2]))) {
                        throw new IllegalArgumentException(what + " step[" + i + "] 上方 " + o
                                + " 格 (" + s[0] + "," + (s[1] + o) + "," + s[2] + ") 未在净空集合内（会撞头）");
                    }
                }
            }
        }
    }

    /**
     * 【第1批】从几何校验错误消息提取错误码（patch_steps 用）。
     * 根据消息内容归类：体积/洞口/方向/参数，找不到归类时用 GENERIC。
     */
    private static String geomErrorCode(String msg) {
        if (msg == null) return "GENERIC";
        if (msg.contains("体积")) return "VOLUME";
        if (msg.contains("洞口") || msg.contains("掉落缝")) return "HOLE";
        if (msg.contains("facing") || msg.contains("方向")) return "FACING";
        if (msg.contains("target_y") || msg.contains("低于起点")) return "ALIGN";
        if (msg.contains("门") || msg.contains("净空")) return "DOOR";
        if (msg.contains("height") || msg.contains("层级")) return "HEIGHT";
        return "GENERIC";
    }

    /**
     * 从楼梯方块 ID 推导转角平台方块 ID。
     * oak_stairs → oak_planks，stone_brick_stairs → stone_bricks，cobblestone_stairs → cobblestone。
     * 通用规则：去掉 _stairs 后缀，oak/spruce/birch 等木质楼梯 → _planks，其他 → 去掉 _stairs。
     * 用于多段 stairs 路径的转角平台（玩家走到转角时有立足点，不会悬空）。
     */
    private static String derivePlatformId(String stairId) {
        // 去掉 facing 属性（如 oak_stairs[facing=south] → oak_stairs）
        String base = stairId.split("\\[")[0];
        if (base.endsWith("_stairs")) {
            String material = base.substring(0, base.length() - 7); // 去掉 _stairs
            // 木质楼梯用 _planks，石质用原方块名
            if (material.equals("oak") || material.equals("spruce") || material.equals("birch")
                    || material.equals("jungle") || material.equals("acacia")
                    || material.equals("dark_oak") || material.equals("mangrove")
                    || material.equals("cherry") || material.equals("bamboo")
                    || material.equals("crimson") || material.equals("warped")) {
                return material + "_planks";
            }
            // stone_brick_stairs → stone_bricks；其他石头类用 material 本身
            if (material.equals("stone_brick")) return "stone_bricks";
            if (material.equals("nether_brick")) return "nether_bricks";
            if (material.equals("red_sandstone")) return "red_sandstone";
            if (material.equals("sandstone")) return "sandstone";
            if (material.equals("quartz")) return "quartz_block";
            if (material.equals("purpur")) return "purpur_block";
            if (material.equals("prismarine")) return "prismarine";
            if (material.equals("dark_prismarine")) return "dark_prismarine";
            if (material.equals("deepslate_brick")) return "deepslate_bricks";
            return material.isEmpty() ? "stone" : material;
        }
        return "stone"; // 兜底
    }

    /**
     * 生成一段直线楼梯，自动挖开头顶 2 格 air。
     *
     * <p>air 写入 airOffsets/airIds 独立列表，preparePlace 阶段会做实体优先去重，
     * 避免后写的 air 覆盖先写的实体方块（如墙、楼板）。
     *
     * @param offsets      输出：实体方块相对坐标列表
     * @param ids          输出：实体方块 ID 列表
     * @param airOffsets   输出：air 方块相对坐标列表（与实体方块分开）
     * @param airIds       输出：air 方块 ID 列表
     * @param dug          已挖开位置去重集合
     * @param startX       起点相对坐标
     * @param startY       起点高度
     * @param startZ       起点相对坐标
     * @param facing       楼梯延伸方向（north=-z, south=+z, east=+x, west=-x）
     * @param count        楼梯级数
     * @param stairId      楼梯方块基础 ID（不含 facing，如 oak_stairs）
     * @return 终点相对坐标 [x, y, z]
     */
    private static int[] generateStairSegment(List<int[]> offsets, List<String> ids,
                                               List<int[]> airOffsets, List<String> airIds,
                                               java.util.Set<Long> dug,
                                               int startX, int startY, int startZ,
                                               String facing, int count,
                                               String stairId) {
        int dx = 0, dz = 0;
        switch (facing) {
            case "north" -> dz = -1;
            case "south" -> dz = 1;
            case "east" -> dx = 1;
            case "west" -> dx = -1;
        }
        String fullStairId = stairId + "[facing=" + facing + "]";
        // 【L1-4】solid_underside：楼梯下方填实，避免浮空台阶——真实楼梯底下是实心。
        // 用与楼梯同材质（或其 planks 变体）填到本段起始 Y 之下，覆盖楼梯正下方一列。
        // 这里只在 generateStairSegment 内部填"本段楼梯正下方一格"，跨段/到楼板的填实
        // 由调用方按楼板 Y 决定（见 stairs shape 段末的对齐逻辑）。
        String solidId = derivePlatformId(stairId);
        int lastX = startX, lastY = startY, lastZ = startZ;
        java.util.List<int[]> stairSteps = new java.util.ArrayList<>();
        java.util.List<String> stairFacings = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            int sx = startX + dx * i;
            int sy = startY + i;
            int sz = startZ + dz * i;
            // 楼梯方块（带 facing 属性）
            offsets.add(new int[]{sx, sy, sz});
            ids.add(fullStairId);
            // 【L1-4】楼梯下方填实心方块（sy-1 处），避免悬空台阶
            offsets.add(new int[]{sx, sy - 1, sz});
            ids.add(solidId);
            // 【v21.2】净空 3 格（原来 2 格）：玩家 1.8 格高 + 楼梯高半格，2 格会撞上一级
            digAir(airOffsets, airIds, dug, sx, sy + 1, sz);
            digAir(airOffsets, airIds, dug, sx, sy + 2, sz);
            digAir(airOffsets, airIds, dug, sx, sy + 3, sz);
            stairSteps.add(new int[]{sx, sy, sz});
            stairFacings.add(facing);
            lastX = sx; lastY = sy; lastZ = sz;
        }
        // 【v22·生成期自检】直线楼梯同样过几何校验（facing 由本段方向决定，恒一致——
        // 防御性拦网：将来若有人改方向/计数的衔接逻辑，跳级/顶部没对齐会当场炸）
        validateStairSequence(stairSteps, stairFacings, startY + count - 1, dug, "stairs(" + facing + ")");
        // 【L1-2】landing 平台：段末尾插一格平台，Y 与最后一级相同（不是 +1）。
        // 铁律 A：转角平台顶面 Y = 末级踏面 Y + 0.5 → 平台方块 Y = 末级 Y（顶面 +1.0，差 0.5 平滑）。
        // 平台在末级前进方向 +1 格处，上方同样挖 2 格净空。
        int landX = lastX + dx;
        int landY = lastY;
        int landZ = lastZ + dz;
        offsets.add(new int[]{landX, landY, landZ});
        ids.add(solidId);
        // 【v21.2】平台上方同样挖 3 格净空（防转角撞头）
        digAir(airOffsets, airIds, dug, landX, landY + 1, landZ);
        digAir(airOffsets, airIds, dug, landX, landY + 2, landZ);
        digAir(airOffsets, airIds, dug, landX, landY + 3, landZ);
        // 返回 landing 位置（下一段应从 landing + 新方向 + 1y 起）
        return new int[]{landX, landY, landZ};
    }

    /**
     * 在指定位置放 air（挖开方块），用 Set 去重避免重复添加。
     * 写入独立 airOffsets 列表，与实体方块分开，
     * preparePlace 阶段会做实体优先去重——同一位置若已有实体方块，air 会被丢弃，
     * 避免后写的 air 覆盖已建的墙/楼板（顺序性 bug 的根因）。
     */
    private static void digAir(List<int[]> airOffsets, List<String> airIds,
                                java.util.Set<Long> dug, int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        if (dug.add(key)) {
            airOffsets.add(new int[]{x, y, z});
            airIds.add("air");
        }
    }
}
