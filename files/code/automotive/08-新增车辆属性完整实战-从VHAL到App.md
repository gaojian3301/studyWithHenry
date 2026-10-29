# 08 在已有信号上新增车辆属性：HIDL types.hal 到 VHAL 值映射

> 适用场景：工程使用 HIDL VHAL，属性集中定义在 `types.hal` 的 `VehicleProperty` 枚举中；底层信号已经接收和解析完成，现在需要将其中一个字段暴露成车辆属性。
>
> 本文沿用这种工程组织方式，不额外创建一套 C++ 属性编号，不使用 AIDL 的 JSON 配置和缓存接口，不包含 App 实现。

---

## 1. 先明确：types.hal 定义 ID，VHAL 代码给这个 ID 填值

你看到的这种写法是 **HIDL 语法**：

```text
FACTORY_PSM_SWVERMAJOR1 = (
    0x1B05
    | VehiclePropertyGroup:VENDOR
    | VehiclePropertyType:INT32
    | VehicleArea:GLOBAL),
```

它定义了属性编号与类型信息，但并没有说明值来自哪一个底层信号。

真正的对应关系，在接收已有信号后构造属性值时建立：

```cpp
VehiclePropValue value{};
value.prop = static_cast<int32_t>(VehicleProperty::FACTORY_PSM_SWVERMAJOR1);
value.areaId = 0;
value.value.int32Values = {majorVersion};
```

其中 `majorVersion` 就是底层已经解析出来的信号字段。

完整链路：

```text
types.hal 定义 VehicleProperty::FACTORY_PSM_SWVERMAJOR1
    ↓ HIDL 编译生成 C++ 类型
配置表声明 VHAL 支持该属性
    ↓
已有信号中的主版本号字段 majorVersion
    ↓ VHAL 映射
VehiclePropValue {
    prop = FACTORY_PSM_SWVERMAJOR1,
    areaId = 0,
    int32Values = [majorVersion]
}
    ↓
写入已有属性缓存 + 通过已有通道上报变化
```

**所以，在你的工程里应先改 `types.hal`，再补配置和信号映射；不是只定义一个 ID 就结束。**

---

## 2. 需要改的文件总览

下文沿用你提供的 `FACTORY_PSM_SWVERMAJOR1` 演示完整链路。**如果它已经存在，跳过重复定义；新增其他属性时替换名称和未占用的编号。**

| 位置 | 修改内容 |
|---|---|
| 工程实际使用的 `types.hal` | 在 `VehicleProperty` 中增加枚举项 |
| 现有属性配置表，例如 `DefaultConfig.h` | 增加该 ID 的 access、changeMode、area 等配置 |
| 现有 HAL 初始化逻辑 | 配置注册后，建立未收到信号时的不可用状态 |
| 现有信号分发/接收 `.cpp` | 将已经解析的字段送入属性更新方法 |
| 现有 VHAL 实现 `.h/.cpp` | 构造 `VehiclePropValue`，更新缓存并发送事件 |
| 现有 get、订阅白名单 | 通用实现通常无需改；如有专用支持列表，需要补上新 ID |

一般不需要修改 `IVehicle.hal` 的方法，不需要新增 CarService 接口，也不需要改底层报文解析。

HIDL 2.0 的 AOSP 参考路径：

```text
hardware\interfaces\automotive\vehicle\2.0\types.hal

hardware\interfaces\automotive\vehicle\2.0\default\
  impl\vhal_v2_0\DefaultConfig.h
  impl\vhal_v2_0\EmulatedVehicleHal.h
  impl\vhal_v2_0\EmulatedVehicleHal.cpp
  common\include\vhal_v2_0\VehiclePropertyStore.h
  common\src\VehiclePropertyStore.cpp
```

OEM 工程可能放在 vendor 自己的目录，并使用自己的 HAL 类名。**应修改当前产品实际编译和运行的那份代码，而不是另建一个 EmulatedVehicleHal。**

本仓库没有你的 OEM 源码。下面以 `OemVehicleHal` 表示现有 HAL 类；store/get/event 接口对照 AOSP `android-10.0.0_r1` 的 HIDL 实现说明，**不代表推定你的系统就是 Android 10**。OEM 若改过这些接口，沿用项目既有封装。

---

## 3. 修改一：在 types.hal 中定义 ID

### 3.1 在已有 VehicleProperty 枚举中增加条目

```text
enum VehicleProperty : int32_t {
    // 原有属性……

    /**
     * PSM software major version field 1.
     *
     * @change_mode VehiclePropertyChangeMode:ON_CHANGE
     * @access VehiclePropertyAccess:READ
     */
    FACTORY_PSM_SWVERMAJOR1 = (
        0x1B05
        | VehiclePropertyGroup:VENDOR
        | VehiclePropertyType:INT32
        | VehicleArea:GLOBAL),
};
```

这是插入位置示例，**不要新建第二个 `VehicleProperty` 枚举，不要覆盖原文件其他内容**。

位域结果：

```text
0x00001B05：项目分配的属性编号
0x20000000：VENDOR
0x00400000：INT32
0x01000000：GLOBAL
--------------------------
0x21401B05：完整 propertyId
```

`0x1B05` 来自你提供的例子，不是可随意重复分配的新编号。

这里假定“信号可能在运行中更新，使用变化事件通知”，所以示例选择 `ON_CHANGE`。如果项目定义该版本信息在整个运行周期不变，应沿用其 `STATIC` 语义；**不能仅凭属性名字推断更新模式**。

### 3.2 VHAL C++ 如何引用

HIDL 构建会生成对应头文件。代码引用生成的类型，而不是手改生成文件：

```cpp
#include <android/hardware/automotive/vehicle/2.0/types.h>

using android::hardware::automotive::vehicle::V2_0::VehicleProperty;

constexpr int32_t kPsmMajorVersionProp =
        static_cast<int32_t>(VehicleProperty::FACTORY_PSM_SWVERMAJOR1);
```

最后这个 `constexpr` 只是**引用生成枚举的局部别名**，不是在另一处重新分配 ID。也可以全程直接使用：

```cpp
static_cast<int32_t>(VehicleProperty::FACTORY_PSM_SWVERMAJOR1)
```

项目已经使用 `VehicleUtils.h` 的 `toInt()` 时，可以遵循现有风格：

```cpp
toInt(VehicleProperty::FACTORY_PSM_SWVERMAJOR1)
```

注意语法区别：

```text
types.hal：VehiclePropertyGroup:VENDOR
C++：     VehiclePropertyGroup::VENDOR
```

### 3.3 生成文件和接口兼容性

修改后按项目流程重建 HIDL 类型模块和依赖它的 VHAL 模块。不要在 `out` 目录生成的 `types.h` 中手工增加枚举，否则下一次构建就会覆盖。

你提供的写法说明项目采用“在 `types.hal` 中集中维护 vendor 枚举”的方式，本文遵循它；**这不等于所有 HIDL 工程都必须修改已经冻结的 AOSP 接口**。vendor 属性也可以通过通用整型 ID 表达。

如果修改的是已发布/冻结的 HIDL 接口，必须遵守项目的接口版本、hash 和兼容性管理规则，不要把随意修改 `current.txt` 当作修复构建的方法。没有必要为了增加属性再改 `IVehicle.hal` 的 get/set 方法。

---

## 4. 修改二：在配置表声明支持该属性

在工程已有配置表中追加条目。例如 HIDL reference 的 `DefaultConfig.h` 中，`kVehicleProperties` 使用 `.config` 和 `.initialValue` 结构：

```cpp
{
    .config = {
        .prop = static_cast<int32_t>(
                VehicleProperty::FACTORY_PSM_SWVERMAJOR1),
        .access = VehiclePropertyAccess::READ,
        .changeMode = VehiclePropertyChangeMode::ON_CHANGE,
    },
    .initialValue = {
        .int32Values = {0},
    },
},
```

说明：

- `READ`：本例只暴露已有版本信号，不新增控制能力。
- `ON_CHANGE`：与上节假定的信号更新语义保持一致。
- 属性 ID 已编码 `GLOBAL`；这个全局属性不需要声明车门/座椅 area 列表，实际值的 `areaId` 为 `0`。
- `INT32` 已编码在 ID 中，运行时对应 `value.int32Values`，本例只放一个元素。
- `initialValue` 中的 `0` 是占位，**下一节必须把初始状态设为 `UNAVAILABLE`**，不能当成已收到真实版本号。

`types.hal` 中的 `@access`、`@change_mode` 注释不能代替这份运行时配置。属性是否被列出，取决于 VHAL 实际返回的配置。

已有初始化通常已经遍历配置表注册属性：

```cpp
for (const auto& declaration : kVehicleProperties) {
    mPropStore->registerProperty(declaration.config);
}
```

这段用于说明现有机制，已有时不要再增加第二轮注册。只需保证新条目在**被实际遍历的那张表**中。

---

## 5. 修改三：初始状态不能冒充真实信号值

在现有初始化循环中，配置注册后、开始接收信号前，为这个属性增加初始化分支。

例如原循环已经有 `VehiclePropConfig cfg = it.config;`，则在该属性进入通用默认值初始化之前插入：

```cpp
if (cfg.prop == static_cast<int32_t>(
        VehicleProperty::FACTORY_PSM_SWVERMAJOR1)) {
    VehiclePropValue initial{};
    initial.prop = cfg.prop;
    initial.areaId = 0;
    initial.timestamp = android::elapsedRealtimeNano();
    initial.status = VehiclePropertyStatus::UNAVAILABLE;
    initial.value.int32Values = {0};

    if (!mPropStore->writeValue(initial, /*updateStatus=*/true)) {
        ALOGE("Failed to initialize PSM major version property");
    }
    continue;
}
```

这里的 `continue` 是继续外层属性初始化循环，避免后面的通用逻辑又把它覆盖成 `AVAILABLE`。

如果 OEM 已经有“新属性默认不可用”的统一机制，就复用它，不必再加这个特例。若已有有效信号快照，则初始化完成后立即用快照更新，不必等待下一次信号变化。

---

## 6. 修改四：已有信号值 → VehiclePropValue

### 6.1 在现有 HAL 类的头文件中增加声明

```cpp
void onPsmMajorVersionSignal(int32_t majorVersion, bool valid);
```

这里的 `valid` 来自已有信号有效性判断，不能用“值是否为零”代替。

### 6.2 在现有 HAL 源文件中增加实现

下面假定 `OemVehicleHal` 采用 HIDL reference 的现有成员和方法：

```text
mPropStore             已有 VehiclePropertyStore
getValuePool()         已有 VehiclePropValue 对象池
doHalEvent(...)        已有 VehicleHal 事件上报入口
```

**这是向现有 HAL 类添加的方法，不是一份新的 HAL 服务实现。** 如果项目用自己的名字，换成已有成员和封装。

需要的头文件：

```cpp
#include <android/hardware/automotive/vehicle/2.0/types.h>
#include <log/log.h>
#include <utils/SystemClock.h>
#include <utility>
```

在项目现有命名空间中使用 HIDL 类型：

```cpp
using android::hardware::automotive::vehicle::V2_0::VehicleProperty;
using android::hardware::automotive::vehicle::V2_0::VehiclePropValue;
using android::hardware::automotive::vehicle::V2_0::VehiclePropertyStatus;
```

方法实现：

```cpp
void OemVehicleHal::onPsmMajorVersionSignal(int32_t majorVersion, bool valid) {
    VehiclePropValue value{};
    value.prop = static_cast<int32_t>(
            VehicleProperty::FACTORY_PSM_SWVERMAJOR1);
    value.areaId = 0;
    value.timestamp = android::elapsedRealtimeNano();
    value.status = valid
            ? VehiclePropertyStatus::AVAILABLE
            : VehiclePropertyStatus::UNAVAILABLE;
    value.value.int32Values = {valid ? majorVersion : 0};

    auto previous = mPropStore->readValueOrNull(value.prop, value.areaId);
    const bool changed =
            previous == nullptr
            || previous->status != value.status
            || previous->value.int32Values.size() != 1
            || previous->value.int32Values[0] != value.value.int32Values[0];

    if (!mPropStore->writeValue(value, /*updateStatus=*/true)) {
        ALOGE("Failed to store PSM major version property");
        return;
    }

    if (changed) {
        doHalEvent(getValuePool()->obtain(value));
    }
}
```

最重要的对应关系仍然是：

```cpp
value.prop = static_cast<int32_t>(VehicleProperty::FACTORY_PSM_SWVERMAJOR1);
value.value.int32Values = {majorVersion};
```

版本信号的范围、无效编码和缩放规则需要沿用现有信号协议，本文不擅自假设它一定是一个字节或一定在 `0..255`。若现有解析层还没有完成校验，应在填入属性值之前按实际协议校验。

另外：

1. 该方法应运行在项目已有的串行信号处理上下文中。store 单次操作线程安全，不等于“读取旧值 → 比较 → 写入 → 上报”这整段操作自动串行；多个线程可能更新同一属性时，接入已有队列或同步机制。
2. `UNAVAILABLE` 中的 `0` 只是占位，消费方必须尊重状态。
3. `elapsedRealtimeNano()` 用于 VHAL 时间戳，不要直接塞 MCU 原始计数器。
4. 本例比较值和状态，避免相同值的周期信号重复触发 `ON_CHANGE`。如果项目已有统一去重，上述比较应复用或移入现有机制，而不是重复维护。
5. 信号超时/断连时也应调用 `onPsmMajorVersionSignal(0, false)`，恢复后再传入有效值。

### 6.3 HIDL 与之前 AIDL 写法的关键区别

本文对照的 HIDL store 接口是：

```cpp
bool writeValue(const VehiclePropValue& propValue, bool updateStatus);
```

读取接口是：

```cpp
std::unique_ptr<VehiclePropValue> readValueOrNull(
        int32_t prop, int32_t area = 0, int64_t token = 0) const;
```

**这个 HIDL store 写入成功不会自动帮你调用 `doHalEvent`，需要走现有显式事件通道。**

因此不能混用之前 AIDL 示例中的：

```text
mServerSidePropStore->writeValue(对象池指针)
result.ok()
store.setOnValueChangeCallback(...)
```

这不仅是命名空间不同，返回类型、参数和上报机制也不同。若 OEM 自己封装了自动上报缓存，则使用该封装，不要再显式上报一次。

---

## 7. 修改五：在已有信号入口接上方法

假设已有结构体已经包含解析好的字段：

```cpp
void OemVehicleHal::onPsmStatusReceived(const ExistingPsmStatus& status) {
    // 保留现有其他字段处理，仅增加这一句。
    onPsmMajorVersionSignal(status.swVersionMajor1,
                            status.swVersionMajor1Valid);
}
```

`ExistingPsmStatus` 和字段名称是示例，要替换成现有代码中真实名称，不需要重新创建一套信号结构。

如果已有代码是按信号 ID 分发，则在原 `switch` 中增加对应分支：

```cpp
case ExistingSignalId::PSM_SWVERMAJOR1:
    onPsmMajorVersionSignal(decodedValue, valid);
    break;
```

两种接法选现有工程使用的那一种。

这里完成了两个编号空间之间的映射：

```text
底层信号 ID：ExistingSignalId::PSM_SWVERMAJOR1
       ↓ 取出 decodedValue
VHAL 属性 ID：VehicleProperty::FACTORY_PSM_SWVERMAJOR1
```

**底层 signalId 不必等于 VHAL propertyId。** 如果项目已经通过映射表完成这一步，就在现有表中增加“信号 → 属性”的条目，不要额外写一套 switch。

---

## 8. get 和 subscribe 通常不用重新写

### 8.1 get 继续读同一份 store

HIDL reference 普通属性的 get 路径已经采用：

```cpp
auto internalPropValue = mPropStore->readValueOrNull(requestedPropValue);
if (internalPropValue != nullptr) {
    v = getValuePool()->obtain(*internalPropValue);
}
```

新属性的配置已注册，信号回调也已写入同一个 store，就可通过原有 get 路径读取。

只有项目的 get 先经过支持 ID 白名单或特定 switch 时，才需要把新 ID 加进去。不要为每个新属性再新增一个 HIDL get 方法。

### 8.2 事件继续走原有通道

本文 HIDL 参考链路：

```text
已有信号回调
    → onPsmMajorVersionSignal
    → mPropStore->writeValue
    → doHalEvent
    → VehicleHalManager 的事件处理与订阅分发
    → IVehicleCallback
    → CarService
```

只更新缓存不调用事件入口，可能出现“主动读取得到新值，但订阅一直没有变化”的现象。

已有通用订阅分发通常不需要增加分支；如果 OEM 对可订阅属性做了额外过滤，需要增加新 ID。

---

## 9. 这个例子需要增加 set 吗

不需要。`FACTORY_PSM_SWVERMAJOR1` 在本文按只读版本信息处理，底层已有状态信号，不代表存在修改版本号的控制接口。

如果真正新增的是一个可控属性，还要额外做：

```text
配置改为 READ_WRITE
    + 现有 set 分发增加新 VehicleProperty 枚举
    + 将请求值传给现有底层控制接口
```

实际状态仍然由信号回传后更新，不能在 set 被受理时直接把请求值冒充真实状态。

---

## 10. 还要留意哪些现有工程限制

| 检查项 | 处理方式 |
|---|---|
| 新编号是否重复 | 查已有 `types.hal` 和 OEM 编号分配表 |
| HIDL 类型是否重新生成 | 重建工程现有接口模块及依赖模块，不手改生成头文件 |
| 配置是否真的返回给上层 | 确认新条目在当前产品加载的属性表中 |
| 信号回调是否真的执行 | 不能只增加一个无人调用的新方法 |
| 初始状态是否有效 | 未收到信号时不可伪装成真实的零值 |
| 写缓存与 get 是否使用同一个对象 | 不另建一份只写不读的缓存 |
| 上报是否遗漏或重复 | 按现有 HIDL/OEM 通道上报一次 |
| vendor 权限/属性白名单 | 沿用现有策略；有逐 ID 映射时补上新 ID |

如果客户端已经具备对应权限，属性类型和区域用法也一致，客户端确实主要是换 ID，本文不重复客户端实现。

---

## 11. 参考源码与现有文档

当前目录的 `03-VehicleHAL-VHAL详解.md` 同时介绍 HIDL/AIDL；本文明确选择你贴出的 **HIDL** 写法。结合 `02-CarPropertyManager与VehicleProperty.md` 理解属性结构，结合 `07-CarService源码阅读路线.md` 对照读取和事件通路即可。

下列链接仅用于核对 HIDL 参考接口，不代表你的 OEM 自定义属性存在于 AOSP：

| 参考文件（android-10.0.0_r1） | 关注点 |
|---|---|
| [types.hal](https://android.googlesource.com/platform/hardware/interfaces/+/refs/tags/android-10.0.0_r1/automotive/vehicle/2.0/types.hal) | `VehicleProperty` 与位域定义 |
| [DefaultConfig.h](https://android.googlesource.com/platform/hardware/interfaces/+/refs/tags/android-10.0.0_r1/automotive/vehicle/2.0/default/impl/vhal_v2_0/DefaultConfig.h) | `kVehicleProperties` 配置结构 |
| [EmulatedVehicleHal.cpp](https://android.googlesource.com/platform/hardware/interfaces/+/refs/tags/android-10.0.0_r1/automotive/vehicle/2.0/default/impl/vhal_v2_0/EmulatedVehicleHal.cpp) | 初始化、`readValueOrNull`、`writeValue`、`doHalEvent` |
| [VehiclePropertyStore.h](https://android.googlesource.com/platform/hardware/interfaces/+/refs/tags/android-10.0.0_r1/automotive/vehicle/2.0/default/common/include/vhal_v2_0/VehiclePropertyStore.h) | HIDL 缓存接口的参数和返回值 |

**对应你工程的最短修改链就是：`types.hal` 加枚举 → 配置表加条目 → 已有信号字段填进该枚举对应的 `VehiclePropValue` → 写缓存并上报。**
