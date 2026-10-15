# Android 蓝牙机制详解——从跳频无线到 GATT，再到车机互联

> 蓝牙的知识不是一条直线，而是**一棵"按场景分叉的树"**：你要连耳机、连手环、做车机免提、做蓝牙钥匙……走的是完全不同的技术分支。所以这篇的组织方式是**先给能力菜单（我要做 X → 用哪套技术 → 看哪节），再从底层原理往下讲**。
>
> 读完你应该能回答：蓝牙耳机为什么能播歌又能打电话、手环的电量数据怎么被 App 读到、车机接手机要哪几个 Profile、蓝牙钥匙靠什么判断"人是不是在车旁"、`btsnoop` 抓包怎么看。Android 内容用「**Android 视角**」标出，跳过不影响理解蓝牙本身。

---

## 目录

**第一部分：先找你的场景**
1. [能力菜单：我要做 X，该走哪条路](#一能力菜单我要做-x该走哪条路)
2. [两个分支：经典蓝牙 vs 低功耗蓝牙](#二两个分支经典蓝牙-vs-低功耗蓝牙)

**第二部分：原理（蓝牙到底怎么通信）**
3. [物理层：2.4GHz 上的"跳着说话"](#三物理层24ghz-上的跳着说话)
4. [协议栈：Controller、Host 与 HCI 分界线](#四协议栈controllerhost-与-hci-分界线)
5. [经典蓝牙：从 Inquiry 到 RFCOMM 串口](#五经典蓝牙从-inquiry-到-rfcomm-串口)
6. [BLE：广播、连接与 GATT 数据表](#六ble广播连接与-gatt-数据表)
7. [配对与安全：暗号怎么约定，钥匙怎么保管](#七配对与安全暗号怎么约定钥匙怎么保管)

**第三部分：场景详解**
8. [音频链路：A2DP、HFP、AVRCP 与延迟](#八音频链路a2dphfpavrcp-与延迟)
9. [Profile 全集：能力清单与 UUID 速查](#九profile-全集能力清单与-uuid-速查)
10. [BLE 高级应用：蓝牙钥匙、测距与 OTA](#十ble-高级应用蓝牙钥匙测距与-ota)

**第四部分：平台落地**
11. [Android 蓝牙架构：从 App 到芯片](#十一android-蓝牙架构从-app-到芯片)
12. [车机场景：四个角色与 ROM 定制](#十二车机场景四个角色与-rom-定制)
13. [调试工具箱](#十三调试工具箱)
14. [常见问题排查表](#十四常见问题排查表)
15. [要动哪些文件：定制与排障速查](#十五要动哪些文件定制与排障速查)

---

## 一、能力菜单：我要做 X，该走哪条路

**先查表，再往下读**。蓝牙的问题几乎都能归到这几种场景：

| 我要做… | 用什么技术 | 关键 Profile/机制 | 重点章节 |
|---|---|---|---|
| 连蓝牙耳机/音箱放音乐 | 经典蓝牙 | A2DP（Source 侧） | 9、8 |
| 免提通话（车机/耳机） | 经典蓝牙 | **HFP**（HF 角色），音频走 SCO | 8、9 |
| 车机接手机（通话+音乐+通讯录） | 经典蓝牙 | HFP(HF) + A2DP(Sink) + AVRCP(CT) + PBAP(PCE) + MAP | 12 |
| 读手环/心率带/温度计数据 | BLE | **GATT 读/通知** | 6 |
| 连胎压、OBD、传感器模块 | BLE（老模块可能是经典 SPP） | 自定义 GATT 服务 / SPP 串口 | 6、5 |
| 透传字节流（旧设备、诊断仪） | 经典蓝牙 | **SPP**（RFCOMM 串口） | 5 |
| 手机靠近自动解锁车（蓝牙钥匙） | BLE | RSSI 测距 + 自定义 GATT 挑战应答 | 10 |
| 升级固件（OTA/DFU） | BLE | 大 MTU + 分片写 + 通知确认 | 6、10 |
| 键鼠手柄 | 经典蓝牙 | HID | 9 |
| 手机投屏到车机 | 不是蓝牙 | Wi-Fi（见网络串讲第 13 章局域网发现） | — |
| 手机与车机之间传文件 | 已淘汰 | OPP / 用 Wi-Fi 直连替代 | 9 |

**三条通用选型直觉**：

1. **要"持续传音频/字节流"→ 经典蓝牙**（A2DP、SPP、HFP）；
2. **要"省电 + 小数据 + 结构化"→ BLE**（GATT）；
3. **不确定设备支持哪种** → 看厂商文档标的"经典/BLE/双模"，别猜。

---

## 二、两个分支：经典蓝牙 vs 低功耗蓝牙

**这是全文最重要的一章。两种技术从设计目标就不同，所以协议、API、调试手段全都不同。**

### 2.1 设计目标的差异

```
经典蓝牙（BR/EDR, Basic Rate / Enhanced Data Rate）
  1998 年诞生，目标：替代线缆，**持续传数据**（语音、音频流、串口）
  速率高（1~3 Mbps），功耗大，一直连着就费电
  → 耳机听歌、车载免提、条码枪、老式串口模块

低功耗蓝牙（BLE, Bluetooth Low Energy）
  2010 年（蓝牙 4.0）从 Nokia 的 Wibree 并入，目标：**省电到极致**
  平时几乎不连，需要时才"醒来"几毫秒说一句话再睡
  速率低（几十~几百 kbps 有效载荷），纽扣电池可用几个月到几年
  → 手环、心率带、电子秤、蓝牙钥匙、胎压监测、iBeacon
```

### 2.2 对比表（背下来）

| 维度 | 经典蓝牙 (BR/EDR) | BLE |
|---|---|---|
| 发现方式 | Inquiry 主动搜索（慢，几秒） | **广播**（设备主动吆喝，毫秒级） |
| 建连速度 | 秒级 | **毫秒级** |
| 拓扑 | 1 主 7 从（piconet），可级联 | 连接后一般 1 对 1（也支持 1 对多） |
| 数据通道 | RFCOMM（串口模拟） / L2CAP | **GATT 属性表** |
| 数据模型 | 字节流（像 Socket） | 结构化"读/写/订阅"（像访问数据库） |
| 典型吞吐 | ~1 Mbps 有效 | ~100 kbps（协商 MTU 后更高） |
| 功耗 | 高（几十 mA） | 极低（收发几 mA，平时 µA） |
| Android API | `BluetoothSocket` / Profile 类 | `BluetoothGatt` / Scanner / Advertiser |

**双模（Dual Mode）设备**：手机、车机都是双模——经典蓝牙连耳机，BLE 连手环，同一颗芯片跑两套协议。

### 2.3 版本演进（只需记住几个节点）

| 版本 | 关键点 |
|---|---|
| 2.0 + EDR | 经典蓝牙提速 |
| 3.0 + HS | 用 Wi-Fi 传大文件（实际很少用） |
| **4.0** | **引入 BLE**，里程碑 |
| 4.2 | BLE 支持更大 MTU、隐私地址（RPA） |
| 5.0 | 2M PHY（更快）、Coded PHY（更远）、扩展广播 |
| 5.1 / 5.2 | 测向（AoA/AoD）、**LE Audio + LC3 编码** |
| 6.x | 信道探测（channel sounding）等 |

「**Android 视角**」Android 8.0 起支持蓝牙 5 的 2M PHY 等特性；Android 13 起支持 **LE Audio**（LC3）——这是车机/耳机一次重要的音频换代（低延迟、双耳独立、助听器友好）。

---

## 三、物理层：2.4GHz 上的"跳着说话"

### 3.1 频段与信道

蓝牙工作在 **2.4GHz ISM 免许可频段**（和 Wi-Fi、微波炉、无线鼠标同频段）。

- **经典蓝牙**把 2.4GHz 切成 **79 个 1MHz 信道**；
- **BLE** 切成 **40 个 2MHz 信道**，其中 **37、38、39 三个是广播信道**（专门用来"吆喝"），其余 37 个是数据信道。

### 3.2 跳频（FHSS）：抗干扰与防窃听

蓝牙不是固定在一个信道说话，而是**双方按同一套伪随机序列，每秒跳 1600 次信道**。

类比：两人约定"每隔半毫秒换一个频道说话"，偷听者截获某频道片段也拼不出完整内容；某频道被 Wi-Fi 占了，下一跳就绕开——**这就是蓝牙在拥挤的 2.4GHz 里还能用的原因**。

BLE 类似但更聪明：广播只固定用 3 个信道，连接后在 37 个数据信道里跳，并按各信道误码率**动态避开坏信道**（自适应跳频 AFH，经典蓝牙也有）。

### 3.3 功率等级与 PHY

| 等级 | 最大发射功率 | 典型距离 |
|---|---|---|
| Class 1 | 100 mW | 100 米（车机、dongle） |
| Class 2 | 2.5 mW | 10 米（手机、耳机最常见） |
| Class 3 | 1 mW | 1 米 |

BLE 用 **PHY** 描述速率：LE 1M（默认）、LE 2M（更快）、LE Coded（更远更低速，蓝牙 5.0 长距离模式）。

### 3.4 与 Wi-Fi 的干扰

同频干扰是真实高频问题（车机同时开 Wi-Fi 热点和蓝牙尤其明显）：音频断续、连接不稳、扫描不到设备。原因：Wi-Fi 占用 20/40MHz 带宽压在蓝牙信道上；**芯片级共存（coexistence）**机制负责让两者分时使用天线（BT/Wi-Fi combo 芯片走 coex 仲裁）。缓解：Wi-Fi 改 5GHz；或调整 coex 优先级（vendor 侧参数，ROM 定制可能碰）。

---

## 四、协议栈：Controller、Host 与 HCI 分界线

### 4.1 分层图

```
┌──────────────────── Host（跑在主机 CPU 上，Android 里就是 AP）────────────────────┐
│  Profile 层：A2DP / HFP / AVRCP / PBAP / MAP / HID / GATT Service                │
│  ├─ 高层协议：RFCOMM(串口) / SDP(服务发现) / AVDTP(音频流) / ATT(GATT) / SMP(配对)│
│  └─ 核心协议：L2CAP（多路复用与分段）                                            │
├─────────────────────────── HCI（标准化的"命令/事件"接口）───────────────────────┤
│    ├─ ACL 数据（业务数据）                                                       │
│    ├─ HCI Command（Host → Controller：查询、配置、发起连接）                      │
│    └─ HCI Event（Controller → Host：连接完成、广播报告、错误）                    │
├────────────────────── Controller（跑在蓝牙芯片/SoC 上）─────────────────────────┤
│  链路层 Link Layer（帧、跳频、连接调度）  ← BLE 的 "LL" 或经典的 "Baseband"        │
│  物理层 PHY（调制解调、射频收发）                                                 │
└────────────────────────────────────────────────────────────────────────────────┘
```

**为什么这条线重要**：HCI 是**标准化**的，所以"Host 用谁的、Controller 用谁的"可以任意搭配；`btsnoop` 抓包抓的就是这一层（既能看命令事件，也能看数据），是蓝牙调试的第一现场。

### 4.2 HCI 报文长什么样（抓包时对照用）

```
>> HCI_Command: LE Set Scan Parameters      （手机要求芯片开始扫描）
<< HCI_Event:   Command Complete
<< HCI_Event:   LE Advertising Report        （收到一个广播，含地址、RSSI、广播数据）
>> HCI_Command: LE Create Connection         （发起连接）
<< HCI_Event:   LE Connection Complete       （连接建立，含 interval/latency/timeout）
>> ACL Data:    ATT_Read_Request             （读某个特征值）
<< ACL Data:    ATT_Read_Response            （值的字节内容）
<< ACL Data:    ATT_Handle_Value_Notification（设备主动推来的通知）
```

**看懂这几行，就理解了"主机下命令 → 芯片执行 → 事件上报 → 数据交换"的完整闭环**。

### 4.3 各层职责

| 层 | 职责 | 类比 |
|---|---|---|
| L2CAP | 在链路之上开"逻辑信道"，做多路复用、分段重组、MTU 协商 | 一条物理管道上分出多个编号车道 |
| SDP | 经典蓝牙的"服务黄页"：查对方支持哪些服务、对应哪个通道 | 查号台 |
| RFCOMM | 模拟串口（RS-232），给 SPP 用 | 虚拟 COM 口 |
| ATT / GATT | BLE 的数据访问协议（读/写/通知）与数据建模 | 数据库查询协议 + 表结构 |
| SMP | BLE 的配对与密钥分发 | 交换暗号 |
| AVDTP / AVCTP | 音频流传输 / 音频控制（AVRCP 的承载） | 音频管线 + 遥控器 |

### 4.4 连接（Connect）与绑定（Bond）的区别

- **连接**：这一次链路建立，断开就没了；
- **绑定**：双方交换并保存了长期密钥（经典叫 link key，BLE 叫 LTK/IRK），**下次不用重新配对**，系统里表现为"已配对设备"。

「**Android 视角**」`BluetoothDevice.getBondState()` 返回 `BOND_NONE / BOND_BONDING / BOND_BONDED`——很多"连不上"其实是"没绑定或被删了绑定"。绑定信息存在 `/data/misc/bluetooth/` 下（恢复出厂即丢，见车机章节）。

---

## 五、经典蓝牙：从 Inquiry 到 RFCOMM 串口

### 5.1 建立连接的全流程

```
① Inquiry（查询）：主设备在多个跳频信道上喊"附近有谁？"
     └─ 从设备在 Inquiry Scan 窗口里听到，回 FHS 包（自己的地址+时钟）
     └─ 这一步慢（数秒），就是"搜索设备"转圈圈的原因

② Page（寻呼）：主设备用已知地址直接点名联系某台设备
     └─ 从设备在 Page Scan 窗口里应答 → ACL 链路建立

③ 安全：没绑定时走配对（第 7 章）；已绑定则用 link key 直接认证

④ SDP 服务发现：主设备问"你提供哪些服务？各自的 RFCOMM 通道号是多少？"

⑤ 建立 Profile 通道：
     ├─ 串口类：RFCOMM 上开一条"虚拟串口"，读写字节流（SPP）
     └─ 音频类：L2CAP/AVDTP 上开流（A2DP，第 8 章）

⑥ 业务通信
```

**为什么"搜索很慢、重连很快"**：重连时地址已知，跳过 Inquiry 直接 Page；绑定过的设备连 link key 都现成，所以车机开机后能秒连上次那台手机。

### 5.2 SPP：最像 Socket 的蓝牙用法

```kotlin
// 服务端（也可做发起方）——注册 SPP 服务并监听
val server = adapter.listenUsingRfcommWithServiceRecord("MySPP", SPP_UUID)
val socket = server.accept()                       // 阻塞等连接

// 客户端
val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
socket.connect()                                   // 阻塞建连
socket.outputStream.write("AT+STATUS?\r\n".toByteArray())
val n = socket.inputStream.read(ByteArray(1024))   // 阻塞读
```

**注意**：SPP 的 UUID 固定为 `00001101-...`；SPP 的字节流**同样没有消息边界**（和 TCP 一样，需要自己定协议——定长/分隔符/长度前缀三种解法）。

「**Android 视角**」`createRfcommSocketToServiceRecord` 走标准 SDP 查询；有些老模块不响应 SDP，得用反射走 `createRfcommSocket(channel)` 直连固定通道号——调老设备的经典绕行手段。

---

## 六、BLE：广播、连接与 GATT 数据表

### 6.1 两种状态：广播与连接

**广播（Advertising）**——没有连接，单向吆喝：

```
手环：  每隔几十~几百毫秒，在 37/38/39 信道发一个 31 字节的广播包
        "我是心率带，服务 UUID 0x180D，名字叫 MiBand"
手机：  扫描 → App 通过 ScanCallback 拿到结果
```

**这就是 BLE 最省电的地方**：发完就睡，不必维持连接。iBeacon、信标、资产标签全靠它。

**连接**——手机主动发起（`CONNECT_IND`），之后进入**连接事件（connection event）**机制：

```
每 interval 时间（7.5ms ~ 4s）双方醒来一次交换数据，然后一起睡
  interval 越大越省电，延迟越高
  slave latency：从设备可"跳过" N 次事件不应答（进一步省电）
  supervision timeout：连续这么久没收到对方就判定断连
约束：timeout > (1 + latency) × interval × 2
```

「**Android 视角**」连接参数通过 `requestConnectionPriority()` 请求三档之一（HIGH ≈ 11.25ms / BALANCED ≈ 30ms / LOW_POWER ≈ 500ms 起，**最终以控制器协商结果为准**）。OTA 升级用 HIGH，长期监测用 LOW_POWER——这个参数直接影响手环续航和 App 响应速度。

### 6.2 广播包的结构（31 字节怎么花的）

| 类型 | 内容 | 用途 |
|---|---|---|
| 0x01 Flags | 是否可连接 / 是否 BLE-only | 决定手机能不能连它 |
| 0x02/0x03 | 16 位 Service UUID 列表 | 让扫描方快速判断"这设备有什么用" |
| 0x09 | Complete Local Name | 设备名 |
| 0xFF | Manufacturer Specific Data | **厂商自定义**：电量、传感器值、iBeacon 的 UUID/major/minor |

只放得下 31 字节，不够就靠**扫描响应（Scan Response）**再补 31 字节；蓝牙 5.0 的**扩展广播**突破了该限制。

**实践要点**：很多设备在广播里直接塞业务数据（如温度），App 不连接就能读——这是"零连接采集"，也是车机胎压监测省电的关键。

### 6.3 GATT：BLE 的数据模型（最重要的一节）

BLE 连接后，数据不是"字节流"，而是一张**结构化的属性表**。用类比理解：

```
一台 BLE 设备  =  一本产品说明书
  Service（服务）      =  书里的一章，代表一个功能模块
    Characteristic（特征） =  章里的一条，代表一个数据项
      Descriptor（描述符） =  对这条的补充说明（如"单位是摄氏度"）
```

**标准 UUID**（16 位短写，展开为 `0000xxxx-0000-1000-8000-00805F9B34FB`）代表通用含义；**128 位自定义 UUID** 是厂商业务。

| UUID | 服务 | 常见特征 |
|---|---|---|
| 0x1800 | Generic Access | 设备名、外观 |
| 0x1801 | Generic Attribute | 服务变更通知 |
| 0x180A | Device Information | 厂商名、型号、固件版本 |
| 0x180F | **Battery Service** | 0x2A19 电量百分比 |
| 0x180D | Heart Rate | 0x2A37 心率值 |
| 0x181A | Environmental Sensing | 温度 0x2A6E、湿度 0x2A6F |
| 0x2902 | （描述符）**CCCD** | 是否开启通知 |

**四种属性 + 三个动作**：

| 属性 | 动作 | 语义 |
|---|---|---|
| Read | `readCharacteristic()` | 主动读一次 |
| Write | `writeCharacteristic()` | 写值（Write Request 有应答 / Write Command 无应答） |
| Notify | `setCharacteristicNotification()` | **设备主动推送**（最关键） |
| Indicate | 同上 | 带确认的推送（更可靠、更慢） |

**Notification 的坑（高频）**：开启通知要做**两件事**——本地注册回调 + 写 CCCD 描述符告诉设备"开始推给我"：

```kotlin
gatt.setCharacteristicNotification(batteryChar, true)      // 1) 本地允许该特征通知
val cccd = batteryChar.getDescriptor(CCCD_UUID)            // 2) 写 CCCD = ENABLE_NOTIFICATION
cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
gatt.writeDescriptor(cccd)
```

**只做第 1 步 = 永远收不到数据**，这是 BLE 开发第一大坑。

### 6.4 BLE 的完整交互流程

```
① 扫描：BluetoothLeScanner.startScan(filters, settings, callback)
     ScanSettings 三档：LOW_POWER / BALANCED / LOW_LATENCY
② 连接：device.connectGatt(context, autoConnect=false, gattCallback)
③ 发现服务：onConnectionStateChange(CONNECTED) → gatt.discoverServices()
④ 拿服务与特征：onServicesDiscovered → getService(uuid)?.getCharacteristic(uuid)
⑤ 协商 MTU：gatt.requestMtu(517) → onMtuChanged（默认 23 字节，含 3 字节头 → 净荷 20）
⑥ 读写/订阅：read / write / setCharacteristicNotification + 写 CCCD
⑦ 断开：gatt.disconnect() + gatt.close()
```

**四个必须知道的细节**：

- **所有 GATT 操作必须串行**：上一个未回调前再发会被静默丢弃（Android 内部只有一个"待完成操作"槽位）。要用队列管理。
- **`autoConnect=true`**：系统在设备出现时自动连（省电、慢，适合配对过的传感器）；`false`：直接发起连接（快，设备不在就失败）。
- **`gatt.close()` 必须调**：只 `disconnect()` 不 `close()` 会泄漏 GATT 客户端，多次重连后出现"连不上、回调不响应"，只能重启蓝牙。
- **MTU 决定吞吐**：默认净荷 20 字节；协商到 517 后单包几百字节，传大文件（OTA 固件）必须先协商 MTU 并分批写。

「**Android 视角**」BLE 扫描权限演变：Android 12 前需要 `BLUETOOTH_ADMIN` + **定位权限**（扫描结果可推算位置）；Android 12 起改为 `BLUETOOTH_SCAN`（可加 `android:usesPermissionFlags="neverForLocation"` 免掉定位权限）+ `BLUETOOTH_CONNECT`。**车机 App 扫描不到设备，先查这几个权限**。

---

## 七、配对与安全：暗号怎么约定，钥匙怎么保管

### 7.1 配对与绑定

```
配对 = 双方协商出一份"长期密钥"（经典：Link Key；BLE：LTK）
绑定 = 把密钥存起来（Android 存于 /data/misc/bluetooth/），下次免配对
```

### 7.2 关联模型：怎么防止中间人（MITM）

配对的核心难点是**如何在可能被监听的第一次通信中安全地协商出密钥**。按两端的"输入输出能力"选择方案：

| 方式 | 前提 | 安全性 |
|---|---|---|
| **Just Works** | 至少一方无屏无键盘 | 无 MITM 防护——多数手环/耳机走这条 |
| **Passkey Entry** | 一方显示 6 位数字，另一方输入 | 有 MITM 防护 |
| **Numeric Comparison** | 双方都有屏（BLE Secure Connections） | 有 MITM 防护，两端显示同一数字由用户确认 |
| **OOB** | 借助 NFC 等带外通道传密钥 | 最高 |

「**车机场景**」车机通常有屏幕，与手机配对走 **Numeric Comparison / Passkey**（车机显示 6 位码、手机弹窗确认，或反过来）——比"免提耳机 Just Works"安全得多，也解释了车机配对为什么总要在两边点一次确认。

### 7.3 密钥家族（BLE 侧，看到缩写不慌）

| 密钥 | 作用 |
|---|---|
| TK | 临时密钥（配对中用，之后丢弃） |
| STK | TK 派生的短期密钥（LE Legacy 用） |
| **LTK** | 长期密钥，加密链路用（相当于经典蓝牙的 Link Key） |
| EDIV / Rand | 标识哪把 LTK（支持多设备） |
| **IRK** | 身份解析密钥，用于解析对方的随机地址（隐私） |
| CSRK | 连接签名密钥，用于无加密链路的数据签名 |

### 7.4 地址类型与隐私

BLE 地址可以是固定（Public/Static）或**周期性变化（RPA，可解析私有地址）**——设备定期换地址防跟踪，只有持 IRK 的绑定方能认出"这还是那台设备"。

**副作用**：扫描时看到一堆随机地址，**同一设备两次扫描 MAC 可能不同**。做设备识别不能用 MAC 当唯一 ID，要用广播里的厂商数据或服务 UUID。

---

## 八、音频链路：A2DP、HFP、AVRCP 与延迟

### 8.1 两条独立的音频路径

| | A2DP | HFP |
|---|---|---|
| 用途 | 听音乐（立体声、高质量） | 打电话（双向、保通话） |
| 通道 | L2CAP 上的 AVDTP 流 | SCO / eSCO 专用链路 |
| 编码 | SBC / AAC / aptX / LDAC / LHDC | CVSD（窄带 8kHz）/ mSBC（宽带 16kHz） |
| 带宽 | 数百 kbps | 64 kbps 级 |
| 延迟 | 100~300ms（看编码） | 更低且稳定 |

**为什么车里听歌好好的，一接电话音质就变差**：通话时音频从 A2DP 切到 HFP（SCO 链路），编码降到语音级——这是协议决定的，不是故障。**车机要同时跑 A2DP Sink + HFP HF + AVRCP CT 三个 Profile**，缺一个就会出现"能听歌不能打电话"或"能打电话不能切歌"。

蓝牙 5.2 的 **LE Audio + LC3** 是这条链路的换代：更低延迟、更低码率下更好音质、支持一源多播（多只耳机/多座位同时听同一路音源）——车机多座位音频受益明显。

### 8.2 延迟从哪来（车机体验关键）

1. **编码缓冲**：SBC/AAC 编码器要先攒够一帧；
2. **AVDTP 传输与重传**：干扰下链路层重传会累积延迟；
3. **接收端解码 + 播放缓冲**；
4. **AVRCP 与音频不同步**：暂停/播放指令走 AVCTP，比音频流慢，所以"按了暂停还有半秒声音"。

优化方向：低延迟编码器（aptX LL / LC3）、加大发射功率等级、BT/Wi-Fi 共存参数调优、播放端缓冲调小（牺牲抗抖动）。

### 8.3 AVRCP 元数据同步的坑

AVRCP 1.3 起支持"当前播放信息"（曲名/歌手/专辑/时长），1.4 起支持浏览媒体库（车载列表）。常见问题：

- 车机显示"未知曲目" → 版本协商到 1.0，或手机侧未注册元数据监听（`registerCallback`）；
- 切歌指令无效 → CT/TG 角色搞反了（车机应为 CT，手机 TG）；
- 进度条不动 → 需要 1.6 的"绝对音量/绝对进度"支持。

### 8.4 多设备与音频路由（车机多手机场景）

车机常要同时连两台手机（一台通话、一台音乐）或"主副驾各一只蓝牙耳机"。此时决定"声音走谁"的不是蓝牙栈，而是**音频策略**：

```
蓝牙 Profile 只管"连接与编解码" → AudioPolicy / AudioService 决定"哪路音频路由到哪个设备"
```

排障时先分清：**是连接层没建立（看 `dumpsys bluetooth_manager`），还是音频路由选错（看 `dumpsys audio`）**。这一区分能省一半时间。

---

## 九、Profile 全集：能力清单与 UUID 速查

### 9.1 Profile / Protocol / Service：三个词的区别

| 词 | 含义 | 例子 |
|---|---|---|
| **Protocol（协议）** | 通信规则本身，是"怎么说话" | L2CAP、ATT、RFCOMM、SDP |
| **Profile（配置文件）** | **应用场景的一套约定组合**：为做成一件事，规定用哪些协议、什么参数 | HFP、A2DP、HID |
| **Service（服务）** | ① 经典蓝牙 SDP 上注册的一个可用能力；② BLE GATT 表格中的一章 | SPP 服务；BLE 电池 Service |

设备双方必须支持**同一 Profile 的互补角色**才能互通。

### 9.2 经典蓝牙 Profile 全表

| Profile | 全称 | 干什么 | 角色（成对） |
|---|---|---|---|
| **HFP** | Hands-Free Profile | 免提通话（车机核心） | AG（手机/音频网关）↔ HF（车机/免提） |
| HSP | Headset Profile | 更老的单声道通话 | AG ↔ HS |
| **A2DP** | Advanced Audio Distribution | 高质量立体声音乐 | Source（手机）↔ Sink（车机/耳机） |
| **AVRCP** | Audio/Video Remote Control | 播放/暂停/上下曲/元数据 | CT（控制器，车机）↔ TG（目标，手机） |
| **PBAP** | Phone Book Access | 同步通讯录/通话记录 | PCE（客户端，车机）↔ PSE（服务端，手机） |
| **MAP** | Message Access | 短信收发 | MNS（通知）/ MAS（访问） |
| SPP | Serial Port | 模拟串口，透传数据 | 车载诊断、老式模块 |
| HID | Human Interface Device | 键鼠手柄 | Host ↔ Device |
| OPP | Object Push | 推文件（名片/图片） | 已基本淘汰 |
| PAN | Personal Area Networking | 蓝牙网络共享 | 已被 Wi-Fi 热点取代 |

### 9.3 标准 UUID 速查（模板 `0000xxxx-0000-1000-8000-00805F9B34FB`）

| xxxx | 服务 |
|---|---|
| 1101 | SPP（串口） |
| 1105 | OPP（对象推送） |
| 110A / 110B | A2DP Source / Sink |
| 110C / 110E | AVRCP Target / Controller |
| 1108 / 1112 | HSP AG / HS |
| 111E / 111F | HFP HF / AG |
| 1124 | HID |
| 112E / 112F | PBAP PCE / PSE |
| 1132 / 1133 | MAP MAS / MNS |

### 9.4 Android 里 Profile 对应的 API 类

| Profile | 客户端类 | 说明 |
|---|---|---|
| A2DP | `BluetoothA2dp` | 作为 Sink 时很多方法需系统权限 |
| HFP/HSP | `BluetoothHeadset` | **支持 HF 和 AG 双角色**（耳机做 HF，车机做 AG） |
| AVRCP | `BluetoothAvrcpController` / `BluetoothAvrcpTarget`（隐藏类，系统权限） | 车机常做 Target |
| PBAP | `BluetoothPbapClient` | 系统应用才可用 |
| MAP | `BluetoothMapClient` / `BluetoothMap` | 同上 |
| HID | `BluetoothHidHost` / `BluetoothHidDevice`（API 28+） | 车机可做 Host |
| SPP | 无 Profile 类，直接 `createRfcommSocketToServiceRecord()` | 自由度高 |
| GATT | `BluetoothGatt` | BLE 通用 |

「**Android 视角**」普通 App 只能碰 A2DP/Headset/GATT 等少数几个；PBAP、MAP、AVRCP Target 等需要系统签名权限（`BLUETOOTH_PRIVILEGED`），**所以车机上的通讯录同步通常由系统 App 实现**。

---

## 十、BLE 高级应用：蓝牙钥匙、测距与 OTA

这三个是车机/物联网最常定制的 BLE 场景，都属于"**自定义 GATT 服务 + 自己设计协议**"的范畴。

### 10.1 蓝牙钥匙：怎么判断"人就在车旁"

```
① 手机作为 BLE 外设/中心（视方案而定），车机持续扫描/连接
② 车机读取信号强度 RSSI，粗估距离（RSSI 每降低约 6dB 距离翻倍，误差很大）
③ 靠近到阈值 → 通过自定义 GATT 特征发起挑战应答（Challenge-Response）鉴权
④ 校验通过 → 车机侧 CAN/BCM 执行解锁
```

**必须知道的三个工程真相**：

1. **RSSI 不能直接当距离用**（人体遮挡、姿态、天线方向都会剧变）——多数方案用 RSSI + 连续采样滤波 + 双端测距（手机侧同时测车机 RSSI）来提高可靠性；
2. **必须有密码学鉴权**，否则一个重放攻击设备就能开门（把挑战应答应答录下来重放）——所以**防重放（nonce/计数器）+ 防中继（距离边界或超时校验）**是核心；
3. **蓝牙 5.1 的测向（AoA/AoD）**能给出角度信息，比单纯 RSSI 精确，但需要多天线硬件支持。

### 10.2 BLE OTA / DFU：固件升级

```
① 连接并协商大 MTU（如 517）
② 通过控制特征发"开始升级"（带固件长度、CRC）
③ 按块写数据特征（每块几百字节），依赖 Write Response 或通知确认——**不能闷头狂写**
④ 每块确认后继续；出错重传该块
⑤ 写完后发"校验并切换"指令，设备重启进新固件
```

**性能与稳定性要点**：MTU 越大越快；用 `CONNECTION_PRIORITY_HIGH` 提连接间隔；写操作必须串行（6.4）；**升级期间不要允许系统省电杀后台**，且要考虑失败回滚（双 bank 固件）。

### 10.3 BLE 与"零连接采集"

传感器类设备常把关键数据直接放进**广播包**（0xFF 厂商数据）里——**不连接就能读，功耗最低**。代价是广播数据只有 31 字节（扩展广播可更多），且任何人可读（只能放非敏感数据）。

车机胎压监测（TPMS）、信标定位、资产标签都走这条路。

---

## 十一、Android 蓝牙架构：从 App 到芯片

### 11.1 全链路（对照分层思路）

```
┌ App 进程 ────────────────────────────────────────────────────────────┐
│ BluetoothManager（Context.getSystemService）                         │
│   ├─ BluetoothAdapter          适配器总入口（开关、扫描、已配对列表）    │
│   ├─ BluetoothDevice           远端设备（地址、名字、bondState）        │
│   ├─ BluetoothGatt              BLE 客户端（连接、读写、通知）          │
│   ├─ BluetoothLeScanner/Advertiser  BLE 扫描/广播                     │
│   ├─ BluetoothSocket           经典蓝牙 SPP 字节流（像 TCP Socket）    │
│   └─ BluetoothA2dp/Headset/PbapClient…  各 Profile 客户端              │
├ Binder / AIDL ──────────────────────────────────────────────────────┤
│ system_server: BluetoothManagerService（总管家：开关、权限、绑定列表） │
│   └─ IBluetooth / IBluetoothManager (AIDL)                          │
├ 蓝牙应用进程 com.android.bluetooth ──────────────────────────────────┤
│ AdapterService + 各 Profile Service（A2dpService / HeadsetService / │
│   GattService / PbapService / MapService / Avrcp* / Hid*…)          │
│   每个 Profile Service 内部都是 StateMachine（空闲/连接中/已连接）    │
├ Bluetooth Stack（native，JNI 桥接）──────────────────────────────────┤
│ 旧：system/bt（Fluoride：btif / bta / stack / hci）                   │
│ 新：packages/modules/Bluetooth（Gabeldorsche 重构 + APEX 模块化）      │
│   stack 内实现 L2CAP / SDP / RFCOMM / ATT / GATT / SMP / AVDTP       │
├ HCI（socket / HAL 通道）────────────────────────────────────────────┤
│ android.hardware.bluetooth@1.x（HIDL）或 AIDL IBluetoothHci（新）     │
├ Controller（芯片固件）→ 射频 → 空中 2.4GHz ─────────────────────────┤
└ Vendor：libbt-vendor.so / 固件下载 / BD 地址（常来自 /persist 或 NV）  └─
```

### 11.2 Adapter 状态机（`dumpsys bluetooth_manager` 里的状态来源）

```
OFF → BLE_TURNING_ON → BLE_ON → TURNING_ON → ON
ON  → TURNING_OFF → BLE_TURNING_OFF → OFF
```

`BLE_ON` 表示"BLE 已就绪但经典蓝牙未完全启动"——**这就是有些设备上"蓝牙还没开完，BLE 扫描已经能用"的原因**，也是排查"开关卡住"的关键状态。

### 11.3 权限（版本差异是最大的坑）

| Android 版本 | 需要的权限 |
|---|---|
| ≤ 11 | `BLUETOOTH`、`BLUETOOTH_ADMIN`，**BLE 扫描还需 `ACCESS_FINE_LOCATION`** |
| 12+ | `BLUETOOTH_SCAN`（可配 `neverForLocation`）、`BLUETOOTH_CONNECT`、`BLUETOOTH_ADVERTISE` |
| 系统应用专属 | `BLUETOOTH_PRIVILEGED`（PBAP/MAP/AVRCP Target、开关蓝牙等） |

Manifest 里要按版本声明两套（`maxSdkVersion` 切割），否则在新系统上要么权限报错、要么扫描无结果。

---

## 十二、车机场景：四个角色与 ROM 定制

### 12.1 四个角色

| 角色 | 用什么 Profile | 说明 |
|---|---|---|
| **手机互联** | HFP(HF) + A2DP(Sink) + AVRCP(CT) + PBAP(PCE) + MAP | 免提通话、音乐、通讯录、短信 |
| **蓝牙钥匙** | BLE 自定义 GATT | 靠近解锁，RSSI + 挑战应答（第 10 章） |
| **BLE 传感器接入** | BLE GATT（0x180F/0x181A 或厂商自定义） | 胎压 TPMS、OBD、温度传感器 |
| **被连接方（少见）** | 反向角色（AG、A2DP Source） | 车机做音频源给后排耳机、给手机提供网络 |

### 12.2 ROM 定制的关键配置

Profile 开关在 **Bluetooth 应用的资源里**（`packages/apps/Bluetooth/res/values/config.xml`，可用 overlay 覆盖）：

```xml
<bool name="profile_supported_a2dp">true</bool>
<bool name="profile_supported_a2dp_sink">true</bool>      <!-- 车机作为播放端 -->
<bool name="profile_supported_hs_hfp">true</bool>          <!-- 车机作为 HF -->
<bool name="profile_supported_hfpclient">true</bool>       <!-- 车机作为 AG -->
<bool name="profile_supported_avrcp_controller">true</bool>
<bool name="profile_supported_avrcp_target">true</bool>
<bool name="profile_supported_pbapclient">true</bool>
<bool name="profile_supported_map">true</bool>
<bool name="profile_supported_hid_host">true</bool>
```

**定制套路**：overlay 打开需要的 Profile → 确认 SELinux 策略放开（`hal_bluetooth_*`、`bluetooth_*`）→ 确认 HAL 实现（vendor 的 `IBluetoothHci`）→ 确认权限（起特权白名单里给系统蓝牙应用放行 `BLUETOOTH_PRIVILEGED` 等）。**任一步缺失，表现都是"Profile 不出现"或"连接后功能异常"**。

### 12.3 车机特有的坑

- **多设备与音频路由**：两台手机同时连（一台通话、一台音乐），路由由 `AudioPolicy` + 蓝牙音频 HAL 决定（见 8.4）。
- **音频焦点切换**：导航播报时压低/暂停蓝牙音乐（`AudioFocus` + ducking），与蓝牙栈是两套机制配合。
- **开机自动重连**：绑定信息在 `/data/misc/bluetooth/`，恢复出厂即丢；"出厂预设绑定"要谨慎处理安全与法规。
- **配对弹窗与驾驶态**：`CarUxRestrictions` 可能禁止弹出配对框，需设计免打扰策略。
- **系统时间**：时间错乱会影响部分加密流程（呼应网络串讲的证书时间问题）。
- **多用户**：车机的每个 Android 用户有各自的蓝牙配置与绑定列表，切换用户后"配对设备不见了"通常是正常的用户隔离，不是故障（对照《Android 存储机制详解》第 10 章）。

---

## 十三、调试工具箱

```bash
# ── Framework 状态 ──────────────────────────────
adb shell dumpsys bluetooth_manager          # 适配器状态、已绑定设备、各 Profile 连接情况
adb shell dumpsys bluetooth_manager | grep -iE "state|bonded|Profile"
adb shell settings get global bluetooth_on   # 蓝牙开关状态

# ── 音频路由（多设备场景必备）──────────────────────
adb shell dumpsys audio | grep -iE "bluetooth|a2dp|sco|focus"

# ── 日志 ──────────────────────────────
adb logcat -b all | grep -iE "Bluetooth|bt_|hci|bta_|gatt|smp|avdt|rfcomm"
adb logcat -s bt_stack bt_btif bt_bta_av btif     # 只看蓝牙 native 栈各 tag

# ── HCI 抓包（最有用的一招）──────────────────────────
# 1) 开发者选项打开"启用蓝牙 HCI 信息收集日志"（或 setprop persist.bluetooth.btsnooplogmode full）
# 2) 复现问题
# 3) 抓文件：/data/misc/bluetooth/logs/btsnoop_hci.log（老版本在 /data/misc/bluedroid/）
adb pull /data/misc/bluetooth/logs/btsnoop_hci.log
# 4) Wireshark 打开（原生支持 btsnoop），能看到
#    HCI Command / Event / ACL / L2CAP / ATT / GATT / SDP / RFCOMM 全链路
# 或者直接 adb bugreport 打包（含 btsnoop）

# ── 空中抓包（需专用硬件）──────────────────────────
# Frontline / Ellisys / nRF Sniffer：抓真实空口包，排查"根本没连上"类问题
```

**btsnoop 里怎么读**（按"谁主动"看方向）：

| 看到什么 | 说明 |
|---|---|
| `HCI_Command: Create Connection` | 主机要求芯片发起经典连接（后面跟 `Connection Complete` 看结果） |
| `HCI_Event: LE Advertising Report` | 收到 BLE 广播（含广播内容与 RSSI） |
| `HCI_Event: LE Connection Complete` | BLE 连接建立，含 interval/latency/timeout |
| `ATT_Read_Response` / `ATT_Handle_Value_Notification` | GATT 读到值 / 收到通知 |
| `SDP_Service_Search_Attribute_Request` | 服务发现（经典 Profile 能不能通就看这步） |
| `Encryption Change` / `SMP` 相关 | 配对/加密过程（失败原因常在这里） |

**分诊顺序**：

1. `dumpsys bluetooth_manager` —— 适配器状态、绑定状态、Profile 连接状态（相当于网络的"接口/路由"）；
2. `logcat | grep Bluetooth` —— framework 与 native 栈的报错；
3. `btsnoop` + Wireshark —— 看**空中到底发了什么**，区分"主机没发"vs"发了没回"。

---

## 十四、常见问题排查表

| 现象 | 最可能的原因 | 章节 | 第一手段 |
|---|---|---|---|
| 搜不到设备 | 设备未开可发现/未广播；Android 12+ 权限缺失 | 6.1、11.3 | 查权限 + logcat 看扫描回调 |
| 扫描到但连不上 | 已被别的主机占用；不在可连接广播状态；RPA 地址变化 | 6.1、7.4 | btsnoop 看连接后的错误码 |
| 连上后立刻断 | 连接参数违规（timeout ≤ (1+latency)×interval×2）、信号弱、配对未完成 | 6.1 | 看 `Disconnection Complete` 的 reason |
| GATT 读不到值 | 服务/特征 UUID 不对；未 `discoverServices()`；操作未串行 | 6.4 | btsnoop 看 ATT 层有无请求 |
| 收不到通知 | **只调了 `setCharacteristicNotification` 没写 CCCD** | 6.3 | 检查是否 `writeDescriptor(0x2902)` |
| 反复重连失败直到需重启蓝牙 | `gatt.close()` 未调用导致客户端泄漏 | 6.4 | 复查资源释放路径 |
| 车机能听歌不能打电话 | HFP 未启用或未连（只跑了 A2DP） | 8.1 | `dumpsys bluetooth_manager` |
| 音质差/断续 | 2.4G 干扰（Wi-Fi 共存）、编码器协商成低档、距离过远 | 3.4、8.1 | 换 5G Wi-Fi、看 AVDTP 协商编码 |
| 播放控制无效、无曲目信息 | AVRCP 角色/版本问题 | 8.3 | 检查 CT/TG 配置与元数据回调 |
| 通话/音乐走了错误的设备 | 音频路由选择问题（不是蓝牙连接问题） | 8.4 | `dumpsys audio` 看路由与焦点 |
| 通讯录不同步 | PBAP 需系统权限 + Profile 未开启 | 9.4、12.2 | 确认 `profile_supported_pbapclient` + 权限 |
| 配对弹窗不出现 | Just Works 无 UI 流程；驾驶态被 UX 限制拦截 | 7.2、12.3 | 看 SMP/配对日志与 UX restriction |
| 蓝牙钥匙时灵时不灵 | RSSI 抖动大/人体遮挡；未做滤波与双端测距 | 10.1 | 采样日志 + 提高鉴权鲁棒性 |
| BLE OTA 中途失败 | 操作未串行、连接间隔太大、进程被省电杀掉 | 10.2 | 提高优先级 + 分块确认 + 保活 |
| 蓝牙开关打不开 | 适配器状态机卡在 BLE_TURNING_ON；vendor 固件/HAL 异常 | 11.2 | `dumpsys bluetooth_manager` + vendor 日志 |
| 换用户后配对设备不见了 | 多用户数据隔离（正常） | 12.3 | 确认是否真的同一用户 |

---

## 十五、要动哪些文件：定制与排障速查

蓝牙是**vendor 与 framework 高度交织**的模块，所以比起"读源码路线"，更实用的是"**按目标反查要动哪些地方**"：

| 目标 | 要动的地方 |
|---|---|
| 开启/关闭某个 Profile | `packages/apps/Bluetooth/res/values/config.xml` 的 `profile_supported_*`（overlay 覆盖） |
| 修改蓝牙默认开关、默认名称 | 系统资源 overlay + `BluetoothManagerService` 的默认配置 |
| 支持 LE Audio / LC3 | Profile 配置 + 音频 HAL + vendor 编码器能力声明 |
| 加自定义 BLE 服务（蓝牙钥匙、传感器） | App 层 `BluetoothGattServer`；若要在系统侧介入则改 `GattService` |
| 免提/AG 角色行为（车机） | `HeadsetService`（AG 侧逻辑）+ `profile_supported_hfpclient` |
| 配对弹窗样式与流程 | `com.android.bluetooth` 的配对 UI + `Settings` 蓝牙页（或车机自研设置） |
| 蓝牙地址（BD_ADDR） | vendor 分区/`persist` 中的地址源；**不可随意改**（影响绑定与法规） |
| 固件下载与初始化 | vendor 的蓝牙 HAL 实现 + 固件文件（`/vendor/firmware`、`/etc/bluetooth`） |
| 共存（Wi-Fi/BT 抢天线） | vendor coex 配置（与 Wi-Fi 模块联动） |
| 权限问题（系统应用） | `privapp-permissions` 名单 + SELinux 策略（`bluetooth_*`、`hal_bluetooth_*`） |

**要读源码时的顺序**（只挑一条走通即可）：

1. **App 层状态流**：`BluetoothGatt` + `BluetoothGattCallback`（对照 6.4 流程）；
2. **总管家**：`BluetoothManagerService`（`IBluetoothManager`）→ `AdapterService`；
3. **状态机**：`AdapterService` + `AdapterState` 的迁移（对照 11.2）；
4. **Profile**：挑 `HeadsetService` 读透（HFP 有 AG/HF 双角色，最能体现 Profile 的复杂性）；
5. **native 栈**：`packages/modules/Bluetooth/system/stack` 按 `l2cap → gatt/att → smp → sdp/rfcomm → avdt` 顺序，逐个对上第 4 章的分层图；
6. **HCI 与 HAL**：`hal/bluetooth` 的 AIDL 接口 + vendor 实现。

---

## 十六、一图总结

```
┌─────────────────────────────────────────────────────────────────────┐
│  你的 App：BluetoothAdapter / BluetoothGatt / BluetoothSocket        │
│           Profile 类：A2dp、Headset、PbapClient、AvrcpController…      │
├─────────────────────────────────────────────────────────────────────┤
│  framework：BluetoothManagerService（system_server，管家）            │
│  com.android.bluetooth：AdapterService + 各 Profile Service(StateMachine) │
├─────────────────────────────────────────────────────────────────────┤
│  native stack：L2CAP → { RFCOMM/SDP | ATT-GATT/SMP | AVDTP }          │
│  （经典蓝牙走 SDP 发现 + RFCOMM 串口；BLE 走广播 + GATT 表格）          │
├──────────────────────── HCI（命令/事件/ACL）────────────────────────┤
│  Controller：链路层调度 + 跳频 + PHY 射频                              │
└──────────────────────── 2.4GHz 空中（与 Wi-Fi 抢频段）───────────────┘

两条技术路线记住这两句：
  经典蓝牙 = "先搜索，再点名建连，然后开串口或音频流" → 耳机、免提、串口
  BLE     = "平时举牌子吆喝（广播），需要时走近私聊（连接）看表格（GATT）"
            → 手环、车钥匙、传感器；蓝牙 5.2 起还能做 LE Audio

车机四件套：HFP(HF) 打电话 + A2DP(Sink) 放音乐 + AVRCP(CT) 控播放 + PBAP(PCE) 同步通讯录
          ；再加 BLE 一条自定义 GATT 通道做蓝牙钥匙/传感器。
```

---

*关联阅读（同目录）：《Android 开发者网络串讲》（协议分层与调试方法论的姊妹篇）、《Android 存储机制详解》（多用户隔离、`/data/misc` 落盘位置）。*
