# SystemUI 机制详解：从状态栏到锁屏与通知面板

> 这篇从用户每天都会看到的现象切入：状态栏图标为什么不显示，通知为什么不弹 heads-up，锁屏上为什么弹不出 Activity，下拉面板为什么行为异常，多屏车机为什么有两套状态栏，自己新加的一个 SystemUI 类为什么根本没被启动。目标是把"看得见的系统界面"和 SystemUI 进程内部的启动装配、窗口、状态机、通知流水线、Keyguard 串成一条链。
> 目标读者：已经了解 AMS/ATMS/PMS/WMS 和通知机制的基本职责，希望进一步理解 SystemUI 进程结构、系统栏窗口、图标链路、通知展示流水线、锁屏状态机以及车机定制点的开发者。
>
> **版本说明**：SystemUI 是 AOSP 里迭代最快的模块之一，类名和架构在 Android 10 → 14 之间改动很大。本文以 Android 13/14 为主线，遇到明显改名的地方会标注版本线索（例如 `StatusBar` → `CentralSurfaces`、`NotificationEntryManager` → `NotifPipeline`）。看第三方博客时务必先对版本，否则很容易把两条不同年代的链路当成一回事。

---

## 目录

1. [先建立直觉：SystemUI 是什么](#1先建立直觉systemui-是什么)
2. [SystemUI 和 WMS、ATMS、NMS 的关系](#2systemui-和-wmsatmsnms-的关系)
3. [从用户现象看 SystemUI](#3从用户现象看-systemui)
4. [SystemUI 涉及到的核心类](#4systemui-涉及到的核心类)
5. [SystemUI 进程是怎么起来的](#5systemui-进程是怎么起来的)
6. [系统栏窗口：状态栏和导航栏怎么挂到 WMS 上](#6系统栏窗口状态栏和导航栏怎么挂到-wms-上)
7. [StatusBarState：状态栏和面板的状态机](#7statusbarstate状态栏和面板的状态机)
8. [状态栏图标链路：IStatusBarService 与 CommandQueue](#8状态栏图标链路istatusbarservice-与-commandqueue)
9. [系统栏与 Insets、沉浸式、手势导航](#9系统栏与-insets沉浸式手势导航)
10. [通知在 SystemUI 侧的流水线](#10通知在-systemui-侧的流水线)
11. [heads-up 为什么弹，为什么不弹](#11heads-up-为什么弹为什么不弹)
12. [Keyguard：锁屏状态机与解锁流程](#12keyguard锁屏状态机与解锁流程)
13. [QS 快捷设置](#13qs-快捷设置)
14. [Recents：UI 其实不在 SystemUI](#14recentsui-其实不在-systemui)
15. [音量面板、截屏、全局动作、隐私指示器](#15音量面板截屏全局动作隐私指示器)
16. [多屏、多用户与车机定制](#16多屏多用户与车机定制)
17. [新架构：Dagger、Scene、Compose 与协程](#17新架构daggerscenecompose-与协程)
18. [常见问题与排查方法](#18常见问题与排查方法)
19. [第三方系统常见修改点](#19第三方系统常见修改点)
20. [读源码的推荐路线](#20读源码的推荐路线)
21. [关键源码路径速查](#21关键源码路径速查)
22. [一图总结](#22一图总结)

---

## 1. 先建立直觉：SystemUI 是什么

**一句话：SystemUI 是一个特殊的系统 App，它把 Framework 服务里的系统状态翻译成用户能看见、能点的界面。**

它不是 Framework Service（不跑在 `system_server` 里），也不是普通 App：

```text
system_server 进程                 com.android.systemui 进程
  AMS / ATMS / WMS                  SystemUIApplication
  NMS / PowerManager   ──Binder──►   StatusBar / Keyguard / Shade
  StatusBarManagerService            QS / Volume / Screenshot
        ▲                                  │
        └──────── 反向 Binder 回调 ─────────┘
              CommandQueue.Callbacks
```

它负责的界面大致有：

- 状态栏 StatusBar（含通知面板 NotificationShade）。
- 导航栏 NavigationBar（三键 / 手势）。
- 锁屏 Keyguard（Bouncer、PIN/图案/密码、生物识别交互）。
- 快捷设置 QS。
- 音量面板、截屏 UI、长按电源的全局动作菜单。
- 隐私指示器（麦克风/摄像头绿点）。
- 壁纸（`ImageWallpaper` 在 SystemUI 进程里）。

可以这样记：

> Framework 服务负责"系统规则和状态"，SystemUI 负责"把状态变成界面"，WMS 负责"把这些界面当窗口管起来"。

---

## 2. SystemUI 和 WMS、ATMS、NMS 的关系

```text
NMS（NotificationManagerService）
  管通知数据、排序、渠道、拦截
        │ Binder（NotificationListenerService 回调）
        ▼
SystemUI
  管展示：通知列表、heads-up、锁屏通知、通知面板
        │ 也是窗口
        ▼
WMS
  管 SystemUI 的状态栏/导航栏/Shade 窗口，以及系统栏 Insets
        ▲
        │ Keyguard 状态、Task 信息
ATMS
  和 SystemUI 协作锁屏状态、最近任务、全屏 intent
```

分工表：

| 模块 | 负责什么 | 不负责什么 |
|---|---|---|
| NMS | 通知接收、渠道、排序、免打扰、拦截 | 不决定通知长什么样 |
| SystemUI | 通知展示、系统栏界面、锁屏 UI、QS、音量 | 不决定通知能否发布 |
| WMS | SystemUI 窗口的层级、大小、Insets、焦点 | 不关心状态栏里画什么图标 |
| ATMS | Keyguard 状态判定、Task、Activity 启动许可 | 不画锁屏界面 |
| PowerManager | 亮灭屏、Wakefulness、Doze | 不画 AOD 界面 |

关键认知：**SystemUI 大部分界面本身就是窗口**，所以显示层面的问题一定要同时看 WMS；**通知数据来自 NMS**，所以"通知不显示"要先分清是数据没到还是 UI 没画；**锁屏能不能显示某个 Activity 是 ATMS 判的**，不是 SystemUI 判的。

---

## 3. 从用户现象看 SystemUI

| 用户/测试侧现象 | 更可能涉及的 SystemUI 逻辑 |
|---|---|
| 状态栏不显示、被遮挡 | 状态栏窗口未 add、display 错、`StatusBarState`、WMS 层级 |
| 自定义图标（信号、温度）不出现 | slot 未在 framework 侧注册、`IconManager` 没有对应 slot |
| 图标颜色和背景不匹配（黑底黑字） | `DarkIconDispatcher` / `LightBarController` |
| 通知不弹 heads-up | 打断判定逻辑、免打扰、全屏条件、驾驶策略 |
| 通知在锁屏看不到 | 锁屏通知可见性判定、锁屏隐私设置 |
| 下拉面板拉不下来 / 拉下来关不掉 | 面板手势、窗口触摸处理、disable flags |
| 状态栏下拉时内容不跟手、卡顿 | Scene 过渡、主线程耗时、后台执行器排队 |
| 锁屏上弹不出 Activity | ATMS `KeyguardController`、`showWhenLocked`、全屏 intent |
| 锁屏 PIN 输入正确但仍不解锁 | Bouncer/生物识别状态、Scrim 状态、`keyguardGoingAway` 卡住 |
| 手势上滑进不了最近任务 | 手势识别、Launcher 的 `IOverviewProxy`、WMS 动画控制 |
| 多屏车机第二块屏没有状态栏 | per-display 子组件未创建、bar 未注册到该 display |
| 音量键不弹音量面板 | 音量键事件未到 SystemUI，或音量控制器未连接 |
| SystemUI 整体消失（无状态栏无导航栏） | SystemUI 进程崩溃/被杀，自恢复失败 |

学习入口建议就顺着这些现象走：每一条都能在 `dumpsys` 和 logcat 里找到对应状态。

---

## 4. SystemUI 涉及到的核心类

### 4.1 SystemUI 侧类（进程内）

| 类 | 作用 |
|---|---|
| `SystemUIApplication` | SystemUI 的 `Application`，负责组件装配与启动 |
| `SystemUIService` | 进程入口 Service，被 system_server 拉起后触发装配 |
| `SystemUI` / `CoreStartable` | 各功能模块的基类/接口，`start()`、`onBootCompleted()` |
| `CentralSurfaces`（旧名 `StatusBar`） | 状态栏 + 通知面板总协调者 |
| `CentralSurfacesImpl` | 具体实现，几乎所有子系统都挂在它上面 |
| `StatusBarWindowController` | 创建状态栏窗口 |
| `NotificationShadeWindowController` | 创建通知面板窗口（新版与状态栏分离） |
| `StatusBarIconControllerImpl` | 系统图标管理（slot → `IconManager`） |
| `NotificationShadeWindowViewController` | 通知面板窗口的触摸事件处理 |
| `NotificationPanelViewController` | 下拉面板的显示、展开、手势 |
| `NotificationStackScrollLayoutController` | 通知列表布局 |
| `KeyguardViewMediator` | 锁屏总协调者，状态机所在 |
| `KeyguardUpdateMonitor` | 锁屏状态事件源（生物识别、SIM、用户切换） |
| `KeyguardStateController` | 对外只读的锁屏状态视图 |
| `ScrimController` / `ScrimState` | 锁屏/面板背后的遮罩状态机 |
| `QSTileHost` / `QSTileImpl` | 快捷设置 tile 的创建与生命周期 |
| `VolumeDialogControllerImpl` | 音量面板控制器 |
| `OverviewProxyService` | 与 Launcher 最近任务（`IOverviewProxy`）的桥 |
| `UserSwitcherController` | 多用户切换 |

### 4.2 Binder 接口

| 接口 | 谁实现 | 作用 |
|---|---|---|
| `IStatusBarService` | `StatusBarManagerService`（framework） | App/系统服务访问状态栏能力，如 `setIcon`、`disable`、`expandNotificationsPanel` |
| `CommandQueue.Callbacks` | `CommandQueue`（SystemUI） | **反向**回调：framework 通知 SystemUI 更新图标、禁用状态、展开面板 |
| `IKeyguardService` | `KeyguardService`（SystemUI） | SystemUI 向 framework 汇报锁屏状态、接收熄屏/亮屏/遮挡事件 |
| `KeyguardServiceDelegate` | framework（`system_server`） | `system_server` 侧访问 Keyguard 的代理 |
| `IOverviewProxy` | Launcher3 | Launcher 的最近任务实现，SystemUI 通过它协作 |
| `ISystemUiProxy` | SystemUI | SystemUI 向 Launcher 暴露能力（截屏、导航栏颜色等） |
| `ITakeScreenshotService` | `TakeScreenshotService`（SystemUI） | 提供截屏服务 |

### 4.3 Framework / system_server 侧类

| 类 | 作用 |
|---|---|
| `StatusBarManagerService` | `IStatusBarService` 的实现，持有 `StatusBarIconList` 和 disable flags |
| `StatusBarIconList` | 所有系统图标 slot 的清单，slot 来自 framework-res 的 `config_statusBarIcons` |
| `DisplayPolicy`（WMS） | 系统栏窗口的布局、Insets、能见度策略 |
| `InsetsStateController`（WMS） | 系统栏/IME/cutout 的 Insets 状态源 |
| `KeyguardController`（ATMS） | 判定"锁屏状态下能不能启动/显示这个 Activity" |
| `ActivityRecord`（ATMS） | 持有 `ShowWhenLocked`、可见性等状态 |
| `PhoneWindowManager` | 音量键、电源键、沉浸式 flags 的汇总者 |
| `ScreenshotHelper` | 触发截屏，绑定 SystemUI 的截屏服务 |

---

## 5. SystemUI 进程是怎么起来的

这是现有资料最容易漏、但二次开发最容易踩坑的一节。

### 5.1 system_server 拉起 SystemUIService

`SystemServer` 在 `startOtherServices()` 阶段调用 `startSystemUi()`，代码非常短：

```java
// SystemServer.java（精简示意）
private void startSystemUi(Context context, SystemServiceManager systemServiceManager) {
    Intent intent = new Intent();
    intent.setComponent(new ComponentName(
            "com.android.systemui", "com.android.systemui.SystemUIService"));
    intent.addFlags(Intent.FLAG_DEBUG_TRIAGED_MISSING);
    context.startServiceAsUser(intent, UserHandle.SYSTEM);
}
```

几个要点：

- 用的是 **`startServiceAsUser(..., SYSTEM)`**，所以 SystemUI 天然运行在 system user 的身份下。
- 启动时机在 `system_server` 把大部分服务都起完之后，因为此时状态栏依赖的东西（AMS、WMS、NMS、PMS）都已在位。
- SystemUI 一旦起来就长期驻留，它的状态栏是"系统看起来正常"的视觉基线。

### 5.2 SystemUIApplication 负责真正的装配

`SystemUIService.onCreate()` 本身几乎不做事，只是触发 Application 层装配：

```java
// SystemUIService.java（精简示意）
@Override
public void onCreate() {
    super.onCreate();
    ((SystemUIApplication) getApplication()).startServicesIfNeeded();
}
```

`SystemUIApplication` 的行为可以概括成：

```text
SystemUIApplication.startServicesIfNeeded(tag)
  ├─ 若已启动（mServicesStarted）直接返回
  ├─ 读取组件列表：R.array.config_systemUIServiceComponents
  ├─ 逐个通过工厂创建实例、依赖注入、调用 start()
  └─ 记录耗时，供启动性能分析
```

组件列表就在 SystemUI 自己的资源里，形如：

```xml
<!-- packages/SystemUI/res/values/config.xml -->
<string-array name="config_systemUIServiceComponents" translatable="false">
    <item>com.android.systemui.media.MediaProjectionAppSelector</item>
    <item>com.android.systemui.screenshot.ScreenshotServiceComponent</item>
    ...
    <item>com.android.systemui.statusbar.phone.StatusBar</item>
    <item>com.android.systemui.keyguard.KeyguardViewMediator</item>
    <item>com.android.systemui.volume.VolumeUI</item>
    <item>com.android.systemui.recents.Recents</item>
</string-array>
```

**结论：一个 SystemUI 模块要被启动，必须出现在这个数组里。** 这是"我加了类但没生效"的第一嫌疑人。

### 5.3 CoreStartable 与 Dagger 装配

早期 SystemUI 的模块基类是抽象类 `SystemUI`，Android 13 起改成接口 `CoreStartable`：

```java
// 精简示意
public interface CoreStartable extends Dumpable {
    default void start() {}
    default void onBootCompleted() {}
}
```

与之配套的是 Dagger 的 `@ClassKey` 多绑定，把"类名"映射成"可创建对象"：

```java
// 精简示意：为每个 CoreStartable 建一个 map 条目
@Binds
@IntoMap
@ClassKey(KeyguardViewMediator.class)
CoreStartable bindKeyguardViewMediator(KeyguardViewMediator sysui);
```

于是 `startServicesIfNeeded()` 拿到字符串类名后，可以这样构造：

```java
CoreStartable component = mSysUIComponent.createCoreStartable(clazz);
component.start();
```

**所以新增一个模块要动两处**：组件数组里加类名 + Dagger 里加 `@IntoMap @ClassKey` 绑定。少一个就是"编译通过但永远不启动"。

另外 SystemUI 的工厂本身是可替换的：framework-res 里的 `config_systemUIFactoryComponent` 指向 `com.android.systemui.SystemUIFactory`，**车机的 `CarSystemUIFactory` 就是靠这个字符串 overlay 替换进去的**（见第 16 节）。

### 5.4 第二阶段：BootComplete

有些模块不能在系统还没启动完时跑（比如要读 Settings、要等用户解锁），于是有第二次启动：

```text
ACTION_BOOT_COMPLETED
  └─ BootCompleteReceiver
       └─ SystemUIApplication.startServicesIfNeeded("BootComplete")
            └─ 对已启动的 CoreStartable 调 onBootCompleted()
```

排查"某功能开机后过一会儿才生效"时，注意确认它属于哪个阶段。

### 5.5 为什么"我加的类没启动"

按这个顺序查：

1. 类名是否写进了 `config_systemUIServiceComponents`。
2. 是否在 Dagger 里做了 `@IntoMap @ClassKey` 绑定。
3. 是否满足启动前置条件（有些模块依赖 `ActivityManager.isSystemReady()`、`UserManager.isUserUnlocked()`）。
4. 是否被 `config` 里的开关布尔值关掉了。
5. 启动时抛异常——单个模块 `start()` 抛异常通常只打 log，不一定崩整个进程，容易被忽略。

### 5.6 进程优先级与崩溃恢复

- SystemUI 是 `system_server` 启动的常驻进程，oom adj 很低，正常不会被系统回收。
- 但它**不是** `persistent` 进程。真的被杀了要靠重新拉起或系统重启。
- SystemUI 崩溃的典型表现：状态栏和导航栏一起消失、壁纸闪一下、通知面板拉不出来。抓 log：

```bash
adb logcat -b crash | grep -i systemui
adb logcat | grep -i "Force finishing activity.*systemui"
adb shell dumpsys activity processes | grep -i systemui   # 看 adj 和 pid
```

---

## 6. 系统栏窗口：状态栏和导航栏怎么挂到 WMS 上

### 6.1 状态栏窗口

`StatusBarWindowController` 负责创建状态栏窗口，本质是一次 `WindowManager.addView()`：

```java
// 精简示意
WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
        MATCH_PARENT, statusBarHeight, TYPE_STATUS_BAR, 0, PixelFormat.TRANSLUCENT);
lp.gravity = Gravity.TOP;
lp.setTitle("StatusBar");
lp.flags = FLAG_NOT_FOCUSABLE | FLAG_HARDWARE_ACCELERATED | FLAG_LAYOUT_IN_SCREEN;
windowManager.addView(mStatusBarWindowView, lp);
```

在 `dumpsys window` 里它长这样（这也是最快的验证方式）：

```text
Window #3 Window{... u0 StatusBar}:
    mOwnerUid=1000 ... mAttrs={(0,0)(fillx72) ty=STATUS_BAR fmt=TRANSLUCENT}
```

要点：

- **用的是 SystemUI 自己的 window token**，不依附任何 Activity，所以不存在 BadTokenException 那类问题。
- 窗口高度通常等于 `status_bar_height` 资源（可 overlay）。
- 它是 `FLAG_NOT_FOCUSABLE`，**不抢焦点**：状态栏能点，但输入焦点仍在 App。
- 状态栏窗口的大小/可见性会被 `StatusBarState` 影响：锁屏下可能被要求整条显示或整条隐藏。

### 6.2 NotificationShade 独立窗口

新版 AOSP 把通知面板从状态栏窗口里拆出来，单独一个 `NotificationShadeWindowController`：

- 旧结构：状态栏窗口很高，下拉只是把窗口内的面板视图移上来。
- 新结构：`NotificationShadeWindowView` 单独一个窗口，状态栏只是它的一部分。

**实践意义**：`dumpsys window` 里找 `NotificationShade` 还是找 `StatusBar` 取决于版本；改下拉面板前先确认自己的 ROM 是哪种结构。

### 6.3 导航栏窗口

`NavigationBar` 持有自己的窗口（`TYPE_NAVIGATION_BAR`，title `NavigationBar`），负责：

- 三键 / 双键 / 手势模式的切换。
- 按键图标与颜色。
- 手势模式下的 Home 条（`NavigationHandle`）与返回手势区域（`EdgeBackGestureHandler`）。

### 6.4 每个 display 一套

SystemUI 的窗口是**按 display 创建**的。多屏/车机场景下，每个 display 都可能需要一套状态栏和导航栏：

```text
Display 0（主屏）
  ├─ 状态栏窗口
  └─ 导航栏窗口
Display 1（副驾/后排屏）
  ├─ 状态栏窗口（另一套实例）
  └─ 导航栏窗口
```

实现上通过按 display 划分子组件和限定符完成（`@PerDisplay`、per-display 子组件，见第 16、17 节）。**"副屏没有状态栏"多数是这套机制没跑起来，而不是 WMS 把窗口丢了。**

### 6.5 窗口层级和壁纸

从下往上的大致顺序（简化）：

```text
壁纸（ImageWallpaper / WallpaperManagerService）
   ▼
App 窗口（Activity / Dialog / 悬浮窗）
   ▼
系统栏：状态栏 / 导航栏 / 通知面板
   ▼
锁屏 Bouncer、Keyguard 相关高亮层
   ▼
系统级弹窗：全局动作、权限弹窗、ANR 对话框
```

壁纸虽然"在 App 后面"，但它的宿主是 SystemUI 进程里的 `ImageWallpaper`，图层由 WMS 侧的 `WallpaperController` 管理——所以上滑时壁纸的视差、锁屏时的缩放，都是 WMS 在改它的 alpha/offset。

---

## 7. StatusBarState：状态栏和面板的状态机

这是 SystemUI 里最重要的一个状态，很多"下拉面板行为诡异"的问题本质上都是它切错了。

```java
// com.android.systemui.statusbar.StatusBarState
public static final int SHADE = 0;          // 常态：桌面/App 上，下拉就是通知面板
public static final int KEYGUARD = 1;       // 锁屏：面板和锁屏一体，不能随便下拉
public static final int SHADE_LOCKED = 2;   // 解锁后仍在锁屏视觉上（点通知进入 App 之前）
```

状态大致这样迁移：

```text
        SHADE ──熄屏再亮屏──► KEYGUARD
          ▲                      │
          │                 指纹/密码解锁
    回到桌面/App                  │
          │                      ▼
        SHADE ◄── 点通知进 App ── SHADE_LOCKED
```

谁在驱动它：

- `StatusBarStateController` / `StatusBarStateControllerImpl`：写状态、通知监听者。
- `KeyguardViewMediator`：锁屏显示/隐藏时改状态。
- `KeyguardUpdateMonitor`：生物识别成功、用户切换时参与判定。
- `NotificationPanelViewController`：根据状态决定下拉范围、是否允许完全展开。

新版本还有一个统一状态 `SysUiState`，把"面板展开""锁屏显示""有 heads-up"等做成一组 flag，供状态栏核心内部判断。

**排查建议**：面板行为异常时，先确认当前 `StatusBarState`，再确认 Keyguard 的 showing/occluded 组合，最后才看 View 层。

---

## 8. 状态栏图标链路：IStatusBarService 与 CommandQueue

这是理解"系统栏就是一个 Binder 服务前端"的关键，也是车机加自定义图标（温度、雷达、摄像头状态）的必经之路。

### 8.1 一条 setIcon 的完整链路

发送方（App 或系统服务）：

```java
// 需要系统权限（@SystemApi / @hide，平台签名或 system UID 才可用）
StatusBarManager statusBarManager = context.getSystemService(StatusBarManager.class);
StatusBarIcon icon = new StatusBarIcon(user, pkgName, resId, level, number, contentDescription);
statusBarManager.setIcon("my_car_temp", icon);
statusBarManager.setIconVisibility("my_car_temp", true);
```

链路：

```text
StatusBarManager.setIcon(slot, icon)
  └─ IStatusBarService.setIcon()                     ← Binder 到 system_server
       └─ StatusBarManagerService.setIcon()
            ├─ 校验权限
            ├─ 校验 slot 是否在 StatusBarIconList 里
            ├─ 更新 StatusBarIconList
            └─ CommandQueue.Callbacks.setIcon(slot, icon)   ← 反向 Binder 回调 SystemUI
                 └─ StatusBarIconControllerImpl.setIcon()
                      └─ 找到该 slot 的 IconManager
                           └─ setIcon() → 更新 StatusBarIconView
```

三个必须记住的点：

- **图标数据存在 framework 侧**（`StatusBarManagerService` 里的 `StatusBarIconList`），SystemUI 只是渲染方。所以 SystemUI 重启后能自动恢复图标。
- **slot 是协议的关键**：用字符串标识一块可更新的区域。slot 不在清单里，`setIcon` 直接失败。
- **控制流是双向的**：正向是 `IStatusBarService`，反向是 `CommandQueue.Callbacks`。

### 8.2 CommandQueue.Callbacks 是什么

`CommandQueue` 是 SystemUI 实现的一组回调，framework 用它"命令"SystemUI 改界面（节选，不同版本有增减）：

| 回调 | 作用 |
|---|---|
| `setIcon(slot, icon)` / `removeIcon(slot)` | 增删系统图标 |
| `setIconVisibility(slot, visible)` | 图标可见性 |
| `disable(what, userId, token, diff)` | 禁用状态（App 侧沉浸式请求的汇总结果） |
| `animateExpandNotificationsPanel()` | 展开通知面板 |
| `animateCollapsePanels(flags, force, delayed)` | 收起面板 |
| `showRecentApps()` / `toggleRecentApps()` | 最近任务 |
| `showGlobalActions()` | 长按电源的全局动作菜单 |
| `showShutdownUi()` | 关机/重启界面 |
| `setImeWindowStatus(...)` | 输入法状态 |
| `setWindowState(displayId, state)` | 系统栏窗口可见性状态 |
| `topAppWindowChanged(menuVisible)` | 前台 App 菜单键状态 |
| `showWirelessChargingAnimation()` | 无线充电动画 |
| `passThroughShellCommand(args, fd)` | 透传 shell 命令（调试命令的通道之一） |

**注意 `disable()` 这一条**：App 调 `View.setSystemUiVisibility()` / `WindowInsetsController.hide()` 想要隐藏系统栏时，请求不会直接发给 SystemUI，而是由 framework 汇总后通过 `disable()` 告诉 SystemUI"你现在处于被禁用状态"。所以排查沉浸式问题要同时看 App 请求、`DisplayPolicy` 状态和 SystemUI 的禁用状态。

### 8.3 slot 与 IconManager

- framework 侧的 slot 清单来自 framework-res：

```xml
<!-- frameworks/base/core/res/res/values/config.xml -->
<string-array name="config_statusBarIcons">
    <item>ime</item>
    <item>sync</item>
    <item>wifi</item>
    <item>mobile</item>
    <item>bluetooth</item>
    <item>volume</item>
    <item>location</item>
    ...
</string-array>
```

- SystemUI 侧为这些 slot 准备渲染位置，并分组到若干 `IconManager`（左区/右区/时钟区等，Android 12 起支持多 icon slot）。
- 每个 `IconManager` 内部维护一个 `StatusBarIconView` 列表，按 slot 顺序摆放。

### 8.4 图标深浅色

状态栏背景随 App 颜色变化时，图标要反色，否则会出现"黑底黑字"。链路：

```text
状态栏背景亮度（App 侧颜色 / 壁纸）
  └─ LightBarController / DarkIconDispatcher 计算 isDark
       └─ 通过 IWindowManager 的 appearance 接口告知 WMS
            └─ WMS 把 appearance 放进 InsetsState
                 ├─ 分发给 App：APPEARANCE_LIGHT_STATUS_BARS
                 └─ SystemUI 侧同步刷新自己的图标颜色
```

排查"图标看不清"时，先确认是 SystemUI 侧的图标颜色变了，还是 App 侧没有响应 appearance 变化。

### 8.5 车机加自定义图标的完整清单

1. framework-res 的 `config_statusBarIcons` 里加 slot 名（例如 `car_temp`）。
2. 系统服务或 App 里用 `StatusBarManager.setIcon("car_temp", icon)` 更新。
3. SystemUI 侧确认该 slot 有对应的渲染位置；没有就在 icon group 配置里补上。
4. 验权限：调用方需要系统签名或状态栏相关权限。
5. 用 dumpsys 验证：

```bash
adb shell cmd statusbar get-status-icons
adb shell dumpsys activity service com.android.systemui | grep -i icon
```

---

## 9. 系统栏与 Insets、沉浸式、手势导航

### 9.1 Insets 到底谁算的

常见误解是"SystemUI 决定了 App 能用多大区域"。实际是：

```text
SystemUI        提供"系统栏窗口存在、高度是多少"的事实（窗口 + 资源值）
      ▼
WMS DisplayPolicy / InsetsStateController
      ├─ 汇总 status bar / nav bar / IME / cutout 的 Insets
      └─ 通过 InsetsState 分发
            ▼
App 侧 WindowInsets / ViewRootImpl.dispatchApplyInsets
```

所以状态栏高度不对，可能是 overlay 的尺寸资源改了，也可能是窗口没按预期高度创建。

### 9.2 沉浸式与 disable flags

```text
App: 请求隐藏系统栏（WindowInsetsController / setSystemUiVisibility）
  └─ ViewRootImpl → IWindowSession → WMS WindowState
       └─ DisplayPolicy 汇总当前显示需求
            └─ StatusBarManagerService.setDisableFlags()
                 └─ CommandQueue.Callbacks.disable()
                      └─ SystemUI 更新图标/面板展开能力
```

排查清单：

- App 是否真的请求了（`dumpsys window` 看窗口 flags）。
- 是否被 SystemUI 的策略拒绝（车机常配置"禁止隐藏状态栏"）。
- 是否被多层窗口叠加（悬浮窗也在请求显示系统栏）。

### 9.3 手势导航

- `EdgeBackGestureHandler`：左右边缘的返回手势识别，在 SystemUI 进程里。
- `NavigationBar`：手势模式下只显示一条 Home 条，尺寸和位置来自资源。
- 上滑进最近任务：SystemUI 识别手势，**实际动画由 Launcher 和 WMS 完成**（见第 14 节）。
- 车载/平板可能出现 `Taskbar`（由 Launcher 绘制，SystemUI 通过代理接口协作）。

### 9.4 常见现象

| 现象 | 先看什么 |
|---|---|
| App 内容被状态栏遮住 | App 是否处理 Insets、WMS InsetsState、状态栏高度资源 |
| 沉浸式没生效 | disable flags 链路、车机策略、窗口叠加 |
| 手势返回不灵敏 | 手势识别区域、与其他手势冲突、车机 overlay |

---

## 10. 通知在 SystemUI 侧的流水线

### 10.1 总览

```text
NMS
  │ NotificationListenerService 回调（onNotificationPosted / Removed / RankingUpdate）
  ▼
SystemUI NotificationListener
  │
  ▼
NotifCollection（持有所有 NotificationEntry）
  │ NotifCollectionListener
  ▼
NotifPipeline（一串 Coordinator 依次处理）
  ├─ 排序 / 排名
  ├─ 过滤（是否该显示）
  ├─ 分组 / 摘要
  └─ 打断判定（要不要 heads-up）
  │
  ▼
NotificationPresenter → NotificationRowBinder
  │
  ▼
ExpandableNotificationRow（真正的一行通知）
```

### 10.2 数据结构与"条目"概念

- `NotificationEntry`：SystemUI 侧对一条通知的完整视图（含 ranking、sbn、view 状态）。
- `NotifCollection`：所有 entry 的集合，是数据源头。
- `NotifPipeline`：数据流水线，用 Coordinator 链式处理，取代了旧的 `NotificationEntryManager`。

### 10.3 排序、过滤、分组

| 环节 | 典型实现 | 作用 |
|---|---|---|
| 排序 | 排名相关 Coordinator | 按 NMS 给出的 rank 决定顺序 |
| 过滤 | `NotifFilter` 系列 | 该不该在这个场景显示（当前用户、DND） |
| 分组 | 分组/摘要 Coordinator | 同 App 通知聚合、组摘要 |
| 隐私 | 敏感内容相关 Controller | 锁屏上隐藏敏感内容 |

锁屏可见性是单独一环：

```text
锁屏是否显示某条通知
  ├─ 用户设置（锁屏是否显示通知）
  ├─ 通知渠道/应用级敏感度
  └─ 锁屏通知可见性判定
```

### 10.4 渲染

```text
NotifShadeEventSource
  └─ NotificationListCoordinator / NotifViewManager
       └─ NotificationRowBinderImpl
            └─ ExpandableNotificationRow
                 ├─ NotificationContentView（小/大/heads-up 三种布局）
                 └─ NotificationMenuRow（长按动作）
                       └─ NotificationStackScrollLayoutController（列表容器）
                            └─ NotificationPanelViewController（面板）
                                 └─ NotificationShadeWindowViewController（窗口触摸）
```

**排查"通知卡片错位/高度不对"**：先看 `ExpandableNotificationRow` 的测量（是否被 layout 限制），再往上看列表容器，最后才是面板。

### 10.5 点击、长按、滑动

| 交互 | 实现 | 去向 |
|---|---|---|
| 点击 | 通知点击处理器 | `PendingIntent.send()` → ATMS 启动 Activity |
| 长按 | 通知菜单行 + 自定义动作 | 设置、静默、延迟等 |
| 滑动 | 滑动动作助手 | 清除、延迟、回复 |
| 全屏 | `fullScreenIntent` 通路 | 锁屏/通话/闹钟全屏 |

点击链路的一个关键细节：**SystemUI 只是把 `PendingIntent` 发出去**，真正的启动许可由 ATMS 判。所以"点了通知没进 App"要同时看 SystemUI 有没有发、ATMS 有没有允许。

### 10.6 版本差异（重要）

| 内容 | 旧（Android 11 及以前） | 新（Android 13+） |
|---|---|---|
| 通知数据管理 | `NotificationEntryManager` + `NotificationData` | `NotifCollection` + `NotifPipeline` |
| 排序/过滤 | 管理器内部逻辑 | 一组 Coordinator |
| 打断（heads-up） | `NotificationInterruptStateProvider` | 打断判定相关 Coordinator |
| 状态栏类名 | `StatusBar` | `CentralSurfaces` / `CentralSurfacesImpl` |

改通知相关代码前先 `grep` 一下自己 ROM 里到底是哪套类名，这一步能省掉大量排查时间。

---

## 11. heads-up 为什么弹，为什么不弹

heads-up（顶部悬浮通知）是投诉最多的点，它的判定集中在打断判定逻辑里。

### 11.1 决策链

```text
通知到达
  └─ 打断判定
       ├─ 通知是否允许打断（渠道重要性达到高）
       ├─ 是否当前前台 App 自己发的（自己发的通常不弹）
       ├─ 屏幕是否亮着、是否在锁屏
       ├─ 用户是否在免打扰模式（Zen / DND）
       ├─ 是否已存在同 key 的 heads-up
       └─ 是否满足 fullScreenIntent 条件
            ▼
       HeadsUpManagerPhone（管理活跃的 heads-up 队列）
            ├─ 更新状态栏上的 heads-up 视图
            └─ 超时后自动收起到通知面板
```

### 11.2 逐条对照

| 条件 | 不弹的常见原因 | 检查方式 |
|---|---|---|
| 渠道重要性 | 渠道 importance 不够高 | `dumpsys notification --noredact` 看 channel |
| 前台 App 排除 | 通知就是当前 App 发的 | 换 App 测试 |
| 屏幕状态 | 屏幕灭着，走了锁屏/Doze 通路 | 亮屏测试 |
| 免打扰 | 打开了 DND / 驾驶模式 | 检查 Zen 模式设置 |
| 已有同 key | 同一通知被更新而非新建 | 观察 key 是否相同 |
| App 权限 | 通知权限被关闭，通知根本没进来 | `dumpsys notification` 看是否被 block |
| 车机策略 | 驾驶 UX 限制压制了非驾驶相关通知 | 看车载 UX restriction 相关 log |

### 11.3 区分"数据没到"和"UI 没画"

```bash
adb shell dumpsys notification --noredact | grep -A5 "NotificationRecord"
adb shell dumpsys activity service com.android.systemui | grep -i "heads\|notif"
adb logcat -s SystemUI:* NotificationService:*
```

- NMS 里有记录 → 数据到了，问题在 SystemUI 的过滤/打断判定。
- NMS 里没有 → 问题在发布方权限、渠道或 NMS 拦截。

---

## 12. Keyguard：锁屏状态机与解锁流程

Keyguard 是 SystemUI 里最复杂、最容易被低估的部分。它横跨两个进程：

```text
system_server（ATMS/WMS）              com.android.systemui
  KeyguardController                     KeyguardViewMediator
  KeyguardServiceDelegate  ◄──Binder──►  KeyguardUpdateMonitor
  ActivityRecord.showWhenLocked          Bouncer / SecurityContainer
  PhoneWindowManager                     ScrimController / DozeScrim
```

### 12.1 为什么说它跨了两个进程

- **SystemUI 侧是"显示者"**：决定锁屏界面长什么样、什么时候亮起来、什么时候解锁。
- **framework 侧是"裁决者"**：决定锁屏状态下哪些 Activity 能显示、Task 怎么切换、窗口怎么遮挡。
- 两边通过 `IKeyguardService` + `KeyguardServiceDelegate` 对话。

### 12.2 KeyguardViewMediator 与状态

`KeyguardViewMediator` 内部的几个关键状态：

```text
mKeyguardShowing     // 锁屏正在显示
mOccluded            // 被"遮挡"：锁屏逻辑上仍锁着，但界面让给了别的全屏内容
mKeyguardGoingAway   // 正在退场（解锁动画中）
```

对外只读视图由 `KeyguardStateController` 提供：

```java
keyguardStateController.isShowing();
keyguardStateController.isOccluded();
keyguardStateController.canDismissLockScreen();   // 是否允许直接上滑进入
keyguardStateController.isTrusted();              // trust agent 是否认为是可信环境
```

**`occluded` 是最容易搞混的概念**：它不等于解锁。当一个 `showWhenLocked` 的 Activity（相机、车载全屏页）让锁屏让位时，锁屏界面被盖住，但系统仍然认为"设备是锁着的"。

### 12.3 与 framework 的对话

SystemUI 侧实现的 `IKeyguardService` 方法会被 `system_server` 调用（节选）：

| 方法 | 时机 |
|---|---|
| `onSystemReady()` | 系统启动完成 |
| `onBootCompleted()` | 开机广播后 |
| `setOccluded(occluded, animate)` | 有全屏内容遮挡锁屏 |
| `onStartedWakingUp()` / `onFinishedGoingToSleep()` | 亮屏 / 熄屏 |
| `onScreenTurnedOn()` / `onScreenTurnedOff()` | 屏幕物理状态变化 |
| `verifyUnlock()` | 请求确认解锁状态 |
| `setKeyguardEnabled(enabled)` | 设备策略禁用/启用锁屏 |
| `onDreamingStarted()` / `onDreamingStopped()` | 屏保 / Doze |

反向（SystemUI → framework）通过状态回调汇报 `showing`、`occluded`、`secure` 等状态。

### 12.4 锁屏下 Activity 能不能显示

这是"锁屏上弹不出 Activity"的核心链路：

```text
Activity.setShowWhenLocked(true)
  或 WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        ▼
ActivityRecord 记录 ShowWhenLocked
        ▼
ATMS KeyguardController.isKeyguardLocked()
        ├─ 允许 → 该 Activity 可显示在锁屏上（可能同时把锁屏置为 occluded）
        └─ 不允许 → 启动被推迟或直接拒绝
```

常见现象与原因：

| 现象 | 原因 |
|---|---|
| 锁屏上 Activity 不显示 | 没设 `showWhenLocked`，或被 KeyguardController 拒绝 |
| 设了还是不显示 | 窗口 flags 没传下去，或在 Activity 还没 ready 时就启动 |
| 显示后锁屏没回来 | 遮挡状态没恢复，锁屏一直不可见 |
| 全屏通知没弹全屏界面 | 缺 `USE_FULL_SCREEN_INTENT` 权限，或被锁屏/策略压制 |

### 12.5 Bouncer 与生物识别

```text
上滑锁屏 / 点击安全区域
  └─ Bouncer（安全输入容器）
       ├─ 安全模型决定需要哪种验证方式
       ├─ KeyguardPINView / PatternView / PasswordView
       ├─ 生物识别（BiometricUnlockController ↔ BiometricService）
       └─ 验证通过 → KeyguardViewMediator.keyguardDone()
```

生物识别的两条路径要区分：**解锁路径**（直接解锁进入桌面）和**认证路径**（只用于支付/授权，不解锁）。用户反馈"指纹识别了但没解锁"，往往是走了认证路径，或 `keyguardGoingAway` 状态卡住。

### 12.6 Scrim 与 Doze

`ScrimController` 用一组 `ScrimState` 表达锁屏/面板之间的遮罩形态，典型状态包括：

```text
OFF / KEYGUARD / BOUNCER / SHADE_LOCKED / UNLOCKED / DREAMING / AOD / ...
```

AOD（Always On Display）由 Doze 相关组件驱动，和 `PowerManager` 的 wakefulness 强耦合。**AOD 黑屏/卡住的问题，光看 SystemUI 是看不出来的，要连 Power 一起看。**

### 12.7 Direct Boot

- 用户数据在解锁前不可解密，此时只有 `directBootAware` 的组件能跑。
- SystemUI 在解锁前要能显示锁屏本身，因此有"锁屏态"和"解锁后"两阶段初始化。
- 关键判断：`UserManager.isUserUnlocked()`；事件源：`KeyguardUpdateMonitor` 收到用户解锁/切换。

```bash
adb shell dumpsys trust       # TrustManager 状态
adb shell dumpsys window policy | grep -i keyguard
adb shell dumpsys activity service com.android.systemui | grep -i keyguard
adb shell locksettings get-disabled
adb shell wm dismiss-keyguard  # 调试用：直接把锁屏关掉
```

---

## 13. QS 快捷设置

### 13.1 结构

```text
QuickSettings
  ├─ QSTileHost            管理所有 tile 的生命周期
  │    └─ QSFactory 根据 spec 创建 QSTileImpl 子类
  ├─ TileServiceManager    管理第三方 App 的 TileService
  ├─ QSPanelController     完整面板
  ├─ QuickQSPanelController 快捷区（下拉一小段显示的那排）
  └─ QSCustomizer          编辑模式
```

每个 tile 的生命周期由 `QSTileImpl` 管理：创建视图、响应点击、刷新状态。

### 13.2 tile 列表从哪来

```text
默认 tile 列表
  ├─ secure setting: sysui_qs_tiles（用户/ROM 定制后的持久化值）
  └─ 资源默认值：config_quickSettingsTiles 一类
```

**修改默认 tile 的正确做法**：改默认值（资源或初始值），不要只改运行时的 secure setting。

### 13.3 第三方 App 的 tile

App 声明 `TileService` 并申请权限后，系统把它包装成 tile，通过 `TileServiceManager` 绑定。排查"我的 tile 不出现"：

1. `TileService` 是否声明正确、权限是否授予。
2. 是否被用户从 QS 里移除了（secure setting 里没它）。
3. 绑定是否失败（看 `TileServiceManager` 相关 log）。

### 13.4 车机常见定制

- 列数/行数：改 QS 的布局资源，或使用大屏专用布局。
- 关闭 QS 或限制可编辑：改策略或直接不注册编辑入口。
- 大屏/车机常配 `Taskbar` 或专用快捷区，需要和 Launcher 一起改。

---

## 14. Recents：UI 其实不在 SystemUI

**这一节要纠正一个很常见的误解**：Android 9（Pie）之后，手势导航下的最近任务界面是由 **Launcher** 绘制的，SystemUI 只是协调者。

### 14.1 真实分工

```text
SystemUI 进程                          Launcher 进程
  OverviewProxyService  ──bind──►      TouchInteractionService
       │                                    │ 实现 IOverviewProxy
       │                                    ▼
       │                              最近任务 UI / 任务卡片 / Taskbar
       │
       └─ 通过代理接口提供能力（截屏、导航条颜色、屏幕固定等）

system_server（WMS）
  RecentsAnimationController   ← 上滑手势后 App 缩放跟手的动画控制
```

### 14.2 上滑手势的链路

```text
用户上滑
  └─ SystemUI 识别手势（EdgeBackGestureHandler / NavigationBar）
       └─ 通知 Launcher
            └─ Launcher 调用 ATMS 的最近任务动画接口
                 └─ WMS 接管 App 的 SurfaceControl
                      └─ Launcher 按手指位移缩放、平移这些图层
```

所以"最近任务卡片黑屏/错位/卡住"可能出在三个地方：手势识别（SystemUI）、任务卡片渲染（Launcher）、动画控制（WMS）。

### 14.3 三键模式

三键模式下点"最近任务"通常走 ATMS 拉起 Launcher 的最近任务界面，SystemUI 只负责把按键事件转出去。

### 14.4 调试

```bash
adb shell dumpsys activity recents | head -50
adb shell dumpsys activity service com.android.systemui | grep -i "overview\|recents"
adb shell dumpsys window | grep -i "TaskView\|recents"
```

---

## 15. 音量面板、截屏、全局动作、隐私指示器

### 15.1 音量面板

```text
按音量键
  └─ PhoneWindowManager.interceptKeyBeforeQueueing（framework）
       └─ AudioService 处理音量变化
            └─ 音量控制器回调
                 └─ SystemUI VolumeDialogControllerImpl
                      └─ VolumeDialogImpl 显示面板
```

要点：音量键事件是 framework 先处理的，SystemUI 只是 UI。**"音量键没反应"要先看 AudioService 是否收到按键，而不是先怀疑 SystemUI。**

### 15.2 截屏

```text
电源+音量下 / API 调用
  └─ ScreenshotHelper（framework）
       └─ 绑定 SystemUI 的截屏服务
            └─ ScreenshotController：抓图、显示预览、保存到相册
```

系统侧抓图能力来自 WMS/SurfaceFlinger，SystemUI 负责预览和保存流程。

### 15.3 全局动作（长按电源）

framework 在长按电源时通过 `CommandQueue.Callbacks.showGlobalActions()` 通知 SystemUI，由 SystemUI 弹出关机/重启/紧急呼叫菜单。车机常把这个菜单改掉或直接禁用。

### 15.4 隐私指示器（Android 12+）

```text
App 使用摄像头/麦克风
  └─ SensorPrivacyManager / AppOpsManager 回调
       └─ SystemUI 隐私项控制器
            └─ 状态栏右上角绿点（PrivacyDot）
```

车机做合规时，这块常被要求改成"必须显示且不可关闭"。

---

## 16. 多屏、多用户与车机定制

### 16.1 多屏

- SystemUI 的状态栏/导航栏是 **per-display** 的：每个 display 可能需要一套。
- 实现上通过按 display 划分子组件为每个 display 创建独立实例。
- 配置变化（分辨率、密度、夜间模式）由 `ConfigurationController` 统一分发。

常见问题：

| 现象 | 原因方向 |
|---|---|
| 副屏没有状态栏 | per-display 子组件没创建 / bar 没注册到该 display |
| 状态栏显示到了错误的屏 | displayId 传递错误、窗口创建时用了默认 display |
| 改分辨率后状态栏高度不对 | 资源未按 display 配置、配置分发时序问题 |

### 16.2 多用户

- SystemUI 默认以 system user 身份运行单进程，通过用户跟踪组件感知当前前台用户。
- 切换用户时 SystemUI 要重建大量与用户相关的状态（通知可见性、锁屏、快捷设置）。
- 用户相关的通知/锁屏行为都受当前用户设置影响，**排查"换用户后行为变了"必须切到那个用户去复现**。

### 16.3 车机 SystemUI 的结构差异

车机不是"改几个资源"，而是换了一整套 SystemUI 工厂：

```text
framework-res: config_systemUIFactoryComponent
  ├─ 手机：com.android.systemui.SystemUIFactory
  └─ 车机：CarSystemUIFactory（overlay 替换）
        └─ 车机 SystemUI 模块负责状态栏/导航栏的车机实现
```

因此车机上：

- 状态栏/导航栏的类往往不在 `packages/SystemUI`，而在车机专属目录。
- 通知展示常被简化（驾驶时限制展示、只显示通话和导航）。
- 驾驶 UX 限制会参与通知打断判定，这解释了"为什么有些通知在车机上不弹 heads-up"。

车机 SystemUI 改动后必须验证的组合：

```text
多屏 × 多用户 × day/night × 驾驶/驻车 × 状态栏可见性
```

### 16.4 常用 overlay 位置

| 内容 | 位置 |
|---|---|
| 状态栏高度等尺寸 | SystemUI 的 `res/values/dimens.xml` + overlay |
| SystemUI 工厂选择 | framework-res 的 `config_systemUIFactoryComponent` |
| 系统图标 slot 清单 | framework-res 的 `config_statusBarIcons` |
| 系统栏显示策略 | framework-res / SystemUI 的策略资源 |

---

## 17. 新架构：Dagger、Scene、Compose 与协程

### 17.1 依赖注入

- SystemUI 用 Dagger 做全量注入，作用域注解是 `@SysUISingleton`。
- 顶层组件提供全局依赖；按 display 划分的子组件提供 display 相关依赖。
- **新增模块必须同时改组件数组和 Dagger 绑定**（见 5.3），这是新手最常踩的坑。

### 17.2 统一状态

过去状态散落在几十个类里（`mExpanded`、`mKeyguardShowing`、`mBouncerShowing`……），新版本引入 `SysUiState`，把"面板展开、锁屏显示、有 heads-up、通知栏可见"等收敛成一组 flag。

**收益**：判断"现在是什么状态"只需要看一处；**代价**：状态更新是异步分发的，读的时候要注意时序。

### 17.3 Scene 框架

新版本引入 Scene 体系，用来统一描述"锁屏 / 通知面板 / 常态"之间的场景切换与过渡动画。旧的"直接操作 View 的 y 位移"逐步被场景过渡取代。

**实践意义**：改下拉/锁屏过渡动画时，先确认自己 ROM 里 Scene 开关是否打开，两套实现完全不同。

### 17.4 Compose

新版 SystemUI 在部分面板使用 Jetpack Compose，通过宿主容器嵌入原有的 View 体系，再用场景过渡把两侧桥接起来。写自定义界面时要注意：

- 不要假设所有 UI 都是 View，有的区域是 Compose。
- 混用时要明确谁负责测量和滚动。

### 17.5 协程与线程

- SystemUI 大量使用协程，主线程 scope 和后台 scope 由限定符区分。
- 通知处理、图标更新、数据查询很多在后台执行器上，但最终 UI 更新回主线程。
- **排查 SystemUI 卡顿**：先看主线程耗时，再看后台执行器是否排队（`dumpsys` 与 Perfetto 一起看）。

---

## 18. 常见问题与排查方法

### 18.1 通用命令

```bash
# SystemUI 内部状态（最重要的一条）
adb shell dumpsys activity service com.android.systemui

# 系统栏窗口
adb shell dumpsys window | grep -i "StatusBar\|NavigationBar\|NotificationShade\|Keyguard"

# 通知侧
adb shell dumpsys notification --noredact | head -80

# 锁屏
adb shell dumpsys window policy | grep -i keyguard
adb shell dumpsys trust

# 日志
adb logcat -b all | grep -iE "SystemUI|StatusBar|Keyguard|Notification|HeadsUp"

# 快速验证命令（由 SystemUI 注册，部分版本才有）
adb shell cmd statusbar expand-notifications
adb shell cmd statusbar expand-settings
adb shell cmd statusbar collapse
adb shell cmd statusbar get-status-icons
```

### 18.2 分问题排查

**状态栏整体不显示**

1. SystemUI 进程是否存活（`dumpsys activity processes | grep systemui`）。
2. 状态栏窗口是否存在（`dumpsys window | grep StatusBar`）。
3. 窗口是否在正确的 display。
4. 是否被状态机/策略置为不可见。

**自定义图标不显示**

1. slot 是否在 `config_statusBarIcons` 里。
2. `setIcon` 是否成功（权限是否足够）。
3. SystemUI 侧是否有该 slot 的渲染位置。
4. `cmd statusbar get-status-icons` 看 framework 侧的记录里有没有它。

**通知不弹 heads-up**

按第 11 节的判定链逐条排除，先确认 NMS 里有没有这条通知。

**锁屏上 Activity 显示不了**

1. 有没有设 `showWhenLocked`。
2. 启动被谁拒了（看 ATMS 与 KeyguardController 相关 log）。
3. 是不是启动时机太早（Activity 还没 ready）。

**手势上滑进不了最近任务**

区分 SystemUI 手势识别、Launcher 绘制、WMS 动画三段，分别看第 14 节的 dumpsys。

**SystemUI 卡顿**

1. 先确认卡的是哪个场景（展开面板、切换 App、解锁）。
2. Perfetto/systrace 抓 SystemUI 的主线程。
3. 看是否后台执行器排队、是否有频繁的配置变化重建。

---

## 19. 第三方系统常见修改点

| 需求 | 主要改动位置 | 注意 |
|---|---|---|
| 状态栏高度/图标大小 | SystemUI 尺寸资源 + overlay | 同时验证 Insets 与 App 布局 |
| 加自定义状态栏图标 | framework-res `config_statusBarIcons` + SystemUI slot | 需要系统权限调用 `setIcon` |
| 隐藏状态栏/导航栏 | 策略资源 + 窗口创建逻辑 | 影响 Insets，App 内容会顶到边缘 |
| 定制 QS tile | 默认 tile 列表 + tile 实现 | 优先改默认值而非 secure setting |
| 锁屏界面与解锁策略 | Keyguard 相关类 / 设备策略 | 要验证 Direct Boot、多用户 |
| 通知过滤/白名单 | 通知过滤环节 | 注意区分 NMS 层过滤与 SystemUI 层过滤 |
| 简化最近任务 | Launcher 侧（不是 SystemUI） | 分工别搞错 |
| 多屏状态栏策略 | per-display 子组件 + 资源 | 每块屏都要单独验证 |

**原则**：SystemUI 是用户可见界面，任何改动都要同时验证 **WMS 窗口/Insets、通知、锁屏、多用户、多显示** 五个面。

---

## 20. 读源码的推荐路线

### 20.1 进程启动与装配

```text
SystemServer.startSystemUi
SystemUIApplication.onCreate
SystemUIApplication.startServicesIfNeeded
读取 R.array.config_systemUIServiceComponents
创建 CoreStartable → start()
BootCompleteReceiver → startServicesIfNeeded("BootComplete")
```

### 20.2 状态栏窗口创建

```text
CentralSurfaces / StatusBar 构造
StatusBarWindowController.attach()
WindowManager.addView(StatusBarWindowView, lp)
dumpsys window 里出现 "StatusBar"
```

### 20.3 图标更新

```text
StatusBarManager.setIcon
IStatusBarService.setIcon
StatusBarManagerService.setIcon
CommandQueue.Callbacks.setIcon
StatusBarIconControllerImpl.setIcon
IconManager → StatusBarIconView
```

### 20.4 通知流水线

```text
NotificationListener.onNotificationPosted
NotifCollection
NotifPipeline（各 Coordinator）
打断判定
HeadsUpManagerPhone / NotificationPresenter
ExpandableNotificationRow
```

### 20.5 锁屏状态

```text
KeyguardViewMediator.onSystemReady
KeyguardUpdateMonitor 收到事件
StatusBarState 切换
ScrimController / Bouncer
IKeyguardService 回调 framework
KeyguardServiceDelegate → KeyguardController（ATMS）
```

### 20.6 面板展开

```text
NotificationPanelViewController（触摸）
NotificationShadeWindowViewController
StatusBarStateController
NotificationStackScrollLayoutController
ScrimController
```

---

## 21. 关键源码路径速查

| 内容 | 路径 |
|---|---|
| SystemUI 主体 | `frameworks/base/packages/SystemUI/` |
| 进程与装配 | `frameworks/base/packages/SystemUI/src/com/android/systemui/SystemUIApplication.java`、`SystemUIService.java` |
| 组件列表资源 | `frameworks/base/packages/SystemUI/res/values/config.xml` |
| 状态栏核心 | `frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/phone/` |
| 状态栏窗口 | `.../statusbar/phone/StatusBarWindowController.java` |
| 通知 UI | `.../statusbar/notification/` |
| Keyguard | `.../keyguard/` |
| QS | `.../qs/` |
| 最近任务代理 | `.../recents/` |
| 音量 | `.../volume/` |
| 截屏 | `.../screenshot/` |
| 状态栏服务（framework） | `frameworks/base/services/core/java/com/android/server/statusbar/StatusBarManagerService.java` |
| 状态栏 API（framework） | `frameworks/base/core/java/android/app/StatusBarManager.java` |
| 图标 slot 资源配置 | `frameworks/base/core/res/res/values/config.xml`（`config_statusBarIcons`） |
| 系统栏布局策略 | `frameworks/base/services/core/java/com/android/server/wm/DisplayPolicy.java` |
| Keyguard 裁决（ATMS） | `frameworks/base/services/core/java/com/android/server/wm/KeyguardController.java` |
| Keyguard 代理（framework） | `frameworks/base/core/java/com/android/internal/policy/KeyguardServiceDelegate.java` |
| SystemUI 工厂选择 | framework-res `config_systemUIFactoryComponent` |
| 车机 SystemUI | `packages/apps/Car/SystemUI/`（各自 ROM 结构可能不同） |

---

## 22. 一图总结

```text
system_server 进程
  SystemServer.startSystemUi
        │ startServiceAsUser
        ▼
com.android.systemui 进程
  SystemUIService
        └─ SystemUIApplication.startServicesIfNeeded
             ├─ 读取 config_systemUIServiceComponents
             ├─ Dagger 创建 CoreStartable
             └─ start() / onBootCompleted()
                  │
     ┌────────────┼────────────────┬───────────────┐
     ▼            ▼                ▼               ▼
 状态栏/面板    Keyguard         QS / 音量       最近任务代理
 窗口 + 状态机  状态机 + Bouncer  截屏 / 隐私      OverviewProxy
     │            │                │               │
     └────────────┴────────┬───────┴───────────────┘
                           ▼
                       WMS（当窗口管）
                           │
                           ▼
                   SurfaceFlinger（合成上屏）

数据与控制来源：
  NMS ──通知数据──► 通知流水线 ──► 面板 / heads-up
  App/系统服务 ──IStatusBarService.setIcon──► StatusBarManagerService
                                              └─CommandQueue 反向回调──► 图标更新
  ATMS ──KeyguardController──► 锁屏下能否显示 Activity
```

---

## 小结

- **SystemUI 是一个特殊系统 App**，跑在 `com.android.systemui` 进程，由 `system_server` 通过 `startServiceAsUser` 拉起，`SystemUIApplication` 读组件数组完成装配。
- **新增模块要同时改组件数组和 Dagger 绑定**，否则编译通过但永不启动。
- **系统栏界面就是窗口**，`StatusBarWindowController` 用 SystemUI 自己的 token 把它们挂到 WMS 上；多屏下每个 display 一套。
- **图标链路是"framework 存数据、SystemUI 渲染"**：`IStatusBarService` 正向进、`CommandQueue.Callbacks` 反向出，slot 由 framework-res 的 `config_statusBarIcons` 定义。
- **`StatusBarState` 是面板行为的核心状态机**，面板异常先看它。
- **通知在 SystemUI 侧是一条 Coordinator 流水线**，旧版 `NotificationEntryManager` 与新版 `NotifPipeline` 不要混着看。
- **Keyguard 跨两个进程**：显示在 SystemUI，裁决在 ATMS 的 `KeyguardController`，"锁屏上能不能显示 Activity"要看两边。
- **最近任务的 UI 在 Launcher，不在 SystemUI**，SystemUI 只做手势识别与代理。
- **车机 SystemUI 是另一套实现**（`CarSystemUIFactory`），并用驾驶 UX 限制参与通知与界面策略。

如果只记一个核心模型：

> SystemUI 把 Framework 的状态变成窗口和界面：NMS 给通知数据，WMS 管这些窗口的位置与层级，ATMS 决定锁屏下谁能显示，而 SystemUI 自己负责装配、状态机和渲染。
