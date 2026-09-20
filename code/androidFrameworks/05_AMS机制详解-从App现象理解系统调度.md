# AMS 机制详解：从 App 现象理解系统调度

> 这篇不从源码类开始讲，而是从 App 开发者最容易看到的现象切入：为什么 `startActivity()` 会失败，为什么 Service 起不来，为什么广播收不到，为什么应用会被杀，为什么 ANR 文件里总能看到 AMS。
> 目标读者：写过 Android App 或系统应用，知道四大组件，但希望把“App 侧现象”和 Framework 里的 AMS/ATMS 调度关系对应起来。

---

## 目录

1. [先建立直觉：你在 App 里看到的很多现象，背后都是 AMS 在调度](#1先建立直觉你在-app-里看到的很多现象背后都是-ams-在调度)
2. [从 App 看 AMS：一张现象对照表](#2从-app-看-ams一张现象对照表)
3. [App 调用系统服务的基本模型](#3app-调用系统服务的基本模型)
4. [现象一：startActivity 为什么不是简单地 new 一个 Activity](#4现象一startactivity-为什么不是简单地-new-一个-activity)
5. [现象二：为什么有时 Activity 启动失败或启动很慢](#5现象二为什么有时-activity-启动失败或启动很慢)
6. [现象三：为什么 Application.onCreate 比 Activity.onCreate 更早影响冷启动](#6现象三为什么-applicationoncreate-比-activityoncreate-更早影响冷启动)
7. [现象四：为什么后台 Service 起不来](#7现象四为什么后台-service-起不来)
8. [现象五：为什么前台服务还会启动失败](#8现象五为什么前台服务还会启动失败)
9. [现象六：为什么广播收不到、收得慢、还会 ANR](#9现象六为什么广播收不到收得慢还会-anr)
10. [现象七：为什么 App 明明没崩溃却被杀了](#10现象七为什么-app-明明没崩溃却被杀了)
11. [现象八：为什么 bindService 会影响两个进程的生死](#11现象八为什么-bindservice-会影响两个进程的生死)
12. [现象九：为什么 ContentProvider 会拖慢启动](#12现象九为什么-contentprovider-会拖慢启动)
13. [现象十：为什么 ANR 经常不是卡在你以为的位置](#13现象十为什么-anr-经常不是卡在你以为的位置)
14. [从 dumpsys 和 logcat 看 AMS 在想什么](#14从-dumpsys-和-logcat-看-ams-在想什么)
15. [AMS 涉及的核心类：按 App 现象记](#15ams-涉及的核心类按-app-现象记)
16. [第三方系统常见修改：从 App 需求到 Framework 策略](#16第三方系统常见修改从-app-需求到-framework-策略)
17. [读源码的推荐路线](#17读源码的推荐路线)
18. [一图总结](#18一图总结)

---

## 1. 先建立直觉：你在 App 里看到的很多现象，背后都是 AMS 在调度

App 开发时，你写的代码通常像这样：

```java
startActivity(intent);
startService(intent);
bindService(intent, connection, flags);
sendBroadcast(intent);
```

这些 API 看起来很像本地函数调用，但它们真正要做的事情远远不止“调用一个方法”：

- `startActivity()` 不是直接 new 一个 Activity，而是请求系统帮你解析 Intent、检查权限、选择 task、必要时启动目标进程，再让目标进程创建 Activity。
- `startService()` 不是直接 new 一个 Service，而是请求 AMS 判断后台限制、创建 `ServiceRecord`、启动目标进程，再调度应用主线程执行生命周期。
- `sendBroadcast()` 不是简单遍历回调，而是由 AMS 查询静态/动态 Receiver，按队列和优先级分发，还要处理超时和后台限制。
- App 被杀不一定是 crash，也可能是 AMS 计算进程优先级后，LMKD 在内存压力下杀掉了它。

所以可以先用一句话建立直觉：

> AMS 是 App 世界和系统调度世界之间的总协调者。App 只发起请求，真正决定“能不能做、什么时候做、在哪个进程做、做完如何回报”的，是 system_server 里的 AMS/ATMS。

这里要特别注意版本差异：Android 10 之后，Activity/Task 相关逻辑大量迁移到了 `ActivityTaskManagerService`（ATMS）。实际读源码时，Activity 启动主线经常是 ATMS 在前台调度，AMS 负责进程、Service、Broadcast、Provider、OOM、ANR 等底层状态。但从 App 现象理解时，可以先把 AMS/ATMS 看成一组共同工作的 Activity/进程调度中心。

---

## 2. 从 App 看 AMS：一张现象对照表

| App 侧现象 | 你看到的表象 | Framework 背后的关键点 | 重点类 |
|---|---|---|---|
| Activity 启动失败 | `ActivityNotFoundException`、`SecurityException`、无反应 | Intent 解析、exported、权限、后台启动限制、userId | `ActivityTaskManagerService`、`ActivityStarter`、`ActivityRecord` |
| Activity 启动慢 | 白屏、黑屏、冷启动久 | pause 前一个 Activity、fork 进程、bindApplication、Provider 安装、生命周期调度 | `ActivityTaskSupervisor`、`ProcessList`、`ActivityThread` |
| Application 初始化慢 | Activity 还没进 `onCreate()` 就卡 | `bindApplication` 阶段发生在 Activity 创建之前 | `ActivityThread`、`LoadedApk`、`IApplicationThread` |
| Service 起不来 | 后台调用失败、抛异常 | Android 8+ 后台 Service 限制、前台服务限制 | `ActivityManagerService`、`ActiveServices`、`ServiceRecord` |
| 前台服务失败 | `ForegroundServiceStartNotAllowedException` | 后台启动前台服务限制、类型和权限检查 | `ActiveServices`、`AppOps` |
| 广播收不到 | 静态 Receiver 没触发 | 隐式广播限制、stopped 状态、权限、用户状态 | `BroadcastQueue`、`BroadcastRecord`、`ReceiverList` |
| App 被杀 | 没 crash，但进程没了 | OOM adj、cached 进程、LMKD、后台限制 | `OomAdjuster`、`ProcessRecord`、`ProcessList`、`lmkd` |
| ANR | 弹框或后台 ANR | 主线程超时、广播/Service/Provider 超时、system_server 等待回报 | `AnrHelper`、`AppErrors`、`ProcessErrorStateRecord` |
| bindService 后进程不容易死 | 被前台进程绑定后存活更久 | 绑定关系会传播进程重要性 | `ConnectionRecord`、`AppBindRecord`、`OomAdjuster` |
| ContentProvider 拖慢启动 | 冷启动早期耗时 | Provider 在 `Application.onCreate()` 前安装 | `ContentProviderHelper`、`ActivityThread` |

这张表是读 AMS 的入口：先从你看到的 App 现象出发，再找到 system_server 里对应的调度模块。

---

## 3. App 调用系统服务的基本模型

App 和 AMS/ATMS 的交互不是普通方法调用，而是 Binder IPC。

### 3.1 App 调 AMS

以 Activity 启动为例：

```text
App 进程
  Activity.startActivity()
        │
        ▼
  Instrumentation.execStartActivity()
        │
        ▼
  ActivityTaskManager.getService().startActivity(...)
        │ Binder
        ▼
system_server
  ActivityTaskManagerService / ActivityManagerService
```

以 Service 为例：

```text
App 进程
  ContextImpl.startService()
        │
        ▼
  ActivityManager.getService().startService(...)
        │ Binder
        ▼
system_server
  ActivityManagerService -> ActiveServices
```

你在 App 里看到的是 `Context` API，真正进入系统后会变成对 `IActivityManager` 或 `IActivityTaskManager` 的 Binder 调用。

### 3.2 AMS 回调 App

system_server 不能直接调用你进程里的 Java 对象。它会通过应用进程启动时注册上来的 `ApplicationThread` 反向调用 App。

```text
App 进程启动
  ActivityThread.main()
        │
        ▼
  ActivityThread.attach(false)
        │ Binder：把 ApplicationThread 交给 AMS
        ▼
system_server
  AMS 保存到 ProcessRecord.thread
```

之后 AMS/ATMS 要让 App 创建 Activity、Service、Receiver，就会走：

```text
system_server
  IApplicationThread.scheduleXXX(...)
        │ Binder
        ▼
App 进程
  ApplicationThread
        │
        ▼
  ActivityThread.H 切到主线程
        │
        ▼
  执行 onCreate / onStart / onResume / onReceive / onBind ...
```

这解释了一个关键现象：

> 组件生命周期最终在 App 主线程执行；AMS 只是调度者，不会替你在 system_server 里执行 Activity 或 Service 的业务代码。

---

## 4. 现象一：startActivity 为什么不是简单地 new 一个 Activity

很多初学者会问：既然 Activity 是一个 Java 类，为什么不能直接 `new MainActivity()`？

因为 Activity 不是普通 Java 对象，它必须被系统纳入管理：

- 要有 `Context`。
- 要绑定 `Window`。
- 要有 Activity token。
- 要进入 task/back stack。
- 要受生命周期调度。
- 要被 WMS 管窗口、输入、焦点。
- 要受权限、多用户、后台启动限制约束。

所以 `startActivity()` 实际是在向系统发请求：

```text
我是谁：calling uid / package / userId
我要启动谁：Intent / ComponentName / resolvedType
从哪里启动：source Activity token
怎么启动：flags / launchMode / ActivityOptions
是否要结果：requestCode / resultTo
```

system_server 收到后才会决定：目标是谁、能不能启动、放到哪个 task、是否要新建进程、何时调度生命周期。

### 4.1 App 侧最容易看到的结果

```java
startActivity(intent);
```

可能出现几类结果：

- 立刻成功：目标进程已存在，Activity 很快创建。
- 冷启动成功但慢：目标进程不存在，需要 fork、bindApplication、创建 Application、安装 Provider。
- 直接抛异常：Intent 解析失败或权限检查失败。
- 没有明显反应：被后台启动限制、task 复用、目标 Activity 已在栈顶、启动到其他 display/user。
- 启动后马上退出：目标进程 crash、Activity `onCreate()` 抛异常、主题/资源加载失败。

### 4.2 Framework 里的核心判断

`ActivityStarter` 会处理很多启动规则：

- `FLAG_ACTIVITY_NEW_TASK`
- `FLAG_ACTIVITY_CLEAR_TOP`
- `FLAG_ACTIVITY_SINGLE_TOP`
- `launchMode=standard/singleTop/singleTask/singleInstance/singleInstancePerTask`
- task 复用
- resultTo 是否允许
- 后台启动是否允许
- 目标 Activity 是否 exported
- 调用方是否有权限

也就是说，App 侧一行 `startActivity()`，Framework 里其实是一套完整的“组件解析 + 安全校验 + task 策略 + 进程调度”。

---

## 5. 现象二：为什么有时 Activity 启动失败或启动很慢

Activity 启动问题可以按“失败”和“慢”分开看。

### 5.1 启动失败：常见 App 侧异常

#### ActivityNotFoundException

典型原因：

- Intent action/category/data 没有匹配到 Activity。
- 显式组件名写错。
- 目标应用没安装。
- 目标组件在当前 user 下不可见。
- Android 11+ 包可见性限制导致查询不到目标包。

App 侧看到：

```text
android.content.ActivityNotFoundException: No Activity found to handle Intent
```

Framework 背后：PMS 解析 Intent 时没有找到可用 `ResolveInfo`。

#### SecurityException

典型原因：

- 目标 Activity 没有 `exported=true`。
- 调用方缺少目标组件要求的 permission。
- 跨用户启动但没有跨用户权限。
- 后台启动 Activity 被限制。

App 侧看到：

```text
java.lang.SecurityException: Permission Denial: starting Intent ...
```

Framework 背后：AMS/ATMS 在启动前基于 calling uid/pid、permission、userId、exported 做了拦截。

### 5.2 启动慢：常见卡点

启动慢不是只有 Activity 的 `onCreate()` 慢。完整链路里任何一段慢，用户都可能看到白屏、黑屏或点击无反应。

```text
点击图标 / startActivity
  ├─ Launcher 或调用方发起 Binder
  ├─ system_server 解析 Intent 和 task
  ├─ pause 当前 Activity
  ├─ fork 目标进程
  ├─ bindApplication
  ├─ 安装 ContentProvider
  ├─ Application.onCreate
  ├─ Activity.onCreate
  ├─ Activity.onResume
  └─ 首帧绘制
```

App 开发者最容易误判的是：只盯着 `Activity.onCreate()`。但很多冷启动问题发生在它之前。

### 5.3 App 侧怎么观察

```bash
adb shell am start -W -n com.example/.MainActivity
```

常见输出字段：

- `ThisTime`：最后一个 Activity 启动耗时。
- `TotalTime`：整个启动链路耗时。
- `WaitTime`：命令等待启动完成的总耗时。

再结合 logcat：

```bash
adb logcat -b all | grep -i "ActivityTaskManager\|ActivityManager\|Displayed\|START"
```

如果 `bindApplication` 之前就慢，优先看进程启动、Provider、Application；如果 `Displayed` 很晚，优先看 Activity 创建、布局、首帧绘制。

---

## 6. 现象三：为什么 Application.onCreate 比 Activity.onCreate 更早影响冷启动

很多 App 冷启动慢，开发者打开 trace 才发现：Activity 还没进 `onCreate()`，主线程已经忙了很久。

原因在于新进程启动后，AMS 首先调度的是 `bindApplication`。

```text
Zygote fork 新进程
  └─ ActivityThread.main()
       └─ ActivityThread.attach(false)
            └─ AMS.attachApplication
                 └─ IApplicationThread.bindApplication
                      └─ ActivityThread.handleBindApplication
                           ├─ 创建 LoadedApk
                           ├─ 创建 ClassLoader
                           ├─ 安装 ContentProvider
                           ├─ 创建 Application
                           └─ Application.onCreate
```

只有 `bindApplication` 结束后，系统才会继续调度目标 Activity 的 launch。

### 6.1 App 侧能看到什么

常见表现：

- 点击图标后白屏很久。
- `MainActivity.onCreate()` 日志很晚才出现。
- 关闭某个三方 SDK 后冷启动明显变快。
- 添加一个 ContentProvider 后，即使没主动调用它，冷启动也变慢。

### 6.2 为什么 Provider 比 Application 还早

安装在 manifest 里的 ContentProvider 通常会在 `Application.onCreate()` 之前创建。

这就是为什么很多 SDK 喜欢用 ContentProvider 做自动初始化，也解释了为什么它会拖慢冷启动：你没写调用代码，但 Provider 已经在进程绑定阶段被系统创建了。

### 6.3 优化方向

- Application 里只做必要初始化。
- 三方 SDK 尽量延迟初始化。
- 能懒加载就不要在启动阶段加载。
- ContentProvider 自动初始化要谨慎。
- 用 Perfetto 看 `bindApplication` 阶段，而不是只看 Activity 生命周期日志。

---

## 7. 现象四：为什么后台 Service 起不来

Android 8.0 之后，后台直接 `startService()` 受到了严格限制。

App 侧常见代码：

```java
context.startService(new Intent(context, UploadService.class));
```

如果 App 在后台，可能会看到：

```text
java.lang.IllegalStateException: Not allowed to start service Intent ... app is in background
```

背后是 AMS 的 `ActiveServices` 在判断：调用方当前是否允许启动后台 Service。

### 7.1 为什么系统要限制后台 Service

因为后台 Service 如果不受控，会造成：

- 后台常驻进程变多。
- 电量消耗增加。
- 内存长期被占用。
- 用户不知道谁在后台运行。
- 系统低内存回收策略失效。

所以系统逐步把后台执行收敛到更明确的机制：前台服务、JobScheduler、WorkManager、Alarm、绑定服务等。

### 7.2 AMS 如何启动 Service

```text
ContextImpl.startService()
  └─ ActivityManager.getService().startService(...)
       └─ ActivityManagerService
            └─ ActiveServices.startServiceLocked()
                 ├─ 解析 ServiceInfo
                 ├─ 检查 exported / permission
                 ├─ 检查后台启动限制
                 ├─ 创建 ServiceRecord
                 ├─ 必要时启动进程
                 └─ scheduleCreateService / scheduleServiceArgs
```

应用进程收到后，才会真正执行：

```text
Service.onCreate()
Service.onStartCommand()
```

### 7.3 App 侧该怎么选

| 需求 | 推荐机制 |
|---|---|
| 用户可感知、需要立即执行 | 前台服务 |
| 可延迟、需要满足网络/充电等条件 | WorkManager / JobScheduler |
| 和前台页面强绑定 | bindService |
| 精准时间触发 | AlarmManager，注意省电限制 |
| 短小后台任务 | 协程/线程池 + 生命周期约束，避免常驻 Service |

---

## 8. 现象五：为什么前台服务还会启动失败

很多人以为把 `startService()` 改成 `startForegroundService()` 就万事大吉，其实不是。

```java
context.startForegroundService(intent);
```

这只是告诉系统：我要启动一个即将变成前台服务的 Service。系统仍然会要求你在规定时间内调用：

```java
startForeground(notificationId, notification);
```

否则 AMS 会认为你“承诺变前台但没有兑现”，触发异常或杀进程。

### 8.1 常见失败场景

- `startForegroundService()` 后 Service 初始化太慢，没有及时 `startForeground()`。
- Android 12+ 后台场景启动前台服务被限制，抛 `ForegroundServiceStartNotAllowedException`。
- Android 14+ 前台服务类型声明不完整。
- 缺少对应前台服务权限。
- 通知渠道没有正确创建。

### 8.2 Framework 背后的状态

AMS/`ActiveServices` 会记录 Service 是否处于 foreground、是否在等待调用 `startForeground()`、是否超时。

前台服务还会影响 OOM adj：一个真正处于前台服务状态的进程，通常比普通后台 Service 进程更不容易被杀。

但这也意味着：三方系统如果随便放宽前台服务限制，会直接影响功耗、内存和后台常驻策略。

---

## 9. 现象六：为什么广播收不到、收得慢、还会 ANR

App 侧广播问题非常常见，但背后原因不一样。

### 9.1 静态广播收不到

manifest 注册：

```xml
<receiver android:name=".BootReceiver" android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

可能收不到的原因：

- 没有权限，例如 `RECEIVE_BOOT_COMPLETED`。
- Android 8.0 后隐式广播静态注册限制。
- 应用处于 stopped 状态。
- 当前 user 没有解锁，应该监听 `LOCKED_BOOT_COMPLETED`。
- 设备厂商有开机自启或后台限制策略。
- 广播发给了另一个 user。

### 9.2 动态广播收不到

动态注册 receiver 依赖进程存活：

```java
registerReceiver(receiver, filter);
```

如果进程被杀，动态注册自然不存在。AMS 只会在内部维护当前活着进程注册的 `ReceiverList` 和 `BroadcastFilter`。

### 9.3 有序广播为什么慢

有序广播是一个接一个分发：

```text
Receiver A onReceive 完成
  └─ Receiver B onReceive
       └─ Receiver C onReceive
```

前一个 Receiver 卡住，后面的都会等。AMS 会为广播设置超时，超时后就可能触发 Broadcast ANR。

### 9.4 onReceive 为什么不能做重活

`onReceive()` 运行在应用主线程，AMS 还在等你回报 `finishReceiver`。如果你在里面做网络、数据库大查询、等待锁，就会拖住广播队列。

更好的方式：

- 小任务直接处理。
- 耗时任务交给 WorkManager/JobScheduler。
- 用户可感知的长期任务用前台服务。
- 不要在 `onReceive()` 里无限等待 Binder 或锁。

---

## 10. 现象七：为什么 App 明明没崩溃却被杀了

App 进程消失不等于 crash。常见情况有三类：

1. Java/native crash。
2. 用户或系统 force-stop。
3. 低内存时被 LMKD 杀掉。

第三类最容易被误解。

### 10.1 AMS 眼里的进程重要性

AMS 不会把所有 App 进程看成一样重要。它会根据组件状态计算 OOM adj：

| 进程状态 | App 侧例子 | 存活直觉 |
|---|---|---|
| top | 当前正在交互的 Activity | 最不容易被杀 |
| visible | 可见但不在焦点，例如弹窗后面的页面 | 不太容易被杀 |
| foreground service | 正在运行合法前台服务 | 不太容易被杀 |
| service | 后台 Service | 可能被杀 |
| cached | 回到后台、只保留 Activity 状态 | 容易被杀 |
| empty | 没有活动组件 | 最容易被杀 |

当内存紧张时，LMKD 会优先杀低优先级进程。

### 10.2 App 侧怎么判断是不是被 LMKD 杀

看 events log：

```bash
adb logcat -b events | grep -i "am_kill\|am_proc_died"
```

看系统日志：

```bash
adb logcat -b all | grep -i "lmkd\|lowmemorykiller\|Killing"
```

如果看到类似：

```text
ActivityManager: Killing 12345:com.example/u0a123 (adj  cached): remove task
lmkd: Kill 'com.example' ...
```

那就说明不是代码抛异常，而是系统进程管理策略生效了。

### 10.3 为什么切到后台后更容易死

Activity 进入后台后，进程可能变成 cached。cached 进程存在的意义是加速下次打开，不是保证常驻。系统内存紧张时，杀 cached 进程是正常行为。

如果业务真的需要后台执行，要选择合适机制，而不是指望进程永远活着。

---

## 11. 现象八：为什么 bindService 会影响两个进程的生死

`bindService()` 不只是拿一个接口，它还建立了两个进程之间的关系。

```java
bindService(intent, connection, Context.BIND_AUTO_CREATE);
```

AMS 内部会创建类似这样的记录：

- `ServiceRecord`：被绑定的 Service。
- `AppBindRecord`：某个 app 和某个 Service 的绑定关系。
- `ConnectionRecord`：一次具体的 `ServiceConnection`。

### 11.1 绑定关系会传播重要性

如果前台进程 A 绑定了后台进程 B 的 Service，B 的重要性可能会提高，因为 B 正在为前台用户可见的功能服务。

```text
前台 App A
  bindService
        │
        ▼
后台 Service 进程 B
  因绑定关系提高 OOM 优先级
```

这就是为什么某些后台进程在被前台应用绑定时不容易被杀。

### 11.2 常见问题

- 忘记 `unbindService()`，导致 Service 长时间存活。
- Service 进程死后，客户端收到 `onServiceDisconnected()`。
- 远端 Binder 死亡时，需要处理 `linkToDeath` 或重新绑定。
- 使用 `BIND_AUTO_CREATE` 会让 AMS 在没有 Service 实例时自动创建。

---

## 12. 现象九：为什么 ContentProvider 会拖慢启动

ContentProvider 不只是数据库封装，它也是进程启动链路的一部分。

### 12.1 Provider 作为启动入口

如果 App A 访问 App B 的 Provider，而 B 进程没起来，AMS 会先启动 B：

```text
App A query Provider
        │ Binder
        ▼
AMS / ContentProviderHelper
        │
        ├─ 查询 PMS 找到 ProviderInfo
        ├─ 如果 B 进程不存在，启动 B
        ├─ 等待 B publishContentProviders
        └─ 把 Provider Binder 返回给 A
```

所以 Provider 访问可能隐式拉起另一个进程。

### 12.2 Provider 自动初始化

App 自己进程启动时，manifest 里声明的 Provider 通常会在 `Application.onCreate()` 之前安装。

这会导致：

- 没有打开任何页面，Provider 已经执行 `onCreate()`。
- SDK 通过 Provider 做自动初始化。
- Provider 里做重活会直接拖慢冷启动。

### 12.3 Provider ANR

如果 Provider 发布超时或访问超时，也会触发 ANR。排查时不要只看 Activity 和 Service，Provider 也是冷启动和跨进程访问的重要卡点。

---

## 13. 现象十：为什么 ANR 经常不是卡在你以为的位置

ANR 的表象是“App 无响应”，但根因可能不在当前页面。

### 13.1 App 侧常见误判

| 表象 | 可能根因 |
|---|---|
| 点击按钮后 ANR | 主线程等 Binder，对端 system_server 或远端服务卡住 |
| 启动页 ANR | Application、Provider、前一个 Activity pause、主线程锁等待 |
| 广播 ANR | `onReceive()` 慢，或冷启动 Receiver 进程慢 |
| Service ANR | `onCreate()` / `onStartCommand()` 慢，或前台服务未及时 `startForeground()` |
| 系统大面积 ANR | system_server 卡锁、Binder 线程池耗尽、CPU 饥饿 |

### 13.2 AMS 怎么判断 ANR

AMS/ATMS 给应用下发某些任务后，会等待应用回报。例如：

- Activity pause 要等应用回报 pause 完成。
- Broadcast 要等 Receiver finish。
- Service 生命周期要在规定时间内完成。
- Provider 要在规定时间内 publish。

如果超时，AMS 会触发 ANR 流程：

```text
超时消息触发
  └─ AMS / ATMS 判断目标进程仍未响应
       └─ AnrHelper / AppErrors
            ├─ 收集 reason
            ├─ dump 关键进程线程栈
            ├─ 写 /data/anr
            └─ 弹框或后台处理
```

### 13.3 看 ANR 文件的顺序

1. 看 reason，确认是哪类 ANR。
2. 看主线程 stack。
3. 如果主线程在 Binder，找对端。
4. 如果主线程在等锁，找锁持有线程。
5. 看 Binder 线程是否都被占满。
6. 看 system_server 是否也在同一时间卡住。
7. 结合 Perfetto 看 CPU、I/O、调度延迟。

---

## 14. 从 dumpsys 和 logcat 看 AMS 在想什么

AMS 很复杂，但它把大量状态暴露在 dumpsys 和日志里。排查问题时，先把 App 现象翻译成系统状态。

### 14.1 Activity 相关

```bash
adb shell dumpsys activity activities
adb shell dumpsys activity starter
adb shell dumpsys activity top
```

你可以看：

- 当前 resumed Activity。
- task 栈结构。
- 最近一次启动失败原因。
- ActivityRecord 状态。
- 目标 Activity 是否在正确 display/user。

### 14.2 进程相关

```bash
adb shell dumpsys activity processes
adb shell dumpsys activity oom
adb shell ps -A | grep packageName
```

你可以看：

- 进程是否存在。
- pid、uid、进程名。
- adj、procState。
- 是否 cached、service、foreground。
- 是否有绑定关系、Provider 使用关系。

### 14.3 Service 相关

```bash
adb shell dumpsys activity services packageName
```

你可以看：

- ServiceRecord 是否存在。
- 是否 start requested。
- 是否 foreground。
- 绑定连接有哪些。
- 是否等待执行超时。

### 14.4 Broadcast 相关

```bash
adb shell dumpsys activity broadcasts
```

你可以看：

- 当前广播队列。
- pending / active broadcast。
- ordered broadcast 卡在哪个 receiver。
- 动态注册 receiver。

### 14.5 常用 logcat 关键词

```bash
adb logcat -b all | grep -i "ActivityTaskManager\|ActivityManager\|ActiveServices\|BroadcastQueue\|ANR\|lmkd"
```

常见关键词：

- `START`：Activity 启动。
- `Displayed`：页面显示耗时。
- `Killing`：AMS 杀进程。
- `am_proc_died`：进程死亡事件。
- `ANR in`：ANR 入口日志。
- `ForegroundServiceStartNotAllowedException`：前台服务限制。
- `Background start not allowed`：后台启动限制。

---

## 15. AMS 涉及的核心类：按 App 现象记

不要一开始就背所有类。按 App 现象记更自然。

### 15.1 Activity 启动相关

| 类 | 直观理解 |
|---|---|
| `ActivityTaskManagerService` | Activity/Task 调度总入口 |
| `ActivityStarter` | 处理启动参数、启动模式、flag、task 复用 |
| `ActivityTaskSupervisor` | 协调 pause、resume、realStartActivity、进程准备 |
| `ActivityRecord` | system_server 眼中的一个 Activity |
| `Task` | 一组 Activity 的任务栈 |
| `ClientLifecycleManager` | 把生命周期事务发给应用进程 |
| `ClientTransaction` | 一次生命周期调度包 |
| `LaunchActivityItem` | 告诉 App 创建 Activity |
| `ResumeActivityItem` | 告诉 App 执行 resume |

### 15.2 应用进程相关

| 类 | 直观理解 |
|---|---|
| `ActivityThread` | App 主线程大管家 |
| `ApplicationThread` | App 暴露给 AMS 的 Binder 回调入口 |
| `IApplicationThread` | system_server 调 App 的 Binder 接口 |
| `LoadedApk` | apk 在当前进程内的加载信息 |
| `Instrumentation` | 创建组件、调用生命周期的工具人 |
| `ActivityClientRecord` | App 进程眼中的一个 Activity 记录 |

### 15.3 进程和内存相关

| 类 | 直观理解 |
|---|---|
| `ActivityManagerService` | 进程、Service、Broadcast、Provider、ANR 总管 |
| `ProcessRecord` | system_server 眼中的一个 App 进程 |
| `ProcessList` | 管进程启动和进程列表 |
| `OomAdjuster` | 计算进程重要性和 OOM adj |
| `CachedAppOptimizer` | cached 进程冻结/压缩等优化 |
| `AppProfiler` | 统计 PSS、CPU、性能状态 |

### 15.4 Service / Broadcast / Provider 相关

| 类 | 直观理解 |
|---|---|
| `ActiveServices` | Service 调度中心 |
| `ServiceRecord` | system_server 眼中的一个 Service |
| `ConnectionRecord` | 一次 bindService 连接 |
| `BroadcastQueue` | 广播队列 |
| `BroadcastRecord` | 一次广播请求 |
| `ReceiverList` | 一个进程动态注册的 Receiver 列表 |
| `ContentProviderHelper` | Provider 启动、发布、连接管理 |
| `ContentProviderRecord` | system_server 眼中的一个 Provider |

### 15.5 异常处理相关

| 类 | 直观理解 |
|---|---|
| `AppErrors` | crash / ANR 处理 |
| `AnrHelper` | ANR 排队和辅助处理 |
| `ProcessErrorStateRecord` | 进程错误状态记录 |

---

## 16. 第三方系统常见修改：从 App 需求到 Framework 策略

第三方系统改 AMS/ATMS，通常不是为了“改源码好玩”，而是 App 侧需求推着走。

### 16.1 需求：允许某些 App 后台弹界面

App 侧现象：后台语音助手、导航、电话、倒车应用需要拉起 Activity。

Framework 修改点：

- 后台 Activity 启动限制。
- `ActivityStarter` 中的启动拦截逻辑。
- 特定 uid/package/component 白名单。
- 多 display、多 user 下的启动目标选择。

建议：白名单必须收窄，最好基于签名权限、系统 uid、角色或明确组件，不要简单按包名全放开。

### 16.2 需求：关键 App 常驻不被杀

App 侧现象：车控、导航、媒体、语音进程被杀后影响业务。

Framework 修改点：

- OOM adj 计算。
- persistent 应用策略。
- LMKD 保护名单。
- 进程死亡后拉起策略。

风险：保活过多会造成内存压力，反过来让前台应用更卡。保活不是解决内存泄漏和 ANR 的办法。

### 16.3 需求：开机自动启动业务应用

App 侧现象：设备开机后必须自动进入业务界面或启动后台服务。

Framework 修改点：

- `BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED` 广播策略。
- 静态广播限制。
- 用户解锁前后的启动流程。
- 开机启动队列和延迟策略。

建议：区分“开机必须立刻运行”和“用户解锁后再运行”。开机同时拉太多应用会导致系统启动慢、广播 ANR 和内存抖动。

### 16.4 需求：放宽前台服务限制

App 侧现象：后台采集、定位、上传、设备连接任务需要启动前台服务。

Framework 修改点：

- 前台服务启动限制。
- 前台服务类型检查。
- 权限和 AppOps。
- 通知展示策略。

风险：这类修改很容易影响功耗和 CTS/GTS。最好只针对系统签名应用或明确业务场景放行，并保留日志。

### 16.5 需求：指定 App 启动到特定屏幕

App 侧现象：车载中控、仪表、后排屏需要不同 Activity 显示在不同 display。

Framework 修改点：

- `ActivityOptions.setLaunchDisplayId()`。
- ATMS display/task 选择。
- display 权限和白名单。
- Launcher 与系统 task 策略一致性。

风险：displayId、userId、Activity token 不一致，会出现黑屏、启动失败、返回栈错乱。

### 16.6 修改 AMS/ATMS 的底线

- 不要“哪里报错就在哪里 return true”。
- 不要全局放宽安全限制。
- 不要只看单 App 成功，要看多用户、多屏、低内存、锁屏、开机、CTS/GTS。
- 每个例外都要能在日志里解释：谁、为什么、在哪个场景被放行。
- 尽量把策略集中到统一入口，不要散落在多个分支。

---

## 17. 读源码的推荐路线

从 App 现象出发读源码，会比从 AMS 第一行开始读更有效。

### 17.1 想搞懂 Activity 启动

```text
Activity.startActivity
Activity.startActivityForResult
Instrumentation.execStartActivity
ActivityTaskManagerService.startActivity
ActivityStarter.execute
ActivityTaskSupervisor.realStartActivityLocked
ClientLifecycleManager.scheduleTransaction
ActivityThread.handleLaunchActivity
```

### 17.2 想搞懂冷启动和 Application

```text
ProcessList.startProcessLocked
ZygoteProcess.start
ActivityThread.main
ActivityThread.attach
ActivityManagerService.attachApplication
IApplicationThread.bindApplication
ActivityThread.handleBindApplication
LoadedApk.makeApplication
Application.onCreate
```

### 17.3 想搞懂 Service 起不来

```text
ContextImpl.startService
ActivityManagerService.startService
ActiveServices.startServiceLocked
ActiveServices.bringUpServiceLocked
ActiveServices.realStartServiceLocked
ActivityThread.handleCreateService
Service.onCreate / onStartCommand
```

### 17.4 想搞懂广播收不到

```text
ContextImpl.sendBroadcast
ActivityManagerService.broadcastIntentWithFeature
ActivityManagerService.broadcastIntentLocked
BroadcastQueue / BroadcastProcessQueue
IApplicationThread.scheduleReceiver
ActivityThread.handleReceiver
BroadcastReceiver.onReceive
```

### 17.5 想搞懂进程为什么被杀

```text
ActivityManagerService.updateOomAdjLocked
OomAdjuster.updateOomAdjLocked
ProcessRecord / ProcessStateRecord
ProcessList.setOomAdj
lmkd
```

### 17.6 想搞懂 ANR

```text
具体超时入口：Input / Broadcast / Service / Provider
ActivityManagerService.appNotResponding
AnrHelper
AppErrors
ProcessErrorStateRecord
/data/anr/anr_*
```

---

## 18. 一图总结

```text
App 侧看到的现象
  ├─ startActivity 慢 / 失败
  ├─ Service 起不来
  ├─ 广播收不到
  ├─ App 被杀
  ├─ ANR
  └─ 冷启动慢
        │
        ▼ Binder
system_server
  ┌─────────────────────────────┐
  │ AMS / ATMS                  │
  │                             │
  │ 解析 Intent                 │
  │ 校验权限 / userId / exported│
  │ 选择 task / display         │
  │ 启动或复用进程              │
  │ 调度生命周期                │
  │ 分发 Service / Broadcast    │
  │ 计算 OOM adj                │
  │ 处理 ANR / crash            │
  └──────────────┬──────────────┘
                 │
                 ├─ PMS：包和组件信息
                 ├─ WMS：窗口和显示
                 ├─ Zygote：fork 新进程
                 ├─ LMKD：低内存 kill
                 └─ App 进程：IApplicationThread 回调
```

---

## 小结

- App 里的四大组件 API，本质上是在向 system_server 发调度请求。
- AMS/ATMS 决定组件能不能启动、在哪个进程启动、进入哪个 task、生命周期什么时候执行。
- App 生命周期最终在应用主线程执行，system_server 通过 `IApplicationThread` 回调应用进程。
- 冷启动慢经常发生在 `Activity.onCreate()` 之前，尤其是进程 fork、`bindApplication`、Provider 安装、`Application.onCreate()`。
- Service、Broadcast、Provider 都可能隐式拉起进程，也都可能导致 ANR。
- App 被杀不一定是 crash，很多时候是 AMS/OomAdjuster/LMKD 的进程管理结果。
- 排查 AMS 相关问题时，要把 App 侧现象翻译成 Framework 状态：组件解析、权限、userId、后台限制、进程状态、OOM adj、生命周期回报。
- 第三方系统定制 AMS/ATMS 时，最重要的是边界清晰、日志可观察、不要为了单个 App 破坏系统级安全和资源调度。

如果只记一个核心模型：

> App 发请求，AMS/ATMS 做决策，Zygote 准备进程，ActivityThread 执行生命周期，OomAdjuster/LMKD 决定后台进程能活多久。