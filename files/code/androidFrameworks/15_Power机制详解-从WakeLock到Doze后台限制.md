# Power 机制详解：从 WakeLock 到 Doze 后台限制

> Power 体系回答的是设备「什么时候醒、谁不让它睡、睡着后谁来把它叫醒」。它上接 AMS 的进程/前台状态、WMS 的亮屏请求，横连 DeviceIdle 的 Doze 限制，下探到 Linux 内核的 autosuspend（自动挂起）。本文聚焦**电源状态机与唤醒链路本身**：WakeLock、wakefulness、SuspendBlocker、亮灭屏时序、Doze 两层、以及车机（ACC 上下电、多屏 PowerGroup、仪表常亮）的特殊形态。
>
> 分工约定：后台任务调度（Alarm / JobScheduler / Doze 对任务的约束细节）见 `16_后台任务机制详解`；**应用侧**省电与后台限制优化见 `androidApp/09-后台任务与系统限制`；本文不展开任务调度实现，只在边界处点出衔接。

---

## 目录

1. [开场：Power 回答的三个问题](#1开场power-回答的三个问题)
2. [PowerManagerService 架构：Notifier 与 SuspendBlocker 分层](#2powermanagerservice-架构notifier-与-suspendblocker-分层)
3. [wakefulness 状态机：AWAKE / DOZING / GOING_TO_SLEEP / ASLEEP](#3wakefulness-状态机awake--dozing--going_to_sleep--asleep)
4. [WakeLock 全解：类型、引用计数与 PARTIAL 的真相](#4wakelock-全解类型引用计数与-partial-的真相)
5. [休眠真正的含义：autosuspend 与 SuspendBlocker 两条链](#5休眠真正的含义autosuspend-与-suspendblocker-两条链)
6. [用户不活动计时：userActivity 与 screen-off timeout](#6用户不活动计时useractivity-与-screen-off-timeout)
7. [亮灭屏与 Display：DisplayPowerController、proximity 与熄屏时序](#7亮灭屏与-displaydisplaypowercontrollerproximity-与熄屏时序)
8. [Doze 两条：浅 Doze 与深 Doze 是完全不同层次](#8doze-两条浅-doze-与深-doze-是完全不同层次)
9. [电池与充电状态：BatteryService 与 BatterySaver 两级](#9电池与充电状态batteryservice-与-batterysaver-两级)
10. [车机专章：ACC 上下电、GarageMode 与多屏 PowerGroup](#10车机专章acc-上下电garagemode-与多屏-powergroup)
11. [平台定制：ro.、config_ 与 overlay 电源参数](#11平台定制roconfig_-与-overlay-电源参数)
12. [调试：三条 dumpsys 与 WakeLock 泄漏定位](#12调试三条-dumpsys-与-wakelock-泄漏定位)
13. [常见问题排查表](#13常见问题排查表)
14. [读源码路线：五条必看调用链](#14读源码路线五条必看调用链)
15. [源码路径速查 + 一图总结 + 关联阅读](#15源码路径速查--一图总结--关联阅读)

---

## 1. 开场：Power 回答的三个问题

把整个电源体系压缩成三个问题，后面所有机制都是这三个问题的答案：

| 问题 | 对应机制 | 你在 `dumpsys power` 里看哪里 |
|---|---|---|
| **① 系统现在醒着吗？** | `wakefulness` 状态（AWAKE/DOZING/ASLEEP…）+ 各 `PowerGroup` 的显示状态 | `mWakefulness`、`Display Power State` 段 |
| **② 谁不让它睡？** | 持有的 `WakeLock`（尤其 `PARTIAL_WAKE_LOCK`）、未释放的 `SuspendBlocker`、亮着的屏幕 | `Wake Locks`、`Suspend Blockers` 段 |
| **③ 睡着之后谁来叫醒？** | 内核/驱动的 wakeup source（按键、RTC、网络、传感器）、`AlarmManager`、用户活动 | kernel `wakeup_sources`、`dumpsys alarm` |

> 类比：把手机想成一台「需要有人按着开关才不跳闸」的灯。`WakeLock` 是「我按住开关」的举手申请；`SuspendBlocker` 是真正压住总闸的配重；`wakefulness` 是「灯现在的档位」；而 autosuspend 是「没人按开关、配重也撤了，灯就真灭了去省电」的那一瞬间。

### wakefulness 全景状态图

```text
                用户按键/触摸 / 来电 / 应用 wakeUp()
                          │
                          ▼
   ┌─────────────────────────────────────┐
   │            WAKEFULNESS_AWAKE          │◄──────────────────┐
   │       （全醒：CPU 跑、屏按策略亮）     │                   │
   └─────────────────────────────────────┘                   │
        │ 超时无活动 / 电源键 / goToSleep()                    │ userActivity()
        ▼                                                     │ 或 wakeUp()
   ┌─────────────────────────────────────┐                   │
   │        WAKEFULNESS_GOING_TO_SLEEP     │                   │
   │  （过渡态：先冻输入、播放熄屏动画、     │                   │
   │   关屏，完成后才转 ASLEEP）            │                   │
   └─────────────────────────────────────┘                   │
        │ (屏幕已灭、锁完成)                                    │
        ▼                                                     │
   ┌─────────────────────────────────────┐                   │
   │         WAKEFULNESS_ASLEEP            │                   │
   │  （CPU 可被 suspend；无 PARTIAL 时     │                   │
   │   进入内核 autosuspend）               │                   │
   └─────────────────────────────────────┘                   │
        ▲                                      │              │
        │ (Dreaming/Doze 屏保或 AOD)           │              │
        │                                       ▼              │
   ┌─────────────────────────────────────┐   (wakeUp)        │
   │         WAKEFULNESS_DOZING           │───────────────────┘
   │  （低功耗显示态：AOD / 屏保 / Doze      │
   │   屏，CPU 仍可短暂运行）                │
   └─────────────────────────────────────┘
```

### 怎么读这篇

- 先建立「状态机 + 两条 blocker 链」的心智模型（第 3、5 章）。
- 调功耗/排查不休眠，直接跳第 12、13 章拿命令和表。
- 车机相关直接看第 10 章。
- 想读源码从第 14 章五条链路切入。

---

## 2. PowerManagerService 架构：Notifier 与 SuspendBlocker 分层

`PowerManagerService`（下文简称 PMS）是电源状态的中枢，但它**不做任何一件具体的事到底层**——它把请求翻译成「向上广播」和「向下拦休眠」两路动作。

### 2.1 六类协作模块

| 模块 | 角色 | 一句话职责 |
|---|---|---|
| `PowerManagerService` | 状态机核心 | 汇总所有输入，算出「该醒还是该睡」，并驱动上下游 |
| `PowerManager` (App API) | 门面 | `newWakeLock()`、`userActivity()`、`wakeUp()`、`goToSleep()` 的 Binder 入口 |
| `Notifier` | **对上广播** | 状态变化时通知 WMS、AMS、Audio、Dream、Keyguard、BatteryStats |
| `SuspendBlocker` | **对下拦休眠** | 向内核申请「不许 suspend」的配重（native 层 `wakeup_source`/autosuspend 控制） |
| `DisplayPowerController` | 显示侧执行 | 每块屏的亮度、亮灭、proximity、Doze 显示 |
| `PowerGroup` | 多屏分组 | Android 11+ 引入，每块屏一组独立的 wakefulness + 显示状态 |

### 2.2 分层示意：PMS 居中，上广播、下拦睡

```text
   ┌─────────────── 上层（被通知者）────────────────┐
   │  WMS(亮屏/冻输入)  AMS(进程/后台)  Audio(静音)   │
   │  DreamService(AOD)  Keyguard(锁屏)  BatteryStats│
   └───────────────────┬───────────────────────────┘
                        │ Notifier.broadcast  (状态变更事件)
   ┌───────────────────┴───────────────────────────┐
   │            PowerManagerService                  │
   │   updatePowerStateLocked()  ← 唯一的「重算」入口 │
   │   所有输入（wakelock/计时/按键/充电）最后都汇聚   │
   │   到这里，算出新的 wakefulness + 各 Group 显示态  │
   └───────────────────┬───────────────────────────┘
                        │ SuspendBlocker.acquire/release
   ┌───────────────────┴───────────────────────────┐
   │  下层（内核/硬件）                              │
   │  nativeAcquireSuspendBlocker → wakeup_source    │
   │  nativeSetAutoSuspend(true/false) → autosuspend │
   └────────────────────────────────────────────────┘
```

**记忆点**：`Notifier` 负责「告诉大家要变天了」，`SuspendBlocker` 负责「别让天真的塌下来（睡死）」。PMS 自己只算状态，不下场。

### 2.3 PowerGroup：车机多屏的电源钥匙

Android 11（R）之前，整个设备只有一个全局 wakefulness；多屏设备（车机主驾屏 + 副驾屏 + 仪表屏）要各自亮灭就无法表达。于是引入 `PowerGroup`：

- 每个 `Display` 归属一个 `PowerGroup`（默认至少 1 个，主屏）。
- 每个 `PowerGroup` 持有自己的 `mWakefulness` 与显示状态（`mDisplayPowerState`）。
- `goToSleep()` 可作用于**全部 Group** 或**指定 Group**——这是车机「只灭副驾屏、仪表常亮」能落地的根本。
- PMS 顶层仍有一个聚合 `mWakefulness`，当**所有 Group 都 ASLEEP** 时整机才真正进入可 suspend 状态。

> 「**Android 视角**」：多屏相关的 Group 分配、occupant zone 映射在 `23_多屏与多窗口机制详解` 细讲；本文只关心「每块屏如何拥有独立电源状态」。

---

## 3. wakefulness 状态机：AWAKE / DOZING / GOING_TO_SLEEP / ASLEEP

`wakefulness` 是 PMS 对「设备有多清醒」的枚举（在 `Wakefulness` 常量类 / `PowerManagerInternal` 中定义）。**Android 11+ 把原来散落的整型常量收敛为语义化常量**，下面以现行集合为准。

### 3.1 四个主状态 + 一个过渡态

| 常量 | 含义 | CPU | 屏幕 | 典型触发 |
|---|---|---|---|---|
| `WAKEFULNESS_AWAKE` | 全醒 | 运行 | 按策略亮/灭 | 开机、用户活动、`wakeUp()` |
| `WAKEFULNESS_DREAMING` | 屏保态（旧，部分版本仍保留） | 运行 | Dream 接管 | idle 进入屏保 |
| `WAKEFULNESS_DOZING` | 低功耗显示（AOD/Doze 屏） | 可短时运行 | 低亮/部分像素 | `wakeUp()` 带 `WAKEFULNESS_DOZING` 或进入 Doze 显示 |
| `WAKEFULNESS_GOING_TO_SLEEP` | 过渡态 | 运行→降 | 正熄灭 | `goToSleep()` 后到真正 ASLEEP 前 |
| `WAKEFULNESS_ASLEEP` | 睡 | 可被 suspend | 灭 | 熄屏动画结束、无 blocker |

> 版本差异：旧版本用 `mWakefulness` 整型 + `WAKEFULNESS_AWAKE=1` 等；新版用 `Wakefulness` 语义常量，`isAwake()/isAsleep()/isDozing()` 等辅助判断取代裸比较。**照抄 `== 1` 这类判断在 R+ 会编译不过**。

### 3.2 状态迁移的触发源

| 迁移 | 谁触发 | 走哪条代码路径 |
|---|---|---|
| AWAKE → GOING_TO_SLEEP | 电源键 / 超时 / `goToSleep()` / 设备策略 | `PMS.goToSleep()` → `goToSleepInternal()` → `updatePowerStateLocked()` |
| GOING_TO_SLEEP → ASLEEP | 熄屏动画完成、屏幕状态为 OFF | `Notifier` + `DisplayPowerController` 回调后 `finishWakefulnessChangeLocked()` |
| ASLEEP → AWAKE | 电源键 / 来电 / `wakeUp()` / 充电插入(可配) | `PMS.wakeUp()` → `wakeUpInternal()` |
| AWAKE → DOZING | Doze 显示（AOD）进入 | `wakeUp()` 带原因 `WAKE_REASON_APPLICATION`/Doze |
| DOZING → AWAKE | 用户抬起设备/触摸 | `wakeUp()` 清除 DOZING |

### 3.3 与 PowerGroup 的组合关系

关键认知：**`wakefulness` 不再是全局唯一值**，而是「1 个整机聚合值 + N 个 Group 局部值」。

```text
整机 wakefulness = 聚合(Group0.wakefulness, Group1.wakefulness, ...)

  Group0 (主驾屏)  : AWAKE   ─┐
  Group1 (副驾屏)  : ASLEEP   ├─→ 整机仍为 AWAKE（只要有一个 Group 醒着）
  Group2 (仪表屏)  : AWAKE   ─┘

  当所有 Group 都 ASLEEP  → 整机 wakefulness = ASLEEP → 允许 autosuspend
```

这意味着：车机让「副驾屏休眠、主驾与仪表常亮」在电源层面是完全合法的状态，PMS 不会因此把整车上锁。排查「某块屏不亮」时要**按 Group 逐个看 `Display Power State` 段**（第 12 章）。

---

## 4. WakeLock 全解：类型、引用计数与 PARTIAL 的真相

`WakeLock` 是 App/系统向 PMS 申请的「别睡」令牌。多数功耗问题都出在这里。

### 4.1 WakeLock 的「层级（level）」与「标志（flag）」是两回事

很多人把 `PARTIAL_WAKE_LOCK`、`FULL_WAKE_LOCK` 叫「flag」，其实它们属于**层级（level）**，决定「锁针对 CPU 还是屏幕」；而真正的 **flag** 是叠加在层级上的修饰位。

**层级（决定锁什么）**：

| 层级常量 | 效果 | 现状 |
|---|---|---|
| `PARTIAL_WAKE_LOCK` | 保 CPU 运行，屏幕可灭 | **唯一推荐使用的应用层级**，Android 12+ 其余屏幕相关层级已废弃 |
| `SCREEN_DIM_WAKE_LOCK` | 屏保持暗亮 | 已废弃（API level 17 起 discouraged，现移除） |
| `SCREEN_BRIGHT_WAKE_LOCK` | 屏保持亮 | 已废弃 |
| `FULL_WAKE_LOCK` | 屏+键盘全亮 | 已废弃（等价于 `SCREEN_BRIGHT`+`KEYGUARD`） |
| `PROXIMITY_SCREEN_OFF_WAKE_LOCK` | 接近传感器触发时灭屏 | 仍可用（通话场景） |
| `DOZE_WAKE_LOCK` | 进入 Doze 显示（AOD） | **仅系统** |
| `DRAW_WAKE_LOCK` | 允许在 Doze 下继续绘制 | **仅系统** |

**标志（修饰位，用 `setFlags()` / 直接 OR 进 acquire 参数）**：

| 标志 | 含义 |
|---|---|
| `ACQUIRE_CAUSES_WAKEUP` | 获取锁时立刻点亮屏幕（否则只保 CPU） |
| `ON_AFTER_RELEASE` | 释放锁后把屏幕再点亮一小段时间 |
| `RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY` | 释放时若仍在接近传感器范围内则等待离开 |

> 常见误解：以为 `FULL_WAKE_LOCK` 能「锁住后台」。实际上屏幕相关层级早已废弃，且它们只能影响显示，**真正拦住内核 suspend 的是 `PARTIAL` 映射到的 SuspendBlocker**（见第 5 章）。

### 4.2 引用计数 vs 非计数

`PowerManager` 提供两套语义：

- **计数型**（`acquire()` 不带超时）：每次 `acquire()` 计数 +1，需配对 `release()`。多次 acquire 必须相同次数 release，否则锁泄漏。
- **非计数型**（`acquire(long timeout)` 带超时）：一次 acquire 即锁定，超时自动释放，release 不配对也安全——适合「做完这段活就放手」的场景。

```java
PowerManager pm = (PowerManager) ctx.getSystemService(POWER_SERVICE);
// 计数型：必须成对的 release，否则永久持有
PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tag:mywork");
wl.acquire();                 // count = 1
// ... 工作 ...
wl.release();                 // count = 0，真正释放

// 非计数型：带超时，最安全
wl.acquire(10_000);           // 10s 后自动释放，无需配对 release
```

### 4.3 `isHeld` 陷阱

- `wl.isHeld()` 只表示「这个 Java 对象当前是否持有锁」，**不代表整机因此保持清醒**——可能还有别人也持锁、或屏幕本身亮着。
- 非计数型超时释放后 `isHeld()` 变 `false`，但若作用域外又 `acquire()`，旧对象仍可持有。
- 排查泄漏要看 `dumpsys power` 的 `Wake Locks: ` 段，而**不是**在 App 里打 `isHeld()` 日志（你只看到自己这一把锁）。

### 4.4 PARTIAL 与 SuspendBlocker 的对应（核心）

`PARTIAL_WAKE_LOCK` 不是直接进内核，中间隔着 PMS 的 `SuspendBlocker`：

```text
App: wl.acquire(PARTIAL_WAKE_LOCK)
   │  Binder
   ▼
PMS: mWakeLockSuspendBlocker.acquire()   ← 名字是 "PowerManagerService.WakeLocks"
   │  nativeAcquireSuspendBlocker("PowerManagerService.WakeLocks")
   ▼
内核: 注册 wakeup_source，autosuspend 被挡住 → CPU 不进 suspend
```

只要**存在至少一把有效的 PARTIAL 锁**，PMS 就持有 `"PowerManagerService.WakeLocks"` 这个 SuspendBlocker，内核就不会自动挂起。这把锁也是 `dumpsys power` 里 `Suspend Blockers` 段最常出现、最该盯的那一行（第 5、12 章）。

---

## 5. 休眠真正的含义：autosuspend 与 SuspendBlocker 两条链

「设备睡了」在 Android 里有**两个完全不同的层次**，混淆它们会让你在排查时一头雾水：

1. **Framework 层睡眠**：wakefulness 从 AWAKE 变 ASLEEP，屏幕灭、CPU 仍可跑（有 PARTIAL 时）。
2. **内核层 suspend**：CPU 真正停摆、进入低功耗，由 **autosuspend + SuspendBlocker** 控制。

### 5.1 两条链

```text
链 A（Framework 清醒度，软件态）：
  WakeLock / 超时 / 按键
        → updatePowerStateLocked()
        → 各 PowerGroup.wakefulness = ASLEEP
        → 屏幕 OFF、通知上层（Notifier）

链 B（内核真正挂起，硬件态）：
  没有任何 SuspendBlocker 被持有（无 PARTIAL 映射的 WakeLocks blocker、
  无 Display blocker、无 Broadcast blocker…）
        → nativeSetAutoSuspend(true)
        → libsuspend 尝试冻结进程、关 CPU、进 RAM 自刷新
        → 直到某个 wakeup_source 触发（按键/RTC/网络中断）
```

**关键**：链 A 的 ASLEEP ≠ 链 B 的 suspend。设备可以是「屏幕灭了（ASLEEP）但 CPU 还在跑（有 PARTIAL 锁）」。这就是为什么「熄屏后 CPU 不睡」——链 A 已经睡了，但链 B 被 SuspendBlocker 挡住。

### 5.2 `dumpsys power` 里 SuspendBlockers 各自计数

`dumpsys power` 的 `Suspend Blockers` 段列出每个 blocker 的引用计数，真实命名形如：

| SuspendBlocker 名 | 何时被持有 | 含义 |
|---|---|---|
| `PowerManagerService.WakeLocks` | 有 PARTIAL 等唤醒锁生效时 | 拦住内核 suspend（最常见 culprit） |
| `PowerManagerService.Display` | 任一屏需要保持可显示（含 Doze 绘制） | 显示相关不可挂起 |
| `PowerManagerService.Broadcasts` | 正在发电源状态变化广播 | 防止发广播时睡死 |
| `PowerManagerService.Sleep` | 正在进入睡眠的过渡期 | 保证睡眠流程跑完 |
| `PowerManagerService.WirelessCharger` | 无线充电相关抑制 | 充电策略 |

每条后面的 `(0x...) count=N` 表示该 blocker 当前被引用 N 次。

### 5.3 PARTIAL wakelock 与 blocker 的对应关系（定位不休眠的钥匙）

```bash
# 第一步：看有没有 blocker 没释放（count>0 的就是嫌疑）
adb shell dumpsys power | sed -n '/Suspend Blockers/,/Wake Locks/p'

# 第二步：若有 "PowerManagerService.WakeLocks" 且 count>0，说明有 PARTIAL 锁未释放
# 第三步：看是谁持有
adb shell dumpsys power | sed -n '/Wake Locks:/,/Suspend Blockers/p'
# 输出形如：
#   Wake Locks: size=2
#     PARTIAL_WAKE_LOCK 'com.xxx:location' (uid=10034, pid=1234, ws=null) ...
```

只要 `WakeLocks` blocker 的 count 不为 0，内核就永远不会 autosuspend。多数「熄屏耗电/不休眠」问题的根因就在这里。

---

## 6. 用户不活动计时：userActivity 与 screen-off timeout

设备为什么会在你不动它之后灭屏？靠的是 `userActivity()` 维护的「最后一次活动时间」与 `screen-off timeout」的比较。

### 6.1 userActivity 是什么

任何「用户还活着」的信号都会调用 `PowerManager.userActivity()`：

- 触摸、按键、轨迹球、传感器（如抬手亮屏）等输入事件；
- WMS 在 `dispatch` 输入后转调 PMS；
- 部分系统事件（来电响铃、充电插入若配置了 `config_unplugTurnsOnScreen` 类策略）也会刷新。

`userActivity()` 最终更新 `mLastUserActivityTime` 等时间戳，并标记「需要重新计算电源状态」。

### 6.2 screen-off timeout 怎么算出来

`screen-off timeout` 不是单一常量，而是由一组值按优先级裁定的：

```text
基础值：Settings.Secure.SCREEN_OFF_TIMEOUT（用户设置，如 30s/1min/5min）
   │
   ├─ 设备策略上限：DevicePolicyManager 可强制最大超时（企业/车机管控）
   │
   ├─ 充电/常亮 flags：窗口 FLAG_KEEP_SCREEN_ON、WakeLock 屏幕层级（旧）
   │      → 等于"无限超时"，永不因超时而灭
   │
   ├─ 最小/最大值钳制：config_minimumScreenOffTimeout / config_maximumScreenOffTimeout
   │
   ▼
最终 mScreenOffTimeout = clamp(用户值, 最小, 最大)  且受 policy 限制
```

当 `now - mLastUserActivityTime >= mScreenOffTimeout` 且无 KEEP_SCREEN_ON，则触发 `goToSleep()` 链。

### 6.3 活动重置的坑

- **只有"用户活动"才重置计时**，App 的后台 CPU 工作、网络回调**不会**刷新 `userActivity`（这是省电设计）。所以一个持续下载的 App 屏仍会按时熄灭——想保活必须用 `PARTIAL_WAKE_LOCK`，而非指望"我在跑所以它不灭"。
- `userActivity()` 有防抖：短时间内的高频事件会被合并，不会每次都重算。
- 车机里**方向盘按键、语音唤醒**往往也要转成 userActivity，否则车机「人还在」误判为离车而休眠。

> 「**Android 视角**」：`SCREEN_OFF_TIMEOUT` 在车机常被定制为更大值或随 ACC 状态切换（见第 10 章）。

---

## 7. 亮灭屏与 Display：DisplayPowerController、proximity 与熄屏时序

屏幕的「物理亮灭」由 `DisplayPowerController`（简称 DPC）执行，PMS 只负责算出"应该是什么状态"，DPC 负责"把它变成现实"。

### 7.1 请求如何流转：DisplayPowerRequest

PMS 在 `updatePowerStateLocked()` 中为每个 `PowerGroup` 算出目标显示状态，封装成 `DisplayPowerRequest` 交给 DPC：

```text
PMS.updatePowerStateLocked()
   └─ 对每个 Group 计算目标 state（ON / DIM / DOZE / OFF / DOZE_SUSPEND…）
   └─ 生成 DisplayPowerRequest{policy, screenState, brightness, ...}
        │  (Binder / DisplayManager 内部)
        ▼
   DisplayPowerController.requestPowerState()
        └─ 异步动画 + 与 SurfaceFlinger / 背光 HAL 协作
        └─ 状态稳定后回调 onStateChanged() → 通知 PMS 继续（如转 ASLEEP）
```

`DisplayPowerRequest` 的 `policy` 关键取值：`BRIGHT`、`DIM`、`DOZE`、`OFF`、`DOZE_SUSPEND`、`VR`。

### 7.2 proximity（接近传感器）的作用

通话场景中，`PROXIMITY_SCREEN_OFF_WAKE_LOCK` + `ProximitySensor` 让「脸贴近」时灭屏、「脸离开」时亮屏：

- DPC 监听 proximity 事件，`mProximityPositive` 为真时强制屏幕 OFF（仅物理灭，不释放其他锁）；
- 释放该锁时可带 `RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY`，确保离开耳边才真正恢复；
- 车机一般没有通话 proximity 场景，但若把该锁误用于其他传感器，会出现"贴膜/反光导致屏幕乱灭"——见排查表第 13 章。

### 7.3 熄屏动画时序与 GOING_TO_SLEEP 期间的输入拦截

这是最容易被误解的细节：**熄屏不是「立刻黑」**。时序大致是：

```text
t0  goToSleep() 触发，wakefulness = GOING_TO_SLEEP
t1  PMS 通过 Notifier 通知 InputManager：开始「冻结输入」
       → InputDispatcher 不再派发新的普通输入（防止熄屏瞬间误触）
t2  DPC 播放熄屏/息屏动画（若有），背光渐暗
t3  屏幕 state = OFF，DPC 回调 onStateChanged()
t4  PMS 确认所有 Group 屏已 OFF → wakefulness = ASLEEP
t5  释放 Sleep blocker，允许 autosuspend
```

> 关键点：**输入拦截发生在 t1，而屏幕真正熄灭在 t3~t4**。所以「熄屏过程中按键被吞」是预期行为——很多"电源键失灵"其实是时序上用户在 t1~t3 窗口内连按，被冻结窗口过滤。唤醒时同理：`wakeUp()` 后 InputManager 解除冻结，输入才恢复。

---

## 8. Doze 两条：浅 Doze 与深 Doze 是完全不同层次

Doze 是 Android 省电的核心，但「浅 Doze」和「深 Doze」常被混为一谈，甚至被和内核 suspend 画等号。**它们是三个层次里的不同层**：

```text
层次 1（Framework 休眠态）  → wakefulness=ASLEEP，屏灭，但 CPU 可能还跑
层次 2（浅 Doze / Light Idle）→ DeviceIdleController 的 light idle：屏灭+未充电即进入，
                                  惰性限制网络/Job/Alarm（部分推迟）
层次 3（深 Doze / Deep Idle）→ DeviceIdleController 的 deep idle：需静止+屏灭+未充电，
                                  更严格限制，周期性开 maintenance window
   ↑ 以上都还是 Framework 层限制
层次 4（内核 suspend）      → autosuspend，CPU 真正停摆（第 5 章）
```

### 8.1 浅 Doze（Light Device Idle）

- **进入条件宽松**：屏幕关闭且未充电即可（Android 9/P 引入，应对"兜里手机不停网"的耗电）。
- **限制程度轻**：推迟部分后台网络、推迟非关键 `Job`、对 `Alarm` 做轻度延迟，但保留紧急通道（`setAndAllowWhileIdle` / 高优先级 FCM）。
- 维持时间短，不与"静止"强绑定。

### 8.2 深 Doze（Deep Device Idle）

- **进入条件严格**：屏幕关闭 + 未充电 + 设备**静止**（加速度计判定）持续一段时间。
- **限制程度重**：普通 `Alarm` 批量推迟到 maintenance window、`Job` 挂起、后台网络几乎全断、GPS/WiFi 扫描受限。
- 周期性 `MAINTENANCE` 窗口：短暂放开限制让待执行任务集中跑，然后重新收紧。

> 常见误解纠正：**深 Doze ≠ 内核 suspend**。深 Doze 只是"限制后台任务"，CPU 仍可能在跑（尤其在 maintenance 窗口或仍有 PARTIAL 锁时）。真正"CPU 停摆"是 `autosuspend`（第 5 章），两者之间隔着 SuspendBlocker。把"Doze 下收不到推送"和"设备没睡"当成同一回事，会让你在错误的地方排查。

### 8.3 `dumpsys deviceidle` 实操

```bash
# 看当前 idle 状态机（light / deep 各自处于哪个 state）
adb shell dumpsys deviceidle
# 强制进入 deep idle（调试耗电/后台限制）：
adb shell dumpsys deviceidle force-idle deep
# 退出：
adb shell dumpsys deviceidle unforce
# 看白名单（豁免 Doze 限制的包）：
adb shell dumpsys deviceidle whitelist
# light idle 单独调试：
adb shell dumpsys deviceidle force-idle light
```

状态机枚举（参考 `DeviceIdleController`）：`ACTIVE → INACTIVE → IDLE_PENDING → SENSING → LOCATING → IDLE → MAINTENANCE`，light 与 deep 各有独立轨迹。后台任务被限制的细节（Alarm 如何推迟、`JobScheduler` 的约束）见 `16_后台任务机制详解`。

---

## 9. 电池与充电状态：BatteryService 与 BatterySaver 两级

电源离不开电量与充电状态。充电会直接影响"是否允许休眠/是否因插入而亮屏"。

### 9.1 BatteryService / BatteryManagerService 的广播

- 内核或 `healthd`/`Health` HAL 上报电量、充电状态，`BatteryService` 发 `ACTION_BATTERY_CHANGED`（**粘性广播**）、`ACTION_POWER_CONNECTED` / `ACTION_POWER_DISCONNECTED`。
- PMS 监听充电状态：插入电源常会触发 `wakeUp()`（若 `config_unplugTurnsOnScreen` 类配置允许），并放宽 `SCREEN_OFF_TIMEOUT`。
- `BatteryManager` 暴露 `isCharging()`、`getIntProperty(BATTERY_PROPERTY_*)`，App 可读但不应轮询（用 `BroadcastReceiver` 监听）。

### 9.2 BatterySaver 两级：FULL 与 ADAPTIVE

`BatterySaver` 是系统级省电开关，分两级（常量在 `BatterySaverPolicy` / `PowerManager`）：

| 模式 | 常量 | 行为 |
|---|---|---|
| 关闭 | `BATTERY_SAVER_MODE_OFF` | 正常 |
| 全量 | `BATTERY_SAVER_MODE_FULL` | 触发所有限制：降频/禁用后台数据/限制振动/收紧 Job 与同步/降亮度等 |
| 自适应 | `BATTERY_SAVER_MODE_ADAPTIVE` | 按使用习惯「智能」缩短后台活动，限制较温和，可常开 |

**对系统行为的影响清单（排查"功能被误伤"时对照）**：

- 后台数据/网络：FULL 下默认禁后台移动数据，导致后台同步失败、消息延迟；
- `JobScheduler`/`SyncManager`：约束收紧，非紧急 Job 不跑（联动 `16_`）；
- 动画与特效：降低帧率/关闭部分视觉效果；
- 振动与声音：振动可能被禁用；
- 定位精度：可在 FULL 下降级为省电定位；
- `WakeLock`：FULL 不强制释放已有 PARTIAL，但会压缩后台应用的保活意愿（配合 AppOps 限制）。

```bash
# 看 BatterySaver 当前状态与触发原因
adb shell dumpsys battery | grep -i "saver\|powered\|level"
# 强制开启/关闭 FULL
adb shell settings put global low_power 1
adb shell settings put global low_power 0
```

> 版本差异：ADAPTIVE 模式是较新 Android 加入；老版本只有「开/关」二元。车机一般不启用 BatterySaver（整车供电逻辑不同），但若车机复用了手机框架默认值，可能出现"插着电却触发省电误伤"——见排查表。

---

## 10. 车机专章：ACC 上下电、GarageMode 与多屏 PowerGroup

本章对车机 ROM 开发者价值最高。车机的"电源"和手机有本质区别：**手机靠电池，车机靠 ACC（附件电源）/ 常电（B+）**，熄火后有一段"维持运行再休眠"的特殊流程。

### 10.1 ACC 上下电与 suspend 的关系

- **ACC ON（点火/附件电）**：相当于手机"插着电"，车机全功能运行，通常 `wakefulness=AWAKE`、屏幕全亮。
- **ACC OFF（熄火）**：车辆切断附件电，只留常电维持。此时车机必须尽快走完"保存状态 → 释放 SuspendBlocker → autosuspend（或下电）"，否则常电会被耗尽（所谓"趴窝/亏电")。
- 关键链路：VHAL/CarPowerManager 收到 `ACC_OFF` → `CarPowerManager` 通知 PMS 进入 `SHUTDOWN_PREPARE`/`SUSPEND` 流程 → PMS 释放各 Group 锁、进入 ASLEEP，最终 autosuspend 或整车进入低功耗待机。

> 这里"睡着"有两层含义：① Framework 层 ASLEEP（屏灭、让出 CPU）；② 整车进入 **深度休眠（常电维持最小电路，SoC 可断电或进极低功耗）**。车机的"不休眠"灾难性后果是**电瓶亏电无法启动**，比手机耗电严重得多。

### 10.2 GarageMode：车机空闲维护窗口

熄火后车机不会立刻死，而是进入 **GarageMode（车库模式）**——一段"车停着但系统还醒着做维护"的时间窗：

- 用途：完成 OTA 下载校验、日志上传、媒体索引、地图预取等"用户无感但需要算力"的任务；
- 机制：CarService 在 GarageMode 期间放宽 `JobScheduler` 约束，让被 Doze/关机推迟的 Job 跑完（具体调度细节见 `16_后台任务机制详解`，本文只点衔接）；
- 超时或任务完成后，CarPowerManager 才真正命令 PMS 释放 SuspendBlocker、autosuspend。

### 10.3 多屏各自的亮灭与 PowerGroup

车机常见多屏：主驾屏（IVI）、副驾屏、仪表屏（Cluster）、HUD/RSE。靠第 2.3 节的 `PowerGroup` 实现独立控制：

```text
PowerGroup[0] 主驾屏  → ACC ON 时 AWAKE；ACC OFF 跟随休眠
PowerGroup[1] 副驾屏  → 可由独立策略在乘客离开时提前 ASLEEP
PowerGroup[2] 仪表屏  → 常亮策略（见下）
```

每块屏的 `DisplayPowerState` 在 `dumpsys power` 中**按 Group 分开列出**，排查"某块屏不亮/不灭"必须按 Group 看，而不能只看整机 wakefulness。

### 10.4 仪表屏（Cluster）的常亮策略

仪表屏为安全相关显示（车速、告警），往往要求 **ACC OFF 后短期内仍常亮**（或至少不下电）。实现方式：

- 仪表屏归属独立的 `PowerGroup`，其 `screen-off timeout` 被设为极大/永不，或常电供给使其绕过 PMS 休眠；
- 部分方案仪表由独立 MCU/SoC 驱动，Android 仅输出画面，电源由车身 ECU 而非 PMS 管——此时"仪表还亮但 Android 已睡"是正常态，不要误判为 PMS bug。

### 10.5 电源键 vs ACC 的差异

| 触发 | 语义 | 结果 |
|---|---|---|
| 手机电源键短按 | 用户主动锁屏/亮屏 | `goToSleep()`/`wakeUp()`，整机 wakefulness 切换 |
| 车机电源键 | 多为"息屏/唤醒屏幕"，**不一定等于熄火** | 单 Group 或整机显示切换 |
| **ACC OFF** | 车辆断电指令 | 整车进入 SUSPEND/SHUTDOWN 流程，远超屏幕层面 |

**易错点**：车机的"电源键"常被厂商映射成"只灭屏"，而真正的休眠由 ACC 信号驱动。把"按电源键屏幕还亮"当成 bug，其实是该键没接 `goToSleep`；把"ACC OFF 后屏幕立刻黑但 CPU 还在跑很久"当成卡死，其实是 GarageMode 在做维护。

### 10.6 VHAL 电源属性衔接

Android 车载通过 **VHAL 的一类电源属性**（如表示点火/ACC 状态的 vehicle property，具体属性名随车型与 VHAL 实现而异，见 `automotive/03-VehicleHAL-VHAL详解` 与 `automotive/02-CarPropertyManager与VehicleProperty`）把车辆电源态暴露给 Android：

- CarService 订阅该属性变化 → 转成 `CarPowerManager` 的状态事件 → 驱动 PMS 的 wakefulness / Group 策略；
- 反向：Android 准备休眠完成时，也通过 VHAL 通知车身"我可以断电了"，形成**握手**，避免车机提前断电丢数据或延迟断电亏电。

> 这部分与车载总线、VHAL 强耦合，本文只点衔接边界；属性名/枚举以 `automotive/03` 实际目录为准，不要照抄本文的泛称。

---

## 11. 平台定制：ro.、config_ 与 overlay 电源参数

ROM 开发者的日常就是改这些。下面挑**高价值、最常被改**的列出。

### 11.1 五个最常动的 `config_` 资源（frameworks/base/core/res/res/values/config.xml）

| config 项 | 作用 | 改它解决什么 |
|---|---|---|
| `config_unplugTurnsOnScreen` | 拔掉充电器是否点亮屏幕 | 车机常设为 false（ACC 逻辑接管亮屏，避免误亮） |
| `config_minimumScreenOffTimeout` | 屏幕超时下限 | 防止被策略压到过小导致频闪灭屏 |
| `config_maximumScreenOffTimeout` | 屏幕超时的上限 | 车机常调大，避免驾驶中频繁熄屏 |
| `config_attentiveTimeout` | Android 11+ 注意力超时（久不看屏的二次确认） | 车机可关掉该交互 |
| `config_autoPowerSaveMode` | 是否自动进入 BatterySaver | 车机一般关，避免误伤车控功能 |

> `config_` 通过资源 overlay（第 11.3 节）覆盖；**不要直接改 frameworks/base 源码里的默认值**，否则升级合并冲突。

### 11.2 有用的 `ro.` / `persist.` 属性

| 属性 | 含义 | 备注 |
|---|---|---|
| `ro.powerhal.xyz`（厂商自定义） | Power HAL 行为开关 | 命名随芯片平台（QCOM/MTK）不同 |
| `persist.sys.power.*` | 运行时可调的电源调试开关 | 重启仍保留，配合 `androidOthers/Android启动与属性系统详解` 的属性机制 |
| `ro.lights.sleep` 一类 | 背光/灯光休眠相关 | 具体以平台为准 |

> 「**Android 视角**」：`persist.` 属性在 init 阶段被读取，属于 `androidOthers/Android启动与属性系统详解-从init.rc到属性服务` 的范畴，改电源相关 persist 属性要注意开机早期是否被覆盖。

### 11.3 overlay 常改的电源参数

- **资源 overlay（RRO / device overlay）**：覆盖 `config_screenBrightnessSettingDefault`（默认亮度）、`config_autoBrightness*`（自动亮度曲线）、上面的 `config_*`。
- **Power HAL 调优**：`PowerHint`（`INTERACTION`、`LAUNCH`、`SUSTAINED_PERFORMANCE`）由 PMS/AMS 调用，厂商在 HAL 实现里决定提频/降频策略。
- **framework 覆盖**：部分厂商把 `PowerManagerService` 整段派生子类（如车机的 `CarPowerManagerService` 包装 PMS），逻辑在 vendor/odm 分区。

### 11.4 `dumpsys power` 逐段读法（速查）

```bash
adb shell dumpsys power
```

按段落重点看：

| 段 | 看什么 |
|---|---|
| `Power Manager State` | `mWakefulness`（整机清醒度）、各时间戳 |
| `Display Power State` | 每块 `PowerGroup` 的屏状态（ON/DIM/OFF/DOZE）、亮度 |
| `Wake Locks:` | 所有持有中的 WakeLock 列表（tag/uid/pid/ws） |
| `Suspend Blockers` | 各 blocker 的 count，找 count>0 的 culprit |
| `Settings and Configuration` | 当前 timeout、policy 值 |
| `Power Groups` | 多屏分组及其 wakefulness（车机必看） |

---

## 12. 调试：三条 dumpsys 与 WakeLock 泄漏定位

### 12.1 三条核心命令

```bash
# 1) 电源总览：wakefulness / Group / WakeLocks / SuspendBlockers
adb shell dumpsys power

# 2) 电池/充电/省电状态
adb shell dumpsys battery

# 3) Doze / DeviceIdle 状态机与白名单
adb shell dumpsys deviceidle
```

### 12.2 WakeLock 泄漏定位（谁持有 PARTIAL）

```bash
# A. 直接看 WakeLock 列表，grep 出 PARTIAL
adb shell dumpsys power | grep -A 20 "Wake Locks:"

# B. 只看 PARTIAL 且找出 uid/pid/tag
adb shell dumpsys power | grep -E "PARTIAL_WAKE_LOCK" -A 1

# C. 对应的 SuspendBlocker 是否因此未释放
adb shell dumpsys power | grep -A 30 "Suspend Blockers"
#    若 "PowerManagerService.WakeLocks" count>0，而上一步有 PARTIAL，即泄漏源

# D. 进一步按 uid 聚合（谁持有最多）
adb shell dumpsys batterystats | grep -i "wake_lock" -A 5
```

典型输出解读：

```text
Wake Locks: size=1
  PARTIAL_WAKE_LOCK 'com.navi:gps' (uid=10045, pid=8821, ws=WorkSource{10045})
        ^tag                ^uid      ^pid        ^WorkSource(谁触发)
```

拿着 `pid=8821` 去 `dumpsys activity processes` 反查包名，或 `ps -p 8821` 确认进程，就是修复对象（漏 `release()` 或异常路径未释放）。

### 12.3 熄屏后应用还在跑的归因

屏幕灭了（wakefulness=ASLEEP）但 CPU 没睡，归因顺序：

1. `dumpsys power` → `Suspend Blockers`：有没有 `count>0`？
2. 若有 `PowerManagerService.WakeLocks` count>0 → 存在 PARTIAL 锁，回到 12.2 找持有者；
3. 若 `PowerManagerService.Display` count>0 → 某屏还被要求显示（含 Doze 绘制），看 `Display Power State` 哪块 Group 没 OFF；
4. 若两者都是 0 但 CPU 仍跑 → 不是 PMS 层问题，可能是内核 wakeup_source（kernel `cat /sys/kernel/debug/wakeup_sources`）或音频/相机等硬件持有。

### 12.4 亮屏慢 / 熄屏慢

- 亮屏慢：看 `wakeUp()` 是否因 `wakefulness` 被某些策略挡住；`dumpsys power` 看 `mWakefulness` 变化时间戳；或 `DisplayPowerController` 动画/背光 ramp 过长（查 `config_` 亮度曲线）。
- 熄屏慢：多在 `GOING_TO_SLEEP` 期间卡在输入冻结或动画（第 7.3 节）；看 `Suspend Blockers` 中 `Sleep` blocker 是否长期持有。

---

## 13. 常见问题排查表

| 现象 | 根因 | 对应章节 | 第一命令 |
|---|---|---|---|
| 熄屏后 CPU 不睡 | PARTIAL_WAKE_LOCK 未释放 | 4.4 / 5.3 / 12.2 | `adb shell dumpsys power | grep -A30 "Suspend Blockers"` |
| 亮屏慢 | `wakeUp` 被策略挡 / 背光 ramp 长 | 7.3 / 12.4 | `adb shell dumpsys power | grep mWakefulness` |
| 无法自动熄屏 | 有 KEEP_SCREEN_ON / timeout 被 policy 钳大 | 6.2 / 11.1 | `adb shell dumpsys window | grep KEEP_SCREEN_ON` |
| Doze 下收不到高优先级推送 | 误用普通 Alarm / 不在白名单 | 8.2 / 8.3 | `adb shell dumpsys deviceidle whitelist` |
| 车机 ACC 熄火后不睡 | GarageMode 未结束 / 仍持 SuspendBlocker | 10.1 / 10.2 | `adb shell dumpsys power | grep -E "Suspend Blockers|Power Groups"` |
| 多屏中某块不亮 | 对应 PowerGroup 屏状态异常 | 2.3 / 10.3 / 11.4 | `adb shell dumpsys power | grep -A40 "Display Power State"` |
| proximity 失灵 | 接近传感器未接 / 锁用错 | 7.2 | `adb shell dumpsys sensor | grep proximity` |
| BatterySaver 误伤功能 | 开了 FULL 限制后台/网络 | 9.2 | `adb shell dumpsys battery | grep -i saver` |
| 屏幕频繁闪灭 | `screen-off timeout` 被压到最小值 | 6.2 / 11.1 | `adb shell settings get system screen_off_timeout` |
| 拔充电线屏幕乱亮 | `config_unplugTurnsOnScreen` 误配 | 9.1 / 11.1 | 查 overlay 的 `config_unplugTurnsOnScreen` |
| 仪表屏意外熄灭 | 仪表 Group 被错误纳入休眠 | 10.4 | 按 Group 看 `dumpsys power` 的 Power Groups |
| 电源键只灭屏不休眠 | 该键未接 `goToSleep`/被映射错 | 10.5 | `adb shell dumpsys power | grep mWakefulness` |
| 息屏动画卡住 | `GOING_TO_SLEEP` 输入冻结未解除 | 7.3 | 看 `Suspend Blockers` 的 `Sleep` 计数 |
| 后台 Job 不执行 | Doze/限制 + 不在白名单 | 8 / 16 | `adb shell dumpsys jobscheduler` |
| 整机不进 autosuspend | 多个 blocker 任一 count>0 | 5.2 | `adb shell dumpsys power | sed -n '/Suspend Blockers/,/Wake/p'` |
| 车机熄火亏电 | 休眠流程未完成就断电 / VHAL 握手失败 | 10.1 / 10.6 | 查 CarPowerManager 日志 + VHAL 属性回调 |

---

## 14. 读源码路线：五条必看调用链

带绝对路径前缀：`frameworks/base/services/core/java/com/android/server/power/`，下文省略。

### 链路 1：`acquireWakeLock` → 更新持有集合 → 影响 blocker

```text
PowerManagerService.acquireWakeLock()                 // Binder 入口
  → acquireWakeLockInternal()
     → 创建/复用 WakeLock 对象，加入 mWakeLocks（ArrayList）
     → updatePowerStateLocked()                       // 唯一重算入口
        → updateWakeLockSummaryLocked()               // 汇总所有锁 → mWakeLockSummary
        → 若含 PARTIAL → mWakeLockSuspendBlocker.acquire()
```

### 链路 2：`userActivity` → 重置活动 → 重算是否超时

```text
PowerManager.userActivity() (Binder)
  → PowerManagerService.userActivity()
     → userActivityInternal()
        → 更新 mLastUserActivityTime(Now)
        → updatePowerStateLocked()                   // 重新比较 timeout
```

### 链路 3：`goToSleep` 全链（熄屏睡眠）

```text
PowerManager.goToSleep()
  → PowerManagerService.goToSleep()
     → goToSleepInternal()
        → 设 wakefulness = GOING_TO_SLEEP
        → Notifier.onGoToSleepStarted()              // 冻结输入、发广播
        → updatePowerStateLocked()
           → 各 PowerGroup → DisplayPowerRequest(OFF)
           → DisplayPowerController 灭屏动画
           → 回调 → finishWakefulnessChangeLocked() → wakefulness = ASLEEP
           → 释放 Sleep blocker
```

### 链路 4：`DisplayPowerRequest` 的流转

```text
PMS.updateDisplayPowerStateLocked()
  → 生成 DisplayPowerRequest（per Group）
  → DisplayManagerInternal.requestPowerState()        // 跨到 DPC
     → DisplayPowerController.requestPowerState()
        → 异步动画 + 与 SurfaceFlinger/背光 HAL 协作
        → onStateChanged() 回调 PMS（驱动 wakefulness 完成）
```

### 链路 5：SuspendBlocker 的 acquire/release 与 autosuspend

```text
PMS.updateSuspendBlockerLocked()
  → 根据 mWakeLockSummary / 显示需求，决定持有哪些 SuspendBlocker
  → mWakeLockSuspendBlocker.acquire()/release()       // 名字 "PowerManagerService.WakeLocks"
  → 当所有 blocker 释放 → nativeSetAutoSuspend(true)
     → libsuspend 尝试内核挂起，直到 wakeup_source 触发
```

> 读源码建议顺序：先 `updatePowerStateLocked()`（它是所有输入的汇聚点），再顺藤摸瓜看上面五条。别从 `PowerManager` API 一层层钻，容易迷失。

---

## 15. 源码路径速查 + 一图总结 + 关联阅读

### 15.1 源码路径速查表

| 内容 | 路径 |
|---|---|
| `PowerManagerService` | `frameworks/base/services/core/java/com/android/server/power/PowerManagerService.java` |
| `PowerManager` (App API) | `frameworks/base/core/java/android/os/PowerManager.java` |
| `Notifier` | `.../server/power/Notifier.java` |
| `SuspendBlocker` 接口与实现 | `.../server/power/PowerManagerService.java` 内 `SuspendBlockerImpl` |
| `DisplayPowerController` | `frameworks/base/services/core/java/com/android/server/display/DisplayPowerController.java` |
| `PowerGroup` | `.../server/power/PowerGroup.java` |
| `Wakefulness` 常量 | `.../server/power/Wakefulness.java`（或 `PowerManagerInternal` 内） |
| `DeviceIdleController` (Doze) | `frameworks/base/services/core/java/com/android/server/DeviceIdleController.java` |
| `BatteryService` | `frameworks/base/services/core/java/com/android/server/BatteryService.java` |
| `CarPowerManager` (车机) | `packages/services/Car/service/src/com/android/car/pm/CarPowerManager.java` |
| `GarageMode` (车机) | `packages/services/Car/service/.../GarageMode.java` |
| 内核 autosuspend | `system/core/libsuspend/`（native 侧） |

### 15.2 一图总结

```text
                    ┌─────────────────────────────────────────┐
                    │         用户/应用/按键/充电/ACC          │
                    └───────────────────┬─────────────────────┘
                                        │ acquire/release/userActivity/goToSleep
                                        ▼
                    ┌─────────────────────────────────────────┐
                    │        PowerManagerService                │
                    │   updatePowerStateLocked() 唯一重算入口   │
                    │   算出: 整机+各 PowerGroup.wakefulness    │
                    └──────┬───────────────────────┬──────────┘
              Notifier(上) │                        │ SuspendBlocker(下)
                           ▼                        ▼
               WMS/AMS/Audio           "PowerManagerService.WakeLocks" 等
               /Dream/Keyguard                   │
               状态广播                          count>0 ?
                                                │
                                     ┌──────────┴──────────┐
                                     ▼                     ▼
                              有 blocker 持有        全部释放
                                     │                     │
                                     ▼                     ▼
                              autosuspend 被挡       nativeSetAutoSuspend(true)
                              CPU 不睡(链B失败)       → 内核真正挂起(链B成功)
```

**一句话记忆链**：
> 谁不让睡看 **WakeLock**，WakeLock 真拦睡靠 **SuspendBlocker**，SuspendBlocker 全撤才进 **autosuspend**；屏幕只是 **wakefulness** 的一面，多屏各归各的 **PowerGroup**；Doze 管的是"后台能不能跑"，和"内核睡没睡"是两件事。

### 15.3 关联阅读

- `16_后台任务机制详解-从Alarm到JobScheduler`：Alarm/Job 如何被 Doze 限制、GarageMode 如何调度 Job（本文第 8、10.2 章的下游）。
- `18_SystemUI机制详解-从状态栏到锁屏与通知面板`：熄屏通知、Doze 屏（AOD）、锁屏与电源状态联动（第 7、8 章的上层呈现）。
- `23_多屏与多窗口机制详解-从DisplayManager到CarOccupantZone`：PowerGroup 与多屏/occupant zone 的映射细节（第 2.3、10.3 章的延伸）。
- `androidApp/09-后台任务与系统限制`：**应用侧**省电与后台限制优化（本文的 App 视角补全）。
- `androidOthers/Android启动与属性系统详解-从init.rc到属性服务`：`persist.`/`ro.` 电源属性的读取时机（第 11.2 章基础）。
- `automotive/02-CarPropertyManager与VehicleProperty`、`automotive/03-VehicleHAL-VHAL详解`：车机 ACC/点火的 VHAL 电源属性与握手（第 10.1、10.6 章的深入）。
- `androidOthers/Android车载总线详解-从CAN到SOME-IP与车辆网络`：ACC/常电在整车网络中的来源（第 10 章背景）。

---

> 版本差异速记：① `PowerGroup` 为 **Android 11(R) 引入**，在此之前无多屏独立电源态；② `Wakefulness` 语义化常量在 R+ 替代旧整型，旧的 `WAKEFULNESS_AWAKE == 1` 写法已失效；③ Doze 的 **浅/深（light/deep）双轨** 自 Android 9(P) 起成型；④ `BatterySaver` 的 **ADAPTIVE** 模式为较新加入，老版本仅 FULL/OFF 二元。改动时务必先 `grep` 目标分支实际符号，勿照抄本文常量名。