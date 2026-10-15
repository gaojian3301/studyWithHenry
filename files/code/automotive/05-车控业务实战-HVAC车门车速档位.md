# 05 车控业务实战：HVAC、车门、车速、档位

> 理解 CarService 和 VHAL 最好的方式，是拿几个典型车控场景串起来：空调、车门、车速、档位。它们分别代表可读写区域属性、可控制离散状态、连续只读属性和车辆状态枚举。

---

## 1. 车控页面的通用流程

一个车控页面通常不是简单地调一次 set。

推荐流程：

```text
进入页面
  -> 连接 CarService
  -> 获取 CarPropertyManager
  -> 查询 CarPropertyConfig
  -> 判断属性和区域是否支持
  -> 读取当前值
  -> 注册属性变化 callback
  -> 用户操作时 setProperty
  -> 等底层回调确认
  -> 页面退出时取消订阅
```

代码结构上建议分层：

```text
UI
  -> ViewModel
  -> VehicleRepository
  -> CarPropertyDataSource
  -> CarPropertyManager
```

不要在 UI 组件里散落 propertyId、areaId 和权限处理。

---

## 2. HVAC：空调控制

HVAC 是车控里最典型的区域属性。

常见属性：

| 属性 | 含义 |
|---|---|
| `HVAC_POWER_ON` | 空调开关 |
| `HVAC_TEMPERATURE_SET` | 设定温度 |
| `HVAC_FAN_SPEED` | 风量 |
| `HVAC_FAN_DIRECTION` | 风向 |
| `HVAC_AC_ON` | AC 开关 |
| `HVAC_AUTO_ON` | 自动模式 |
| `HVAC_SEAT_TEMPERATURE` | 座椅温控，版本和车型相关 |

区域可能包括：

```text
驾驶位
副驾驶
后排左
后排右
全车
```

### 2.1 温度控制流程

```text
用户拖动温度条
  -> 检查该 area 是否支持 HVAC_TEMPERATURE_SET
  -> 检查温度是否在 min/max 范围内
  -> setFloatProperty
  -> UI 显示 pending
  -> callback 收到新值
  -> UI 更新为实际状态
```

示例：

```kotlin
propertyManager.setFloatProperty(
    VehiclePropertyIds.HVAC_TEMPERATURE_SET,
    driverAreaId,
    22.5f,
)
```

查询范围：

```kotlin
val config = propertyManager.getCarPropertyConfig(VehiclePropertyIds.HVAC_TEMPERATURE_SET)
val areaConfig = config.getAreaConfig(driverAreaId)
val min = areaConfig.minValue as Float
val max = areaConfig.maxValue as Float
```

### 2.2 HVAC 常见坑

| 现象 | 可能原因 |
|---|---|
| 温度 set 后不变 | 车辆未 ready、HVAC 关闭、VHAL 拒绝、区域不支持 |
| 左右温度同时变 | 车型只有单区空调，或者 sync 模式开启 |
| UI 范围和实车不一致 | 没读取 areaConfig min/max，写死了范围 |
| callback 回来的值跳变 | 底层控制器做了离散档位或单位转换 |
| 模拟器可用实车失败 | 实车权限、区域映射、控制器状态差异 |

---

## 3. 车门：锁、开关和区域

车门属性通常是离散状态，并且强区域相关。

常见属性：

| 属性 | 含义 |
|---|---|
| `DOOR_LOCK` | 车门锁状态 |
| `DOOR_POS` | 车门开合位置或状态 |
| `DOOR_MOVE` | 电动车门移动控制，车型相关 |

区域例子：

```text
ROW_1_LEFT   -> 左前门
ROW_1_RIGHT  -> 右前门
ROW_2_LEFT   -> 左后门
ROW_2_RIGHT  -> 右后门
```

### 3.1 车门锁控制

```kotlin
propertyManager.setBooleanProperty(
    VehiclePropertyIds.DOOR_LOCK,
    leftFrontDoorAreaId,
    true,
)
```

业务上要考虑：

- 行驶中是否允许解锁。
- 儿童锁是否影响后门。
- 远程控制和本地控制是否冲突。
- 控制失败如何提示。
- 物理按键改变状态时 UI 是否同步。

### 3.2 车门状态 UI

车门 UI 不应该只依赖点击状态，而应该订阅真实属性：

```text
DOOR_LOCK -> 显示锁/解锁
DOOR_POS  -> 显示开/关/半开
```

车门是安全相关能力。系统 App 可以做控制，普通 App 通常只能获取有限状态，甚至完全无权访问。

---

## 4. 车速：连续只读属性

车速通常是全局、连续、只读属性。

读取：

```kotlin
val speed = propertyManager.getFloatProperty(
    VehiclePropertyIds.PERF_VEHICLE_SPEED,
    VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL,
)
```

订阅：

```kotlin
propertyManager.registerCallback(
    speedCallback,
    VehiclePropertyIds.PERF_VEHICLE_SPEED,
    CarPropertyManager.SENSOR_RATE_NORMAL,
)
```

车速常用于：

- 驾驶分心限制。
- 行驶中禁用复杂输入。
- 导航、仪表、辅助驾驶状态展示。
- 车辆状态判断。

### 4.1 车速使用注意

- 不要用过高采样率刷新普通 UI。
- 处理 unavailable 和 error。
- 明确单位，避免 m/s 和 km/h 混用。
- 不要用车速做唯一安全判断，安全策略应由系统服务和车辆状态共同决定。

---

## 5. 档位：枚举状态

档位通常是全局、on change、只读属性。

常见属性：

| 属性 | 含义 |
|---|---|
| `GEAR_SELECTION` | 用户选择的档位 |
| `CURRENT_GEAR` | 当前实际档位 |

两者可能不同。例如用户选择 D，但车辆控制器实际切换存在延迟或失败。

业务上更应该看实际语义：

```text
展示用户选择 -> GEAR_SELECTION
判断车辆真实状态 -> CURRENT_GEAR
```

常见档位：

```text
P / R / N / D / S / L
```

不同车型、电动车、混动车可能有更多状态或 vendor 扩展。

---

## 6. 状态确认：不要乐观更新关键车控状态

普通 App UI 常见做法是点击后立即更新状态。但车控场景里要谨慎。

推荐模式：

```text
用户操作
  -> 本地标记 pending command
  -> 调用 set
  -> 等 callback 或超时
  -> 成功：显示新状态
  -> 失败：恢复旧状态并提示失败原因
```

原因：

- 底层可能拒绝。
- 车辆状态可能不允许。
- 控制器执行需要时间。
- 其他控制源可能抢占。
- 真实状态可能被物理按键改变。

---

## 7. 车控 Repository 设计示例

可以把车辆属性封装成领域状态：

```kotlin
data class HvacState(
    val powerOn: Boolean,
    val driverTemperature: Float?,
    val passengerTemperature: Float?,
    val fanSpeed: Int?,
    val available: Boolean,
)
```

Repository 负责：

```text
CarPropertyConfig 查询
属性订阅和取消
CarPropertyValue 转 domain model
set 命令发送
权限和异常处理
pending/timeout 管理
```

ViewModel 只暴露：

```kotlin
val uiState: StateFlow<HvacUiState>
fun setDriverTemperature(value: Float)
fun setPower(on: Boolean)
```

这样 UI 不需要知道 `VehiclePropertyIds.HVAC_TEMPERATURE_SET` 这种底层细节。

---

## 8. 业务常见问题

| 问题 | 排查方向 |
|---|---|
| 页面首次进入状态为空 | 是否先读当前值，VHAL 是否已有初始值 |
| 点击控制后马上又变回去 | 底层拒绝、sync 模式、其他控制源覆盖 |
| 某些车型少几个按钮 | property config 不支持对应区域或能力 |
| 行驶中按钮被禁用 | UX restrictions 或车辆安全策略 |
| 不同座位看到不同控制项 | Occupant Zone、用户、区域能力不同 |
| 实车和模拟器表现不同 | fake VHAL 配置过于理想化，实车信号和权限不同 |

---

## 9. 实战排查顺序

1. 确认 propertyId 是否标准属性或 vendor 属性。
2. 查询 `CarPropertyConfig`，确认属性存在。
3. 确认 areaId 是否在支持列表中。
4. 确认 access 是否允许读写。
5. 确认 App 权限是否 granted。
6. set 后查看 VHAL 是否收到请求。
7. 查看底层是否上报真实状态变化。
8. 确认 UI 是否只响应 callback，而不是本地误判。

---

## 10. 最小闭环练习

建议按这个顺序练习：

```text
1. 订阅车速，打印 callback
2. 读取当前档位，做一个只读状态展示
3. 读取四个车门锁状态，画出区域 UI
4. 控制 HVAC 温度，处理 callback 确认
5. 加入 unavailable/error/pending 状态
6. 用 fake VHAL 注入变化，验证 UI 同步
```

这几个场景覆盖了 Vehicle 开发最核心的模型：全局属性、区域属性、连续属性、枚举属性、读写属性、状态确认和权限问题。
