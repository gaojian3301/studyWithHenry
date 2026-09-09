# Gradle Kotlin DSL 入门

> `build.gradle.kts` 不是一种新配置语言，它就是 Kotlin DSL。你在里面看到的 `plugins {}`、`android {}`、`dependencies {}`，本质都是 Gradle 暴露出来的 Kotlin receiver lambda。

---

## 目录

1. [Groovy DSL 和 Kotlin DSL 的区别](#1-groovy-dsl-和-kotlin-dsl-的区别)
2. [`plugins {}`](#2-plugins-)
3. [`android {}`](#3-android-)
4. [`dependencies {}`](#4-dependencies-)
5. [版本管理](#5-版本管理)
6. [常见 Android 配置](#6-常见-android-配置)
7. [自定义 Gradle 任务](#7-自定义-gradle-任务)
8. [Kotlin DSL 为什么有时写法奇怪](#8-kotlin-dsl-为什么有时写法奇怪)
9. [常见误区](#9-常见误区)

---

## 1. Groovy DSL 和 Kotlin DSL 的区别

老项目常见：

```groovy
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
}
```

Kotlin DSL：

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
```

Kotlin DSL 的优点：

- 类型检查更强。
- IDE 补全更好。
- 重构更可靠。
- 和 Kotlin 项目语言一致。

缺点：

- 某些插件文档仍以 Groovy 为主。
- 动态配置迁移时要查 Kotlin 写法。

---

## 2. `plugins {}`

声明当前模块使用哪些插件：

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}
```

常见插件：

| 插件 | 作用 |
|---|---|
| `com.android.application` | Android App 模块 |
| `com.android.library` | Android Library 模块 |
| `org.jetbrains.kotlin.android` | Android Kotlin 支持 |
| `kotlin-kapt` | 注解处理 |
| `com.google.devtools.ksp` | KSP 注解处理 |
| `dagger.hilt.android.plugin` | Hilt 支持 |

---

## 3. `android {}`

Android Gradle Plugin 提供 `android {}` 配置块：

```kotlin
android {
    namespace = "com.example.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.app"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
}
```

从 Kotlin DSL 角度看，`android {}` 是一个 receiver lambda：

```text
android {
  当前 this 是 Android 扩展对象
  所以能直接写 namespace、compileSdk、defaultConfig
}
```

---

## 4. `dependencies {}`

依赖配置：

```kotlin
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
```

常见 configuration：

| 名称 | 含义 |
|---|---|
| `implementation` | 当前模块实现依赖，不向下游暴露 API |
| `api` | library 模块中使用，会向下游暴露 API |
| `compileOnly` | 编译需要，运行时不打包 |
| `runtimeOnly` | 编译不需要，运行时需要 |
| `testImplementation` | 本地单元测试依赖 |
| `androidTestImplementation` | Android instrumentation 测试依赖 |
| `kapt` / `ksp` | 注解处理器依赖 |

库模块里要特别区分 `api` 和 `implementation`。能用 `implementation` 就不要随便用 `api`，避免依赖泄漏。

---

## 5. 版本管理

现代项目常用 Version Catalog：

```toml
# gradle/libs.versions.toml
[versions]
kotlin = "2.0.21"
coreKtx = "1.13.1"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }

[plugins]
android-application = { id = "com.android.application", version = "8.7.0" }
```

在 `build.gradle.kts` 中使用：

```kotlin
plugins {
    alias(libs.plugins.android.application)
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
```

好处：

- 版本集中管理。
- 多模块一致性更好。
- 升级依赖时更容易排查。

---

## 6. 常见 Android 配置

### buildTypes

```kotlin
android {
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}
```

### productFlavors

```kotlin
android {
    flavorDimensions += "channel"

    productFlavors {
        create("dev") {
            dimension = "channel"
            applicationIdSuffix = ".dev"
        }

        create("prod") {
            dimension = "channel"
        }
    }
}
```

### compileOptions 和 kotlinOptions

```kotlin
android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}
```

---

## 7. 自定义 Gradle 任务

注册一个任务：

```kotlin
tasks.register("printVersion") {
    group = "help"
    description = "Print project version"

    doLast {
        println(project.version)
    }
}
```

带类型的任务：

```kotlin
tasks.register<Copy>("copyReadme") {
    from(rootProject.file("README.md"))
    into(layout.buildDirectory.dir("docs"))
}
```

Gradle 推荐尽量用 `register`，避免配置阶段立刻创建所有任务。

---

## 8. Kotlin DSL 为什么有时写法奇怪

因为它是 Kotlin，不能像 Groovy 那样随便动态调用。

Groovy：

```groovy
release {
    minifyEnabled true
}
```

Kotlin：

```kotlin
release {
    isMinifyEnabled = true
}
```

Groovy 的属性名和方法调用更宽松，Kotlin 则需要符合类型和属性命名。

另一个例子：

```kotlin
productFlavors {
    create("dev") {
        dimension = "channel"
    }
}
```

因为 `dev {}` 不是天然存在的强类型方法，需要通过 `create("dev")` 创建。

---

## 9. 常见误区

- 把 Groovy 写法直接复制到 `.kts`，导致语法不通。
- 多模块里到处手写版本号，后期升级困难。
- library 模块滥用 `api`，导致依赖向外泄漏。
- 在配置阶段做耗时 IO，拖慢 Gradle sync。
- 自定义任务直接 `tasks.create`，不如优先 `tasks.register`。
- 不理解 DSL receiver，看到 `android { defaultConfig { } }` 就以为是特殊语法。
