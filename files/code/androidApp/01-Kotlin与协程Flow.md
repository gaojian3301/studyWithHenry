# 01 Kotlin 与协程 Flow：App 异步代码的底座

> 你已经用过 Kotlin 和 Compose，所以这篇不重复语法大全，而是关注 App 开发里最容易影响稳定性的几个点：协程作用域、取消、异常、Flow 状态建模、生命周期收集，以及它们在源码中该怎么看。

---

## 1. 为什么 App 开发离不开协程

Android App 里多数耗时任务都来自：网络、数据库、文件、蓝牙、定位、播放器、图片解码。协程的价值不是“把异步写成同步”，而是把异步任务和生命周期绑定起来。

常见层级：

```text
Activity / Fragment / Compose
  -> ViewModel.viewModelScope
      -> UseCase suspend operator
          -> Repository
              -> Retrofit / Room / DataStore / SDK callback
```

原则：UI 层启动协程，业务层暴露 `suspend` 或 `Flow`，数据层负责切线程和适配外部 API。

---

## 2. suspend 函数应该表达“一次性结果”

适合 `suspend` 的场景：登录、提交表单、读取一次配置、请求一次详情页。

```kotlin
class UserRepository(
    private val api: UserApi,
    private val dao: UserDao,
) {
    suspend fun refreshUser(userId: String): User {
        val remote = api.getUser(userId)
        dao.upsert(remote.toEntity())
        return remote.toDomain()
    }
}
```

调用侧：

```kotlin
class UserViewModel(
    private val repository: UserRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(UserUiState())
    val uiState: StateFlow<UserUiState> = _uiState.asStateFlow()

    fun refresh(userId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            runCatching { repository.refreshUser(userId) }
                .onSuccess { user -> _uiState.update { it.copy(loading = false, user = user) } }
                .onFailure { error -> _uiState.update { it.copy(loading = false, error = error.message) } }
        }
    }
}
```

源码阅读点：

- `androidx.lifecycle.ViewModelKt.getViewModelScope`
- `kotlinx.coroutines.JobSupport`
- `kotlinx.coroutines.DispatchedTask`

---

## 3. Flow 应该表达“持续变化的数据”

适合 Flow 的场景：数据库列表、登录态、设置项、网络状态、播放器进度、搜索输入。

```kotlin
class ArticleRepository(
    private val dao: ArticleDao,
    private val api: ArticleApi,
) {
    fun observeArticles(): Flow<List<Article>> {
        return dao.observeAll()
            .map { list -> list.map { it.toDomain() } }
    }

    suspend fun sync() {
        val remote = api.getArticles()
        dao.replaceAll(remote.map { it.toEntity() })
    }
}
```

ViewModel 合并数据：

```kotlin
data class ArticleUiState(
    val loading: Boolean = false,
    val articles: List<Article> = emptyList(),
    val error: String? = null,
)

class ArticleViewModel(
    repository: ArticleRepository,
) : ViewModel() {
    val uiState: StateFlow<ArticleUiState> = repository.observeArticles()
        .map { ArticleUiState(articles = it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ArticleUiState(loading = true),
        )
}
```

`stateIn` 的关键是把冷流转换成 `StateFlow`，并由 `viewModelScope` 控制上游订阅。

---

## 4. Dispatchers 怎么用

推荐让 Repository 内部决定 I/O 线程：

```kotlin
class FileRepository(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun readConfig(file: File): Config = withContext(ioDispatcher) {
        file.inputStream().use { stream ->
            decodeConfig(stream)
        }
    }
}
```

不要在 ViewModel 里到处写 `withContext(Dispatchers.IO)`，否则 UI 层会知道太多数据层细节。

常见规则：

- 网络：Retrofit suspend 通常已经不阻塞主线程，但复杂解析仍可放 IO/Default。
- Room：suspend DAO 会走 Room 自己的 executor。
- 文件：放 `Dispatchers.IO`。
- 大量计算：放 `Dispatchers.Default`。

---

## 5. 取消不是异常分支

协程取消通过 `CancellationException` 传播，不应该被吞掉。

错误写法：

```kotlin
viewModelScope.launch {
    try {
        repository.load()
    } catch (error: Exception) {
        showError(error)
    }
}
```

更稳的写法：

```kotlin
viewModelScope.launch {
    try {
        repository.load()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        showError(error)
    }
}
```

如果你用 `runCatching`，也要注意它会捕获 `CancellationException`。

---

## 6. callbackFlow 适配回调 API

很多 Android API 还是 callback 风格，可以转成 Flow：

```kotlin
fun LocationClient.locations(): Flow<Location> = callbackFlow {
    val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { trySend(it) }
        }
    }

    requestLocationUpdates(callback)

    awaitClose {
        removeLocationUpdates(callback)
    }
}
```

重点是 `awaitClose`，否则 UI 取消收集后底层监听还在，会产生泄漏和耗电。

---

## 7. Compose 中收集 Flow

Android 上优先用：

```kotlin
@Composable
fun ArticleRoute(viewModel: ArticleViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ArticleScreen(uiState = uiState, onRefresh = viewModel::refresh)
}
```

`collectAsStateWithLifecycle()` 背后会结合 Lifecycle，避免界面不可见时仍持续收集。

源码阅读点：

- `androidx.lifecycle.compose.FlowExtKt.collectAsStateWithLifecycle`
- `androidx.lifecycle.repeatOnLifecycle`

---

## 8. SharedFlow、Channel 和一次性事件

UI 一次性事件比如 Toast、跳转、Snackbar，不适合放进持久 `UiState`。

```kotlin
sealed interface LoginEvent {
    data object NavigateHome : LoginEvent
    data class ShowMessage(val text: String) : LoginEvent
}

class LoginViewModel : ViewModel() {
    private val _events = MutableSharedFlow<LoginEvent>()
    val events = _events.asSharedFlow()

    fun login() {
        viewModelScope.launch {
            _events.emit(LoginEvent.NavigateHome)
        }
    }
}
```

Compose 收集：

```kotlin
LaunchedEffect(Unit) {
    viewModel.events.collect { event ->
        when (event) {
            LoginEvent.NavigateHome -> navController.navigate("home")
            is LoginEvent.ShowMessage -> snackbarHostState.showSnackbar(event.text)
        }
    }
}
```

---

## 9. 常见坑

| 问题 | 根因 | 建议 |
|---|---|---|
| 页面退出后网络还在跑 | scope 用错 | UI 任务用 `viewModelScope` 或 lifecycle scope |
| Flow 重复请求 | 冷流被多处 collect | 用 `stateIn/shareIn` 共享 |
| 取消无效 | 吞掉 `CancellationException` | 单独 rethrow |
| Compose 重组触发重复任务 | 在 Composable 直接 launch | 用 `LaunchedEffect(key)` |
| callback 泄漏 | 没有 `awaitClose` | 在取消时反注册 |

---

## 10. 源码查看建议

优先看这些源码入口：

| 主题 | 源码 |
|---|---|
| ViewModel 作用域 | `androidx.lifecycle.ViewModel`、`ViewModelKt` |
| 生命周期收集 | `androidx.lifecycle.RepeatOnLifecycleKt` |
| Compose 收集 Flow | `androidx.lifecycle.compose` |
| 协程调度 | `kotlinx.coroutines.DispatchedTask`、`CoroutineScheduler` |
| Flow 状态化 | `StateFlowImpl`、`SharedFlowImpl` |

看源码时重点追：谁创建 scope，Job 父子关系是什么，取消从哪里传播，上游 Flow 什么时候开始和停止。
