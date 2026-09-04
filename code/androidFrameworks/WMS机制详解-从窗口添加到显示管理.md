# WMS 机制详解：从窗口添加到显示管理

> 这篇从 App 侧最容易看到的窗口现象切入：`setContentView()` 后窗口怎么出现，`Dialog.show()` 为什么会报 `BadTokenException`，悬浮窗为什么需要权限，输入法为什么会顶起页面，多屏窗口为什么会跑错 display。目标是把 App 的 Window/View 体验和 Framework 里的 WMS 串起来。
> 目标读者：已经了解 AMS/ATMS/PMS 基本职责，希望进一步理解 Android 窗口系统、Window token、窗口层级、焦点、输入法、多屏和 WMS 与 SurfaceFlinger 关系的开发者。

---

## 目录

1. [先建立直觉：WMS 是什么](#1先建立直觉wms-是什么)
2. [WMS 和 ATMS、AMS、SurfaceFlinger 的关系](#2wms-和-atmsamssurfaceflinger-的关系)
3. [从 App 侧看 WMS：哪些现象和它有关](#3从-app-侧看-wms哪些现象和它有关)
4. [WMS 涉及到的核心类](#4wms-涉及到的核心类)
5. [Activity 窗口是怎么添加到 WMS 的](#5activity-窗口是怎么添加到-wms-的)
6. [Dialog、Toast、PopupWindow、悬浮窗有什么区别](#6dialogtoastpopupwindow悬浮窗有什么区别)
7. [Window token：为什么会有 BadTokenException](#7window-token为什么会有-badtokenexception)
8. [窗口类型、层级和 Z-order](#8窗口类型层级和-z-order)
9. [窗口焦点与输入焦点](#9窗口焦点与输入焦点)
10. [输入法窗口和软键盘调整](#10输入法窗口和软键盘调整)
11. [窗口布局、Insets 和系统栏](#11窗口布局insets-和系统栏)
12. [WMS 与 SurfaceFlinger：窗口到图层的桥](#12wms-与-surfaceflinger窗口到图层的桥)
13. [多屏、多窗口和车载显示](#13多屏多窗口和车载显示)
14. [常见问题与排查方法](#14常见问题与排查方法)
15. [第三方系统常见修改点](#15第三方系统常见修改点)
16. [读源码的推荐路线](#16读源码的推荐路线)
17. [关键源码路径速查](#17关键源码路径速查)
18. [一图总结](#18一图总结)

---

## 1. 先建立直觉：WMS 是什么

**一句话：WMS 是 Android 系统里负责“管理窗口秩序”的系统服务。**

App 里你经常接触这些操作：

```java
setContentView(R.layout.main);
dialog.show();
popupWindow.showAsDropDown(anchor);
windowManager.addView(view, params);
```

这些操作最终都会和“窗口”有关。WMS 关心的不是按钮文字怎么画、图片怎么解码，而是：

- 这个窗口能不能添加？
- 这个窗口属于哪个 Activity 或哪个 token？
- 窗口是什么类型：Activity、Dialog、Toast、输入法、状态栏、悬浮窗？
- 窗口多大，放在哪里？
- 哪个窗口在上面，哪个在下面？
- 哪个窗口拿焦点，输入事件应该给谁？
- 多屏场景下窗口属于哪个 display？
- 系统栏、刘海、软键盘、手势区域如何影响窗口布局？

所以可以这样记：

> App/View 负责画内容，WMS 负责管理窗口规则，SurfaceFlinger 负责把窗口对应的图层合成上屏。

---

## 2. WMS 和 ATMS、AMS、SurfaceFlinger 的关系

WMS 不是孤立工作的。一次 Activity 显示出来，至少要经过 ATMS、AMS、App 进程、WMS、SurfaceFlinger。

```text
App 调 startActivity
        │
        ▼
ATMS
  决定 Activity 启动、Task、生命周期
        │
        ▼
AMS
  确保目标进程存在，维护进程状态
        │
        ▼
App 进程
  ActivityThread 创建 Activity，setContentView
        │
        ▼
WMS
  接收窗口、管理层级、焦点、布局、display
        │
        ▼
SurfaceFlinger
  合成所有 Layer，送到屏幕
```

几个服务的分工：

| 模块 | 负责什么 | 不负责什么 |
|---|---|---|
| ATMS | Activity、Task、启动模式、生命周期调度 | 不负责像素合成 |
| AMS | 进程、Service、Broadcast、Provider、OOM、ANR | 不负责窗口层级 |
| WMS | 窗口添加、布局、层级、焦点、Insets、display | 不负责 App 内部 View 怎么画 |
| App/View/RenderThread | 具体 UI 内容绘制 | 不决定系统窗口层级 |
| SurfaceFlinger | Layer 合成、VSYNC、HWC、最终上屏 | 不决定 Activity 启动规则 |

WMS 位于“组件生命周期”和“最终上屏”之间。Activity resume 了，不代表窗口一定已经显示；窗口添加到 WMS 了，也不代表 SurfaceFlinger 已经拿到有效 buffer。

---

## 3. 从 App 侧看 WMS：哪些现象和它有关

| App 侧现象 | 更可能涉及的 WMS 逻辑 |
|---|---|
| `Dialog.show()` 报 `BadTokenException` | token 无效、Activity 已 finish、窗口类型不允许 |
| 悬浮窗加不上 | `TYPE_APPLICATION_OVERLAY` 权限、AppOps、窗口类型限制 |
| Toast 显示异常 | Toast 窗口策略、后台限制、通知权限或系统定制 |
| 输入法遮挡页面 | Insets、softInputMode、IME 窗口布局 |
| 点击事件给错窗口 | 输入焦点、触摸窗口查找、窗口 flags |
| Activity 黑屏或白屏 | 窗口已创建但 App 未提交有效 buffer，或等待首帧 |
| 多屏启动后窗口跑错屏 | displayId、WindowContext、ActivityOptions、TaskDisplayArea |
| 锁屏上弹窗失败 | showWhenLocked、窗口类型、权限、Keyguard 策略 |
| 状态栏/导航栏遮挡内容 | decorFitsSystemWindows、Insets 分发、系统栏策略 |
| PopupWindow 位置错 | anchor 坐标、窗口布局、display cutout/insets |

WMS 的学习入口很适合从这些现象开始，因为它们都能在 dumpsys window 和 logcat 中找到对应状态。

---

## 4. WMS 涉及到的核心类

### 4.1 App 侧类

| 类 | 作用 |
|---|---|
| `Window` | 抽象窗口，Activity 通常使用 `PhoneWindow` |
| `PhoneWindow` | Activity/Dialog 的窗口实现，管理 DecorView |
| `DecorView` | 顶层 View，承载 App 内容和窗口装饰 |
| `WindowManager` | App 侧窗口管理入口 |
| `WindowManagerImpl` | `WindowManager` 的实现，转给 `WindowManagerGlobal` |
| `WindowManagerGlobal` | 进程内窗口全局管理，维护 ViewRootImpl 列表 |
| `ViewRootImpl` | View 树和 WMS 的桥，负责 measure/layout/draw、输入分发、窗口 session |
| `WindowManager.LayoutParams` | 窗口类型、大小、flag、softInputMode、format 等参数 |

### 4.2 Binder 接口

| 接口 | 作用 |
|---|---|
| `IWindowManager` | App/system_server 访问 WMS 的 Binder 接口 |
| `IWindowSession` | 一个 App 进程和 WMS 的窗口会话，用于 add/remove/relayout |
| `IWindow` | App 提供给 WMS 的窗口回调接口，通常由 `ViewRootImpl.W` 实现 |

### 4.3 system_server 侧类

| 类 | 作用 |
|---|---|
| `WindowManagerService` | WMS 主服务，窗口管理核心 |
| `Session` | WMS 中代表一个客户端进程的窗口会话 |
| `WindowState` | system_server 眼中的一个窗口 |
| `WindowToken` | 一组窗口的 token，表达窗口归属和合法性 |
| `ActivityRecord` | Activity 记录，和 Activity 窗口 token 紧密关联 |
| `DisplayContent` | 一个 display 的窗口、任务和显示状态 |
| `WindowStateAnimator` | 窗口动画和 Surface 状态相关管理 |
| `WindowSurfaceController` | WMS 侧控制窗口 Surface 的封装 |
| `InsetsStateController` | 管理系统栏、输入法、cutout 等 Insets 状态 |
| `InputMonitor` | 协调窗口和输入系统，更新可接收输入的窗口信息 |
| `RootWindowContainer` | WMS/ATMS 中全局窗口与任务容器根节点 |

---

## 5. Activity 窗口是怎么添加到 WMS 的

Activity 创建后，`setContentView()` 只是把布局装进 Activity 的 `DecorView`，真正把窗口交给系统通常发生在 Activity resume 附近。

### 5.1 App 侧链路

```text
ActivityThread.handleResumeActivity()
  └─ Activity.performResume()
       └─ onResume()
  └─ WindowManager.addView(decorView, layoutParams)
       └─ WindowManagerGlobal.addView()
            └─ 创建 ViewRootImpl
                 └─ ViewRootImpl.setView()
                      └─ IWindowSession.addToDisplayAsUser()
                           └─ Binder 到 WMS
```

这里要注意：`setContentView()` 不等于窗口已经显示。它只是设置内容 View；真正添加窗口要通过 `WindowManager.addView()` 进入 WMS。

### 5.2 WMS 侧链路

```text
WindowManagerService.addWindow()
  ├─ 校验 LayoutParams 和窗口类型
  ├─ 校验 token 是否有效
  ├─ 找到 DisplayContent
  ├─ 创建 WindowState
  ├─ 加入窗口列表和 token
  ├─ 更新焦点、布局、输入窗口
  └─ 创建/控制 SurfaceControl
```

WMS 成功接收窗口后，App 还要完成绘制并提交 buffer，SurfaceFlinger 才能最终合成显示。

### 5.3 为什么 Activity resume 了但画面还没出来

常见原因：

- Activity 主线程在 `onResume()` 或之后卡住，没有完成首帧绘制。
- 窗口已经 add，但还没有 relayout 或没有有效 Surface。
- App 没有提交 buffer，SurfaceFlinger 没有可合成内容。
- WMS 等待 starting window、转场动画或窗口可见性变化。
- 多屏/窗口模式导致窗口加到了非预期 display。

---

## 6. Dialog、Toast、PopupWindow、悬浮窗有什么区别

这些在 App 看来都是“弹个东西”，但在 WMS 眼里它们是不同类型的窗口。

| 类型 | 常见窗口类型 | 依赖 token | 特点 |
|---|---|---|---|
| Activity 主窗口 | `TYPE_BASE_APPLICATION` | Activity token | Activity 的主窗口 |
| Dialog | `TYPE_APPLICATION` / panel 类 | 通常依赖 Activity token | 依附 Activity，Activity 死了 Dialog 也不能显示 |
| PopupWindow | application panel/sub-panel | 依赖 anchor 所在窗口 token | 位置跟 anchor 和父窗口有关 |
| Toast | Toast 类型或通知路径，版本差异大 | 受系统策略限制 | 后台限制越来越多 |
| 悬浮窗 | `TYPE_APPLICATION_OVERLAY` | 不依赖具体 Activity token | 需要悬浮窗权限和 AppOps |
| 输入法 | `TYPE_INPUT_METHOD` | 系统管理 | 影响焦点和 Insets |
| 状态栏/导航栏 | system bar 类型 | 系统进程 | SystemUI 管理，层级较高 |

### 6.1 Dialog 为什么依赖 Activity

Dialog 的窗口通常附着在 Activity token 上。Activity finish 后 token 失效，再 show Dialog 就可能报：

```text
android.view.WindowManager$BadTokenException
```

这不是 Dialog 本身无法 new，而是 WMS 不允许把一个依附已销毁 Activity 的窗口加入系统。

### 6.2 悬浮窗为什么特殊

悬浮窗不依附具体 Activity，可以覆盖在其他 App 上，所以系统必须严格管控：

- 需要 `SYSTEM_ALERT_WINDOW` 权限。
- 需要 AppOps 允许。
- Android 8.0 后普通 App 使用 `TYPE_APPLICATION_OVERLAY`。
- 锁屏、状态栏、输入法上方显示会受额外限制。

---

## 7. Window token：为什么会有 BadTokenException

Window token 是 WMS 判断“这个窗口有没有合法归属”的关键。

### 7.1 token 的直观理解

可以把 token 理解成窗口的身份证或归属证明：

```text
ActivityRecord.token
        │
        ▼
Activity 主窗口 / Dialog / PopupWindow
```

Activity 窗口必须属于一个有效的 ActivityRecord；Dialog/PopupWindow 通常也要依附在某个 Activity 或父窗口上。

### 7.2 BadTokenException 常见原因

- Activity 已经 finish，异步回调里还在 `dialog.show()`。
- 使用 application context 创建 Dialog。
- 窗口类型和 token 不匹配。
- 悬浮窗没有权限却使用 overlay 类型。
- 在 Activity 还没 attach/window ready 时过早添加窗口。
- 多用户或多 display 下 token 所属环境不匹配。

### 7.3 App 侧规避建议

- Dialog 使用 Activity context。
- show 前判断 Activity 是否 finishing/destroyed。
- 异步回调返回时确认页面仍然存活。
- 悬浮窗使用正确 type，并检查权限。
- 不要持有旧 Activity 的 WindowManager 或 Context 长期使用。

---

## 8. 窗口类型、层级和 Z-order

WMS 要决定所有窗口谁盖住谁。这个顺序不是 App 随便决定的，而是由窗口类型、token、子窗口关系、系统策略共同决定。

### 8.1 常见窗口类型

| 类型 | 例子 |
|---|---|
| Application window | Activity、Dialog、PopupWindow |
| Input method window | 软键盘 |
| System window | 状态栏、导航栏、锁屏、系统弹窗 |
| Overlay window | 普通 App 悬浮窗 |
| Wallpaper window | 壁纸 |

### 8.2 flags 也会影响行为

常见 flags：

| flag | 影响 |
|---|---|
| `FLAG_NOT_FOCUSABLE` | 窗口不拿输入焦点 |
| `FLAG_NOT_TOUCHABLE` | 不接收触摸 |
| `FLAG_NOT_TOUCH_MODAL` | 窗口外触摸可传给后面窗口 |
| `FLAG_LAYOUT_NO_LIMITS` | 允许布局超出屏幕边界 |
| `FLAG_FULLSCREEN` | 请求全屏显示 |
| `FLAG_KEEP_SCREEN_ON` | 窗口可请求保持亮屏 |
| `FLAG_SECURE` | 禁止截图/录屏显示该窗口内容 |

WMS 会结合 type 和 flags 决定窗口层级、焦点、输入区域和可见性。

---

## 9. 窗口焦点与输入焦点

“窗口显示在最上面”和“窗口拿到输入焦点”不是一回事。

### 9.1 焦点窗口

WMS 会选择一个 focused window，它通常决定：

- 按键事件给谁。
- 输入法服务跟随谁。
- 窗口焦点变化回调。
- ANR 归因时当前输入目标是谁。

### 9.2 为什么点击给错窗口

触摸事件由输入系统根据窗口区域、层级、flags 判断目标窗口。常见影响因素：

- 窗口是否 touchable。
- 是否设置 `FLAG_NOT_TOUCH_MODAL`。
- 窗口透明区域是否可穿透。
- 悬浮窗是否覆盖目标区域。
- 输入法或系统窗口是否拦截。

WMS 会把窗口信息同步给 InputDispatcher。真正分发输入的是输入系统，但窗口边界、层级、焦点信息来自 WMS。

---

## 10. 输入法窗口和软键盘调整

输入法也是窗口，而且是非常特殊的系统窗口。

### 10.1 softInputMode

App 可以通过 manifest 或代码设置：

```xml
<activity
    android:name=".MainActivity"
    android:windowSoftInputMode="adjustResize" />
```

常见模式：

| 模式 | 直观效果 |
|---|---|
| `adjustResize` | 窗口可用区域变小，内容重新布局 |
| `adjustPan` | 窗口整体平移，让输入框露出 |
| `adjustNothing` | 系统不主动调整，App 自己处理 Insets |
| `stateVisible` | 进入时请求显示输入法 |
| `stateHidden` | 进入时请求隐藏输入法 |

### 10.2 Insets

现代 Android 更推荐用 Insets 理解系统栏和输入法影响：

```text
状态栏 Insets
导航栏 Insets
IME Insets
DisplayCutout Insets
手势区域 Insets
```

WMS 负责维护 Insets 状态，App 侧通过 `WindowInsets` 感知可用区域变化。

---

## 11. 窗口布局、Insets 和系统栏

系统栏不是简单盖在 App 上，它们会影响布局、安全区域和交互区域。

### 11.1 decorFitsSystemWindows

现代沉浸式布局经常使用：

```java
WindowCompat.setDecorFitsSystemWindows(window, false);
```

这意味着 App 希望内容延伸到系统栏区域，然后自己处理 Insets。

如果处理不好，就会出现：

- 内容被状态栏遮挡。
- 底部按钮被导航栏挡住。
- 刘海屏裁切异常。
- 横屏时安全区域不对。

### 11.2 WMS 的角色

WMS 不负责帮你写布局逻辑，但它负责计算并下发窗口可见区域、系统栏区域、输入法区域、cutout 区域。App 根据这些信息调整自己的 View。

---

## 12. WMS 与 SurfaceFlinger：窗口到图层的桥

WMS 管窗口，SurfaceFlinger 管图层合成。它们之间通过 SurfaceControl/Layer 连接。

```text
WMS
  WindowState
        │
        ▼
  SurfaceControl
        │ Binder/native
        ▼
SurfaceFlinger
  Layer
```

### 12.1 WMS 不画内容

WMS 不会绘制按钮、文本和列表。App 的 View/RenderThread 负责把内容画进 buffer。

WMS 更像是在告诉 SurfaceFlinger：

- 这个窗口对应的 Layer 应该在什么位置。
- 多大。
- 透明度是多少。
- 层级顺序是多少。
- 是否隐藏。
- 是否参与动画。

SurfaceFlinger 再把所有 Layer 的 buffer 合成成最终屏幕画面。

### 12.2 黑屏问题怎么分层看

- Activity 未启动：看 ATMS/AMS。
- Activity 已 resume，但没 add window：看 App/WMS。
- WindowState 有了，但没有 buffer：看 App 渲染/ViewRootImpl。
- Layer 有 buffer，但没上屏：看 SurfaceFlinger/HWC。

---

## 13. 多屏、多窗口和车载显示

WMS 在车机、平板、桌面模式里非常关键。

### 13.1 多屏窗口归属

窗口属于某个 display：

```text
DisplayContent #0：主屏
  WindowState A
  WindowState B

DisplayContent #1：副屏
  WindowState C
```

ATMS 决定 Activity/Task 启动到哪个 display，WMS 管这个 display 上有哪些窗口、如何布局、谁拿焦点。

### 13.2 车载常见问题

- Activity 启动到了中控，但窗口跑到仪表屏。
- 副屏 Activity 没焦点，按键事件送不到。
- 系统弹窗盖住倒车或导航。
- 输入法出现在错误 display。
- 多用户和多 display 组合导致窗口不可见。

排查时要同时看：

```bash
adb shell dumpsys activity activities
adb shell dumpsys window displays
adb shell dumpsys display
```

---

## 14. 常见问题与排查方法

### 14.1 BadTokenException

看：

- Dialog 使用的 Context 是否是 Activity。
- Activity 是否已经 finish/destroyed。
- 窗口 type 是否正确。
- 悬浮窗权限是否授予。

### 14.2 窗口不显示

排查：

```bash
adb shell dumpsys window windows | grep -i "mCurrentFocus\|mFocusedApp\|Window #\|packageName"
adb shell dumpsys activity top
adb logcat -b all | grep -i "WindowManager\|ViewRootImpl\|BadToken"
```

重点看：WindowState 是否存在、是否 visible、是否有 surface、是否在正确 display。

### 14.3 输入法遮挡或不弹

看：

- 当前 focused window 是谁。
- focused view 是否请求输入法。
- softInputMode 设置。
- Insets 是否正确消费。
- 是否有窗口设置 `FLAG_NOT_FOCUSABLE`。

### 14.4 焦点错乱

排查：

```bash
adb shell dumpsys window | grep -i "mCurrentFocus\|mFocusedApp\|InputMethodTarget\|FocusedDisplay"
adb shell dumpsys input
```

如果 WMS 焦点和 InputDispatcher 目标不一致，要看窗口更新是否及时、是否有悬浮窗或系统窗口干扰。

### 14.5 黑屏/白屏

按层次看：

1. ATMS：Activity 是否已 resumed。
2. WMS：窗口是否添加、是否 visible。
3. App：是否完成首帧绘制。
4. SurfaceFlinger：Layer 是否有 buffer、是否合成。

---

## 15. 第三方系统常见修改点

### 15.1 悬浮窗策略

需求：车载助手、悬浮球、告警提示覆盖在其他 App 上。

建议：

- 基于签名、uid、角色或明确包名白名单。
- 不要全局放开 overlay。
- 处理锁屏、输入法、状态栏、多屏层级。
- 保留 dumpsys 和日志，记录窗口 type、uid、display。

### 15.2 系统弹窗层级

需求：倒车、来电、告警、导航提示需要高层级显示。

风险：层级过高会遮挡输入法、状态栏、锁屏或安全提示。

建议：优先定义清晰的窗口类型和显示场景，不要随意复用系统最高层级窗口。

### 15.3 多屏窗口策略

需求：中控、仪表、后排屏分离显示。

建议：

- ATMS 决定 Activity/Task 到哪个 display。
- WMS 决定该 display 上窗口焦点、层级、Insets。
- 输入法、系统弹窗、权限弹窗也要指定 display 策略。

### 15.4 焦点和输入策略

需求：旋钮、方向盘按键、遥控器、触摸屏输入送给特定窗口。

建议：WMS 和 InputDispatcher 一起看，不要只改窗口层级。焦点策略要考虑多 display 和安全窗口。

### 15.5 系统栏和沉浸式策略

需求：隐藏状态栏/导航栏，定制手势区域，车机全屏。

风险：影响返回、Home、权限弹窗、输入法、安全区域。

建议：优先通过官方 Insets/SystemUI API 控制，Framework 定制要考虑所有窗口模式。

---

## 16. 读源码的推荐路线

### 16.1 Activity 窗口添加

```text
ActivityThread.handleResumeActivity
WindowManagerImpl.addView
WindowManagerGlobal.addView
ViewRootImpl.setView
Session.addToDisplayAsUser
WindowManagerService.addWindow
WindowState
```

### 16.2 relayout 和 Surface 创建

```text
ViewRootImpl.performTraversals
IWindowSession.relayout
WindowManagerService.relayoutWindow
WindowStateAnimator
WindowSurfaceController
SurfaceControl
```

### 16.3 焦点更新

```text
WindowManagerService.updateFocusedWindowLocked
DisplayContent.updateFocusedWindowLocked
InputMonitor.updateInputWindowsLw
InputDispatcher
```

### 16.4 Insets 分发

```text
InsetsStateController
InsetsSourceProvider
WindowInsets
ViewRootImpl.dispatchApplyInsets
```

---

## 17. 关键源码路径速查

| 内容 | 路径 |
|---|---|
| WMS 主服务 | `frameworks/base/services/core/java/com/android/server/wm/WindowManagerService.java` |
| WindowState | `frameworks/base/services/core/java/com/android/server/wm/WindowState.java` |
| DisplayContent | `frameworks/base/services/core/java/com/android/server/wm/DisplayContent.java` |
| WindowToken | `frameworks/base/services/core/java/com/android/server/wm/WindowToken.java` |
| Session | `frameworks/base/services/core/java/com/android/server/wm/Session.java` |
| Insets 管理 | `frameworks/base/services/core/java/com/android/server/wm/InsetsStateController.java` |
| InputMonitor | `frameworks/base/services/core/java/com/android/server/wm/InputMonitor.java` |
| App 侧 WindowManager | `frameworks/base/core/java/android/view/WindowManager.java` |
| WindowManagerGlobal | `frameworks/base/core/java/android/view/WindowManagerGlobal.java` |
| ViewRootImpl | `frameworks/base/core/java/android/view/ViewRootImpl.java` |
| PhoneWindow | `frameworks/base/core/java/com/android/internal/policy/PhoneWindow.java` |
| Window 参数 | `frameworks/base/core/java/android/view/WindowManager.java` |
| SurfaceControl | `frameworks/base/core/java/android/view/SurfaceControl.java` |
| SurfaceFlinger | `frameworks/native/services/surfaceflinger/` |

---

## 18. 一图总结

```text
App 进程
  Activity / Dialog / PopupWindow / Overlay
        │
        ▼
  Window / DecorView / ViewRootImpl
        │ IWindowSession Binder
        ▼
system_server
  WindowManagerService
        │
        ├─ 校验 token / type / permission
        ├─ 创建 WindowState
        ├─ 决定 display / size / position
        ├─ 计算层级和焦点
        ├─ 管理 Insets / IME / system bars
        └─ 通过 SurfaceControl 控制 Layer 属性
        │
        ▼
surfaceflinger 进程
  SurfaceFlinger
        │
        └─ 合成所有 Layer 并上屏
```

---

## 小结

- **WMS 是窗口秩序管理者**，负责窗口能不能添加、属于谁、显示在哪、多大、层级如何、焦点给谁。
- **WMS 不负责绘制 App 内容**，App/View/RenderThread 负责画内容，SurfaceFlinger 负责最终合成上屏。
- **Activity resume 不等于窗口已经显示**，还要经历 addWindow、relayout、App 绘制、buffer 提交、SurfaceFlinger 合成。
- **Window token 是窗口合法性的关键**，Dialog、PopupWindow、Activity 窗口都离不开正确 token。
- **输入法、系统栏、刘海、手势区域都可以用 Insets 理解**，WMS 负责维护这些区域状态并分发给 App。
- **多屏和车载场景下 WMS 必须和 ATMS、Input、SurfaceFlinger 一起看**，只看一个服务很容易误判。

如果只记一个核心模型：

> ATMS 决定哪个 Activity 该显示，WMS 决定它的窗口怎么显示，App 负责把内容画进 buffer，SurfaceFlinger 负责把所有窗口图层合成到屏幕上。
