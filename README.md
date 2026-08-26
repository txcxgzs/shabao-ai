# 沙包 AI (Shabao AI Companion)

> 你的 Minecraft 世界里，一个**会思考、会规划、会自己做决定**的 AI 队友。
>
> 面向 Minecraft **1.21.1** 的 Fabric Mod：世界里存在一个真实的 AI 玩家 NPC，接入任意 OpenAI 兼容的大模型（DeepSeek / 豆包 / GLM / Qwen / [OrcaRouter](https://www.orcarouter.ai)……），自主完成**规划 → 侦查 → 建造 → 验收**的完整闭环，还能陪你聊天、语音互动、打怪、探索、记录一切。

## ✨ 核心亮点

- 🧠 **常驻 Agent（v22 Runtime）**：AI 队友常驻在线而非一次性问答机——跟随 / 插火把 / 危险警戒等日常由本地技能自动执行（零模型调用）；可设定常驻目标（跟随 / 建造 / 挖矿协助 / 自由陪伴 / 探索），卡住自动重规划，还会主动搭话。
- 🏗️ **一句话建造**：`build_plan` 用 shape 指令（fill / wall / box / floor / door / stairs / spiral / stairwell / ladder / clear）一次返回完整建筑计划，一个 15×15 三层小屋只需几行 JSON；开工前有绿色方块预览。
- 🌍 **自动夷平地形**：`clear` 指令削高 + 填平低洼到地表中位数，地基不再悬空、不挖深坑；门前自动清障 1 格。
- 🪜 **专业楼梯工程**：2×2 紧凑螺旋楼梯（正确朝向 + 支撑 + 净空）、直线 / 回转楼梯、`verify_path` 3D 可达性自检（BFS）。
- 🗺️ **结构定位**：`locate_structure` 参数化工具定位村庄 / 要塞 / 海底神殿 / 林地府邸 / 远古城市 / 试炼密室 / 废弃矿井 / 废弃传送门，找到后可带路或传送。
- 💾 **多层记忆**：建筑记忆落盘（楼层 Y、累计方块、坐标）+ 情景记忆 `recall`（跨会话自然语言检索）+ 对话历史持久化。
- 💬 **语音互动**：按住 `V` 说话（ASR），AI 回复用 AI 语音朗读（TTS）。
- 💑 **长期关系**：从朋友到伴侣的关系推进，牵手 / 拥抱 / 亲吻身体互动，互动权限可配置。
- 🎨 **自定义形象**：内置皮肤库 + 本地 PNG 自定义皮肤，角色名可改。
- 🛡️ **安全护栏**：参数化指令工具 + 指令白名单（管理指令一律拦截）、覆盖确认、方块预算、越界拦截、家具嵌墙 / 床配对 / 附着物支撑校验。
- 🎯 **可换大脑**：任意 OpenAI 兼容端点，`reasoningEffort` 档位控制思考强度（low / medium / high / max），流式输出实时可见。

## 🎮 使用方式

1. **进服即陪伴**：进入世界后 AI 伙伴会自动生成，无需手动召唤；`directChatMode=true` 时，聊天框消息会自动转发给 AI 处理（聊天内容照常广播给其他玩家）。
2. **试试这些**：
   - `帮我在这建一栋 15×15 的三层小屋，先清平地形`
   - `把附近的僵尸清一清`
   - `带我去最近的村庄` / `去 2 楼看看`
   - `在这等我`（暂停目标，说"继续吧"恢复）
3. **语音**：按住 `V` 录音（最长 30 秒），松开自动识别并交给 AI；AI 回复自动朗读。

### 常用命令

| 命令 | 说明 |
|---|---|
| `/ai spawn` | 手动生成 / 刷新 AI 伙伴（进服会自动生成，一般用不到） |
| `/ai clear` | 拆除该玩家最近一次建筑 |
| `/ai reload` | 重载配置（管理员） |
| `/ai memory clear` | 清空建筑记忆 |

## 🛠️ 安装

**想直接玩？** 去 [Releases](https://github.com/txcxgzs/shabao-ai/releases) 下载最新版 JAR，放进 `mods/` 文件夹即可。首次启动生成 `config/shabao-ai.json`，配置请在游戏内模组菜单里编辑。

### 依赖模组（安装前请备齐）

| 模组 | 版本要求 | 必需 | 用途 |
|---|---|---|---|
| Minecraft | 1.21.1 | 必需 | 游戏本体版本 |
| Fabric Loader | 0.19.3+ | 必需 | Fabric 加载器（安装器会一并装好） |
| Fabric API | 0.116.x | 必需 | 核心 API，本模组强依赖 |
| Cloth Config | ≥ 15.0.0 | 必需 | 游戏内配置界面（本模组强依赖） |
| ModMenu | ≥ 11.0.0 | 推荐 | 模组列表 + 配置入口（装了才能直接在模组菜单里改配置） |

> 用 [Modrinth App](https://modrinth.com/app) 或 Prism Launcher 一键安装时，把本模组的依赖勾上即可自动补齐；手动安装时记得单独下载上面的 Fabric API 与 Cloth Config。

**开发者自行构建**（不建议普通玩家使用）：环境要求 Java 21、Fabric Loader 0.19.3+、Fabric API 0.116.x、ModMenu + Cloth Config ≥ 15.0.0。

```bash
./gradlew remapJar
```

产物位于 `build/libs/shabao-ai-companion-<版本>.jar`。

## ⚙️ 配置（游戏内模组菜单）

**绝大多数配置都可以在游戏内编辑，无需手动改文件**：模组列表（ModMenu）→ 沙包 AI → 配置
（Cloth Config 界面），分四页：AI 角色（称呼 / 皮肤）、通用 LLM、语音 ASR/TTS、游戏行为。
保存后写入 `config/shabao-ai.json` 并即时生效。

等价的配置文件结构如下（一般不需要手改）：

```jsonc
{
  "llm": {                        // 任意 OpenAI 兼容端点
    "baseUrl": "https://api.example.com/v1",
    "apiKey": "仅保存在本机，不要提交",
    "model": "deepseek-chat"
  },
  "asr": { /* 语音识别：默认 MiMo V2.5，Base URL/模型可换成任意兼容端点 */ },
  "tts": { /* 语音合成：默认 MiMo V2.5，Base URL/模型可换成任意兼容端点 */ },
  "agentMaxSteps": 200,           // Agent 最大步数
  "maxBuildBlocks": 12000,        // 单次建造方块上限
  "reasoningEffort": "high",      // 思考强度 low/medium/high/max
  "useToolCalling": true,         // 原生 tool calling
  "streamOutput": true,           // 流式输出（默认开启，/ai stream 可切换）
  "confirmOverwriteOnly": true,   // 只在地形/方块覆盖时确认
  "allowSurvivalBuilding": false, // 默认拒绝生存模式自动建造
  "directChatMode": true          // 普通聊天直接进 AI
}
```

> LLM 端点需兼容 `POST {baseUrl}/chat/completions`，支持工具调用可选。像 [OrcaRouter](https://www.orcarouter.ai)（`https://api.orcarouter.ai/v1`）这类 OpenAI 兼容网关，一个 key 即可接入多模型并自动路由/容灾。语音 ASR/TTS 默认使用 MiMo
> （`https://api.xiaomimimo.com/v1`，模型 `mimo-v2.5-asr` / `mimo-v2.5-tts`），也支持任意返回
> `choices[0].message.content`（ASR）或 `choices[0].message.audio.data`（TTS，base64 WAV）的
> OpenAI 兼容语音端点。建议在创造模式和可信世界启用自动建造。

## 🧱 架构概览

```
Minecraft 世界
   └── CompanionEntity（世界中可见的 AI 玩家实体，寻路/跟随/交互/渲染）
         ├── AgentExecutor（LLM 决策：多步循环 + 会话历史 + todo + 工具定义）
         │     ├── AgentAction         动作解析（tool calling + shape 几何校验）
         │     ├── EnvironmentScanner  环境感知（地面基准/地形统计/门前探测/候选地基）
         │     ├── BuildMemory         建筑记忆（落盘持久化）
         │     ├── Block/BuildingKnowledge  方块百科 + 建筑教程知识库
         │     ├── BlockCodec          方块编解码 + 自然方块判定
         │     └── LlmClient           大模型客户端（流式/思考/tool calling）
         └── AgentRuntime（常驻调度器，每 tick 心跳）
               ├── SkillManager    本地技能（FollowSkill/TorchAssistSkill/SafetySkill，0 次 LLM）
               ├── GoalManager     常驻目标状态机（follow/build/mine_assist/companion/explore）
               ├── EventBus        事件唤醒 LLM（危险/受伤/卡住/目标完成/社交空闲）
               ├── BrainScheduler  每玩家串行 Brain 调度
               ├── EpisodicMemory  情景记忆（跨会话 recall）
               ├── RelationshipManager / InteractionManager  关系与身体互动
               └── CognitionPulse  认知节奏（local/event/adaptive/active）
```

核心设计：
- **世界坐标**：所有坐标用世界绝对坐标，AI 所见即所得，无需原点转换。
- **shape 指令**：几何由框架生成（楼梯朝向 / 支撑 / 净空 / 洞口交叉校验），模型只表达意图，不手写几千个坐标。
- **常驻自治**：本地技能自动执行日常行为，只有值得动脑的事件才唤醒 LLM，省 token 且响应快。
- **自我验收**：`verify_path` 跑 3D BFS 检查楼层可达性 + 门通行（door_check），结果回灌 AI 修正。
- **安全隔离**：参数化指令工具 + 指令白名单 + 权限降级执行，世界改动只在服务端主线程。

## 🛡️ 安全边界

- 世界改动只发生在服务端主线程；默认拒绝生存模式自动建造（`allowSurvivalBuilding`）。
- 指令通道收敛为参数化工具（give / set_time / set_weather / kill_type / tp_self / tp_player / locate_structure 等）+ 白名单（give / time / weather / tp / gamemode / effect / kill / clear / enchant / xp / list / locate），管理指令（op / stop / ban / kick）一律拦截；放方块只能走 place / build_plan（禁 setblock / fill）。
- 单次建筑受 `maxBuildBlocks` 限制；放置前检查覆盖，遇到玩家建筑会请求确认。
- 家具嵌墙 / 床配对 / 附着物支撑（火把、梯子、灯笼）均有校验，失败会给出可执行的修复建议。

## 📝 记录与记忆

- 完整日志：`logs/shabao-ai-agent.log`（每次请求 / 思考 / 动作 / 结果）。
- 建筑记忆：`shabao-ai-memory/<玩家UUID>.json`（楼层 Y、累计方块、建筑中心）。
- 情景记忆：存档内 `shabao-ai-memory/episodic/`（事件时间线，AI 可用 `recall` 跨会话检索）。
- 会话历史：按玩家持久化，可"清空对话"重置。

## 🙏 鸣谢

- 感谢 **Verity 模组** 与 B 站 up 主 [点燃篝火的 Anrry](https://space.bilibili.com/522322575/?)，他们的创意与演示为本项目提供了重要的灵感来源与方向参考。
- 本项目全部代码为原创编写，未曾参考、复制或抄袭任何项目与模组的源代码，仅借鉴了玩法功能，最终实现均由本人独立完成。

## 📄 License

[GNU AGPL-3.0](LICENSE) —— Copyright (C) 2026 txcxgzs（沙包 AI / Shabao AI Companion）。

本 Mod 采用 **GNU Affero General Public License v3.0**：任何人可自由使用、修改、分发，
但修改后的版本若再分发或对外提供网络服务，**必须同样以 AGPL-3.0 开源并保留版权声明**，
不得改名换皮闭源发布。
