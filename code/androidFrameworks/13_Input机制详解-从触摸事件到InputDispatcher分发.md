# Input 机制详解：从触摸事件到 InputDispatcher 分发

> 接在 WMS 后面理解最顺：WMS 决定哪个窗口能接收输入，Input 系统负责把触摸、按键、旋钮、遥控器等事件送到正确窗口，App 最终在 `View.dispatchTouchEvent()`、`onKeyDown()` 里收到事件。

---

## 目录

1. [Input 系统是什么](#1input-系统是什么)
2. [和 WMS 的关系](#2和-wms-的关系)
3. [一次触摸事件的旅程](#3一次触摸事件的旅程)
4. [核心类和模块](#4核心类和模块)
5. [InputReader：从设备读取事件](#5inputreader从设备读取事件)
6. [InputDispatcher：把事件分发给窗口](#6inputdispatcher把事件分发给窗口)
7. [App 侧如何收到事件](#7app-侧如何收到事件)
8. [Input ANR](#8input-anr)
9. [常见问题与排查](#9常见问题与排查)
10. [第三方系统常见修改点](#10第三方系统常见修改点)
11. [源码路径速查](#11源码路径速查)

---

## 1. Input 系统是什么

Input 系统负责把底层输入设备产生的事件送到正确的应用窗口。

常见输入包括：

- 触摸屏。
- 物理按键。
- 鼠标、键盘、触控板。
- 车机旋钮、方向盘按键。
- 遥控器、游戏手柄。

一句话：

> WMS 管“哪个窗口能接输入”，Input 系统管“事件如何从设备送到这个窗口”。

---

## 2. 和 WMS 的关系

InputDispatcher 分发事件前需要知道当前窗口信息：

- 哪个窗口有焦点。
- 每个窗口的位置和可触摸区域。
- 窗口是否 touchable/focusable。
- 窗口层级顺序。
- 哪个 display 上有哪些窗口。

这些信息主要来自 WMS：

```text
WMS
  WindowState / InputWindowHandle
        │
        ▼
InputDispatcher
  根据窗口信息命中目标窗口
```

所以点击无响应时不能只看 App，也要看 WMS 焦点和 InputDispatcher 状态。

---

## 3. 一次触摸事件的旅程

```text
触摸屏硬件
  └─ Linux input event
       └─ EventHub 读取 /dev/input
            └─ InputReader 解析为 MotionEvent
                 └─ InputDispatcher 找目标窗口
                      └─ InputChannel 发送到 App
                           └─ ViewRootImpl 接收
                                └─ DecorView.dispatchTouchEvent
                                     └─ Activity.dispatchTouchEvent
                                          └─ ViewGroup / View onTouchEvent
```

这条链路解释了两个现象：

- 窗口显示出来，不代表一定能收到触摸。
- App 主线程卡住，会导致 InputDispatcher 等不到事件处理完成，最终触发 Input ANR。

---

## 4. 核心类和模块

| 模块 / 类 | 作用 |
|---|---|
| `InputManagerService` | Java system_server 服务，管理输入设备和 native input |
| `NativeInputManager` | Java IMS 和 native input 的桥 |
| `EventHub` | 读取 `/dev/input` 设备事件 |
| `InputReader` | 把底层事件解析成 Android 输入事件 |
| `InputDispatcher` | 根据窗口、焦点、触摸区域分发事件 |
| `InputChannel` | system_server/native 与 App 之间传输入事件的通道 |
| `ViewRootImpl` | App 侧接收输入并送入 View 树 |
| `WindowInputEventReceiver` | App 侧输入事件接收器 |
| `InputMonitor` | WMS 侧同步窗口输入信息给 InputDispatcher |

---

## 5. InputReader：从设备读取事件

`InputReader` 更靠近硬件输入。它会：

- 监听 `/dev/input/event*`。
- 识别设备类型。
- 处理触摸坐标、按键码、轴数据。
- 做坐标转换、校准、display 映射。
- 生成 `KeyEvent`、`MotionEvent` 等高层事件。

车机里方向盘按键、旋钮、触摸屏坐标不准，很多时候要从 InputReader、keylayout、idc 配置查起。

---

## 6. InputDispatcher：把事件分发给窗口

`InputDispatcher` 负责决定事件给谁。

它会考虑：

- focused window。
- touched window。
- displayId。
- 窗口层级。
- 窗口 touchable region。
- `FLAG_NOT_TOUCHABLE`、`FLAG_NOT_FOCUSABLE`、`FLAG_NOT_TOUCH_MODAL`。
- ANR 超时。

如果目标窗口迟迟不消费输入，InputDispatcher 会等待，超过阈值后触发 Input ANR。

---

## 7. App 侧如何收到事件

App 侧大致链路：

```text
InputChannel
  └─ WindowInputEventReceiver.onInputEvent
       └─ ViewRootImpl.enqueueInputEvent
            └─ ViewRootImpl.doProcessInputEvents
                 └─ DecorView.dispatchTouchEvent
                      └─ Activity.dispatchTouchEvent
                           └─ ViewGroup.dispatchTouchEvent
                                └─ View.onTouchEvent
```

按键事件类似，会进入 `dispatchKeyEvent()`、`onKeyDown()` 等回调。

---

## 8. Input ANR

Input ANR 的典型原因是：InputDispatcher 把事件发给目标窗口后，App 没有及时处理。

常见根因：

- App 主线程卡住。
- 主线程正在等 Binder 返回。
- 主线程等待锁。
- system_server 卡住导致输入分发延迟。
- 焦点窗口异常，事件送到了不可响应窗口。

排查顺序：

1. 看 ANR reason 是否是 input dispatching timed out。
2. 看当前 focused window。
3. 看目标 App 主线程栈。
4. 如果主线程等 Binder，继续查对端。
5. 看 `dumpsys input` 和 `dumpsys window`。

---

## 9. 常见问题与排查

### 点击无响应

```bash
adb shell dumpsys window | grep -i "mCurrentFocus\|mFocusedApp"
adb shell dumpsys input
adb logcat -b all | grep -i "InputDispatcher\|InputReader\|ANR"
```

重点看：焦点窗口是谁、触摸区域是否正确、App 主线程是否卡住。

### 按键不生效

看 keylayout 和输入设备：

```bash
adb shell getevent -l
adb shell dumpsys input
```

### 多屏触摸错位

看触摸设备映射到哪个 display，以及 WMS display/window 信息。

---

## 10. 第三方系统常见修改点

- 车机方向盘按键映射。
- 旋钮和遥控器键值定制。
- 多屏触摸设备和 display 绑定。
- 特殊窗口焦点策略。
- 输入事件拦截，例如驾驶安全限制。

原则：输入定制要同时看 InputReader、InputDispatcher、WMS 焦点和 App 事件分发，不要只改一个点。

---

## 11. 源码路径速查

| 内容 | 路径 |
|---|---|
| InputManagerService | `frameworks/base/services/core/java/com/android/server/input/InputManagerService.java` |
| native input | `frameworks/native/services/inputflinger/` |
| EventHub | `frameworks/native/services/inputflinger/reader/EventHub.cpp` |
| InputReader | `frameworks/native/services/inputflinger/reader/InputReader.cpp` |
| InputDispatcher | `frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp` |
| ViewRootImpl | `frameworks/base/core/java/android/view/ViewRootImpl.java` |
| WMS InputMonitor | `frameworks/base/services/core/java/com/android/server/wm/InputMonitor.java` |
