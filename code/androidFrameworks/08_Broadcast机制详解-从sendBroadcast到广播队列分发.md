# Broadcast 机制详解：从 sendBroadcast 到广播队列分发

> Broadcast 是 Android 事件分发机制。它看起来只是 `sendBroadcast()`，实际会经过 AMS、PMS、广播队列、后台限制、多用户、权限和 ANR 管理。

---

## 目录

1. [Broadcast 是什么](#1broadcast-是什么)
2. [从 App 侧看广播问题](#2从-app-侧看广播问题)
3. [广播类型](#3广播类型)
4. [发送广播流程](#4发送广播流程)
5. [静态 Receiver 和动态 Receiver](#5静态-receiver-和动态-receiver)
6. [有序广播和并行广播](#6有序广播和并行广播)
7. [后台限制和隐式广播限制](#7后台限制和隐式广播限制)
8. [广播 ANR](#8广播-anr)
9. [常见问题与排查](#9常见问题与排查)
10. [第三方系统常见修改点](#10第三方系统常见修改点)
11. [源码路径速查](#11源码路径速查)

---

## 1. Broadcast 是什么

Broadcast 用来在系统或应用之间分发事件：

```java
sendBroadcast(intent);
registerReceiver(receiver, filter);
```

常见广播：

- 开机完成。
- 网络变化。
- 包安装/卸载。
- 电量变化。
- 用户切换。
- 自定义业务事件。

一句话：

> 广播不是简单回调列表，而是 AMS 管理的一套带权限、队列、用户、后台限制和超时的事件分发机制。

---

## 2. 从 App 侧看广播问题

| 现象 | 可能原因 |
|---|---|
| manifest Receiver 收不到 | Android 8+ 隐式广播限制、未安装当前 user、stopped 状态 |
| 动态 Receiver 收不到 | 注册进程已死、注册时机不对、action 不匹配 |
| BOOT_COMPLETED 收不到 | 权限缺失、用户未解锁、应用 stopped、厂商自启限制 |
| 有序广播很慢 | 前一个 Receiver 卡住 |
| onReceive ANR | 主线程耗时、冷启动慢、未及时 finish |

---

## 3. 广播类型

| 类型 | 特点 |
|---|---|
| 普通广播 | 可并行分发，接收者互不等待 |
| 有序广播 | 按顺序逐个分发，前一个完成后才到下一个 |
| sticky 广播 | 保存最后值，现代 Android 中大量限制或废弃 |
| 显式广播 | 指定 package/component，范围明确 |
| 隐式广播 | 按 action/category/data 匹配，限制更多 |

---

## 4. 发送广播流程

```text
App
  ContextImpl.sendBroadcast()
        │ Binder
        ▼
system_server
  ActivityManagerService.broadcastIntentWithFeature()
        ├─ 校验调用方 uid/pid、权限、userId
        ├─ 处理广播 flag 和后台限制
        ├─ 查询动态 Receiver：AMS 内部 ReceiverList
        ├─ 查询静态 Receiver：PMS resolveReceiver
        ├─ 生成 BroadcastRecord
        └─ 放入 BroadcastQueue / BroadcastProcessQueue
```

---

## 5. 静态 Receiver 和动态 Receiver

### 静态 Receiver

写在 manifest 里，由 PMS 扫描记录。进程没启动时，也可能被广播拉起。

### 动态 Receiver

运行时注册：

```java
registerReceiver(receiver, filter);
```

它只在注册进程存活期间有效。进程死了，动态 Receiver 就没了。

---

## 6. 有序广播和并行广播

有序广播链路：

```text
Receiver A onReceive
  └─ finishReceiver
       └─ Receiver B onReceive
            └─ finishReceiver
                 └─ Receiver C onReceive
```

前一个 Receiver 卡住，后面的都会等待。因此有序广播更容易因为单个接收者变慢。

---

## 7. 后台限制和隐式广播限制

Android 8.0 后，大量隐式广播不能再随便通过 manifest 静态接收。

原因是静态广播会频繁拉起后台进程，影响启动速度、内存和功耗。

替代方式：

- 动态注册。
- JobScheduler / WorkManager。
- 显式广播。
- 系统白名单广播。

---

## 8. 广播 ANR

`onReceive()` 在主线程执行，AMS 会等待 Receiver 完成。

常见 ANR 原因：

- `onReceive()` 做网络或数据库重活。
- 冷启动进程太慢。
- 主线程等锁或 Binder。
- 有序广播前一个接收者卡住。

原则：`onReceive()` 只做轻量工作，重活交给 Job/WorkManager/前台服务。

---

## 9. 常见问题与排查

```bash
adb shell dumpsys activity broadcasts
adb shell cmd package query-receivers --brief -a actionName packageName
adb logcat -b all | grep -i "BroadcastQueue\|BroadcastRecord\|finishReceiver"
```

重点看：广播是否入队、接收者是否解析到、当前卡在哪个 Receiver、userId 是否正确、权限是否匹配。

---

## 10. 第三方系统常见修改点

- 开机广播白名单。
- 放宽静态隐式广播限制。
- 自启动管理策略。
- 有序广播超时调整。
- 多用户广播分发策略。
- 车机电源状态广播定制。

原则：广播放宽会直接影响后台拉起、功耗和开机速度，最好只对明确系统应用或签名应用放行。

---

## 11. 源码路径速查

| 内容 | 路径 |
|---|---|
| AMS 广播入口 | `frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java` |
| BroadcastQueue | `frameworks/base/services/core/java/com/android/server/am/BroadcastQueue.java` |
| BroadcastRecord | `frameworks/base/services/core/java/com/android/server/am/BroadcastRecord.java` |
| ReceiverList | `frameworks/base/services/core/java/com/android/server/am/ReceiverList.java` |
| BroadcastFilter | `frameworks/base/services/core/java/com/android/server/am/BroadcastFilter.java` |
| ActivityThread receiver | `frameworks/base/core/java/android/app/ActivityThread.java` |
