# Input 机制详解：从触摸事件到 InputDispatcher 分发

> 接在 WMS 后面理解最顺：WMS 决定哪个窗口能接收输入（见 `11_WMS机制详解`），Input 系统负责把触摸、按键、旋钮、遥控器等事件送到正确窗口，App 最终在 `View.dispatchTouchEvent()`、`onKeyDown()` 里收到事件（View 层手势分发属于 App 知识，细节见 `androidApp/03-UI开发-View系统`）。本篇以**单屏为主线**；多屏输入与 per-display 焦点仅放一小节衔接，详见 `23_多屏与多窗口机制详解` 第 9 节。

本篇是**分层栈型**知识：输入事件自底向上穿过内核、native inputflinger、system_server（IMS/WMS）、App 四层。请按自底向上的顺序读，上层现象（"点不动""按键没反应"）最终都要回到底层原因。

---

## 1. 开场：输入系统要回答的三个问题

输入系统存在的全部意义，就是为了回答下面三个问题。任何一次"点不动""按键没反应""事件延迟高"的 bug，本质上都是这三个问题里某一个答错了：

| 问题 | 含义 | 答错时的典型现象 |
|---|---|---|
| **事件从哪来？** | 硬件 → 内核 `evdev` → `/dev/input/eventX` → `EventHub` 读出原始事件 | 内核层就没事件（`getevent` 抓不到）→ 多半是驱动/固件/TP（Touch Panel）问题 |
| **事件给谁？** | `InputDispatcher` 根据焦点窗口、touchable region、层级，命中目标 `WindowHandle` | 焦点窗口错、窗口被遮挡、flag 设错 → 点击无反应或点错窗口 |
| **慢了谁受罚？** | 事件发出后 App 必须在阈值内 `finishInputEvent`，否则计为 Input ANR | App 主线程卡住、等 Binder、等锁 → `Input dispatching timed out` |

> 「**Android 视角**」输入系统横跨内核与用户态：下半部（kernel `input` 子系统）只负责"采集并排队"，真正的"理解"与"分发决策"全部在用户态的 native `inputflinger` 和 Java 层 `InputManagerService` 完成。vendor 开发者最常改的 `idc`/`kl`/`kcm` 配置文件，都作用于用户态这一侧。

### 1.1 一次触摸事件的全景

```text
   ┌─────────── 内核层 (kernel) ───────────┐
   触摸屏硬件
     │ 电容扫描 → 坐标/压力
     ▼
   input 子系统 (driver)
     │ 协议 B (MT slot) 描述多指
     ▼
   /dev/input/eventX   （evdev 字符设备，字节流）
   └────────────────────────────────────────┘
                   │  read()
   ┌────── native inputflinger (system_server 进程) ──────┐
                   ▼
   EventHub       收集 /dev/input 全部设备 + 原始事件
                   ▼
   InputReader    设备枚举 / idc 配置 / 坐标校准 / 标准化
                   │  产出 EventEntry (KeyEntry / MotionEntry …)
                   ▼
   InputDispatcher 焦点窗口 + hit 测试 + 分派策略
                   │  InputChannel (socket pair)
   └───────────────────────────────────────────────────────┘
                   ▼
   ┌────────── App 进程 ──────────┐
   InputEventReceiver (Looper 监听 fd)
     ▼
   ViewRootImpl → DecorView.dispatchTouchEvent
     ▼
   Activity → ViewGroup → View.onTouchEvent / onKeyDown
   └──────────────────────────────┘
```

记住这条链：**硬件不决定给谁，内核不决定给谁，只有 `InputDispatcher` 决定给谁**。

### 1.2 怎么读这篇文档

- 想排查"点不动"：`第 6 节（Dispatcher 分派）` → `第 12 节（调试工具箱）` → `第 13 节（排查表）`。
- 想查 Input ANR：`第 7 节（状态机）` → `第 8 节（ANR 语义）`。
- 想改车机按键/旋钮映射：`第 10 节（注入与虚拟设备）` → `第 11 节（按键与 KeyCharacterMap）`。
- 想顺着源码走一遍：`第 15 节（读源码路线）` → `第 16 节（源码路径速查）`。

---

## 2. 内核层衔接：evdev / input 子系统

用户态之上的所有"设备理解"都建立在 `/dev/input/eventX` 这份字节流之上，所以先搞清楚内核给了什么。

### 2.1 evdev 与 `/dev/input/eventX`

Linux `input` 子系统分三层：driver（具体硬件，如 `touchscreen`/`gpio-keys`）→ `input core`（统一事件模型）→ `evdev`（向用户态暴露字符设备）。每个物理设备会在 `/dev/input/` 下生成一个 `eventX` 节点，设备元信息（vendor/product/name）在 `/sys/class/input/inputX/`。

一个原始事件在内核态是 `struct input_event`：

```c
struct input_event {
    struct timeval time;  // 事件时间戳（秒+微秒）—— 输入延迟分析的关键起点
    __u16 type;           // EV_KEY / EV_ABS / EV_SYN / EV_REL ...
    __u16 code;           // 具体键码或轴，如 ABS_MT_POSITION_X、KEY_VOLUMEUP
    __s32 value;          // 键值/坐标/状态
};
```

`EV_SYN`（同步事件，`SYN_REPORT`）用于"一帧采样结束"的分隔——用户态靠它来切分一次完整采样。

### 2.2 用 `getevent` 读原始事件

```bash
# 列出所有输入设备及其配置
adb shell getevent -p

# 实时打印原始事件；-l 显示符号名，-t 显示时间戳
adb shell getevent -lt
# 输出示例（手指按下）：
# [   12345.678901] /dev/input/event3: EV_ABS       ABS_MT_TRACKING_ID   00000000
# [   12345.678901] /dev/input/event3: EV_ABS       ABS_MT_POSITION_X    000001e0
# [   12345.678901] /dev/input/event3: EV_ABS       ABS_MT_POSITION_Y    000002a0
# [   12345.678901] /dev/input/event3: EV_SYN       SYN_REPORT           00000000
```

`getevent` 抓不到事件 = 问题在内核/驱动/固件，**用户态无权甩锅**。这是"没收到事件"分层二分法的第一刀（见 `第 12 节`）。

### 2.3 触摸屏的协议 B（MT slot）一句话直觉

多点触摸使用 **Multitouch Protocol B**：内核用"slot"给每根手指编号（`ABS_MT_SLOT`），每根手指用 `ABS_MT_TRACKING_ID` 标识"这是哪根手指、是否离开"，坐标用 `ABS_MT_POSITION_X/Y` 上报，最后用 `SYN_REPORT` 表示"这一帧手指状态齐了"。`InputReader` 负责把"slot + tracking id"翻译成 Android 的 pointer index（即 `MotionEvent.getPointerId()` 的来源）。协议 A 已废弃，车机 TP 驱动一般只用 B。

> 「**Android 视角**」`ABS_MT` 的坐标单位、方向（是否需翻转/缩放）由 vendor 的 `idc` 文件（`touch.deviceType`、`touch.orientationAware` 等）和 TP 固件共同决定。坐标漂移/跳点八成在 idc 或固件，而非 framework。

### 2.4 内核给用户态的"元信息"：ioctl 与 input_id

`EventHub` 打开 `eventX` 后，不光 `read()` 拿事件，还会用 `ioctl` 取设备元信息，最关键是 `EVIOCGID`（`struct input_id`：bustype/vendor/product/version）和 `EVIOCGBIT`（该设备支持的 type/code 位图，用来判断"这是不是触摸屏、支持哪些轴"）。`InputReader` 据此选 `InputMapper`——这就是为什么"设备类型识别错"会导致整个加工链路走偏（比如把触摸屏当鼠标，坐标全乱）。

```c
// EventHub 侧等价逻辑（简化）
struct input_id id;
ioctl(fd, EVIOCGID, &id);          // 拿到 vendor/product，用于匹配 idc/kl 文件名
unsigned long ev_bits[...];
ioctl(fd, EVIOCGBIT(0, sizeof(ev_bits)), ev_bits);  // 看支持 EV_KEY/EV_ABS/EV_REL 哪些
```

> 记忆：内核不关心"这是方向盘还是键盘"，只报 id + 能力位图；用户态用这套信息去 `idc/kl/kcm` 里查"它到底是谁"。

---

## 3. InputManagerService 架构

`InputManagerService`（IMS）是 Java 层的输入总管，但它只做"组织与桥接"——重活（读设备、分派）都在 native。

### 3.1 三层结构

```text
  system_server (Java)
  ┌─────────────────────────────────────┐
  │ InputManagerService (IMS)            │  ① 系统服务，注册到 ServiceManager
  │   └─ NativeInputManager (JNI 桥)     │  ② Java ↔ native 的唯一桥，也回调 WMS
  └─────────────────────────────────────┘
                   │ JNI
  ┌─────────────────────────────────────┐
  │ InputManager (native, in inputflinger)│ ③ 真正起线程的工厂
  │   ├─ InputReaderThread  (读线程)      │
  │   └─ InputDispatcherThread(分派线程)  │
  └─────────────────────────────────────┘
```

`InputManager::initialize()` 会先后 `new InputReader`（持有 `EventHub`）和 `new InputDispatcher`，各自挂到一个 `InputThread` 上跑独立循环。两个线程**职责严格分离**：reader 只负责"把原始事件变成 Android 事件"，dispatcher 只负责"决定给谁、等待完成"。二者之间通过一个 `QueuedInputListener` / 内部队列衔接。

### 3.2 IMS 与 WMS 的耦合点

IMS 不是孤立的，它持有 `WindowManagerCallbacks`（实现在 WMS 侧），用于：

- 询问"当前焦点窗口是谁"（拦截链、ANR 判定要用）；
- 通知 WMS "输入事件被消费/丢弃"（用于 `InputMonitor` 同步）；
- 把 `KeyEvent` 交给 `PhoneWindowManager` 做系统级拦截裁决（见 `第 11 节`）。

### 3.3 InputChannel 的配对与注册

App 与 system_server 之间不是 Binder，而是一对 **`InputChannel`——本质上是 `socketpair(AF_UNIX, SOCK_SEQPACKET)`**。一端在 App（`ViewRootImpl`），一端在 `InputDispatcher` 的连接（connection）。配对发生在 WMS `addWindow()` 时：

```text
WMS.addWindow()
  └─ Session.relayoutWindow / addWindow
       └─ WindowState.openInputChannel()
            └─ InputChannel.openInputChannelPair(name)   // 生成 pair[0], pair[1]
                 ├─ pair[0] → 通过 IWindowSession 传回 App（ViewRootImpl.setView）
                 └─ pair[1] → mInputManager.registerInputChannel(channel, windowHandle, ...)
                      └─ InputDispatcher 建立 Connection，监听其 fd
```

> 关键直觉：**一个窗口 = 一个 InputChannel = 一个 Connection**。`InputDispatcher` 维护的是"窗口 handle → connection"的映射；事件能不能发出去，取决于这个 connection 的 fd 是否可写、队列是否积压。

`registerInputChannel` 之后，`InputDispatcher` 会把该窗口的 `InputWindowHandle`（携带 frame、touchable region、flags、displayId 等）纳入其窗口列表——这是第 6 节 hit 测试的数据来源。

---

## 4. InputReader：从设备读取并"理解"事件

`InputReader`（`frameworks/native/services/inputflinger/reader/`）运行在独立线程，循环调用 `EventHub::getEvents()` 拿原始事件，然后分设备类型交给对应的 `InputMapper` 加工。

### 4.1 EventHub 读事件

`EventHub` 是 `InputReader` 与 `/dev/input` 之间的唯一接口：

- 用 `inotify` 监听 `/dev/input` 的增删，设备热插拔（USB 键盘、外接旋钮）即时感知；
- 维护所有 `eventX` 的 fd，统一 `epoll` 等待；
- `getEvents()` 返回一批 `RawEvent`（含设备 id、type、code、value、时间戳）。

EventHub 不做"理解"，只做"采集 + 设备枚举"。

### 4.2 设备枚举与 idc 配置

每个设备在 `EventHub::openDeviceLocked()` 时会被识别类型，并加载配置，优先级链（vendor 最常改这里）：

```text
  /system/usr/idc/<device-name>.idc          (设备级，最优先)
  /data/system/devices/idc/<device-name>.idc (运行时覆盖)
  /system/usr/keylayout/<device-name>.kl     (按键布局)
  /system/usr/keychars/<device-name>.kcm     (按键字符映射)
  <built-in 默认>                             (最低优先)
```

`idc`（Input Device Configuration）里常见开关：`deviceType`（touchscreen/keyboard/dpad/…）、`touch.deviceType`、`touch.orientationAware`、`keyboard.layout` 等。`InputReader` 据此选择合适的 `InputMapper`（如 `MultiTouchInputMapper`、`KeyboardInputMapper`、`SwitchInputMapper`）。车机里触摸屏坐标不准、旋钮被识别成键盘，多半是 idc 配错。

### 4.3 事件加工（识别相关处理）

注意：`InputReader` 做的不是"手势识别"（双指缩放、滑动这种语义在 App 层 `androidApp/03` 处理），而是**设备语义的标准化**：

- 把协议 B 的 slot/tracking_id 归一成 Android 的多指 pointer 模型；
- 坐标按 idc 做翻转、缩放、display 映射；
- 按键做去抖、重复（repeat）处理；
- 把 raw 轴数据（如滚轮 `REL_WHEEL`）转成标准事件。

坐标变换是这里最容易出车机 bug 的一环：`MultiTouchInputMapper` 会按 idc 的 `x/y 偏移与缩放`、`orientationAware`（横竖屏是否翻转）、`viewport`（映射到哪个 display 的哪块矩形）把 TP 原始坐标换算成**屏幕像素坐标**。换算错 → 触摸点偏移、旋转 90°、左右镜像——这些都不是 App 的锅。

### 4.4 标准化后的产出：EventEntry

读线程加工完，通过 `QueuedInputListener` 把一批事件投递给 dispatcher 线程，进入 `InputDispatcher` 的入站队列（inbound 一类队列）。这里被加工成的是 `EventEntry` 的子类：

| EventEntry 子类 | 来源 | 对应 App 事件 |
|---|---|---|
| `KeyEntry` | `EV_KEY` | `KeyEvent`（按键/旋钮/硬键） |
| `MotionEntry` | `EV_ABS`+多指 / `EV_REL` | `MotionEvent`（触摸/鼠标移动） |
| `SwitchEntry` | `EV_SW` | 系统开关（合盖、耳机插入等） |
| `SensorEntry` / `ConfigChangeEntry` / `DeviceResetEntry` | 设备/配置变化 | 配置变更通知 |

> 版本差异：Android 12+ 引入 `FocusEntry`（焦点变化事件，配合 per-display 焦点演进，见 `23_多屏与多窗口机制详解`）；`SensorEntry` 自 Android 10 起用于直接上报传感器类输入。

### 4.5 reader 与 dispatcher 的衔接：QueuedInputListener

`InputReader` 线程和 `InputDispatcher` 线程是两条独立线程，二者通过 `QueuedInputListener` 解耦：`InputMapper` 加工出的 `EventEntry` 不直接调 dispatcher，而是暂存进一个队列，`InputReader::loopOnce()` 末尾一次性 `flush()` 给 dispatcher 的入站队列。这样 reader 再慢也不会阻塞 dispatcher 分派，反之亦然。排查"事件在 reader 和 dispatcher 之间丢"时，关键看这次 flush 是否发生、dispatcher 入站队列是否积压。

---

## 5. InputClassifier 与 InputReader 的产出

### 5.1 InputClassifier（Android 10+）接入点

`InputReader` 在把 `MotionEntry` 交给 dispatcher 之前，会先经过 `InputClassifier`。它的职责是**对 motion 做分类与延迟度量**（区分离散点击 vs 连续滑动，喂给 `latency` 统计框架），本身不影响"给谁"。本篇只点接入点，不展开其内部算法。

```text
InputReader → (QueuedInputListener) → InputClassifier::classify
                                  → 注入 latency 标记
                                  → InputDispatcher 入站队列
```

### 5.2 EventEntry 进入 dispatcher 后发生了什么

`InputDispatcher` 把入站的 `EventEntry` 放入"待处理"的队列，等待其分派主循环（`dispatchOnce`）消费。从这里开始，事件进入**分派状态机**（详见 `第 7 节`），下一步就是回答"给谁"。

---

## 6. InputDispatcher：决定事件给谁

`InputDispatcher`（`frameworks/native/services/inputflinger/dispatcher/`）运行在独立线程，是整条链上**唯一做"给谁"决策的组件**。它的决策依赖 WMS 通过 `InputMonitor` 周期性推送的窗口快照。

### 6.1 焦点窗口的维护：`setInputWindows` 的触发时机

WMS 每次窗口拓扑变化（add/remove/relayout/focus 变化）都会调用 `InputMonitor.updateInputWindowsLw()`，最终走到：

```text
WMS InputMonitor.updateInputWindowsLw()
  └─ IMS.setInputWindows() / updateInputWindows()
       └─ InputDispatcher::setInputWindows()   // 原子替换窗口快照
```

`InputDispatcher` 因此持有最新的窗口列表，每个窗口用 `InputWindowHandle` 描述，内含：

- `frame`：窗口在屏幕上的矩形；
- `touchableRegion`：可触摸区域（可 != frame，例如透明边角镂空）；
- `visible`、`focusable`、`touchable` 标志；
- `ownerPid`/`ownerUid`、`displayId`、`windowToken` 等。

> 直觉：**焦点窗口不是输入系统自己算的，是 WMS 算好推过来的**。InputDispatcher 只信这份快照。快照过期/没推，就会出现"焦点窗口"和"实际显示"不一致导致的点击错乱。

### 6.2 触摸目标的 hit 测试（命中目标窗口）

对 `MotionEntry`（尤其是 `ACTION_DOWN`），dispatcher 执行命中测试：

1. 取当前 display 的窗口列表，按 z 序从顶到底遍历；
2. 对每个窗口检查：坐标是否落在 `touchableRegion` 内、窗口是否 `touchable`、是否被更高层级的窗口遮挡；
3. 处理特殊 flag：`FLAG_NOT_TOUCHABLE`（直接跳过）、`FLAG_NOT_TOUCH_MODAL`（只有落在自身 region 才接收，外部透传）、`FLAG_WATCH_OUTSIDE_TOUCH` 等；
4. 命中后得到**触摸目标窗口集合**（多指/多窗口可能多个）。

遮挡与"遮罩"也在这里处理：被不透明上层覆盖的区域不会命中下层窗口。车机常见坑：某个 SystemUI 浮层 `FLAG_NOT_TOUCH_MODAL` 配错，导致点击穿透或吞掉。

### 6.3 分派策略：发给哪个连接

确定目标窗口后，dispatcher 通过"窗口 handle → Connection"映射，把事件写入对应 `InputChannel`（socket）。策略要点：

- **按键**：发给当前 focus 窗口；若无 focus，走 `PhoneWindowManager` 的 fallback（如音量键在没焦点时仍生效）。
- **触摸 DOWN**：建立"触摸状态（一类会话结构）"记录目标窗口，后续 MOVE/UP 必须发给同一窗口，保证**序列一致性**。
- **外部事件（outside touch）**：根据 flag 决定是否额外通知窗口"你外面被点了"。
- **指针事件（鼠标/触控笔）**：按 hover/button 状态分派，逻辑较触摸更复杂。

手势（双指缩放等）**不在这里处理**——dispatcher 只负责把原始 pointer 数据整段交给 App，语义由 `androidApp/03` 的 `View` 树判定。

### 6.4 鼠标 / 触控笔等指针事件的分派差异

触摸是"手指按下即命中"，指针设备（鼠标、触控笔）还要考虑 `hover`、按键状态与"光标下是谁"。dispatcher 对指针事件会先做"指针窗口命中"（pointer window，由 `WindowHandle` 的 `pointerTouchableRegion` 决定），再按 button 状态分派；触控笔通常有独立的 `toolType`，App 可通过 `MotionEvent.getToolType()` 区分。车机常见：鼠标模式（旋钮模拟鼠标）依赖这套 pointer 命中，配错 region 会导致"光标在 A 窗口、点击却落 B 窗口"。

### 6.5 事件被丢弃（drop）的常见原因

dispatcher 不是永远把事件发出去，以下情况会**丢弃并记入日志**（`logcat` 搜 `dropBecause`）：

- 目标窗口在投递前已 `gone`/不可见 → `DROP_REASON_APP_DIED` / `NOT_VISIBLE`；
- 非焦点窗口收到需聚焦的按键 → 仅投 focus 窗口（其余丢弃）；
- 策略拦截（PWM 已消费）→ `POLICY`（见 `第 11 节`）；
- 设备被禁用 / 事件过期（如排队过久的 stale motion）。

排障时 `dumpsys input` 的 connection 详情 + logcat 的 `dropBecause` 能直接告诉你"事件去哪了"。

### 6.4 版本演进提示

- Android 10 起 inputflinger **目录化**：`reader/`、`dispatcher/`、`host/` 分目录（`第 16 节` 速查表有结构）。
- Android 12 起引入 per-display 焦点与 `FocusEntry`，多屏下"给谁"要叠加 display 维度（见 `23_多屏与多窗口机制详解` 第 9 节）。

---

## 7. 事件序列与状态机：DOWN/MOVE/UP 一致性

输入不是孤立事件，而是一串有状态的序列。dispatcher 维护"触摸会话"一类结构，保证同一个手指的 DOWN→…→UP 始终落在同一窗口。

### 7.1 序列一致性的硬约束

- 一次触摸以 `ACTION_DOWN` 开始，建立会话并锁定**目标窗口集合**；
- 后续 `ACTION_MOVE` / `ACTION_POINTER_DOWN/UP` / `ACTION_UP` 必须发给同一组窗口；
- 若中途某根手指离开，会话降级但其余手指仍锁定原窗口；
- 所有手指抬起 → 会话结束、释放目标记录。

为什么要锁？因为 App 收到 DOWN 后会建立手势上下文（如滚动），中途换窗口会让手势状态错乱。这也是"滑动时窗口被关闭/遮挡"必须发 `CANCEL` 的原因。

### 7.2 触摸被新窗口"截胡"时的 CANCEL 生成

当会话进行中，目标窗口发生不可兼容的变化（被移除、变 `NOT_TOUCHABLE`、被更高层级窗口覆盖、焦点被抢），dispatcher 不能继续把事件发给原窗口，也**不能假装没事**——它会向原窗口补发一个 `ACTION_CANCEL`，通知"这次手势作废"，然后把后续事件改投新目标（或丢弃）。

```text
手指按下 → 锁定窗口 A
   ... MOVE 给 A ...
窗口 A 被 SystemUI 浮层 B 覆盖（z 序更高且 touchable）
   → dispatcher 对 A 补发 ACTION_CANCEL
   → 后续事件按 hit 测试改投 B（或被 B 吞掉）
```

App 侧必须在 `onTouchEvent` 收到 `ACTION_CANCEL` 时**立即复位所有手势/拖拽状态**，否则会出现"松手后还在滚动"的鬼畜。车机常见：空调浮层弹出吞掉地图拖拽。

### 7.3 ANR 计时从哪里开始

dispatcher 把事件写入目标 connection 的**出站队列**并移入"等待完成"的一类监视结构后，就开始计时。一旦 App 调用 `finishInputEvent()`（经 `InputChannel` 回写），该事件从等待结构移除，计时停止。若超过阈值仍未移除 → 触发 ANR（见 `第 8 节`）。

关键点：**计时起点是事件真正交给目标窗口之后**，不是 reader 读到、也不是 dispatcher 拿到事件时。这解释了为什么"焦点窗口为空"时算另一种 ANR（根本没有目标可计时，见下节）。

---

## 8. 输入 ANR：5 秒规则的准确语义

### 8.1 5 秒到底计什么

常听到的"输入 5 秒 ANR"准确语义是：**dispatcher 把事件交付给目标窗口后，该窗口所属进程必须在阈值内回报 `finishInputEvent`**。默认阈值对普通 Activity 窗口是 ~5000ms（系统/非应用窗口可取更长值，如 `UNMULTIPLIED_DISPATCHING_TIMEOUT` 体系）。不是"事件产生 5 秒"，而是"交付后 5 秒没完成"。所以：

- 事件在内核/reader 排队不算；
- 事件在 dispatcher 入站队列排队（还没找到目标）不算；
- **只有"已交付、等回包"这段时间才计入**。

### 8.2 三种输入 ANR 的区分

Input ANR 不是只有一种，reason 字符串区分根因：

| ANR 类型 | reason 关键词 | 本质 | 谁的问题 |
|---|---|---|---|
| **无焦点窗口** | `no focused window` / `has no focused window` | 事件找不到目标，连等待对象都没有 | WMS 焦点没建好 / 窗口未聚焦 |
| **焦点进程不响应** | `waiting because the focused window ... has not finished processing` | 目标窗口有，但 App 主线程没在阈值内 `finish` | App 主线程卡（Binder/锁/重活） |
| **调度/分发超时** | `dispatch timeout` / `timed out` | 系统侧排队挤压、binder 转交慢、system_server 卡 | system_server 负载 / 调度 |

三者排查方向完全不同：第一类查 WMS 焦点与 `dumpsys window mCurrentFocus`；第二类查 App 主线程栈（ANR trace 里目标进程 `main` 线程在等什么）；第三类查 system_server 是否卡顿、binder 是否堵。

### 8.3 trace 与 dumpsys 里怎么确认

ANR trace 头部会有输入原因行，形如：

```text
Reason: Input dispatching timed out (...Waiting because the focused window ...
```
或（无焦点）：

```text
Reason: Input dispatching timed out (no focused window, ...)
```

`dumpsys input` 的 `Input Dispatcher State` 里能看到每个 connection 的**等待队列**与 `Waited xxxms for ...` 一类行——这是确认"到底卡在等哪个窗口、等了多久"的最直接证据：

```bash
adb shell dumpsys input | grep -A30 "Input Dispatcher State"
# 关注：FocusedWindow、Connections 下的 outbound/wait 队列长度、Waited 行
```

> 记忆点：**输入 ANR = "事件送出去了，对方 5 秒没说收到"**。不是慢，是"没回包"。

---

## 9. App 侧接收：InputEventReceiver 与 Looper

事件从 `InputChannel` 到达 App 后，进入标准的消息循环体系（与 `03_消息机制详解` 的 Looper/Handler 同源）。

### 9.1 接收链路

```text
InputChannel (App 端 fd)
  └─ NativeInputEventReceiver (native 监听 fd，注册到 Looper)
       └─ 可读时 → InputEventReceiver.dispatchInputEvent()  (Java)
            └─ ViewRootImpl.dispatchInputEvent()
                 └─ enqueueInputEvent() → 入"输入事件"一类队列
                      └─ doProcessInputEvents() → 分发到 View 树
                           └─ DecorView.dispatchTouchEvent → Activity → ViewGroup/View
```

`InputEventReceiver` 本质是把 `InputChannel` 的 fd 用 `Looper.addFd()` 挂到主线程 Looper 上，事件到达即回调——**所以它和 `Handler` 共用同一个 Looper 循环**（详见 `03_消息机制详解`）。这也意味着：主线程 Looper 被别的消息长时间阻塞，输入事件即使到了 fd 也处理不了，照样触发 ANR。

### 9.2 事件批与批量消费（batching）

为降低唤醒次数、减少延迟抖动，dispatcher 与 receiver 都支持**批处理**：多个 `MOVE` 可能合并到同一个 `MotionEvent`（通过 `getHistorySize()` 取历史采样点），一次性交给 App。App 通常无需关心历史点，但做低延迟手写/绘图的场景要用 `getHistoricalX/Y` 还原细节。

VSync 与输入时间戳的衔接在渲染侧：输入事件带的时间戳会参与后续绘制节奏（Choreographer 调度），细节见 `22_图形渲染机制详解`。

### 9.3 回包：`finishInputEvent` 是 ANR 计时的终点

App 处理完事件**必须**调用 `finishInputEvent()`（经 `InputChannel` 回写 fd）。dispatcher 收到回包后把该事件从"等待完成"结构移除，ANR 计时停止。注意：

- 回包有两种结果：`FINISHED` 表示正常消费，`CANCELLED` 表示窗口放弃（如被覆盖）；
- **即使窗口不关心这个事件，也要 finish**，否则 dispatcher 一直等 → ANR；
- `ViewRootImpl` 会在事件真正走完 View 树后自动 finish，所以正常 App 不会漏；但若你在 `onTouchEvent` 里 `return true` 却忘了让事件自然流向 finish 路径（例如自己吞掉后没回包），就会埋雷。

### 9.4 未处理事件与超时保护

对于因窗口销毁、ANR 等原因无法投递的事件，dispatcher 有一类"待回收/过期"处理：超时未完成会被强制标记为完成并触发 ANR reason，避免连接队列无限积压拖垮后续事件。这部分与 `第 8 节` 的 ANR 计时是同一套结构的两端。

### 9.5 View 层只一句话带过

`ViewGroup.dispatchTouchEvent` 如何把事件按 z 序、命中、拦截（`onInterceptTouchEvent`）往下传，最终落到 `View.onTouchEvent()` / `onKeyDown()`——这是 App 知识，本篇不展开，完整链路见 `androidApp/03-UI开发-View系统`。本篇只关心"事件怎么到 `DecorView` 这一刀"。

---

## 10. 注入与虚拟设备：adb / 自动化 / 车机硬键

"谁都能造事件"是个危险能力，所以注入路径有严格权限闸门。

### 10.1 `adb shell input` 的注入路径

```bash
adb shell input tap 100 200
adb shell input keyevent KEYCODE_BACK
adb shell input swipe 100 200 300 400
```

它们走 `InputManager.injectInputEvent()`（Java），最终进入 `InputDispatcher::injectInputEvent()`。权限语义：

- `INJECT_AS_OWNER` / `INJECT_AS_TRUSTED`：仅 system、shell（adb）等特权调用方可用；
- 普通 App 注入需要 `INJECT_EVENTS` 权限且要经 `WindowManager` 的可见性/焦点校验（防止后台 App 偷点）；
- 注入的事件与真实事件走**同一条分派逻辑**，所以注入"点不到"往往也是焦点/region 问题。

### 10.2 自动化框架（uiautomator / Espresso）走哪条路

`uiautomator` 的 `injectInputEvent` 同样经 IMS 注入，但它在 `Instrumentation`/shell 特权下运行，可绕过部分前台校验。**底层与 `adb shell input` 同源**，排障时可直接 `dumpsys input` 看注入事件是否被 dispatcher 接收。

### 10.3 车机物理按键（方向盘 / 旋钮）的落地

车机方向盘按键、旋钮、遥控器等，**硬件上仍是标准输入设备**：TP/按键驱动把它们暴露成 `/dev/input/eventX`，走 `EventHub → InputReader`（经 `KeyboardInputMapper` / 自定义 mapper）→ `InputDispatcher`，与普通按键**完全同一条路**。vendor 的区别只在 idc/kl/kcm 配置（见 `第 11 节`）。

真正"车机专属"的是**硬键到 `CarService` 的衔接**：部分按键（如语音键、主页键）会在 `PhoneWindowManager` 拦截后，通过 `CarService`/`CarInputService` 派发给车载特定逻辑（如触发语音助理）。这一衔接属 automotive 专题，本篇只点接入点，详见 `automotive/` 系列。

---

## 11. 按键与 KeyCharacterMap：映射与系统级裁决

触摸是"坐标 + 手势"，按键则是"键码 + 语义"。前者靠 region，后者靠映射表与拦截链。

### 11.1 键值映射：从 scancode 到 KeyEvent

一个物理按键从按下到 App 拿到 `KeyEvent`，经过两层映射：

```text
硬件 scancode (驱动层, 如 0x73)
  └─ keylayout (.kl): scancode → keyCode (如 KEY_VOLUMEUP → KEYCODE_VOLUME_UP)
       └─ keychars (.kcm): keyCode + metaState → 字符 (如 'a' / 'A')
            └─ KeyEvent(keyCode, ..., metaState) 交给 App
```

- `.kl`（keylayout）：决定"这个物理键对应哪个 Android `KeyEvent.keyCode`"，车机方向盘键大多在这里改；
- `.kcm`（keychar map）：决定"这个 keyCode 在不同修饰键下产出什么字符"，影响 `KeyEvent.getUnicodeChar()`；
- 优先级同 `idc`（设备专属 > 默认）。改错 kcm 会出现"按音量键打出字符"这类怪象。

### 11.2 拦截链：`PhoneWindowManager` 的裁决点

按键在分派给焦点窗口**前后**，都有系统级拦截机会，位置在 `PhoneWindowManager`（PWM）：

| 方法 | 调用时机 | 典型用途 |
|---|---|---|
| `interceptKeyBeforeQueueing()` | 事件进入 dispatcher 队列**之前** | 全局硬键（静音键、媒体键在锁屏也生效）、尽早消费 |
| `interceptKeyBeforeDispatching()` | dispatcher 找到目标窗口**之后、投递前** | 电源键长按、Home/Recents、驾驶安全限制 |
| `dispatchUnhandledKey()` | 焦点窗口**没消费**该键时 | 兜底处理（如 fallback 到系统行为） |

PWM 返回 `ACTION_GO_ON` 表示放行，返回 `ACTION_PASS_TO_USER`/消耗则截断——**这就是"拦截策略吞掉按键"的根因所在**（排查表第 6 行）。车机驾驶模式下禁用某些 App 按键，正是在 PWM 拦截链里裁决。

一条典型按键的裁决时序（以音量键为例）：

```text
KeyEntry 生成
  └─ InputDispatcher 调 NativeInputManager.interceptKeyBeforeDispatching()
       └─ PhoneWindowManager.interceptKeyBeforeDispatching()   // 投递前
            ├─ 命中系统手势(如长按电源) → 消耗, 不再给焦点窗口
            └─ 放行 → 继续投递焦点窗口
  └─ 焦点窗口 onKeyDown() 处理
       └─ 若窗口未消费 → PWM.dispatchUnhandledKey() 兜底
```

`interceptKeyBeforeQueueing` 更靠前（事件入 dispatcher 队列之前就决定要不要进队列），适合"锁屏/灭屏也该响应的硬键"；两者配合，构成 Android 按键的"双层门禁"。

### 11.3 电源键长按等系统手势的裁决点

电源键短按/长按、双击、组合键（如音量下+电源截屏）的时序裁决都在 PWM 侧，由 `KeyCombinationManager` 之类结构配合。它们**不走普通焦点窗口**，由系统直接消费——这也是为什么"电源键没反应"要查 PWM 而非 App。

---

## 12. 调试工具箱

按层组织命令，先定位"卡在哪一环"。

### 12.1 内核层：`getevent`

```bash
adb shell getevent -lt            # 实时原始事件 + 时间戳；抓不到=内核/驱动问题
adb shell getevent -p             # 设备列表与能力（ABS/KEY 支持）
```

### 12.2 native/system_server：`dumpsys input`

```bash
adb shell dumpsys input           # 全量输入状态
adb shell dumpsys input | grep -A20 "Input Dispatcher State"
# 重点字段：
#   FocusedWindow        —— 当前焦点窗口（与 dumpsys window 交叉核对）
#   Connections          —— 每个窗口连接的 outbound/wait 队列长度
#   Waited xxxms for ... —— 等待某窗口回包的耗时（ANR 直接证据）
#   Event Hub State      —— 已枚举设备、各 eventX 状态
```

### 12.3 日志关键字

```bash
adb logcat -b all | grep -i "InputDispatcher\|InputReader\|ANR\|drop"
# InputDispatcher 会打印：事件被丢弃(dropBecause)、inject 结果、focus 变化
```

### 12.4 Perfetto 的 input 轨

```text
Perfetto (ui.perfetto.dev) → 勾选 "Input" / "InputReader" / "InputDispatcher" 轨道
```
可看到 reader→dispatcher→app 的端到端延迟，定位"事件延迟高"是卡在内核采样、分派等待还是 App 处理（VSync 衔接见 `22_图形渲染机制详解`）。

### 12.5 复现"没收到事件"的分层二分法

严格按四刀切，不要跳步：

```text
① 内核有没有？   → getevent -lt 抓得到 = 驱动 OK，否则查 TP/固件/idc
② reader 有没有？→ dumpsys input 的 Event Hub State 能看到设备 + 事件计数
③ dispatcher 给谁了？→ dumpsys input 看 FocusedWindow / Connection 队列 / drop 原因
④ app 收到没？   → logcat 搜 ViewRootImpl/dispatchInputEvent，或 App 加日志
```

每一刀把"有/没有"记下来，问题域立刻缩小一半。绝大多数"点不动"落在 ③（焦点/region/flag）或 ④（App 主线程卡）。

### 12.6 读一份真实 `dumpsys input` 片段

下面是一份**精简且标注**的 `Input Dispatcher State` 片段，排障时照着读：

```text
Input Dispatcher State:
  FocusedWindow: name='Window{abc u0 com.xxx/.Main}'  displayId=0
     ↑ 当前焦点窗口。为空/错 = 点击无反应或"无焦点 ANR"的根因（第 8.2 节）

  Connections:
    Window{abc ...}:
      outboundQueue: length=0        ← 已写出、等内核/App 收的
      waitQueue: length=1            ← 已交付、等 App finish 的（ANR 看这里）
        (MotionEvent ACTION_DOWN) Waited 3200ms for 'com.xxx'
      ⇒ waitQueue 非 0 且 Waited 接近 5000 = 即将/已经 Input ANR（第 8 节）

  Event Hub State:
    Devices:
      3: name='touch_dev' classes=0x4 (touchscreen)  ← 设备已被枚举
      add remove via inotify
```

读法口诀：**先看 FocusedWindow 对不对，再看目标 connection 的 waitQueue 是不是在涨、Waited 到多少**。两者就能定位"卡在焦点"还是"卡在 App 没回包"。

---

## 13. 常见问题排查表

> 四列：现象 | 原因（根因方向） | 对应章节 | 第一命令。按"先确认在哪一层"的顺序使用。

| 现象 | 原因（根因方向） | 章节 | 第一命令 |
|---|---|---|---|
| 点击无反应（窗口在但点不动） | 焦点窗口错 / touchable region 被覆盖 / flag 配错 | 6、12③ | `adb shell dumpsys window mCurrentFocus` |
| 触摸漂移 / 跳点 | TP 固件问题 / idc 坐标翻转缩放错 / 校准失效 | 2.3、4.2 | `adb shell getevent -lt`（看原始坐标是否抖） |
| 事件延迟高（>1 帧） | reader→dispatcher 排队 / App 主线程处理慢 / VSync 失步 | 7.3、9.2、22 | Perfetto input 轨看端到端延迟 |
| `Input dispatching timed out` | 焦点进程未在 5s 内 finish（主线程卡 Binder/锁） | 8.1、8.2 | `adb shell dumpsys input \| grep "Waited"` |
| 无焦点窗口型 ANR | 窗口未聚焦 / WMS 焦点没建好 | 6.1、8.2 | `adb shell dumpsys window mCurrentFocus` |
| 按键映射错（按 A 出 B） | `.kl`/`.kcm` 配错或优先级覆盖 | 11.1 | `adb shell dumpsys input \| grep -i "kl\|keylayout"` |
| 拦截策略吞掉按键 | PWM `interceptKeyBefore*` 返回消耗 | 11.2 | 查 `PhoneWindowManager` 拦截日志 |
| 外设键盘/鼠标无效 | 设备未被 EventHub 枚举 / idc deviceType 错 | 4.1、4.2 | `adb shell getevent -p`（看设备是否出现） |
| 车机旋钮不响应 | 旋钮未映射成 keyCode / mapper 选错 | 4.2、10.3、11.1 | `adb shell getevent -lt`（确认有事件+code） |
| per-display 焦点错（多屏点错屏） | 触摸映射到错误 display / 跨屏焦点 | 14、23§9 | `adb shell dumpsys input \| grep display` |
| 滑动被浮层打断（鬼畜滚动） | 中途窗口变化未正确处理 CANCEL | 7.2 | 复现 + logcat 搜 `CANCEL` |
| 注入事件点不到 | 注入无焦点窗口 / 前台校验拦截 | 10.1 | `adb shell input tap x y` + `dumpsys input` |
| 电源键/组合键无效 | PWM 裁决点未消费或消费错 | 11.3 | 查 `PhoneWindowManager` 电源键逻辑 |
| 设备热插拔不识别 | `/dev/input` inotify 未触发 / 权限 | 4.1 | `adb shell getevent -p`（插拔前后对比） |
| 触摸区域镂空失效 | `touchableRegion` 与 frame 不符 / NOT_TOUCH_MODAL 错 | 6.2 | `dumpsys input` 看窗口 region |
| 系统按键（音量）锁屏也触发 | `interceptKeyBeforeQueueing` 早消费 | 11.2 | 查 PWM 该键的 queueing 分支 |
| App 收到事件但处理慢 | 主线程 Looper 被长消息阻塞 | 9.1、8.2 | ANR trace 看目标进程 `main` 线程栈 |

---

## 14. 多屏输入衔接（指 23 第 9 节）

本篇主线是单屏，但车机往往是多屏（仪表 + 中控 + 副驾）。多屏下输入系统要额外回答"**事件落在哪个 display**"：

- `EventHub` 仍是单例，但每个触摸设备可通过 idc 的 `deviceType` + WMS/DisplayManager 的绑定，固定映射到某 `displayId`；
- `InputDispatcher` 的窗口快照按 display 分组，hit 测试先定 display 再定窗口；
- Android 12+ 引入 **per-display 焦点**（每个 display 独立 focus 窗口），`FocusEntry` 随之加入事件流；
- 跨屏拖拽、焦点抢占、旋钮控制"当前激活屏"等逻辑，属于 `23_多屏与多窗口机制详解` 第 9 节的范畴，本篇不重复。

> 速判：如果你的"点错屏"问题，先确认 `/dev/input` 设备绑定到哪个 display（`dumpsys input` 的 display 分组 + `23` 第 9 节），再回看本篇第 6 节的单屏 hit 测试逻辑。

---

## 15. 读源码路线（5 条主线）

按下面五条线走，能把"事件从哪来、给谁、慢了谁受罚"彻底打通：

1. **`/dev/input` → `EventHub` → `InputReader`**（采集与理解）
   `EventHub.cpp` 的 `getEvents()` / `openDeviceLocked()` → `InputReader.cpp` 的 `loopOnce()` → 各 `InputMapper`。看清楚原始字节如何变成 `EventEntry`。

2. **`InputDispatcher` 分派主循环**（给谁）
   `InputDispatcher.cpp` 的 `dispatchOnce()` → `dispatchOnceInnerLocked()` → `findTouchedWindowTargetsLocked()`（hit 测试）→ `dispatchEventLocked()`（写 connection）。这是全篇最核心的 200 行。

3. **`InputChannel` 双端注册**（桥）
   `WMS` 的 `openInputChannel()` → `InputChannel.openInputChannelPair()` → `IMS.registerInputChannel()` → dispatcher 建立 `Connection` 并监听 fd。理解"窗口 ↔ 连接"映射。

4. **ANR 计时**（慢了谁受罚）
   从 `enqueueDispatchEntryLocked()`（入等待结构）到 `finishDispatchEntryLocked()`（移除并停止计时），配合 `handleTargetsNotReadyLocked()` 生成 ANR reason。对照 `第 8 节` 三种类型。

5. **按键拦截链**（系统级裁决）
   `InputDispatcher` 投递前回调 `NativeInputManager::interceptKeyBeforeDispatching()` → `PhoneWindowManager` 的 `interceptKeyBeforeDispatching/Queueing()` → `dispatchUnhandledKey()`。对照 `第 11 节`。

> 建议顺序：先走 1→2→3 建立主链，再用 4、5 两个横切视角补全 ANR 与按键。

---

## 16. 源码路径速查表

### 16.1 native inputflinger 目录结构（`frameworks/native/services/inputflinger/`）

> 版本差异：Android 10 之前文件扁平分布在 `inputflinger/` 下；**Android 10 起目录化**为 `reader/`、`dispatcher/`、`host/`、`tests/`。

```text
inputflinger/
├─ reader/                # 读线程一侧
│  ├─ EventHub.cpp         # /dev/input 采集、设备枚举
│  ├─ InputReader.cpp      # 主循环 loopOnce，分发到 Mapper
│  ├─ InputDevice.cpp
│  ├─ mapper/              # 各设备类型加工（多指/键盘/开关/传感器）
│  │  ├─ MultiTouchInputMapper.cpp
│  │  ├─ KeyboardInputMapper.cpp
│  │  └─ SwitchInputMapper.cpp
│  └─ InputClassifier.cpp  # Android 10+ 分类器接入点
├─ dispatcher/            # 分派线程一侧
│  ├─ InputDispatcher.cpp  # 决策"给谁"、ANR 计时、Connection
│  ├─ InputState.cpp       # 触摸会话/序列状态
│  └─ Connection.cpp / InputChannel.cpp
├─ host/                  # 创建 InputReader+Dispatcher 的工厂（InputManager）
└─ InputTransport.cpp     # InputChannel 的 socket 收发实现
```

### 16.2 Java / Framework 接口点

| 内容 | 路径 |
|---|---|
| `InputManagerService` | `frameworks/base/services/core/java/com/android/server/input/InputManagerService.java` |
| `NativeInputManager`（JNI 桥） | `frameworks/base/services/core/jni/com_android_server_input_InputManagerService.cpp` |
| `InputMonitor`（WMS→IMS 窗口同步） | `frameworks/base/services/core/java/com/android/server/wm/InputMonitor.java` |
| `PhoneWindowManager`（按键拦截） | `frameworks/base/services/core/java/com/android/server/policy/PhoneWindowManager.java` |
| `ViewRootImpl`（App 接收） | `frameworks/base/core/java/android/view/ViewRootImpl.java` |
| `InputEventReceiver` | `frameworks/base/core/java/android/view/InputEventReceiver.java` |
| WMS `addWindow`/`openInputChannel` | `frameworks/base/services/core/java/com/android/server/wm/WindowManagerService.java`、`Session.java` |

### 16.3 配置文件（`/system/usr/`，vendor 最常改）

| 文件 | 作用 |
|---|---|
| `idc/<dev>.idc` | 输入设备配置（类型、方向、display 绑定） |
| `keylayout/<dev>.kl` | scancode → keyCode |
| `keychars/<dev>.kcm` | keyCode + meta → 字符 |

---

## 17. 一图总结

```text
┌──────────────── 内核 ────────────────┐   ┌──────── 用户态 inputflinger ────────┐   ┌──── App ────┐
│  TP/按键驱动 → input core → evdev     │   │  EventHub → InputReader(理解)        │   │ InputChannel│
│  /dev/input/eventX (bytes)            │   │      ↓ EventEntry(Key/Motion…)      │   │   → Receiver│
│  getevent 可读 = 驱动 OK              │   │  InputDispatcher(给谁: 焦点+hit)     │   │   → View树  │
└──────────────────────────────────────┘   │      ↓ InputChannel(socket pair)     │   └────────────┘
                                            │  WMS 推窗口快照(setInputWindows)     │
                                            │  PWM 裁决按键(intercept*)            │
                                            └─────────────────────────────────────┘
        三个问题： 从哪来？(内核/EventHub)  给谁？(仅 Dispatcher 决定)  慢了谁受罚？(5s 未 finish → ANR)
```

**一句话记忆链**：
> 硬件只采样、内核只排队、**只有 InputDispatcher 决定给谁**；给出去后对方 5 秒不回包就 ANR；排查按"内核→reader→dispatcher→app"四刀切。

---

## 18. 关联阅读

- `01_Android启动流程详解`：system_server 与 IMS 的启动起点。
- `03_消息机制详解-从Looper到Handler和Choreographer`：App 侧 `InputEventReceiver` 与 Looper 共用消息循环的根。
- `05_AMS机制详解` / `06_AMS机制详解`：App 进程与 ANR 进程维度的上下文。
- `11_WMS机制详解-从窗口添加到显示管理`：焦点窗口、touchable region、窗口快照如何推给 InputDispatcher（本篇第 6 节的数据源）。
- `12_SurfaceFlinger机制详解` / `22_图形渲染机制详解-从HWUI到GPU上屏`：输入时间戳与 VSync、绘制节奏的衔接（本篇第 9.2 节指向）。
- `23_多屏与多窗口机制详解-从DisplayManager到CarOccupantZone`：per-display 焦点与多屏输入（本篇第 14 节衔接，重点看第 9 节）。
- `androidApp/03-UI开发-View系统`：View 层 `onTouchEvent`/手势分发（App 知识，本篇第 9.3 节带过）。
- `automotive/` 系列：车机硬键到 `CarService` 的衔接（本篇第 10.3 节接入点）。


