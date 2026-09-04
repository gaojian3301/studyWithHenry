# Binder 机制详解：从 AIDL 到 Framework 层

> 以 AMS 为切入点，讲清楚你天天在 AIDL 里用的 Binder，在 Framework 层和内核里到底发生了什么。
> 目标读者：写过 AIDL、做过系统应用，但没深入过 native / 内核层的 Android 开发者。

---

## 目录

1. [先建立直觉：Binder 是什么](#1先建立直觉binder-是什么)
2. [AIDL 到底生成了什么](#2aidl-到底生成了什么)
3. [一次调用的完整旅程：Java → Native → 内核](#3一次调用的完整旅程java--native--内核)
4. [内核 Binder 驱动：为什么只要「一次拷贝」](#4内核-binder-驱动为什么只要一次拷贝)
5. [ServiceManager：系统服务的电话簿](#5servicemanager系统服务的电话簿)
6. [结合 AMS：一次真实的启动调用链](#6结合-ams一次真实的启动调用链)
7. [AIDL 实战里最容易忽略的细节](#7aidl-实战里最容易忽略的细节)
8. [一图总结](#8一图总结)
9. [关键源码路径速查](#9关键源码路径速查)

---

## 1. 先建立直觉：Binder 是什么

**一句话：Binder 是一个「进程间的函数调用路由器」。**

你写 AIDL 时的体感是「我调了个方法，返回值就回来了」，好像和本地调用没区别。但方法真正执行的地方，可能是另一个进程。Binder 干的事，就是把这个「像本地调用」的假象维持住：

- 把你传的**参数**打包（序列化成 `Parcel`）
- 通过**内核**把数据投递到目标进程
- 在目标进程里找到对应的**服务对象**、执行真正的方法
- 把**返回值**再打包传回来

**为什么 Android 不用现成的 socket / pipe / 共享内存，非要自己造一个 Binder？**

| 机制 | 拷贝次数 | 传 fd | 安全（谁能调用我） |
|---|---|---|---|
| socket / pipe | 2 次（用户→内核→用户） | 麻烦 | 无法识别调用方身份 |
| 共享内存 | 0 次但需自己同步 | 否 | 无法识别调用方身份 |
| **Binder** | **1 次**（见第 4 节） | **天然支持** | **内核记录调用方 uid/pid** |

两个关键优势决定了 Android 选 Binder：

1. **性能**：只拷贝一次，比 socket 少一半开销。
2. **安全**：内核能精确知道「谁在调用我」（uid/pid），系统服务可以据此做权限校验。这对 AMS 这种「谁都能问、但必须鉴权」的服务至关重要。
3. **能传文件描述符（fd）**：这是 Binder 的独门绝技。你后面学 SurfaceFlinger 会看到，应用和 SurfaceFlinger 之间传递 `Surface` 的 buffer，靠的就是 Binder 跨进程传 fd。你做的车载视频播放，底层也是这条通路。

---

## 2. AIDL 到底生成了什么

你写一个 AIDL，编译器（`aidl`）会生成一个 Java 类。**看懂这个生成的类，是理解 Binder 的第一块敲门砖。**

假设你写：

```java
// IMyService.aidl
interface IMyService {
    int add(int a, int b);
}
```

生成后的 `IMyService.java` 核心结构如下（已简化，保留骨架）：

```java
public interface IMyService extends android.os.IInterface {

    // ===== 服务端：Stub 继承自 Binder，它就是服务端的「真身」 =====
    public static abstract class Stub extends android.os.Binder implements IMyService {
        private static final String DESCRIPTOR = "IMyService";
        static final int TRANSACTION_add = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);

        public Stub() {
            this.attachInterface(this, DESCRIPTOR);
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

        // ===== 服务端分发入口：所有跨进程调用，最终都汇聚到这个方法 =====
        @Override
        public boolean onTransact(int code, android.os.Parcel data,
                                  android.os.Parcel reply, int flags) {
            switch (code) {
                case TRANSACTION_add:
                    data.enforceInterface(DESCRIPTOR);
                    int _arg0 = data.readInt();
                    int _arg1 = data.readInt();
                    data.enforceNoDataAvail();
                    int _result = this.add(_arg0, _arg1);   // ← 调用你写的实现
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
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(a);
            data.writeInt(b);
            mRemote.transact(TRANSACTION_add, data, reply, 0); // ← 跨进程从这里发起
            reply.readException();
            int result = reply.readInt();
            data.recycle();
            reply.recycle();
            return result;
        }
    }
}
```

这段代码仍然是简化版，但已经保留了真实 AIDL 生成代码里几个很关键的动作：

- `writeInterfaceToken(DESCRIPTOR)` / `enforceInterface(DESCRIPTOR)`：客户端写入接口描述符，服务端校验“你调的是不是这个接口”。
- `writeNoException()` / `readException()`：跨进程传递异常状态。服务端方法抛出可跨进程传递的异常时，客户端会在 `readException()` 这里重新感知到。
- `enforceNoDataAvail()`：较新版本生成代码里常见，用来确认参数已经读完，防止调用方偷偷塞入多余数据。
- `recycle()`：`Parcel` 是可复用对象，用完要回收，真实代码通常会放在 `finally` 里保证执行。

这里有五个**必须刻进脑子**的点：

### 点 1：`Stub` 继承自 `Binder`，它自己就是一个 Binder 对象

`Binder` 类是 Java 层对「Binder 内核对象」的封装。服务端进程里，你 new 出来的 `Stub` 对象，就是那个真正「活在本地」的服务实体。`attachInterface(this, DESCRIPTOR)` 把它和接口描述符绑在一起，方便后面校验。

### 点 2：`onTransact` 是服务端的「总调度台」

不管客户端发来多少种方法调用，服务端都只从 **`onTransact(int code, ...)`** 这一个入口进来，然后根据 `code`（方法编号）分发到具体实现。`AMS` 的 `onTransact` 里，是几百个 case，分别对应 `startActivity`、`bindService`、`broadcastIntent` 等。

### 点 3：客户端拿到的 `mRemote` 不是 `Stub`，而是 `BinderProxy`

这是最容易误解的地方。**对于跨进程调用，客户端拿到的 `IBinder` 对象不是服务端的 `Stub` 真身，而是一个 `BinderProxy`**——它只是一个「遥控器」，内部持有一个 native 指针，指向 native 层的 `BpBinder`。

`asInterface` 里那句 `queryLocalInterface(DESCRIPTOR)` 就是在判断：
- 如果这个 Binder 恰好就在**本进程**（比如服务端自己拿到了自己的引用），`queryLocalInterface` 能直接返回本地实现，就不用跨进程了；
- 否则说明是**远端**引用，包一层 `Proxy`。

这个判断也解释了为什么「同一进程内的 Binder 调用」很快——它根本不走内核。

### 点 4：跨进程传对象，不是传 Java 对象本身

AIDL 里的参数会被写进 `Parcel`。如果你传的是 `Parcelable`，跨进程后拿到的是**反序列化出来的新对象**，不是原来的同一个 Java 对象。

```aidl
void update(in User user);
void fill(out User user);
void modify(inout User user);
```

参数方向也会影响传输成本：

- `in`：客户端把对象内容传给服务端，最常用。
- `out`：客户端不把对象内容传过去，服务端负责写回结果。
- `inout`：先从客户端传给服务端，再从服务端写回客户端，开销最大。

实际开发里，如果不是特别需要让服务端“回填”对象，优先用 `in`。更重要的是：不要把大对象当普通方法参数随手塞进 Binder，后面第 7 节会讲原因。

### 点 5：`oneway` 是异步调用，但不是开新线程魔法

普通 AIDL 方法默认是**同步调用**：客户端线程会阻塞在 `transact()`，等服务端执行完成并写回 `reply` 后才继续往下走。

如果接口或方法声明了 `oneway`：

```aidl
oneway interface IMyCallback {
    void onEvent(int code);
}
```

它的语义会变成：客户端把请求发出去就返回，不等待服务端结果。因此 `oneway` 方法不能有返回值，也不能抛需要跨进程传递的 checked exception。

但要注意，`oneway` **不等于服务端一定立刻并发执行**。它只是进入目标进程的异步 Binder 队列，最终仍然要由目标进程的 Binder 线程池取出来执行。如果服务端线程池被耗尽，`oneway` 调用也会排队。

---

## 3. 一次调用的完整旅程：Java → Native → 内核

把上一节的 Java 代码往下追，就进入了 Framework 的 native 层。以客户端 `Proxy.add(1, 2)` 为例，完整链路是：

```
【客户端进程】
Proxy.add(1,2)
  └─> mRemote.transact(code, data, reply)          // mRemote 是 BinderProxy
        └─> BinderProxy.transactNative(...)         // JNI 调用
              └─> BpBinder::transact()              // native 层的「代理」
                    └─> IPCThreadState::transact()  // 真正负责收发数据的线程对象
                          └─> ioctl(BINDER_WRITE_READ)   // 进入内核 Binder 驱动
════════════════════════ 内核 Binder 驱动 ════════════════════════
                                 │ 数据写入目标进程的等待队列，唤醒目标线程
                                 ▼
【服务端进程】(比如 system_server)
IPCThreadState 被唤醒
  └─> 从内核读出数据 → BBinder::transact()        // native 层的「服务端」
        └─> 回调到 Java 层 Stub.onTransact(code, ...)
              └─> 你的实现 add(1,2) 执行 → 结果写回 reply
                    └─> 原路返回客户端
```

这一层有几个关键类，认识它们就够用了：

| 类 | 层 | 作用 | 类比 |
|---|---|---|---|
| `BinderProxy` | Java | 客户端持有的远端引用 | 遥控器 |
| `Binder` / `Stub` | Java | 服务端实体 / 分发台 | 被遥控的机器 |
| `BpBinder` | native C++ | Bp = Binder Proxy，客户端的 native 代理 | 遥控器里的红外发射器 |
| `BBinder` | native C++ | 服务端的 native 基类 | 机器里的接收器 |
| `ProcessState` | native C++ | **进程单例**：打开 `/dev/binder`、做 mmap 映射 | 电话总机 |
| `IPCThreadState` | native C++ | **每线程一个**：真正执行 transact 收发 | 接线员 |

**记忆口诀：`Bp` 是 Proxy（客户端），`BB` 是服务端，`IPCThreadState` 是跑腿的，`ProcessState` 是总机。**

`ProcessState` 为什么重要？它在一个进程第一次用 Binder 时初始化，干了两件大事：
1. `open("/dev/binder")` —— 打开 binder 设备；
2. `mmap` —— 把内核缓冲区映射到用户空间（这是「一次拷贝」的关键，见下一节）。

还有一个经常被忽略的问题：**服务端方法到底跑在哪个线程？**

跨进程 Binder 调用到达服务端后，通常不是跑在服务端主线程，而是跑在 Binder 线程池里的某个线程。也就是说，你在 `Stub` 实现里写的代码天然要面对多线程：共享数据要加锁或串行化，耗时操作不要长时间占住 Binder 线程，更新 UI 或依赖主线程的逻辑要切回主线程。

系统服务里常见的写法是：Binder 入口先做参数解析、权限校验和少量状态判断，然后把真正耗时或需要串行处理的工作丢给 `Handler`、专门的工作线程，或者服务自己的内部调度器。

---

## 4. 内核 Binder 驱动：为什么只要「一次拷贝」

传统 IPC（socket / pipe）传数据，要拷贝**两次**：

```
发送方用户空间 ──copy_from_user──> 内核缓冲区 ──copy_to_user──> 接收方用户空间
         (第 1 次拷贝)                    (第 2 次拷贝)
```

Binder 只拷贝**一次**，秘诀在于服务端启动时的那次 `mmap`：

- 服务端进程（如 system_server）初始化时，`ProcessState` 通过 `mmap` 建立一块 Binder 映射区域。
- 客户端发起调用时，Binder 驱动把发送方用户空间的数据拷贝到目标进程对应的 Binder 缓冲区。
- 这块缓冲区已经映射到接收方用户空间，所以服务端**不用再经历一次 `copy_to_user`**，可以直接从映射区域读取。

```
发送方用户空间 ──copy_from_user──> 内核缓冲区
                                    │
                                    └──(已 mmap 映射)──> 接收方用户空间直接可见
                                           （0 次额外拷贝）
```

**这就是 Binder 比 socket 高效的本质**：靠 `mmap` 省掉了一次 `copy_to_user`。

严格说，这里的“一次拷贝”讲的是普通 transaction 数据本身：从发送方用户空间拷贝到目标进程的 Binder 映射缓冲区。接收方能直接读到这块映射区域，所以省掉传统 IPC 里的第二次拷贝。

> 补充：这里的「一次拷贝」是数据拷贝。Binder 还支持传 fd，传 fd 时传递的是 fd 在目标进程里的编号映射，几乎零数据开销——这也是为什么 SurfaceFlinger 能高效地在进程间传递图形 buffer。

---

## 5. ServiceManager：系统服务的电话簿

问题来了：客户端要调 AMS，但怎么拿到 AMS 的 `BinderProxy`？总不能靠猜。

答案是一个**特殊的 Binder 服务**——**ServiceManager**。它扮演「电话簿」的角色：

- **服务端注册**：AMS、WMS、PMS 启动时，调用 `ServiceManager.addService("activity", binder)`，把自己的 Binder 引用登记进去。
- **客户端查找**：应用进程调用 `ServiceManager.getService("activity")`，拿到对应的 `BinderProxy`。

ServiceManager 自己的 Binder 句柄是写死的 **0**（所有进程都知道「找电话簿，就打 0 号」），所以它能被所有人找到。

不过“能找到电话簿”不代表“能随便登记号码”。系统服务的注册和访问还会受权限、SELinux、`service_contexts` 等策略约束。普通应用一般只能查询公开的系统服务，不能随便把自己注册成系统级服务。

对应到你熟悉的代码：

```java
// 应用层看到的入口（以 AMS 为例）
ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
```

`getSystemService` 内部一路追下去，最终就是：

```java
IBinder b = ServiceManager.getService("activity");   // 拿到 AMS 的 BinderProxy
IActivityManager am = IActivityManager.Stub.asInterface(b);  // 包成 Proxy
```

---

## 6. 结合 AMS：一次真实的启动调用链

现在把前面所有东西串起来，看一次真实的 `startActivity` 是怎么跨进程到 AMS 的。

**角色分工：**

- **AMS 是服务端**：`ActivityManagerService` 跑在 `system_server` 进程里，它继承 `IActivityManager.Stub`。
- **应用进程是客户端**：`ActivityThread` 里发起调用。

**服务端出生（SystemServer）：**

```java
// SystemServer.java
private void startBootstrapServices() {
    ...
    ActivityManagerService am = new ActivityManagerService(mSystemContext);
    ServiceManager.addService("activity", am);   // 注册进电话簿
    ...
}
```

**客户端调用（应用进程）：**

```java
// Activity.java 里 startActivity 一路往下，最终到 ActivityThread 或 ActivityTaskManager：
IActivityManager am = ActivityManager.getService();   // 底层 = ServiceManager.getService("activity")
am.startActivity(...);   // ← 这一行，就是跨进程 Binder 调用
```

> 注：较新 Android 版本里，Activity 启动相关逻辑大量迁移到了 `ActivityTaskManagerService`（ATMS）和 `IActivityTaskManager`。本文仍用 AMS 作为主线，是为了把 Binder 机制讲顺；真实源码追踪时，需要结合具体 Android 版本看入口落在 AMS 还是 ATMS。

`am.startActivity(...)` 实际执行的是 `IActivityManager.Stub.Proxy.startActivity(...)`，它把参数写进 `Parcel`，调 `mRemote.transact(...)`，然后：

1. 数据经内核 Binder 驱动投递到 `system_server`；
2. `system_server` 的 binder 线程池里，某个线程被唤醒；
3. 走到 `ActivityManagerService.onTransact()`；
4. 分发到真正的 `startActivity()` 实现；
5. 后续 AMS 再去调 Zygote fork 进程、通知 WMS 建窗口……（这是下一讲的内容）。

**所以你现在可以这样理解 AMS：**

> AMS = 一个「住在 system_server 里、用 Binder 对外服务的系统服务」。应用进程里的 `IActivityManager` 只是它的 Proxy（遥控器）。你写应用时调 `startActivity`，本质上就是隔着 Binder 遥控 system_server 里的 AMS 干活。

---

## 7. AIDL 实战里最容易忽略的细节

前面讲的是主链路，下面这些是写 AIDL 或看 Framework 代码时最容易踩坑的地方。

### 7.1 服务端可以知道“谁在调用我”

Binder 的安全价值不只是“能跨进程”，而是内核会把调用方身份带到服务端。服务端可以这样取调用方信息：

```java
int uid = Binder.getCallingUid();
int pid = Binder.getCallingPid();
```

系统服务会基于 uid/pid 做权限校验，比如判断调用方是不是 system uid、有没有某个 permission、是不是当前用户下的合法应用。

Framework 里还经常能看到这一组代码：

```java
final long token = Binder.clearCallingIdentity();
try {
    // 以当前进程自己的身份执行后续逻辑
} finally {
    Binder.restoreCallingIdentity(token);
}
```

它的意思是：当前 Binder 线程正在替远端调用方办事，如果继续访问别的系统服务，默认会带着“远端调用方身份”。有些场景下系统服务需要临时清掉这个身份，改用自己进程的身份继续执行。用完必须 `restoreCallingIdentity()`，否则后续逻辑的身份会乱。

### 7.2 远端进程可能会死：`linkToDeath`

Binder 引用不只是“能调用”，还能监听远端死亡：

```java
IBinder.DeathRecipient recipient = new IBinder.DeathRecipient() {
    @Override
    public void binderDied() {
        // 远端进程死了：清理缓存、移除 callback、重新绑定服务等
    }
};

binder.linkToDeath(recipient, 0);
```

这在系统服务管理 callback、应用绑定远端服务、native 服务互相依赖时很常见。因为跨进程调用不是本地方法调用，远端进程随时可能被杀、崩溃或重启，客户端必须能处理 `DeadObjectException` 和死亡回调。

### 7.3 Binder 不适合传大数据

Binder transaction buffer 不是无限的。实际开发里常见的经验上限是 **1MB 级别**，而且这是一个进程内多笔事务共享的缓冲资源，不是“每个方法都稳定可用 1MB”。

所以不要通过 AIDL 直接传大 `Bitmap`、大数组、大 JSON、大文件内容。更合理的方式是：

- 大文件：传文件路径、`ParcelFileDescriptor` 或 fd。
- 大块内存：用共享内存、`ashmem` / `ASharedMemory`、硬件 buffer 等机制。
- Binder 本身：只传控制信息、小对象、句柄和 fd。

### 7.4 Binder 调用会阻塞，也会失败

AIDL 的语法让它看起来像普通接口，但它本质上是 IPC。一次调用可能遇到：

- 服务端线程池繁忙，客户端同步调用被阻塞。
- 服务端执行慢，调用方主线程卡顿甚至 ANR。
- 远端进程死亡，抛出 `DeadObjectException` 或 `RemoteException`。
- 参数过大，触发 `TransactionTooLargeException`。

因此应用侧不要在主线程做可能耗时的跨进程调用；服务端也不要在 Binder 线程里做长时间 I/O、等待锁或复杂计算。

---

## 8. 一图总结

```
应用进程                          system_server 进程
┌──────────────────┐              ┌──────────────────────────────┐
│ Activity.startActivity │        │ ActivityManagerService (服务端)│
│       │                  │        │   ▲                          │
│       ▼                  │        │   │ onTransact()             │
│ IActivityManager.Proxy   │        │   │   └─ startActivity()     │
│       │ transact()       │ Binder │   │                          │
│       ▼                  │ 驱动   │   │                          │
│ BinderProxy → BpBinder   │ ══════►│ BBinder ← IPCThreadState    │
└──────────────────┘              └──────────────────────────────┘
                    内核 binder 驱动：一次拷贝 + 记录调用方 uid/pid
                          ▲
                          │ 注册 / 查找
                     ServiceManager（句柄 0，电话簿）
```

---

## 9. 关键源码路径速查

| 内容 | 路径 |
|---|---|
| AMS 接口定义（AIDL） | `frameworks/base/core/java/android/app/IActivityManager.aidl` |
| AMS 实现 | `frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java` |
| ATMS 接口定义（新版本 Activity 启动主线） | `frameworks/base/core/java/android/app/IActivityTaskManager.aidl` |
| ATMS 实现 | `frameworks/base/services/core/java/com/android/server/wm/ActivityTaskManagerService.java` |
| SystemServer 启动服务 | `frameworks/base/services/java/com/android/server/SystemServer.java` |
| Java 层 Binder / BinderProxy / Parcel | `frameworks/base/core/java/android/os/Binder.java` 等 |
| Java → Native 的 JNI | `frameworks/base/core/jni/android_util_Binder.cpp` |
| Native 层 Binder 库 | `frameworks/native/libs/binder/`（`BpBinder.cpp`、`IPCThreadState.cpp`、`ProcessState.cpp`） |
| 内核 Binder 驱动 | `drivers/android/binder.c`（旧版在 `drivers/staging/android/`） |

---

## 小结

- **Binder = 进程间的函数调用路由器**，靠「一次拷贝」和「可鉴权」胜出。
- **AIDL 生成的 `Stub` 是服务端真身，`Proxy` 是客户端遥控器**，`onTransact` 是所有调用的汇聚点。
- **跨进程时客户端拿到的是 `BinderProxy`**，不是服务端对象；同进程会走 `queryLocalInterface` 的本地优化。
- **跨进程 = Java(Proxy) → native(BpBinder/IPCThreadState) → 内核驱动 → native(BBinder) → Java(Stub.onTransact)**。
- **ServiceManager 是电话簿**，AMS 注册其中，应用通过它拿到 AMS 的遥控器。
- **AIDL 实战要关注线程、身份、死亡、大小限制**：服务端方法跑在 Binder 线程池，权限校验依赖 uid/pid，远端会死亡，大数据不要直接塞进 transaction。

下一讲建议：顺着 `startActivity` 的这条 Binder 调用，追 AMS 内部到底做了哪些事（进程 fork、ActivityRecord 创建、生命周期回调），把「Activity 启动流程」完整走通。
