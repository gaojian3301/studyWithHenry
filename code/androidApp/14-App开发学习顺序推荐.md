# 14 App 开发学习顺序推荐

> 这套 App 文档建议和 `androidFrameworks` 目录配合阅读：App 文档解释你怎么写代码，Framework 文档解释系统为什么这样调度它。

---

## 1. 推荐总路线

```text
1. Kotlin 与协程 Flow
2. Android 应用组件与生命周期
3. UI 开发：View 系统
4. UI 开发：Jetpack Compose
5. App 架构：MVVM / MVI / Repository
6. Jetpack 核心库
7. 网络与数据存储
8. 权限、安全与隐私
9. 后台任务与系统限制
10. 性能优化与 ANR
11. Gradle 构建与工程化
12. 测试与质量保障
13. 常见业务能力专题
```

第 14 篇就是当前这篇路线图。

---

## 2. 第一阶段：先能写稳定的异步和生命周期代码

### 2.1 Kotlin 与协程 Flow

重点掌握：

- `suspend` 表达一次性任务。
- `Flow` 表达持续状态。
- `StateFlow` 暴露 UI 状态。
- `viewModelScope` 和生命周期绑定。
- 取消、异常、`callbackFlow`。

对应文档：`01-Kotlin与协程Flow.md`

### 2.2 应用组件与生命周期

重点掌握：

- `ActivityThread` 如何驱动 App。
- Application、Activity、Fragment、Service、Receiver、Provider 生命周期。
- Context 和系统服务。
- App 代码如何对应 Framework 调度。

对应文档：`02-Android应用组件与生命周期.md`

---

## 3. 第二阶段：UI 和状态

### 3.1 View 系统

View 系统是理解窗口、输入、绘制的基础。

重点掌握：

- `setContentView` 到 DecorView。
- `measure/layout/draw`。
- 事件分发。
- RecyclerView。
- WindowInsets。

对应文档：`03-UI开发-View系统.md`

### 3.2 Jetpack Compose

Compose 是新项目主力 UI 方案。

重点掌握：

- State 和重组。
- `remember` / `rememberSaveable`。
- `LaunchedEffect` / `DisposableEffect`。
- Lazy list 性能。
- Navigation Compose。
- Compose 和 View 混用。

对应文档：`04-UI开发-JetpackCompose.md`

---

## 4. 第三阶段：架构、Jetpack 和数据层

### 4.1 App 架构

重点掌握：

- MVVM。
- MVI 适用场景。
- Repository。
- UseCase。
- UI State 和事件。
- 错误模型。

对应文档：`05-App架构-MVVM-MVI-Repository.md`

### 4.2 Jetpack 核心库

重点掌握：

- Lifecycle。
- ViewModel。
- Room。
- DataStore。
- WorkManager。
- Paging。
- Hilt。

对应文档：`06-Jetpack核心库.md`

### 4.3 网络与数据存储

重点掌握：

- Retrofit。
- OkHttp 拦截器。
- Token 刷新。
- Room 缓存。
- 数据库迁移。
- MediaStore / SAF / FileProvider。

对应文档：`07-网络与数据存储.md`

---

## 5. 第四阶段：系统限制和问题排查

### 5.1 权限、安全与隐私

重点掌握：

- 运行时权限。
- AppOps。
- 通知权限。
- 前台服务类型。
- 分区存储。
- PendingIntent、Deep Link、WebView 安全。

对应文档：`08-权限安全与隐私.md`

### 5.2 后台任务与系统限制

重点掌握：

- WorkManager。
- AlarmManager。
- 前台服务。
- Doze。
- App Standby。
- 后台启动限制。

对应文档：`09-后台任务与系统限制.md`

### 5.3 性能优化与 ANR

重点掌握：

- 启动优化。
- UI 卡顿。
- StrictMode。
- ANR trace。
- 内存泄漏。
- 耗电。
- 包体积。

对应文档：`10-性能优化与ANR.md`

---

## 6. 第五阶段：工程质量和业务专题

### 6.1 Gradle 构建与工程化

重点掌握：

- AGP 和构建链路。
- 多模块。
- buildTypes / flavors。
- R8。
- 构建性能。
- CI/CD。

对应文档：`11-Gradle构建与工程化.md`

### 6.2 测试与质量保障

重点掌握：

- ViewModel 单测。
- 协程和 Flow 测试。
- Room migration 测试。
- Compose UI Test。
- Espresso。
- 线上 crash/ANR 监控。

对应文档：`12-测试与质量保障.md`

### 6.3 常见业务能力专题

重点掌握：

- 登录态和 Token。
- 推送。
- WebView。
- 分享。
- 支付。
- 地图定位。
- 蓝牙。
- 相机相册。
- 音视频。
- 下载上传。

对应文档：`13-常见业务能力专题.md`

---

## 7. 按问题反查该看什么

| 问题 | 优先看 |
|---|---|
| 页面状态混乱 | 01 + 04 + 05 |
| 生命周期回调不符合预期 | 02 + Framework AMS/ATMS |
| Compose 反复请求或重组异常 | 04 + 01 |
| RecyclerView/List 卡顿 | 03 + 10 |
| 登录态刷新异常 | 07 + 13 |
| 数据刷新 UI 不变 | 01 + 06 + 07 |
| 数据库升级崩溃 | 07 + 12 |
| 权限已给但功能不可用 | 08 + Framework 权限机制 |
| 后台任务不执行 | 09 + Framework Power/Job/Alarm |
| 前台服务启动失败 | 09 + 08 + Framework AMS/Service |
| App 启动慢 | 02 + 10 + Framework 启动流程 |
| ANR | 01 + 02 + 10 + Framework AMS/Input |
| release 崩溃 debug 正常 | 11 + 12 |
| WebView 安全问题 | 08 + 13 |
| 音视频有声无画 | 13 + Framework Media/SurfaceFlinger |

---

## 8. 和 Framework 文档的配合方式

建议遇到问题时这样读：

```text
先看 App 文档：我这边代码怎么写、常见坑是什么
再看 Framework 文档：系统服务为什么允许/拒绝/延迟/杀掉它
最后看源码：从 App API 入口一路追到 AndroidX 或 Framework 实现
```

例如：后台任务不执行。

```text
09 后台任务与系统限制
  -> WorkManager/Alarm/Service 代码写法
  -> Framework Power / Alarm / JobScheduler
  -> dumpsys jobscheduler / alarm / deviceidle
```

例如：Activity 启动慢。

```text
02 应用组件与生命周期
  -> 10 性能优化与 ANR
  -> Framework Android 启动流程 / AMS / ATMS / WMS
  -> ActivityThread / TransactionExecutor / ViewRootImpl
```

这套资料的目标不是背 API，而是形成一条稳定路径：能写、能解释、能查源码、能排线上问题。
