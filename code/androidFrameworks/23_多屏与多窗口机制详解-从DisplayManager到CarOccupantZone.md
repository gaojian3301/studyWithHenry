# 多屏与多窗口机制详解：从 DisplayManager 到 CarOccupantZone

> 这篇是 11_WMS 的"屏维度"延伸。11_WMS 讲单屏内窗口如何被管理（token、层级、焦点、Insets），本篇讲**一块屏是怎么来的、窗口/Activity 怎么落到指定屏、同一屏内怎么被编排成多窗口、车机多乘员区怎么把"人/用户/屏"绑起来**。
> 目标读者：会读 AOSP 源码、做 Android 原生与车载定制 ROM（vendor 端）的开发者；习惯术语中英混用。本篇主角是三类对象在"屏"维度上的编排：`Display` / `DisplayContent` / `Task` / `TaskOrganizer` 的关系。

---

## 目录

1. [开场：四个问题与多屏世界地图](#1开场四个问题与多屏世界地图)
2. [Display 的两个视角：App 侧与 WMS 侧](#2display-的两个视角app-侧与-wms-侧)
3. [屏幕从哪来：DisplayAdapter 与 VirtualDisplay](#3屏幕从哪来displayadapter-与-virtualdisplay)
4. [Presentation：把内容钉在一块屏上](#4presentation把内容钉在一块屏上)
5. [Activity 与多屏：落到指定 Display](#5activity-与多屏落到指定-display)
6. [多窗口形态总览](#6多窗口形态总览)
7. [WMShell 与 TaskOrganizer：分屏/PiP 的"调度"与"UI"分离](#7wmshell-与-taskorganizer分屏pip-的调度与ui-分离)
8. [多窗口下的生命周期与状态](#8多窗口下的生命周期与状态)
9. [跨屏输入与焦点](#9跨屏输入与焦点)
10. [SystemUI 多屏：衔接 18 篇第 16 节](#10systemui-多屏衔接-18-篇第-16-节)
11. [车机专章：CarOccupantZone 与乘员区](#11车机专章caroccupantzone-与乘员区)
12. [配置与 overlay：屏的密度、尺寸与能力](#12配置与-overlay屏的密度尺寸与能力)
13. [调试工具箱](#13调试工具箱)
14. [常见问题排查表](#14常见问题排查表)
15. [读源码路线](#15读源码路线)
16. [关键源码路径速查](#16关键源码路径速查)
17. [一图总结](#17一图总结)
18. [关联阅读](#18关联阅读)

---

## 1. 开场：四个问题与多屏世界地图

先抛四个贯穿全文的问题，读完这篇你应能逐条回答：

1. **系统里有哪些屏、从哪来？** —— 内置物理屏、VirtualDisplay、WiFi/Cast display 都是"display"，它们如何在 `DisplayManager` 里注册、在 WMS 里表现为 `DisplayContent`。
2. **窗口/Activity 怎么落到指定屏？** —— `ActivityOptions.setLaunchDisplayId`、WindowContext、`DisplayContent` 归属。
3. **多窗口形态（分屏/浮窗/PiP/嵌入）如何编排？** —— 每种形态改变的是 Task/Window 的边界与标志，调度在 ATMS、UI 在 WMShell。
4. **车机多乘员区怎么映射屏、用户与显示？** —— `CarOccupantZoneService` 把 driver/passenger/rear 的 zone 绑到 displayId 与 userId。

### 1.1 多屏世界地图

```text
┌─────────────────────────────────────────────────────────────────────┐
│ App 层 API（进程内，能直接调）                                          │
│   DisplayManager / Display / MediaRouter / ActivityOptions            │
│   Presentation / WindowManager(WindowContext)                         │
├─────────────────────────────────────────────────────────────────────┤
│ Framework 层（system_server，wm/display 服务）                         │
│   DisplayManagerService ── DisplayAdapter ── LogicalDisplay           │
│   WindowManagerService ── RootWindowContainer ── DisplayContent       │
│   ActivityTaskManagerService ── Task / TaskDisplayArea                │
│   WMShell(SystemUI进程) ── ShellTaskOrganizer / SplitScreen / Pip      │
├─────────────────────────────────────────────────────────────────────┤
│ 车机层（packages/services/Car）                                         │
│   CarOccupantZoneService ── OccupantZoneInfo ── (zone ↔ display ↔ user)│
│   CarActivityManager / ClusterHome / CarZoomUxRestrictions            │
└─────────────────────────────────────────────────────────────────────┘
        │ 最终都汇聚到 SurfaceFlinger 按 display 合成 Layer
        ▼
   Display 0 主屏 / Display 1 副驾屏 / Display 2 仪表(cluster) / Virtual N
```

### 1.2 与姊妹篇的分工

| 篇 | 讲什么 | 本篇与它的边界 |
|---|---|---|
| 11_WMS | 单屏内窗口管理（token、层级、焦点、Insets） | 本篇只讲"屏"维度，不重复单屏窗口规则 |
| 10_ATMS | Activity/Task 启动与生命周期 | 本篇只讲 ATMS 里"选哪块 display"的那一段 |
| 13_Input | 输入事件分发 | 本篇第 9 节只衔接 InputDispatcher 的 per-display 分发 |
| 18_SystemUI | 状态栏/锁屏/通知 | 本篇第 10 节只衔接其第 16 节 per-display SystemUI，不重述 |
| 19_多用户 | 用户切换与隔离 | 本篇第 11 节把 zone↔userId 绑定点出，细节回看 19 |
| 21_Audio | 音频焦点 | 第 11 节仅一句带过多屏音频焦点 |
| 22_图形渲染 | SurfaceFlinger/Layer | 本篇不展开合成，只说 display 到 Layer 的归属 |
| automotive/01 | AAOS 整体架构 | 本篇车机章是其"多屏细分"，不重讲 CarService 架构 |

### 1.3 怎么读

- 做车机 HMI：直接跳第 3、11、12 章。
- 排"窗口跑错屏"：第 5、13、14 章。
- 排"分屏/PiP 不灵"：第 6、7、8 章。
- 想读源码：第 15、16 章给路线与路径。

---

## 2. Display 的两个视角：App 侧与 WMS 侧

「**Android 视角**」：App 拿到的 `Display` 和 WMS 内部的 `DisplayContent` 不是同一个对象，但一一对应，靠 `displayId` 串起来。这是理解多屏的第一道关。

### 2.1 App 侧：`android.view.Display`

App 通过 `DisplayManager` 枚举和查询屏：

```java
DisplayManager dm = context.getSystemService(DisplayManager.class);
Display[] displays = dm.getDisplays();        // 所有LogicalDisplay
for (Display d : displays) {
    int id = d.getDisplayId();                 // 0=主屏, 1..N=副屏/virtual
    int state = d.getState();                   // STATE_ON / OFF / DOZE
    String name = d.getName();                  // 如 "Built-in Screen"
}
```

`Display` 是个**轻量句柄**：它不持有窗口，只暴露尺寸、密度、旋转、状态。真正的窗口管理在 WMS。

### 2.2 WMS 侧：`DisplayContent`

`DisplayContent` 在 `RootWindowContainer` 下，是 WMS 管理"一块屏上所有窗口"的容器：

```text
RootWindowContainer
  └─ DisplayContent #0  (主屏)
       ├─ TaskDisplayArea         ← Task/Activity 落在哪块屏的"区域"
       │    └─ Task → ActivityRecord → WindowState(AppWindowToken)
       ├─ WindowState(状态栏/导航栏/壁纸…)
       └─ InputMonitor(该屏输入监控器，见第9节)
  └─ DisplayContent #1  (副屏)
       └─ …
```

要点：
- 一个 `DisplayContent` 对应一个 `LogicalDisplay`，对应一个 `displayId`。
- `TaskDisplayArea`（TDA）是 DisplayContent 内"承载 Task"的专属子容器。Activity 最终挂在某个 TDA 上，从而绑定到某块屏。
- `DisplayContent` 持有 `mDisplay`（native `IBinder` 指向 SurfaceFlinger 侧 display），窗口的 Layer 最终以它为归属上屏。

### 2.3 displayId 的分配与含义

| displayId | 含义 | 来源 |
|---|---|---|
| `Display.DEFAULT_DISPLAY = 0` | 主屏（built-in） | 设备启动即有 |
| `1..N-1` | 扩展物理屏 / 模拟副屏（开发者选项 Overlay display） | DisplayAdapter 动态注册 |
| 大整数（如 `Integer.MAX_VALUE` 量级） | `VirtualDisplay`（投屏、Presentation 内容屏、MediaProjection） | `createVirtualDisplay` 时分配 |

`displayId` 由 `DisplayManagerService` 统一分配，App 侧 `Display.getDisplayId()` 与 WMS 侧 `DisplayContent.mDisplayId` 一致。

### 2.4 LogicalDisplay 与底层设备解耦

`LogicalDisplay` 是"系统逻辑上看到的一块屏"，它和真实硬件（`DisplayDevice`，由 `DisplayAdapter` 提供）解耦：

```text
DisplayDevice (硬件, 来自 DisplayAdapter: LocalDisplayAdapter/BatteryDisplayAdapter/VirtualDisplayAdapter)
      │  bind
      ▼
LogicalDisplay (带 DisplayInfo、mDisplayId、指向 DisplayDevice)
      │  WMS 镜像为
      ▼
DisplayContent (WMS 窗口容器)  ←→  App 侧 Display 句柄
```

解耦的意义：热插拔 HDMI、创建 VirtualDisplay、开发者选项模拟副屏，都只是增删 `LogicalDisplay` 并通知 WMS 增删 `DisplayContent`，App 通过 `DisplayManager.DisplayListener` 收到 `onDisplayAdded/Changed/Removed`，无需知道底层是 HDMI 还是虚拟屏。

### 2.5 DisplayInfo 关键字段

`DisplayInfo` 是 LogicalDisplay 的核心数据，WMS/App 都读它：

```java
DisplayInfo info = new DisplayInfo();
display.getDisplayInfo(info);          // 或 LogicalDisplay.getDisplayInfoLocked()
// 关键字段：
//  info.displayId            屏ID
//  info.name                 屏名
//  info.appWidth/appHeight   应用可用分辨率（已扣 cutout/导航栏）
//  info.logicalWidth/Height  逻辑分辨率
//  info.rotation             旋转 0/90/180/270
//  info.logicalDensityDpi    密度（影响 dp→px）
//  info.flags                FLAG_PRESENTATION / FLAG_PRIVATE 等
//  info.displayCutout        刘海/挖孔区域
```

`「Android 视角」`：`Display.getRealSize()` 返回含系统栏的整屏尺寸，`getSize()` 返回扣掉系统栏后的应用区域——多屏下两块屏可能尺寸/密度不同，App 必须按所在 Display 取，不能缓存主屏值。

---

## 3. 屏幕从哪来：DisplayAdapter 与 VirtualDisplay

「**Android 视角**」：所有屏都是 `DisplayManagerService` 通过一类 `DisplayAdapter` 注册进来的。理解适配器体系，才能区分"真屏/虚拟屏/模拟屏"。

### 3.1 DisplayAdapter 体系

```text
DisplayManagerService
  ├─ LocalDisplayAdapter        ← 内置物理屏(HDMI/eDP/DSI)，由 SurfaceFlinger 上报
  ├─ VirtualDisplayAdapter      ← createVirtualDisplay 创建的虚拟屏
  ├─ WifiDisplayAdapter         ← WiFi Display / Miracast 投屏（一句带过）
  ├─ OverlayDisplayAdapter      ← 开发者选项"模拟副屏"(overlay display)
  └─ (车机) 还可能自定义 Adapter 把特定硬件屏注册进来
```

`DisplayAdapter` 负责把底层设备（`DisplayDevice`）抽象给 DMS；DMS 把设备绑到 `LogicalDisplay`，再通知 WMS 建 `DisplayContent`。

### 3.2 内置 Built-in 屏与 DisplayAddress

内置屏由 `LocalDisplayAdapter` 在开机时枚举，用 `DisplayAddress` 标识物理位置：

```java
// frameworks/base/services/core/java/com/android/server/display/LocalDisplayAdapter.java
// 物理地址：PhysicalDisplayAddress(对应 DRM connector / 屏幕索引)
DisplayAddress address = DisplayAddress.fromPhysicalDisplayId(phyId);
```

`Display.DEFAULT_DISPLAY`（id=0）就是第一个 built-in 屏。车机里仪表屏、中控屏通常都是 built-in 的不同 connector，各自一个 `LogicalDisplay`。

### 3.3 VirtualDisplay：参数语义

`DisplayManager.createVirtualDisplay` 是 App 创建虚拟屏的入口，语义最关键：

```java
VirtualDisplay vd = dm.createVirtualDisplay(
    "my-virtual",                    // name：调试时 dumpsys 里看到的名字
    width, height,                   // 逻辑分辨率（px）
    densityDpi,                      // 密度，决定 virtual 内 dp→px
    surface,                         // Surface：虚拟屏的内容"画布消费者"
    flags);                          // 见下表
```

| flags（版本敏感） | 含义 |
|---|---|
| `VIRTUAL_DISPLAY_FLAG_PUBLIC` | 其他 App 可在此屏启动 Activity（如 Presentation 目标屏）。**无此 flag 时该屏是私有的** |
| `VIRTUAL_DISPLAY_FLAG_PRESENTATION` | 标记为可承载 Presentation 的屏 |
| `VIRTUAL_DISPLAY_FLAG_SECURE` | 内容可显示在安全层（防截屏） |
| `VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY` | 只显示自己启动的内容，不镜像其他屏 |
| `VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR` | 自动镜像主屏内容（投屏用） |
| `VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH` | 该虚拟屏接收触控输入 |

`「Android 视角」`：**`surface` 是最容易踩坑的参数**。VirtualDisplay 只是一块"画布"，必须有人持续消费它的 buffer（生产者=虚拟屏内窗口，消费者=你传入的 Surface），否则屏上无内容（见第 14 节排查）。MediaProjection 投屏就是系统替你持有一个 SurfaceConsumer 把屏幕内容喂进去。

### 3.4 VirtualDisplay 三大场景

| 场景 | 谁创建 | surface 来源 | 典型用途 |
|---|---|---|---|
| MediaProjection 投屏 | App 经 MediaProjection | `createVirtualDisplay` + `MediaProjection.createVirtualDisplay` 内部 Surface | 录屏/无线投屏 |
| Presentation 内容屏 | 系统为 Presentation 建 | Presentation 内部 Surface | 副屏独立内容（第 4 节） |
| 开发者选项模拟副屏 | OverlayDisplayAdapter | 内部 Surface 由 SurfaceFlinger 合成 | 多屏开发自测（无需真硬件） |

### 3.5 WiFi/Cast display（一句带过）

`WifiDisplayAdapter` 处理 Miracast/WiFi Display，机制同 VirtualDisplay 但多了网络协商层，车机一般用不到，知道它走同一套 LogicalDisplay→DisplayContent 管线即可。

---

## 4. Presentation：把内容钉在一块屏上

`Presentation` 是 App 把 UI 显示到**非主屏**最省事的官方方式：它继承自 `Dialog`，但自带"属于某块 display"的 window token 和 context。

### 4.1 设计要点

- `Presentation(Context outerContext, Display display)`：构造时传入目标 `Display`，内部用 `Context.createDisplayContext(display)` 生成一个**per-display 的 Context**——这个 Context 的 resources、尺寸、密度都按目标屏算。
- 它不是一个普通 Dialog，而是一个独立 window，挂在目标 `DisplayContent` 上，与主屏 Activity 的窗口**不在同一块屏**。
- 生命周期跟随 `Display` 状态：目标屏 `STATE_OFF`/`removed` 时 Presentation 自动 `onDisplayRemoved` → `dismiss()`。

### 4.2 完整创建代码

```java
// 在 Activity 里：找到副屏并弹 Presentation
DisplayManager dm = getSystemService(DisplayManager.class);
// 选非主屏且支持 PRESENTATION 的屏
Display target = null;
for (Display d : dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
    if (d.getDisplayId() != Display.DEFAULT_DISPLAY) { target = d; break; }
}
if (target == null) return;   // 没有副屏就别弹

class MyPresentation extends Presentation {
    MyPresentation(Context c, Display d) { super(c, d); }
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.presentation_layout);  // 这块布局跑在副屏
    }
}
MyPresentation p = new MyPresentation(this, target);
p.show();   // 内部用 per-display token 加到 WMS 的目标 DisplayContent
```

注意：传入的 `outerContext` 最好是 Activity/Application；Presentation 自己创建的 displayContext 才决定"内容画在副屏、且按副屏密度布局"。

### 4.3 生命周期跟随 Display 状态

必须监听屏状态，避免"屏没了还在用"：

```java
DisplayManager.DisplayListener listener = new DisplayManager.DisplayListener() {
    public void onDisplayAdded(int id) { /* 可能新副屏插入，重建 Presentation */ }
    public void onDisplayChanged(int id) { /* 分辨率/旋转变化，Presentation 需重建 */ }
    public void onDisplayRemoved(int id) {
        if (id == target.getDisplayId()) p.dismiss();  // 屏移除，必须 dismiss
    }
};
dm.registerDisplayListener(listener, null);
```

### 4.4 误用场景

| 误用 | 后果 | 正确做法 |
|---|---|---|
| 用主屏 Context 创建 Presentation | 内容仍画在主屏或 token 冲突 | 必须传 `createDisplayContext(display)` 后的 context 或让 Presentation 自己构造 |
| 屏移除后不 dismiss | `onDisplayRemoved` 后 window token 失效，后续操作抛异常 | 监听 `onDisplayRemoved` 主动 dismiss |
| 在 Presentation 里 startActivity 不带 displayId | 新 Activity 启到主屏 | 用 `ActivityOptions.setLaunchDisplayId` 指定（第 5 节） |
| 缓存 Display 对象 | Display 句柄可能失效 | 用 displayId，按需重新 `dm.getDisplay(id)` |

---

## 5. Activity 与多屏：落到指定 Display

从"想让 Activity 出现在某块屏"到"真的落在那块屏"，路径是：App 指定 `launchDisplayId` → `ActivityStarter` 选目标 `DisplayContent`/`TaskDisplayArea` → ATMS 建 `ActivityRecord` 归属该屏 → WMS 把窗口挂到对应 `DisplayContent`。

### 5.1 ActivityOptions.setLaunchDisplayId 完整示例

```java
// API 级别：Android 8.0(API 26) 起 ActivityOptions 支持 setLaunchDisplayId
ActivityOptions opts = ActivityOptions.makeBasic();
opts.setLaunchDisplayId(targetDisplayId);   // 指定落到哪块屏
Intent intent = new Intent(this, PassengerActivity.class);
startActivity(intent, opts.toBundle());
```

`「Android 视角」`：`setLaunchDisplayId` 只是"请求"，最终能否落到该屏还看目标 Task 的 `TaskDisplayArea` 是否允许（例如该屏只接受特定 uid、或锁屏/驾驶限制）。ATMS 会校验并可能回退到默认屏。

### 5.2 ActivityRecord / Task 与 DisplayContent 的归属

```text
DisplayContent #1 (副驾屏)
  └─ TaskDisplayArea
       └─ Task (taskId)
            └─ ActivityRecord   ← 带着 mDisplayId / 所属 TDA
                 └─ WindowState  ← WMS 内窗口，挂在 DisplayContent #1
```

- `ActivityRecord.mDisplayId`、`Task.mDisplayId` 决定它属于哪块屏。
- Task 一旦创建在某块屏，默认同 Task 的 Activity 都落同屏（除非显式换 display）。
- 跨屏的"容器"是 `TaskDisplayArea`：同一 `DisplayContent` 下可有多个 TDA（例如分屏的两个半区就是两个 TDA，见第 6 节）。

### 5.3 ActivityStarter 如何选目标 Display

调用链（精简）：

```text
ActivityStarter.execute()
  → ActivityStarter.resolveDisplay()
      ├─ 取自 ActivityOptions.getLaunchDisplayId()        ← App 显式指定
      ├─ 或取 sourceRecord/Task 的 mDisplayId             ← 同源
      └─ 校验 TaskDisplayArea 是否允许（ATMS 的 LaunchParamsController）
  → 选定 DisplayContent / TaskDisplayArea
  → ActivityRecord 建在该 DisplayContent，WindowState 挂上去
```

`「Android 视角」`：`LaunchParamsController` 是 ATMS 里统一裁决"Activity 该去哪块屏/哪个 TDA"的组件，车机定制（ClusterHome 强制仪表屏、驾驶限制禁副屏启动）常在此拦截。

### 5.4 命令行：am start --display

```bash
# 把某个 Activity 直接启到指定 display（调试神器）
adb shell am start --display 1 -n com.xxx/.PassengerActivity
# 把已有 task 迁移到另一块屏
adb shell am task move-task-to-display <taskId> <displayId>
```

### 5.5 跨屏迁移：moveTaskToDisplay 与 TaskOrganizer 路径

Activity 已在一个屏，想搬到另一屏，两条路径：

1. **ATMS 直接搬**：`ActivityTaskManagerService.moveTaskToDisplay()`（及 `am task move-task-to-display`）。会重算配置、重建 WindowState 归属到新 `DisplayContent`，触发 `onConfigurationChanged`。
2. **TaskOrganizer 路径**（Android 11+/WMShell 场景）：分屏、PiP、桌面窗口化等由 `ShellTaskOrganizer` 通过 `TaskOrganizer` 事务把 Task 从一个 TDA 挪到另一个 TDA（甚至跨屏），由 SystemUI 进程驱动（第 7 节详述）。

### 5.6 onConfigurationChanged 与重建语义

跨屏后 Activity 通常**不重建进程**，但会收到配置变化：

- 跨屏一般会触发 `onConfigurationChanged`（density/屏幕尺寸/screenLayout 变了），**不一定**触发 `onCreate` 重建。
- 若 `AndroidManifest` 里该 Activity 的 `configChanges` 没覆盖 `density|screenSize|smallestScreenSize`，系统会走 `onDestroy→onCreate` 重建。
- 多窗口 resize（分屏拖动分界线）同理：没声明对应 `configChanges` 就重建，声明了只回调 `onConfigurationChanged`。

```java
// 想避免跨屏重建，manifest 至少覆盖：
android:configChanges="density|screenSize|smallestScreenSize|screenLayout|orientation|keyboardHidden"
// 但车机跨屏若屏密度差异大，重建往往更稳，按业务取舍

---

## 6. 多窗口形态总览

同一块屏（尤其大屏/平板/车机副驾屏）内，系统把窗口编排成多种"多窗口形态"。它们的本质是：**改变 Task/Window 的边界、标志与所在 TaskDisplayArea**。调度统一在 ATMS，UI/手势在 WMShell（第 7 节）。

### 6.1 五种形态对照

| 形态 | 引入/API 级别 | 本质 | 入口/控制器 | 主要约束 |
|---|---|---|---|---|
| split-screen 分屏 | 7.0(N, API 24) | 一块屏两个 TDA，左右/上下各一个 Task | WMShell `SplitScreenController`（相关控制器） | 两个 Activity 都得 `resizeableActivity`；手机已弱化，平板/车机常用 |
| freeform 自由窗口 | 7.0(N) 概念，靠 `config` 开启 | Task 可拖动/缩放，像桌面 | `FreeformWorkspaceListener`（相关监听器） | 需 `config_freeformWindowManagement=true` |
| PiP 画中画 | 8.0(O, API 26) | Activity 缩成小窗悬浮，其余继续 | `PipTaskOrganizer`（相关控制器） | manifest `supportsPictureInPicture=true` |
| Activity Embedding | 9.0(P? 实为 11 R API 30 稳定) | 同 Task 内拆成两个 pane（主+详情） | Jetpack `ActivityEmbeddingRule` + 系统侧 | 同 Task、同应用或同 process；车机/平板双窗格 |
| 桌面窗口化 | Android 15(API 35) 演进方向 | 类 ChromeOS 自由浮窗 + 任务栏 | 新桌面模式（一句带过） | 仍在演进，按版本确认 |

`「Android 视角」`：Activity Embedding 的 API 级别常被误记——**核心 `SplitPairRule` 等类在 Android 11(API 30) 由 Jetpack `androidx.window` 引入并提供向后兼容**，并非每个形态都依赖 framework 内部版本。

### 6.2 各形态窗口属性/标志

- **分屏**：两个 Task 分别放进同一 `DisplayContent` 下的两个 `TaskDisplayArea`（primary/secondary）。分界线拖动由 WMShell 处理并通知 ATMS 重设 TDA 边界。
- **PiP**：Activity 进入 PiP 后，其 Task 标记为 `TASK_FLAG_PINNED`，Window 用 `TYPE_APPLICATION_OVERLAY` 类的小窗属性，始终 topmost 之一，且受 `PipBounds` 约束。
- **freeform**：Task 的窗口有可缩放边框，位置/尺寸由用户拖拽，ATMS 记 `bounds`。
- **Activity Embedding**：不新建 Task，而是把第二个 Activity 作为"嵌入 pane"放进当前 Task 的容器，复用同一 `ActivityRecord` 树，避免跨 Task 切换动画。

### 6.3 版本差异提醒

- 分屏在手机上 Android 12+ 后改为"众包/手势触发"且默认弱化；**平板和车机**仍常用传统分屏（vendor overlay 开启）。
- PiP 的 `setPictureInPictureParams`（aspect ratio、source rect hint）API 从 26 起，26 之前无 PiP。
- Activity Embedding 与 `resizeableActivity=false` 冲突时以 manifest 为准。

---

## 7. WMShell 与 TaskOrganizer：分屏/PiP 的"调度"与"UI"分离

这是本篇最易被误解处：**多窗口的"该不该分屏/该把 Task 挪到哪"由 ATMS 决策；"分屏线怎么画、手势怎么跟、PiP 动画"由 WMShell（跑在 SystemUI 进程）做**。

### 7.1 TaskOrganizer 是什么

`TaskOrganizer` 是 ATMS 暴露给"想接管 Task 外观/位置"的客户端（如 SystemUI 的 WMShell）的接口。它让 SystemUI 进程能直接：

- 监听 Task 的增删/层级变化（`onTaskAppeared/Changed/Vanished`）；
- 通过 `SurfaceControl.Transaction` 直接改 Task 的 `bounds`、层级、裁剪——**不经过每帧的 WMS 重新布局**，性能更好。

### 7.2 ShellTaskOrganizer：双端桥梁

```text
system_server (ATMS)
   └─ TaskOrganizerController   ← Binder 桥，管理所有注册的 organizer
          ▲ 注册/回调
          │
SystemUI 进程 (WMShell)
   └─ ShellTaskOrganizer         ← 实现 TaskOrganizer，注册到 ATMS
        ├─ SplitScreenController（相关控制器：管理分屏两个 TDA 与分界线）
        └─ PipTaskOrganizer（相关控制器：管理 PiP 边界/动画）
```

- ATMS 侧：`TaskOrganizerController` 持有注册表，把 Task 状态变化推给对应 organizer。
- SystemUI 侧：`ShellTaskOrganizer` 实现具体编排逻辑，用 transaction 直接改 SurfaceControl。

### 7.3 transaction 机制（一句讲清）

`TaskOrganizer` 收到 `onTaskAppeared` 时拿到 `TaskOrganizer.TaskOrganizerState` 与 `SurfaceControl`，之后用：

```java
// WMShell 内（SystemUI 进程）
SurfaceControl.Transaction t = new SurfaceControl.Transaction();
t.setPosition(taskLeash, x, y);
t.setCrop(taskLeash, new Rect(0,0,w,h));   // 改分屏半区/ PiP 小窗尺寸
t.setLayer(taskLeash, layer);
t.apply();
```

这套 transaction **绕过 WMS 主锁的逐帧 layout**，是手势拖动分屏线流畅的关键。`「Android 视角」`：这里 `taskLeash` 是 ATMS 给 organizer 的"操控句柄"（SurfaceControl），不是 WindowState 本身。

### 7.4 明确分工（重要）

| 动作 | 谁负责 | 进程 |
|---|---|---|
| 是否允许分屏、把哪个 Task 放进分屏半区 | ATMS（LaunchParamsController / Task 调度） | system_server |
| 分屏分界线绘制、拖动手势、动画 | `SplitScreenController`（相关控制器） | SystemUI(WMShell) |
| PiP 进入决策（是否支持 PiP） | ATMS + manifest `supportsPictureInPicture` | system_server |
| PiP 位置/尺寸/展开动画 | `PipTaskOrganizer`（相关控制器） | SystemUI(WMShell) |

所以"分屏线拖不动""PiP 卡住"先判断是 ATMS 没给 Task（看 `dumpsys activity` 里 Task 归属）还是 WMShell 的 transaction/手势没生效（看 SystemUI 进程 log）。

---

## 8. 多窗口下的生命周期与状态

多窗口最反直觉的是：**窗口可见（visible）≠ 已恢复（RESUMED）**。分屏里两个 Activity 都可见，但只有一个 RESUMED。

### 8.1 visible ≠ RESUMED

- 分屏两个半区：都 `visible=true`，但只有获焦的那个 `RESUMED`，另一个停在 `STARTED`（能画、不收输入）。
- PiP：原 Activity 进入 PiP 后通常 `onPause` + `onPictureInPictureModeChanged(true)`，仍可见但非 RESUMED。
- 浮窗/freeform 失焦：同样 `PAUSED` 但 `visible`。

### 8.2 关键回调与时机

| 回调 | 触发时机 |
|---|---|
| `onMultiWindowModeChanged(boolean, Configuration)` | 进入/退出分屏或 freeform |
| `onPictureInPictureModeChanged(boolean, Configuration)` | 进入/退出 PiP |
| `onConfigurationChanged(Configuration)` | 跨屏/分屏 resize 导致配置变化 |

顺序示例（Activity 从全屏拖入分屏）：

```text
onPause()                      ← 失去 RESUMED
onMultiWindowModeChanged(true, newConfig)
onConfigurationChanged(newConfig)   ← 新尺寸/密度
(onDestroy→onCreate 若未声明对应 configChanges)
onStart()
onResume()                     ← 重新获焦则 RESUMED
```

### 8.3 PiP 状态机简图

```text
全屏 RESUMED
   │ enterPictureInPictureMode()
   ▼
PiP EXPANDED? ──否──► PIPPED(small window, PAUSED visible)
   │                       │
   │ expand()              │ tap-to-expand / close
   ▼                       ▼
EXPANDED(full-ish)       FINISHED(onActivityFinish)
   │ close
   ▼
全屏 RESUMED (或销毁)
```

### 8.4 manifest 声明

```xml
<activity
    android:supportsPictureInPicture="true"     <!-- PiP 必需 -->
    android:resizeableActivity="true"            <!-- 分屏/freeform 必需，默认 true -->
    android:configChanges="density|screenSize|smallestScreenSize|screenLayout|orientation" />
```

`「Android 视角」`：`resizeableActivity=false` 的 Activity 在分屏请求时会被拒绝进分屏（ATMS 直接不把它放进 secondary TDA）。车机副驾屏常见定制：某些应用强制 `resizeableActivity=true` 以允许副屏分屏。

---

## 9. 跨屏输入与焦点

「**Android 视角**」：输入事件按 display 分发。每块屏有独立焦点窗口，多屏可"同时各有焦点"（不同屏各收各的输入，互不影响）。细节见 13_Input，本节点到即止。

### 9.1 InputDispatcher 的 per-display 分发

```text
InputReader 读事件(带 displayId, 来自驱动/InputFlinger)
   │
   ▼
InputDispatcher
   ├─ 按 displayId 选该屏的焦点窗口(FocusedWindow)
   ├─ 多屏：每个 display 独立一条焦点链，互不抢占
   ▼
对应 DisplayContent 的焦点 WindowState 收到事件
```

`「Android 视角」`：触摸事件在 `MotionEvent` 里带 `displayId`，InputDispatcher 据此把事件投到对应 `DisplayContent` 的焦点窗口。所以"副屏点不动"先查该屏焦点窗口是否真的在该屏。

### 9.2 每块屏的焦点窗口

WMS 里每个 `DisplayContent` 维护自己的焦点状态（`DisplayContent.mCurrentFocus`）。`dumpsys window` 能看到 `FocusedDisplay` 与每块屏的 `mCurrentFocus`。

### 9.3 多屏同时有焦点

设计上允许不同 display 同时各有一个 focus/foreground window——例如主屏导航 RESUMED、副驾屏视频 RESUMED，各自收输入。冲突只发生在"全局焦点"（如 IME target、系统手势）需指定主屏。

### 9.4 DisplayContent 的输入监控器

每块 `DisplayContent` 持有 `InputMonitor`（相关监控器），负责把该屏的窗口层级/焦点变化同步给 InputDispatcher（注册 `InputWindowHandle`）。窗口增删、层级变化、焦点切换都会经它更新 InputDispatcher 的窗口列表，否则"点了但事件送错窗口"。

---

## 10. SystemUI 多屏：衔接 18 篇第 16 节

> 衔接说明：18_SystemUI 第 16 节已讲"状态栏/导航栏是 per-display 的、车机换 CarSystemUIFactory"。本篇不重述，只补"锁屏/通知/Taskbar 在几块屏如何分布"的规则。

### 10.1 per-display 状态栏/通知面板

- 每多一块 display，SystemUI 通过 `@PerDisplay` 子组件为该屏建一套 StatusBar/NotificationShade（见 18 篇 16.1）。
- "副屏没状态栏"=per-display 子组件没为该 displayId 创建（而非 WMS 丢了窗口）。查法见 18 篇排查表。

### 10.2 锁屏在哪块屏出现

- 锁屏（Keyguard）默认只在**主屏（display 0）**出现；副屏/仪表屏通常不弹锁屏，或仅显示受限内容。
- 车机：仪表屏（cluster）几乎永不放锁屏，只放 ClusterHome（第 11 节）。

### 10.3 Taskbar 与大屏导航

- Android 平板/桌面模式：Taskbar 出现在主屏底部，作为大屏导航与最近任务入口。
- 副屏一般无 Taskbar，避免每块屏都占空间。车机副驾屏若需应用抽屉，由车机 Launcher 自定义，不走系统 Taskbar。

### 10.4 Toast / 通知投到哪块屏

规则（经验性，随版本/定制变化）：

| 内容 | 默认落屏 |
|---|---|
| 前台 Activity 所在屏的 Toast | 与该 Activity 同 display |
| 系统 Toast / 全局提示 | 主屏 |
| 通知（NotificationShade 展开） | 各屏若都有 per-display 通知面板则各自显示；否则主屏 |
| 车机驾驶限制下的通知 | 仅主屏/仪表屏可见，副驾屏可能抑制（驾驶 UX 限制，见 18 篇 16.3） |

`「Android 视角」`：具体投屏逻辑厂商差异大；定制 ROM 改"通知/Toast 落屏"时优先查 SystemUI 里 per-display 通知控制器的 display 选择，而不是 WMS。

---

## 11. 车机专章：CarOccupantZone 与乘员区

本篇对车机开发者的核心价值点。手机多屏是"多块物理屏"，车机多屏叠加了"**人在哪 (occupant) + 用哪个用户 (userId) + 看哪块屏 (displayId)**"三维绑定。负责这件事的是 `CarOccupantZoneService`。

### 11.1 为什么需要 OccupantZone

一台车有多块屏（仪表 cluster / 中控 center / 副驾 passenger），还有多个"乘员位置"（driver/passenger/rear）。系统要回答：

```text
driver   → 中控主用户(userId 10)    → display 0 (中控) + display 2 (仪表集群)
passenger→ 副驾用户(userId 11)       → display 1 (副驾屏)
rear     → 后排用户(userId 12)       → display 3 (后排屏)
```

OccupantZone 就是把"位置"抽象成 `OccupantZoneInfo`，再把它和 `displayId`、`userId` 绑起来。App 不必写死 displayId，只需说"我要给副驾启动"，由系统查 zone 映射。

### 11.2 OccupantZoneInfo 与 zone 类型

`OccupantZoneInfo` 描述一个乘员区：

```java
// packages/services/Car/car-lib/src/android/car/occupantzone/OccupantZoneInfo.java
int zoneId;                       // 区域ID
@OccupantType int occupantType;   // OCCUPANT_TYPE_DRIVER / PASSENGER / REAR // 等
int displayId;                    // 该 zone 绑定的主显示（可能多个）
int userId;                       // 该 zone 关联的用户
```

zone 类型（API 常量）：

| 类型 | 含义 |
|---|---|
| `OCCUPANT_TYPE_DRIVER` | 驾驶员位 |
| `OCCUPANT_TYPE_PASSENGER` | 前排乘客（副驾） |
| `OCCUPANT_TYPE_REAR` | 后排 |

### 11.3 Zone ↔ Display 映射（配置驱动）

映射来自 `car` 服务的资源/配置，典型是 `config_occupant_zone_*` 系列（在 `packages/services/Car/service/res/values/config.xml` 或 vendor overlay）：

```xml
<!-- 概念示意：一个 zone 绑定一个 display -->
<string-array name="config_occupant_zone_display_mapping">
    <!-- zoneId, displayId, occupantType -->
    "0,2,DRIVER"      <!-- 仪表集群给驾驶员 -->
    "1,1,PASSENGER"   <!-- 副驾屏给乘客 -->
</string-array>
```

`CarOccupantZoneManager`（App 侧 API）提供查询：

```java
CarOccupantZoneManager ozm = (CarOccupantZoneManager)
        car.getCarManager(Car.OCCUPANT_ZONE_SERVICE);
// 查副驾 zone 对应的 displayId
List<OccupantZoneInfo> zones = ozm.getAllOccupantZones();
for (OccupantZoneInfo z : zones) {
    if (z.occupantType == OCCUPANT_TYPE_PASSENGER) {
        int disp = ozm.getDisplayIdForOccupant(z.zoneId);  // 拿到副驾屏 displayId
    }
}
```

### 11.4 Zone ↔ userId ↔ Display 三者绑定

`CarOccupantZoneService`（system_server 内 car 服务）在启动/用户切换时构建三元映射：

```text
CarOccupantZoneService.init()
   ├─ 读 config_occupant_zone_*  → 建 OccupantZoneInfo 列表
   ├─ 每个 zone 绑定 displayId（来自 DisplayManager 的 LogicalDisplay）
   └─ 每个 zone 绑定 userId（来自 19_多用户 的用户分配策略）
        │ 切换用户时更新 zone→userId
        ▼
App 请求"给副驾启动X" → 查 zone → 得 displayId + userId
```

`「Android 视角」`：userId 与 display 的绑定是车机"副驾独立用户"的核心——副驾屏跑在另一个 Android 用户下，与主驾用户进程隔离（细节回看 19_多用户）。

### 11.5 ClusterHome 与仪表栈隔离

仪表屏（cluster）通常跑 `ClusterHome`——一个专属 launcher，且**与主屏 Activity 栈隔离**：

- ClusterHome 被强制绑定到仪表 display（通过 `LaunchParamsController` 拦截，任何想进仪表屏的 Activity 都要是 cluster 白名单）。
- 仪表屏一般不放普通应用，避免遮挡车速/警示。倒车影像、导航简图从 ClusterHome 或指定 provider 出。
- 隔离意味着：中控崩溃不影响仪表；仪表的 Activity 不会"误入"中控屏。

### 11.6 CarActivityManager 跨屏 moveTask

车机常需"把某 Task 从主屏挪到副驾屏"，用 `CarActivityManager`：

```java
CarActivityManager cam = (CarActivityManager) car.getCarManager(Car.CAR_ACTIVITY_SERVICE);
// 把指定 task 迁移到副驾 zone 对应的 display
OccupantZoneInfo pz = ...; // 副驾 zone
int passengerDisplay = ozm.getDisplayIdForOccupant(pz.zoneId);
cam.moveToDisplay(taskId, passengerDisplay, false);
```

底层仍是第 5.5 节的 `moveTaskToDisplay` / TaskOrganizer 路径，只是套了"按 zone 查 display"的车机语义。

### 11.7 副驾屏独立应用启动链路

```text
车机 Launcher(副驾用户) / 语音
  → CarActivityManager.startPassengerActivity(...)
  → 查 OccupantZoneInfo(PASSENGER) 得 displayId + userId
  → ActivityOptions.setLaunchDisplayId(displayId)
  → ATMS 在副驾 userId 进程启动 Activity，挂到该 DisplayContent
  → 副驾屏显示，与主驾用户互不干扰
```

### 11.8 多屏音频焦点（一句带过）

每块屏对应一个用户，音频焦点按用户/zone 隔离管理，细节见 21_Audio（本篇不展开）。

---

## 12. 配置与 overlay：屏的密度、尺寸与能力

多屏定制大多落在 config 与 overlay。以下为常用项，具体字段名随版本/厂商略有差异，以 `framework-res` 与 `car/service` 实际资源为准。

### 12.1 多屏能力开关

| config | 作用 |
|---|---|
| `config_supportsMultiDisplay` | 系统是否声明支持多屏（影响部分多窗口路径） |
| `config_enableMultiWindow` / `config_freeformWindowManagement` | 分屏/freeform 是否开 |
| `config_multiwindowComponents` | 允许多窗口的组件白名单 |

### 12.2 显示相关

| config | 作用 |
|---|---|
| `config_densityDpi`（per-display overlay） | 每块屏独立 density，副驾屏可能更高 |
| `display_cutout` / `config_mainDisplayCutout` | 刘海/挖孔区域（per-display） |
| `R.bool.config_ignoreStatusBarContrast` | 系统栏对比度策略 |

### 12.3 Taskbar / freeform 相关

- `config_navBarInteractionMode`：手势/三键/Taskbar。
- `config_freeformWindowManagement`、`config_pipSupportsExpanded`：窗口化能力。

### 12.4 车机 per-display 定制

车机通常在 vendor overlay 里对特定 displayId 指定尺寸/density，并把 `config_occupant_zone_*` 放进 `car/service` overlay（第 11.3 节）。

### 12.5 wm 命令 per-display 用法

```bash
# 查看所有 display 及尺寸/密度
adb shell wm size            # 默认主屏
adb shell wm density          # 默认主屏
# 指定 display 改尺寸/密度（Android 多屏支持后 wm 支持 --display）
adb shell wm size --display 1 1920x1080
adb shell wm density --display 1 240
# 复位
adb shell wm size --display 1 reset
```

`「Android 视角」`：`wm size/density` 的 `--display` 参数需系统支持，老版本只有全局；车机调试优先用 `dumpsys display` 看实际 LogicalDisplay 配置而非单纯改 wm。

---

## 13. 调试工具箱

每条命令带 `#` 注释说明"看什么"。

```bash
# 所有屏：displayId / name / owner / state / 分辨率
adb shell dumpsys display
#   → 看 LogicalDisplay 列表，确认副屏/仪表屏是否注册、STATE_ON 没

# WMS 视角：每块 DisplayContent 下有哪些窗口、焦点、TDA
adb shell dumpsys window displays
#   → 看 mDisplayId、mCurrentFocus、TaskDisplayArea 划分

# ATMS 视角：Activity/Task 落在哪个 display
adb shell dumpsys activity activities | grep -i display
#   → 看 ActivityRecord/Task 的 mDisplayId，确认没跑错屏

# 直接把 Activity 启到指定 display（第5.4节）
adb shell am start --display 1 -n com.xxx/.PassengerActivity

# 把已有 task 迁到另一屏
adb shell am task move-task-to-display <taskId> <displayId>

# 多窗口/分屏状态（看 Task 是否在分屏 TDA）
adb shell dumpsys activity activities | grep -i -E "split|pinned|freeform"

# PiP 状态
adb shell dumpsys activity | grep -i pip

# 输入：每块屏焦点窗口
adb shell dumpsys input | grep -i -E "display|focus"

# 车机：occupant zone 映射
adb shell dumpsys car_service | grep -i -E "occupant|zone"

# Perfetto：看某屏帧轨（SurfaceFlinger 按 display 出 tracks）
#   → 录制后搜 displayId 对应 layer/帧，确认某屏是否掉帧无帧
```

---

## 14. 常见问题排查表

| 现象 | 原因（方向） | 章节 | 第一命令 |
|---|---|---|---|
| Presentation 黑屏/不显示 | 目标屏 `STATE_OFF`、未 `show()`、surface 未消费 | 4.3/3.3 | `dumpsys display` 看屏 state |
| VirtualDisplay 无内容 | 传入 `surface` 没被消费者消费（buffer 空） | 3.3/3.4 | `dumpsys display` 看 virtual 状态 |
| Activity 启到错误屏 | `setLaunchDisplayId` 没设/`LaunchParamsController` 回退 | 5.1/5.3 | `dumpsys activity activities \| grep display` |
| 跨屏迁移后状态丢失/重建两次 | 没声明 `configChanges`，触发 onDestroy→onCreate | 5.6/8.4 | 看 logcat `ActivityThread` 重建 |
| PiP 大小异常 | `PictureInPictureParams` aspect ratio 没设/被限制 | 6.2/8.3 | `dumpsys activity \| grep pip` |
| 副驾屏应用没起 | occupant zone 映射错 / 对应用户没起 | 11.3/11.4 | `dumpsys car_service \| grep zone` |
| 多屏触控错乱 | 该屏焦点窗口不在本屏 / InputMonitor 未同步 | 9.1/9.4 | `dumpsys input \| grep focus` |
| 仪表屏无帧 | cluster display 未注册/ClusterHome 未起/无 buffer | 11.5/13 | `dumpsys display` + `dumpsys surfaceflinger` |
| 分屏线拖不动 | WMShell SplitScreenController 未接管/手势未生效 | 7.4 | 看 SystemUI 进程 log |
| PiP 卡住不展开 | PipTaskOrganizer transaction 异常 / 不支持 PiP | 7.4/8.4 | `dumpsys activity \| grep pip` + SystemUI log |
| 副屏无状态栏 | per-display 子组件未创建（见 18 篇 16.1） | 10.1 | 看 SystemUI 启动 log |
| 通知投错屏 | per-display 通知控制器 display 选错 | 10.4 | `dumpsys notification` + SystemUI log |
| 分屏拒进 | `resizeableActivity=false` | 8.4/6.3 | 看 manifest |
| 改分辨率后状态栏高度错 | 资源未按 display 配置分发 | 10.1/12.2 | `dumpsys window displays` |
| 多用户切后行为变 | 需切到该用户复现（19_多用户） | 11.4 | `am switch-user <id>` |
| HDMI 热插拔屏不显示 | LocalDisplayAdapter 未 bind / overlay 禁多屏 | 3.2/12.1 | `dumpsys display` 看 device |

---

## 15. 读源码路线

五条主线，按"想解决什么问题"选择切入。

### 15.1 DisplayManager → DisplayAdapter → LogicalDisplay

> 问题："一块屏是怎么注册进系统的？"

```text
frameworks/base/services/core/java/com/android/server/display/DisplayManagerService.java
  └─ DisplayAdapter.Listener.onDisplayDeviceAdded()
       → LocalDisplayAdapter / VirtualDisplayAdapter / OverlayDisplayAdapter
  └─ LogicalDisplay 创建/绑定 DisplayDevice
  └─ DisplayManagerGlobal 通知 App 侧 DisplayManager.DisplayListener
```

### 15.2 createVirtualDisplay → WMS

> 问题："虚拟屏内容怎么上屏、窗口怎么挂上去？"

```text
DisplayManager.createVirtualDisplay()
  → DisplayManagerService.createVirtualDisplayInternal()
  → VirtualDisplayAdapter 建 LogicalDisplay + DisplayDevice
  → RootWindowContainer.onDisplayAdded(displayId)
       → WMS 建 DisplayContent(displayId) + TaskDisplayArea
  → App 在其上 addWindow → WindowState 挂到 DisplayContent
```

### 15.3 setLaunchDisplayId → ActivityStarter → DisplayContent

> 问题："为什么我的 Activity 跑错屏？"

```text
ActivityOptions.setLaunchDisplayId(id)
  → ActivityStarter.resolveDisplay()
  → LaunchParamsController 裁决 TaskDisplayArea
  → ActivityRecord 建在目标 DisplayContent
  → WindowState 归属该 DisplayContent（dumpsys window displays 可见）
```

### 15.4 TaskOrganizer 双端

> 问题："分屏/PiP 的 UI 和调度谁管？"

```text
system_server:
  frameworks/base/services/core/java/com/android/server/wm/TaskOrganizerController.java
       ← 管理 organizer 注册表，推送 Task 状态
SystemUI(WMShell):
  packages/SystemUI/shared/src/.../shell/ShellTaskOrganizer.java
  packages/SystemUI/.../wm/shell/.../SplitScreenController.java   (相关控制器)
  packages/SystemUI/.../wm/shell/pip/PipTaskOrganizer.java          (相关控制器)
       → SurfaceControl.Transaction 改 taskLeash 位置/尺寸
```

### 15.5 CarOccupantZoneService 初始化与映射构建

> 问题："副驾屏为什么没起应用 / zone 映射怎么来的？"

```text
packages/services/Car/service/src/.../CarOccupantZoneService.java
  └─ init() 读 config_occupant_zone_*
  └─ 建 OccupantZoneInfo 列表，绑 displayId + userId
  └─ CarOccupantZoneManager(App API) 提供查询
  └─ CarActivityManager.moveToDisplay() 按 zone 查 display 后迁移 Task
```

---

## 16. 关键源码路径速查

| 对象 | 路径 |
|---|---|
| App 侧 Display | `frameworks/base/core/java/android/view/Display.java` |
| DisplayManager | `frameworks/base/core/java/android/hardware/display/DisplayManager.java` |
| DisplayInfo | `frameworks/base/core/java/android/view/DisplayInfo.java` |
| Presentation | `frameworks/base/core/java/android/app/Presentation.java` |
| VirtualDisplay | `frameworks/base/core/java/android/hardware/display/VirtualDisplay.java` |
| 屏管理服务 | `frameworks/base/services/core/java/com/android/server/display/DisplayManagerService.java` |
| 各 Adapter | `frameworks/base/services/core/java/com/android/server/display/*DisplayAdapter.java` |
| LogicalDisplay | `frameworks/base/services/core/java/com/android/server/display/LogicalDisplay.java` |
| WMS 容器 | `frameworks/base/services/core/java/com/android/server/wm/DisplayContent.java` |
| 根容器 | `frameworks/base/services/core/java/com/android/server/wm/RootWindowContainer.java` |
| Task 区域 | `frameworks/base/services/core/java/com/android/server/wm/TaskDisplayArea.java` |
| 启动选屏 | `frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java` |
| 参数裁决 | `frameworks/base/services/core/java/com/android/server/wm/LaunchParamsController.java` |
| Task 组织者 | `frameworks/base/services/core/java/com/android/server/wm/TaskOrganizerController.java` |
| WMShell organizer | `packages/SystemUI/shared/.../shell/ShellTaskOrganizer.java` |
| 分屏控制器 | `packages/SystemUI/.../wm/shell/.../split/`（相关控制器） |
| PiP 控制器 | `packages/SystemUI/.../wm/shell/pip/`（相关控制器） |
| 乘员区服务 | `packages/services/Car/service/src/.../CarOccupantZoneService.java` |
| 乘员区 API | `packages/services/Car/car-lib/.../occupantzone/CarOccupantZoneManager.java` |
| 车机 Activity | `packages/services/Car/service/src/.../CarActivityManager.java` |

---

## 17. 一图总结

### 17.1 一块新屏从产生到有内容的链路

```text
[硬件/虚拟源]
  LocalDisplayAdapter / VirtualDisplayAdapter / OverlayDisplayAdapter
        │  DisplayDevice
        ▼
DisplayManagerService
        │  bind → LogicalDisplay (带 displayId / DisplayInfo)
        │  通知 App: DisplayManager.DisplayListener
        ▼
WindowManagerService.RootWindowContainer
        │  onDisplayAdded → DisplayContent + TaskDisplayArea
        ▼
App 启动 Activity (setLaunchDisplayId / Presentation / WindowContext)
        │  WindowState 挂到该 DisplayContent
        ▼
SurfaceFlinger 按 displayId 合成 Layer → 屏上有内容
```

### 17.2 多窗口编排图

```text
DisplayContent (一块屏)
  ├─ TaskDisplayArea A (分屏左 / 主 pane)
  │     └─ Task → ActivityRecord → WindowState
  ├─ TaskDisplayArea B (分屏右 / 嵌入 pane / PiP)
  │     └─ Task(pinned) → PiP 小窗
  └─ freeform Task (可缩放浮窗)
        ↑ ShellTaskOrganizer(WMShell) 用 transaction 改 bounds/layer
        ↑ ATMS 决定"谁进哪个 TDA"
```

**一句话记忆链**：屏由 DisplayAdapter 注册成 LogicalDisplay，WMS 镜像成 DisplayContent；Activity 用 launchDisplayId 选屏，多窗口由 ATMS 调度 + WMShell 画 UI，车机再用 OccupantZone 把"人/用户/屏"绑成一环。

---

## 18. 关联阅读

| 篇 | 关系 | 何时去读 |
|---|---|---|
| `11_WMS机制详解-从窗口添加到显示管理` | 单屏窗口管理（token/层级/焦点） | 先懂单屏再看本篇"屏维度" |
| `10_ATMS机制详解`（建议篇） | Activity/Task 启动与生命周期 | 想深挖选屏前的启动流程 |
| `13_Input机制详解`（建议篇） | 输入事件 per-display 分发 | 排"触控送错屏"时回看 |
| `18_SystemUI机制详解-从状态栏到锁屏与通知面板` | 第 16 节 per-display SystemUI | 排副屏状态栏/通知时衔接 |
| `19_多用户机制详解`（建议篇） | 用户切换与隔离 | 理解 zone↔userId 绑定的底座 |
| `21_Audio机制详解`（建议篇） | 多屏音频焦点 | 车机副驾独立声音隔离 |
| `22_图形渲染机制详解`（建议篇） | SurfaceFlinger/Layer | 屏到 Layer 的合成细节 |
| `automotive/01-AAOS整体架构与CarService` | AAOS 整体架构 | 本篇车机章是其"多屏细分" |
| `automotive/03-VehicleHAL-VHAL详解` | 车辆信号 HAL | 仪表屏数据来源（车速等） |

---





---


```

---

