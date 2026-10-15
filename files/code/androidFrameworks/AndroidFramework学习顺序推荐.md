# Android Framework 学习顺序推荐

> 这篇用来串起当前目录里的 Framework 文档。目标不是把所有源码一次读完，而是按“从 App 现象到系统服务”的顺序，建立一张能排查真实问题的地图。

---

## 1. 推荐总路线

```text
1. Android 启动流程：init / Zygote / SystemServer
2. Binder / AIDL
3. 消息机制：Looper / Handler / Choreographer
4. PMS
5. AMS
6. ATMS
7. Service / Broadcast / ContentProvider
8. WMS
9. SurfaceFlinger
10. 图形渲染：HWUI / RenderThread / GPU 上屏
11. Input
12. Permission / AppOps
13. Power
14. Alarm / JobScheduler
15. Notification
16. SystemUI
17. 多用户 / DevicePolicy
18. 相机与媒体编解码
19. Audio
```

这个顺序的核心逻辑是：先理解系统怎么启动、服务怎么通过 Binder 通信、线程消息怎么调度；再理解包、进程、Activity 和四大组件；然后理解窗口、显示、输入；最后扩展到权限、后台、系统 UI、多用户和媒体。

---

## 2. 第一阶段：系统启动、跨进程和线程调度

### 2.1 Android 启动流程

先知道 Framework 服务是怎么起来的：`init` 启动 Zygote，Zygote fork `system_server`，SystemServer 再启动 PMS、AMS、ATMS、WMS 等服务。

重点理解：

- init 和 rc 脚本。
- Zygote 预加载和 fork。
- system_server。
- SystemServiceManager。
- boot phase。

对应文档：`Android启动流程详解-从init到Zygote和SystemServer.md`

### 2.2 Binder / AIDL

先学 Binder，因为 Framework 服务几乎都通过 Binder 对外提供能力。

你要理解：

- Stub / Proxy。
- BinderProxy。
- Parcel。
- ServiceManager。
- system_server 和 App 进程如何通信。

对应文档：`Binder机制详解-从AIDL到Framework.md`

### 2.3 消息机制

再学 Looper/Handler，因为 App 主线程、`ActivityThread.H`、AMS/WMS 内部调度、Choreographer 绘制节奏都依赖消息机制。

重点理解：

- Looper / MessageQueue / Handler。
- ActivityThread 主线程。
- ActivityThread.H。
- Choreographer 和 VSYNC。
- ANR 和消息阻塞。

对应文档：`消息机制详解-从Looper到Handler和Choreographer.md`

---

## 3. 第二阶段：包、进程、Activity 和四大组件主干

### 3.1 PMS

PMS 告诉系统“有哪些 App 和组件”。

重点理解：

- 获取 App 列表。
- 安装/卸载。
- Manifest 解析。
- Intent 解析。
- 权限和签名。

对应文档：`PMS机制详解-从App列表到安装卸载流程.md`

### 3.2 AMS

AMS 管应用进程和组件运行状态。

重点理解：

- 进程启动。
- Service/Broadcast/Provider。
- OOM adj。
- ANR。

对应文档：`AMS机制详解-从App现象理解系统调度.md`、`AMS机制详解-从应用交互到Framework工作流程.md`

### 3.3 Service

Service 是 AMS 管理后台执行和跨进程绑定的重要组件。

重点理解：

- `startService()`。
- `bindService()`。
- 前台服务。
- Service 和 OOM adj。
- Service ANR。

对应文档：`Service机制详解-从startService到bindService和前台服务.md`

### 3.4 Broadcast

Broadcast 是 AMS 管理的系统事件分发机制。

重点理解：

- 静态/动态 Receiver。
- 有序/并行广播。
- 隐式广播限制。
- 开机广播。
- Broadcast ANR。

对应文档：`Broadcast机制详解-从sendBroadcast到广播队列分发.md`

### 3.5 ContentProvider

Provider 是跨进程数据访问组件，也会影响冷启动和权限。

重点理解：

- `ContentResolver.query()`。
- Provider 进程拉起。
- Provider 安装早于 `Application.onCreate()`。
- URI 权限和 FileProvider。
- ContentObserver。

对应文档：`ContentProvider机制详解-从ContentResolver到跨进程数据访问.md`

### 3.6 ATMS

ATMS 管 Activity 和 Task。

重点理解：

- `startActivity()`。
- `ActivityStarter`。
- launchMode 和 flags。
- Task / ActivityRecord。
- ClientTransaction。

对应文档：`ATMS机制详解-从startActivity到任务栈调度.md`

---

## 4. 第三阶段：窗口、显示、输入

### 4.1 WMS

WMS 管窗口秩序。

重点理解：

- Window token。
- WindowState。
- 窗口层级和焦点。
- Insets。
- 多屏窗口。

对应文档：`WMS机制详解-从窗口添加到显示管理.md`

### 4.2 SurfaceFlinger

SurfaceFlinger 管图层合成和上屏。

重点理解：

- Surface / Buffer / Layer。
- BufferQueue。
- VSYNC。
- HWC。
- SurfaceView / TextureView。

对应文档：`SurfaceFlinger机制详解-从Buffer到屏幕合成.md`

### 4.3 图形渲染：HWUI / RenderThread / GPU 上屏

SurfaceFlinger 讲的是**合成端**（Buffer 到屏幕）。这一段补上它前面的**绘制端**：App 说"我要画"之后，绘制指令怎么产生、怎么变成 GPU 命令、怎么进 BufferQueue。

重点理解：

- 三个线程与两个世界：UI 线程 / RenderThread / SurfaceFlinger，CPU 绘制与 GPU 渲染的边界。
- VSYNC 与 Choreographer 的几类 callback、frame deadline。
- DisplayList（RenderNode）与硬件加速的失效场景。
- Skia 后端（GL / Vulkan）与 Bitmap 上传成本。
- BufferQueue 状态机与三级缓冲。
- 掉帧按轨归因：UI 线程慢 / RenderThread 慢 / SF 侧 / 送显侧。

对应文档：`图形渲染机制详解-从HWUI到GPU上屏.md`

### 4.4 Input

Input 管触摸和按键如何送到窗口。

重点理解：

- InputReader。
- InputDispatcher。
- InputChannel。
- WMS 焦点窗口。
- Input ANR。

对应文档：`Input机制详解-从触摸事件到InputDispatcher分发.md`

### 4.5 多屏与多窗口：DisplayManager / VirtualDisplay / 车机 OccupantZone

11_WMS 讲的是单屏内的窗口管理，这一篇把维度换成"多块屏 + 屏内多窗口"。车机多屏（仪表 / 中控 / 副驾）直接看第 11 节车机专章。

重点理解：

- Display 的两个视角：App 侧 `Display` 与 WMS 侧 `DisplayContent`，displayId 分配。
- 屏幕从哪来：Built-in / VirtualDisplay / 模拟副屏。
- Presentation、`ActivityOptions.setLaunchDisplayId` 与 Activity 跨屏迁移。
- 多窗口四形态：分屏 / freeform / PiP / Activity Embedding，及 WMShell 与 TaskOrganizer 的"调度 vs UI"分离。
- 跨屏输入与焦点、per-display SystemUI（衔接 18 篇第 16 节）。
- 车机 OccupantZone：zone ↔ Display ↔ userId 的三方绑定。

对应文档：`23_多屏与多窗口机制详解-从DisplayManager到CarOccupantZone.md`

---

## 5. 第四阶段：权限、后台和功耗

### 5.1 Permission / AppOps

解释“有权限为什么还不能用”。

对应文档：`权限机制详解-从Permission到AppOps.md`

### 5.2 Power

解释亮灭屏、WakeLock、Doze。

对应文档：`Power机制详解-从WakeLock到Doze后台限制.md`

### 5.3 Alarm / JobScheduler

解释后台任务为什么不准时、不执行。

对应文档：`后台任务机制详解-从Alarm到JobScheduler.md`

---

## 6. 第五阶段：系统 UI、多用户和媒体

### 6.1 Notification

解释通知发布、渠道、前台服务通知、SystemUI 展示。

对应文档：`通知机制详解-从NotificationManagerService到SystemUI展示.md`

### 6.2 SystemUI

解释状态栏、导航栏、锁屏、通知面板、最近任务。

对应文档：`SystemUI机制详解-从状态栏到锁屏与通知面板.md`

### 6.3 多用户 / DevicePolicy

解释 userId、profile、设备管理、权限固定、卸载限制。

对应文档：`多用户机制详解-从UserManager到DevicePolicy.md`

### 6.4 相机与媒体编解码

解释一帧图像、一段视频的完整流水线：Camera2 / CameraX 与 Camera HAL3 / CameraService、预览拍照录像三条输出路径、MediaCodec 与 Codec2、封装与播放（Media3 / ExoPlayer），以及与 SurfaceFlinger 的衔接。

对应文档：`相机与媒体编解码详解-从CameraHAL到MediaCodec与上屏.md`

### 6.5 Audio

解释播放、录音、混音、音频焦点、音量和路由策略。

对应文档：`Audio机制详解-从AudioTrack到AudioFlinger和AudioPolicy.md`

---

## 7. 按问题反查该看什么

| 问题 | 优先看 |
|---|---|
| App 装不上、卸不掉 | PMS |
| Intent 找不到 Activity | PMS + ATMS |
| Activity 启动慢 | ATMS + AMS + App + WMS |
| 返回栈异常 | ATMS |
| Service 起不来 | Service + AMS + Power |
| 前台服务异常 | Service + AMS + Notification + Permission/AppOps |
| 广播收不到 | Broadcast + AMS + PMS + 多用户 |
| Provider 查询慢 | ContentProvider + AMS + App 数据库 |
| FileProvider 分享失败 | ContentProvider + Permission/AppOps |
| App 被杀 | AMS + OOM + LMKD |
| Dialog 报 BadToken | WMS |
| 窗口不显示 | WMS + SurfaceFlinger |
| 黑屏/花屏/掉帧 | SurfaceFlinger + HWC + App 渲染 |
| 点击无响应 | Input + WMS + ANR |
| 主线程卡顿/ANR | 消息机制 + AMS/ATMS/Input |
| 有权限但功能不可用 | Permission + AppOps |
| 后台任务不执行 | Power + Alarm + JobScheduler + AMS |
| 通知不显示 | Notification + Permission + SystemUI |
| 锁屏/状态栏/导航栏异常 | SystemUI + WMS |
| 多用户下 App 不见 | PMS + UserManager |
| 应用卸载不了 | PMS + DevicePolicy |
| 视频有声无画 | Media + SurfaceFlinger + HWC |
| 开机慢/开机广播异常 | Android 启动流程 + PMS + AMS + Broadcast |

---

## 8. 学习方法建议

- 每个服务先记职责边界，不要急着背所有类。
- 先从 App 侧现象切入，再往 system_server/native 追。
- 每条链路至少掌握一个 dumpsys 命令。
- 学一个服务时，同时记住它依赖谁、被谁调用。
- 不要把 Android Framework 当单体源码看，它是一组通过 Binder 和状态机协作的服务。

如果只记一条主线：

> App 通过 Binder 请求 Framework；PMS 提供包事实，AMS/ATMS 管组件和进程，WMS 管窗口，SurfaceFlinger 管上屏，Input 把用户操作送回 App，Power/Job/Alarm 决定后台能不能继续跑。