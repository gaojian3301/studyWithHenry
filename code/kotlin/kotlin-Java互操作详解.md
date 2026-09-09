# Kotlin 与 Java 互操作详解

> Android 项目很少是纯 Kotlin 世界。系统 API、旧业务代码、第三方 SDK、注解处理器、Gradle 插件都可能来自 Java。Kotlin 写得顺不顺，很大程度取决于你能不能看懂 Java 互操作里的平台类型、SAM、静态方法、异常和集合差异。

---

## 目录

1. [Kotlin 和 Java 为什么能互调](#1-kotlin-和-java-为什么能互调)
2. [平台类型 `String!`](#2-平台类型-string)
3. [Java 调 Kotlin：属性、方法和文件函数](#3-java-调-kotlin属性方法和文件函数)
4. [`@JvmStatic`](#4-jvmstatic)
5. [`@JvmOverloads`](#5-jvmoverloads)
6. [`@JvmField`](#6-jvmfield)
7. [SAM 转换](#7-sam-转换)
8. [异常差异](#8-异常差异)
9. [集合互操作](#9-集合互操作)
10. [可见性和命名](#10-可见性和命名)
11. [Android 常见场景](#11-android-常见场景)
12. [常见误区](#12-常见误区)

---

## 1. Kotlin 和 Java 为什么能互调

Kotlin/JVM 最终也编译成 JVM 字节码，所以 Kotlin 可以调用 Java，Java 也可以调用 Kotlin。

```kotlin
val intent = Intent(context, DetailActivity::class.java)
```

这里 `Intent` 就是 Java/Kotlin 都能使用的 Android API。

互操作时真正要注意的是：Kotlin 有一些语言特性 Java 没有，例如空安全、属性、默认参数、顶层函数、扩展函数。

---

## 2. 平台类型 `String!`

Java 类型没有天然区分可空和非空。Kotlin 调 Java 时，经常看到平台类型：

```text
String!
User!
```

它表示 Kotlin 不知道这个值能不能为 null。

Java：

```java
public String getName() {
    return null;
}
```

Kotlin：

```kotlin
val name = javaUser.name
println(name.length) // 可能 NPE
```

稳妥写法：

```kotlin
val name: String? = javaUser.name
val displayName = name ?: "Unknown"
```

如果 Java 有 `@Nullable` / `@NonNull` 注解，Kotlin 能更准确推断。

---

## 3. Java 调 Kotlin：属性、方法和文件函数

Kotlin 属性：

```kotlin
class User(
    val name: String,
    var age: Int
)
```

Java 调用：

```java
User user = new User("Henry", 6);
String name = user.getName();
user.setAge(7);
```

Kotlin 顶层函数：

```kotlin
// File: StringUtils.kt
fun normalize(value: String): String = value.trim().lowercase()
```

Java 默认这样调用：

```java
String result = StringUtilsKt.normalize(" Hello ");
```

可以用 `@file:JvmName` 改 Java 侧类名：

```kotlin
@file:JvmName("Strings")

fun normalize(value: String): String = value.trim().lowercase()
```

Java：

```java
String result = Strings.normalize(" Hello ");
```

---

## 4. `@JvmStatic`

Kotlin 的 `companion object` 不等于 Java 静态方法。

```kotlin
class UserManager {
    companion object {
        fun create(): UserManager = UserManager()
    }
}
```

Java 默认调用：

```java
UserManager manager = UserManager.Companion.create();
```

加 `@JvmStatic`：

```kotlin
class UserManager {
    companion object {
        @JvmStatic
        fun create(): UserManager = UserManager()
    }
}
```

Java 可以这样调用：

```java
UserManager manager = UserManager.create();
```

---

## 5. `@JvmOverloads`

Kotlin 支持默认参数：

```kotlin
class UserView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr)
```

Java 不认识默认参数。加 `@JvmOverloads` 后，Kotlin 会为 Java 生成多个重载构造/方法。

常见于自定义 View、Java 代码需要调用的工具方法。

---

## 6. `@JvmField`

Kotlin 属性默认会生成 getter/setter。如果 Java 侧需要直接字段，可以用 `@JvmField`：

```kotlin
object Constants {
    @JvmField
    val DEFAULT_TIMEOUT = 5000
}
```

Java：

```java
int timeout = Constants.DEFAULT_TIMEOUT;
```

更常见的编译期常量：

```kotlin
const val API_VERSION = 1
```

`const val` 只能用于顶层、object 或 companion object 中的基础类型和 String。

---

## 7. SAM 转换

SAM 是 Single Abstract Method，只有一个抽象方法的接口。

Java：

```java
public interface OnClickListener {
    void onClick(View view);
}
```

Kotlin 可以用 lambda 传入：

```kotlin
button.setOnClickListener { view ->
    println(view.id)
}
```

这就是 SAM 转换。

Kotlin 自己也可以定义 `fun interface`：

```kotlin
fun interface OnUserClickListener {
    fun onClick(user: User)
}
```

使用：

```kotlin
val listener = OnUserClickListener { user ->
    println(user.name)
}
```

---

## 8. 异常差异

Java 有 checked exception，Kotlin 没有强制 checked exception。

Java：

```java
public void read() throws IOException {}
```

Kotlin 调用时不强制 try-catch：

```kotlin
reader.read()
```

但异常仍然可能抛出。

如果 Kotlin 方法要让 Java 调用方看到 `throws`，可以加：

```kotlin
@Throws(IOException::class)
fun readFile() {
    throw IOException()
}
```

---

## 9. 集合互操作

Kotlin 区分只读集合和可变集合：

```kotlin
List<T>
MutableList<T>
```

Java 只有 `List<T>` 接口，没有 Kotlin 这种只读约束。Java 可以修改 Kotlin 传过去的底层可变集合。

```kotlin
val list: List<String> = mutableListOf("A")
javaApi.mutate(list)
```

如果要防御，传副本：

```kotlin
javaApi.useList(list.toList())
```

---

## 10. 可见性和命名

Kotlin 的 `internal` 在 JVM 上不是 Java 意义的包私有。Java 代码仍可能通过编译后的名字访问到一些成员。

Kotlin 关键字冲突时，可以用反引号：

```kotlin
javaObj.`is`()
```

不建议在自己代码里设计需要反引号才能调用的 API。

---

## 11. Android 常见场景

### 自定义 View 构造函数

```kotlin
class AvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr)
```

### Java SDK 回调转 lambda

```kotlin
sdk.setCallback { result ->
    handleResult(result)
}
```

### 给 Java 暴露工具类

```kotlin
object UserUtils {
    @JvmStatic
    fun formatName(user: User): String = user.name.trim()
}
```

### 处理 Java 返回 null

```kotlin
val title = legacyApi.title ?: "Untitled"
```

---

## 12. 常见误区

- 以为 Java 返回的值在 Kotlin 里一定受空安全保护。
- 忘记 `@JvmOverloads`，导致 Java/XML 相关构造调用不方便。
- 以为 companion object 方法天然就是 Java 静态方法。
- 把 Kotlin 只读集合传给 Java 后，以为 Java 不能改。
- Kotlin 不强制 checked exception，不代表不会抛异常。
- 为了 Java 调用方便过度牺牲 Kotlin API 的清晰度。
