# Service 机制详解：从 startService 到 bindService 和前台服务

> Service 是 AMS 管理里最贴近后台执行的一类组件。它能解释后台任务为什么起不来、前台服务为什么会超时、绑定服务为什么能提高进程优先级、远端 Service 为什么会死亡。

---

## 目录

1. [Service 是什么](#1service-是什么)
2. [从 App 侧看 Service 问题](#2从-app-侧看-service-问题)
3. [startService 流程](#3startservice-流程)
4. [bindService 流程](#4bindservice-流程)
5. [前台服务流程](#5前台服务流程)
6. [Service 和进程优先级](#6service-和进程优先级)
7. [远端 Service 和 Binder 死亡](#7远端-service-和-binder-死亡)
8. [Service ANR](#8service-anr)
9. [常见问题与排查](#9常见问题与排查)
10. [第三方系统常见修改点](#10第三方系统常见修改点)
11. [源码路径速查](#11源码路径速查)

---

## 1. Service 是什么

Service 是一种没有界面的应用组件，常用于后台或跨进程能力暴露。

常见用法：

```java
startService(intent);
startForegroundService(intent);
bindService(intent, connection, flags);
```

一句话：

> Service 不是线程，也不是进程保活神器；它是由 AMS 管理生命周期的组件，默认生命周期回调仍在 App 主线程执行。

---

## 2. 从 App 侧看 Service 问题

| 现象 | 可能原因 |
|---|---|
| 后台 startService 报错 | Android 8+ 后台 Service 限制 |
| 前台服务启动后崩溃 | 未及时调用 `startForeground()` |
| bindService 后进程不容易死 | 绑定关系提高 OOM 优先级 |
| onStartCommand 很慢导致 ANR | Service 生命周期主线程执行 |
| onServiceDisconnected | 远端进程死亡或 Binder 断开 |

---

## 3. startService 流程

```text
App
  ContextImpl.startService()
        │ Binder
        ▼
AMS
  ActiveServices.startServiceLocked()
        ├─ PMS 查询 ServiceInfo
        ├─ 检查 permission/exported/background limit
        ├─ 创建 ServiceRecord
        ├─ 必要时启动目标进程
        └─ realStartServiceLocked()
             └─ IApplicationThread.scheduleCreateService()
```

App 侧收到后：

```text
ActivityThread.handleCreateService
  ├─ 反射创建 Service
  ├─ Service.attach
  ├─ Service.onCreate
  └─ Service.onStartCommand
```

---

## 4. bindService 流程

```text
App A bindService
        │ Binder
        ▼
AMS / ActiveServices
  创建 ConnectionRecord / AppBindRecord
        │
        ├─ 必要时启动 App B 进程
        ├─ 调用 Service.onBind
        └─ publishService 返回 IBinder
             │
             ▼
App A
  ServiceConnection.onServiceConnected
```

`onBind()` 返回的 Binder 可能是本地 Binder，也可能是远端 BinderProxy。

---

## 5. 前台服务流程

前台服务适合用户可感知的持续任务。

```java
startForegroundService(intent);
// Service 启动后尽快调用
startForeground(id, notification);
```

限制点：

- Android 8+ 要及时 `startForeground()`。
- Android 12+ 后台启动前台服务限制更严格。
- Android 14+ 前台服务类型和权限更细。
- 必须有合规通知。

---

## 6. Service 和进程优先级

Service 会影响 OOM adj：

- 正在执行生命周期的 Service 会临时提高优先级。
- 前台服务显著提高进程重要性。
- 被前台进程绑定的 Service 可能继承一定重要性。
- 普通后台 Service 不保证进程长期存活。

---

## 7. 远端 Service 和 Binder 死亡

跨进程 bindService 时，远端进程可能死亡。

客户端要处理：

- `onServiceDisconnected()`。
- `DeadObjectException`。
- `linkToDeath()`。
- 重新绑定和状态恢复。

---

## 8. Service ANR

Service 生命周期在主线程执行，AMS 有超时监控。

常见根因：

- `onCreate()` 做重活。
- `onStartCommand()` 做网络/数据库。
- `onBind()` 等待锁或 Binder。
- 前台服务未及时 `startForeground()`。

---

## 9. 常见问题与排查

```bash
adb shell dumpsys activity services packageName
adb logcat -b all | grep -i "ActiveServices\|ServiceRecord\|ForegroundService"
adb shell dumpsys activity processes
```

重点看 ServiceRecord、startRequested、connections、foreground、executing、timeout。

---

## 10. 第三方系统常见修改点

- 后台 Service 白名单。
- 前台服务启动限制放宽。
- 核心服务保活。
- 绑定服务优先级调整。
- Service ANR 超时阈值调整。

原则：Service 策略直接影响后台常驻、功耗和内存，修改要限制调用方并保留日志。

---

## 11. 源码路径速查

| 内容 | 路径 |
|---|---|
| ActiveServices | `frameworks/base/services/core/java/com/android/server/am/ActiveServices.java` |
| ServiceRecord | `frameworks/base/services/core/java/com/android/server/am/ServiceRecord.java` |
| ConnectionRecord | `frameworks/base/services/core/java/com/android/server/am/ConnectionRecord.java` |
| AppBindRecord | `frameworks/base/services/core/java/com/android/server/am/AppBindRecord.java` |
| ActivityThread Service | `frameworks/base/core/java/android/app/ActivityThread.java` |
| Service API | `frameworks/base/core/java/android/app/Service.java` |
