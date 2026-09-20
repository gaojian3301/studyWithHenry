# 后台任务机制详解：从 Alarm 到 JobScheduler

> 后台任务是 Android Framework 里最容易“看起来没问题，但就是不执行”的领域。它和 Power/Doze、AMS 前后台状态、Alarm、Job、前台服务、网络和电量策略强相关。

---

## 目录

1. [后台任务到底是什么](#1后台任务到底是什么)
2. [App 侧常见现象](#2app-侧常见现象)
3. [AlarmManagerService](#3alarmmanagerservice)
4. [JobSchedulerService](#4jobschedulerservice)
5. [WorkManager 和系统服务的关系](#5workmanager-和系统服务的关系)
6. [Doze、Standby 和省电限制](#6dozestandby-和省电限制)
7. [前台服务与后台任务](#7前台服务与后台任务)
8. [常见问题与排查](#8常见问题与排查)
9. [第三方系统常见修改点](#9第三方系统常见修改点)
10. [源码路径速查](#10源码路径速查)

---

## 1. 后台任务到底是什么

后台任务不是一个单独服务，而是一组机制：

- `AlarmManager`：按时间触发。
- `JobScheduler`：按条件调度。
- `WorkManager`：Jetpack 封装，底层常用 JobScheduler。
- 前台服务：用户可感知的持续任务。
- Broadcast：系统事件触发。
- SyncAdapter/ContentObserver：数据同步和变化触发。

一句话：

> Android 越新，越不鼓励 App 在后台随时运行，而是让系统根据电量、网络、空闲状态统一调度。

---

## 2. App 侧常见现象

| 现象 | 可能原因 |
|---|---|
| 定时任务不准 | Doze 推迟普通 Alarm |
| Job 很久不执行 | 约束不满足、App standby bucket 限制 |
| 息屏后上传停止 | 网络受限、进程被杀、WakeLock 不足 |
| 开机后任务没恢复 | BOOT_COMPLETED 限制、未重新 schedule |
| 后台启动 Service 报错 | Android 8+ 后台 Service 限制 |
| WorkManager 延迟 | 底层 JobScheduler 受系统策略影响 |

---

## 3. AlarmManagerService

Alarm 负责“到某个时间点提醒系统执行”。

常见类型：

- `set()`：普通定时，不保证精确。
- `setExact()`：更精确，但仍受策略影响。
- `setAndAllowWhileIdle()`：Doze 中也允许，但频率受限。
- `setExactAndAllowWhileIdle()`：更强的精确需求，限制更严格。
- `RTC_WAKEUP` / `ELAPSED_REALTIME_WAKEUP`：是否唤醒设备。

Android 12+ 精确闹钟权限更严格，普通 App 不能随意使用 exact alarm。

---

## 4. JobSchedulerService

JobScheduler 根据条件执行任务：

```java
JobInfo job = new JobInfo.Builder(id, component)
        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
        .setRequiresCharging(true)
        .build();
```

常见约束：

- 网络类型。
- 是否充电。
- 是否空闲。
- 电量是否低。
- 存储空间是否低。
- 最小延迟和 deadline。

Job 不适合要求“立刻执行”的任务，它是系统统一调度模型。

---

## 5. WorkManager 和系统服务的关系

WorkManager 是 Jetpack 提供的兼容封装。新版本系统上，持久后台任务通常会落到 JobScheduler。

```text
WorkManager
  └─ 根据系统版本选择底层调度器
       └─ JobScheduler / Alarm / 内部调度
```

所以 WorkManager 延迟执行时，不一定是 WorkManager bug，可能是 JobScheduler 约束、Doze、省电策略或 App standby 限制。

---

## 6. Doze、Standby 和省电限制

后台任务受这些状态影响：

- Doze：设备空闲省电。
- App Standby Bucket：根据用户使用频率给 App 分桶。
- Battery Saver：省电模式。
- 后台网络限制。
- 厂商省电策略。

常用命令：

```bash
adb shell dumpsys deviceidle
adb shell am get-standby-bucket packageName
adb shell dumpsys jobscheduler
adb shell dumpsys alarm
```

---

## 7. 前台服务与后台任务

如果任务必须马上执行、持续运行、且用户应该知道它在运行，使用前台服务。

但前台服务也不是无限制：

- Android 8+ 后台 Service 限制。
- Android 12+ 前台服务启动限制。
- Android 14+ 前台服务类型和权限更严格。

---

## 8. 常见问题与排查

### Alarm 不准

看是否 Doze、是否 exact alarm 权限、是否被合并推迟。

```bash
adb shell dumpsys alarm
adb shell dumpsys deviceidle
```

### Job 不执行

```bash
adb shell dumpsys jobscheduler packageName
adb shell cmd jobscheduler run -f packageName jobId
```

重点看 constraints satisfied、standby bucket、battery saver、network。

### WorkManager 不执行

先查 WorkManager 自身日志，再查 JobScheduler 和系统约束。

---

## 9. 第三方系统常见修改点

- 放宽关键系统应用的 Doze 限制。
- 定制 Alarm 白名单。
- 调整 JobScheduler 并发和约束策略。
- 车机息屏/ACC 状态下允许特定任务运行。
- 后台任务和前台服务联动策略。

原则：后台任务修改必须评估功耗、发热、内存和用户可感知性。

---

## 10. 源码路径速查

| 内容 | 路径 |
|---|---|
| AlarmManagerService | `frameworks/base/services/core/java/com/android/server/alarm/AlarmManagerService.java` |
| JobSchedulerService | `frameworks/base/services/core/java/com/android/server/job/JobSchedulerService.java` |
| DeviceIdleController | `frameworks/base/services/core/java/com/android/server/DeviceIdleController.java` |
| Job API | `frameworks/base/core/java/android/app/job/` |
| Alarm API | `frameworks/base/core/java/android/app/AlarmManager.java` |
