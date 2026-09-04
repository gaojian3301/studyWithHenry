# SystemUI 机制详解：从状态栏到锁屏与通知面板

> SystemUI 不是普通 App，也不是单个 Framework Service。它是运行在独立进程里的系统界面集合，负责状态栏、导航栏、锁屏、通知面板、快捷设置、最近任务等用户每天都看到的系统 UI。

---

## 目录

1. [SystemUI 是什么](#1systemui-是什么)
2. [和 WMS、ATMS、Notification 的关系](#2和-wmsatmsnotification-的关系)
3. [核心模块](#3核心模块)
4. [状态栏与导航栏](#4状态栏与导航栏)
5. [通知面板](#5通知面板)
6. [锁屏 Keyguard](#6锁屏-keyguard)
7. [最近任务 Recents](#7最近任务-recents)
8. [常见问题与排查](#8常见问题与排查)
9. [第三方系统常见修改点](#9第三方系统常见修改点)
10. [源码路径速查](#10源码路径速查)

---

## 1. SystemUI 是什么

SystemUI 运行在 `com.android.systemui` 进程里，负责大量系统界面：

- 状态栏。
- 导航栏。
- 通知栏和通知面板。
- 快捷设置 QS。
- 锁屏 Keyguard。
- 最近任务。
- 音量面板。
- 截屏 UI。
- 部分权限/隐私提示。

一句话：

> Framework 服务负责系统规则，SystemUI 负责把很多系统状态变成用户能看见、能操作的界面。

---

## 2. 和 WMS、ATMS、Notification 的关系

```text
NotificationManagerService
  管通知数据和排序
        │
        ▼
SystemUI
  展示通知栏、heads-up、通知面板

WMS
  管 SystemUI 的状态栏/导航栏窗口

ATMS
  和 SystemUI 协作最近任务、锁屏启动、任务切换
```

SystemUI 的很多界面本质也是窗口，所以显示层面离不开 WMS；通知数据来自 NotificationManagerService；任务切换和最近任务离不开 ATMS。

---

## 3. 核心模块

| 模块 | 作用 |
|---|---|
| StatusBar / CentralSurfaces | 状态栏和通知面板核心 |
| NavigationBar | 导航栏 |
| Keyguard | 锁屏 |
| Notification pipeline | 通知列表、排序、过滤、渲染 |
| QS | 快捷设置面板 |
| Recents / Overview | 最近任务 |
| VolumeDialog | 音量面板 |
| Screenshot | 截屏 UI |

SystemUI 新版本大量使用 Kotlin、Dagger、MVVM/Scene/Compose 等架构，具体类名会随 Android 版本变化。

---

## 4. 状态栏与导航栏

状态栏/导航栏通常是 SystemUI 添加到 WMS 的系统窗口。

它们影响：

- App 可用区域。
- Insets。
- 沉浸式模式。
- 手势导航区域。
- 系统图标显示。
- 多屏系统栏策略。

如果 App 内容被状态栏或导航栏遮挡，要同时看 App Insets 处理、WMS Insets 状态和 SystemUI 系统栏窗口。

---

## 5. 通知面板

通知数据来自 NotificationManagerService，SystemUI 负责展示：

- 通知列表。
- heads-up。
- 锁屏通知。
- 通知分组。
- 通知点击 PendingIntent。
- 通知渠道展示效果。

通知不显示时，要区分：是 NMS 没接收/拦截，还是 SystemUI 展示过滤。

---

## 6. 锁屏 Keyguard

Keyguard 负责锁屏界面和解锁流程。

它和这些场景强相关：

- Activity 是否能显示在锁屏上。
- 来电/闹钟全屏显示。
- 生物识别解锁。
- 用户解锁前后 Direct Boot。
- 锁屏通知隐私。

WMS/ATMS 会和 Keyguard 状态协作，决定窗口是否可见、Activity 是否可启动到锁屏上。

---

## 7. 最近任务 Recents

最近任务展示的是 ATMS 管理的 Task 信息，但 UI 在 SystemUI。

```text
ATMS
  Task / ActivityRecord / snapshot
        │
        ▼
SystemUI Recents
  展示任务卡片、切换、清理
```

最近任务异常时，要同时看 ATMS 的 task 状态和 SystemUI 的 recents UI。

---

## 8. 常见问题与排查

```bash
adb shell dumpsys activity service com.android.systemui
adb shell dumpsys window | grep -i "StatusBar\|NavigationBar\|Keyguard"
adb logcat -b all | grep -i "SystemUI\|StatusBar\|Keyguard\|Notification"
```

常见问题：

- 状态栏不显示。
- 导航栏按键无效。
- 通知不弹 heads-up。
- 锁屏上 Activity 显示异常。
- 最近任务卡片异常。
- 多屏系统栏显示到错误屏幕。

---

## 9. 第三方系统常见修改点

- 车机状态栏/导航栏定制。
- 快捷设置 tile 定制。
- 锁屏界面和解锁策略定制。
- 通知过滤、白名单、优先级。
- 最近任务隐藏或改样式。
- 多屏 SystemUI 分屏显示策略。

原则：SystemUI 是用户可见系统界面，改动要同时验证 WMS 窗口、Insets、通知、锁屏、多用户和多显示。

---

## 10. 源码路径速查

| 内容 | 路径 |
|---|---|
| SystemUI | `frameworks/base/packages/SystemUI/` |
| Keyguard | `frameworks/base/packages/SystemUI/src/com/android/keyguard/` |
| Notification UI | `frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/notification/` |
| QS | `frameworks/base/packages/SystemUI/src/com/android/systemui/qs/` |
| Recents | `frameworks/base/packages/SystemUI/src/com/android/systemui/recents/` 或新版本相关目录 |
