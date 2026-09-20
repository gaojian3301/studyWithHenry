# 权限机制详解：从 Permission 到 AppOps

> PMS 文档里讲了权限声明和安装授权，这篇单独讲“为什么 Manifest 有权限、用户也点了允许，功能还是可能不能用”。答案通常在 PermissionManagerService 和 AppOpsService 的组合里。

---

## 目录

1. [先建立直觉](#1先建立直觉)
2. [Permission 和 AppOps 的区别](#2permission-和-appops-的区别)
3. [权限类型](#3权限类型)
4. [运行时权限流程](#4运行时权限流程)
5. [AppOps 工作方式](#5appops-工作方式)
6. [PMS、Permission、AppOps、AMS 的关系](#6pmspermissionappopsams-的关系)
7. [常见问题与排查](#7常见问题与排查)
8. [第三方系统常见修改点](#8第三方系统常见修改点)
9. [源码路径速查](#9源码路径速查)

---

## 1. 先建立直觉

Android 权限不是一个单点判断，而是一组机制：

- Manifest 声明。
- 安装时权限授予。
- 运行时权限弹窗。
- 签名权限校验。
- priv-app 白名单。
- AppOps 运行时开关。
- 设备策略固定权限。
- 用户和 profile 限制。

一句话：

> Permission 决定“你有没有资格”，AppOps 经常决定“这次操作放不放行”。

---

## 2. Permission 和 AppOps 的区别

| 机制 | 直观理解 | 例子 |
|---|---|---|
| Permission | 权限资格，通常来自 Manifest 和授权状态 | CAMERA、RECORD_AUDIO |
| AppOps | 操作开关，更细粒度地控制一次行为 | 后台定位、悬浮窗、通知、使用情况访问 |

可能出现：

```text
checkSelfPermission() == GRANTED
但 AppOps mode == ignored
最终功能仍然不可用
```

---

## 3. 权限类型

| 类型 | 特点 |
|---|---|
| normal | 安装时自动授予，风险低 |
| dangerous | 运行时授权，相机、位置、麦克风等 |
| signature | 签名一致才授予 |
| privileged | priv-app 且白名单允许 |
| development | 调试用途，可 adb grant |
| role 相关权限 | 默认短信、电话等角色带来的能力 |

privileged 权限不是放到 `/system/priv-app` 就自动都有，还需要 `privapp-permissions-*.xml` 白名单。

---

## 4. 运行时权限流程

```text
App requestPermissions
  └─ PermissionController 展示 UI
       └─ 用户允许/拒绝
            └─ PermissionManagerService 更新权限状态
                 └─ 写 runtime-permissions.xml
```

常见状态：

- granted。
- denied。
- user fixed：用户选择不再询问。
- policy fixed：设备策略固定，用户不能改。
- one-time permission：一次性授权。
- auto revoke：长期不用后自动回收权限。

---

## 5. AppOps 工作方式

AppOps 按 uid/package/op 记录操作模式：

- allow：允许。
- ignore：静默拒绝。
- deny：拒绝并可能抛异常。
- default：按默认策略。
- foreground：只允许前台使用。

常见命令：

```bash
adb shell appops get packageName
adb shell appops set packageName SYSTEM_ALERT_WINDOW allow
adb shell appops set packageName ACCESS_FINE_LOCATION foreground
```

---

## 6. PMS、Permission、AppOps、AMS 的关系

```text
PMS
  解析 Manifest，知道 App 申请了什么权限
        │
        ▼
PermissionManagerService
  管权限是否授予
        │
        ▼
AppOpsService
  管具体操作是否允许
        │
        ▼
AMS / 各系统服务
  在启动组件或执行敏感操作时检查权限和 AppOps
```

例如悬浮窗：Manifest 权限不够，还要 AppOps 允许，WMS 才会让 overlay window 添加。

---

## 7. 常见问题与排查

### 有权限但功能不可用

```bash
adb shell dumpsys package packageName | grep -i permission
adb shell appops get packageName
adb shell dumpsys permission
```

### priv-app 权限没授予

检查：

- APK 是否在 priv-app 目录。
- 白名单文件是否在对应分区。
- 权限是否属于 privileged。
- 签名是否满足。

### 权限被策略固定

看 DevicePolicy：

```bash
adb shell dumpsys device_policy
```

---

## 8. 第三方系统常见修改点

- 给系统应用默认授予运行时权限。
- 增加 priv-app 权限白名单。
- 放宽悬浮窗、通知、后台定位 AppOps。
- 企业设备固定某些权限状态。
- 车机系统应用按角色获得特殊能力。

原则：权限和 AppOps 是安全边界，白名单优先基于签名/uid/role，不要只按包名全放开。

---

## 9. 源码路径速查

| 内容 | 路径 |
|---|---|
| PermissionManagerService | `frameworks/base/services/core/java/com/android/server/pm/permission/PermissionManagerService.java` |
| AppOpsService | `frameworks/base/services/core/java/com/android/server/appop/AppOpsService.java` |
| PackageManagerService | `frameworks/base/services/core/java/com/android/server/pm/PackageManagerService.java` |
| PermissionController | `packages/modules/Permission/` |
| AppOpsManager API | `frameworks/base/core/java/android/app/AppOpsManager.java` |