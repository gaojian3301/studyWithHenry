# Binder 机制详解：从 AIDL 到 Framework 层

> 以 AIDL 为切入点，把你在 `*.aidl` 里用的 Binder，一路追到 Framework native 层、再到内核 `binder` 驱动，讲清楚「进程隔离下怎么拿到对方、怎么把参数传过去、怎么等结果、对方死了怎么办」。
> 目标读者：写过 AIDL、做过系统应用 / 车载 ROM vendor 开发，会读 AOSP 源码，但没系统梳理过 native / 内核层的 Android 开发者。

> 「**Android 视角**」标注的内容表示「跳过不影响原理理解，但和你日常在 AOSP / 车机里碰到的事直接相关」，可以放心略读。

---

## 目录

1. [开场：Binder 要回答的四个问题](#1开场binder-要回答的四个问题)
2. [为什么不是其他 IPC：Binder 的四个独特设计](#2为什么不是其他-ipcbinder-的四个独特设计)
3. [内核层：binder 驱动概览与一次拷贝](#3内核层binder-驱动概览与一次拷贝)
4. [BC/BR 命令协议：一次调用的报文时序](#4bcbr-命令协议一次调用的报文时序)
5. [ServiceManager：系统服务的电话簿（句柄 0）](#5servicemanager系统服务的电话簿句柄-0)
6. [native 层 libbinder：Bp/BB、ProcessState、Parcel](#6native-层-libbinderbpbbprocessstateparcel)
7. [Java 层：IBinder/Binder/Proxy 与 AIDL 生成代码](#7java-层ibinderbinderproxy-与-aidl-生成代码)
8. [一次调用的完整时序：逐段标注发生了什么](#8一次调用的完整时序逐段标注发生了什么)
9. [线程模型：Binder 线程池与重入的坑](#9线程模型binder-线程池与重入的坑)
10. [异步与同步事务：oneway、buffer 限制与 TransactionTooLarge](#10异步与同步事务onewaybuffer-限制与-transactiontoolarge)
11. [binder_ndk 与 AIDL 三种后端：跨语言与 fd 传递](#11binder_ndk-与-aidl-三种后端跨语言与-fd-传递)
12. [死亡通知与稳定性：linkToDeath 全链路](#12死亡通知与稳定性linktodeath-全链路)
13. [调试：service list、调试节点与 trace](#13调试service-list调试节点与-trace)
14. [常见问题排查表](#14常见问题排查表)
15. [安全与权限层：uid/pid 自认证与 SELinux](#15安全与权限层uidpid-自认证与-selinux)
16. [读源码的推荐路线](#16读源码的推荐路线)
17. [关键源码路径速查](#17关键源码路径速查)
18. [一图总结与关联阅读](#18一图总结与关联阅读)

---

## 1. 开场：Binder 要回答的四个问题

**一句话：Binder 是 Android 的「进程间函数调用路由器」。** 你写 AIDL 的体感是「我调了个方法，返回值就回来了」，好像和本地调用没区别——但方法真正执行的地方，往往是另一个进程（比如 `system_server`）。Binder 干的事，就是把这个「像本地调用」的假象维持住。

在动手前，先用四个问题给 Binder 画个坐标系。后面每一章都是在回答其中一个：

| # | 要回答的问题 | 对应章节 |
|---|---|---|
| Q1 | **进程隔离下，怎么「拿到对方」？**（我凭什么得到一个能调远端方法的对象） | 第 5 章（ServiceManager）、第 7 章（asInterface） |
| Q2 | **怎么把参数传过去、又怎么把结果传回来？**（序列化 + 通道） | 第 4 章（命令协议）、第 6/7 章（Parcel） |
| Q3 | **怎么等结果、又是怎么「不阻塞」的？**（同步 vs 异步） | 第 8 章（时序）、第 10 章（oneway） |
| Q4 | **对方进程死了怎么办？**（死亡感知 + 回调） | 第 12 章（死亡通知） |

### 1.1 一次跨进程调用的全景图

下面这张图是整个系列反复回指的总图。先有个整体印象，后面逐层拆开：

```text
┌──────────────────────────── 客户端进程 ────────────────────────────┐
│  AIDL Proxy.add(1,2)                                                │
│     │ transact(code, data, reply)                                   │
│     ▼                                                               │
│  BinderProxy ──JNI──> BpBinder ──> IPCThreadState                   │
│                                       │ ioctl(BINDER_WRITE_READ)    │
└───────────────────────────────────────┬───────────────────────────┘
                                        │  ╔═══════════ 内核 binder 驱动 ═══════════╗
                                        ▼  ║ copy_from_user 一次拷贝进目标 mmap 区   ║
┌──────────────────────────── 服务端进程 ─┘  ║ 记录调用方 uid/pid、唤醒目标线程       ║
│  IPCThreadState 被唤醒                      ╚═════════════════════════════════════╝
│     │ 读 BR_TRANSACTION                                                    │
│     ▼                                                                      │
│  BBinder.onTransact(code) ──JNI──> Stub.onTransact(code)                  │
│     │  调用你的真正实现 add(1,2)                                            │
│     ▼  结果写回 reply ── BC_REPLY ──> 原路返回客户端                        │
└──────────────────────────────────────────────────────────────────────────┘
                                        ▲
                                        │ 注册 / 查询（句柄 0 = ServiceManager）
                                 ServiceManager（电话簿）
```

**怎么读这篇：** 建议按章节顺序自底向上（驱动 → native → Java → AIDL），因为上层每个概念都回指下层。已经熟 AIDL 的，可以直接从第 6 章开始，遇到不熟的术语再回第 1～5 章。车机 vendor 开发者请重点看第 5 章（vendor servicemanager）、第 11 章（binder_ndk / 跨语言 fd 传递）、第 14 章（排查表）。

---

## 2. 为什么不是其他 IPC：Binder 的四个独特设计

Android 不是没有别的选择：Linux 自带 socket、pipe、共享内存、消息队列。Binder 能成为 Android 默认 IPC，靠的是下面这张对比里高亮的四个差异。

| 机制 | 拷贝次数 | 能传 fd | 调用方身份自携带 | 架构模型 |
|---|---|---|---|---|
| socket / pipe | 2 次（用户→内核→用户） | 麻烦（需 SCM_RIGHTS） | ❌ 需自己带 | 点对点流 |
| 共享内存 | 0 次，但需自己加锁同步 | 否 | ❌ | 共享内存块 |
| 消息队列 | 2 次 | 否 | ❌ | 内核队列 |
| **Binder** | **1 次**（见第 3 章） | **天然支持** | **内核记录 uid/pid** | **C/S（client-server）** |

四个独特设计，是后面所有章节的主线：

1. **一次拷贝（one-copy）**：服务端进程启动时 `mmap` 一块内核缓冲区到用户空间，发送方的数据只从用户态拷到这块共享映射区一次，接收方直接读映射区，省掉传统 IPC 的第二次 `copy_to_user`。第 3 章展开。
2. **对象引用计数（object refcount）**：Binder 传递的不是「死数据」，而是「对另一个进程里某个对象的引用」，内核为每个 Binder 实体维护强/弱引用计数（`BC_ACQUIRE/RELEASE`、`INCREFS/DECREFS`），跨进程对象生命周期可管。第 4、6 章展开。
3. **进程死亡感知（death notification）**：你注册的引用，在远端进程死掉时内核会主动给你发 `BR_DEAD_BINDER`，上层转成 `DeathRecipient.binderDied()` / `DeadObjectException`。第 12 章展开。
4. **C/S 架构 + 身份自认证**：内核在事务里记录调用方 `uid/pid`，服务端无需信任客户端自报身份就能做鉴权；配合 ServiceManager 这种「电话簿」形成稳定的 C/S 寻址。第 5、15 章展开。

> 「**Android 视角**」：**能跨进程传文件描述符（fd）** 是 Binder 的独门绝技。你后面学 SurfaceFlinger 会看到，App 和 SurfaceFlinger 之间传递 `Surface` 的 graphic buffer，靠的就是 Binder 跨进程传 fd；车机里的多屏、相机、音频 buffer 走的是同一机制。这是共享内存都做不好的事。

---

## 3. 内核层：binder 驱动概览与一次拷贝

### 3.1 /dev/binder 与驱动角色

Binder 的核心是内核里的一个字符设备驱动，设备节点通常是 `/dev/binder`（还有 `/dev/vndbinder`、`/dev/hwbinder`，第 5/11 章会区分）。它不是一个「转发数据的网络栈」，而是一个**事务调度器 + 引用计数管理器 + 缓冲区管家**：

- 它**不**替你拷贝数据到「对方的进程空间」，而是把数据放进目标进程预先 `mmap` 好的缓冲区里，再唤醒目标线程去读。
- 它**维护** Binder 实体（谁提供了服务）和引用（谁拿着服务的句柄）的映射，使「句柄（handle）」在任意进程里都能解析到正确的实体。
- 它**记录**每次事务的调用方 `uid/pid`、是否 oneway、buffer 占用，用于鉴权和限额。

### 3.2 四个核心结构体（及其关系）

驱动里最重要的四个结构体，记住它们的关系就抓住了 binder 驱动的骨架：

```text
binder_proc                ← 每个打开 /dev/binder 的进程一个
  │
  ├── binder_node[]        ← 本进程「提供」的 Binder 实体（服务真身）
  │      字段：ptr（实体在用户空间的地址）、cookie、refs（被谁引用）
  │
  ├── binder_ref[]         ← 本进程「持有」的对别的进程的引用
  │      字段：desc（= 句柄 handle，如 0,1,2...）、node（指向对方的 binder_node）
  │
  ├── binder_buffer[]      ← mmap 区域内分配给各次事务的数据块
  │      字段：data（指向 mmap 区里的数据）、可携带 flat_binder_object
  │
  └── binder_thread[]      ← 本进程里参与 binder 通信的每个线程（含 looper 状态）
```

直觉记忆：

- **`binder_node` = 服务真身**，住在「服务所在进程」的 `binder_proc` 里。
- **`binder_ref` = 遥控器**，住在「调用方进程」的 `binder_proc` 里，其 `desc` 字段就是上层看到的 **handle**。
- **handle 0 永远是 ServiceManager**（第 5 章），所以任何进程「开进程就能打 0 号找电话簿」。
- **`binder_buffer`** 是真正装 transaction 数据的地方，位于 mmap 区域。

### 3.3 mmap 与「一次拷贝」原理

传统 IPC（socket/pipe）传数据要拷贝两次：

```text
发送方用户空间 ──copy_from_user──> 内核缓冲区 ──copy_to_user──> 接收方用户空间
        (第 1 次)                     (第 2 次)
```

Binder 只拷贝一次，秘诀在**服务端启动时的那次 `mmap`**：

```text
发送方用户空间 ──copy_from_user──> 内核 binder_buffer（已 mmap 到「接收方」用户空间）
                                        │
                                        └──(同一块映射)──> 接收方用户空间直接可见（0 次额外拷贝）
```

要点：

- 服务端进程（如 `system_server`、或被 bind 的 vendor 进程）初始化时，`ProcessState` 通过 `mmap` 把一块 binder 缓冲区映射到自己用户空间。
- 客户端发起调用时，驱动把发送方用户空间的数据 `copy_from_user` 进**目标进程**对应的 `binder_buffer`（这块 buffer 在目标进程的 mmap 区里）。
- 接收方线程被唤醒后，**直接从自己的 mmap 区域读**，不需要再 `copy_to_user`。这就是「一次拷贝」的本质：靠 mmap 省掉了第二次拷贝。

更严格地说，「一次拷贝」指的是普通事务数据本身从发送方用户空间拷到目标进程的 binder 映射缓冲区；接收方经映射区直接可见。Binder 还支持传 fd（第 11 章），传 fd 时只传递目标进程的 fd 编号映射，几乎零数据开销——这也是图形 buffer 能高效跨进程的原因。

> 版本差异：mmap 的缓冲区大小由用户态通过 `BINDER_VM_SIZE` 一类参数约定，不同 Android 版本/进程略有差异；内核侧按 `binder_buffer` 粒度从这块区域分配。不要写死某个字节常量，记住「这是一块进程级共享的有限区域」即可（第 10 章的限额也源于此）。

---

## 4. BC/BR 命令协议：一次调用的报文时序

Binder 驱动和用户态之间，靠一组**命令码**通过 `ioctl(BINDER_WRITE_READ)` 交换。命名约定：

- **BC_ = Binder Command**：用户态 → 驱动（你发给驱动的指令）。
- **BR_ = Binder Return**：驱动 → 用户态（驱动回给你的结果）。

### 4.1 常用命令对照表

| 命令 | 方向 | 含义 |
|---|---|---|
| `BC_TRANSACTION` | →驱动 | 发起一次方法调用（带 code、data、flags） |
| `BC_REPLY` | →驱动 | 服务端回送调用结果 |
| `BC_ACQUIRE` / `BC_RELEASE` | →驱动 | 强引用 +1 / -1 |
| `BC_INCREFS` / `BC_DECREFS` | →驱动 | 弱引用 +1 / -1 |
| `BC_REQUEST_DEATH_NOTIFICATION` | →驱动 | 注册远端死亡通知 |
| `BC_CLEAR_DEATH_NOTIFICATION` | →驱动 | 注销远端死亡通知 |
| `BC_FREE_BUFFER` | →驱动 | 用完事务 buffer 后归还 |
| `BC_ENTER_LOOPER` / `BC_REGISTER_LOOPER` / `BC_EXIT_LOOPER` | →驱动 | 线程池状态管理 |
| `BR_TRANSACTION` | 驱动→ | 收到一次调用，去执行 |
| `BR_REPLY` | 驱动→ | 收到调用结果 |
| `BR_DEAD_REPLY` | 驱动→ | 目标已死，无回复（返回端死亡） |
| `BR_FAILED_REPLY` | 驱动→ | 事务失败（如事务超限、目标无效） |
| `BR_ACQUIRE` / `BR_RELEASE` | 驱动→ | 强引用计数回调 |
| `BR_DEAD_BINDER` | 驱动→ | 你持有的某个引用，其远端进程死了 |
| `BR_NOOP` / `BR_OK` | 驱动→ | 空操作 / 成功确认 |
| `BR_SPAWN_LOOPER` | 驱动→ | 线程池不够了，让你再起一个 binder 线程 |

> 版本差异：`binder` 协议有版本号，驱动与用户态 libbinder 通过 `BINDER_VERSION` 协商；旧版本字段布局与新版本不同，但 `BC_/BR_` 语义长期稳定。阅读不同版本 AOSP 时注意 `binder_write_read` 结构体的字段差异。

### 4.2 一次调用的命令时序（ASCII）

以同步调用 `add(1,2)` 为例，时序是「一问一答」：

```text
客户端线程                        内核驱动                        服务端线程
     │                               │                               │
     │──── BC_TRANSACTION ──────────>│                               │
     │   (code=add, data=[1,2])      │ 分配 binder_buffer            │
     │                               │ 唤醒目标线程                  │
     │                               │──── BR_TRANSACTION ──────────>│
     │                               │   (把 buffer 交服务端读)      │
     │          (客户端线程阻塞等)    │                               │ 执行 add(1,2)
     │                               │<─── BC_REPLY ────────────────│
     │                               │   (结果写回同一 buffer)       │
     │<─── BR_REPLY ─────────────────│                               │
     │   读 reply、拿到结果、继续     │                               │
```

如果是 **oneway**（第 10 章），客户端发完 `BC_TRANSACTION` 立刻返回，**没有 `BC_REPLY`/`BR_REPLY`**，服务端执行不阻塞客户端。此时若目标已死，客户端不会收到 `BR_DEAD_REPLY`（因为它根本没在等回复），这会带来「oneway 调用悄悄丢失」的现象（第 14 章）。

---

## 5. ServiceManager：系统服务的电话簿（句柄 0）

Q1「怎么拿到对方」的答案是：先找电话簿。

### 5.1 句柄 0 与 context manager

`ServiceManager` 本身也是一个 Binder 服务，但它**特殊**：它的句柄被驱动写死为 **0**。所有进程都知道「要找电话簿，就打 0 号」，所以无需先知道电话簿在哪就能找到它。

驱动在处理事务时，看到 `handle == 0` 就路由到 ServiceManager 的实体节点（context manager）。因此 `defaultServiceManager()` 在 native 层本质就是 `ProcessState::getContextObject(0)` → 拿到 `BpBinder(0)`，再 `interface_cast<IServiceManager>` 成接口。

### 5.2 注册与查询流程

ServiceManager 内部是个简单的名字→句柄表，核心动作（源码里叫 `svcmgr_handler`）：

| 动作 | 对应常量 | 作用 |
|---|---|---|
| 注册服务 | `SVC_MGR_ADD_SERVICE` | 服务端把 `(name, binder)` 登记进表 |
| 查询服务 | `SVC_MGR_GET_SERVICE` | 客户端按名字拿到对应 `IBinder`（找不到返回 null） |
| 检查服务 | `SVC_MGR_CHECK_SERVICE` | 类似 get，但区分「不存在」与「尚未就绪」 |
| 列出服务 | `SVC_MGR_LIST_SERVICES` | `service list` 命令的来源 |

服务端注册、客户端查找的代码骨架：

```cpp
// native 侧示意：服务端注册
defaultServiceManager()->addService(String16("activity"), amBinder);

// Java 侧客户端查找
IBinder b = ServiceManager.getService("activity");      // 拿到 AMS 的 BinderProxy
IActivityManager am = IActivityManager.Stub.asInterface(b);  // 包成 Proxy（见第 7 章）
```

### 5.3 defaultServiceManager() 的懒加载

`defaultServiceManager()` 是**懒加载 + 进程内单例**：第一次调用时才去 `getContextObject(0)` 并 `interface_cast`。之后进程内复用同一个 `IServiceManager` 代理。这意味着「拿到电话簿」的成本只在首次；之后每次 `getService` 都是一次对句柄 0 的普通 Binder 调用。

### 5.4 不止一个 servicemanager（vendor / 车机必看）

> 「**Android 视角**」：从 Android 8 起，系统里有**多个 servicemanager 实例**，按域隔离：

| 实例 | 设备节点 | 服务范围 | 谁能用 |
|---|---|---|---|
| `servicemanager` | `/dev/binder` | 框架 / 系统服务 | 系统进程、App |
| `vndservicemanager` | `/dev/vndbinder` | **vendor 服务**（HAL、vendor 进程） | vendor 域进程 |
| `hwservicemanager` | `/dev/hwbinder` | HIDL 服务 | 跨域 |

**这条对车机 vendor 开发者至关重要**：你写的 vendor 服务若注册到 `vndservicemanager`，就必须用 `/dev/vndbinder` 去 `getService`，而不是默认的 `/dev/binder`。用错节点会「找不到服务」或注册失败（第 14 章排查表里有对应行）。`servicemanager` 自身也由 init 拉起，不同实例通过 `servicemanager.rc` 的 `user`/`seclabel` 区分。

下一章进入 native 层，看 `getContextObject(0)` 拿到的 `BpBinder` 到底是什么，以及 `Parcel` 怎么把对象「拍扁」传过去。

---

## 6. native 层 libbinder：Bp/BB、ProcessState、Parcel

Java 层看到的 `Binder`/`BinderProxy` 只是壳，真正的收发逻辑在 `frameworks/native/libs/binder`（`libbinder`）。记住这张「两侧成对」的表（native 是 Java 之下的一层，不是和 Java 平行的另一世界）：

| 层 | 客户端（代理侧） | 服务端（实体侧） | 通信线程对象 |
|---|---|---|---|
| Java | `BinderProxy` | `Binder` / `Stub` | （线程即调用方线程） |
| native C++ | `BpBinder`（p = proxy） | `BBinder`（b = binder） | `IPCThreadState` |
| native 进程级 | —— | `ProcessState`（单例） | —— |

### 6.1 IBinder、BBinder、BpBinder

`IBinder` 是统一接口，定义 `transact()`、`queryLocalInterface()`、`linkToDeath()`、`pingBinder()`、`isBinderAlive()` 等。两个关键子类：

- **`BpBinder`**：客户端的 native 代理。它**不实现你的业务方法**，只持有一个 `mHandle`（句柄）。所有 `transact(code, data, reply, flags)` 都会转成 `BC_TRANSACTION` 发给驱动。你 Java 层的 `BinderProxy` 内部就包了一个 `BpBinder` 的 native 指针。
- **`BBinder`**：服务端的 native 基类。它的 `onTransact(code, data, reply, flags)` 是服务端总入口；Java 层 `Stub.onTransact` 最终由它通过 JNI 回调上来。

> 记忆口诀：`Bp` 是 Proxy（遥控器，客户端），`BB` 是服务端实体，`IPCThreadState` 是跑腿的，`ProcessState` 是总机。

### 6.2 ProcessState：进程级单例（总机）

每个进程第一次用 Binder 时，`ProcessState::self()` 创建单例，干两件大事：

1. `open("/dev/binder")`（vendor 进程可能是 `/dev/vndbinder`）——打开 binder 设备。
2. `mmap(...)`——把内核缓冲区映射到用户空间（第 3 章「一次拷贝」的关键）。

它还管理 Binder 线程池上限。历史上默认上限约为 **16 个**（不同版本/启动方式略有差异，可通过 `setMaxThreads` 一类接口调整）。这个上限是「驱动允许该进程并发处理的事务线程数」，不是「进程总线程数」。

### 6.3 IPCThreadState：每线程一个（接线员）

`IPCThreadState` 是**线程局部**的：每个真正收发 Binder 数据的线程都有自己的实例。它负责：

- 调 `ioctl(BINDER_WRITE_READ)` 和驱动对话（`talkWithDriver()`）；
- 处理 `BR_*` 返回：收到 `BR_TRANSACTION` 就调 `BBinder::transact` → 你的 `onTransact`；收到 `BR_DEAD_BINDER` 就触发死亡回调（第 12 章）；
- 在需要时按驱动 `BR_SPAWN_LOOPER` 提示再孵化一个 binder 线程。

客户端发起调用时，调用方线程临时变成「binder 线程」：它阻塞在 `ioctl` 上等 `BR_REPLY`（同步调用），或在发完 oneway 后返回。

### 6.4 Parcel 与对象扁平化（flatten）

`Parcel` 是 Binder 的「序列化容器」，但它不只是字节流——它能携带**对象引用**。关键在于 `flat_binder_object`：当你往 Parcel 写一个 Binder 对象时，不是把对象本身拷过去，而是写一个扁平描述符，类型大致有：

| 写入方 | 类型（量级） | 含义 |
|---|---|---|
| 写入本地实体（真实 Binder） | `BINDER_TYPE_BINDER` | 我是这个对象的主人 |
| 写入一个代理（引用别人的） | `BINDER_TYPE_HANDLE` | 我持有句柄 x 的引用 |
| 写入文件描述符 | `BINDER_TYPE_FD` | 跨进程传 fd（第 11 章） |
| 弱引用变体 | `BINDER_TYPE_WEAK_*` | 弱引用版本 |

**驱动做的关键转换**：当 A 进程把「自己的本地 Binder（BINDER_TYPE_BINDER）」通过事务发给 B 进程时，驱动在 B 进程侧把它改写成 `BINDER_TYPE_HANDLE` 并分配一个 `binder_ref`（handle），这样 B 拿到的就是一个「遥控器」而不是 A 的私有指针——既安全又实现了跨进程对象引用。这就是为什么「跨进程传 Binder 对象」能成立。

### 6.5 强/弱引用计数与死亡通知

Binder 实体是跨进程对象，生命周期靠内核计数：

- 客户端拿到引用时，native 侧发 `BC_INCREFS`（弱引用 +1）；真正用时 `BC_ACQUIRE`（强引用 +1）。
- 释放时对应 `BC_DECREFS` / `BC_RELEASE`。当某个实体的强引用归零，驱动回调 `BR_RELEASE`，持有者可以销毁对象。
- 死亡通知：`BC_REQUEST_DEATH_NOTIFICATION` 注册后，远端进程死，驱动给引用方发 `BR_DEAD_BINDER`（第 12 章）。

这套机制保证了「跨进程持有的对象」不会在还有人用时被偷偷释放，也不会在主人死后变成野指针。

---

## 7. Java 层：IBinder/Binder/Proxy 与 AIDL 生成代码

### 7.1 三个类的关系

- **`IBinder`**：接口，定义 `transact()`、`queryLocalInterface()`、`linkToDeath()` 等。
- **`Binder`**：Java 层的本地 Binder 实体基类（你的 `Stub` 继承它），内部挂一个 native `BBinder`。
- **`BinderProxy`**：Java 层的远程引用（客户端拿到的），内部挂一个 native `BpBinder`（句柄）。

关键点：**跨进程时，客户端拿到的 `IBinder` 不是服务端的 `Stub` 真身，而是 `BinderProxy`**——它只是个遥控器。同进程的引用则能直接拿到本地实现（见 7.2 的 `queryLocalInterface`）。

### 7.2 AIDL 生成的代码（逐段解读）

你写一个 `IMyService.aidl`，`aidl` 编译器会生成 `IMyService.java`。看懂它是理解 Binder 的第一块敲门砖。下面是真实生成结构的简化版（保留骨架）：

```java
public interface IMyService extends android.os.IInterface {

    // ===== 服务端：Stub 继承自 Binder，它就是服务端的「真身」 =====
    public static abstract class Stub extends android.os.Binder implements IMyService {
        private static final String DESCRIPTOR = "IMyService";
        static final int TRANSACTION_add = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);

        public Stub() {
            this.attachInterface(this, DESCRIPTOR);   // 把接口描述符绑到本地实体
        }

        // 客户端拿到 IBinder 后，用它「还原」成接口
        public static IMyService asInterface(android.os.IBinder obj) {
            if (obj == null) return null;
            // 关键判断：这个 Binder 是不是就在本进程？
            android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin != null && iin instanceof IMyService) {
                return (IMyService) iin;        // 本地 Binder，直接返回真身，不跨进程
            }
            return new Proxy(obj);              // 远端 Binder，包一层 Proxy（遥控器）
        }

        @Override
        public android.os.IBinder asBinder() { return this; }

        // ===== 服务端分发入口：所有跨进程调用都汇聚到这里 =====
        @Override
        public boolean onTransact(int code, android.os.Parcel data,
                                  android.os.Parcel reply, int flags) {
            switch (code) {
                case TRANSACTION_add:
                    data.enforceInterface(DESCRIPTOR);          // 校验接口描述符
                    int _arg0 = data.readInt();
                    int _arg1 = data.readInt();
                    data.enforceNoDataAvail();                  // 确认参数已读完
                    int _result = this.add(_arg0, _arg1);       // ← 调用你写的实现
                    reply.writeNoException();
                    reply.writeInt(_result);
                    return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    // ===== 客户端：Proxy 持有远端引用，负责「假装本地调用」 =====
    private static class Proxy implements IMyService {
        private android.os.IBinder mRemote;

        Proxy(android.os.IBinder remote) { mRemote = remote; }

        @Override
        public int add(int a, int b) {
            android.os.Parcel data  = android.os.Parcel.obtain();
            android.os.Parcel reply = android.os.Parcel.obtain();
            data.writeInterfaceToken(DESCRIPTOR);   // 客户端写接口描述符
            data.writeInt(a);
            data.writeInt(b);
            mRemote.transact(TRANSACTION_add, data, reply, 0); // ← 跨进程发起
            reply.readException();                  // 重放服务端异常
            int result = reply.readInt();
            data.recycle();
            reply.recycle();
            return result;
        }
    }
}
```

逐段要点：

- **`asInterface` 的进程内/跨进程分支**：`queryLocalInterface(DESCRIPTOR)` 命中就直接返回本地实现（**不跨进程**），没命中才包 `Proxy`。这解释了「同进程 Binder 调用很快」——它根本不走内核。
- **`Stub.onTransact` 是总调度台**：无论多少个方法，服务端都从 `onTransact(code, ...)` 一个入口进来，按 `code`（方法编号）分发。AMS 的 `onTransact` 里是几百个 case，对应 `startActivity`、`bindService` 等。
- **`writeInterfaceToken` / `enforceInterface`**：客户端写入、服务端校验「你调的是不是这个接口」，防止拿错服务。
- **`writeNoException` / `readException`**：跨进程传递异常状态；服务端抛出的可跨进程异常，客户端在 `readException()` 处重新感知。
- **`enforceNoDataAvail`**：较新版本生成代码里常见，确认参数读完，防止调用方偷偷塞多余数据。
- **`recycle()`**：`Parcel` 是可复用对象，真实代码通常放在 `finally` 里保证回收。

### 7.3 in / out / inout 与 oneway

AIDL 参数方向影响传输成本（第 6.4 节的扁平化同样适用）：

```aidl
void update(in User user);     // 客户端 → 服务端（最常用）
void fill(out User user);      // 服务端写回客户端（客户端不先传内容）
void modify(inout User user);  // 先传过去，再写回（开销最大）
```

- `in`：内容从客户端传给服务端。
- `out`：客户端不先传内容，服务端负责填结果。
- `inout`：双向，开销最大。

实际开发里，不需要服务端「回填」就优先用 `in`；不要把大对象当普通参数随手塞进 Binder（第 10 章讲限额）。

`oneway` 改变**调用语义**而非「开新线程」：

```aidl
oneway interface IMyCallback {
    void onEvent(int code);
}
```

声明 `oneway` 后：客户端把请求发出去就返回，不等结果；因此不能有返回值，也不能抛需要跨进程传递的 checked exception。但它**不代表服务端立刻并发执行**——只是进入目标进程的异步队列，最终仍由目标 Binder 线程池取出执行；若线程池耗尽，oneway 也会排队（第 9、14 章）。

---

## 8. 一次调用的完整时序：逐段标注发生了什么

把第 7 章的 Java 代码往下追，以客户端 `Proxy.add(1,2)`（同步）为例，逐段标注**每一段发生了什么**：

```text
【客户端进程】
Proxy.add(1,2)
  └─> mRemote.transact(code, data, reply, 0)    // mRemote 是 BinderProxy
        └─> BinderProxy.transactNative(...)      // JNI 进入 native
              └─> BpBinder::transact()           // 客户端 native 代理
                    └─> IPCThreadState::transact()
                          └─> ioctl(BINDER_WRITE_READ, BC_TRANSACTION)
══════════════════════ 内核 binder 驱动（一次拷贝 + 记 uid/pid） ════════════════
                                 │ 数据 copy_from_user 进目标进程 mmap 区
                                 │ 找到目标 node 对应的线程，唤醒它
                                 ▼
【服务端进程】（如 system_server）
IPCThreadState 被 BR_TRANSACTION 唤醒
  └─> 从 mmap 区读数据 → BBinder::transact()      // 服务端 native 基类
        └─> JNI 回调 Java 层 Stub.onTransact(code, data, reply, flags)
              └─> 你的实现 add(1,2) 执行 → reply.writeInt(result)
                    └─> BC_REPLY → 驱动 → 原路 BR_REPLY 回客户端
【客户端线程】
  从 ioctl 返回 → 读 reply → readException() → 拿到 result → 继续
```

逐段发生了什么：

1. **Java Proxy**：把参数写进 `Parcel`，写接口 token，调 `transact`。
2. **JNI → BpBinder**：Java 的 `BinderProxy` 把活交给 native 的 `BpBinder`（持有句柄）。
3. **IPCThreadState → 驱动**：当前线程变成 binder 线程，发 `BC_TRANSACTION`，**同步调用在此阻塞**等 `BR_REPLY`。
4. **驱动**：做「一次拷贝」；记录调用方 `uid/pid`（第 15 章）；根据 handle 找到目标 `binder_node`，唤醒目标进程里空闲的 binder 线程（不够就 `BR_SPAWN_LOOPER`）。
5. **服务端 BBinder → Stub.onTransact**：被唤醒的线程读 `BR_TRANSACTION`，走 `BBinder::transact` → JNI 回到 Java `Stub.onTransact`，按 `code` 分发到你的实现。
6. **回程**：`reply` 写结果 → `BC_REPLY` → 驱动原路送回 → 客户端 `ioctl` 返回 → 读结果。

> 一个常被忽略的事实：**你的服务方法跑在哪个线程？** 通常跑在目标进程的 Binder 线程池线程，不是主线程。所以 `Stub` 实现天然多线程：共享状态要加锁/串行化，耗时操作别长时间占住 binder 线程，要更新 UI 或依赖主线程的逻辑要切回去。系统服务常见做法：Binder 入口先做参数解析、权限校验，把耗时/串行工作丢给 `Handler` 或专门工作线程。

---

## 9. 线程模型：Binder 线程池与重入的坑

### 9.1 线程池默认上限约为 16

Binder 线程由驱动按需要孵化：线程处理 `BR_TRANSACTION` 时若发现池里空闲线程不够，驱动回 `BR_SPAWN_LOOPER` 提示进程再起一个。进程通过 `ProcessState` 把「最大线程数」告诉驱动，历史上**默认上限约为 16**（不同版本/启动方式略有差异，可用 `setMaxThreads` 一类接口调整）。注意这 16 是「驱动允许该进程同时处理 binder 事务的线程上限」，不是进程总线程数。

### 9.2 主线程是否参与

- **发起方**：是普通调用方线程（比如 App 主线程发起 `startActivity`），它阻塞在 `ioctl` 上等回复——所以「在主线程做耗时跨进程调用」会卡 UI（第 14 章 ANR）。
- **服务端**：被唤醒执行 `onTransact` 的是 binder 线程池线程，通常**不是**服务端主线程。但许多系统服务（如 `system_server`）会在启动早期把主线程也注册成 looper 参与 binder 处理——具体看进程初始化是否调用 `joinThreadPool`。不要假设「服务端主线程一定不处理 binder」。

### 9.3 oneway 与并发重入的坑

两个最容易踩的坑：

1. **线程池耗尽导致同步调用挂死**：若服务端所有 binder 线程都阻塞在「等待别的进程的同步返回」上，新来的调用无处安放，客户端同步调用就一直等——表现为调用无响应、甚至触发调用方 ANR。常见于 A 调 B、B 又同步调回 A 的嵌套场景。
2. **重入（re-entrancy）死锁**：当服务在自己的 binder 线程上处理事务 T1，T1 内部又同步调用另一个进程，而那个进程又回调当前进程——可能复用/等待同一个受限的线程池，形成环等待。设计上应：binder 入口尽量快进快出，耗时与可重入逻辑挪到业务线程；回调类接口优先用 `oneway`；避免在 binder 线程持有锁的同时做跨进程同步调用。

---

## 10. 异步与同步事务：oneway、buffer 限制与 TransactionTooLarge

### 10.1 oneway 语义再明确

- **同步（默认）**：客户端线程阻塞等 `BR_REPLY`，有返回值、可抛异常。
- **oneway**：客户端发完 `BC_TRANSACTION` 立即返回，**没有回程**；服务端在它的 binder 线程异步执行；不能有返回值、不能传 checked 异常。

oneway 的价值是「调用方不被服务端速度拖住」，但它**不减小服务端负载**——服务端仍要线程去执行；若线程池满，oneway 同样排队。

### 10.2 异步事务的 buffer 限制（量级结论）

binder 缓冲区是进程级共享的有限资源（第 3 章 mmap 区）。为了不让「-fire-and-forget」的异步事务挤占同步事务的生存空间，**异步（oneway）事务可用的 buffer 份额明显小于同步事务——量级上约为同步可用额度的一半**。这是设计层面的保护，不是某个写死的字节常量；具体数值随版本与配置变化，记住「oneway 能用的 buffer 更紧」即可。

### 10.3 TransactionTooLargeException 的真实原因

这是最高频的 Binder 崩溃之一，但很多人理解错：

- **不是「单次调用限 1MB」**，而是 **binder 缓冲区是进程内所有线程/事务共享的一块约 1MB 量级的区域**。当「本次事务数据 + 当前进程所有在途事务数据」超过这块共享区，就会抛 `TransactionTooLargeException`（native 侧表现为 `BR_FAILED_REPLY` / `errno` 一类）。
- 所以即使单次数据「不到 1MB」，若并发在途事务多，也会触发；反之大对象单独传也可能直接超。
- 典型诱因：通过 AIDL 直接传大 `Bitmap`、大数组、大 JSON、整页 list。

正确姿势（车机/系统服务通用）：

- 大文件：传路径、`ParcelFileDescriptor` 或 fd（第 11 章）。
- 大块内存：用共享内存 / `ashmem`、`ASharedMemory`、硬件 buffer。
- Binder 本身：只传控制信息、小对象、句柄、fd。

下一章讲 `binder_ndk` 与 AIDL 的三种后端，以及 fd 是怎么「零拷贝」跨进程传的。

---

## 11. binder_ndk 与 AIDL 三种后端：跨语言与 fd 传递

### 11.1 AIDL 的三种后端

同一个 `*.aidl` 接口，AOSP 的 `aidl` 编译器可以按不同「后端（backend）」生成代码，对应不同语言/运行时：

| 后端 | 产物 | 典型使用方 | 底层库 |
|---|---|---|---|
| **Java**（默认） | `IInterface` + `Stub`/`Proxy` 的 Java 类 | App、framework Java 服务 | `libbinder` 经 JNI |
| **C++** | `BnInterface`/`BpInterface` 的 C++ 类 | framework native 服务、HAL 上层 | `libbinder`（native） |
| **NDK** | NDK 可见的 C/C++ 接口（`AIBinder` 系列） | **vendor / 应用 native 代码**，需稳定 ABI | `libbinder_ndk` |

> 版本差异：`libbinder_ndk` 以及 AIDL 的 NDK 后端在 Android 8 时期开始引入尝试，**在 Android 10（API 29）前后成为稳定可用、带稳定 ABI 的正式后端**。车机 vendor 若要用 native 服务跨进程，优先走 NDK 后端而不是私拉 `libbinder` 内部符号——NDK 后端保证跨版本 ABI 稳定。

### 11.2 binder_ndk 与 libbinder 的层次关系

`libbinder_ndk` 是 `libbinder` 之上、面向 NDK/稳定 ABI 的一层封装：它把 `libbinder` 的 `BpBinder`/`BBinder`/事务机制包成 NDK 可调用的 `AIBinder`、`AStatus`、`AParcel` 等。Java 侧也可以通过 `AIDL` 生成的 Java 代码和 NDK 侧互通——因为底层都落到同一套 binder 事务（同一个 `BC_TRANSACTION`/`BR_*` 协议）。也就是说：

```text
Java AIDL  ─┐
C++ libbinder ─┤── 同一套 binder 驱动事务（BC_/BR_、handle、node）
NDK libbinder_ndk ─┘
```

三者只是「语言外壳」不同，过的是同一座桥。

### 11.3 文件描述符（fd）跨进程传递

这是 Binder 区别于 socket/共享内存的关键能力（第 2 章提过）。机制：

- AIDL 里用 `ParcelFileDescriptor` / `FileDescriptor` 类型，或 native 用 fd 字段；写入时 Parcel 产生一个 `BINDER_TYPE_FD` 的 `flat_binder_object`。
- 驱动在目标进程里**复制（dup）** 这个 fd，使目标进程拿到一个指向同一内核文件/socket/ashmem 的新 fd 编号。
- 因此「传 fd」几乎零数据拷贝——传的只是「让对方也能打开同一个内核对象」的许可，而不是把文件内容搬一遍。

> 「**Android 视角**」：车机里相机 buffer、Surface 的 graphic buffer、音频共享内存、HAL 与 framework 之间的大数据块，几乎都靠 Binder 传 fd 而不是传数据体。这也是为什么前述「1MB binder buffer」根本装不下图形数据——图形数据走 fd，控制信息才走 binder buffer。

---

## 12. 死亡通知与稳定性：linkToDeath 全链路

Q4「对方死了怎么办」由死亡通知机制回答。它分两条路径，但最终都来自驱动的 `BR_DEAD_BINDER`。

### 12.1 两条注册路径

| 路径 | 注册方式 | 底层命令 |
|---|---|---|
| Java | `IBinder.linkToDeath(DeathRecipient, flags)` | JNI → `BpBinder::linkToDeath` → `BC_REQUEST_DEATH_NOTIFICATION` |
| native | `BpBinder::linkToDeath(DeathRecipient*, cookie)` | 同上 |

注册后，引用方进程持有「对远端实体的带死亡感知的引用」。

### 12.2 DeathRecipient 回调在哪个线程

**死亡回调不跑在发起调用的线程上，而是跑在引用方进程的某个 binder 线程上**（由 `IPCThreadState` 在处理 `BR_DEAD_BINDER` 时触发，进一步回调到 Java `DeathRecipient.binderDied()` 或 native `DeathRecipient::binderDied()`）。这意味着：

- `binderDied()` 里不要假设自己在「调用方上下文」；
- 回调在 binder 线程，注意和主线程/业务线程的同步；
- 不要在 `binderDied()` 里做长耗时或再发起可能重入的同步 binder 调用（踩第 9.3 的坑）。

### 12.3 服务端崩溃时客户端的完整事件链

```text
1. 服务端进程死亡（被杀 / crash / 重启）
2. 内核 binder 驱动标记该进程的所有 binder_node 为 dead
3. 驱动向每个持有这些 node 引用的进程，发送 BR_DEAD_BINDER
4. 客户端 IPCThreadState 处理 BR_DEAD_BINDER
      └─> 触发该引用的 DeathRecipient 回调（binder 线程）
            └─> Java: binderDied() / native: binderDied()
5. 此后客户端再对这个 dead binder 做 transact → 抛 DeadObjectException
   （而非收到 BR_DEAD_REPLY——BR_DEAD_REPLY 是「本次同步调用目标已死」的即时回复）
```

实践中，系统服务管理 callback、App 绑定远端服务、native 服务互相依赖时都会用 `linkToDeath`：在 `binderDied()` 里清理缓存、移除 callback、触发重连/重绑定。因为跨进程调用不是本地方法调用，远端随时可能死，客户端必须同时处理 `DeadObjectException`（调用时）和死亡回调（被动通知）两条线。

---

## 13. 调试：service list、调试节点与 trace

### 13.1 用户态命令

```bash
# 列出当前 servicemanager 注册的所有服务（名字 + 所在进程信息）
adb shell service list

# 列出所有可被 dumpsys 的服务（看某个服务的 dump 输出）
adb shell dumpsys -l

# 直接 dump 某个服务（例如 activity / window / 你自己的 vendor 服务）
adb shell dumpsys <服务名>

# 看进程里 binder 线程与引用概况（需要权限/eng 或 root）
adb shell cat /proc/<pid>/status   # 间接：Threads 数、Binder 相关
```

> 「**Android 视角**」：vendor 服务注册在 `vndservicemanager`，要用 `vndservice list` / `vndservice check`（`/dev/vndbinder`）而不是 `service list`（`/dev/binder`）。用错命令会「看不到自己的服务」。

### 13.2 内核 binder 调试节点（/sys/kernel/debug/binder/）

eng/userdebug + root 下可读（user 版本通常不可见，需 `mount -t debugfs` 且权限）：

| 节点 | 看什么 |
|---|---|
| `state` | 各进程的 `binder_proc` 概要：线程数、已分配/空闲 buffer、引用与 node 数、各线程 looper 状态。定位「线程池满」「buffer 泄漏」。 |
| `stats` | 累计统计：事务数、buffer 分配/释放次数、各类 BR_ 计数。看趋势。 |
| `transactions` | 当前在途事务快照：谁调谁、code、大小、状态。看「卡住的事务」。 |
| `transaction_log` | 最近若干条已完成事务的环形日志（调用方/服务端 pid、handle、code、size）。排查「刚发生了什么」。 |
| `failed_transaction_log` | **失败事务**日志：`BR_FAILED_REPLY`、超大事务、无效目标等。配合 `TransactionTooLargeException` 看根因。 |

```bash
# 例：看失败事务（定向排查 TransactionTooLarge / 无效目标）
adb shell cat /sys/kernel/debug/binder/failed_transaction_log
# 例：看整体状态（线程池 / buffer 占用）
adb shell cat /sys/kernel/debug/binder/state
```

### 13.3 trace / systrace 中的 binder 轨

atrace / perfetto 里关注 `binder_driver`、`binder_lock` 等 category。一次跨进程调用在 trace 里表现为：调用方线程 `binder_transaction` → 在 `binder:` 轨上等待 → 服务端线程被唤醒执行 → 回程。用它定位「跨进程调用卡在哪一侧」。

### 13.4 logcat 的关键字

| 关键字 | 对应问题 |
|---|---|
| `TransactionTooLargeException` / `!!! FAILED BINDER TRANSACTION !!!` | 事务超限（第 10 章） |
| `BR_FAILED_REPLY` | 事务失败（目标无效/超限） |
| `BR_DEAD_REPLY` / `DeadObjectException` | 目标已死 |
| `AndroidRuntime: Binder` / `BinderProxy` | Binder 相关崩溃栈 |
| `binder: <pid>: <错误>` | 驱动侧报错（如 buffer 分配失败） |
| `libbinder: BpBinder` / `waiting for ...` | 引用等待/线程池相关 |

---

## 14. 常见问题排查表

现象 → 原因 → 章节 → 第一命令（四列）。优先用第一命令确认方向，再回指章节深挖。

| # | 现象 | 原因（量级/方向） | 章节 | 第一命令 |
|---|---|---|---|---|
| 1 | 调用远端抛 `DeadObjectException` | 目标进程已死或重启；引用失效 | 第 12 章 | `adb shell service list` 确认服务是否还在 |
| 2 | `TransactionTooLargeException` | 进程内 binder buffer（约 1MB 量级共享）被在途事务占满或大对象直传 | 第 10 章 | `cat /sys/kernel/debug/binder/failed_transaction_log` |
| 3 | oneway 调用「悄悄没反应」 | oneway 无回程，目标死/线程池满/被丢弃时不报异常 | 第 10、12 章 | `dumpsys <服务>` 看目标状态 + `state` 看线程池 |
| 4 | `ServiceManager.getService` 返回 null / 找不到服务 | 服务未注册、名字拼错、或用了错的服务管理器 | 第 5 章 | `service list`（系统）/ `vndservice list`（vendor） |
| 5 | binder 线程耗尽，调用挂死/ANR | 线程池（默认约 16）全阻塞在同步跨进程等待，新调用无线程处理 | 第 9 章 | `cat /sys/kernel/debug/binder/state` 看线程 looper 状态 |
| 6 | RPC 无响应导致 ANR | 同步调用卡在远端慢逻辑 / 远端线程池满 / 嵌套重入死锁 | 第 8、9 章 | `dumpsys activity` + trace 看卡在哪一侧 |
| 7 | fd 泄漏（进程 fd 数持续涨） | 跨进程传 fd 后接收方未关闭，或 `ParcelFileDescriptor` 未回收 | 第 11 章 | `ls /proc/<pid>/fd | wc -l` 观察增长 |
| 8 | 跨用户 binder 调用被拒 | 跨 `user` 的 binder 访问受限（多用户/工作资料场景） | 第 15 章 | `dumpsys <服务>` 看 uid/调用方；检查 SELinux |
| 9 | 收到 `BR_DEAD_REPLY` | 本次同步调用的目标在事务途中死亡 | 第 12 章 | `service list` 确认目标存活 |
| 10 | 收到 `BR_FAILED_REPLY` | 事务失败：目标无效、buffer 不足、权限/SELinux 拒绝 | 第 10、15 章 | `failed_transaction_log` + `logcat | grep binder` |
| 11 | `clearCallingIdentity` 用错致鉴权串味 | 在事务外调用 `getCallingUid` 拿到的是自身 uid，或忘记 restore | 第 15 章 | 审查调用点是否仍在 transact 上下文中 |
| 12 | 静态持有 BinderProxy 致对象/服务不释放 | 把远端引用存进 static，引用计数不归零，服务端实体无法销毁 | 第 6.5 章 | `dumpsys <服务>` 看 node/ref 计数异常 |
| 13 | 重入死锁（A↔B 互调卡死） | binder 线程池受限下同步嵌套调用形成环等待 | 第 9.3 章 | trace 看两侧线程互等 |
| 14 | `mmap` 失败 / binder 初始化失败 | binder 缓冲区分配失败，进程 binder 不可用 | 第 3 章 | `logcat | grep -i "binder\|mmap"` |
| 15 | 异步事务偶发失败但同步正常 | 异步可用 buffer 份额更小（约同步一半量级），更易触发超限 | 第 10.2 章 | `failed_transaction_log` 比对 oneway 事务 |
| 16 | vendor 服务「注册/查找」总失败 | 错用 `/dev/binder` 而非 `/dev/vndbinder`（vndservicemanager） | 第 5.4 章 | `vndservice list` / 检查 `servicemanager.rc` 域 |
| 17 | SELinux 报 `avc: denied` 涉及 binder | 调用方/服务端类型无权进行该 binder 调用 | 第 15 章 | `logcat | grep avc` 看 binder 相关拒绝 |
| 18 | 同进程 Binder 调用却走了内核 | `queryLocalInterface` 未命中（接口描述符不匹配/跨 classloader） | 第 7.2 章 | 检查 DESCRIPTOR 与接口一致性 |

下一章讲安全与权限层：uid/pid 自认证和 SELinux 如何约束 binder 调用。

---

## 15. 安全与权限层：uid/pid 自认证与 SELinux

Binder 的安全价值不只是「能跨进程」，而是内核把调用方身份直接带到服务端，且调用受 SELinux 约束。

### 15.1 uid/pid 自带认证（来自驱动）

每次 `BR_TRANSACTION` 到达服务端时，驱动已经把**调用方进程的 `uid` 和 `pid`** 记录在事务上下文里。服务端可随时取用，无需信任客户端自报：

```java
int uid = Binder.getCallingUid();   // 调用方 uid
int pid = Binder.getCallingPid();   // 调用方 pid
```

系统服务据此做鉴权：判断调用方是不是 `system` uid、有没有某 `permission`、是不是当前用户下的合法应用。

**缓存陷阱（必踩点）：**

- `getCallingUid()`/`getCallingPid()` 的值来自「当前线程的调用事务上下文」，由驱动在 `BR_TRANSACTION` 时设置、事务结束后清除。所以在**非 binder 线程**（例如你把活 post 到一个 `Handler`/工作线程后再去取）上调用，拿到的是**本进程自己的 uid/pid**，不是原始调用方——鉴权会失效或误判。
- 同一 binder 线程处理嵌套调用时，上下文会被新事务覆盖，注意在入口处先取好值。

### 15.2 clearCallingIdentity 的语义与陷阱

当你在 binder 线程上替远端调用方办事，又要**以本进程自己的身份**去访问别的系统服务时（例如系统服务内部转发），需要临时清掉「调用方身份」：

```java
final long token = Binder.clearCallingIdentity();   // 保存并切换到本进程身份
try {
    // 以当前进程身份访问其他服务（不被远端 uid 限制）
} finally {
    Binder.restoreCallingIdentity(token);           // 必须恢复，否则后续身份错乱
}
```

- `clearCallingIdentity` 保存「当前调用身份」并把线程身份重置为本进程（orig）的 uid/pid；
- **`restoreCallingIdentity` 不调用会导致该线程后续所有 binder 调用的身份都变成本进程**——鉴权串味，是隐蔽的安全/逻辑 bug。

### 15.3 SELinux 对 binder 调用的约束

> 「**Android 视角**」：光有 uid/pid 还不够，Android 用 SELinux 给 binder 调用加第二道闸。

- 每个系统服务有对应的 SELinux 类型；`servicemanager` 自身也按域隔离（系统/`vndservicemanager`/hwservicemanager）。
- binder 调用受 `binder_call` 一类规则约束：主体（调用方类型）是否允许向客体（服务端类型）发起 binder 调用；服务注册/查找还要过 `binder_service` 与 `service_contexts` 的校验。
- 常见报错：`avc: denied { call } ...` 涉及 binder。此时不是 Java 权限问题，而是 SELinux 策略问题。

这部分属于横向专题，详见 `androidOthers/` 下的 **SELinux 篇**（本系列关联的横向篇），那里会讲 `service_contexts`、类型定义与如何为 vendor 服务补策略。

---

## 16. 读源码的推荐路线

按目标挑一条，不要一上来通读全栈。每条都是「从入口追到落点」的最短路径。

### 16.1 AIDL 生成类 → Java Binder → native Parcel → 驱动事务（主线）

```text
你的 IMyService.aidl
  → 生成的 IMyService.Stub / Proxy（frameworks/base/... 或 out/ 目录）
  → android.os.Binder / BinderProxy（frameworks/base/core/java/android/os/Binder.java）
  → JNI：android_util_Binder.cpp
  → BpBinder / BBinder / IPCThreadState（frameworks/native/libs/binder）
  → ioctl(BINDER_WRITE_READ) → 驱动 binder_ioctl（kernel drivers/android/binder.c）
```

### 16.2 ServiceManager 注册与查找链

```text
ServiceManager.addService / getService（Java）
  → defaultServiceManager() → getContextObject(0) → BpBinder(0)
  → native servicemanager 服务（frameworks/native/cmds/servicemanager）
  → 驱动对 handle 0 的 context manager 特殊路由
```

### 16.3 死亡通知链

```text
IBinder.linkToDeath（Java）
  → BpBinder::linkToDeath → BC_REQUEST_DEATH_NOTIFICATION
  → 驱动在 node 死亡时发 BR_DEAD_BINDER
  → IPCThreadState 处理 → DeathRecipient.binderDied()
```

### 16.4 oneway / 异步事务路径

```text
AIDL oneway 方法
  → transact(..., FLAG_ONEWAY) → BC_TRANSACTION（flags 含 oneway）
  → 驱动不回 BR_REPLY；目标侧异步入队执行
  → 关注异步 buffer 份额（第 10.2 章）
```

### 16.5 线程池孵化路径

```text
ProcessState 设最大线程数
  → 驱动 BR_SPAWN_LOOPER
  → 进程 spawn 新 binder 线程 → IPCThreadState::joinThreadPool
  → 该线程进入 talkWithDriver 循环处理 BR_TRANSACTION
```

---

## 17. 关键源码路径速查

| 内容 | 路径 |
|---|---|
| Java 层 Binder / BinderProxy / Parcel | `frameworks/base/core/java/android/os/Binder.java`、`BinderProxy.java`、`Parcel.java` |
| Java → Native 的 JNI | `frameworks/base/core/jni/android_util_Binder.cpp` |
| native libbinder | `frameworks/native/libs/binder/`（`BpBinder.cpp`、`BBinder.cpp`、`IPCThreadState.cpp`、`ProcessState.cpp`、`Parcel.cpp`、`IInterface.cpp`） |
| AIDL 编译器 | `frameworks/native/cmds/aidl/`（含 Java/C++/NDK 后端生成） |
| NDK 封装 | `frameworks/native/libs/binder/ndk/`（即 `libbinder_ndk`，`AIBinder`/`AParcel` 等） |
| servicemanager 服务 | `frameworks/native/cmds/servicemanager/`（`service_manager.c`、对应 `.rc`） |
| vndservicemanager / hwservicemanager | 同目录，由 init 按域拉起（见第 5.4 章） |
| 内核 binder 驱动 | `drivers/android/binder.c`；旧版在 `drivers/staging/android/` |
| 内核 binder 头/协议 | `drivers/android/binder_alloc.c`、`include/uapi/linux/android/binder.h`（BC_/BR_ 定义） |
| AMS 接口（AIDL 示例） | `frameworks/base/core/java/android/app/IActivityManager.aidl` |
| SystemServer 启动服务 | `frameworks/base/services/java/com/android/server/SystemServer.java` |

> 版本差异：内核 binder 源码在不同 AOSP 版本里路径/拆分略有不同（如 `binder_alloc` 是否独立文件）；`libbinder_ndk` 目录在较新版本才出现。对号入座时以你 checkout 的版本为准。

---

## 18. 一图总结与关联阅读

### 18.1 一次跨进程调用的分层 ASCII

```text
┌─────────────── 客户端进程 ───────────────┐      ┌────────── 内核 binder 驱动 ──────────┐
│ AIDL Proxy                                    │      │ 一次拷贝（mmap 区）                 │
│   │ transact()                                │      │ 记录调用方 uid/pid                  │
│   ▼                                           │      │ node/ref 映射（handle）             │
│ BinderProxy ─JNI─> BpBinder                   │──ioctl─▶ 唤醒目标线程                    │
│                         │ IPCThreadState      │      └───────────────────────────────────┘
└──────────────────────────────────────────────┘                    │
                                                      BR_TRANSACTION│
┌─────────────── 服务端进程 ───────────────┐      ┌─────────────────▼────────────────────┐
│ IPCThreadState 被唤醒                      │      │                                     │
│   ▼                                        │      │                                     │
│ BBinder.onTransact ─JNI─> Stub.onTransact  │      │                                     │
│   │ 你的真正实现                            │      │                                     │
│   ▼ 写 reply ─BC_REPLY─> 原路返回          │◀─────│                                     │
└────────────────────────────────────────────┘      └─────────────────────────────────────┘
                 ▲                                          │
                 │ 注册 / 查找（handle 0 = ServiceManager）  │
            ServiceManager（电话簿，分系统/vendor/hwbinder 域）
```

**一句话记忆链：**
> 拿到遥控器（`asInterface`）→ 写进 `Parcel`（`transact`）→ native `BpBinder` 发 `BC_TRANSACTION` → 驱动「一次拷贝 + 记身份」唤醒对端 → 对端 `BBinder.onTransact` 执行 → `BC_REPLY` 回程；对方会死（`linkToDeath`），线程池有上限（约 16），buffer 共享且有限（约 1MB 量级），fd 走零拷贝。

### 18.2 关联阅读

- **05 / 06 章（AMS / ActivityManagerService）**：本篇的 Binder 机制在 AMS 上如何落地——`IActivityManager` 就是 `Stub`/`Proxy` 的真实例子，`startActivity` 是跨进程到 `system_server` 的典型调用。
- **10 章（ATMS / ActivityTaskManagerService）**：新版 Activity 启动主线迁移到 ATMS，同样走 Binder；追踪时确认入口落在 AMS 还是 ATMS（版本差异）。
- **09 章（ContentProvider / CP）**：`ContentProvider` 的 `query/insert` 等也经 Binder，且涉及 `stable`/`unstable` 引用与死亡语义，可对照第 12 章。
- **`androidOthers/` 的 NDK 与 JNI 篇**：本篇第 11 章的 `libbinder_ndk` 衔接点——理解 Java↔native 边界、引用类型差异。
- **`androidOthers/` 的 SELinux 篇**：第 15.3 章提到的 `service_contexts`、`binder_call` 规则与 vendor 服务策略，在那里展开。
- **`androidOthers/` 的存储 / 共享内存篇**：第 10.3 章「大对象改用共享内存/ashmem」的下游方案。

---

## 小结

- **Binder = 进程间函数调用路由器**，靠「一次拷贝」「对象引用计数」「进程死亡感知」「C/S + 身份自认证」四个独特设计胜出（第 2、3、6、12、15 章）。
- **AIDL 生成的 `Stub` 是服务端真身、`Proxy` 是客户端遥控器**，`onTransact` 是调用汇聚点；同进程走 `queryLocalInterface` 本地优化（第 7 章）。
- **一次调用 = Java(Proxy) → native(BpBinder/IPCThreadState) → 驱动 → native(BBinder) → Java(Stub.onTransact)**，同步等 `BR_REPLY`、oneway 无回程（第 8、10 章）。
- **ServiceManager 是电话簿（handle 0）**，且有系统/`vnd`/`hw` 三域之分，vendor 服务别用错节点（第 5 章）。
- **线程池默认约 16、buffer 共享约 1MB 量级、fd 走零拷贝**——这是三类最常踩坑的边界（第 9、10、11 章）。
- **死亡感知与身份鉴权是 Binder 安全的两根支柱**：`linkToDeath` 被动通知，`getCallingUid` 自认证但受线程上下文陷阱约束（第 12、15 章）。

如果只记一个核心模型：

> Binder 是一座「带身份认证的单向桥」——你拿到的是遥控器，参数被拍扁装进一块共享映射区（一次拷贝），对端线程被叫醒执行，结果原路送回；桥会告诉你对方死了，也记得谁在过桥。
