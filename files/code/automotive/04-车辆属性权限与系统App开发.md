# 04 车辆属性权限与系统 App 开发

> Automotive 开发里，能不能调用 API 往往不是代码写法问题，而是权限、签名、安装位置、SELinux、VHAL 配置共同决定的结果。车辆属性涉及安全和隐私，权限模型比普通 App 更严格。

---

## 1. 为什么车辆属性权限很严格

车辆属性不是普通手机传感器。

它可能涉及：

- 行车安全：车速、档位、制动、转向。
- 车辆控制：空调、车门、车窗、座椅、后备箱。
- 隐私信息：位置、行程、驾驶行为。
- 车辆状态：电量、油量、充电、故障码。
- 法规要求：行驶中 UI 限制、驾驶分心限制。

所以 AAOS 不会允许普通三方 App 随意读写所有车辆属性。

---

## 2. 权限检查发生在哪里

典型链路：

```text
App/System App
  -> CarPropertyManager
  -> Binder 调用 ICarProperty
  -> CarPropertyService
  -> 权限检查
  -> VehicleHal
  -> VHAL
```

`CarPropertyManager` 只是客户端包装。真正关键的权限检查通常在 `CarPropertyService` 或相关 helper 中完成。

这意味着：

```text
即使 App 代码能编译，也不代表运行时能访问车辆属性。
```

---

## 3. 常见权限类型

Android Automotive 中常见几类权限：

| 类型 | 含义 |
|---|---|
| normal permission | 普通权限，安装时自动授予，车辆属性里不多见 |
| dangerous permission | 运行时权限，类似位置权限 |
| signature permission | 只有同平台签名或指定签名应用可获得 |
| privileged permission | 预装在 priv-app 且白名单允许的应用可获得 |
| vendor permission | 车厂自定义权限，通常给自家系统应用 |
| SELinux/HAL 权限 | 系统进程访问 HAL、文件、节点、服务时需要 |

车控类能力通常不是普通应用市场 App 能拿到的权限。

---

## 4. App 成为系统能力调用方的条件

一个车控系统 App 可能需要：

```text
放入 system_ext/priv-app 或 product/priv-app
  + 使用平台签名或车厂签名
  + AndroidManifest 声明 car permission
  + privapp-permissions 白名单授予
  + 满足 SELinux 和 service 访问策略
```

示例 manifest：

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.car.permission.CAR_SPEED" />
    <uses-permission android:name="android.car.permission.CONTROL_CAR_CLIMATE" />

    <application
        android:label="Vehicle Control"
        android:persistent="false" />
</manifest>
```

是否能拿到权限，还取决于权限保护级别和安装位置。

---

## 5. priv-app 权限白名单

Android 对 privileged permission 有白名单要求。

常见文件位置：

```text
system/etc/permissions/privapp-permissions-*.xml
system_ext/etc/permissions/privapp-permissions-*.xml
product/etc/permissions/privapp-permissions-*.xml
vendor/etc/permissions/privapp-permissions-*.xml
```

示例：

```xml
<permissions>
    <privapp-permissions package="com.example.carcontrol">
        <permission name="android.car.permission.CONTROL_CAR_CLIMATE" />
        <permission name="android.car.permission.CAR_SPEED" />
    </privapp-permissions>
</permissions>
```

如果 App 在 priv-app 里声明了 privileged 权限，但白名单没有授予，系统可能拒绝启动或打印权限违规日志。

---

## 6. 车辆属性和权限的映射

一个 property 可能对应读权限和写权限。

例如：

| 属性 | 读权限 | 写权限 |
|---|---|---|
| 车速 | `CAR_SPEED` | 通常不可写 |
| 空调温度 | 读 HVAC 权限 | 控制 HVAC 权限 |
| 车门锁 | 读车门权限 | 控制车门权限 |
| 座椅加热 | 读座椅权限 | 控制座椅权限 |

实际映射由 AAOS 版本和车厂定制决定。读源码时要找：

```text
propertyId -> readPermission/writePermission
```

常见位置可能在 `CarPropertyService`、`PropertyHalService`、`VehiclePropertyIds` 相关配置或 vendor 扩展中。

---

## 7. 系统 App 开发常见形态

车载系统 App 常见部署方式：

```text
packages/apps/Car/...
packages/services/Car/...
vendor/<oem>/apps/...
product/priv-app/...
system_ext/priv-app/...
```

构建方式可能是：

- Android.bp / Soong。
- Android.mk 老项目。
- Gradle 构建后预装。
- AOSP module 直接编入系统镜像。

系统 App 和普通 App 的区别：

| 维度 | 普通 App | 系统/特权 App |
|---|---|---|
| 安装来源 | 用户安装或应用市场 | 系统镜像预装 |
| 权限 | 受普通权限模型限制 | 可获得 signature/privileged 权限 |
| 签名 | 开发者签名 | 平台签名或车厂签名 |
| 升级 | 应用市场或 adb install | OTA、预装更新、系统分区策略 |
| 能力 | 受限访问系统服务 | 可访问更多 car/system API |

---

## 8. API 可见性和 hidden API

AAOS 里有些能力属于公开 SDK，有些属于 system API、hidden API 或 @SystemApi。

开发时要区分：

| API 类型 | 使用方 |
|---|---|
| public API | 普通 App 可编译使用 |
| system API | 系统应用或使用 system SDK 的模块 |
| hidden API | 不建议普通 App 直接使用，系统内模块可能访问 |
| test API | 测试或内部验证场景 |

车厂系统 App 通常会使用 system SDK 或平台源码编译。普通 Android Studio App 直接依赖公开 SDK，可能看不到某些 car API。

---

## 9. SELinux 与 HAL 访问

App 一般不直接访问 VHAL，主要是 CarService 访问 HAL。但系统集成时可能遇到 SELinux 问题。

典型现象：

```text
avc: denied
```

排查方向：

- 哪个进程访问哪个 service、file、socket、binder。
- 是否是 car service domain。
- VHAL service 是否正确注册。
- vendor service context 是否配置。
- neverallow 是否禁止该访问。

不要看到 `avc: denied` 就直接 allow。车载系统涉及安全边界，要确认访问是否合理。

---

## 10. 调试权限问题

常用命令：

```bash
adb shell dumpsys package com.example.carcontrol
adb shell pm list packages -f | grep carcontrol
adb shell dumpsys car_service
adb logcat | grep -i "permission\|carservice\|carproperty"
```

重点看：

- App 安装在哪个分区。
- App 实际 uid、签名、targetSdk。
- manifest 声明了哪些权限。
- 哪些权限 granted=true。
- 是否有 privapp permission violation。
- CarService 日志中拒绝的 propertyId 和 uid。

---

## 11. 常见问题

| 现象 | 可能原因 |
|---|---|
| `SecurityException` | 缺少 car permission，或权限未授予 |
| manifest 写了权限但仍失败 | 权限是 signature/privileged，普通 App 无法获得 |
| 系统 App 有权限，更新版没权限 | 安装分区、签名、sharedUserId、白名单不一致 |
| debug 签名可用，release 不可用 | 签名变了，signature permission 不再匹配 |
| 读属性可以，写属性失败 | 只有读权限，没有控制权限 |
| App 编译不过 car API | SDK 不包含 system API 或依赖 car-lib 不正确 |
| VHAL 正常但 App 访问失败 | Framework 权限层拒绝，不是底层问题 |

---

## 12. 开发建议

- 先确认目标 App 是普通 App、系统 App，还是平台内置模块。
- 车辆属性能力要做权限矩阵，不要只看 API 文档。
- 控制类功能要明确安全边界和车辆状态限制。
- 读写 property 前先查 config 和权限失败路径。
- 把 `SecurityException`、unavailable、not supported 作为正常分支处理。
- 实车项目中，权限、签名、分区、SELinux 要和系统集成一起设计。
