# Kotlin 中的 inject 详解

这是一份适合直接在浏览器打开的 HTML 版文档，重点讲清楚 Kotlin 里常见的 `inject`、`@Inject`、`by inject()`、Hilt / Dagger、Koin，以及 Android 项目里的实际使用方式。

# Kotlin 中的 inject 详解

## 1. 先说结论

在 Kotlin 里，`inject` 不是 Kotlin 语言关键字。

你平时看到的 `inject`，几乎都来自“依赖注入（Dependency Injection，DI）”框架，常见有两类：

1. `@Inject`

- 常见于 Dagger / Hilt
- 表示“这个对象可以由依赖注入框架来创建或注入”

1. `by inject()`

- 常见于 Koin
- 表示“从依赖容器里取一个对象”，并且通常是懒加载

所以，`inject` 本质上不是 Kotlin 语法点，而是框架提供的一种“注入依赖”的能力。

---

## 2. 什么叫依赖注入

先看一个普通类：

```kotlin
class UserRepository

class UserService(
    private val repository: UserRepository
)
```

`UserService` 依赖 `UserRepository`。

如果不用依赖注入，最直接的写法可能是：

```kotlin
class UserService {
    private val repository = UserRepository()
}
```

这叫“自己创建依赖”。问题是：

1. 依赖被写死了
2. 测试时不好替换成假实现
3. 创建逻辑分散，到处 `new`（Kotlin 里就是构造）
4. 依赖关系越来越复杂时很难维护

依赖注入的思路是：

- 类只声明“我需要什么”
- 至于“这个东西怎么创建”，交给外部容器或框架

比如：

```kotlin
class UserService(
    private val repository: UserRepository
)
```

这里 `UserService` 只关心：

“我需要一个 `UserRepository`。”

它不关心这个对象是谁创建的、什么时候创建的、是不是单例。

这就是依赖注入的核心思想。

---

## 3. 为什么项目里要用 inject

### 3.1 解耦

业务类不再自己创建依赖，而是只声明依赖。

这样类和类之间耦合更低。

### 3.2 更容易测试

例如：

```kotlin
interface UserRepository {
    fun loadName(): String
}

class RealUserRepository : UserRepository {
    override fun loadName(): String = "real"
}

class FakeUserRepository : UserRepository {
    override fun loadName(): String = "fake"
}

class UserService(
    private val repository: UserRepository
) {
    fun getName(): String = repository.loadName()
}
```

测试时可以直接传入假的实现：

```kotlin
val service = UserService(FakeUserRepository())
```

### 3.3 更适合大型项目

当对象创建链很长时，例如：

- ViewModel 依赖 UseCase
- UseCase 依赖 Repository
- Repository 依赖 ApiService / Dao / Logger

如果都手动创建，会非常乱。

依赖注入框架可以统一管理这些对象关系。

---

## 4. Kotlin 里常见的 inject 形式

### 4.1 `@Inject`

常见于：

- Dagger
- Hilt

#### 示例

```kotlin
import javax.inject.Inject

class UserRepository @Inject constructor()

class UserService @Inject constructor(
    private val repository: UserRepository
)
```

这段代码的意思：

- `UserRepository` 的构造函数可以被 DI 框架调用
- `UserService` 的构造函数也可以被 DI 框架调用
- 当框架要创建 `UserService` 时，它看到需要 `UserRepository`
- 如果 `UserRepository` 也可构造，就会自动创建并传进去

可以理解成：

`@Inject constructor(...)` 表示“这个类支持构造注入”。

### 4.2 `by inject()`

常见于：

- Koin

#### 示例

```kotlin
class MyActivity : AppCompatActivity() {
    private val repository: UserRepository by inject()
}
```

含义是：

- `repository` 这个属性不用你自己初始化
- 当第一次访问它时
- Koin 会从容器里找到 `UserRepository` 的实例并返回

这是 Koin 提供的能力，不是 Kotlin 原生的 `inject`。

Kotlin 原生只提供：

- `by`：委托语法
- 属性委托机制

Koin 只是借用了这个语法糖。

---

## 5. `@Inject` 的详细理解

### 5.1 构造注入

这是最推荐的写法。

```kotlin
class UserRepository @Inject constructor()

class UserService @Inject constructor(
    private val repository: UserRepository
)
```

优点：

1. 依赖关系最清楚
2. 对象不可缺少，创建时必须传入
3. 最容易做单元测试
4. 类初始化后状态完整

这是现代 Android / Kotlin 项目里最推荐的 DI 写法。

### 5.2 字段注入

```kotlin
class MyActivity : AppCompatActivity() {

    @Inject
    lateinit var repository: UserRepository
}
```

这表示：

- `repository` 不是你自己赋值
- 框架会在合适时机帮你注入

问题是：

1. 依赖不如构造函数直观
2. 如果注入时机不对，可能访问未初始化属性
3. 测试时不如构造注入自然

所以一般建议：

- 普通业务类优先构造注入
- Android 组件（Activity / Fragment）由于系统创建限制，字段注入是常见补充方案

### 5.3 参数也可以被注入

例如 Hilt 里的 ViewModel：

```kotlin
@HiltViewModel
class UserViewModel @Inject constructor(
    private val repository: UserRepository
) : ViewModel()
```

这里不是你手动 `new UserViewModel(...)`。

而是 Hilt 帮你创建 ViewModel，并把依赖传进去。

---

## 6. 有些类为什么不能只靠 `@Inject constructor`

并不是所有对象都能直接写：

```kotlin
class Xxx @Inject constructor()
```

比如下面这种第三方类：

```kotlin
Retrofit.Builder()
    .baseUrl("https://example.com")
    .build()
```

或者：

- Room Database
- Retrofit
- OkHttpClient
- SharedPreferences
- 第三方 SDK 对象

这些对象通常：

1. 不是你自己写的类
2. 构造方式复杂
3. 需要配置参数

这时就需要专门告诉 DI 框架“怎么提供它”，也就是：

- `@Provides`
- `@Binds`

---

## 7. `@Provides` 是什么

以 Hilt / Dagger 为例：

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    fun provideUserRepository(): UserRepository {
        return UserRepository()
    }
}
```

意思是：

“当有人需要 `UserRepository` 时，请调用这个方法提供实例。”

如果对象构造很复杂：

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder().build()
    }

    @Provides
    fun provideRetrofit(client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://example.com")
            .client(client)
            .build()
    }
}
```

这里也体现了 DI：

- `provideRetrofit` 依赖 `OkHttpClient`
- 框架会先拿到 `OkHttpClient`
- 再调用 `provideRetrofit`

---

## 8. `@Binds` 是什么

当你依赖的是接口，但真正提供的是实现类时，经常用它。

```kotlin
interface UserRepository {
    fun loadName(): String
}

class UserRepositoryImpl @Inject constructor() : UserRepository {
    override fun loadName(): String = "Tom"
}
```

如果别的类依赖接口：

```kotlin
class UserService @Inject constructor(
    private val repository: UserRepository
)
```

框架这时会问：

“`UserRepository` 是接口，我该给哪个实现？”

这时需要绑定：

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindUserRepository(
        impl: UserRepositoryImpl
    ): UserRepository
}
```

意思是：

“凡是需要 `UserRepository`，就给 `UserRepositoryImpl`。”

### `@Binds` 和 `@Provides` 的区别

- `@Binds`
- 适合“接口 -> 实现类”映射
- 更简洁
- 通常要求实现类本身可被注入

- `@Provides`
- 适合创建逻辑复杂的对象
- 可以写任意构建代码

---

## 9. `@Singleton` 等作用域是什么

依赖注入不仅管“怎么创建”，还常管“创建几次”。

例如：

```kotlin
@Singleton
class UserRepository @Inject constructor()
```

含义通常是：

- 在指定作用域里只创建一个实例
- 后续再需要时复用同一个对象

Android/Hilt 常见作用域：

- `@Singleton`：应用级别
- `@ActivityScoped`
- `@FragmentScoped`
- `@ViewModelScoped`

作用域的意义：

1. 避免重复创建重对象
2. 保持共享状态
3. 明确对象生命周期

但也要注意：

- 不要什么都设成单例
- 有状态对象如果生命周期太长，容易出 bug

---

## 10. Koin 里的 `inject()` 到底是什么

Koin 风格和 Dagger/Hilt 不一样。

### 10.1 先定义模块

```kotlin
val appModule = module {
    single { UserRepository() }
    single { UserService(get()) }
}
```

含义：

- `single { UserRepository() }`：容器里放一个单例 `UserRepository`
- `single { UserService(get()) }`：创建 `UserService` 时，从容器中取一个依赖传进去

这里的 `get()` 就是“向容器要一个依赖”。

### 10.2 在类中使用 `by inject()`

```kotlin
class MyActivity : AppCompatActivity(), KoinComponent {
    private val userService: UserService by inject()
}
```

含义：

- `userService` 是委托属性
- 第一次访问时，Koin 帮你从容器里找 `UserService`

这本质上类似：

```kotlin
private val userService: UserService
    get() = getKoin().get()
```

只是 `by inject()` 写法更优雅。

### 10.3 `inject()` 和 `get()` 的区别

Koin 里常见两个写法：

```kotlin
val service: UserService by inject()
```

和：

```kotlin
val service: UserService = get()
```

通常可以这样理解：

- `by inject()`：懒加载，访问时再取
- `get()`：当前位置立即取值

所以在属性声明时，`by inject()` 很常见。

---

## 11. Kotlin 语法层面，`by inject()` 为什么成立

这和 Kotlin 的“属性委托”有关。

### 示例

```kotlin
val name by lazy {
    "hello"
}
```

这里的 `lazy {}` 返回一个委托对象。

Kotlin 在访问 `name` 时，会转而调用委托对象的方法。

Koin 的 `inject()` 原理类似：

- `inject()` 返回一个委托对象
- 这个委托对象知道如何从 Koin 容器中取实例
- 当属性被访问时，调用对应逻辑返回对象

所以：

- `by` 是 Kotlin 语法
- `inject()` 是框架实现

这两个配合起来，才有了 `by inject()`。

---

## 12. Hilt / Dagger 和 Koin 的风格差异

### 12.1 Hilt / Dagger

特点：

1. 编译期生成代码
2. 类型校验更严格
3. 大型 Android 项目里很常见
4. 性能通常更稳定
5. 学习成本相对更高

典型写法：

```kotlin
class Repo @Inject constructor()

@HiltViewModel
class MyViewModel @Inject constructor(
    private val repo: Repo
) : ViewModel()
```

### 12.2 Koin

特点：

1. 写法更接近 Kotlin 风格
2. 上手快
3. 配置更直观
4. 运行时解析依赖
5. 大项目里规范需要更自觉

典型写法：

```kotlin
val appModule = module {
    single { Repo() }
    factory { MyUseCase(get()) }
}

class MyActivity : AppCompatActivity(), KoinComponent {
    private val useCase: MyUseCase by inject()
}
```

---

## 13. Android 里 inject 的典型使用场景

### 13.1 Repository 注入到 ViewModel

```kotlin
@HiltViewModel
class UserViewModel @Inject constructor(
    private val repository: UserRepository
) : ViewModel()
```

这是最常见模式。

### 13.2 网络层对象注入

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder().build()
    }
}
```

### 13.3 数据库对象注入

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "app.db"
        ).build()
    }
}
```

---

## 14. 最佳实践

### 14.1 优先构造注入

推荐：

```kotlin
class UserService @Inject constructor(
    private val repository: UserRepository
)
```

不太推荐一上来就字段注入：

```kotlin
@Inject
lateinit var repository: UserRepository
```

原因：

- 构造注入更清楚
- 更利于不可变设计
- 更好测试

### 14.2 面向接口依赖，而不是具体实现

```kotlin
interface UserRepository
class UserRepositoryImpl @Inject constructor() : UserRepository
```

业务层依赖接口：

```kotlin
class UserService @Inject constructor(
    private val repository: UserRepository
)
```

这样更容易替换实现。

### 14.3 把创建逻辑放到 Module，而不是业务代码里

例如 Retrofit、Room、OkHttp 这种基础设施对象，适合集中放在 Module。

不要在 Repository 里直接自己创建：

```kotlin
class UserRepository {
    private val retrofit = Retrofit.Builder() ...
}
```

这种会让类职责变乱。

### 14.4 谨慎使用单例

单例虽然方便，但不要把所有对象都做成单例。

适合单例的：

- Retrofit
- OkHttpClient
- Database
- 配置类

不一定适合单例的：

- 带页面状态的对象
- 带临时业务状态的对象

---

## 15. 常见误区

### 15.1 误以为 `inject` 是 Kotlin 原生关键字

不是。

Kotlin 原生并没有 `inject` 关键字。

它只是被很多框架广泛使用，所以看起来像语言自带。

### 15.2 误以为 `@Inject` 就等于“自动一切都能注入”

不是。

只有在 DI 容器知道怎么创建对象时才行。

例如：

- 你的类有 `@Inject constructor`
- 或者有 `@Provides`
- 或者有 `@Binds`

否则框架仍然不知道怎么构造它。

### 15.3 把 Android 系统组件当成普通类处理

像 Activity / Fragment / Service 通常不是你手动创建的，系统会创建。

所以它们和普通 Kotlin 类不一样，注入方式也会有框架限制。

例如在 Hilt 里，要先：

```kotlin
@AndroidEntryPoint
class MainActivity : AppCompatActivity()
```

否则成员注入通常不会生效。

### 15.4 过度依赖字段注入

字段注入看起来省事，但时间长了容易让依赖隐藏起来。

一个类需要什么，最好在构造函数里一眼就能看出来。

---

## 16. 一个完整小例子：Hilt 风格

### Repository

```kotlin
class UserRepository @Inject constructor() {
    fun getUserName(): String = "Alice"
}
```

### UseCase

```kotlin
class GetUserNameUseCase @Inject constructor(
    private val repository: UserRepository
) {
    operator fun invoke(): String = repository.getUserName()
}
```

### ViewModel

```kotlin
@HiltViewModel
class UserViewModel @Inject constructor(
    private val getUserNameUseCase: GetUserNameUseCase
) : ViewModel() {

    fun loadName(): String {
        return getUserNameUseCase()
    }
}
```

### 页面中使用

```kotlin
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val viewModel: UserViewModel by viewModels()
}
```

依赖链是：

- MainActivity 使用 UserViewModel
- UserViewModel 依赖 GetUserNameUseCase
- GetUserNameUseCase 依赖 UserRepository
- Hilt 自动把这条链串起来

---

## 17. 一个完整小例子：Koin 风格

### 定义模块

```kotlin
val appModule = module {
    single { UserRepository() }
    factory { GetUserNameUseCase(get()) }
    viewModel { UserViewModel(get()) }
}
```

### 类定义

```kotlin
class UserRepository {
    fun getUserName(): String = "Alice"
}

class GetUserNameUseCase(
    private val repository: UserRepository
) {
    operator fun invoke(): String = repository.getUserName()
}

class UserViewModel(
    private val getUserNameUseCase: GetUserNameUseCase
) : ViewModel()
```

### 页面中使用

```kotlin
class MainActivity : AppCompatActivity() {
    private val viewModel: UserViewModel by viewModel()
}
```

这里：

- `single` 表示单例
- `factory` 表示每次要都新建
- `get()` 表示从容器中取依赖
- `by viewModel()` / `by inject()` 是 Koin 提供的委托注入写法

---

## 18. 初学者可以怎么记

你可以用一句话记住：

`inject` = “我不自己创建对象，而是让框架给我对象。”

然后再分两种：

### 看到 `@Inject`

理解成：

“这个类/构造函数/成员支持被注入。”

### 看到 `by inject()`

理解成：

“从容器里取一个对象给当前属性。”

---

## 19. 一个比较标准的回答模板

如果别人问：“Kotlin 中 inject 是什么？”

你可以这样回答：

`inject` 不是 Kotlin 关键字，通常指依赖注入框架提供的注入能力。

在 Dagger/Hilt 中，常见形式是 `@Inject`，用于构造注入或成员注入；

在 Koin 中，常见形式是 `by inject()`，本质上是 Kotlin 属性委托，用于从依赖容器中懒加载对象。

依赖注入的目的主要是解耦、提升可测试性和统一对象创建管理。

---

## 20. 最后总结

Kotlin 里的 `inject`，核心要点就 4 句：

1. `inject` 不是 Kotlin 原生关键字
2. 它通常来自依赖注入框架
3. `@Inject` 常见于 Hilt / Dagger
4. `by inject()` 常见于 Koin，本质依赖 Kotlin 的委托属性机制

如果你后面是想学 Android 里的实际开发，最值得继续深挖的是这几个主题：

1. Hilt 中 `@Inject`、`@Provides`、`@Binds` 的配合
2. Koin 的 `module`、`single`、`factory`、`by inject()`
3. ViewModel、Repository、Retrofit、Room 在 DI 里的标准组织方式
4. 构造注入、字段注入、作用域的区别

Kotlin inject 详解 · HTML 版本
