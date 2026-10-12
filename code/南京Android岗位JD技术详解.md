# 南京 Android 岗位 JD 技术详解

> 配套文档：`南京Android开发岗位技能需求分析.md`（市场与技能清单）  
> 本文用途：把南京在招 JD 里出现的每一个技术点，**讲清原理、调用链、面试会被追问到哪里**，并给出延伸方向  
> 读者定位：12 年开发经验、9 年车机开发经验，**不需要基础扫盲，需要的是"系统层的完整链条"**

---

## 阅读说明

### 本文的组织方式

本文不按"章节"组织，而按**知识块**组织。每个知识块来自真实 JD 里的技术点，统一采用这个结构：

```
【JD 出处】  哪家公司的岗位、要求原话
【要解决的问题】 为什么业界需要它
【原理】     机制层面讲清楚
【关键调用链 / 代码】
【常见追问】  面试官会往哪里深挖
【延伸方向】  下一步该看什么源码 / 做什么实验
```


### 知识块地图

| 编号                     | 知识块                                                        | 主要 JD 来源          |
| ---------------------- | ---------------------------------------------------------- | ----------------- |
| **第一部分：车机与座舱体系**       |                                                            |                   |
| 1.1                    | Android Automotive（AAOS）全景与手机 Android 的本质差异                | 光庭、经创、华苏、小米       |
| 1.2                    | CarService 架构与启动流程                                         | 光庭、经创             |
| 1.3                    | CarPropertyManager → CarPropertyService → VehicleHal 完整调用链 | 经创、光庭、小米          |
| 1.4                    | VHAL 属性系统：属性、Area、配置、采样率、订阅回调                              | 经创、光庭             |
| 1.5                    | CarService 其他核心服务：音频、电源、UX 限制、包管理                          | 光庭、紫光展锐           |
| 1.6                    | 车载总线：CAN / CAN FD                                          | 经创、小米 MCU         |
| 1.7                    | SOMEIP 原理与它在 Android 中的落点                                  | 经创                |
| 1.8                    | DDS 原理及与 SOMEIP 的对比                                        | 经创                |
| 1.9                    | 从 CAN 一帧到 App 回调：完整数据通路                                    | 经创、光庭             |
| 1.10                   | 车载诊断：UDS / DoIP / ISO-TP                                   | 小米 MCU、光庭         |
| 1.11                   | AUTOSAR 与 MCU 侧：SWC / RTE / BSW / CDD / MCAL               | 小米 MCU            |
| 1.12                   | 车机多屏、仪表投屏、多窗口分屏                                            | 华苏、颖派             |
| **第二部分：Framework 系统层** |                                                            |                   |
| 2.1                    | Android 系统分层与启动流程（BootROM → SystemServer）                  | 全车机岗、机器岛          |
| 2.2                    | Binder 深度详解                                                | 全体（底层中的底层）        |
| 2.3                    | Handler / Looper / MessageQueue 与 ANR 的关系                  | 全体                |
| 2.4                    | AMS：Activity 启动流程与进程管理                                     | 华苏、机器岛、颖派         |
| 2.5                    | WMS：窗口、Surface、多窗口分屏、Transaction                           | 颖派、华苏、机器岛         |
| 2.6                    | PMS：包扫描、权限、签名                                              | 华苏、机器岛            |
| 2.7                    | SystemUI / keyguard / Settings 模块                          | 颖派、机器岛            |
| 2.8                    | SurfaceFlinger 与图形栈（BufferQueue / VSYNC / hwui）            | BOSS直聘、机器岛、小米图形渲染 |
| 2.9                    | AOSP 编译、裁剪、定制全流程                                           | 光庭、颖派、机器岛         |
| 2.10                   | 系统应用与权限：priv-app / platform 签名 / system uid / SELinux      | 小米、光庭             |
| **第三部分：应用层硬功夫**        |                                                            |                   |
| 3.1                    | Kotlin 协程原理（CPS 变换与状态机）                                    | 焦点科技、浩鲸、上海之几      |
| 3.2                    | Flow / StateFlow / SharedFlow                              | 同上                |
| 3.3                    | Jetpack Compose 原理与重组机制                                    | 浩鲸、上海之几、BOSS直聘    |
| 3.4                    | MVVM / MVI / MVP 的取舍与演进                                    | 浩鲸、华设、软通          |
| 3.5                    | 组件化 / 模块化 / 插件化                                            | 浩鲸、焦点科技           |
| 3.6                    | WebView + JSBridge 混合开发                                    | 中瑞、上海之几、车机 H5     |
| 3.7                    | 性能优化方法论（启动 / 内存 / 卡顿 / 耗电 / 包体）                            | 几乎全体              |
| 3.8                    | 稳定性治理（ANR / Crash / Watchdog / 死机重启）                       | 经创、华苏、小米          |
| **第四部分：音视频与图形**        |                                                            |                   |
| 4.1                    | Android 媒体框架全景与播放链路                                        | 紫光展锐、软通           |
| 4.2                    | MediaCodec 原理                                              | 紫光展锐、软通           |
| 4.3                    | Audio 栈：AudioFlinger / AudioPolicy / 车机多 zone              | 紫光展锐、车载音频         |
| 4.4                    | 编解码基础与低延迟播放（tunneled playback）                             | 北弓、软通             |
| 4.5                    | 流媒体协议：RTP/RTSP/RTMP/SRT/WebRTC                             | 北弓智能              |
| 4.6                    | FFmpeg / ExoPlayer / GStreamer                             | 软通、紫光展锐           |
| 4.7                    | OpenGL ES / Vulkan / Skia / Shader                         | 小米图形渲染、软通         |
| **第五部分：跨端与鸿蒙**         |                                                            |                   |
| 5.1                    | React Native 原理与容器优化                                       | 中瑞集团              |
| 5.2                    | Flutter 原理                                                 | 汉桑、中瑞             |
| 5.3                    | HarmonyOS：ArkTS / Stage 模型 / 分布式                           | 软通、鱼跃、易宝          |
| **第六部分：把知识串成面试叙事**     |                                                            |                   |
| 6.1                    | 三轮实战叙事模板（ANR / 属性不更新 / 死机重启）                               | 通用                |
| 6.2                    | 45 分钟技术面的问题地图                                              | 通用                |

### 阅读建议

按你的背景，**建议顺序是 1 → 2 → 3.8 → 4.3 → 6**，其余按需查阅。

理由：第 1 部分是你日常在做的，但它会把你的认知从"调 SDK"补到"链路完整"；第 2 部分是 A 档岗位的门槛；3.8 和 4.3 是车机岗最高频的追问点；第 6 部分是把你已有经验变成可复述的资产。

---

# 第一部分：车机与座舱体系

## 1.1 Android Automotive（AAOS）全景与手机 Android 的本质差异

### 【JD 出处】

- 光庭信息：*"负责智能座舱 Android 系统 Framework 层、HAL 层核心模块的定制开发"*
- 经创集团：*"负责车机 Android Car Service、VHAL 相关模块开发、迭代与维护"*
- 华苏科技：*"熟悉 Car Service、仪表投屏等中间件 Service 开发"*

### 【要解决的问题】

手机 Android 假设"用户拿着手机、一次只用一个 App、网络始终可用"。车机完全不成立：

| 维度   | 手机 Android | Android Automotive                                     |
| ---- | ---------- | ------------------------------------------------------ |
| 电源   | 电池，用户主动充电  | 12V 电瓶，熄火后仍要待机；有 **STR（Suspend to RAM）** 与 **快速启动** 诉求 |
| 车辆信号 | 无          | 车速、挡位、车门、空调、胎压、灯光……必须从 HAL 上来                          |
| 用户   | 一次一个前台 App | 多用户、多屏、多音区（前排/后排）并存                                    |
| 安全   | 崩溃退出即可     | 驾驶中限制交互（**UX Restrictions**）、倒车影像必须 <2s 出图             |
| 生命周期 | 跟着用户操作     | 跟着 **点火状态**（OFF / ACC / ON / RUN）走                     |
| 音频   | 一个音源       | 多音区独立音源，且有**抢占与 ducking**                              |

所以 AAOS 不是在手机 Android 上加了几个 App，而是**在 Framework 层插入了一整套"车辆语义"**。


### 【原理】

AAOS 的架构是在原生 Android 上叠加两层：

```
┌──────────────────────────────────────────────┐
│  Car Apps（HVAC、Settings、Launcher、Media）  │
├──────────────────────────────────────────────┤
│  Car API（android.car.*）                     │
│    Car / CarPropertyManager / CarAudioManager │
├──────────────────────────────────────────────┤
│  CarService（独立进程 com.android.car）        │
│    ├── CarPropertyService   ← 车辆属性         │
│    ├── CarAudioService      ← 多音区           │
│    ├── CarPowerManagementService ← 电源/STR     │
│    ├── CarUxRestrictionsManagerService ← 驾驶限制│
│    ├── CarPackageManagerService ← 白名单       │
│    └── ...                                    │
├──────────────────────────────────────────────┤
│  VehicleHal（Java 层代理）                     │
├──────────────────────────────────────────────┤
│  Vehicle HAL（HIDL / AIDL，独立 native 进程）   │
├──────────────────────────────────────────────┤
│  CAN 网关 / MCU / 域控制器                     │
└──────────────────────────────────────────────┘
```

两个关键认知：

**第一，CarService 是一个独立进程，不是 system_server 的一部分。**  
它的进程名是 `com.android.car`，通过 `CarServiceHelperService`（跑在 system_server 里）被 bind 起来。这个设计是为了**故障隔离**：CarService 崩了不应该拖垮 system_server（但实际上 CarService 死了会导致车控功能全失效，所以它有自己的"看门狗"逻辑——`ICarImpl` 里的服务会互相监督）。

**第二，CarService 内部的每个服务都注册了两套 Binder 接口。**  
以属性服务为例，它有 `CarPropertyService`（给 App 用的、带权限校验的接口）和 `PropertyHalService`（挂在 CarService 内部、给 HAL 通信用）——这是"对外收口、对内留缝"的典型设计。

### 【关键调用链】

App 获取车速的最小链路：

```java
// App 侧
Car car = Car.createCar(context);
CarPropertyManager mgr = (CarPropertyManager) car.getCarManager(Car.PROPERTY_SERVICE);
float speed = mgr.getProperty(float.class, VehiclePropertyIds.PERF_VEHICLE_SPEED, 0);
```

展开后：

```
App
 └─ CarPropertyManager.getProperty()
     └─ CarPropertyManager.mCarPropertyService.getProperty()   // ICarProperty (Binder)
         └─ [Binder 跨进程到 com.android.car]
             └─ CarPropertyService.getProperty()
                 ├─ 权限检查（checkPermission / Car.PERMISSION_CAR_SPEED）
                 └─ PropertyHalService.getProperty()
                     └─ VehicleHal.get()
                         └─ IVehicle.getValues() / get()  // HIDL 或 AIDL
                             └─ [跨进程到 Vehicle HAL native 进程]
                                 └─ 读取共享内存 / 请求 MCU
```

### 【常见追问】

**Q：CarService 为什么不做进 system_server？**  
隔离故障 + 降低 system_server 启动时间 + 便于按车型定制（不同 OEM 的 CarService 差异极大，不应该污染 AOSP 主干）。

**Q：`Car.createCar()` 做了什么事？**  
通过 `ICarServiceHelper` / `CarServiceHelperService` 拿到 `ICar` 的 Binder 代理，并建立连接回调（`ServiceConnection`）。如果 CarService 未就绪，`createCar` 会返回一个"未连接"的 Car 对象，后续 `getCarManager` 会抛异常——**这是车机 App 崩溃的高频原因之一**。

**Q：Android 13/14 的 AAOS 有什么大变化？**  
两个方向：一是 **Vehicle HAL 从 HIDL 迁到 AIDL**（`android.hardware.automotive.vehicle` AIDL 化）；二是引入 **Zone（`VehicleAreaSeat` 的 zone 细化）** 和 **`CarPropertyManager` 的权限粒度收紧**。CarService 侧出现 `VehicleStub` 抽象层来同时兼容 HIDL/AIDL。

### 【延伸方向】

- 源码入口：`packages/services/Car/`（CarService）、`hardware/interfaces/automotive/vehicle/`
- 实验：在 AAOS 模拟器（Android Studio 的 Automotive with Play Store 镜像）上跑 `adb shell dumpsys car_service`，看所有服务与属性配置
- 论文级阅读：阅读 `car-lib/` 下的 `CarPropertyManager.java`，把所有 `PERF_*` 属性和对应权限列一遍

---

## 1.2 CarService 架构与启动流程

### 【JD 出处】

- 经创集团：*"精通 Android 四大组件、AIDL 跨进程通信机制；熟悉 Android Car Service、VHAL 架构与运行原理"*
- 光庭信息：*"熟悉 Android AOSP 编译、裁剪、定制流程，精通 Framework 系统层开发 或 HAL 层（HIDL/AIDL）硬件适配开发"*

### 【要解决的问题】

车机开机后，从"Linux 内核起来"到"空调 App 能读到车内温度"，中间需要一条可预测、可观测、可容错的启动路径。CarService 就是这个路径的宿主。


### 【原理】

#### 启动时序

```
BootROM → bootloader → kernel → init
  → zygote → system_server（SystemServer.main）
      → startBootstrapServices()
      → startCoreServices()
      → startOtherServices()          ← CarServiceHelperService 在这里
          └─ CarServiceHelperService 通过 config 数组拿到 CarService 的组件名
              └─ bindService(CarService)   // 跨进程
                  └─ [com.android.car 进程被 fork 出来]
```

`CarServiceHelperService` 的关键工作是：**在 SystemServer 侧等待 CarService 完成初始化**，然后把自己作为 `ICarServiceHelper` 注册给 CarService，用于反向调用（比如监听 system_server 的某些状态、代理权限检查）。

#### CarService 内部：`ICarImpl` 的"造车"过程

```java
// packages/services/Car/service/src/com/android/car/CarService.java
public void onCreate() {
    ...
    mCarImpl = new ICarImpl(this, mVehicle /* VehicleStub */, mSystemInterface, ...);
    mCarImpl.init();
}
```

`ICarImpl` 的构造函数是一份"服务清单"，顺序很重要（后面的服务依赖前面的）：

```java
// 简化示意
mCarPropertyService       = new CarPropertyService(serviceContext, mVehicle);
mCarAudioService          = new CarAudioService(serviceContext, mCarPropertyService, ...);
mCarPowerManagementService= new CarPowerManagementService(vhalInterface, ...);
mCarUxRestrictionsManager = new CarUxRestrictionsManagerService(serviceContext, mCarPropertyService);
mCarPackageManagerService = new CarPackageManagerService(serviceContext, mCarUxRestrictionsManager);
mCarDiagnosticService     = new CarDiagnosticService(serviceContext, mVehicle);
mCarProjectionService     = new CarProjectionService(...);   // 手机投屏
...
```

这些服务之间有三种耦合方式，值得记牢：

1. **直接持有引用**（同进程，如 `CarPackageManagerService` 依赖 `CarUxRestrictionsManagerService`）
2. **订阅属性回调**（如 `CarPowerManagementService` 订阅 `GEAR_SELECTION`、`PERF_VEHICLE_SPEED` 判断是否需要休眠）
3. **发布系统状态**（如 `CarUxRestrictionsManagerService` 通过 `ContentProvider` / `CarUxRestrictionsManager` 对外发布驾驶限制）

#### "列车"概念的由来

CarService 这个类名来自 Google 内部的比喻：**每一个 Car 服务就像一节车厢，`ICarImpl` 是机车，`init()` 是挂接车厢的过程**。所以你在源码里会看到 `CarService` 的注释和 `mCarImpl` 的命名风格。这个比喻不是修辞——它准确描述了"服务按顺序初始化、互相依赖、整体作为一个进程运行"的事实。

### 【常见追问】

**Q：CarService 崩了会怎样？**  
四条后路：① `CarServiceHelperService` 会重新 bind（`ServiceConnection.onServiceDisconnected` → 重连）；② 正在使用 Car API 的 App 会收到 `NullPointerException` 或 `IllegalStateException`；③ 依赖车辆信号的系统功能（空调、仪表）会降级或冻结；④ 严重时会触发重启。**所以在车机项目里，CarService 的稳定性优先级等价于 system_server。**

**Q：`CarServiceHelperService` 与 CarService 之间为什么需要双向 Binder？**  
因为有些判断必须在 system_server 侧做——例如权限授予（需要 `PackageManager` 与 `AppOpsManager`）、`Activity` 启动拦截（`CarPackageManagerService` 需要知道前台 Activity）。CarService 通过反向接口回调 system_server，避免把 `ActivityManagerService` 的职责复制一份。

**Q：为什么 `ICarImpl` 里服务顺序这么讲究？**  
因为 `CarAudioService` 构造时需要 `CarPropertyService` 来订阅 `AUDIO_VOLUME` 类属性；`CarPackageManagerService` 构造时需要 `CarUxRestrictionsManagerService`。**顺序错了会在 init 阶段直接抛 NPE，表现是"开机后车控全废但系统看起来正常"** —— 这是车机 bring-up 阶段最常见的坑。

### 【延伸方向】

- 阅读 `ICarImpl.java` 的完整服务列表，对照 `Car.java` 里的 `*_SERVICE` 常量，做一个映射表
- 实验：`adb shell dumpsys car_service` 看每个服务的状态；`adb shell dumpsys car_service --services` 列出全部服务名
- 延伸：阅读 `CarServiceHelperService` 在 `services/core/java/com/android/server/` 下的实现（它在 framework 侧，不在 CarService 包里）

---

## 1.3 CarPropertyManager → CarPropertyService → VehicleHal 完整调用链

### 【JD 出处】

- 经创集团：*"负责车机 Android Car Service、VHAL 相关模块开发"*
- 光庭信息：*"至少擅长车载任一核心模块：车控 CarService/VehicleHAL"*

### 【要解决的问题】

车辆信号有几百上千个（车速、转速、油量、每个车门状态、每个座椅加热档位、每个车窗位置……）。这些信号：

- 变化频率差别巨大（车速 100Hz，车门状态几年变一次）
- 数据类型不同（bool / int / float / int[] / float[] / String / byte[]）
- 有权限差异（车速所有人都能读，车门控制只有系统 App 能写）
- 分布在不同的 ECU 上

如果没有统一抽象，每个 App 都要自己对接 MCU —— 所以诞生了 **VHAL 属性系统**：把所有车辆信号统一建模成**带 ID、带区域、带访问权限、带变化模式的对象**。


### 【原理】

#### 三层抽象

```
CarPropertyManager        （App 侧的便捷封装，类型安全）
      ↕ ICarProperty (Binder)
CarPropertyService        （CarService 内，做权限、类型、默认值、缓存）
      ↕
PropertyHalService        （CarService 内，做属性过滤与订阅管理）
      ↕
VehicleHal (Java)         （CarService 内，HIDL/AIDL 客户端封装）
      ↕ IVehicle (HIDL/AIDL)
Vehicle HAL (native 进程)  （真正与 MCU/CAN 通信）
```

#### 读属性的完整链路（带权限检查）

```java
// App
CarPropertyManager mgr = car.getCarManager(Car.PROPERTY_SERVICE);
int gear = mgr.getProperty(int.class, VehiclePropertyIds.GEAR_SELECTION, VehicleAreaSeat.SEAT_ROW_1_LEFT);
```

```
1. CarPropertyManager.getProperty(Class, propId, areaId)
     └─ 内部转到 mCarPropertyService.getProperty(propId, areaId)   // 跨进程

2. CarPropertyService.getProperty()
     ├─ assertPropertyPermission(propId, areaId)   ← 关键：权限检查在这
     │     通过 Car.PERMISSION_* 与 VehiclePropertyAccess 比对
     ├─ 类型校验：config.getPropertyType() 与请求的 Class 是否一致
     └─ PropertyHalService.getProperty()

3. PropertyHalService.getProperty()
     ├─ 属性过滤器 FilteringSensor 判断是否需要"刷新"（针对 ON_CHANGE 属性）
     └─ VehicleHal.get(propId, areaId)

4. VehicleHal.get()   // Java 层
     └─ mVehicle.getValues() / mVehicle.get(...)   // HIDL/AIDL 调用

5. Vehicle HAL native
     ├─ 从共享内存 / socket / SPI 拿 MCU 上报的数据
     └─ 组装 VehiclePropValue { prop, areaId, timestamp, status, value }

6. 返回值沿原路返回，同时 VehicleHal 会触发对应的 callback（如果有订阅）
```

#### 写属性的链路（多了"能力校验"）

```java
mgr.setProperty(boolean.class, VehiclePropertyIds.HVAC_AC_ON, areaId, true);
```

```
CarPropertyManager.setProperty()
  → CarPropertyService.setProperty()
      ├─ 权限检查（写权限比读权限严格得多，通常是 signature|privileged）
      ├─ 检查该属性是否在"当前电源状态"下可写
      └─ PropertyHalService.setProperty()
          → VehicleHal.set()
              → IVehicle.set()  → HAL → MCU → CAN 帧发出
```

注意：**写是异步的**。`setProperty` 返回时只是"命令已下发"，属性真正变化会通过订阅回调通知你。忘记订阅、直接轮询，是车机应用开发中最常见的 bug 来源。

### 【常见追问】

**Q：`getProperty` 会不会阻塞？**  
会。它是同步 Binder 调用，一路同步到 HAL。如果 HAL 卡住（比如 MCU 通信超时），**App 主线程会被挂住 5 秒以上直至 ANR**。所以规范做法是：在子线程读，或优先用订阅。这一点在车机上比手机上更致命，因为 MCU 通信本身就有毫秒到几十毫秒的抖动。

**Q：为什么要有 `PropertyHalService` 这一层，不能直接 `VehicleHal`？**  
两层职责不同：`VehicleHal` 是"通信管道"，`PropertyHalService` 是"业务策略层"——它负责订阅去重（多个 App 订阅同一属性只往 HAL 订一次）、采样率协商（取所有订阅者的最大 rate）、以及 ON_CHANGE 属性的"惰性刷新"。这层是**跨进程调用量的放大器/收敛器**。

**Q：`CarPropertyValue.getStatus()` 有哪些值，什么时候会出现非 `AVAILABLE`？**  
`AVAILABLE` / `UNAVAILABLE` / `ERROR`。ECU 未上电、信号超时、MCU 未上报时会得到 `UNAVAILABLE`。**App 必须处理 UNAVAILABLE，否则会拿默认值渲染出错误 UI**（例如车速显示 0 而实际在跑）。

**Q：属性有缓存吗？**  
Java 层有。`VehicleHal` 会缓存 `STATIC` 和 `ON_CHANGE` 属性的最后值，对 `CONTINUOUS` 属性则受采样率控制。`CarPropertyService` 侧也维护 `CarPropertyConfig` 的缓存。**所以要分清"读到的是缓存还是实时值"** —— 对 `ON_CHANGE` 属性，`getProperty` 返回的是最后一次上报值。

### 【延伸方向】

- 源码：`packages/services/Car/car-lib/src/android/car/hardware/property/` 下 `CarPropertyManager` / `CarPropertyValue` / `CarPropertyConfig`
- 源码：`packages/services/Car/service/src/com/android/car/hal/` 下 `VehicleHal` / `PropertyHalService`
- 实验：用 AAOS 模拟器的 VHAL 模拟器（`emulator` 的 `vehicle` 参数或 `VehicleHal` 的 fake 实现）动态改属性值，观察回调
- 延伸阅读：`hardware/interfaces/automotive/vehicle/aidl/` 下的 `IVehicle.aidl` 与 `VehicleProperty.aidl`

---

## 1.4 VHAL 属性系统：属性、Area、配置、采样率、订阅回调

### 【JD 出处】

- 经创集团：*"熟悉 Android Car Service、VHAL 架构与运行原理，有 CAN/SOMEIP/DDS 车载信号对接实战经验"*
- 光庭信息：*"熟悉 Android AOSP 编译、裁剪、定制流程……精通 HAL 层（HIDL/AIDL）硬件适配开发"*

> 这一节是全文中**面试回报率最高的一节**。因为它是"应用层候选人答不上、系统层候选人天天用"的分水岭。

### 【要解决的问题】

如何用一套数据模型统一描述"车速"和"左后车门是否锁上"和"空调出风方向"？


### 【原理】

#### 四要素：属性 ID + Area + 配置 + 值

```java
// 一个完整的属性标识 = (propId, areaId)
// 一个属性值 = (propId, areaId, status, timestamp, value)
```

**① 属性 ID（`VehiclePropertyIds`）**

命名有规律，按功能域分段（`0x0xxx` 系统、`0x1xxx` 信息、`0x2xxx` 动力、`0x3xxx` 车身……一直到 `0x4xxx` 各类，以及厂商自定义的 vendor 段）：

| 段位              | 域         | 例子                                                                 |
| --------------- | --------- | ------------------------------------------------------------------ |
| `0x0100–0x01FF` | Info      | `PERF_VEHICLE_SPEED`、`PERF_ODOMETER`、`GEAR_SELECTION`、`ENGINE_RPM` |
| `0x0200–0x02FF` | 动力        | —                                                                  |
| `0x0300–0x03FF` | 车身        | `DOOR_LOCK`、`DOOR_POS`、`WINDOW_POS`、`HVAC_AC_ON`、`HVAC_FAN_SPEED`  |
| `0x0400–0x04FF` | 灯光/状态     | `TURN_SIGNAL_STATE`、`NIGHT_MODE`                                   |
| `0x0500–0x05FF` | 驾驶        | `CURRENT_GEAR`、`PARKING_BRAKE_ON`                                  |
| `0x0600–0x06FF` | 车辆        | `TIRE_PRESSURE`                                                    |
| `0x0A00–0x0AFF` | 厂商自定义     | `VENDOR_*`                                                         |
| `0x1xxxxxxx`    | 厂商扩展段（高位） | —                                                                  |

**② Area（区域）**

一个属性可以有不同的作用区域。`VehicleArea` 定义了区域类型：

| 区域类型     | 用途    | 例子                      |
| -------- | ----- | ----------------------- |
| `GLOBAL` | 整车唯一  | 车速、挡位                   |
| `WINDOW` | 每个车窗  | `WINDOW_POS`            |
| `MIRROR` | 每个后视镜 | `MIRROR_Z_POS`          |
| `SEAT`   | 每个座椅  | `HVAC_SEAT_TEMPERATURE` |
| `DOOR`   | 每个车门  | `DOOR_LOCK`             |
| `WHEEL`  | 每个轮   | 胎压                      |
| `VENDOR` | 厂商定义  | 自定义区域                   |

Area 的编码方式很巧妙——**位段组合**：

```java
// packages/services/Car/car-lib/src/android/car/VehicleAreaSeat.java
// 行与列分别占位，然后按位或
public static final int SEAT_ROW_1_LEFT   = 0x0001;
public static final int SEAT_ROW_1_RIGHT  = 0x0002;
public static final int SEAT_ROW_2_LEFT   = 0x0010;
public static final int SEAT_ROW_2_RIGHT  = 0x0020;

public static int createAreaId(int seats) {
    return VehicleAreaType.VEHICLE_AREA_TYPE_SEAT << 24 | (seats & 0x00FFFFFF);
}
```

`0x0001`、`0x0002`、`0x0010`、`0x0020` 的位模式说明：**低位是行内位置，高四位中的一位是行号**。所以组合座椅（如"主驾+副驾"）就是 `0x0003`——这是典型的"位图表达集合"设计。

> Android 14 起引入 **Zone** 概念，进一步细化（不再只有"座椅"一个维度，而是"座位区/行"的更细划分），面试时可以提一句"知道 zone/area 的演进方向"。

**③ 属性配置（`VehiclePropConfig` / `CarPropertyConfig`）**

每个属性在 HAL 层都有一份 `config`，定义它的行为契约：

| 字段                                | 含义                                    |
| --------------------------------- | ------------------------------------- |
| `prop`                            | 属性 ID                                 |
| `access`                          | `READ` / `WRITE` / `READ_WRITE`       |
| `changeMode`                      | `STATIC` / `ON_CHANGE` / `CONTINUOUS` |
| `areaType`                        | 区域类型                                  |
| `configArray` / `configString`    | 依赖其他属性的 ID、支持的区域位图、枚举值等               |
| `minSampleRate` / `maxSampleRate` | 采样率范围（Hz），`0` 表示"只在变化时上报"             |
| `supportedAreas`                  | 支持的区域列表                               |

**这份配置是整个 VHAL 的"契约层"**，它决定了：写代码时能调哪些属性、以什么类型调、能不能订阅、以多高的频率订阅。

**④ 变化模式（`changeMode`）—— 面试最喜欢问的点**

| 模式           | 语义        | 典型属性                                                           | 订阅行为                   |
| ------------ | --------- | -------------------------------------------------------------- | ---------------------- |
| `STATIC`     | 永不变化，只读一次 | `INFO_VIN`、`INFO_MAKE`、`INFO_MODEL`                            | 不可订阅，`getProperty` 拿一次 |
| `ON_CHANGE`  | 值变化时才上报   | `GEAR_SELECTION`、`DOOR_LOCK`、`HVAC_AC_ON`                      | 订阅后只在变化时回调；`rateHz=0`  |
| `CONTINUOUS` | 周期性上报     | `PERF_VEHICLE_SPEED`、`PERF_VEHICLE_SPEED_DISPLAY`、`ENGINE_RPM` | 订阅时必须给 `rateHz`（Hz）    |

**这一条为什么重要？** 因为它直接决定"为什么不能高频轮询车速"：

- `ON_CHANGE` 属性如果轮询，你会在"值没变"的情况下产生大量的跨进程调用 → 无意义功耗 + Binder 流量
- `CONTINUOUS` 属性如果没有订阅率控制，100Hz 上报会让 Binder 与 App 都被打爆 —— 正确做法是订阅时给出**你真正需要的频率**（UI 刷新 10Hz 足够，控制逻辑可能要 50Hz）
- 反过来，`ON_CHANGE` 属性如果去轮询，会在值没变的情况下产生大量跨进程调用 → 无意义功耗 + Binder 流量

**⑤ 采样率协商**

```java
// 订阅时给 rateHz
mgr.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, 10); // 10Hz
```

`PropertyHalService` 会把所有订阅者的 rate 取**最大值**下发给 HAL（因为 HAL 只能给一个频率，必须满足最快的那位），每个订阅者再按自己请求的 rate 做**降频过滤**：

```
App A 订阅 5Hz  ┐
App B 订阅 20Hz ├→ HAL 下发 20Hz → PropertyHalService 按各订阅者 rate 节流
App C 订阅 10Hz ┘
```

这是一个非常漂亮的"共享一个高频源、各自降频"的设计，**是答"VHAL 架构为什么这样设计"的标准答案**。

#### 订阅与回调

```java
CarPropertyManager.CarPropertyEventCallback callback =
    new CarPropertyManager.CarPropertyEventCallback() {
        @Override
        public void onChangeEvent(CarPropertyValue value) {
            // value.getPropertyId() / getAreaId() / getValue() / getStatus() / getTimestamp()
        }
        @Override
        public void onErrorEvent(int propId, int areaId) {
            // 属性失效/被移除
        }
    };

mgr.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, 10);
// 不再需要时
mgr.unregisterCallback(callback);
```

回调的执行线程需要注意：**回调在 Binder 线程池的线程上执行**，不是主线程。所以：

- 不能直接更新 UI（要么 `Handler` 切主线程，要么用 `LiveData`/`Flow` 转换）


- 回调里不要做耗时操作，否则会占住 Binder 线程，影响其他跨进程调用

#### 一个完整的可运行示例（车机应用侧规范写法）

```kotlin
class SpeedRepository(private val car: Car) {
    private val mgr = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
    private val _speed = MutableStateFlow(0f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val status = value.status
            if (status == CarPropertyValue.STATUS_AVAILABLE) {
                _speed.value = value.value as Float
            } else {
                // 关键：不能把失效值当作 0 渲染出去
                Log.w(TAG, "speed unavailable, status=$status")
            }
        }
        override fun onErrorEvent(propId: Int, areaId: Int) {
            Log.e(TAG, "speed property error: $propId/$areaId")
        }
    }

    fun start() {
        // 订阅前先确认属性存在，避免 IllegalArgumentException
        if (!mgr.isPropertyAvailable(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0)) return
        // 10Hz 足够驱动 UI，别直接要 100Hz
        mgr.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, 10f)
    }

    fun stop() = mgr.unregisterCallback(callback)
}
```

### 【常见追问】

**Q：怎么判断一个属性该用 `getProperty` 还是订阅？**  
一句话规则：**`STATIC` 用 get，`ON_CHANGE` 用订阅，`CONTINUOUS` 用订阅且必须给 rate**。反过来做都会有问题（轮询 ON_CHANGE 浪费、get 拿 CONTINUOUS 拿不到最新值）。

**Q：多个 App 订阅同一属性，HAL 会收到几次订阅？**  
一次。`PropertyHalService` 做了聚合，取最大 rate 下发，各订阅者本地节流。

**Q：`subscribePropertyEvents` 和 `registerCallback` 是什么关系？**  
历史遗留。`registerCallback` 是较新的 API（Car API 层，带 rate 与 area 参数），`subscribePropertyEvents` 是更早期的 HIDL 时代 API。新代码应该用 `registerCallback`。**面试时能说出这个"新旧 API 并存"的事实，会显得你是真读过源码而不是背文档。**

**Q：怎么新增一个自定义车辆属性？**  
四步：① 在 `VehicleProperty`（或 vendor 的 AIDL 定义）里分配一个 `VENDOR_*` ID（必须是厂商段，且不能与已有冲突）；② 在 HAL 的 `config` 里声明该属性（access / changeMode / areaType / rate）；③ HAL 实现 `get`/`set`（HIDL 为 `get`/`set`，AIDL 为 `getValues`/`setValues` 或新版 `get`/`set`）；④ CarService 侧如需业务封装，加一个专用 Service。**再加一步**：定义对应的 `Car.PERMISSION_*` 权限并在 `config` 里给它 `owner`。

**Q：`CONTINUOUS` 属性订阅后，怎么知道"这一帧和上一帧是不是同一个值"？**  
比 `value`，但更可靠的是比 `timestamp`。**注意 `CONTINUOUS` 属性即使值不变也会上报**（周期触发），所以"值不变就不刷新 UI"要靠自己去重。

### 【延伸方向】

- 必读：`hardware/interfaces/automotive/vehicle/aidl/android/hardware/automotive/vehicle/VehicleProperty.aidl`（属性全景）
- 必读：`VehicleAreaSeat.java` / `VehicleAreaDoor.java` 的位段编码
- 实验：写一个最小的"属性浏览器" App，列出所有可用属性、类型、changeMode、areaType（用 `CarPropertyManager.getPropertyList()`）
- 延伸：读 `CarPropertyConfig.getChangeMode()` 与 `PropertyHalService` 的 rate 协商代码

---

## 1.5 CarService 其他核心服务：音频、电源、UX 限制、包管理

### 【JD 出处】

- 光庭信息：*"覆盖车控、音频、网络、相机、车载诊断、AOSP 系统定制等车载核心业务场景"*
- 华苏科技：*"熟悉 Android 多媒体、时间同步、诊断、Car Service、仪表投屏等中间件 Service 开发经历"*

### 1.5.1 CarAudioService —— 车机音频为什么和手机完全不同

**要解决的问题**：车里有多个座位，后排想听歌、前排想听导航、副驾想看电影。同时导航播报必须以最高优先级打断音乐（并且音乐音量要"压下去"而不是停掉——这就是 **ducking**）。

**核心模型四件套**：

| 概念                | 含义                                                                     |
| ----------------- | ---------------------------------------------------------------------- |
| `CarAudioZone`    | 音区（物理输出设备组），如前排 zone、后排 zone                                           |
| `CarAudioContext` | 音频上下文（逻辑用途），如 `MUSIC`、`NAVIGATION`、`PHONE_CALL`、`ALARM`、`SYSTEM_SOUND` |
| `CarVolumeGroup`  | 音量组，把多个 context 绑定到同一个音量控制                                             |
| `CarAudioFocus`   | 音频焦点策略（车机版，比手机的 `AudioFocus` 更复杂，需要处理"跨 zone 焦点")                      |

**关键链路**：

```
App 创建 AudioTrack(usage = USAGE_MEDIA)
  → AudioFlinger 根据 audio attributes + 设备地址路由
  → AudioPolicyService 决定输出设备
  → CarAudioService 根据 CarAudioZone / CarVolumeGroup 决定
      ① 路由到哪个 zone
      ② 受到哪个 volume group 控制
      ③ 焦点被谁抢占 / 是否要 duck
```

**为什么这个岗位值钱**：手机 Android 的音频焦点是"一个赢家独占"；车机是"多方共存 + 相互压制 + 分区隔离"。这一套逻辑只在 AAOS 里存在，**做过车机音频的人在国内是稀缺的**。

### 1.5.2 CarPowerManagementService —— 车机的电源状态机

**要解决的问题**：车机不能"关机"，熄火后要立刻进入待机（STR），下次点火 2 秒内必须能出倒车影像。这是手机 Android 里完全不存在的问题域。

**核心概念**：

| 概念                          | 含义                                                                      |
| --------------------------- | ----------------------------------------------------------------------- |
| `VehicleApPowerState`       | AP 电源请求状态：`OFF`、`DEEP_SLEEP`、`ON_DISP_OFF`、`ON_FULL`、`SHUTDOWN_PREPARE` |
| `VehicleApPowerStateReport` | 上报给 VHAL 的实际状态（用于 MCU 决定是否断电）                                           |
| `GarageMode`                | "车库模式"：熄火后仍保持一段时间的后台任务（升级、数据上报）                                         |
| STR（Suspend to RAM）         | 内存保持供电，CPU 休眠，唤醒速度快                                                     |
| `Hibernation`（Android 14+）  | 内存落盘，比 STR 更省电但唤醒更慢                                                     |

**关键交互**：MCU 通过 VHAL 下发 `AP_POWER_STATE_REQ`（"我要断电了，你准备一下"），CarPowerManagementService 收到后：

```
1. 通知所有 App/Service 准备（onSuspend）
2. 关闭屏幕、暂停非必要任务
3. 上报 AP_POWER_STATE_REPORT → MCU 才真正切断供电
4. 唤醒时反向：MCU 上电 → AP 从 STR 恢复 → 快速恢复各服务
```

**这一步是"车机与手机最本质的差异"** —— 也是答"为什么车机 Framework 需要专门定制"的最佳论据。

### 1.5.3 CarUxRestrictionsManagerService —— 驾驶分心限制

**要解决的问题**：车速 > 某阈值时，不允许用户在屏幕上打 20 个字。法规（NHTSA、中国 GB 等）对这有硬要求。

**原理**：

```
订阅 VHAL 属性 PERF_VEHICLE_SPEED / GEAR_SELECTION / PARKING_BRAKE_ON
  → 计算当前 UxRestrictions { restrictions bitmask }
      UX_RESTRICTIONS_FULLY_RESTRICTED
      UX_RESTRICTIONS_NO_VIDEO
      UX_RESTRICTIONS_NO_TEXT_MESSAGE
      UX_RESTRICTIONS_LIMIT_STRING_LENGTH
      UX_RESTRICTIONS_NO_KEYBOARD
      ...
  → 通过 CarUxRestrictionsManager 发布给 App
  → App 侧用 CarUxRestrictionsManager.getCurrentCarUxRestrictions() 或监听回调
  → 也可以直接交给 CarUxRestrictionsUtil / CarUxRestrictionsConfiguration
```

**关键实现**：`CarUxRestrictionsManagerService` 内部用 `CarUxRestrictionsConfiguration` 表达"某车速下有哪些限制"，这份配置来自车型定义。**不同车型的限制矩阵不同，这是 OEM 定制的高频点。**

### 1.5.4 CarPackageManagerService —— 驾驶时的应用白名单

**要解决的问题**：驾驶时不允许启动某些 App（比如抖音），或者说"只有白名单内的 App 才允许在当前驾驶状态下显示"。

**原理**：

```
CarPackageManagerService
  ├─ 从 config 读取"允许在驾驶时使用的 Activity 列表"（blacklist/whitelist）
  ├─ 拦截 Activity 启动（通过 ActivityManager 的 startActivity 拦截机制）
  ├─ 订阅 UxRestrictions 变化，动态调整拦截规则
  └─ 对违规启动返回一个"安全提示 Activity"
```

**与用户的关联点**：你的项目里维护 priv-app 系统应用（`sharedUserId=android.uid.system`），**系统应用天然在 `CarPackageManagerService` 的免检名单里**——这类"系统应用的特权行为"在车机岗面试里是很硬的谈资。

### 【常见追问】

**Q：车机的音频焦点比手机复杂在哪？**  
三个维度：① **多 zone**（分区隔离，前排静音不影响后排）；② **抢占与 duck**（导航播报压低音乐而不是停止音乐）；③ **系统级强制优先级**（倒车雷达、ADAS 报警不可被任何 App 抢占）。

**Q：`CarPowerManagementService` 与 Android 的 `PowerManagerService` 是什么关系？**  
上下游关系。`PowerManagerService` 管的是"Android 内部的电源策略"（WakeLock、Doze、屏幕）；`CarPowerManagementService` 管的是"整车电源状态"，它会**接收整车请求并驱动** `PowerManagerService`（如请求关机、请求进入 suspend）。两者通过 `SystemInterface` 交互。

**Q：怎么调试"熄火后 CarService 没进休眠"？**  
`adb shell dumpsys car_service --power` 看电源状态机；检查 `WakeLock`（`dumpsys power`）；检查 `GarageMode` 是否在跑；看 VHAL 的 `AP_POWER_STATE_REQ` 有没有下来（`dumpsys car_service --hal`）。**这类问题在车机项目里是日常，几乎每个 bring-up 都会遇到。**

### 【延伸方向】

- `packages/services/Car/service/src/com/android/car/audio/`（CarAudioService）
- `packages/services/Car/service/src/com/android/car/power/`（CarPowerManagementService）
- `packages/services/Car/service/src/com/android/car/pm/`（CarPackageManagerService）
- 延伸阅读：`packages/services/Car/car-lib/src/android/car/media/CarAudioManager.java` 的 zone API

## 1.6 车载总线：CAN 与 CAN FD

### 【JD 出处】

- 经创集团：*"对接 CAN、SOMEIP、DDS 车载信号，完成上层车机服务交互开发；有 CAN/SOMEIP/DDS 车载信号对接实战经验"*
- 小米汽车-MCU 高级开发工程师：*"有 CAN、UDS、NM、DoIP 开发应用经验者优先"*

### 【要解决的问题】

车上有几十到上百个 ECU（发动机、变速箱、ESP、空调、车窗、座椅、灯光……）。它们来自不同供应商，跑着不同的软件。**必须有一条标准总线让它们互相通信**，而且必须在强电磁干扰、线束长、成本敏感的整车环境里可靠工作。CAN 就是为此设计的。


### 【原理】

#### CAN 的三个核心设计

**① 差分信号 + 显性/隐性电平**

CAN_H / CAN_L 两根线，压差 >0.9V 为**显性（dominant，逻辑 0）**，压差 ≈0V 为**隐性（recessive，逻辑 1）**。差分传输抗共模干扰，这就是 CAN 能在发动机舱里活下来的原因。

**② 非破坏性仲裁（NDA）—— CAN 最精妙的设计**

CAN 报文开头是 11 位（标准帧）或 29 位（扩展帧）标识符。多个节点同时发送时：

```
节点 A 发 ID = 0x100 (二进制 001 0000 0000)
节点 B 发 ID = 0x120 (二进制 001 0010 0000)
                        ↑ 第 4 位：A 发 0（显性），B 发 1（隐性）
```

**显性覆盖隐性**：节点 B 在总线上读到 0 而自己发的是 1 → 立刻判定"我输了"，退出发送转为接收。**仲裁过程不破坏任何数据**，赢家继续发完，输家下一帧再试。

推论：**ID 越小优先级越高**。所以车上紧急报文（刹车、安全气囊）用很小的 ID。

**③ 位填充 + CRC + ACK**

- **位填充**：连续 5 个同极性位后强制插入 1 个反极性位，保证接收方能持续同步时钟
- **CRC15** 校验
- **ACK 槽**：任何正确接收的节点在 ACK 槽发显性位，发送方据此知道"至少有人收到了"

#### 报文结构（数据帧）

```
┌──────┬─────┬──────────┬───┬─────┬──────┬─────┬─────┐
│ SOF  │ ID  │ 控制域    │数据│ CRC │ ACK  │ EOF │ IFS │
│ 1bit │11bit│ 6bit     │0-8B│15bit│ 2bit │7bit │7bit │
└──────┴─────┴──────────┴───┴─────┴──────┴─────┴─────┘
                   ↑ DLC 表示数据长度（经典 CAN 最多 8 字节）
```

#### CAN FD（Flexible Data-rate）

|      | 经典 CAN     | CAN FD                              |
| ---- | ---------- | ----------------------------------- |
| 数据长度 | 最多 8 字节    | 最多 **64 字节**                        |
| 速率   | 全部 ≤1 Mbps | 仲裁段 ≤1 Mbps，**数据段可到 5–8 Mbps**（双速率） |
| CRC  | 15 位       | 17/21 位（按长度）                        |
| 用途   | 车身、动力      | 需要大数据量的域（ADAS、标定、刷写）                |

**为什么车机人要懂 CAN**：因为整车信号——车速、挡位、车门、空调、灯光——**全部以 CAN 报文的形式在总线上跑**。VHAL 的每一个属性，最终都对应总线上的一个或几个信号。理解 CAN 之后，"VHAL 属性是怎么来的"这个问题才算真正闭环。

#### 从"信号"到"属性"的映射（矩阵）

车载工程里有一份 **CAN Matrix（DBC / ARXML）**，定义了：

```
报文 ID 0x1A0（车速报文，周期 20ms）
  ├─ 信号 VehicleSpeed: 起始位 0, 长度 16, 精度 0.01, 偏移 0, 单位 km/h
  ├─ 信号 GearPosition: 起始位 16, 长度 4, 取值表 {0:P, 1:R, 2:N, 3:D}
  └─ ...
```

OEM / Tier1 的工作就是**把 DBC 里的信号映射到 VHAL 的 `VehicleProperty` 上**：

```
CAN 0x1A0.VehicleSpeed  ──映射──▶  PERF_VEHICLE_SPEED (0x0105)
CAN 0x1A0.GearPosition  ──映射──▶  GEAR_SELECTION (0x0101)
CAN 0x3B0.DoorLockFL    ──映射──▶  DOOR_LOCK (0x0301) + areaId = FRONT_LEFT
```

**这份映射表是车机项目里最核心的配置资产之一**，通常放在 HAL 层（`VehicleHal` 的 config 或 MCU 固件里）。

### 【常见追问】

**Q：CAN 总线忙了会怎样？**  
丢帧。CAN 没有重传机制（在数据链路层），靠上层（如 SOMEIP 的可靠传输、UDS 的重试）或者业务上容忍。**所以车载通信对"周期性 + 幂等"有强要求**：车速每 20ms 重发一次，丢一帧不影响。

**Q：为什么 CAN FD 要分成两个速率？**  
仲裁段必须保持低速（低速更容易实现无损仲裁，因为位时间必须覆盖最远节点的往返传播延迟），数据段只涉及已经仲裁成功的单一发送者，可以用高速率。**这是一个非常聪明的折中**，面试时能讲清"为什么不能全段高速"是加分项。

**Q：LIN、FlexRay、以太网在车上分别管什么？**

- **LIN**：单线、低速（≤20kbps）、主从、便宜 → 车窗、雨刮、后视镜
- **CAN**：多主、中速、可靠 → 车身、动力、诊断
- **CAN FD**：大带宽 CAN → ADAS、刷写
- **FlexRay**：时间触发、高确定性（10Mbps）→ 线控转向/制动（逐渐被以太网取代）
- **车载以太网**（100BASE-T1 / 1000BASE-T1，单对双绞线）：高带宽 → 摄像头、雷达、SOMEIP/DDS、DoIP、OTA

**Q：一个 App 直接读 CAN 报文可以吗？**  
不应该，也基本做不到。App 只能通过 VHAL 属性访问。**这是 AAOS 的架构约束**：App 层不允许直接碰总线，因为①总线带宽有限，不能让每个 App 都去订阅原始报文；②安全上有分级要求；③信号到属性的语义转换（精度、偏移、枚举）必须集中做一次。**面试如果被问"为什么不让 App 直接调 CAN"，这三个理由就是答案。**

### 【延伸方向】

- 概念：CAN 2.0B、ISO 11898-1/2/3、ISO 11898-2 的物理层
- 工具：Vector CANoe / CANalyzer、PCAN-USB、`candump`/`cansend`（Linux SocketCAN）
- 延伸：读 DBC 文件格式，自己解析一份简单的 DBC
- 延伸：Linux SocketCAN 的 `can-utils`，用 `candump can0` 实际抓一帧看

---

## 1.7 SOMEIP 原理与它在 Android 中的落点

### 【JD 出处】

- 经创集团：*"对接 CAN、SOMEIP、DDS 车载信号，完成上层车机服务交互开发"*

### 【要解决的问题】

CAN 只有 8（或 64）字节，且是"广播 + 无连接"。当车上出现需要传输结构化数据、大数据量、甚至"服务发现"的场景时（摄像头、雷达点云、OTA 包、SOA 服务调用），CAN 就不够了。

**SOMEIP（Scalable service-Oriented MiddlewarE over IP）** 就是为了在**车载以太网**上做**面向服务的通信（SOA）**&#x800C;设计的中间件协议，由 AUTOSAR 标准化。


### 【原理】

#### 核心思想

把车载功能抽象成"服务"，每个服务有方法（Method）、事件（Event）、字段（Field），通过 **服务发现（Service Discovery）** 动态找到彼此。

#### 协议栈位置

```
应用（SWC / App）
   ↓
SOMEIP（序列化、RPC、事件发布/订阅、SD）
   ↓
TCP / UDP（通常 UDP 做事件，TCP 做方法调用）
   ↓
IP → 车载以太网（100BASE-T1 / 1000BASE-T1）
```

#### 报文结构

```
┌─────────┬──────────┬───────────┬──────────┬──────────┬─────────┐
│ Message │ Request  │ Protocol  │ Interface│ Message  │ Return  │
│ ID(4B)  │ ID(4B)   │ Version   │ Version  │ Type     │ Code    │
├─────────┴──────────┴───────────┴──────────┴──────────┴─────────┤
│ Payload（序列化的方法参数 / 事件数据）                            │
└────────────────────────────────────────────────────────────────┘
```

关键字段：

| 字段             | 含义                                                                      |
| -------------- | ----------------------------------------------------------------------- |
| `Message ID`   | Service ID(16bit) + Method/Event ID(16bit)，全局唯一标识一次调用/事件                |
| `Request ID`   | Client ID(16bit) + Session ID(16bit)，用于把响应匹配回请求                         |
| `Message Type` | `REQUEST` / `REQUEST_NO_RETURN` / `NOTIFICATION` / `RESPONSE` / `ERROR` |
| `Return Code`  | 结果码（`E_OK`、`E_NOT_OK`、`E_UNKNOWN_SERVICE`…）                             |

#### 服务发现（SOMEIP-SD）

这是 SOMEIP 最有特色的部分。它本身也是 SOMEIP 报文（使用固定的 Service ID `0xFFFF`），通过多播/单播实现：

```
OfferService      服务提供者广播"我提供 Service 0x1234"
FindService       消费者询问"谁提供 Service 0x1234"
SubscribeEventgroup  消费者订阅某事件组
SubscribeEventgroupAck 提供者确认
StopOfferService  服务下线
```

**关键机制**：**TTL + 周期性 Offer**。服务每 N 秒重发一次 Offer（TTL 到期前），消费者如果连续错过 TTL 就认为服务下线。这个设计使"ECU 热插拔/重启"能被自动感知，不需要中心注册表。

#### 事件 vs 方法

| 类型         | 模式         | 传输        | 语义                           |
| ---------- | ---------- | --------- | ---------------------------- |
| **Method** | RPC，请求-响应  | TCP 或 UDP | 调用一次，有返回值（或 fire-and-forget） |
| **Event**  | 发布-订阅      | 通常 UDP 多播 | 值变化时推送                       |
| **Field**  | 方法 + 事件的组合 | —         | 有 getter/setter + 变化事件       |

#### 它在 Android 侧的位置

**Android 的 VHAL 并不直接用 SOMEIP**。真实的链路上，SOMEIP 在 Android 之外：

```
车机 Android（VHAL 属性） 
      ↕ HIDL/AIDL（Android 内部）
Vehicle HAL 进程
      ↕ 私有 IPC / SPI / 共享内存
MCU / 域控制器 / 网关
      ↕ SOMEIP over Ethernet
其他 ECU（ADAS 域、车身域、智驾域）
```

**更常见的情况**：MCU 或网关把 SOMEIP 收到的信号"翻译"成 VHAL 属性，Android 侧完全看不到 SOMEIP。但在**软件定义汽车（SDV）**&#x8D8B;势下，有些架构会把 SOMEIP 客户端直接放在 Vehicle HAL 或 CarService 里。

### 【常见追问】

**Q：SOMEIP 和 gRPC 有什么区别？**（高频对比题）  
四个核心差异：

|      | SOMEIP                | gRPC                     |
| ---- | --------------------- | ------------------------ |
| 传输   | UDP 优先（事件）/ TCP       | HTTP/2（长连接 TCP）          |
| 服务发现 | 内建（SD，多播 TTL）         | 依赖外部（K8s / Consul / DNS） |
| 序列化  | 固定布局（TLV 可选），无 schema | Protobuf（需 schema，带 tag） |
| 设计目标 | 车载实时性 + 确定性 + 无中心     | 通用 RPC + 跨语言 + 生态        |

**核心差异一句话**：SOMEIP **把服务发现做进了协议**，因为车上没有 DNS/注册中心，且 ECU 随时上下线；gRPC 假设有一个稳定的服务注册基础设施。

**Q：为什么事件用 UDP 而方法用 TCP？**  
事件是**周期性的、可丢的**（丢一帧下一个周期就补上），UDP 无连接开销小、无队头阻塞；方法是**单次的、不可丢的**，需要 TCP 的可靠性。**这个取舍逻辑是答这题的关键。**

**Q：SOMEIP 怎么保证大数据量？**  
通过 **SOMEIP-TP（Transport Protocol）**：把大 payload 分段（Segment），每段带 `Segment ID`，接收方重组。类似 IP 分片但应用层自主实现，避免 IP 分片在车载网络里的兼容问题。

### 【延伸方向】

- 规范：AUTOSAR PRS SOME/IP Protocol Specification、SOME/IP Service Discovery Protocol Specification
- 开源实现：`vsomeip`（BMW 开源，C++）、`CommonAPI`
- 实验：在 PC 上跑 `vsomeip` 的 hello world，用 Wireshark 抓 SOMEIP 报文
- 延伸：读 `vsomeip` 的 `service-discovery` 实现代码

---

## 1.8 DDS 原理及与 SOMEIP 的对比

### 【JD 出处】

- 经创集团：*"对接 CAN、SOMEIP、DDS 车载信号"*

### 【要解决的问题】

比 SOMEIP 更极端的场景：**多个节点、多对多、高频、且对实时性有毫秒级要求**（如自动驾驶的数据总线）。传统"服务调用"的中心化模型不够，需要**以数据为中心**的架构。

DDS（Data Distribution Service）是 OMG 标准，**以数据为中心（DCPS, Data-Centric Publish-Subscribe）**&#x7684;发布订阅中间件。


### 【原理】

#### 核心模型：全局数据空间（Global Data Space）

这是 DDS 最核心的抽象，也是它与 SOMEIP / MQTT 最大的区别：

```
        ┌──────────── 全局数据空间 ────────────┐
        │  Topic: VehicleSpeed  (Topic是"数据") │
        │  Topic: ObstacleList                 │
        └──────────────────────────────────────┘
             ↑写                    ↓读
        ┌─────────┐            ┌─────────┐
        │ Publisher│            │Subscriber│
        │ (ECU A) │            │ (ECU B) │
        └─────────┘            └─────────┘
```

**关键点**：发布者不关心谁在订阅，订阅者不关心谁在发布。**通信的目标是"数据状态"，而不是"服务方法"**。速度 Topic 的语义是"当前速度是多少"，而不是"调用 getSpeed()"。

#### QoS —— DDS 的灵魂

DDS 的强大不在通信，而在**QoS（Quality of Service）策略**：它让你用声明式的方式描述"这条数据我要求什么"。

| QoS 策略                             | 作用                                                                          |
| ---------------------------------- | --------------------------------------------------------------------------- |
| `RELIABILITY`                      | `BEST_EFFORT`（可丢，低延迟）或 `RELIABLE`（不丢，重传）                                    |
| `DURABILITY`                       | `VOLATILE`（新订阅者拿不到旧数据）／`TRANSIENT_LOCAL`（拿到最后 N 条）／`TRANSIENT`／`PERSISTENT` |
| `HISTORY`                          | 保留 `KEEP_LAST(n)` 还是 `KEEP_ALL`                                             |
| `DEADLINE`                         | 期望的更新周期，超期触发回调（可做故障检测）                                                      |
| `LIVELINESS`                       | 存活检测（与 DEADLINE 配合判断对端是否掉线）                                                 |
| `OWNERSHIP` + `OWNERSHIP_STRENGTH` | 同一 Topic 多个发布者时谁"拥有"数据（冗余源切换）                                               |
| `LATENCY_BUDGET`                   | 延迟预算提示                                                                      |
| `TRANSPORT_PRIORITY`               | 传输优先级                                                                       |
| `PARTITION`                        | 逻辑分区（同一 Topic 可按域划分）                                                        |

**`OWNERSHIP` 是车载场景的杀手级特性**：主备两个 ECU 都发布同一个 Topic，`OWNERSHIP_STRENGTH` 高的是"主"，主挂掉自动切到备——**冗余切换不需要应用写任何代码**。

#### QoS 的自动协商（RxO 匹配）

发布者和订阅者各自声明 QoS，DDS 会做 **Requested/Offered（RxO）兼容性检查**：

```
订阅者请求：RELIABILITY = RELIABLE
发布者提供：RELIABILITY = BEST_EFFORT
→ 不兼容，两者不会匹配（连不上）
```

**这个"连不上"是 DDS 排错的第一大坑**：现象是"发布订阅都正常启动但收不到数据"，根因是 QoS 不兼容。**面试里能说出这一点，说明你真调过 DDS。**

#### 发现机制

DDS 有两种发现（Discovery）：

1. **SPDP（Simple Participant Discovery Protocol）**：通过 UDP 多播周期性宣告自己（类似 SOMEIP 的 OfferService）
2. **SEDP（Simple Endpoint Discovery Protocol）**：交换各自的 Topic/Reader/Writer 及其 QoS，做 RxO 匹配

#### 有线协议标准

- **RTPS（Real-Time Publish-Subscribe，DDSI-RTPS）**：DDS 在 UDP/IP 上的互操作线协议，是不同 DDS 厂商产品互通的基础
- 传输：UDP（默认，实时）或共享内存（同机大流量）

### 【SOMEIP vs DDS 对比表】

| 维度    | SOMEIP                    | DDS                                    |
| ----- | ------------------------- | -------------------------------------- |
| 抽象模型  | 面向服务（RPC + 事件）            | **面向数据（Topic）**                        |
| 标准来源  | AUTOSAR                   | OMG                                    |
| 服务发现  | SOMEIP-SD（Offer/Find，TTL） | SPDP + SEDP（RxO 匹配）                    |
| QoS   | 几乎没有（只有 TCP/UDP 选择）       | **极丰富（十几条策略）**                         |
| 冗余/容错 | 需应用自己做                    | `OWNERSHIP` 内建                         |
| 序列化   | 固定布局                      | CDR（`Fast CDR`）                        |
| 传输    | TCP / UDP                 | UDP / SHM                              |
| 典型用途  | 车身域、面向服务的功能调用             | **自动驾驶、感知、高频数据总线**                     |
| 生态    | AUTOSAR 系（Vector、ETAS）    | RTI Connext、FastDDS、CycloneDDS、OpenDDS |

**一句话总结两者的定位差异**：

- **SOMEIP 解决"服务怎么调"** —— 是 SOA 的 RPC 层
- **DDS 解决"数据怎么流"** —— 是数据总线层，天然为多对多、高实时、高可靠设计

**当前趋势**：两者在融合。AUTOSAR Adaptive 的 `ara::com` 底层可以是 SOMEIP；而自动驾驶域多直接用 DDS（ROS 2 的中间件就是 DDS）。所以**做智能座舱的人会遇到 SOMEIP，做智驾的人会遇到 DDS**，而"两个都懂"的人才在市场上很抢手。

### 【常见追问】

**Q：为什么车厂不统一用一个协议？**  
因为需求分层：车身域的功能调用是"低频、有明确服务边界"→ SOMEIP 合适；智驾的感知数据是"高频、多对多、有 QoS 分级需求"→ DDS 合适。**强行统一会在某一侧付出不必要的复杂度代价。**

**Q：DDS 的 `RELIABLE` 和 TCP 的可靠有什么区别？**  
DDS 的 `RELIABLE` 是**基于 UDP 的可靠性**：`HEARTBEAT` + `ACKNACK` 机制做重传与流量控制，但保留了 UDP 的"无队头阻塞"特性。**所以 DDS RELIABLE 更适合"多主题并行、某一主题丢包不影响其他主题"的场景。** 用 TCP 的话，一个流的丢包会阻塞整个连接上的其他消息。

**Q：DDS 怎么做同一台机器内的零拷贝？**  
通过 **共享内存传输**（如 RTI 的 SHMEM、FastDDS 的 SHM transport）+ **零拷贝 API**（`loan` / `return_loan`，直接从中间件的缓冲池借用内存）。**这是 DDS 在高带宽场景（点云、图像）下能跑起来的关键。**

### 【延伸方向】

- 开源实现：**Fast DDS**（eProsima，有 Android 支持）、**Cyclone DDS**（Eclipse）、**RTI Connext**（商业，车载主流）
- 实验：在 PC 上跑 Fast DDS 的 HelloWorld，改 QoS 观察匹配行为
- 延伸：ROS 2 的中间件抽象（`rmw`）就是 DDS，读 ROS 2 的 `rmw_fastrtps` 是好入口
- 延伸：AUTOSAR Adaptive 的 `ara::com` 与 SOMEIP/DDS 的绑定关系

---

## 1.9 从 CAN 一帧到 App 回调：完整数据通路

### 【JD 出处】

- 经创集团：*"对接 CAN、SOMEIP、DDS 车载信号，完成上层车机服务交互开发；精通 Android 四大组件、AIDL 跨进程通信机制"*

> 这一节是 1.6–1.8 的总装。**面试里能从头到尾把这条链讲清楚，等于直接宣告"我是系统层的人"。**


### 【完整链路】

以"左前车门打开 → 车机 UI 弹出提示"为例：

```
① 物理层
   左前门锁开关闭合 → 车门模块 ECU 检测电平变化

② CAN 链路层
   车门 ECU 将信号打包成 CAN 帧：
     ID = 0x2B0, Data = [0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00]
     信号 DoorStatus_FL = 0x01（1 = 开）
   以 100ms 周期广播（或事件触发）

③ 网关 / 域控制器
   中央网关收到 0x2B0，根据路由表转发到以太网 / 或直接给座舱域控制器
   （若跨域，可能转成 SOMEIP 事件或 DDS Topic 传输）

④ MCU（座舱域控制器内）
   MCU 收到报文：
     ├─ 查 CAN Matrix（DBC）：0x2B0 的第 0 位 = DoorStatus_FL
     ├─ 换算：raw 0x01 → 布尔 true
     └─ 把"信号"转成"VHAL 属性"：
          DOOR_POS (0x0303), areaId = VehicleAreaDoor.ROW_1_LEFT, value = 1
          DOOR_LOCK / DOOR_MOVE 等关联属性一并更新

⑤ VHAL 传输层（HIDL/AIDL）
   MCU 通过 SPI / 共享内存 / 私有 IPC 把 VehiclePropValue 给到
   Vehicle HAL native 进程：
     VehiclePropValue {
        prop = 0x0303, areaId = 0x0100 (ROW_1_LEFT),
        timestamp = ..., status = AVAILABLE, value.int32Values = [1]
     }

⑥ Vehicle HAL → CarService（跨进程，HIDL/AIDL Binder）
   VehicleHal（Java）收到回调 → 分发到已订阅的 PropertyHalService

⑦ PropertyHalService
   ├─ 检查该属性是否有订阅者
   ├─ 按各订阅者的 rate 节流（ON_CHANGE 属性 rate=0，直接透传）
   └─ 触发对应订阅者的回调

⑧ CarPropertyService → App（跨进程，Binder）
   通过 ICarProperty 回调，App 侧 Binder 线程执行
   CarPropertyEventCallback.onChangeEvent(CarPropertyValue)

⑨ App
   回调在 Binder 线程 → 切主线程 / 更新 StateFlow → UI 弹出提示
```

### 【每一跳的延迟量级】（面试加分项）

| 跳                         | 典型延迟      |
| ------------------------- | --------- |
| 传感器 → ECU 采样              | 1–10 ms   |
| CAN 传输（500kbps，8 字节）      | ~0.3 ms   |
| 网关路由转发                    | 1–5 ms    |
| MCU 处理 + 属性转换             | 1–5 ms    |
| MCU → VHAL（SPI/共享内存）      | <1 ms     |
| VHAL → CarService（Binder） | <1 ms     |
| CarService → App（Binder）  | <1 ms     |
| App 处理 + 渲染               | 16 ms（一帧） |

**总计：端到端 20–40ms 是正常水平**。这就是为什么"倒车影像必须在 2 秒内出图"是一个真正的工程挑战（还要加上摄像头启动、解码、Surface 合成、屏幕背光）。

### 【常见追问】

**Q：为什么 ON_CHANGE 属性能做到"变化才上报"，而 MCU 还是周期发 CAN？**  
因为 CAN 报文是**周期广播**的（总线协议要求），但 VHAL 层会**做变化检测**：MCU 或 HAL 对比新旧值，只有变化时才通过 VHAL 向上报。**所以"ON_CHANGE"是 HAL 之上的语义，不是 CAN 的特性。** 这一点很多人答不上来。

**Q：如果车门状态在 100ms 内反复变化，会不会有大量回调？**  
会。所以工程上通常：（1）HAL 层做**去抖（debounce）**，比如连续 3 帧一致才认为变化；（2）上层做节流。**"去抖应该放在哪一层"是一个真实的架构决策**——放在 HAL 层最省资源，放在 App 层最灵活。

**Q：App 侧的 Binder 回调线程是什么？回调里能做什么？**  
是 `Binder` 线程池里的一个线程（`Binder:xxxx_N`）。能做的是：更新内存状态、发消息到主线程、触发 Flow。**不能做**：直接操作 UI、做耗时 IO（会占住 Binder 线程，影响同一进程内的其他跨进程调用，甚至诱发 ANR）。

### 【延伸方向】

- 实验：在 AAOS 模拟器上 `adb shell dumpsys car_service --hal` 看属性订阅与回调状态
- 实验：用 AAOS 的 VHAL 假实现（`emulator` 支持动态改属性）模拟车门状态变化，观察 App 回调
- 延伸阅读：`packages/services/Car/service/src/com/android/car/hal/VehicleHal.java` 的属性分发逻辑

---

## 1.10 车载诊断：UDS / DoIP / ISO-TP

### 【JD 出处】

- 小米汽车-MCU 高级开发工程师：*"有 CAN、UDS、NM、DoIP 开发应用经验者优先"*
- 光庭信息：*"至少擅长车载任一核心模块：车控 CarService/VehicleHAL、车载音频、相机、网络、车载诊断、系统策略定制等"*

### 【要解决的问题】

车在 4S 店要能读故障码、要能刷写固件、要能做在线标定。这需要一套**标准化的诊断协议**，让任何诊断仪都能和任何车对话。这套标准就是 **UDS（Unified Diagnostic Services，ISO 14229）**。


### 【原理】

#### 分层

```
UDS（ISO 14229）        ← 应用层：诊断服务（读码、刷写、例程）
    ↓
DoIP（ISO 13400）       ← 传输：以太网上的诊断（替代 CAN 传输）
或 ISO-TP（ISO 15765-2）← 传输：CAN 上的多帧传输
    ↓
CAN / 车载以太网
```

#### UDS 的核心服务

| 服务 ID            | 名称                                                   | 用途                  |
| ---------------- | ---------------------------------------------------- | ------------------- |
| `0x10`           | DiagnosticSessionControl                             | 切会话：默认 / 编程 / 扩展    |
| `0x11`           | ECUReset                                             | 复位 ECU              |
| `0x14`           | ClearDiagnosticInformation                           | 清故障码                |
| `0x19`           | ReadDTCInformation                                   | 读故障码（DTC）           |
| `0x22`           | ReadDataByIdentifier                                 | 按 DID 读数据（VIN、软件版本） |
| `0x23`           | ReadMemoryByAddress                                  | 按地址读内存              |
| `0x27`           | SecurityAccess                                       | **安全访问（解锁）**        |
| `0x28`           | CommunicationControl                                 | 关闭/开启正常通信           |
| `0x2E`           | WriteDataByIdentifier                                | 按 DID 写数据           |
| `0x31`           | RoutineControl                                       | 启动/停止例程（如自检、标定）     |
| `0x34/0x36/0x37` | RequestDownload / TransferData / RequestTransferExit | **刷写固件三件套**         |
| `0x3E`           | TesterPresent                                        | 心跳保活（防止会话超时）        |
| `0x85`           | ControlDTCSetting                                    | 控制故障码记录开关           |

#### 一次典型诊断会话

```
1. 诊断仪 → 0x10 0x03      请求进入扩展会话 (Extended Session)
   ECU    → 0x50 0x03      肯定响应
2. 诊断仪 → 0x27 0x01      请求种子 (Seed)
   ECU    → 0x67 0x01 <seed>   返回种子
3. 诊断仪 → 0x27 0x02 <key> 发送密钥 (Key)，key = f(seed, 秘钥)
   ECU    → 0x67 0x02      解锁成功
4. 诊断仪 → 0x22 0xF1 0x90  读 VIN
   ECU    → 0x62 0xF1 0x90 <17字节VIN>
5. 诊断仪 → 0x3E 0x00      保活（每 2s 一次）
```

**`0x27 SecurityAccess` 是诊断的安全边界**：`seed-key` 算法由 OEM 定义，通常涉及加密算法和车载秘钥库（HSM）。**破解这个算法就是所谓的"刷隐藏功能"** —— 产业上有巨大灰色需求，也是 OEM 严格保护的部分。

#### ISO-TP（ISO 15765-2）：CAN 上的多帧传输

CAN 一帧最多 8 字节（FD 最多 64），但 UDS 报文常常几百字节。ISO-TP 用**四个帧类型**解决：

| 类型                        | 首字节高 4 位 | 用途                 |
| ------------------------- | -------- | ------------------ |
| **SF**（Single Frame）      | `0x0`    | 单帧，长度 ≤7 字节        |
| **FF**（First Frame）       | `0x1`    | 多帧的第一帧，含总长度        |
| **CF**（Consecutive Frame） | `0x2`    | 后续帧，含序号（0–15 循环）   |
| **FC**（Flow Control）      | `0x3`    | 接收方告知发送方"继续/等待/溢出" |

```
发送方 → FF: [0x10, len_hi, len_lo, data...]   （总长度 + 首段数据）
接收方 → FC: [0x30, BS, STmin]                 （允许发，块大小、最小间隔）
发送方 → CF: [0x21, data...]                   （序号 1）
发送方 → CF: [0x22, data...]                   （序号 2）
...
```

**`BS`（Block Size）= 0 表示"不用等 FC，一直发"；`STmin` 是两帧之间的最小间隔**（接收方能力不足时可以要求发慢点）。这两个参数是诊断协议调试的常见坑点。

#### DoIP（ISO 13400）

**Diagnostic over IP** —— 以太网上的诊断。为什么需要它？

- 刷写一个固件包动辄几 MB 到几十 MB。CAN 500kbps 下要几十分钟，DoIP 在 100Mbps 以太网上是几十秒
- 智能座舱域控制器、ADAS 域控制器往往**没有 CAN 接口**，只有以太网

**DoIP 的关键设计**：

- 固定使用 UDP 13400 做"车辆发现与状态查询"，TCP 13400 做诊断数据传输
- **逻辑地址（Logical Address）** 寻址：诊断仪有源地址，ECU 有目标地址
- 有独立的状态机：`Vehicle Identification Request` → `Vehicle Announcement` → 建立 TCP → 路由激活（`Routing Activation`）→ 才能发 UDS


### 【常见追问】

**Q：UDS 里的否定响应 `0x7F` 是什么？**  
`7F <原服务ID> <NRC>`。NRC（Negative Response Code）常见值：

| NRC    | 含义                                       | 常见原因                 |
| ------ | ---------------------------------------- | -------------------- |
| `0x10` | generalReject                            | 一般性拒绝                |
| `0x11` | serviceNotSupported                      | 不支持该服务               |
| `0x12` | subFunctionNotSupported                  | 不支持该子功能              |
| `0x13` | incorrectMessageLengthOrInvalidFormat    | 长度错                  |
| `0x22` | conditionsNotCorrect                     | 条件不满足（如车速不为 0 时不允刷写） |
| `0x31` | requestOutOfRange                        | DID / 地址越界           |
| `0x33` | securityAccessDenied                     | **没解锁就想写**           |
| `0x78` | requestCorrectlyReceived-ResponsePending | "收到了，正在处理，别急"        |

**`0x78` 是诊断实现里最容易出错的地方**：ECU 处理耗时操作时先回 `7F xx 78`，之后再回真正的响应。诊断仪必须把 P2 超时从 P2Server 切换到 **P2*Server**（典型 5 秒 → 50 秒），否则会误判超时。

**Q：NM（Network Management）是什么？**  
网络管理。AUTOSAR 的 `Nm` 模块管理 ECU 的"睡眠/唤醒"，让总线上不该活跃的 ECU 进入睡眠以省电。`CanNm` 通过周期性 NM 报文维持网络唤醒状态，如果一段时间收不到任何 NM 报文，就各自进入 Bus-Sleep。**这是"车停几天电瓶不亏电"的技术保障。**

**Q：Android 侧和诊断有什么关系？**  
两个方向：

1. **Android 是诊断的"被诊断对象"**：车机的固件版本、故障码要能被 4S 店读出来 → CarService 里的 `CarDiagnosticService` 提供 `CarDiagnosticManager` 给 App 读诊断帧
2. **Android 是诊断的"执行者"**：OTA 升级、远程诊断 → 车机上的 App/SDK 发起 DoIP/UDS 会话，下载固件、触发刷写

### 【延伸方向】

- 标准：ISO 14229-1（UDS）、ISO 15765-2（ISO-TP）、ISO 13400（DoIP）
- 工具：Vector CANdelaStudio、`udsoncan`（Python 开源 UDS 实现）、`python-doipclient`
- 实验：用 `udsoncan` + SocketCAN 在本地模拟一次 UDS 会话
- 延伸：阅读 AUTOSAR 的 `Dcm`（Diagnostic Communication Manager）规范，理解 ECU 侧怎么实现

---

## 1.11 AUTOSAR 与 MCU 侧：SWC / RTE / BSW / CDD / MCAL

### 【JD 出处】

- 小米汽车-MCU 高级开发工程师：*"负责小米汽车智能座舱 MCU 软件架构与开发；熟悉 AUTOSAR 规范和架构，至少 5 年以上 AUTOSAR 软件设计和开发经验；熟练使用 Vector DaVinci、ETAS、EB 等 AUTOSAR 工具；负责基于 AutoSAR 标准的复杂驱动 CDD 开发"*

### 【要解决的问题】

一辆车有几十上百个 ECU，每个 ECU 里跑着不同供应商的软件。**没有统一架构的话，换一个 ECU 供应商就要重写整个软件栈。** AUTOSAR 就是车载软件的"标准化分层"。


### 【原理】

#### Classic AUTOSAR 的分层

```
┌─────────────────────────────────────────────────────┐
│  Application Layer（应用层）                          │
│    SWC（Software Component）— 一条功能逻辑            │
│    ├─ Sensor/Actuator SWC                            │
│    ├─ Application SWC                                │
│    └─ Service SWC                                    │
├─────────────────────────────────────────────────────┤
│  RTE（Runtime Environment）                           │
│    SWC 之间的"虚拟总线"，把 SWC 间的接口绑定到具体实现   │
├─────────────────────────────────────────────────────┤
│  BSW（Basic Software，基础软件）                       │
│    ├─ Services Layer   ：EcuM、BswM、Dcm、Dem、NvM、ComM │
│    ├─ ECU Abstraction  ：CanIf、CanTp、IoHwAb          │
│    └─ Complex Drivers  ：CDD（自定义复杂驱动）          │
├─────────────────────────────────────────────────────┤
│  MCAL（Microcontroller Abstraction Layer）            │
│    Can、Spi、Adc、Pwm、Dio、Gpt、Fls、Wdg……            │
├─────────────────────────────────────────────────────┤
│  微控制器硬件（英飞凌 / NXP / 瑞萨 / TI）               │
└─────────────────────────────────────────────────────┘
```

#### 关键概念逐个拆

**SWC（Software Component）**  
一个 SWC 就是"一个功能模块"，比如"车门控制 SWC"、"空调控制 SWC"。它有两种实现形式：

- **Atomic SWC**：最小的、不可再分的
- **Composition**：由多个 SWC 组合而成

SWC 不直接调用其他 SWC，而是通过 **Port** 声明"我要什么"和"我给什么"：

| Port 类型           | 方向                | 语义          |
| ----------------- | ----------------- | ----------- |
| `PPort` / `RPort` | Provide / Require | 提供/需要的服务    |
| `SenderReceiver`  | 数据流               | 发/收数据（类似事件） |
| `ClientServer`    | 调用                | 客户端/服务端方法调用 |
| `ModeSwitch`      | 模式                | 模式切换通知      |

**RTE（Runtime Environment）**  
RTE 是自动生成的胶水层。你在设计工具里"连线"（把 SWC 的 RPort 绑到另一个 SWC 的 PPort），工具就生成 RTE 代码（`Rte_Call_xxx`、`Rte_Write_xxx`）。**RTE 让 SWC 之间零耦合**——换一个 SWC 的实现不影响调用方。

**BSW 里的关键模块**（面试常问）

| 模块             | 全称                               | 职责                                  |
| -------------- | -------------------------------- | ----------------------------------- |
| `EcuM`         | ECU State Manager                | ECU 启动/关机/睡眠状态机                     |
| `BswM`         | BSW Mode Manager                 | 各 BSW 模块的使能/去使能协调                   |
| `ComM`         | Communication Manager            | 通信模式管理（Full/No/Silent）              |
| `Dcm`          | Diagnostic Communication Manager | **UDS 服务实现**（处理 0x10/0x27/0x22…）    |
| `Dem`          | Diagnostic Event Manager         | **故障码（DTC）管理与存储**                   |
| `NvM`          | NVRAM Manager                    | 非易失存储（写 EEPROM/Flash，带掉电保护）         |
| `Nm` / `CanNm` | Network Management               | 网络睡眠/唤醒                             |
| `Com`          | Communication                    | 信号打包/解包（把信号打成 PDU）                  |
| `PduR`         | PDU Router                       | PDU 路由（CAN PDU ↔ Com PDU ↔ Dcm PDU） |
| `CanIf`        | CAN Interface                    | CAN 驱动抽象                            |
| `CanTp`        | CAN Transport Protocol           | **ISO-TP 实现**（多帧传输）                 |
| `IpduM`        | I-PDU Multiplexer                | 多变体 PDU（同一 ID 承载不同信号集）              |

**通信栈的数据流**（重要，能讲清这条链是 AUTOSAR 面试的核心）：

```
SWC（应用）
  ↕ RTE（Rte_Write）
Com（信号 → PDU，打包）
  ↕ PduR（路由）
CanIf（PDU → CAN 帧）
  ↕
Can Driver（MCAL）→ CAN 控制器 → 总线

反向：
Can Driver → CanIf → PduR → Com（解包 → 信号）→ RTE → SWC
```

**CDD（Complex Device Driver）**  
当标准 BSW 模块无法满足需求时（比如驱动一颗专用 ASIC、实现非标准的时序），就把代码写在 CDD 里，**绕过 ECU Abstraction，直接访问硬件**。小米 JD 里明确要求"基于 AutoSAR 标准的复杂驱动 CDD 开发"，说明这个岗位需要直接啃硬件。

**MCAL**  
最底层的"微控制器抽象层"，由芯片厂商提供（如 NXP 的 MCAL、英飞凌的 MCAL），包含 `Can`、`Spi`、`Adc`、`Pwm`、`Gpt`、`Fls`（Flash 驱动）、`Wdg`（看门狗）等驱动。**MCAL 的配置由工具（EB tresos / DaVinci Configurator）生成。**

#### Vector 工具链（JD 点名的）

| 工具                       | 用途                                   |
| ------------------------ | ------------------------------------ |
| **DaVinci Configurator** | 配置 BSW + 生成代码（对应 EB 的 `tresos`）      |
| **DaVinci Developer**    | 设计 SWC 与 Port 连接（对应 ETAS 的 `ISOLAR`） |
| **MICROSAR**             | Vector 的 BSW 实现（协议栈）                 |
| **CANoe / CANalyzer**    | 总线仿真与测试、诊断测试                         |
| **vFlash**               | 刷写工具                                 |
| **CANdelaStudio**        | 诊断描述（CDD 文件，定义 DID/DTC）              |

#### Adaptive AUTOSAR（趋势）

Classic AUTOSAR 是"静态配置 + 生成代码"，适合硬实时、资源受限的 MCU。但智能座舱、智驾域用的是高性能 SoC 跑 Linux/Android，需要**动态、面向服务、支持 OTA** 的架构：

|    | Classic         | Adaptive                      |
| -- | --------------- | ----------------------------- |
| 语言 | C               | **C++14/17**                  |
| OS | OSEK/VDX（无进程隔离） | **POSIX（进程隔离）**               |
| 通信 | 信号（`Com`）       | **服务（`ara::com`，SOMEIP/DDS）** |
| 配置 | 编译期生成           | **运行期动态**                     |
| 更新 | 整包刷写            | **单应用 OTA**                   |
| 适用 | MCU（车身、动力）      | SoC（座舱、智驾）                    |

**Adaptive 的 `ara::com` 就是"面向服务的 C++ API 封装 SOMEIP/DDS"** —— 这正好把 1.7 和 1.8 串起来了。

### 【常见追问】

**Q：RTE 是手写的还是生成的？**  
**生成**。这是 AUTOSAR 的核心思想之一：SWC 与 BSW 的绑定关系在设计工具里配置，代码由工具生成。手写 RTE 在实践上不可行（组合爆炸）。

**Q：为什么 AUTOSAR 这么"重"？**  
因为它要解决的是一致性问题，而不是效率问题。一家 OEM 有几十家供应商、上百个 ECU，没有标准分层就无法集成。**代价是代码体积大、学习曲线陡、不适合小项目。** 这也是为什么新势力更倾向"用 Adaptive AUTOSAR + 部分自研"而不是全盘 Classic。

**Q：MCU 侧和 Android 侧怎么通信？**  
常见几种：

- **SPI**：MCU 作为 SPI slave，SoC 作为 master，通过自定义协议交换 VHAL 属性（最常见）
- **UART / 串口**：低带宽场景
- **共享内存 / RPMSG**：同一 SoC 内不同核之间
- **以太网 + SOMEIP**：新一代域控架构

**为什么 Android 侧要关心这个**：因为**这条链的稳定性和延迟直接决定车机功能的体验**。MCU 通信异常 → VHAL 属性不更新 → 车速表卡住 / 空调失联。**车机 bug 有很大一部分根源在 MCU 通信，而不是 Android 代码。**

### 【延伸方向】

- 标准：AUTOSAR CP 的 `SWS_*` 系列文档（分层规范）、`EXP_*`（说明文档）
- 入门：读 AUTOSAR 的 `Layered Software Architecture` 官方图，对照每个模块的职责
- 实践：EB tresos 或 Vector DaVinci 有评估版，可以生成一份最小工程看代码结构
- 延伸：AUTOSAR Adaptive 的 `ara::com`、`ara::exec`、`ara::diag` 规范
- 延伸：`ara::diag` 与 UDS 的关系（Adaptive 侧怎么实现诊断）

---

## 1.12 车机多屏、仪表投屏、多窗口分屏

### 【JD 出处】

- 华苏科技：*"熟悉 Car Service、仪表投屏等中间件 Service 开发经历"；"深度定制 Android Framework 核心模块（AMS/WMS/PMS 等），支持多窗口、分屏等复杂交互"*
- 颖派科技：*"定制化 AOSP 系统（尤其是窗口管理器 Window Manager）"*

### 【要解决的问题】

手机只有一块屏。车机有：中控主屏、副驾娱乐屏、仪表屏、HUD、后排娱乐屏、电子后视镜……**它们由同一个 SoC 驱动，但显示完全不同的内容、由不同的进程渲染、有不同的安全等级**。


### 【原理】

#### 三种"多屏"的本质

| 模式             | 机制                                                                                                      | 典型场景               |
| -------------- | ------------------------------------------------------------------------------------------------------- | ------------------ |
| **多 Display**  | 每个物理屏是一个独立的 `DisplayContent`，各自有独立的窗口栈、`DisplayInfo`（分辨率/DPI/刷新率）                                       | 中控 + 副驾屏 + 仪表屏     |
| **虚拟 Display** | 屏幕数量多于物理输出，用 `VirtualDisplay`（`DisplayManager.createVirtualDisplay`）+ `Surface` 承载                      | 把某块内容投到别的设备、录屏     |
| **仪表投屏**       | 两种做法：① **VirtualDisplay**（Android 侧渲染到 Surface，再通过某种通道送到仪表）；② **仪表独立渲染**（仪表由 MCU/独立 SoC 画，Android 只发信号） | 导航箭头、媒体信息、电话信息投到仪表 |

#### 仪表投屏的两条技术路线（面试常考）

**路线 A：Android 作为渲染源（VirtualDisplay + CLUSTER Display）**

```
Android 侧：
  创建一个 DISPLAY_TYPE_CLUSTER 的 display（AAOS 里 CarService 会预创建）
  仪表 App 通过 ActivityManager 在指定 display 上启动 Activity
  → WMS 创建 SurfaceControl
  → SurfaceFlinger 合成
  → 通过 Display HAL / 硬件通道输出到仪表屏
```

优点：仪表内容可以跟着 Android 生态走（App 直接画）。缺点：**安规风险**——仪表属于安全件（车速、警告灯），不能依赖 Android 的稳定性。

**路线 B：信号投屏（Android 只发数据，仪表自己画）**

```
Android 侧：
  把"导航下一步转向"、"当前媒体信息"打包
  → 通过 VHAL 属性 或 私有 IPC 发给 MCU
MCU 侧：
  仪表 MCU 自己渲染（用 OpenGL ES / 或仪表专用图形库）
```

优点：仪表不依赖 Android，符合功能安全要求。缺点：内容受限，需要 Android 和仪表两侧约定协议。**这是当前量产车的主流做法**，也是"车机应用开发"和"仪表开发"这两个岗位的分界。

**面试建议**：如果你做过的是路线 B（通过 SDK 往仪表发信号），就老实说 B，并说明你理解 A 的机制和 B 的优势。**不要硬说做过 A** —— 深挖三层就露馅。

#### 多窗口 / 分屏的 Framework 实现

AAOS 的分屏与手机不同：手机上"分屏"是 `ActivityManager` 的 `split-screen` 模式；车机上的"多窗口"往往是**同一屏上多个 App 并存**（导航 + 音乐 + 车控卡片），实现路径有三条：

| 路径                                                  | 机制                                                                 | 谁的活                  |
| --------------------------------------------------- | ------------------------------------------------------------------ | -------------------- |
| **原生分屏**（`ActivityManager` split-screen / freeform） | `TaskOrganizer` + `WindowContainer` + `SplitScreenController`      | Framework/WMS 定制     |
| **`ActivityView` / `SurfaceViewControl`**           | 一个 App 内嵌另一个 App/View 的 Surface（`ActivityView` + `VirtualDisplay`） | 应用层可实现               |
| **`Presentation` / `VirtualDisplay`**               | 把内容渲染到独立 Display，在 Surface 上显示                                     | 应用层可实现               |
| **多窗口 Shell 定制**                                    | 车机自研的 WindowManager Shell（`SystemUI` 的 `WMShell`），定义车机自己的分屏规则      | Framework + SystemUI |

**关键点**：`android.window.ShellTaskOrganizer`（Android 12+）是现在做多窗口定制的正确入口。它让 OEM 可以拦截"任务启动 → 显示"的过程，自己决定"这个任务放在哪个容器、多大、在哪个 display"。**颖派、华苏的"WMS 定制 / 多窗口分屏"岗位，本质就是写 `TaskOrganizer` 的实现。**

```java
// 定制多窗口的核心：继承 ShellTaskOrganizer，重写任务的容器分配
public class CarTaskOrganizer extends ShellTaskOrganizer {
    @Override
    public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash) {
        // 决定这个任务放到哪个 TaskDisplayArea / 哪个 SurfaceControl
        ...
    }
}
```

### 【常见追问】

**Q：一块屏上跑三个 App，GPU 开销怎么算？**  
每个 App 有自己的 `BufferQueue` + `Surface`，`SurfaceFlinger` 把它们合成成一个图层（`Layer`）。合成方式有两种：

1. **GPU 合成**（`Client Composition`）：SurfaceFlinger 用 GPU 把多个层画到一张 buffer
2. **硬件合成**（`Device Composition`）：交给 `Composer HAL`（Display 硬件里的合成器）直接合成，不经过 GPU

**车机上优先走硬件合成**（省 GPU、省电、省带宽），但受**层数限制**（硬件合成器通常支持 4–8 层）。层数超了就会退回 GPU 合成 → 掉帧。**这就是"多窗口分屏导致卡顿"的根因，也是 WMS/图形岗的高频考点。**

**Q：多个 Display 的 VSYNC 是共用的吗？**  
不一定。每块屏有独立的刷新率与 VSYNC 相位。`SurfaceFlinger` 内部的调度策略（`Scheduler`，Android 12+）会为每个 display 维护独立的 `RefreshRateSelector`。**多屏异刷（比如中控 60Hz + 仪表 30Hz）是车机的常态，也是掉帧排查的难点。**

**Q：副驾屏播放视频时，主驾屏的驾驶限制怎么办？**  
靠 `CarUxRestrictions` 的**分区（zone）**&#x652F;持——Android 14 起 `CarUxRestrictionsManagerService` 可以按 `OccupantZone` 下发不同的限制策略。**这是"多屏 + 安全"的交叉点，能讲清楚说明你碰过真实的量产需求。**

### 【延伸方向】

- 源码：`frameworks/base/services/core/java/com/android/server/wm/` 的 `DisplayContent`、`TaskDisplayArea`、`TaskOrganizerController`
- 源码：`frameworks/base/libs/WindowManager/Shell/` 的 `ShellTaskOrganizer`
- 延伸：`DisplayManagerService` / `VirtualDisplayAdapter` / `CarService` 的 `CarOccupantZoneService`
- 延伸阅读：AAOS 的 `CarDisplayService`（多屏与 OccupantZone 的映射）

---

# 第二部分：Framework 系统层

> 这一部分对应南京 A 档岗位（光庭 18–45K、机器岛 30–60K、颖派、华苏）的门槛。  
> 对你而言，这一部分的价值是**把 9 年积累的"应用侧经验"换成"系统侧语言"**。

## 2.1 Android 系统分层与启动流程

### 【JD 出处】

- 机器岛智能科技：*"熟悉 Java/C/C++，有 Android 应用开发经验 or 系统开发经验；有系统开发经验者优先"*
- 光庭信息：*"熟练掌握 Java 或 C/C++ 任意一种系统开发语言，熟悉 Android 系统架构、Binder 跨进程通信、系统服务运行机制"*

### 【要解决的问题】

"从按下一键启动到看到桌面"，这 3–8 秒里系统做了什么？不理解这条链，就无法回答"启动优化该动哪里"，也无法理解 ANR / Watchdog 这些机制的来源。


### 【原理】

#### 分层（自上而下）

```
┌───────────────────────────────────────────────┐
│ Applications（App / 系统 App）                 │
├───────────────────────────────────────────────┤
│ Java API Framework                            │
│   ActivityManager / WindowManager / PackageManager │
│   ContentProvider / View System / Resource Manager │
│   Notification / Location / Telephony          │
├───────────────────────────────────────────────┤
│ Native C/C++ Libraries  |  Android Runtime     │
│   libc / libbinder / libgui / libui / Skia     │
│   libstagefright / libmedia / OpenGL ES / Vulkan│
│   ART（AOT + JIT + 解释器，AOT 编译成 .oat/.vdex）│
├───────────────────────────────────────────────┤
│ HAL（HIDL / AIDL）                             │
│   camera / audio / sensors / vehicle / graphics │
├───────────────────────────────────────────────┤
│ Linux Kernel（Binder Driver / ashmem / lowmemorykiller / 驱动）│
└───────────────────────────────────────────────┘
```

#### 启动流程（这是面试的高频题）

```
① BootROM（CPU 内置固件）
   └─ 加载 bootloader 到 SRAM

② Bootloader（uboot / lk / ABL）
   ├─ 初始化 DRAM、校验镜像签名（AVB，Android Verified Boot）
   ├─ 加载 boot.img（kernel + ramdisk）与 dtb
   └─ 决定启动槽位（A/B 分区）与 bootreason

③ Linux Kernel
   ├─ 初始化内存、调度、驱动
   ├─ 挂载 rootfs，启动第一个用户态进程 init（PID 1）
   └─ 记录 boot reason（ro.boot.bootreason）

④ init（/system/bin/init）
   ├─ 解析 /system/etc/init/hw/init.rc 及各 rc 文件
   ├─ 按 Action（trigger: early-init → init → late-init → boot）顺序执行
   ├─ 启动 native 服务（servicemanager、hwservicemanager、vold、netd、
   │   surfaceflinger、audioserver、cameraserver、zygote、vehicle HAL…）
   └─ 关键动作：mount 分区、设置 SELinux、启动属性服务（property_service）

⑤ Zygote
   ├─ init 里以 `service zygote /system/bin/app_process64 -Xzygote ...` 启动
   ├─ 预加载常用类与资源（preloadClasses / preloadResources）
   ├─ 创建 Socket（/dev/socket/zygote）等待 fork 请求
   └─ fork 出 system_server

⑥ system_server（Zygote fork 出来）
   └─ SystemServer.main() → run()
       ├─ startBootstrapServices()
       │    ActivityManagerService / PowerManagerService /
       │    PackageManagerService / DisplayManagerService /
       │    UserManagerService / RecoverySystemService
       ├─ startCoreServices()
       │    BatteryService / UsageStatsService / WebViewUpdateService
       └─ startOtherServices()
            WindowManagerService / InputManagerService /
            NetworkManagementService / CarServiceHelperService /
            SystemUI / 各 Service
       └─ systemReady() → ActivityManagerService 启动 Home（Launcher）

⑦ Home App 起来 → 用户看到桌面
```

**关键的时间节点（车机上尤其重要）**：

| 阶段                   | 典型耗时       | 车机特殊要求                          |
| -------------------- | ---------- | ------------------------------- |
| BootROM + Bootloader | 200–800 ms | 要求支持"快速启动"（跳过部分初始化）             |
| Kernel               | 1–2 s      | 常做裁剪                            |
| init 到 Zygote        | 300–800 ms | 可并行化                            |
| Zygote preload       | 300–700 ms | **preloadClasses 是重头戏**         |
| system_server 起来     | 1–3 s      | CarServiceHelperService 在这里     |
| Launcher 可见          | 2–5 s      | 车机要求 <2s 出倒车影像，靠"快速启动 + 优先渲染"实现 |

#### 关键文件与命令

```bash
# 看启动耗时
adb shell dmesg | grep -i "init:"           # init 阶段
adb logcat -b events | grep boot_progress    # 各阶段打点
adb shell getprop | grep boot               # 启动相关属性
adb shell dumpsys activity processes | head # 进程状态

# 关键的 boot_progress 打点（events buffer）
boot_progress_start           # Zygote 开始
boot_progress_preload_start   # preload 开始
boot_progress_preload_end     # preload 结束
boot_progress_system_run      # system_server 开始
boot_progress_pms_start       # PMS 开始
boot_progress_pms_ready       # PMS 就绪
boot_progress_ams_ready       # AMS 就绪
boot_progress_enable_screen   # 屏幕可见
```

### 【常见追问】

**Q：为什么 Android 要用 Zygote 而不是进程池？**  
因为**预加载 + fork 共享内存**。Zygote 预加载了 2000+ 个类和系统资源，fork 出来的子进程通过写时复制（COW）共享这些内存。所以新建一个 App 进程只需要几毫秒，而如果每个进程都重新加载类，需要几百毫秒。**这是 Android 能做到"点击秒开"的根本原因。**

**Q：`preloadClasses` 加载哪些类？**  
`frameworks/base/config/preloaded-classes` 文件里列出的类。**OEM 优化启动的一个常见手段就是精简这份清单**（去掉本机型用不到的类），代价是 App 首次使用这些类时会有轻微卡顿。

**Q：车机的启动优化和手机有什么不同？**  
三点：① **倒车影像优先级最高**——很多方案是把倒车影像做成独立通路（不经 Android，直接由 MCU/ISP 出图），Android 只负责在合适时机接管；② **STR（Suspend to RAM）唤醒比冷启动更常用**——所以"唤醒时间"比"冷启动时间"更关键；③ **服务启动顺序可以定制**——比如空调 HMI 可以先于 Launcher 起来。

**Q：`system_server` 挂了为什么会整机重启？**  
`system_server` 是所有系统服务的宿主，它挂掉后没有任何机制能恢复（Zygote 无法"重启"已 fork 的进程，只能重启整个 zygote → 而 zygote 重启等价于 Android 层重启）。所以 `Watchdog` 的策略是：**第一次超时 dump 堆栈，第二次超时直接杀掉 system_server**（见 3.8）。

### 【延伸方向】

- 必读：`system/core/init/` 的 `init.cpp`、`service.cpp`、`builtins.cpp`
- 必读：`frameworks/base/services/java/com/android/server/SystemServer.java`
- 实验：抓一次完整开机 log，用 `boot_progress_*` 打点画一条时间轴
- 延伸：AVB（Verified Boot）与 dm-verity、A/B 分区与 seamless update

---

## 2.2 Binder 深度详解

### 【JD 出处】

- 几乎所有 System/Framework 岗：*"熟悉 Android 系统架构、Binder 跨进程通信、系统服务运行机制"；"精通 Android 四大组件、AIDL 跨进程通信机制"*

> Binder 是 **AMS / WMS / PMS / CarService / SurfaceFlinger 共同的底座**。你在推 AMS/WMS 学习主线，Binder 是那条主线上最该先打穿的一环。

### 【要解决的问题】

Android 需要一个高效的进程间通信（IPC）机制，用于：

- App 调用系统服务（`startActivity`、`getSystemService`）
- 系统服务反向回调 App
- App 之间传递复杂数据

候选方案对比：

| 方案          | 拷贝次数    | 评价                       |
| ----------- | ------- | ------------------------ |
| 管道 / Socket | 2 次     | 通用但慢                     |
| 共享内存 + 同步机制 | 0 次     | 快但同步复杂、无对象语义             |
| **Binder**  | **1 次** | 快 + 有对象语义 + 有 UID/PID 校验 |
| 信号 / 消息队列   | —       | 不适合传数据                   |

**Binder 胜出的三个理由**：一次拷贝、面向对象（能传对象引用与死亡通知）、**内建安全（Binder 驱动自动带上调用方的 UID/PID，无法伪造）**。


### 【原理】

#### 一次拷贝是怎么实现的

关键在 `mmap`：

```
发送进程 A                     内核                      接收进程 B
                                    
                        ┌──────────────────┐
                        │  binder 内核缓冲区 │
                        │  (由 B 的 mmap    │
                        │   建立，1MB-8MB)  │
                        └──────────────────┘
                              ↑          ↑
                     copy_from_user   B 直接读取
                     （唯一一次拷贝）   （零拷贝）
```

具体过程：

1. **接收进程 B** 在打开 `/dev/binder` 后调用 `binder_mmap()`，把内核里的一块物理页映射到自己的用户空间
2. **发送进程 A** 调用 `binder_transaction`，驱动把 A 的数据 `copy_from_user` 一次到那块内核缓冲区
3. **B** 在自己的用户空间直接访问那块缓冲区（因为在 mmap 时已经建立映射），**不需要第二次拷贝**

对比传统 IPC（管道/Socket）：数据要先从 A 的用户空间拷到内核，再从内核拷到 B 的用户空间 —— **两次拷贝**。Binder 省掉了第二次。

> **面试金句**：Binder 的"一次拷贝"不是魔法，是"接收方预先 mmap，发送方直接拷进接收方的映射区"。

#### 核心类与对象

**Native 层**（`frameworks/native/libs/binder/`）：

| 类                        | 角色                                   |
| ------------------------ | ------------------------------------ |
| `IBinder`                | 接口基类                                 |
| `BpBinder`（Binder Proxy） | **代理端**，代表"远程对象"。持有 `handle`（整数句柄）   |
| `BBinder`（Binder Base）   | **本地端**，代表"本进程内的真实对象"                |
| `ProcessState`           | 每进程一个单例，管理 `/dev/binder` 的 fd 与 mmap |
| `IPCThreadState`         | **每线程一个**，负责与驱动交互（`transact`）        |
| `Parcel`                 | 数据序列化容器                              |
| `ServiceManager`         | 服务注册表，**句柄固定为 0**                    |

**Java 层**（`frameworks/base/core/java/android/os/`）：

| 类                | 角色                                |
| ---------------- | --------------------------------- |
| `IBinder`        | Java 侧接口                          |
| `Binder`         | Java 侧 BBinder                    |
| `BinderProxy`    | Java 侧 BpBinder                   |
| `Parcel`         | Java 侧数据容器                        |
| `ServiceManager` | 服务管理（`addService` / `getService`） |

**JNI 桥接**（`frameworks/base/core/jni/android_util_Binder.cpp`）：

```
Java Binder 对象  ←─ javaObjectForIBinder() ─→ IBinder (native)
Java BinderProxy  ←─ ibinderForJavaObject() ─→ BpBinder (native)
```

每个 Java `Binder` 对象对应一个 native `JavaBBinder`（继承 `BBinder`）；每个 `BinderProxy` 对应一个 `BpBinder(handle)`。

#### AIDL 生成物长什么样（这是理解 Binder 最快的路径）

```java
// 你写的 IMyService.aidl
interface IMyService {
    int add(int a, int b);
    void ping();
}
```

编译后生成 `IMyService.java`（简化）：

```java
public interface IMyService extends android.os.IInterface {

    // ── 本地实现基类（服务端继承它）──
    public static abstract class Stub extends android.os.Binder implements IMyService {
        private static final String DESCRIPTOR = "com.example.IMyService";

        public Stub() { this.attachInterface(this, DESCRIPTOR); }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            switch (code) {
                case TRANSACTION_add: {
                    data.enforceInterface(DESCRIPTOR);
                    int _arg0 = data.readInt();
                    int _arg1 = data.readInt();
                    int _result = this.add(_arg0, _arg1);
                    reply.writeNoException();
                    reply.writeInt(_result);
                    return true;
                }
                case TRANSACTION_ping: {
                    data.enforceInterface(DESCRIPTOR);
                    this.ping();
                    reply.writeNoException();
                    return true;
                }
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    // ── 远程代理（客户端拿到它）──
    private static class Proxy implements IMyService {
        private final android.os.IBinder mRemote;
        Proxy(android.os.IBinder remote) { mRemote = remote; }

        @Override
        public int add(int a, int b) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeInt(a);
                data.writeInt(b);
                // ↓ 真正的跨进程调用
                mRemote.transact(Stub.TRANSACTION_add, data, reply, 0);
                reply.readException();
                return reply.readInt();
            } finally {
                data.recycle(); reply.recycle();
            }
        }
    }
}
```

**从这段生成代码能读出四件事**：

1. **方法调用被序列化成 `Parcel`**（所以传大对象慢——这是 Binder 1MB 限制的来源）
2. **方法通过整数 `code` 分发**（`onTransact` 里的 switch）
3. **`DESCRIPTOR` 是接口标识**（`enforceInterface` 做校验，防止协议错配）
4. **`Proxy` 里没有任何"服务对象"，只有一个 `IBinder`（handle 整数）** —— 这就是"代理模式"的物理体现

#### 完整调用链（一次 `add(1, 2)`）

```
客户端 App
 └─ Proxy.add(1, 2)
     └─ Parcel 写入 DESCRIPTOR + 1 + 2
         └─ mRemote.transact(TRANSACTION_add, data, reply, 0)   // java
             └─ BinderProxy.transact()                          // jni
                 └─ BpBinder::transact(handle, ...)             // native
                     └─ IPCThreadState::transact()
                         └─ IPCThreadState::talkWithDriver()
                             └─ ioctl(fd, BINDER_WRITE_READ, &bwr)  // 陷入内核
                                 └─ binder_transaction()
                                     ├─ 根据 handle 找到目标进程
                                     ├─ copy_from_user 数据到内核缓冲
                                     ├─ 唤醒目标进程的 binder 线程
                                     └─ 把目标 BBinder 对象转换写入 reply
服务端
 └─ [Binder 线程] binder_thread_read → BBinder::transact
     └─ JavaBBinder::onTransact → Java Binder.onTransact
         └─ Stub.onTransact(code, data, reply, flags)
             └─ 分发到 this.add(1, 2)   ← 真实实现
                 └─ 结果写回 reply → 沿原路返回
```

#### `oneway`：异步调用

```java
oneway interface IMyService {
    void notifySomething(int value);   // 不阻塞，不等返回
}
```

生成的代码里 `transact` 会带 `IBinder.FLAG_ONEWAY`，驱动在把数据投递到目标进程的 Binder 线程后**立刻返回**，发送方不阻塞。

**用途**：从系统服务向 App 发通知（比如 `CarPropertyEventCallback` 的回调就是异步语义）。

**风险**：`oneway` 调用**不限流**。如果发送方疯狂调用，接收方的 Binder 缓冲区会满 → 触发 `TransactionTooLargeException` 或丢弃。**这是"车机频繁属性回调导致 App 崩"的深层原因之一。**

#### 死亡通知（linkToDeath）

```java
mRemote.linkToDeath(new IBinder.DeathRecipient() {
    @Override
    public void binderDied() {
        // 对端进程挂了，这里被调用
    }
}, 0);
```

**实现原理**：`linkToDeath` 时 Binder 驱动会记录"这个进程关心那个 handle"，对端进程退出时驱动主动发一个 `BR_DEAD_BINDER` 通知。**这是 Binder 内建的能力，是它的重要优势之一**（Socket 需要自己实现心跳）。

**在 CarService 里的典型应用**：`Car.createCar()` 内部就会 `linkToDeath` 到 CarService 的 Binder，CarService 挂了会触发 `onServiceDisconnected`。

#### Binder 线程池

- 每个进程的 Binder 线程数有限，默认最多 **16 个**（`ProcessState` 通过 `BINDER_SET_MAX_THREADS` 设置）
- **并发度有限**：如果 16 个线程全被耗时操作占住，后续跨进程调用会排队 → **表现是"卡"，严重时诱发 ANR**
- 所以规范：**Binder 回调里绝不做耗时操作**

**这是"Binder 线程池被占满"这个经典 ANR 场景的机制解释**，也是 `dumpsys activity anrs` 里经常能看到 `Binder:xxx_N` 线程全部 block 的原因。

#### 1MB 限制（`TransactionTooLargeException`）

Binder 的传输缓冲区每进程约 **1MB**（mmap 大小的一半左右可用作传输）。**超出就抛 `TransactionTooLargeException`**。

常见触发场景：

- `onSaveInstanceState` 把大 Bitmap 塞进 Bundle
- 跨进程传大数组 / 大字符串
- 频繁 `oneway` 调用导致缓冲区积压

**正确做法**：大数据走 `ashmem` / 文件 / Socket，Binder 只传句柄。

### 【常见追问】

**Q：为什么 Binder 不需要共享内存那样的同步机制？**  
因为 Binder 驱动本身就是同步点——它用一个链表式的等待队列（`binder_proc` 的 `todo` 队列）来保证"数据先入队、再唤醒接收方"，同步由内核完成。而裸共享内存需要自己实现信号量与互斥锁，容易出错。

**Q：`ServiceManager` 为什么要固定句柄 0？**  
因为"用它来查询其他服务"这件事必须没有先有鸡还是先有蛋的问题。句柄 0 在 Binder 驱动里是硬编码的约定，任何进程都能通过 `BpBinder(0)` 拿到它。**这是一个非常典型的 bootstrap 设计。**

**Q：AIDL 的 `in` / `out` / `inout` 是什么？**  
`in` = 客户端 → 服务端（默认）；`out` = 服务端 → 客户端（客户端传的初始值会被忽略，服务端填充后回传）；`inout` = 双向。**对基本类型默认是 `in`，对数组/对象必须显式声明。** 一个经典坑：`out` 参数的"回传"实现依赖 Parcel 的写入顺序与 `data` 复用，写错会静默丢数据。

**Q：为什么 Binder 不用 Unix Domain Socket（如 D-Bus 那样）？**  
因为**性能与安全**。D-Bus 每次调用要经过：客户端 → dbus-daemon（守护进程）→ 服务端，而且数据要多次拷贝 + 多次上下文切换。Binder 是"内核直接转发"，只有一次拷贝，且内核自带 UID/PID 校验。**Linux 社区后来也承认 Binder 的设计优势，Android 之外的系统（如 Fuchsia、部分 Linux 发行版的 binderfs）也在借鉴。**

### 【延伸方向】

- 必读：`frameworks/native/libs/binder/` 的 `BpBinder.cpp`、`IPCThreadState.cpp`、`ProcessState.cpp`
- 必读：`frameworks/base/core/jni/android_util_Binder.cpp`（Java ↔ native 桥接）
- 内核：`drivers/android/binder.c`（`binder_transaction`、`binder_mmap`）
- 实验（强烈推荐）：写一个最小 AIDL，反编译看生成的 `Stub` / `Proxy`，再打断点跟一次 `transact`
- 延伸：`Binder` 与 `BinderProxy` 的 `Parcel` 池（`Parcel.obtain()` 的 6 个缓存池）

---

## 2.3 Handler / Looper / MessageQueue 与 ANR 的关系

### 【JD 出处】

- 软通动力：*"熟悉 Android 框架及各种特性（如跨进程通信、Handle 等）"*
- 浩鲸云计算：*"深入理解 Android Framework 原理、Activity 生命周期、Handler 机制等核心知识"*

### 【要解决的问题】

Android 的主线程是单线程模型（所有 UI 操作必须在主线程）。那主线程在"空闲时"做什么？——它在一个无限循环里等待消息。这就是 `Looper`。

### 【原理】

#### 四件套

```
Looper（每线程一个，存在 ThreadLocal）
  └─ MessageQueue（消息队列，内部用单向链表 + 时间排序）
      └─ Message（消息体：what / arg1 / arg2 / obj / target / when / callback）
          └─ Handler（发送与处理消息，持有 Looper 与 MessageQueue 引用）
```

#### 核心机制：阻塞与唤醒

```java
// Looper.loop() 核心（简化）
public static void loop() {
    final MessageQueue queue = me.mQueue;
    for (;;) {
        Message msg = queue.next();   // ← 可能阻塞
        if (msg == null) return;
        msg.target.dispatchMessage(msg);  // ← Handler.handleMessage
        msg.recycleUnchecked();
    }
}
```

`queue.next()` 在无消息时会阻塞。**阻塞方式不是忙等，而是 epoll**：

```
MessageQueue.nativeInit()  在 native 层创建一个 pipe（+ epoll 监听它）
   ├─ 队列空 → epoll_wait 阻塞（线程休眠，不耗 CPU）
   └─ 有新消息 → nativeWake() 往 pipe 写一个字节 → epoll_wait 返回 → 继续处理
```

**这样设计的好处**：主线程空闲时真正休眠（不耗电），有了消息立刻被唤醒（低延迟）。

#### 消息排序：`when`

`MessageQueue` 按 `msg.when`（目标执行时间的 uptimeMillis）排序，`next()` 会算"到下一个消息还有多久"：

- 有消息立即要执行 → 直接返回它
- 下一条消息在未来 → 用 `epoll_wait(timeout)` 精确休眠到那个时刻

所以 `postDelayed` / `sendMessageDelayed` 的实现是"定时睡眠"而不是"定时轮询"。**这是 Android 主线程消息机制优雅的地方。**

#### 同步屏障（Sync Barrier）

```java
// MessageQueue.postSyncBarrier()
// 插入一条 target == null 的消息，此后所有"非异步消息"都被挡住
```

**用途**：让某些"异步消息"**插队**执行。

最典型的用例是 **`Choreographer` 的 VSYNC 消息**：

```
消息队列（有屏障）
  ├─ 普通消息 ...（被屏障挡住，不执行）
  ├─ ★ Barrier（target = null）★
  └─ 异步消息：FrameDisplayEventReceiver（VSYNC 到达 → doFrame）
```

**为什么需要它**：如果队列里积压了大量普通消息（比如一堆动画回调），VSYNC 消息如果排在后面，就会错过这一帧 → 掉帧。加屏障让 VSYNC 优先执行，**保证渲染节奏不被业务消息打乱**。

**这也是"为什么主线程消息堆积会导致掉帧"的完整解释**。

#### `IdleHandler`

```java
Looper.myQueue().addIdleHandler(() -> {
    // 队列空闲时执行（只在"暂时没有消息"时调用）
    return false;   // true = 保留，下次空闲还会调
});
```

**用途**：延迟初始化。启动优化里把"不紧急的初始化"塞进 `IdleHandler`，可以显著改善首帧时间。

**注意**：`IdleHandler` 只在"消息队列暂时为空"时触发。如果队列一直被塞满（有大量消息），它可能永远不执行。**所以它不能用来做"必须执行"的任务。**

#### 与 ANR 的关系（这是重点）

ANR 的四种类型，全部与主线程消息循环有关：

| ANR 类型                          | 超时                         | 机制                                                           |
| ------------------------------- | -------------------------- | ------------------------------------------------------------ |
| **Input dispatching timed out** | 5s（KEY_DISPATCHING / 输入事件） | 输入事件通过 Binder 投递到 App 主线程，主线程迟迟处理不完 → `InputDispatcher` 判定超时 |
| **Broadcast of Intent**         | 前台 10s / 后台 60s            | `BroadcastReceiver.onReceive` 在**主线程**执行，超时未 `finish`        |
| **Service Timeout**             | 前台 20s / 后台 200s           | `Service.onCreate/onStartCommand/onBind` 在主线程执行超时            |
| **ContentProvider publish**     | 10s                        | `onCreate` 超时                                                |
| **JobScheduler / Binding**      | 变化                         | —                                                            |

**关键理解**：ANR 的本质是"**主线程的消息处理超时**"。所有 ANR 的根因都能归结为两类：

1. **单个消息处理太久**（主线程做 IO、做大量计算）
2. **消息被长时间挡住**（等锁、等 Binder、被同步屏障+异步消息占住）

**这就是你排查 ANR 时的两分法**，比"看栈猜"要系统得多。

### 【常见追问】

**Q：为什么主线程不能做网络请求？**  
不是因为"技术上不行"，而是因为：① 网络请求不确定耗时（可能 30 秒），会把消息循环堵死 → 输入事件超时 → ANR；② `NetworkOnMainThreadException` 只是一个"保护性拦截"，拦截不了所有场景（如 Socket 直接读写）。**本质原因是它会破坏"消息循环必须及时返回"这个前提。**

**Q：`Handler` 内存泄漏的原理和标准解法？**  
非静态内部类 / 匿名类持有外部 `Activity` 引用；如果有一条 `Message` 还在队列里（延迟消息），GC 就无法回收 Activity。解法：① 静态内部类 + `WeakReference`；② 在 `onDestroy` 中 `removeCallbacksAndMessages(null)`；③ 用 `Lifecycle` 感知的替代方案（协程 / `LifecycleScope`）。

**Q：`Message.obtain()` 为什么比 `new Message()` 好？**  
因为 `Message` 有对象池（最多 50 个），`obtain()` 复用，`recycle()` 归还。**高频发消息的场景（如属性回调、动画）能显著减少 GC 压力。**

**Q：`View.post()` 和 `Handler.post()` 有什么区别？**  
`View.post()` 在 View 已 attach 时用的是 View 所在线程的 Handler（即主线程），并且**会等待 View attach**——如果 View 还没 attach，runnable 会被存起来，等 attach 时执行。`Handler.post()` 直接用指定 Looper。**`View.post` 常被用来"等 View 尺寸确定后执行"，这是它的独有价值。**

### 【延伸方向】

- 源码：`frameworks/base/core/java/android/os/` 的 `Looper.java`、`MessageQueue.java`、`Handler.java`、`Message.java`
- 源码：`frameworks/base/core/jni/android_os_MessageQueue.cpp`（epoll 与 pipe 在这里）
- 实验：`adb shell dumpsys activity anrs`、`adb shell dumpsys activity top` 看主线程栈
- 延伸：`Trace.traceBegin` 与 `Looper` 的 `logging` 机制（`Looper.setSlowLogThreshold` 可打印耗时消息）

## 2.4 AMS：Activity 启动流程与进程管理

### 【JD 出处】

- 华苏科技：*"深度定制 Android Framework 核心模块（AMS/WMS/PMS 等），支持多窗口、分屏等复杂交互；主导系统级性能优化（启动/流畅度/内存），解决 ANR/死机等稳定性问题"*
- 机器岛智能科技：*"熟悉以下一个或多个模块：AMS、PMS、WMS、Input、SurfaceFlinger、Audio、WiFi、Bluetooth、SystemUI"*

### 【要解决的问题】

AMS 要回答四个问题：**谁在运行、跑在哪个进程、什么时候启动、什么时候杀掉。**

### 【原理】

#### 一次 `startActivity` 的完整调用链

```
App 进程
 └─ Activity.startActivity(intent)
     └─ Instrumentation.execStartActivity()
         └─ ActivityTaskManager.getService().startActivity(...)   // binder
            [跨进程到 system_server]
 ├─ ActivityTaskManagerService.startActivity()      ← Android 10+ 从 AMS 拆出
 │   └─ ActivityStartController.obtainStarter(...).execute()
 │       ├─ ActivityStarter.execute()
 │       │   ├─ 解析 Intent（resolveActivity / IntentResolver）
 │       │   ├─ 检查权限、导出、SELinux
 │       │   ├─ 计算启动模式（launchMode / flag）与目标 Task
 │       │   └─ Task 复用 / 新建判断
 │       └─ ActivityStarter.startActivityUnchecked()
 │           └─ RootWindowContainer.resumeFocusedTasksTopActivities()
 │               └─ Task.resumeTopActivityUncheckedLocked()
 │                   └─ Task.resumeTopActivityInnerLocked()
 │                       ├─ 若目标进程未启动 → ProcessList.startProcess()
 │                       │     └─ ZygoteProcess.startViaZygote()  ← fork
 │                       └─ ActivityTaskSupervisor.startSpecificActivity()
 │                           └─ realStartActivityLocked()
 │                               ├─ 通过 IApplicationThread (binder) 通知 App
 │                               └─ App 侧 ClientTransaction / LaunchActivityItem
 └─ App 进程
     └─ ActivityThread.handleLaunchActivity()
         ├─ performLaunchActivity()
         │   ├─ Instrumentation.newActivity()  ← 反射创建 Activity
         │   ├─ Activity.attach()              ← 绑定 Context / Window
         │   └─ Activity.onCreate()            ← 你的代码
         ├─ handleResumeActivity()
         │   ├─ Activity.onResume()
         │   └─ WindowManager.addView(decorView)  ← 交给 WMS
         └─ 此 ViewRootImpl 建立后，走 traversal 上屏
```

**关键点**：

| 关注点                                | 说明                                                                                          |
| ---------------------------------- | ------------------------------------------------------------------------------------------- |
| `ActivityTaskManagerService`（ATMS） | Android 10 起从 AMS 拆分出来，专管"活动与任务"。**面试若提"AMS 启动 Activity"应知道实际是 ATMS 在做**                    |
| `ActivityStarter`                  | 启动逻辑的集中地，所有 launchMode / flag 的判断都在这                                                        |
| `ClientTransaction`                | 客户端事务机制（Android 9 引入），把"要执行的操作"打包成 `TransactionItem` 批量下发，取代了以前的一次次 `scheduleXxx` binder 调用 |
| `ActivityThread`                   | App 进程的"主线程管家"，`main()` 里跑 `Looper.loop()`                                                  |

#### 进程启动（`ProcessList` / `ZygoteProcess`）

```
ProcessList.startProcessLocked()
  ├─ 决定进程名（processName）
  ├─ 检查是否已有可复用进程（同 process 且同 uid）
  ├─ 检查 adj / 内存水位（是否要 lmkd kill）
  └─ Process.start()
      └─ ZygoteProcess.startViaZygote()
          └─ 往 /dev/socket/zygote 写参数
              └─ [Zygote fork]
                  └─ ZygoteInit.handleChildProc() → RuntimeInit → ActivityThread.main()
```

#### 进程优先级与 LMK

**这是"为什么后台 App 会被杀"的核心**（`ProcessList` 里的 `oom_adj` / `adj`）：

| adj 值 | 常量                    | 含义               |
| ----- | --------------------- | ---------------- |
| 0     | `FOREGROUND_APP_ADJ`  | 前台可见             |
| 100   | `VISIBLE_APP_ADJ`     | 可见但非前台           |
| 200   | `PERCEPTIBLE_APP_ADJ` | 可感知（如前台 Service） |
| 250   | `BACKUP_APP_ADJ`      | —                |
| 300   | `SERVICE_ADJ`         | 后台 Service       |
| 700   | `PREVIOUS_APP_ADJ`    | 上一个 App（切回来快）    |
| 900   | `CACHED_APP_ADJ`      | 缓存进程（最容易被杀）      |
| 1000  | `HOME_APP_ADJ`        | Launcher         |
| -1000 | `SYSTEM_ADJ`          | system_server    |

**LMK（Low Memory Killer）**：内核侧或用户态 `lmkd` 监控内存水位，按 `adj` 从大到小杀进程。

> **车机上的重要差异**：车机的关键应用（空调、设置、倒车影像、仪表）**不能被 LMK 杀掉**。做法有：① 把它们的 `adj` 永久钉在高优先级（Android 有 `setProcessImportant` / `oom_score_adj` 的定制接口）；② 定制 `lmkd` 的杀进程策略（车机上通常大幅放宽，因为内存配置普遍更大）；③ 把应用做成 `persistent` 进程。**这是车机 Framework 定制的常见需求，也是一个很有说服力的面试案例。**

### 【常见追问】

**Q：`launchMode` 四种模式的区别？**（高频基础题，但深挖点在第 4 个）  
`standard`（新建实例）、`singleTop`（栈顶复用）、`singleTask`（栈内复用，清掉其上的）、`singleInstance`（独立 Task）。深挖点：**`singleTask` 的"清栈"是由 `ActivityStarter` 计算的 `mLaunchFlags` 决定的，具体是 `FLAG_ACTIVITY_CLEAR_TOP` + `FLAG_ACTIVITY_NEW_TASK` 的组合**，而不是 `singleTask` 本身。

**Q：`Activity` 的启动流程在 Android 9 之后有什么变化？**  
引入了 **`ClientTransaction`** 机制：以前每个生命周期回调都是一次独立的 Binder 调用（`scheduleLaunchActivity`、`scheduleResumeActivity`…），现在把一组操作打包成一个 `ClientTransaction`，一次 Binder 调用下发多个 `TransactionItem`（`LaunchActivityItem`、`ResumeActivityItem`、`DestroyActivityItem`）。**好处是减少跨进程次数、保证操作顺序、便于统一生命周期管理。**

**Q：为什么 `Application.onCreate` 慢会拖慢启动？**  
因为 `ActivityThread.handleBindApplication()` 会先执行 `Application.attachBaseContext` → `ContentProvider.onCreate`（**注意顺序，Provider 在 Application.onCreate 之前**）→ `Application.onCreate`，然后才 `handleLaunchActivity`。所以 `ContentProvider` 里的初始化开销会直接算进冷启动时间。**这就是"启动优化要先查 ContentProvider"的原因。**

**Q：车机上为什么需要定制 `ActivityManagerService`？**  
三个常见需求：① **启动拦截**（驾驶限制白名单，`CarPackageManagerService` 就是干这个的）；② **进程保活**（关键应用不被杀）；③ **启动顺序控制**（空调 HMI 要先起）。**这三点你如果做车机项目，大概率接触过，面试时按"需求 → 定制点 → 实现方式"讲。**

### 【延伸方向】

- 源码：`frameworks/base/services/core/java/com/android/server/wm/`（ATMS 已归入 wm 目录，叫 `ActivityTaskManagerService.java`）
- 源码：`frameworks/base/services/core/java/com/android/server/am/`（`ActivityManagerService.java`、`ProcessList.java`、`OomAdjuster.java`）
- 实验：`adb shell dumpsys activity activities`（任务栈）、`dumpsys activity processes`（进程列表与 adj）
- 延伸：`ClientTransaction` 与 `TransactionExecutor`

---

## 2.5 WMS：窗口、Surface、多窗口分屏、Transaction

### 【JD 出处】

- 颖派科技：*"定制化 AOSP 系统（尤其是窗口管理器 Window Manager）；熟悉 Window Manager 模块开发"*
- 华苏科技：*"深度定制 Android Framework 核心模块（AMS/WMS/PMS 等），支持多窗口、分屏等复杂交互"*
- BOSS直聘：*"熟悉 graphic 系统，熟悉 hwui、wms 相关优先"*

### 【要解决的问题】

屏幕上有很多"互相遮挡的矩形"：Activity 的窗口、状态栏、输入法、悬浮窗、Toast、Dialog、分屏、画中画……谁在上谁在下、谁接受触摸、谁该被裁剪、动画怎么做 —— 全部由 WMS 决定。

### 【原理】

#### 三层模型：Window → WindowState → Surface

```
App 进程                              system_server
─────────                            ─────────────
PhoneWindow（Window 的实现）
  └─ DecorView（View 树根）
      └─ ViewRootImpl  ──binder──▶  WindowManagerService
           └─ mWindowSession              └─ WindowState（窗口的状态记录）
                (IWindowSession)              ├─ mLayoutParams
                                              ├─ mFrame / mParentFrame
                                              ├─ mAttrs
                                              └─ mClient（IWindow，回调 App）
           └─ mSurfaceControl  ◀──────────  SurfaceControl（真正决定图层）
                                              └─ SurfaceFlinger 的 Layer
```

**关键认知**：**`View` 树是"内容"，`Surface` 是"画布"，`SurfaceControl` 是"图层句柄"**。WMS 管的是后者（布局层级与图层），App 画的是前者。

#### `performTraversals` —— 一次 UI 更新的完整链路

```
ViewRootImpl.setView() / requestLayout()
  └─ scheduleTraversals()
      ├─ 设置同步屏障（postSyncBarrier）      ← 见 2.3
      └─ Choreographer.postCallback(TRAVERSAL, mTraversalRunnable)
          └─ [VSYNC 到达]
              └─ Choreographer#doFrame
                  ├─ CALLBACK_INPUT      （输入事件优先）
                  ├─ CALLBACK_ANIMATION  （动画）
                  └─ CALLBACK_TRAVERSAL
                      └─ ViewRootImpl.performTraversals()
                          ├─ performMeasure()   （测量）
                          ├─ performLayout()    （布局）
                          ├─ performDraw()      （绘制）
                          │    └─ ThreadedRenderer.draw()
                          │        └─ RenderThread（GPU 提交）
                          └─ relayoutWindow()   ← binder 到 WMS，最终 applyTransaction
                              └─ SurfaceFlinger 合成 → 上屏
```

**这段是理解"为什么掉帧"的地图**：任何一环超时（测量 16ms 内做不完、RenderThread 排队、SurfaceFlinger 合成层数超限）都会表现为掉帧。

#### `SurfaceControl.Transaction`（Android 10+ 的重要变化）

以前 WMS 直接操作 `Surface`，现在统一用 `SurfaceControl` + `Transaction`：

```java
SurfaceControl.Transaction t = systemTransaction();
t.setPosition(surfaceControl, x, y);
t.setLayer(surfaceControl, layer);
t.setAlpha(surfaceControl, alpha);
t.apply();   // 一次性原子提交
```

**为什么要这样改**：

1. **原子性**：一批变更一起生效，避免"中间态"被合成出来（以前会出现一帧的错位）
2. **性能**：以前每个属性变化都是一次跨进程调用，现在打包成一个 `Transaction` 一次提交
3. **可定制**：`WindowManager` 的定制（如车机的分屏规则）可以通过操控 `Transaction` 实现，不必改 WMS 内核

**颖派"WMS 定制"、华苏"多窗口分屏"这两个岗位的技术核心就在这里。**

#### 多窗口 / 分屏的实现路径

| 层次              | 机制                                                                                         | 定制难度            |
| --------------- | ------------------------------------------------------------------------------------------ | --------------- |
| **WMS 内部**      | `DisplayContent`（每个屏）→ `TaskDisplayArea` → `Task` → `ActivityRecord`，加 `WindowContainer` 树 | 高（改 WMS）        |
| **Shell 层**（推荐） | `ShellTaskOrganizer` + `TaskOrganizer`，拦截"任务出现"并决定它的容器                                     | **中（官方支持的定制点）** |
| **应用层**         | `ActivityView` / `VirtualDisplay` / `Presentation`                                         | 低               |

```java
// 车机多窗口定制的标准入口（Android 12+）
public class CarTaskOrganizer extends ShellTaskOrganizer {
    @Override
    public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash) {
        // ① 决定这个任务放到哪个 display / TaskDisplayArea
        // ② 用 SurfaceControl.Transaction 设置位置、大小、层级
        // ③ 记录映射关系，等 onTaskVanished 时清理
    }
}
```

**面试可以说的话**："现在做车机多窗口的正确姿势不是改 WMS 内核，而是实现 `ShellTaskOrganizer`，因为 WMS 从 Android 10 起把'布局决策'外移到了 WindowManager Shell。改内核的代价是每次 AOSP 升级都要重新适配。"

#### 窗口动画与 `WindowAnimator`

窗口动画（Activity 转场、输入法弹出）由 WMS 的 `WindowAnimator` 驱动，依赖 VSYNC。车机的定制点在于**转场动画时长与曲线**（车机要求比手机更快更利落，通常 200–300ms）。

### 【常见追问】

**Q：`Window`、`View`、`Surface`、`SurfaceControl` 四者关系？**  
一句话：`Window` 是抽象概念（一个屏幕区域），`View` 是里面的内容树，`Surface` 是内容可以画上去的画布，`SurfaceControl` 是这块画布在 SurfaceFlinger 里的图层句柄。**`ViewRootImpl` 是串联这四者的枢纽。**

**Q：`WindowManager.addView` 之后发生了什么？**  
`ViewRootImpl.setView()` → `mWindowSession.addToDisplayAsUser()`（binder 到 WMS）→ WMS 创建 `WindowState` 与 `SurfaceControl` → 回传 `Surface` 给 App → `ViewRootImpl` 建立 `mSurface` → 触发首次 `scheduleTraversals()`。

**Q：为什么 `Dialog`/`Toast` 需要单独的 Window？**  
因为它们是**不同层级的图层**。`SurfaceFlinger` 的合成是"按 Layer 排序"，同一 `Window` 内的 View 遮挡靠绘制顺序解决（会重绘），跨 Window 的遮挡靠图层顺序解决（不重绘）。**所以"悬浮在别的 App 之上"必须用独立 Window。**

**Q：`WindowManager.LayoutParams.type` 在 Android 8+ 之后有什么变化？**  
`TYPE_APPLICATION_OVERLAY` 取代了 `TYPE_SYSTEM_ALERT`/`TYPE_PHONE` 等一系列旧类型，且必须申请 `SYSTEM_ALERT_WINDOW` 权限。**系统应用（platform 签名）可以申请 `INTERNAL_SYSTEM_WINDOW` 类权限，从而使用 `TYPE_STATUS_BAR`、`TYPE_NAVIGATION_BAR`、`TYPE_SYSTEM_DIALOG` 等内部类型——这是系统应用的特权之一，也是你项目的环境。**

### 【延伸方向】

- 源码：`frameworks/base/services/core/java/com/android/server/wm/`（`WindowManagerService.java`、`WindowState.java`、`RootWindowContainer.java`、`TaskOrganizerController.java`）
- 源码：`frameworks/base/core/java/android/view/ViewRootImpl.java`（`performTraversals` 必读）
- 延伸：`frameworks/base/libs/WindowManager/Shell/`（`ShellTaskOrganizer.java`、`SplitScreenController.java`）
- 延伸：`SurfaceControl.java` 与 `Transaction`

---

## 2.6 PMS：包扫描、权限、签名

### 【JD 出处】

- 华苏科技、机器岛智能科技：*"AMS/WMS/PMS"*

### 【要解决的问题】

系统要知道"装了哪些 App、每个 App 声明了什么组件、申请了什么权限、用什么证书签的名"。这份"账本"就是 PMS 维护的。

### 【原理】

#### 包扫描（开机最耗时的环节之一）

```
PackageManagerService.main()
  ├─ 扫描 /system/framework（系统框架包）
  ├─ 扫描 /system/priv-app、/system/app、/vendor/app、/product/app
  ├─ 扫描 /data/app（第三方）
  └─ 每个 APK：
      ├─ PackageParser.parsePackage()   ← 解析 AndroidManifest.xml
      ├─ 收集：permissions / activities / services / receivers / providers
      ├─ 签名验证与签名信息收集
      └─ 写入 PackageSetting → 持久化到 /data/system/packages.xml
```

**性能关键点**：

- **`AndroidManifest.xml` 的解析**是主要开销（二进制 XML 解析）
- 系统 App 数量越多，开机越慢 → 车机定制时会裁剪非必要预装
- **`packages.xml` 的写入是同步的**，掉电可能损坏 → 有 `packages-backup.xml` 与 `.journal` 恢复机制

#### 权限模型（车机岗的高频考点）

| 类型              | 声明方式                       | 授予方式                             |
| --------------- | -------------------------- | -------------------------------- |
| **normal**      | `protectionLevel="normal"` | 安装即授予                            |
| **dangerous**   | 运行时权限                      | 用户运行时确认（Android 6+）              |
| **signature**   | `signature`                | 与声明者**同签名**才授予                   |
| **privileged**  | `signature\|privileged`    | 放到 **`/system/priv-app`** 目录即可获得 |
| **development** | `development`              | `adb` 可授予                        |

**关键规则（必须记住）**：

```
signature|privileged → ① 与 platform 同签名 或 ② 在 /system/priv-app 下
                       两者满足其一即可
```

**`privapp-permissions` 白名单**（Android 9+ 的强制要求）：

```
/system/etc/permissions/privapp-permissions-<pkg>.xml
```

```xml
<permissions>
    <privapp-permissions package="com.example.systemapp">
        <permission name="android.permission.STATUS_BAR"/>
        <permission name="android.permission.CONTROL_KEYGUARD"/>
    </privapp-permissions>
</permissions>
```

如果 `ro.control_privapp_permissions=enforce`（默认），**不在白名单里的特权权限会导致开机失败（PMS 抛异常）**。这是系统应用集成时最常见的"开机起不来"原因。**这条对你（维护 priv-app 系统应用）应该是熟面孔，但值得把"enforce 模式 + 白名单"这个机制正式讲一遍。**

#### 签名与 `sharedUserId`

```xml

<manifest android:sharedUserId="android.uid.system" ... >
```

**`sharedUserId` 的作用**：让多个 APK 运行在**同一个 Linux UID** 下，从而：

- 可以互相访问对方的私有文件与 SharedPreferences
- 权限自动互授
- 可以在同一进程里

**`android.uid.system` 的特殊性**：它等同于 `system_server` 的 UID。获得这个 UID 意味着：

- 可以调用大量 `@SystemApi` / `@hide` 接口
- 可以读写 `/data/system/` 下的系统状态文件
- 可以发送 `PROTECTED_BROADCAST`

**前提**：必须用 **platform 私钥签名**。`Android.bp` 里写：

```
android_app {
    name: "MySystemApp",
    certificate: "platform",     // ← 用 platform key 签名
    privileged: true,            // ← 装到 priv-app
}
```

**这是"系统应用开发"的三个钥匙**：`certificate: "platform"` + `privileged: true` + `sharedUserId="android.uid.system"`。三者可以独立使用，组合起来权限最大。**你在做的正是这套东西，面试时把它讲成"特权模型"而不是"我们把 APK 放错了目录"，层次完全不同。**

### 【常见追问】

**Q：`packages.xml` 坏了会怎样？**  
PMS 会尝试用 `packages-backup.xml` 恢复；再不行会**重新全量扫描**（很慢，且可能丢失运行时权限授予状态）。工程上要避免"频繁写 `packages.xml`"（比如频繁安装卸载测试）在掉电场景。

**Q：为什么系统 App 有时也会"权限被拒"？**  
三个常见原因：① 用了 `signature` 级别权限但签名是 debug key（没配 `certificate: "platform"`）；② 特权权限不在 `privapp-permissions` 白名单里；③ `runtime` 权限没在运行时申请（`privileged` 只解决"特权权限"，不解决"危险权限"的运行时申请）。**第 ③ 条对你 SDK 33+ 的适配是高频坑。**

**Q：Scoped Storage 对系统应用有什么例外？**  
`android.uid.system` 与拥有 `MANAGE_EXTERNAL_STORAGE` 的应用可以绕过部分 scoped storage 限制。系统应用在 Android 10+ 的存储访问上仍有一系列"后门"（`/data/system`、`/data/misc`、`/data/vendor` 等）。

### 【延伸方向】

- 源码：`frameworks/base/services/core/java/com/android/server/pm/`（`PackageManagerService.java`、`PackageParser2.java`、`PermissionManagerService.java`）
- 源码：`frameworks/base/services/core/java/com/android/server/pm/permission/`
- 延伸阅读：`/system/etc/permissions/` 下的所有 xml，理解平台权限声明方式
- 延伸：`AppOpsManager`（运行时权限之外的另一种权限控制，与 `PackageManager` 互补）

## 2.7 SystemUI / keyguard / Settings 模块定制

### 【JD 出处】

- 企查查收录的 Framework 岗：*"负责 systemui、keyguard、Settings 等模块维护及开发"*
- 机器岛智能科技：*"熟悉以下一个或多个模块：AMS、PMS、WMS、Input、SurfaceFlinger、Audio、WiFi、Bluetooth、SystemUI"*

### 【要解决的问题】

这三个模块是"**用户每天看到但属于系统而不是应用**"的部分。它们的定制在车机项目里占比极高——因为 OEM 要把它们改成自己品牌的形态。

### 【原理】

#### SystemUI

**是什么**：一个系统应用（`com.android.systemui`），以 `system` UID 运行，负责：

| 组件                                                   | 职责                                                      |
| ---------------------------------------------------- | ------------------------------------------------------- |
| `StatusBar`                                          | 状态栏（时间、信号、电量、通知图标）                                      |
| `NavigationBar`                                      | 导航栏（返回/Home/最近）——车机通常隐藏或改成固定工具栏                         |
| `NotificationShade`                                  | 通知栏与快捷设置面板                                              |
| `KeyguardViewMediator`（在 system_server）              | 锁屏状态机（`SystemUI` 侧是 `KeyguardView` / `KeyguardBouncer`） |
| `VolumeUI` / `GlobalActionsDialog`                   | 音量面板 / 长按电源菜单                                           |
| `ScreenshotHelper`                                   | 截屏                                                      |
| `Dock / Recents（Launcher3 或 SystemUI 的 RecentsView）` | 最近任务                                                    |

**架构要点**：

- `SystemUIApplication` 启动各 `SystemUI` 子系统，每个子系统实现 `SystemUI` 接口
- 依赖注入用 **Dagger**（`SystemUIRootComponent`）
- 与 `system_server` 的桥接是 **`IStatusBarService`**（`StatusBarManagerService` 是 system_server 侧的实现）
- 通知走 `NotificationManagerService` → `StatusBar` 的 `NotificationListener`

**车机定制的常见需求**：

- 状态栏改成"顶部固定信息条"（显示温度、时间、信号、用户头像）
- 快捷设置面板改成车控面板（空调、座椅、音量、氛围灯）
- 去掉锁屏（车机一般不做锁屏，或做成"快速用户切换"）
- 音量 UI 改成"按音区分组"（对应 1.5.1 的 `CarVolumeGroup`）

```xml


```

**AOSP 里已经有车机版 SystemUI**：`packages/apps/Car/SystemUI/`（CarSystemUI），它是 `SystemUI` 的定制版本，正是"把手机的 SystemUI 改成车机形态"的官方范例。**读这个目录是理解"车机 SystemUI 定制该怎么做"的最快路径。**

#### keyguard（锁屏）

**核心是状态机**：`KeyguardViewMediator`（在 system_server 的 `com.android.server.policy`）管理这些状态：

```
OFF → SHOWING → OCCLUDED → HIDDEN
      ↑              ↓
   KEYGUARD         BOUNCER（密码输入界面）
```

关键 API：`WindowManagerPolicy` 的 `KeyguardStateMonitor`、`IKeyguardService`（SystemUI 侧实现）、`IKeyguardStateCallback`。

**与 WMS 的耦合**：锁屏是"特殊的 Window"（`TYPE_KEYGUARD`），WMS 里有专门的 `KeyguardController` 来协调"窗口显示时机与锁屏状态"。**如果要定制"车机不做锁屏"，正确做法是配置而非改代码**（`config_enableKeyguardService=false` 或 overlay `config_showNavigationBar` 之类）。

#### Settings

**是什么**：`packages/apps/Settings`，一个普通系统应用，但通过 `android.uid.system` 获得了修改系统设置的权限。

**架构**：Android 8+ 起用 `PreferenceFragmentCompat` + `DashboardFragment` 体系。定制点：

| 定制需求     | 位置                                                                          |
| -------- | --------------------------------------------------------------------------- |
| 新增设置项    | 新建 `DashboardFragment` + 在 `search_indexable` / `top_level_settings.xml` 注册 |
| 隐藏某项     | overlay 或直接改 xml                                                            |
| 新增整个一级菜单 | `DashboardFragmentRegistry`                                                 |
| 车机专属设置   | `packages/apps/Car/Settings/`（已有车机版，含空调、车辆信息、驾驶辅助等）                         |

**同样是 `packages/apps/Car/Settings/`** —— AOSP 已经提供了车机版 Settings，这是最好的参考实现。

### 【常见追问】

**Q：SystemUI 挂了会怎样？**  
状态栏/导航栏消失但 App 还能用，因为 SystemUI 是独立进程。系统会自动重启它（`SystemUIService` 被 `SystemServer` 监控，有 `crash` 重启逻辑）。**但在车机上这很严重**——状态栏上可能挂着空调温度、时间、挡位显示。

**Q：`StatusBarManagerService` 和 `StatusBar` 是什么关系？**  
前者在 `system_server`，是"可以被别的进程调用的 Binder 服务"（如 `disable()` 控制状态栏可否下拉）；后者在 SystemUI 进程，是"实际的 UI 实现"。前者通过 `IStatusBar` 回调后者。**这种"服务在 system_server、UI 在独立进程"的模式在 Android 里很常见（`WallpaperManagerService` 同理）。**

**Q：为什么 SystemUI 的定制风险高？**  
因为它是所有"系统级 UI"的宿主，一旦改坏，整个系统的视觉会崩，而且它**没有用户可见的错误提示**（用户只看到黑屏/缺元素）。所以实践上要求：① 优先用 overlay 而不是改源码；② 一定要有回归测试清单。

### 【延伸方向】

- 源码：`frameworks/base/packages/SystemUI/`
- 源码：`packages/apps/Car/SystemUI/`（车机版，必读）
- 源码：`packages/apps/Car/Settings/`（车机版设置）
- 源码：`frameworks/base/services/core/java/com/android/server/policy/keyguard/`
- 延伸：RRO（Runtime Resource Overlay）机制，理解 `overlay/` 目录怎么工作

---

## 2.8 SurfaceFlinger 与图形栈（BufferQueue / VSYNC / hwui）

### 【JD 出处】

- BOSS直聘（南京）：*"熟悉 graphic 系统，熟悉 hwui、wms 相关优先，有游戏图形处理经验者优先"*
- 小米南京·Android 图形渲染工程师：*"参与座舱系统图形动效开发与升级优化；参与座舱独立渲染引擎研发；深入研究图形渲染技术 Android 图形架构，包括 OpenGL ES、Vulkan、Skia 等"*

> 这一节是**小米图形渲染岗 + BOSS直聘图形岗**的核心知识区，也是"掉帧/卡顿"类问题的终点站。

### 【要解决的问题】

一个 App 画的每一帧，怎么从"CPU 上的绘制指令"变成"屏幕上的像素"？中间要经过：App 进程 → GPU → 合成器 → 显示控制器。**这条链路是 Android 图形性能的全部战场。**

### 【原理】

#### 全链路总览

```
App 进程
  ┌──────────────────────────────────────────────┐
  │ UI Thread（主线程）                           │
  │   measure / layout / draw(RenderNode)        │
  │        ↓ RenderNode 树（display list）        │
  │ RenderThread                                 │
  │   Skia 绘制指令 → OpenGL ES / Vulkan 调用     │
  └──────────────┬───────────────────────────────┘
                 │ dequeueBuffer / queueBuffer
  ┌──────────────▼──────────────┐
  │ BufferQueue（生产者-消费者）  │
  │  生产者：App 的 RenderThread │
  │  消费者：SurfaceFlinger      │
  └──────────────┬──────────────┘
                 │ acquireBuffer
  ┌──────────────▼──────────────┐
  │ SurfaceFlinger               │
  │  收集所有 Layer 的 buffer     │
  │  → 合成（GPU 合成 / 硬件合成）│
  │  → 提交给 Composer HAL       │
  └──────────────┬──────────────┘
                 │
  ┌──────────────▼──────────────┐
  │ Composer HAL → 显示控制器     │
  │  → Display（MIPI DSI / eDP） │
  └─────────────────────────────┘
```

#### BufferQueue：生产者-消费者模型

```
        ┌──────────────────────────┐
        │      BufferQueue         │
        │  FREE  →  DEQUEUED       │
        │        →  QUEUED         │
        │        →  ACQUIRED       │
        └──────────────────────────┘
   生产者（App）                  消费者（SurfaceFlinger）
   dequeueBuffer() 拿一块空闲的   acquireBuffer() 拿一块已填好的
   queueBuffer()   交回去          releaseBuffer() 用完还回去
```

**关键参数：`max_dequeued_buffer_count`**

- 通常为 **3（三缓冲）**
- 意义：App 最多可以"提前画 3 帧"
- **为什么需要三缓冲**：如果只有双缓冲，App 必须等 SF 释放上一帧才能画下一帧，一旦 SF 慢一点 App 就被卡住 → 抖动。三缓冲给了缓冲空间，但代价是**延迟增加**（画的帧可能要等 2 个 VSYNC 才上屏）

**车机的重要差异**：**车机普遍倾向"低延迟优先"而不是"不丢帧优先"**。倒车影像、360 环视、仪表动画都要求"显示的必须是当前帧"。所以车机上常常把某些 Surface 配置为**双缓冲甚至单缓冲（`max_dequeued_buffer_count=1`，可能丢帧但不累积延迟）**。这是一个非常"车机特色"的取舍，面试时能主动提出来会很有说服力。

```java
// 设置 buffer 数量（Surface 侧）
surfaceHolder.setFixedSize(w, h);
// 或通过 SurfaceControl / Surface 的 API 设置 bufferCount
```

#### VSYNC 与 Choreographer

**VSYNC**（Vertical Synchronization）有两个作用：① 告诉 App"该画下一帧了"；② 告诉 SF"该合成了"。

**Android 12+ 的 VSYNC 分发**：

```
硬件 VSYNC（HW_VSYNC_0）
    ↓
SurfaceFlinger 的 Scheduler
    ├─ 用 VSYNC-sf 的相位通知 SF 合成
    └─ 用 VSYNC-app 的相位通知 App（通过 appEventThread）
        └─ Choreographer.FrameDisplayEventReceiver.onVsync()
            └─ Choreographer.doFrame(frameTimeNanos)
                ├─ CALLBACK_INPUT
                ├─ CALLBACK_ANIMATION
                ├─ CALLBACK_INSETS_ANIMATION
                └─ CALLBACK_TRAVERSAL → performTraversals()
```

**VSYNC offset 的意义**：`VSYNC-app` 通常比 `VSYNC-sf` 提前几毫秒发送，让 App 有时间画完，正好赶上 SF 的合成窗口。**这个 offset 如果配置不当（车机多屏时尤其容易），会表现为"所有动画都差半拍"。**

#### Choreographer 三段式回调的顺序为什么重要

```
doFrame:
  1. INPUT      ← 先处理输入，保证点击响应不滞后
  2. ANIMATION  ← 再跑动画，算出新属性值
  3. TRAVERSAL  ← 最后做布局绘制，用上动画的新值
```

**如果反了会怎样**：动画改了属性值但布局已经做完 → 这一帧显示的是旧值 → 视觉上"慢一帧"。**这个顺序不是随意排的，是"数据流"决定的最优顺序。** 面试被问"为什么输入在最前"就答这个。

#### hwui（Hardware UI）

**是什么**：Android 的硬件加速绘制库，`frameworks/base/libs/hwui/`。它把 `Canvas` 的绘制调用转成 GPU 命令。

**核心机制：`RenderNode` + Display List**

```java
// View.draw() 不是立刻画到屏幕，而是"记录"绘制命令
canvas.drawRect(...);       // → RenderNode 里追加一条 Op
canvas.drawText(...);       // → 再追加一条
```

- 每个 `View` 对应一个 `RenderNode`，里面存着绘制命令列表（display list）
- **`View.draw()` 的产物是"录制"，真正的绘制在 `RenderThread` 上执行**
- 所以：**UI 线程负责"录制 + 布局"，RenderThread 负责"执行绘制"** —— 这是 Android 4.0 引入 RenderThread 的核心收益：**即使 UI 线程忙，绘制也能继续在 RenderThread 上进行，动画不会卡**（这就是"UI 线程卡但动画还流畅"的原因）

**`RenderThread` 的管线**：

```
CanvasContext
  └─ Pipeline（SkiaOpenGLPipeline / SkiaVulkanPipeline）
      └─ Skia 的 GrDirectContext（Ganesh）
          └─ OpenGL ES / Vulkan
```

#### SurfaceFlinger 的合成

**两种合成方式**：

| 方式                             | 谁做                              | 条件                 |
| ------------------------------ | ------------------------------- | ------------------ |
| **Device Composition（硬件合成）**   | `Composer HAL`（Display 硬件里的合成器） | 层数、格式、缩放比例都受硬件限制   |
| **Client Composition（GPU 合成）** | SurfaceFlinger 用 GPU 画          | 超出硬件限制时回退，**耗电且慢** |

**合成决策逻辑**（`SurfaceFlinger::composite`）：

```
对每个 Layer：
  ├─ 是否能被硬件合成器接受？（格式 / 是否旋转 / 是否需 blending）
  ├─ 是否被其他 Layer 完全遮挡？（→ 直接跳过）
  └─ 层数是否超限？
如果所有层都能硬件合成 → 零 GPU 参与（最理想）
否则 → GPU 合成那些不能硬件合成的层
```

**车载的关键约束**：**显示硬件的合成层数通常只有 4–6 层**（成本与功耗考虑）。当车机 UI 变复杂（多个悬浮窗、多屏），层数很容易超标 → GPU 合成 → 掉帧与发热。**这就是"多窗口分屏导致卡顿"的根因，也是车机图形优化的核心战场。**

**`dumpsys SurfaceFlinger` 是排查这类问题的第一工具**：

```bash
adb shell dumpsys SurfaceFlinger              # 全部 Layer 状态与合成方式
adb shell dumpsys SurfaceFlinger --latency <layer>  # 某层的帧延迟直方图
adb shell dumpsys SurfaceFlinger --list       # 列出所有 Layer
adb shell dumpsys SurfaceFlinger --dispsync   # VSYNC 配置
```

#### Perfetto / Systrace：看这条链路的标准工具

```bash
# 抓一段 Perfetto trace
adb shell perfetto -o /data/misc/perfetto-traces/trace.perfetto-trace -t 10s \
  sched freq idle am wm gfx view binder_driver hal dalvik camera input res

adb pull /data/misc/perfetto-traces/trace.perfetto-trace
# 用 https://ui.perfetto.dev 打开
```

**关键轨道**：

| 轨道                           | 看什么                             |
| ---------------------------- | ------------------------------- |
| `Choreographer#doFrame`      | 一帧的总耗时，是否 >16.67ms              |
| `DrawFrame` / `RenderThread` | RenderThread 是否成为瓶颈             |
| `Scheduled / Actual` frame   | 掉帧（Scheduled 有但 Actual 没有 = 丢帧） |
| `SurfaceFlinger`             | 合成耗时、合成方式                       |
| `binder transaction`         | 跨进程调用耗时（找同步 Binder 阻塞）          |
| `CPU Frequency`              | 降频导致的性能下降                       |
| `sched`                      | 线程唤醒延迟、CPU 抢占（车机上常被其他域抢占）       |

### 【常见追问】

**Q：为什么掉帧要看"UI Thread"和"RenderThread"两个轨？**  
因为它们的职责分离了。UI 线程做 measure/layout/record，RenderThread 做 GPU 提交。**如果 UI 线程是瓶颈 → 掉帧且下一帧的测量也慢；如果 RenderThread 是瓶颈 → UI 线程看起来正常但帧还是没上屏。** 分开看才能定位。

**Q：三缓冲让延迟增加多少？**  
最坏情况下增加 2 个 VSYNC 周期。60Hz 下约 33ms，120Hz 下约 16ms。**这个数字解释了为什么"高刷屏 + 三缓冲"的反而是延迟更低的组合** —— 车机上加刷新率不只是为了"顺滑"，也是为了降低触控到显示的延迟。

**Q：为什么 `View.measure` 可能被调用多次？**  
因为 `RelativeLayout`、`LinearLayout`（带 `weight`）这类布局需要"先量一遍才知道孩子多大，再量第二遍"。**官方建站是 `ConstraintLayout` 或 `ConstraintLayout` 的 `Flow`/`Barrier`，因为它能用一次测量解出整棵树**（约束求解）。**车机 UI 复杂但要求低延迟，布局层级应当更扁平**——这是"车机 UI 优化"与"手机 UI 优化"的一个不同侧重。

**Q：`SurfaceTexture` / `TextureView` 与 `SurfaceView` 的区别？**

- `SurfaceView`：有独立 `Surface`（独立图层），交给 SF 合成。**性能好、支持硬件叠加，但不能做变换动画**（在 Android 10 之前）
- `TextureView`：把内容作为纹理合成到 View 树里，**支持变换动画但多一次 GPU 拷贝、耗电**
- `SurfaceTexture`：`SurfaceView`/`TextureView` 内部用的机制，也是相机预览、视频解码输出到 GL 的关键

**车机建议**：视频播放、倒车影像、相机预览用 `SurfaceView`（低延迟、低功耗）；需要动画变形时才用 `TextureView`。

### 【延伸方向】

- 源码：`frameworks/native/services/surfaceflinger/`（`SurfaceFlinger.cpp`、`BufferQueue.cpp`、`Layer.cpp`、`Scheduler/`）
- 源码：`frameworks/base/libs/hwui/`（`RenderThread.cpp`、`SkiaPipeline.cpp`、`RenderNode.cpp`）
- 源码：`frameworks/base/core/java/android/view/Choreographer.java`、`ThreadedRenderer.java`
- 工具：Perfetto UI、`dumpsys SurfaceFlinger`、`dumpsys gfxinfo <pkg> framestats`
- 延伸：Composer HAL（`android.hardware.graphics.composer`）与 `HWC` 的层数限制
- 延伸：Vulkan 在 Android 图形栈里的位置（`SkiaVulkanPipeline`、`Vulkan` 原生开发）

---

## 2.9 AOSP 编译、裁剪、定制全流程

### 【JD 出处】

- 光庭信息：*"熟悉 Android AOSP 编译、裁剪、定制流程，精通 Framework 系统层开发 或 HAL 层（HIDL/AIDL）硬件适配开发"*
- 颖派科技：*"基于 AOSP 源码构建系统镜像；实现系统构建流程的自动化"*
- 华苏科技：*"完成 ROM 对主流硬件平台（高通/MTK 等）的适配与驱动整合"*

> 这一节是"从 B 档跨到 A 档"的关键。**只要真正跑通过一次完整闭环（sync → build → flash → verify），你在面试里的段位就变了。**

### 【原理】

#### 源码获取

```bash
# 1. 安装 repo 工具
mkdir ~/bin && curl https://storage.googleapis.com/git-repo-downloads/repo > ~/bin/repo
chmod a+x ~/bin/repo

# 2. 初始化（以 Android 14 为例）
repo init -u https://android.googlesource.com/platform/manifest \
          -b android-14.0.0_r1 --depth=1

# 3. 同步（首次会下载 ~100GB+）
repo sync -c -j8 --no-tags --no-clone-bundle

# 国内加速（如已配镜像）
# 把 manifest 换成清华/中科大镜像
```

**仓库结构**：

```
aosp/
├── build/              # 构建系统（Soong、Kati、Make）
├── frameworks/         # base（framework）、av（媒体）、native、opt
├── packages/
│   ├── apps/           # Settings、SystemUI（在 frameworks/base/packages）、Launcher3…
│   └── services/Car/   # CarService
├── hardware/
│   ├── interfaces/     # HAL 接口定义（HIDL .hal / AIDL .aidl）
│   ├── libhardware/    # HAL 加载器
│   └── qcom/ · mediatek/  # 平台相关（第三方，不在 AOSP）
├── system/
│   ├── core/           # init、liblog、libcutils
│   └── sepolicy/       # SELinux 策略
├── device/<vendor>/<device>/   # 板级配置（BoardConfig.mk、device.mk）
├── vendor/<vendor>/            # 厂商私有（通常预编译）
└── out/                # 构建产出
```

#### 构建系统：Soong（Android.bp）+ Kati（Android.mk）+ Ninja

```
Android.mk（旧）→ Kati → ninja 文件 ┐
                                     ├→ Ninja → 编译
Android.bp（新）→ Soong → ninja 文件 ┘
```

| 构建描述              | 文件            | 现在的地位           |
| ----------------- | ------------- | --------------- |
| Soong / Blueprint | `Android.bp`  | **主流**，简洁的声明式语法 |
| Make              | `Android.mk`  | 存量，Kati 转换      |
| Ninja             | `build.ninja` | 底层执行器（生成的，不手写）  |

```python
# Android.bp 示例：一个系统应用
android_app {
    name: "MyCarApp",
    srcs: ["src/**/*.java"],
    resource_dirs: ["res"],
    manifest: "AndroidManifest.xml",
    certificate: "platform",       // 用 platform key 签名
    privileged: true,              // 装到 priv-app
    platform_apis: true,           // 可访问 @hide / @SystemApi
    static_libs: ["androidx.appcompat_appcompat"],
    optimize: { enabled: false },
}
```

| 关键字段                                | 含义                                          |
| ----------------------------------- | ------------------------------------------- |
| `certificate`                       | `"platform"` / `"shared"` / `"media"` / 自定义 |
| `privileged`                        | 是否进 `priv-app`                              |
| `platform_apis`                     | 是否可用平台隐藏 API                                |
| `required`                          | 依赖的额外模块（如 `privileged-permission` xml）      |
| `product_specific` / `vendor: true` | 装到 `product` / `vendor` 分区                  |

#### `lunch` 与产品配置

```bash
source build/envsetup.sh
lunch aosp_arm64-userdebug        # 通用
lunch <product>-<buildtype>       # 例如 sdk_car_x86_64-userdebug（车机模拟器）
```

**build type 的区别（重要）**：

| 类型          | 权限                                  | 用途          |
| ----------- | ----------------------------------- | ----------- |
| `user`      | 最严格，无 root、无调试                      | 量产          |
| `userdebug` | 有 root（`adb root`）、可改系统、`verity` 可关 | **开发/调试**   |
| `eng`       | 全部调试模块编译进来                          | 早期 bring-up |

**产品配置的关键文件**：

| 文件                                           | 内容                                           |
| -------------------------------------------- | -------------------------------------------- |
| `device/<v>/<d>/BoardConfig.mk`              | 架构、分区大小、内核、`TARGET_*`                        |
| `device/<v>/<d>/device.mk`                   | `PRODUCT_*` 变量、`PRODUCT_PACKAGES`（要打包哪些模块）   |
| `build/make/target/product/*.mk`             | 通用产品模板（如 `aosp_base.mk`、`generic_system.mk`） |
| `device.mk` 里 `PRODUCT_PACKAGES += MyCarApp` | 把模块打进系统镜像                                    |

#### 编译与产物

```bash
m                    # 全量编译（等价 make -jN from root）
mka                  # 同上，soong 优化
m <module>           # 只编译某个模块（如 m MyCarApp）
mm                   # 编译当前目录的模块
mmm <dir>            # 编译指定目录

# 产物在 out/target/product/<device>/
system.img        # system 分区
vendor.img        # vendor 分区（Treble 之后必须独立）
product.img       # product 分区
system_ext.img
boot.img          # kernel + ramdisk
super.img         # 动态分区（system+vendor+product+odm 的容器）
```

**动态分区（Dynamic Partitions）**：分区大小不再固定，`super` 分区里动态分配。改分区大小只需改配置并 `lpmake`/`lpdump`，不需要重划分 eMMC。**这是 Android 10 之后必须理解的概念。**

```bash
# 查看动态分区
adb shell lpdump
# 手动调整（调试用）
adb shell lpmake ...
```

#### 烧写与验证

```bash
adb reboot bootloader
fastboot flashall -w          # 刷全部镜像（-w 会清 data）
fastboot flash system system.img
fastboot flash super super.img
fastboot reboot

# 单分区刷写（部分平台）
adb reboot fastboot           # 进 fastbootd（用户空间的 fastboot，支持动态分区）
fastboot flash system system.img
```

**关键调试开关**（bring-up 阶段的救命稻草）：

```bash
# 关闭 verity/dm-verity（改系统后不刷整包也能生效）
adb root && adb remount
adb disable-verity
adb enable-verity

# 关闭 SELinux 强制（仅调试！）
adb shell setenforce 0
# 或在 cmdline 里加 androidboot.selinux=permissive
```

#### 构建流程自动化（颖派 JD 的点）

```bash
# 典型的 CI 脚本骨架
#!/bin/bash
set -e
repo sync -c -j$(nproc) --no-tags
source build/envsetup.sh
lunch ${PRODUCT}-userdebug
make -j$(nproc) dist        # dist 生成 dist/ 目录，含 OTA 包
# 或
m -j$(nproc) otapackage     # 只出 OTA 包
```

**`m dist` vs `m otapackage`**：`dist` 会把所有需要的产物汇总到 `out/dist/`（含 `*-target_files-*.zip`），`otapackage` 只出 OTA。**OTA 包的生成用 `ota_from_target_files`（script 在 `build/make/tools/releasetools/`）。**

#### Treble、VNDK 与分区

**Treble（Android 8+）** 把系统分成两半：

```
system.img（Android Framework，可独立升级）
    ↕ VNDK（Vendor NDK，稳定的 native 接口）
vendor.img（HAL + 驱动，由 SoC 厂商提供）
```

**为什么要这样**：让 OEM 能升级 Android 版本而不动 vendor 部分，从而"更便宜地做系统升级"。

**VNDK 的意义**：vendor 侧的 native 代码只能用 VNDK 里提供的稳定接口，不能用 `libui`、`libgui` 之类的私有库。**这是一个强约束** —— 如果你在 HAL 里用了非 VNDK 库，编译会报 `VNDK violation`。**这是 HAL 开发最常踩的坑之一。**

### 【常见追问】

**Q：改了 Framework 的代码，怎么快速验证而不刷整包？**  
`adb root && adb remount` 后把改动的 APK/JAR push 进去，然后重启对应进程（`adb shell stop && adb shell start` 重启 framework；或 `killall system_server` 让它自己重启）。**但改 native 库或 init 相关的东西往往必须刷包。**

**Q：为什么我的模块没进系统镜像？**  
三条排查路径：① `PRODUCT_PACKAGES` 里有没有加；② 模块的 `Android.bp` 里 `product_specific` / `vendor` 有没有配对分区；③ 有没有被 `PRODUCT_PACKAGES` 的 `overrides` 或 `include` 规则冲掉。**`out/target/product/<device>/system/priv-app/` 下看文件在不在，是最直接的验证。**

**Q：`VNDK violation` 怎么解决？**  
三条路：① 换成 VNDK 内的等价库；② 把库静态编译进你的模块（消除运行时依赖）；③ 把库加入 `vndk.private` / `vndk.sp`（不推荐，会破坏 Treble 隔离）。**面试如果被问到这个，说明面试官做过真实的 HAL 开发。**

**Q：`userdebug` 和 `eng` 上能做的事，量产版本能做吗？**  
不能。`user` 版本：`adb root` 不可用、`remount` 不可用、`dm-verity` 强制开启、SELinux enforcing 且不能改。**所以"在 userdebug 上测得好好的，量产版出问题"是车机项目的经典事故类型，根因通常是"依赖了调试权限"。**

### 【延伸方向】

- 官方文档：source.android.com 的 "Build a System Image"、"Soong"、"Treble"、"Dynamic Partitions"
- 实践建议：**只做一次完整闭环就够** —— `repo sync` 一个小型 AOSP（或用已下载的 SDK 源码）、`lunch aosp_arm64-userdebug`、`m`、在模拟器上启动。走通一次的价值远大于读十遍文档
- 延伸：RRO/overlay、`PRODUCT_*` 变量全景、`init.rc` 语法与 `init` 内置命令
- 延伸：OTA（A/B seamless update、`update_engine`、`payload.bin`、`delta_generator`）

---

## 2.10 系统应用工程实践：从 Android.bp 到 priv-app 集成

### 【JD 出处】

- 熊猫通信：*"负责 Android 平台系统应用的定制与维护；能配合底层团队完成 Framework 功能的扩展及修改"*
- 润和软件（座舱）：*"熟悉 Android 系统应用开发流程，具有嵌入式开发经验"*
- 小米南京：*"具备良好的代码规范，掌握常见的设计模式…"*

> 这一节是**为你量身定做的**——你已经在这套东西里干了 9 年。这里做的是"把你已经在做的事，整理成可以讲出口的体系"。

### 【原理】

#### 系统应用的三种"特权形态"

| 形态             | 配置                                          | 能做什么                                          |
| -------------- | ------------------------------------------- | --------------------------------------------- |
| **平台签名**       | `certificate: "platform"`                   | 获得 `signature` 级别权限；与 platform 同签名            |
| **privileged** | `privileged: true`（装到 `priv-app`）           | 获得 `signature\|privileged` 级别权限               |
| **system uid** | `android:sharedUserId="android.uid.system"` | 与 system_server 同 UID，可读写系统状态、调用 `@SystemApi` |

**三者是正交的，组合使用**：

```
certificate: platform  +  privileged: true  +  sharedUserId=system
        ↓                       ↓                    ↓
   签名级别权限            特权权限白名单          uid 级别特权
```

#### 完整集成流程（可以讲成"规范"）

```
① 定义应用
   Android.bp：
     android_app {
       name: "MySystemApp",
       certificate: "platform",
       privileged: true,
       platform_apis: true,          // 允许使用 @hide
       required: ["privapp-permissions-myapp"],
       ...
     }

② 权限声明
   AndroidManifest.xml：
     <uses-permission android:name="android.permission.CONTROL_KEYGUARD"/>
     <uses-permission android:name="android.permission.STATUS_BAR"/>
     <uses-permission android:name="android.permission.MANAGE_USERS"/>

③ 白名单（Android 9+ 必须）
   /system/etc/permissions/privapp-permissions-myapp.xml：
     <permissions>
       <privapp-permissions package="com.example.myapp">
         <permission name="android.permission.CONTROL_KEYGUARD"/>
         <permission name="android.permission.STATUS_BAR"/>
       </privapp-permissions>
     </permissions>

④ 打进镜像
   device.mk：PRODUCT_PACKAGES += MySystemApp privapp-permissions-myapp

⑤ 编译烧写
   m MySystemApp && adb root && adb remount
   adb push out/.../MySystemApp.apk /system/priv-app/MySystemApp/
   # 注意：push 之后必须重启（PMS 会重新扫描）
```

#### `@hide` / `@SystemApi` / `@TestApi` —— 三种 API 可见性

| 注解           | 含义               | 谁能用                                    |
| ------------ | ---------------- | -------------------------------------- |
| 无            | 公开 API           | 所有 App                                 |
| `@SystemApi` | 系统 API           | 平台签名 / 特权应用                            |
| `@hide`      | 隐藏 API（不在 SDK 里） | 需要 `platform_apis: true` 编译，且运行时通常需要特权 |
| `@TestApi`   | 测试专用             | 测试应用                                   |

```java
// 使用 @hide API 时的典型写法
// 编译期：platform_apis: true 让 javac 能找到这些类
// 运行期：需要特权（否则会抛 SecurityException）
try {
    Method m = SomeHiddenClass.class.getMethod("someMethod");
    m.invoke(...);
} catch (...) { }
```

**这是系统应用开发的核心技能之一**：知道哪些能力是"能用的"，哪些是"看起来能用但会被权限拦住的"。

#### 与 Framework 协作的三种方式

| 方式                     | 场景        | 示例                                                 |
| ---------------------- | --------- | -------------------------------------------------- |
| **调用已有系统服务**           | 用现成能力     | `ActivityManager`、`PackageManager`、`WindowManager` |
| **反射 `@hide` API**     | 用没公开的 API | `SystemProperties`、`WindowManagerGlobal`           |
| **定制 Framework 暴露新接口** | 现有能力不够    | 在 framework 里加一个 `@SystemApi`，或在 AIDL 里加方法         |

**第三条是"配合底层团队完成 Framework 功能的扩展及修改"的实际含义**（熊猫通信 JD 原话）。工程上要注意：**改 Framework 的 API 加在哪个位置** —— 加在 `core/java/android/app/` 下（对所有 App 可见，但要谨慎）、还是加一个厂商专属的 AIDL 服务。

#### 调试系统应用的特有手段

```bash
# 1. 重置权限授予状态（改权限白名单后）
adb shell pm clear <pkg>

# 2. 看某 App 实际被授予了哪些权限
adb shell dumpsys package <pkg> | grep -A50 "grantedPermissions"

# 3. 看特权权限白名单是否生效
adb logcat | grep -i "privapp"
# 若 enforce 模式下缺少白名单，会出现：
# "Privileged permission X for package Y not in privapp-permissions whitelist"

# 4. 临时改成"只警告不失败"（调试用，不能上量产）
# 在 device.mk 里：
# PRODUCT_PRODUCT_PROPERTIES += ro.control_privapp_permissions=log
```

### 【常见追问】

**Q：为什么 Android 9 之后加 `privapp-permissions` 白名单这一层？**  
因为之前"放到 `priv-app` 就能拿到所有特权权限"太宽松，恶意预装应用（尤其是第三方预装）可以静默获得致命权限。白名单强制 OEM 显式声明"这个应用为什么需要这个特权权限"，是一个**安全审计门槛**。

**Q：`sharedUserId` 在 Android 10 之后有什么变化？**  
**已废弃**（deprecated）。原因是它与"每个应用独立 UID"的安全模型冲突，且阻碍了应用安装优化。**替代方案**：用 `signature` 权限 + 共享数据通过 `ContentProvider`（`android:exported` + 签名级别权限保护）。**但系统应用仍在大量使用 `sharedUserId`** —— 因为量产项目没法为了架构洁癖去重构。**面试时如果被问到，能说清"已废弃但系统应用仍在用"这个现实，会显得你既有理论认知又懂工程实践。**

**Q：系统应用可以 `kill` 别的进程吗？**  
持有 `android.permission.KILL_BACKGROUND_PROCESSES` 可以调用 `ActivityManager.killBackgroundProcesses()`（但只能杀后台）；`android.uid.system` 有更强制的手段（`Process.killProcess`、甚至 `am force-stop` 的等价实现）。**但这是高风险的** —— 车机上误杀关键进程会导致功能失效。

### 【延伸方向】

- 阅读 AOSP 里一个真实的车机系统应用：`packages/apps/Car/` 下的任意模块，看它的 `Android.bp` 配置
- 延伸：`AppOpsManager`（比权限更细粒度的运行时控制）
- 延伸：SELinux 策略（`system/sepolicy/`）—— 系统应用的"最后一层门"往往是 SELinux 而不是权限。（`avc: denied` 日志是系统应用集成的日常）
- 延伸：`system/sepolicy/private/` 与 `vendor/` 的策略分离规则

---

# 第三部分：应用层硬功夫

## 3.1 Kotlin 协程原理（CPS 变换与状态机）

### 【JD 出处】

- 焦点科技：*"熟练掌握 Kotlin 和 Java 语言，了解 kotlin 协程等高阶用法"*
- 上海之几：*"精通 Kotlin 语言，熟悉协程、扩展函数等特性"*
- 光庭信息（车机）：*"熟练掌握 Kotlin 和 Java 语言，了解 kotlin 协程等高阶用法"*

> 协程已是 Kotlin 岗的硬门槛。**面试深挖点几乎一定落在"挂起函数怎么实现的"** —— 因为这一层能区分"会用"和"懂"。

### 【要解决的问题】

Android 的异步编程历史：`Thread` → `AsyncTask` → `Handler` → `RxJava` → **协程**。每一步都在解决上一步的痛点：

| 方案          | 痛点                          |
| ----------- | --------------------------- |
| `Thread`    | 无法取消、无法组合、无返回值              |
| `AsyncTask` | 回调地狱、内存泄漏、API 设计差           |
| `RxJava`    | 学习曲线陡、栈深、调试困难               |
| **协程**      | 用同步写法表达异步，**可取消、可组合、结构化并发** |

**协程的核心卖点是"用同步代码的形态写异步逻辑"** —— 没有回调、没有 `flatMap`、有正常的 try/catch。

### 【原理】

#### CPS 变换（Continuation-Passing Style）

```kotlin
// 你写的
suspend fun fetchUser(id: String): User {
    val name = fetchName(id)        // 挂起点 1
    val age = fetchAge(id)          // 挂起点 2
    return User(name, age)
}
```

编译器改写后（等价形态）：

```kotlin
// 编译器生成的东西（伪代码）
fun fetchUser(id: String, completion: Continuation<User>): Any? {
    val sm = completion as? FetchUserContinuation
        ?: FetchUserContinuation(completion)

    when (sm.label) {
        0 -> {
            sm.label = 1
            sm.id = id
            val result = fetchName(id, sm)     // 传自己作为 continuation
            if (result == COROUTINE_SUSPENDED) return COROUTINE_SUSPENDED
            sm.result1 = result
            // fallthrough
        }
        1 -> {
            sm.result1 = sm.result            // 恢复时结果在这里
        }
    }
    // ... 第二段
    when (sm.label) {
        1 -> {
            sm.label = 2
            val result = fetchAge(sm.id, sm)
            if (result == COROUTINE_SUSPENDED) return COROUTINE_SUSPENDED
            sm.result2 = result
        }
        2 -> { sm.result2 = sm.result }
    }
    return User(sm.result1, sm.result2)
}
```

**三个关键点**：

1. **`suspend` 函数被改写为多了一个 `Continuation` 参数的普通函数**，返回类型是 `Any?`（因为要能返回 `COROUTINE_SUSPENDED` 这个哨兵值）
2. **函数内的局部变量被"提升"为 continuation 对象的字段**（因为挂起后栈会销毁，变量必须存在堆上）
3. **用 `label` 做状态机**，每次恢复从上次的 label 继续执行

**这个机制叫 CPS 变换。** 面试如果只能答一句，就答："`suspend` 函数在编译期被改写成带 `Continuation` 参数、用 `label` 做状态机的普通函数。挂起点返回 `COROUTINE_SUSPENDED`，恢复时通过 `continuation.resumeWith` 从 label 处继续。"

#### 挂起与恢复的完整链路

```kotlin
// 发起
lifecycleScope.launch(Dispatchers.Main) {
    val user = fetchUser("123")      // 挂起
    updateUi(user)                   // 恢复后执行
}
```

```
1. launch 创建一个 StandaloneCoroutine（继承 AbstractCoroutine）
   └─ 生成 Continuation 包装 updateUi 之后的部分
   └─ 通过 Dispatcher 派发 coroutine.start()

2. 执行 fetchUser()
   └─ 内部调用 fetchName()（也是一个挂起函数）
       └─ 真正的挂起点：一个 suspendCancellableCoroutine / withContext
           └─ 注册一个回调（如 OkHttp 的 Callback / withContext 的 DispatchedContinuation）
           └─ 返回 COROUTINE_SUSPENDED

3. fetchUser 收到 COROUTINE_SUSPENDED → 直接 return COROUTINE_SUSPENDED
   └─ launch 的协程体也 return → 线程被释放（关键：不阻塞线程！）

4. 异步操作完成（OKHttp 回调 / 另一个线程做完）
   └─ 调用 continuation.resumeWith(Result.success(value))
       └─ 检查是否需要切换线程（Dispatcher.isDispatchNeeded）
           ├─ 需要 → dispatcher.dispatch(continuation) 投到目标线程
           └─ 不需要 → 直接 resumeWith 在当前线程继续
               └─ 从 label 位置继续执行 fetchUser

5. fetchUser 返回真正的 User → 协程体继续执行 updateUi(user)
```

**最关键的一点**：**挂起不等于阻塞**。挂起是"保存现场后放弃线程"，线程立刻去做别的事；恢复时"找回来继续跑"。**这就是协程能做到"用少量线程跑大量并发任务"的原因。**

#### 调度器（Dispatcher）

| Dispatcher                   | 背后                                 | 用途               |
| ---------------------------- | ---------------------------------- | ---------------- |
| `Dispatchers.Main`           | 主线程 Handler（`HandlerContext`）      | UI 更新            |
| `Dispatchers.Main.immediate` | 同上，但若已在主线程则直接执行（不 post）            | 避免不必要的一次 post 延迟 |
| `Dispatchers.Default`        | `SharedThreadPool`（CPU 核数，最少 2）    | **CPU 密集**计算     |
| `Dispatchers.IO`             | `SharedThreadPool`（默认 64 线程，可按需增长） | **IO 阻塞**操作      |
| `Dispatchers.Unconfined`     | 不切换，在当前线程跑                         | 测试 / 特殊场景        |
| `limitedParallelism(n)`      | 限制并发数（Kotlin 1.6+）                 | 限流               |

**`Default` 与 `IO` 的区别是高频追问**：`Default` 是 CPU 密集型（线程数 ≈ 核数），`IO` 是阻塞型（线程数多，因为线程大部分时间在等）。**用错会怎样**：在 `Default` 里做阻塞 IO 会占住 CPU 核数的配额，导致其他计算任务排队。

#### 结构化并发

```kotlin
suspend fun loadAll() = coroutineScope {          // ← 结构化并发
    val a = async { fetchA() }
    val b = async { fetchB() }
    a.await() to b.await()
}
// 若 fetchA 抛异常 → coroutineScope 取消所有子协程 → 异常向上传播
```

**四条规则**：

1. **父协程等待所有子协程完成**（`coroutineScope` 会 suspend 直到子全部结束）
2. **子协程失败会取消父协程**（除非用 `supervisorScope` / `SupervisorJob`）
3. **取消会向下传播**（父被取消 → 所有子被取消）
4. **`CancellationException` 不视为错误**（不会触发 `CoroutineExceptionHandler`）

**为什么结构化并发重要**：解决了 `GlobalScope` 时代的"协程泄漏"问题。**`GlobalScope` 是反模式** —— 它的生命周期与 App 一样长，任何 `launch` 进去的协程都会在后台永远跑下去（除非手动取消）。

**Android 上的正确用法**：

| 场景                | 作用域                                                              |
| ----------------- | ---------------------------------------------------------------- |
| Activity/Fragment | `lifecycleScope`（`onDestroy` 自动取消）                               |
| ViewModel         | `viewModelScope`（`onCleared` 自动取消）                               |
| 自定义生命周期           | `CoroutineScope(SupervisorJob() + Dispatchers.IO)` + 手动 cancel   |
| 与 Flow 结合         | `repeatOnLifecycle(Lifecycle.State.STARTED) { flow.collect {} }` |

#### 取消的协作式实现

```kotlin
suspend fun longWork() {
    repeat(1000) { i ->
        ensureActive()          // ← 检查取消（抛 CancellationException）
        // 或 if (!isActive) return
        doChunk(i)
    }
}
```

**关键认知**：**协程的取消是协作式的**。`job.cancel()` 只是设置标志位，不会强杀线程。所以：

- 纯 CPU 循环必须自己检查 `isActive` / 调 `ensureActive()`
- `delay()`、`yield()`、所有 Kotlin 的挂起函数都是可取消的（内部检查过）
- **阻塞式 API（`Thread.sleep`、`InputStream.read`）不可取消** → 必须换成 `delay`、`withContext(Dispatchers.IO)` + 中断检查

**这一条是"协程不生效/取消不掉"类问题的根因。**

### 【常见追问】

**Q：协程和线程是什么关系？**  
**协程是"用户态的轻量任务"，线程是它的执行载体。** 一个线程可以跑成千上万个协程（因为协程挂起时会让出线程）。但协程本身不做并行——并行仍然靠多线程。**所以"协程比线程快"这个说法是错的，正确的是"协程比线程省资源、更容易组合"。**

**Q：`runBlocking` 为什么不能在主线程用？**  
它会**阻塞当前线程**直到协程完成。在主线程用它 = 把主线程堵死 → ANR。它只该用在 `main()` 测试、或者从"非协程世界"进入"协程世界"的边界处（且必须不在主线程）。

**Q：`withContext` 和 `launch` 的区别？**  
`withContext` 会**挂起当前协程并等待结果返回**（有返回值，像同步调用）；`launch` **立即返回 `Job`**，不等待（fire-and-forget）。**`withContext` 是"切换线程执行一段代码"，`launch` 是"开一个后台任务"。**

**Q：`async` 里的异常什么时候抛？**  
`async` 的异常被延迟到 `await()` 时才抛（在 `CoroutineScope` 内）。**但如果 `async` 在 `coroutineScope` 里且没有被 `await`，异常仍会导致整个 scope 失败** —— 因为它是"子协程失败"而非"未捕获异常"。这是一个经典陷阱，用 `supervisorScope` 可以隔离。

### 【延伸方向】

- 源码：`kotlinx.coroutines` 的 `Builders.common.kt`、`DispatchedContinuation.kt`、`CoroutineDispatcher.kt`
- 反编译实验（强烈推荐）：写一个小 suspend 函数，用 `javap -c` 或 Android Studio 的 "Show Kotlin Bytecode → Decompile" 看生成的 `Continuation` 类与状态机。**看一眼胜过读十篇博客。**
- 延伸：`kotlin.coroutines.ContinuationInterceptor`、`ThreadContextElement`（用于传递上下文，如 MDC 日志）
- 延伸：`Flow` 与协程的关系（见 3.2）

---

## 3.2 Flow / StateFlow / SharedFlow

### 【JD 出处】

- 同上各 Kotlin 岗；车机岗中"属性订阅 → UI 更新"的链路通常要求 Flow

### 【要解决的问题】

协程解决"单次异步"，但车机场景是**持续的数据流**：车速每秒变 10 次、车门状态偶尔变、倒车信号偶尔来。需要一个"可以连续发出多个值、可取消、可组合、有背压"的抽象。这就是 `Flow`。

### 【原理】

#### 冷流 `Flow`：每次收集都重新执行

```kotlin
fun speedFlow(car: Car): Flow<Float> = flow {
    val mgr = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
    val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            // 问题：flow builder 里不能直接 emit（emit 是 suspend）
        }
        override fun onErrorEvent(propId: Int, areaId: Int) {}
    }
    mgr.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, 10f)
    try {
        // 需要把回调桥接成流
    } finally {
        mgr.unregisterCallback(callback)   // ← 关键：收集结束时清理
    }
}
```

**这个例子暴露了 `flow {}` 的局限**：它不能直接消费"回调式"数据源（`emit` 是 suspend 函数，不能从普通回调线程调）。**正确解法是 `callbackFlow`：**

```kotlin
fun speedFlow(car: Car): Flow<Float> = callbackFlow {
    val mgr = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager

    val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (value.status == CarPropertyValue.STATUS_AVAILABLE) {
                trySend(value.value as Float)      // ← 非 suspend，可在回调里调
            }
        }
        override fun onErrorEvent(propId: Int, areaId: Int) {
            close(CarPropertyException("error $propId/$areaId"))
        }
    }

    mgr.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, 10f)

    awaitClose {
        mgr.unregisterCallback(callback)           // ← 上游取消时清理
    }
}
```

**`callbackFlow` / `awaitClose` 这一对是"把回调 API 适配成 Flow"的标准写法**，在车机开发里几乎天天用（因为 Car API 大量是回调式的）。**这一段如果能在面试里手写出来，含金量很高。**

#### `flow {}` 与 `channelFlow` / `callbackFlow` 的本质区别

|              | `flow {}`                 | `channelFlow` / `callbackFlow` |
| ------------ | ------------------------- | ------------------------------ |
| 上游执行位置       | 与收集者同一协程（顺序执行）            | 可以有独立协程（内部用 `Channel`）         |
| `emit` 可调用位置 | 只能在 `flow {}` 内部（suspend） | 可用 `trySend`（非 suspend）        |
| 用途           | 数据变换、异步序列                 | **桥接回调 / 多源合并 / 并发生产**         |
| 背压           | 天然（上下游同速）                 | 通过 `Channel` 缓冲（需注意 buffer 大小） |

**"为什么 `flow {}` 不能从回调里 emit"这个问题的答案**：因为 `flow {}` 的发射逻辑与收集逻辑跑在同一个协程里（这是为了让"冷流每次收集重新执行"成立）。回调来自外部线程/线程池，与那个协程不同源，所以需要 `Channel` 做桥接 —— 这就是 `channelFlow` 存在的理由。

#### `StateFlow` 与 `SharedFlow`

```kotlin
// StateFlow：有初始值、总是有值、去重、粘性
private val _speed = MutableStateFlow(0f)
val speed: StateFlow<Float> = _speed.asStateFlow()

// SharedFlow：无初始值、可配置 replay、可配置缓冲与溢出策略
private val _events = MutableSharedFlow<CarEvent>(
    replay = 0,
    extraBufferCapacity = 16,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
)
val events: SharedFlow<CarEvent> = _events.asSharedFlow()
```

| 特性   | `StateFlow`         | `SharedFlow`                              |
| ---- | ------------------- | ----------------------------------------- |
| 初始值  | 必须提供                | 不需要                                       |
| 当前值  | `.value` 可读         | 无（要靠 replay）                              |
| 去重   | **自动（值相等不发射）**      | 不去重                                       |
| 粘性   | 总是把最后值给新订阅者         | 由 `replay` 决定                             |
| 缓冲   | 容量 1（Conflated）     | 可配 `extraBufferCapacity`                  |
| 丢弃策略 | 覆盖旧值（类似 `conflate`） | `SUSPEND` / `DROP_OLDEST` / `DROP_LATEST` |
| 适用   | **UI 状态**           | **事件（一次性通知）**                             |

**核心选择规则**：

- **状态**（车速、车门状态、UI 数据）→ `StateFlow`
- **事件**（导航播报、Toast、跳转指令）→ `SharedFlow(replay = 0)`

**`StateFlow` 为什么会自动去重，这是个坑**：

```kotlin
_speed.value = 10f
_speed.value = 10f   // ← 不会发射（equals 判定相等）
```

所以它适合"状态"（关心最终值），**不适合"事件"**（同值事件也需要通知）。**这就是为什么一次性的 Toast 要用 `SharedFlow`。**

#### 背压（Backpressure）

Flow 的背压处理有三种：

```kotlin
flow {
    repeat(1000) { emit(it) }             // 生产快
}.buffer(64)                              // ① 加缓冲（Channel）
 .conflate()                             // ② 丢弃中间值（只保最新）
 .collect { delay(100); handle(it) }      // 消费慢
```

| 操作符             | 语义                               | 场景           |
| --------------- | -------------------------------- | ------------ |
| `buffer(n)`     | 加 n 容量缓冲，生产与消费并行                 | 让上下游解耦       |
| `conflate()`    | 等价 `buffer(1, DROP_OLDEST)`，只保最新 | 状态类（UI 只要最新） |
| `collectLatest` | 新值到来时取消上一个处理                     | 搜索、按最新输入请求   |
| 都不加，默认          | 生产被消费阻塞（同步）                      | 需要严格顺序且量小    |

**车机场景的实际选择**：车速 → `conflate()`（UI 只关心最新值，不需要每个值都渲染）；事件类（车门开）→ `buffer()`（每个事件都不能丢）。

#### 生命周期安全（Android 特有）

```kotlin
// ❌ 错误：界面不可见时仍在收集
lifecycleScope.launch {
    speedFlow(car).collect { updateUi(it) }    // 后台时还在跑 → 浪费 + 隐患
}

// ✅ 正确：只在 STARTED 及以上状态收集
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        speedFlow(car).collect { updateUi(it) }
    }
}
```

**`repeatOnLifecycle` vs `flowWithLifecycle`**：前者用于"一段代码里收集多个流"，后者用于"单个流"。**这是 Android 官方推荐的 Flow 收集姿势**，面试常问。

### 【常见追问】

**Q：`StateFlow` 和 `LiveData` 怎么选？**

|        | `LiveData`                  | `StateFlow`                 |
| ------ | --------------------------- | --------------------------- |
| 生命周期感知 | **内建**（自动在 STARTED 停止）      | 需 `repeatOnLifecycle`       |
| 线程     | 主线程（`setValue` 必须主线程）       | 任意（线程安全）                    |
| 操作符    | 少（`map`/`switchMap`）        | **丰富（Flow 全家桶）**            |
| 初始值    | 可为 null                     | 必须提供                        |
| 测试     | 需 `InstantTaskExecutorRule` | 天然可测（`runTest` + `Turbine`） |

**结论**：新代码用 `StateFlow`，`LiveData` 主要用于"和 XML/DataBinding 配合"或"已有代码"。**车机上因为数据源密集（属性回调），`StateFlow` 的线程安全和操作符优势更明显。**

**Q：`SharedFlow` 的事件丢了怎么办？**  
三个原因：① `replay = 0` 且订阅者在发射之前还没订阅（**最常见**）→ 需要保证订阅早于发射，或给 `replay = 1`；② `extraBufferCapacity = 0` 且用 `tryEmit` → 直接失败；③ `onBufferOverflow` 设为 DROP。**工程建议：事件流给 `extraBufferCapacity ≥ 4` + `onBufferOverflow = DROP_OLDEST`，并且用 `Channel(BUFFERED)` 做一次性事件的另一种选择。**

**Q：属性回调很密集（如 100Hz 车速），直接打进 `StateFlow` 会怎样？**  
会每 10ms 触发一次重组/渲染，浪费 CPU。三个解法：① 订阅时就要低 rate（10Hz 足够画 UI）；② 用 `.conflate()` 或 `.sample(100)`（Kotlin 1.8+）节流；③ 在 UI 层做帧同步（`withFrameNanos`）。**车机 UI 的耗电与发热有相当一部分来自"没必要的高频刷新"。**

### 【延伸方向】

- 必读：`kotlinx.coroutines/flow/` 下的 `StateFlow.kt`、`SharedFlow.kt`、`Merge.kt`
- 实践：把项目里所有 Car 属性回调改写成 `callbackFlow`，观察代码量的下降
- 测试：`Turbine` 库（测 Flow 的最佳工具）
- 延伸：`Flow` 与 `Channel` 的关系（`Channel` 是 `Flow` 的底层之一）

---

## 3.3 Jetpack Compose 原理与重组机制

### 【JD 出处】

- 浩鲸云计算：*"跟踪安卓领域新技术、新框架（如 Jetpack Compose、跨端方案等）"*
- 上海之几：*"熟悉安卓基础组件（Jetpack Compose、ViewModel、LiveData 等）"*
- 高级客户端/操作系统/Framework 研发：*"Compose"*

> 你在系统学 Compose 的 side effects 与重组机制——这一节正好把这些概念串成一条完整的机制链。

### 【要解决的问题】

传统 View 体系是**命令式 + 树结构 + 手动刷新**（`findViewById` → 改属性 → `invalidate`）。问题是：状态分散、刷新靠人管、容易漏。Compose 用**声明式 + 函数 + 自动重组**取代它。

### 【原理】

#### 编译期：`@Composable` 被改写成了什么

```kotlin
@Composable
fun Greeting(name: String) {
    Text("Hello, $name")
}
```

编译后（伪代码）：

```kotlin
fun Greeting(name: String, $composer: Composer<*>, $changed: Int) {
    $composer.startRestartGroup(/* 唯一的 key，基于调用位置 */)
    if ($changed and 0b0001 == 0) {
        // 参数没变 → 可以跳过（skip）
    }
    Text("Hello, $name", $composer, 0)
    $composer.endRestartGroup()?.updateScope { c ->
        Greeting(name, c, $changed or 0b0001)   // ← 重组时调用这个 lambda
    }
}
```

**三个关键机制**：

| 机制                              | 作用                                       |
| ------------------------------- | ---------------------------------------- |
| **`Composer` 参数**               | 编译期注入，用于读写 `SlotTable`（Compose 的运行时数据结构） |
| **`$changed` 位掩码**              | 记录每个参数是否变化，用于**跳过（skip）** 不必要的执行         |
| **Restart Group + updateScope** | 记录"这段组合的重组入口"，重组时只需要重新调用这个 lambda        |

#### `Composer` / `SlotTable` / `GapBuffer`

- **`SlotTable`**：Compose 的"虚拟 DOM"，用扁平数组存整棵组合树（不是对象树！这是它比 View 树快的重要原因）
- **`GapBuffer`**：`SlotTable` 内部的动态数组，把"当前光标位置"做成一个可移动的 gap，使**在任意位置插入/删除都是 O(1)**（因为只需要移动 gap）

```
SlotTable（简化）:
[ group1 | group2 | ... | GAP | ... | groupN ]
                          ↑ 插入发生在这里
```

**为什么这很重要**：Compose 每次重组都可能增删节点，用 `GapBuffer` 保证这些操作是常数时间。这是 Compose 能做到"重组也不慢"的底层原因。

#### 位置记忆化（Positional Memoization）

```kotlin
@Composable
fun Counter() {
    var count by remember { mutableStateOf(0) }   // ← remember
    Button(onClick = { count++ }) { Text("$count") }
}
```

**`remember` 的实现**：`Composer` 用一个"当前所在位置的 key"（由调用位置 + 循环索引生成）去 `SlotTable` 里查。如果查到了就用缓存值，查不到就执行 lambda 并存入。

**这解释了两件事**：

1. **为什么 `remember` 不能放在条件分支里**（位置会变 → key 错位 → 状态错乱）
2. **为什么循环里要显式给 `key(...)`**（否则位置索引会漂移）

```kotlin
// ❌ 危险：条件变化会让 remember 的位置错位
if (flag) {
    var a by remember { mutableStateOf(0) }
}

// ✅ 正确写法
var a by remember { mutableStateOf(0) }
if (flag) { ... }
```

#### 状态系统：快照（Snapshot）

```kotlin
val state = mutableStateOf(0)     // 返回 MutableState<Int>
state.value = 1                   // ← 写入，触发依赖它的重组
```

**快照系统的三个概念**：

| 概念                            | 含义                                 |
| ----------------------------- | ---------------------------------- |
| `Snapshot`                    | 一个"状态的一致性视图"。默认用全局快照，但也可以开子快照做批量修改 |
| `StateObject` / `StateRecord` | 记录某个状态在不同快照下的值                     |
| `Snapshot.apply()`            | 把子快照的修改**原子地**合并回全局                |

**为什么需要快照**：因为重组过程本身必须看到"一致的状态"。如果重组到一半状态变了，会渲染出中间态。快照让"重组读到的状态"是一个冻结的视图。

**这也是 Compose 能做到"一次重组内状态一致"的机制保障。**

#### 重组（Recomposition）

```
① 状态变化：state.value = 1
     └─ SnapshotMutableStateImpl 通知所有观察者
         └─ Recomposer 被唤醒

② Recomposer 调度
     └─ Recomposer 跑在 AndroidUiDispatcher 上（绑定到 Choreographer 的帧时钟）
         └─ withFrameNanos { } 等待下一帧
             └─ 在帧回调里执行重组（与 VSYNC 对齐）  ← 关键：重组是帧同步的

③ 执行重组
     └─ 调用 RestartGroup 的 updateScope lambda
         └─ 只重跑"读了这个状态"的 Composable（细粒度）
             └─ 记录哪些节点被"失效"（invalidated）

④ 应用修改
     └─ LayoutNode 树更新 → 测量 / 布局 / 绘制
```

**"重组是帧同步的"这个点很关键**：`Recomposer` 通过 `MonotonicFrameClock`（`AndroidUiDispatcher` 提供）在 VSYNC 时执行重组。所以即使状态在 100Hz 变化，重组最多也是每帧一次 —— **Compose 天然做了"帧节流"**。这是它相对"手动 invalidate"的一个隐性优势。

#### 三个阶段：Composition → Layout → Drawing

| 阶段              | 做什么                                | 何时重跑         |
| --------------- | ---------------------------------- | ------------ |
| **Composition** | 执行 Composable，生成/更新 `LayoutNode` 树 | 读到的状态变化      |
| **Layout**      | 测量与布局（`MeasurePolicy`）             | 尺寸/位置相关的状态变化 |
| **Drawing**     | 绘制到 Canvas                         | 绘制相关的状态变化    |

**优化技巧：把状态读取"推迟"到后面的阶段**，可以减少重跑范围：

```kotlin
// ❌ 在 Composition 阶段读了 offsetX → 动画每帧都会重组
Box(Modifier.offset(x = animatedX.dp)) { }

// ✅ 用 lambda 形式的 Modifier，把读取推迟到 Layout 阶段 → 只重布局，不重组
Box(Modifier.offset { IntOffset(animatedX.roundToInt(), 0) }) { }
```

**这是 Compose 性能优化的第一原则**：**能用 lambda 形式就用 lambda 形式**（`offset {}`、`graphicsLayer {}`、`alpha {}` 等）。**面试里能讲出"Composition 阶段读取 vs Layout 阶段读取"的区别，说明你真的调过性能。**

#### SideEffect 家族（你在学的重点）

| API                           | 触发时机                                 | 典型用途                        |
| ----------------------------- | ------------------------------------ | --------------------------- |
| `LaunchedEffect(key)`         | 进入组合时启动协程；`key` 变化时**取消并重启**；离开组合时取消 | 一次性加载数据、订阅 Flow             |
| `DisposableEffect(key)`       | 进入组合时执行；`onDispose` 在 key 变化或离开组合时执行 | **注册/注销回调**（如 Car 属性订阅）     |
| `SideEffect`                  | 每次成功重组后都执行（不 suspend）                | 把 Compose 状态同步给非 Compose 代码 |
| `rememberCoroutineScope()`    | 返回一个绑定到当前组合的 `CoroutineScope`        | 在事件回调里启动协程                  |
| `produceState(initial, key)`  | 用协程产生一个 `State`                      | 把异步数据源变成 State              |
| `snapshotFlow { }`            | 把 Compose 状态变成 `Flow`                | 观察状态变化并去抖                   |
| `rememberUpdatedState(value)` | 让 `LaunchedEffect` 里能读到最新值而不重启       | 长生命周期 effect 读最新 lambda     |
| `derivedStateOf { }`          | 从其他状态派生，只在**派生结果变化**时通知              | 减少重组次数                      |

**车机场景的标准模板**（把 Car 属性订阅写进 Compose）：

```kotlin
@Composable
fun VehicleSpeedText(car: Car) {
    var speed by remember { mutableStateOf<Float?>(null) }

    DisposableEffect(car) {
        val mgr = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        val cb = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                if (value.status == CarPropertyValue.STATUS_AVAILABLE) {
                    speed = value.value as Float   // ← 写入 State → 触发重组
                }
            }
            override fun onErrorEvent(propId: Int, areaId: Int) { speed = null }
        }
        mgr.registerCallback(cb, VehiclePropertyIds.PERF_VEHICLE_SPEED, 10f)

        onDispose { mgr.unregisterCallback(cb) }   // ← 关键：离开组合必须注销
    }

    Text(text = speed?.let { "%.0f km/h".format(it) } ?: "—")
}
```

**`DisposableEffect` 是 Compose 里做"注册/注销"的唯一正确姿势。** 用 `LaunchedEffect` 也能写，但 `DisposableEffect` 更贴切（它不 suspend，且 `onDispose` 语义明确）。

#### `derivedStateOf` 为什么能减少重组

```kotlin
// ❌ scrollState.value 每变一次都重组（可能一帧变多次）
val showButton = scrollState.value > 100

// ✅ 只在 true/false 切换时重组
val showButton by remember { derivedStateOf { scrollState.value > 100 } }
```

**机制**：`derivedStateOf` 内部用 `Snapshot` 的观察机制，只有"计算结果"变化时才通知下游。**这是"高频变化的状态 → 低频的 UI 依赖"的标准降噪手段。**

### 【常见追问】

**Q：为什么 Compose 的 Composable 函数必须首字母大写？**  
因为它有特殊语义（编译期被改写），且可以在任何地方调用（不像普通函数那样有调用顺序限制）。**大写是一个"这是 Composable"的约定标记**，让编译器与人都能识别。

**Q：Compose 一定比 View 快吗？**  
不是。两者各有场景：

- Compose 在**复杂状态驱动的 UI**（频繁部分刷新）上更快，因为重组范围小
- View 在**极简静态 UI** 上启动更快（Compose 有第一批次的重组与运行时初始化开销）
- **车机上要注意首帧时间**：Compose 的 `Recomposer` 初始化与首次组合有开销。冷启动敏感的界面（倒车影像启动）仍需谨慎

**Q：`@Stable` / `@Immutable` 有什么用？**  
它们是给编译器的**稳定性提示**。如果一个参数类型被编译器判定为"unstable"，那么参数变化时**无法跳过**重组（因为编译器不敢假设它没变）。

```kotlin
// List<T> 被判定为 unstable（因为 List 可能是 MutableList）
// → 每次重组都会重跑，即使 list 内容没变
@Composable fun ListView(items: List<Item>) { }

// 解法 1：用 ImmutableList（kotlinx.collections.immutable）
// 解法 2：包一层 @Immutable data class
// 解法 3：Kotlin 2.0 的 Strong Skipping Mode（默认开启，弱化了这个问题）
```

**`@Stable` / `@Immutable` 与 Strong Skipping 是 Compose 性能话题里最深的一层**，如果面试官在调 Compose 性能，这就是他真正想问的。

**Q：Compose 里的 `ViewModel` 怎么用？**  
`viewModel()` 是 Compose 的一个 Composable 函数，它从当前的 `ViewModelStoreOwner`（通常是 Activity/Fragment）拿到 store，然后取或创建 ViewModel。**关键点**：Compose 里 ViewModel 的生命周期仍是 `ViewModelStoreOwner` 的，不是"组合的"，所以它能跨重组存活。

### 【延伸方向】

- 必读：`androidx.compose.runtime/` 下的 `Composer.kt`、`SlotTable.kt`、`Recomposer.kt`、`Snapshot.kt`
- 必读：`androidx.compose.runtime/Effects.kt`（所有 Effect 的实现）
- 工具：Layout Inspector（看重组次数）、Compose Compiler Metrics（`-P plugin:androidx.compose.compiler.plugins.kotlin:metricsDestination=...`，看哪些 Composable 被标记为 skippable / restartable）
- 延伸：Kotlin 2.0 的 Strong Skipping Mode 与 `@NonRestartableComposable`
- 延伸：`Modifier` 链的存储结构（`Modifier.Node`，Android 早期是 `Modifier.Element` 链）

## 3.4 MVVM / MVI / MVP 的取舍与演进

### 【JD 出处】

- 浩鲸云计算：*"熟悉 MVVM/MVI 等架构模式，掌握组件化、插件化开发思路"*
- 华设设计集团：*"熟悉 MVP、MVVM、MVI，且有过架构搭建经验"*
- 软通动力（车机）：*"了解 Android SDK 及 MVC、MVP、MVVM 框架模式"*
- 万声音乐：*"熟悉 google 倡导的 MVVM 架构"*

### 【要解决的问题】

UI 代码的天然熵增：Activity 里堆网络请求、数据处理、UI 刷新，最后变成 2000 行的"God Activity"。架构模式的唯一目的是**把"状态"和"渲染"分开，让状态变化有唯一入口**。

### 【原理】

#### 三代演进

**① MVP（2012–2016）：View 与 Presenter 双向接口**

```
View（Activity/Fragment）  ←→  Presenter  ←→  Model
   implements IView           implements IPresenter
   presenter.onClick()        view.showLoading()
                              model.fetch().subscribe(...)
```

问题：**接口爆炸**（每个界面一套 `IView` / `IPresenter`），生命周期管理靠人（`onDestroy` 里 `presenter.detachView()`）。

**② MVVM（2016–2020）：数据绑定（单向或双向）**

```
View  ←observe─  ViewModel  ←─  Model（Repository）
  (观察 LiveData/StateFlow)   (持有业务逻辑，不含 View 引用)
```

**核心优势**：ViewModel 不持有 View 引用 → 天然解决生命周期问题（配合 `ViewModelStore` 跨旋转存活）。DataBinding/ViewBinding 负责把数据绑到 UI。

**③ MVI（2019 至今）：单向数据流**

```
        ┌──────────────────────────────────────┐
        │            View（纯渲染）              │
        │  render(state) ← 无状态、可预测        │
        └───────┬──────────────────────▲───────┘
        Intent/Event                State
        (用户操作)                  (唯一状态源)
                │                      │
        ┌───────▼──────────────────────┴───────┐
        │     ViewModel / Reducer              │
        │  reduce(currentState, intent) -> newState │
        └──────────────────────────────────────┘
```

三条铁律：

| 铁律              | 含义                                         |
| --------------- | ------------------------------------------ |
| **单一状态源（SSOT）** | 整个界面只有一个 `State` 对象                        |
| **单向数据流**       | Event 向下 → Reducer 处理 → State 向上 → View 渲染 |
| **状态不可变**       | `State` 是 `data class`，每次产生新实例             |

```kotlin
// MVI 的典型形态
data class VehicleUiState(
    val speed: Float = 0f,
    val gear: Gear = Gear.P,
    val doorsOpen: Set<DoorPosition> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

sealed interface VehicleIntent {
    data class DoorStateChanged(val door: DoorPosition, val open: Boolean) : VehicleIntent
    data class SpeedChanged(val speed: Float) : VehicleIntent
    data object Retry : VehicleIntent
}

class VehicleViewModel(private val repo: VehicleRepository) : ViewModel() {
    private val _state = MutableStateFlow(VehicleUiState())
    val state: StateFlow<VehicleUiState> = _state.asStateFlow()

    fun onIntent(intent: VehicleIntent) {
        _state.update { current ->
            when (intent) {
                is VehicleIntent.SpeedChanged -> current.copy(speed = intent.speed)
                is VehicleIntent.DoorStateChanged ->
                    current.copy(doorsOpen = if (intent.open)
                        current.doorsOpen + intent.door
                    else current.doorsOpen - intent.door)
                VehicleIntent.Retry -> current.copy(error = null, isLoading = true)
            }
        }
    }
}
```

**`_state.update {}` 是关键**：它用 CAS 做原子更新，保证并发场景下状态不会丢。比 `_state.value = _state.value.copy(...)` 安全（后者在并发下有竞态）。

#### 三者对比与选择

| 维度         | MVC | MVP         | MVVM           | MVI                 |
| ---------- | --- | ----------- | -------------- | ------------------- |
| View 与逻辑耦合 | 高   | 低（接口隔离）     | 中（绑定）          | **最低**              |
| 状态管理       | 分散  | 分散          | 分散（多 LiveData） | **集中（单一 State）**    |
| 可测试性       | 差   | 好           | 好              | **最好**（纯函数 reduce）  |
| 样板代码       | 少   | **多（接口爆炸）** | 中              | 中（需定义 Intent/State） |
| 状态一致性      | 差   | 差           | 中              | **强**               |
| 适用         | 小界面 | 遗留代码        | **通用**         | **复杂状态机**           |

**结论（面试可以这么答）**：

- **MVVM 是通用默认选择**（Google 推荐，生态齐全）
- **MVI 适合"状态复杂 + 状态间有约束"的场景**。**车机 UI 恰好是典型场景** —— 例如"倒车时禁止显示视频"、"驾驶中限制键盘输入"、"车速 >0 时不允许多窗口"——这类"状态组合决定可用性"的逻辑，用 MVI 的单一 State + 纯函数 reduce 表达最清晰
- **MVP 基本是存量**，不必主动学

**车机场景的 MVI 优势（这是你可以主动讲的观点）**：  
车机状态有两个特点：① **状态来源多**（VHAL 属性、驾驶限制、电源状态、用户操作）② **状态之间有互斥/依赖关系**（挡位与车速与倒车影像）。如果每个状态各用一个 `LiveData`，跨状态的约束逻辑只能写在"观察者里"，很容易出现"先收到 A 后收到 B 导致瞬间渲染错误状态"。**单一不可变 State + 原子 update 从机制上消除了这类 bug。**

### 【常见追问】

**Q：ViewModel 为什么能在屏幕旋转后存活？**  
`ViewModel` 存在 `ViewModelStore` 里，`ViewModelStore` 由 `ViewModelStoreOwner`（Activity/Fragment）持有。屏幕旋转时 Activity 会销毁重建，但 **`ViewModelStore` 通过 `NonConfigurationInstance` 机制被保留**（`ActivityThread` 的 `ActivityClientRecord` 里存 `lastNonConfigurationInstances`），所以 ViewModel 存活。**`onCleared()` 在真正销毁（非配置变更）时调用。**

**Q：ViewModel 里能持有 `Context` 吗？**  
**不能持有 Activity Context**（会泄漏）。需要 Context 时用 `AndroidViewModel` + `Application`，或者干脆不要（Repository 层也不用 Context）。

**Q：`ViewModel` 与 `SavedStateHandle` 的区别？**  
`ViewModel` 活过"配置变更"，但**活不过"进程死亡"**。`SavedStateHandle` 通过 `onSaveInstanceState` 把状态存进 Bundle，进程被杀后能恢复。**车机上这个更关键**——熄火后进程可能被杀，重建时要能恢复到之前状态。

### 【延伸方向】

- 官方：`developer.android.com/topic/architecture`（Guide to app architecture）
- 延伸：`Orbit MVI`、`MVIKotlin`、`Mavericks`（MVI 实现库）
- 延伸：`Store` 类的抽象（Intent → reduce → State + SideEffect），这是 MVI 的通用模式
- 延伸阅读：`androidx.lifecycle.ViewModel` 与 `ViewModelStore` 源码

---

## 3.5 组件化 / 模块化 / 插件化

### 【JD 出处】

- 浩鲸云计算：*"推进组件化、模块化、工程化建设，制定技术规范与开发标准，提升代码复用性、可维护性及研发效率"*
- 焦点科技：*"熟悉 MVC、MVVM 等架构模式，具备模块化开发经验"*

### 【原理】

#### 三个"化"的区别（先分清概念，这是面试第一题）

| 概念      | 层次   | 目标                | 产物                                      |
| ------- | ---- | ----------------- | --------------------------------------- |
| **模块化** | 源码组织 | 关注点分离、编译加速        | Gradle `module`（`app`、`feature`、`core`） |
| **组件化** | 架构设计 | **业务模块可独立运行**、可插拔 | 每个业务是一个可独立调试的 module                    |
| **插件化** | 运行时  | **不重新安装就下发新功能**   | APK/DEX 在运行时加载                          |

**注意**：很多项目把"模块化"叫"组件化"，严格说不对。**能独立编译运行**才是组件化的标志。

#### 模块分层（工程上最常考的形态）

```
app（壳工程，只做路由注册与打包）
 ├─ feature/vehicle      （车控）
 ├─ feature/media        （媒体）
 ├─ feature/navigation   （导航）
 ├─ feature/settings     （设置）
 ├─ core/common          （基础工具、扩展函数）
 ├─ core/ui              （UI 组件库、主题）
 ├─ core/network         （网络层）
 ├─ core/data            （数据层、Repository）
 └─ core/car             （Car API 封装）
```

**依赖规则**：`feature` 可以依赖 `core`，**`feature` 之间不能直接依赖**；`app` 依赖所有 `feature`。

**为什么 feature 之间不能互相依赖**：一旦依赖，就变成了"隐式耦合"——删掉一个 feature 会编译不过。**这是"模块化是否成功"的第一个判据。**

#### 两个核心机制

**① 路由（解耦 feature 之间的跳转）**

```kotlin
// 声明（在 feature/navigation 模块里，不依赖其他 feature）
@Route(path = "/navigation/main")
class NavigationActivity : AppCompatActivity()

// 使用（在 feature/vehicle 里，不知道 NavigationActivity 的存在）
Router.navigate(context, "/navigation/main", bundleOf("dest" to "home"))
```

**路由的本质是"用字符串替换了类型引用"**。代价是编译期检查丢失（`path` 写错要到运行时才发现），所以成熟方案会做"路由表 + 编译期注解处理 + 校验"。

**车机上的特殊价值**：车机功能按域划分（车控/媒体/导航/空调），天然适合组件化；而且**不同车型可能只装其中一部分功能** → 组件化让"按车型裁剪"变成"改 `PRODUCT_PACKAGES`"。

**② 通信（跨模块的数据传递）**

三种模式：

| 模式                      | 机制                                     | 适用           |
| ----------------------- | -------------------------------------- | ------------ |
| **接口下沉**                | 把接口放在 `core/api`，实现在 feature，`app` 里注入 | 编译期安全，**推荐** |
| **事件总线**                | `LiveEventBus` / `Flow` 的全局单例          | 弱依赖，但**难追踪** |
| **ServiceLoader / SPI** | 运行时发现实现                                | 通用但样板多       |

```kotlin
// 接口下沉的写法
// core/api 模块
interface VehicleService {
    fun getSpeed(): Flow<Float>
}

// feature/vehicle 模块
class VehicleServiceImpl : VehicleService { ... }

// app 模块（或 DI 容器）负责注入
```

**车机上推荐"接口下沉"**：车控数据的消费者（媒体、导航、仪表投屏）都需要车速、挡位，用事件总线会导致"谁发的这条车速"无法追踪。

#### 插件化（了解即可，车机上很少用）

三种技术路线：

| 路线                           | 原理                                                          | 代表                |
| ---------------------------- | ----------------------------------------------------------- | ----------------- |
| **DEX 动态加载**                 | `DexClassLoader` 加载外部 DEX，反射调用                              | RePlugin（360）     |
| **Hook AMS/PMS**             | 反射替换系统的 `IActivityManager` / `IPackageManager`，让系统以为插件是已安装的 | VirtualAPK（滴滴）    |
| **Hook 资源 + ClassLoader 合并** | 把插件的 `Resources` 与 `ClassLoader` 合并进宿主                      | Shadow（腾讯，无 Hook） |

**为什么 Android 9+ 之后插件化变难**：Google 逐步封锁了非 SDK 接口（灰名单/黑名单）、`IActivityManager` 的 AIDL 变动频繁、`Resources` 的 `addAssetPath` 反射受限。**现在主流是"Shadow 路线"（不 Hook 系统，用代理组件 + 自己的类加载体系）。**

**车机上为什么不常用插件化**：车机 App 分发不经过应用商店，是随 ROM 出厂/OTA 下发的。**OTA 已经解决了"下发新版本"的问题，插件化的收益（热更新）意义不大，而稳定性风险很高。** 这是"手机经验不能照搬到车机"的一个典型例子，**面试时能主动讲这个对比非常加分。**

### 【常见追问】

**Q：模块化最大的收益是什么？**  
两个：① **编译速度**（增量编译只重编改动的模块，大项目从 10 分钟降到 1 分钟）；② **边界约束**（编译期强制不耦合，比"口头约定"可靠）。**收益 ② 常被低估，但它才是长期价值。**

**Q：组件化怎么做到"单独运行"？**  
用一个 Gradle 开关：

```groovy
// gradle.properties
isModule = false    // false = 组件化模式（各 feature 可独立运行）
```

```groovy
// feature/vehicle/build.gradle
if (isModule.toBoolean()) {
    apply plugin: 'com.android.library'       // 集成模式
} else {
    apply plugin: 'com.android.application'   // 独立运行
    // 需要独立的 AndroidManifest 与 Application
}
```

**独立运行时每个 feature 都要有自己的 `AndroidManifest.xml`（带 Launcher）与 `Application`**，这就是"组件化"的工程量所在。

**Q：资源冲突怎么解决？**  
三条规则：① **资源名前缀**（每个模块统一用 `模块名_` 前缀，如 `vehicle_btn_start`）；② `resourcePrefix`（在 Gradle 里强制约束）；③ 用 `R` 类隔离（`com.xxx.vehicle.R`）。**面试如果被问到"模块化踩过什么坑"，资源冲突一定是答案之一。**

### 【延伸方向】

- 官方：`developer.android.com/guide/app-architecture` 的 "Modularization"
- 延伸：`ARouter`、`WMRouter`、`TheRouter`（路由方案对比）
- 延伸：Gradle 的 `api` vs `implementation`（前者传递依赖，后者不传递 —— 模块化里必须用 `implementation` 收敛依赖）
- 延伸阅读：Shadow 的插件化实现原理（不 Hook 系统的插件化）

---

## 3.6 WebView + JSBridge 混合开发

### 【JD 出处】

- 上海之几：*"实现 WebView 与 H5 页面的混合开发及交互（JavaScript 接口调用、数据传输）；具备 WebView 集成与调优经验，能实现 JavaScript 与 Native 双向通信"*
- 中瑞集团：*"负责 Android App 通用 Bridge 能力建设，涵盖相机、地图、定位、导航、网络等模块"*
- 苏宁易购：*"熟悉混合式开发，并对内嵌 H5 页面有优化经验者优先考虑"*

> 这一节是你的现有强项（HtmlScreen/HtmlFragment + JSBridge）。这里把它整理成"体系化表述"，并补上"优化"与"安全"两个维度的深度。

### 【原理】

#### WebView 的进程模型

```
App 进程
  └─ WebView（Chromium 内核，自带 V8 引擎）
      └─ 通过 Binder 与下列进程通信：
          ├─ **renderer 进程**（网页渲染与 JS 执行，独立沙箱进程）
          ├─ **GPU 进程**（合成）
          ├─ **network 进程**（网络请求）
          └─ **webview_zygote**（renderer 的孵化器）
```

**关键认知**：

1. **JS 跑在独立进程**（renderer），所以"JS 崩了不会崩 App"（Android 8+ 有 renderer 崩溃恢复机制）
2. **`WebView` 的所有 JS 调用都是跨进程的** → 有开销，不能高频
3. **每个 App 都有独立的 WebView 数据目录**（Cookie、缓存、LocalStorage 隔离）

#### 双向通信的五种机制

| 机制                                           | 方向          | 特点                                           | 现状     |
| -------------------------------------------- | ----------- | -------------------------------------------- | ------ |
| **`addJavascriptInterface(obj, name)`**      | JS → Native | 反射暴露对象，**同步调用有返回值**                          | **主流** |
| **`evaluateJavascript(script, cb)`**         | Native → JS | 可拿返回值（API 19+）                               | **主流** |
| **`WebViewClient.shouldOverrideUrlLoading`** | JS → Native | URL scheme 拦截（`jsbridge://action?param=...`） | 传统     |
| **`WebChromeClient.onJsPrompt/onJsAlert`**   | JS → Native | `prompt` 拦截（可同步返回）                           | 传统     |
| **`WebMessagePort` / `postWebMessage`**      | 双向          | AndroidX WebKit，**推荐的新方案**                   | 新      |

**`addJavascriptInterface` 的关键细节**：

```java
// ① 必须加 @JavascriptInterface 注解（Android 4.2+）
public class JsBridge {
    @JavascriptInterface
    public String getSpeed() {
        return String.valueOf(currentSpeed);
    }

    @JavascriptInterface
    public void openCamera(String params) {
        // 注意：这个方法在 **WebView 的 JavaBridge 线程**执行，不是主线程
    }
}

webView.addJavascriptInterface(new JsBridge(), "Android");
```

```javascript
// JS 侧
const speed = window.Android.getSpeed();   // 同步拿到返回值
window.Android.openCamera('{"mode":"photo"}');
```

**三个致命细节**：

1. **`@JavascriptInterface` 缺失 = 方法不可见**（Android 4.2 的安全修复）
2. **执行线程是 `JavaBridge` 线程，不是主线程** → 回调里操作 UI 必须 `post` 到主线程
3. **`addJavascriptInterface` 有历史安全漏洞**（Android 4.1 及以前，利用反射可执行任意 Java 代码：`getClass().forName('java.lang.Runtime')...`）→ 现在的对策是：① 只暴露最小接口；② **不要暴露任何能间接获取 `Class` 的方法**；③ 加载 HTTPS 页面（防中间人替换页面注入 JS）

**`evaluateJavascript` 的使用约束**：

```java
// 必须在主线程调用
webView.post(() -> {
    webView.evaluateJavascript("javascript:updateSpeed(" + speed + ")", value -> {
        // value 是 JSON 编码的返回值
        Log.d(TAG, "result=" + value);
    });
});
```

**`evaluateJavascript` 的三个坑**：

1. **必须在主线程调用**（否则抛异常）
2. **页面未加载完成时调用会丢**（要等 `onPageFinished`）
3. **返回值是 JSON 编码的字符串**（`"abc"` 带引号，`null` 是字符串 `"null"`）

#### JSBridge 的工程化设计

一个生产级 JSBridge 通常包含：

```
┌─────────────────────────────────────────────────┐
│ JS 侧：                                          │
│   window.JSBridge.call(method, params, callbackId)│
│      └─ 统一入口，把 (method, params, callbackId) │
│         序列化成一个 JSON，调用 Native 的 postMessage │
├─────────────────────────────────────────────────┤
│ Native 侧：                                       │
│   @JavascriptInterface postMessage(String json)  │
│      ├─ 解析出 method / params / callbackId       │
│      ├─ 路由到注册的 handler                      │
│      ├─ handler 执行（可能异步）                   │
│      └─ 回调时调用 evaluateJavascript 把结果和      │
│         callbackId 传回 JS                        │
├─────────────────────────────────────────────────┤
│ JS 侧：                                          │
│   window.__JSBridgeCallback(callbackId, result)  │
│      └─ 按 callbackId 找到原来注册的 callback 执行  │
└─────────────────────────────────────────────────┘
```

```javascript
// JS 侧 SDK 的关键实现
let callbackId = 0;
const callbacks = {};

function call(method, params, cb) {
    const id = `cb_${callbackId++}`;
    callbacks[id] = cb;
    window.Android.postMessage(JSON.stringify({ method, params, callbackId: id }));
}

// Native 回调入口
window.__JSBridgeCallback = function (id, result) {
    const cb = callbacks[id];
    if (cb) { cb(result); delete callbacks[id]; }
};
```

**这个设计的三个要点**：

1. **单一 Native 入口**（`postMessage`）→ 减少反射暴露面，安全
2. **`callbackId` 配对** → 支持并发调用与异步回调（这是 url-scheme 方案做不到的）
3. **JSON 序列化** → 结构化数据传递

**这是你项目里 `HtmlScreen` / `HtmlFragment` + JSBridge 的通用形态**。面试时应该按"单一入口 + callbackId 配对 + JSON 序列化"这三句话讲，而不是"我们用了 `addJavascriptInterface`"。

#### 性能优化（JD 明确要求"调优"）

| 优化点       | 手段                                                                                                                                                           | 收益      |
| --------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------- |
| **首屏时间**  | ① `WebView` 预创建（提前 `new WebView` + `loadDataWithBaseURL` 预热）；② 离线包（HTML/JS/CSS 打包进 APK，用 `shouldInterceptRequest` 拦截本地资源）；③ 首屏数据预取（Native 提前请求接口，H5 加载时直接注入） | 显著      |
| **内存**    | ① 复用 `WebView` 实例（池化）；② 页面退出时 `destroy()` + `loadUrl("about:blank")` + 移出父容器（**必须三步，否则泄漏**）；③ 限制 `WebView` 数量                                                | 显著      |
| **滚动流畅度** | ① `setLayerType(LAYER_TYPE_HARDWARE)`（视情况）；② 避免 `overflow: scroll` 嵌套；③ 用 `-webkit-overflow-scrolling: touch`                                                | 中       |
| **JS 执行** | ① 批量调用（避免每条数据都过 Bridge）；② 高频数据用"Native 主动推 + JS 侧节流"；③ V8 的 `JIT` 预热                                                                                         | 中       |
| **多进程隔离** | `WebView.setDataDirectorySuffix()` 让不同用途的 WebView 用不同数据目录（避免 Cookie 冲突，也更安全）                                                                                 | 中       |
| **渲染优先级** | `setRendererPriorityPolicy()` 控制后台 WebView 的渲染优先级，省电                                                                                                         | 中（车机重要） |

**车机特有的优化点**：

- **H5 在车机上是主流**（很多车机 UI 大比例用 H5，便于跨车型复用的"云 UI"）。所以"WebView 首屏 + 内存"在车机上的重要性远高于手机
- **车机的 `WebView` 版本是固定的**（随 ROM 出厂），**不能靠"用户升级 Chrome"解决兼容问题** → 必须用 ROM 里的 Chromium 版本做兼容性测试。这是与手机开发的重大差异
- **主题切换**（你的 HtmlScreen 通过 CSS reload 实现）→ 更好的做法是用 CSS 变量 + `WebView.evaluateJavascript` 动态改变量，避免整页 reload 的闪烁与状态丢失

#### 安全（必须提，否则显得不专业）

| 风险                            | 对策                                                                                                               |
| ----------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| `addJavascriptInterface` 反射注入 | 只暴露最小接口；不暴露返回 `Object`/`Class` 的方法；API 17+ 才用（或加 `@JavascriptInterface`）                                         |
| 中间人篡改页面                       | **强制 HTTPS**（`setMixedContentMode`）、证书校验                                                                         |
| `file://` 页面的同源绕过             | `setAllowFileAccess(false)`、`setAllowFileAccessFromFileURLs(false)`、`setAllowUniversalAccessFromFileURLs(false)` |
| JS 调用危险能力                     | 白名单（只允许特定域名调用特定方法）、校验 `webView.getUrl()`                                                                         |
| Cookie 泄漏                     | `setAcceptThirdPartyCookies(false)`、敏感数据不放 Cookie                                                                |

**面试金句**："H5 的安全边界必须由 Native 守住 —— 因为 H5 由服务端下发、可能被篡改，**Native 不能假设 JS 是可信的**。"

### 【常见追问】

**Q：WebView 内存泄漏的三个经典原因？**  
① `WebView` 持有 `Activity` 的 `Context`（应该用 `applicationContext` 创建，或确保销毁）；② `addJavascriptInterface` 的对象持有 Activity 引用；③ `WebViewClient` / `WebChromeClient` 是匿名内部类持有 Activity。**标准销毁流程**：

```java
override fun onDestroy() {
    webView?.let {
        it.loadUrl("about:blank")
        it.stopLoading()
        it.clearHistory()
        it.removeAllViews()
        (it.parent as? ViewGroup)?.removeView(it)   // ← 必须从父容器移除
        it.destroy()
    }
    webView = null
    webViewClient = null
    webChromeClient = null
    jsBridge = null
}
```

**Q：`shouldInterceptRequest` 做离线包要注意什么？**  
四个点：① **在子线程执行**（它是同步回调，在主线程做 IO 会卡渲染线程）；② 要设置正确的 `MIME` 与 `Content-Type`；③ 要处理 `Range` 请求（视频）；④ 要考虑**版本更新与缓存失效**（离线包更新后要让旧缓存失效）。**第 ④ 是实践中最容易出问题的地方。**

**Q：H5 与 Native 谁该做"路由"？**  
**Native 做路由**。因为 H5 不知道 App 的页面结构，而且"跳转到 Native 页面"这个行为本身就应该由 Native 决策（可以统一做登录校验、参数校验、降级）。**JSBridge 里最常被调用的方法就是 `navigate(path, params)`。**

### 【延伸方向】

- 官方：`developer.android.com/guide/webapps`、`androidx.webkit`（`WebViewCompat`、`WebMessagePort`）
- 安全：OWASP Mobile 的 WebView 相关条目
- 延伸：离线包方案（差量更新、`ServiceWorker`）
- 延伸阅读：`androidx.webkit` 的 `WebViewFeature`（用 feature flag 判断能力，避免版本判断散落各处）

## 3.7 性能优化方法论（启动 / 内存 / 卡顿 / 耗电 / 包体）

### 【JD 出处】

- 浩鲸云计算：*"负责安卓端性能优化与问题攻坚，针对启动速度、内存泄漏、卡顿、耗电等核心问题，运用专业工具定位并落地优化方案"*
- 中瑞集团：*"优化 Android App 性能，提升启动性能、渲染性能、网络性能等用户体验指标"*
- 小米南京·座舱软件开发工程师：*"负责座舱车机系统优化（性能/稳定性）；负责相关量化标准、大数据、工具开发；负责相关长效机制开发"*

> 注意小米那条 JD ——"**量化标准 + 工具 + 长效机制**"。这三个词说明了性能优化在成熟团队的定位：**不是一个"临时任务"，而是工程体系**。面试时按这个框架讲，层次会立刻不同。

### 【通用方法论（五步）】

```
① 定义指标（量化标准）
   没有指标的优化都是耍流氓。先定义：
     启动：冷启动耗时（Displayed / reportFullyDrawn）
     卡顿：掉帧率（janky frames %）、P90/P99 帧耗时
     内存：PSS 峰值、GC 次数、大对象数量
     耗电：单位时间电流（mAh/h）、WakeLock 时长
     包体：APK 大小、Dex 大小、so 大小、资源大小

② 埋点与度量（工具）
   线上：自定义打点 + 上报；Android Vitals（Play）/ 自建平台
   线下：Perfetto、Systrace、Profiler、LeakCanary、Battery Historian

③ 定位瓶颈
   自顶向下：从"哪个阶段慢"定位到"哪个函数慢"
   工具：Perfetto 的火焰图、Simpleperf、Method Tracing

④ 优化与验证
   一次只改一个变量；改动前后用同一指标对比

⑤ 防劣化（长效机制）
   CI 里的性能门禁（启动时间超标则构建失败）
   定期回归（每次发版前跑性能用例）
   代码审查规则（禁止在主线程做 IO 等）
```

**第 ⑤ 步是"长期主义者"和"救火队员"的分界。** 面试时主动提"防劣化"，会显得你有体系视角。

### 3.7.1 启动优化

#### 冷启动的阶段拆分（必须能画出来）

```
用户点击图标
  ├─ ① 进程创建（Zygote fork）
  ├─ ② Application 初始化
  │     attachBaseContext()
  │     → ContentProvider.onCreate()   ← 很多库在这里初始化（容易踩坑）
  │     → Application.onCreate()
  ├─ ③ Activity 创建
  │     onCreate() → onStart() → onResume()
  ├─ ④ 首帧绘制
  │     ViewRootImpl.performTraversals()（measure / layout / draw）
  └─ ⑤ 首帧上屏 → **Displayed** 打点
        ↓ 之后业务往往还有异步数据要加载
     ⑥ 内容真正可见 → **reportFullyDrawn()** 打点
```

**关键认知**：`Displayed` 只到"首帧上屏"，但用户感知的"能用"往往在 `reportFullyDrawn()` 之后。**两个指标都要看，主动调 `reportFullyDrawn()` 说明你懂用户感知。**

#### 度量方法

```bash
# 冷启动时间（Displayed）
adb shell am start -W -n com.example/.MainActivity
# 输出：ThisTime / TotalTime / WaitTime

# 系统打点（events buffer）
adb logcat -b events | grep -E "am_|wm_"
# am_activity_launch_time: [0,com.example/.Main,i,1234]  ← 单位 ms

# 更精细：Perfetto 的 android_startup 轨
adb shell perfetto -o /data/misc/perfetto-traces/startup.perfetto-trace -t 8s \
  sched freq am wm gfx view binder_driver dalvik

# 应用内打点
# 在首帧回调里调用 reportFullyDrawn()
```

#### 优化手段（按收益排序）

| 手段                          | 机制                                                                                                           | 典型收益                   |
| --------------------------- | ------------------------------------------------------------------------------------------------------------ | ---------------------- |
| **1. 精简 `ContentProvider`** | 很多 SDK 用 `ContentProvider` 做"无侵入初始化"，它们在 `Application.onCreate` 之前执行，**每个都是一个启动成本**。用 `App Startup` 库统一延迟初始化 | 100–500ms              |
| **2. 延迟非必要初始化**             | 用 `IdleHandler` / 懒加载 / `App Startup` 的依赖声明                                                                  | 100–400ms              |
| **3. 启动窗口优化（避免白屏）**         | `windowBackground` 用主题化的 `layer-list`/`SplashScreen`，让"白屏"变成品牌图                                              | 感知改善明显                 |
| **4. 异步初始化 + 主线程等待**        | 把初始化拆到多线程，主线程只等关键路径                                                                                          | 200–600ms              |
| **5. 布局优化**                 | 减少层级、用 `ConstraintLayout`、`ViewStub` 延迟加载、`merge`                                                            | 50–200ms（车机 UI 复杂，收益大） |
| **6. 类加载优化**                | 启动路径上的类首次加载有 `verify` 开销 → **Baseline Profile**（Android 7+ 的 `dex2oat` 预处理）+ **Profile Guided Optimization** | 15%–30%                |
| **7. 关闭启动期的无关日志/埋点**        | 简单但有效                                                                                                        | 小                      |
| **8. 车机专属：优先出关键画面**         | 倒车影像/空调 HMI 优先渲染，其他延迟                                                                                        | 车机特有                   |

**Baseline Profile 值得展开**（这是现代 Android 启动优化的"标准答案"）：

```
问题：ART 在首次运行某段代码时，要先解释执行（收集 profile），再编译（JIT/AOT）。
      用户第一次打开 App 时，所有这些代码都是"冷的"。

解法：在"云端/构建期"用 Macrobenchmark 跑一遍关键路径，生成 profile 文件，
      打进 APK（assets/dexopt/baseline.prof），安装时 dex2oat 按它做 AOT 编译。

结果：冷启动显著变快（官方数据 15%–30%），首帧更快。
```

```kotlin
// app/build.gradle
baselineProfile {
    saveInSrcDir = true
}

// baseline-prof.txt 的内容长这样（由 Macrobenchmark 生成）
HSPLcom/example/MainActivity;->onCreate(Landroid/os/Bundle;)V
HSPLcom/example/data/Repository;->load()V
```

#### 车机启动优化的特殊性

| 差异                     | 说明                                                             |
| ---------------------- | -------------------------------------------------------------- |
| **"开机启动"≠"应用启动"**      | 车机关心的是"上电到可用"（整机启动），涉及 init.rc 的并行化、服务裁剪、`system_server` 初始化顺序 |
| **STR 唤醒比冷启动常见**       | 优化重点从"冷启动"转到"从 suspend 恢复到可用"的时间                               |
| **主屏必须是 Native 而非 H5** | H5 首帧受 WebView 初始化影响（数百 ms），车机主屏要保证快速可见                        |
| **倒车影像必须 <2s**         | 通常走独立通路（不经 Android），但 Android 侧的"接管时机"要准确                      |

### 3.7.2 内存优化

#### 度量

```bash
adb shell dumpsys meminfo <pkg>          # PSS / RSS / Heap 各项
adb shell dumpsys meminfo <pkg> -d       # 详细信息
adb shell am dumpheap <pkg> /data/local/tmp/hprof
adb shell dumpsys gfxinfo <pkg>          # 渲染相关
```

**关键指标**：

| 指标                             | 含义                         | 关注点           |
| ------------------------------ | -------------------------- | ------------- |
| **PSS**（Proportional Set Size） | 按比例分摊共享内存后的实际占用            | **最接近真实内存消耗** |
| **RSS**                        | 物理内存映射总量（含共享）              | 会虚高           |
| **Java Heap**                  | ART 堆                      | 与 GC 相关       |
| **Native Heap**                | native 分配（Bitmap、Skia、解码器） | 容易漏看          |
| **Graphics**                   | GPU 显存映射                   | 车机上很关键        |

#### 常见问题与对策

| 问题                 | 机制                                   | 对策                                                           |
| ------------------ | ------------------------------------ | ------------------------------------------------------------ |
| **Activity 泄漏**    | 长生命周期对象持有 Activity                   | LeakCanary 检测；用 `applicationContext`；`WeakReference`         |
| **Bitmap 过大**      | 未按需采样、未复用                            | `inSampleSize`、`inBitmap` 复用、`BitmapFactory.Options`         |
| **Native 内存泄漏**    | JNI 层分配没释放、`Bitmap` 的 native 部分      | `malloc_debug`、`heapprofd`（Perfetto 的 native heap profiling） |
| **Handler / 线程泄漏** | 匿名内部类 + 未取消                          | `removeCallbacksAndMessages(null)`、`lifecycleScope`          |
| **资源未关闭**          | `Cursor` / `InputStream` / `WebView` | try-with-resources；`onDestroy` 清理                            |
| **WebView 泄漏**     | 未 `destroy()`                        | 见 3.6 的标准销毁流程                                                |
| **大对象数组**          | 一次加载大量数据                             | 分页、`Paging`、流式处理                                             |
| **匿名类持有 Context**  | 静态字段里的 `View`                        | 静态字段不存 View / Context                                        |

#### LeakCanary 的原理（面试高频）

```
① ObjectWatcher 创建 KeyedWeakReference（弱引用）指向"应该被回收的对象"
② 加入 ReferenceQueue 等待 GC
③ 等 5 秒后延迟检查：如果弱引用还在 queue 外（未被回收）
   → 判定为"可能泄漏"
④ 触发 HeapDump（.hprof）
⑤ Shark（LeakCanary 的 hprof 分析器）做 dominator tree 分析，
   找到"从 GC Root 到该对象的最短强引用路径"
⑥ 输出泄漏链与建议
```

**关键理解**：**"可能泄漏" ≠ "一定泄漏"**（可能只是 GC 还没跑）。所以 LeakCanary 会先 `Runtime.gc()` 再等，仍然在就高度可疑。

```kotlin
// 自定义 LeakCanary 的 Watch
class CarFragment : Fragment() {
    override fun onDestroy() {
        AppWatcher.objectWatcher.watch(this, "CarFragment destroyed")
    }
}
```

#### Android 12+ 的 "App 内存限制" 与车机

Android 12 引入了按"性能等级（Performance Class）"界定的内存上限。**车机的内存配置通常远高于手机**（8GB–16GB 常见），但**同时运行的东西也更多**（多屏、多 zone、常驻应用）。**所以车机的内存优化目标是"不 OOM 且不频繁 GC"，而不是"越小越好"。**

### 3.7.3 卡顿优化

见 2.8 的图形栈部分（`BufferQueue`、VSYNC、`Choreographer`、`RenderThread`）。这里补充**应用侧的排查方法论**：

```
① 先用 Perfetto/Systrace 抓一段，看 Choreographer#doFrame 是否超 16.67ms
② 看超时的部分是：
     measure/layout 慢  → 布局层级深、重复测量、RelativeLayout、动态创建 View
     draw 慢            → onDraw 里做重操作、大量绘制指令、频繁 invalidate
     RenderThread 慢    → 复杂 Shader、大纹理、过度绘制
     binder 同步调用    → 主线程同步 Binder 阻塞（看 binder transaction 轨）
     锁竞争             → 主线程等锁（看 sched 轨的 Blocked）
③ 用"二分法"缩小：注释掉部分代码/用 Layout Inspector 看层级
```

**高频原因清单**：

| 原因                 | 检测                                          | 修复                                            |
| ------------------ | ------------------------------------------- | --------------------------------------------- |
| 布局层级过深             | Layout Inspector                            | 扁平化、`ConstraintLayout`、`merge`                |
| 重复测量               | Systrace 的 `measure` 轨                      | 避免 `RelativeLayout`、`NestedScrollView` 嵌套     |
| 主线程 IO             | StrictMode                                  | 移到子线程                                         |
| 主线程同步 Binder       | Systrace 的 `binder transaction`             | 改异步/提前缓存                                      |
| 频繁 `requestLayout` | `View` 的 `requestLayout` 计数                 | 批量更新、`View.post` 合并                           |
| 过度绘制               | "调试 GPU 过度绘制"                               | 减少背景层数                                        |
| 动画用 `View` 属性      | —                                           | 用 `ViewPropertyAnimator` / `RenderNode` 友好的属性 |
| 大图未采样              | Profiler                                    | `inSampleSize`                                |
| **Compose 重组过多**   | Compose Compiler Metrics / Layout Inspector | 见 3.3（稳定类型、`derivedStateOf`、lambda Modifier）  |

**车机特有原因**：

- **多屏异刷导致的音画不同步/抖动**（见 2.8 的 VSYNC 部分）
- **硬件合成层数超限**（`dumpsys SurfaceFlinger` 确认）
- **CPU 被其他域抢占**（`sched` 轨看）
- **温度降频**（车机舱内夏天温度高，SoC 降频明显）

### 3.7.4 耗电优化

车机与手机在这里差异最大：**车机的"耗电"是 12V 电瓶，用户对"停几天打不着火"极度敏感。所以车机的"耗电"更多表现为"静态电流（暗电流）"问题。**

| 问题                     | 机制                              | 对策                                            |
| ---------------------- | ------------------------------- | --------------------------------------------- |
| **WakeLock 未释放**       | 持有 `PARTIAL_WAKE_LOCK` 不让 CPU 睡 | `dumpsys power` 查；用带超时的 `acquire(timeout)`    |
| **AlarmManager 频繁唤醒**  | `setRepeating` 导致周期唤醒           | 用 `setExactAndAllowWhileIdle` / `WorkManager` |
| **JobScheduler 不合理**   | 频繁触发                            | 合并任务、加约束（充电/网络/空闲）                            |
| **网络轮询**               | 自建心跳过频                          | 长连接 + 心跳自适应；用 FCM/推送                          |
| **传感器未注销**             | 定位/GPS 一直开                      | 按需注册、`onPause` 注销                             |
| **屏幕常亮**               | `FLAG_KEEP_SCREEN_ON` 忘清        | 用 `WindowManager.LayoutParams` 局部控制           |
| **车机专属：GarageMode 耗电** | 熄火后仍跑后台任务                       | 限制任务时长与频率；MCU 侧监控电流                           |
| **车机专属：外设常供电**         | USB/CAN 收发器常开                   | 由 MCU 控制外设供电，进入休眠时断电                          |

```bash
# 车机常备命令
adb shell dumpsys power | grep -A20 "Wake Locks"
adb shell dumpsys batterystats --reset      # 重置统计
adb shell dumpsys batterystats              # 看耗电归因
# 或抓 bugreport 上传 Battery Historian
```

### 3.7.5 包体优化

| 手段                    | 机制                                  | 收益         |
| --------------------- | ----------------------------------- | ---------- |
| **R8（混淆 + 压缩 + 优化）**  | 无用代码消除、类合并、方法内联                     | 20%–40%    |
| **资源压缩**              | `shrinkResources true`（依赖 R8 的引用分析） | 5%–15%     |
| **资源混淆（AndResGuard）** | 缩短资源路径名                             | 3%–8%      |
| **图片优化**              | WebP、SVG（Vector Drawable）           | 视图片占比      |
| **so 裁剪**             | 只保留需要的 ABI（`abiFilters`）            | 大          |
| **App Bundle / 动态交付** | 按设备下发                               | 下载体积降 30%+ |
| **Dex 分包优化**          | 主 dex 精简（启动相关类）                     | 启动间接收益     |
| **去掉重复依赖**            | `./gradlew dependencies` 查冲突        | 视情况        |
| **车机专属：多语言/多分区裁剪**    | 车机只需要少数语言与地区资源                      | 可省数 MB     |

### 【常见追问】

**Q：启动优化你会先看什么？**  
**先看 `ContentProvider`。** 因为它们在 `Application.onCreate` 之前执行，且通常是第三方 SDK 偷偷加的初始化（推送、统计、崩溃上报），你不知道它们存在，但它们已经吃掉了 200–500ms。用 `adb shell dumpsys package <pkg> | grep Provider` 或看启动 trace 里的 `bindApplication` 阶段。

**Q：怎么证明优化有效？**  
必须**同一设备、同一条件、多次取中位数**。三个坑：① 首次安装后的第一次启动（要 AOT 编译）比后续慢很多，不能混在一起比；② 系统在后台忙时数据失真；③ 只用一次测量不够（抖动大）。**工程上建议用 `Macrobenchmark` 写自动化用例。**

**Q：为什么"优化完过几个月又慢了"？**  
因为**没有防劣化机制**。解法：① CI 里加性能门禁（启动时间超过基线就 fail）；② 把性能用例纳入发版检查；③ 代码审查规则（禁止主线程 IO、禁止在启动路径加初始化）。**小米那条 JD 里的"长效机制"指的就是这个。**

**Q：车机的性能优化和手机有什么本质不同？**  
四个本质差异：

1. **"启动"的定义不同**（整机上电 vs 应用冷启动；STR 唤醒 vs 冷启动）
2. **功耗约束不同**（12V 电瓶的暗电流是硬指标，不是"用户体验"）
3. **温度环境不同**（舱内高温导致降频，"性能"需要考虑热设计）
4. **实时性约束不同**（倒车影像 <2s、触控到显示延迟，比"平滑"更重要 → 可能主动选"低延迟 + 丢帧"而不是"不丢帧 + 高延迟"）

**这四点如果能在面试里主动讲出来，会显著区别于"只会套手机优化方法论"的候选人。**

### 【延伸方向】

- 官方：`developer.android.com/topic/performance`（整套性能指南）
- 工具：Perfetto（UI 在线使用）、Macrobenchmark、Baseline Profile、LeakCanary、Simpleperf、`heapprofd`
- 延伸：Android Vitals 的指标定义（ANR rate、crash rate、startup time、jank）
- 延伸：车机的启动优化实践（`init.rc` 并行化、服务懒加载、`system_server` 定制）

---

## 3.8 稳定性治理（ANR / Crash / Watchdog / 死机重启）

### 【JD 出处】

- 经创集团：*"负责车机系统稳定性问题排查，分析 ANR、JavaCrash、NativeCrash、Watchdog、内存泄漏、系统死机重启等各类疑难故障"*
- 华苏科技：*"主导系统级性能优化（启动/流畅度/内存），解决 ANR/死机等稳定性问题"*
- 机器岛智能科技：*"能够解决 U 卡顿、应用 crash 和 ANR"*
- BOSS直聘：*"维护 Android Framework 系统核心服务和架构，解决各种死机重启/ANR/FC 等系统问题"*

> **这是车机岗 JD 里出现频率最高的一组词**。它也是"9 年车机经验"最直接的兑现点——因为这些问题的排查经验，是纯应用层候选人拿不出来的。

### 3.8.1 ANR 完整机制

#### 五种 ANR 与超时值

| 类型                              | 超时                                                                            | 触发点                                              | 日志关键词                                  |
| ------------------------------- | ----------------------------------------------------------------------------- | ------------------------------------------------ | -------------------------------------- |
| **Input dispatching timed out** | **5s**（无输入事件的 Window 为 5s；`ActivityRecord` 的 `inputDispatchingTimeoutMillis`） | `InputDispatcher` → `WindowManagerService` → AMS | `Input dispatching timed out`          |
| **Broadcast of Intent**         | 前台 **10s** / 后台 **60s**                                                       | `BroadcastQueue` / `BroadcastDispatcher`         | `Broadcast of Intent`                  |
| **Service Timeout**             | 前台 **20s** / 后台 **200s**                                                      | `ActiveServices`                                 | `Timeout executing service`            |
| **ContentProvider publish**     | **10s**                                                                       | `ContentProviderRecord`                          | `Timeout publishing content providers` |
| **JobScheduler / binding**      | 变化                                                                            | `JobServiceContext`                              | —                                      |

**注意"前台 / 后台"的差异**：为什么后台 broadcast 给 60s？因为后台任务本来就可能被 CPU 抢占，给太短会误判。**这个取值差异背后是"误报 vs 漏报"的权衡**，能讲出来说明你理解设计意图而非背数字。

#### 完整触发链路（以输入超时为例）

```
① 用户触摸 → InputReader 读取 → InputDispatcher 分发
② InputDispatcher 把事件投递到目标 Window 的 App 进程（通过 InputChannel）
   └─ 记录"开始等待时刻"
③ App 侧在 InputEventReceiver 回调里处理
   └─ 如果主线程忙（消息队列积压），事件排队等待
④ InputDispatcher 等待 5s（默认）
   └─ checkWindowReadyForMoreInput() 判定超时
⑤ 通知 WindowManagerService / ActivityManagerService
   └─ AMS 的 AnrHelper / AppErrors.appNotResponding()
⑥ AMS 收集"现场证据"
   ├─ dumpStackTraces()：dump 所有进程的栈（主线程 + Binder 线程）
   ├─ CPU 使用信息（top / /proc/stat）
   ├─ 内存信息
   └─ 写入 /data/anr/anr_<时间戳>（多份）与 traces.txt
⑦ 弹 ANR 对话框（前台时）；或静默记录（后台）
   └─ 同时上报（DropBox: SYSTEM_APP_ANR / data_app_anr）
⑧ 若用户选择"等待"，继续；若"关闭应用"，AMS 杀进程
```

**Android 11 的变化**：引入 `AnrHelper`，**ANR 的栈收集从 AMS 主线程移到独立线程**（`AnrConsumer`），避免"ANR 时系统也卡住"。**这是一个很值得提的演进细节** —— 说明 Google 也在解决"ANR 处理的性能问题"。

#### 证据文件在哪

```bash
# 应用迹
adb shell ls -l /data/anr/
adb shell cat /data/anr/anr_2026-10-12-15-23-09-123

# 系统 DropBox（更完整，含 system_server ANR / watchdog）
adb shell dumpsys dropbox --print | grep -A100 system_app_anr
adb shell dumpsys dropbox --print | grep -A100 system_server_watchdog

# 直接看 ANR 记录
adb shell dumpsys activity anrs
adb shell dumpsys activity processes | grep -A5 "ANR"
```

**`/data/anr/` 下的文件很关键**：ANR 发生时，AMS 会 dump **所有进程**的栈（不只是出问题的那个），所以你可以看到"谁在占 CPU / 谁持有锁"。**这是分析 ANR 的完整证据链。**

#### 六类根因（这是你排查时的分类框架）

| 根因                            | 典型栈特征                                         | 定位方法                             |
| ----------------------------- | --------------------------------------------- | -------------------------------- |
| **① 主线程做耗时操作**                | 主线程栈停在 IO / 解析 / 循环                           | 看主线程栈最上面的业务代码                    |
| **② 主线程等锁**                   | 主线程栈含 `waiting to lock <0x...> (a Monitor)`   | **看括号里的对象被哪个线程持有** → 那个线程就是罪魁    |
| **③ 主线程同步 Binder 阻塞**         | 主线程栈含 `android.os.BinderProxy.transactNative` | 找是调哪个服务；再看那个服务的进程是否卡住            |
| **④ Binder 线程池被占满**           | 多个 `Binder:xxx_N` 线程都在跑业务                     | 说明 Binder 回调里有耗时操作               |
| **⑤ 系统服务卡住（system_server 侧）** | 主线程正常但事件分发不下去                                 | 看 `system_server` 的栈（traces 里会有） |
| **⑥ CPU 被其他进程抢占**             | 栈看起来正常但 timeout                               | 看 traces 里的 CPU 信息、`top` 输出      |

**分类框架的价值**：拿到一份 ANR trace，**先归类再深挖**，而不是"从头到尾读一遍栈"。

**②的解释要特别强调**：trace 里的格式是

```
"main" prio=5 tid=1 Blocked
  at com.example.Foo.bar(Foo.java:42)
  - waiting to lock <0x0a1b2c3d> (a java.lang.Object) held by thread 23
```

注意最后的 **`held by thread 23`** —— 这个信息是 Android 特有的（ART 会记录锁持有者），**顺着它去找 thread 23 的栈，往往一眼就能看出问题**。这个技巧在面试里说出来很显功力。

### 3.8.2 Crash 分类与定位

#### Java Crash

```
触发：未捕获异常 → Thread 的 UncaughtExceptionHandler
  → ActivityThread 的 KillApplicationHandler
  → AMS.handleApplicationCrash → 记录 DropBox
  → 弹"应用停止运行"（前台）或静默
```

```bash
adb logcat -b crash                    # crash buffer（最直接）
adb shell dumpsys dropbox --print | grep -A50 data_app_crash
```

**关键**：**在 release 版本里，不可捕获异常必须自己接管**（`Thread.setDefaultUncaughtExceptionHandler`），否则你只知道"崩了"而不知道"为什么"。

#### Native Crash

```
触发：SIGSEGV / SIGABRT / SIGBUS / SIGILL 等信号
  → 内核把信号交给 debuggerd（或 crash_dump，Android 8+）
  → crash_dump 抓取：
       ① 信号与地址
       ② 寄存器状态
       ③ backtrace（用 /proc/<pid>/maps 做符号解析）
       ④ logcat 尾部
  → 写入 /data/tombstones/tombstone_XX
```

```bash
adb shell ls -l /data/tombstones/
adb shell cat /data/tombstones/tombstone_01
adb bugreport                     # 更完整（含 tombstone 与 log）
```

**看懂 tombstone 的关键**：

```
backtrace:
  #00 pc 0000000000123456  /system/lib64/libfoo.so (bar+0x28)
  #01 pc 0000000000234567  /system/lib64/libbaz.so (qux+0x1c)
```

**符号化**（把 `bar+0x28` 变成 `FooBar::doThing() at foo.cpp:123`）：

```bash
# 方法 1：ndk-stack（需要未 strip 的 so）
ndk-stack -sym <path-to-unstripped-libs> -dump tombstone_01

# 方法 2：addr2line（需要带调试信息的 so）
llvm-symbolizer --obj=libfoo.so 0x123456

# 方法 3：直接给符号表文件
# 构建时保留 symbols（out/target/product/<dev>/symbols/）
```

**这是 Native Crash 排查的必备技能，而且"符号表"这件事恰恰是系统工程师的日常。**

#### Watdog（Watchdog）

```
是什么：运行在 system_server 里的一个监控线程（com.android.server.Watchdog）
监控对象：main、ui、display、animation、fstrim、ion、file、reboot 等 Handler
超时值：默认 60s（DEFAULT_TIMEOUT），可通过 ro.hw_timeout_multiplier 放宽

行为：
  第一次超时 → dump 所有线程栈（"Watchdog半杀"），但不重启
  第二次超时（再等 60s）→ 直接 kill system_server
      → Zygote 重启 → Android 层重启（用户看到"黑屏后回到开机动画"）

Watchdog 的堆栈也会进 /data/anr/ 与 DropBox（system_server_watchdog）
```

```bash
adb logcat | grep -i watchdog
adb shell dumpsys dropbox --print | grep -A100 system_server_watchdog
# Watchdog 的 trace 也是全进程 dump，同样可以分析
```

**关键工程知识**：

| 技巧                             | 说明                                                                  |
| ------------------------------ | ------------------------------------------------------------------- |
| **`setAllowRestart(false)`**   | 调试用：让 Watchdog 只 dump 不重启，保住现场（`adb shell setprop` 或代码里改）           |
| **`ro.hw_timeout_multiplier`** | 放宽超时（低性能机器/调试时用）                                                    |
| **Watchdog 栈里找"谁卡住"**          | Watchdog 自身的栈会显示它在等哪个锁/哪个线程 → 那个就是罪魁                                |
| **常见原因**                       | system_server 里等一个卡死的 Binder（如某个 HAL 挂了）、锁竞争、`PackageManager` 扫描大量包 |

#### 系统死机 / 重启

**这是车机上最严重的问题（用户描述为"车机黑屏重启了"）。** 分层排查：

```
① 应用层重启？      → 只是 Launcher 重启，logcat 有 ActivityManager 重启日志
② Android 层重启？  → Zygote 重启，logcat 有 "Zygote: Exit" 与完整开机日志
③ 内核 panic？      → 看 /sys/fs/pstore/（ramoops）或 /proc/last_kmsg
④ 硬件复位？        → 看 bootreason（ro.boot.bootreason）
                      常见值：kernel_panic / watchdog / reboot_reason_* / hard_reset
```

```bash
# bootreason（最关键的线索）
adb shell getprop ro.boot.bootreason
adb shell cat /proc/cmdline | tr ' ' '\n' | grep -i bootreason

# pstore / ramoops（内核崩溃现场，掉电不丢）
adb shell ls -l /sys/fs/pstore/
adb shell cat /sys/fs/pstore/dmesg-ramoops-0

# Android 层的最后遗言（DropBox 是金矿）
adb shell dumpsys dropbox --print | grep -E "watchdog|tombstone|system_server|system_app"
adb shell dumpsys dropbox --print | tail -200

# 内核日志（若有）
adb shell cat /proc/last_kmsg | tail -200     # 老平台
```

**DropBox 是车机问题排查的核心工具**，它记录了：

- `SYSTEM_TOMBSTONE`（native crash）
- `system_app_anr` / `data_app_anr`
- `system_server_watchdog`（Watchdog 触发）
- `system_server_crash` / `system_server_lowmem`
- `SYSTEM_SERVER_WATCHDOG`  等

**面试时的讲法**：不要说"我们看 logcat"，要说"**先定层级（应用 / Android / 内核 / 硬件），再用 bootreason 与 DropBox 定位到具体层，最后看该层的证据文件**"。这是方法论，不是技巧。

### 3.8.3 稳定性治理的工程体系

| 层次      | 手段                                                                           |
| ------- | ---------------------------------------------------------------------------- |
| **预防**  | 代码审查规则（主线程 IO 检测 StrictMode）、静态分析（Lint / Detekt）、单测与集成测试                     |
| **监控**  | 崩溃上报（自建 / Bugly / Firebase）、ANR 上报（含栈 + 设备信息 + 场景）、**Watchdog 与 DropBox 采集** |
| **归因**  | 按签名聚合（同一根因的多个栈归为一类）、按版本/机型/场景分布                                              |
| **止损**  | 灰度发布、远程开关（`Feature Flag`）、降级策略                                               |
| **闭环**  | 每个问题要有"根因 + 修复 + 验证 + 防再发"四件套                                                |
| **防劣化** | 崩溃率/ANR 率的 CI 门禁；线上指标告警                                                      |

**"崩溃率 / ANR 率"是要能说出量级的**：

| 指标           | 优秀    | 及格    | 危险      |
| ------------ | ----- | ----- | ------- |
| 崩溃率（用户维度）    | <0.1% | <0.5% | >1%     |
| ANR 率（用户维度）  | <0.1% | <0.3% | >0.5%   |
| 启动崩溃（首次启动即崩） | 0     | —     | 任何非零都要查 |

**车机的指标要求通常更严**（因为影响面是整车功能而不是单个 App）。

### 【常见追问】

**Q：拿到一份 ANR trace，你的第一步是什么？**  
**先看"是什么类型的 ANR"**（输入/广播/服务），再看**主线程栈的顶部**（在做什么），然后**如果是等锁，顺着 `held by thread N` 找持有者**。三步定方向，然后才深挖。**不要一上来就读整份 trace** —— 那会浪费时间且容易迷路。

**Q：为什么 ANR 时系统也会卡？**  
因为 AMS 收集栈（`dumpStackTraces`）是**在 AMS 的主线程上做的**（Android 11 之前），dump 几十个进程的栈要几百毫秒到几秒，期间 AMS 无法响应其他请求 → 整个系统卡顿。**Android 11 的 `AnrHelper` 正是为了解决这个问题**（把收集移出主线程）。

**Q：`Watchdog` 为什么不直接重启而是先 dump？**  
因为**重启会丢掉现场**，没有栈就无法定位。所以设计成"第一次 dump（留证据），第二次才重启（保可用）"。同时它给了一次"救回来"的机会——如果卡住的原因消失了，系统能继续跑。**这是"可用性 vs 可诊断性"权衡的经典设计。**

**Q：Native Crash 和 Java Crash 有关系吗？**  
有关系。两个常见关联：① **Java 层持有 native 资源未释放** → native 侧访问野指针 → SIGSEGV；② **JNI 层的异常处理不当** → Java 异常穿过 JNI 边界 → 未定义行为 / abort。**所以排查 Native Crash 时要同时看 Java 侧的调用上下文**（tombstone 里的 `logcat` 尾部通常会带一些 Java 栈信息）。

**Q：你在车机上遇到的最难的问题是什么？**（这是"送分题"，但需要准备）  
按这个结构准备 2–3 个：

```
① 现象：谁在什么场景下看到什么异常（要具体：车型、工况、复现概率）
② 初步分层：是应用层 / Android 层 / HAL 层 / 内核层
③ 证据采集：抓了什么（logcat / tombstone / Dropbox / perfetto / 串口日志）
④ 定位过程：怎么排除干扰项，找到根因
⑤ 修复与验证：改了什么，怎么验证（复现概率下降 / 长稳测试）
⑥ 防再发：加监控 / 加规则 / 加测试
```

**有你 9 年的车机经历，这个题是你可以碾压别人的地方。** 关键在于**提前把 2–3 个案例整理成这个六段式**，而不是现场想。

### 【延伸方向】

- 源码：`frameworks/base/services/core/java/com/android/server/am/` 的 `AppErrors.java`、`AnrHelper.java`
- 源码：`frameworks/base/services/core/java/com/android/server/Watchdog.java`
- 源码：`frameworks/base/core/java/android/os/`（`Debug.java` 的 `dumpStackTraces`）
- 工具：`debuggerd` / `crash_dump`（`system/core/debuggerd/`）、`ndk-stack`、`llvm-symbolizer`
- 延伸：`DropBoxManagerService` 的实现与 `dumpsys dropbox` 的所有 tag
- 延伸：内核侧 `ramoops`/`pstore` 配置、`last_kmsg`、`bootreason` 的写入路径

---

# 第四部分：音视频与图形

> 对应岗位：紫光展锐（20–40K 音频框架）、软通动力（15–27K 播放器）、北弓智能（流媒体）、小米南京（图形渲染）。  
> **对车机人的价值**：车机的"多媒体"（蓝牙音乐、本地播放、投屏、语音）和"图形"（仪表动画、HMI 转场、倒车影像）本质都落在这个技术栈上。

## 4.1 Android 媒体框架全景与播放链路

### 【JD 出处】

- 紫光展锐：*"熟悉 Android 多媒体系统框架（mediaplayer/mediarecorder/mediacodec/mediaextractor/stagefright/nuplayer 等）"*
- 软通动力：*"熟悉多媒体相关知识（Demuxer / Decoder / Render 等）；负责视频播放器新需求开发与项目维护"*

### 【要解决的问题】

一个 MP4 文件要变成屏幕上的画面与会出声的声音，需要：**解封装 → 解码 → 渲染 → 音视频同步**。这四步在 Android 里由一条完整的框架链承担。

### 【原理】

#### 全链路

```
应用层
  MediaPlayer / ExoPlayer / MediaCodec 直接用
      │
      ▼
┌──────────────────────────────────────────────────────┐
│ frameworks/av/media/libmediaplayerservice/            │
│   MediaPlayerService（native 服务，跑在 mediaserver）  │
│     ├─ createPlayer() 根据 use-nuplayer 决定：         │
│     │    NuPlayer（现代，默认）                        │
│     │    StagefrightPlayer（历史，已废弃）             │
│     └─ NuPlayer                                        │
│         ├─ NuPlayer::GenericSource  ← 本地文件         │
│         ├─ HTTPLiveSource           ← HLS              │
│         ├─ RTSPSource               ← RTSP             │
│         ├─ NuPlayerDecoder          ← 解码             │
│         └─ NuPlayerRenderer         ← 渲染与 A/V 同步  │
├──────────────────────────────────────────────────────┤
│ MediaExtractor（解封装/Demuxer）                       │
│   把 MP4/TS/MKV 拆成音轨、视频轨、字幕轨                │
│   输出：编码后的 ES（Elementary Stream）               │
├──────────────────────────────────────────────────────┤
│ MediaCodec（解码/编码）                                │
│   上层接口 → frameworks/av/media/libstagefright/       │
│     ├─ ACodec（OMX 路径，旧）                          │
│     └─ CCodec（Codec2 路径，新，Android 10+ 默认）      │
│   → 硬件编解码器（SoC 的 VPU）或软件编解码器            │
├──────────────────────────────────────────────────────┤
│ 渲染                                                   │
│   视频：解码输出到 Surface → SurfaceFlinger 合成        │
│   音频：解码输出到 AudioTrack → AudioFlinger 混音       │
└──────────────────────────────────────────────────────┘
```

#### 四个关键概念（面试必问）

| 概念           | 中文  | 职责                     | Android 对应                       |
| ------------ | --- | ---------------------- | -------------------------------- |
| **Demuxer**  | 解封装 | 把一个容器文件拆成多条流           | `MediaExtractor`                 |
| **Decoder**  | 解码  | 把压缩的 ES 变成原始帧（YUV/PCM） | `MediaCodec`                     |
| **Renderer** | 渲染  | 把原始帧送到显示/音频设备          | `Surface` / `AudioTrack`         |
| **Sync**     | 同步  | 让音频和视频对齐（以音频时钟为基准）     | `NuPlayerRenderer` / `MediaSync` |

**"音视频同步"是面试的分水岭问题**：

```
标准做法：以音频时钟为主时钟（audio master clock）
  ① 音频通常不能丢帧（丢帧会被听出来），视频可以丢
  ② 视频帧带 PTS（Presentation TimeStamp）
  ③ 渲染前计算：视频 PTS 与 当前音频播放位置的差值
       video ahead  → 等待（或降帧）
       video behind  → 丢帧（只显示最新的）
       video 在阈值内 → 正常显示
```

**车机的特殊性**：车机上视频（尤其是倒车影像、环视）**要求低延迟，宁可丢帧也不能滞后** —— 这正好与"电影播放追求平滑"相反。所以车机上影像类视频常用 **tunneled playback**（见 4.4），并倾向于"丢帧保实时"。

#### `MediaPlayer` 的状态机（基础但常考）

```
Idle
 └─ setDataSource() → Initialized
     ├─ prepare()/prepareAsync() → Prepared
     │   └─ start() → Started
     │       ├─ pause() → Paused
     │       └─ seekTo()/start() → Started
     ├─ reset() → Idle
     └─ release() → End
```

**关键规则**：`release()` 之后 `MediaPlayer` 不可复用（要重新 `new`）。**`MediaPlayer` 是有限资源**（底层占解码器），不 `release` 会导致"下一次播放失败"或"解码器耗尽"。**这是"播放几次后就播不了"类 bug 的根因。**

### 【常见追问】

**Q：`MediaPlayer` 和 `ExoPlayer` 怎么选？**

|          | `MediaPlayer` | `ExoPlayer`                   |
| -------- | ------------- | ----------------------------- |
| 底层       | native 实现，黑盒  | Java 层，可定制                    |
| 格式支持     | 依赖平台          | 可扩展（可加自定义 Extractor/Renderer） |
| DASH/HLS | 有限            | **完整支持**                      |
| 自定义      | 很难            | **容易**（可以注入自定义数据源、解码器）        |
| 体积       | 系统内建          | 增加几百 KB                       |
| 适用       | 简单播放          | **复杂场景（DRM、自适应码率、字幕、多音轨）**    |

**车机建议**：车机的播放需求通常复杂（多音轨、source 切换、与音区联动），**`ExoPlayer` 更合适**；但"低延迟倒车影像"要用 `MediaCodec` 直接操作（`ExoPlayer` 的抽象层会引入延迟）。

**Q：为什么播放 4K 视频有时会卡？**  
三个可能：① **硬解码器不支持该 Profile/Level** → 退到软解（CPU 解码 4K 一定卡）；② **解码输出到 `Surface` 的路径有额外拷贝**（如果用了 `TextureView` 而不是 `SurfaceView`）；③ **码率超出硬件带宽**（4K 60fps 的码率需求可能超过内存带宽）。**排查手段：`dumpsys media.codec`、logcat 里的 `OMX` / `CCodec` 日志、确认 `MediaCodecInfo` 是否 `isHardwareAccelerated()`。**

### 【延伸方向】

- 源码：`frameworks/av/media/libmediaplayerservice/nuplayer/`（`NuPlayer.cpp`、`NuPlayerRenderer.cpp`）
- 源码：`frameworks/av/media/libstagefright/`（`MediaExtractor`、`ACodec`、`CCodec`）
- 延伸：`media.codec` 的 HAL（`android.hardware.media.c2`，Codec2）
- 延伸阅读：`MediaCodecInfo` / `CodecCapabilities`（判断设备支持哪些格式）

---

## 4.2 MediaCodec 原理

### 【JD 出处】

- 紫光展锐的 JD 明确列出 `MediaCodec`
- 北弓智能：*"了解音视频编解码原理，如 H.264、H.265、AAC 等，能够进行相关性能优化"*

### 【原理】

#### 两种工作模式

**① 同步模式（Buffer 模式）**

```java
val codec = MediaCodec.createDecoderByType("video/avc")
codec.configure(format, surface, null, 0)
codec.start()

while (true) {
    val inIdx = codec.dequeueInputBuffer(10000)          // 拿一个输入 buffer
    if (inIdx >= 0) {
        val buf = codec.getInputBuffer(inIdx)!!
        buf.clear()
        val size = extractor.readSampleData(buf, 0)      // 从 MediaExtractor 读一帧
        val pts = extractor.sampleTime
        codec.queueInputBuffer(inIdx, 0, size, pts, 0)   // 提交
        extractor.advance()
    }

    val outIdx = codec.dequeueOutputBuffer(info, 10000)  // 拿一个输出 buffer
    if (outIdx >= 0) {
        codec.releaseOutputBuffer(outIdx, true)          // true = 渲染到 Surface
    } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
        // 输出格式变了（如分辨率变化）
    }
}
```

**② 异步模式（Callback 模式，API 21+）**

```java
codec.setCallback(object : MediaCodec.Callback() {
    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
        // 有输入 buffer 可用了 → 填数据 → queueInputBuffer
    }
    override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: BufferInfo) {
        // 有输出帧了 → releaseOutputBuffer(index, render = true)
    }
    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
    override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {}
})
```

**异步模式的三个优势**：① 不需要轮询（省 CPU）；② 天然支持"解码器随时可用就喂数据"（吞吐更平稳）；③ 更容易与 Flow/协程结合。**新代码优先用异步模式。**

#### 关键设计：Buffer 的所有权

```
dequeueInputBuffer()  → 你拿到 index，往里填数据
queueInputBuffer()    → 交还给解码器（此时你不再拥有它）
dequeueOutputBuffer() → 你拿到 index，代表一帧解码结果
releaseOutputBuffer() → 交还给解码器（必须先渲染/消费完）
```

**核心规则**：**`dequeue` 拿到的 buffer 在 `queue`/`release` 之前属于你，之后属于解码器。** 如果乱序或重复操作，会得到 `IllegalStateException` 或 `CodecException`。

**这是"播放器崩溃"类 bug 的高频原因**（尤其是在 `seek`、`stop`、`flush` 与解码线程并发时）。

#### 为什么需要 `flush()`

```java
codec.flush()   // 丢弃所有待处理的输入/输出 buffer，重置到 start() 后的状态
```

**用途**：`seek` 之后，之前排队的数据都作废了，必须 `flush` 清干净，否则会播到"seek 前的帧"。**但 `flush` 之后必须重新提交 CSD（Codec Specific Data，即 H.264 的 SPS/PPS）**，否则解码器不知道参数集。**这是 `seek` 实现里最容易漏的一步。**

#### Surface 模式 vs ByteBuffer 模式

|    | ByteBuffer 模式            | Surface 模式                    |
| -- | ------------------------ | ----------------------------- |
| 输出 | 解码成 YUV/RGB 到 ByteBuffer | 直接输出到 `Surface`               |
| 性能 | **差**（多一次拷贝 + CPU 色彩转换）  | **好**（零拷贝，直接进 SurfaceFlinger） |
| 用途 | 需要处理像素（滤镜、识别、转码）         | **播放（绝大多数场景）**                |

```java
// Surface 模式：configure 时传 surface
codec.configure(format, surface, null, 0)

// 之后 releaseOutputBuffer(index, true) 就直接渲染了
```

**面试要点**：**"播放一律用 Surface 模式"**，因为 ByteBuffer 模式要先解码到内存、再由 GL 上传纹理，多两次拷贝。**只有在做图像处理（如视频滤镜、AI 推理）时才需要 ByteBuffer 模式。**

#### 车机特有的低延迟技巧

| 技巧                             | 说明                                |
| ------------------------------ | --------------------------------- |
| **`KEY_LOW_LATENCY`**（API 30+） | 让解码器优先降低延迟而非吞吐（可能不输出 B 帧）         |
| **`KEY_OPERATING_RATE`**       | 提示解码器的运行速率（低延迟场景设高）               |
| **不要 B 帧**                     | B 帧需要等待后续帧，天然增加延迟。车上影像流通常用全 I/P 帧 |
| **`MediaFormat.KEY_PRIORITY`** | 设 0（实时优先）                         |
| **缩短 `dequeue` 超时**            | 用 0 超时 + 非阻塞轮询（或用异步模式）            |
| **减少 `Surface` 层级**            | 见 2.8，层数超限会退到 GPU 合成              |

### 【常见追问】

**Q：`dequeueOutputBuffer` 返回 `INFO_TRY_AGAIN_LATER` 表示什么？**  
表示"暂时没有输出"（解码器还在工作）。**这不是错误**，应该等待后重试。返回 `INFO_OUTPUT_FORMAT_CHANGED` 表示输出格式变了（如分辨率/色彩空间变化），必须调用 `codec.getOutputFormat()` 重新读取。

**Q：解码器数量有限制吗？**  
有。SoC 的硬件解码器通常只有 1–2 个实例（受 VPU 资源限制）。**所以"同时播放多个视频"在车机上往往做不到**（比如主屏播视频 + 副屏播视频 + 倒车影像，就可能超出）。`MediaCodecList` 与 `MediaCodecInfo` 能查到支持的实例数。**这是车机多屏视频需求的核心约束，也是"为什么车机只有一个视频源"的硬件原因。**

**Q：CSD（Codec Specific Data）是什么？**  
H.264 的 SPS/PPS、H.265 的 VPS/SPS/PPS、AAC 的 AudioSpecificConfig。它们是"解码器初始化参数"，必须在第一帧之前提交。在 `MediaFormat` 里用 `csd-0` / `csd-1` 传递。**`MediaExtractor` 会自动把它们放进 `MediaFormat`，但手动构造（如接收 RTSP 流）时必须自己提取。**

### 【延伸方向】

- 官方：`developer.android.com/reference/android/media/MediaCodec`
- 示例：AOSP 的 `cts/tests/tests/media/codec/src/...`、Grafika（老但经典）
- 源码：`frameworks/av/media/libstagefright/` 的 `ACodec.cpp`、`Codec2/`（`CCodec.cpp`）
- 延伸：Codec2 HAL（`android.hardware.media.c2`）的组件模型

---

## 4.3 Audio 栈：AudioFlinger / AudioPolicy / 车机多 zone

### 【JD 出处】

- 紫光展锐：*"熟悉 Android 音频系统框架（AudioService、AudioEffect、AudioPolicy、AudioFlinger、SoundTrigger 等）；有丰富的 AudioService、AudioPolicy、AudioFlinger 优化调试经验者优先"*
- 光庭信息：*"至少擅长车载任一核心模块：车控 CarService/VehicleHAL、车载音频、相机、网络、车载诊断、系统策略定制等"*

> **车载音频是南京市场上最稀缺的技能之一**。原因很简单：只有做过车机的人才会碰到"多音区 + 抢占 + ducking"这套东西。

### 【原理】

#### 分层架构

```
应用层
  AudioManager / MediaPlayer / SoundPool / AudioTrack / AudioRecord
      │  Binder
      ▼
┌───────────────────────────────────────────────────┐
│ AudioService（system_server 内）                   │
│   ├─ 音量管理（各 stream 的 volume index）          │
│   ├─ 音频焦点（AudioFocus）                        │
│   ├─ 静音/振动/铃声模式（RingerMode）               │
│   └─ AudioDeviceBroker（设备连接状态管理）          │
├───────────────────────────────────────────────────┤
│ AudioPolicyService（audioserver 进程）             │
│   └─ AudioPolicyManager                            │
│        ├─ 决定"某个 AudioTrack 输出到哪个设备"      │
│        ├─ 输入设备选择（录音源）                    │
│        └─ 音量曲线、强制使用策略                    │
├───────────────────────────────────────────────────┤
│ AudioFlinger（audioserver 进程）                    │
│   ├─ 各类 Thread：                                 │
│   │    MixerThread（混音后输出）                    │
│   │    DirectOutputThread（直通，不经混音）         │
│   │    OffloadThread（硬件解码直通，省电）          │
│   │    RecordThread（录音）                        │
│   ├─ Track（每条音轨，对应一个 AudioTrack）         │
│   └─ 混音（多个 Track 混合成一个输出流）            │
├───────────────────────────────────────────────────┤
│ Audio HAL                                          │
│   （android.hardware.audio@7.0 或 AIDL core HAL）  │
├───────────────────────────────────────────────────┤
│ 驱动 / DSP / 功放                                    │
└───────────────────────────────────────────────────┘
```

**关键分工**：

| 组件                   | 决策           | 执行                        |
| -------------------- | ------------ | ------------------------- |
| `AudioService`       | 音量、焦点、模式     | 通过 `AudioSystem` 调 native |
| `AudioPolicyService` | **路由决策**（去哪） | 通知 AudioFlinger           |
| `AudioFlinger`       | —            | **混音、播放**（怎么放）            |
| Audio HAL            | —            | 真正驱动硬件                    |

**面试常问的一句话总结**：**AudioPolicy 决定"往哪走"，AudioFlinger 决定"怎么混"。**

#### 一次播放的完整链路

```
App: audioTrack.write(pcm) 或 MediaPlayer 内部
  └─ AudioTrack（Java）→ android_media_AudioTrack（JNI）
      └─ AudioTrack（native，libmedia）
          └─ createTrack() → IAudioFlinger（Binder）
              └─ [audioserver 进程]
                  └─ AudioFlinger::createTrack()
                      ├─ 通过 AudioPolicyService 决定 output（哪个 Thread）
                      └─ 创建 Track，加入对应 MixerThread
                          └─ MixerThread 周期性：
                              ├─ 从所有 Track 读 PCM（共享内存环形缓冲）
                              ├─ 混音（叠加 + 音量 + 效果）
                              └─ 写入 Audio HAL（out_write）
                                  └─ DSP → 功放 → 喇叭
```

**关键机制：共享内存环形缓冲（FastMixer / Mmap）**

`AudioTrack`（native）与 `AudioFlinger` 之间用**共享内存**传 PCM（不是 Binder 传数据，Binder 只传控制命令）。**这解释了"为什么音频不能跨进程拷贝"——每秒 48kHz × 2ch × 2B = 192KB/s，Binder 撑不住。**

- **Normal Mixer**：App 写入一块共享内存，`MixerThread` 周期性读取混音 → 有一定延迟（通常 20–40ms）
- **FastMixer / FastTrack**（`AAudio` / `MMAP`）：App 直接写入 AudioFlinger 的输出缓冲 → **延迟可降到 5–10ms**
- **Offload**：压缩音频（MP3/AAC）不解码，直接交给 DSP 解码输出 → **极省电**（车机上很重要）

#### 音频焦点（AudioFocus）

```
App A 请求焦点（AUDIOFOCUS_GAIN）
  → AudioService 检查当前焦点所有者
  → 通知之前的持有者"你失去了焦点"
       AUDIOFOCUS_LOSS         → 永久失去，应停止播放
       AUDIOFOCUS_LOSS_TRANSIENT → 暂时失去（如来电），应暂停
       AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK → **可以降低音量继续播**（导航播报）
  → 授予 A 焦点
```

**车机的差异**：Android 的 `AudioManager.requestAudioFocus` 是"一个赢家"模型；**AAOS 用 `CarAudioFocus`**，它支持：

| 特性                  | 说明                                         |
| ------------------- | ------------------------------------------ |
| **多 zone**          | 前后排各自有独立的焦点栈（互不干扰）                         |
| **上下文（context）优先级** | `NAVIGATION` > `MUSIC`；`ALARM` / `CALL` 最高 |
| **内建的 ducking**     | 规则化：导航播报时自动压低 `MUSIC` 音量到 N%               |
| **拒绝（reject）**      | 某些 context 组合直接拒绝（如两个 `NAVIGATION` 不能同时播）  |
| **强制（exclusive）**   | `SAFETY` / `ALARM` 类可强制排除其他所有音源            |

**这是"车载音频"最核心的差异点。** 面试如果被问"车载音频难点在哪"，答案就是这个。

#### 车机音频的四个难点

| 难点            | 说明                                                                                        |
| ------------- | ----------------------------------------------------------------------------------------- |
| **多 zone 路由** | 同一个 `AudioTrack` 要能路由到"前排"或"后排"，靠 `AudioAttributes` + 设备地址（`AudioDeviceInfo` 的 `address`） |
| **音源抢占与恢复**   | 导航播报结束后，音乐音量要**精确恢复**到播报前的值（不是恢复到某个固定值）                                                   |
| **音区独立音量**    | 前排音量、后排音量是独立组，但共用同一套物理功放 → 需要在 DSP 层做通道级增益                                                |
| **延迟一致性**     | 多 zone 输出要时间对齐，否则前后排听到的声音有回声感                                                             |

#### `AudioEffect` 与 `SoundTrigger`

| 模块                                                            | 作用                                                          |
| ------------------------------------------------------------- | ----------------------------------------------------------- |
| `AudioEffect`（`Equalizer`、`BassBoost`、`Virtualizer`、`Reverb`） | 音效处理，基于 `AudioEffect` 框架（`frameworks/av/media/libeffects`）  |
| `AutomaticGainControl`（AGC）                                   | 麦克风自动增益                                                     |
| `AcousticEchoCanceler`（AEC）                                   | 回声消除（免提通话必需）                                                |
| `NoiseSuppressor`（NS）                                         | 噪声抑制                                                        |
| `SoundTrigger`                                                | **低功耗语音唤醒**（"你好，XX"）→ 靠 `SoundTriggerHAL` + DSP 常驻监听，不唤醒 AP |

**车机上 `AEC` / `NS` 特别重要**（车内麦克风多、有回声与噪声），`SoundTrigger` 是语音助手的基础。

### 【常见追问】

**Q：为什么调整音量会"有延迟"或者"跳变"？**  
音量曲线在 `AudioPolicyManager` 里定义（`VolumeCurve`）。改变音量时 `AudioFlinger` 的 `Track` 需要渐变（ramp）以避免"咔哒"声。**车机上跨 zone 的音量同步更麻烦**，因为需要保证多个输出设备的音步进一致。

**Q：`AudioTrack` 的 `MODE_STREAM` 和 `MODE_STATIC` 区别？**  
`MODE_STREAM`：边写边播（用于长音频，如音乐、TTS）；`MODE_STATIC`：一次性写入全部数据然后播（用于短音效，如提示音，延迟更低）。**车机的提示音（开门音、按键音）应该用 `MODE_STATIC` + `SoundPool`。**

**Q：`usages` 和 `content types` 的作用？**  
`AudioAttributes` 由 `usage`（用途）+ `contentType`（内容类型）组成，**路由与焦点策略都是基于它们**的，而不是基于 App 身份。所以同一个 App 可以有"导航播报"和"音乐播放"两种音频，走不同的路由与焦点规则。**这是 Android 音频设计的核心抽象，能讲清就说明理解了设计意图。**

**Q：`AudioTrack` 的 `underrun` 是什么？**  
"欠载"：App 写得比播放慢，`MixerThread` 读到了空的缓冲 → 产生杂音/断续。**排查**：`dumpsys media.audio_flinger` 里的 underrun 计数；`AudioTrack.getUnderrunCount()`。**车机上的常见原因**：写音频的线程被别的任务抢占（CPU 调度问题）。

### 【延伸方向】

- 源码：`frameworks/av/services/audioflinger/`（`AudioFlinger.cpp`、`Threads.cpp`、`Tracks.cpp`）
- 源码：`frameworks/av/services/audiopolicy/`（`AudioPolicyManager.cpp`）
- 源码：`frameworks/base/services/core/java/com/android/server/audio/`（`AudioService.java`）
- 源码：`packages/services/Car/service/src/com/android/car/audio/`（**CarAudioService，车载音频必读**）
- 工具：`dumpsys media.audio_flinger`、`dumpsys media.audio_policy`、`dumpsys audio`
- 延伸：AAudio（低延迟音频 API）、`MediaSync`、tunneled playback

---

## 4.4 编解码基础与低延迟播放（tunneled playback）

### 【JD 出处】

- 北弓智能：*"了解音视频编解码原理，如 H.264、H.265、AAC 等，能够进行相关性能优化"*
- 软通动力：*"熟悉多媒体和播放器相关知识（Demuxer / Decoder / Render 等）"*

### 【原理】

#### H.264 基础（够用就好）

| 概念                         | 含义                                      |
| -------------------------- | --------------------------------------- |
| **I 帧**（Intra）             | 帧内编码，独立可解（关键帧）                          |
| **P 帧**（Predicted）         | 前向预测，依赖前面的帧                             |
| **B 帧**（Bi-directional）    | 双向预测，依赖前后帧 → **增加延迟**                   |
| **GOP**（Group of Pictures） | 从 I 帧到下一个 I 帧之间的一组帧                     |
| **SPS / PPS**              | 序列参数集 / 图像参数集，解码器初始化必需                  |
| **NALU**                   | 网络抽象层单元，H.264 码流的基本单位                   |
| **Profile / Level**        | 编码档次（Baseline/Main/High）与等级（决定分辨率/码率上限） |

**为什么车载不用 B 帧**：B 帧必须等后面的帧才能解码 → 增加 1–2 帧的延迟 → 对倒车影像、环视是致命的。**所以车载影像流的编码配置通常是 Baseline Profile + 零 B 帧。**

#### H.265（HEVC）相比 H.264

|       | H.264            | H.265                |
| ----- | ---------------- | -------------------- |
| 压缩效率  | 基准               | **同等画质省 40%–50% 码率** |
| 计算复杂度 | 基准               | 约 2–3 倍（解码复杂度也高）     |
| 块大小   | 16×16 宏块         | **可变 CTU（最大 64×64）** |
| 授权    | MPEG LA / Via LA | 多专利池，授权更复杂           |

**车机上的取舍**：H.265 省带宽但更吃解码性能 → 中低端 SoC 上 H.265 4K 可能解码不了。**所以"支持什么格式"要按 SoC 能力清单来定。**

#### AAC 基础

| 概念                              | 含义                                                               |
| ------------------------------- | ---------------------------------------------------------------- |
| **ADTS**                        | 音频传输流格式（每帧带 header），用于流式传输                                       |
| **Raw AAC / LATM**              | 不带 ADTS header，需要在 `MediaFormat` 里给 `csd-0`（AudioSpecificConfig） |
| **AAC-LC / HE-AAC / HE-AACv2**  | 档次；HE 系列用 SBR/PS 技术，低码率下更好听                                      |
| **Sample Rate / Channel Count** | 常用 44.1k/48k、2ch                                                 |

**坑点**：在 Android 上用 `MediaCodec` 解 AAC 时，**如果输入是 ADTS 格式，需要自己去掉 ADTS header**（除非用 `MediaExtractor`，它会处理）。**这是"手动喂 AAC 数据解码失败"的常见原因。**

#### tunneled playback（车机低延迟的关键）

**普通播放路径**：

```
MediaCodec 解码 → AudioTrack 播放音频 + Surface 显示视频
                  ↑ 音视频同步由 App/native 层的 NuPlayerRenderer 做
                  ↑ 音频经过 AudioFlinger 的 MixerThread（有混音延迟）
```

**Tunneled 路径**：

```
MediaCodec(音频) --tunnel--> AudioTrack --tunnel--> AudioFlinger
     ↑
     | 音视频同步时钟由 **AudioFlinger/DSP 的硬件播放位置** 提供
     ↓
MediaCodec(视频) 输出到 Surface，按硬件时钟渲染
```

**优点**：

| 优点       | 原因                            |
| -------- | ----------------------------- |
| **低延迟**  | 音频不经 MixerThread（不混音），直接走硬件通道 |
| **低功耗**  | 音频可 offload（DSP 解码）           |
| **精确同步** | 用硬件播放位置做时钟，不受 App 调度抖动影响      |
| **音质**   | 无损直通（不经混音器的音量处理）              |

**实现要点**：

```java
// 音频侧：用 tunneled AudioTrack
val audioTrack = AudioTrack.Builder()
    .setAudioAttributes(attrs)
    .setAudioFormat(format)
    .setTransferMode(AudioTrack.MODE_STREAM)
    .build()
// MediaCodec 配置时把 audioTrack 的 sessionId 传给它
// 视频侧：MediaFormat.KEY_TUNNEL_PLAYBACK 相关配置
```

**车机为什么依赖它**：车机需要"低延迟 + 多音区 + 省电"，tunneled playback 恰好一次性满足三条。**紫光展锐那条 JD 里的"播放、音效、音质、编解码"问题，很多都会落到 tunneled 路径上。**

### 【常见追问】

**Q：为什么视频"首帧慢"？**  
三个原因：① 必须等到第一个 I 帧（如果是中途接入的流）；② 解码器初始化的固定开销（几十到几百 ms）；③ `Surface` 首次分配 buffer 的开销。**优化：预创建解码器、缓存第一个 I 帧、`Surface` 预热。**

**Q：怎么判断设备是否支持硬件解码某格式？**

```kotlin
val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
val name = list.findDecoderForFormat(format)     // 返回解码器名（null = 不支持）
val info = MediaCodecInfo // 从 list.codecInfos 里找
info.isHardwareAccelerated                        // 是否硬件加速
info.isVendor                                     // 是否厂商实现
```

**注意**：`findDecoderForFormat` 可能返回软解码器。**车机上必须明确要求硬件解码**（`isHardwareAccelerated == true`），否则性能不达标。

### 【延伸方向】

- 规范：ITU-T H.264 / H.265、ISO 14496-3（AAC）
- 工具：`ffprobe`（看码流信息）、`Elecard StreamEye`（码流分析）、`MediaInfo`
- 延伸：tunneled playback 的官方说明与限制（不能与 `Visualizer` 共用等）
- 延伸：Codec2 的 `C2Work` 模型与 `CCodec`

---

## 4.5 流媒体协议：RTP/RTSP/RTMP/SRT/WebRTC

### 【JD 出处】

- 北弓智能：*"熟悉 RTP/RTCP、RTSP、RTMP、SRT 等音视频传输协议，熟悉其在 Android 平台上的应用；熟悉网络编程，深入理解 TCP/IP 协议，掌握 Socket 编程，有 UDP/TCP 长连接、消息可靠投递等相关经验"*
- BOSS直聘：*"流媒体传输协议；WebRTC；直播/视频会议经验"*

### 【原理】

#### 五个协议一张表

| 协议           | 传输层              | 延迟             | 可靠性              | 定位                          |
| ------------ | ---------------- | -------------- | ---------------- | --------------------------- |
| **RTP/RTCP** | UDP              | 低              | 不保证（RTCP 做统计与反馈） | **媒体传输的基石**（几乎所有实时音视频都在它之上） |
| **RTSP**     | TCP（信令）+ RTP（媒体） | 低              | 信令可靠             | **控制协议**（点播、监控、IPC）         |
| **RTMP**     | TCP              | 中（1–3s）        | 可靠               | 推流（直播），Adobe 已弃但生态仍在        |
| **SRT**      | UDP              | **低**          | **可靠（ARQ 重传）**   | 低延迟抗丢包传输（直播、远程制作、车载回传）      |
| **WebRTC**   | UDP（SRTP）        | **极低（<500ms）** | 部分（NACK/FEC）     | 端到端实时通信（视频通话、云游戏）           |

#### RTP / RTCP

```
RTP 包结构：
┌────────┬────────┬───────────┬──────────┬───────────────┐
│ V/P/X  │ CC     │ M / PT    │ Seq Num  │ Timestamp     │
├────────┴────────┴───────────┴──────────┴───────────────┤
│ SSRC（同步源标识）                                        │
├─────────────────────────────────────────────────────────┤
│ Payload（编码后的数据，如 H.264 NALU）                    │
└─────────────────────────────────────────────────────────┘
```

| 字段                 | 作用                       |
| ------------------ | ------------------------ |
| `Seq Num`          | 序号，用于**丢包检测与重排序**        |
| `Timestamp`        | **媒体时间戳（采样率单位）**，用于音视频同步 |
| `SSRC`             | 标识一个媒体流（音视频各一个）          |
| `PT`（Payload Type） | 编码类型（如 96 = H.264）       |

**RTCP**：与 RTP 并行，周期性发送统计信息：

- `SR`（Sender Report）：发送方的发送统计（含 NTP 时间戳，用于墙钟同步）
- `RR`（Receiver Report）：接收方报告丢包率、抖动
- `SDES`：源描述（CNAME）
- `BYE`：结束

**为什么要 RTCP**：RTP 只管"发"，RTCP 负责"反馈与同步"。**音视频同步就靠 SR 里的 NTP 时间戳把两条流的 RTP 时间戳对齐到同一个墙钟。**

#### RTSP

```
C（客户端）              S（服务端）
  │ OPTIONS                │
  │───────────────────────>│  查询支持的方法
  │ DESCRIBE               │
  │───────────────────────>│  获取 SDP（媒体描述）
  │<───────────────────────│  SDP: 有哪些流、编码、地址
  │ SETUP (track 1)        │
  │───────────────────────>│  建立 RTP 会话（协商端口/传输方式）
  │<───────────────────────│  Session ID
  │ SETUP (track 2)        │
  │ PLAY                   │
  │───────────────────────>│  开始播放
  │<═══════════════════════│  ═══ RTP 流开始 ═══
  │ PAUSE / TEARDOWN       │
  │───────────────────────>│  暂停 / 结束
```

**关键点**：

- **RTSP 是控制平面，RTP 是数据平面**。这是理解 RTSP 的第一句话
- `SETUP` 时可以选择 **RTP over UDP**（低延迟）或 **RTP over TCP**（穿透 NAT/防火墙，但会队头阻塞）
- **SDP（Session Description Protocol）** 描述"有哪些媒体、用什么编码、RTP 端口是多少"

**Android 上的实现**：AOSP 有 `NuPlayerRTSPSource`，但功能有限。**生产级通常用 FFmpeg / ExoPlayer 的 RTSP 扩展 / GStreamer**。

#### SRT（Secure Reliable Transport）

**核心是"在 UDP 上做可靠传输 + 低延迟"**：

| 机制              | 说明                                        |
| --------------- | ----------------------------------------- |
| **ARQ（自动重传请求）** | 接收方检测到丢包 → 请求重传（有延迟预算，超时则放弃）              |
| **延迟缓冲区**       | 用一个可配置的"latency buffer"（如 120ms）抵消抖动与重传时间 |
| **时间戳与序号**      | 类似 RTP                                    |
| **加密**          | AES 加密（"Secure"）                          |
| **握手与流控**       | 双向握手协商参数（类似 TCP 的握手但更轻）                   |

**SRT vs RTMP**：RTMP 在丢包网络上会累积延迟（TCP 重传导致队列堆积），SRT 用**有界重传**（超时放弃）保证"延迟不增长"。**所以远程直播/车载视频回传倾向 SRT。**

#### WebRTC

**四层能力**：

| 层          | 内容                                                                             |
| ---------- | ------------------------------------------------------------------------------ |
| **采集与编解码** | 摄像头/麦克风采集，VP8/VP9/H.264/AV1 编解码，Opus 音频                                        |
| **传输**     | **SRTP**（加密的 RTP）+ **ICE**（NAT 穿透）+ **DTLS**（密钥协商）                             |
| **质量保障**   | **NACK**（丢包重传）、**FEC**（前向纠错）、**PLI/FIR**（请求关键帧）、**GCC 拥塞控制**、**Jitter Buffer** |
| **控制**     | SDP 协商、ICE Candidate 交换、信令（需自建）                                                |

**WebRTC 的核心价值是"自适应"**：根据网络状况动态调整码率、分辨率、帧率，并主动请求关键帧来快速恢复。**这是它比"裸 RTP"强的地方。**

**在 Android 上的应用**：`org.webrtc`（官方库）、`libwebrtc`（native）。车机上用于**远程监控/远程诊断（把车内画面回传给云端）**。

### 【常见追问】

**Q：为什么实时音视频用 UDP 不用 TCP？**  
因为 TCP 的**队头阻塞（HOL blocking）**：一个包丢了，后续所有包都要等它重传，延迟会持续累积。实时场景下"宁可丢一帧也不要延迟增长"。**所以 WebRTC 用 UDP + 有界重传（NACK）+ FEC。** 这是最重要的一道对比题。

**Q：SRT 和 QUIC 像吗？**  
概念上很像（都是"在 UDP 上做可靠传输 + 低延迟"），但目标不同：QUIC 是通用传输（HTTP/3 的底座），SRT 是为**单向实时媒体流**优化的（有 latency buffer 的概念，不追求完全可靠）。**能说出相似性与差异，说明你对传输层有整体理解。**

**Q：RTP 的时间戳是绝对时间吗？**  
不是。RTP 时间戳是**相对起始时刻的、以采样率为单位的计数**（例如视频用 90000Hz 时钟）。要转成绝对时间需要 RTCP SR 里的 NTP 时间戳做锚点。

### 【延伸方向】

- RFC：3550（RTP/RTCP）、2326（RTSP）、6184（H.264 over RTP）、3551（SRTP）
- SRT：Haivision 的 SRT Alliance 规范
- WebRTC：`webrtc.googlesource.com`、`org.webrtc` 的 Android 示例
- 工具：`ffmpeg`（可做 RTSP 拉流、SRT 推流）、`Wireshark`（RTP 分析）、`srt-live-transmit`

---

## 4.6 FFmpeg / ExoPlayer / GStreamer

### 【JD 出处】

- 软通动力：*"熟悉常用的多媒体开发框架之一或有相关开发经验者优先（ExoPlayer / Ffmpeg / Gstreamer 等）"*
- 紫光展锐：*"熟悉 gstreamer、ffmpeg、vlc 等开源多媒体框架者加分"*
- 汉桑科技：*"熟悉 C++ 开发，有 FFmpeg、OpenGL、MediaCodec 等底层音视频处理经验者优先"*

### 【原理】

#### 三者的定位差异（面试第一题）

| 框架            | 层次              | 语言          | 强项                             | 弱项                       |
| ------------- | --------------- | ----------- | ------------------------------ | ------------------------ |
| **FFmpeg**    | 编解码与格式转换库       | C           | **格式支持最全**（几乎所有容器与编码）；转码强      | 不适合做播放器 UI/渲染            |
| **ExoPlayer** | 播放器框架           | Java/Kotlin | 播放类需求开发快；可扩展；自适应流（DASH/HLS）    | 依赖 MediaCodec（**不解码能力**） |
| **GStreamer** | 多媒体框架（pipeline） | C           | **pipeline 模型极灵活**；插件化；可做复杂处理链 | 学习曲线陡；Android 集成麻烦       |

**一句话总结**：

- 需要**转码、封装、格式转换** → FFmpeg
- 需要**做一个播放器** → ExoPlayer
- 需要**搭复杂的媒体处理流水线**（采集 → 处理 → 编码 → 推流） → GStreamer

#### FFmpeg 的核心结构

```
libavformat   → 容器/协议（demux/mux，支持 MP4/TS/RTSP/RTMP/SRT...）
libavcodec    → 编解码器（H.264/H.265/AAC/...，含软解软编）
libavfilter   → 滤镜（缩放、裁剪、叠加、转场...）
libavutil     → 工具（内存、时间、数学）
libswscale    → 图像缩放与色彩空间转换（YUV ↔ RGB）
libswresample → 音频重采样与格式转换
```

**Android 上集成 FFmpeg 的三个要点**：

| 要点       | 说明                                                                                                                                 |
| -------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| **编译**   | 用 `NDK` 交叉编译，产出各 ABI 的 `.so`。**配置选项决定体积**（`--disable-everything --enable-decoder=h264` 能省几十 MB）                                    |
| **体积控制** | 全量 FFmpeg 的 `libavcodec.so` 可达 20–40MB/ABI。**必须裁剪**                                                                                |
| **硬解码**  | FFmpeg 的软解在车机上不可接受（4K 必卡）。需要用 **`MediaCodec` 做硬解**，FFmpeg 只做 demux → 这就是"FFmpeg + MediaCodec"的组合方案（`libstagefright` 的 FFmpeg 硬解封装） |

#### ExoPlayer 的架构（重点，因为车机最可能用）

```
┌─────────────────────────────────────────────────┐
│ Player（对外接口）                                │
│   ExoPlayerImpl → ExoPlayerImplInternal（核心）   │
├─────────────────────────────────────────────────┤
│ MediaSource（媒体来源）                           │
│   ProgressiveMediaSource（本地/HTTP）             │
│   HlsMediaSource / DashMediaSource / SsMediaSource│
│   MergingMediaSource（合并音视频/字幕）            │
│   ConcatenatingMediaSource（播放列表）             │
│   ClippingMediaSource（裁剪）                      │
├─────────────────────────────────────────────────┤
│ Extractor（解封装）→ 继承自 MediaExtractor 概念    │
│   Mp4Extractor / TsExtractor / FlvExtractor ...   │
├─────────────────────────────────────────────────┤
│ Renderer（渲染器）                                │
│   MediaCodecVideoRenderer（视频）                  │
│   MediaCodecAudioRenderer（音频）                  │
│   TextRenderer（字幕）                             │
├─────────────────────────────────────────────────┤
│ TrackSelector（轨道选择：选哪条视频/音轨/清晰度）   │
│ LoadControl（加载控制：缓冲策略）                  │
│ MediaClock（时钟：音视频同步基准）                 │
│ AudioSink / VideoSink（输出）                      │
└─────────────────────────────────────────────────┘
```

**ExoPlayer 最值得掌握的三点**：

1. **`MediaSource` 可自定义** → 可以接自己的数据源（车机的本地媒体库、USB、DLNA）
2. **`TrackSelector` 可定制** → 多音轨场景（车机播放多语言视频）必须定制
3. **`AudioSink` / `RenderersFactory` 可注入** → 可以接入 `CarAudioManager` 的多 zone 路由，**这是车机 ExoPlayer 集成的关键定制点**

**车机定制 ExoPlayer 的典型需求**：

```kotlin
// 1. 让音频走车机的音区路由
class CarAudioSink : DefaultAudioSink(...) { /* 用 CarAudioManager 的路由 */ }

// 2. 自定义 RenderersFactory
class CarRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(...) = CarAudioSink(...)
}

// 3. 提供自定义 MediaSource（本地媒体库）
class UsbMediaSource : BaseMediaSource() { /* 遍历 USB 目录 */ }
```

#### GStreamer 的 pipeline 模型

```bash
# 命令行示例：把摄像头画面 H.264 编码后推到 RTSP
gst-launch-1.0 -v \
  v4l2src ! video/x-raw,width=1920,height=1080 ! \
  v4l2h264enc ! h264parse ! rtph264pay ! \
  udpsink host=192.168.1.100 port=5000
```

```
逻辑上是：source → filter/codec → sink 的元素链（element pipeline）
每个 element 有 src pad 与 sink pad，通过 pad 连接
用 GstBus 传递消息（EOS、ERROR、STATE_CHANGED）
```

**GStreamer 的车载应用场景**：**环视/行车记录仪的拼接处理链**（4 路摄像头 → 畸变校正 → 拼接 → 编码 → 存储/显示），因为这类"多源 → 处理 → 输出"的流水线用 GStreamer 表达最自然。

### 【常见追问】

**Q：Android 上为什么不能只用 FFmpeg 解码？**  
因为 ① 软解性能不够（4K 一定卡）；② 软解耗电巨大；③ 会用 CPU 与业务线程抢资源。**所以 Android 上的正确姿势是"FFmpeg 做 demux/格式处理 + MediaCodec 做硬解码"**。

**Q：ExoPlayer 和 MediaPlayer 在"低延迟"上的差异？**  
ExoPlayer 的 Java 层抽象（`MediaSource` → `Renderer` 的多级流水）会引入一定延迟，主要来自 **`LoadControl` 的缓冲策略**（默认会缓冲较多）。低延迟场景要：① 用 `DefaultLoadControl` 的小缓冲配置；② 用 `setLiveConfiguration`；③ 极端情况直接用 `MediaCodec` 绕开 ExoPlayer。**车机的倒车影像多数不用 ExoPlayer，就是这个原因。**

### 【延伸方向】

- FFmpeg：官方 wiki 的 "CompilationGuide/Android"、`ffmpeg-kit`（已停止维护但仍有大量项目在用）
- ExoPlayer：`developer.android.com/guide/topics/media/exoplayer`、Media3（ExoPlayer 已并入 Media3）
- GStreamer：官方教程（`gst-docs`）+ `gst-launch-1.0` 实验
- 延伸：`ijkplayer`（Bilibili 开源，FFmpeg + MediaCodec 的组合范例，**读它的代码能学到"FFmpeg 硬解集成"的完整套路**）

---

## 4.7 OpenGL ES / Vulkan / Skia / Shader

### 【JD 出处】

- 小米南京·Android 图形渲染工程师：*"深入研究图形渲染技术 Android 图形架构，包括 OpenGL ES、Vulkan、Skia 等；有计算机图形学经验或者熟悉 Skia、OpenGL、Vulkan、Shader 者优先；熟悉 Unity、UE、Cocos 等开发者优先"*
- 软通动力：*"熟悉 OpenGL 基本原理，了解滤镜、转场等各后处理特效的基本原理"*

### 【原理】

#### OpenGL ES 的核心概念

```
① 上下文（Context）与 Surface
   EGLDisplay / EGLContext / EGLSurface
   EGL 是 OpenGL ES 与窗口系统（Android 的 Surface）之间的桥

② 渲染管线（Pipeline）
   顶点数据 → 顶点着色器 → 图元装配 → 光栅化 → 片元着色器 → 逐片元操作 → 帧缓冲

③ Shader（GLSL）
   顶点着色器（vertex）：决定"顶点在哪"
   片元着色器（fragment）：决定"每个像素是什么颜色"

④ 数据传递
   VBO（顶点缓冲对象）/ VAO（顶点数组对象）
   纹理（Texture）：uniform sampler2D
   uniform（每次绘制不变的参数）/ attribute（逐顶点）/ varying（顶点→片元）
```

**最小可运行示例（Android，用 `GLSurfaceView`）**：

```java
public class MyRenderer implements GLSurfaceView.Renderer {
    private int program;

    // 顶点着色器
    private static final String VERTEX_SHADER =
        "attribute vec4 aPos;" +
        "attribute vec2 aTexCoord;" +
        "uniform mat4 uMvpMatrix;" +
        "varying vec2 vTexCoord;" +
        "void main() {" +
        "  gl_Position = uMvpMatrix * aPos;" +
        "  vTexCoord = aTexCoord;" +
        "}";

    // 片元着色器：一个简单的"复古"滤镜
    private static final String FRAGMENT_SHADER =
        "precision mediump float;" +
        "uniform sampler2D uTexture;" +
        "varying vec2 vTexCoord;" +
        "void main() {" +
        "  vec4 color = texture2D(uTexture, vTexCoord);" +
        "  float gray = dot(color.rgb, vec3(0.299, 0.587, 0.114));" +
        "  gl_FragColor = vec4(vec3(gray), color.a);" +
        "}";

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        program = loadProgram(VERTEX_SHADER, FRAGMENT_SHADER);   // 编译 + 链接
        GLES20.glClearColor(0f, 0f, 0f, 1f);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);
        // ... 绑定 VBO / 纹理 / uniform，glDrawArrays
    }
}
```

**这段 20 行代码覆盖了 GL 的完整套路**：着色器编译链接 → 传 uniform/attribute → 绑定纹理 → 绘制。

#### OpenGL ES vs Vulkan（面试常考对比）

| 维度           | OpenGL ES                 | Vulkan                                  |
| ------------ | ------------------------- | --------------------------------------- |
| 抽象层级         | 高层（驱动帮你管状态）               | **低层（你显式管一切）**                          |
| 状态管理         | 全局状态机（易错，难并行）             | **显式管线状态对象（PSO）**                       |
| 多线程          | 差（上下文是单线程的）               | **好（命令缓冲可并行录制）**                        |
| 驱动开销         | 高（驱动做大量校验）                | **低**                                   |
| 显存管理         | 驱动管                       | **应用管**（分配、绑定、释放）                       |
| 学习曲线         | 平缓                        | **陡峭**                                  |
| Android 上的使用 | `GLSurfaceView` / `EGL14` | `ANativeWindow` + `VkSwapchainKHR`（NDK） |

**为什么 Android 转向 Vulkan**：因为 GL 的全局状态与驱动开销在多核 CPU 上成了瓶颈。Vulkan 把控制权交给应用，代价是复杂度。**面试如果被问"Vulkan 为什么快"，答案就是"移除驱动的隐式同步与校验，让应用显式控制"。**

**车机的选择**：中低端 SoC 的 Vulkan 驱动质量参差，**所以车机上 OpenGL ES 仍是主流**；高端座舱（高通 8155/8295 级别）开始用 Vulkan（因为 UX 越来越复杂，需要更好的多线程与更低开销）。

#### Skia 与 Shader

**Skia**：Android 的 2D 图形引擎（Chrome、Flutter 也用）。它是 `Canvas` API 的底层实现。

```java
// Skia 的 Java 接口就是 Canvas
// Skia 的 native 接口（NDK）
// sk_canvas.h, sk_paint.h, ...
```

**Skia 在 Android 图形栈的位置**（串起 2.8）：

```
View.draw(Canvas) → RenderNode（display list）
                     → RenderThread
                         → Skia（SkCanvas / SkSurface）
                             → Ganesh（GL/Vulkan 后端）
                                 → GPU
```

**在小米的"座舱独立渲染引擎"岗位上，Skia 层的定制是核心工作**：因为要在 `SurfaceFlinger` 之外自己控制渲染路径（比如为了做更炫的桌面车模、转场动效）。

**Shader 在动效中的用法**：

| 效果        | Shader 手段          |
| --------- | ------------------ |
| 模糊        | 高斯模糊（多次采样）         |
| 转场（溶解/擦除） | 用进度 uniform 混合两张纹理 |
| 波纹/扭曲     | 用噪声纹理扰动 UV 坐标      |
| 高光/扫光     | 时间 uniform + 渐变函数  |
| 色彩调整      | 颜色矩阵运算             |

```glsl
// 转场效果的片元着色器骨架
uniform sampler2D uTextureFrom;
uniform sampler2D uTextureTo;
uniform float uProgress;      // 0 → 1
varying vec2 vTexCoord;

void main() {
    vec4 from = texture2D(uTextureFrom, vTexCoord);
    vec4 to   = texture2D(uTextureTo,   vTexCoord);
    float noise = texture2D(uNoise, vTexCoord).r;
    float t = smoothstep(noise - 0.1, noise + 0.1, uProgress);
    gl_FragColor = mix(from, to, t);
}
```

**这段代码是"转场动效"的通用骨架** —— 用一张噪声纹理 + `smoothstep` 做阈值过渡。**能写出这个，面试官会认为你真的做过图形。**

#### `RenderEffect`（Android 12+，应用层的 Shader 能力）

```kotlin
// 不需要写 GL 代码就能加滤镜
imageView.setRenderEffect(
    RenderEffect.createBlurEffect(25f, 25f, Shader.TileMode.CLAMP)
)
// 也可自定义
RenderEffect.createRuntimeShaderEffect(runtimeShader, "uniformName")  // Android 13+
```

**`RuntimeShader`（AGSL，Android 13+）** 允许用类 GLSL 的语言在应用层写 Shader，**不需要 OpenGL/Vulkan**。**这是"车机 HMI 动效"最实用的新能力**（因为很多动效不需要真的上 GL 代码，用 `RenderEffect` + `AGSL` 就够了，且不会与 `SurfaceFlinger` 打架）。

### 【常见追问】

**Q：`GLSurfaceView` 和 `SurfaceView` 的区别？**  
`GLSurfaceView` 继承自 `SurfaceView`，额外做了三件事：① 管理 EGL 上下文与渲染线程（`GLThread`）；② 提供 `Renderer` 回调；③ 处理 Surface 创建/销毁与上下文的绑定。**所以它是"SurfaceView + GL 的脚手架"。**

**Q：`TextureView` 为什么比 `SurfaceView` 耗电？**  
因为 `SurfaceView` 有独立图层，`SurfaceFlinger` 用硬件合成（几乎零拷贝）；而 `TextureView` 的内容要作为纹理先渲染到 View 树的一个图层，**多一次 GPU 采样与合成**。**这是"车机上视频一律用 SurfaceView"的性能原因。**

**Q：为什么 GL 的纹理上传（`glTexImage2D`）是性能瓶颈？**  
因为它涉及 CPU → GPU 的数据拷贝（通常每帧几 MB）。优化手段：① 用 `SurfaceTexture`（零拷贝，`updateTexImage`）；② 用 PBO（`glMapBufferRange`）；③ 用硬件缓冲区（`AHardwareBuffer`，Android 8+，可跨进程零拷贝共享）。**`AHardwareBuffer` 是现代 Android 图形的重要能力，能提它说明你跟得上。**

### 【延伸方向】

- 书籍：《OpenGL ES 3.0 编程指南》、《Real-Time Rendering》
- 官方：Android 的 "Graphics architecture" 文档、`androidx.graphics`（`BitmapShader`、`RenderEffect`）
- NDK：`EGL` / `GLES3` / `vulkan` 头文件与示例（`ndk-samples`）
- 延伸：`AGSL`（Android Graphics Shading Language，Android 13+）
- 延伸：`AHardwareBuffer` 与 `ImageReader`（零拷贝的图像流转）

---

# 第五部分：跨端与鸿蒙

## 5.1 React Native 原理与容器优化

### 【JD 出处】

- 中瑞集团：*"优化 Android ReactNative 容器加载性能与稳定性，降低 App 崩溃率；具备跨端开发经验，熟悉 uniapp、Flutter、ReactNative 等技术者优先"*
- 浩鲸云计算：*"熟练使用 React Native 跨端框架开发的优先"*

> 中瑞那条 JD 的要求不是"会用 RN"，而是"**优化 RN 容器的加载性能与稳定性**"。这是两个价格。

### 【原理】

#### 旧架构：Bridge（异步 JSON 消息）

```
┌──────────────────────────────────────────────────────┐
│ JS 线程（Hermes / JSC）                               │
│   你的 React 代码                                     │
└──────────────┬───────────────────────────────────────┘
               │ ① 序列化成 JSON 字符串
               │    （异步、批量、通过 MessageQueue）
┌──────────────▼───────────────────────────────────────┐
│ C++ Bridge（batchedBridge）                           │
│   MessageQueue + 序列化/反序列化                       │
└──────────────┬───────────────────────────────────────┘
               │ ② 反序列化
┌──────────────▼───────────────────────────────────────┐
│ Native 模块线程（NativeModules）                       │
│   相机 / 定位 / 存储 / 蓝牙...                         │
└──────────────┬───────────────────────────────────────┘
               │ ③ UI 操作
┌──────────────▼───────────────────────────────────────┐
│ UI 线程（Main）：Shadow Tree → View 树               │
│   最终变成 Android 的原生 View                        │
└──────────────────────────────────────────────────────┘
```

**旧架构的三大瓶颈**：

| 瓶颈             | 原因                                   |
| -------------- | ------------------------------------ |
| **JSON 序列化开销** | 每次通信都要序列化/反序列化，大数据（如长列表数据）成本极高       |
| **异步 + 批量**    | 一个动画需要每帧跨三次线程 → 掉帧（"RN 动画都不流畅"的印象来源） |
| **无法同步**       | 某些场景需要同步拿原生值（如测量布局），Bridge 做不到       |

#### 新架构：JSI + Fabric + TurboModules

| 组件                            | 取代               | 机制                                             |
| ----------------------------- | ---------------- | ---------------------------------------------- |
| **JSI**（JavaScript Interface） | Bridge           | **C++ 抽象层，JS 引擎可直接持有 C++ 对象的引用并直接调用**（不经序列化）   |
| **TurboModules**              | 旧的 NativeModules | 按需懒加载原生模块（旧架构启动时要初始化所有模块）                      |
| **Fabric**                    | 旧渲染器（UIManager）  | **C++ 渲染管线**，Shadow Tree 用 C++ 实现，可同步访问，支持并发渲染 |
| **Codegen**                   | 手写桥接代码           | 从 TS 类型定义生成 C++/Java/ObjC 桥接代码                 |
| **Hermes**                    | JSC              | 为 RN 优化的 JS 引擎（预编译字节码，启动更快、内存更省）               |

**JSI 的价值一句话**：**旧架构是"两个语言之间传消息"，新架构是"两个语言共享对象"。** 这让跨端通信从"序列化 + 异步"变成"直接调用"，是数量级的改善。

#### 在 Android 上的具体体现

```
so 文件：libreactnativejni.so、libhermes.so、libjsi.so、libfabricjni.so
Java 侧：ReactRootView、ReactInstanceManager、ReactContext、CatalystInstance
```

### 5.1.1 容器优化（中瑞 JD 的核心）

| 优化方向     | 手段                                                                                                                                                                                            | 效果                      |
| -------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------- |
| **启动速度** | ① **预热 `ReactInstanceManager`**（App 启动时提前创建，页面打开时复用）；② **预加载 Bundle**（把 JS Bundle 提前读进内存/预解析）；③ **Hermes 字节码**（避免运行时解析 JS 源码）；④ **多实例池**（多个 RN 页面复用 instance）；⑤ **`SoLoader` 优化**（so 加载是启动大头） | 首次打开从 1–2s 降到 200–500ms |
| **内存**   | ① **多 Bundle 拆分 / 按需加载**（避免一次性加载整个 JS Bundle）；② **`ReactInstanceManager.destroy()`** 的时机与彻底性（不彻底会泄漏）；③ **图片缓存策略**（RN 的 `Image` 默认缓存行为需定制）                                                     | 显著                      |
| **稳定性**  | ① **JS 异常捕获**（`ErrorUtils.setGlobalHandler`）；② **RN 侧的 `unstable_enableLogBox` 关闭**（开发工具不上线）；③ **原生崩溃的隔离**（RN 崩溃不应拖垮整个 App）；④ **`ReactNativeHost` 的 `getUseDeveloperSupport` 必须为 false**      | 崩溃率下降                   |
| **列表性能** | **`FlatList`**（不是 `ScrollView` + `map`）+ `keyExtractor` + `getItemLayout` + `removeClippedSubviews` + 分页                                                                                      | 长列表从卡顿到流畅               |
| **动画**   | 用 **`Animated` + `useNativeDriver: true`**（把动画驱动交给原生，不经过 Bridge）；或 `react-native-reanimated`（跑在 UI 线程）                                                                                        | 动画不掉帧                   |
| **图片**   | `react-native-fast-image`（原生图片库）；`resizeMode` 与尺寸预知                                                                                                                                           | 内存与大图问题                 |

**"RN 容器启动慢"的排查顺序**（这是面试可用的框架）：

```
① 是 Bundle 加载慢？  → 看 JS Bundle 大小、是否用了 Hermes 字节码
② 是 instance 创建慢？ → 是否预热、是否复用了 instance
③ 是首屏渲染慢？      → 看 Shadow Tree 构建、是否一次性渲染了长列表
④ 是 so 加载慢？      → 看 SoLoader 日志、是否精简了 so
```

**车机上为什么会用 RN**：因为车机 UI 需要跨车型复用、快速迭代（云 UI 思路）。**但 RN 在车机上的风险更高**（内存与稳定性要求更严），所以"容器优化"能力在车机岗上值钱。

### 【常见追问】

**Q：RN 和 Flutter 的本质区别？**  
**RN 在原生控件上"画"（映射到真实 View），Flutter 在自己画的画布上画（不问原生控件）**。

- RN：`<View>` → `android.view.View`；好处是原生体验一致（原生控件的行为/无障碍/输入法都对）；坏处是跨平台差异与 Bridge 开销
- Flutter：整个界面是一张 `FlutterView` 里的 Skia/Impeller 画布；好处是跨平台**像素级一致**、性能可预测；坏处是"看起来不像原生"、包体大

**Q：`useNativeDriver` 能解决所有动画问题吗？**  
不能。它只支持 `transform` 和 `opacity` 两类属性（因为这两类可以不走 layout，直接在原生层做）。**布局类属性（`width`、`height`、`top`）无法用 native driver** → 只能走 Bridge → 掉帧。**所以 RN 里做动画必须尽量用 `transform` / `opacity`。**

**Q：RN 热更新在车机上能用吗？**  
技术可行（CodePush / 自建），但**车机上极其谨慎**：① 车规与合规要求（代码变更需走 OTA 流程）；② 安全（动态下发代码是攻击面）；③ 一致性（同一车型的体验必须一致）。**结论：车机上"热更新"通常被 OTA 取代。**

### 【延伸方向】

- 官方：`reactnative.dev/docs/architecture-overview`（新架构说明）、`reactnative.dev/docs/the-new-architecture`
- Blog：Callstack 的 "React Native's New Architecture" 系列
- 延伸：Hermes 引擎（字节码格式、启动优化）
- 延伸：`react-native-reanimated` 的工作原理（Worklet + UI 线程）

---

## 5.2 Flutter 原理

### 【JD 出处】

- 汉桑科技：*"熟悉以下技术栈之一：Flutter 开发（Dart）"*
- 中瑞集团：*"具备跨端开发经验，熟悉 uniapp、Flutter、ReactNative 等技术者优先"*
- 应届/初级岗中 Flutter 也常出现

### 【原理】

#### 自绘模型：三棵树

```
Widget 树（不可变的配置描述）
   │  build()
   ▼
Element 树（可变的、有生命周期的实例）
   │  createRenderObject()
   ▼
RenderObject 树（负责布局与绘制）
   │  paint()
   ▼
Layer 树 → Scene → dart:ui → Skia / Impeller → GPU
```

| 树                | 特点                                                               |
| ---------------- | ---------------------------------------------------------------- |
| **Widget**       | **不可变**（每帧重建，非常轻量，就是配置数据）                                        |
| **Element**      | 可变，持有 `State`，负责 diff（`updateChild`）与生命周期（`initState`/`dispose`） |
| **RenderObject** | 真正做 `layout()` 与 `paint()`，是性能的核心                                |

**关键理解**：**"Widget 重建"不等于"RenderObject 重建"**。Flutter 的 diff（`canUpdate` 比较 `runtimeType` + `key`）决定是否复用 `Element` 与 `RenderObject`。**所以 Flutter 的性能优化的核心是"让 dirty 的子树尽量小"**（`const` 构造、`RepaintBoundary`、`ValueListenableBuilder` 局部刷新）。

#### Dart 与渲染引擎

| 技术                                                | 说明                                                                                          |
| ------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| **Dart**                                          | 语言；发布版用 **AOT 编译为 ARM 机器码**（不是解释执行），调试版用 JIT（支持热重载）                                         |
| **`dart:ui`**                                     | Dart 与引擎（C++）的边界，提供 `Canvas`、`Picture`、`Scene`                                              |
| **Skia**（历史）/ **Impeller**（新，默认在 iOS 与部分 Android） | 图形后端。Impeller 预编译着色器，解决 Skia 的"首次运行着色器编译导致卡顿"问题                                             |
| **Platform Channel**                              | Dart 与原生通信（`MethodChannel` / `EventChannel`，底层是 `BinaryMessenger` + `StandardMessageCodec`） |

**Platform Channel 的性能特性**：它是**异步 + 序列化**的（类似 RN 的旧 Bridge），所以"每帧都过 Channel"会掉帧。**解法：① 用 `Texture` / `PlatformView` 但谨慎（`PlatformView` 的开销很大）；② 用 FFI（Dart 直接调用 C，**&#x4E0D;经 Channe&#x6C;**，这是现在的推荐方式）。**

#### Flutter vs RN vs 原生（面试对比题）

| 维度      | 原生         | Flutter               | RN              |
| ------- | ---------- | --------------------- | --------------- |
| 渲染      | 原生 View    | **自绘（Skia/Impeller）** | 映射原生 View       |
| 跨平台一致性  | —          | **像素级**               | 有差异             |
| 性能上限    | 最高         | 高（自绘可预测）              | 中（Bridge 开销）    |
| 包体      | 最小         | **大**（引擎约 5–10MB/ABI） | 中               |
| 与原生混合   | —          | 中（`PlatformView` 有开销） | **好**（本来就是原生控件） |
| 无障碍/输入法 | 最好         | 需适配                   | 好               |
| 适合      | 系统 UI、性能敏感 | 跨平台统一 UI              | 动态化、快速迭代        |

**车机上的取舍**：

- 车机上"跨平台"的收益主要是**跨车型/跨 SoC 平台复用**（Android 与 QNX/Linux 并存时更有价值）
- 但车机对"内存、启动时间、无障碍、输入法（车内语音输入）"要求高 → **Flutter 在这些方面是短板**
- 结论：**车机主力仍是原生 + H5**；Flutter 更多用于"跨座舱平台复用"或"非关键 UI"

### 【延伸方向】

- 官方：`docs.flutter.dev/resources/architectural-overview`
- 延伸：Impeller 的着色器预编译、`RepaintBoundary` 的性能作用
- 延伸：`dart:ffi`（免 Channel 的原生调用）

---

## 5.3 HarmonyOS：ArkTS / Stage 模型 / 分布式

### 【JD 出处】

- 软通动力（鸿蒙）：*"基于鸿蒙 OS 系统特性，使用 ArkTS/JS/TS 等技术栈开发前端应用；掌握 ArkTS/JS/TS 编程语言及鸿蒙 UI 组件、状态管理等核心技术；了解鸿蒙与其他系统（如 Android、iOS）的适配差异"*
- 鱼跃医疗：*"熟悉 HarmonyOS 开发框架，了解 Stage 模型、Ability、UIAbility 生命周期；有 Android 原生开发经验，能快速迁移或改造已有应用"*
- 深圳易宝：*"熟悉 Java/Kotlin/ArkTS 语言；鸿蒙特有能力的实现，如小艺助手、锁屏卡片、一镜到底等功能模块"*

### 【原理】

#### 与 Android 的对应关系（这是理解鸿蒙最快的路径）

| Android                | HarmonyOS                                                                                              | 差异要点                                           |
| ---------------------- | ------------------------------------------------------------------------------------------------------ | ---------------------------------------------- |
| `Activity` / `Service` | **`UIAbility`**（带 UI）/ **`ExtensionAbility`**（无 UI，如 `ServiceExtensionAbility`、`FormExtensionAbility`） | 一个 `UIAbility` 可以有**多个 `WindowStage`**（多窗口更原生） |
| `Context`              | `UIAbilityContext` / `ApplicationContext`                                                              | 分层更细                                           |
| 四大组件                   | `UIAbility` + `ExtensionAbility` 家族 + `FormExtensionAbility`（卡片）                                       | 卡片是**一等公民**                                    |
| `AndroidManifest.xml`  | **`module.json5`** + `app.json5`                                                                       | JSON5 格式，模块化更强                                 |
| XML 布局                 | **ArkUI 声明式**（ArkTS 写 UI）                                                                              | 无 XML 布局                                       |
| `AIDL` + Binder        | **`IPC` / `RPC`**（`@ohos.rpc`）                                                                         | 接口用 IDL 描述                                     |
| `JNI` / NDK            | **`ArkTS NAPI`**                                                                                       | 类似的 C 接口层                                      |
| APK                    | **HAP**（应用包）/ **HSP**（共享包）/ **HAR**（静态库）                                                               | 拆得更细                                           |
| `View` 体系              | **ArkUI 组件（声明式）**                                                                                      | 类似 Compose                                     |

#### ArkTS

**ArkTS 是 TypeScript 的"强化版"**：

| 强化                               | 目的              |
| -------------------------------- | --------------- |
| **禁用 `any` / `unknown`**（严格类型）   | 运行时性能（可 AOT 优化） |
| **禁用结构类型**（必须显式接口）               | 同上              |
| **限制动态属性**（不能随意 `obj.xxx = ...`） | 同上              |
| **强制 `@Observed` / 状态装饰器**       | 让状态管理可静态分析      |

**状态管理 V2（新版）**：

```
V1：@State / @Prop / @Link / @Observed / @ObjectLink / @Provide / @Consume
V2：@ComponentV2 / @Local / @Param / @Once / @Event / @Provider / @Consumer
```

**为什么 V2 存在**：V1 的 `@Observed` + `@ObjectLink` 对嵌套对象支持很差（深层属性变化不触发刷新）。V2 用更细粒度的观察机制解决。**这是鸿蒙 UI 面试的高频点。**

#### Stage 模型与生命周期

```
应用启动
  └─ AbilityStage.onCreate()             ← 模块级（每个 HAP 一个）
      └─ UIAbility.onCreate(want, launchParam)
          ├─ 加载页面（windowStage.loadContent(...)）
          └─ UIAbility.onWindowStageCreate(windowStage)   ← 可以在这里设置 UI
              ├─ onForeground()          ← 切到前台
              ├─ onBackground()          ← 切到后台
              └─ onWindowStageDestroy()
                  └─ UIAbility.onDestroy()
```

**`WindowStage` 是关键差异**：Android 的 `Activity` 与"窗口"基本一一对应；鸿蒙的 `UIAbility` 可以持有 `WindowStage`，在同一个 `UIAbility` 里管理多个窗口（**这对车机的多屏场景天然友好**）。

#### 分布式能力

| 能力                             | 说明               | 车机的相关性         |
| ------------------------------ | ---------------- | -------------- |
| **分布式软总线**                     | 设备间自发现、自组网、免配网传输 | 手机 ↔ 车机互联      |
| **`DistributedDataObject`**    | 跨设备数据对象同步        | 手机与车机共享导航目的地   |
| **`distributedDeviceManager`** | 设备发现与状态查询        | 车机作为"分布式设备"被发现 |
| **跨设备迁移**                      | Ability 从手机迁到车机  | 上车后 App 无缝接续   |
| **超级终端**                       | 多设备组合成一个逻辑设备     | 手机 + 车机 + 手表   |

**车机上的鸿蒙价值**：**"手机应用无缝上车"**（把手机上的 App 迁移到车机继续用），这是鸿蒙座舱的核心卖点之一。**但技术上仍有大量约束**（UI 适配、输入方式差异、安全隔离）。

### 【常见追问】

**Q：有 Android 经验，转鸿蒙要学什么？**  
三样：① **ArkTS 的声明式 UI**（类比 Compose，但语法不同）；② **Stage 模型的 Ability 体系**（类比 Activity/Service 但拆分方式不同）；③ **`module.json5` 的配置模型**。**不用学的**：Binder（换成 IPC/RPC）、`Context` 体系（简化了）、资源系统（类似但更简单）。**结论：有 Compose 经验的人转鸿蒙最快。**

**Q：鸿蒙为什么不用 XML 布局？**  
因为声明式 UI 的可组合性更好（与 Compose 同样的理由），而且 ArkTS 可以在编译期做更激进的优化（严格类型 → 更少的运行时检查）。

**Q：车机的鸿蒙岗位为什么现在不多？**  
两个原因：① **量产上车周期长**（一款车的座舱方案定了就不会中途换 OS）；② **生态迁移成本**（车机的核心能力（CarService、VHAL、总线）在鸿蒙上还没有成熟的对标实现）。**所以当前"鸿蒙座舱"更多是试点与预研，岗位量还没起来。** 对车机人来说，鸿蒙是"储备技能"而不是"主攻方向"。

### 【延伸方向】

- 官方：`developer.huawei.com/consumer/cn/doc/harmonyos-guides/`（ArkTS、Stage 模型、分布式）
- 迁移参考：官方有 "Android 应用迁移到鸿蒙" 的指南
- 延伸：`ArkUI` 的渲染机制（与 Compose 的对比）、`NAPI`
- 延伸：`HarmonyOS NEXT`（纯血鸿蒙）的变化

---

# 第六部分：把知识串成面试叙事

## 6.1 三轮实战叙事模板

### 模板一：ANR 排查（车机场景）

> 这段可以直接套用你自己的案例，按六段式组织：

```
【现象】
某车型在"刚上电后 5 秒内点击空调卡片"时，约 20% 概率出现输入无响应，
约 3 秒后弹出 ANR 或界面自动恢复。

【初步分层】
- 是应用层还是系统层？
  用 `adb shell dumpsys activity anrs` 拿到 ANR 记录，
  看 ANR 类型：是 "Input dispatching timed out"。
  → 说明"输入事件在主线程被阻塞"，属于应用侧或系统服务侧。

【证据采集】
① /data/anr/anr_xxx：dump 了所有进程的栈
② logcat -b crash + events buffer：看 ANR 前后的事件
③ Perfetto 抓 10 秒（包含 sched、binder_driver、am、wm、gfx 轨）

【定位过程】
- 看主线程栈，发现停在：
    at com.example.vehicle.VehicleRepo.getGear(VehicleRepo.kt:88)
    - waiting to lock <0x0a1b2c3d> (a java.lang.Object) held by thread 23
- 顺着 "held by thread 23" 找 thread 23 的栈：
    停在 CarPropertyManager.getProperty(...) → BinderProxy.transactNative
  → 结论：thread 23 在做一个**同步 Binder 调用**，而这个调用卡住了，
    主线程在等它释放锁。
- 再看 Perfetto 的 binder transaction 轨：
    发现该 transaction 耗时 3.2 秒
  → 说明 CarService 或 VHAL 侧卡了。

【根因】
进一步在 logcat 里发现 VHAL 的 mcu 通信日志有超时重试
（上电初期 MCU 还没就绪，属性查询会阻塞重试）。
- 上电初期的 getProperty 会阻塞 3 秒以上，是 MCU 侧行为。

【修复与验证】
① 应用侧：把上电初期的属性读取改为"订阅 + 超时兜底"，
   不再在主线程同步 getProperty；
   给同步调用加 500ms 超时，超时返回 UNAVAILABLE。
② 系统侧（与 MCU 团队协作）：MCU 就绪前对属性查询快速返回 UNAVAILABLE，
   而不是阻塞重试。
验证：20% → 0%，长稳测试 72 小时无复现。

【防再发】
① CI 加 StrictMode 检查（主线程 Binder 调用告警）；
② 在 VehicleRepo 里统一封装"必须走订阅"的约束；
③ 加监控：统计 getProperty 的耗时分布，超阈值告警。
```

**为什么这个叙事有说服力**：① 有量化（20%、3 秒、72 小时）；② 有分层思路；③ 用到了 `held by thread N` 这个高级技巧；④ 有系统侧协作（说明你不是只改自己那块）；⑤ 有防再发机制（对应小米 JD 的"长效机制"）。

### 模板二：车辆属性不更新

```
【现象】
某车型量产前测试发现：熄火再上电后，约 5% 概率出现"挡位显示停在 P 挡"，
实际车辆已挂 D 挡。重启车机后恢复。

【分层】
属性不更新可能是：
 ① HAL → VHAL（属性没上报）
 ② VHAL → CarService（订阅断了）
 ③ CarService → App（回调没到）
 ④ App 侧（收到了但没更新 UI）

【证据采集】
① `dumpsys car_service --hal`：看属性订阅状态
② `dumpsys car_service --property GEAR_SELECTION`：看缓存值与时间戳
③ App 侧日志：记录每次 onErrorEvent 与 onChangeEvent
④ VHAL 侧的 logcat（MCU 通信与属性上报）

【定位过程】
- App 侧日志显示：熄火前有 onErrorEvent(GEAR_SELECTION)，
  之后**再也没有 onChangeEvent**
  → 说明 App 的订阅在某个时刻失效了，且没有恢复
- `dumpsys car_service` 显示 CarService 侧认为"仍有订阅者"
  → 说明是**订阅状态两边不一致**
- 查代码发现：App 在 onErrorEvent 里只打了日志，**没有重新注册**
  → 而 CarService 在某些错误路径下会清掉该订阅者

【根因】
CarService 的 PropertyHalService 在收到 HAL 的 error 事件时，
会对该属性执行一次"清理订阅"，但**不会通知 App 重新注册**。
而 App 的 onErrorEvent 实现只是打日志。
→ 结果：一次 error 事件导致永久失去订阅。

【修复与验证】
① App 侧：onErrorEvent 里启动"重新注册"的退避重试（指数退避，最多 5 次）；
② 同时增加"看门狗"：超过 N 秒没收到该属性的任何事件（对 CONTINUOUS 属性）
   就主动重新注册；
③ 系统侧：CarService 的 error 路径改为"保留订阅 + 上报 error 状态"
   （这样 App 能感知到状态而不是静默失效）。
验证：5% → 0%，并且在长稳测试中主动注入 error 事件验证恢复路径。

【防再发】
① 建立"属性订阅健康度"监控：统计每个属性的最后上报时间；
② 在 App 的 VehicleRepo 层统一实现"订阅自愈"，不允许业务代码自己注册；
③ 把"error 事件后的恢复"写进团队编码规范。
```

**为什么这个叙事特别硬**：因为它是**纯粹的"车机原生问题"** —— 手机开发者提不出这个案例。

### 模板三：系统死机重启

```
【现象】
某车型在高温环境（>40℃）下连续导航 2 小时后，约 10% 概率出现车机黑屏，
10 秒后回到开机动画。

【分层（先定层级）】
adb shell getprop ro.boot.bootreason
  → 得到 "watchdog"   ← 关键线索：不是 kernel panic，是 Android 层 Watchdog 触发

【证据采集】
① bootreason = watchdog → 查 Watchdog 的记录
   → adb shell dumpsys dropbox --print | grep -A200 system_server_watchdog
② 拿到 Watchdog 的 dump：包含所有线程的栈
③ 同时抓 /sys/fs/pstore/（排除内核侧）
④ 看 Perfetto 的长时 trace（如果能复现）

【定位过程】
- Watchdog dump 里，Watchdog 自己的栈显示它在等一个锁：
    Watchdog 线程：Blocked, waiting to lock <0x...> 
- 顺着找锁持有者：system_server 的 main 线程
  → main 线程栈：卡在 SurfaceFlinger 的同步 transaction
- 再看是谁持有：找到 WMS 的一个调用链，卡在
    SurfaceControl.Transaction.apply() 的同步等待
- Perfetto 里看 SurfaceFlinger：合成耗时高达 300ms+（正常 5ms）
  → 说明是**图形侧卡住了**
- 进一步看 SurfaceFlinger 的状态：Layer 数量异常多（80+）
  → 说明有大量图层没有被销毁

【根因】
高温 → SoC 降频 → 图形性能下降 → 某个动画导致的临时图层没有及时销毁
→ 图层泄漏 → 合成耗时上升 → WMS 的同步 transaction 阻塞
→ system_server main 线程被阻塞 → Watchdog 超时 → 重启
（这是一个典型的"性能问题升级为稳定性问题"的链条）

【修复与验证】
① 短期：修复图层泄漏（在动画结束与异常路径上都释放 SurfaceControl）；
② 中期：把 WMS 侧的那处同步 transaction 改为异步（或加超时）；
③ 系统侧：调整 Watchdog 的超时（`ro.hw_timeout_multiplier`）作为兜底；
④ 热设计：与硬件团队评估降频策略。
验证：高温舱内复现测试 100 小时无复现。

【防再发】
① 加监控：统计 SurfaceFlinger 的 Layer 数量与合成耗时，超阈值告警；
② 加测试：高温环境下的长稳测试纳入发布门禁；
③ 在 CI 里加"图层泄漏检测"（长时间运行后 Layer 数是否稳定）。
```

**为什么这个叙事是"降维打击"**：它展示了**跨层定位能力**（从 bootreason → Watchdog → WMS → SurfaceFlinger → 图层泄漏），这是纯应用层候选人完全不具备的。**面试官听到这个基本会认定你是系统层的人。**

### 通用要点

| 要点           | 说明                                             |
| ------------ | ---------------------------------------------- |
| **必须量化**     | 概率、耗时、时长（"约 20%"、"3.2 秒"、"72 小时"）              |
| **必须有分层**    | 展示你不是"照着 logcat 猜"，而是有系统的方法                    |
| **必须有工具**    | DropBox / tombstone / Perfetto / dumpsys 的具体命令 |
| **必须有协作**    | 与 MCU/硬件/系统侧团队协作，说明你的边界感与推动力                   |
| **必须有防再发**   | 这是"工程师"与"救火队员"的分界                              |
| **准备 2–3 个** | 覆盖不同类型（应用层问题 / 系统层问题 / 跨层问题）                   |

## 6.2 45 分钟技术面的问题地图

### 按岗位类型的提问分布

| 岗位                        | 30% 时间                              | 40% 时间                        | 30% 时间          |
| ------------------------- | ----------------------------------- | ----------------------------- | --------------- |
| **车机 FW/HAL（光庭、颖派、华苏）**   | Binder + CarService/VHAL 原理         | AOSP 编译定制 + 稳定性排查             | 量产案例分析 + 架构设计题  |
| **车机应用（小米）**              | Kotlin/协程 + Compose + 架构            | 性能与稳定性（ANR/启动/内存）             | 座舱业务理解 + 协作     |
| **Framework（机器岛、BOSS直聘）** | Binder / AMS / WMS / SurfaceFlinger | 死机重启/ANR/Watchdog 定位          | 源码级定制能力         |
| **音视频（紫光展锐、软通）**          | MediaCodec / 播放链路                   | 音频栈（AudioFlinger/AudioPolicy） | 具体问题（音质/延迟/兼容性） |
| **图形渲染（小米）**              | OpenGL ES / Vulkan / Skia           | 渲染管线与性能                       | 动效实现方案          |
| **通用 App（浩鲸、焦点）**         | Kotlin/协程/Compose                   | 性能优化 + 架构                     | 工程化/组件化         |

### 按你的背景，最可能的三个"深挖点"

**① Binder → CarService 的深度**

```
预期问题链：
  Binder 为什么快？→ 一次拷贝怎么实现？→ AIDL 生成什么代码？
  → oneway 的语义与风险？→ 死亡通知怎么实现？
  → CarService 怎么用它？（linkToDeath / onServiceDisconnected）
  → 你遇到过 Binder 相关的线上问题吗？
```

**准备重点**：2.2 + 1.3。**能讲出"`Binder` 一次性拷贝的实现是接收方预先 mmap"这句话，基本就过关了。**

**② 稳定性排查的方法论**

```
预期问题链：
  讲一个你排查过的 ANR。→ 为什么主线程会被阻塞？
  → trace 里怎么看出锁持有者？→ 如果是 Binder 卡住怎么办？
  → Native crash 怎么符号化？→ Watchdog 触发的重启怎么定位？
  → DropBox 里有哪些 tag 是有用的？
```

**准备重点**：3.8 + 6.1 的三个模板。**这是你最强的区域，必须准备好 2–3 个案例的六段式。**

**③ AOSP 定制与量产**

```
预期问题链：
  你改过 AOSP 的什么模块？→ 编译流程是怎样的？
  → `VNDK violation` 遇到过吗？→ 动态分区是什么？
  → 系统应用和普通应用的区别？→ priv-app 白名单是什么？
  → 量产版本和 userdebug 的差异导致过什么问题？
```

**准备重点**：2.9 + 2.10。**如果没跑通过完整 AOSP 编译，至少要把流程与关键概念讲清，并诚实说明"了解但未独立完成闭环"** —— 深挖三层就露馅的情况在面试里最伤。

### 反问环节（必须准备 3 个）

| 问题                                                | 你想了解什么                  |
| ------------------------------------------------- | ----------------------- |
| *"这个岗位的产品是自研还是给客户做项目？项目周期多长？"*                    | 判断是"产品型"还是"外包型"（决定成长空间） |
| *"团队里 Framework / HAL / 应用的人是怎么分工的？这个岗要独立负责哪一层？"* | 判断技术深度与你的边界感            |
| *"车机的问题在量产阶段多吗？团队有稳定的问题排查流程与工具吗？"*                | 判断团队成熟度（对应"长效机制"）       |
| *"目前团队最头疼的技术问题是什么？"*                              | 判断这活好不好干，也能展示你的问题意识     |

### 最后一条建议

**把你的 9 年车机经验"产品化"**：

| 不要这样说                    | 要这样说                                                                                  |
| ------------------------ | ------------------------------------------------------------------------------------- |
| "我做车机应用的，用过 Pateo 的 SDK" | "我在博泰做车机 9 年，主要负责车机应用层与系统应用的集成，涉及 CarService 属性订阅、混合架构（WebView+JSBridge）、平台签名系统应用的部署" |
| "我们用了 AIDL"              | "我们通过 AIDL 与车机服务通信，我理解这条链从 CarPropertyManager 到 VehicleHal 的完整路径"                     |
| "我改过系统应用"                | "我维护 platform-signed 的 priv-app 应用，处理过 privapp-permissions 白名单、SELinux 策略与实际权限的差异"    |
| "遇到过 ANR"                | "我定位过 XX 个 ANR，其中比较有代表性的是'上电初期同步 Binder 阻塞导致输入超时'，根因在 MCU 通信"                         |

**区别在于"复述做过什么"和"讲清你懂什么"。** 后者才是 12 年经验该有的表达。

---

## 附录：知识块与 JD 词汇的对照索引

| JD 里的词                                                                            | 本文位置      |
| --------------------------------------------------------------------------------- | --------- |
| Car Service / CarService                                                          | 1.1, 1.2  |
| VHAL / VehicleHAL / 属性                                                            | 1.3, 1.4  |
| CAN / CAN FD                                                                      | 1.6       |
| SOMEIP                                                                            | 1.7       |
| DDS                                                                               | 1.8       |
| UDS / DoIP / ISO-TP                                                               | 1.10      |
| AUTOSAR / SWC / RTE / BSW / CDD / MCAL / Vector DaVinci                           | 1.11      |
| 仪表投屏 / 多窗口 / 分屏                                                                   | 1.12, 2.5 |
| Binder / AIDL / IPC                                                               | 2.2       |
| Handler / Looper / 多线程                                                            | 2.3       |
| AMS / ATMS                                                                        | 2.4       |
| WMS / Window Manager / SurfaceControl / Transaction                               | 2.5       |
| PMS / 权限 / 签名                                                                     | 2.6, 2.10 |
| SystemUI / keyguard / Settings                                                    | 2.7       |
| graphic 系统 / hwui / SurfaceFlinger / BufferQueue                                  | 2.8       |
| AOSP 编译 / 裁剪 / 定制 / 系统镜像 / 构建自动化                                                  | 2.9       |
| priv-app / platform 签名 / system uid / SELinux                                     | 2.10      |
| Kotlin / 协程 / 扩展函数                                                                | 3.1       |
| Flow                                                                              | 3.2       |
| Jetpack / Compose / ViewModel / LiveData                                          | 3.3, 3.4  |
| MVVM / MVI / MVP / 组件化 / 模块化 / 插件化                                                | 3.4, 3.5  |
| WebView / H5 / JSBridge / 双向通信                                                    | 3.6       |
| 性能优化 / 启动 / 内存 / 卡顿 / 耗电 / LeakCanary / Profiler / Systrace / Perfetto            | 3.7       |
| ANR / JavaCrash / NativeCrash / Watchdog / 死机重启 / 内存泄漏                            | 3.8       |
| MediaCodec / MediaExtractor / Stagefright / NuPlayer / Demuxer / Decoder / Render | 4.1, 4.2  |
| AudioService / AudioFlinger / AudioPolicy / AudioEffect / SoundTrigger            | 4.3       |
| H.264 / H.265 / AAC / 编解码                                                         | 4.4       |
| RTP / RTCP / RTSP / RTMP / SRT / WebRTC                                           | 4.5       |
| FFmpeg / ExoPlayer / GStreamer / VLC                                              | 4.6       |
| OpenGL / OpenGL ES / Vulkan / Skia / Shader / 图形动效 / 渲染引擎                         | 4.7       |
| React Native / RN 容器                                                              | 5.1       |
| Flutter                                                                           | 5.2       |
| ArkTS / Stage 模型 / UIAbility / 分布式 / DevEco Studio                                | 5.3       |

---

> **使用建议**：这份文档的设计目标是"按需查阅"而不是"通读"。建议先读第 6 部分（叙事模板与问题地图），知道自己要准备什么，再回头补对应知识块的深度。
>
> 文档中的源码路径均以 AOSP（`android-14.0.0_r1` 前后）为准；不同版本目录可能微调，但模块划分与机制是稳定的。
