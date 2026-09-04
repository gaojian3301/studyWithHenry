# Android 启动流程详解：从 init 到 Zygote 和 SystemServer

> 这篇补齐 Framework 学习里的“系统从哪里来”。前面的 AMS/PMS/WMS 都运行在系统启动之后；这里要看 Android 如何从 init 启动 Zygote，再 fork 出 system_server，并启动各大 SystemService。

---

## 目录

1. [Android 启动总览](#1android-启动总览)
2. [init 进程](#2init-进程)
3. [Zygote 是什么](#3zygote-是什么)
4. [SystemServer 是什么](#4systemserver-是什么)
5. [SystemServiceManager 和启动阶段](#5systemservicemanager-和启动阶段)
6. [PMS、AMS、WMS 等服务如何启动](#6pmsamswms-等服务如何启动)
7. [开机广播和 Launcher 启动](#7开机广播和-launcher-启动)
8. [常见问题与排查](#8常见问题与排查)
9. [第三方系统常见修改点](#9第三方系统常见修改点)
10. [源码路径速查](#10源码路径速查)

---

## 1. Android 启动总览

```text
Bootloader
  └─ Linux Kernel
       └─ init
            ├─ 启动 servicemanager、vold、ueventd 等 native 服务
            ├─ 启动 zygote / zygote64
            └─ Zygote fork system_server
                 └─ SystemServer.main
                      ├─ 启动 PMS
                      ├─ 启动 AMS/ATMS
                      ├─ 启动 WMS
                      ├─ 启动 Power/Input/Notification 等服务
                      └─ 启动 Launcher
```

一句话：

> init 是第一个用户空间进程，Zygote 是 Java 世界的孵化器，system_server 是 Framework 服务的大本营。

---

## 2. init 进程

init 负责：

- 解析 `.rc` 脚本。
- 挂载文件系统。
- 设置属性服务。
- 启动 native daemon。
- 启动 Zygote。
- 管理服务重启。

常见 rc 服务：

```text
service zygote /system/bin/app_process64 ...
service surfaceflinger /system/bin/surfaceflinger
service servicemanager /system/bin/servicemanager
```

---

## 3. Zygote 是什么

Zygote 是 Android Java 进程的父进程。

它会预加载：

- Android framework 类。
- 常用资源。
- ART runtime。
- 部分 native 库。

之后 App 进程和 system_server 都可以从 Zygote fork，减少启动成本。

```text
Zygote
  ├─ fork system_server
  ├─ fork App A
  └─ fork App B
```

---

## 4. SystemServer 是什么

`system_server` 是大部分 Java Framework 服务所在进程。

里面运行：

- PMS。
- AMS。
- ATMS。
- WMS。
- InputManagerService。
- PowerManagerService。
- NotificationManagerService。
- JobSchedulerService。
- AlarmManagerService。
- UserManagerService。

system_server 崩溃通常会导致系统重启，因为它是 Framework 的核心进程。

---

## 5. SystemServiceManager 和启动阶段

SystemServer 会用 `SystemServiceManager` 启动服务，并分阶段通知 boot phase。

常见阶段：

- 等待默认显示 ready。
- 系统服务 ready。
- ActivityManager ready。
- 第三方应用可启动。
- boot completed。

服务启动顺序很重要：PMS 要先知道包信息，AMS/ATMS 才能启动组件，WMS/Display 要准备显示，最后才能启动 Launcher。

---

## 6. PMS、AMS、WMS 等服务如何启动

粗略顺序：

```text
SystemServer.startBootstrapServices
  ├─ Installer / installd 连接
  ├─ PowerManagerService
  ├─ PackageManagerService
  └─ ActivityManagerService

SystemServer.startCoreServices
  └─ BatteryService / UsageStats 等

SystemServer.startOtherServices
  ├─ WindowManagerService
  ├─ InputManagerService
  ├─ NotificationManagerService
  ├─ JobSchedulerService
  ├─ AlarmManagerService
  └─ 其他服务
```

不同 Android 版本顺序和拆分会变化，但 bootstrapping 逻辑类似。

---

## 7. 开机广播和 Launcher 启动

系统服务 ready 后，AMS/ATMS 会启动 Home：

```text
ATMS 启动 Home Intent
  └─ PMS 解析 Launcher Activity
       └─ AMS 启动 Launcher 进程
            └─ ActivityThread 创建 Launcher Activity
```

随后系统发送：

- `LOCKED_BOOT_COMPLETED`
- `BOOT_COMPLETED`

开机自启动问题经常要看 SystemServer boot phase、用户解锁状态、PMS 包状态和 AMS 广播队列。

---

## 8. 常见问题与排查

```bash
adb logcat -b all | grep -i "SystemServer\|PackageManager\|ActivityManager\|BOOT_COMPLETED"
adb shell getprop sys.boot_completed
adb shell dumpsys activity broadcasts
adb shell dumpsys package
```

常见问题：

- 开机卡在 boot animation。
- system_server 崩溃重启。
- PMS 扫描慢。
- Launcher 启动失败。
- BOOT_COMPLETED 没收到。

---

## 9. 第三方系统常见修改点

- 预装服务启动顺序。
- 开机自启动白名单。
- 缩短 boot animation 时间。
- 延迟启动非核心服务。
- 车机 ACC/休眠恢复流程。
- 首次开机初始化应用和数据。

原则：启动流程修改要谨慎，system_server 早期服务依赖复杂，顺序错了很容易导致开机卡死或循环重启。

---

## 10. 源码路径速查

| 内容 | 路径 |
|---|---|
| init | `system/core/init/` |
| init rc | `system/core/rootdir/init*.rc`、设备目录 rc |
| ZygoteInit | `frameworks/base/core/java/com/android/internal/os/ZygoteInit.java` |
| ZygoteConnection | `frameworks/base/core/java/com/android/internal/os/ZygoteConnection.java` |
| SystemServer | `frameworks/base/services/java/com/android/server/SystemServer.java` |
| SystemServiceManager | `frameworks/base/services/core/java/com/android/server/SystemServiceManager.java` |
| SystemService | `frameworks/base/services/core/java/com/android/server/SystemService.java` |
