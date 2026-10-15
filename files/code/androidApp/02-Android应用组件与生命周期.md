# 02 Android 应用组件与生命周期：从 App 代码追到 ActivityThread

> App 侧写 Activity、Service、Receiver、Provider 时，真正驱动生命周期的是 Framework。理解 `ActivityThread`、`Instrumentation`、`LoadedApk`、`ContextImpl`，很多“为什么回调顺序是这样”的问题就能解释清楚。

---

## 1. App 进程启动后发生了什么

一个 App 进程被 Zygote fork 后，入口通常是：

```text
ActivityThread.main()
  ├─ Looper.prepareMainLooper()
  ├─ ActivityThread.attach(false)
  │    └─ AMS.attachApplication(IApplicationThread)
  └─ Looper.loop()
```

Framework 通过 `IApplicationThread` 这个 Binder 接口回调 App 进程，App 再把事件投递到主线程 `ActivityThread.H`。

源码入口：

- `frameworks/base/core/java/android/app/ActivityThread.java`
- `frameworks/base/core/java/android/app/LoadedApk.java`
- `frameworks/base/core/java/android/app/ContextImpl.java`
- `frameworks/base/core/java/android/app/Instrumentation.java`

---

## 2. Application 创建

典型顺序：

```text
ActivityThread.handleBindApplication
  ├─ installContentProviders
  ├─ LoadedApk.makeApplication
  │    ├─ 创建 Application 对象
  │    └─ Instrumentation.callApplicationOnCreate
  └─ Application.onCreate
```

这解释了两个现象：

- Provider 可能早于 `Application.onCreate()`。
- App 冷启动耗时不只在 Activity，还可能在 Provider 或 Application 初始化。

App 侧建议：

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 只做必要初始化，耗时 SDK 延迟到首屏后或按需初始化。
    }
}
```

---

## 3. Activity 生命周期和源码链路

App 代码：

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MainRoute() }
    }

    override fun onStart() { super.onStart() }
    override fun onResume() { super.onResume() }
    override fun onPause() { super.onPause() }
    override fun onStop() { super.onStop() }
    override fun onDestroy() { super.onDestroy() }
}
```

Framework 链路简化：

```text
ATMS/AMS
  -> IApplicationThread.scheduleTransaction
      -> ClientTransaction
          -> ActivityThread.H
              -> TransactionExecutor
                  -> LaunchActivityItem / ResumeActivityItem
                      -> Activity.performCreate / performResume
```

源码阅读重点：

- `ActivityThread.handleLaunchActivity`
- `TransactionExecutor.execute`
- `LaunchActivityItem.execute`
- `Activity.performCreate`
- `Instrumentation.callActivityOnCreate`

---

## 4. 启动模式和任务栈

常见启动模式：

| 模式 | 行为 |
|---|---|
| standard | 每次创建新实例 |
| singleTop | 栈顶已有则复用并回调 `onNewIntent` |
| singleTask | 任务栈内复用，清理其上的 Activity |
| singleInstancePerTask | 更强隔离，适合少数特殊入口 |

App 侧处理新 Intent：

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleDeepLink(intent)
}
```

排查任务栈：

```bash
adb shell dumpsys activity activities
adb shell dumpsys activity starter
```

---

## 5. Fragment 生命周期

Fragment 是 AndroidX 组件，不是 Framework 四大组件，但 App 开发非常常见。

核心区别：

- Fragment 生命周期：`onCreate` 到 `onDestroy`。
- View 生命周期：`onCreateView` 到 `onDestroyView`。

错误示例：

```kotlin
class ListFragment : Fragment(R.layout.list) {
    private var binding: ListBinding? = null

    override fun onDestroy() {
        super.onDestroy()
        binding = null // 太晚，View 已经销毁。
    }
}
```

更合理：

```kotlin
override fun onDestroyView() {
    binding = null
    super.onDestroyView()
}
```

源码阅读点：

- `androidx.fragment.app.FragmentManager`
- `androidx.fragment.app.FragmentStateManager`
- `androidx.fragment.app.SpecialEffectsController`

---

## 6. Service 生命周期

App 代码：

```kotlin
class SyncService : Service() {
    override fun onCreate() { super.onCreate() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = null
}
```

注意：这些回调默认在主线程。不要在 `onCreate/onStartCommand/onBind` 里做重活。

Framework 链路：

```text
AMS ActiveServices
  -> IApplicationThread.scheduleCreateService
      -> ActivityThread.handleCreateService
          -> Service.onCreate
```

---

## 7. BroadcastReceiver 生命周期

```kotlin
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            WorkManager.getInstance(context).enqueue(syncWork())
        }
    }
}
```

`onReceive()` 也在主线程，且有超时约束。长任务要交给 WorkManager 或前台服务。

源码入口：

- `ActivityThread.handleReceiver`
- `LoadedApk.ReceiverDispatcher`
- `BroadcastReceiver.PendingResult.finish`

---

## 8. ContentProvider 生命周期

```kotlin
class AppProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null
}
```

Provider 的 `onCreate()` 可能在 Application 前执行，适合轻量初始化，不适合打开大库、做网络请求。

源码入口：

- `ActivityThread.installProvider`
- `ActivityThread.installContentProviders`
- `ContentProvider.attachInfo`

---

## 9. Context 到底是什么

App 经常见到的 Context：

- `Application`：进程级生命周期。
- `Activity`：带窗口和主题。
- `Service`：服务上下文。
- `ContextImpl`：真正实现大多数系统服务访问。
- `ContextWrapper`：包装转发。

源码入口：

- `Context.java`
- `ContextImpl.java`
- `ContextWrapper.java`
- `SystemServiceRegistry.java`

获取系统服务：

```kotlin
val notificationManager = context.getSystemService(NotificationManager::class.java)
```

最终会通过 `SystemServiceRegistry` 找到对应 manager，而 manager 往往再通过 Binder 调 system_server。

---

## 10. 生命周期相关常见问题

| 现象 | 优先看 |
|---|---|
| Application 很慢 | Provider、App Startup、Application 初始化 |
| Activity 重建 | 配置变更、进程死亡恢复、系统回收 |
| `onNewIntent` 不走 | launchMode、flag、任务栈 |
| Fragment 泄漏 | View 生命周期、adapter、binding 清理 |
| Service ANR | 主线程重活、前台服务通知超时 |
| Receiver 收不到 | 注册方式、后台限制、权限、多用户 |

---

## 11. 建议源码阅读顺序

1. `ActivityThread.main`
2. `ActivityThread.ApplicationThread`
3. `ActivityThread.H`
4. `LoadedApk.makeApplication`
5. `Instrumentation.callActivityOnCreate`
6. `TransactionExecutor`
7. `Activity.performCreate/performResume`
8. `ContextImpl.getSystemService`

读这些源码时，你会发现 App 开发里的生命周期、Context、系统服务、主线程消息，本质都集中在 `ActivityThread` 这条主线上。
