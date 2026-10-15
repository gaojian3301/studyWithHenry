# Android NDK 与 JNI 详解——从跨语言调用到 .so 加载，再到 Native 崩溃排查

> 姊妹篇：`Android开发者网络串讲-从WiFi到HTTP.md`（分层栈）、`Android蓝牙机制详解-从跳频到GATT与车机互联.md`（能力树）、`Android存储机制详解-从分区到ScopedStorage与多用户.md`（空间层级）。
>
> **这一篇的形状不一样。** NDK 不是"一层套一层"的协议栈，也不是"按场景分叉"的能力树——它的本质是 **Java 世界和原生世界之间的一堵墙**。所以全文按"过桥"来组织：
>
> 1. **过桥的规矩**（JNI 的语言语义：绑定、类型、引用、线程、异常）
> 2. **桥两端的两个世界**（art/Java 堆 vs 原生堆，ABI 与 .so 的加载）
> 3. **修桥与排障**（构建体系、崩溃符号化、sanitizer、安全、平台集成）
>
> **阅读约定**：
> - 「**通用 JNI**」= 和桌面 JVM 一样、不依赖 Android 的部分，只在两端差异明显时才展开。
> - 「**平台视角**」= 只有 platform-signed App / vendor / HAL 才会碰到的部分，写普通 App 可以跳过。
> - 类名与 API 以 Android 13/14 + NDK r25/r26 为主线，涉及版本差异处会标出。

---

## 目录

1. [开场：NDK 是什么，什么时候该用](#一开场ndk-是什么什么时候该用)
2. [坐标系：一次 native 调用的完整旅程](#二坐标系一次-native-调用的完整旅程)
3. [native 方法怎么被找到：两种绑定方式](#三native-方法怎么被找到两种绑定方式)
4. [类型过境：Java ↔ JNI ↔ C++ 全对照](#四类型过境java--jni--c-全对照)
5. [字符串与数组：最容易踩坑的两类参数](#五字符串与数组最容易踩坑的两类参数)
6. [引用：局部、全局、弱全局](#六引用局部全局弱全局)
7. [JNIEnv 与线程：为什么不能跨线程缓存](#七jnienv-与线程为什么不能跨线程缓存)
8. [异常与错误处理：Android 上出错就是 abort](#八异常与错误处理android-上出错就是-abort)
9. [从 native 回调 Java 与性能向 JNI](#九从-native-回调-java-与性能向-jni)
10. [内存：native 堆与 Java 堆是两个世界](#十内存native-堆与-java-堆是两个世界)
11. [ABI 与 .so：一个 APK 里的多种机器码](#十一abi-与-so一个-apk-里的多种机器码)
12. [.so 的加载：搜索路径与命名空间隔离](#十二so-的加载搜索路径与命名空间隔离)
13. [构建：CMake、ndk-build、Android.bp 三套体系](#十三构建cmakendk-buildandroidbp-三套体系)
14. [崩溃排查：tombstone 与符号化](#十四崩溃排查tombstone-与符号化)
15. [Sanitizer 与 native 性能剖析](#十五sanitizer-与-native-性能剖析)
16. [安全：native 是一把双刃剑](#十六安全native-是一把双刃剑)
17. [平台视角：系统 App、HAL 与 VNDK](#十七平台视角系统-apphal-与-vndk)
18. [调试工具箱](#十八调试工具箱)
19. [常见问题排查表](#十九常见问题排查表)
20. [读源码与读文档路线](#二十读源码与读文档路线)
21. [一图总结](#二十一图总结)

---

## 一、开场：NDK 是什么，什么时候该用

### 1.1 先把三个词分开

| 词 | 是什么 | 一句话 |
|---|---|---|
| **JNI** | Java Native Interface，规范 | Java 虚拟机规定的"和原生代码互调"的标准接口。桌面 JVM、Android 都有，**不是 Android 独有** |
| **NDK** | Native Development Kit，工具包 | Google 给 Android 打包的一套"能编译出 Android 上能跑的 .so"的工具链 + 头文件 + 库（clang、linker、`liblog`、`AAudio`…） |
| **原生库（.so）** | 产物 | 用 C/C++（或 Rust）编译出的 ELF 动态库，被 Java 通过 JNI 调用 |

关系：**JNI 是语言层面的契约，NDK 是 Android 上实现这份契约所需的工具和运行时**。写 JNI 不一定要用 NDK（桌面 JVM 也能写），但 Android 上要编出可用 .so 就得用 NDK。

### 1.2 决策：什么时候该下沉到 native

这是 NDK 最容易误用的地方。默认答案永远是"**别用**"，直到有明确理由。

| 场景 | 该用 native 吗 | 原因 |
|---|---|---|
| 复用已有的 C/C++ 库（FFmpeg、OpenSSL、自研算法、跨平台 SDK） | ✅ 该用 | 重写成 Java 的成本远高于桥接 |
| CPU 密集计算（图像处理、音视频编解码、加解密、物理引擎、推理） | ✅ 该用 | 关掉边界检查、可用 NEON/SIMD、无 GC 干扰，通常 3~20 倍 |
| 需要访问 NDK 才暴露的系统能力（`AAudio` 低延迟、`AHardwareBuffer`、`AMediaCodec`、`Vulkan`、`ASharedMemory`） | ✅ 该用 | Java 层没有等价 API |
| 系统 / HAL / 驱动侧对接（VHAL、native AIDL 服务、`libbinder_ndk`） | ✅ 必须 | 这些接口只有 C/C++ 形态 |
| 只是"觉得 Java 慢" | ❌ 别用 | 先测。多数业务瓶颈在 IO、网络、数据库、主线程阻塞，不在语言 |
| 为了"防反编译" | ❌ 别用（作为主要理由） | 反编译门槛提高有限，维护成本却是实打实的；见第十六节 |
| 只有几十行工具函数 | ❌ 别用 | JNI 边界代码本身可能比业务代码还多 |
| 想绕过 Java 的安全/权限模型 | ❌ 做不到 | native 代码仍跑在 App 的 UID 和 SELinux 域里，见第十六节 |

### 1.3 代价清单（决定之前先读完）

1. **崩溃形态更硬**。native 崩了是 SIGSEGV/SIGABRT，整个进程直接死，没有 Java 那套 `try/catch` 和 ANR 提示；必须自己符号化 tombstone（第十四节）。
2. **ABI 组合爆炸**。4 种 ABI × 32/64 位，包体变大、CI 变慢、`UnsatisfiedLinkError` 的一半来源在这里（第十一节）。
3. **构建体系多一套**。Gradle 里嵌 CMake，或 AOSP 里的 Soong/`Android.bp`，排错面变大（第十三节）。
4. **内存要自己管**。GC 不认识 native 内存，漏一个 `free` 就是稳定泄漏（第十节）。
5. **JNI 边界代码容易写错，而且 Android 上一写错就 abort**（第八节），不像桌面 JVM 那样多数只是警告。
6. **调试工具链断层**。Java 侧的 debugger 看不到 C++ 栈，得换 lldb / simpleperf / sanitizer。

### 1.4 三个常见误解

- **"NDK 里不能用 Java 的对象"** —— 能。JNI 就是让你在 C++ 里操作 Java 对象、调用 Java 方法（第九节），只是每一步都要显式过边界。
- **"native 代码更安全"** —— 不。安全边界是 **UID + SELinux 域**，跟语言无关。native 只是让逆向门槛高一点点。
- **"用了 NDK 就摆脱了 ART"** —— 不。native 线程要接进虚拟机（第七节），native 分配的大内存也会算进 App 的内存压力（第十节）。

---

## 二、坐标系：一次 native 调用的完整旅程

### 2.1 全景图

```
┌─────────────────────────────── Java / Kotlin 世界 ───────────────────────────────┐
│  class NativeBridge {                                                            │
│      companion object {                                                          │
│          init { System.loadLibrary("ndkdemo") }      ← 加载期：dlopen + 符号解析   │
│      }                                                                           │
│      external fun encode(src: ByteArray, w: Int, h: Int): Long    ← 调用期        │
│  }                                                                               │
└──────────────────────────────────┬──────────────────────────────────────────────┘
                                   │  ① 参数装箱成 JNI 类型（jint/jbyteArray/…）
                                   ▼
┌─────────────────────── JNI 边界（本层全是"过桥规矩"）────────────────────────────┐
│  · 方法查找：命名约定 或 RegisterNatives（第三节）                                 │
│  · 类型转换：jint/jstring/jobjectArray…（第四节）                                 │
│  · 引用管理：local / global / weak global（第六节）                                │
│  · 线程绑定：JNIEnv 只属于当前线程（第七节）                                        │
│  · 异常：Java 异常 vs C++ 异常（第八节）                                           │
└──────────────────────────────────┬──────────────────────────────────────────────┘
                                   │  ② 进入 C++ 函数，拿到 JNIEnv* + jobject/jclass
                                   ▼
┌──────────────────────────── 原生世界（C/C++）────────────────────────────────────┐
│  libndkdemo.so                                                                   │
│    ├── 业务逻辑（算法、状态机）                                                    │
│    ├── 第三方静态库（libthirdparty.a）                                             │
│    └── 链接的系统库：libc / libm / libc++_shared / liblog / libandroid / libz…     │
│                          （第十三节：CMake / Android.bp 决定链上谁）              │
└──────────────────────────────────┬──────────────────────────────────────────────┘
                                   │  ③ 系统调用 / 驱动
                                   ▼
                          Linux 内核（mmap / ioctl / socket / binder）
                                   │  ④ 出错
                                   ▼
                   SIGSEGV / SIGABRT → debuggerd → tombstone（第十四节）
```

### 2.2 五个阶段，各自会怎么坏

这张表是全篇的导航——**先定位"坏在哪一段"，再翻对应章节**。

| 阶段 | 什么时候发生 | 典型症状 | 章节 |
|---|---|---|---|
| **编译期** | 构建机 | CMake 找不到头文件、`undefined reference`、ABI 参数错 | 十三 |
| **打包期** | 打 APK / 编 image | so 没进包、进了错的 ABI 目录、被 strip 掉符号 | 十一、十三 |
| **加载期** | `System.loadLibrary` | `UnsatisfiedLinkError: dlopen failed…` | 十二 |
| **调用期** | 第一次调 native 方法 | `No implementation found for…`、调用崩溃、JNI DETECTED ERROR abort | 三、四、六、八 |
| **崩溃期** | 运行时任意时刻 | SIGSEGV/SIGABRT、tombstone、地址看不懂 | 十四、十五 |

一个实用的心法：**"没进包/名字不对"看加载期，"进了包但崩"看调用期，两者都用 `readelf` + `logcat -s DEBUG libc linker` 区分。**

---

## 三、native 方法怎么被找到：两种绑定方式

Java 里声明一个 `native` 方法后，ART 需要在某个 .so 里找到对应的 C++ 函数。有两条路。

### 3.1 路一：命名约定（隐式绑定）

函数名按固定规则拼出来：

```
Java_ <类的全限定名，点变下划线> _ <方法名> __ <参数签名，点变下划线>
```

转义规则（**必须记，因为很多"名字明明对了却找不到"都是这里**）：

| 原字符 | 编码后 |
|---|---|
| `.`（包分隔） | `_` |
| `_`（下划线本身） | `_1` |
| `;` | `_2` |
| `[` | `_3` |
| `$`（内部类） | `_00024` |

例子：

```cpp
// Java: package com.example.ndk;  class NativeBridge { native int add(int a, int b); }

// 长名（带签名）：只拼参数，不含返回值；无参方法以 "__" 结尾
extern "C" JNIEXPORT jint JNICALL
Java_com_example_ndk_NativeBridge_add__II(JNIEnv* env, jobject thiz, jint a, jint b);

// 短名（不带签名）：重载时会歧义
extern "C" JNIEXPORT jint JNICALL
Java_com_example_ndk_NativeBridge_add(JNIEnv* env, jobject thiz, jint a, jint b);
```

三个必须注意的点：

1. **`JNIEXPORT` / `JNICALL` 不能省**（前者保证符号可见，后者在 32 位 ARM 上保证调用约定）。
2. **`extern "C"` 不能省**，否则 C++ 会做 name mangling，ART 按 C 规则找必然失败。
3. **ART 的查找顺序是"先短名、后长名"**（`JniShortName` 优先）。这意味着：**只要你的方法有重载，短名就可能撞车**；即使没重载，一旦以后加了重载，还可能找到错的那个。所以——**生产代码一律走路二**。

### 3.2 路二：`RegisterNatives`（显式绑定，推荐）

在 `JNI_OnLoad` 里把 Java 方法和函数指针显式登记进虚拟机：

```cpp
#include <jni.h>
#include <android/log.h>

#define LOG_TAG "ndkdemo"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── 实现 ──────────────────────────────────────────────
static jint impl_add(JNIEnv* /*env*/, jobject /*thiz*/, jint a, jint b) {
    return a + b;
}

// 静态方法第二个参数是 jclass，实例方法第二个参数是 jobject(thiz)
static jstring impl_greet(JNIEnv* env, jclass /*clazz*/, jstring name) {
    if (name == nullptr) return env->NewStringUTF("(null)");
    const char* utf = env->GetStringUTFChars(name, nullptr);
    if (utf == nullptr) return nullptr;                 // 失败时已挂起异常
    std::string s = std::string("hi, ") + utf;
    env->ReleaseStringUTFChars(name, utf);              // 必须释放
    return env->NewStringUTF(s.c_str());
}

// ── 注册表 ────────────────────────────────────────────
static const JNINativeMethod kMethods[] = {
    // Java 方法名, 签名（描述符）, 函数指针
    {"add",   "(II)I",                  reinterpret_cast<void*>(impl_add)},
    {"greet", "(Ljava/lang/String;)Ljava/lang/String;", reinterpret_cast<void*>(impl_greet)},
};

// ── 入口：加载 so 时由虚拟机调用 ────────────────────────
extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;                                 // 版本不支持
    }
    jclass clazz = env->FindClass("com/example/ndk/NativeBridge");
    if (clazz == nullptr) {
        LOGE("FindClass failed");                       // 类名写错 / 类加载器不对
        return JNI_ERR;
    }
    if (env->RegisterNatives(clazz, kMethods,
                             sizeof(kMethods) / sizeof(kMethods[0])) != JNI_OK) {
        env->DeleteLocalRef(clazz);
        return JNI_ERR;                                 // 签名和方法名必须与 Java 侧完全一致
    }
    env->DeleteLocalRef(clazz);                         // 局部引用用完就删
    return JNI_VERSION_1_6;                             // 返回值必须是合法的 JNI 版本
}
```

**签名（描述符）速查**：

| Java 类型 | 描述符 | | Java 类型 | 描述符 |
|---|---|---|---|---|
| `int` | `I` | | `String` | `Ljava/lang/String;` |
| `long` | `J` | | `Object` | `Ljava/lang/Object;` |
| `short` | `S` | | `int[]` | `[I` |
| `byte` | `B` | | `String[]` | `[Ljava/lang/String;` |
| `char` | `C` | | `void`（仅返回值） | `V` |
| `boolean` | `Z` | | 内部类 `Foo.Bar` | `Lcom/x/Foo$Bar;` |
| `float` / `double` | `F` / `D` | | | |

**别手写**。用 JDK 的 `javap` 反编译 class 文件自动获得：

```bash
javap -s -p app/build/tmp/kotlin-classes/debug/com/example/ndk/NativeBridge.class
#   public final native long encode(byte[], int, int);
#     descriptor: ([BII)J          ← 直接抄进 JNINativeMethod
```

这是**最省时间的一招**：签名写错时，Android 会在 `RegisterNatives` 返回失败或调用时抛 `NoSuchMethodError`，用 `javap` 比对 5 秒就能定位。

### 3.3 两种方式对照

| 维度 | 命名约定 | `RegisterNatives` |
|---|---|---|
| 函数名 | 被固定格式绑死，长、易错 | 随便叫，可放匿名 namespace |
| 重载 | 容易歧义 | 表里各写一行，天然支持 |
| 混淆 | 类名一变，符号就废（需 keep 规则） | 类名走字符串，配合 `FindClass` 一处修改 |
| 符号暴露 | 所有实现符号都在动态符号表里，方便逆向 | 可以全部 `static` + `-fvisibility=hidden`，只留 `JNI_OnLoad` |
| 灵活性 | 无法运行时换实现 | 可以按场景注册不同实现（如测试桩） |
| 调试 | 名字即信息，堆栈里一眼看出是哪个 Java 方法 | 需要 map 表才发现对应关系 |
| 推荐 | 玩具 demo | **生产代码** |

### 3.4 Kotlin 侧的写法与坑

```kotlin
package com.example.ndk

object NativeBridge {                       // 单例 → 静态方法
    init {
        System.loadLibrary("ndkdemo")       // 加载 libndkdemo.so
    }
    external fun add(a: Int, b: Int): Int
    external fun greet(name: String?): String
}

class Decoder : AutoCloseable {
    private var handle: Long = nativeCreate()          // 构造函数里就跨界，见第十节
    external fun decode(bytes: ByteArray): Long
    private external fun nativeRelease(h: Long)
    override fun close() = nativeRelease(handle)
    companion object {
        init { System.loadLibrary("ndkdemo") }
        private external fun nativeCreate(): Long
    }
}
```

Kotlin 相关的四个坑：

1. **`object` 里的 `external fun` 是静态方法**（第二个参数是 `jclass`），`class` 里的是实例方法（`jobject`）。写错就崩在调用处。
2. **`init { }` 里 `loadLibrary` 抛异常会让类初始化失败**，表现为 `ExceptionInInitializerError`（真正的 `UnsatisfiedLinkError` 在 `cause` 里）。排查时**一定要看 cause**。
3. **`ByteArray`/`IntArray` 映射到 `jbyteArray`/`jintArray`**，但 `List<Int>`、`Array<Int>` 映射到 `jobjectArray`，处理方式完全不同（第五节）。
4. **`@JvmStatic` 和伴生对象的 `external`** 在符号命名上略有差异；配合命名约定更容易踩坑 → 又一个用 `RegisterNatives` 的理由。

> **冷知识**：`JNI_OnLoad` 只在 `.so` **第一次被加载**时调用一次；同一个 so 被多个类加载器加载会有各自的 `JNI_OnLoad`（这会导致全局状态重复初始化，见第六节）。

---

## 四、类型过境：Java ↔ JNI ↔ C++ 全对照

### 4.1 基本类型

| Java | JNI 类型 | 实际 C 类型 | 字节数 | 备注 |
|---|---|---|---|---|
| `boolean` | `jboolean` | `unsigned char` | 1 | 不是 C 的 `bool`（1 字节），但语义只有 0/1 |
| `byte` | `jbyte` | `signed char` | 1 | Java 的 byte 是**有符号**的 |
| `char` | `jchar` | `unsigned short` | 2 | Java char 是 UTF-16 码元，不是 ASCII |
| `short` | `jshort` | `short` | 2 | |
| `int` | `jint` | `int` | 4 | |
| `long` | `jlong` | `long long` | 8 | ⚠️ **见下方警告** |
| `float` | `jfloat` | `float` | 4 | |
| `double` | `jdouble` | `double` | 8 | |
| `void` | `void` | — | — | |

> ⚠️ **32 位上的经典血案**：`jlong` 在 C 里是 `long long`，不是 `long`。在 **armeabi-v7a / x86** 上，C 的 `long` 只有 4 字节。如果你写
> ```cpp
> static void bad(JNIEnv*, jobject, jlong v) {
>     long x = (long)v;      // 32 位下高 32 位被截断；时间戳、文件大小、句柄全废
> }
> ```
> 在 64 位上一切正常，一到 32 位就出诡异 bug。**规则：所有 Java `long` 一律用 `jlong` 或 `int64_t` 承接。**

### 4.2 引用类型

| Java | JNI 类型 | 说明 |
|---|---|---|
| 任意对象 | `jobject` | 所有对象引用的基类型，实际是不透明指针 |
| `String` | `jstring` | 有自己的字符串 API（第五节） |
| `Class` | `jclass` | `jobject` 的子类型 |
| `Throwable` | `jthrowable` | 抛出/检查异常用 |
| `Object[]` 等 | `jobjectArray` | 每个元素要 `GetObjectArrayElement` 取 |
| 其他 | `jarray` 及具体化 | 见下 |

```cpp
// 从 Java 侧收到的对象，第一步几乎总是拿它的类和方法
jclass cls = env->GetObjectClass(obj);          // 局部引用，用完 DeleteLocalRef
jmethodID mid = env->GetMethodID(cls, "getName", "()Ljava/lang/String;");
```

> **心智模型**：`jobject` **不是** C++ 指针，你**不能**解引用、不能 `static_cast` 成你自己的类。它只是"虚拟机里某个对象的凭据"。想在 C++ 侧持有真实数据，要么让它成为一个真正的 C++ 对象（句柄模式，第十节），要么每次跨界都老老实实调 JNI 函数取值。

### 4.3 数组

| Java | JNI（基本类型） | JNI（对象） |
|---|---|---|
| `int[]` | `jintArray` | `jobjectArray` |
| `byte[]` | `jbyteArray` | |
| `boolean[]` | `jbooleanArray` | |
| `long[]` | `jlongArray` | |
| `String[]` | — | `jobjectArray` |
| `float[]` | `jfloatArray` | |

**没有 `jbyteArray` 的直接内存访问捷径**——必须走 `Get/Release<Type>ArrayElements` 或 `GetPrimitiveArrayCritical`（第五节）。

### 4.4 常见类型的正确写法

```cpp
// ① 把 C++ 的 std::vector<uint8_t> 塞进 Java 的 byte[]
static jbyteArray vec_to_jbytes(JNIEnv* env, const std::vector<uint8_t>& v) {
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(v.size()));
    if (arr == nullptr) return nullptr;                       // OOM，异常已挂起
    // 第 0 个参数是起始下标（jsize 是 jint），别漏
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(v.size()),
                            reinterpret_cast<const jbyte*>(v.data()));
    return arr;                                               // 返回给 Java 的局部引用不用删
}

// ② 反向：Java 的 byte[] → C++
static std::vector<uint8_t> jbytes_to_vec(JNIEnv* env, jbyteArray arr) {
    if (arr == nullptr) return {};
    const jsize n = env->GetArrayLength(arr);
    std::vector<uint8_t> v(static_cast<size_t>(n));
    env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte*>(v.data()));
    return v;
}
```

**优先用 `Get/Set<Type>ArrayRegion`**，而不是 `Get<Type>ArrayElements`：
- `Region` 版本明确拷贝到你自己指定的缓冲区，**没有 `Release` 的配对负担**；
- `Elements` 版本会返回一个可能指向虚拟机内部内存的指针，**必须 `Release`（否则钉住对象、导致 GC 无法搬迁甚至死锁）**，且 `Release` 的第三个参数（`0` / `JNI_COMMIT` / `JNI_ABORT`）语义容易记错。

只有在"整块大数组、想零拷贝"时才考虑 `Elements` 或 `Critical`（第五节）。

### 4.5 `jobjectArray` 的遍历

```cpp
// Java: String[] names
static void walk_strings(JNIEnv* env, jobjectArray names) {
    const jsize n = env->GetArrayLength(names);
    for (jsize i = 0; i < n; ++i) {
        jstring s = static_cast<jstring>(env->GetObjectArrayElement(names, i));  // 新局部引用！
        if (s == nullptr) continue;
        const char* utf = env->GetStringUTFChars(s, nullptr);
        if (utf != nullptr) {
            __android_log_print(ANDROID_LOG_INFO, "ndkdemo", "[%d] %s", i, utf);
            env->ReleaseStringUTFChars(s, utf);
        }
        env->DeleteLocalRef(s);        // 循环里必须删，否则局部引用表溢出（第六节）
    }
}
```

---

## 五、字符串与数组：最容易踩坑的两类参数

### 5.1 字符串：`jstring` 不是 `char*`，而且不是标准 UTF-8

Java 的 `String` 是 UTF-16 序列，JNI 提供两种读取方式，**都有反直觉的地方**：

| API | 返回 | 编码 | 陷阱 |
|---|---|---|---|
| `GetStringUTFChars` | `const char*` | **Modified UTF-8**（CESU-8 变体） | 不是标准 UTF-8！ |
| `GetStringChars` | `const jchar*` | UTF-16 | 要考虑字节序；需配对 `ReleaseStringChars` |
| `GetStringRegion` / `GetStringUTFRegion` | 无（拷进你的缓冲区） | UTF-16 / Modified UTF-8 | **最安全，推荐** |

**Modified UTF-8 的两个变形**：

1. **`\0` 被编码成两字节 `0xC0 0x80`**，所以 `strlen()` 得到的长度是对的（不会提前截断）——这是它的优点。
2. **补充平面字符（emoji、生僻字）用"代理对"两段三字节编码，共 6 字节**，而标准 UTF-8 只要 4 字节。

后果：

```cpp
// Java 侧: String s = "A😀";   // 长度 = 3 个 UTF-16 码元
jstring js = /* ... */;
const jsize u16_len = env->GetStringLength(js);      // 3（UTF-16 码元数）
const jsize utf_len = env->GetStringUTFLength(js);   // 1 + 6 = 7（Modified UTF-8 字节数）

const char* p = env->GetStringUTFChars(js, nullptr);
// 把 p 塞给 libcurl / json 库 / 标准 UTF-8 解码器 → 可能出现乱码或非法序列
// 因为 "😀" 在这里是 ED A0 BD ED B8 80 六个字节，而不是 F0 9F 98 80

// 正确做法：先用 Java 侧或 ICU 转成标准 UTF-8，或者：
std::u16string utf16;
utf16.resize(u16_len);
env->GetStringRegion(js, 0, u16_len, reinterpret_cast<jchar*>(&utf16[0]));
// 再用你自己的 UTF-16 → UTF-8（标准）转换函数
```

**`NewStringUTF` 同样期望 Modified UTF-8**：你传标准 UTF-8 的 emoji 进去，Java 侧会拿到两个垃圾字符（U+FFFD 或乱码）。跨语言传含 emoji 的字符串是这个 bug 的高发区（车机上的"蓝牙设备名""通讯录姓名"尤其常见）。

**RAII 封装（强烈建议每个项目都抄一份）**：

```cpp
class ScopedJStringUtf {
public:
    ScopedJStringUtf(JNIEnv* env, jstring s) : env_(env), js_(s) {
        if (s != nullptr) p_ = env->GetStringUTFChars(s, nullptr);
    }
    ~ScopedJStringUtf() {
        if (p_ != nullptr) env_->ReleaseStringUTFChars(js_, p_);
    }
    ScopedJStringUtf(const ScopedJStringUtf&) = delete;
    ScopedJStringUtf& operator=(const ScopedJStringUtf&) = delete;

    const char* get() const { return p_; }
    explicit operator bool() const { return p_ != nullptr; }

private:
    JNIEnv* env_;
    jstring js_;
    const char* p_ = nullptr;
};

// 用法：Get/Release 永远配对，中途 return 也不会漏
static void log_title(JNIEnv* env, jstring title) {
    ScopedJStringUtf t(env, title);
    if (!t) return;
    LOGI("title=%s", t.get());
}
```

> 平台代码里同类的轮子：`libnativehelper` 的 `ScopedLocalRef`、`scoped_utf_chars`（见第二十节）。自己写业务时抄这个思路即可。

### 5.2 数组的三种访问方式与代价

| 方式 | 会不会拷贝 | 能否在区间内调 JNI | 适用 |
|---|---|---|---|
| `Get/Set<Type>ArrayRegion` | 一定拷贝 | ✅ 可以 | **默认选择** |
| `Get<Type>ArrayElements` + `Release` | 可能拷贝（看 `isCopy`） | ✅ 可以 | 需要原地修改且数组小 |
| `GetPrimitiveArrayCritical` + `Release` | 通常不拷贝（钉住内存） | ❌ **绝对不行** | 大数组、性能敏感 |

```cpp
// Elements 版本的完整姿势：一定要检查 isCopy，一定要 Release
jint* p = env->GetIntArrayElements(arr, &isCopy);
if (p == nullptr) return;                 // 失败（异常已挂起）
// isCopy == JNI_TRUE 说明你拿到的是副本，改完了必须"提交"回去
for (jsize i = 0; i < n; ++i) p[i] *= 2;
env->ReleaseIntArrayElements(arr, p, 0);   // 0 = 拷回并释放
// 改完不想拷回 → JNI_ABORT；只要拷回不释放（很罕见）→ JNI_COMMIT
```

### 5.3 `Critical` 区的三条禁忌

```cpp
jbyte* p = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(arr, nullptr));
if (p != nullptr) {
    // ✅ 可以：纯计算、memcpy、写自己的缓冲区
    std::memcpy(dst, p, len);

    // ❌ 绝对不可以：调用任何其他 JNI 函数（GetObjectClass/FindClass/CallXxx…）
    // ❌ 绝对不可以：阻塞（IO、锁、sleep）—— ART 会打印
    //      "JNI critical lock held for 200ms" 并可能直接 abort
    // ❌ 绝对不可以：在该区间内把控制权交给别的线程去碰同一个数组
    env->ReleasePrimitiveArrayCritical(arr, p, JNI_ABORT);
}
```

> **一句话原则**：`Critical` 区里只做"纯内存操作"，代码行数越少越好。Android 上 `Critical` 区是**按线程计数**的，嵌套/超时都会被记录，CheckJNI 模式下更严。

### 5.4 大块数据怎么传（性能路线）

| 数据量 | 推荐方式 | 说明 |
|---|---|---|
| < 几 KB | `byte[]` + `SetByteArrayRegion` | 简单优先，一次拷贝可忽略 |
| 几十 KB ~ 几 MB | `ByteBuffer.allocateDirect` + `GetDirectBufferAddress` | **零拷贝**，native 侧直接拿到长期有效地址 |
| 超大缓冲 / 跨进程共享 | `ASharedMemory`（`ASharedMemory_create` + `ASharedMemory_getSize`），或 `AHardwareBuffer`（图形/编解码） | 见第十节 |

```cpp
// Java: ByteBuffer buf = ByteBuffer.allocateDirect(size);   // 必须是 direct！
static void fill_direct(JNIEnv* env, jobject /*thiz*/, jobject byteBuffer) {
    void* addr = env->GetDirectBufferAddress(byteBuffer);
    const jlong cap = env->GetDirectBufferCapacity(byteBuffer);
    if (addr == nullptr || cap <= 0) return;     // 传了非 direct 的 ByteBuffer → 返回 null
    std::memset(addr, 0, static_cast<size_t>(cap));
    // 注意：direct buffer 的地址在 Java 侧仍持有引用期间有效；
    // 不要把它缓存下来在 Java 对象可能被回收后再用
}
```

---

## 六、引用：局部、全局、弱全局

这是 JNI 里**最容易被忽视、又最容易造成 abort** 的一块。

### 6.1 局部引用（Local Reference）

- **生命周期**：本次 native 方法调用期间（严格说是创建它的那个 native 调用帧）。
- **归属**：**按线程**维护一张"局部引用表"。
- **上限**：Android/ART 默认 **512 项**（具体数值以版本为准），超了直接 abort：

```
JNI ERROR (app bug): local reference table overflow (max=512)
```

- **自动释放**：native 方法返回时，本次调用期间创建的所有局部引用被批量释放。**所以返回值要 `return` 而不是在返回前删掉。**

```cpp
// ❌ 经典崩溃：循环里每次都创建一个新局部引用
static void bad(JNIEnv* env, jobjectArray arr) {
    for (jsize i = 0; i < env->GetArrayLength(arr); ++i) {
        jstring s = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
        use(s);
        // 忘了 DeleteLocalRef(s) → 数组长度 > 512 时直接 abort
    }
}

// ✅ 正确：或者用 PushLocalFrame 批量管理
static void good(JNIEnv* env, jobjectArray arr) {
    const jsize n = env->GetArrayLength(arr);
    for (jsize i = 0; i < n; ++i) {
        jstring s = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
        use(s);
        env->DeleteLocalRef(s);                  // 每次循环删掉
    }
}

// ✅ 或者：一次性开帧
static void framed(JNIEnv* env, jobjectArray arr) {
    const jsize n = env->GetArrayLength(arr);
    for (jsize i = 0; i < n; ++i) {
        if (env->PushLocalFrame(16) != JNI_OK) return;   // 预留 16 个位置
        jstring s = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
        use(s);
        env->PopLocalFrame(nullptr);                     // 帧内所有局部引用一次性释放
    }
}
```

### 6.2 全局引用（Global Reference）

- 用 `NewGlobalRef` 创建，**显式 `DeleteGlobalRef` 才会释放**。
- **可以跨线程使用**，是"在 C++ 里长期持有 Java 对象"的唯一正确方式。
- 忘了删 → Java 侧对象**永远回收不掉**，就是稳定的 native 引起的 Java 内存泄漏。

```cpp
struct JavaCallback {
    JavaVM* vm = nullptr;
    jobject listener = nullptr;      // 全局引用
    jmethodID onProgress = nullptr;  // methodID 是稳定的，可缓存

    bool init(JNIEnv* env, jobject l) {
        listener = env->NewGlobalRef(l);
        if (listener == nullptr) return false;
        jclass cls = env->GetObjectClass(listener);
        onProgress = env->GetMethodID(cls, "onProgress", "(IJ)V");
        env->DeleteLocalRef(cls);
        return onProgress != nullptr;
    }

    ~JavaCallback() {
        // 析构时可能已经不在原来的线程，必须重新 attach（第七节）
        JNIEnv* env = nullptr;
        if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return;
        if (listener != nullptr) env->DeleteGlobalRef(listener);
    }
};
```

**高频坑**：`GetObjectClass(obj)` 返回的是**局部引用**。如果你想长期保存这个 class，必须 `NewGlobalRef` 一份：

```cpp
jclass local = env->FindClass("com/example/Foo");
g_foo_class = static_cast<jclass>(env->NewGlobalRef(local));   // ✅ 长期保存用这个
env->DeleteLocalRef(local);

// 之后任何线程都可以：
// jmethodID mid = env->GetStaticMethodID(g_foo_class, "of", "(I)Lcom/example/Foo;");
```

### 6.3 弱全局引用（Weak Global Reference）

跟 Java 的 `WeakReference` 一个意思：**不阻止 GC 回收**。适合"缓存一个类，回收了再重新找"的场景。判断是否已被回收靠跟 `nullptr` 比较：

```cpp
jweak w = env->NewWeakGlobalRef(obj);
// ... 之后 ...
if (env->IsSameObject(w, nullptr)) {
    // 对象已被 GC 回收，弱引用失效
} else {
    jobject strong = env->NewLocalRef(w);   // 用之前先提升为局部引用，避免竞争
    // 用完 DeleteLocalRef(strong)
}
env->DeleteWeakGlobalRef(w);
```

### 6.4 一张表：什么时候用哪种

| 需求 | 用哪种 | 生命周期管理 |
|---|---|---|
| 方法调用期间的临时对象 | 局部引用 | 自动；循环里手动删 |
| 一次调用里的大量临时对象 | `PushLocalFrame`/`PopLocalFrame` | 自动批量 |
| 长期缓存的 `jclass` / `jmethodID` | 全局引用（`jclass`）；`jmethodID` 本身稳定 | 显式 `DeleteGlobalRef` |
| 注册的回调 listener / `Activity` 引用 | 全局引用 | 显式删除，注意泄漏 |
| 可选缓存、不想阻止回收 | 弱全局引用 | 显式删除 + `IsSameObject(…, nullptr)` 判活 |
| 返回值 | 局部引用 | 直接 `return`，不要删 |

---

## 七、JNIEnv 与线程：为什么不能跨线程缓存

### 7.1 两个东西，两种作用域

| | `JNIEnv*` | `JavaVM*` |
|---|---|---|
| 作用域 | **当前线程** | 整个进程 |
| 能否缓存到全局变量 | ❌ **绝对不能** | ✅ 可以，且应该 |
| 获取方式 | 函数参数传入 / `GetEnv` / `AttachCurrentThread` | `JNI_OnLoad` 的参数 |
| 跨线程使用后果 | `JNI DETECTED ERROR: JNIEnv is not valid for this thread` → **abort** | 用来 attach 别的线程 |

> **血案模板**：一个 `static JNIEnv* g_env;` 在第一次调用时被赋值，之后被后台线程使用。开发机单线程跑得挺好，一上多线程就随机 abort。**任何把 `JNIEnv*` 存进成员变量/全局变量的代码都是错的**（唯一例外：存在"本次调用栈"上并只在本次调用里用）。

### 7.2 原生线程接入虚拟机的标准模板

```cpp
static JavaVM* g_vm = nullptr;      // 进程级，可以放心缓存

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    // ... 注册方法 ...
    return JNI_VERSION_1_6;
}

// 任何由 C++ 自己创建、需要回调 Java 的线程，都必须走这个函数
static bool attach_current_thread(JNIEnv** out_env) {
    if (g_vm == nullptr) return false;
    jint r = g_vm->GetEnv(reinterpret_cast<void**>(out_env), JNI_VERSION_1_6);
    if (r == JNI_OK) return false;                 // 已经 attach 过，返回 false 表示"不需要 detach"
    if (r == JNI_EDETACHED) {
        JavaVMAttachArgs args{
            JNI_VERSION_1_6,
            "ndk-worker",     // 线程名 —— 出问题时 tombstone/logcat 里能看到，救命
            nullptr           // 不需要特定 ClassLoader 时传 null
        };
        if (g_vm->AttachCurrentThread(out_env, &args) != JNI_OK) return false;
        return true;                                   // 返回 true 表示"是本次 attach 的，必须 detach"
    }
    return false;
}

void worker_thread() {
    JNIEnv* env = nullptr;
    const bool need_detach = attach_current_thread(&env);
    if (env != nullptr) {
        // 这里才能安全地使用 env 调 Java
    }
    if (need_detach) {
        // ⚠️ 线程退出前必须 detach，否则 ART 直接 abort：
        //    "native thread exiting without having called DetachCurrentThread"
        g_vm->DetachCurrentThread();
    }
}
```

三条铁律：

1. **`JNIEnv*` 不能跨线程**；
2. **原生线程退出前必须 `DetachCurrentThread`**（否则 abort，且报错文案很明确，看到就懂了）；
3. **不要 attach 主线程**（主线程本来就 attach 着，`GetEnv` 会返回 `JNI_OK`）。

### 7.3 排查小技巧

```bash
# 看 ART 报的线程问题（关键字很明确）
adb logcat -s art libc DEBUG | grep -iE "JNI|thread|Detach"

# 从 tombstone 里看线程名（attach 时起的名字会出现在 name: 字段）
adb shell cat /data/tombstones/tombstone_00 | grep -E "^(pid|name):"
```

> 给线程起名字（`JavaVMAttachArgs.name`、`pthread_setname_np`）是**性价比最高的调试投资**：native 崩溃的 tombstone 里只给你 tid 和线程名，没名字就只能靠猜。

---

## 八、异常与错误处理：Android 上出错就是 abort

### 8.1 与桌面 JVM 的最大差异

| | 桌面 JVM | Android ART |
|---|---|---|
| JNI 使用错误（越界引用、跨线程 `JNIEnv`） | 多数只是打印警告，程序继续跑 | **默认直接 abort 整个进程** |
| 报错文案 | `WARNING in native method: ...` | `JNI DETECTED ERROR IN APPLICATION: ...` + `Abort message:` |
| 严格检查开关 | `-Xcheck:jni` | `adb shell setprop dalvik.vm.checkjni true`（或 `-Xcheck:jni`，需可调试） |

**含义**：Android 上 JNI 错误不是"可能出错"，而是"**一定会死**"。写 JNI 时要按"每个 JNI 调用都可能失败"的心态写。

### 8.2 三条铁律

1. **每个可能失败的 JNI 调用后，都要思考"失败了怎么办"**（`FindClass`、`GetMethodID`、`GetStringUTFChars`、`New*Array` 都可能失败并挂起异常）。
2. **带着未处理的异常不能继续调 JNI**。要么 `ExceptionClear()` 处理掉，要么立刻 `return` 让异常传播回 Java。
3. **不要从 C++ 异常穿过 JNI 边界回到 Java**（Java 侧不认 `std::exception`，行为未定义/直接崩）。

### 8.3 抛 Java 异常的正确姿势

```cpp
static jlong safe_open(JNIEnv* env, jobject, jstring path) {
    ScopedJStringUtf p(env, path);
    if (!p) return 0;                                  // 取字符串就失败了，异常已挂起，直接返回

    jlong h = native_open(p.get());
    if (h == 0) {
        // 方法一：直接抛一个新异常（推荐，最简单）
        jclass cls = env->FindClass("java/io/IOException");
        if (cls != nullptr) {
            env->ThrowNew(cls, "native_open failed: device busy");
            env->DeleteLocalRef(cls);
        }
        return 0;                                      // 返回值无意义，Java 侧会收到异常
    }
    return h;
}
```

其他工具：

```cpp
// 检查是否有异常挂起（不消费）
if (env->ExceptionCheck()) { /* ... */ }

// 取出异常对象（会清掉挂起状态），自己判断类型后决定吃掉还是重抛
jthrowable t = env->ExceptionOccurred();
if (t != nullptr) {
    env->ExceptionClear();
    // ... 处理 ...
    env->Throw(t);              // 决定重抛
    env->DeleteLocalRef(t);
}

// 打印调用栈到 stderr（调试用）
env->ExceptionDescribe();

// 主动清理（确认要吃掉异常时）
env->ExceptionClear();
```

### 8.4 `JNI DETECTED ERROR` 对照表（这张表能省你几天）

| abort message 里的片段 | 真正原因 | 修法 |
|---|---|---|
| `local reference table overflow (max=512)` | 循环里没 `DeleteLocalRef` | 第六节 |
| `JNIEnv is not valid for this thread` / `using JNIEnv from a different thread` | 缓存了 `JNIEnv*` | 第七节 |
| `native thread exiting without having called DetachCurrentThread` | 忘了 `Detach` | 第七节 |
| `use of deleted global reference` | `DeleteGlobalRef` 之后还在用（常见于回调时对象已销毁） | 加生命周期标志位 |
| `jobject is an invalid local reference` | 局部引用越界（在别的调用里用了上次的引用） | 提升为全局引用，或改为本次重新获取 |
| `... called with pending exception` | 上一处异常没清理就继续调 JNI | 第八节三条铁律 |
| `GetFieldID called with pending exception` / `no "I" field "mCount" in class ...` | 字段名/签名写错，或类被混淆改名 | `javap` 核对；混淆 keep 规则 |
| `GetMethodID called with pending exception` / `no method ...` | 方法名或**签名**写错（重载时最容易） | 同上，`javap -s` |
| `can't call CallVoidMethod on a null jobject` | 传入的 Java 对象是 null，或已被回收 | 调用前判空 |
| `JNI critical lock held for ...ms` | `Critical` 区里做了耗时/阻塞操作 | 第五节 |
| `JNI ERROR (app bug): accessed stale local reference` | 跨线程使用了别的线程的局部引用 | 第六节 6.2 |
| `Cannot allocate memory` / `Failed to allocate ...` | 引用/内存耗尽 | 检查泄漏 |

> **读法提示**：这类 abort 的 tombstone 里，`Abort message:` 那一行就是根因；`backtrace` 里找第一帧属于**你的 so** 的函数（跳过 `libart.so` 的 JNI trampoline）。

---

## 九、从 native 回调 Java 与性能向 JNI

### 9.1 调用 Java 方法四步曲

```cpp
// ① 拿到类（缓存成全局引用，别每次 FindClass）
// ② 拿到 methodID（缓存起来，它是稳定的）
// ③ 准备参数（基本类型直接传，对象要已经是 jobject）
// ④ 按返回类型选 Call<Type>Method

void notify_progress(JNIEnv* env, JavaCallback& cb, jlong done, jint percent) {
    if (cb.listener == nullptr || cb.onProgress == nullptr) return;
    env->CallVoidMethod(cb.listener, cb.onProgress, done, percent);
    // 调用后要检查异常：Java 侧抛错会挂起在这里，不检查就会在下一个 JNI 调用处爆炸
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();       // 开发期打印
        env->ExceptionClear();          // 或者让它传播：直接 return
    }
}
```

`Call<Type>Method` 家族与返回类型一一对应：

| Java 返回 | 函数 |
|---|---|
| `void` | `CallVoidMethod` |
| `int` / `long` / `boolean` / `float` / `double` / … | `CallIntMethod` / `CallLongMethod` / `CallBooleanMethod` / `CallFloatMethod` / `CallDoubleMethod` |
| 对象（`String`、`Object`、数组…） | `CallObjectMethod`（返回 `jobject`，需 `static_cast` 到具体类型） |
| 静态方法 | 换成 `CallStatic<Type>Method`，第一个参数是 `jclass` |
| 可变参数 | `Call<Type>MethodV`（`va_list`）或 `Call<Type>MethodA`（`jvalue*` 数组） |

### 9.2 `methodID` / `jclass` 缓存策略

```cpp
class JniCache {
public:
    static JniCache& instance() { static JniCache c; return c; }

    bool init(JNIEnv* env) {
        jclass local = env->FindClass("com/example/ndk/ProgressListener");
        if (local == nullptr) return false;
        clazz_ = static_cast<jclass>(env->NewGlobalRef(local));   // 必须提升为全局
        env->DeleteLocalRef(local);
        if (clazz_ == nullptr) return false;

        onProgress_ = env->GetMethodID(clazz_, "onProgress", "(IJ)V");
        return onProgress_ != nullptr;
    }

private:
    jclass clazz_ = nullptr;          // 全局引用，进程级存活
    jmethodID onProgress_ = nullptr;  // methodID 本身稳定，不需要 NewGlobalRef
};

// ✅ methodID 可以随便缓存成静态变量
// ❌ jclass 拿到局部引用就缓存 = 下次调用就是 "invalid local reference"
```

**注意 `FindClass` 的类加载器语义**：在 `JNI_OnLoad`（由 `System.loadLibrary` 触发）里调用 `FindClass`，用的是**加载这个 so 的那个类加载器**；而在一个原生创建的、被 `AttachCurrentThread` 附加上来的线程里调用 `FindClass`，用的是**系统类加载器**——**找不到 App 自己的类**。原生线程里要用 App 类，要么通过 `JavaVMAttachArgs.class_loader` 传入合适加载器（API 受限，且多数版本不支持随意设置），要么从 Java 侧先把 `jclass` 传进来缓存好。这是"回调时 `FindClass` 返回 null"的标准答案。

### 9.3 JNI 调用开销从哪来

一次 `CallVoidMethod` 的成本大致来自：

1. **跨边界检查**：ART 要校验 `jobject` 有效性、`methodID` 归属、参数类型（CheckJNI 下更严）。
2. **引用簿记**：每次返回对象都可能新建局部引用（进入局部引用表，需要内存 + 后续批量清理）。
3. **无法内联**：JNI 调用点是虚调用，编译器看不到实现，寄存器分配/内联都做不了。
4. **参数装箱**：基本类型没问题，但传 `Integer`/`String` 会产生对象（真正的开销大头）。

**量级直觉**：一次空的 JNI 调用通常比一次普通 Java 方法调用贵 **一个数量级左右**；`GetStringUTFChars` 这类字符串操作贵更多。所以**设计原则是"少跨界、批量传"**，而不是"把每个 getter 都下沉"。

### 9.4 性能向的三板斧

**① 批量化：一次传整块数据，别循环跨界**

```cpp
// ❌ 每个像素一次跨界（百万级调用，纯粹浪费）
for (int i = 0; i < n; ++i) {
    env->CallVoidMethod(listener, onPixel, i, data[i]);
}

// ✅ 整块传，Java 侧循环
jbyteArray all = vec_to_jbytes(env, data);
env->CallVoidMethod(listener, onBlock, all, 0, (jint)data.size());
env->DeleteLocalRef(all);        // 返回值是局部引用，用完删
```

**② 句柄化（handle）：把 C++ 对象变成 Java 侧的一个 `long`**

这是 native 侧持有状态的**标准模式**，几乎每个成熟 SDK 都这么写：

```cpp
// C++ 侧的会话对象
struct Session {
    explicit Session(int fd) : fd_(fd) {}
    ~Session() { if (fd_ >= 0) ::close(fd_); }
    int fd_;
    std::mutex mu_;
};

// Java 侧只看到一个不透明句柄
// public final class Session implements AutoCloseable {
//     private long handle;                      // 0 表示已释放
//     private native long nativeCreate(int fd);
//     private native void nativeClose(long h);
//     private native int nativeRead(long h, byte[] dst);
//     public void close() { if (handle != 0) { nativeClose(handle); handle = 0; } }
// }

static jlong nativeCreate(JNIEnv*, jobject, jint fd) {
    Session* s = new (std::nothrow) Session(fd);   // ⚠️ native 的 new 失败不抛 OutOfMemoryError
    return reinterpret_cast<jlong>(s);              // 指针塞进 long（64 位也放得下）
}

static void nativeClose(JNIEnv*, jobject, jlong h) {
    delete reinterpret_cast<Session*>(h);           // 谁分配谁释放
}

static jint nativeRead(JNIEnv* env, jobject, jlong h, jbyteArray dst) {
    Session* s = reinterpret_cast<Session*>(h);
    if (s == nullptr) return -1;                    // 必须判空：Java 侧可能传 0 或已释放的句柄
    std::lock_guard<std::mutex> lk(s->mu_);
    // ...
    return 0;
}
```

这个模式的三个要点：

- **`reinterpret_cast<jlong>(ptr)` 在 32/64 位都安全**（指针 4 或 8 字节，`jlong` 恒 8 字节）；反过来 `reinterpret_cast<Session*>(jlong)` 在 32 位下**会截断高 32 位**——所以要用 `reinterpret_cast<Session*>(static_cast<uintptr_t>(h))` 或直接 `(Session*)(intptr_t)h` 的写法，避免编译器在 32 位上按 `jlong` 处理地址。**32 位机型上句柄错乱的一类根因。**
- **对象释放后必须把 Java 侧句柄置 0**（`close()` 里做），否则就是 use-after-free。
- **多线程下句柄本身没锁保护**：`nativeClose` 与 `nativeRead` 并发时会 UAF。生产代码要么在 Java 侧加锁/串行化，要么在 C++ 侧用"句柄表 + 引用计数"（`std::shared_ptr<Session>` 存在全局 map 里，句柄是 map 的 key），这样即使并发也不会踩野指针。**句柄表方案更稳，推荐给会被多线程访问的 SDK。**

**③ `@FastNative` / `@CriticalNative`（平台视角，App 用不了）**

```java
// AOSP 里的写法（@hide 注解，属于 libcore 引导类路径）
@CriticalNative
private static native int nativeAtomicAdd(int a, int b);

@FastNative
private static native void nativeSetUp();      // 仍需 JNIEnv，但是快速路径
```

| 注解 | 效果 | 约束 |
|---|---|---|
| `@FastNative` | 跳过部分检查，不加同步/线程状态切换开销 | 不能抛异常、不能长时间阻塞 |
| `@CriticalNative` | **完全不传 `JNIEnv*` 和 `jclass`**，参数只能是基本类型 | 只能是 `static`，**绝不能抛 Java 异常**，不能调任何 JNI 函数，不能分配 |

普通 App 无法使用（注解在 boot classpath 上，ART 按描述符校验）。**你如果做平台开发，给高频小函数（如原子计数、属性读写）加 `@CriticalNative` 能把开销砍掉一大半**；但用错（在里面抛异常）会直接 abort。

### 9.5 一个完整例子：native 计算 + 进度回调 + 取消

```cpp
// Java:
// public interface TaskListener { void onProgress(int percent); boolean isCancelled(); }
// public final class Job {
//     private volatile long handle;
//     public Job(TaskListener l) { handle = nativeCreate(l); }
//     public boolean run(byte[] input) { return nativeRun(handle, input); }
//     public void cancel() { nativeCancel(handle); }
//     public void release() { nativeRelease(handle); handle = 0; }
//     private static native long nativeCreate(TaskListener l);
//     private static native boolean nativeRun(long h, byte[] input);
//     private static native void nativeCancel(long h);
//     private static native void nativeRelease(long h);
// }

struct Job {
    JavaVM* vm = nullptr;
    jobject listener = nullptr;        // 全局引用
    jmethodID onProgress = nullptr;
    jmethodID isCancelled = nullptr;
    std::atomic<bool> cancelled{false};
};

static jlong nativeCreate(JNIEnv* env, jclass, jobject listener) {
    auto* j = new (std::nothrow) Job();
    if (j == nullptr) return 0;
    env->GetJavaVM(&j->vm);
    j->listener = env->NewGlobalRef(listener);          // 必须全局引用
    if (j->listener == nullptr) { delete j; return 0; }

    jclass cls = env->GetObjectClass(listener);
    j->onProgress  = env->GetMethodID(cls, "onProgress", "(I)V");
    j->isCancelled = env->GetMethodID(cls, "isCancelled", "()Z");
    env->DeleteLocalRef(cls);
    if (j->onProgress == nullptr || j->isCancelled == nullptr) {
        env->DeleteGlobalRef(j->listener);
        delete j;
        return 0;
    }
    return reinterpret_cast<jlong>(j);
}

static bool nativeRun(JNIEnv* env, jclass, jlong h, jbyteArray input) {
    auto* j = reinterpret_cast<Job*>(reinterpret_cast<uintptr_t>(h));
    if (j == nullptr) return false;
    const auto data = jbytes_to_vec(env, input);

    for (int i = 0; i < 100; ++i) {
        // 查取消：每次跨一次边界，但相比"每像素一次"已经便宜得多
        const jboolean c = env->CallBooleanMethod(j->listener, j->isCancelled);
        if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
        if (c) return false;

        cpu_heavy_step(data, i);

        // 报进度：低频跨界（比如每 5%）
        if (i % 5 == 0) {
            env->CallVoidMethod(j->listener, j->onProgress, i);
            if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
        }
    }
    return true;
}

static void nativeRelease(JNIEnv* env, jclass, jlong h) {
    auto* j = reinterpret_cast<Job*>(reinterpret_cast<uintptr_t>(h));
    if (j == nullptr) return;
    if (j->listener != nullptr) env->DeleteGlobalRef(j->listener);   // 必须删，否则泄漏 Java 对象
    delete j;
}
```

这个例子同时演示了第九节所有要点：**全局引用、methodID 缓存、异常检查、批量传参、低频回调、句柄模式**。

---

## 十、内存：native 堆与 Java 堆是两个世界

### 10.1 一张图看清内存版图

```
┌──────────────────────── App 进程的虚拟地址空间 ────────────────────────┐
│                                                                      │
│  Java 堆（ART heap）              Native 堆（malloc arena / mmap）     │
│  ├─ GC 管理，自动回收              ├─ 谁也管不了，只有你 free/delete   │
│  ├─ 上限由 dalvik.vm.heapsize      ├─ 上限是"设备还剩多少内存"          │
│  │   / largeHeap / 设备 RAM 决定   ├─ 分配失败 = nullptr / bad_alloc   │
│  ├─ 满了 → OutOfMemoryError        ├─ 满了 → abort / SIGABRT / 被 LMK 杀 │
│  └─ dumpsys meminfo 的 "Java Heap" └─ dumpsys meminfo 的 "Native Heap"  │
│                                                                      │
│  代码区：.so / .oat / .art（只读，mmap 自 APK 或分区）                  │
│  图形内存：gralloc / AHardwareBuffer（进程外，由 SurfaceFlinger 记账）   │
└──────────────────────────────────────────────────────────────────────┘
```

**核心结论：GC 完全不知道 native 内存的存在。** 你在 C++ 里 `new` 了 1GB，Java 侧不会有任何内存压力信号，GC 不会更勤快地跑，`OutOfMemoryError` 也不会更早到来——直到系统 LMK（low memory killer）按"谁占得多"把整个进程干掉。

### 10.2 分配失败的两种世界

| | Java 侧 | Native 侧 |
|---|---|---|
| 失败表现 | 抛 `OutOfMemoryError` | `malloc` 返回 `nullptr`；`new` 抛 `std::bad_alloc` |
| 能否捕获 | `catch (OutOfMemoryError)` | `nullptr` 判断 / `nothrow` / C++ try-catch |
| 未处理后果 | 崩溃（Java 栈可读） | `std::terminate` → `abort` → SIGABRT（栈里只有 libc++） |
| 内存统计 | Java Heap 列 | Native Heap 列 |

```cpp
// ✅ C++ 侧分配大块内存的三种写法
// ① nothrow：最常用，不引入 C++ 异常
Session* s = new (std::nothrow) Session(fd);
if (s == nullptr) { /* 上报给 Java 侧，抛一个 OutOfMemoryError 更友好 */ }

// ② 智能指针 + 明确的失败路径（RAII，强烈推荐）
auto buf = std::make_unique<uint8_t[]>(size);   // 失败会抛 bad_alloc
// 别让 bad_alloc 穿过 JNI 边界：包一层 try-catch（见下）

// ③ 把 C++ 异常挡在边界内
static jlong create_session(JNIEnv* env, jobject, jint fd) {
    try {
        auto* s = new Session(fd);
        return reinterpret_cast<jlong>(s);
    } catch (const std::bad_alloc&) {
        jclass e = env->FindClass("java/lang/OutOfMemoryError");
        if (e) { env->ThrowNew(e, "native OOM"); env->DeleteLocalRef(e); }
        return 0;
    } catch (const std::exception& e) {
        jclass t = env->FindClass("java/lang/RuntimeException");
        if (t) { env->ThrowNew(t, e.what()); env->DeleteLocalRef(t); }
        return 0;
    }
}
```

> ⚠️ **最容易忘记的一条**：如果编译时带 `-fno-exceptions`（NDK 项目常见的省体积选项），`new` 失败**不会**抛异常而是直接 abort。此时**必须用 `nothrow` 版本**。

### 10.3 谁分配谁释放：一张清单

| 你调用了 | 必须配对调用 | 忘了会怎样 |
|---|---|---|
| `new` / `malloc` / `strdup` | `delete` / `free` | native 内存泄漏 |
| `GetStringUTFChars` | `ReleaseStringUTFChars` | 字符串资源被钉住，GC 无法回收 |
| `GetStringChars` | `ReleaseStringChars` | 同上 |
| `Get<Type>ArrayElements` | `Release<Type>ArrayElements` | 数组被钉住，可能死锁 |
| `GetPrimitiveArrayCritical` | `ReleasePrimitiveArrayCritical` | **Critical 锁不释放，后续 JNI 全卡死** |
| `GetDirectBufferAddress` | 无（但别长期缓存地址） | Java 对象被回收后地址失效 → UAF |
| `NewGlobalRef` | `DeleteGlobalRef` | **Java 对象永久泄漏** |
| `NewWeakGlobalRef` | `DeleteWeakGlobalRef` | 弱引用表泄漏 |
| `GetObjectArrayElement` | `DeleteLocalRef`（循环里） | 局部引用表溢出 abort |
| `AttachCurrentThread` | `DetachCurrentThread` | 线程退出时 abort |
| `open` / `fopen` / `socket` / `open (binder)` | `close` | fd 泄漏，几百个之后 `EMFILE` |

**工程建议：用 RAII 把所有配对关系变成析构函数**，这样"中途 return"也不会漏：

```cpp
using ScopedFd = std::unique_ptr<int, decltype(&::close)>;   // 或自己写个 20 行的类
class Fd {
public:
    explicit Fd(int fd = -1) : fd_(fd) {}
    ~Fd() { reset(); }
    Fd(Fd&& o) noexcept : fd_(o.release()) {}
    Fd& operator=(Fd&& o) noexcept { if (this != &o) { reset(); fd_ = o.release(); } return *this; }
    Fd(const Fd&) = delete;
    Fd& operator=(const Fd&) = delete;

    int get() const { return fd_; }
    int release() { int t = fd_; fd_ = -1; return t; }
    void reset(int fd = -1) { if (fd_ >= 0) ::close(fd_); fd_ = fd; }
private:
    int fd_;
};
```

### 10.4 让 GC 感知 native 内存

native 分配的大块内存（尤其是代表图片、音频缓冲的那类）如果不告诉虚拟机，就会出现"Java 侧内存看着很健康，进程实际占用却涨到被 LMK 杀"。

| 方案 | 说明 | 可用性 |
|---|---|---|
| **显式 `close()` + `AutoCloseable`** | **官方推荐做法**。责任明确、时机可控 | 所有版本 |
| `java.lang.ref.Cleaner` | 兜底式清理，避免用户忘记 `close()` | Android 13（API 33）起 |
| `NativeAllocationRegistry` | 平台内部用它把 native 字节数登记给 GC，触发 GC 时回收并重算内存压力 | @hide，**平台视角**可用 |
| `sun.misc.Cleaner` | 老代码里的清理器 | 私有 API，不要用 |

```kotlin
// 推荐形态：主路径靠 close()，兜底靠 Cleaner（API 33+）
class NativeBuffer(size: Int) : AutoCloseable {
    private val handle: Long = nativeAlloc(size)
    private val cleanable = if (Build.VERSION.SDK_INT >= 33) {
        Cleaner.create().register(this) {
            // 注意：lambda 里不要捕获 this（否则永远回收不掉），只捕获独立的基本类型值
            nativeFree(if (size >= 0) size else 0L)
        }
    } else null

    override fun close() { /* 幂等释放 + cleanable?.clean() */ }
}
```

> Cleaner 的经典陷阱：**清理动作里捕获了被清理对象本身的引用，导致对象永远不会变成不可达，Cleaner 永远不触发**。上面注释里那点必须小心。

### 10.5 大缓冲与共享内存

| API | 用途 | 关键点 |
|---|---|---|
| `ASharedMemory_create(name, size)` | 匿名共享内存，可跨进程 | 返回 fd，配合 `mmap` 使用；替代已废弃的 ashmem |
| `AHardwareBuffer_*` | 图形/编解码用的 GPU 可访问缓冲 | 常与 `EGLImage` / `Vulkan` 配合；有跨进程能力 |
| `AAssetManager_*` | 读 APK 内的 assets | 不必解压到内存，适合大资源 |
| `memfd_create`（`<sys/mman.h>`） | Linux 原生匿名文件 | 比 `/dev/ashmem` 更通用 |
| `mmap` + `PROT_*` / `MAP_*` | 文件映射、大缓冲 | 注意 `MAP_SHARED` vs `MAP_PRIVATE` |

```cpp
// 创建一块 4MB 的匿名共享内存并映射
int fd = ASharedMemory_create("my-buffer", 4 * 1024 * 1024);
if (fd < 0) return -1;
void* addr = mmap(nullptr, 4 * 1024 * 1024, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
if (addr == MAP_FAILED) { ::close(fd); return -1; }
// ... 用完了 ...
munmap(addr, 4 * 1024 * 1024);
::close(fd);          // fd 也要关，两个资源都要收
```

### 10.6 native 内存问题怎么查

```bash
# ① 看总数：Native Heap 列（单位 KB）
adb shell dumpsys meminfo com.example.ndkdemo | sed -n '/App Summary/,/TOTAL/p'

# ② 看是谁在分配（需要 malloc_debug）
adb shell setprop libc.debug.malloc.program app_process    # 或你的进程名
adb shell setprop libc.debug.malloc.options backtrace=8     # 记录 8 层调用栈
adb shell stop && adb shell start                           # 需要重启框架
adb shell am dumpheap -n com.example.ndkdemo /data/local/tmp/native.hprof

# ③ heapprofd（Perfetto）：抓一段时间的 native 分配火焰图（不需要重启）
#    perfetto UI → Record → Heap profiler → 填 App 名与时长 → 导出 html
#    命令行：
adb shell perfetto -o /data/misc/perfetto-traces/heap.pftrace -t 20s \
  -c - <<< 'buffers{size_kb:8192} data_sources{config{name:"android.heapprofd"
  heapprofd_config{process_cmdline:"com.example.ndkdemo" sampling_interval_bytes:4096}}}'

# ④ 抓单次大分配的调用栈（GWP-ASan，平台内置，采样式，几乎零开销）
#    应用侧 opt-in：AndroidManifest 里 android:gwpAsanMode="always"

# ⑤ 看进程 maps，确认某个 so / 缓冲是否真的加载了、多大
adb shell cat /proc/$(adb shell pidof com.example.ndkdemo)/maps | grep -E "libndkdemo|my-buffer|anon"

# ⑥ 看 native 线程栈（怀疑 native 死循环/死锁时）
adb shell debuggerd -b $(adb shell pidof com.example.ndkdemo)
```

---

## 十一、ABI 与 .so：一个 APK 里的多种机器码

### 11.1 四种 ABI

| ABI | 架构 | 位宽 | Play 要求 | 说明 |
|---|---|---|---|---|
| `arm64-v8a` | ARMv8-A (AArch64) | 64 | **必须有** | 现在的主力机型；NEON 是强制的 |
| `armeabi-v7a` | ARMv7-A | 32 | 已不强制 | 老设备；NDK r17 起 `armeabi` 已被移除，这是最后的 32 位 ARM |
| `x86_64` | x86-64 | 64 | 模拟器/少数平板 | 模拟器首选 |
| `x86` | x86 | 32 | 老模拟器 | 新 NDK 仍支持但少用 |

（`mips` / `mips64` 在 NDK r17 移除；`armeabi` 同批移除。）

**为什么必须同时提供 64 位**：Google Play 自 2019 年起要求新应用/更新包含 64 位版本；并且设备侧的规则是"**如果设备支持 64 位，而 APK 只有 32 位，可能安装/运行失败**"。近年趋势是向纯 64 位收敛（Android 12 起允许"没有 32 位 Zygote"的设备形态），**新代码不要依赖 32 位分支**。具体到你的平台，以该版本的 CDD 与设备配置为准。

### 11.2 32/64 位带来的真实 bug

| 坑 | 表现 | 修法 |
|---|---|---|
| `long` 截断 | 64 位正常、32 位数据错乱 | 一律用 `jlong` / `int64_t`（第四节） |
| 句柄截断 | 32 位下句柄错乱/UAF | `reinterpret_cast<T*>(static_cast<uintptr_t>(h))`（第九节） |
| `size_t` 打印格式 | 日志里地址/大小被截断 | `PRIuPTR` / `%zu`，或强转 `(unsigned long long)` |
| 结构体对齐/大小 | 与外部协议对接时字段错位 | 用固定宽度类型（`uint32_t`）+ `#pragma pack` / `static_assert(sizeof(T)==N)` |
| 混装 so | `dlopen failed: ... is 32-bit instead of 64-bit` | 所有依赖库同一位宽；见 12.5 |

**加一个编译期保险栓**：

```cpp
static_assert(sizeof(jlong) == 8, "jlong must be 8 bytes");
static_assert(sizeof(void*) == 4 || sizeof(void*) == 8, "unexpected pointer width");
```

### 11.3 ABI 裁剪

```kotlin
android {
    defaultConfig {
        ndk {
            // 只在开发期用，最终交给 App Bundle 拆
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }
}
```

- **开发期**：只编自己设备/模拟器需要的 ABI（`arm64-v8a` + `x86_64`），构建时间能少一半以上。
- **发布期**：用 **Android App Bundle（AAB）**，让 Play / 分发渠道按设备下发对应 ABI；如果必须出 APK，用 `splits.abi` 拆包，**别再往一个包里塞 4 个 ABI**（每个 so 10MB 就是 40MB）。
- 压缩后大小：so 在 APK 里默认是压缩的，解压后可能膨胀 2~3 倍，注意 `extractNativeLibs` 的影响（见 11.4）。

### 11.4 so 的两种打包方式

| | `extractNativeLibs=true`（传统） | `extractNativeLibs=false`（新默认） |
|---|---|---|
| 装机时 | 解压到 `/data/app/~~xxx/<pkg>-xxx/lib/<abi>/` | 不解压，直接从 APK 内 `mmap` |
| APK 内 | so 被压缩 | so 必须**不压缩**且**页对齐** |
| 首次启动 | 慢（要解压） | 快 |
| 磁盘占用 | APK + 解压副本 | 只有 APK |
| AGP 控制项 | `useLegacyPackaging = true` | `useLegacyPackaging = false` |

```kotlin
// AGP 8.x
android {
    packaging {
        jniLibs { useLegacyPackaging = false }     // 默认就是 false，只在需要 wrap.sh 时改 true
    }
}
```

> **注意**：`wrap.sh`（第十五节 ASan 用）需要在 `lib/<abi>/` 下有一个可执行脚本，而 `extractNativeLibs=false` 时 APK 内的文件不能直接执行 → **跑 ASan 要临时把 `useLegacyPackaging` 设为 `true`**。这是官方文档里明确写着的组合限制。

### 11.5 SONAME 与 `libc++_shared.so`

```bash
# 查看依赖（DT_NEEDED 就是"我运行时需要谁"）
llvm-readelf -d libndkdemo.so | grep -E "NEEDED|SONAME|RPATH|RUNPATH"
#  0x0000000000000001 (NEEDED)  Shared library: [liblog.so]
#  0x0000000000000001 (NEEDED)  Shared library: [libc++_shared.so]   ← 注意这一行
#  0x000000000000000e (SONAME)  Library soname: [libndkdemo.so]
```

- **`libc++_shared.so` 是 NDK 的 C++ 标准库**。如果 `ANDROID_STL=c++_shared`，它必须**在同一个 ABI 目录下能被找到**：要么 Gradle/`ndk-build` 自动帮你打包（默认会自动），要么你自己 `jniLibs` 里放一份。**"我本地 cmake 编过但装到机器上崩在 STL 里"多半是它没进包。**
- **一个进程里只能有一份 `libc++_shared.so`**。多个 so 各自带一份不同版本 → 崩溃（ODR 冲突、ABI 不兼容）。这是"第三方 SDK 用了不同 NDK 版本导致崩溃"的根因，**解法是统一 NDK 版本，或让一部分用 `c++_static`（但绝不能跨 so 边界传递 STL 对象，比如 `std::string` 当参数）**。
- 只提供 C 接口的库用 `ANDROID_STL=none` 最省事（没有 STL 依赖，体积最小）。
- **`RPATH` / `RUNPATH` 在 Android 上基本不用**（会被 linker namespace 覆盖），别指望靠它改搜索路径。

### 11.6 一个 so 的体检命令

```bash
NDK=~/Library/Android/sdk/ndk/26.1.10909125
BIN=$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin     # Linux 是 linux-x86_64

# 位数与架构：class 是 ELF64 → 64 位；Machine 是 AArch64 → arm64-v8a
$BIN/llvm-readelf -h libndkdemo.so | grep -E "Class|Machine|Type"

# 有没有被 strip（.symtab 在 = 没剥；只剩 .dynsym = 剥了）
$BIN/llvm-readelf -S libndkdemo.so | grep -E "\.symtab|\.dynsym|debug_info"

# 导出/需要哪些符号
$BIN/llvm-nm -D --defined-only libndkdemo.so | head
$BIN/llvm-objdump -T libndkdemo.so | grep UND

# 有没有危险的 TEXTREL（targetSdk 23+ 会拒绝加载）
$BIN/llvm-readelf -d libndkdemo.so | grep -i textrel

# 拉出设备上的 so 来比对（确认设备上跑的就是你编的那个）
adb shell "cat /data/app/*/com.example.ndkdemo-*/lib/arm64/libndkdemo.so" > /tmp/dev.so
md5sum libndkdemo.so /tmp/dev.so
```

---

## 十二、.so 的加载：搜索路径与命名空间隔离

### 12.1 `System.loadLibrary` 到底去哪找

```java
System.loadLibrary("ndkdemo");    // 找 libndkdemo.so
System.load("/sdcard/libndkdemo.so");  // 绝对路径（几乎不用，且 targetSdk 29+ 会被拦）
```

`loadLibrary("ndkdemo")` 在 Android 上的实际行为（简化后的查找顺序）：

1. 类加载器记录的 **native library 目录**（`ClassLoader.findLibrary`）：
   - APK 内（`extractNativeLibs=false`）：`/data/app/~~xxx/<pkg>-xxx/base.apk!/lib/arm64-v8a/`
   - 解压后：`/data/app/~~xxx/<pkg>-xxx/lib/arm64-v8a/`
   - **分区 App**：`/system/lib64`、`/system_ext/lib64`、`/product/lib64`（见 12.6）
2. 该目录下按 `lib<name>.so` 拼名字找文件。
3. 找到后交给 **linker（`/apex/com.android.runtime/bin/linker64`）** `dlopen`。
4. `dlopen` 成功 → 调用 `JNI_OnLoad`（如果有）→ 之后才能调方法。

所以 **`UnsatisfiedLinkError: dlopen failed: library "libndkdemo.so" not found` 的第一反应是"文件到底在哪"**，而不是"符号对不对"：

```bash
# 看看 APK 里到底有没有这个 ABI 的 so（最直接）
unzip -l app-release.apk | grep "\.so"
#   期望看到 lib/arm64-v8a/libndkdemo.so

# 看设备上解压/映射在哪
adb shell "ls -l /data/app/*/com.example.ndkdemo-*/lib/arm64/"
adb shell cat /proc/$(adb shell pidof com.example.ndkdemo)/maps | grep ndkdemo

# 看设备的 ABI 能力（装错 ABI 会表现为"没找到"）
adb shell getprop ro.product.cpu.abilist
#   期望输出包含 arm64-v8a
```

### 12.2 Linker Namespace（Android 7+ 的隔离墙）

Android 7 引入 **linker namespace**（命名空间）：每个 namespace 只被允许访问**白名单内的目录**，且不同 namespace 之间的依赖关系被显式约束。

```bash
# 在设备上直接看 namespace 配置（最权威）
adb shell cat /system/etc/ld.config.txt | head -60
# 里面能看到 [name] 段、search.paths、permitted.paths、以及 allowed 依赖关系
```

常打交道的几个 namespace：

| namespace | 谁在用 | 能看见什么 |
|---|---|---|
| `default` | init 启动的原生进程 | `/system/lib64`、`/vendor/lib64`、`/system_ext/lib64`… |
| `sphal` | SP-HAL | `/vendor/lib64`、部分 `/system/lib64`（VNDK-SP） |
| `vndk` | vendor 进程 | `/system/lib64/vndk-*` |
| `classloader-namespace` | **每个 App 的每个 ClassLoader** | APK 自己的 lib 目录 + `/system/lib64`（**但只有公开库**） |
| `vendor` / `product` | 分区进程 | 各自分区目录 |

**对 App 开发最实际的影响**：App 的 namespace 里，`/system/lib64` 下的库**只有列在 `/system/etc/public.libraries.txt` 里的才允许 `dlopen`**：

```bash
adb shell cat /system/etc/public.libraries.txt | sort
#   典型内容：libandroid.so libc.so libdl.so liblog.so libm.so libz.so
#             libEGL.so libGLESv2.so libvulkan.so libOpenSLES.so ...
# 没在列表里的（比如 libandroid_runtime.so）→ App dlopen 会得到：
#   dlopen failed: library "libandroid_runtime.so" is not accessible
#     for the namespace "classloader-namespace"
```

> **平台视角**：只有 platform-signed 的 App（在 `/system` 或专有域里）才可能访问平台私有库；普通 App 想用 `libandroid_runtime.so` 之类是不可能的，**别在网上抄那种"dlopen 平台库"的技巧**——即使在开发机上碰巧成功，也会随版本失效并被 CTS/兼容性测试拦下。

### 12.3 两类致命错误，必须分清

这是排查 .so 问题的**分水岭**：

| 报错 | 阶段 | 含义 | 常见原因 |
|---|---|---|---|
| `dlopen failed: library "libX.so" not found` | **找文件** | 文件名/路径没找到 | 没打包、ABI 目录不对、名字拼错、被裁掉 |
| `dlopen failed: cannot locate symbol "foo" referenced by "libY.so"` | **符号解析** | 文件找到了，但它需要的某个符号在已加载的库里找不到 | 版本不匹配（system 与 vendor 库版本不同）、符号被 `-fvisibility=hidden` 隐藏、`libY.so` 链了错误版本、真的没链上 |
| `dlopen failed: ... is 32-bit instead of 64-bit` | 找文件 | ABI 位宽不匹配 | 混合 32/64 依赖 |
| `dlopen failed: ... has text relocations` | 找文件 | 存在 TEXTREL | 老工具链/`-fPIC` 缺失；targetSdk 23+ 拒绝 |
| `dlopen failed: ... is not accessible for the namespace ...` | 找文件 | 撞了 namespace 白名单 | 12.2 |
| `dlopen failed: ... invalid ELF header` / `file too short` | 找文件 | 文件损坏 | 下载到 HTML、git 换行、被截断、架构错 |
| `dlopen failed: ... has no DT_SONAME` / 段间隙问题 | 找文件 | ELF 结构不合法 | so 被非 linker 工具改写、patch 过 |
| `No implementation found for <method>` | **方法查找** | so 加载成功了！只是没找到对应函数 | 命名约定写错、没 `RegisterNatives`、函数被 `static`/内联/剥离、`extern "C"` 忘了、静态库因"未被引用"被 linker 丢弃 |
| `Couldn't load libX: findLibrary returned null` | 找文件 | APK 里没有**任何**该 ABI 的 so 目录 | 打包问题（常见于仅提供 x86 却跑在 arm 设备） |

**最后一个**（`No implementation found`）特别值得单列：它说明**加载期已经过了**，问题在绑定期，第一站是 `javap -s` 比对签名（第三节）。

### 12.4 `dlopen` 的符号可见性

即便文件都在，符号也可能"看不见"，原因通常是编译侧：

```cpp
// 1) C++ 编译出的名字被 mangling（JNI 侧忘了 extern "C"）
//    nm 里看到 _Z3fooi 而不是 foo

// 2) 默认可见性被关掉（现代 NDK/平台默认就是 hidden）
//    CMakeLists:  add_compile_options(-fvisibility=hidden)
//    然后必须显式导出：
#define MY_API __attribute__((visibility("default")))
MY_API int my_public_fn(void);

// 3) 被 linker version script 白名单挡掉（Soong 里常见）
//    出现 "cannot locate symbol" 时，先确认目标符号在不在 .dynsym 里
```

```bash
$BIN/llvm-nm -D libndkdemo.so | grep -i " T "      # T = 动态符号表里的已定义文本符号
$BIN/llvm-nm -D libndkdemo.so | grep -i " U "      # U = 未定义（要从别处解析）
```

**关键区分**：`U`（undefined，需要外部提供）在加载时找不到 = `cannot locate symbol`；**根本没出现在 `-D` 输出里** = 被隐藏/内联/剥离，那 JNI 就永远找不到它。

### 12.5 符号版本不匹配（平台视角的重灾区）

`cannot locate symbol` 在**平台/vendor 混合**场景最常见：

```
# system 分区升级了 libfoo.so，删掉/改了某个符号；
# vendor 分区里的 libbar.so 还在按老版本找它 →
#   dlopen failed: cannot locate symbol "_ZN4base3LogEi" referenced by "/vendor/lib64/libbar.so"
```

三层防护：

1. **vendor 侧只用 VNDK / LLNDK 里承诺稳定的库和符号**（见第十七节）；
2. 用 `llvm-readelf --dyn-syms` 对比新旧版本的符号差异；
3. A/B 与 GSI 场景下特别注意"system 与 vendor 版本必须匹配"的约束（`ro.vndk.version` / VINTF 兼容性矩阵会在启动时校验）。

### 12.6 平台 App 的 .so 放在哪

| 落盘位置 | 谁会用 | Soong 写法 |
|---|---|---|
| APK 内部 `lib/<abi>/` | 普通 App、可独立发布的预装 App | `jni_libs: ["libfoo"]` + `use_embedded_native_libs: true` |
| `/system/lib64` | 系统核心 App、被多个模块共享的库 | `cc_library_shared` + `system_specific`（默认） |
| `/system_ext/lib64` | system_ext 分区 App | `system_ext_specific: true` |
| `/product/lib64` | product 分区 App | `product_specific: true` |
| `/vendor/lib64` | vendor 进程 / HAL（见第十七节） | `vendor: true` |

**决定 App 能不能加载某个库的，是它的 namespace 里包不包含那个目录**。product 分区 App 的 namespace 会包含 `/product/lib64`，system 的会包含 `/system/lib64`，但**不会**包含别人的分区目录。这类问题的现场报道长这样：

```
dlopen failed: library "libndkdemo.so" not found   # 明明 /system/lib64 里有
# 其实 App 在 product 分区，它的 namespace 里只有 /product/lib64
```

**排查顺序**：`adb shell pm path <pkg>` 看 App 在哪个分区 → `ls -l /<分区>/lib64/` 看库在哪个分区 → 两者必须一致（或把库换到 App 所在分区）。

---

## 十三、构建：CMake、ndk-build、Android.bp 三套体系

### 13.1 先认清三套体系的分工

| | ndk-build | CMake | Android.bp（Soong） |
|---|---|---|---|
| 配置文件 | `Android.mk` / `Application.mk` | `CMakeLists.txt` | `Android.bp` |
| 主要场景 | 老项目、NDK 官方样例遗留 | **App 内置 native 库（当前主流）** | **AOSP / 平台 / HAL** |
| 谁驱动 | `ndk-build` 脚本 | Gradle `externalNativeBuild` 或手写 | `m` / `soong_ui` |
| 增量与 IDE | 一般 | 好（CLion / AS 直接打开 CMake 工程） | Soong 统一管理，跨模块依赖清晰 |
| 官方态度 | 维护 | **推荐给 App** | **平台唯一选择** |

> 拿不准怎么选：**写 App 用 CMake，改平台用 Android.bp**。`ndk-build` 只在维护老项目时读一读。

### 13.2 CMakeLists：最小可用模板（逐行注释）

```cmake
cmake_minimum_required(VERSION 3.22.1)      # 与 AGP 要求的版本对齐，太低会警告
project(ndkdemo LANGUAGES C CXX)            # 声明语言，避免误启 Fortran 之类的探测

add_library(ndkdemo SHARED                  # SHARED = .so；STATIC = .a（会被链进别的 so）
        src/native_bridge.cpp
        src/codec.cpp)

target_include_directories(ndkdemo PRIVATE  # PRIVATE = 只本 target 可见，避免污染
        ${CMAKE_CURRENT_SOURCE_DIR}/include)

# ── 链系统库：NDK 预定义的 find_library 名 ──────────────────────
# android = libandroid.so（ANativeWindow / AAssetManager / AConfiguration…）
# log     = liblog.so   （__android_log_print）
find_library(log-lib log)
target_link_libraries(ndkdemo PRIVATE
        android
        ${log-lib}
        z                       # libz.so，直接写名字也行
        jnigraphics)            # 需要操作 Bitmap 时

# ── 第三方预编译静态库（IMPORTED target）──────────────────────
add_library(thirdparty STATIC IMPORTED)
set_target_properties(thirdparty PROPERTIES
        IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libthirdparty.a
        INTERFACE_INCLUDE_DIRECTORIES ${CMAKE_CURRENT_SOURCE_DIR}/../include)
target_link_libraries(ndkdemo PRIVATE thirdparty)

# ── 编译选项 ────────────────────────────────────────────────
target_compile_options(ndkdemo PRIVATE
        -Wall -Wextra
        -fvisibility=hidden          # 默认隐藏，只导出显式标记的符号（体积 + 安全）
        -fno-exceptions              # 省体积；用了就必须改用 nothrow new（见 10.2）
        -fno-rtti)
target_compile_definitions(ndkdemo PRIVATE MY_BUILD_ID="2026.09")

set_target_properties(ndkdemo PROPERTIES
        CXX_STANDARD 17
        CXX_STANDARD_REQUIRED ON
        CXX_EXTENSIONS OFF)          # 用标准 C++，不用 GNU 扩展
```

**CMake 侧最常见的四个错**：

| 现象 | 原因 |
|---|---|
| `fatal error: 'xxx.h' file not found` | `target_include_directories` 漏了路径，或路径写成了绝对 Windows 路径 |
| `undefined reference to 'foo'` | 该库没 `target_link_libraries`，或链接顺序（依赖者在前）不对 |
| `cannot find -lxxx` | 库名写错 / 预编译库路径不对 / 该 ABI 目录下没有这个 .a |
| 改了 CMakeLists 没生效 | Gradle 缓存；`./gradlew clean` 或删 `build/.cxx` |

### 13.3 Gradle 集成（Kotlin DSL）

```kotlin
android {
    ndkVersion = "26.1.10909125"          // 必须显式指定！否则用 AGP 自带版本，多人协作易不一致

    defaultConfig {
        minSdk = 29
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fvisibility=hidden")
                arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_PLATFORM=android-29")
            }
        }
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }   // 开发期只编这两个
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            externalNativeBuild {
                cmake {
                    // 保留调试信息：否则崩溃地址无法符号化（第十四节）
                    arguments += "-DCMAKE_BUILD_TYPE=RelWithDebInfo"
                }
            }
            ndk { debugSymbolLevel = "FULL" }   // 让 AAB 带走符号表，Play 可自动符号化
        }
    }

    packaging {
        jniLibs { useLegacyPackaging = false }
    }
}
```

**要点**：

- **`ndkVersion` 一定要写死**。"我本地能编，CI 报错"十有八九是两边 NDK 版本不同。
- **`debugSymbolLevel = "FULL"`**：打 AAB 时把未剥离的符号一起交上去，线上 native 崩溃才能自动符号化。不开的话你只有一堆十六进制地址。
- **release 不要强制 `strip`**，或者至少在 CI 里把 `obj/` 下的未剥离副本归档存好（`build/intermediates/cxx/.../obj/arm64-v8a/`）——**这是唯一能符号化现场崩溃的东西**。

### 13.4 预编译第三方库的三种形态

| 你拿到的东西 | 怎么用 | 注意 |
|---|---|---|
| `libfoo.so`（动态） | 放进 `src/main/jniLibs/<abi>/libfoo.so`，CMake 里 `find_library` + `target_link_libraries` | 它自己的依赖（`DT_NEEDED`）也要提供，否则加载期报 `not found` |
| `libfoo.a`（静态） | `add_library(foo STATIC IMPORTED)` + `IMPORTED_LOCATION` | **必须被显式引用**，否则 linker 不把它链进来（症状：`No implementation found`） |
| 源码 | 直接 `add_subdirectory` 或 `add_library` 编进来 | 注意它自己的 CMake 是否支持 Android 交叉编译 |

**静态库被"优化掉"的经典问题**：linker 只从静态库里取"被需要的"目标文件。如果 `libfoo.a` 里的 JNI 实现没有任何 C++ 代码引用它，链接后 so 里就没有那些符号 → 加载成功但 `No implementation found`。解法：

```cmake
# 让 linker 整体包含静态库的所有目标文件
target_link_options(ndkdemo PRIVATE "-Wl,--whole-archive" thirdparty "-Wl,--no-whole-archive")
# 或者用 CMake 的写法（更清晰）
set_target_properties(thirdparty PROPERTIES INTERFACE_LINK_OPTIONS "-Wl,--whole-archive")
```

### 13.5 STL 选型

| `ANDROID_STL` | 说明 | 什么时候用 |
|---|---|---|
| `c++_shared` | 共用一个 `libc++_shared.so` | **多个 so 之间要传 STL 对象**（如 `std::string`、`std::vector`）；App 默认 |
| `c++_static` | 每个 so 静态链自己那份 | 单个 so、想要体积/独立性；**绝不能跨 so 边界传 STL 对象** |
| `none` | 不用 STL | 纯 C 库，体积最小 |
| `system` | 用系统 STL（已废弃） | 别用 |

> **一句话规则**：**跨 so 边界只传 C 类型（指针、长度、基础类型）**，任何情况下都最安全。这也是 NDK 官方对"如何设计 native 接口"的核心建议。

### 13.6 Android.bp：平台与 HAL（平台视角）

```python
// ── native 库 ────────────────────────────────────────────────
cc_library_shared {
    name: "libndkdemo",
    srcs: ["src/*.cpp"],
    cflags: ["-Wall", "-Werror", "-Wno-unused-parameter"],
    cppflags: ["-std=c++17"],

    // ⚠️ 关键区别：
    //  shared_libs —— 会写进 DT_NEEDED，运行时必须能找到它，Soong 会做符号可见性检查
    //  libs        —— 只参与链接、不写 DT_NEEDED（用于 dlopen 时手动加载的库）
    shared_libs: ["liblog", "libutils", "libbase"],
    static_libs: ["libmyalgo"],

    stl: "c++_shared",
    sdk_version: "current",        // 走 NDK 稳定 API（App 侧）。平台内部库应去掉这几行
    // vendor: true,               // HAL：只能链 vendor 或 VNDK/LLNDK 库
    // product_specific: true,
    // system_ext_specific: true,
    visibility: ["//vendor/xxx/hal:__subpackages__"],
}
```

```python
// ── 带 native 库的平台 App（platform-signed + priv-app）─────────
android_app {
    name: "NdkDemo",
    srcs: ["src/**/*.java", "src/**/*.kt"],
    resource_dirs: ["res"],
    manifest: "AndroidManifest.xml",

    platform_apis: true,           // 可用 @hide API（系统应用专属）
    certificate: "platform",       // 平台签名 → 可申请 signature|privileged 权限
    privileged: true,              // 装进 priv-app
    product_specific: true,        // 落到 /product/priv-app/

    jni_libs: ["libndkdemo"],      // 关联 native 库
    use_embedded_native_libs: false,  // false → so 落到分区 lib64，不打进 APK
                                      // true  → so 打进 APK 的 lib/<abi>/（自包含，便于单刷）
    // jni_uses_platform_apis: true,  // native 侧也用了平台私有 API 时
    // native_coverage: true,
}
```

**两个容易踩的点**：

- `use_embedded_native_libs: false`（走分区 lib64）**要求库与 App 在同一分区**，否则就是 12.6 那个 namespace 问题。分区 App 想自包含就设 `true`。
- `sdk_version: "current"` 会把库限制在 NDK 稳定 API 集。**平台内部库千万别加它**，否则 `libutils`、`libcutils` 这类平台私有库全链不上。

### 13.7 编译参数的取舍：符号要不要留

```makefile
# 体积 vs 可调试性的权衡：
#  -g           保留调试信息（体积大增，但 addr2line 能给出文件:行号）
#  -gline-tables-only  只留行号表（体积适中，addr2line 有行号、无变量）← 推荐给 release
#  什么都不加 + strip  → 只有符号名，无行号
#  strip 到底            → 什么都查不出来（灾难）
```

**推荐配置**：

| 构建 | 参数 | 产物 |
|---|---|---|
| debug | `-g -O0` | 全符号，可单步 |
| release | `-O2 -gline-tables-only`，**不 strip**，或 strip 后保留 `obj/` 副本 | 崩溃可符号化到行 |
| 对外发布 APK | 可 strip 用户可见的 so，**但必须归档未剥离副本** | 现场可回溯 |

---

## 十四、崩溃排查：tombstone 与符号化

### 14.1 一次 native 崩溃的完整现场

**第一步：logcat 里的引子**

```
F/libc    ( 4321): Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0 in tid 4345 (ndk-worker), pid 4321 (com.example.ndkdemo)
F/DEBUG   ( 4322): *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***
F/DEBUG   ( 4322): Build fingerprint: 'Android/xxx/xxx:14/UP1A.xxx/12345:user/release-keys'
F/DEBUG   ( 4322): pid: 4321, tid: 4345, name: ndk-worker  >>> com.example.ndkdemo <<<
F/DEBUG   ( 4322): uid: 10123
F/DEBUG   ( 4322): signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0000000000000000
F/DEBUG   ( 4322):     x0  0000000000000000  x1  0000007a1b2c3d40  x2  0000000000000010 ...
F/DEBUG   ( 4322): backtrace:
F/DEBUG   ( 4322):       #00 pc 0000000000001a2c  /data/app/~~x/com.example.ndkdemo-y/lib/arm64/libndkdemo.so (crash_here+24)
F/DEBUG   ( 4322):       #01 pc 0000000000001b40  /data/app/~~x/com.example.ndkdemo-y/lib/arm64/libndkdemo.so (Java_com_example_ndkdemo_Bridge_bad+60)
F/DEBUG   ( 4322):       #02 pc 000000000034a1e4  /apex/com.android.art/lib64/libart.so (art_quick_generic_jni_trampoline+148)
F/DEBUG   ( 4322):       #03 pc 00000000000f3c48  /apex/com.android.art/lib64/libart.so (art_quick_invoke_stub+328)
F/DEBUG   ( 4322):       #04 pc 0000000000381f9c  /apex/com.android.art/lib64/libart.so (art::interpreter::Execute+...)
F/DEBUG   ( 4322):       #05 pc 0000000000000000  <unknown>
```

**第二步：去设备上拿完整 tombstone**

```bash
adb shell ls -l /data/tombstones/          # Android 11+ 在 /data/tombstones/tombstone_XX
adb pull /data/tombstones/tombstone_00
# 有 root 时也可以直接看
adb shell cat /data/tombstones/tombstone_00
# 或者现场重抓一次 backtrace（进程还在的话）
adb shell debuggerd -b $(adb shell pidof com.example.ndkdemo)
# Android 13+：直接看 dropbox / bugreport
adb bugreport bug.zip && unzip -l bug.zip | grep -i tombstone
```

### 14.2 tombstone 逐字段解读

```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0000000000000000
       │         │              │                   │
       │         │              │                   └─ 出错地址：0x0 = 空指针解引用（最常见）
       │         │              └─ SEGV_MAPERR = 地址没映射；SEGV_ACCERR = 映射了但无权限（写只读段）
       │         └─ 具体成因码（不同信号含义不同）
       └─ 信号编号：详解见下表

Abort message: 'JNI DETECTED ERROR IN APPLICATION: ...'
       └─ ★ 有这一行先读这一行！JNI 误用的根因都在这儿（第八节 8.4 对照表）

    x0 … x30, sp, lr, pc
       └─ 崩溃瞬间的寄存器快照。x0 常是"第一个参数"，sp 是栈指针，
          lr 是返回地址、pc 是当前指令地址。读内存越界时通常 x0/x1 = 出错地址

backtrace:
      #00 pc 0000000000001a2c  /data/app/.../libndkdemo.so (crash_here+24)
         │    │                 │                        │        └─ 偏移 24 字节处
         │    │                 │                        └─ 符号名（需要 so 带符号表才有）
         │    │                 └─ 哪个库（这才是定位模块的关键）
         │    └─ 库内相对地址（符号化的输入）
         └─ 栈帧序号：00 是最深处，往上就是调用者，一直到 Java 层
```

| 信号 | 常见原因 |
|---|---|
| `SIGSEGV` (11) | 空指针/野指针解引用、数组越界、栈溢出、use-after-free |
| `SIGABRT` (6) | `abort()`：断言失败、C++ 未捕获异常、**JNI 误用（ART 主动 abort）**、`LOG_ALWAYS_FATAL` |
| `SIGBUS` (7) | 未对齐访问、`mmap` 文件被截断后访问 |
| `SIGILL` (4) | 执行了非法指令（NEON 用了但设备不支持、指令集不匹配） |
| `SIGFPE` (8) | 整数除零、整数溢出陷阱 |
| `SIGTRAP` (5) | 断点、`__builtin_trap`、部分 sanitizer 命中 |

**看栈的三条经验**：

1. **先找 `#0x` 里属于你自己 so 的第一帧** —— 那就是出事的位置；再往上的 `libart.so` 帧说明"是从 Java 调进来的"（`art_quick_generic_jni_trampoline` 就是 JNI 入口）。
2. **`abort message` 优先于 backtrace**。JNI 误用的根因都在那一行字里（第八节 8.4）。
3. **栈里出现 `libc.so (abort+…)` 而上面是 `libc++_shared.so (std::terminate)`** → 是 C++ 异常没接住，往回找哪个函数可能抛（`std::bad_alloc`、`std::out_of_range`）。

### 14.3 符号化三板斧

**前提：你需要一份未剥离（unstripped）的 so**。三个来源：

| 来源 | 路径 |
|---|---|
| Android Studio / Gradle | `build/intermediates/cxx/Debug/<hash>/obj/arm64-v8a/libndkdemo.so`（`obj/` 下通常是未剥离的） |
| AOSP 构建 | `out/target/product/<device>/symbols/<分区>/lib64/libndkdemo.so` —— **`symbols/` 目录专门存未剥离副本** |
| 自己保管 | CI 里把 `obj/` 归档；或开 `debugSymbolLevel=FULL` 让 AAB 带着 |

**① `ndk-stack`：最省事，直接吃 logcat/tombstone**

```bash
$NDK/ndk-stack -sym ./obj/arm64-v8a -dump crash.log
# 输出会把 #00 pc 0000000000001a2c 替换成
#   #00 pc 0000000000001a2c  libndkdemo.so (crash_here+24)
#   #01 pc ...              libndkdemo.so (Java_..._bad+60)
```

**② `llvm-symbolizer`：拿到文件:行号（最有用）**

```bash
BIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
$BIN/llvm-symbolizer --obj=./obj/arm64-v8a/libndkdemo.so \
    --functions=linkage --inlines 0x1a2c 0x1b40
#   → crash_here() at /path/codec.cpp:137:9
# 一次可以带多个地址（把 backtrace 里所有属于该 so 的 pc 一起丢进去）
```

**③ `addr2line`：老牌工具，脚本里好用**

```bash
$BIN/llvm-addr2line -f -C -e libndkdemo.so 0x1a2c
#   crash_here
#   /home/me/proj/codec.cpp:137
```

**符号化不出来的三种原因**（按出现频率）：

| 现象 | 原因 | 解法 |
|---|---|---|
| 只有地址，没有符号名 | 用了被 strip 的 so（APK 里那份） | 换成 `obj/` 或 `symbols/` 下的 |
| 有符号名，没有文件行号 | 编译时没加 `-g` / `-gline-tables-only` | 加上重编；已有构建无法回填 |
| 符号名完全不对（`_ZN…` 乱码） | 参数忘了 `-C`（demangle） | `addr2line -C` / `llvm-symbolizer` 默认 demangle |
| 地址对不上（符号名明显不是出事的函数） | **地址来自另一个版本的 so**（构建号不一致） | 用 `md5sum` 比对设备上的 so 与本地副本（11.6） |

> **最容易犯的错**：拿 release APK 里的 so 去符号化。它一定被 strip 过。**"符号化不出来"第一件事是确认用的是未剥离副本 + 与线上版本一致。**

### 14.4 让 native 自己打 backtrace（现场没有 tombstone 时）

```cpp
#include <execinfo.h>        // backtrace / backtrace_symbols
#include <unistd.h>

// 注意：不要放在信号处理器里随便调（不是异步信号安全的）
void log_backtrace(const char* tag) {
    void* frames[64];
    const int n = ::backtrace(frames, 64);
    char** syms = ::backtrace_symbols(frames, n);
    if (syms == nullptr) return;
    for (int i = 0; i < n; ++i) {
        __android_log_print(ANDROID_LOG_ERROR, "ndkdemo", "%s #%02d %s", tag, i, syms[i]);
    }
    ::free(syms);
}

// 更狠的玩法：在崩溃信号处理器里用 libbacktrace（Android 平台的库，能直接出符号）
// 平台视角：libbacktrace 的 API 会随版本变化，用前先确认自己平台的头文件
```

**同时抓 Java 栈**（native 崩了但想看完整体 Java 调用链）：

```bash
# 崩溃后 Java 侧也会打出异常；或者主动 dump 一次 Java 栈
adb shell kill -3 $(adb shell pidof com.example.ndkdemo)     # 会写 traces 到 /data/anr/
adb shell ls -l /data/anr/
```

### 14.5 活体抓栈与动态调试

```bash
# ① 不求崩溃，直接看某个 native 线程当前在干什么（卡死/死循环首选）
adb shell debuggerd -b $(adb shell pidof com.example.ndkdemo)

# ② Perfetto 采样：native 热点 + 调用栈 + 线程时序
#    命令行抓 10 秒 sched + callstack 采样
adb shell perfetto -o /data/misc/perfetto-traces/trace.pftrace -t 10s \
  -c - <<< 'buffers{size_kb:16384} data_sources{config{name:"linux.perf"
  perf_event_config{sample_freq:1000}} data_source{config{name:"linux.ftrace"
  ftrace_config{ftrace_events:["sched/sched_switch"]}}}'
#    然后用 ui.perfetto.dev 打开

# ③ lldb 动态调试（NDK 自带 lldb-server）
#    AS 里直接 Run → Profile/Debug 就能设断点是最省事的路径
$NDK/prebuilt/linux-x86_64/bin/lldb
#    (lldb) platform select remote-android
#    (lldb) platform connect connect://<设备IP>:<端口>  或经 adb forward

# ④ 看 so 到底从哪个地址映射进来（结合 maps 算偏移）
adb shell cat /proc/$(adb shell pidof com.example.ndkdemo)/maps | grep libndkdemo
#   7a1b2c0000-7a1b2d8000 r-xp ... /data/app/.../libndkdemo.so
#   崩溃地址 - 段基址 = 库内偏移（就是你要符号化的那个值）
```

### 14.6 版本与构建号的铁律

**没有版本对应关系，符号化就是算命。** 落地做法：

1. 编译时注入唯一 build id（`-Wl,--build-id=sha1` 或自定义宏 `-DMY_BUILD_ID=$(git rev-parse --short HEAD)`），并在启动时打一条日志。
2. CI 里按 build id 归档 `<buildid>/arm64-v8a/libndkdemo.so`（未剥离）。
3. 收到崩溃日志 → 从日志里取 build id → 取对应符号副本 → 符号化。

```bash
# 从 so 里读出 linker 记录的 build id（正是 logcat 崩溃日志里那串）
llvm-readelf -n libndkdemo.so | grep -A1 "Build ID"
```

---

## 十五、Sanitizer 与 native 性能剖析

### 15.1 为什么必须要 sanitizer

native 崩溃最难受的一点是**"崩的地方不是错的地方"**：内存越界写坏了一块内存，可能在几秒后、在完全无关的代码里崩掉。tombstone 只告诉你"崩在哪"，**不告诉你"错在哪"**。

Sanitizer 的思路是：**在编译期插桩，把"错误发生的那一刻"抓出来**。

| 工具 | 抓什么 | 开销 | 适用 |
|---|---|---|---|
| **ASan**（AddressSanitizer） | 越界读写、use-after-free、double free、栈/全局溢出、内存泄漏 | 2~3 倍 CPU，2~3 倍内存 | **native 排查首选** |
| **HWASan** | 同上（用硬件 tag 实现） | 开销小很多（~2 倍内存，CPU 接近 1） | 需要 64 位 + 支持的系统镜像 |
| **GWP-ASan** | 采样式抓 UAF/越界（平台内置） | 几乎为零 | 线上灰度也能开 |
| **UBSan** | 未定义行为（有符号溢出、错误移位、空指针解引用前的检查、对齐） | 小 | 与 ASan 组合 |
| **TSan** | 数据竞争 | 5~15 倍 | 多线程逻辑排查（很慢，专用） |
| **MTE** | 硬件内存标记（ARMv9） | 低 | 新机型，Android 14+ 可对堆开启 |

### 15.2 ASan 完整启用步骤（App 视角）

```kotlin
// ① build.gradle.kts：加 ASan 编译链接参数（debug 变体单独加）
android {
    defaultConfig {
        externalNativeBuild {
            cmake {
                // ⚠️ ASan 要求所有 native 代码与 libc++ 一致插桩；
                //    最简单是只给 debug 开
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DCMAKE_C_FLAGS=-fsanitize=address -fno-omit-frame-pointer",
                        "-DCMAKE_CXX_FLAGS=-fsanitize=address -fno-omit-frame-pointer",
                        "-DCMAKE_SHARED_LINKER_FLAGS=-fsanitize=address",
                        "-DANDROID_STL=c++_shared"     // ASan 必须用 shared STL
                    )
                }
            }
        }
    }
    packaging {
        // ⚠️ wrap.sh 需要能执行，所以必须 legacy packaging（见 11.4）
        jniLibs { useLegacyPackaging = true }
    }
}
```

```sh
# ② 把 wrap.sh 放到 app/src/main/resources/lib/arm64-v8a/wrap.sh
#!/system/bin/sh
HERE="$(cd "$(dirname "$0")" && pwd)"
export ASAN_OPTIONS=log_to_syslog=false,allow_user_segv_handler=1,detect_leaks=1
export LD_PRELOAD="$HERE/libclang_rt.asan-aarch64-android.so"
exec "$@"
```

```bash
# ③ wrap.sh 必须有可执行权限（Windows 下开发要注意，Git 会把 x 位丢掉）
chmod 755 app/src/main/resources/lib/arm64-v8a/wrap.sh

# ④ ASan 运行库由 NDK 提供，从工具链目录拷过去
cp $NDK/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/*/lib/linux/libclang_rt.asan-aarch64-android.so \
   app/src/main/resources/lib/arm64-v8a/

# ⑤ 要求 App 可调试（android:debuggable="true"，debug 变体天然满足）
```

**ASan 的能力演示**（错误在"越界的瞬间"被抓出来，而不是几秒后）：

```
==12345==ERROR: AddressSanitizer: heap-buffer-overflow on address 0x...
WRITE of size 4 at 0x... thread T0
    #0 0x... in write_pixel /proj/codec.cpp:88:9
    #1 0x... in Java_com_example_ndkdemo_Bridge_encode /proj/bridge.cpp:142:5

0x... is located 0 bytes after 4096-byte region [0x...,0x...) allocated by thread T0 here:
    #0 0x... in operator new[](unsigned long)
    #1 0x... in alloc_buffer /proj/codec.cpp:60:22

SUMMARY: AddressSanitizer: heap-buffer-overflow /proj/codec.cpp:88:9 in write_pixel
```

注意最后两段：**"错在哪"（88 行）和"内存在哪分配的"（60 行）都给出来了**，这比 tombstone 强太多。

**ASan 的四个常见卡点**：

| 现象 | 原因 |
|---|---|
| App 起不来 / 立刻 SIGSEGV | `wrap.sh` 没有可执行权限，或路径不对（必须是 APK 的 `lib/<abi>/wrap.sh`） |
| `library "libclang_rt.asan-…" not found` | 忘了把运行库拷到 `lib/<abi>/` |
| 装不上 / so 找不到 | `useLegacyPackaging` 没设 `true` |
| 报一堆 `libc++_shared.so` 相关错误 | STL 没同时插桩；确保 ASan 变体也用 `c++_shared` 且是插桩过的 NDK 版本 |

> 平台视角：AOSP 里用 `SANITIZE_TARGET=address` 整镜像构建（`m -j SANITIZE_TARGET=address`），能让整个系统（含 framework 与 system 库）带 ASan，代价是性能与内存。对"系统库内存越界"这类问题是最强武器。

### 15.3 HWASan / MTE / GWP-ASan 的取舍

| | ASan | HWASan | GWP-ASan | MTE |
|---|---|---|---|---|
| 检测率 | 接近 100% | 高 | 采样（发现率低但覆盖面广） | 高（对堆） |
| 性能开销 | 高 | 中 | ~0 | 低 |
| 前提 | debuggable + wrap.sh | 64 位 + 支持镜像（Pixel 有 userdebug 镜像） | 平台已内置 | ARMv9 + 系统支持 |
| 适用阶段 | 开发/测试 | 测试/内测 | **线上灰度** | 线上 |

**实践顺序**：开发用 ASan → 内测用 HWASan（跑得快）→ 线上开 GWP-ASan（`android:gwpAsanMode="always"`，或在开发者选项里对特定 App 开）→ 新机型靠 MTE。

### 15.4 UBSan：抓"没崩但结果不对"

```kotlin
// 加到 CMake arguments 里（可与 ASan 同时用）
"-DCMAKE_CXX_FLAGS=-fsanitize=undefined -fno-sanitize-recover=all"
// -fno-sanitize-recover=all → 第一次未定义行为就 abort，方便在 CI 里卡住
```

典型能抓到的：有符号整数溢出、移位超位宽、除零、空指针传给需要非空的地方、对齐错误。**这些 bug 在 debug 上"看起来没事"，到 release 开 `-O2` 就变成诡异崩溃**——车机上跑长稳测试时特别值得开一轮 UBSan。

### 15.5 simpleperf：native 热点定位

```bash
# ① 抓到采样数据（app_profiler.py 会自动下载 simpleperf 到设备）
python $NDK/simpleperf/app_profiler.py \
    -p com.example.ndkdemo -r "-g -f 1000 --duration 10" \
    -lib $NDK/simpleperf/lib/linux/x86_64 \
    --ndk_path $NDK -o perf.data --disable_adb_root

# ② 生成报告：哪个函数占了多少 CPU（带调用栈树）
python $NDK/simpleperf/report.py -i perf.data --sort dso,symbol -g
python $NDK/simpleperf/report_sample.py --print_tasks -i perf.data

# ③ 直接对现有进程采样（不需要 root 时用 --app 包名）
adb shell simpleperf record -p $(adb shell pidof com.example.ndkdemo) -g -f 1000 --duration 5 -o /data/local/tmp/p.data
```

**读法**：先看 `dso` 维度（时间花在哪个 .so），再看 `symbol`（哪个函数），最后用 `-g` 看调用链找"为什么它被调了这么多次"。**这一步能区分"算法慢"和"被调太多次"——后者通常是跨界设计问题（第九节）。**

### 15.6 App 场景下该盯的 native 指标

| 指标 | 工具 | 目标 |
|---|---|---|
| 启动耗时里的 native 部分 | `am start -W` + Perfetto | 别在 `JNI_OnLoad` 或构造函数里做重活 |
| 帧率与主线程 native 阻塞 | Perfetto / `atrace` | native 调用不要在主线程做 > 几 ms 的事 |
| native 内存增长 | heapprofd / `dumpsys meminfo` | 长跑不涨（泄漏） |
| native 崩溃率 | Play Vitals / 自建符号化服务 | 有符号文件才能算崩溃率 |
| ANR 里有没有 native 栈 | `/data/anr/traces.txt` + tombstone 对照 | native 卡住导致的 ANR |

> **一条经验**：`JNI_OnLoad`、静态初始化、`System.loadLibrary` 都发生在**进程启动的关键路径**上。见过太多"启动慢 800ms"最后定位到"`JNI_OnLoad` 里初始化了 OpenCV 全部模块"。**JNI_OnLoad 只做注册，重活延后到首次调用。**

---

## 十六、安全：native 是一把双刃剑

### 16.1 先破除幻觉：native 不改变安全边界

| 你以为 | 实际 |
|---|---|
| native 可以做 Java 不能做的事 | ❌ 仍在 App 的 UID、SELinux 域、cgroup、namespace 里 |
| native 可以绕过权限 | ❌ 文件、网络、相机等权限检查在内核/系统服务侧，跟语言无关 |
| native 可以随便 `exec` | ❌ Android 10 起对"从可写目录执行代码"有限制（见 16.2） |
| native 更难被反向 | ⚠️ 只是"更难一点"：反编译门槛提高，但 hook/动态调试一样能拿下 |
| native 代码更"快" | ⚠️ 只在特定负载上快；跨界开销可能把收益吃光 |

**唯一的例外**：如果 App 本身是 platform-signed + priv-app，那就天然拥有签名级/特权权限——**但这是"签名"带来的，不是 native 带来的**。

### 16.2 Android 10+ 的代码执行限制（做热修复/插件化的天花板）

从 Android 10（API 29）开始，targetSdk ≥ 29 的应用**不能从可写目录加载/执行代码**（典型就是 App 私有数据目录 `/data/data/<pkg>/`）。表现为：

```
# 典型症状（不同实现路径文案不同）
java.io.IOException: Permission denied        # 试图 exec 一个 data 目录下的二进制
dlopen failed: ... is not accessible ...       # 试图 dlopen 可写目录下的 so
avc: denied { execute } for ...                # SELinux 层拒绝（execute_no_trans）
```

**对方案设计的含义**：

| 方案 | 可行性 |
|---|---|
| 只在 so 内部做逻辑，so 从 APK/分区加载 | ✅ 正常做法 |
| 把算法放进数据目录的 so，运行时 `dlopen` 更新 | ❌ 撞限制（除非系统签名 App + 定制 sepolicy 放开） |
| 热更新 native 代码 | ❌ 与平台安全模型冲突；**平台 App 走 OTA/分区更新才是正路** |
| 用 `Runtime.exec` 跑 data 目录下的二进制 | ❌ 同样被拦 |
| 用 `ASharedMemory` / 解释型字节码方案 | ✅ 常见替代思路 |

> 具体拒绝点在"目标 API + SELinux 域 + 路径"的组合判定上，不同 OEM/版本可能有细微差别。做平台定制时，**正确的做法是走自己的 OTA 与分区更新流程，而不是在 sepolicy 上开洞**。

### 16.3 加固编译选项清单

```cmake
target_compile_options(ndkdemo PRIVATE
        -fstack-protector-strong          # 栈保护（canary）
        -D_FORTIFY_SOURCE=2               # 对 memcpy/strcpy 之类做编译期与运行期检查
        -fvisibility=hidden               # 默认隐藏符号
        -fno-strict-aliasing              # 避免某些 UB 优化引发的诡异问题
        )

target_link_options(ndkdemo PRIVATE
        -Wl,-z,relro                       # GOT 只读
        -Wl,-z,now                         # 立即绑定（配合 RELRO 防 GOT 覆写）
        -Wl,-z,noexecstack                 # 栈不可执行
        -Wl,--build-id=sha1                # 崩溃符号化与版本追踪
        # -fsanitize=cfi                   # 控制流完整性（NDK 支持有限，平台侧用得多）
        )
```

> 这些选项现代 NDK 已默认打开一部分（`-fstack-protector-strong`、部分 RELRO、`-z noexecstack` 等），**但不要"依赖默认值"**：CI 换 NDK 版本、平台换 toolchain 都可能变。**显式写出来 + 用 `llvm-readelf -d` 验证结果**是最稳的。

```bash
# 验证加固是否生效
llvm-readelf -d libndkdemo.so | grep -E "BIND_NOW|FLAGS"     # 看到 BIND_NOW = RELRO 完整
llvm-readelf -l libndkdemo.so | grep GNU_STACK               # 应该是 RW（不含 E）
llvm-readelf -d libndkdemo.so | grep -i textrel              # 应该啥也没有
$BIN/llvm-nm -D libndkdemo.so | wc -l                        # 导出符号数越少越好
```

### 16.4 符号隐藏与防逆向的取舍

| 手段 | 效果 | 代价 |
|---|---|---|
| `-fvisibility=hidden` + 显式导出 | ✅ 免费，减少攻击面与体积 | 无 |
| `RegisterNatives` 代替命名约定 | ✅ 符号表里看不出 Java 方法名 | 调试时栈不好读 |
| 编译期 strip 符号 | 一般 | **失去现场符号化能力**（见 14.3） |
| 字符串加密 / 常量混淆 | 有一定效果 | 影响可读性与性能 |
| 控制流平坦化 / 虚拟化（商用加固） | 效果明显 | 体积/性能/兼容性代价大，可能与 ART/车机限制冲突 |
| Root/调试检测、完整性校验 | 阻断"随手改" | **绕过成本并不高**，别当主要防线 |

**务实的结论**：把 native 用在该用的地方（性能、复用、系统能力），**安全上做到"符号隐藏 + 常规加固 + 不给敏感逻辑写死密钥"**，就够了。真正的敏感逻辑（如支付凭据）不该依赖"代码藏得好"，而是依赖**服务端校验 + 硬件密钥（TEE/StrongBox）**。

### 16.5 so 完整性校验的正确姿势

```cpp
// 想校验"我的 so 没被替换"，在 native 侧算自己的 CRC 是不靠谱的
// （攻击者完全可以同时改掉校验逻辑和校验值；且 so 可能被加载器 mmap 后就没法读了）
// 相对靠谱的做法：
//   1) 在 Java/Kotlin 侧用 PackageManager 校验 APK 签名 + 校验 lib 目录文件 hash
//      注意：签名校验本身也是"同进程内"的，理论上可被 hook
//   2) 关键结论放到服务端做；客户端自检只作为"提高成本"的辅助手段
//   3) 平台 App 可用 selinux + 分区只读性 + dm-verity 保证"系统库不被篡改"
```

**平台 App 的真正优势**：库放在 `/system`/`/product` 分区，受 **dm-verity / AVB** 保护，**这是有密码学保证的完整性**，比任何应用层自检都可靠。

---

## 十七、平台视角：系统 App、HAL 与 VNDK

> 这一节写给平台/vendor 开发。普通 App 可以跳过。

### 17.1 平台 App 的 native 库落盘位置（复习 + 扩展）

```
/system/priv-app/NdkDemo/NdkDemo.apk
   ├─ 若 use_embedded_native_libs: true  → so 在 APK 内 lib/arm64/
   └─ 若 false（默认）                    → so 需要落到与 App 同分区的 lib64/
        /system/lib64/libndkdemo.so          （system 分区 App）
        /system_ext/lib64/libndkdemo.so      （system_ext App）
        /product/lib64/libndkdemo.so         （product App）

Android.bp（三种落盘位置，选一个与 App 相同的）：
   cc_library_shared { name: "libndkdemo", system_specific: true }        // /system
   cc_library_shared { name: "libndkdemo", system_ext_specific: true }    // /system_ext
   cc_library_shared { name: "libndkdemo", product_specific: true }       // /product
```

**自检口诀**：**App 在哪，库就在哪。** 不一致 → `dlopen failed: ... not found`（12.6）。

### 17.2 vendor 侧 native HAL（车机最常改）

```python
// ① 接口定义：AIDL（ndk: true 生成 C++ 版）
aidl_interface {
    name: "android.hardware.xxx.demo",
    srcs: ["aidl/**/*.aidl"],
    stability: "vintf",
    backend: {
        ndk: { enabled: true, apex_available: ["//apex_available:platform"] },
        cpp: { enabled: true },
        java: { enabled: true },
    },
    versions: ["1"],
}

// ② 实现：vendor 侧二进制
cc_binary {
    name: "vendor.xxx.demo-service",
    relative_install_path: "hw",
    vendor: true,                       // ★ 装到 /vendor/bin/hw/
    init_rc: ["vendor.xxx.demo-service.rc"],
    vintf_fragments: ["vendor.xxx.demo-service.xml"],   // ★ 声明到 VINTF
    srcs: ["service.cpp", "demo_impl.cpp"],
    shared_libs: [
        "android.hardware.xxx.demo-V1-ndk",   // AIDL-NDK 生成的接口库
        "libbinder_ndk",                      // ★ NDK 版 binder（不是 libbinder）
        "libbase", "liblog",
    ],
    cflags: ["-Wall", "-Werror"],
}
```

```cpp
// ③ 用 libbinder_ndk 注册服务（约 20 行，是 vendor 服务的标准骨架）
#include <android/binder_manager.h>
#include <android/binder_process.h>

int main() {
    ABinderProcess_setThreadPoolMaxThreadCount(2);
    std::shared_ptr<BnDemo> demo = ndk::SharedRefBase::make<BnDemo>();

    const std::string name = std::string(BnDemo::descriptor) + "/default";
    // Android 13+ 更推荐 AServiceManager_addServiceWithCallback 做延迟注册
    binder_status_t st = AServiceManager_addService(demo->asBinder().get(), name.c_str());
    if (st != STATUS_OK) return -1;

    ABinderProcess_joinThreadPool();
    return 0;
}
```

```cpp
// ④ 客户端侧（可以是 system 进程、App 的 native 部分）
#include <android/binder_manager.h>
std::shared_ptr<IDemo> demo = IDemo::fromBinder(
        ndk::SpAIBinder(AServiceManager_waitForService("android.hardware.xxx.demo.IDemo/default")));
if (demo != nullptr) {
    demo->doSomething(42);       // 跨进程调用，异常通过 ndk::ScopedAStatus 返回
}
```

**与 Java 侧的 AIDL 对照**：Java 用 `Ibinder`/`Stub`/`Proxy`，native 用 `AIBinder`/`BnXxx`/`BpXxx`，**两者可以互通**（一个 Java 客户端能调 native 服务，反之亦然），因为 binder 协议是统一的。

### 17.3 SELinux 三件套与 init.rc（新增 native 服务必做）

```
# ① device/<vendor>/<board>/sepolicy/vendor/file_contexts
/vendor/bin/hw/vendor\.xxx\.demo-service    u:object_r:vendor_xxx_demo_exec:s0

# ② device/<vendor>/<board>/sepolicy/vendor/vendor_xxx_demo.te
type vendor_xxx_demo, domain;
type vendor_xxx_demo_exec, exec_type, vendor_file_type, file_type;
init_daemon_domain(vendor_xxx_demo)                 # 让 init 能把它拉起来
binder_use(vendor_xxx_demo)                         # 用 binder
add_hwservice(vendor_xxx_demo, vendor_xxx_demo_hwservice)   # 若是 HAL，注册到 hwservice
allow vendor_xxx_demo vendor_xxx_demo_hwservice:hwservice_manager add;
allow vendor_xxx_demo sysfs_thermal:file r_file_perms;      # 按需逐条加，别写 allow ... *:* *

# ③ init rc：vendor.xxx.demo-service.rc
service vendor-xxx-demo /vendor/bin/hw/vendor.xxx.demo-service
    class hal
    user system
    group system
    capabilities NET_BIND_SERVICE
    writable_exec_dir_on_data
```

**报错长什么样**：native 服务跑起来但不工作 → 先看 `avc: denied`：

```bash
adb shell dmesg | grep "avc: denied" | grep xxx_demo
adb shell "cat /sys/fs/selinux/avc/cache_stats"     # 看是否频繁被拒
adb shell logcat -b all | grep -i avc | grep xxx_demo
# 也可以临时用 permissive 域确认是 SELinux 的问题（绝不带到量产）
adb shell setenforce 0     # 全局 permissive（仅调试）
```

### 17.4 VHAL 就是 native 的（与 automotive 篇的接口）

车机的车辆属性 HAL（VHAL）是一个**用 C++ 写的、基于 AIDL 的 vendor 服务**：`vendor.xxx.vehicle-service`。它的实现细节在 `automotive/03-VehicleHAL-VHAL详解.md` 里展开；这里只强调 NDK 视角的三点：

1. **VHAL 是 native 服务**：改属性语义、加自定义属性、处理多屏/多用户属性，都要写 C++；
2. **它的客户端可以是 Java（`CarPropertyManager`）也可以是 native**，走同一套 AIDL/binder；
3. **属性权限（`CarPropertyManager` 的读写权限）在客户端与 VHAL 两侧都会校验**，native 侧绕不过 `system` 分区的 sepolicy。

### 17.5 车机场景的 native 特有坑

| 场景 | 现象 | 原因 / 对策 |
|---|---|---|
| 多屏 | 多 display 下某个屏的 native 渲染不出来 | native 侧要按 `ASurfaceControl`/displayId 分别创建，别假设只有主屏 |
| 多用户 | 切用户后 native 服务状态错乱 | native 也要监听用户切换（`onUserSwitched`），不要用全局单例存用户态 |
| OTA / A-B | 升级后 native App 崩在 `cannot locate symbol` | system 与 vendor 库版本必须匹配（12.5）；VNDK 版本对齐 |
| 32/64 混装 | 某些模块只有 32 位 so，与 64 位 App 冲突 | `compile_multilib: "64"` 明确指定；混装时"进程位宽 = 主执行文件位宽" |
| 长跑稳定性 | 跑几天后 native 内存涨 | heapprofd / malloc_debug 常驻采样；把 native 缓存设上限 |
| 开机时序 | native 服务比依赖的东西起得早 | `init.rc` 里用 `wait_for_prop` / `interface_start`，或在 main 里等 |
| 写日志 | 日志把 eMMC/UFS 写爆 | native 侧也要限额滚动（与存储篇的"防写爆"同源） |

---

## 十八、调试工具箱

```bash
# ── ① 环境与版本（先确认"用的是哪套"）────────────────────────────
adb shell getprop ro.product.cpu.abilist                # 设备支持哪些 ABI
adb shell getprop ro.build.version.sdk                  # API 级别
adb shell getprop ro.vndk.version                       # VNDK 版本（vendor 相关）
adb shell getprop dalvik.vm.heapsize                    # Java 堆上限
adb shell getprop persist.sys.debug.malloc              # malloc debug 是否开着
./gradlew -q :app:dependencies | head                   # 确认 AGP/NDK 版本
cat $NDK/source.properties                              # NDK 版本号

# ── ② 加载期（.so 在哪、链了谁）────────────────────────────────
unzip -l app-release.apk | grep "\.so"                  # APK 里有哪几个 ABI 的 so
adb shell "ls -l /data/app/*/<pkg>-*/lib/arm64/"        # 解压后位置
adb shell "ls -l /system/lib64/libndkdemo.so"           # 分区 App 的位置
adb shell cat /proc/$(adb shell pidof <pkg>)/maps | grep -E "ndkdemo|\.so"  # 实际映射
adb shell cat /system/etc/public.libraries.txt           # App 能 dlopen 的系统公开库
adb shell cat /system/etc/ld.config.txt                  # namespace 白名单（12.2）
$BIN/llvm-readelf -d libndkdemo.so | grep NEEDED         # 它需要谁
$BIN/llvm-readelf -d libndkdemo.so | grep -E "SONAME|RPATH|RUNPATH"

# ── ③ 绑定期（符号在不在）──────────────────────────────────────
$BIN/llvm-nm -D --defined-only libndkdemo.so             # 导出了哪些符号
$BIN/llvm-nm -D libndkdemo.so | grep " U "                # 依赖哪些外部符号
$BIN/llvm-nm -D libndkdemo.so | grep -i "Java_\|JNI_OnLoad"
javap -s -p …/Bridge.class                               # 核对 Java 侧签名（第三节）

# ── ④ 崩溃与符号化 ────────────────────────────────────────────
adb logcat -b crash -v threadtime                        # 只看崩溃缓冲
adb logcat -s DEBUG libc linker art                      # 关键 tag
adb shell ls -l /data/tombstones/                        # 历史崩溃
adb pull /data/tombstones/tombstone_00
adb shell debuggerd -b $(adb shell pidof <pkg>)          # 活体抓 native 栈
$NDK/ndk-stack -sym ./obj/arm64-v8a -dump crash.log      # 一行命令符号化
$BIN/llvm-symbolizer --obj=libndkdemo.so --inlines 0x1a2c
$BIN/llvm-addr2line -f -C -e libndkdemo.so 0x1a2c
llvm-readelf -n libndkdemo.so | grep -A1 "Build ID"      # 版本追踪
md5sum libndkdemo.so                                     # 与设备上那份比对

# ── ⑤ 内存 ────────────────────────────────────────────────────
adb shell dumpsys meminfo <pkg> | sed -n '/App Summary/,/TOTAL/p'
adb shell dumpsys meminfo <pkg> | grep -i "native heap"
adb shell am dumpheap -n <pkg> /data/local/tmp/native.hprof      # 需先开 malloc_debug
# heapprofd 见 10.6；GWP-ASan 见 15.3

# ── ⑥ 性能 ────────────────────────────────────────────────────
python $NDK/simpleperf/app_profiler.py -p <pkg> -r "-g -f 1000 --duration 10" --ndk_path $NDK
adb shell atrace -t 5 -b 8192 gfx view sched freq -o /data/local/tmp/t            # 系统级 trace
# Perfetto：ui.perfetto.dev 或 `adb shell perfetto -c - --txt -o /data/misc/perfetto-traces/x`

# ── ⑦ 平台 / vendor ───────────────────────────────────────────
adb shell ls -l /vendor/lib64/ | head                    # vendor 侧库
adb shell dumpsys -l | grep -i vehicle                   # 车辆服务是否在
adb shell dmesg | grep "avc: denied"                     # SELinux 拒绝
adb shell getenforce                                     # Enforcing / Permissive
adb shell lshal | grep -i demo                           # HAL 是否注册成功
adb logcat -b all | grep -iE "linker|dlopen|namespace"   # 加载期细节

# ── ⑧ 构建排错 ───────────────────────────────────────────────
./gradlew assembleDebug --info | grep -i cmake           # 看 CMake 实际调用
# 生成的编译命令（验证 flags 真的传下去了）
cat app/.cxx/Debug/*/arm64-v8a/build.ninja | grep -m5 "FLAGS ="
```

---

## 十九、常见问题排查表

> 用法：先在"现象"列找到最像的，再按"第一条命令"动手。**"第几节"是详解位置。**

| # | 现象 | 最可能的原因 | 第几节 | 第一条命令 |
|---|---|---|---|---|
| 1 | `UnsatisfiedLinkError: findLibrary returned null` | APK 里没有该 ABI 的 so 目录 | 11.1 / 12.1 | `unzip -l app.apk \| grep '\.so'` |
| 2 | `dlopen failed: library "libX.so" not found` | 没打进包 / 打到错 ABI / 分区不一致 | 12.1 / 12.6 | `adb shell "ls -l /data/app/*/<pkg>-*/lib/arm64/"` |
| 3 | `dlopen failed: ... is 32-bit instead of 64-bit` | 32/64 混装 | 11.2 / 12.3 | `$BIN/llvm-readelf -h libX.so \| grep Class` |
| 4 | `dlopen failed: cannot locate symbol "foo"` | 依赖库版本不匹配 / 符号被隐藏 | 12.3 / 12.5 | `$BIN/llvm-nm -D dep.so \| grep foo` |
| 5 | `... is not accessible for the namespace` | App 撞了 namespace 白名单 | 12.2 | `adb shell cat /system/etc/public.libraries.txt` |
| 6 | `... has text relocations` | 老工具链 / 缺 `-fPIC` | 12.3 | `$BIN/llvm-readelf -d libX.so \| grep -i textrel` |
| 7 | `... invalid ELF header` / `file too short` | 文件损坏（HTML、换行、截断） | 12.3 | `file libX.so` |
| 8 | `No implementation found for …` | 命名约定写错 / 没注册 / `extern "C"` 漏了 / 静态库被丢弃 | 3.1 / 3.2 / 13.4 | `$BIN/llvm-nm -D libX.so \| grep Java_` |
| 9 | `NoSuchMethodError` / `RegisterNatives` 失败 | 签名（描述符）写错 | 3.2 | `javap -s -p Bridge.class` |
| 10 | `ExceptionInInitializerError` | `System.loadLibrary` 抛异常被包了一层 | 3.4 | `adb logcat \| grep -A5 ExceptionInInitializerError` |
| 11 | abort: `local reference table overflow (max=512)` | 循环里没 `DeleteLocalRef` | 6.1 | 检查循环内 `GetObjectArrayElement` |
| 12 | abort: `JNIEnv is not valid for this thread` | 缓存了 `JNIEnv*` | 7.1 | 全局搜 `JNIEnv*` 成员变量 |
| 13 | abort: `native thread exiting without having called DetachCurrentThread` | 忘了 `Detach` | 7.2 | 检查所有 `pthread_create` 的出口 |
| 14 | abort: `use of deleted global reference` | `DeleteGlobalRef` 后继续用 | 6.2 | 检查回调对象的生命周期 |
| 15 | abort: `... called with pending exception` | 上一处异常没清 | 8.2 | 检查每个 JNI 调用后的 `ExceptionCheck` |
| 16 | abort: `no "I" field ...` / `no method ...` | 字段/方法名或签名错、被混淆 | 8.4 | `javap -s -p` + 检查 keep 规则 |
| 17 | abort: `JNI critical lock held for 200ms` | `Critical` 区里阻塞/耗时 | 5.3 | 搜 `GetPrimitiveArrayCritical` |
| 18 | SIGSEGV `fault addr 0x0`，栈顶在你的 so | 空指针解引用 | 14.2 | `adb pull /data/tombstones/tombstone_00` |
| 19 | SIGSEGV 但栈里只有地址、没有符号名 | 用被 strip 的 so 符号化 | 14.3 | 换 `obj/` 或 `symbols/` 下的副本 |
| 20 | SIGABRT + `std::terminate` | C++ 异常没接住（bad_alloc 等） | 10.2 | 给边界函数包 try-catch |
| 21 | 只在 32 位机型出错，64 位正常 | `long` 截断 / 句柄截断 | 4.1 / 11.2 | 搜 `long`、`reinterpret_cast<.*\*>` |
| 22 | 时间戳/文件大小在 32 位上乱 | 同上 | 4.1 | 改用 `jlong`/`int64_t` |
| 23 | native 内存持续上涨 | `malloc`/`new` 漏释放 / 全局引用泄漏 | 6.2 / 10.3 | `dumpsys meminfo` + heapprofd |
| 24 | Java 对象回收不掉（Java 内存涨） | `NewGlobalRef` 无 `DeleteGlobalRef` | 6.2 | 搜 `NewGlobalRef` |
| 25 | 崩溃只在 release 出现 | UB 被 `-O2` 放大 / assert 被去掉 | 15.4 | 跑一轮 UBSan |
| 26 | 第三方 SDK 一引入就崩 | 多份 `libc++_shared.so` 冲突 | 11.5 | `llvm-readelf -d` 比对 NEEDED |
| 27 | 启动变慢 | `JNI_OnLoad` 里做重活 | 15.6 | Perfetto 看启动阶段 |
| 28 | 线上崩溃无法定位 | 没保留未剥离符号 / 没 build id | 13.7 / 14.6 | 归档 `obj/` 副本 + `--build-id` |
| 29 | 平台 App 加载不到库，库明明在 `/system/lib64` | App 在其他分区，namespace 不包含该目录 | 12.6 / 17.1 | `adb shell pm path <pkg>` |
| 30 | native 服务跑起来但不工作 | SELinux 拒绝 | 17.3 | `adb shell dmesg \| grep "avc: denied"` |
| 31 | 想动态加载新的 so 失败 | Android 10+ 可写目录执行限制 | 16.2 | 看 `avc: denied { execute }` |
| 32 | ASan 跑不起来 / App 立刻崩 | `wrap.sh` 无执行权限或路径错 | 15.2 | `ls -l app/src/main/resources/lib/arm64-v8a/` |

---

## 二十、读源码与读文档路线

### 20.1 五条线

**线一：ART 侧 JNI 的入口（理解"方法怎么被找到"）**

```
art/runtime/jni/jni_internal.cc                  ← JNI 所有函数的实现（CallXxxMethod、GetStringUTFChars…）
   └─ art/runtime/java_vm_ext.cc                 ← JavaVMExt::FindCodeForNativeMethod / FindNativeMethodInternal（符号查找）
   └─ art/runtime/jni/check_jni.cc               ← 所有 "JNI DETECTED ERROR" 判定的源头（★ 排查必读）
   └─ art/runtime/jni/java_vm_ext.cc             ← JNI_OnLoad 调用时机
   └─ art/runtime/thread.cc                      ← AttachCurrentThread / DetachCurrentThread / 线程局部引用表
   └─ art/runtime/jni/indirect_reference_table.cc← 局部引用表（512 那个数字在这里）
```

读法建议：**先在 `check_jni.cc` 里搜你遇到的报错字符串**，直接看到判定条件与上下文，比读文档快十倍。

**线二：linker 与 namespace（理解"库为什么找不到"）**

```
bionic/linker/linker.cpp                          ← dlopen 主流程
   └─ bionic/linker/linker_namespaces.cpp         ← namespace 白名单判定（"not accessible" 从这来）
   └─ system/core/rootdir/etc/ld.config.txt       ← 默认 namespace 配置（设备上是 /system/etc/ld.config.txt）
   └─ bionic/libc/bionic/libc_init_*.cpp          ← 进程启动时 linker 初始化
```

**线三：崩溃现场怎么产生的**

```
bionic/debuggerd/                                 ← 崩溃接收与 tombstone 生成
   └─ bionic/linker/…/ unwind                    ← 栈展开
   └─ system/core/debuggerd/handler/             ← 信号处理器
   └─ bionic/libc/bionic/abort.cpp               ← abort → SIGABRT 的路径（JNI 错误最终走这里）
```

**线四：NDK 头文件（最实用的"官方文档"）**

```
$NDK/toolchains/llvm/prebuilt/<host>/sysroot/usr/include/jni.h        ← JNI 全部 API 与注释（真·权威）
$NDK/toolchains/llvm/prebuilt/<host>/sysroot/usr/include/android/     ← NDK 原生 API（log.h、asset_manager.h、native_window.h…）
$NDK/sources/android/native_app_glue/                                 ← NativeActivity 的标准实现（约 100 行，值得通读）
$NDK/sources/cxx-stl/llvm-libc++/                                     ← STL（历史路径，新版在 toolchains 里）
```

**线五：找现成的正确写法（比任何教程都靠谱）**

```
system/core/libutils/                             ← RefBase、Thread、Looper 的 C++ 实现风格
system/logging/liblog/                            ← 日志库的写法
frameworks/base/core/jni/                         ← ★ 平台 App/JNI 的海量实例（android_util_Binder.cpp 等）
frameworks/native/libs/binder/                    ← native binder（C++）
system/core/libnativehelper/                      ← ★ ScopedLocalRef / JNIHelp（现成的 RAII 轮子）
hardware/interfaces/                              ← HAL 的 AIDL 与实现（vendor 侧范式）
packages/services/Car/car-lib/                    ← 车机 Java 侧（配合 automotive 篇）
```

### 20.2 官方文档（按重要性）

| 文档 | 看什么 |
|---|---|
| NDK 官方《JNI Tips》 | 性能建议、引用管理、`@CriticalNative` 的权威说明 |
| NDK 官方《Linking and Loading》(`ld.config`) | namespace、公开库列表、vendor 隔离 |
| NDK 官方《Address Sanitizer》 | wrap.sh 的确切要求（版本变化较多，务必看当前版本文档） |
| NDK 官方《Common Problems》 | 浓缩版的排查清单，与本文第十九节互补 |
| AOSP《ELF / 动态链接》与《VNDK》文档 | 平台 vndk/llndk 规则 |
| Android《Behavior changes》各版本页 | 权限/执行限制这类"按版本变"的规则 |

### 20.3 一个高效的排查习惯

**遇到 native 问题，先分类，再查**：

```
它是"找得到文件但没符号"  → 走 12.3 / 3.2（绑定问题）
它是"文件都没找到"        → 走 12.1 / 11.4（打包与 ABI 问题）
它是"跑着崩"              → 先读 tombstone 的 abort message（8.4 / 14.2），再符号化（14.3）
它是"慢"                  → simpleperf（15.5），先看是不是"跨界次数太多"（9.3）
它是"内存涨"              → 10.6
它是"平台 App 不生效"     → 12.6 / 17.1 / 17.3（分区 + namespace + SELinux）
```

---

## 二十一、一图总结

```
                        ┌──────────────────────────────────────┐
                        │  ① 编译期：CMake / Android.bp         │
                        │     选 ABI、选 STL、链库、留符号       │
                        │     ── 出错：undefined reference     │
                        └───────────────┬──────────────────────┘
                                        │ 产出 libndkdemo.so
                                        ▼
                        ┌──────────────────────────────────────┐
                        │  ② 打包期：放哪儿                     │
                        │     APK lib/<abi>/  或  /system/lib64 │
                        │     ── 出错：not found / 32-64 混装   │
                        └───────────────┬──────────────────────┘
                                        │ System.loadLibrary("ndkdemo")
                                        ▼
        ┌───────────────────────────────────────────────────────────────┐
        │  ③ 加载期：linker + namespace                                 │
        │     dlopen → 解析 DT_NEEDED → 检查 namespace 白名单            │
        │     ── 出错：dlopen failed: library not found                │
        │              dlopen failed: cannot locate symbol             │
        │              is not accessible for the namespace             │
        └───────────────┬───────────────────────────────────────────────┘
                        │ 成功 → 调用 JNI_OnLoad（注册方法、缓存全局引用）
                        ▼
        ┌───────────────────────────────────────────────────────────────┐
        │  ④ 绑定期：找到函数                                            │
        │     RegisterNatives（推荐） 或 命名约定 Java_pkg_Cls_m__II     │
        │     ── 出错：No implementation found for …                    │
        └───────────────┬───────────────────────────────────────────────┘
                        │ 每次调用都要过"五道规矩"
                        ▼
   ┌────────────────────────────────────────────────────────────────────────┐
   │  ⑤ 调用期：JNI 五道规矩                                                │
   │   ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐    │
   │   │ 类型转换  │ │ 引用管理  │ │线程绑定   │ │ 异常检查  │ │ 内存配对  │    │
   │   │ jlong!   │ │ 512 上限  │ │ 不许缓存  │ │ 先清理   │ │ 谁分配谁  │    │
   │   │ 第五节    │ │ 全局引用  │ │ JNIEnv    │ │ 再继续   │ │ 释放     │    │
   │   │ 第四节    │ │ 第六节    │ │ 第七节     │ │ 第八节   │ │ 第十节    │    │
   │   └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘    │
   │   ── 任一条违规：Android 直接 abort（JNI DETECTED ERROR）               │
   └───────────────────────────────┬────────────────────────────────────────┘
                                   │
                    ┌──────────────┴──────────────┐
                    ▼                             ▼
        ┌───────────────────────┐     ┌───────────────────────┐
        │ 一切正常               │     │ 出事                   │
        │ 继续过桥               │     │ SIGSEGV / SIGABRT      │
        └───────────────────────┘     │        ↓              │
                                      │ debuggerd → tombstone  │
                                      │        ↓              │
                                      │ 读 Abort message 优先  │
                                      │        ↓              │
                                      │ 用未剥离 so 符号化      │
                                      │ (ndk-stack /           │
                                      │  llvm-symbolizer)      │
                                      │        ↓              │
                                      │ 一直查不出来 → ASan    │
                                      │ (第十五节)             │
                                      └───────────────────────┘
```

### 一句话记忆链

> **JNI 是墙上的门：进门先报户口（类型），拿好通行证（引用），只能用自己那把钥匙（JNIEnv 按线程），出事必须马上处理（异常），借的东西一定还（配对释放）。**
>
> **构建决定墙在哪（ABI/打包），linker 决定门开不开（搜索路径/namespace），符号表决定门牌号认不认得（绑定），tombstone 决定事故报告怎么写（符号化）。**

### 三条最省时间的经验

1. **一律用 `RegisterNatives`，签名用 `javap -s` 抄** —— 直接消灭掉一半的"方法找不到"问题（第三节）。
2. **每个 JNI 调用后问一句"失败了会怎样"** —— 消灭掉一大半的 abort（第八节）。
3. **release 构建必须归档未剥离 so + build id** —— 没有它，线上 native 崩溃永远只能靠猜（第十四节）。

---

## 关联阅读

| 文档 | 关系 |
|---|---|
| `automotive/03-VehicleHAL-VHAL详解.md` | VHAL 就是 native AIDL 服务，本文第十七节是它的 NDK 视角 |
| `androidFrameworks/01_Android启动流程详解.md` | `init` 怎么拉起 native 服务、`SystemServer` 与 JNI 的关系 |
| `androidFrameworks/02_Binder机制详解.md` | AIDL → binder 的通用机制；本文 17.2 是它的 native 版 |
| `androidFrameworks/21_Audio机制详解.md` | `AAudio`/`AudioTrack` 在 native 侧的形态（第 15 章提及的低延迟路径） |
| `androidOthers/Android存储机制详解.md` | so 在分区里的落盘、`/system` 与 `/data` 的可写性与 dm-verity |
| `C++快速上手指南.md` | 不熟 C++ 时先读它（RAII、智能指针、move 语义是本文的基础） |

---


---
