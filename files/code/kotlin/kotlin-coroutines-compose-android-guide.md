# Android Compose 中的 Kotlin 协程使用整理

这份文档专门讲协程在 Android，尤其是 Jetpack Compose 里的落地方式。
核心目标不是“背 API”，而是帮你分清：哪些事该放在 ViewModel，哪些事该放在 Composable，哪些副作用该怎么启动和回收。

## 1. 先建立 Android/Compose 分工

ViewModel 负责

- 发请求、查数据库、调 repository
- 维护页面状态
- 暴露 `StateFlow` / `SharedFlow`
- 处理业务逻辑和异常

Composable 负责

- 展示状态
- 响应用户交互
- 处理与 UI 生命周期强绑定的副作用
- 桥接动画、滚动、Snackbar、导航等 UI 行为

**一句话：** 长生命周期业务任务优先放在 `ViewModel`，短生命周期 UI 副作用才放在 Compose 侧。

## 2. ViewModel 里怎么用协程

### 2.1 默认起点：viewModelScope.launch

```kotlin
class UserViewModel(
    private val repository: UserRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(UserUiState())
    val uiState = _uiState.asStateFlow()

    fun loadUser() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                val user = repository.loadUser()
                _uiState.update { it.copy(loading = false, user = user) }
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, error = e.message) }
            }
        }
    }
}
```

**记法：** 页面逻辑一律先想 `viewModelScope`，不要把请求直接塞进 Composable。

### 2.2 Repository 层封装 suspend / Flow

```kotlin
class UserRepository(
    private val api: UserApi,
    private val dao: UserDao
) {
    suspend fun loadUser(): User = withContext(Dispatchers.IO) {
        dao.getUser() ?: api.fetchUser().also { dao.insert(it) }
    }

    fun observeUser(): Flow<User> = dao.observeUser()
}
```

这样 ViewModel 就能保持“业务编排”的角色，而不是被 IO 细节污染。

## 3. Compose 里怎么收集状态

### 3.1 最推荐：collectAsStateWithLifecycle

```kotlin
@Composable
fun UserScreen(viewModel: UserViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    UserContent(
        state = uiState,
        onRetry = viewModel::loadUser
    )
}
```

这是现在 Compose 页面收集 `StateFlow` 最稳妥的方式。它把生命周期问题处理得更自然。

**不要图省事直接 everywhere collectAsState()**。如果数据源跟生命周期密切相关，优先用 `collectAsStateWithLifecycle()`。

### 3.2 状态上屏的正确思路

- ViewModel 提供 `StateFlow<UiState>`
- Composable 用 `collectAsStateWithLifecycle()` 转成 State
- UI 只根据 state 渲染，不自己偷偷发请求

## 4. LaunchedEffect

`LaunchedEffect` 适合在 Composable 进入组合后启动一个与当前 UI 生命周期绑定的协程。

### 4.1 首次进入页面触发副作用

```kotlin
@Composable
fun DetailScreen(viewModel: DetailViewModel = viewModel()) {
    LaunchedEffect(Unit) {
        viewModel.load()
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    DetailContent(uiState)
}
```

**注意：** 这种写法可用，但更理想的是很多初始化请求直接在 ViewModel init 或显式事件里完成。`LaunchedEffect(Unit)` 不该沦为“页面里随手发请求的万能入口”。

### 4.2 key 变化时重新执行

```kotlin
LaunchedEffect(userId) {
    viewModel.loadUser(userId)
}
```

当 `userId` 变化，旧协程会取消，新协程会启动。

### 4.3 收集一次性事件

```kotlin
LaunchedEffect(Unit) {
    viewModel.events.collect { event ->
        when (event) {
            is UiEvent.ShowToast -> toast(event.message)
            UiEvent.NavigateBack -> navController.popBackStack()
        }
    }
}
```

这是 Compose 中收集 `SharedFlow` / `Channel` 事件的高频用法。

## 5. rememberCoroutineScope

当你需要在点击事件里手动启动一个跟当前 Composable 生命周期绑定的协程，可以用 `rememberCoroutineScope()`。

```kotlin
@Composable
fun SnackBarDemo(snackbarHostState: SnackbarHostState) {
    val scope = rememberCoroutineScope()

    Button(onClick = {
        scope.launch {
            snackbarHostState.showSnackbar("Saved")
        }
    }) {
        Text("Save")
    }
}
```

**典型场景：** Snackbar、scrollState.animateScrollTo()、pager 动画、短小 UI 交互副作用。

**不要滥用：** 如果是业务请求、加载列表、提交表单这类更像页面逻辑的任务，仍然优先放在 ViewModel。

## 6. produceState / snapshotFlow

### 6.1 produceState：把协程结果包装成 Compose State

```kotlin
@Composable
fun rememberAvatar(url: String): State<ImageBitmap?> {
    return produceState<ImageBitmap?>(initialValue = null, url) {
        value = imageRepository.load(url)
    }
}
```

适合把一个异步结果直接桥接成页面状态，但别把它写成大型业务层。

### 6.2 snapshotFlow：把 Compose 状态转成 Flow

```kotlin
LaunchedEffect(listState) {
    snapshotFlow { listState.firstVisibleItemIndex }
        .distinctUntilChanged()
        .collect { index ->
            analytics.logVisibleIndex(index)
        }
}
```

特别适合监听滚动、输入、UI 派生状态，并用 Flow 操作符做节流、去重等处理。

## 7. 一次性事件怎么处理

### 7.1 页面状态用 StateFlow，事件用 SharedFlow

```kotlin
private val _uiState = MutableStateFlow(LoginUiState())
val uiState = _uiState.asStateFlow()

private val _events = MutableSharedFlow<LoginEvent>()
val events = _events.asSharedFlow()
```

### 7.2 ViewModel 发事件

```kotlin
viewModelScope.launch {
    _events.emit(LoginEvent.ShowSnackBar("登录成功"))
}
```

### 7.3 Compose 收事件

```kotlin
LaunchedEffect(Unit) {
    viewModel.events.collect { event ->
        when (event) {
            is LoginEvent.ShowSnackBar -> snackbarHostState.showSnackbar(event.message)
            LoginEvent.NavigateHome -> navController.navigate("home")
        }
    }
}
```

为什么不用 StateFlow 发事件

因为事件是“一次性的”，而 `StateFlow` 会保留最新值，新订阅者进来可能误消费旧事件。

Channel 能不能做事件

能，但现在很多 Compose 场景下，`SharedFlow` 的表达更自然；Channel 更像消息管道。

### 7.4 在 Compose 里怎么选：StateFlow / SharedFlow / Channel

| 你要表达的东西 | 推荐 | 原因 |
| --- | --- | --- |
| 页面当前状态 | `StateFlow` | 页面随时都需要拿到最新状态 |
| 一次性 UI 事件 | `SharedFlow` | 更符合广播/收集模型，配合 `LaunchedEffect` 很自然 |
| 单消费者任务队列 | `Channel` | 更像消息被某个协程消费掉 |

**你现在可以这样记：**

- `StateFlow`：UI 状态
- `SharedFlow`：UI 事件广播
- `Channel`：单消费消息/任务分发

### 7.5 为什么很多 Compose 页面不用 Channel 发事件

因为 Compose 页面的一次性事件，很多时候并不是“必须只能有一个消费者拿到”。更常见的需求是：

- ViewModel 发一个事件
- 当前页面收集它
- 触发 Snackbar、导航、Toast 等 UI 副作用

这种模型下，`SharedFlow` 往往更直接；而 `Channel` 更适合“任务排队”和“谁拿到谁处理”的语义。

## 8. 生命周期与 repeatOnLifecycle

如果你不是在纯 Compose 的 `collectAsStateWithLifecycle()` 里，而是在 Activity / Fragment 层自己收集 Flow，推荐这样写：

```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.uiState.collect { state ->
            render(state)
        }
    }
}
```

这样界面不可见时会自动停止收集，可见时再恢复，避免多余工作和生命周期问题。

## 9. 常见实战模式

### 9.1 搜索框联想

```kotlin
val keyword = MutableStateFlow("")

val result = keyword
    .debounce(300)
    .distinctUntilChanged()
    .flatMapLatest { query -> repository.search(query) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
```

### 9.2 下拉刷新

```kotlin
fun refresh() {
    viewModelScope.launch {
        runCatching { repository.refresh() }
            .onFailure { _events.emit(UiEvent.ShowSnackBar("刷新失败")) }
    }
}
```

### 9.3 Snackbar / 导航 / 滚动

这类都是 UI 副作用，通常由 Compose 侧通过 `LaunchedEffect` 或 `rememberCoroutineScope` 接住。

## 10. 最常见误区

### 误区 1：Composable 里直接发网络请求

除非是很明确的 UI 绑定副作用，否则大多数请求应该放到 ViewModel。

### 误区 2：把 LaunchedEffect 当万能入口

它适合副作用绑定，不适合承载一坨业务流程。

### 误区 3：事件也放 StateFlow

容易重复消费旧值，一次性事件更适合 SharedFlow / Channel。

### 误区 4：点击事件也去 ViewModel 里 showSnackbar

Snackbar、滚动、动画这些 UI 行为通常应该在 Compose 侧执行。

### 误区 5：哪里都 collect

要么用 `collectAsStateWithLifecycle()`，要么明确生命周期，不然容易重复收集。

### 误区 6：忘了 key 变化会重启 LaunchedEffect

key 选错会导致重复请求或意外取消。

## 11. 一页速查表

| 场景 | 优先做法 | 说明 |
| --- | --- | --- |
| 页面加载数据 | `viewModelScope.launch` | 业务逻辑放 ViewModel |
| 页面展示状态 | `StateFlow + collectAsStateWithLifecycle()` | 最常见的 Compose 状态收集方式 |
| 首次进入页面触发副作用 | `LaunchedEffect(Unit)` | 注意别承载过多业务逻辑 |
| 参数变化后重新加载 | `LaunchedEffect(key)` | key 变化会取消旧任务并重启 |
| 按钮点击后 showSnackbar / 动画 / 滚动 | `rememberCoroutineScope()` | UI 交互副作用 |
| 异步结果转 Compose State | `produceState` | 轻量桥接，不要滥用成业务层 |
| 把 Compose 状态变成 Flow | `snapshotFlow` | 监听滚动、输入等 UI 状态 |
| 一次性事件 | `SharedFlow + LaunchedEffect` | Snackbar、导航、Toast |

**最终抓手：** 在 Compose 里，你只要先分清三件事，协程就不会太乱：

- 这是业务任务，还是 UI 副作用？
- 这是持续状态，还是一次性事件？
- 这个协程应该跟 ViewModel 走，还是跟 Composable 生命周期走？

Android Compose 中的 Kotlin 协程使用整理 · 重点是分清 ViewModel、Compose、副作用、状态流和事件流
