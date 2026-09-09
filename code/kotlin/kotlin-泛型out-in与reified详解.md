# Kotlin 泛型、out / in 与 reified 详解

> Kotlin 泛型常见难点不是 `List<String>`，而是 `out`、`in`、`where`、`inline reified`。这些概念在 Repository、UseCase、Result、Koin、Retrofit、序列化、事件系统里都很常见。

---

## 目录

1. [泛型解决什么问题](#1-泛型解决什么问题)
2. [泛型函数和泛型类](#2-泛型函数和泛型类)
3. [上界约束](#3-上界约束)
4. [为什么需要 out / in](#4-为什么需要-out--in)
5. [`out`：生产者协变](#5-out生产者协变)
6. [`in`：消费者逆变](#6-in消费者逆变)
7. [星投影 `*`](#7-星投影-)
8. [类型擦除](#8-类型擦除)
9. [`inline reified` 是什么](#9-inline-reified-是什么)
10. [Android 常见例子](#10-android-常见例子)
11. [常见误区](#11-常见误区)

---

## 1. 泛型解决什么问题

泛型让一段代码可以服务多种类型，同时保留类型安全。

不用泛型：

```kotlin
class StringBox(val value: String)
class IntBox(val value: Int)
```

使用泛型：

```kotlin
class Box<T>(val value: T)

val nameBox = Box("Henry")
val ageBox = Box(6)
```

`T` 是类型参数，真正使用时会被具体类型替代。

---

## 2. 泛型函数和泛型类

### 泛型函数

```kotlin
fun <T> first(items: List<T>): T? {
    return items.firstOrNull()
}
```

调用：

```kotlin
val name: String? = first(listOf("A", "B"))
val age: Int? = first(listOf(1, 2))
```

### 泛型类

```kotlin
class Repository<T> {
    fun save(value: T) {}
    fun load(): T? = null
}
```

### 泛型接口

```kotlin
interface Mapper<I, O> {
    fun map(input: I): O
}
```

---

## 3. 上界约束

可以限制 `T` 必须是某种类型的子类：

```kotlin
fun <T : Number> double(value: T): Double {
    return value.toDouble() * 2
}
```

多个约束用 `where`：

```kotlin
fun <T> save(value: T)
    where T : CharSequence,
          T : Comparable<T> {
    println(value.length)
}
```

---

## 4. 为什么需要 out / in

假设有继承关系：

```kotlin
open class Animal
class Dog : Animal()
```

一个 `Dog` 可以赋值给 `Animal`：

```kotlin
val animal: Animal = Dog()
```

但 `MutableList<Dog>` 不能随便当成 `MutableList<Animal>`：

```kotlin
val dogs: MutableList<Dog> = mutableListOf(Dog())
// val animals: MutableList<Animal> = dogs // 不允许
```

如果允许，就可能这样出错：

```kotlin
animals.add(Cat())
```

这样 `dogs` 里就混进了不是 Dog 的对象。

所以 Kotlin 需要 `out` / `in` 来告诉编译器：这个泛型类型到底只产出 T，还是只消费 T。

---

## 5. `out`：生产者协变

`out T` 表示这个类型主要“生产 T”，可以把 `子类型容器` 当成 `父类型容器` 使用。

```kotlin
interface Producer<out T> {
    fun produce(): T
}
```

使用：

```kotlin
val dogProducer: Producer<Dog> = object : Producer<Dog> {
    override fun produce(): Dog = Dog()
}

val animalProducer: Producer<Animal> = dogProducer
```

为什么安全？因为你只从里面拿 `Animal`，而 `Dog` 本来就是 `Animal`。

记法：

```text
out = 往外拿 = 生产者 = 协变
```

常见例子：

```kotlin
interface ApiResult<out T>
```

如果 `ApiResult<User>` 可以当成 `ApiResult<Any>` 用，通常就需要 `out`。

---

## 6. `in`：消费者逆变

`in T` 表示这个类型主要“消费 T”。

```kotlin
interface Consumer<in T> {
    fun consume(value: T)
}
```

使用：

```kotlin
val animalConsumer: Consumer<Animal> = object : Consumer<Animal> {
    override fun consume(value: Animal) {}
}

val dogConsumer: Consumer<Dog> = animalConsumer
```

为什么安全？一个能处理所有 `Animal` 的消费者，当然能处理 `Dog`。

记法：

```text
in = 往里传 = 消费者 = 逆变
```

常见例子：

```kotlin
interface Comparator<in T> {
    fun compare(a: T, b: T): Int
}
```

---

## 7. 星投影 `*`

不知道泛型参数具体是什么时，可以用 `*`：

```kotlin
fun printList(list: List<*>) {
    list.forEach { println(it) }
}
```

`List<*>` 可以安全读取，读出来通常是 `Any?`。

但对于可变集合，不能安全写入具体值：

```kotlin
fun addItem(list: MutableList<*>) {
    // list.add("x") // 不允许
}
```

因为编译器不知道这个 list 原本是不是 `MutableList<Int>`。

---

## 8. 类型擦除

JVM 上泛型多数会在运行时被擦除。

```kotlin
val users: List<User> = listOf()
val names: List<String> = listOf()
```

运行时它们主要都是 `List`，不一定保留完整的 `User` / `String` 泛型信息。

所以这种判断不允许：

```kotlin
// if (value is List<String>) {}
```

通常只能判断：

```kotlin
if (value is List<*>) {}
```

---

## 9. `inline reified` 是什么

普通泛型函数里不能直接拿到 `T::class`：

```kotlin
fun <T> parse(json: String): T {
    // println(T::class) // 不允许
    TODO()
}
```

如果函数是 `inline`，类型参数可以标记为 `reified`：

```kotlin
inline fun <reified T> typeName(): String {
    return T::class.simpleName ?: "Unknown"
}
```

调用：

```kotlin
val name = typeName<User>()
```

`reified` 的意思是：内联后，调用点的具体类型能被带进函数体。

常见用途：

```kotlin
inline fun <reified T> Any?.castOrNull(): T? {
    return this as? T
}
```

```kotlin
val user = value.castOrNull<User>()
```

---

## 10. Android 常见例子

### Koin / DI

```kotlin
inline fun <reified T> Module.single(noinline definition: () -> T) {
    // 用 T 的类型作为 key 注册依赖
}
```

所以你能写：

```kotlin
single<UserRepository> { UserRepositoryImpl() }
```

### Intent extra 简化

```kotlin
inline fun <reified T> Intent.extra(key: String): T? {
    return extras?.get(key) as? T
}
```

### Result 建模

```kotlin
sealed interface Result<out T> {
    data class Success<T>(val value: T) : Result<T>
    data class Failure(val throwable: Throwable) : Result<Nothing>
}
```

`Failure` 用 `Nothing`，可以兼容任何 `Result<T>`。

---

## 11. 常见误区

- `out` 不是输出参数，它表示泛型类型主要生产 T。
- `in` 不是输入参数名称，它表示泛型类型主要消费 T。
- `MutableList<Dog>` 不能当成 `MutableList<Animal>`。
- `List<Dog>` 可以当成 `List<Animal>`，因为 Kotlin 的只读 `List` 是协变的。
- `reified` 只能用于 `inline` 函数的类型参数。
- `reified` 也不能完全解决所有嵌套泛型擦除问题，例如 `List<User>` 内部类型仍要谨慎处理。
