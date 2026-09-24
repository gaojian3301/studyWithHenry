# Android 构建系统详解——从 Soong 到 AOSP 整机集成

> 这篇是"编译配置"知识的 ROM 对偶篇。你日常在 `device/vendor` 目录改配置、编镜像、刷机，背后是一套和 App 侧完全不同的构建世界。
>
> 知识形状是**流水线型**：同一份源码在生命周期里被反复转换形态（`.bp` 蓝图 → ninja 规则 → `.so/.apk` 二进制 → 各分区镜像 → 烧进闪存）。所以本篇严格按"形态链顺序"讲，每一形态回答三个问题：**谁产出的、长什么样、错了什么症状**。最后把并行的管理机制（分区归属、Overlay、APEX）抽成横切章。
>
> 它与同目录《Android 存储机制详解》是**呼应关系**：存储篇告诉你"系统分区是只读 + dm-verity 保护"，本篇告诉你"这些只读镜像是怎么被 build 出来的"；也跟 `androidFrameworks/01_Android启动流程详解` 衔接——01 篇讲 init 怎么把镜像跑起来，本篇讲镜像怎么来的。

---

## 目录

**第一段：形态链——从源码到镜像**

1. [开场：两套构建世界的分工](#一开场两套构建世界的分工)
2. [AOSP 目录结构与分层](#二aosp-目录结构与分层)
3. [构建入口与流程：envsetup、lunch、soong_ui、ninja](#三构建入口与流程envsetuplunchsoong_uininja)
4. [Android.bp 语法全表](#四androidbp-语法全表)
5. [Android.mk 与遗留模块](#五androidmk-与遗留模块)
6. [产品配置三件套](#六产品配置三件套)

**第二段：横切规则——东西归谁、长什么样**

7. [分区归属规则专题](#七分区归属规则专题)
8. [RRO 与 Overlay](#八rro-与-overlay)
9. [Treble 与 VNDK/LLNDK 的构建侧约束](#九treble-与-vndkllndk-的构建侧约束)
10. [APEX 与 Mainline](#十apex-与-mainline)

**第三段：产物、验证与效率**

11. [产物与镜像](#十一产物与镜像)
12. [刷机与快速验证](#十二刷机与快速验证)
13. [编译效率](#十三编译效率)
14. [常见错误排查表](#十四常见错误排查表)
15. [车机 vendor 视角](#十五车机-vendor-视角)
16. [读源码路线 + 源码路径速查表](#十六读源码路线--源码路径速查表)
17. [一图总结](#十七一图总结)

---

## 一、开场：两套构建世界的分工

先纠正一个常见误解：**Android 应用开发用的 Gradle 和整机编译用的 Soong/Make 是两个完全不互通的世界**。你改 App 用 `./gradlew assembleRelease`，你编系统镜像用 `m systemimage`——两者除了"都叫构建"之外几乎没交集。

| 维度 | App 构建（Gradle） | 整机构建（Soong / Make / Ninja） |
|---|---|---|
| 输入 | `build.gradle`、`src/`、`res/` | `Android.bp` / `Android.mk`、源码树、产品配置 |
| 输出 | `app-release.apk` / AAB | `system.img`、`vendor.img`、`boot.img`、`*.so`、`*.apk`（系统预装） |
| 工具链 | Gradle + AGP + Kotlin/Java 编译器 | Soong（蓝图）→ Ninja → clang/gcc + dex2oat + aapt2 |
| 产物落点 | 给应用商店或本地安装 | 烧进设备分区（`/system`、`/vendor`、`/product`…） |
| 配置中心 | `build.gradle` + `gradle.properties` | `device.mk` / `BoardConfig.mk` / `AndroidProducts.mk` |
| 并行与缓存 | Gradle 守护进程 + 构建缓存 | `ninja` 原生并行 + `ccache` + 增量 |
| 你最常在哪改 | `app/` 模块 | `device/<vendor>/<product>/`、`vendor/<chip>/` |

**一句话定位**：Gradle 管"一个 App 怎么变成安装包"；Soong/Make 管"几千个模块怎么拼成一个能开机、能刷机的完整系统镜像"。本篇只讲后者。

### 1.1 从 Android.bp 到 system.img 的全景图

```
源码树（几百 GB）
  │
  │  ★ 阶段一：读取蓝图 ★
  ├─ Android.bp ──┐
  ├─ Android.mk ──┤
  └─ 产品配置 ────┤
                  ▼
        soong (蓝图解析器 + 模块图)
                  │  生成 build.ninja（描述"谁依赖谁、怎么编译"）
                  ▼
        ninja（真正的执行引擎，并行跑命令）
                  │  调用 clang / aapt2 / dex2oat / jar / zip …
                  ▼
        中间产物 + 安装清单（out/target/product/<device>/）
                  │  build_image.py 按分区把文件打包
                  ▼
        system.img / vendor.img / product.img / …
                  │  fastboot flash
                  ▼
        设备闪存上的分区（只读 + dm-verity，见存储篇）
```

**怎么读这张图**（建议顺序）：

1. 先看**阶段一**——你写的 `Android.bp` 不是直接被编译器用的，而是先被 `soong` 收集成一棵"模块依赖图"。
2. 关键转折点是 **`build.ninja`**：它是 Soong 的产出、Ninja 的输入，是一份"编译计划书"。想看"某个模块到底会执行哪些命令"，就翻它。
3. 最后是 **`build_image.py`**——它按"分区归属规则"（第 7 章）把文件塞进对应 `.img`。预装 APK 没进镜像，十有八九是这一步的规则没配对。

「**Android 视角**」App 开发者几乎不用关心上面这条链；但做 ROM/vendor 的人，**每个环节都可能卡你**：`bp` 写错 → 模块不编；产品配置漏写 → 装不进镜像；分区归属错 → 刷进去起不来或被 verity 拦。

---

## 二、AOSP 目录结构与分层

理解构建系统，先要知道源码树里各目录"装什么"。下面这张表是你在 `repo sync` 之后每天打交道的全景。

| 顶层目录 | 装什么 | 你（vendor 开发者）关心的点 |
|---|---|---|
| `build/` | 构建系统本身：`build/soong`（新）、`build/make`（旧 kati/make）、`build/bazel`（实验） | **改构建规则、加 bp 模块类型都在这**；排查"为什么这个模块没被编"先来这 |
| `system/` | 原生的系统服务与核心 native 程序（init、logcat、bionic 部分、vold…） | init/property 相关源码在这（见文件 B）；很多核心 `.so` 来自这里 |
| `frameworks/` | Java Framework（`frameworks/base` 是 PMS/AMS/WMS 老家）+ native 框架 | App 能用的 API 从这来；车载定制常 overlay `frameworks/base` 的资源 |
| `packages/` | 系统 App（`Settings`、`SystemUI`、`Launcher3`…） | 想改/替换系统 App 在此；`packages/apps` 下放你自己的预装 App 蓝图 |
| `device/` | **设备专属配置**：`device/<vendor>/<product>/` | **你的主战场**：`device.mk`、`BoardConfig.mk`、rc 文件、overlay |
| `vendor/` | 芯片厂/BSP 闭源或半开源码（高通、MTK…）+ 厂商私有 | 与芯片厂交接的边界；很多 `PRODUCT_PACKAGES` 来自这里 |
| `hardware/` | HAL 接口定义与实现（老式 `hardware/libhardware`） | Treble 之前的 HAL 实现；现在多数移到 `vendor` 或 `hardware/interfaces` |
| `kernel/` | 设备内核（部分仓库单独管理） | 内核单独编，产物是 `boot.img` 里的 `Image`/`dtb` |
| `out/` | **所有构建产物**（编译缓存、镜像、安装清单） | 排查"文件最终落在哪"的唯一真相目录（第 11 章） |
| `prebuilts/` | 预编译工具链与二进制（clang、SDK、工具） | 一般不动；版本升级时可能要跟 |
| `art/` | ART 运行时（dex2oat、libart） | 影响 App 编译速度与 oat 产物 |
| `external/` | 第三方开源库（openssl、sqlite、protobuf…） | 升级依赖在此；CVE 修复常改这里 |

### 2.1 分层心智模型

把源码树想成"三层楼"：

```
┌ 平台层（platform）：build/ system/ frameworks/ art/ external/
│    → 所有设备共用，Google 维护为主
├ 设备层（device）：device/<vendor>/<product>/
│    → 你定"这台机器长什么样"：分区大小、预装谁、改哪些 overlay
└ 供应商层（vendor）：vendor/<chip>/  + 芯片厂闭源
     → HAL、驱动、BoardConfig 底层参数，与 BSP 强绑定
```

**关键认知**：`device/` 和 `vendor/` 是"配置密度最高"的区域。一个 ROM 项目的差异化（车机定制、客户 branding、预装清单）几乎全落在 `device/<vendor>/<product>/` 这一个目录里。本篇第 6、7、15 章都围绕它展开。

---

## 三、构建入口与流程：envsetup、lunch、soong_ui、ninja

### 3.1 标准开局三连

每次开新终端编系统，必须按顺序：

```bash
source build/envsetup.sh     # ① 注入 m/mm/mmm/lunch 等命令到当前 shell
lunch aosp_arm64-eng         # ② 选定产品 + 编译变体（product-variant）
m systemimage                # ③ 启动构建（实际调用 soong_ui → ninja）
```

**为什么不能 `./gradlew` 一把梭**：AOSP 没有现成的"可执行构建脚本"，必须先 `source envsetup.sh` 把一系列 shell 函数（本质是 `build/envsetup.sh` 里定义的 `function m(){}`）注入环境，否则 `m` 命令根本不存在。

`lunch` 的两个参数含义：

| 部分 | 例子 | 说明 |
|---|---|---|
| 产品名（product） | `aosp_arm64`、`yourcar_product` | 对应 `device/<vendor>/<product>` 或 `AndroidProducts.mk` 里定义的 `PRODUCT_NAME` |
| 变体（variant） | `eng`、`user`、`userdebug` | `eng` 带 root/debug；`user` 是发售版（无 root）；`userdebug` 介于两者之间，可 `adb root` |

### 3.2 m / mm / mmm / mma 的区别

| 命令 | 作用范围 | 典型用法 |
|---|---|---|
| `m <target>` | 全树指定目标（如 `m systemimage`） | 编整个系统镜像 |
| `mm` | 当前目录所在模块 | 在 `packages/apps/Settings` 里改完直接 `mm` |
| `mmm <path>` | 指定路径的模块（可多个） | `mmm frameworks/base` |
| `mma` | `mm` 的"连带依赖"版：把该模块**依赖的**模块也一起编 | 改了底层库后确保上游重编 |
| `mmma <path>` | `mmm` 的连带依赖版 | 同上，指定路径 |

「**Android 视角**」车机日常：改一个系统 App 用 `mm` 最快；改了共享 `.so` 后要 `mma` 或 `m` 全编，否则别的模块还链着旧库。

### 3.3 soong_ui 与 ninja 的关系（核心）

```
你敲 m systemimage
   └─> out/soong_ui  (soong_ui，构建前端)
         │ ① 调 soong_build：扫描全树 *.bp / *.mk，构建模块图
         │ ② 生成 out/soong/build.ninja   (主 ninja 文件)
         │ ③ 调 ninja -f out/soong/build.ninja 执行
         └─> ninja（并行执行每条编译命令，默认吃满 CPU）
```

- **Soong**：蓝图（`.bp`）的解析器 + 模块图求解器。它**不直接编译**，只负责把"蓝图语言"翻译成"Ninja 能懂的规则语言"。
- **Ninja**：极简、极快的构建执行器（和 Make 同类，但设计目标就是速度）。它读 `build.ninja`，并行跑命令。
- **kati**（旧）：把遗留 `Android.mk` 转成 ninja 规则的桥梁，逐步被 Soong 取代。

### 3.4 `out/soong/build.ninja` 是什么

它是 Soong 的**唯一核心产出**，是一份巨大的纯文本"编译计划书"：

```bash
# 大小常常数十 MB，行数上百万；不要 cat，用 grep 找目标
grep -n "my_module_name" out/soong/build.ninja | head
# 只看某个目标会执行哪些命令
ninja -t targets rules          # 列出规则类型
ninja -t query out/target/.../my.so   # 查某产物的依赖与命令
```

**排查价值**：当"某个模块到底有没有被编译、被编成了 `.so` 还是 `.a`、装到哪个路径"说不清时，`build.ninja` 是终极真相。但注意它每次 `m` 都会重新生成，属于**生成物，不要手改**。

### 3.5 版本差异时间线（必标）

| Android 版本 | 构建系统关键变化 | 影响 |
|---|---|---|
| ≤ 6.0 | 纯 GNU Make（`Android.mk` 一统天下） | 全树 Make，慢 |
| 7.0 (N) | 引入 Soong（实验），`Android.bp` 出现 | 新模块开始用 bp |
| 8.0 (O) | Soong 成为默认，逐步替代 Make | 大量模块迁到 bp |
| 9.0 (P) | `Android.mk` 仍可共存，但官方鼓励 bp | 双轨并行 |
| 10~13 | Soong 主导，`kati` 仅处理遗留 mk | 写新模块**一律用 bp** |
| 14+ | `build/bazel` 实验推进（部分模块 bp→bazel） | 未来可能换引擎，但 bp 仍主流 |

**结论**：你现在写新模块，**默认 `Android.bp`**；只有在接芯片厂遗留 `mk` 时才碰 `Android.mk`（第 5 章）。

---

## 四、Android.bp 语法全表

`Android.bp` 不是脚本，是**声明式蓝图（blueprint）**：你只描述"我是什么模块、依赖谁、编成什么"，具体怎么编译由 Soong 的内置规则决定。它**没有 if/for/函数调用**（那是 Make 的坏习惯），控制流靠 `defaults`、`select`、`soong_config`。

### 4.1 通用结构

```bp
// 注释用双斜杠；一个文件可含多个模块
cc_library {
    name: "libfoo",              // 模块名（唯一，别的模块靠它引用）
    srcs: ["a.c", "b.cpp"],      // 源文件列表
    shared_libs: ["libcutils"],  // 依赖的共享库
    export_include_dirs: ["include"],
}
```

### 4.2 C/C++ 模块：cc_binary / cc_library

| 模块类型 | 产出 | 场景 |
|---|---|---|
| `cc_binary` | 可执行文件（`/system/bin/xxx`） | native 守护进程、命令行工具 |
| `cc_library`（默认 shared） | `.so` 共享库 | 被多个模块动态链接 |
| `cc_library_shared` | 显式共享库 | 同上，语义更明确 |
| `cc_library_static` | `.a` 静态库 | 编进调用方，不单独存在 |
| `cc_library_headers` | 仅头文件库 | 只给别人提供头文件 |

**三种"库依赖"的区别**（高频坑）：

```bp
cc_library {
    name: "libbar",
    srcs: ["bar.cpp"],
    shared_libs: ["libcutils"],   // 运行时动态链接：编出 libson.so，运行时找 libcutils.so
    static_libs: ["libevent"],    // 编译期把 libevent 的代码直接打进本库（体积变大、无运行时依赖）
    header_libs: ["libbase_headers"], // 只依赖头文件（接口库），不产生链接
}
```

| 字段 | 链接时机 | 产物影响 | 何时用 |
|---|---|---|---|
| `shared_libs` | 运行时动态链接 | 单独 `.so`，体积共享 | 多模块共用、可独立更新 |
| `static_libs` | 编译期并入 | 调用方体积变大、无外部依赖 | 小工具、避免运行时找库 |
| `header_libs` | 仅编译期头文件 | 不产生链接 | 纯接口/头文件-only 库 |

### 4.3 Java / App 模块：android_app / android_library / android_app_import

```bp
// ① 普通系统 App（会编成 apk 并参与签名、预装）
android_app {
    name: "MySystemApp",
    srcs: ["java/**/*.java"],
    resource_dirs: ["res"],
    platform_apis: true,          // 用系统隐藏 API（@hide），需 platform 签名
    certificate: "platform",      // 用 platform 密钥签名（priv-app 常需要）
    privileged: true,             // 放进 /system/priv-app（见第 7 章）
    product_specific: true,       // 归属 product 分区（见第 7 章）
    required: ["my_native_dep"],  // 安装本 App 时连带安装依赖
}

// ② 仅库（被别的 android_app 依赖，不单独成 apk）
android_library {
    name: "mylib",
    srcs: ["src/**/*.java"],
    libs: ["framework"],          // 引用已存在的 java 库
    static_libs: ["androidx-foo"],
}

// ③ 预编译已存在的 apk（没有源码，只有 .apk 文件）
android_app_import {
    name: "PrebuiltApp",
    apk: "prebuilt/app.apk",
    privileged: false,
    presigned: true,              // 用 apk 原有签名（不重新签）
    product_specific: true,
}
```

| 类型 | 产出 | 何时用 |
|---|---|---|
| `android_app` | 带源码的 apk | 你自己写的系统 App |
| `android_library` | 中间 java 库（不参与预装） | 多个 App 共享的业务代码 |
| `android_app_import` | 预编译 apk（无源码） | 接第三方/芯片厂给的 apk |

### 4.4 prebuilt_etc：预编译配置文件

把任意文件（xml、conf、rc、json）装到系统指定路径：

```bp
prebuilt_etc {
    name: "my_config_xml",
    src: "config/myconfig.xml",
    sub_dir: "mycompany",         // 装到 /system/etc/mycompany/
    product_specific: true,       // 装到 /product/etc/... 而非 /system/etc
}
```

常见 `sub_dir` 与分区组合决定最终落点：`/system/etc/<sub_dir>`、`/vendor/etc/<sub_dir>` 等。预置 rc 文件常走 `prebuilt_etc` 进 `vendor/etc/init/`（文件 B 第 5 章会讲 rc 加载顺序）。

### 4.5 java_library / java_library_static

```bp
java_library {
    name: "mylib-host",           // 设备上用的 java 库
    srcs: ["src/*.java"],
    installable: true,
}
java_library_host {               // 仅编译期/主机端用（如构建工具）
    name: "gen-tool",
    srcs: ["tool/*.java"],
}
```

### 4.6 apex：APEX 模块（Android 10+）

APEX 是把"可更新系统组件"打包成一种特殊模块（第 10 章详述）：

```bp
apex {
    name: "com.android.myapex",
    manifest: "manifest.json",    // 声明 packageName、version
    native_shared_libs: ["libfoo"],
    binaries: ["mybin"],
    updatable: true,              // 允许通过 Mainline 更新
    key: "myapex.key",            // 签名密钥
    certificate: "myapex.cert",
}
```

### 4.7 defaults：复用一组属性（替代 Make 的变量/继承）

```bp
// 定义一个"默认值集合"
cc_defaults {
    name: "my_common",
    cflags: ["-Wall", "-Werror"],
    shared_libs: ["liblog"],
    sanitize: { address: true },
}

cc_library {
    name: "libfoo",
    defaults: ["my_common"],      // 继承上面所有属性，可再覆盖
    srcs: ["foo.cpp"],
}
```

**价值**：车机项目里常有一个 `car_defaults` 统一定义车规编译开关，各模块 `defaults: ["car_defaults"]` 即可。

### 4.8 visibility：可见性控制（防止误依赖）

```bp
cc_library {
    name: "libinternal",
    visibility: [
        "//packages/apps/MyApp",  // 只允许这个模块引用
        "//system/core:__subpackages__", // 允许 system/core 子包
    ],
}
```

不写 `visibility` 时，默认行为受 `package` 声明的 `default_visibility` 约束。这是避免"应用层直接链系统内部库"的护栏。

### 4.9 select：按条件选值（替代 if）

`select` 根据**构建变量**（如 `android_arch`、自定义 `soong_config_variable`）选择不同值：

```bp
cc_library {
    name: "libarch",
    srcs: select({
        "android_arm64": ["arm64.cpp"],
        "android_x86_64": ["x86_64.cpp"],
        "conditions_default": ["generic.cpp"],
    }),
}
```

### 4.10 soong_config：编译期特性开关（NDK/芯片厂常用）

```bp
// 在 Android.bp 里声明一个可配置变量
soong_config_string_variable {
    name: "board_arch",
}
// 模块按变量取值选择行为
cc_library {
    name: "libvariant",
    soong_config_variables: {
        board_arch: {
            conditions: {
                "variant_a": { srcs: ["a.cpp"] },
                "variant_b": { srcs: ["b.cpp"] },
            },
            conditions_default: { srcs: ["default.cpp"] },
        },
    },
}
```

**与 select 的区别**：`select` 基于 Soong 内置/arch 变量；`soong_config` 是基于 `SOONG_CONFIG_*` 环境变量或 `BoardConfig` 注入的**产品自定义开关**——芯片厂用它区分不同板型。`androidOthers/NDK` 篇会进一步讲 VNDK 与这类开关的耦合。

---

## 五、Android.mk 与遗留模块

### 5.1 为什么还要懂

虽然新模块一律 `Android.bp`，但**芯片厂 BSP、闭源 HAL、大量 legacy 驱动仍用 `Android.mk`**。而且 Soong 通过 `kati` 把 mk 转成 ninja，所以 mk 模块依然能编。你接 BSP 时大概率要读/改 mk。

### 5.2 与 bp 的互操作

```bp
// Android.bp 里可以"引用"一个 mk 定义的模块——只要那个 mk 模块有唯一 name
cc_library {
    name: "libnew",
    shared_libs: ["liblegacy"],   // liblegacy 在 Android.mk 里定义，照样能链
}
```

Soong 会先让 kati 处理所有 `Android.mk`，把它们的模块名暴露给 bp 侧。**注意**：mk 不能引用用 bp 的 `defaults`（那是 bp 专属概念），复杂继承在 mk 侧用 `include` + 变量模拟。

### 5.3 预编译 APK 的几种写法对比

| 写法 | 模块类型 | 签名 | 适用 |
|---|---|---|---|
| `android_app_import` + `presigned: true` | bp | 保留原签名 | 第三方 apk，不重签 |
| `android_app_import` + `certificate: "platform"` | bp | 用平台密钥重签 | 要进 priv-app、需系统权限 |
| `Android.mk` 的 `BUILD_PREBUILT` | mk | 取决于 `LOCAL_CERTIFICATE` | 接 BSP 遗留 |
| `PRODUCT_PACKAGES += <apk模块名>` | 产品配置 | 由模块定义决定 | 决定"是否装进镜像" |

**关键区分**：模块定义决定"apk 长什么样、怎么签"；`PRODUCT_PACKAGES` 决定"它会不会进镜像"（第 6、7 章）。两者缺一不可——定义了不加入 `PRODUCT_PACKAGES`，镜像里就没有它。

---

## 六、产品配置三件套

`device/<vendor>/<product>/` 下最核心的三个文件，决定"这台机器到底包含什么"。

### 6.1 AndroidProducts.mk——产品清单

```makefile
PRODUCT_MAKEFILES := \
    $(LOCAL_DIR)/my_product.mk          # 指向具体的产品定义

# 多产品时这里列多个；lunch 菜单读的就是这里
```

它只做一件事：**登记有哪些产品**，每个产品对应一个 `xxx.mk`（下面要定义 `PRODUCT_NAME`）。

### 6.2 device.mk——功能与包清单（最常用）

这是你改得最多的文件，决定预装哪些模块、拷哪些文件、overlay 哪些资源：

```makefile
# ① 继承一个基础产品（必须，否则缺核心配置）
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit.mk)

# ② 预装模块：写进镜像的关键！
PRODUCT_PACKAGES += \
    MySystemApp \
    libfoo \
    PrebuiltApp

# ③ 拷贝文件到镜像（支持分区前缀，见第 7 章）
PRODUCT_COPY_FILES += \
    $(LOCAL_DIR)/config/init.my.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/init.my.rc

# ④ 资源覆盖（静态 overlay，见第 8 章）
PRODUCT_PACKAGE_OVERLAYS := \
    $(LOCAL_DIR)/overlay

# ⑤ 属性覆盖（产出 build.prop，见文件 B 第 5 章）
PRODUCT_PROPERTY_OVERRIDES += \
    ro.product.brand=MyCar \
    persist.demo.mode=0

# ⑥ 设备形态特性
PRODUCT_CHARACTERISTICS := automotive,tablet   # 影响 Google 服务的可用能力
PRODUCT_AAPT_CONFIG := mdpi hdpi xhdpi         # 资源密度偏好
PRODUCT_LOCALES := zh_CN en_US
```

### 6.3 BoardConfig.mk——板级参数（最底层）

决定**硬件相关**的编译参数、分区大小、架构：

```makefile
TARGET_ARCH := arm64
TARGET_ARCH_VARIANT := armv8-a
TARGET_BOARD_PLATFORM := mychip            # 芯片平台标识
BOARD_SYSTEMIMAGE_PARTITION_SIZE := 4294967296   # system 分区 4GB
BOARD_VENDORIMAGE_PARTITION_SIZE := 1073741824
BOARD_SUPER_PARTITION_SIZE := 6442450944   # super 容器大小（动态分区）
BOARD_FLASH_BLOCK_SIZE := 4096
TARGET_USERIMAGES_USE_F2FS := true         # userdata 用 f2fs
BOARD_AVB_ENABLE := true                   # 开启 AVB 校验（verity）
```

**三件套分工一句话**：
- `AndroidProducts.mk`：有哪些产品（菜单）。
- `device.mk`：这台机器**装什么 App、拷什么文件、改什么配置**（功能层）。
- `BoardConfig.mk`：这台机器**硬件参数与分区大小**（板层）。

### 6.4 高价值变量速查

| 变量 | 作用 | 常见误用 |
|---|---|---|
| `PRODUCT_PACKAGES` | 把模块编进镜像 | 漏写 → 模块没进镜像（第 14 章首条） |
| `PRODUCT_COPY_FILES` | 拷贝文件到指定分区路径 | 路径写错分区前缀 → 文件装错地方 |
| `PRODUCT_PACKAGE_OVERLAYS` | 静态覆盖资源 | 路径不存在 → overlay 静默失效 |
| `PRODUCT_PROPERTY_OVERRIDES` | 注入系统属性（build.prop） | 与 `system.prop` 冲突时后者优先 |
| `PRODUCT_CHARACTERISTICS` | 设备形态（automotive 等） | 漏写 automotive → 车机特性缺失 |
| `PRODUCT_COPY_FILES` 的分区前缀 | `$(TARGET_COPY_OUT_VENDOR)` 等 | 不懂前缀 → 文件进错分区（第 7 章） |

「**Android 视角**」车机项目里，`device.mk` 往往几百行，是"客户定制清单"的集中地（第 15 章）。

---

## 七、分区归属规则专题（重点：预装 APK 的坑）

**这是整篇最高价值的章节**。用户在预装 APK 时踩过的坑，本质都是"文件装错了分区"或"特权白名单没随包一起生效"。先回看存储篇的分区全景：系统分区只读且 dm-verity 保护，所以**"东西放哪个分区"直接决定了它能不能被改、能不能过 verity、能不能被 PMS 正确识别**。

### 7.1 各分区装什么

| 分区 | 挂载点 | 该放什么 | 不该放什么 |
|---|---|---|---|
| `system` | `/system` | AOSP 核心框架、核心 App、原生工具 | 厂商定制、闭源 HAL |
| `system_ext` | `/system_ext` | 系统级扩展（部分 OEM 功能） | 与芯片强绑定的东西 |
| `product` | `/product` | **厂商/产品预装 App、配置**（车机定制常在这） | 芯片私有库（应进 vendor） |
| `vendor` | `/vendor` | HAL、驱动、芯片厂闭源库 | 上层 App、Framework 代码 |
| `odm` | `/odm` | **ODM 定制**（车机厂商独立定制层） | 芯片通用部分 |
| `data` | `/data` | 用户数据、运行时安装 | 出厂预置（会被格式化） |

**铁律**：与芯片强相关的东西进 `vendor`；与"这台具体车机/OEM 产品"相关的进 `product` 或 `odm`；纯 AOSP 系统进 `system`。放错分区会导致：Treble 校验失败（vendor 里混进 framework 类）、verity 拒绝（改了只读区）、PMS 扫描不到（priv-app 放错位置）。

### 7.2 PRODUCT_PACKAGES 的后缀变体

`PRODUCT_PACKAGES` 决定"模块进镜像"，但它有个**隐藏的分区路由机制**——靠模块自身的 `product_specific` / `vendor` / `system_ext_specific` 等属性决定落点。等价地，也可以直接写带命名空间的方式（不同版本语法略有差异）：

```makefile
PRODUCT_PACKAGES += MySystemApp          # 默认进 system（除非模块声明了分区属性）
PRODUCT_PACKAGES += MyProductApp         # 模块里写 product_specific: true → 进 product
PRODUCT_PACKAGES += MyVendorLib          # 模块里写 vendor: true → 进 vendor
```

对应的 `Android.bp` 分区属性：

```bp
android_app {
    name: "MyProductApp",
    product_specific: true,   // ← 决定落 product 分区
}
cc_library {
    name: "MyVendorLib",
    vendor: true,             // ← 决定落 vendor 分区（Treble 要求 vendor 模块独立）
}
```

**版本差异**：
- Android 8~9：分区归属靠 `LOCAL_MODULE_PATH` / `PRODUCT_PACKAGES` 约定，规则较松。
- Android 10（引入动态分区 + product 独立）：`product_specific`、`system_ext_specific` 成为明确语法。
- Android 11+：`vendor` 模块必须真正隔离（VNDK 强制），混放会在 `make`/`vintf` 校验报错。

### 7.3 priv-app 与 privapp-permissions 白名单

**预装一个需要系统级权限的 App（如设置、系统 UI、车机核心服务），必须放进 `/system/priv-app`（或对应分区的 priv-app）**，否则 `signature|privileged` 权限申请会被拒。

```bp
android_app {
    name: "MyPrivApp",
    privileged: true,          // ← 关键！装进 *priv-app 目录
    certificate: "platform",   // priv-app 几乎都要 platform 签名
    product_specific: true,    // 若在 product 分区，则落 /product/priv-app
}
```

但光放进去还不够——Android 对 priv-app 有**白名单机制**：priv-app 申请的每一个 `privileged` 权限，必须在 `privapp-permissions-*.xml` 里显式授权，否则该权限被静默剥夺（不会崩溃，只是用不了）。

```xml
<!-- 文件：privapp-permissions-myapp.xml，需随包一起打包到 -->
<!-- /system/etc/permissions/ 或对应分区 etc/permissions/ -->
<permissions>
    <privapp-permissions package="com.mycompany.myapp">
        <permission name="android.permission.READ_PRIVILEGED_PHONE_STATE"/>
        <permission name="android.permission.CONTROL_INCALL_UI"/>
    </privapp-permissions>
</permissions>
```

**打包进镜像的方式**（两种方式任选）：

```makefile
# 方式一：用 prebuilt_etc 模块声明，再 PRODUCT_PACKAGES 加入
# Android.bp:
prebuilt_etc {
    name: "privapp-permissions-myapp",
    src: "privapp-permissions-myapp.xml",
    sub_dir: "permissions",       # 落 etc/permissions/
    product_specific: true,       # 与 App 同分区！
}
# device.mk:
PRODUCT_PACKAGES += privapp-permissions-myapp
```

**关键坑**：白名单文件必须与 App **同分区**（都在 product 或都在 system）。跨分区时 PMS 扫描不到，权限照样被剥夺。呼应 `androidFrameworks/04_PMS`：PMS 启动时按分区加载 `etc/permissions`，priv-app 权限校验在这完成。

### 7.4 dm-verity 与只读分区的关系（呼应存储篇）

存储篇讲过：system/vendor/product 是只读 + dm-verity 块哈希校验。构建侧的对应动作是：

1. 构建时为每个只读分区生成 **verity 树 + vbmeta 签名**（`BOARD_AVB_ENABLE := true`）；
2. 刷机后 bootloader 用 vbmeta 验签，内核用 verity 校验每块；
3. **后果**：你在 `device.mk` 里加的文件、改的配置，必须经过"重新构建镜像"才会被 verity 认可。`adb push` 直接改只读分区（即使 remount）在 OTA/重刷后会丢，且不被 verity 信任。

所以正确姿势永远是一句：**改 `device.mk` / `PRODUCT_COPY_FILES` → 重新 `m` 出镜像 → 刷机**，而不是依赖运行时改只读区。

### 7.5 一招查"某个文件最终进了哪个分区"

```bash
# 编完后查安装清单（每个模块的安装路径都在这里，最权威）
grep -rn "myapp.apk" out/target/product/<device>/installed-files.txt
# 直接看某分区的文件列表
ls out/target/product/<device>/system/priv-app/
ls out/target/product/<device>/product/app/
```

`installed-files.txt` 是"模块名 → 绝对安装路径（含分区）"的完整映射，排"模块没进镜像/进错分区"必看（第 11 章再展开）。

---

## 八、RRO 与 Overlay

"改系统 UI / 改默认配置"有两种姿势，选错会很痛苦。

### 8.1 静态 Overlay（构建期，PRODUCT_PACKAGE_OVERLAYS）

```makefile
# device.mk
PRODUCT_PACKAGE_OVERLAYS := $(LOCAL_DIR)/overlay
```

目录结构按"被覆盖的包路径"组织：

```
overlay/
 └─ frameworks/base/core/res/      # 覆盖 framework 的资源
      └─ res/values/config.xml      # 同名资源会被优先采用
```

**特点**：编译期就把资源合进去，镜像里只有一份最终资源，**不可运行时切换**，但零运行时开销。适合车机"出厂就定死的品牌主题、默认配置"。

### 8.2 运行时 RRO（Runtime Resource Overlay，Android 5+）

RRO 是独立安装的 overlay 包，运行时由 `OverlayManagerService`（OMS）动态启用/禁用：

```bp
// overlay 包本身也是一个 android_app，标记为 resource-only
android_app {
    name: "MyThemeOverlay",
    resource_dirs: ["res"],
    android_manifest: "AndroidManifest.xml",   // 里声明 targetPackage + overlay
    // 无需源码、无需 activity
}
```

```xml
<!-- AndroidManifest.xml：声明"我覆盖谁" -->
<manifest package="com.mycompany.theme">
    <application android:hasCode="false" />
    <overlay android:targetPackage="com.android.systemui"
             android:targetName="SysuiDarkTheme"
             android:priority="10" />
</manifest>
```

```bash
# 运行时启用/禁用（无需重刷）
cmd overlay enable com.mycompany.theme
cmd overlay disable com.mycompany.theme
# 也可用 OverlayManager API（fabricate overlay，Android 11+ 支持代码动态创建）
```

**overlayable（Android 11+）**：被覆盖的 App 必须显式声明"哪些资源允许被 overlay"（`overlayable` 标签），否则 RRO 改不动它。这是安全约束——防止任意 overlay 篡改系统资源。

### 8.3 两种方式的取舍

| 维度 | 静态 Overlay | 运行时 RRO |
|---|---|---|
| 生效时机 | 编译期，随镜像 | 运行时，可热切换 |
| 是否需要重刷 | 是 | 否（装 overlay 包即可） |
| 能否按场景切换 | 否 | 能（白天/黑夜主题、不同车型） |
| 性能开销 | 零 | 极小（OMS 解析） |
| 适用 | 出厂固定品牌、默认配置 | 多主题、客户可切换、车机多车型共用一套镜像 |
| 车机推荐 | 基础 branding | **多车型/多客户差异化**首选 |

「**Android 视角**」车机常玩"一套镜像 + 多个 RRO"：基础系统相同，不同客户/车型用不同 RRO 切主题与默认配置，避免为每个客户单独编镜像。静态 overlay 适合"永远不变"的东西。

---

## 九、Treble 与 VNDK/LLNDK 的构建侧约束

Treble（Android 8 引入）的核心是**解耦 framework 与 vendor**：vendor 实现 HAL，framework 通过稳定接口（HIDL/AIDL）调用，两边可独立更新。构建侧因此有了硬性约束。

### 9.1 VNDK（Vendor NDK）是什么

VNDK 是一组"vendor 模块允许链接、且保证跨版本稳定"的系统库集合。构建时：

- `vendor` 分区的库**只能**链接 VNDK 白名单里的 `system` 库，不能随便链 framework 内部库；
- 违反则 `make` 阶段报 `error: VNDK` 相关（库不在 VNDK 集）；
- 目的是：系统升级时 framework 库变了，vendor 仍能用旧版 VNDK 跑，不崩溃。

### 9.2 LLNDK（Low Level NDK）

比 VNDK 更底层、更稳定的库（libc、libm、libdl 等），framework 和 vendor **共用同一份**，版本必须一致。

### 9.3 构建侧怎么标

```bp
cc_library {
    name: "libmyhal",
    vendor: true,                 // 声明这是 vendor 模块
    shared_libs: [
        "libc",                   // LLNDK：允许
        "libbase.vndk",           // VNDK：允许（带 .vndk 后缀标识）
        // "libframeworkinternal" ← 私有库，vendor 链它会报 VNDK 错
    ],
}
```

**版本化差异**：
- Android 8.0：Treble 首次引入，VNDK 初版；
- Android 9：VNDK 强制化，引入 `BOARD_VNDK_VERSION`；
- Android 10+：动态分区 + VNDK 版本绑定更严格，`/vendor` 与 `/system` 的 VNDK 版本必须匹配，否则 boot 失败。

衔接 `androidOthers/NDK` 篇：NDK 是 App 侧的稳定 C/C++ 接口；VNDK/LLNDK 是**系统内部**的等效稳定接口，面向 vendor 实现者。两者理念一致——"稳定接口隔离变化"。

---

## 十、APEX 与 Mainline

### 10.1 APEX 是什么（Android 10 引入）

APEX 是"可更新的系统组件包"，把原本在 `/system` 里、随整包 OTA 才能更新的模块（如 ART、运行时库、部分服务），**独立成一种签名容器**，可通过 Google Play（Mainline）单独更新，无需整机 OTA。

```bp
apex {
    name: "com.android.runtime",
    manifest: "manifest.json",   // { "name": "...", "version": 1 }
    native_shared_libs: ["libart"],
    updatable: true,              // 允许 Mainline 更新
    key: "com.android.runtime.key",
}
```

### 10.2 与 system.img 的关系

- APEX **不进** `system.img` 的普通目录，而是生成 `.apex` 文件，刷进 `/system/apex` 或独立 `apex` 分区；
- 开机时由 `apexd` 解压挂载成可读写的 loop 设备，内容"覆盖"对应系统路径；
- 所以"同一个库到底用的是 system 里的还是 apex 里的"要看 `apexd` 状态（排查用 `apexdstatus` / `dmctl`）。

### 10.3 版本约束（updatable）

- 标记 `updatable: true` 的 APEX 受 Mainline 版本约束：**设备预装的 APEX 版本不能高于框架预期**，否则启动受阻；
- 自研 APEX 若不想被 Mainline 覆盖，可设 `updatable: false`（厂商锁定）；
- 调试：`adb shell pm list packages | grep apex`、`adb shell getprop | grep apex`。

「**Android 视角**」车机项目常把自研核心服务打成 `updatable: false` 的 APEX，避免被运营商/Google 更新破坏车规行为。

---

## 十一、产物与镜像

### 11.1 out/target/product/<device>/ 里有什么

```bash
out/target/product/<device>/
 ├─ system.img / vendor.img / product.img   ← 各分区镜像（sparse 或 raw）
 ├─ system/  vendor/  product/             ← 各分区的"解包后目录"（镜像的来源）
 ├─ obj/                                     ← 编译中间产物（.o、.so、classes）
 ├─ symbols/                                 ← 带符号的二进制（native 崩溃符号化用）
 ├─ installed-files.txt                      ← 模块 → 安装路径映射（第 7.5 用过）
 ├─ apex/                                    ← 生成的 .apex 文件
 ├─ boot.img / vendor_boot.img               ← 内核 + ramdisk
 └─ super.img                                ← 动态分区容器（含 system/vendor/product）
```

### 11.2 sparse image 与 super.img

- **raw image**：可直接 `mount -o loop` 挂载的普通文件系统镜像；
- **sparse image**：把大量全零块压缩掉，体积更小、刷写更快，**不能直接 mount**，需 `simg2img` 转换；
- **super.img**：动态分区容器（Android 10+），里面含多个逻辑分区（第 3 章存储篇讲过）。刷写要用 `fastboot flash super` 或进 `fastbootd`。

```bash
# 看 super 里有哪些逻辑分区
adb shell lpdump
# 把 sparse 转 raw 以便本地查看
simg2img system.img system.raw.img
mkdir /tmp/sys && sudo mount -o loop system.raw.img /tmp/sys
```

### 11.3 installed-files.txt 怎么读

```text
# 格式：<大小>\t<路径（相对分区根）>\t<模块/source>
4096    /system/priv-app/MyApp/MyApp.apk    MyApp
```

排查"模块最终落在哪个镜像/路径"的三板斧：

```bash
grep "MyApp.apk" out/target/product/<device>/installed-files.txt  # 落点
grep -rl "MyApp" out/target/product/<device>/system/              # 目录里找
ls out/target/product/<device>/product/priv-app/                  # 直接看 product 分区
```

「**Android 视角**」如果 `installed-files.txt` 里根本没有你的模块，说明 `PRODUCT_PACKAGES` 没加或模块 `enabled` 被条件关闭——问题在配置，不在镜像。

---

## 十二、刷机与快速验证

### 12.1 标准 fastboot 刷写

```bash
fastboot flash boot boot.img              # 内核 + ramdisk
fastboot flash vendor_boot vendor_boot.img
fastboot flash system system.img          # 注意：动态分区设备可能要 flash super
fastboot flash vendor vendor.img
fastboot flash product product.img
fastboot flash super super.img            # 动态分区方式（推荐）
fastboot reboot
```

**A/B 设备注意**：`fastboot flash system` 实际写的是当前空闲槽，刷完要确认下次从哪槽启动（`fastboot set_active a/b`）。`fastboot getvar current-slot` 看当前槽。

### 12.2 免重刷的快速验证

改了只读分区内容想立刻看效果，可临时关 verity（仅调试机）：

```bash
adb root
adb disable-verity        # 写 vbmeta，标记关闭校验
adb reboot
adb root
adb remount               # 现在 /system、/product 可写
adb push myfile /system/etc/myfile     # 直接推
adb shell restorecon -R /system/etc    # 修 SELinux 上下文（否则可能访问被拒）
adb reboot
```

**重要**：`disable-verity` + `remount` 是**调试手段，不是交付方式**。它会在 OTA/重刷后丢失，且不被 verity 信任（存储篇第 3.4 节）。正式改必须用 `PRODUCT_COPY_FILES` 编进镜像。

### 12.3 adb sync 与直接 push

```bash
adb sync system            # 把 out/.../system/ 的内容同步到设备 /system（需 remount）
adb sync data              # 同步 /data 侧
# 改单个 native 二进制可只 push 对应文件，省去整镜像重刷
adb push out/target/.../mybin /system/bin/mybin
```

**节奏建议**：native 小改 → `mm` + `adb push` 单文件验证最快；App/资源/配置 → 多半要重刷对应镜像（因为还要过签名/overlay/PMS 扫描）。

---

## 十三、编译效率

### 13.1 ccache

```bash
export USE_CCACHE=1
export CCACHE_DIR=/path/to/ccache
prebuilts/misc/linux-x86/ccache/ccache -M 50G   # 设缓存上限
```

`ccache` 缓存编译产物哈希，二次编译命中缓存可快数倍。**服务器多用户共用一份 ccache 最划算**。

### 13.2 增量编译

- `mm` / `mmm` 只编改动模块，远快于 `m`；
- `ninja` 天生增量（只重编依赖链变化的文件）；
- 改了头文件 → 所有包含它的重编（无法避免），所以减少不必要的全局头依赖。

### 13.3 m nothing 与排查"全量重编"

```bash
m nothing     # 只解析蓝图、生成 ninja，不真正编译——用来快速验证 bp/mk 语法错误
```

**常见"全量重编"误操作**：

| 误操作 | 后果 | 对策 |
|---|---|---|
| 改了 `BoardConfig.mk` 的分区大小 | 触发大量重编 | 分区参数定稿后再改；或用 `m nothing` 先验证 |
| `touch` 了被广引用的头文件 | 全军覆没重编 | 避免大范围 include 改动 |
| 清了 `out/` 或 ccache | 全量从头 | 别随便 `rm -rf out`；ccache 留着 |
| 切了 lunch 变体（eng↔user） | 基本全量 | 确认好变体再编 |
| `OUT_DIR` 指错目录 | 找不到缓存，全量 | 固定 OUT_DIR |

「**Android 视角**」车机整编一次常 1~数小时，养成"先 `m nothing` 验证配置、再 `mm` 局部、最后才全编"的习惯，能省大量时间。

---

## 十四、常见错误排查表

| 现象 | 最可能的原因 | 章节 | 第一命令 |
|---|---|---|---|
| 模块没进镜像 | `PRODUCT_PACKAGES` 漏写 / 模块 `enabled` 被关 | 6.2、7.2 | `grep 模块名 out/.../installed-files.txt` |
| overlay 不生效 | `PRODUCT_PACKAGE_OVERLAYS` 路径错 / 资源名不匹配 / Android 11+ 未声明 overlayable | 8.1 | `ls $(LOCAL_DIR)/overlay` 核对结构 |
| ninja missing dependency | bp 里漏声明 `shared_libs`/`srcs` | 4.2 | `ninja -t query out/.../目标` |
| APK 未签名 / 签名错 | `certificate` 没设或 priv-app 没 platform 签名 | 4.3、7.3 | `adb shell pm dump <pkg> \| grep sig` |
| PRODUCT_PACKAGES 写错名字 | 模块名拼错或被条件排除 | 6.2 | `grep -rn "name: \"模块\"" 源码树` |
| prebuilt 权限不对（不可执行） | bp/mk 未声明 `executable` / 未设 `user` 可执行 | 4.4 | `adb shell ls -l /system/bin/xxx` |
| apex 校验失败 / 启动卡住 | `updatable` 版本高于框架预期 / 缺 key | 10.3 | `adb shell getprop \| grep apex` |
| SELinux 编译报错（neverallow） | 模块新增了被策略禁止的域/权限 | 7.3（呼应 SELinux 篇） | `m sepolicy` 看具体 neverallow |
| 产物没更新（push 无效） | 改的是源码但没 `mm`，或缓存未清 | 13.2 | `mm <模块> && adb push ...` |
| 文件装错分区 | `PRODUCT_COPY_FILES` 前缀用错 / bp 分区属性错 | 7.1、7.2 | `grep 文件名 installed-files.txt` |
| priv-app 权限被剥夺 | `privapp-permissions` 白名单漏写或跨分区 | 7.3 | `adb shell dmesg \| grep privapp` |
| VNDK 报错（vendor 链私有库） | vendor 模块链了非 VNDK 的 system 库 | 9.1 | `grep VNDK out/.../build.ninja` |
| build.ninja 没生成 | bp 语法错 / `m nothing` 先报 | 3.4 | `m nothing` |
| lunch 找不到产品 | `AndroidProducts.mk` 没登记 | 6.1 | `ls device/<vendor>/<product>/*.mk` |
| 刷完不开机（verity） | 改了只读分区未重编镜像 / 跨槽 | 7.4、12.1 | `fastboot getvar current-slot` |
| OTA 后属性/配置丢失 | 写在 `data` 而非系统分区 / persist 用法错 | 7.1（呼应存储篇） | `adb shell getprop ro.xxx` |
| 重编极慢疑似全量 | 误改 BoardConfig / 清了缓存 | 13.3 | `m nothing` 先验证 |

---

## 十五、车机 vendor 视角

### 15.1 device/<vendor>/<product> 的组织方式

一个典型车机项目的 `device/` 布局：

```
device/<vendor>/<product>/
 ├─ AndroidProducts.mk        # 产品菜单
 ├─ <product>.mk              # 主 device.mk（继承基础 + 加定制）
 ├─ BoardConfig.mk            # 板级参数
 ├─ overlay/                  # 静态资源覆盖
 ├─ config/                   # 预置 xml/conf/rc
 ├─ init.<product>.rc         # 本机 init 脚本（文件 B 会讲）
 ├─ sepolicy/                 # 自定义 SELinux 策略
 └─ prebuilts/                # 第三方预编译 apk/库
```

### 15.2 与 BSP/芯片厂交接的边界

| 你（OEM/ODM）负责 | 芯片厂（BSP）负责 |
|---|---|
| `device/<vendor>/<product>` 全部 | `vendor/<chip>/` 的 HAL、驱动、BoardConfig 基础 |
| 预装 App、主题、默认配置 | 底层音视频/相机/显示 HAL 实现 |
| 车机专属服务（rc + native） | 内核、bootloader、基带 |
| 客户定制项（branding） | SoC 能力上限 |

**交接边界清晰原则**：芯片厂给的 `vendor/<chip>` 当作黑盒，**只通过 `PRODUCT_PACKAGES` 引用它的产物、通过 `BoardConfig` 继承它的参数**，不随便改它内部。车机差异化的代码尽量收敛在 `device/<vendor>/<product>`。

### 15.3 客户定制项该放哪一层

| 定制内容 | 推荐落点 | 理由 |
|---|---|---|
| 品牌主题/Logo | 静态 overlay 或 RRO | 出厂固定用 overlay，多客户用 RRO |
| 预装客户 App | `product` 或 `odm` 分区 | 与芯片解耦，易裁剪 |
| 车机专属 native 服务 | `vendor` 或 `odm` | 贴近 HAL，启动早 |
| 默认系统配置（属性） | `PRODUCT_PROPERTY_OVERRIDES` | 进 build.prop，呼应文件 B |
| 标定/序列号 | `persist`（运行时）或厂商分区 | 不被 userdata 格式化（存储篇 15.2） |

---

## 十六、读源码路线 + 源码路径速查表

### 16.1 读源码路线

1. **先读 `build/soong` 的 README 与 `androidmk`/`bp2build`**，建立"蓝图→ninja"的整体认知；
2. **跟一条模块**：挑一个你熟悉的 `Android.bp`（如 `packages/apps/Settings`），`m nothing` 后去 `out/soong/build.ninja` 找它的规则，看它如何被编；
3. **读产品配置链路**：`device.mk` → `inherit-product` 链 → `PRODUCT_PACKAGES` 如何影响镜像；
4. **读镜像打包**：`build/make/core/Makefile` + `build/tools/` 里的 `build_image.py`。

### 16.2 源码路径速查表

| 内容 | 路径 |
|---|---|
| Soong 主逻辑 | `build/soong/` |
| 蓝图解析/模块图 | `build/soong/android/`、`build/soong/bp2build/` |
| 旧 Make/kati | `build/make/`、`build/kati/` |
| cc 模块类型实现 | `build/soong/cc/` |
| java/app 模块类型 | `build/soong/java/` |
| apex 模块 | `build/soong/apex/` |
| 产品配置核心 | `build/make/core/product_config.mk`、`core/Makefile` |
| 镜像打包脚本 | `build/tools/releasetools/`、`system/extras/partition_tools/` |
| VNDK 定义 | `build/soong/vndk/`、`development/vndk/` |
| envsetup / lunch | `build/envsetup.sh` |

---

## 十七、一图总结

```
┌ 源码形态 ───────────────────────────────────────────────────────────┐
│  Android.bp / Android.mk + device.mk/BoardConfig.mk（声明式蓝图）     │
├ Soong 求解 ──────────────────────────────────────────────────────────┤
│  soong_build 扫描全树 → 模块依赖图 → 生成 out/soong/build.ninja       │
├ Ninja 执行 ──────────────────────────────────────────────────────────┤
│  ninja 并行调 clang/aapt2/dex2oat → .so/.apk/.jar 中间产物           │
├ 分区打包 ────────────────────────────────────────────────────────────┤
│  build_image.py 按"分区归属规则"塞进 system/vendor/product… → *.img  │
│  （priv-app 需白名单、verity 签名、VNDK 隔离在此约束）               │
├ 烧录运行 ────────────────────────────────────────────────────────────┤
│  fastboot flash → 闪存只读分区（dm-verity）→ init 启动（见 01 篇）   │
└──────────────────────────────────────────────────────────────────────┘

记忆链：
  蓝图(bp) → 计划书(build.ninja) → 二进制(.so/.apk) → 镜像(.img) → 闪存分区
  决定"装哪/进不进镜像"的是 PRODUCT_PACKAGES + 分区属性（第 6、7 章）
  决定"长什么样/怎么签"的是模块定义（第 4、5 章）

版本一句话：Make(≤6) → Soong 引入(7) → 默认(8) → bp 主导(10+) → bazel 实验(14+)
分区一句话：system(核心) / vendor(芯片) / product·odm(厂商定制) / data(用户) 各司其职
车机三件事：device/ 收敛差异化、RRO 做多客户、属性与预置走系统分区才持久
```

*关联阅读（同库）：`androidFrameworks/01_Android启动流程详解`（init 怎么把本篇的镜像跑起来）、`androidFrameworks/04_PMS`（priv-app 白名单如何被扫描生效）、同目录《Android 存储机制详解》（只读分区与 dm-verity 的来龙去脉）、`androidOthers/NDK`（VNDK/LLNDK 的稳定接口理念）、`androidApp/11` 与 `androidApp/15`（App 侧 Gradle 构建与本篇的对照）。*
