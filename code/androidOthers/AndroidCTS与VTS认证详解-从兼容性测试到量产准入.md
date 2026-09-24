# Android CTS 与 VTS 认证详解——从兼容性测试到量产准入

> 这篇是**清单 / 流程型**文档：主线是"三类测试各测什么 → 怎么跑 → 失败怎么读怎么修 → 怎么进量产"。全篇最有价值的内容是**第 6 章的失败原因清单与修法**，而不是测试框架（tradefed）的源码结构——那是 Google 的事，你只需会跑、会读、会修。
>
> 建议读法：
>
> **第一段 认知（要不要测、测什么）**（1~2 章）→ **第二段 怎么跑与怎么读**（3~5 章）→ **第三段 失败清单与修法（核心）**（6~7 章）→ **第四段 其他套件与量产准入**（8~10 章）→ **第五段 工程化与车机**（11~12 章）→ **第六段 工具箱与排查**（13~15 章）。
>
> 车机内容集中在第 12 章。读完你能回答：我改了 framework / vendor，到底该跑哪套测试、失败日志怎么读、第 6 章里哪一条戳中了你、车机认证为什么周期更长、产线下线检测跟 CTS 不是一回事。

---

## 目录

**第一段：认知——要不要测、测什么**

1. [开场：什么时候会碰到认证](#一开场什么时候会碰到认证)
2. [各测试的定位与边界](#二各测试的定位与边界)

**第二段：怎么跑与怎么读**

3. [CTS 的组织方式](#三cts-的组织方式)
4. [跑一次 CTS](#四跑一次-cts)
5. [CTS 失败怎么读](#五cts-失败怎么读)

**第三段：失败清单与修法（核心）**

6. [CTS 失败的高频原因清单](#六cts-失败的高频原因清单)
7. [CTS-on-GSI：定位问题归属](#七cts-on-gsi定位问题归属)

**第四段：其他套件与量产准入**

8. [VTS：Vendor 接口的契约测试](#八vtsvendor-接口的契约测试)
9. [GTS 与其他套件](#九gts-与其他套件)
10. [认证流程与量产准入](#十认证流程与量产准入)

**第五段：工程化与车机**

11. [把认证工程化](#十一把认证工程化)
12. [车机专章：Automotive 的差异](#十二车机专章automotive-的差异)

**第六段：工具箱与排查**

13. [调试工具箱](#十三调试工具箱)
14. [常见问题排查表](#十四常见问题排查表)
15. [一图总结](#十五一图总结)

**关联阅读** 见文末第 16 节。

---

## 一、开场：什么时候会碰到认证

先泼一盆冷水：**认证不是 Android 开发的必答题，而是量产准入的门票**。下面这张表决定你此刻要不要关心 CTS/VTS。

| 你的场景 | 要不要过认证 | 说明 |
|---|---|---|
| 个人玩机、root 改 framework 自嗨 | 不需要 | 没人查你兼容性 |
| 公司内部测试 ROM、预研样机 | 不需要 | 不对外销售，不预装 GMS |
| 给客户做车机 demo（不进 4S 店销售） | 通常不强制 | 看合同约定，无 GMS 则无 GMS 套件要求 |
| **量产并预装 GMS（Google 移动服务）** | **必须** | 没过 CTS/VTS/GTS，拿不到 GMS 授权，不能预装 Play 商店等 |
| 量产但不带 GMS（中国区部分车机/平板） | 看监管与渠道 | CTS 仍建议跑（保证 App 兼容），但无 GMS 套件强制 |

**一句话记忆**：**带 GMS 量产 = 必须过认证；不带 GMS 的自用/内测 ROM = 不用过**。GMS（Google Mobile Services，含 Play 商店、GMS Core、Google 地图套件等）是 Google 的私有应用与服务，预装它必须签 **MADA（Mobile Application Distribution Agreement，移动应用分发协议）**，而 MADA 的硬性前提就是兼容性测试达标。

「**Android 视角**」很多车机走的是 **AOSP + 无 GMS** 路线（尤其中国区、出口受限地区），此时 GTS 不跑，但 CTS 仍强烈建议跑——因为 CTS 测的是"你改没改坏 framework 行为"，直接关系到车上 App（导航、音乐、语音）能不能正常跑。VTS 则与 Treble 合规绑定，只要做了 vendor/system 解耦就该关心。

**本篇的四个"不要"**：
- 不要沉迷 tradefed 源码——你只需要 `run cts` 和它报的结果；
- 不要背用例数量——Android 每个版本用例都在涨，具体数字**以当前版本官方文档为准**；
- 不要假设"测试套件版本 = Android 版本"——每个 Android 版本有对应的 CTS/VTS 发布版，**版本对应关系以官方发布页为准**；
- 不要等量产前两周才第一次跑——认证应该前置到开发早期，否则修不完。

---

## 二、各测试的定位与边界

四个套件（CTS / VTS / GTS / STS）管的东西完全不同。先建立坐标系，再给"我改了 X 该跑哪个"的对照表。

### 2.1 四个套件各管什么

| 套件 | 全称 | 测什么 | 谁必须跑 | 核心目标 |
|---|---|---|---|---|
| **CTS** | Compatibility Test Suite | framework / App 层 **API 行为一致性** | 所有量产设备（含无 GMS 也建议） | 保证 App 写出来在哪台设备行为一致 |
| **CTS-Verifier** | CTS 验证器 | 需要人工/硬件参与的用例（相机、传感器、蓝牙、USB） | 同 CTS | CTS 自动跑不了的"手动题" |
| **CTS-on-GSI** | 在 GSI 上跑 CTS | 用 Google 官方 Generic System Image 跑 CTS，隔离"是你改坏的还是 AOSP 的问题" | 排查时 | 问题归属判定（见第 7 章） |
| **VTS** | Vendor Test Suite | **Vendor Interface** 与 **HAL 契约**（AIDL/HIDL）、内核/驱动相关模块 | 做了 Treble 解耦的设备 | 保证 vendor 实现不破坏 HAL 接口契约 |
| **GTS** | GMS Test Suite | GMS 应用集成、GMS 行为、MADA 合规项 | **仅带 GMS 的设备** | Google 对"你能不能预装我全家桶"的验收 |
| **STS** | Security Test Suite | 安全补丁级别（Security Patch Level）对应的修复是否真的生效 | 关注安全合规的设备 | 验证 CVE 修复不是"改了版本号没改代码" |

**关键边界认知**：
- **CTS 测"上层行为"**：你的 system/framework 改得对不对，App 调 API 拿到的结果符不符合 Android CDD 定义的行为。
- **VTS 测"下层契约"**：你的 vendor 实现（HAL）有没有违反 Google 定的接口契约——这是 **Treble（系统/vendor 解耦）** 的配套保障。Treble 让 system 能独立升级而不要求 vendor 跟着改，VTS 就是守住这条边界的测试。
- **GTS 测"Google 的私货"**：没有 GMS 授权就跑不了也别跑，属于商业协议范畴。
- **STS 测"安全补丁真话"**：有的厂商把 `ro.build.version.security_patch` 往上填了，代码其实没合，STS 一跑就露馅。

### 2.2 "我改了 X，该跑哪个"对照表

这是日常最高频的一张表——**改完代码，先判断要不要跑、跑哪套**。

| 你改 / 定制的东西 | 优先跑 | 原因 |
|---|---|---|
| 改了 `frameworks/base` 的 API 行为、默认设置 | **CTS** | 直接动到 framework 行为 |
| 删了 / 改了系统预装 App、默认 App | **CTS + CTS-Verifier** | 默认能力缺失会触发用例失败 |
| 改了 SELinux policy（sepolicy） | **CTS + VTS** | 权限域变化影响 framework 与 HAL 行为 |
| 改了 vendor 里的 HAL 实现（camera/audio/gnss…） | **VTS** | HAL 契约可能被破坏 |
| 升级 / 替换 VNDK 版本 | **VTS** | VNDK 与 vendor 接口强绑定 |
| 动了 build.prop 的 `ro.product.*` / `ro.build.*` | **CTS** | 大量用例读这些属性做断言 |
| 改了 GMS 集成、Play 服务、GMS 权限 | **GTS** | GMS 行为验收 |
| 合入了安全补丁、改了 security_patch 版本 | **STS** | 验证补丁真实生效 |
| 车机多屏 / 驾驶模式 / 多用户改动 | **CTS（含 automotive 用例）+ CTS-Verifier** | 见第 12 章 |
| 只改了 App 层（非系统 App） | 通常不跑整机 CTS | 系统行为没动，自测即可 |

**推论**：做车机 vendor 端定制的你，日常最容易被 CTS 和 VTS 同时咬——因为 framework 和 HAL 你两边都可能动。第 6 章和第 8 章就是为你准备的。

---

## 三、CTS 的组织方式

跑之前先懂"CTS 内部怎么组织"，否则 `--module`、`--abi`、`--retry` 这些参数会像天书。

### 3.1 三个核心概念

| 概念 | 含义 | 类比 |
|---|---|---|
| **Test Module（测试模块）** | 一组相关用例的打包（如 `CtsWindowManagerDeviceTestCases`） | 一门考试里的一个科目 |
| **Test Case（测试用例）** | 模块里最小的执行单元（一个 `@Test` 方法） | 科目里的一道小题 |
| **Test Plan（测试计划）** | 一组模块的集合，决定 `run cts` 默认跑哪些 | 整张考卷的选题范围 |

`run cts`（不带参数）会跑**完整计划**，耗时数小时到一天以上，取决于设备与用例规模。日常开发绝不该每次都跑全量。

### 3.2 常见测试计划（以官方包内目录为准）

CTS 包解压后通常在 `android-cts/` 下，包含多个子计划。常见组成（**具体计划名随版本变化，以你下载的套件为准**）：

| 计划 / 目录 | 内容 | 何时用 |
|---|---|---|
| `cts` | 主机端框架主计划 | 标准整机认证 |
| `cts_instant` | Instant App 相关用例 | 支持免安装应用的设备 |
| `cts_verifier` | 手动验证 APK（需人在设备上点） | CTS 自动不了的硬件/交互题 |
| `cts-common` / `testcases` | 模块与依赖的仓库 | 被上面计划引用 |

「**Android 视角**」CTS-Verifier 是一个单独的 APK，需要**人拿着设备做交互**（比如转一下屏幕看传感器、插拔 USB 看枚举）。自动化流水线跑不了它，只能手工过或写半自动脚本辅助。量产提交时它的结果也要一并上传。

### 3.3 设备要求与 setup

跑 CTS 前设备要处于"干净可比"的状态，否则结果不可信：

```bash
# ── 设备准备（每次跑前核对）─────────────────────────
adb devices                                  # 确认只连了一台，多台会抢设备
adb root                                     # CTS 需要 root 才能设属性/读状态
adb shell settings put global verifier_verify_adb_installs 0   # 关掉安装校验加速
adb shell settings put global package_verifier_enable 0
adb install -r android-cts/repository/testcases/CtsVerifier.apk  # 装验证器（手动用例）

# ── 设备需满足（CTS 前置假设）──────────────────────
# · 已恢复出厂 / 干净用户 0
# · 屏幕常亮且未锁屏（开发者选项：保持唤醒）
# · 时区设为太平洋时间（部分时区相关用例假设），语言英文
# · 已登录 Google 账号（GMS 相关用例需要，仅当跑 GTS/部分 CTS）
# · 剩余电量 > 50% 或接电源（防止中途关机）
```

**坑**：CTS 对"设备初始状态"极其敏感。你本地过了，换台没清数据的样机就挂——多半是脏数据/残留账号导致，**不是代码问题**。量产提交流程要求提交前先恢复出厂。

---

## 四、跑一次 CTS

### 4.1 环境准备

```bash
# ── 宿主机（Linux 推荐，Windows 需 WSL/虚拟机）─────
java -version                       # 需要特定 JDK 版本，以官方文档为准
adb version                         # platform-tools 版本需匹配套件要求
unzip android-cts-*.zip -d ~/cts   # 解压套件

# ── 进入 tradefed 控制台 ───────────────────────────
cd ~/cts/android-cts
./tools/cts-tradefed               # 启动后进入 tradefed 交互 shell
# 进入后提示符变成 tradefed > ，下面命令都在里面敲
```

「**Android 视角**」**tradefed**（Trade Federation）是 Google 的测试执行框架，CTS/VTS/GTS 都跑在它上面。`cts-tradefed` / `vts-tradefed` 只是带不同配置的启动脚本。你**不需要读它源码**，只要会敲几条命令。

### 4.2 最常用的跑法

```bash
# 完整跑（认证提交用，耗时数小时起）
tradefed > run cts

# 指定 ABI（32 位还是 64 位；车机多为 arm64-v8a）
tradefed > run cts --abi arm64-v8a

# 只跑某个模块（开发期最高频：改完立刻验证）
tradefed > run cts --module CtsWindowManagerDeviceTestCases

# 只跑一个用例（定位单点失败）
tradefed > run cts --module CtsFooTestCases --test com.example.FooTest#testBar

# 分片：多设备并行，缩短总时长（N 台设备一起跑）
tradefed > run cts --shard-count 4

# 排除已知无关模块（加速回归）
tradefed > run cts --exclude-filter CtsKnownBrokenModule
```

**`--shard-count` 的本质**：把用例集切成 N 份，分配到当前连接的 N 台设备并行执行。车机认证用例多，**分片是量产前赶进度的利器**——但要求你手头有同型号多台设备。

### 4.3 重跑失败用例（--retry）

完整跑挂了几十个用例，**不要从头再跑**，用 session 重跑只失败的：

```bash
# 第一次完整跑会打印 session id，例如：
#   Completed with 12345 passed / 37 failed / 2 modules not done
#   Session 0 (or a number) ...

tradefed > l r                       # list results，看历史 session
tradefed > run cts --retry <session_id>     # 只重跑该 session 的失败项
```

**为什么一定要 retry 而不是直接再 run cts**：retry 会复用原 session 的"失败清单"，只补跑失败项，几分钟到几十分钟搞定；重新全量跑则是几个小时。但注意——**retry 前若改了代码/配置，要先确认改动真的影响那些失败项**，否则白跑。

### 4.4 结果产物在哪

```bash
# 结果目录结构（以实际解压路径为准）
android-cts/
 ├─ results/<YYYY.MM.DD.HH.MM.SS>/     # 每次跑一个时间戳目录
 │   ├─ test_result.xml                # ★ 机器可读的逐用例结果（必看）
 │   ├─ test_result_failures.html      # 失败汇总（人看）
 │   ├─ invocation_summary.txt         # 概览
 │   └─ ...
 ├─ logs/<同时间戳>/                    # 该次运行的 logcat / tradefed 日志
 │   ├─ logcat-<device>.txt.gz
 │   └─ tradefed_log_*.txt
 └─ repository/testcases/              # 所有模块 apk 与配置
```

**三个你一定会反复打开的文件**：
- `test_result.xml`：用 grep / 解析工具看每个 case 的 `pass/fail`，失败项带 `stackTrace`；
- `test_result_failures.html`：浏览器打开，按模块看红绿；
- `logs/` 下的 `logcat-*.txt.gz`：**失败用例对应的设备日志**，定位"为什么挂"的关键证据（见第 5 章）。

---

## 五、CTS 失败怎么读

很多人一看到满屏红就慌。其实 CTS 失败分三类，先分类再动手，能省掉 80% 的无用功。

### 5.1 失败的三类归属

| 类别 | 含义 | 你的处置 |
|---|---|---|
| **真 bug（real failure）** | 你的改动真的让 API 行为偏离 CDD | 必须修代码/配置 |
| **测试环境问题（environment）** | 设备状态不对、资源不足、网络/账号缺失导致 | 清数据、重 setup、retry |
| **不适用（not applicable / waive）** | 该用例对本设备形态不适用，需申请豁免 | 走 waiver 流程（第 10 章） |

**第一个动作永远是先看失败归类，而不是改代码**。下面给方法论。

### 5.2 结果文件结构怎么读

```bash
# test_result.xml 里每个用例大概长这样（节选）
# <TestCase result="fail" name="FooTest">
#   <Test result="fail" name="testBar">
#     <StackTrace>junit.framework.AssertionFailedError: expected 1 but was 0
#       at com.android.cts.foo.FooTest.testBar(FooTest.java:42) ...</StackTrace>
#   </Test>
# </TestCase>

# 快速统计失败模块与数量
grep -c 'result="fail"' android-cts/results/*/test_result.xml
# 提取失败用例名（去重），拿到清单再去对应模块
grep -oP 'name="\K[^"]+(?="[^>]*result="fail")' android-cts/results/*/test_result.xml | sort -u
```

### 5.3 如何单跑一个用例拿完整堆栈

全量跑的 logcat 是混合的，难定位。先 retry 到只跑那个失败用例，再单独抓它的 log：

```bash
# 只跑这一个问题用例，日志干净好读
tradefed > run cts --module CtsFooTestCases --test com.android.cts.foo.FooTest#testBar

# 另开一个终端，边跑边抓该设备的完整 logcat（带 buffer）
adb logcat -b all -v threadtime > ~/cts_debug/logcat_full.txt
# 跑完后在里面搜用例名 / 报错类名，往往能看到 framework 抛的原始异常
```

**关键技巧**：CTS 失败有时是"断言期望值"和"设备实际值"不一致。完整堆栈里一定会出现 `expected X but was Y` 或 `AssertionFailedError`——**X 是 CDD 规定该有的行为，Y 是你的设备给的**。`Y` 为什么是那个值，就是你排查的入口（属性被改？默认 App 缺失？权限没给？）。

### 5.4 区分"我们改坏了"与"测试假设了某硬件能力"

这是车机/定制 ROM 最常踩的坑：**很多 CTS 用例假设设备具备某些硬件能力（camera、蓝牙、NFC、指纹），如果你的硬件没有，用例失败是"不适用"而非"你改坏了"**。

判断方法：

```bash
# 看设备到底声明了哪些硬件特性（测试就是读这个）
adb shell pm list features                 # 列出设备声明的 feature
adb shell getprop ro.build.characteristics # 设备形态：automotive / tv / watch / default
adb shell cat /system/etc/permissions/*.xml | grep -i "feature"   # feature 声明来源
```

- 如果用例要求 `android.hardware.camera` 而你的 `pm list features` 里没有它 → **设备不该声明也不该被要求**，属于配置/声明问题（要么补硬件声明一致性，要么 waiver）；
- 如果设备**声明了** camera 但用例失败 → 是你的 HAL/framework 真没实现好 → 真 bug。

「**Android 视角**」feature 声明来自 `/system/etc/permissions/` 下的 xml（如 `android.hardware.camera.xml`）。车机常常"声明了某 feature 但硬件/实现不全"，这是 CTS 失败的温床——**声明即承诺，承诺就要过测**。要么补齐实现，要么撤掉声明（并在 CDD 允许范围内）。

---

## 六、CTS 失败的高频原因清单

**这是全篇最重要的章节**。下面 18 条是定制 ROM / 车机量产中最常戳中的失败根因，按"现象 → 根因 → 修法"给出。**每条都来自真实量产踩坑，优先级从高到低**。

### 6.1 预装与默认组件类

**① 默认 App 未预装 / 被替换**
- 现象：`CtsDefaultApp*` 或 `PackageManager` 相关用例失败，报"expected default X but found none / found Y"。
- 根因：CDD 要求某些能力有默认实现（浏览器、短信、通话、桌面、输入法等），你砍掉了或换成了非系统预期的实现。
- 修法：在 `PRODUCT_PACKAGES` / `PRODUCT_SYSTEM_DEFAULTS` 里补齐默认 App，或用 `DefaultAppPreference` 机制正确设置默认值。车机若确实无电话能力，需确保相关 feature 不声明（见 5.4）。

**② 缺失平台签名（platform signature）**
- 现象：依赖 `android.uid.system` 或 `signatureOrSystem` 权限的用例失败，或系统 App 装不上/行为异常。
- 根因：你的系统 App 用了 `android:sharedUserId="android.uid.system"` 或申请了签名级权限，但**签名不是该 build 的 platform key**。
- 修法：用对应 product 的 `platform.pk8` / `platform.x509.pem` 重新签名；确认 `build/target/product/security/` 的密钥与设备实际刷入的一致。**量产换密钥必须同步所有签名 App**。

**③ privileged permission 白名单缺项**
- 现象：`PermissionController` / `PrivilegedPermission` 相关用例失败，报某权限未在白名单。
- 根因：`/system/priv-app` 下的 App 申请了 `signature|privileged` 权限，但没在 `privapp-permissions-*.xml` 里声明允许。呼应 **PMS 篇（androidFrameworks/04_PMS）**：privileged App 的权限不再默认全给，必须显式白名单。
- 修法：在 `etc/permissions/privapp-permissions-<yourapp>.xml` 增加该权限条目，并确认文件被打包进 `/system/etc/permissions/`。

### 6.2 权限与默认值类

**④ 权限默认值被改**
- 现象：某权限默认 `granted`/`denied` 状态与 CDD/参考实现不符，用例断言失败。
- 根因：在 `frameworks/base` 或 `DefaultPermissionGrantPolicy`（相关控制器一类）里改了某权限的默认授予逻辑。
- 修法：恢复默认授予策略，或确认你的变更符合 CDD 对该权限的默认要求（部分权限必须默认授予给特定系统 App）。

**⑤ 行为差异：时间格式 / 时区 / 语言 / 默认设置**
- 现象：`CtsTextUtils`、`DateFormat`、`Locale`、设置相关用例失败。
- 根因：改了默认语言、默认 24 小时制、默认时区、数字/日期格式；或 locale 数据被裁剪。
- 修法：恢复 Android 默认行为，或在 CDD 允许的定制范围内调整并确认对应用例的容忍度。车机常因"默认中文+24 小时制"踩时区/格式用例，**跑 CTS 时按官方建议把设备设回英文/太平洋时区**。

**⑥ 系统属性被改（ro.product.* / ro.build.*）**
- 现象：大量用例在读属性做断言时失败，或 `Build` 相关用例挂。
- 根因：`ro.product.model`、`ro.product.manufacturer`、`ro.build.version.*` 等随手改了格式/取值，或字段缺失。
- 修法：对照 CDD 与参考实现填全字段；属性是只读的（ro.*），改了要重刷。注意别把 `ro.build.version.security_patch` 填错（见 STS 章）。

### 6.3 显示与硬件假设类

**⑦ 屏幕尺寸 / 分辨率 / 密度（density）假设**
- 现象：UI 布局、壁纸、多窗口、截屏相关用例失败。
- 根因：车机多为异形屏 / 横屏 / 超大密度，`config_density`、最小宽度 `sw<N>dp` 等被改，或 `ro.sf.lcd_density` 设置与资源不匹配。
- 修法：确保 `frameworks/base/core/res/res/values/config.xml` 里的显示相关 config 与真实硬件一致；横屏设备要正确声明 `screenOrientation` 默认值。

**⑧ SELinux 策略差异**
- 现象：某系统服务/App 行为异常导致用例失败，dmesg 里有 `avc: denied`。
- 根因：改 sepolicy 后，某域缺权限，framework 功能被 SELinux 卡住（间接导致 CTS 失败）。
- 修法：看 `dmesg | grep avc`，按拒绝项补 `allow` 规则，或修正文件上下文 `file_contexts`。**禁止用 `permissive` 全量放行过认证**——那会被 Google 视为违规。

**⑨ 缺少 GMS 或测试依赖的包**
- 现象：GMS 相关用例失败（仅带 GMS 设备）；或某些 CTS 用例依赖特定 provider/服务不在。
- 根因：GMS 没正确预装，或 CTS 依赖的 `android.cts` 相关系统组件被你裁剪。
- 修法：确认 GMS 套件完整且签名正确；别误删 `system` 里 CTS 依赖的系统组件（如 `ExternalStorageProvider`）。

### 6.4 交互与系统行为类

**⑩ 开机向导（Setup Wizard）被改**
- 现象：`CtsDevicePolicy`、`User`、账号相关用例失败，或设备处于"未完成 setup"状态。
- 根因：改了 `SetupWizard` 流程，导致部分系统能力在 setup 完成后才可用，而用例在预期时点断言。
- 修法：保证开机向导走完后设备处于"fully setup"状态；量产提交前手动走完向导。

**⑪ 通知与状态栏改动**
- 现象：`Notification`、`StatusBar`、`SystemUI` 相关用例失败。
- 根因：SystemUI 被大改（自定义通知样式、状态栏图标规则），偏离了 CTS 对通知行为/可见性的假设。
- 修法：保留标准通知通道与行为；定制放在不影响断言语义的层次。

**⑫ 默认输入法 / 字体被改**
- 现象：输入、IME、字体渲染相关用例失败。
- 根因：替换了默认 IME 或字体，导致文本输入/度量行为偏移。
- 修法：确保默认 IME 实现标准输入协议；字体裁剪不破坏必备字形与度量。

**⑬ 音频 / 音量默认值**
- 现象：`Audio`、`Media` 相关用例失败，或断言默认音量/静音状态。
- 根因：改了默认音量曲线、默认静音、或音频焦点行为。
- 修法：恢复 CDD 规定的默认音频状态；车机常因"默认媒体音量 0"踩坑——**出厂默认音量要符合用例假设**。

**⑭ 相机 / 传感器行为差异**
- 现象：`Camera`、`Sensor`、`Location` 相关用例失败。
- 根因：HAL 实现不全或返回了非标准值（如传感器精度、相机分辨率列表、GPS 默认行为）。
- 修法：按 HAL 契约补齐；无该硬件则撤掉 feature 声明（见 5.4）。

### 6.5 设备形态与多用户类

**⑮ 内存 / 性能假设（low RAM device）**
- 现象：部分用例因内存不足被跳过或超时失败。
- 根因：设备声明了 `low_ram` 但某些用例假设充足内存，或后台被杀导致断言失败。
- 修法：正确设置 `ro.config.low_ram` 与 `ActivityManager` 相关内存配置；确认是否为"不适用"用例。

**⑯ 多用户 / 工作资料限制**
- 现象：`MultiUser`、`DevicePolicy`、`UserManager` 用例失败。
- 根因：改了多用户最大数、禁用了某些用户类型、或车机定制破坏了工作资料（Work Profile）机制。
- 修法：保留多用户基本能力（CDD 要求）；车机若限制多用户需在 CDD 框架内并走 waiver。

**⑰ build 指纹 / OTA 相关字段不一致**
- 现象：`Build`、`Incremental`、OTA 相关断言失败。
- 根因：`ro.build.fingerprint`、`ro.build.id` 等被随意改动，或与 `build_number` 不一致。
- 修法：用标准 `build/make` 流程生成指纹，保证一致性与可复现。

**⑱ 省电 / 后台限制过于激进**
- 现象：后台任务、JobScheduler、Alarm 相关用例超时或行为不符。
- 根因：Doze / 后台限制策略被改得太狠，导致用例预期的广播/任务未按时触发。
- 修法：恢复标准省电行为，或确认定制不破坏 CTS 假设的后台调度语义。

**小结**：18 条里，**①③④⑨⑪⑬** 是车机/vendor 定制最高频。修的时候记住——**CTS 失败 90% 不是"算法错了"，而是"声明、默认、预装、权限、SELinux 这五件事没对齐"**。

---

## 七、CTS-on-GSI：定位问题归属

你改了一堆，CTS 挂了。问题是：**这到底是 AOSP 本来就有的问题，还是你改出来的？** CTS-on-GSI 就是用来回答这个问题的"对照实验"。

### 7.1 GSI 是什么

**GSI（Generic System Image，通用系统镜像）** 是 Google 提供的、符合 CDD 的"纯正" system 镜像。把它刷到你的设备上（保留你自己的 vendor），再跑 CTS：

- **GSI 上 CTS 也挂** → 大概率是 **AOSP/硬件/ vendor 的问题**（或用例本身在你这形态不适用），不是你 framework 改的；
- **GSI 上 CTS 过了，你的 system 挂** → **100% 是你改坏了 framework**，回去查第 6 章。

### 7.2 操作流程

```bash
# 1) 下载与你设备 treble 类型匹配的 GSI（如 aosp_arm64 / aosp_x86）
#    类型由 ro.treble.enabled + 架构决定，以官方 GSI 发布页为准

# 2) 解锁并刷 GSI（保留 vendor / boot）
fastboot erase system
fastboot flash system gsi.img
# 若设备用动态分区：fastboot flash system system.img 改为进 fastbootd 刷
fastboot reboot

# 3) 在 GSI 上跑同样的 CTS 计划
cts-tradefed
tradefed > run cts --module <你失败的模块>

# 4) 对比：你的 system 失败模块 vs GSI 失败模块
#    GSI 也失败 → 问题不在你（考虑 waiver / 上游）
#    GSI 通过   → 问题在你（回第 6 章逐条修）
```

### 7.3 结论判读矩阵

| 你的 system | GSI | 结论 | 行动 |
|---|---|---|---|
| 失败 | 失败 | 非你引入（硬件/上游/不适用） | 走 waiver 或查 vendor/硬件 |
| 失败 | 通过 | **你改坏了 framework** | 第 6 章逐条修 |
| 通过 | 失败 | 你反而修好了（少见） | 保留你的改动，记录差异 |
| 通过 | 通过 | 本来就没问题 | 正常进入量产流程 |

「**Android 视角**」GSI 能跑的前提是你的设备 **Treble 合规**（vendor 与 system 解耦干净）。这正是 VTS 要守住的边界——**VTS 不过，GSI 都刷不上或跑不稳**，所以 VTS 和 GSI 是配套的。

---

## 八、VTS：Vendor 接口的契约测试

VTS 是 vendor 端开发者最该上心的套件。它不关心你的 App 好不好用，只关心**你写的 HAL 有没有遵守 Google 定的接口契约**。

### 8.1 VTS 测什么

| 测试对象 | 说明 | 关联知识 |
|---|---|---|
| **HAL 接口契约**（HIDL / AIDL HAL） | 验证你的 HAL 实现返回的接口、枚举、错误码符合定义 | 衔接 androidOthers NDK 篇、automotive/03-VHAL |
| **VNDK（Vendor Native Development Kit）** | 验证 vendor 用的 native 库版本与 system 提供的一致 | 衔接构建篇 |
| **内核 / 驱动相关模块** | 部分内核接口、sysfs 节点、HAL 相关内核行为 | SELinux 篇 |
| **Treble 合规项** | system-vendor 隔离是否干净 | 见 7.3 |

**一句话**：CTS 保证"上层行为一致"，VTS 保证"下层契约不变"——两个一起守住 Treble 的"system 可独立升级"承诺。

### 8.2 与具体 HAL 的对应关系

```bash
# VTS 模块通常按 HAL 名组织，例如：
#   VtsHalAudioV7_0TargetTest        → audio HAL 7.0
#   VtsHalCameraProviderV2_4         → camera provider HAL
#   VtsHalGraphicsMapperV*/VtsHalGnss* ...
# 跑法（vts-tradefed 启动）：
vts-tradefed
tradefed > run vts                       # 全量
tradefed > run vts --module VtsHalAudioV7_0TargetTest   # 单 HAL
```

**车机高频 VTS 目标**（衔接 automotive/03-VHAL）：
- **VHAL（Vehicle HAL）**：车机独有，定义车身信号（车速、档位、HVAC、灯光）的 AIDL/HIDL 接口。VTS 会验证你的 VHAL 实现是否返回合法的属性 ID 与取值范围。
- **audio HAL**：车机多音区（主驾/副驾/后排独立音量）往往在 audio HAL 上扩展，最容易踩契约。
- **gnss / sensor HAL**：导航与惯性数据来源，实现不全直接挂。

### 8.3 VTS 常见失败归类

| 现象 | 根因 | 修法 |
|---|---|---|
| HAL 返回 `BAD_VALUE` / 未实现的方法 | HAL 接口没补齐 | 实现对应方法或正确返回 `UNSUPPORTED` |
| VNDK 版本不匹配 | vendor 用了与 system 不一致的 VNDK | 统一 VNDK 版本（构建篇） |
| SELinux 拒绝 HAL 访问 | HAL 域缺 allow | 补 sepolicy（呼应 SELinux 篇） |
| 属性/枚举超出定义范围 | 自定义值未走扩展机制 | 用 vendor 扩展属性而非滥用标准枚举 |

**注意**：VTS 对"接口契约"是**强校验**——你不能在 HAL 里悄悄改返回语义。车机想扩展车身信号，要走 VHAL 的 `VendorExtension` 机制，而不是篡改标准属性定义。

---

## 九、GTS 与其他套件

### 9.1 GTS：Google 对"你能不能预装我全家桶"的验收

**GTS（GMS Test Suite）** 只存在于**带 GMS 授权**的设备。它验证 Google 应用（Play 商店、GMS Core、Chrome、YouTube 等）是否正确集成、是否行为合规。没有 MADA（GMS 授权协议）就**既没资格也没必要跑**。

| 维度 | 说明 |
|---|---|
| 前提 | 已签 MADA，已正确刷入 GMS 套件且签名匹配 |
| 环境 | **必须真机 + 联网 + 已登录 Google 账号**，模拟器/无网跑不了 |
| 范围 | GMS 应用集成、GMS API 行为、Google 规定的合规项 |
| 提交 | 结果随 CTS/VTS 一起上传到 Google（第 10 章） |

「**Android 视角**」GTS 失败常见原因：GMS 包版本不匹配、GMS 权限/签名错、设备改动了 GMS 依赖的系统行为（又回到第 6 章那些坑）、网络/账号环境不达标。**GTS 不是你能"修代码"修掉的，更多是集成与配置问题**。

### 9.2 STS：安全补丁真话测试

**STS（Security Test Suite）** 验证设备声明的 `ro.build.version.security_patch`（安全补丁级别）对应的 CVE 修复**真的生效了**，而不是只改了版本字符串。

- 现象：填了高版本 security_patch，但某个 CVE 的 poc 用例仍能复现 → STS 失败；
- 根因：要么补丁没真正合入，要么合入不完整（"假升级"）；
- 修法：确认对应月份的安全补丁完整合入并重新编译。**这是安全合规与招标的硬指标**，别在 security_patch 上弄虚作假。

### 9.3 CDD：哪些是 MUST、哪些可以讨价还价

**CDD（Compatibility Definition Document，兼容性定义文档）** 是 Google 对每个 Android 版本的"合规说明书"，定义了设备**必须（MUST）/ 禁止（MUST NOT）/ 应该（SHOULD）/ 不应（SHOULD NOT）/ 可以（MAY）** 满足的要求。CTS/VTS 就是 CDD 的可执行化。

| 关键词 | 含义 | 不遵守的后果 |
|---|---|---|
| **MUST / MUST NOT** | 强制 | CTS/VTS 用例直接失败，认证不过 |
| **SHOULD / SHOULD NOT** | 强烈建议 | 通常不强制，但可能影响评审 |
| **MAY** | 可选 | 自由实现 |

**实用建议**：把 CDD 当成"验收清单"通读一遍，重点看 MUST 项与你的设备形态（automotive）相关的章节。具体条款**以当前版本官方 CDD 文档为准**，不要凭记忆。

---

## 十、认证流程与量产准入

代码改完、CTS/VTS/GTS 都过了，不等于能卖。还有一道**提交与评审**流程。

### 10.1 提交通道：DCC / ATS

| 名词 | 角色 |
|---|---|
| **DCC（Device Compliance Console）** | Google 提供的**结果提交与审核平台**，厂商把测试报告上传到这里等待 Google 评审 |
| **ATS（Android Test Station）** | Google 提供的**测试执行/管理工具**，用于组织、调度、归档测试运行（与 tradefed 配合） |

> 具体提交入口、账号体系、评审周期等以 Google 官方合作资料（MADA 合作伙伴可获取）为准，本节只讲主干逻辑，不写死具体 URL / 字段。

### 10.2 测试报告的构成

提交给 Google 的不是一个文件，而是一组**可复现 + 可溯源**的产物：

| 内容 | 说明 |
|---|---|
| CTS 结果（含所有 session 与 retry） | `test_result.xml` 等，必须覆盖完整计划 |
| CTS-Verifier 结果 | 手动用例的通过记录（需人工确认） |
| VTS 结果 | vendor 接口契约验证 |
| GTS 结果（如适用） | 仅带 GMS 设备 |
| waiver 申请 | 对不适用用例的豁免说明（见 10.3） |
| 设备信息 | `dump device_info` 输出的硬件/软件指纹 |

**铁律**：报告必须来自**恢复出厂后、干净状态**的设备，且版本（build fingerprint）与你要量产的一致。**改一行代码就要重跑相关套件**，不能拿旧报告顶替。

### 10.3 waiver（豁免）：什么能免、什么不能

不是所有失败都要修。CDD 允许对"确实不适用"的用例申请 waiver：

| 可申请 waiver | 不应靠 waiver 掩盖 |
|---|---|
| 设备形态真的不具备某硬件（如车机无通话能力） | 真 bug（framework 被改坏） |
| 用例与你的合法定制明确冲突且 CDD 允许 | 偷懒没实现、或半吊子实现 |
| 上游 AOSP 已知问题（需证明） | security_patch 造假（STS 失败） |

**waiver 的限制**：要写清楚"为什么不适用"，Google 会审；滥用 waiver 会被打回甚至影响授权。**waiver 是"讲道理"，不是"免死金牌"**。

### 10.4 版本与 security patch 的对应关系

- 每个 Android 版本（如 13 / 14）有对应的 CTS/VTS **套件版本**，必须匹配；
- `ro.build.version.security_patch` 必须与实际合入的补丁一致（STS 验证，见 9.2）；
- **认证报告与具体 build 绑定**：量产哪版固件，就提交哪版的报告；
- 具体版本号、用例数量、套件发布节奏**以官方发布页为准**，本文不写死。

### 10.5 认证有效期与回归要求

- 认证**绑定具体 build / 型号**；同一型号换固件通常要重新提交或做回归；
- 安全补丁每月更新，**security_patch 升级需重跑 STS**（至少），大版本升级需重跑全套；
- 量产后若发现需要 OTA 改 framework，**改完必须回归 CTS/VTS**（衔接 OTA 篇）。

---

## 十一、把认证工程化

认证不该是量产前两周的手忙脚乱，而应嵌进 CI/CD，让它"平时就在跑"。

### 11.1 选哪几组接进 CI

全量 CTS 太慢，CI 里跑**关键回归集**即可：

| CI 档位 | 跑什么 | 触发 |
|---|---|---|
| 提交门禁（每次 commit） | 你改动相关的单模块（如改 camera 就跑 `CtsCamera*`） | push / PR |
| 每日回归 | 高频失败模块集合（第 6 章挑出的 TOP N） | nightly |
| 发布前全量 | 完整 CTS + VTS +（GTS） | release tag |

**筛选技巧**：用 `--include-filter` 把"你关心的模块"列成白名单，CI 只跑这些，几分钟到几十分钟出结果，而不是几小时。

### 11.2 灰度 / 量产前的快速回归集

把历史上戳中过你的失败项（第 6 章 18 条对应模块）固化成一个"**认证冒烟集**"：

```bash
# 用 include-filter 组合你的高频模块（示例，模块名以你套件为准）
tradefed > run cts --include-filter CtsPackageInstaller \
                   --include-filter CtsPermission \
                   --include-filter CtsWindowManagerDeviceTestCases \
                   --include-filter CtsAppTestCases
# 这比全量快一个数量级，适合每次 build 后先过一遍
```

### 11.3 失败用例的看板与责任划分

| 失败类别 | 责任方 | 处理 SLA |
|---|---|---|
| framework 行为偏离 | Framework 组（你） | 高优，阻断发布 |
| vendor / HAL 问题 | vendor / BSP 组 | 高优 |
| 环境问题（脏数据/账号） | 测试组 | 重 setup 后 retry |
| 不适用 | 认证负责人 | 走 waiver |

**建议**：把 `test_result.xml` 解析成看板（失败数、模块分布、趋势），每次 build 对比上一次——**失败数是涨还是跌，一眼可见**，避免"最后一次才发现挂了一大片"。

### 11.4 多机型复用的测试计划

同一平台衍生多款车机时，**把"认证冒烟集 + 设备 setup 脚本"模板化**，每款机型只换 `ro.product.*` 与硬件 feature 声明，复用同一套 CI 配置。避免每款车从头搭测试。

---

## 十二、车机专章：Automotive 的差异

车机（Android Automotive OS，AAOS）不是"带屏幕的手机"，认证上有几个独特挑战。

### 12.1 Automotive CTS 的差异点

| 差异 | 手机 CTS 假设 | 车机实际情况 | 应对 |
|---|---|---|---|
| **多屏** | 单屏 | 仪表 + 中控 + 副驾多屏 | 多屏相关用例（Display、MultiDisplay）需正确实现 |
| **多用户 / 多区** | 简单用户 | 车主/乘客/后排独立用户与音区 | UserManager 定制要在 CDD 框架内 |
| **驾驶限制（UX Restrictions）** | 无 | 行驶中禁用视频/复杂交互 | 驾驶模式用例验证限制是否生效 |
| **无触摸 / 无 GMS** | 默认有触摸 + GMS | 旋钮/语音交互、常无 GMS | 相关 feature 不声明 + waiver |
| **电源模型** | 电池 | 常电 + 启停 | 休眠/唤醒、ACC 信号相关用例 |

「**Android 视角**」AAOS 有一套 `CarService` 与 `UX_RESTRICTIONS` 机制，行驶中限制某些操作。CTS 里 automotive 专属用例会验证这些限制**真的生效**（比如行驶中不该弹出视频）。车机若为了"体验"绕过了限制，反而过不了测——而且这关系到安全合规。

### 12.2 车机认证为什么周期更长

1. **用例更多更杂**：手机 + automotive 叠加，且多屏/多用户放大用例组合；
2. **硬件形态多样**：每款车屏幕、芯片、HAL 都不同，GSI 对照实验更难标准化；
3. **VHAL 扩展风险**：车机大量定制在 VHAL，VTS 契约校验更敏感；
4. **无 GMS 也要保证 App 兼容**：没了 GTS，CTS 的"App 兼容"权重更高；
5. **线下与线上割裂**：产线测试 ≠ CTS，但量产节奏要求两者都稳（见 12.3）。

### 12.3 产线测试（PIT）与 CTS 的区别（别混淆）

这是车厂最容易搞混的概念：

| | 产线测试（Production / PIT） | CTS / VTS |
|---|---|---|
| 目的 | 出厂前**每台**查硬件良率（屏、触控、传感器、摄像头） | 验证**型号**的兼容性合规 |
| 范围 | 单板/单机的硬件功能自检 | 系统行为 + vendor 契约 |
| 频率 | 每台车下线必跑 | 每个 build / 认证周期跑 |
| 是否提交 Google | 否 | 是 |

**关键认知**：**产线测试保"这台车没坏"，CTS/VTS 保"这个型号合规"**。两者都重要但不能互相替代。车机不能因为"产线测过了"就跳过 CTS——产线测的是硬件良率，CTS 测的是你改没改坏 Android 行为。

### 12.4 车机量产前的认证清单

- [ ] 恢复出厂 + 干净用户 0 跑通完整 CTS；
- [ ] VTS 全 HAL 通过（重点 VHAL / audio / gnss）；
- [ ] 无 GMS 则确认 GTS 不适用并有记录；
- [ ] STS 对应 security_patch 通过；
- [ ] CTS-Verifier 手动用例人工确认；
- [ ] 不适用项整理成 waiver 并准备理由；
- [ ] 认证冒烟集（11.2）已固化进 CI；
- [ ] 产线 PIT 与 CTS 职责划分清晰（12.3）。

---

## 十三、调试工具箱

日常排查就这几条命令，按用途分组，每条带 `#` 注释说明期望输出。

```bash
# ── tradefed 控制台内 ──────────────────────────────
l r                              # list results：看历史 session 与失败数
l m                              # list modules：当前套件有哪些模块可跑
run cts --module <M> --test <C#t>  # 单跑一个用例，日志干净好读
run cts --retry <session>        # 只重跑失败项
run cts --shard-count 4          # 4 台设备并行分片
run vts --module <VtsHalXxx>     # 单跑某个 HAL 的 VTS

# ── 设备信息（提交报告要用）──────────────────────
adb shell dumpsys package <pkg> | grep -iE "versionName|flags"   # 包版本/标志
adb shell getprop ro.build.fingerprint       # 构建指纹（报告必填）
adb shell getprop ro.build.version.security_patch  # 安全补丁级别（STS 校验）
adb shell getprop ro.product.model           # 产品型号
adb shell pm list features                   # ★ 设备声明了哪些硬件能力
adb shell getprop ro.build.characteristics   # 设备形态：automotive/tv/watch

# ── 抓日志定位失败 ───────────────────────────────
adb logcat -b all -v threadtime > ~/cts/logcat.txt   # 边跑边抓全 buffer
adb shell dmesg | grep avc                          # ★ SELinux 拒绝（第 6.8 条）
adb bugreport ~/cts/bug.zip                         # 整机快照（含所有 dumpsys）

# ── 设备状态核对 ─────────────────────────────────
adb shell settings list global | grep -iE "verify|verifier"  # 确认关掉安装校验
adb shell wm size ; adb shell wm density            # 屏幕分辨率/密度（第 6.7 条）
adb shell pm list users                             # 多用户状态（第 6.16 条）
```

**排查三板斧**（与存储篇同构）：
1. **看声明**（`pm list features`、`ro.build.characteristics`）——是"没这硬件"还是"有但没实现好"？
2. **看属性与默认**（`getprop`、`settings`）——属性被改 / 默认值偏了没？
3. **看拒绝**（`dmesg | grep avc`、`logcat` 里的异常）——SELinux 或 framework 在卡你。

---

## 十四、常见问题排查表

| 现象 | 最可能的原因 | 章节 | 第一命令 |
|---|---|---|---|
| CTS 满屏红，但设备没清数据 | 脏数据/残留账号导致环境问题 | 3.3、5.1 | `adb shell pm list users` |
| 改了 framework 后 CTS 挂 | 你改坏了 API 行为（真 bug） | 6、7.3 | GSI 对照：刷 GSI 重跑 |
| 某权限用例失败 | privapp 白名单缺项 | 6.3 | `adb shell dumpsys package <pkg> \| grep permission` |
| 系统 App 装不上/行为怪 | 平台签名不匹配 | 6.2 | `adb shell pm dump <pkg> \| grep -i sign` |
| 默认浏览器/短信用例挂 | 默认 App 缺失或被替换 | 6.1 | `adb shell cmd role list` |
| 日期/时区/语言用例挂 | 默认区域/格式被改 | 6.5 | `adb shell getprop persist.sys.locale` |
| 屏幕/壁纸/多窗口用例挂 | 分辨率/密度配置不符 | 6.7 | `adb shell wm density` |
| 某功能突然异常但无报错 | SELinux 拒绝（avc denied） | 6.8 | `adb shell dmesg \| grep avc` |
| 相机/传感器用例挂 | HAL 实现不全或 feature 误声明 | 6.14、5.4 | `adb shell pm list features` |
| 后台任务/闹钟用例超时 | 省电/后台限制太激进 | 6.18 | `adb shell dumpsys deviceidle` |
| 音频/音量用例挂 | 默认音量/静音被改 | 6.13 | `adb shell media volume --get` |
| 多用户/工作资料用例挂 | 多用户能力被裁剪破坏 | 6.16 | `adb shell pm list users` |
| VTS 某 HAL 返回 BAD_VALUE | HAL 接口没补齐 | 8.3 | `vts-tradefed` 单跑该 HAL |
| VTS 报 VNDK 不匹配 | vendor/system VNDK 版本不一致 | 8.3 | `adb shell getprop ro.vndk.version` |
| GTS 跑不起来 | 未签 MADA / GMS 没装好 / 无网无账号 | 9.1 | `adb shell pm list packages gms` |
| STS 失败但填了高补丁级别 | 安全补丁没真合入（假升级） | 9.2 | `adb shell getprop ro.build.version.security_patch` |
| 产线测过却 CTS 挂 | 混淆了 PIT 与 CTS 职责 | 12.3 | 重跑完整 CTS |
| 车机行驶中限制用例挂 | UX_RESTRICTIONS 未生效 | 12.1 | `adb shell cmd car_service ...` |
| 重试还是挂但 GSI 通过 | 确认是你改的，回第 6 章 | 7.3 | `git log` 查近期 framework 改动 |
| 报告被 Google 打回 | 设备非干净状态/版本不一致 | 10.2 | `adb shell getprop ro.build.fingerprint` |

---

## 十五、一图总结

```
┌ 你改了代码/配置 ───────────────────────────────────────────────────┐
│  改 framework? ──► 跑 CTS           改 vendor/HAL? ──► 跑 VTS        │
│  改 GMS 集成?  ──► 跑 GTS(需MADA)   改 security_patch? ──► 跑 STS    │
└──────────────┬─────────────────────────────────────────────────────┘
               │ 失败
               ▼
┌ 失败三类归属 ──────────────────────────────────────────────────────┐
│  真 bug ──► 第6章清单逐条修（声明/默认/预装/权限/SELinux）          │
│  环境问题 ──► 清数据重 setup 后 retry                              │
│  不适用   ──► 走 waiver（讲道理，不是免死金牌）                    │
└──────────────┬─────────────────────────────────────────────────────┘
               │ 仍分不清是谁的锅？
               ▼
┌ CTS-on-GSI 对照实验 ───────────────────────────────────────────────┐
│  GSI 也挂 → 非你引入（硬件/上游/不适用）   GSI 过 → 你改坏了        │
└──────────────┬─────────────────────────────────────────────────────┘
               │ 全过
               ▼
┌ 提交 DCC/ATS ──► Google 评审 ──► 量产准入 ──► 改固件就回归（CI 冒烟集）┐
└──────────────────────────────────────────────────────────────────┘

记忆链（五句话）：
  ① 带 GMS 量产 = 必须过；自用/内测 = 不用过。
  ② CTS 测上层行为，VTS 测下层 HAL 契约，GTS 是 Google 私货，STS 验安全补丁真话。
  ③ CTS 失败 90% 是"声明/默认/预装/权限/SELinux"五件事没对齐，不是算法错。
  ④ GSI 对照实验是判"谁改坏"的照妖镜：GSI 过你挂 = 你的问题。
  ⑤ 产线 PIT 保单台良率，CTS/VTS 保型号合规——两者别混淆，也别互相替代。
```

---

## 十六、关联阅读

同目录 `androidOthers/` 与 `androidFrameworks/`、`automotive/` 下的姊妹篇，按需跳转：

- **`androidFrameworks/04_PMS`（PackageManagerService）**：第 6.3 条 privapp 白名单、默认权限授予策略的底层机制，本文只讲"现象与修法"，原理去那篇。
- **`androidFrameworks/14_权限`**：权限默认值、signature 级权限、运行时权限模型，对应第 6.2/6.4 条。
- **`androidOthers` NDK 篇 / 构建篇**：VTS 的 VNDK 校验、HAL 的 native 实现与构建集成（第 8 章）。
- **`androidOthers` SELinux 篇**：第 6.8 条 `avc denied` 的域规则与 `file_contexts` 修复。
- **`androidOthers` OTA 篇**：认证与固件绑定、OTA 后必须回归 CTS/VTS（第 10.5 节）。
- **`automotive` 各篇（尤其 03-VHAL）**：车机 VHAL 接口契约、多屏、UX_RESTRICTIONS，对应第 8.2 / 12.1 节。
- **`androidOthers` 存储机制详解**：排查方法论同构（声明→属性→拒绝三板斧），可作为对照阅读。

---

*本文版本对应关系、套件版本号、用例数量等均以 Google 官方发布文档与你所签 MADA 合作资料为准；内部 API 签名未写死，相关机制以"一类/相关控制器"表述。*

---



---



---

