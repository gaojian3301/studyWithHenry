# 07 CarService 源码阅读路线

> CarService 源码范围很大，不建议从所有服务一起看。更有效的方式是选择一个具体车辆属性，比如车速或空调温度，从 App API 一路追到 VHAL。

---

## 1. 推荐主线

先用一条最小链路建立整体结构：

```text
Car.createCar()
  -> Car.getCarManager(Car.PROPERTY_SERVICE)
  -> CarPropertyManager.get/set/registerCallback
  -> ICarProperty.aidl
  -> CarPropertyService
  -> VehicleHal
  -> IVehicle
  -> fake/vendor VHAL
```

这条链路清楚后，再看 CarPower、CarAudio、CarUser 等服务会容易很多。

---

## 2. 第一站：car-lib 中的 App API

常见路径：

```text
packages/services/Car/car-lib/src/android/car/Car.java
packages/services/Car/car-lib/src/android/car/hardware/property/CarPropertyManager.java
packages/services/Car/car-lib/src/android/car/hardware/CarPropertyValue.java
packages/services/Car/car-lib/src/android/car/hardware/CarPropertyConfig.java
packages/services/Car/car-lib/src/android/car/VehiclePropertyIds.java
```

先看这些问题：

- `Car.createCar()` 怎么连接服务。
- `Car.getCarManager()` 怎么根据 service name 创建 manager。
- `CarPropertyManager` 如何封装 Binder 调用。
- callback 如何注册、保存和分发。
- `CarPropertyValue` 和 `CarPropertyConfig` 字段含义。

---

## 3. 第二站：Binder 接口

搜索关键词：

```text
ICar.aidl
ICarProperty.aidl
ICarPropertyEventListener.aidl
```

Binder 接口告诉你 App 和 CarService 之间真正传递了什么。

重点看：

```text
getProperty
setProperty
registerListener
unregisterListener
getPropertyList
```

读 AIDL 的好处是能过滤掉很多实现细节，先看清契约。

---

## 4. 第三站：CarService 启动和服务注册

常见路径：

```text
packages/services/Car/service/src/com/android/car/CarService.java
packages/services/Car/service/src/com/android/car/ICarImpl.java
```

重点看：

- CarService 什么时候启动。
- `ICarImpl` 如何创建各个 car service。
- service name 到具体 manager/service 的映射关系。
- init/release 生命周期。
- dump 输出包含哪些信息。

可以带着这个问题读：

```text
App 调 getCarManager(PROPERTY_SERVICE)，最后到底拿到了哪个 Binder 服务？
```

---

## 5. 第四站：CarPropertyService

常见路径：

```text
packages/services/Car/service/src/com/android/car/hal/PropertyHalService.java
packages/services/Car/service/src/com/android/car/CarPropertyService.java
```

不同版本命名和拆分可能不同，但职责类似。

重点看：

- property config 从哪里来。
- propertyId 如何映射权限。
- get/set 前如何做权限检查。
- listener 如何注册。
- VHAL event 如何分发给客户端。
- 客户端死亡如何清理。
- dump 输出如何组织。

建议先追一个方法：

```text
CarPropertyManager.getProperty
  -> ICarProperty.getProperty
  -> CarPropertyService.getProperty
  -> VehicleHal.get
```

再追一个回调：

```text
VHAL onPropertyEvent
  -> CarPropertyService onHalEvents
  -> listener callback
  -> CarPropertyManager onChangeEvent
```

---

## 6. 第五站：VehicleHal

常见路径：

```text
packages/services/Car/service/src/com/android/car/hal/VehicleHal.java
packages/services/Car/service/src/com/android/car/hal/VehicleHalCallback.java
```

重点看：

- 连接的是 AIDL VHAL 还是 HIDL VHAL。
- 如何加载所有 property config。
- get/set/subscribe 如何转发到 HAL。
- HAL 错误如何转换成上层异常或状态。
- VHAL callback 如何进入 CarService。

`VehicleHal` 是 Framework 和 HAL 的边界，很多疑难问题都要在这里判断：

```text
问题在 App/CarService 侧，还是已经进入 VHAL/vendor 侧？
```

---

## 7. 第六站：AIDL/HIDL Vehicle HAL

常见路径：

```text
hardware/interfaces/automotive/vehicle/aidl/
hardware/interfaces/automotive/vehicle/2.0/
```

重点看接口：

```text
IVehicle
IVehicleCallback
VehiclePropConfig
VehiclePropValue
SubscribeOptions
StatusCode
```

不要一开始被 HAL 工程细节拖走。先确认：

- config 怎么返回。
- get 怎么返回值。
- set 怎么返回状态。
- subscribe 怎么设置采样率。
- callback 怎么上报 property event。

---

## 8. 第七站：Fake VHAL 或参考实现

搜索关键词：

```text
FakeVehicleHardware
DefaultConfig
VehiclePropertyStore
JsonFakeValueGenerator
PERF_VEHICLE_SPEED
HVAC_TEMPERATURE_SET
```

参考实现能帮你看懂：

- 一个 property config 怎么写。
- 初始值怎么存。
- set 后如何更新 store。
- 如何触发 onPropertyEvent。
- continuous property 如何定时上报。

如果你还没有真实车厂 VHAL 代码，Fake VHAL 是最好的学习材料。

---

## 9. 带问题读源码

### 9.1 App 为什么连不上 CarService

看：

```text
Car.createCar
ServiceConnection
ICar binder
CarService 是否启动
service list
```

### 9.2 为什么 getProperty 抛 SecurityException

看：

```text
CarPropertyService 权限检查
propertyId -> permission 映射
App manifest
privapp-permissions
package granted permissions
```

### 9.3 为什么属性不存在

看：

```text
VHAL getAllPropConfigs
VehicleHal 初始化缓存
CarPropertyService propertyList
Fake/Vendor VHAL config
```

### 9.4 为什么 set 后 UI 没变化

看：

```text
set 是否到 CarPropertyService
set 是否到 VHAL
VHAL 是否接受
底层是否上报新值
CarPropertyService 是否分发 callback
App 是否还注册 listener
```

### 9.5 为什么 release 或量产版本权限不一样

看：

```text
签名
安装分区
privapp permission whitelist
product/system_ext/vendor 差异
SELinux
targetSdk 和权限策略
```

---

## 10. 推荐阅读顺序总结

```text
1. Car.java
2. CarPropertyManager.java
3. ICarProperty.aidl
4. CarService.java / ICarImpl.java
5. CarPropertyService.java
6. VehicleHal.java
7. IVehicle.aidl 或 HIDL IVehicle.hal
8. Fake VHAL config 和实现
9. dumpsys car_service 输出
10. 实际项目中的 vendor property 定义
```

每一步都用同一个属性做锚点，比如：

```text
PERF_VEHICLE_SPEED
```

或者：

```text
HVAC_TEMPERATURE_SET
```

这样不会在庞大的 CarService 代码里迷路。

---

## 11. 常用搜索关键词

```text
Car.PROPERTY_SERVICE
CarPropertyManager
ICarProperty
CarPropertyService
VehicleHal
onPropertyEvent
getAllPropConfigs
VehiclePropConfig
VehiclePropValue
PERF_VEHICLE_SPEED
HVAC_TEMPERATURE_SET
```

如果是权限问题，搜：

```text
checkPermission
assertPermission
readPermission
writePermission
CAR_SPEED
CONTROL_CAR_CLIMATE
privapp-permissions
```

如果是 VHAL 问题，搜：

```text
IVehicle
subscribe
StatusCode
VehiclePropertyStore
FakeVehicleHardware
DefaultConfig
```

---

## 12. 一图总结

```text
App/System App
  Car.createCar
  CarPropertyManager
        │
        │ ICarProperty Binder
        ▼
CarService / ICarImpl
  CarPropertyService
        │
        │ VehicleHal API
        ▼
VehicleHal
        │
        │ AIDL/HIDL IVehicle
        ▼
Fake or Vendor VHAL
        │
        ▼
Vehicle signal adapter
        │
        ▼
CAN / LIN / Ethernet / MCU
```

读源码的目标不是背类名，而是能回答：

```text
一个车辆属性从底层变化到 App UI，中间经过了哪些对象？
App 控制一个车辆能力时，系统在哪里检查权限，在哪里转发到 VHAL，在哪里确认真实状态？
```
