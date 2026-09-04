# 11 Gradle 构建与工程化：从单模块到可维护的大型 App

> App 做大之后，问题经常不在业务代码，而在构建速度、依赖冲突、模块边界、R8、签名、渠道、CI 和版本治理。

---

## 1. Android 构建链路

```text
source code
  -> Kotlin/Javac 编译
  -> Android resource merge / AAPT2
  -> D8/R8
  -> dex
  -> package APK/AAB
  -> sign
  -> install / upload
```

关键角色：

- Gradle：构建系统。
- Android Gradle Plugin：Android 构建任务。
- Kotlin Gradle Plugin：Kotlin 编译。
- AAPT2：资源编译。
- D8/R8：dex 和压缩混淆优化。

---

## 2. 常见项目结构

```text
app
core:common
core:network
core:database
core:ui
feature:home
feature:profile
feature:settings
```

依赖方向：

```text
app -> feature:* -> core:*
feature 之间尽量不直接互相依赖
```

模块化目标不是“模块越多越好”，而是降低编译影响面和业务耦合。

---

## 3. Gradle Kotlin DSL 示例

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("dagger.hilt.android.plugin")
}

android {
    namespace = "com.example.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.app"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
}
```

版本建议放 Version Catalog：

```toml
[versions]
agp = "8.6.0"
kotlin = "2.0.20"

[libraries]
androidx-core = { module = "androidx.core:core-ktx", version = "1.13.1" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
```

---

## 4. buildTypes 和 productFlavors

```kotlin
android {
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
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

    flavorDimensions += "channel"
    productFlavors {
        create("google") { dimension = "channel" }
        create("domestic") { dimension = "channel" }
    }
}
```

变体数量会指数增长，要控制 flavor 维度。

---

## 5. BuildConfig 和资源配置

```kotlin
android {
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        buildConfigField("String", "API_BASE_URL", '"https://api.example.com/"')
        resValue("string", "app_name", "Example")
    }
}
```

不要把密钥直接写进 APK。客户端包里的内容都可以被逆向。

---

## 6. R8 和 keep 规则

```proguard
-keep class com.example.model.** { *; }
-keepattributes Signature
-keepattributes RuntimeVisibleAnnotations
```

排查混淆问题：

- 看 release 崩溃堆栈是否已 mapping 还原。
- 检查反射、序列化、JNI、WebView JS Bridge。
- keep 规则不要一把梭 `-keep class ** { *; }`。

常用文件：

```text
app/build/outputs/mapping/release/mapping.txt
app/build/outputs/mapping/release/seeds.txt
app/build/outputs/mapping/release/usage.txt
```

---

## 7. 构建性能

建议：

- 打开 Gradle build cache。
- 使用 configuration cache。
- 避免 task 在配置阶段做 I/O。
- 控制 kapt，优先 KSP。
- 模块边界稳定。
- 不要在 Gradle 脚本里动态扫大量文件。

命令：

```bash
./gradlew assembleDebug --scan
./gradlew :app:dependencies
./gradlew buildHealth
```

---

## 8. CI/CD

典型流水线：

```text
checkout
  -> restore gradle cache
  -> lint
  -> unit test
  -> assemble
  -> instrumentation test
  -> sign
  -> upload artifact
```

关键点：

- release 签名不要放仓库。
- mapping 文件要归档。
- 每次发版记录 commit、versionCode、渠道、mapping。
- 自动化检查依赖漏洞和 license。

---

## 9. 工程化常见问题

| 问题 | 排查方向 |
|---|---|
| 构建越来越慢 | Gradle scan、kapt、模块依赖 |
| 依赖冲突 | `dependencyInsight`、版本统一 |
| release 崩溃 debug 正常 | R8 keep、资源 shrink、签名差异 |
| CI 偶发失败 | 缓存、并发、测试隔离 |
| 多渠道行为不一致 | flavor 配置、manifest placeholder |

---

## 10. 源码查看建议

| 主题 | 源码入口 |
|---|---|
| AGP 任务 | Android Gradle Plugin 源码 |
| Kotlin 编译 | Kotlin Gradle Plugin |
| 资源编译 | AAPT2 |
| dex | D8 |
| shrink/obfuscate | R8 |

工程化源码不一定要全读，但要能看懂构建日志、任务图和产物目录。
