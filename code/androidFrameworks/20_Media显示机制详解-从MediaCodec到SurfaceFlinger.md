# Media 显示机制详解：从 MediaCodec 到 SurfaceFlinger

> 视频、相机、车载影像这类问题会横跨 MediaCodec、Camera、Surface、BufferQueue、SurfaceFlinger、HWC 和 WMS。学完 SurfaceFlinger 后，Media 显示链路是很自然的下一步。

---

## 目录

1. [Media 显示链路是什么](#1media-显示链路是什么)
2. [视频播放到屏幕的流程](#2视频播放到屏幕的流程)
3. [相机预览到屏幕的流程](#3相机预览到屏幕的流程)
4. [SurfaceView 和 TextureView 选择](#4surfaceview-和-textureview-选择)
5. [核心模块](#5核心模块)
6. [音视频同步和掉帧](#6音视频同步和掉帧)
7. [DRM/protected content](#7drmprotected-content)
8. [常见问题与排查](#8常见问题与排查)
9. [第三方系统常见修改点](#9第三方系统常见修改点)
10. [源码路径速查](#10源码路径速查)

---

## 1. Media 显示链路是什么

普通 View 是 App 自己画 UI；视频/相机常常是解码器或 Camera 直接往 Surface 里写 buffer。

一句话：

> Media 显示链路是“解码器或相机生产 buffer，SurfaceFlinger 消费并合成上屏”。

---

## 2. 视频播放到屏幕的流程

```text
播放器
  └─ MediaExtractor 读数据
       └─ MediaCodec 解码
            └─ 输出到 Surface
                 └─ BufferQueue queueBuffer
                      └─ SurfaceFlinger Layer
                           └─ HWC/GPU 合成上屏
```

如果用 SurfaceView，视频通常是独立 Layer；如果用 TextureView，视频内容会作为纹理合成进 App 主窗口。

---

## 3. 相机预览到屏幕的流程

```text
Camera HAL
  └─ CameraService
       └─ App Camera API
            └─ 输出到 Surface
                 └─ BufferQueue
                      └─ SurfaceFlinger 合成显示
```

相机预览黑屏可能发生在 Camera HAL、CameraService、App Surface 生命周期、BufferQueue、SurfaceFlinger Layer、HWC 任一环节。

---

## 4. SurfaceView 和 TextureView 选择

| 方案 | 特点 |
|---|---|
| SurfaceView | 独立 Surface/Layer，性能好，适合视频/相机/游戏 |
| TextureView | 内容进入 View 树，动画/裁剪/透明更方便，但可能多 GPU 开销 |
| 普通 View | 适合 UI，不适合高频视频 buffer |

车载视频、倒车影像、相机预览通常优先考虑 SurfaceView 或原生 Surface 路径。

---

## 5. 核心模块

| 模块 | 作用 |
|---|---|
| `MediaCodec` | 编解码 |
| `MediaExtractor` | 封装格式解析 |
| `MediaPlayer` / ExoPlayer | 播放器上层 |
| `CameraService` | 相机系统服务 |
| `Surface` | buffer 输出目标 |
| `BufferQueue` | producer/consumer 队列 |
| `SurfaceFlinger` | Layer 合成 |
| HWC | 硬件 overlay 合成 |
| AudioFlinger/AudioPolicy | 音频输出与策略 |

---

## 6. 音视频同步和掉帧

视频显示需要考虑：

- 解码速度。
- buffer 生产速度。
- VSYNC 合成节奏。
- 音频时钟。
- SurfaceFlinger latch/present。
- HWC overlay 是否可用。

掉帧可能是解码慢，也可能是渲染、合成、显示硬件或系统负载问题。

---

## 7. DRM/protected content

受保护视频可能使用 secure/protected buffer：

- 截图或录屏显示黑。
- 只能走特定硬件路径。
- 不允许普通 CPU 访问像素。
- 多屏和投屏可能受限制。

这类问题要同时看 DRM、MediaCodec、SurfaceFlinger、HWC。

---

## 8. 常见问题与排查

### 视频黑屏

```bash
adb shell dumpsys SurfaceFlinger --list
adb shell dumpsys media.codec
adb logcat -b all | grep -i "MediaCodec\|Surface\|BufferQueue\|SurfaceFlinger"
```

看 Surface 是否存在、是否有 buffer、codec 是否输出、Layer 是否 hidden。

### 有声音没画面

说明解复用/音频可能正常，重点查视频 decoder、Surface、Layer、HWC。

### 相机预览黑屏

查 CameraService、Surface 生命周期、权限、buffer 是否输出。

---

## 9. 第三方系统常见修改点

- 车载倒车影像低延迟显示。
- 多路视频和多屏显示。
- HWC overlay 策略优化。
- protected content 截屏/投屏策略。
- 相机预览旋转、裁剪、缩放。
- 音视频同步和低延迟播放。

---

## 10. 源码路径速查

| 内容 | 路径 |
|---|---|
| MediaCodec framework | `frameworks/av/media/libstagefright/` |
| MediaPlayer | `frameworks/av/media/libmedia/` |
| CameraService | `frameworks/av/services/camera/libcameraservice/` |
| BufferQueue | `frameworks/native/libs/gui/BufferQueue*.cpp` |
| SurfaceFlinger | `frameworks/native/services/surfaceflinger/` |
| AudioFlinger | `frameworks/av/services/audioflinger/` |
| AudioPolicy | `frameworks/av/services/audiopolicy/` |
