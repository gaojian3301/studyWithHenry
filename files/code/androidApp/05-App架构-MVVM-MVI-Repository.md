# 05 App 架构：MVVM、MVI、Repository 与状态流

> App 架构不是用来套名词的，而是为了让四件事变清楚：**状态（state）放哪里、用户事件（event/intent）怎么流、业务规则归谁管、谁来测**。本文用 Kotlin + Compose 把 UI、ViewModel、UseCase、Repository、DataSource 串成一条可复制的链路。
>
> 「**Android 视角**」标注的内容与车机/系统机制相关，跳过不影响理解架构原理本身。

---

## 1. 开场：架构要回答的三个问题

一个页面从诞生到销毁，会反复面对三个问题。架构模式所有争论，本质上都是在给这三个问题找答案：

| 必须回答的问题 | 直白说法 | 答错的症状 |
|---|---|---|
| **状态放哪？** | 当前页面"长什么样"的数据存在谁手里 | 旋转屏幕后数据丢了、下拉刷新状态乱跳 |
| **事件怎么流？** | 用户点了按钮、网络回来了，这条消息走哪条路 | 点了没反应、重复请求、回调地狱 |
| **谁来测试？** | 哪一层能脱离 Android 框架单独跑单测 | 改一行 UI 要跑真机、不敢重构 |

三层答案凑齐，就是一个可维护的页面。下面这张地图给你一个时间线直觉，从"最朴素"到"最形式化"：

```text
 MVC (1979, Smalltalk)        MVP (2000s)            MVVM (~2005 WPF)         MVI (~2014, Cycle.js 思想)
 ┌──────────┐                ┌──────────┐           ┌──────────┐            ┌──────────┐
 │  Model   │                │  Model   │           │  Model   │            │  Model   │
 └────▲─────┘                └────▲─────┘           └────▲─────┘            └────▲─────┘
      │ 双向                      │ 单向                   │ 观察                    │ 产生 State
 ┌────┴─────┐ 双向             ┌─┴──┐                ┌─┴──┐                 ┌─┴──┐
 │ Controller├──────┐         │Presenter├─调用─▶   │ViewModel├──StateFlow─▶│View│
 └────┬─────┘      ▼          └───▲────┘           └───▲────┘   collect    └────┘
      │ 双向              ┌───────┴────┐               │ 事件(Intent)             │
 ┌────┴─────┐            │    View    │◀──接口回调──   │◀──dispatch──            │
 │   View   │            └────────────┘               │                        │
 └──────────┘                                         └────────────────────────┘
  View 持有 Model 引用    View 不碰 Model,            View 不写业务,            State 唯一真源,
  耦合最重               Presenter 持有两者          只渲染 State             Intent→Reduce→State
```

四个阶段的共同演化方向：**让 View 越来越"笨"，让状态越来越"集中"**。到 MVI 这一步，状态（state）被收敛成唯一真源，用户的一切操作都被建模成 Intent 再统一归约（reduce）。

> 本文定位：App 层深度基线，低于 Framework 篇。Compose 状态管理细节见 **04 篇**，协程/Flow 语法见 **01 篇**，网络与存储见 **07 篇**。本文只衔接，不展开。

**怎么读**：第 2 章先讲共识（单向数据流），第 3–6 章把 Repository、MVVM、MVI 逐一解剖，第 7 章横向对比选型，第 8 章解决"一次性事件"这个高频坑，第 9–10 章给 DI 衔接和一个车机完整例子，第 11–12 章落到测试和排查，第 13 章一图总结。

---

## 2. 先讲清一个共识：单向数据流与状态下沉

不管你叫它 MVC、MVVM 还是 MVI，现代 Android 架构的底座只有一句话：**单向数据流（Unidirectional Data Flow, UDF）**——数据从数据层流向 UI，事件从 UI 流向数据层，二者不混在同一条路上反向乱窜。

### 2.1 什么是状态下沉

"状态（state）"就是"页面此刻长什么样"：列表内容、加载中标记、错误提示、输入框文字。所谓**状态下沉**，是指把这些都收拢到 UI 层之外（ViewModel 或一个 Store）统一持有，而不是散落在 `TextView`、`变量`、`SharedPreferences` 各处。

下沉后，UI 变成**纯函数**的姿态：`UI = f(state)`。给它同一个 state，它永远画出同一个样子——这一句是所有状态管理框架的终极目标。

### 2.2 为什么"在 Activity 里写逻辑"会腐烂（不贴标签，讲病灶）

很多老代码把网络请求、数据库读写、状态变量全堆在 `Activity/Fragment` 里。它能跑，但会顺着四条裂缝腐烂：

1. **配置重建即失忆**。旋转屏幕、车机切换 day/night 主题都会重建 Activity。堆在里面的 `var list: List<X>?` 跟着一起没了，于是你写一堆 `onSaveInstanceState` 补丁，越补越乱。
2. **无法测试**。Activity 构造需要 `Context`、需要 `Instrumentation`，你无法在 JVM 单测里验证"请求回来后状态对不对"。逻辑一旦进 Activity，就等于判了单测死刑。
3. **状态分散、互相打架**。A 处改了 `isLoading`，B 处读了旧的；两个回调先后到达，谁覆盖谁看运气。没有单一状态对象，就没有"此刻真相"可言。
4. **生命周期耦合**。你在 `onResume` 里发起请求，却忘了在 `onPause` 取消，离开页面请求还在飞，回来又发一次——竞态（race）由此而生。

UDF + 状态下沉正是针对这四条裂缝的疫苗：状态交给"能跨配置重建、能在 JVM 里跑"的 ViewModel；UI 只负责把 state 画出来、把用户操作发回去。

```text
 腐烂形态（双向乱窜）            健康形态（单向闭环）
 ┌──────────┐                  ┌──────────┐
 │  Activity │◀─读/写状态─┐     │   View   │──Intent/事件──▶
 │  (又当又立)│            │     └────┬─────┘                │
 │  逻辑+UI  │─请求/回调──▶│          ▼                      ▼
 └──────────┘             │     ┌──────────┐          ┌──────────────┐
        ▲ 配置重建丢失     │     │ ViewModel │──State──▶│  Repository  │
        └─────────────────┘     │ (状态真源) │          │ (数据真源)   │
                                └──────────┘◀─Flow─────└──────────────┘
```

---

## 3. Repository 模式：数据层的边界

Repository 是**数据层（data layer）对外的唯一门面**。UI 和 ViewModel 永远只认识 `XxxRepository` 接口，至于数据来自 Retrofit、Room、还是 `SharedPreferences`，它们一概不关心。

### 3.1 数据层怎么分层

```text
 ViewModel
    │ 只认接口
    ▼
 ┌─────────────────┐
 │  XxxRepository  │  ← 门面：对外承诺"我能给你什么数据"
 └────────┬────────┘
          │ 内部组合
     ┌────┴─────┐
     ▼          ▼
 ┌──────────┐ ┌──────────┐
 │ Remote   │ │ Local    │  ← DataSource：只管"怎么取"，不管"业务含义"
 │DataSource│ │DataSource│
 │(Retrofit)│ │ (Room)   │
 └──────────┘ └──────────┘
```

### 3.2 Repository 的职责边界：它不是 Dao 的包装

常见误区是把 Repository 写成"给 Dao 套层皮"：

```kotlin
// 反例：这只是 Dao 的转发器，没有价值
class UserRepositoryImpl(private val dao: UserDao) {
    fun getUser(id: String) = dao.getById(id)   // 直接透传，等于没封装
}
```

真正有价值的 Repository 至少承担三件事：**① 决定数据来源优先级（缓存优先还是网络优先）；② 把"实体（Entity/网络 DTO）"映射成"领域模型（Domain Model）"；③ 统一错误与加载状态**。下面这个例子把"本地优先、网络兜底并回写"的策略收进 Repository，正是它该管的事：

```kotlin
interface UserRepository {
    fun observeUser(id: String): Flow<User>      // 持续观察，数据变化自动推
    suspend fun refreshUser(id: String)          // 一次性拉取并落库
}

class RealUserRepository(
    private val api: UserApi,
    private val dao: UserDao,
) : UserRepository {
    // 外部拿到的是领域模型 User，不知道底层是 Room 行还是 JSON
    override fun observeUser(id: String): Flow<User> =
        dao.observeById(id).map { it.toDomain() }

    // 网络拉取 → 落本地 → 上层通过 observeUser 自然收到更新（SSOT，见 3.4）
    override suspend fun refreshUser(id: String) {
        val remote = api.getUser(id)        // 网络 DTO
        dao.upsert(remote.toEntity())       // 转成本地 Entity 落库
    }
}
```

### 3.3 暴露 Flow 还是 suspend？一张选择表

Repository 的方法该返回 `Flow<T>` 还是 `suspend fun T`？取决于"调用方要不要持续感知变化"：

| 场景 | 返回类型 | 理由 |
|---|---|---|
| 读一个会变的集合（列表/设置/登录态） | `Flow<T>` | 数据库或远端一变，UI 自动刷新，免轮询 |
| 一次性操作（提交订单、登录、上传） | `suspend` | 只关心"成或败"这一次结果，没有"持续观察"需求 |
| 读一次且不关心后续（启动页拉配置） | `suspend` | 简化，避免为了一次性结果挂一个长期 Flow |
| 需要"发射多个阶段"（加载中→成功） | `Flow<LoadResult<T>>` | 用 Flow 把进度逐步推出来（见第 6 章错误建模） |

经验法则：**"这个值以后可能自己变"就给 Flow；"就这一次"就给 suspend**。

### 3.4 单一数据源 SSOT 原则

**单一可信数据源（Single Source of Truth, SSOT）**：UI 只从一处读"真相"。在上面的例子里，真相就是本地数据库（Room），网络只是"刷新本地"的手段。UI 通过 `observeUser` 订阅本地，网络回来后 `dao.upsert` 触发 Flow 重新发射——UI 永远不需要知道"这次是网络给的还是缓存给的"。这就避免了"内存里一份、数据库里一份、谁覆盖谁"的经典混乱。

「**Android 视角**」车机场景里，SSOT 常常是本地数据库或一个 `CarPropertyManager` 的快照缓存：车辆信号（车速、档位）来自系统服务，App 不该各自缓存一份再互相打架，而应由一个 Repository 统一订阅并下发。

---

## 4. MVVM 完整解剖

MVVM（Model-View-ViewModel）是当前 Android 官方架构的默认推荐。它的核心契约：**View 不写业务，只把 State 画出来、把事件发回去；ViewModel 持有 State 并活过配置重建**。

### 4.1 ViewModel 的三重角色

1. **状态持有者（State Holder）**：页面所有 UI 状态集中在一个 `UiState` 里，由 ViewModel 持有。
2. **配置重建存活者**：Activity 旋转重建时，ViewModel 由 `ViewModelStore` 缓存复用，`onSaveInstanceState` 的"手工补丁"因此退役。
3. **作用域（Scope）管理者**：自带 `viewModelScope`，所有协程都绑定它的生命周期，离开页面自动取消。

### 4.2 最朴素的 MVVM 写法（继承原文示例）

```kotlin
data class FeedUiState(
    val refreshing: Boolean = false,
    val items: List<FeedItem> = emptyList(),
    val error: String? = null,
)

class FeedViewModel(
    private val observeFeed: ObserveFeedUseCase,
    private val refreshFeed: RefreshFeedUseCase,
) : ViewModel() {
    // 对外只暴露不可变的 StateFlow（只读接口），内部用 Mutable 改
    val uiState: StateFlow<FeedUiState> = observeFeed()
        .map { items -> FeedUiState(items = items) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            FeedUiState(refreshing = true),
        )

    fun refresh() {
        viewModelScope.launch { refreshFeed() }   // 绑定 VM 作用域，页面走了自动取消
    }
}
```

UI 端用 `collectAsStateWithLifecycle` 收集——它比 `collectAsState` 多一层 **生命周期感知**：App 进后台时自动停止收集，回到前台再恢复，省电且避免后台空转：

```kotlin
@Composable
fun FeedScreen(vm: FeedViewModel) {
    val state by vm.uiState.collectAsStateWithLifecycle()  // 仅在 STARTED 及以上收集
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh) {
        LazyColumn {
            items(state.items, key = { it.id }) { FeedRow(it) }
        }
    }
}
```

### 4.3 viewModelScope 的取消语义

`viewModelScope` 是一个绑定到 ViewModel 的 `CoroutineScope`，它在 **`ViewModel.onCleared()` 被调用时** 取消其中所有协程。两个关键事实：

- 取消是**结构化**的：挂在这个 scope 下的每一个 `launch` 都会被递归取消，不会漏。
- 取消发生在 **ViewModel 真正销毁**时（通常是页面 finish 或宿主进程清理），**不是**配置重建时——所以旋转屏幕不会打断正在进行的请求，这正是它比"在 Activity 里 launch"安全的地方。

> 注意：`viewModelScope` 的取消是"尽力关闭通道"，若你的 `suspend` 函数在做不可打断的 native 调用，仍需自行配合 `isActive` 检查，详见 **01 篇**。

### 4.4 常见反模式

| 反模式 | 为什么坏 | 正确做法 |
|---|---|---|
| 把 `Context`/`Activity` 存进 ViewModel | VM 比 Activity 活得久，会持有已销毁的 Context → 内存泄漏甚至崩溃 | 需要 Context 时通过 `AndroidViewModel` 拿 `application`，或把依赖注入进来 |
| 在 `init` 里 `launch` 一个永不结束的 Flow 却不处理 | 流永远挂起，VM 销毁时才取消，逻辑难以追踪 | 用 `stateIn` 暴露、由 UI 端 `collect` 驱动，或在 `init` 里正确 `flatMap` 后 `stateIn` |
| 暴露 `MutableStateFlow` 给 UI | UI 能直接改状态，破坏单向流 | 对外只暴露 `StateFlow`（只读），内部持有 `Mutable` |
| 一个 VM 管五个页面的状态 | 状态膨胀、单测困难、复用性差 | 按页面/功能拆 VM，跨页面共享用 `activityViewModels` 或专门 Store |

（ViewModel 的进程级生命周期、SavedStateHandle、共享场景，留到第 5 章专门讲清，那里有和"旋转恢复"容易混为一谈的坑。）

---

## 5. ViewModel 的进程与生命周期真相

很多人以为"旋转屏幕数据不丢"就是 ViewModel 的全部价值，其实那只是它能活过**配置重建（configuration change）**；进程级死亡是另一回事，二者机制完全不同。

### 5.1 onCleared 什么时候触发

`onCleared()` 是 ViewModel 真正要被丢弃时调用的钩子，此时 `viewModelScope` 取消、所有协程被递归取消。触发条件：

- Activity/Fragment 真正 `finish`（用户返回、主动 `finish`）；
- 宿主 Fragment 被移除且不再复用；
- 进程被杀（此时**没机会**调用，见 5.2）。

注意：**旋转屏幕、折叠屏展开、day/night 切换——这些配置重建不会触发 `onCleared`**，因为 `ViewModelStore` 持有的是同一个 VM 实例。这正是 VM 与 `onSaveInstanceState` 的本质区别：后者每次重建都要走一遍，前者直接复用内存里的对象。

### 5.2 SavedStateHandle 与进程死亡恢复（和"旋转恢复"是两回事）

两个恢复场景常被混为一谈，记住这张表：

| 场景 | 触发 | ViewModel 还在吗 | 靠什么恢复 |
|---|---|---|---|
| 配置重建（旋转） | 系统销毁重建 Activity | 在，被 `ViewModelStore` 复用 | VM 内存里直接有，无需任何动作 |
| 进程死亡（LMK/系统回收） | 系统杀进程 | 不在，全新创建 | 只有 `SavedStateHandle` 里显式持久化的那点数据 |

`SavedStateHandle` 是 VM 里一个能"跨进程死亡"的小键值仓库，底层走和 `onSaveInstanceState` 同样的 Bundle 机制（**有大小限制，别塞大对象/列表**）。典型用法：保存当前选中 id、搜索关键词这类"必须恢复的小状态"。

```kotlin
class DetailViewModel(savedStateHandle: SavedStateHandle) : ViewModel() {
    // 进程死亡后重建，key 仍在；旋转则一直有
    var selectedId: String
        get() = savedStateHandle.get<String>("id").orEmpty()
        set(value) = savedStateHandle.set("id", value)
}
```

要点：**ViewModel 帮你扛的是"配置重建"，不是"进程死亡"**。进程死亡后 VM 是全新的，只有 `SavedStateHandle` 里显式存过的才能回来；大块数据应当重新从 Repository（SSOT）拉取，而不是塞进 SavedStateHandle。

### 5.3 嵌套与共享场景

- `activityViewModels()`：多个 Fragment 共享同一个宿主 Activity 作用域的 VM，适合"多页签共享筛选条件"。
- `navGraphViewModels()` / `by viewModels(navGraphId)`：限定在某个 navigation graph 作用域，离开该图即销毁。
- `hiltViewModel()` / `by viewModels()`：常规按 Fragment/Activity 作用域获取。

「**Android 视角**」车机多屏（主驾屏/副驾屏/仪表）常需要跨屏共享 VM，但**跨进程无法直接共享 ViewModel**——此时要走系统服务或数据库 SSOT 中转，而非指望 VM 跨进程。同一进程内的多 Activity（如车机 launcher 与设置）才能用 `activityViewModels` 共享。

---

## 6. MVI 完整解剖

MVI = Model-View-Intent。它把 MVVM 里"散落的多个 StateFlow/事件方法"收敛成一套严格三件套，适合交互复杂的页面。

### 6.1 三件套：Single State / Intent / Reduce

- **Single State（单一状态）**：整个页面只有**一个**不可变状态对象（对比 MVVM 可能多个 Flow）。UI 一次性 collect 这一个对象。
- **Intent / Action（意图）**：把用户操作、生命周期事件全部建模成密封类（`sealed class`）的"意图"。
- **Reduce（归约）**：纯函数 `State + Intent -> NewState`，旧状态进、新状态出，**不碰 IO、不抛副作用**。

### 6.2 完整代码：sealed class 定义 Action

```kotlin
sealed interface SearchIntent {
    data class QueryChanged(val query: String) : SearchIntent
    data object Retry : SearchIntent
    data object Clear : SearchIntent
}

data class SearchState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<ResultItem> = emptyList(),
    val error: String? = null,   // 错误也作为状态一部分，UI 统一渲染
)

class SearchViewModel(private val search: SearchUseCase) : ViewModel() {
    // extraBufferCapacity：UI 线程 tryEmit 不挂起、不丢
    private val intents = MutableSharedFlow<SearchIntent>(extraBufferCapacity = 64)

    // 单一状态源：scan 把每个 Intent 归约出下一个 State
    val state: StateFlow<SearchState> = intents
        .scan(SearchState()) { state, intent -> reduce(state, intent) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchState())

    fun dispatch(intent: SearchIntent) {
        intents.tryEmit(intent)   // UI 线程安全，不挂起
    }

    // Reduce 必须是纯函数：同输入同输出，不碰网络/IO
    private fun reduce(state: SearchState, intent: SearchIntent): SearchState =
        when (intent) {
            is SearchIntent.QueryChanged -> state.copy(query = intent.query, error = null)
            SearchIntent.Retry -> state.copy(loading = true)
            SearchIntent.Clear -> SearchState()
        }
}
```

### 6.3 状态合并（reduce）与副作用分离

Reduce 是纯函数，但"发起搜索请求"是**副作用（side effect）**：它有耗时、会失败、不能塞进 `State`（State 是给 UI 画的快照，不是动作）。MVI 把副作用与状态分开：

- **状态（State）**：UI 渲染用，进 State；
- **一次性事件 / 副作用（Effect）**：导航跳转、Toast、震动——用独立 `Channel`/`SharedFlow` 发，**不进 State**（否则配置重建会重放，见第 8 章）。

```kotlin
// 副作用单独开一条"只消费一次"的通道
private val _effects = Channel<SearchEffect>(Channel.BUFFERED)
val effects = _effects.receiveAsFlow()

private fun doSearch(query: String) = viewModelScope.launch {
    _effects.send(SearchEffect.NavigateToResult(query))  // 不发进 State
}
sealed interface SearchEffect {
    data class NavigateToResult(val query: String) : SearchEffect
}
```

错误建模也建议收敛进 State 而不是散落 `catch`。统一的错误类型（取自真实工程实践）：

```kotlin
sealed interface AppError {
    data object NetworkUnavailable : AppError
    data object Unauthorized : AppError
    data class Server(val code: Int, val message: String?) : AppError
    data class Unknown(val cause: Throwable) : AppError
}
// Repository 层统一映射：suspend fun <T> safeApiCall(block) = try { Success(block()) }
//   catch (e: IOException) { Failure(NetworkUnavailable) } catch (e: HttpException) { ... }
```

### 6.4 MVI 与 MVVM 逐项对照表

| 维度 | MVVM | MVI |
|---|---|---|
| 状态数量 | 可多个（多个 StateFlow/LiveData） | 单一状态对象（Single State） |
| 用户操作建模 | 方法调用（`fun refresh()`） | 显式 Intent/Action 密封类 |
| 状态变更方式 | 各处 `mutable.value = ...` | 统一 `reduce` 纯函数 |
| 可预测性 | 中（改值点分散） | 高（所有变更过 reduce 一处） |
| 样板代码 | 少 | 多（Intent / Effect / Reduce 三件套） |
| 调试/时间旅行 | 难 | 易（Intent 序列可重放） |
| 一次性事件 | 自行加 Channel | 原生分离 Effect 通道 |

---

## 7. 三种模式横向对比与选型

### 7.1 四模式总表

| 模式 | 状态管理 | 可测性 | 学习成本 | 适用规模 |
|---|---|---|---|---|
| MVC | View 直接持有 Model，状态分散 | 差（逻辑在 Activity） | 低 | 教学 / 极简 demo |
| MVP | Presenter 持有 View 接口，手动同步 | 中（可 mock View） | 中 | 遗留代码 / 老项目 |
| MVVM | ViewModel + Flow，状态集中 | 好（VM 可 JVM 单测） | 中 | 绝大多数 App 页面 |
| MVI | 单一状态 + Intent/Reduce，形式化 | 最好（纯 reduce 可测） | 高 | 复杂状态机 / 多人协作大页面 |

### 7.2 实际工程常见混合形态

真实项目很少"纯"某一种。最主流的是 **MVVM 骨架 + 关键页面 MVI**：

- 80% 页面用 MVVM（一个 `UiState` + 几个事件方法），开发快、样板少；
- 剩下 20% 交互复杂的（搜索、表单向导、播放器、车机多源信号聚合）用 MVI，靠 Intent 把状态变更收口，降低多人改同一页面时的冲突与回归。

你工程里的 `ObserveFeedUseCase`、`SearchUseCase` 这类**用例（UseCase）**正好坐在 VM 与 Repository 之间，承载跨仓库的业务规则（如 `SubmitOrderUseCase` 同时依赖 cart/order/user 三个 Repository）。简单转发可以不要 UseCase，规则一复杂就该建——它是 domain 层，不属于 Repository 也不属于 VM。

### 7.3 什么时候不必上 MVI

- 页面只有"加载→展示"两种状态，没有复杂交互分支；
- 团队对 MVI 样板接受度低，强行上会产出大量模板代码反而难读；
- 一次性小工具页、设置页，MVVM 足够。

记住：**模式是手段不是目的**。MVI 的回报在"状态复杂到多个开发者频繁冲突"时才显著。

---

## 8. 事件的一次性问题

"一次性事件（one-shot event）"指只该发生一次、不该被重放的东西：导航跳转、Toast、Snackbar、震动。它和"状态"本质不同——状态可以重画，事件重放就是 bug。

### 8.1 SharedFlow / Channel 对比表与选型

| 特性 | SharedFlow | Channel |
|---|---|---|
| 多订阅者 | 支持（广播） | 单消费者（一对一） |
| 重放（replay） | 可配（默认 0） | 无重放，取走即没 |
| 背压 | 缓冲策略可配 | 默认挂起 / 缓冲 |
| 典型用途 | 多 Fragment 共享事件 | 单一 VM → UI 的一次性副作用 |
| 配置重建安全 | replay>0 时可能重放 | 取走即消费，天然一次性 |

经验：**VM 向单个 UI 发一次性副作用，优先 `Channel` 转 `receiveAsFlow()`（取走即消费）；需要多观察者广播才用 SharedFlow**。

### 8.2 stateIn(WhileSubscribed(5000)) 的 5000ms 语义与陷阱

`SharingStarted.WhileSubscribed(5000)` 含义：当**最后一个订阅者取消后，再等 5000ms**，若仍无人订阅才停止上游并重置状态。

- 好处：旋转屏幕时订阅短暂断开又重连，5000ms 窗口内上游 Flow 不重启，请求/状态不丢、不重发；
- 陷阱①：若上游是会"一直发射"的热流，5000ms 后停掉再重启会有断点；
- 陷阱②：页面快速进出（如 ViewPager 预加载）可能反复触发"停止-启动"，用 `WhileSubscribed(5000, 1000)` 的第二个参数（重订阅延迟）可缓解；
- 陷阱③：**不要误以为这能恢复进程死亡**——进程死了 VM 重建，状态从头来，见第 5 章。

### 8.3 配置重建时的副作用重放问题

若把"一次性事件"错误地塞进 State（例如 `state.showToast = true`），旋转后 UI 重建重新 collect 到这个 `true`，Toast 弹两次。解法：

- 副作用走 `Channel`/`SharedFlow`（取走即消费），**绝不进 State**；
- 或采用"事件消费确认"模式（旧 `SingleLiveEvent` 思路，但推荐 Channel 更干净）。

---

## 9. 依赖注入衔接

VM / Repository / UseCase 之间靠**构造函数注入（constructor injection）**解耦，这样单测时传入 fake 即可，无需真 Retrofit / Room。

### 9.1 Hilt 最小示例

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    abstract fun bindUserRepository(impl: RealUserRepository): UserRepository
}

@HiltViewModel
class UserViewModel @Inject constructor(
    private val repository: UserRepository,   // 由 Hilt 按图注入
) : ViewModel()
```

`UserRepository` 是接口、`RealUserRepository` 是实现，Hilt 在编译期生成"接口→实现"的绑定。源码入口：`HiltViewModelFactory`、`build/generated/hilt/` 下生成代码、AndroidX `ViewModelProvider` 负责缓存复用。

你工程里用的是 **kotlin-inject**，同理：用 `@Inject` 标注构造 + `@Component` 声明依赖图，`@InjectViewModel` 注入 VM——机制不同，思想完全一致，本文不展开。

### 9.2 手写 ServiceLocator 的过渡形态

还没上 DI 框架时，可用一个简单 `object` 充当服务定位器过渡，避免全局到处 `new`：

```kotlin
object ServiceLocator {
    val userRepository: UserRepository by lazy {
        RealUserRepository(UserApi(), UserDao())
    }
}
// VM 里：private val repo = ServiceLocator.userRepository
```

缺点：依赖关系不透明、难做作用域、单测还得换实现。它是"起步够用、迟早要换 Hilt / kotlin-inject"的过渡，不是终点。

---

## 10. 用一个完整例子串起来：车机"倒车记录列表"

场景：车机"倒车影像回顾"页，列出历史倒车记录（时间、缩略图、时长），下拉刷新，点击某条跳转到播放页（一次性导航事件）。完整可复制链路：Repository 拿数据 → VM 持状态 → Compose UI 渲染 → 一次性导航事件。

```kotlin
// ---- 领域模型 ----
data class ReverseRecord(
    val id: String,
    val timestamp: Long,
    val thumbnailUrl: String,
    val durationSec: Int,
)

// ---- Repository（SSOT：本地优先，网络兜底）----
interface ReverseRecordRepository {
    fun observeRecords(): Flow<List<ReverseRecord>>
    suspend fun refresh()
}
class RealReverseRecordRepository(
    private val api: ReverseApi,
    private val dao: ReverseRecordDao,
) : ReverseRecordRepository {
    override fun observeRecords(): Flow<List<ReverseRecord>> =
        dao.observeAll().map { it.toDomain() }
    override suspend fun refresh() {
        dao.upsertAll(api.getRecords().toEntity())
    }
}

// ---- UiState + 一次性导航 Effect ----
data class ReverseListUiState(
    val loading: Boolean = false,
    val records: List<ReverseRecord> = emptyList(),
    val error: String? = null,
)
sealed interface ReverseListEffect {
    data class NavigateToPlay(val id: String) : ReverseListEffect
}

// ---- ViewModel ----
class ReverseListViewModel(
    private val repo: ReverseRecordRepository,
) : ViewModel() {
    val uiState: StateFlow<ReverseListUiState> = repo.observeRecords()
        .map { ReverseListUiState(records = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReverseListUiState(loading = true))

    private val _effects = Channel<ReverseListEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()   // 一次性事件，绝不进 State

    fun refresh() = viewModelScope.launch {
        runCatching { repo.refresh() }
            .onFailure { /* 失败进 State，不抛异常 */ }
    }
    fun onItemClick(id: String) = viewModelScope.launch {
        _effects.send(ReverseListEffect.NavigateToPlay(id))  // 导航只发一次
    }
}

// ---- Compose UI ----
@Composable
fun ReverseListScreen(
    vm: ReverseListViewModel,
    onPlay: (String) -> Unit,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    // 收集一次性事件，取走即消费
    LaunchedEffect(Unit) {
        vm.effects.collect { effect ->
            when (effect) {
                is ReverseListEffect.NavigateToPlay -> onPlay(effect.id)
            }
        }
    }
    LazyColumn {
        items(state.records, key = { it.id }) { record ->
            ReverseRow(record, onClick = { vm.onItemClick(record.id) })
        }
    }
}
```

这条链路把本文所有概念落地：Repository 是 SSOT（3.4）、VM 持单一 `UiState` 并活过旋转（4 / 5）、导航是 Channel 一次性事件（8）、UI 纯渲染（2.1）。

---

## 11. 测试性落点

架构分层的回报，在单测时兑现：VM 和 Repository 都能在 JVM 里跑，不需要真机。

### 11.1 VM 单元测试：用 runTest 验证状态序列

不依赖真网络——给 VM 注入一个 fake Repository。用 `kotlinx-coroutines-test` 的 `runTest` + `StandardTestDispatcher` 驱动，断言状态序列：

```kotlin
@Test
fun `refresh then records present`() = runTest {
    val fakeRepo = FakeReverseRecordRepository()
    val vm = ReverseListViewModel(fakeRepo)
    val states = mutableListOf<ReverseListUiState>()
    val job = launch { vm.uiState.toList(states) }   // 收集状态序列
    vm.refresh()
    advanceUntilIdle()
    assertTrue(states.last().records.isNotEmpty())
    job.cancel()
}
```

> Turbine 是简化 Flow 断言的第三方库（`state.test { awaitItem() }`），但纯 `runTest + toList` 已够用，按需引入。

### 11.2 Repository fake 的写法

Fake 实现同一接口，内存里塞测试数据，不碰 Retrofit / Room：

```kotlin
class FakeReverseRecordRepository : ReverseRecordRepository {
    private val flow = MutableStateFlow<List<ReverseRecord>>(emptyList())
    override fun observeRecords(): Flow<List<ReverseRecord>> = flow
    override suspend fun refresh() {
        flow.value = listOf(ReverseRecord("1", 0, "", 10))
    }
}
```

把"构造注入 + 接口 fake"这套测试技法系统讲透，放到 **12 篇（测试专题）**，本文只点思路。

---

## 12. 常见坑排查表

| 现象 | 原因 | 章节 | 第一命令 / 检查点 |
|---|---|---|---|
| 旋转屏幕后请求重发 | `collect` 用 `collectAsState` 而非 `WithLifecycle`，或上游未用 `WhileSubscribed(5000)` | 4.2 / 8.2 | 查是否引入 `lifecycle-runtime-compose`；看上游 `stateIn` 参数 |
| State 不更新 | `UiState` 非 `data class` 或字段未 `copy`，`equals` 恒 false | 4.1 | 给状态类加 `data class`；确认改值走 `copy` |
| collectAsState 无 UI 更新 | 后台生命周期收集被暂停 | 4.2 | 改用 `collectAsStateWithLifecycle`（STARTED 阈值） |
| 一次性事件被吞 | Channel 未 `receiveAsFlow` 收集，或 `trySend` 失败 | 8.1 | 检查 UI 端 `LaunchedEffect` 是否 collect effects |
| 一次性事件重复 | 事件错误塞进 State，重建设重放 | 8.3 | 把副作用移出 `UiState`，改走 Channel |
| VM 泄漏 | 把 Activity / View 引用存进 VM | 4.4 | LeakCanary 报警；搜 VM 里是否持有 `Context` / `View` |
| 进程恢复后状态丢失 | 只靠 VM 内存，没存 SavedStateHandle | 5.2 | 确认小状态写入 `SavedStateHandle` |
| 跨页面状态不同步 | 两页面各自 `by viewModels()`，非同一实例 | 5.3 | 共享态改用 `activityViewModels()` 或统一 Repository SSOT |
| MVI reduce 里调了 IO | reduce 非纯函数，测试不可预测 | 6.3 | 把网络 / DB 移出 reduce，发 Effect 后再 `launch` |
| 热流 5000ms 后断点 | 上游是持续热流，`WhileSubscribed` 停了重启 | 8.2 | 改用 `Lazily` 或调整停止策略 |
| 注入失败 / VM 为 null | Hilt 未 `@HiltViewModel` 或模块未装 | 9.1 | 查生成代码 `build/generated/hilt/`、是否 `@AndroidEntryPoint` |
| Repository 直接透传 Dao | 无数据来源策略 / 映射，封装无意义 | 3.2 | 检查 Repository 是否含"来源决策 + 映射 + 错误处理" |

---

## 13. 一图总结与关联阅读

### 13.1 UDF 全链 ASCII

```text
        ┌──────────────────────── 单向数据流 (UDF) ─────────────────────────┐
        │                                                                  │
  用户操作│                                                                  │
  ───────┼──────────────────────────────────────────────────────────       │
        │ │  Intent / 事件 (dispatch)                                       │
        ▼ ▼                                                                 │
   ┌────────────┐   命令/查询    ┌────────────────┐   调用接口    ┌──────────────┐│
   │   View     │ ───────────▶  │   ViewModel    │ ───────────▶ │ Repository   ││
   │ (纯渲染 f) │ ◀── State ──── │ (状态真源/作用域)│ ◀── Flow ──── │ (数据真源)   ││
   └────────────┘  collect      └───────┬────────┘   observe     └──────┬───────┘│
        ▲                               │ reduce (纯函数)                │        │
        │                               ▼                               ▼        │
        │                         ┌──────────┐ ┌──────────┐ ┌──────────┐         │
        │                         │ Effect   │ │ Remote   │ │ Local    │         │
        │                         │(一次性)  │ │DataSource│ │DataSource│         │
        └─────────────────────────│ Channel  │ └──────────┘ └──────────┘         │
                                  └──────────┘                                   │
        └──────────────────────────────────────────────────────────────────────┘
```

**一句话记忆链**：状态下沉给 VM 当真源 → 用户操作变 Intent 单向回流 → Repository 当数据真源（SSOT）→ 一次性事件走 Channel 别进 State → 旋转靠 VM、进程死靠 SavedStateHandle。

### 13.2 关联阅读

- **01 篇**：协程 `CoroutineScope`、`viewModelScope` 取消语义、Flow 操作符。
- **03 篇**：Lifecycle 与 `repeatOnLifecycle` 原理。
- **04 篇**：Compose 状态管理细节（`StateObject`、`derivedStateOf` 等，本文只衔接）。
- **06 篇**：Room 与本地数据源（Repository 的 LocalDataSource 落地）。
- **07 篇**：网络与远程数据源（Retrofit、错误处理 `safeApiCall`）。
- **10 篇**：导航与页面跳转（一次性导航事件的最终落地）。
- **12 篇**：测试专题（Repository fake、VM 状态序列断言的完整技法）。

> 源码阅读点：`ViewModelStore` / `ViewModelProvider`（VM 缓存与复用）、`SavedStateHandleController`（进程恢复）、`HiltViewModelFactory`（注入）、`InvalidationTracker`（Room Flow 触发）。重点永远不是 AndroidX 如何实现全部细节，而是看清"对象由谁创建、谁持有、何时释放"。

---

这就是全文。从"三个问题"出发，到 UDF 闭环收尾——架构的价值不在名词，而在让状态、事件、测试各归其位。
