# 06 模拟器与 Fake VHAL 调试

> 没有实车硬件时，AAOS 模拟器和 Fake VHAL 是理解 CarService、CarPropertyManager、Vehicle HAL 链路的最佳入口。它能帮你验证 Framework 和 App 逻辑，但不能替代最终实车验证。

---

## 1. Fake VHAL 能解决什么问题

Fake VHAL 可以模拟车辆属性：

- 车速变化。
- 档位变化。
- 空调状态。
- 车门锁状态。
- 座椅、车窗、灯光等部分属性。
- 电量、油量等状态。

它适合用来验证：

```text
VHAL 事件上报
  -> CarService 分发
  -> CarPropertyManager callback
  -> App UI 更新
```

也适合验证：

```text
App setProperty
  -> CarPropertyService
  -> VehicleHal
  -> Fake VHAL
  -> 回调确认
```

---

## 2. 模拟器环境的价值和边界

模拟器适合：

- 学习 API。
- 调试 App 订阅逻辑。
- 验证 propertyId、areaId、callback。
- 编写自动化测试。
- 排查 Framework 层逻辑。

模拟器不适合完全验证：

- 实车 CAN 信号时序。
- ECU ready 时机。
- 车辆电源状态。
- 控制命令失败率。
- 真实安全策略。
- 车厂 vendor VHAL 映射。

所以工作流通常是：

```text
模拟器打通功能闭环
  -> 台架验证 VHAL 映射
  -> 实车验证状态、时序、安全策略
```

---

## 3. 启动后先看 CarService 状态

常用命令：

```bash
adb shell dumpsys car_service
```

重点看：

- CarService 是否启动。
- Vehicle HAL 是否连接。
- CarPropertyService 是否正常。
- 已注册的客户端和订阅。
- 属性配置数量。
- 最近的错误日志。

也可以看服务列表：

```bash
adb shell service list | grep -i car
```

---

## 4. 查看日志

常用方向：

```bash
adb logcat | grep -i "carservice\|carproperty\|vehiclehal\|vhal"
```

如果知道具体 tag，可以缩小：

```bash
adb logcat -s CarService CarPropertyService VehicleHal
```

不同 Android 版本 tag 会变化。源码排查时可以在 `packages/services/Car` 中搜索：

```text
Slog
Log
TAG
```

---

## 5. 注入车辆属性变化

不同 AAOS 版本和模拟器镜像提供的工具不完全一样，常见方式包括：

- emulator extended controls 中的 car data 页面。
- `cmd car_service` 相关命令。
- VHAL debug shell 命令。
- vendor 自带调试工具。
- 修改 fake VHAL 默认配置。
- 写测试代码直接调用 CarPropertyManager。

如果命令不确定，先看：

```bash
adb shell cmd car_service help
adb shell dumpsys car_service --help
```

有些版本没有完整 help，需要根据源码或镜像能力确认。

---

## 6. 用 App 验证订阅链路

最小验证：订阅车速。

```kotlin
val callback = object : CarPropertyManager.CarPropertyEventCallback {
    override fun onChangeEvent(value: CarPropertyValue<Any>) {
        Log.d("VehicleDemo", "property=${value.propertyId}, area=${value.areaId}, value=${value.value}")
    }

    override fun onErrorEvent(propertyId: Int, areaId: Int) {
        Log.e("VehicleDemo", "error property=$propertyId area=$areaId")
    }
}

propertyManager.registerCallback(
    callback,
    VehiclePropertyIds.PERF_VEHICLE_SPEED,
    CarPropertyManager.SENSOR_RATE_NORMAL,
)
```

然后注入车速变化，观察：

```text
Fake VHAL
  -> CarService log
  -> App log
  -> UI state
```

如果 Fake VHAL 有变化但 App 没收到，重点看权限、订阅参数和 property config。

---

## 7. 用 dumpsys 看属性和订阅

`dumpsys car_service` 是最重要的入口之一。

常见关注点：

```text
CarPropertyService
  property configs
  clients
  subscriptions
  permission checks
  HAL connection
```

当 UI 不刷新时，先问：

1. App 是否真的注册了 callback。
2. 注册的 propertyId 是否正确。
3. 是否订阅了正确 area。
4. VHAL 是否上报了事件。
5. CarService 是否把事件分发给该客户端。
6. App 进程是否还活着，callback 是否被取消。

---

## 8. Fake VHAL 配置

Fake VHAL 通常会有默认 property config，描述支持哪些属性。

你可以从源码中找：

```text
DefaultConfig
FakeVehicleHardware
VehicleHalManager
VehiclePropertyStore
```

不同版本命名不同，搜索关键词：

```text
PERF_VEHICLE_SPEED
HVAC_TEMPERATURE_SET
DOOR_LOCK
VehiclePropConfig
```

看配置时重点关注：

- propertyId。
- access。
- changeMode。
- areaConfigs。
- min/max value。
- sample rate。
- 初始值。

很多“App 代码没问题但读不到”的情况，其实是 fake VHAL 没配置该属性。

---

## 9. 调试 setProperty

以空调温度为例：

```text
App 调 setFloatProperty
  -> log App 请求值
  -> CarPropertyService 权限检查
  -> VehicleHal set
  -> Fake VHAL 更新内部 store
  -> Fake VHAL 触发 property event
  -> App callback 收到新值
```

如果 set 没效果，按顺序看：

| 层级 | 检查点 |
|---|---|
| App | propertyId、areaId、value 类型是否正确 |
| 权限 | 是否有写权限，是否是系统/特权 App |
| Config | access 是否包含 write，area 是否支持 |
| CarService | 是否打印 set 错误或权限拒绝 |
| VHAL | set 是否被调用，是否返回错误 |
| 回调 | 是否上报了最终状态 |

---

## 10. 模拟器和实车差异

| 维度 | 模拟器/Fake VHAL | 实车/Vendor VHAL |
|---|---|---|
| 属性配置 | 通常较固定、理想化 | 车型、配置、区域差异很大 |
| 状态变化 | 可控、简单 | 有延迟、失败、无效状态 |
| 电源状态 | 简化 | ACC、点火、休眠、唤醒复杂 |
| 权限 | 开发镜像可能更宽松 | 量产镜像更严格 |
| 控制命令 | 常常直接成功 | 可能被 ECU、车辆状态、安全策略拒绝 |
| 信号质量 | 稳定 | 有 timeout、invalid、抖动、丢包 |

因此不要写出只适配模拟器的车控逻辑。真实代码必须处理：

- unsupported。
- unavailable。
- error。
- timeout。
- permission denied。
- pending。
- vehicle not ready。

---

## 11. 推荐调试闭环

1. `dumpsys car_service` 确认服务和 VHAL 正常。
2. 查询 `propertyList`，确认属性存在。
3. 打印目标属性的 `CarPropertyConfig`。
4. 注册 callback，并在日志里打印 propertyId、areaId、status、timestamp、value。
5. 通过 fake VHAL 注入变化。
6. 验证 App callback 是否收到。
7. 触发 App setProperty。
8. 验证 VHAL 是否收到 set，是否回调最终状态。
9. 加入异常状态测试。
10. 再迁移到台架或实车验证。

---

## 12. 自动化测试思路

可以把车控逻辑拆成两层测试：

```text
Repository 单元测试
  -> fake CarPropertyDataSource
  -> 验证 domain state、pending、error、timeout

集成测试
  -> AAOS emulator / fake VHAL
  -> 验证 CarPropertyManager 到 UI 的真实链路
```

测试重点：

- 属性不存在时 UI 怎么显示。
- 权限失败时怎么降级。
- set 后 callback 成功。
- set 后超时。
- callback 回 unavailable。
- 高频属性不会刷爆 UI。

车控代码最大的风险不是“正常路径不通”，而是车辆状态复杂时没有处理异常分支。
