# 通知机制详解：从 NotificationManagerService 到 SystemUI 展示

> 通知系统连接 App、前台服务、权限、渠道（Channel）、NMS（NotificationManagerService）和 SystemUI。App 调一次 `notify()` 只是入口——通知能不能发出、怎么排序、什么时候响铃震动、锁屏是否露内容、要不要 heads-up，全部由系统策略在 NMS 侧裁决，最后才交给 SystemUI 画出来。
>
> **本篇定位**：聚焦 **NMS 侧**（接收、裁决、rank、通道、响铃震动、锁屏可见性、DND）。SystemUI 侧的展示流水线（NotifPipeline / heads-up 决策 / 渲染）在本系列 `18_SystemUI机制详解` 第 10、11 节已详讲，本篇只在第 12 节画一条衔接线指过去。App 侧 `NotificationChannel` 的写法一句带过，见 `androidApp` 系列。
>
> **分工边界**（避免和姊妹篇重复）：
> - 前台服务怎么 `startForeground`、FSI 权限、foregroundServiceType → 见 `07_Service机制详解` 第 8 节。
> - 权限与 AppOps 的总框架 → 见 `14_权限机制详解`。
> - 震动要拿的 WakeLock 语义 → 见 `15_Power机制详解` 第 4 节。
> - 通知的 UI 渲染 / heads-up 弹不弹 → 见 `18_SystemUI机制详解` 第 10、11 节。
> - 多用户隔离 → 见 `19_多用户机制详解`。

---

## 目录

1. [开场：通知回答的三个问题](#1开场通知回答的三个问题)
2. [NotificationManagerService 架构](#2notificationmanagerservice-架构)
3. [Channel 体系（Android 8.0 的转折点）](#3channel-体系android-80-的转折点)
4. [importance 取值全表与用户改写](#4importance-取值全表与用户改写)
5. [notify() 的裁决链](#5notify-的裁决链)
6. [Ranking：一组排序调整器](#6ranking一组排序调整器)
7. [声音 / 震动 / 灯与免打扰 DND](#7声音--震动--灯与免打扰-dnd)
8. [前台服务通知的特殊规则](#8前台服务通知的特殊规则)
9. [通知的可见性与隐私](#9通知的可见性与隐私)
10. [通知的去重与合并](#10通知的去重与合并)
11. [跨用户与多用户](#11跨用户与多用户)
12. [SystemUI 衔接小节](#12systemui-衔接小节)
13. [调试工具箱](#13调试工具箱)
14. [常见问题排查表](#14常见问题排查表)
15. [读源码路线](#15读源码路线)
16. [源码路径速查 + 一图总结 + 关联阅读](#16源码路径速查--一图总结--关联阅读)

---

## 1. 开场：通知回答的三个问题

把通知系统当成"一栋大楼的收发室"最容易理解：App 像一个个公司，把"信"（Notification）塞进收发室（NMS），收发室决定这封信能不能进楼、摆在哪个格子、要不要按门铃、锁屏时能不能让人看见，最后由前台（SystemUI）把信摆上墙。

通知系统本质上要回答三个问题：

| 问题 | 谁负责 | 一句话 |
|---|---|---|
| **谁能发？** | 权限 + AppOps + 通道 | App 有没有 `POST_NOTIFICATIONS`、通道开没开、是不是被后台冻结 |
| **发出来排序谁说了算？** | `RankingHelper` + 一组排序调整器 | importance、是否最近打扰过、联系人星级、人工置顶，共同算出 globalSortKey |
| **响了之后谁负责？** | NMS `buzzBeepBlinkLocked` + SystemUI | NMS 决定声音/震动/灯与 DND 过滤；SystemUI 决定 heads-up 与点击去向 |

记住这条主线：**App 只负责"投信"，NMS 负责"审信和排信"，SystemUI 负责"贴信"**。绝大多数"通知不显示 / 不响 / 不弹"的 bug，定位的第一步就是判断卡在哪一关。

### 1.1 一次 notify() 的全景

```text
App 进程
  NotificationManager.notify(id, tag, notification)
        │ Binder（INotificationManager）
        ▼
system_server · NotificationManagerService
  enqueueNotificationInternal()
        ├─① 调用方校验：uid/pid、POST_NOTIFICATIONS(13+)、App 是否 suspended / 后台冻结（bucket）
        ├─② 通道校验：NotificationChannel 是否存在（8.0+）、importance 取值
        ├─③ 入队建记录：new NotificationRecord(sbn, channel, ...)
        ├─④ 去重/合并：同 key 更新 or 新建；分组 summary 的自动生成
        ├─⑤ Ranking：一组 NotificationSignalExtractor 重算 globalSortKey
        ├─⑥ 打断相关：DND(ZenModeHelper) 过滤、声音/震动/灯 → buzzBeepBlinkLocked()
        └─⑦ 下发监听者：NotificationListeners.onNotificationPosted → SystemUI
                       │ （衔接见本篇第 12 节 → 18 篇第 10/11 节）
                       ▼
                 SystemUI NotifPipeline → 渲染 / heads-up / 锁屏
```

后面每一章都是把这条链上的某一环拆开讲。

### 1.2 怎么读这篇

- 想搞清楚"为什么我的通知没出来" → 先读第 5 章（裁决链）和第 14 章（排查表）。
- 想看懂排序为什么乱 → 第 6 章（Ranking）。
- 想改车机的响铃 / 震动 / 锁屏策略 → 第 7、9 章。
- 想接 SystemUI 渲染 → 第 12 章直接跳 18 篇。
- 全文约定：**版本差异（8.0 channel、13 POST_NOTIFICATIONS、12 FSN 延迟、14 全屏 intent 收紧）一律用「Android 视角」标注**，照抄前先看 SDK 版本。

---

## 2. NotificationManagerService 架构

NMS 跑在 `system_server` 进程，是通知的"中央裁决所"。它不画任何 UI，只产出结构化的"通知记录"并通过 Binder 推给 SystemUI 等监听者。

### 2.1 三个核心数据对象

| 对象 | 真实类 / 说明 | 角色 |
|---|---|---|
| 入站封装 | `StatusBarNotification`（简称 sbn） | App 传来的 `(pkg, user, id, tag, notification)` 的不可变封装，**通知的"身份"就挂在它上面** |
| 内部记录 | `NotificationRecord` | NMS 自己持有的"活记录"：sbn + 通道 + ranking 信息 + 统计 +  flags。排序、过滤、下发全都围绕它 |
| 通道存储 | `NotificationChannel` + `NotificationChannelGroup` | 每条通知必须归属一个通道；通道按 `(pkg, userId)` 维度由 `RankingHelper` 维护 |
| 排序信号 | 一组 `NotificationSignalExtractor` | 各自从 record 抽一个"信号"拼成排序键（见第 6 章） |

「Android 视角」：`StatusBarNotification.getKey()` 是通知的全局唯一键，格式大致是 `user|pkg|tag|id|postTime`——注意末尾带了 `postTime`，所以**同一 `(id, tag)` 重新 post 会得到新的 key**，但 NMS 内部用 `(id, tag)` 做"更新语义"（见第 10 章），key 的 postTime 只是用来区分历史实例。

### 2.2 线程模型：主线程 + RankingHandler 工作线程

NMS 不是把所有活都压在 `system_server` 主线程（`FgThread` / `UiThread` 视调用来源）上。排序这类可能较重的活被挪到专门的 `RankingHandler`——一个跑在 **工作线程** 上的 `Handler`：

```text
调用入口（Binder 线程 / FgThread）
   └─ enqueueNotificationInternal()        // 校验、建记录：尽量轻
        └─ mRankingHandler.requestSort()   // 抛到 RankingHandler 工作线程
             └─ RankingHelper.sort()       // 一组 Extractor 算 globalSortKey
                  └─ 回到主路径：buzzBeepBlink / 下发监听者
```

为什么重要：车机 ROM 里如果你在 Extractor 里加了重逻辑（比如查联系人星级要走 CP），就会把 RankingHandler 线程拖慢，表现为"通知延迟出现"。**自定义排序逻辑务必别在 RankingHandler 上做阻塞 IO**。

### 2.3 监听者体系：NotificationListenerService 的注册与权限

NMS 通过 `NotificationListeners`（一个 `ManagedServices` 子类）管理所有"想看通知的客户端"。SystemUI 只是其中一个最特殊的监听者。

- **注册**：App 实现 `NotificationListenerService` 并声明 `BIND_NOTIFICATION_LISTENER_SERVICE` 权限 + 对应的 `service` 元数据；用户在"通知访问权"里手动授予后，NMS 才把该 listener 加入活跃集合。
- **下发**：NMS 对每个 listener 单独调用 `onNotificationPosted / onNotificationRemoved / onNotificationRankingUpdate`。注意是**逐 listener 串行分发**，某个 listener（含 SystemUI）的 `onNotificationPosted` 卡住，会影响其他监听者的实时性。
- **权限边界**：listener 拿到的 `StatusBarNotification` 默认会**脱敏包名**等（`dumpsys` 里叫 redaction）；只有持有 `ACCESS_NOTIFICATIONS` 或系统 listener 才能看到原始信息。`dumpsys notification --noredact` 是绕过脱敏看内部的调试开关（见第 13 章）。

「Android 视角」：SystemUI 既是通知的"展示者"，也是 NMS 的一个普通 listener。所以"通知不显示"要先分清是 **NMS 没下发（listener 没收到 onNotificationPosted）**，还是 **SystemUI 收到了但被自己过滤/打断判定挡掉**（见 18 篇第 11.3 节）。

---

## 3. Channel 体系（Android 8.0 的转折点）

Android 8.0（API 26）是通知系统最大的分水岭：**从"每条通知自带优先级"变成"通知必须挂在通道（Channel）上，通道决定优先级与行为"**。这是把"打扰权"从 App 手里夺回给用户的关键设计。

### 3.1 创建与更新规则

```java
// App 侧（只写一句，详细见 androidApp 系列）
NotificationChannel ch = new NotificationChannel(
        "chat", "聊天消息", NotificationManager.IMPORTANCE_HIGH);
ch.setDescription("好友发来的聊天");
ch.enableVibration(true);
getSystemService(NotificationManager.class).createNotificationChannel(ch);
```

通道一旦创建，App 还能改，但有铁律：

> **importance（以及用户可锁定的字段）只能降、不能升；用户改过的字段 App 永远改不回。**

底层机制是 `NotificationChannel` 上的 **"用户锁定字段"**（`getUserLockedFields()`）：只要用户手动调过某通道的 importance/静音/震动，那个字段就被打上锁，App 后续 `createNotificationChannel` 对该字段的"升档"会被 NMS 直接忽略。这点第 4 章再展开。

通道更新是**幂等**的：重复 `createNotificationChannel` 同一 id 不会新建，而是合并更新未锁定的字段。

### 3.2 通道的服务端存储

通道不是存在 App 自己的目录里，而是 NMS 代为持久化：

- 存储位置：`/data/system/notification_policy.xml`（不同版本路径可能微调），由 `RankingHelper` 在内存中以 `(pkg, userId) → List<NotificationChannel>` 的表维护，并落盘。
- 因此**清除 NMS 数据 / 恢复出厂会丢通道配置**；用户改过的通道偏好也跟着丢。
- 车机 ROM 调试时常遇到"通道 importance 怎么改都不生效"——优先 `dumpsys notification` 看通道实际值，而不是看 App 代码（见第 13 章）。

### 3.3 老 App 的兼容通道（"已废弃的 legacy 通道"）

**对于 `targetSdk < O`（8.0）的存量 App**，它们根本没有通道概念。NMS 不会让它们崩溃，而是**自动给它们建一个系统默认通道**，常被称为"miscellaneous（杂项）"通道，id 类似 `DEFAULT_CHANNEL_ID = "miscellaneous"`。

- 这类 App 在通知上设置的旧式 `priority`（PRIORITY_HIGH 等）会被 NMS **映射**到这个默认通道的 importance。
- 用户看到的是"杂项"分组，所有老 App 的通知挤在一起，无法单独精细控制——这正是 8.0 引入通道要解决的问题。
- 在 dumpsys 里它通常显示为 importance 由系统派生、且 `lockedImportance = false`（因为老 App 没法锁）。

「Android 视角」：车机上如果有个 system app 一直用老 targetSdk 发通知，你会发现它的通道叫"miscellaneous"，且重要性被统管——想单独改它，要么升 targetSdk 显式建通道，要么在 ROM 层给这个 pkg 预置通道（见第 9 章 visibility 之外的通道默认值定制）。

---

## 4. importance 取值全表与用户改写

### 4.1 importance 取值全表（标准 5 档 + 2 个内部哨兵）

用户常说"七档 importance"，严格讲 **用户可设的是 5 档**（0~4）；另有 2 个内部哨兵值，加起来 7 个常量。下表一次列清：

| 常量 | 值 | 类别 | 展示与行为差异 |
|---|---|---|---|
| `IMPORTANCE_UNSPECIFIED` | `-1000` | 内部哨兵 | "未指定"，通道创建前的初始态，NMS 会按规则兜底（通常落到 DEFAULT） |
| `IMPORTANCE_NONE` | `0` | 标准档 | **完全静默 + 不进通知栏**（等价"关闭该通道"）。不会响、不震、不显示、不 heads-up |
| `IMPORTANCE_MIN` | `1` | 标准档 | 进通知栏但**不响不震**，折叠在底部，无 heads-up |
| `IMPORTANCE_LOW` | `2` | 标准档 | 进通知栏、**不响不震**，但比 MIN 更"正式"（如公开状态） |
| `IMPORTANCE_DEFAULT` | `3` | 标准档 | 进通知栏、**响一声 + 不震（默认）**，无 heads-up |
| `IMPORTANCE_HIGH` | `4` | 标准档 | 进通知栏、**响 + 震 + 可 heads-up**（顶部悬浮），最强打扰档 |
| （历史 `PRIORITY_*`） | `-2~-2` | 旧式(已废弃) | 8.0 前的 `Notification.priority`，现已由通道 importance 取代（见 3.3 映射） |

一句话记忆：**NONE 是关、MIN/LOW 是哑、DEFAULT 是响一声、HIGH 是又响又弹**。

### 4.2 importance 与用户改写的关系（最常被踩的坑）

这是第 3.1 节铁律的展开。把"谁能改 importance"画成一张权限关系图：

```text
            App 想调高 importance
                    │
                    ▼
   通道该字段被用户锁定了吗？（getUserLockedFields 含 IMPORTANCE）
        ├─ 是 → NMS 忽略 App 的升档（保持用户设定）★核心坑
        └─ 否 → 采用 App 设定（但只能降不能升的规则仍然适用）
                    │
                    ▼
   用户随时可在设置里手动调低 / 调高 → 调过即锁定 → App 再也改不回
```

**典型翻车**：App 上线时把通道设成 HIGH 想强提醒，用户嫌吵手动降到 DEFAULT。之后 App 发版想"改回 HIGH"——**做不到**，因为 importance 已被用户锁定。App 能做的只有引导用户去设置里手动调，或重新建一个**新 id** 的通道（用户没动过的新通道未被锁定）。

「Android 视角」：车机系统 App 想保证"我的通知一定 HIGH"的常见做法是：**预置通道（在 NMS 初始化/首次启动时由系统侧 `createNotificationChannel` 写死 importance）**，且不让用户轻易改——但要注意 UX 与隐私合规，别让后台 App 随意 HIGH 打扰司机。

---

（第 1~4 章完，后续第 5~10 章见下一部分）

---

## 5. notify() 的裁决链

`NotificationManager.notify()` 一路 Binder 到 NMS 的 `enqueueNotificationInternal()`，这是通知真正的"安检通道"。把每个检查点画出来，定位"为什么没出来"就靠它。

### 5.1 前置校验顺序（从外到内）

```text
enqueueNotificationInternal()
  ├─① 调用方身份：Binder.getCallingUid()/Pid() 取真实 uid，防止冒充
  ├─② 包量上限：单包通知数 > MAX_PACKAGE_NOTIFICATIONS(=50) → 拒绝/抛异常
  ├─③ 权限闸门：
  │     · Android 13+ 必须持有运行时权限 POST_NOTIFICATIONS（见 5.2）
  │     · AppOps OP_POST_NOTIFICATIONS 未被用户关
  │     · 通道本身 enabled（用户没在设置里关掉该通道）
  ├─④ 冻结/受限：
  │     · 包处于 suspended 状态 → 直接丢弃
  │     · 后台受限（standby bucket 极低 / 被电池优化）会间接延迟投递（见 16 篇）
  ├─⑤ 通道存在性：targetSdk >= O 且通道不存在 → 该通知被丢弃（部分版本直接抛异常）
  ├─⑥ 前台服务联动检查（见 5.3、第 8 章）
  └─⑦ 入队建记录 → 进入第 6 章 Ranking
```

任何一个关口失败，通知都不会走到"下发监听者"，`dumpsys notification` 里也看不到这条 record——这是和"SystemUI 收到但没画"最关键的区分点。

### 5.2 POST_NOTIFICATIONS（Android 13 的硬门槛）

`android.permission.POST_NOTIFICATIONS` 是 **Android 13（API 33）新增的运行时权限**。在此之前通知权限是"装好就有"，13 起变成需要用户授权：

- 未授权时，`enqueueNotificationInternal` 直接拦截，连通道都不看。
- **但有两个例外不拦**：① 媒体会话类通知（MediaSession 驱动）；② 前台服务通知（见第 8 章）——它们走特殊豁免，所以"13 收不到普通通知但前台服务通知还在"是正常现象。
- 权限状态查法：`adb shell dumpsys package <pkg> | grep -i POST_NOTIFICATIONS`，或在设置里看"通知访问"。

「Android 视角」：车机系统 App 如果在 13 上突然"没通知了"，第一反应不是查通道，而是查这个运行时权限有没有弹窗授予过。OEM 预装可走 `default-permissions` 白名单自动授予。

### 5.3 雷区：前台服务通知与 FSI 权限的联动

前台服务（`startForeground`）的通知有一套独立规则，和"普通 notify"不是一条路（详情见 `07_Service机制详解` 第 8 节）。NMS 这侧的联动检查是：

- 带 `FLAG_FOREGROUND_SERVICE` 的通知**必须**真的来自一个正在前台运行的服务；否则 NMS 会剥离前台属性或记异常（AMS 侧还会因为没及时 `startForeground` 而 ANR/弹"正在运行"提示）。
- 带 **full-screen intent（全屏意图）** 的高优先通知，要过 `USE_FULL_SCREEN_INTENT` 权限（Android 14 收紧，见第 9 章）。

所以"前台服务通知不显示"要**同时看 AMS（服务有没有真的进 foreground）和 NMS（通道/权限）**——这是跨服务的经典坑。

### 5.4 一个被拦的典型日志片段

裁决链失败时 NMS 通常会打印带原因的行，是最快的定位线索：

```bash
# 常见拦截日志，直接点出原因：
#  · 通道不存在：
NotificationService: ... channel 'xxx' doesn't exist, skipping notification
#  · 被 DND 拦截（仅静音）：
NotificationService: ... suppressed by DND (suppressedEffects=...)
#  · 权限/AppOps 关：
NotificationService: ... not posting, blocked by AppOps / POST_NOTIFICATIONS
#  · 包量超限：
NotificationService: ... package has too many active notifications (50)
```

把这些行和 `dumpsys notification` 对看，基本能在一条日志里确认卡在 5.1 的哪一关。

---

## 6. Ranking：一组排序调整器

排在第 5 章之后，是因为 Ranking 发生在"记录建好、准备下发"之前。NMS 不是用某个单一字段排序，而是用**一组可插拔的"排序调整器"（NotificationSignalExtractor 实现）**各自抽一个信号，拼成最终的 `globalSortKey` 字符串，再按字典序排。

### 6.1 架构：Extractor 链

```text
NotificationRecord 建好
   │ mRankingHandler 抛到工作线程
   ▼
RankingHelper.sort(records)
   └─ 遍历每个 NotificationSignalExtractor：
        · ImportanceExtractor    → 抽 importance（基础权重，最重要）
        · ConversationExtractor  → 抽是否对话(conv)、对话是否重要
        · PeopleExtractor        → 抽联系人星级（starred contacts 加权）
        · VisibilityExtractor    → 抽锁屏可见性（影响是否参与排序展示）
        · PriorityExtractor      → 抽旧式 priority（兼容）
        · ZenModeExtractor       → 抽 DND 下是否被压制
        · …（各 ROM 可加自定义 Extractor）
   │ 每个 Extractor 往 record 写一个或多个"排序字段"
   ▼
算 globalSortKey（一串拼接的排序键）
   └─ 按 globalSortKey 字典序 → 决定通知列表里的上下顺序
```

设计要点：**Extractor 是无状态的"信号采集器"，新增排序维度只需加一个 Extractor**，不用动主排序逻辑。这是 NMS 里扩展性最好的一块——车机想"驾驶中把导航通知永远置顶"，最干净的做法就是加一个自定义 Extractor（注意别在 RankingHandler 上做阻塞 IO，见 2.2）。

### 6.2 决定顺序的信号集合

按权重从硬到软：

| 信号 | 来源 | 对排序的影响 |
|---|---|---|
| importance | 通道（用户可锁） | 最硬的基础权重：HIGH 永远压 DEFAULT 压 MIN |
| `isRecentlyIntrusive` | NMS 统计 | 最近刚打扰过用户的通知，适当降权，避免连环轰炸 |
| 人工排序 / 置顶 | 用户拖拽、通知助手 | 用户手动置顶的通道/对话权重最高 |
| 联系人星级 | `PeopleExtractor` 查 Contacts | starred 联系人发来的对话类通知加权 |
| 对话(conv)标记 | `ConversationExtractor` | 标记为重要对话的优先于普通通知 |
| 时间 / postTime | 记录自带 | 同权重下，新的在上 |

### 6.3 用 dumpsys 读 rank 的方法

排序结果可以直接在 `dumpsys` 里看到，关键是加 `--noredact` 看原始数据：

```bash
# 看全部记录的 rank（带原始包名，不被脱敏）
adb shell dumpsys notification --noredact | grep -A3 "NotificationRecord"

# 只看某个包的通道与 importance
adb shell dumpsys notification --noredact | grep -A20 "pkg=com.xxx"

# 看 globalSortKey（排序键，字典序即展示序）
adb shell dumpsys notification --noredact | grep -i "sortKey\|rank"
```

`globalSortKey` 形如 `0|4|...|...` 的分段字符串，第一段通常是"是否在锁屏/私密"相关，第二段是 importance 衍生权重——比对两条通知的 sortKey 就能确认"为什么它排在我上面"。

---

## 7. 声音 / 震动 / 灯与免打扰 DND

通知"响了之后谁负责"——真正执行响铃/震动/闪光的是 NMS 的 `buzzBeepBlinkLocked()`（名字就是 buzz+beep+blink 三件套）。它综合通道配置、DND、锁屏状态，决定这一条通知到底出不出声。

### 7.1 默认声与通道 sound 的解析

- 通道创建时可设 `setSound(Uri, AudioAttributes)`；不设为 `null` 时走系统默认通知声（来自 `AudioAttributes.USAGE_NOTIFICATION`）。
- `IMPORTANCE_NONE/MIN/LOW` 的通道 sound 会被忽略（不响）；只有 `DEFAULT` 响一声、`HIGH` 响且可能叠加震动。
- 声音最终由 `NotificationManagerService` 通过 `AudioManager` / 通知声音通路播放，受当前音量流（`STREAM_NOTIFICATION`）和 `AudioPolicy` 影响（音频细节见 `21_Audio机制详解`）。

### 7.2 VibrationEffect 与 Power 的衔接

通道 `enableVibration(true)` + 至少 DEFAULT/HIGH 时，NMS 用 `VibratorManager` 播放一个 `VibrationEffect`：

- 现代 Android 用 `VibrationEffect.createWaveform(...)` 或预置的默认通知震动；老式 `Notification.vibrate` 长数组已废弃。
- **震动要不要拿 WakeLock？** 要。NMS 在处理"该唤醒屏幕/该出声出震"的通知时，会**短暂持有一个 PARTIAL WakeLock**，保证设备在休眠边缘时这次 buzz/beep 不会被 autosuspend 截断（这块的 WakeLock 语义见 `15_Power机制详解` 第 4 节）。震动/响铃结束后释放。所以车机"灭屏来通知偶尔不震"往往和电源态/Doze 有关，而不只是振动器坏了。

### 7.3 锁屏时的铃声策略

锁屏与否不改变"是否响"，但改变"响的方式"：

- 锁屏 + DND 可能静音（见 7.4）。
- 锁屏下若用户在设置里关了"锁屏通知声"，即使通道 HIGH 也可能只震动不响——这个开关在 `NotificationManager.Policy` 里。
- 全屏 intent 在锁屏才会真正拉起界面（见第 9 章）。

### 7.4 免打扰 DND 的三层（filter / condition / component）与 ZenModeHelper

DND（勿扰）是通知被"静音或拦截"的第二大原因（第一是权限/通道关）。NMS 用一个 `ZenModeHelper` 当中枢，拆成三层理解最清楚：

```text
ZenModeHelper（component：状态机 + 配置中枢）
   ├─ ZenModeConfig         → 用户/策略配置的"规则集"（哪些渠道/联系人可破免打扰）
   ├─ ZenModeConditions     → condition：自动规则的触发条件（定时、日历、驾驶模式…）
   └─ ZenModeFiltering      → filter：对每条通知做"该不该被拦截/静音"的裁决
```

| 层 | 角色 | 你调试时看什么 |
|---|---|---|
| **component（ZenModeHelper）** | 维护当前 Zen 模式开关与配置，是总控 | `dumpsys notification | grep -i zen` 看当前是否处于 DND |
| **condition（ZenModeConditions）** | 决定"什么时候自动进 DND"（如车机驾驶模式自动免打扰） | 自动规则是否误触发（车机常见） |
| **filter（ZenModeFiltering）** | 对到达的通知逐条判断：拦截 / 仅静音 / 透传 | 某条通知被 DND 吃掉还是只静音 |

「Android 视角」：车机**驾驶模式（Driving UX Restriction）经常和 ZenMode 联动**——一挂挡就进 DND，把非导航/非通话通知全静音。排查"开车时通知不响"先 `dumpsys notification | grep -i zen` 确认是不是驾驶 DND 在起作用。

### 7.5 NotificationManager.Policy 与 SUPPRESSED_EFFECT

DND 不是简单的"全静音开关"，而是用 `NotificationManager.Policy` 描述"哪些类别可破免打扰、被压制时表现为何"。两个关键维度：

- **PRIORITY_CATEGORY_\***（允许破 DND 的类别）：如 `PRIORITY_CATEGORY_ALARMS`（闹钟）、`PRIORITY_CATEGORY_MESSAGES`（消息）、`PRIORITY_CATEGORY_CALLS`（来电）、`PRIORITY_CATEGORY_CONVERSATIONS`（对话）、`PRIORITY_CATEGORY_REPEAT_CALLERS`（重复来电）。
- **SUPPRESSED_EFFECT_\***（被压制时的表现）：`SUPPRESSED_EFFECT_SCREEN_OFF`（灭屏时压制）、`SUPPRESSED_EFFECT_SCREEN_ON`（亮屏时压制）、`SUPPRESSED_EFFECT_FULL_SCREEN_INTENT`（压制全屏意图）、`SUPPRESSED_EFFECT_PEEK`（压制 heads-up 弹出）、`SUPPRESSED_EFFECT_STATUS_BAR`（压制状态栏图标）、`SUPPRESSED_EFFECT_AMBIENT`（压制 AOD/息屏显示）、`SUPPRESSED_EFFECT_NOTIFICATION_LIST`（压制通知列表）。

理解这组常量能解释很多"怪现象"：比如同样是 DND，**亮屏不弹 heads-up 但灭屏仍响**（就是 `SUPPRESSED_EFFECT_PEEK` 只挂在 `SCREEN_ON` 上）。车机定制 DND 时，改的就是这套 Policy 与 suppressed effect 的组合。

---

## 8. 前台服务通知的特殊规则

前台服务（FGS）通知是通知系统里最"特权"的一类：它**不受 POST_NOTIFICATIONS 拦截**（13+ 也豁免），但有一套独有的展示节奏。

### 8.1 延迟展示（Android 12+）

**Android 12（API 31）起，前台服务通知默认延迟 10 秒才出现**，目的是减少"一启动服务就弹通知"的视觉骚扰（尤其短时服务）。

- 延迟由系统控制，不是 App 能随意关的。
- **立即显示的例外**：系统对"需要让用户立刻知晓"的前台服务类型（如媒体播放 `mediaPlayback`、通话 `phoneCall` 等 `foregroundServiceType`）跳过延迟，立即展示。
- 关于 `FOREGROUND_SERVICE_IMMEDIATE`：**Android 没有公开的 App 侧 flag 叫这个名字**。是否立即显示由"前台服务类型 + 系统策略"决定，而不是 App 在 Notification 上打个旗。想深挖类型与豁免，见 `07_Service机制详解` 第 8 节（foregroundServiceType 家族）。

### 8.2 user 归属与展示

前台服务通知同样带 `UserHandle`：哪个 user 的服务，就在哪个 user 下展示（多用户隔离见第 11 章）。车机里"主驾用户跑的前台服务通知不会跑到副屏/乘客用户"就是这条规则在起作用。

### 8.3 和其它通知的差异速查

| 维度 | 普通通知 | 前台服务通知 |
|---|---|---|
| 13+ POST_NOTIFICATIONS | 必须授权 | **豁免** |
| 可关闭通道 | 用户可关 | 通道可被关，但服务仍要求展示（冲突时系统仍尽量展示，否则 AMS 侧会报"服务未前台化"） |
| 延迟展示 | 无 | Android 12+ 默认延迟 10s（部分类型立即） |
| 必须常驻 | 否 | 是（服务结束后必须 cancel，否则系统补刀） |

---

## 9. 通知的可见性与隐私

通知内容可能含敏感信息（短信内容、OTP、聊天）。锁屏上露多少，由"通道/通知的 visibility + 用户锁屏设置 + redaction 规则"共同决定。NMS 在下发前就把"该不该打码"算好，SystemUI 只是照着画。

### 9.1 锁屏可见性三档

| 常量 | 值 | 含义 |
|---|---|---|
| `VISIBILITY_PUBLIC` | `1` | 锁屏完整显示内容 |
| `VISIBILITY_PRIVATE` | `0` | 锁屏默认只显示"有通知"，**内容被打码（redacted）**；用户解锁才看全。可配 `publicVersion` 给锁屏看一个安全版 |
| `VISIBILITY_SECRET` | `-1` | 锁屏连"有通知"都不显示（最私密，如后台计时器） |

### 9.2 redaction（打码）的判定

redaction 不是 App 单方面说了算，是两层取交集：

```text
锁屏是否显示内容 =
   通知自身 visibility（PUBLIC 才允许露）
     AND
   用户级锁屏通知设置（用户关了"锁屏显示内容" → 一律 PRIVATE 化）
     ↓
   结果：PUBLIC 且用户允许 → 露全；否则 → 显示 redacted（或隐藏）
```

- 想给锁屏一个"安全版"内容，用 `Notification.Builder.setPublicVersion(notif)` 提供打码后的替代视图。
- `dumpsys` 里 redaction 体现在 sbn 是否被脱敏——`--noredact` 才看全（见第 13 章）。

### 9.3 悬浮 / 全屏 intent（full-screen intent）的裁决

`fullScreenIntent`（FSI）是最高打扰形态：直接拉起一个全屏 Activity（来电、闹钟、计时结束）。它的裁决在 Android 12 和 14 两道闸：

- **Android 12**：FSI 只有在**设备锁屏/灭屏**时才真正全屏拉起；用户正在用手机时 FSI 会被降级成普通 heads-up（避免你正打字突然被来电界面覆盖）。
- **Android 14（API 34）收紧**：新增权限 `USE_FULL_SCREEN_INTENT`，虽默认自动授予但**用户可撤回**；目标 34+ 的 App 必须声明它，否则 FSI 不生效。Google Play 政策也限制滥用 FSI。

「Android 视角」：车机来电/导航全屏页若"突然不弹了"，优先确认 targetSdk 是否到了 34（要 `USE_FULL_SCREEN_INTENT`）以及当前是不是"用户在操作"被降级成 heads-up（第 11 章 / 18 篇第 11 节）。

### 9.4 一个 redaction 的最小示例

```java
Notification publicNotif = new Notification.Builder(ctx, "chat")
        .setContentTitle("新消息")
        .setContentText("有 1 条新消息")          // 锁屏只看这一句
        .build();

Notification notif = new Notification.Builder(ctx, "chat")
        .setContentTitle("张三")
        .setContentText("今晚加班吗？")            // 私密内容
        .setVisibility(Notification.VISIBILITY_PRIVATE)
        .setPublicVersion(publicNotif)            // ← 锁屏打码后显示的安全版
        .build();
```

- 锁屏（且用户允许显示通知但隐藏内容）时，SystemUI 用 `publicVersion` 渲染，私密正文不露。
- 若没设 `publicVersion`，PRIVATE 下锁屏就只显示"1 条通知"这类占位。

---

## 10. 通知的去重与合并

通知不是"每调一次 notify 就多条"。NMS 用 `(id, tag)` + 分组机制做去重与合并，理解这部分能避免"通知越积越多"和"取消不掉"两类问题。

### 10.1 同 key 的 update 语义

- 身份四元组：`(userId, pkg, tag, id)`。App **再次 notify 同一 `(tag, id)`** → 不是新建，而是**更新**已有记录（内容、extras 替换，key 的 postTime 变新但逻辑条目不变）。
- `Notification.key`（带 postTime）只在"历史实例"层面唯一；**更新/取消靠的是 `(id, tag)`**（见 2.1）。
- 想强制当新通知（不覆盖旧的），用不同 `id`，或带 `FLAG_ONLY_ALERT_ONCE`（只在首次提示，更新时不重复响）。

### 10.2 分组 summary 的服务端逻辑

`Notification.Builder.setGroup(groupKey)` + `setGroupSummary(true)` 把一组通知聚成一个"摘要"：

- 普通成员通知 + 一个 summary 通知（通常 `setGroupSummary(true)`）组成一组。
- NMS / SystemUI 协作：成员聚到摘要下；**如果只有成员没有 summary，NMS 会自动生成一个 autogroup summary**（这就是"为什么我没建 summary 却出现一个分组头"）。
- 取消分组：cancel 掉 summary 或所有成员，autogroup 摘要会被 NMS 自动回收。

### 10.3 自动取消与撤销链（"延迟移除"）

- `FLAG_AUTO_CANCEL`：用户点开通知后自动 cancel。
- `cancel(id/tag)` / `cancelAll()`：App 主动撤销，NMS 走"延迟移除"流程——先标记 removed、通知各 listener `onNotificationRemoved`，再在合适时机真正从记录表摘除（避免正在分发的 listener 并发读到空）。
- 前台服务通知：服务 `stopForeground(true)` / 销毁时，AMS 会要求 NMS 移除对应通知；若 App 不主动 cancel，系统会"补刀"移除（见第 8 章）。
- 跨进程注意：listener 收到的 `onNotificationRemoved` 是**异步**的，不能假设 cancel 后立即从 SystemUI 列表消失（UI 层还有动画/收尾）。

### 10.4 常用 Notification flags 速查

这些标记直接影响 NMS 的去重/合并/提示行为，车机调试时常要对照：

| Flag | 作用 |
|---|---|
| `FLAG_AUTO_CANCEL` | 用户点开/清除后自动 cancel |
| `FLAG_ONGOING_EVENT` | 标记为进行中（如 FGS、导航），不可手动划掉 |
| `FLAG_NO_CLEAR` | 不在"全部清除"时被清掉 |
| `FLAG_FOREGROUND_SERVICE` | 标识这是前台服务通知（联动 AMS，见第 8 章） |
| `FLAG_ONLY_ALERT_ONCE` | 同 `(id,tag)` 更新时只在首次提示，后续静默更新 |
| `FLAG_GROUP_SUMMARY` | 标记该通知是分组摘要（见 10.2） |
| `FLAG_BUBBLE` | 允许以气泡（Bubble）形式展示 |

「Android 视角」：`FLAG_FOREGROUND_SERVICE` 通知若对应服务已死但没 cancel，系统会补刀移除；`FLAG_ONLY_ALERT_ONCE` 是"频繁更新进度条又不希望每次都响"的标配（如下载进度）。

（第 5~10 章完，后续第 11~16 章见下一部分）

---

## 11. 跨用户与多用户

通知从出生起就带 `UserHandle`。这是多用户/工作资料（work profile）场景不串台的基础。

### 11.1 通知的 user 归属

- 每条 `StatusBarNotification` 都带 `userId`；NMS 内部所有记录、通道、统计都按 `(pkg, userId)` 维度隔离。
- 一个包在 user 0 和 user 10 下是**两套独立通道、两套通知**，互不干扰。
- 下发时 NMS 只把"属于当前活跃 user"的通知推给该 user 的 SystemUI 实例（多用户下每个 user 有自己的 SystemUI 进程/上下文）。

### 11.2 work profile 的注入与 merged（一句带过）

工作资料的通知会通过 `CrossProfileNotifications` 机制"注入"到个人资料侧显示（用户可在设置里开"在两处显示工作通知"），底层由 NMS 跨 profile 桥接；细节不在本篇展开。

### 11.3 车机按 user 的通知隔离（指 19）

车机常见"主驾用户 / 乘客用户 / 系统用户"多用户模型：某用户跑的前台服务通知、系统通知都只在对应 user 下展示，不会串到别的用户屏（多用户隔离底层见 `19_多用户机制详解`，车机多屏展示见 `18_SystemUI机制详解` 第 16 节、`23_多屏与多窗口机制详解`）。排查"副屏出现本不该出现的通知"优先确认它到底属于哪个 user。

---

## 12. SystemUI 衔接小节

本篇聚焦 NMS；展示端只画一条衔接线，指到 `18_SystemUI机制详解` 第 10、11 节。

### 12.1 两条桥，别混为一谈

```text
NMS 侧（本篇）
  ├─ 通知"内容/排序/打断判定" → 走 NotificationListeners
  │     onNotificationPosted / Removed / RankingUpdate
  │           │
  │           ▼
  │     SystemUI NotificationListener
  │           → NotifCollection → NotifPipeline（排序/过滤/分组/打断）
  │           → 渲染 / heads-up（详见 18 篇第 10、11 节）
  │
  └─ 状态栏"图标/计数"信号 → 走 IStatusBar / CommandQueue（setIcon 等）
                │  （这是图标层面，不是通知内容层面；详见 18 篇第 8 节）
                ▼
          SystemUI 状态栏图标区
```

要点：**通知内容走的是 `NotificationListenerService` 回调桥，不是 `IStatusBar`**。`IStatusBar`/`CommandQueue` 只负责状态栏图标（比如"有几个通知"的角标信号），和"哪条通知长什么样"是两回事。所以定位"通知列表里没有"看 listener 桥（指向 18 篇 10），定位"状态栏图标数不对"才看 `IStatusBar` 桥（指向 18 篇 8）。

### 12.2 指路

- 想知道"通知卡片怎么渲染 / heads-up 为什么弹不弹" → `18_SystemUI机制详解` 第 10、11 节（含决策链与逐条对照表）。
- 想知道"锁屏状态机怎么影响通知" → 18 篇第 12 节（Keyguard）。
- 本篇不重复这些，免得和姊妹篇打架。

---

## 13. 调试工具箱

工具分三层：发/授权、看内部状态、抓日志。按"先确认 NMS 有没有记录，再确认 SystemUI 怎么判"的顺序用。

### 13.1 `adb shell cmd notification` 子命令组

NMS 的 shell 命令以**授权与查询**为主（不是用来发通知的）。常用：

```bash
# 给某个包在 DND 下"允许/禁止"破免打扰（排查 DND 拦截最常用）
adb shell cmd notification allow_dnd <pkg> [--user USER_ID]
adb shell cmd notification disallow_dnd <pkg> [--user USER_ID]

# 重置某包的通知设置（通道/锁定的 importance 全清回默认）
adb shell cmd notification reset <pkg> [--user USER_ID]

# 列出/查看当前通知记录
adb shell cmd notification list
adb shell cmd notification get <key>

# 帮助
adb shell cmd notification help
```

「Android 视角」：**想"模拟发一条通知"没有标准的 `post` 子命令**——最干净的做法是写个最小 App 用 `NotificationManager.notify()`，或借助 SystemUI 自带的测试入口（部分 ROM 有 `cmd statusbar` 相关调试命令）。不要假定 NMS 有 `post` 子命令，避免排查走偏。

### 13.2 `dumpsys notification` 逐段读法

这是 NMS 调试的"主武器"。重点看三段：

```bash
# 1) record 段：当前所有活着的通知记录（带 redaction 脱敏）
adb shell dumpsys notification | grep -A4 "NotificationRecord"

# 2) channel 段：每个包的通道与 importance（--noredact 看原始包名）
adb shell dumpsys notification --noredact | grep -B1 -A6 "NotificationChannel"

# 3) ranking 段：排序键 / Zen(DND) 状态
adb shell dumpsys notification --noredact | grep -i "sortKey\|zen\|rank"
```

| 段 | 看什么 | 能回答的问题 |
|---|---|---|
| `NotificationRecord` | 有没有这条通知、flags、key | "NMS 到底收没收到" |
| `NotificationChannel` | importance、sound、vibration、locked | "为什么没声/没震/被降权" |
| `zen` / `ZenMode` | 当前是否 DND、哪条规则触发 | "是不是被免打扰吃了" |
| `sortKey` | 排序键 | "为什么排别人下面" |

### 13.3 logcat 关键行

```bash
# NMS 主线
adb logcat -s NotificationService:* NotificationManager:* 

# 通道/权限相关
adb logcat | grep -i "NotificationChannel\|POST_NOTIFICATIONS\|blocked"

# 联动 SystemUI（确认数据有没有到展示端）
adb logcat -s SystemUI:*
```

常见有用行：NMS 打印 `Suppressing notification` / `blocked` / `channel ... not found` / `DND` 等，基本直接点出拦截原因。

### 13.4 通知不显示的分层二分法

按这条链路从外到内二分，不要上来就改代码：

```text
App 调用 notify 了吗？
   └─ 否 → App 侧问题（PendingIntent/没调）
        ▼ 是
NMS 里有 NotificationRecord 吗？（dumpsys notification）
   └─ 否 → 卡在 NMS 裁决（权限/通道/冻结/DND，见第 5、7 章）
        ▼ 是
channel importance / DND 正常吗？（dumpsys channel/zen）
   └─ 异常 → 卡在响铃/拦截（第 7 章）
        ▼ 正常
SystemUI 收到 onNotificationPosted 了吗？（logcat SystemUI）
   └─ 否 → listener 桥问题（通知访问权/脱敏，见 2.3）
        ▼ 是
SystemUI 过滤/打断判定放行了吗？（18 篇第 11 节）
   └─ 否 → UI 侧 heads-up/过滤问题（18 篇 10/11）
```

---

## 14. 常见问题排查表

| 现象 | 原因 | 章节 | 第一命令 |
|---|---|---|---|
| 13+ 普通通知收不到 | `POST_NOTIFICATIONS` 未授权 | 5.2 | `adb shell dumpsys package <pkg> \| grep -i POST_NOTIFICATIONS` |
| channel importance 被用户降了改不回 | 用户锁定字段，App 升档被忽略 | 4.2 | `adb shell dumpsys notification --noredact \| grep -i importance` |
| 通知无声音 | 通道 sound 失效或 importance 太低 | 7.1 | `adb shell dumpsys notification --noredact \| grep -i sound` |
| 通知被静音但不显示拦截 | DND（Zen）仅静音非拦截 | 7.4 | `adb shell dumpsys notification \| grep -i zen` |
| 锁屏完全不显示通知 | visibility=SECRET 或用户关锁屏通知 | 9.1 | 设置→锁屏通知；`dumpsys notification` 看 visibility |
| 锁屏内容被打码 | PRIVATE + 用户设置，无 publicVersion | 9.2 | 看 `setPublicVersion` / 锁屏显示内容开关 |
| heads-up 不弹 | importance 不够高 / 当前前台 App / DND | 18 篇 11 | `dumpsys notification --noredact` 看 channel（指 18 篇） |
| 前台服务通知不显示 | FSN 延迟 10s / 未真正前台化 / FSI | 8 | `dumpsys notification` 看记录 + 查 AMS（07 篇 8） |
| 全屏 intent 不弹 | 14+ 需 `USE_FULL_SCREEN_INTENT` / 被降级 heads-up | 9.3 | `dumpsys notification` 看 fullScreenIntent |
| 通知点击无反应 | `PendingIntent` 失效 / 用了 `FLAG_IMMUTABLE` 却改 extra | 10.3/18.10.5 | `logcat` 看 `PendingIntent` cancel / `Canceled` |
| 多用户串台 | 通知归属 user 弄错 | 11 | `adb shell pm list users` |
| 取消不掉 / 残留 | cancel 异步 + autogroup 摘要未清 | 10.3 | `dumpsys notification` 看 removed 状态 |
| 通知延迟才出现 | RankingHandler 卡 / FSN 延迟 | 2.2/8.1 | 看 RankingHandler 线程栈 |
| 老 App 通知挤在"杂项" | legacy 默认通道（targetSdk<O） | 3.3 | `dumpsys notification` 看 miscellaneous |
| 设了 importance 不生效 | targetSdk 改动 / 系统预置通道覆盖 | 3.2 | `dumpsys notification` 看实际 channel 值 |
| 通知数量爆了被拒 | 超过 `MAX_PACKAGE_NOTIFICATIONS(=50)` | 5.1 | `dumpsys notification` 看该包 count |
| listener 收不到通知 | 通知访问权未授予 / 被脱敏 | 2.3 | 设置→通知访问权；`dumpsys notification --noredact` |
| 驾驶时通知不响 | 车机驾驶模式联动 Zen(DND) | 7.4 | `dumpsys notification \| grep -i zen` |

---

## 15. 读源码路线

四条必看调用链，按兴趣挑：

### 链路 1：notify 全链 → SystemUI 桥
`NotificationManager.notify()` → `INotificationManager.enqueueNotificationWithTag()` → `NotificationManagerService.enqueueNotificationInternal()` → 校验（5 章）→ `NotificationRecord` 建记录 → `RankingHandler` 排序（6 章）→ `buzzBeepBlinkLocked()`（7 章）→ `NotificationListeners.notifyPostedLocked()` → SystemUI `NotificationListener`（12 章桥 → 18 篇 10）。

### 链路 2：Ranking 重算
`RankingHelper.sort()` → 遍历 `NotificationSignalExtractor` 各实现抽信号 → 写 `NotificationRecord` 排序字段 → 算 `globalSortKey` → 按字典序排。想加自定义排序维度，从这里入手（注意 2.2 的线程陷阱）。

### 链路 3：Channel 创建与存储
`NotificationManager.createNotificationChannel()` → `INotificationManager.createNotificationChannels()` → `RankingHelper.createNotificationChannel()` → 写内存 `(pkg,userId)` 表 + 落盘 `notification_policy.xml`。用户锁定字段的判定（`getUserLockedFields`）也在这里。

### 链路 4：DND 过滤链
`ZenModeHelper` 状态切换 → `ZenModeConditions` 触发自动规则 → `ZenModeFiltering.shouldIntercept()` 对每条通知裁决（拦截/静音/透传）→ 影响 `buzzBeepBlinkLocked` 与下发。车机驾驶免打扰从这里追。

---

## 16. 源码路径速查 + 一图总结 + 关联阅读

### 16.1 源码路径速查表

| 内容 | 路径 |
|---|---|
| NotificationManagerService | `frameworks/base/services/core/java/com/android/server/notification/NotificationManagerService.java` |
| NotificationRecord | `frameworks/base/services/core/java/com/android/server/notification/NotificationRecord.java` |
| RankingHelper | `frameworks/base/services/core/java/com/android/server/notification/RankingHelper.java` |
| RankingHandler（接口） | `frameworks/base/services/core/java/com/android/server/notification/RankingHandler.java` |
| NotificationSignalExtractor（接口） | `frameworks/base/services/core/java/com/android/server/notification/NotificationSignalExtractor.java` |
| NotificationListeners（ManagedServices） | `frameworks/base/services/core/java/com/android/server/notification/NotificationListeners.java` |
| ZenModeHelper / Config / Filtering | `frameworks/base/services/core/java/com/android/server/notification/ZenMode*.java` |
| NotificationChannel / Group（App API） | `frameworks/base/core/java/android/app/NotificationChannel.java` |
| NotificationManager（App API） | `frameworks/base/core/java/android/app/NotificationManager.java` |
| StatusBarNotification | `frameworks/base/core/java/android/service/notification/StatusBarNotification.java` |
| SystemUI 通知流水线 | `frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/notification/` |
| 通道持久化 | `/data/system/notification_policy.xml`（不同版本路径可能微调） |

### 16.2 一图总结：一条通知的裁决管线

```text
App: notify(id,tag,notif)
   │
   ▼ Binder
┌──────────────── NMS（本篇主场） ────────────────┐
│ ① 身份/权限闸门  (uid, POST_NOTIFICATIONS[13+], 冻结) │
│ ② 通道闸门      (Channel 存在? importance? 用户锁定?) │
│ ③ 建记录        (NotificationRecord + sbn)          │
│ ④ 去重/分组      (同 id/tag 更新, autogroup summary)  │
│ ⑤ Ranking       (一组 Extractor → globalSortKey)     │
│ ⑥ 打断/提示      (ZenMode 过滤, buzzBeepBlink 声/震/灯)│
│ ⑦ 下发监听者     (NotificationListeners → SystemUI)   │
└──────────────────────────────────────────────────┘
   │ onNotificationPosted
   ▼
SystemUI NotifPipeline（排序/过滤/分组/打断 → 渲染/heads-up）  → 见 18 篇 10/11
```

**一句话记忆链**：投信（App）→ 审信（权限/通道）→ 排信（Ranking）→ 响信（buzzBeepBlink/DND）→ 贴信（SystemUI）。卡哪关，先用 dumpsys 看 NMS 有没有 record，再二分到 SystemUI。

### 16.3 关联阅读

- `07_Service机制详解` 第 8 节：前台服务、`startForeground`、foregroundServiceType、FSI 权限——本篇第 5.3、8 章的源头。
- `14_权限机制详解`：权限与 AppOps 总框架，理解 `POST_NOTIFICATIONS` 与 `OP_POST_NOTIFICATIONS` 的底层。
- `15_Power机制详解` 第 4 节：通知震动/响铃持有的 PARTIAL WakeLock 语义。
- `18_SystemUI机制详解` 第 8、10、11、12 节：状态栏图标桥、NotifPipeline、heads-up 决策、Keyguard——本篇展示端的落地。
- `19_多用户机制详解`：通知按 user 隔离、`uid = userId*100000 + appId` 的基础。
- `23_多屏与多窗口机制详解`：车机多屏下通知归属与展示。
- `androidApp` 系列：`NotificationChannel` 的 App 侧创建/更新写法（本篇不重复）。

---

## 小结

通知系统把"打扰权"从 App 收归系统：App 只能"投信"，NMS 用**权限闸门 + 通道 importance + Ranking 排序调整器 + DND 过滤**完成裁决，最后通过 `NotificationListeners` 桥把结构化记录交给 SystemUI 渲染。记住三关——**能不能发（权限/通道）、排第几（Ranking）、响了谁负责（buzzBeepBlink/DND）**——配合 `dumpsys notification --noredact` 与第 14 章排查表，绝大多数通知问题都能在五分钟内定位到具体哪一关。
