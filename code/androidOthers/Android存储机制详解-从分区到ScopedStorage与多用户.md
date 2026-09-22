# Android 存储机制详解——从分区到 Scoped Storage，再到多用户与车机

> 存储知识有两个天然维度：**空间**（从闪存芯片到应用目录，一层层往里看）和**时间**（权限模型年年变，老代码会在新系统上突然崩）。所以这篇分三段读：
>
> **第一段 原理**（空间从哪来、怎么被划分与加密）→ **第二段 开发日常**（我该把文件放哪、怎么读写、权限怎么写）→ **第三段 系统与厂商视角**（多用户、空间治理、车机定制）。
>
> 读完你能回答：`filesDir` 的文件到底在磁盘哪里、同一份代码为什么 Android 9 能跑 13 崩、换用户后数据还在不在、`adb push` 到 `/product` 为什么重启就没了、清缓存和清数据的区别、车机为什么不能无限制写录像。车机内容集中在第三段。

---

## 目录

**第一段：原理——空间从哪来**

1. [开场：存储问题只有五个问题](#一开场存储问题只有五个问题)
2. [五层视角与物理介质](#二五层视角与物理介质)
3. [分区全景：设备被切成了什么](#三分区全景设备被切成了什么)
4. [从开机到可用：挂载与加密](#四从开机到可用挂载与加密)

**第二段：开发日常——我该把文件放哪**

5. [三类存储位置（最实用的一张表）](#五三类存储位置最实用的一张表)
6. [内部存储的真实长相与清理语义](#六内部存储的真实长相与清理语义)
7. [外部存储的三个身份与路径映射](#七外部存储的三个身份与路径映射)
8. [权限演进史与按版本的写法](#八权限演进史与按版本的写法)
9. [跨边界访问：MediaStore、SAF、FileProvider](#九跨边界访问mediastoresaffileprovider)
10. [旧代码迁移清单（Scoped Storage 改造）](#十旧代码迁移清单scoped-storage-改造)
11. [存储与性能：为什么 IO 会让 App 卡](#十一存储与性能为什么-io-会让-app-卡)

**第三段：系统与厂商视角**

12. [多用户与存储隔离](#十二多用户与存储隔离)
13. [空间治理：配额、统计与清理](#十三空间治理配额统计与清理)
14. [媒体扫描、缩略图与不可见文件](#十四媒体扫描缩略图与不可见文件)
15. [备份、迁移与刷机各分区的命运](#十五备份迁移与刷机各分区的命运)
16. [车机场景：多分区、外设与寿命](#十六车机场景多分区外设与寿命)
17. [调试工具箱](#十七调试工具箱)
18. [常见问题排查表](#十八常见问题排查表)
19. [一图总结](#十九一图总结)

---

## 一、开场：存储问题只有五个问题

任何存储问题，拆开都是下面五个之一。**先判断属于哪个，再去对应章节**：

| 问题 | 本质 | 章节 |
|---|---|---|
| 文件**在哪**？ | 层级结构：分区 → 挂载点 → 逻辑视图 | 2~7 |
| **能不能**读写？ | 权限：Linux 权限位 + 运行时权限 + Scoped Storage | 8~10 |
| **重启/换用户**后还在吗？ | 分区是否持久 + 用户隔离 + 加密状态 | 3、4、12 |
| 还能**用多久**？ | 配额、剩余空间、写入寿命 | 13、16 |
| 换机/刷机还**在吗**？ | 备份机制 + 是否属于 userdata | 15 |

**一个贯穿全文的类比——设备像一栋大楼**：

```
楼（UFS/eMMC 闪存芯片）
 ├─ 各层楼 = 分区（系统区、用户数据区、厂商校准区）
 ├─ 楼层里的房间 = 目录（/data/data/<包名>、/sdcard/DCIM…）
 ├─ 门禁卡 = 权限（uid/gid + 运行时权限 + FUSE 过滤）
 └─ 保险柜 = 加密（开机输密码，就是为了开这个柜）
```

关键认知：**Android 不是"一个磁盘 + 一堆文件夹"，而是"多个分区 + 每区明确归属 + 用户数据被加密与隔离"**。理解这点，很多"奇怪现象"都自洽了。

---

## 二、五层视角与物理介质

### 2.1 同一份文件，五个"名字"

| 层 | 关注点 | 例子 |
|---|---|---|
| ① 物理层 | 闪存芯片与块设备 | UFS 芯片、`/dev/block/by-name/userdata` |
| ② 分区层 | 逻辑切分 | `system`、`super`、`userdata` |
| ③ 文件系统层 | 怎么组织文件 | ext4 / f2fs / EROFS |
| ④ 挂载层 | 分区挂到哪个路径 | `userdata` → `/data`；`/data/media/0` → `/storage/emulated/0` |
| ⑤ 逻辑视图层 | 给应用看的"世界" | `filesDir`、`/sdcard/Download`、MediaStore 条目 |

**一个文件的完整身份链**：

```
Java:      context.filesDir                    ← 应用视角（第 ⑤ 层）
路径:      /data/user/0/com.example/files      ← 逻辑路径
真实:      /data/data/com.example/files        ← 挂载后的真实路径
底层:      /data 分区（userdata）→ f2fs → /dev/block/by-name/userdata   ← ① ② ③ 层
```

**排查时先确定自己站在哪一层**：`ls /sdcard` 看不到文件，可能是第 ⑤ 层（FUSE 权限视图）的问题，而不是第 ③ 层（文件真不存在）。

### 2.2 闪存：eMMC 与 UFS

| | eMMC | UFS |
|---|---|---|
| 类比 | 单车道公路（半双工） | 多车道高速（全双工、可并行） |
| 速度 | 150~400 MB/s | 1~4 GB/s |
| 定位 | 中低端/部分车机 | 旗舰手机、主流车机 |

**影响设计的三个特性**：擦写次数有限（UFS 约几千次 P/E cycle）、**必须先擦后写**（随机小写比顺序写慢得多，还放大写放大）、有坏块管理与磨损均衡。**车机天天写日志、循环录像，是闪存寿命的主要杀手**（第 16 章有对策）。

### 2.3 三种文件系统，各有分工

| 文件系统 | 特性 | 用在哪 |
|---|---|---|
| **ext4** | 老牌通用、稳、支持日志 | 早期 userdata、部分分区 |
| **f2fs** | 为闪存优化、随机写更快 | **现代设备 userdata（/data）默认** |
| **EROFS** | 只读、高压缩比、随机读快 | **系统分区**（Android 13 起主流） |

**为什么系统分区用只读文件系统**：系统内容出厂后不该被改（安全 + 防篡改），配合 dm-verity 校验——这也解释了为什么 **`adb push` 到 `/system`、`/product` 会失败或重启后失效**。

**还有一类"内存盘"（tmpfs）**：`/dev`、`/tmp`、`/mnt/ramdisk` 等根本不在闪存上，**重启即清空**。往这些目录写持久化数据必然丢。

---

## 三、分区全景：设备被切成了什么

### 3.1 分区表（以现代 A/B 设备为例）

| 分区 | 挂载点（典型） | 内容 | 可写 | 谁写 |
|---|---|---|---|---|
| `boot` / `init_boot` / `vendor_boot` | 不挂载（被 bootloader 加载） | 内核 + ramdisk | 只读 | 刷机 |
| `dtbo` | 不挂载 | 设备树叠加 | 只读 | 刷机 |
| `vbmeta` | 不挂载 | AVB 校验元数据 | 只读 | 刷机 |
| `super` | 不直接挂载 | **动态分区容器**（见 3.2） | 只读 | 刷机 |
| `system` | `/system` | 系统框架、核心 App | 只读 | 刷机 |
| `system_ext` | `/system_ext` | 系统扩展 | 只读 | 刷机 |
| `product` | `/product` | 厂商预装 App、配置 | 只读 | 刷机 |
| `vendor` | `/vendor` | HAL、驱动、vendor 库 | 只读 | 刷机 |
| `odm` | `/odm` | ODM 定制（车机厂商常用） | 只读 | 刷机 |
| **`userdata`** | **`/data`** | **所有用户数据、App 数据、内部存储** | **可写** | 运行时 |
| `metadata` | `/metadata` | 加密元数据 | 可写（受保护） | 系统 |
| `persist` | `/mnt/vendor/persist` | 校准数据（传感器、Wi-Fi MAC、BT 地址） | 可写 | 厂商/系统 |
| `misc` | 不挂载 | bootloader 控制块 | 可写 | bootloader |
| `frp` | 不挂载 | 恢复出厂保护状态 | 可写 | 系统 |
| `cache` | （已基本废弃） | OTA 临时缓存 | 可写 | 系统 |
| `modem`/`radio`、`bluetooth`、`tz`、`xbl` | 不挂载 | 各芯片固件 | 只读 | 刷机 |

**要背下来的一条分界线**：**只有 `userdata`（/data）是"用户数据的地盘"**——恢复出厂就是格式化它；其余分区要么只读、要么是厂商/系统的专属数据区。这解释了两类常见困惑："我的数据为什么恢复出厂就没了""为什么改系统文件会被 verity 拦下"。

### 3.2 super 动态分区（Android 10+）

传统做法给 system/vendor/product 各切固定大小，改不了；**动态分区把这些做成一个叫 `super` 的大容器，里面各逻辑分区的大小在刷机时按需分配**：

```
super 分区
 ├─ 逻辑分区：system      （大小写在 super 元数据里，不是 GPT）
 ├─ 逻辑分区：vendor
 ├─ 逻辑分区：product / system_ext / odm
```

好处：OTA 时能重切空间、A/B 更省空间、产品线共用分区表。代价：`fastboot flash system` 这类命令行为变了，要用 `fastboot flash super` 或进 `fastbootd`。查法：`adb shell lpdump`（需权限）。

### 3.3 A/B 双槽位与虚拟 A/B

系统分区做**两份**（slot A / slot B），OTA 时后台写空闲槽，重启切过去——**升级失败不会变砖**，回滚就是切回另一槽。

- 代价：分区占用翻倍（所以动态分区 + 虚拟 A/B 一起出现，用 COW 快照省空间）；
- `/data` 只有一份（用户数据不双份），所以"系统升级后 App 数据还在"；
- **手工刷机常见坑**：改了系统文件没生效，其实是写进了另一个槽（`fastboot getvar current-slot` 确认）。

### 3.4 dm-verity 与 AVB：为什么系统区防改

- **dm-verity**：对只读分区做块级哈希树校验，任何一块被改，读到时立刻报错；
- **AVB（Verified Boot 2.0）**：用 `vbmeta` 里的签名链验证各分区，bootloader 决定是否允许启动。

开发时的标准操作：

```bash
adb root && adb disable-verity      # 关闭 verity（会写 vbmeta，需重启）
adb reboot
adb root && adb remount             # 现在 /system、/product 可写
# 改完文件后 restorecon -R 修 SELinux 上下文，再重启
```

**注意**：正式改系统内容要靠 **build 进镜像**（`PRODUCT_COPY_FILES` / `PRODUCT_PACKAGES`），别依赖 remount——它会在 OTA/重刷后丢失，也不会被 verity 认可。

### 3.5 想加一个自定义分区（车机常见需求）

```
① 分区表：在分区表配置里加一项（如 oem），分配大小（GPT 或 super 逻辑分区）
② fstab：为它写一行挂载规则（设备节点 + 挂载点 + 文件系统 + 选项）
③ 文件系统与镜像：确定用 ext4/EROFS，构建时生成镜像并写入
④ SELinux 上下文：为新挂载点定义 file_contexts，否则会 mount 失败或访问被拒
⑤ 权限与所有权：确定挂在 /oem 之类路径下的 uid/gid 与访问策略
⑥ 应用可见性：应用侧如何拿到路径（env 变量/自定义 API/或直接约定绝对路径）
```

**最容易漏的是 ④**——SELinux 上下文缺失的表现往往是"挂载成功但任何进程都读不到"。

---

## 四、从开机到可用：挂载与加密

### 4.1 开机挂载链路

```
① bootloader 加载 boot 分区 → 起 Linux 内核
② 内核挂 ramdisk 作为根文件系统，启动 init（PID 1）
③ init 读 /fstab.<硬件>（描述"哪个分区挂到哪、什么文件系统、什么选项"）
④ fs_mgr 按 fstab 挂载：只读分区（system/vendor/product…）+ 需要加密的 /data
     └─ 注意：/data 此时还挂不上，要先解密（见 4.3）
⑤ vold（Volume Daemon，存储总管家）：管理卷、加密、外部存储挂载
⑥ 启动原生服务与 system_server
⑦ 用户解锁后，/data 用 CE 密钥解密；MediaProvider(FUSE) 提供 /storage/emulated 视图
⑧ 插 USB/SD 卡时，vold 探测、挂载、通知 framework
```

**关键点**：`/data` 在开机早期**不可用**（未解密）——这就是"开机自启服务读不到自己数据"的原因（见 4.4）。

### 4.2 fstab：挂载规则的唯一真相

```
# <块设备>                   <挂载点>  <文件系统> <选项>
/dev/block/by-name/system    /system   ext4   ro,barrier=1,avb
/dev/block/by-name/userdata  /data     f2fs   rw,nosuid,nodev,noatime,inlinecrypt,...
```

**看懂 fstab 基本就摸清了这台设备的存储结构**，车机加自定义分区也是改这里。运行时看实际结果：

```bash
adb shell cat /proc/mounts
adb shell mount | grep -E "data|emulated"
```

### 4.3 加密：FDE → FBE，以及 DE / CE 两类存储

| 方案 | 年代 | 特点 |
|---|---|---|
| **FDE**（全盘加密） | Android 5~9 | 整个 /data 一把密钥，解锁后所有人可读 |
| **FBE**（文件级加密） | Android 7 引入，**Android 10 起新设备强制** | 每个文件独立密钥，且分两类存储 |

FBE 把应用数据分成两份物理目录：

| 类型 | 目录 | 何时可访问 | 该放什么 |
|---|---|---|---|
| **DE**（Device Encrypted） | `/data/user_de/0/<pkg>` | **开机后即可** | 闹钟、铃声、开机广播接收、锁屏信息 |
| **CE**（Credential Encrypted） | `/data/user/0/<pkg>`（= `/data/data/<pkg>`） | **用户解锁后** | 绝大多数业务数据（默认都在这） |

**密钥链**：用户密码/图案 → 派生用户密钥 → 解锁 CE 存储 → 文件级密钥（每文件一把）→ 硬件根密钥（Keymaster/KeyMint 在 TEE 中保护，永不出安全芯片）。**推论**：改锁屏密码 = 用户密钥重新派生（这就是"换密码要重新加密"的机制背景）。

### 4.4 Direct Boot：存储的"可用性时序"

```
开机 → DE 可用（ACTION_LOCKED_BOOT_COMPLETED）→ 用户解锁 → CE 可用（ACTION_BOOT_COMPLETED）
```

「**Android 视角**」`context.createDeviceProtectedStorageContext()` 拿 DE 上下文。**闹钟类 App 必须把闹钟数据存 DE**，否则关机闹钟不响。

### 4.5 FUSE：`/storage/emulated` 是一层"权限视图"

`/storage/emulated/0`（也就是 `/sdcard`）**不是内核挂载的真实文件系统，而是 MediaProvider 用 FUSE（Android 11+）提供的按权限过滤的视图**：

```
真实数据：/data/media/0/...（userdata 里的普通目录，只有系统能直接访问）
        ↓ MediaProvider(FUSE) 按调用者权限投影
应用视角：/storage/emulated/0/...（只看得见你有权限的部分）
```

这解释了三个高频现象：

1. `/sdcard` 里的文件本质在 `/data/media/`，**占的是 userdata 空间**（"内部共享存储"其实是 /data 的一部分，没有独立分区）；
2. Scoped Storage 的"只看自己的照片"是 FUSE 层过滤的结果，不是 Linux 权限位；
3. `ls /data/media/0` 与 `ls /sdcard` 结果可能不同——前者是真相，后者是视图。

（Android 10 及以前用 `sdcardfs` + 挂载命名空间实现类似效果，11 起改 FUSE。排查时先确认系统版本。）

---

## 五、三类存储位置（最实用的一张表）

**开发时 90% 的路径选择都在这里**：

| 类别 | 路径（用户 0） | API | 需要权限 | 卸载后 | 其他 App 可见 |
|---|---|---|---|---|---|
| **内部私有**（最安全） | `/data/user/0/<pkg>/` | `getFilesDir()`、`getCacheDir()`、`getNoBackupFilesDir()` | 无 | 删除 | 看不到 |
| **外部私有**（大文件推荐） | `/storage/emulated/0/Android/data/<pkg>/files/`（或 `/cache/`） | `getExternalFilesDir(Environment.DIRECTORY_MOVIES)`、`getExternalCacheDir()` | **无**（4.4+） | 删除 | 拿不到路径（FUSE 过滤） |
| **外部媒体私有**（11+） | `/storage/emulated/0/Android/media/<pkg>/` | MediaStore 或受限的文件路径 | 无 | 删除 | 可被 MediaStore 索引 |
| **OBB**（游戏扩展资源） | `/storage/emulated/0/Android/obb/<pkg>/` | — | 无 | 删除 | 不可见 |
| **共享媒体库** | `/storage/emulated/0/DCIM`、`Pictures`、`Movies`、`Music`、`Download`、`Documents` | **MediaStore**、SAF | 按类型（第 8 章） | **保留** | 全局可见 |

**选型三条铁律**：

1. **越小越敏感 → 越靠上**（私有内部存储）；
2. **文件大 / 要给用户看见 / 要跨应用共享 → 往下走**（外部私有或 MediaStore）；
3. **绝不硬编码 `/sdcard/...`**，用 API 取路径——外部存储可能不存在、可能有多卷、多用户路径不同。

### 5.1 一个真实的决策例子

需求：下载一个 300MB 的离线地图包。

```kotlin
// ✗ 错误：写内部存储，用户清缓存就丢、且吃 /data 空间
File(context.filesDir, "map.pack")

// ✓ 正确：外部私有目录（无需权限、卸载清理、用户可在文件管理器看到）
val dir = context.getExternalFilesDir("maps")
// 外部存储可能未挂载，必须判空
if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) { /* 降级 */ }
File(dir, "map.pack")
```

**为什么不用 `/sdcard/maps`**：需要权限、卸载后残留垃圾、Android 11+ 会被 Scoped Storage 拦。

---

## 六、内部存储的真实长相与清理语义

```bash
adb shell run-as com.example.app ls -l /data/data/com.example.app/
# files/          getFilesDir()          业务文件（参与备份）
# cache/          getCacheDir()          可被系统随时清（备份排除）
# code_cache/     系统/JIT 用
# databases/      Room/SQLite 的 .db
# shared_prefs/   SharedPreferences 的 XML
# no_backup/      getNoBackupFilesDir()  不参与自动备份
# app_<子目录>     getDir("name") 的产物
# lib/            原生库（安装时解压）
```

**清理语义要分清**（用户和测试最常问）：

| 操作 | 效果 |
|---|---|
| **清缓存**（Clear cache） | 只删 `cache/` 与 `getExternalCacheDir()`，`files/` 与数据库保留 |
| **清数据**（Clear storage） | 删除整个 `/data/data/<pkg>` + 外部私有目录，等同"重装" |
| **卸载** | 私有数据被删；**共享媒体库里的文件保留**（这就是"卸载后照片还在"） |
| `pm clear <pkg>` | 等同"清数据" |

---

## 七、外部存储的三个身份与路径映射

这是最容易讲错的地方，用一张图钉死：

```
应用/用户看到的：/sdcard/Download/a.pdf
                  ↓ 符号链接链（路径变换，不是挂载）
/storage/self/primary → /mnt/user/0/primary → /storage/emulated/0
                  ↓ FUSE 投影（MediaProvider 按权限过滤）
内核真相：/data/media/0/Download/a.pdf      ← 实体在 userdata 分区里
```

| 应用看到的 | 用户 0 的真实路径 | 用户 10 的真实路径 |
|---|---|---|
| `/data/data/<pkg>` | `/data/user/0/<pkg>` | `/data/user/10/<pkg>` |
| `/sdcard`（`/storage/emulated/0`） | `/data/media/0` | `/data/media/10` |
| `getExternalFilesDir()` | `/storage/emulated/0/Android/data/<pkg>/files` | `/storage/emulated/10/Android/data/<pkg>/files` |

**结论**：

- **别假设 `/data/user/0` 是实体**（Android 11 起 `/data/user/0` 是 `/data/data` 的符号链接，以前反过来）；
- **多用户各有独立的"SD 卡"**（隔离由 FUSE 的 userId 视图实现，见第 12 章）；
- **对外部存储的所有读写都可能失败**（未挂载、已满、被移除），代码必须有降级路径。

「**Android 视角**」`StorageManager.getStorageVolumes()` + `StorageVolumeCallback` 可以监听外部卷的插入/移除/状态变化，这是"U 盘音乐播放器"类功能的基础。

---

## 八、权限演进史与按版本的写法

这一章必须按时间线读，否则"为什么同一份代码在 A 版本好使、B 版本崩"永远想不通。

### 8.1 时间线

| 版本 | 变化 |
|---|---|
| **4.3 及以前** | `/sdcard` 是真实挂载点，权限就是一个 Linux 组 `sdcard_rw`——**任何 App 拿了权限就能读写全部文件**（混乱的总根源） |
| **4.4 (API 19)** | 引入 `getExternalFilesDir()`：外部存储上给每个 App 私有目录，**访问它不需要任何权限** |
| 6.0 (API 23) | **运行时权限**：`READ/WRITE_EXTERNAL_STORAGE` 需运行时申请 |
| 7.0 (API 24) | **FileProvider 强制**：`file://` 跨应用分享抛 `FileUriExposedException`，必须用 `content://` |
| 8.0 (API 26) | 引入**每应用存储配额**（第 13 章） |
| **10 (API 29)** | **Scoped Storage 引入**；可用 `requestLegacyExternalStorage="true"` 临时退出 |
| **11 (API 30)** | **强制 Scoped Storage**（`requestLegacyExternalStorage` 被忽略）；`WRITE_EXTERNAL_STORAGE` 不再有作用；新增 `MANAGE_EXTERNAL_STORAGE`（"所有文件访问"，政策敏感）；不能通过路径或 SAF 访问其他 App 的 `Android/data` |
| **13 (API 33)** | 媒体权限拆细：`READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` / `READ_MEDIA_AUDIO` |
| **14 (API 34)** | 新增 `READ_MEDIA_VISUAL_USER_SELECTED`（部分照片授权），App 必须处理"访问不完整" |

**Scoped Storage 的核心规则**：

1. App 自由读写**自己的私有目录**（内部 + 外部私有），无需权限；
2. 访问**其他 App 的媒体文件**要通过 MediaStore；
3. 访问**自己创建的媒体文件**（通过 MediaStore 插入的）可继续用文件路径直接改；
4. **不能**通过文件路径访问其他 App 的 `Android/data`；
5. 访问 `/sdcard` 上**非媒体、非自己的文件**（任意 PDF、自定义后缀）→ 必须走 SAF 或 `MANAGE_EXTERNAL_STORAGE`。

### 8.2 按版本写权限（直接抄）

```xml
<!-- 老版本：一把梭，最高到 API 32 -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />
<!-- Android 13+：按类型细粒度 -->
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
<!-- Android 14+：部分照片授权 -->
<uses-permission android:name="android.permission.READ_MEDIA_VISUAL_USER_SELECTED" />
<!-- 全盘访问：政策敏感，仅文件管理器/备份类可用 -->
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE"
    tools:ignore="ScopedStorage" />
```

### 8.3 权限对照总表（贴墙上）

| 想做的事 | API ≤ 32 | API 33+ | 备注 |
|---|---|---|---|
| 读写自己私有目录（含外部私有） | 无权限 | 无权限 | 永远不需要权限 |
| 读用户图片/视频 | `READ_EXTERNAL_STORAGE` | `READ_MEDIA_IMAGES/VIDEO` | 34+ 可能是"部分授权" |
| 读音频 | `READ_EXTERNAL_STORAGE` | `READ_MEDIA_AUDIO` | — |
| 全盘任意文件 | `MANAGE_EXTERNAL_STORAGE` | 同左 | 政策敏感 |
| 自己创建的媒体文件路径读写 | 允许 | 允许 | 无需权限 |
| 用户选定的任意文件 | SAF / Photo Picker | 同左 | **推荐，无需危险权限** |

**最佳实践：能不用权限就不用**——用系统**照片选择器**（`ACTION_PICK_IMAGES`，Android 13+，Photo Picker 向后兼容到 11）让用户挑文件，**零权限**拿到结果 URI。

「**Android 视角**」`WRITE_EXTERNAL_STORAGE` 基本是历史包袱：targetSdk 30+ 时申请它没有任何效果，很多"权限申请了还是不能写"的 case 就是这个原因。

---

## 九、跨边界访问：MediaStore、SAF、FileProvider

三条路各管一类需求，混用会白折腾。

### 9.1 MediaStore：访问"媒体库"，不是文件系统

MediaStore 是 MediaProvider 维护的**媒体数据库**，把 `/data/media/0` 下的文件按类型索引成"表"，可以像查数据库一样查询。

```kotlin
// 查询最近 100 张图片（只需 READ_MEDIA_IMAGES）
val cursor = contentResolver.query(
    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
    arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
    null, null, "${MediaStore.Images.Media.DATE_TAKEN} DESC LIMIT 100")
val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
contentResolver.openInputStream(uri)?.use { /* 读图 */ }
```

**往共享媒体库写文件**（Android 10+ 标准姿势；11+ 用 `IS_PENDING` 避免半成品被索引）：

```kotlin
val values = ContentValues().apply {
    put(MediaStore.Images.Media.DISPLAY_NAME, "photo_001.jpg")
    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MyApp")
    put(MediaStore.Images.Media.IS_PENDING, 1)          // 写入中不对外可见
}
val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
contentResolver.openOutputStream(uri)?.use { /* 写数据 */ }
values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
contentResolver.update(uri, values, null, null)          // 解锁，相册可见
```

**适用与不适**：✅ 相册浏览、扫描媒体、保存照片、读音乐列表；❌ 不适合读任意文档（PDF/zip）——那不是媒体，查不到；也不提供目录树语义。

### 9.2 SAF：让用户当"授权人"

通过**系统文件选择器**让用户指定文件或目录，App 拿到**授权 URI**，权限来自用户选择而非 App 申请。

```kotlin
// ① 让用户选一个文档
startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT)
    .addCategory(Intent.CATEGORY_OPENABLE)
    .setType("application/pdf"), REQ)
// ② 让用户选一个目录（可持久访问）——备份/网盘类靠它
startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE)
// ③ 拿到 URI 后必须申请"持久化"，否则进程重启即失效
contentResolver.takePersistableUriPermission(uri,
    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
// 之后可用 DocumentFile.fromTreeUri(context, uri) 像文件一样遍历
```

**两个关键点**：`ACTION_OPEN_DOCUMENT` 读、`ACTION_CREATE_DOCUMENT` 让用户在共享区创建、`ACTION_OPEN_DOCUMENT_TREE` 授权整个目录；**忘记 `takePersistableUriPermission` = 重启后 URI 失效**（SAF 第一大坑）。

### 9.3 FileProvider：把"我的文件"借给别人

App 私有文件不能把 `file://` 路径给别人（7.0 起抛异常，对方也没权限读）。FileProvider 把私有目录映射成 `content://` 路径，并临时授权接收方。

```xml
<provider android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false" android:grantUriPermissions="true">
    <meta-data android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

```xml
<!-- res/xml/file_paths.xml -->
<paths>
    <files-path name="internal" path="." />
    <cache-path name="cache" path="." />
    <external-files-path name="ext" path="." />
</paths>
```

```kotlin
val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
intent.putExtra(Intent.EXTRA_STREAM, uri)
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)   // 必须加
startActivity(Intent.createChooser(intent, "分享"))
```

### 9.4 选型决策表

| 需求 | 选谁 | 需要权限吗 |
|---|---|---|
| 把 App 里的照片/日志分享给微信、邮件 | **FileProvider** | 不需要 |
| 浏览/读取用户相册与音乐 | **MediaStore** | 需要（13+ 按类型） |
| 用户挑一张照片让我处理 | **Photo Picker**（`ACTION_PICK_IMAGES`） | **不需要** |
| 用户挑任意文档（PDF/Excel/zip） | **SAF**（`ACTION_OPEN_DOCUMENT`） | 不需要 |
| 让用户指定一个备份目录 | **SAF**（`ACTION_OPEN_DOCUMENT_TREE`） | 不需要 |
| 全盘扫描文件（文件管理器/清理工具） | `MANAGE_EXTERNAL_STORAGE` | 需要，政策敏感 |

---

## 十、旧代码迁移清单（Scoped Storage 改造）

如果你手上是老的 targetSdk ≤ 28 的代码，升到 30+ 会集中报错。按下面顺序改：

| 老写法 | 新写法 | 备注 |
|---|---|---|
| `Environment.getExternalStorageDirectory()` 直接建目录写文件 | `getExternalFilesDir()`（私有）或 MediaStore `insert`（共享） | **最高频**
| 遍历 `/sdcard` 找图片 | `MediaStore.Images` 查询 | 快得多，且符合规则 |
| 直接 `File("/sdcard/DCIM/xx.jpg").writeBytes(...)` | MediaStore 插入 + `openOutputStream` + `IS_PENDING` | —
| 分享时用 `Uri.fromFile(file)` | `FileProvider.getUriForFile(...)` + `FLAG_GRANT_READ_URI_PERMISSION` | 7.0 起必须
| 用 `WRITE_EXTERNAL_STORAGE` 保证可写 | 删掉：30+ 无作用 | 权限声明要按版本切割
| 读所有图片靠 `READ_EXTERNAL_STORAGE` | 13+ 改 `READ_MEDIA_IMAGES`，或直接用 Photo Picker | 更省事
| 通过路径读别的 App 的 `Android/data` | 不可行，改走对方提供的 ContentProvider/SAF | 11+ 彻底封死
| 备份到用户指定的外部目录 | SAF `ACTION_OPEN_DOCUMENT_TREE` + `takePersistableUriPermission` | 记得持久化授权
| 用 `MediaStore.MediaColumns.DATA` 路径读写 | 改为通过 `_ID` 拼 content URI | `DATA` 已不可靠

**验证方式**：改完在 API 30 / 33 / 34 三个版本真机上各跑一遍主要路径（写媒体、分享、下载），再检查 `adb shell dumpsys package <pkg> | grep -i permission` 的授权结果。

---

## 十一、存储与性能：为什么 IO 会让 App 卡

存储不只是"能不能写"，还是**性能问题的主要来源之一**。三条硬知识：

### 11.1 fsync：一次落盘就是一个"卡顿点"

`write()` 只是把数据交给内核缓存，**真正落盘要等 `fsync`**（或系统周期性回写）。闪存上一次 fsync 可能几毫秒到几十毫秒——**如果在主线程做，就是肉眼可见的掉帧**。

```kotlin
// ✗ 主线程写文件/fsync → 掉帧
File(context.filesDir, "log.txt").appendText(data)

// ✓ 挪到 IO 线程（协程的 IO 调度器）
withContext(Dispatchers.IO) { File(context.filesDir, "log.txt").appendText(data) }
```

**StrictMode** 能帮你抓这类问题（`detectDiskReads()` / `detectDiskWrites()`），调试期务必开。

### 11.2 SQLite：事务与 WAL 的取舍

- **不包事务逐条 insert** = 每条一次 fsync，慢上百倍。**批量写必须包事务**；
- 默认 `journal_mode` 是 WAL（写前日志）时，读不阻塞写、写不阻塞读，适合移动端；
- `db.setTransactionSuccessful()` 前的所有写入在失败时会回滚——别在事务里做网络请求。

### 11.3 随机写 vs 顺序写：闪存的真实脾气

```
顺序大块写（一次几 MB）  → 快、对闪存友好
海量随机小写（每次几十字节）→ 慢（写放大）、加速磨损
```

**实践建议**：日志与统计类写入做**批量合并 + 定时落盘**（攒 100 条写一次），既省电又延长闪存寿命——车机的循环录像与日志策略同理（见第 16 章）。

### 11.4 空间不足也是性能问题

空间接近满时，文件系统需要频繁整理空闲块，**写入延迟显著上升**；同时系统会开始清理各 App 缓存（第 13 章），带来额外 IO。所以**维护合理的水位**（不要把 /data 写满到 95%+）本身就是性能优化。

---

## 十二、多用户与存储隔离

### 12.1 Android 的"用户"不是登录账号，而是数据隔离单元

| userId | 是谁 |
|---|---|
| 0 | 机主（Owner），**所有设备都有** |
| 10, 11, … | 普通用户 / 工作资料 |
| 通常最大 id | 访客（Guest，可随时重置） |

**车机把这个功能用得很重**：车主账号、成员账号、访客模式、后排娱乐独立用户……每个用户有**独立的 App 数据 + 独立的"外部存储"**。

### 12.2 目录布局

```
/data/
 ├─ user/0/、user/10/             ← 各用户的应用私有数据（CE）
 ├─ user_de/0/、user_de/10/       ← 各用户的 DE 数据
 ├─ media/0/、media/10/           ← 各用户的"外部存储"实体
 ├─ system/users/0.xml            ← 用户列表与配置
 └─ misc/、app/、dalvik-cache/、tombstones/ …
```

映射表见第 7 章。**三条结论**：

1. **每个用户的"SD 卡"独立**（用户 10 看不到用户 0 的照片）；
2. **同一 App 装在多个用户下 = 完全独立的数据**（所以同一个 App 在两个用户下能登录不同账号）；
3. **切用户 = 原用户的 CE 密钥被丢掉**（数据仍在磁盘但读不了）。

### 12.3 车机常见坑：初始化跑了多次

预装 App 的"首次运行初始化"如果写在 `Application.onCreate` 里，会**在每个用户下各跑一遍**（每个用户一份数据目录）。**初始化逻辑要分清 per-user 还是 per-device**：

- per-user：用户偏好、账号信息 → 放自己的私有目录；
- per-device：设备序列号、标定数据、共享媒体索引 → 放系统区或 `persist`（第 3 章）。

```bash
adb shell pm list users                 # 列出所有用户
adb shell pm list packages --user 0     # 指定用户查包
adb shell am get-current-user           # 当前前台用户
```

---

## 十三、空间治理：配额、统计与清理

### 13.1 每应用配额（Android 8+）

`/data` 上启用了**项目配额（project quota）**，每个 App 有独立空间上限：写超配额 → `ENOSPC`（`IOException: No space left on device`）**即使整机还有空间**。

「**Android 视角**」精确统计用 `StorageStatsManager`：

```kotlin
val ssm = context.getSystemService(StorageStatsManager::class.java)
val stats = ssm.queryStatsForUid(StorageManager.UUID_DEFAULT, Process.myUid())
Log.d("storage", "数据=${stats.dataBytes} 缓存=${stats.cacheBytes} 代码=${stats.appBytes}")
```

### 13.2 "要空间"的正确请求方式：allocateBytes

系统不是"剩多少给多少"，而是**你可以申请一块空间，系统会为你去清缓存**：

```kotlin
val sm = context.getSystemService(StorageManager::class.java)
val uuid = StorageManager.UUID_DEFAULT
val allocatable = sm.getAllocatableBytes(uuid)   // 含清缓存的潜力
if (needBytes <= allocatable) {
    sm.allocateBytes(uuid, needBytes)            // 触发系统清理并预留
}
```

**这是下载大文件/升级包前的正确姿势**——直接写文件在紧张时会失败。

### 13.3 系统端的空间管理

- **低存储阈值**：低于阈值发 `ACTION_DEVICE_STORAGE_LOW` 并主动清缓存；
- **清理优先级**：优先删"最久未用 App 的 cache"，其次 `code_cache`；
- **`storaged` 服务**：AOSP 里做存储统计与寿命监控的原生服务（`dumpsys storaged`），**车机常裁剪以省资源**。

```bash
adb shell df -h /data                       # 分区总览
adb shell du -sh /data/data/com.example.app # 某 App 占用（需权限）
adb shell dumpsys diskstats                 # 系统级统计（含 App 大小与配额）
adb shell sm list-volumes all               # 卷列表（physical/emulated/public）
```

---

## 十四、媒体扫描、缩略图与不可见文件

这一章解释一堆"文件明明在，为什么相册/媒体库里没有"的问题。

### 14.1 媒体库不是"目录"，是扫描结果

MediaStore 里的条目由 **MediaScanner** 扫描产生。所以：

- **用 `File` 直接往 `/sdcard/DCIM` 写一张图**（在允许的场景下）后，媒体库可能还没有它——需要**通知扫描**：

```kotlin
// 老方式（API 29 起对应用私有文件受限）
MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, uri -> /* done */ }
```

- **更好的方式**：直接用 MediaStore `insert` 写媒体（9.1），插入即入库，无需扫描。

### 14.2 `.nomedia`：让目录对媒体库隐身

在目录里放一个空的 `.nomedia` 文件，**扫描器会跳过该目录及子目录**——常用于缓存图片、游戏资源、私有素材，避免污染用户相册。

**踩坑**：`.nomedia` 只在扫描时生效；已经入库的条目不会自动消失（需要显式删除或触发重扫）。

### 14.3 缩略图与派生文件

系统会为媒体生成缩略图并缓存（占空间但不属于你的 App）。所以**"媒体库删了照片，空间没全回来"**，往往还有缩略图/回收站等派生数据。Android 11+ 有"回收站"机制（删除的媒体先保留约 30 天），需要用户主动清空。

### 14.4 为什么"文件管理器看不到 Android/data"

Android 11 起，该目录对第三方应用（含 SAF、MTP）访问被限制。**这不是 bug，是设计**——保护其他 App 的私有外部数据。需要时用：

- `run-as`（调试自己的 App）；
- root/`adb shell`（设备侧直读 `/data/media/0/Android/data`）；
- 或在 App 内用 `getExternalFilesDir()` 自己那条路径。

---

## 十五、备份、迁移与刷机各分区的命运

### 15.1 Auto Backup（云备份）

Android 6.0 起 App 数据可自动备份（默认**每 App 上限 25MB**）：

- **默认包含**：`filesDir`、`databases`、`shared_prefs`、部分外部私有目录；
- **默认排除**：`cache/`、`code_cache/`、`no_backup/`；
- **控制方式**：Android 12+ 用 `android:dataExtractionRules`；12 以下用 `android:fullBackupContent`；`android:allowBackup="false"` 完全关闭。

**踩坑**：`allowBackup="true"` 意味着数据可被导出，**敏感数据应放 `no_backup/` 或加密**；车机常需关闭 Auto Backup（无云环境/合规要求）。

### 15.2 刷机/恢复出厂时各分区的命运

| 操作 | `/data`（userdata） | `/data/media`（照片等） | 系统分区 | `persist`/校准 |
|---|---|---|---|---|
| 清 App 数据 | 该 App 目录被删 | 保留 | — | — |
| 卸载 App | 该 App 目录被删 | **共享区文件保留** | — | — |
| 恢复出厂 | **全部清除** | **清除** | 保留/重写 | 通常保留 |
| 刷系统镜像（不清 data） | 保留 | 保留 | 覆盖 | 保留 |
| 换用户 | 该用户目录保留（不可读） | 同左 | — | — |

**这解释了车机售后的高频对话**："为什么恢复出厂后定位还是准的？"——标定数据在 `persist`，不在 `/data`。

### 15.3 高价值调试目录

| 路径 | 内容 | 用途 |
|---|---|---|
| `/data/local/tmp/` | adb shell 可写的临时区 | 推二进制工具（tcpdump、busybox）；**重启可能清空** |
| `/data/anr/` | ANR trace | 卡顿分析 |
| `/data/tombstones/` | native 崩溃堆栈 | native crash |
| `/data/system/dropbox/` | 系统级日志事件 | 系统异常 |
| `/data/misc/` | 子系统数据（wifi、bluetooth、keystore、vold…） | 绑定信息、密钥库 |
| `/data/vendor/` | vendor 私有数据 | 车机摄像头标定、TBOX 数据 |
| `/data/app/`、`/data/dalvik-cache/` | 已安装 APK、AOT 产物 | 包体/性能排查 |
| `/metadata/` | 加密元数据 | **谨慎操作**，误删可能导致解锁失败 |

---

## 十六、车机场景：多分区、外设与寿命

### 16.1 车机比手机多出来的存储面

| 场景 | 涉及能力 |
|---|---|
| **多分区定制** | `/oem`、`/vendor`、`/data/vendor`、`/mnt/vendor/persist`（加分区步骤见 3.5） |
| **外接存储** | USB 优盘 / SD 卡（`StorageVolume`、`sm list-volumes`）、U 盘音乐扫描与索引 |
| **大容量循环录像** | 行车记录仪/哨兵模式：分片覆盖 + 空间水位控制，**绝不能写满** |
| **多用户** | 车主/成员/访客各自数据与媒体区（第 12 章） |
| **存储寿命监控** | UFS 健康度、写放大（AOSP car-lib 有 `storagemonitoring` 相关能力，厂商多有自研） |
| **日志策略** | 限制 logd 缓冲、限制落盘、必要时挂内存盘 |
| **OTA** | A/B 槽位 + super 动态分区，升级需额外临时空间（要预留） |

### 16.2 外接存储的完整生命周期

```
插入 → kernel 探测 → vold 挂载到 /mnt/media_rw/<UUID> → 
        包一层 FUSE 给应用看 /storage/<UUID> → framework 通过 StorageVolume 上报 → 
        应用收到 StorageVolumeCallback（插入）
拔除 → 应用收到移除回调 → 卷卸载（**正在写的文件会失败，必须处理**）
```

**"插了 U 盘但 App 看不到"四个检查点**：卷是否被上报（`sm list-volumes`）→ App 是否注册了 `StorageVolumeCallback` → 是否有访问权限（`MANAGE_EXTERNAL_STORAGE` 或 SAF 授权）→ 是否查错路径（多卷时 UUID 不同）。

### 16.3 日志与录像的"防写爆"五条

```
① 限额：日志目录做大小上限 + 滚动删除；录像按分片 + 保留最近 N 小时
② 降频：量产版本日志级别降级（debug → warn），关掉高频 trace
③ 隔离：高写入流量的数据放到独立目录/分区，避免影响系统与用户数据
④ 合并：小写入攒批落盘（第 11 章），减少 fsync 次数与写放大
⑤ 水位：为 /data 设保护水位（如剩余 < 10% 触发自动清理最旧录像）
```

### 16.4 闪存寿命

UFS 的 P/E 次数有限，**长期循环写是主要损耗源**。可行手段：延长录像分片长度（减少元数据写）、顺序写优于随机小写、日志写内存盘再批量落盘、监控 `storaged` 的寿命指标并做预警。

**这也是为什么"车机不要无脑开全量日志"**——写寿命比空间更早成为瓶颈。

### 16.5 出厂预置内容的落盘策略

"恢复出厂后预置内容还在"（出厂地图、预置媒体、标定数据）要求内容**不在 userdata**：

| 内容 | 放哪 | 理由 |
|---|---|---|
| 系统级预置媒体/地图 | 系统分区（只读） | 恢复出厂不丢，可被 OTA 更新 |
| 每用户个性化初始数据 | 首次开机初始化脚本 | 每个用户各一份（per-user） |
| 设备级标定/序列号 | `persist` / 厂商分区 | 绝不能被 userdata 格式化影响 |
| 用户可修改的默认配置 | 系统默认值 + 首次运行复制到私有目录 | 避免共享状态被改坏 |

---

## 十七、调试工具箱

```bash
# ── 分区与挂载 ──────────────────────────────
adb shell cat /proc/mounts                  # 当前实际挂载（最权威）
adb shell mount | grep -E "data|emulated|media"
adb shell df -h                             # 各分区容量与剩余
adb shell ls -l /dev/block/by-name/         # 分区块设备名（需权限）
adb shell getprop ro.build.ab_update        # 是否 A/B
fastboot getvar current-slot                # 当前槽位

# ── 卷（vold 视角）──────────────────────────────
adb shell sm list-volumes all               # 所有卷及状态
adb shell dumpsys mount | head -60          # vold 状态：卷、加密、用户解锁

# ── 应用数据 ──────────────────────────────
adb shell dumpsys package com.example.app | grep -iE "dataDir|codePath|permission"
adb shell run-as com.example.app ls -l      # 以 App 身份进它自己的目录
adb shell run-as com.example.app du -sh .   # 统计占用
adb shell pm clear com.example.app          # 清数据（等同用户点"清除数据"）

# ── 空间与配额 ──────────────────────────────
adb shell du -sh /data/data/* 2>/dev/null | sort -h | tail -20
adb shell dumpsys diskstats                 # App 大小、配额、系统统计
adb shell dumpsys storaged                  # 存储寿命/IO（设备支持时）

# ── 媒体库 ──────────────────────────────
adb shell content query --uri content://media/external/images/media --projection _id:_display_name | head

# ── 用户与加密 ──────────────────────────────
adb shell pm list users ; adb shell am get-current-user
adb shell getprop ro.crypto.state           # encrypted / unencrypted
adb shell getprop ro.crypto.type            # file / block（FBE / FDE）
```

**排查三板斧**：

1. **看分区与挂载**（`df`、`/proc/mounts`）——空间不够？分区根本没挂？
2. **看权限与视图**（`ls -lZ`、`run-as`、`dumpsys package`）——是这个 App 没权限，还是 FUSE 视图过滤？
3. **看加密与用户状态**（`getprop ro.crypto.*`、`dumpsys mount`）——数据是否根本没解密（用户未解锁 / DE 与 CE 用错）？

---

## 十八、常见问题排查表

| 现象 | 最可能的原因 | 章节 |
|---|---|---|
| `EACCES / Permission denied` 写 `/sdcard/xxx` | Scoped Storage 限制（targetSdk 30+） | 8、10 |
| 代码在 Android 9 好使，13 崩 | 权限模型换代（`WRITE_EXTERNAL_STORAGE` 失效、Android/data 不可访问） | 8.1 |
| 申请了存储权限还是不能写 | targetSdk ≥ 30 时该权限无任何作用 | 8.3 |
| `ENOSPC` 但整机还有几十 GB | **命中 App 配额**，不是盘满 | 13.1 |
| 文件管理器看不到 `Android/data` | Android 11+ 的访问限制（设计如此） | 14.4 |
| 文件写进去了但相册里没有 | 媒体库靠扫描，需 insert 或通知扫描 | 14.1 |
| 目录不想被相册索引 | 放一个空 `.nomedia` | 14.2 |
| 删了照片空间没全回来 | 缩略图/回收站等派生数据 | 14.3 |
| 开机自启服务读不到自己的数据 | 跑在 DE 阶段，CE 未解锁 | 4.4 |
| 重启后 SAF 拿到的 URI 失效 | 忘了 `takePersistableUriPermission` | 9.2 |
| 分享文件抛 `FileUriExposedException` | 用了 `file://`，须换 FileProvider 的 `content://` | 9.3 |
| `adb push` 到 `/system` 失败/重启丢失 | 只读分区 + verity + A/B 槽位 | 3.3、3.4 |
| 卸载 App 后照片还在 / 还占空间 | 共享媒体库不随卸载删除 | 6 |
| 换用户后 App 数据"没了" | 每个用户数据独立（隔离，非丢失） | 12.2 |
| 恢复出厂后标定数据还在 | 在 `persist`/厂商分区，不属于 userdata | 15.2 |
| 多用户下首次初始化跑了多次 | per-user 目录各一份，逻辑未区分 per-device | 12.3 |
| 写日志/录像时 App 掉帧 | fsync 在主线程 / 小写未合并 | 11 |
| 车机插 U 盘 App 读不到 | 卷未上报 / 未监听回调 / 权限 / 多卷路径 | 16.2 |
| 长时间录像后系统卡顿、写入失败 | 空间水位未控制 / 日志写爆 / 闪存压力 | 16.3 |
| 新加的分区挂载成功但读不到 | SELinux 上下文（file_contexts）缺失 | 3.5 |

---

## 十九、一图总结

```
┌ 逻辑视图层（应用/用户看到的世界）──────────────────────────────────────┐
│  /sdcard（= /storage/emulated/0）  /data/data/<pkg>  MediaStore 条目     │
├ 挂载与视图层 ────────────────────────────────────────────────────────┤
│  FUSE(MediaProvider) 按权限投影  ← 真正决定"你能看见什么"              │
├ 分区与文件系统层 ─────────────────────────────────────────────────────┤
│  userdata(/data, f2fs, FBE 加密)  ← 唯一装用户数据的分区               │
│  super → system/vendor/product/system_ext/odm（只读 + dm-verity + AVB）│
│  metadata / persist / misc / frp …（系统与厂商专用）                   │
├ 物理层 ──────────────────────────────────────────────────────────────┤
│  UFS/eMMC：擦写寿命有限、随机写慢、必须擦后写                           │
└──────────────────────────────────────────────────────────────────────┘

路径映射（记这三行就够）：
  /data/data/<pkg>          → /data/user/<userId>/<pkg>       （每用户一份，CE 加密）
  /sdcard                   → /data/media/<userId>            （"外部存储"其实是 /data 的一部分）
  /storage/emulated/<userId> ← FUSE ← /data/media/<userId>    （你看到的是过滤后的视图）

权限一句话史：4.4 给私有目录 → 6.0 运行时权限 → 10 分区存储 → 11 强制 + 全文件访问权限
            → 13 按媒体类型细分 → 14 部分照片授权。方向始终是"从全盘可写到按需授权"。

车机三件事：多用户各自隔离、外接存储走 vold/StorageVolume、写入必须控水位（保闪存与系统可用）。
```

---

*关联阅读（同目录）：《Android 开发者网络串讲》（系统服务与权限模型的姊妹篇）、《Android 蓝牙机制详解》（`/data/misc` 落盘位置与多用户隔离的另一个例子）。*
