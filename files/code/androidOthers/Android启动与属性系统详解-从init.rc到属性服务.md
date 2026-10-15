# Android 启动与属性系统详解——从 init.rc 到属性服务

> 这篇是 `androidFrameworks/01_Android启动流程详解` 的**姊妹篇 + 补充篇**。01 篇站在"Java Framework 视角"讲了 init → Zygote → system_server 的大脉络；本篇站在"init 自身视角"，把 01 篇只是一笔带过、但 ROM/vendor 开发者天天要改的三件事讲透：
>
> **init 是 Android 第一个用户态进程（PID 1），同时管三件事——挂载（mount）、起服务（service）、管属性（property）。**
>
> 知识形状是**类交互型**：围绕 init 这个"主管"和它手下的 rc 文件、property_service、service 对象之间的调用与状态机展开。读完你能自己"加一个开机自启的 native 服务""看懂 rc 语法""知道某个 `getprop` 的值为啥设不上""排查服务起不来的 avc denied"。

---

## 目录

**第一段：init 是什么、管什么**

1. [开场：init 的三重身份](#一开场init-的三重身份)
2. [启动时序全景（只补 01 篇没讲的细节）](#二启动时序全景只补-01-篇没讲的细节)
3. [init 一阶段与二阶段](#三init-一阶段与二阶段)
4. [init.rc 语言](#四initrc-语言)

**第二段：rc 来自哪、属性怎么来**

5. [rc 文件的来源与加载顺序](#五rc-文件的来源与加载顺序)
6. [属性系统：命名约定与产出关系](#六属性系统命名约定与产出关系)
7. [属性的读写规则](#七属性的读写规则)
8. [属性的系统实现](#八属性的系统实现)
9. [属性与服务的联动](#九属性与服务的联动)

**第三段：实战、调试与车机**

10. [完整案例：新增一个 native service（7 步清单）](#十完整案例新增一个-native-service7-步清单)
11. [调试与命令](#十一调试与命令)
12. [常见问题排查表](#十二常见问题排查表)
13. [车机场景](#十三车机场景)
14. [读源码路线 + 源码路径速查表](#十四读源码路线--源码路径速查表)
15. [一图总结](#十五一图总结)

---

## 一、开场：init 的三重身份

把 init 想成"系统的大管家"，它一个人干三份工：

```
        ┌─────────────────────────────────────────────┐
        │  init (PID 1) —— Android 第一个用户态进程      │
        ├──────────────┬──────────────┬────────────────┤
        │ ① 挂载主管    │ ② 服务主管    │ ③ 属性主管      │
        │ 读 fstab      │ 解析 rc       │ 维护属性服务    │
        │ 挂只读分区    │ 起/管 service │ getprop/setprop │
        │ 挂 /data(解密)│ 重启崩溃服务  │ on property:触发│
        └──────────────┴──────────────┴────────────────┘
```

| 身份 | 负责 | 你改得最多的地方 |
|---|---|---|
| **挂载主管** | 按 fstab 挂载各分区、触发 fs_mgr、解密 /data | `fstab.<device>`、rc 里的 `mount` 命令 |
| **服务主管** | 解析 `*.rc`，启动/监控 native 服务，崩溃按 `onrestart` 重启 | `*.rc` 的 `service` 段落 |
| **属性主管** | 运行 property_service，处理 `getprop`/`setprop`，响应 `on property:` 触发 | `*.prop`、`PRODUCT_PROPERTY_OVERRIDES`、rc 的 `on property:` |

**类比**：init 像一栋大楼的"总物业"——① 负责通水电（挂载分区、解密数据）；② 负责安排各承包商按时开工并盯他们别跑路（服务启停与重启）；③ 负责楼内的广播喇叭与公告栏（属性：谁都能看、特定人能改、改了会触发通知）。

「**Android 视角**」App 开发者几乎不碰 init；但做 ROM/vendor 的，**开机自启服务、默认属性、工厂模式、ACC 电源管理**全在这三件事里。

---

## 二、启动时序全景（只补 01 篇没讲的细节）

01 篇已经给出主干：`bootloader → kernel → init → zygote → system_server`。本篇只补 init 视角下、01 篇没展开的**细节节点**：

```text
bootloader 加载 boot 分区（kernel + ramdisk）
  └─ kernel 启动，挂 ramdisk 为根，execve("/init")  → PID 1
       ├─ 【第一阶段】early-init：挂载 tmpfs、创建基础目录、加载 SELinux 策略早期部分
       ├─ 重新挂载根（switch root 到真实根，若有）
       ├─ 【第二阶段】init 主体：
       │    ├─ 初始化 property_service（建共享内存区域）
       │    ├─ 解析所有 *.rc（import 链见第 5 章）
       │    ├─ 触发 early-fs / fs / post-fs 等 action（挂载各分区）
       │    ├─ fs_mgr 按 fstab 挂载 system/vendor/product（只读）与 /data（解密，见存储篇）
       │    ├─ 触发 late-fs / boot 等 action
       │    ├─ 启动 class core 服务（servicemanager、hwservicemanager、vold、logd…）
       │    ├─ 启动 class main 服务（zygote、surfaceflinger…）
       │    └─ 启动 class late_start 服务（大部分 vendor/odm 服务、车机定制服务）
       └─ init 进入死循环：监听属性变化、子进程退出（重启服务）、uevent、键盘组合键
```

**与 01 篇的差异点**（01 篇没讲、本篇重点）：

1. **two-stage init**（第 3 章）：init 分两阶段，因为 SELinux 与属性要在挂载系统分区前就位；
2. **property_service 在挂载系统前就初始化**（第 6 章）：所以 `ro.` 属性在最早阶段就能读；
3. **service 按 class 分批启动**（第 9 章）：`core` → `main` → `late_start`，车机自己加的服务多挂在 `late_start`；
4. **init 的主循环**（第 8、9 章）：它不死，靠事件循环同时管属性、服务重启、uevent。

### 2.3 启动阶段对照表（什么时候该干什么）

init 内部按一串命名的 action 阶段推进，理解它们能精确定位"我的服务/命令该挂哪"：

| 阶段（trigger） | 时机 | 适合做的事 | 不该做的 |
|---|---|---|---|
| `early-init` | 最早，系统分区未挂 | 建基础目录、早期 SELinux | 访问 /system 文件 |
| `init` | 早期初始化 | 核心节点、属性早期设置 | 起依赖 Framework 的服务 |
| `early-fs` / `fs` / `post-fs` | 挂载分区 | 按 fstab 挂 system/vendor、解密 /data | — |
| `post-fs-data` | /data 解密后 | 建 /data 下目录、恢复 persist | 重活（拖开机） |
| `boot` | 主要启动 | 起 class main、发早期就绪信号 | 极重服务（改 late_start） |
| `late-start` / `class late_start` | 最晚 | **车机自有服务、重 daemon** | 影响首屏的东西 |
| `charger` | 充电关机态 | 充电画面相关 | 正常开机路径不涉及 |

**经验法则**：怀疑"我的命令/服务太早跑了（文件还没挂、/data 还没解密）"，就把它从 `on boot` 往后挪到 `on property:sys.boot_completed=1` 或 `late_start`——绝大多数"启动期找不到文件/属性读不到"都是时机错配。

---

## 三、init 一阶段与二阶段

### 3.1 为什么分两阶段（two-stage init）

**引入版本**：Android 8.0（Treble）起明确 two-stage init；更早是单阶段。

根因：**SELinux 策略与属性系统需要先于"挂载并访问系统分区"准备好**，否则会有鸡生蛋问题——策略文件在 `/system` 上，但要读它又得先有安全上下文。

两阶段把"不依赖系统分区的早期工作"和"依赖系统分区的主工作"切开：

| 阶段 | 时机 | 关键动作 |
|---|---|---|
| **第一阶段（first stage）** | kernel 起来后立即 | 挂 tmpfs 为 `/dev`、`/tmp`；初始化早期 SELinux（加载 `/sepolicy` 或 `plat_sepolicy`）；创建基础目录；准备 property 区域 |
| **第二阶段（second stage）** | 重新 exec init 自身 | 重新挂载根文件系统；加载完整 SELinux 策略；初始化 property_service；解析 rc；挂载各分区；起服务 |

### 3.2 two-stage init 关键动作清单

```text
第一阶段（first stage init）：
  1. 挂载 tmpfs 到 /dev、/tmp，创建 /dev/pts、/dev/socket
  2. 挂载 /sys、/proc
  3. 加载早期 SELinux 策略（决策：是 enforcement 还是 permissive）
  4. 创建 /dev/.booting 等标志
  5. 重新 exec /init（带上 --second-stage 参数）→ 进入第二阶段

第二阶段（second stage init）：
  1. 重新挂载根（switch_root），挂上真实 /system 等
  2. 加载完整 SELinux 策略（load_policy）
  3. property_service 初始化：建立 __system_property_area__ 共享内存
  4. 解析 /init.rc 及 import 链（第 5 章）
  5. 按顺序触发 action：early-init → init → early-fs → fs → post-fs → … → boot
  6. 挂载分区（fs_mgr 按 fstab）
  7. 启动 class core / main / late_start 服务
  8. 进入事件循环
```

**调试意义**：如果设备"卡在 logo 之前"（连 rc 都没跑），多半是第一阶段或 fstab/SELinux 问题；如果"卡在 boot animation"，多半是 `late_start` 服务或 system_server 起不来（后者见 01 篇）。

---

## 四、init.rc 语言

`*.rc` 是 Android 的**初始化配置语言**（不是 shell 脚本）。它描述"在什么触发条件下执行什么命令、启动什么服务"。

### 4.1 三大 section 类型

| section | 关键字 | 作用 |
|---|---|---|
| `on` | `on <trigger>` | 一组 action（命令序列），在被 trigger 触发时执行 |
| `import` | `import <path>` | 引入另一个 rc 文件（构成加载链） |
| `service` | `service <name> <path> [args]` | 定义一个 native 服务及其参数 |

### 4.2 on / action / trigger / command

```rc
# on <trigger>：当 trigger 命中时，依次执行下面的 command
on boot
    # trigger 内是命令（command），每行一个
    mkdir /dev/cool 0700 root root
    chmod 0666 /dev/cool
    start coolservice          # 启动名为 coolservice 的 service
    setprop sys.cool.ready 1   # 设属性

# 常见 trigger 关键字
on early-init                  # 最早
on init
on late-init
on early-fs / on fs / on post-fs   # 挂载相关
on boot                        # 主要启动阶段
on charger                     # 充电模式
on property:sys.boot_completed=1   # 属性触发（第 9 章）
```

**trigger 的两类**：
- **事件 trigger**：`boot`、`late-init` 等，由 init 内部在特定阶段触发；
- **属性 trigger**：`on property:xxx=y`，当属性 `xxx` 被设为 `y` 时触发（强大，见第 9 章）。

**常用 command**（节选）：`mkdir`、`mount`、`chmod`、`chown`、`symlink`、`write`、`copy`、`setprop`、`start`、`stop`、`restart`、`exec`、`exec_start`、`trigger`、`wait`、`rm`、`restorecon`。

### 4.3 service 字段全表（重点）

```rc
service coolservice /system/bin/coolservice
    class main                    # 所属 class，决定何时启动（core/main/late_start/自定义）
    user root                    # 运行 uid（默认 root，建议降权）
    group root system            # 附加 gid 列表
    seclabel u:r:coolservice:s0  # SELinux 域（不写则继承，但推荐显式写）
    capabilities NET_ADMIN       # Linux capabilities（精细授权，替代 root）
    socket coolservice stream 660 system system   # 创建 /dev/socket/coolservice
    file /dev/cool w             # 打开文件描述符传给服务
    onrestart restart othersvc   # 本服务重启时连带重启 othersvc
    oneshot                      # 只启动一次，退出不重启
    disabled                     # 不随 class 自动启动，需显式 start
    shutdown critical            # 关机时优先/必须优雅停（车机重要）
    critical                     # 崩溃 4 次/4 分钟内 → 系统重启进 recovery
```

| 字段 | 含义 | 车机注意 |
|---|---|---|
| `class` | 启动分组 | 自定义服务多挂 `late_start`（系统起完再起） |
| `user`/`group` | 运行身份 | **别都用 root**，按需降权 |
| `seclabel` | SELinux 域 | 必须配对应 te 策略，否则 avc denied（第 10 章） |
| `capabilities` | Linux 能力 | 给最小必要能力，替代全 root |
| `socket` | 建 `/dev/socket/` 通信点 | 与 framework 通过 socket 通信常用 |
| `onrestart` | 重启联动 | 依赖服务崩溃时连带处理 |
| `oneshot` | 一次性 | 跑完即退的脚本类 |
| `disabled` | 不自启 | 需别处 `start` 才起 |
| `critical` | 频繁崩溃致重启 | 核心服务用，防止静默失效 |

### 4.4 exec_start 与 exec

```rc
# exec：同步执行一个命令（阻塞当前 action，直到命令结束），可指定上下文
exec u:r:cooldomain:s0 root -- /system/bin/setup.sh

# exec_start：启动一个已定义的 service 并等待它退出（区别于 start 是异步）
exec_start coolsetup
```

**区别**：`start` 异步（丢出去就继续）、`exec_start` 等它退出、`exec` 是直接跑命令（不定义为 service）。初始化脚本里做"挂载后跑个一次性配置"常用 `exec`。

---

## 五、rc 文件的来源与加载顺序

### 5.1 各分区的 rc 来源

init 在启动时会扫描多个分区的 `etc/init/` 目录，**分散在各分区**是 Treble 的设计——vendor/odm 自己的服务自己带 rc，不污染 system。

| 来源路径 | 分区 | 内容 |
|---|---|---|
| `/system/etc/init/` | system | AOSP 自带服务（servicemanager、logd…） |
| `/system_ext/etc/init/` | system_ext | 系统扩展服务 |
| `/vendor/etc/init/` | vendor | **芯片厂/BSP 服务**（HAL 类 daemon） |
| `/odm/etc/init/` | odm | **ODM 定制服务**（车机厂商常用） |
| `/product/etc/init/` | product | 产品预装服务 |
| `/boot` 里的 ramdisk `init.rc` | ramdisk | 最早的根 init（第一阶段用） |
| 设备目录 `init.<device>.rc` | 随 boot.img | 设备专属早期配置 |

### 5.2 import 链与同名冲突

```rc
# 主 init.rc 里会 import 子文件，形成链：
import /init.${ro.hardware}.rc
import /vendor/etc/init/hw/init.${ro.hardware}.rc
import /system/etc/init/...
```

**加载顺序要点**：
- 先加载 `/init.rc` 及其 import 链，再扫描各分区 `etc/init/` 下的所有 rc；
- **同名服务/action**：后加载的不会覆盖先加载的同名 service（init 要求 service 名全局唯一，重名会报错）；action 同名会**合并**触发；
- **冲突排查**：同一 service 名在 system 和 vendor 各定义一份 → init 解析报错 "duplicate service"。统一改名字或收敛到一处。

### 5.3 你加 rc 的正确落点

车机自定义服务 rc 应放 `device/<vendor>/<product>/` 并随镜像进 `vendor/etc/init/` 或 `odm/etc/init/`（通过 `PRODUCT_COPY_FILES`，呼应构建篇第 7 章），**不要直接改 AOSP 的 `/system/etc/init`**。

---

## 六、属性系统：命名约定与产出关系

### 6.1 属性名结构与命名约定

Android 属性本质是"全局键值对（key-value）"。**前缀决定语义与权限**：

| 前缀 | 含义 | 例子 | 谁来写 |
|---|---|---|---|
| `ro.` | read-only，只读（设一次后不可改） | `ro.build.version.sdk` | 构建期写死 |
| `persist.` | 持久化（改了落盘，重启保留） | `persist.sys.timezone` | 系统/用户 |
| `sys.` | 系统运行状态（常由 init/service 维护） | `sys.boot_completed` | init 框架 |
| `ctl.` | 控制指令（不是值，是动作） | `ctl.start` / `ctl.stop` | 触发服务启停 |
| `vendor.` / `odm.` | 厂商/ODM 自定义 | `vendor.audio.mode` | vendor 侧 |
| `debug.` | 调试开关 | `debug.sf.enable` | 开发者 |
| `persist.vendor.` | 厂商持久化 | `persist.vendor.touch` | vendor 侧 |

**命名约定铁律**：
- 自定义属性**务必带 `vendor.`/`odm.`/`persist.vendor.` 前缀**，否则可能与 AOSP 未来新增属性冲突；
- `ctl.start=<svc>`、`ctl.stop=<svc>` 是"触发动作"而非"存值"——设它们等于给 init 发命令。

### 6.2 property_contexts 的作用

属性不是"随便一个字符串"，每条属性在 SELinux 下都归属一个**属性类型（property_type）**，由 `property_contexts` 文件定义：

```text
# property_contexts 片段：把属性名模式映射到 SELinux 类型
ro.build.*          u:object_r:build_prop:s0
persist.vendor.*    u:object_r:vendor_prop:s0
ctl.start           u:object_r:ctl_prop:s0
```

**意义**：进程想 `setprop` 某属性，必须拥有对该属性类型写权限的 SELinux 规则，否则 **avc denied**（第 12 章高频坑）。所以"属性设不上"往往不是命令错，是 SELinux 没授权。

### 6.3 build.prop / system.prop / PRODUCT_PROPERTY_OVERRIDES 的产出关系

这三个概念容易混，讲清链路：

```
构建期：
  build/make 收集所有属性来源
    ├─ 系统默认：build/core/*.prop、system/build.prop 模板
    ├─ 设备层：device/<vendor>/<product>/system.prop（若存在）
    └─ 产品配置：PRODUCT_PROPERTY_OVERRIDES += "k=v"   ← 你在 device.mk 写的地方
        ↓ 合并（有优先级：后写的覆盖先写的；system.prop 通常优先于 overrides 视版本）
  生成：
    /system/build.prop       （只读，ro. 与大部分属性在这）
    /vendor/build.prop       （vendor 属性）
    /product/build.prop
  运行时 init 加载这些文件 → 填入 property 共享内存
```

**优先级（常见）**：`*.prop` 文件 > `PRODUCT_PROPERTY_OVERRIDES`（具体版本有差异，Android 10+ 对 `persist.` 的处理更严格）。排查"我设的属性为啥不生效"先 `getprop` 看实际值，再比对 device.mk 与 prop 文件的覆盖关系（呼应构建篇第 6.2 节）。

---

## 七、属性的读写规则

### 7.1 ro. 只读一次

`ro.` 属性在**首次设置后不可修改**（setprop 第二次会失败/被忽略）。它们通常在构建期或 init 早期写好（如 `ro.build.fingerprint`）。想"运行时可变"的信息**绝不能用 `ro.`**。

### 7.2 persist. 落盘与恢复

`persist.` 属性改动会写入 `/data/property/` 下的对应文件（按属性名存），**重启后由 init 在 early 阶段重新加载**，所以值能跨重启保留。

```bash
# 看持久化属性实际落盘内容
adb shell ls /data/property/        # 文件名就是属性名（去掉点？视版本）
adb shell cat /data/property/persist.sys.xxx
```

**坑**：`/data` 未解密前（DE 阶段之前，见存储篇）persist 属性读不到——所以"开机很早期就读 persist 配置"的服务会扑空。

### 7.3 ctl.start / ctl.stop 触发服务

`setprop ctl.start <服务名>` 等于让 init 启动该 service；`ctl.stop` 停止。这是**从 shell 或 App（经 SELinux 授权）控制 native 服务**的标准方式。

```bash
adb shell setprop ctl.start coolservice     # 启动 coolservice
adb shell setprop ctl.stop  coolservice     # 停止
# 注意：ctl. 不是存值，set 完立刻返回，状态看 init.svc.<name>（第 9 章）
```

### 7.4 属性长度与类型限制

- **key 长度**：上限通常 32 字节左右（含前缀）；超长被截断/拒绝；
- **value 长度**：上限通常 92 字节（旧版更小，新版放宽但仍有限）；长字符串（如大 JSON）**不适合塞属性**，应改走文件或 binder；
- **类型**：属性只有字符串一种类型，没有 int/bool。布尔用 `"0"/"1"` 约定，数字比较要自己 parse。

### 7.5 SELinux 对 setprop 的限制（指向 SELinux 篇）

任何 `setprop` 在 SELinux enforcing 模式下都受 `property_contexts` + 域规则约束。进程域（如 `untrusted_app`）默认**只能写极少属性**；写 `vendor.` 需要 `vendor_prop` 的写权限，写 `ctl.` 需要 `ctl_prop` 权限。被拒时 `dmesg` 里出现 `avc: denied { write } for property=...`。（SELinux 篇有完整策略写法。）

---

## 八、属性的系统实现

### 8.1 property_service 与共享内存

属性系统的核心是一个**单一数据源 + 多进程共享**：

```
init 进程（属性服务）
  ├─ 维护属性表（内存结构）
  └─ 把属性区域 mmap 成共享内存：__system_property_area__
        ├─ 应用进程（bionic）映射同一块 → 直接读（零拷贝）
        ├─ shell / 其他进程映射同一块 → 直接读
        └─ 写属性：通过 binder 或 socket 请求 init 的 property_service 修改
```

**为什么这样设计**：属性被巨量进程高频读，如果每个读都走 binder 调用 init，开销爆炸。共享内存让"读"几乎免费，"写"才走 init 一次。

### 8.2 bionic 侧直读优化（Android 9+）

从 Android 9 起，`__system_property_find` / `__system_property_read` 等 bionic 函数**直接读 `__system_property_area__` 共享内存映射，不再每次走 binder**。这是一次重要性能优化：读属性从"IPC 往返"变成"本地内存读"。

```c
// bionic 侧（概念示意，非让你写）
const prop_info *pi = __system_property_find("ro.build.version.sdk");
if (pi) __system_property_read(pi, NULL, buf);   // 直接读共享区
```

**推论**：`getprop` 命令也是走这套直读，所以极快；但"写"仍需联系 init。

### 8.3 属性变更通知与 watch 机制

当属性被写，init 会**通知所有 watch 了该属性的进程**（通过共享内存里的序列号/标志）。监听方式：

```c
// bionic: 注册一个属性变更回调（C/C++ 侧，native 服务常用）
const prop_info *pi = __system_property_find("sys.boot_completed");
__system_property_area__  // watch 通过对比区域版本号实现
```

Java 侧：`SystemProperties.addChangeCallback()` 或在 native 守护里轮询/回调。init 的 `on property:xxx=y` 触发器（第 9 章）底层就是这套通知机制。

---

## 九、属性与服务的联动

这是 init 最巧妙的设计之一：**属性变化可以直接触发 action 或控制服务**。

### 9.1 init.svc.<name> 状态属性

每个 service 自动暴露一个 `init.svc.<name>` 属性，反映其状态：

```
init.svc.coolservice == "running" | "stopped" | "restarting"
```

你可以用它做"等服务起来再干别的"的脚本逻辑（轮询该属性）。

### 9.2 on property: 触发器

```rc
# 当 sys.boot_completed 被设为 1，执行这批命令
on property:sys.boot_completed=1
    start late_coolservice          # 开机完成后再起某个服务
    setprop vendor.ready 1

# 当某个 vendor 属性变化，切换模式
on property:vendor.mode=lowpower
    setprop vendor.cpufreq 1
```

**这是"事件驱动启动"的核心**：不用在 `on boot` 里顺序硬等，而是"某条件满足就触发"。车机常用来做"开机完成 → 起车机专属服务""工厂模式属性变化 → 切换行为"。

### 9.3 start / stop / restart 命令

```rc
service coolservice /system/bin/coolservice
    class late_start
    disabled

on boot
    # 不在 boot 时自动起（disabled），由下面条件触发
on property:vendor.start_cool=1
    start coolservice
on property:vendor.start_cool=0
    stop coolservice
```

`restart` = stop 立即再 start（常用于配置热更新后重拉服务）。

### 9.4 service 的 class 与 class_start

```rc
# 一次性启动某 class 下所有服务
on boot
    class_start core          # 启 core 类
    class_start main          # 启 main 类
    class_start late_start    # 启 late_start 类（车机自定义服务多在这）
```

`class_start <name>` 触发该类所有非 disabled 服务启动。自定义服务放哪个 class 决定它的启动时机——核心底层用 `core`，依赖Framework的用 `main`，最晚的定制用 `late_start`。

---

## 十、完整案例：新增一个 native service（7 步清单）

目标：加一个名为 `coolservice` 的 native 守护，开机自启、以独立 SELinux 域运行、能被属性控制启停。每一步都给可复制内容。

### 步骤 1：写 rc 文件

```rc
# device/<vendor>/<product>/coolservice.rc
service coolservice /system/bin/coolservice
    class late_start              # 系统起完再起
    user system                   # 降权，不用 root
    group system
    seclabel u:r:coolservice:s0   # 显式指定 SELinux 域（必须步骤 3 配策略）
    capabilities NET_ADMIN        # 仅给必要能力
    oneshot                       # 自行常驻就用默认；跑完退出用 oneshot
    onrestart restart dependency  # 崩溃连带处理（如有依赖）

on property:vendor.cool.enable=1
    start coolservice
on property:vendor.cool.enable=0
    stop coolservice
```

### 步骤 2：编译模块（Android.bp）

```bp
cc_binary {
    name: "coolservice",
    srcs: ["coolservice.cpp"],
    shared_libs: ["libcutils", "liblog"],
    vendor: true,                       # 进 vendor 分区
    init_rc: ["coolservice.rc"],        # 关键！rc 随模块一起打包进 vendor/etc/init
    sepolicy: ["coolservice.te"],       # 关键！SELinux 策略一起编（步骤 3）
}
```

注意 `init_rc` 和 `sepolicy` 字段——它们让 rc 与 te **随模块自动部署到正确分区**，比手写 `PRODUCT_COPY_FILES` 更稳（呼应构建篇第 7 章）。

### 步骤 3：写 SELinux 策略

```te
# coolservice.te
type coolservice, domain;
type coolservice_exec, exec_type, file_type;

# 允许它作为 domain 运行、过渡执行
init_daemon_domain(coolservice)
# 给它必要的文件/属性访问（最小权限）
allow coolservice vendor_prop:property_service set;   # 允许写 vendor 属性
allow coolservice self:capability net_admin;
```

同时在 `file_contexts` 里把可执行文件标域：

```text
/system/bin/coolservice  u:object_r:coolservice_exec:s0
```

### 步骤 4：编入镜像

```bash
m nothing                 # 先验证 bp/te 语法
mmm device/<vendor>/<product>/coolservice   # 局部编
# 或全编
m vendorimage
```

### 步骤 5：验证 getprop / ps

```bash
adb shell ps -A | grep coolservice      # 看进程是否起来、什么 user
adb shell getprop init.svc.coolservice  # 看状态 running/stopped
adb shell getprop vendor.cool.enable    # 看触发属性
```

### 步骤 6：排查（起不来时）

```bash
adb shell dmesg | grep -i avc          # SELinux 拒绝？看 coolservice 域缺什么
adb shell logcat -b kernel | grep cool # native 日志
adb shell ps -A | grep coolservice     # 是否立刻退出（oneshot/崩溃）
```

### 步骤 7：交付清单

- [ ] rc 用 `init_rc` 随模块进 `vendor/etc/init`
- [ ] te 用 `sepolicy` 随模块编，且 `file_contexts` 标了 exec 域
- [ ] 服务以非 root 身份运行（`user`/`group`）
- [ ] 仅授予最小 `capabilities`
- [ ] 触发属性（如 `vendor.cool.enable`）与 rc 的 `on property:` 对应
- [ ] 真机验证 `ps`/`getprop init.svc.` 正常，无 avc denied

---

## 十一、调试与命令

```bash
# ── 属性 ──────────────────────────────
getprop                          # 列出全部属性
getprop ro.build.version.sdk     # 读单个（直接读共享内存，极快）
getprop init.svc.<name>          # 看服务状态
setprop vendor.xxx 1             # 写属性（受 SELinux 约束）
setprop ctl.start <svc>          # 启动服务（不是存值！）
setprop ctl.stop  <svc>          # 停止服务

# ── 进程与服务 ─────────────────────────
ps -A | grep <svc>               # 看进程是否存在/什么 user
service list                     # 看 binder 服务（native 服务若注册 binder 会列）
adb shell dumpsys activity services  # 看 Java 侧服务状态（配合）

# ── 内核与 init 日志 ───────────────────
dmesg | grep -i avc              # SELinux 拒绝（属性/文件访问被拦）
dmesg | grep init                # init 自身日志
logcat -b kernel                 # 内核日志缓冲
logcat -b all | grep -i "init\|cool"  # init 相关

# ── 启动进度 ───────────────────────────
getprop sys.boot_completed       # 1 = 开机完成（on property 触发点）
getprop init.svc.servicemanager  # 核心服务状态

# ── rc 文件检查 ────────────────────────
adb shell ls /vendor/etc/init/   # 看 rc 是否真的部署进去
adb shell cat /vendor/etc/init/coolservice.rc
```

**每条命令的期望**：`getprop` 应秒回；`ps -A | grep` 应能看到你的服务进程且 user 非意外；`dmesg | grep avc` 排障时**应当无输出**（有就说明 SELinux 拦了）。

---

## 十二、常见问题排查表

| 现象 | 最可能的原因 | 章节 | 第一命令 |
|---|---|---|---|
| 服务起不来（进程没有） | rc 没部署到对应分区 etc/init | 5.1、10.1 | `adb shell ls /vendor/etc/init/` |
| cannot execve / exec format error | 二进制架构不对 / 没执行权限 / 缺动态库 | 4.3、10.2 | `adb shell ls -l /system/bin/<svc>` |
| avc denied（服务启动即被拦） | SELinux 域/策略缺 allow | 10.3、7.5 | `adb shell dmesg \| grep avc` |
| 属性设不上（setprop 无效果） | SELinux 无该属性类型写权限 / 属性只读 | 6.2、7.1 | `dmesg \| grep avc` + `getprop 属性名` |
| sys.boot_completed 卡在 0 | system_server 未发完成广播 / late_start 卡住 | 2、9.2 | `getprop sys.boot_completed` |
| persist 属性丢失（重启后没了） | 写在非 persist 前缀 / /data 未解密时读 | 7.2、6.1 | `adb shell ls /data/property/` |
| 重启循环（bootloop） | 某 `critical` 服务频崩 / rc 语法错 | 4.3、3.2 | `dmesg \| grep init` + `logcat -b kernel` |
| 依赖顺序错误（服务早起了） | class 选错 / 没用 on property 等条件 | 9.4、4.3 | `getprop init.svc.<dep>` |
| on property 触发没反应 | 属性名/值不匹配 / 属性被 SELinux 拦写 | 9.2、6.2 | `getprop 该属性` 核对值 |
| ctl.start 无效 | 服务名拼错 / 服务 disabled 且无 start 路径 | 7.3、9.3 | `getprop init.svc.<name>` |
| 服务以 root 跑（安全隐患） | rc 未写 user/group | 4.3 | `ps -A \| grep <svc>` 看 user |
| rc 解析报错（duplicate service） | 同名 service 在多个 rc 定义 | 5.2 | `dmesg \| grep "duplicate service"` |
| build.prop 里没我的属性 | PRODUCT_PROPERTY_OVERRIDES 漏写/被覆盖 | 6.3 | `getprop 我的属性` |
| init 卡在挂载前（连 logo 都没有） | 第一阶段/fstab/SELinux 早期问题 | 3.2 | `dmesg`（抓 earliest） |
| 服务起来又立刻退出 | 程序自身崩溃 / 依赖文件不存在 | 10.6 | `logcat -b kernel \| grep <svc>` |
| 改了属性重启没生效（ro.） | `ro.` 只读，改了被忽略 | 7.1 | `getprop ro.xxx` |
| OTA 后属性值异常 | 旧 persist 值残留 / 新 build.prop 覆盖逻辑变 | 7.2、6.3 | 对比 `getprop` 与期望值 |

---

## 十三、车机场景

### 13.1 开机自启服务与依赖排序

车机有大量"开机就要跑"的守护（CAN 总线服务、TBOX、音效、相机标定）。正确做法：

- 核心 HAL 类放 `core`/`main`（芯片厂已定）；
- **车机自有服务挂 `late_start`**，避免拖慢开机首屏；
- 强依赖（如"音效服务依赖音频 HAL 起完"）用 `on property:init.svc.audio_hal=running` 触发，而非硬等。

### 13.2 ACC/电源状态与 init 的关系

车机电源比手机复杂（ACC 信号、休眠/唤醒、低功耗）。这类状态常用**属性**表达，再由 rc 的 `on property:` 触发相应服务：

```rc
on property:vendor.power.acc=off
    setprop vendor.cool.enable 0     # ACC 断开 → 停某些服务省电
on property:vendor.power.acc=on
    setprop vendor.cool.enable 1
```

### 13.3 工厂模式与量产属性

工厂/量产常用一组 `persist.` 或 `vendor.` 属性标记设备状态（已校准、已老化、区域码）。它们必须持久且可写：

```bash
setprop persist.vendor.factory.calibrated 1   # 校准完成标记
getprop persist.vendor.factory.calibrated    # 出厂检测脚本读取
```

**坑**：量产脚本若在 DE 阶段之前（/data 未解密）就读 persist，会读不到——要确认服务启动时机（呼应存储篇 DE/CE 时序）。

### 13.4 OTA 后属性迁移

OTA 更新 `build.prop` 会刷新 `ro.`/系统属性，但 `persist.` 保留在 `/data`。如果新版本"期望某 persist 默认值变了"，需要**迁移逻辑**（首次启动脚本比对旧值）。`ro.` 写死导致"升级不生效"是经典坑：某行为靠 `ro.xxx` 控制且旧版已写死，OTA 只更新 system，ro 值沿用旧镜像残留——需确认新镜像的 build.prop 真正覆盖。

### 13.5 调试三板斧（车机）

1. **看服务在不在**（`ps -A`、`getprop init.svc.`）；
2. **看 SELinux 拦没拦**（`dmesg | grep avc`）；
3. **看属性对不对**（`getprop` 核对触发条件）。

### 13.6 车机 rc 实战片段：ACC / 休眠唤醒

下面是一段"接近真实"的车机 rc 片段，演示如何用属性表达电源状态并联动服务（注意 `late_start` 与 `on property:` 的配合）：

```rc
# 车机电源状态联动：ACC 断开进入低功耗，恢复后拉起服务
service car_power /system/bin/car_power_daemon
    class late_start
    user system
    group system
    seclabel u:r:car_power:s0
    capabilities SYS_NICE
    onrestart restart car_audio

# ACC 断开 → 停非必要服务、发通知
on property:vendor.power.acc=off
    setprop vendor.cool.enable 0
    setprop vendor.media.enable 0
    write /sys/class/thermal/thermal_zone0/mode disabled

# ACC 恢复 → 重新拉起
on property:vendor.power.acc=on
    setprop vendor.cool.enable 1
    setprop vendor.media.enable 1
    write /sys/class/thermal/thermal_zone0/mode enabled

# 休眠（suspend）前停服务，唤醒后再起（避免 suspend 期间持锁）
on property:sys.power_suspend=1
    stop car_media
on property:sys.power_suspend=0
    start car_media
```

**要点**：车机的"电源事件"几乎都用属性桥接，而不是直接在 rc 里写死命令——这样 Framework/HAL 也能通过 `setprop` 触发，解耦且可远程调试（`adb shell setprop vendor.power.acc off` 即可模拟断电）。

### 13.7 量产 / 工厂模式 rc 与属性

量产线常用"工厂属性 + 一次性脚本"做校准与检测：

```rc
# 工厂模式：进入时跑校准脚本（exec_start 同步等结束）
on property:vendor.factory.mode=1
    exec_start factory_calib
    setprop vendor.factory.calibrated 1

service factory_calib /system/bin/factory_calib.sh
    user root
    oneshot                      # 跑完即退，不常驻
    disabled                     # 不随 class 自动起，仅被 exec_start 调
    seclabel u:r:factory:s0
```

```bash
# 量产脚本侧（主机或 recovery 下）
adb shell setprop vendor.factory.mode 1     # 触发校准
adb shell getprop vendor.factory.calibrated # 等返回 1 表示完成
```

**两个坑**：
- `factory_calib` 用了 `root`，但只应是工厂/研发构建（`userdebug`/`eng`）才暴露，发售 `user` 版应移除该 service，否则是安全后门；
- 校准结果若用 `persist.vendor.factory.*` 记录，要确保读取发生在 `/data` 解密之后（DE 阶段之前读不到，呼应存储篇第 4 章）。

### 13.8 车机属性命名规范（交付清单）

为避免与 AOSP 未来属性冲突、也便于团队维护，车机自定义属性建议：

| 类别 | 命名前缀 | 例子 | 是否持久 |
|---|---|---|---|
| 车机通用配置 | `vendor.car.` | `vendor.car.boot_animation` | 否（默认） |
| 需跨重启保留 | `persist.vendor.car.` | `persist.vendor.car.region` | 是 |
| 电源/ACC | `vendor.power.` | `vendor.power.acc` | 否 |
| 工厂/量产 | `vendor.factory.` | `vendor.factory.calibrated` | 多用 persist |
| 调试开关 | `debug.vendor.car.` | `debug.vendor.car.loglevel` | 否 |

并在 `property_contexts` 里为 `vendor.car.*`、`persist.vendor.car.*` 等分配独立 SELinux 类型，避免所有自定义属性挤在 `default_prop` 下导致权限失控。

---

## 十四、读源码路线 + 源码路径速查表

### 14.1 读源码路线

1. 先读 `system/core/init/README.md`（官方 rc 语法权威说明）；
2. 跟 `init.cpp` 的 `main()`：看两阶段如何切（`FirstStageMain` / `SecondStageMain`）；
3. 读 `property_service.cpp`：属性如何存、如何响应 setprop（binder/socket）；
4. 读 `bionic/libc/bionic/system_properties.cpp` + `__system_property_area__`：理解直读共享内存；
5. selinux 部分：看 `system/sepolicy` 下 `property_contexts` 与 te 规则。

### 14.2 源码路径速查表

| 内容 | 路径 |
|---|---|
| init 主逻辑 | `system/core/init/init.cpp` |
| 第一阶段 | `system/core/init/first_stage_init.cpp`、`first_stage_mount.cpp` |
| 第二阶段 | `system/core/init/main.cpp`、`second_stage_init.cpp` |
| rc 解析 | `system/core/init/parser.cpp`、`init_parser.cpp` |
| service 管理 | `system/core/init/service.cpp`、`service_list.cpp` |
| 属性服务 | `system/core/init/property_service.cpp` |
| action/trigger | `system/core/init/action.cpp`、`builtins.cpp` |
| 属性客户端（bionic） | `bionic/libc/bionic/system_properties.cpp` |
| 属性区域定义 | `bionic/libc/include/sys/_system_properties.h` |
| SELinux 属性上下文 | `system/sepolicy/private/property_contexts`、`*/property.te` |
| fstab 挂载 | `system/core/fs_mgr/` |
| 内置命令（builtins） | `system/core/init/builtins.cpp` |

---

## 十五、一图总结

```
┌ init (PID 1) 三重身份 ──────────────────────────────────────────────┐
│ ① 挂载主管：fstab → fs_mgr → 挂 system/vendor(/data 解密)            │
│ ② 服务主管：解析 *.rc → 按 class 起服务 → 崩溃重启 → 事件循环        │
│ ③ 属性主管：property_service + 共享内存 → getprop/setprop/触发       │
└──────────────────────────────────────────────────────────────────────┘

rc 加载链：/init.rc(import) → /system/etc/init → /vendor/etc/init
          → /odm/etc/init → /product/etc/init（同名 service 报错，action 合并）

属性三个真相：
  · ro. 只读一次；persist. 落 /data/property 跨重启；ctl. 是动作不是值
  · 每个属性在 SELinux 下归属一个类型（property_contexts）→ setprop 受域规则约束
  · 读走 __system_property_area__ 共享内存（Android 9+ 不再 binder），写才找 init

联动核心：
  on property:xxx=y  → 触发 action
  init.svc.<name>    → 服务状态（running/stopped）
  class_start <cls>  → 批量起服务（core→main→late_start）

记忆链：
  init 三件事 = 挂分区 + 起服务 + 管属性
  加服务七步 = rc(init_rc) → bp(cc_binary) → te(sepolicy) → 编镜像 → 验ps → 排avc → 交付
  属性设不上先想：SELinux 拦了？还是 ro. 只读？还是写在 /data 解密前？

版本一句话：two-stage init(8) / 属性共享内存直读(9) / property_contexts 强制(各版强化)
车机三件事：late_start 挂自制服务、用属性表达 ACC/工厂状态、OTA 后 persist 迁移
```

*关联阅读（同库）：`androidFrameworks/01_Android启动流程详解`（init 之上的 Zygote/SystemServer 脉络，本篇只补 init 自身）、SELinux 系统篇（`property_contexts` 与 te 策略的完整写法）、同目录《Android 存储机制详解》（`/data` 解密时序决定 persist 属性何时可读）、OTA 篇（A/B 与属性迁移）。呼应构建篇《Android 构建系统详解》：本篇的 rc/te 正是用 `init_rc`/`sepolicy` 字段随 `Android.bp` 模块编进镜像的。*
