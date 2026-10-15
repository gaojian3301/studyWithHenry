# 相机与媒体编解码详解：从 Camera HAL 到 MediaCodec 与上屏

> 这篇把"拍照、录像、视频播放"三件事背后共用的那条硬件→软件管线串起来：一帧图像从相机 Sensor 出发，经过 ISP、Camera HAL、BufferQueue、SurfaceFlinger 上屏，或经过 MediaCodec 编码写进文件；播放时则反向，从文件解封装、解码、再上屏。目标是让做 Android 原生/车机 ROM 的你，既能从 App 侧用 Camera2/CameraX 写功能，也能从 Framework/HAL 侧看懂一帧数据到底搬了几次家、卡在哪里。
> 目标读者：会写 Kotlin、能读 AOSP 源码、关心车载相机（倒车/环视/DMS）与媒体编解码（H.264/H.265/硬解）的开发者。音频部分（录音/播放/焦点）已在 `21_Audio机制详解` 讲透，本文只在第 12 章做衔接，不展开。

---

## 目录

1. [开场：三件事共用一条管线 + 一帧的五次搬家](#1开场三件事共用一条管线--一帧的五次搬家)
2. [Camera2 的编程模型](#2camera2-的编程模型)
3. [CameraX 的价值与取舍](#3camerax-的价值与取舍)
4. [Camera HAL3 与 CameraService](#4camera-hal3-与-cameraservice)
5. [从 Sensor 到 App 的数据形态](#5从-sensor-到-app-的数据形态)
6. [三条输出路径的差异](#6三条输出路径的差异)
7. [MediaCodec 详解](#7mediacodec-详解)
8. [Codec2 架构](#8codec2-架构)
9. [编码到文件：封装与解封装](#9编码到文件封装与解封装)
10. [解码与播放：ExoPlayer/Media3](#10解码与播放exoplayermedia3)
11. [与 SurfaceFlinger 的衔接（指向 12 篇）](#11与-surfaceflinger-的衔接指向-12-篇)
12. [音频在录制/播放中的角色（指向 21 篇）](#12音频在录制播放中的角色指向-21-篇)
13. [车机场景专章](#13车机场景专章)
14. [调试工具箱](#14调试工具箱)
15. [常见问题排查表](#15常见问题排查表)
16. [读源码的推荐路线](#16读源码的推荐路线)
17. [关键源码路径速查](#17关键源码路径速查)
18. [一图总结](#18一图总结)
19. [关联阅读](#19关联阅读)

---

## 1. 开场：三件事共用一条管线 + 一帧的五次搬家

### 1.1 先建立直觉：相机和视频不是两个系统

很多人学 Android 会分开学"相机"和"视频播放"，但它们底层是**同一条数据流水线**的不同出口。

用一个生活类比：相机 Sensor 像一台**高速复印机**，每毫秒吐出一张原始纸（RAW）；ISP 像**修图车间**，把 RAW 修成能看的照片（YUV/JPEG）；Camera HAL 像**收发室**，把修好的纸装进标准信封（gralloc buffer）交给应用；应用可以：

- 直接把信封贴到橱窗上给路人看（**预览** → SurfaceFlinger 上屏）；
- 把信封交给档案室归档（**拍照** → ImageReader 存文件）；
- 把信封交给压缩厂做成小包裹寄走（**录像** → MediaCodec 编码 → MediaMuxer 写文件）；
- 反过来，播放就是把包裹拆开还原成纸，再贴到橱窗（**解码** → SurfaceFlinger 上屏）。

所以：拍照、录像、播放，本质是同一笔"纸张物流"在不同节点的分叉。

### 1.2 一帧图像的五次搬家（全景图）

```text
拍照 / 录像 / 播放 共用同一笔数据物流：

  ┌──────────────────────── 采集端（相机）────────────────────────┐
  │                                                                │
  │  Sensor(光电信号)                                              │
  │     │  ① 光电→RAW（模拟转数字，驱动层）                        │
  │     ▼                                                          │
  │  ISP(图像信号处理器)                                           │
  │     │  ② RAW→YUV/JPEG（去噪/去马赛克/3A 后处理）               │
  │     ▼                                                          │
  │  Camera HAL3 (相机硬件抽象层)                                   │
  │     │  ③ 把帧装进 gralloc buffer，经 BufferQueue 出队          │
  │     ▼                                                          │
  │  App 侧 Surface（来自 BufferQueue 的消费者端）                 │
  │     │  ④ 一条路上屏 / 一条路编码 / 一条路存图                  │
  │     ├──────────────► SurfaceFlinger 合成 → 屏幕（预览）        │
  │     ├──────────────► MediaCodec(编码) → MediaMuxer → 文件（录像）│
  │     └──────────────► ImageReader 拿到 Image → 存 JPEG（拍照）   │
  └──────────────────────────────────────────────────────────────┘

  ┌──────────────────────── 播放端（反向）────────────────────────┐
  │  文件 → MediaExtractor 解封装 → MediaCodec(解码)                │
  │     │  ⑤ 解码输出 Surface → BufferQueue → SurfaceFlinger → 屏幕 │
  └──────────────────────────────────────────────────────────────┘

  关键"搬家"计数（以录像为例一帧经历的 5 次 buffer 易主）：
    ① Sensor → ISP            （硬件内部 DMA / 内存）
    ② ISP → Camera HAL        （HAL 持有 buffer，递交 BufferQueue）
    ③ Camera HAL → App Surface（BufferQueue 生产者→消费者）
    ④ App Surface → MediaCodec（编码输入 Surface，跨进程/跨 buffer）
    ⑤ MediaCodec → 文件/再解码上屏（编码产物落盘或解码回 Surface）
```

> 记住一句话：**相机产帧、BufferQueue 传帧、SurfaceFlinger/MediaCodec 消费帧**。排查任何卡顿/黑屏，先问"这一帧卡在哪次搬家"。

### 1.3 本篇怎么读

- App 开发者：直接看第 2、3、6、7、9、10 章，能写出拍照/录像/播放。
- Framework/ROM 开发者：重点看第 4、5、8、11、13 章，理解 HAL、Buffer 流转与车机约束。
- 排查问题：直接跳第 14、15 章拿命令和排查表。
- 想读源码：第 16、17 章给路线和路径。

`「Android 视角」`：下文凡标注此块的内容，是 Android 特有的机制（权限、Binder、HAL、Scoped Storage 等），跳过不影响对原理的理解，但做 ROM/车机必看。

---

## 2. Camera2 的编程模型

`Camera2`（`android.hardware.camera2`，API 21 / Android 5.0 Lollipop 引入）是取代旧 `Camera`（`android.hardware.Camera`，已废弃）的全新相机 API。它的核心是**"请求-结果"的流水线（pipeline）语义**，而不是旧 API 的"打开相机就不断回调帧"。

### 2.1 五个核心类

| 类 | 引入版本 | 作用 | 直觉 |
|---|---|---|---|
| `CameraManager` | API 21 | 系统相机总管，枚举/打开相机 | 教务处：查有哪些相机、开哪间 |
| `CameraDevice` | API 21 | 代表一个被打开的相机实例 | 这间教室的钥匙，开了才能用 |
| `CameraCaptureSession` | API 21 | 相机与一组输出 Surface 的会话 | 教室和若干"收件箱"之间的快递通道 |
| `CaptureRequest` | API 21 | 一次拍摄请求（参数打包） | 一张快递单：拍什么、怎么拍 |
| `CaptureResult` | API 21 | 一次拍摄的结果元数据 | 快递回执：实际用了什么参数、拍成了啥 |

`「Android 视角」`：`CameraManager` 是系统服务 `CameraService` 在 App 进程的 Binder 代理入口；`CameraDevice`/`CameraCaptureSession` 背后都通过 Binder 连到 `CameraService` → HAL，App 拿到的只是 framework 侧的封装对象。

### 2.2 请求-结果的流水线语义

Camera2 不是"调一次拍一张"，而是把请求投进流水线，相机硬件按序处理，结果异步回来：

```text
App 构造 CaptureRequest（绑定若干输出 Surface）
   │
   ▼
CameraCaptureSession.capture / setRepeatingRequest
   │  // 请求进入 in-flight 队列
   ▼
CameraService → Camera HAL → ISP → Sensor 真正出帧
   │
   ▼
onCaptureStarted（开始曝光）        // 回调：这一帧开始拍了
onCaptureProgressed（部分元数据）   // 回调：3A 状态进展
onCaptureCompleted（CaptureResult） // 回调：这一帧拍完，附带元数据
```

#### 2.2.1 in-flight 数量（在途请求）

"in-flight"指已提交但还没拿到 `CaptureResult` 的请求数。Camera2 允许流水线里有多个请求同时在途（类似 CPU 流水线），这才能做到高帧率预览。HAL 通过 `CameraCharacteristics` 报告最大在途帧数上限（`REQUEST_PIPELINE_MAX_DEPTH`）。App 不需要手动管理，但理解它有助于解释"为什么连点拍照会有延迟"——前面的请求还在硬件里排队。

#### 2.2.2 repeating request vs one-shot request

| 类型 | API | 语义 | 典型用途 |
|---|---|---|---|
| Repeating | `setRepeatingRequest` | 反复投递同一请求，直到停止 | 预览（连续出帧） |
| One-shot | `capture` | 投递一次，优先级更高、插队 | 拍照、单次抓帧 |

`capture()` 的一次性请求会**插到 repeating 前面**，所以拍照时预览会短暂让路，拍完恢复预览——这就是为什么拍照瞬间预览会"顿一下"。

### 2.3 OutputConfiguration 与 Surface 组合

一个 `CameraCaptureSession` 可以同时绑定多个输出 Surface，每个 Surface 对应一种用途：

```text
CameraCaptureSession
   ├─ Surface A（预览 TextView / SurfaceView 的 Surface）→ 连续帧
   ├─ Surface B（ImageReader 的 Surface）               → 拍照高分辨率帧
   └─ Surface C（MediaCodec 编码输入 Surface）          → 录像帧
```

`OutputConfiguration`（API 24 引入）封装"一个输出目标 + 它的 Surface"，多个 `OutputConfiguration` 用 `SessionConfiguration` 提交给 `createCaptureSession`。核心约束：**HAL 是否支持某个 Surface 组合，由 `CameraCharacteristics` 的流配置表决定**（见第 5 章）。比如很多手机不能同时 4K 预览 + 4K 录像 + 高分辨率拍照，因为 ISP/带宽不够。

### 2.4 最小 Kotlin 示例（预览 + 拍照骨架）

```kotlin
// 1) 拿到 CameraManager 并打开相机
val cm = getSystemService(CameraManager::class.java)
val cameraId = cm.cameraIdList[0]           // 0 通常是后置；车机多摄要枚举 characteristics
cm.openCamera(cameraId, executor, object : CameraDevice.StateCallback() {
    override fun onOpened(device: CameraDevice) {
        // 2) 准备输出 Surface：预览用 SurfaceView/TextureView 的 Surface
        val targets = listOf(previewSurface, imageReader.surface)
        // 3) 创建会话（API 21 基础版；高版本用 SessionConfiguration）
        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                // 4) 预览：repeating request
                val previewReq = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(previewSurface)
                }
                session.setRepeatingRequest(previewReq.build(), null, handler)
            }
        }, handler)
    }
    override fun onDisconnected(device: CameraDevice) { device.close() }
    override fun onError(device: CameraDevice, error: Int) { device.close() }
})

// 拍照：一次性 capture 插队到预览前
val shotReq = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
    addTarget(imageReader.surface)
}
session.capture(shotReq.build(), object : CameraCaptureSession.CaptureCallback() {
    override fun onCaptureCompleted(session: CameraCaptureSession,
                                    request: CaptureRequest, result: CaptureResult) {
        // result 里可拿到本次拍照的实际曝光/对焦/白平衡元数据
    }
}, handler)
```

`「Android 视角」`：`TEMPLATE_PREVIEW`/`TEMPLATE_STILL_CAPTURE`/`TEMPLATE_RECORD` 等模板只是"推荐参数集合"，framework 会按模板填默认值，具体能否生效还要看 HAL 实现。车机 HAL 常对模板做裁剪。

---

## 3. CameraX 的价值与取舍

`CameraX`（`androidx.camera.*`，Jetpack 组件，1.0.0 稳定版于 2021 年随 Android 11/12 周期发布）是**构建在 Camera2 之上的上层封装**，目标是消除设备碎片化、简化生命周期管理。

### 3.1 三个核心 UseCase

| UseCase | 作用 | 底层对应 |
|---|---|---|
| `Preview` | 把相机画面送到 `PreviewView` | Camera2 预览 Surface + repeating request |
| `ImageCapture` | 拍照、闪光灯、连拍、HDR | Camera2 `capture` + ImageReader |
| `VideoCapture` | 录像到文件 | Camera2 编码输入 Surface + MediaRecorder/MediaCodec |

使用方式（生命周期感知，避免手动 release）：

```kotlin
val cameraProvider = ProcessCameraProvider.getInstance(this).get()
val preview = Preview.Builder().build().also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }
val imageCapture = ImageCapture.Builder()
    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)   // 或 MAX_QUALITY
    .build()
val videoCapture = VideoCapture.Builder().build()

cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
    preview, imageCapture, videoCapture)   // 一条调用把多个 UseCase 绑到生命周期
```

### 3.2 Camera2 vs CameraX 取舍表

| 维度 | Camera2 | CameraX |
|---|---|---|
| 抽象层级 | 底层，直接操作 device/session/request | 高层，UseCase 组合 |
| 生命周期 | 手动 open/close，易泄漏 | 自动跟随 Lifecycle |
| 设备适配 | 需自己处理旋转/比例/厂商差异 | 官方做兼容性层，自动选最佳配置 |
| 多摄/逻辑相机 | 需读 `CameraCharacteristics` 自己拼 | 提供 `CameraSelector` 语义选择 |
| 控制粒度 | 极细（每帧参数、in-flight） | 粗（模板级），深度定制受限 |
| 车机多路并发 | 必须 Camera2（CameraX 不擅长多路同时） | 不适合 |
| 倒车/环视低延迟 | 必须 Camera2 + HAL 直连 | 不满足 |
| 学习成本 | 高 | 低 |

**抉择建议**：
- 普通 App、扫码、人像、短视频：优先 CameraX，省事抗碎片。
- 车机倒车/环视/DMS、需要多路相机同时出帧、需要精确控制每帧参数或直连 HAL：必须用 Camera2，甚至绕过 framework 走 vendor 专用通道。

`「Android 视角」`：CameraX 的 `ProcessCameraProvider` 内部仍然走 `CameraManager`/`CameraDevice`，它的兼容性处理本质上是在运行时读 `CameraCharacteristics` 并挑一组最合适的流配置——这部分逻辑在没有 CameraX 的年代要开发者自己写。

---

## 4. Camera HAL3 与 CameraService

### 4.1 HAL 接口演进（版本对照）

| 阶段 | 版本 | 特征 | 现状 |
|---|---|---|---|
| HAL1 (`CAMERA_DEVICE_API_VERSION_1_0`) | Android 早期 | 面向"拍照"的阻塞式 API，`takePicture` 风格 | 已废弃 |
| HAL3 (`CAMERA_DEVICE_API_VERSION_3_0~3_7`) | API 21 / 5.0 引入，逐步演进到 3.7 | 请求-结果流水线、metadata 驱动、3A 可控 | 现代主流 |
| HIDL Camera HAL | API 26 / 8.0 引入 | 用 HIDL 描述 HAL 接口，Treble 化 | 大量存量设备 |
| AIDL Camera HAL | API 31 / 12 引入 | 用 AIDL 替代 HIDL，统一接口语言、性能更好 | 新平台/车机主流 |

`「Android 视角」`：Android 8.0 的 Treble 把 HAL 从 `libhardware` 的 C 结构体改成 HIDL/IPC；Android 12 起相机 HAL 优先用 AIDL。读写 vendor 相机代码时，先确认是 HIDL 还是 AIDL——两者接口文件目录完全不同（`hardware/interfaces/camera/` vs `aidl/android/hardware/camera/`）。

### 4.2 Provider / Device / Session / RequestThread 层次

Camera HAL3 的逻辑层次（从 framework 往下）：

```text
CameraService (system_server / cameraserver 进程)
   │ Binder
   ▼
CameraProviderManager            // framework 侧：管理所有 CameraProvider（一个 HAL 进程一个）
   │ HIDL/AIDL
   ▼
ICameraProvider (HAL 进程，如 vendor.camera-provider)  // 枚举相机、懒加载 device
   │
   ▼
ICameraDevice (HAL)              // 对应一个物理/逻辑相机
   │
   ▼
ICameraDeviceSession (HAL)       // 一个已配置的流会话
   │
   ▼
RequestThread (HAL 内部线程)     // 把 framework 的 capture 请求转成对驱动/Sensor 的实际操作
   │
   ▼
Driver / ISP / Sensor
```

- `CameraProviderManager`：framework 在 `CameraService` 里维护，负责连接每个 HAL provider、缓存 `CameraCharacteristics`。
- `CameraProvider`：`getCameraIdList()` 枚举相机、`createDevice()` 创建设备。
- `CameraDevice`/`CameraDeviceSession`：HAL 侧实现，处理 `processCaptureRequest`（一次提交一批请求）。
- `RequestThread`：HAL 内部把 framework 请求转成硬件操作、并按 in-flight 上限调度。

### 4.3 Buffer 管理：gralloc handle 流转

相机出帧的核心是 buffer 在进程间"零拷贝"流转，靠 `gralloc`（图形内存分配器）和 `BufferQueue`：

```text
HAL 侧：
  gralloc 分配一块物理连续/可 DMA 的内存（buffer）
  ISP 把帧写入这块 buffer
  HAL 通过 BufferQueue::queue 把 buffer 句柄（handle，不是数据拷贝）交给消费者

App 侧（消费者）：
  Surface 从 BufferQueue::acquire 拿到同一块 buffer 的 handle
  预览：SurfaceFlinger 直接合成这块 buffer 上屏
  录像：MediaCodec 读这块 buffer 编码
```

关键点：**流转的是 gralloc handle（指向同一块内存的句柄），不是把像素 memcpy 一遍**。这就是相机能做到高帧率低延迟的原因。车机倒车影像对这条 buffer 链路延迟极其敏感（见第 13 章）。

### 4.4 打开相机的完整调用链（framework → HAL）

```text
App: CameraManager.openCamera
  └─ CameraManagerGlobal → ICameraService.openCameraDeviceUser
       └─ CameraService::connectDevice
            └─ CameraProviderManager::startProviderInterface / getCameraDeviceInterface
                 └─ ICameraProvider::createDevice   (HIDL/AIDL Binder 到 HAL 进程)
                      └─ HAL: CameraDevice 创建
            └─ 返回 CameraDeviceUser（App 侧 CameraDevice 的 Binder 代理）
  └─ App 拿到 CameraDevice
```

`「Android 视角」`：`CameraService` 本身在 `system_server` 中，但它会通过 `cameraserver` 相关 binder 线程与 HAL 通信；HAL 是独立进程（通常 `vendor` 进程），跨进程靠 HIDL/AIDL。权限校验（CAMERA 权限、AppOps、device policy）在 `CameraService` 这层完成。

---

## 5. 从 Sensor 到 App 的数据形态

### 5.1 图像形态链：RAW → YUV → JPEG

| 形态 | 含义 | 谁产出 | App 通常拿到吗 |
|---|---|---|---|
| RAW（Bayer） | Sensor 原始拜耳阵列，未去马赛克 | Sensor/ISP 最前端 | 一般拿不到（专业模式/RAW 域才暴露） |
| YUV | 去马赛克、去噪后的亮色分离数据 | ISP | 预览/录像常用 `YUV_420_888` |
| JPEG | 压缩后的标准图片 | ISP 或 CPU 编码 | 拍照产物 |

常用像素格式（详见 `ImageFormat`）：
- `YUV_420_888`：灵活的三平面 YUV，Camera2 最常用输出格式。
- `JPEG`：拍照直出。
- `PRIVATE`：Surface 内部格式，常用于预览/编码 Surface（App 不能直接读像素，交给 SurfaceFlinger/MediaCodec）。
- `RAW_SENSOR` / `RAW10` / `RAW16`：RAW 域。

### 5.2 3A：AE / AF / AW B

3A 是 ISP 实时调节的三件事，Camera2 通过 `CaptureRequest` 控制、`CaptureResult` 回报：

| 项 | 全称 | 作用 | 相关 key |
|---|---|---|---|
| AE | Auto Exposure（自动曝光） | 控制快门/增益/曝光时间 | `CONTROL_AE_MODE` |
| AF | Auto Focus（自动对焦） | 控制镜头对焦 | `CONTROL_AF_MODE` |
| AWB | Auto White Balance（自动白平衡） | 控制色温 | `CONTROL_AWB_MODE` |

`CaptureResult` 里的 `RESULT_KEY` 能让你知道"这次拍的帧，AE 到底收敛到多少曝光时间、AF 对没对上焦"——对焦失败、夜景过暗等问题都先查 3A 结果。

### 5.3 stride（步长）与内存布局

YUV buffer 在内存里**不一定**是"宽×高×1.5"紧密排列。每行之间有 **stride（步长/对齐）**，因为 GPU/ISP 要求行对齐（如 16/64 字节对齐）。读 `ImageReader` 的 `Plane` 时必须用 `rowStride`/`pixelStride`，不能想当然按宽度算：

```kotlin
val image = reader.acquireLatestImage()
val plane = image.planes[0]            // Y 平面
val buffer = plane.buffer
val rowStride = plane.rowStride        // 一行实际字节数（可能 > width）
val pixelStride = plane.pixelStride    // 像素间距（YUV420 中 Y 通常为 1）
// 拷贝时必须按 rowStride 跨行，否则图像错位/出现绿条
image.close()                          // 用完必须 close，否则 buffer 不回池，预览卡死
```

### 5.4 多摄与逻辑相机（logical multi-camera）

Android 9（API 28）引入**逻辑相机**：一个 `CameraDevice` 背后可能是多个物理相机（广角+长焦+超广角）融合。

- `CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA` 标记支持。
- `CameraCharacteristics.getPhysicalCameraIds()` 拿到物理子相机。
- `CaptureRequest` 可指定 `LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE` 做物理相机同步曝光（用于景深/融合）。

`「Android 视角」`：逻辑相机在 framework 层做融合调度，HAL 层负责真正把多路 Sensor 的帧对齐。车机环视（4 路鱼眼融合成俯视图）常是 vendor 自己实现的"逻辑相机"或独立拼接服务。

### 5.5 CameraCharacteristics 里值得读的字段

| 字段 | 为什么重要 |
|---|---|
| `SCALER_STREAM_CONFIGURATION_MAP` | 哪些格式+尺寸组合合法，决定 Surface 组合能不能配 |
| `REQUEST_PIPELINE_MAX_DEPTH` | 最大 in-flight 帧数，影响并发/延迟 |
| `CONTROL_AF_AVAILABLE_MODES` | 支持哪些对焦模式 |
| `SENSOR_INFO_ACTIVE_ARRAY_SIZE` | Sensor 有效区域，裁剪/畸变校正用 |
| `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` | 焦距，多摄切换/光学变焦 |
| `INFO_SUPPORTED_HARDWARE_LEVEL` | `LIMITED`/`FULL`/`LEVEL_3`/`EXTERNAL`，决定能力上限（车机 USB/HDMI 相机常为 `EXTERNAL`） |
| `SYNC_MAX_LATENCY` | 请求到结果的延迟等级，低延迟场景关键 |

`「Android 视角」`：`INFO_SUPPORTED_HARDWARE_LEVEL` 为 `EXTERNAL` 时（USB 摄像头、部分车机 HDMI-in），很多高级 3A/手动控制不被支持——别照着手机文档硬调参数。

---

## 6. 三条输出路径的差异

同一个 `CameraCaptureSession` 可以把帧同时送到不同 Surface，但三条路径的数据流和延迟特征完全不同。

### 6.1 预览 → Surface（最低延迟）

```text
Camera HAL 出帧
   └─ BufferQueue(queue)  → 预览 Surface（SurfaceView/TextureView/PreviewView）
        └─ SurfaceFlinger acquire → 合成 → 屏幕
```

- 格式多为 `PRIVATE`，App 不直接读像素。
- 走 `setRepeatingRequest`，连续出帧。
- 延迟最低（典型 < 1 帧~几十 ms），是倒车影像的基础。
- 关键：`Surface` 必须在 `createCaptureSession` 前就合法存在（SurfaceView 要等 `SurfaceHolder.Callback.surfaceCreated`）。

### 6.2 拍照 → ImageReader（高分辨率、一次性）

```text
Camera HAL 出帧（按 STILL_CAPTURE 模板，可能用不同 stream）
   └─ BufferQueue → ImageReader 的 Surface
        └─ ImageReader.acquireLatestImage() 拿到 Image
             └─ 读 YUV/转 JPEG → 写文件
```

- 分辨率通常高于预览（如 4800 万 vs 预览 1080p）。
- `capture()` 一次性请求，插队到预览前，所以拍照瞬时预览会顿。
- 必须 `image.close()` 归还 buffer，否则 BufferQueue 池耗尽 → 后续帧全部阻塞（"拍照后预览卡死"的经典原因）。
- 延迟：从按下到拿到 Image 可能 100~500ms（含 3A 收敛）。

### 6.3 录像 → MediaCodec 输入 Surface（编码、持续）

```text
Camera HAL 出帧
   └─ BufferQueue → MediaCodec 的「输入 Surface」（Input Surface）
        └─ MediaCodec 内部把 Surface 的 buffer 作为编码输入（不拷贝像素）
             └─ 编码产出 → MediaMuxer 写文件
```

- 录像**不要**把帧 `ImageReader` 读出来再喂 `MediaCodec`（多了一次拷贝/CPU 解码，慢且耗电）。
- 正确做法：用 `MediaCodec.createInputSurface()` 拿到 Surface，直接作为 `CaptureRequest` 的 target。
- 帧直接以 gralloc buffer 形式进编码器，零拷贝。
- 延迟：录像本身实时，但写入文件有缓冲；倒车影像**不录像**，所以走 6.1 而非 6.3。

### 6.4 三条路径对比

| 维度 | 预览 Surface | ImageReader 拍照 | MediaCodec 输入 Surface 录像 |
|---|---|---|---|
| 格式 | `PRIVATE` | `YUV_420_888`/`JPEG` | `PRIVATE`（编码器内部处理） |
| 模式 | repeating | one-shot | repeating |
| 是否 App 读像素 | 否（直接上屏） | 是（需 close） | 否（编码器消费） |
| 延迟 | 最低 | 中（含 3A） | 低（实时编码） |
| 典型故障 | 黑屏/绿屏 | 卡死(buffer 不归还) | 卡顿/丢帧/音画不同步 |

`「Android 视角」`：能不能同时开三条，取决于 HAL 的 `SCALER_STREAM_CONFIGURATION_MAP`。车机常需"预览 + 录像 + DMS 分析"三路并发，选平台时务必确认 HAL 支持。

---

## 7. MediaCodec 详解

`MediaCodec` 是 Android 的音视频**编解码器（codec）**访问入口，位于 `android.media`。它既能编码（相机录像）也能解码（视频播放），是连接"原始帧/Surface"和"压缩码流/文件"的桥梁。

### 7.1 两种输入模式

| 模式 | 用法 | 适用 |
|---|---|---|
| ByteBuffer 输入 | `getInputBuffer(index)` 拿 buffer，手动填数据，queue 回去 | 已有内存数据（如网络流、离线文件） |
| Surface 输入 | `createInputSurface()` 拿到 Surface 作为源 | 相机录像、屏幕录制（零拷贝） |

```text
ByteBuffer 模式：
  dequeueInputBuffer(timeout) → 拿到空 buffer 索引
  fill buffer with raw frame
  queueInputBuffer(index, ..., pts, flags)
       │
       ▼
  MediaCodec 内部编码/解码
       │
  dequeueOutputBuffer → 拿到编码后 buffer / 解码后数据
  releaseOutputBuffer(index, renderToSurface)

Surface 模式：
  MediaCodec.createInputSurface() → 这个 Surface 作为「生产者」
  相机 / 屏幕录制 把帧 queue 进它
  MediaCodec 自动消费，App 不碰像素
```

### 7.2 同步模式 vs 异步回调模式

| 模式 | API 版本 | 用法 | 适用 |
|---|---|---|---|
| 同步 | 全版本 | 自己在线程里 `dequeueInput/OutputBuffer` 轮询 | 简单脚本、离线处理 |
| 异步回调 | API 21（Lollipop）引入 `MediaCodec.Callback` | `setCallback` + `onInputBufferAvailable`/`onOutputBufferAvailable` | 实时播放/录像，避免轮询阻塞 |

异步示例（API 21+）：

```kotlin
codec.setCallback(object : MediaCodec.Callback() {
    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
        // 有空输入 buffer，填数据（Surface 模式下通常不用管，framework 自己填）
    }
    override fun onOutputBufferAvailable(codec: MediaCodec, index: Int,
                                          info: MediaCodec.BufferInfo) {
        // 有输出（编码产物或解码帧）
        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) { /* 结束 */ }
        codec.releaseOutputBuffer(index, true) // true=渲染到输出 Surface（上屏）
    }
    override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) { /* 解码失败可能回退软解 */ }
    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) { /* 首帧拿到实际格式 */ }
})
codec.configure(format, outputSurface, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
codec.start()
```

`「Android 视角」`：异步模式在 API 21 才稳定可用；更早版本只能同步轮询。车机/播放器务必用异步，避免主线程或轮询线程卡顿。

### 7.3 MediaCodec 的状态机

`MediaCodec` 内部是一个明确的状态机，理解它才能解释"为什么 configure 报IllegalState、为什么 stop 后必须 reset 才能重用"：

```text
  [Uninitialized]  ── configure() ──►  [Configured]
        ▲                                │ start()
        │ reset()                        ▼
        │                          [Executing]
        │                          ├─ [Flushed]   (flush 后，输入未满)
        │                          ├─ [Running]   (正常编解码)
        │                          └─ [End-of-Stream] (收到 EOS 标志)
        │                                │ stop()
        ▼                                ▼
  [Uninitialized] ◄── reset() ──  [Configured] ... 或 release() → [Released]

  关键约束（踩坑点）：
  - 未 configure 就 start() → IllegalStateException
  - Configured 之后改 MediaFormat → 无效，需 reset()
  - 收到 EOS 后必须 stop()/reset() 才能处理新流
  - release() 后对象作废，再调用任何方法抛异常
```

`「Android 视角」`：这个状态机在 `MediaCodec.cpp`（native）里由 `mState` 维护，Java 层抛的 `IllegalStateException` 就来自状态不匹配。异步模式下 `onOutputFormatChanged` 只在第一次进入 Running 时回调一次。

### 7.4 MediaFormat 配置

编码示例（H.264 录像）：

```kotlin
val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
    setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)        // 8 Mbps
    setInteger(MediaFormat.KEY_FRAME_RATE, 30)             // 30 fps
    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)        // 每 1s 一个关键帧（GOP）
    setInteger(MediaFormat.KEY_COLOR_FORMAT,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) // Surface 输入
}
codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
val inputSurface = codec.createInputSurface()  // 给 Camera2 当 target
```

解码示例：

```kotlin
val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
codec.configure(format, outputSurface /* 上屏 Surface */, null, 0 /* 0=解码 */)
```

### 7.5 关键帧与 BUFFER_FLAG_CODEC_CONFIG

| 标志 | 含义 |
|---|---|
| `BUFFER_FLAG_KEY_FRAME` | 这是一个关键帧（I 帧），可独立解码 |
| `BUFFER_FLAG_CODEC_CONFIG` | 这个 buffer 含编解码器配置数据（SPS/PPS/codec private data），不是帧 |
| `BUFFER_FLAG_END_OF_STREAM` | 这是最后一帧，之后无数据 |
| `BUFFER_FLAG_PARTIAL_FRAME` | 一个访问单元分多个 buffer（少见） |

**坑**：第一个输出 buffer 通常带 `BUFFER_FLAG_CODEC_CONFIG`（含 SPS/PPS），它**不是**一帧画面，不能直接当帧渲染/写入文件，但**必须**在写文件/喂给后续解码器前先写配置数据，否则对方无法解码。`MediaMuxer` 会自动抽离处理，但你手动拼流时要小心。

### 7.6 MediaCodecList 与软硬解选择

选择编码器/解码器靠 `MediaCodecList` + `MediaCodecInfo`：

```text
MediaCodecList
  └─ getCodecInfos()
       ├─ 硬解/硬编：CodecInfo.isHardwareAccelerated() == true，名字常含 "OMX.<vendor>." 或 "c2.<vendor>."
       └─ 软解/软编：isSoftwareOnly() == true，名字常含 "OMX.google." / "c2.android."
```

`「Android 视角」`：现代 Android（Android 11+）默认走 **Codec2（CCodec）** 而非老 OMX。老 OMX 插件（`OMX.*`）在 Android 12+ 基本淘汰，新平台只用 Codec2 组件（`c2.*`）。选 Codec2 还是 OMX 是 framework 的组件发现/ranking 决定的（见第 8 章）。车机硬解 H.265 必须确认 vendor 是否提供 `c2.<vendor>.hevc.decoder`。

---

## 8. Codec2 架构

### 8.1 MediaCodec 与 Codec2 HAL 的关系

`MediaCodec`（Java API）只是一层门面。底层经历了两代组件架构：

```text
MediaCodec (Java)
   │
   ▼
MediaCodecSource / 统一封装层
   │
   ├─ 旧：OMX 组件 (OMXNodeInstance)        ← Android 12+ 基本废弃
   │
   └─ 新：CCodec（Codec2 客户端）            ← Android 11+ 默认
        │
        ▼
   Codec2 HAL（组件运行在 vendor/codec 进程）
        │
        ▼
   实际编解码（硬件加速 / 软件）
```

`Codec2`（Codecs v2）是 Android 11（API 30）引入的新媒体组件框架，解决 OMX 的线程模型缺陷和性能问题。CCodec 是 framework 侧对接 Codec2 的适配层；`MediaCodec` 的 API 不变，底层自动选 CCodec 还是 OMX。

### 8.2 组件发现与 ranking（为什么系统选了它）

framework 决定"用哪个解码器"靠组件发现 + ranking：

```text
Codec2Client 枚举所有已注册组件（来自 vendor 的 .so / 配置）
   └─ 每个组件声明：支持的 mime、是否硬件、是否软件、rank 值
        └─ MediaCodecList 据此排序
             └─ 默认选 rank 最优且能满足要求的组件
                  ├─ 优先硬件加速组件（rank 高）
                  └─ 某些场景（如安全/DRM）只能用特定组件
```

ranking 规则（直觉）：
- 硬件组件 rank 通常高于软件组件 → 默认硬解。
- 同一 mime 若有多个硬件组件，按 vendor 配置的 rank 取最优。
- 软解（`c2.android.*` / `OMX.google.*`）作为兜底，硬解失败/不支持时回退。

`「Android 视角」`：车机如果硬解 H.265 黑屏/花屏，先确认用的是 `c2.<vendor>.hevc.decoder` 还是回退到了软解——`dumpsys media.codec` 能看到当前组件（见第 14 章）。

### 8.3 为什么淘汰 OMX

| 维度 | OMX | Codec2 / CCodec |
|---|---|---|
| 线程模型 | 回调嵌套、易死锁 | 更清晰的异步、确定性状态 |
| 性能 | 中转多、延迟高 | buffer 流转更直接、低延迟 |
| 维护 | 代码老化、Google 不再主推 | 新平台唯一演进方向 |
| 版本 | Android 12+ 废弃 | Android 11+ 默认 |

`「Android 视角」`：虽然 API 叫 `MediaCodec` 没变，但 ROM 开发者排查编解码问题时要分清"这是 CCodec 路径还是 OMX 路径"——logcat tag 完全不同（`CCodec`/`Codec2Client` vs `OMXClient`/`ACodec`）。

---

## 9. 编码到文件：封装与解封装

### 9.1 MediaMuxer：把编码产物封装成 mp4/mkv

光有 `MediaCodec` 的码流还不够，还要**封装（mux）**成容器（如 mp4）。`MediaMuxer` 负责把视频轨 + 音频轨交错写入一个文件：

```text
MediaCodec(视频) → 编码 buffer
MediaCodec(音频) → 编码 buffer   // 音频部分见 21 篇
        │
        ▼
MediaMuxer.addTrack(format) ×2   // 先加轨，拿到 trackIndex
MediaMuxer.start()
        │
        ▼
MediaMuxer.writeSampleData(trackIndex, buffer, bufferInfo)  // PTS 在这里写入
        │
        ▼
MediaMuxer.stop() / release()
```

### 9.2 时间戳 PTS / DTS

| 概念 | 含义 |
|---|---|
| PTS（Presentation Time Stamp） | 该帧**应该播放**的时刻 |
| DTS（Decode Time Stamp） | 该帧**应该解码**的时刻 |
| 关系 | 无 B 帧时 PTS==DTS；有 B 帧时 DTS < PTS（先解码、后显示） |

`MediaCodec` 的 `BufferInfo.presentationTimeUs` 就是 PTS。写 `MediaMuxer` 时**必须**给对 PTS，否则播放快进/慢放/音画不同步。相机录像时 PTS 通常来自 `CaptureResult` 的时间基准或编码器内部计数。

### 9.3 音视频轨交错与对齐

- 视频帧和音频帧要**交错**写入（不是先写完整视频再写音频），否则 seek/播放异常。
- 两轨的 PTS 基准必须一致（同一时钟起点），否则音画不同步。
- `MediaMuxer` 对 mp4 要求第一帧通常是关键帧 + codec config。

### 9.4 Scoped Storage 对录像写文件的影响（版本坑）

| 版本 | 行为 |
|---|---|
| Android 9 (API 28) 及之前 | 直接 `FileOutputStream` 写 `sdcard/` 任意路径 |
| Android 10 (API 29) | Scoped Storage 引入，App 默认只看到自己沙箱 + MediaStore |
| Android 11 (API 30) | 强制 Scoped Storage，写共享区必须走 `MediaStore` 或 `MediaColumns`；`requestLegacyExternalStorage` 失效 |
| Android 11+ 特殊情况 | `MANAGE_EXTERNAL_STORAGE` 权限可获广泛访问（Google Play 严格审核）；或写到 App 私有目录 `/sdcard/Android/data/<pkg>/` 不受限 |

`「Android 视角」`：车机录像 App 若还要兼容老文件管理习惯：
- 优先写 App 私有目录（`getExternalFilesDir`）——无需权限、不受 Scoped Storage 限制，但卸载即删。
- 要进相册/被其他 App 访问：用 `MediaStore.Video` 插入并拿 `ContentResolver.openFileDescriptor`。
- 行车记录循环录像（第 13 章）强烈建议写私有目录 + 自己的循环删除逻辑，避免 MediaStore 索引开销和权限弹窗。

### 9.5 MediaExtractor：解封装读轨

播放时反向用 `MediaExtractor` 把文件拆回各轨：

```text
MediaExtractor.setDataSource(file)
   └─ getTrackCount() → 每条轨一个 MediaFormat
   └─ selectTrack(i)
   └─ readSampleData(buffer, offset) → 拿到一帧压缩数据 + sampleTime(PTS)
   └─ 喂给对应 MediaCodec 解码
```

---

## 10. 解码与播放：ExoPlayer / Media3

### 10.1 三段结构（Extractor → Renderer → Surface）

现代播放器（ExoPlayer，已并入 AndroidX Media3）的骨架是：

```text
MediaSource (描述数据源：文件/网络/HLS/DASH)
   └─ Extractor（解封装，等价于 MediaExtractor，拆轨）
        └─ 各 Track 的 Sample 流
             └─ Renderer（每轨一个：VideoRenderer / AudioRenderer / TextRenderer）
                  ├─ VideoRenderer → MediaCodecRenderer → MediaCodec(解码) → 输出 Surface → SurfaceFlinger
                  └─ AudioRenderer → MediaCodec(解码音频) → AudioTrack（见 21 篇）
```

### 10.2 MediaCodecRenderer

`MediaCodecRenderer`（Media3 中 `MediaCodecVideoRenderer`/`MediaCodecAudioRenderer` 的基类）是播放器里"指挥 MediaCodec"的角色：

```text
MediaCodecRenderer
   └─ 从 Extractor 拿压缩 sample（带 PTS）
   └─ 调 MediaCodec.dequeueInputBuffer → queueInputBuffer（喂码流）
   └─ onOutputBufferAvailable → releaseOutputBuffer(index, render=true)（视频上屏 / 音频给 AudioTrack）
   └─ 负责对齐 PTS 与播放时钟
```

视频播放"上屏"靠 `releaseOutputBuffer(index, true)`：第二个参数 `render=true` 让解码出的 buffer 直接送进输出 Surface → BufferQueue → SurfaceFlinger（详见第 11 章）。

### 10.3 音视频同步（AV sync）机制

播放器维护一个**主时钟（clock）**，通常：
- 以**音频**为基准时钟（audio 连续性最好，见 21 篇 AudioTrack 的播放位置）。
- 视频帧按 PTS 与主时钟比对：
  - PTS 落后主时钟 → 丢帧（drop frame），快进追上。
  - PTS 超前主时钟 → 延迟渲染（hold），等时钟。

```text
主时钟（音频播放位置）
   │
   ├─ 视频帧 PTS < 时钟 - 阈值  → 丢弃该帧（画面跳）
   ├─ 视频帧 PTS ≈ 时钟         → 正常渲染
   └─ 视频帧 PTS > 时钟 + 阈值  → 暂不渲染，等时钟
```

### 10.4 音画不同步的归因

| 现象 | 常见原因 | 指向 |
|---|---|---|
| 整体慢半拍 | PTS 基准不一致（视频轨/音频轨起点不同） | 9.2 时间戳 |
| 偶尔跳帧 | 视频解码慢，被迫丢帧追时钟 | 8.2 硬解/软解 |
| 越放越偏 | 音频时钟漂移 / 视频帧率与容器声明不符 | 21 篇音频时钟 |
| 开局不同步 | 第一帧不是关键帧 / codec config 丢失 | 7.4 |
| 网络流卡 | 缓冲不足导致 PTS 跳变 | 播放器缓冲策略 |

`「Android 视角」`：视频同步细节见 21 篇音频时钟；本文只讲"视频帧如何上屏 + PTS 从哪来"。不要把音画同步完全归咎于视频侧——多数情况是音频时钟或 PTS 写错。

---

## 11. 与 SurfaceFlinger 的衔接（指向 12 篇）

### 11.1 视频帧如何最终上屏

不管是**相机预览**还是**视频解码播放**，最终画面都要经过 `SurfaceFlinger` 合成上屏。回顾 12 篇（WMS/SurfaceFlinger 体系）：Surface 背后是 `BufferQueue`，生产者产帧、消费者（SurfaceFlinger）合成。

```text
相机预览 / 视频解码 的视频 Surface
   │  （Surface 即 BufferQueue 的消费者端句柄）
   ▼
BufferQueue
   │  SurfaceFlinger 作为最终消费者 acquire 帧
   ▼
SurfaceFlinger
   └─ 把该 Layer 的 buffer 交给 HWC 合成
        └─ 屏幕显示
```

- 相机预览的 Surface：`SurfaceView`/`TextureView`/`PreviewView` 的 Surface，被 `CameraCaptureSession` 当作输出 target。
- 视频解码的 Surface：`MediaCodec.configure(format, outputSurface, ...)` 传入的 Surface，`releaseOutputBuffer(index, true)` 渲染到它。
- 两者殊途同归：都通过 `BufferQueue` 把 buffer 交给 `SurfaceFlinger`。

### 11.2 BufferQueue 的"生产者-消费者"视角

| 角色 | 相机预览 | 视频播放 |
|---|---|---|
| 生产者 | Camera HAL（queue 帧） | MediaCodec（queue 解码帧） |
| 消费者 | SurfaceFlinger（合成上屏） | SurfaceFlinger（合成上屏） |
| 介质 | 同一 BufferQueue | 同一 BufferQueue |

关键认知：**MediaCodec 解码输出到 Surface，本质是 MediaCodec 把解码后的 buffer 填进这个 Surface 的 BufferQueue，SurfaceFlinger 来取**。这和第 12 篇 WMS 里"App 画进 Surface、SurfaceFlinger 合成"是同一套机制，只是生产者从 App RenderThread 换成了 MediaCodec。

### 11.3 secure buffer 与 DRM（Widevine 简述）

受版权保护的内容（Widevine L1/L3）解码要求**secure buffer（安全缓冲区）**：

- secure buffer 的内容**不会**离开 TEE/安全硬件，普通 CPU/App 读不到像素。
- 解码器配置为 secure 后，输出 Surface 必须也是 secure path，`SurfaceFlinger`/HWC 在受保护图层合成，截图/录屏拿不到明文。
- Widevine 是 Android 主流 DRM：L1 在硬件安全层解码（高清/4K），L3 在软件层（标清）。

`「Android 视角」`：secure 路径要 vendor HAL（TEE、安全视频通路）支持。车机播放受保护流媒体时，若只能 L3，说明平台未接安全视频通路——这是硬件/ROM 集成问题，App 层无解。详细合成/图层见 12 篇。

### 11.4 衔接小结

- 相机与视频"上屏"这条线 = `Surface + BufferQueue + SurfaceFlinger`，和 WMS 管理的窗口图层是同一套图形栈。
- 想要更低延迟/更低功耗的相机上屏，优化方向在 HAL→BufferQueue 这段（第 4、13 章），不在 SurfaceFlinger 侧。
- 想看图层合成状态：`dumpsys SurfaceFlinger`（见第 14 章与 12 篇）。

---

## 12. 音频在录制/播放中的角色（指向 21 篇）

> 本章**只做衔接，不展开**。音频的录音、播放、混音、焦点、路由已在 `21_Audio机制详解-从AudioTrack到AudioFlinger和AudioPolicy` 详尽讲解。这里只点出"相机/媒体"场景下音频在哪条线上、有哪些坑。

### 12.1 录像时的音频

录像 = 视频（本文第 6.3/7 章）+ 音频（21 篇）一起进 `MediaMuxer`：

```text
Camera HAL → MediaCodec(视频) ─┐
                               ├─ MediaMuxer（交错写 mp4）
Mic → AudioRecord → AudioFlinger → MediaCodec(音频 AAC) ─┘
```

- 录音入口：`AudioRecord`（21 篇第 3 章）。
- 编码：音频通常 AAC（`MediaCodec` 音频组件或 `MediaRecorder` 内部）。
- 封装：视频轨 + 音频轨的 PTS 必须**同一时钟基准**（9.2 节），否则音画不同步。

### 12.2 播放时的音频

播放时音频从 `MediaCodec` 解码后走向 `AudioTrack → AudioFlinger`（21 篇第 2 章），并作为**主时钟**驱动视频同步（10.3 节）。

### 12.3 通道数/采样率不一致的坑（衔接提示）

| 坑 | 说明 | 解决指向 |
|---|---|---|
| 录像采集采样率 ≠ 编码采样率 | 需重采样，可能引入延迟/杂音 | 21 篇 AudioFlinger 重采样 |
| 单声道麦录成双声道 | 封装声明与数据不符，播放异常 | 录制前统一 AudioFormat 声道 |
| 视频 30fps 但音频 44.1k | 时钟基准不同，长片累积漂移 | 用同一时钟源打 PTS（9.2） |
| 蓝牙麦克风延迟高 | 录像配音不同步 | 21 篇蓝牙音频/低延迟 |

---

## 13. 车机场景专章

车机是本文最重要的"Android 落地"场景。相机与媒体编解码在车里有特殊约束，普通手机文档不会讲。

### 13.1 倒车影像与环视（低延迟预览）

倒车/环视对延迟极度敏感：从挂倒挡到画面出来，要求通常 **< 100~200ms**，否则安全隐患。

- **走预览路径（6.1），绝不录像**：倒车影像是实时预览上屏，不经过 MediaCodec 编码。
- **低延迟关键在 HAL→BufferQueue 这段**：减少 in-flight、跳过 3A 收敛（倒车常用固定曝光/定焦）、直接 queue 到预览 Surface。
- **HAL 直连优化**：高端车机让 Camera HAL 出帧后绕过 framework 多余拷贝，直接给 SurfaceFlinger 合成（vendor 定制）。
- **挂挡触发**：`CarService`/vendor 服务监听 gear 状态，直接打开对应相机 `CameraDevice`，不走 App 级 CameraX 生命周期。

### 13.2 多路相机并发的 HAL 约束

环视 = 4 路鱼眼同时出帧；DMS/OMS = 额外 1~2 路。这要求 HAL 支持多路并发：

| 约束 | 说明 |
|---|---|
| 并发 stream 数 | HAL 能否同时开 N 路 preview stream（看 `SCALER_STREAM_CONFIGURATION_MAP` 与硬件能力） |
| ISP/带宽 | 多路 1080p/4K 同时出帧对 ISP、内存带宽压力大 |
| 逻辑相机融合 | 环视拼接常是 vendor 自建"逻辑相机"或独立拼接服务 |
| CameraService 配额 | 系统可能限制同时打开的 `CameraDevice` 数量（device policy / 厂商限制） |

`「Android 视角」`：若倒车影像打不开，先确认是否被其他相机（如 DMS）占用——同一物理相机或同一 HAL 配额被占，新 `openCamera` 会报 `CameraInUseException`/busy（见 15 章）。

### 13.3 DMS / OMS 摄像头

- **DMS（Driver Monitoring System，驾驶员监控）**：车内朝司机，做人眼/疲劳检测，通常中低分辨率、高帧率、常开。
- **OMS（Occupant Monitoring System，乘员监控）**：朝乘客，看儿童/宠物遗留。
- 它们持续出帧给**算法模块**（非上屏），数据流：Camera HAL → Surface/ImageReader → 算法（人脸/姿态）。
- 隐私：车内摄像头受严格权限 + 指示灯 + 隐私合规约束（`CAMERA` 权限 + 厂商隐私策略 + 录制指示）。

### 13.4 H.264 / H.265 硬件编解码选型

| 编码 | 优势 | 劣势 | 车机选型建议 |
|---|---|---|---|
| H.264 (AVC) | 兼容好、硬解普及、复杂度低 | 压缩率一般 | 行车记录、兼容外设首选 |
| H.265 (HEVC) | 同画质码率约减半、省存储/带宽 | 硬解支持不一、专利费、解码耗电略高 | 高清录像/多路环视存储紧张时；务必确认 vendor 提供 `c2.<vendor>.hevc.*` |

`「Android 视角」`：硬解 H.265 是否可用，看 `MediaCodecList` 里有没有 `c2.<vendor>.hevc.decoder`（8.2 节）。很多车机 SoC 支持 H.265 编码但不支持解码，或反之——选编码格式前先查组件列表，别假设对称。

### 13.5 HDMI-in 与 CVBS 输入

车机常要接入外部视频源（机顶盒、游戏机、倒车雷达摄像头）：

- **HDMI-in**：作为 `EXTERNAL` 相机（`INFO_SUPPORTED_HARDWARE_LEVEL = EXTERNAL`），经 Camera2 打开，能力受限（见 5.5）。
- **CVBS（复合视频）**：模拟信号，需 vendor 转接芯片转成数字流再进 Camera HAL，常表现为一个特殊 cameraId。
- 两者都走 Camera2 的"相机"抽象，但 3A/分辨率控制很少，主要当"视频源 Surface"上屏。

### 13.6 多屏播放

车机多屏（中控 + 仪表 + 后排）：

- 播放画面要指定输出到哪个 `Display`（参考 12 篇 WMS 多屏）。
- 视频 Surface 绑定目标 Display 的 `SurfaceControl`/Surface。
- 注意音频路由（21 篇）：后排播放的声音走哪组扬声器、是否独立音区。

### 13.7 行车记录循环录像与存储写入寿命

行车记录 = 长时间、循环、持续写入。两个问题：

1. **存储写入寿命**：eMMC/SD 卡有擦写寿命，7×24 循环写会加速损耗。
   - 建议：写 App 私有目录（`getExternalFilesDir`，不受 Scoped Storage 限制，9.4 节）。
   - 建议：分段文件（如每 3 分钟一个 mp4）+ 固定数量循环删除最旧片段，而非单文件无限增长。
   - 建议：选耐写等级的存储（车规 SD/SSD）。
   - 交叉参考：`androidOthers` 存储篇（Scoped Storage、存储寿命、文件管理）。

2. **断电安全**：车机可能随时断电，循环录像要保证"当前段可恢复"。
   - 定期 `MediaMuxer.stop()` + 新段，避免最后一刻损坏整文件。
   - 用 `File` 原子替换而非原地覆盖。

`「Android 视角」`：行车记录循环录像的存储策略务必和 `androidOthers/存储` 篇一起看，Scoped Storage、私有目录、`MANAGE_EXTERNAL_STORAGE` 都直接影响实现。

---

## 14. 调试工具箱

每条命令带 `#` 注释，期望输出重点已标。

### 14.1 相机相关

```bash
# 列出相机设备与 HAL 状态（最常用）
adb shell dumpsys media.camera
# 重点看：CameraProvider 列表、Camera ID→Characteristics、当前打开的 device、stream 配置

# 相机服务是否活着 / 崩溃重启
adb shell service check media.camera
# 期望：Service media.camera: found

# 监听相机打开/关闭/错误（framework 标签）
adb logcat -s CameraManager CameraDevice CameraCaptureSession
# 或抓更底层
adb logcat -s CameraService CameraProviderManager

# HAL 层日志（HIDL/AIDL 实现，tag 因 vendor 而异，常含 CameraHal /_provider / device）
adb logcat | grep -i "camera.*hal\|provider\|requestthread"
```

### 14.2 编解码相关

```bash
# 编解码器组件状态、当前在用组件、是否硬解
adb shell dumpsys media.codec
# 重点看：Component 列表（c2.* 还是 OMX.*）、本次 session 用的组件、错误计数

# 当前播放/录制会话（MediaPlayer / MediaRecorder / MediaCodec 内部状态）
adb shell dumpsys media.player
adb shell dumpsys media.extractor

# 列出设备支持的编解码器（等同 MediaCodecList 的命令行版）
adb shell mediainfo        # 部分 ROM 有；或用：
adb shell pm list features | grep -i codec
```

### 14.3 图形/上屏衔接

```bash
# 看图层、BufferQueue、哪块 buffer 在合成（衔接 12 篇）
adb shell dumpsys SurfaceFlinger
# 重点看：Layers 列表、各 Layer 的 buffer 队列、是否有有效 buffer（黑屏排查）

# 图形缓冲队列详细
adb shell dumpsys SurfaceFlinger --list        # 列出 layers
```

### 14.4 性能：Perfetto 看相机与 codec 轨

```bash
# 打开 perfetto 网页 UI 抓 trace，勾选 camera / codec2 / surfaceflinger 等 track
adb shell perfetto --txt -c - -o /data/memfd:/perfetto-trace \
  <<'CFG'
buffers: { size_kb: 65536 }
data_sources: { config { name: "android.camera" } }
data_sources: { config { name: "android.codec" } }
data_sources: { config { name: "android.surfaceflinger" } }
CFG
# 更简单：用 `adb shell perfetto` 配合 https://ui.perfetto.dev 录制，
# 看 camera capture 延迟、codec 处理时长、SurfaceFlinger 合成节奏
```

### 14.5 MediaCodec 日志开关

```bash
# 打开 CCodec / Codec2 详细日志
adb shell setprop log.tag.CCodec VERBOSE
adb shell setprop log.tag.Codec2Client VERBOSE
# 老 OMX 路径
adb shell setprop log.tag.OMXClient VERBOSE
adb shell setprop log.tag.ACodec VERBOSE

# 重启媒体相关进程让开关生效（必要时）
adb shell pkill -f media.codec
```

---

## 15. 常见问题排查表

| 现象 | 原因 | 章节 | 第一命令 |
|---|---|---|---|
| 预览黑屏/绿屏 | Surface 未就绪或 BufferQueue 无有效 buffer | 6.1 / 11 | `adb shell dumpsys SurfaceFlinger` |
| 预览有但卡顿掉帧 | HAL in-flight 满/ISP 带宽不足/降分辨率 | 2.2 / 4.3 | `adb shell dumpsys media.camera` |
| 相机打不开（busy） | 被其他相机/DMS 占用、并发配额超限 | 13.2 / 4 | `adb logcat -s CameraService` |
| 相机打不开（权限） | 无 CAMERA 权限/AppOps 拒绝/后台限制 | 4.4 | `adb shell appops get <pkg> CAMERA` |
| 拍照后预览卡死 | ImageReader 的 Image 未 close，buffer 池耗尽 | 6.2 | `adb shell dumpsys media.camera`（看 buffer 计数） |
| 拍照慢/模糊 | 3A 未收敛就拍 / 曝光不足 | 5.2 | `adb logcat -s CaptureResult` |
| 录像卡顿 | 编码跟不上/磁盘写入慢/分辨率过高 | 7 / 13.7 | `adb shell dumpsys media.codec` |
| 录像音画不同步 | 音视频 PTS 基准不一致 | 9.2 / 12.3 | `adb shell dumpsys media.extractor` |
| 解码失败回退软解 | 硬解组件缺失/格式不支持 | 8.2 | `adb shell dumpsys media.codec`（看组件名） |
| 播放花屏/绿条 | stride 算错/关键帧丢失/SPS 缺失 | 5.3 / 7.4 | `adb logcat -s CCodec MediaCodec` |
| 倒车影像延迟高 | 走了编码路径/3A 收敛/多余拷贝 | 13.1 | `adb shell dumpsys media.camera` + perfetto |
| 环视某路无画面 | 多路并发配额/物理相机失效 | 13.2 | `adb shell dumpsys media.camera` |
| HDMI-in 无信号 | EXTERNAL 相机未枚举/源未接 | 13.5 | `adb shell dumpsys media.camera`（看 cameraId） |
| 硬解 H.265 失败 | vendor 无 `c2.*.hevc.decoder` | 8.2 / 13.4 | `adb shell dumpsys media.codec` |
| 录像写文件失败 | Scoped Storage 限制/无权限 | 9.4 | `adb shell appops get <pkg> MANAGE_EXTERNAL_STORAGE` |
| 截图/录屏黑屏 | 内容为 secure buffer（Widevine/安全图层） | 11.3 | `adb shell dumpsys SurfaceFlinger`（看 protected layer） |
| 多屏播放上错屏 | Display 指定错/窗口跑错 display | 13.6 / 12篇 | `adb shell dumpsys window displays` |
| 行车记录卡死/损坏 | 单文件无限增长/断电损坏 | 13.7 | 查存储写入 + `dumpsys media.recorder` |

---

## 16. 读源码的推荐路线

### 16.1 从 Camera2 API 到 CameraService 到 HAL

```text
frameworks/base/core/java/android/hardware/camera2/
  CameraManager.java
    └─ CameraManagerGlobal（Binder 连 ICameraService）
frameworks/base/services/core/java/com/android/server/camera/
  CameraService.java（system_server 侧）
    └─ 连接 CameraProviderManager
frameworks/av/services/camera/libcameraservice/
  CameraService.cpp
    └─ CameraProviderManager.cpp        // 管理 HAL provider
    └─ camera3/CameraDevice3.cpp        // HAL3 device 封装
      └─ 提交 processCaptureRequest 给 HAL
hardware/interfaces/camera/ 或 aidl/android/hardware/camera/
  ICameraProvider / ICameraDevice / ICameraDeviceSession（HAL 接口）
vendor/<oem>/camera/ 或 vendor/<oem>/proprietary/camera/
  HAL 实现（RequestThread、ISP 对接、驱动）
```

### 16.2 从 BufferQueue 看帧流转

```text
frameworks/native/libs/gui/
  BufferQueue.cpp / BufferQueueCore.cpp   // 生产者-消费者核心
  BufferItem.cpp
frameworks/native/libs/gui/
  Surface.cpp（生产者端，Camera HAL / MediaCodec 用）
  SurfaceComposerClient / Layer（消费者端，SurfaceFlinger 用）
frameworks/native/services/surfaceflinger/
  SurfaceFlinger.cpp / BufferLayer.cpp    // 合成上屏
```

### 16.3 从 Camera HAL 出帧到 App Surface

```text
vendor HAL：RequestThread 出帧
  └─ gralloc 分配 buffer（frameworks/native/libs/gralloc/ 或 vendor gralloc）
  └─ BufferQueue::queue（生产者 = HAL 侧 Surface）
App 侧 Surface::dequeue/acquire（消费者）
  └─ 预览：SurfaceFlinger；录像：MediaCodec 输入 Surface
```

### 16.4 从 MediaCodec 到 Codec2 到 HAL

```text
frameworks/base/media/java/android/media/
  MediaCodec.java
frameworks/av/media/libstagefright/
  MediaCodec.cpp（native 封装）
    ├─ CCodec.cpp                        // Codec2 客户端（Android 11+ 默认）
    │     └─ Codec2Client → framework/av/media/codec2/ → vendor 组件
    └─ (legacy) ACodec.cpp / OMXClient   // OMX 路径（Android 12+ 废弃）
frameworks/av/media/codec2/
  Codec2 框架、组件接口、ComponentStore
vendor/<oem>/media/codec2/ 或 hardware/<oem>/media/codec2/
  实际编解码组件（c2.<vendor>.*）
```

### 16.5 从文件到播放上屏（Media3 / ExoPlayer）

```text
frameworks/base/media/（MediaExtractor/MediaMuxer Java 层）
frameworks/av/media/libstagefright/
  MediaExtractor.cpp / MPEG4Extractor.cpp  // 解封装
  MediaWriter / MPEG4Writer.cpp            // 封装（MediaMuxer 底层）
androidx.media3（外部仓库，播放器）
  ExoPlayer / MediaSource / MediaCodecRenderer
    └─ 通过 MediaCodec 解码 → 输出 Surface → BufferQueue → SurfaceFlinger
```

### 16.6 音频衔接（只指路，不展开）

```text
frameworks/av/media/libaudioclient/  AudioRecord / AudioTrack
frameworks/av/services/audioflinger/ AudioFlinger
frameworks/av/services/audiopolicy/  AudioPolicy
# 详见 21_Audio机制详解 第 11 章源码路径
```

---

## 17. 关键源码路径速查

| 内容 | 路径 |
|---|---|
| Camera2 Java API | `frameworks/base/core/java/android/hardware/camera2/` |
| CameraManager | `frameworks/base/core/java/android/hardware/camera2/CameraManager.java` |
| CameraService（framework 侧） | `frameworks/av/services/camera/libcameraservice/CameraService.cpp` |
| CameraProviderManager | `frameworks/av/services/camera/libcameraservice/CameraProviderManager.cpp` |
| Camera HAL3 接口（HIDL） | `hardware/interfaces/camera/` |
| Camera HAL3 接口（AIDL） | `aidl/android/hardware/camera/` |
| camera metadata 定义 | `system/media/camera/docs/` + `camera_metadata_tags` |
| BufferQueue | `frameworks/native/libs/gui/BufferQueue.cpp` |
| Surface（生产者端） | `frameworks/native/libs/gui/Surface.cpp` |
| gralloc | `frameworks/native/libs/gralloc/` 或 vendor gralloc 实现 |
| MediaCodec Java | `frameworks/base/media/java/android/media/MediaCodec.java` |
| MediaCodec native | `frameworks/av/media/libstagefright/MediaCodec.cpp` |
| CCodec（Codec2 客户端） | `frameworks/av/media/libstagefright/CCodec.cpp` |
| Codec2 框架 | `frameworks/av/media/codec2/` |
| OMX 客户端（旧） | `frameworks/av/media/libstagefright/OMXClient.cpp` |
| MediaMuxer/MediaExtractor | `frameworks/av/media/libstagefright/`（`MPEG4Writer`/`MPEG4Extractor`） |
| Media3/ExoPlayer | `frameworks/support（androidx.media3）`/ 外部仓库 |
| SurfaceFlinger 衔接 | `frameworks/native/services/surfaceflinger/`（见 12 篇） |
| Audio 衔接 | `frameworks/av/services/audioflinger/`（见 21 篇） |

---

## 18. 一图总结

```text
                    ┌──────────── 采集端：相机 ────────────┐
                    │  Sensor → ISP(3A) → Camera HAL3      │
                    │       │ gralloc buffer 流转           │
                    │       ▼                               │
                    │  BufferQueue（生产者=HAL）            │
                    └───────────────┬───────────────────────┘
                                    │
            ┌───────────────────────┼───────────────────────┐
            ▼                       ▼                       ▼
     ① 预览 Surface          ② 录像 Surface           ③ 拍照 ImageReader
            │              (MediaCodec 输入)              │
            │                       │                     │
            │               MediaCodec 编码               │ acquire Image
            │                       │                     │
            │                       ▼                     ▼
            │                MediaMuxer 写文件       JPEG 存盘
            │             （音频见 21 篇一起 mux）   （注意 close buffer）
            ▼
     BufferQueue → SurfaceFlinger → 屏幕（上屏）
            ▲
            │  ④ 播放反向：文件 → MediaExtractor → MediaCodec 解码
            │     → 输出 Surface → BufferQueue → SurfaceFlinger → 屏幕
            └────────────────────────────────────────────────────

记忆链（一句话背下来）：
  相机产帧靠 HAL，流转靠 BufferQueue，上屏靠 SurfaceFlinger；
  录像编码靠 MediaCodec（零拷贝进输入 Surface），封装靠 MediaMuxer；
  播放解封装靠 Extractor、解码靠 MediaCodec、同步靠音频时钟（21 篇）；
  卡顿黑屏先问「这一帧卡在五次搬家哪一步」。
```

---

## 19. 关联阅读

- **`12_SurfaceFlinger 详解`**（同目录）：本文第 11 章衔接的图层合成、BufferQueue、Layer、安全图层、多屏全部在那里讲透。
- **`21_Audio机制详解-从AudioTrack到AudioFlinger和AudioPolicy`**（同目录）：本文第 12 章衔接的录音/播放/混音/焦点/路由，音频时钟与音画同步的权威来源。
- **`13_Input 机制详解`**（同目录）：相机触摸对焦、车机旋钮/方向盘按键触发拍照的输入链路。
- **`androidFrameworks/14_权限机制详解`**（同目录）：`CAMERA` 权限、`AppOps`、`MANAGE_EXTERNAL_STORAGE`（录像写文件）的权限判定。
- **`androidOthers/存储篇`**（跨领域）：Scoped Storage、私有目录、eMMC/SD 写入寿命、循环录像文件管理（本文 9.4、13.7 交叉引用）。
- **`androidOthers/蓝牙篇`**（跨领域）：蓝牙麦克风录像、A2DP 播放路由（本文 12.3 衔接）。
- **`androidApp/13 业务专题`**（应用层）：相机/媒体相关 App 业务落地（扫码、短视频、播放器封装）。

---

## 小结

- **相机与视频共用一条数据流水线**：Sensor→ISP→Camera HAL→BufferQueue→（上屏 / 编码 / 存图），理解"一帧五次搬家"就能定位大多数问题。
- **App 侧三件套**：Camera2（底层、车机必用）、CameraX（上层封装、普通 App 首选）、MediaCodec（编解码门面）。异步模式 API 21+，Codec2 是 Android 11+ 默认、OMX 在 12+ 废弃。
- **HAL 演进**：HAL1→HAL3（API 21）→HIDL（API 26）→AIDL Camera HAL（API 31）；Buffer 靠 gralloc handle 零拷贝流转。
- **三条输出路径差异巨大**：预览最低延迟、拍照要 close buffer、录像用输入 Surface 零拷贝进编码器。
- **录像/播放的音频与封装**：MediaMuxer 交错写轨、PTS 基准必须一致；音频时钟是同步主时钟（见 21 篇）。
- **车机专属**：倒车走预览不编码、多路并发受 HAL 配额约束、H.265 硬解看 vendor 组件、行车记录循环写私有目录省寿命。
- **排障第一原则**：先 `dumpsys media.camera` / `media.codec` / `SurfaceFlinger` 看是"卡在采集、编码还是上屏"，再对症下药。

如果只记一个核心模型：

> Camera HAL 产帧、BufferQueue 传帧、SurfaceFlinger 与 MediaCodec 消费帧；拍照/录像/播放不过是把这条流水线在不同节点分叉。
