# 通知机制详解：从 NotificationManagerService 到 SystemUI 展示

> 通知系统连接 App、前台服务、权限、渠道、NMS 和 SystemUI。App 调 `notify()` 只是入口，通知能不能显示、怎么排序、能不能 heads-up、锁屏是否展示，都由系统策略共同决定。

---

## 目录

1. [通知系统是什么](#1通知系统是什么)
2. [一次通知发布流程](#2一次通知发布流程)
3. [核心类和模块](#3核心类和模块)
4. [通知渠道和重要性](#4通知渠道和重要性)
5. [前台服务通知](#5前台服务通知)
6. [通知权限和 AppOps](#6通知权限和-appops)
7. [SystemUI 如何展示通知](#7systemui-如何展示通知)
8. [常见问题与排查](#8常见问题与排查)
9. [第三方系统常见修改点](#9第三方系统常见修改点)
10. [源码路径速查](#10源码路径速查)

---

## 1. 通知系统是什么

通知系统负责把 App 的消息、前台服务状态、系统提醒展示给用户。

它涉及：

- App 侧 `NotificationManager`。
- system_server 中的 `NotificationManagerService`。
- 权限和 AppOps。
- 通知渠道 Channel。
- SystemUI 展示。
- 锁屏隐私和 DND 勿扰模式。

---

## 2. 一次通知发布流程

```text
App
  NotificationManager.notify(id, notification)
        │ Binder
        ▼
system_server
  NotificationManagerService
        ├─ 校验权限和 AppOps
        ├─ 检查 channel
        ├─ 计算 importance / ranking
        ├─ 处理 DND、锁屏隐私、分组
        └─ 通知 SystemUI
             └─ 展示状态栏图标、通知列表、heads-up
```

---

## 3. 核心类和模块

| 类 / 模块 | 作用 |
|---|---|
| `NotificationManager` | App 侧 API |
| `INotificationManager` | Binder 接口 |
| `NotificationManagerService` | 通知管理主服务 |
| `NotificationRecord` | system_server 内部通知记录 |
| `RankingHelper` | 排序、渠道和重要性辅助 |
| `ZenModeHelper` | 勿扰模式 |
| `NotificationListenerService` | 通知监听服务 |
| SystemUI notification pipeline | 通知展示、过滤、分组、动画 |

---

## 4. 通知渠道和重要性

Android 8.0 后通知必须有 channel。

```java
NotificationChannel channel = new NotificationChannel(
        "message", "消息", NotificationManager.IMPORTANCE_HIGH);
notificationManager.createNotificationChannel(channel);
```

channel 决定：

- 是否有声音。
- 是否震动。
- 是否 heads-up。
- 锁屏显示程度。
- 用户是否关闭该类通知。

App 后续不能随意把用户调低的重要性改高。

---

## 5. 前台服务通知

前台服务必须展示通知：

```java
startForeground(notificationId, notification);
```

AMS/ActiveServices 会要求 Service 在规定时间内进入 foreground 状态，NMS 负责通知展示。前台服务异常经常要同时看 AMS 和 NMS。

---

## 6. 通知权限和 AppOps

Android 13 起普通通知需要 `POST_NOTIFICATIONS` 运行时权限。

同时通知也受 AppOps、渠道开关、DND、用户设置影响。

排查时不要只看 Manifest。

---

## 7. SystemUI 如何展示通知

NMS 负责通知数据和策略，SystemUI 负责真正的 UI 展示。

```text
NMS
  NotificationRecord / Ranking
        │
        ▼
SystemUI
  通知列表 / heads-up / 锁屏通知 / 图标
```

通知“不显示”可能是 NMS 侧没通过，也可能是 SystemUI 侧过滤或 UI 异常。

---

## 8. 常见问题与排查

```bash
adb shell dumpsys notification
adb shell appops get packageName
adb shell dumpsys package packageName | grep -i POST_NOTIFICATIONS
adb logcat -b all | grep -i "NotificationService\|NotificationManager\|SystemUI"
```

常见问题：

- 通知渠道 importance 太低。
- Android 13 通知权限未授权。
- DND 拦截。
- 前台服务通知不合规。
- SystemUI 展示过滤。
- 用户关闭了 channel。

---

## 9. 第三方系统常见修改点

- 通知白名单和黑名单。
- 车机通知过滤。
- heads-up 策略定制。
- 前台服务通知展示策略。
- 锁屏通知隐私。
- 通知声音、震动和渠道默认值。

原则：通知既是用户体验，也是安全和隐私边界。不要让后台 App 随意高优先级打扰用户。

---

## 10. 源码路径速查

| 内容 | 路径 |
|---|---|
| NotificationManagerService | `frameworks/base/services/core/java/com/android/server/notification/NotificationManagerService.java` |
| Notification API | `frameworks/base/core/java/android/app/NotificationManager.java` |
| NotificationChannel | `frameworks/base/core/java/android/app/NotificationChannel.java` |
| SystemUI 通知 | `frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/notification/` |
| Zen/DND | `frameworks/base/services/core/java/com/android/server/notification/ZenModeHelper.java` |
