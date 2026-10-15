# 10 性能优化与 ANR：启动、卡顿、内存、耗电和源码排查

> App 性能优化不是玄学。大多数问题能归到几条链路：启动主线程太忙、帧内工作太重、内存持有太久、后台任务太频繁、Binder/锁等待太长。

---

## 1. 性能问题分类

| 类型 | 用户现象 | 常用工具 |
|---|---|---|
| 启动慢 | 白屏久、首屏慢 | Perfetto、Macrobenchmark、logcat |
| 卡顿掉帧 | 滑动不顺、动画顿 | JankStats、gfxinfo、Perfetto |
| ANR | 应用无响应 | traces、logcat、bugreport |
| 内存泄漏 | 越用越卡、OOM | LeakCanary、Profiler |
| 耗电 | 后台耗电高 | Battery Historian、dumpsys batterystats |
| 包体积 | 下载大、安装慢 | APK Analyzer、R8 |

---

## 2. 启动优化

冷启动链路：

```text
Launcher click
  -> ATMS/AMS startActivity
      -> Zygote fork app process
          -> ActivityThread.main
              -> installContentProviders
              -> Application.onCreate
              -> Activity.onCreate
              -> first draw
```

App 侧应避免：

- Application 初始化所有 SDK。
- Provider 自动初始化重活。
- 首屏同步读数据库/文件。
- 主线程网络或反射扫描。

延迟初始化示例：

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppInitializer.getInstance(this).initializeComponent(CrashInitializer::class.java)
    }
}

class AnalyticsInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        ProcessLifecycleOwner.get().lifecycleScope.launchWhenStarted {
            Analytics.init(context)
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
```

注意：App Startup 本身也是 Provider 触发，重活仍然会拖慢启动。

---

## 3. Macrobenchmark 测启动

```kotlin
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup() = benchmarkRule.measureRepeated(
        packageName = "com.example.app",
        metrics = listOf(StartupTimingMetric()),
        iterations = 10,
        startupMode = StartupMode.COLD,
    ) {
        pressHome()
        startActivityAndWait()
    }
}
```

配合 Baseline Profile 可以优化热点代码解释执行成本。

---

## 4. UI 卡顿和掉帧

一帧预算通常约 16.6ms。超过就可能掉帧。

常见原因：

- 主线程同步 I/O。
- RecyclerView/Compose 列表 item 太重。
- 频繁 `requestLayout()`。
- 图片解码太大。
- Compose 重组范围过大。
- Binder 同步调用卡住主线程。

JankStats：

```kotlin
val jankStats = JankStats.createAndTrack(window) { frameData ->
    if (frameData.isJank) {
        Log.w("Jank", "Jank frame: ${frameData.frameDurationUiNanos}")
    }
}
```

---

## 5. StrictMode 抓主线程问题

```kotlin
if (BuildConfig.DEBUG) {
    StrictMode.setThreadPolicy(
        StrictMode.ThreadPolicy.Builder()
            .detectDiskReads()
            .detectDiskWrites()
            .detectNetwork()
            .penaltyLog()
            .build(),
    )
}
```

这能尽早暴露主线程读文件、写数据库、网络访问。

---

## 6. ANR 怎么看

ANR 类型：

- Input dispatch timeout。
- Broadcast timeout。
- Service timeout。
- ContentProvider timeout。

看 trace 时先找 main thread：

```text
"main" prio=5 tid=1 Native
  at android.os.BinderProxy.transactNative(Native method)
  at android.os.BinderProxy.transact(BinderProxy.java)
  at com.example.Repository.loadSync(Repository.kt:42)
```

判断方向：

- 卡在 Binder：看对端服务是否慢。
- 卡在 monitor：看锁被谁持有。
- 卡在 SQLite：看事务和慢查询。
- 卡在 MessageQueue：看是否有同步屏障或消息堆积。

---

## 7. 内存泄漏

典型泄漏：

```kotlin
object ImageLoaderHolder {
    var activity: Activity? = null
}
```

更常见的是监听未取消：

```kotlin
override fun onStart() {
    super.onStart()
    sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
}

override fun onStop() {
    sensorManager.unregisterListener(listener)
    super.onStop()
}
```

Compose 中注意 `DisposableEffect`：

```kotlin
DisposableEffect(Unit) {
    callbackRegistry.add(callback)
    onDispose { callbackRegistry.remove(callback) }
}
```

---

## 8. Bitmap 和大图

问题：图片过大导致 OOM 或 UI 卡顿。

建议：

- 使用 Coil/Glide 这类成熟库。
- 指定目标尺寸。
- 列表中避免原图加载。
- 关注硬件位图和内存缓存。

Coil 示例：

```kotlin
AsyncImage(
    model = ImageRequest.Builder(LocalContext.current)
        .data(url)
        .size(640, 360)
        .crossfade(true)
        .build(),
    contentDescription = null,
)
```

---

## 9. 耗电优化

耗电常见来源：

- 频繁定位。
- 频繁网络唤醒。
- WakeLock 未释放。
- 后台任务太密。
- 蓝牙/音视频持续运行。

WakeLock 要成对释放：

```kotlin
val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "app:sync")
try {
    wakeLock.acquire(30_000)
    syncNow()
} finally {
    if (wakeLock.isHeld) wakeLock.release()
}
```

---

## 10. 包体积优化

```gradle
android {
    buildTypes {
        release {
            minifyEnabled true
            shrinkResources true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }
}
```

重点：

- 检查大资源和重复资源。
- 只保留必要 ABI。
- R8 keep 规则不要过宽。
- 动态特性模块适合低频能力。

---

## 11. 常用命令

```bash
adb shell dumpsys gfxinfo package.name framestats
adb shell am dumpheap package.name /sdcard/app.hprof
adb shell dumpsys meminfo package.name
adb shell dumpsys batterystats package.name
adb shell am trace-ipc start
adb bugreport bugreport.zip
```

---

## 12. 源码查看建议

| 问题 | 源码入口 |
|---|---|
| 启动慢 | `ActivityThread`、`LoadedApk`、`AppInitializer` |
| 掉帧 | `ViewRootImpl`、`Choreographer`、Compose `Recomposer` |
| ANR | `ActivityManagerService`、`AnrHelper`、`InputDispatcher` |
| Work 延迟 | `WorkManagerImpl`、`JobSchedulerService` |
| 内存 | ART GC、LeakCanary Shark |

性能排查建议从现象出发，不要一上来优化代码。先定位瓶颈在启动、主线程、渲染、内存、后台还是系统限制。
