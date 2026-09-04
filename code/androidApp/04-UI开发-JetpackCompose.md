# 04 UI 开发：Jetpack Compose 状态、重组与源码入口

> 你已经用过 Compose，这篇重点不放在基础控件，而是放在 Compose 真正容易踩坑的地方：状态读写、重组范围、副作用、列表性能、Navigation、和 View 系统混用。

---

## 1. Compose 和 View 的思维差异

View 系统通常是“拿到对象后修改它”：

```kotlin
titleView.text = user.name
progressBar.isVisible = loading
```

Compose 更像“根据状态重新描述 UI”：

```kotlin
@Composable
fun UserScreen(state: UserUiState) {
    if (state.loading) {
        CircularProgressIndicator()
    } else {
        Text(text = state.user.name)
    }
}
```

核心问题变成：状态从哪里来、谁持有、谁读取、读取后会触发多大范围重组。

源码入口：

- `androidx.compose.runtime.Composer`
- `androidx.compose.runtime.Recomposer`
- `androidx.compose.runtime.snapshots.Snapshot`

---

## 2. UI State 建模

推荐 ViewModel 暴露不可变 UI State：

```kotlin
data class ProfileUiState(
    val loading: Boolean = false,
    val user: User? = null,
    val errorMessage: String? = null,
)

class ProfileViewModel(
    private val repository: UserRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ProfileUiState(loading = true))
    val uiState = _uiState.asStateFlow()

    fun load(userId: String) {
        viewModelScope.launch {
            runCatching { repository.getUser(userId) }
                .onSuccess { user -> _uiState.value = ProfileUiState(user = user) }
                .onFailure { error -> _uiState.value = ProfileUiState(errorMessage = error.message) }
        }
    }
}
```

Compose 侧：

```kotlin
@Composable
fun ProfileRoute(viewModel: ProfileViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    ProfileScreen(
        state = state,
        onRetry = { viewModel.load("current") },
    )
}
```

这样 UI 和业务逻辑分开，重组时不会重新触发网络请求。

---

## 3. remember 保存的是组合中的状态

```kotlin
@Composable
fun SearchBox(onSearch: (String) -> Unit) {
    var keyword by rememberSaveable { mutableStateOf("") }

    TextField(
        value = keyword,
        onValueChange = { keyword = it },
        singleLine = true,
        keyboardActions = KeyboardActions(
            onSearch = { onSearch(keyword) },
        ),
    )
}
```

区别：

| API | 生命周期 |
|---|---|
| `remember` | 当前 Composition |
| `rememberSaveable` | 可通过 Bundle 保存，配置变更后恢复 |
| ViewModel | 页面业务状态，进程死亡可配合 SavedStateHandle |

不要把业务状态都塞进 `remember`，否则页面重建、导航返回、进程死亡恢复会变复杂。

---

## 4. 副作用 API 怎么选

| API | 适合场景 |
|---|---|
| `LaunchedEffect` | 进入组合后启动协程 |
| `DisposableEffect` | 注册和反注册监听 |
| `SideEffect` | 每次成功重组后同步外部对象 |
| `produceState` | 把外部异步数据转成 State |
| `rememberUpdatedState` | 避免 effect 捕获旧 lambda |

一次性收集事件：

```kotlin
@Composable
fun LoginRoute(viewModel: LoginViewModel, navController: NavController) {
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                LoginEvent.NavigateHome -> navController.navigate("home")
                is LoginEvent.Toast -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }
}
```

注册监听：

```kotlin
@Composable
fun KeyboardObserver(onChanged: (Boolean) -> Unit) {
    val view = LocalView.current
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            onChanged(isKeyboardVisible(view))
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
}
```

---

## 5. 重组和性能

Compose 性能问题通常来自：状态放太高、列表 item key 不稳定、频繁创建大对象、在组合阶段做重活。

列表写法：

```kotlin
@Composable
fun UserList(users: List<User>, onClick: (User) -> Unit) {
    LazyColumn {
        items(
            items = users,
            key = { it.id },
        ) { user ->
            UserRow(user = user, onClick = { onClick(user) })
        }
    }
}
```

派生状态：

```kotlin
val showScrollTop by remember {
    derivedStateOf { listState.firstVisibleItemIndex > 3 }
}
```

不要这样：

```kotlin
@Composable
fun BadScreen(repository: Repository) {
    val user = runBlocking { repository.loadUser() }
    Text(user.name)
}
```

Composable 应该是轻量、可重复执行的函数。

---

## 6. Navigation Compose

基础结构：

```kotlin
@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeRoute(onOpenDetail = { id -> navController.navigate("detail/$id") })
        }
        composable(
            route = "detail/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { backStackEntry ->
            DetailRoute(id = checkNotNull(backStackEntry.arguments?.getString("id")))
        }
    }
}
```

注意：

- 不要把大对象直接放路由参数。
- 返回结果可用 `SavedStateHandle`。
- 深链要同时关注 manifest、NavGraph 和安全校验。

源码入口：

- `androidx.navigation.NavController`
- `androidx.navigation.compose.NavHost`
- `androidx.navigation.NavBackStackEntry`

---

## 7. Compose 和 View 混用

Compose 放进 View：

```kotlin
binding.composeView.setContent {
    MaterialTheme {
        ProfileRoute()
    }
}
```

View 放进 Compose：

```kotlin
@Composable
fun MapContainer(onReady: (MapView) -> Unit) {
    AndroidView(
        factory = { context -> MapView(context).also(onReady) },
        update = { mapView -> mapView.onResume() },
    )
}
```

混用时要关注生命周期，尤其是 MapView、WebView、播放器、广告 SDK。

---

## 8. 常见问题

| 现象 | 可能原因 | 入口 |
|---|---|---|
| 页面反复请求 | 请求写在 Composable 直接执行 | `LaunchedEffect`、ViewModel |
| 列表滚动卡 | item 没 key、重组范围大 | `LazyList`、Layout Inspector |
| 状态丢失 | 用 `remember` 保存业务状态 | ViewModel、`rememberSaveable` |
| 事件重复消费 | 导航/Toast 放入持久 state | `SharedFlow` |
| AndroidView 泄漏 | 没处理生命周期 | `DisposableEffect` |

---

## 9. 源码查看建议

建议顺序：

1. `setContent` 如何创建 ComposeView。
2. `Recomposer` 如何驱动重组。
3. `SnapshotMutableStateImpl` 如何触发 invalidation。
4. `LaunchedEffectImpl` 如何绑定协程。
5. `LazyList` 如何 compose 可见 item。
6. `NavController.navigate` 如何维护返回栈。

Compose 源码比较抽象，读源码时不要从 Runtime 全量展开，先从你熟悉的 API 反向追。
