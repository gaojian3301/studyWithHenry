# ATMS 机制详解：从 startActivity 到任务栈调度

> 这篇是 AMS 文档的姊妹篇。AMS 更像应用进程、Service、Broadcast、Provider、OOM、ANR 的总调度中心；ATMS 则专门负责 Activity、Task、启动模式、多窗口、多屏和生命周期事务。两者不是替代关系，而是现代 Android 中共同完成应用启动和前后台切换的协作关系。
> 目标读者：已经知道 AMS/PMS/Binder 的基本概念，希望进一步看懂 `startActivity()`、任务栈、ActivityRecord、Task、ClientTransaction、多屏多窗口和 ATMS/AMS 协作流程的 Android 开发者。

---

## 目录

1. [先建立直觉：ATMS 是什么](#1先建立直觉atms-是什么)
2. [ATMS 和 AMS 的关系](#2atms-和-ams-的关系)
3. [从 App 侧看 ATMS：哪些现象和它有关](#3从-app-侧看-atms哪些现象和它有关)
4. [ATMS 涉及到的核心类](#4atms-涉及到的核心类)
5. [startActivity 总体流程](#5startactivity-总体流程)
6. [ActivityStarter：解析启动请求](#6activitystarter解析启动请求)
7. [Task 与 ActivityRecord：任务栈到底是什么](#7task-与-activityrecord任务栈到底是什么)
8. [launchMode 和 Intent flags 如何影响任务栈](#8launchmode-和-intent-flags-如何影响任务栈)
9. [pause、resume、stop 的生命周期调度](#9pauseresumestop-的生命周期调度)
10. [ClientTransaction：ATMS 如何让 App 执行生命周期](#10clienttransactionatms-如何让-app-执行生命周期)
11. [ATMS 与 AMS 如何配合启动进程](#11atms-与-ams-如何配合启动进程)
12. [ATMS 与 PMS、WMS 的配合](#12atms-与-pmswms-的配合)
13. [多窗口、多屏与 Display 调度](#13多窗口多屏与-display-调度)
14. [后台启动 Activity 限制](#14后台启动-activity-限制)
15. [常见问题与排查方法](#15常见问题与排查方法)
16. [第三方系统常见修改点](#16第三方系统常见修改点)
17. [读源码的推荐路线](#17读源码的推荐路线)
18. [关键源码路径速查](#18关键源码路径速查)
19. [一图总结](#19一图总结)

---

## 1. 先建立直觉：ATMS 是什么

**一句话：ATMS 是 Android 里专门负责 Activity 和 Task 调度的系统服务。**

App 侧你最熟悉的入口是：

```java
startActivity(intent);
startActivityForResult(intent, requestCode);
finish();
moveTaskToBack(true);
```

这些 API 背后会涉及一系列问题：

- 这个 Intent 对应哪个 Activity？
- 这个 Activity 能不能被当前 App 启动？
- 目标 Activity 放进已有 Task，还是新建 Task？
- `singleTop`、`singleTask`、`NEW_TASK`、`CLEAR_TOP` 到底怎么影响返回栈？
- 当前前台 Activity 要不要先 pause？
- 目标进程不存在时，谁负责启动进程？
- Activity 生命周期如何跨进程发给 App？
- 多窗口、多屏场景下，Activity 应该启动到哪个 display、哪个 root task？

这些问题主要由 ATMS 体系处理。

早期 Android 中，Activity 栈和任务管理大量写在 AMS 里。后来 AMS 变得过于庞大，Android 10 左右把 Activity/Task 相关逻辑拆到 `ActivityTaskManagerService`（ATMS）和 `com.android.server.wm` 包中。现在看 Activity 启动流程，不能只看 AMS，要重点看 ATMS。

---

## 2. ATMS 和 AMS 的关系

ATMS 和 AMS 是协作关系，不是“有了 ATMS 就不需要 AMS”。

可以先这样分工：

| 服务 | 主要职责 | 直观理解 |
|---|---|---|
| AMS：`ActivityManagerService` | 进程、Service、Broadcast、Provider、OOM adj、ANR、应用状态 | 管“应用进程和组件运行状态” |
| ATMS：`ActivityTaskManagerService` | Activity 启动、Task、ActivityRecord、Display、多窗口、生命周期事务 | 管“Activity 怎么启动、放哪、怎么切前后台” |
| PMS：`PackageManagerService` | 包信息、组件解析、权限、签名、安装卸载 | 管“系统里有哪些应用和组件” |
| WMS：`WindowManagerService` | 窗口、焦点、显示、Surface、布局 | 管“窗口怎么显示到屏幕上” |

一次 `startActivity()` 里，它们会这样配合：

```text
App 调 startActivity
        │
        ▼
ATMS 接收启动请求
        │
        ├─ 问 PMS：Intent 匹配哪个 Activity？权限/exported 是否允许？
        ├─ 自己决定：放入哪个 Task、哪个 Display、是否复用已有 Activity？
        ├─ 问 AMS：目标进程是否存在？不存在就启动进程
        ├─ 和 WMS 协作：窗口焦点、显示区域、可见性变化
        └─ 通过 ClientTransaction 回调 App 执行生命周期
```

所以更准确的模型是：

> ATMS 负责 Activity/Task 决策；AMS 负责进程和应用状态支撑；PMS 提供包和组件事实；WMS 负责窗口显示。Activity 启动是这几套服务共同完成的。

---

## 3. 从 App 侧看 ATMS：哪些现象和它有关

| App 侧现象 | 可能对应的 ATMS 逻辑 |
|---|---|
| `startActivity()` 后打开了旧页面 | Task 复用、`singleTask`、`CLEAR_TOP`、`singleTop` |
| Activity 没有重新走 `onCreate()`，只走 `onNewIntent()` | 复用了已有 ActivityRecord |
| 点击后没反应或被拦截 | 后台启动限制、权限/exported、user/display 策略 |
| Activity 启动到错误屏幕 | displayId、ActivityOptions、多屏启动策略 |
| 返回键顺序不符合预期 | Task 栈组织、launchMode、taskAffinity |
| 冷启动慢 | 进程启动、pause 前一个 Activity、bindApplication、launch 生命周期事务 |
| 黑屏/白屏时间长 | Activity 创建慢、窗口显示慢、前一个 Activity pause 慢 |
| Activity 切后台后马上 stop | 可见性变化和生命周期调度 |
| 多窗口下生命周期和全屏不一样 | top resumed、visible、focused、multi-resume 策略 |

ATMS 的价值在于：它解释了很多“明明我只是启动页面，为什么结果和预期不一样”的问题。

---

## 4. ATMS 涉及到的核心类

### 4.1 对外接口

| 类 / 接口 | 作用 |
|---|---|
| `IActivityTaskManager.aidl` | ATMS 对外 Binder 接口 |
| `ActivityTaskManager` | App/framework 侧访问 ATMS 的包装类 |
| `ActivityTaskManagerService` | ATMS 服务端实现，运行在 `system_server` |

App 侧 `startActivity()` 现代版本通常会进入：

```text
ActivityTaskManager.getService().startActivity(...)
```

这背后就是对 `IActivityTaskManager` 的 Binder 调用。

### 4.2 启动流程核心类

| 类 | 作用 |
|---|---|
| `ActivityStarter` | 解析启动请求、Intent flags、launchMode、task 复用，是 startActivity 核心类 |
| `ActivityStartController` | 创建和管理 `ActivityStarter`，协调启动请求 |
| `ActivityTaskSupervisor` | 协调 Activity 启动、pause/resume、进程准备、realStartActivity |
| `RootWindowContainer` | display/task/activity 的根容器，负责全局查找和 resume 顶层 Activity |
| `Task` | 一个任务栈，里面保存一组 ActivityRecord |
| `ActivityRecord` | system_server 眼中的一个 Activity 实例 |
| `TaskDisplayArea` | 一个 display 上可以承载 task 的区域 |
| `RootTask` | 旧文档里常说的 stack 在新版本中的形态之一，用来组织 Task |

### 4.3 生命周期事务相关类

| 类 | 作用 |
|---|---|
| `ClientLifecycleManager` | system_server 侧发送生命周期事务 |
| `ClientTransaction` | 一次发给 App 的生命周期调度包 |
| `LaunchActivityItem` | 告诉 App 创建 Activity |
| `ResumeActivityItem` | 告诉 App 执行 resume |
| `PauseActivityItem` | 告诉 App 执行 pause |
| `StopActivityItem` | 告诉 App 执行 stop |
| `DestroyActivityItem` | 告诉 App 销毁 Activity |
| `TransactionExecutor` | App 侧执行 `ClientTransaction` |

### 4.4 App 进程侧类

| 类 | 作用 |
|---|---|
| `ActivityThread` | App 主线程入口，真正执行 Activity 生命周期 |
| `ApplicationThread` | App 暴露给 system_server 的 Binder Stub |
| `IApplicationThread` | ATMS/AMS 回调 App 的 Binder 接口 |
| `ActivityClientRecord` | App 进程内的 Activity 记录 |
| `Instrumentation` | 反射创建 Activity、调用生命周期 |

---

## 5. startActivity 总体流程

从 App 侧看：

```text
Activity.startActivity()
  └─ Activity.startActivityForResult()
       └─ Instrumentation.execStartActivity()
            └─ ActivityTaskManager.getService().startActivity(...)
                 └─ Binder 到 system_server
```

进入 system_server 后：

```text
ActivityTaskManagerService.startActivity()
  └─ ActivityStartController.obtainStarter()
       └─ ActivityStarter.execute()
            ├─ 解析 Intent 和 ActivityInfo
            ├─ 校验权限、exported、userId、后台启动限制
            ├─ 计算 launchMode、flags、taskAffinity
            ├─ 查找或创建目标 Task
            ├─ pause 当前 top Activity
            ├─ 必要时请求 AMS 启动目标进程
            └─ realStartActivityLocked()
                 └─ ClientTransaction 发给目标 App
```

目标 App 收到后：

```text
ApplicationThread.scheduleTransaction()
  └─ ActivityThread.H 切主线程
       └─ TransactionExecutor.execute()
            ├─ LaunchActivityItem：handleLaunchActivity
            └─ ResumeActivityItem：handleResumeActivity
```

这条链路说明：`startActivity()` 不是“一步到 Activity.onCreate()”，中间要经过 Binder、ATMS 决策、AMS 进程支撑、App 主线程执行。

---

## 6. ActivityStarter：解析启动请求

`ActivityStarter` 是理解 Activity 启动规则的核心类。它主要回答几个问题。

### 6.1 要启动谁

ATMS 会结合 PMS 解析 Intent：

```text
Intent
  ├─ 显式 component：直接找目标 ActivityInfo
  └─ 隐式 action/category/data：通过 PMS intent-filter 匹配
```

如果解析不到，就会出现 App 侧常见的：

```text
ActivityNotFoundException
```

### 6.2 能不能启动

常见校验包括：

- 目标 Activity 是否存在。
- 目标 Activity 是否 enabled。
- 目标 Activity 是否 exported。
- 调用方是否有目标权限。
- 是否跨用户启动。
- 后台启动 Activity 是否允许。
- 当前锁屏、profile、display 状态是否允许。

校验失败时，App 侧可能看到 `SecurityException`，logcat 中可能看到 `Permission Denial`、`Background activity start denied` 之类日志。

### 6.3 放进哪个 Task

这是 ATMS 最核心的职责之一。它会综合：

- `launchMode`
- Intent flags
- `taskAffinity`
- resultTo / requestCode
- source Activity 所在 Task
- 是否从 Launcher 启动
- 是否多窗口/多屏
- 是否已有可复用 Activity

最终决定：复用已有 Activity、复用已有 Task、新建 Task，还是把 Activity 放到当前 Task 顶部。

---

## 7. Task 与 ActivityRecord：任务栈到底是什么

App 开发里常说“返回栈”，Framework 里更准确地说是 Task 里的 ActivityRecord 组织关系。

### 7.1 ActivityRecord

`ActivityRecord` 是 system_server 中代表一个 Activity 实例的记录，它保存：

- componentName
- intent
- ActivityInfo
- token
- 所属 Task
- 所属进程
- 当前生命周期状态
- visible / finishing / resumed 等状态
- resultTo / requestCode
- launchMode / flags 影响后的结果

App 进程里真正的 Activity 对象不在 system_server。system_server 只有 `ActivityRecord`，通过 token 和 App 进程里的 `ActivityClientRecord` 对应。

### 7.2 Task

`Task` 是一组有返回关系的 ActivityRecord。

典型例子：

```text
Task #1
  MainActivity
  DetailActivity
  EditActivity  <- top
```

按返回键时，通常会 finish 顶部 Activity，回到下面的 Activity。

### 7.3 RootWindowContainer / DisplayArea / RootTask

现代 Android 为了支持多窗口、多屏、分屏、桌面模式，层级比早期“一个 ActivityStack”复杂很多。

可以先按这个直觉理解：

```text
RootWindowContainer
  └─ DisplayContent / TaskDisplayArea
       └─ RootTask
            └─ Task
                 └─ ActivityRecord
```

不同 Android 版本类名和层级细节会变化，但核心是：Activity 不只是线性栈，它属于某个 Task，Task 又属于某个 display/window 容器。

---

## 8. launchMode 和 Intent flags 如何影响任务栈

这是 App 侧最容易遇到、也最容易误判的 ATMS 行为。

### 8.1 launchMode

| launchMode | 直观行为 |
|---|---|
| `standard` | 每次启动通常创建新实例 |
| `singleTop` | 如果目标 Activity 已在栈顶，复用并回调 `onNewIntent()` |
| `singleTask` | 在匹配 Task 中复用已有实例，并清理其上方 Activity |
| `singleInstance` | 目标 Activity 独占一个 Task，旧模式，新版本不推荐滥用 |
| `singleInstancePerTask` | 新版本引入，更明确地表达每个 Task 一个实例 |

### 8.2 常见 flags

| flag | 直观行为 |
|---|---|
| `FLAG_ACTIVITY_NEW_TASK` | 在新 Task 或已有匹配 Task 中启动 |
| `FLAG_ACTIVITY_CLEAR_TOP` | 如果目标已在栈内，清掉它上面的 Activity |
| `FLAG_ACTIVITY_SINGLE_TOP` | 如果目标在栈顶，复用并走 `onNewIntent()` |
| `FLAG_ACTIVITY_CLEAR_TASK` | 配合 `NEW_TASK` 清空目标 Task |
| `FLAG_ACTIVITY_REORDER_TO_FRONT` | 把已有 Activity 移到前台 |
| `FLAG_ACTIVITY_MULTIPLE_TASK` | 允许创建多个 Task，通常要谨慎使用 |

### 8.3 App 侧现象怎么对应

| 现象 | 常见原因 |
|---|---|
| `onCreate()` 没走，走了 `onNewIntent()` | `singleTop`、`singleTask` 或 `SINGLE_TOP` flag 复用实例 |
| 启动后中间页面没了 | `CLEAR_TOP` 或 `singleTask` 清理了上方 Activity |
| 返回键回到奇怪页面 | taskAffinity、`NEW_TASK`、跨任务启动导致 |
| Launcher 点击回到旧页面 | Launcher 复用已有 Task，而不是每次新建 |
| 多次点击生成多个任务 | `MULTIPLE_TASK`、documentLaunchMode 或特殊 flags |

理解这些行为时，不要只看 App 代码，要看最终传给 ATMS 的 Intent flags、Manifest launchMode、taskAffinity 和当前已有 Task 状态。

---

## 9. pause、resume、stop 的生命周期调度

Activity 切换不是直接“新 Activity onResume”。通常要先处理当前前台 Activity。

### 9.1 pause 当前 Activity

启动新 Activity 时，如果当前 top Activity 需要让出前台，ATMS 会先下发 pause：

```text
ATMS
  startPausingLocked
        │ Binder：IApplicationThread
        ▼
App A
  ActivityThread 执行 onPause
        │ Binder 回报 activityPaused
        ▼
ATMS
  继续启动/显示目标 Activity
```

如果 App A 主线程卡住，`onPause()` 回报不及时，用户就可能看到启动慢、黑屏、甚至 ANR。

### 9.2 resume 目标 Activity

目标 Activity 准备好后，ATMS 会通过 `ResumeActivityItem` 调度 App：

```text
ClientTransaction
  └─ ResumeActivityItem
       └─ ActivityThread.handleResumeActivity
            └─ Activity.performResume
                 └─ onResume()
```

### 9.3 stop 不一定立刻发生

当前 Activity 进入后台后，通常会在不可见时 stop。但 stop 的时机可能受动画、窗口可见性、多窗口状态影响。

多窗口下，一个 Activity 即使不是 top，也可能仍然 visible，因此不一定进入 stop。

---

## 10. ClientTransaction：ATMS 如何让 App 执行生命周期

现代 Android 使用 `ClientTransaction` 统一描述发给 App 的生命周期事务。

### 10.1 为什么需要 ClientTransaction

早期系统里，AMS 直接调用类似 `scheduleLaunchActivity()`、`schedulePauseActivity()`。后来生命周期调度越来越复杂，需要一种更统一的事务模型。

`ClientTransaction` 可以把一组操作打包发给 App，例如：

```text
ClientTransaction
  callbacks:
    - LaunchActivityItem
  lifecycleStateRequest:
    - ResumeActivityItem
```

App 侧由 `TransactionExecutor` 按顺序执行。

### 10.2 launch + resume 的例子

```text
system_server
  ClientLifecycleManager.scheduleTransaction
        │ Binder
        ▼
App 进程
  ApplicationThread.scheduleTransaction
        │
        ▼
  ActivityThread.H
        │
        ▼
  TransactionExecutor.execute
        ├─ LaunchActivityItem.execute
        │    └─ ActivityThread.handleLaunchActivity
        └─ ResumeActivityItem.execute
             └─ ActivityThread.handleResumeActivity
```

这也解释了为什么生命周期一定在 App 主线程跑：Binder 线程收到调度后，会转给 `ActivityThread.H`，最终在主线程执行。

---

## 11. ATMS 与 AMS 如何配合启动进程

ATMS 不单独管理所有进程状态。目标 Activity 所在进程不存在时，需要 AMS/ProcessList 支撑。

### 11.1 目标进程已存在

```text
ATMS 找到目标 ActivityRecord
  └─ 目标 ProcessRecord 已有 IApplicationThread
       └─ realStartActivityLocked
            └─ ClientTransaction 发给 App
```

这种情况下不需要 Zygote fork，启动会更快。

### 11.2 目标进程不存在

```text
ATMS 准备启动 Activity
  └─ 发现目标进程不存在
       └─ 请求 AMS 启动进程
            └─ ProcessList.startProcessLocked
                 └─ ZygoteProcess.start
                      └─ fork 新 App 进程
```

新进程起来后：

```text
ActivityThread.main
  └─ ActivityThread.attach(false)
       └─ AMS.attachApplication
            ├─ 绑定 Application
            ├─ 安装 Provider
            └─ 通知 ATMS 继续启动等待中的 Activity
```

这就是 AMS/ATMS 配合最关键的地方：

- ATMS 知道“要启动哪个 Activity，以及它应该放在哪个 Task”。
- AMS 知道“目标应用进程是否存在，如何启动进程，进程状态如何维护”。
- 新进程 attach 后，AMS/ATMS 再继续完成 Activity 生命周期下发。

### 11.3 冷启动慢为什么两边都要看

冷启动慢可能卡在 ATMS，也可能卡在 AMS：

| 卡点 | 更偏哪个服务 |
|---|---|
| Intent 解析、task 选择、pause 前一个 Activity | ATMS |
| fork 进程、ProcessRecord、bindApplication | AMS |
| Provider 安装、Application.onCreate | AMS 调度 + App 进程执行 |
| launch/resume 生命周期事务 | ATMS 调度 + App 进程执行 |
| 窗口显示、首帧 | WMS + App 渲染 |

---

## 12. ATMS 与 PMS、WMS 的配合

### 12.1 ATMS 问 PMS：目标组件是谁

启动 Activity 时，ATMS 需要 PMS 提供事实：

- Intent 能解析到哪个 `ActivityInfo`。
- 目标 Activity 是否 exported。
- 目标 Activity 需要什么 permission。
- 目标 package 当前 user 下是否 installed/enabled。
- 调用方是否能看见目标 package。

PMS 回答“这个组件是什么、能不能被访问”，ATMS 决定“如果能访问，怎么启动”。

### 12.2 ATMS 和 WMS：Activity 不是窗口，但它需要窗口

Activity 是应用组件，窗口是显示实体。Activity 的生命周期和窗口显示强相关，但不是一回事。

```text
ATMS
  管 ActivityRecord、Task、resume/pause

WMS
  管 WindowState、DisplayContent、焦点、Surface、布局
```

当 Activity resume 后，App 会添加窗口；WMS 负责把窗口组织到 display 上，处理焦点、输入、动画和可见性。ATMS 需要和 WMS 协作判断 Activity 是否可见、是否应该 stop、哪个 Activity 是 top resumed。

---

## 13. 多窗口、多屏与 Display 调度

ATMS 变重要的一个原因，就是 Android 支持越来越复杂的窗口和显示形态：分屏、自由窗口、画中画、桌面模式、车载多屏。

### 13.1 多屏启动

App 或系统可以通过 `ActivityOptions` 指定 display：

```java
ActivityOptions options = ActivityOptions.makeBasic();
options.setLaunchDisplayId(displayId);
context.startActivity(intent, options.toBundle());
```

ATMS 会检查：

- display 是否存在。
- 调用方是否有权限启动到该 display。
- 目标 Activity 是否支持该 display/windowing mode。
- 是否有合适的 TaskDisplayArea 和 RootTask。
- 当前 user 是否允许在该 display 启动。

### 13.2 多窗口生命周期

多窗口下，Activity 生命周期不能再简单理解为“只有一个前台 Activity”。

可能出现：

- 多个 Activity 同时 visible。
- 只有一个 top resumed Activity 接收最主要焦点。
- 某些版本和设备支持 multi-resume。
- 可见但非焦点 Activity 可能处于 resumed 或 paused，取决于版本和窗口模式。

这解释了为什么分屏/车载多屏场景下，`onPause()`、`onStop()` 的触发时机可能和全屏手机不同。

---

## 14. 后台启动 Activity 限制

App 侧常见现象：后台进程调用 `startActivity()`，但界面没有弹出来。

Android 为了防止后台应用打扰用户，逐步收紧后台启动 Activity。

### 14.1 ATMS 会看什么

常见判断因素：

- 调用方是否在前台。
- 调用方是否有可见窗口。
- 调用方是否最近和用户交互。
- 是否通过 PendingIntent 等用户可预期路径启动。
- 是否系统 uid、设备管理、默认电话、闹钟、导航等特殊角色。
- 是否拥有特定权限或白名单。

### 14.2 App 侧替代方案

- 使用通知，引导用户点击进入。
- 使用全屏 Intent，仅适合来电、闹钟等强打扰场景。
- 使用 PendingIntent，让启动和用户动作绑定。
- 对系统/车载应用，通过明确权限或系统策略放行。

### 14.3 三方系统为什么常改这里

车机、行业设备经常需要后台拉起：电话、倒车、导航、语音助手、告警页面。修改时应该在 ATMS 启动限制策略处集中处理，并记录 caller、target、userId、displayId、reason。

不要全局放开后台启动，否则任何后台 App 都能弹窗，安全和体验都会失控。

---

## 15. 常见问题与排查方法

### 15.1 Activity 没启动

常见原因：

- Intent 解析不到。
- 目标 Activity 未 exported。
- 缺少 permission。
- 后台启动被限制。
- userId/displayId 不对。
- Activity disabled 或 package disabled。

排查：

```bash
adb shell am start -W -n package/.Activity
adb shell dumpsys activity starter
adb shell dumpsys activity activities
adb logcat -b all | grep -i "ActivityTaskManager\|ActivityManager\|Permission Denial\|Background activity"
```

### 15.2 Activity 复用了旧实例

看这些：

- Manifest 里的 `launchMode`。
- Intent flags。
- `taskAffinity`。
- 当前是否已有同 component ActivityRecord。
- 是否走了 `onNewIntent()`。

排查：

```bash
adb shell dumpsys activity activities | grep -i "Hist\|Task\|Run"
```

### 15.3 返回栈混乱

常见原因：

- 乱用 `NEW_TASK`。
- Activity 设置了特殊 `taskAffinity`。
- `CLEAR_TOP` 清理了中间页面。
- Launcher 或通知启动路径和 App 内部启动路径 flags 不一致。
- 多窗口/多屏导致 Task 在不同容器中。

### 15.4 启动慢或黑屏

看链路上的每一段：

- 前一个 Activity 是否 pause 慢。
- 目标进程是否冷启动。
- `bindApplication` 是否慢。
- Provider 是否安装慢。
- Activity `onCreate()` 是否慢。
- 首帧绘制是否慢。
- WMS 是否等待窗口或动画。

排查：

```bash
adb shell am start -W -n package/.Activity
adb logcat -b all | grep -i "Displayed\|ActivityTaskManager\|WindowManager"
```

Perfetto 更适合分析启动慢，因为它能同时看到 system_server、App 主线程、Binder、渲染线程和 CPU 调度。

### 15.5 多屏启动失败

常见原因：

- displayId 不存在。
- 调用方无权启动到目标 display。
- 目标 Activity 不支持该窗口模式。
- userId 和 display 所属用户不匹配。
- 系统定制的 display 白名单拦截。

排查：

```bash
adb shell dumpsys display
adb shell dumpsys activity activities
adb shell dumpsys window displays
adb logcat -b all | grep -i "launchDisplay\|TaskDisplayArea\|ActivityTaskManager"
```

---

## 16. 第三方系统常见修改点

### 16.1 后台启动白名单

需求：导航、电话、倒车、语音、告警等后台拉起界面。

建议改法：

- 在 ATMS 后台启动限制判断处集中处理。
- 白名单基于签名、uid、role、明确 component 或系统能力。
- 记录放行原因。
- 同时校验 userId、displayId、锁屏状态。

风险：全局放开会导致任意后台弹窗。

### 16.2 多屏启动策略

需求：车载中控、仪表、后排屏指定显示不同页面。

建议改法：

- 优先使用 `ActivityOptions.setLaunchDisplayId()`。
- 在 TaskDisplayArea / display 选择策略处集中定制。
- 建立 caller-target-display 白名单。
- 统一处理 Launcher、通知、语音、系统服务启动路径。

风险：只改某一个入口会导致不同启动路径行为不一致。

### 16.3 Task 清理和返回栈策略

需求：行业设备希望回到主页时清空业务页面，或某些页面永远单实例。

建议改法：

- 优先通过 Manifest launchMode、Intent flags、任务栈 API 解决。
- Framework 定制要集中在 ActivityStarter/Task 复用策略。
- 保留 dumpsys 可见状态，方便排查返回栈。

风险：随意清 Task 会破坏用户返回预期，也可能影响最近任务。

### 16.4 锁屏和特殊场景启动

需求：来电、闹钟、倒车、紧急告警在锁屏上显示。

建议改法：

- 区分普通后台启动和锁屏上显示。
- 使用官方窗口/Activity 属性，例如 showWhenLocked、turnScreenOn。
- 系统定制要验证息屏、锁屏、多用户、驾驶安全。

### 16.5 Activity 启动性能优化

需求：点击图标或切换页面更快。

可能修改：

- 启动路径日志和 trace 增强。
- 预启动或预热关键进程。
- 调整动画和窗口等待策略。
- 优化系统服务锁竞争。

风险：预启动会增加内存，绕过窗口等待可能造成黑屏或闪屏。

---

## 17. 读源码的推荐路线

### 17.1 从 App startActivity 入口读

```text
Activity.startActivity
Activity.startActivityForResult
Instrumentation.execStartActivity
ActivityTaskManager.getService().startActivity
ActivityTaskManagerService.startActivity
```

### 17.2 读 ActivityStarter

```text
ActivityStartController.obtainStarter
ActivityStarter.execute
ActivityStarter.executeRequest
ActivityStarter.startActivityUnchecked
ActivityStarter.startActivityInner
```

重点看：Intent 解析、权限检查、background start、flags、launchMode、task 选择。

### 17.3 读 Task 和 ActivityRecord

```text
RootWindowContainer
TaskDisplayArea
RootTask
Task
ActivityRecord
```

重点看：Activity 属于哪个 Task，Task 属于哪个显示区域，top Activity 如何计算。

### 17.4 读生命周期事务

```text
ActivityTaskSupervisor.realStartActivityLocked
ClientLifecycleManager.scheduleTransaction
ClientTransaction
LaunchActivityItem / ResumeActivityItem / PauseActivityItem
ApplicationThread.scheduleTransaction
ActivityThread.TransactionExecutor
```

重点看：system_server 如何把生命周期调度发给 App 主线程。

### 17.5 读 ATMS/AMS 协作

```text
ATMS 决定启动 Activity
AMS / ProcessList 启动进程
ActivityThread.attach
AMS.attachApplication
ATMS 继续 realStartActivityLocked
```

重点看：目标进程不存在时，Activity 启动如何挂起等待进程 attach。

---

## 18. 关键源码路径速查

| 内容 | 路径 |
|---|---|
| ATMS 主服务 | `frameworks/base/services/core/java/com/android/server/wm/ActivityTaskManagerService.java` |
| ATMS AIDL | `frameworks/base/core/java/android/app/IActivityTaskManager.aidl` |
| App 侧 ActivityTaskManager | `frameworks/base/core/java/android/app/ActivityTaskManager.java` |
| Activity 启动器 | `frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java` |
| 启动控制器 | `frameworks/base/services/core/java/com/android/server/wm/ActivityStartController.java` |
| Activity 启动协调 | `frameworks/base/services/core/java/com/android/server/wm/ActivityTaskSupervisor.java` |
| Activity 记录 | `frameworks/base/services/core/java/com/android/server/wm/ActivityRecord.java` |
| Task 记录 | `frameworks/base/services/core/java/com/android/server/wm/Task.java` |
| 根窗口容器 | `frameworks/base/services/core/java/com/android/server/wm/RootWindowContainer.java` |
| Display 区域 | `frameworks/base/services/core/java/com/android/server/wm/TaskDisplayArea.java` |
| 生命周期发送 | `frameworks/base/services/core/java/com/android/server/wm/ClientLifecycleManager.java` |
| 生命周期事务 | `frameworks/base/core/java/android/app/servertransaction/` |
| App 主线程 | `frameworks/base/core/java/android/app/ActivityThread.java` |
| Instrumentation | `frameworks/base/core/java/android/app/Instrumentation.java` |
| AMS 主服务 | `frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java` |
| 进程管理 | `frameworks/base/services/core/java/com/android/server/am/ProcessList.java` |
| PMS 主服务 | `frameworks/base/services/core/java/com/android/server/pm/PackageManagerService.java` |
| WMS 主服务 | `frameworks/base/services/core/java/com/android/server/wm/WindowManagerService.java` |

---

## 19. 一图总结

```text
App 进程
  Activity.startActivity(intent)
        │
        ▼ Binder：IActivityTaskManager
system_server
  ┌───────────────────────────────────────────────┐
  │ ATMS：ActivityTaskManagerService              │
  │                                               │
  │ 解析启动请求                                  │
  │ 判断权限 / exported / 后台启动                │
  │ 处理 launchMode / flags / taskAffinity        │
  │ 查找或创建 Task / ActivityRecord              │
  │ 选择 display / windowing mode                 │
  │ 调度 pause / launch / resume                  │
  └───────────────┬───────────────────────────────┘
                  │
        ┌─────────┼──────────────────────────┐
        │         │                          │
        ▼         ▼                          ▼
      PMS        AMS                        WMS
  组件/权限   进程启动/OOM/ANR          窗口/焦点/显示
        │         │                          │
        └─────────┼──────────────────────────┘
                  │ Binder：IApplicationThread
                  ▼
App 进程
  ActivityThread / TransactionExecutor
        │
        ├─ LaunchActivityItem -> onCreate/onStart
        ├─ ResumeActivityItem -> onResume
        ├─ PauseActivityItem  -> onPause
        └─ StopActivityItem   -> onStop
```

---

## 小结

- **ATMS 是 Activity 和 Task 调度中心**，主要负责 `startActivity`、任务栈、启动模式、多窗口、多屏和生命周期事务。
- **ATMS 与 AMS 是协作关系**：ATMS 决定 Activity 怎么启动和放哪，AMS 负责进程启动、进程状态、OOM、ANR 等支撑。
- **Activity 启动不是直接创建对象**，而是 App Binder 到 ATMS，ATMS 解析和决策，必要时让 AMS 启动进程，再通过 `ClientTransaction` 回调 App 主线程执行生命周期。
- **Task 不是简单的 Java 栈**，而是 system_server 中由 `Task`、`ActivityRecord`、`RootWindowContainer`、`TaskDisplayArea` 等对象组织起来的窗口/任务结构。
- **launchMode 和 Intent flags 是 ATMS 决策任务栈的核心输入**，很多 `onNewIntent()`、返回栈异常、旧页面复用问题都要从这里看。
- **多窗口、多屏让 Activity 生命周期更复杂**，可见、焦点、resumed、top resumed 不再总是一个简单状态。
- **三方系统改 ATMS 时要和 AMS/PMS/WMS 一起考虑**，尤其是后台启动、多屏启动、Task 策略和锁屏场景，不能只看单个 App 是否能拉起页面。

如果只记一个核心模型：

> ATMS 管 Activity 和 Task 的“位置与生命周期”，AMS 管应用进程的“存在与重要性”；一次 Activity 启动，是 ATMS 做页面调度，AMS 做进程支撑，PMS 提供组件事实，WMS 负责窗口显示。