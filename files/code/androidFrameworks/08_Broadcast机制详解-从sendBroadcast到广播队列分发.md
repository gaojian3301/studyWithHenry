# Broadcast 机制详解：从 sendBroadcast 到广播队列分发

> 广播（Broadcast）是 Android 里"一对多"的事件分发机制。它表面只是 `sendBroadcast()` 一行，背后却要穿过 `ContextImpl` → `ActivityManagerService`（AMS）→ 广播队列（BroadcastQueue）→ 跨进程 dispatch → 各 App 的 `BroadcastReceiver.onReceive()`。整条链路还夹着权限校验、PMS 静态接收者解析、前台/后台队列的分流、多用户隔离、后台拉起限制和 ANR 超时。
>
> 本篇定位：**只讲广播本身**。AMS 内部两条生命周期链路（应用启动、进程管理）的细节在 `05_AMS` / `06_AMS` 篇；通知的展示链路在 `17_通知` 篇。本篇不展开它们，只在该引用处指路。

---

## 目录

1. [开场：广播要回答的三个问题](#1-开场广播要回答的三个问题)
2. [注册与接收者模型：动态、静态与合并集合](#2-注册与接收者模型动态静态与合并集合)
3. [sendBroadcast 主链路：从 AMS 到广播队列](#3-sendbroadcast-主链路从-ams-到广播队列)
4. [广播队列：前台/后台分流、并发与超时](#4-广播队列前台后台分流并发与超时)
5. [有序广播 ordered broadcast：串行、合并与拦截](#5-有序广播-ordered-broadcast串行合并与拦截)
6. [Sticky 广播的兴衰与替代](#6-sticky-广播的兴衰与替代)
7. [Manifest 隐式广播限制（Android 8）与导出声明（Android 14）](#7-manifest-隐式广播限制android-8与导出声明android-14)
8. [广播与进程启动：冷启动代价与广播 ANR](#8-广播与进程启动冷启动代价与广播-anr)
9. [权限与安全：发送/接收权限、受保护广播与本地广播](#9-权限与安全发送接收权限受保护广播与本地广播)
10. [广播 vs 其他事件机制：什么时候用什么](#10-广播-vs-其他事件机制什么时候用什么)
11. [车机/平台视角：自定义系统广播、开机时序与多用户](#11-车机平台视角自定义系统广播开机时序与多用户)
12. [dumpsys 解读：我的广播去哪了](#12-dumpsys-解读我的广播去哪了)
13. [常见问题排查表](#13-常见问题排查表)
14. [读源码路线](#14-读源码路线)
15. [源码路径速查表 + 一图总结 + 关联阅读](#15-源码路径速查表--一图总结--关联阅读)

---

## 1. 开场：广播要回答的三个问题

生活类比先铺垫直觉：**广播像小区里的"大喇叭广播"**。物业（发送方）对着喇叭喊一句"停水了"，所有在小区里、且订阅了这个频道（注册了对应 action）的住户（接收者）都会听到。但真实系统比大喇叭复杂，它必须回答三个问题：

**问题一：怎么把"一个事件"同时告诉很多接收者？**
发送方只调用一次 `sendBroadcast(intent)`，AMS 会把它复制成多份，分发给所有匹配（matching）的接收者。匹配的依据是 `IntentFilter` 里的 action / category / data（URI 与 MIME）。注意：**一次发送 ≠ 一次调用链**——AMS 内部要为 N 个接收者分别走 N 条 dispatch 分支。

**问题二：谁有权听（谁能收）？**
三层 gate：
- 过滤层：`IntentFilter` 不匹配的直接不过滤掉；
- 权限层：发送方可要求 `permission`，接收方必须持有该权限才能收；接收方注册时也可要求发送方持有权限；
- 导出层（Android 13+ 动态注册必填、Android 14 静态必声明）：未显式声明导出的接收者，别的 UID 发不过来。

**问题三：延迟与丢失谁负责？**
- 延迟：广播入队后按队列调度，前台广播优先、后台广播限速；某接收者是死进程还要先冷启动，时间就长。
- 丢失：动态接收者的进程已死 → 收不到；进程在广播飞行途中被杀 → 该接收者这条被丢弃；有序广播中途 abort → 后续全丢。AMS 不保证"一定送达"，它只保证"按规则尽力分发"。

### 1.1 全链路全景（sendBroadcast → onReceive）

```text
App 进程                                      system_server                                 目标 App 进程
─────────────                                ──────────────                                ─────────────
ContextImpl.sendBroadcast(intent)
   │ (Binder: IActivityManager)
   ▼
                                          AMS.broadcastIntentWithFeature()
                                            ├─ verifyBroadcastLocked()   权限/flag/uid 校验
                                            ├─ 收集动态接收者: mRegisteredReceivers (ReceiverList)
                                            ├─ 收集静态接收者: PMS.resolveReceiver()
                                            ├─ 合并 → 构造 BroadcastRecord
                                            └─ enqueueBroadcastLocked()
                                                 ├─ 前台广播 → mFgBroadcastQueue
                                                 └─ 后台广播 → mBgBroadcastQueue
                                                                        │
                                                                        ▼ scheduleBroadcastsLocked()
                                                          processNextBroadcastLocked()
                                                            ├─ 并行广播: 一次性给所有匹配者 dispatch
                                                            └─ 有序广播: 逐个 dispatch, 等 finishReceiver
                                                                        │ (Binder: IApplicationThread)
                                                                        ▼
                                                                   ActivityThread.scheduleReceiver()
                                                                     └─ 实例化 BroadcastReceiver
                                                                     └─ onReceive()  ← 主线程
                                                                        └─ 动态: 立即结束
                                                                           有序: finishReceiver() 回 AMS 调度下一个
```

### 1.2 怎么读这篇

- 想先建立模型 → 第 2 章（接收者模型）、第 4 章（队列）。
- 想追发送链路 → 第 3 章（sendBroadcast 主链路）、第 14 章（读源码路线）。
- 遇到收不到 / 卡住 / ANR → 第 13 章排查表、第 12 章 dumpsys。
- 做车机系统广播 / 开机广播 → 第 11 章。
- 版本差异集中在：第 2 章（13+ receiver flags）、第 7 章（8.0 隐式广播、14 导出声明）、第 9 章（受保护广播）。

---

## 2. 注册与接收者模型：动态、静态与合并集合

广播的"听众名单"来自两个来源，性质完全不同。理解这一点是理解后面所有分发逻辑的前提。

### 2.1 动态注册：LoadedApk 侧 + ReceiverDispatcher

运行时在进程内注册。代码在 App 侧：

```java
// 最常见写法（Android 13+ 必须带 flags，见 2.4）
IntentFilter filter = new IntentFilter("com.xxx.MY_ACTION");
BroadcastReceiver r = new MyReceiver();
// Android 13 (API 33) 起第三个参数 context 用 RECEIVER_NOT_EXPORTED / EXPORTED
registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED);
```

注册时 App 侧做了什么（都在 `LoadedApk` / `ContextImpl` 内）：

```text
ContextImpl.registerReceiverInternal()
   ├─ 构造 LoadedApk.ReceiverDispatcher   // 持有一个 InnerReceiver (IIntentReceiver Stub)
   ├─ 通过 Binder 把 InnerReceiver + IntentFilter 交给 AMS
   └─ AMS 侧登记到 mRegisteredReceivers:
          ReceiverList  (一个进程在 AMS 侧的所有动态接收者集合)
             └─ BroadcastFilter (单个 IntentFilter 的 AMS 表达)
```

要点：
- **动态接收者活在进程的"内存名单"里**。`InnerReceiver` 是 Binder Stub，AMS 通过它跨进程回调 dispatch。进程一死，Binder 断连，AMS 自动从 `ReceiverList` 里移除——所以死进程收不到动态广播。
- `ReceiverDispatcher` 还负责把 `onReceive()` 切回主线程（通过 `Handler`），所以你在 `onReceive` 里能直接碰 UI 相关对象。
- 动态接收者**没有跨进程冷启动能力**：发广播时它对应的进程必须活着，否则直接跳过。

### 2.2 静态注册：PMS 扫描 manifest 的 receiver 记录

写在 `AndroidManifest.xml` 里，安装时由 `PackageManagerService`（PMS）扫描 `<receiver>` 并记录进 `PackageParser` 解析出的 `Activity（receiver 类型）`结构。代码：

```xml
<receiver android:name=".BootReceiver"
          android:exported="true"            <!-- Android 12+ 建议显式; Android 14 强制要求(见 7.2) -->
          android:permission="android.permission.SOME_PERM">  <!-- 可选: 要求发送方持有权限 -->
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

静态接收者的特点：
- **它不在任何进程的"内存名单"里**，只存在于 PMS 的包数据库。发送广播时 AMS 调 `PMS.resolveReceiver()`（早期）/ `queryIntentReceivers()` 现查。
- 静态接收者**可以拉起死进程**：如果匹配到的 receiver 所在进程没启动，AMS 会先 `startProcessLocked()` 把它拉起来，再 dispatch。这是"一个广播拉起 N 个进程"功耗问题的根源（详见第 8 章）。
- Android 8.0 起，大量**隐式**静态广播被禁止（详见第 7 章），但显式广播和少数白名单 action 仍可静态接收。

### 2.3 两套集合怎么合流

一次 `sendBroadcast` 时，AMS 需要"谁该收"的完整名单，步骤是：

```text
broadcastIntentLocked()
   ├─ 查动态: 遍历 mRegisteredReceivers 里的 BroadcastFilter, 用 IntentResolver 匹配 action/category/data
   ├─ 查静态: PMS.queryIntentReceivers(intent, ...) 返回 ResolveInfo 列表
   ├─ 合并 + 去重(同一组件既动态又静态 → 按 uid/component 去重, 不重复分发)
   ├─ 按 ordered 与否分桶:
   │     unordered → 并行分支集合 (parallel)
   │     ordered   → 按优先级排序后的串行列表 (ordered)
   └─ 写入 BroadcastRecord.receivers 字段
```

记忆点：**动态来自"活进程内存"，静态来自"PMS 磁盘/数据库"，发送时刻才合并**。这也解释了为什么动态接收者"进程死即失效"而静态接收者"能拉起进程"。

### 2.4 Android 13+ 动态注册必须带 receiver flags（安全）

从 **Android 13（API 33）** 起，`Context.registerReceiver()` 的 `flags` 参数从"可选"变成"必填语义"。两个关键 flag：

| flag | 含义 | 不写会怎样 |
|---|---|---|
| `RECEIVER_NOT_EXPORTED` | 这个动态接收者**只接收来自本应用自身（同 UID）**的广播，别的 UID/应用发不过来 | 不设这个又没设 EXPORTED，在 13+ 上部分重载会抛异常或默认不安全 |
| `RECEIVER_EXPORTED` | 接收来自**其他应用**的广播（需要时才设） | 设了就对外暴露，需配合权限收紧 |

为什么加这个？历史上动态注册默认"谁都能发给我"，恶意应用可不停给你发广播让你反复冷启动 / 做 DOS。13+ 把"是否可被外部发送"变成**显式声明**，默认偏保守。

```java
// 推荐: 只收自己发的(如 App 内多进程通信)
registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED);

// 必须收外部广播时(如监听系统广播)才用 EXPORTED, 并尽量叠加权限
registerReceiver(r, filter, Context.RECEIVER_EXPORTED);
```

> 「**Android 视角**」：`RECEIVER_EXPORTED/NOT_EXPORTED` 实际落到 `ContextImpl` 后，会转成 `IntentFilter` 的 `setPriority` 之外的"可见性"标记，并最终影响 AMS `IntentResolver` 匹配时是否允许跨 UID 命中。具体字段名随版本微调，看 `LoadedApk` / `BroadcastFilter` 的 `instantAppVisibility` / `visibility` 相关位即可。

---

## 3. sendBroadcast 主链路：从 AMS 到广播队列

本章把第 1.1 全景里 system_server 这一段拆开。注意 AMS 的两条生命周期细节（应用启动 `startProcessLocked`、进程记录 `ProcessRecord`）在 `05_AMS` / `06_AMS` 篇，本篇只在用到处指路。

### 3.1 入口与分流

```text
ContextImpl.sendBroadcast(Intent, permission=null)
   │ Binder.transact(IActivityManager.broadcastIntent)
   ▼
ActivityManagerService.broadcastIntentWithFeature(callingPackage, intent, ...)
   └─ broadcastIntentLocked(...)                      // 真正干活的大函数
         ├─ 1) verifyBroadcastLocked(intent)          // 校验: 保护广播/空 action/超大 extra
         ├─ 2) 处理 flag: FLAG_RECEIVER_FOREGROUND / REPLACE_PENDING / REGISTERED_ONLY ...
         ├─ 3) 后台限制: 若调用方在后台且发隐式广播 → 部分被拦(见第 7 章)
         ├─ 4) 收集 receivers (动态 + 静态, 见 2.3)
         ├─ 5) 构造 BroadcastRecord
         └─ 6) enqueueBroadcastLocked(record)
                  ├─ isForeground()? → mFgBroadcastQueue.enqueueBroadcastLocked()
                  └─ 否则         → mBgBroadcastQueue.enqueueBroadcastLocked()
```

### 3.2 verifyBroadcastLocked：第一道闸门

`verifyBroadcastLocked` 做几件关键事（不写死签名，按职责记）：

- **保护广播检查（protected-broadcast）**：如果 intent 的 action 属于系统声明的 `protected-broadcast`，非系统 UID 发送会被直接拒绝并抛 `SecurityException`（详见第 9 章）。这是防止"伪造系统广播"的核心机制。
- **Intent 合法性**：action 不能为 null 的某些组合、extra 体积不能超上限（`Binder` 事务缓冲区限制，约 1MB 量级，跨进程大数据请用别的通道）。
- **flag 规范化**：把调用方身份、是否前台、是否有序等折算进 `BroadcastOptions` / record 字段。

### 3.3 ReceiverList / BroadcastRecord：队列里的最小单位

- `BroadcastRecord`：一次 `sendBroadcast` 的**完整上下文**——intent、调用方 uid/pid、permission、所有 receivers 列表（已排序）、当前分发下标 `nextReceiver`、有序标志、超时计时等。它是队列里被调度的"任务"。
- `ReceiverList`：**进程维度**的动态接收者容器；一个 `ProcessRecord` 对应一个 `ReceiverList`，里面挂多个 `BroadcastFilter`。AMS 收动态广播时从这里匹配。
- `BroadcastFilter`：一个 `IntentFilter` 在 AMS 侧的反序列化表达，记录 action/category/data 以及归属的 `ReceiverList`（从而知道该往哪个进程 dispatch）。

```java
// 概念结构(字段随版本增减, 看 BroadcastRecord.java)
class BroadcastRecord {
    Intent intent;
    String requiredPermission;          // 发送方要求的接收权限
    List receivers;                     // 合并后的接收者(动态+静态), 有序广播已排序
    int nextReceiver;                   // 当前分发到哪个(有序广播靠它推进)
    boolean ordered;
    long enqueueClockTime;              // 入队时刻, 用于超时判定
    // ...
}
```

### 3.4 入队后的"谁来跑"

入队并不立刻分发。`enqueueBroadcastLocked` 只是把 record 放进队列的 `mParallelBroadcasts`（并行，无序）或 `mOrderedBroadcasts`（串行，有序）列表，然后 `scheduleBroadcastsLocked()` 通过 Handler 异步触发 `processNextBroadcastLocked()`。

> 为什么要异步？`broadcastIntent` 是 Binder 调用，AMS 不能在被调用线程里同步把广播派发给成百个接收者（会卡死 Binder 线程）。入队 + Handler 让分发回到 AMS 自己的广播线程上跑，这也是"前台队列 / 后台队列各有一条线程"的由来（下一章）。

---

## 4. 广播队列：前台/后台分流、并发与超时

### 4.1 两个逻辑队列

AMS 维护**两个逻辑广播队列**（注意是"逻辑队列"，不是物理两个线程这么简单）：

| 队列 | 触发条件 | 速度/优先级 | 典型场景 |
|---|---|---|---|
| **foreground queue**（前台队列） | 发送方在前台、或带 `FLAG_RECEIVER_FOREGROUND`、或系统关键广播 | 高优先级，尽量快派发 | 用户正在交互时发出的广播、部分系统广播 |
| **background queue**（后台队列） | 发送方在后台、普通广播 | 限速（串行节流），慢 | 后台应用发出的广播、部分隐式广播 |

> 「**Android 视角**」历史上 `BroadcastQueue` 直接持有 `mParallelBroadcasts` 和 `mOrderedBroadcasts` 两个列表，并各有一条 Handler 线程（fg/bg 各一）。**Android 13 起引入了 modern 实现（`BroadcastQueueModernImpl` + 每进程 `BroadcastProcessQueue`）**，把"按进程"再细分队列，目标是对后台广播做更细的节流与公平性控制。本篇用"两类（前台/后台）队列"这个稳定的心智模型描述；**具体常量（并发数、超时阈值）以你正在读的 AOSP 版本中 `ActiveServices` / `BroadcastQueue` / `BroadcastConstants` 里的常量为准**，下面给的是量级与方向，不要照抄数字。

### 4.2 超时（ANR 阈值）的量级

广播 ANR 的超时不是"广播整体"的超时，而是"**单个接收者 onReceive 必须在限定时间内完成**"的超时。量级（务必以本版本常量为准）：

- **前台广播**：接收者 `onReceive` 超时在 **10 秒量级**（早期版本 `BROADCAST_FG_TIMEOUT = 10s`）。
- **后台广播**：超时在 **60 秒量级**（`BROADCAST_BG_TIMEOUT = 60s` 量级）——后台更宽松，因为本就慢。
- 对于**有序广播**，常把"整个有序链"的上限也纳入考量，部分版本/场景前台有序链总超时在 **20 秒量级**范围，具体看 `BroadcastConstants` 中 `TIMEOUT` 系列常量。

> 再次强调：上面是"量级"。不同 Android 大版本、甚至同一版本不同设备厂商都可能改常量。排查 ANR 时**先 `dumpsys activity broadcasts` 看实际 timeout 配置**，再下结论。第 8 章会展开 ANR 触发链路。

### 4.3 队列调度与"广播风暴"

`processNextBroadcastLocked()` 的调度逻辑要点：

- **并行广播（unordered）**：从 `mParallelBroadcasts` 取出 record，对 `receivers` 列表里**每一个接收者同时 dispatch**（各自的 Binder 调用），互不等待。所以无序广播"发得快"，但接收者多时瞬时 Binder 压力巨大。
- **有序广播（ordered）**：从 `mOrderedBroadcasts` 取 record，按 `receivers` 排序后的顺序，**一次只 dispatch 一个**，等它 `finishReceiver()` 回来才推进 `nextReceiver` 到下一个（详见第 5 章）。

**广播风暴（broadcast storm）**的表现：短时间内海量广播（例如一个系统事件触发几十个应用各自又发广播）涌入同一队列，每个又带大量接收者，导致：
- system_server 的广播线程长期 100%；
- 大量接收者进程被拉起（静态），内存/CPU 飙升；
- 正常交互广播被排在后面，出现"按按钮半天没反应"的卡顿。

平台侧（车机 ROM 厂商）常做的缓解：收紧静态隐式广播（第 7 章）、给后台队列加更狠的节流、白名单化开机广播（第 11 章）。

---

## 5. 有序广播 ordered broadcast：串行、合并与拦截

普通广播是"群发"，有序广播是"传话筒接力"——前一个讲完，后一个才接。

### 5.1 串行分发回路

```text
processNextBroadcastLocked()
   └─ 取有序 record, 看 nextReceiver 指向 Receiver[i]
        └─ schedule broadcast 给 Receiver[i] (Binder)
             └─ 目标进程 onReceive()
                  ├─ 可调用: getResultData()/getResultExtras()/setResultData()... 改写结果
                  ├─ 可调用: abortBroadcast()  // 截断, 后面全不收
                  └─ finishReceiver() (Binder 回 AMS)
                       └─ AMS: record.nextReceiver++ → 调度 Receiver[i+1]
```

关键点：**前一个不 `finishReceiver()`，后面永远等**。这就解释了为什么"有序广播卡住不往下传"是高频故障（排查表第 13 章）。

### 5.2 结果合并：resultData / resultExtras / resultReceiver

有序广播允许"中间人改结果"，后一个接收者能看到前一个改了什么：

- `setResultData(String)` / `getResultData()`：传递一段字符串结果（经典用途：短信拦截改 body、来电改显示）。
- `setResultExtras(Bundle)` / `getResultExtras()`：传递结构化 extras。
- `setResultCode(int)` / `getResultCode()`：传递结果码。
- `sendOrderedBroadcast(intent, permission, resultReceiver, ...)`：最后的 `resultReceiver` 是**所有接收者都处理完（或被 abort）后**，由初始发送方指定、在发送方进程回调的"终态接收者"，能拿到最终 `resultData/resultExtras`。

```java
// 发送方: 带终态 resultReceiver
sendOrderedBroadcast(intent, null,
        new ResultReceiver(),  // 全部处理完后在发送方进程回调 onReceive
        null, Activity.RESULT_OK, null, null);

// 中间接收者: 读取并改写
public void onReceive(Context c, Intent i) {
    String data = getResultData();          // 上一个接收者留下的
    setResultData(data + "-modified");      // 改写后传给下一个
    // 或者: abortBroadcast();  // 后面都收不到
}
```

### 5.3 abortBroadcast：截断链

调用 `abortBroadcast()` 的接收者之后，**剩余有序接收者全部收不到**这条广播。典型用途：最高优先级的接收者"已处理"，不想让低优先级的（如其他 App）再收到。注意：
- 只能用于**有序广播**；无序广播调用无效（也抛异常）。
- 高优先级系统接收者常用它做"拦截"（如拦截短信、拦截来电），这是有序广播相比无序广播最特殊的能力。

### 5.4 动态与静态 receiver 的顺序规则

有序广播按 `priority` 排序，规则要点：

- **动态注册**的优先级由 `IntentFilter.setPriority(int)` 设置，范围约 `-1000 ~ 1000`（以 `IntentFilter` 常量为准）。
- **静态注册**的优先级由 manifest 的 `<intent-filter android:priority="...">` 决定。
- 排序时：高 priority 先收；同 priority 时，**动态接收者通常排在静态接收者之前**（因为动态是"已活着的进程"，优先于还要拉起进程的静态）。具体先后以 `BroadcastQueue` 里 `sortReceivers` / 比较器逻辑为准，不同版本略有差异。
- **Android 8.0 后台限制的影响**：即使你给静态接收者设了高 priority，若它是"受限隐式广播"的静态接收者，系统在 8.0+ 会直接不把隐式广播分发给它（详见第 7 章）。换句话说，**8.0 后静态接收者的优先级在受限场景下"形同虚设"**，想保证收到得改动态注册或走白名单 action。

### 5.5 有序广播的代价

有序广播因为"串行 + 等 finish"，天然容易被单个慢接收者拖垮整条链。经验法则：
- 链上任何一个接收者 `onReceive` 做了 IO / 网络 / 重计算 → 全链变慢，且容易触发 ANR（第 8 章）。
- 不要把"高优先级 + 慢操作"放有序链头部。
- 能用无序广播就别用有序；有序只用于"确实需要拦结果/改结果"的场景。

---

## 6. Sticky 广播的兴衰与替代

### 6.1 什么是 Sticky 广播

Sticky（粘性）广播的语义是：**发出后，系统会"记住"这条广播和它携带的最后值；之后才注册接收者的人，一注册就能立刻收到这条"滞留"的广播**。典型 API 是旧版 `sendStickyBroadcast()` / `registerReceiver()` 时直接带回 sticky 值。

```java
// 历史写法(已废弃, 不要再用)
sendStickyBroadcast(intent);                       // 发出并被系统缓存
// 稍后某进程注册, 立刻能拿到上一条 sticky 值
Intent sticky = registerReceiver(receiver, filter); // 返回值即最近一次 sticky intent
```

直觉类比：**普通广播像喊一嗓子，没在场就听不到；sticky 像黑板上写了"今日停水"，后到的人一看黑板就知道**。

### 6.2 为什么被 deprecated（衰落原因）

- **隐私与泄露**：sticky 广播把"谁发过什么、带了什么数据"长期留在系统里，任何有相应权限（甚至无权限，取决于版本）的 App 都能 `registerReceiver` 一下把历史值读出来。系统级状态（电量、WiFi）被长期暴露。
- **状态陈旧与竞态**：sticky 只存"最后一个值"，多个发送方并发覆盖会产生读到的不是你期望的快照；且它和"当前真实状态"可能早已脱节。
- **被更干净的机制取代**：Android 逐步把系统 sticky 广播迁移为 `ContentProvider` / 系统服务查询 API（如电量用 `BatteryManager` 查询、网络用 `ConnectivityManager` 的 `getNetworkCapabilities` 查询），而非"广播+缓存"。

> 现状：**`sendStickyBroadcast()` 及相关 API 在较高 Android 版本已被标记为 `@Deprecated` 且对普通应用基本不可用**（系统 UID 仍可能用少量遗留 sticky）。新代码不要依赖它。

### 6.3 替代方案

| 旧需求 | 现代替代 |
|---|---|
| 想知道"最近一次系统状态"（电量/网络/时区） | 主动查询：`BatteryManager` / `ConnectivityManager.getNetworkCapabilities()` / `Locale` 查询，而不是等 sticky |
| App 内"后注册也能拿到上次事件" | 进程内事件总线（见第 10 章）或自己维护一个 `StateFlow` / 单例缓存 |
| 跨进程共享"最新值" | 用 `ContentProvider` + `registerContentObserver`，或系统服务暴露的查询接口 |

一句话：**sticky 想解决的是"状态化缓存"，而现代 Android 把这件事交给了"显式查询 API"和"状态化对象（如 `StateFlow`）"，而不是广播**。

---

## 7. Manifest 隐式广播限制（Android 8）与导出声明（Android 14）

这是平台/车机开发者改 ROM 时最容易踩、也最常"为了兼容放开"的坑。务必分清三件事的时间线。

### 7.1 Android 8.0 隐式广播限制（核心改动）

从 **Android 8.0（API 26）** 起，**大多数隐式广播（implicit broadcast）不能再通过 manifest 静态注册的 `<receiver>` 接收**。

- 隐式广播：Intent 未指定具体 component/package，只靠 action 匹配（如 `ACTION_BATTERY_CHANGED`、`ACTION_TIME_TICK`、`CONNECTIVITY_ACTION`）。
- 限制动机：**静态接收隐式广播会频繁拉起后台进程**（想一想：每换一次网络、每掉 1% 电，几十个 App 的进程被拉起来跑 `onReceive`），这对启动速度、内存、功耗是灾难。Android 8 的整机功耗/后台管控改革（Background Execution Limits）把这块一并收了。

```text
Android 8 之前: 隐式广播 ──┬─ 动态 receiver (活进程)  收得到
                           └─ 静态 receiver (manifest) 收得到 ← 会拉起死进程

Android 8 之后: 隐式广播 ──┬─ 动态 receiver (活进程)  收得到
                           └─ 静态 receiver (manifest) 大部分收不到 ← 被系统拦下
                                 例外: 白名单 action(见 7.2) / 显式广播 / 少数特殊广播
```

应对方式：
- 改用**动态注册**（进程活着时收）；进程死后自然不收，符合后台管控。
- 改用 `JobScheduler` / `WorkManager` 监听状态变化（系统级合并触发，不拉起进程）。
- 用**显式广播**（指定 package/component），显式广播不受此限制。
- 系统/特权应用走**白名单**（见 7.2）。

### 7.2 例外清单（白名单）与设计动机

并非所有隐式广播都被禁。系统保留了一批"确实需要在开机/关键节点通知所有应用"的广播，典型白名单 action 包括（以你版本 `Intent` / 兼容性文档为准，举例）：

- `BOOT_COMPLETED`（`android.intent.action.BOOT_COMPLETED`）：开机完成，闹钟/推送类应用必需。
- `LOCALE_CHANGED`：系统语言/区域变化。
- `MY_PACKAGE_REPLACED`：本应用被更新（注意是"本包"专属，不会拉起别人）。
- `UID_REMOVED` / `PACKAGE_FULLY_REMOVED` 等包管理事件（部分）。
- `TIME_SET` / `TIMEZONE_CHANGED` 等时间相关（部分版本豁免）。

设计动机：**白名单 = "宁可拉起进程也要保证送达"的极少数系统级事件**，其余一律交给动态注册/Job。作为 ROM 厂商，你可以通过系统配置新增白名单 action，但每加一个都要评估"会不会又回到 8.0 前那种广播拉起风暴"——第 11 章会讲车机侧的正确姿势。

### 7.3 Android 14 的 receiver 必须可导出声明

- **Android 12（API 31）** 起：manifest 的 `<receiver>` 若同时含 `<intent-filter>`，`android:exported` **强烈建议显式声明**，缺省会被 lint/build 警告。
- **Android 14（API 34）** 起收紧：`<receiver>` 含 intent-filter 时，**`android:exported` 必须显式声明**，否则安装/构建直接报错。原因与 13+ 动态 flags 同源——防止"默认导出"带来的越权接收。

```xml
<!-- 正确: 显式声明导出 -->
<receiver android:name=".MyReceiver" android:exported="false">
    <intent-filter><action android:name="com.xxx.INTERNAL" /></intent-filter>
</receiver>

<!-- 要收系统/外部广播(如 BOOT_COMPLETED)时 export 设为 true, 并尽量叠加 permission -->
<receiver android:name=".BootReceiver" android:exported="true"
          android:permission="android.permission.RECEIVE_BOOT_COMPLETED">
    <intent-filter><action android:name="android.intent.action.BOOT_COMPLETED" /></intent-filter>
</receiver>
```

记忆：**8.0 管"隐式静态收不收"，13/14 管"你能不能被外部发"**。两条规则正交，都得遵守。

---

## 8. 广播与进程启动：冷启动代价与广播 ANR

### 8.1 静态 receiver 触发冷启动的代价

当一个广播匹配到了**静态接收者，而该接收者所在进程还没启动**，AMS 会：

```text
processNextBroadcastLocked() 处理某静态 receiver
   └─ 发现目标 ProcessRecord 不存在 / 已死
        └─ startProcessLocked()                // 拉起新进程(详见 05/06_AMS 篇的应用启动链路)
             └─ 新进程 attach → 走 ActivityThread.main() → 绑定 Application
                  └─ 进程就绪后, 才真正 scheduleReceiver() → onReceive()
```

代价：
- **启动一个进程**要 fork zygote 子进程、加载 ART、初始化 Application、`onCreate`……量级在**数百毫秒到数秒**（低端车机/设备更慢）。
- **一条广播可能拉起 N 个进程**：比如 `BOOT_COMPLETED` 会同时匹配几十个 App 的静态 receiver，于是开机时几十个进程被同时拉起——这就是"开机慢、开机后几分钟才安静"的根因之一。
- 被拉起的进程如果 `onReceive` 完就没什么可干，很快又进缓存/被杀，纯属功耗浪费。

平台侧对策：开机广播白名单（只放行必要系统/签名应用）、延迟分发（开机后分批）、合并（见第 11 章）。

### 8.2 广播 ANR 的触发链路

`onReceive()` 运行在接收者进程的**主线程**。AMS 对每个被分发的接收者启动超时计时（阈值见第 4.2 量级）。若超时未 `finish`（无序广播 onReceive 返回即算完；有序广播要 `finishReceiver` 回 AMS），AMS 就对该接收者进程发起 **broadcast timeout ANR**。

```text
dispatch 给 Receiver[i]
   └─ AMS 启动超时计时(前台~10s / 后台~60s 量级)
        ├─ onReceive 及时返回(有序则 finishReceiver 回 AMS) → 取消计时, 正常
        └─ 超时仍未完成 → AppNotResponding, 弹 ANR / 杀进程
```

ANR 常见原因（也是第 13 章排查表的高频项）：
- `onReceive` 里做了网络请求、数据库大查询、文件 IO、重计算；
- 接收者是死进程，冷启动本身太慢（叠加 8.1 的代价）；
- 主线程等锁 / 等同步 Binder 调用（Binder 对端卡死）；
- **有序广播前一个接收者卡住**，导致后一个一直没被 dispatch（表面是"我的广播没到"，实际是"卡在前一个人"）；
- 后台队列虽超时宽松（60s 量级），但接收者若被 cgroup 节流（后台进程 CPU 受限），`onReceive` 实际跑得很慢也会超时。

原则：**`onReceive` 只做轻量工作**（更新一个变量、发条消息、起一个 `JobIntentService` / `WorkManager` / 前台服务去干重活）。有序广播尤其要快，因为它阻塞整条链。

### 8.3 进程被杀后 BroadcastRecord 的处置

分两种场景：
- **dispatch 之前进程就死**：动态接收者随 `ReceiverList` 移除，直接不分发；静态接收者若进程没了会被重新拉起（8.1），除非被后台限制拦下。
- **dispatch 途中 / 等待 finish 时进程被杀**：AMS 收到 `BinderDeath`，把该接收者标记为失败，有序广播会尝试跳过它推进到下一个（具体是否跳过、是否记超时，以 `BroadcastQueue` 的 `cleanupDisabledPackageReceiversLocked` / `processNextBroadcast` 中死亡处理逻辑为准）。简言之：**进程被杀的那一条广播分支被丢弃，其余分支按规则继续或被超时**。不会有"整条广播重试"的保证。

---

## 9. 权限与安全：发送/接收权限、受保护广播与本地广播

广播是跨应用/跨 UID 的公开信道，安全是设计重点。

### 9.1 发送权限：sendBroadcast(permission)

发送方可以要求"只有持有某权限的接收者才能收这条广播"：

```java
// 只有声明并授予了 "com.xxx.PRIV" 权限的接收者能收
sendBroadcast(intent, "com.xxx.PRIV");
```

- 权限在 manifest 里由 `<permission>` 定义，接收方需在 `<uses-permission>` 持有（或被签名/特权授予）。
- 系统广播常用此方法限制"谁能听我说"（例如某些系统事件只给系统/签名应用）。

### 9.2 接收权限：registerReceiver / manifest 的 permission

反过来，接收方可以要求"只有持有某权限的发送方才发得进来"：

```java
// 动态注册时要求发送方持有权限
registerReceiver(receiver, filter, "com.xxx.SEND_PERM", null, Context.RECEIVER_NOT_EXPORTED);
```

```xml
<!-- 静态: receiver 上声明 permission, 发送方必须持有 -->
<receiver android:name=".PrivReceiver" android:permission="com.xxx.SEND_PERM" android:exported="true">
    <intent-filter><action android:name="com.xxx.SECRET" /></intent-filter>
</receiver>
```

双向权限 + 13+/14 导出声明，构成广播的"四道闸"：filter 匹配 → exported 可见性 → 发送权限 → 接收权限。

### 9.3 onReceive 里的越权风险

`BroadcastReceiver.onReceive(Context, Intent)` 的 `Context` 在**静态接收者场景**是 `ReceiverRestrictedContext`——它**禁用了 `registerReceiver()` 的部分重载和 `bindService()`**，防止接收者借机偷偷注册/绑定。但仍有风险点：
- 不校验 `Intent` 来源就执行敏感操作（任何人都可能发广播给你，除非你设了权限/导出）；
- 在 `onReceive` 里启动 Activity 需要 `FLAG_ACTIVITY_NEW_TASK`（否则崩溃），且会被系统管控；
- 把 `Intent` extras 当可信数据直接用（可能来自恶意发送方）——**所有广播 extras 都应按"不可信输入"处理**。

### 9.4 保护性广播（protected-broadcast）

系统通过 `protected-broadcast` 声明"某些 action 只有系统/特定 UID 能发"，防止第三方伪造系统广播（例如伪造 `BOOT_COMPLETED` 让别的应用误以为开机了、伪造 `PACKAGE_REMOVED` 搞破坏）。

声明位置（系统镜像的 `framework`/特权应用 manifest 或系统配置）：

```xml
<!-- 在系统/特权应用的 manifest 里 -->
<protected-broadcast android:action="android.intent.action.BOOT_COMPLETED" />
<protected-broadcast android:action="android.intent.action.PACKAGE_REMOVED" />
<!-- ... 系统关键 action 列表 -->
```

机制：AMS 在 `verifyBroadcastLocked`（第 3.2 章）检查——若 intent 的 action 属于 `protected-broadcast` 集合，而发送方不是系统/声明方 UID，直接拒绝。ROM 厂商加自定义系统广播时，**若不希望被第三方伪造，务必用 `protected-broadcast` 声明**（配合 SELinux / 签名权限更稳，见第 11 章）。

### 9.5 LocalBroadcastManager 已废弃与替代

`LocalBroadcastManager`（support/v4 旧类）曾是"App 内广播"的推荐做法，但它：
- 只在**同一进程内**分发（本质是进程内 handler 派发，不走 Binder、无跨进程）；
- 已被废弃，官方建议用**其他进程内机制**替代。

替代对照（App 侧一句带过，详见 `androidApp/09` 系列）：

| 场景 | 替代 |
|---|---|
| App 内事件（同进程） | `LiveData` / `Kotlin Flow`（`SharedFlow`/`StateFlow`）/ 简单回调 / 事件总线库 |
| 跨进程但同应用多进程 | 仍可用 `BroadcastReceiver` 但谨慎；或 AIDL / `Messenger` |

> 注意：`LocalBroadcastManager` 不用 AMS、不经队列、无 ANR 概念——它和本篇讲的"系统广播"是两套东西，只是名字都叫 broadcast。废弃后别再在新代码里用。

---

## 10. 广播 vs 其他事件机制：什么时候用什么

Android 里"把一件事通知给别人"的通道不止广播。选型错了，要么性能炸，要么收不到。下面是对照（App 侧只给结论，展开见 `androidApp/09` 系列）。

| 机制 | 跨进程 | 跨应用 | 是否经 AMS/队列 | 是否可能 ANR | 典型用途 | 不合适的地方 |
|---|---|---|---|---|---|---|
| **Broadcast（系统广播）** | 是 | 是 | 是（fg/bg 队列） | 是（onReceive 超时） | 系统事件、跨应用通知（开机、网络、电量、自定义系统广播） | 高频事件、需要返回值、App 内通信 |
| **Broadcast（有序）** | 是 | 是 | 是（串行） | 是 | 需要拦截/改结果（短信、来电拦截） | 性能敏感、链长 |
| **事件总线（EventBus 等）** | 否（同进程） | 否 | 否（进程内） | 否 | App 内组件解耦通信 | 跨进程、跨应用 |
| **Kotlin Flow / SharedFlow / StateFlow** | 否（同进程） | 否 | 否 | 否 | App 内响应式状态流、可订阅"最新值"（替代 sticky） | 跨进程 |
| **AIDL 回调（oneway / 注册 listener）** | 是 | 是（同签名/权限） | 否（直连 Binder） | 否（除非对端卡） | 需要双向、低延迟、带返回值的跨进程调用（如系统服务暴露的 listener） | 简单的"通知一下"也上 AIDL 偏重 |
| **ContentObserver（通过 ContentProvider）** | 是 | 是（按 URI 权限） | 否（notifyChange 派发） | 否 | 监听数据变化（通讯录、设置、自定义 provider 状态） | 非数据变化的事件 |

### 10.1 选型口诀

- **系统/跨应用"事件通知"** → 广播（但注意 8.0 限制、13/14 导出）。
- **App 内部解耦** → 事件总线或 Flow，**不要**用广播（更重、且易踩权限/导出坑）。
- **需要"最新值 + 可订阅"**（替代 sticky）→ `StateFlow` / `ContentObserver`，不要 sticky 广播。
- **需要"双向、带返回值、低延迟"的跨进程** → AIDL 注册 listener，不要广播（广播是单向 fire-and-forget，有序广播的 result 也只是字符串/Bundle，表达力弱）。
- **高频事件**（如传感器每秒多次）→ 任何广播都不合适，会淹没队列；用 AIDL 轮询/回调或 Flow。

---

## 11. 车机/平台视角：自定义系统广播、开机时序与多用户

车机 ROM vendor 开发者是广播机制的"重度玩家"——你要发自定义系统广播、管开机广播、处理多用户座舱。本章给正确姿势。

### 11.1 自定义系统广播的正确姿势

如果你要在车机系统里定义"仅供系统/特权应用使用的广播"（如 `CAR_MODE_CHANGED`、`HVAC_STATE`），正确做法是四件套：

1. **声明 `protected-broadcast`**：在系统/特权应用 manifest 里 `<protected-broadcast android:action="com.car.xxx"/>`，防止第三方伪造（见 9.4）。
2. **配合权限**：发送方 `sendBroadcast(intent, "com.car.xxx.PERM")`，接收方 manifest 声明 `android:permission` 要求持有该权限；权限的 `protectionLevel` 用 `signature` 或 `signatureOrSystem`，限定只有签名/系统应用能收发。
3. **（可选）SELinux**：若接收方是独立守护进程/原生服务通过 `broadcast` 相关接口，确保 SELinux policy 允许对应 domain 的 `binder` / `service_manager` 访问，否则 Binder 调用会被 avc 拒绝（报 `avc: denied`）。
4. **（可选）PMS 特权声明**：把接收方应用放在 `/system/priv-app` 并声明 `android:protectionLevel="signature|privileged"`，确保它确实是"系统信任"的。

> 一句话：**自定义系统广播 = protected-broadcast + 签名权限 + （SELinux 放行）+ （priv-app）**。缺 protected-broadcast 会被伪造，缺权限会被任意应用监听。

### 11.2 开机广播的时序依赖

车机最易踩的"开机收不到"往往不是权限问题，而是**时序**：

```text
Linux 内核启动
   └─ init 进程 (详见 androidOthers/init 篇) 启动
        ├─ 设置属性 sys.boot_completed = "1"   ← 早期, 表示 init 阶段完成
        └─ 启动 zygote → system_server → AMS
             └─ 系统服务就绪后, AMS 发出 BOOT_COMPLETED
                  (android.intent.action.BOOT_COMPLETED)  ← 晚于 sys.boot_completed 属性

关键差异:
  sys.boot_completed 属性  → init 层面标记, 早于 BOOT_COMPLETED 广播
  BOOT_COMPLETED 广播      → AMS 发出, 表示"系统框架基本可用、可启动三方组件"
```

要点：
- **`sys.boot_completed=1` 不等于"能收 BOOT_COMPLETED 广播"**：前者是 init 属性，后者是 AMS 在所有引导阶段（包括 `ActivityManager` 进入可交互状态、home 启动等）完成后才发。有些早期服务只等属性就动手，结果三方组件还没就绪。
- 想"最早动手"别依赖 `BOOT_COMPLETED` 静态接收（它会在所有应用里排队，还受白名单/限速影响）。系统/特权组件可在 init / early-boot 阶段用属性监听或 native 服务拉起。
- 详情见 `androidOthers/init` 篇的 init 属性与 boot 阶段；本篇只强调"两个完成不是一回事"。

### 11.3 多用户下广播的 user 指定

车机常有主驾/副驾多用户、或车机用户与手机用户隔离。`sendBroadcast` 时有 `userId` 维度：

- `sendBroadcastAsUser(intent, UserHandle)`：指定广播只发给某个 user 的接收者。发给 `UserHandle.ALL` 才会跨所有用户。
- 静态接收者按"安装该 user 的包"匹配；动态接收者按"注册进程所属 user"匹配。
- 跨用户发广播受 `INTERACT_ACROSS_USERS` / `INTERACT_ACROSS_USERS_FULL` 权限约束，普通应用不能乱跨。
- 典型坑："我在 user 0 发的广播，user 10 的接收者收不到"——这是**正确行为**，因为没指定 `ALL` 或对应 user。

---

## 12. dumpsys 解读：我的广播去哪了

`dumpsys activity broadcasts` 是排查广播问题的第一命令。下面逐块讲读法。

```bash
adb shell dumpsys activity broadcasts        # 全部广播状态
adb shell dumpsys activity broadcasts brief  # 精简
```

### 12.1 输出结构（按块）

典型输出含这些块（不同版本字段名略异）：

- **Historical broadcasts [foreground] / [background]**：已发送过的广播历史（环形缓冲），每条含 action、caller（uid/pid/package）、接收者数、分发耗时。用来确认"这条广播到底有没有被发出来、匹配到几个接收者"。
- **mFg/BgBroadcastQueue 的当前状态**：前台/后台队列当前在处理的 `BroadcastRecord`、卡在哪个 `nextReceiver`、已运行多久（**这是看"有序广播卡在哪"的关键**）。
- **Sticky broadcasts**：当前系统缓存的 sticky intent 列表（遗留，见第 6 章）。
- **ReceiverList / registered receivers**：当前各进程动态注册情况（哪个 pid 注册了哪些 filter）。用来确认"我的动态接收者到底注册上没有"。
- **mDelayedBroadcasts / 等待进程启动的**：因目标进程未启动而等待/正在拉起的广播（对应第 8.1 的冷启动代价）。

### 12.2 怎么查"我的广播去哪了"

```text
① 先在历史里 grep 你的 action:
     看 BroadcastRecord 是否存在 → 没有 = 根本没发出来/被发送侧拦了
     有但 receivers=0 → 没人匹配(filter 不对 / 隐式被 8.0 限制 / user 不对)
② 看当前队列里是否卡住:
     nextReceiver 停在某个 receiver, 且 running 时间 > 超时量级 → 有序广播卡住(第 13 章)
③ 看 registered receivers:
     你的动态接收者不在列表 → 注册时机错/进程死了/注册抛异常
④ 看是否进了 delayed(等待进程启动):
     是 → 你的接收者是静态且进程没起, 正在冷启动(第 8 章)
```

配合 logcat：

```bash
adb logcat -b all | grep -iE "BroadcastQueue|BroadcastRecord|finishReceiver|deliver"
# 关注: 入队 / 分发到哪个 receiver / finishReceiver 回 AMS / 超时
```

---

## 13. 常见问题排查表

| 现象 | 原因 | 章节 | 第一命令 |
|---|---|---|---|
| `BOOT_COMPLETED` 收不到 | 缺 `RECEIVE_BOOT_COMPLETED` 权限 / 应用处于 stopped / 用户未解锁 / 静态未 export | 7.2 / 7.3 | `adb shell dumpsys package <pkg> \| grep -i "boot"` |
| 有序广播卡住不往下传 | 前一个接收者 `onReceive` 未 `finishReceiver` 或耗时超时被 ANR | 5.1 / 8.2 | `adb shell dumpsys activity broadcasts \| grep -A20 "nextReceiver"` |
| `onReceive` 里耗时被 ANR | 主线程做了 IO/网络/重算；或冷启动慢 | 8.2 | `adb logcat -b system \| grep -i "ANR\|broadcast"` |
| 动态注册 flag 崩溃（13+） | `registerReceiver` 未带 `RECEIVER_EXPORTED/NOT_EXPORTED` | 2.4 | 看 logcat `IllegalArgumentException` 栈 |
| 广播延迟巨大 | 落后台队列被限速；或触发大量进程冷启动 | 4.1 / 8.1 | `adb shell dumpsys activity broadcasts \| grep -i delayed` |
| 隐式广播 8.0 后不触发 | 静态接收隐式广播被限制（非白名单 action） | 7.1 | `adb shell cmd package query-receivers -a <action>` |
| 广播丢失（进程刚被杀） | 动态接收者进程死；或 dispatch 途中被杀被丢弃 | 2.1 / 8.3 | `adb logcat \| grep -i "BinderDeath\|process died"` |
| `protected-broadcast` 报错 | 非系统 UID 发送了受保护 action | 9.4 | `adb logcat \| grep -i "protected-broadcast\|SecurityException"` |
| 多用户收不到 | 发送未指定目标 user / 未用 `UserHandle.ALL` | 11.3 | `adb shell dumpsys activity broadcasts \| grep -i user` |
| 静态 receiver 收不到自定义广播 | 未 `exported=true` 且被 14 强制要求；或权限不符 | 7.3 / 9.2 | `adb shell cmd package dump <pkg>` |
| 收到广播但 extras 为空/异常 | 发送方 extra 超 Binder 缓冲被截断；或伪造发送方 | 3.2 / 9.3 | `adb shell dumpsys activity broadcasts` 看 intent |
| 前台广播被当成后台 | 发送方在前台判定失败 / 未带 `FLAG_RECEIVER_FOREGROUND` | 4.1 | 看 record 的 `foreground` 标记 |
| 一个广播拉起几十个进程致开机慢 | 开机类静态接收者过多、无白名单收敛 | 8.1 / 11.2 | `adb shell dumpsys activity broadcasts \| grep "BOOT"` |
| `abortBroadcast` 不生效 | 用在了无序广播上；或优先级不够高仍被先收 | 5.3 / 5.4 | 确认 `ordered=true` 与 priority |
| 自定义系统广播被第三方监听 | 未用 `protected-broadcast` + 权限 | 9.4 / 11.1 | 查接收方 `query-receivers` 是否暴露 |
| 发广播抛 `SecurityException` | 要求了接收方没有的权限，或发了受保护 action | 9.1 / 9.4 | 看栈里缺失的 permission 名 |

---

## 14. 读源码路线

按"由浅入深 + 由发送到分发"给四条路线，读 AOSP 时顺着走最快。

### 路线 A：sendBroadcast → AMS → 入队（主链路）
```text
frameworks/base/core/java/android/content/ContextWrapper.java        sendBroadcast()
frameworks/base/core/java/android/app/ContextImpl.java               sendBroadcast() → AMS.broadcastIntent
frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java
        broadcastIntentWithFeature() → broadcastIntentLocked()       // 校验+收集+构造
frameworks/base/services/core/java/com/android/server/am/BroadcastQueue.java (或 BroadcastQueueModernImpl)
        enqueueBroadcastLocked() → scheduleBroadcastsLocked()
```
目标：搞懂"一次 sendBroadcast 怎么变成队列里的一个 BroadcastRecord"。

### 路线 B：有序广播的 finishReceiver 回路
```text
BroadcastQueue.processNextBroadcastLocked()       // 取 record, dispatch 第 nextReceiver 个
  → ActivityThread.scheduleReceiver() (Binder)    // 目标进程 onReceive
  → 接收者 finishReceiver() (Binder)              // 回 AMS
  → BroadcastQueue.finishReceiver()               // nextReceiver++, 调度下一个
```
目标：搞懂"有序广播为什么卡住就全链停"（第 5、13 章的根）。

### 路线 C：静态 receiver 的 PMS 查询
```text
ActivityManagerService.broadcastIntentLocked()
  → PackageManagerService.queryIntentReceivers() / resolveReceiver()
frameworks/base/services/core/java/com/android/server/pm/PackageManagerService.java
  → 返回 ResolveInfo 列表 → 合并进 BroadcastRecord.receivers
```
目标：搞懂"死进程为什么还能被匹配到、以及 8.0 隐式限制在哪一步被拦"。

### 路线 D：队列超时与 ANR
```text
BroadcastQueue 中超时常量 (BroadcastConstants / ActiveServices 相关)
  → processNextBroadcastLocked() 里对单接收者启动超时
  → 超时未 finish → AppErrors / 发起 broadcast ANR
frameworks/base/services/core/java/com/android/server/am/AppErrors.java (或 ANR 处理类)
```
目标：搞懂"onReceive 超时的阈值从哪来、ANR 怎么触发"（第 4.2、8.2 章的根）。

> 提示：Android 13+ 队列实现迁移到 `BroadcastQueueModernImpl` + `BroadcastProcessQueue`，类名变了但四条路线的"职责节点"不变，顺着函数名（broadcastIntent / processNextBroadcast / finishReceiver / queryIntentReceivers）找即可。

---

## 15. 源码路径速查表 + 一图总结 + 关联阅读

### 15.1 源码路径速查表

| 内容 | 路径（AOSP） |
|---|---|
| App 侧发送入口 | `frameworks/base/core/java/android/app/ContextImpl.java` |
| AMS 广播入口 | `frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java` |
| 广播队列（经典） | `frameworks/base/services/core/java/com/android/server/am/BroadcastQueue.java` |
| 广播队列（13+ modern） | `frameworks/base/services/core/java/com/android/server/am/BroadcastQueueModernImpl.java` |
| 每进程队列（13+） | `frameworks/base/services/core/java/com/android/server/am/BroadcastProcessQueue.java` |
| 广播记录 | `frameworks/base/services/core/java/com/android/server/am/BroadcastRecord.java` |
| 进程动态接收者集合 | `frameworks/base/services/core/java/com/android/server/am/ReceiverList.java` |
| 动态 filter 的 AMS 表达 | `frameworks/base/services/core/java/com/android/server/am/BroadcastFilter.java` |
| 动态注册 App 侧 | `frameworks/base/core/java/android/app/LoadedApk.java`（ReceiverDispatcher） |
| 静态接收者解析 | `frameworks/base/services/core/java/com/android/server/pm/PackageManagerService.java` |
| 接收者进程侧分发 | `frameworks/base/core/java/android/app/ActivityThread.java`（`scheduleReceiver`） |
| 超时常量 | `frameworks/base/services/core/java/com/android/server/am/BroadcastConstants.java`（或 `ActiveServices` 相关常量） |

### 15.2 一图总结（两类广播、两种队列）

```text
                         sendBroadcast(intent)
                                │
                  broadcastIntentLocked()
                   ├─ 动态: ReceiverList (活进程内存)
                   └─ 静态: PMS.queryIntentReceivers (磁盘/DB, 可拉起进程)
                                │  合并去重 → BroadcastRecord
                                ▼
            ┌─────────────────── 广播队列(两类) ───────────────────┐
            │  前台队列 (fg)  高优先级 / 快 / 超时 ~10s 量级        │
            │  后台队列 (bg)  限速   / 慢 / 超时 ~60s 量级         │
            └─────────────────────────────────────────────────────┘
                                │
            ┌─────────────────── 每队列内两类广播 ─────────────────┐
            │  并行(无序): 一次性 dispatch 给所有匹配者, 互不等待  │
            │  有序(ordered): 按 priority 串行, 等 finishReceiver  │
            └─────────────────────────────────────────────────────┘
                                │ (Binder 回各进程)
                                ▼
                   ActivityThread → onReceive() 主线程
```

**一句话记忆链**：
> 发送方喊一嗓子 → AMS 把"活进程的名单（动态）"和"PMS 的名单（静态）"合并成一条 `BroadcastRecord` → 丢进前台/后台两条队列 → 无序的一股脑群发、有序的一个接一个传 → 各进程主线程 `onReceive` → 慢了就 ANR，进程死了这条就丢。记住"动态靠内存、静态靠 PMS、分发靠队列、超时靠量级"。

### 15.3 关联阅读

- `05_AMS` / `06_AMS`：AMS 内部两条生命周期链路（应用启动 `startProcessLocked`、进程记录 `ProcessRecord`）的细节，本篇第 3、8 章引用处指路。
- `17_通知`：通知的展示链路（与广播同属"系统事件触达"，但走 `NotificationManager` / `NotificationManagerService`）。
- `16_后台任务`：被广播 `onReceive` 唤起后，重活应交给 JobScheduler / WorkManager / 前台服务，详见此篇。
- `androidOthers/init` 篇：开机 `sys.boot_completed` 属性与 `BOOT_COMPLETED` 广播的时序差异（第 11.2 章引用）。
- `androidApp/09` 系列：App 侧事件总线、Flow、`LocalBroadcastManager` 替代方案（第 9.5、10 章引用）。
