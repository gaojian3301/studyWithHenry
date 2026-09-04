# 05 App 架构：MVVM、MVI、Repository 与状态流

> App 架构不是为了套名词，而是为了让页面状态、业务规则、数据来源、错误处理和测试边界变清楚。本文用代码串起 UI、ViewModel、UseCase、Repository、DataSource。

---

## 1. 一个推荐的基础分层

```text
ui
  -> ViewModel
      -> UseCase
          -> Repository interface
              -> Repository implementation
                  -> RemoteDataSource / LocalDataSource
```

小项目可以少一层，复杂项目需要边界更明确。关键不是层数，而是依赖方向稳定：UI 不直接知道 Retrofit、Room、SharedPreferences。

---

## 2. MVVM 的基本写法

```kotlin
data class FeedUiState(
    val refreshing: Boolean = false,
    val items: List<FeedItem> = emptyList(),
    val error: String? = null,
)

class FeedViewModel(
    private val observeFeed: ObserveFeedUseCase,
    private val refreshFeed: RefreshFeedUseCase,
) : ViewModel() {
    val uiState: StateFlow<FeedUiState> = observeFeed()
        .map { items -> FeedUiState(items = items) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedUiState(refreshing = true))

    fun refresh() {
        viewModelScope.launch {
            refreshFeed()
        }
    }
}
```

UI 只渲染状态：

```kotlin
@Composable
fun FeedScreen(state: FeedUiState, onRefresh: () -> Unit) {
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh) {
        LazyColumn {
            items(state.items, key = { it.id }) { item -> FeedRow(item) }
        }
    }
}
```

---

## 3. MVI 适合复杂交互

MVI 把用户行为也建模出来：

```kotlin
sealed interface SearchIntent {
    data class QueryChanged(val query: String) : SearchIntent
    data object Retry : SearchIntent
}

data class SearchState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<ResultItem> = emptyList(),
)
```

ViewModel：

```kotlin
class SearchViewModel(
    private val search: SearchUseCase,
) : ViewModel() {
    private val intents = MutableSharedFlow<SearchIntent>()

    val state: StateFlow<SearchState> = intents
        .scan(SearchState()) { state, intent -> reduce(state, intent) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchState())

    fun dispatch(intent: SearchIntent) {
        viewModelScope.launch { intents.emit(intent) }
    }

    private suspend fun reduce(state: SearchState, intent: SearchIntent): SearchState {
        return when (intent) {
            is SearchIntent.QueryChanged -> state.copy(query = intent.query)
            SearchIntent.Retry -> state.copy(loading = true)
        }
    }
}
```

真实项目里 MVI 要避免过度抽象。表单、搜索、播放器、复杂状态机适合；简单页面用 MVVM 更直接。

---

## 4. Repository 应该屏蔽数据来源

接口放 domain 或 data 边界：

```kotlin
interface UserRepository {
    fun observeUser(id: String): Flow<User>
    suspend fun refreshUser(id: String)
}
```

实现：

```kotlin
class RealUserRepository(
    private val api: UserApi,
    private val dao: UserDao,
) : UserRepository {
    override fun observeUser(id: String): Flow<User> {
        return dao.observeById(id).map { it.toDomain() }
    }

    override suspend fun refreshUser(id: String) {
        val remote = api.getUser(id)
        dao.upsert(remote.toEntity())
    }
}
```

UI 层不关心数据来自网络还是数据库。

---

## 5. UseCase 什么时候需要

UseCase 适合承载跨 Repository 的业务规则：

```kotlin
class SubmitOrderUseCase(
    private val cartRepository: CartRepository,
    private val orderRepository: OrderRepository,
    private val userRepository: UserRepository,
) {
    suspend operator fun invoke(): OrderId {
        val user = userRepository.requireLoginUser()
        val cart = cartRepository.currentCart()
        require(cart.items.isNotEmpty())
        return orderRepository.submit(user.id, cart)
    }
}
```

如果 UseCase 只是简单转发 Repository，暂时可以不建。

---

## 6. Result 和错误建模

不要把所有错误都变成字符串。

```kotlin
sealed interface AppError {
    data object NetworkUnavailable : AppError
    data object Unauthorized : AppError
    data class Server(val code: Int, val message: String?) : AppError
    data class Unknown(val cause: Throwable) : AppError
}

sealed interface LoadResult<out T> {
    data class Success<T>(val value: T) : LoadResult<T>
    data class Failure(val error: AppError) : LoadResult<Nothing>
}
```

统一映射：

```kotlin
suspend fun <T> safeApiCall(block: suspend () -> T): LoadResult<T> {
    return try {
        LoadResult.Success(block())
    } catch (error: IOException) {
        LoadResult.Failure(AppError.NetworkUnavailable)
    } catch (error: HttpException) {
        LoadResult.Failure(AppError.Server(error.code(), error.message()))
    }
}
```

---

## 7. 依赖注入边界

Hilt 示例：

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    abstract fun bindUserRepository(impl: RealUserRepository): UserRepository
}

@HiltViewModel
class UserViewModel @Inject constructor(
    private val repository: UserRepository,
) : ViewModel()
```

源码阅读点：

- Hilt 生成代码通常在 `build/generated/hilt/`。
- ViewModel 注入和 `HiltViewModelFactory` 相关。
- AndroidX `ViewModelProvider` 负责缓存和复用 ViewModel。

---

## 8. 架构常见问题

| 问题 | 根因 | 建议 |
|---|---|---|
| ViewModel 很胖 | 业务规则都堆 UI 层 | 拆 UseCase 或 domain service |
| Repository 很乱 | 网络、数据库、映射、缓存策略混在一起 | 拆 DataSource 和 Mapper |
| UI 状态不一致 | 多个 LiveData/Flow 分散 | 统一 `UiState` |
| 错误处理重复 | 每个页面 catch 一遍 | 建统一错误模型 |
| 单测困难 | 依赖具体实现 | 依赖接口，构造注入 |

---

## 9. 源码查看建议

| 主题 | 源码入口 |
|---|---|
| ViewModel 缓存 | `ViewModelStore`、`ViewModelProvider` |
| SavedStateHandle | `SavedStateHandleController` |
| Lifecycle 收集 | `repeatOnLifecycle` |
| Hilt ViewModel | `HiltViewModelFactory`、生成代码 |
| Room Flow | `InvalidationTracker` |

架构源码阅读的重点不是 AndroidX 怎么实现所有细节，而是看清“对象由谁创建、谁持有、什么时候释放”。
