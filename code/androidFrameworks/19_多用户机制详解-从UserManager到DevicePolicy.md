# 多用户机制详解：从 UserManager 到 DevicePolicy

> Android 从 4.2 起就是一台**多用户 Linux**——同一个 kernel、同一套 `system_server`，但上面跑着若干个彼此隔离的"身份"。车机里副驾屏登录后排账户、主驾切驾驶员档案、Enterprise 工作资料，根子都在 `UserManagerService` + `ActivityManager` 的用户状态机 + `DevicePolicyManagerService` 这一套。
>
> 本篇聚焦**用户模型与生命周期管理本身**：一个设备能有几个身份、身份怎么创建/销毁、组件/数据/服务怎么按身份隔离。不包含：
> - 多用户的**存储隔离**：见 `../androidOthers/Android存储机制详解-从分区到ScopedStorage与多用户.md` 第 15 章；
> - 屏与 zone 的绑定（Display ↔ OccupantZone）：见 `23_多屏与多窗口机制详解-从DisplayManager到CarOccupantZone.md` 第 11 节；
> - SystemUI 的 per-user 行为：见 `18_SystemUI机制详解-从状态栏到锁屏与通知面板.md`。

---

## 1. 开场：多用户要回答的三个问题与 userId 全景图

### 1.1 三个问题

把"多用户"想成**一栋楼里好几户人家**（类比）：电表、水管是整栋楼共用的（kernel、Binder、system_server），但每户有自己独立的门锁、家具、信箱。Android 多用户要回答三件事：

| 问题 | 一句话 | 由谁负责 |
|---|---|---|
| ① 一个设备能有几个"身份" | userId 的编号空间与类型 | `UserManagerService` 分配/回收 |
| ② 身份怎么创建、销毁、切换 | 生命周期状态机 | `ActivityManager`(`UserController`) + `UserManagerService` |
| ③ 组件/数据/服务怎么按身份隔离 | uid 编码 userId、目录按 user 切分、广播按 user 过滤 | PMS / AMS / `UserManager` |

记住：**几乎所有"App 装了却看不到""广播收不到""数据查空""权限灰掉"的诡异现象，先问一句"在哪个 user 下"**，八成能定位。

### 1.2 userId 全景图

userId 是 `int`，在 `android.os.UserHandle` 里有一组特殊常量，普通用户从 `10` 起分配（`0` 固定给 system user，`10` 起是 secondary/profile）：

```text
  USER_NULL   = -10000   // 非法/未指定用户，很多 API 的"无用户"哨兵
  USER_ALL    = -1        // 广播/回调要投递给"所有用户"
  USER_CURRENT = -2       // 当前前台用户（动态解析）
  USER_CURRENT_OR_SELF = -3
  USER_SYSTEM = 0         // system user（设备拥有者、常驻）
 ───────────────────────────────────────────────
  10, 11, 12 ...          // full / secondary user（手机的次要用户、车机的副驾/后排账户）
  10+ 系列里也可能夹着 profile：
  例如 10 = 主用户，11 = 10 的 managed profile(work)，
       12 = clone profile，13 = private space ……
```

uid 把 userId 和 appId 编码进同一整数，公式（见 `Process`/`UserHandle`）：

```text
  uid = userId * 100000 + appId
  其中 appId ∈ [10000, 19999] 是普通 App，[1000,9999] 是共享 UID/系统 App
```

例：同一个包 `com.x` 在 user 0 下 uid = `0*100000 + 10001 = 10001`，在 user 10 下 uid = `10*100000 + 10001 = 1010001`。**进程、文件属主、Binder 调用方身份全靠这个 uid 区分**——这就是隔离的物理基础。

### 1.3 怎么读这篇

- 想先建立模型：直接看第 2 章（用户类型全表）和第 4 章（状态机）。
- 做存储/权限排查：第 6 章（FBE 与锁）、第 7 章（跨用户通信）。
- 做车机账户/Zone：第 9–11 章（Headless System User、OccupantZone 绑定、切驾驶员链路）。
- 排障直接跳第 14 章排查表、第 13 章调试工具箱。
- 读源码对照第 15、16 章路径表与路线。

「**Android 视角**」：手机出厂默认只有 user 0，多用户能力默认关闭（除非 `config_multiuserMaximumUsers > 1` 或设备声明 `android.hardware.telephony` 等触发多用户场景）；车机/平板/Enterprise 才是多用户的主战场。

---

## 2. 用户类型全表

`UserManager` 用 `UserTypeDetails` 描述每种用户类型（框架内常量 `UserManager.USER_TYPE_*`）。下面是真实存在的集合与用途：

| 类型常量 | 中文 | 编号起点 | 是否能前台 | 典型用途 | 主要限制 |
|---|---|---|---|---|---|
| `USER_TYPE_FULL_SYSTEM` | 系统用户 | 固定 `0` | 视设备而定（车机 Android 15 起多为 headless，无 UI） | 持有系统服务、device owner、全局状态 | 不能删除；早期同时是前台用户 |
| `USER_TYPE_FULL_SECONDARY` | 次要/普通用户 | `10+` | 能 | 手机的"添加用户"、车机副驾/后排账户 | 不能成为 device owner；受创建者限制 |
| `USER_TYPE_FULL_GUEST` | 访客用户 | `10+` | 能 | 临时借用，退出即清数据 | 退出后数据全清；不可装持久 App |
| `USER_TYPE_FULL_DEMO` | 演示用户 | `10+` | 能 | 零售展示机 | 预置演示内容，受限 |
| `USER_TYPE_PROFILE_MANAGED` | 受管资料 / 工作资料 | 父用户同号段 | 不能单独前台（叠加在父用户上） | Enterprise 工作资料（work profile） | 受 profile owner policy 管控；parent/child 关系 |
| `USER_TYPE_PROFILE_CLONE` | 克隆资料 | `10+` | 不能单独前台 | Android 12+，把 App 克隆一份独立数据 | 仅数据隔离，受父用户约束 |
| `USER_TYPE_PROFILE_PRIVATE` | 私密空间 | `10+` | 不能单独前台 | **Android 15+**，私密 App/数据，锁屏密码二次保护 | 解锁后才可见；默认隐藏 |
| `USER_TYPE_PROFILE_RESTRICTED` | 受限资料 | （历史） | 不能单独前台 | 早期平板儿童模式，**已废弃**（Android 12 起移除） | 受父用户限制白名单约束 |

### 2.1 关键区分：full vs profile

- **full user**（系统/次要/访客/演示）：有自己完整的用户目录、可成为前台用户、独立 login。`profile` 没有独立 login 流程，它是**挂在某个 full user（parent）之下**的"子集"，前台始终是 parent。
- **profile 的 parent/child**：`UserInfo.profileGroupId` 指向 parent 的 userId；一个 parent 可挂多个 profile（work、clone、private）。切 parent 前台时 profile 跟着"亮起来"（unlocked），但 `am get-current-user` 看到的仍是 parent 的 userId。

### 2.2 车机相关：driver / passenger 不是"用户类型"

「**Android 视角**」重要澄清：**Android 没有内置 "driver user" / "passenger user" 这种类型**。车机里"主驾账户""副驾账户"是 AAOS 在 `secondary user` 之上的**语义层**——由 `CarUserService` + `OccupantZone` 负责把"座位"映射到某个 `secondary user` 的 userId。所以你看到的后排账户，本质就是一个 `USER_TYPE_FULL_SECONDARY`，只是被 `CarOccupantZoneManager` 绑定到了某块屏/某个座位（见第 10、11 章与 `23_多屏与多窗口机制详解` 第 11 节）。

### 2.3 版本演进提示

- **Android 12**：移除 `RESTRICTED` profile；引入 `CLONE` profile。
- **Android 15**：引入 `PRIVATE` profile；**AAOS 把 system user 推进 headless**（见第 9 章），这是车机多用户最大的架构变化。
- **Android 15 automotive**：`Headless System User` 成为推荐/强制形态，`user 0` 不再持有显示与 Launcher。

---

## 3. UserManagerService：核心数据结构

`UserManagerService`（简称 UMS，位于 `frameworks/base/services/core/java/com/android/server/pm/UserManagerService.java`）是用户目录的"账房先生"。它**不是**决定组件能否启动的服务（那是 AMS），它管的是"有哪些用户、各自什么属性、目录在哪、被什么限制"。

### 3.1 `UserInfo` 的核心字段

`UserInfo`（`android.content.pm.UserInfo`，数据体在 `frameworks/base/core/java/android/content/pm/UserInfo.java`）关键字段：

```java
public class UserInfo {
    public int id;                 // userId，全局唯一，删除后可能被复用
    public String name;            // 显示名（"主驾""后排乘客"…）
    public String iconPath;        // 头像路径
    public @UserIdInt int id;      // 同 id（新代码用 @UserIdInt 标注）
    public int flags;              // 见 3.2 的标志位
    public long serialNumber;      // 用户序号，即使 id 复用也递增，用于 CE 密钥
    public int userType;           // 见第 2 章类型
    public long creationTime;
    public long lastLoggedInTime;
    public String lastLoggedInFingerprint;
    public boolean partial;        // 创建中途（还没初始化完），partial 用户不可见/不可用
    public boolean preCreated;     // Android 12+ 预创建（加速首次登录）
    public boolean isEnabled();    // 是否启用
    public boolean isManagedProfile();
    public boolean isGuest();
    public boolean isProfile();
}
```

### 3.2 flags 标志位（节选真实位）

`flags` 是位或，常用位（`UserInfo` 常量）：

| flag | 含义 |
|---|---|
| `FLAG_PRIMARY` | 设备的主用户（早期 = system user，多用户设备里是第一个 secondary） |
| `FLAG_ADMIN` | 管理员，可创建/删用户 |
| `FLAG_GUEST` | 访客 |
| `FLAG_RESTRICTED` | 受限（已废弃类型） |
| `FLAG_DISABLED` | 已禁用 |
| `FLAG_PROFILE` | 这是个 profile |
| `FLAG_MANAGED_PROFILE` | 受管资料 |
| `FLAG_SYSTEM` | system user |
| `FLAG_EPHEMERAL` | 临时用户，退出即删 |
| `FLAG_INITIALIZED` | 已收到过 `ACTION_USER_INITIALIZE`，不可重复初始化 |
| `FLAG_QUIET_MODE` | profile 处于"安静模式"（work 资料关掉，图标变灰） |
| `FLAG_DEMO` | 演示用户 |
| `FLAG_FULL` | full user |
| `FLAG_HEADLESS` | **headless system user**（Android 15 automotive，user 0 无 UI） |
| `FLAG_NO_RUNNING_SINGLE_USER` | 不允许单独作为唯一 running 用户 |

### 3.3 userId 的分配与回收

- **system user 永远是 0**，由 `system_server` 启动早期直接 `startUser(0, ...)`。
- 新用户编号来自 UMS 内部计数器 `mNextSerialNumber` / `mUserIds` 列表；从 `10` 起递增，**跳过已占用**。
- 删除用户后其 `id` **可能被后续创建复用**——所以持久化逻辑不要拿 `userId` 当稳定主键，用 `serialNumber` 或 `UserHandle` 语义。
- 上限：`UserManager.getMaxSupportedUsers()` 决定，受 `config_multiuserMaximumUsers`（见第 12 章）与设备形态约束。
- **`serialNumber` 才是稳定主键**：`id` 会复用，`serialNumber` 单调递增且绑定 CE 密钥槽。任何需要"这个用户即便删了重建也不是同一个"的持久化（如车机账户档案），用 `serialNumber` 而非 `id`。
- **`preCreated` 用户（Android 12+）**：`createUser` 时可带 `USER_CREATE_POC`/预创建标志，先建好目录与 uid 但暂不登录，首次人脸/刷卡识别到时直接 `switchUser` 到它，省去"识别→建用户→初始化"的冷启动延迟。车机首次登录主驾账户慢，多半就是没用 preCreated。

### 3.4 用户目录：两个根

每个用户两套数据目录，隔离的落地点（详细见 `../androidOthers/Android存储机制详解-从分区到ScopedStorage与多用户.md` 第 15 章，这里只点名）：

```text
/data/user_de/<userId>/<package>    ← DE：Device-Encrypted，锁屏前即可用（Direct Boot）
/data/user/<userId>/<package>       ← CE：Credential-Encrypted，用户解锁后才解密可见
/data/system/users/<userId>/        ← 系统侧该用户的运行时状态/限制/设置
```

`/data/user/<id>` 是当前 CE 用户的软链锚点；`/data/user_de/<id>` 在 FBE 设备上一开机就可用。profile 也各自有 `<id>` 目录，挂在 parent 同号段。

---

## 4. 用户生命周期状态机

决定"组件能不能起、广播能不能收、数据能不能读"的不是 UMS 的 `UserInfo`，而是 **`ActivityManager`(`UserController`) 维护的运行态**——`UserState`。

### 4.1 状态全集（`UserState` / `UserManager` 常量）

```text
  STATE_BOOTING        = 0   // 用户正在启动，进程/服务尚未就绪
  STATE_RUNNING_LOCKED = 1   // 已 running，但 CE 未解密（锁屏态，只能碰 DE）
  STATE_RUNNING_UNLOCKING = 2 // 正在解密 CE（解锁进行中）
  STATE_RUNNING_UNLOCKED = 3  // 完全解锁，CE 可用，App 正常跑
  STATE_STOPPING       = 4   // 正在停止用户（停止进程、解绑服务）
  STATE_SHUTDOWN       = 5   // 用户已关闭
```

迁移图（前台用户视角，secondary 类似但无 boot）：

```text
        startUser(0)                     unlock (输入密码/无锁)
  [无] ─────────────► STATE_BOOTING ─────────────────────────► STATE_RUNNING_LOCKED
                                                                       │
                                                          CE 解密完成  │
                                                                       ▼
                                                              STATE_RUNNING_UNLOCKING
                                                                       │
                                                                       ▼
                                                            STATE_RUNNING_UNLOCKED
                                                                       │
                                                          stopUser   │
                                                                       ▼
                                                              STATE_STOPPING ──► STATE_SHUTDOWN
```

- `system user` 在 `system_server` 起来后直接进入 BOOTING→（多数设备）RUNNING_UNLOCKED；**headless 模式下 user 0 没有 UI，不进锁屏流程**。
- profile 跟随 parent：parent 进 UNLOCKED，profile 也进 UNLOCKED（除非 `QUIET_MODE`）。

### 4.2 启动/停止的异步语义

`UserController.startUser()` / `stopUser()` **都是异步的**：它们发消息给 `UserController` 的内部 handler（`android.os.Handler`），真正动作在 `ActivityManager` 的 `UserHandler` 消息循环中跑。因此：

- `startUser` 返回时用户**可能还在 BOOTING**，要等 `STATE_RUNNING_UNLOCKED` 才能确定"App 能起"。
- 监听状态用回调：`UserManager.addUserUnlockListener(...)`（Java），或 shell 侧轮询 `am get-current-user` + `dumpsys user`。
- `stopUser` 同样异步：会先通知各服务停止该 user 的组件，最后才置 `STATE_SHUTDOWN`。强制停止 `stopUser(uid, /* force= */ true)` 会跳过优雅停止。

### 4.3 `switchUser` 的完整链路（概览，详链见第 5、11 章）

```text
  AMS.switchUser(targetId)             ← am switch-user / CarUserService 触发
    └─ UserController.switchUser()
         ├─ 校验 target 已存在且非 disabled
         ├─ 停止当前前台 user 的部分组件（或降级为后台 running）
         ├─ startUser(targetId, foreground=true)
         ├─ WindowManager 切 display 归属（见 23 第 11 节）
         ├─ 发 ACTION_USER_FOREGROUND / ACTION_USER_BACKGROUND
         └─ 回调 ActivityManagerInternal 的锁屏/显示切换
```

切用户的"卡住"多数不是 switch 本身，而是 **target user 还没 UNLOCKED**（CE 没解密，见第 14 章排查表）。

---

## 5. 用户启动流程：per-user 组件的启动与过滤

`system_server` 启动尾段会起 system user，再视配置起其他 user。`ActivityManager` 在用户进入 RUNNING 态后，按 user 过滤规则启动/投递该用户的组件。

### 5.1 system user 先起

`SystemServer` → `ActivityManagerService` 的 `startUser` 把 user 0 拉到 running；随后 `system_server` 内各服务（PMS、AMS、WMS…）都以 user 0 身份初始化全局状态。**注意**：system user 起的很多是"全局单例服务"，它们不属于某个 secondary user 的进程空间。

### 5.2 per-user 组件的过滤规则

同一份 APK，装到多个 user 后，它的组件**按 user 分别运行在各自进程**（因为 uid 不同）。启动/投递时有三条硬规则：

1. **广播按 user 隔离**：`sendBroadcast` 不带 user 限定 → 只投到**调用方所在 user**。`sendBroadcastAsUser(intent, UserHandle.of(target))` 才能跨 user，且需 `INTERACT_ACROSS_USERS` 权限（见第 7 章）。一个 user 没 running，它的 manifest 接收器收不到任何广播。
2. **`ACTION_USER_INITIALIZE`**：当一个新 user 首次启动到 RUNNING 时，系统只向**该用户**发一次 `ACTION_USER_INITIALIZE`（受 `FLAG_INITIALIZED` 保护，不会重发）。这是"首次装默认 App / 初始化设置"的正经钩子，**不要**在 `BOOT_COMPLETED` 里做 per-user 初始化（见 5.4 坑）。
3. **`BOOT_COMPLETED` 是 per-user 的**：每个 user 解锁后各自收一份 `ACTION_BOOT_COMPLETED`（以及锁屏前的 `LOCKED_BOOT_COMPLETED`）。如果某 user 没启动，它就收不到。

> 关于"user all 的 receiver 权限条件"：Android**没有 `RECEIVER_NOT_EXPORTED` 这种跨 user 开关**。`RECEIVER_NOT_EXPORTED` 是 manifest `<receiver android:exported="false">` 的语义，与 user 维度无关。跨 user 广播的门槛是**权限**（`INTERACT_ACROSS_USERS` / `INTERACT_ACROSS_USERS_FULL`），不是 exported 标志。另外系统内部用 `Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND` 之类控制是否投给未启动的用户进程，但那仍是权限 + 状态双重判断。

### 5.3 `pm install --user` 与 per-user 安装

包在 PMS 里的"已安装"状态是**按 user 记账**的（`Settings` 里每个 user 一份 `PackageSetting`）：

```bash
# 把某 APK 只装到 user 10（其他用户看不到、不会起）
adb install --user 10 base.apk
# 已存在于某 user 的包，额外"启用安装"到另一 user（不重新拷 APK）
pm install-existing --user 12 com.example.app
# 只看某个 user 装了什么
pm list packages --user 10
# 卸载某 user 下的包（不影响其他 user）
pm uninstall --user 10 com.example.app
```

要点：
- `pm install` 默认装到**当前/指定 user**；`--user ALL` 或省略时的行为取决于调用方身份。
- `install-existing` 是车机/Enterprise 预装"同一份 APK 多用户共享代码、各自数据隔离"的关键命令。
- 一个包在某个 user 下 `hidden`/`disabled`/`not installed`，Launcher 与 AMS 都不会给它启动机会——这是"装了却看不到"的头号原因。

### 5.4 一个经典坑：per-user 初始化跑多次 vs 只跑一次

- 想"每个 user 首次登录都初始化一次" → 监听 `ACTION_USER_INITIALIZE`（每个 user 一次，受 `FLAG_INITIALIZED` 保证）。
- 想"整台设备只做一次" → 用 `USER_SYSTEM` 下的 `BOOT_COMPLETED` 或 `DevicePolicy`/一次性 flag，**别**在每个 user 的 `BOOT_COMPLETED` 里无脑做。
- 车机里"预装 App 在某个新用户下没起来"：多半是预装只装到了 user 0 / 初始 user，没 `install-existing` 到新登录的 secondary user（排查见第 14 章）。

---

## 6. FBE 与用户的锁：CE/DE 与解锁时序

多用户 + FBE（File-Based Encryption，文件级加密）叠加后，"用户解锁"成了组件可见性的开关。这部分**存储细节**见 `../androidOthers/Android存储机制详解-从分区到ScopedStorage与多用户.md` 第 15 章，这里讲它和用户的咬合点。

### 6.1 两个加密域

| 域 | 路径 | 何时可用 | 典型内容 |
|---|---|---|---|
| DE（Device Encrypted） | `/data/user_de/<id>/` | 开机即可用（未解锁） | 闹钟、核心服务缓存、Direct Boot 必需数据 |
| CE（Credential Encrypted） | `/data/user/<id>/` | 用户**解锁**后解密 | 用户照片、账号、普通 App 数据 |

`Direct Boot`（直接启动）：未解锁时 App 只能访问 DE；声明 `android:defaultToDeviceProtectedStorage="true"` 或显式 `Context.createDeviceProtectedStorageContext()` 才能读 DE。

### 6.2 解锁时序与 `isUserUnlocked`

```text
  开机 → user 0 进 STATE_RUNNING_LOCKED（DE 可用，CE 未解）
       → 用户输入锁屏密码 / 无锁 → CE 解密 → STATE_RUNNING_UNLOCKED
```

- `UserManager.isUserUnlocked(userId)`：返回该 user 的 CE 是否已解密。未解锁时返回 `false`，读 `/data/user/<id>` 下的文件会失败。
- `UserManager.isUserUnlocked()`（无参，当前进程 user）同理。
- profile 跟随 parent：parent UNLOCKED 后 profile 通常也 UNLOCKED（除非 `QUIET_MODE`）。

### 6.3 `wait-for-unlock` 与 `cmd user`

App 侧等解锁：注册 `BroadcastReceiver` 监听 `Intent.ACTION_USER_UNLOCKED`（或 `UserManager.addUserUnlockListener`）。Shell/脚本侧没有单条"等待某 user 解锁"的命令，常用组合：

```bash
# 循环等到 user 10 进入 unlocked（dumpsys user 看 state）
adb shell 'until dumpsys user | grep -q "userId=10.*UNLOCKED"; do sleep 1; done'
# 或：直接看当前用户状态
adb shell dumpsys user | grep -A3 "userId=10"
```

`cmd user` 相关（见第 13 章完整清单）：

```bash
cmd user list                 # 列出所有用户及 state 摘要
cmd user info 10              # 某用户详情（含 running/unlocked 标记）
```

### 6.4 真实坑位

- **"广播收不到"**：发在 `BOOT_COMPLETED` 之前、且 App 只挂了 `BOOT_COMPLETED` 接收器——注意 `LOCKED_BOOT_COMPLETED` 在解锁前就发，此时 CE 不可用，若接收器去读 CE 文件会崩。
- **"闹钟/重要服务在锁屏前就要跑"**：必须放在 DE 域或用 `android:directBootAware="true"`，否则未解锁前起不来（车机开机即服务场景尤其重要）。

---

## 7. 用户间通信与跨 user 限制

不同 user 的进程 uid 不同（第 1.2 节），因此**默认彼此是"不同应用"**——Binder 调用、ContentProvider 查询、广播都受跨用户边界约束。

### 7.1 Binder 跨用户权限分级

调别 user 的组件，AMS 会查 `CHECK_INTERACT_ACROSS_USERS`：

| 权限 | 保护级别（真实） | 能做什么 |
|---|---|---|
| `INTERACT_ACROSS_USERS` | `signature|privileged` 或 `system` | 跨 user 启动/查询/发广播（有限） |
| `INTERACT_ACROSS_USERS_FULL` | `signature|privileged` | 完全跨 user（含停止其他用户进程等高危操作） |

「**Android 视角**」：普通第三方 App **拿不到**这两个权限（签名/特权级），所以正常 App 无法随意碰别 user 的数据——这是多用户隔离的安全底座。系统/车机 ROM 内的 privileged App 才能用。

AMS 内部的检查骨架（示意，真实在 `ActivityManagerService.checkInteractAcrossUsersPermission` / `UserController` 调用链）：

```java
// 伪代码：任何跨 user 操作入口都会先过这关
int checkInteractAcrossUsersPermission(int callingUid, int targetUserId, boolean allowAll) {
    if (callingUid == Process.SYSTEM_UID) return MODE_ALLOWED;   // system_server 永远放行
    // FULL 权限可做任何跨 user；普通 INTERACT_ACROSS_USERS 不允许 stop 他人进程等高危
    if (checkPermission(INTERACT_ACROSS_USERS_FULL, callingUid) == PERMISSION_GRANTED)
        return MODE_ALLOWED;
    if (allowAll && checkPermission(INTERACT_ACROSS_USERS, callingUid) == PERMISSION_GRANTED)
        return MODE_ALLOWED;
    return MODE_DENIED;   // 抛 SecurityException: "INTERACT_ACROSS_USERS"
}
```

记住：**`INTERACT_ACROSS_USERS` 不是"能做任何跨 user 事"**，它通常只允许查询/启动，高危动作（停别人进程、读别人 CE）要 `INTERACT_ACROSS_USERS_FULL`。

### 7.2 `USER_NULL` 与特殊 userId 语义

- `USER_NULL = -10000`：很多 API 用它表示"未指定/无效用户"。例如 `UserHandle.of(-10000)` 会抛异常——传 user 参数前先判空。
- `USER_ALL = -1`：回调/广播要覆盖所有用户（如 `Intent.ACTION_USER_PRESENT` 系统内部按 all 投）。
- `USER_CURRENT = -2`：运行时解析为"当前前台 user"。

### 7.3 系统应用读其他用户数据的正确姿势

不要直接 `new File("/data/user/10/...")`（会踩权限/路径错），走官方 API，且**指明目标 user**：

```java
// 1) 取"某 user"的 PackageManager 视图
Context userCtx = createContextAsUser(UserHandle.of(10), 0);
PackageManager pm10 = userCtx.getPackageManager();
// 或直接在系统服务里按 user 查询组件信息
ActivityInfo ai = pm10.getActivityInfo(componentName, PackageManager.GET_META_DATA);

// 2) 系统服务内部：带 user 的查询 API（AMS/PMS 均有 *AsUser 变体）
//    getActivityInfoAsUser / getPackageInfoAsUser / getApplicationInfoAsUser
//    调用方需持有 INTERACT_ACROSS_USERS 权限，否则抛 SecurityException

// 3) 跨 user 发广播（特权 App）
Intent i = new Intent("com.x ACTION");
i.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
context.sendBroadcastAsUser(i, UserHandle.of(10)); // 需 INTERACT_ACROSS_USERS
```

ContentProvider 跨 user：provider 默认只服务其安装 user。跨 user 查询要么 provider 在目标 user 也 installed，要么走 `ContentProvider` 的 `*AsUser` 客户端（系统侧），且仍需跨用户权限。

系统侧跨 user 查询 provider 的写法（示意）：

```java
// 在系统服务里，用目标 user 的 Context 取 ContentResolver，再 query
Context targetCtx = mContext.createContextAsUser(UserHandle.of(10), 0);
Cursor c = targetCtx.getContentResolver().query(
        uri, projection, selection, args, sort);   // 需 INTERACT_ACROSS_USERS
// 注意：若 user 10 下该 provider 的包未 installed，query 返回 null/空，不抛异常
```

客户端 App 没有跨 user 权限时，正确做法是**让目标 user 自己的进程去查，再通过 Binder 把结果回传**，而不是自己越权读别 user 的 provider。

### 7.4 为什么"ContentProvider 跨 user 查询空"

最常见原因：
1. 目标 user 下该 provider 的包**未安装/被禁用**（PMS 按 user 记账，见 5.3）。
2. 调用方无 `INTERACT_ACROSS_USERS`，AMS 直接拒绝，返回空/null 而非报错（部分 API 静默失败，最坑）。
3. 目标 user 处于 `STATE_STOPPING/SHUTDOWN`，进程已死。

---

## 8. DevicePolicyManagerService 与 managed profile

`DevicePolicyManagerService`（DPM，位于 `frameworks/base/services/devicepolicy/java/com/android/server/devicepolicy/DevicePolicyManagerService.java`）是企业/设备管理的策略中枢。它和 UMS 的关系是：**UMS 管"有哪些用户、什么类型"，DPM 在 managed profile / device owner 之上叠加"能做什么"的限制**。

### 8.1 架构一句带过

DPM 通过 `DeviceAdminReceiver` + `DevicePolicyManager` 客户端，把"禁止卸载、固定密码、禁用相机、锁屏幕、清数据"等策略落到具体 user。策略最终改的是 `UserInfo.flags`（`FLAG_DISABLED` 等）和 PMS 的包状态。

### 8.2 profile 的 parent/child 关系

- `managed profile`（工作资料）：`profileGroupId == parent userId`。工作资料里的 App 数据独立，但前台仍是 parent；切到 parent 时 work App 才"亮"。
- `profile owner` 只能管自己这个 profile；`device owner` 能管整台设备（通常设在 system user 或专用 device-owner user）。
- `QUIET_MODE`（`DevicePolicyManager.setProfileOwnerQuietMode`）让工作资料整体"下班"——图标变灰、进程暂停。

### 8.3 车机里实际用到哪部分（实话实说）

「**Android 视角**」**绝大多数消费级车机不用 DPM**。车机的"账户/限制"是 AAOS 自己用 `CarUserService` + `UserManager` 实现的，不走 `DevicePolicyManagerService` 的 device/profile owner 模型。DPM 只在以下情况出现：
- 车队管理（fleet）/ 租赁车：用 `device owner` 锁定设置、禁卸载。
- 个别 OEM 把"副驾受限模式"借用了 `restrictions` 思路，但那是 `UserManager` 的 `USER_RESTRICTIONS`，不是 DPM。

所以本篇对 DPM 只点到为止——车机开发者的精力应放在第 9–11 章的 AAOS 用户模型上。

---

## 9. 车机专章（一）：Headless System User 与 Android 15 演进

这是车机用户模型**最大的架构变化**，必须单独讲。

### 9.1 旧模型 vs 新模型

| | 旧（Android <15 多数设备） | 新（Android 15 AAOS headless） |
|---|---|---|
| system user (0) | 同时是前台用户，有 Launcher/显示 | **无 UI、无显示**（headless），只跑系统服务 |
| 前台用户 | user 0 | 一个独立的 `secondary user`（如 driver 主驾账户） |
| 启动顺序 | system_user 启动即前台 | system_user 先 headless 起，再 `startUser` 一个 secondary 作为 foreground |
| 适用 | 手机、旧车机 | AAOS 推荐/强制（多屏、多 Occupant 必需） |

`android:FLAG_HEADLESS` 打在 user 0 上，标志它是 headless system user。

### 9.2 为什么要 headless

车机有多屏（主驾/副驾/后排/仪表/HUD），每个屏要绑定不同 user。若 user 0 霸占前台与显示，多 Occupant 映射就别扭。**让 user 0 退居幕后做"系统容器"，把"谁在前台"完全交给 secondary user + OccupantZone**，多屏多账户才干净。

对启动顺序的具体影响（容易踩坑）：
- headless 下 **user 0 的 `BOOT_COMPLETED` 仍在 system server 早期发出**，但它**没有前台/没有 display**。任何"等前台就绪再做"的逻辑不能挂在 user 0 的 `BOOT_COMPLETED` 上——要等目标 secondary user 的 `UNLOCKED`/`FOREGROUND`（`CarUserManager` 监听，见第 10、11 章）。
- 依赖默认 display 的组件（Launcher、锁屏）在 user 0 下基本无意义，它们属于前台 secondary user。

### 9.3 版本差异重点

- **Android 15 (API 35) automotive**：`Headless System User` 成为 AAOS 的推荐默认；构建侧通过 `TARGET_HEADLESS_SYSTEM_USER := true` 开启（一类构建开关，见第 12 章）。
- 判定当前设备是否 headless：`am get-current-user` 返回非 0（前台是 secondary），且 `dumpsys user` 里 user 0 标注 `FLAG_HEADLESS` / `HEADLESS`。

---

## 10. 车机专章（二）：OccupantZone 绑定与 CarUserManager 监听

### 10.1 OccupantZone ↔ user 的绑定链路

「**Android 视角**」屏幕与用户的绑定不在本篇展开（完整见 `23_多屏与多窗口机制详解-从DisplayManager到CarOccupantZone.md` 第 11 节），这里只给绑定关系本身：

```text
  OccupantZone（座位/区域：DRIVER / FRONT_PASSENGER / REAR_...）
        │  CarOccupantZoneManager / CarOccupantZoneService 维护映射
        ▼
  userId（secondary user，如 10 = 主驾账户，11 = 副驾账户）
        │
        ▼
  Display（某块物理屏 belongs to 该 zone）
```

绑定配置来自 `car_occupant_zone` 资源 / VHAL 的 zone 定义。Zone 绑错会直接导致"副驾屏应用跑在 driver user 下"（排查表第 14 章）。

### 10.2 `CarUserManager` 生命周期监听

`CarUserManager`（`packages/services/Car/car-lib`）让 App/服务监听用户切换事件。核心是 `UserLifecycleListener` + `UserLifecycleEventFilter`：

```java
Car car = Car.createCar(context);
CarUserManager cum = (CarUserManager) car.getCarManager(Car.CAR_USER_SERVICE);

// 只关心"切到前台"且目标 user 是 10/11 的事件（过滤语义！）
UserLifecycleEventFilter filter = new UserLifecycleEventFilter.Builder()
        .setUserIds(10, 11)
        .setEventTypes(UserLifecycleEvent.EVENT_TYPE_SWITCHING)
        .build();

cum.addListener(Runnable::run, filter, new UserLifecycleListener() {
    @Override
    public void onEvent(UserLifecycleEvent event) {
        int targetUser = event.getUserId();        // 目标 user
        int eventType  = event.getEventType();      // SWITCHING / UNLOCKED / BACKGROUND 等
        // 在切用户瞬间做媒体/账号/屏的联动
    }
});
```

`UserLifecycleEventFilter` 的过滤语义是重点：**不指定 `setUserIds` 就收所有 user；不指定 `setEventTypes` 就收所有事件类型**（SWITCHING / UNLOCKED / FOREGROUND / BACKGROUND / CREATED / REMOVED / STOPPING）。车机里务必按 user + 事件类型过滤，否则监听会收到大量无关事件。

### 10.3 事件类型对照

| 事件 | 触发时机 |
|---|---|
| `EVENT_TYPE_SWITCHING` | 切换进行中（目标 user 即将前台） |
| `EVENT_TYPE_UNLOCKED` | 目标 user CE 解密完成 |
| `EVENT_TYPE_FOREGROUND` | 目标 user 成为前台 |
| `EVENT_TYPE_BACKGROUND` | 某 user 退到后台 |
| `EVENT_TYPE_CREATED` / `REMOVED` | 用户创建/删除 |
| `EVENT_TYPE_STOPPING` / `SHUTDOWN` | 用户停止/关闭 |

---

## 11. 车机专章（三）：切换驾驶员账户的完整链路

副驾登录、主驾切档案，端到端链路是车机用户模型最有价值的一环。

### 11.1 触发源：VHAL 通知

驾驶员识别（人脸识别/指纹/座椅记忆/物理开关）结果先到 **VHAL**，通过用户识别类属性（如 `USER_IDENTIFICATION_ASSOCIATION` / 切换请求属性）上报。VHAL 不直接懂 Android user，它只报"某个 zone 想要哪个 user"。

```text
  VHAL (USER_IDENTIFICATION / switch request)
     │  CarPropertyService 转发
     ▼
  CarUserService（packages/services/Car/service）
     │  校验目标 user 存在、未 disabled、未越权
     ▼
  CarUserManagerService 决定"切到哪个 userId"
     │  调用 ActivityManager 的 switchUser
     ▼
  ActivityManager.UserController.switchUser(targetId)   ← 见第 4.3 节
     ├─ 停止旧前台 user 组件 / 降级后台
     ├─ startUser(targetId, foreground=true)
     ├─ WMS 把 display 重新归属到目标 zone 的屏（见 23 第 11 节）
     └─ 发 ACTION_USER_FOREGROUND / BACKGROUND
     ▼
  CarUserManager 回调 UserLifecycleListener（SWITCHING → UNLOCKED → FOREGROUND）
```

### 11.2 headless 下的切换要点

- 若目标 user 还没创建：`CarUserService` 会先 `createUser` 再 `switchUser`（首次登录慢，可用第 3.3 节的 `preCreated` 用户加速）。
- 目标 user **未 UNLOCKED** 时切过去会"卡"在锁屏/解密——这是 `switchUser` 卡住的头号原因（排查表见第 14 章）。
- 切换是**异步**的（第 4.2 节）：`CarUserManager` 的回调才是"切完了"的可靠信号，不要在调用 `switchUser` 后立刻假设前台已变。

### 11.3 多用户下预装应用初始化跑多次的坑（呼应存储篇）

- 现象：同一预装 App 在每个新登录的 user 下都重新初始化、弹引导、建数据库——这**通常是预期**（per-user 数据隔离，每个 user 是独立"住户"）。真正要修的是"设备级只做一次"的逻辑被误放在 per-user 初始化里。
- 正确分工：设备级一次性逻辑放 user 0 / `BOOT_COMPLETED`（system user）或 `DevicePolicy`；per-user 逻辑放 `ACTION_USER_INITIALIZE`（见 5.4）。
- 存储篇（`../androidOthers/Android存储机制详解-从分区到ScopedStorage与多用户.md` 第 15 章）详述每个 user 的 `/data/user/<id>` 为何独立、为何不能跨 user 共享 CE 文件。

---

## 12. 平台定制：配置与构建开关

ROM 厂商改多用户行为，主要动三类东西。配置名若不确定，统一用"**一类系统配置 + getprop/adb 验证法**"表述，不要写死。

### 12.1 最大用户数（一类系统配置）

控制"设备最多能建几个用户"的，是 `UserManager.getMaxSupportedUsers()`，其来源是 framework 资源 `config_multiuserMaximumUsers`（位于 `frameworks/base/core/res/res/values/config.xml`）。车机通常调大以支持主驾/副驾/后排多账户。

```bash
# 验证当前上限（真实可读）
adb shell dumpsys user | grep -i "max.*user\|Maximum"
# 或在 UserManager 内部计数
adb shell cmd user list | head
# 看 framework 资源里的值（需要源码/反编译）
#   frameworks/base/core/res/res/values/config.xml : config_multiuserMaximumUsers
```

「**Android 视角**」：不要把上限写成固定 `fw.max_users` 之类属性——历史上有过不同命名，且不同 Android 版本取值逻辑不同。**以 `getMaxSupportedUsers()` 运行时返回值为准**，改它要去 overlay `config_multiuserMaximumUsers`。

### 12.2 headless 模式的构建开关（一类构建开关）

Android 15 AAOS 开 headless system user，靠一类构建变量（表述为 `TARGET_HEADLESS_SYSTEM_USER` 之类的 make 开关，落在 `device/` 或 `build/` 的 `*.mk`）：

```bash
# 运行时验证是否 headless：前台 user 非 0 且 user 0 带 headless 标记
adb shell am get-current-user          # 期望返回非 0（如 10/11）
adb shell dumpsys user | grep -i "headless\|FLAG_HEADLESS"
# 看属性（不同版本属性名可能不同，先 grep 再下结论）
adb shell getprop | grep -i "headless\|system_user"
```

### 12.3 默认用户 / 自动切换

默认启动到哪个 user、是否自动按 VHAL 切换，由 AAOS 的 `CarUserManagerService` 配置（`R.xml` 里的 `default_user` / `switch_user` 策略资源）控制。这部分在 automotive 篇（`01-AAOS整体架构与CarService.md`、`07-CarService源码阅读路线.md`）细讲，本篇只点名它属于"用户模型的上层策略"。

---

## 13. 调试工具箱

按模块分组，每条带期望输出要点。

### 13.1 当前用户与切换

```bash
am get-current-user            # 当前前台 userId（headless 车机里应非 0）
am switch-user 10              # 切到 user 10（需权限，root/shell 可）
am start-user 10               # 启动（后台）user 10
am stop-user 10                # 停止 user 10（异步）
am start-user --user 10 com.x/.MainActivity   # 以指定 user 起 Activity
```

### 13.2 `cmd user`

```bash
cmd user list                  # 所有用户：userId / name / state / flags 摘要
cmd user info 10               # 单用户详情：UserInfo、running、unlocked、serial
cmd user create "Rear"         # 建一个 secondary user（类型可加 --profileOf 建 profile）
cmd user remove 10             # 删除用户（清数据）
```

### 13.3 `dumpsys user` 逐段读法

```bash
dumpsys user
```

重点看三段：
- **UserInfo 块**：每个用户的 `id / name / flags / serialNumber / userType`，确认 `FLAG_HEADLESS`/`FLAG_MANAGED_PROFILE` 等。
- **State 块**：用户的运行状态（`BOOTING/RUNNING_LOCKED/RUNNING_UNLOCKED/STOPPING/SHUTDOWN`），对应第 4 章状态机。
- **handlers / listeners**：系统注册的 per-user 回调（含 `CarUserManager` 的监听），排查"切换没回调"时看这里是否注册成功。

### 13.4 PMS 按用户视角

```bash
pm list users                  # 同 cmd user list 的用户清单
pm list packages --user 10     # 只看 user 10 装了哪些包（验证"装了却看不到"）
pm dump com.x                  # 看包在各 user 下的 installed/enabled/hidden 状态
dumpsys package com.x | grep -i "userId\|enabled\|hidden"   # 逐 user 安装态
```

### 13.5 多用户下日志分离

`logcat` **没有原生的 `--user` 开关**。但 uid 编码了 userId（第 1.2 节），可用 uid 区间过滤某个 user 的所有进程日志：

```bash
# user 10 的 uid 区间 = [10*100000, 10*100000+99999] = [1000000, 1099999]
adb logcat --uid=1000000-1099999     # 部分 logcat 版本支持 --uid 范围
# 不支持时退而求其次：用 tag + pid 过滤，或在 App 日志里打印 UserHandle.myUserId()
```

`「**Android 视角**」`：`--uid` 过滤的可用性取决于 `logcat` 版本，老版本只有 `--pid`。稳妥做法是在关键日志里主动输出 `UserHandle.myUserId()`，方便按文本 grep。

---

## 14. 常见问题排查表

> 四列：现象 | 原因 | 指向章节 | 第一命令。

| 现象 | 原因 | 章节 | 第一命令 |
|---|---|---|---|
| 新用户下预装 App 没起 | 预装只装到初始 user，未 `install-existing` 到新 user | 5.3 / 11.3 | `pm list packages --user <新id>` |
| App 装了但 Launcher 看不到 | 当前 user 下该包 hidden/disabled/未安装 | 5.3 | `dumpsys package <pkg> \| grep -i "enabled\|hidden"` |
| `switchUser` 卡住 | 目标 user 未 UNLOCKED（CE 未解密） | 4.3 / 6.2 | `dumpsys user \| grep -A3 "userId=<目标>"` |
| 广播收不到（user 过滤） | 广播发给了其他 user，或目标 user 未 running | 5.2 | `am get-current-user` + `dumpsys user` |
| `LOCKED_BOOT_COMPLETED` 里读文件崩 | 解锁前只能碰 DE，读了 CE 文件 | 6.1 | 看 crash 栈是否 `FileNotFoundException` on `/data/user/` |
| ContentProvider 跨 user 查询空 | 目标 user 下 provider 未安装/无跨用户权限/静默失败 | 7.3 / 7.4 | `pm list packages --user <目标>` |
| managed profile 通知不显示 | profile 处于 `QUIET_MODE` 或 parent 未 UNLOCKED | 8.2 | `cmd user info <profileId>` |
| 车机切账户后媒体会话丢失 | 媒体会话绑定在旧 user 进程，切 user 进程被杀 | 11.2 | `dumpsys media_session \| grep -i user` |
| `max users` 改了不生效 | 改错资源/未 overlay `config_multiuserMaximumUsers` | 12.1 | `dumpsys user \| grep -i max` |
| 副驾屏应用跑在 driver user 下 | OccupantZone ↔ user 绑定错 | 10.1 / 23 第 11 节 | `cmd car_service occupancy`(AAOS) |
| 报 `SecurityException: INTERACT_ACROSS_USERS` | 调用方无跨用户权限（普通 App 必然有） | 7.1 | 检查 manifest 权限 + 调用方 UID |
| `pm install --user` 失败 | 包在其他 user 已装但状态异常 / 空间满 | 5.3 | `pm list packages --user <id>` |
| 用户删了又出现相同 id | userId 被复用（非 bug，用 serialNumber 做主键） | 3.3 | `cmd user info <id>` 看 serialNumber |
| headless 设备前台仍是 user 0 | 构建未开 headless 开关 / 启动顺序错 | 9.3 / 12.2 | `am get-current-user` + `dumpsys user \| grep headless` |
| 工作资料 App 图标灰 | `QUIET_MODE` 开启（"下班"） | 8.2 | `cmd user info <profileId>` |
| `getActivityInfoAsUser` 返回 null | 目标 user 下包未安装或无跨用户权限 | 7.3 | `pm list packages --user <目标>` |
| `BOOT_COMPLETED` 初始化重复跑 | 逻辑放在 per-user 而非 system user | 5.4 / 11.3 | 检查 receiver 注册在哪个 user |

---

## 15. 读源码路线

按目标挑一条，配 `cgrep`/`Android Studio` 跟调用链。

1. **startUser 全链**：`ActivityManagerService.startUser` → `UserController.startUser` → `UserController.startUserWithPermissions` → `finishUserBoot` / `finishUserUnlocking`。看状态如何从 BOOTING→UNLOCKED。
2. **switchUser 链路**：`CarUserService.switchUser`(AAOS) → `ActivityManager.switchUser` → `UserController.switchUser` → `moveUserToForeground`。对比第 4.3 / 11.1 节。
3. **per-user 组件过滤**：`BroadcastQueue` 里 `deliverToRegisteredReceiverLocked` / `manifest` 分支对 `Intent.FLAG_RECEIVER_*` 与 user 的判断；`ActivityStarter` 对目标 user 的 running/unlocked 校验。
4. **CarUserManager 监听注册**：`CarUserManager.addListener` → `CarUserManagerService`（AAOS）→ `UserController` 的 `registerUserSwitchObserver`；看 `UserLifecycleEventFilter` 如何过滤。
5. **用户创建**：`UserManager.createUser` → `UserManagerService.createUser` → `createUserInternal` → 写 `/data/system/users/<id>/` + PMS `installExistingPackageForUser`。看 `preCreated` 与 `partial` 标志如何消消。
6. **解锁时序**：`LockSettingsService` 解密 CE → `ActivityManager` 收到 `unlockUser` → `finishUserUnlocking` → 发 `ACTION_USER_UNLOCKED`（对应 6.2 节）。

---

## 16. 源码路径速查表 + 一图总结 + 关联阅读

### 16.1 源码路径速查

| 内容 | 路径 |
|---|---|
| `UserManagerService` | `frameworks/base/services/core/java/com/android/server/pm/UserManagerService.java` |
| `UserInfo` | `frameworks/base/core/java/android/content/pm/UserInfo.java` |
| `UserHandle` / 特殊 userId 常量 | `frameworks/base/core/java/android/os/UserHandle.java` |
| 用户运行状态机 `UserState` | `frameworks/base/services/core/java/com/android/server/am/UserState.java` |
| `UserController`（start/switch/stop 的异步中枢） | `frameworks/base/services/core/java/com/android/server/am/UserController.java` |
| `UserManager` API | `frameworks/base/core/java/android/os/UserManager.java` |
| `DevicePolicyManagerService` | `frameworks/base/services/devicepolicy/java/com/android/server/devicepolicy/DevicePolicyManagerService.java` |
| `DevicePolicyManager` API | `frameworks/base/core/java/android/app/admin/DevicePolicyManager.java` |
| `CarUserService` | `packages/services/Car/service/src/com/android/car/user/CarUserService.java` |
| `CarUserManager`(client) | `packages/services/Car/car-lib/src/android/car/user/CarUserManager.java` |
| `UserLifecycleEvent` / `Filter` | `packages/services/Car/car-lib/src/android/car/user/UserLifecycleEvent*.java` |
| PMS 用户安装态 | `frameworks/base/services/core/java/com/android/server/pm/Settings.java` |

### 16.2 一图总结

```text
┌──────────────────── 用户状态机（UserController） ────────────────────┐
│  startUser ─► BOOTING ─► RUNNING_LOCKED ─(解锁)─► UNLOCKING ─► UNLOCKED │
│                                                                  │ stopUser
│                                                              STOPPING ─► SHUTDOWN
└────────────────────────────────────────────────────────────────────┘

   隔离维度（同一个 APK 在不同 user 为何"像两个 App"）
   ┌────────────┬───────────────────────────────────────────────┐
   │ 维度        │ 机制                                          │
   ├────────────┼───────────────────────────────────────────────┤
   │ 进程/身份   │ uid = userId*100000 + appId                    │
   │ 数据        │ /data/user/<id>(CE)  /data/user_de/<id>(DE)   │
   │ 安装态      │ PMS 按 user 记账 installed/enabled/hidden     │
   │ 广播        │ 默认只投调用方 user，跨 user 需权限           │
   │ 前台        │ 单前台 user，profile 跟随 parent              │
   └────────────┴───────────────────────────────────────────────┘

   车机链路一句话记忆链：
   VHAL 报 zone 想要谁 ─► CarUserService 解析 userId ─► AMS.switchUser
   ─► WMS 重绑屏(见23§11) ─► CarUserManager 回调(UNLOCKED 才算完)
```

**一句话记忆链**：`userId 编码进 uid → uid 决定进程/文件/权限隔离 → UserController 状态机管生死 → 车机里 user 0 退居 headless、secondary user 借 OccupantZone 上屏。`

### 16.3 关联阅读

- `05_AMS机制详解-从App现象理解系统调度.md`、`06_AMS机制详解-从应用交互到Framework工作流程.md`：组件启动与 user 校验的调用方。
- `09_ContentProvider机制详解-从ContentResolver到跨进程数据访问.md`：跨 user 查询的底层（呼应 7.4）。
- `23_多屏与多窗口机制详解-从DisplayManager到CarOccupantZone.md` 第 11 节：屏 ↔ OccupantZone ↔ user 绑定全貌。
- `../androidOthers/Android存储机制详解-从分区到ScopedStorage与多用户.md` 第 15 章：多用户的 CE/DE 存储隔离细节。
- `18_SystemUI机制详解-从状态栏到锁屏与通知面板.md`：SystemUI 的 per-user 行为。
- automotive 系列：`01-AAOS整体架构与CarService.md`、`07-CarService源码阅读路线.md`：CarUserService / CarUserManager 上层策略。
- `14_权限机制详解-从Permission到AppOps.md`：`INTERACT_ACROSS_USERS` 等权限的授予模型。
