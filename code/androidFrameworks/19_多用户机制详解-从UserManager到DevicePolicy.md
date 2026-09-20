# 多用户机制详解：从 UserManager 到 DevicePolicy

> Android 是多用户系统。很多“App 明明装了但看不到”“广播收不到”“权限改不了”“应用卸载不了”的问题，根因都在 user/profile/device policy。

---

## 目录

1. [多用户是什么](#1多用户是什么)
2. [App 侧常见现象](#2app-侧常见现象)
3. [uid、userId、appId](#3uiduseridappid)
4. [UserManagerService](#4usermanagerservice)
5. [DevicePolicyManagerService](#5devicepolicymanagerservice)
6. [Direct Boot 和用户解锁](#6direct-boot-和用户解锁)
7. [和 PMS、AMS 的关系](#7和-pmsams-的关系)
8. [常见问题与排查](#8常见问题与排查)
9. [第三方系统常见修改点](#9第三方系统常见修改点)
10. [源码路径速查](#10源码路径速查)

---

## 1. 多用户是什么

Android 的应用安装、权限、数据目录、组件启动都带 user 维度。

同一个包可能：

- 对 user 0 已安装。
- 对 user 10 未安装。
- 在某个 profile 被禁用。
- 权限在不同 user 下状态不同。
- 数据目录按 user 隔离。

---

## 2. App 侧常见现象

| 现象 | 可能原因 |
|---|---|
| App 装了但 Launcher 看不到 | 当前 user 未安装/被隐藏/被禁用 |
| 广播收不到 | 广播发给了其他 user，或 user 未解锁 |
| Provider 访问失败 | 跨用户权限不足 |
| 应用卸载不了 | device owner/profile owner/device admin |
| 权限按钮灰掉 | policy fixed |
| 开机广播行为不同 | locked/unlocked user 状态不同 |

---

## 3. uid、userId、appId

Android uid 里编码了 userId 和 appId。

```text
uid = userId * 100000 + appId
```

例如同一个包在不同用户下会有不同 uid。

这解释了为什么权限、进程、数据目录都要看 user。

---

## 4. UserManagerService

`UserManagerService` 管理：

- 用户创建和删除。
- 用户切换。
- profile。
- 用户是否 running/unlocked。
- 用户限制 restrictions。
- guest、managed profile、system user。

常用命令：

```bash
adb shell pm list users
adb shell dumpsys user
```

---

## 5. DevicePolicyManagerService

设备策略服务用于企业和设备管理场景。

它可以：

- 设置 device owner。
- 设置 profile owner。
- 禁止卸载某些应用。
- 固定权限策略。
- 禁用相机、截屏、安装来源等能力。
- 设置用户限制。

所以应用卸载不了、权限改不了，不一定是 PMS bug，可能是 DPM 策略。

---

## 6. Direct Boot 和用户解锁

用户未解锁前，只能访问 device encrypted 存储：

```text
/data/user_de/<userId>/<package>
```

用户解锁后才能访问 credential encrypted 存储：

```text
/data/user/<userId>/<package>
```

开机广播也分：

- `LOCKED_BOOT_COMPLETED`
- `BOOT_COMPLETED`

---

## 7. 和 PMS、AMS 的关系

```text
PMS
  记录包在每个 user 下是否 installed/enabled/hidden

AMS/ATMS
  启动组件时检查目标 user 是否 running/unlocked，调用方是否能跨用户

UserManagerService
  提供用户状态和限制

DevicePolicyManagerService
  提供设备管理策略
```

---

## 8. 常见问题与排查

```bash
adb shell pm list users
adb shell pm list packages --user 0
adb shell dumpsys user
adb shell dumpsys package packageName
adb shell dumpsys device_policy
```

重点看：当前 user、包是否 installed、是否 hidden/disabled、用户是否 unlocked、是否有 device policy。

---

## 9. 第三方系统常见修改点

- 车机主用户/访客用户策略。
- 开机默认用户和自动切换。
- 不同用户预装不同应用。
- 禁止卸载关键应用。
- 固定权限和设备策略。
- 用户解锁前启动必要服务。

原则：多用户修改要同步考虑 PMS 安装状态、AMS 启动限制、数据目录、权限和 Launcher 展示。

---

## 10. 源码路径速查

| 内容 | 路径 |
|---|---|
| UserManagerService | `frameworks/base/services/core/java/com/android/server/pm/UserManagerService.java` |
| DevicePolicyManagerService | `frameworks/base/services/devicepolicy/java/com/android/server/devicepolicy/DevicePolicyManagerService.java` |
| UserManager API | `frameworks/base/core/java/android/os/UserManager.java` |
| DevicePolicyManager API | `frameworks/base/core/java/android/app/admin/DevicePolicyManager.java` |
| PMS user state | `frameworks/base/services/core/java/com/android/server/pm/Settings.java` |
