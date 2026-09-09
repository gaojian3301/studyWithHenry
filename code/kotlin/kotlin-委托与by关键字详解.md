# Kotlin 委托与 by 关键字详解

> Kotlin 的 `by` 很常见：`by lazy`、`by viewModels()`、`by inject()`、`class A : B by b`。它背后是“把某件事交给另一个对象做”。Android 项目里，属性委托尤其高频。

---

## 目录

1. [`by` 的核心含义](#1-by-的核心含义)
2. [属性委托是什么](#2-属性委托是什么)
3. [`by lazy`](#3-by-lazy)
4. [`Delegates.observable`](#4-delegatesobservable)
5. [`by viewModels()`](#5-by-viewmodels)
6. [Koin 的 `by inject()`](#6-koin-的-by-inject)
7. [接口委托](#7-接口委托)
8. [自己实现属性委托](#8-自己实现属性委托)
9. [常见误区](#9-常见误区)

---

## 1. `by` 的核心含义

`by` 可以粗略理解为：

> 当前对象不亲自做这件事，而是委托给另一个对象。

两类最常见：

```kotlin
val name by lazy { "Henry" }
```

这是属性委托。

```kotlin
class UserRepository(cache: Cache) : Cache by cache
```

这是接口委托。

---

## 2. 属性委托是什么

普通属性：

```kotlin
val name: String = "Henry"
```

委托属性：

```kotlin
val name: String by lazy {
    "Henry"
}
```

访问 `name` 时，Kotlin 实际会调用委托对象的 `getValue()`。

如果是 `var`，还会调用 `setValue()`。

大概等价于：

```kotlin
private val nameDelegate = lazy { "Henry" }

val name: String
    get() = nameDelegate.value
```

---

## 3. `by lazy`

`lazy` 表示第一次访问时才初始化。

```kotlin
val config: Config by lazy {
    loadConfig()
}
```

特点：

- 没访问就不创建。
- 第一次访问后缓存结果。
- 默认线程安全。

Android 常见写法：

```kotlin
private val adapter by lazy {
    UserAdapter(onClick = ::openUser)
}
```

注意生命周期：

```kotlin
class UserFragment : Fragment() {
    private val binding by lazy {
        FragmentUserBinding.inflate(layoutInflater)
    }
}
```

这种写法要小心，因为 Fragment 的 view 生命周期可能短于 Fragment 实例生命周期。ViewBinding 更常见做法是 `_binding` 在 `onDestroyView()` 置空。

---

## 4. `Delegates.observable`

监听属性变化：

```kotlin
var count: Int by Delegates.observable(0) { property, oldValue, newValue ->
    println("${property.name}: $oldValue -> $newValue")
}
```

适合：

- 简单调试。
- 非 UI 框架里的轻量状态监听。
- 配置变化观察。

不建议替代 Android UI 状态体系。Compose / ViewModel 里更常用 `StateFlow`、`mutableStateOf`。

---

## 5. `by viewModels()`

AndroidX 里常见：

```kotlin
class UserActivity : AppCompatActivity() {
    private val viewModel: UserViewModel by viewModels()
}
```

这也是属性委托。

第一次访问 `viewModel` 时，委托对象会从 `ViewModelProvider` 里拿 ViewModel：

```text
访问 viewModel
  -> 调用委托 getValue
       -> 找 ViewModelStore
       -> 有就复用，没有就创建
```

Fragment 里还有：

```kotlin
private val viewModel: UserViewModel by viewModels()
private val activityViewModel: SharedViewModel by activityViewModels()
```

区别：

| 写法 | ViewModelStore owner |
|---|---|
| `by viewModels()` | 当前 Activity / Fragment |
| `by activityViewModels()` | 宿主 Activity |

---

## 6. Koin 的 `by inject()`

Koin 里常见：

```kotlin
class UserActivity : AppCompatActivity(), KoinComponent {
    private val repository: UserRepository by inject()
}
```

`inject()` 返回一个委托对象。第一次访问 `repository` 时，委托对象再去 Koin 容器里取依赖。

和 `get()` 对比：

```kotlin
private val a: UserRepository by inject() // 懒加载
private val b: UserRepository = get()     // 立即获取
```

如果依赖创建很重，`by inject()` 可以延迟到真正使用时。

---

## 7. 接口委托

接口委托可以减少转发代码。

```kotlin
interface Logger {
    fun log(message: String)
}

class ConsoleLogger : Logger {
    override fun log(message: String) {
        println(message)
    }
}

class UserService(
    logger: Logger
) : Logger by logger
```

`UserService` 自动拥有 `Logger` 的实现，相当于把 `log()` 转发给传入的 `logger`。

也可以覆盖其中部分方法：

```kotlin
class UserService(
    private val logger: Logger
) : Logger by logger {
    override fun log(message: String) {
        logger.log("UserService: $message")
    }
}
```

---

## 8. 自己实现属性委托

只读属性委托需要 `getValue`：

```kotlin
class TrimmedStringDelegate(
    private val value: String
) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): String {
        return value.trim()
    }
}

val title by TrimmedStringDelegate("  Hello  ")
```

可变属性委托需要 `getValue` 和 `setValue`：

```kotlin
class NonNegativeDelegate {
    private var value: Int = 0

    operator fun getValue(thisRef: Any?, property: KProperty<*>): Int {
        return value
    }

    operator fun setValue(thisRef: Any?, property: KProperty<*>, newValue: Int) {
        value = newValue.coerceAtLeast(0)
    }
}

var count by NonNegativeDelegate()
```

需要导入：

```kotlin
import kotlin.reflect.KProperty
```

---

## 9. 常见误区

- `by lazy` 不是每次访问都执行，只执行一次。
- `by inject()` 是懒获取依赖，不等于编译期注入。
- Fragment 里不要随便用 `by lazy` 持有 ViewBinding。
- `by viewModels()` 的生命周期取决于 ViewModelStoreOwner。
- 接口委托适合减少转发，但不要让类的职责变得模糊。
- 自定义委托很强，但业务代码里别为了炫技滥用。
