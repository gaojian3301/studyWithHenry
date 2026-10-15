# 01 AAOS 整体架构与 CarService

> Android Automotive OS 不是把 Android Framework 全部换掉，而是在原生 Android 之上增加车载系统层。理解 Automotive，最重要的是抓住 `CarService`、`Car API`、`Vehicle HAL` 这条主线。

---

## 1. 先区分 Android Auto 和 AAOS

| 名称 | 运行位置 | 开发重点 |
|---|---|---|
| Android Auto | App 主要运行在手机，车机负责投屏和交互 | Car App Library、手机 App、模板化 UI |
| Android Automotive OS, AAOS | Android 系统直接运行在车机硬件上 | 系统 App、Framework、CarService、VHAL、车控 |

本目录重点讨论的是 **AAOS**。

一句话理解：

```text
普通 Android Framework
  + 车载系统服务 packages/services/Car
  + Vehicle HAL
  + 车厂定制系统 App
  + 车辆总线和控制器适配
  = Android Automotive OS
```

---

## 2. AAOS 中真正新增的核心层

普通 Android 已经有：

- AMS：进程、Service、Broadcast、Provider。
- ATMS/WMS：Activity、Task、窗口、显示。
- PMS：包管理、权限、安装。
- Power：亮灭屏、休眠、省电。
- Audio：音频焦点、路由、音量。
- Input：触摸、按键、旋钮输入。

AAOS 并不会把这些机制全部重写，而是新增车载领域层：

```text
App / System App
  -> android.car.* API
  -> CarService
  -> CarPropertyService / CarPowerManagementService / CarAudioService ...
  -> VehicleHal
  -> AIDL/HIDL Vehicle HAL
  -> vendor VHAL implementation
  -> CAN / LIN / Ethernet / MCU / domain controller
```

对系统开发者来说，最值得集中学习的是：

- App 如何连接 `CarService`。
- `CarService` 如何注册和管理各个 car service。
- 车辆属性如何抽象为 `VehicleProperty`。
- 车辆信号如何从 VHAL 上报到 Framework。
- App 或系统应用如何读写车身属性。
- 权限、安全、区域、订阅频率如何控制。

---

## 3. CarService 是什么

`CarService` 是 AAOS 的车载系统服务入口，通常位于：

```text
packages/services/Car/service/
```

它运行在系统进程或独立的 car service 进程中，向外提供 `android.car.*` API 背后的 Binder 服务。

可以把它理解为：

```text
CarService 是车载 Framework 的总入口。
AMS/WMS 是通用 Android 的系统调度中心；
CarService 是 AAOS 里车辆能力的系统服务集合入口。
```

它负责：

- 启动和管理多个车载子服务。
- 暴露 Binder 给 App/System App。
- 连接 Vehicle HAL。
- 做权限检查和调用方身份校验。
- 把底层车辆属性变化分发给上层监听者。
- 处理车载电源、音频、用户、属性、诊断等领域能力。

---

## 4. CarService 常见子服务

| 服务 | 作用 |
|---|---|
| `CarPropertyService` | 车辆属性读写和订阅，是 Vehicle 主线最核心服务 |
| `CarPowerManagementService` | 车载电源状态、suspend、shutdown、garage mode |
| `CarAudioService` | Audio Zone、Volume Group、车载音频策略 |
| `CarUserService` | 车载多用户、驾驶员用户、用户切换 |
| `CarPackageManagerService` | 车载 App 安全限制、驾驶状态限制相关能力 |
| `CarInputService` | 车载输入事件，如方向盘按键、旋钮等 |
| `CarOccupantZoneService` | 座舱区域、屏幕、用户、乘员关系 |
| `CarUxRestrictionsManagerService` | 驾驶分心限制，控制行驶中 UI 能做什么 |

本目录会以 `CarPropertyService` 和 Vehicle 为主线。其他服务只在和车辆属性相关时补充。

---

## 5. App 侧如何进入 CarService

系统 App 或有权限的 App 通常这样连接：

```kotlin
val car = Car.createCar(context)
car.connect()

val propertyManager = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
```

异步连接写法更常见：

```kotlin
val car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER) { car, ready ->
    if (ready) {
        val manager = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
    }
}
```

这条链路背后大致是：

```text
Car.createCar(context)
  -> 绑定或查询 car_service
  -> 获得 ICar Binder
  -> getCarManager(PROPERTY_SERVICE)
  -> 获得 ICarProperty Binder 包装对象
  -> CarPropertyManager 调用 CarPropertyService
```

---

## 6. Vehicle 主线调用链

读取一个车辆属性时，可以先记住这条链：

```text
App / System App
  CarPropertyManager.getProperty()
        │ Binder
        ▼
  CarPropertyService
        │
        ▼
  VehicleHal
        │ AIDL/HIDL
        ▼
  IVehicle
        │
        ▼
  vendor VHAL
        │
        ▼
  车辆总线 / MCU / 域控制器
```

订阅一个车辆属性时，方向相反：

```text
车辆信号变化
  -> vendor VHAL
  -> IVehicle callback
  -> VehicleHal
  -> CarPropertyService
  -> CarPropertyManager callback
  -> App/System App
```

---

## 7. 为什么说其他模块和原生差异没那么大

很多机制确实还是原生 Android 那套：

| 领域 | AAOS 是否重写 | 车载差异主要在哪里 |
|---|---|---|
| AMS/进程 | 不重写 | 车载系统 App、后台策略、用户切换场景更多 |
| WMS/显示 | 不重写 | 多屏、Occupant Zone、Cluster、副驾屏策略 |
| PMS/权限 | 不重写 | privileged app、signature permission、car permission 更多 |
| Power | 不重写 | 车辆点火、ACC、suspend/resume、garage mode |
| Audio | 不重写 | Audio Zone、音量组、导航/电话/媒体策略更复杂 |
| Input | 不重写 | 方向盘按键、旋钮、硬按键、座舱区域 |

所以学习策略应该是：

```text
原生 Framework 作为底座
CarService + Vehicle 作为主线
遇到电源、音频、多屏、用户等车载差异时再局部展开
```

---

## 8. 关键源码路径

常见源码位置：

```text
packages/services/Car/
packages/services/Car/service/
packages/services/Car/car-lib/
packages/services/Car/tests/

hardware/interfaces/automotive/vehicle/
hardware/interfaces/automotive/vehicle/aidl/
hardware/interfaces/automotive/vehicle/2.0/

frameworks/base/core/java/android/car/        # 不同版本中路径可能不同
```

更常见的 API 路径是：

```text
packages/services/Car/car-lib/src/android/car/
packages/services/Car/car-lib/src/android/car/hardware/property/
```

---

## 9. 建议阅读顺序

1. 先理解 `Car.createCar()` 如何拿到 `CarPropertyManager`。
2. 再理解 `CarPropertyManager` 的 get/set/subscribe。
3. 然后看 `CarPropertyService` 如何做权限、配置、回调管理。
4. 接着看 `VehicleHal` 如何和 AIDL/HIDL VHAL 交互。
5. 最后看 VHAL fake 实现或厂商实现如何映射真实车辆信号。

如果只是做车控 App 或系统 App，重点看前 3 步。如果要做系统集成或 BSP/vendor 适配，必须继续看到 VHAL 和底层信号映射。
