# 消息机制详解：从 Looper 到 Handler 和 Choreographer

> Android Framework 大量依赖消息机制。App 主线程、ActivityThread.H、AMS/WMS 内部 Handler、输入分发、View 绘制、Choreographer VSYNC，都绕不开 Looper/Handler/MessageQueue。

---

## 目录

1. [消息机制是什么](#1消息机制是什么)
2. [Looper、Handler、MessageQueue 的关系](#2looperhandlermessagequeue-的关系)
3. [App 主线程为什么能一直运行](#3app-主线程为什么能一直运行)
4. [ActivityThread.H 为什么重要](#4activitythreadh-为什么重要)
5. [Choreographer 和 VSYNC](#5choreographer-和-vsync)
6. [同步屏障和异步消息](#6同步屏障和异步消息)
7. [HandlerThread](#7handlerthread)
8. [ANR 和消息队列](#8anr-和消息队列)
9. [常见问题与排查](#9常见问题与排查)
10. [源码路径速查](#10源码路径速查)

---

## 1. 消息机制是什么

Android 的很多操作不是直接同步执行，而是投递到某个线程的消息队列中排队执行。

例如：

- AMS 回调 App 生命周期，最终投递到主线程。
- ViewRootImpl 收到输入，投递处理输入事件。
- Choreographer 收到 VSYNC，投递绘制任务。
- WMS/AMS 内部用 Handler 串行化状态修改。

一句话：

> Looper 让线程活着循环取消息，Handler 负责投递和处理消息，MessageQueue 负责排队。

---

## 2. Looper、Handler、MessageQueue 的关系

```text
Thread
  └─ Looper.loop()
       └─ MessageQueue.next()
            └─ Handler.dispatchMessage()
                 └─ Handler.handleMessage() / Runnable.run()
```

一个线程最多一个 Looper，一个 Looper 对应一个 MessageQueue，一个线程可以有多个 Handler 往同一个队列投消息。

---

## 3. App 主线程为什么能一直运行

App 进程入口：

```text
ActivityThread.main()
  ├─ Looper.prepareMainLooper()
  ├─ ActivityThread.attach(false)
  └─ Looper.loop()
```

主线程进入 `Looper.loop()` 后不会退出，而是不断处理消息：生命周期、输入、绘制、广播、Service 回调等。

主线程卡住，本质上就是某个消息处理太久，导致后续消息无法执行。

---

## 4. ActivityThread.H 为什么重要

`ActivityThread.H` 是 App 主线程处理 Framework 调度的重要 Handler。

它会处理：

- bindApplication。
- launch/resume/pause/stop Activity。
- create/bind Service。
- receive Broadcast。
- configuration changed。
- provider publish。

所以 ANR 文件里主线程如果卡在某个生命周期，通常就是某个 H 消息处理太久。

---

## 5. Choreographer 和 VSYNC

Choreographer 负责把输入、动画、布局绘制和屏幕刷新节奏对齐。

```text
VSYNC
  └─ Choreographer.doFrame
       ├─ input callbacks
       ├─ animation callbacks
       ├─ traversal callbacks
       └─ commit callbacks
```

View 绘制通常由 `ViewRootImpl.scheduleTraversals()` 触发，再通过 Choreographer 等待下一帧执行。

---

## 6. 同步屏障和异步消息

MessageQueue 支持同步屏障，用来让某些异步消息优先执行。View 绘制流程里会用到这个机制，保证 VSYNC 相关消息更及时。

理解这个点有助于看懂为什么某些普通 Handler 消息会被暂时挡住，而绘制相关消息能优先走。

---

## 7. HandlerThread

`HandlerThread` 是带 Looper 的工作线程，Framework 和 App 都常用它串行处理后台任务。

适合：

- 串行 I/O。
- 状态机。
- 系统服务内部调度。

不适合：CPU 密集型并行计算。

---

## 8. ANR 和消息队列

ANR 常见原因不是“线程死了”，而是主线程正在处理一个耗时消息，导致系统期待的回调迟迟无法执行。

例如：

- Input 事件处理超时。
- Broadcast `onReceive()` 超时。
- Service `onCreate()` 超时。
- Activity pause 回报超时。

排查时看主线程栈：它正在处理哪个消息，为什么没有回到 Looper 继续取下一条。

---

## 9. 常见问题与排查

- 主线程卡顿：看主线程 trace、Perfetto、Looper logging。
- 掉帧：看 Choreographer、ViewRootImpl、RenderThread。
- Handler 内存泄漏：避免非静态内部 Handler 长期持有 Activity。
- 消息乱序：检查 `postDelayed`、同步屏障、不同线程投递时机。

常用工具：

```bash
adb shell am trace-ipc start
adb shell dumpsys gfxinfo packageName framestats
adb logcat -b all | grep -i "Choreographer\|Skipped"
```

---

## 10. 源码路径速查

| 内容 | 路径 |
|---|---|
| Looper | `frameworks/base/core/java/android/os/Looper.java` |
| Handler | `frameworks/base/core/java/android/os/Handler.java` |
| MessageQueue | `frameworks/base/core/java/android/os/MessageQueue.java` |
| native Looper | `system/core/libutils/Looper.cpp` |
| ActivityThread | `frameworks/base/core/java/android/app/ActivityThread.java` |
| Choreographer | `frameworks/base/core/java/android/view/Choreographer.java` |
| ViewRootImpl | `frameworks/base/core/java/android/view/ViewRootImpl.java` |