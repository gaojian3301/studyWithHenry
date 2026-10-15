# Kotlin 协程常见用法整理

这份文档不追求把所有 API 全背下来，而是帮你建立一个“什么时候用什么”的实际心智模型。
重点放在 Android / 后端开发里最常见、最容易混淆、最值得掌握的协程用法。

## 1. 先建立协程思维

很多人卡协程，不是因为 API 不会背，而是脑子里还在用“线程”理解一切。协程更适合这样理解：

你要先知道

- **协程不是线程**，它是更轻量的异步任务单位。
- **suspend 不是开新线程**，而是“这个函数可以挂起，稍后恢复”。
- **协程最核心的问题**不是并发，而是把异步代码写得像同步代码一样清楚。

你要形成的直觉

- 需要执行一个异步任务：考虑 `launch` 或 `async`
- 需要返回结果：优先想 `suspend` / `withContext`
- 需要并发等待多个结果：考虑 `async + await`
- 需要持续产出数据：考虑 `Flow`

**一句话：** 协程是“异步流程的组织方式”。你真正要掌握的是结构化并发，而不是只会写几个 builder。

## 2. 怎么启动协程

### 2.1 launch：只关心执行，不关心返回值

适合“做一件事”，例如刷新 UI、发请求后更新状态、写日志、启动后台任务。

```kotlin
viewModelScope.launch {
    val user = repository.loadUser()
    _uiState.value = UiState.Success(user)
}
```

**记法：** `launch` = 发射一个任务，重点是“去执行”。返回的是 `Job`，不是结果。

### 2.2 async：需要并发计算并拿结果

适合“最终要拿值回来”的场景，返回 `Deferred<T>`，通过 `await()` 获取结果。

```kotlin
coroutineScope {
    val userDeferred = async { repository.loadUser() }
    val postDeferred = async { repository.loadPosts() }

    val user = userDeferred.await()
    val posts = postDeferred.await()
    UiModel(user, posts)
}
```

**别滥用：** 如果只是顺序执行并返回一个值，很多时候直接写 `suspend` 函数就够了，不需要上来就 `async`。

### 2.3 runBlocking：桥接阻塞世界

常用于测试、main 函数、脚本入口。它会阻塞当前线程，直到协程体执行结束。

```kotlin
fun main() = runBlocking {
    val result = repository.loadUser()
    println(result)
}
```

**Android 里不要乱用：** 尤其不要在主线程、点击事件、ViewModel 正常业务代码里用 `runBlocking`。

## 3. suspend 到底是什么

`suspend` 不是“异步版函数”这么简单，它表达的是：这个函数可以在执行过程中挂起，但不会阻塞线程。

```kotlin
suspend fun loadUserProfile(userId: String): UserProfile {
    val user = api.getUser(userId)
    val posts = api.getPosts(userId)
    return UserProfile(user, posts)
}
```

它适合干什么

- 网络请求
- 数据库读取
- 需要等待结果的 IO 操作
- 一串异步步骤的顺序编排

它不代表什么

- 不代表自动切线程
- 不代表自动并行
- 不代表一定更快
- 不代表能随便在任何地方调用

**最重要的一点：** `suspend` 只是说明“这个函数可挂起”，真正在哪个线程跑，取决于调用它的协程上下文以及你是否用了 `withContext`。

## 4. 线程切换与 withContext

### 4.1 withContext：最常见也最实用

当你需要明确切到某个 dispatcher 执行一段代码时，用 `withContext`。

```kotlin
suspend fun readCache(): String = withContext(Dispatchers.IO) {
    file.readText()
}
```

它很适合包装 IO、数据库、文件操作，让调用方继续用“同步顺序”的方式写代码。

### 4.2 常见 Dispatcher 怎么理解

| Dispatcher | 适用场景 | 注意点 |
| --- | --- | --- |
| `Dispatchers.Main` | Android 主线程、更新 UI | 不要做耗时任务 |
| `Dispatchers.IO` | 网络、数据库、文件读写 | 最常用的后台 dispatcher |
| `Dispatchers.Default` | CPU 密集型计算 | 如排序、解析、复杂计算 |
| `Dispatchers.Unconfined` | 少数特殊场景 | 日常业务基本别用 |

### 4.3 一个非常常见的写法

```kotlin
viewModelScope.launch {
    _uiState.value = UiState.Loading

    val result = withContext(Dispatchers.IO) {
        repository.fetchData()
    }

    _uiState.value = UiState.Success(result)
}
```

**记法：** 外层 `launch` 负责流程，内层 `withContext` 负责切线程做重活。

## 5. coroutineScope / supervisorScope

### 5.1 coroutineScope：子任务是“一荣俱荣，一损俱损”

```kotlin
suspend fun loadPage(): PageData = coroutineScope {
    val banner = async { api.loadBanner() }
    val list = async { api.loadList() }
    PageData(banner.await(), list.await())
}
```

如果其中一个子协程失败，整个 scope 会失败，其他子协程也会被取消。

### 5.2 supervisorScope：一个孩子挂了，不拖死兄弟

```kotlin
suspend fun loadWidgets(): WidgetState = supervisorScope {
    val weather = async { runCatching { api.loadWeather() }.getOrNull() }
    val news = async { runCatching { api.loadNews() }.getOrNull() }
    WidgetState(weather.await(), news.await())
}
```

适合“局部失败可接受”的页面拼装场景，比如多个卡片并发加载，坏一个不影响全部。

coroutineScope

更严格。适合多个子任务必须共同成功的情况。

supervisorScope

更宽松。适合部分失败可容忍的情况。

## 6. Job、取消、超时

### 6.1 Job：协程的生命周期句柄

```kotlin
val job = scope.launch {
    repeat(100) {
        delay(500)
        println("working $it")
    }
}

job.cancel()
```

拿到 `Job`，你就可以取消、join、检查状态。

### 6.2 协程取消是“协作式”的

意思是：协程代码需要跑到可取消点，或者主动检查取消状态，取消才会生效。

```kotlin
scope.launch {
    while (isActive) {
        doSmallWork()
    }
}
```

**常见坑：** 纯 CPU 死循环里不检查 `isActive`、不调用挂起函数，协程可能不响应取消。

### 6.3 join / cancelAndJoin

```kotlin
val job = scope.launch { ... }
job.join() // 等待执行完

job.cancelAndJoin() // 先取消，再等待结束
```

### 6.4 超时控制

```kotlin
val result = withTimeout(3_000) {
    api.fetchData()
}

val nullableResult = withTimeoutOrNull(3_000) {
    api.fetchData()
}
```

`withTimeout` 超时会抛异常，`withTimeoutOrNull` 超时则返回 null。

## 7. 异常处理

### 7.1 最常见的方式：try-catch

```kotlin
viewModelScope.launch {
    try {
        val data = repository.fetchData()
        _uiState.value = UiState.Success(data)
    } catch (e: Exception) {
        _uiState.value = UiState.Error(e.message ?: "unknown error")
    }
}
```

### 7.2 CoroutineExceptionHandler 适合兜底，不适合替代业务处理

```kotlin
val handler = CoroutineExceptionHandler { _, throwable ->
    logError(throwable)
}

scope.launch(handler) {
    riskyTask()
}
```

**经验上：** 页面状态、接口失败、重试提示，这些业务异常还是尽量在具体协程里 `try-catch` 处理。`CoroutineExceptionHandler` 更像最后一层兜底。

### 7.3 launch 和 async 的异常感受不一样

- `launch` 里的异常更像“直接抛出来”。
- `async` 里的异常通常会在 `await()` 时暴露。

```kotlin
val deferred = scope.async {
    error("boom")
}

try {
    deferred.await()
} catch (e: Exception) {
    println("catch: ${e.message}")
}
```

## 8. Flow 常见用法

当数据不是“一次性结果”，而是“持续发射的一串值”时，Flow 就很合适。

### 8.1 最基础：定义与 collect

```kotlin
fun tickerFlow() = flow {
    repeat(5) {
        emit(it)
        delay(1000)
    }
}

viewModelScope.launch {
    tickerFlow().collect { value ->
        println(value)
    }
}
```

### 8.2 像集合一样变换

```kotlin
repository.userFlow()
    .filter { it.isActive }
    .map { it.name }
    .collect { name ->
        println(name)
    }
```

### 8.3 常见操作符

| 操作符 | 用途 | 典型场景 |
| --- | --- | --- |
| `map` | 转换数据 | DTO -> UI Model |
| `filter` | 筛选数据 | 只保留合法状态 |
| `debounce` | 防抖 | 搜索框输入 |
| `distinctUntilChanged` | 去掉重复值 | 避免重复刷新 UI |
| `flatMapLatest` | 只关心最新请求 | 联想搜索、切换条件查询 |
| `combine` | 组合多个流 | 表单状态汇总 |

### 8.4 StateFlow / SharedFlow 怎么记

StateFlow

- 有当前值
- 适合表示 UI 状态
- 新订阅者会立即收到最新值

SharedFlow

- 更像广播事件流
- 适合一次性事件、消息分发
- 是否重放由配置决定

```kotlin
private val _uiState = MutableStateFlow(UiState())
val uiState: StateFlow<UiState> = _uiState

private val _events = MutableSharedFlow<UiEvent>()
val events = _events.asSharedFlow()
```

## 9. Android 里怎么落地

### 9.1 ViewModel 里：优先用 viewModelScope

```kotlin
class UserViewModel(
    private val repository: UserRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(UserUiState())
    val uiState = _uiState.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true)
            try {
                val user = withContext(Dispatchers.IO) {
                    repository.loadUser()
                }
                _uiState.value = UserUiState(user = user, loading = false)
            } catch (e: Exception) {
                _uiState.value = UserUiState(error = e.message, loading = false)
            }
        }
    }
}
```

### 9.2 生命周期相关：优先用 lifecycleScope / repeatOnLifecycle

```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.uiState.collect { state ->
            render(state)
        }
    }
}
```

这样页面不可见时会自动停止收集，可见时再恢复，避免无意义工作和泄漏。

### 9.3 Repository 层常见姿势

```kotlin
class UserRepository(
    private val api: UserApi,
    private val dao: UserDao
) {
    suspend fun loadUser(): User = withContext(Dispatchers.IO) {
        dao.getUser() ?: api.getUser().also { dao.save(it) }
    }
}
```

**推荐分层习惯：** UI 层负责启动协程，Repository 层提供 `suspend` / `Flow` 能力，线程切换尽量收口在明确的位置。

## 10. 最常见误区

### 误区 1：suspend = 后台线程

错。`suspend` 不负责切线程，切线程要靠上下文或 `withContext`。

### 误区 2：async 比 suspend 高级

错。`async` 主要解决“并发 + 取结果”，不是所有返回值场景都该用它。

### 误区 3：哪里都能 GlobalScope.launch

错。它容易脱离生命周期，很多时候会带来难管的任务和泄漏风险。

### 误区 4：取消就是立刻停止

错。取消是协作式的，需要挂起点或主动检查取消状态。

### 误区 5：Flow 就是 LiveData 替代品

不完全对。Flow 是更通用的异步数据流模型，不只是 UI 观察工具。

### 误区 6：异常只要加 handler 就行

错。业务异常处理还是要回到具体协程逻辑里。

## 11. Channel 常见用法

如果说 Flow 更像“持续的数据流”，那 Channel 更像“协程之间传消息的管道”。你可以把它先理解成一个协程安全的队列：一边 `send`，另一边 `receive`。

### 11.1 最基础的 send / receive

```kotlin
val channel = Channel<Int>()

launch {
    channel.send(1)
    channel.send(2)
    channel.close()
}

launch {
    for (value in channel) {
        println(value)
    }
}
```

**记法：** Channel 主要解决“协程和协程之间怎么传东西”。

### 11.2 为什么很多人会把它和 Flow 搞混

| 对比项 | Channel | Flow |
| --- | --- | --- |
| 核心定位 | 协程间通信、任务队列、事件传递 | 异步数据流、响应式处理 |
| 使用方式 | `send / receive` | `emit / collect` |
| 典型场景 | 生产者-消费者、任务分发 | 状态流、搜索流、数据变换链 |
| 更像什么 | 队列 / 管道 | 流 / 序列 / 响应式流 |

**实战判断：** 如果你主要在“表达状态变化”或“做一连串流式变换”，优先 Flow；如果你在“传递消息”或“排队消费任务”，Channel 更顺手。

### 11.3 三种你最常见的 Channel 形态

| 类型 | 特点 | 适合场景 |
| --- | --- | --- |
| `Channel()` | 默认 rendezvous，无缓冲；send 和 receive 要彼此配合 | 严格一发一收 |
| `Channel(capacity = N)` | 带缓冲区 | 生产速度可能大于消费速度 |
| `Channel.CONFLATED` | 只保留最新值，旧值会被覆盖 | 只关心最新事件/状态 |

```kotlin
val buffered = Channel<String>(capacity = 10)
val conflated = Channel<Int>(Channel.CONFLATED)
```

### 11.4 生产者-消费者是它最经典的用法

```kotlin
val taskChannel = Channel<String>(capacity = 20)

launch {
    listOf("a", "b", "c").forEach { taskChannel.send(it) }
    taskChannel.close()
}

repeat(2) {
    launch {
        for (task in taskChannel) {
            println("consume: $task")
        }
    }
}
```

这类模式在下载队列、上报队列、后台任务分发里很常见。

### 11.5 receiveCatching 比 receive 更稳

```kotlin
when (val result = channel.receiveCatching()) {
    is ChannelResult.Success -> println(result.getOrNull())
    else -> println("channel closed")
}
```

当你不确定 channel 是否已经关闭时，这种写法更安全。

### 11.6 produce / actor 要不要学

`produce` 和 `actor` 是基于 Channel 的封装思路：

- `produce`：更偏“创建一个不断产出数据的协程”
- `actor`：更偏“创建一个串行处理消息的协程”

但在现在实际业务里，很多场景已经被 Flow、SharedFlow、StateFlow 覆盖，所以你可以先把核心精力放在 Channel 本体和 Flow 的边界上。

### 11.7 Android / Compose 里怎么用它更合适

- 如果是 **页面状态**：优先 `StateFlow`
- 如果是 **一次性 UI 事件**：通常优先 `SharedFlow`，有时也会用 Channel
- 如果是 **后台任务排队**：Channel 很合适

**别一上来就用 Channel 做所有事件分发。** 在 Android UI 层，很多时候 `SharedFlow` 更自然；Channel 更适合明显的“收发消息 / 队列消费”模型。

### 11.8 你刚才那个疑问，最适合这样记

| 类型 | 多个观察者都能收到吗 | 会不会保留最新值 | 消息会不会被“消费掉” | 最适合 |
| --- | --- | --- | --- | --- |
| `StateFlow` | 会 | 会，永远有一个当前值 | 不会按“消费一次就没了”理解 | 页面状态、UI state |
| `SharedFlow` | 会 | 看 replay 配置 | 通常不是单消费者模型 | 广播事件、多个订阅者 |
| `Channel` | 通常不是 | 默认不强调“保留最新状态” | 会，更像消息被某个消费者拿走 | 单消费消息、任务队列、生产者消费者 |

**把它们想象成三种完全不同的东西：**

- **StateFlow**：公告栏，所有人都能看到当前最新内容
- **SharedFlow**：广播喇叭，广播出去后，正在听的人都能听见
- **Channel**：传送带/队列，一个包裹通常只会被一个人拿走

### 11.9 一个最容易记住的例子

```kotlin
// StateFlow: 多个地方都能看到当前状态
val state = MutableStateFlow("idle")

// SharedFlow: 一个事件可以广播给多个正在收听的人
val events = MutableSharedFlow<String>()

// Channel: 一条消息通常只会被一个消费者拿到
val channel = Channel<String>()
```

**所以你的那句话可以修正成：** “Flow 家族里，`StateFlow` 和 `SharedFlow` 更偏多观察者；而 `Channel` 更偏单条消息被单个消费者拿走。” 这样理解就比较准确了。

## 12. 一页速查表

| 需求 | 优先考虑 | 一句理解 |
| --- | --- | --- |
| 启动一个不关心返回值的任务 | `launch` | 去做这件事 |
| 并发执行并拿多个结果 | `async + await` | 并发算，最后取值 |
| 封装一个可等待结果的异步函数 | `suspend fun` | 把异步流程写成顺序代码 |
| 切到 IO / Default 做事 | `withContext` | 明确切线程跑一段代码 |
| 多个子任务必须一起成功 | `coroutineScope` | 一个失败，整体失败 |
| 多个子任务允许部分失败 | `supervisorScope` | 失败隔离 |
| 控制生命周期 | `Job` | 可以取消、等待、跟踪状态 |
| 限制最长执行时间 | `withTimeout` | 超过时间就中断 |
| 持续发射数据 | `Flow` | 异步数据流 |
| 表示页面状态 | `StateFlow` | 永远有一个最新值 |
| 发送一次性事件 | `SharedFlow` | 更像广播事件 |

**最后给你的抓手：** 如果你总觉得协程乱，先只抓住 4 个高频组合：

- `viewModelScope.launch { ... }`
- `suspend fun ...`
- `withContext(Dispatchers.IO) { ... }`
- `Flow / StateFlow collect`

先把这四个用顺，再去扩展并发、取消、异常传播、冷流热流这些更进阶的话题，会容易很多。

Kotlin 协程常见用法整理 · 适合从“知道 API”过渡到“知道什么时候该用什么”
