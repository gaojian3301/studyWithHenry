# 消息机制详解：从 Looper 到 Handler 和 Choreographer

> 这是 Android Framework 的"心跳"。App 主线程、ActivityThread.H、WMS/AMS 内部调度、输入事件分发、View 绘制、Choreographer 的 VSYNC 回调，全部跑在 Looper/Handler/MessageQueue 之上。
>
> 本文是 **Framework 机制视角**：只讲这套机制本身，以及它在系统里的位置。App 侧怎么用 Handler（协程切换、postDelayed 做动画、泄露修复套路）属于 `androidApp/` 系列，不在这里展开；AMS/WMS/ATMS 内部怎么用消息机制，各对应篇章已讲。Choreographer 与 VSYNC 的详细渲染链路见 `22_图形渲染机制详解-从HWUI到GPU上屏.md` 第 3 节，本文第 11 章只做"衔接"。

---

## 1. 开场：消息机制回答的三个问题

先把整件事框在三个问题上，后面所有章节都是这三个问题的展开：

| 问题 | 谁负责 | 一句话答案 |
|---|---|---|
| **谁在等消息？** | `Looper.loop()` | 一个线程进入 `loop()` 后就卡在循环里，不断取消息、分发、再取 |
| **消息怎么排队？** | `MessageQueue` | 一条按 `when`（执行时间）升序的单链表，新消息插到正确位置 |
| **队列空了去哪？** | `MessageQueue.next()` → native `epoll` | 不烧 CPU，线程阻塞在 `nativePollOnce`，等 `eventfd` 被 `wake()` 唤醒 |

> **「Android 视角」** 主线程（UI 线程）之所以"活着"，不是因为它跑了个死循环空转，而是因为 `Looper.loop()` 在消息队列空时会**挂起**自己（进入内核等待），有消息时被唤醒。这就是为什么主线程既"一直运行"又不"占满一个核"。

### 1.1 一次 `Handler.sendMessage` 的全景链路

```text
App 业务代码（任意线程）
  └─ Handler.sendMessage(msg)                  // 用户侧入口
       └─ Handler.sendMessageDelayed(msg, 0)
            └─ Handler.sendMessageAtTime(msg, uptimeMillis)
                 └─ Handler.enqueueMessage(queue, msg, uptimeMillis)   // 给 msg.target = this
                      └─ MessageQueue.enqueueMessage(msg, when)
                           ├─ 按 when 插入 mMessages 单链表
                           └─ nativeWake(mPtr)                          // 写 eventfd，唤醒 epoll
                      (此时另一个线程/主线程若阻塞在 nativePollOnce，被唤醒)
                 └─ MessageQueue.next() 返回这条 msg
            └─ Looper.loop() 取出 msg → msg.target.dispatchMessage(msg)
                 └─ Handler.dispatchMessage(msg)
                      ├─ msg.callback.run()         // 情况 A：post(Runnable)
                      ├─ mCallback.handleMessage()  // 情况 B：构造时传 Callback
                      └─ handleMessage(msg)         // 情况 C：最常见子类重写
```

注意链路里**有两个线程视角**：投递线程（enqueue + wake）和消化线程（next + dispatch）。这是理解"为什么 Handler 能跨线程切回主线程"的关键——`enqueue`/`wake` 在子线程跑，`next`/`dispatch` 在主线程跑，二者通过同一个 `MessageQueue` 对象（以及它底层的 native 队列）会合。

### 1.2 这篇怎么读

- 第 2 章先把四件套的对象关系和"线程绑定"讲透（含 `ThreadLocal` 的坑）。
- 第 3~6 章顺着一次消息的生命周期走：**创建 Looper → 消息入队 → 底层阻塞/唤醒 → 取消息分发**。
- 第 7~10 章是"用得好不好"：优先级、HandlerThread、idle、跨进程边界。
- 第 11~12 章是系统/车机视角：Choreographer 挂在主 Looper 上、ANR 与队列的关系、开机早期 Looper 就绪点。
- 第 13~16 章是工具与排障：调试手段、排查表、读源码路线、速查表。

---

## 2. 四件套关系图：Looper / MessageQueue / Message / Handler

### 2.1 对象关系

```text
        ┌─────────────────────────── Thread ───────────────────────────┐
        │                                                               │
        │   ThreadLocal<Looper>  ──get()──►  Looper  (每线程至多一个)    │
        │                                   │ mQueue                    │
        │                                   ▼                           │
        │                               MessageQueue  (单链表 mMessages) │
        │                                   ▲                           │
        │   Handler A ──mLooper,mQueue──┐    │    ┌── Handler B (同一线程│
        │   Handler C ──────────────────┘    │    └── 可有多个 Handler)  │
        │                                     │                          │
        │   Message ──next──► Message ──next─┘──► ... (按 when 排序)     │
        └───────────────────────────────────────────────────────────────┘
```

核心不变量：

1. **一个线程至多一个 `Looper`**；没有 `Looper` 的线程不能 new `Handler`（除非显式传 `Looper`）。
2. **一个 `Looper` 绑定一个 `MessageQueue`**（`Looper.mQueue` 在构造时创建）。
3. **一个 `MessageQueue` 可被多个 `Handler` 共享**；`Handler` 只是"投递 + 处理"的客户端，它通过 `mLooper`/`mQueue` 引用找到队列。
4. **`Message` 是数据载体**，不属于某个 `Handler`，靠 `target` 字段反指处理它的 `Handler`。

### 2.2 线程绑定语义：`ThreadLocal` 存 Looper

`Looper` 用 `ThreadLocal` 把自身"钉"在创建它的线程上：

```java
// frameworks/base/core/java/android/os/Looper.java（简化）
static final ThreadLocal<Looper> sThreadLocal = new ThreadLocal<Looper>();

private static void prepare(boolean quitAllowed) {
    if (sThreadLocal.get() != null) {
        throw new RuntimeException("Only one Looper may be created per thread");
    }
    sThreadLocal.set(new Looper(quitAllowed));   // 当前线程私有
}

public static Looper myLooper() {
    return sThreadLocal.get();                   // 只在"自己的"线程里能拿到
}
```

`ThreadLocal` 本质是**每个线程一张私有 map**：`get()` 返回"当前线程 set 过的那个值"。所以 `myLooper()` 在 A 线程拿到 A 的 Looper，在 B 线程拿到 B 的，互不串。

> **`ThreadLocal` 的坑（车机/平台开发高频）**
> - **在没 prepare 的线程 new Handler** → `Looper.myLooper()` 返回 `null`，`Handler` 构造里抛 `RuntimeException: Can't create handler inside thread that has not called Looper.prepare()`。这是全系列最常见的崩溃之一（见第 14 章排查表）。
> - **线程复用**：线程池（如 `Executors.newFixedThreadPool`）的线程默认没有 Looper。若你在一个池化线程里 `new Handler()`，大概率报上面那个错。正确做法是用 `HandlerThread`，或显式 `Looper.prepare()`。
> - **主线程 Looper 全局唯一**：通过 `Looper.getMainLooper()` 拿，它不依赖 `ThreadLocal`（见第 3 章 `sMainLooper`）。

### 2.3 版本差异：默认 `Handler()` 构造器在 R(30) 被废弃

```java
// API < 30：new Handler() 隐式取当前线程 Looper，没有就抛异常
// API >= 30：该构造函数被 @Deprecated，强制你显式传 Looper
Handler h = new Handler(Looper.getMainLooper());   // 推荐
Handler h2 = new Handler(Looper.myLooper());        // 子线程需先 prepare
```

原因：隐式取当前线程 Looper 容易在"当前线程其实没 prepare"的上下文中静默出错，且不利于静态分析线程归属。R 之后编译会有 lint 警告，运行时行为不变。

---

## 3. Looper.prepare 与主线程 Looper：从哪来、为什么不退出

### 3.1 主线程 Looper 的诞生链

App 进程真正入口是 `ActivityThread.main()`（不是你写的 `Application.onCreate`）：

```text
Zygote fork 出 App 进程
  └─ ActivityThread.main()                      // 进程第一个 Java 调用
       ├─ Looper.prepareMainLooper()            // ① 为主线程造 Looper
       ├─ ActivityThread.attach(false)          // ② 连 AMS、binder 握手
       └─ Looper.loop()                         // ③ 进入不死循环
```

`prepareMainLooper()` 内部做的是 `prepare(false)`（主线程**不允许退出**，`quitAllowed=false`）+ 把造好的 Looper 存进静态字段 `sMainLooper`：

```java
// frameworks/base/core/java/android/os/Looper.java（简化）
public static void prepareMainLooper() {
    prepare(false);                              // 主线程不可 quit
    synchronized (Looper.class) {
        if (sMainLooper != null)
            throw new IllegalStateException("The main Looper has already been prepared.");
        sMainLooper = myLooper();                // 全局保存主线程 Looper
    }
}

public static Looper getMainLooper() {
    synchronized (Looper.class) { return sMainLooper; }
}
```

> **`sMainLooper` 演进（版本差异）**：早期 `sMainLooper` 直接是 `ActivityThread` 的静态字段，后迁移到 `Looper` 类统一管理（约 Android 2.3 之后）。今天你应当用 `Looper.getMainLooper()`，不要去反射 `ActivityThread.sMainLooper`——那是已经 @hide 的内部字段。

### 3.2 为什么主线程"不会退出"

看 `loop()` 的骨架（去掉日志与慢消息统计）：

```java
// frameworks/base/core/java/android/os/Looper.java
public static void loop() {
    final Looper me = myLooper();
    if (me == null) throw new RuntimeException("No Looper; Looper.prepare() wasn't called");
    for (;;) {                                    // 注意：没有 break 条件
        Message msg = me.mQueue.next();          // 可能阻塞
        if (msg == null) {                        // 只有 queue 退出时返回 null
            return;                               // 普通线程 quit 才会走到这
        }
        msg.target.dispatchMessage(msg);          // 分发
        msg.recycleUnchecked();                   // 回收进对象池
    }
}
```

主线程的 Looper 是用 `prepare(false)` 创建的，**`next()` 永远不返回 `null`**（见第 4/6 章 `quitAllowed` 检查），于是 `for(;;)` 没有出口——除非进程被系统杀掉。这正是 App 主线程"永远活着"的真相：**不是它有什么特殊能力，而是它的消息队列永远不会主动 quit**。

### 3.3 `quit()` 与 `quitSafely()`

普通线程的 `HandlerThread` 可以退出，二者区别在"是否清空已到点的消息"：

| 方法 | 行为 | 适用 |
|---|---|---|
| `quit()` | 立即清空**所有**消息（含未到点的 `delayed`），`next()` 返回 `null` | 线程不再需要，要立刻停 |
| `quitSafely()` | 只清**未到点**的消息，已到点的 `when<=now` 消息继续处理完，再返回 `null` | 优雅退出，避免丢在途任务 |

```java
// MessageQueue.quit()
void quit(boolean safe) {
    if (!mQuitAllowed)                           // 主线程 mQuitAllowed=false
        throw new IllegalStateException("Main thread not allowed to quit.");
    synchronized (this) {
        if (mQuitting) return;
        mQuitting = true;
        if (safe) removeAllFutureMessagesLocked(); // 仅移除 when>now
        else      removeAllMessagesLocked();        // 全清
        nativeWake(mPtr);                          // 唤醒 next() 让它看到退出标志
    }
}
```

> **「Android 视角」** `quitSafely()` 是 `HandlerThread.quitSafely()` 在 API 18 引入的，之前只有 `quit()`。车机后台线程池优雅关停时务必用 `quitSafely()`，否则可能截断正在处理的 binder 回调。注意：**`mQuitAllowed` 为 false 的主线程调用 quit 会直接抛 `IllegalStateException`**——系统不允许你 kill 自己的主线程。

---

## 4. MessageQueue 的存储结构：链表、对象池与 `when` 时间源

### 4.1 `mMessages` 是按 `when` 排序的单链表

`MessageQueue` 没有用数组/队列，而是维护一条**按 `when`（应执行时间）升序**的单向链表，头指针是 `mMessages`：

```java
// frameworks/base/core/java/android/os/MessageQueue.java（字段，简化）
Message mMessages;     // 链表头，when 最小（最早该执行）的节点
```

入队时从头部扫描，找到第一个 `when` 比新消息大的节点，插到它前面；若没有则插到尾部。所以 `next()` 永远先取头节点——**最早该执行的先走**。

```java
// MessageQueue.enqueueMessage（核心逻辑，简化）
boolean enqueueMessage(Message msg, long when) {
    if (msg.target == null) throw new IllegalArgumentException("Message must have a target");
    synchronized (this) {
        msg.when = when;
        Message p = mMessages;
        if (p == null || when == 0 || when < p.when) {
            msg.next = p; mMessages = msg;       // 插到表头（立即执行/最早）
            needWake = mBlocked;                 // 若 next() 正阻塞，需要唤醒
        } else {
            Message prev;
            for (;;) {                            // 沿链表找插入点
                prev = p; p = p.next;
                if (p == null || when < p.when) break;
            }
            msg.next = p; prev.next = msg;        // 中间插入
            needWake = mBlocked && p.target == null
                        && msg.isAsynchronous();  // 屏障相关，见第 6 章
        }
        if (needWake) nativeWake(mPtr);
    }
    return true;
}
```

### 4.2 `Message` 对象池：`obtain()` / `recycle()`

消息对象大量创建/销毁会压力 GC。Android 用**全局 sPool 单链表**复用 `Message`：

```java
// Message.obtain() 优先从池里拿，避免 new
public static Message obtain() {
    synchronized (sPoolSync) {
        if (sPool != null) {
            Message m = sPool; sPool = m.next;    // 从池头摘一个
            m.next = null; m.flags = 0; sPoolSize--;
            return m;
        }
    }
    return new Message();                         // 池空才 new
}
```

`loop()` 每处理完一条消息会调用 `msg.recycleUnchecked()` 把它还回池（上限 `MAX_POOL_SIZE = 50`）。这解释了为什么你**不应该** `new Message()` 后长期持有——它本该被回收复用。

> **长消息对队列的影响（第 7 章展开）**：`mMessages` 是单链表，`enqueueMessage` 最坏要 O(n) 扫描插入点；**某条消息处理太久**还会阻塞 `next()` 之后的所有消息，这是 ANR 的直接来源。

### 4.3 `when` 的时间源：不是 `System.currentTimeMillis()`

`when` 来自 `SystemClock.uptimeMillis()`（自开机、不含深度睡眠的单调时间），**不是**墙钟 `currentTimeMillis()`：

```java
// Handler.sendMessageDelayed
public final boolean sendMessageDelayed(Message msg, long delayMillis) {
    if (delayMillis < 0) delayMillis = 0;
    return sendMessageAtTime(msg, SystemClock.uptimeMillis() + delayMillis);
}
```

| 时间源 | 含义 | 用错后果 |
|---|---|---|
| `SystemClock.uptimeMillis()` | 单调、不含休眠 | 消息调度基准，**delay 准确** |
| `SystemClock.elapsedRealtime()` | 单调、含休眠 | AlarmManager 用 |
| `System.currentTimeMillis()` | 墙钟、可被用户/网络修改 | 若用做 `when`，用户改时间会让消息"瞬间到点"或"永远不到" |

> **版本差异**：`MessageQueue` 内部对"时间源"的选择长期是 `uptimeMillis`；Android 在部分路径（如 `postDelayed` 对齐到 vsync）引入了 `SystemClock.uptimeMillis()` 的变体。车机定制若改了时间同步逻辑，要确认不影响 `when` 基准。

---

## 5. native 层：epoll + eventfd，空闲时不烧 CPU

Java 的 `MessageQueue` 只是壳，真正的"阻塞/唤醒"在 native (`android::MessageQueue`，实现在 `system/core/libutils/Looper.cpp` 与 `frameworks/base/core/jni/android_os_MessageQueue.cpp`)。

### 5.1 为什么不能 `while(true){}` 空转

如果队列空就 `while` 循环空转检查，主线程会 100% 占满一个核，整机发热、掉电。Android 用 **Linux `epoll` + `eventfd`** 让线程在"没消息"时**陷入内核睡眠**，被写 `eventfd` 唤醒。

```text
Java:  MessageQueue.next() 空闲
         └─ nativePollOnce(mPtr, timeoutMillis)   // JNI 下沉
              └─ native: Looper::pollOnce(timeout)
                   └─ epoll_wait(mEpollFd, ..., timeoutMillis)   // 内核睡眠
                        ▲
                        │  eventfd 被写入（nativeWake）
                        └─ epoll_wait 返回 → next() 继续取消息
```

### 5.2 关键 fd：`mWakeEventFd` 与 `mEpollFd`

```cpp
// system/core/libutils/Looper.cpp（native Looper，简化字段）
int mWakeEventFd;     // eventfd，用于"写 1 个字节唤醒"
int mEpollFd;         // epoll 实例，监听 mWakeEventFd（+ 其他 fd 如 input）
```

- `wake()`（对应 Java `nativeWake`）：向 `mWakeEventFd` 写入一个 `uint64_t=1`，`epoll_wait` 立刻返回。
- `nativePollOnce(timeout)`：调 `epoll_wait`，`timeout` 可为：
  - `0`：立即返回（不阻塞，用于"只是看看有没有消息"）；
  - `-1`：无限阻塞，**直到 eventfd 被写**（队列真正空、且无可执行 delayed 消息时）；
  - `>0`：阻塞到下一个 `delayed` 消息的 `when`（到时自动唤醒）。

```cpp
// 唤醒（写 eventfd）
void Looper::wake() {
    uint64_t inc = 1;
    ssize_t nWrite = write(mWakeEventFd, &inc, sizeof(uint64_t));
    // 写 1 字节即触发 epoll；幂等，重复 wake 不会累积
}
```

### 5.3 Java / native 消息的混合队列（mPtr）

`MessageQueue` 在 native 侧还维护自己的"native 消息"（C++ 侧通过 `addFd`/`Looper::addFd` 注册的 fd 事件，例如 Input 的 fd、surfaceflinger 的 vsync fd）。这两类消息在 `pollOnce` 里被统一处理：

- `mPtr`（Java 侧 `long mPtr`）保存 native `NativeMessageQueue*` 指针；
- `next()` 调 `nativePollOnce` 时，native 侧会先处理 native 消息，再回 Java 侧处理 `mMessages` 链表；
- **native 消息的处理位置**：在 Java 消息 `next()` 的"间隙"（每次循环前先 poll 一次 native），不是插在 Java 链表里。

> **「Android 视角」** 第 13 章 Input 系统的事件本质也是通过 native `addFd` 注册到主线程 Looper 上的 fd 来投递的——所以"输入事件"和"Handler 消息"最终共用同一条 `epoll` 唤醒链。这也是第 16 章关联阅读里"13 Input 的事件也是消息"的由来。

### 5.4 空闲时不烧 CPU 的闭环

```text
next() 发现：mMessages 为空 且 无 delayed 到点 且 无同步屏障
  → nativePollOnce(ptr, -1)        // 无限阻塞，CPU 让出
      ... 线程睡在内核 ...
  子线程 sendMessage → enqueueMessage → nativeWake → 写 eventfd
      ... epoll_wait 返回 ...
  next() 取出 msg → loop 分发
```

这个闭环是"主线程永远活着却不占满 CPU"的工程答案，也是第 13 章看 ANR trace 时 `nativePollOnce` 出现在栈顶**不等于卡死**的原因（见第 13 章）。

---

## 6. loop() 取消息逐行解读：next() 的返回语义、同步屏障、target==null

### 6.1 `next()` 的返回语义：可能为 `null`

`next()` 是 `loop()` 的心脏。它的返回值有两种含义：

| 返回值 | 含义 | loop() 的处理 |
|---|---|---|
| 非 `null` Message | 取到一条该执行的消息 | `dispatchMessage` 分发 |
| `null` | 队列已 `quit` | `loop()` `return`，线程退出 |

```java
// MessageQueue.next()（骨架，简化，含关键分支）
Message next() {
    final long ptr = mPtr;
    if (ptr == 0) return null;                    // native 已销毁

    int pendingIdleHandlerCount = -1;
    int nextPollTimeoutMillis = 0;
    for (;;) {
        if (nextPollTimeoutMillis != 0) Binder.flushPendingCommands();
        nativePollOnce(ptr, nextPollTimeoutMillis);   // 阻塞点

        synchronized (this) {
            final long now = SystemClock.uptimeMillis();
            Message prevMsg = null;
            Message msg = mMessages;
            if (msg != null && msg.target == null) {   // ★ 同步屏障哨兵
                do { prevMsg = msg; msg = msg.next; }
                while (msg != null && !msg.isAsynchronous());  // 跳过同步，找异步
            }
            if (msg != null) {
                if (now < msg.when) {                  // 还没到点，算剩余超时
                    nextPollTimeoutMillis = (int) Math.min(msg.when - now, Integer.MAX_VALUE);
                } else {                               // 到点，出队
                    mBlocked = false;
                    if (prevMsg != null) prevMsg.next = msg.next;
                    else mMessages = msg.next;
                    msg.next = null; msg.markInUse();
                    return msg;                        // ★ 返回这条
                }
            } else {
                nextPollTimeoutMillis = -1;            // 队列空，无限阻塞
            }

            if (mQuitting) {                           // ★ quit 标志
                dispose(); return null;                // ★ 返回 null → loop 退出
            }
            // ... idle handler 处理（第 9 章）...
        }
    }
}
```

### 6.2 同步屏障（Sync Barrier）：`target == null` 是什么

注意上面那个 `msg.target == null` 的分支。在 `MessageQueue` 里，**`target == null` 的 Message 是"同步屏障"哨兵**，它不指代任何 Handler，作用是：**挡住普通（同步）消息，只放行异步（isAsynchronous）消息**。

```java
// 插入屏障（Android 早期公开，后 @hide；应用不应直接调）
int enqueueSyncBarrier(long when) {
    synchronized (this) {
        int token = mNextBarrierToken++;
        Message msg = Message.obtain();
        msg.when = when;
        msg.arg1 = token;
        // 注意：没有设置 msg.target  → 这就是屏障
        Message p = mMessages;
        if (when != 0) { /* 找到插入点 */ }
        // 插入链表
        if (needWake) nativeWake(mPtr);
        return token;
    }
}
// 解除屏障
void removeSyncBarrier(int token) { /* 找到 arg1==token 的屏障节点删除并 wake */ }
```

`next()` 遇到 `target==null` 的屏障后，会跳过所有**同步**消息，一直往后找第一个 `isAsynchronous()` 的消息优先返回。这样"绘制/输入这类高优异步消息"就能插队到普通 UI 消息前面。

> **为什么应用不建议用屏障（版本差异 + 风险）**
> - `postSyncBarrier()` / `removeSyncBarrier()` 在 API 23(M) 之后逐步变为 `@hide`，**应用层 SDK 已不可见**，反射调用在后续版本可能被限制/移除。
> - 它是"全局队列级"的开关，**忘了解除屏障会导致整个队列的普通消息饥饿**（界面卡住但不崩）。系统内部（如 `ViewRootImpl` 在 `scheduleTraversals` 时插屏障、绘制完移除）用它是因为生命周期受控。
> - 应用若想"高优消息"，应用侧正确做法是 `Message.setAsynchronous(true)` 配合已有屏障，或干脆用 `Choreographer`/独立 `HandlerThread`，不要自己造屏障。

### 6.3 同步屏障在系统里的真实使用场景

屏障是 **Framework 内部机制**，不是给 App 用的 API。典型：

- `ViewRootImpl.scheduleTraversals()`：插屏障 → 等 VSYNC → `doTraversal()` 跑完绘制 → 移除屏障。保证"绘制"相关的异步消息优先于普通 `Handler` 消息，避免掉帧。
- `Choreographer` 派发的 `FrameDisplayEventReceiver` 消息标记为异步，配合屏障被优先执行（衔接见第 11 章）。

> **「Android 视角」** 你想"让某条消息优先"时，别碰屏障。Framework 层的优先靠屏障+异步消息；**应用层**的优先靠：① 放到 `Choreographer`/`View.postOnAnimation`；② 放独立高优 `HandlerThread`；③ 极端情况 `Message.setAsynchronous(true)`（但需已有屏障环境，否则无效）。记住：**异步消息只有在存在屏障时才"优先"，没有屏障时它就只是条普通消息。**

---

## 7. 消息分发与回调优先级：三级、复用链、长消息影响

### 7.1 `dispatchMessage` 的三级优先级

`loop()` 取到消息后调用 `msg.target.dispatchMessage(msg)`。分发顺序**固定三级**，从高到低：

```java
// Handler.dispatchMessage
public void dispatchMessage(Message msg) {
    if (msg.callback != null) {            // ① 最高：msg 自带的 Runnable
        handleCallback(msg);               //    → msg.callback.run()
    } else {
        if (mCallback != null) {           // ② 次高：Handler 构造时传的 Callback
            if (mCallback.handleMessage(msg)) return;
        }
        handleMessage(msg);                // ③ 最低：子类重写的 handleMessage
    }
}
```

| 级别 | 触发条件 | 典型来源 | 说明 |
|---|---|---|---|
| ① `msg.callback` | `Handler.post(Runnable)` 时把 Runnable 塞进 `msg.callback` | `view.post()`、`handler.postDelayed(r, 100)` | 优先级最高，绕过了 `handleMessage` |
| ② `mCallback` | `new Handler(callback)` 传入 `Callback` 接口 | 想在不子类化 Handler 时拦截消息 | 返回 `true` 表示"我吃掉了"，不再走 ③ |
| ③ `handleMessage` | 子类重写 | 最常见 `extends Handler` 用法 | 默认落点 |

> **容易踩的混淆**：`Handler.post(Runnable)` **不是**新开线程执行 Runnable，它只是把 Runnable 包成 `Message.callback`，照样在 Handler 所在线程（通常是主线程）执行。车机里有人误以为 `post` 能"异步执行耗时任务"，结果直接在主线程跑重活 → ANR。

### 7.2 `Message.obtain` 与复用链（再强调）

第 4.2 章讲过对象池，这里补"为什么你最好用 `obtain` 而不是 `new`"：

```java
// 推荐：从池取，自动复用
Message msg = Message.obtain(handler, what, arg1, arg2);
msg.obj = payload;
handler.sendMessage(msg);

// 不推荐：绕过池，增加 GC 压力（高频消息尤其明显）
Message msg = new Message();   // 仅在池满/特殊场景才必要
```

池上限 50 条（`MAX_POOL_SIZE`）。高频消息（如每帧、每 touch）若都用 `new`，会持续触发young GC，在车机低配平台上表现为周期性卡顿。**高频消息务必 `obtain` + 自动回收**。

### 7.3 一条长消息对整条队列的"堵车"

`MessageQueue` 是**串行**队列：哪怕有 100 条消息排队，当前这条 `handleMessage` 没 return，后面的全卡着。`next()` 在 `dispatchMessage` 返回前根本不会被调用。

```text
mMessages: [A(when=10)][B(20)][C(30)][D(40)]...
            │
            ▼ loop 取 A → dispatchMessage(A)
              A.handleMessage 跑了 2 秒（主线程卡死）
              → B/C/D 全被推迟 ≥2 秒 → 掉帧/ANR
```

推论（与 ANR 的关系，第 12 章展开）：**主线程卡顿永远不是"队列坏了"，而是某条具体消息处理太久**。定位 ANR 就是去 trace 里看"主线程此刻卡在 `handleMessage` 的哪一行"。

---

## 8. HandlerThread、IntentService 淘汰、子线程 Handler 模板与内存泄漏

### 8.1 `HandlerThread`：带 Looper 的工作线程

`HandlerThread` = `Thread` + 在 `run()` 里 `Looper.prepare()` + `Looper.loop()`，并提供 `getLooper()`。它让"在子线程串行处理消息"变成一行代码：

```java
// 标准模板
HandlerThread worker = new HandlerThread("bg-serial");
worker.start();                                   // 触发 run()：prepare + loop
Handler workerHandler = new Handler(worker.getLooper());  // 绑到该线程队列
workerHandler.post(() -> { /* 在子线程串行跑 */ });

// 不再需要：优雅退出（API 18+）
worker.quitSafely();
```

适合：串行 I/O、状态机、系统服务内部调度。**不适合** CPU 密集型并行计算（一个线程一个队列，并行度=1）。

> **`getLooper()` 的阻塞语义**：`HandlerThread.getLooper()` 内部会 `wait()` 直到 `run()` 里 `Looper` 建好才返回。所以"先 `start()` 再 `getLooper()`"是安全的——不会拿到 `null`。

### 8.2 `IntentService` 已废弃（版本差异）

`IntentService` 本质是"一个 `HandlerThread` + `onHandleIntent` 在子线程串行执行 + 全部处理完自动 `stopSelf`"。它在 **Android O(26) 被废弃**，原因：

- 后台 Service 在 O 之后受到严格限制（后台执行限制 / 后台位置限制）；
- 它隐藏了线程模型，难以与 `JobScheduler`/WorkManager 配合。

替代方案：

| 旧 | 新 | 说明 |
|---|---|---|
| `IntentService` | `JobIntentService`（support） / `WorkManager` | 保证执行且遵守后台限制 |
| 单纯串行后台任务 | `HandlerThread` + `Executor` | 仍需自己管理生命周期 |

车机系统服务里仍大量用 `HandlerThread`（不受 App 后台限制约束），但 App 侧不要再新建 `IntentService`。

### 8.3 子线程 Handler 的标准模板代码

```java
class Worker {
    private final HandlerThread thread = new HandlerThread("my-worker");
    private final Handler handler;

    Worker() {
        thread.start();
        handler = new Handler(thread.getLooper());   // 显式传 Looper
    }
    void doAsync(Runnable r) { handler.post(r); }
    void shutdown() { thread.quitSafely(); }
}
```

要点：**永远显式传 `Looper`**（API 30+ 默认构造器已废弃，见 2.3）；退出时 `quitSafely()` 而非 `quit()`。

### 8.4 内存泄漏：非静态内部 Handler 持有外部

`Handler` 若是 `Activity` 的**非静态内部类**，会隐式持有 `Activity` 引用；若它 `postDelayed` 了一条长延时消息，`Activity` 本该销毁时因消息还挂在队列里、且 `msg.target=handler→activity`，导致 `Activity` 无法被 GC：

```java
// 危险：泄漏 Activity
private Handler mHandler = new Handler(Looper.getMainLooper()) {
    @Override public void handleMessage(Message msg) { /* 用 this 访问 Activity */ }
};

// 正确做法 1：静态内部类 + 弱引用
private static class SafeHandler extends Handler {
    private final WeakReference<MyActivity> ref;
    SafeHandler(MyActivity a) { super(Looper.getMainLooper()); ref = new WeakReference<>(a); }
    @Override public void handleMessage(Message msg) {
        MyActivity a = ref.get(); if (a == null) return; /* 用 a */
    }
}

// 正确做法 2：onDestroy 时清空
@Override protected void onDestroy() {
    mHandler.removeCallbacksAndMessages(null);   // 传入 null = 移除该 handler 的全部消息
    super.onDestroy();
}
```

> `removeCallbacksAndMessages(null)` 的 `null` 是特殊值：**移除该 Handler 投递的所有回调和消息**（不看 what/obj）。车机长驻页面尤其要在 `onDestroy`/`release` 里调它，否则消息队列里残留的 `msg.target` 会长期持有页面。

---

## 9. idle handler：addIdleHandler 的语义、场景与风险

当 `next()` 发现**当前没有可立即执行的消息**（队列空或头部未到点）且**非 quitting** 时，会触发 `IdleHandler`——"队列闲了，来干点不急的活"。

```java
// MessageQueue.IdleHandler
public static interface IdleHandler {
    boolean queueIdle();   // 返回 true=保留（下次空闲再调）；false=一次后移除
}

// 注册 / 反注册
Looper.myQueue().addIdleHandler(idleHandler);
Looper.myQueue().removeIdleHandler(idleHandler);
```

`next()` 在空闲分支收集所有 `IdleHandler`，逐个调 `queueIdle()`：

```java
// next() 空闲分支（简化）
if (pendingIdleHandlerCount < 0 && (mMessages == null || now < mMessages.when)) {
    pendingIdleHandlerCount = mIdleHandlers.size();
}
if (pendingIdleHandlerCount > 0) {
    for (IdleHandler h : mIdleHandlers) {
        boolean keep = h.queueIdle();              // 调一次
        if (!keep) mIdleHandlers.remove(h);        // false 则移除
    }
}
```

### 9.1 使用场景

- **GC 前的资源清理**、预加载（闲时预取下一屏数据）；
- `ActivityThread` 用 idle handler 在人脑"空闲"时做 `GcIdler`、慢速 binder 清理；
- 车机 Launcher 闲时预热下一个常驻卡片。

### 9.2 风险：空闲 handler 死循环 / 饿死主线程

`queueIdle()` 返回的布尔值是陷阱：

- 返回 `true` 表示"我想下次空闲还被调"。若你在 `queueIdle()` 里**又 post 了可立即执行的消息**，队列永不空闲 → 你再被调 → 再 post → **主线程被 idle 回调占满**，正常 UI 消息被饿死。
- `queueIdle()` 本身跑在**主线程**，耗时操作会直接卡 UI。

```java
// 反例：会"饿死"主线程的 idle
Looper.myQueue().addIdleHandler(() -> {
    doSomeWork();             // 若耗时 → 卡主线程
    handler.post(this::again);// 又塞可立即执行的消息 → 队列不再空闲 → 反复回调
    return true;              // 且保留自己 → 死循环
});
```

> 规则：**idle handler 只做轻量、一次性、不 post 可立即执行消息**的活；要循环就返回 `false` 自己调度，且确保每轮之间有真实空闲。

---

## 10. 跨进程消息的边界：Handler 不是 IPC、Messenger 原理、与 AIDL 取舍

### 10.1 纠偏：Handler 是线程间，不是进程间

`Handler` 投递消息的前提是**收发双方共享同一个 `MessageQueue` 对象**（同进程同线程或同进程跨线程）。它**不能**跨进程——两个 App 进程各有各的 `Looper`，`Message` 也无法跨进程序列化（含 `Runnable`/`target` 引用）。

> **常见误解**："我用 Handler 给另一个 App 发消息"——不行。`Message` 里的 `callback`/`target` 是 Java 对象引用，Binder 传不了。跨进程必须走 **Binder**（AIDL / Messenger）。

### 10.2 `Messenger`：基于 Binder 的"串行消息"

`Messenger` 是 Android 给"不想写 AIDL"的人提供的**消息式 IPC 封装**。它的本质：

```text
Client 进程                         Server 进程
  Messenger(clientSide)  ──send──►  IMessenger (Binder)  ──►  Handler (server 线程)
       │                                   │
       │  跨进程 Binder 调用                 │  server 侧把 Message 重新 enqueue
       │                                   │  到自己的 Handler 队列
       ▼                                   ▼
  (client 的 replyTo 也是个 Messenger)   server 用 msg.replyTo.send() 回包
```

- `Messenger` 内部持有一个 `IMessenger` Binder（服务端 `Handler` 的包装）；
- `Messenger.send(msg)` 走 Binder 把 `Message` **序列化**传到对端，对端在**自己的 Handler 队列**里 `dispatchMessage`；
- 因此跨进程后，消息一样是**串行、单线程**处理的（服务端一个 Handler 顺序消费）。

```java
// 服务端
private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper()) {
    @Override public void handleMessage(Message msg) {
        // 跨进程过来的消息，在这里串行处理
        Message reply = Message.obtain(null, MSG_REPLY, doWork());
        reply.replyTo = msg.replyTo;
        try { msg.replyTo.send(reply); } catch (RemoteException e) { }
    }
});
public IBinder onBind(Intent i) { return messenger.getBinder(); }
```

### 10.3 Messenger vs AIDL 取舍

| 维度 | Messenger | AIDL |
|---|---|---|
| 抽象 | 消息式（一个 `handleMessage` 分发所有 what） | 接口式（多个具名方法） |
| 并发 | 串行（单 Handler 顺序） | 可并发（默认 oneway/多线程 binder 线程池） |
| 双向 | 用 `replyTo` 回包，略绕 | 可定义 `in/out` 双向接口，直接 |
| 复杂度 | 低，不用写 `.aidl` | 高，需定义接口 + 处理多线程 |
| 适用 | 简单请求/响应、低频控制指令 | 高频、多方法、高并发、需细粒度接口 |

> **车机视角**：车控类简单指令（如"调音量""切源"）用 Messenger 足够且不易并发踩坑；多媒体/大量数据传输、需要回调接口的，用 AIDL（oneway 注意线程安全）。二者底层都是 Binder，没有性能量级差异，**差异在编程模型与并发语义**。

---

## 11. Choreographer 衔接小节：它为什么挂在主 Looper 上

> **本节只做"衔接"**。`Choreographer` 与 VSYNC 的完整机制（FrameDisplayEventReceiver、VSYNC  Offset、doFrame 五阶段、掉帧归因）在 `22_图形渲染机制详解-从HWUI到GPU上屏.md` **第 3 节**，请移步那里读细节。这里只讲它和本文消息机制的接合点。

核心事实：**`Choreographer` 是运行在主线程 `Looper` 上的**——它不是一个独立线程，它的"收到 VSYNC"是通过主线程消息队列里的一条**异步消息**触发的。

```text
SurfaceFlinger 发出 VSYNC（物理/合成节拍）
  └─ 通过 BitTube/EventThread 写到 App 主线程注册的 fd
       └─ 主线程 Looper 的 epoll 收到（见第 5.3 章 native fd 注册）
            └─ FrameDisplayEventReceiver.onVsync()  →  post 一条异步消息到主队列
                 └─ Choreographer.doFrame()         // 在 loop/dispatch 里执行
                      ├─ input callbacks
                      ├─ animation callbacks
                      ├─ traversal (measure/layout/draw)
                      └─ commit
```

接合点解释了前面几章的内容如何串起来：

- **第 5.3 节**：`FrameDisplayEventReceiver` 是一个"接收器（Receiver）类"，通过 native `addFd` 把 VSYNC 的 fd 挂到主线程 `Looper` 的 `epoll` 上；
- **第 6.2 节**：doFrame 相关消息标记为**异步**，配合 `ViewRootImpl` 插入的**同步屏障**被优先执行，避免被普通 UI 消息插队掉帧；
- **第 1、3 章**：这一切最终都走 `Looper.loop() → dispatchMessage`，和你 `handler.post` 的 Runnable 是**同一个主线程队列里的邻居**。

> 一句话记忆：**Choreographer 不是另一套机制，它是"主线程消息队列里的一条高优异步消息"**。看懂本文，再看 22 篇的渲染管线就不会觉得它是黑盒。

---

## 12. 车机 / 平台视角：队列与 ANR、长消息定位、开机早期 Looper 就绪

### 12.1 主线程消息队列与 ANR 的关系

ANR（Application Not Responding）的判定本质是：**系统期待主线程在某个时间窗内消费掉某条特定消息（输入、生命周期、广播等），但主线程被另一条"长消息"挡着没空取它**。典型时间窗：输入事件 5s、广播 `onReceive` 10s(BG)/60s(FG)、Service 生命周期 20s。

```text
系统：期待主线程在 5s 内处理 input 消息 X
主线程：此刻卡在 handleMessage(Y)，Y 跑了 6s 还没 return
  → next() 没机会取 X → X 超时被判定 ANR
```

> **分工边界**：ANR 的**判定机制**（Watchdog、AMS 超时发信号、trace 采集）在 `05_AMS机制详解` / `06_WMS机制详解` 讲；本文只讲"为什么消息机制层面会卡"——答案是**串行队列 + 单条消息过长**。

### 12.2 系统进程里长消息的定位方法

车机系统进程（system_server、car_service、surfaceflinger 的 Java 侧）同样跑 Looper。定位"哪条消息慢"：

- **ANR trace**：`/data/anr/traces.txt`（老版本）或 `tombstoned` 的 ` /data/anr/` 新格式，看主线程栈是否卡在 `dispatchMessage`/`handleMessage` 的具体行（不是 `nativePollOnce`，后者是空闲）。
- **`Looper` 慢消息日志**：`Looper` 自带 `Printer` 能在"消息分发前/后"打印，配合阈值可抓出 >Xms 的消息（详见第 13 章 `setMessageLogging`）。
- **`dumpsys` 类**：部分系统服务在 `dump()` 里打印"最后 N 条消息耗时"（车机定制常加这个），可直接 `adb shell dumpsys <service>` 看。
- **Perfetto / atrace**：在 `Looper.dispatch` / `MessageQueue` 的 slice 上读每条消息的耗时（第 13 章）。

### 12.3 开机早期 Looper 就绪时间点

车机常问"系统服务 X 的回调为什么在开机很早期收不到 Handler 消息"——答案是 **Looper 就绪有先后**：

```text
init → zygote → system_server 进程启动
  ├─ 各 Service 构造时可能用 Handler（此时主 Looper 已 prepare）
  ├─ ActivityManagerService 等通过 Looper.getMainLooper() 拿主线程队列
  └─ "开机早期" 指 looper 未 loop() 之前，消息会堆积在 mMessages，loop() 一开始才处理
```

> **分工边界**：开机流程各阶段、谁先 prepare 主 Looper、Zygote→system_server 的时序在 `01_系统启动详解`（Android Framework 启动篇）讲。本文只强调：**在 `Looper.loop()` 被调用之前，所有 `sendMessage` 只是入队不执行**——这是很多"早早期消息丢失/延迟"的 root cause。车机定制若在 `ActivityThread.main` 之前（如 `attach` 之前）就指望消息被处理，会发现它们要等到 `loop()` 才开始跑。

---

## 13. 调试工具箱：从 ANR trace 到 Perfetto 与 MAT

> `dumpsys` 系列**没有**直接 dump 消息队列的命令（`dumpsys activity` 能看到 ANR 但不展开队列）。所以定位消息问题要靠下面几把刀。

### 13.1 ANR trace 里看主线程栈：`nativePollOnce` ≠ 卡死

抓 ANR trace（车机常用 `adb shell kill -3 <pid>` 或 `adb shell am hang`）后，主线程栈顶常出现：

```text
"main" prio=5 tid=1 Native
  | group="main" sCount=1 dsCount=0 flags=1 obj=...
  | sysTid=1234
  #00 pc 00000000  libutils.so android::Looper::pollOnce(...)
  #01 pc 00000000  libandroid_runtime.so android::android_os_MessageQueue_nativePollOnce(...)
  #02 pc 00000000  android.os.MessageQueue.next(...)
  #03 pc 00000000  android.os.Looper.loop(...)
```

**关键判读**：
- 栈顶是 `nativePollOnce` / `MessageQueue.next` → 主线程**空闲等待**（队列空、epoll 睡着）。这不是卡死，是"没消息可处理"。此时 ANR 的原因是**别的线程/别的进程**没及时给主线程发它期待的消息，或主线程在**更早**某刻卡过。
- 若栈顶是 `dispatchMessage` / `handleMessage(XXX.java:NNN)` → 主线程**正卡在某条具体消息的第 NNN 行**——这才是真正的"长消息"。直接看 `XXX.java:NNN` 这行在干嘛。

> 经验：看到 `nativePollOnce` 在栈顶就别在主线程上找问题了，去查"谁该发却没发消息"（如 binder 对端阻塞、AMS 没回调）。这是车机 ANR 归因最常见的误判点。

### 13.2 `Looper.setMessageLogging` + `Printer`：抓慢消息

`Looper` 留了一个钩子：分发每条消息**前/后**各调一次 `Printer.println`。自己实现 `Printer`，在两次打印之间记时，就能抓出 >阈值 的慢消息：

```java
// 在主线程早期（如 Application.onCreate / attachBaseContext）安装
Looper.getMainLooper().setMessageLogging(new Printer() {
    private static final long SLOW = 16; // ms，超过一帧视作慢消息
    private long t = 0;
    @Override public void println(String x) {
        if (x.startsWith(">")) {            // 消息即将分发
            t = SystemClock.uptimeMillis();
        } else {                            // 消息分发结束 "<"
            long cost = SystemClock.uptimeMillis() - t;
            if (cost > SLOW)
                Log.w("SlowMsg", x + " cost=" + cost + "ms");
        }
    }
});
```

> **版本差异**：`setMessageLogging` 自很早版本就存在（API 1），但 `println` 的 `">"`/ `"<"` 前缀字符串是 `Looper` 内部约定（`">>>>> Dispatching to "` 与 `"<<<<< Finished to "`），**请匹配 `startsWith` 而非相等**，避免版本间前缀微调导致漏抓。Choreographer 的 doFrame 也会过这条日志，所以你看到的慢消息里会夹杂渲染帧——这恰好帮你区分"是 UI 卡还是渲染卡"。
>
> **优雅退出条件**：`Printer` 只用于调试/灰度，**正式发布应移除或置空**（设 `setMessageLogging(null)`），否则每条消息两次字符串拼接有固定开销。车机可做成"开关 + 阈值下发"，默认关。

### 13.3 Perfetto / atrace：在轨上读消息

`perfetto` 的 `android.game` / `android.sysui` / 自定义 `atrace` 类别能抓到 `Looper` 的 slice：

```bash
# 抓主线程消息分发耗时
adb shell atrace --async_start -a com.your.pkg looper sched
# ... 复现卡顿 ...
adb shell atrace --async_stop -o /data/local/tmp/trace.raw
adb pull /data/local/tmp/trace.raw
```

或在 Perfetto UI 里打开 `systrace`，搜 `Looper.dispatchMessage`、`MessageQueue`、`Choreographer#doFrame` 的 slice，直接看到每条消息的起止与耗时，对比是否超过 16.6ms/帧。

### 13.4 Handler 泄漏的 MAT 视角

用 Android Studio Memory Profiler 抓 `hprof` → 导 MAT（Memory Analyzer）：

```text
排查路径：
  Dominator Tree → 过滤 Activity →
    发现 Activity 实例 GC Root 路径里有 "this$0" / "<handler>" →
      上一级是 android.os.Handler → MessageQueue → Message
结论：有未 remove 的 msg.target=Handler→Activity，Activity 无法回收
```

对照 8.4 章：`onDestroy` 里 `removeCallbacksAndMessages(null)` 能切断这条引用链。MAT 里看到 `MessageQueue` 持有你的 `Handler`、Handler 又持有 `Activity`，基本就是这条泄漏。

---

## 14. 常见问题排查表

| 现象 | 原因 | 章节 | 第一命令 / 操作 |
|---|---|---|---|
| `CalledFromWrongThreadException` | 在非 Looper 线程更新 UI（UI 操作最终走主队列） | 2.2 / 12.1 | 把操作包进 `runOnUiThread`/`handler.post` |
| `Can't create handler inside thread that has not called Looper.prepare()` | 线程无 Looper 就 `new Handler()` | 2.2 / 3.3 | 改用 `HandlerThread` 或 `new Handler(Looper.getMainLooper())` |
| 消息延迟不准（有时提前/滞后） | 误用 `currentTimeMillis` 当 `when` 基准；或消息被屏障/长消息插队 | 4.3 / 6.2 | 用 `postDelayed`（基于 `uptimeMillis`）；查是否有同步屏障 |
| 子线程 Looper 未退出 → 线程泄漏 | `HandlerThread` 忘了 `quit`/`quitSafely` | 3.3 / 8.1 | `thread.quitSafely()`；监控线程数 `ps -T` |
| 主线程被长消息卡死 → ANR | 某 `handleMessage` 跑太久，挡住后续消息 | 7.3 / 12.1 | `adb shell am hang` 抓 trace，看 `handleMessage(XXX:N)` |
| idle handler 死循环饿死主线程 | `queueIdle` 返回 true 且又 post 可立即执行的消息 | 9.2 | 改为 `return false`；idle 内不 post 立即消息 |
| Handler 内存泄漏 | 非静态内部 Handler + `postDelayed` 持 Activity | 8.4 / 13.4 | `onDestroy` 调 `removeCallbacksAndMessages(null)`；MAT 验证 |
| `new Handler()` 编译告警（R+） | API 30 默认构造器废弃，需显式 Looper | 2.3 | 改 `new Handler(Looper.getMainLooper())` |
| `postSyncBarrier` 编译找不到 | 该方法已 `@hide`，应用不可用 | 6.2 | 改用 `Choreographer`/`setAsynchronous`；不要反射 |
| `Only one Looper may be created per thread` | 同一线程 `prepare` 两次 | 2.2 / 3.1 | 每个线程只 `prepare` 一次；复用 `getMainLooper` |
| 主线程栈顶是 `nativePollOnce` 但报 ANR | 误判：主线程其实空闲，是别处没发消息/对端阻塞 | 13.1 | 转去查 binder 对端 / AMS 是否卡 |
| 用 Messenger 跨进程但收不到 | `Messenger` 需 `IMessenger` Binder 正常连接；`replyTo` 未设导致回包失败 | 10.2 | 确认 `onBind` 返回 `messenger.getBinder()`；检查 `replyTo` |
| 开机很早的消息不执行 | `Looper.loop()` 未调用前消息只入队不处理 | 12.3 | 确认发送时机在 `loop()` 之后，或延后到 `onLoopReady` |
| `quit()` 主线程抛 `IllegalStateException` | 主线程 `mQuitAllowed=false`，不允许 quit | 3.2 / 3.3 | 不要 quit 主线程；子线程才用 quit |

---

## 15. 读源码路线（建议 4 条主线）

按"先跟一条完整链路、再挖底层、再看初始化、最后看特殊机制"的顺序读，效率最高。AOSP 路径默认 `frameworks/base/`。

### 路线 A：一次发送消息的完整链路
```text
Handler.sendMessage            android/os/Handler.java
  → sendMessageAtTime
  → enqueueMessage             (msg.target = this)
  → MessageQueue.enqueueMessage  android/os/MessageQueue.java
  → nativeWake(mPtr)            android_os_MessageQueue.cpp
```
读这条你能看清"入队 + 唤醒"两个动作，以及 `when` 怎么定。

### 路线 B：取消息与底层阻塞
```text
Looper.loop()                  android/os/Looper.java
  → MessageQueue.next()
  → nativePollOnce(ptr, timeout)  →  android_os_MessageQueue.cpp
    → NativeMessageQueue::pollOnce →  system/core/libutils/Looper.cpp
      → epoll_wait(mEpollFd, ...)   (空闲睡眠点)
      → 被 wake() 写 eventfd 唤醒
```
读这条你能理解"队列空为什么不烧 CPU"，以及 native / Java 消息如何汇合。

### 路线 C：主 Looper 初始化（进程起点）
```text
ActivityThread.main()          android/app/ActivityThread.java
  → Looper.prepareMainLooper()
  → Looper.prepare(false)  +  sMainLooper = myLooper()
  → ActivityThread.attach(false)
  → Looper.loop()
```
读这条你能确认"主线程为什么永远活着、为什么不能 quit"。

### 路线 D：同步屏障插入与异步消息
```text
ViewRootImpl.scheduleTraversals()   android/view/ViewRootImpl.java
  → mTraversalBarrier = mHandler.getLooper().getQueue()
        .postSyncBarrier()           (target==null 的哨兵)
  → Choreographer.postCallback(...)  (异步消息)
  → doTraversal() → removeSyncBarrier(token)
```
读这条你能看清"屏障怎么挡同步、异步消息怎么插队"，以及为什么应用不该自己造屏障（见 6.2 / 11 章）。

> 额外一条（车机推荐）：`HandlerThread.run()` → `Looper.prepare()` → `loop()`，对照路线 C 看"普通线程如何拥有自己的队列"。

---

## 16. 源码路径速查表 + 一图总结 + 关联阅读

### 16.1 源码路径速查表

| 内容 | 路径 | 备注 |
|---|---|---|
| `Looper` | `frameworks/base/core/java/android/os/Looper.java` | `prepare/loop/myLooper/getMainLooper` |
| `Handler` | `frameworks/base/core/java/android/os/Handler.java` | `send*/post*/dispatchMessage/enqueue` |
| `MessageQueue`(Java) | `frameworks/base/core/java/android/os/MessageQueue.java` | `enqueueMessage/next/postSyncBarrier` |
| `Message` | `frameworks/base/core/java/android/os/Message.java` | `obtain/recycle/setAsynchronous` |
| native MessageQueue JNI | `frameworks/base/core/jni/android_os_MessageQueue.cpp` | `nativePollOnce/nativeWake` |
| native Looper | `system/core/libutils/Looper.cpp` | `pollOnce/wake/epoll` |
| `HandlerThread` | `frameworks/base/core/java/android/os/HandlerThread.java` | `run/getLooper/quit` |
| `Messenger` / `IMessenger` | `frameworks/base/core/java/android/os/Messenger.java` + `IMessenger.aidl` | 跨进程消息封装 |
| `ActivityThread` | `frameworks/base/core/java/android/app/ActivityThread.java` | `main/prepareMainLooper` |
| `Choreographer` | `frameworks/base/core/java/android/view/Choreographer.java` | 衔接见第 11 章 |
| `ViewRootImpl` | `frameworks/base/core/java/android/view/ViewRootImpl.java` | 屏障插入点（路线 D） |

### 16.2 一图总结：Handler 全家桶

```text
  线程 Thread ── ThreadLocal ──► Looper (每线程一个, 主线程存 sMainLooper)
                                   │ mQueue
                                   ▼
                              MessageQueue
                              ├─ mMessages : 按 when 升序单链表
                              ├─ enqueueMessage() 按 when 插 + nativeWake(eventfd)
                              ├─ next() 取头节点 / 遇屏障跳过同步找异步
                              └─ mPtr ──► native Looper (epoll_wait, 空闲睡眠不烧CPU)
                                   ▲
        Handler A/B/C (同线程可多个) ┘ 都往同一队列 enqueue
        ├─ sendMessage/post → enqueueMessage(msg, when=uptimeMillis+delay)
        └─ dispatchMessage 优先级: msg.callback > mCallback > handleMessage

   主线程 loop() 不死 (prepare(false), next 永不返回 null)
   └─ 取到消息 → dispatch → handleMessage/Runnable.run
   └─ Choreographer 的 VSYNC 异步消息也在这条队列里 (见 22 篇第 3 节)

   一句话记忆链：
   "线程把自己交给 Looper，Looper 在队列空时睡在 epoll 上；
    谁想办事就 new Handler 往队列塞消息，队列按时间排好，
    醒了就一条条 dispatch——主线程因为不准 quit 所以永远醒着等活。"
```

### 16.3 关联阅读

- `01_系统启动详解`：开机早期主 Looper 何时 `prepare`/`loop`（第 12.3 章引用）。
- `05_AMS机制详解`、`06_WMS机制详解`：ANR 的**判定机制**与 AMS/WMS 内部如何靠 Handler 串行化调度（第 12.1 章分工边界）。
- `22_图形渲染机制详解-从HWUI到GPU上屏.md`（第 3 节）：Choreographer、VSYNC、同步屏障在渲染管线里的完整作用（第 6.2 / 11 章衔接）。
- `13_Input系统详解`：输入事件本质也是通过 native `addFd` 注册到主线程 `Looper` 的 fd，与 Handler 消息共用同一条 `epoll` 唤醒链（第 5.3 章）。
- `androidApp/01`（应用层协程与 Handler）：App 侧 `Handler` 与 `Dispatchers.Main` 的切换点、泄露修复套路——本文是 Framework 机制视角，App 用法不在此展开。

> **收尾提醒**：本文讲"机制"，不讲"App 怎么用得对"——后者在 `androidApp/` 系列。当你在车机里遇到"消息没按时到/主线程卡/队列饿死"，先用第 14 章表定位现象，再用第 15 章路线去 AOSP 跟代码，基本都能落到"某条具体消息在某行卡了太久"这一个 root cause 上。
