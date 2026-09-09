# Audio 机制详解：从 AudioTrack 到 AudioFlinger 和 AudioPolicy

> 这篇补齐 Media 模块里容易被忽略的音频链路。视频显示重点看 Surface、BufferQueue、SurfaceFlinger；音频播放和录音则要看 AudioTrack、AudioRecord、AudioFlinger、AudioPolicy、Audio HAL 和音频焦点。

---

## 目录

- [Audio 机制详解：从 AudioTrack 到 AudioFlinger 和 AudioPolicy](#audio-机制详解从-audiotrack-到-audioflinger-和-audiopolicy)
  - [目录](#目录)
  - [1. Audio 链路是什么](#1-audio-链路是什么)
  - [2. 音频播放流程](#2-音频播放流程)
  - [3. 音频录音流程](#3-音频录音流程)
  - [4. AudioFlinger 是什么](#4-audioflinger-是什么)
  - [5. AudioPolicy 是什么](#5-audiopolicy-是什么)
  - [6. 音频焦点、音量和路由](#6-音频焦点音量和路由)
    - [音频焦点](#音频焦点)
    - [常见焦点请求类型](#常见焦点请求类型)
    - [App 收到焦点变化后应该做什么](#app-收到焦点变化后应该做什么)
    - [音量](#音量)
    - [路由](#路由)
  - [7. 低延迟、Offload 和 AAudio](#7-低延迟offload-和-aaudio)
  - [8. 音频知识地图](#8-音频知识地图)
  - [9. 常见问题与排查](#9-常见问题与排查)
    - [没声音](#没声音)
    - [有声音但走错设备](#有声音但走错设备)
    - [卡顿、爆音、断续](#卡顿爆音断续)
    - [录音没数据或声音异常](#录音没数据或声音异常)
  - [10. 第三方系统常见修改点](#10-第三方系统常见修改点)
  - [11. 源码路径速查](#11-源码路径速查)

---

## 1. Audio 链路是什么

Android 音频链路负责把 App 的 PCM 数据送到硬件，也负责把麦克风采集的数据送回 App。

一句话：

> Audio 播放链路是“App 写音频数据，AudioFlinger 混音，AudioPolicy 选设备和策略，Audio HAL 输出到硬件”。

音频和视频最大的区别是：视频更关注 buffer 如何上屏，音频更关注实时性、混音、音量、路由、焦点和设备切换。

---

## 2. 音频播放流程

典型播放链路：

```text
App / Player
  └─ AudioTrack.write PCM
       └─ Binder 调用 AudioFlinger
            └─ Track 加入 MixerThread
                 └─ 混音、重采样、音量处理
                      └─ Audio HAL
                           └─ Speaker / Bluetooth / USB / HDMI
```

如果是 `MediaPlayer` 或 ExoPlayer 播放音乐，解码后也会走到 AudioTrack：

```text
MediaExtractor
  └─ MediaCodec 解码音频
       └─ AudioTrack 输出 PCM
            └─ AudioFlinger 混音
                 └─ Audio HAL 输出
```

播放卡顿、杂音、无声，通常要分别判断：App 是否写入数据、AudioTrack 是否创建成功、AudioFlinger 是否有 track、AudioPolicy 是否选对设备、HAL 是否正常输出。

---

## 3. 音频录音流程

典型录音链路：

```text
Microphone
  └─ Audio HAL 采集
       └─ AudioFlinger RecordThread
            └─ AudioRecord.read
                 └─ App 获取 PCM 数据
```

录音链路会受权限、隐私开关、音频路由、采样率、输入源和并发策略影响。

常见输入源包括：

- `MIC`
- `VOICE_RECOGNITION`
- `VOICE_COMMUNICATION`
- `CAMCORDER`

不同输入源可能触发不同的降噪、回声消除、自动增益和路由策略。

---

## 4. AudioFlinger 是什么

`AudioFlinger` 是 Android native 层的核心音频服务，运行在 `audioserver` 进程中。

它主要负责：

- 管理播放 Track 和录音 RecordTrack。
- 创建 playback thread / record thread。
- 混音多个 App 的音频流。
- 重采样和格式转换。
- 处理音量、静音、effect。
- 调用 Audio HAL 读写硬件。

粗略理解：

```text
多个 App 的 AudioTrack
  └─ AudioFlinger MixerThread
       ├─ 混音
       ├─ 重采样
       ├─ 音效处理
       └─ 写入 Audio HAL
```

AudioFlinger 更偏“数据面”：它关心音频数据怎么混、怎么写、线程怎么跑、buffer 是否 underrun。

---

## 5. AudioPolicy 是什么

`AudioPolicyService` 也运行在 `audioserver` 进程中，它负责音频策略。

它主要决定：

- 当前声音应该走扬声器、听筒、耳机、蓝牙、USB 还是 HDMI。
- 不同 stream / usage 的音量策略。
- 电话、导航、媒体、闹钟、通知之间的优先级。
- 音频焦点变化后应该 duck、pause 还是 stop。
- 输入输出设备如何选择。
- 是否允许并发播放或录音。

可以这样分工：

| 模块 | 更关心什么 |
|---|---|
| AudioFlinger | 音频数据怎么流动、混音、写入硬件 |
| AudioPolicy | 音频应该走哪里、谁优先、音量怎么算 |

所以“有声音但走错设备”更多看 AudioPolicy；“设备对了但卡顿、爆音、underrun”更多看 AudioFlinger/HAL。

---

## 6. 音频焦点、音量和路由

### 音频焦点

音频焦点是 App 最直接会接触到的音频策略入口。它解决的问题不是“声音数据怎么写到硬件”，而是“多个 App 同时想发声时，谁应该继续播，谁应该暂停，谁应该降低音量”。

典型例子：

- 音乐播放时，导航播报插入，音乐临时变小。
- 听歌时接到电话，音乐暂停。
- 短视频 App 开始播放后，后台音乐停止。
- 语音助手唤醒时，其他媒体声音降低或暂停。

App 播放前通常会通过 `AudioManager.requestAudioFocus()` 请求焦点：

```text
App requestAudioFocus
  └─ AudioService
                ├─ 记录当前 focus stack
                ├─ 判断新请求和已有播放者的关系
                └─ 通知其他 App duck / pause / loss focus
```

这里 Java Framework 侧的 `AudioService` 非常关键。App 调用的是 `AudioManager`，真正管理焦点栈、分发焦点变化回调的是 system_server 里的 `AudioService`；native 层 `AudioPolicyService` 更偏设备路由、策略和 HAL 连接。

### 常见焦点请求类型

| 请求类型 | 典型场景 | 对其他 App 的影响 |
|---|---|---|
| `AUDIOFOCUS_GAIN` | 音乐、长视频、播客 | 长时间占用焦点，其他媒体通常暂停 |
| `AUDIOFOCUS_GAIN_TRANSIENT` | 短提示、语音助手、临时播放 | 短暂占用，结束后应释放焦点 |
| `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` | 导航播报、短音效 | 允许其他 App 降低音量继续播 |
| `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` | 录音、语音识别、通话前后处理 | 希望独占音频，其他 App 应暂停或停止 |

App 收到的焦点变化常见有：

| 回调 | 含义 | App 常见处理 |
|---|---|---|
| `AUDIOFOCUS_GAIN` | 重新拿到焦点 | 恢复播放或恢复原音量 |
| `AUDIOFOCUS_LOSS` | 长时间失去焦点 | 停止播放并释放焦点相关资源 |
| `AUDIOFOCUS_LOSS_TRANSIENT` | 暂时失去焦点 | 暂停，等待后续 `GAIN` 再恢复 |
| `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` | 可以降低音量继续播放 | 降低音量，或根据内容类型选择暂停 |

不是所有 App 都适合 duck。音乐可以降低音量继续播；有声书、播客、教学内容被压低后可能听不清，更适合暂停。

### App 收到焦点变化后应该做什么

App 侧重点不是知道 AudioFlinger 怎么混音，而是把焦点当成播放状态机的一部分：

```text
准备播放
     └─ requestAudioFocus 成功
                └─ start playback

收到 AUDIOFOCUS_LOSS_TRANSIENT
     └─ pause playback

收到 AUDIOFOCUS_GAIN
     └─ 如果之前是因为焦点丢失而暂停，则恢复播放

停止播放
     └─ abandonAudioFocus
```

几个容易踩坑的点：

- 播放前不请求焦点，可能和其他 App 混在一起播。
- 收到 `LOSS` 后还继续播，会破坏系统音频体验，也可能被厂商策略强制压制。
- 收到 `CAN_DUCK` 后不处理，导航、语音助手这类场景会体验很差。
- 临时音频播放结束后不 `abandonAudioFocus`，可能导致别的 App 无法正确恢复。
- 播放器内部的 play/pause 状态要区分“用户主动暂停”和“焦点丢失导致暂停”，否则重新拿到焦点时容易错误自动播放。

排查焦点问题重点看：

```bash
adb shell dumpsys audio
```

常关注这些信息：

- 当前 focus owner 是哪个包。
- focus stack 里有哪些客户端。
- 最近一次 focus request / abandon 记录。
- App 的 usage/content type 是否符合场景。
- 是否被电话、语音助手、蓝牙或车机策略抢走焦点。

### 音量

Android 音量不是简单的一个全局值，而是和 stream、usage、device、音量曲线有关。

常见 stream：

- `STREAM_MUSIC`
- `STREAM_RING`
- `STREAM_ALARM`
- `STREAM_VOICE_CALL`
- `STREAM_NOTIFICATION`

新代码更推荐从 AudioAttributes 的 usage 理解音频用途，例如 media、alarm、notification、voice communication。

### 路由

路由会受设备插拔、蓝牙连接、通话状态、投屏、车机音区、多用户策略影响。

```text
耳机插入 / 蓝牙连接 / 通话开始
  └─ AudioPolicy 重新选择 device
       └─ AudioFlinger 切换 output thread 或 HAL device
```

---

## 7. 低延迟、Offload 和 AAudio

普通音频播放通常会经过 AudioFlinger 混音，稳定但有一定延迟。

对游戏、乐器、实时通话这类场景，延迟非常关键。

常见路径：

| 路径 | 适合场景 |
|---|---|
| AudioTrack 普通模式 | 常规音乐、提示音、视频播放 |
| FAST Track | 低延迟播放，要求格式和采样率匹配 |
| AAudio / Oboe | 游戏、实时音频、低延迟输入输出 |
| Offload | 音乐长时间播放，交给 DSP 降低功耗 |

Offload 常用于音乐播放：解码和播放尽量交给专用硬件，减少 CPU 和功耗。低延迟则更关注 buffer 尺寸、线程调度、采样率匹配和 HAL 支持。

---

## 8. 音频知识地图

前面几节只是音频 Framework 主干。实际做 App 或系统问题排查时，音频还会分出很多专题：

| 方向 | 重点问题 |
|---|---|
| 播放 API | `MediaPlayer`、ExoPlayer、`SoundPool`、`AudioTrack`、AAudio 怎么选 |
| 录音 API | `MediaRecorder`、`AudioRecord`、输入源、编码格式、采样率 |
| 音频焦点 | 多 App 抢声音时，谁暂停、谁 duck、谁恢复 |
| AudioAttributes | usage、content type、flags 如何影响焦点、音量和路由 |
| 音量体系 | stream volume、volume group、device volume、音量曲线 |
| 路由和设备 | 扬声器、听筒、有线耳机、蓝牙、USB、HDMI、车机音区 |
| 蓝牙音频 | A2DP、SCO、BLE Audio、通话和媒体路由差异 |
| 通话音频 | mode、voice call、回声消除、降噪、双向低延迟 |
| 音效 | Equalizer、BassBoost、Virtualizer、AEC、NS、AGC、AudioEffect session |
| 录音隐私 | `RECORD_AUDIO`、AppOps、隐私指示器、后台录音限制 |
| 并发策略 | 多 App 同时播放、同时录音、通话中录音、语音助手抢占 |
| 性能延迟 | underrun、buffer size、FAST track、线程调度、功耗 |
| 编解码和格式 | AAC、Opus、PCM、采样率、声道数、bitrate、容器封装 |
| 系统定制 | 默认路由、音量曲线、车机混音、开机音、提示音、区域音频 |

如果从 App 开发角度学习，优先级通常是：

```text
AudioAttributes
  └─ AudioFocus
       └─ 播放/录音 API
            └─ 音量和路由
                 └─ 蓝牙/通话/低延迟专题
                      └─ AudioFlinger/AudioPolicy 源码
```

如果从 Framework 或厂商系统角度学习，优先级通常是：

```text
AudioService
  └─ AudioPolicyService / AudioPolicyManager
       └─ AudioFlinger
            └─ Audio HAL
                 └─ 设备路由、音量曲线、低延迟和稳定性
```

---

## 9. 常见问题与排查

### 没声音

```bash
adb shell dumpsys audio
adb shell dumpsys media.audio_flinger
adb shell dumpsys media.audio_policy
adb logcat -b all | grep -i "AudioTrack\|AudioFlinger\|AudioPolicy\|audioserver"
```

重点看：

- App 是否拿到 audio focus。
- AudioTrack 是否创建成功。
- AudioFlinger 里是否有 active track。
- 当前路由设备是否正确。
- 音量是否为 0 或被 mute。
- audioserver 是否崩溃重启。

### 有声音但走错设备

重点查 AudioPolicy：

```bash
adb shell dumpsys media.audio_policy
adb shell dumpsys audio
```

看当前 connected devices、selected device、strategy、product strategy、volume group。

### 卡顿、爆音、断续

重点查 underrun、buffer、线程调度和 HAL：

```bash
adb shell dumpsys media.audio_flinger
adb logcat -b all | grep -i "underrun\|AudioFlinger\|AudioTrack"
```

常见原因：

- App 写数据太慢。
- buffer 太小。
- 采样率不匹配导致重采样压力大。
- CPU 忙或线程调度延迟。
- HAL 输出阻塞。

### 录音没数据或声音异常

```bash
adb shell dumpsys media.audio_flinger
adb shell dumpsys media.audio_policy
adb shell appops get 包名 RECORD_AUDIO
```

重点看权限、AppOps、隐私开关、输入源、设备路由、RecordThread 状态。

---

## 10. 第三方系统常见修改点

- 默认音量和音量曲线。
- 扬声器、听筒、蓝牙、USB、HDMI 路由策略。
- 车机多音区和导航混音策略。
- 通话、媒体、导航、语音助手的焦点优先级。
- 开机提示音、按键音、系统音效。
- 低延迟播放和录音优化。
- audioserver 崩溃恢复和 HAL 兼容。
- 麦克风隐私、录音并发和后台录音限制。

---

## 11. 源码路径速查

| 内容 | 路径 |
|---|---|
| AudioTrack | `frameworks/av/media/libaudioclient/` |
| AudioRecord | `frameworks/av/media/libaudioclient/` |
| AudioFlinger | `frameworks/av/services/audioflinger/` |
| AudioPolicyService | `frameworks/av/services/audiopolicy/service/` |
| AudioPolicyManager | `frameworks/av/services/audiopolicy/managerdefault/` |
| AudioService | `frameworks/base/services/core/java/com/android/server/audio/` |
| AudioManager | `frameworks/base/media/java/android/media/` |
| Audio HAL 接口 | `hardware/interfaces/audio/`、`hardware/libhardware/include/hardware/audio.h` |
| AAudio | `frameworks/av/media/libaaudio/` |