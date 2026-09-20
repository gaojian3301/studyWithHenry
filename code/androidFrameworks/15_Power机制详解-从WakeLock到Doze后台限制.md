# Power 机制详解：从 WakeLock 到 Doze 后台限制

> Power 体系解释的是“设备什么时候亮、什么时候睡、后台任务为什么被限制”。它和 AMS 的进程状态、Alarm/Job 的后台调度、WMS 的亮屏请求、系统功耗策略都有关系。

---

## 目录

1. [PowerManagerService 是什么](#1powermanagerservice-是什么)
2. [App 侧能看到的现象](#2app-侧能看到的现象)
3. [核心类和模块](#3核心类和模块)
4. [WakeLock 机制](#4wakelock-机制)
5. [亮屏、灭屏和用户活动](#5亮屏灭屏和用户活动)
6. [Doze 与 DeviceIdle](#6doze-与-deviceidle)
7. [和 Alarm、Job、AMS 的关系](#7和-alarmjobams-的关系)
8. [常见问题与排查](#8常见问题与排查)
9. [第三方系统常见修改点](#9第三方系统常见修改点)
10. [源码路径速查](#10源码路径速查)

---

## 1. PowerManagerService 是什么

**PowerManagerService 是 Android 的电源状态管理中心。**

它主要管理：

- 屏幕亮灭。
- 设备是否进入休眠。
- WakeLock。
- 用户活动时间。
- 电源键、唤醒、休眠策略。
- Doze、Idle、Battery Saver 等后台限制的协作。

一句话：

> AMS 管进程活不活，Power 管设备醒不醒；后台任务能不能及时执行，经常要同时看两边。

---

## 2. App 侧能看到的现象

| 现象 | 可能相关机制 |
|---|---|
| 设备不灭屏 | WakeLock、窗口 `KEEP_SCREEN_ON`、用户活动刷新 |
| 息屏后任务不跑 | Doze、App Standby、Alarm/Job 限制 |
| 定时器不准 | Doze 下 Alarm 被推迟 |
| 后台网络停了 | Idle、Battery Saver、后台限制 |
| 无法唤醒屏幕 | 权限、WakeLock 类型、Keyguard、Power HAL |
| 亮屏后马上灭 | user activity、timeout、sensor/policy |

---

## 3. 核心类和模块

| 类 / 模块 | 作用 |
|---|---|
| `PowerManagerService` | 电源管理主服务 |
| `PowerManager` | App 侧 API |
| `WakeLock` | App/系统请求设备保持唤醒的机制 |
| `DisplayPowerController` | 显示亮度、亮灭屏状态控制 |
| `Notifier` | 通知系统其他模块电源状态变化 |
| `DeviceIdleController` | Doze/Idle 状态管理 |
| `BatteryStatsService` | 统计耗电和 WakeLock 使用 |
| Power HAL | 厂商电源硬件抽象层 |

---

## 4. WakeLock 机制

App 常见写法：

```java
PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "demo:work");
wl.acquire(10_000);
```

WakeLock 用来告诉系统：这段时间我还有重要工作，不要完全睡下去。

常见类型：

- `PARTIAL_WAKE_LOCK`：CPU 保持运行，屏幕可灭。
- 屏幕相关 WakeLock 新版本中很多已不推荐，通常用窗口 flag 或系统 API。

注意：WakeLock 不是保活神器。它不保证进程不被杀，也不绕过所有后台限制。滥用 WakeLock 会导致耗电异常。

---

## 5. 亮屏、灭屏和用户活动

影响屏幕状态的因素：

- 电源键。
- 用户触摸/按键活动。
- 窗口 `FLAG_KEEP_SCREEN_ON`。
- WakeLock。
- 传感器策略。
- Keyguard/锁屏策略。
- 车机或厂商电源状态。

WMS 的窗口可以影响 Power，例如 Activity 设置 keep screen on，最终会让 PowerManagerService 延迟灭屏。

---

## 6. Doze 与 DeviceIdle

Doze 是 Android 为省电引入的空闲模式。设备长时间静止、息屏、未充电时，系统会进入更严格的后台限制。

Doze 中常见变化：

- 普通 Alarm 延迟。
- Job 延迟。
- 后台网络受限。
- wakelock 行为受约束。
- 系统周期性进入 maintenance window，让部分任务短暂执行。

常用命令：

```bash
adb shell dumpsys deviceidle
adb shell dumpsys power
adb shell dumpsys batterystats
```

---

## 7. 和 Alarm、Job、AMS 的关系

```text
Power / DeviceIdle
  决定设备是否空闲、是否省电
        │
        ├─ AlarmManagerService：决定 Alarm 是否推迟
        ├─ JobSchedulerService：决定 Job 是否满足约束
        └─ AMS：后台限制、进程状态、前台服务状态
```

例如一个后台上传任务不执行，可能不是 JobScheduler 本身坏了，而是设备处于 Doze，网络约束不满足，App 又不在白名单。

---

## 8. 常见问题与排查

### 设备不休眠

```bash
adb shell dumpsys power
adb shell dumpsys batterystats | grep -i wake
adb shell dumpsys window | grep -i KEEP_SCREEN_ON
```

### 后台任务息屏后不跑

```bash
adb shell dumpsys deviceidle
adb shell dumpsys jobscheduler
adb shell dumpsys alarm
```

### 耗电异常

看 WakeLock、前台服务、Job、Alarm、后台定位、网络等。

---

## 9. 第三方系统常见修改点

- 车机 ACC/点火状态和 Android 睡眠唤醒联动。
- 特定系统应用加入 idle 白名单。
- 调整息屏、亮度、休眠超时策略。
- 后台任务和省电策略放宽。
- Power HAL 性能/功耗 hint 定制。

原则：功耗策略不能只满足单个 App，要看整机续航、发热、开机恢复、低电保护和系统稳定性。

---

## 10. 源码路径速查

| 内容 | 路径 |
|---|---|
| PowerManagerService | `frameworks/base/services/core/java/com/android/server/power/PowerManagerService.java` |
| PowerManager API | `frameworks/base/core/java/android/os/PowerManager.java` |
| DisplayPowerController | `frameworks/base/services/core/java/com/android/server/display/DisplayPowerController.java` |
| DeviceIdleController | `frameworks/base/services/core/java/com/android/server/DeviceIdleController.java` |
| BatteryStatsService | `frameworks/base/services/core/java/com/android/server/am/BatteryStatsService.java` |
