# AMS 机制详解：从应用交互到 Framework 工作流程

> 以 Android Framework 的核心服务 AMS 为主线，讲清楚应用进程、system_server、Zygote、WMS/ATMS/PMS 等模块之间是如何协作的。
> 目标读者：写过 Android 应用或系统应用，了解 Binder 基础，希望进一步读懂 Activity 启动、进程管理、Service、Broadcast、ANR、三方系统定制的开发者。

---

## 目录

1. [先建立直觉：AMS 是什么](#1先建立直觉ams-是什么)
2. [AMS 在系统中的位置](#2ams-在系统中的位置)
3. [AMS 涉及到的核心类](#3ams-涉及到的核心类)
4. [AMS 与应用进程如何交互](#4ams-与应用进程如何交互)
5. [Activity 启动流程](#5activity-启动流程)
6. [应用进程启动流程](#6应用进程启动流程)
7. [Service 工作流程](#7service-工作流程)
8. [Broadcast 工作流程](#8broadcast-工作流程)
9. [进程与 OOM 调整流程](#9进程与-oom-调整流程)
10. [ANR 监控与处理](#10anr-监控与处理)
11. [权限、身份与多用户](#11权限身份与多用户)
12. [常见问题与排查方法](#12常见问题与排查方法)
13. [第三方系统常见修改点](#13第三方系统常见修改点)
14. [读源码时的建议路线](#14读源码时的建议路线)
15. [关键源码路径速查](#15关键源码路径速查)
16. [一图总结](#16一图总结)

---

## 1. 先建立直觉：AMS 是什么

**一句话：AMS 是 Android 系统里负责“管理应用生命周期和进程状态”的总调度中心。**

应用开发时你经常写：

```java
startActivity(intent);
startService(intent);
sendBroadcast(intent);
bindService(intent, conn, flags);
```

这些 API 看起来是本地方法调用，但背后通常都会跨进程进入 `system_server`，最终由 AMS 或它拆分出去的相关服务来调度。

AMS 主要负责这些事情：

- **进程管理**：什么时候启动应用进程，什么时候杀进程，进程优先级怎么计算。
- **Activity/Service/Broadcast 调度**：接收应用请求，解析目标组件，安排生命周期回调。
- **应用状态管理**：前台、后台、cached、empty、正在启动、正在停止等状态。
- **ANR 管理**：输入、广播、Service、ContentProvider 超时后的 ANR 归因和 dump。
- **权限与身份校验**：检查调用方 uid/pid、permission、userId、appId。
- **和其他系统服务协作**：PMS 负责包信息，WMS/ATMS 负责窗口和任务栈，Zygote 负责 fork，LMKD 负责低内存回收。

需要先说明一个版本差异：早期 Android 里很多 Activity 栈、任务、窗口相关逻辑都在 AMS 中；Android 10 之后，Activity 和 task 管理大量迁移到 `ActivityTaskManagerService`（ATMS）。现在更准确的理解是：

> AMS 仍然是进程、Service、Broadcast、Provider、OOM、ANR 的核心；Activity 启动这条链路由 AMS 和 ATMS 共同完成，ATMS 更靠近 Activity/Task/Window 调度，AMS 更靠近进程和应用状态调度。

---

## 2. AMS 在系统中的位置

AMS 运行在 `system_server` 进程中。应用进程不能直接访问 AMS 对象本身，只能通过 Binder 拿到它的代理。

典型交互图如下：

```text
应用进程
  Activity / ContextImpl / ActivityThread
        │ Binder 调用
        ▼
system_server
  ActivityManagerService / ActivityTaskManagerService
        │
        ├─ PackageManagerService：查询组件、权限、包信息
        ├─ WindowManagerService：窗口、显示、焦点
        ├─ ProcessList：进程启动、进程记录、OOM adj
        ├─ ActiveServices：Service 启停与绑定
        ├─ BroadcastQueue / BroadcastProcessQueue：广播分发
        ├─ ContentProviderHelper：Provider 发布和查询
        └─ ZygoteProcess：请求 Zygote fork 应用进程

zygote / zygote64
  fork 出 app_process
        │
        ▼
新应用进程
  ActivityThread.main()
```

AMS 在 `SystemServer` 中启动，并注册到 ServiceManager：

```java
ActivityManagerService am = ActivityManagerService.Lifecycle.startService(...);
ServiceManager.addService(Context.ACTIVITY_SERVICE, am);
```

应用侧通过类似下面的方式拿到代理：

```java
IActivityManager am = ActivityManager.getService();
```

这背后就是：

```java
IBinder b = ServiceManager.getService("activity");
IActivityManager am = IActivityManager.Stub.asInterface(b);
```

所以 AMS 的调用模型和 AIDL/Binder 完全一致：应用侧拿到的是 `IActivityManager.Proxy`，服务端真正执行的是 `ActivityManagerService`。

---

## 3. AMS 涉及到的核心类

AMS 相关类非常多，先按职责分组看，比按源码文件逐个背更容易建立结构。

### 3.1 对外 Binder 接口

| 类 / 接口 | 位置 | 作用 |
|---|---|---|
| `IActivityManager.aidl` | `frameworks/base/core/java/android/app/` | AMS 对外 Binder 接口定义 |
| `ActivityManagerService` | `frameworks/base/services/core/java/com/android/server/am/` | AMS 服务端实现，继承 `IActivityManager.Stub` |
| `ActivityManager` | `frameworks/base/core/java/android/app/` | 应用侧公开 API 包装类 |
| `ActivityManagerNative` | 旧版本 | 旧版 AMS Binder 辅助类，新版本已弱化或移除 |
| `ServiceManager` | `frameworks/base/core/java/android/os/` | 查询 AMS Binder 服务 |

重点记住：`IActivityManager` 是契约，`ActivityManagerService` 是服务端，`ActivityManager.getService()` 拿到客户端代理。

### 3.2 应用进程侧核心类

| 类 | 作用 |
|---|---|
| `ActivityThread` | 应用进程主线程入口，负责接收 AMS/ATMS 调度并执行组件生命周期 |
| `ApplicationThread` | `ActivityThread` 内部 Binder Stub，AMS 通过它反向调用应用进程 |
| `IApplicationThread.aidl` | system_server 调应用进程的 Binder 接口 |
| `Instrumentation` | 创建 Activity、调用生命周期、启动 Activity 的应用侧入口之一 |
| `ContextImpl` | `startActivity`、`startService`、`sendBroadcast` 等 Context API 的具体实现 |
| `LoadedApk` | 应用 apk 在进程内的加载信息，负责创建 `Application`、ClassLoader 等 |
| `ActivityClientRecord` | 应用进程内保存 Activity 实例、token、intent、状态等信息 |

容易混淆的一点：应用调用 AMS 是一条 Binder；AMS 回调应用进程也是另一条 Binder。AMS 不是直接“调用你的 Activity 对象”，它只能调用应用进程暴露给 system_server 的 `ApplicationThread`。

### 3.3 system_server 内部记录类

| 类 | 作用 |
|---|---|
| `ProcessRecord` | AMS 眼中的一个应用进程，保存 pid、uid、包名、进程状态、adj、线程代理等 |
| `ProcessList` | 维护进程列表、启动进程、更新 OOM adj，和 Zygote/LMKD 交互 |
| `ProcessStateRecord` | 保存进程状态、调度组、adj、前后台等动态信息 |
| `ProcessServiceRecord` | 保存进程内 Service 相关状态 |
| `ProcessProviderRecord` | 保存进程内 Provider 相关状态 |
| `ProcessReceiverRecord` | 保存进程内 BroadcastReceiver 相关状态 |
| `ProcessErrorStateRecord` | 保存 crash、ANR、not responding 等异常状态 |
| `AppBindRecord` | 一个应用和一个 Service 之间的绑定关系 |
| `ConnectionRecord` | 一次具体的 ServiceConnection 绑定记录 |
| `ContentProviderRecord` | 一个 ContentProvider 的系统侧记录 |

这些 `Record` 类可以理解成 AMS 的“账本”。应用进程里真实对象可能已经创建、销毁或崩溃，但 AMS 必须用这些记录维护全局状态。

### 3.4 Activity/Task 相关类

新版本 Android 中，这部分主要在 `com.android.server.wm` 包，由 ATMS/WindowManager 侧管理。

| 类 | 作用 |
|---|---|
| `ActivityTaskManagerService` | Activity、Task、Stack、启动模式的核心调度服务 |
| `ActivityTaskSupervisor` | Activity 启动、resume、pause、进程准备等流程协调 |
| `ActivityRecord` | system_server 眼中的一个 Activity 实例记录 |
| `Task` | 一组 Activity 的任务栈记录 |
| `RootWindowContainer` | 所有 display、task、activity 的根容器 |
| `ActivityStarter` | 解析启动参数、启动模式、flag、task 复用等 |
| `ClientLifecycleManager` | 向应用进程发送生命周期事务 |
| `ClientTransaction` | 描述一次发给应用进程的生命周期调度 |
| `LaunchActivityItem` / `ResumeActivityItem` | 具体生命周期事务项 |

读 Activity 启动流程时，不要只盯着 AMS。现代版本里，大量关键逻辑在 ATMS、`ActivityStarter`、`ActivityTaskSupervisor`、`ClientLifecycleManager`。

### 3.5 Service、Broadcast、Provider 相关类

| 类 | 作用 |
|---|---|
| `ActiveServices` | Service 启动、绑定、重启、超时、前台服务管理 |
| `ServiceRecord` | 一个 Service 的系统侧记录 |
| `BroadcastQueue` | 旧版广播队列，管理 ordered/parallel broadcast 分发 |
| `BroadcastProcessQueue` | 新版广播分发中的进程维度队列 |
| `BroadcastRecord` | 一次广播的系统侧记录 |
| `BroadcastFilter` | 动态注册 Receiver 的过滤条件 |
| `ReceiverList` | 一个应用进程注册的 Receiver 列表 |
| `ContentProviderHelper` | Provider 查询、启动、发布、引用计数管理 |
| `ContentProviderConnection` | Provider 使用方与提供方之间的连接记录 |

这三类组件都和 AMS 强相关：Service 和 Broadcast 基本由 AMS 直接管理；Provider 也由 AMS 管理进程和发布过程，但查询组件信息需要 PMS 配合。

### 3.6 低内存与进程回收相关类

| 类 / 模块 | 作用 |
|---|---|
| `OomAdjuster` | 计算进程 OOM adj、进程状态、调度组 |
| `CachedAppOptimizer` | cached 进程冻结、压缩等优化 |
| `AppProfiler` | 统计 CPU、内存、PSS，配合性能和低内存策略 |
| `ProcessList` | 将 adj 等信息同步给内核、LMKD 或进程管理模块 |
| `lmkd` | native 守护进程，根据内存压力杀低优先级进程 |

AMS 不只是“启动组件”，还持续维护每个进程的重要性。用户看到哪个界面、前台 Service 是否存在、Provider 是否被使用，都会影响进程优先级。

---

## 4. AMS 与应用进程如何交互

AMS 和应用进程之间不是单向调用，而是双向 Binder 协作。

### 4.1 应用调用 AMS

应用发起请求时，通常从 `ContextImpl` 或 `Instrumentation` 进入：

```text
Activity.startActivity()
  └─ Instrumentation.execStartActivity()
       └─ ActivityTaskManager.getService().startActivity(...)
            └─ Binder 到 system_server
```

```text
ContextImpl.startService()
  └─ ActivityManager.getService().startService(...)
       └─ Binder 到 system_server
```

```text
ContextImpl.sendBroadcast()
  └─ ActivityManager.getService().broadcastIntent(...)
       └─ Binder 到 system_server
```

应用请求进入 system_server 后，AMS/ATMS 会做这些通用动作：

1. 获取调用方 uid/pid。
2. 检查调用权限、userId、包可见性、后台限制。
3. 向 PMS 查询目标组件信息。
4. 结合当前系统状态判断是否允许启动。
5. 如果目标进程不存在，请求 Zygote fork。
6. 如果目标进程已存在，通过 `IApplicationThread` 回调应用进程。

### 4.2 AMS 回调应用进程

每个应用进程启动后，会在 `ActivityThread.attach()` 中把自己的 `ApplicationThread` 传给 AMS：

```text
ActivityThread.main()
  └─ ActivityThread.attach(false)
       └─ ActivityManager.getService().attachApplication(mAppThread, ...)
            └─ AMS 保存 IApplicationThread 到 ProcessRecord.thread
```

之后 AMS 想让应用执行生命周期，就通过 `IApplicationThread` 回调：

```text
system_server
  ProcessRecord.thread = IApplicationThread.Proxy
        │ Binder
        ▼
应用进程
  ApplicationThread.scheduleTransaction(...)
        │
        ▼
  ActivityThread.H 处理消息
        │
        ▼
  Instrumentation.callActivityOnCreate/onResume/onPause...
```

所以生命周期真正执行的位置是应用主线程，不是 system_server。AMS 负责“下命令”，`ActivityThread` 负责“在应用进程内执行”。

### 4.3 token 是 AMS 识别组件的钥匙

Activity、Service、Provider 等组件在跨进程协作时会使用 token 或 binder 标识。

以 Activity 为例，system_server 中有 `ActivityRecord`，应用进程中有 `ActivityClientRecord`，两边靠同一个 `IBinder token` 对应起来：

```text
system_server: ActivityRecord.token
应用进程:     ActivityClientRecord.token
```

应用后续调用 `finish()`、`onPause` 完成回报、配置变化回报等，都要带着 token，AMS/ATMS 才知道你说的是哪个 Activity。

---

## 5. Activity 启动流程

Activity 启动是 AMS/ATMS 最经典的一条链路。为了避免陷入版本细节，先看核心阶段。

### 5.1 总体流程

```text
应用进程 A
  startActivity(intent)
        │
        ▼ Binder
system_server
  ATMS/AMS 校验请求、解析目标 Activity
        │
        ├─ 目标进程已存在：直接 scheduleLaunchActivity
        │
        └─ 目标进程不存在：请求 Zygote fork
                         │
                         ▼
                    新应用进程 B
                      ActivityThread.main()
                         │ attachApplication
                         ▼ Binder
system_server
  realStartActivityLocked
        │ Binder
        ▼
应用进程 B
  ActivityThread.handleLaunchActivity
        │
        ├─ 创建 LoadedApk / ClassLoader
        ├─ 创建 Application
        ├─ 反射创建 Activity
        ├─ attach Context / Window
        └─ 调用 onCreate / onStart / onResume
```

### 5.2 应用侧发起

常见调用链大致是：

```text
Activity.startActivity()
  └─ Activity.startActivityForResult()
       └─ Instrumentation.execStartActivity()
            └─ ActivityTaskManager.getService().startActivity(...)
```

这里会把这些信息传给 system_server：

- 调用方的 `IApplicationThread`。
- 调用方包名、featureId、userId。
- `Intent`、resolvedType。
- resultTo、requestCode、options。
- 调用方 Activity token。

### 5.3 system_server 解析和校验

ATMS/AMS 收到请求后，会做一系列判断：

- Intent 是否能解析到 Activity。
- 目标 Activity 是否 exported。
- 调用方是否有权限启动。
- 是否跨用户启动，是否允许访问目标 user。
- 后台启动 Activity 是否受限。
- 启动模式和 flag 如何影响 task 复用。
- 当前 top Activity 是否需要 pause。

涉及的关键类通常包括：

- `ActivityTaskManagerService`
- `ActivityStarter`
- `ActivityTaskSupervisor`
- `RootWindowContainer`
- `Task`
- `ActivityRecord`

### 5.4 pause 当前 Activity

如果当前前台已有 Activity，需要先 pause：

```text
system_server
  startPausingLocked
        │ Binder
        ▼
应用进程 A
  ActivityThread 执行 onPause
        │ Binder 回报
        ▼
system_server
  activityPaused
```

这是一个异步过程。system_server 下发 pause 后，会等待应用回报；如果应用主线程卡住，pause 超时可能引发 ANR 或启动延迟。

### 5.5 启动目标进程

如果目标 Activity 所在进程不存在，AMS 会进入进程启动流程：

```text
AMS.startProcessLocked
  └─ ProcessList.startProcessLocked
       └─ ZygoteProcess.start
            └─ socket 请求 zygote fork
```

进程 fork 出来后执行：

```text
ActivityThread.main()
  └─ Looper.prepareMainLooper()
  └─ ActivityThread.attach(false)
  └─ Looper.loop()
```

`attachApplication` 回到 AMS 后，AMS 才能拿到这个进程的 `IApplicationThread`，后续才能调度 Activity 生命周期。

### 5.6 应用进程创建 Activity

system_server 通过 `ClientTransaction` 下发生命周期事务：

```text
ClientTransaction
  ├─ LaunchActivityItem
  └─ ResumeActivityItem
```

应用侧处理流程大致是：

```text
ApplicationThread.scheduleTransaction()
  └─ ClientTransactionHandler.scheduleTransaction()
       └─ ActivityThread.H 发送消息到主线程
            └─ TransactionExecutor.execute()
                 ├─ handleLaunchActivity()
                 └─ handleResumeActivity()
```

`handleLaunchActivity()` 内部会：

1. 通过 `LoadedApk` 获取 ClassLoader。
2. 创建或复用 `Application`。
3. 通过 `Instrumentation.newActivity()` 反射创建 Activity。
4. 调用 `Activity.attach()` 绑定 Context、Window、token。
5. 调用 `Instrumentation.callActivityOnCreate()`。
6. 继续执行 `onStart()`、`onResume()`。

### 5.7 常见观察点

排查 Activity 启动问题时，优先看这些信息：

- `am start -W` 输出的 `ThisTime`、`TotalTime`、`WaitTime`。
- logcat 中 `ActivityTaskManager`、`ActivityManager`、`WindowManager` 相关日志。
- 是否卡在 pause 前一个 Activity。
- 是否卡在进程启动、bindApplication、ContentProvider install。
- 是否被后台启动限制拦截。
- 是否因为权限、exported、userId、Intent 解析失败而拒绝。

---

## 6. 应用进程启动流程

AMS 启动应用进程不是直接 `fork()`，而是通过 Zygote。

### 6.1 为什么要通过 Zygote

Zygote 进程在系统启动早期就已经加载了 Android Runtime、核心 Java 类、常用资源。新应用从 Zygote fork 出来，可以复用这部分预加载成果，减少启动开销。

```text
system_server
  AMS / ProcessList
        │ socket 命令
        ▼
zygote
  fork()
        │
        ▼
app_process
  ActivityThread.main()
```

### 6.2 AMS 侧记录进程

AMS 在启动进程前会创建或更新 `ProcessRecord`，记录：

- `processName`
- `uid`
- `pid`
- `ApplicationInfo`
- `hostingType`，例如 `activity`、`service`、`broadcast`、`content provider`
- `hostingName`
- 启动时间和超时消息
- 当前进程状态和 OOM adj

如果进程迟迟没有 attach 回来，AMS 会认为启动失败，清理对应记录。

### 6.3 bindApplication

应用进程 attach 到 AMS 后，AMS 会回调：

```text
IApplicationThread.bindApplication(...)
```

应用进程收到后会执行：

- 创建 `LoadedApk`。
- 设置进程名、兼容配置、字体、资源、Instrumentation。
- 安装 ContentProvider。
- 创建 `Application`。
- 调用 `Application.onCreate()`。

很多“冷启动慢”并不慢在 Activity 的 `onCreate()`，而是慢在 `bindApplication` 阶段：类加载、Application 初始化、Provider 自动安装、三方 SDK 初始化，都可能发生在这里。

---

## 7. Service 工作流程

Service 主要由 AMS 内部的 `ActiveServices` 管理。

### 7.1 startService

典型链路：

```text
ContextImpl.startService()
  └─ ActivityManager.getService().startService(...)
       └─ AMS
            └─ ActiveServices.startServiceLocked()
                 ├─ 查询 ServiceInfo
                 ├─ 检查权限和后台启动限制
                 ├─ 创建 ServiceRecord
                 ├─ 必要时启动目标进程
                 └─ realStartServiceLocked()
                      └─ IApplicationThread.scheduleCreateService()
```

应用进程收到后：

```text
ActivityThread.handleCreateService()
  ├─ 反射创建 Service
  ├─ attach Context
  ├─ 调用 onCreate()
  └─ 回到 AMS 继续 onStartCommand()
```

之后 AMS 会下发 `scheduleServiceArgs()`，应用侧执行 `onStartCommand()`。

### 7.2 bindService

典型链路：

```text
ContextImpl.bindService()
  └─ ActivityManager.getService().bindIsolatedService(...)
       └─ AMS
            └─ ActiveServices.bindServiceLocked()
                 ├─ 创建 AppBindRecord / ConnectionRecord
                 ├─ 必要时启动目标进程和 Service
                 ├─ 调用 Service.onBind()
                 └─ publishService 回传 IBinder
```

`onBind()` 返回的 `IBinder` 会通过 AMS 再传回客户端，客户端的 `ServiceConnection.onServiceConnected()` 才会被调用。

### 7.3 前台服务

前台服务比普通后台 Service 有更高优先级，但也有更严格限制：

- 启动后必须在规定时间内调用 `startForeground()`。
- Android 8.0 之后后台启动 Service 有限制，需要使用 `startForegroundService()`。
- Android 12 之后前台服务启动限制更严格，某些后台场景会抛 `ForegroundServiceStartNotAllowedException`。
- Android 14 之后前台服务类型和权限检查更细。

AMS 会跟踪前台服务状态，并影响进程 OOM adj。一个有合法前台服务的进程通常不会被当作普通 cached 进程处理。

---

## 8. Broadcast 工作流程

Broadcast 也由 AMS 管理，但不同 Android 版本实现变化比较大。核心思想不变：AMS 接收广播请求，找到匹配的接收者，按规则分发。

### 8.1 广播类型

| 类型 | 特点 |
|---|---|
| 普通广播 | 可以并行分发，接收者之间没有顺序关系 |
| 有序广播 | 按优先级和队列顺序逐个分发，前一个处理完才到下一个 |
| sticky 广播 | 历史上用于保存最后一次广播，新版本中大量限制或废弃 |
| 显式广播 | 指定 package 或 component，匹配范围小 |
| 隐式广播 | 只靠 action/category/data 匹配，Android 8.0 后静态注册限制很多 |

### 8.2 发送广播

典型链路：

```text
ContextImpl.sendBroadcast()
  └─ ActivityManager.getService().broadcastIntentWithFeature(...)
       └─ AMS.broadcastIntentLocked()
            ├─ 校验权限、用户、后台限制
            ├─ 查询静态 Receiver：PMS
            ├─ 查询动态 Receiver：AMS 内部 ReceiverList
            ├─ 生成 BroadcastRecord
            └─ 入队分发
```

### 8.3 接收广播

如果目标进程已存在：

```text
AMS
  └─ IApplicationThread.scheduleReceiver()
       └─ ActivityThread.handleReceiver()
            ├─ 反射创建 BroadcastReceiver
            ├─ 调用 onReceive()
            └─ 回报 finishReceiver
```

如果目标进程不存在，AMS 会先启动进程，再调度 Receiver。

### 8.4 广播 ANR

广播分发有超时限制，尤其是有序广播。常见原因：

- `onReceive()` 中做耗时 I/O。
- 主线程等待锁。
- 冷启动进程太慢。
- 前一个有序广播接收者迟迟不 finish。

原则是：`onReceive()` 只做轻量工作，耗时任务交给 `JobScheduler`、`WorkManager`、前台服务或自己的后台线程。

---

## 9. 进程与 OOM 调整流程

AMS 会根据应用当前重要性不断更新 OOM adj，决定低内存时谁更容易被杀。

### 9.1 进程重要性直觉

大致从高到低可以这样理解：

| 类型 | 例子 | 被杀概率 |
|---|---|---|
| persistent/system | system_server、电话等核心进程 | 极低 |
| top | 当前前台可见且可交互 Activity | 极低 |
| foreground service | 正在运行合法前台服务 | 低 |
| visible | 可见但不在前台的 Activity | 较低 |
| perceptible | 用户能感知的音乐、定位等 | 较低 |
| service | 后台 Service | 中 |
| cached | 缓存 Activity 或空进程 | 高 |
| empty | 没有活动组件的空进程 | 很高 |

真实系统里分类更细，adj 数值也更复杂，但这个表足够建立直觉。

### 9.2 OOM adj 如何受组件影响

这些因素都会影响进程优先级：

- 是否有 top resumed Activity。
- 是否有可见 Activity。
- 是否正在执行 Service lifecycle。
- 是否有前台服务。
- 是否被其他重要进程绑定。
- 是否持有正在使用的 ContentProvider。
- 是否正在接收广播。
- 是否是 persistent app。

AMS/`OomAdjuster` 会沿着组件关系和绑定关系传播重要性。例如 A 进程是前台，A bind 了 B 的 Service，那么 B 可能因为“被前台进程绑定”而提高优先级。

### 9.3 和 LMKD 的关系

AMS 计算 adj，LMKD 负责在内存压力下杀进程。

```text
AMS / OomAdjuster
  计算进程 adj、procState
        │
        ▼
ProcessList / native 接口
  同步给内核或 lmkd
        │
        ▼
lmkd
  根据内存压力和 adj 选择进程 kill
```

所以看到应用被杀时，不要只看 Java crash。很多情况下是 LMKD 根据内存压力杀掉了 cached 或低优先级进程，logcat 里会有 `lowmemorykiller`、`lmkd`、`ActivityManager: Killing` 等日志。

---

## 10. ANR 监控与处理

ANR 的本质是：系统要求应用在规定时间内完成某件事，但应用没有及时响应。

### 10.1 常见 ANR 类型

| 类型 | 常见超时场景 |
|---|---|
| Input ANR | 主线程没有及时处理输入事件，常见 5s 左右 |
| Broadcast ANR | `BroadcastReceiver.onReceive()` 超时 |
| Service ANR | `Service` 启动、绑定、执行生命周期超时 |
| ContentProvider ANR | Provider 发布或访问超时 |

不同 Android 版本、前后台状态、组件类型会影响具体超时时间，不能只背一个固定数字。

### 10.2 AMS 如何处理 ANR

AMS 发现超时后，大致会做：

1. 标记进程进入 not responding 状态。
2. 收集 CPU、内存、进程状态。
3. dump 关键进程 Java stack。
4. 写入 `/data/anr/anr_*` 或 traces 文件。
5. 通知 `AppErrors` 弹出 ANR 对话框或后台静默处理。
6. 根据用户选择或系统策略杀进程。

关键类包括：

- `AppErrors`
- `AnrHelper`
- `ProcessErrorStateRecord`
- `ActivityManagerService.appNotResponding`

### 10.3 排查 ANR 的基本方法

先看 ANR 文件里的这几类信息：

- 发生 ANR 的进程、uid、reason。
- 主线程 stack。
- Binder 线程 stack。
- 是否在等锁。
- 是否有 ContentProvider、Service、Broadcast 调用链。
- CPU 使用率是否异常。
- system_server 是否也卡住。

如果主线程在等 Binder 返回，要继续查对端是谁；如果 Binder 线程池都在等待同一把锁，要查锁持有者；如果 system_server 卡住，应用侧 ANR 可能只是结果，不是根因。

---

## 11. 权限、身份与多用户

AMS 是系统安全边界的一部分。任何来自应用的请求，都不能只相信参数本身。

### 11.1 调用方身份

AMS 可以通过 Binder 拿到调用方：

```java
int callingUid = Binder.getCallingUid();
int callingPid = Binder.getCallingPid();
```

然后结合 PMS 查询：

- uid 属于哪个 package。
- 是否有目标 permission。
- 是否是 system/root/shell。
- 是否允许跨用户操作。
- 目标组件是否 exported。

### 11.2 clearCallingIdentity

Framework 代码里经常出现：

```java
final long ident = Binder.clearCallingIdentity();
try {
    // 以 system_server 自己的身份访问其他服务
} finally {
    Binder.restoreCallingIdentity(ident);
}
```

这不是可有可无的模板代码。system_server 处理 Binder 请求时，当前线程携带远端调用方身份；如果继续调用别的系统服务，可能会被当成“远端应用”而不是“系统服务”处理。需要切身份时必须清理，用完必须恢复。

### 11.3 多用户

Android 的 uid 里包含 userId 和 appId。AMS 处理组件启动时必须明确当前操作属于哪个用户：

- `UserHandle.getUserId(uid)` 取调用方用户。
- 启动组件时要检查目标 user 是否存在、是否 running、是否 unlocked。
- 跨用户操作通常需要 `INTERACT_ACROSS_USERS` 或 `INTERACT_ACROSS_USERS_FULL`。
- 车机、平板、多用户设备上，多用户逻辑非常容易影响启动和广播行为。

---

## 12. 常见问题与排查方法

这一节按实际开发和系统定制中最常遇到的问题来整理。

### 12.1 Activity 启动失败

常见原因：

- `ActivityNotFoundException`：Intent 解析不到目标 Activity。
- `SecurityException`：目标 Activity 未 exported，或缺少权限。
- 后台启动限制：后台进程直接拉起界面被系统拦截。
- userId 不对：目标应用装在另一个用户下。
- 启动模式和 flag 导致复用已有 task，看起来像“没启动”。
- 目标进程启动失败：native crash、Application 初始化崩溃、Provider 安装失败。

排查建议：

```bash
adb shell am start -W -n package/.Activity
adb logcat -b all | grep -i "ActivityTaskManager\|ActivityManager\|START"
adb shell dumpsys activity activities
adb shell dumpsys activity starter
```

### 12.2 冷启动慢

常见卡点：

- Zygote fork 排队或进程启动慢。
- `bindApplication` 中 Application 初始化过重。
- ContentProvider 自动初始化过多。
- 主线程类加载、反射、I/O 太多。
- 前一个 Activity pause 慢。
- system_server 繁忙或锁竞争。

排查建议：

- 用 `am start -W` 先看整体耗时。
- 用 Perfetto / systrace 看 `bindApplication`、`activityStart`、`launching` 相关切片。
- 临时关闭三方 SDK 初始化，验证是否在 Application 阶段。
- 查 logcat 中 `Displayed`、`Fully drawn`、`ActivityTaskManager` 日志。

### 12.3 Service 启动失败或被杀

常见原因：

- Android 8.0 后后台启动 Service 受限。
- `startForegroundService()` 后未及时 `startForeground()`。
- 前台服务类型或权限不满足。
- Service 所在进程 OOM adj 低，被 LMKD 杀。
- `onCreate()` / `onStartCommand()` 主线程耗时导致 ANR。

排查建议：

```bash
adb shell dumpsys activity services packageName
adb logcat -b all | grep -i "ForegroundService\|ActiveServices\|ServiceRecord"
```

### 12.4 广播收不到

常见原因：

- Android 8.0 后大量隐式广播不能通过 manifest 静态注册接收。
- 应用处于 stopped 状态。
- userId 不匹配。
- 权限不匹配，发送方或接收方缺 permission。
- 广播被后台限制、应用待机、厂商省电策略拦截。
- 有序广播被前面的接收者中断或拖慢。

排查建议：

```bash
adb shell dumpsys activity broadcasts
adb shell cmd package query-receivers --brief -a android.intent.action.BOOT_COMPLETED packageName
adb logcat -b all | grep -i "BroadcastQueue\|BroadcastRecord"
```

### 12.5 应用莫名其妙被杀

常见原因：

- LMKD 根据内存压力杀 cached 进程。
- 用户或系统执行 force-stop。
- 进程 crash 后被 AMS 清理。
- 后台限制策略杀掉长期后台运行进程。
- 厂商定制的省电、白名单、后台管控策略。

排查建议：

```bash
adb logcat -b events | grep -i "am_kill\|am_proc_died\|am_crash"
adb logcat -b all | grep -i "Killing .* packageName\|lmkd\|lowmemorykiller"
adb shell dumpsys activity processes
adb shell dumpsys meminfo packageName
```

### 12.6 ANR 不知道卡在哪里

排查顺序：

1. 找 `/data/anr/` 下对应时间的 ANR 文件。
2. 看 reason，确认是 input、broadcast、service 还是 provider。
3. 看目标进程主线程 stack。
4. 如果主线程在 Binder 调用，看 Binder 对端。
5. 如果主线程等锁，找锁持有线程。
6. 看 system_server 是否同时卡住。
7. 结合 Perfetto 看当时 CPU、I/O、锁竞争和调度延迟。

---

## 13. 第三方系统常见修改点

车机、平板、电视、ROM、行业设备都会改 AMS 相关逻辑。下面按常见目标整理。

### 13.1 后台启动限制白名单

需求例子：某些导航、语音、倒车、电话、系统助手需要在后台拉起界面。

常见修改点：

- Activity 后台启动限制判断。
- 特定 uid/package/component 白名单。
- 特定场景信号，例如倒车、来电、语音唤醒。
- 与 `AppOps`、权限、角色管理结合。

风险：

- 白名单过宽会导致任意后台弹窗，影响安全和体验。
- 绕过 Activity 启动限制后，仍要考虑锁屏、用户、显示区域、任务栈。
- 多用户和多显示设备上，错误 user/display 会导致界面出现在错误位置。

建议：

- 白名单尽量基于签名权限、系统 uid、role 或明确 component。
- 增加日志，记录为什么允许后台启动。
- 不要只按包名放行，容易被同名包、预装差异、调试包绕晕。

### 13.2 保活和进程优先级调整

需求例子：关键应用不能被杀，导航、媒体、车控、语音助手需要长期运行。

常见修改点：

- 修改 OOM adj 计算。
- 将应用标成 persistent。
- 增加进程保活白名单。
- LMKD 侧增加保护策略。
- 监听进程死亡后自动拉起。

风险：

- 保活过多会挤压系统内存，导致前台应用卡顿或系统频繁 kill 其他进程。
- persistent 应用 crash 后反复重启，可能造成系统抖动。
- 单纯提高 adj 不能解决应用自身泄漏、死锁、ANR。

建议：

- 先确认业务是否真的需要常驻。
- 优先用前台服务、JobScheduler、系统角色、绑定关系等标准机制。
- 定制 adj 时要配合内存水线、LMKD 日志和压力测试。

### 13.3 开机自启动和广播限制调整

需求例子：设备开机后自动启动行业应用或系统应用。

常见修改点：

- `BOOT_COMPLETED` 广播分发策略。
- 静态广播限制白名单。
- package stopped 状态处理。
- 用户解锁前后广播：`LOCKED_BOOT_COMPLETED` / `BOOT_COMPLETED`。

风险：

- 开机同时拉起太多应用会拖慢开机完成时间。
- Direct Boot 阶段访问 credential encrypted 存储会失败。
- 多用户设备上只给 system user 发广播可能不符合业务预期。

建议：

- 区分开机必须启动和用户解锁后再启动。
- 加启动队列和延迟策略，避免开机风暴。
- 对失败、超时、ANR 做降级，防止拖垮系统启动。

### 13.4 前台服务策略修改

需求例子：允许特定后台应用启动前台服务，或放宽前台服务类型限制。

常见修改点：

- `startForegroundService` 限制。
- `ForegroundServiceStartNotAllowedException` 触发条件。
- 前台服务类型检查。
- 通知展示策略。

风险：

- 前台服务滥用会造成后台常驻、耗电、状态栏污染。
- 放宽限制后可能影响 CTS/GTS。
- 用户不可感知的“前台服务”会破坏系统功耗模型。

建议：

- 只对白名单系统应用或签名应用放行。
- 保留可观测日志和 dumpsys 信息。
- 不要绕过通知和类型检查，除非产品明确承担兼容风险。

### 13.5 多屏、多窗口、车载场景定制

需求例子：指定应用启动到副屏、仪表屏、后排屏，或限制某些 Activity 只能在特定 display。

常见修改点：

- ActivityOptions 中 displayId。
- ATMS 的 display/task 选择策略。
- 启动白名单和显示区域权限。
- Task 复用和 root task 选择。

风险：

- Activity token、displayId、userId 三者不一致会出现黑屏或启动失败。
- WMS、ATMS、Launcher 对 task 的理解不一致会导致返回栈异常。
- 车载场景要考虑驾驶安全限制。

建议：

- 优先使用官方多显示 API 和 ActivityOptions。
- 策略集中在 ATMS/Task 选择处，不要在很多入口散落判断。
- 对每次跨屏启动打结构化日志：caller、target、userId、displayId、reason。

### 13.6 ANR 超时阈值修改

需求例子：行业设备性能较弱，希望放宽广播或 Service ANR 时间。

常见修改点：

- Broadcast timeout。
- Service timeout。
- Provider publish timeout。
- Input timeout 通常涉及更底层输入系统，不只 AMS。

风险：

- 放宽超时只会延迟暴露问题，不会解决主线程卡顿。
- 用户体感可能更差，因为系统更晚才处理异常。
- CTS 或稳定性测试可能受影响。

建议：

- 先用 trace 找根因，确认是否确实需要调阈值。
- 只对特定系统应用或场景放宽，不要全局放大。
- 保留 watchdog 和关键服务保护，避免 system_server 被拖死。

### 13.7 权限和组件暴露策略修改

需求例子：系统应用之间需要跨包启动私有组件，或行业 app 需要特殊权限。

常见修改点：

- exported 检查。
- signature permission。
- AppOps 默认值。
- 隐式 Intent 解析范围。
- 包可见性限制。

风险：

- 过度放宽会引入提权和组件劫持风险。
- Android 12 起组件 exported 要求更严格。
- 包可见性策略影响应用能否查询到目标组件。

建议：

- 能用签名权限就不要用包名白名单。
- 能用显式 Intent 就不要扩大隐式解析。
- 安全相关修改必须记录调用方 uid、pid、包名和放行原因。

### 13.8 修改时的基本原则

改 AMS/ATMS 时最忌讳“哪里拦了就在哪里 return true”。更稳的做法是：

- 找到统一策略入口，而不是散落在多个分支。
- 保留原生逻辑，只在明确条件下插入例外。
- 每个例外都能解释：谁、在什么场景、为什么允许。
- 记录日志和 dumpsys，方便售后和稳定性排查。
- 同时验证安全、功耗、启动速度、低内存、CTS/GTS 风险。

---

## 14. 读源码时的建议路线

AMS 源码很大，建议按问题链路读，不要从第一行开始硬啃。

### 14.1 Activity 启动路线

```text
Activity.startActivity
Instrumentation.execStartActivity
ActivityTaskManagerService.startActivity
ActivityStarter.execute
ActivityTaskSupervisor.realStartActivityLocked
ClientLifecycleManager.scheduleTransaction
ActivityThread.handleLaunchActivity
```

### 14.2 进程启动路线

```text
ActivityManagerService.startProcessLocked
ProcessList.startProcessLocked
ZygoteProcess.start
ActivityThread.main
ActivityThread.attach
ActivityManagerService.attachApplication
IApplicationThread.bindApplication
```

### 14.3 Service 路线

```text
ContextImpl.startService / bindService
ActivityManagerService.startService / bindIsolatedService
ActiveServices.startServiceLocked / bindServiceLocked
realStartServiceLocked
ActivityThread.handleCreateService
Service.onCreate / onStartCommand / onBind
```

### 14.4 Broadcast 路线

```text
ContextImpl.sendBroadcast
ActivityManagerService.broadcastIntentWithFeature
ActivityManagerService.broadcastIntentLocked
BroadcastQueue / BroadcastProcessQueue
IApplicationThread.scheduleReceiver
ActivityThread.handleReceiver
BroadcastReceiver.onReceive
```

### 14.5 ANR 路线

```text
超时消息触发
ActiveServices / BroadcastQueue / InputDispatcher / Provider 相关入口
ActivityManagerService.appNotResponding
AnrHelper
AppErrors
/data/anr traces
```

---

## 15. 关键源码路径速查

| 内容 | 路径 |
|---|---|
| AMS 实现 | `frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java` |
| AMS AIDL | `frameworks/base/core/java/android/app/IActivityManager.aidl` |
| 应用侧 ActivityManager | `frameworks/base/core/java/android/app/ActivityManager.java` |
| 应用主线程 | `frameworks/base/core/java/android/app/ActivityThread.java` |
| 应用回调接口 | `frameworks/base/core/java/android/app/IApplicationThread.aidl` |
| Context 实现 | `frameworks/base/core/java/android/app/ContextImpl.java` |
| Instrumentation | `frameworks/base/core/java/android/app/Instrumentation.java` |
| 进程管理 | `frameworks/base/services/core/java/com/android/server/am/ProcessList.java` |
| OOM 调整 | `frameworks/base/services/core/java/com/android/server/am/OomAdjuster.java` |
| Service 管理 | `frameworks/base/services/core/java/com/android/server/am/ActiveServices.java` |
| Broadcast 管理 | `frameworks/base/services/core/java/com/android/server/am/BroadcastQueue.java` |
| Provider 管理 | `frameworks/base/services/core/java/com/android/server/am/ContentProviderHelper.java` |
| ANR / crash 处理 | `frameworks/base/services/core/java/com/android/server/am/AppErrors.java` |
| ATMS 实现 | `frameworks/base/services/core/java/com/android/server/wm/ActivityTaskManagerService.java` |
| Activity 启动器 | `frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java` |
| Activity 记录 | `frameworks/base/services/core/java/com/android/server/wm/ActivityRecord.java` |
| Task 记录 | `frameworks/base/services/core/java/com/android/server/wm/Task.java` |
| 生命周期事务 | `frameworks/base/core/java/android/app/servertransaction/` |
| SystemServer 启动 | `frameworks/base/services/java/com/android/server/SystemServer.java` |
| Zygote Java 入口 | `frameworks/base/core/java/com/android/internal/os/ZygoteInit.java` |
| Zygote 进程启动 | `frameworks/base/core/java/com/android/internal/os/ZygoteConnection.java` |
| LMKD | `system/memory/lmkd/` |

---

## 16. 一图总结

```text
                    ┌──────────────────────────┐
                    │        应用进程 A         │
                    │ Activity / ContextImpl    │
                    │ ActivityThread            │
                    └─────────────┬────────────┘
                                  │
                                  │ Binder：IActivityManager / IActivityTaskManager
                                  ▼
┌────────────────────────────────────────────────────────────────┐
│                         system_server                          │
│                                                                │
│  ┌──────────────────────┐     ┌──────────────────────────────┐ │
│  │ ActivityManagerService│     │ ActivityTaskManagerService   │ │
│  │ 进程 / Service /      │◄───►│ Activity / Task / Display    │ │
│  │ Broadcast / Provider  │     │ 启动模式 / 生命周期调度       │ │
│  └───────────┬──────────┘     └──────────────┬───────────────┘ │
│              │                               │                 │
│              ├─ PMS：组件解析、权限、包信息   │                 │
│              ├─ WMS：窗口、焦点、显示         │                 │
│              ├─ ProcessList/OomAdjuster       │                 │
│              ├─ ActiveServices                │                 │
│              ├─ BroadcastQueue                │                 │
│              └─ AppErrors/AnrHelper           │                 │
└──────────────┬────────────────────────────────┬────────────────┘
               │                                │
               │ socket 请求 fork               │ Binder：IApplicationThread
               ▼                                ▼
        ┌──────────────┐               ┌──────────────────────────┐
        │    Zygote    │               │        应用进程 B         │
        │ fork app     │──────────────►│ ActivityThread.main      │
        └──────────────┘               │ ApplicationThread        │
                                       │ Activity / Service /     │
                                       │ Receiver / Provider      │
                                       └──────────────────────────┘
```

---

## 小结

- **AMS 是应用生命周期和进程状态的总调度中心**，运行在 `system_server`。
- **应用调用 AMS 走 Binder，AMS 回调应用也走 Binder**，回调入口是应用进程的 `ApplicationThread`。
- **现代 Android 中 Activity 主流程由 AMS + ATMS 协作完成**，AMS 更偏进程和状态，ATMS 更偏 Activity/Task/Display。
- **AMS 内部靠各种 Record 维护全局账本**，例如 `ProcessRecord`、`ActivityRecord`、`ServiceRecord`、`BroadcastRecord`。
- **Activity 启动的关键链路是：应用发起 → system_server 校验调度 → 必要时 Zygote fork → 应用 attach → 生命周期事务下发**。
- **Service、Broadcast、Provider 都可能触发进程启动和 ANR**，不能把它们只看成普通 Java 回调。
- **OOM adj 是 AMS 持续计算的进程重要性模型**，LMKD 会基于内存压力和 adj 杀进程。
- **三方定制 AMS 要特别小心安全、功耗、低内存、CTS/GTS 和多用户/多显示场景**，所有白名单和例外都应该有明确边界和可观测日志。

下一讲建议：顺着 `startActivity` 的源码，把 `ActivityStarter`、`ActivityTaskSupervisor`、`ClientTransaction`、`ActivityThread.handleLaunchActivity()` 串起来，单独拆一篇“Activity 启动流程详解”。