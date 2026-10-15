# 后台任务机制详解：从 Alarm 到 JobScheduler

> 后台任务是 Android Framework 里最容易"看起来没问题，但就是不执行"的领域。它和 Power/Doze、AMS 前后台状态、Alarm、Job、前台服务、网络和电量策略强相关。
>
> **本篇分工（避免重复）**：Doze/电源状态机的机制本身在 `15_Power` 讲，本篇只讲"被限制时调度器怎么响应"；App 侧 WorkManager 的用法（API 调用、Worker 写法）在 `androidApp/09` 讲，本篇只讲 WorkManager 的**落点**与 JobScheduler/AlarmManager 内核的关系；前台服务机制在 `07_Service` 讲，本篇只在点出"什么时候该用前台服务而不是后台任务"。

---

## 目录

1. [开场：后台任务回答的三个问题](#1-开场后台任务回答的三个问题)
2. [限制与调度全景图](#2-限制与调度全景图)
3. [AlarmManager：四种 set 与三种时间源](#3-alarmmanager四种-set-与三种时间源)
4. [AlarmManagerService 内核](#4-alarmmanagerservice-内核)
5. [JobScheduler：JobInfo 约束全集](#5-jobschedulerjobinfo-约束全集)
6. [JobScheduler 调度内核：StateController 与并发](#6-jobscheduler-调度内核statecontroller-与并发)
7. [App Standby 与 Bucket](#7-app-standby-与-bucket)
8. [三种后台限制的交集表](#8-三种后台限制的交集表)
9. [WorkManager 的落点](#9-workmanager-的落点)
10. [WorkSource 与责任归属](#10-worksource-与责任归属)
11. [车机专章：GarageMode 与后台任务](#11-车机专章garagemode-与后台任务)
12. [平台定制：config_ 参数与白名单](#12-平台定制config_-参数与白名单)
13. [常见问题排查表](#13-常见问题排查表)
14. [读源码路线](#14-读源码路线)
15. [源码路径速查表与一图总结](#15-源码路径速查表与一图总结)

---

## 1. 开场：后台任务回答的三个问题

后台任务不是一个单独服务，而是一组机制：

- `AlarmManager`：按**时间**触发（到某个时刻叫醒系统）。
- `JobScheduler`：按**条件**调度（充电、空闲、联网才跑）。
- `WorkManager`：Jetpack 封装，底层常用 JobScheduler（见第 9 章）。
- 前台服务：用户**可感知**的持续任务（机制在 `07_Service`）。
- Broadcast：系统事件触发（机制在 `08_Broadcast`）。
- SyncAdapter / ContentObserver：数据同步与变化触发。

一句话直觉：

> Android 越新，越不鼓励 App 在后台随时运行，而是让系统根据电量、网络、空闲状态**统一调度**。App 提的不再是"立刻跑"，而是"满足这些条件时帮我跑"。

本篇围绕三个问题展开，这三个问题也是你以后排查任何"后台不执行"问题的思考框架：

1. **什么时候算"后台"？**
   应用不在前台、没有可见 `Activity`、没有前台服务、没有用户正在交互的通知时，就算后台。AMS 的 `ProcessState`（前台/可见/服务/后台/缓存）直接决定调度器给你的待遇。
2. **系统靠什么省电？**
   三层省电墙：①唤醒合并（alarm batch、job 合并）②限制窗口（Doze、App Standby bucket、battery saver）③直接拦（后台启动 Service 限制、后台网络限制）。具体机制在 `15_Power`，本篇讲它们如何"挡住"你的任务。
3. **任务什么时候能跑？**
   答："系统认为当前条件满足时"。对 alarm 是"到点且没被合并/限制"；对 job 是"所有约束都 satisfied"。你能做的不是强行跑，而是**正确声明约束**，让系统在合适的窗口里帮你跑。

### 版本差异速查（先存进脑子）

本篇涉及的关键版本拐点，排查时先对号入座：

| 版本 / API | 引入的变化 | 对本篇的影响 |
|---|---|---|
| Android 6.0 / 23 | Doze 引入 | alarm/job 进入被限制时代 |
| Android 8.0 / 26 | 后台 Service 限制 | 裸后台 Service 不能随便起（见 8 章） |
| Android 9.0 / 28 | App Standby Bucket（4 档） | 用频驱动的限流生效（见 7 章） |
| Android 11 / 30 | `JobConcurrencyManager` 取代 `JobServiceContextController`；`QuotaController` 按 bucket 限时长 | 并发模型改写（见 6 章） |
| Android 12 / 31 | 精确 alarm 特殊权限（`SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM`）；`RESTRICTED` 成为第 5 档 bucket | exact alarm 被拒的主因（见 4.3 / 7.1） |

`「Android 视角」` 常量多为"量级/以本版本源码为准"：超时约 10 分钟、allowWhileIdle 约每 15 分钟几次、各 bucket 配额天数等，不同版本与厂商可能改动，正式下结论前请 grep 本版本源码确认。

### 怎么读这篇

- 想立刻上手排查：`直接跳第 13 章排查表`。
- 想理解 alarm：第 3、4 章。
- 想理解 job：第 5、6 章。
- 想理解"为什么我的任务被卡住"：第 7、8 章（bucket 与限制交集）。
- 想理解上层 WorkManager：第 9 章。
- 车机/整机定制：第 11、12 章。
- 想顺着源码读：第 14、15 章。

---

## 2. 限制与调度全景图

把后台任务想象成"快递柜取件"：**你（App）把包裹（任务）投进系统（调度器），系统决定什么时候开门让你取（执行）**。开门的条件是"现在是取件时间段、且你这个用户等级有权限"。

```
                          ┌──────────────────────────────────────────────┐
   App 提请求             │            系统调度器（system_server）          │
   ┌──────────┐          │                                                │
   │ Alarm /  │          │  限制层1: 合并   alarm batch / job 合并          │
   │  Job /   │─────────▶│  限制层2: 窗口   Doze / Standby bucket /        │
   │  WM      │          │                  Battery Saver / 后台服务限制    │
   └──────────┘          │  限制层3: 拦     Android8 后台Service / 网络限制 │
                         │                                                │
                         │  判定: 到点? 约束满足? 配额够? 优先级够?          │
                         └───────────────────────┬────────────────────────┘
                                                 │ 满足
                                                 ▼
                                  进程拉起 / 调 JobService.onStartJob
                                                 │
                                                 ▼
                                          batterystats 归因（WorkSource）
```

**两条主干**：

| 主干 | 触发语义 | 是否可靠 | 典型用途 |
|---|---|---|---|
| AlarmManager | 时间驱动（到点） | 否（会被合并/延迟/丢） | 闹钟、定时提醒、精确时刻事件 |
| JobScheduler | 条件驱动（约束满足） | 延迟但更省电 | 上传、同步、清理、预取 |

记忆链：**"Alarm 问'几点'，Job 问'行不行'；系统先把'几点'合并、把'行不行'卡窗，都满足才放行。"**

---

## 3. AlarmManager：四种 set 与三种时间源

`AlarmManager` 是 App 能拿到的最"硬"的定时能力——它能在设备休眠时唤醒 CPU。正因为成本最高，系统对它的限制也最严。**先选时间源，再选 set 类型**，两者是正交的两个维度。

### 3.1 三种时间源（先决定"基准时钟"）

| 类型常量 | 基准 | wake？ | 何时用 |
|---|---|---|---|
| `ELAPSED_REALTIME` | `SystemClock.elapsedRealtime()` 开机至今，**单调** | 否 | 相对时长（"30 分钟后"），不关心墙钟 |
| `ELAPSED_REALTIME_WAKEUP` | 同上 | **是** | 需要在休眠中也到点唤醒 |
| `RTC` | `System.currentTimeMillis()` 墙钟，**可被用户/网络改** | 否 | 日历时刻（"今天 14:00"） |
| `RTC_WAKEUP` | 同上 | **是** | 日历时刻且必须唤醒 |

`「Android 视角」`：**优先用 `ELAPSED_REALTIME*`。** 因为墙钟（`RTC`）会被 NITZ/NTP/用户手动改时间，导致 alarm 提前或延后甚至不触发；而 `elapsedRealtime` 单调递增，适合"N 秒后/分钟后"这类相对语义。只有"每天 8:00 提醒"这种**墙钟语义**才用 `RTC`。

### 3.2 四种 set（再决定"精度与优先级"）

| 方法 | 是否精确 | Doze 中能跑? | 配额/限制 | 典型场景 |
|---|---|---|---|---|
| `set(type, t, pi)` | 否（会被 batch 合并到其他 alarm） | 否 | 无 | 不重要的周期提醒 |
| `setWindow(type, start, len, pi)` | 窗口内精确 | 否 | 无 | "10±5 分钟内"提醒 |
| `setExact(type, t, pi)` | **精确** | 否 | 需 `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM`（Android 12+） | 精确时刻、日历事件 |
| `setAndAllowWhileIdle(type, t, pi)` | 否 | **能**（但受限频） | 限频（每 15min 几次） | 息屏后仍需的模糊提醒 |
| `setExactAndAllowWhileIdle(type, t, pi)` | **精确** | **能**（但受限频） | 需精确权限 + 限频 | 闹钟类关键事件（仍有频率上限） |
| `setAlarmClock(info, pi)` | 精确且**最高优先级** | 能（Doze 也会让路） | 无（用户可见） | 系统闹钟 App |

`「Android 视角」` 关键陷阱：

- **`set` 不等于"到点就响"**。系统会把多个 alarm 按"能合并到最早那个时刻一起响"的规则塞进 `Batch`（见第 4 章），所以你设的 8:00 可能跟别人的 7:58 一起在 7:58 触发。
- **`setExact` 在 Doze 深睡时照样被推迟**（除非用 `...AndAllowWhileIdle` 或 `AlarmClock`）。"Exact"只保证**不被合并**，不保证**不被 Doze 挡**。
- **`...AndAllowWhileIdle` 有频率硬上限**（Android 在 `AlarmManagerService` 内维护 `allowWhileIdle` 的近期派发计数，约每 15 分钟只允许少数几次跨 Doze 派发）。别指望用它做高频后台轮询。

### 3.3 一个正确选型的小例子

```java
// 场景：用户设了"每天早上 7:30 吃药提醒"，必须响（闹钟类）
AlarmManager am = ctx.getSystemService(AlarmManager.class);
Intent i = new Intent(ctx, RemindReceiver.class);
PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, i, FLAG_IMMUTABLE);

// 墙钟语义 + 必须唤醒 + 用户可见 → AlarmClock
// 注意：setAlarmClock 无需 SCHEDULE_EXACT_ALARM 权限（它本来就是闹钟）
AlarmManager.AlarmClockInfo info =
        new AlarmManager.AlarmClockInfo(next7_30(), pi);
am.setAlarmClock(info, pi);   // Doze 会让路，状态栏显示闹钟图标

// 反例：用 setExactAndAllowWhileIdle 做"每 5 分钟后台同步"
// → 一方面需要精确权限，另一方面频率上限会把它掐掉，应该改用 JobScheduler
```

### 3.4 alarm 的"不可靠性声明"

`「Android 视角」` 理解下面三条，能解释 90% 的"alarm 不准"：

1. **休眠合并**：非 `WAKEUP` 的 alarm 在设备睡着时不触发，要等下次唤醒才补；`WAKEUP` 的会被合并进 `Batch` 减少唤醒次数。
2. **内存紧张会丢**？严格说 alarm 本身由 `system_server` 持有、不会因 App 进程死而丢；但**App 进程被杀后其 `PendingIntent` 指向的 Broadcast/Service 若依赖 App 存活处理，就会"响而无果"**。另外 `set` 的非精确语义会让触发时刻漂移。
3. **Doze 推迟**：非 `allowWhileIdle` 的 alarm 在 Doze 主维护窗口之外被挂起（见 `15_Power`）。

---

## 4. AlarmManagerService 内核

`AlarmManager` 的 Binder 实现在 `system_server` 的 `AlarmManagerService`（以下简称 AMSvc，注意和 `ActivityManagerService` 缩写撞名，本文称 AlarmMSvc）。它的核心职责：**收集所有 App 的 alarm → 合并成 Batch → 等内核 alarm 驱动唤醒 → 派发**。

### 4.1 set 的调用链落点

```text
App: AlarmManager.setExactAndAllowWhileIdle(type, t, pi)
  └─ AlarmManager(代理, android.app)                        [client 进程]
       └─ IAlarmManager.set()  (Binder)
            └─ AlarmManagerService.set(...)                 [system_server]
                 └─ setImpl(...) → setImplLocked(...)
                      ├─ 校验: canScheduleExactAlarms()?     // Android 12+
                      ├─ 限频: allowWhileIdle 配额检查
                      ├─ 构造 Alarm 对象 (uid, type, when, op=PendingIntent)
                      ├─ 放进 mAlarmBatches (按 batching 规则合并)
                      └─ rescheduleKernelAlarmsLocked()
                           └─ nativeSet() → 内核 alarm 驱动 (Androids alarmtimer)
```

`「Android 视角」` `AlarmThread`（继承自 `Thread`，在 AlarmMSvc 内）是真正"等铃"的线程：

```java
// 简化自 AlarmManagerService.AlarmThread.run()
public void run() {
    while (true) {
        // 阻塞等待内核 alarm 驱动 fd 可读（到点）
        int result = nativeWaitForAlarm(mNativeData);
        synchronized (mLock) {
            // 到点后，把到时间的 Batch 里的 alarm 派发出去
            deliverAlarmsLocked(triggerList, nowELAPSED);
        }
    }
}
```

### 4.2 批处理（Batch）合并的依据：唤醒成本

AlarmMSvc 维护 `ArrayList<Batch> mAlarmBatches`，按 `Batch.start` 排序。**合并规则**：若新 alarm 的 `when`（最晚可触发时刻）≥ 某个 Batch 的 `start`，且新 alarm 的 `start` ≤ 该 Batch 的 `end`，则两 alarm 可同批——它们都会在 `Batch.start`（即最早那个时刻）触发。

直觉：**把多个"不太挑时刻"的 alarm 挤到同一时刻一起响，设备只需唤醒一次 CPU**。这就是省电来源，也是 `set` 不精确的根源。

```text
App A: set(ELAPSED_WAKEUP, 1000)     想 1000 响
App B: set(ELAPSED_WAKEUP, 980)      想 980 响
→ 合并成 Batch.start=980，两者都在 980 响（A 被提前了 20ms，用户无所谓）
```

### 4.3 canScheduleExactAlarms 与精确闹钟权限（Android 12+）

Android 12（API 31）起，精确 alarm 不再是"随便调"，引入了**特殊权限**模型：

| 权限 | 级别 | 谁能用 | 备注 |
|---|---|---|---|
| `SCHEDULE_EXACT_ALARM` | 普通权限（runtime，可被用户撤销） | 一般 App 申请 | 用 `setExact`/`setExactAndAllowWhileIdle` 需要它；用户可在设置里关 |
| `USE_EXACT_ALARM` | 普通权限但**仅特定用例**（闹钟/日历类，manifest 声明即授予） | 时钟/日历 App | 不可被用户撤销，但 Play 商店用途审查严 |
| 无（仅 `setAlarmClock`） | — | 闹钟 App | `AlarmClockInfo` 自带用户可见性，不需上面任何权限 |

`AlarmManager.canScheduleExactAlarms()` 返回 `false` 时，调用 `setExact*` 会抛 `SecurityException`（或新版本直接不生效并返回失败）。

```java
// Android 12+ 正确姿势
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        && !am.canScheduleExactAlarms()) {
    // 引导用户去设置授予，或降级为 setWindow
    Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
    i.setData(Uri.parse("package:" + getPackageName()));
    startActivity(i);
}
```

`「Android 视角」` 车机/整机的坑：系统/预装应用若要稳定用精确 alarm，应在权限白名单或 `USE_EXACT_ALARM` 路线，否则 OTA 后用户误关权限会导致定时任务整体失效。

### 4.4 dumpsys alarm 逐段读法

```bash
adb shell dumpsys alarm
```

输出按段（节选关键）：

| 段落 | 看什么 | 排查用途 |
|---|---|---|
| `Top Alarms:` | 按 wakeups 排序的 alarm 列表，含 `uid`/`pkg`/`type` | 找"谁在疯狂唤醒"，电池归因 |
| `mAlarmBatches` | 当前合并批次与每个 alarm 的 `when` | 看你的 alarm 被合并到哪 |
| `Pending alarm batches` | 待触发批次 | 确认 alarm 是否真的被登记 |
| `allowWhileIdle` 相关 | 近期 `allowWhileIdle` 派发时间 | 判断是否触频率上限 |
| `Recent Delivery History` | 最近派发记录（时间+包） | 验证"到底响没响、几点响" |
| `mBroadcastRefCount` | 正在派发的 Broadcast 计数 | 若长期 >0 说明有 receiver 卡住 |

```bash
# 只看某个包
adb shell dumpsys alarm | grep -A20 "your.package"
# 强制触发一次（调试用，不等到点）
adb shell am broadcast ...   # 配合自定义 receiver 验证
```

---

## 5. JobScheduler：JobInfo 约束全集

`JobScheduler` 是"条件驱动"的调度器：你声明约束，系统在满足时执行，不满足就一直挂着。**不要用它做"立刻执行"的任务**（那是前台服务/`startForegroundService` 的事）。

`JobSchedulerImpl`（client 侧 Binder 代理，包 `android.app`）→ `JobSchedulerService`（system_server）→ `JobStatus`（内部表示）。

### 5.1 JobInfo.Builder 约束全集表

| 约束方法 | 语义 | 常见陷阱 |
|---|---|---|
| `setRequiredNetworkType(int)` | `NETWORK_TYPE_NONE/ANY/UNMETERED/NOT_ROAMING/METERED` | `UNMETERED` 在车机/流量场景几乎永远不满足，慎用 |
| `setRequiresCharging(boolean)` | 充电中 | 与 `setRequiresDeviceIdle` 组合是"维护窗口"经典用法（见 11 章） |
| `setRequiresDeviceIdle(boolean)` | 设备空闲（接近 Doze 态） | 用户一直在动就永远不跑；Android 7+ 更严格 |
| `setRequiresBatteryNotLow(boolean)` | 非低电 | 低电时挂起 |
| `setRequiresStorageNotLow(boolean)` | 存储非低 | 存储告警时挂起 |
| `setMinimumLatency(long)` | 最短延迟（不能和 `setPeriodic` 同用） | 只是下限，不保证到点就跑 |
| `setOverrideDeadline(long)` | 最迟此时**强制跑**（无视其他约束） | 设了它等于"软约束"，可能在不理想条件下跑 |
| `setPeriodic(long)` / `setPeriodic(long, flex)` | 周期任务 | API < 24 不支持 `flex`；周期任务忽略 latency/deadline |
| `setPersisted(boolean)` | 重启后保留 | **必须**有 `RECEIVE_BOOT_COMPLETED` 权限，否则抛异常 |
| `setBackoffCriteria(long, int)` | 失败重试退避（`LINEAR`/`EXPONENTIAL`），初始值 | 默认指数退避 30s 起；重试上限由系统策略 |
| `setTriggerContentUri(...)` + `setTriggerContentMaxDelay` | 监听 ContentProvider 变化触发 | 需要对应 URI 权限；变化频繁会一直触发 |
| `setEstimatedNetworkBytes(long,long)` | 估计传输量 | 帮助连通性控制器选网络（新版本） |
| `setImportantWhileForeground(boolean)` | 前台期间提高优先级 | 仅在前台有效 |

### 5.2 一个可靠的 Job 写法

```java
ComponentName svc = new ComponentName(ctx, MyJobService.class);
JobInfo job = new JobInfo.Builder(JOB_ID, svc)
        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
        .setRequiresCharging(true)
        .setOverrideDeadline(TimeUnit.HOURS.toMillis(6))   // 最多 6h 后强制跑
        .setBackoffCriteria(TimeUnit.MINUTES.toMillis(2),
                            JobInfo.BACKOFF_POLICY_EXPONENTIAL)
        .setPersisted(true)                                // 需 RECEIVE_BOOT_COMPLETED
        .build();

JobScheduler js = ctx.getSystemService(JobScheduler.class);
int res = js.schedule(job);   // 返回 RESULT_SUCCESS / RESULT_FAILURE
```

`「Android 视角」` `setOverrideDeadline` 是你对抗"约束永远不满足"的兜底：即便没连上 Wi-Fi，6 小时后也强制跑一次，避免任务石沉大海。

### 5.3 JobSchedulerImpl → JobStatus 的落库与持久化

```text
App: JobScheduler.schedule(jobInfo)
  └─ JobSchedulerImpl.schedule()                     [client]
       └─ IJobScheduler.schedule(jobInfo)  (Binder)
            └─ JobSchedulerService.scheduleAsPackage()
                 ├─ 构造 JobStatus(JobInfo, uid, packageName, ...)
                 ├─ 若 setPersisted → JobStore.add(JobStatus)
                 │     └─ 写 /data/system/job/jobs.xml   (JobStore 持久化)
                 ├─ 通知各 StateController 开始跟踪该 job
                 └─ maybeRunJobsLocked()  // 立刻评估一次能不能跑
```

`JobStore`（包 `com.android.server.job`）把 `persisted` 的 job 序列化进 `jobs.xml`；开机时 `JobSchedulerService` 通过 `JobStore.readJobMapFromDisk()` 恢复。**这就是"重启后任务还在"的来源**——前提是 `setPersisted(true)` 且声明 `RECEIVE_BOOT_COMPLETED`。

### 5.4 dumpsys jobscheduler 读法

```bash
adb shell dumpsys jobscheduler
adb shell dumpsys jobscheduler | grep -A30 "your.package"
# 强制立即跑（忽略约束，调试用）
adb shell cmd jobscheduler run -f your.package JOB_ID
# 查看某个 uid 的 job
adb shell cmd jobscheduler get-job-state your.package JOB_ID
```

| 段落 | 看什么 |
|---|---|
| `Started jobs:` | 正在执行的 job、已运行时长（判断是否超时） |
| `Pending jobs:` | 等待中 job，含每个约束的 `satisfied`/`unsatisfied` 标记 |
| `Job #id uid=...` | 单个 job 的约束明细、`standby bucket`、`effective priority` |
| `mReadyToRock` / `mReportedActive` | 调度器整体是否在工作 |
| `Quota` 相关（Android 11+） | 该 bucket 的执行时长配额余量 |

`「Android 视角」` 排查 job 不跑，第一眼就盯 `Pending jobs` 里那条 job 的约束列表：哪个 `unsatisfied` 一目了然（比如 `connectivity: UNMETERED=false` 说明没连 Wi-Fi）。

---

## 6. JobScheduler 调度内核：StateController 与并发

`JobSchedulerService` 不是"到点就跑"，而是一组**状态控制器（StateController）**各自跟踪全局状态，再对所有 job 做"约束 AND 判定"，最后由**并发管理器**按优先级挑能跑的 job 拉起。

### 6.1 StateController 体系（"一类控制器"）

这些控制器都在 `com/android/server/job/controllers/` 下，统一继承 `StateController`：

| 控制器 | 跟踪的状态 | 对应 JobInfo 约束 |
|---|---|---|
| `ConnectivityController` | 网络类型/计费/漫游 | `setRequiredNetworkType` |
| `IdleController` | 设备是否空闲（屏幕灭+无交互） | `setRequiresDeviceIdle` |
| `TimeController` | 时间窗口（deadline/latency） | `setMinimumLatency`/`setOverrideDeadline` |
| `BatteryController` | 充电/电量 | `setRequiresCharging`/`setRequiresBatteryNotLow` |
| `DeviceIdleJobsController` | Doze 状态 | 与 idle 协同，Doze 中放行 idle job |
| `StorageController` | 存储余量 | `setRequiresStorageNotLow` |
| `ContentObserverController` | ContentProvider 变化 | `setTriggerContentUri` |
| `QuotaController`（Android 11+） | 每 bucket 执行时长配额 | 限制 RARE/RESTRICTED 的运行时长 |
| `BackgroundJobsController` | 后台限制状态 | 受 `OPERATION_` 限制 |

每个控制器对跟踪的 job 调用 `JobStatus.setXXXConstraintSatisfied(bool)`，所有约束位都置位 → job 进入"ready"集合。

### 6.2 JobConcurrencyManager：并发上限与优先级排序

`「Android 视角」` 早期版本用 `JobServiceContextController`，**Android 11（API 30）起改名为 `JobConcurrencyManager`**。它维护一组 `JobServiceContext`（执行槽，数量上限来自 config 参数集合，以本版本源码为准），并做：

1. **并发上限**：系统/前台/后台 job 各有上限（一组 `mMax*` 常量），总数封顶。
2. **优先级排序**：`JobStatus.getEffectivePriority()` 综合 `uid 重要性`（前台 App > 后台）+ `standby bucket`（ACTIVE > RARE）+ `expedite` 标志。
3. **抢占**：高优先级 job 就绪时，可能顶掉低优先级正在跑的（按策略）。

```text
maybeRunJobsLocked()
  ├─ 收集所有 constraint-satisfied 的 job
  ├─ 按 effectivePriority 排序
  ├─ 遍历执行槽 JobServiceContext[]:
  │     ├─ 槽空闲 → 绑定 job, context.executeRunnableJob()
  │     └─ 槽占用但优先级低 → 依据策略可能 preempt
  └─ 超并发上限则排队
```

### 6.3 job 执行的进程拉起与超时

job 执行走 `JobService` 的 `onStartJob(JobParameters)`，**运行在 App 进程**。若 App 进程不在，系统先拉起进程（和 Broadcast/Service 拉起同源）。

`「Android 视角」` **超时是量级概念，以本版本为准**：标准 job 必须在约 **10 分钟**量级内调用 `jobFinished()`，否则系统视为执行失败——强行停止该 job、计入失败、按 `setBackoffCriteria` 退避重试。超过超时还会导致该 `JobServiceContext` 槽被回收，影响同 App 其他 job。

```java
public class MyJobService extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        // 同步做轻活；重活请丢到线程，做完调 jobFinished
        doWork();
        jobFinished(params, false);  // false=不需要重试
        return true;                 // true=活还没干完，自己管生命周期
    }
    @Override public boolean onStopJob(JobParameters p) {
        // 超时/约束失效时被调，返回 true 表示想重试
        return true;
    }
}
```

超时后果链：**被杀 → 标记失败 → 退避 → 下次更晚跑 → 频繁失败还可能耗尽 `QuotaController` 配额被进一步限流**。

---

## 7. App Standby 与 Bucket

`「Android 视角」` App Standby（Android 9 / API 28 引入）是"**用得越少，限制越多**"的机制：系统按用户使用频率把每个 App 分到不同 **bucket（桶）**，桶决定 job 执行频率上限、alarm 限制、后台网络窗口。

### 7.1 五档 bucket（按版本核对）

| Bucket | 引入版本 | 含义 | 待遇（量级） |
|---|---|---|---|
| `ACTIVE` | 9 (28) | 正在用（前台/刚交互/有前台服务） | 几乎无限制，job/alarm 随时 |
| `WORKING_SET` | 9 (28) | 经常用 | 较宽松，有运行配额 |
| `FREQUENT` | 9 (28) | 偶尔用 | 受限，运行窗口拉长 |
| `RARE` | 9 (28) | 很少用 | 严格，后台网络每天仅几个窗口，job 很少跑 |
| `RESTRICTED` | **12 (31)** | 长期不用或用户手动限制 | 最严，几乎只在前台/充电时跑，禁止后台网络 |

`「Android 视角」` **`RESTRICTED` 是 Android 12 才加的第五档**，不要照抄旧资料说"只有四档"。用户可在设置里把任意 App 设为受限。

### 7.2 bucket 的判定信号与降级路径

系统根据"使用统计驱动"的信号判定：

- **升桶信号**：用户启动 App、点击其通知、有前台 `Activity`/`Service`、有 widget 在桌面、设备充电时使用等 → 临时升到 `ACTIVE`。
- **降级路径**：长时间无交互 → `ACTIVE → WORKING_SET → FREQUENT → RARE`（按天数衰减，具体阈值以本版本 `AppStandbyController` 为准）；用户手动或系统策略可一步打到 `RESTRICTED`。

`「Android 视角」` 注意：bucket 是**整机对 App 的全局评价**，和你"这次任务写了什么约束"无关。一个被降到 `RARE` 的 App，即便 job 约束全满足，也可能因为配额/网络窗口被卡很久——这就是"我明明连着 Wi-Fi 却不上传"的隐藏原因。

### 7.3 实验命令

```bash
# 看当前 bucket
adb shell am get-standby-bucket your.package
# 强制设 bucket（调试）
adb shell am set-standby-bucket your.package rare
adb shell am set-standby-bucket your.package active
# 观察降级效果
adb shell dumpsys usagestats | grep -i "your.package"
```

---

## 8. 三种后台限制的交集表

后台任务能否执行，是 **Doze × bucket × battery saver × 后台服务限制** 四层叠加的结果。下表是"我现在到底能不能跑"的合并判定（Doze 机制本身见 `15_Power`，这里只列它对调度的"挡法"）。

| 限制层 | 触发条件 | 对 Alarm 的影响 | 对 Job 的影响 | 绕过方式 |
|---|---|---|---|---|
| **Doze（浅/深）** | 息屏静止一段时间 | 非 `allowWhileIdle`/`AlarmClock` 被挂起 | idle job 可跑；非 idle job 推迟到维护窗口 | `setExactAndAllowWhileIdle`/`setAlarmClock` |
| **App Standby bucket** | 使用频率低 | RARE/RESTRICTED 的 alarm 限频 | job 执行配额被砍、网络窗口受限 | 升桶（用户交互）或 `RESTRICTED` 豁免 |
| **Battery Saver** | 用户开省电 | 限制后台 alarm 频率 | 限制后台 job、推迟非紧急 job | 加电池优化白名单 |
| **后台服务限制（Android 8+）** | App 在后台 | —（alarm 派发到 receiver 不受影响） | job 仍走 JobScheduler（不受此限） | 用前台服务/FGS 类型而非裸后台 Service |

**合并判定口诀**：

> 任务要跑，必须**同时满足**"alarm 到点且未被 Doze 挂起/未被 bucket 限频"**且**"job 所有约束 satisfied 且 bucket 配额有余 且 不在省电拦截名单"。任一层挡住 = 不跑。

`「Android 视角」` 车机场景里这层叠加更复杂：Doze 可能整体关闭（见 11 章），但 `RESTRICTED` 与 battery saver 仍可能生效，排查时四层要**逐一排除**。

---

## 9. WorkManager 的落点

`「Android 视角」` WorkManager 是 Jetpack 封装（App 侧用法见 `androidApp/09`），**本篇只讲它落到系统哪一层、为什么必须先懂 JobScheduler 和 AlarmManager**。一句话：**WorkManager 是"翻译层"，把你的 `WorkRequest` 翻译成系统调度原语。**

### 9.1 各 API 级别的 fallback 矩阵

| 运行平台 | 持久任务落点 | 即时任务（foreground）落点 |
|---|---|---|
| API ≥ 23 (6.0+) | `JobScheduler` | `JobScheduler`（或 Greedy 前台直跑） |
| API 22 (5.1) 及以下 | `AlarmManager`（`setExactAndAllowWhileIdle`）+ `BroadcastReceiver` | 同左 + `GreedyScheduler` |
| 所有版本前台时 | `GreedyScheduler`：App 在前台直接同步执行，不进系统调度 | — |
| 厂商 ROM 阉割 JobScheduler | 退化为 AlarmManager 路线（WM 内部 `Api24Impl`/`Api21Impl` 分支） | — |

WM 内部由一组 `Scheduler` 组成：`SystemJobScheduler`（JobScheduler）、`SystemAlarmScheduler`（AlarmManager）、`GreedyScheduler`（前台）。`WorkManagerImpl` 按版本与约束选择启用哪些。

### 9.2 为什么"懂 WorkManager 必须先懂这两层"

WorkManager 的"延迟"几乎从不是 WM 自己的 bug，而是底层被卡：

```text
你的 WorkRequest
  └─ WM 翻译成 JobInfo / Alarm
       └─ 交给 JobScheduler / AlarmManager
            └─ 受 Doze / bucket / 省电 / 网络约束
                 └─ 满足条件才真正执行
```

所以排查 WM 不执行，链路是：**WM 日志 → 转成的 JobInfo 约束 → `dumpsys jobscheduler` 看 satisfied → 看 bucket/Doze**。直接看 WM 层只会看到"已入队但未执行"，真正原因在下面两层。

`「Android 视角」` 常见误区：以为 `setRequiredNetworkType(CONNECTED)` 在车机流量下能跑——实际 WM 翻译成 `NETWORK_TYPE_ANY`，但 `RESTRICTED` bucket 仍可能把后台网络窗口掐掉。

---

## 10. WorkSource 与责任归属

后台任务耗电"锅该谁背"？靠 `WorkSource` 记录。alarm 与 job 在派发时都会带上发起方的 `uid`，`batterystats` 据此归因。

### 10.1 WorkSource 记录

```java
// Alarm: 显式声明责任方（系统应用代他人注册 alarm 时尤其重要）
AlarmManager am = ...;
am.setExactAndAllowWhileIdle(type, t, pi);
am.setWorkSource(new WorkSource(uidOfOwner));  // 让电量算到 uidOfOwner 头上
```

Job 的 `JobStatus` 内部也以 `uid`/`packageName` 记录归属，`batterystats` 的 `job` 段按 uid 汇总运行时长。

### 10.2 电池归因查看

```bash
# 电量按 uid 归因（找后台任务耗电大户）
adb shell dumpsys batterystats --charged | grep -i "job\|wakeup"
adb shell dumpsys batterystats your.package | grep -i "Job\|Alarm"
```

### 10.3 系统应用后台定时上报的正确姿势

`「Android 视角」` 车机/整机系统应用常需"后台定时上报位置/状态"。错误姿势是高频 `setExactAndAllowWhileIdle`——既耗电又容易被电池优化标记为异常。正确姿势：

- **优先用 JobScheduler + 时间窗**：`setRequiredNetworkType(ANY)` + `setOverrideDeadline` + `setBackoffCriteria`，让系统挑好窗口批量跑。
- **充电+空闲时做重活**：`setRequiresCharging(true)` + `setRequiresDeviceIdle(true)`，把上报并入"维护窗口"（见 11 章）。
- **精确 alarm 白名单**：若业务真的必须精确时刻（如定时采集），用 `USE_EXACT_ALARM` 或在系统白名单内，避免被用户撤销 `SCHEDULE_EXACT_ALARM`。
- **避免"时间窗轮询"陷阱**：不要为"大概每 15 分钟一次"而设 15 分钟精确 alarm；用 job 的 periodic + 网络约束，系统会自动合并。

---

## 11. 车机专章：GarageMode 与后台任务

`「Android 视角」` AAOS（Android Automotive）里后台任务有独特形态：车熄火、屏灭，但系统常需要趁机做固件升级、媒体索引、日志上传等"重活"。机制细节在 `15_Power`，本篇讲 **JobScheduler/Alarm 在 garage mode 里的行为差异与配置**。

### 11.1 GarageMode 是什么

`CarGarageModeService`（包 `com.android.car` / `packages/services/Car`）在检测到车辆"驻车/熄火"后，让系统保持一段时间的**非 Doze、可交互-like** 状态（"车库模式"），给后台 job 一个稳定执行窗口。期间：

- `DeviceIdleJobsController` 视 garage mode 为"空闲" → `setRequiresDeviceIdle(true)` 的 job **可以跑**。
- 普通 Doze 主维护窗口逻辑被 garage mode 接管，不再是"息屏 N 分钟进深 Doze"。
- `setRequiresCharging` 在充电（插枪/行驶发电）时满足。

### 11.2 熄火窗口执行维护任务的策略

经典组合：**`setRequiresDeviceIdle(true)` + `setRequiresCharging(true)` + `setPersisted(true)`**。

```java
JobInfo maintenance = new JobInfo.Builder(MAINT_ID, svc)
        .setRequiresDeviceIdle(true)     // garage mode 下满足
        .setRequiresCharging(true)       // 充电/行驶中满足
        .setPersisted(true)              // 熄火重启仍保留
        .setOverrideDeadline(HOURS(12))  // 最迟 12h 兜底跑
        .build();
```

这组约束在手机上几乎"永远不跑"（用户很少让手机既空闲又充电很久），但在车机 garage mode 下**正好命中**——这就是车机后台任务设计的精髓。

### 11.3 车机网络稀疏对 connectivity 约束的影响

车机常处于"无网/仅蜂窝/热点不稳定"。`NETWORK_TYPE_UNMETERED` 在车机几乎不可得 → 维护 job 应改用 `NETWORK_TYPE_ANY`，并自行做"有网才传、失败退避"。否则会卡在 connectivity 约束永不跑。

### 11.4 预装应用的 job 白名单

厂商预装的系统应用（OTA、车控、诊断上传）应在 `device idle` 白名单与 `JobScheduler` 配额豁免内，否则会被 Doze/bucket 同样限制。配置见第 12 章 `config_` 与白名单机制。

---

## 12. 平台定制：config_ 参数与白名单

`「Android 视角」` 作为 vendor/ROM 开发者，你最常动的是 `frameworks/base/core/res/res/values/config.xml` 里的一组参数（**不写死具体名字，按"一类参数"描述**，因为不同版本命名会演进）：

### 12.1 影响调度的高价值参数（一类）

| 参数类别 | 作用 | 改动风险 |
|---|---|---|
| Job 并发上限类（一组 int） | 控制 `JobConcurrencyManager` 的总/系统/后台执行槽数 | 调太高→内存/CPU 压力；太低→任务积压 |
| Alarm 精确白名单类 | 豁免某些 uid/包受精确 alarm 频率/权限限制 | 滥加→某 App 疯狂唤醒耗电 |
| Doze 触发阈值类（一组时长） | 息屏多久进浅/深 Doze、维护窗口长度 | 调长→更省电但通知延迟；调短→更灵敏 |
| App Standby 衰减阈值类 | 各 bucket 降级所需天数/交互信号 | 调严→低频 App 更快被限 |
| 后台限制/网络限制类 | 后台网络窗口、后台启动限制开关 | 关掉→违背 AOSP 省电初衷，CTS/VTS 风险 |

### 12.2 device idle 白名单

```bash
# 查看 Doze 白名单（豁免 Doze 限制）
adb shell dumpsys deviceidle whitelist
# 加白名单（整机预置在 /system/etc/permissions 或 config 里）
adb shell cmd deviceidle whitelist +com.your.system.app
```

白名单内的包不被 Doze 推迟——**只给真正需要常驻后台的系统应用**，否则 Doze 失效、整机耗电飙升。

### 12.3 厂商省电 Package restart 的风险

很多厂商 ROM 有"省电时杀后台/重启限制"逻辑。风险：

- 杀掉持有 `persisted` job 的 App 进程 → job 仍在 `jobs.xml`，系统会重新拉起执行，但**若被杀太频繁**，`QuotaController` 会判定异常并限流。
- 对预装系统服务误杀 → 车控/OTA 失效。
- 建议：这类服务用**前台服务 + 正确 `foregroundServiceType`**（见 `07_Service`），而不是靠"不被杀"侥幸。

---

## 13. 常见问题排查表

> 第一命令列直接可复制执行。现象按出现频率排序。

| 现象 | 原因 | 关联章节 | 第一命令 |
|---|---|---|---|
| alarm 不准时/被推迟 | 被合并进 Batch 或 Doze 挂起 | 3.2 / 4.2 | `adb shell dumpsys alarm \| grep -A20 your.pkg` |
| exact alarm 在 Android 12+ 被拒/不响 | 缺 `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM` | 4.3 | `adb shell dumpsys alarm \| grep -i "exact\|permission"` |
| job 完全不跑 | 某约束 `unsatisfied`（如 UNMETERED 无 Wi-Fi） | 5.1 / 6.1 | `adb shell cmd jobscheduler get-job-state pkg ID` |
| job 很久才跑 | bucket 降到 RARE/RESTRICTED，配额砍 | 7.1 | `adb shell am get-standby-bucket pkg` |
| job 跑一半被杀 | 超 ~10 分钟超时 / 进程死 | 6.3 | `adb shell dumpsys jobscheduler \| grep -A10 "Started jobs"` |
| 息屏后上传停止 | Doze 推迟 + 后台网络窗口受限 | 8 / 15_Power | `adb shell dumpsys deviceidle` |
| 开机后任务没恢复 | 未 `setPersisted(true)` 或缺 `RECEIVE_BOOT_COMPLETED` | 5.3 | `adb shell dumpsys jobscheduler \| grep persisted` |
| WorkManager 延迟 | 底层 JobScheduler 被 bucket/Doze 卡 | 9.2 | 先看 `dumpsys jobscheduler` 再看 WM 日志 |
| bucket 掉 RARE 后行为差异 | 后台网络窗口极少、job 配额低 | 7.2 | `adb shell am set-standby-bucket pkg rare` 复现 |
| 熄火后维护任务没跑 | garage mode 没进 / 约束组合错 | 11.2 | `adb shell dumpsys car_service \| grep -i garage` |
| 电池归因误判 | 未设 `WorkSource`，电量算错 uid | 10.1 | `adb shell dumpsys batterystats pkg` |
| 后台启动 Service 报错 | Android 8+ 后台 Service 限制 | 8 | `adb logcat \| grep "Background start"` |
| `setExact` 抛 SecurityException | Android 12+ 无精确权限 | 4.3 | 检查 `canScheduleExactAlarms()` |
| `allowWhileIdle` 频率被掐 | 跨 Doze 派发次数超上限 | 3.2 | `adb shell dumpsys alarm \| grep allowWhileIdle` |
| 周期性 job 不按预期 | `setPeriodic` 忽略了 latency/deadline | 5.1 | `dumpsys jobscheduler` 看 period |
| 厂商 ROM 下 job 永不跑 | 省电杀进程 + QuotaController 限流 | 12.3 | `dumpsys jobscheduler \| grep quota` |
| persisted job 重启丢失 | jobs.xml 写入失败/分区只读 | 5.3 | `ls -l /data/system/job/` |
| 车机连 Wi-Fi 仍不跑 | 用了 UNMETERED 但车机是蜂窝 | 11.3 | `dumpsys connectivity` 看网络类型 |

---

## 14. 读源码路线

`「Android 视角」` 推荐四条链，顺着读比通读高效。

### 路线 A：setExactAndAllowWhileIdle 全链

```text
android/app/AlarmManager.java
  setExactAndAllowWhileIdle()
    └─ IAlarmManager.set()  → AlarmManagerService.set()
         frameworks/base/services/core/java/com/android/server/alarm/AlarmManagerService.java
         └─ setImplLocked()
              ├─ canScheduleExactAlarms()      // 精确权限判定
              ├─ allowWhileIdle 配额检查
              ├─ 构造 Alarm，mAlarmBatches 合并
              └─ rescheduleKernelAlarmsLocked() → nativeSet()
    └─ AlarmThread.run() 等内核 fd → deliverAlarmsLocked() 派发 PendingIntent
```

### 路线 B：JobScheduler.schedule → JobStatus 落库 → 执行

```text
android/app/job/JobScheduler.java (JobSchedulerImpl)
  schedule() → IJobScheduler.schedule()
    frameworks/base/services/core/java/com/android/server/job/JobSchedulerService.java
    scheduleAsPackage()
      ├─ new JobStatus()                         // JobInfo → 内部表示
      ├─ JobStore.add() → /data/system/job/jobs.xml   // 持久化
      ├─ 各 StateController.maybeStartTrackingJobLocked()
      └─ maybeRunJobsLocked() → JobConcurrencyManager 挑 job
           └─ JobServiceContext.executeRunnableJob()
                └─ App 进程 JobService.onStartJob()
```

### 路线 C：bucket 判定

```text
frameworks/base/services/usage/java/com/android/server/usage/AppStandbyController.java
  onScreenOn/notifyInteraction/notifyPackageNotUsed()
    └─ 计算 bucket → UsageStatsManager.STANDBY_BUCKET_*
         └─ JobSchedulerService 读取 bucket → 影响 QuotaController 配额
```

### 路线 D：garage mode 与 job 联动

```text
packages/services/Car/service/src/com/android/car/garagemode/CarGarageModeService.java
  enterGarageMode()
    └─ DeviceIdleJobsController (framework) 视为 idle
         └─ IdleController 置 job idle-constraint satisfied
              └─ setRequiresDeviceIdle job 进入 ready → 执行
```

---

## 15. 源码路径速查表与一图总结

### 15.1 源码路径速查表

| 内容 | 路径 |
|---|---|
| AlarmManager（client API） | `frameworks/base/core/java/android/app/AlarmManager.java` |
| AlarmManagerService | `frameworks/base/services/core/java/com/android/server/alarm/AlarmManagerService.java` |
| AlarmThread / Batch | 同上文件内（内部类 `AlarmThread`、`Batch`） |
| JobScheduler（client API） | `frameworks/base/core/java/android/app/job/JobScheduler.java` + `JobSchedulerImpl.java` |
| JobSchedulerService | `frameworks/base/services/core/java/com/android/server/job/JobSchedulerService.java` |
| JobStatus / JobStore（持久化） | `frameworks/base/services/core/java/com/android/server/job/JobStatus.java`、`JobStore.java` |
| StateController 体系 | `frameworks/base/services/core/java/com/android/server/job/controllers/` |
| JobConcurrencyManager | `frameworks/base/services/core/java/com/android/server/job/JobConcurrencyManager.java` |
| AppStandbyController | `frameworks/base/services/usage/java/com/android/server/usage/AppStandbyController.java` |
| DeviceIdleController（Doze） | `frameworks/base/services/core/java/com/android/server/DeviceIdleController.java` |
| CarGarageModeService | `packages/services/Car/service/src/com/android/car/garagemode/CarGarageModeService.java` |
| config_ 调度参数 | `frameworks/base/core/res/res/values/config.xml` |

### 15.2 一次定时任务的完整旅程（ASCII）

```text
   App 进程                          system_server                     内核/App 回派
  ┌──────────────┐                ┌──────────────────────────┐      ┌──────────────┐
  │ setExact/     │  Binder       │ AlarmManagerService       │      │ PendingIntent│
  │ setJob        │──────────────▶│  ├ 权限/配额校验           │      │ 指向的        │
  │               │                │  ├ Batch 合并(唤醒省钱)    │      │ Broadcast/    │
  └──────────────┘                │  ├ JobStatus 落 jobs.xml  │      │ Service 执行  │
                                  │  └ StateController 判约束  │      └──────┬───────┘
                                  └───────────┬──────────────┘             │
              限制判定（任一层挡=不跑）            │ 满足                     │
   ┌──────────────────────────────────────────────────────────────────┐  │
   │ Doze?  bucket?  BatterySaver?  后台服务限制?  网络/充电/idle?       │◀─┘
   └──────────────────────────────────────────────────────────────────┘
                                  │ 放行
                                  ▼
                          JobServiceContext 拉起 App 进程
                                  │
                                  ▼
                          onStartJob → 干活 → jobFinished
                                  │
                                  ▼
                          batterystats 按 WorkSource 归因
```

**一句话记忆链**：
> **"App 提'几点/啥条件' → 系统合并+卡窗（Doze×bucket×省电×后台限制）→ 全满足才拉起执行 → 耗电按 WorkSource 算账。Alarm 问'几点'，Job 问'行不行'，WorkManager 只是翻译。"**

### 15.3 关联阅读

- `07_Service`：前台服务机制（何时该用前台服务而非后台任务）
- `15_Power`：Doze / 电源状态机机制本身（本篇只讲"被限制时调度器怎么响应"）
- `08_Broadcast`：系统事件触发与 `BOOT_COMPLETED` 限制
- `androidApp/09`：WorkManager 的 App 侧用法（本篇只讲其落点）
- `automotive` 篇：AAOS 整车电源与 GarageMode 机制细节

---

（全文完）
