# Android Lifecycle 常见用法：Activity、Compose 与协程结合

这份文档专门讲 Android 里的 lifecycle。重点不是只背生命周期回调顺序，而是弄清楚：
lifecycle 到底解决什么问题、在 Activity 和 Compose 里怎么用、以及它和协程该怎么配合才不容易泄漏、不容易写错。

## 1. lifecycle 到底是什么

Android 里的 `lifecycle`，本质上是在描述一个组件“现在活到哪个阶段了”。
最常见的组件就是 `Activity` 和 `Fragment`。

它存在的意义，不是为了让你背 `onCreate / onStart / onResume` 这些函数，
而是为了让你的代码知道：**什么时候该开始工作，什么时候该停止工作**。

没有 lifecycle 时的问题

- 页面销毁了，后台任务还在跑
- 页面不可见了，Flow 还在 collect
- 回调对象忘记解绑，导致内存泄漏
- UI 已经不在了，还试图更新界面

有 lifecycle 后的核心收益

- 代码能感知页面是否可见
- 资源注册和释放更有边界
- 协程能跟生命周期自动取消
- Flow 收集可以按可见性自动启停

**一句话理解：** lifecycle 就是“组件活着的阶段信息”，你写的任务、监听器、协程，都应该尽量跟这个阶段信息绑定。

## 2. 生命周期状态和事件

Lifecycle 常见会同时提到两套概念：

- **State**：当前处于什么状态
- **Event**：刚刚发生了什么事件

| 概念 | 典型值 | 怎么理解 |
| --- | --- | --- |
| State | `INITIALIZED`、`CREATED`、`STARTED`、`RESUMED`、`DESTROYED` | 组件现在活到哪一步了 |
| Event | `ON_CREATE`、`ON_START`、`ON_RESUME`、`ON_PAUSE`、`ON_STOP`、`ON_DESTROY` | 组件刚经历了哪个回调事件 |

### 2.1 最实用的状态理解

- `CREATED`：页面已经创建，但不一定可见
- `STARTED`：页面对用户可见了
- `RESUMED`：页面可交互，处于前台活跃状态
- `DESTROYED`：页面彻底结束

**开发里最常用的判断不是事件，而是状态：** 比如“只要页面至少在 STARTED 状态，就开始收集数据”。

## 3. lifecycle 的常见用法

### 3.1 获取 lifecycle

```kotlin
val lifecycle = lifecycleOwner.lifecycle
```

只要对象实现了 `LifecycleOwner`，你就可以拿到它的生命周期对象。

### 3.2 判断当前状态

```kotlin
if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
    // 当前页面至少可见
}
```

### 3.3 注册观察者

```kotlin
class MyObserver : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) {
        // 页面可见时开始某些工作
    }

    override fun onStop(owner: LifecycleOwner) {
        // 页面不可见时停止某些工作
    }
}

lifecycle.addObserver(MyObserver())
```

这种方式适合把“跟生命周期有关的行为”从 Activity/Fragment 里拆出去。

### 3.4 和资源注册/释放配合

比如：

- 注册传感器监听
- 打开摄像头或定位
- 绑定播放器
- 页面可见时开始轮询

**不要只会在 onCreate 里开东西，却忘了在 onStop/onDestroy 里关。** lifecycle 的价值很大一部分就在于帮你把“开始”和“结束”成对管理。

## 4. 在 Activity 中的常见用法

### 4.1 最基础：根据生命周期做初始化和释放

```kotlin
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 初始化 UI、ViewModel、Adapter
    }

    override fun onStart() {
        super.onStart()
        // 页面可见，开始一些轻量工作
    }

    override fun onStop() {
        super.onStop()
        // 页面不可见，停止对应工作
    }
}
```

这属于最传统的写法，但只是基础层。更现代一点的做法，是把观察逻辑交给 lifecycle-aware API。

### 4.2 用 observer 代替把所有逻辑塞进 Activity

```kotlin
class AnalyticsObserver : DefaultLifecycleObserver {
    override fun onResume(owner: LifecycleOwner) {
        logPageShow()
    }

    override fun onPause(owner: LifecycleOwner) {
        logPageHide()
    }
}

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(AnalyticsObserver())
    }
}
```

这种拆法的优点是：

- Activity 更干净
- 可复用
- 更适合把统计、播放器、定位等逻辑封装出去

### 4.3 在 Activity 里收集 UI 数据

如果你要观察 ViewModel 的状态流，不推荐直接裸 collect 到底，而是结合生命周期：

```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.uiState.collect { state ->
            render(state)
        }
    }
}
```

**为什么是 STARTED：** 因为很多 UI 数据只在页面可见时才需要收集。这样页面不可见时会自动停止，重新可见时再恢复。

## 5. 在 Compose 中的常见用法

### 5.1 Compose 里为什么也要关心 lifecycle

很多人以为 Compose 只是“声明式 UI”，就不用关心生命周期了。其实不是。
只要你涉及下面这些事情，lifecycle 依然重要：

- 页面进入前台/离开前台时做事
- 收集 Flow
- 控制播放器、相机、定位、蓝牙等资源
- 在 Composable 里做副作用

### 5.2 获取当前 LifecycleOwner

```kotlin
@Composable
fun MyScreen() {
    val lifecycleOwner = LocalLifecycleOwner.current
}
```

这就是 Compose 世界里拿 lifecycle 的最常见入口。

### 5.3 在 Compose 中监听生命周期事件

```kotlin
@Composable
fun PlayerScreen() {
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> startPlayer()
                Lifecycle.Event.ON_PAUSE -> pausePlayer()
                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}
```

这是 Compose 里很常见的写法：`DisposableEffect + lifecycle observer`。

**核心思路：** Composable 自己没有传统 Activity 那些回调函数，所以要通过 `LocalLifecycleOwner` 拿到宿主生命周期，再在副作用里注册和解绑 observer。

### 5.4 Compose 中更常见的是收集状态，而不是手动盯事件

大多数页面状态更新场景里，你不会手动看 `ON_RESUME`，而是直接收集 ViewModel 的 `StateFlow`。

```kotlin
@Composable
fun UserScreen(viewModel: UserViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    UserContent(state)
}
```

这里的 `collectAsStateWithLifecycle()` 非常重要，
因为它比普通 `collectAsState()` 更适合 Android 生命周期场景。

### 5.5 什么时候用 LaunchedEffect，什么时候用 lifecycle observer

| 场景 | 更适合什么 | 原因 |
| --- | --- | --- |
| Composable 进入组合后执行一次逻辑 | `LaunchedEffect` | 它关注的是 Compose 组合生命周期 |
| 需要知道页面 resume / pause | Lifecycle observer | 这属于宿主生命周期，不是单纯组合生命周期 |
| 收集 ViewModel 状态流 | `collectAsStateWithLifecycle()` | 更省事，也更安全 |

## 6. 和协程结合怎么用

### 6.1 lifecycleScope 是什么

`lifecycleScope` 是和某个 LifecycleOwner 绑定的协程作用域。

```kotlin
lifecycleScope.launch {
    val data = repository.loadData()
    render(data)
}
```

它的最大好处是：**生命周期结束时，这个 scope 里的协程会自动取消**。

传统问题

页面都销毁了，协程还在跑，回来还试图更新 UI。

lifecycleScope 的作用

页面结束时协程自动取消，减少泄漏和无效 UI 更新。

### 6.2 repeatOnLifecycle 是什么

`repeatOnLifecycle` 不是简单“启动一个协程”，
它的意思是：当生命周期达到某个状态时，就开始执行 block；掉到这个状态以下时，就取消 block；再次回到该状态时，再重新启动。

```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.uiState.collect { state ->
            render(state)
        }
    }
}
```

### 6.3 为什么它特别适合 Flow 收集

- 页面可见时开始 collect
- 页面不可见时停止 collect
- 重新可见时重新 collect

这对 UI 数据流来说很自然，因为 UI 本来就只在“用户看得到”时才需要更新。

**不要把长期收集 Flow 写成裸的 launch + collect。** 对 UI 层来说，很多时候应该优先想 `repeatOnLifecycle` 或 Compose 的 `collectAsStateWithLifecycle()`。

### 6.4 launchWhenStarted / launchWhenResumed 为什么现在不太推荐当主力

你可能见过：

```kotlin
lifecycleScope.launchWhenStarted {
    // do something
}
```

它们不是完全不能用，但现在更推荐用 `repeatOnLifecycle`，因为后者语义更清晰，尤其适合持续收集 Flow。

### 6.5 在 Compose 里和协程怎么搭

Compose 自己也有协程相关 API，比如：

- `LaunchedEffect`
- `rememberCoroutineScope()`

```kotlin
@Composable
fun ExampleScreen() {
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

这里的协程更偏“Compose 交互逻辑”。而真正跟页面可见性强相关的数据收集，仍然建议交给 lifecycle-aware 的方式。

## 7. 和 Flow / StateFlow 的配合

### 7.1 Activity / Fragment 中

```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.uiState.collect { state ->
            render(state)
        }
    }
}
```

这是现在非常标准的一种 UI 层收集写法。

### 7.2 Compose 中

```kotlin
@Composable
fun UserScreen(viewModel: UserViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    UserContent(uiState)
}
```

它相当于帮你把 lifecycle 和状态收集的细节封装掉了。

### 7.3 为什么别随便直接 collectAsState()

普通 `collectAsState()` 更偏通用 Compose 世界，
而 Android 项目里大多数界面状态流都应该考虑宿主生命周期。

**可以先记住一条经验规则：** 在 Android + Compose 里，ViewModel 的 `StateFlow` 用 `collectAsStateWithLifecycle()`，通常是更稳的默认选择。

## 8. 常见坑

### 坑 1：页面不可见了还在 collect

常见原因是直接 `launch {{ flow.collect() }}`，没和 lifecycle 绑定。

### 坑 2：Compose 里忘记 removeObserver

手动加 observer 时，一定要在 `onDispose` 里移除。

### 坑 3：把 Compose 组合生命周期和 Activity 生命周期混为一谈

`LaunchedEffect` 管的是组合进入/退出，不等于页面真的 resume / pause。

### 坑 4：在 onCreate 就开始长期 UI 数据收集

如果不结合 `repeatOnLifecycle`，页面 stop 后仍可能继续处理数据。

### 坑 5：什么都写进 Activity 回调

时间长了 Activity 会非常重，很多逻辑更适合 observer 或 ViewModel。

### 坑 6：滥用 lifecycle 处理业务状态

lifecycle 管“页面活到哪了”，业务状态还是应该交给 ViewModel + StateFlow。

## 9. 一页速查表

| 需求 | 推荐做法 | 一句理解 |
| --- | --- | --- |
| 想感知 Activity/Fragment 生命周期 | `lifecycle` / observer | 知道页面现在活到哪一步 |
| 想把逻辑从 Activity 中拆出去 | `DefaultLifecycleObserver` | 让生命周期逻辑模块化 |
| 页面销毁时自动取消协程 | `lifecycleScope` | scope 跟页面生命周期绑定 |
| 页面可见时收集 Flow，不可见时停止 | `repeatOnLifecycle(STARTED)` | UI 层收集 Flow 的常用标准写法 |
| Compose 中获取 lifecycle | `LocalLifecycleOwner.current` | 拿到宿主生命周期 |
| Compose 中监听页面 resume/pause | `DisposableEffect + LifecycleEventObserver` | 注册 observer 并在 dispose 时解绑 |
| Compose 中收集 ViewModel 状态 | `collectAsStateWithLifecycle()` | 更适合 Android 生命周期场景 |
| 按钮点击触发一个短协程 | `rememberCoroutineScope()` | 适合 Composable 内部交互逻辑 |

**最后给你的实战记忆法：**

- 页面状态：交给 `ViewModel + StateFlow`
- UI 收集：Activity 用 `repeatOnLifecycle`，Compose 用 `collectAsStateWithLifecycle()`
- 页面前后台切换：用 lifecycle observer
- 短交互协程：Compose 里用 `rememberCoroutineScope()` / `LaunchedEffect`

你只要先把这 4 组搭配用熟，Android 里 lifecycle 基本就不会乱了。

Android Lifecycle 常见用法整理 · Activity / Compose / 协程结合版
