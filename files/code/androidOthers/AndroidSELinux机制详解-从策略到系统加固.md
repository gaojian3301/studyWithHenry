# Android SELinux 机制详解——从策略到系统加固

> 姊妹篇：`Android权限机制详解-从Permission到AppOps.md`（本篇是 **MAC 侧**，那篇是 **DAC 侧**）、`AndroidNDK与JNI详解-从跨语言调用到so加载与Native崩溃排查.md`（native 代码仍跑在 UID + SELinux 域里，没逃脱 MAC）、`automotive/03-VehicleHAL-VHAL详解.md`（案例二直接衔接 VHAL 的 binder/HAL 互通）。
>
> **这一篇的形状不一样。** SELinux 不是"一层套一层"的协议栈，也不是"按场景分叉"的能力树——它的本质是**每一次访问都要先回答三个问题**：
>
> - **谁（who）** = subject 的 domain（域）
> - **对什么（what）** = object 的 type（类型）
> - **做什么（how）** = class（客体类）+ permission（权限）
>
> 所以这个主题属于**规则 / 边界型**：全文以"三个问题"为骨架，**错误清单（avc denied 判读）与照抄模板（新增 service / HAL / 设备节点）是最高价值块**，优先级高于理论。你平时搜 `restorecon` 多半就是卡在某个 type 没打对，本篇把这类坑集中讲清。
>
> **阅读约定**：
> - 「**通用 SELinux**」= 与桌面 Linux（Fedora/RHEL 的 targeted 策略）一致的部分，不依赖 Android。
> - 「**Android 视角**」= 只有 AOSP / vendor / 车机 ROM 才会碰到、写普通 App 可以跳过的部分。
> - 「**车机视角**」= 量产 ROM 的 vendor 加固、预装标签、调试开关，专给车载定制 ROM 开发者。
> - 策略语法以 **Android 11~14 + Treble 分区策略** 为主线，涉及版本差异处会标出。
> - 不确定的内部 API 签名一律用"相关控制器 / 一类"表述，不写死。

---

## 目录

1. [开场：为什么 uid/gid 不够](#一开场为什么-uidgid-不够)
2. [Android 上的 SELinux 形态：enforcing / permissive](#二android-上的-selinux-形态enforcing--permissive)
3. [核心概念对照表：术语成对出现](#三核心概念对照表术语成对出现)
4. [策略文件与加载链路](#四策略文件与加载链路)
5. [te 文件语法与常用宏](#五-te-文件语法与常用宏)
6. [上下文类文件各自管什么](#六上下文类文件各自管什么)
7. [Treble 带来的分区策略约束](#七treble-带来的分区策略约束)
8. [案例一：新增一个 native service](#八案例一新增一个-native-service)
9. [案例二：新增一个 AIDL HAL](#九案例二新增一个-aidl-hal)
10. [案例三：让系统 App 访问系统私有资源](#十案例三让系统-app-访问系统私有资源)
11. [案例四：新增设备节点 / 驱动访问](#十一案例四新增设备节点--驱动访问)
12. [AVC 日志逐字段解读](#十二avc-日志逐字段解读)
13. [audit2allow 的正确用法与三个陷阱](#十三audit2allow-的正确用法与三个陷阱)
14. [常见问题排查表](#十四常见问题排查表)
15. [车机场景：vendor 加固与调试开关](#十五车机场景vendor-加固与调试开关)
16. [读源码路线 + 源码路径速查表](#十六读源码路线--源码路径速查表)
17. [一图总结](#十七一图总结)
18. [关联阅读](#十八关联阅读)

---

## 一、开场：为什么 uid/gid 不够

### 1.1 先用生活类比建立直觉

把一台 Android 设备想象成一家公司大楼：

- **uid / gid（DAC 侧）** = 员工工牌上的"员工号"。它只回答"你是哪个部门的"（root / system / shell / 某个 app 的 uid 1000x）。
- **门禁系统（MAC 侧，SELinux）** = 大楼里每扇门、每台打印机、每间档案室的"刷卡权限表"。它回答"**即使你有工牌，这扇门你今天能不能进、能不能打印**"。

关键差异：**DAC（自主访问控制）看"你是谁"，MAC（强制访问控制）看"你被允许对某个具体对象做什么"**。工牌可以伪造（root 提权后 uid=0 几乎畅通无阻），但门禁表是大楼统一下发、谁都改不了的——这就是 MAC 存在的理由。

> 类比记忆链：**员工证 = domain（你是哪个域的进程）｜门 = type（对象是什么类型）｜刷卡动作 = permission（读/写/执行/连接）｜门禁总表 = sepolicy（加载进内核的策略）**。

### 1.2 DAC 的三个硬伤（为什么必须有 SELinux）

`14_权限机制详解` 那篇讲的是 App 层的 Permission / AppOps，属于**用户态的资格判断**；本篇的 SELinux 工作在**内核态**，补的是 DAC 的三处漏洞：

| 缺陷 | 说明 | SELinux 怎么补 |
|---|---|---|
| **root 即万能** | DAC 下 uid=0 几乎绕过一切文件权限，提权漏洞一发不可收拾 | MAC 下 root 进程也受 domain 约束，root 不等于能为所欲为 |
| **只看"属主"不看"用途"** | DAC 只判断"是不是我的文件"，不判断"这个进程该不该碰" | MAC 按 domain→type→permission 三元组逐次判定 |
| **权限是"全有或全无"** | 一个进程要么能读某文件，要么不能，粒度粗 | MAC 把能力拆成 class×permission，可精确到 `ioctl` 的某几个 cmd |

一句话：**DAC 解决"身份"，MAC 解决"行为边界"**。Android 在 4.3 引入 SELinux，4.4 起对核心域 enforce，5.0 起全面 enforcing（userdebug 仍可切 permissive 调试）。

### 1.3 三个核心问题：本篇的骨架

每次内核做访问决策，SELinux 都在内核里跑这段逻辑：

```
访问请求进来
   └─ scontext（who）= 发起进程属于哪个 domain？
   └─ tcontext（what）= 目标对象被打成哪个 type？
   └─ tclass + perm（how）= 操作属于哪个客体类（file/process/binder/…），要哪个权限？
        │
        ▼
   查 sepolicy：allow <sdomain> <ttype>:<tclass> { <perm> }; 存在吗？
        │
        ├─ 允许 → 放行，记一条 granted（默认不审计）
        └─ 拒绝 → 记一条 avc: denied（审计日志），按 enforcing/permissive 决定真拒还是只记
```

记住这三问，后面所有 te 语法、上下文文件、avc 日志，都是围绕它们转的。

### 1.4 怎么读本篇

- **急着排错**：直接跳到 [第十二节 AVC 解读](#十二avc-日志逐字段解读) 和 [第十四节排查表](#十四常见问题排查表)。
- **要新增组件**：从 [第八节 native service](#八案例一新增一个-native-service) 或 [第九节 HAL](#九案例二新增一个-aidl-hal) 照抄三件套。
- **想搞懂原理**：顺序读 [第二 ~ 七节](#二android-上的-selinux-形态enforcing--permissive)。
- **车机量产**：重点看 [第七节 Treble 约束](#七treble-带来的分区策略约束) 和 [第十五节车机场景](#十五车机场景vendor-加固与调试开关)。

---

## 二、Android 上的 SELinux 形态：enforcing / permissive

### 2.1 两种模式

| 模式 | 行为 | 用途 |
|---|---|---|
| **enforcing** | 真正拦截违规访问，并记 avc denied | 用户版（user）必须；量产必须 |
| **permissive** | 不拦截，但照样记 avc denied（"只审计不拒绝"） | 开发期定位缺哪条 allow；**量产前必须关掉** |

> 注意：permissive 下功能通常"正常"，因为请求其实被放行了，只是日志在记。所以**一个功能在 permississive 下好使、enforcing 下静默失败，几乎可以肯定是少了一条 allow**，而不是代码 bug。

### 2.2 查看与临时切换

```bash
# 看当前模式，输出 Enforcing / Permissive
adb shell getenforce

# 临时切到 permissive（需要设备支持，user 版通常被 patch 锁死）
adb shell setenforce 0     # 0 = permissive, 1 = enforcing
adb shell setenforce 1

# 内核启动参数决定的初始模式（bootloader 传下来的）
adb shell getprop ro.boot.selinux     # 常见 enforcing / permissive
```

### 2.3 版本与构建差异（重点）

| 构建类型 | 默认行为 | 说明 |
|---|---|---|
| **user** | 强制 enforcing，且 `setenforce 0` 通常无效 | 量产版；SELinux 是安全边界，不能关 |
| **userdebug** | 默认 enforcing，**允许** `setenforce` 临时切 | 工程机 / 车机开发常用 |
| **eng** | 部分版本默认 permissive | 纯开发，不建议长测 |

`「Android 视角」` 还有一个关键开关：**`BOARD_KERNEL_CMDLINE` 里的 `androidboot.selinux=permissive`** 或 `selinux=0`。某些车机早期 bring-up 会临时塞这个，但**量产前必须删掉**，否则 CTS / 安全审计过不了。

### 2.4 哪些场景必须是 enforcing

- 任何**对外发布**的 user 版本。
- **支付、车控、仪表**等安全敏感域（车机视角：VHAL、车身控制服务所在域绝不能是 permissive）。
- 通过 **CTS / VTS / STS** 认证的设备（SELinux 相关用例直接 fail）。
- 开启 **Verified Boot / dm-verity** 后若 SELinux 关了，启动链完整性意义减半。

> 反面教材：某车机 bring-up 时图省事全局 permissive，量产前夜才发现 vendor 域有一堆真实违规被 enforce 后集体失声——这就是 [第十五节](#十五车机场景vendor-加固与调试开关) 强调"早切 enforcing 多跑"的原因。

---

## 三、核心概念对照表：术语成对出现

### 3.1 五组成对术语

SELinux 术语最怕"单着看"，必须成对记：

| 维度 | 主语侧（subject） | 宾语侧（object） | 一句话 |
|---|---|---|---|
| **身份** | subject（发起访问的进程） | object（被访问的文件/节点/服务） | 谁对什么 |
| **类型** | **domain（域）**——进程的 type | **type（类型）**——客体的 type | 进程的类型叫 domain，文件的类型叫 type，本质都是 type |
| **操作** | — | **class（客体类）** + **permission（权限）** | file 有 read/write，process 有 transition，binder 有 call |
| **归类** | **attribute（属性）** | **attribute（属性）** | 一组 type 的"标签"，allow 可针对 attribute 批量授权 |
| **SELinux 身份** | **user（selinux user，常是 `u`）** | **role（角色，对象侧常是 `object_r`）** | 与 Linux uid 不是一回事，别混 |

> 最容易混的点：**domain 和 type 是同一个东西的两种叫法**——进程的安全上下文里 type 字段就是它的 domain；文件的安全上下文里 type 字段就是它的 type。te 里 `type foo, domain;` 表示 foo 是个进程域，`type foo, file_type;` 表示 foo 是个文件类型。

### 3.2 把 `u:object_r:system_file:s0` 拆成四段

这是你在 `file_contexts` 里最常见的一串，四个字段用冒号分隔：**user:role:type:level（MLS 级别）**。

```text
u : object_r : system_file : s0
│     │          │            │
│     │          │            └─ level（MLS 多级安全级别）。Android 几乎永远 s0，不展开
│     │          └────────────── type（类型）：这个对象是什么。这里是 system_file
│     └──────────────────────── role（角色）：对象侧的 role 永远是 object_r
└────────────────────────────── user（SELinux 用户）：Android 上进程和对象都常是 u
```

进程侧你会看到 `u:r:init:s0`（user=u, role=r, type=init, level=s0）——注意 role 是 `r` 而不是 `object_r`，这是 subject 与 object 在 role 字段上的区别。

### 3.3 一个最小的"三段式"判定

```text
进程 init（domain=init）想要 execute 文件 /system/bin/foo
  scontext = u:r:init:s0
  tcontext = u:object_r:foo_exec:s0
  tclass   = file
  perm     = execute

策略里要有：allow init foo_exec:file { execute read open };
→ 有就放行，没有就 avc denied。
```

---

## 四、策略文件与加载链路

### 4.1 两个分区、两套策略（Treble 之后）

`「Android 视角」` 从 Android 8.0（Treble）起，策略按分区拆成 platform 和 vendor 两份：

| 路径 | 归属 | 包含什么 | 文件名模式 |
|---|---|---|---|
| `/system/etc/selinux/` | platform（AOSP 主体） | plat_*.cil / plat_*_mappings / precompiled sepolicy | `plat_sepolicy.cil`、`plat_mac_permissions.xml` |
| `/vendor/etc/selinux/` | vendor（芯片/ODM/车厂） | vendor 自己的域、neverallow、映射 | `vendor_sepolicy.cil`、`vendor_property_contexts` 等 |

> 历史演变：早期（Android 7 及以前）只有一份 `/sepolicy`；Treble 后为支持**独立升级 system 不破坏 vendor**，拆成两份，运行时由内核拼成统一策略。版本差异见 [第七节](#七treble-带来的分区策略约束)。

### 4.2 编译产物与加载时机

```bash
# 编译产物（out 目录里能看到，最终打包进各自分区）
out/target/product/xxx/system/etc/selinux/plat_sepolicy.cil
out/target/product/xxx/vendor/etc/selinux/vendor_sepolicy.cil
# 还有一份"拼好的"二进制策略（policy.X，X 是 policy 版本号 policyvers）
out/target/product/xxx/obj/ETC/sepolicy_intermediates/sepolicy   # 早期单份形态
out/target/product/xxx/vendor/etc/selinux/precompiled_sepolicy  # 新版预编译

# 运行时加载点（内核挂载的 selinuxfs）
adb shell ls -l /sys/fs/selinux/        # 策略与状态接口都在这里
adb shell cat /sys/fs/selinux/enforce   # 1=enforcing, 0=permissive
adb shell cat /sys/fs/selinux/policy    # 当前加载的二进制策略（已拼好）
```

**加载链路（简化）**：

```
init 早期（first stage init）
  └─ 挂载 /sys/fs/selinux
  └─ 读 /vendor/etc/selinux/precompiled_sepolicy（若 vendor 与 system 版本匹配）
        └─ 版本不匹配则现场把 plat_*.cil + vendor_*.cil 拼起来并编译
  └─ 调 security_load_policy() 把二进制策略灌进内核
  └─ 此后每一次访问都按内核里的策略判定
```

### 4.3 两个分析工具

```bash
# 1) checkpolicy：把 te/ cil 源编译成二进制并做语法/约束检查
#    （在编译期就用，本地快速验证 te 写法对不对）
checkpolicy -M -o out.bin -c 30 policy.conf      # -c 指定 policy 版本号

# 2) sepolicy-analyze：对编译后的二进制策略做查询/可达性分析
sepolicy-analyze out/sepolicy dups              # 找出重复 allow
sepolicy-analyze out/sepolicy typeauditallow   # 审计相关
sepolicy-analyze out/sepolicy neverallow       # 哪些 neverallow 生效（排查误伤）

# 反查"某域到底被允许了哪些权限"
sesearch -A -s mysecuresvc out/sepolicy         # 列出 mysecuresvc 的所有 allow
sesearch -A -t system_file -c file out/sepolicy # 谁能动 system_file
```

> `「Android 视角」`：AOSP 在 `system/sepolicy` 里用 `m4`+`cil` 预编译，本地改完 te 后建议先跑 `make sepolicy` 或单独 `checkpolicy` 验证，别等整编才报错。

---

## 五、te 文件语法与常用宏

### 5.1 基础语句

| 语句 | 作用 | 最小示例 |
|---|---|---|
| `type` | 声明一个 type（同时可挂 attribute） | `type mydaemon, domain;` |
| `typeattribute` | 事后给 type 追加 attribute | `typeattribute mydaemon coredomain;` |
| `allow` | 放行一条访问 | `allow mydaemon mydata_file:file { read write };` |
| `neverallow` | 编译期硬性禁止（违反就 build fail） | `neverallow { domain -init } kmem_device:chr_file *;` |
| `dontaudit` | 不记录某条本会被拒的访问（消噪） | `dontaudit untrusted_app mydata_file:file read;` |
| `type_transition` | 创建对象时自动换 type | `type_transition init mydata_dir:dir mydata_file;` |
| `type_change` | 重标签时换 type（较少用） | `type_change sysadm_t user_t:process sysadm_t;` |

逐条解释：

- **`type`**：每个进程域、每个文件类型都得先声明。`domain` 是 platform 预置 attribute，表示"这是进程域"；`file_type` 表示"这是文件类型"。
- **`allow`** 的语法固定为 `allow <源域> <目标类型>:<客体类> { <权限> };`。注意**方向**：是谁去访问谁，源域写在前面。
- **`neverallow`** 不是运行时规则，是**编译期的契约**。它保证"无论谁写的 te，都不能给 X 域放开 Y"。违反直接编译失败（这是 vendor 最常见被 AOSP 误伤的地方，见 [第七节](#七treble-带来的分区策略约束)）。
- **`dontaudit`** 只压制日志，**不改变放行与否**——该拒还是拒，只是不刷屏。调试期常用，但**交付前评估是否该补 allow 而不是永久 dontaudit**（见 [第十三节](#十三audit2allow-的正确用法与三个陷阱)）。
- **`type_transition`**：经典场景——init 在某个目录里 `mkdir`，新目录/文件自动带上指定 type，省得事后 restorecon。

### 5.2 高频宏（宏展开后是多条 allow 的组合）

`「Android 视角」` system/sepolicy 提供大量宏，写在 `*.te` 里等于一次性写对好几条基础规则：

| 宏 | 展开含义（直觉） | 典型用法 |
|---|---|---|
| `init_daemon_domain(T)` | 让 T 从 init 拉起时自动进入自己域（含 transition + 对 init 的 execute 许可） | `init_daemon_domain(mydaemon)` |
| `hal_server_domain(T, H)` | 声明 T 是 HAL 类型 H 的服务端（含 binder / hwservice 相关放行） | `hal_server_domain(mydaemon, myhal)` |
| `hal_client_domain(T, H)` | 声明 T 是 HAL 类型 H 的客户端 | `hal_client_domain(system_app, myhal)` |
| `binder_call(C, S)` | 允许 C 向 S 发起 binder 调用（含 binder 类权限） | `binder_call(client, server)` |
| `binder_use(T)` | 允许 T 使用 binder 设备 | `binder_use(mydaemon)` |
| `file_type(T)` | 标记 T 是文件类型 | `type mydata_file, file_type, data_file_type;` |
| `get_prop(T, P)` | 允许 T 读某属性 | `get_prop(mydaemon, system_prop)` |
| `set_prop(T, P)` | 允许 T 写某属性 | `set_prop(mydaemon, my_prop)` |
| `dev_type(T)` | 标记 T 是设备节点类型 | `type mydev, dev_type;` |
| `appdomain(T)` | 把 T 纳入通用 App 域约束集 | `type myapp, domain, appdomain;` |

> 宏是 `m4` 在编译期展开的，出错时日志里常看到展开后的原始规则。不确定的宏名以你用的 AOSP 分支里 `system/sepolicy/public/te_macros` 为准（用"一类宏定义"表述，不写死签名）。

### 5.3 一个"最小可编译"的域定义

```te
# mydaemon.te  —— 一个从 init 拉起的守护进程的最小策略
type mydaemon, domain;                 # 1) 声明进程域
type mydaemon_exec, exec_type, file_type;   # 2) 声明它的可执行文件类型
init_daemon_domain(mydaemon)           # 3) 从 init 拉起时自动 transition 进 mydaemon
binder_use(mydaemon)                   # 4) 允许使用 /dev/binder
# 5) 允许写自己的数据文件（data_file_type 才能落在 /data 下被正确打标）
type mydaemon_data_file, file_type, data_file_type;
allow mydaemon mydaemon_data_file:file { create read write open getattr setattr };
```

下一段（[第六节](#六上下文类文件各自管什么)）讲这些 type 怎么和 `file_contexts` 等上下文文件挂钩——**te 只定义"规则"，真正把文件/属性/服务"贴上 type 标签"的是上下文文件**。

---

## 六、上下文类文件各自管什么

### 6.1 一句话区分

`te` 文件回答"**有了 type 之后允许什么**"；上下文（*_contexts）文件回答"**这个路径 / 属性 / 服务名该贴上哪个 type**"。两者必须配合，缺一边都没用。

### 6.2 对照表：每个上下文文件管哪类"对象"

| 文件 | 作用对象 | 贴在谁的上下文 | 写什么示例 | 对应三类问题中的 |
|---|---|---|---|---|
| **file_contexts** | 磁盘文件路径（含可执行文件、数据目录、设备节点） | 文件系统里的真实文件 | `/system/bin/foo u:object_r:foo_exec:s0` | **what（tcontext）** |
| **property_contexts** | 系统属性名（如 `ro.xxx`、`persist.xxx`） | 属性服务里的 key | `my.foo.u:object_r:my_prop:s0` | what |
| **hwservice_contexts** | hwbinder 服务接口名（`vendor.foo.Ixxx`） | hwservicemanager 注册名 | `vendor.foo.IMy u:object_r:my_hwservice:s0` | what |
| **service_contexts** | binder 服务名（servicemanager 注册名） | servicemanager 注册名 | `my_service u:object_r:my_service:s0` | what |
| **vndservice_contexts** | vendor binder 服务名（vndservicemanager） | vndservicemanager 注册名 | `my_vnd u:object_r:my_vnd_service:s0` | what |
| **seapp_contexts** | App 的（user, seinfo, 包名）组合 | App 进程域 + 数据文件类型 | `user=system seinfo=platform name=com.x domain=myapp_app` | **who + what** |
| **genfs_contexts** | 伪/虚拟文件系统节点（procfs、sysfs、debugfs、tracefs、configfs） | 动态生成、没有真实磁盘文件的节点 | `genfscon proc /soc u:object_r:sysfs_soc:s0` | what |
| **mac_permissions.xml** | App 的签名 / 包名 / 厂商 | 给 App 打 `seinfo` 标签（给 seapp_contexts 用） | `<signer signature="..."><seinfo value="platform"/></signer>` | who（间接） |

### 6.3 为什么需要 genfs_contexts 而 file_contexts 不行

`file_contexts` 给**真实落盘文件**打标，靠的是 `restorecon` 遍历磁盘。但 `/proc`、`/sys`、`/dev` 下的节点是内核运行时动态生成的，**磁盘上不存在**，restorecon 扫不到。所以这一类用 `genfscon` 在内核创建节点时就贴上 type：

```text
# genfs_contexts 语法：genfscon <文件系统> <路径> <上下文>
genfscon sysfs /devices/platform/foo   u:object_r:sysfs_foo:s0
genfscon proc  /driver/bar            u:object_r:proc_bar:s0
```

### 6.4 车机视角的两个高频坑

- **新增 sysfs 节点忘写 genfs_contexts**：结果节点落在 `sysfs` 默认 type，你的 daemon 只有 `allow mydaemon sysfs:file read` 才读得到，但这样等于放开整个 sysfs——要么补 genfscon 给它单独 type，要么接受 neverallow 风险。
- **property_contexts 改了属性仍报 avc**：属性名要用**前缀匹配**，写 `my.` 会匹配 `my.*` 全部；写错前缀会被归到 `default_prop`，于是 `get_prop(mydaemon, my_prop)` 对不上。用 `adb shell getprop -Z my.foo` 看实际打上的 type 验证。

---

## 七、Treble 带来的分区策略约束

### 7.1 为什么拆

`「Android 视角」` Android 8.0（API 26，Treble）的目标：**system 分区能独立 OTA 升级，不破坏 vendor 分区**。如果策略只有一份混在一起，system 一升级就会把 vendor 的域定义冲掉。于是策略也按分区拆：

```
/system/etc/selinux/   → plat_*.cil   （AOSP 平台域，Google 控）
/vendor/etc/selinux/   → vendor_*.cil （芯片厂/ODM/车厂自己的域）
```

两者在运行时由内核/初始化逻辑拼成一份统一策略，但**编译期互相看不到对方的私有 type**。

### 7.2 三条硬约束（vendor 开发者必背）

| 约束 | 含义 | 违反后果 |
|---|---|---|
| **vendor 不能引用 platform private type** | vendor te 里出现的 type，必须要么是 vendor 自己声明、要么是 AOSP **公开（public）** 的 type/attribute | 编译失败或 VTS 不过 |
| **platform 不会为 vendor 私有 type 开 allow** | 你 vendor 的 daemon 想碰 system 的资源，得用 AOSP 预留的 attribute（如 `appdomain`、`hal_type`）去接 | 只能自己 vendor 内闭环，或走标准 HAL 接口 |
| **neverallow 由 platform 单方面下钉** | AOSP 在 plat 策略里写死大量 neverallow（如"任何域不得读写 `init` 的 tmpfs"、"vendor 域不得碰 app 域"），vendor 无法推翻 | vendor 不经意写了 `allow mydaemon system_app_data_file:...` 就编译 fail |

> **版本差异（重点）**：
> - Android 8.0 引入 plat/vendor 拆分。
> - Android 9~10 逐步收紧：明确 `vendor` 域不得访问大量 platform 私有 type，neverallow 大量增加。
> - Android 11+ **neverallow 收敛趋势**持续：Google 不断把"vendor 越界访问"钉死，vendor 想"图省事 allow 一下"越来越难编译过；policy 版本号（policyvers）也随之增长（见 [第四节 4.2](#四策略文件与加载链路)），新内核支持更高的 policy capability。
> - Android 12+ 还有 `microdroid` / `isolated` 等更细的域，但核心约束不变。

### 7.3 常见"被 AOSP neverallow 误伤"清单

```text
# 下面这些写法在编译期会被 plat 的 neverallow 拦下（vendor 侧尤其高发）
allow my_vendor_daemon system_app_data_file:file { read };   # vendor 碰 app 数据
allow my_vendor_daemon system_server:process { sigkill };     # vendor 杀 system_server
allow my_vendor_daemon default_prop:property_service { set }; # vendor 写非自有属性
allow { domain -init } kmem_device:chr_file *;                # 被通用 neverallow 覆盖
```

正确做法不是去删 neverallow（你改不了 AOSP 那部分），而是：

1. **走标准接口**：要跟 framework 通信就实现/调用标准 AIDL HAL，用 `hal_server_domain` / `hal_client_domain` 接入，而不是自己 allow 去碰 `system_server`。
2. **自有属性走 property_contexts + 独立 prop type**：声明 `my_vendor_prop` 并 `set_prop(mydaemon, my_vendor_prop)`，别碰 `default_prop`。
3. **设备节点走 dev_type + 自己的 file_contexts**：见 [第十一节](#十一案例四新增设备节点--驱动访问)。

### 7.4 怎么在编译期看到"到底被哪条 neverallow 拦了"

```bash
# 编译失败日志里会给出具体 neverallow 规则的位置，例如：
#   neverallow check failed at .../system/sepolicy/public/domain.te:123
# 本地可用 sepolicy-analyze 精确反查
sepolicy-analyze out/target/product/xxx/obj/ETC/.../sepolicy neverallow \
  | grep -i "system_app_data_file"     # 看哪条 neverallow 涉及目标类型

# 也常用 sed/awk 在 system/sepolicy 里定位对应规则，确认是不是 vendor 越界
```

---

## 八、案例一：新增一个 native service

`「Android 视角」` `「车机视角」` 这是车机 bring-up 最高频操作：你写了一个 C++ 守护进程要常驻，缺 SELinux 策略时它在 enforcing 下会**起不来或起来后被杀**。下面给**三件套**（te + file_contexts + init.rc）的完整内容，逐行解释。

### 8.1 三件套总览

假设服务名 `mysecuresvc`，可执行文件 `/system/bin/mysecuresvc`，数据目录 `/data/misc/mysecuresvc`。

### 8.2 te 文件（完整可复制）

```te
# ===== mysecuresvc.te =====
# 1) 声明进程域：这个 daemon 跑在自己的 domain 里
type mysecuresvc, domain;
# 2) 声明它的可执行文件类型（exec_type 表示"可被 execute"）
type mysecuresvc_exec, exec_type, file_type;
# 3) 从 init 拉起时自动 transition 进 mysecuresvc 域
init_daemon_domain(mysecuresvc)

# 4) 数据目录类型（data_file_type 才能落在 /data 下被正确打标）
type mysecuresvc_data_file, file_type, data_file_type;
# 5) 允许对自身数据文件做读写等
allow mysecuresvc mysecuresvc_data_file:file { create read write open getattr setattr };
# 6) 允许在自身数据目录下增删文件（对 dir 的权限要和 file 分开写）
allow mysecuresvc mysecuresvc_data_file:dir { search read write add_name remove_name };

# 7) 允许使用 binder 并与 system_server 通信
binder_use(mysecuresvc)
binder_call(mysecuresvc, system_server)

# 8) 允许读系统属性（如 ro.build.xxx）
get_prop(mysecuresvc, system_prop)

# 9) 允许连 logd 写日志（否则 logcat 看不到它的输出）
allow mysecuresvc logd:unix_stream_socket connectto;
```

逐行要点：

- **第 3 行 `init_daemon_domain`** 是关键：它内部展开成 `domain_auto_trans(init, mysecuresvc_exec, mysecuresvc)` + 对 init 的 execute 许可。没有它，init 直接 exec 后进程仍留在 `init` 域，策略全错。
- **第 5、6 行** file 与 dir 的权限是两套，别指望 `file` 权限覆盖目录操作。
- **第 9 行** 很多新手漏：native 服务用 `__android_log_print` 走 logd 的 unix stream socket，禁了就完全没日志，排错时更懵。

### 8.3 file_contexts（完整可复制）

```text
# ===== file_contexts（追加片段）=====
# 可执行文件 → 打上 mysecuresvc_exec（对应 te 第 2 行）
/system/bin/mysecuresvc              u:object_r:mysecuresvc_exec:s0
# 数据目录及其下所有内容 → 打上 mysecuresvc_data_file
/data/misc/mysecuresvc(/.*)?         u:object_r:mysecuresvc_data_file:s0
```

要点：

- **路径要写对分区**。若可执行文件在 `/vendor/bin/`，就写 `/vendor/bin/mysecuresvc`，且这条 file_contexts 要进 **vendor** 的上下文文件（不是 system 的），否则 `restorecon` 不会扫到。
- **`(/.*)?`** 是正则，表示"该目录及所有子内容"。只写目录本身不含子文件。
- 改完必须 `restorecon -R /system/bin/mysecuresvc /data/misc/mysecuresvc`（或重编后首次启动自动打标），否则磁盘上的 type 还是旧的——这就是你搜 `restorecon` 的由来。

### 8.4 init.rc（完整可复制）

```rc
# ===== init.rc（或 init.<board>.rc，service 段）=====
service mysecuresvc /system/bin/mysecuresvc
    class main
    user system
    group system
    # seclabel 显式指定 exec 上下文；与 file_contexts 一致，双保险
    seclabel u:object_r:mysecuresvc_exec:s0
    oneshot
```

要点：

- **`seclabel`**：早期 init 在 transition 完成前就 exec，显式 seclabel 保证进程从一开始就带对 domain；配合 `init_daemon_domain` 是标准做法。
- **`class main`**：让它在 `class_start main` 时启动。若你用自定义 class，记得有地方 `class_start` 它。
- 若放的是 **vendor** 服务，这条 rc 应位于 `/vendor/etc/init/`，对应的 seclabel type 也要在 vendor 策略里声明，受 [第七节](#七treble-带来的分区策略约束) 约束。

### 8.5 验证顺序

```bash
# 1) 文件标签是否打对
adb shell ls -Z /system/bin/mysecuresvc
#    期望：u:object_r:mysecuresvc_exec:s0
# 2) 进程是否进入正确域
adb shell ps -Z | grep mysecuresvc
#    期望：u:r:mysecuresvc:s0 ...
# 3) 若仍 denied，抓 avc 反推缺哪条 allow（见第十二、十三节）
adb shell dmesg | grep avc
```

---

## 九、案例二：新增一个 AIDL HAL

`「Android 视角」` `「车机视角」` HAL 是 vendor 与 framework 之间的标准桥梁。AIDL HAL（Android 10+ 主流，取代旧的 HIDL）的 SELinux 套路和 native service 类似，但多了 **hwservice / binder 互通 / client 侧放行** 三件事。本例接口 `vendor.foo.IMyHal`，服务进程 `myhal-default`，并与 `automotive/03-VehicleHAL-VHAL详解.md` 的 VHAL 走同一套 binder 互通模型。

### 9.1 te 文件（服务端，完整可复制）

```te
# ===== myhal.te（服务端）=====
# 1) 进程域与可执行类型
type myhal_default, domain;
type myhal_default_exec, exec_type, file_type;
init_daemon_domain(myhal_default)

# 2) HAL 类型：供 server/client 宏引用
type myhal, hal_type;
# 3) 声明"我是 myhal 的服务端"——内部展开对 hwservicemanager /
#    servicemanager 的 add、对 binder 的相关许可
hal_server_domain(myhal_default, myhal)

# 4) hwbinder / binder 服务名标签类型
type myhal_hwservice, hwservice_manager_type;
# 5) 允许服务端 add + find 自己的 hwservice
allow myhal_default myhal_hwservice:hwservice_manager { add find };

# 6) 与 framework（system_server）双向 binder 互通
binder_call(myhal_default, system_server)
binder_call(system_server, myhal_default)
binder_use(myhal_default)
```

### 9.2 hwservice_contexts（完整可复制）

```text
# ===== hwservice_contexts（追加片段）=====
# 接口全名 → 打上 myhal_hwservice（对应 te 第 4 行）
vendor.foo.IMyHal      u:object_r:myhal_hwservice:s0
```

要点：接口名要和 `.aidl` 里的全限定名、以及服务注册时用的名字**完全一致**（含大小写与点号）。错一个字符，客户端 `getService` 就匹配不到，报 `SELinux: avc: denied { find }`。

### 9.3 init.rc（完整可复制）

```rc
# ===== init.<board>.rc（vendor 侧）=====
service myhal-default /vendor/bin/hw/vendor.foo.myhal@1.0-service
    class hal
    user system
    group system
    seclabel u:object_r:myhal_default_exec:s0
```

> 这是 **vendor** 服务，所以 rc、te、hwservice_contexts 都应落在 `/vendor/etc/selinux/` 与 `/vendor/etc/` 下，受 Treble 约束（[第七节](#七treble-带来的分区策略约束)）——`hal_server_domain` 这类宏已帮你对接 AOSP 公开的 `hal_type` attribute，不会踩 neverallow。

### 9.4 client 侧放行（完整可复制）

假设调用方是另一个域 `myclient`（可以是 native 服务，也可以是 system_app）：

```te
# ===== myclient.te（客户端，片段）=====
# 1) 声明它是 myhal 的客户端
hal_client_domain(myclient, myhal)
# 2) 允许 find 这个 hwservice（hal_client_domain 通常已含，但显式写更稳）
allow myclient myhal_hwservice:hwservice_manager find;
# 3) 允许向服务端发起 binder 调用
binder_call(myclient, myhal_default)
```

### 9.5 与 VHAL 的衔接（车机视角）

`automotive/03-VehicleHAL-VHAL详解.md` 里 CarService 通过 `android.hardware.automotive.vehicle` 这组 AIDL 接口访问 VHAL。它的 SELinux 模型**完全等同于本节**：VHAL 进程是 `hal_server_domain(<vhalsvc>, <vehiclehal>)`，CarService 所在域是 `hal_client_domain(<carservice域>, <vehiclehal>)`，二者靠 `binder_call` 互通。新人照本节三件套改，再在 client 域补 `hal_client_domain` 即可，不用重新发明。

### 9.6 验证

```bash
# 服务是否注册、域对不对
adb shell ps -Z | grep myhal-default
# binder 调用被拒时抓 avc，重点看 tcontext 是不是 myhal_hwservice
adb shell logcat -b events -d | grep avc | grep myhal
```

---

## 十、案例三：让系统 App 访问系统私有资源

`「Android 视角」` 默认情况下，所有普通 App 都落在 `untrusted_app`（或 `appdomain` 通用约束集）里，权限被压得很死。如果你有一个**平台签名系统 App** 需要读某个系统私有目录/属性，正确做法是**给它单独建一个域**，而不是去放宽 `untrusted_app`（那会放开所有 App）。

### 10.1 先看清三种 App 域

| 域 | 适用对象 | 能力边界 |
|---|---|---|
| **untrusted_app** | 普通第三方 App（uid 10000+） | 最严，只能碰自己的 app_data_file 和少量公开资源 |
| **priv_app** | `/system/priv-app` 下、且权限白名单放行的特权 App | 比 untrusted 宽，但仍受 many neverallow 约束 |
| **platform_app / 自定义域** | 平台签名 App，或你单独建的系统 App 域 | 按你写的 te 精确授权（最小权限原则） |

> 关键认知：**域越粗，风险面越大**。给 `untrusted_app` 加一条 allow，等于所有第三方 App 都拿到；给单独域加，只有这一个 App 拿到。

### 10.2 seapp_contexts（完整可复制）

```text
# ===== seapp_contexts（追加片段）=====
# 当满足 user=system（平台签名）且包名 = com.example.sysapp 时，
# 进程域设为 my_sysapp_app，数据文件类型设为 app_data_file
user=system seinfo=platform name=com.example.sysapp domain=my_sysapp_app type=app_data_file
```

要点：

- 字段顺序固定：`user`、`seinfo`、`name` 等用空格分隔，匹配规则从左到右。**`seinfo` 来自 `mac_permissions.xml`**（按签名打标签），平台签名 App 通常是 `platform`。
- `domain=` 决定进程域，`type=` 决定它的数据目录类型（一般沿用 `app_data_file` 即可）。
- 改完 seapp_contexts 需**重编或在设备上 `restorecon` + 重启 App**，因为域在进程 fork 时确定。

### 10.3 te 文件（完整可复制）

```te
# ===== my_sysapp_app.te =====
# 1) 进程域，纳入 appdomain 通用约束集（获得 App 应有的基础许可）
type my_sysapp_app, domain, appdomain;
# 2) 允许读系统私有数据目录（例如 /data/system/foo）
allow my_sysapp_app system_data_file:file { read getattr open };
allow my_sysapp_app system_data_file:dir { search read };
# 3) 允许通过 binder 调用系统服务
binder_call(my_sysapp_app, system_server)
# 4) 允许读某系统属性
get_prop(my_sysapp_app, system_prop)
# 5) 如需写自有属性，声明独立 prop 类型并放行（不要碰 default_prop）
#    allow my_sysapp_app my_sysapp_prop:property_service { set };
```

### 10.4 为什么不推荐直接改 untrusted_app / priv_app

```text
# 反例：为了省事给所有 App 放开
allow untrusted_app system_data_file:file { read };   # ❌ 所有第三方 App 都能读
# 正例：只给需要的系统 App 单独域
allow my_sysapp_app system_data_file:file { read };   # ✅ 仅此 App 可读
```

`「Android 视角」` 很多 CTS 用例专门检查 `untrusted_app` 不能越界访问系统资源，放宽它会导致 CTS 失败、且引入隐私/安全漏洞。

---

## 十一、案例四：新增设备节点 / 驱动访问

`「Android 视角」` `「车机视角」` 车机常要访问自定义字符设备（CAN、雷达、屏驱等）。设备节点在 `/dev` 下，由 **ueventd** 创建并打标，SELinux 侧要做两件事：给节点一个独立 type + 给访问方域放行 `chr_file` 权限。

### 11.1 file_contexts（完整可复制）

```text
# ===== file_contexts（追加片段）=====
# 设备节点 → 独立 type（不要复用 device 或 default 的笼统 type）
/dev/mydevice       u:object_r:mydevice_device:s0
```

### 11.2 ueventd.rc（完整可复制）

```rc
# ===== ueventd.<board>.rc（或 device.mk 里指定的 ueventd 片段）=====
# 节点路径  权限  属主  属组
/dev/mydevice       0660   system   system
```

要点：

- **ueventd 负责创建节点并按 ueventd.rc 设 uid/gid/perm（这是 DAC 层）**；节点创建后 ueventd 会用 file_contexts 里的规则给它打 SELinux type（这是 MAC 层）。两者缺一不可。
- 如果节点是**动态创建**（驱动 `device_create`），ueventd 同样按 file_contexts 匹配打标，无需手动 `mknod`。
- 改完 file_contexts，**重启后 ueventd 自动重打标**；调试时可 `adb shell ls -Z /dev/mydevice` 确认标签，不对就 `restorecon /dev/mydevice`（少数节点需重触发 uevent：`adb shell恢复` 后用 `restorecon -v`）。

### 11.3 te 文件（完整可复制）

```te
# ===== mydriver.te（访问方 daemon 侧）=====
# 1) 设备节点类型：dev_type 表示"这是设备"，配合 file_contexts
type mydevice_device, dev_type;
# 2) 允许 mydaemon 对该字符设备读写/打开/ioctl
allow mydaemon mydevice_device:chr_file { read write open ioctl getattr };
# 3) 若需要 ioctl 特定 cmd，可进一步用 ioctl 白名单（更严）
#    allowxperm mydaemon mydevice_device:chr_file ioctl { 0x1234 0x5678 };
```

### 11.4 完整闭环验证

```bash
# 1) 节点标签是否正确
adb shell ls -Z /dev/mydevice
#    期望：u:object_r:mydevice_device:s0
# 2) DAC 权限是否够（属主/组是否匹配访问方 uid/gid）
adb shell ls -l /dev/mydevice
# 3) 访问方域是否拿到 chr_file 权限，被拒就抓 avc
adb shell dmesg | grep avc | grep mydevice
```

> 常见坑：**只改了 ueventd.rc 没改 file_contexts**，节点落在默认 `device` type，于是访问方必须 `allow mydaemon device:chr_file ...`——这等于放开所有设备节点，既不安全也可能踩 AOSP neverallow。务必用独立 `mydevice_device` type。

---

## 十二、AVC 日志逐字段解读

### 12.1 一条典型的 avc（精简后）

```text
avc: denied { read } for pid=1234 comm="mysecuresvc" name="foo" dev="sda1" ino=12345
     scontext=u:r:mysecuresvc:s0
     tcontext=u:object_r:system_data_file:s0
     tclass=file permissive=0
```

### 12.2 逐字段读法（对照"三个问题"）

| 字段 | 对应三问 | 读法 |
|---|---|---|
| `denied { read }` | **how（权限）** | 被拒的是 `read` 这个 permission（可能一行有多条，如 `{ read write }`） |
| `pid=1234 comm="mysecuresvc"` | 诊断用 | 哪个进程、叫什么名字（comm 是进程名，便于定位，但不是 type） |
| `name="foo" dev= ino=` | 诊断用 | 目标文件/对象的名字、设备、inode，定位具体文件路径 |
| `scontext=u:r:mysecuresvc:s0` | **who（domain）** | 发起方域 = `mysecuresvc`（第 3 段 type 就是 domain） |
| `tcontext=u:object_r:system_data_file:s0` | **what（type）** | 目标对象的 type = `system_data_file`（第 3 段） |
| `tclass=file` | **how（class）** | 操作的客体类是 `file` |
| `permissive=0` | 模式 | `0`=真拒（enforcing），`1`=只是记日志（permissive 下） |

### 12.3 从一条 avc 反推应该补哪条 allow

把上面四要素直接套进 `allow` 模板：

```text
scontext 的域   = mysecuresvc
tcontext 的 type = system_data_file
tclass          = file
被拒 permission  = read

→ 缺的规则就是：
allow mysecuresvc system_data_file:file { read };
```

> 一眼记忆：**scontext 域 + tcontext 类型 + tclass + 被拒权限**，四样拼成一条 `allow`。多条权限就写进 `{ }` 里。

### 12.4 怎么取 avc 日志

```bash
# 1) 内核环形缓冲（最常见，enforcing 拒访都走这里）
adb shell dmesg | grep avc

# 2) logcat 的 events 缓冲（用户态审计也常落这里）
adb shell logcat -b events -d | grep avc

# 3) 全缓冲兜底
adb shell logcat -b all -d | grep -i "avc:"

# 4) 当前模式与策略状态（/sys/fs/selinux 是 selinuxfs）
adb shell cat /sys/fs/selinux/enforce        # 1 enforcing / 0 permissive
adb shell cat /sys/fs/selinux/deny_unknown   # 未知 class 默认拒绝=1

# 5) 实时抓：先清屏再复现操作
adb shell dmesg -C ; <复现操作> ; adb shell dmesg | grep avc
```

---

## 十三、audit2allow 的正确用法与三个陷阱

### 13.1 它能做什么

`audit2allow` 把抓到的 avc 日志**自动翻译成 te 片段**，是排错加速器，但绝不是"复制即交付"的工具。

```bash
# 把当前 dmesg 里的 avc 转成 te 规则
adb shell dmesg | grep avc | audit2allow

# 带上已编译策略做校验（-p），能提示是否与 neverallow 冲突
adb shell logcat -b events -d | grep avc | audit2allow -p out/target/.../sepolicy

# 只针对某个域生成，避免噪音
adb shell dmesg | grep avc | audit2allow | grep mydaemon
```

### 13.2 三个陷阱

| 陷阱 | 表现 | 危害 |
|---|---|---|
| **① 掩盖真实问题** | 一见 avc 就 `audit2allow` 然后全加 | 本该走标准 HAL/接口的设计缺陷被"允许"掩盖，越界访问固化 |
| **② 放行过宽** | 生成出 `allow mydaemon domain:...` 或 `allow mydaemon system:file { read }` | 等于把"所有域/整个 system"都放开，丧失 MAC 意义，且常踩 neverallow |
| **③ 破坏 neverallow** | 加的 allow 与 AOSP 的 neverallow 冲突 | 编译直接 fail，或强行绕过后 CTS/安全审计不过 |

> 经验法则：**audit2allow 的产物先人工审一遍**——域对不对、对象 type 是不是该用更细的独立 type、权限列表是不是刚好覆盖被拒的那几个，而不是整段粘贴。

### 13.3 临时手段：dontaudit 与 permissive domain

```te
# 1) dontaudit：只压制日志，不改变"拒"的结果（用于已知无害、不想刷屏的拒绝）
dontaudit untrusted_app mydata_file:file read;

# 2) permissive domain：调试期把"某单个域"设为 permissive（仅该域不真拒）
#    ⚠ 仅限 userdebug / eng，量产前必须删除
permissive mydaemon;
```

何时能用、何时必须收回：

- **dontaudit**：确认"这条拒绝本就不影响功能、且对象已是最小权限"时可用；**交付前评估**是否应补 allow 而非永久压制。
- **permissive domain**：只在 bring-up 定位阶段用，快速确认"是不是 SELinux 在挡"。**量产前必须删除**，否则该域等于裸奔，违背 [第二节 2.4](#二android-上的-selinux-形态enforcing--permissive) 的 enforced 要求。

---

## 十四、常见问题排查表

> 四列：**现象 | 原因 | 章节 | 第一命令**。遇到报错先按"第一命令"抓现场。

| 现象 | 原因 | 章节 | 第一命令 |
|---|---|---|---|
| `avc: denied { read }` 访问文件 | 缺对应 allow | [十二](#十二avc-日志逐字段解读) | `adb shell dmesg \| grep avc` |
| `avc: denied { execute }` 启动进程 | exec type 不对 / 缺 transition | [八](#八案例一新增一个-native-service) | `adb shell ls -Z /system/bin/<svc>` |
| `avc: denied { find }` hwservice | hwservice_contexts 没配或 client 未放行 | [九](#九案例二新增一个-aidl-hal) | `adb shell logcat -b events -d \| grep avc \| grep hwservice` |
| `avc: denied { call }` binder | binder_call 未配置 | [八/九](#八案例一新增一个-native-service) | `sesearch -A -s client -t server out/sepolicy` |
| `avc: denied { set }` 属性 | property_contexts 未配 / 非自有 prop | [六](#六上下文类文件各自管什么) | `adb shell getprop -Z <prop>` |
| 服务起来后仍在 `init` 域 | `init_daemon_domain` 漏或 seclabel 错 | [八](#八案例一新增一个-native-service) | `adb shell ps -Z \| grep <svc>` |
| file_contexts 改了不生效 | 没 restorecon / 路径写错 / 编译期打标 | [八](#八案例一新增一个-native-service) | `adb shell restorecon -R /path; ls -Z /path` |
| neverallow 编译失败 | vendor 越界 allow 违反 AOSP neverallow | [七](#七treble-带来的分区策略约束) | 看 build 日志 `neverallow ... at *.te:N` |
| enforcing 下静默失败、permissive 下正常 | 缺 allow（被真拒但没抛错） | [二/十二](#二android-上的-selinux-形态enforcing--permissive) | `adb shell setenforce 0` 复现后 `dmesg \| grep avc` |
| 设备节点访问被拒 | 缺 dev_type / chr_file allow 或 file_contexts 错 | [十一](#十一案例四新增设备节点--驱动访问) | `adb shell ls -Z /dev/<node>` |
| 属性设置被拒 | set_prop 目标 type 错 / 动了 default_prop | [六](#六上下文类文件各自管什么) | `adb shell getprop -Z <prop>; dmesg \| grep avc \| grep prop` |
| 系统 App 访问资源被拒 | 域未纳入 appdomain / 未单独建域 | [十](#十案例三让系统-app-访问系统私有资源) | `adb shell ps -Z \| grep <pkg>` |
| sysfs/proc 节点访问被拒 | 缺 genfs_contexts | [六](#六上下文类文件各自管什么) | `adb shell ls -Z /sys/...` 查是否落在默认 type |
| seapp_contexts 不生效 | seinfo 错 / mac_permissions 没匹配签名 | [十](#十案例三让系统-app-访问系统私有资源) | `adb shell id -Z; pm dump <pkg> \| grep seinfo` |
| audit2allow 生成的规则编译不过 | 与 neverallow 冲突 | [十三](#十三audit2allow-的正确用法与三个陷阱) | `sepolicy-analyze out/sepolicy neverallow` |
| avc denied 刷屏但功能正常 | 应 dontaudit 而非 allow | [十三](#十三audit2allow-的正确用法与三个陷阱) | 评估后 `dontaudit <域> <type>:<class> <perm>;` |
| 读 `/sys/fs/selinux/enforce` 失败 | selinuxfs 未挂载/权限 | [四](#四策略文件与加载链路) | `adb shell cat /sys/fs/selinux/enforce` |
| 服务反复重启 | 服务域缺核心 allow 被 init 杀 | [八/十二](#八案例一新增一个-native-service) | `dmesg \| grep avc` 看 init 相关拒绝 |
| 启动后整体 permissive | bootloader/kernel cmdline 带了 selinux=0 | [二](#二android-上的-selinux-形态enforcing--permissive) | `adb shell getprop ro.boot.selinux` |

---

## 十五、车机场景：vendor 加固与调试开关

`「车机视角」` 车载定制 ROM 的特殊性在于：**vendor 分区占比大、自研服务多、且安全要求高**（车控/仪表/座舱域隔离）。下面几条是车机开发中反复要做的。

### 15.1 厂商自定义域的组织建议

- **按子系统建域，不要一个大 `vendor_xxx` 通吃**：CAN 服务、雷达服务、座舱 HAL 各自独立域，互不可见，符合最小权限。
- **自有属性 / 节点 / hwservice 一律独立 type**：见 [第十一节](#十一案例四新增设备节点--驱动访问)、[第九节](#九案例二新增一个-aidl-hal)，别复用 AOSP 默认 type。
- **跨域通信只走 binder / 标准 HAL**：避免 `allow vendor_a system_server:...` 这类越界写法，否则 [第七节](#七treble-带来的分区策略约束) 的 neverallow 会拦。

### 15.2 量产前必须关掉 permissive

```bash
# 1) 确认没有残留的全局 permissive 开关
adb shell getprop ro.boot.selinux        # 必须 enforcing
# 2) 确认 te 里没有遗留的 permissive 域
grep -rn "permissive " device/ vendor/  # 应为空（user 版）
# 3) 确认 kernel cmdline 没带 selinux=0 / androidboot.selinux=permissive
adb shell cat /proc/cmdline | grep -o "selinux=[^ ]*"
```

> 量产前**早切 enforcing 多跑**：很多车机在 bring-up 全程 permissive，临量产才切 enforcing，结果一夜之间一堆功能"失声"。建议 **userdebug 阶段就周期性在 enforcing 下跑全功能回归**。

### 15.3 第三方 App 预装的标签策略

- 预装到 `/system/app` 或 `/system/priv-app` 的 App，按 [第十节](#十案例三让系统-app-访问系统私有资源) 决定要不要单独域；普通预装 App 保持 `untrusted_app` 最安全。
- `priv-app` 预装若要特权，**白名单（`privapp-permissions-*.xml`）与 SELinux 域要同时具备**，缺一不可（与 `14_权限机制详解` 的 privileged 权限联动）。

### 15.4 快速定位的调试开关

```bash
# 单域临时 permissive（仅 userdebug，定位用，见 13.3）
#   在对应 te 里加：permissive mydaemon;
# 抓全量 avc 的"最快姿势"
adb shell dmesg -C; <操作>; adb shell dmesg | grep avc
# 看某个域当前所有已授权规则
sesearch -A -s mydaemon out/target/.../sepolicy
```

---

## 十六、读源码路线 + 源码路径速查表

### 16.1 源码阅读顺序建议

1. **策略源**：先读 `system/sepolicy`（public / private / vendor 三套），理解 type/allow 怎么组织、宏在哪定义。
2. **用户态库**：`external/selinux/libselinux` —— `getenforce`、`setenforce`、`security_load_policy`、标签查询都在这里。
3. **init 的 SELinux 初始化**：`system/core/init/` 里负责 early mount、load policy、restorecon 的部分（相关控制器/一类初始化逻辑），看策略是怎么灌进内核的。
4. **审计通路**：`system/logging/logd` 与内核 audit 子系统的衔接——avc 如何从内核到 `dmesg` / `logcat -b events`。

### 16.2 源码路径速查表

| 内容 | 路径（AOSP） |
|---|---|
| 平台策略源（te / 宏 / 上下文） | `system/sepolicy/`（`public/`、`private/`、`vendor/` 子目录） |
| 宏定义（init_daemon_domain 等） | `system/sepolicy/public/te_macros` |
| 上下文文件模板 | `system/sepolicy/{file,property,hwservice,service,seapp,genfs}_contexts` |
| SELinux 用户态库 | `external/selinux/libselinux/` |
| init 的 SELinux 初始化 | `system/core/init/` 中负责 selinux 加载与 restorecon 的相关模块 |
| 策略编译/分析工具 | `external/selinux/` 下的 `checkpolicy`、`sepolicy-analysis` 一类工具 |
| 审计日志通路 | `system/logging/logd/` 与内核 `security/selinux/` + `kernel/audit` |
| 属性服务与标签 | `system/core/` 中属性服务相关实现 + `property_contexts` 加载逻辑 |

> 路径随 AOSP 版本有拆分调整（如部分模块迁到 `packages/modules/`），以你所用分支为准；不写死内部 API 签名。

---

## 十七、一图总结

### 17.1 一次访问的判定路径（ASCII）

```text
┌──────────────────────── 访问发生 ────────────────────────┐
│                                                          │
│   who? ── scontext = u:r:<domain>:s0                     │
│      │                                                   │
│      ▼                                                   │
│   what? ─ tcontext = u:object_r:<type>:s0                │
│      │                                                   │
│      ▼                                                   │
│   how? ── tclass=<class> + perm=<permission>             │
│      │                                                   │
│      ▼                                                   │
│   ┌─────────────── 查 sepolicy（内核里）──────────────┐  │
│   │  allow <domain> <type>:<class> { <perm> }; 存在？ │  │
│   └──────────────┬───────────────────────────────────┘  │
│                  │                                       │
│        ┌─────────┴─────────┐                             │
│     存在(allow)         不存在(默认拒绝)                  │
│        │                   │                             │
│        ▼                   ▼                             │
│     放行                avc: denied 日志                  │
│                       ┌───┴───┐                          │
│                   enforcing  permissive                  │
│                     真拒        只记不拒                  │
└──────────────────────────────────────────────────────────┘
```

### 17.2 一句话记忆链

> **员工证（domain）拿去刷 门（type）的 刷卡动作（permission），门禁总表（sepolicy）说不行就记一条 avc（denied），enforcing 真拦、permissive 只记。**

三问闭环：**who（domain）→ what（type）→ how（class+perm）**，拼成一条 `allow`，缺哪段补哪段。

---

## 十八、关联阅读

- `Android权限机制详解-从Permission到AppOps.md`（同目录 `androidFrameworks/`）：**DAC 侧**——Manifest 权限、AppOps、uid 资格判断；本篇是 **MAC 侧**，两者互补（先看那篇懂"资格"，再看本篇懂"行为边界"）。
- `AndroidNDK与JNI详解-从跨语言调用到so加载与Native崩溃排查.md`（同目录 `androidOthers/`）：native 代码仍跑在 UID + SELinux 域里，[第十六节]里强调"native 不脱出 MAC"。
- `automotive/03-VehicleHAL-VHAL详解.md`：案例二的 HAL binder 互通模型直接衔接 VHAL；车载 HAL 的 SELinux 套路与 [第九节](#九案例二新增一个-aidl-hal) 完全一致。
- `automotive/04-车辆属性权限与系统App开发.md`：系统 App 域与车辆属性访问，呼应 [第十节](#十案例三让系统-app-访问系统私有资源)。
- `Android存储机制详解-从分区到ScopedStorage与多用户.md`（同目录 `androidOthers/`）：分区与 `/data` 标签，理解 `file_type` / `data_file_type` 的落地背景。
- `Android构建机制详解`（同目录 `androidOthers/` 构建篇，若存在）：sepolicy 的 `Android.bp` / `file_contexts` 如何编进各自分区镜像。

> 本篇刻意只讲 SELinux 这一层，不重复 DAC 权限（见权限机制篇）、不重复 Binder 传输细节（见 NDK/VHAL 篇）。交叉引用已按实际文件名标注。


