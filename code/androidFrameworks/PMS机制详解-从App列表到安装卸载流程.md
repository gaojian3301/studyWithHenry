# PMS 机制详解：从 App 列表到安装卸载流程

> 以 App 开发和系统应用最常用的能力为入口：获取应用列表、查询组件、安装 APK、卸载应用、权限授权、签名校验。目标是把你在应用侧看到的 `PackageManager` API、`pm install/uninstall` 命令，和 Framework 里的 `PackageManagerService` 工作流程对应起来。
> 目标读者：写过 Android App 或系统应用，知道 APK、Manifest、四大组件，希望理解 PMS 如何管理包信息、安装卸载、权限、组件解析和系统定制策略。

---

## 目录

1. [先建立直觉：PMS 是什么](#1先建立直觉pms-是什么)
2. [从 App 侧看 PMS：你最常用的能力](#2从-app-侧看-pms你最常用的能力)
3. [PMS 在系统中的位置](#3pms-在系统中的位置)
4. [PMS 涉及到的核心类](#4pms-涉及到的核心类)
5. [系统启动时 PMS 如何扫描应用](#5系统启动时-pms-如何扫描应用)
6. [获取 App 列表和包信息流程](#6获取-app-列表和包信息流程)
7. [Intent 解析和组件查询流程](#7intent-解析和组件查询流程)
8. [APK 安装流程](#8apk-安装流程)
9. [APK 卸载流程](#9apk-卸载流程)
10. [更新、降级和替换安装](#10更新降级和替换安装)
11. [权限管理流程](#11权限管理流程)
12. [签名、sharedUserId 与系统应用](#12签名shareduserid-与系统应用)
13. [应用数据目录和 dex/oat 优化](#13应用数据目录和-dexoat-优化)
14. [常见问题与排查方法](#14常见问题与排查方法)
15. [第三方系统常见修改点](#15第三方系统常见修改点)
16. [读源码的推荐路线](#16读源码的推荐路线)
17. [关键源码路径速查](#17关键源码路径速查)
18. [一图总结](#18一图总结)

---

## 1. 先建立直觉：PMS 是什么

**一句话：PMS 是 Android 系统里负责“认识、安装、卸载、查询、校验应用”的包管理中心。**

AMS 更关心“应用进程和组件什么时候运行”，PMS 更关心“系统里有哪些包、每个包声明了什么、有没有权限、签名是否可信、组件能不能被找到”。

你平时用到的这些能力，背后基本都要经过 PMS：

```java
PackageManager pm = context.getPackageManager();

List<ApplicationInfo> apps = pm.getInstalledApplications(0);
PackageInfo info = pm.getPackageInfo("com.example", 0);
ResolveInfo activity = pm.resolveActivity(intent, 0);
List<ResolveInfo> receivers = pm.queryBroadcastReceivers(intent, 0);
```

命令行也是一样：

```bash
adb shell pm list packages
adb install app.apk
adb shell pm uninstall com.example
adb shell pm grant com.example android.permission.CAMERA
```

PMS 主要负责：

- **包扫描**：系统启动时扫描 `/system/app`、`/system/priv-app`、`/product/app`、`/vendor/app`、`/data/app` 等目录。
- **Manifest 解析**：读取 package、version、application、activity、service、receiver、provider、permission 等声明。
- **安装和卸载**：校验 APK、拷贝或移动文件、创建数据目录、更新系统包设置。
- **包信息查询**：给 App、AMS、Launcher、Settings 提供包和组件信息。
- **Intent 解析**：根据 action/category/data/component 找到匹配的 Activity、Service、Receiver、Provider。
- **权限管理**：安装时授权、运行时授权、签名权限、privileged 权限、AppOps 配合。
- **签名校验**：安装更新、共享 uid、系统权限、签名权限都依赖签名判断。
- **多用户管理**：同一个 APK 可以对不同 user 有不同安装状态、启用状态和权限状态。

---

## 2. 从 App 侧看 PMS：你最常用的能力

你之前理解的“PMS 常用就是获取 App 列表、卸载、安装”是很好的入口。可以这样扩展：

| App/系统应用想做什么 | 常用 API / 命令 | PMS 背后做什么 |
|---|---|---|
| 获取已安装 App 列表 | `getInstalledPackages()` / `pm list packages` | 按 user、可见性、flag 过滤已知包 |
| 获取某个 App 信息 | `getPackageInfo()` | 返回 Manifest 解析后的包信息、签名、权限、组件等 |
| 判断 Intent 能否打开 | `resolveActivity()` | 用 IntentResolver 匹配 Activity intent-filter |
| 查询 Launcher 图标 | `queryIntentActivities(ACTION_MAIN + CATEGORY_LAUNCHER)` | 查有 Launcher 入口的 Activity |
| 安装 APK | `adb install` / `PackageInstaller` | 校验、解析、拷贝、创建数据、dexopt、更新设置、发广播 |
| 卸载 APK | `pm uninstall` / `PackageInstaller.uninstall()` | 校验权限、删除代码和数据、更新状态、发广播 |
| 禁用 App | `setApplicationEnabledSetting()` / `pm disable-user` | 改 user 维度启用状态，不一定删除 APK |
| 授权权限 | `requestPermissions()` / `pm grant` | 更新运行时权限状态，通知相关服务 |
| 查询权限 | `checkPermission()` | 结合 uid、user、权限状态判断是否允许 |

这里要先建立一个关键区别：

> PMS 管的是“包的存在和声明”，AMS 管的是“进程和组件的运行”。启动 Activity 时，AMS/ATMS 要问 PMS：这个 Intent 对应哪个 Activity？这个 Activity 是否 exported？调用方有没有权限？然后 AMS/ATMS 再决定是否启动进程和调度生命周期。

---

## 3. PMS 在系统中的位置

PMS 运行在 `system_server` 进程中，和 AMS、WMS、ATMS 等系统服务同进程。

```text
App 进程
  PackageManager API
        │ Binder
        ▼
system_server
  PackageManagerService
        │
        ├─ 扫描 APK 目录
        ├─ 解析 AndroidManifest.xml
        ├─ 维护 packages.xml / package-restrictions.xml
        ├─ 处理安装、卸载、更新
        ├─ 管理权限和签名
        ├─ 提供组件查询和 Intent 解析
        └─ 通知 AMS / Launcher / Settings / PermissionController

installd native daemon
  创建/删除数据目录、dexopt、清理 oat/cache
```

App 侧拿到的是 `ApplicationPackageManager`，它内部通过 Binder 调用 `IPackageManager`：

```text
context.getPackageManager()
  └─ ApplicationPackageManager
       └─ IPackageManager.Proxy
            └─ Binder 到 PackageManagerService
```

安装相关能力现在更多通过 `PackageInstaller` 暴露：

```text
PackageInstaller.Session
  写入 APK
  commit()
        │ Binder
        ▼
PackageInstallerService / PackageManagerService
```

---

## 4. PMS 涉及到的核心类

### 4.1 对外接口和 App 侧包装

| 类 / 接口 | 作用 |
|---|---|
| `PackageManager` | App 侧公开 API 抽象类 |
| `ApplicationPackageManager` | App 侧具体实现，内部持有 `IPackageManager` 代理 |
| `IPackageManager.aidl` | App/system_server 调 PMS 的 Binder 接口 |
| `PackageInstaller` | 面向安装会话的公开 API |
| `PackageInstaller.Session` | 一次安装会话，负责写入 APK、提交安装 |
| `LauncherApps` | Launcher 查询用户可启动应用的 API |

### 4.2 system_server 内部核心类

| 类 | 作用 |
|---|---|
| `PackageManagerService` | PMS 主服务，包扫描、查询、安装、卸载、权限协调 |
| `Computer` | 新版本 PMS 中用于提供一致包快照查询的对象 |
| `Settings` | 维护包持久化配置，如 packages.xml、权限、安装状态 |
| `PackageSetting` | 一个 package 的系统侧持久化状态 |
| `PackageStateInternal` | 包状态查询视图，包括安装、启用、用户维度状态 |
| `ParsedPackage` | Manifest 解析后的包结构 |
| `AndroidPackage` | 扫描后系统内部使用的包模型 |
| `PackageParser` / `ParsingPackageUtils` | 旧/新版本中的 APK 和 Manifest 解析逻辑 |
| `ComponentResolver` | 管理 Activity、Service、Receiver、Provider 的查询和解析 |
| `AppsFilter` | Android 11+ 包可见性过滤逻辑 |
| `PermissionManagerService` | 权限管理核心服务，和 PMS 紧密协作 |
| `PackageInstallerService` | 管理安装 session、安装回调和安装来源 |
| `InstallPackageHelper` | 新版本中承载安装流程的辅助类 |
| `DeletePackageHelper` | 新版本中承载卸载流程的辅助类 |
| `InstallArgs` / `FileInstallArgs` | 安装路径、文件操作参数封装 |

### 4.3 native 和存储相关模块

| 模块 | 作用 |
|---|---|
| `installd` | native 守护进程，负责数据目录、dexopt、清理、快照等底层文件操作 |
| `dex2oat` | 将 dex 编译成 oat/vdex，提高运行性能 |
| `vold` | 存储卷和外部存储相关管理 |
| `apexd` | APEX 包管理，Android 10+ 系统模块化相关 |

可以这样理解：PMS 做“策略和状态管理”，`installd` 做“需要高权限的文件系统操作”。

---

## 5. 系统启动时 PMS 如何扫描应用

开机后系统必须先知道“设备里有哪些应用”，才能让 Launcher 展示图标、AMS 启动组件、Settings 展示应用列表。

### 5.1 扫描哪些目录

典型目录包括：

| 目录 | 含义 |
|---|---|
| `/system/app` | 系统普通应用 |
| `/system/priv-app` | 系统特权应用，可申请 privileged 权限 |
| `/product/app`、`/product/priv-app` | 产品分区应用 |
| `/vendor/app`、`/vendor/priv-app` | vendor 分区应用 |
| `/odm/app`、`/oem/app` | 厂商/设备定制应用 |
| `/data/app` | 用户安装或更新后的应用 APK |
| `/data/app-staging` | 安装过程中的临时 staging 目录 |

不同 Android 版本和设备分区会有差异，但思路一致：系统分区放预装应用，`/data/app` 放用户安装和系统应用更新包。

### 5.2 扫描做了什么

```text
SystemServer 启动 PMS
  └─ PMS 读取 Settings：packages.xml 等历史状态
       └─ 扫描系统分区 APK
            └─ 扫描 /data/app APK
                 └─ 解析 AndroidManifest.xml
                      ├─ 建立 package 信息
                      ├─ 建立 Activity/Service/Receiver/Provider 索引
                      ├─ 校验签名和版本
                      ├─ 恢复 user 维度安装/启用/权限状态
                      └─ 写回 packages.xml
```

### 5.3 PMS 持久化了什么

常见持久化文件：

| 文件 | 作用 |
|---|---|
| `/data/system/packages.xml` | 所有包的核心状态：包名、codePath、签名、版本、sharedUser、权限等 |
| `/data/system/packages.list` | 给 native 层和其他模块使用的包列表简表 |
| `/data/system/users/<userId>/package-restrictions.xml` | 每个用户下包的安装、隐藏、启用、停止等状态 |
| `/data/system/users/<userId>/runtime-permissions.xml` | 每个用户下运行时权限授权状态 |

这解释了一个现象：同一个 APK 文件存在，不代表对所有用户都“已安装、已启用、可见”。多用户状态是单独记录的。

---

## 6. 获取 App 列表和包信息流程

你理解的“PMS 常用就是获取 App 列表”，确实是最常见入口。

### 6.1 getInstalledPackages 做了什么

```java
List<PackageInfo> packages = pm.getInstalledPackages(flags);
```

大致链路：

```text
App
  ApplicationPackageManager.getInstalledPackages()
        │ Binder
        ▼
system_server
  PackageManagerService.getInstalledPackages()
        │
        ├─ 获取调用方 uid/userId
        ├─ 读取当前包快照 Computer
        ├─ 遍历 PackageSetting / PackageState
        ├─ 按 userId 判断是否 installed/enabled/hidden
        ├─ 按 AppsFilter 判断调用方是否能看见目标包
        ├─ 按 flags 决定返回 activities/permissions/signatures 等信息
        └─ 返回 ParceledListSlice<PackageInfo>
```

### 6.2 为什么有些 App 查不到其他应用

Android 11 开始引入包可见性限制。普通 App 不能随便枚举所有安装包。

常见表现：

- `getInstalledPackages()` 返回数量明显变少。
- `queryIntentActivities()` 查不到目标应用。
- 明明设备上安装了某 App，但代码里 `getPackageInfo()` 抛 `NameNotFoundException`。

常见解决方式：在 manifest 里声明 `queries`：

```xml
<queries>
    <package android:name="com.example.target" />
    <intent>
        <action android:name="android.intent.action.VIEW" />
        <data android:scheme="https" />
    </intent>
</queries>
```

或者在特殊场景申请 `QUERY_ALL_PACKAGES`，但这个权限在普通上架应用里限制很严。

### 6.3 flags 为什么重要

查询包信息时，flags 决定 PMS 返回多少内容：

```java
PackageInfo info = pm.getPackageInfo(
        "com.example",
        PackageManager.GET_ACTIVITIES
                | PackageManager.GET_SERVICES
                | PackageManager.GET_PERMISSIONS);
```

返回越多，PMS 需要组装的数据越多，跨进程传输也越大。不要在频繁路径里无脑带所有 flag，尤其是获取全量 App 列表时。

---

## 7. Intent 解析和组件查询流程

AMS 启动 Activity 前，经常要问 PMS：这个 Intent 到底匹配谁？

### 7.1 显式 Intent

```java
Intent intent = new Intent();
intent.setComponent(new ComponentName("com.example", "com.example.MainActivity"));
```

显式 Intent 已经指定 package/class，PMS 主要检查：

- 包是否存在。
- 组件是否存在。
- 组件是否对当前 user 安装且启用。
- 调用方是否有权限访问。
- exported 是否允许外部调用。

### 7.2 隐式 Intent

```java
Intent intent = new Intent(Intent.ACTION_VIEW);
intent.setData(Uri.parse("https://example.com"));
```

隐式 Intent 要通过 intent-filter 匹配：

```text
Intent(action/category/data/type)
        │
        ▼
ComponentResolver / IntentResolver
        │
        ├─ 匹配 Activity intent-filter
        ├─ 匹配 Service intent-filter
        ├─ 匹配 Receiver intent-filter
        └─ 根据 priority、默认浏览器、用户选择等排序
```

### 7.3 Launcher 图标是怎么查出来的

Launcher 常见查询：

```java
Intent intent = new Intent(Intent.ACTION_MAIN);
intent.addCategory(Intent.CATEGORY_LAUNCHER);
List<ResolveInfo> list = pm.queryIntentActivities(intent, 0);
```

PMS 会返回所有声明了入口 Activity 的应用。Launcher 再结合用户、profile、应用隐藏/禁用状态、LauncherApps API 展示图标。

如果图标不显示，常见原因：

- 没有 `MAIN` + `LAUNCHER`。
- Activity 被 disabled。
- 应用对当前 user 未安装。
- 应用处于 hidden/suspended。
- Launcher 有自己的过滤策略。

---

## 8. APK 安装流程

安装是 PMS 最重要、也最容易出问题的流程。

### 8.1 从 adb install 看整体链路

```bash
adb install app.apk
```

大致链路：

```text
adb install
  └─ adb push / streaming APK
       └─ package installer session
            └─ PackageInstallerService
                 └─ PackageManagerService
                      ├─ 创建安装参数
                      ├─ 解析 APK
                      ├─ 校验包名、版本、签名、权限
                      ├─ 拷贝/移动 APK 到 /data/app
                      ├─ 调用 installd 创建数据目录
                      ├─ dexopt / 生成 oat/vdex
                      ├─ 更新 packages.xml 和内存状态
                      ├─ 通知 AMS 处理进程和组件变化
                      └─ 发送 PACKAGE_ADDED / PACKAGE_REPLACED 广播
```

### 8.2 PackageInstaller 安装模型

现代 Android 推荐安装方式是 session：

```text
PackageInstaller.createSession(params)
  └─ openSession(sessionId)
       └─ write APK bytes
            └─ fsync
                 └─ commit(intentSender)
                      └─ PMS 执行安装
```

这种模型支持：

- 大 APK 分块写入。
- split APK 安装。
- 安装前确认。
- 安装结果异步回调。
- staged install / rollback 等高级能力。

### 8.3 PMS 安装时主要校验什么

| 校验项 | 说明 | 常见失败 |
|---|---|---|
| APK 格式 | zip 结构、Manifest、资源是否可解析 | `INSTALL_PARSE_FAILED_*` |
| 包名 | packageName 是否合法、是否和已有包冲突 | `INSTALL_FAILED_INVALID_APK` |
| 版本 | 是否允许升级/降级 | `INSTALL_FAILED_VERSION_DOWNGRADE` |
| 签名 | 更新安装必须和旧版本签名兼容 | `INSTALL_FAILED_UPDATE_INCOMPATIBLE` |
| ABI | native so 是否匹配设备 ABI | `INSTALL_FAILED_NO_MATCHING_ABIS` |
| SDK | minSdk/targetSdk 是否符合 | `INSTALL_FAILED_OLDER_SDK` |
| 权限 | privileged 权限、签名权限是否允许 | 权限不授予或安装失败 |
| 存储空间 | `/data` 空间是否足够 | `INSTALL_FAILED_INSUFFICIENT_STORAGE` |
| sharedUserId | shared uid 签名是否一致 | `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE` |

### 8.4 APK 文件最后放在哪里

普通用户安装应用通常放在：

```text
/data/app/<package-random>/base.apk
/data/app/<package-random>/split_config.xxx.apk
```

系统应用原始 APK 在系统分区：

```text
/system/app/xxx/xxx.apk
/system/priv-app/xxx/xxx.apk
/product/app/xxx/xxx.apk
/vendor/app/xxx/xxx.apk
```

如果系统应用被用户更新，新版本通常会放到 `/data/app`，PMS 会用 data 分区的新版本覆盖系统分区的旧版本逻辑；卸载更新时可以回退到系统分区版本。

### 8.5 安装完成后发什么广播

常见广播：

```text
android.intent.action.PACKAGE_ADDED
android.intent.action.PACKAGE_REPLACED
android.intent.action.MY_PACKAGE_REPLACED
```

注意：Android 版本越新，对静态广播、后台启动、包可见性限制越多。不要假设所有应用都能收到所有包变化广播。

---

## 9. APK 卸载流程

卸载不是简单删除 APK 文件。PMS 要同时处理包状态、用户状态、数据目录、权限、组件和广播。

### 9.1 adb uninstall 整体链路

```bash
adb shell pm uninstall com.example
```

大致链路：

```text
pm uninstall
  └─ PackageInstaller / IPackageInstaller
       └─ PackageManagerService.deletePackageAsUser()
            ├─ 校验调用方是否有卸载权限
            ├─ 判断是否系统应用、设备管理器、profile owner、device owner
            ├─ 判断是全用户卸载还是单用户卸载
            ├─ 停止相关进程和组件
            ├─ 删除或隐藏 package 状态
            ├─ 调用 installd 删除数据目录和 dex 文件
            ├─ 更新 packages.xml / package-restrictions.xml
            └─ 发送 PACKAGE_REMOVED / PACKAGE_FULLY_REMOVED 广播
```

### 9.2 单用户卸载和全量卸载

Android 是多用户系统。卸载要区分：

```bash
adb shell pm uninstall --user 0 com.example
```

这通常只是对 user 0 标记为未安装，不一定删除 `/data/app` 的 APK 文件，因为其他 user 可能还在使用。

全用户都卸载后，PMS 才能真正删除代码路径。

### 9.3 卸载系统应用为什么不一样

系统应用在只读系统分区，普通卸载不能真正删除 `/system/app` 或 `/system/priv-app` 的 APK。

常见情况：

- `pm uninstall --user 0 packageName`：对当前用户卸载，系统 APK 仍在系统分区。
- Settings 里“卸载更新”：删除 `/data/app` 中的更新版本，回退到系统分区版本。
- root 或重新打包系统镜像：才可能真正移除系统分区 APK。

### 9.4 卸载时数据怎么处理

默认卸载会删除应用数据：

```text
/data/user/<userId>/<package>
/data/data/<package> -> /data/user/0/<package>
/data/user_de/<userId>/<package>
/data/misc/profiles/cur/<userId>/<package>
```

如果使用保留数据选项：

```bash
adb shell pm uninstall -k com.example
```

代码会卸载，但数据可能保留，后续重新安装同包名应用时可能复用旧数据。实际效果还受 Android 版本、签名、用户和策略影响。

### 9.5 卸载失败常见原因

- 应用是 device owner / profile owner。
- 应用是 active device admin。
- 系统应用不允许全量删除。
- 调用方没有卸载权限。
- 多用户下其他用户仍安装。
- 包名不存在或当前 user 下未安装。

---

## 10. 更新、降级和替换安装

安装一个已存在包时，PMS 会走替换安装。

### 10.1 更新安装

```bash
adb install -r app.apk
```

PMS 重点检查：

- 包名必须一致。
- 签名必须和旧版本兼容。
- versionCode 通常必须更高或相等。
- sharedUserId 必须兼容。
- 不能丢失某些系统要求的属性。

成功后会保留应用数据，并发送 `PACKAGE_REPLACED` 和目标应用自己的 `MY_PACKAGE_REPLACED`。

### 10.2 降级安装

```bash
adb install -r -d app.apk
```

降级默认不允许，需要调试场景或特定权限。常见失败：

```text
INSTALL_FAILED_VERSION_DOWNGRADE
```

系统这样设计是为了避免旧版本代码读写新版本数据结构，造成数据损坏或安全回退。

### 10.3 签名不一致

最常见安装失败之一：

```text
INSTALL_FAILED_UPDATE_INCOMPATIBLE
```

原因：设备上已有同包名应用，但新 APK 签名不兼容。解决方式通常是卸载旧包后再装，或使用同一签名重新打包。系统应用还要考虑 platform 签名、sharedUserId、privileged 权限等因素。

---

## 11. 权限管理流程

PMS 和权限系统关系非常紧密。新版本中权限核心逻辑更多在 `PermissionManagerService`，但包声明、安装状态、签名仍由 PMS 提供。

### 11.1 权限从哪里来

App 在 Manifest 里声明：

```xml
<uses-permission android:name="android.permission.CAMERA" />
```

PMS 扫描 APK 时会记录 requested permissions。安装后权限是否真的授予，要看权限类型。

### 11.2 权限类型

| 类型 | 特点 | 例子 |
|---|---|---|
| normal | 安装时自动授予，风险低 | 网络、震动等 |
| dangerous | 运行时权限，需要用户授权 | 相机、位置、麦克风 |
| signature | 只有和定义权限的应用签名一致才授予 | 系统或厂商内部权限 |
| privileged | 只有 priv-app 且在白名单中才可能授予 | 系统特权权限 |
| development | 调试或开发用途，可通过 adb grant | 部分调试权限 |

### 11.3 运行时权限

App 调用：

```java
requestPermissions(new String[] { Manifest.permission.CAMERA }, 100);
```

背后流程大致是：

```text
App 请求权限
  └─ PermissionController 展示授权 UI
       └─ 用户选择允许/拒绝
            └─ PermissionManagerService 更新授权状态
                 └─ 写入 runtime-permissions.xml
```

PMS 查询包信息时可以返回权限声明和授权状态；AMS 启动组件或系统服务执行敏感操作时，也会检查调用方权限。

### 11.4 privileged 权限白名单

系统特权应用不是只要放进 `/system/priv-app` 就能拿所有权限。Android 8.0 之后要求 privileged permission whitelist。

常见白名单文件：

```text
/system/etc/permissions/privapp-permissions-*.xml
/product/etc/permissions/privapp-permissions-*.xml
/vendor/etc/permissions/privapp-permissions-*.xml
```

如果 priv-app 申请了不在白名单里的 privileged 权限，可能安装或开机扫描失败，也可能权限不被授予，具体取决于系统版本和配置。

---

## 12. 签名、sharedUserId 与系统应用

签名是 Android 包管理的安全基础。

### 12.1 签名用来做什么

PMS 会用签名判断：

- 同包名更新是否允许。
- signature 权限是否授予。
- sharedUserId 是否允许共享 uid。
- 系统应用更新是否可信。
- 两个包是否签名一致。

### 12.2 sharedUserId

早期系统应用常见：

```xml
<manifest android:sharedUserId="android.uid.system">
```

这意味着应用希望和 system uid 共享 Linux uid。要求非常严格：必须使用匹配签名，且新版本 Android 已经不鼓励新应用使用 sharedUserId。

常见失败：

```text
INSTALL_FAILED_SHARED_USER_INCOMPATIBLE
```

### 12.3 系统应用和特权应用

| 类型 | 位置 | 特点 |
|---|---|---|
| 普通系统应用 | `/system/app` 等 | 随系统预装，但不是 priv-app |
| 特权系统应用 | `/system/priv-app` 等 | 可申请 privileged 权限，但需要白名单 |
| platform 签名应用 | 使用平台证书签名 | 可获得 signature 权限，可使用部分内部能力 |
| 用户更新系统应用 | 新 APK 在 `/data/app` | 覆盖系统分区版本，卸载更新可回退 |

---

## 13. 应用数据目录和 dex/oat 优化

PMS 安装包时，不只是记录 APK，还要准备运行环境。

### 13.1 数据目录

典型目录：

```text
/data/user/0/com.example
/data/data/com.example -> /data/user/0/com.example
/data/user_de/0/com.example
```

`/data/user_de` 是 device encrypted 存储，用户未解锁时也可能访问；`/data/user` 是 credential encrypted 存储，通常用户解锁后才可访问。

PMS 通过 `installd` 创建、删除、迁移这些目录，并设置 uid/gid、SELinux context。

### 13.2 dexopt

安装或首次运行时，系统可能对 dex 做优化：

```text
APK dex
  └─ dex2oat / ART 编译
       └─ oat / vdex / art profile
```

这会影响：

- 安装耗时。
- 首次启动速度。
- 后台编译任务。
- OTA 后应用优化时间。

所以有时你会看到“安装已经结束但首次启动仍慢”，这可能和 dexopt、profile、资源加载、Application 初始化共同相关。

---

## 14. 常见问题与排查方法

### 14.1 查不到已安装应用

可能原因：

- Android 11+ 包可见性限制。
- 查询的是错误 user。
- 应用被 hidden、suspended、disabled。
- 调用时没有带合适 flags。
- 目标包是 instant app 或特殊安装状态。

排查：

```bash
adb shell pm list packages --user 0
adb shell pm list packages -d
adb shell dumpsys package com.example
adb shell dumpsys package queries
```

### 14.2 安装失败

常见错误：

| 错误 | 可能原因 |
|---|---|
| `INSTALL_FAILED_VERSION_DOWNGRADE` | versionCode 降级 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 同包名签名不一致 |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | native so 不支持设备 ABI |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` | 存储空间不足 |
| `INSTALL_PARSE_FAILED_MANIFEST_MALFORMED` | Manifest 格式或属性错误 |
| `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE` | sharedUserId 签名不兼容 |
| `INSTALL_FAILED_DUPLICATE_PACKAGE` | 包冲突或残留状态异常 |

排查：

```bash
adb install -r app.apk
adb shell pm install-existing --user 0 com.example
adb shell dumpsys package com.example
adb logcat -b all | grep -i "PackageManager\|PackageInstaller\|installd"
```

### 14.3 卸载失败

可能原因：

- 系统应用只能对当前用户卸载，不能删除系统分区 APK。
- 应用是 device owner/profile owner。
- 应用是 active device admin。
- 调用方没有卸载权限。
- 多用户下仍有用户安装。

排查：

```bash
adb shell pm uninstall --user 0 com.example
adb shell dpm remove-active-admin com.example/.AdminReceiver
adb shell dumpsys device_policy
adb shell dumpsys package com.example
```

### 14.4 权限没有授予

可能原因：

- dangerous 权限需要运行时授权。
- signature 权限签名不匹配。
- privileged 权限没有白名单。
- 权限被 policy fixed 或 user fixed。
- AppOps 仍然拦截。

排查：

```bash
adb shell dumpsys package com.example | grep -i permission
adb shell pm grant com.example android.permission.CAMERA
adb shell appops get com.example
adb shell dumpsys permission
```

### 14.5 Launcher 图标不显示

可能原因：

- 没有 `ACTION_MAIN` + `CATEGORY_LAUNCHER`。
- 入口 Activity disabled。
- App 对当前 user 未安装。
- Launcher 缓存未刷新。
- 应用 suspended/hidden。

排查：

```bash
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER
adb shell pm list packages --user 0
adb shell dumpsys package com.example
```

---

## 15. 第三方系统常见修改点

### 15.1 预装应用和可卸载策略

需求：系统预装 App，但允许用户卸载或不允许卸载。

常见修改：

- 调整预装目录：`/system/app`、`/system/priv-app`、`/product/app`。
- 修改卸载策略，只允许 `--user` 卸载。
- 首次开机按配置安装到特定 user。
- 配置默认 enabled/disabled 状态。

风险：预装包和 `/data/app` 更新包的优先级、签名、版本不一致，会导致开机扫描异常或更新失败。

### 15.2 静默安装和卸载

需求：设备管理、车机、行业终端需要后台安装/卸载 APK。

常见修改：

- 给系统签名应用开放安装权限。
- 定制 PackageInstaller 确认 UI。
- 增加白名单允许静默安装。
- 通过设备管理策略安装应用。

风险：静默安装是高危能力，必须限制调用方身份、签名、来源路径和可安装包范围。否则很容易变成任意安装后门。

### 15.3 privileged 权限白名单

需求：预装系统应用需要访问系统级权限。

常见修改：

- 在 `privapp-permissions-*.xml` 中声明允许权限。
- 调整应用所在分区，确保权限白名单分区匹配。
- 使用 platform 签名。

风险：权限白名单过宽会破坏 Android 权限边界，也可能导致 CTS/GTS 失败。

### 15.4 包可见性放宽

需求：Launcher、安全软件、应用商店、车机管控应用需要看到所有应用。

常见修改：

- 授予 `QUERY_ALL_PACKAGES`。
- 在 `AppsFilter` 或系统策略中加入白名单。
- 使用系统 API 或 LauncherApps 查询。

风险：过度放宽会影响隐私。普通应用不应该随意枚举全量安装列表。

### 15.5 安装来源和签名策略

需求：只允许安装厂商签名应用，或只允许指定商店安装。

常见修改：

- 校验 installer package。
- 校验 APK 签名证书。
- 校验安装路径或下载来源。
- 增加企业白名单/黑名单。

风险：只在 UI 层拦截不可靠，真正策略要落在 PMS/PackageInstallerService 安装提交路径。

### 15.6 多用户安装策略

需求：车机主用户、副用户、访客用户安装不同应用。

常见修改：

- 首次开机按 user 安装/隐藏应用。
- `install-existing --user` 策略。
- 不同 user 下不同 enabled/disabled 状态。
- 用户切换时刷新 Launcher 和权限状态。

风险：只看 APK 是否存在不够，必须看 package-restrictions 中当前 user 的 installed/hidden/enabled 状态。

### 15.7 修改 PMS 的基本原则

- 安装、卸载、权限属于安全边界，不能随意放开。
- 策略要落在服务端，不要只改 Settings 或安装 UI。
- 白名单要基于签名、uid、role、设备管理身份，不要只靠包名。
- 修改后要验证多用户、系统应用更新、卸载更新、OTA、CTS/GTS。
- 所有静默安装、权限放行、包可见性例外都要有日志和 dumpsys 可查。

---

## 16. 读源码的推荐路线

### 16.1 获取应用列表

```text
ApplicationPackageManager.getInstalledPackages
IPackageManager.getInstalledPackages
PackageManagerService.getInstalledPackages
Computer / PackageStateInternal
AppsFilter
PackageInfo 组装返回
```

### 16.2 查询 Intent 能否打开

```text
PackageManager.resolveActivity / queryIntentActivities
ApplicationPackageManager
PackageManagerService
ComponentResolver
IntentResolver
ResolveInfo 返回
```

### 16.3 安装 APK

```text
PackageInstaller.Session.commit
PackageInstallerService
PackageManagerService
InstallPackageHelper / installPackageTracedLI
解析 APK / 校验签名 / 校验版本
拷贝到 /data/app
installd 创建数据目录和 dexopt
更新 Settings / packages.xml
发送 PACKAGE_ADDED / PACKAGE_REPLACED
```

### 16.4 卸载 APK

```text
PackageInstaller.uninstall / pm uninstall
PackageManagerService.deletePackageAsUser
DeletePackageHelper
校验卸载权限和系统应用状态
停止进程 / 移除组件状态
installd 删除数据和 oat
更新 Settings / package-restrictions.xml
发送 PACKAGE_REMOVED / PACKAGE_FULLY_REMOVED
```

### 16.5 权限授权

```text
PackageManager.requestPermissions 相关入口
PermissionController
PermissionManagerService
PackageManagerService 查询包声明
runtime-permissions.xml
AppOps 进一步控制
```

---

## 17. 关键源码路径速查

| 内容 | 路径 |
|---|---|
| PMS 主服务 | `frameworks/base/services/core/java/com/android/server/pm/PackageManagerService.java` |
| PMS Binder 接口 | `frameworks/base/core/java/android/content/pm/IPackageManager.aidl` |
| App 侧 PackageManager | `frameworks/base/core/java/android/content/pm/PackageManager.java` |
| App 侧实现 | `frameworks/base/core/java/android/app/ApplicationPackageManager.java` |
| 安装 API | `frameworks/base/core/java/android/content/pm/PackageInstaller.java` |
| 安装服务 | `frameworks/base/services/core/java/com/android/server/pm/PackageInstallerService.java` |
| 安装流程辅助类 | `frameworks/base/services/core/java/com/android/server/pm/InstallPackageHelper.java` |
| 卸载流程辅助类 | `frameworks/base/services/core/java/com/android/server/pm/DeletePackageHelper.java` |
| 包状态持久化 | `frameworks/base/services/core/java/com/android/server/pm/Settings.java` |
| 包状态 | `frameworks/base/services/core/java/com/android/server/pm/PackageSetting.java` |
| 组件解析 | `frameworks/base/services/core/java/com/android/server/pm/resolution/ComponentResolver.java` |
| 包可见性过滤 | `frameworks/base/services/core/java/com/android/server/pm/AppsFilter*.java` |
| 权限服务 | `frameworks/base/services/core/java/com/android/server/pm/permission/PermissionManagerService.java` |
| APK 解析 | `frameworks/base/core/java/android/content/pm/parsing/` |
| installd AIDL | `frameworks/native/cmds/installd/binder/android/os/IInstalld.aidl` |
| installd 实现 | `frameworks/native/cmds/installd/` |
| dex2oat | `art/dex2oat/` |

---

## 18. 一图总结

```text
App / adb / Settings / Launcher
  ├─ 获取应用列表
  ├─ 查询 Intent
  ├─ 安装 APK
  ├─ 卸载 APK
  ├─ 授权权限
  └─ 启用/禁用应用
        │
        ▼ Binder
system_server
  ┌────────────────────────────────────┐
  │ PackageManagerService              │
  │                                    │
  │ 扫描 APK 目录                      │
  │ 解析 Manifest                      │
  │ 维护包状态和用户状态               │
  │ 校验签名、版本、权限               │
  │ 提供 PackageInfo / ResolveInfo     │
  │ 调度安装、卸载、更新               │
  └──────────────┬─────────────────────┘
                 │
                 ├─ PermissionManagerService：权限授权
                 ├─ ComponentResolver：组件和 Intent 匹配
                 ├─ AppsFilter：包可见性过滤
                 ├─ Settings：packages.xml 等持久化
                 ├─ AMS/ATMS：启动组件前查询 PMS
                 └─ installd：数据目录、dexopt、清理
```

---

## 小结

- **PMS 是 Android 的包管理中心**，负责认识系统里有哪些包、每个包声明了什么、能否安装更新、能否卸载、能否被查询和启动。
- **获取 App 列表不是简单遍历 APK 文件**，还要经过 user 状态、包可见性、enabled/disabled、hidden/suspended、flags 过滤。
- **安装流程核心是：创建 session → 写入 APK → 解析 Manifest → 校验签名/版本/权限/ABI → 放入 `/data/app` → 创建数据目录 → dexopt → 更新状态 → 发广播**。
- **卸载流程核心是：校验权限和系统应用状态 → 区分单用户/全用户 → 停止组件和进程 → 删除代码或用户状态 → 清理数据 → 更新持久化 → 发广播**。
- **PMS 和 AMS 强绑定**：AMS 启动 Activity/Service/Broadcast 前，经常要向 PMS 查询组件、权限、exported、user 状态。
- **权限和签名是 PMS 的安全核心**，系统应用、priv-app、signature permission、sharedUserId 都离不开签名和白名单。
- **第三方系统改 PMS 时要格外谨慎**，静默安装、权限放行、包可见性、卸载保护都属于安全边界，必须限制调用方并保留可排查日志。

如果只记一个核心模型：

> App 问 PMS“系统里有什么、我能不能用”；安装器问 PMS“这个 APK 能不能进系统”；AMS 问 PMS“这个组件能不能启动”；PMS 根据包状态、Manifest、签名、权限和用户状态给出答案。