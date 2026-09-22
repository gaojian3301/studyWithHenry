# Service 机制详解：从 startService 到 bindService 和前台服务

> 这篇从 App 侧最容易踩的坑切入：后台 `startService()` 为什么直接抛异常，`startForegroundService()` 之后为什么莫名其妙崩溃，`bindService` 为什么能让两个进程绑在一起生死与共，远端 Service 死了 `onServiceDisconnected` 之后该怎么救。目标是把 App 的三种 Service 用法和 AMS 内部的 `ActiveServices` 状态机、OOM adj、ANR 监控串成一条链。
> 目标读者：已经了解 AMS 基本职责（05/06 篇），希望进一步理解 Service 生命周期调度、前台服务限制、绑定关系与进程优先级、Service ANR 以及 ROM 定制点的开发者。
>
> **与 05/06 篇的分工**：AMS 篇只给了 Service 的概览链路；这篇专门展开 Service 本身的细节——返回值语义、绑定记录结构、bind flags、前台服务限制、dumpsys 逐字段解读。

---

## 目录

1. [先建立直觉：Service 是什么，不是什么](#1先建立直觉service-是什么不是什么)
2. [从 App 侧看 Service：一张现象对照表](#2从-app-侧看-service一张现象对照表)
3. [Service 涉及到的核心类](#3service-涉及到的核心类)
4. [ServiceRecord：system_server 眼里的一个 Service](#4servicerecordsystem_server-眼里的一个-service)
5. [startService 完整链路](#5startservice-完整链路)
6. [bindService 完整链路](#6bindservice-完整链路)
7. [bind flags 家族](#7bind-flags-家族)
8. [前台服务](#8前台服务)
9. [后台执行限制](#9后台执行限制)
10. [Service 与进程优先级](#10service-与进程优先级)
11. [跨进程绑定的稳定性：死亡通知与重绑](#11跨进程绑定的稳定性死亡通知与重绑)
12. [Service ANR：超时机制与常见根因](#12service-anr超时机制与常见根因)
13. [dumpsys activity services 输出逐段解读](#13dumpsys-activity-services-输出逐段解读)
14. [常见问题与排查](#14常见问题与排查)
15. [第三方系统常见修改点](#15第三方系统常见修改点)
16. [读源码的推荐路线](#16读源码的推荐路线)
17. [关键源码路径速查](#17关键源码路径速查)
18. [一图总结](#18一图总结)

---

## 1. 先建立直觉：Service 是什么，不是什么

**一句话：Service 是一个由 AMS 调度生命周期、没有界面的应用组件。**

它不是：

- **不是线程**。`onCreate()`、`onStartCommand()` 全部跑在 App 主线程，Service 里照样会卡主线程、照样会 ANR。
- **不是进程保活神器**。普通后台 Service 的进程随时可能被 LMKD 回收，想保活得靠前台服务或绑定关系。
- **不是独立的执行上下文**。它默认和 App 其他组件共享同一个进程、同一个主线程 Looper。

它的正确用途只有两类：

1. **后台任务的容器**：配 `startService()` / `startForegroundService()`。
2. **跨进程/跨组件能力暴露**：配 `bindService()`，对外提供 Binder 接口。

常见的使用组合：

```java
context.startService(intent);              // 后台任务（受后台限制约束）
context.startForegroundService(intent);    // 用户可感知的持续任务（前台服务）
context.bindService(intent, conn, flags);  // 获取远端能力、提高进程优先级
```

---

## 2. 从 App 侧看 Service：一张现象对照表

| App 侧现象 | 背后的 Framework 逻辑 |
|---|---|
| 后台 `startService()` 抛 `IllegalStateException` | Android 8+ 的后台 Service 启动限制，见第 9 节 |
| `startForegroundService()` 后 App 崩溃：did not then call startForeground | 前台服务超时未调 `startForeground()`，见第 8 节 |
| Android 12+ 后台启动前台服务抛 `ForegroundServiceStartNotAllowedException` | FGS 后台启动限制与豁免判定，见第 8.2 节 |
| App 退后台后 Service 被杀 | OOM adj 回落、LMKD 回收，见第 10 节 |
| `bindService` 后进程明显更耐杀 | 绑定关系向 OOM adj 传播重要性，见第 10 节 |
| 远端 Service 死了，`onServiceDisconnected` | Binder 死亡通知链路，见第 11 节 |
| `onStartCommand()` 执行超 20 秒触发 ANR | Service 生命周期主线程执行 + AMS 超时监控，见第 12 节 |
| Service 重启后 intent 丢了 | `onStartCommand()` 返回值语义，见第 5.2 节 |
| `unbindService` 后 Service 又活了 | 还有其他连接或 `stopSelf` 时机不对，见第 6.3 节 |

学习入口建议顺着这些现象走，每一条都能在 `dumpsys activity services` 和 logcat 里找到对应状态。

---

## 3. Service 涉及到的核心类

### 3.1 App 侧类

| 类 | 作用 |
|---|---|
| `Service` | 组件基类，`onCreate` / `onStartCommand` / `onBind` / `onUnbind` / `onDestroy` |
| `ContextImpl` | `startService` / `bindService` 的真正实现入口 |
| `ServiceConnection` | 客户端的连接回调接口 |
| `IServiceConnection` | `ServiceConnection` 的 Binder 包装，由 LoadedApk 创建后交给 AMS |
| `LoadedApk.ServiceDispatcher` | App 进程内管理连接回调分发的结构 |
| `ActivityThread` | 接收 AMS 的调度消息，执行 Service 生命周期 |

### 3.2 Binder 接口

| 接口 | 谁实现 | 作用 |
|---|---|---|
| `IActivityManager.startService` | AMS | App → system_server，请求启动 Service |
| `IActivityManager.bindIsolatedService` | AMS | App → system_server，请求绑定 Service |
| `IApplicationThread.scheduleCreateService` | ActivityThread | system_server → App，调度生命周期 |
| `IServiceConnection` | App 侧 | system_server → App，回传连接状态 |
| `IBinder`（onBind 返回值） | Service 自己 | 真正的能力接口，跨进程时是 BinderProxy |

### 3.3 system_server 侧类（ActiveServices 家族）

| 类 | 作用 |
|---|---|
| `ActiveServices` | Service 的真正管理者，AMS 把所有 Service 请求都转给它 |
| `ServiceRecord` | system_server 眼中的一个 Service（一个组件一份） |
| `ServiceRecord.StartItem` | 一次 `startService` 对应的一个启动项（含 intent） |
| `AppBindRecord` | 某个客户端进程对某个 Service 的绑定关系 |
| `ConnectionRecord` | 一次 `bindService()` 调用对应的一条连接 |
| `ProcessRecord` | 宿主进程记录，持有 OOM adj 状态 |
| `ActiveServices.ServiceMap` | 每个userId/进程的 Service 状态表（starting/executing 队列） |
| `ActivityManagerConstants` | 超时常量（`service_timeout` 等可被 Settings 覆盖） |

记住一个分工：**`AMS` 是外壳，`ActiveServices` 才是干活的**。查 Service 问题基本就是在读 `ServiceRecord` 的状态。

---

## 4. ServiceRecord：system_server 眼里的一个 Service

理解后面所有链路之前，先建立这个模型：

```text
ServiceRecord（每个 Service 组件一份）
  ├─ name / packageName / permission / exported
  ├─ startRequested        是否有未消费的 startService 请求
  ├─ stopIfKilled          由 onStartCommand 返回值决定
  ├─ deliverStartCommand   进程被杀重启后是否重投 intent
  ├─ startList / deliveries  待处理的 StartItem 队列
  ├─ foreGroupId / foreground 是否前台服务
  ├─ bindings              AppBindRecord 集合（谁绑了我）
  ├─ connections           ConnectionRecord 集合（谁连了我）
  ├─ restartCount / restartDelay 重启次数与下次重启时间
  └─ app                   宿主 ProcessRecord（可能为 null：进程还没起来）
```

几种"活着"的状态要分清：

| 状态 | 含义 |
|---|---|
| `app != null` | 宿主进程存在，Service 已创建 |
| `startRequested` | 有人 start 过还没 stop |
| `bindings` 非空 | 还有人绑着 |
| `foreground` | 正以前台服务身份运行 |

**Service 什么时候真正销毁**：`startRequested == false` 且 `bindings == null` 且没有正在执行的生命周期。三个条件少一个都不销毁——这就是为什么"stop 了怎么还在"通常是还有隐藏的绑定。

---

## 5. startService 完整链路

### 5.1 调用链

```text
App 进程
  ContextImpl.startService()
    └─ 限制检查（后台状态直接抛 IllegalStateException）
       └─ IActivityManager.startService()          ← Binder
            ▼
system_server
  ActiveServices.startServiceLocked()
       ├─ retrieveServiceLocked()：通过 PMS 解析 ServiceInfo
       ├─ 权限检查（permission / exported / callingPackage）
       ├─ 后台启动限制检查（bringUpServiceLocked 内）
       ├─ 取出或创建 ServiceRecord
       ├─ 把 intent 包成 StartItem，加入 startList
       └─ bringUpServiceLocked()
            ├─ app == null → 启动宿主进程（AMS.startProcessLocked）
            └─ realStartServiceLocked()
                 ├─ 创建 ProcessRecord 关联
                 ├─ bumpServiceExecutingLocked()（临时抬高优先级，防 ANR 期间被杀）
                 └─ app.thread.scheduleCreateService()   ← Binder 回 App
```

App 进程收到后：

```text
ActivityThread.handleCreateService()
  ├─ LoadedApk 获取类加载器，反射创建 Service
  ├─ ContextImpl + Service.attach()（attachApplication 上下文）
  ├─ service.onCreate()                          ← 主线程执行
  └─ 完成后回报 serviceDoneExecuting()
```

`onCreate()` 执行完后，AMS 继续下发参数：

```text
ActiveServices
  └─ sendServiceArgsLocked()
       └─ app.thread.scheduleServiceArgs()        ← Binder 回 App
            └─ ActivityThread.handleServiceArgs()
                 └─ service.onStartCommand(intent, flags, startId)   ← 主线程
                      └─ 回报 serviceDoneExecuting()
```

两个容易忽略的细节：

- **`onCreate()` 和 `onStartCommand()` 之间隔着一次完整的 Binder 往返**。如果进程冷启动，中间还夹着 `bindApplication`，所以"startService 到 onStartCommand 执行"可能远比想象慢。
- **每次生命周期结束都要回报 `serviceDoneExecuting()`**。AMS 用它来解除"执行中"状态——忘了回报（比如回调里抛异常）会导致该 Service 永远处于 executing 状态，后续 start 全部排队。

### 5.2 onStartCommand 返回值语义

这是 startService 模型里最容易被忽略、又最能解释"Service 被杀后行为"的部分：

| 返回值 | 被杀后 | stopIfKilled | 典型场景 |
|---|---|---|---|
| `START_STICKY` | 重启 Service，**intent 为 null** | false | 音乐播放等"只要活着"的任务 |
| `START_NOT_STICKY` | 不重启 | true | 一次性任务，丢了就丢了 |
| `START_REDELIVER_INTENT` | 重启并**重投最后一次 intent** | true | 下载等"必须做完"的任务 |
| `START_STICKY_COMPATIBILITY` | sticky 的兼容版本，不保证重启 | false | 老版本兼容 |

这些值直接写进 `ServiceRecord`：

```text
onStartCommand 返回值
  └─ ServiceRecord.stopIfKilled / deliverStartCommand
       └─ 进程被杀后 ActiveServices.performScheduleRestartLocked()
            ├─ START_NOT_STICKY → startRequested 置 false，不再重启
            ├─ START_STICKY → 延迟重启（restartDelay 随次数指数增长），intent 为 null
            └─ START_REDELIVER_INTENT → 重启并重投 StartItem 里保留的 intent
```

**重启延迟是指数退避的**：连续崩溃的 Service 重启间隔会越来越长（几秒 → 几十秒），`crashCount` 太高后甚至不再自动重启。排查"Service 崩几次后不起来了"就是这条逻辑。

### 5.3 stop 的三种方式与销毁时机

```text
1. Service 自己调 stopSelf() / stopSelfResult(startId)
     └─ 只在"没有更新的 StartItem"时才真正 stop
        （stopSelfResult 带 startId 可以避免停掉后来又 start 的请求）
2. 客户端调 context.stopService()
3. 所有绑定断开且 startRequested == false → 自然销毁
     └─ Service.onDestroy()
```

**经典坑**：`bindService` 着的 Service 调 `stopSelf()` 不会立刻销毁，要等最后一个 `unbindService` 之后才走 `onDestroy`。

---

## 6. bindService 完整链路

### 6.1 调用链

```text
App 进程（客户端）
  ContextImpl.bindService()
    └─ LoadedApk.getServiceDispatcher(conn)     ← 把回调包成 IServiceConnection
       └─ IActivityManager.bindIsolatedService()  ← Binder
            ▼
system_server
  ActiveServices.bindServiceLocked()
       ├─ 取出或创建 ServiceRecord
       ├─ 找到/创建 AppBindRecord（客户端进程级）
       ├─ 创建 ConnectionRecord（本次 bind 调用级）
       ├─ bringUpServiceLocked()（必要时拉起进程和 Service）
       ├─ 调用 Service.onBind(intent)             ← 目标进程主线程
       │    └─ 返回 IBinder（可能为 null！）
       └─ publishServiceLocked()
            └─ conn.connected(name, binder, dead)  ← Binder 回客户端
                 ▼
App 进程（客户端）
  ServiceDispatcher → 主线程
    └─ ServiceConnection.onServiceConnected(name, binder)
```

结构对应关系要理清：

```text
一次 bindService() 调用   = 1 个 ConnectionRecord
一个客户端进程           = 1 个 AppBindRecord（对一个 ServiceRecord）
一个 Service             = 1 个 ServiceRecord
```

同一个进程对同一个 Service 反复 `bindService`，`AppBindRecord` 只有一份，`ConnectionRecord` 每次加一条。

### 6.2 onBind 的返回值决定连接结果

| onBind 返回 | 客户端收到 | 说明 |
|---|---|---|
| 本地 `Binder` 子类 | 同一进程直接拿到对象 | 进程内调用，没有 Binder 开销 |
| `Binder` 子类（跨进程） | `BinderProxy` | 走 Binder 驱动，见 02 篇 |
| `null` | `onNullBinding()`（API 28+） | 表示"我给你绑了，但不提供接口"，常见于只用绑定提升优先级的场景 |
| 抛异常 | `onServiceDisconnected()` | 绑定失败 |

### 6.3 unbind 与 onUnbind / onRebind

```text
客户端 unbindService(conn)
  └─ ActiveServices.unbindServiceLocked()
       ├─ 移除对应 ConnectionRecord
       ├─ 回调客户端 onServiceDisconnected?（否——只在服务端死亡时回调）
       ├─ 若该客户端没有连接了 → bringDownServiceIfNeededLocked()
       │    └─ 调用 Service.onUnbind(intent)
       │         └─ onUnbind 返回 true → 记下"希望 onRebind"
       └─ Service 真正销毁时走 onDestroy()
```

**两个高频疑问**：

1. **`onServiceDisconnected` 什么时候调？** 只在**服务端进程死亡**时回调，正常 `unbind` 不会调它。它不代表"断开"，而是"对面死了"。
2. **`onRebind` 什么时候调？** 只有 `onUnbind()` 返回过 `true`，且 Service 没销毁（start 请求还在）时，下一次 bind 才走 `onRebind` 而不是重新 `onCreate`。

### 6.4 isolated process 与多实例

- `android:isolatedProcess="true"` 的 Service 跑在无权限的独立沙箱进程里，配合 `bindIsolatedService` 可以对一个 Service 创建多个隔离实例。
- 典型用途：解析不可信内容（渲染、解压），崩了不影响主进程。
- 车机上也可能用它隔离第三方插件逻辑。

---

## 7. bind flags 家族

`bindService` 的第三个参数是理解绑定行为的关键，每个 flag 对应 OOM adj / 调度上的一种诉求：

| flag | 作用 |
|---|---|
| `BIND_AUTO_CREATE` | 绑定时自动创建 Service（最常用） |
| `BIND_NOT_FOREGROUND` | 绑定不把服务端抬到前台优先级 |
| `BIND_ABOVE_CLIENT` | 服务端优先级不低于客户端（服务端比客户端更重要） |
| `BIND_ALLOW_OOM_MANAGEMENT` | 允许系统按普通方式管理该进程（弱化保活） |
| `BIND_WAIVE_PRIORITY` | 绑定完全不影响服务端优先级 |
| `BIND_IMPORTANT` | 把服务端抬到接近前台的重要性 |
| `BIND_ADJUST_WITH_ACTIVITY` | 随绑定方 Activity 的可见性动态调整优先级 |
| `BIND_NOT_PERCEPTIBLE`（API 29+） | 服务端降为"不可感知"级别 |
| `BIND_INCLUDE_CAPABILITIES`（API 31+） | 把客户端的能力（如前台定位）传给服务端 |
| `BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS`（API 31+） | 允许服务端在后台启动 Activity |

**选型直觉**：

- 普通能力获取：`BIND_AUTO_CREATE`。
- 不想让绑定拖累功耗：加 `BIND_NOT_FOREGROUND` 或 `BIND_WAIVE_PRIORITY`。
- 客户端是 Activity、希望优先级跟随界面：`BIND_ADJUST_WITH_ACTIVITY`。
- 系统级关键服务（车机的车控服务）：`BIND_ABOVE_CLIENT` / `BIND_IMPORTANT`。

---

## 8. 前台服务

前台服务是 Service 家族里限制最细、版本差异最大的部分。

### 8.1 基本用法与时限

```java
// 客户端
context.startForegroundService(intent);

// Service 侧：启动后必须尽快调用
Notification notification = buildNotification();   // 必须是合规通知
startForeground(id, notification);
```

超时机制：

```text
ActiveServices
  └─ 标记 ServiceRecord 为"期望前台"，启动计时
       └─ 超时（默认 10 秒左右，常量可被系统设置覆盖）仍未 startForeground
            └─ 抛异常并终止该 Service
               logcat 关键字：
               "Context.startForegroundService() did not then call Service.startForeground()"
```

**调用 `startForeground` 也有失败情况**：通知被用户/策略禁用、通知渠道被删，都可能让前台化失败——表现同样是超时崩溃。排查时通知和 Service 两条线都要看（关联 17 通知篇）。

### 8.2 Android 12+：后台启动前台服务的限制

Android 12 起从后台调 `startForegroundService()` 可能直接抛：

```text
ForegroundServiceStartNotAllowedException
```

判定大致是：调用方进程不在"可从后台启动前台服务"的豁免列表里就拒绝。常见豁免：

| 豁免场景 | 说明 |
|---|---|
| 有可见 Activity | App 在前台 |
| 有另一个前台服务在跑 | 已有前台身份 |
| 收到了高优先级 FCM 消息 | 数据消息不算，需要高优先级推送 |
| 闹钟/精确任务完成回调 | `AlarmManager` 精确闹钟等 |
| 绑定了可见 UI 的进程 | 例如车机仪表、UI 组件绑定 |
| 设备管理器 / 器件配套应用 | Device Admin、CompanionDeviceManager |
| 短暂白名单（temp-allowlist） | 系统临时授予，如广播高优先级回调之后 |

**车机/ROM 视角**：这套豁免由 `ActiveServices` 的 FGS 启动限制逻辑 + `PowerExemptionManager`（原 DeviceIdle whitelist）共同决定，ROM 定制通常就是往白名单/豁免逻辑里加自家包名。

### 8.3 Android 13/14：前台服务类型

Android 14 要求前台服务必须声明**类型**，且每个类型对应专门权限：

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
...

<service
    android:name=".PlaybackService"
    android:foregroundServiceType="mediaPlayback|location" />
```

| 类型 | 用途 | 备注 |
|---|---|---|
| `dataSync` / `mediaProcessing` | 数据同步 / 媒体处理 | Android 15 起有 6 小时时长上限的先行版本约束 |
| `mediaPlayback` | 音视频播放 | 车机音乐类最常用 |
| `location` | 后台定位 | 需要定位权限链路配合 |
| `camera` / `microphone` | 采集类 | 和隐私指示器联动 |
| `connectedDevice` | 外设/车机互联 | 车机互联场景常用 |
| `shortService` | 超短任务 | 见 8.5 |

### 8.4 前台服务与通知、进程状态

```text
startForeground(id, notification)
  ├─ ServiceRecord.foreground = true
  ├─ 通知发给 NMS（用户可感知、不可被滑动清除）
  └─ ProcessRecord 进程状态提升为 FOREGROUND_SERVICE
       └─ OOM adj 抬升，普通场景不会被 LMKD 回收（见第 10 节）
```

`stopForeground()` 之后，通知消失、进程状态回落，但 Service 本身还活着。

### 8.5 shortService 与 onTimeout

Android 14 引入 `shortService` 类型：短时前台服务，系统给它一个很短的运行窗口，超时前会回调：

```java
@Override
public void onTimeout(int startId) {
    // 在被系统终止前收尾
    stopSelf();
}
```

适合"几秒内能完成"的任务（保存状态、同步一小批数据）。用错了类型（长任务挂 shortService）会频繁被杀。

---

## 9. 后台执行限制

### 9.1 Android 8（O）起：后台不能 startService

```text
App 处于后台（无可见 Activity、无前台服务、不在白名单）
  └─ context.startService()
       └─ IllegalStateException: Not allowed to start service Intent: app is in background
```

合规做法按优先级：

1. 改用 `JobScheduler` / `WorkManager`。
2. 场景确实需要立即执行 → `startForegroundService()`。
3. 系统级 App → 申请加入省电白名单（`PowerExemptionManager` / `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）。

### 9.2 隐式广播限制的连带影响

Android 8 同时限制了大部分隐式静态广播（见 08 广播篇），很多原来"收开机广播再拉 Service"的模式失效。常见替代：

- `BOOT_COMPLETED` → `startForegroundService`（仍允许）。
- 周期任务 → `JobScheduler`。
- 事件驱动 → 显式广播或 `JobScheduler` 的触发条件。

### 9.3 车机视角

车机 ROM 的后台策略通常比手机宽松（导航、音乐、TTS 常驻是刚需），常见定制：

- 把自家核心包名加入 FGS 启动豁免和省电白名单。
- 放宽 cached 进程的杀进程阈值。
- 对特定 Service 关闭 ANR 超时监控（谨慎）。

但**放宽不等于无限制**：多屏、多用户下后台 Service 数量失控会直接拖垮内存，定制时要有包名 + 数量上限。

---

## 10. Service 与进程优先级

Service 是 OOM adj 计算里最活跃的因子之一。定性关系（从高到低）：

```text
前台可见 Activity
   ▼
前台服务（FOREGROUND_SERVICE 状态）
   ▼
正在执行生命周期的 Service（executing，临时抬升，防止生命周期中途被杀）
   ▼
被前台 App 绑定的 Service（继承客户端重要性，可被 flags 调整）
   ▼
recent / B Service（近期用过的空转服务进程）
   ▼
cached 进程（随时可杀）
```

几条值得记住的规则：

- **绑定关系会双向影响**：客户端前台 → 服务端被抬高；`BIND_ABOVE_CLIENT` 时服务端甚至不低于客户端。
- **`BIND_WAIVE_PRIORITY` 是显式豁免**，适合"我只是取个能力，别为我改优先级"。
- **前台服务不是不死之身**：被用户强制停止、DevicePolicy 限制、ROM 白名单策略，仍然可以杀。
- **正在执行生命周期的 Service 进程被临时保护**——这就是为什么 Service 卡死引发的往往不是进程被杀，而是 ANR（见第 12 节）。

---

## 11. 跨进程绑定的稳定性：死亡通知与重绑

跨进程 `bindService` 后，服务端随时可能死亡（崩溃、被 LMKD 杀、被用户停止）。

### 11.1 三种客户端回调，含义不同

| 回调 | 触发条件 | 该做什么 |
|---|---|---|
| `onServiceConnected` | 绑定建立 | 拿到 `IBinder`，开始使用 |
| `onServiceDisconnected` | **服务端进程死亡**（正常 unbind 不回调） | 清理本地引用，等系统重绑 |
| `onBindingDied`（API 28+） | ServiceRecord 对应组件死亡且无法原地恢复 | **主动重新 `bindService`**，这是和 Disconnected 的关键区别 |
| `onNullBinding`（API 28+） | `onBind` 返回 null | 没有接口可用，按需 unbind |

### 11.2 服务端死亡后的系统行为

```text
服务端进程死亡
  └─ ActiveServices 获得死亡通知（Binder linkToDeath）
       ├─ 清理该进程的 ServiceRecord 状态
       ├─ 通知所有客户端 onServiceDisconnected / onBindingDied
       └─ 若满足重启条件（startRequested / BIND_AUTO_CREATE 的连接还在）
            └─ 按 restartDelay 指数退避重新拉起
                 └─ 恢复后回调 onServiceConnected（对 BIND_AUTO_CREATE 的连接自动重连）
```

### 11.3 客户端健壮性清单

```java
private final ServiceConnection mConn = new ServiceConnection() {
    @Override public void onServiceConnected(ComponentName name, IBinder binder) {
        mRemote = IMyAidlInterface.Stub.asInterface(binder);
        // 建议再加一层死亡兜底
        binder.linkToDeath(mDeathRecipient, 0);
    }
    @Override public void onServiceDisconnected(ComponentName name) {
        mRemote = null;                    // 立刻置空，避免 DeadObjectException 扩散
    }
    @Override public void onBindingDied(ComponentName name) {
        context.bindService(...);          // 主动重绑
    }
};
```

- 所有远端调用都要捕获 `DeadObjectException`（通常还需处理 `RemoteException`）。
- "重绑风暴"要防：服务端反复崩溃时，指数退避由系统控制，但客户端自己的 `onBindingDied` 重试也要加退避。
- 车机的车控/TTS 服务长期跨进程，这条链路是稳定性事故高发区。

---

## 12. Service ANR：超时机制与常见根因

### 12.1 超时规则

```text
Service 生命周期回调（onCreate / onStartCommand / onBind / onRebind ...）
  ├─ 全部在 App 主线程执行
  └─ AMS 侧计时：bumpServiceExecutingLocked() 埋下超时消息
       ├─ 前台（有 Activity/前台服务相关）超时：约 20 秒
       ├─ 后台超时：约 200 秒（前台 × 8）
       └─ serviceDoneExecuting() 回报 → 撤销超时消息
```

超时常量可被系统设置覆盖（ROM 调参常用）：

```bash
adb shell settings put global service_timeout 60000           # 前台 20s → 60s
adb shell settings put global service_background_timeout 300000
# 读取当前值：
adb shell settings get global activity_manager_constants
```

ANR log 关键字：

```text
ANR in com.xxx/.MyService
Reason: Executing service com.xxx/.MyService
```

### 12.2 常见根因

| 根因 | 典型表现 |
|---|---|
| `onCreate()` 里同步初始化 SDK/读大文件 | 冷启动进程后直接 ANR |
| `onStartCommand()` 做网络/数据库 | 收到 intent 后卡住 |
| `onBind()` 里等锁（常见于和另一个线程抢单例锁） | `bindService` 后超时 |
| 主线程被同步 Binder 调用阻塞（调远端且远端也卡） | 双进程互锁 |
| 主线程被 `BroadcastReceiver.onReceive` 等其他消息占住 | Service 回调排队超时 |

### 12.3 排查方法

```bash
# 超时时抓主线程栈——直接指向卡住的行
adb shell kill -3 <pid>          # 或 debuggerd
# 看 data/anr/traces.txt 里主线程状态

# 观察 Service 的执行状态
adb shell dumpsys activity services <package>
```

---

## 13. dumpsys activity services 输出逐段解读

排查 Service 问题的主力命令，值得逐字段看懂：

```text
* ServiceRecord{1a2b3c4 u0 com.car.app/.control.CarService}
    intent={cmp=com.car.app/.control.CarService}
      → 这个 Service 的组件与 intent
    packageName=com.car.app
    processName=com.car.app           → 宿主进程名（和进程是否分离有关）
    permission=null                   → bind/start 需要的权限（null 表示不校验）
    baseDir=/system/app/CarApp/CarApp.apk
    app=ProcessRecord{... pid=1234}   → 宿主进程；null 说明进程还没起来
    created=-1m52s startRequested=true stopIfKilled=false
      → created：已创建时长；startRequested：有未 stop 的 start 请求
      → stopIfKilled：由 onStartCommand 返回值决定（见 5.2）
    callStart=true lastActivity=-1m50s
    executingStart=-1m49s             → 正在执行生命周期的起点（配合超时排查）
    restarts=#0 starts=#3             → 重启次数 / start 次数
    foreground=true fgId=42 fgType='mediaPlayback'
      → 是否前台服务、通知 id、声明的类型
    connections:
      * ConnectionRecord{... com.client.app/.MainActivity:@1a2b}
        binding=AppBindRecord{... com.client.app}
          flags=0x1(BIND_AUTO_CREATE)   → bind flags，直接决定优先级行为
        created=-1m50s
```

读法建议：

1. **先看 `app`**：null → 进程没起来，问题在进程启动链路。
2. **再看 `foreground` / `startRequested` / `connections`**：确认它"为什么活着"。
3. **看 `executingStart` 和超时**：和 ANR 时间对齐。
4. **看 `restarts` / `crashCount`**：解释"为什么越重启越慢、最后不起了"。
5. **看 connection 的 `flags`**：优先级和保活行为几乎都由它决定。

---

## 14. 常见问题与排查

```bash
# Service 状态（主力命令）
adb shell dumpsys activity services <package>

# 前台服务专项
adb shell dumpsys activity services | grep -i "foreground\|fgId"
adb shell dumpsys notification --noredact | grep -i "foreground"

# 进程优先级
adb shell dumpsys activity processes | grep -A10 <package>

# 日志
adb logcat -b all | grep -iE "ActiveServices|ServiceRecord|ForegroundService|ANR"
```

**后台 startService 抛 IllegalStateException**

1. 确认调用时进程状态（是否真在后台）。
2. 查省电白名单：`adb shell dumpsys deviceidle whitelist`。
3. 车机 ROM 查 FGS/后台启动豁免策略。

**前台服务超时崩溃**

1. `onCreate/onStartCommand` 里是否及时调了 `startForeground`。
2. 通知渠道是否被删、通知是否被策略屏蔽（导致前台化失败）。
3. Android 14+ 检查 `foregroundServiceType` 与权限是否齐全。
4. `dumpsys activity services` 看该 Service 是否还挂着"期望前台"状态。

**bindService 不回调 onServiceConnected**

1. `dumpsys` 看 `onBind` 是否已执行（AppBindRecord 是否存在）。
2. 权限：服务端 `android:permission` 是否拦了客户端。
3. `onBind` 是否返回 null（此时走 `onNullBinding`）。
4. 是否绑定超时前服务端就崩了（看服务端进程）。

**Service 反复重启越来越慢**

1. 看 `crashCount` / `restarts`。
2. 指数退避是设计行为；根因还是要修服务端崩溃。
3. ROM 侧可调整重启策略，但先确认不是掩盖 bug。

---

## 15. 第三方系统常见修改点

| 需求 | 主要改动位置 | 注意 |
|---|---|---|
| 后台 Service 白名单 | FGS 启动限制豁免 / 省电白名单 | 要有包名准入控制，避免失控 |
| 前台服务超时阈值 | `ActiveServices` 常量或 Settings `service_start_foreground_timeout_ms` 一类 | 只对自家受控服务放宽 |
| Service ANR 超时 | Settings `service_timeout` / `service_background_timeout` | 全局生效，影响面大 |
| 关键服务保活 | `BIND_ABOVE_CLIENT` / `BIND_IMPORTANT` + 白名单 | 配合 05 篇 OOM adj 一起看 |
| 绑定优先级策略 | `ActiveServices` 的 OOM adj 传播逻辑 | 影响全系统，改动要回归 LMKD 表现 |
| 隔离插件进程 | `isolatedProcess` + `bindIsolatedService` | 注意隔离进程没有系统权限 |
| 车机常驻服务 | 白名单 + 前台服务 + 绑定组合拳 | 验证多用户/多屏下每个用户的表现 |

**原则**：Service 策略直接决定后台常驻、功耗和内存水位。放宽限制必须"按包名、按类型、留日志"，否则兜不住第三方 App 滥用。

---

## 16. 读源码的推荐路线

### 16.1 startService

```text
ContextImpl.startService
IActivityManager.startService
ActiveServices.startServiceLocked
ActiveServices.bringUpServiceLocked
ActiveServices.realStartServiceLocked
ActivityThread.scheduleCreateService → handleCreateService
ActiveServices.sendServiceArgsLocked
ActivityThread.handleServiceArgs → onStartCommand
serviceDoneExecuting
```

### 16.2 bindService

```text
ContextImpl.bindService
LoadedApk.getServiceDispatcher
IActivityManager.bindIsolatedService
ActiveServices.bindServiceLocked
Service.onBind
ActiveServices.publishServiceLocked
IServiceConnection.connected
ServiceConnection.onServiceConnected
```

### 16.3 前台服务

```text
ActiveServices.setServiceForeground
ServiceRecord.foreground
NotificationManagerService 发通知
ProcessRecord 进程状态 → OOM adj
超时监控 → ForegroundServiceDidNotStartInTimeException 一类
```

### 16.4 服务端死亡与重启

```text
进程死亡通知（linkToDeath / binderDied）
ActiveServices.serviceDied / performScheduleRestartLocked
restartDelay 指数退避
重新 bringUpServiceLocked
```

### 16.5 ANR 监控

```text
ActiveServices.bumpServiceExecutingLocked
消息 ServiceTimeout / ServiceForegroundAnr
ActivityManagerConstants.service_timeout
AppErrors / AMS ANR 流程
```

---

## 17. 关键源码路径速查

| 内容 | 路径 |
|---|---|
| Service 管理核心 | `frameworks/base/services/core/java/com/android/server/am/ActiveServices.java` |
| ServiceRecord | `frameworks/base/services/core/java/com/android/server/am/ServiceRecord.java` |
| ConnectionRecord | `frameworks/base/services/core/java/com/android/server/am/ConnectionRecord.java` |
| AppBindRecord | `frameworks/base/services/core/java/com/android/server/am/AppBindRecord.java` |
| AMS 外壳 | `frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java` |
| 超时常量 | `frameworks/base/services/core/java/com/android/server/am/ActivityManagerConstants.java` |
| 前台服务豁免 | `frameworks/base/services/core/java/com/android/server/power/`（PowerExemptionManager 相关） |
| App 侧 Service | `frameworks/base/core/java/android/app/Service.java` |
| 生命周期调度 | `frameworks/base/core/java/android/app/ActivityThread.java`（handleCreateService / handleServiceArgs / handleBindService） |
| 连接分发 | `frameworks/base/core/java/android/app/LoadedApk.java`（ServiceDispatcher） |
| Context 实现 | `frameworks/base/core/java/android/app/ContextImpl.java` |
| FGS 类型定义 | `frameworks/base/core/java/android/content/pm/ServiceInfo.java` |

---

## 18. 一图总结

```text
App 进程                              system_server
  startService / startForegroundService
  bindService(conn, flags)
        │ IActivityManager
        ▼
  ActiveServices
        ├─ retrieveServiceLocked（PMS 解析）
        ├─ 校验：权限 / 后台启动限制 / FGS 豁免
        ├─ ServiceRecord
        │    ├─ startRequested / stopIfKilled / deliverStartCommand
        │    ├─ foreground（前台服务）+ 通知
        │    ├─ bindings（AppBindRecord）
        │    └─ connections（ConnectionRecord）
        ├─ bringUpServiceLocked
        │    └─ 进程不存在 → startProcessLocked
        └─ realStartServiceLocked
             │ IApplicationThread（Binder）
             ▼
App 进程
  handleCreateService → onCreate
  handleServiceArgs   → onStartCommand
  handleBindService   → onBind
        │ serviceDoneExecuting / publishService
        ▼
  ActiveServices
        ├─ 解除 executing（撤销 ANR 计时）
        ├─ 前台服务 → OOM adj 抬升
        ├─ 绑定关系 → 优先级传播（flags 决定方向）
        └─ 死亡 → 指数退避重启 / 通知客户端断开
```

---

## 小结

- **Service 不是线程**，生命周期回调全在主线程，卡 Service 就是卡主线程。
- **`ActiveServices` 是真正的管理者**，`ServiceRecord` 的 `startRequested` / `foreground` / `bindings` 三个状态决定了它"为什么活着、什么时候死"。
- **`onStartCommand` 的返回值决定被杀后的行为**：STICKY 空投重启、NOT_STICKY 不重启、REDELIVER 重投 intent；重启延迟指数退避。
- **`bindService` 的三张记录表**（ServiceRecord / AppBindRecord / ConnectionRecord）和 **bind flags** 决定了绑定对 OOM adj 的影响方向。
- **前台服务是"通知 + 类型 + 权限 + 时限"的组合拳**：超时不 `startForeground` 会崩，Android 12 后台启动要豁免，Android 14 要类型和配套权限。
- **`onServiceDisconnected` 只在服务端死亡时回调**，正常 unbind 不走它；要健壮就用 `onBindingDied` 主动重绑 + `linkToDeath` 兜底。
- **Service ANR 前台 20 秒 / 后台 200 秒**，常量可被 Settings 覆盖；`dumpsys activity services` 的 `executingStart` 是对时神器。
- **ROM 定制的正确姿势是"按包名、按类型、留日志"地放宽**，而不是全局关限制。

如果只记一个核心模型：

> startService 决定"活多久、死了怎么复活"，bindService 决定"和谁绑在一起生死"，前台服务决定"系统能不能容忍你一直活着"——三者全部落在 ActiveServices 的 ServiceRecord 状态机上。
