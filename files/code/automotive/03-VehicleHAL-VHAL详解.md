# 03 Vehicle HAL / VHAL 详解

> Vehicle HAL 是 Android Automotive 和车辆底层之间的边界。CarService 不直接读 CAN 报文，而是通过 VHAL 读写标准化后的车辆属性。

---

## 1. VHAL 在系统中的位置

整体链路：

```text
App / System App
  -> CarPropertyManager
  -> CarPropertyService
  -> VehicleHal
  -> IVehicle AIDL/HIDL
  -> vendor VHAL implementation
  -> CAN / LIN / Ethernet / MCU / domain controller
```

VHAL 的职责不是做复杂业务 UI，而是把车辆底层信号转换为 AAOS 能理解的 property 模型。

它主要负责：

- 暴露车辆属性配置。
- 响应属性读取。
- 处理属性写入。
- 上报属性变化事件。
- 把 Android property 映射到车厂底层信号。
- 管理底层连接状态和错误状态。

---

## 2. AIDL VHAL 和 HIDL VHAL

不同 Android 版本中，Vehicle HAL 可能基于 HIDL 或 AIDL。

| 类型 | 特点 |
|---|---|
| HIDL VHAL | 老版本常见，接口路径通常在 `hardware/interfaces/automotive/vehicle/2.0` |
| AIDL VHAL | 新版本主线，更符合 Android 新 HAL 方向 |

常见路径：

```text
hardware/interfaces/automotive/vehicle/2.0/
hardware/interfaces/automotive/vehicle/aidl/
```

学习时不用一开始纠结 AIDL/HIDL 细节，先抓住共同模型：

```text
getAllPropConfigs
getPropConfigs
get
set
subscribe
unsubscribe
onPropertyEvent
onPropertySetError
```

---

## 3. VHAL 暴露的核心对象

### 3.1 VehiclePropConfig

描述一个属性的能力：

```text
propertyId
access
changeMode
areaConfigs
configArray
configString
minSampleRate
maxSampleRate
```

可以理解为：

```text
VehiclePropConfig = 这个属性支持什么、怎么读写、有哪些区域、范围是多少
```

### 3.2 VehiclePropValue

描述一个属性的具体值：

```text
prop
areaId
value
status
timestamp
```

可以理解为：

```text
VehiclePropValue = 某个属性在某个区域上的一次实际数据
```

### 3.3 VehiclePropertyStatus

常见状态：

| 状态 | 含义 |
|---|---|
| available | 当前值可用 |
| unavailable | 当前值不可用 |
| error | 当前值错误或底层异常 |

上层不要假设每次读到的值都可靠。车身信号可能因为车辆状态、ECU 未 ready、通信异常而不可用。

---

## 4. get：读取属性

读取链路：

```text
CarPropertyManager.getProperty()
  -> CarPropertyService.getProperty()
  -> VehicleHal.get()
  -> IVehicle.get()
  -> vendor VHAL 查询底层缓存或控制器
```

VHAL 实现可以从两类地方取值：

- 最近一次底层信号缓存。
- 实时向控制器查询。

对上层来说，关键是返回：

```text
value + status + timestamp
```

不是所有属性都适合实时查询。高频属性通常由底层持续上报，VHAL 缓存最新值。

---

## 5. set：写入属性

写入链路：

```text
CarPropertyManager.setProperty()
  -> CarPropertyService 权限检查
  -> VehicleHal.set()
  -> IVehicle.set()
  -> vendor VHAL 写入底层控制器
  -> 底层执行或拒绝
  -> 后续通过 property event 回报真实状态
```

重点：`set` 成功返回不一定等于车辆物理状态已经变化。

例如用户设置空调温度：

```text
App set 22°C
  -> VHAL 接受请求
  -> HVAC 控制器执行
  -> 控制器回报当前设定温度
  -> VHAL 上报 HVAC_TEMPERATURE_SET = 22°C
  -> App 收到 callback 更新 UI
```

更稳妥的 UI 设计是：

```text
用户点击
  -> UI 显示 pending
  -> setProperty
  -> 等 callback 确认
  -> 更新为 confirmed 状态
```

---

## 6. subscribe：订阅属性变化

订阅链路：

```text
CarPropertyManager.registerCallback()
  -> CarPropertyService 注册客户端
  -> VehicleHal.subscribe()
  -> IVehicle.subscribe()
  -> vendor VHAL 按采样率上报
```

属性变化上报链路：

```text
底层信号变化
  -> vendor VHAL
  -> IVehicleCallback.onPropertyEvent
  -> VehicleHal
  -> CarPropertyService
  -> CarPropertyManager callback
```

连续属性需要考虑采样率：

```text
车速、转速、电池功率、电机转速
```

不要给普通 UI 使用过高采样率，否则会增加 Binder、线程、UI 刷新和底层通信压力。

---

## 7. 标准属性和 Vendor 属性

AAOS 定义了一批标准属性，比如：

```text
PERF_VEHICLE_SPEED
GEAR_SELECTION
CURRENT_GEAR
HVAC_TEMPERATURE_SET
DOOR_LOCK
SEAT_HEAT
FUEL_LEVEL
EV_BATTERY_LEVEL
```

车厂可以定义 vendor property，用于表达标准属性没有覆盖的能力。

设计 vendor property 时要注意：

- ID 范围不要和标准属性冲突。
- 明确 access、changeMode、area。
- 明确权限模型。
- 文档化 value 类型和取值范围。
- 避免把业务语义模糊地塞进 int 数组。

好的 property 设计应该让上层一看就知道：

```text
这是什么能力
属于哪个区域
是否可读写
值的类型是什么
什么时候会变化
错误状态如何表达
```

---

## 8. VHAL 与真实车辆信号映射

典型映射：

```text
CAN signal: VehicleSpeed
  -> vendor adapter
  -> VehiclePropertyIds.PERF_VEHICLE_SPEED
  -> VehiclePropValue.floatValues[0]
```

区域属性映射：

```text
CAN signal: DoorLock_FL
  -> VehiclePropertyIds.DOOR_LOCK + ROW_1_LEFT area

CAN signal: DoorLock_FR
  -> VehiclePropertyIds.DOOR_LOCK + ROW_1_RIGHT area
```

VHAL 需要处理：

- 单位转换，比如 km/h、m/s、摄氏度。
- 枚举值转换，比如底层档位到 AAOS 档位常量。
- 区域映射，比如左前、右前、后排。
- 信号有效位，比如 invalid、timeout、not available。
- 初始值和默认值。
- 控制命令的 ACK/NACK。

---

## 9. Fake VHAL

开发和调试中常用 fake VHAL 或模拟器 VHAL。

用途：

- 没有真实车身硬件时调试车控 App。
- 注入车速、档位、车门、空调状态。
- 验证 CarPropertyManager 回调链路。
- 编写自动化测试。

常见操作思路：

```text
启动 AAOS emulator
  -> 用命令或工具设置车辆属性
  -> CarService 收到 VHAL 事件
  -> App callback 刷新 UI
```

Fake VHAL 能验证 Framework 和 App 逻辑，但不能替代实车验证。真实车辆会有通信延迟、控制失败、状态不可用、点火状态差异等问题。

---

## 10. VHAL 常见问题

| 问题 | 可能原因 |
|---|---|
| 属性配置不存在 | VHAL 没声明该 property config |
| 属性有配置但读不到 | 底层未上报、status unavailable、权限不足 |
| 写入成功但状态不变 | 底层拒绝、控制器未执行、没有回报事件 |
| 区域属性只有部分区域可用 | areaConfig 不完整或区域映射错误 |
| 车速等连续属性卡顿 | 采样率、底层上报频率、Binder 分发或 UI 处理问题 |
| 模拟器可用实车不可用 | 真实 VHAL 映射、权限、车辆状态或 ECU ready 时机不同 |

---

## 11. 排查建议

优先看四层：

```text
App 层：是否请求正确 propertyId/areaId，是否有权限
CarService 层：是否注册、是否通过权限检查、是否收到 VHAL 事件
VHAL 层：是否声明 config，get/set/subscribe 是否正常
底层层：CAN/MCU/控制器是否真的有信号或执行控制
```

常用命令方向：

```bash
adb shell dumpsys car_service
adb logcat -s CarService CarPropertyService VehicleHal
```

不同版本 tag 会有差异，实际排查时可以先全局搜索：

```bash
adb logcat | grep -i "vehicle\|carproperty\|carservice"
```

---

## 12. 读源码时抓住的接口

建议顺序：

1. `CarPropertyManager`：App 侧 API。
2. `ICarProperty.aidl`：Binder 契约。
3. `CarPropertyService`：权限、客户端、配置、回调。
4. `VehicleHal`：Framework 到 HAL 的适配层。
5. `IVehicle`：AIDL/HIDL HAL 接口。
6. fake/reference VHAL：看属性如何配置和上报。

看源码时不要一开始陷入所有 car service。先跑通一个属性，比如车速或空调温度，这条链清楚后再扩展到其他车辆能力。
