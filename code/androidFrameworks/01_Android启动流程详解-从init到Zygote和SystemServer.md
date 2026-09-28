# Android 启动流程详解：从 init 到 Zygote 和 SystemServer

> 本篇是 Framework 学习序列里"系统从哪里来"的一篇。前面的 AMS/PMS/WMS/ATMS 都运行在系统启动之后；这里要看 Android 如何从内核交接给 `init`，再由 `init` 拉起 `Zygote`，`Zygote` fork 出 `system_server`，最后 `SystemServer` 在 `startBootstrapServices / startCoreServices / startOtherServices` 三阶段里把各大系统服务装配起来。
>
> **分工边界（重要，避免重复）**：
> - `init` 的两阶段、init.rc 语法与属性服务细节，已在 `androidOthers/Android启动与属性系统详解-从init.rc到属性服务.md`（简称"init 篇"）详讲。本篇在 `kernel → init` 这一段只做**衔接**，不展开。
> - `Zygote` 的 `preload` 与 boot image 原理，已在 `androidOthers/Android虚拟机详解-从Dalvik到ART与代码形态演变.md` 第九节（简称"ART 篇"）详讲。本篇只引用其结论。
> - `SystemUI` 被拉起的那一段，与 `18_SystemUI机制详解` 第 5 节衔接。
> - 读完本篇你应该能回答三个问题：①用户态第一个进程是谁；②App 进程从哪来；③系统服务怎么就绪。

---

## 目录

- [1. 开场：启动流程回答的三个问题](#1-开场启动流程回答的三个问题)
- [2. kernel → init：衔接与 Zygote 的 rc 定义](#2-kernel--init衔接与-zygote-的-rc-定义)
- [3. app_process 与 AppRuntime：C++ 入口如何进入 Java 世界](#3-app_process-与-appruntimec-入口如何进入-java-世界)
- [4. ZygoteInit：预热、USAP 与 socket accept loop](#4-zygoteinit预热usap-与-socket-accept-loop)
- [5. fork 请求协议与 specialize 全程](#5-fork-请求协议与-specialize-全程)
- [6. system_server 的 fork 与 SystemServer.run](#6-system_server-的-fork-与-systemserverrun)
- [7. SystemServer 三阶段：bootstrap / core / other](#7-systemserver-三阶段bootstrap--core--other)
- [8. boot phase 与 SystemServiceManager 生命周期](#8-boot-phase-与-systemservicemanager-生命周期)
- [9. Watchdog：专杀卡死的核心服务](#9-watchdog专杀卡死的核心服务)
- [10. 启动完成的三种信号](#10-启动完成的三种信号)
- [11. 启动时间与优化视角](#11-启动时间与优化视角)
- [12. Zygote 多进程形态：32/64 双 zygote 与 WebView](#12-zygote-多进程形态3264-双-zygote-与-webview)
- [13. 车机 / 平台视角](#13-车机--平台视角)
- [14. 调试与观测](#14-调试与观测)
- [15. 常见问题排查表](#15-常见问题排查表)
- [16. 读源码路线（5 条主线）](#16-读源码路线5-条主线)
- [17. 源码路径速查 + 一图总结 + 关联阅读](#17-源码路径速查--一图总结--关联阅读)

---

## 1. 开场：启动流程回答的三个问题

启动流程不是一条"从下往上"的单调链，而是围绕三个核心疑问组织起来的。带着这三个问题读，后面所有章节都是它们的答案。

| 问题 | 答案位置 | 一句话 |
|---|---|---|
| **用户态第一个进程是谁？** | `init`（PID 1），由内核 `rest_init` 拉起 | init 是所有进程的祖先，但它自己不是 fork 出来的"普通进程" |
| **App 进程从哪来？** | 从 `Zygote` 进程 **fork** 出来 | 不是 `spawn` 新建，而是复制一份已热身好的 Java 运行时 |
| **系统服务怎么就绪？** | `system_server` 进程里 `SystemServer` 三阶段装配 | 一大堆 `SystemService` 子类通过 `ServiceManager.addService` 注册，客户端跨进程取 |

**全景时序（ASCII）**：

```text
Boot ROM           厂商固件，校验并加载 bootloader（只读，不可改）
  └─ bootloader    (aboot / uboot / bl2) 解锁、找 boot 分区、跳内核
       └─ Linux Kernel  (start_kernel → rest_init → 启动用户态 init)
            └─ init (PID 1)        解析 init.rc，挂载，起属性服务
                 ├─ ueventd / servicemanager / vold / hwservicemanager …
                 ├─ zygote / zygote64 (app_process)
                 │    ├─ 预加载类/资源/库（见 ART 篇第九节）
                 │    ├─ fork → system_server      ← 不 exec，父子共享运行时
                 │    └─ runSelectLoop 等 socket → fork App 进程
                 └─ （其他 native / hal 服务）

system_server
  ├─ startBootstrapServices  (Installer, AMS, ATMS, PMS…)
  ├─ startCoreServices        (Battery, UsageStats, BinderStats…)
  ├─ startOtherServices       (WMS, Input, Power, Notification, NMS…)
  ├─ PHASE_BOOT_COMPLETED → 发 ACTION_BOOT_COMPLETED 广播 + setprop sys.boot_completed=1
  └─ 启动 Home / SystemUI（见 18_SystemUI 第 5 节）

Launcher / SystemUI 进程  ← 也是从 zygote fork 出来的普通 App 进程
```

**怎么读**：建议先顺着 §16 的"源码路线"走一遍，再回来按 §2→§7 精读。遇到 init.rc / 属性 / preload 细节，按上面的分工边界跳到对应姊妹篇，本篇不重复。

「**Android 视角**」本篇涉及的"进程"在 Linux 意义上都是 `task_struct`，只是 system_server / zygote 因为承载 Framework 而地位特殊；它们的崩溃后果（重启谁）是车机/整机稳定性问题的核心，务必记住 §9 的 Watchdog 与 §15 的排查表。

---

## 2. kernel → init：衔接与 Zygote 的 rc 定义

### 2.1 cmdline 怎么传到 init

内核启动时，bootloader 把 `boot.img` 里的 `cmdline` 传给内核，`kernel_init` 把它存进 `/proc/cmdline`，`init` 通过 `android::init::GetCommandLine` 一类接口读取。和启动流程关系最密切的是 `androidboot.*` 前缀的属性：

```text
androidboot.mode=normal        # 启动模式（normal / recovery / charger）
androidboot.bootloader=xxx     # bootloader 版本，写进 ro.bootloader
androidboot.hardware=xxx       # 硬件代号，驱动/SEPolicy 选择依据
androidboot.serialno=xxx       # 序列号
androidboot.verifiedbootstate=green  # AVB 校验状态（green/orange/yellow）
androidboot.secureboot=1
```

`init` 会把 `androidboot.<name>` 翻译成 `ro.boot.<name>` 属性（只读），后续 Framework / vendor 代码都读属性而不是解析 cmdline。`ro.boot.*` 的解析与落地细节见 **init 篇 第六、七、九章**，本篇不展开。

### 2.2 init 衔接要点（只列你必知的几根线）

- `init` 分**两阶段**（first stage / second stage），第一阶段挂载 `/system`、`/vendor`、`/data` 并切换 SELinux 策略；第二阶段重新 `exec` 自己解析全部 `init.rc`。细节见 **init 篇 第三、四章**。
- `init` 启动顺序：先 `class core`（servicemanager、ueventd、vold…），再 `class main`（包含 zygote）。`class` 机制与 `class_start` 见 **init 篇 4.3 / 9.4**。
- `init` 通过 `on property:sys.boot_completed=1` 这类 `on property:` 触发器在启动收尾阶段做收尾动作（如关掉 `bootanim`）。见 **init 篇 9.2**。

### 2.3 Zygote 的 rc 定义逐字段解读

`init` 通过 `service zygote ...` 把 Zygote 定义为一个受管服务。以 64 位设备的 `system/core/rootdir/init.zygote64.rc` 为蓝本（不同版本字段略有增减，以你仓库实际为准）：

```rc
service zygote /system/bin/app_process64 -Xzygote /system/bin --zygote --start-system-server
    class main
    socket zygote stream 660 root system
    onrestart write /sys/android_power/request_state wake
    onrestart write /sys/power/state wake
    onrestart restart audioserver
    onrestart restart cameraserver
    onrestart restart media
    onrestart restart netd
    onrestart restart wificond
    writepid /dev/cpuset/foreground/tasks
```

逐字段解释：

| 字段 | 含义 |
|---|---|
| `service zygote` | 定义一个名为 `zygote` 的服务；状态可在 `init.svc.zygote` 查看（见 init 篇 9.1） |
| `/system/bin/app_process64` | 可执行文件，C++ 入口在 `frameworks/base/cmds/app_process/app_main.cpp`（见 §3） |
| `-Xzygote /system/bin` | `-Xzygote` 是 ART 的标记参数，告诉运行时"我是 zygote，保留一份用于 fork 的快照"；`/system/bin` 是 `nice-name` 外的备用路径参数 |
| `--zygote` | 让 `app_process` 走 `ZygoteInit` 而非普通 `RuntimeInit` 入口 |
| `--start-system-server` | 关键开关：`ZygoteInit` 起来后会 **fork 出 system_server**（见 §6） |
| `class main` | 归入 `main` 类，在 early boot 之后由 `class_start main` 统一启动（早于 `late_start`，晚于 `core`）。这就是为什么 zygote 比大部分 vendor 服务早 |
| `socket zygote stream 660 root system` | 在 `/dev/socket/zygote` 建一个 **流式（stream）** unix domain socket，权限 `660`，属主 `root`、属组 `system`。**这是 AMS 向 zygote 请求 fork App 的通道**（见 §5） |
| `onrestart write .../request_state wake` | zygote 重启前，先写 `wake` 防止设备休眠，保证重启能完成 |
| `onrestart write /sys/power/state wake` | 同上，写 `/sys/power/state` 唤醒 |
| `onrestart restart audioserver/cameraserver/media/netd/wificond` | **这些 native 服务持有连向 zygote/servicemanager 的 binder 或依赖 zygote 的进程关系，zygote 一死它们必须跟着重启**；否则会陷入"半死"状态 |
| `writepid /dev/cpuset/foreground/tasks` | zygote 启动时把自己的 pid 写进 foreground cpuset，保证它跑在大核/前台调度组 |

> 版本差异：`usap_pool_primary` socket（见 §4 USAP）在 **Android 10+** 的 rc 里出现；32/64 双 zygote 的 `zygote_secondary` 见 §12；`--enable-lazy-preload` 用于 secondary zygote 延迟预热。

---

## 3. app_process 与 AppRuntime：C++ 入口如何进入 Java 世界

### 3.1 调用链：从 `main` 到 `ZygoteInit.main`

```text
app_main.cpp::main(argc, argv)
  ├─ 解析 argv：--zygote / --start-system-server / -Xzygote / nice-name
  ├─ 决定启动类：有 --zygote → "com.android.internal.os.ZygoteInit"
  │             否则        → "com.android.internal.os.RuntimeInit"
  ├─ AppRuntime runtime;  runtime.start(className, args, /*zygote=*/true)
  │     │  AppRuntime 继承 AndroidRuntime
  │     └─ AndroidRuntime::start("com.android.internal.os.ZygoteInit", ...)
  │            ├─ 启动 ART VM：JNI_CreateJavaVM（加载 boot image，见 ART 篇 9.2）
  │            ├─ startReg(env)：注册 Android 自己的 JNI 函数表
  │            └─ 反射调用 ZygoteInit.main()  ← 到此进入 Java 世界
  └─ (ZygoteInit 内部见 §4)
```

`AndroidRuntime::start` 是"C++ → Java"的总闸门。它先做三件事：①创建 VM；②`startReg` 注册一批 native 方法（如 `android.util.Log`、`android.os.Process` 等的基础 JNI）；③用 `env->CallStaticVoidMethod` 反射调到 `ZygoteInit.main`。

### 3.2 JNI 注册：`startReg` 一类

`startReg(env)` 内部会调用一系列 `register_android_*` 函数，把 C++ 实现挂到对应 Java native 方法上。签名不必死记，记住结构即可：

```cpp
// 形如：
static const RegJNIRec gRegJNI[] = {
    REG_JNI(register_android_util_Log),
    REG_JNI(register_android_os_Process),
    REG_JNI(register_android_os_Binder),   // ← Binder 的 native 侧在这里注册
    ...
};
// AndroidRuntime::startReg 遍历这张表逐个注册
```

`register_android_os_Binder` 这类函数把 `android.os.Binder` / `Parcel` 的 native 实现接进来，所以**即便在 zygote 阶段，Binder 的 native 能力就已经就绪**——system_server 起来后第一条 `addService` 才能走通（见 §7）。

### 3.3 为什么用 fork 而不是 spawn

这是整套启动设计的命门。对比：

| | spawn（新建进程） | fork（复制 zygote） |
|---|---|---|
| 运行时状态 | 每次从零建 VM、加载类 | 直接继承 zygote 已预热的 VM + 类 + 资源 |
| 内存 | 各自独立，全量占用 | **COW 共享 boot image 与预加载类**（见 ART 篇 9.3） |
| 启动耗时 | 几百 ms 级 | 几十 ms 级 |
| 地址布局一致性 | 每次不同 | 父子一致，便于 debuggerd / 崩溃符号化统一 |

代价是 fork 后必须**降权与隔离**（见 §5 的 specialize）：App 不能带着 zygote 的 root 身份和完整能力跑。`fork + specialize` 的本质就是"先共享、再分化"。

「**Android 视角**」`fork` 在多线程进程里有雷区——zygote 早已是多线程，fork 只复制调用线程，其他线程"消失"，所以 fork 之后到 `exec`/specialize 之前**严禁调用可能用其他线程的 libc 函数**（如 `malloc` 在某些实现、锁）。`Zygote.forkAndSpecialize` 内部用 `fork()` 后立即走 specialize 的极简路径来规避。

---

## 4. ZygoteInit：预热、USAP 与 socket accept loop

### 4.1 preload 阶段（只引结论，细节在 ART 篇）

`ZygoteInit.main` 在做 socket loop 之前先预热：

```java
ZygoteInit.main(String[] argv) {
    // 1) 建 ZygoteServer，登记 zygote socket
    ZygoteServer zygoteServer = new ZygoteServer();
    // 2) 预热（耗时大头，但一次付出、所有 App 共享）
    if (!enableLazyPreload) {
        preload(bootTimingsTraceLog);   // 见下方
    }
    // 3) 如有 --start-system-server，fork system_server（见 §6）
    if (startSystemServer) forkSystemServer(...);
    // 4) 进入 accept loop（见 §4.3）
    zygoteServer.runSelectLoop(abiList);
}
```

`preload()` 做三件事（**完整原理、类列表来源、boot image 共享机制见 ART 篇第九节 9.5 / 9.2 / 9.3**）：

1. **预加载类**：`preloaded-classes` 列表（由 AOSP 基于 profile 生成），一次性塞进 VM；
2. **预加载资源**：`Resources` 里常用 drawable/color，避免每个 App 首次取资源时 IO；
3. **预加载共享 native 库**：`System.loadLibrary` 常用 so（如 `android`、`compiler_rt`），让它们进共享页。

> 取舍：preload 越多，单次 zygote 启动越慢，但每个 App 启动越快、内存越省。车机首帧优化的关键平衡点就在这一行（见 §11 与 ART 篇 6.4 / 第十七节）。

### 4.2 USAP：非专门化 App 进程池

**USAP = Unspecialized App Process（未专门化 App 进程）**。**Android 10 (Q) 引入**。

背景：`runSelectLoop` 里每次收到 fork 请求才现 fork，App 启动密集时（开机、多任务）zygote 的 select loop 成为瓶颈。USAP 思路：提前 fork 一批**还没 specialize（没降权、没设 uid、没挂 application classpath）**的"毛坯"进程放在池里，AMS 要进程时直接从这个池里挑一个做 specialize，省掉 fork 本身的抖动。

```rc
# Android 10+ 的 rc 里多了这行（命名规则见 §2.3）
socket usap_pool_primary stream 660 root system
```

语义边界：

- **USAP 进程 ≠ 真正 App 进程**：它和 zygote 一样共享 boot image，但还带着 zygote 的 root 身份，直到被 `specializeAppProcess` 接管；
- 开关：`usap` 可以通过属性（如 `framework` 的 `usap` 配置）整体关掉回退到"按需 fork"；
- 与 `ActivityManager` 的配合：AMS 维护 USAP 池水位，不够时让 zygote 补充。

「**Android 视角**」USAP 是"用常驻换延迟"的典型手段，和 SurfaceFlinger 的缓冲、binder 线程池思路一致；车机冷启动首屏大量 App 预加载时收益明显。

### 4.3 Zygote socket 的 accept loop

`ZygoteServer.runSelectLoop` 用 `poll` 同时监听 `zygote` socket 与 `usap_pool` socket：

```java
// 简化结构
void runSelectLoop(...) {
    while (true) {
        // poll 所有 socket fd（zygote 命令通道 + USAP 池）
        int index = poll(...);
        if (index == 0) {
            // 新连接进来：accept，建 ZygoteConnection
            ZygoteConnection newPeer = accept(...);
        } else {
            // 已有连接发来 fork 请求：读取参数包 → forkAndSpecialize（见 §5）
            ZygoteConnection connection = peers.get(index);
            connection.processCommand(this, /*multiple=*/true);
        }
    }
}
```

每个连接是一个 `ZygoteConnection`，负责按 §5 的协议解析参数、执行 fork。注意 loop 是**单线程**的：一次只处理一个 fork 请求，所以 zygote 一旦在 specialize 里卡住，所有 App 启动都会排队——这也是 §9 Watchdog 之外另一个"启动卡死"的高发点。

---

## 5. fork 请求协议与 specialize 全程

### 5.1 socket 参数包

AMS 要起一个 App 时，通过 `Process.start` → `zygoteSendArgsAndGetResult` 把下面这些字段按行写进 socket。zygote 侧 `ZygoteConnection` 用 `ZygoteArguments` 解析（字段名随版本微调，结构稳定）：

```text
--runtime-args
--setuid=<uid>            # 目标 App 的 uid（非 root）
--setgid=<gid>            # 主 gid
--setgroups=<gids>        #  supplementary gids（如 camera / internet 组）
--target-sdk-version=<N>
--nice-name=<进程名>       # 比如 com.example.app，ps 里看到的名字
--rlimit=<nofile>,<cur>,<max>   # 资源上限（如打开文件数）
--capabilities=<effective>,<permitted>  # 给进程的 Linux capabilities 位图
--capabilities=<...>,<...>
--mount-external=<0|1|2>  # 外部存储挂载方式（sdcardfs / fuse）
--invoke-with=<wrapper>   # 调试用 wrapper（如 strace），一般空
--package-name=<pkg>
--seinfo=<seinfo>         # SELinux 上下文选择依据
--nice-name=<...>
-- appDataDir=<dir>
--  <class>  <method>     # 入口：通常是 android.app.ActivityThread
```

`--` 之后的是真正的 Java 入口参数（`className` + `args`）。`ZygoteArguments` 解析后进入 `forkAndSpecialize`。

### 5.2 specialize 全程：共享之后如何分化

`Zygote.forkAndSpecialize` 内部（`native` 实现在 `frameworks/base/core/jni/com_android_internal_os_Zygote.cpp`）做三件大事：

```text
fork()                                  ← 复制出子进程（父子此时还一模一样）
  ├─ [父] 返回 childPid，继续 runSelectLoop
  └─ [子] pid == 0 分支：
       1) 降权：setuid/setgid/setgroups 到 App 的 uid/gid
       2) SELinux 过渡：selinux_android_setcontext(uid, isSystemServer=false,
                        seinfo, pkgname) → 切换 domain（如 u:r:untrusted_app:s0）
       3) Seccomp 过滤：applySeccompFilter() 挂 syscall 过滤器（限制能调的系统调用）
       4) 关闭 zygote 那一侧的 socket / 清掉 USAP 残留
       5) nice-name、rlimit、capabilities 落到位
       6) 调用 ZygoteInit.childZygoteInit → RuntimeInit.applicationInit
            → 反射 findStaticMain → ActivityThread.main()
```

三个接入点逐条说明：

- **降权**：fork 出来时子进程还是 root（继承了 zygote），必须立刻 `setuid(uid)` 落到 App 的 uid。这一步早做，避免任何代码以 root 跑。
- **SELinux 过渡**：`selinux_android_setcontext` 把进程 domain 从 `u:r:zygote:s0` 换成 App 的 domain（普通 App 是 `untrusted_app`，系统 App 是 `platform_app`/`system_app`）。**这一步失败或 seinfo 错误，进程起不来且 log 里是 avc denied**——见 §15。
- **Seccomp 过滤**：给非特权 App 挂 `seccomp` 过滤（基于 `/sepolicy` 与 `libseccomp`），禁掉 `execve`、`ptrace` 等危险 syscall。这是 ANDROID 8+ 的硬性隔离要求。

### 5.3 pid==0 分支：从子进程到 `ActivityThread.main`

子进程在 `pid == 0` 分支里完成 specialize 后，走 `RuntimeInit` 的通用入口：

```java
// RuntimeInit.applicationInit
→ findStaticMain(className, argv, classLoader)   // 反射拿 ActivityThread.main
→ 通过 MethodAndArgsCaller 抛到 Looper 顶层执行
→ ActivityThread.main()                          // App 主线程入口，建 Looper、Attach Binder
```

`ActivityThread.main` 之后才是大家熟悉的 `attach → bindApplication → onCreate`。也就是说：**App 的 `Application.onCreate` 之前，已经完成了 fork + 降权 + SELinux + Seccomp + 反射四步**。`ActivityThread` 的后续是 AMS/PMS 篇的内容，本篇不展开。

---

## 6. system_server 的 fork 与 SystemServer.run

### 6.1 与 App fork 的关键差异：system_server fork 后**不 exec**

`ZygoteInit.forkSystemServer` 走一条**特权路径**，不是 §5 的 App specialize 路径：

```text
ZygoteInit.main（带 --start-system-server）
  └─ forkSystemServer(...)
       ├─ Zygote.forkSystemServer(uid=1000, gid=1000, gids, ...)
       │    └─ native fork()（与 App 一样是复制，但保留 capabilities）
       ├─ [父] 返回 pid，继续 runSelectLoop
       └─ [子] pid == 0 分支：handleSystemServerProcess(parsedArgs)
              ├─ 关掉 zygote 的 socket（不再接受 fork 请求）
              ├─ 设进程名 "system_server"
              ├─ SELinux 过渡到 u:r:system_server:s0（不是 untrusted_app）
              ├─ 保留 system uid 与所需 capabilities（不像 App 那样裸降到普通 uid）
              ├─ 挂 system_server 自己的 seccomp / 不做 App 级限制
              └─ ZygoteInit.zygoteInit → RuntimeInit.applicationInit
                   → findStaticMain("com.android.server.SystemServer", "main")
                   → SystemServer.main() → SystemServer.run()   ← 见 §7
```

要点：**system_server 是 zygote 的"特殊子进程"，fork 出来后既不 exec 新程序，也不按 App 那样降权扣 capability**，所以它天然带着 Framework 需要的系统权限（读所有 App 数据、发全局广播等）。这也是为什么 system_server 崩溃比单个 App 崩溃严重得多。

### 6.2 `SystemServer.run` 的总入口骨架

```java
public static void main(String[] args) {
    new SystemServer().run();
}

void run() {
    // 1) 准备 Looper / 系统上下文（createSystemContext）
    // 2) 初始化 native 服务（SurfaceFlinger 句柄、Display 早期）
    // 3) 三阶段启动（核心都在这里）
    startBootstrapServices(t);
    startCoreServices(t);
    startOtherServices(t);
    // 4) 收尾：phase 推进到 BOOT_COMPLETED（见 §8、§10）
    mSystemServiceManager.startBootPhase(SystemService.PHASE_BOOT_COMPLETED);
}
```

`SystemServer` 自己不直接 `new` 服务，而是统一经 `SystemServiceManager` 来管理生命周期（见 §8）。记住这条线：**zygote fork → SystemServer.run → 三阶段 → addService 注册 → phase 推进**。

---

## 7. SystemServer 三阶段：bootstrap / core / other

`SystemServer` 把服务拆成三段，不是随意分组，而是**按依赖深度排序**：越底层、被别人依赖越多的越先起。

### 7.1 `startBootstrapServices`：被依赖的"地基"

| 顺序（近似） | 服务 | 为什么这个位置 |
|---|---|---|
| 1 | `PlatformCompat` | 后续服务可能查 compat 设置，必须先就位 |
| 2 | `Installer` | 连接 `installd`（native），PMS/其他需要落盘安装的操作全靠它 |
| 3 | `DeviceIdentifiersPolicyService` | 设备标识策略，早定早查 |
| 4 | `UriGrantsManagerService` | URI 授权，AMS/PMS 起组件常需 |
| 5 | `ActivityTaskManagerService` (ATMS) | 窗口/任务栈管理，AMS 依赖它 |
| 6 | `ActivityManagerService` (AMS) | Framework 中枢；`setSystemProcess` 把自己注册进 ServiceManager，并 add 一批 well-known 服务 |
| 7 | `PowerManagerService` | 极其多服务依赖"是否亮屏/唤醒锁"，必须早 |
| 8 | `ThermalManagerService` | 温控，影响性能策略 |
| 9 | `PackageManagerService` (PMS) | **重量级**：扫描 `/system`、`/vendor`、`/data/app`，建包索引；必须早于任何"解析组件"的动作 |
| 10 | `DisplayManagerService` | 显示是 WMS/ATMS 的前提；启动后进入 `PHASE_WAIT_FOR_DEFAULT_DISPLAY` |

> 依赖链精髓：**PMS 必须先把"有哪些包/组件"建好，AMS/ATMS 才能解析并启动组件；ATMS/Display 必须先有，WMS 才能挂窗口；PowerManager 几乎被所有人依赖，所以排得很前。**

### 7.2 `startCoreServices`：系统健康与统计

| 服务 | 作用 |
|---|---|
| `BatteryService` | 电池/充电状态，PowerManager、很多省电逻辑依赖 |
| `UsageStatsService` | 应用使用时长统计，电池优化/数字健康用 |
| `WebViewUpdateService` | 管理 WebView 实现版本（见 §12） |
| `BinderStatsService` / `LooperStatsService` | 性能/调用统计，可观测性 |
| `RollbackManagerService`（部分版本） | 模块回滚 |

### 7.3 `startOtherServices`：功能大杂烩（挑 15 个讲清顺序逻辑）

这一段最长，挑有依赖代表性的讲：

| 顺序（近似） | 服务 | 为什么这个位置 |
|---|---|---|
| 1 | `InputManagerService` | 输入设备，WMS 要把输入事件路由给窗口，故早于 WMS |
| 2 | `WindowManagerService` (WMS) | 依赖 ATMS/Display/Input，是窗口系统核心（详见 `11_WMS机制详解`） |
| 3 | `VrManagerService` | VR 模式会覆盖显示/输入策略 |
| 4 | `NotificationManagerService` (NMS) | 通知要弹窗，依赖 WMS 的窗口能力 |
| 5 | `DeviceStorageMonitorService` | 存储空间告警 |
| 6 | `LocationManagerService` | 定位 |
| 7 | `CountryDetectorService` | 国家/区域检测 |
| 8 | `SearchManagerService` | 全局搜索 |
| 9 | `WallpaperManagerService` | 壁纸（WMS 窗口背景） |
| 10 | `AudioService` | 音频策略，与广播/通知音相关 |
| 11 | `DockObserver` / `Thermals` | 外设/温控 |
| 12 | `MediaRouterService` | 媒体路由 |
| 13 | `MmsService` | 彩信 |
| 14 | `PowerUI` / `StorageNotification` | 系统 UI 提示（非 SystemUI 主体） |
| 15 | `NetworkManagement` / `Connectivity` 相关 | 网络，很多服务监听连接状态 |

WMS 起完后，`SystemServer` 会回调 `AMS.systemReady()`，由 AMS 启动 `Launcher` 与 `SystemUI`（SystemUI 的拉起细节见 `18_SystemUI机制详解` 第 5 节）。

### 7.4 `ServiceManager.addService` 时序与"服务不可用窗口"

每个服务在自己的 `onStart()` 里一般做两件事：①真正 `new` 出服务对象；②通过 `ServiceManager.addService(name, binder)` 把 Binder 暴露出去。AMS 的 `setSystemProcess` 一次性 add 了一批：`activity`、`procstats`、`meminfo`、`gfxinfo`、`dbinfo`、`permission`、`cpuinfo`、`usagestats` 等。

```java
// 典型写法（服务侧）
public void onStart() {
    publishBinderService(SERVICE_NAME, mService); // 内部 = ServiceManager.addService
}
```

**"服务不可用窗口"**：从 `system_server` fork 完成、到某个服务 `addService` 成功之间，客户端调用 `ServiceManager.getService(name)` 会得到 `null`。窗口通常几十~几百 ms，但有两个后果要注意：

1. **客户端要等**：系统内部用 `ServiceManager.getService(name)` 拿到的可能是 `null`，所以跨服务调用常写成"在 `onBootPhase` 里再取"（见 §8），而不是在构造函数里直接抓；
2. **shell/测试**：`service check <name>` 在窗口期内会返回 `not found`，别误判服务没起。

---

## 8. boot phase 与 SystemServiceManager 生命周期

### 8.1 `SystemService` 生命周期

所有系统服务继承 `SystemService`，生命周期由两个钩子驱动：

```java
public abstract class SystemService {
    public void onStart() { }            // 启动并 addService（同名于 startXxxServices 阶段触发）
    public void onBootPhase(int phase) { } // 每推进一个 phase 被回调一次
}
```

`SystemServiceManager`（SSM）统一管理：
- `startService(cls)` → 反射构造 → 调 `onStart()`；
- `startBootPhase(phase)` → 按已注册顺序，对**每个**服务调 `onBootPhase(phase)`。

### 8.2 `PHASE_*` 各阶段的语义

数值在 `SystemService` 里定义，跨版本稳定：

| 常量 | 值 | 语义 | 谁在这些点做什么 |
|---|---|---|---|
| `PHASE_WAIT_FOR_DEFAULT_DISPLAY` | 100 | 默认 Display 就绪 | DisplayManager 之后才可建窗口相关服务 |
| `PHASE_LOCK_SETTINGS_READY` | 480 | 锁屏/加密设置可读 | 需要用户凭据相关的服务（如某些凭据存储） |
| `PHASE_SYSTEM_SERVICES_READY` | 500 | 核心服务互相可用 | 跨服务依赖在此刻安全取对方 Binder |
| `PHASE_DEVICE_SPECIFIC_READY` | 520 | 设备/厂商定制就绪 | OEM 服务可启动设备特有逻辑 |
| `PHASE_ACTIVITY_MANAGER_READY` | 550 | AMS 已 ready | 可以启动用户态组件、广播开始流动 |
| `PHASE_THIRD_PARTY_APPS_CAN_START` | 600 | 三方 App 可启动 | 限制后台启动的策略放开 |
| `PHASE_BOOT_COMPLETED` | 1000 | 开机完成 | 做"开机后才该做"的初始化（见 §10） |

### 8.3 依赖等待的惯例

**黄金规则：跨服务引用不要在 `onStart()` 里直接拿，要在 `onBootPhase(PHASE_SYSTEM_SERVICES_READY)` 里拿。**

原因：服务 A 的 `onStart()` 可能跑在依赖 B 的 `onStart()` 之前，此时 `getService(B)` 是 `null`。把"要对方"的逻辑推迟到 `PHASE_SYSTEM_SERVICES_READY`（保证 B 已 `addService`），就能稳定拿到。这是读 Framework 源码时判断"这两行为什么不在构造函数里"的钥匙。

「**Android 视角**」车机/OEM 加自定义服务时，务必让你的 `onBootPhase` 选择正确的相位，别贪早——在 `PHASE_SYSTEM_SERVICES_READY` 之前依赖别的系统服务，极容易拿到 `null` 导致启动中断（见 §15）。

---

## 9. Watchdog：专杀卡死的核心服务

`Watchdog` 是 `system_server` 进程内的一条监控线程，专门防止"核心服务卡死把整机拖死"。

### 9.1 它监控谁

- **核心服务的 binder 线程**：`AMS`、`WMS`、`PowerManagerService` 等向 Watchdog 注册 `monitor`（一个实现 `Watchdog.Monitor` 的对象）。Watchdog 线程会调用 `monitor()`，而 `monitor()` 内部通常只是 `synchronized (this) {}`——如果那个服务的锁被别的线程长期持有（死锁），`monitor()` 就卡住。
- **system_server 主线程与关键 Looper**：通过 `HandlerChecker` 对 `FgThread`/`UiThread`/`IoThread`/主 Looper 发消息，看是否在超时内被消费。主线程卡（ANR 式死循环）也会被它抓到。

### 9.2 怎么判卡死

```text
Watchdog 线程每 ~30s（DEFAULT_TIMEOUT）跑一轮：
  for 每个 HandlerChecker / Monitor：
      post 一个检查任务到对应线程的 Looper / 调 monitor()
      等 timeout（默认 30s，部分 checker 更长）
  if 所有 checker 在一轮内都完成 → 健康
  else → 计一次失败；连续失败超过阈值（通常 2~3 轮）→
      dump 堆栈（native + Java）到 /data/anr/ 与 logcat
      → 触发重启
```

判据本质：**"这个线程在 timeout 内还能不能响应我最简单的请求"**。死锁、主线程同步 IO、无限循环都会让它超时。

### 9.3 杀了谁、怎么重启

- 优先路径：`Watchdog` 调 `PowerManagerService.reboot("Watchdog")` 走正常重启；
- fallback：若 PowerManager 自己就是卡死元凶，直接 `Process.killProcess(Process.myPid())` + `System.exit(10)`；
- 后果：**system_server 进程死亡 → 整机重启**（不是只重启 zygote）。重启计数 +1，如果短时间内反复（bootloop），会进入 recovery / 要求恢复出厂（见 §15）。

「**Android 视角**」车机遇到"莫名重启、log 里有 `Watchdog: \*\*\* WATCHDOG KILLING SYSTEM PROCESS ***`"——先去 `/data/anr/traces_*` 看是哪个 `monitor` 卡住，再顺藤摸瓜找那个服务的锁竞争，而不是去怀疑 Watchdog 本身。

---

## 10. 启动完成的三种信号

"开机完成"在系统里有**三种不同形态的信号**，受众和时间点都不同，常被混淆：

| 信号 | 形态 | 触发点 | 受众 | 用途 |
|---|---|---|---|---|
| `PHASE_BOOT_COMPLETED` | 进程内回调（int=1000） | `SystemServer.run` 末尾 `startBootPhase(1000)` | 其他 `SystemService` 的 `onBootPhase` | 系统服务做"开机后才该做"的事 |
| `ACTION_BOOT_COMPLETED` | 有序广播（sticky） | `AMS.finishBooting` 在 PHASE 之后发出 | 声明 `RECEIVE_BOOT_COMPLETED` 的 App | App 开机自启（见 §13） |
| `sys.boot_completed=1` | 系统属性 | `AMS` 发完广播后 `SystemProperties.set` | init 脚本 / native / shell | `init` 用 `on property:` 停 bootanim |

**先后关系（重要）**：

```text
SystemServer.run
  └─ startBootPhase(PHASE_BOOT_COMPLETED)       ① 系统服务内部先收到
       └─ AMS.finishBooting()
            ├─ sendBroadcast(ACTION_BOOT_COMPLETED)   ② App 收到广播
            └─ SystemProperties.set("sys.boot_completed", "1")  ③ 属性置位
                 └─ init: on property:sys.boot_completed=1  → stop bootanim  ④ 开机动画停止
```

- `PHASE_BOOT_COMPLETED`（①）**早于**广播（②），系统服务内部钩子先于 App；
- `sys.boot_completed`（③）与广播几乎同时，但它是给 `init`/native 看的，不是给 App 的；
- `bootanim` 停止（④）依赖属性，所以**开机动画消失 ≈ sys.boot_completed 置位时刻**，未必等于 Launcher 首帧。

延伸阅读：广播细节见 `08_Broadcast机制详解`；`on property:` 触发器与 `init.svc.*` 见 **init 篇 9.2 / 9.1**。

---

## 11. 启动时间与优化视角

### 11.1 测量：bootchart

AOSP 自带 bootchart 支持（由 init 启用）：

```bash
adb shell touch /data/bootchart/enabled   # 开采样
adb reboot
adb pull /data/bootchart ./bootchart       # 重启后取出
# 用 system/core/init/ 里的工具或 bootchart 渲染成图
```

它给出**从 init 启动到各服务起来的 CPU/进程时间线**，适合定位"某服务吃满了 CPU 导致整机慢"。

### 11.2 测量：logcat 的 Boot Event 计时

`logcat -b events` 里有 `boot_progress_*` 事件，是量化各阶段耗时的官方标尺：

```bash
adb logcat -b events -d | grep -i boot
# 典型输出（时间戳=开机后 ms）：
# boot_progress_start (init 接手)
# boot_progress_preload_start / boot_progress_preload_end   ← Zygote preload 区间
# boot_progress_system_run   (SystemServer 开始)
# boot_progress_pms_ready    (PMS 扫描完成)
# boot_progress_ams_ready    (AMS ready)
# boot_progress_enable_screen (可以画第一屏)
```

配合 `am_proc_start` 看"同一时刻起了多少进程"（密度高 = 启动拥堵）：

```bash
adb logcat -b events -d | grep am_proc_start | wc -l
dmesg | grep -i init        # 内核侧 init 时间戳，看 kernel→user 的交接
```

### 11.3 Zygote preload 对启动的取舍

preload 是"一次付出、所有 App 共享"的权衡（完整取舍见 **ART 篇 6.4 / 第十七节**）。结论：preload 列越长 → zygote 启动越慢（拖累"首屏前"那段），但 App 启动越快、内存越省。优化方向：

- 精简 `preloaded-classes`（删掉车机根本用不到的类）；
- 用 `speed-profile`/Baseline Profile 替代全量 preload；
- 首次开机慢主要是 **dexopt**（见 ART 篇第十七节），与 preload 是两码事，别混。

### 11.4 车机开机时间的构成

把"按下电源到看到车机桌面"拆开，便于定位瓶颈：

```text
[ 0 ] 供电 / bootloader（厂商固件，常不在 AOSP 可控范围）
[ 1 ] Kernel 启动        bootloader → start_kernel → init（看 dmesg 时间戳）
[ 2 ] User space ready   init → zygote preload → system_server 三阶段
[ 3 ] AMS ready          PHASE_ACTIVITY_MANAGER_READY（组件可启动）
[ 4 ] 首帧               boot_progress_enable_screen → SurfaceFlinger 首帧 → Launcher 绘制
```

- 卡在 [2]：看 `boot_progress_preload_*` 与 `pms_ready` 间距，通常是 preload 或 PMS 扫描；
- 卡在 [4]：看 `sys.boot_completed` 已置但画面没出，是 SurfaceFlinger / Launcher / 资源加载问题；
- 车机常额外受 **HAL 初始化**（仪表、CAN、音效）拖慢 [1]~[2] 衔接，这部分在 vendor 镜像，不在本篇范围。

---

## 12. Zygote 多进程形态：32/64 双 zygote 与 WebView

### 12.1 为什么有 32/64 双 zygote

现代设备是 64 位内核，但存在**只能用 32 位 ABI 的 App**（老包、部分 native 库只编了 32 位）。于是有两条 zygote：

| rc 服务 | 可执行 | 角色 | socket-name |
|---|---|---|---|
| `zygote` | `app_process64` | 主 zygote，孵 64 位 App 与 system_server | `zygote` |
| `zygote_secondary` | `app_process32` | 次 zygote，孵 32 位 App | `zygote_secondary` |

对应 rc（64+32 设备，`init.zygote64_32.rc` 节选）：

```rc
service zygote /system/bin/app_process64 -Xzygote /system/bin --zygote \
    --start-system-server --socket-name=zygote
    class main
    socket zygote stream 660 root system
    socket usap_pool_primary stream 660 root system

service zygote_secondary /system/bin/app_process32 -Xzygote /system/bin --zygote \
    --socket-name=zygote_secondary --enable-lazy-preload
    class main
    socket zygote_secondary stream 660 root system
    socket usap_pool_secondary stream 660 root system
```

注意 `zygote_secondary` 不带 `--start-system-server`（只有主 zygote 孵 system_server），且 `--enable-lazy-preload`——次 zygote 延迟预热，省内存（32 位 App 通常少）。

### 12.2 AMS 怎么选哪个 zygote

`ActivityManagerService` 在 `startProcessLocked` 时根据目标 App 的 `abi` 决定走哪个 socket：

```text
App 是 64 位 ABI（arm64-v8a）→ 连 /dev/socket/zygote        → 主 zygote fork
App 是 32 位 ABI（armeabi-v7a）→ 连 /dev/socket/zygote_secondary → 次 zygote fork
```

`Process` 类把 socket 名透传给 `openZygoteSocketIfNeeded`，拿不到对应 socket 就起不了进程。**纯 64 位设备（无 32 位支持）只有主 zygote，32 位 App 直接装不了/起不来**——这是"换机型后老 App 启动报错"的常见根因（见 §15 第 14 行）。

### 12.3 WebView zygote（一句带过）

**Android 7 (N) 引入独立的 `webview_zygote`**（一般基于 `app_process32`），把 WebView 的庞大 native 库（`libwebviewchromium`）隔离进专属进程池。App 的 WebView 渲染进程从 `webview_zygote` fork，而非主 zygote，既缩短 App 启动、又把 WebView 崩溃/内存与系统主体隔开。调试见 §15 第 15 行。

> 版本差异：USAP 池（`usap_pool_primary/secondary`）在 **Android 10+**；双 zygote 与 `--enable-lazy-preload` 自 64 位普及以来一直存在；`webview_zygote` 自 N 起。

---

## 13. 车机 / 平台视角

### 13.1 开机自启服务的正确姿势：init 服务 vs BOOT_COMPLETED

| 需求 | 用哪条 | 原因 |
|---|---|---|
| 常驻 native / vendor 守护（CAN 网关、音效 DSP 守护） | **init 服务**（写 rc，`class main`/`core`） | 比任何 App 都早，不依赖用户解锁，崩溃由 init 自动重启（见 **init 篇 第九章**） |
| 必须用户解锁后才工作的 App 逻辑 | **`BOOT_COMPLETED` 接收器** | 需声明 `RECEIVE_BOOT_COMPLETED`；收不到广播见 §15 第 2 行 |
| 锁屏前就要跑（如加密存储初始化） | **`LOCKED_BOOT_COMPLETED`** | 在用户解锁前发出，早于 `BOOT_COMPLETED`（见 §10） |

原则：**"越底层、越必须常驻"越往 init 放；"越像普通 App 行为"越用广播。** 把本该是 init 服务的东西塞进 App 的 BOOT_COMPLETED，会拖慢且易因权限/解锁顺序失败。

### 13.2 车机冷启动 vs ACC 快速恢复

车机（AAOS）与手机最大不同在**供电模型**：

- **冷启动（cold boot）**：彻底断电后上电（比如拔电瓶/OTA 后首次）。走完整 §1 时序，耗时最长，是 §11.4 的优化对象。
- **ACC 快速恢复**：车辆 ACC（附件电）断开时，车机常进入 **suspend（S3 类）** 而非关机；ACC 恢复时 **resume**，zygote/system_server 根本没死，只唤醒，几百 ms 回到桌面。这条路径**不走 boot_progress 事件**，排查"为什么有时快有时慢"要先分清是冷启还是 resume。

### 13.3 Garage mode（一句带过）

Android 11+ 的车辆 **Garage Mode**：车辆静置（车库）时进入维护窗口，集中跑 dexopt / 备份 / OTA 准备等重活，避免打扰驾驶。与启动流程的关系是"它消费 boot 后空闲算力"，细节见 `automotive/` 第 15/16 篇，本篇不展开。

---

## 14. 调试与观测

按层组织，先内核/启动，再 Framework，再单服务。

```bash
# ① 内核侧：kernel → init 的交接时间
dmesg | grep -i init
dmesg | grep -i "Freeing init memory"

# ② 启动阶段计时（events 缓冲里的 boot_progress_*）
logcat -b events -d | grep -i boot
logcat -b events -d | grep am_proc_start      # 看同时起了多少进程

# ③ Framework 进程状态
ps -A | grep -E "zygote|system_server|surfaceflinger"
getprop init.svc.zygote                        # 应为 "running"
getprop init.svc.zygote_secondary             # 双 zygote 设备才有
getprop sys.boot_completed                     # "1" = 开机完成

# ④ 单服务状态（ServiceManager 注册情况）
service list | grep -i "activity\|package\|window\|power"   # 看是否注册
service check activity                         # 等价地检查某服务可达
dumpsys activity boot-phase                    # 当前 boot phase（部分版本）
dumpsys package <pkg> | grep -i "firstInstall" # PMS 对该包的视角
dumpsys window policy                          # WMS 视角的显示/窗口状态

# ⑤ SELinux / 崩溃线索
dmesg | grep avc                              # zygote specialize / addService 被拒
logcat -b system -d | grep -iE "SystemServer|Zygote|Watchdog|FATAL"
```

### 14.1 验证"某服务没起来"的分层排查

```text
第 1 层：init 有没有起这个服务？
   → ps -A | grep <svc>  与  getprop init.svc.<svc>
   → 没起：查 rc 是否 class main / 是否 ctl.start 触发（见 init 篇 9.3/9.4）

第 2 层：servicemanager 有没有注册？
   → service list | grep <name>   /  service check <name>
   → 未注册：服务 onStart 抛异常 或 addService 被拒（SELinux / 名字冲突，见 §15）

第 3 层：客户端怎么拿？
   → 客户端用 ServiceManager.getService(name) 拿到的是代理还是 null
   → 拿到 null 但 service list 有：可能是"服务不可用窗口"（§7.4）或客户端取太早（§8.3）
```

---

## 15. 常见问题排查表

| 现象 | 原因 | 章节 | 第一命令 |
|---|---|---|---|
| 卡在开机动画（bootanim 不停） | PMS 扫描慢 / 首启 dexopt / 某服务 onStart 抛异常 | §7、§11 | `getprop sys.boot_completed` |
| `BOOT_COMPLETED` 收不到 | 未声明 `RECEIVE_BOOT_COMPLETED` / 设备未解锁 / 被版本收起 | §10 | `dumpsys activity broadcasts \| grep BOOT_COMPLETED` |
| system_server 崩溃循环（bootloop） | 某服务 `onStart` 抛异常 / Watchdog 杀进程 | §9 | `logcat -b system \| grep -iE "FATAL\|Watchdog"` |
| 某系统服务空指针导致启动中断 | `onStart` 里过早 `getService` 拿到 null | §8 | `logcat \| grep -i "<Svc>.java"` |
| zygote 崩溃、全部 App 重启 | zygote 死 → `onrestart` 重启依赖 + App 全失连 | §4、§5 | `dmesg \| grep -i zygote` |
| 服务 add 失败（SELinux 拒绝） | 该 domain 无 `add_service` 权限 | §7.4 | `dmesg \| grep avc` |
| 服务 add 失败（名字冲突） | 同名服务重复 `addService` | §7.4 | `service list \| grep <name>` |
| 开机动画久 | `preload` 过重 / PMS 慢 | §11.3 | `logcat -b events \| grep boot_progress_preload` |
| 首次开机特别慢 | 首次开机需 dexopt 大量包（非 preload） | §11.3（ART 17 节） | `pm compile --list` |
| App 起不来（SELinux domain 错） | `seinfo` 配错 / `avc denied` | §5.2 | `dmesg \| grep avc` + `ps -Z <app>` |
| `ActivityThread` 报 RuntimeInit 异常 | fork 参数包解析错 / uid 非法 | §5.1 | `logcat \| grep Zygote` |
| 设备反复重启（Watchdog） | 核心服务死锁 / 主线程卡死 | §9 | `cat /data/anr/traces_* \| grep -A20 Watchdog` |
| 服务在但不工作 | boot phase 没推进 / 依赖未就绪 | §8 | `dumpsys <svc>` |
| 32 位 App 起不来 | `zygote_secondary` 没起 / 设备纯 64 位 | §12 | `getprop init.svc.zygote_secondary` |
| WebView 渲染进程崩溃 | `webview_zygote` 异常 | §12.3 | `ps -A \| grep webview` |
| 开机后 CPU 持续高 | 某服务后台循环 | §11.1 | `bootchart` / `top -H` |
| 厂商服务早于依赖拿 null | `onBootPhase` 相位用错 | §8.3 | 查该服务 `onBootPhase` 实现 |
| `LOCKED_BOOT_COMPLETED` 时机误解 | 它早于用户解锁，非"开机完成" | §10 | `dumpsys activity broadcasts` |

---

## 16. 读源码路线（6 条主线）

1. **kernel cmdline → init**：`system/core/init/main.cpp` → `FirstStageMain` / `SecondStageMain` → `init.rc` 解析（init 篇详讲）。重点看 `androidboot.*` 如何变 `ro.boot.*`。
2. **app_process → ZygoteInit**：`frameworks/base/cmds/app_process/app_main.cpp::main` → `AndroidRuntime::start` → `frameworks/base/core/jni/AndroidRuntime.cpp` → 反射 `ZygoteInit.main`（§3）。
3. **fork specialize 全程**：`ZygoteInit.forkSystemServer` / `ZygoteConnection.processCommand` → `Zygote.forkAndSpecialize` → `frameworks/base/core/jni/com_android_internal_os_Zygote.cpp`（native 降权/SELinux/Seccomp）→ `RuntimeInit` → `ActivityThread.main`（§5、§6）。
4. **SystemServer 三段**：`frameworks/base/services/java/com/android/server/SystemServer.java` 的 `startBootstrapServices` / `startCoreServices` / `startOtherServices` → `SystemServiceManager.startService` / `startBootPhase`（§7、§8）。
5. **Watchdog 循环**：`frameworks/base/services/core/java/com/android/server/Watchdog.java` 的 `run()` 与 `HandlerChecker` / `monitor`（§9）。
6. **boot 信号链路**：`SystemServer.run` → `startBootPhase(PHASE_BOOT_COMPLETED)` → `ActivityManagerService.finishBooting` → `sendBroadcast(ACTION_BOOT_COMPLETED)` + `SystemProperties.set(sys.boot_completed)`（§10）。

---

## 17. 源码路径速查 + 一图总结 + 关联阅读

### 17.1 源码路径速查表

| 内容 | 路径 |
|---|---|
| init 主程序 / 两阶段 | `system/core/init/main.cpp`、`init.cpp`（`FirstStageMain`/`SecondStageMain`） |
| rc 文件来源 | `system/core/rootdir/init*.rc`、`device/<vendor>/<product>/*.rc`（init 篇 第五章） |
| zygote rc 定义 | `system/core/rootdir/init.zygote*.rc` |
| app_process 入口 | `frameworks/base/cmds/app_process/app_main.cpp` |
| AndroidRuntime | `frameworks/base/core/jni/AndroidRuntime.cpp`（`start`/`startReg`） |
| ZygoteInit | `frameworks/base/core/java/com/android/internal/os/ZygoteInit.java` |
| ZygoteConnection | `frameworks/base/core/java/com/android/internal/os/ZygoteConnection.java` |
| Zygote（fork/specialize native） | `frameworks/base/core/jni/com_android_internal_os_Zygote.cpp` |
| RuntimeInit | `frameworks/base/core/java/com/android/internal/os/RuntimeInit.java` |
| SystemServer | `frameworks/base/services/java/com/android/server/SystemServer.java` |
| SystemServiceManager | `frameworks/base/services/core/java/com/android/server/SystemServiceManager.java` |
| SystemService | `frameworks/base/services/core/java/com/android/server/SystemService.java`（`PHASE_*` 定义） |
| Watchdog | `frameworks/base/services/core/java/com/android/server/Watchdog.java` |
| 启动事件写入 | `frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java`（`finishBooting`） |
| boot image / preload | 见 **ART 篇第九节、第十七节** |

### 17.2 一图总结（启动分层）

```text
┌──────────────────────────────────────────────────────────────┐
│ Boot ROM → bootloader → Kernel (start_kernel → rest_init)     │
└───────────────────────────────────┬──────────────────────────┘
                                     │ fork PID 1
┌────────────────────────────────────▼─────────────────────────┐
│ init（two-stage，属性服务，class main）                         │
│   ├─ servicemanager / ueventd / vold …                        │
│   └─ service zygote /system/bin/app_process64 (rc 见 §2.3)     │
└────────────────────────────────────┬─────────────────────────┘
                                     │ AndroidRuntime::start (§3)
┌────────────────────────────────────▼─────────────────────────┐
│ Zygote（preload：类/资源/库，见 ART 篇 §9）                     │
│   ├─ forkAndSpecialize → App  (USAP 池，§4.2)                  │
│   └─ forkSystemServer  → system_server (不 exec，§6)           │
└────────────────────────────────────┬─────────────────────────┘
                                     │ SystemServer.run 三阶段 (§7)
┌────────────────────────────────────▼─────────────────────────┐
│ system_server                                                   │
│   bootstrap(Installer/ATMS/AMS/PMS/Display)                    │
│   → core(Battery/Usage/WebView)                                │
│   → other(WMS/Input/NMS/Power/Audio…)                          │
│   → startBootPhase(1000) → AMS.finishBooting                  │
└────────────────────────────────────┬─────────────────────────┘
                                     │ 三种信号 (§10)
        ┌────────────────────────────┼───────────────────────────┐
        ▼                            ▼                           ▼
  PHASE_BOOT_COMPLETED      ACTION_BOOT_COMPLETED          sys.boot_completed=1
  (系统服务内部)            (App 自启)                    (init 停 bootanim)
```

**一句话记忆链**：`init 拉 zygote，zygote fork 出世界（App + system_server），system_server 三阶段装配服务，phase 推进到 1000，三信号齐发，开机完成——而 Watchdog 在背后盯着谁卡死。`

### 17.3 关联阅读

- `androidOthers/Android启动与属性系统详解-从init.rc到属性服务.md`：init 两阶段、init.rc 语法、属性系统（本篇 §2 衔接点）。
- `androidOthers/Android虚拟机详解-从Dalvik到ART与代码形态演变.md`：第九节 preload/boot image、第十七节 dexopt 与车机开机时间（本篇 §4.1、§11.3）。
- `02_Binder机制详解`：ServiceManager、`addService`/`getService` 的 Binder 底层。
- `04_PMS机制详解`：PMS 扫描/安装与启动阶段的位置（§7.1）。
- `05_AMS机制详解` / `06_ATMS机制详解`：组件启动、Home 拉起（§7、§10）。
- `11_WMS机制详解`：WMS 在 `startOtherServices` 的位置与窗口系统。
- `18_SystemUI机制详解`：第 5 节 SystemUI 进程如何被 system_server 拉起。
- `08_Broadcast机制详解`：`ACTION_BOOT_COMPLETED` 广播的发送与接收。

> 版本差异速记：USAP 池 **Android 10+**；`webview_zygote` **Android 7+**；`--enable-lazy-preload` 随双 zygote 普及；`PHASE_*` 数值跨版本稳定；Android 14+ 对 first-stage init 的早年挂载（snapuserd 等）进一步收敛到第一阶段，init 时序细节以你仓库实际 `init.rc` 与 `init.cpp` 为准。
