# Kotlin 空安全与数据建模详解

> 这篇讲 Kotlin 最日常、最影响代码质量的两块：空安全和数据建模。Android 代码里大量 UI state、接口结果、页面事件、错误状态，都可以靠 `?`、`data class`、`sealed class`、`when` 写得更清楚。

---

## 目录

1. [为什么 Kotlin 特别重视空安全](#1-为什么-kotlin-特别重视空安全)
2. [`?`、`?.`、`?:`、`!!` 怎么用](#2--怎么用)
3. [`let` 和判空链式写法](#3-let-和判空链式写法)
4. [平台类型：Java 互操作里的空安全漏洞](#4-平台类型java-互操作里的空安全漏洞)
5. [`data class`：数据对象的默认选择](#5-data-class数据对象的默认选择)
6. [`copy()` 和不可变状态更新](#6-copy-和不可变状态更新)
7. [`sealed class` / `sealed interface`：表达有限状态](#7-sealed-class--sealed-interface表达有限状态)
8. [`enum class` 适合什么场景](#8-enum-class-适合什么场景)
9. [`when` 和穷尽检查](#9-when-和穷尽检查)
10. [Android 常见建模方式](#10-android-常见建模方式)
11. [常见误区](#11-常见误区)

---

## 1. 为什么 Kotlin 特别重视空安全

Java 里任何引用都可能是 `null`，所以很多崩溃来自：

```text
NullPointerException
```

Kotlin 把“可不可以为空”放进类型系统：

```kotlin
val name: String = "Henry"   // 不能为 null
val nick: String? = null      // 可以为 null
```

这意味着：

- `String` 可以直接调用 `.length`。
- `String?` 必须先处理 null，才能安全使用。

```kotlin
fun printLength(name: String?) {
    // println(name.length) // 编译不过
    println(name?.length)
}
```

一句话：

> Kotlin 空安全不是为了让你少写判断，而是把“可能为空”这件事提前暴露在编译期。

---

## 2. `?`、`?.`、`?:`、`!!` 怎么用

### 可空类型 `?`

```kotlin
val user: User? = repository.findUser(id)
```

`User?` 表示这个变量可能没有值。

### 安全调用 `?.`

```kotlin
val nameLength = user?.name?.length
```

只要链路中有一个是 null，结果就是 null，不会崩溃。

### Elvis 操作符 `?:`

```kotlin
val displayName = user?.name ?: "Guest"
```

左边不是 null 就用左边，否则用右边。

也可以提前返回：

```kotlin
fun showUser(user: User?) {
    val realUser = user ?: return
    println(realUser.name)
}
```

### 非空断言 `!!`

```kotlin
val length = user!!.name.length
```

`!!` 的意思是：“我保证它不是 null，如果错了就崩。”

日常建议：

- 业务代码少用 `!!`。
- 测试、临时断言、确实由外部框架保证非空时才考虑。
- 能用 `?: return`、`?: error(...)`、`requireNotNull()` 就不要急着用 `!!`。

---

## 3. `let` 和判空链式写法

可空对象需要“有值才执行”时常用 `let`：

```kotlin
user?.let {
    println(it.name)
}
```

如果逻辑比较长，建议给 `it` 改名：

```kotlin
user?.let { currentUser ->
    println(currentUser.name)
    println(currentUser.age)
}
```

但不要把所有逻辑都塞进很长的判空链：

```kotlin
val title = user?.profile?.company?.address?.city?.let { city ->
    "City: $city"
} ?: "Unknown"
```

链太长时，拆成局部变量通常更好读。

---

## 4. 平台类型：Java 互操作里的空安全漏洞

Kotlin 调 Java 时，Java 类型可能显示成平台类型：

```kotlin
String!
User!
```

它的意思是：Kotlin 不知道它到底能不能为 null。

例如 Java：

```java
public String getName() {
    return null;
}
```

Kotlin 调用：

```kotlin
val name = javaUser.name
println(name.length) // 可能运行时 NPE
```

处理建议：

```kotlin
val name: String? = javaUser.name
val displayName = name ?: "Unknown"
```

如果你维护 Java API，尽量加注解：

```java
@NonNull
public String getName()

@Nullable
public String getNick()
```

---

## 5. `data class`：数据对象的默认选择

`data class` 适合表达“主要用来装数据”的对象。

```kotlin
data class User(
    val id: String,
    val name: String,
    val age: Int
)
```

它自动生成：

- `equals()`
- `hashCode()`
- `toString()`
- `copy()`
- `componentN()`

所以 UI state、网络 DTO、数据库实体、列表 item model 都经常用 `data class`。

---

## 6. `copy()` 和不可变状态更新

Android 里推荐用不可变 UI state：

```kotlin
data class UserUiState(
    val loading: Boolean = false,
    val user: User? = null,
    val error: String? = null
)
```

更新状态时不要改原对象，而是 copy 新对象：

```kotlin
_uiState.update { old ->
    old.copy(
        loading = false,
        user = user,
        error = null
    )
}
```

这样好处是：

- 状态变化更可追踪。
- Compose / Flow 更容易感知新状态。
- 不容易出现多个地方共享同一个可变对象导致的隐蔽 bug。

注意：`copy()` 是浅拷贝。如果字段里有 `MutableList`，里面的 list 仍然可能被共享。

```kotlin
data class State(val items: MutableList<String>)

val a = State(mutableListOf("A"))
val b = a.copy()
b.items.add("B")
println(a.items) // [A, B]
```

所以状态里优先用只读集合：

```kotlin
data class State(val items: List<String>)
```

---

## 7. `sealed class` / `sealed interface`：表达有限状态

`sealed` 适合表达“结果只可能是固定几种”。

```kotlin
sealed interface LoadState {
    data object Idle : LoadState
    data object Loading : LoadState
    data class Success(val user: User) : LoadState
    data class Error(val message: String) : LoadState
}
```

UI 里很常见：

```kotlin
when (val state = uiState.loadState) {
    LoadState.Idle -> Unit
    LoadState.Loading -> LoadingView()
    is LoadState.Success -> UserView(state.user)
    is LoadState.Error -> ErrorView(state.message)
}
```

优点：

- 状态种类集中定义。
- `when` 可以做穷尽检查。
- 比一堆 Boolean 更清楚。

不要这样建模：

```kotlin
data class UiState(
    val loading: Boolean,
    val success: Boolean,
    val error: String?
)
```

这种状态可能同时出现 `loading=true`、`success=true`、`error!=null`，语义混乱。

---

## 8. `enum class` 适合什么场景

`enum` 适合表达简单固定枚举：

```kotlin
enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM
}
```

适合：

- 简单类型分类。
- 不需要每种状态携带不同数据。
- 需要稳定名称或 ordinal 之外的字段。

```kotlin
enum class AudioQuality(val bitrate: Int) {
    LOW(96),
    MEDIUM(160),
    HIGH(320)
}
```

如果每个分支携带的数据结构不同，优先考虑 `sealed`。

---

## 9. `when` 和穷尽检查

`when` 搭配 `sealed` 很强：

```kotlin
fun render(state: LoadState) {
    when (state) {
        LoadState.Idle -> Unit
        LoadState.Loading -> Unit
        is LoadState.Success -> println(state.user)
        is LoadState.Error -> println(state.message)
    }
}
```

如果以后新增：

```kotlin
data object Empty : LoadState
```

编译器会提示 `when` 没处理完整。

这比用字符串或 int 常量可靠很多。

---

## 10. Android 常见建模方式

### 页面状态

```kotlin
data class UserUiState(
    val isLoading: Boolean = false,
    val user: User? = null,
    val message: UiMessage? = null
)
```

### 一次性事件

```kotlin
sealed interface UserEvent {
    data object NavigateBack : UserEvent
    data class ShowToast(val message: String) : UserEvent
}
```

### 接口结果

```kotlin
sealed interface ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>
    data class Failure(val code: Int, val message: String) : ApiResult<Nothing>
}
```

这里 `out T` 表示协变，详细看泛型文档。

---

## 11. 常见误区

- 用 `!!` 快速解决编译错误，后面变成运行时崩溃。
- 用多个 Boolean 表达互斥状态。
- 在 UI state 里放 `MutableList`、`MutableMap`。
- `data class.copy()` 当成深拷贝。
- Java 返回的平台类型不做空值防御。
- `when` 里加 `else` 掩盖 sealed 状态新增后的编译提醒。
