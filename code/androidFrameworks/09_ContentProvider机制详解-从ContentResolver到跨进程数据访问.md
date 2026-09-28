# ContentProvider 机制详解：从 ContentResolver 到跨进程数据访问

> ContentProvider（内容提供者，下文简称 CP）是 Android 四大组件里最容易被低估的一环。它表面上只是"数据库封装"，实际上是一套**跨进程数据访问 + 隐式进程拉起 + URI 寻址 + 临时授权 + 数据变更通知**的完整机制。车载 ROM vendor 在预装、权限、媒体共享、多用户隔离上几乎一定会和它打交道。

本篇聚焦 CP **机制本身**：四个角色怎么协作、进程怎么被拉起、URI 怎么匹配、Cursor 怎么跨进程回流、权限与授权边界、并发与崩溃语义、系统 provider 速查、平台与多用户视角、排查与读源码路线。

「Android 视角」约定：凡标了这个标签的，是 AOSP / 车机 / ROM 层面的细节，跳过不影响理解 CP 的通用原理；但做 vendor 开发时务必看。

---

## 0. 目录（导航）

- [1. 开场：ContentProvider 回答哪三个问题](#1-开场contentprovider-回答哪三个问题)
- [2. 四个角色：ContentProvider / ContentResolver / IContentProvider / Transport](#2-四个角色contentprovider--contentresolver--icontentprovider--transport)
- [3. 启动与发布：进程起来后 Provider 怎么就位](#3-启动与发布进程起来后-provider-怎么就位)
- [4. 按需拉起：acquireProvider 的完整链路与等待](#4-按需拉起acquireprovider-的完整链路与等待)
- [5. Uri 匹配与 authority：寻址、冲突与权限](#5-uri-匹配与-authority寻址冲突与权限)
- [6. Cursor 的跨进程本质：窗口化分批加载](#6-cursor-的跨进程本质窗口化分批加载)
- [7. 增删改与批量：返回语义、applyBatch 与 call()](#7-增删改与批量返回语义applybatch-与-call)
- [8. ContentObserver 跨进程通知：双向链路与自更新循环](#8-contentobserver-跨进程通知双向链路与自更新循环)
- [9. URI 权限临时授予：grantUriPermission 与 FLAG_GRANT_*](#9-uri-权限临时授予granturipermission-与-flag_grant_)
- [10. Provider 的并发模型与崩溃语义](#10-provider-的并发模型与崩溃语义)
- [11. 单例 Provider 与系统 Provider 速查](#11-单例-provider-与系统-provider-速查)
- [12. 平台视角：SELinux / 多用户 / dumpsys 读法](#12-平台视角selinux--多用户--dumpsys-读法)
- [13. 常见问题排查表](#13-常见问题排查表)
- [14. 读源码路线](#14-读源码路线)
- [15. 源码路径速查 + 一图总结 + 关联阅读](#15-源码路径速查--一图总结--关联阅读)

---

## 1. 开场：ContentProvider 回答哪三个问题

当你写下：

```java
Cursor c = getContentResolver().query(
        Uri.parse("content://com.example.user/users/1"),
        new String[]{"name", "age"}, null, null, null);
```

你实际上在问 CP 机制三个问题。**把这三个问题记牢，后面所有章节都是在回答它们**：

| 问题 | 含义 | 由谁回答 |
|---|---|---|
| **数据在哪** | 这个 `content://` URI 对应的 Provider 在哪个进程、哪个包里？ | PMS（解析 manifest authority）+ AMS（找进程） |
| **谁能读** | 调用方有没有 `readPermission` / `writePermission`、`path-permission`、URI 临时授权？ | Provider 的 `Transport` 在每次调用入口做权限校验 |
| **读的时候进程活着吗** | Provider 所在进程可能根本没启动；本次调用要不要（隐式）把它拉起来？ | `acquireProvider` → 必要时 `startProcessLocked` |

一句话记忆：**ContentResolver 负责"找"，AMS 负责"拉起与管理"，Transport 负责"鉴权与转发"，ContentProvider 负责"真正干活"。**

### 1.1 一次 query 的全景图

下面这条链路是本篇的主线，后面每一章都是把其中一段放大：

```text
App A（调用方进程）                          system_server                      App B（Provider 进程）
─────────────────────                       ──────────────                    ───────────────────
ContentResolver.query(uri)
   └─ acquireProvider(uri)  ──Binder──▶  AMS / ContentProviderHelper
        │                                   ├─ PMS: authority → ProviderInfo
        │                                   ├─ 权限 / userId / exported 检查
        │                                   ├─ 目标进程在跑？
        │                                   │    否 → startProcessLocked 拉起 App B
        │                                   │            （App B 起来后 publishContentProviders）
        │                                   └─ 等待直至 published，返回 IContentProvider
        │  ◀── IContentProvider (unstable) ─┘
   └─ unstableProvider.query(...)  ──Binder──▶  ContentProvider.Transport.query()
                                                     └─ ContentProvider.query()
                                                            └─ 返回 Cursor
        │  ◀── CursorWindow（共享内存 ashmem）回流 ──┘
   └─ BulkCursorToCursorAdaptor（包装成 Cursor 交给 App A）
```

注意三个"跨进程"动作：① 找 Provider（A→AMS）；② 真正执行 query（A→B）；③ Cursor 数据回流（B→A，走共享内存而非 Binder 拷贝）。

### 1.2 怎么读这篇

- 想先建立直觉：读第 2 章（四个角色关系图）。
- 关心冷启动被拖慢 / 进程拉起：读第 3、4 章。
- 关心权限拒绝 `SecurityException`、URI 临时授权：读第 5、9 章。
- 关心 Cursor 卡顿 / "Row too big"：读第 6 章。
- 关心数据变更通知不回调：读第 8 章。
- 做 vendor 预装 / 多用户 / SELinux：读第 12 章。
- 直接排故障：跳第 13 章。
- 想下场读 AOSP：跳第 14、15 章。

---

## 2. 四个角色：ContentProvider / ContentResolver / IContentProvider / Transport

CP 机制之所以绕，是因为"调用方"和"提供方"中间还隔着 Binder 契约与 AMS。先把四个核心角色摆清楚。

### 2.1 角色关系图

```text
   调用方进程 App A                           Provider 进程 App B
 ┌────────────────────────┐                ┌──────────────────────────────┐
 │ ContentResolver        │                │ ContentProvider（你写的子类） │
 │  （抽象类）            │                │   query/insert/update/delete  │
 │   ↑ 具体实现           │    Binder      │      ↑ 被调用                 │
 │ ApplicationContentResolver│  ─────────▶ │ ContentProvider.Transport     │
 │   （ActivityThread 内部类）│  IContentProvider │  extends IContentProvider.Stub │
 │   getContentResolver()  │  (AIDL 代理)  │   （Binder 桩，做权限校验）   │
 │   持有 ActivityThread   │                │     持有外层 ContentProvider │
 │   的引用               │                │     mTransport = new Transport()│
 └────────────────────────┘                └──────────────────────────────┘
           │                                          ▲
           │ acquireProvider(uri)                     │ publishContentProviders
           ▼                                          │
   ┌──────────────────────────────────────────────────────┐
   │ ActivityThread（两侧各一份实例）                        │
   │   调用方侧：acquireProvider → 问 AMS 拿 IContentProvider│
   │   提供方侧：installContentProviders → 创建并 publish    │
   └──────────────────────────────────────────────────────┘
           │                                          │
           ▼                                          ▼
   ┌──────────────────────────────────────────────────────┐
   │ system_server：AMS / ContentProviderHelper             │
   │   登记 ProviderRecord、按 authority 寻址、拉起进程、   │
   │   等待 publish、缓存 IContentProvider                  │
   └──────────────────────────────────────────────────────┘
```

四者职责一句话：

| 角色 | 所在进程 | 是什么 | 关键方法 |
|---|---|---|---|
| `ContentResolver` | 调用方 | 客户端入口抽象类 | `query/insert/update/delete/applyBatch/call` |
| `ApplicationContentResolver` | 调用方 | `ContentResolver` 的具体实现，`ActivityThread` 的内部类 | 把请求委托给 `ActivityThread.acquireProvider` |
| `IContentProvider` | 两侧 | AIDL 接口（跨进程契约），定义 query/insert/... 签名 | Binder 代理 ↔ Binder 桩 |
| `ContentProvider.Transport` | Provider | `IContentProvider.Stub` 的具体实现，是真正的 Binder 服务端 | 每次调用前 `enforceReadPermission` 等，再转调外层 `ContentProvider` |
| `ContentProvider` | Provider | 你继承的组件，实现真正的业务逻辑 | `onCreate/query/insert/update/delete/applyBatch/call` |

### 2.2 ApplicationContentResolver 的归属（容易被忽略）

`getContentResolver()` 返回的不是 `ContentResolver` 本身，而是 `ContextImpl` 里持有的 `ApplicationContentResolver` 实例：

```java
// ContextImpl.java（简化）
private final ApplicationContentResolver mContentResolver;
@Override
public ContentResolver getContentResolver() { return mContentResolver; }

// ApplicationContentResolver 是 ActivityThread 的内部类
private static final class ApplicationContentResolver extends ContentResolver {
    private final ActivityThread mMainThread;
    @Override
    protected IContentProvider acquireProvider(Context c, String auth, int userId, boolean stable) {
        return mMainThread.acquireProvider(c, auth, userId, stable); // 关键：委托给 ActivityThread
    }
    @Override
    protected IContentProvider acquireExistingProvider(Context c, String auth, int userId, boolean stable) {
        return mMainThread.acquireExistingProvider(c, auth, userId, stable);
    }
}
```

所以"客户端怎么找到 Provider"这步，最终落到**调用方自己进程里的 `ActivityThread`** 上——它维护一张 `mProviderByAuthority` 缓存表，命中就直接返回，未命中才跨进程去问 AMS。

### 2.3 ContentProvider 自己是怎么暴露 Binder 的

你在 manifest 里声明一个 `<provider>`，并在 `Application` 起来时由 `installContentProviders` 通过反射 `newInstance` 创建实例。它内部持有一个 `Transport`：

```java
// ContentProvider.java
private Transport mTransport = new Transport();   // 这个就是对外暴露的 Binder 对象
public IContentProvider getIContentProvider() { return mTransport; }
```

`Transport` 在每次跨进程入口（如 `query/insert`）先调用 `enforceReadPermission` / `enforceWritePermission`，权限不过就直接抛 `SecurityException`，过则转调外层 `ContentProvider` 的对应方法。**这意味着权限校验发生在 Provider 进程里，而不是调用方进程里**——调用方永远不会拿到"裸"的 Provider 对象，只拿到 `IContentProvider` 代理。

---

## 3. 启动与发布：进程起来后 Provider 怎么就位

这一章解答"数据在哪 / 进程活着吗"在**提供方自己冷启动**时的细节，也是冷启动被拖慢、甚至 ANR 的高频来源。

### 3.1 精确时序：Provider.onCreate 在 onCreate 之前

先在脑子里钉死这条顺序（实测于 `ActivityThread.handleBindApplication`）：

```text
ActivityThread.handleBindApplication(data)
  ├─ makeApplication(...)            // 内部 newApplication → Application.attachBaseContext()
  │                                 //   （注意：attachBaseContext 在这里，早于 Provider）
  ├─ if (data.providers != null)
  │     installContentProviders(app, data.providers)   // ★ 所有 manifest Provider 的 onCreate 在这跑
  │        └─ 对每个 ProviderInfo：
  │             ├─ 反射/AppComponentFactory 创建 ContentProvider 实例
  │             ├─ provider.attachInfo() → provider.onCreate()   // 在【主线程】执行
  │             └─ 收集 (ProviderInfo, IContentProvider) 准备 publish
  ├─ publishContentProviders(...)   // 把 Transport（IContentProvider）上报给 AMS
  │
  └─ mInstrumentation.callApplicationOnCreate(app)   // ★ Application.onCreate 在这跑
```

结论（很多人记错）：

> **`Application.attachBaseContext()` → 所有 manifest 的 `ContentProvider.onCreate()` → `Application.onCreate()`。**
> Provider 的 `onCreate()` **早于** `Application.onCreate()`，但**晚于** `attachBaseContext()`。

### 3.2 Provider.onCreate 跑在主线程，且会阻塞后续

`installContentProviders` 是在 `handleBindApplication` 的调用链上、由**新进程的主线程（UI 线程）**直接执行的，不是 binder 线程。所以：

- 你在 `ContentProvider.onCreate()` 里做数据库打开、网络、重反射、读大文件 → 直接拖慢**自己进程的冷启动**，严重时触发 **Application 启动超时（本来就是主线程阻塞）**。
- 三方 SDK 常用 `ContentProvider` 做"无感初始化"（如 WorkManager 的 `WorkManagerInitializer`、Firebase 早期 `FirebaseInitProvider`、LeakCanary 的 `MainProcessController` 等）。你没手动调用 SDK，它也可能在 `Application.onCreate()` 之前就跑完了。车机 ROM 预装一堆 SDK 时，这个坑会被放大。

「Android 视角」vendor 优化手段：

- 审计预装 APK 的 `<provider>`，把非必要的 `android:enabled="false"` 或合并初始化。
- 用 `android:initOrder` 控制同包内多个 Provider 的启动先后（只影响同进程内相对顺序，不能跨进程）。
- 把重活在 `Application.onCreate()` 之后延迟到后台线程，或对 Provider 做懒加载（首次 `query` 才初始化）。

### 3.3 publish 之后：ContentProviderRecord 与 Holder

Provider 实例创建并 `onCreate()` 后，`publishContentProviders` 跨进程通知 AMS，AMS 侧建立：

- `ContentProviderRecord`（简称 CPR）：记录 authority、包名、进程、uid、`userId`、`IContentProvider` 引用、连接列表。
- `ContentProviderHolder`：AMS 返回给"等待者"的对象，内含 `IContentProvider provider` 和 `ProviderInfo info`，以及一个 `stable` 标志（见第 4 章）。
- `ContentProviderConnection`：调用方与 CPR 之间的一条引用计数连接，决定 Provider 进程能否被回收（没连接且不在前台时，Provider 进程可能被 LMK 杀掉）。

publish 完成后，AMS 会 `cpr.notifyAll()`，唤醒所有因为"Provider 还没起来"而阻塞等待的调用方（第 4 章详述）。

---

## 4. 按需拉起：acquireProvider 的完整链路与等待

这一章把"读的时候进程活着吗"在**调用方视角**彻底讲清：Provider 进程没起来会发生什么、调用方会等多久、多进程 Provider 怎么玩。

### 4.1 acquireProvider 完整链路

```text
ContentResolver.query(uri)                         // 调用方
  └─ acquireUnstableProvider(uri)                  // ★ query 默认用 unstable
       └─ acquireProvider(mContext, authority, userId, stable=false)
            └─ ApplicationContentResolver.acquireProvider(...)
                 └─ ActivityThread.acquireProvider(context, auth, userId, stable)
                      ├─ 查本地缓存 mProviderByAuthority[auth]   // 命中且未死 → 直接返回
                      └─ 未命中 → AMS.getContentProvider(getApplicationThread(), auth, userId, stable)
                           └─ ContentProviderHelper.getContentProviderImpl(...)
                                ├─ 按 authority 找到 ProviderInfo（问 PMS）
                                ├─ getProcessRecordLocked(target)  // 目标进程在跑吗？
                                │     ├─ 在跑且已 publish → 直接返回 ContentProviderHolder
                                │     └─ 没在跑 →
                                │            startProcessLocked(...)  // 拉起 App B
                                │            cpr.launchingProviders.add(caller)  // 登记等待者
                                │            cpr.wait()   // ★ 调用方 binder 线程在此阻塞等待
                                │                           （直到 App B publish 后 notifyAll）
                                └─ 返回 ContentProviderHolder（含 IContentProvider）
```

要点：

1. **先查本地缓存**：`ActivityThread` 的 `mProviderByAuthority` 命中即可零跨进程返回；只有冷拉起才走 AMS。
2. **query 用 unstable provider**：`ContentResolver.query` 内部走 `acquireUnstableProvider`。区别在于——如果目标进程死了，unstable 不会把"调用方自己的 Provider 连接"也拖死，而是让调用方收到 `DeadObjectException` 并自动重试（最多两次后判定 Provider 真死了）。`call()` 等场景用 stable provider，目标死亡会直接 kill 调用方与 Provider 的连接引用（详见第 10 章）。
3. **等待是阻塞的**：调用方的 binder 线程会停在 `cpr.wait()`，直到目标进程 `publishContentProviders`。这就是为什么"只是查一个 Provider，却把另一个 App 冷启动了"，而且这次 query 的耗时 = 对方进程冷启动 + Provider.onCreate + 实际查询。

### 4.2 android:process 与多进程 Provider

一个 Provider 可以指定 `android:process`，从而：

- 跑在**非默认进程**（比如 `:provider` 独立进程），与主进程隔离，主进程崩了不影响它，反之亦然。
- 同一个 APK 里多个 Provider 可以分属不同进程；同一个 authority 只能属于一个进程。

坑（车机 / 多进程 App 高频）：

- **多进程 Provider 死锁**：进程 A 的 Provider 在查询时又去 binder 调用进程 B 的 Provider，而 B 此刻正通过 binder 反向调用 A 的某个同步接口（例如 A 主线程等 B 结果、B 又等 A 主线程），形成跨进程死锁。现象是两侧都 `binder` 卡死、最终可能 ANR。排查见第 13 章。
- 独立进程里的 Provider 也走 `installContentProviders`（每个进程各自 `handleBindApplication`），只是 `data.providers` 只含"属于本进程"的那些。
- 多进程下 `Application` 会被**每个进程各创建一个实例**，Provider.onCreate 在每个进程各跑一次。

「版本差异」：Provider 的**生命周期本身**在 Android 各版本间没有大的行为变化；变化集中在权限校验粒度（`path-permission`、URI 授权）与 `call()` 的返回值 redaction（见第 7、9 章），以及后台启动限制对"被拉起"的影响（Android 12+ 对后台/缓存状态拉起其它进程更严格，Provider 冷拉起可能被 `foregroundService` / 可见性约束影响）。

---

## 5. Uri 匹配与 authority：寻址、冲突与权限

"数据在哪"最终落到 **authority（授权名）** 这个字符串上。manifest 的 `<provider>` 通过 `android:authorities` 把它和某个 `ContentProvider` 类绑定，PMS 据此建索引，AMS 据此寻址。

### 5.1 URI 结构与 UriMatcher

一个标准 content URI：

```text
content://com.example.user/users/1/books
        └authority─┘ └────path────┘
```

- `authority` 决定"找哪个 Provider"（全局唯一）。
- `path` 由 Provider 自己解释（通常 `/表名/行id`）。

Provider 内部用 `UriMatcher` 把 URI 路由到具体逻辑：

```java
public class UserProvider extends ContentProvider {
    private static final int CODE_USERS      = 1;
    private static final int CODE_USER_ID    = 2;
    private static final int CODE_USER_BOOKS = 3;
    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);
    static {
        // # 匹配数字，* 匹配任意文本
        MATCHER.addURI("com.example.user", "users",            CODE_USERS);
        MATCHER.addURI("com.example.user", "users/#",          CODE_USER_ID);
        MATCHER.addURI("com.example.user", "users/#/books",    CODE_USER_BOOKS);
    }
    @Override
    public Cursor query(Uri uri, String[] proj, String sel, String[] args, String sort) {
        switch (MATCHER.match(uri)) {
            case CODE_USERS:      return queryAllUsers(proj, sel, args, sort);
            case CODE_USER_ID:    long id = ContentUris.parseId(uri); return queryUser(id, proj);
            case CODE_USER_BOOKS: long uid = Long.parseLong(uri.getPathSegments().get(1));
                                  return queryBooks(uid, proj);
            default: throw new IllegalArgumentException("Unknown URI: " + uri);
        }
    }
}
```

配套工具：`ContentUris.withAppendedId(uri, id)` 构造带 id 的 URI；`ContentUris.parseId(uri)` 取出 id。`UriMatcher` 是纯本地字符串匹配，不参与跨进程，是 Provider 自己拿到 URI 后的"分诊台"。

### 5.2 authority 冲突：INSTALL_FAILED_CONFLICTING_PROVIDER

PMS 在扫描/安装时要求 **authority 全局唯一**。两个**不同包**声明了同一个 authority，后装的那个会失败：

```text
adb install xxx.apk
  → Failure [INSTALL_FAILED_CONFLICTING_PROVIDER]
```

「Android 视角」vendor 必踩点：

- 不要把同一个 authority 同时写进「系统预装 APK」和「可卸载的用户 APK」——升级/覆盖安装时必冲突。
- 同 `android:sharedUserId` 的多个系统应用可以"共享"一个 Provider（跑在同一 UID），但 authority 仍只能由其中一个真正声明。
- 一个 Provider 在单个 `<provider>` 元素里可以用 `;` 分隔声明**多个 authority**（如 `android:authorities="com.a;com.b"`），但列表里每一个都必须全局唯一。
- 想让一个 Provider 类服务多个 authority，要么在 manifest 写多条 `<provider>` 指向同一 class（不同 authority），要么单条用 `;` 分隔。

### 5.3 read / write / path-permission 逐项讲清

权限在 `<provider>` 上有三层（由粗到细）：

| 属性 | 作用域 | 含义 |
|---|---|---|
| `android:permission` | 整个 authority | 同时作为读和写的默认权限 |
| `android:readPermission` | 整个 authority | 查询/读的门槛（覆盖 `permission`） |
| `android:writePermission` | 整个 authority | 增删改/写的门槛（覆盖 `permission`） |
| `<path-permission>` | 特定 path | 仅对匹配路径施加更细的读/写权限 |

`path-permission` 支持三种匹配：

- `android:path="/users"` —— 精确匹配该路径；
- `android:pathPrefix="/users"` —— 该路径及其所有后代；
- `android:pathPattern="/users/.*"` —— 正则（`.*` 表示任意，`/` 需转义成 `//`）。

典型用法：Provider 整体 `exported="true"` 允许被外部发现，但 `/users/secret` 这种敏感路径用 `path-permission` 额外加一个签名/特权权限，做到"公开的表 + 受保护的子路径"。

`android:exported` 的取值注意（避免版本坑）：

- Provider 历史上**未显式声明时默认 `exported=true`**（与 Activity/Service 不同，Provider 不需要 intent-filter 也会默认导出）。
- **Android 12（API 31）起**：带 `<intent-filter>` 的组件必须显式声明 `android:exported`，否则安装/构建失败。
- 建议：vendor 给所有 `<provider>` 显式写 `android:exported`，不要让默认值替你做安全决策。

### 5.4 临时授权与静态权限的关系（承上启下）

上面三项是"静态声明"的权限，写在 manifest 里、安装期就定死。但很多场景（如把一张图通过 Intent 分享给另一个 App）没法预先知道对方包名、也不该把整个 Provider 暴露出去——这就是第 9 章 **URI 临时授予（grantUriPermission）** 的用武之地。两者关系：静态权限先卡一道大门，临时授权再开一个精确的小口子。

---

## 6. Cursor 的跨进程本质：窗口化分批加载

很多人以为 query 返回的是"把对方进程的数据整体拷贝过来"。错。Cursor 跨进程的核心是 **Binder 代理 + 共享内存窗口（CursorWindow）的懒加载**。

### 6.1 服务端怎么把 Cursor 交出去

```text
App B 进程                                          App A 进程
─────────────────────────────────                 ─────────────────────────────────
ContentProvider.query() 返回真实 Cursor
   （如 SQLiteCursor，实现 CrossProcessCursor）
        │
ContentProvider.Transport.query()
   └─ new CursorToBulkCursorAdaptor(realCursor, observer, ...)
        │   // 这个 Adaptor 本身是一个 Binder（IBulkCursor 的桩）
        ▼
   通过 IContentProvider.query() 把 Adaptor 的代理(IBulkCursor) 交回调用方
                                                    ContentResolver.query 拿到代理
                                                        └─ new BulkCursorToCursorAdaptor(ibulk)
                                                            这就是 App A 拿到的"Cursor"
```

于是 App A 手里是一个 `BulkCursorToCursorAdaptor`（实现了 `CrossProcessCursor`），它**本身不持有数据**，只持有指向 App B 的 `IBulkCursor` 代理。所有 `moveToNext()/getString()` 最终都通过 Binder 向 App B 要窗口数据。

### 6.2 CursorWindow：共享内存里的滑动窗口

真正搬运数据的是 `CursorWindow`——一块 **ashmem 共享内存**（不是 Binder parcel 拷贝）：

- 服务端把若干行塞进一个固定大小的 `CursorWindow`（历史默认 **2 MB**，由系统属性 `android.cursorWindowSize` 以 KB 计；移动端不必全量拷贝）；
- 这个窗口的 fd 通过 Binder **只传文件描述符**（零拷贝共享），客户端直接读共享内存；
- 客户端 cursor 移动到窗口外的行时，框架回调 `fillWindow(startPos, window)` 向服务端要下一批，再换/补窗口。

所以"query 返回 10 万行"不会一次性把 10 万行传过来，而是**按需分页进窗口**——这是 CP 能扛大结果集的关键。

### 6.3 crossProcessCursor 与自定义窗口

接口 `CrossProcessCursor` 定义：

```java
public interface CrossProcessCursor extends Cursor {
    void fillWindow(int position, CursorWindow window); // 把指定位置起的行填进窗口
    CursorWindow getWindow();                           // 当前已填好的窗口
    boolean onMove(int oldPosition, int newPosition);   // 移动时的钩子
}
```

`AbstractWindowedCursor`（`SQLiteCursor` 的基类）实现了它。机制要点：

- 若服务端 Cursor 实现了 `CrossProcessCursor`，服务端可以直接 `fillWindow` 把数据装进窗口；否则框架会用 `CursorWindow` 自己搬运。
- 你可以用 `setWindow(CursorWindow)` 给 Cursor 指定自定义窗口（大小/来源），实现特殊分页或内存控制。
- `getWindow()` 返回的就是当前那块 ashmem 窗口；客户端读 `getString/.getBlob` 都从这块窗口取。

### 6.4 "Row too big to fit in CursorWindow"

这是 CP 查询最常见的崩溃之一：

```text
android.database.CursorWindowAllocationException:
  Cursor window allocation of 2048 kb failed. ... Row too big to fit in CursorWindow
```

成因：单行的序列化大小**超过单个 CursorWindow 的容量**（典型是把大 BLOB / 长文本整行查出）。它发生在服务端 `fillWindow` 往窗口填这一行时放不下。排查与对策：

- 不要把大 BLOB（图片/文件）直接当列查出来；改存文件路径、另走流或 FileProvider；
- 拆分查询列，只取需要的字段；
- 必要时调大窗口（`setWindow` 或系统属性 `android.cursorWindowSize`，但 ROM 侧改全局属性要评估内存）。
- 注意：窗口是**按行**填的，单行超限即炸，和总行数无关。

「版本差异」：CursorWindow 默认大小长期是 2 MB；Android 11（API 30）起对部分场景放宽/可配置，但具体上限仍是"单行不能超窗口"。把大对象当列查这个坑跨版本都在。

---

## 7. 增删改与批量：返回语义、applyBatch 与 call()

CRUD 不只是"发起调用"，每个方法的**返回值语义**和**事务边界**决定了你怎么写客户端逻辑。

### 7.1 insert / update / delete / bulkInsert 的返回语义

| 方法 | 返回类型 | 语义 | 失败表现 |
|---|---|---|---|
| `insert(Uri, ContentValues)` | `Uri` | **新行的 URI**（authority + 追加的行 id），如 `content://com.example.user/users/7` | 失败返回 `null`（不抛异常） |
| `bulkInsert(Uri, ContentValues[])` | `int` | 成功插入的**行数** | 返回实际插入数（可能 < 数组长度，取决于实现） |
| `update(Uri, values, where, selArgs)` | `int` | 受影响的**行数** | 返回受影响行数（0 表示没匹配） |
| `delete(Uri, where, selArgs)` | `int` | 被删除的**行数** | 返回被删行数 |

关键提醒：

- `insert` 返回的是 **URI 不是 id**——取 id 要 `ContentUris.parseId(uri)`。很多新手直接当 long 用会崩。
- `insert` 失败是 **返回 null 而非抛异常**，客户端必须判空，不能假设"调用没抛就成功了"。
- `update/delete` 返回 0 不代表出错，只代表没匹配到行——别把它当失败处理。

### 7.2 applyBatch：ContentProviderOperation 的事务语义

`applyBatch(ArrayList<ContentProviderOperation>)` 返回 `ContentProviderResult[]`（与入参一一对应）。它的核心价值是**把多个操作放进一个事务**，要么全成、要么全回滚：

```java
ArrayList<ContentProviderOperation> ops = new ArrayList<>();
ops.add(ContentProviderOperation.newInsert(uri)
        .withValues(v1).build());
ops.add(ContentProviderOperation.newUpdate(uri)
        .withSelection("id=?", new String[]{"7"})
        .withValue("name", "henry")
        .withValueBackReference(...)   // 引用前一步 insert 返回的行 id
        .build());
ContentProviderResult[] results = resolver.applyBatch("com.example.user", ops);
```

要点：

- **事务性**：标准实现里 Provider 会用 `db.beginTransaction()` 包住整批，任意一步抛 `OperationApplicationException` 则整批回滚（对本地 SQLite 而言）。
- **BackReference**：`withValueBackReference("col", 0)` 表示取第 0 个操作的返回结果（如刚 insert 的 id）填到当前操作的 `col`，实现"先插父再插子"。
- **yield（让渡）**：`withYieldAllowed(true)` 是一个**提示**——当批量很大时，Provider 应在合适位置调用 `SQLiteDatabase.yieldIfContendedSafely()` 让出锁/连接，避免一长事务饿死其它并发读写。注意它是"提示"，是否生效取决于 Provider 实现是否真的去 yield。
- 跨 authority 的批量：`applyBatch` 也可以接收混合 authority 的操作，但要求所有 operation 的 authority 都指向**同一 Provider**（除非 Provider 自己重写 `applyBatch` 做分发）。

### 7.3 call()：通用的"任意方法"逃生舱

当 CRUD 表达不了你的操作（如"清空缓存""触发某内部动作""取一个聚合计算结果"），用：

```java
Bundle res = resolver.call("com.example.user", "purgeCache", null, args);
```

`call(String method, String arg, Bundle extras)` 返回 `Bundle`，是 Provider 暴露任意自定义逻辑的通道。系统 Provider 大量用它：

- `Settings` Provider 用 `call("PUT" / "GET" / "RESET", ...)` 实现设置项的读写与持久化；
- `MediaStore` 用它暴露特殊能力（如"创建收藏""取某个媒体集"）。

注意：`call()` 同样在 Provider 的 **binder 线程**上执行，同样受 `exported` 与权限约束（Transport 会对 `call` 做 `enforceCallPermission` 等校验）。

「版本差异」**CALL redaction（Android 14 / API 34）**：系统开始对部分 Provider 的 `call()` 返回值做隐私 redaction——敏感键（如 Settings 中带特定标记的字段）对非特权调用方返回时被抹掉/替换为占位。第三方 Provider 一般不受影响，但**不要通过 `call()` 无条件返回敏感数据**，实现自定义 `call()` 时把敏感字段按需收敛。

---

## 8. ContentObserver 跨进程通知：双向链路与自更新循环

`ContentObserver` 让你"监听某个 URI 的数据变化"，但它背后是一条 **ContentService 维护的跨进程观察者树**，不是简单的回调。

### 8.1 双向链路：注册与通知

```text
注册（客户端）                               system_server                       Provider 端
─────────────────                          ──────────────                     ──────────────
resolver.registerContentObserver(           ContentResolver.registerContentObserver
   uri, notifyForDescendants, observer)  ──▶  IContentService.registerContentObserver
        │                                      ContentService 把 observer 挂到
        │                                      ObserverNode 树上（按 uri 段分叉）
        │                                                                provider 数据变了：
        │                                                                 resolver.notifyChange(uri, selfObs, syncToNetwork)
        │                                                                   └─ IContentService.notifyChange ─▶ ContentService 遍历树
        │                                                                       找到匹配的 observer（含祖先 notifyForDescendants）
        │                                                                          └─ observer.onChange(selfChange, uri) ──▶ 客户端回调（跨进程）
```

注册侧（客户端）：

```java
Uri uri = Uri.parse("content://com.example.user/users");
resolver.registerContentObserver(uri, true /* notifyForDescendants */, new ContentObserver(new Handler()) {
    @Override public void onChange(boolean selfChange, Uri uri) {
        // 数据变了，重新拉取
    }
});
```

Provider 侧（数据变更后必须主动发通知，否则没人收到）：

```java
getContext().getContentResolver().notifyChange(changedUri, null /* observer */, false /* syncToNetwork */);
```

### 8.2 三个最容易踩的语义点

1. **notifyForDescendants（第二个 boolean）**：注册时若为 `true`，则 URI 的子路径变化也会通知。例如注册 `content://x/users`，`true` 时会连 `content://x/users/1` 的变化也收到；`false` 只匹配精确 URI。
2. **notifyChange 的 observer 参数能排除自己**：`notifyChange(uri, observer)` 传入的 `observer` 会被**排除在本次通知之外**。这是防回声的标准手段——"我改的数据，就别再通知我自己"。
3. **syncToNetwork**：`notifyChange` 的第三个参数为 `true` 时，会触发为该 authority 注册的 `SyncAdapter` 发起网络同步。不小心置 `true` 可能在本地小改动后拉起网络同步，浪费流量与电量（车机流量敏感场景尤其注意）。

### 8.3 debounce 与自更新循环

- **没有内置去抖（debounce）**：连续 10 次 `notifyChange` 同一个 URI，观察者的 `onChange` 会被调 10 次。高频变更场景要自己在客户端用 `Handler.postDelayed` 合并，或在 Provider 端节流。
- **自更新循环（最常见 bug）**：`onChange` 里又去写数据（写 → notifyChange → onChange → 写 …）。破圈办法：
  - 在写入前 `unregisterContentObserver`，写完再注册；
  - 写入时 `notifyChange(uri, thisObserver)` 把自身排除；
  - 加一个"我正在处理"的重入标记；
  - 注意 `ContentObserver.deliverSelfNotifications()`：若注册时开了"自我投递"，连自己触发的变更都会收到——默认关闭，别误开。

---

## 9. URI 权限临时授予：grantUriPermission 与 FLAG_GRANT_*

静态权限（第 5 章）回答"谁能读整个 Provider"，临时授权回答"我此刻把**这一个 URI** 临时借给**这一个包**读/写"。这是 FileProvider 分享文件、跨 App 传图的底层机制。

### 9.1 两种授予方式

**方式一：代码显式授予**

```java
// 把 uri 以读权限临时授予 targetPkg，直到本进程死亡（或显式 revoke）
context.grantUriPermission(targetPkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
// 撤销
context.revokeUriPermission(targetPkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
```

**方式二：Intent flag 授予（最常用，FileProvider 分享就这么干）**

```java
Intent share = new Intent(Intent.ACTION_VIEW);
share.setDataAndType(contentUri, "image/jpeg");
share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);  // 借给接收方读权限
share.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION); // 可选：写权限
startActivity(share);
```

接收方在 `onActivityResult`/Intent 里拿到的就是已带临时读权限的 `content://` URI，无需声明任何 `<provider>` 权限即可访问。

`FLAG_GRANT_*` 全家桶：

| Flag | 含义 |
|---|---|
| `FLAG_GRANT_READ_URI_PERMISSION` | 临时读 |
| `FLAG_GRANT_WRITE_URI_PERMISSION` | 临时写 |
| `FLAG_GRANT_PERSISTABLE_URI_PERMISSION` | 授予可持久化（跨进程死亡/重启保留，需配合 `takePersistableUriPermission`） |
| `FLAG_GRANT_PREFIX_URI_PERMISSION` | 授权范围扩展到 URI 的全部后代（前缀匹配） |

### 9.2 Clip 的 URI 授权（一次授权一整组）

通过 `Intent.setClipData(ClipData)` 携带多个 URI，再配合 grant flag，可以**一次性把 clip 里所有 URI 的权限都授予接收方**：

```java
ClipData clip = ClipData.newUri(resolver, "label", uri1);
clip.addItem(new ClipData.Item(uri2));
share.setClipData(clip);
share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);  // 对所有 clip 内 URI 生效
```

这是多文件分享（如选多张图发送）的标准做法，比逐个 `grantUriPermission` 干净。

### 9.3 持久化（persistable）与安全边界

- **默认临时授权随授予方进程死亡而失效**；想跨重启保留，必须：Provider 声明 `android:grantUriPermissions="true"`（或对应 `<path-permission>` 允许），授予时带 `FLAG_GRANT_PERSISTABLE_URI_PERMISSION`，并在授予方用 `context.getContentResolver().takePersistableUriPermission(uri, flags)` 落地。
- **安全边界（务必记牢）**：
  - 授权是**精确作用域**：只给 `targetUid` 对 `那个具体 uri`（或 prefix）的 `指定 mode`，**不是整个 Provider**；
  - 没有 `android:grantUriPermissions="true"`（或匹配的 path-permission），Intent flag 会被无视，接收方访问直接 `SecurityException`；
  - 授予记录由 AMS / `UriGrantsManagerService` 以 `UriPermission` 形式保存，可 `revokeUriPermission` 随时撤回；
  - 接收方拿到的只是"这个 URI 的临时门票"，绕不到 Provider 的其它路径。

「Android 视角」FileProvider 的 App 侧完整用法（配置 `file_paths.xml`、`authorities` 命名、分享 Intent 写法）本篇不展开，见 `androidApp/07`；本篇只讲清临时授权这一底层机制与边界。

---

## 10. Provider 的并发模型与崩溃语义

CP 不像 Activity 只有一个主线程。理解"你的代码跑在哪个线程、进程死了客户端会怎样"，是写稳 Provider 的前提。

### 10.1 默认跑在 binder 线程池

- `onCreate()` 在**提供方进程的主线程**执行（因为它在 `handleBindApplication` 主线程链路上，见第 3 章）。
- `query/insert/update/delete/applyBatch/call` 全部在**提供方进程的 binder 线程池**里并发执行——一个 `BinderThread #N` 对应一次调用。这意味着你的 Provider 实现**可能被多个线程同时进入**，必须保证线程安全（共享字段加锁、不要依赖单线程假设）。
- 因为跑在 binder 线程，这些方法**不能操作 UI**，也不能假设"和主线程串行"。

「Android 视角」SQLite 并发坑：多线程同时读写同一 SQLite 数据库，靠 `SQLiteDatabase` 的**连接池（connection pool）+ WAL** 来支撑并发；长事务会阻塞其它读写，单连接上事务是串行的。经典的"database is locked"、`SQLiteDatabaseLockedException` 多源于此。这部分深坑（WAL、连接池大小、事务边界）本篇不展开，统一放在 `androidApp/07`；本篇只点明：Provider 方法在 binder 线程并发进入，你的数据库访问要经得起并发。

### 10.2 两类 ANR / 阻塞来源

| 来源 | 触发 | 归属线程 |
|---|---|---|
| Provider 发布超时 | 进程起来后迟迟不 `publishContentProviders`（onCreate 卡死） | 提供方主线程 |
| 调用超时 | `query/update` 在 binder 线程做重活、长事务、跨进程互等 | 提供方 binder 线程 |
| 调用方等待超时 | 目标进程冷启动慢 / 死循环没 publish，调用方 `cpr.wait()` 久等 | 调用方 binder 线程 |

优化铁律：`onCreate()` 只做轻量初始化；查询/写入避免主线程重活（虽然它本来就在 binder 线程，但长事务同样拖垮调用方并可能触发调用方侧超时）。

### 10.3 Provider 进程被杀后，客户端的行为

Provider 进程不是长生不老的（LMK、崩溃、升级都会杀它）。客户端手里的 `IContentProvider` 失效后行为取决于它是 **stable 还是 unstable**：

```text
query 用的是 unstable provider：
  目标进程死亡 → 调用方收到 DeadObjectException
       └─ 框架自动重试（acquireUnstableProvider 计数，最多两次）
            ├─ 目标复活并重新 publish → 继续
            └─ 仍失败 → 把 DeadObjectException 抛给应用代码

call() 等用的是 stable provider：
  目标死亡 → 该 stable 连接计数 -1，严重时会让调用方与 Provider 的连接引用失效
```

客户端最佳实践：

- 捕获 `DeadObjectException` 后**重新 `acquireProvider` / 重试**，不要假设 Provider 长驻；
- 不要在调用方主线程同步等一个"可能冷启动"的 Provider（会 ANR）；
- 高频访问的 Provider 若频繁被杀，本质是连接（引用）没稳住或内存紧张，去看 `ContentProviderConnection` 数与低内存状态（第 12、13 章）。

### 10.4 multiprocess 与单例：进程归属的两种特例

- `android:multiprocess="true"`：系统可以把 Provider **启动在调用方进程里**（每个调用进程一份实例），从而**避免跨进程**——`onCreate` 也跑在调用方进程。默认 `false`（Provider 跑在自己声明的 `android:process`）。
- 系统单例 Provider（如 `SettingsProvider`）：直接运行在 **system_server** 进程里，全局只有一份实例，所有 App 跨进程访问它。这类 Provider 一旦在 system_server 卡住，**全系统都受影响**，排错优先级最高。

---

## 11. 单例 Provider 与系统 Provider 速查

做 vendor / 车机开发，你八成会去读或对接这些系统 Provider。下面是一张 authority 速查表（具体包名随版本/厂商有差异，以 `dumpsys package` 为准）。

| Provider | authority | 用途 | 备注 |
|---|---|---|---|
| MediaStore | `media`（媒体库）、`com.android.providers.media.documents`（文档视图） | 图片/视频/音频元数据 | 媒体文件的实际字节走 `openFile`/文件描述符，不是 Cursor |
| Settings | `settings` | 系统/安全/全局设置项 | 跑在 system_server，是典型单例 Provider；读写多走 `call()` |
| Contacts | `com.android.contacts`、`contacts` | 联系人 | 含 `ContactsContract` 各子表 |
| CallLog | `call_log` | 通话记录 | 受通话权限约束 |
| Calendar | `com.android.calendar` | 日历事件 | — |
| Downloads / DownloadManager | `downloads`、`com.android.providers.downloads.documents` | 下载管理 | — |
| ExternalStorage / Documents | `com.android.externalstorage.documents` | SAF 文档树 | 配合 `DocumentsProvider` 体系 |
| Telephony | `telephony`（及 `mms-sms` 等） | 短信/彩信 | 强权限，普通 App 不可直接访问 |
| UserDictionary | `user_dictionary` | 用户词典 | — |

使用要点：

- 系统 Provider 多带严格权限/签名约束，**不要假设能直接 `query`**；先用 `dumpsys package <provider包>` 看 `readPermission`/`exported`。
- 访问这些 Provider 同样走第 1~6 章的同一套机制（acquireProvider → Transport → CursorWindow），只是"提供方"是系统进程。
- 「Android 视角」车机里常自研 `Car*Provider` 或健康/车辆状态 Provider。写这类 Provider 时复用本篇的并发、权限、发布时序结论即可；**不臆造不存在的系统类**——若不确定某个 Car Provider 的具体 authority，请先用 `dumpsys activity providers` 在真机核实，再写进文档。

---

## 12. 平台视角：SELinux / 多用户 / dumpsys 读法

从 ROM vendor 视角，CP 不止是 App 间的事，还牵动 SELinux、PMS、多用户隔离。

### 12.1 SELinux 与 PMS 层面

- **SELinux**：Provider 的跨进程访问同样受 sepolicy 约束。预装 Provider 要对外暴露，其**进程 domain** 必须拥有对目标（system_server 的 binder / service_manager 查找、或具体数据文件的 `file` 读）的允许规则。典型坑：FileProvider 分享文件时，源文件所在目录的 SELinux 上下文（如 `sdcardfs`/`fuse`、`vendor` 分区文件）必须允许授予的 `target_uid` 读取，否则即便 Java 层授权成功，`openFile` 仍被 `avc: denied` 拦下。排错看 kernel log 里的 `avc: denied { read }`。
- **PMS**：authority 全局唯一性在 PMS 扫描/安装期校验（第 5 章的 `INSTALL_FAILED_CONFLICTING_PROVIDER` 就来自这里）。预装 Provider 的权限放行常通过**签名权限（signature permission）+ sharedUserId** 在 PMS / 权限层完成——两个同签名的系统应用可共享权限，避免逐包显式授权。

### 12.2 多用户下 Provider 的 userId 归属

`ContentResolver` 是**按 user 语义**工作的：

- URI 可编码 userId：`content://<userId>@authority/path`（如 `content://10@media` 指 user 10 的 media）。`acquireProvider` 用 `uri.getUserId()` 或调用方自身 userId 决定去哪个 user 空间找 Provider。
- `ContentProviderRecord` 携带 `userId`；**默认 user 0 的 Provider 不会被 user 10 直接访问**，除非系统 Provider 显式处理多 user（如带 `android:singleUser`，或对每 user 各建实例）。
- SettingsProvider 这类系统 Provider 内部按 user 分库存储，`content://settings/...` 在 user 10 下访问的是 user 10 的设置，天然隔离。
- 车机多用户（司机/乘客、工作资料）场景下，跨 user 访问 Provider 必须走显式跨用户授权或系统桥接，否则拿到的是空数据或 `SecurityException`。多用户机制本身见 `19_多用户`。

### 12.3 dumpsys activity providers 逐字段读法

这是排 Provider 故障的第一命令：

```bash
adb shell dumpsys activity providers          # 全量
adb shell dumpsys activity providers <auth>   # 过滤某个 authority
```

输出结构（按区块）：

- `Published Providers`：已发布的 authority → `ContentProviderRecord` 摘要（authority、包名、进程、uid、userId）。
- `Singletons`：系统单例 Provider（跑在 system_server 的，如 settings）。
- `Active Providers`（`mProviders`）：所有活跃 CPR 的详细字段：
  - `authority` / `packageName` / `processName` / `uid` / `userId`；
  - `provider`：Transport（Binder 桩）指针；
  - `app`：所在 `ProcessRecord`（若为空说明进程没起来）；
  - `connections`：`ContentProviderConnection` 列表——每条含 client 进程、引用计数、**stable 计数**；这是判断"Provider 会不会被 LMK 杀"的关键（有稳定连接通常保活）；
  - `launchCount` / `inLaunchingProviders` / `hasConnection`。
- `mLaunchingProviders`：**正在拉起、尚未 publish 的 CPR 列表**——如果某个 authority 一直出现在这里，说明目标进程起不来或 `onCreate` 卡死（对应第 13 章"query 卡住"）。

读图口诀：**看 `app` 是否为空（进程在不在）、看 `connections` 有没有稳定连接（会不会被杀）、看 `mLaunchingProviders` 有没有滞留（卡在拉起/发布）。**

---

## 13. 常见问题排查表

> 列顺序：现象 → 原因 → 章节 → 第一命令。命令里 `<auth>`/`<pkg>` 换成实际值。

| 现象 | 原因 | 章节 | 第一命令 |
|---|---|---|---|
| `INSTALL_FAILED_CONFLICTING_PROVIDER` | 两个包声明同一 authority | 5.2 | `adb shell pm list packages -f \| grep <auth>` |
| `query` 卡住/长时间无返回 | Provider 进程起不来，或 `onCreate` 卡死在 launching | 3、4 | `dumpsys activity providers \| grep -A6 <auth>`（看是否 `inLaunchingProviders`） |
| `query` 偶发极慢（冷启动后） | 目标进程冷启动 + Provider.onCreate 重活 | 3.2、4 | 抓 `am_activity_launch_time` / `systrace` 看 provider 进程 |
| `CursorWindowAllocationException: Row too big` | 单行超 CursorWindow 容量 | 6.4 | 拆查询列 / 不在结果集放大 BLOB |
| `SecurityException: Permission Denial`（读） | 缺 `readPermission` 或未 `exported` | 5.3 | `dumpsys package <pkg> \| grep -i -A2 provider` |
| `SecurityException`（访问临时 URI） | 未 `grantUriPermission` 或 Intent 缺 `FLAG_GRANT_*` | 9 | 检查 Intent flag、`grantUriPermissions` 属性 |
| `IllegalArgumentException: Failed to find configured root`（FileProvider） | path 未在 `file_paths.xml` 配置 | androidApp/07 | 看异常提示的具体 path |
| `ContentObserver` 收不到回调 | 没调 `notifyChange` / `userId` 不一致 / observer 进程死 | 8 | `dumpsys activity providers` + 确认 `notifyChange` 已调用 |
| `applyBatch` 整体回滚/部分失败 | 某 operation 抛 `OperationApplicationException` | 7.2 | 捕获异常，逐 op 打印定位 |
| 多进程 Provider 死锁（两侧 binder 卡死） | 进程间同步互等 | 4.2 | `adb shell kill -3 <pid>` 看 binder 线程栈 |
| 后台拉起 Provider 失败 | Android 12+ 后台启动限制 | 4 | 看 `am` 日志，改用 foregroundService / 提升可见性 |
| `call()` 返回值被截断/为空 | Android 14+ CALL redaction | 7.3 | 用特权/签名权限调用，或自查返回字段 |
| 权限已配置仍被拒 | `path-permission` 更细限制覆盖全局权限 | 5.3 | 检查 `<path-permission>` 配置 |
| Provider 进程频繁被杀、反复冷启 | 无稳定连接 / 低内存（LMK） | 10、12 | `dumpsys activity providers` 看 `connections` 数与低内存状态 |
| 多用户下访问不到数据 | `userId` 不一致 / 未跨用户授权 | 12.2 | 用 `content://<userId>@<auth>` 核对 userId |

---

## 14. 读源码路线

下场读 AOSP 时，按这四条链路顺藤摸瓜，比通读全文件高效得多。

### 路线一：acquireProvider → AMS.getContentProvider → 拉起并发布（对应第 4 章）

```text
ContentResolver.acquireProvider / acquireUnstableProvider
  └─ ApplicationContentResolver.acquireProvider        (ContextImpl 内部，委托给 ActivityThread)
       └─ ActivityThread.acquireProvider               (先查 mProviderByAuthority 缓存)
            └─ IActivityManager.getContentProvider     (跨进程 → AMS)
                 └─ ContentProviderHelper.getContentProviderImpl
                      ├─ 找 ProviderInfo（问 PMS）
                      ├─ getProcessRecordLocked → 空则 startProcessLocked
                      └─ cpr.wait() 直到目标 publishContentProviders
```

### 路线二：query 的 Cursor 窗口回路（对应第 1、6 章）

```text
ContentResolver.query → acquireUnstableProvider → unstableProvider.query
  └─ ContentProvider.Transport.query                  (权限校验 + 转调)
       └─ ContentProvider.query → 真实 Cursor
            └─ new CursorToBulkCursorAdaptor(cursor)  (服务端 IBulkCursor 桩)
                 ▼ 跨进程
            BulkCursorToCursorAdaptor                 (客户端 Cursor 包装)
                 └─ 移动时 fillWindow → IBulkCursor.getWindow（ashmem 共享内存）
```

### 路线三：ContentObserver 通知链（对应第 8 章）

```text
registerContentObserver → IContentService.registerContentObserver
  └─ ContentService.registerContentObserver → ObserverNode 树挂载
notifyChange → IContentService.notifyChange
  └─ ContentService.notifyChange → 遍历 ObserverNode → observer.onChange (跨进程回调)
```

### 路线四：发布时序（对应第 3 章）

```text
ActivityThread.handleBindApplication
  ├─ makeApplication → Application.attachBaseContext
  ├─ installContentProviders → 反射建实例 → attachInfo → ContentProvider.onCreate
  ├─ publishContentProviders → IActivityManager.publishContentProviders
  │     └─ ContentProviderHelper.publishContentProvidersLocked → 建/更新 CPR → cpr.notifyAll()
  └─ callApplicationOnCreate → Application.onCreate
```

读这四条时，重点盯 `ContentProviderHelper`（AMS 侧总管）、`ActivityThread`（两侧各一份）、`ContentProvider.Transport`（权限与转发）、`ContentService`（观察者树）四个类。

---

## 15. 源码路径速查 + 一图总结 + 关联阅读

### 15.1 源码路径速查表

| 内容 | 路径 |
|---|---|
| `ContentResolver`（客户端入口） | `frameworks/base/core/java/android/content/ContentResolver.java` |
| `ApplicationContentResolver` | `frameworks/base/core/java/android/app/ContextImpl.java`（内部类） |
| `ContentProvider`（组件基类） | `frameworks/base/core/java/android/content/ContentProvider.java` |
| `IContentProvider`（AIDL 契约） | `frameworks/base/core/java/android/content/IContentProvider.aidl` |
| `Transport`（Binder 桩） | `ContentProvider.java` 内部类 `Transport` |
| `ActivityThread`（安装/获取/发布） | `frameworks/base/core/java/android/app/ActivityThread.java` |
| `ContentProviderHelper`（AMS 总管） | `frameworks/base/services/core/java/com/android/server/am/ContentProviderHelper.java` |
| `ContentProviderRecord` / `Connection` | `frameworks/base/services/core/java/com/android/server/am/ContentProviderRecord.java` |
| `ContentService`（观察者树） | `frameworks/base/services/core/java/com/android/server/content/ContentService.java` |
| `CursorWindow`（共享内存窗口） | `frameworks/base/core/java/android/database/CursorWindow.java` |
| `BulkCursorToCursorAdaptor`（客户端） | `frameworks/base/core/java/android/database/BulkCursorToCursorAdaptor.java` |
| `CursorToBulkCursorAdaptor`（服务端） | `frameworks/base/core/java/android/database/CursorToBulkCursorAdaptor.java` |
| `ContentProviderOperation`（批量） | `frameworks/base/core/java/android/content/ContentProviderOperation.java` |
| `UriGrantsManagerService`（临时授权） | `frameworks/base/services/core/java/com/android/server/uri/UriGrantsManagerService.java` |
| FileProvider（App 侧） | `androidx.core.content.FileProvider`（详见 `androidApp/07`） |

### 15.2 一图总结

```text
                content://<authority>/<path>/<id>
                          │
        ┌─────────────────┼─────────────────────────────────────────┐
        │ ① 数据在哪        │ ② 谁能读          │ ③ 进程活着吗          │
        ▼                 ▼                   ▼
   PMS 解析 authority   Transport 入口        acquireProvider
   → ProviderInfo       enforceRead/Write     → 命中缓存? 直接返
                        + path-permission     → 否: AMS 拉起进程
                        + 临时 URI 授权         → cpr.wait() 直到 publish
        │                                                          │
        ▼                                                          ▼
   ┌──────────────── 调用方进程 ────────────────┐   ┌──── 提供方进程 ────┐
   │ ContentResolver → ApplicationContentResolver│   │ ContentProvider   │
   │   → ActivityThread.acquireProvider          │   │   .onCreate(主线程)│
   │   → IContentProvider(unstable) proxy ───────┼──▶│ Transport(IBinder)│
   │ BulkCursorToCursorAdaptor ◀── CursorWindow ─┼───│   query→真实Cursor│
   │   (ashmem 共享内存, 窗口化分批)              │   │   → publish 给 AMS │
   └────────────────────────────────────────────┘   └────────────────────┘
                           │                              │
                           ▼                              ▼
                    system_server: AMS / ContentProviderHelper（寻址·拉起·缓存·等 publish）
                                  ContentService（ContentObserver 树）
                                  UriGrantsManager（临时授权记录）
```

**一句话记忆链**：
> 找（Resolver→AMS 按 authority 寻址）→ 拉（不在就 fork，onCreate 早于 App.onCreate）→ 鉴（Transport 卡权限/临时授权）→ 干（Provider 在 binder 线程真查询）→ 回（CursorWindow 走共享内存窗口回流）→ 告（ContentObserver 经 ContentService 跨进程通知）。

### 15.3 关联阅读与分工

- **`02_Binder`**：本篇所有跨进程调用的底层契约（AIDL、Binder 线程池、DeadObject）在此展开。
- **`05_AMS` / `06_AMS`**：AMS 概览与进程管理，本篇的 `startProcessLocked`、进程记录都建立在那两篇之上。
- **`19_多用户`**：第 12.2 节的 `userId` 隔离、跨用户授权，多用户机制细节在那篇。
- **`androidOthers/存储篇`**：存储权限策略、ScopedStorage、媒体文件访问的权限大背景，本篇第 5、9 章的权限与文件 URI 与之衔接。
- **`androidApp/07`**：FileProvider 的 **App 侧用法**（`file_paths.xml`、authorities 命名、分享 Intent 写法）、SQLite 并发与 `SQLiteDatabase` 连接池深坑——本篇已点明边界，具体落到那篇。

本篇分工声明：AMS 概览见 05/06，FileProvider 的 App 侧用法见 `androidApp/07`，存储权限策略细节见 `androidOthers/存储篇`；本篇只聚焦 **ContentProvider 机制本身**（角色、发布、拉起、URI、Cursor、授权、并发、系统 provider、平台/多用户视角、排查与源码路线）。

---

> 文档版本说明：Provider 生命周期本身在 Android 各版本间无重大变化；本篇标注的版本差异集中在 `call()` 返回值 redaction（Android 14 / API 34）、`android:exported` 强制显式声明（Android 12 / API 31）、后台拉起限制（Android 12+）与 CursorWindow 默认大小（长期 2 MB，Android 11 起可配置）。不确定签名处均以"相关方法/一类"表述，避免写死内部 API。

