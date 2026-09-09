# 15 App 编译配置文件详解：settings、build.gradle.kts、Version Catalog 与工程参数

> Android 项目的编译配置文件不是孤立存在的。`settings.gradle.kts` 决定“有哪些模块和插件仓库”，根目录 `build.gradle.kts` 决定“全局构建约定”，模块 `build.gradle.kts` 决定“这个模块怎么编译、打包和依赖谁”，`libs.versions.toml` 则负责“版本集中治理”。

---

## 1. 先看一眼典型项目结构

```text
MyApp/
  settings.gradle.kts
  build.gradle.kts
  gradle.properties
  local.properties
  gradlew
  gradlew.bat
  gradle/
    wrapper/
      gradle-wrapper.properties
    libs.versions.toml
  app/
    build.gradle.kts
    proguard-rules.pro
    src/
      main/
        AndroidManifest.xml
        java/...
        res/...
  core/
    network/
      build.gradle.kts
  feature/
    home/
      build.gradle.kts
```

这些文件大致分为四类：

| 文件 | 主要职责 |
|---|---|
| `settings.gradle.kts` | 声明项目名称、模块列表、插件仓库、依赖仓库、Version Catalog 来源 |
| 根目录 `build.gradle.kts` | 放全项目共享的插件声明、构建约定或公共配置入口 |
| 模块 `build.gradle.kts` | 配置具体模块的插件、Android 参数、依赖、构建变体 |
| `gradle/libs.versions.toml` | 集中管理插件版本、依赖版本、依赖别名和 bundle |
| `gradle.properties` | Gradle、Kotlin、Android Gradle Plugin 的全局开关和 JVM 参数 |
| `local.properties` | 本机私有配置，比如 Android SDK 路径，不应提交到仓库 |
| `gradle-wrapper.properties` | 固定 Gradle 版本，保证团队和 CI 使用一致的 Gradle |
| `proguard-rules.pro` | R8/ProGuard 混淆、压缩和 keep 规则 |

---

## 2. Gradle 构建时会先读哪些文件

一次普通的 Android 构建大致会经历：

```text
读取 settings.gradle.kts
  -> 找到模块、插件仓库、依赖仓库、Version Catalog
  -> 配置根项目 build.gradle.kts
  -> 配置各模块 build.gradle.kts
  -> 生成任务图
  -> 执行 assembleDebug / test / lint 等任务
```

所以排查构建问题时，可以按这个顺序思考：

1. 模块是否被 `settings.gradle.kts` include 进来了。
2. 插件和依赖是否能从仓库解析到。
3. Version Catalog 里的别名和版本是否写对。
4. 模块自己的 `plugins {}`、`android {}`、`dependencies {}` 是否合理。
5. Gradle、AGP、Kotlin、JDK 版本是否兼容。

---

## 3. `settings.gradle.kts`：项目入口配置

`settings.gradle.kts` 是 Gradle 初始化阶段读取的文件。它决定这个项目由哪些模块组成，也决定插件和依赖从哪里下载。

常见写法：

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MyApp"

include(":app")
include(":core:network")
include(":core:database")
include(":feature:home")
```

### 3.1 `pluginManagement`

`pluginManagement` 负责配置插件解析仓库。

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
```

这里影响的是这些插件：

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}
```

如果这里缺少 `google()`，Android Gradle Plugin 可能解析失败。

### 3.2 `dependencyResolutionManagement`

`dependencyResolutionManagement` 负责依赖解析仓库。

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
```

常见模式：

| 配置 | 含义 |
|---|---|
| `FAIL_ON_PROJECT_REPOS` | 禁止模块自己再声明仓库，依赖仓库统一在 settings 管理 |
| `PREFER_SETTINGS` | 优先使用 settings 中的仓库 |
| `PREFER_PROJECT` | 优先使用项目或模块中声明的仓库 |

新项目通常建议使用 `FAIL_ON_PROJECT_REPOS`，这样多模块项目更容易治理依赖来源。

### 3.3 `include`

`include` 声明模块：

```kotlin
include(":app")
include(":core:network")
```

模块路径和目录默认对应：

```text
:app           -> app/
:core:network  -> core/network/
```

如果目录名和模块名不一致，可以显式指定：

```kotlin
include(":network")
project(":network").projectDir = file("core/network")
```

一般不建议随便改映射，除非迁移老项目或接入特殊目录结构。

### 3.4 Version Catalog 来源

默认情况下，Gradle 会自动识别：

```text
gradle/libs.versions.toml
```

如果需要自定义 catalog，可以在 `settings.gradle.kts` 中配置：

```kotlin
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("gradle/libs.versions.toml"))
        }
    }
}
```

---

## 4. 根目录 `build.gradle.kts`：全局构建约定

根目录 `build.gradle.kts` 作用在根项目上，常用于声明插件版本和共享构建逻辑。

现代 Android 项目常见写法：

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ksp) apply false
}
```

这里的 `apply false` 很重要：

```text
声明插件版本，但不把插件应用到根项目。
具体哪个模块要用插件，由模块自己的 build.gradle.kts 决定。
```

例如根项目声明：

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
}
```

模块里再应用：

```kotlin
plugins {
    alias(libs.plugins.android.application)
}
```

### 4.1 不建议在根 build.gradle.kts 里堆太多逻辑

老项目里可能会看到：

```kotlin
subprojects {
    repositories {
        google()
        mavenCentral()
    }
}
```

或者：

```kotlin
allprojects {
    configurations.all {
        resolutionStrategy.force("...")
    }
}
```

这些写法容易让构建逻辑变得隐式。现代项目更推荐：

- 仓库放到 `settings.gradle.kts`。
- 版本放到 `libs.versions.toml`。
- 公共模块配置放到 convention plugin。
- 模块自己的差异留在模块 `build.gradle.kts`。

### 4.2 convention plugin 是什么

当很多模块都有重复配置时，不要在每个模块复制一大段：

```kotlin
android {
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
```

可以把公共配置抽到 `build-logic` 里的 convention plugin：

```text
build-logic/
  convention/
    build.gradle.kts
    src/main/kotlin/myapp.android.library.gradle.kts
```

模块中只写：

```kotlin
plugins {
    id("myapp.android.library")
}
```

小项目可以先不用 convention plugin；当模块数量多、重复配置明显增加时再引入。

---

## 5. 模块 `build.gradle.kts`：真正决定模块怎么编译

以 `app/build.gradle.kts` 为例：

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.myapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.myapp"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
```

模块配置主要看三块：

| 配置块 | 作用 |
|---|---|
| `plugins {}` | 当前模块应用哪些能力，比如 Android App、Kotlin、KSP、Hilt |
| `android {}` | Android 编译、打包、变体、资源、签名等配置 |
| `dependencies {}` | 当前模块依赖哪些库和模块 |

---

## 6. `plugins {}`：决定模块类型和能力

App 模块：

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}
```

Android Library 模块：

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
```

纯 Kotlin/JVM 模块：

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}
```

常见插件：

| 插件 | 常见别名 | 作用 |
|---|---|---|
| `com.android.application` | `libs.plugins.android.application` | 生成 APK/AAB 的 App 模块 |
| `com.android.library` | `libs.plugins.android.library` | Android Library 模块，生成 AAR |
| `org.jetbrains.kotlin.android` | `libs.plugins.kotlin.android` | Kotlin Android 编译支持 |
| `org.jetbrains.kotlin.jvm` | `libs.plugins.kotlin.jvm` | Kotlin JVM 模块支持 |
| `com.google.devtools.ksp` | `libs.plugins.ksp` | KSP 注解处理 |
| `org.jetbrains.kotlin.kapt` | `libs.plugins.kotlin.kapt` | kapt 注解处理 |
| `com.google.dagger.hilt.android` | `libs.plugins.hilt` | Hilt 依赖注入 |
| `org.jetbrains.kotlin.plugin.serialization` | `libs.plugins.kotlin.serialization` | Kotlinx Serialization 编译插件 |
| `org.jetbrains.kotlin.plugin.compose` | `libs.plugins.kotlin.compose` | Kotlin 2.x 下 Compose 编译插件 |

注意：插件版本通常放在 `libs.versions.toml` 的 `[plugins]` 中，而不是散落在每个模块里。

---

## 7. `android {}`：Android 模块核心配置

### 7.1 `namespace`

```kotlin
android {
    namespace = "com.example.myapp"
}
```

`namespace` 用于生成 `R` 类、`BuildConfig` 等代码的包名。它不一定等于最终安装到手机上的包名。

### 7.2 `compileSdk`

```kotlin
android {
    compileSdk = 35
}
```

`compileSdk` 表示用哪个 Android SDK 版本编译。它影响你能调用哪些新 API，但不代表 App 只能运行在这个版本。

通常建议：

- 新项目尽量使用当前稳定的较新版本。
- 升级 `compileSdk` 后要关注 Android Gradle Plugin、依赖库、lint 规则变化。

### 7.3 `defaultConfig`

```kotlin
android {
    defaultConfig {
        applicationId = "com.example.myapp"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
}
```

关键字段：

| 字段 | 含义 |
|---|---|
| `applicationId` | App 安装到设备上的唯一包名，只在 application 模块中配置 |
| `minSdk` | App 支持的最低 Android 版本 |
| `targetSdk` | App 适配到的 Android 行为版本 |
| `versionCode` | 给应用市场和系统识别升级用的整数版本号 |
| `versionName` | 展示给用户看的版本名 |

`namespace` 和 `applicationId` 的区别很常见：

```text
namespace     -> 编译期代码命名空间
applicationId -> 运行时安装包名
```

Library 模块没有 `applicationId`，通常只需要：

```kotlin
android {
    namespace = "com.example.core.network"
    compileSdk = 35

    defaultConfig {
        minSdk = 23
    }
}
```

### 7.4 `buildTypes`

`buildTypes` 描述 debug、release 这类构建类型。

```kotlin
android {
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
        }

        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}
```

常见配置：

| 配置 | 作用 |
|---|---|
| `isDebuggable` | 是否允许调试 |
| `applicationIdSuffix` | 给包名追加后缀，方便 debug 和 release 共存 |
| `versionNameSuffix` | 给版本名追加后缀 |
| `isMinifyEnabled` | 是否开启 R8 代码压缩、优化、混淆 |
| `isShrinkResources` | 是否移除未使用资源，通常需要配合 R8 |
| `proguardFiles` | 指定混淆规则文件 |
| `signingConfig` | 指定签名配置 |

### 7.5 `productFlavors`

`productFlavors` 描述产品风味，比如渠道、环境、客户定制版本。

```kotlin
android {
    flavorDimensions += "env"

    productFlavors {
        create("dev") {
            dimension = "env"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }

        create("prod") {
            dimension = "env"
        }
    }
}
```

如果同时有多个维度：

```kotlin
android {
    flavorDimensions += listOf("env", "channel")

    productFlavors {
        create("dev") { dimension = "env" }
        create("prod") { dimension = "env" }
        create("google") { dimension = "channel" }
        create("domestic") { dimension = "channel" }
    }
}
```

最终变体会组合出来：

```text
devGoogleDebug
prodGoogleRelease
devDomesticDebug
prodDomesticRelease
```

变体数量会增长很快。不要把所有差异都做成 flavor，简单开关可以考虑远程配置、构建参数或资源覆盖。

### 7.6 `compileOptions` 和 Kotlin JVM target

Java 编译目标：

```kotlin
android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
```

Kotlin 编译目标常见写法：

```kotlin
kotlin {
    jvmToolchain(17)
}
```

老项目也可能看到：

```kotlin
kotlinOptions {
    jvmTarget = "17"
}
```

新项目更推荐 toolchain，因为它能更明确地约束 JDK 工具链。

### 7.7 `buildFeatures`

```kotlin
android {
    buildFeatures {
        compose = true
        buildConfig = true
        viewBinding = true
    }
}
```

常见选项：

| 选项 | 作用 |
|---|---|
| `compose` | 开启 Jetpack Compose |
| `buildConfig` | 生成 `BuildConfig` 类 |
| `viewBinding` | 开启 ViewBinding |
| `dataBinding` | 开启 DataBinding |
| `aidl` | 是否启用 AIDL 支持 |
| `renderScript` | 老项目可能出现，新项目基本不建议再使用 |

### 7.8 Compose 配置

Kotlin 2.x 项目通常使用 Compose Compiler Gradle Plugin：

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    buildFeatures {
        compose = true
    }
}
```

Kotlin 1.x 老项目常见：

```kotlin
android {
    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }
}
```

Compose、Kotlin、AGP 的版本要互相兼容。遇到 Compose 编译器报错时，先检查这三者的版本组合。

### 7.9 `packaging`

```kotlin
android {
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}
```

常用于处理依赖包里的重复资源文件。例如多个库都带了同名 license 文件时，可能会出现 merge 失败。

### 7.10 `sourceSets`

默认源码目录：

```text
src/main/java
src/main/kotlin
src/main/res
src/test/java
src/androidTest/java
```

如果需要自定义：

```kotlin
android {
    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
            res.srcDirs("src/main/res")
        }
    }
}
```

新项目尽量使用默认结构，除非有迁移历史包袱。

---

## 8. `dependencies {}`：依赖关系和打包边界

常见依赖写法：

```kotlin
dependencies {
    implementation(project(":core:network"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
```

### 8.1 `implementation` 和 `api`

```kotlin
dependencies {
    implementation(libs.okhttp)
    api(libs.retrofit)
}
```

区别：

| 配置 | 含义 |
|---|---|
| `implementation` | 只给当前模块内部使用，不暴露给依赖当前模块的下游模块 |
| `api` | 当前模块的公开 API 需要这个依赖，下游模块也能看到 |

例子：

```kotlin
// core:network 对外暴露 Retrofit 类型
interface ApiFactory {
    fun retrofit(): Retrofit
}
```

如果公开函数签名里出现了 `Retrofit`，那 `core:network` 可能需要使用 `api(libs.retrofit)`。如果只是内部实现用到 OkHttp，优先用 `implementation(libs.okhttp)`。

### 8.2 `compileOnly` 和 `runtimeOnly`

```kotlin
dependencies {
    compileOnly(libs.javax.annotation)
    runtimeOnly(libs.database.driver)
}
```

| 配置 | 含义 |
|---|---|
| `compileOnly` | 编译期需要，运行时不打包 |
| `runtimeOnly` | 编译期不需要，运行时需要 |

Android 项目里最常见的还是 `implementation`、`api`、`testImplementation`、`androidTestImplementation`、`ksp`、`kapt`。

### 8.3 `ksp` 和 `kapt`

```kotlin
dependencies {
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
}
```

或者老项目：

```kotlin
dependencies {
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
}
```

建议：

- 新库优先看是否支持 KSP。
- kapt 会启动注解处理流程，通常比 KSP 更慢。
- Hilt、Room、Moshi 等库要确认 runtime 和 compiler 版本一致。

### 8.4 BOM

BOM 用于统一一组依赖的版本，比如 Compose 或 Firebase：

```kotlin
dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
}
```

使用 BOM 后，BOM 管理的库通常不需要单独写版本。

---

## 9. `libs.versions.toml`：Version Catalog 详解

`gradle/libs.versions.toml` 常见结构：

```toml
[versions]
agp = "8.7.2"
kotlin = "2.0.21"
coreKtx = "1.15.0"
appcompat = "1.7.0"
junit = "4.13.2"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-appcompat = { module = "androidx.appcompat:appcompat", version.ref = "appcompat" }
junit = { module = "junit:junit", version.ref = "junit" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }

[bundles]
androidx-basic = ["androidx-core-ktx", "androidx-appcompat"]
```

### 9.1 `[versions]`

集中定义版本号：

```toml
[versions]
okhttp = "4.12.0"
retrofit = "2.11.0"
```

在 library 或 plugin 中引用：

```toml
[libraries]
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
retrofit = { module = "com.squareup.retrofit2:retrofit", version.ref = "retrofit" }
```

### 9.2 `[libraries]`

声明依赖别名：

```toml
[libraries]
androidx-lifecycle-viewmodel-compose = {
    module = "androidx.lifecycle:lifecycle-viewmodel-compose",
    version = "2.8.7"
}
```

在 Kotlin DSL 中使用：

```kotlin
dependencies {
    implementation(libs.androidx.lifecycle.viewmodel.compose)
}
```

别名中的 `-` 会变成 Kotlin 访问时的 `.`：

```text
androidx-lifecycle-viewmodel-compose
  -> libs.androidx.lifecycle.viewmodel.compose
```

### 9.3 `[plugins]`

声明插件别名：

```toml
[plugins]
ksp = { id = "com.google.devtools.ksp", version = "2.0.21-1.0.28" }
```

在 `build.gradle.kts` 中使用：

```kotlin
plugins {
    alias(libs.plugins.ksp)
}
```

### 9.4 `[bundles]`

把一组库打包成一个别名：

```toml
[bundles]
network = ["okhttp", "retrofit", "retrofit-converter-moshi"]
```

使用：

```kotlin
dependencies {
    implementation(libs.bundles.network)
}
```

适合稳定成组出现的依赖。不要为了少写几行就滥用 bundle，否则会让模块真实依赖变得不清楚。

### 9.5 Version Catalog 常见错误

| 问题 | 原因 |
|---|---|
| `Unresolved reference: libs` | Gradle 没识别到 catalog，检查 `gradle/libs.versions.toml` 文件位置 |
| `Unresolved reference: xxx` | TOML 别名和 Kotlin 访问路径不一致 |
| 插件找不到 | `[plugins]` 的 `id` 或版本错误，或 `pluginManagement` 仓库缺失 |
| 依赖找不到 | `[libraries]` 的 `module` 坐标错误，或依赖仓库缺失 |
| 版本冲突 | 多处强行指定版本，或者 BOM 与显式版本混用不合理 |

---

## 10. `gradle.properties`：构建开关和性能参数

常见配置：

```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true

android.useAndroidX=true
android.nonTransitiveRClass=true
android.nonFinalResIds=false

kotlin.code.style=official
kotlin.incremental=true
```

常见字段：

| 配置 | 作用 |
|---|---|
| `org.gradle.jvmargs` | Gradle daemon 的 JVM 参数，内存不足时重点看这里 |
| `org.gradle.parallel` | 允许并行构建互不依赖的模块 |
| `org.gradle.caching` | 开启 Gradle build cache |
| `org.gradle.configuration-cache` | 开启配置缓存，减少重复配置时间 |
| `android.useAndroidX` | 使用 AndroidX |
| `android.nonTransitiveRClass` | 每个模块的 R 类只包含本模块资源，减少耦合和编译影响 |
| `kotlin.incremental` | Kotlin 增量编译 |

注意：

- `configuration-cache` 对构建脚本和插件有要求，老项目可能需要逐步修。
- JVM 内存不是越大越好，过大可能影响机器整体性能。
- 这些参数会影响整个项目和 CI，要和团队保持一致。

---

## 11. `local.properties`：本机私有配置

典型内容：

```properties
sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk
```

它通常由 Android Studio 自动生成，不应该提交到 Git。

有时项目会把本机私有参数也放这里：

```properties
MAP_API_KEY=your-local-key
```

然后在 Gradle 中读取：

```kotlin
import java.util.Properties

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

android {
    defaultConfig {
        buildConfigField(
            "String",
            "MAP_API_KEY",
            "\"${localProperties.getProperty("MAP_API_KEY", "")}\"",
        )
    }
}
```

但要记住：只要写进 APK，客户端就有被逆向提取的可能。真正的服务端密钥不要放进 App。

---

## 12. Gradle Wrapper：固定 Gradle 版本

相关文件：

```text
gradlew
gradlew.bat
gradle/wrapper/gradle-wrapper.properties
```

`gradle-wrapper.properties` 示例：

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.9-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

团队项目应该使用：

```bash
./gradlew assembleDebug
```

而不是直接使用本机安装的 `gradle`。这样能保证本地和 CI 使用同一个 Gradle 版本。

升级 wrapper：

```bash
./gradlew wrapper --gradle-version 8.9
```

升级前要确认 Gradle、Android Gradle Plugin、Kotlin Plugin 的兼容关系。

---

## 13. 签名配置：debug、release 和密钥管理

debug 包通常自动使用 debug keystore。release 包需要正式签名。

示例：

```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file("../keystore/release.jks")
            storePassword = providers.gradleProperty("RELEASE_STORE_PASSWORD").orNull
            keyAlias = providers.gradleProperty("RELEASE_KEY_ALIAS").orNull
            keyPassword = providers.gradleProperty("RELEASE_KEY_PASSWORD").orNull
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
```

正式项目注意：

- keystore 不要提交到普通代码仓库。
- 密码不要明文写进 `build.gradle.kts`。
- CI 中用 secret 注入签名参数。
- 每次发版保存 mapping 文件和构建产物信息。

---

## 14. `BuildConfig`、`resValue` 和 manifest placeholder

### 14.1 `BuildConfig`

```kotlin
android {
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        buildConfigField("String", "API_BASE_URL", "\"https://api.example.com/\"")
        buildConfigField("Boolean", "ENABLE_LOG", "false")
    }
}
```

代码中使用：

```kotlin
if (BuildConfig.ENABLE_LOG) {
    // enable debug log
}
```

### 14.2 `resValue`

```kotlin
android {
    defaultConfig {
        resValue("string", "app_name", "My App")
    }
}
```

适合生成简单资源值。

### 14.3 manifest placeholder

`AndroidManifest.xml`：

```xml
<meta-data
    android:name="com.example.API_HOST"
    android:value="${apiHost}" />
```

`build.gradle.kts`：

```kotlin
android {
    defaultConfig {
        manifestPlaceholders["apiHost"] = "api.example.com"
    }
}
```

常用于渠道号、三方 SDK appId、scheme、host 等需要写进 manifest 的值。

---

## 15. R8、ProGuard 和资源压缩

release 常见配置：

```kotlin
android {
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}
```

`proguard-rules.pro` 示例：

```proguard
-keep class com.example.model.** { *; }
-keepattributes Signature
-keepattributes RuntimeVisibleAnnotations
```

需要重点关注：

- 反射创建的类。
- JSON 序列化和反序列化模型。
- JNI 调用的类和方法。
- WebView JavaScript Bridge。
- 被三方 SDK 文档要求 keep 的类。

不要一遇到问题就写：

```proguard
-keep class ** { *; }
```

这会让混淆和压缩几乎失效，也会增加包体积。

---

## 16. 多模块项目怎么组织配置

一个常见分层：

```text
:app
:feature:home
:feature:profile
:core:common
:core:network
:core:database
:core:ui
```

依赖方向建议：

```text
app -> feature:* -> core:*
feature 之间尽量不要互相直接依赖
core 不依赖 feature
```

模块 build 文件示例：

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.core.network"
    compileSdk = 35

    defaultConfig {
        minSdk = 23
    }
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.retrofit)
}
```

如果每个 library 模块都重复 `compileSdk`、`minSdk`、`compileOptions`，可以逐步抽到 convention plugin。

---

## 17. 常用排查命令

查看所有任务：

```bash
./gradlew tasks
```

构建 debug 包：

```bash
./gradlew assembleDebug
```

构建某个模块：

```bash
./gradlew :app:assembleDebug
```

查看依赖树：

```bash
./gradlew :app:dependencies
```

查看某个依赖为什么被引入：

```bash
./gradlew :app:dependencyInsight --dependency okhttp
```

执行单元测试：

```bash
./gradlew testDebugUnitTest
```

执行 lint：

```bash
./gradlew lintDebug
```

生成构建扫描：

```bash
./gradlew assembleDebug --scan
```

---

## 18. 常见错误和定位方式

| 报错或现象 | 常见原因 | 排查方向 |
|---|---|---|
| `Plugin ... was not found` | 插件仓库缺失、插件 id 错误、插件版本错误 | 查 `settings.gradle.kts` 的 `pluginManagement` 和 `libs.versions.toml` 的 `[plugins]` |
| `Could not find ...` | 依赖坐标错误、仓库缺失、网络或镜像问题 | 查 `dependencyResolutionManagement` 和 `[libraries]` |
| `Unresolved reference: libs` | Version Catalog 未识别 | 查 `gradle/libs.versions.toml` 位置和 settings 配置 |
| `Namespace not specified` | AGP 8+ 要求 Android 模块声明 `namespace` | 在模块 `android {}` 中添加 `namespace` |
| `Duplicate class` | 多个依赖带了相同 class，或新旧依赖混用 | 用 `dependencyInsight` 找来源，统一版本或排除依赖 |
| `Manifest merger failed` | manifest 冲突、placeholder 缺失、权限或组件声明冲突 | 看 merged manifest 报告，检查 `manifestPlaceholders` |
| debug 正常 release 崩溃 | R8 混淆、资源压缩、签名或网络安全配置差异 | 关闭/开启局部开关对比，查看 mapping 和 keep 规则 |
| 构建很慢 | kapt、配置阶段 I/O、模块依赖过重、缓存未开启 | 用 build scan、profile、替换 KSP、优化模块边界 |
| `Unsupported class file major version` | JDK、Gradle、AGP 版本不兼容 | 检查 Gradle wrapper、JDK、AGP 兼容矩阵 |

---

## 19. 一套推荐的阅读顺序

刚接手一个 Android 项目时，可以按这个顺序看配置：

1. 看 `settings.gradle.kts`：有哪些模块、仓库在哪里、是否使用 Version Catalog。
2. 看 `gradle/libs.versions.toml`：AGP、Kotlin、核心依赖版本是多少。
3. 看根目录 `build.gradle.kts`：插件是否集中声明，有没有全局脚本逻辑。
4. 看 `app/build.gradle.kts`：包名、SDK、构建变体、签名、混淆、核心依赖。
5. 看核心模块的 `build.gradle.kts`：模块边界、`api` 泄漏、注解处理器。
6. 看 `gradle.properties`：缓存、JVM、AndroidX、R 类开关。
7. 看 `proguard-rules.pro`：release 包是否有特殊 keep 规则。

---

## 20. 配置文件设计建议

| 建议 | 原因 |
|---|---|
| 版本集中放到 `libs.versions.toml` | 方便统一升级和排查冲突 |
| 模块里只保留模块差异 | 降低重复配置和维护成本 |
| 仓库统一放到 `settings.gradle.kts` | 避免模块私自引入不受控仓库 |
| 能用 `implementation` 就不用 `api` | 减少依赖泄漏和重新编译范围 |
| release 密钥和密码不要进仓库 | 降低泄露风险 |
| flavor 维度要克制 | 避免变体数量爆炸 |
| 开启 R8 后要配合真实 release 验证 | debug 不能覆盖 release 混淆问题 |
| Gradle、AGP、Kotlin、JDK 要成组升级 | 这些版本强相关，单独升级容易破坏构建 |

---

## 21. 最小可用配置示例

`settings.gradle.kts`：

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MyApp"
include(":app")
```

根目录 `build.gradle.kts`：

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}
```

`gradle/libs.versions.toml`：

```toml
[versions]
agp = "8.7.2"
kotlin = "2.0.21"
coreKtx = "1.15.0"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
```

`app/build.gradle.kts`：

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.myapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.myapp"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
```

理解这四个文件后，再去看签名、flavor、R8、CI、convention plugin，就会清楚很多。
