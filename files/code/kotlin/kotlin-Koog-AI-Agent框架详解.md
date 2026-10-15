# Koog AI Agent 框架详解——从零到 Android 车机落地

> 读者设定：Android 原生 + 车载定制 ROM（vendor 端）开发者，Kotlin 主力，正在把 JetBrains Koog 接进车机应用。
> 阅读约定：中文为主、术语保留英文；概念先给生活类比，再给类名/调用链/状态机，最后给可运行代码。
> 版本基线：本文以 **Koog 1.2.0**（stable，`ai.koog:koog-agents:1.2.0`）+ Kotlin 2.2.0 + JDK 17 为准。

---

## 第一节 Koog 是什么，本文怎么读

### 1.1 一句话定位

**Koog** 是 JetBrains 出品的、用 **Kotlin Multiplatform（KMP）** 写的 AI Agent（智能体）框架。它的核心世界观可以浓缩成一句话：

> **Agent = 策略图（Strategy Graph，本质是一台显式状态机）+ 工具集（ToolRegistry）+ 模型执行器（PromptExecutor）+ 配置（AIAgentConfig）+ 横切特性（Features）。**

这套世界观和 Android 开发者熟悉的「Activity 生命周期 + Intent + Service」有神似之处：生命周期是一台状态机，`Intent` 是输入，各组件靠注册表（`Service`/`ContentProvider` 的 `PackageManager` 查询）解耦。后面你会发现这个类比贯穿全篇。

### 1.2 和竞品的对比

你已经在用 Kotlin，那为什么不直接用现成的方案？下面这张表帮你快速决策：

| 方案 | 语言/平台 | 编排方式 | 车机落地友好度 | 核心差异 |
|---|---|---|---|---|
| **Koog** | Kotlin（JVM/KMP/JS/Native） | **图策略 DSL（状态机）** | 高（纯 Kotlin、可 Ollama 本地跑） | 把 Agent 当**显式状态机**编排，易可视化、可 checkpoint |
| **LangChain4j** | Java/Kotlin（JVM） | 链式（Chain）/ `@AiService` 注解 | 中（JVM 可用但偏 Java 风格） | 生态大、链式为隐式流程，状态可视化弱 |
| **Spring AI** | Java（Spring 生态） | `@Tool` + `ChatClient` 链式 | 低（强依赖 Spring 容器，车机用不上） | 适合后端微服务，和 Android 水土不服 |
| **直接调 OpenAI/通义 HTTP** | 任意（OkHttp + JSON） | 自己写循环 | 中（最灵活但最费力） | 没有工具循环/状态机抽象，全要手搓 |

**结论**：如果你要的是「在 Android/Kotlin 工程里、把一次 LLM 调用 + 多轮工具循环 + 状态持久化」这件事**正规化、可视化、可组合**，Koog 是当前 Kotlin 生态里最对路的选择，尤其是它支持**本地 Ollama 模型**——这对车机内网/无外网场景价值极大（见第四节）。

### 1.3 「Agent 到底是个啥」——生活类比

把 Agent 想成你雇的一个**助理**：

- 你给他一份**说明书**（systemPrompt，告诉他「你是车控助理，只能开关空调和车窗」）；
- 你交给他一部**电话簿**（ToolRegistry，里面写着「查天气拨 114」「开窗帘打电话给车窗 ECU」）；
- 他遇到不会的事，就**翻说明书 + 查电话簿打电话**（LLM 决策 + 调工具）；
- 电话打完了，把结果**汇报给你**（LLM 生成最终回复）；
- 中间如果还要再打电话（工具又返回「需要再查」），就**继续循环**，直到他说「办完了」。

Koog 的「策略图」就是把这个助理的**工作流程画成一张状态机**：`开始 → 问 LLM → 是文字就结束 / 是工具调用就去执行 → 把结果送回 LLM → 再判断`。这张图是可以被 `asMermaidDiagram()` 画出来的（第六节）。

### 1.4 本文怎么读

- **想马上跑起来**：跳到第三节（快速开始），照抄依赖和 `main` 函数。
- **想理解编排本质**：重点读第二节（概念地图）+ 第六节（策略图状态机）。
- **想接车机**：直接看第十一节（Android 落地专章）+ 第十节（车控 demo）。
- **遇到报错**：查第十二节（排查表），按「第一命令」定位。
- 交叉引用统一用「见第 N 节」格式，全文章节编号为第一节～第十五节。

---

## 第二节 核心概念地图（五构件关系）

### 2.1 五个构件一张图

Koog 把一次 Agent 运行拆成五个相互协作的构件。下面这张 ASCII 关系图请先印在脑子里：

```
                         ┌─────────────────────────────┐
                         │        AIAgent (门面)        │
                         │   持有下面全部五个构件        │
                         └─────────────────────────────┘
                                      │
        ┌──────────────┬──────────────┼───────────────┬──────────────┐
        ▼              ▼              ▼               ▼              ▼
 ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐
 │ PromptExe- │ │  Strategy  │ │ ToolRegi-  │ │AIAgentConfig│ │ Features   │
 │ cutor      │ │ (Graph/    │ │ stry       │ │            │ │ (横切特性) │
 │ (模型执行) │ │  Functional)│ │ (工具集)   │ │ (系统提示/ │ │ events/    │
 │            │ │            │ │            │ │  参数/迭代) │ │ tracing/   │
 └─────┬──────┘ └─────┬──────┘ └─────┬──────┘ └─────┬──────┘ │ memory/    │
       │               │             │              │        │ persist/   │
       │  调用 LLM API │  决定流程   │ 提供工具      │ 提供默认  │ stream     │
       ▼               ▼             ▼              ▼        └─────┬──────┘
 ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐    │ 在运行各
 │ LLM 云端/  │ │ 状态机:     │ │ 工具函数    │ │ systemPrompt│   │ 阶段插入
 │ 本地 Ollama│ │ node/edge   │ │ (@Tool/    │ │ temperature│   │ 钩子
 └────────────┘ └────────────┘ │  SimpleTool)│ │ maxIter... │   └──────────┘
                               └────────────┘ └────────────┘
```

**一句话关系**：`AIAgent` 是门面，它把「**怎么调模型**」（PromptExecutor）、「**按什么流程走**」（Strategy 状态机）、「**能用哪些工具**」（ToolRegistry）、「**默认人设与参数**」（AIAgentConfig）、「**横切能力**」（Features）五样东西组装起来。`run(input)` 一触发，Strategy 这台状态机就开始在节点间跳转，每到一个 LLM 节点就找 PromptExecutor 要模型输出，要调工具就去 ToolRegistry 查。

### 2.2 术语中英对照表

| 中文 | 英文 | 一句话 |
|---|---|---|
| 智能体 | Agent | 由策略图 + 工具 + 执行器组成的可运行实体 |
| 提示执行器 | PromptExecutor | 把「消息列表」发给模型、拿回「回复/工具调用」的组件 |
| 策略（图） | Strategy / Graph | Agent 的工作流，是一台显式状态机 |
| 节点 | Node | 状态机里的一个状态/动作（如「请求 LLM」「执行工具」） |
| 边 | Edge | 节点间的转移，带条件（onCondition 等） |
| 子图 | Subgraph | 图里的一段，可限制自己的工具集 |
| 工具注册表 | ToolRegistry | 存放所有可用工具的容器，支持 `+` 合并 |
| 会话 | Session | 一次对话的上下文（消息历史）载体 |
| 特性 | Features | 横切能力：事件、追踪、记忆、持久化、流式等 |

### 2.3 关键类名速查（先混个脸熟）

| 职责 | 类名 / 函数 |
|---|---|
| 门面构造 | `AIAgent` |
| 快速执行器 | `simpleOpenAIExecutor(token)`、`simpleOllamaAIExecutor()` |
| 多模型执行器 | `DefaultMultiLLMPromptExecutor(...)` |
| 模型标识 | `OpenAIModels.Chat.GPT4o`、`OllamaModels.Meta.LLAMA_3_2`（实现 `LLModel`） |
| 图策略 DSL | `strategy { }`、`nodeLLMRequest()`、`nodeExecuteTools()`、`edge(...)` |
| 工具 | `@Tool`、`SimpleTool<T>`、`ToolRegistry` |
| Agent 当工具 | `AIAgentService` + `createAgentTool(...)` |

---

## 第三节 快速开始：跑通第一个 Agent

### 3.1 依赖与环境（含国内镜像注意）

你的本地工程 `D:\Skills\KOOG` 用的是：

```kotlin
// build.gradle.kts（Kotlin DSL）
plugins {
    kotlin("jvm") version "2.2.0"   // Koog 1.2.x 要求 Kotlin 2.2.0
}

kotlin {
    jvmToolchain(17)                 // JDK 17+，Gradle 8.0+
}

dependencies {
    // 主依赖（stable 通道）
    implementation("ai.koog:koog-agents:1.2.0")
    // 附加能力（beta）：更多内置工具 / 高级特性
    implementation("ai.koog:koog-agents-additions:1.2.0-beta")
    // 旧版遗留，迁移期建议统一升级到 1.2.0
    // implementation("ai.koog:agents-ext-jvm:0.8.0")
}
```

> **「Android 视角」**：纯 JVM 依赖（`koog-agents`）在 Android 工程里可直接用（见第十一节），但 `agents-ext-jvm:0.8.0` 是 0.x 遗留，和 1.x API 不兼容，迁移期建议统一升级，不要新旧混用，否则 `NoClassDefFoundError` 会让你怀疑人生（见第十二节）。

**国内网络镜像**：Koog 发布在 **Maven Central**（不依赖 GitHub 网络），所以只要给 Gradle 配好阿里云/腾讯云镜像即可，无需梯子：

```kotlin
// settings.gradle.kts —— 在 pluginManagement / dependencyResolutionManagement 里加
dependencyResolutionManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/public")  // 阿里云公共仓
        mavenCentral()                                        // 兜底
    }
}
```

### 3.2 单次运行 Agent 完整可运行代码

下面是一段**可直接复制到 `main.kt` 跑通**的最小示例（注意：代码里不要写死真实 key，从环境变量读）：

```kotlin
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.ext.tool.SimplyKt
import ai.koog.prompt.executor.clients.openai.simpleOpenAIExecutor
import ai.koog.prompt.model.OpenAIModels

suspend fun main() {
    // 1) 执行器：用 OpenAI，key 从环境变量取，绝不写死在代码里
    val executor = simpleOpenAIExecutor(System.getenv("OPENAI_API_KEY") ?: error("缺少 OPENAI_API_KEY"))

    // 2) 门面：系统提示 + 模型 + 执行器
    val agent = AIAgent(
        executor = executor,
        systemPrompt = "你是一个简洁的中文助手，回答不超过两句话。",
        llmModel = OpenAIModels.Chat.GPT4o
    )

    // 3) 运行：输入一句话，拿到回复
    val reply = agent.run("用一句话解释什么是 Agent。")
    println(reply)
}
```

> 说明：上面用的是「单次运行（quick config）」形态——只给 `systemPrompt`/`llmModel`，Koog 内部会自动套一个默认的图策略（请求 LLM → 有工具就执行 → 有文字就结束）。下一节会看到它的完整形态和三种形态取舍。

### 3.3 `run()` 的挂起语义（suspend）

`agent.run(input)` 是一个 **suspend 函数**，必须在协程里调用：

```kotlin
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {          // 桥接阻塞 main 到协程世界
    val reply = agent.run("Hello")
    println(reply)
}
```

**为什么是 suspend？** 因为一次 `run` 内部可能是**多轮的**：LLM 返回工具调用 → 执行工具（可能耗时的 I/O）→ 再把结果送回 LLM → 再判断……整条链路都是异步 I/O。把 `run` 设计成 suspend，意味着：

- 它**不会阻塞调用线程**（`Dispatchers` 默认切到 IO/Default）；
- 在 Android 里你可以直接 `lifecycleScope.launch { agent.run(...) }`，不卡 UI 线程（见第十一节）；
- 它是**可取消的**：父协程取消，`run` 内部也会跟着取消（车机里「用户退出页面就停掉 Agent」很关键）。

> 「Android 视角」：这正是 Koog 比「自己开线程 + Handler 回主线程」优雅的地方——它天生是协程世界一等公民，和你已经熟悉的 `viewModelScope`/`lifecycleScope` 无缝衔接。

---

## 第四节 Prompt Executor 与多模型

`PromptExecutor` 是「**怎么把消息发给模型、把模型回复拿回来**」这一层的抽象。你可以把它理解成 Android 里的 `Retrofit` 接口：上层（`AIAgent`/策略图）只喊「发！收！」，至于发给 OpenAI 还是本地 Ollama，由执行器决定。

### 4.1 各 provider 客户端与简单执行器

Koog 内置了对主流厂商的客户端封装，常见用法分两种粒度：

**(a) 一行拉起（最省事）**

```kotlin
import ai.koog.prompt.executor.clients.openai.simpleOpenAIExecutor
import ai.koog.prompt.executor.clients.anthropic.simpleAnthropicExecutor   // 如存在
import ai.koog.prompt.executor.clients.google.simpleGoogleExecutor

val openAI = simpleOpenAIExecutor(System.getenv("OPENAI_API_KEY")!!)
// val claude = simpleAnthropicExecutor(...)
// val gemini = simpleGoogleExecutor(...)
```

**(b) 先建客户端，再传给多模型执行器（推荐，可控）**

```kotlin
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor

// 1) 各 provider 客户端（配置 baseUrl / apiKey / timeout 等）
val openAIClient = OpenAILLMClient(apiKey = System.getenv("OPENAI_API_KEY")!!)
val anthropicClient = AnthropicLLMClient(apiKey = System.getenv("ANTHROPIC_API_KEY")!!)
val googleClient = GoogleLLMClient(apiKey = System.getenv("GEMINI_API_KEY")!!)

// 2) 多模型执行器：把多个客户端装进去，按 LLModel 路由
val multiExecutor: MultiLLMPromptExecutor = DefaultMultiLLMPromptExecutor(
    openAIClient, anthropicClient, googleClient
)
```

> 注：多模型执行器的精确构造签名随版本微调，上例给出的是「一类/相关接口」的通用形态。核心不变：**多个 `LLMClient` → 一个 `DefaultMultiLLMPromptExecutor` → 根据传入的 `LLModel` 自动选厂商**。具体参数名以 `api.koog.ai` 当前版本文档为准。

### 4.2 `LLModel`——模型的「身份证」

模型不是字符串，而是 `LLModel` 的实现类。Koog 用「哪家厂 + 哪个系列 + 哪个具体模型」的三段式枚举组织：

```kotlin
import ai.koog.prompt.model.OpenAIModels
import ai.koog.prompt.model.OllamaModels
import ai.koog.prompt.model.AnthropicModels   // 若存在
import ai.koog.prompt.model.LLMModel

val m1: LLMModel = OpenAIModels.Chat.GPT4o
val m2: LLMModel = OpenAIModels.Chat.GPT4oMini
val m3: LLMModel = OllamaModels.Meta.LLAMA_3_2
```

当你 `agent.run(...)` 时，传入的 `llmModel` 会被执行器用来决定「调哪个厂商的哪个 endpoint、用哪个模型名」。这就是为什么多模型执行器能做到「同一个 Agent 代码，换 `LLModel` 就换厂商」。

### 4.3 Ollama 本地模型（车机/内网价值重点）

**这是 Koog 对车机最友好的能力，请务必重点理解。**

Ollama 是一个在你**本地机器/内网服务器**上跑大模型的服务，默认地址 `http://localhost:11434`。Koog 提供了开箱即用的本地执行器：

```kotlin
import ai.koog.prompt.executor.clients.ollama.simpleOllamaAIExecutor
import ai.koog.prompt.model.OllamaModels

// 连本地 Ollama（默认 11434 端口）
val localExecutor = simpleOllamaAIExecutor()

val localAgent = AIAgent(
    executor = localExecutor,
    systemPrompt = "你是车机本地助手，断网也能工作。",
    llmModel = OllamaModels.Meta.LLAMA_3_2
)
```

**为什么对车机至关重要**（对应你 vendor 端/车机的场景）：

- **无外网也能跑**：车机在地下车库、隧道、境外漫游时外网可能不通；把模型部署在车机本体或车内网关（如一台带 NPU 的 SoC 跑 Ollama），Agent 不依赖云。
- **数据不出车**：用户说「把空调调到 23 度」「导航去公司」，这些指令和位置数据**完全留在车内**，满足隐私与数据出境合规（见第十一节）。
- **零 API 费用、零延迟抖动**：没有云厂商按 token 计费，也没有公网 RTT。

> 部署形态建议：车机本体算力弱时，可在**车内域控制器/网关**上跑 Ollama（如 7B/8B 量化模型），车机 App 通过 `http://192.168.x.x:11434` 访问；把 `simpleOllamaAIExecutor()` 的 baseUrl 指向该内网地址即可（具体 baseUrl 配置参数以当前版本为准，是一类可执行配置项）。

### 4.4 模型降级与 fallback（实战建议）

云模型会 429（限流）、会 401（key 失效）、会超时。一个稳妥的设计是「**主云 + 本地兜底**」：

```kotlin
// 思路（伪代码骨架，具体异常类型以当前版本为准）：
// try { cloudAgent.run(input) } catch (e: RateLimitException) { localAgent.run(input) }
val primary = AIAgent(executor = openAIExecutor, llmModel = OpenAIModels.Chat.GPT4o)
val fallback = AIAgent(executor = localExecutor, llmModel = OllamaModels.Meta.LLAMA_3_2)

suspend fun ask(text: String): String = runCatching { primary.run(text) }
    .getOrElse { fallback.run(text) }   // 云挂了就走本地 Ollama
```

> 更精细的 fallback（按异常类型分流、加退避重试）属于 Features 里的事件处理/重试范畴，见第九节与第十二节的 429 排查。

---

（未完，第四节结束，后续第五节起见下一段写入）

---

## 第五节 三种 Agent 形态与取舍

Koog 的 `AIAgent` 有三种「门面形态」，本质区别在**你给多少编排控制权**：

### 5.1 形态一：单次运行（quick config）

只给 `systemPrompt` + `llmModel` + `executor`，Koog 内部自动套一个默认图策略（请求 → 工具 → 结束）。第三节用的就是它。

```kotlin
val agent = AIAgent(
    executor = simpleOpenAIExecutor(key),
    systemPrompt = "...",
    llmModel = OpenAIModels.Chat.GPT4o
)
// 可选微调（属 AIAgentConfig 维度，见第八节）：
// maxIterations = 20, temperature = 0.3
```

适用：聊天助手、单轮问答、快速验证 idea。**不**适合需要精确控制「先查什么后查什么」「工具循环几次就停」的场景。

### 5.2 形态二：图策略（Graph Strategy）—— 本文重点

显式用 `strategy { }` DSL 画出状态机，把 `node`/`edge` 交出去。见第六节。

```kotlin
val agent = AIAgent(
    executor = executor,
    systemPrompt = "...",
    llmModel = OpenAIModels.Chat.GPT4o,
    strategy = myGraphStrategy,     // 显式状态机
    toolRegistry = toolRegistry
)
```

适用：车控助理（必须先鉴权→再读状态→再执行指令）、多步工具链、需要可视化/checkpoint 的严肃场景。

### 5.3 形态三：函数式策略（Functional Strategy）

把流程写成普通 Kotlin 高阶函数/挂起函数，不用图 DSL。适合「流程就是一段普通代码」的简单编排（如固定三步调用）。具体函数名随版本演变，属「一类/相关接口」，请以其时文档为准；核心思想是「用 Kotlin 代码而非图来描述流程」。

### 5.4 取舍表

| 维度 | 单次运行 | 图策略 | 函数式策略 |
|---|---|---|---|
| 上手成本 | 最低（3 行） | 中（要学 DSL） | 低（写 Kotlin 即可） |
| 可视化 | 无（黑盒） | **有（asMermaidDiagram）** | 无 |
| 状态持久化/checkpoint | 弱 | **强（图天然可断点续跑）** | 中（自己存） |
| 流程控制精度 | 低 | **最高（node/edge 级）** | 高（代码级） |
| 可组合/复用 | 低 | **高（subgraph 复用）** | 中 |
| 车机严肃场景推荐度 | 验证用 | **首选** | 简单子流程 |

**结论**：车机落地首选**图策略**（第六节），因为你要的是「流程可审计、可 checkpoint 恢复、工具调用可限制」。

---

## 第六节 策略图深入（本篇最重一章）

策略图（Strategy Graph）是 Koog 的灵魂。请记住一句话：

> **策略图 = 一台显式有限状态机（FSM）。`node` 是状态，`edge` 是带条件的转移，`nodeStart`/`nodeFinish` 是初始/终止状态。**

### 6.1 node/edge 的状态机语义（ASCII 状态图）

一个最基础的图（请求 LLM → 文字就结束 / 工具就执行 → 回送结果 → 再判断）长这样：

```
        ┌──────────┐
   ───▶ │ nodeStart │
        └────┬─────┘
             │ edge(nodeStart forwardTo nodeCallLLM)
             ▼
      ┌──────────────┐   onTextMessage{true}   ┌──────────┐
      │  nodeCallLLM  │ ─────────────────────▶ │nodeFinish│
      │ (请求 LLM)    │                         └──────────┘
      └────┬─────┘
           │ onToolCalls{true}
           ▼
   ┌────────────────┐       edge(直接连)      ┌────────────────────┐
   │ executeToolCall │ ─────────────────────▶ │ sendToolResult     │
   │ (执行工具)      │                        │ (把结果送回 LLM)   │
   └────────────────┘                        └─────────┬──────────┘
                                                      │ onTextMessage{true}
                                                      ▼ (见下方回环)
                                               ┌──────────┐
                                               │nodeFinish│
                                                      ▲
                                                      │ onToolCalls{true}
                                               ┌────────────────────┐
                                               │ sendToolResult      │
                                               │ (再调工具→回环)     │
                                               └────────────────────┘
```

**语义要点**：
1. 引擎从 `nodeStart` 出发，沿 `edge` 走；
2. 一个节点可能有**多条出边**，引擎按 `edge` 定义的**顺序**逐条检查条件，第一条满足的就走；
3. `onTextMessage` / `onToolCalls` 是「LLM 这次返回的是文字还是工具调用」的二选一判断；
4. `sendToolResult → executeToolCall` 形成**回环**：工具结果送回 LLM 后，如果 LLM 还想调工具，就再来一轮，直到 `onTextMessage` 为真才到 `nodeFinish`。

这就是「Agent 循环调工具」的本质——**不是魔法，是状态机里的回环边**。

### 6.2 预置节点全表

| 节点（函数） | 作用 | 输入→输出 |
|---|---|---|
| `nodeLLMRequest()` | 把当前消息发给 LLM，拿回「文字或工具调用」 | 消息 → LLM 响应 |
| `nodeExecuteTools()` | 执行 LLM 请求的所有工具调用 | 工具调用列表 → 结果列表 |
| `nodeLLMSendToolResults()` | 把工具结果送回 LLM | 工具结果 → LLM 响应 |
| `nodeExecuteSingleTool` | 执行**单个**指定工具（带参数） | 参数 → 结果 |
| `nodeExecuteMultipleTools` | 执行多个工具 | 调用列表 → 结果列表 |
| `nodeLLMSendMultipleToolResults` | 回送多个工具结果 | 多结果 → LLM 响应 |
| `nodeLLMCompressHistory()` | 压缩历史（省 token，见第九节） | 历史 → 压缩后历史 |
| `node<Input,Output> { ... }` | **自定义**节点，lambda 里写任意逻辑 | 你定 |

> 注意：文档在 0.x 时期节点命名有过变化（如早期 `nodeLLMSendToolResult` 单数 vs 现在复数 `nodeLLMSendToolResults`），**以你用的 1.2.0 当前版本文档为准**（见第十三节）。

### 6.3 edge 的条件全家福

`edge(source forwardTo target)` 后可接条件，决定「什么时候走这条边」：

```kotlin
edge(nodeStart forwardTo nodeCallLLM)                       // 无条件（直接连）
edge(nodeCallLLM forwardTo nodeFinish onTextMessage { true }) // LLM 回了文字
edge(nodeCallLLM forwardTo executeToolCall onToolCalls { true }) // LLM 要调工具
edge(nodeCallLLM forwardTo nodeFinish onToolNotCalled { true }) // LLM 没调工具
edge(nodeSendInput forwardTo branchA
        onCondition { input -> input.contains("空调") })     // 自定义条件
edge(nodeSendInput forwardTo branchB transformed { it.uppercase() }) // 转移时改数据
```

| 条件 | 含义 | 典型用途 |
|---|---|---|
| `onTextMessage { }` | LLM 返回纯文字 | 结束流程 |
| `onToolCalls { }` | LLM 返回工具调用 | 进入工具执行 |
| `onToolNotCalled { }` | LLM 没调工具 | 兜底/直接结束 |
| `onCondition { input -> ... }` | 任意布尔 | 按内容分叉 |
| `transformed { ... }` | 转移时转换数据 | 改写/裁剪传给下个节点的数据 |

**实战坑**：多条出边时，**条件顺序即判定顺序**。如果你先写了 `onCondition{true}` 兜底，后面的 `onToolCalls` 就永远走不到——把更具体的条件放前面（见第十二节「图走错分支」）。

### 6.4 subgraph 与工具限制

子图（subgraph）是图里的一段「独立车间」，可以**只许用部分工具**——这对车机安全很关键（比如「鉴权子图」只允许用 `verifyPin` 工具，绝不许碰 `openDoor`）：

```kotlin
val strategy = strategy<String, String>("car-control") {
    val auth by subgraph<String, String>(name = "auth", tools = listOf(verifyPinTool)) {
        val ask by nodeLLMRequest()
        val runTool by nodeExecuteTools()
        val reply by nodeLLMSendToolResults()
        edge(nodeStart forwardTo ask)
        edge(ask forwardTo nodeFinish onTextMessage { true })
        edge(ask forwardTo runTool onToolCalls { true })
        edge(runTool forwardTo reply)
        edge(reply forwardTo nodeFinish onTextMessage { true })
        edge(reply forwardTo runTool onToolCalls { true })
    }
    // 主图只能用到 auth 暴露出的能力，工具集在 subgraph 内被约束
}
```

**收益**：把「危险工具」关在受限 subgraph 里，主图调度时天然隔离，符合车机「最小权限」原则。

### 6.5 并行节点与 selectByMax

当几个独立计算可以并发跑，用 `parallel` 把多个节点并行执行，再用 `selectByMax`（或同类选择器）挑结果：

```kotlin
val calc by parallel<String, Int>(
    nodeCalcTokens, nodeCalcSymbols, nodeCalcWords
) {
    selectByMax { it }   // 选「值最大」的那路结果
}
// calc 的类型是 AsyncParallelResult，可在后续节点里取用
```

**语义**：`parallel` 节点内部并发跑 `nodeCalcTokens/symbols/words`，全部（或按选择器策略）完成后，按 `selectByMax { it }` 选出结果。适合「多路独立评估、取最优」的车机场景（如多个传感器解读并行、取最置信的那个）。

### 6.6 `asMermaidDiagram()` 调试

在 JVM 上，可以把整张图导出成 **Mermaid 状态图**，贴进支持 Mermaid 的 Markdown 预览（如 VS Code 插件、GitHub）直接看：

```kotlin
val mermaid: String = myStrategy.asMermaidDiagram()
println(mermaid)   // 复制到 https://mermaid.live 或本地预览即可可视化
```

**调试价值**：图状态机「易可视化」是官方强调的核心收益之一。当你发现 Agent「从不调工具」或「卡在循环」，第一反应应是 `asMermaidDiagram()` 看边有没有连错，而不是猜 LLM 抽风（见第十二节）。

---

## 第七节 工具系统（Tool）

工具是 Agent 的「手」——它让 LLM 从「只会聊天」变成「能干活」。Koog 支持三种工具来源（见 tools 文档）：内置工具、注解式自定义工具、类式自定义工具。车机里你主要写后两种。

### 7.1 注解式 `@Tool`（最省事）

用 `@Tool` 注解一个函数，Koog 自动把它暴露给 LLM（含参数 schema 推导）：

```kotlin
import ai.koog.agents.core.tools.annotations.Tool

class CarTools {
    @Tool("打开车窗，level 为开度 0-100")
    fun openWindow(level: Int): String {
        // 「Android 视角」：这里实际去 binder 调 vendor 车窗 HAL
        VehicleHAL.openWindow(level)
        return "车窗已开到 $level%"
    }

    @Tool("查询车内温度")
    fun getCabinTemp(): String {
        return "当前车内温度 23.5°C"
    }
}
```

> 注解式适合「参数简单、逻辑短」的工具。参数类型要可序列化（Koog 用 kotlinx.serialization 推导 schema），`Int/String/Boolean` 这类最稳。

### 7.2 类式 `SimpleTool<T>`（完全可控）

当你要精确控制参数结构、描述文案、执行逻辑时，继承 `SimpleTool<T>`：

```kotlin
import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolArgs
import ai.koog.agents.core.tools.ToolResult
import kotlinx.serialization.Serializable
import kotlin.reflect.typeOf

// 1) 参数类（必须 @Serializable，T 的载体）
@Serializable
data class SetAcArgs(val temperature: Double, val fanSpeed: Int) : ToolArgs

// 2) 结果类（可选，看需求）
@Serializable
data class SetAcResult(val ok: Boolean, val msg: String) : ToolResult {
    override fun toString(): String = msg   // LLM 看到的是 toString 文本
}

// 3) 工具类
class SetAcTool : SimpleTool<SetAcArgs>(
    argsType = typeToken<SetAcArgs>(),          // 参数类型令牌
    name = "set_ac",                             // LLM 看到的工具名（英文短词）
    description = "设置空调温度和风量。temperature 单位摄氏度(16-30)，fanSpeed 1-5。" // 关键！
) {
    override suspend fun execute(args: SetAcArgs): String {
        // 真实车控：调 HVAC HAL
        VehicleHAL.setAC(args.temperature, args.fanSpeed)
        return "空调已设为 ${args.temperature}°C，风量 ${args.fanSpeed} 档"
    }
}
```

**为什么类式更可控**：`description` 和参数结构你说了算，而 LLM **完全根据 `description` 决定要不要调这个工具、怎么填参数**——描述写得好不好，直接决定 Agent 聪不聪明（见 7.4）。

### 7.3 ToolRegistry 组装与合并

工具先装进 `ToolRegistry`，再交给 `AIAgent`：

```kotlin
import ai.koog.agents.core.tools.ToolRegistry

val toolRegistry = ToolRegistry {
    tools(SetAcTool(), CarTools())   // 类式 + 注解式都能装
    tool(openWindowToolInstance)      // 也可逐个 tool(...)
}

// 多注册表合并（"+" 运算符）
val regA = ToolRegistry { tools(SetAcTool()) }
val regB = ToolRegistry { tools(CarTools()) }
val merged = regA + regB          // 合并后的新注册表
```

### 7.4 工具描述怎么写（实战建议，影响 LLM 选择）

LLM 看不到你的代码，它只看 `name` + `description` + 参数 schema 来决策。几条血泪经验：

1. **name 用动作+对象的英文短词**：`set_ac`、`open_window`、`query_temp`，别用 `tool1`。
2. **description 写清「什么时候用 + 参数含义 + 取值边界」**：把 `temperature 单位摄氏度(16-30)` 写进去，LLM 就不会填 100。
3. **歧义工具拆开**：与其一个 `control(mode, value)` 让 LLM 猜 mode，不如 `set_ac` / `open_window` 两个清晰工具。
4. **负面约束也写**：「仅在内网有效」「不要用于行驶中」——写进 description，LLM 会当约束遵守。

> 反例：如果 `description` 写成 `"控制车"` 这种废话，LLM 大概率**永不调工具**，表现为「Agent 从不调工具」，排查时先查描述（见第十二节）。

### 7.5 工具内异常处理（防止拖死 Agent）

工具里抛异常会**直接中断 Agent 运行**。务必把异常吞掉、转成文本返回，让 LLM 自己决定下一步：

```kotlin
override suspend fun execute(args: SetAcArgs): String = runCatching {
    VehicleHAL.setAC(args.temperature, args.fanSpeed)
    "空调已设为 ${args.temperature}°C"
}.getOrElse { e ->
    // 关键：返回错误文本而非抛异常，Agent 才能继续/重试
    "执行失败：${e.message}，请检查车辆是否通电"
}
```

> Koog 官方也强调：工具里要做好 error handling 防止 agent 整体失败（见第九节事件处理里对工具异常的统一捕获）。

### 7.6 并行工具调用

独立工具可以并行跑，用 `toParallelToolCallsRaw` 扩展：

```kotlin
val node by node<Unit, Unit> {
    llm.writeSession {
        flow { emit(Book("书名", "作者", "简介")) }
            .toParallelToolCallsRaw(BookTool::class)
            .collect()   // 并行分发到 BookTool 执行
    }
}
```

另外，`nodeExecuteTools(parallel = true)` 也能让「执行工具节点」内部并行处理多个工具调用（见第六节 6.2）。

### 7.7 Agent 作为工具（分层/多 Agent 架构）

这是 Koog 的杀手锏：**把一个 Agent 整个包成另一个 Agent 的工具**。适合「总指挥 Agent + 多个专家 Agent」：

```kotlin
import ai.koog.agents.core.agent.AIAgentService

// 1) 专家 Agent（如「车辆诊断专家」）
val diagService = AIAgentService(
    promptExecutor = simpleOpenAIExecutor(apiKey),
    llmModel = OpenAIModels.Chat.GPT4o,
    systemPrompt = "你是车辆故障诊断专家。",
    toolRegistry = diagToolRegistry
)

// 2) 把专家 Agent 变成工具
val diagTool = diagService.createAgentTool(
    agentName = "diagnose",
    agentDescription = "诊断车辆故障，输入症状描述，输出诊断结论",
    inputDescription = "车辆症状的自然语言描述",
    inputType = typeToken<String>()   // 输入类型令牌
)

// 3) 总指挥 Agent 把它当普通工具挂上
val coordinator = AIAgent(
    executor = simpleOpenAIExecutor(apiKey),
    llmModel = OpenAIModels.Chat.GPT4o,
    systemPrompt = "你是车控总助理，复杂诊断请调用 diagnose 工具。",
    toolRegistry = ToolRegistry { tool(diagTool) }
)
```

**执行时**：总 Agent 调 `diagnose` → 参数按 `inputType` 反序列化 → 子 Agent 跑完 → 输出序列化回传。收益是**模块化、可复用、关注点分离**（一个诊断 Agent 可被多个上层 Agent 复用）。

---

（未完，第七节结束，后续第八节起见下一段写入）

---

## 第八节 会话与 Prompt 工程

LLM 是「无状态」的——它不记得上一句，所谓「对话记忆」其实是**把历史消息 Whole 塞回下一次请求**。Koog 用 `Session` + `Prompt` 来管这件事。

### 8.1 会话（Session）生命周期

`AIAgent.run(input)` 其实背后会创建/复用会话。完整形态支持显式会话管理：

```kotlin
// 显式建会话，拿到可复用的 AIAgentRunSession
val session = agent.createSession(sessionId = "user-123")

// 带 sessionId 运行，多次 run 共享同一段历史（多轮对话）
val r1 = agent.run("把空调开到 24 度", sessionId = "user-123")
val r2 = agent.run("再加一档风量",      sessionId = "user-123") // 记得上一句
```

**语义**：`sessionId` 把多次 `run` 串成「同一段对话」。车机里 `sessionId` 可以按「当前用户档位/当前页面」来定，比如 `sessionId = "driver_profile_${seatId}"`，实现「主驾和副驾各自记忆」。

### 8.2 `AIAgentLLMWriteSession` 与 `readSession`

在策略图的自定义节点里，你能拿到 `llm` 这个会话句柄，它有 `writeSession`（改消息）和 `readSession`（读消息）：

```kotlin
val myNode by node<String, String> { input ->
    llm.readSession {
        // 读：当前 prompt 里有多少条消息，用于条件判断（如历史压缩触发）
        println("当前消息数 = ${prompt.messages.size}")
    }
    llm.writeSession {
        // 写：往会话里塞一条系统/用户消息、或直接调工具
        // flow {...}.toParallelToolCallsRaw(...).collect()
    }
    "done"
}
```

**记忆点**：`readSession` 是「看历史」，`writeSession` 是「改历史/调工具」。第六节里 `nodeLLMCompressHistory` 就是靠 `readSession { prompt.messages.size }` 判断要不要压缩（见第九节）。

### 8.3 Prompt DSL（消息角色）

Koog 的 `Prompt` 由多段 `Message` 组成，角色分 `system`/`user`/`assistant`/`tool`。在策略里你也可以显式构造带角色的消息来指导 LLM：

```kotlin
// 概念示意（具体 DSL 构造器名以当前版本为准，是一类 API）
Prompt(
    messages = listOf(
        Message.Role.System("你是车控助理，只回答与车辆控制相关的问题。"),
        Message.Role.User("把车窗开一半"),
        Message.Role.Assistant("已为您开窗 50%。"),
        Message.Role.Tool("open_window", "车窗已开到 50%")  // 工具结果回送
    )
)
```

### 8.4 `AIAgentConfig`——默认人设与参数

`AIAgentConfig` 承载「系统提示 + 模型默认参数 + 迭代上限」等。完整构造时可作为独立配置对象传入（具体字段名随版本稳定，类名 `AIAgentConfig` 是事实标准）：

```kotlin
// 概念骨架（字段名以当前文档为准，属一类配置 API）
val config = AIAgentConfig(
    systemPrompt = "你是车控助理。",
    // llmModel = ...,  模型
    // maxIterations = 20,  工具循环上限，防止死循环烧 token
    // temperature = 0.2,   越低越确定（车控要稳，建议低）
)
// AIAgent(executor, config, strategy, toolRegistry, ...)
```

**车机建议**：`temperature` 调低（0.1~0.3）让输出更稳定可预期；`maxIterations` 设一个上限（如 15~20），防止 LLM 陷入「调工具→调工具」的死循环把 token 烧光（见第十二节「maxIterations 打满」）。

---

## 第九节 Features 横切段（事件/追踪/记忆/持久化/压缩/流式）

Features 是「**横切**」能力——它们不属某一节点，而是在 Agent 运行的各个阶段插入钩子。官方有独立文档页。本节给**概念 + 关键 API 名 + 简例**，不确定的签名用「一类/相关接口」表述，不写死。

### 9.1 事件处理（Agent Events）

Koog 会在运行关键节点抛出事件，你注册 handler 来响应：`onToolCall`（即将/已调工具）、`onAgentFinished`（运行结束）、`onError` 等。

```kotlin
// 概念骨架（具体注册方式/接口名以当前版本为准）
agent.events.onToolCall { event ->
    println("[日志] 准备调工具：${event.toolName} 参数=${event.args}")
}
agent.events.onAgentFinished { event ->
    println("[日志] 本次耗时 ${event.duration} 回复=${event.result}")
}
```

**车机价值**：用事件做审计日志（谁在几点让车做了什么），也用于「工具异常统一捕获」——把工具里的崩溃在事件层兜底，避免单个工具拖死整个 Agent（呼应第七节 7.5）。

### 9.2 Tracing（追踪）

Tracing 把一次 `run` 的内部步骤（LLM 请求、工具调用、节点跳转）记录成可观测链路，便于接 Prometheus/OpenTelemetry 或在车机诊断里回放。属「一类追踪接口」，配置后自动采集。

### 9.3 Agent Memory（记忆）

Memory 让知识**跨会话、跨 Agent 保留**（区别于 8.1 的「单次会话历史」）。例如把「用户偏好 24°C」存进长期记忆，下次新会话也能用。

```kotlin
// 概念骨架：记忆是一层可挂的存储，Agent 读取时自动注入相关记忆
// agent.installFeature(MemoryFeature(...))
```

### 9.4 Persistency（持久化 / checkpoint）

图状态机「**状态持久化**」是官方强调的核心收益之一。Persistency 把 Agent 的**运行中间状态（当前节点、历史消息）**存成 checkpoint，崩溃/断电后可从断点恢复——对车机至关重要（车机可能随时断电/重启）。

```kotlin
// 概念骨架：配置 checkpoint 存储（文件/数据库），run 中断后可用同一 sessionId 恢复
// agent.installFeature(PersistencyFeature(storage = ...))
```

### 9.5 History Compression（历史压缩）

长对话历史会越来越长、越来越烧 token。Koog 提供预置压缩策略（如「只保留最近 N 条」`FromLastNMessages`、或 LLM 摘要压缩），在 `nodeLLMCompressHistory` 节点触发（见第六节 6.2、6.4 示例）。

**什么时候必须压缩**：① 多轮车控对话超过数十轮；② `readSession { prompt.messages.size > 100 }` 触发；③ 出现「历史爆 token」报错（见第十二节）。

### 9.6 Streaming（流式）

Streaming 让 LLM 输出**逐字/逐块**返回，而不是等全部生成完才给——车机 HMI 上能做出「打字机效果」，体验远好于「转圈半天蹦一句」。

```kotlin
// 概念骨架：以流的方式收 LLM 输出，含并行工具场景
// agent.runStreaming(input) { chunk -> updateHmi(chunk) }
```

**车机价值**：结合 WebView/JSBridge（见第十一节），把 `chunk` 实时推到 HMI 渲染，用户感知延迟大幅下降。

---

## 第十节 完整实战 demo：车机用车控助理

把前面所有零件焊成一个**可复制、可运行**的 JVM demo（对标你本地 `D:\Skills\KOOG` 工程）。它包含：2 个车控工具（`set_ac`、`open_window`）+ 一张图策略 + 事件打日志。

```kotlin
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolArgs
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.agent.strategyAI
import ai.koog.prompt.executor.clients.openai.simpleOpenAIExecutor
import ai.koog.prompt.model.OpenAIModels
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlin.reflect.typeOf

// ---------- 1) 工具参数 ----------
@Serializable data class SetAcArgs(val temperature: Double, val fan: Int) : ToolArgs
@Serializable data class OpenWindowArgs(val level: Int) : ToolArgs

// ---------- 2) 工具实现（这里用 println 模拟调 HAL） ----------
object VehicleHAL {
    fun setAC(t: Double, f: Int) = println("[HAL] 空调 -> $t°C 风量$f")
    fun openWindow(l: Int) = println("[HAL] 车窗 -> $l%")
}

class SetAcTool : SimpleTool<SetAcArgs>(
    argsType = typeToken<SetAcArgs>(),
    name = "set_ac",
    description = "设置空调。temperature 摄氏度(16-30)，fan 风量(1-5)。仅车辆通电时可用。"
) {
    override suspend fun execute(args: SetAcArgs): String = runCatching {
        VehicleHAL.setAC(args.temperature, args.fan)
        "空调已设为 ${args.temperature}°C，风量 ${args.fan} 档"
    }.getOrElse { "执行失败：${it.message}" }
}

class OpenWindowTool : SimpleTool<OpenWindowArgs>(
    argsType = typeToken<OpenWindowArgs>(),
    name = "open_window",
    description = "开窗，level 开度 0-100。行驶中请勿全开。"
) {
    override suspend fun execute(args: OpenWindowArgs): String = runCatching {
        VehicleHAL.openWindow(args.level)
        "车窗已开到 ${args.level}%"
    }.getOrElse { "执行失败：${it.message}" }
}

// ---------- 3) 图策略（请求→工具→回送→结束，带日志节点） ----------
val carStrategy = strategy<String, String>("car-control") {
    val callLLM by nodeLLMRequest()
    val doTools by nodeExecuteTools()
    val sendBack by nodeLLMSendToolResults()

    edge(nodeStart forwardTo callLLM)
    edge(callLLM forwardTo nodeFinish onTextMessage { true })
    edge(callLLM forwardTo doTools onToolCalls { true })
    edge(doTools forwardTo sendBack)
    edge(sendBack forwardTo nodeFinish onTextMessage { true })
    edge(sendBack forwardTo doTools onToolCalls { true })
}

// ---------- 4) 组装并运行 ----------
fun main() = runBlocking {
    val agent = AIAgent(
        executor = simpleOpenAIExecutor(System.getenv("OPENAI_API_KEY") ?: error("no key")),
        systemPrompt = "你是车控助理，只能调用 set_ac / open_window 两个工具。回答简洁。",
        llmModel = OpenAIModels.Chat.GPT4o,
        strategy = carStrategy,
        toolRegistry = ToolRegistry { tools(SetAcTool(), OpenWindowTool()) }
    )

    // 事件打日志（见第九节 9.1）
    agent.events.onToolCall { e -> println("[event] 调工具=${e.toolName}") }
    agent.events.onAgentFinished { e -> println("[event] 完成 耗时=${e.duration}") }

    val reply = agent.run("帮我把空调开到 24 度，风量 3 档")
    println("最终回复：$reply")
}
```

**跑起来你会看到**：`[HAL] 空调 -> 24.0°C 风量3` → `[event] 调工具=set_ac` → `最终回复：空调已设为 24.0°C…`。这就是「LLM 决策 → 调工具 → 回送 → 结束」整条链路跑通。

> 标注：上面 `strategy<String,String>("car-control"){...}`、`nodeLLMRequest()` 等是 Koog 图 DSL 的事实标准 API；`AIAgent` 构造参数名与 `events` 注册方式若与你 1.2.0 工程略有出入，以 `D:\Skills\KOOG` 本地工程与 docs.koog.ai 当前版本为准。把 `simpleOpenAIExecutor` 换成 `simpleOllamaAIExecutor()` 即可离线跑（见第四节 4.3）。

---

（未完，第十节结束，后续第十一节起见下一段写入）

---

## 第十一节 Android 车机落地专章

这是你最关心的：怎么把 Koog 真正塞进 Android/AAOS 车机应用。下面每条都标了「Android 视角」。

### 11.1 KMP artifact 在 Android 工程里的引入

`koog-agents:1.2.0` 是纯 JVM/Kotlin 依赖，在 Android（`com.android.application`）工程里**直接当普通 `implementation` 依赖**即可，不需要特殊处理 KMP 产物：

```kotlin
// app/build.gradle.kts
android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }   // 需 JDK 17
}
dependencies {
    implementation("ai.koog:koog-agents:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.0")
}
```

> 注意：旧 `agents-ext-jvm:0.8.0` 是 0.x API，别和新版混用（见第十三节）。若只用本地 Ollama，连网络 SDK 的最小集也够，体积可控。

### 11.2 协程 scope 设计（后台 Service 跑、结果回主线程）

Agent 的 `run` 是 suspend，**绝不能放主线程**。车机里标准做法是：在一个**前台/后台 Service** 里用 `CoroutineScope` 跑 Agent，结果通过 `LiveData`/`StateFlow` 或回调抛回主线程更新 HMI。

```kotlin
class CarAgentService : Service() {
    // 独立的 agent scope，绑 Service 生命周期，退出即取消
    private val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun askAsync(text: String, onResult: (String) -> Unit) {
        agentScope.launch {
            val reply = agent.run(text)            // 在 IO 线程跑，不卡 UI
            withContext(Dispatchers.Main) {        // 结果回主线程
                onResult(reply)
                updateHmi(reply)                   // 刷新车机界面
            }
        }
    }
    override fun onDestroy() { agentScope.cancel() } // 防止泄漏
}
```

**要点**：用 `SupervisorJob` 让单个 `run` 失败不影响其他；Service 销毁时 `cancel()` 整个 scope；用户退出页面可单独 `cancel` 对应 job，做到「退出即停」。

### 11.3 INTERNET 权限与内网部署

- 云模型需要 `<uses-permission android:name="android.permission.INTERNET" />`；
- **内网 Ollama 不需要公网**：把 `simpleOllamaAIExecutor()` 指向车内网关 `http://192.168.x.x:11434`，车机与网关同处车载以太网/WiFi，**不触外网**；
- 若走云，建议加「主云 + 本地兜底」（见第四节 4.4），隧道/境外漫游时自动降级本地。

### 11.4 token 与内存预算

车机 SoC 内存有限，要算账：
- **模型侧**：本地 Ollama 7B/8B 量化约吃 4~6GB RAM，确认车机空闲内存够；云模型则只耗网络与少量客户端内存。
- **上下文侧**：`maxIterations` 设上限防死循环（第八节 8.4）；长对话上 History Compression（第九节 9.5），避免 `prompt.messages` 无限膨胀把内存/token 烧穿。
- **并发侧**：多个 Agent 实例（如主驾/副驾各一个）要共享内存预算，必要时用 `parallel`/串行化调度。

### 11.5 与 WebView + JSBridge 的 HMI 联动（Agent 决策 → 驱动界面）

车机 HMI 常用 WebView 渲染、原生通过 JSBridge 注入。Koog 的定位是「**决策大脑**」：它产出「该做什么」，原生桥接方法负责「真正驱动界面/车辆」。

```
┌─────────┐  语音/触控   ┌──────────────┐  决策结果   ┌──────────────┐
│  HMI    │ ──────────▶ │ Koog Agent   │ ──────────▶ │ JSBridge 桥  │
│ WebView │             │ (Strategy图) │             │ (原生方法)   │
└─────────┘ ◀────────── └──────────────┘ ◀────────── └──────────────┘
   渲染      流式 chunk    工具调用 HAL     回调/状态    驱动 UI/车辆
```

```kotlin
// 原生侧：把 Agent 决策通过 JSBridge 推给 HMI
agent.events.onAgentFinished { e ->
    webView.post { webView.evaluateJavascript(
        "window.onAgentReply('${e.result}')", null) }
}
// 或用第九节 9.6 的 Streaming，把 chunk 实时 evaluateJavascript 做打字机
```

> 模式总结：Agent 不直接碰 UI，它只输出「文本/工具调用」；原生层把结果翻译成 JSBridge 调用，WebView 负责画。这样 Agent 逻辑和 HMI 解耦，符合你 vendor 端「逻辑/界面分离」的既有架构。

### 11.6 隐私与数据出境合规提醒

- 车控指令、位置、用户偏好属于**敏感个人信息**。走云模型时数据会出境，需评估合规（告知、最小化、加密）；
- **首选本地 Ollama**（第四节 4.3）：数据全程不出车，合规风险最低；
- 即便本地，也建议在 `description` 里约束工具「不记录用户对话到日志」，并对事件日志（第九节 9.1）做脱敏后再落盘。

---

## 第十二节 常见问题排查表

> 格式：现象 | 原因 | 指向章节 | 第一命令/动作。按这个表能解决 90% 的坑。

| 现象 | 原因 | 章节 | 第一命令/动作 |
|---|---|---|---|
| `NoClassDefFoundError` / `ClassNotFound` | 镜像缺包或新旧版本混用（0.8 + 1.2） | 13 节 | `./gradlew dependencies` 查冲突；统一升级到 1.2.0 |
| 依赖下载不动 / 超时 | Maven Central 被墙，没配国内镜像 | 3.1 节 | 在 `settings.gradle.kts` 加阿里云镜像后 `./gradlew --refresh-dependencies` |
| `401 Unauthorized` | API key 缺失/失效 | 3.2/4.1 节 | `echo $OPENAI_API_KEY` 确认环境变量；检查 key 是否过期 |
| `429 Too Many Requests` | 限流 | 4.4/9.1 节 | 加退避重试或切 `simpleOllamaAIExecutor()` 本地兜底 |
| Agent 从不调工具 | 工具 `description` 太差 / 没进 `ToolRegistry` | 7.4/7.3 节 | `asMermaidDiagram()` 看边；打印 registry 工具列表 |
| `maxIterations` 打满无结果 | LLM 陷入工具循环 | 8.4/6.1 节 | 调低 `temperature`、收窄 `description`、加 `onToolNotCalled` 兜底边 |
| 历史爆 token / OOM | 多轮对话未压缩 | 9.5/6.4 节 | 加 `nodeLLMCompressHistory` + `readSession{size>100}` 触发 |
| 主线程卡死 / ANR | `run()` 在 UI 线程调用 | 11.2 节 | 移到 `Dispatchers.IO` 的 `agentScope.launch` |
| Streaming 无输出 | 没接流式 API / HMI 未刷 | 9.6/11.5 节 | 改用 `runStreaming`，把 chunk `evaluateJavascript` 推 HMI |
| checkpoint 恢复失败 | Persistency 存储损坏/版本不符 | 9.4 节 | 清 checkpoint 存储；确认同 `sessionId`、同策略版本 |
| 图走错分支 | 出边条件顺序错（兜底 `onCondition{true}` 在前） | 6.3 节 | 把具体条件放前面，兜底放最后 |
| 工具参数解析失败 | `argsType` 没 `@Serializable` | 7.2 节 | 给参数类加 `@Serializable` 与 `ToolArgs` 实现 |
| 本地 Ollama 连不上 | 服务没起 / 端口错 / 模型没拉 | 4.3 节 | `curl http://localhost:11434/api/tags` 验证，`ollama pull llama3.2` |
| 旧教程 API 对不上 | 0.x → 1.x 重命名 | 13 节 | 以 docs.koog.ai 当前版本为准，别照抄旧博文 |
| 编译报 Kotlin 版本不符 | 用了 Kotlin < 2.2.0 | 3.1 节 | `kotlin("jvm") version "2.2.0"` 且 JDK 17 |

---

## 第十三节 版本与迁移提醒（0.x → 1.x）

Koog 迭代快，**旧教程极易踩版本坑**。牢记「以 docs.koog.ai 当前版本为准」。

| 变化点 | 0.x 时代 | 1.x（本文基线 1.2.0） | 影响 |
|---|---|---|---|
| 单次运行 API | 较繁琐 | 简化为 `AIAgent(executor, systemPrompt, llmModel)` | 快速上手更短 |
| 节点命名 | `nodeLLMSendToolResult`（单数）等 | `nodeLLMSendToolResults`（复数）等 | 抄旧代码会编译不过 |
| 策略形态 | graph/functional 未明确分裂 | 显式分 graph 策略与 functional 策略 | 选型更清晰（见 5.3） |
| 依赖坐标 | `agents-ext-jvm:0.8.0` | `koog-agents:1.2.0` + `koog-agents-additions:1.2.0-beta` | 旧包别混用 |
| 环境 | — | Kotlin 2.2.0 / JDK 17 / Gradle 8.0+ | 升级工具链 |

**迁移建议**：
1. 一次性把 `agents-ext-jvm:0.8.0` 升级到 `koog-agents:1.2.0`，避免 classpath 上两个不兼容版本（第十二节 `NoClassDefFoundError` 主因）；
2. 全局搜旧节点名（单数）替换为复数；
3. 跑通本文第十节的 demo 作为「基线」，再逐步把老逻辑迁进去；
4. 任何对不上的 API，**先查版本号再改代码**，别凭记忆补签名。

---

## 第十四节 一图总结（一次 `agent.run` 的完整时序）

```
用户输入 "开空调24度"
      │
      ▼
┌─────────────────────────────────────────────────────────┐
│ AIAgent.run(input, sessionId)  ── suspend，进入策略图状态机 │
│                                                           │
│  nodeStart                                                 │
│      │ edge → nodeCallLLM (nodeLLMRequest)                 │
│      ▼                                                     │
│  PromptExecutor ──▶ LLM(云端/本地Ollama)                   │
│      │            ◀── 回复：文字 或 工具调用                 │
│      │                                                     │
│   ┌── 文字 ──▶ nodeFinish ──▶ 返回 reply                   │
│   │                                                       │
│   └── 工具调用 ──▶ nodeExecuteTools ──▶ ToolRegistry 查工具 │
│                     │            (调用 set_ac/open_window) │
│                     ▼                                      │
│                 nodeLLMSendToolResults ──▶ 回送结果给 LLM   │
│                     │                                      │
│                     └── 再判断：文字→Finish / 工具→循环─────┘
│  （可 checkpoint 持久化；可 History Compression 截断历史）   │
└─────────────────────────────────────────────────────────┘
      │
      ▼
最终回复 ──▶（Android）Dispatchers.Main 回主线程 ──▶ JSBridge 驱动 HMI
```

**一句话记忆链**：
> 门面 `AIAgent` 持「执行器+图策略+工具集+配置+特性」五构件 → `run()` 触发图状态机 → LLM 节点经 `PromptExecutor` 决策 → 要干活就走 `ToolRegistry` 调工具 → 结果回送 LLM 循环 → 出文字即 `nodeFinish` → 全程可 checkpoint/压缩/流式 → Android 里 IO 线程跑、Main 线程回 HMI。

---

## 第十五节 关联阅读

同目录（`code/`）与根目录的姊妹篇，按主题接力：

- **根目录《大模型参数详解与运行配置.md》**：读懂 `temperature`/`maxTokens`/`top_p` 怎么调（呼应第八节 8.4）。
- **根目录《Transformer 原理通俗讲解.md》**：理解 LLM 为什么「会推理但不会干活」，从而懂 Agent/工具为何必要。
- **`kotlin/kotlin-coroutines-guide.md`**：`suspend`/`Dispatchers`/`Scope` 底层机制（呼应第三节 3.3、第十一节 11.2）。
- **`kotlin/kotlin-Java互操作详解.md`**：Koog 是 Kotlin 优先，但车机 Framework 多 Java，跨语言调用看这篇。
- **`androidApp/` 系列 Service/协程篇**：后台 Service 跑 Agent、scope 生命周期管理（第十一节 11.2 的延伸）。
- **`automotive/` 系列**：车机 HMI、车载网络、vendor HAL 对接（第十一节 11.5 的纵深）。

> 本文定位为「框架能力树 + 图状态机」混合形状：前半按能力树（执行器→策略→工具→会话→features）铺开，中段深入策略图状态机，后段落到 Android 车机。与上面姊妹篇是「同一库不同形状」，分工是「本文讲 Koog 框架本身，其余讲模型原理/语言/平台」，互不重复。

---

*全文完。版本基线：Koog 1.2.0 / Kotlin 2.2.0 / JDK 17。任何 API 与本地工程 `D:\Skills\KOOG` 不符时，以 docs.koog.ai 当前版本与本地工程为准。*
