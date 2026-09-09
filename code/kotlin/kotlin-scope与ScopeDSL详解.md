# Kotlin Scope 与 ScopeDSL 详解

> 这篇专门解释代码里经常看到的 `scope`、`ScopeDSL`、`this`、`it`、`apply`、`let`、`module { }`、`scope { }`、`ColumnScope`、`CoroutineScope` 这类写法。Kotlin 里的 scope 不是一个单一概念，它可能指语言作用域、对象配置作用域、DSL 接收者作用域、依赖注入生命周期、协程生命周期或 Compose 布局作用域。

---

## 目录

- [Kotlin Scope 与 ScopeDSL 详解](#kotlin-scope-与-scopedsl-详解)
  - [目录](#目录)
  - [1. 先分清 Kotlin 里的几种 scope](#1-先分清-kotlin-里的几种-scope)
  - [2. 普通作用域：变量在哪里可见](#2-普通作用域变量在哪里可见)
  - [3. Scope Functions：let、run、with、apply、also](#3-scope-functionsletrunwithapplyalso)
    - [`let`：拿对象做转换](#let拿对象做转换)
    - [`apply`：配置对象，然后返回对象本身](#apply配置对象然后返回对象本身)
    - [`also`：不改变主流程，顺手做副作用](#also不改变主流程顺手做副作用)
    - [`run`：在对象上下文里算一个结果](#run在对象上下文里算一个结果)
    - [`with`：不是扩展函数，适合集中访问对象](#with不是扩展函数适合集中访问对象)
    - [怎么选](#怎么选)
  - [4. `this` 和 `it` 怎么判断](#4-this-和-it-怎么判断)
  - [5. 带 receiver 的 lambda](#5-带-receiver-的-lambda)
  - [6. DSL 是怎么写出来的](#6-dsl-是怎么写出来的)
  - [7. `@DslMarker` 解决什么问题](#7-dslmarker-解决什么问题)
  - [8. ScopeDSL 常见来源：Koin 依赖注入](#8-scopedsl-常见来源koin-依赖注入)
    - [`module {}`、`scope {}`、`scoped {}` 分别在干什么](#module-scope-scoped--分别在干什么)
    - [定义阶段和运行阶段](#定义阶段和运行阶段)
    - [`single`、`factory`、`scoped` 的生命周期区别](#singlefactoryscoped-的生命周期区别)
    - [`get()` 在 `scoped {}` 里从哪里找依赖](#get-在-scoped--里从哪里找依赖)
    - [带参数的 scoped 依赖](#带参数的-scoped-依赖)
    - [Android 里常见的 Koin scope 写法](#android-里常见的-koin-scope-写法)
    - [Koin scope 适合放什么](#koin-scope-适合放什么)
    - [Koin scope 和 ViewModel 的关系](#koin-scope-和-viewmodel-的关系)
    - [ScopeDSL 背后的 Kotlin 语法](#scopedsl-背后的-kotlin-语法)
    - [DI scope 和普通 scope 的区别](#di-scope-和普通-scope-的区别)
    - [常见误区](#常见误区)
  - [9. CoroutineScope、LifecycleScope、ViewModelScope](#9-coroutinescopelifecyclescopeviewmodelscope)
  - [10. Compose 里的 Scope：ColumnScope、RowScope、BoxScope](#10-compose-里的-scopecolumnscoperowscopeboxscope)
  - [11. 读 scope / DSL 代码的步骤](#11-读-scope--dsl-代码的步骤)
    - [第一步：看当前代码块是谁的 lambda](#第一步看当前代码块是谁的-lambda)
    - [第二步：判断 `this` 和 `it`](#第二步判断-this-和-it)
    - [第三步：看返回值](#第三步看返回值)
    - [第四步：判断这个 scope 管的是可见性还是生命周期](#第四步判断这个-scope-管的是可见性还是生命周期)
  - [12. Kotlin 还必须掌握哪些用法](#12-kotlin-还必须掌握哪些用法)
  - [13. 源码和排查关键词](#13-源码和排查关键词)

---

## 1. 先分清 Kotlin 里的几种 scope

看到 `scope` 时，先不要急着翻译成“作用域”，要先判断它是哪一种：

| 名称 | 例子 | 本质 |
|---|---|---|
| 语言作用域 | `{ val a = 1 }` | 变量在哪里能访问 |
| scope function | `user.apply { name = "Tom" }` | 临时进入某个对象的上下文 |
| DSL receiver scope | `html { body { } }` | lambda 里隐式拿到某个接收者对象 |
| DI scope | `scope<Activity> { scoped { Presenter(get()) } }` | 依赖对象的生命周期范围 |
| CoroutineScope | `viewModelScope.launch { }` | 协程任务的生命周期边界 |
| Compose scope | `Column { Text(...) }`、`BoxScope.align()` | 布局 DSL 给子组件提供能力 |

一句话：

> Kotlin 的 scope 不是一个语法点，而是一组“代码在什么上下文里执行、能访问谁、生命周期归谁管”的概念。

---

## 2. 普通作用域：变量在哪里可见

最基础的 scope 是代码块作用域。

```kotlin
fun demo() {
    val outer = "outer"

    if (true) {
        val inner = "inner"
        println(outer)
        println(inner)
    }

    println(outer)
    // println(inner) // 这里访问不到
}
```

这类 scope 的规则很简单：

- 外层变量可以在内层访问。
- 内层变量不能在外层访问。
- 内层可以定义同名变量，但不建议滥用。

```kotlin
val name = "outer"

run {
    val name = "inner"
    println(name) // inner
}
```

读 Kotlin DSL 时经常困惑，是因为 DSL 不是只靠普通作用域，还会叠加“隐式 receiver”。

---

## 3. Scope Functions：let、run、with、apply、also

Kotlin 标准库里的 scope functions 是最常见的迷惑来源。

它们都做同一件事：

> 把一段代码放进某个对象的上下文里执行。

区别主要看两个问题：

1. 这个对象在代码块里叫 `this` 还是 `it`？
2. 整个表达式返回原对象，还是返回 lambda 最后一行？

| 函数 | 对象名字 | 返回值 | 常见用途 |
|---|---|---|---|
| `let` | `it` | lambda 最后一行 | 判空、转换结果 |
| `run` | `this` | lambda 最后一行 | 对象上下文里计算结果 |
| `with` | `this` | lambda 最后一行 | 对一个对象连续操作，不是扩展函数 |
| `apply` | `this` | 原对象 | 配置对象 |
| `also` | `it` | 原对象 | 顺手打印、校验、副作用 |

### `let`：拿对象做转换

```kotlin
val length = name?.let {
    it.trim().length
}
```

适合：

- 判空后使用对象。
- 把对象转换成另一个值。
- 希望对象有明确名字时，可以改名。

```kotlin
val title = user?.let { currentUser ->
    "Hello, ${currentUser.name}"
}
```

### `apply`：配置对象，然后返回对象本身

```kotlin
val intent = Intent(context, DetailActivity::class.java).apply {
    putExtra("id", id)
    putExtra("title", title)
}
```

适合：

- 创建对象后连续设置属性。
- Builder 风格配置。
- Android 里配置 `Intent`、`Bundle`、`TextView`、`Paint` 等对象。

### `also`：不改变主流程，顺手做副作用

```kotlin
val user = repository.loadUser()
    .also { println("loaded user: $it") }
```

适合：

- 打日志。
- 临时调试。
- 对链式调用中的中间值做检查。

### `run`：在对象上下文里算一个结果

```kotlin
val displayName = user.run {
    if (nickname.isNotBlank()) nickname else name
}
```

适合：

- 需要 `this` 访问对象成员。
- 最后返回一个新结果。

### `with`：不是扩展函数，适合集中访问对象

```kotlin
val summary = with(user) {
    "$name / $age"
}
```

适合：

- 对同一个对象读多个字段。
- 生成一个结果。

### 怎么选

简单记法：

```text
要返回原对象：apply / also
要返回计算结果：let / run / with

想用 this：apply / run / with
想用 it：let / also
```

更实用的选择：

| 场景 | 推荐 |
|---|---|
| 判空后执行 | `?.let { }` |
| 创建后配置对象 | `apply { }` |
| 链式调用中打日志 | `also { }` |
| 在对象内部算一个结果 | `run { }` |
| 对已有对象集中读取字段 | `with(obj) { }` |

---

## 4. `this` 和 `it` 怎么判断

这是读 scope 代码的关键。

```kotlin
user.let {
    println(it.name)
}
```

`let` 里对象叫 `it`。

```kotlin
user.apply {
    println(name)
    println(this.name)
}
```

`apply` 里对象叫 `this`，所以可以省略 `this.`。

问题来了，如果有多层 `this`：

```kotlin
class UserScreen {
    val name = "screen"

    fun render(user: User) {
        user.apply {
            println(name)
        }
    }
}
```

这里 `println(name)` 优先找 `user.name`。如果你想访问外层 `UserScreen.name`，需要标签：

```kotlin
class UserScreen {
    val name = "screen"

    fun render(user: User) {
        user.apply {
            println(this.name)           // User.name
            println(this@UserScreen.name) // UserScreen.name
        }
    }
}
```

读不懂 DSL 时，第一步就是问：

```text
当前代码块里的 this 是谁？
当前代码块里的 it 是谁？
有没有外层 this 被遮住？
```

---

## 5. 带 receiver 的 lambda

Kotlin DSL 的核心是“带 receiver 的 lambda”。

普通 lambda 是这样：

```kotlin
val block: (User) -> String = { user ->
    user.name
}
```

带 receiver 的 lambda 是这样：

```kotlin
val block: User.() -> String = {
    name
}
```

区别：

| 类型 | lambda 里怎么访问 User |
|---|---|
| `(User) -> String` | 通过参数 `user.name` 或 `it.name` |
| `User.() -> String` | 通过隐式 `this.name`，可以省略成 `name` |

自己写一个最小例子：

```kotlin
class Html {
    fun body(block: Body.() -> Unit) {
        val body = Body()
        body.block()
    }
}

class Body {
    fun text(value: String) {
        println(value)
    }
}

fun html(block: Html.() -> Unit) {
    val html = Html()
    html.block()
}
```

使用时：

```kotlin
html {
    body {
        text("Hello")
    }
}
```

为什么 `body { }` 可以直接调用？因为 `html { }` 里面的 `this` 是 `Html`。

为什么 `text("Hello")` 可以直接调用？因为 `body { }` 里面的 `this` 是 `Body`。

这就是 DSL 的基本机制。

---

## 6. DSL 是怎么写出来的

很多 Kotlin 框架都用了 DSL：

```kotlin
dependencies {
    implementation("xxx")
}

module {
    single { UserRepository(get()) }
}

Column {
    Text("Hello")
}
```

这些代码看起来像语法，其实多数只是函数调用 + lambda receiver。

比如：

```kotlin
fun module(block: Module.() -> Unit): Module {
    val module = Module()
    module.block()
    return module
}

class Module {
    fun single(block: Scope.() -> Any) {
        // 保存一个依赖定义
    }
}
```

于是可以写：

```kotlin
val appModule = module {
    single { UserRepository(get()) }
}
```

真正发生的是：

```text
调用 module 函数
  -> 创建 Module 对象
  -> 把 lambda 放到 Module 这个 receiver 上执行
       -> 所以 lambda 内能直接调用 single
```

DSL 的优点：

- 代码更接近配置文件。
- 可以隐藏样板代码。
- 可以把“某个上下文里允许做什么”限制得更清楚。

DSL 的缺点：

- 多层 `this` 容易混乱。
- 函数不像普通调用那么显眼。
- 新手很难看出 `get()`、`single()`、`scoped()` 到底来自哪里。

---

## 7. `@DslMarker` 解决什么问题

多层 DSL 最大的问题是：内层 scope 可能误调用外层 scope 的方法。

例如：

```kotlin
html {
    body {
        body {
            text("nested")
        }
    }
}
```

如果不限制，内层可能还能随便调用外层 receiver 的方法，代码可读性和正确性都会变差。

Kotlin 提供 `@DslMarker` 来限制这种隐式 receiver 混用：

```kotlin
@DslMarker
annotation class HtmlDsl

@HtmlDsl
class Html {
    fun body(block: Body.() -> Unit) {}
}

@HtmlDsl
class Body {
    fun text(value: String) {}
}
```

加上之后，同一 DSL 层级里，内层 receiver 会遮住外层 receiver。你如果真的要访问外层，需要显式标签。

一句话：

> `@DslMarker` 是给 DSL 加护栏，避免你在内层代码块里误调用外层上下文的方法。

Compose、Gradle、Kotlin HTML DSL、一些 DI 框架都会用类似思路控制 DSL 的可见范围。

---

## 8. ScopeDSL 常见来源：Koin 依赖注入

你看到的 `ScopeDSL` 很可能不是 Kotlin 标准库，而是某个框架定义的类。Android 项目里常见来源之一是 Koin。

先抓住一句话：

> Koin 的 `ScopeDSL` 是 `scope { ... }` 配置块里的 receiver，它用来声明“这个 scope 生命周期内有哪些依赖可以被创建和复用”。

Koin 里经常看到：

```kotlin
val appModule = module {
    single { UserRepository(get()) }
    factory { UserViewModel(get()) }

    scope<ActivityA> {
        scoped { PagePresenter(get()) }
    }
}
```

这里有几层概念：

| 写法 | 含义 |
|---|---|
| `module { }` | 定义一组依赖 |
| `single { }` | 容器内单例 |
| `factory { }` | 每次注入都创建新对象 |
| `scope<T> { }` | 定义一个和某种生命周期绑定的依赖范围 |
| `scoped { }` | 在这个 scope 内复用的对象 |
| `get()` | 从当前 Koin 上下文取依赖 |

`ScopeDSL` 通常就是 `scope { }` 代码块里的 receiver 类型。也就是说：

```kotlin
scope<ActivityA> {
    scoped { PagePresenter(get()) }
}
```

这段代码块内部可以直接调用 `scoped`，是因为当前 lambda 的 `this` 是类似 `ScopeDSL` 的对象。

大概可以理解成：

```kotlin
fun Module.scope(block: ScopeDSL.() -> Unit) {
    val scopeDsl = ScopeDSL()
    scopeDsl.block()
}
```

所以读 `ScopeDSL` 代码时，不要把它想复杂。它本质上通常是：

```text
一个专门给 DSL 代码块当 this 的对象
```

### `module {}`、`scope {}`、`scoped {}` 分别在干什么

这几个词长得像，但层级不同：

```text
module { }
  └─ 定义一个 Koin 模块

scope<ActivityA> { }
  └─ 在模块里定义一个 scope 类型

scoped { PagePresenter(get()) }
  └─ 在这个 scope 类型里定义一个依赖
```

所以这段：

```kotlin
val pageModule = module {
    scope<ActivityA> {
        scoped { PagePresenter(get()) }
        scoped { PageTracker() }
    }
}
```

含义不是“现在立刻创建 `ActivityA` 的 scope”，而是：

```text
注册规则：
  当以后创建 ActivityA 这个 scope 时，
  这个 scope 里可以创建并复用 PagePresenter 和 PageTracker。
```

这是 Koin scope 最容易混的地方：

```text
ScopeDSL：定义阶段的 DSL 对象
Scope：运行时真正存在的 scope 实例
```

### 定义阶段和运行阶段

定义阶段：

```kotlin
val appModule = module {
    scope<ActivityA> {
        scoped { PagePresenter(get()) }
    }
}
```

这一段通常在应用启动时加载：

```kotlin
startKoin {
    modules(appModule)
}
```

此时 Koin 只是保存依赖定义，不一定创建 `PagePresenter`。

运行阶段：

```kotlin
class ActivityA : AppCompatActivity(), KoinScopeComponent {
    override val scope: Scope by activityScope()

    private val presenter: PagePresenter by inject()
}
```

Activity 真的启动后，Koin 才会创建或拿到一个运行时 `Scope`，然后在这个 scope 里解析 `PagePresenter`。

可以这样画：

```text
启动时加载 module
  └─ ScopeDSL 记录 scoped 定义

ActivityA 创建
  └─ 创建运行时 Scope 实例
       └─ inject PagePresenter
            └─ 第一次创建并放进这个 Scope
            └─ 后续在同一 Scope 内复用

ActivityA 销毁
  └─ close Scope
       └─ scoped 对象释放引用
```

### `single`、`factory`、`scoped` 的生命周期区别

| 写法 | 创建次数 | 生命周期 |
|---|---|---|
| `single { }` | Koin 容器内一个实例 | 跟 Koin application 绑定，通常接近 App 级别 |
| `factory { }` | 每次请求都创建新实例 | 不缓存，谁拿到谁自己使用 |
| `scoped { }` | 每个运行时 Scope 内一个实例 | 跟某个 Koin Scope 绑定，scope 关闭后释放 |

例子：

```kotlin
val appModule = module {
    single { AppDatabase(get()) }
    factory { UserFormatter() }

    scope<UserDetailActivity> {
        scoped { UserDetailPresenter(get(), get()) }
    }
}
```

含义：

- `AppDatabase` 全局复用。
- `UserFormatter` 每次要都新建。
- `UserDetailPresenter` 在同一个 `UserDetailActivity` scope 内复用，不同 Activity 实例之间不共享。

### `get()` 在 `scoped {}` 里从哪里找依赖

```kotlin
scope<UserDetailActivity> {
    scoped { UserDetailPresenter(get(), get()) }
}
```

这里的 `get()` 会按 Koin 的解析规则找依赖。粗略理解：

```text
先看当前 scope 里有没有定义
  -> 再看外层 Koin 容器里有没有 single/factory
       -> 再结合类型、qualifier、参数决定拿哪个
```

比如：

```kotlin
val appModule = module {
    single<UserRepository> { UserRepositoryImpl() }

    scope<UserDetailActivity> {
        scoped { UserDetailPresenter(repository = get()) }
    }
}
```

`UserDetailPresenter` 是 scope 内对象，但它依赖的 `UserRepository` 可以来自全局 `single`。

如果同一个类型有多个实现，就要用 qualifier：

```kotlin
val appModule = module {
    single(named("remote")) { RemoteUserRepository() }
    single(named("local")) { LocalUserRepository() }

    scope<UserDetailActivity> {
        scoped {
            UserDetailPresenter(
                repository = get(named("remote"))
            )
        }
    }
}
```

### 带参数的 scoped 依赖

页面级对象经常需要页面参数，例如 `userId`。

```kotlin
val appModule = module {
    scope<UserDetailActivity> {
        scoped { parameters ->
            UserDetailPresenter(
                userId = parameters.get(),
                repository = get()
            )
        }
    }
}
```

使用时传入参数：

```kotlin
class UserDetailActivity : AppCompatActivity(), KoinScopeComponent {
    override val scope: Scope by activityScope()

    private val presenter: UserDetailPresenter by inject {
        parametersOf(intent.getStringExtra("userId"))
    }
}
```

这表示：

```text
Presenter 的生命周期归 Activity scope 管
Presenter 的 userId 来自当前页面参数
Presenter 的 repository 从 Koin 容器解析
```

### Android 里常见的 Koin scope 写法

Koin Android 通常会提供一些便捷 API，例如 `activityScope()`、`fragmentScope()`。实际可用 API 会随 Koin 版本变化，但思路一致：

```kotlin
class UserDetailActivity : AppCompatActivity(), KoinScopeComponent {
    override val scope: Scope by activityScope()

    private val presenter: UserDetailPresenter by inject()
}
```

Fragment 场景类似：

```kotlin
class UserDetailFragment : Fragment(), KoinScopeComponent {
    override val scope: Scope by fragmentScope()

    private val presenter: UserDetailPresenter by inject()
}
```

如果不用 Android 扩展，也可能看到手动创建和关闭：

```kotlin
class PageController : KoinComponent {
    private val scope = getKoin().createScope<PageController>()

    val presenter: PagePresenter = scope.get()

    fun destroy() {
        scope.close()
    }
}
```

手动 scope 的重点是：谁创建，谁关闭。

### Koin scope 适合放什么

适合放在 `scoped` 里的对象：

- 页面 Presenter。
- 页面级 UseCase 聚合对象。
- 和 Activity/Fragment 生命周期一致的 coordinator。
- 页面级缓存。
- 需要在同一个页面内多处共享，但不该全局单例的状态对象。

不适合放在 `scoped` 里的对象：

- 全局数据库、网络客户端，这类更适合 `single`。
- 完全无状态的小工具类，这类通常 `factory` 或直接构造都可以。
- 持有短生命周期 View 的对象，如果 scope 比 View 生命周期更长，容易泄漏。
- 本应由 ViewModel 管的 UI 状态，如果混进 Activity scope，可能和配置变更行为冲突。

### Koin scope 和 ViewModel 的关系

很多 Android 项目里，页面状态更推荐放到 ViewModel。Koin scope 不等于 ViewModel 生命周期。

```text
ViewModel
  └─ 更适合保存页面 UI 状态，处理配置变更

Koin scoped object
  └─ 更适合表达“这个依赖在某个业务/页面 scope 内复用”
```

如果一个对象需要经历屏幕旋转后仍然保留，通常优先考虑 ViewModel。如果只是 Activity 实例级别复用，Koin activity scope 就够。

### ScopeDSL 背后的 Kotlin 语法

从 Kotlin 角度看，Koin 可能大致类似这样设计：

```kotlin
class ModuleDSL {
    inline fun <reified T> scope(block: ScopeDSL.() -> Unit) {
        val scopeDsl = ScopeDSL(scopeType = T::class)
        scopeDsl.block()
        save(scopeDsl.definitions)
    }
}

class ScopeDSL {
    inline fun <reified T> scoped(noinline definition: Scope.() -> T) {
        // 保存 T 在当前 scope 中的创建规则
    }
}
```

所以：

```kotlin
scope<UserDetailActivity> {
    scoped { UserDetailPresenter(get()) }
}
```

可以拆成：

```text
scope<UserDetailActivity> { ... }
  -> 当前 this 是 ScopeDSL
  -> 所以能直接调用 scoped { ... }

scoped { UserDetailPresenter(get()) }
  -> 保存 UserDetailPresenter 的创建 lambda
  -> 创建 lambda 运行时可以通过 get() 解析依赖
```

注意：上面是帮助理解的简化模型，不等同于 Koin 源码的完整实现。

### DI scope 和普通 scope 的区别

DI scope 不是变量作用域，而是对象生命周期。

```kotlin
single { UserRepository() }
```

表示应用容器里只有一个 `UserRepository`。

```kotlin
factory { UserFormatter() }
```

表示每次需要时都创建一个新的 `UserFormatter`。

```kotlin
scope<ActivityA> {
    scoped { PagePresenter(get()) }
}
```

表示 `PagePresenter` 在某个 Activity scope 里复用；scope 关闭后，对象也应该释放。

### 常见误区

- `scope { }` 不是协程作用域。
- `scoped { }` 不是 Kotlin 语言关键字。
- `get()` 不是全局函数，通常来自当前 DI DSL receiver 或上下文。
- `single` 不等于 Kotlin `object`，它是 DI 容器管理的单例。
- scope 没关闭时，里面对象可能一直被持有，容易造成泄漏。

---

## 9. CoroutineScope、LifecycleScope、ViewModelScope

协程里的 scope 是生命周期边界。

```kotlin
viewModelScope.launch {
    val user = repository.loadUser()
    _uiState.value = UiState.Success(user)
}
```

这里 `viewModelScope` 的意思是：

```text
这个 scope 里启动的协程归 ViewModel 管
ViewModel 清理时，这些协程会取消
```

常见 scope：

| Scope | 生命周期 |
|---|---|
| `viewModelScope` | 跟 ViewModel 绑定 |
| `lifecycleScope` | 跟 Activity/Fragment lifecycle 绑定 |
| `rememberCoroutineScope()` | 跟当前 Composable 组合生命周期绑定 |
| `CoroutineScope(...)` | 手动创建，需要自己取消 |
| `GlobalScope` | 全局，不跟业务生命周期绑定，日常慎用 |

协程 scope 的核心问题不是“能访问哪些变量”，而是：

```text
谁负责取消这些协程？
子协程失败会不会影响兄弟协程？
异常往哪里传播？
```

所以它和 `apply { }`、`let { }` 不是一类东西。

---

## 10. Compose 里的 Scope：ColumnScope、RowScope、BoxScope

Compose 里也大量使用 receiver scope。

```kotlin
Column {
    Text("Title")
    Spacer(modifier = Modifier.weight(1f))
}
```

这里 `Column { }` 的内容 lambda 通常运行在 `ColumnScope` 里，所以里面能使用 `Modifier.weight()`。

类似地：

```kotlin
Box {
    Text(
        text = "Top End",
        modifier = Modifier.align(Alignment.TopEnd)
    )
}
```

`align` 是 `BoxScope` 里可用的能力。你把同样代码搬到 `Column` 外面，可能就不能用了。

可以这样记：

| Compose scope | 常见能力 |
|---|---|
| `ColumnScope` | `Modifier.weight()`、纵向布局相关能力 |
| `RowScope` | `Modifier.weight()`、横向布局相关能力 |
| `BoxScope` | `Modifier.align()`、叠放定位能力 |

所以 Compose 的 scope 本质是：

> 当前布局容器给子组件开放的一组上下文能力。

---

## 11. 读 scope / DSL 代码的步骤

遇到看不懂的 scope 代码，可以按这个顺序拆：

### 第一步：看当前代码块是谁的 lambda

```kotlin
module {
    scope<ActivityA> {
        scoped { Presenter(get()) }
    }
}
```

先找外层函数定义：

```text
module 的参数类型是什么？
scope 的参数类型是什么？
scoped 的参数类型是什么？
```

如果参数类型长这样：

```kotlin
Module.() -> Unit
ScopeDSL.() -> Unit
```

说明 lambda 里有隐式 `this`。

### 第二步：判断 `this` 和 `it`

```kotlin
user.apply {
    posts.map {
        title + name
    }
}
```

这里可能同时有：

- `apply` 的 `this`：`user`
- `map` 的 `it`：某个 `post`
- 外层类的 `this`

复杂时要主动写清楚：

```kotlin
user.apply {
    posts.map { post ->
        "${this.name}: ${post.title}"
    }
}
```

### 第三步：看返回值

```kotlin
val result = user.apply { name = "Tom" }
```

`result` 是 `user`。

```kotlin
val result = user.let { it.name }
```

`result` 是 `name`。

很多 bug 就是把 `apply` 和 `let` 的返回值搞反。

### 第四步：判断这个 scope 管的是可见性还是生命周期

```kotlin
viewModelScope.launch { }
```

这是协程生命周期。

```kotlin
scope<Activity> { scoped { } }
```

这是 DI 对象生命周期。

```kotlin
Box { Modifier.align(...) }
```

这是 Compose 布局上下文。

---

## 12. Kotlin 还必须掌握哪些用法

结合当前 `kotlin` 目录已有文档，协程、Compose 协程、算法、依赖注入已经有基础内容。除此之外，Kotlin 里还很值得单独掌握这些：

| 主题 | 为什么重要 | 当前建议 |
|---|---|---|
| 空安全 | `?`、`?:`、`?.`、`!!` 是 Kotlin 日常代码核心 | 必须熟练 |
| data class / sealed class / enum | UI state、网络模型、业务状态建模常用 | 必须熟练 |
| object / companion object | 单例、静态入口、工厂方法常见 | 必须熟练 |
| 扩展函数 / 扩展属性 | Kotlin API 设计和 Android 工具函数高频使用 | 必须熟练 |
| 高阶函数和 lambda | scope functions、DSL、回调封装的基础 | 必须熟练 |
| receiver lambda | Gradle、Compose、Koin、HTML DSL 的基础 | 必须熟练 |
| 泛型和 variance | `List<out T>`、`Comparable<in T>`、Repository 抽象常见 | 建议深入 |
| inline / reified / crossinline / noinline | Koin、Retrofit、序列化、泛型工具常见 | 建议深入 |
| 委托 | `by lazy`、`by viewModels`、属性代理、接口代理 | Android 高频 |
| sealed interface + when | 表达 UI 状态、事件、结果类型很舒服 | Android 高频 |
| Result / runCatching | 轻量错误建模和异常转换常见 | 建议掌握 |
| Flow 操作符 | `map`、`flatMapLatest`、`combine`、`stateIn` | 已在协程文档中部分覆盖，可继续加强 |
| Channel / SharedFlow / StateFlow | 事件、状态、一次性消息 | 已有部分覆盖，可继续加强 |
| Kotlin Serialization | 现代 Kotlin 项目常用 JSON 方案 | 如果做网络建议掌握 |
| Java 互操作 | SAM、platform type、`@JvmStatic`、`@JvmOverloads` | Android 必备 |
| Gradle Kotlin DSL | `build.gradle.kts` 本身就是 Kotlin DSL | 工程化必备 |

如果继续补文档，推荐按这个顺序：

```text
1. Kotlin 空安全、数据建模与 sealed 状态：kotlin-空安全与数据建模详解.md
2. Kotlin 高阶函数、lambda、扩展函数和 DSL：当前文档
3. Kotlin 泛型、out/in、reified：kotlin-泛型out-in与reified详解.md
4. Kotlin 委托、by lazy、by viewModels、属性代理：kotlin-委托与by关键字详解.md
5. Kotlin 与 Java 互操作：kotlin-Java互操作详解.md
6. Gradle Kotlin DSL 入门：kotlin-GradleKotlinDSL入门.md
```

其中第 2 点已经由这篇 scope / DSL 文档覆盖了一大半。

---

## 13. 源码和排查关键词

读代码时可以优先搜索这些关键词：

```text
ScopeDSL
CoroutineScope
LifecycleCoroutineScope
viewModelScope
rememberCoroutineScope
ColumnScope
RowScope
BoxScope
@DslMarker
receiver
T.() -> Unit
apply
also
let
run
with
scoped
single
factory
```

看到一个 DSL 函数，最重要的是跳到它的函数签名，看参数是不是这种类型：

```kotlin
SomeScope.() -> Unit
```

只要看到 `XxxScope.() -> Unit`，就说明代码块里隐藏了一个 `this: XxxScope`。