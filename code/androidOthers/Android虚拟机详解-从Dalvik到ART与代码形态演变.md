# Android 虚拟机详解——从 Dalvik 到 ART，一份代码的形态演变

> 姊妹篇：`Android开发者网络串讲-从WiFi到HTTP.md`（分层栈）、`Android蓝牙机制详解-从跳频到GATT与车机互联.md`（能力树）、`Android存储机制详解-从分区到ScopedStorage与多用户.md`（空间层级）、`AndroidNDK与JNI详解-从跨语言调用到so加载与Native崩溃排查.md`（边界桥接）。
>
> **这一篇用第五种组织方式：流水线型。**
>
> 虚拟机没法按类名罗列来学（ART 的类有几千个，按类讲就变成了源码目录导读），也不适合分层栈（它不是一个上下依赖的协议栈）。它最自然的形状是一条**形态降级链**——同一份 Kotlin 代码，先后以五种形态存在：
>
> ```
>    .kt/.java  →  .class  →  .dex  →  .oat/.vdex（机器码）  →  堆里的对象
>      源码          字节码      虚拟机字节码      本地代码          运行时数据
>      ↑谁产出        ↑谁产出     ↑谁产出          ↑谁产出           ↑谁在管
>      javac/kotlinc  d8/R8      dex2oat/JIT      ART 运行时        GC
> ```
>
> 所以全文按这条链的顺序讲：**每章回答"这个形态谁产出的、长什么样、错了会怎样"**。中间穿插两条横切线——GC（第十~十三章）和可观测性（第十五~十七章）。
>
> **阅读约定**：
> - 「**App 视角**」= 只写 App 的开发者需要知道的（配置、日志、性能）。
> - 「**平台视角**」= 只有改 ROM / 系统 App / 预装策略才会碰到的（boot image、dexopt 策略、编译参数）。
> - 版本基线：Dalvik 覆盖到 4.4，ART 以 Android 13/14/15 为主线；**ART 是模块化可更新的（APEX），同一台 Android 版本上 ART 版本可能不同**，涉及具体参数时会标出这一点。

---

## 目录

**第一段：形态链（一份代码的五种样子）**

1. [开场：虚拟机在系统里的位置](#一开场虚拟机在系统里的位置)
2. [Dalvik / ART / JVM 三者对照](#二dalvik--art--jvm-三者对照)
3. [形态一：源码到字节码](#三形态一源码到字节码)
4. [形态二：字节码到 DEX（d8 / R8 / 脱糖 / 64K 限制）](#四形态二字节码到-dex)
5. [DEX 格式解剖](#五dex-格式解剖)
6. [形态三：DEX 到机器码（AOT 与 dex2oat）](#六形态三dex-到机器码aot-与-dex2oat)

**第二段：运行期（代码跑起来以后）**

7. [形态四：执行链（解释器到优化编译）](#七形态四执行链解释器到优化编译)
8. [Profile 与 dexopt 策略：性能为什么时好时坏](#八profile-与-dexopt-策略性能为什么时好时坏)
9. [启动映像与 Zygote：所有 App 共享的那份内存](#九启动映像与-zygote所有-app-共享的那份内存)
10. [形态五：堆的布局](#十形态五堆的布局)
11. [垃圾回收：从 mark-sweep 到并发复制到分代](#十一垃圾回收从-mark-sweep-到并发复制到分代)
12. [逐字段读 GC 日志](#十二逐字段读-gc-日志)
13. [引用、finalizer、Cleaner 与泄漏](#十三引用finalizercleaner-与泄漏)

**第三段：周边与落地**

14. [类加载与 dex 动态加载](#十四类加载与-dex-动态加载)
15. [可观测性与调试工具](#十五可观测性与调试工具)
16. [性能剖析：编译状态、内联、启动优化](#十六性能剖析编译状态内联启动优化)
17. [平台视角：boot image、dexopt 策略、车机定制](#十七平台视角boot-imagedexopt-策略车机定制)
18. [常见问题排查表](#十八常见问题排查表)
19. [读源码与读文档路线](#十九读源码与读文档路线)
20. [一图总结](#二十一图总结)

---

## 一、开场：虚拟机在系统里的位置

### 1.1 一张位置图

```
┌──────────────────────────────────────────────────────────────────┐
│  App（Kotlin / Java 代码）                                        │
├──────────────────────────────────────────────────────────────────┤
│  Framework（Java 层：ActivityManagerService、WMS…）                │
├──────────────────────────────────────────────────────────────────┤
│  ┌───────────────────── 本文的主角 ─────────────────────┐          │
│  │  虚拟机（ART）                                      │          │
│  │   · 把 DEX 编译/解释成机器码执行                     │          │
│  │   · 提供 Java 语义：对象、线程、异常、类加载          │          │
│  │   · 管理 Java 堆与 GC                               │          │
│  │   · 提供 JDWP 调试、SIGQUIT 抓栈、tombstone 里的 Java 栈 │      │
│  └─────────────────────────────────────────────────────┘          │
├──────────────────────────────────────────────────────────────────┤
│  native 层（libc / libbinder / libnativehelper / NDK）            │
├──────────────────────────────────────────────────────────────────┤
│  Linux 内核（mmap / futex / 信号 / cgroup / SELinux）              │
└──────────────────────────────────────────────────────────────────┘
```

三个"虚拟机"容易混的概念，先分清：

| 说法 | 指的是什么 | 和本文的关系 |
|---|---|---|
| **Android 虚拟机** | 狭义 = 执行 Java 字节码的运行时（Dalvik / ART） | **本文主角** |
| `app_process` / Zygote 进程 | 承载虚拟机的**进程** | 第九节 |
| 虚拟化（AVD、KVM、容器） | 硬件/系统级虚拟化 | 无关 |

### 1.2 为什么 Android 要自己造一个虚拟机，而不用 JVM

这是理解整个设计的起点。Google 当年的四条约束：

| 约束 | JVM 的问题 | Android 的对策 |
|---|---|---|
| 手机内存/CPU 有限（2007 年） | JVM 单进程模型重，每进程一套运行时 | **Zygote 预加载 + fork**，所有 App 共享 boot image（第九节） |
| 需要多进程隔离 + 快速启动 | JVM 启动一套运行时很慢 | 进程 fork 时直接继承已初始化的虚拟机状态 |
| 体积要小（安装包、ROM） | `.jar` 里每个 class 各自带常量池，冗余大 | **DEX 把所有类合并成共享的常量池**，体积小很多 |
| 安全模型按应用沙箱设计 | JVM 的 SecurityManager 模型不适合 | 靠 UID + SELinux，虚拟机只负责语言语义 |

一句话：**Android 的虚拟机不是"Java 的另一个实现"，而是为"一个设备上跑几十个互相隔离的应用进程"重新设计的运行时。**

### 1.3 这篇怎么读

- **只想搞懂性能问题**（启动慢、卡顿、内存涨）→ 直接看 **第八节（Profile 与 dexopt）**、**第十二节（读 GC 日志）**、**第十六节（性能剖析）**。
- **只想知道构建产物**（为什么有 dex、为什么要 multidex）→ **第四、五节**。
- **要改 ROM / 预装 / 车机** → **第十七节**（boot image、dexopt 策略、heap 参数、开机时间）。
- **想系统读一遍** → 按顺序，前六章是形态链，后面是运行时。

---

## 二、Dalvik / ART / JVM 三者对照

### 2.1 一页历史

| Android 版本 | 运行时 | 关键变化 |
|---|---|---|
| 1.0 – 2.1 | **Dalvik** | 纯解释执行 |
| 2.2 (Froyo) | Dalvik + **Tracing JIT** | 追踪热循环生成 trace 代码，性能约 2~5 倍 |
| 4.4 (KitKat) | **ART 作为可选运行时** | 开发者可切换；AOT 编译，安装变慢但运行快 |
| 5.0 (Lollipop) | **ART 成为唯一运行时** | Dalvik 被移除；AOT（全量 `speed`） |
| 7.0 (Nougat) | ART + **JIT/AOT 混合 + Profile** | 安装时几乎不编译，先跑 JIT 收 profile，空闲时按 profile 优化编译（**当前架构的起点**） |
| 8.0 (Oreo) | 并发复制 GC | CC GC 取代 CMS 成为默认；`.art`（app image）引入 |
| 9.0 (Pie) | Profile 二进制格式 | profile 更小更快；`speed-profile` 成熟 |
| 10 (Q) | **ART 进 APEX（可独立更新）** | `com.android.art` 模块化；Cloud Profiles |
| 12 – 15 | 模块化 + 更多 GC/编译优化 | 分代 GC、16KB 页支持等；ART 版本与 Android 版本解耦 |

> **一个必须建立的认知**：从 Android 10 起，**ART 是一个可以单独更新的模块（APEX）**。所以"Android 13 的 ART 是什么行为"这句话本身不严谨——同一台 Android 13 设备，ART 可能被打过补丁。排查编译/GC 行为时，先确认 ART 版本：

```bash
adb shell cat /apex/com.android.art/apex_manifest.json | grep -i version   # APEX 里声明的版本
adb shell getprop ro.apex.updatable                                        # 是否支持 APEX 更新
adb shell dumpsys package | grep -i "art" | head
# 老一些的写法（仍有用）
adb shell "cmd package dump com.android.art 2>/dev/null | head"
```

### 2.2 Dalvik vs ART 的关键差异

| 维度 | Dalvik | ART |
|---|---|---|
| 执行方式 | 解释 + **Tracing JIT** | 解释 + **Method-based JIT** + **AOT**（混合） |
| 编译粒度 | trace（一段基本块序列） | method（整个方法） |
| 安装/启动 | 装得快、启动要 JIT 预热 | 7.0 前装得慢（AOT）；7.0 后装得快、后台再编译 |
| 编译时机 | 只在运行时 | 安装时 / 空闲时 / 运行时（JIT） |
| 编译产物 | JIT 代码在内存（+ `dalvik-cache` 里的 odex 优化结果） | `.oat`（机器码）+ `.vdex`（dex 与验证信息） |
| GC | mark-sweep，**全程 STW** | 并发复制（CC），大部分阶段与应用线程并发 |
| GC 可见症状 | `GC_FOR_ALLOC freed ...` 频繁、卡顿明显 | `Background concurrent copying GC ... paused 123us`，暂停进了微秒级 |
| 栈帧/寄存器 | 同样的寄存器式字节码 | 相同 DEX 格式；执行引擎换成 optimizing compiler |
| 调试信息 | `dexdump` | `oatdump`（能看到编译后的机器码与 GC map） |

### 2.3 DEX vs JVM class：为什么 Android 不直接用 `.class`

| 维度 | JVM `.class` | Android `.dex` |
|---|---|---|
| 指令模型 | **栈式**（操作数栈） | **寄存器式**（最多 65535 个虚拟寄存器） |
| 常量池 | **每个类一份** | **整个 DEX 共享一份**（string/type/field/method 池） |
| 一个文件的粒度 | 一个类 | **一个模块的几百上千个类**（合并） |
| 引用方式 | 符号名（`Ljava/lang/String;`） | 索引（16 位 type_idx、16 位 method_idx） |
| 体积 | 冗余大 | 小很多（这是当年的核心动机） |
| 指令数 | 约 200+ | 约 200+，但格式固定 16 位单元，便于嵌入式解码 |

**寄存器式带来的直接后果**：单条指令做更多事（一条 `add-int v0, v1, v2`），指令数更少、解码更快，但**指令编码里放索引的位宽有限**——这正是 **64K 方法数限制**的根因（第四节、第五节展开）。

---

## 三、形态一：源码到字节码

### 3.1 这一步做了什么

```
Hello.kt  ──kotlinc──▶  Hello.class  ──┐
                                        ├─▶  （JVM 字节码，栈式）
Hello.java ──javac────▶  Hello.class  ──┘
```

- **Kotlin → JVM 字节码**：`kotlinc` 把 Kotlin 编译成 **Java 虚拟机字节码**（不是 Android 专有格式）。Kotlin 的 `object`、扩展函数、默认参数、`inline`、协程都会被翻译成 Java 看得到的东西（静态字段、静态方法、合成方法等）。
- **这一步在 Android 上其实"用不到 JVM"**：编译发生在构建机上，产物 `.class` 只是**给 d8/R8 吃的中间格式**。设备上从来没有 JVM 字节码在跑。

### 3.2 和虚拟机有关的三个后果

**① Kotlin 的语法糖会变成什么（影响方法数与体积）**

| Kotlin 写法 | 编译成什么 | 对虚拟机的影响 |
|---|---|---|
| `object Foo { }` | 类 + `INSTANCE` 静态字段 + 私有构造 | 多几个方法 |
| `data class` | `component1..N`、`copy`、`equals`、`hashCode`、`toString` | **每个 data class 约 +6 个方法** |
| 扩展函数 | 静态方法 + 首参是接收者 | 不额外增加方法，但**不能被子类重写** |
| 默认参数 | 多一个合成方法（`foo$default`） | 重载越多，合成方法越多 |
| `inline` 函数 | 调用点展开，函数体可能被丢弃 | **有助于减少方法数**（尤其是 `forEach` 之类） |
| 属性 `val x` | `getX()` | 每个属性 +1 方法 |
| 协程 | 状态机类 + `Continuation` 对象 | 每个挂起点会生成额外类与方法 |
| 包级函数/属性 | `FooKt` 类里的静态方法 | 出现一堆 `XxxKt` 类 |

**这一列对 64K 方法数吃紧的项目很实际**：data class 和协程是方法数大户。

**② 注解处理器 / KAPT / KSP 在哪一步**

```
Kotlin 源码
   ├─ KSP / KAPT：生成额外源码（Room 的 _Impl、Hilt、DataBinding）
   └─ kotlinc / javac：编译成 .class
        └─ d8 / R8：转 DEX
```

**生成的代码也要进 DEX**，所以"我明明没写几行，方法数却爆了"的答案往往在这里。

**③ 调试信息（行号）从这一步就绑定了**

`.class` 里的 `LineNumberTable` / `LocalVariableTable` 会一路带到 DEX 的 `debug_info_item`，最终决定崩溃栈里能不能给出 `Foo.kt:42`。**release 构建用 R8 时 `-keepattributes SourceFile,LineNumberTable` 影响的就是这条链。**

```kotlin
// app/build.gradle.kts（release）
android {
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles += file("proguard-rules.pro")
        }
    }
}
```

```proguard
# proguard-rules.pro：保留行号，否则线上崩溃栈只有 "Foo.a(Unknown Source)"
-keepattributes SourceFile,LineNumberTable
# 保留文件名字符串（否则文件名被混淆成 "a"）
-renamesourcefileattribute SourceFile
```

### 3.3 「平台视角」构建链的三种历史方案

| 方案 | 时期 | 说明 |
|---|---|---|
| `javac` + `dx` | Android 1.0 – 7.0 | `dx` 是老的 dex 转换器（已在 AGP 中移除） |
| **Jack & Jill** | Android 6 – 8（实验性） | 一个把源码直接编译到 DEX 的编译器，已废弃 |
| **javac/kotlinc + d8/R8** | Android 8 至今 | 当前唯一方案 |

---

## 四、形态二：字节码到 DEX

### 4.1 d8 与 R8 的分工

```
.class（一堆）──d8──▶ classes.dex            （只做转换 + 脱糖）
.class（一堆）──R8──▶ classes.dex            （转换 + 脱糖 + 压缩 + 优化 + 混淆）
                        ↑
                   release 默认走这条（AGP 3.4+）
```

| 工具 | 做什么 | 什么时候用 |
|---|---|---|
| **d8** | `.class` → DEX；**脱糖（desugaring）**；multidex 拆分 | debug 构建、`isMinifyEnabled=false` |
| **R8** | d8 的全部功能 + **shrink（删无用代码）** + **optimize** + **obfuscate（混淆）** | release 构建、`isMinifyEnabled=true` |

R8 与 ProGuard 的关系：**R8 是 ProGuard 规则的兼容实现**（读同一套 `proguard-rules.pro`），但引擎完全不同——R8 直接在 DEX/字节码层做全局优化，比 ProGuard 快且优化更激进。

### 4.2 脱糖（Desugaring）：新语法怎么在旧设备上跑

这是 Android 编译链最独特的一环。DEX 的指令集演进很慢，所以**新语言特性要在编译期被"翻译"成老指令**：

| 语言特性 | 脱糖结果 |
|---|---|
| Lambda / 方法引用（`minSdk < 26`） | 生成一个合成内部类（`Foo$$ExternalSyntheticLambda0`） |
| Lambda（`minSdk >= 26`） | 用 DEX 的 `invoke-custom` 指令 + `LambdaMetafactory`（**Android 8 起 DEX 才支持这个 opcode**） |
| 接口默认方法 / 静态方法 | 生成伴生类 + 在实现类里补转发方法 |
| `try-with-resources` | 展开成 `try/finally` + 显式的 null 判断 |
| 私有接口方法 | 转成静态辅助方法 |
| `java.time`、`java.util.stream`、`Optional` 等 | **需要 core library desugaring**（把库的一部分打包进 APK） |

```kotlin
android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true      // 让 java.time / stream 在低版本可用
    }
}
dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
```

**脱糖直接影响两件事**：① 方法数与体积（每个 lambda 一个类）；② 调试体验（栈里出现 `Foo$$ExternalSyntheticLambda0.run` 而不是你的源码行）。

### 4.3 64K 方法数限制：限制到底在哪

**结论先行**：一个 `.dex` 文件里最多能有 **65536** 个"被引用的方法"和 **65536** 个"被引用的字段"，因为：

```
DEX 的 invoke-kind 指令格式（简化）：
   [op(8bit)] [method@BBBB(16bit)] [arg count / registers]

                     ↑ 这个索引只有 16 位 → 最多 65536
```

所以不是"你写了 65536 个方法就爆"，而是**你在一个 dex 里引用到的（含 Android framework 的所有方法）超过 65536 个**——这也解释了为什么加一个第三方库（哪怕只调一个方法）也可能把方法数顶爆。

顺便把 DEX 里另外几个池的位宽也说清（它们在 DEX 格式里定义）：

| 池 | 引用索引位宽 | 上限 |
|---|---|---|
| `string_ids` | 32 位 | 很大（`string_idx` 是 u32） |
| `type_ids` | **16 位** | 65536 个类型 |
| `proto_ids`（方法签名） | **16 位** | 65536 个签名 |
| `field_ids` | 指令里 16 位 | 65536 个字段 |
| `method_ids` | 指令里 16 位 | **65536 个方法（就是那个著名限制）** |

### 4.4 Multidex：怎么绕过 64K

**做法**：把代码切成 `classes.dex`、`classes2.dex`、`classes3.dex`…（每个都是独立的 DEX，各有自己的 64K 空间）。

| Android 版本 | multidex 的行为 | 注意 |
|---|---|---|
| < 5.0（API < 21） | 系统只认 `classes.dex`，其余要靠 `MultiDex.install(context)` 在**应用启动时**手动加载 | **必须在 `attachBaseContext` 里调**；启动要解压/校验 dex，**容易 ANR**；需要 `MultiDexApplication` |
| ≥ 5.0（API ≥ 21） | 系统原生支持 multidex，`classesN.dex` 自动全部加载 | 无额外配置；`minSdk >= 21` 时 AGP 默认走这条 |

```kotlin
android {
    defaultConfig {
        multiDexEnabled = true
        // minSdk >= 21 时不需要 MultiDexApplication
        // minSdk < 21 需要：multiDexKeepProguard / multiDexKeepFile
    }
}
```

> **平台视角**：Android 5.0 之前那套 `MultiDex.install` 在车机上很少见（车机基本都 ≥ 8），但**预装 App 仍会碰到方法数问题**——因为预装 App 常常把一堆 SDK 塞在一起，而且**系统分区 App 的 dex 也要进 DEX 限制**。解法不是 multidex，而是把可拆的部分做成独立 App 或用 `PRODUCT_PACKAGES` 拆模块。

### 4.5 R8 做了什么（决定你 release 包的行为）

R8 的四类动作，每一类都直接影响虚拟机上看到的东西：

| 动作 | 做什么 | 对虚拟机的影响 |
|---|---|---|
| **Shrinking（摇树）** | 删未被引用的类/方法/字段 | **反射用到的类可能被误删**（需要 keep 规则） |
| **Optimization** | 内联小方法、删无用分支、合并类、常量传播 | 崩溃栈里的方法可能"消失"（被内联了） |
| **Obfuscation** | 类/方法/字段改名成 `a`、`b` | 崩溃栈全是短名；**JNI 用命名约定会失效**（与 NDK 篇 第三节对应） |
| **Dexing** | 生成 DEX，控制 multidex 拆分 | 决定哪些类在 `classes.dex`（影响启动，见下） |

**R8 与启动速度**：`classes.dex` 是启动时必须加载的，所以 R8 会尽量把**启动路径上的类**放在主 dex。控制手段：

```proguard
# 1) 手动指定要放主 dex 的类（minSdk < 21 的 multidex 才需要）
#    在 multiDexKeepFile 指向的文件里写：
#    com/example/MyApplication.class
#    com/example/startup/LauncherActivity.class

# 2) 保留反射/序列化用到的成员（否则线上必崩，debug 不崩）
-keep class com.example.model.** { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep <methods>;
}
-keep @androidx.annotation.Keep class * {*;}

# 3) 保留 JNI 方法（与 NDK 篇第三节：RegisterNatives 可以少写 keep 规则）
-keepclasseswithmembernames class * { native <methods>; }

# 4) 保留枚举的 values()/valueOf()（被反射调用）
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
```

> **一条铁律**：**debug 不崩、release 崩**的绝大多数原因是 R8 删了/改了反射用到的类。**在 release 上跑一遍完整回归**是唯一可靠的验证。

---

## 五、DEX 格式解剖

这一节让 64K 限制、`dexdump` 输出、`VerifyError` 从"黑箱"变成"看得懂的东西"。

### 5.1 文件结构（从前往后）

```
┌─────────────────────────────────────────────┐
│ header_item（112 字节，固定）                 │
│   magic      "dex\n035\0" / 037 / 038 / 039  │  ← DEX 版本号
│   checksum   adler32（校验整个文件）           │
│   signature  SHA-1（校验 data 段）            │
│   file_size / header_size                    │
│   endian_tag                                  │
│   map_off    → 描述"所有段在哪"的总索引         │
│   string_ids_size/off                         │
│   type_ids_size/off                           │
│   proto_ids_size/off                          │
│   field_ids_size/off                          │
│   method_ids_size/off                         │
│   class_defs_size/off                         │
│   data_size/off                               │
├─────────────────────────────────────────────┤
│ string_ids[]   → 指向 data 段里的 MUTF-8 字符串 │
│ type_ids[]     → 字符串索引（如 "Ljava/lang/String;"）│
│ proto_ids[]    → (返回类型, 参数列表)          │
│ field_ids[]    → (所属类, 类型, 名字)          │
│ method_ids[]   → (所属类, 原型, 名字)          │
│ class_defs[]   → 类定义（含 class_data_item 的偏移）│
├─────────────────────────────────────────────┤
│ data 段                                        │
│   · 字符串数据（MUTF-8）                       │
│   · type_lists（方法的参数类型列表）             │
│   · annotation 相关                             │
│   · debug_info_item（行号、局部变量）            │
│   · code_item（★ 真正的字节码在这里）            │
│   · class_data_item（字段/方法表）               │
│   · map_list                                    │
│   · 静态值（static values）                     │
└─────────────────────────────────────────────┘
```

**读法要点**：DEX 是"索引表 + 数据区"的设计——前面全是定长索引表，真正的内容在 data 段。**这正是它能做到共享常量池、体积小的原因。**

### 5.2 `code_item`：字节码长什么样

```bash
# 把 DEX 反汇编成可读的字节码（-d 显示指令）
$ANDROID_HOME/build-tools/<ver>/dexdump -d classes.dex | head -80

# 只看某个类的
dexdump -d -f classes.dex | grep -A40 "Class descriptor.*MyClass"
```

输出的形态大致是：

```
0004c8:                                        |[0004c8] com.example.MyClass.add:(II)I
0004d0: 9000 0304                              |0000: add-int v0, v3, v4      ← 寄存器式指令
0004d4: 0f00 0300                              |0002: return v0
```

几个要点：

- 指令是 **16 位单元（code unit）** 为单位的，所以偏移量都是偶数（`0000`、`0002`…）。
- 寄存器用 `v0`、`p0` 表示：`v` 是局部寄存器，`p` 是"参数寄存器"（映射到方法末尾的 v 寄存器）。
- 64 位值（`long`/`double`）占**一对**寄存器（如 `v0:v1`）。
- **寄存器数量上限 65535**，且每个方法声明的寄存器数受指令编码限制（实践中远低于 65535 也会因为极端情况出问题）。

### 5.3 DEX 版本与 Android 版本

| DEX magic | 版本 | 引入的关键能力 | 对应 Android |
|---|---|---|---|
| `dex\n035` | 035 | 基础格式（远古） | 1.0+ |
| `dex\n037` | 037 | 支持 `default` 接口方法 | 7.0 |
| `dex\n038` | 038 | **`invoke-custom` / `invoke-polymorphic`** | **8.0** |
| `dex\n039` | 039 | **`const-method-handle` / `const-method-type`** | 9.0 |
| `dex\n040/041` | 040+ | 新版本继续演进（如 16KB 页相关） | 10+ |

**这意味着**：`minSdk = 21` 的项目不能直接用 `invoke-custom`（DEX 38 才有），所以 d8/R8 会**按 `minSdk` 决定脱糖策略**（4.2 节那张表）。构建日志里出现 `Desugaring invoke-custom` 之类的词就是这条链在工作。

### 5.4 VDex 与 CompactDex：dex 的"二次包装"

Android 8 起引入了两层包装，理解它们能解释"为什么同一个 APK 在设备上占两份空间"：

| 产物 | 是什么 | 为什么要有 |
|---|---|---|
| `.vdex` | 一个文件里装 **原始 dex + 验证结果（verification）**，可含 **CompactDex** | 避免 dex 在 `/data` 再存一份；验证结果可以直接复用 |
| **CompactDex（cdex）** | 重排过的 DEX，去掉验证时不需要的东西，更紧凑 | 减少 vdex 体积、加快加载 |
| `.oat` | **机器码**（AOT 编译产物） | 直接执行，不用再编译 |

```
APK 里的 classes.dex（原始，只读，在 /system 或 /data/app）
        │  安装/优化时
        ▼
/data/app/<pkg>/oat/arm64/base.vdex    ← 原始 dex + 验证信息（+ cdex）
/data/app/<pkg>/oat/arm64/base.oat     ← AOT 机器码
/data/app/<pkg>/oat/arm64/base.art     ← 可选的堆镜像（boot image 那种，见第九节）
```

> **体检命令**：直接看某个 App 的这些文件有多大，能立刻判断"这台设备上它被编译到什么程度"：
> ```bash
> adb shell "ls -l /data/app/*/com.example.app-*/oat/arm64/"
> #  base.oat  —— 机器码，越大说明编译得越激进（speed > speed-profile > verify）
> #  base.vdex —— dex + 验证信息
> #  base.art  —— 堆镜像（通常没有）
>
> # 系统分区 App（自己目录只读）的产物在 dalvik-cache：
> adb shell "ls -l /data/dalvik-cache/arm64/ | grep -i <name>"
> ```

---

## 六、形态三：DEX 到机器码（AOT 与 dex2oat）

### 6.1 dex2oat：把 dex 编译成本地代码的那个进程

```bash
# 手动给某个包做一次 AOT 编译（最直白的理解方式）
adb shell cmd package compile -m speed -f com.example.app
#   -m speed        编译器过滤档（compiler filter），见 6.2
#   -f              强制执行（不管是否已有产物）
# 结果：/data/app/.../oat/arm64/base.oat 变大

# 只按 profile 编译（默认策略）
adb shell cmd package compile -m speed-profile -f com.example.app

# 重置（删掉编译产物，回到解释执行）
adb shell cmd package compile --reset com.example.app

# 看某个包的编译状态
adb shell dumpsys package com.example.app | sed -n '/Dexopt state/,/^$/p'
#   [com.example.app]
#     path: /data/app/.../base.apk
#     arm64: [status=speed-profile] [reason=bg-dexopt]
#            ↑状态            ↑是谁触发的这次编译
#     arm64: [status=verify] [reason=install]
```

`dumpsys package` 里 `status` 的值就是下面这张表里的档位，`reason` 说明**这次编译是被什么触发的**——这是排查"为什么我的 App 在有些设备上很快"的第一条线索。

### 6.2 compiler filter：档位决定一切

| filter | 做什么 | 体积 | 运行性能 | 什么时候用 |
|---|---|---|---|---|
| `verify` | 只做验证，不编译（解释执行 + JIT） | **最小** | 最低（首次运行慢） | 首次开机、OTA 后短暂状态、空间紧张设备 |
| `quicken`（历史） | 只做"快速化"（旧版机制） | 小 | 低 | 已废弃 |
| **`speed-profile`** | **只编译 profile 命中的热点方法** | 中 | **高**（热点全在机器码里） | **安装后默认、后台 dexopt 默认** |
| `speed` | 全量优化编译（所有方法） | **最大** | 最高（但差异不大） | boot image、`PRODUCT_DEXPREOPT_SPEED_APPS`、手动 `pm compile` |
| `everything` | `speed` + 保留调试信息 | 最大 | 高 | 调试/分析用 |
| `run-from-apk` / `run-from-apk-fallback` | 直接从 APK 跑，不在 /data 生成产物 | 0 | 低 | 分区空间极紧张、只读设备 |

**核心认知**：`speed-profile` 用 20% 的体积拿到了 `speed` 约 95% 的性能——**这就是为什么 Google 在 Android 7 之后放弃了全量 AOT**。没装 profile 的 App 走 `verify`（解释 + JIT 预热），装上 profile 后再后台编译成 `speed-profile`，性能会**在使用的头几天逐渐变好**。

`verify` + `speed-profile` 不是全部：

| 状态 | 含义 | 典型触发 |
|---|---|---|
| `verify` | 只有验证，没编译 | 刚安装、`pm.dexopt.install=verify` 的设备 |
| `speed-profile` | 按 profile 编译 | bg-dexopt 跑过之后 |
| `speed` | 全量编译 | boot image、`cmd package compile -m speed` |
| `space`（历史） | 最小体积优先 | 老设备低存储 |

### 6.3 dex2oat 的关键参数

```bash
# 官方文档里会提到的手动调用（一般在 /apex/com.android.art/bin/dex2oat64）
adb shell dex2oat64 --dex-file=/data/local/tmp/classes.dex \
    --oat-file=/data/local/tmp/out.oat \
    --instruction-set=arm64 \
    --compiler-filter=speed-profile \
    --profile-file=/data/misc/profiles/cur/0/com.example.app/primary.prof \
    --boot-image=/system/framework/arm64/boot.art
```

| 参数 | 作用 |
|---|---|
| `--compiler-filter` | 编译档位（6.2） |
| `--profile-file` | 用哪个 profile 决定热点 |
| `--instruction-set` | `arm64` / `arm` / `x86_64` / `x86` |
| `--boot-image` | 用哪个 boot image（决定哪些类已经预初始化） |
| `--dex-file` / `--oat-file` | 输入输出 |
| `--swap-fd` / `--very-large-app-threshold` | 大 App 的优化（把 dex 换到临时文件、调阈值） |
| `--compilation-reason` | 记录本次编译的原因（会写进 dumpsys 的 reason） |

> **平台视角**：`dalvik.vm.dex2oat-*` 系列属性（如 `dalvik.vm.dex2oat-filter`、`dalvik.vm.dex2oat-threads`）是老版本的调参入口；**Android 12 起统一迁到了 `pm.dexopt.*`**（见第十七节）。改 ROM 时以自己版本的 `pm.dexopt.*` 为准。

### 6.4 AOT 的代价与取舍

| 维度 | 全量 AOT（`speed`） | Profile AOT（`speed-profile`） |
|---|---|---|
| 安装时间 | 长（几十秒到几分钟） | 短（`verify`，几乎无编译） |
| 磁盘占用 | 大（oat 可能比 dex 大 2~3 倍） | 中 |
| 首次运行性能 | 好 | **差**（解释 + JIT 预热） |
| 稳定期性能 | 好 | **好**（与 speed 接近） |
| 适合谁 | 系统 App、极高频 App | 绝大多数 App（默认） |

**"首次启动很慢"的合理解释**：新装的 App 只有 `verify` 状态，第一次启动纯解释执行 + JIT 预热。**这不是 bug，是设计**——用"头几分钟慢"换"安装快 + 省空间 + 后台还能改"。

App 侧能做的事情：

```kotlin
// 1) 首次启动时主动请求编译（会让用户等，慎用；适合引导页/初始化流程）
//    需要权限：Android 14+ 起普通 App 无法直接调用 compile，这里是平台 App 的做法
//    普通 App 做法：把重活延后，或者用 Baseline Profile（见下）

// 2) ★ 首选：Baseline Profile（Android 9+ / AGP 8.0+ 正式支持）
//    在构建期把一个"预热 profile"打进 APK，安装后系统可以直接按它编译，
//    不必等用户用出来。对启动速度提升通常 20%~40%。
```

```kotlin
// app/build.gradle.kts —— Baseline Profile
plugins {
    id("androidx.baselineprofile")
}
dependencies {
    baselineProfile(project(":baselineprofile"))     // 一个专门跑启动路径的模块
}
android {
    buildTypes {
        // baseline profile 需要 non-minified 变体 + 不能关掉 ART
        create("benchmark") { initWith(getByName("release")); isMinifyEnabled = false }
    }
}
```

```kotlin
// baselineprofile/src/main/kotlin/BaselineProfileGenerator.kt
@OptIn(ExperimentalBaselineProfilesApi::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test fun generate() = rule.collect(packageName = "com.example.app") {
        startActivityAndWait()                     // 冷启动
        device.findObject(By.res("home")).click()  // 走一遍启动关键路径
        device.waitForIdle()
    }
}
```

**Baseline Profile 的产物**会以 `assets/dexopt/baseline.prof`（或 `baseline.profm`）形式进 APK，系统安装时会读它。**这是目前 App 提升启动速度最划算的手段**，而且对低端机效果更明显。

### 6.5 编译产物的存放与命名

| 场景 | 产物位置 |
|---|---|
| 从 `/data/app` 安装的 App | `/data/app/~~<随机>/<pkg>-<随机>/oat/<abi>/base.{oat,vdex,art}` |
| 系统分区上的 App（自己目录只读） | `/data/dalvik-cache/<abi>/` 下按转义后的路径命名，例如 `system@priv-app@Foo@Foo.apk@classes.dex` |
| boot image | `/system/framework/<abi>/boot.{art,oat,vdex}`（名字/位置随版本有变化，用 `ls` 确认） |
| 多 ABI | 每个 ABI 一套产物（所以 4 ABI 的设备可能占 4 份 oat 空间） |

```bash
# 一次看清某个包的编译产物与状态（排查"空间被 oat 吃掉"最有用）
adb shell "dumpsys package <pkg> | sed -n '/Dexopt state/,/^$/p'"
adb shell "du -sh /data/app/*/<pkg>-*/oat/*"
adb shell "du -sh /data/dalvik-cache/*"      # 系统 App 的产物总量（经常几个 GB）
```

> **一个高频疑问**：**"为什么卸载 App 后 /data 空间没完全回来？"** —— `/data/dalvik-cache` 里系统 App 的编译产物不属于任何 App，卸载用户 App 不会回收它；而**系统 App 升级/降级后旧产物会残留**。清理方式（平台视角）：
> ```bash
> # 清掉所有 App 的编译产物（会导致下次启动变慢，慎用）
> adb shell cmd package compile --reset -a
> # 只清某个包
> adb shell cmd package compile --reset <pkg>
> ```

---

## 七、形态四：执行链（解释器到优化编译）

这一段是"代码真正跑起来之后"的故事。Android 7 之后，**同一份代码在一个进程的生命周期里会被四级引擎先后执行**。

### 7.1 四级执行引擎

```
① 解释器（Interpreter）
        │ 方法被调用次数到达阈值（dalvik.vm.jitthreshold）
        ▼
② 基线 JIT（Baseline JIT）—— 快速编译，无优化，只去掉解释开销
        │ 继续收集 profile（热点、类型、内联候选）
        ▼
③ Profile 落盘 → 后台 dex2oat
        ▼
④ 优化编译（Optimizing Compiler）产物 .oat —— 有内联、有寄存器分配、有寄存器化
```

| 层 | 触发 | 产物 | 特点 |
|---|---|---|---|
| 解释器 | 代码刚加载 | 无 | 慢，但零启动成本；带 inline cache 加速虚调用 |
| 基线 JIT | 方法调用达到阈值 | 内存中的机器码（**JIT code cache**） | 快得多，但无优化 |
| 优化编译（AOT） | 后台 dex2oat 按 profile | `.oat` 文件 | 全优化：内联、常量传播、去虚拟化、寄存器分配 |
| 优化编译（JIT） | 极端热的循环/方法（`--jit-on-first-use` 等） | JIT code cache | 少见，主要用于 profile 引导 |

**关键参数**（运行时可通过 `dalvik.vm.*` 或 `-Xjit...` 调整）：

| 属性 | 默认（量级） | 作用 |
|---|---|---|
| `dalvik.vm.usejit` | `true` | JIT 总开关 |
| `dalvik.vm.jitthreshold` | 约 20000 | 方法调用多少次后编译 |
| `dalvik.vm.jitinitialsize` / `jitmaxsize` | 几十 KB / 几十 MB | JIT code cache 的初值与上限 |
| `dalvik.vm.profilesavermininterval` | 约 20000 ms | profile 落盘最小间隔 |
| `dalvik.vm.profilebootclasspath` | `false` | 是否给 bootclasspath 收 profile |

> **JIT code cache 满了会怎样**：ART 会触发一次 GC 尝试回收"已充分 profiling 的方法"的 JIT 代码（`full GC` + `jit code cache GC`）。如果你在日志里看到 `Jit code cache full` 之类，说明有大量极端热点方法被反复编译/丢弃，通常意味着代码里有**上万次调用的极小方法**（批量化改造能解决）。

### 7.2 解释器里的 inline cache：虚调用怎么变快

Java 的虚方法调用（`invoke-virtual`）理论上要查虚表。ART 的解释器在每个调用点维护一个 **inline cache**（记录"这个调用点上次实际是什么类"），命中就直接跳——这让"单一接收者"的调用（绝大多数真实代码）几乎和多态无关。

**这解释了一件很实际的事**：**为什么"接口只有一个实现"这种代码在 Android 上不会因为虚调用而慢**。反过来，如果一个调用点有几十个不同实现交替出现（典型如超大继承体系里的 `onDraw`），inline cache 就会频繁 miss，性能掉下来——**这也是"有些代码结构天然更快"的一个真实原因**。

### 7.3 优化编译到底做了什么

`oatdump` 能把优化后的机器码打出来（第十五节）。优化编译器的典型变换：

| 变换 | 效果 |
|---|---|
| **方法内联**（inline） | 消除调用开销；是性能提升的最大来源 |
| **去虚拟化**（devirtualization） | 单一实现的虚调用变成直接调用，然后可内联 |
| 常量传播 / 死代码消除 | 从 profile 与静态分析得出 |
| 寄存器分配 | 把 DEX 的虚拟寄存器映射到真实寄存器 |
| 数组边界检查消除 | 循环里 `for (i = 0; i < a.length; i++)` 可省掉部分检查（部分场景） |
| 异常与锁优化 | 无异常的 try/catch 零开销；无竞争的 synchronized 用 fast path |

**对内联的取舍**（影响你写代码的方式）：

```kotlin
// ✅ 有利于内联：小方法、final/private/static、单一实现
private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v

// ❌ 不利于内联：大方法体（超过内联预算）
// ❌ 不利于去虚拟化：接口有多个实现（无法证明单一）
```

> **别过度优化**：ART 的编译器已经很聪明，**先测量（第十六节），再改**。常见的真实收益来自"减少工作次数"而不是"让方法更快"。

### 7.4 一条完整的执行时间线（把前面串起来）

以"用户第一次打开新装的 App"为例：

```
t=0     安装完成 → status=verify（只有验证，没编译）
t=0     用户点图标
t=0.1s  zygote fork 出进程（9 节），boot image 映射进来（0 拷贝）
t=0.2s  应用类从 APK 里的 dex 加载 → 验证（复用 vdex 里的验证结果，若已有）
t=0.3s  启动路径的解释执行 → 热点方法被基线 JIT 编译
t=1.5s  界面出现（比优化过的慢，因为大量方法还在解释执行）
t=2s~   前端/后台持续使用，profile 在内存里累积
t=20s+  ProfileSaver 把 profile 落盘到 /data/misc/profiles/cur/0/<pkg>/primary.prof
t=次日   设备空闲 + 充电 → BackgroundDexOptService 跑 dex2oat -m speed-profile
        → 产物写入 base.oat，profile 从 cur 复制到 ref
t=再启动 status=speed-profile，启动快 20%~40%
```

**这条时间线是理解所有"App 性能变化"的总纲**：性能不是常数，它随"被使用了多久 + 设备是否空闲过"变化。

---

## 八、Profile 与 dexopt 策略：性能为什么时好时坏

这一节可能是全文最"实用"的——**大部分"App 在用户那里变慢/变快"的疑问，答案都在这里。**

### 8.1 两套目录，两种 profile

```
/data/misc/profiles/cur/<userId>/<pkg>/primary.prof     ← 运行时累积的"当前" profile（可能还没被用来编译）
/data/misc/profiles/ref/<pkg>/primary.prof              ← 上一次编译时用的"参考" profile
```

| | `cur` | `ref` |
|---|---|---|
| 谁写 | ART 的 `ProfileSaver`（JIT 期间累积） | PMS 在 dexopt 成功后从 cur 复制过来 |
| 按用户分？ | **是**（`cur/0/`、`cur/10/`） | 否（全局一份） |
| 作用 | 下一次 dexopt 的输入 | 记录"上次是用什么 profile 编的"，用于决定要不要重编 |

```bash
# 看 profile 是否存在、多大、多久没更新（排查"为什么还没优化"的第一条命令）
adb shell "ls -l /data/misc/profiles/cur/0/<pkg>/"
adb shell "ls -l /data/misc/profiles/ref/<pkg>/"

# 把 profile 转成可读文本（profman 在 APEX 里）
adb shell /apex/com.android.art/bin/profman --dump-only \
    --profile-file=/data/misc/profiles/cur/0/<pkg>/primary.prof
# 输出大致是：
#   [0] class com.example.MainActivity
#   [1] method com.example.MainActivity onCreate()V
#   [2] method com.example.Foo parse(I)I
```

> 多用户（车机常见）：**profile 按用户分，编译产物不按用户分**。所以"切到另一个用户后 App 变慢"是正常的（新用户没有自己的 profile），但"编译产物"是共享的。这个细节能解释不少车机上的怪异现象。

### 8.2 谁在什么时候调用 dex2oat

| 触发者 | 时机 | 用的 filter | 属性控制 |
|---|---|---|---|
| **安装/更新** | `pm install` 时 | `pm.dexopt.install`（默认常为 `verify`，避免安装慢） | `pm.dexopt.install` / `pm.dexopt.install-fast` |
| **首次开机** | 系统首次启动 | `pm.dexopt.first-boot`（AOSP 默认常为 `verify`） | `pm.dexopt.first-boot` |
| **OTA 之后** | 升级完首次启动 | `pm.dexopt.boot-after-ota` | `pm.dexopt.boot-after-ota` |
| **开机后** | 启动完成后台 | `pm.dexopt.post-boot` | `pm.dexopt.post-boot` |
| **后台空闲编译** | 设备**空闲 + 充电**时（JobScheduler 的 `BackgroundDexOptJob`，job id 800） | `pm.dexopt.bg-dexopt`（默认 `speed-profile`） | `pm.dexopt.bg-dexopt` / `pm.dexopt.disable_bg_dexopt` |
| **手动** | 开发者/用户触发 | 命令行指定 | — |

```bash
# 看这些属性的实际值（每台设备/ROM 都可能不同，这是定制时的第一站）
adb shell getprop | grep -E "^\[pm\.dexopt"
#   [pm.dexopt.bg-dexopt]: [speed-profile]
#   [pm.dexopt.first-boot]: [verify]
#   [pm.dexopt.install]: [speed-profile]
#   ...

# 立刻跑一次后台 dexopt（不用等空闲充电）
adb shell cmd package bg-dexopt-job

# 关掉后台编译（省电/省寿命，代价是 App 一直慢）
adb shell setprop pm.dexopt.disable_bg_dexopt true
```

### 8.3 为什么"后台优化"很重要，以及它为什么常常没发生

`bg-dexopt` 的条件是 **设备空闲 + 充电 + 电量足够**（由 JobScheduler 判定；具体条件随版本有差异）。现实中的结果：

| 场景 | 结果 |
|---|---|
| 用户白天正常用、晚上充电 | ✅ 通常能在几小时内完成优化 |
| 用户从不让设备空闲（车机长时间导航） | ⚠️ 优化可能长期不触发 |
| 车机（插着电、常亮、从不等空闲） | ⚠️ **强烈建议平台侧配置策略**（见第十七节） |
| 云手机/模拟器 | ⚠️ 通常不会触发，性能表现比真机差 |

**这解释了一个常见现象**：同一个 APK，在你的开发机上（经常充电+空闲）很快，在用户某些设备上一直偏慢。

**App 侧的应对**：
1. **打 Baseline Profile**（6.4）——不依赖设备空闲，安装后立刻生效。
2. **把重活从启动路径挪走**（懒加载），让"没优化"的代价变小。
3. 用 **Startup Profile / DexLayout** 之类的工具（AGP 8+ 的 `baselineprofile` 插件会同时处理）。

### 8.4 Cloud Profiles（云 profile）

Android 10 起引入：从 Google Play 收集"其他用户设备上的 profile"，在安装时直接下发，让新装 App 也能立刻按 profile 编译（相当于"别人帮你热身"）。

- 属性/开关：`dalvik.vm.dex2oat` 侧有 cloud profile 相关参数；PMS 侧用 `PackageDexOptimizer` 的 cloud profile 路径。
- 只有 Play 分发的 App 能享受；**车机/内网分发的 App 没有这条捷径 → 更要靠自己打 Baseline Profile。**

### 8.5 App 侧可用的"加速编译"手段清单

| 手段 | 适用 | 效果 |
|---|---|---|
| **Baseline Profile** | 所有 App（AGP 8+） | ★ 启动快 20%~40%，不依赖设备空闲 |
| 减少启动路径上的类/方法（懒加载） | 所有 App | 直接降低"未优化"的代价 |
| Base Dex 优化（R8 控制主 dex 内容） | 多 dex 项目 | 减少启动时加载的 dex 数量 |
| `android:extractNativeLibs` / so 加载方式 | 有 NDK 的 App | 见 NDK 篇 11.4（影响启动） |
| 主动引导用户"首次启动后重启" | 不推荐 | 体验差，别做 |
| 平台侧强制 `speed` 编译 | **平台视角** | 预装 App 可以；第三方 App 不现实 |

---

## 九、启动映像与 Zygote：所有 App 共享的那份内存

### 9.1 启动链

```
init
 └─ 解析 init.rc：service zygote /system/bin/app_process64 ...
     └─ app_process64（C++ 程序，frameworks/base/cmds/app_process/app_main.cpp）
         └─ AndroidRuntime::start()
             ├─ JNI_CreateJavaVM()  ★ 在这里创建 ART 虚拟机
             ├─ 加载 boot image（boot.art / boot.oat / boot.vdex）
             ├─ 反射调用 com.android.internal.os.ZygoteInit.main()
             │    ├─ preload()：预加载常用类与资源（preloaded-classes）
             │    ├─ forkSystemServer() → SystemServer（fork 出系统服务进程）
             │    └─ runSelectLoop()：等 socket 请求 → fork 出 App 进程
             └─ 之后每个 App 进程都是 zygote 的 fork
```

**理解要点**：**虚拟机不是"每个 App 各建一个"，而是 zygote 建一次、fork 出所有 App 进程共用同一套已初始化的运行时状态。** 这就是 Android 启动快的根本原因。

### 9.2 boot image 里有什么

| 内容 | 说明 |
|---|---|
| **预编译的核心库代码** | `core-oj`（`java.lang`/`java.util`…）、`core-libart`、`okhttp`、`bouncycastle`、`apache-xml` 等 bootclasspath 上的类 |
| **堆镜像（image space）** | 这些类里**静态字段与常用对象的预初始化结果**（如 `Integer.CACHE`、`Charset` 表、`System.out`）。App 进程 fork 后**直接共享**，不需要重新初始化 |
| **`oat` / `vdex`** | 机器码与验证信息，App 进程 mmap 进来即可用 |

```bash
# 看 boot image 的大小与位置
adb shell "ls -l /system/framework/arm64/ | grep -E 'boot\.(art|oat|vdex)'"
adb shell "du -sh /system/framework/*/boot.*"

# boot image 里到底有哪些类（用 oatdump，见第十五节）
adb shell /apex/com.android.art/bin/oatdump --image=/system/framework/arm64/boot.art \
    --output=/data/local/tmp/boot.txt
```

### 9.3 fork 与 COW：为什么 App 进程的内存这么"便宜"

```
            zygote 进程（boot image 里的几万个对象 + 已初始化的类）
                │  fork()
      ┌─────────┼─────────┐
      ▼         ▼         ▼
   App A     App B     App C
   （只把"自己改过的页"复制一份 —— Copy-On-Write）
```

- 所有 App 进程**共享** boot image 的物理内存页（只读，不会复制）。
- 只有被写过的页才会真正复制（COW）。
- **后果**：`dumpsys meminfo` 里 App 的 "Code" / "Shared" 部分是共享的，看"PSS"（proportional set size）才是相对公平的占用指标。

```bash
# 看共享情况（PSS 才是跨进程可加的值）
adb shell dumpsys meminfo com.example.app | sed -n '/App Summary/,/TOTAL/p'
#                Pss  Private  Swapped     Heap     Heap     Heap
#              Total    Dirty  Private      Pss    Dirty    Swap
#   Native Heap   ...
#   Dalvik Heap   ...      ← 就是 Java 堆
#   .so mmap      ...
#   .oat mmap     ...      ← AOT 机器码（共享）
#   .art mmap     ...      ← boot image 的堆镜像（共享）
```

**`dumpsys meminfo` 里 `Dalvik Heap` 与 `.art mmap` 的区别**是很多人搞混的点：**`.art mmap` 是"启动映像带来的共享内存"，`Dalvik Heap` 才是"你的 App 自己分配的对象"。** 分析内存时不要把它们加起来当作"我的内存"。

### 9.4 App image（`.art`）：给单个 App 做的"小 boot image"

思路是把 App 启动用到的类也预初始化成一个堆镜像，让启动更快。产物是 `/data/app/.../oat/<abi>/base.art`。

| 版本 | 默认策略 |
|---|---|
| Android 8 – 9 | 生成（配合 `speed`/`speed-profile`） |
| Android 10+ | **默认不再生成**（收益不抵空间与维护成本）；**Baseline Profile 取代了它的角色** |

所以：**看到 `base.art` 存在说明这台设备/这个版本还在生成它；看不到是正常的。** 想让 App 启动快，现在的正路是 Baseline Profile（6.4）。

### 9.5 预加载（preload）：zygote 起来时做的热身

`ZygoteInit.preload()` 会做三件事：

1. **预加载类**（`preloaded-classes` 列表，由 AOSP 的 `WritePreloadedClassFile` 基于 profile 生成）；
2. **预加载资源**（`Resources` 里常用的 drawable/color，避免每个 App 首次取资源时 IO）；
3. **预加载文本/时区等**（`ICU`、时区数据）。

```bash
# 设备上实际的预加载类列表（不同 ROM 可能不同）
adb shell "ls -l /system/etc/preloaded-classes"
adb shell "wc -l /system/etc/preloaded-classes"
adb shell "head -30 /system/etc/preloaded-classes"
```

**平台视角的取舍**：预加载越多 → App 启动越快，但 **zygote 启动变慢、boot image 变大、常驻内存增加**。这是"开机时间 vs 应用启动速度"的经典权衡，车机上很值得实测。

---

## 十、形态五：堆的布局

代码最终变成堆里的一堆对象，而堆是虚拟机自己划的。**看懂堆的空间划分，GC 的行为就全是推论。**

### 10.1 ART 堆的空间划分

```
Java 堆（虚拟的"一个堆"，实际由多个 space 组成）
├── ImageSpace         ← boot image 的堆镜像（第九节）
│                        内容：核心类 + 预初始化对象；位置固定（不移动），只读
│                        大小：跟 boot image 一起，通常几十 MB
├── ZygoteSpace        ← fork 之后"共享、只读"的分配区
│                        内容：zygote 在 fork 前最后阶段的分配；几乎不回收
├── NonMovingSpace     ← 不可移动空间
│                        内容：类对象（Class）、JNI 全局引用、部分 native 侧引用的对象
│                        为什么需要：native 代码持有裸指针，不能移动（与 NDK 篇 10 节呼应）
├── MainSpace (RegionSpace)  ★ 主分配区
│                        内容：App 运行时分配的对象（绝大多数）
│                        形态：切成一块块 region（默认 256 KB），可移动（复制式回收）
├── LOS (Large Object Space)  ← 大对象空间
│                        内容：超过阈值（约 12 KB）的对象（大数组、大 Bitmap 的像素缓冲）
│                        特点：不参与复制（太大，拷贝代价高），用 mark-sweep 管理
└── （JIT code cache 不在 Java 堆里，是可执行内存，另一套系统）
```

**这张图能直接回答三个高频问题**：

| 疑问 | 答案 |
|---|---|
| 为什么 native 侧能持有 Java 对象的裸指针？ | 那些对象在 **NonMovingSpace**（或 ImageSpace），不会被搬动 |
| 为什么大数组的 GC 行为和普通对象不同？ | 它在 **LOS**，不参与复制，回收靠 mark-sweep 且更"迟钝" |
| 为什么 `Bitmap` 的内存有时不涨 Java 堆？ | Android 8 起像素数据在 **native 堆**（NDK 篇 10 节），只有 `Bitmap` 对象本身在 Java 堆 |

### 10.2 两个分配器

| 分配器 | 用在哪 | 机制 |
|---|---|---|
| **RosAlloc**（Runs of Slots） | MainSpace / NonMovingSpace 的小对象 | 按 size bucket 分 slot，类似 tcmalloc；每线程有本地缓冲（免锁） |
| **LOS 分配器** | 大对象 | 单独的 free-list / mark-sweep |

**线程局部缓冲（TLAB 类似物）**：小对象分配几乎不需要锁——每个线程从自己的 run 里切，这解释了"为什么 Java 分配对象很快"。**分配快 ≠ 回收免费**：真正贵的是 GC。

### 10.3 堆的尺寸由谁决定

| 参数（property） | 作用 | 默认（量级） |
|---|---|---|
| `dalvik.vm.heapstartsize` | 初始堆大小 | 几 MB |
| `dalvik.vm.heapgrowthlimit` | **普通 App 的上限** | 128 MB ~ 256 MB（随设备 RAM 档位） |
| `dalvik.vm.heapsize` | `largeHeap=true` 的上限 | 512 MB 左右 |
| `dalvik.vm.heaptargetutilization` | GC 后的目标占用比 | 0.75 |
| `dalvik.vm.heapminfree` / `heapmaxfree` | GC 后保留的空闲上下限 | 512 KB / 8 MB |

```bash
adb shell getprop | grep -E "dalvik.vm.heap"
#   [dalvik.vm.heapgrowthlimit]: [192m]
#   [dalvik.vm.heapsize]: [512m]

# App 侧读到的是这个（就是上面属性的解释）
# ActivityManager.getMemoryClass()        → heapgrowthlimit 换算成 MB
# ActivityManager.getLargeMemoryClass()   → heapsize 换算成 MB
```

```kotlin
// App 侧：搞清楚自己有多少预算
val am = getSystemService(ActivityManager::class.java)
Log.i("mem", "normal=${am.memoryClass}MB large=${am.largeMemoryClass}MB " +
             "lowRam=${am.isLowRamDevice}")
```

> **一个真实取舍**：调大 `heapgrowthlimit` 能让 App 少 OOM，但**会让整个系统的内存压力变大**（更多进程触发 LMK 被杀）。**车机上"多屏+多 App 并行"时尤其要克制**——单进程给太多，等于让其它进程更容易被系统杀掉。这是第十七节会谈的定制权衡。

### 10.4 定位"谁在吃 Java 堆"

```bash
# ① 概览：Dalvik Heap 的 PSS 与 Objects 分类
adb shell dumpsys meminfo com.example.app

# ② 抓 Java 堆快照（hprof），用 Android Studio 的 Memory Profiler 打开
adb shell am dumpheap com.example.app /data/local/tmp/app.hprof
adb pull /data/local/tmp/app.hprof
#   Android Studio → Profiler → Memory → Load hprof
#   重点看：谁持有最多的对象、GC Root 到它的引用路径（这就是泄漏链）

# ③ 强制一次 GC 再看（对比"应该释放的有没有释放"）
adb shell am dumpheap -n com.example.app /data/local/tmp/app.hprof   # -n = 先 GC 再 dump

# ④ 实时分配追踪（Perfetto 的 Java heap profiler）
#    perfetto UI → Record → Java heap dump / Heap profiler
```

**读 hprof 的三个套路**：

1. **先看"Retained Size"排序**，不是"Allocations" —— 一个对象被很多地方引用时，它才是贵的那块。
2. **找 GC Root 链**：从可疑大对象往上追到 Root（静态字段、线程栈、JNI 全局引用）。**泄漏的本质永远是"有一条不该存在的强引用链"。**
3. **`Bitmap`、`byte[]`、`String`、`HashMap$Node[]` 是四大常客**，先查这四个。

---

## 十一、垃圾回收：从 mark-sweep 到并发复制到分代

### 11.1 三代 GC 的演进（每一代解决上一代的什么问题）

```
① 标记-清除（Mark-Sweep，Dalvik / ART 早期）
   做法：从 GC Root 遍历标记存活对象 → 清除未标记的
   缺陷：全程 STW（应用完全停顿）；内存碎片化
        Dalvik 上表现为 "GC_FOR_ALLOC freed ..." 频繁出现 + 明显卡顿（几百 ms）

② 并发标记清除（CMS，Concurrent Mark Sweep，ART 默认过一段时间）
   做法：标记阶段与应用线程并发；只有初始标记/重标记短暂停顿
   缺陷：不整理内存 → 碎片；碎片严重时退化为 STW 的 mark-compact
       日志：Concurrent mark sweep GC ...

③ 并发复制（CC，Concurrent Copying，Android 8 起默认）★ 当前主流
   做法：把存活对象从"来源 region"复制到"目标 region"，然后整块回收来源 region
        复制与应用线程并发进行（用 read barrier 保证"看到的一定是复制后的新地址"）
   优点：暂停时间进微秒级；天然没有碎片（复制即整理）
   代价：需要额外一份空间（区域级，不是整个堆）；每次访问对象要走 read barrier（有 CPU 成本）

④ 分代并发复制（Generational CC，较新版本引入）
   做法：区分"新对象"和"老对象"，多数对象朝生夕死 → 只扫新区域就回收掉大部分垃圾
   优点：进一步减少 GC 工作量与内存扫描范围
   注意：默认开启情况随 ART 版本变化，以自己平台为准
```

**读日志时能对号入座**：

| 日志里的字眼 | 是哪一代 |
|---|---|
| `concurrent mark sweep GC` | CMS |
| `concurrent copying GC` | CC（当前） |
| `mark sweep GC` / `mark compact GC` | 老式 STW 的（通常只在特殊场景，如 `native` 内存压力） |
| `Background ... GC` | 后台 GC（GC 发生在后台线程，暂停更短） |

### 11.2 一次 CC GC 的四个阶段

```
① 初始暂停（Initial Pause）—— 很短
     · 把 GC Root 标出来，确定"要复制的起点"
     · 冻结部分线程状态

② 并发标记 + 并发复制（Concurrent Mark & Copy）—— 主要工作量在这里
     · 应用线程继续跑，GC 线程同时在复制存活对象
     · read barrier 保证：应用线程永远看到"最新的对象位置"（对象被搬了也不知道）

③ 重标记暂停（Remark Pause）—— 短
     · 处理并发期间"新产生的引用"，确定最终存活集

④ 并发清理 + 回收 region
     · 清空已复制走的 region，归还给分配器
```

**"paused xxx total yyy"就是这一段的关键数字**：`paused` 是应用被真正停顿的总时长（要尽量小），`total` 是这次 GC 从开始到结束的墙钟时长（可以很长，因为大部分是并发的）。

### 11.3 GC 触发原因（cause）

这是排查"GC 为什么这么频繁"的核心线索。日志或 `-Xlog:gc` 里会带 cause：

| cause | 含义 | 说明 |
|---|---|---|
| `GC_FOR_ALLOC` | 分配失败，堆不够了 | **最常见的"分配太快"信号** |
| `GC_HEAP_GROWTH` / 堆增长触发 | 堆占用超过增长阈值 | 正常节奏 |
| `GC_FOR_BACKGROUND` / `Background` | 后台 GC（GC 线程在后台跑） | 良性，暂停短 |
| `GC_FOR_NATIVE_ALLOC` | **native 内存压力触发** | 与 NDK 篇 10.4 的 `NativeAllocationRegistry` 呼应 |
| `GC_FOR_EXPLICIT` | `System.gc()` 被调用 | **应避免**（见下） |
| `GC_FOR_HEAP_TRIM` | 系统要求释放内存（如切后台） | 系统行为，正常 |

**关于 `System.gc()`**：

```kotlin
// ❌ 不要这么写
fun clearCache() {
    cache.clear()
    System.gc()          // 只是"建议"，且强制一次完整 GC（暂停可能几十 ms），还会禁用某些优化
}

// ✅ 正确做法：断开引用，把回收时机交给 GC
fun clearCache() {
    cache.clear()        // 引用断了，GC 自然会处理
}
```

**例外**：在**内存测量**场景（如你想知道"清空后真实占用是多少"），主动 GC 是合理的——但那是诊断行为，不是业务逻辑。

### 11.4 GC 与掉帧的关系

GC 的暂停会直接占用主线程的时间，落在帧间隔里就是掉帧。三类典型场景：

| 场景 | 现象 | 对策 |
|---|---|---|
| **在 `onDraw`/`onBindViewHolder` 里分配对象** | 每个列表项都制造垃圾 → 频繁小 GC → 滑动卡顿 | **把对象移出循环/复用**（`Pools`、预分配数组） |
| **一次性分配大量对象**（解析大 JSON、深拷贝） | 一次 CLOSE 的停顿，明显卡一下 | 分片处理、用流式解析、放到子线程 |
| **自动装箱 / 临时字符串拼接** | 隐性产生大量小对象 | 避免 `Map<Int, X>`（用 `SparseArray`）、避免循环里字符串拼接 |

```kotlin
// ❌ 三个 GC 压力源：装箱、迭代器对象、临时字符串
val map = HashMap<Int, Foo>()                      // Int 装箱成 Integer，每个 key 一个对象
for (i in 0 until n) { map[i] = Foo() }
val s = "result: " + a + ", " + b + ", " + c       // 编译期可能优化，但运行时拼接会造对象

// ✅ 换成原始类型容器 / 明确格式化
val map = SparseArray<Foo>()                       // 不装箱
val s = buildString { append("result: "); append(a); /* ... */ }   // 单次分配
```

**扫描 GC 热点的方法**：Perfetto 里打开 `art` 的 GC 事件轨，看 GC 触发频率与暂停位置；或用 Android Studio Memory Profiler 的 "Allocation Tracking"，直接定位"谁在频繁分配"。

### 11.5 GC 相关的可调参数（平台视角，少量）

| 参数 | 作用 |
|---|---|
| `dalvik.vm.gctype` / `-Xgc:` | 选择 GC 实现（不同版本支持度不同，改动风险高） |
| `dalvik.vm.heapminfree` / `heapmaxfree` | 控制 GC 后保留的空闲，**调大能减少 GC 频率但增加内存占用** |
| `dalvik.vm.heaptargetutilization` | 目标占用比，调低会更早 GC、更省内存 |
| `dalvik.vm.heapgrowthlimit` | 单进程上限（10.3） |
| 较新版本的 GC 相关 flag | 如用户态缺页处理、分代 GC 开关等，**名字随 ART 版本变化，改前必查自己版本的默认值** |

> **给车机的建议**：不要为了"App 少 OOM"就猛调 `heapgrowthlimit`。**先测整机内存压力下的进程存活率**（`dumpsys activity processes` 里的 `adj`），再决定。

---

## 十二、逐字段读 GC 日志

### 12.1 那行日志的每个字段

```
I/art: Background concurrent copying GC freed 12345(678KB) AllocSpace objects,
       3(120KB) LOS objects, 42% free, 12MB/20MB, paused 123us total 1.234s
       │          │                │        │        │         │       │      │        │
       │          │                │        │        │         │       │      │        └─ 本次 GC 的墙钟总时长
       │          │                │        │        │         │       │      └─ ★ 应用被真正停顿的总时间
       │          │                │        │        │         │       └─ 当前堆占用 / 堆总量
       │          │                │        │        │         └─ GC 之后的空闲比例
       │          │                │        │        └─ 回收的大对象数量(字节)
       │          │                │        └─ 回收的普通对象数量(字节)  ← 注意这是"数量(大小)"
       │          │                └─ AllocSpace = MainSpace 里的对象
       │          └─ 本次 GC 的名字（收集器类型）
       └─ Background = 在后台（GC 线程）完成的，不是前台抢占式的
```

**怎么用这几个数字下判断**：

| 观察 | 推断 | 下一步 |
|---|---|---|
| `paused` 只有几十~几百微秒 | 正常，CC GC 的表现 | 不用管 |
| `paused` 达到**几毫秒以上** | 异常（可能是 LOS 过多、堆碎片、或 GC 被抢占） | 看 LOS objects 是否很大 |
| `total` 很长（几百 ms ~ 秒级） | 并发 GC 期间 CPU 被抢，或者堆很大 | 看是否 CPU 满、是否有大量大对象 |
| `42% free` 长期很低（< 15%） | 堆很紧张，GC 会更频繁 | 查内存泄漏或用 hprof 看大头 |
| 短时间出现**很多条** GC 日志 | 分配速率过高 | 用 Allocation Tracking 找分配源 |
| `freed 0B` | GC 没回收掉东西 | 可能是内存泄漏（或只是刚 GC 过） |

### 12.2 打开更详细的 GC 日志

```bash
# Android 14 之前：-verbose:gc（写到 logcat 的 art tag）
# Android 14 起：推荐用 -Xlog（统一的日志开关）

# 方式一：给单个 App 开（需要可调试 App 或 root；setprop 后重启 App）
adb shell setprop dalvik.vm.extra-opts "-verbose:gc"
adb shell am force-stop com.example.app     # 重启 App 生效

# 方式二：命令行启动（am start 带 --runtime-args 不方便，更常用的是上面那种）
# 方式三：平台侧全局开（debug 版 ROM）—— 改 dalvik.vm.extra-opts 属性

# 更细的 ART 日志（新版本）：
adb shell setprop dalvik.vm.extra-opts "-Xlog:gc*=info"
adb shell setprop dalvik.vm.extra-opts "-Xlog:gc+heap=debug"

# 看 ART 的运行时错误/告警（与 GC 无关但同一个 tag，值得一起看）
adb logcat -s art:* | head -50
```

**日志里还常见这几类（要能区分）**：

| 日志片段 | 含义 |
|---|---|
| `JIT code cache full` / `Jit code cache ...` | JIT 缓存满，触发了一次 GC 尝试回收代码（7.1） |
| `WaitForGcToComplete blocked ...` | 线程在等 GC 结束被阻塞了（这段时间会计入"卡"） |
| `Long monitor contention` | 锁竞争，**不是 GC，但常与 GC 一起出现**（排查卡顿时别搞混） |
| `GC did not free ...` | 见上表 |
| `Failed to allocate a ...` / `OutOfMemoryError` | 堆真的不够了，看 `dumpsys meminfo` |

```bash
# 一次抓全：GC + 卡顿 + JIT 一起看（排查性能问题时的组合拳）
adb logcat -v threadtime | grep -E "art|dalvik|Choreographer|ActivityManager" | head -100
```

### 12.3 Perfetto 里的 GC

```bash
# 抓包含 ART 事件的 trace（GC/编译/线程调度一起）
adb shell perfetto -o /data/misc/perfetto-traces/gc.pftrace -t 15s -c - <<< '
buffers { size_kb: 32768 }
data_sources { config { name: "linux.ftrace" ftrace_config {
  ftrace_events: ["sched/sched_switch", "freq/cpu_freq"]
  atrace_categories: ["dalvik", "art", "sched", "freq", "view"]
  atrace_apps: ["com.example.app"]
} } }
'
adb pull /data/misc/perfetto-traces/gc.pftrace
# 打开 ui.perfetto.dev → 看 art 轨上的 GC 事件是否落在掉帧区间
```

**Perfetto 比日志强的两点**：① 能看到 GC 与主线程帧的时间对齐关系（"这次卡顿到底是 GC 还是别的"）；② 能看到 GC 各阶段的时间分布。

---

## 十三、引用、finalizer、Cleaner 与泄漏

### 13.1 四种引用在 ART 里怎么被处理

| 引用类型 | 回收时机 | 典型用途 | ART 上的注意点 |
|---|---|---|---|
| **强引用**（默认） | 只要可达就不回收 | 普通对象 | — |
| **软引用** `SoftReference` | 内存紧张时清除 | 缓存 | ⚠️ **Android 官方不建议用它做缓存**（清除时机不确定、行为与 JVM 不同）→ **用 `LruCache`** |
| **弱引用** `WeakReference` | **下一次 GC 就清**（只要没有强引用） | 缓存 key、观察者、LeakCanary 的核心 | 清完还要用就重新获取（注意竞态） |
| **虚引用** `PhantomReference` | 对象已被回收后入队 | 替代 `finalize`，做资源清理 | 必须配合 `ReferenceQueue`；`get()` 永远返回 null |

```kotlin
// 软引用的经典误用（Android 上不要这么写）
val cache = HashMap<String, SoftReference<Bitmap>>()   // ❌ 清除时机不可控，容易被清空

// ✅ 用有明确淘汰策略的缓存
val cache = object : LruCache<String, Bitmap>(maxSizeBytes) {
    override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
}

// ✅ 弱引用的正确用法：需要"不阻止回收"的引用（注意用完判活）
class ListenerProxy(target: Listener) {
    private val ref = WeakReference(target)
    fun onEvent() = ref.get()?.onEvent()
}
```

### 13.2 `finalize()`：Android 上最该避开的 API

**为什么 finalize 危险**（这些是 ART 上可观测的真实现象）：

1. **对象至少要经历两次 GC 才会被真正回收**：第一次把对象放进 `FinalizerReference` 队列，`FinalizerDaemon` 线程执行 `finalize()`，第二次 GC 才回收对象本身。
2. **`finalize()` 在 `FinalizerDaemon` 单线程执行**：一个慢 `finalize()` 会**阻塞所有其他对象的 finalize**，队列堆积 → 内存涨。
3. **可能触发致命错误**：`FinalizerWatchdogDaemon` 会监控单个 `finalize()` 是否超时（默认约 10 秒），超时后**直接抛错并终止进程**：

```
E/art: Timeout executing finalizer in ...
F/art: Finalizer timed out! ...
```
或者
```
java.lang.RuntimeException: Timeout executing finalizer
```

4. **`finalize()` 里抛异常会被吞掉**（不会传到调用方），问题更难发现。
5. Android 9 起对 `finalize()` 的使用有警告/严格模式检测；**新代码一律不要用**。

```kotlin
// ❌ 千万别这么写
class MyResource {
    private val fd = nativeOpen()
    override fun finalize() { nativeClose(fd) }    // 不确定何时执行，还可能导致进程被杀
}

// ✅ 正确形态：AutoCloseable + try-with-resources（Kotlin 的 use）
class MyResource : AutoCloseable {
    private var fd = nativeOpen()
    override fun close() {
        if (fd != 0) { nativeClose(fd); fd = 0 }    // 幂等
    }
}
fun useIt() {
    MyResource().use { r -> /* ... */ }              // 异常路径也会 close
}
```

### 13.3 Cleaner / PhantomReference：finalize 的现代替代

| 方案 | 可用版本 | 特点 |
|---|---|---|
| `java.lang.ref.Cleaner` | **Android 13（API 33）起** | 官方替代方案；实现上基于 `PhantomReference` |
| `PhantomReference` + `ReferenceQueue` | 所有版本 | 最底层、最可控，但要自己写线程 |
| **显式 `close()` / `AutoCloseable`** | 所有版本 | **首选**：时机可控、可测试、可幂等 |

```kotlin
// Android 13+ 的 Cleaner 用法（注意：不要捕获 this，否则永远回收不掉）
class NativeBuffer(size: Int) : AutoCloseable {
    private val size = size
    private val handle: Long = nativeAlloc(size)
    private var closed = false

    private val cleaner: Cleaner? =
        if (Build.VERSION.SDK_INT >= 33) {
            Cleaner.create().also { c ->
                // 只捕获"独立于 this"的状态，绝不捕获 this
                c.register(this, CleanupAction(handle))
            }
        } else null

    override fun close() {
        if (closed) return
        closed = true
        nativeFree(handle)      // 主路径：立即释放
        cleaner?.clean()        // 兜底路径：防止重复触发
    }

    // 清理动作单独成类，避免隐式持有外部实例
    private class CleanupAction(private val handle: Long) : Runnable {
        override fun run() = nativeFree(handle)
    }
}
```

> **Cleaner 的三条纪律**：① 不在清理动作里引用被清理对象；② 清理动作必须幂等（可能与 `close()` 竞争）；③ **`Cleaner` 线程的清理代码不能调用可能抛异常的代码**，否则清理静默失效。

### 13.4 泄漏检测：为什么弱引用能"看到"泄漏

LeakCanary / Instrumentation 的通用原理（值得理解，因为它解释了"为什么需要主动 GC"）：

```
① 用 WeakReference 包住可疑对象（如一个 Activity）
② 在 Activity.onDestroy 之后，把这个弱引用挂到 ReferenceQueue 上
③ 主动触发一次 GC（Debug.dumpHprofData 前会做）—— 此时如果对象本该被回收
   ④ 若弱引用**没有**进入 ReferenceQueue（说明对象还被强引用链挂着）→ 泄漏
   ⑤ dump hprof，从该对象向上找 GC Root 的引用路径 → 指出"谁在持有它"
```

**所以"为什么泄漏检测工具要主动 GC"**：弱引用的清除发生在 GC 时，不 GC 就无法判断"本该回收的对象是否真的被回收"。

### 13.5 Android 上最常见的五类泄漏

| 泄漏 | 原因 | 症状 | 对策 |
|---|---|---|---|
| 静态集合当缓存 | `static val map = HashMap<...>()` 只增不减 | 内存持续上涨，GC 日志 `freed` 很小 | 用 `LruCache` / `WeakHashMap`，或加淘汰 |
| 非静态内部类 / Handler | 内部类隐式持有外部 `Activity` | 旋转屏幕后 Activity 泄漏 | `static` 内部类 + `WeakReference`；Handler 用主线程并移除消息 |
| 未注销的监听器 | `registerListener` 后忘了 `unregister` | 系统服务持有你的对象 | 生命周期配对注销（`onStart`/`onStop` 对称） |
| 单例持有 Context | `object Foo { val ctx = activity }` | 整个进程生命周期泄漏 | 只持 `applicationContext` |
| 线程/协程未取消 | 长跑线程持有 View/Activity 引用 | 退出页面后仍占内存 | 绑定生命周期（`lifecycleScope`）、`Thread` 加停止标志 |

> **与 NDK 篇的分工**：这一节讲 Java 堆里的泄漏；**native 内存泄漏**（`malloc` 漏 free、JNI 全局引用漏删）在 NDK 篇第十节和 6.2 节。**两者症状相似（进程内存涨）但排查工具完全不同**——先用 `dumpsys meminfo` 看涨的是 `Dalvik Heap` 还是 `Native Heap`，能省一半时间。

---

## 十四、类加载与 dex 动态加载

### 14.1 Android 的类加载器家族

```
ClassLoader
├── BootClassLoader          ← 加载 bootclasspath（boot image 里的核心库）
│                              由 ART 内部创建，parent 为 null
├── PathClassLoader          ← 加载"已安装的 APK"
│                              parent = BootClassLoader
│                              ★ App 的 Application 类就是用它加载的
├── DexClassLoader           ← 加载任意路径上的 dex / jar / apk（动态加载）
│                              parent 可指定
├── InMemoryDexClassLoader   ← API 26+，从 ByteBuffer 直接加载 dex（不落盘）
├── DelegateLastClassLoader  ← API 27+，双亲委派顺序反转（先自己，后 parent）
└── (URLClassLoader)         ← Android 上基本不可用（没有完整的 java.net 支持）
```

```kotlin
// 已安装 APK 的加载器（每个 App 一个，进程启动时就建好）
val appLoader = context.classLoader                       // PathClassLoader
Log.i("cl", "parent=${appLoader.parent}")                 // BootClassLoader@...
Log.i("cl", "paths=${(appLoader as BaseDexClassLoader).dexPath}")   // APK 路径

// 动态加载一个外部的 dex（注意 Android 10+ / 14+ 的限制，见 14.4）
val dexLoader = DexClassLoader(
    dexPath,                  // /data/.../plugin.dex 或 .jar/.apk 路径
    optimizedDirectory,       // 产物目录；Android 8+ 起可传 null（用系统默认）
    null,                     // native 库搜索路径
    context.classLoader       // parent
)
val cls = dexLoader.loadClass("com.plugin.Entry")
val instance = cls.getDeclaredConstructor().newInstance()
```

**关键实现类**（读源码时的入口）：`BaseDexClassLoader` → `DexPathList` → `DexFile`（native 侧对应 ART 的 `OatFile` / `DexFile`）。**"类找不到"的排查路径就在这条链上**：`dexPath` 对不对 → dex 里到底有没有这个类（`dexdump` 查）→ 类名大小写/包名 → 是否被 R8 删了。

### 14.2 双亲委派在 Android 上长什么样

```kotlin
// ClassLoader.loadClass 的流程（Android 与 JVM 一致）
// ① 已加载过 → 直接返回
// ② parent != null → parent.loadClass()（★ 双亲优先）
// ③ 还是没找到 → 自己的 findClass()
```

**Android 的实际效果**：App 的 `PathClassLoader` 的 parent 是 `BootClassLoader`，而 `BootClassLoader` 只能找到 bootclasspath 上的类。所以：

- 找 `android.app.Activity` → 由 `BootClassLoader` 提供（来自 boot image）
- 找 `com.example.MyActivity` → `BootClassLoader` 找不到 → 回到 `PathClassLoader` 从 APK 的 dex 里找

**这条规则解释了两个现象**：

| 现象 | 原因 |
|---|---|
| 插件里的类**无法覆盖**系统类 | 双亲优先：`android.*` 一定由 bootclasspath 提供 |
| 插件里定义了与宿主同名的类，用哪个？ | 看哪个 ClassLoader 先加载——**同名类在同一个 ClassLoader 里只会加载一次**（"类身份 = 类名 + ClassLoader"，这是插件化所有坑的根源） |

**类身份问题（插件化的核心难题）**：

```kotlin
// 同一个 .class 文件，被两个 ClassLoader 加载 → 两个不同的 Class 对象
val c1 = hostLoader.loadClass("com.x.Foo")
val c2 = pluginLoader.loadClass("com.x.Foo")
Log.i("cl", "c1 == c2 → ${c1 === c2}")     // false！
// 后果：c1 的实例不能赋给 c2 类型的变量 → ClassCastException，
//       即使两者的字节码完全一样。
```

### 14.3 类验证（verifier）：为什么改字节码会出诡异问题

DEX 在加载时（或 AOT 编译时）会被**验证**，确保不会破坏虚拟机安全：

| 验证层次 | 检查什么 |
|---|---|
| 结构验证 | DEX 格式是否合法、索引是否越界 |
| 类型验证 | 字节码里的类型是否匹配（如不能把 `int` 当对象用） |
| 方法验证 | 方法签名的多态性、`invoke` 的目标是否存在 |

| 验证结果 | 虚拟机行为 |
|---|---|
| 通过 | 可 AOT 编译为机器码 |
| **软失败（soft fail）** | 该方法**降级为解释执行**（不编译），运行正常但慢 |
| 硬失败 | 抛 `VerifyError` / `ClassNotFoundException` |

```bash
# 让设备上的 ART 严格验证（排查"改过字节码后的诡异行为"）
adb shell setprop dalvik.vm.dex2oat-filter verify
# 或者用 -Xverify:none 跳过验证（仅调试，绝不上线）
adb shell setprop dalvik.vm.extra-opts "-Xverify:none"
```

> **软失败是个隐形的性能杀手**：它不报错、不崩溃，只是**让那些方法永远只是解释执行**。如果你在做字节码插桩/加固/热修复，"某些方法莫名其妙慢"很可能就是这里。**验证结果会被缓存到 `.vdex`**，所以改了字节码要清掉对应产物再看。

### 14.4 热修复 / 插件化为什么这么难

四个"硬墙"（都是虚拟机和平台有意设计的）：

| 墙 | 内容 | 影响 |
|---|---|---|
| **① 代码执行限制** | Android 10 起，targetSdk ≥ 29 的应用**不能从可写目录加载/执行代码** | 从 `/data/data` 加载 dex/so 的方案被拦（NDK 篇 16.2 同源） |
| **② 类身份问题** | 类名 + ClassLoader 决定类身份（14.2） | 替换类实现需要 ClassLoader 层的 hack（如 `BaseDexClassLoader` 的 patch） |
| **③ 隐藏 API 限制** | Android 9 起限制访问 non-SDK 接口（hidden API），分 light/dark/black 名单 | 依赖 `@hide` API 的 hook 会失效；`dalvik.vm.hiddenapi` 相关策略 |
| **④ dex 加载入口收紧** | Android 14 起进一步限制 `DexClassLoader` 从可写路径加载 | 需要改用只读文件或新 API |

```bash
# 看设备的隐藏 API 限制策略（平台/调试用）
adb shell getprop | grep -i hidden
adb shell settings get global hidden_api_policy
#   hidden_api_policy 值：0=默认 1=禁用限制 2=只警告（仅调试）
```

**给平台开发者的建议**：**在系统 App / ROM 层面做功能，不要依赖插件化框架。** 你的 `platform_apis: true` 已经能直接访问 @hide API，OTA 也能更新功能——插件化解决的是"第三方 App 无法更新"的问题，而你没有这个问题。

### 14.5 类初始化（`<clinit>`）的几个坑

```kotlin
// ① 类初始化在第一次"主动使用"时才发生（首次 new / 静态字段访问 / 反射）
// ② <clinit> 由虚拟机加锁保证只执行一次 → 但可能死锁：
object A { init { B.use() } }        // A.<clinit> 持有 A 的锁，去要 B 的锁
object B { init { A.use() } }        // B.<clinit> 持有 B 的锁，去要 A 的锁 → 死锁

// ③ 类初始化抛异常 → ExceptionInInitializerError（真正的异常在 cause 里）
//    且这个类**本进程内会被标记为"初始化失败"**，后续访问直接抛 NoClassDefFoundError
//    ★ 排查铁律：看到 NoClassDefFoundError，先找前面的 ExceptionInInitializerError

// ④ Kotlin 的伴生对象 init 在 <clinit> 里执行 → 慢初始化会把类加载拖慢（影响启动）
```

**观测命令**：

```bash
# 看类加载与初始化相关的日志
adb logcat | grep -E "ClassLoader|NoClassDefFoundError|ExceptionInInitializerError|VerifyError"
# 看某个 App 加载了多少个 ClassLoader（插件化泄漏的常见症状）
adb shell dumpsys meminfo <pkg> | grep -i classloader
```

---

## 十五、可观测性与调试工具

虚拟机的工具分三类：**看形态**（dex/oat 里有什么）、**看运行**（profile/GC/线程）、**看现场**（崩溃/卡顿）。

### 15.1 工具清单（按"形态链"排列）

| 阶段/对象 | 工具 | 在哪 |
|---|---|---|
| 看 DEX | `dexdump` | 主机：`$ANDROID_HOME/build-tools/<ver>/dexdump` |
| 看 DEX（更友好） | `jadx` / `baksmali`（smali 反汇编，可改回 dex） | 第三方 |
| 看 OAT（机器码） | **`oatdump`** | 设备：`/apex/com.android.art/bin/oatdump` |
| 看 profile | **`profman`** | 设备：`/apex/com.android.art/bin/profman` |
| 手动编译 | **`dex2oat` / `dex2oat64`** | 设备：`/apex/com.android.art/bin/` |
| 编译状态查询 | `dumpsys package` | 设备 |
| 触发编译 | `cmd package compile` / `cmd package bg-dexopt-job` | 设备 |
| 堆快照 | `am dumpheap` + `hprof-conv` + Studio Profiler | 设备 + 主机 |
| 抓 Java 栈 | `kill -3`（SIGQUIT）→ `/data/anr/` | 设备 |
| 实时追踪 | Perfetto / Studio Profiler / `simpleperf` | 设备 + 主机 |
| 调试 | JDWP（`adb jdwp` + IDE attach） | 设备 + 主机 |

```bash
# 零基础：先搞清 ART 在哪、有哪些工具
adb shell ls /apex/com.android.art/bin/
#   art  dex2oat  dex2oat64  dexlist  dexoptanalyzer  oatdump  profman ...
adb shell ls -l /apex/com.android.art/lib64/ | grep libart
#   libart.so  libartbase.so  libartpalette.so  libprofile.so ...
```

### 15.2 `oatdump`：看编译成了什么（信息量最大的一个工具）

```bash
# ① 看一个 App 的编译产物：每个方法的编译状态、机器码、GC map、内联信息
adb shell /apex/com.android.art/bin/oatdump \
    --oat-file=/data/app/~~x/com.example.app-y/oat/arm64/base.oat \
    --output=/data/local/tmp/oat.txt
adb pull /data/local/tmp/oat.txt

# ② 看 boot image：预初始化的堆对象与 bootclasspath 类
adb shell /apex/com.android.art/bin/oatdump \
    --image=/system/framework/arm64/boot.art \
    --output=/data/local/tmp/boot.txt

# ③ 只看某个类（输出动辄几十 MB，一定要过滤）
grep -A30 "com.example.MainActivity" /data/local/tmp/oat.txt | head -60
```

输出里值得看的四处：

```
0x0000000000001234: [0x...] com.example.Foo.add(II)I
  |   ① 编译状态：compiled / verified(未编译) / interpreted
  |   ② 代码大小与是否是 quick code
  |   ③ 内联信息：inline 了哪些方法（优化编译的核心动作，16.3 用它判断"为什么没内联"）
  |   ④ GC map / stack map：这个栈帧的哪些位置有引用（决定 GC 怎么扫栈）
  |   ⑤ 机器码反汇编（能看到真实的 arm64 指令）
```

| 状态 | 含义 | 性能 |
|---|---|---|
| `compiled` | 有机器码 | 最好 |
| 未编译（只有验证） | 靠解释 + JIT 兜底 | 慢 |
| **验证软失败** | 永远解释执行，且 JIT 也不优化 | **最慢且不报错**（14.3） |

### 15.3 `profman`：读 profile

```bash
# dump 成文本：看清"系统认为你哪些方法是热点"
adb shell /apex/com.android.art/bin/profman --dump-only \
    --profile-file=/data/misc/profiles/cur/0/com.example.app/primary.prof

# 输出大致是：
#   [0] class com.example.MainActivity
#   [1] method com.example.MainActivity onCreate()V
#   [2] method com.example.Foo parse(I)I
```

**这个输出解释了"为什么我的 App 编译得不理想"**：`speed-profile` **只编译 profile 里出现的方法**。如果你的瓶颈在解析逻辑，而 profile 里全是 UI 方法（因为用户交互主要触发 UI），**解析逻辑就永远停在解释执行**。

### 15.4 编译日志：看 dex2oat 干了什么

```bash
# 手动跑一次，最直观（会看到用了什么 filter、编译了多少方法）
adb shell dex2oat64 --dex-file=/data/local/tmp/test.dex \
    --oat-file=/data/local/tmp/test.oat \
    --instruction-set=arm64 --compiler-filter=speed-profile \
    --boot-image=/system/framework/arm64/boot.art 2>&1 | head -40
#   Using compiler filter speed-profile
#   Compiled 1234 methods          ← profile 命中的方法数

# 用 cmd package compile 时，dex2oat 的输出会进 logcat
adb logcat -s dex2oat:V PackageManager:I art:I | head -60
```

### 15.5 运行期观测：抓 Java 栈

```bash
# ① SIGQUIT：让虚拟机把所有线程的 Java 栈写进 /data/anr/
adb shell kill -3 $(adb shell pidof com.example.app)
adb shell ls -lt /data/anr/ | head
adb pull /data/anr/traces.txt

# traces.txt 里每个线程一段，形态是：
#   "main" prio=5 tid=1 Native
#     | group="main" sCount=1 ucsCount=0 flags=1 obj=0x...
#     | sysTid=1234 nice=0 cgrp=default sched=0/0 handle=0x...
#     | state=S schedstat=( ... )
#     at android.os.SystemClock.sleep(Native method)
#     at com.example.MainActivity.onCreate(MainActivity.kt:42)   ← ★ 就是这行
#
#   ★ 这是 ANR / 卡死的标准手法：栈是虚拟机在 SIGQUIT 处理器里遍历线程打印的

# ② JDWP：接调试器（IDE attach 用的就是它）
adb jdwp                       # 列出可调试进程
adb forward tcp:8700 jdwp:<pid>
#   ⚠️ 一旦 attach，虚拟机进入"可调试"状态：性能下降、JIT/优化行为改变
#      → 开调试器测性能是不可靠的

# ③ Perfetto：GC / JIT / 锁竞争 / 帧 一起看
adb shell perfetto -o /data/misc/perfetto-traces/art.pftrace -t 15s -c - <<'EOF'
buffers { size_kb: 32768 }
data_sources { config { name: "linux.ftrace" ftrace_config {
  ftrace_events: ["sched/sched_switch"]
  atrace_categories: ["dalvik", "art", "sched", "view", "binder_driver"]
  atrace_apps: ["com.example.app"]
} } }
EOF
# 打开 ui.perfetto.dev，看 art 轨上的 GC 事件是否落在掉帧区间
```

### 15.6 一次"App 变慢了"的标准排查流程

```bash
# ── 1. 它现在被编译到什么程度？────────────────────────────
adb shell dumpsys package com.example.app | sed -n '/Dexopt state/,/^$/p'
#    verify        → 还没优化，慢是正常的（第八节）
#    speed-profile → 已优化，瓶颈在别处

# ── 2. 有没有 profile？────────────────────────────────────
adb shell "ls -l /data/misc/profiles/cur/0/com.example.app/"
#    没有 → bg-dexopt 永远不会优化它

# ── 3. GC 是否在拖后腿？───────────────────────────────────
adb logcat -s art:* | grep -E "GC|Jit code cache" | tail -30

# ── 4. 时间花在哪个 Java 栈上？─────────────────────────────
adb shell kill -3 $(adb shell pidof com.example.app)    # 抓两三次对比

# ── 5. 内存是否到了上限？──────────────────────────────────
adb shell dumpsys meminfo com.example.app | sed -n '/App Summary/,/TOTAL/p'

# ── 6. 手动优化一次，验证假设 ─────────────────────────────
adb shell cmd package compile -m speed -f com.example.app
#    变快 → 确认是编译状态问题 → 上 Baseline Profile（6.4）
#    没变 → 瓶颈不在虚拟机层，去查 IO/网络/锁
```

**顺序是有讲究的**：从最便宜的检查开始，**先排除"本该快但现在不快"，再找真正的瓶颈**。

---

## 十六、性能剖析：编译状态、内联、启动优化

### 16.1 启动优化的清单（按收益排序）

| 手段 | 典型收益 | 成本 | 适用 |
|---|---|---|---|
| **Baseline Profile** | 启动快 20%~40% | 一个测试模块 + 构建时间 | ★ 所有 App |
| 减少启动路径上的类与 `<clinit>` | 10%~30% | 重构 | 所有 App |
| 把 `ContentProvider` / SDK 初始化延后 | 5%~20% | 架构调整 | 多 SDK 初始化项目 |
| 减少主 dex 内容（R8 / multidex 配置） | 5%~15% | 配置 | 多 dex 项目 |
| 减小 `Application.onCreate` 工作量 | 视情况 | 重构 | 所有 App |
| 平台预编译（`speed`） | 20%~40% | **平台视角** | 系统 App（第十七节） |

```kotlin
// ❌ 典型"启动杀手"：所有 SDK 都在 Application.onCreate 里初始化
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.init(this)     // 每个几十~几百 ms
        Analytics.init(this)
        Net.init(this)
        ImageLoader.init(this)
        Database.init(this)          // 甚至同步做了 IO
        // → 启动路径上几百个类被加载与初始化，且全在"未优化"状态下执行
    }
}

// ✅ 关键路径同步，其余延后
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.init(this)              // 崩溃上报必须最早
        Looper.myQueue().addIdleHandler {     // 空闲时再初始化
            Analytics.init(applicationContext)
            false                             // 返回 false = 执行完就移除
        }
    }
}
```

**关键认知**：**未优化的代码上做的每件事都更贵。** 启动阶段（`verify` 状态）代码是解释执行的，同样的初始化逻辑，在启动时执行和用户操作时执行，单次成本可能差 5~10 倍。**这就是"懒加载"在 Android 上收益比 JVM 更明显的原因。**

### 16.2 分诊表：瓶颈在不在虚拟机层

| 现象 | 是虚拟机层问题？ | 怎么确认 |
|---|---|---|
| 首次启动慢，用几天后变快 | ✅ 是（编译状态） | `dumpsys package` 看 status 变化 |
| 某个循环/计算特别慢 | ⚠️ 可能（未编译/未内联） | `oatdump` 看该方法是否 `compiled` |
| 卡顿但 GC 日志正常 | ❌ 不是 GC | Perfetto 看主线程在等什么（IO/锁/binder） |
| ANR | ❌ 通常不是虚拟机 | `/data/anr/traces.txt` 看主线程栈 |
| 内存持续涨 | ⚠️ 先分 Dalvik / Native | `dumpsys meminfo`（第十三节与 NDK 篇分工） |
| release 比 debug 还慢 | ⚠️ 可能 | 对比两者 `Dexopt state`（见下） |
| 低端机性能断崖 | ✅ 常见（编译状态 + 内存档位） | 对比 `heapgrowthlimit` 与 GC 频率 |

```bash
# "release 比 debug 慢"的专用排查：
# debug 版本可能反而被编译得更彻底（本地构建常带 speed），而 release 装上去是 verify
adb shell dumpsys package com.example.app | grep -A3 "Dexopt state"
```

### 16.3 "我的方法为什么没被内联"

| 原因 | 说明 |
|---|---|
| **方法在 profile 里没出现** | `speed-profile` 只编译热点；没编译就谈不上内联 |
| 方法体太大 | 超出内联预算（阈值随编译档位变化） |
| 虚调用无法去虚拟化 | 接口/父类有多个实现，编译器证明不了单一接收者（7.2） |
| `synchronized` 方法 | 有锁的方法内联要额外处理，可能放弃 |
| 异常路径复杂 | `try/catch` 结构增加内联成本 |
| 编译档位是 `verify` | 根本没编译 |

```bash
# 打开 JIT 日志，看哪些方法被 JIT 编译了
adb shell setprop dalvik.vm.extra-opts "-Xlog:jit*=info"
# 老版本写法
adb shell setprop dalvik.vm.extra-opts "-verbose:jit"
adb logcat -s art:* | grep -i jit
```

### 16.4 dex2oat 自身的性能（平台视角）

```bash
# 看线程/CPU 配置
adb shell getprop | grep -i dex2oat
#   [dalvik.vm.dex2oat-threads] / [dalvik.vm.dex2oat-cpu-set]

# 现场观测一次编译（判断"编译慢"是不是 dex2oat 本身的问题）
adb shell "ps -A | grep dex2oat"
adb shell "top -H -p $(adb shell pidof dex2oat64) 2>/dev/null | head"
```

| 目标 | 手段 | 代价 |
|---|---|---|
| 安装更快 | `pm.dexopt.install=verify` | 首次启动更慢 |
| 首启更快 | `pm.dexopt.first-boot=verify` | 首启后 App 首次使用慢 |
| 预装 App 一开始就快 | 预置编译产物（17.3） | /data 或系统分区空间 |
| 减少 oat 占用 | 用 `speed-profile` 而不是 `speed` | 需要 profile 才能变快 |
| 编译更快 | 增加 dex2oat 线程数 | 抢 CPU，影响前台体验 |

---

## 十七、平台视角：boot image、dexopt 策略、车机定制

> 写给改 ROM / 做预装 / 车机定制的场景。普通 App 可跳到第十八节。

### 17.1 关键属性总表（定制第一站）

```bash
# 每次改 ROM 前，先留一份基线（后面所有对比都靠它）
adb shell getprop | grep -E "dalvik\.vm|pm\.dexopt|ro\.bootimage|persist\.art" > art-props.txt
```

| 属性 | 作用 | 常见取值 |
|---|---|---|
| `dalvik.vm.image-dex2oat-filter` | **boot image** 的编译档 | `speed`（值得全量编） |
| `pm.dexopt.install` | 安装时 | `verify` / `speed-profile` |
| `pm.dexopt.first-boot` | 首次开机 | `verify` |
| `pm.dexopt.boot-after-ota` | OTA 后首次启动 | `verify` / `speed-profile` |
| `pm.dexopt.post-boot` | 开机后 | 随版本 |
| `pm.dexopt.bg-dexopt` | 后台空闲编译 | `speed-profile` |
| `pm.dexopt.disable_bg_dexopt` | 关掉后台编译 | `false`（默认） |
| `dalvik.vm.dex2oat-threads` | 编译线程数 | 随核数 |
| `dalvik.vm.heapsize` / `heapgrowthlimit` | 堆上限 | 见 10.3 |
| `dalvik.vm.usejit` | JIT 开关 | `true`（关掉会让未编译代码极慢） |
| `dalvik.vm.jitthreshold` | JIT 触发阈值 | 调大更省电但预热更慢 |
| `dalvik.vm.checkjni` | JNI 严格检查 | `false`（调试时开，对应 NDK 篇第八节） |

> ⚠️ **属性名有版本变迁**（早期 `dalvik.vm.dex2oat-filter` 系列 → 现在的 `pm.dexopt.*`）。**改前先 grep 自己源码树里定义这些属性的 `.prop` 文件**，不要照抄博客。

### 17.2 boot image 的构建与精简

```makefile
# bootclasspath 由 PRODUCT_BOOT_JARS 决定
PRODUCT_BOOT_JARS += my-custom-lib          # 把自己的库放进 bootclasspath（谨慎）
```

**不要随便往 bootclasspath 加东西**：

| 好处 | 代价 |
|---|---|
| 你的类在所有进程可用、已预加载 | **boot image 变大 → 所有进程内存增加、开机变慢** |
| 启动少一次类加载 | 升级要刷 ROM；boot image 一变，相关 oat 可能全部失效重编 |

```bash
# 精简方向的现状检查
adb shell "du -sh /system/framework/*/boot.*"
adb shell "wc -l /system/etc/preloaded-classes"
```

精简手段：删掉 bootclasspath 里用不到的库、削减预加载类列表。**代价是相关类首次使用时变慢** —— 典型的"开机时间 vs 应用启动"权衡。

### 17.3 预装 App 的编译策略

```makefile
# 名字随 AOSP 版本有变化，以自己源码树的 dex_preopt*.mk 为准
DEX_PREOPT_DEFAULT := speed                # 预装 App 全量编译：开机即快，占空间
# DEX_PREOPT_DEFAULT := speed-profile      # 省空间，首次使用等 profile

PRODUCT_DEXPREOPT_SPEED_APPS += MyCarApp   # 只给指定 App 全量优化（平衡的常用手法）
DONT_DEXPREOPT_PREBUILTS := true           # 预编译 APK 不预置产物（省空间）
```

> **改完必须实测三个指标**：**首次开机时间 / `/data` 占用 / 目标 App 首次启动时间**。三者互相牵制，没有免费的提升。

### 17.4 首次开机为什么长（以及怎么改）

```
内核 → init → 挂载 → zygote → system_server ←── 界面可用（但很慢）
                                    │
                                    └→ 首次开机 dexopt（first-boot）★ 大头
```

| 想减少… | 手段 | 代价 |
|---|---|---|
| 首次开机时间 | `pm.dexopt.first-boot=verify` | 开机后 App 首次使用慢（靠 JIT + 后续 bg-dexopt 补） |
| 首次开机时间（更狠） | 跳过首启 dexopt / `run-from-apk` | 长期性能差 |
| App 首次使用体验 | `first-boot=speed-profile` + 预置 Baseline Profile | 开机更慢 |
| 长期体验 | 保证 `bg-dexopt` 能真的跑起来（17.5） | — |

### 17.5 车机专属：设备从不等空闲，所以永远不编译

**问题**：`bg-dexopt` 依赖"**设备空闲 + 充电**"。车机常年插电、屏幕常亮、后台总有事 → JobScheduler 的空闲条件可能长期不满足 → App 永远停在 `verify` → 用户抱怨"车机上比手机慢"。

**对策清单**（按推荐度）：

| 对策 | 做法 | 说明 |
|---|---|---|
| **① 预置编译产物** | `DEX_PREOPT_DEFAULT := speed` 或 `PRODUCT_DEXPREOPT_SPEED_APPS` | 最直接，代价是空间 |
| **② 产线/首启主动触发** | 出厂前跑一次全量编译；或开机后延迟触发后台编译 | 很实用，见下面命令 |
| **③ 打 Baseline Profile** | App 自带预热 profile | **不依赖平台策略，最稳** |
| **④ 放宽 bg-dexopt 约束** | 改 `BackgroundDexOptService` / JobScheduler 的空闲判定 | 改动面大，要评估功耗与寿命 |
| **⑤ 服务端/OTA 后补一次** | OTA 完成后触发 `bg-dexopt-job` | 与 `pm.dexopt.boot-after-ota` 配合 |

```bash
# 出厂前/OTA 后一次性优化全部 App（产线脚本）
adb shell cmd package compile -a -m speed-profile -f
# 校验覆盖率
adb shell dumpsys package | grep -c "status=speed-profile"
```

### 17.6 车机上的堆参数与多用户

| 定制点 | 建议 | 风险 |
|---|---|---|
| 调大 `heapgrowthlimit` | 只有确认"多 App 并行后仍有富余内存"才动 | **调大 → 其它进程更容易被 LMK 杀**。先测 `dumpsys activity processes` 的 adj 与 lmkd 日志 |
| 调大 `heapminfree` / `heapmaxfree` | 减少 GC 频率，适合卡顿敏感场景 | 内存占用上升 |
| **多用户** | profile 按用户分（`cur/<userId>/`），编译产物全局共享 | "切用户后 App 变慢"是**预期行为**，不是 bug |
| 多屏 | 每个 display 的 Activity 各自占堆 | 内存不够时应"减少持有"而不是"调上限" |
| 常驻前台服务 | 长期持对象 → GC 频繁 | 用 Perfetto 看 GC 频率，给常驻缓存设上限 |

### 17.7 调试开关（平台视角，改完记得清掉）

```bash
# 全局打开 JNI 严格检查（显著变慢，仅调试，对应 NDK 篇第八节）
adb shell setprop dalvik.vm.checkjni true

# 全局详细日志
adb shell setprop dalvik.vm.extra-opts "-verbose:gc -verbose:jit"

# 验证某问题是不是 JIT 引起的（性能会崩，仅调试）
adb shell setprop dalvik.vm.usejit false

# 跳过验证（线上绝不能开，等于放弃安全检查）
adb shell setprop dalvik.vm.extra-opts "-Xverify:none"

# 用完清掉！
adb shell setprop dalvik.vm.extra-opts ""
```

> **注意**：这些属性是**全局的**（zygote 读属性决定虚拟机行为）。**改完要重启对应进程或整个系统才生效，且一定要记得清空**，否则会一直压着整机性能。

### 17.8 清 /data 之后为什么"变慢"了

预装/车机场景的高频疑问，答案就在这条链上：

```
清 /data（恢复出厂 / 刷机后首次开机）
   ├─ 用户 App 全没了（正常）
   ├─ ★ 系统 App 的 dexopt 产物也全没了（它们在 /data/dalvik-cache）
   │     → 全部回到 verify 状态 → 首次打开任何 App 都慢
   ├─ ★ 所有 Baseline Profile 也没了 → App 自带的预热也失效
   └─ 首次开机要重跑 first-boot dexopt → 开机时间变长
```

**"恢复出厂后车机变慢"是正常现象，bg-dexopt 跑完会恢复。** 产品上如果无法接受这个窗口，就用 17.5 的对策①②。

---

## 十八、常见问题排查表

> 用法：先在"现象"列找最像的，再按"第一条命令"动手。**"第几节"是详解位置。**

| # | 现象 | 最可能的原因 | 第几节 | 第一条命令 |
|---|---|---|---|---|
| 1 | 新装的 App 第一次启动特别慢 | 状态是 `verify`，还没优化 | 6.2 / 8.2 | `adb shell dumpsys package <pkg> \| sed -n '/Dexopt state/,/^$/p'` |
| 2 | 用几天后自己变快了 | `bg-dexopt` 跑过了（预期行为） | 8.2 | 同上，看 status 是否变 `speed-profile` |
| 3 | 一直很慢，从没变快 | 没有 profile / bg-dexopt 没机会跑 | 8.3 | `ls -l /data/misc/profiles/cur/0/<pkg>/` |
| 4 | 车机上的 App 永远比手机慢 | 设备从不空闲，bg-dexopt 不触发 | 17.5 | 同上 + `getprop pm.dexopt.bg-dexopt` |
| 5 | 想不依赖设备空闲就变快 | — | 6.4 | 上 Baseline Profile |
| 6 | 日志里 `GC_FOR_ALLOC` 刷屏 | 分配速率过高 | 11.3 | `adb logcat -s art:* \| grep GC` |
| 7 | 列表滑动卡顿 + GC 日志频繁 | 在主线程/`onBindViewHolder` 里分配对象 | 11.4 | Studio Profiler → Allocation Tracking |
| 8 | GC 日志 `paused` 到几毫秒以上 | LOS 对象过多 / 堆压力大 | 12.1 | 看日志里 `LOS objects` 的大小 |
| 9 | GC 日志 `total` 很长（几百 ms+） | CPU 被抢 / 堆很大 | 12.1 | Perfetto 看 CPU 占用 |
| 10 | `freed 0B` 反复出现 | 内存泄漏，或刚 GC 过 | 12.1 / 13.5 | `dumpsys meminfo` 看是否持续上涨 |
| 11 | Java 内存持续上涨 | 强引用链没断（静态集合/内部类/监听器） | 13.5 | `am dumpheap` + Studio 追 GC Root 链 |
| 12 | `Finalizer timed out!` / 进程被杀 | `finalize()` 阻塞或超时 | 13.2 | 全局搜 `finalize(` |
| 13 | `Timeout executing finalizer` | 同上 | 13.2 | 同上 |
| 14 | `NoClassDefFoundError` | **类初始化失败**（前面通常有 `ExceptionInInitializerError`） | 14.5 | `adb logcat \| grep -B5 NoClassDefFoundError` |
| 15 | `ExceptionInInitializerError` | `<clinit>` 抛异常（静态初始化、`loadLibrary` 失败） | 14.5 | 看 `cause` |
| 16 | `VerifyError` | 字节码被改过 / dex 版本不匹配 / 插桩后不合法 | 14.3 | `dexdump -d` 查该类 |
| 17 | 某些方法莫名很慢（不报错不崩） | **验证软失败** → 永远解释执行 | 14.3 | `oatdump` 看是否 `compiled` |
| 18 | release 崩、debug 不崩 | R8 删了反射用到的类 | 4.5 | 看崩溃栈 + 补 keep 规则 |
| 19 | release 崩溃栈没有行号 | 没保留调试信息 | 3.2 | `-keepattributes SourceFile,LineNumberTable` |
| 20 | `ClassCastException` 但类型明明一样 | 两个 ClassLoader 加载了同名类 | 14.2 | 打印 `obj.javaClass.classLoader` |
| 21 | 插件里的类无法覆盖系统类 | 双亲委派（bootclasspath 优先） | 14.2 | 确认类名与加载器 |
| 22 | 动态加载 dex 失败 | Android 10+/14+ 的可写目录限制 | 14.4 | `adb logcat \| grep -E "DexClassLoader\|Permission"` |
| 23 | hook 突然失效 | 隐藏 API 限制（Android 9+） | 14.4 | `adb shell settings get global hidden_api_policy` |
| 24 | 方法数超过 64K 无法构建 | 单 dex 的 method index 只有 16 位 | 4.3 | 构建日志里的 `Too many method references` |
| 25 | 安装后 /data 空间被吃掉很多 | oat/vdex 产物（尤其多 ABI） | 6.5 | `du -sh /data/app/*/<pkg>-*/oat/*` |
| 26 | 卸载 App 后空间没完全回来 | 系统 App 的产物在 `/data/dalvik-cache` | 6.5 | `du -sh /data/dalvik-cache/*` |
| 27 | 首次开机特别久 | `first-boot` dexopt | 17.4 | `adb logcat \| grep -iE "dexopt\|dex2oat"` |
| 28 | 恢复出厂后整体变慢 | 编译产物与 profile 被清空 | 17.8 | 等 bg-dexopt，或主动触发（17.5） |
| 29 | 切换用户后某 App 变慢 | profile 按用户分，新用户没有 | 8.1 / 17.6 | `ls /data/misc/profiles/cur/*/` |
| 30 | `OutOfMemoryError: Failed to allocate` | Java 堆到上限 | 10.3 | `dumpsys meminfo <pkg> \| grep -i "dalvik heap"` |
| 31 | 内存涨但 Java 堆不大 | 涨的是 native 堆 | 13.5 | `dumpsys meminfo` 对比 `Dalvik Heap` 与 `Native Heap`（→ NDK 篇） |
| 32 | 空实现的方法也占时间 | 脱糖生成的合成类/方法被调用 | 4.2 | `dexdump` 看合成类 |
| 33 | 开调试器后性能完全不一样 | JDWP 让虚拟机进入可调试状态 | 15.5 | 关掉调试器重测 |
| 34 | 关掉 JIT 后 App 极慢 | 未编译代码全靠解释 | 7.1 | `getprop dalvik.vm.usejit`（确认是否被改过） |
| 35 | 改字节码/插桩后行为异常 | 验证软失败 / dex 不合法 | 14.3 | 清掉 oat/vdex 产物重新生成 |

---

## 十九、读源码与读文档路线

### 19.1 源码结构（`art/` 顶层，按重要性）

```
art/
├── runtime/                       ★ 虚拟机核心（从这里开始）
│   ├── runtime.cc / runtime.h         Runtime::Create / Init（虚拟机启动入口）
│   ├── thread.cc                      Thread / 状态机 / AttachCurrentThread（→ NDK 篇第七节）
│   ├── jni/                           JNI 的实现（→ NDK 篇主要落点）
│   ├── gc/                        ★ 垃圾回收
│   │   ├── heap.cc                    Heap::AllocObject / CollectGarbageInternal（分配与触发）
│   │   ├── collector/concurrent_copying.cc   ★ CC GC 实现（第十一节）
│   │   ├── collector/mark_sweep.cc            mark-sweep（LOS 也用它）
│   │   ├── space/region_space.cc              RegionSpace（主分配区的 region 管理）
│   │   └── space/large_object_space.cc        LOS
│   ├── dex/                       ★ DEX 加载与验证
│   │   ├── dex_file.cc                DEX 格式解析（第五节）
│   │   └── dex_verifier.cc         ★ 验证器（14.3 软失败的判定在这）
│   ├── oat/                           OAT 文件格式与加载（第六节）
│   ├── interpreter/               ★ 解释器（含 inline cache，7.2）
│   ├── jit/                       ★ JIT 编译器与 JIT code cache（7.1）
│   ├── entrypoints/               ★ 运行时入口（Java ↔ 编译代码 ↔ 解释器之间的桥）
│   ├── class_linker.cc            类加载、链接、<clinit>（第十四节）
│   ├── mirror/                    Java 对象在 native 侧的镜像（mirror::Object 等）
│   └── hprof/                     堆快照产出
├── compiler/                      ★ 编译器
│   ├── optimizing/                ★ 优化编译的各 Pass（内联、去虚拟化、寄存器分配）
│   ├── jni/                       为 JNI 方法生成 stub
│   └── utils/
├── dex2oat/                       ★ dex2oat 的 main（第六节）
├── dexoptanalyzer/                    判断"要不要重新编译"
├── oatdump/                       ★ oatdump 工具本身
├── profman/                       ★ profile 工具本身
└── libartbase/                        基础库（内存、文件、日志）
```

### 19.2 五条读源码路线

**线一：虚拟机怎么启动的（接 Framework 01 篇）**

```
frameworks/base/cmds/app_process/app_main.cpp         C++ 入口
  → AndroidRuntime::start()                            frameworks/base/core/jni/AndroidRuntime.cpp
     → JNI_CreateJavaVM                                art/runtime/jni/java_vm_ext.cc
        → Runtime::Create / Runtime::Init              art/runtime/runtime.cc
           → 加载 boot image                            art/runtime/oat/ + image_space
           → ZygoteInit.main()                          ZygoteInit.java
              → BootClassLoader 建立 → preload → fork system_server / App
```

**线二：一次方法调用怎么执行（理解四级引擎）**

```
ArtMethod::Invoke                                      art/runtime/mirror/art_method.cc
  ├─ 已是机器码？→ art_quick_invoke_stub（entrypoints/quick）→ 直接执行
  ├─ 需要 JIT？  → art/runtime/jit/jit_compiler.cc → 写入 JIT code cache
  └─ 否则        → art/runtime/interpreter/interpreter_switch_impl.cc（解释器）
                     └─ 解释器里的 inline cache：interpreter_common.h 里的 DoInvoke / 桩实现
```

**线三：GC 全程（对应第十~十二章）**

```
Heap::AllocObject（分配失败）
  → Heap::CollectGarbageInternal                      art/runtime/gc/heap.cc
     → 选 collector（CC / MarkSweep / CMS）
        → concurrent_copying.cc：初始暂停 → 并发标记复制 → 重标记 → 回收 region
           → RegionSpace::Allocate / Free
              → 日志输出（"Background concurrent copying GC freed …" 就在这附近）
```

**线四：DEX 怎么变成可执行（对应第四~六章）**

```
DexFile::Open / DexFileVerifier                        art/runtime/dex/
  → ClassLinker::LoadClass / LinkClass                  art/runtime/class_linker.cc
     → dex2oat 主流程（art/dex2oat/dex2oat.cc）         用哪个 filter、编译哪些方法
        → compiler/optimizing/ 里跑一遍 Pass 管线
           → 写 oat（art/compiler/oat_writer.cc）
              → 运行时由 OatFile 加载（art/runtime/oat/oat_file.cc）
```

**线五：Java 侧的镜像（想改框架行为时看这条）**

```
libcore/                        ← Java 侧核心库实现（Object、String、System、Charset…）
  └─ ojluni/src/main/java/java/lang/Object.java 等的 Android 版本实现
frameworks/base/core/java/       ← Framework Java 层（AMS/WMS 等，接 Framework 系列）
libnativehelper/                 ← JNI 辅助（ScopedLocalRef 等，接 NDK 篇）
```

### 19.3 官方文档与工具文档

| 文档 | 看什么 |
|---|---|
| AOSP《ART 与 Dalvik 概览》 | 架构总览、dex2oat/JIT/GC 的官方描述 |
| AOSP《Configuring ART》 | `dalvik.vm.*` / `pm.dexopt.*` 的权威清单（版本相关，**以自己源码树为准**） |
| AOSP《Debugging ART》 | oatdump / profman / 各调试开关的用法 |
| Android《Baseline Profiles》 | Baseline Profile 的官方做法与收益数据 |
| Android《Reducing app size》/《App startup time》 | 构建参数与启动优化的官方建议 |
| `oatdump --help`、`profman --help` | **比任何博客都准**（因为版本相关） |

### 19.4 一个高效的排查习惯

```
它"慢" → 先看 dumpsys 的 Dexopt state（第六/八节）
           已优化  → 再用 Perfetto 抓（第十五节），别怀疑虚拟机
           未优化  → Baseline Profile / 触发编译
它"崩" → Java 崩溃看 logcat 的栈（第十八节 #14~19）
           没有 Java 栈 → 是 native 崩溃 → NDK 篇第十四节
它"占内存" → dumpsys 先分 Dalvik / Native（第十三节 / NDK 篇第十节）
它"开机慢" → 数 dexopt 的时间（第十七节）
```

---

## 二十、一图总结

```
   一份代码的五种形态（本文主线）
   ════════════════════════════════════════════════════════════════════

   Hello.kt
      │  ①  kotlinc / javac
      ▼
   Hello.class（JVM 字节码，栈式；设备上从不执行，只是中间格式）
      │  ②  d8（debug）/ R8（release）
      │      脱糖：lambda、默认方法、try-with-resources、java.time
      │      摇树/混淆/优化；multidex 拆分（64K method index 限制在这）
      ▼
   classes.dex（寄存器式；共享常量池；16 位 method/field/type/proto 索引）
      │  ③  安装/空闲时：dex2oat（compiler filter 决定编多少）
      │      verify → speed-profile → speed → everything
      ▼
   base.oat（机器码）+ base.vdex（dex + 验证结果）[+ base.art 堆镜像]
      │  ④  运行期：解释器 → 基线 JIT → profile → 优化编译
      │      热点方法进 profile（primary.prof）→ 后台再 AOT 一遍
      ▼
   堆里的对象（ImageSpace / ZygoteSpace / NonMoving / Main(RegionSpace) / LOS）
      │  ⑤  GC：mark-sweep → CMS → CC（并发复制）→ 分代 CC
      │      "Background concurrent copying GC freed … paused 123us total 1.2s"
      ▼
   被回收 / 被泄漏（泄漏 = 一条不该存在的强引用链）


   三条并行的"谁在管"
   ┌────────────────────┬──────────────────────┬─────────────────────┐
   │ 编译状态（管性能）   │ Profile（管编译什么） │ 堆与 GC（管内存）    │
   │ dumpsys package    │ /data/misc/profiles/ │ dumpsys meminfo     │
   │ /Dexopt state      │ cur + ref            │ + art 日志          │
   │ verify/speed-      │ profman 可读          │ paused / total      │
   │ profile/speed      │                      │                     │
   └────────────────────┴──────────────────────┴─────────────────────┘
              │                    │                      │
              └──────── 都由 ART 决定，而 ART 在 APEX 里可独立更新 ────┘
                            （所以"版本"要说清是 Android 版本还是 ART 版本）
```

### 一句话记忆链

> **源码 → 字节码 → DEX → 机器码 → 堆对象**，每降一级换一个工具：
> **`dexdump` 看 DEX，`oatdump` 看机器码，`profman` 看 profile，`dumpsys meminfo` 看堆，`oatdump`/`dumpsys package` 看编译状态。**

### 三条最省时间的经验

1. **遇到"慢"，第一站永远是 `dumpsys package … /Dexopt state`** —— 它能区分"本该快但没优化"和"真的是瓶颈"，省掉一半无效分析（第八、十五节）。
2. **想不依赖设备空闲就变快，上 Baseline Profile** —— 这是 App 侧唯一能稳定拿到 20%~40% 启动提升的手段（第 6.4 节）。
3. **看到 `NoClassDefFoundError` 先往前找 `ExceptionInInitializerError`；看到内存涨先分 Dalvik / Native** —— 两条分岔路口，走对能直接少两轮排查（第 14.5、13.5 节）。

---

## 关联阅读

| 文档 | 关系 |
|---|---|
| `androidFrameworks/01_Android启动流程详解.md` | `app_process` → `AndroidRuntime::start()` 的上游；zygote 与 boot image 的系统视角 |
| `androidFrameworks/03_消息机制详解.md` | Looper/Handler 跑在虚拟机线程模型上；ANR 抓栈与本文第十五节同一套工具 |
| `androidFrameworks/05_AMS机制详解.md` | `dumpsys activity processes` 的 adj 与本文 17.6 的 LMK/堆参数权衡呼应 |
| `androidOthers/AndroidNDK与JNI详解.md` | 边界桥接视角：`JNIEnv` 线程模型、native 堆与 Java 堆的分工、tombstone 与 Java 栈的对照 |
| `androidOthers/Android存储机制详解.md` | `/data/dalvik-cache`、oat/vdex 占用与分区空间的互补视角 |
| `androidApp/15-App编译配置文件详解.md` | 构建侧（Gradle/AGP）视角，本文第 3、4、6 节的另一半 |
| `C++快速上手指南.md` | 想读 ART 的 C++ 源码（`runtime/`、`compiler/`）时的语言基础 |

