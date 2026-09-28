# Audio 机制详解：从 AudioTrack 到 AudioFlinger 和 AudioPolicy

> 这是 Android 通用音频栈的主干文档。补齐 Media 模块里最容易被"会解码、会上屏"的人忽略的音频链路：声音从 App 写出，经过 `AudioTrack` → `AudioFlinger` 混音 → `AudioPolicy` 选设备策略 → `Audio HAL` 吐到硬件 DAC。录音则是反向。
>
> **分工边界（避免和姊妹篇重复）**：MediaCodec / 相机 / 视频解码归 [20_相机与媒体](./20_相机与媒体编解码详解-从CameraHAL到MediaCodec与上屏.md)；SystemUI 音量条 UI 归 [18_SystemUI](./18_SystemUI机制详解-从状态栏到锁屏与通知面板.md)；车机 `CarAudioService` 服务本体归 automotive 系列篇，本篇只在第 11 章指路、不重讲。本篇聚焦**通用音频栈**。

---

## 目录

- [1. 开场：音频要回答的三个问题](#1-开场音频要回答的三个问题)
- [2. 音频的三层架构：App API / Framework 服务 / HAL](#2-音频的三层架构app-api--framework-服务--hal)
- [3. AudioTrack：模式、缓冲、时间戳与格式](#3-audiotrack模式缓冲时间戳与格式)
- [4. AudioFlinger 内核：PlaybackThread 家族与 Track 状态机](#4-audioflinger-内核playbackthread-家族与-track-状态机)
- [5. 混音本体：重采样、混音、音量应用与 underrun](#5-混音本体重采样混音音量应用与-underrun)
- [6. AudioPolicy：策略判定三要素与设备选择](#6-audiopolicy策略判定三要素与设备选择)
- [7. 音量体系：stream volume 曲线与 setStreamVolume 链路](#7-音量体系stream-volume-曲线与-setstreamvolume-链路)
- [8. AudioFocus：焦点请求、丢失回调与"君子协定"](#8-audiofocus焦点请求丢失回调与君子协定)
- [9. AudioRecord 与采集：路径、隐私指示与 AEC/NS](#9-audiorecord-与采集路径隐私指示与-aecns)
- [10. HAL 层：接口演进与 audio_policy_configuration.xml](#10-hal-层接口演进与-audio_policy_configurationxml)
- [11. 车机音频专章：CarAudioService 与多音区](#11-车机音频专章caraudioservice-与多音区)
- [12. 低延迟、Offload 与 AAudio/Oboe 路径](#12-低延迟offload-与-aaudiooboe-路径)
- [13. 调试工具箱：dumpsys / tinymix / Perfetto](#13-调试工具箱dumpsys--tinymix--perfetto)
- [14. 常见问题排查表](#14-常见问题排查表)
- [15. 读源码路线（5 条）](#15-读源码路线5-条)
- [16. 版本演进对照（AIDL HAL / offload / mmap / AudioAttributes）](#16-版本演进对照aidl-hal--offload--mmap--audioattributes)
- [17. 源码路径速查表 + 一图总结 + 关联阅读](#17-源码路径速查表--一图总结--关联阅读)

---

## 1. 开场：音频要回答的三个问题

视频开发者容易把"音频"当成"又一个 buffer 上屏"——错了。声音是**连续实时流**，丢了就断、晚到就卡、错路就串台。读这套代码前，先记住音频栈永远在回答三个问题：

| 问题 | 谁来答 | 一句话 |
|---|---|---|
| **这段声音走哪条路？** | `AudioPolicy` | 扬声器 / 听筒 / 蓝牙 / USB / HDMI / 车机 bus，选哪个硬件出口 |
| **跟谁混在一起？** | `AudioFlinger` | 多个 App 的声音怎么叠、谁的音量多大、要不要重采样 |
| **被谁管（谁优先、能不能播）？** | `AudioService` + `AudioPolicy` + `AudioFocus` | 电话来了一律压你、导航播报你 duck、后台乱播被系统制裁 |

生活类比：**`AudioTrack` 是快递员把货（PCM 数据）交给分拣中心（`AudioFlinger`），分拣中心按调度单（`AudioPolicy`）决定走哪条干线，干线尽头是收货的喇叭/耳机（`HAL`→`DAC`）**。三个问题分别对应"货怎么进中心""怎么分拣""走哪条干线"。

### 一条 AudioTrack 播放的全景图

```text
App (MediaPlayer / ExoPlayer / 直接 AudioTrack)
  │  AudioTrack.write(pcm)                       ← 应用进程，PCM 数据写入共享内存
  ▼
AudioTrack (libaudioclient, 同进程)              ← 把数据经 Binder 推到 audioserver
  │  Binder: createTrack / write
  ▼
AudioFlinger  (audioserver 进程)                 ← 数据面：混音、重采样、音量
  │  PlaybackThread (MixerThread / OffloadThread / MmapThread ...)
  │    ├─ AudioMixer：把多路 Track 混成一路
  │    └─ 按周期把混好的 buffer 写下去
  ▼
Audio HAL (audio.aidl / audio.h2.0~7.0)         ← 厂商实现，hw device
  │  write to output stream
  ▼
Kernel ALSA / TinyALSA → DAC → Speaker/BT/USB/HDMI
```

反向的录音链路（第 9 章展开）：

```text
Microphone → HAL capture → AudioFlinger RecordThread → AudioRecord.read() → App
```

### 怎么读这篇

- 想**排障无声/卡顿**：直接跳第 13、14 章（dumpsys + 排查表）。
- 想**动 vendor 音频配置**：第 10 章 `audio_policy_configuration.xml` 是你会改得最多的文件。
- 想**搞车机多音区**：第 11 章指路 `CarAudioService` / `car_audio_configuration.xml`。
- 想**抠源码**：第 15、17 章给路线与路径。
- 本篇是"分层栈型"知识：**上层现象一定能回指下层原因**。看到 `underrun` 别只骂 App，先看 `AudioFlinger` 线程周期，再看 HAL 是不是阻塞。

---

## 2. 音频的三层架构：App API / Framework 服务 / HAL

音频栈从上到下分三层，依赖方向严格单向（App 不认识 HAL，HAL 不知道有谁在播）：

```text
┌──────────────────────────────────────────────────────────┐
│ ① App API 层（Java / Kotlin，应用进程）                    │
│   AudioTrack / AudioRecord / MediaPlayer / AAudio(Oboe)    │
│   语义：我要播/录，给我一个句柄，我往里写/读               │
├──────────────────────────────────────────────────────────┤
│ ② Framework 服务层（system_server + audioserver）          │
│   AudioService(AudioManager 后端)  ── 策略/焦点/音量 Java   │
│   AudioSystem      ── Java↔native 桥                       │
│   AudioFlinger     ── 数据面：混音/重采样/线程              │
│   AudioPolicyService / AudioPolicyManager ── 设备/策略选型  │
├──────────────────────────────────────────────────────────┤
│ ③ HAL 层（厂商实现，binder 到 audio HAL）                  │
│   legacy audio.h → HIDL(2.0~7.0) → AIDL(Android 12+)      │
│   语义：把混好的 PCM（或压缩流 offload）送到真实硬件        │
└──────────────────────────────────────────────────────────┘
```

### ① App API 层（各管一摊，别混用）

| API | 角色 | 何时用 |
|---|---|---|
| `AudioTrack` | 最底层播放接口，直接喂 PCM | 自己解码、游戏音效、实时合成 |
| `AudioRecord` | 最底层采集接口，直接读 PCM | 自己处理录音、语音识别喂数据 |
| `MediaPlayer` | 高层封装：自带解码 + 输出 | 播本地/网络文件，懒得管解码 |
| `MediaRecorder` | 高层封装：采集 + 编码 | 录音存文件 |
| `AAudio` / `Oboe` | 低延迟 C API（Oboe 是其 C++ 封装） | 游戏、乐器、实时输入，追求最小延迟 |
| `Media3` (ExoPlayer 继任) | 现代媒体播放栈 | 新项目统一播放器，底层仍落 `AudioTrack` |

一句带过：**ExoPlayer / Media3 解码完的音频，最终也是 `AudioTrack.write()` 出去的**；它和 `MediaPlayer` 的区别在"解码与编排"，不在"音频栈出口"。

### ② Framework 服务层

- **`AudioService`**（在 `system_server`）：App 通过 `AudioManager` 调它。管**音量数据库、AudioFocus 焦点栈、模式（ring/vibrate/silent）、设备状态广播**。它偏"控制面"。
- **`AudioSystem`**：Java 到 native 的静态桥，很多方法直接转 `AudioSystem::` native 调用（如 `setStreamVolume`、`getDevicesForStream`）。
- **`AudioFlinger`**（在 `audioserver`）：**数据面**。管 Track、PlaybackThread、混音、重采样、写 HAL。
- **`AudioPolicyService` / `AudioPolicyManager`**（在 `audioserver`）：**策略面**。决定"声音走哪、谁优先、音量怎么算"，并负责 `openOutput/openInput` 跟 HAL 建联。

「**Android 视角**」：`audioserver` 是独立进程（`/system/bin/audioserver`），`AudioFlinger` 和 `AudioPolicyService` 都是它的"服务线程"。它崩了会拉起重启，但正在播的声音会断——排障时先 `ps -A | grep audioserver` 看是不是刚重启过。

### ③ HAL 层（演进主线）

| 阶段 | 接口 | 起始版本 | 现状 |
|---|---|---|---|
| Legacy | `hardware/libhardware/include/hardware/audio.h`（`audio_hw_device_t`） | 早期 | 老设备/老 vendor 仍在用 |
| HIDL | `hardware/interfaces/audio/2.0` ~ `7.0` | Android 8 (Treble) | 长期主流，Android 12 前标配 |
| AIDL | `hardware/interfaces/audio/aidl/` | Android 12 引入，13/14 主推 | **新项目迁移目标，vendor 必须跟** |

第 10、16 章详述。结论先记住：**Android 13 起官方主推 AIDL audio HAL；旧 HIDL 7.0 仍兼容但逐步退场；legacy `audio.h` 基本只剩参考实现**。

---

## 3. AudioTrack：模式、缓冲、时间戳与格式

`AudioTrack` 是"应用侧播放句柄"。你创建一个 `AudioTrack`，往里 `write()` PCM，它负责把数据送到 `AudioFlinger` 的一条 `Track` 上。

### 3.1 MODE_STATIC vs MODE_STREAM

| 模式 | 语义 | 典型场景 |
|---|---|---|
| `MODE_STREAM` | 边写边播，数据持续从 App 流向 `AudioFlinger` | 音乐、通话、实时合成——绝大多数场景 |
| `MODE_STATIC` | 一次性把全部数据搬进共享内存，之后循环/播放，不再频繁 write | 短音效、按键音、游戏里反复播放的 sample |

`MODE_STATIC` 的好处是播放期间 App 不用反复 write、对抖动不敏感；代价是**必须把整段数据先准备好**，且数据量受共享内存上限约束，不适合长音频。

### 3.2 write 的阻塞语义与 buffer 大小

`write()` 不保证"一次写完"。它有"阻塞"和"非阻塞"两种行为（构造时的 `transferMode` / `AudioTrack` 的 `WRITE_BLOCKING` 标志）：

- **阻塞写**：`write()` 会等 `AudioFlinger` 内部 buffer 有空位才返回，返回值是实际写入字节数（≤你给的长度）。适合不想自己控 pacing 的播放。
- **非阻塞写**：buffer 满了立即返回已写入量（可能 < 请求量），App 需自行决定何时再写。低延迟场景常用，配合 `getAvailableWriteSize()` 之类判断。

**buffer 大小怎么算**——App 必须先用 `AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)` 拿到"最小安全缓冲"：

```kotlin
val minBuf = AudioTrack.getMinBufferSize(
    44100,                                   // 采样率 Hz
    AudioFormat.CHANNEL_OUT_STEREO,          // 声道布局
    AudioFormat.ENCODING_PCM_16BIT           // 编码格式
)
// 实际构造时通常乘 2~N 倍，避免 underrun：
val bufferSize = minBuf * 2
val track = AudioTrack.Builder()
    .setAudioAttributes(AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA).build())
    .setAudioFormat(AudioFormat.Builder()
        .setSampleRate(44100)
        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
    .setBufferSizeInBytes(bufferSize)
    .setTransferMode(AudioTrack.MODE_STREAM)
    .build()
track.play()                                 // 进入 STARTING→ACTIVE
track.write(pcm, 0, pcm.size)                // 阻塞写，返回写入字节数
```

「**Android 视角**」：`getMinBufferSize` 内部会结合 `AudioTrack` 的"帧周期"和硬件延迟算出一个下限；乘 2 是经验值——太小容易 underrun（"没米下锅"），太大引入播放延迟（尤其低延迟场景要反着压小）。

### 3.3 playback position 与时间戳

- `getPlaybackHeadPosition()`：返回**已播放帧数**（软件侧位置），用来算"播到第几秒"，但这是"已经交给混音的位置"，不是"喇叭真正发出的位置"。
- `getTimestamp(AudioTimestamp)`：返回**硬件呈现时间线**——`framePosition`（某个时刻硬件已呈现到的帧）+ `nanoTime`（该帧被呈现的单调时钟时刻）。这是做音视频**对齐（AV sync）**的关键：视频侧用这个时间戳把画面卡到对应音频帧。

```kotlin
val ts = AudioTimestamp()
if (track.getTimestamp(ts) == AudioTrack.SUCCESS) {
    // ts.framePosition: 硬件此刻已播到的帧
    // ts.nanoTime:     该帧的呈现时间(CLOCK_MONOTONIC)
    val presentedSec = ts.framePosition.toDouble() / sampleRate
}
```

### 3.4 ENCODING / 采样率 / 声道布局（AudioFormat）

| 概念 | 取值示例 | 说明 |
|---|---|---|
| 编码 `ENCODING_*` | `PCM_16BIT`（默认）、`PCM_8BIT`、`PCM_FLOAT`、`ENCODING_AC3`/`E_AC3`（透传） | 浮点路径是高质量混音的趋势；压缩格式通常走 offload/透传而非软件解码 |
| 采样率 | `8000`/`16000`/`44100`/`48000` | 48k 是 Android 音频内部主流；不匹配会触发重采样（见第 5 章） |
| 声道布局 `CHANNEL_OUT_*` | `MONO`、`STEREO`、`5POINT1`、`7POINT1` | 注意是"输出布局"，和输入的声道数未必一致 |

「**Android 视角**」：`AudioFormat` 在 API 21+ 用 Builder 构造，老代码里还能看到直接传 `CHANNEL_CONFIGURATION_*` 常量——新代码统一用 `CHANNEL_OUT_*` + `AudioAttributes`。

---

## 4. AudioFlinger 内核：PlaybackThread 家族与 Track 状态机

`AudioFlinger` 跑在 `audioserver`，是音频的"数据面大脑"。它不为每个 App 起一个线程，而是**按"输出硬件路径"起 PlaybackThread**，多个 App 的 Track 挂在同一线程下被混音。

### 4.1 PlaybackThread 家族（分工是重点）

| 线程类型 | 何时用 | 关键特征 |
|---|---|---|
| `MixerThread` | 绝大多数音乐/媒体/提示音 | 软件混音：多路 PCM 进来，重采样+混音成一路再写 HAL |
| `DirectThread` | 不能软件混的音（HDMI 透传 AC3/EAC3、高码率、要求独占） | 单路直通，**不混音**，独占该 output |
| `OffloadThread` | 压缩音频 offload 到 DSP（见第 12 章） | 把压缩帧直接交给 DSP 解码播放，省 CPU/功耗 |
| `MmapThread` | 低延迟 AAudio/Oboe 路径（见第 12 章） | mmap 共享内存 + NO_IRQ，绕过常规 Binder 写路径 |
| `DuplicatingThread` | 同一路要同时送到多个 output（如 扬声器+蓝牙） | 把混音结果"复制"到多个下游线程 |
| `RecordThread` | 录音（对应 AudioRecord） | 从 HAL capture，分发给各 RecordTrack |

判定逻辑（简化）：`AudioPolicy` 选好 output 后，`AudioFlinger` 看该 track 的 flags/格式/采样率，决定挂到哪类线程。例如"需要 offload 且 output 支持"→ `OffloadThread`；"低延迟 mmap 且 HAL 支持"→ `MmapThread`；其余常规 PCM → `MixerThread`。

### 4.2 Track 的创建与状态机

App 调 `AudioTrack` 时，native 侧通过 Binder 让 `AudioFlinger` 调 `createTrack()`，返回一个 `Track` 句柄挂在对应 `PlaybackThread` 下。`Track`（更准确地是 `TrackBase`）有一套状态机：

```text
IDLE
  └─ start() → STARTING_1 → STARTING_2 → ACTIVE     (正在喂数据、被混音)
ACTIVE
  ├─ stop()  → STOPPING_1 → STOPPING_2 → STOPPED     (停播，保留 buffer)
  ├─ pause() → PAUSING → PAUSED                      (暂停，保留位置)
  └─ flush() → FLUSHING → FLUSHED                    (清空已缓冲数据)
STOPPED / PAUSED / FLUSHED
  └─ 再次 start() → 回到 ACTIVE
```

实战含义：
- **`FLUSHED` 之后**位置归零，恢复播放要从头或重新 seek，否则会"接不上"。
- 状态切换不是瞬间完成的——`STARTING_*` / `STOPPING_*` 是过渡态，线程下一轮循环才真正生效；排障时 dump 里看到过渡态卡住，往往是线程被阻塞。
- 一个 Track 被 `AudioFlinger` 判定"长时间没数据"会被标记为 inactive，但不一定立即销毁。

### 4.3 FastMixer 与普通混音的两级

`MixerThread` 内部其实有**两级混音**：

1. **普通混音**（在 `AudioFlinger` 的工作线程里跑）：把绝大多数"非 fast"的 Track 混成主 buffer。
2. **FastMixer**（独立高优先级线程 `AudioFlinger::FastMixer`）：专门服务"fast track"（低延迟、格式/采样率必须和 output 严格匹配的那批）。它用单独的、被提优先级的线程 + 锁，把 fast track 混好后塞进主输出，避免被普通混音的抖动拖死。

为什么分两级？普通混音要处理重采样、各种格式、可能阻塞，抖动大；fast track 要求确定性延迟，不能被普通任务拖累，所以单独拎出来跑在 `SCHED_FIFO` 高优先级上。

### 4.4 effect 的一句带过

音效（`AudioEffect`：Equalizer、BassBoost、Virtualizer、AEC/NS 等）在 `AudioFlinger` 里以 effect chain 挂在 output/track 上，在混音后（或录音前）处理。**本篇不展开 effect 实现**，只记住：effect 既可能跑在 `AudioFlinger`（软件）也可能 offload 到 DSP，挂在"某条 output 的某个 stream/session"上，dump 里能看到 effect chain 列表。

---

## 5. 混音本体：重采样、混音、音量应用与 underrun

这一章回答"多路声音到底在哪一步被叠到一起、音量在哪乘、为什么会卡"。全部发生在 `AudioFlinger` 的 `AudioMixer`（及其下游）。

### 5.1 重采样 / 混音 / 音量应用发生在哪

```text
每路 Track (可能 44.1k/48k、单/双/多声道、PCM16/FLOAT)
   │  ① 重采样(Resampler)：把各路统一成 output 的采样率/声道
   ▼
AudioMixer 累加缓冲
   │  ② 混音(Mix)：逐样本相加（带饱和/溢出保护）
   │  ③ 音量应用(Volume)：乘以该 track 的 gain（= stream 音量 × 相对增益）
   ▼
混好的一路 PCM → (可选 effect chain) → 写 HAL
   │  ④ device 音量：通常由 HAL/硬件在 device 端再乘（见第 7 章）
   ▼
硬件 DAC
```

注意**两个音量施加点**的分工：
- **track gain（软件，在 mixer 里）**：承载 stream 音量、相对音量、ducking。Android 默认把 stream volume 折算成 track gain 在软件里乘掉。
- **device volume（硬件，HAL 端）**：部分设备/蓝牙走"绝对音量"，由硬件在最后一级乘。两者错位会导致"明明调了音量却没反应"（第 7、14 章）。

### 5.2 浮点 vs 定点混音

`AudioMixer` 早期用定点（Q0.15 / Q4.27 之类）累加，现代主流路径改用**浮点（float）**混音：

| 实现 | 优点 | 缺点 |
|---|---|---|
| 定点（fixed-point） | 省算力、确定性强、老设备友好 | 动态范围/精度受限，叠加多路易削波 |
| 浮点（float） | 精度高、多路叠加不易失真、现代默认 | 需要 FPU/NEON，老低端设备吃力 |

`AudioMixer` 通过模板/配置选择路径（如 `kUseFloat` 一类开关），vendor 在性能受限平台上可能回退定点。排障"多路叠加爆音"时，知道走的是哪条路径有助于判断是算法还是硬件 clipping。

### 5.3 一个周期（period）的时序与 underrun

`MixerThread` 不是"有数据才动"，而是**按固定周期被唤醒**：每次醒来，它去拉取各 Track 当前 buffer，混音，写一段（通常 = 半个 buffer 大小的"帧数"）给 HAL，然后等下一个周期。

```text
T0  线程醒来 → 拉 Track A/B/C 数据 → 混音 → 写 N 帧给 HAL → 休眠
T1  线程醒来 → 再拉 → 再混 → 再写 N 帧 ...
        │
        └─ 若某 Track 在 T1 时还没 write 够 N 帧 → 该路"欠供"
              → mixer 用静音/上一次数据补 → 听感：咔哒/断片 = UNDERRUN
```

**underrun（欠载）= "AudioFlinger 没米下锅"**。最典型的 debug 信号：
- `dumpsys media.audio_flinger` 里该 Track / 线程的 `underrun` 计数持续涨；
- logcat 里 `underrun` / `AudioTrack: ... buffer` 警告。
- 根因几乎都在"上游供数据慢"：App write 太慢、buffer 太小、CPU 被抢、重采样吃力、HAL 写阻塞。

反方向还有 **overrun（录音侧）**：HAL 采太快、App 读太慢，capture buffer 溢出丢帧。

---

## 6. AudioPolicy：策略判定三要素与设备选择

`AudioPolicyService`（数据面之上是它的"策略面"）只干一件事：**给定"一段声音想怎么用"，算出"它该走哪个设备、用什么音量组、能不能和别人并发"**。它跑在 `audioserver`，但和应用侧 `AudioService` 密切协作（焦点/音量由 Java 侧牵头，设备选型由它落地）。

### 6.1 策略判定三要素：stream → usage → attributes

历史上 Android 用 **stream type**（如 `STREAM_MUSIC`、`STREAM_RING`）表达"声音类别"。这是个粗粒度、容易误用的老体系——例如"导航"和"音乐"都是 `STREAM_MUSIC`，但策略诉求完全不同。

现代演进到 **`AudioAttributes`**，用三个字段精确描述一段声音的语义：

| 字段 | 含义 | 示例 |
|---|---|---|
| `usage` | 这段声音"用来干嘛" | `USAGE_MEDIA` / `USAGE_ALARM` / `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` / `USAGE_VOICE_COMMUNICATION` |
| `content_type` | 内容性质 | `CONTENT_TYPE_MUSIC` / `CONTENT_TYPE_SPEECH` / `CONTENT_TYPE_SONIFICATION` |
| `flags` | 附加提示 | `FLAG_AUDIBILITY_ENFORCED`、`FLAG_HW_AV_SYNC` 等 |

「**Android 视角**」：`AudioAttributes` 从 API 21 引入；**API 26 起 `requestAudioFocus` 必须用 `AudioFocusRequest` 携带 `AudioAttributes`**，老的纯 stream 焦点请求被标记为过时。新代码一律用 attributes，stream type 只在 volume 表/老兼容层出现。

### 6.2 openOutput 与设备选择

当一段声音需要播放、`AudioPolicy` 选定策略后，真正"打开硬件出口"靠 `openOutput()`（录音侧是 `openInput()`）：

```text
AudioPolicyManager::getDeviceForStrategy()   ← 策略→设备
   └─ EngineBase::getDeviceForStrategy()     ← 具体判定（见 6.3）
        └─ 得到 device 类型 (SPEAKER / WIRED_HEADPHONE / BT_A2DP ...)
AudioFlinger::openOutput()                    ← 据此向 HAL 要一个 output stream
   └─ HAL: open_output_stream()              ← 厂商实现，返回 hw stream
```

设备选择还受**已连接设备状态**驱动：耳机插入、蓝牙连上、USB 音频、HDMI 接管、车机音区切换，都会让 `AudioPolicy` 重新跑一遍 `getDeviceForStrategy`，再把 `AudioFlinger` 的 active track 迁到新 output（或触发 duplicating）。

### 6.3 strategy / device 判定的入口（Engine）

`AudioPolicyManager` 把"策略→设备"的核心判定委托给 **`EngineBase` / `AudioPolicyEngine`**（不同版本类名略有差异，统称 Engine）。它的输入是"strategy / product strategy"，输出是 device。

- 旧体系：`getStrategyForStream(stream)` → `getDeviceForStrategy(strategy)`，靠硬编码规则 + `audio_policy.conf` 里的 device。
- 新体系（AIDL / `VolumeGroup` + `ProductStrategy`）：设备与音量组解耦，`car_audio_configuration.xml` / `audio_policy_configuration.xml` 里声明 `productStrategies` 和 `volumeGroups`，Engine 查表判定。

### 6.4 优先级与独占：通话 vs 音乐

`AudioPolicy` 的核心冲突仲裁：

| 冲突场景 | 裁决 |
|---|---|
| 通话进行中 | `VOICE_CALL` 几乎总是独占，`STREAM_MUSIC` 被 mute 或 duck 到极低 |
| 导航播报 vs 音乐 | 导航（transient 焦点）让音乐 duck，而非停 |
| 闹钟 vs 媒体 | 闹钟通常强制可闻，不能被静音策略压没 |
| `EXCLUSIVE` 焦点（录音/语音识别） | 要求其他媒体暂停，独占输入/输出 |

一句话记忆：**`AudioPolicy` 是"调度单"，决定干线；但它不亲自搬货，搬货的是 `AudioFlinger`，而"谁有资格搬"由 `AudioFocus` 先裁定（第 8 章）**。

---

## 7. 音量体系：stream volume 曲线与 setStreamVolume 链路

Android 的音量不是一个全局数字，而是一张**按"音量组/stream × 设备"查表的曲线**。理解它要先分清"音量存哪、音量在哪乘"。

### 7.1 stream volume 表的存储

音量曲线定义在系统配置文件里（不同版本文件名略有差异，统称**音量曲线配置文件**），典型如 `audio_policy_volumes.xml` 与 `volume_groups.xml`，打包到 `/system/etc/` 或 vendor 分区。它长这样（示意）：

```xml
<volumeGroup>
    <group> music </group>            <!-- 音量组：一个旋钮控一组 stream/usage -->
    <device> speaker </device>
    <point> 0,0 </point>             <!-- index 0 → 增益 -∞ (静音) -->
    <point> 25, -2400 </point>       <!-- index 25 → -24.00 dB -->
    <point> 100, 0 </point>          <!-- index 100 → 0 dB (最大) -->
</volumeGroup>
```

要点：
- 音量是**离散 index（如 0~25 或 0~100）**，曲线把 index 映射到 dB 增益（不是整数百分比）。
- 同一音量组在不同**设备**上可以有不同曲线（扬声器 vs 耳机的最大响度不同，这是保护听力的设计）。
- `VolumeGroup` 把"音乐/导航/系统"等 usage 归类到一个旋钮——你在 SystemUI 拉的那条"媒体音量"实际控的是一个 `VolumeGroup`（见 [18_SystemUI](./18_SystemUI机制详解-从状态栏到锁屏与通知面板.md)）。

### 7.2 曲线应用位置：track gain vs device volume

回到第 5 章那张图，音量有两级施加点：

| 施加点 | 谁算 | 承载什么 |
|---|---|---|
| **track gain（软件 mixer）** | `AudioFlinger` / `AudioMixer` | stream/usage 音量、相对增益、ducking |
| **device volume（硬件 HAL）** | 硬件或 HAL | "绝对音量"场景（尤其蓝牙 AVRCP），由设备端最终乘 |

默认情况下 Android 把 stream 音量折算进 track gain，在软件混音阶段乘掉；**蓝牙绝对音量（AVRCP absolute volume）** 时改成：手机只发"音量 index"给蓝牙设备，由耳机/车机自己乘——这样 SystemUI 拉音量条，实际指令是"通知远端调增益"，本地不削弱 PCM。一句话带过：**绝对音量的归一化靠 AVRCP 协议把 index 映射到设备端增益**。

### 7.3 setStreamVolume 的链路

```text
App: audioManager.setStreamVolume(STREAM_MUSIC, idx, flags)
  ▼
AudioService.setStreamVolume()                 ← system_server，查 VolumeGroup、存数据库
  ▼
AudioSystem.setStreamVolume()                  ← Java→native 桥
  ▼
AudioPolicyManager: setStreamVolume / 设置 VolumeGroup 增益
  ▼
AudioFlinger: 给该组下所有 active Track 的 track gain 重新计算
  ▼
下一次混音周期，新的增益生效（听感上音量变了）
```

实战坑：调了音量"没反应"，先确认你调的 **stream/usage 是不是当前 active 的那组**（比如你在调 `STREAM_MUSIC` 但正在播的是 `USAGE_ASSISTANCE_NAVIGATION` 归到另一组），再确认是不是蓝牙绝对音量场景（本地 PCM 本就不衰减）。详见第 14 章。

---

## 8. AudioFocus：焦点请求、丢失回调与"君子协定"

`AudioFocus` 解决的是"多 App 抢声音时，谁播、谁停、谁压低"——它**不涉及数据搬运**，只管"资格"。应用侧入口是 `AudioManager`，落地在 `system_server` 的 `AudioService`。

### 8.1 焦点请求四类与 ducking

| 请求类型 | 典型场景 | 对其他 App 的影响 |
|---|---|---|
| `AUDIOFOCUS_GAIN` | 音乐、长视频、播客 | 长占，其他媒体通常**暂停** |
| `AUDIOFOCUS_GAIN_TRANSIENT` | 语音助手、短提示 | 短暂占用，结束应 `abandon` |
| `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` | 导航播报、短音效 | 别人可**降低音量继续播**（duck） |
| `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` | 录音、语音识别、通话前处理 | 希望**独占**，别人暂停/停止 |

API 26+ 用 `AudioFocusRequest` 携带 `AudioAttributes`：

```kotlin
val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
    .setAudioAttributes(AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).build())
    .setOnAudioFocusChangeListener { focus ->
        when (focus) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
            AudioManager.AUDIOFOCUS_GAIN -> resumeIfWasPaused()
            AudioManager.AUDIOFOCUS_LOSS -> stopAndAbandon()
        }
    }.build()
audioManager.requestAudioFocus(req)
```

### 8.2 owner 记录与丢失回调

`AudioService` 内部维护一个**焦点栈 / owner 记录**：当前谁持有焦点、它的 usage、它抢了谁。`requestAudioFocus` 时，系统对比新请求与现有 owner 的优先级/类型，决定：
- 直接授权（新请求优先级低，排队）；
- 抢断现有 owner，向其发送 **`AUDIOFOCUS_LOSS` / `LOSS_TRANSIENT` / `LOSS_TRANSIENT_CAN_DUCK`** 回调。

| 回调 | 含义 | App 常见处理 |
|---|---|---|
| `AUDIOFOCUS_GAIN` | 重新拿到 | 恢复播放/原音量 |
| `AUDIOFOCUS_LOSS` | 长时间失去 | **停止并释放资源** |
| `AUDIOFOCUS_LOSS_TRANSIENT` | 暂时失去 | 暂停，等 `GAIN` 恢复 |
| `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` | 可压低继续播 | 降音量（音乐适用；有声书更宜暂停） |

### 8.3 焦点与多用户 / 多屏

焦点默认是**单用户、前台为主**的。多用户（secondary user）、多屏（[23_多屏](./23_多屏与多窗口机制详解-从DisplayManager到CarOccupantZone.md) 系列）下，焦点需要按"用户/显示"隔离，否则后台用户的导航会抢前台用户的音乐。`CarAudioService` 把焦点进一步按**音区（zone）** 隔离——见第 11 章。

### 8.4 "焦点只是一种君子协定"的实战含义

**焦点不是强制仲裁，是约定。** 系统只负责"发通知"，不强制你停播。坑：
- 不请求焦点就播 → 会和别人叠在一起；
- 收到 `LOSS` 还播 → 破坏体验，可能被厂商/车机策略**强制压制**（直接 mute 你的 track）；
- 收到 `CAN_DUCK` 不处理 → 导航/语音助手体验崩；
- 临时音频播完不 `abandonAudioFocus` → 别人无法恢复；
- 播放器要区分"用户主动暂停"和"焦点丢失暂停"，否则重新拿焦点会误自动播放。

排障焦点：`dumpsys audio` 看 focus owner / stack / 最近 request-abandon（第 13 章）。

---

## 9. AudioRecord 与采集：路径、隐私指示与 AEC/NS

录音是播放的镜像：`AudioFlinger` 用 `RecordThread` 从 HAL 拉采集数据，分发给各 `RecordTrack`，App 侧 `AudioRecord.read()` 取走 PCM。

### 9.1 采集路径

```text
Microphone → Audio HAL capture (input stream)
   ▼
AudioFlinger RecordThread            ← 按周期从 HAL 拉一帧
   ▼
RecordTrack (挂在 RecordThread 下)    ← 每路录音一个
   ▼
AudioRecord.read(pcm)                ← App 进程取走 PCM
```

`AudioRecord` 的构造同样要 `getMinBufferSize()`，同样有阻塞/非阻塞读。输入源（`MediaRecorder.AudioSource`）决定路由与处理链：

| 输入源 | 典型处理 |
|---|---|
| `MIC` | 默认，走普通麦克风链 |
| `VOICE_RECOGNITION` | 偏语音识别：常开 AEC/AGC/NS，采样率适配识别引擎 |
| `VOICE_COMMUNICATION` | 通话上行：强 AEC/NS，双向低延迟 |
| `CAMCORDER` | 录像伴音：和摄像帧对齐 |

### 9.2 隐私指示（mic 归属与 PrivacyIndicator）

从 Android 12 起，App 使用麦克风会在状态栏亮 **隐私指示器（PrivacyIndicator）**，并在 `AppOps` 记录 `RECORD_AUDIO` 的使用。这条和 [18_SystemUI](./18_SystemUI机制详解-从状态栏到锁屏与通知面板.md) 的隐私芯片/指示器强相关——本篇只指路：录音"没数据或权限被限"时，先查 `appops` 与 `AppOpsManager`（也见 [14_权限](./14_权限机制详解-从Permission到AppOps.md) 一类基础篇），再查音频路由。

### 9.3 AEC / NS 的一句带过

回声消除（AEC）、降噪（NS）、自动增益（AGC）通常挂在录音的 **预处理 effect chain** 上，既可软件（`AudioFlinger` 里跑）也可 offload 到 DSP（通话场景常见）。它们的开启与否由输入源 + 设备能力决定；排障"通话有回声/录音有底噪"时，先看对应输入源的预处理链有没有挂上。

---

## 10. HAL 层：接口演进与 audio_policy_configuration.xml

HAL 是**厂商定制的主战场**。上面所有策略最终都要落到 HAL 的 `open_output_stream` / `open_input_stream` / `write` / `read`。

### 10.1 接口演进：HIDL → AIDL

| 接口 | 路径 | 起始 | 说明 |
|---|---|---|---|
| Legacy | `hardware/libhardware/include/hardware/audio.h` | 早期 | `audio_hw_device_t` + `audio_stream_out_t`，参考实现在 `hardware/libhardware_legacy` |
| HIDL | `hardware/interfaces/audio/2.0` … `7.0` | Android 8 (Treble) | binderized HAL，长期主流 |
| **AIDL** | `hardware/interfaces/audio/aidl/` | Android 12 引入，13/14 主推 | **新设备必须跟**，统一到 AIDL 框架，便于 mainline 更新 |

「**Android 视角**」：Android 12 首次提供 audio AIDL HAL；**Android 13/14 起官方明确主推 AIDL，HIDL 7.0 作为兼容过渡**。vendor 的 `audio.primary.<soc>` 服务要从 HIDL 迁 AIDL，否则拿不到新特性（如更细的设备路由、 Car 多 bus）。迁移时数据结构（`DeviceAddress`、`AudioPort`、metadata）都换成 AIDL 定义。

### 10.2 audio_policy_configuration.xml 逐段（vendor 最常改的文件）

这份 XML 声明"本设备有哪些音频模块、哪些端口、怎么连"。结构（示意，字段名以实际版本为准）：

```xml
<audioPolicyConfiguration>
  <modules>
    <module name="primary" halVersion="3.0">
      <!-- 1) mixPort：软件侧的"流端点"，声明它能出的格式/采样率 -->
      <mixPorts>
        <mixPort name="primary output" role="source"
                 flags="AUDIO_OUTPUT_FLAG_PRIMARY">
          <profile name="" format="AUDIO_FORMAT_PCM_16_BIT"
                   samplingRates="48000" channelMasks="AUDIO_CHANNEL_OUT_STEREO"/>
        </mixPort>
        <mixPort name="compressed_offload" role="source"
                 flags="AUDIO_OUTPUT_FLAG_COMPRESS_OFFLOAD">
          <profile format="AUDIO_FORMAT_AAC" samplingRates="44100-48000"/>
        </mixPort>
      </mixPorts>

      <!-- 2) devicePort：硬件端点，type 决定它是扬声器/耳机/蓝牙 bus -->
      <devicePorts>
        <devicePort tagName="Speaker" type="AUDIO_DEVICE_OUT_SPEAKER"
                    role="sink"/>
        <devicePort tagName="WiredHeadset" type="AUDIO_DEVICE_OUT_WIRED_HEADSET"
                    role="sink"/>
        <devicePort tagName="Bus0" type="AUDIO_DEVICE_OUT_BUS"
                    role="sink" address="bus0_media"/>
      </devicePorts>

      <!-- 3) route：把 mixPort 连到 devicePort（谁流向谁） -->
      <routes>
        <route type="mix" sink="Speaker" sources="primary output"/>
        <route type="mix" sink="WiredHeadset" sources="primary output"/>
        <route type="mix" sink="Bus0" sources="compressed_offload"/>
      </routes>
    </module>
  </modules>
</audioPolicyConfiguration>
```

逐段含义：

| 段 | 作用 | vendor 改它干嘛 |
|---|---|---|
| `module` | 一个 HAL 实例（primary / usb / bluetooth / r_submix） | 加新 HAL（如独立 DSP 模块） |
| `mixPort` | 软件流端点 + 支持的 `profile`（格式/采样率/声道） | 声明支持 offload / mmap / 某些格式 |
| `devicePort` | 真实硬件端口，`type` 决定路由语义；`BUS` 类型用于车机 | 加车机 bus、改名设备 |
| `route` | mixPort→devicePort 的连接关系 | 决定某路混音结果流向哪个硬件 |

### 10.3 primary / offload / mmap 三类 port 的连接关系

- **primary output**：绝大多数声音的归宿，接 `MixerThread`，连到 Speaker/耳机等默认设备。
- **offload**：`COMPRESS_OFFLOAD` 标志的 mixPort，连到能解码压缩流的 DSP port；只有标了这标志，`AudioFlinger` 才会建 `OffloadThread`（第 12 章）。
- **mmap**：低延迟路径对应的 port（通常 primary 里带 `MMAP`/`NO_IRQ` 能力），连 `MmapThread`。
- 一份配置里可以有多个 module（USB 音频一个 module、蓝牙一个 module），`AudioPolicy` 跨 module 选设备时按 device type 路由。

**最常见的 vendor 报错**：XML 里 `route` 引用的 `mixPort`/`devicePort` 名字拼错、或 `profile` 声明的采样率 HAL 实际不支持 → HAL 起不来 / `AudioFlinger` 初始化失败，整机无声。语法检查见第 14 章。

---

## 11. 车机音频专章：CarAudioService 与多音区

车机是 Android 音频最复杂的落地场景之一。**本篇不重讲 `CarAudioService` 实现**（那归 automotive 系列篇），只讲它和通用音频栈的接口、以及你需要知道的映射关系。

### 11.1 CarAudioService 与 car_audio_configuration.xml

AAOS（Android Automotive）用 `CarAudioService`（在 `system_server` 的 car 服务里）接管音频路由，配置文件是 `car_audio_configuration.xml`。它把"音频区域（zone）""音量组""硬件 bus"三者绑定：

```xml
<carAudioConfiguration>
  <zones>
    <zone name="primary_zone" audioZoneId="0">
      <volumeGroups>
        <volumeGroup>
          <device> bus0_media </device>     <!-- 该音量组对应哪个 HAL bus -->
          <device> bus1_navigation </device>
        </volumeGroup>
      </volumeGroups>
      <zoneConfigs> ... </zoneConfigs>
    </zone>
    <zone name="rear_zone" audioZoneId="1">
      <volumeGroups> ... </volumeGroups>      <!-- 后排独立音量/路由 -->
    </zone>
  </zones>
</carAudioConfiguration>
```

映射关系要点：

| 概念 | 含义 | 对应通用栈的什么 |
|---|---|---|
| **zone（音区）** | 主驾 / 后排等独立播放域 | 一个 zone ≈ 一组独立的 `VolumeGroup` + 默认设备 |
| **volume group** | 同一条旋钮控的 usage 集合 | 同通用栈 `VolumeGroup` |
| **bus** | 连到功放/ DSP 的物理/逻辑通道 | 对应 HAL 的 `devicePort type=BUS`（第 10 章） |

也就是说：**`car_audio_configuration.xml` 里的 `busX_xxx` 必须和 `audio_policy_configuration.xml` 里 `type=AUDIO_DEVICE_OUT_BUS` 的 `devicePort`（tagName/address）对得上**，否则 zone 路由不到硬件。

### 11.2 多 zone（主驾 / 后排独立播放）

多音区让"主驾听导航、后排看片"互不干扰：`CarAudioService` 给每个 zone 一套 `AudioAttributes→device` 的路由表，zone 之间焦点、音量、路由**彼此隔离**。通用栈里一次 `getDeviceForStrategy` 在车机侧被升级成"先定 zone，再定该 zone 内的 device"。

### 11.3 音频焦点在车机的扩展（CarAudioFocus）

`CarAudioFocus` 在通用 `AudioFocus` 之上**按 zone 隔离**：主驾的导航不会 duck 后排的音乐，各 zone 内部再走 8.3/8.4 那套君子协定。它仍落地在 `AudioService` 的焦点框架里，只是 owner 记录多了 zone 维度。

### 11.4 与第 23 篇多屏的对应（一句）

车机的"音区（zone）"和多屏的"显示域（display）"是同一套"空间隔离"思想的音频/视频两面——`CarAudioService` 的 zone 路由与多屏的窗口/焦点归属逻辑可对照阅读（见 [23_多屏机制详解](./23_多屏与多窗口机制详解-从DisplayManager到CarOccupantZone.md)）。

---

## 12. 低延迟、Offload 与 AAudio/Oboe 路径

前面几章主角都是 `MixerThread` 的普通软件路径。还有三条"特殊通道"必须分清：

| 路径 | 触发条件 | 适合场景 | 代价 |
|---|---|---|---|
| **普通 Mixer** | 默认 PCM 播放 | 音乐/视频/提示音 | 延迟中等（~20ms+），最稳 |
| **FAST Track** | 采样率/格式与 output 严格匹配 + 请求 fast | 低延迟但不走 mmap | 受限多，仍过 `AudioFlinger` |
| **Offload** | track 标志 offload 且 output 支持压缩解码 | 长时间音乐，省电 | 不能软件混音、不能实时改 PCM |
| **Mmap / AAudio** | HAL 支持 mmap + App 用 AAudio/Oboe | 游戏/乐器/实时 I/O | 需要 HAL mmap 支持，设备差异大 |

### 12.1 Offload 什么时候走

Offload = 把**压缩音频（如 AAC）原样交给 DSP 解码播放**，CPU 几乎不碰 PCM。判定链（简化）：

```text
App 创建 AudioTrack，携带 offload 意图（或格式是压缩、usage=MEDIA）
  ▼
AudioPolicy 看目标 output 是否声明了 COMPRESS_OFFLOAD mixPort（第 10 章）
  ▼
是 → AudioFlinger 建 OffloadThread，把压缩帧直接 write 给 HAL/DSP
否 → 退回普通 MixerThread（先软件解码成 PCM 再混）
```

**什么时候"预期走 offload 却没走"**（排查见第 14 章）：配置里没声明 offload mixPort、HAL 不支持该编码、track 带了不能 offload 的 flag（如需要实时监控/特效）、或采样率不在 profile 范围。后果是 CPU 兜底解码，功耗上升、可能引入延迟。

### 12.2 Mmap 低延迟路径

Android 8.0 引入 `AAudio` + mmap：App 和 HAL 通过**共享内存环形缓冲**直接交换数据，绕过常规 Binder `write()` 和 `AudioFlinger` 的常规混音线程（走 `MmapThread`，NO_IRQ 模式由 HAL 自行调度）。这是游戏/实时音频的最低延迟路径。`Oboe` 是 Google 提供的 C++ 封装，自动在 AAudio 与老的 `AudioTrack` 之间择优。

「**Android 视角**」：mmap 能否用**完全取决于 HAL 是否实现 mmap 接口**（AIDL HAL 里对应 `IMmap` 一类）。很多非手机设备（尤其车机/老 SOC）没实现 → AAudio 自动回退到 `AudioTrack` 路径。别假设 mmap 一定可用。

### 12.3 一句话总结三条特殊通道

**Offload 是"把活甩给 DSP 省电"，Mmap 是"少绕路降延迟"，FAST 是"在常规路里开个VIP通道"**——它们都只是 `AudioFlinger` 不同 `PlaybackThread` 子类 + HAL 不同能力的组合，数据最终都还是从某个 output stream 出到硬件。

---

## 13. 调试工具箱：dumpsys / tinymix / Perfetto

排音频问题 80% 靠这几个命令。按"从 Java 策略面 → native 数据面 → HAL"逐层挖。

### 13.1 `dumpsys audio`（Java 策略面：焦点 / stream volume / device）

```bash
adb shell dumpsys audio
```

重点段（不同版本顺序略有差异）：

| 段 | 看什么 | 典型异常 |
|---|---|---|
| `Audio Focus` / `Focus stack` | 当前 focus owner、最近 request/abandon | owner 不是预期 App、被电话/车机抢走 |
| `Stream volumes` / `VolumeGroup` | 各 stream/组当前 index、mute 状态 | index=0 却以为开了音量；device 静音 |
| `Connected devices` / `Device state` | 已连接设备、当前 selected device | 该走蓝牙却停在 speaker |
| `Audio routes` / `Strategy` | 策略→设备结果 | 路由错（见第 14 章） |

### 13.2 `dumpsys media.audio_flinger`（native 数据面：tracks / buffer / underrun）

```bash
adb shell dumpsys media.audio_flinger
```

重点段：

| 段 | 看什么 | 典型异常 |
|---|---|---|
| `Threads` → 各 `PlaybackThread` | 线程类型（Mixer/Offload/Mmap）、采样率、帧数 | 该是 Offload 却是 Mixer |
| `Tracks` | 每个 Track 的状态（ACTIVE/PAUSED）、`underrun` 计数、`framesReady` | underrun 持续涨 = 上游供数据慢 |
| `fast` / `FastMixer` | fast track 状态 | 低延迟抖动 |
| `HAL` 层 buffer 状态 | 写 HAL 是否阻塞 | write 卡住导致整线程 stall |

### 13.3 `dumpsys media.audio_policy`（策略面：device / strategy 判定结果）

```bash
adb shell dumpsys media.audio_policy
```

看 `Engine` 给出的 `getDeviceForStrategy` 结果、`available outputs`、`volume curves` 是否加载。当你怀疑"策略选错设备"时，这里是源头——比 Java 侧 `dumpsys audio` 更贴近 `AudioPolicyManager` 的真实判定。

### 13.4 tinymix / tinyplay（HAL 直连，按可用性）

`tinyalsa` 工具（`tinymix` / `tinyplay` / `tinycap`）直接绕过 Framework 操作 ALSA 设备，用来**验证 HAL/驱动本身有没有声音**：

```bash
adb shell tinymix             # 列出/改 mixer 控件（如 speaker switch、音量寄存器）
adb shell tinyplay /data/test.wav   # 直接让驱动播一个 wav，绕过 AudioFlinger
```

若 `tinyplay` 有声而 App 无声 → 问题在 Framework（焦点/路由/混音）；若 `tinyplay` 也无声 → 问题在 HAL/驱动/硬件。**这是二分法的分水岭**。注意：user 版固件常不带这些工具，需 userdebug 或单独 push。

### 13.5 Perfetto 的 audio 轨

```bash
adb shell perfetto -o /data/misc/perfetto-traces/audio.pftrace \
  -t 10s sched freq audio '\
  SS:AudioFlinger:* SS:AudioPolicy:*'
```

Perfetto 能抓 `AudioFlinger` 线程唤醒周期、混音耗时、underrun 时间线，定位"卡顿是 CPU 调度还是混音算力"的终极手段。配合 `atrace` 的 `audio` 类别也行。

---

## 14. 常见问题排查表

原则：**无声先二分——App 写入了没 → Track 建了吗 → device 对吗 → HAL 有数据吗**（用 `tinyplay` 当分水岭）。下表按"现象 → 原因 → 见哪章 → 第一命令"组织。

| 现象 | 原因 | 章节 | 第一命令 |
|---|---|---|---|
| 完全无声 | App 没拿到焦点被 mute | 8 | `dumpsys audio` 看 Focus owner |
| 完全无声 | `AudioTrack` 没创建/play 没调 | 3 | `dumpsys media.audio_flinger` 看 Tracks |
| 完全无声 | 路由到错误/静音设备 | 6、10 | `dumpsys media.audio_policy` 看 device |
| 完全无声（HAL 级） | HAL/驱动没声 | 10、13.4 | `tinyplay test.wav` 验证 |
| 声音卡顿 / 爆音 | Track underrun，App 供数据慢 | 5 | `dumpsys media.audio_flinger` 看 underrun 计数 |
| 声音卡顿 | buffer 太小 | 3 | 查 `getMinBufferSize` 与构造 bufferSize |
| 声音卡顿 | 重采样吃力 / CPU 被抢 | 5、13.5 | Perfetto 看混音耗时 |
| 声音卡顿 | HAL write 阻塞 | 4、10 | `dumpsys media.audio_flinger` HAL 段 |
| offload 走错路 | 预期 offload 却走 PCM 混音 | 12.1 | `dumpsys media.audio_flinger` 看线程类型 |
| 音量改了没效果 | 调错 stream/usage 组 | 7 | `dumpsys audio` 看 VolumeGroup |
| 音量改了没效果 | 蓝牙绝对音量（本地不衰减） | 7.2 | `dumpsys audio` 看 device 音量模式 |
| 焦点频繁丢失 | 被电话/语音助手/车机抢 | 8 | `dumpsys audio` 看 focus 历史 |
| 焦点频繁丢失 | 自己没 abandon 导致串扰 | 8.4 | 查 `abandonAudioFocus` 调用 |
| 蓝牙声音断续 | A2DP 调度/绝对音量协商 | 7.2、12 | `dumpsys media.audio_policy` 看 BT device |
| 录音没数据 | `RECORD_AUDIO` 被 AppOps 限 | 9.2、14篇 | `appops get <pkg> RECORD_AUDIO` |
| 录音没数据 | 隐私指示器/后台限制拦截 | 9.2、18 | 查状态栏指示器 + AppOps |
| 多 zone 路由错 | bus 与 HAL devicePort 对不上 | 11.1 | 比对两份 xml 的 bus/address |
| HAL 起不来 | `audio_policy_configuration.xml` 语法错 | 10.3 | `logcat` 搜 `audio_policy` parse 错误 |
| 低延迟不生效 | HAL 没实现 mmap，回退 | 12.2 | `dumpsys media.audio_flinger` 看线程是否 Mmap |

「**Android 视角**」：`audioserver` 崩溃重启也会表现为"突然无声然后恢复"——`logcat` 搜 `audioserver` / `AudioFlinger` 的 crash 栈，比在 App 层猜更高效。

---

## 15. 读源码路线（5 条）

按"带着问题读"比从头读高效。下面 5 条覆盖主干，路径见第 17 章。

### 路线 1：AudioTrack 构造 → AudioFlinger createTrack

```text
frameworks/base/media/java/android/media/AudioTrack.java      // Java 构造、参数校验
  ▼ (JNI)
frameworks/av/media/libaudioclient/AudioTrack.cpp             // native 构造，建共享内存
  ▼ (Binder: IAudioFlinger)
frameworks/av/services/audioflinger/AudioFlinger.cpp          // createTrack()
  ▼
frameworks/av/services/audioflinger/Threads.cpp               // 挂到某个 PlaybackThread
  ▼
Track.cpp (TrackBase / Track)                                // 真正的 Track 对象 + 状态机
```

### 路线 2：mixer 周期循环（混音/重采样/underrun 的现场）

```text
frameworks/av/services/audioflinger/Threads.cpp
  └─ MixerThread::threadLoop()                  // 每周期醒来
       └─ prepareTracks_l() / mixOneBuffer()    // 拉各 Track、判定 underrun
            └─ AudioMixer.cpp::process()        // 重采样 + 混音 + 音量
                 └─ resampler/ 与 volume 子模块
```

### 路线 3：AudioPolicy device 选择

```text
frameworks/av/services/audiopolicy/managerdefault/AudioPolicyManager.cpp
  └─ getDeviceForStrategy()                     // strategy → device
       └─ EngineBase / AudioPolicyEngine        // 查 product strategy 表
            └─ AudioFlinger::openOutput()        // 向 HAL 开 output
                 └─ HAL open_output_stream()
```

### 路线 4：focus 请求链

```text
frameworks/base/media/java/android/media/AudioManager.java    // requestAudioFocus
  ▼
frameworks/base/services/core/java/com/android/server/audio/AudioService.java  // 焦点栈
  ▼
MediaFocusControl.java                                      // owner 记录、dispatch
  ▼
向各 client 发 onAudioFocusChange 回调                            // 丢失/获得
```

### 路线 5：car audio zone 初始化

```text
packages/services/Car/service/src/com/android/car/audio/CarAudioService.java
  └─ 解析 car_audio_configuration.xml                          // zone/volumeGroup/bus
       └─ 建立 zone → AudioAttributes → device 路由表
            └─ CarAudioFocus（按 zone 隔离的焦点）
                 └─ 落到通用 AudioPolicy 的设备选择（第 11 章）
```

---

## 16. 版本演进对照（AIDL HAL / offload / mmap / AudioAttributes）

把容易踩版本坑的点集中对照，照抄前先对版本。

| 能力 / 接口 | 引入版本 | 行为要点 | 迁移/注意 |
|---|---|---|---|
| `AudioAttributes` | API 21 | 用 usage/content_type/flags 描述声音语义 | 新代码替代 stream type |
| `AudioFocusRequest` + attributes | API 26 | 焦点请求必须带 attributes，老接口过时 | 不带的请求被标记过时 |
| Offload（压缩 offload 到 DSP） | 早已有（deep buffer），现代强化 | 省电；需配置 offload mixPort | 见 12.1 判定链 |
| **AAudio + mmap 低延迟** | Android 8.0 | 共享内存 NO_IRQ 路径 | **取决于 HAL 是否实现 mmap**，否则回退 |
| audio **HIDL** HAL | Android 8 (Treble) | 2.0~7.0 主流 | 长期兼容 |
| audio **AIDL** HAL | Android 12 引入 | 13/14 主推、新设备目标 | **vendor 必须迁移**，结构换 AIDL 定义 |
| `audio_policy_configuration.xml` | 随 HIDL/Treble | 取代老 `audio_policy.conf` | vendor 最常改 |
| `car_audio_configuration.xml` | AAOS（Android 9+ 演进） | 多 zone/volumeGroup/bus | 与 HAL bus 对应 |
| 隐私指示器（mic） | Android 12 | 录音时状态栏亮指示 + AppOps 记录 | 见 9.2、18 |

一句话：**接口层面"HIDL→AIDL"和"stream→attributes"是两个最该记住的迁移方向；能力层面"mmap 要看 HAL、offload 要看配置"是两个最常被误判的前提**。

---

## 17. 源码路径速查表 + 一图总结 + 关联阅读

### 17.1 源码路径速查

| 内容 | 路径 |
|---|---|
| `AudioTrack` (Java) | `frameworks/base/media/java/android/media/AudioTrack.java` |
| `AudioRecord` (Java) | `frameworks/base/media/java/android/media/AudioRecord.java` |
| `AudioManager` | `frameworks/base/media/java/android/media/AudioManager.java` |
| `AudioService` / `MediaFocusControl` | `frameworks/base/services/core/java/com/android/server/audio/` |
| `AudioTrack` / `AudioRecord` (native) | `frameworks/av/media/libaudioclient/` |
| `AudioFlinger` | `frameworks/av/services/audioflinger/`（`AudioFlinger.cpp` / `Threads.cpp` / `TrackBase.cpp` / `MixerThread`） |
| `AudioMixer` | `frameworks/av/services/audioflinger/audio/mixer/`（`AudioMixer.cpp` + `resampler/`） |
| `AudioPolicyService` | `frameworks/av/services/audiopolicy/service/` |
| `AudioPolicyManager` | `frameworks/av/services/audiopolicy/managerdefault/` |
| AudioPolicy `Engine` | `frameworks/av/services/audiopolicy/engine/`（`EngineBase` 一类） |
| `AudioSystem` (native) | `frameworks/av/media/libaudioclient/` |
| AAudio / Oboe | `frameworks/av/media/libaaudio/`（Oboe 在 `frameworks/av/media/oboe/`） |
| Audio HAL 接口（HIDL） | `hardware/interfaces/audio/2.0` … `7.0` |
| Audio HAL 接口（**AIDL**） | `hardware/interfaces/audio/aidl/` |
| Audio HAL（legacy） | `hardware/libhardware/include/hardware/audio.h` |
| 音量曲线配置 | `audio_policy_volumes.xml` / `volume_groups.xml`（系统/etc 或 vendor） |
| 音频策略配置 | `audio_policy_configuration.xml`（vendor 最常改） |
| 车机音频配置 | `car_audio_configuration.xml`（AAOS） |
| `CarAudioService` | `packages/services/Car/service/src/com/android/car/audio/` |

### 17.2 一图总结（播放链分层）

```text
┌──────────── App ────────────┐
│ AudioTrack.write(PCM)        │   ① 应用层：喂数据、管焦点/音量意图
└──────────────┬──────────────┘
               │ Binder
┌──────────────▼──────────────┐
│ audioserver                  │
│  AudioFlinger (数据面)        │   ② 混音/重采样/音量(track gain)/线程
│    MixerThread/Offload/Mmap  │
│  AudioPolicy (策略面)         │   ③ 选设备/策略/音量组
└──────────────┬──────────────┘
               │ HAL (HIDL/AIDL)
┌──────────────▼──────────────┐
│ audio.primary.<soc>          │   ④ 厂商：写硬件
│  → ALSA/TinyALSA → DAC       │
└─────────────────────────────┘
```

**一句话记忆链**：`AudioTrack` 把货（PCM）交给 `AudioFlinger` 这个分拣中心，`AudioPolicy` 给它调度单（走哪条干线），干线尽头 `HAL` 把货送到喇叭——而"谁有资格发货"由 `AudioFocus` 这个门卫先盖章。

### 17.3 关联阅读

- [20_相机与媒体机制详解](./20_相机与媒体编解码详解-从CameraHAL到MediaCodec与上屏.md)：MediaCodec 解码、相机、视频上屏（本篇音频解码后落 AudioTrack 的部分归它）。
- [18_SystemUI机制详解](./18_SystemUI机制详解-从状态栏到锁屏与通知面板.md)：音量条 UI、隐私指示器（mic 指示）的展示层。
- [14_权限机制详解](./14_权限机制详解-从Permission到AppOps.md)：`RECORD_AUDIO` 与 AppOps 限制（录音被拦的根因）。
- [15_Power机制详解](./15_Power机制详解-从WakeLock到Doze后台限制.md)：音频播放/录音对 wakelock、休眠策略的影响。
- automotive 系列篇：`CarAudioService` / `CarAudioFocus` 车机服务本体（本篇第 11 章只指路）。
- [23_多屏机制详解](./23_多屏与多窗口机制详解-从DisplayManager到CarOccupantZone.md)：显示域与音区（zone）的空间隔离对照。

---

