# 11 Gradle 构建与工程化：构建的心智模型，而不是 DSL 速查

> 本篇定位：讲清"一次构建里到底发生了什么"，建立任务图、变体、依赖解析、加速与治理的心智模型。
> 逐文件、逐 block 的 DSL 语法交给姊妹篇 `15-App编译配置文件详解.md`；Gradle Kotlin DSL 基础交给 `kotlin/kotlin-GradleKotlinDSL入门.md`。本篇不重复 DSL 行级讲解，只讲"为什么这么配、配错会怎样、怎么查"。
> 读者设定：会写 Kotlin、做过 Android/车机 App，但没系统想过构建流水线。中文为主，术语保留英文。

---

## 1. 开场：构建在回答哪三个问题

每次敲下 `./gradlew :app:assembleDebug`，Gradle 其实在替你回答三个问题。先记住这三个问题，后面所有章节都是它们的展开。

| 问题 | 含义 | 对应章节 |
|---|---|---|
| **① 源码怎么变成 APK/AAB？** | 编译、资源、dex、打包、签名谁先谁后 | 第 2 章（三阶段）、第 6 章（签名） |
| **② 为什么每次改动重编时间不一样？** | 哪些 task 重跑、哪些命中 UP-TO-DATE、增量边界在哪 | 第 2 章、第 7 章（提速）、第 8 章（模块） |
| **③ 依赖冲突到底谁说了算？** | 同一个库出现多个版本，最终选哪个 | 第 4 章（解析）、第 5 章（治理） |

### 1.1 一次 `assembleDebug` 的任务图（简版）

下面这张图是你执行 `./gradlew :app:assembleDebug` 时，Gradle 实际构造并执行的任务依赖图（task graph）的骨架。方括号 `[]` 是 task，箭头 `→` 表示"必须先于"。

```text
configureProject (配置阶段，全部模块)
        │
        ▼
   :app:preBuild
        │
        ├──→ :app:compileDebugKotlin      ← Kotlin 源码 → .class
        ├──→ :app:dataBindingMerge...     ← 可选，数据绑定/视图绑定
        ├──→ :app:mergeDebugResources      ← res/ 合并（flavor<buildType 优先级）
        ├──→ :app:processDebugManifest     ← Manifest 合并
        ├──→ :app:generateDebugBuildConfig ← BuildConfig 字段
        │
        ▼  (上面多数可并行)
   :app:compileDebugJavaWithJavac   (若含 Java 或 KSP/kapt 产物)
        │
        ▼
   :app:dexBuilderDebug             ← .class → DEX (D8)
   :app:mergeExtDexDebug
   :app:packageDebug                ← 打包成未签名 APK
        │
        ▼
   :app:signingConfigWriterDebug    ← 写入签名配置
   :app:signDebugBundle / :app:apkDebug ← v1/v2/v3 签名
        │
        ▼
   :app:assembleDebug               ← 收口 task（聚合上述产物）
```

**怎么读这张图（读图三步）：**

1. 最左边竖线 `configureProject` 是**配置阶段**一次性做完的（后面第 2 章讲它为什么慢不得）。
2. 中间一簇 `compile* / merge* / process*` 是**执行阶段**真正干活、可并行的 task。**横着并排的 task 之间无依赖，能并行**。
3. 最下面 `assembleDebug` 只是个"空壳聚合 task"，它本身不干活，只声明"依赖上面全部产物"。你点它，Gradle 才去解析出整张图。

### 1.2 本篇怎么读

- 想搞懂"为什么慢" → 直奔第 2 章、第 7 章。
- 想搞懂 flavor / 多渠道 / 改 manifest → 第 3 章。
- 想搞懂 `Duplicate class` 和版本打架 → 第 4、5 章。
- 做车机 priv-app、系统签名 → 第 6、10 章。
- 构建挂了不知道从哪查 → 第 11 章排查表。

---

## 2. 构建三阶段：Configuration / Execution / Finalization

Gradle 一次构建严格分成三个阶段。这是整篇最重要的心智模型——**很多性能坑和"配置不生效"的诡异现象，根源都在阶段边界搞错**。

```text
┌─────────────────────────────────────────────────────────────┐
│ Phase 1: Initialization  初始化                              │
│   找 settings.gradle(.kts)，决定有哪些 project（模块）        │
├─────────────────────────────────────────────────────────────┤
│ Phase 2: Configuration   配置                               │
│   执行所有 build.gradle(.kts) 的"顶层代码"                    │
│   注册 task、确定 task 之间的依赖关系 → 生成 task graph        │
│   ⚠️ 这一阶段不做"编译/src 处理"，只决定"要做什么"            │
├─────────────────────────────────────────────────────────────┤
│ Phase 3: Execution   执行                                  │
│   按 task graph 拓扑序，跑被选中的 task（带 UP-TO-DATE 判定）  │
├─────────────────────────────────────────────────────────────┤
│ Phase 4: Finalization  收尾                                 │
│   build finish 钩子、build scan 上报、缓存写回                │
└─────────────────────────────────────────────────────────────┘
```

### 2.1 准确分界：什么算"配置阶段代码"

在 `build.gradle.kts` 里，**不在任何 `tasks.register { }` / `afterEvaluate { }` 回调内部、直接写在文件顶层的语句，都属于配置阶段代码，每次构建都会无条件执行**，无论你最终跑的是 `assembleDebug` 还是 `help`。

```kotlin
// build.gradle.kts  —— 以下每一行都是"配置阶段代码"
val myDir = file("some/path")        // 配置阶段执行：建 File 对象
println("configuring $name")         // 配置阶段执行：每次都打印
val files = myDir.listFiles()        // 配置阶段执行：每次都扫磁盘 ⚠️ 性能陷阱

android {
    // 这里面的块也是配置阶段求值
    compileSdk = 35
}

tasks.register("myTask") {
    // ↓↓↓ 只有这段 lambda 里的代码，才在 task 真正执行时才跑
    doLast {
        println("running myTask")    // 仅当 myTask 被选中执行时才打印
    }
}
```

`「Android 视角」` 很多人把"读文件、查 git、拼 URL、访问网络"写在配置阶段，结果每次 `./gradlew tasks` 都要卡几秒——因为这些重活在决定"要做什么"之前就先跑了。

### 2.2 为什么"configuration 阶段别做重活"是第一大性能戒律

配置阶段是**串行、单线程**的（在 configuration cache 出现前尤其如此），而且无论你只跑一个 task 还是全量构建，配置阶段都**整张项目图全跑一遍**。模块越多，配置越重。这直接催生了两个机制：

- **Configuration Cache（配置缓存，AGP 7.4+ 引入，AGP 8.x 逐步稳定）**：把"配置阶段产物 = task graph"序列化到磁盘，下次同参数构建跳过整个配置阶段，直接 load。稳定前偶有插件不兼容，AGP 8.x 已覆盖主流插件。
- **Build Cache（构建缓存）**：缓存**执行阶段** task 的输出（如编译结果），跨机器/跨构建复用。**注意它和 configuration cache 管的是不同阶段**，见第 7 章对比。

> 版本差异：Configuration Cache 在 AGP 8.1+ 默认对绝大多数场景可用；早期版本需在 `gradle.properties` 显式 `org.gradle.configuration-cache=true` 且容忍部分插件报错。新工程建议直接开启。

### 2.3 task graph 怎么看：不真跑，只看图

```bash
# 干跑：打印会执行的 task 列表，但不真正执行任何动作
./gradlew :app:assembleDebug --dry-run

# 看某个 task 的依赖来源（谁 require 了它）
./gradlew :app:help --task :app:assembleDebug
# 或直接用：
./gradlew :app:tasks --all          # 列出全部 task 及分组
```

`--dry-run` 输出里每行前面的 `>` 标记的就是"本次会被执行的 task"。如果你改了一行代码，发现某个 task 没出现在 `--dry-run` 里，说明它被判 UP-TO-DATE 跳过了——这通常是好事（增量生效），但也可能是"输入没被 Gradle 识别为变更"导致漏编（第 11 章"修改未生效"）。

---

## 3. Variant 心智模型：buildType × flavor × dimension 的笛卡尔积

"变体（variant）"是 AGP 自动组合出来的"一种完整构建配置"。理解变体，关键是理解它是**多维度的笛卡尔积**，不是手写的列表。

### 3.1 笛卡尔积怎么算

```text
变体数 = (buildType 数) × (各 flavorDimension 下 flavor 数之积)

例：
  buildTypes      = { debug, release }                         → 2
  flavorDimensions = "channel" → flavors { google, huawei }    → 2
  flavorDimensions = "abi"     → flavors { arm64, arm32 }      → 2
  ────────────────────────────────────────────────────────────
  变体数 = 2 × 2 × 2 = 8 个（debugGoogleArm64, releaseHuaweiArm32 ...）
```

`「Android 视角」` 每次加一个 flavor dimension，变体数是**乘法**增长，不是加法。车机项目常见 `channel × abi × region` 三维，极易失控。治理原则：**能用 buildType 表达的别用 flavor；能用 productFlavors 单维度解决的别加维度**。

### 3.2 sourceSet 合并优先级（一张表背下来）

AGP 合并资源/代码/Manifest 时，**优先级高的覆盖优先级低的**。下面这张表是排查"为什么我的 string/资源没生效"的命脉。

| 优先级 | sourceSet | 说明 |
|---|---|---|
| 最低 | `main` | 所有变体共有 |
| ↑ | `flavor` (如 `google`) | 单 dimension 的 flavor |
| ↑ | `flavorDimension` 组合 (如 `googleArm64`) | 多 dimension 时维度顺序决定先后 |
| ↑ | `buildType` (如 `debug`) | buildType 高于 flavor |
| ↑ | `variant` (如 `googleDebug`) | 最具体，最高优先级 |
| （测试专用） | `test` / `androidTest` | 单测/仪器测，不进发布包 |

记忆链：**越具体的集合优先级越高**（main < flavor < buildType < variant）。同名资源/Manifest 属性，高优先级直接覆盖低优先级，而非报错。

### 3.3 在 variant 里注册 task、改 manifest 的正确姿势

不要手写一堆 `tasks.register("assembleGoogleDebug")`——应该基于 AGP 暴露的 `androidComponents` / `applicationVariants` 钩子，让 AGP 为每个变体自动回调：

```kotlin
// build.gradle.kts（AGP 7+ 推荐 androidComponents）
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        // variant 是配置阶段拿到、执行阶段可用的变体对象
        val taskProvider = tasks.register<DefaultTask>("${variant.name}Report") {
            doLast {
                println("building ${variant.name}, apk dir = ${variant.artifacts}")
            }
        }
        // 把自定义 task 挂到该变体的 assemble 之后
        variant.assemble?.let { it.dependsOn(taskProvider) }
    }
}
```

改 Manifest 也不要直接 sed 改文件，而用 **Manifest 占位符**（AGP 在合并时替换）：

```kotlin
android {
    defaultConfig {
        // AndroidManifest.xml 里写 ${APP_LABEL}
        manifestPlaceholders["APP_LABEL"] = "MyApp"
        manifestPlaceholders["CHANNEL"]   = "google"
    }
    productFlavors {
        create("huawei") {
            manifestPlaceholders["CHANNEL"] = "huawei"  // 高优先级覆盖
        }
    }
}
```

`「Android 视角」` 改 `applicationId` 用 `applicationIdSuffix`（buildType）或 flavor 的 `applicationId`，别去手改 Manifest 的 package——AGP 8 起 **namespace 强制**与 `applicationId` 解耦（见第 10 章），Manifest 里 `package` 属性已被废弃。

---

## 4. 依赖解析：依赖树怎么读、版本冲突谁说了算

Gradle 的依赖解析（dependency resolution）是"传递依赖（transitive dependency）"自动拉取 + 版本决策的过程。理解它，才能看懂 `Duplicate class` 和"行为异常但没报错"。

### 4.1 依赖树怎么读（逐段读法）

```bash
# 看某个具体配置（configuration）的依赖树，最有用
./gradlew :app:dependencies --configuration releaseRuntimeClasspath

# 想看某条依赖"为什么是这个版本"，用 insight（见第 5 章）
./gradlew :app:dependencyInsight --dependency androidx.core:core-ktx --configuration releaseRuntimeClasspath
```

`dependencies` 输出结构（读懂每一段）：

```text
releaseRuntimeClasspath - Dependencies for runtime classpath...
+--- androidx.core:core-ktx:1.13.1                  ← 直接/被选中版本
|    +--- androidx.annotation:annotation:1.8.0      ← 它的传递依赖
|    \--- androidx.core:core:1.13.1                 ← ktx 对 core 的桥接
+--- com.squareup.retrofit2:retrofit:2.11.0
|    \--- com.squareup.okhttp3:okhttp:4.12.0        ← 传递拉进来的 okhttp
\--- com.squareup.okhttp3:okhttp:4.11.0 -> 4.12.0   ← ⚠️ 看到 "-> 4.12.0" 就是发生了版本提升！
```

读法口诀：

1. 每段缩进 = 依赖层级（`+---` 平级，`\---` 末项）。
2. `-> X.Y.Z` 这个箭头是**金矿**：它表示"声明的是老版本，但被解析成新版本了"，版本冲突就藏在这里。
3. 同一个库出现两次不同版本 → 必有提升/强制，需确认是否兼容。

### 4.2 版本冲突的判定规则（讲清真实策略，不是"最高胜出"那么简单）

Gradle 默认用 **fail on conflict 的"最高版本胜出"（highest version wins）策略**，但细节是：

- **对同一模块（group:name 相同）的多个版本请求，Gradle 选最高的那个**作为唯一版本，所有调用方都用它。这是"默认 resolution strategy"。
- 它**不会**自动报警告，也不会因为"你直接声明的是低版本"就听你的——直接声明的版本只是"请求之一"，仍参与最高胜出。
- 只有当两个版本** mutually exclusive 且无法统一**（极少见，如 `strictly` 约束冲突）时，才会**构建失败**并明确报 conflict。

> 反直觉点：你 `implementation("okhttp:4.11.0")`，但 retrofit 拉来 `4.12.0`，最终是 `4.12.0`。你的声明没"赢"。所以"为什么我锁的版本没生效" 90% 是因为别的传递依赖请求了更高版本。

### 4.3 版本控制四件套：force / strictly / exclude / constraints 怎么选

| 手段 | 写法 | 语义 | 适用场景 | 风险 |
|---|---|---|---|---|
| `force` | `resolutionStrategy.force("g:okhttp:4.12.0")` | 强制固定该模块版本（仍走最高胜出逻辑，只是把你的请求抬到最高） | 快速止血、统一版本 | 掩盖真实冲突，可能拉来不兼容 API |
| `strictly` | `"okhttp:{strictly 4.12.0}"` 或 `because` | **严格要求**该版本，出现其他版本直接**失败**而非静默提升 | 你绝对不能接受别的版本时 | 任何传递依赖想要别的版本 → 构建失败，需配合 `exclude` |
| `exclude` | `implementation(x) { exclude(group="g", module="m") }` | 从某条依赖路径上**剔除**指定模块 | 某个库自带了你不想要的传递依赖 | 剔多了会导致运行时 `NoClassDefFoundError` |
| `constraints` | `constraints { implementation("g:m:4.12.0") }` | 声明"推荐/约束版本"但不引入依赖本身 | 在 BOM 之外统一版本基线 | 约束与实际不符时仍按最高胜出 |

```kotlin
// build.gradle.kts 示例：四类写法
configurations.all {
    resolutionStrategy {
        force("com.squareup.okhttp3:okhttp:4.12.0")          // ① 强制
        // eachDependency { if (requested.group=="com.squareup.okhttp3") useVersion("4.12.0") }
    }
}
dependencies {
    implementation("com.squareup.retrofit2:retrofit:2.11.0") {
        exclude(group = "com.squareup.okhttp3", module = "okhttp")  // ③ 剔除
    }
    constraints {                                               // ④ 约束
        implementation("androidx.core:core-ktx:1.13.1")
    }
}
// ② strictly 写在版本字符串里
dependencies { implementation("com.google.code.gson:gson:{strictly 2.10.1}") }
```

### 4.4 BOM 与 platform：别手写一堆版本

BOM（Bill of Materials）是一个"只声明版本、不含代码"的特殊依赖，用来**统一一组库版本**，避免你逐个写版本号。

```kotlin
dependencies {
    // platform() 引入 BOM，其内声明的版本成为同组库的默认基线
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")          // 不用写版本，跟 BOM
    implementation("androidx.compose.material3:material3")

    // 对 Kotlin 生态也常用（注意：这是 Gradle 的 platform，不是 Maven BOM 的别名）
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.0.20"))
}
```

`「Android 视角」` BOM 本质是 `constraints` 的打包版。它**只在你没显式写版本时提供默认值**，一旦你显式写了版本，BOM 不再介入（但仍受最高胜出约束）。

---

## 5. 依赖治理：冲突、重复类与国内镜像

解析规则懂了，真正干活时踩的是"治理"层面的坑：同一个类出现两次、行为诡异但编译通过、依赖死活下不来。

### 5.1 Duplicate class（重复类）排查路径

`Duplicate class com.foo.Bar found in modules A and B` 的根因：**同一个 fully-qualified 类名被两个不同 artifact 提供**。常见来源：

1. **同一库的不同打包形态**：如 `com.google.guava:guava`（完整）vs `guava-jdk5`（精简），或 `kotlin-stdlib` vs `kotlin-stdlib-jdk7` 旧版重叠。
2. **support 库与 AndroidX 共存**：老 `com.android.support:*` 与新 `androidx.*` 提供同名类 → 必须全量迁移 AndroidX（AGP 自带 `refactor` 工具）。
3. **传递依赖各自带了一份**：用 `dependencies --configuration xxxRuntimeClasspath` 找到两个 artifact，对其中一个 `exclude` 该模块。

排查链路（照着走）：

```bash
# 1) 看冲突模块到底从哪来
./gradlew :app:dependencies --configuration debugRuntimeClasspath | grep -B2 -A2 "guava"
# 2) 精确定位某类的来源 artifact
./gradlew :app:dependencyInsight --dependency com.google.guava:guava --configuration debugRuntimeClasspath
# 3) 在提供方里 exclude 掉重复的那一份
```

### 5.2 R8 / 混淆与依赖的隐性冲突

R8 做 shrink 时会按"入口点（保持规则）"判定死代码。若某库靠**反射/ServiceLoader/注解**被调用，却没 keep，会被误删，表现为"release 崩溃、debug 正常""某个功能静默失效"——这**不是依赖版本冲突，是混淆裁剪冲突**。

```text
判定链条：
  release 才崩 + debug 正常
    → 先看 mapping.txt 能否还原堆栈（build/outputs/mapping/release/mapping.txt）
    → 再查是否反射/JNI/WebView JS Bridge/序列化 相关
    → 在 proguard-rules.pro 加 -keep，而非 -keep class ** { *; } 一把梭
```

`「Android 视角」` R8 详细 keep 语法属 `15-App编译配置文件详解.md` 范畴，本篇只给判定心智：release-only 异常先怀疑 shrink，再怀疑签名，最后才怀疑代码。

### 5.3 传递依赖"静默升级"的风险

依赖树里那个 `-> 4.12.0` 的箭头，可能把你的代码带进一个你没测过的版本。用 `dependencyInsight` 看"为什么是它"：

```bash
./gradlew :app:dependencyInsight \
  --dependency com.squareup.okhttp3:okhttp \
  --configuration releaseRuntimeClasspath
# 输出会列出：谁请求了 4.11.0、谁请求了 4.12.0、最终选中哪个、依据哪条规则
```

治理手段（按推荐度）：BOM 锁基线（4.4）＞ version catalog 统一版本（第 9 章）＞ `constraints` ＞ `force` 兜底。`force` 是最后手段，因为它会**掩盖**真实的不兼容。

### 5.4 国内镜像配置（用户网络环境刚需）

Gradle 默认从 `mavenCentral()` / `google()` 拉取，国内常超时。在 `settings.gradle.kts` 里把仓库换成阿里云代理（保留原仓库语义，仅换 URL）：

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 阿里云代理：google 与 gradle plugin  portal
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        // 阿里云代理：maven central
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        // 保留原生仓库作为兜底（如阿里云未同步时）
        google()
        mavenCentral()
    }
}
```

`「Android 视角」` 镜像只是**代理缓存**，artifact 内容应与上游一致；若遇到"下载下来校验失败/证书错误"，优先清 `~/.gradle/caches/` 对应条目，再怀疑镜像同步延迟。CI 上同理配镜像可大幅提速。

---

## 6. 签名与发布链路：四处落点 + 车机平台签名

签名是"构建产物能装到哪"的分水岭。一个 APK/AAB 的签名配置（signingConfig）有四个典型落点，含义完全不同。

| 落点 | 谁提供 keystore | 什么时候用 | 备注 |
|---|---|---|---|
| **① debug 默认** | AGP 自动生成 `~/.android/debug.keystore` | 本地 `assembleDebug` | 所有开发者共享同一默认密钥；签名不一致无法覆盖安装 |
| **② release 自签名** | 你自己的 `release.jks` | 对外发布 APK | keystore **丢失=永远不能同名升级**，必须离线备份 |
| **③ Play App Signing** | Google 保管上传密钥对应的"应用签名密钥" | 上架 Play | 你只持"上传密钥"，Google 重签；支持密钥轮换 |
| **④ 车机平台签名** | 厂商 `platform.pk8` + `x509.pem` | 预装 priv-app / 系统权限 | 用平台密钥才能拿到 `android.uid.system` 等 signature 级权限 |

### 6.1 release 自签名的最小配置

```kotlin
// build.gradle.kts（:app）
android {
    signingConfigs {
        create("release") {
            storeFile   = file("release.jks")
            storePassword = System.getenv("KEYSTORE_PWD")   // 别硬编码，走 env/CI secret
            keyAlias      = "release"
            keyPassword   = System.getenv("KEY_PWD")
        }
    }
    buildTypes {
        release { signingConfig = signingConfigs.getByName("release") }
    }
}
```

### 6.2 车机平台签名：platform.pk8 / x509.pem 转 JKS（priv-app 常用）

车机 ROM 开发者做预装 priv-app 时，拿到的不是 jks，而是 AOSP 的 `platform.pk8`（私钥，PKCS8 DER）和 `platform.x509.pem`（证书）。需先合成 keystore，再交给 AGP。完整转换流程（用 `apksign` 思路 / `keytool`+`openssl`）：

```bash
# 1) pk8 私钥 PEM 化（去 DER 头）
openssl pkcs8 -inform DER -nocrypt -in platform.pk8 -out platform.pem

# 2) 私钥 + 证书 合成 PKCS12
openssl pkcs12 -export -in platform.x509.pem -inkey platform.pem \
  -out platform.p12 -name platform -password pass:android

# 3) PKCS12 转 JKS（keytool），供 AGP signingConfig 使用
keytool -importkeystore -deststorepass android -destkeystore platform.jks \
  -srckeystore platform.p12 -srcstoretype PKCS12 -srcstorepass android \
  -alias platform

# 4) AGP 里引用
# signingConfigs { create("platform") {
#     storeFile = file("platform.jks"); storePassword = "android"
#     keyAlias = "platform"; keyPassword = "android" } }
```

`「Android 视角」` 用平台密钥签名后，Manifest 里 `android:sharedUserId="android.uid.system"` 或 `android:protectionLevel="signature"` 的权限才能生效——这是 priv-app 拿系统权限的硬前提。keystore 密码常被 AOSP 设为 `android`，但**不要**用于对外发布。

### 6.3 AAB vs APK vs 车机直接 push 对比

| 形态 | 产出 | 适用 | 签名关注点 |
|---|---|---|---|
| **APK** | 单包，含全部资源配置 | 本地调试、直接安装、国内商店 | v1/v2/v3 全开最稳 |
| **AAB** | 应用包（Google Play 按设备切分下发 APK） | 上架 Play | Play 重签，你持上传密钥 |
| **车机 push** | `adb push` 到 `/system/priv-app` 或 `/product` | ROM 预装 | 必须平台签名 + 与 ROM 签名一致，否则 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 类失败 |

---

## 7. 构建提速清单（可执行）

按"见效快→见效慢、风险高→低"排序。先开前三项，通常能砍掉 30%~60% 时间。

### 7.1 gradle.properties 关键项

```properties
# gradle.properties（项目根或 ~/.gradle/gradle.properties 全局）
org.gradle.daemon=true                       # 守护进程常驻（默认开，别关）
org.gradle.parallel=true                     # 多模块并行执行 task
org.gradle.caching=true                      # 构建缓存（执行阶段输出复用）
org.gradle.configuration-cache=true          # 配置缓存（跳过配置阶段，AGP 8.x 稳）
org.gradle.jvmargs=-Xmx4g -XX:+HeapDumpOnOutOfMemoryError  # 给足堆，防 OOM
android.useAndroidX=true
kotlin.daemon.jvmargs=-Xmx2g                  # Kotlin 守护进程独立堆
```

### 7.2 Configuration Cache vs Build Cache：管的不同时段

| 维度 | Configuration Cache | Build Cache |
|---|---|---|
| 缓存内容 | task graph（配置阶段产物） | task 输出（执行阶段产物，如 .class/dex） |
| 跳过阶段 | 整个配置阶段 | 仅跳过已命中缓存的 task 执行 |
| 失效条件 | 任何配置脚本/插件逻辑变更 | task 输入（源/资源/参数）变更 |
| 跨机器 | 默认不跨（同一台复用） | 可配远程缓存（`remote.build-cache`）跨 CI 共享 |

记忆：**configuration cache 让你"决定做什么"更快，build cache 让你"做过的活不重做"**。两者正交，一起开。

### 7.3 KSP 取代 kapt（注解处理器提速）

`kapt` 要先把 Kotlin 编译成 Java stub 再交给 Java 注解处理器，慢且吃内存；`KSP`（Kotlin Symbol Processing）直接读 Kotlin 语法树，快数倍、内存低。

```kotlin
// 替换：kapt("androidx.room:room-compiler")  →  ksp("androidx.room:room-compiler")
plugins { id("com.google.devtools.ksp") }
dependencies { ksp("androidx.room:room-compiler:2.6.1") }
```

> 版本差异：KSP 在 Kotlin 2.0（K2 编译器）下已演进为稳定主力；少数老库仍只支持 kapt，需用 `kapt` 时尽量限定在单一模块以缩小影响面。

### 7.4 其他可执行项

```bash
./gradlew assembleDebug --offline      # 离线构建：强制用缓存，验证缓存是否健康
./gradlew build --scan                 # 生成构建性能报告（哪一步最慢）
```

- **模块拆分影响增量**：模块边界越清晰、用 `implementation` 而非 `api`（第 8 章），改一个模块就越少触发上游重编。巨型单模块 = 任何改动全量重编。
- **AGP 升级策略**：保持 AGP 与 Kotlin、Gradle 版本三角兼容（查官方兼容表）；先升 Gradle 小版本 → 再升 AGP → 最后 Kotlin。每次只动一个，便于定位回归。

---

## 8. 多模块工程：拓扑、方向约束与可见性

模块不是"越多越好"，是"边界对、方向清"。

### 8.1 结构拓扑

```text
:app                 ← 壳，组装所有 feature，几乎不含业务
 ├── :feature:home
 ├── :feature:settings
 └── :feature:profile
       ├── :core:common      ← 基础工具/扩展
       ├── :core:network     ← 网络层
       ├── :core:database    ← 持久化
       └── :core:ui          ← 公共 UI 组件
依赖方向：app → feature → core ；feature 之间尽量不互依
```

### 8.2 convention plugin：复用配置 + 约束方向的由来

每个模块都抄一遍 `android { compileSdk... defaultConfig... }` 既啰嗦又易漂移。于是把"同一类模块的通用配置"抽成 **convention plugin**（约定插件）。`buildSrc`（老）与 `convention plugin`（新，AGP 7+/Gradle 8 推荐）对比：

| 维度 | buildSrc | convention plugin（独立 includeBuild 模块） |
|---|---|---|
| 生效机制 | 自动参与构建 classpath | 显式 `includeBuild(":build-logic")` + `pluginManagement` |
| 配置缓存兼容 | 较差（易被算进配置输入） | 好 |
| 复用/发布 | 仅本仓库 | 可抽成独立 Gradle 插件工程复用 |
| 推荐度 | 旧项目遗留 | **新项目首选** |

最小实现（convention plugin）：

```kotlin
// build-logic/src/main/kotlin/com.example.android.library.gradle.kts 插件
plugins { id("com.android.library") ; id("org.jetbrains.kotlin.android") }
android {
    compileSdk = 35
    defaultConfig { minSdk = 23 }
    // 所有 library 模块统一在这里配，业务模块只 apply 这个插件
}
// 业务模块：plugins { id("com.example.android.library") }
```

### 8.3 api vs implementation：可见性与性能的差异

这是"改一个模块会不会重编全部"的关键。

```kotlin
dependencies {
    api("androidx.core:core-ktx")        // 暴露给依赖本模块的上游 → 上游也要重编
    implementation("okhttp3:okhttp")     // 仅本模块内部用 → 上游不感知，不重编
}
```

| 关键字 | 传递可见性 | 上游是否因它变更而重编 | 用法 |
|---|---|---|---|
| `api` | 是（上游能 import 该依赖的类） | 是 | 真的需要把依赖类型暴露到本模块 public API 时 |
| `implementation` | 否 | 否 | 默认用这个；内部实现细节 |
| `compileOnly` | 编译期可见、不打包 | 否 | 运行期由平台提供（见第 10 章 framework.jar） |

记忆：**能写 `implementation` 就别写 `api`**。滥用 `api` 会让依赖图"全连通"，一个小改动顺着 `api` 链一路重编到 `:app`。

---

## 9. 构建脚本工程化：version catalog 与复用

### 9.1 version catalog（libs.versions.toml）完整结构

把版本、库、插件集中到 `gradle/libs.versions.toml`，告别散落各处的写死版本：

```toml
[versions]
agp        = "8.6.0"
kotlin     = "2.0.20"
core-ktx   = "1.13.1"
room       = "2.6.1"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "core-ktx" }
room-runtime      = { module = "androidx.room:room-runtime", version.ref = "room" }
room-compiler     = { module = "androidx.room:room-compiler", version.ref = "room" }
okhttp            = "com.squareup.okhttp3:okhttp:4.12.0"   # 也可直接写死版本

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android      = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
ksp                 = { id = "com.google.devtools.ksp", version.ref = "kotlin" }

[bundles]
room = ["room-runtime", "room-compiler"]   # 成组引用
```

引用：

```kotlin
plugins { alias(libs.plugins.android.application) ; alias(libs.plugins.ksp) }
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.bundles.room)
}
```

### 9.2 Kotlin DSL 里的复用（fun / extension）

把重复逻辑抽成函数/扩展，而不是复制粘贴块：

```kotlin
// build.gradle.kts 内复用
fun VersionCatalog.androidX(module: String) = findLibrary(module).get()
// 或抽 extension：
val NamedDomainObjectContainer<*>.commonAndroid: Unit get() = Unit

// 跨模块复用请用第 8 章的 convention plugin，而不是在每文件写 fun
```

### 9.3 gradle.properties：项目级 vs 全局级

| 文件 | 路径 | 作用域 | 典型内容 |
|---|---|---|---|
| 项目级 | `<project>/gradle.properties` | 仅本仓库 | `android.useAndroidX`、`org.gradle.caching`、模块开关 |
| 全局级 | `~/.gradle/gradle.properties` | 当前用户所有仓库 | 守护进程堆、`org.gradle.daemon`、代理/镜像代理配置 |

`「Android 视角」` 团队统一项（缓存、并行、配置缓存）放项目级进版本库；个人机器项（堆大小、本地代理）放全局级，避免污染他人和 CI。

---

## 10. 车机 / 平台衔接：系统应用工程的特殊依赖

### 10.1 framework.jar / system libs 的 compileOnly 引入

系统应用（priv-app）常要调用 `@hide` 的 framework API 或车机平台私有 SDK。运行期这些类由系统提供，所以编译期用 `compileOnly` 引入，**不打包进 APK**，否则会和系统里的类冲突（甚至 `Duplicate class` 到系统库）。

```kotlin
dependencies {
    // 编译期可见、不进包：避免与 /system/framework 里的类重复
    compileOnly(files("libs/framework.jar"))
    compileOnly(files("libs/vehicle-platform.jar"))
}
```

风险清单：

- `compileOnly` 的类运行期若不存在 → 运行时 `NoClassDefFoundError`/`NoSuchMethodError`（必须确认目标 ROM 真有该类）。
- 自带的 jar 与系统 jar **类名重叠** → 运行期优先加载系统的（或 bootclasspath 顺序问题），行为可能和你编译时看到的不一致。
- 别用 `implementation` 引入 framework 类，否则 APK 体积膨胀且和系统集成冲突。

### 10.2 与 AOSP Soong 构建的关系（一句话）

App 层 Gradle 构建是**应用视角**的打包；车机系统镜像整体由 AOSP 的 **Soong/Blueprint** 构建，Gradle 产出的 APK 最终被 Soong 当成预编译产物塞进 `system.img`——两者的签名、API 级别必须对齐。系统构建专题见 `androidOthers/构建系统篇（Soong 与 AOSP 集成）`。

### 10.3 CI 上的签名与构建缓存

- 平台签名 `platform.jks`、release keystore **绝不进仓库**，走 CI secret（环境变量/加密文件）。
- CI 开启 `org.gradle.caching=true` + 远程 build cache，让多台 runner 共享编译产物；但 **configuration cache 在 CI 上需确认插件兼容**，否则先关。
- 每发版归档 `mapping.txt`、记录 `versionCode`/commit/渠道，便于线上崩溃还原与回滚。

---

## 11. 构建失败排查表（现象 | 原因 | 章节 | 第一命令）

| 现象 | 原因 | 章节 | 第一命令 |
|---|---|---|---|
| `Duplicate class X found in A and B` | 两个 artifact 提供同名类 | 5.1 | `./gradlew :app:dependencies --configuration debugRuntimeClasspath` |
| 依赖冲突但没报错、行为异常 | 传递依赖被静默提升到不兼容版本 | 4.2 / 5.3 | `./gradlew :app:dependencyInsight --dependency <g:m> --configuration releaseRuntimeClasspath` |
| 改了代码重新构建却不生效 | task 被判 UP-TO-DATE 跳过 / 输入未识别为变更 | 2.3 / 7.2 | `./gradlew :app:assembleDebug --rerun-tasks` |
| kapt/KSP 生成的类找不到 | 注解处理器未跑 / 生成目录未加入源集 | 7.3 | `./gradlew :app:kaptDebugKotlin --rerun-tasks`（或 `kspDebugKotlin`） |
| 配置阶段 OOM / 构建卡在 configuring | 配置阶段做了重活（扫盘/网络） | 2.1 / 2.2 | 看 `org.gradle.jvmargs` 加堆；`--scan` 定位重活 |
| 签名不匹配、安装失败 | debug/release/platform 密钥不一致 | 6.1 / 6.2 | `adb install` 看 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` |
| 车机 push 后签名验证失败 | APK 未用平台签名 / 与 ROM 签名不符 | 6.2 / 10.1 | `adb shell pm install --check-<file>` 比对签名 |
| 依赖下不下来（超时/证书） | 国内网络 / 镜像未同步 | 5.4 | `./gradlew :app:dependencies --refresh-dependencies` |
| release 崩溃、debug 正常 | R8 误删反射/序列化相关类 | 5.2 | 查 `build/outputs/mapping/release/mapping.txt` |
| flavor 资源/Manifest 没生效 | sourceSet 优先级被覆盖 | 3.2 | 确认覆盖方 sourceSet 是否更高优先级 |
| 多 flavor 构建慢到不可接受 | 维度笛卡尔积爆炸 | 3.1 | 收敛 dimension 数量 |
| Configuration phase 缓存报错 | 某插件不支持 config cache | 2.2 / 7.2 | 临时 `org.gradle.configuration-cache=false` 验证 |
| `namespace` 报错 / Manifest package 废弃 | AGP 8 强制 namespace 与 applicationId 解耦 | 3.3 / 10 | 在 `android { namespace = "..." }` 声明 |
| 改一个模块全部重编 | 滥用 `api` 使依赖图全连通 | 8.3 | 把 `api` 改 `implementation` 再测增量 |

---

## 12. 一图总结 + 关联阅读

### 12.1 构建三阶段 + variant 合并（一图）

```text
【构建三阶段】
  Configuration(算 task graph, 别做重活)
        │  → Configuration Cache 跳过此阶段
        ▼
  Execution(跑被选中的 task, UP-TO-DATE 判定)
        │  → Build Cache 复用 task 输出
        ▼
  Finalization(缓存写回 / scan 上报)

【variant 合并优先级】
  main < flavor < buildType < variant
  同名资源：高优先级覆盖低优先级，不报错

【一句话记忆链】
  "配置决定做什么（慢不得）→ 执行做且只做变更的（缓存复用）→
   变体靠笛卡尔积组合（维度乘法）→ 依赖按最高版本胜出（箭头即提升）→
   签名决定装到哪（平台签名换 priv-app 权限）"
```

### 12.2 关联阅读

- `15-App编译配置文件详解.md`：逐个文件、逐个 block 的 DSL 语法与 keep 规则（本篇的"手册版"）。
- `kotlin/kotlin-GradleKotlinDSL入门.md`：Gradle Kotlin DSL 基础（本篇假定你已会 DSL 写法）。
- `androidOthers/构建系统篇（Soong 与 AOSP 集成）`：车机系统镜像整体构建，与 App 层 Gradle 的关系。
- `androidApp/02-...`：应用工程结构与生命周期起点，理解"源码从哪来、去哪打包"。

> 分工声明：本篇讲"构建心智模型与工程化"（任务图、变体、依赖解析、加速、治理、签名、车机衔接）；逐文件 DSL 行级讲解归 `15-` 篇，请勿在两篇间复制 DSL 段。
