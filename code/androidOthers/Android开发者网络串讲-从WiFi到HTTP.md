# Android 开发者网络串讲——从 Wi-Fi 到 HTTP，把每层都讲透

> 这篇的组织方式是**严格自底向上**：按包真正走过的顺序，从链路层一路讲到应用层，再讲四条横切主题（代理、长连接、局域网发现、内容分发），最后落到 Android 与实战。
>
> 为什么是这个顺序？因为网络是**真的分层栈**——上层的一切行为都能用下层解释。"切网后请求为什么全挂""接电话音质为什么变差"，答案都在下面几层。预设读者不懂网络，每个概念先用生活类比讲直觉；Android 专属内容用「**Android 视角**」标出，跳过不影响理解网络本身。

---

## 目录

**第一部分：地图**
1. [开场：网络到底在干什么](#一开场网络到底在干什么)
2. [分层模型与一次请求的完整旅程](#二分层模型与一次请求的完整旅程)

**第二部分：自底向上，逐层展开**
3. [链路层：Wi-Fi、MAC 地址与 ARP](#三链路层wi-fimac-地址与-arp)
4. [IP 层：地址、子网、路由、NAT](#四ip-层地址子网路由nat)
5. [端口：传输层的门牌号](#五端口传输层的门牌号)
6. [TCP：可靠传输是怎么做到的](#六tcp可靠传输是怎么做到的)
7. [UDP：不可靠但自由](#七udp不可靠但自由)
8. [DNS：域名怎么变成 IP](#八dns域名怎么变成-ip)
9. [TLS/HTTPS：明文怎么变成密文](#九tlsshttps明文怎么变成密文)
10. [HTTP：请求、响应、缓存与版本演进](#十http请求响应缓存与版本演进)

**第三部分：四条横切主题（不属于某一层，而是贯穿各层）**
11. [代理：流量如何被改道](#十一代理流量如何被改道)
12. [长连接与推送：服务器怎么主动找你的 App](#十二长连接与推送服务器怎么主动找你的-app)
13. [局域网发现：mDNS、SSDP 与投屏互联](#十三局域网发现mdnsssdp-与投屏互联)
14. [CDN 与负载均衡：大规模网站的两块基石](#十四cdn-与负载均衡大规模网站的两块基石)

**第四部分：落到 Android 与实战**
15. [Android 特有：多网络与网络切换](#十五android-特有多网络与网络切换)
16. [调试与性能归因](#十六调试与性能归因)
17. [常见问题排查表](#十七常见问题排查表)
18. [读源码路线](#十八读源码路线)
19. [一图总结](#十九一图总结)

---

## 一、开场：网络到底在干什么

抛开术语，网络只做一件事：**把一段字节从 A 机器送到 B 机器，并且让 B 能看懂**。

用寄快递类比，整个互联网就是一张巨大的快递网络：

| 快递世界 | 网络世界 | 本文哪一章 |
|---|---|---|
| 收件人地址"某某市某某区某某街道 88 号" | IP 地址 `203.0.113.10` | 4 |
| "收件人：3 楼张三" | 端口号 8080 | 5 |
| 公司前台代收所有快递再分给员工 | NAT（网络地址转换） | 4 |
| 先打 114 查公司地址再寄件 | DNS 域名解析 | 8 |
| 货运单号 + 签收回执（确认对方收到） | TCP 的确认与重传 | 6 |
| 用密码箱寄贵重物品，钥匙提前谈好 | TLS 加密 | 9 |
| 花店帮跑腿、门卫代收登记 | 代理 | 11 |

**另一个必须先建立的直觉**：网上传输的不是"一个文件"，而是**一个一个小包（packet）**。大文件被切成很多小包，各自独立走网络，到对端按编号重排。为什么要切？因为一根线路要被成千上万人共用——如果 1GB 文件独占线路发完，别人就都卡死了。分包就是"马路车流"模式：每辆车很小，可以穿插着走。

**最后一个先钉死的概念**：网络上两台机器通信，永远涉及"**两套地址**"——IP 负责端到端（寄到哪家公司），MAC 负责这一跳（这段路怎么走）。后面会反复用到这个区分。

---

## 二、分层模型与一次请求的完整旅程

### 2.1 为什么要分层

"把字节从 A 送到 B 并让 B 看懂"，拆开其实是好几件独立的事：信号怎么在空气里传播、包怎么送到隔壁机器、怎么跨山跨海、送到主机上的哪个程序、内容格式怎么约定。

每一层只解决一个问题，**只依赖下一层、只服务上一层**。好处是技术可以独立换代：Wi-Fi 换成 5G，你的 HTTP 代码一行不改；HTTP/1.1 升到 HTTP/3，网卡无感知。就像快递业换了新型卡车，寄件人收件人完全无感。

### 2.2 四层与实际职责

| 实际四层 | 干什么 | 代表协议 | 一句话类比 |
|---|---|---|---|
| 应用层 | 定义"内容长什么样" | HTTP、DNS、TLS*、WebSocket | 信里写什么语言 |
| 传输层 | 主机内哪个程序收、可不可靠 | TCP、UDP | 收件人是几楼几号 |
| 网络层 | 跨网络寻址与路由 | IP、ICMP | 快递单上的收货地址 |
| 链路层 | 相邻两台机器之间怎么传 | Wi-Fi(802.11)、以太网、ARP | 这段公路上怎么开车 |

*TLS 在分层图上位置有争议（介于应用与传输之间），不影响理解：记住"它加密 TCP 之上的内容"即可。

### 2.3 封装：每一层都在"套信封"

```
应用层造出数据：      [ HTTP 请求报文 ]
加 TCP 头 →           [ TCP头 | HTTP报文 ]                 ← 段 segment
加 IP 头  →           [ IP头 | TCP头 | HTTP报文 ]           ← 包 packet
加帧头帧尾 →          [ 帧头 | IP头 | TCP头 | 报文 | 帧尾 ]  ← 帧 frame
                    ↓ 变成电波/光信号
```

接收方反向剥壳，每层头部里都写着"上一层是什么"（IP 头里"协议号 6"表示里面是 TCP），所以剥完知道交给谁。

### 2.4 全景：一次请求的完整旅程

`https://api.example.com/v1/data` 的完整流程，后面的章节按这个顺序展开：

```
① DNS 解析（第 8 章）               先查"api.example.com 的 IP 是多少" → 203.0.113.10
② TCP 三次握手（第 5、6 章）        与 203.0.113.10 的 443 端口建可靠连接 → ESTABLISHED
③ TLS 握手（第 9 章）               验证对方证书 → 协商对称密钥 → 之后全加密
④ HTTP 请求（第 10 章）             组装请求行与头部，写进加密通道
⑤ IP 路由（第 4 章）                内核查路由表，决定从哪个网卡出、下一跳给谁
⑥ 链路层发出（第 3 章）             ARP 查网关 MAC → 组帧 → Wi-Fi 加密 → 电波发出
                                   ……逐跳转发抵达服务器，反向剥壳逐层上交
⑦ 响应原路返回，App 收到 JSON
```

**两个先后关系先钉死**（新手最容易乱）：

1. **DNS 在 TCP 之前**——不知道 IP，连握手都发不出去；
2. **TLS 在 TCP 之后**——TLS 握手本身是跑在 TCP 连接里的数据。

---

## 三、链路层：Wi-Fi、MAC 地址与 ARP

### 3.1 这一层的边界

链路层只负责一件事：**把包送到"同一片网络"里的下一台设备**。注意是"下一台"而不是终点——就像高速上你只关心"下个出口怎么走"，跨省路线是 IP 层的事。

"同一片网络"指：手机与路由器之间、路由器与光猫之间。一旦出了网关，就交给 IP 层。

### 3.2 MAC 地址：设备的身份证号

每块网卡出厂烧录了全球唯一的 48 位地址，如 `a4:b1:c1:33:22:11`。**MAC 与 IP 的区别**是初学头号困惑：

- **MAC 像身份证号**：全球唯一、出厂即定、跟随设备一生；但不能靠它寄快递（你没法靠身份证号把包裹送到"当前住在杭州的那个人"手里）。
- **IP 像邮寄地址**：随位置变化（从家 Wi-Fi 到公司就变了），但寻址靠它。

配合方式：**IP 负责端到端找到目标主机，MAC 负责这一跳把帧送到下一台设备**。包每过一个路由器，帧里的目的 MAC 换成下一段的，但 IP 头里的源/目的 IP 全程不变。

### 3.3 ARP：查"IP 对应的 MAC"

```
手机：广播喊话"谁是 192.168.1.1？报上你的 MAC！"
路由器：单播回答"我是，MAC 是 aa:bb:cc:dd:ee:ff"
手机：记进 ARP 缓存表（下次直接用）
```

`adb shell arp -a` 看缓存表。**排查"连上 Wi-Fi 但上不了网"时，如果 ARP 里连网关都没有，说明链路层都没通**。

### 3.4 Wi-Fi(802.11) 与 WPA 加密

Wi-Fi 的接入流程：**扫描（周围有哪些 AP）→ 关联 → 认证（WPA 的 4 次握手，证明双方掌握密码，密码本身从不在空中传输）→ DHCP 拿 IP**。

**关键认知：WPA 只保护"手机 ↔ 路由器"这一段**。出了路由器交给运营商网络，WPA 管不着。所以连公共 Wi-Fi ≠ 安全——端到端保护靠 TLS（第 9 章）。**两层加密各管一段，互不替代**。

「**Android 视角**」Wi-Fi 栈：`WifiManager`（App 入口）→ system_server 的 `WifiService/ClientModeImpl`（状态机）→ 独立进程 `wpa_supplicant`（管认证）→ HAL → 驱动。"连上 Wi-Fi"和"能上网"是两回事：系统发 HTTP 探测验证连通性（`NetworkMonitor`），这就是偶尔显示"此网络无法访问互联网"的原因。

---

## 四、IP 层：地址、子网、路由、NAT

### 4.1 IP 地址的结构

IPv4 是 32 位，写成 `192.168.1.105`，分两部分：

```
192.168.1.105 / 24
└────┬─────┘ └┬┘
  网络部分   主机部分（哪个小区 / 小区里哪户）
```

判断"对方和我是不是同一个小区"靠**子网掩码**：IP 与掩码按位与，结果相同 = 同网段（直接走链路层），不同 = 跨网络（交给网关）。

**私有地址段**（只在局域网有效，全球有几亿台设备共用）：

| 段 | 范围 | 常见用途 |
|---|---|---|
| 10.0.0.0/8 | 10.x.x.x | 企业内网 |
| 172.16.0.0/12 | 172.16~31.x.x | 企业内网、Docker |
| 192.168.0.0/16 | 192.168.x.x | 家用路由器、车机热点 |

### 4.2 路由：每一跳只看下一个出口

没人知道全程路线，**每台路由器只负责"查表决定下一跳"**。匹配规则是**最长前缀优先**：`203.0.113.0/24` 与 `0.0.0.0/0`（默认路由）同时存在时，目标 `203.0.113.10` 优先匹配更具体的前一条。

```bash
adb shell ip route     # 最后一条 default 就是"其他所有流量交给网关"
```

**TTL**：每个路由器减 1，减到 0 丢弃并回信，防止路由环路打转。`ping` 结果的 TTL 可粗略估算跳数。

### 4.3 NAT：全公司共用一个对外的门牌

```
内网                              路由器(NAT, 公网 203.0.113.1)         公网
192.168.1.105:51234  ──▶  改写成 203.0.113.1:40001  ──▶  服务器
服务器回包 → 查 NAT 映射表改回 192.168.1.105:51234 → 手机
```

三个开发现象由此解释：

1. 服务器日志里看到的客户端 IP 是网关的；
2. **长连接会静默断掉**：NAT 表项有超时（几十秒到几分钟无流量就清除），之后服务器发来的包被路由器丢弃——**这就是心跳包存在的理由**（不是为了检测，而是让 NAT 表项不过期）；
3. **App 做服务端难**：公网用户无法主动穿透进你的 NAT，需要打洞技术（STUN/TURN，P2P 场景才需要）。

### 4.4 ICMP：IP 层的"事故报告系统"

`ping`（Echo 请求）和 `traceroute`（利用 TTL 递增让沿途路由器回话）都基于它。`Connection refused` 的 RST、`Host unreachable` 也来自这一层。**排查网络永远先 ping，因为它最贴近 IP 层**。

### 4.5 DHCP：入网时怎么拿到 IP

连上网后 IP 是 DHCP 分配的：广播"我需要 IP"→ 服务器（通常就是路由器）从地址池租一个，连同**子网掩码、网关、DNS 服务器地址**一起下发。**注意 DNS 服务器地址也是这里发的**——第 8 章的查询起点就是它。

### 4.6 IPv6：地址不够用的终局

IPv4 只有约 43 亿个地址，早已不够；IPv6 用 128 位。手机蜂窝网络通常 IPv6 优先（`getaddrinfo` 返回两种地址，协议栈自动选）。要知的坑：个别老服务器只有 IPv4，在纯 IPv6 网络下连不上，需网关转换。

「**Android 视角**」IP 配置由独立进程 NetworkStack 里的 `DhcpClient` 完成，`dumpsys connectivity` 里每个网络的 `LinkProperties` 是本机 IP/网关/DNS 的权威来源。

---

## 五、端口：传输层的门牌号

### 5.1 为什么需要端口

IP 把包送到**主机**，但主机上几十个程序同时在跑。**端口就是主机里的"房间号"**。一条连接由**五元组**唯一标识：

```
(源 IP, 源端口, 目的 IP, 目的端口, 协议)
例：(192.168.1.105, 51234, 203.0.113.10, 443, TCP)
```

服务器靠"源 IP + 源端口"区分同一时刻的几千个客户端——**你能和同一网站开 10 个连接，靠的是 10 个不同的本地源端口**。

### 5.2 端口号的分类

| 范围 | 名称 | 绑定权限 | 说明 |
|---|---|---|---|
| 0~1023 | 熟知端口 | root/系统 | 标准服务 |
| 1024~49151 | 注册端口 | 任意 | 常见软件监听区 |
| 49152~65535 | 临时端口 | 内核自动分配 | **客户端侧**出站连接用 |

**必背端口速查**：

| 端口 | 用途 | 备注 |
|---|---|---|
| 20/21 | FTP | 老文件传输 |
| 22 | SSH/SCP | 远程登录 |
| 53 | DNS | UDP 为主，响应过大或区域传输用 TCP |
| 80 | HTTP | 明文 |
| 443 | HTTPS | HTTP over TLS |
| 465/587 | SMTPS/SMTP | 邮件发送 |
| 853 | DNS over TLS | 加密 DNS |
| 1080 | SOCKS5 代理 | 本地代理工具默认 |
| 1900 / 5353 | SSDP / mDNS | 局域网发现（第 13 章） |
| 3306/5432/6379 | MySQL/PostgreSQL/Redis | 后台调试常碰 |
| 8080/8443 | HTTP 备用 | 开发服务器、代理 |
| 5555/5037 | adb over TCP / adb server | Android 特有 |

### 5.3 临时端口耗尽：一个真实事故

每次出站连接内核从临时端口区间挑一个空闲端口，连接关闭后端口不能立刻复用（要等 TIME_WAIT，见 6.4）。压测、爬虫、高频短连接场景下几万个端口全卡在 TIME_WAIT，新连接直接报：

```
java.net.BindException: Cannot assign requested address
```

**正解不是调大端口范围，而是减少短连接**——连接复用（HTTP keep-alive、OkHttp 连接池）才是根治方案。

### 5.4 端口占用的排查

```bash
adb shell ss -tnlp | grep 8080        # 谁占了这个端口
```

注意绑定地址：`0.0.0.0:8080` = 监听所有网卡（外部可达）；`127.0.0.1:8080` = 只监听本机回环（外部连不上）。**只绑 127.0.0.1 的服务，adb reverse 之后车机照样访问不到**——这是"reverse 不通"的第一大原因。

「**Android 视角**」端口转发两件套：

```bash
adb forward tcp:8080 tcp:8080   # 电脑访问 localhost:8080 → 设备的 8080（调试设备上的服务）
adb reverse tcp:9090 tcp:9090   # 设备访问 localhost:9090 → 电脑的 9090（App 连电脑上的 mock）
```

---

## 六、TCP：可靠传输是怎么做到的

### 6.1 TCP 解决什么问题

IP 的承诺是"尽力而为"：包可能丢、乱序、重复。TCP 在它之上补齐：**可靠（收到就确认，没确认就重传）、有序（按序列号重排）、流量控制（对方收不下就减速）、拥塞控制（网络堵了全体减速）**。HTTP、gRPC、WebSocket 全跑在它上面。

### 6.2 序列号：可靠传输的基石

TCP 给每个字节编号。发送方发出"从第 1000 字节开始的 500 字节"，接收方回"确认收到 1500"——**ack 的含义是"1500 之前的都收到了，下一个请发 1500"**。超时没等到 ack 就重传。接收方收到乱序包会缓存重排，对上层永远呈现连续字节流。

### 6.3 三次握手：为什么是三次

```
客户端                                服务器
  │ ─── SYN，seq=1000 ────────────────▶ │
  │ ◀── SYN+ACK，seq=5000，ack=1001 ─── │
  │ ─── ACK，ack=5001 ────────────────▶ │
  │          双方进入 ESTABLISHED        │
```

**三次的本质是双方各确认一次对方的起始编号**。只两次的话，服务器无法确认"客户端收到了我的 SYN+ACK"——一个迷路很久的旧 SYN 迟到时，服务器会建立错误的连接。

### 6.4 四次挥手、TIME_WAIT 与 CLOSE_WAIT

```
A ── FIN ──▶ B     A："我没数据要发了"
A ◀─ ACK ── B
A ◀─ FIN ── B     B："我也没有了"
A ── ACK ─▶ B      A 等 2MSL 才真正关闭（TIME_WAIT）
```

**TIME_WAIT 只出现在主动关闭方**，等 2MSL 是为了让网络里残留的旧包自然消亡，不污染下一个同端口的新连接。客户端频繁短连接会堆积大量 TIME_WAIT——正常但浪费端口，再次指向"连接复用"。

**CLOSE_WAIT 堆积 = 你的代码 bug**：它表示"对方已关，我还没关"。服务端看到成百上千 CLOSE_WAIT，直接查连接泄漏（Android 上常见于 OkHttp 的 `Response` body 没消费/没关闭）。

**RST（暴力复位）**：不走四次挥手直接报废连接。触发：端口没人监听、NAT 表项清除后旧包到达、防火墙拦截、服务端崩溃。表现为 `ConnectException` / `Connection reset`。

### 6.5 重传与拥塞控制（弱网优化的理论根基）

- **超时重传**：超时（RTO，初始约 200ms~1s，每次翻倍退避）就重传。**一次丢包 = 至少一个 RTO 的卡顿**——弱网下"卡一下"的体感来源。
- **快速重传**：收到 3 个重复 ack 不等超时，立即重传。
- **慢启动**：新连接发送窗口从小（约 10 个包）起步，每轮成功翻倍。**含义：新连接头几十毫秒吞吐很低**——这就是连接复用/多路复用能明显提速的根本原因。
- **拥塞避免**：窗口涨到阈值后线性增长，检测到丢包减半。

**车机场景**：隧道、地库连续丢包时 TCP 反复退避到秒级延迟甚至断连，这正是 QUIC（10.6）要解决的场景。

### 6.6 粘包与拆包：TCP 没有消息边界

TCP 交付给应用的是**无结构的字节流**。你发 "HELLO" 和 "WORLD"，对方可能一次收到 "HELLOWORLD"，也可能收到 "HEL" 再收到 "LOWORLD"。这不是 bug，是设计（它只管字节可靠有序，不管语义切分）。

三种解法（所有基于 TCP 的协议都要选一种）：

1. **定长**：每条消息固定 100 字节（浪费，少用）；
2. **分隔符**：用 `\n` 等特殊字符结尾（文本协议常用，需转义）；
3. **长度前缀**：先发 4 字节表示总长，再发内容（二进制协议主流，HTTP 的 `Content-Length` 本质就是它）。

「**Android 视角**」两个超时 API 必须分清：

```kotlin
socket.connect(InetSocketAddress(host, 80), 5000)  // ① 握手超时：只管建立连接
socket.soTimeout = 10_000                          // ② 读超时：read() 最多阻塞这么久
```

只设 ① 会遇到"连上了但读数据永远卡住"；只设 ② 会遇到"连都连不上还在傻等"。

---

## 七、UDP：不可靠但自由

UDP 就是"裸的 IP + 端口号"：无连接、不握手、不确认、不重传、不排序。好处是**零延迟开销**，坏处是**丢了就是丢了**。

| 维度 | TCP | UDP |
|---|---|---|
| 连接 | 三次握手 | 无连接，拿起来就发 |
| 可靠性 | 确认 + 重传，必达 | 尽力而为 |
| 有序性 | 严格按序 | 乱序 |
| 数据形态 | 字节流（无边界，要自己设计） | 报文（一次一份，**有边界**） |
| 头部开销 | 20 字节起 | 8 字节 |
| 典型应用 | HTTP、gRPC、SSH | DNS、视频直播、游戏、NTP、QUIC、mDNS/SSDP |

**谁在用 UDP，为什么**：

- **DNS**：一问一答，为一个 IP 走三次握手太浪费；丢了重发一次查询就行。
- **实时音视频/游戏**：要的是"最新画面"，丢一帧无所谓，等重传 200ms 就滞后了——**宁可丢，不可等**。
- **局域网发现**（第 13 章）：mDNS/SSDP 靠组播，只有 UDP 支持。
- **QUIC/HTTP/3**（10.6）：在 UDP 之上**自己实现**一套可定制、按流独立的可靠传输。这是"UDP 只是底座，可靠性可以按需自己造"的最佳证明。

**编程模型的差异要记住**：UDP 报文有边界（一次 send 对应一次 receive），TCP 字节流无边界（6.6 的粘包问题）。

---

## 八、DNS：域名怎么变成 IP

### 8.1 为什么需要 DNS

人记得住 `api.example.com`，机器只认 IP。DNS 是互联网的查号台，且**必须先于一切连接**。

### 8.2 域名的层级结构

```
www.example.com.        ← 末尾有隐藏的根 "."
 │     │      │   │
主机  二级域 顶级域 根域
```

层级从右往左升高，决定了查询顺序：**先问根"管 .com 的是谁"，再问 .com"管 example.com 的是谁"，最后问 example.com 的权威服务器"www 的 IP 是多少"**。

### 8.3 递归查询与迭代查询

设备发起的是**递归查询**（你必须给我最终答案），DNS 服务器之间走**迭代查询**（我不知道，但我告诉你该问谁）：

```
手机 → 本地 DNS："www.example.com 的 IP？"
   本地 DNS（无缓存时逐级迭代）：
      → 根服务器："管 .com 的是这批地址"
      → .com 服务器："管 example.com 的是这些"
      → example.com 权威服务器："www = 93.184.216.34"
   缓存结果（按 TTL）→ 返回手机
```

### 8.4 缓存层级：为什么"改了配置不生效"

```
浏览器/JVM 内缓存 → 系统缓存 → 本地 DNS（运营商）→ 权威服务器
      秒级              分钟级        按域名 TTL
```

测试期可用 `hosts` 强制指定（Android 上 remount 后编辑 `/system/etc/hosts`）。

### 8.5 记录类型

| 类型 | 含义 | 场景 |
|---|---|---|
| A | 域名 → IPv4 | 最基本 |
| AAAA | 域名 → IPv6 | IPv6 网络 |
| CNAME | 域名 → 另一个域名 | **CDN 的实现基础**（14.1） |
| MX / TXT / NS | 邮件 / 文本验证 / 域的 DNS 服务器 | 域名验证、SPF |

### 8.6 明文 DNS 的三宗罪与加密 DNS

明文 UDP 53 有三个问题：**隐私**（中间人知道你访问什么域名）、**劫持**（运营商换成广告页/缓存节点）、**投毒**（伪造响应）。解法：**DoT（DNS over TLS，853）**、**DoH（DNS over HTTPS，443，伪装成普通 HTTPS 更难被封）**、DoQ（DNS over QUIC）。

「**Android 视角**」App 查询链路有 Android 特有的中转：`InetAddress.getByName()` → netd 进程的 `DnsProxyListener` → 系统 DNS（App 进程不直接读 DNS 配置）。JVM 层另有一层缓存（成功结果默认约 30 秒）。主动做 DNS 分流/自选节点时用 `android.net.DnsResolver`（API 28+）。

**`UnknownHostException` 分诊**：它 ≠ 没网。可能是域名不存在（真 NXDOMAIN）、DNS 服务器不可达、被劫持返回空、JVM 缓存了失败结果。分诊方法见第 17 章。

---

## 九、TLS/HTTPS：明文怎么变成密文

### 9.1 为什么需要 TLS：三个威胁

HTTP 是明文的，经过运营商、骨干网时路径上任何一环都能：**窃听**（看到账号密码）、**篡改**（插广告、改金额）、**冒充**（假基站/假 DNS 带你去假网站）。TLS 一次解决三个问题：**加密、完整性（篡一比特就校验失败）、身份认证（证书证明"我真的是 example.com"）**。HTTPS = HTTP over TLS，除此之外没区别。

### 9.2 三件密码学工具的直觉

- **对称加密（AES/ChaCha20）**：加密解密同一把钥匙，极快，适合海量数据。致命问题：**钥匙怎么安全地给对方**？明文寄钥匙等于白加密。
- **非对称加密（RSA/ECDHE）**：公钥可公开，**公钥加密的只有私钥能解**。解决钥匙分发，但极慢（慢百倍以上），只能加密少量数据。
- **哈希（SHA-256）**：任意数据压成固定长度"指纹"，变一比特指纹全变，且不可逆。用于完整性校验和签名。

**TLS 的组合拳因此显而易见**：用非对称加密安全地"商量"出一把对称密钥，之后全部数据用对称密钥加密。这个商量过程就是密钥交换。

### 9.3 密钥交换的直觉：怎么在众目睽睽下商量出秘密

Diffie-Hellman 的**调色板类比**：

```
公开约定一个"基色"（人人可见）：
  A 私下加一个秘密色发给 B      B 私下加另一个秘密色发给 A
  A 拿到 B 的混合色再加自己的秘密色 → 得到最终色
  B 拿到 A 的混合色再加自己的秘密色 → 得到同一个最终色
旁观者两种混合色都看到了，倒推不出各自的秘密色
（颜料混合容易、分离极难——数学上对应离散对数难题）
```

两人从没传过秘密，却在众目睽睽下得到同一个只有他俩知道的秘密。TLS 1.3 用其椭圆曲线版本（**ECDHE**），每次连接用新的临时密钥对——即使服务器长期私钥日后泄漏，**过去的流量也解不开**（前向安全）。TLS 1.2 的老式 RSA 密钥交换没这性质，已淘汰。

### 9.4 握手全流程（TLS 1.2 与 1.3 对照）

```
TLS 1.2（2-RTT）:
Client ── ClientHello(随机数+支持的套件) ──────────▶ Server
Client ◀── ServerHello + 证书 + 密钥交换参数 ─────── Server
Client ─── 密钥交换 + ChangeCipherSpec + Finished ─▶ Server
Client ◀── Finished ────────────────────────────── Server
                     ══ 开始加密通信 ══

TLS 1.3（1-RTT）:
Client ── ClientHello + key_share(直接带ECDHE参数) ─▶ Server
Client ◀── ServerHello + 证书 + Finished ──────────── Server
Client ── Finished +【第一条加密应用数据】──────────▶ Server
```

1.3 快在把密钥交换参数直接塞进第一条消息，砍掉一个 RTT；恢复会话时甚至 0-RTT（用老密钥重放数据，有重放风险，只适合幂等请求）。

**会话恢复**：完整握手贵（1~2 RTT + 非对称运算），TLS 用 Session Ticket/PSK 恢复：上次结束时服务器给你一张"票"，重连出示即可跳过证书验证和密钥交换。「**Android 视角**」OkHttp 的 `ConnectionPool`（默认 5 条空闲连接、5 分钟存活）复用的正是"TCP 连接 + TLS 会话"，弱网下命中率直接决定体验。

### 9.5 证书体系：凭什么相信"这把公钥是 example.com 的"

密钥交换防了窃听，但防不住冒充。证书体系用**信任链**解决：

```
系统信任库预装的根 CA（DigiCert、GlobalSign…）
   │ 签名认证 ↓
中间 CA
   │ 签名认证 ↓
服务器证书（example.com 的公钥 + 域名 + 有效期）
```

**"签名"的含义**：上级用自己的私钥对下级证书的哈希加密；客户端验证 = 用上级公钥解出哈希、与现场算的比对——**逐级验签，直到命中系统信任库里的根**。类比：不认识申请人，但他的介绍信由你认识的局长签发，信上有局长亲笔印章（伪造成本极高）。

App 侧要验证四点，任一点失败握手中止：

1. **签名链完整且根可信**；
2. **域名匹配**（证书 SAN 要包含你连的域名；连 IP 时要有 IP SAN）；
3. **有效期**未过；
4. **未被吊销**（OCSP/CRL）。

| 报错 | 真实原因 |
|---|---|
| `Trust anchor for certification path not found` | 自签证书没进信任库 / 抓包工具证书未装 / **服务器漏发中间证书（最常见）** |
| `Hostname 'xxx' not verified` | 域名不匹配（IP 直连但证书没 IP SAN） |
| `Certificate expired` / 日期相关 | 证书过期，或**设备系统时间错乱**（车机 GPS 授时失败时高发——证书验证极依赖时钟） |

「**Android 视角**」两个专属机制：

- **Network Security Config**（API 24+）：XML 声明每个域名的信任源与是否允许明文。Android 9 起默认禁止明文 HTTP——App 突然连不上 `http://` 接口就是它。

  ```xml
  <network-security-config>
      <domain-config cleartextTrafficPermitted="false">
          <domain includeSubdomains="true">api.example.com</domain>
          <trust-anchors><certificates src="@raw/ca_internal"/></trust-anchors>
      </domain-config>
  </network-security-config>
  ```

- **Certificate Pinning**：OkHttp `CertificatePinner` 把服务器公钥哈希写死在 App 内，连系统 CA 签的合法证书也只认锁定那把——防"攻击者从 CA 骗来合法证书"。**风险**：锁了又没备用证书，服务端一换证书存量 App 全废。最佳实践：锁中间 CA 公钥 + 至少留一把备用。

### 9.6 SNI 与一个隐私遗留问题

TLS 握手的 ClientHello 里明文写着要访问的域名（SNI，让一台服务器挂多个 HTTPS 站点成为可能）——所以**"你访问了哪个域名"在加密 DNS + TLS 时代仍可能被中间人看到**（内容看不到，域名看得到）。ESNI/ECH 是后续演进，了解即可。

---

## 十、HTTP：请求、响应、缓存与版本演进

### 10.1 HTTP 在整个栈里的位置

前面所有层解决的都是"**可靠加密地把字节送到**"，HTTP 才定义"**字节的内容是什么意思**"。

### 10.2 一条请求的解剖

```http
GET /v1/data?id=1 HTTP/1.1          ← 请求行：方法 + 路径 + 版本
Host: api.example.com               ← 必带：一台服务器挂多个网站，靠它路由
User-Agent: okhttp/4.12.0
Accept: application/json            ← 我能接受/想要的格式
Accept-Encoding: gzip               ← 我支持压缩（OkHttp 自动加、自动解压）
Cookie: session=abc123
（空行）
（请求体，GET 通常为空）
```

```http
HTTP/1.1 200 OK                     ← 状态行
Content-Type: application/json      ← 响应体格式
Content-Length: 128                 ← 响应体多长（读响应的依据，见 6.6）
Cache-Control: max-age=300          ← 缓存指令（见 10.5）
Set-Cookie: session=abc123; Path=/
（空行）
{"id":1,"name":"hello"}
```

### 10.3 方法与状态码

**方法（幂等性是关键概念）**：GET（读，幂等）、POST（创建/提交，不幂等）、PUT（整体替换，幂等）、DELETE（幂等）、PATCH（部分更新）、HEAD（只要响应头，探活用）。**幂等 = 执行一次和多次效果相同**——幂等的方法可安全重试，这是网络自动重试机制的判断依据（GET 失败敢重试，POST 默认不敢）。

| 族 | 含义 | 必须认识的 |
|---|---|---|
| 2xx | 成功 | 200、**204**（成功无内容）、206（断点续传） |
| 3xx | 重定向 | **301**（永久，会缓存）、**302**（临时）、**304**（Not Modified，见 10.5） |
| 4xx | 客户端错 | **400**（参数错）、**401**（未登录/凭证无效）、**403**（无权限）、**404**、**429**（被限流） |
| 5xx | 服务端错 | 500、502（网关收到上游无效响应）、503（服务暂不可用）、504（网关等上游超时） |

502/504 出现说明流量经过了**网关/反向代理**（第 11、14 章），问题在网关后面。

### 10.4 Cookie 与会话：HTTP 怎么"记住"你

HTTP 无状态——每个请求独立，服务器不记得你上一秒来过。登录态靠 Cookie：

```
登录成功 → 响应 Set-Cookie: session=abc123
之后每个请求 → 自动带上 Cookie: session=abc123 → 服务器认出你是谁
```

移动端多数用 **Token（放在 `Authorization: Bearer xxx` 头）替代 Cookie**，本质相同（服务器发你一张凭证，你每次出示），只是存储位置和失效策略由 App 控制。

### 10.5 缓存：省流量省时间的第一杠杆

**强缓存**——直接用本地副本，连请求都不发：

```
响应带 Cache-Control: max-age=3600 → 一小时内再次请求直接用本地副本（零耗时）
```

**协商缓存**——过期后问服务器"还能用吗"：

```
客户端：If-None-Match: "etag-v3"      带上次的资源指纹
服务器：没变 → 304 Not Modified（不带响应体，省流量）；变了 → 200 + 新内容 + 新 ETag
```

（老式 `Last-Modified`/`If-Modified-Since` 按时间判断，精度只到秒，优先用 ETag。）指令速查：`no-store`（彻底不存）、`no-cache`（可存但每次必须协商）、`max-age`（新鲜期秒数）。

「**Android 视角**」OkHttp 完整实现了这套协议，但需显式开启：

```kotlin
val client = OkHttpClient.Builder()
    .cache(Cache(File(context.cacheDir, "http"), 50L * 1024 * 1024))
    .build()
Request.Builder().cacheControl(CacheControl.FORCE_NETWORK)  // 强制走网络（仍可 304 协商省流量）
Request.Builder().cacheControl(CacheControl.FORCE_CACHE)    // 离线兜底
```

注意：OkHttp 缓存只对 GET 生效；服务端不下发 `Cache-Control` 就默认不缓存，需和后端约定。

### 10.6 版本演进：1.1 → 2 → 3（按体感讲）

| 版本 | 底层 | 关键改进 | 遗留问题 |
|---|---|---|---|
| HTTP/1.1 | TCP | keep-alive 长连接、chunked 分块 | **同一连接请求串行**（上一个没回完下一个干等）；浏览器靠开 6~8 条连接缓解 |
| HTTP/2 | TCP | **二进制分帧 + 多路复用**（一条连接并发几十个请求）、头部压缩 HPACK、服务器推送 | HTTP 层队头阻塞解决了，但 **TCP 层丢包仍卡住所有流**（TCP 保证全局有序） |
| HTTP/3 | **QUIC(UDP)** | 可靠传输搬到用户态重新实现：**按流独立重传**、**0-RTT**、**连接迁移** | 需服务端支持，尚在普及 |

**连接迁移值得单独讲**：TCP 连接由四元组标识，手机 Wi-Fi 切蜂窝时 IP 变了连接必死。QUIC 用 **Connection ID** 标识连接（与 IP 无关），切网后凭 ID 找回连接——**下载不打断、视频不卡顿**。这是"在 UDP 上重建传输层"最动人的成果。

「**Android 视角**」OkHttp 自动协商 HTTP/2（TLS 握手的 ALPN 扩展顺带约定版本）；HTTP/3 需 OkHttp 5 显式开启或换 Cronet。

---

## 十一、代理：流量如何被改道

### 11.1 三种立场

```
正向代理（站在客户端这边，客户端主动配置）：
  App ──▶ 正向代理(Charles/公司网关) ──▶ 服务器
  服务器看到的来源是代理；用于抓包调试、企业审计、访问控制

反向代理（站在服务器那边，客户端毫不知情）：
  App ──▶ 反向代理(Nginx/网关/CDN 边缘) ──▶ 内部真实服务
  负载均衡、灰度发布、SSL 卸载都在这层

透明代理（链路上强制插入，客户端不知情）：
  App ──▶ [运营商/防火墙劫持重定向] ──▶ 服务器
  404 劫持页、DNS 广告注入就是它
```

### 11.2 HTTP 代理的两种模式

**模式一：转发明文 HTTP**。代理是"完整的 HTTP 客户端"——App 发 `GET http://api.example.com/v1 HTTP/1.1`（请求行带完整 URL），代理自己解析、自己连服务器、把响应回传。**代理能读懂全部内容**。

**模式二：HTTPS 走 CONNECT 隧道**。HTTPS 是密文，代理既看不懂也不该看，于是协议约定：

```
App                          代理(Charles)                 服务器
  │ ── CONNECT api.example.com:443 HTTP/1.1 ──▶ │
  │ ◀── HTTP/1.1 200 Connection Established ─── │
  │ ══════ 这条连接此后变成纯字节隧道 ═══════════▶ │
  │   TLS 握手和加密的 HTTP 全在隧道里跑，代理只搬字节
```

CONNECT 的语义就是："帮我和目标 443 建一条 TCP 隧道，然后闭嘴别听"。

### 11.3 HTTPS 抓包 = 中间人攻击（把 9.5 和这里连起来）

代理想看 HTTPS 明文，唯一办法是两头欺骗：

```
App ◀── 假证书"api.example.com"(Charles 自签 CA) ── Charles ◀── 真证书 ── 真服务器
      App↔Charles 一条 TLS                          Charles↔服务器 另一条 TLS
```

对 App 装作服务器（现签一张 `api.example.com` 的假证书），对服务器装作客户端，解密→记录→重加密。**所以手机装 Charles 证书就是给中间人发通行证；Android 9 之后系统不再默认信任用户 CA，还要在 NSC 里显式声明**——抓包配置失败的所有报错都对应 9.5 的表格。若 App 做了证书锁定，假证书会被当场拒绝，表现为握手失败而非"抓不到包"。

### 11.4 SOCKS5、PAC 与系统代理的真相

- **SOCKS5**：传输层的"哑代理"，不解析任何应用协议，只做"和目标建 TCP/UDP 连接、搬字节、可选认证"。因为什么都不懂，所以什么都能代理（WebSocket、gRPC、自定义协议、游戏流量）。本地代理工具监听 1080 端口就是它。
- **PAC（Proxy Auto-Config）**：一段 JS 函数 `FindProxyForURL(url, host)`，按域名/网段返回"走哪个代理或直连"。企业网做分流（内网直连、其余走网关）靠它。
- 代理可串联：`App → 公司代理 → 上级代理 → 目标`，每跳只知道上一跳。

**"代理没生效"的本质**：系统代理只是"App 可读的配置"，不是内核级的强制规则：

| 流量类型 | 认系统代理吗 | 原因 |
|---|---|---|
| OkHttp / HttpURLConnection | ✅ 走 | 主动读 `ProxySelector.getDefault()`，HTTPS 自动转 CONNECT |
| 自己 `new Socket()` 直连 | ❌ 不走 | 内核压根不知道"系统代理"存在 |
| native 代码（C 层 connect） | ❌ 不走 | 同上 |
| 显式 `Proxy.NO_PROXY` 的库 | ❌ 不走 | 库主动绕过 |

**想抓全部流量**，要么逐个库强制指定代理，要么用 **VPN 型抓包**（用 Android VpnService 建 tun 网卡，把全设备的 IP 包吸进来重新转发，Packet Capture/HttpCanary 就是这个原理，代价是接管整机流量）。

「**Android 视角**」系统代理来自 Wi-Fi/以太网设置（存为 `ProxyInfo`）；App 内强制指定：

```kotlin
OkHttpClient.Builder()
    .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("192.168.1.100", 8888)))
    // SOCKS5：Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 1080))
    .build()
```

---

## 十二、长连接与推送：服务器怎么主动找你的 App

所有 App 都会遇到这个问题：**服务器有新消息时，怎么让 App 知道？** 五种方案代价完全不同。

| 方案 | 原理 | 延迟 | 耗电/流量 | 适用 |
|---|---|---|---|---|
| **轮询**（Polling） | App 定时问"有新消息吗" | 取决于间隔 | 高（空请求多） | 简单场景、低频 |
| **长轮询**（Long Polling） | 请求挂住等，服务器有数据才回应 | 低 | 中（连接反复重建） | 兼容性最好的"准推送" |
| **SSE**（Server-Sent Events） | 一条 HTTP 连接上服务器持续单向推流 | 低 | 低 | 行情、日志流、大模型流式输出 |
| **WebSocket** | 协议升级后成为全双工通道 | 低 | 低 | 聊天、协同编辑、游戏 |
| **系统推送通道** | 系统统一维护一条长连接 | 低 | **最低（多 App 共用）** | 进程被杀也能收 |

### 12.1 前三种：都在"HTTP 里想办法"

```http
# 长轮询：服务器不立刻回，挂住直到有数据或超时
GET /messages?since=12345 HTTP/1.1
→ （可能 30 秒后才返回）HTTP/1.1 200 OK  {"new": [...]}

# SSE：一次请求，持续接收文本事件流
GET /events HTTP/1.1
Accept: text/event-stream
← data: {"price": 101.2}\n\n
← data: {"price": 101.4}\n\n
```

SSE 的优点：**就是普通 HTTP**（代理/防火墙友好、自带重连语义）、单向足够用；缺点：不能双工、HTTP/1.1 下会占住一条连接。

### 12.2 WebSocket：借用 HTTP 的门，然后不走 HTTP 的路

```http
GET /chat HTTP/1.1
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
```

服务器回 `101 Switching Protocols` 后，**这条 TCP 连接脱离 HTTP 语义，变成全双工通道**，双方随时互发带长度前缀的帧（6.6 的第三种解法）。它继承了 HTTP 的便利（80/443 端口、穿透防火墙、可套 TLS），但记住两件事：

1. **没有内置心跳和重连**——NAT 超时（4.3）会让连接静默死亡，必须自己发心跳；
2. 「**Android 视角**」OkHttp 的 `WebSocket` 类提供 `pingInterval(n)` 自动心跳和失败回调，长连接必配。

### 12.3 系统推送通道：为什么 Android 必须有它

长连接的最大成本是**保活**：断了要重连、进程被杀要拉起，而 Android 为了省电会杀后台——如果每个 App 都自建长连接，电池直接被拖垮。于是有了"**系统统一一条连接**"的方案：App 把消息交给推送服务器，推送服务器通过系统共享的长连接唤醒对应 App。

**国内的现实**：厂商各有一套（小米、华为、OPPO、vivo、荣耀…），加上海外的 FCM。所以国内 App 常要集成多套推送 SDK 并按设备品牌路由——这也是 App 难以完全放弃自建长连接的原因。**"App 被杀还能收到消息"，靠的就是这条系统通道**，因此推送到达率与"是否被系统省电策略限制"强相关（对照《Android 存储/省电》相关章节）。

### 12.4 选型决策（直接抄）

```
需要双向实时 + 服务端主动推（聊天/协同）    → WebSocket
只需服务端单向推、想少写协议（行情/流式）    → SSE
进程被杀也要能收到（IM、订单状态）          → 系统推送通道（国内多厂商路由）
临时、低频（每天同步一次）                 → 轮询就够
兼容性优先、改不了服务端                   → 长轮询
```

---

## 十三、局域网发现：mDNS、SSDP 与投屏互联

前面十二章讲的全是"连到互联网上的服务器"。但有一大类场景完全不同：**同一局域网内的设备互相发现**——手机投屏到车机/电视、打印机发现、智能家居配网。它们不需要 DNS 服务器，也不需要公网 IP，用的是**局域网组播/广播**。

| 协议 | 全称 | 干什么 | 典型 |
|---|---|---|---|
| **mDNS** | Multicast DNS | 用组播（224.0.0.251:5353）在局域网内解析 `.local` 域名、列举服务 | Bonjour、AirPlay/AirPrint、Android 的 `NsdManager` |
| **DNS-SD** | DNS Service Discovery | 在 mDNS 之上描述"有哪些服务、在哪"（`_http._tcp` 这类服务类型） | 发现投屏接收端、打印机 |
| **SSDP** | Simple Service Discovery Protocol | 基于 UDP 的"我在这里"通知与搜索 | **DLNA/UPnP**、投屏、部分车机互联 |
| **DLNA** | Digital Living Network Alliance | 基于 UPnP 的媒体共享（发现 + 播放控制） | 手机推视频到车机/电视 |

**一次真实的交互长什么样**（手机投屏到车机）：

```
① 车机启动后周期性通过 SSDP/mDNS 广播："我是投屏接收端，服务类型 _airplay._tcp"
② 手机打开投屏 → 组播查询同类型服务 → 收到车机响应（含 IP、端口、设备名）
③ 手机直接连车机的 IP+端口建立控制通道（此时才进入普通 TCP/TLS 流程）
④ 之后媒体流用 RTSP/RTP 或自定义隧道传输
```

**排障要点**（局域网发现失败几乎都是这几条）：

- **组播被阻断**：路由器开了"AP 隔离/客户端隔离"，或防火墙挡了 5353/1900 端口——设备各自能上网，但互相发现不了；
- **不在同一网段**：手机连 Wi-Fi、车机走以太网/热点，跨网段组播默认不转发；
- **省电策略**：Android 后台限制会让组播接收不稳；
- **多网卡选错**：车机同时有 Wi-Fi 与以太网时，服务绑在了另一个网卡上（呼应第 15 章的 per-Network 概念）。

「**Android 视角**」标准 API 是 `NsdManager`（Network Service Discovery）：`discoverServices()` 发现、`registerService()` 注册，底层由 native 的 mDNSResponder 支持。

---

## 十四、CDN 与负载均衡：大规模网站的两块基石

### 14.1 CDN：为什么你请求的"北京服务器"可能在上海旁边

把内容复制到全球几百个边缘机房，让用户就近取。怎么做到"就近"？靠 **DNS 调度**（8.5 的 CNAME 伏笔回收）：

```
你请求 cdn.example.com/video.mp4
  → DNS 发现是 CNAME → example.cdnprovider.net
  → CDN 的权威 DNS 按你**本地 DNS 的 IP**（粗略代表你的位置）返回最近的边缘节点 IP
  → 你连上的其实是上海机房
```

CDN 特别适合**静态内容**（图片、视频、JS 包）；动态接口一般回源站。抓包时看到一串陌生 CNAME 域名，就是 CDN 调度的痕迹。

### 14.2 负载均衡：一台入口机怎么分给一百台后端

- **L4（传输层）**：只看 IP+端口分发（TCP 连接级），快，不懂内容；
- **L7（应用层）**：能看 HTTP 内容，按 URL/Cookie 分发，还能做 SSL 卸载（帮后端扛 TLS 计算）、灰度路由。

常见策略：轮询、加权、最少连接、一致性哈希（分布式缓存标配）。**502/504（10.3）就是负载均衡器在告诉你"我后面那台机器挂了/太慢了"**。

---

## 十五、Android 特有：多网络与网络切换

### 15.1 Network 对象：一台设备多个网络

传统 Linux 一个默认网卡；Android 因蜂窝 + Wi-Fi + 车机以太网并存，把每个可用网络抽象成 **`Network` 对象**，流量可指定走哪一个：

```kotlin
val cm = context.getSystemService(ConnectivityManager::class.java)
cm.registerNetworkCallback(
    NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .build(),
    object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { /* 可用 */ }
        override fun onLost(network: Network) { /* 掉了，切备份 */ }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            // NET_CAPABILITY_VALIDATED = NetworkMonitor 探测通过
        }
    })
```

**车机高频需求——强制某请求走指定网络**（如 TBOX 蜂窝）：

```kotlin
network.socketFactory.createSocket()      // 用指定 Network 建 Socket
network.openConnection(urlConnection)     // 指定 Network 的 URLConnection
cm.bindProcessToNetwork(network)          // 整个进程默认流量都走它
```

### 15.2 切换瞬间的现象合集

| 现象 | 链路上的原因 |
|---|---|
| 切网后第一批请求失败 | 旧 TCP 连接四元组失效（源 IP 变了），收到 RST；QUIC 的连接迁移（10.6）正为此而生 |
| `UnknownHostException` 突发 | 新网络 DNS 未就绪，旧缓存又已过期 |
| 请求全部 pending 超时 | App 没监听 `onLost`，仍试图走已死接口 |
| OkHttp 自动恢复 | `retryOnConnectionFailure(true)`（默认开）会在连接失败后用新路由重试 |

---

## 十六、调试与性能归因

诊断网络问题有两类工具：**看状态**（现在什么样）与**看过程**（慢在哪一段）。前者用命令，后者靠代码埋点。

### 16.1 命令速查（按层分组）

```bash
# ── 链路/IP 层 ──────────────────────────────
adb shell ip route / ip addr          # 路由表、网卡
adb shell arp -a                      # ARP 缓存（链路层通不通的证据）
adb shell ping 8.8.8.8                # 绕过 DNS 测纯连通性
adb shell ping api.example.com        # DNS + 连通性一起测
adb shell traceroute 8.8.8.8          # 逐跳路径

# ── Framework 状态 ────────────────────────
adb shell dumpsys connectivity        # 所有 Network、LinkProperties、验证状态
adb shell dumpsys wifi                # Wi-Fi 状态机当前状态
adb shell getprop net.dns1            # 当前 DNS

# ── TCP/端口 ──────────────────────────────
adb shell ss -tn                      # TCP 状态分布（CLOSE_WAIT 泄漏、TIME_WAIT 堆积）
adb shell ss -tnlp | grep 8080        # 谁占了端口

# ── TLS ──────────────────────────────
openssl s_client -connect api.example.com:443 -servername api.example.com
#   看证书链是否完整、Verify return code

# ── 抓包 ──────────────────────────────
adb shell tcpdump -i wlan0 -w /sdcard/cap.pcap     # Wireshark 分析
# HTTPS 在抓包里只有密文；要看明文 → Charles + 证书 + NSC（11.3）或 VPN 型抓包
```

### 16.2 性能归因：把"慢"拆成四段

诊断卡顿的第一原则：**先确定慢在哪一段，再谈优化**。OkHttp 的 EventListener 就干这个：

```kotlin
client.eventListenerFactory { call -> object : EventListener() {
    private var t = 0L
    override fun dnsStart(call: Call, domainName: String) { t = System.nanoTime() }
    override fun dnsEnd(call: Call, domainName: String, addrs: List<InetAddress>) { log("DNS ${ms(t)}") }
    override fun connectStart(call: Call, i: InetSocketAddress, p: Proxy) { t = System.nanoTime() }
    override fun secureConnectEnd(call: Call, handshake: Handshake?) { log("TLS ${ms(t)}") }
    override fun responseHeadersEnd(call: Call, response: Response) { log("TTFB ${ms(t)}") }
    override fun callEnd(call: Call) { log("总耗时 ${ms(t)}") }
}}
```

四个数字各自指向不同的问题：

| 阶段 | 耗时高说明什么 | 怎么办 |
|---|---|---|
| DNS | 解析慢或被劫持 | 换 DNS、DoT/DoH、本地缓存、多 IP 容灾 |
| TCP 连接 | 建连慢（跨地域、丢包、握手重传） | 连接复用（连接池）、就近接入（CDN/多机房）、QUIC |
| TLS | 每次完整握手的开销 | 会话恢复、连接池复用、TLS 1.3 |
| TTFB | 服务端处理慢 | 服务端优化/加缓存；客户端管不了 |

### 16.3 三个最容易被忽略的指标

- **带宽 ≠ 时延**：带宽是"每秒能运多少"，时延是"一个来回多久"。视频卡顿看带宽，接口慢看时延（RTT）。
- **抖动（jitter）**：时延的波动。语音/视频最怕它，TCP 重传会放大抖动。
- **丢包率**：TCP 对它极敏感（一个丢包触发重传/窗口减半），**弱网下 1% 丢包可能带来数倍时延增长**——这解释了"信号满格但就是慢"。

---

## 十七、常见问题排查表

**分诊顺序（拿到任何网络问题先做这三步）**：

1. `ping 域名`，不通再 `ping IP`——区分 **DNS 问题** vs **连通性问题**（第 4、8 章）；
2. `ss -tn` 看 TCP 状态——区分**没建连** vs **建连后挂死**（第 6 章）；
3. EventListener 分段计时——区分**客户端慢** vs **服务器慢**（16.2）。

| 现象 | 最可能的原因 | 章节 | 第一命令 |
|---|---|---|---|
| `UnknownHostException` | DNS 劫持/超时/缓存 NXDOMAIN | 8 | `ping IP` |
| `ConnectException: refused` | 端口没监听 / RST | 5、6 | `ss -tnlp` |
| `SocketTimeoutException: connect` | 握手无响应（防火墙丢包/路由不通） | 6.3 | 抓包看 SYN 是否重发 |
| `SocketTimeoutException: read` | 连上了但服务端不回 | 6.6 | EventListener + 服务端日志 |
| `Connection reset` | NAT 超时/中间设备 RST/服务端崩 | 4.3、6.4 | 加心跳 + 抓包看 RST 方向 |
| `SSLHandshakeException` | 证书链不全/自签未信任/时间错乱 | 9.5 | `openssl s_client` |
| `CleartextNotPermitted` | Android 9+ 默认禁明文 | 9.5 | NSC 配置 |
| `BindException: Cannot assign...` | 临时端口耗尽 | 5.3 | `ss -tn` |
| CLOSE_WAIT 堆积 | Response body 未关闭（连接泄漏） | 6.4 | `ss -tn` |
| TIME_WAIT 堆积 | 高频短连接 | 5.3、6.4 | 改连接复用 |
| 切网后全部请求挂起 | 未处理 `onLost` | 15.2 | `dumpsys connectivity` |
| 代理配了抓不到包 | 流量不走系统代理（裸 Socket/native） | 11.4 | 换 VPN 型抓包 |
| 局域网内发现不到设备 | 组播被隔离 / 跨网段 / 多网卡选错 | 13 | 检查 AP 隔离与网段 |
| 弱网"卡一下就好" | TCP 慢启动/重传退避 | 6.5 | EventListener 分段计时 |
| 进程被杀后收不到消息 | 未接入系统推送通道 | 12.3 | 检查推送 SDK 路由 |
| 502/504 | 反向代理后面的服务挂了/超时 | 10.3、14.2 | 服务端 + 网关日志 |

---

## 十八、读源码路线

按依赖顺序，从 App 层往下钻（每步的验证手段见第 16 章，边读边跑）：

1. **OkHttp 主链路**：`RealCall.execute()` → 拦截器链 `RetryAndFollowUpInterceptor → BridgeInterceptor → CacheInterceptor → ConnectInterceptor → CallServerInterceptor`。重点 `ConnectInterceptor`：TCP+TLS 建连、连接池取用全在这。
2. **DNS**：`Dns.SYSTEM` → `InetAddress.getAllByName` → libcore → netd 的 dnsproxyd 中转（Android 特有）。
3. **Framework 连接管理**：`ConnectivityManager` → AIDL `IConnectivityManager` → `ConnectivityService` → `NetworkAgentInfo`。对照车机双网场景读。
4. **Wi-Fi 状态机**：`ClientModeImpl` 的状态流转，对照 `dumpsys wifi` 输出读。
5. **内核视角（选读）**：`/proc/net/tcp`（`ss` 的数据来源）、路由表结构。

---

## 十九、一图总结

```
┌─────────────────────────────────────────────────────────────────┐
│                       你写的 Kotlin 代码                          │
│   Retrofit / OkHttp / WebSocket / SSE / 自定义 Socket / NsdManager│
└──────────────┬──────────────────────────────────────────────────┘
               │ ① DNS：域名→IP（先查号，递归+逐级缓存）            第 8 章
               │ ② TCP：SYN→SYN-ACK→ACK（编号+确认建可靠管道）      第 6 章
               │ ③ TLS：证书验证 + 密钥交换（管道变保险管）          第 9 章
               │ ④ HTTP：请求组装、缓存、多路复用（货物的包装约定）   第 10 章
┌──────────────▼──────────────────────────────────────────────────┐
│  传输层：端口把包分给具体进程（房间号）                            第 5 章
│  网络层：IP 路由逐跳转发，NAT 复用公网出口（地址+前台代收）          第 4 章
│  链路层：ARP 查网关 MAC，Wi-Fi 加密发出本跳                        第 3 章
└──────────────┬──────────────────────────────────────────────────┘
     横切主题（贯穿各层，不属于某一层）：
       代理（11）· 长连接与推送（12）· 局域网发现（13）· CDN/负载均衡（14）
     落地：Android 多网络与切换（15）· 调试与性能归因（16）

一句话记忆链：
  DNS 告诉你门牌号 → TCP 保证门牌号之间一条可靠管道 → TLS 把管道变成保险管
  → HTTP 定义管子里运的货 → 端口是进门的房间号 → IP 是门牌本身
  → Wi-Fi 是最后那段公路 → Socket 是你握住这一切的门把手
  → 代理是可插在任意位置的中转站 → CDN/负载均衡是目的地门口的分拣中心。
```

---

*关联阅读（同目录）：《Android 蓝牙机制详解》（短距无线与协议分层的另一视角）、《Android 存储机制详解》（系统服务与权限模型的相似套路）。*
