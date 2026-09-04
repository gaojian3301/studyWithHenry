# 06 Jetpack 核心库：Lifecycle、ViewModel、Room、DataStore、WorkManager、Paging、Hilt

> Jetpack 是现代 Android App 的基础设施。这篇不逐个背 API，而是抓住每个库解决什么问题、典型代码怎么写、源码从哪里切进去。

---

## 1. Lifecycle

Lifecycle 让组件状态变成可观察对象。

```kotlin
class CameraObserver(
    private val camera: CameraController,
) : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) {
        camera.open()
    }

    override fun onStop(owner: LifecycleOwner) {
        camera.close()
    }
}
```

注册：

```kotlin
lifecycle.addObserver(CameraObserver(camera))
```

源码入口：

- `LifecycleRegistry`
- `DefaultLifecycleObserverAdapter`
- `ComponentActivity`

---

## 2. ViewModel

ViewModel 用来保存页面业务状态，跨配置变更存活。

```kotlin
class DetailViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: ArticleRepository,
) : ViewModel() {
    private val id: String = checkNotNull(savedStateHandle["id"])

    val article = repository.observeArticle(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
```

源码入口：

- `ViewModelStore`
- `ViewModelProvider`
- `SavedStateHandle`
- `ViewModelKt.getViewModelScope`

---

## 3. Room

Room 是 SQLite 抽象层。

```kotlin
@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    val name: String,
)

@Dao
interface UserDao {
    @Query("SELECT * FROM users WHERE id = :id")
    fun observeUser(id: String): Flow<UserEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(user: UserEntity)
}

@Database(entities = [UserEntity::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
}
```

重点：

- DAO 生成代码在 build 目录。
- Flow 查询依赖 `InvalidationTracker`。
- 数据库迁移必须可测试。

源码入口：

- `RoomDatabase`
- `InvalidationTracker`
- 生成的 `*_Impl` 类

---

## 4. DataStore

DataStore 替代 SharedPreferences，支持 Flow 和事务式更新。

```kotlin
val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {
    private val darkModeKey = booleanPreferencesKey("dark_mode")

    val darkMode: Flow<Boolean> = dataStore.data
        .map { preferences -> preferences[darkModeKey] ?: false }

    suspend fun setDarkMode(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[darkModeKey] = enabled }
    }
}
```

源码入口：

- `SingleProcessDataStore`
- `DataStoreImpl`
- `PreferencesFactory`

---

## 5. WorkManager

WorkManager 适合可延迟、可靠执行的后台任务。

```kotlin
class SyncWorker(
    context: Context,
    params: WorkerParameters,
    private val repository: SyncRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return runCatching { repository.sync() }
            .fold(
                onSuccess = { Result.success() },
                onFailure = { Result.retry() },
            )
    }
}
```

入队：

```kotlin
val request = OneTimeWorkRequestBuilder<SyncWorker>()
    .setConstraints(
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build(),
    )
    .build()

WorkManager.getInstance(context).enqueueUniqueWork(
    "sync",
    ExistingWorkPolicy.KEEP,
    request,
)
```

源码入口：

- `WorkManagerImpl`
- `Processor`
- `GreedyScheduler`
- `SystemJobScheduler`

---

## 6. Paging 3

Paging 适合分页列表。

```kotlin
class ArticlePagingSource(
    private val api: ArticleApi,
) : PagingSource<Int, Article>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Article> {
        val page = params.key ?: 1
        return runCatching { api.getArticles(page, params.loadSize) }
            .fold(
                onSuccess = { data ->
                    LoadResult.Page(
                        data = data.items,
                        prevKey = if (page == 1) null else page - 1,
                        nextKey = if (data.items.isEmpty()) null else page + 1,
                    )
                },
                onFailure = { LoadResult.Error(it) },
            )
    }

    override fun getRefreshKey(state: PagingState<Int, Article>): Int? = null
}
```

Compose：

```kotlin
val articles = viewModel.articles.collectAsLazyPagingItems()
LazyColumn {
    items(articles.itemCount) { index ->
        articles[index]?.let { ArticleRow(it) }
    }
}
```

源码入口：

- `Pager`
- `PagingSource`
- `PageFetcher`
- `AsyncPagingDataDiffer`

---

## 7. Hilt

Hilt 解决 Android 对象创建和依赖注入。

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder().build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://api.example.com/")
            .client(client)
            .addConverterFactory(Json.asConverterFactory("application/json".toMediaType()))
            .build()
    }
}
```

源码查看建议：

- 看生成代码，不要只看注解。
- 关注 Component 层级：Singleton、ActivityRetained、ViewModel、Activity、Fragment。
- 排查重复绑定、作用域错配、循环依赖。

---

## 8. Jetpack 常见组合

```text
Compose UI
  -> collectAsStateWithLifecycle
      -> ViewModel StateFlow
          -> Repository
              -> Room Flow + Retrofit suspend
                  -> WorkManager 后台同步
```

这个组合是现在很多 App 的主线。源码阅读时按链路追，比逐个库孤立阅读更有效。
