# Android OTA 机制详解——从 A/B 更新到回滚与 GSI

> OTA（Over-The-Air，空中升级）要回答的其实只有四个问题：**怎么打包、怎么下发、怎么装、装坏了怎么办**。这篇就按这四个问题分四段组织。
>
> - **打包（第 3、6 章）**：把一个"新系统"做成一个能被安装程序读懂的 OTA 包（payload.bin）；
> - **下发（第 7、8 章）**：包怎么从服务器到设备（整包/增量、流式/非流式、灰度分批）；
> - **安装（第 2、4、5、11 章）**：设备怎么把包里的数据写进存储、切到新系统、重启生效——A/B 与 non-A/B 两条路线；
> - **装坏了怎么办（第 9、10、15 章）**：回滚、救援、GSI/DSU 这种"先不刷机试试"的安全网。
>
> 车机读者请直接跳到第 13 章看"为什么车机 OTA 比手机难得多"。
> 分区与 A/B 槽位的基础概念（slot、super 动态分区、dm-verity/AVB）在《Android 存储机制详解》里已经讲透，本文**只讲 OTA 视角下的影响**，不重复定义。

---

## 目录

**一、开场：四个问题 + OTA 全景时序图**

**二、前提：分区布局与 slot 元数据（OTA 视角的差异）**

**三、OTA 包的两种形态：完整包 vs 增量包 + payload.bin 结构**

**四、A/B 更新原理：update_engine 状态机与回滚**

**五、Virtual A/B（Android 11+）：快照与 CoW**

**六、制作 OTA 包：ota_from_target_files 与签名体系**

**七、流式更新（streaming A/B）**

**八、服务器侧：接口、灰度、增量链**

**九、GSI 与 DSU：不刷机验证系统改动**

**十、回滚与救援：切槽、recovery、fastboot 现场处置**

**十一、传统 non-A/B 流程（车机常见）**

**十二、升级带来的兼容性问题**

**十三、车机专章：为什么车机 OTA 更难**

**十四、调试工具箱**

**十五、常见问题排查表**

**十六、读源码路线 + 源码路径速查表**

**十七、一图总结与关联阅读**

---

## 一、开场：四个问题 + OTA 全景时序图

### 1.1 一个贯穿全文的类比——"给飞行中的飞机换发动机"

一架飞机不能落地停飞去换发动机，但 OTA 要求**设备不进厂、用户照常用，系统却被换掉了**。A/B 更新就是"在备用的那台发动机上换好，然后让飞机切到备用机，确认能飞之后再废掉旧的"。

```
类比                      真实 OTA 概念
─────────────────────────────────────────────────────────
飞行中的飞机             正在用的当前系统（current slot）
备用发动机               空闲的 slot（A 或 B）
换发动机                 update_engine 把新系统写进空闲 slot
切到备用机              设置 slot 为 active，重启
确认能飞                首次启动成功 → markBootSuccessful
废掉旧发动机            旧 slot 标记为 unbootable / 下次覆盖
```

关键点：**升级过程中用户一直用的是旧系统，直到重启那一刻才切换**。这就是为什么"升级时手机还能打电话"。

### 1.2 OTA 全景时序图（怎么读）

下面这张图把"四个问题"串成一条线。读的时候按箭头从左到右：打包（左）→ 下发（中）→ 安装（右）→ 回滚（兜底）。

```
┌─ 服务器侧 ─────────────────────┐         ┌─ 设备侧 ───────────────────────────────┐
│                                │         │                                        │
│  build 出 target-files.zip     │         │  ┌─ update_engine（A/B 安装器）────────┐ │
│        │ ota_from_target_files │         │  │  ① 下载 payload（流式或先下完）     │ │
│        ▼                       │  HTTP   │  │  ② 校验签名 + hash（不解密整包）    │ │
│  ota_payload.bin(.xz)  ────────┼─────────┼──┼─▶③ 写入"非当前 slot"的分区          │ │
│  payload_properties.txt        │  Range  │  │  ④ 写完后设置该 slot active          │ │
│  ota_metadata / 包签名         │  请求   │  │  ⑤ 重启 ──▶ bootloader 选 active     │ │
│                                │         │  │  ⑥ 新系统启动 → update_verifier      │ │
│  灰度/分批/按机型分流           │         │  │     校验关键分区 → markBootSuccessful │ │
│                                │         │  └─────────────────────────────────────┘ │
└────────────────────────────────┘         │    失败 → 自动切回旧 slot（回滚）        │
                                           │    或进入 recovery / sideload 救援        │
                                           └────────────────────────────────────────┘
```

> 怎么读：上边一行是"包从哪来、带什么元信息"；中间 HTTP 是"下发"；右边 `update_engine` 框是"安装"；最底一行是"装坏了的兜底路径"。

### 1.3 本文与《Android 存储机制详解》的分工

| 主题 | 存储篇负责 | 本文（OTA 篇）负责 |
|---|---|---|
| `super` / 动态分区 | 是什么、怎么挂载 | **动态分区让 OTA 能重切空间**、Virtual A/B 借它做快照 |
| A/B slot | slot 是什么、双份系统区 | **update_engine 怎么用它、怎么切、怎么回滚** |
| dm-verity / AVB | 为什么系统区防改 | **verity 校验失败会怎样、OTA 包如何带合法 vbmeta** |
| `userdata` | 为什么升级后 App 数据还在 | **数据迁移、降级、版本兼容问题**（第 12 章） |

---

## 二、前提：分区布局与 slot 元数据（OTA 视角的差异）

OTA 安装程序的"操作对象"就是分区。但**分区布局直接决定了 OTA 的路线**——所以先看三类布局的差异。

### 2.1 三种分区布局对照

| 布局 | 系统分区形态 | 升级时写哪 | 重启切换 | 典型设备 |
|---|---|---|---|---|
| **A/B（无缝更新）** | `system_a`/`system_b` 等成对 | 写**非当前** slot，不干扰在用系统 | bootloader 选 active slot | 现代手机、主流车机 |
| **non-A/B（恢复式）** | 单个 `system` | 进 **recovery** 写 `system` | recovery 直接重启新系统 | 老车机、部分工控 |
| **Virtual A/B** | 物理上可能只一份（或逻辑双份）+ CoW 快照 | 写当前 slot 的快照，merge 到原位 | 同 A/B，但空间占用更低 | Android 11+ 新设备 |

> 一句话记忆：**A/B 是"两份实体、写空闲那份"；non-A/B 是"一份、进 recovery 覆盖写"；Virtual A/B 是"一份实体 + 写时复制快照"，省空间又保留无感升级。**

### 2.2 slot 元数据：谁来记住"该启动哪一份"

A/B 设备需要一块"小账本"记录每个 slot 的状态。三处相关：

| 元数据 | 存放 / 负责方 | OTA 中的作用 |
|---|---|---|
| **`misc` 分区里的 slot 数据** | bootloader 读取 | 记录 A/B 各自的 `successful` / `active` / `unbootable` 标志 |
| **BCB（Bootloader Control Block）** | 通常也在 `misc` | recovery 与 bootloader 之间的"通信便条"，如"下次启动进 recovery" |
| **`boot_control` HAL** | `hardware/interfaces/boot/`（1.0/1.1/2.0…） | 系统侧（update_engine）通过它向 bootloader 设置 active slot、查询状态 |

```bash
# 看当前 slot 与两份槽的状态（bootctl 是 boot_control HAL 的命令行壳）
adb shell bootctl getnumberofslots      # 通常是 2
adb shell bootctl getcurrentslot        # 当前正在跑的是 A 还是 B
adb shell bootctl getslotinfo 0         # slot 0 的 successful/active/unbootable 标志
```

`boot_control` HAL 版本差异：老设备是 `1.0`，较新的是 `1.1`（增加 `setSnapshotMergeStatus` 等分阶段回调）和 `2.0`。**不要写死具体方法签名**——不同版本接口略有差异，调用方（update_engine）依赖 HAL 版本协商。

### 2.3 动态分区（super.img）对 OTA 的影响

《Android 存储机制详解》已说明 super 是"装 system/vendor/product 的容器，逻辑分区大小写在元数据里"。对 OTA 而言它带来两个能力：

1. **OTA 能重切分区大小**：新版本 `system` 变大、`vendor` 变小，OTA 时直接改 `super` 内的逻辑分区尺寸，无需改 GPT 分区表。
2. **Virtual A/B 依赖它**：快照（snapshot）挂在 `super` 的逻辑分区之上，所以 OTA 包操作的是"逻辑分区 + 快照层"，不是物理块。

```bash
# 看 super 内逻辑分区真实大小（需 root / 工程权限）
adb shell lpdump                    # 列出 super 里的逻辑分区与尺寸
adb shell lpdump --slot 1           # 看另一个 slot 的布局
```

> 注意：non-A/B 设备没有 super 动态分区概念（或只有静态 system），所以**动态分区相关能力是 A/B / Virtual A/B 才有的**。

---

## 三、OTA 包的两种形态：完整包 vs 增量包 + payload.bin 结构

### 3.1 完整包 vs 增量包

| 维度 | 完整包（full） | 增量包（incremental / delta） |
|---|---|---|
| 包含内容 | 目标版本**所有**分区的完整数据 | 仅"源版本→目标版本"的**差异** |
| 包体大小 | 大（几百 MB ~ 几 GB） | 小（几 MB ~ 几百 MB） |
| 适用前提 | 任意设备都能装 | **必须当前系统等于某个特定基线版本** |
| 失败后果 | 总能刷（只要签名对） | 基线不符直接拒绝安装 |
| 典型用途 | 出厂镜像、救砖、跨大版本 | 日常小版本 OTA、省流量 |

类比：完整包是"整本新书寄给你"，增量包是"只寄修改的那几页 + 告诉你贴到哪"。

### 3.2 增量算法：block-based / bsdiff / imgdiff

增量包要把"差异"算准且包体小，AOSP 用了几种算法：

| 算法 | 作用层 | 特点 |
|---|---|---|
| **block-based（基于块的差异）** | 整块（如 4KB）粒度 | 最通用，但对"内容挪动"不友好，包体偏大 |
| **bsdiff** | 二进制文件级 | 针对可执行文件优化，擅长"同文件小修改"，包体小 |
| **imgdiff** | 镜像/文件结构感知 | 理解 zip/apk/镜像结构，比 bsdiff 更省（如把 APK 当结构化数据 diff） |

> 工程取舍：源码/资源改动多用 imgdiff/bsdiff；整分区数据变动（如 vendor 镜像）常用 block-based。一个 OTA 包里**不同分区可以混用不同算法**，由 manifest 描述。

### 3.3 payload.bin 结构（安装程序的"施工图"）

OTA 包的核心就是 `payload.bin`（可能再压缩成 `payload.bin.xz`）。它大致由三部分组成：

```
payload.bin
 ├─ BlobHeader + Magics            ← 魔数，安装程序先认它是 OTA payload
 ├─ Manifest（清单）               ← 描述本次更新涉及哪些分区、版本号、操作列表
 │     ├─ 分区 A：操作序列（替换/快照/写块…）
 │     ├─ 分区 B：操作序列
 │     └─ … 
 ├─ 分区数据（按操作序列组织的 blob 流）  ← 真正的差异数据或完整数据
 └─ 签名（payload 签名，用于校验完整性）
```

- **Manifest（清单）**：安装程序首先解析它，知道"要更新 `system`、`vendor`、`boot` 哪些分区，分别用什么操作"。
- **分区操作（PartitionUpdate）**：每个分区有一串操作（`REPLACE`、`ZERO`、`DISCARD`、`SOURCE_COPY`、`BROTLI_BSDIFF`…），A/B 下通常是"写目标 slot"。
- **安装步骤**：update_engine 按顺序——下载 → 逐分区校验 → 写块 → 写完做整体 hash 校验。

### 3.4 payload_properties.txt（流式更新与校验用）

和 `payload.bin` 配套的纯文本文件，记录校验与版本信息，服务器侧和流式更新都会用到：

```text
# 典型 payload_properties.txt 字段（示意，不同版本字段略有差异）
PAYLOAD_MINOR_VERSION=3
FILE_HASH=ab12cd34...          # payload.bin 的整体哈希
FILE_SIZE=123456789
METADATA_HASH=...
METADATA_SIZE=...
```

> 第 7 章"流式更新"会解释：客户端先读这个文件，就能在不下载完整包的前提下边下边校验、边下边写。

### 3.5 包签名 vs payload 签名（两层）

容易混的两层签名：

| 签名层 | 签的是什么 | 校验方 | 作用 |
|---|---|---|---|
| **payload 签名** | `payload.bin` 内部 | `update_engine` 在写入前校验 | 防止 OTA 数据被篡改 |
| **OTA 包（zip）签名** | 整个 OTA zip（含 payload） | recovery / 安装器入口 | 防止整包被替换（non-A/B 场景更关键） |

两者使用同一套密钥体系（见第 6 章 `signapk` 与 release-keys/test-keys）。

---

## 四、A/B 更新原理：update_engine 状态机与回滚

A/B 更新的"安装器"是 `update_engine`（系统里常驻的原生服务）。它把新系统写进**非当前** slot，全程不打断用户。

### 4.1 update_engine 的状态机（核心流程）

```
[ IDLE ] 空闲，等待 OTA 任务
   │ 收到 payload 源（本地文件 / URL）
   ▼
[ DOWNLOADING ] 下载 payload（流式更新时边下边写，见第 7 章）
   │ 下载完成 / 或边下边写完成
   ▼
[ VERIFYING ] 校验签名 + 每块 hash（对应第 3 章 payload 校验）
   │ 校验通过
   ▼
[ WRITE / FINALIZING ] 把数据写入"非当前 slot"的目标分区
   │ 写完、整体校验通过
   ▼
[ 设置 slot active ] 通过 boot_control HAL 把目标 slot 标记为 active
   │
   ▼
[ REBOOT ] 重启 → bootloader 选 active slot 启动新系统
   │
   ▼
[ 新系统启动 → update_verifier ] 校验关键分区 verity
   │ 成功
   ▼
[ markBootSuccessful ] 把该 slot 标记 successful（回滚窗口关闭）
```

### 4.2 为什么"升级失败不会变砖"

关键设计：**新旧系统同时存在**。重启前用的是旧 slot；重启后如果新 slot 启动失败，bootloader 依据 slot 元数据发现"新 slot 没标 successful"，会**自动回退到旧 slot**。

```bash
# 手动查看 / 操作 slot（bootctl 是 boot_control HAL 的命令行壳）
adb shell bootctl setactive 1        # 把 slot 1 设为 active（下次重启进它）
adb shell bootctl markBootSuccessful # 标记当前 slot 启动成功（关闭回滚窗口）
adb shell bootctl setunbootable 0    # 标记 slot 0 不可启动（放弃它）
```

> 注意：日常不用手动 `markBootSuccessful`——新系统首次启动会由 `update_verifier` 自动完成。手动调用只在调试/救援时用。

### 4.3 回滚机制：rollback index 与 markBootSuccessful

两类"版本守卫"防止降级攻击与静默回滚：

| 机制 | 作用 | 触发方 |
|---|---|---|
| **rollback index（回滚索引）** | 记录"最低可接受安全版本"，防止刷入旧的有漏洞的 bootloader/system | AVB / bootloader 在启动早期校验 |
| **markBootSuccessful** | 新 slot 首次成功启动后被标记；未标记的 slot 在下次启动被视为失败 | `update_verifier` + init 流程 |

关系：新系统启动 → `update_verifier`（见 4.4）校验关键分区 verity 通过 → 调用 `markBootSuccessful` → 回滚窗口关闭，此后除非再次 OTA，否则不会再回退。

### 4.4 update_verifier 与 dm-verity 的关系

- **dm-verity**：对只读分区做块级哈希树校验（见存储篇）。任何一块被改，读到即报错。
- **update_verifier**：新系统**首次启动**时，由 init 触发，读取 `fstab` 中标记为 `verify` 的分区，验证其 verity 完整性。**验证通过才允许 `markBootSuccessful`**。

```
新系统启动
  └─ init 解析 fstab，发现需要 verity 的分区
       └─ update_verifier 校验关键分区（system/vendor/boot…）
            ├─ 通过 → markBootSuccessful → 回滚窗口关闭
            └─ 失败 → 不标记 → 下次启动回退旧 slot / 进入救援
```

> 这解释了第 15 章"verity 报错"：如果 OTA 后某分区哈希对不上（包被改、签名错、或 verity 元数据不匹配），新系统无法通过 verifier，表现为"升级后反复回滚/卡开机"。

---

## 五、Virtual A/B（Android 11+）：快照与 CoW

### 5.1 为什么需要 Virtual A/B

传统 A/B 问题是**空间翻倍**：system/vendor 各两份，闪存小的设备扛不住。车机闪存常比旗舰手机紧张。Virtual A/B 的目标：**保留"无感升级 + 可回滚"的好处，但不再物理占两份空间**。

引入版本：**Android 11（API 30）** 起 AOSP 正式支持 Virtual A/B。

### 5.2 核心思想：写时复制（Copy-on-Write, CoW）快照

Virtual A/B 不立刻把新数据写到"另一份完整分区"，而是：

- 在**当前 slot 的分区上叠加一个快照层（snapshot）**；
- OTA 写入时，对"要改的块"先复制原块到快照、再写入新数据（CoW）。**未改的块仍然共享原数据**；
- 新系统启动后，若一切正常，把快照**合并（merge）**回原分区；若失败则丢弃快照、回到原状。

```
物理存储（只有一份 system）
 └─ system 分区本体
       + 快照层（snapuserd 管理）
            ├─ 改过的块：新数据（写时从原块复制）
            └─ 未改的块：直接指向原 system 区块（共享，省空间）
```

### 5.3 snapuserd 与 merge 流程

- **snapuserd**：运行在用户空间的守护进程，拦截对"带快照的分区"的读写，实现 CoW 语义。它让"看起来像两份分区"但物理只占增量。
- **merge（合并）**：新系统成功启动后（markBootSuccessful 之后），系统在后台把快照数据写回真实分区，完成后删除快照层。期间若断电，下次启动能继续 merge（这是 Virtual A/B 的健壮性关键）。

```
OTA 写入 ──▶ 创建快照（snapshot）──▶ 重启进新系统（读走快照层）
                                         │ 成功
                                         ▼
                                  后台 merge ──▶ 快照层消失，数据落回原分区
```

### 5.4 普通 A/B vs Virtual A/B 取舍表

| 维度 | 普通 A/B | Virtual A/B（11+） |
|---|---|---|
| 空间占用 | 双份系统分区 | 仅增量快照，省空间 |
| 用户数据迁移 | 无需迁移（`/data` 单份） | 同样无需迁移 |
| 回滚 | 切换 slot 即可 | 丢弃快照即可 |
| merge 期间风险 | 无（两份实体） | merge 中异常断电需能续 merge |
| 复杂度 | 低 | 高（snapuserd、快照管理） |
| 适用 | 闪存充裕设备 | 闪存紧张 / 车机 |

> 车机选型建议：闪存紧张 + 不能随时重启 → 优先 Virtual A/B，但要重视 merge 流程的健壮性验证（见第 13 章）。

---

## 六、制作 OTA 包：ota_from_target_files 与签名体系

OTA 包不是手工拼的，而是由 `target-files.zip`（build 产物）经 `ota_from_target_files` 生成。

### 6.1 基本命令

```bash
# 从 target-files 生成完整 A/B OTA 包
ota_from_target_files \
  -k releasekey          \  # 签名用的密钥（见 6.4）
  --ab                   \  # 生成 A/B 格式的 payload
  target_files.zip       \  # build 出的 target-files
  ota_full.zip             # 输出的 OTA 包

# 生成增量包（需要"源"和"目标"两份 target-files）
ota_from_target_files \
  --ab --incremental_from old_target_files.zip \
  -k releasekey \
  new_target_files.zip ota_incremental.zip
```

### 6.2 关键参数对照

| 参数 | 含义 | 备注 |
|---|---|---|
| `--ab` | 生成 A/B payload | 现代设备必选 |
| `--virtual_ab` / `--virtual_ab_retrofit` | 生成 Virtual A/B 包 | 11+；retrofit 用于老设备改造 |
| `--incremental_from <zip>` | 增量包基线 | 不指定则为完整包 |
| `-k <key>` | 指定签名密钥 | 见 6.4 |
| `--payload_signer` | 自定义 payload 签名器 | 用外部 HSM/签名服务时 |
| `postinstall` | 配置升级后脚本 | 见 6.3 |

### 6.3 postinstall：升级后在新系统里跑的钩子

某些升级需要"在新系统第一次启动时做点事"，例如重新生成 `boot` 镜像、迁移 vendor 数据。这些通过 **postinstall 机制**在目标 slot 的 `/system` 内放置脚本，新系统首次启动时执行。

```
OTA 安装完成
  └─ 新 slot 标记 active → 重启
       └─ 新系统启动早期执行 postinstall 脚本（在 chroot 到新 slot 的环境下）
            └─ 成功后继续正常启动
```

> 车机常见用法：vendor 镜像变更后，postinstall 里重算某些校准或重建 `vendor` 的派生数据。注意 postinstall 失败会导致升级被判失败、触发回滚。

### 6.4 payload 签名与包签名：密钥体系、release-keys 与 test-keys

Android 构建内置两套密钥：

| 密钥 | 用途 | 风险 |
|---|---|---|
| **test-keys** | 开发/调试版默认签名 | 公开、人人都有，**量产绝不能用来签 OTA**（可被伪造） |
| **release-keys** | 厂商自己的正式密钥 | 必须妥善保管私钥，丢失=无法发正式 OTA |

签名相关工具（在 `build/target/product/security/` 与 `development/tools/` 下）：

```bash
# 用 signapk 给 zip 签名（包签名层）
java -jar signapk.jar releasekey.x509.pem releasekey.pk8 \
     unsigned.zip signed.zip

# OTA payload 的签名由 ota_from_target_files 内部的 payload_signer 完成
# 密钥对：<key>.pk8（私钥）+ <key>.x509.pem（证书）
```

> 安全红线：正式 OTA 必须用 release-keys；test-keys 签的包装进量产车机等于给攻击者开了后门。**车机售后刷机工具拿到的也是 release 体系密钥**。

---

## 七、流式更新（streaming A/B）

### 7.1 边下边写 vs 先下完再写

普通 A/B 会先把整个 payload 下载到本地（需要一份 payload 的临时空间），再开始写。流式更新（streaming）则**边下载边写入目标 slot**，省掉"下载缓存"这一步。

| 模式 | 是否需要下载缓存 | 内存/磁盘压力 | 适用 |
|---|---|---|---|
| 非流式 | 需要（整包临时空间） | 高 | 网络稳定、空间充裕 |
| 流式（streaming） | 不需要 | 低 | 车机/空间紧张、断点续传 |

### 7.2 流式如何保证"下了就能写、且能校验"

关键在 **`payload_properties.txt`**（第 3.4 节）与 **HTTP Range**：

- 客户端先获取 `payload_properties.txt`，得到 `FILE_HASH`、`FILE_SIZE`、`METADATA_HASH` 等；
- 据此先校验 metadata，再**按分区块通过 HTTP `Range` 请求逐步拉取**，每拉一块就写一块并增量校验；
- 断网后可从断点续传（Range 偏移），不用从头下。

```bash
# 服务器侧需支持 Range 请求（Nginx/Apache 默认支持）
# 客户端（update_engine）内部用 Range 拉取 payload 的某段：
#   GET payload.bin  Range: bytes=0-1048575
#   GET payload.bin  Range: bytes=1048576-2097151
```

> 服务器坑：如果 CDN/网关**不支持或吞掉了 Range**，流式更新会退化为反复失败或退回到非流式，表现为"下载进度卡住"。第 8、15 章会涉及。

---

## 八、服务器侧：接口、灰度、增量链

OTA 不是只把包丢出去，服务器侧要管"发给谁、发哪个、失败怎么办"。

### 8.1 接口设计要点（不绑定具体协议）

典型 OTA 服务端要回答设备的四个请求：

```
设备 → 服务器：我是谁（机型/版本/区域/序列号）
服务器 → 设备：有没有适合你的更新？（含包 URL、大小、hash、基线要求）
设备 → 服务器：下载 payload（流式或整包）
设备 → 服务器：上报结果（成功/失败/已回滚）用于统计与止损
```

> 接口形态（REST / 长连接 / 厂商私有协议）不在本文范围；重点是**返回给设备的信息必须包含：包 URL、payload 大小与 hash、增量包的基线版本、签名公钥标识**。

### 8.2 灰度与分批

| 策略 | 做法 | 目的 |
|---|---|---|
| 灰度（canary） | 先放给 1% 设备，监控失败率 | 拦截"包本身有问题" |
| 分批（staged rollout） | 按时间/比例逐步放大 | 平滑服务器压力 + 留止损窗口 |
| 按版本/机型/区域分流 | 同一车型不同软件版本发不同包 | 避免错配基线 |

### 8.3 增量链过长导致的"包体膨胀"与"翻滚"策略

增量包依赖"当前版本 = 某个基线"。如果用户落后很多版本，服务器要么：

- 为每个落后版本都生成"链式增量"（A→B→C→D…），**链路越长，单包虽小但要连续装多次，且任何一环失败全盘回滚**；
- 或者**定期"翻滚"（roll up）**：合成为一个"从小版本直达最新"的增量，或干脆对老版本直接推完整包。

```
用户停在 V2，最新 V10：
  方案甲：V2→V3→V4…→V10（10 次安装，脆弱）
  方案乙：定期生成 V2→V10 的"翻滚增量"，或 V2 直接推完整包
```

> 车机建议：对长期不联网的车（如库存车）直接推完整包最稳；联网频繁的车用翻滚增量控制体积。

---

## 九、GSI 与 DSU：不刷机验证系统改动

### 9.1 GSI 是什么

**GSI（Generic System Image，通用系统镜像）** 是 Google 提供的"纯净 AOSP `system` 镜像"，用来验证设备是否符合 Treble 兼容性——把 GSI 刷进 `system` 槽，看设备能否正常跑。

```
正常系统：vendor 厂商 + system 厂商
GSI 验证：vendor 厂商 + system GSI（验证 vendor 接口兼容性）
```

用途：确认"你的 vendor/HAL 实现是否和标准 Android 契约兼容"，是 VTS / Treble 验证的一环。

### 9.2 DSU 是什么、怎么用

**DSU（Dynamic System Update，动态系统更新）** 让设备**不用刷机**就能临时跑另一个 `system` 镜像（通常就是一个 GSI 或定制 system）：

- 通过 `DynamicSystemManager`（一类系统服务）把镜像下载到 `userdata` 的一个动态分区；
- 重启后临时从 DSU 镜像启动；
- 不想要了，在 DSU 界面"卸载"即可回到原系统，**原系统完全不受影响**。

```
正常启动：boot → 当前 system
DSU 启动：boot → 临时 DSU system（原 system 保留）
卸载 DSU：删除动态分区 → 回到原 system
```

### 9.3 开发调试的正确姿势（不用刷机验证系统改动）

当你改了 Framework / SystemUI，想快速验证但**不想破坏正在用的车机系统**：

```bash
# 触发 DSU 安装（示意，具体命令随版本/厂商略有差异）
adb shell am start -n com.android.dynsystem/.VerificationActivity   # DSU 安装入口
# 或通过 settings / 厂商调试菜单选择 DSU 包
# 安装完成后重启 → 进入 DSU 系统验证你的改动
# 验证完在 DSU 界面选择"卸载"，回到原系统
```

DSU 的镜像安装与卸载要点：

| 操作 | 命令/入口 | 结果 |
|---|---|---|
| 安装 DSU 镜像 | DSU 安装器 / `adb` 推送镜像 | 写入动态分区，需重启生效 |
| 切到 DSU 启动 | 重启（自动从 DSU 起） | 临时运行新 system |
| 卸载 DSU | DSU 界面"卸载" / `dsu` 相关命令 | 删除动态分区，回到原 system |

### 9.4 限制（别踩坑）

- **需要设备支持 DSU**（Android 10+ 起框架支持，但厂商常裁剪或需工程版）；
- DSU 镜像通常**不能改 vendor/boot**，只能换 `system` 一类的上层；
- 占用 `userdata` 空间，空间不足会安装失败；
- 车机量产环境一般**关闭 DSU**（避免非预期切换），只在开发与售后调试镜像时使用。

---

## 十、回滚与救援：切槽、recovery、fastboot 现场处置

升级失败不是终点，关键是"能退回去 / 能救回来"。

### 10.1 set_active 切槽（A/B 设备）

最直接的人工回滚：手动把另一个 slot 设为 active，重启即回到旧系统。

```bash
adb shell bootctl getcurrentslot      # 确认当前是哪个 slot
adb shell bootctl setactive 0         # 切到 slot 0（假设它在升级前是好用的）
adb reboot                            # 重启进旧 slot
```

> 前提：旧 slot 没被标 `unbootable`、且数据是兼容的（第 12 章讲降级风险）。

### 10.2 启动失败的救援路径（从轻到重）

```
① 自动回滚：新 slot 未 markBootSuccessful → bootloader 自动回旧 slot
       │ 仍失败
② Recovery factory reset（恢复出厂）：清 /data，保留系统分区
       │ 仍失败（系统分区本身损坏）
③ sideload：进 recovery，adb sideload 一个正确签名的 OTA 包覆盖装
       │ 仍失败（bootloader/recovery 损坏）
④ fastboot 刷分区：线刷 boot / system / vendor 等具体分区
       │ 仍失败
⑤ 售后网点工具 / 产线设备：专用刷机盒 + release 密钥强刷
```

### 10.3 各救援手段对照

| 手段 | 清数据吗 | 需要什么 | 适用 |
|---|---|---|---|
| 自动回滚 | 否 | 无（自动） | A/B 升级初次失败 |
| recovery factory reset | **清 /data** | 进入 recovery | 系统能起但用户数据坏了 |
| `adb sideload` | 否（OTA 包内决定） | 正确签名 OTA 包 + USB | 系统分区损坏但 recovery 还在 |
| fastboot 刷分区 | 看刷哪个 | 解锁 bootloader + 镜像 | recovery 也坏了 |
| 售后/产线工具 | 看工具 | release 密钥 + 专用硬件 | 彻底变砖 |

### 10.4 sideload 实操

```bash
adb reboot recovery                 # 进 recovery（部分设备是组合键）
# 在 recovery 菜单选 "Apply update from ADB"
adb sideload ota_full.zip          # 把本地 OTA 包推过去安装
# 成功后会提示，重启即可
```

> sideload 走的也是 OTA 安装逻辑，所以**包必须签名正确**，否则会被拒（见第 15 章"sideload 失败"）。

### 10.5 车机"变砖"的现场处置顺序

车机不能随便拆电池、用户不在现场，处置顺序与手机不同（详见第 13 章）。原则：**先尝试无数据损失的回滚 → 再考虑 sideload → 最后才进售后刷机**。现场口诀：

```
看 slot 状态 → 能切回旧 slot 就切 → 不能就 sideload → 再不行进售后
永远不要在"点火状态/车辆行驶相关 ECU 在线"时强刷 bootloader 级分区
```

---

## 十一、传统 non-A/B 流程（车机常见）

很多老车机、工控、以及部分厂商定制仍是 **non-A/B**：只有一份系统分区，升级必须进 recovery 覆盖写。

### 11.1 recovery 与 update.zip

non-A/B 的升级包通常叫 `update.zip`，由 recovery 解析执行：

```
设备重启进 recovery
  └─ recovery 解析 update.zip 里的 META-INF/com/google/android/updater-script
       └─ 按脚本把新 system/vendor 等写到对应分区
            └─ 写校验 → 重启进新系统
```

### 11.2 BCB（Bootloader Control Block）与 applypatch

- **BCB**：recovery 和 bootloader 之间的"便条"。主系统想让下次进 recovery，就往 BCB 写命令；recovery 完成后清掉它。
- **applypatch**：recovery 里做增量修补的工具（对旧文件打二进制 patch 得到新文件），相当于 non-A/B 世界的 bsdiff/imgdiff 执行器。

### 11.3 为什么 non-A/B 仍在用

| 原因 | 说明 |
|---|---|
| 老平台遗留 | 早期车机 SoC 不支持 A/B，改造成本高 |
| 闪存极小 | 放不下双份系统 |
| 厂商定制 recovery 成熟 | 已有稳定的刷机/售后体系 |
| 升级不频繁 | 车机本就少升级，停机窗口可接受 |

> 代价：**升级期间设备不可用**（进 recovery 黑屏），且一旦写入失败、recovery 也坏了，就比 A/B 更难救（没有第二份系统兜底）。

---

## 十二、升级带来的兼容性问题

OTA 不只是"换文件"，还会触发一连串兼容性连锁反应。

### 12.1 数据迁移与版本降级

- **升级**：新系统首次启动可能跑 `PackageManager`（见关联阅读 `androidFrameworks/04_PMS`）做数据迁移（DB schema、SharedPreferences 结构）。
- **降级（回滚到旧 slot）**：旧系统可能读不懂新系统写出的数据格式 → **数据不兼容**。所以 A/B 回滚通常只切系统，**`/data` 不动**；但若是"用户主动降级刷机"，就可能踩数据格式坑。

> 衔接：数据目录与多用户隔离见《Android 存储机制详解》第 12 章。

### 12.2 属性变化（衔接 init / 属性篇）

系统升级可能改变 `system.prop` / `vendor.prop` 或 init 的 `*.rc` 规则。App/服务依赖的某个 `ro.` / `persist.` 属性若被改名或取值变化，会静默失效。

```
升级后某功能异常 → 先 diff 新旧 build.prop / vendor.prop
                 → 看相关属性是否存在、取值是否匹配
```

### 12.3 SELinux 策略收紧导致旧服务起不来

新系统的 `sepolicy` 可能收紧：原本允许某 vendor 服务访问的文件/套接字被拒。表现通常是"升级后某个后台服务起不来、logcat 里一堆 `avc: denied`"。

```
升级后服务起不来 → logcat 过滤 avc: denied
               → 确认是策略变化（对比旧 sepolicy）
               → 在 vendor 的 sepolicy 里补规则（见《SELinux 篇》）
```

### 12.4 verity 校验失败

若 OTA 包与设备 vbmeta/verity 不匹配（密钥错、镜像被改、或 bootloader 锁定状态不符），新系统启动会被 dm-verity 拦下（见 4.4）。表现："升级后卡开机 / 无限重启 / 进 recovery"。

### 12.5 AIDL HAL 接口版本变化（衔接 NDK 篇）

vendor 的 HAL（如 VHAL、audio HAL）用 AIDL/HIDL 定义接口。升级后如果 **Framework 期望的 HAL 接口版本** 与 vendor 实现不一致，会绑定失败 → 对应功能（车控、音频…）整体不可用。

```
Framework 起 CarService → 绑定 VHAL AIDL 接口
   └─ 版本不匹配 → 绑定失败 → 车控属性全部不可用
```

> 衔接：HAL 接口与 native 绑定机制见《Android NDK/JNI 详解》与 automotive 各篇。

---

## 十三、车机专章：为什么车机 OTA 更难

这是车机开发者最该读的一章。手机 OTA 的经验在车上要打折扣。

### 13.1 六大难点对照

| 难点 | 手机 | 车机 | 应对 |
|---|---|---|---|
| 不能随时重启 | 用户自己点"重启" | 行驶中绝不能重启 | 只在**熄火/充电/Park 档**窗口升级 |
| 双分区容量紧张 | 闪存大 | 车机闪存小、系统大 | 优先 Virtual A/B（11+） |
| 电量与网络条件 | 稳定 | 12V 电瓶 + 弱网/车库无网 | 升级前查电压、断点续传 |
| 静默安装 | 可交互确认 | 用户无感、后台完成 | 进度上报 + 可暂停 |
| 失败必须能回滚 | 可手动进 recovery | 用户不在现场 | 自动回滚 + 远程止损 |
| 售后刷机 | 用户送修 | 4S 网点 + 产线工具 | release 密钥强刷体系 |

### 13.2 熄火窗口与点火状态

车机升级最安全的时机是"车辆熄火且常电（ACC off 但 12V 在）"或"充电/Park"。要点：

- 升级前确认 **ignition 状态为 off**（通过 VHAL 的 `IGNITION_STATE` 属性，见 automotive 篇）；
- 升级中若检测到点火（用户上车），应**暂停并延后**，避免行驶中重启；
- 重启生效那一刻必须在安全窗口内完成。

### 13.3 电量与空间保护

```bash
# 升级前置检查（车机侧常见做法，命令随厂商不同）
# ① 电量/电压：读车辆电源状态（经 VHAL 或厂商电源服务）
# ② 空间：确认 /data 与快照所需空间（Virtual A/B 的 merge 需要余量）
adb shell df -h /data               # 看用户数据余量
adb shell snapshotctl dump          # 看快照占用（见 14 章）
```

> 规则：空间或电量不到阈值，**宁可推迟升级也不冒险**——半途断电在 Virtual A/B 下要能续 merge，但普通 A/B 半途则可能留一个半新半旧的 slot。

### 13.4 静默安装策略与进度上报

车机 OTA 多为后台静默下载 + 用户无感安装。设计要点：

- 下载走**流式 + 断点续传**（第 7 章），弱网可恢复；
- 安装进度、结果**上报服务器**（第 8 章），便于远程止损与灰度暂停；
- 升级后**自动回滚**必须默认开启，失败不依赖用户操作。

### 13.5 售后与产线刷机

- **售后网点**：用带 release 密钥的专用工具强刷（对应 10.3 的第⑤类），处理"彻底变砖/量产缺陷"；
- **产线**：新车上电首次写入完整系统 + 标定数据（标定存 `persist`，见存储篇 3.5），OTA 体系与产线刷机共用同一套镜像与密钥。

---

## 十四、调试工具箱

每条命令给 `#` 注释，按场景分组。

```bash
# ── update_engine 状态 ──────────────────────────
update_engine_client --status                  # 看当前 OTA 状态/进度（需权限或 eng 版）
update_engine_client --follow                  # 持续跟踪进度

# ── slot 与 boot_control ─────────────────────
adb shell bootctl getcurrentslot               # 当前在跑哪个 slot
adb shell bootctl getslotinfo 0                # slot 0 的 successful/active 标志
adb shell bootctl setactive 1                  # 把 slot 1 设为 active（下次重启进它）
adb shell bootctl markBootSuccessful           # 手动标记成功（调试用）

# ── 日志过滤 ─────────────────────────────────
adb logcat -s update_engine                    # update_engine 主日志
adb logcat -s payload_verifier                 # payload 校验相关
adb logcat | grep -i "verity\|avb\|vbmeta"     # verity/AVB 报错

# ── sideload 与 recovery ─────────────────────
adb reboot recovery                            # 进 recovery
adb sideload ota_full.zip                      # sideload 一个 OTA 包

# ── Virtual A/B 快照 ─────────────────────────
adb shell snapshotctl dump                     # 查看当前快照与 merge 状态
adb shell snapshotctl merge                    # 手动触发 merge（调试用）
# /metadata/ota/ 下存放快照相关状态（需 root）

# ── 通用系统状态 ─────────────────────────────
adb shell getprop ro.build.ab_update           # 是否 A/B（1=true）
adb shell getprop ro.virtual_ab.enabled        # 是否启用 Virtual A/B
adb shell dumpsys update_engine                # 部分版本支持，看服务内部状态
```

> 提示：很多命令需要 `userdebug`/`eng` 版本或 root。`update_engine_client` 在较新版本路径/参数可能有差异，**不要写死参数**，以设备实际 `--help` 为准。

---

## 十五、常见问题排查表

| 现象 | 最可能的原因 | 章节 | 第一命令 |
|---|---|---|---|
| 升级后卡开机 / 无限重启 | 新 slot 未 markBootSuccessful，bootloader 回退失败 | 4.3、10.1 | `adb shell bootctl getslotinfo 0` |
| slot 切换失败（设了 active 不生效） | boot_control HAL 版本不符 / 被锁定 | 2.2、4.2 | `adb shell bootctl setactive 1` 看报错 |
| merge 卡住 / 时间长 | Virtual A/B 快照大、磁盘慢、后台被限流 | 5.3 | `adb shell snapshotctl dump` |
| 增量包安装失败 | 设备基线版本与增量包要求不符 | 3.1、8.3 | 比对 `ro.build.version.incremental` |
| 签名校验不通过 | 用了 test-keys / 密钥不匹配 | 6.4 | `adb logcat -s update_engine` 看签名错误 |
| verity 报错（启动被拦） | 分区哈希与 vbmeta 不符 / 镜像被改 | 4.4、12.4 | `adb logcat \| grep -i vbmeta` |
| 回滚没生效（仍进坏系统） | 旧 slot 被标 unbootable / 数据不兼容 | 10.1、12.1 | `adb shell bootctl getslotinfo 1` |
| OTA 包体过大（流量爆） | 增量链未翻滚 / 推了完整包 | 8.3 | 查服务器下发包类型 |
| sideload 失败 | 包签名错 / recovery 不支持该格式 | 10.4、15 | `adb sideload ota.zip` 看报错 |
| 流式更新下载卡住 | CDN 不支持 HTTP Range | 7.2 | `curl -I -H "Range: bytes=0-100" <url>` |
| 升级后某 vendor 服务起不来 | SELinux 策略收紧（avc denied） | 12.3 | `adb logcat \| grep "avc: denied"` |
| 升级后车控功能全失效 | HAL AIDL 接口版本不匹配 | 12.5 | `adb logcat -s CarService VehicleHal` |
| postinstall 失败导致回滚 | 升级后脚本执行出错 | 6.3 | `adb logcat -s postinstall` |
| 车机升级中途"不敢重启" | 点火状态不为 off（用户在车上） | 13.2 | 读 VHAL `IGNITION_STATE` |
| 升级前置检查不通过（电量/空间） | 阈值未达，被策略推迟 | 13.3 | `adb shell df -h /data` |
| 双份系统占满闪存 | 普通 A/B + 大系统镜像 | 5.4 | `adb shell lpdump` 看 super 占用 |
| recovery 进不去（non-A/B） | 组合键错 / BCB 被清 | 11.2 | `adb reboot recovery` |
| 灰度期间部分车收不到更新 | 分流规则按版本/区域误判 | 8.2 | 查服务器设备画像上报 |

---

## 十六、读源码路线 + 源码路径速查表

### 16.1 建议阅读顺序

OTA 代码横跨系统服务、recovery、HAL、构建工具，建议按"安装器 → 包格式 → recovery → HAL → 构建"的顺序：

```
① system/update_engine          ← A/B 安装器：状态机、下载、写块、校验
② build/make/tools/releasetools ← ota_from_target_files 等打包脚本（Python）
③ bootable/recovery             ← non-A/B 的更新执行、applypatch、BCB
④ hardware/interfaces/boot      ← boot_control HAL（slot 切换）
⑤ system/core/snapuserd（或对应目录）← Virtual A/B 快照实现（11+）
⑥ frameworks/base 的 DynamicSystem ← DSU / GSI 相关服务
```

### 16.2 源码路径速查表

| 模块 | 路径 | 看什么 |
|---|---|---|
| A/B 安装器 | `system/update_engine/` | `update_engine_client`、状态机、payload 处理、verifier |
| 打包工具 | `build/make/tools/releasetools/` | `ota_from_target_files`、`common.py`、payload 生成 |
| recovery | `bootable/recovery/` | `updater`、applypatch、`install.cpp`、BCB 处理 |
| boot_control HAL | `hardware/interfaces/boot/` | `1.0`/`1.1`/`2.0` 接口与默认实现 |
| 快照（Virtual A/B） | `system/core/snapuserd/`（或 AOSP 对应目录） | CoW、merge、snapshot 管理 |
| verity 校验 | `system/extras/verity/`、`system/core/fs_mgr/` | update_verifier、fstab verify |
| DSU / 动态系统 | `frameworks/base/services/` 的 `DynamicSystem` 一类服务 | DSU 安装/卸载、镜像管理 |
| AVB / vbmeta | `external/avb/`、`platform/external/avb/` | 签名验证、rollback index |

> 不同 Android 版本目录可能有微调（尤其 snapuserd 早期在别处），**以你 checkout 的 manifest 为准**，不要死记路径。

---

## 十七、一图总结与关联阅读

### 17.1 一图总结（ASCII）

```
┌─ 服务器侧 ───────────────────────────────────────────────┐
│ build → target-files.zip → ota_from_target_files          │
│   → payload.bin(+签名) + payload_properties.txt + 包签名  │
│ 灰度 / 分批 / 按机型·版本·区域分流 / 增量链翻滚            │
└───────────────────────────┬──────────────────────────────┘
                            │ HTTP（支持 Range = 流式）
┌─ 设备侧 update_engine（A/B 安装器）──────────────────────┐
│ 下载 → 校验 → 写非当前 slot → 设 active → 重启           │
│   ├─ 普通 A/B：写另一份实体 slot                          │
│   └─ Virtual A/B(11+)：写 CoW 快照(snapuserd)→merge       │
│ 新系统首启：update_verifier 验 verity → markBootSuccessful │
│   └─ 失败 → 自动回滚 / recovery / sideload / fastboot      │
└──────────────────────────────────────────────────────────┘
        slot 元数据：misc/BCB + boot_control HAL（getcurrentslot/setactive）
        动态分区：super → system/vendor…（OTA 可重切大小）

记忆链：打包(payload) → 下发(Range/灰度) → 安装(update_engine 写 slot)
        → 校验(verifier) → 标记成功(markBootSuccessful) → 失败就回滚(bootctl)。
车机铁律：只在安全窗口升级、失败必回滚、密钥用 release、空间电量先查。
```

### 17.2 关联阅读

- 《Android 存储机制详解——从分区到 Scoped Storage 与多用户》：A/B slot、`super` 动态分区、dm-verity/AVB、多用户数据隔离（本文第 2、12 章的前置知识）。
- 构建相关（同目录构建篇）：`target-files.zip` 与 `ota_from_target_files` 的产出来源。
- init 与属性篇：升级后属性/rc 规则变化（第 12.2 章）。
- SELinux 篇：升级后策略收紧导致服务起不来（第 12.3 章）。
- 《Android NDK/JNI 详解》：HAL 接口的 native 绑定与版本协商（第 12.5 章）。
- `androidFrameworks/04_PMS`：升级后的数据迁移与包管理（第 12.1 章）。
- automotive 各篇（03 VHAL、04、05…）：车机升级窗口、点火状态、VHAL 接口版本（第 13 章）。

