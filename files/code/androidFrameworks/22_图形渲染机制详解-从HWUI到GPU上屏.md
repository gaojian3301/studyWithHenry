# 图形渲染机制详解：从 HWUI 到 GPU 上屏

> 这篇是 `12_SurfaceFlinger机制详解` 的**天然上游**。12 篇讲了"SurfaceFlinger 拿到各个 Layer 的 buffer 后怎么合成、怎么上屏"；本篇补齐它前面缺的那一段：**绘制指令怎么从 View 树产生、怎么变成 GPU 命令、怎么写进 GraphicBuffer、怎么进 BufferQueue 队列**。主角是"一个像素的诞生"——从 App 说"我要画"到像素出现在屏幕上的完整流水线。
> 目标读者：已读过 11_WMS（窗口秩序）和 12_SurfaceFlinger（合成与送显），希望把中间"App 内绘制 + RenderThread + Skia + GPU"这一段彻底打通的 Android / 车载 ROM 开发者。

---

## 与 12_SurfaceFlinger 的分工（先读这段，避免重复阅读困惑）

| 主题 | 12_SurfaceFlinger（下游） | 本篇 22（上游） |
|---|---|---|
| BufferQueue 视角 | consumer 端：acquire/release、Layer 如何拿 buffer | producer 端：dequeue/queue、App 如何产 buffer、三重缓冲如何兜底 |
| 主线程职责 | 基本不提 | UI 线程 measure/layout/draw 录制 DisplayList |
| 渲染线程 | 略提 RenderThread | 详讲 RenderThread 的 syncFrameState / 纹理上传 / GPU 命令 |
| Skia / GPU 后端 | 不提 | 详讲 SkiaGL / SkiaVk、HardwareBuffer、Bitmap 上传成本 |
| HWC / 合成 | 详讲 | 仅简述并指回 12 |
| VSYNC / Choreographer | 讲 SF 的 VSYNC-sf | 讲 App 的 VSYNC-app、Choreographer callback 链、frame deadline |
| 掉帧归因 | 列了几类来源 | 给出按轨（Perfetto）逐段定位的方法论 |

一句话分工：**12 讲"合成与送显"，本篇讲"绘制与上屏前"**。衔接点：App 通过 `queueBuffer` 把画好的 GraphicBuffer 交给 BufferQueue 后，故事就交给 12 篇了。

---

## 目录

1. [开场：为什么读完 SurfaceFlinger 还是不懂掉帧](#1开场为什么读完-surfaceflinger-还是不懂掉帧)
2. [三个线程与两个世界：UI 线程 / RenderThread / SF](#2三个线程与两个世界ui-线程--renderthread--sf)
3. [VSYNC 与 Choreographer：节奏从哪来](#3vsync-与-choreographer节奏从哪来)
4. [绘制阶段：View 树如何被记录成 DisplayList](#4绘制阶段view-树如何被记录成-displaylist)
5. [同步与执行阶段：RenderThread 的 drawFrame](#5同步与执行阶段renderthread-的-drawframe)
6. [Skia 与 GPU 后端：2D 指令如何变成 GPU 命令](#6skia-与-gpu-后端2d-指令如何变成-gpu-命令)
7. [BufferQueue 与三级缓冲：buffer 从哪来、队列怎么转](#7bufferqueue-与三级缓冲buffer-从哪来队列怎么转)
8. [合成衔接（简述，指回 12 篇）](#8合成衔接简述指回-12-篇)
9. [一次点击到上屏的完整时间线](#9一次点击到上屏的完整时间线)
10. [掉帧与 jank 归因：按轨定位](#10掉帧与-jank-归因按轨定位)
11. [View 侧的性能陷阱](#11view-侧的性能陷阱)
12. [Compose 的渲染差异（「Android 视角」可跳读）](#12compose-的渲染差异android-视角可跳读)
13. [车机场景专章](#13车机场景专章)
14. [调试工具箱](#14调试工具箱)
15. [常见问题排查表](#15常见问题排查表)
16. [读源码路线](#16读源码路线)
17. [关键源码路径速查](#17关键源码路径速查)
18. [一图总结](#18一图总结)
19. [关联阅读](#19关联阅读)

---

## 1. 开场：为什么读完 SurfaceFlinger 还是不懂掉帧

很多人读完 12 篇会有个困惑："SurfaceFlinger、Layer、BufferQueue 我都懂了，可为什么我的 App 还是掉帧？"原因是 12 篇把 App 内部当作一个黑盒 producer：**"App 把内容画进 buffer"** 七个字一笔带过，没讲"画"这件事在 App 进程里到底发生了什么。

本篇要补的就是这七个字背后的世界。先把一个生活类比摆出来，后面所有概念都挂靠它：

> **送快递的车队类比（渲染流水线）**
> - **UI 线程（司机队长）**：接到"要送一批货"的指令（输入/动画/布局变化），把清单整理好（measure/layout），写在一张"装车单"上（DisplayList，只记录指令，不真正搬货）。
> - **RenderThread（搬运工 + 货车）**：拿着装车单，把货物真正搬上车、按路线开到仓库（GPU 执行绘制命令，写进 GraphicBuffer）。
> - **BufferQueue（仓库收货口）**：货车把货卸在仓库门口（queueBuffer），仓库管理员（SurfaceFlinger）按节奏把各家的货拼成一整车发往门店（合成 + 送显，见 12 篇）。
> - **VSYNC（发车时刻表）**：每隔固定时间发一班车。错过这一班，货只能等下一班——这就是"掉帧"。

关键直觉：**"画"被拆成了"记录指令（UI 线程）"和"执行指令（RenderThread + GPU）"两段**。掉帧既可以发生在记录段（UI 线程卡），也可以发生在执行段（RenderThread/GPU 慢），也可以发生在仓库段（SF/HWC，12 篇已讲）。本篇让你能分清是哪一截出了问题。

怎么读这篇：

- 想先建立全景，读 [第 2 节](#2三个线程与两个世界ui-线程--renderthread--sf) 和 [第 9 节时间线](#9一次点击到上屏的完整时间线)。
- 想懂"为什么主线程不卡动画也顺"，读 [第 5 节](#5同步与执行阶段renderthread-的-drawframe)。
- 想懂"GPU 到底画了什么"，读 [第 6 节](#6skia-与-gpu-后端2d-指令如何变成-gpu-命令)。
- 想抓一帧掉在哪，读 [第 10 节](#10掉帧与-jank-归因按轨定位) 和 [第 14 节工具箱](#14调试工具箱)。
- 车机同学直接跳 [第 13 节](#13车机场景专章)。

**一帧的一生全景图**（后面每节都会回到这张图）：

```text
App 进程                                          surfaceflinger 进程
┌───────────────────────────────┐
│ UI 线程 (main)                 │
│  输入/动画 → measure/layout     │
│  draw(): 把绘制"录制"成         │
│    DisplayList (RenderNode)    │  ← 记录指令，不碰像素
└───────────────┬───────────────┘
                │ (跨线程，只传"录好的指令")
                ▼
┌───────────────────────────────┐
│ RenderThread (独立 native 线程) │
│  syncFrameState 同步属性        │
│  upload 纹理/Bitmap 到 GPU      │
│  drawRenderNode → 生成 GPU 命令 │
│  GPU 执行 → 写进 GraphicBuffer  │
│  queueBuffer → BufferQueue      │
└───────────────┬───────────────┘
                │ queueBuffer
                ▼
          BufferQueue (producer 端)
                │ acquireBuffer
                ▼
┌───────────────────────────────┐
│ SurfaceFlinger                 │
│  latch buffer → GPU/HWC 合成   │  ← 见 12_SurfaceFlinger
│  present → Display             │
└───────────────────────────────┘
```

---

## 2. 三个线程与两个世界：UI 线程 / RenderThread / SF

### 2.1 三个线程各自职责

| 线程 | 所在进程 | 干什么 | 卡住会怎样 |
|---|---|---|---|
| **UI 线程（主线程）** | App 进程 | 处理输入、跑动画逻辑、measure/layout、把绘制指令**录制**成 DisplayList | 输入无响应、ANR、错过 VSYNC 起点 |
| **RenderThread** | App 进程（native） | 把 DisplayList 同步成 GPU 可执行的绘制、上传纹理、真正下发 GPU 命令、queueBuffer | 帧生产慢、掉帧、但**不阻塞 UI 线程** |
| **SurfaceFlinger 主线程** | `surfaceflinger` 进程 | 按 VSYNC 拿各 Layer buffer、合成、present 到屏幕 | 全局掉帧、所有 App 一起卡（见 12 篇） |

一个常见误解：以为"绘制"全在 UI 线程完成。实际上**从 Android 5.0（API 21，Lollipop）起，hwui 的绘制就搬到了独立的 RenderThread**。UI 线程只负责"录制"——调用 `Canvas` 的 `drawRect`/`drawText` 等 API 时，在硬件加速下这些调用不是立刻画像素，而是把一条条绘制指令（DisplayListOp）追加进 `RenderNode` 的录制缓冲区。真正的像素产出在 RenderThread。

### 2.2 两个世界：CPU 绘制 vs GPU 渲染

"两个世界"指指令的产生地和执行地：

```text
CPU 世界（UI 线程 / RenderThread，跑在 CPU 核上）
  写 Java/Kotlin → 调 Canvas API → 生成绘制指令
        │
        │ (RenderThread 把指令翻译成 GPU 命令)
        ▼
GPU 世界（GPU 硬件，独立单元）
  执行顶点/片元着色 → 写 framebuffer (GraphicBuffer)
```

- **CPU 绘制（软件绘制 / Software rendering）**：直接由 CPU 把像素写进 bitmap buffer，对应 `Canvas` 的软件实现（Skia 的 CPU 后端）。路径：`View.draw()` → `SkCanvas` → 软件光栅化。慢、占 CPU，但兼容性好。
- **GPU 渲染（硬件加速 / Hardware acceleration）**：指令由 RenderThread 翻译成 GL/Vulkan 调用，交给 GPU 光栅化。默认开启（**硬件加速自 API 14 / ICS 起默认开启**，API 11 引入但默认关）。

注意：**硬件加速是"用 GPU 画"，不是"不走 UI 线程"**。UI 线程录指令、RenderThread 翻译+下发这条分工，在硬件加速下才成立。

### 2.3 为什么要把 RenderThread 独立出来

如果绘制全在 UI 线程，那么一次复杂绘制（大量 View、大图、阴影）会直接堵住输入和动画，用户立刻感知卡顿。独立出 RenderThread 后：

- UI 线程只做"轻量录制"，通常远快于真正绘制；
- 真正耗时的 GPU 工作由 RenderThread 在另一核上并行做；
- **属性动画（如 translationX、alpha）甚至可以在 RenderThread 上独立重放**，连 UI 线程都不用再跑一遍（见第 5 节）。

代价是引入了"跨线程同步"复杂度：UI 线程录完一帧，要把这帧的 DisplayList 交给 RenderThread，并等它完成才能安全地开始下一帧的录制（否则会出现"录新帧时旧帧还在画"的竞态）。这个同步点就是 `drawFrame` 里的 `syncFrameState`。

---

## 3. VSYNC 与 Choreographer：节奏从哪来

### 3.1 VSYNC 从哪来

屏幕不是随时接受新画面，而是按固定节奏（刷新率）刷新。VSYNC 是"垂直同步"信号，标记"一帧显示时机到了"。在 Android 里：

```text
屏幕/显示硬件 (Display)
  产生硬件 VSYNC
        │
        ▼
HWC / 显示驱动
        │
        ▼
SurfaceFlinger 的 Scheduler
  把 VSYNC 转换成软件事件，分两路发：
  ├─ VSYNC-app  →  App 的 Choreographer（驱动绘制）
  └─ VSYNC-sf   →  SurfaceFlinger 自己（驱动合成，见 12 篇）
```

关键点：**App 不该自己随便决定"什么时候画一帧"**，而是等 Choreographer 收到 VSYNC-app 后才开始。这样 App 产帧的节奏和屏幕刷新、SF 合成节奏对齐，避免撕裂和浪费。

> 版本差异：VSYNC 的软件模拟早期用 `DispSync`；调度细节在 Android 12（API 31，S）前后随 `Scheduler`/`VSyncPredictor` 重构有变化。具体实现随版本演进，抓住"SF 产生 VSYNC，分 app/sf 两路"这个模型即可。

### 3.2 Choreographer：App 的"节拍器"

`Choreographer`（API 16 / Jelly Bean 引入）是 App 进程内的帧调度器。它注册监听 VSYNC-app，收到后按固定顺序回调已注册的 callback。callback 有类型优先级：

| callback 类型 | 顺序 | 典型用途 |
|---|---|---|
| `CALLBACK_INPUT`（1） | 最先 | 输入事件分发（触摸/按键先处理） |
| `CALLBACK_ANIMATION`（2） | 次 | 普通动画（ValueAnimator、View 动画的帧计算） |
| `CALLBACK_INSETS_ANIMATION`（3） | 再次 | 窗口 Insets 动画（如软键盘/系统栏过渡，**约 API 29/Q 引入**） |
| `CALLBACK_TRAVERSAL`（4） | 然后 | `ViewRootImpl.doTraversal()`：measure/layout/draw |
| `CALLBACK_COMMIT`（5） | 最后 | 遍历提交后的收尾（**约 API 23/M 引入**），如触发下一轮调度、提交 RenderThread |

执行顺序保证：先消化输入和动画，再遍历视图树，最后提交。这样一帧内的"数据准备好 → 布局 → 绘制"是有序的。

```text
VSYNC-app 到达 Choreographer
  └─ 按序跑 callback：
     1. INPUT   → 处理本次输入
     2. ANIMATION → 计算动画当前值
     3. INSETS_ANIMATION → 窗口 insets 动画
     4. TRAVERSAL → ViewRootImpl.doTraversal()
                       measure → layout → draw(录制 DisplayList)
     5. COMMIT  → 提交，安排下一帧/通知 RenderThread
```

### 3.3 frame deadline 与"一帧预算"

以 60Hz 屏幕为例，刷新周期约 **16.6ms**（1000/60）。这就是一帧的"预算"（frame budget / deadline）。App 要在 VSYNC-app 到达后的预算内完成：UI 线程录制 + RenderThread 绘制 + queueBuffer。SF 则在 VSYNC-sf 预算内完成合成 + present。任一环节超预算，这一帧就赶不上这次刷新 → 掉帧（jank）。

- 60Hz：~16.6ms/帧
- 90Hz：~11.1ms/帧
- 120Hz：~8.3ms/帧

> 版本差异：可变刷新率（Variable Refresh Rate, VRR）与 `FrameRate` API、`RefreshRate` 策略在 Android 11（API 30，R）后逐步完善；高刷屏在 Android 10+ 设备上普及。App 可通过 `Surface.setFrameRate()` / `WindowManager.LayoutParams.preferredDisplayModeId` 表达期望帧率，最终由 SF 的 `Scheduler` 与 HWC 协商（协商细节见 12 篇第 14.5 节）。

### 3.4 Choreographer 怎么被触发

最常见入口是 `ViewRootImpl` 在需要刷新时调用：

```text
ViewRootImpl.scheduleTraversals()
  └─ Choreographer.postCallback(TRAVERSAL, mTraversalRunnable, null)
       └─ 等下一个 VSYNC-app
            └─ 回调 mTraversalRunnable → doTraversal() → performTraversals()
```

`requestLayout()` / `invalidate()` 最终都会走到 `scheduleTraversals()`，把一次遍历排进下一帧。理解这点对第 11 节的性能陷阱至关重要。

---

## 4. 绘制阶段：View 树如何被记录成 DisplayList

### 4.1 从 View.draw 到 DisplayList

硬件加速开启时，`View.draw(Canvas)` 拿到的 `Canvas` 不是软件 Canvas，而是 hwui 的 `RecordingCanvas`。当 View 调用 `canvas.drawRect(...)` 这类方法时，hwui 不会立刻画像素，而是把这条指令追加进当前 `RenderNode` 的录制列表。

```text
View.onDraw(canvas)
  canvas.drawRect(...)      ← 不是画像素，是"记一笔"
  canvas.drawText(...)      ← 再记一笔
        │
        ▼
RenderNode 里的 DisplayList（一串 DisplayListOp）
  ├─ DrawRectOp
  ├─ DrawTextOp
  └─ ...
```

`RenderNode`（对应 hwui 的 `SkiaDisplayList`/`DisplayListData`）是"一个可绘制节点的录制结果"，既包含绘制指令，也包含变换、裁剪、alpha 等属性。父 View 的 RenderNode 会引用子 View 的 RenderNode，形成一棵树——这就是"DisplayList 树"，和 View 树结构对应，但只保留绘制相关信息。

### 4.2 ThreadedRenderer / RenderProxy：Java 与 native 的桥

App 的 `ViewRootImpl` 持有一个 `ThreadedRenderer`（API 21 起作为硬件加速渲染入口）。它内部通过 `RenderProxy` 把 Java 侧请求转发到 native 的 `RenderThread`：

```text
ViewRootImpl
  └─ ThreadedRenderer
       └─ RenderProxy (JNI)
            └─ RenderThread (native, 独立线程)
                 └─ CanvasContext / hwui 绘制核心
```

- `ThreadedRenderer`：Java 侧"我要渲染这一帧"的入口，负责创建/销毁硬件加速的 `Surface` 关联、驱动每帧。
- `RenderProxy`：跨 JNI 把"录制完成的 DisplayList""属性变更"等事件投递给 RenderThread，而不阻塞 UI 线程。

### 4.3 硬件加速失效：何时 fallback 到软件绘制

并非所有绘制都能走 GPU。当遇到 GPU 不支持的操作时，hwui 可能整帧或局部 fallback 到软件绘制（CPU 光栅化进 bitmap，再当纹理贴上去），性能会明显下降。常见触发：

| 场景 | 说明 |
|---|---|
| `Canvas` 的某些高级 API | 如 `drawPicture`、`drawVertices`、部分 `Xfermode` 组合、大模糊等 |
| 自定义 View 关掉硬件加速 | `setLayerType(LAYER_TYPE_SOFTWARE, null)` 或 manifest/`hardwareAccelerated=false` |
| 图层类型冲突 | 某些 `PorterDuffXfermode` 在硬件加速下不支持，会触发软件路径 |
| 位图格式/颜色空间 | 非 GPU 友好格式可能走 CPU 上传 |

> 经验：如果你发现某页面"明明没干重活却特别卡"，用 `dumpsys gfxinfo` 或开发者选项的"GPU 渲染模式"看是否大面积软件绘制（见第 14 节）。软件绘制下的掉帧，优化 View 层级没用，得先消除 fallback 原因。

### 4.4 硬件层（Hardware Layer）与 DisplayList 的关系

`View.setLayerType(LAYER_TYPE_HARDWARE, ...)` 会让该 View 的 RenderNode 被**离屏（offscreen）渲染进一张纹理**，之后这个 View 的变换/alpha 直接操作纹理，不必每帧重录重画子树。代价是占用 GPU 显存、且"建层"本身有一次离屏渲染开销。

- 适合：频繁做 alpha/translation/scale 动画且内容不变的 View（建一次层，动画只动纹理）。
- 不适合：内容每帧都变的 View（建层反而多一次离屏 + 纹理上传）。

这与第 5 节"属性动画为何不卡主线程"配合理解：动画作用在 hardware layer 上时，RenderThread 直接重放变换，UI 线程完全不参与。

---

## 5. 同步与执行阶段：RenderThread 的 drawFrame

### 5.1 一帧从 UI 线程交到 RenderThread

`performTraversals()` 的 draw 阶段完成后，UI 线程录好了一棵 DisplayList 树，然后触发 RenderThread 开始真正绘制。核心调用链（native 侧，类名随版本略有差异）：

```text
ViewRootImpl.performTraversals()
  └─ draw()
       └─ ThreadedRenderer.draw()
            └─ (JNI) RenderProxy::drawFrame()
                 └─ RenderThread 主循环处理 "DrawFrames" 任务
                      ├─ CanvasContext::drawFrame()
                      │    ├─ syncFrameState()      ← 把 UI 线程录的 DisplayList 同步进来
                      │    ├─ 处理 hardware layer / 动画属性
                      │    ├─ upload 纹理/Bitmap
                      │    ├─ drawRenderNode()       ← 遍历 DisplayList 树，生成 GPU 命令
                      │    └─ flush / submit 给 GPU
                      └─ swapBuffers / queueBuffer → BufferQueue
```

### 5.2 syncFrameState：跨线程同步点

`syncFrameState` 是 UI 线程与 RenderThread 之间的"交接点"。它把 UI 线程刚录好的 DisplayList、各 RenderNode 的属性（位置/alpha/裁剪）同步到 RenderThread 侧的绘制上下文。这一步必须在"UI 线程本帧录制结束"与"RenderThread 开始画"之间完成，避免读到半成品。

同步是**增量**的：hwui 会记录哪些 RenderNode 变了，只同步脏的部分，而不是每帧全量拷贝整棵树。这就是为什么"只改一个 View 的透明度"通常比"改整屏布局"便宜。

### 5.3 纹理上传（upload）的真实成本

图片、Bitmap、字体字形要被 GPU 使用，必须先"上传"到 GPU 可见的内存（通常通过 `glTexImage2D` / Vulkan 的 image 拷贝，底层对应 `AHardwareBuffer`/`HardwareBuffer`）。这一步在 RenderThread 做，但成本客观存在：

- 一张 1080p 的 ARGB_8888 Bitmap ≈ 8MB，上传就是一次 8MB 的拷贝 + GPU 端分配。
- 大图首次出现的一帧，常因上传而"周期性掉一帧"——典型如列表滑动刚进来的图。

优化方向（详见第 11 节）：预解码、正确采样（`inSampleSize`）、`Bitmap.Config.HARDWARE`（让 Bitmap 直接住在 GPU 友好内存里，减少一次拷贝）、异步解码线程。

### 5.4 drawRenderNode：DisplayList 树变成 GPU 命令

`drawRenderNode` 递归遍历 DisplayList 树，把每条 `DisplayListOp` 翻译成 Skia 的绘制调用（见第 6 节），Skia 再翻译成 GL/Vulkan 命令。整个过程在 GPU 上光栅化，结果写进当前帧的 `GraphicBuffer`。

### 5.5 为什么属性动画不卡主线程

这是 RenderThread 独立的最大收益。以"平移一个 View"为例：

- **普通（非硬件层、走 UI 线程）动画**：每帧 UI 线程要 `invalidate` → 重跑 measure/layout/draw 录制，主线程忙。
- **RenderNode 属性动画（RenderThread 动画）**：动画直接改 `RenderNode` 的 `translationX/alpha/scale` 等属性。这些属性在 RenderThread 侧被"重放"，UI 线程**完全不需要重录 DisplayList**，甚至不需要被唤醒。

Android 的 `RenderNodeAnimator`（API 21）/ `ViewPropertyAnimator` 在硬件层场景下会走这条路径。结果：即便 UI 线程因为别的事（如 GC、bind 数据）短暂繁忙，平移/淡入淡出这类动画依然丝滑——因为驱动动画的不是 UI 线程，而是 RenderThread 按 VSYNC 重放属性。

> 工程启示：**想让动画不卡主线程，优先用支持硬件属性的动画（translation/alpha/scale/rotation），并让被动画的 View 内容相对静止（避免每帧重录）**。对"内容每次都变"的动画（如逐帧重绘的自定义 View），这一招不灵。

---

## 6. Skia 与 GPU 后端：2D 指令如何变成 GPU 命令

### 6.1 Skia 是什么

`Skia` 是 Android 的 2D 图形库（Google 维护，C++）。hwui 几乎把所有 `Canvas` 绘制都委托给 Skia：画矩形、文字、路径、图片、模糊、裁剪……Skia 负责把"高级 2D 指令"翻译成底层 GPU API 调用。可以把 Skia 理解为"2D 绘制的编译器"：输入是 `drawRect/drawText`，输出是 GLSL/Vulkan 命令。

```text
Canvas API (Java/Kotlin)
  ▼
hwui RecordingCanvas
  ▼
DisplayListOp
  ▼ (RenderThread)
Skia (SkCanvas / SkDevice)
  ▼ 选择后端
[ SkiaGL ] 或 [ SkiaVk ]   ← GPU 后端
  ▼
OpenGL ES / Vulkan 驱动 → GPU → GraphicBuffer
```

### 6.2 两个 GPU 后端：SkiaGL 与 SkiaVk

| 后端 | 底层 API | 状态 |
|---|---|---|
| `SkiaGL`（Ganesh） | OpenGL ES | 长期默认后端，兼容性好，绝大多数设备支持 |
| `SkiaVk` | Vulkan | **约 Android 10（API 29，Q）起作为可选/测试路径引入，后续版本在部分 Pixel 及越来越多设备成为默认 GPU 后端**；到 Android 13/14 覆盖更广 |

Vulkan 后端的动机：更低的驱动开销、更好的多线程提交、更一致的跨厂商行为。是否启用由设备/系统配置（如 `android` 的 `GpuService`/系统属性 `ro.hardware.ui.backend` 类配置）决定，**具体默认策略随厂商与版本差异很大，不要假定某台车机一定走 Vulkan**。

> 排查提示：如果某机型出现"同样的绘制，A 设备快 B 设备慢"，很可能一个是 SkiaGL、一个是 SkiaVk，或驱动实现不同。可关注 `dumpsys gpu`、GraphicsEnvironment / `ANGLE` 相关日志。

### 6.3 SkSurface / SkImage / SkCanvas

Skia 的几个核心概念（理解"画到哪、画什么"）：

- `SkCanvas`：绘图上下文，"画笔"，所有 `drawXxx` 通过它发出。
- `SkSurface`：一块可绘制的"画布后端"，背后连着一个 `SkImageInfo`（尺寸/格式）和具体的 backing store（在这里就是 GraphicBuffer/HardwareBuffer）。
- `SkImage`：一段已栅格化或可栅格化的图像数据（对应一张 Bitmap/纹理）。

在 hwui 里：每帧的 GraphicBuffer 被包装成一个 `SkSurface`，RenderThread 在该 Surface 的 `SkCanvas` 上重放 DisplayList，结果落在 GraphicBuffer。

### 6.4 HardwareBuffer 与 Bitmap 上传到 GPU 的真实成本

`HardwareBuffer`（NDK `AHardwareBuffer`，Java 侧 `android.hardware.HardwareBuffer`，**API 26 / Oreo 引入**）是跨 CPU/GPU/厂商模块共享图形内存的通用载体。GraphicBuffer 本质上就建立在它之上。

Bitmap 要被 Skia 画，需要变成 GPU 可见的纹理：

```text
Bitmap (Java, 住在 CPU  heap / ashmem)
  └─ 上传 (upload)
       └─ 分配 GPU 端 SkImage/GrTexture
            └─ 拷贝像素到 GPU 内存 (AHardwareBuffer 路径可省一次拷贝)
                 └─ Skia 后续直接采样这张纹理
```

真实成本（量级，供直觉，非精确）：

| 操作 | 量级感受 |
|---|---|
| 上传一张 1080p ARGB_8888 | ~8MB 拷贝，可能 1~数 ms（取决于总线/缓存） |
| 上传一张 4K 图 | ~32MB，常见"滑到这帧突掉"的元凶 |
| `Bitmap.Config.HARDWARE` | Bitmap 直接以 GPU 友好内存存在，上传几乎零额外拷贝 |
| 静态纹理重复绘制 | 仅首次上传贵，之后每帧只是"采样"，很便宜 |

> 结论：**瓶颈常常不是"画"，而是"上传"**。列表/大图场景的掉帧，优先怀疑图片解码与上传，而不是绘制指令数量。

### 6.5 字体与文字渲染

文字是 App 里最容易被低估的 GPU 成本。`drawText` 不是简单贴图：

1. 按字号/字重从字体文件（`.ttf/.otf`，系统或 App 内置）**栅格化字形**成位图（缓存命中则复用）。
2. 字形位图作为纹理上传 GPU。
3. Skia 用距离场（DFT）或位图采样把字形画到 Surface。

坑点：

- 首次使用某字号/字体时，字形不在缓存 → 当帧要栅格化+上传，可能掉一帧。
- 字体文件过大、自定义字体未子集化 → 解析/内存开销大。
- 频繁切换 `Typeface` / 字号 → 缓存命中率低。

（更多 View 侧细节见第 11 节。）

---

## 7. BufferQueue 与三级缓冲：buffer 从哪来、队列怎么转

> 本节从 **producer（App）视角** 讲 BufferQueue，与 12 篇的 consumer（SF）视角互补。状态机细节 12 篇第 5 节已讲，这里聚焦"App 怎么拿到 buffer、三重缓冲怎么兜底掉帧、队列深度意味着什么"，避免重复。

### 7.1 GraphicBuffer 从哪来：Gralloc

App 调 `dequeueBuffer` 拿到的不是裸内存，而是一个 `GraphicBuffer`，由 **Gralloc**（Graphics Allocator，HAL）分配。Gralloc 负责在"对 GPU、对显示控制器、对 CPU 都合适"的内存区域分配显存/帧缓冲。

- 老接口 `alloc`；现代用 mapper HAL（版本演进 `Gralloc1→Gralloc2→...→Gralloc4`，**Gralloc4 / mapper HAL v4 大致随 Android 11（API 30，R）**）。
- 分配时带 **usage flags**（如 `GPU_RENDER`、`COMPONENT`、`CPU_READ`），决定这块内存在哪、谁能访问——usage 配错会导致回拷（copy-back）性能炸裂。

### 7.2 producer 端的 dequeue / queue 状态机（简述）

从 App 侧看一圈 buffer 的生命：

```text
RenderThread 需要画一帧
  └─ dequeueBuffer  ← 从 BufferQueue 拿一块空闲 GraphicBuffer
       └─ 在该 buffer 上做 GPU 绘制（Skia → GL/Vulkan）
            └─ queueBuffer  ← 画完，归还并标记"可消费"
                 └─ (BufferQueue 通知 SF：有新 buffer)
                      └─ SF acquireBuffer（见 12 篇）
                           └─ 合成后 releaseBuffer ← buffer 回到空闲池
```

12 篇已详述 acquire/release 与 fence 同步语义，本篇补一句 producer 端重点：**如果 SF/HWC 消费太慢，空闲 buffer 被占满，App 的 `dequeueBuffer` 会阻塞**——此时 UI 线程可能被迫等待，表现为"整体变卡"。所以 App 侧掉帧也可能是下游（SF）慢的连锁反应，不能只怪自己。

### 7.3 三重缓冲（triple buffering）与丢帧兜底

单/双缓冲的问题：

- **单缓冲**：绘制中屏幕就在读，易撕裂。
- **双缓冲**：一块前台（显示中）+ 一块后台（绘制中）。若 App 这帧没按时 queue，SF 只能重复显示上一块 → 掉一帧；且 App 必须等 SF release 了后台块才能开始下一帧，App 与 SF 容易互相等。

**三重缓冲**：池子里多放一块 buffer，让 App 可以提前准备下一帧，减少"因等 release 而空转"。代价是多占一块显存。Android 默认在合适场景启用三缓冲（具体策略由 BufferQueue / SF 配置决定，不同版本/设备存在差异）。

```text
双缓冲时：
  App 画 B ← SF 还在显示 A，App 画完 B 要等 SF release A 才能画 C（互等）

三重缓冲时：
  App 画 B、同时预画 C，池里始终有"显示中/合成中/绘制中"三块，流水线更顺
```

> 实务：三重缓冲**缓解**掉帧，但不消灭掉帧。它治的是"流水线空转等待"，治不了"单帧本身就超过 deadline"。真正的长任务（大图解码、重布局）仍然会让这一帧错过 VSYNC。

### 7.4 BufferQueue 深度与 BlastBufferQueue

**BufferQueue 深度**（maxBuffer）决定池子里最多几块 buffer，直接影响内存与流水线行为。

> 版本差异：**`BlastBufferQueue` 在 Android 12（API 31，S）引入**，把 App 窗口的 BufferQueue 管理从 SurfaceFlinger 端下沉到客户端/BufferStateLayer，统一了普通窗口与 `SurfaceView` 的 buffer 路径，改善了 buffer 生命周期与多窗口/多屏下的行为。老代码里还能看到 `Surface` 直接持 `BufferQueue` 的旧路径。读源码时注意版本分支。

### 7.5 App 侧怎么看 buffer 状态

```bash
# 看某应用 BufferQueue 占用与历史（producer/consumer 各自几块）
adb shell dumpsys SurfaceFlinger --latency <包名>          # 帧延迟直方图（部分版本）
adb shell dumpsys gfxinfo <包名>                            # 含 framestats（见第 14 节）
```

若 `gfxinfo` 显示大量"buffer 被 SF 侧延迟消费"，问题在合成端（回 12 篇）；若 App 自己每帧都超时，问题在 10/11 节讲的 UI 线程或 RenderThread。

---

## 8. 合成衔接（简述，指回 12 篇）

本篇在 `queueBuffer` 那一刻把接力棒交给 12 篇。这里只补"衔接点"的几句话，不展开：

- App 的 GraphicBuffer 进入 BufferQueue 后，SurfaceFlinger 侧对应一个 **Layer**（普通窗口是主窗口 Layer；`SurfaceView` 是独立 Layer，见 12 篇第 10 节）。
- SF 按 **VSYNC-sf** latch 各 Layer 最新 buffer，决定 **Device 合成（HWC overlay） vs Client 合成（GPU/RenderEngine 把多层画成一张）**。
- **HWC**（Hardware Composer）是真正把最终画面送到 Display 的硬件，fence 机制保证"GPU 写完才给显示控制器读"。
- 多屏时每个 Display 有独立合成目标（见 12 篇第 12 节、本篇第 13 节）。

详细的状态机、HWC 协商、fence、Layer 类型，请直接读 **12_SurfaceFlinger机制详解** 的第 5、8、9 节。本篇不再重复，以免篇幅冗余。

---

## 9. 一次点击到上屏的完整时间线

把前面所有角色串成一条带时间预算的链路（以 60Hz、~16.6ms/帧 为例，时间是"典型量级"不是精确值，仅用于建立直觉）：

```text
[t=0]    输入：触摸事件进入 Input 系统
         → Choreographer CALLBACK_INPUT 分发到 App
         → View 处理 onClick / 触发动画 / requestLayout
         │  ~< 1ms（事件分发本身）
[t≈1ms]  CALLBACK_ANIMATION：计算动画当前属性值
         │  ~1~2ms
[t≈3ms]  CALLBACK_TRAVERSAL：ViewRootImpl.doTraversal()
           → measure  （仅布局脏时）        ~1~3ms（视 View 数量）
           → layout   （仅布局脏时）        ~1~3ms
           → draw     （录制 DisplayList）   ~2~5ms（视绘制指令量）
         │  UI 线程把 DisplayList 交给 RenderThread
[t≈8ms]  RenderThread.drawFrame():
           → syncFrameState（同步脏节点）    ~0.5~1ms
           → upload 新纹理/Bitmap            ~0~3ms（视是否有大图）
           → drawRenderNode（GPU 绘制）      ~2~5ms（视绘制复杂度）
           → queueBuffer → BufferQueue       ~0.5~1ms
         │  App 侧这帧的生产到此结束（约 8~12ms 已用）
[t≈13ms] VSYNC-sf：SurfaceFlinger latch 到 App 新 buffer
           → 决定 Device/Client 合成         ~1~2ms
           → GPU/HWC 合成                    ~2~4ms
           → present 到 Display（HWC）        ~1~2ms
[t≈16ms] 屏幕刷新，像素出现 ✅
```

**关键直觉**：一帧的"预算"是 16.6ms，但 App 生产（UI+RT）通常只占其中一半多，剩下留给 SF/HWC。这也是为什么"App 自己感觉不慢"却仍可能掉帧——SF/HWC 那段如果被别的 Layer（视频、壁纸、多窗口）挤占，也会让你这帧迟到。

掉帧发生的典型节点：

| 发生在 | 表现 | 去哪看 |
|---|---|---|
| UI 线程 measure/layout 太久 | `doFrame` 整体偏长 | 第 10、11 节 |
| 大图首次上传 | 偶发单帧尖刺 | 第 6.4、11 节 |
| RenderThread 绘制复杂 | DrawFrames 轨长 | 第 10 节 |
| SF/HWC 合成重 | present 慢 | 12 篇第 8、13 节 |

---

## 10. 掉帧与 jank 归因：按轨定位

掉帧（jank）本质是"某一帧没在 deadline 前完成生产或消费"。要治它，先定位"卡在哪一截"。最权威的工具是 **Perfetto / systrace**，因为它们能同时看到 App 主线程、RenderThread、GPU、SurfaceFlinger、HWC、VSYNC 五条线的时间关系。

### 10.1 抓一帧看哪几条轨（track）

打开 Perfetto（Web 端 `ui.perfetto.dev` 或 `systrace`）后，关注以下 slice/track：

| 轨 / slice | 属于谁 | 告诉你什么 |
|---|---|---|
| `Choreographer#doFrame` | App 主线程 | 一次遍历（measure/layout/draw）耗时；**整段过长 = UI 线程瓶颈** |
| `DrawFrames` / `RenderThread` | App RenderThread | GPU 绘制、纹理上传耗时；**过长 = 绘制/上传瓶颈** |
| `GPU completion` | GPU 驱动 | GPU 真正画完的时刻；若比 queueBuffer 晚很多，说明 GPU 饱和 |
| `SurfaceFlinger` / `onMessageReceived` | SF 进程 | SF 合成一帧耗时 |
| `HWC present` / `presentFence` | HWC/显示 | 上屏时刻；**过长 = 显示侧瓶颈** |
| VSYNC-app / VSYNC-sf | 调度 | 你的 `doFrame` 是否对齐了 VSYNC，有没有"晚开始" |

`doFrame` 内的子 slice 还能细看：`performTraversals` → `measure` / `layout` / `draw` 各占多少。

### 10.2 jank 分类与典型原因

按"卡在哪一截"分四类，对应不同优化方向：

```text
┌─ UI 线程慢（doFrame 整段长）
│    原因：measure/layout 爆炸、onDraw 里做 IO/对象分配、bind 数据重、
│          RecyclerView 在绑定里解码图片、同步 IPC（Binder 调用）
│    治：第 11 节 View 侧优化
│
├─ RenderThread 慢（DrawFrames 长）
│    原因：绘制指令过多、过度绘制、大图/多图上传、复杂裁剪/模糊、
│          Shader 编译卡顿（首次）
│    治：减少 overdraw、预上传纹理、简化自定义绘制
│
├─ SF 侧慢（SurfaceFlinger 合成长）
│    原因：Layer 太多、Client 合成占比高、多窗口/多屏、HWC overlay 不足
│    治：12 篇第 9/14 节（减少 Layer、争取 HWC overlay）
│
└─ 送显侧慢（present fence 长）
      原因：HWC  busy、显示带宽不足、刷新率切换、驱动/硬件限制
      治：12 篇、第 13 节车机实时性
```

### 10.3 "app 侧" vs "系统侧"快速判断

- 如果你的 `doFrame` + `DrawFrames` 都在 deadline 内，但帧还是没按时显示 → 问题在 SF/HWC（系统侧，看 12 篇）。
- 如果你的 `doFrame` 或 `DrawFrames` 本身就超了 → 问题在自己（第 11 节）。
- 偶发单帧尖刺、周期重复 → 多半是"大图上传"或"Shader 首次编译"或"GC"；持续偏长 → 结构性绘制/布局问题。

### 10.4 Shader 编译卡顿（首次冷启动常见）

Skia/驱动在第一次用到某类绘制（特定圆角/模糊/路径）时要**编译 GPU Shader**，这次编译可能耗时数 ms 到十几 ms，造成"首屏/首次进某页面掉几帧"。对策：

- 冷启动期提前"预热"常见绘制路径（在 splash/空闲时画一次）；
- 避免每次创建不同参数的 `Paint`（参数不同可能触发不同 Shader）；
- 关注设备是否启用 Shader 缓存（部分厂商/版本有 persistent shader cache）。

---

## 11. View 侧的性能陷阱

这一节是"App 工程师最常踩、也最能自己修"的部分。按影响力排序。

### 11.1 过度绘制（overdraw）与层级扁平化

**over绘制** = 同一个像素被不该叠的图层反复画。例如：根布局设了背景，子布局又设背景，列表项再设背景——三层都画了同一块区域，GPU 白做工。

```text
不优：DecorView 背景 + LinearLayout 背景 + CardView 背景（三层同区域）
优：  只在最上层需要处设背景，父层背景设为透明/null
```

- 用开发者选项 **"调试 GPU 过度绘制"**（显示不同颜色层数）直观看。
- 原则：背景能少一层是一层；`View` 设了背景又 `wrap_content` 小区域时，父背景其实是浪费。
- **层级扁平化**：`ConstraintLayout` 减少嵌套；避免为"为了间距"多层 `LinearLayout`。深层嵌套不止拖慢 layout，也拉长 DisplayList 树遍历。

### 11.2 requestLayout 的传播范围

`requestLayout()` 不一定只重测自己：

```text
View.requestLayout()
  └─ 标记 PFLAG_FORCE_LAYOUT，向上冒泡到父，最终触发
     ViewRootImpl 在下一帧 performTraversals 重跑 measure + layout
```

- 在父链里任意一层 `requestLayout`，常常导致**整棵子树甚至全树重新 measure/layout**（尤其父是 `wrap_content`、或 `onMeasure` 未做好缓存）。
- 频繁 `requestLayout`（如动画里每帧改尺寸）是"UI 线程慢"的头号原因。
- 治理：能用 `translation`/`scale`（RenderThread 属性动画，见第 5 节）替代改宽高的，绝不用 `requestLayout`；减少 `wrap_content` 在大列表里的使用。

### 11.3 invalidate 粒度

`invalidate()` 标记"需要重绘"，但会连带父裁剪区域一起重录 DisplayList。

- `invalidate()` 整 View 重录；能用 `invalidate(dirtyRect)` 指定脏区域就指定，减少重录范围。
- 自定义 View 在 `onDraw` 里**不要做对象分配 / 不要读文件 / 不要同步 Binder**，否则每帧都付出这些成本。
- `onDraw` 里避免创建 `Paint`/`Path`/`Rect` 等新对象——提升到字段复用。

### 11.4 RecyclerView 的 bind 超时

列表滑动掉帧，常见根因在 `onBindViewHolder`：

- 在 bind 里**同步解码图片 / 读数据库 / 网络**（应移到异步线程 + 缓存）。
- bind 里做重布局（触发 requestLayout 连锁）。
- item 布局过深/过宽导致 measure 慢。
- `DiffUtil` 在主线程算大列表差异（大数据集应放到后台线程算）。

> 经验：滑动卡，先怀疑 `onBindViewHolder` 是否在主线程干了重活，而不是怀疑 RecyclerView 本身。

### 11.5 文字渲染与字体缓存

- 首次出现的新字号/新字体要栅格化+上传字形（第 6.5 节），会导致"进某个页面第一帧掉一下"。
- 自定义字体未子集化 → 字体文件大、内存与解析开销高。
- 极端：在 `onDraw` 里频繁 `setTextSize`/换 `Typeface` 会反复打掉字形缓存。

### 11.6 图片解码线程与采样

- 大图解码必须在**后台线程**（如 `Coroutine Dispatchers.IO` / `AsyncTask` 已弃用 / `ImageDecoder`+协程），decode 在主线程必掉帧。
- 正确 `inSampleSize` / `ImageDecoder` 按目标尺寸解码，别把 4000px 图原样解成 Bitmap 再缩放显示。
- 优先 `Bitmap.Config.HARDWARE`（API 26+）让 Bitmap 直接住在 GPU 友好内存，省一次上传拷贝（第 6.4 节）。
- 列表图片用三级缓存（内存/磁盘/网络），避免每次滑回都重新 decode + 上传。

---

## 12. Compose 的渲染差异（「Android 视角」可跳读）

> 这一节标注为「Android 视角」：跳过不影响理解上面的 View 体系原理。Compose 的"测量/布局/绘制"概念与 View 一一对应，但实现机制不同。交叉引用 `androidApp/04_Compose` 与 `androidApp/06_Compose性能`。

### 12.1 Compose 的 measure/layout/draw 与 RenderNode

Compose 没有 `View` 树，而是 **Composition → Recomposition → 产生 Composition 树 → 映射到 LayoutNode 树 → 最终渲染**。`LayoutNode` 在渲染阶段也会落地到 hwui 的 `RenderNode`：

```text
Composable 函数
  └─ 重组(Recomposition) 产生/更新 Composition 树
       └─ 布局阶段：LayoutNode.measure/layout
            └─ 绘制阶段：把绘制指令记录进 RenderNode (同 hwui DisplayList)
                 └─ 后续与 View 体系完全一致：RenderThread → Skia → GPU → BufferQueue
```

也就是说：**Compose 改的是"UI 怎么描述与更新"，底层渲染仍然走本篇讲的 hwui / RenderThread / Skia / GPU 流水线**。所以第 5、6、10 节的掉帧归因对 Compose 同样适用——只是"卡在 UI 线程"的表现从 `doFrame` 里的 `measure/layout` 变成了重组与布局阶段。

### 12.2 重组（Recomposition）如何驱动绘制

- Compose 的"重绘"由 **State 变化触发重组**，而非 `invalidate()`。
- 重组范围默认"最小化"（只重跑受影响的 Composable），这往往比 View 的整 View `invalidate` 更省；但**若状态粒度设计差**（一个大 `State` 包裹整个页面），重组会扩大，退化成"全页重录"，回到和 View 一样的开销。
- Compose 的 `Modifier.graphicsLayer { }` 等价于给子树加 hardware layer，动画作用在它上面时走 RenderThread 属性重放（同第 5.5 节）。

### 12.3 Compose 特有的坑

| 现象 | 原因 | 对应本篇章节 |
|---|---|---|
| 滑动卡 | 重组范围过大 / `remember` 用错导致频繁重建 | 第 11 节思路 |
| 动画掉帧 | 没用 `graphicsLayer`，动画触发重组而非属性重放 | 第 5.5 节 |
| 首帧慢 | 重组 + 首帧布局 + 字体/纹理上传叠加 | 第 9 节时间线 |
| 大列表卡 | `LazyColumn` item 内重组重活、key 设计不当 | 第 11.4 节 |

> 落地建议：详细性能手法见 `androidApp/06_Compose性能优化`；底层渲染瓶颈定位用第 10、14 节的 Perfetto 方法，看 `Compose`/`Recomposition` 相关 slice 与 `DrawFrames`。

---

## 13. 车机场景专章

车载（AAOS / 厂商定制 ROM）对图形渲染有特殊约束，vendor 端开发者会直接碰到。

### 13.1 多屏：各自的 SF 与 HWC 资源

车机常有多块屏：

```text
Display #0 中控屏 (Cluster/IVI)
Display #1 仪表屏 (Instrument Cluster)
Display #2 副驾/后排屏 (Optional)
```

- 每块物理显示对应一个 `DisplayDevice`，各有独立的 VSYNC 源、合成目标和刷新节奏（12 篇第 12 节）。
- **SF 是单一进程但多 Display 共享一个 Scheduler/合成线程**——某一屏 Layer 过多会拖累其它屏的合成预算。vendor 定制时要关注 per-display 的 Layer 配额与 HWC 能力。
- 仪表屏通常要求**确定性的实时性**，不能因为中控在播视频就掉帧（见 13.2）。

### 13.2 仪表盘的实时性与帧率保证

仪表（时速、转速、告警）是安全相关，掉帧可能违反功能安全预期：

- 仪表内容尽量走 **HWC overlay**（独立 plane，不依赖 GPU 合成排队），减少被其它 Layer 挤占。
- 复杂仪表用 **独立 SurfaceView / 独立 Layer**，避免和 IVI 主窗口混在同一 buffer 竞争。
- 关注 SF 的 `Scheduler` 策略是否给仪表 display 更高的合成优先级；必要时 vendor 侧定制 VSYNC 分区。
- 避免仪表内容每帧都"全量重绘 + 大图上传"，用硬件层缓存静态底图，只动指针层。

### 13.3 3D 车模的 GPU 占用

- 3D 车模（OpenGL/Vulkan 渲染进 `SurfaceView`）是 GPU 大户，会和其他 UI 的 Skia GPU 渲染争夺 GPU 带宽。
- 要点：控制车模帧率（不必和中控 UI 同高刷）；用 `setFrameRate` 表达期望；避免车模与大量 2D overdraw 同屏；监控 `GPU completion` 轨确认没把 SF 合成挤超时。
- 多屏时车模在某屏、仪表在另一屏，要确认两块屏的 HWC plane 资源不冲突（overlay 不够会回退 GPU 合成，功耗与延迟上升）。

### 13.4 SurfaceView / TextureView 在多屏与透明场景

- **多屏**：`SurfaceView` 的 Layer 属于"创建它的窗口所在 display"。把 SurfaceView 挪到别的屏要重新走 WMS display 策略（见 11 篇第 13 节），否则黑屏或错位。
- **透明/异形**：`SurfaceView` 是独立 Layer，做透明/圆角/动画 historically 麻烦（独立 Layer 不随父 View 一起被 Skia 裁剪）。需要透明+动画的视频常改用 `TextureView`（内容作为纹理进主窗口 buffer，但多一次纹理采样开销，见 12 篇第 10 节）。
- 倒车影像、360 环视这类"低延迟 + 独立视频"几乎必用 `SurfaceView` 走 HWC overlay；但 overlay 资源有限（12 篇第 9.2 节），多路视频/多屏要评估 plane 数量。

### 13.5 开机首帧与黑屏问题

车机对"开机到首帧"时间敏感（冷启动黑屏体验差）。

- 首帧慢的常见链路：SF 起得晚 → Display 初始化晚 → bootanimation 与 SystemUI/车载 Launcher 抢首帧 → 你的 App 首帧被推后。
- 排查分层（同 12 篇第 13.1 / 11 篇第 14.5）：ATMS 是否 resume → WMS 窗口 visible → App 是否提交首帧 buffer → SF Layer 有无 buffer → HWC present。
- vendor 侧常见定制：提前 init 显示、缩短 bootanimation、预加载字体/着色器缓存、把车载 Launcher 首屏做"静态占位 + 渐进填充"减少黑屏观感。
- 注意：过早切换显示内容（Display 未 ready）反而导致闪屏/花屏，需与驱动/SF 启动时机对齐。

---

## 14. 调试工具箱

每条命令都给 `#` 注释说明"看什么"。命令差异较大的版本以设备实际输出为准。

### 14.1 gfxinfo：帧统计（最常用）

```bash
# 看某包总体绘制耗时、最近帧数、是否硬件加速
adb shell dumpsys gfxinfo <包名>

# 逐帧详细字段（framestats）：定位单帧各阶段耗时
adb shell dumpsys gfxinfo <包名> framestats
```

`framestats` 输出的关键字段（时间戳，单位纳秒，相邻字段之差即阶段耗时）：

| 字段（片段） | 含义 |
|---|---|
| `IntendedVsync` / `Vsync` | 本帧预期/实际 VSYNC 时刻 |
| `OldestInputEvent` → `InputEvent` | 输入到处理延迟 |
| `HandlerInputStart` → `AnimationStart` | 输入/动画处理段 |
| `PerformTraversalsStart` | `doTraversal` 起点（UI 线程开始） |
| `DrawStart` | 开始录制 DisplayList |
| `SyncStart` | RenderThread `syncFrameState` 起点 |
| `IssueDrawCommandsStart` | 开始下发 GPU 命令 |
| `SwapBuffers` | `queueBuffer`/swap 时刻 |
| `FrameCompleted` | 整帧完成 |

> 读法：`DrawStart - PerformTraversalsStart` = UI 线程录制耗时；`IssueDrawCommandsStart - SyncStart` = RenderThread 绘制耗时；若 `FrameCompleted - Vsync` 超过一帧预算，这帧掉了。

### 14.2 SurfaceFlinger：Layer 与合成

```bash
# 列出当前所有 Layer（看你的窗口 Layer 在不在、有没有 buffer）
adb shell dumpsys SurfaceFlinger --list

# 完整 SF dump：看各 Layer 的合成方式(Device/Client)、fps、刷新
adb shell dumpsys SurfaceFlinger

# 帧延迟直方图（部分版本可用，看 present 延迟分布）
adb shell dumpsys SurfaceFlinger --latency <包名>

# 看 VSYNC/DispSync 调度状态（高刷/变帧率排查）
adb shell dumpsys SurfaceFlinger --dispsync
```

### 14.3 gpu：GPU 后端与状态

```bash
# 看 GPU 相关信息、当前后端(SkiaGL/SkiaVk)、驱动
adb shell dumpsys gpu
```

### 14.4 开发者选项里的图形调试

- **Profile GPU rendering（GPU 渲染模式分析）**：屏幕上叠加柱状图，每根柱是一帧各阶段耗时（绿/蓝/橙等色块对应不同线程阶段），超过基线即掉帧。快速肉眼判断"掉不掉"。
- **调试 GPU 过度绘制（Overdraw）**：用颜色层数标出 overdraw 区域（蓝<绿<淡红<深红），深红即重点优化区。
- **显示硬件层更新（Show hardware layers updates）**：哪块区域被建成 hardware layer 会闪一下，验证动画是否真走了硬件层。
- **GPU 呈现模式 / 强制 GPU 渲染 / 停用硬件加速** 等开关用于对照实验。

### 14.5 Perfetto / systrace：抓帧黄金工具

```bash
# 方式一：perfetto 命令行（需设备支持，指定 atrace 类别）
adb shell perfetto \
  -o /data/misc/perfetto-traces/trace.pftrace \
  -t 10s \
  -c - <<EOF
buffers: { size_kb: 65536 }
data_sources: { config { name: "linux.ftrace" ftrace_config {
  atrace_categories: "gfx" atrace_categories: "view" atrace_categories: "input"
  atrace_apps: "<包名>" } } }
EOF
# 导出后丢到 ui.perfetto.dev 看 Choreographer#doFrame / DrawFrames / SurfaceFlinger / HWC

# 方式二：老 systrace 包装（仍可用）
python systrace.py -a <包名> -o trace.html gfx view input wm
```

抓帧时重点 slice：见第 10.1 节那张轨对照表。

---

## 15. 常见问题排查表

> 四列：现象 | 原因 | 章节 | 第一命令。现象可按图索骥。

| 现象 | 原因 | 章节 | 第一命令 |
|---|---|---|---|
| 首帧慢、冷启动黑屏久 | App 首帧未提交 / 解码与上传重 | 第 9 / 11 / 13.5 | `adb shell dumpsys gfxinfo <包名>` |
| 滑动掉帧 | onBindViewHolder 主线程重活、大图上传 | 第 11.4 / 6.4 | `adb shell dumpsys gfxinfo <包名> framestats` |
| 属性动画掉帧 | 没走硬件层、动画触发重组 | 第 5.5 / 12.3 | 开发者选项"显示硬件层更新" |
| 列表卡顿 | 嵌套深、requestLayout 连锁 | 第 11.2 / 11.4 | `adb shell dumpsys gfxinfo <包名>` |
| 多屏不同步 | display 归属错、VSYNC 分区 | 第 13.1 / 13.2 | `adb shell dumpsys display` |
| 开机黑屏 | SF/Display 未 ready、首帧晚 | 第 13.5 | `adb shell dumpsys SurfaceFlinger` |
| GPU 占用高 | 3D 车模 / overdraw / 大图 | 第 13.3 / 11.1 | `adb shell dumpsys gpu` |
| SurfaceView 闪黑 | Surface 生命周期 / overlay 不足 | 第 13.4 / 12.10 | `adb shell dumpsys SurfaceFlinger --list` |
| 过度绘制严重 | 多层背景、嵌套过深 | 第 11.1 | 开发者选项"调试 GPU 过度绘制" |
| 文字首帧掉帧 | 字形缓存未命中 | 第 6.5 / 11.5 | `adb shell dumpsys gfxinfo <包名>` |
| 软件绘制掉帧 | 硬件加速 fallback | 第 4.3 | `adb shell dumpsys gfxinfo <包名>`（看渲染类型） |
| Shader 编译卡顿 | 首次冷路径编译着色器 | 第 10.4 | `perfetto` 抓首屏 |
| 动画丝滑但滑动卡 | UI 线程 bind 重，非渲染 | 第 5.5 vs 11.4 | `perfetto` 看 `Choreographer#doFrame` |
| 图片偶发尖刺掉帧 | 大图上传 / 解码在主线程 | 第 6.4 / 11.6 | `perfetto` 看 `DrawFrames`/`GPU` |
| 整体变卡（自己不慢） | SF 下游慢、dequeue 阻塞 | 第 7.2 | `adb shell dumpsys SurfaceFlinger --latency <包名>` |
| 视频/仪表 overlay 失效 | HWC plane 不足回退 GPU 合成 | 第 13.2 / 12.9 | `adb shell dumpsys SurfaceFlinger`（看合成类型） |
| 字体内存高 | 自定义字体未子集化 | 第 11.5 | `adb shell dumpsys meminfo <包名>` |
| 高刷屏没生效 | FrameRate 策略未协商 | 第 3.3 / 12.14.5 | `adb shell dumpsys SurfaceFlinger --dispsync` |

---

## 16. 读源码路线

按"从 Java 入口一路追到 native/Skia/SF"的顺序，每条都是一条可独立阅读的链。

**路线 1：从 ViewRootImpl 到一帧绘制（Java → native 入口）**

```text
ViewRootImpl.scheduleTraversals()
  └─ Choreographer.postCallback(TRAVERSAL)
       └─ doTraversal() → performTraversals()
            ├─ performMeasure()
            ├─ performLayout()
            └─ performDraw() → draw() → ThreadedRenderer.draw()
                 └─ (JNI) RenderProxy::drawFrame()   ← 进入 native RenderThread
```

**路线 2：RenderThread 主循环与 drawFrame（native）**

```text
RenderThread::threadLoop()
  └─ 处理 "DrawFrames" 任务
       └─ CanvasContext::drawFrame()
            ├─ syncFrameState()
            ├─ ReorderBarrier / 处理 hardware layer
            ├─ upload 纹理
            ├─ drawRenderNode() 递归 DisplayList 树
            └─ swapBuffers() → queueBuffer
```

**路线 3：hwui DisplayList → Skia → GPU 后端**

```text
RenderNode / DisplayListData
  └─ 每条 DisplayListOp
       └─ Skia 的 SkCanvas 绘制 (SkSurface 背后是 GraphicBuffer)
            └─ SkiaGL (OpenGL) 或 SkiaVk (Vulkan) 后端
                 └─ 驱动 → GPU → framebuffer
```

**路线 4：App 侧 BufferQueue producer（dequeue/queue）**

```text
RenderThread 需要 buffer
  └─ BufferQueueProducer::dequeueBuffer()  ← Gralloc 分配 GraphicBuffer
       └─ 绘制
            └─ BufferQueueProducer::queueBuffer()
                 └─ (通知 SF，转入 12 篇 consumer 侧)
```

**路线 5：Choreographer 接收 VSYNC-app**

```text
SurfaceFlinger Scheduler 产生 VSYNC-app
  └─ 通过 DisplayEventReceiver / BitTube 发到 App
       └─ Choreographer 的 FrameDisplayEventReceiver.onVsync()
            └─ 按 callback 顺序跑 INPUT→ANIMATION→TRAVERSAL→COMMIT
```

**路线 6（车机/系统侧）：SF 如何接手（接 12 篇）**

```text
SurfaceFlinger::onMessageReceived (VSYNC-sf)
  └─ latchBuffers() → 各 Layer acquireBuffer
       └─ prepareFrame() / doComposition()
            └─ HWC present（见 12 篇第 15.2 节源码路线）
```

> 提示：版本差异大（如 `CanvasContext`、`RenderThread` 类名、`DisplayList` 在 hwui 内的命名随 Android 版本演进）。读源码时**先 `git log` / 看当前分支的实际类名**，不要照抄旧博客签名。本篇对不确定的内部签名一律以"相关类/一类控制器"表述。

---

## 17. 关键源码路径速查

| 内容 | 路径（AOSP） |
|---|---|
| ViewRootImpl（遍历入口） | `frameworks/base/core/java/android/view/ViewRootImpl.java` |
| Choreographer | `frameworks/base/core/java/android/view/Choreographer.java` |
| ThreadedRenderer（硬件加速入口） | `frameworks/base/core/java/android/view/ThreadedRenderer.java` |
| HardwareRenderer / RenderProxy（JNI 桥） | `frameworks/base/core/java/android/view/HardwareRenderer.java`、`frameworks/base/core/jni/` 下对应 `android_view_ThreadedRenderer` |
| RenderThread（native） | `frameworks/base/libs/hwui/renderthread/RenderThread.cpp` |
| RenderNode / DisplayList | `frameworks/base/libs/hwui/RenderNode.cpp`、`frameworks/base/libs/hwui/DisplayList*` |
| hwui 绘制核心（CanvasContext） | `frameworks/base/libs/hwui/CanvasContext.cpp`、`frameworks/base/libs/hwui/` |
| Skia GPU 后端封装 | `frameworks/base/libs/hwui/pipeline/skia/`（`SkiaOpenGLPipeline` / `SkiaVulkanPipeline`） |
| Skia 库本体 | `external/skia/`（含 `src/gpu/gl`、`src/gpu/vk`） |
| BufferQueue producer | `frameworks/native/libs/gui/BufferQueueProducer.cpp` |
| GraphicBuffer / Gralloc 封装 | `frameworks/native/libs/ui/GraphicBuffer.cpp`、`hardware/interfaces/graphics/allocator/`、`/mapper/`（Gralloc HAL） |
| HardwareBuffer（Java 侧） | `frameworks/base/core/java/android/hardware/HardwareBuffer.java` |
| BlastBufferQueue（API 31+） | `frameworks/native/libs/gui/BLASTBufferQueue.cpp` |
| SurfaceFlinger（下游，见 12 篇） | `frameworks/native/services/surfaceflinger/` |
| HWC / Composer HAL | `hardware/interfaces/graphics/composer/`、`frameworks/native/services/surfaceflinger/DisplayHardware/` |

---

## 18. 一图总结

```text
                一个像素的诞生（App 内 → 上屏前）
App 进程
┌─────────────────────────────────────────────┐
│ UI 线程 (main)                               │
│   输入/动画 → measure/layout                  │
│   draw(): 录制 DisplayList 进 RenderNode     │ ← 只记指令
│        │ (跨线程交付)                         │
│        ▼                                      │
│ RenderThread (native, API21+ 独立)           │
│   syncFrameState → 上传纹理/Bitmap            │
│   → drawRenderNode → Skia → GL/Vulkan 命令    │
│   → GraphicBuffer (Gralloc 分配)              │
│   → queueBuffer ──────────────────────────┐ │
└────────────────────────────────────────────┘ │
                                               ▼
                                         BufferQueue
                                    (producer 端, 三缓冲兜底)
                                               │ acquireBuffer
                                               ▼
surfaceflinger 进程                          Layer
┌─────────────────────────────────────────────┐
│ SurfaceFlinger (见 12 篇)                     │
│   latch → GPU/HWC 合成 → HWC present → 屏幕  │
└─────────────────────────────────────────────┘
```

**一句话记忆链**：
> UI 线程**录**指令（DisplayList）→ RenderThread **画**指令（Skia→GPU→GraphicBuffer）→ queueBuffer **交**给 BufferQueue → SurfaceFlinger 接力**合成送显**（12 篇）。掉帧就查这三截哪截超了 deadline：录慢（UI 线程）、画慢（RenderThread/GPU）、交后下游慢（SF/HWC）。

---

## 19. 关联阅读

- **`11_WMS机制详解-从窗口添加到显示管理`**：本篇的"窗口秩序"前提——窗口怎么来、Layer 归属谁。
- **`12_SurfaceFlinger机制详解-从Buffer到屏幕合成`**：本篇的**天然下游**——BufferQueue 的 consumer 端、Layer、HWC 合成、送显、多屏。两篇拼成完整图形链路。
- **`13_Input机制详解`（如存在）**：输入事件如何触发 `Choreographer#doFrame` 的 INPUT callback。
- **`androidApp/03_View系统`**：App 侧 View 测量/布局/绘制的基础。
- **`androidApp/04_Compose`** 与 **`androidApp/06_Compose性能优化`**：第 12 节 Compose 渲染差异的延伸。
- **`androidApp/10_性能优化`**：把第 10/11 节的掉帧归因落到 App 工程实践。

如果只记一个核心模型：

> 12 篇说"SurfaceFlinger 把各 Layer 的 buffer 合成上屏"；本篇补上"App 的 buffer 是怎么来的"——UI 线程录制、RenderThread 用 Skia 走 GPU 画进 GraphicBuffer、再进 BufferQueue。三段接力，任何一段超 deadline 都是掉帧。

---

