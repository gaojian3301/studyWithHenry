# 02 CarPropertyManager 与 VehicleProperty

> `CarPropertyManager` 是 App/System App 访问车辆属性的主要入口。`VehicleProperty` 则是 AAOS 对车辆信号的统一抽象：车速、档位、空调、车门、座椅、电量，都可以被表达成 property。

---

## 1. 车辆能力为什么要抽象成 Property

真实车辆信号通常来自：

```text
CAN / LIN / Ethernet / MCU / 域控制器
```

但 App 不应该直接理解底层报文。AAOS 把它们抽象成统一模型：

```text
VehiclePropertyId + areaId + value + status + timestamp
```

这样上层只需要关心：

- 我要读哪个属性。
- 这个属性属于哪个区域。
- 当前值是多少。
- 这个值是否可用。
- 是否支持订阅变化。
- 调用方有没有权限。

---

## 2. CarPropertyManager 基本用法

获取 manager：

```kotlin
val car = Car.createCar(context)
car.connect()

val propertyManager = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
```

读取属性：

```kotlin
val speed = propertyManager.getFloatProperty(
    VehiclePropertyIds.PERF_VEHICLE_SPEED,
    VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL,
)
```

写入属性：

```kotlin
propertyManager.setFloatProperty(
    VehiclePropertyIds.HVAC_TEMPERATURE_SET,
    areaId,
    22.5f,
)
```

订阅属性变化：

```kotlin
val callback = object : CarPropertyManager.CarPropertyEventCallback {
    override fun onChangeEvent(value: CarPropertyValue<Any>) {
        val propertyId = value.propertyId
        val areaId = value.areaId
        val propertyValue = value.value
    }

    override fun onErrorEvent(propertyId: Int, areaId: Int) {
        // handle property error
    }
}

propertyManager.registerCallback(
    callback,
    VehiclePropertyIds.PERF_VEHICLE_SPEED,
    CarPropertyManager.SENSOR_RATE_NORMAL,
)
```

用完取消：

```kotlin
propertyManager.unregisterCallback(callback)
```

---

## 3. VehicleProperty 的核心字段

一个车辆属性通常可以拆成：

| 字段 | 含义 |
|---|---|
| `propertyId` | 属性 ID，比如车速、档位、空调温度 |
| `areaId` | 区域 ID，比如左前座、右前座、全局 |
| `value` | 属性值，类型可能是 int、float、boolean、数组等 |
| `status` | 值状态，比如 available、unavailable、error |
| `timestamp` | 底层上报时间戳 |
| `access` | 读、写、读写 |
| `changeMode` | 静态、变化上报、连续采样 |
| `minSampleRate/maxSampleRate` | 连续属性支持的采样频率范围 |

常见值对象：

```kotlin
CarPropertyValue<T>
```

可以理解为：

```text
CarPropertyValue = 某个 property 在某个 area 上的某次取值
```

---

## 4. propertyId：车辆属性编号

标准属性定义在：

```text
VehiclePropertyIds
```

常见例子：

| 属性 | 含义 |
|---|---|
| `PERF_VEHICLE_SPEED` | 车速 |
| `GEAR_SELECTION` | 当前选择档位 |
| `CURRENT_GEAR` | 当前实际档位 |
| `PARKING_BRAKE_ON` | 手刹状态 |
| `IGNITION_STATE` | 点火状态 |
| `HVAC_TEMPERATURE_SET` | 空调设定温度 |
| `HVAC_FAN_SPEED` | 空调风量 |
| `DOOR_LOCK` | 车门锁状态 |
| `SEAT_HEAT` | 座椅加热 |
| `EV_BATTERY_LEVEL` | 电动车电量 |
| `FUEL_LEVEL` | 燃油余量 |

车厂也可以定义 vendor property。一般会使用厂商预留范围，避免和标准属性冲突。

---

## 5. areaId：属性作用区域

不是所有属性都是全局的。

全局属性例子：

```text
车速
档位
手刹
点火状态
```

区域属性例子：

```text
左前门锁
右前门锁
驾驶位座椅加热
副驾空调温度
后排左侧空调出风
```

典型区域类型：

| 类型 | 例子 |
|---|---|
| `GLOBAL` | 车速、档位 |
| `WINDOW` | 车窗 |
| `MIRROR` | 后视镜 |
| `SEAT` | 座椅 |
| `DOOR` | 车门 |
| `WHEEL` | 车轮 |

读写区域属性时，`areaId` 必须和 VHAL 配置一致。很多问题不是 propertyId 写错，而是 areaId 不匹配。

---

## 6. access：读写能力

一个属性可能是：

| access | 含义 |
|---|---|
| read | 只能读，比如车速 |
| write | 只能写，少见 |
| read_write | 可读可写，比如部分 HVAC 控制 |

即使属性本身支持写，也不代表任意 App 能写。还需要看：

- 是否有对应 car permission。
- 是否是系统/特权应用。
- 是否签名匹配。
- 当前车辆状态是否允许。
- 底层 VHAL 是否接受这次 set。

---

## 7. changeMode：属性变化模式

| changeMode | 含义 | 例子 |
|---|---|---|
| static | 基本不变 | 车辆配置信息 |
| on change | 变化时上报 | 车门、档位、空调开关 |
| continuous | 连续变化 | 车速、转速 |

连续属性订阅时要传采样频率：

```kotlin
propertyManager.registerCallback(
    callback,
    VehiclePropertyIds.PERF_VEHICLE_SPEED,
    CarPropertyManager.SENSOR_RATE_NORMAL,
)
```

常见采样等级：

| 等级 | 使用场景 |
|---|---|
| `SENSOR_RATE_ONCHANGE` | 只关心变化事件 |
| `SENSOR_RATE_NORMAL` | 普通 UI 展示 |
| `SENSOR_RATE_UI` | UI 较高频刷新 |
| `SENSOR_RATE_FAST` | 高频采样，谨慎使用 |
| `SENSOR_RATE_FASTEST` | 最高频，通常只给系统或测试场景 |

---

## 8. CarPropertyConfig

在读写之前，可以先查询属性配置：

```kotlin
val configs = propertyManager.propertyList
```

单个配置中会包含：

```text
propertyId
access
changeMode
areaIds
min/max value
min/max sample rate
configArray
configString
```

用途：

- 判断属性是否存在。
- 判断某个区域是否支持。
- 判断是否可读写。
- 判断数值范围，比如温度最小/最大值。
- 判断采样频率范围。

车控 UI 不应该硬编码所有能力。更稳妥的做法是：

```text
先读 CarPropertyConfig
  -> 决定 UI 是否展示
  -> 决定控件范围
  -> 决定是否允许用户操作
```

---

## 9. 读取、写入、订阅的区别

| 操作 | 适用场景 | 注意点 |
|---|---|---|
| get | 打开页面时读一次当前状态 | 可能返回 unavailable 或抛异常 |
| set | 用户主动控制车身能力 | 要处理失败、权限、车辆状态限制 |
| subscribe | 状态变化实时刷新 UI | 注意取消订阅，避免泄漏和多余负载 |

典型车控页面流程：

```text
页面启动
  -> 查询 CarPropertyConfig
  -> 读取当前值
  -> 注册 callback
  -> 用户操作时 setProperty
  -> callback 收到底层确认状态后刷新 UI
  -> 页面销毁时 unregister
```

不要只在 set 后本地改 UI。真实车辆可能拒绝执行，或者执行后又被其他控制源改回。

---

## 10. 常见异常和排查

| 现象 | 常见原因 |
|---|---|
| 属性读不到 | VHAL 未配置、propertyId 错、权限不足、areaId 错 |
| set 无效 | 属性只读、权限不足、车辆状态不允许、VHAL 拒绝 |
| callback 不回调 | changeMode 不支持、未订阅正确 area、采样率不合理 |
| 值一直 unavailable | 底层信号无效、车辆状态未 ready、VHAL status 上报异常 |
| debug 能用三方 App 不能用 | 系统权限、签名权限、privileged 权限差异 |

优先排查顺序：

1. `adb shell dumpsys car_service` 看属性服务状态。
2. 看 property config 是否存在。
3. 看权限声明和 App 安装位置。
4. 用 fake VHAL 或工具直接注入属性变化。
5. 看 CarService 和 VHAL 日志。

---

## 11. 设计车控代码的建议

- 不要把 propertyId、areaId、UI 控件散落在页面里。
- 给车辆属性做一层 domain model，比如 `HvacState`、`DoorState`。
- set 后等待真实回调确认，再更新关键状态。
- 对 unavailable/error 状态要有 UI 表达。
- 对权限不足和车辆状态限制要有明确降级。
- 订阅要和生命周期绑定，页面不可见时及时取消。
- 高频属性不要无脑刷 UI，必要时做节流。
