# SurfaceFlinger 机制详解：从 Buffer 到屏幕合成

> 这篇从“窗口已经有了，为什么屏幕还没显示”切入，讲清楚 SurfaceFlinger 在 Android 图形系统里的位置：它不是 WMS，也不负责 App 里按钮文字怎么画；它负责接收各个 Surface/Layer 的 buffer，把它们按层级、变换、透明度和显示策略合成成最终画面。
> 目标读者：已经了解 AMS/ATMS/WMS 基础，希望进一步理解 Surface、BufferQueue、Layer、VSYNC、HWC、掉帧、黑屏、视频/SurfaceView、多屏显示的 Android 开发者。

---

## 目录

1. [先建立直觉：SurfaceFlinger 是什么](#1先建立直觉surfaceflinger-是什么)
2. [SurfaceFlinger 和 WMS、App 渲染的关系](#2surfaceflinger-和-wmsapp-渲染的关系)
3. [从 App 侧看 SurfaceFlinger：哪些现象和它有关](#3从-app-侧看-surfaceflinger哪些现象和它有关)
4. [Surface、Buffer、Layer 的基本概念](#4surfacebufferlayer-的基本概念)
5. [BufferQueue：生产者和消费者模型](#5bufferqueue生产者和消费者模型)
6. [一次 App 画面上屏的完整流程](#6一次-app-画面上屏的完整流程)
7. [SurfaceFlinger 涉及到的核心类](#7surfaceflinger-涉及到的核心类)
8. [VSYNC、Choreographer 和刷新节奏](#8vsyncchoreographer-和刷新节奏)
9. [HWC：硬件合成是怎么参与的](#9hwc硬件合成是怎么参与的)
10. [SurfaceView、TextureView、普通 View 的区别](#10surfaceviewtextureview普通-view-的区别)
11. [WMS 到 SurfaceFlinger：SurfaceControl 和 Transaction](#11wms-到-surfaceflingersurfacecontrol-和-transaction)
12. [多屏、虚拟显示和投屏](#12多屏虚拟显示和投屏)
13. [常见问题与排查方法](#13常见问题与排查方法)
14. [第三方系统常见修改点](#14第三方系统常见修改点)
15. [读源码的推荐路线](#15读源码的推荐路线)
16. [关键源码路径速查](#16关键源码路径速查)
17. [一图总结](#17一图总结)

---

## 1. 先建立直觉：SurfaceFlinger 是什么

**一句话：SurfaceFlinger 是 Android 的图层合成服务，负责把各个应用和系统窗口提交的画面合成到屏幕上。**

它是一个独立 native 进程，不运行在 `system_server`：

```text
system_server
  AMS / ATMS / PMS / WMS / IMS ...

surfaceflinger
  SurfaceFlinger
```

App 里按钮、文字、列表是谁画的？是 App 的 View 系统、RenderThread、Skia、OpenGL/Vulkan 等图形栈。

WMS 做什么？管理窗口规则：窗口大小、位置、层级、焦点、Insets、display。

SurfaceFlinger 做什么？把这些窗口对应的 Layer 统一合成：

```text
App 窗口 Layer
状态栏 Layer
导航栏 Layer
输入法 Layer
SurfaceView 视频 Layer
动画 Layer
壁纸 Layer
        │
        ▼
SurfaceFlinger 合成
        │
        ▼
屏幕显示最终画面
```

所以准确说：

> App 负责画内容，WMS 负责摆窗口，SurfaceFlinger 负责合成所有图层并上屏。

---

## 2. SurfaceFlinger 和 WMS、App 渲染的关系

### 2.1 三者分工

| 模块 | 负责什么 | 典型问题 |
|---|---|---|
| App/View/RenderThread | UI 内容绘制，生成 buffer | View 绘制慢、首帧慢、UI 内容错 |
| WMS | 窗口管理，控制 Layer 的位置、层级、可见性 | 窗口不显示、焦点错、BadToken、Insets 错 |
| SurfaceFlinger | Layer 合成、buffer 消费、VSYNC、HWC、上屏 | 掉帧、黑屏、Layer 无 buffer、合成异常 |

### 2.2 一次显示大概经历什么

```text
App
  View 绘制 / RenderThread / GPU
        │
        ▼
  把画面写进 GraphicBuffer
        │ queueBuffer
        ▼
BufferQueue
        │ acquireBuffer
        ▼
SurfaceFlinger
  拿到各个 Layer 的 buffer
        │
        ├─ GPU 合成
        └─ HWC 硬件合成
        │
        ▼
Display
  屏幕刷新显示
```

WMS 在这条链路里主要控制 Layer 的属性：位置、裁剪、层级、透明度、可见性、变换、display 归属等。

---

## 3. 从 App 侧看 SurfaceFlinger：哪些现象和它有关

| 现象 | 可能相关点 |
|---|---|
| Activity 有窗口但黑屏 | App 没提交 buffer，或 Layer 不可见/无 buffer |
| 视频 SurfaceView 黑屏 | Surface 生命周期、BufferQueue、Layer 层级、HWC overlay |
| 页面掉帧 | App 绘制慢、SurfaceFlinger 合成慢、HWC present 慢、VSYNC miss |
| 屏幕花屏 | buffer 格式、HWC、驱动、显示硬件问题 |
| 截图正常但屏幕异常 | HWC/Display 路径问题，或者 secure/protected layer 差异 |
| 屏幕正常但截图少内容 | secure layer、硬件 overlay、权限限制 |
| 多屏某个屏不显示 | DisplayDevice、Layer display 归属、WMS display 策略 |
| 动画卡顿 | transaction 提交、Layer 变换、合成负载、App 帧生产慢 |
| SurfaceView 盖住其他 View | 独立 Layer 层级和 View 层级不是一回事 |

SurfaceFlinger 问题通常要结合 App 渲染、WMS 状态和 dumpsys SurfaceFlinger 一起看。

---

## 4. Surface、Buffer、Layer 的基本概念

### 4.1 Surface

`Surface` 可以理解成 App 画图的入口。App 拿到 Surface 后，可以通过 Canvas、OpenGL、Vulkan、MediaCodec 等方式往里面写画面。

```text
App
  Surface.lockCanvas / EGL / Vulkan / MediaCodec
        │
        ▼
  写入 buffer
```

### 4.2 Buffer

Buffer 是真正装像素数据的内存块，常见底层类型是 `GraphicBuffer` / gralloc buffer。

一个 buffer 里包含：

- 宽高。
- 像素格式。
- usage 标记。
- fence 同步信息。
- 具体图像内容。

### 4.3 Layer

Layer 是 SurfaceFlinger 眼中的合成单位。一个窗口、SurfaceView、状态栏、导航栏、壁纸，都可能对应一个或多个 Layer。

Layer 不只包含 buffer，还包含合成属性：

- 位置。
- 大小。
- alpha。
- z-order。
- crop。
- transform。
- visible/hidden。
- 目标 display。

### 4.4 三者关系

```text
App 持有 Surface
  Surface 背后连接 BufferQueue producer
        │
        ▼
App 生产 GraphicBuffer
        │
        ▼
SurfaceFlinger 的 Layer 作为消费者拿 buffer
        │
        ▼
参与最终合成
```

---

## 5. BufferQueue：生产者和消费者模型

BufferQueue 是理解 SurfaceFlinger 的核心。

### 5.1 谁生产，谁消费

| 场景 | 生产者 | 消费者 |
|---|---|---|
| 普通 App UI | App RenderThread / HWUI | SurfaceFlinger |
| SurfaceView 视频 | MediaCodec / Camera / GL | SurfaceFlinger |
| TextureView | MediaCodec / Camera / GL | App 进程中的 TextureLayer，再进入 App 主窗口 buffer |
| 截屏 | SurfaceFlinger | 截屏调用方 |

最常见模式：App 是 producer，SurfaceFlinger 是 consumer。

### 5.2 buffer 生命周期

```text
dequeueBuffer
  App 申请一个空闲 buffer
        │
        ▼
App 绘制内容
        │
        ▼
queueBuffer
  把画好的 buffer 入队
        │
        ▼
SurfaceFlinger acquireBuffer
  拿到 buffer 参与合成
        │
        ▼
present 后 releaseBuffer
  buffer 回到可复用状态
```

如果 App 生产太慢，SurfaceFlinger 没有新 buffer；如果 SurfaceFlinger/HWC 消费太慢，App 可能 dequeue 阻塞，出现掉帧。

### 5.3 fence 是什么

图形系统里 CPU、GPU、显示硬件并行工作。fence 用来表示“这块 buffer 什么时候真的可用”。

- acquire fence：消费者拿 buffer 前要等生产者完成写入。
- release fence：生产者复用 buffer 前要等消费者完成使用。

很多偶现花屏、撕裂、显示旧帧问题都和同步/fence/驱动有关。

---

## 6. 一次 App 画面上屏的完整流程

以普通 Activity UI 为例：

```text
Choreographer 收到 VSYNC
  └─ ViewRootImpl.doTraversal
       ├─ measure
       ├─ layout
       └─ draw
            └─ RenderThread / HWUI / Skia
                 └─ GPU 渲染到 GraphicBuffer
                      └─ queueBuffer 到 BufferQueue
                           └─ SurfaceFlinger 收到 Layer 有新 buffer
                                └─ 下一个合成周期 latch buffer
                                     └─ GPU/HWC 合成
                                          └─ present 到屏幕
```

这里有几个关键点：

- App 绘制和 SurfaceFlinger 合成是两个阶段。
- App 按 VSYNC 生产帧，SurfaceFlinger 也按 VSYNC 合成帧。
- 一帧能不能按时显示，取决于 App 是否按时提交 buffer，以及 SurfaceFlinger/HWC 是否按时 present。
- WMS 不参与每个像素的绘制，但会影响 Layer 可见性、位置和层级。

---

## 7. SurfaceFlinger 涉及到的核心类

### 7.1 native 服务核心类

| 类 | 作用 |
|---|---|
| `SurfaceFlinger` | 主服务，管理 Layer、display、合成和事务 |
| `Layer` | 合成单位，保存 buffer 和显示属性 |
| `BufferLayer` | 持有 buffer 的 Layer 类型 |
| `BufferQueueLayer` | 基于 BufferQueue 的 Layer |
| `DisplayDevice` | SurfaceFlinger 中的显示设备抽象 |
| `CompositionEngine` | 合成引擎，组织合成计划 |
| `HWComposer` | 和 HWC HAL 交互的封装 |
| `Scheduler` | VSYNC、刷新调度、帧率策略 |
| `Transaction` | SurfaceControl 属性变更的事务 |

### 7.2 App/framework 侧相关类

| 类 | 作用 |
|---|---|
| `Surface` | App 绘制入口 |
| `SurfaceView` | 拥有独立 Surface/Layer 的 View |
| `TextureView` | 内容作为纹理合成进 App 自己的 View 层级 |
| `SurfaceControl` | 控制 Layer 创建、显示、隐藏、层级、位置等 |
| `ViewRootImpl` | App View 树和 Surface 的连接点 |
| `ThreadedRenderer` | Android HWUI 渲染入口之一 |

### 7.3 HAL 和底层模块

| 模块 | 作用 |
|---|---|
| HWC / Composer HAL | 硬件合成接口 |
| gralloc | 图形 buffer 分配 |
| DRM/KMS 或厂商显示驱动 | Linux 显示输出和硬件驱动 |
| RenderEngine | SurfaceFlinger 使用 GPU 合成时的渲染引擎 |

---

## 8. VSYNC、Choreographer 和刷新节奏

屏幕不是随时显示新画面，而是按刷新节奏显示。60Hz 屏幕大约每 16.6ms 一次刷新，120Hz 大约每 8.3ms。

### 8.1 App VSYNC

App 通过 `Choreographer` 收到 VSYNC 信号后开始一帧：

```text
VSYNC-app
  └─ input
  └─ animation
  └─ traversal
       └─ measure/layout/draw
```

如果 App 主线程或 RenderThread 超时，就会 miss frame。

### 8.2 SurfaceFlinger VSYNC

SurfaceFlinger 也按 VSYNC 做合成：

```text
VSYNC-sf
  └─ latch 各 Layer 新 buffer
  └─ 计算可见区域和合成策略
  └─ GPU/HWC 合成
  └─ present
```

### 8.3 掉帧怎么产生

常见掉帧来源：

- App 主线程太忙，没及时开始绘制。
- RenderThread/GPU 渲染太慢，没及时 queueBuffer。
- SurfaceFlinger 合成负载高。
- HWC present 慢。
- BufferQueue 阻塞。
- CPU/GPU 频率、温控、内存带宽不足。

Perfetto 是分析这类问题的首选工具，因为它能同时看到 App、SurfaceFlinger、RenderThread、GPU、HWC 和 VSYNC。

---

## 9. HWC：硬件合成是怎么参与的

SurfaceFlinger 不一定总是用 GPU 把所有 Layer 合成成一张图。有些 Layer 可以交给硬件合成器 HWC 直接处理。

### 9.1 GPU 合成和 HWC 合成

| 合成方式 | 特点 |
|---|---|
| GPU 合成 | SurfaceFlinger/RenderEngine 把多个 Layer 绘制成目标 buffer |
| HWC 合成 | 显示硬件直接处理多个 layer/plane，更省电或更高效 |

SurfaceFlinger 会和 HWC 协商：哪些 Layer 可以 overlay，哪些必须 GPU 合成。

### 9.2 为什么视频常走硬件 overlay

视频画面通常是独立 Surface，格式和尺寸适合硬件 overlay。这样可以减少 GPU 拷贝和合成开销，提高性能、降低功耗。

但这也会带来排查复杂度：

- 截图可能看不到 protected 视频层。
- 屏幕看到黑，截图正常或相反。
- HWC 资源不足时 overlay 退回 GPU 合成。
- 多屏输出时某些 Layer 不能在目标 display 上硬件合成。

---

## 10. SurfaceView、TextureView、普通 View 的区别

### 10.1 普通 View

普通 View 绘制进 Activity 主窗口的 buffer。

```text
TextView / Button / RecyclerView
        │
        ▼
Activity 主窗口 Surface
        │
        ▼
SurfaceFlinger 中一个主窗口 Layer
```

### 10.2 SurfaceView

SurfaceView 有独立 Surface/Layer，常用于视频、相机、游戏。

```text
Activity 主窗口 Layer
SurfaceView 独立 Layer
```

优点：性能好，适合高频视频/相机。缺点：它的层级和普通 View 层级不是完全一回事，历史上容易出现遮挡、动画、透明、截图相关问题。

### 10.3 TextureView

TextureView 的内容作为纹理进入 App 自己的 View 层级，最终合成进 Activity 主窗口 buffer。

优点：更容易和普通 View 做动画、裁剪、透明。缺点：可能多一次纹理采样和 GPU 开销，不一定适合所有高性能视频场景。

### 10.4 直观选择

| 需求 | 常见选择 |
|---|---|
| 普通 UI | View |
| 高性能视频/相机预览 | SurfaceView |
| 需要和 View 做复杂动画/透明/裁剪的视频 | TextureView |
| 游戏/GL/Vulkan | SurfaceView 或直接 Surface |

---

## 11. WMS 到 SurfaceFlinger：SurfaceControl 和 Transaction

WMS 不直接画图，但它会通过 `SurfaceControl` 控制 Layer 属性。

### 11.1 SurfaceControl 是什么

`SurfaceControl` 可以理解成 Layer 的控制手柄。通过它可以设置：

- position
- size
- layer/z-order
- alpha
- crop
- matrix/transform
- show/hide
- reparent
- corner radius
- buffer

这些修改通常通过 transaction 批量提交：

```text
SurfaceControl.Transaction
  setPosition
  setLayer
  setAlpha
  show / hide
  apply
```

### 11.2 为什么要事务

窗口动画或布局变化往往涉及多个 Layer 同时变化。如果一个属性一个属性立即生效，就可能出现中间状态闪烁。Transaction 能保证一组 Layer 属性在同一个提交点生效。

WMS、Shell、SystemUI、动画系统都会大量使用 SurfaceControl.Transaction。

---

## 12. 多屏、虚拟显示和投屏

SurfaceFlinger 管理物理显示和虚拟显示。

### 12.1 多 display

```text
DisplayDevice #0：主屏
DisplayDevice #1：副屏 / HDMI / 车载仪表
VirtualDisplay：录屏 / 投屏 / Presentation
```

每个 display 有自己的 Layer 集合、合成目标和刷新节奏。WMS/ATMS 决定窗口和 Activity 属于哪个 display，SurfaceFlinger 负责把对应 Layer 合成到对应显示设备。

### 12.2 虚拟显示

录屏、投屏、VirtualDisplay 会创建虚拟显示。SurfaceFlinger 可以把某些 Layer 合成到虚拟显示目标 buffer。

如果 Layer 设置了 secure/protected，截图或录屏时可能被隐藏或变黑。

---

## 13. 常见问题与排查方法

### 13.1 Activity 黑屏

按层次排查：

1. ATMS：Activity 是否 resumed。
2. WMS：窗口是否 visible，Surface 是否创建。
3. App：是否提交了首帧 buffer。
4. SurfaceFlinger：Layer 是否存在、有无 buffer、是否 hidden。
5. HWC：是否 present 到目标 display。

常用命令：

```bash
adb shell dumpsys activity top
adb shell dumpsys window windows
adb shell dumpsys SurfaceFlinger --list
adb shell dumpsys SurfaceFlinger
```

### 13.2 SurfaceView 视频黑屏

常见原因：

- Surface 生命周期过早释放。
- MediaCodec/Camera 没有 queue buffer。
- Layer 被其他窗口遮挡或 hidden。
- HWC overlay 路径异常。
- protected content 截屏显示黑。
- 多屏目标 display 错误。

### 13.3 掉帧和卡顿

优先用 Perfetto，看：

- App 主线程是否错过 VSYNC。
- RenderThread 是否耗时。
- GPU completion 是否慢。
- SurfaceFlinger 合成是否慢。
- HWC present fence 是否慢。
- CPU/GPU 频率和温控。

命令辅助：

```bash
adb shell dumpsys gfxinfo packageName framestats
adb shell dumpsys SurfaceFlinger --latency
adb shell dumpsys SurfaceFlinger --dispsync
```

不同 Android 版本支持的 SurfaceFlinger 参数会有差异，以设备实际输出为准。

### 13.4 截图和屏幕看到的不一致

可能原因：

- secure layer 不允许截图。
- protected video layer 走硬件路径。
- 截图捕获的是合成前/后不同路径。
- 外接屏和主屏显示内容不同。

### 13.5 花屏、撕裂、旧帧

更偏底层：

- fence 同步问题。
- gralloc buffer usage/format 不匹配。
- HWC 合成 bug。
- 显示驱动问题。
- 多屏带宽或 overlay plane 限制。

---

## 14. 第三方系统常见修改点

### 14.1 开机动画和显示启动

需求：定制 bootanimation、加快首屏显示。

常见点：SurfaceFlinger 启动、Display 初始化、bootanimation 进程、SystemUI 首帧。

风险：过早切换显示内容可能导致黑屏、闪屏或显示设备尚未 ready。

### 14.2 多屏显示策略

需求：车载中控、仪表、后排屏独立显示。

常见点：DisplayDevice、WMS display 策略、Layer mirror、虚拟显示。

风险：只改 SurfaceFlinger 不够，ATMS/WMS 必须知道 Activity/窗口属于哪个 display。

### 14.3 视频和相机 overlay

需求：优化视频播放、倒车影像、相机预览性能。

常见点：HWC overlay、buffer 格式、protected content、z-order。

风险：overlay 资源有限，多路视频、多屏、旋转、缩放可能导致回退或黑屏。

### 14.4 截屏/录屏安全策略

需求：禁止某些画面被截屏或投屏。

常见点：`FLAG_SECURE`、secure layer、virtual display 合成策略。

风险：安全策略必须从 WMS 到 SurfaceFlinger 到 HWC 一致，否则可能出现漏截或误黑。

### 14.5 刷新率和性能策略

需求：高刷、低功耗、视频帧率匹配。

常见点：Scheduler、DisplayMode、FrameRate API、HWC present。

风险：刷新率切换会影响功耗、动画流畅度、视频播放和触摸延迟。

---

## 15. 读源码的推荐路线

### 15.1 从 App Surface 到 BufferQueue

```text
ViewRootImpl
Surface
BufferQueueProducer
queueBuffer
SurfaceFlinger Layer
```

### 15.2 SurfaceFlinger 主循环

```text
SurfaceFlinger::init
Scheduler / VSYNC
SurfaceFlinger::onMessageReceived
latchBuffers
prepareFrame
doComposition
postFramebuffer / present
```

### 15.3 WMS 控制 Layer 属性

```text
WindowManagerService
WindowState
SurfaceControl
SurfaceControl.Transaction
SurfaceFlinger::setTransactionState
Layer state update
```

### 15.4 HWC 合成

```text
SurfaceFlinger
CompositionEngine
HWComposer
Composer HAL
Display driver
```

---

## 16. 关键源码路径速查

| 内容 | 路径 |
|---|---|
| SurfaceFlinger 主服务 | `frameworks/native/services/surfaceflinger/SurfaceFlinger.cpp` |
| Layer | `frameworks/native/services/surfaceflinger/Layer.cpp` |
| BufferQueue | `frameworks/native/libs/gui/BufferQueue*.cpp` |
| Surface | `frameworks/native/libs/gui/Surface.cpp` |
| SurfaceControl Java | `frameworks/base/core/java/android/view/SurfaceControl.java` |
| SurfaceControl native | `frameworks/native/libs/gui/SurfaceComposerClient.cpp` |
| CompositionEngine | `frameworks/native/services/surfaceflinger/CompositionEngine/` |
| Scheduler | `frameworks/native/services/surfaceflinger/Scheduler/` |
| RenderEngine | `frameworks/native/libs/renderengine/` |
| HWC 接口 | `hardware/interfaces/graphics/composer/`、`hardware/google/graphics/common/` 等 |
| gralloc 接口 | `hardware/interfaces/graphics/allocator/`、`hardware/interfaces/graphics/mapper/` |
| ViewRootImpl | `frameworks/base/core/java/android/view/ViewRootImpl.java` |
| ThreadedRenderer | `frameworks/base/core/java/android/view/ThreadedRenderer.java` |
| WMS | `frameworks/base/services/core/java/com/android/server/wm/WindowManagerService.java` |

---

## 17. 一图总结

```text
App 进程
  View / SurfaceView / TextureView / MediaCodec
        │
        ├─ 普通 View：HWUI 渲染到主窗口 buffer
        └─ SurfaceView/视频：渲染到独立 Surface buffer
        │
        ▼ queueBuffer
BufferQueue
        │ acquireBuffer
        ▼
surfaceflinger 进程
  SurfaceFlinger
        │
        ├─ Layer：App 窗口
        ├─ Layer：状态栏
        ├─ Layer：导航栏
        ├─ Layer：输入法
        ├─ Layer：视频/相机
        │
        ├─ 读取 WMS/Shell 提交的 Layer 属性
        ├─ 按 VSYNC latch buffer
        ├─ GPU 或 HWC 合成
        └─ present 到 Display
```

---

## 小结

- **SurfaceFlinger 是独立 native 进程里的系统服务**，不是 `system_server` 里的 Java Service。
- **App 负责生产 buffer，SurfaceFlinger 负责消费 buffer 并合成 Layer**。
- **WMS 管窗口规则，SurfaceFlinger 管图层合成**；两者通过 `SurfaceControl` 和 transaction 衔接。
- **BufferQueue 是生产者/消费者桥梁**，App、MediaCodec、Camera 常作为 producer，SurfaceFlinger 常作为 consumer。
- **VSYNC 决定刷新节奏**，掉帧可能发生在 App 绘制、RenderThread、SurfaceFlinger 合成、HWC present 任一阶段。
- **SurfaceView 是独立 Layer，TextureView 更像普通 View 树中的纹理**，这会影响视频、动画、截图和层级问题。
- **多屏、视频、车载、截屏安全、花屏黑屏问题通常要同时看 WMS、SurfaceFlinger、HWC 和驱动**。

如果只记一个核心模型：

> WMS 告诉系统“每个窗口图层应该怎么摆”，App 把“每个图层的内容”画进 buffer，SurfaceFlinger 把这些 buffer 按 Layer 规则合成并送到屏幕。