# 权限机制详解：从 Permission 到 AppOps

> 为什么「Manifest 写了权限、用户也点了允许，功能还是可能不能用」？答案通常藏在 PermissionManagerService 与 AppOpsService 的组合里。本文是 **Framework 侧**视角：权限怎么被声明、怎么被安装/运行时授予、怎么在真正执行那一下被 AppOps 放行或拦截。

---

## 0. 怎么读这篇 + 与其他篇的分工

### 权限机制要回答的三个问题

Android 的"权限"不是一个单点判断，而是一组机制的接力。落到排查时，它其实只回答三个问题：

1. **谁声明了"能力"？** —— App 在 `AndroidManifest.xml` 用 `<uses-permission>` 声明自己需要某项能力；系统用 `<permission>` 定义这项能力的 `protectionLevel`（谁能拿、怎么拿）。
2. **谁批准了？** —— 安装时（normal/signature）或运行时（dangerous，由用户点允许）由 PermissionManagerService 决定授予与否，状态落盘到 `runtime-permissions.xml`。
3. **批准了怎么"执行"？** —— 真正调敏感 API 时，执行那一侧（WMS、CameraService、LocationManagerService…）除了查 Permission，还会查 AppOps。AppOps 是"这次操作放不放行"的细粒度开关 + 审计记录。

一句话记忆：

> **Permission 决定"你有没有资格"，AppOps 决定"这次操作放不放行"。**

### 三层防线图

```text
┌──────────────────────────────────────────────────────────────┐
│  第 1 层  安装 / Manifest 防线（静态、装机即定）                │
│  ── PMS 解析 Manifest，按 protectionLevel 判定                 │
│     normal      → 安装即授予                                   │
│     signature   → 签名一致才授予                               │
│     privileged  → priv-app 且 privapp-permissions 白名单命中   │
└───────────────────────────────┬──────────────────────────────┘
                                 │ 装机后
                                 ▼
┌──────────────────────────────────────────────────────────────┐
│  第 2 层  运行时授权防线（动态、用户可改）                      │
│  ── PermissionManagerService 管"资格"状态                       │
│     dangerous   → 弹窗由用户授权（PermissionController）        │
│     特殊权限     → 跳设置页开关 + AppOps op 双层判定             │
└───────────────────────────────┬──────────────────────────────┘
                                 │ 执行敏感操作时
                                 ▼
┌──────────────────────────────────────────────────────────────┐
│  第 3 层  AppOps / SELinux 防线（执行时、细粒度 + 强制）        │
│  ── AppOpsService 管"这次操作"：MODE_ALLOWED/IGNORED/ERRORED    │
│  ── SELinux(MAC) 管内核级强制：进程能否碰文件/设备节点          │
└──────────────────────────────────────────────────────────────┘
```

注意 **DAC vs MAC 的分工**：本文讲的全是 DAC 之上的"权限/AppOps"层（由 Framework 服务在用户态判定）。更底层的 **SELinux（MAC，强制访问控制）** 由内核与 `sepolicy` 在进程/文件/设备节点层面强制，细节见姊妹篇 `androidOthers/AndroidSELinux机制详解`，本篇只在需要时一句对照衔接。

### 与其他篇的分工（避免重复）

| 主题 | 本篇 | 落在别处 |
|---|---|---|
| SELinux / sepolicy 规则 | 仅做 DAC↔MAC 一句衔接 | `androidOthers/AndroidSELinux机制详解` |
| App 侧怎么写 `<uses-permission>`、怎么调 `requestPermissions` | 点到为止（Framework 视角） | `androidApp/08_……`（应用权限篇） |
| `privapp-permissions.xml` 怎么**打进镜像**（mk/bp、分区路径） | 不展开打包 | `androidOthers/构建系统篇` |
| 本篇只讲 **PMS / SystemConfig 怎么校验**这份白名单 | ✅ 重点 | — |

### 怎么读

- 想建立直觉：读 §1–§2。
- 排查"有权限却用不了"：直接跳 §5（AppOps）、§11（dumpsys）、§12（排查表）。
- 做车机预装 / priv-app 白名单：重点 §7–§8。
- 跟源码：§13 给 4 条调用链路线，§14 给路径速查表。

---

## 1. 权限的静态模型：PermissionInfo 与 protectionLevel

### 1.1 两个角色的 XML

权限机制里有两个互补的声明：

- **定义能力**：`<permission android:name="android.permission.CAMERA" android:protectionLevel="dangerous" />`。系统框架和预装 App 都能定义。
- **申请能力**：`<uses-permission android:name="android.permission.CAMERA" />`。普通 App 只做这一步。

`PackageManager` 把 `<permission>` 解析成 `PermissionInfo`，核心字段：

| 字段 | 含义 |
|---|---|
| `name` | 权限全名，如 `android.permission.ACCESS_FINE_LOCATION` |
| `protectionLevel` | 保护级别（见下），决定"谁能拿、怎么拿" |
| `group` | 所属权限组（运行时授权时 UI 分组展示） |
| `permissionFlags` | 平台附加标记，如 `INSTALLER`、`RESTRICTED`，影响授予策略 |

> 「**Android 视角**」：系统内置权限的 `PermissionInfo` 多数来自 `frameworks/base/core/res/AndroidManifest.xml` 的 `<permission>` 声明，经 PMS 在启动时加载进 `PermissionRegistry`。

### 1.2 protectionLevel 四档（基础）

`protectionLevel` 的低 4 位是最经典的四档：

| 档位 | 值 | 含义 | 授予时机 |
|---|---|---|---|
| `normal` | 0 | 低风险，安装即授予，不弹窗 | 安装时自动 |
| `dangerous` | 1 | 触及隐私/安全，需用户明确同意 | 运行时弹窗授权 |
| `signature` | 2 | 申请方与声明方**签名一致**才授予 | 安装时自动（若签名不符则失败） |
| `signatureOrSystem` | 3 | 历史档位，等价于"签名一致 **或** 是系统镜像 App" | 安装时自动 |

### 1.3 protectionLevel 的 flag 演进（重点：版本差异）

早期只有四档，后来权限边界越来越细，Google 把 `protectionLevel` 改成了 **"基础档 + 一组 flag 位"** 的位掩码。这是**最容易踩版本坑**的地方，务必标清：

```text
基础档（低 4 位）：
  PROTECTION_NORMAL     = 0
  PROTECTION_DANGEROUS  = 1
  PROTECTION_SIGNATURE  = 2
  PROTECTION_SIGNATURE_OR_SYSTEM = 3   // 已废弃语义，逐步被下面 flag 取代

高阶 flag（高位，按位或）：
  FLAG_PRIVILEGED        = 0x10   // 需要 priv-app 身份
  FLAG_SYSTEM            = 0x20   // 需要声明在系统分区 (system/image)
  FLAG_DEVELOPMENT       = 0x40   // 调试权限，可 adb grant
  FLAG_APPOP            = 0x80   // 该权限背后绑定一个 AppOps op
  FLAG_PRE23            = 0x100  // 装在前 Android 6.0 的 App 默认授予
  FLAG_INSTALLER        = 0x200  // 仅允许 installer 持有
  FLAG_VERIFIER         = 0x400  // 仅允许 package verifier
  FLAG_PREINSTALLED     = 0x800  // 仅预装 App
  FLAG_SETTER           = 0x1000 // 仅允许 role/settings 设置者
  FLAG_KNOWN_SIGNER     = ...    // 较新版本：已知签名者（如 oem/ carrier）
```

演进要点（**版本差异标**）：

- **Android 6.0（API 23）之前**：没有运行时权限，`dangerous` 也安装即授予。
- **`signatureOrSystem` 的语义拆分**：旧档 `3` 在新代码中逐渐被 `signature | FLAG_PRIVILEGED | FLAG_SYSTEM` 表达。原因是"system 分区"和"privileged"是两回事——普通的 `/system/app` 系统 App 不等于 `/system/priv-app`（privileged）。写白名单和预装时要区分清楚。
- **`FLAG_PRIVILEGED` 只认 `/system/priv-app`（及 OEM/vendor 对应 priv-app 目录）**：放到 `/system/app` 即使签名对也拿不到 privileged 权限。
- **`FLAG_APPOP`**：标记"这个 Permission 背后挂着一个 AppOps op"。例如 `ACCESS_FINE_LOCATION` 关联 op `OP_FINE_LOCATION`。这是 §5 双层关系的根源。

> 「**Android 视角 / 车机落地**」：车机 ROM 里很多"系统能力"是 `signature | FLAG_PRIVILEGED` 的自定义权限，只授给同一签名或 priv-app 的预装件。给不对分区/签名，预装就会失败（§8 详述）。

### 1.4 权限组（permission-group）

`dangerous` 权限按 `group` 分组，授权时以"组"为单位弹一次窗（同组后续权限自动连带授予，直到被撤销）：

```text
android.permission-group.LOCATION
   ├─ ACCESS_FINE_LOCATION
   └─ ACCESS_COARSE_LOCATION
android.permission-group.CAMERA
   └─ CAMERA
android.permission-group.MICROPHONE
   └─ RECORD_AUDIO
```

注意：**分组是 UI/授权粒度，不是能力粒度**。撤销组内一个权限，同组其他已授权权限会一起掉（运行时判定以单个权限为准）。

### 1.5 uses-permission 的三个变体（易混）

| 写法 | 含义 | 适用 |
|---|---|---|
| `<uses-permission android:name="X" />` | 始终申请 | 大多数 |
| `<uses-permission-sdk-23 android:name="X" />` | **仅当** `targetSdk >= 23` 才视为危险权限走运行时；低 targetSdk 时当作普通授予（兼容旧 App） | 老 App 升级 targetSdk 时的过渡 |
| `<uses-permission android:name="X" android:maxSdkVersion="28" />` | 只在 **API ≤ 28** 时申请该权限；高版本不再需要/自动忽略 | 某权限高版本被废弃（如 `WRITE_EXTERNAL_STORAGE` 在 scoped storage 后多余） |

> 「**Android 视角**」：`maxSdkVersion` 不影响已授予状态，只影响"安装/解析时是否登记该权限请求"。排查老 App 升级后"权限变多/变少"时先看这几个变体。

---

## 2. PMS 侧的授予：安装时与平台默认授权

PMS 在**安装/扫描包**阶段就把权限"算"出来并落库。这部分是静态、装机即定的（对应 §0 第 1 层）。详细的包扫描流程见 `04_PMS机制详解`，本篇只聚焦"权限判定"这一步。

### 2.1 安装时判定链（normal / signature）

```text
PMS.scanPackageDirtyLI / commitPackageSettings
  └─ PermissionManagerService.updatePermissionsForPackage
       └─ 遍历 App 声明的 each <uses-permission>
            ├─ 查 PermissionRegistry 找到 PermissionInfo
            ├─ 按 protectionLevel 分支：
            │    normal            → grant（自动）
            │    signature         → 比对签名 (compareSignatures)
            │    signature|系统flag → 是否在系统分区 / 是否 priv-app
            │    dangerous         → 初始状态 = 未授权（见 2.3）
            └─ 写入 PackageSetting 的 grantedPermissions
```

**签名比对**是 `signature` 权限的核心：`PermissionManagerService` 调用 `PMS.compareSignatures(declaringUidSignatures, requestingSignatures)`。不一致则：

- 该 `signature` 权限**不授予**；
- 若该权限是**安装必需**（`INSTALL_EXISTING` / 某些核心权限），安装直接失败。

> 交叉引用：签名体系、UID 分配、PMS 扫描生命周期见 `04_PMS机制详解`。

### 2.2 平台默认授权清单（"默认授予的例外"）

很多系统 App 的 `dangerous` 权限你从没点过"允许"，却已经是 GRANTED——因为系统在首次启动时通过**平台默认授权**直接授予。机制是：

- `PermissionManagerService` 在 `systemReady` 阶段读取 `DefaultPermissionGrantPolicy`（在 `PermissionController` 模块里，见 §3.4）；
- 按"系统固定角色"给一批预装/特权组件默认授予危险权限，例如：
  - 默认短信 App → `SEND_SMS` / `READ_SMS` 等；
  - 默认电话 App → `CALL_PHONE` / `READ_PHONE_STATE`；
  - 默认 launcher / 系统 UI → 部分前台权限；
  - 车机场景：默认车辆控制 App、默认语音助手 → 相应危险权限被平台默认授权。

这种授权在 `runtime-permissions.xml` 里标记来源，用户可在设置里撤销，但**系统 OTA 升级后可能再次默认授予**（取决于 `DefaultPermissionGrantPolicy` 的 `FIXED` 标记）。

> 「**Android 视角**」：平台默认授权 ≠ `signature`，它是把"本来要用户点的 dangerous"由系统替用户点了。排查"我没授权为什么它有权"时，第一反应是查 `dumpsys package <pkg>` 里权限的 `granted=true` 来源标记，而不是怀疑 App 偷权限。

### 2.3 dangerous 权限的初始状态

关键区分（**版本差异**）：

- **Android 6.0+ 且 `targetSdk >= 23`**：`dangerous` 安装后初始为 **未授权（revoked）**，首次用到才弹窗。即"请求即授予"在 dangerous 上**不成立**——必须走运行时授权。
- **`targetSdk < 23`（legacy App）**：安装即视为已授予（兼容旧行为），首次运行弹"权限已授予"提示，用户可随后收回。
- **`FLAG_PRE23` 权限**：显式声明"装在前 6.0 时代的 App 默认授予"。

所以"dangerous 的初始状态"不是一句话能定的，取决于 `targetSdk` 与 flag。排查时别想当然认为"装完就有"。

---

## 3. 运行时授权链路：从 requestPermissions 到 checkUidPermission

### 3.1 App 侧发起（点到为止）

App 侧（详见 `androidApp/08_应用权限篇`）只做两件事：

```java
// 1) 查询
int r = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA);
// 2) 申请（会弹窗）
ActivityCompat.requestPermissions(activity,
        new String[]{Manifest.permission.CAMERA}, REQ_CODE);
// 回调 onRequestPermissionsResult(...)
```

本篇关心的是：这两个调用**背后 Framework 发生了什么**。

### 3.2 查询链路 checkSelfPermission → checkUidPermission

```text
Context.checkSelfPermission(name)
  └─ ContextImpl.checkPermission → PM.getPermissionResolver
       └─ PermissionManager.checkPermission(name, pid, uid)
            └─ PermissionManagerService.checkPermission  (binder)
                 └─ PermissionManagerService.checkUidPermission(name, uid)
                      ├─ 先查 AppOps? 否——这是"资格"查询
                      ├─ 解析 permission，判断 protectionLevel
                      ├─ 若 normal        → PERMISSION_GRANTED
                      ├─ 若 signature      → 比对签名/privileged 状态
                      ├─ 若 dangerous      → 查 runtime-permissions.xml 该 uid 状态
                      └─ 返回 GRANTED / DENIED
```

关键点：`checkSelfPermission` 只回答"资格"，**不查 AppOps**。所以即使 AppOps 把 op 设成 IGNORED，`checkSelfPermission` 仍可能返回 GRANTED。这就是"有权限却用不了"的一半原因（另一半在 §5）。

### 3.3 授权对话框从哪弹：PermissionController 与 Mainline 演进

`requestPermissions` 的弹窗 UI **不在 framework/base**，而在独立的 `PermissionController` 模块：

```text
App.requestPermissions
  └─ Activity / PackageManager 转交
       └─ 启动 PermissionController 的授权 Activity（系统 UID 进程）
            └─ 用户点允许/拒绝
                 └─ 回写 PermissionManagerService.grantRuntimePermission
                      └─ 落盘 runtime-permissions.xml + 回调 App
```

**版本差异（重点）**：

- **AOSP 旧版**：授权 UI 在 `frameworks/base/packages/PermissionController`（源码树内）。
- **Android 10+（Mainline / 模块化）**：`PermissionController` 被抽成 `packages/modules/Permission/`（Project Mainline 的一部分），可经 Google Play **独立 OTA 更新**授权逻辑与 UI，而不动 system 镜像。

> 「**Android 视角**」：**Google 侧**（GMS 设备）的 `PermissionController` 走 Mainline，升级快；**AOSP 侧**（纯原生/部分车机）仍可能是源码树内旧形态。车机 ROM 若裁剪/替换了 `PermissionController`，会出现"权限弹窗不弹"或"弹窗样式异常"（见 §12 排查表）。

### 3.4 授权后的状态机

`PermissionManagerService` 给每个 (uid, permission) 维护状态，落盘于 `/data/system/users/<userId>/runtime-permissions.xml`：

| 状态 | 含义 |
|---|---|
| `granted` | 已授予 |
| `revoked` | 未授予 / 已撤销 |
| `user_fixed` | 用户选"不再询问"，UI 不再弹 |
| `policy_fixed` | 设备策略（DevicePolicy / 车机策略）固定，用户不可改 |
| `one_time` | 一次性授权（见 §9） |

---

## 4. AppOps 详解：Permission 之外的"执行开关 + 审计"

AppOps（Application Operations）是理解"为什么有权限还用不了"的钥匙。

### 4.1 它与 Permission 的两层关系

AppOps 不是 Permission 的替代品，而是叠加在其上的**两件事**：

1. **执行开关（enforcement）**：很多危险权限背后挂一个 AppOps op（§1.3 的 `FLAG_APPOP`）。执行敏感操作时，服务**先查 Permission（资格），再查 AppOps（这次放不放行）**。两层都过才真正放行。
2. **审计记录（accounting）**：AppOps 记录每个 op 的"上次使用时间、被允许次数、被拒绝次数、前台/后台时长"，用于自动回收（§9）、设置页"权限使用记录"展示。

```text
例：后台定位拿不到
  checkSelfPermission(FINE_LOCATION)        == GRANTED   ✅ 资格有
  但 AppOps op OP_FINE_LOCATION 的 mode
       在 background 维度 == MODE_IGNORED    ❌ 这次不放行
  → LocationManager 返回空位置 / 抛 SecurityException
```

### 4.2 OpMode 四态（及常见扩展态）

核心是 `AppOpsManager` 里的 mode 常量：

| 常量 | 值 | 含义 |
|---|---|---|
| `MODE_ALLOWED` | 0 | 允许 |
| `MODE_IGNORED` | 1 | 静默拒绝（不报错，返回空/无操作） |
| `MODE_ERRORED` | 2 | 拒绝并抛 `SecurityException` / 返回错误 |
| `MODE_DEFAULT` | 3 | 走默认策略（由 `AppOpsService` 按 op 默认表决定） |
| `MODE_FOREGROUND` | 4 | 仅前台允许，后台转 IGNORED（用于后台定位等） |
| `MODE_ASK` | 5+ | 部分 op 支持"每次询问"（较新版本） |

> 「**Android 视角**」：op 默认值在 `AppOpsService` 的 `sOpDefaultMode` / `OP_STR` 表里定义，不同 op 的 `MODE_DEFAULT` 展开结果不同（例如 `OP_COARSE_LOCATION` 默认 ALLOWED，`OP_WRITE_SETTINGS` 默认 ERRORED）。

### 4.3 粒度：按包 / 按 uid / 按 op

AppOps 的键是三维组合：`(uid, packageName, op)`。

- **按 uid**：同 uid 下多个包共享 op 状态（典型：同一个 App 的多个 `Activity`、或 `sharedUserId` 的多个包）。
- **按 package**：可针对单包设置（多包同 uid 时更细）。
- **按 op**：每个敏感操作一个 op（如 `OP_CAMERA`、`OP_FINE_LOCATION`、`OP_SYSTEM_ALERT_WINDOW`）。

设置命令示范：

```bash
# 查看某包全部 op 状态
adb shell appops get com.example.app
# 只看某个 op
adb shell appops get com.example.app ACCESS_FINE_LOCATION

# 设置 op 模式
adb shell appops set com.example.app SYSTEM_ALERT_WINDOW allow
adb shell appops set com.example.app ACCESS_FINE_LOCATION foreground
# 重置为默认
adb shell appops reset com.example.app
```

> 「**Android 视角 / 车机落地**」：车机里常把"系统级悬浮窗/后台定位"对预装件设为 `allow`，对第三方设为 `default/ignored`。改完用 `appops get` 立刻复核，别只看 `dumpsys`。

### 4.4 典型场景：后台定位被 AppOps 限制但权限是授予的

```text
现象：App 前台能拿到 GPS，切后台后 onLocationChanged 不再回调。
排查：
  pm grant com.example.app ACCESS_FINE_LOCATION   # 已是 GRANTED
  appops get com.example.app ACCESS_FINE_LOCATION
      → 显示 background 维度 mode = ignored（或 default 展开为 ignored）
根因：Android 10+ 后台定位是"两段式"（§9），后台维度由 AppOps 单独控制，
      权限 GRANTED 只覆盖前台资格，后台还要 AppOps 放行。
修复（调试用，非用户路径）：
  appops set com.example.app ACCESS_FINE_LOCATION allow   # 但这通常应走用户设置"始终允许"
```

这就是"两层关系"最经典的故障：**Permission 过了，AppOps 没过**。

---

---

## 5. 特殊权限：不是 dangerous 的"危险权限"

有四类权限**不属于 `dangerous`**，不走普通运行时弹窗，而是"**设置页开关 + AppOps op**"双层判定。App 侧写法见 `androidApp/08`，本篇讲 Framework 的判定与开关落点。

### 5.1 总览对照

| 权限 | protectionLevel | 授权入口 | 背后 AppOps op | 特点 |
|---|---|---|---|---|
| `SYSTEM_ALERT_WINDOW`（悬浮窗） | `signature|appop`/special | 设置页"在其他应用上显示" | `OP_SYSTEM_ALERT_WINDOW` | 默认 `MODE_DEFAULT` 多展开为 ERRORED；WMS 加 overlay 前查 |
| `WRITE_SETTINGS`（修改系统设置） | `signature|appop`/special | 设置页"修改系统设置" | `OP_WRITE_SETTINGS` | 默认 ERRORED，需用户显式开 |
| `REQUEST_INSTALL_PACKAGES`（安装未知来源） | special | 设置页"未知来源应用" | `OP_REQUEST_INSTALL_PACKAGES` | 每个 App 单独开关 |
| `BODY_SENSORS`（原 OTHER_SENSORS 类） | `dangerous`（组内） | 运行时弹窗 + op `OP_BODY_SENSORS` | `OP_BODY_SENSORS` | 后台体感传感器另受后台限制 |

> 版本差异：`BODY_SENSORS` 在 Android 13+ 进一步拆分为前台/后台体感传感器权限，并新增 `BODY_SENSORS_BACKGROUND`；媒体权限也在 13+ 拆分为 `READ_MEDIA_IMAGES/VIDEO/AUDIO`（App 篇详述）。本篇是 Framework 视角，记住"这类拆分最终都落到 AppOps op 的粒度细化"即可。

### 5.2 悬浮窗：SYSTEM_ALERT_WINDOW 双层判定

```text
App 想 addView 一个 TYPE_APPLICATION_OVERLAY 窗口
  └─ WMS.addWindow
       ├─ 查 Permission: SYSTEM_ALERT_WINDOW 是否 GRANTED（special 类）
       └─ 查 AppOps: OP_SYSTEM_ALERT_WINDOW mode
            MODE_ALLOWED → 放行
            MODE_ERRORED / DEFAULT(展开ERRORED) → 抛 BadTokenException / 拒绝
```

注意**两个容易混的点**：

- 某些签名/特权 App（系统 UI、车机 Launcher）通过 `signature` 直接拿 `SYSTEM_ALERT_WINDOW`，跳过设置页；但即便如此，AppOps 仍可单独 IGNORED 它。
- 普通 App 即使 `pm grant` 拿到权限，若 AppOps 是默认 ERRORED，仍然加不了窗。排查"悬浮窗加不上"先看 `appops get <pkg> SYSTEM_ALERT_WINDOW`。

### 5.3 修改系统设置 WRITING_SETTINGS

`Settings.System` / `Settings.Global` 的写入最终在 `SettingsProvider` 里查 `OP_WRITE_SETTINGS`。用户没在设置页开"修改系统设置"，op 默认 ERRORED，写入静默失败或抛 `SecurityException`。车机场景里，系统设置类预装件常被平台默认授予此 op（见 §2.2）。

### 5.4 安装未知来源 REQUEST_INSTALL_PACKAGES

每个 App 在设置页有独立开关。Framework 侧在 `PackageInstaller` / `AppOpsService` 查 `OP_REQUEST_INSTALL_PACKAGES`。**这层与 `signature` 无关**——即使系统签名 App，若 op 被设 IGNORED，调起安装也会被拦。车机 OTA/应用商店类预装件需要此 op 为 ALLOWED。

---

## 6. 签名类权限进阶：signature|privileged 的白名单校验链路

这是车机/ROM 开发者最关心的硬骨头：`privileged` 权限不是"放到 priv-app 就自动全有"，而要过 `privapp-permissions` 白名单校验。

### 6.1 三态开关：ro.control_privapp_permissions

系统属性 `ro.control_privapp_permissions` 决定 PMS 对 priv-app 白名单的**执法强度**：

| 值 | 行为 | 适用 |
|---|---|---|
| `enforce` | 严格：priv-app 申请的 privileged 权限**必须**在白名单里，否则**拒绝授予**，启动/安装报错 | 量产 ROM（必须） |
| `log` | 只打 log 不拒绝；缺白名单的权限会打印警告 | 调试、补白名单阶段 |
| `disable` | 完全不校验（所有 priv-app 自动拿全 privileged 权限） | 仅早期开发，绝不可量产 |

> 「**Android 视角 / 车机落地**」：**量产必须 `enforce`**。曾经有厂商为省事用 `disable`，结果 CTS 不过、且任意 priv-app 都能拿全部特权，安全审计直接挂。踩坑呼应：预装一个新 priv-app 后开机发现功能异常，第一反应是 `getprop ro.control_privapp_permissions` 是否为 `enforce`，再查白名单（§8）。

### 6.2 白名单加载链路：privapp-permissions xml → SystemConfig → PMS enforce

```text
开机 / 扫描 priv-app 时：
  SystemConfig.readPrivAppPermissions
    读取各分区下：
      /system/etc/permissions/privapp-permissions-*.xml
      /vendor/etc/permissions/privapp-permissions-*.xml
      /product / system_ext ... 同结构
    解析出： <privapp-permissions package="com.xxx">
              <permission name="android.permission.READ_PRIVILEGED_PHONE_STATE"/>
            </privapp-permissions>
    → 存入 SystemConfig 的 mPrivAppPermissions (package -> Set<perm>)
        │
        ▼
  PermissionManagerService.grantPermissionsForPackageLocked
    若该 pkg 是 priv-app 且权限是 privileged：
      查白名单集合是否包含此 (pkg, perm)
        ├─ 命中  → 授予
        └─ 未命中 → 依据 ro.control_privapp_permissions：
              enforce → 拒绝授予 + Slog.w 警告（privapp 缺失）
              log     → 仅警告
              disable → 照授
```

> 交叉引用：`SystemConfig` 的读取机制、分区 `etc/permissions` 加载顺序详见 `androidOthers/构建系统篇`（本篇不展开"文件怎么打进镜像"，只讲"PMS 怎么校验它"）。

### 6.3 sharedUserId 与权限的合并语义

`sharedUserId`（已废弃但大量旧系统 App 仍用）让多个包共享同一个 uid。权限语义随之变化：

- **授予侧**：只要共享 uid 中**任一包**被授权某权限，同 uid 其他包通过 `checkUidPermission` 也"有资格"（因为查的是 uid）。
- **白名单侧**：privileged 白名单是**按 package 维度**校验的。共享 uid 的 A 包有白名单、B 包没有，则 B 包申请同一 privileged 权限时仍会因"B 不在白名单"被拒——即使它们同 uid。这是预装踩坑高发点：**共享 uid 的每一个包都要各自进白名单**。
- 撤销侧同理：撤一个包的权限可能影响同 uid 其他包。

> 「**Android 视角**」：Android 10+ 强烈建议用 `android:process` + 显式签名/权限共享替代 `sharedUserId`，新 App 已不能声明 `sharedUserId`（targetSdk 限制）。但车机存量系统 App 多仍依赖它，预装补白名单时务必把同 uid 所有包都覆盖。

---

## 7. 平台 / 车机定制视角：白名单语法与补白名单实战

### 7.1 privapp-permissions.xml 语法逐行

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 文件名约定：privapp-permissions-<你的模块名>.xml，放对应分区 etc/permissions -->
<privapp-permissions package="com.oem.vehicle.control">
    <!-- 单行声明一个特权权限 -->
    <permission name="android.permission.READ_PRIVILEGED_PHONE_STATE"/>
    <permission name="android.permission.MANAGE_USB"/>
    <!-- 允许"该包不申请却声明了"的权限被忽略（deny-list 反向用法，较少） -->
    <!-- 不要写 <deny-permission>，privileged 默认是白名单准许制 -->
</privapp-permissions>

<!-- 一个文件可含多个 package 块 -->
<privapp-permissions package="com.oem.voice.assistant">
    <permission name="android.permission.ACCESS_FINE_LOCATION"/>
    <permission name="android.permission.RECORD_AUDIO"/>
</privapp-permissions>
```

语法要点：

- 根标签必须 `privapp-permissions`，且带 `package` 属性（**按包**）。
- 子标签 `<permission name="..."/>` 逐一列出该包允许持有的 privileged 权限。
- 缺一个权限 = 该权限在 `enforce` 下**不被授予**（不是整包失败，是该权限拿不到）。
- 文件放哪：`/system/etc/permissions/`、`/vendor/etc/permissions/`、`/product`、`/system_ext` 等，PMS 都会扫。车机 OEM 私有件通常放 `/vendor/etc/permissions/`，与 AOSP system 分区解耦。

### 7.2 给预装应用补白名单的完整案例（5 步清单）

> 现象：新预装一个 priv-app `com.oem.x`，开机后某项特权功能（如读特权电话状态）失效，`logcat` 出现 `Privileged permission ... not in privapp-permissions`。

**第 1 步：收集缺失权限**
```bash
# 看该包实际请求了哪些 privileged 权限
adb shell dumpsys package com.oem.x | grep -i "privileged"
# 或开机 log 直接给缺失清单
adb logcat | grep -i "privapp"
```

**第 2 步：写 xml**
按 §7.1 语法，把第 1 步收集到的 privileged 权限全部列进 `privapp-permissions-com.oem.x.xml`。

**第 3 步：放对打包路径**
- 代码仓里放 `device/oem/<device>/permissions/privapp-permissions-com.oem.x.xml`；
- 通过 `PRODUCT_COPY_FILES` 或 `prebuilt_etc` 打进 `/vendor/etc/permissions/`；
- （具体 mk/bp 写法见 `androidOthers/构建系统篇`，本篇只确认"落点是 vendor 分区的 etc/permissions"）。

**第 4 步：验证**
```bash
# 确认文件已在设备对应分区
adb shell ls -l /vendor/etc/permissions/privapp-permissions-com.oem.x.xml
# 重启后看是否还报缺失
adb logcat | grep -i "privapp"   # 应无新警告
# 确认权限已授予
adb shell dumpsys package com.oem.x | grep -A2 "READ_PRIVILEGED"
```

**第 5 步：enforce 检查命令**
```bash
adb shell getprop ro.control_privapp_permissions   # 量产须为 enforce
# enforce 下，凡未列入白名单的 privileged 权限都会被拒，
# 此时再跑第 4 步的 dumpsys 应确认目标权限 granted=true
```

### 7.3 vendor 私有权限（custom permission）实践

车机常需"OEM 自定义特权能力"只授给自家签名/预装件：

```xml
<!-- 在 framework 或 OEM 资源里定义权限 -->
<permission android:name="com.oem.permission.VEHICLE_CONTROL"
    android:protectionLevel="signature|privileged"
    android:label="Vehicle Control"/>

<!-- 授权：只有同签名 + priv-app 且进白名单的包能拿 -->
<privapp-permissions package="com.oem.vehicle.control">
    <permission name="com.oem.permission.VEHICLE_CONTROL"/>
</privapp-permissions>
```

要点：

- 自定义权限的 `protectionLevel` 用 `signature|privileged`，既保证"同 OEM 签名"，又保证"仅 priv-app 白名单可控"。
- **跨 App 授权**：车机里"车辆控制 App A"要把能力借给"语音助手 B"时，B 也必须同签名 + 进白名单，否则 `checkUidPermission` 在 B 的 uid 上仍 denied。
- 定义权限的包（声明方）和申请方都要用**同一 OEM 签名**，`signature` 比对才会过（§2.1）。

---

## 8. 权限自动回收与休眠：auto-revoke / hibernation

Android 11（API 30）引入"未使用 App 的权限自动回收"，与 App Standby 体系联动。

### 8.1 auto-revoke（自动回收）

- 触发：App **长时间未使用**（具体阈值由 `AppStandbyController` 判定进入闲置/rare bucket，见 `16_后台任务机制详解`）且非活跃。
- 行为：`PermissionManagerService` 把该 App 的**所有运行时危险权限**回收到 revoked，等同用户撤销。
- 用户可在设置里关掉某 App 的"如果未使用则移除权限"。
- 系统/特权/设备策略固定的权限（`policy_fixed`）**不参与** auto-revoke。

### 8.2 app hibernation（应用休眠）

比 auto-revoke 更重：长期未用的 App 进入 hibernation 后，除回收权限外，还会：

- 停止后台 jobs / alarms / 网络访问；
- 权限状态标记 `revoked`（与 auto-revoke 共享判定入口）。

### 8.3 与 standby bucket 的关系

```text
AppStandbyController 按使用频次把 App 分到 bucket：
  ACTIVE / WORKING_SET / FREQUENT / RARE / RESTRICTED
      │
      ▼ RARE / RESTRICTED 且长期未交互
  PermissionManagerService.AutoRevoke / Hibernation 触发
      → 危险权限回收 + 后台受限
```

> 交叉引用：bucket 判定、jobs/alarms 受限机制见 `16_后台任务机制详解`。排查"某 App 权限莫名其妙没了 + 后台不干活"，先怀疑它落到了 RARE/RESTRICTED 被 auto-revoke。

### 8.4 排查命令

```bash
# 强制把某 App 标为未使用 / 触发 auto-revoke 调试
adb shell am set-standby-bucket com.example.app rare
adb shell cmd appops get com.example.app
# 查看 auto-revoke 设置
adb shell dumpsys permission | grep -i "auto-revoke\|unused"
```

---

## 9. 权限分组 UI、一次性授权与后台位置两段式

### 9.1 分组 UI 的 Framework 支撑

运行时授权弹窗以"权限组"为单位（§1.4）。Framework 侧由 `PermissionController` 读取 `PermissionInfo.group` 聚合成组展示；授予时组内权限一次性写入 `runtime-permissions.xml`。撤销任一权限时，同组已授权项一起重算（以单权限维度落盘）。UI 分组 ≠ 能力分组，再次提醒。

### 9.2 一次性授权（one-time）：Android 11+ 的 AppOps 实现

"仅这次允许"是**用 AppOps 的超时回收**实现的，而不是单纯 Permission 状态：

```text
用户选"仅这一次"
  └─ PermissionManagerService 标记该权限 granted（临时）
  + AppOpsService 对该 op 设一个"前台会话有效期"
       └─ 当 App 离开前台 / 超时（约数分钟，进程退到后台并冷却）
            → AppOps 把该 op 回收为默认/撤销态
            → 下次再进 App 又需重新授权
```

记忆点：**一次性授权的"过期"由 AppOps 计时驱动**，不是 Permission 自己计时。dump 时能看到对应 op 的有效期/最近使用时间。

### 9.3 后台位置两段式（background access 链路）

Android 10+ 把"位置"拆成前台/后台两个维度，是双层机制的典型：

```text
前台拿位置：
  permission ACCESS_FINE_LOCATION granted (dangerous, 用户授权)
   + AppOps OP_FINE_LOCATION mode ALLOWED (前台)
   → LocationManager 返回位置

后台还想拿：
  需额外"始终允许"维度：
   AppOps OP_FINE_LOCATION 的 background 子模式 == ALLOWED
   （用户设置页选"始终允许"，而非"仅在使用中允许"）
  若仅"使用中允许" → 后台维度 mode = IGNORED → 后台拿不到
```

排查"前台能定位、后台不能"直接看：

```bash
adb shell appops get <pkg> ACCESS_FINE_LOCATION
# 关注 background 维度的 mode
```

---

---

## 10. dumpsys 解读：权限与 AppOps 的现场取证

排查时 80% 的信息来自 `dumpsys`。本节逐字段读法。

### 10.1 dumpsys package <pkg> 关键段

```bash
adb shell dumpsys package com.example.app
```

重点看：

```text
requested permissions:          # App 在 Manifest 声明申请的全部权限
  android.permission.CAMERA
  android.permission.ACCESS_FINE_LOCATION
install permissions:            # 安装即定的（normal/signature 等），带 flags
  android.permission.INTERNET: granted=true, flags=[]
runtime permissions:            # 运行时危险权限，含状态与来源
  android.permission.CAMERA: granted=true
      (user fixed=false, policy fixed=false)
  android.permission.ACCESS_FINE_LOCATION: granted=false
      (user fixed=true)         # 用户选了"不再询问"
```

逐字段：

| 字段 | 含义 |
|---|---|
| `requested permissions` | 声明了什么，不看授予 |
| `install permissions` | 安装期授予（normal/signature），`granted` 直接为 true |
| `runtime permissions` | dangerous 状态；`user fixed`=不再询问；`policy fixed`=策略锁死不可改 |
| `unknown sources` / `app flags` | 安装来源、是否 priv-app（`privileged` 标记） |

### 10.2 dumpsys appops <pkg> 条目读法

```bash
adb shell dumpsys appops com.example.app
```

输出每个 op 的：

```text
Uid 10087:
  Package com.example.app:
    OP_FINE_LOCATION (0, 0): mode=allow  time=+2h (fg)  rejectCount=0  allowCount=12
    OP_SYSTEM_ALERT_WINDOW (0, 0): mode=ignore  time=-  rejectCount=3
    OP_CAMERA: mode=default  # default 会按 op 默认表展开
```

字段含义：

| 字段 | 含义 |
|---|---|
| `mode` | 当前 op 模式（allow/ignore/errored/default/foreground…） |
| `time` | 上次该操作发生的时间（前台/后台标记） |
| `acceptCount` / `allowCount` | 被允许次数（审计） |
| `rejectCount` | 被拒绝次数（审计，ignore/errored 都会累计） |

> 排查"功能偶发失效"看 `rejectCount` 是否在增长——能直接证明是 AppOps 在拦，而非业务逻辑。

### 10.3 pm list permissions 用法

```bash
# 按组列出所有权限
adb shell pm list permissions -g
# 只看危险权限
adb shell pm list permissions -g -d
# 查看某权限的声明信息
adb shell pm list permissions | grep -i camera
```

`pm list permissions` 是"静态清单"视角（有哪些权限、分哪组），适合核对 Manifest 与系统定义是否一致。

---

## 11. 常见问题排查表（现象 | 原因 | 章节 | 第一命令）

| # | 现象 | 原因 | 章节 | 第一命令 |
|---|---|---|---|---|
| 1 | 预装 priv-app 开机某项功能失效，log 报 not in privapp-permissions | privileged 权限未进白名单，enforce 下被拒 | §6, §7 | `adb logcat \| grep -i privapp` |
| 2 | 权限 GRANTED 但功能仍不可用 | AppOps op 被 IGNORED/ERRORED | §4, §5 | `adb shell appops get <pkg> <OP>` |
| 3 | 后台定位拿不到，前台正常 | 后台维度 AppOps 未 ALLOWED（两段式） | §9.3 | `appops get <pkg> ACCESS_FINE_LOCATION` |
| 4 | signature 权限装不上 / 不授予 | 申请方与声明方签名不一致 | §2.1, §7.3 | `adb shell dumpsys package <pkg> \| grep -i signature` |
| 5 | 权限弹窗不弹 / 弹窗样式异常 | PermissionController 被裁剪或替换（Mainline 差异） | §3.3 | `adb shell pm list packages \| grep permissioncontroller` |
| 6 | 多用户下权限状态不同步 | 权限按 userId 落盘，各用户独立 | §3.4 | `dumpsys package <pkg>` 看 user 维度 |
| 7 | auto-revoke 导致行为异常 | App 落入 RARE/RESTRICTED 被回收危险权限 | §8 | `am get-standby-bucket <pkg>` |
| 8 | 运行时权限回调丢失 | 申请期间进程被杀 / 配置变更重启 Activity | §3 | 查 logcat `ActivityManager` 杀进程 |
| 9 | 悬浮窗加不上（BadTokenException） | SYSTEM_ALERT_WINDOW 未授权或 AppOps ERRORED | §5.2 | `appops get <pkg> SYSTEM_ALERT_WINDOW` |
| 10 | 修改系统设置写不进去 | WRITING_SETTINGS op 默认 ERRORED，用户未开 | §5.3 | `appops get <pkg> WRITE_SETTINGS` |
| 11 | 未知来源安装被拦 | REQUEST_INSTALL_PACKAGES op IGNORED | §5.4 | `appops get <pkg> REQUEST_INSTALL_PACKAGES` |
| 12 | 共享 uid 的 B 包拿不到 A 有的特权 | 白名单按 package 校验，B 未单独进名单 | §6.3 | `dumpsys package <b_pkg> \| grep privileged` |
| 13 | 我没授权却已 GRANTED | 平台默认授权（DefaultPermissionGrantPolicy） | §2.2 | `dumpsys package <pkg>` 看授权来源 |
| 14 | 一次性授权很快又要求重授权 | AppOps 超时回收（离开前台/冷却） | §9.2 | `appops get <pkg> <OP>` 看 time |
| 15 | 升级 OTA 后权限被重置/重新授予 | 默认授权策略在升级时重跑（`FIXED` 与否） | §2.2 | 对比升级前后 `runtime-permissions.xml` |
| 16 | 车机预装件后台被限 | 后台 op 维度 + standby bucket 双重限制 | §8, §9 | `appops get <pkg>` + `am get-standby-bucket` |
| 17 | enforce 模式整包特权异常 | `ro.control_privapp_permissions` 非预期值 | §6.1 | `getprop ro.control_privapp_permissions` |
| 18 | 自定义 OEM 权限其他 App 拿不到 | 跨 App 不同签名 / 未进白名单 | §7.3 | `dumpsys package <other_pkg> \| grep <custom_perm>` |

---

## 12. 读源码路线（4 条主线）

> 源码路径见 §13。以下给"从入口追到判定"的最小调用链，跟着打 log 或断点即可。

### 12.1 requestPermissions 全链

```text
Activity.requestPermissions
  → ActivityCompat（App 侧桥接，见 androidApp/08）
  → ActivityManager（binder 到 system_server）
  → PermissionController 授权 Activity（弹窗 UI）
  → PermissionManagerService.grantRuntimePermission
  → AppOpsService（若权限挂 op，同步设 AppOps 默认/允许）
  → 写 runtime-permissions.xml
  → 回调 onRequestPermissionsResult
```

关注点：`grantRuntimePermission` 里的 `checkPermission` 前置校验、对 `policy_fixed` 的拒绝逻辑。

### 12.2 checkUidPermission 的判定树

```text
PermissionManagerService.checkUidPermission(name, uid)
  → 取 uid 对应的 PackageSetting / 共享 uid 集合
  → 解析 PermissionInfo，读 protectionLevel
  ├─ normal        → 直接 ALLOWED
  ├─ signature      → compareSignatures；privileged 还要查白名单(§6.2)
  ├─ dangerous      → 读 runtime-permissions.xml 的 granted 状态
  └─ special/appop  → 往往返回"资格有"，真正放行交给 AppOps / 执行服务
```

关注点：签名比对失败路径、`enforce` 下白名单缺失的处理分支（§6.2）。

### 12.3 privapp 白名单加载与 enforce

```text
SystemServer 启动 → SystemConfig 构造
  → SystemConfig.readPrivAppPermissions 扫各分区 etc/permissions/*.xml
  → 填充 mPrivAppPermissions: pkg -> Set<perm>
PMS 扫描 priv-app 包（见 04_PMS）
  → PermissionManagerService.grantPermissionsForPackageLocked
  → 对每个 privileged 权限查白名单集合
  → 按 ro.control_privapp_permissions 决定授/拒
```

关注点：分区扫描顺序（system → vendor → product → system_ext），同名权限/包冲突时的合并优先级。

### 12.4 AppOps setMode 生效链

```text
AppOpsManager.setMode(op, uid, pkg, mode)   // 命令行 appops set 也走这
  → AppOpsService.setMode (binder)
  → 写 mUidOps 内存结构 + 持久化 /data/system/appops.xml
  → 通知监听者（各系统服务注册了 OpChangedListener）
    例：LocationManagerService 监听到 OP_FINE_LOCATION 变化
        → 立即对受影响 uid 改变位置回调行为
```

关注点：`appops.xml` 的落盘时机（批量/延迟写），以及监听回调如何"即时改变执行行为"——这解释了为什么改了 AppOps 不用重启就生效。

---

## 13. 源码路径速查表

| 内容 | 路径 |
|---|---|
| PermissionManagerService | `frameworks/base/services/core/java/com/android/server/pm/permission/PermissionManagerService.java` |
| 权限注册 / 默认策略 | `frameworks/base/services/core/java/com/android/server/pm/permission/PermissionRegistry.java`、`DefaultPermissionGrantPolicy`（在 PermissionController 模块） |
| AppOpsService | `frameworks/base/services/core/java/com/android/server/appop/AppOpsService.java` |
| AppOpsManager（客户端 API） | `frameworks/base/core/java/android/app/AppOpsManager.java` |
| PackageManagerService | `frameworks/base/services/core/java/com/android/server/pm/PackageManagerService.java` |
| SystemConfig（白名单读取） | `frameworks/base/services/core/java/com/android/server/SystemConfig.java` |
| 内置权限定义 | `frameworks/base/core/res/AndroidManifest.xml` |
| PermissionController（弹窗/默认授权，Mainline） | `packages/modules/Permission/` |
| 旧版 PermissionController（AOSP 树内） | `frameworks/base/packages/PermissionController/`（部分版本） |
| runtime-permissions 落盘 | `/data/system/users/<userId>/runtime-permissions.xml` |
| appops 落盘 | `/data/system/appops.xml` |
| privapp 白名单文件 | `/system/etc/permissions/`、`/vendor/etc/permissions/`、`/product`、`/system_ext` 下同名目录 |

---

## 14. 一图总结 + 关联阅读

### 三层防线 ASCII 复盘

```text
声明(谁有能力)      批准(谁给资格)          执行(这次放不放行)
Manifest             PermissionManager        AppOps / SELinux
uses-permission  →   normal/signature/    →  MODE_ALLOWED
                     dangerous(priv)          MODE_IGNORED/ERRORED
                     + privapp 白名单         + 审计计数
                                                              ↑
                                          SELinux(MAC) 内核强制 见姊妹篇
```

**一句话记忆链**：

> 权限分三段——**Manifest 说你要，PMS 看你够不够格（签名/白名单/用户点不点），AppOps 决定这次让不让你干**；priv-app 的特权必须进白名单且 `enforce`，否则宁可功能失效也不乱授。

### 关联阅读

| 姊妹篇 | 与本篇关系 |
|---|---|
| `04_PMS机制详解` | 包扫描、签名比对、UID 分配、权限安装期判定都在 PMS，本篇只聚焦"权限判定"那一步 |
| `18_SystemUI机制详解` | 授权弹窗由 PermissionController 弹出，部分特权 UI 经 SystemUI；悬浮窗 overlay 由 WMS 经 SystemUI 通道 |
| `16_后台任务机制详解` | App Standby bucket 驱动 auto-revoke / hibernation，与权限回收联动 |
| `20_相机机制详解` | CameraService 执行 `CAMERA` 权限 + `OP_CAMERA` 的双重检查，是双层机制典型案例 |
| `androidOthers/AndroidSELinux机制详解` | 第 3 层 SELinux(MAC) 的强制细节，本篇仅做 DAC↔MAC 一句衔接 |
| `androidOthers/构建系统篇` | `privapp-permissions.xml` 如何打进镜像（mk/bp、分区路径），本篇只讲"PMS 怎么校验" |
| `androidApp/08_应用权限篇` | App 侧 `<uses-permission>` 写法与 `requestPermissions` 调用，本篇是 Framework 视角互补 |

---

> 全文完。建议配合 `04_PMS` 与 `16_后台任务` 一起读：权限是"资格"，AppOps 是"执行开关"，而 auto-revoke 把两者和后台生命周期绑在一起。
