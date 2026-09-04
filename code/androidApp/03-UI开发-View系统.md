# 03 UI 开发：View 系统、事件分发与绘制源码

> Compose 很重要，但 View 系统仍然是 Android UI 的地基。Dialog、PopupWindow、WebView、RecyclerView、输入法、WindowInsets、触摸分发、过渡动画、很多第三方 SDK 都绕不开 View。

---

## 1. View 系统的核心对象

```text
Activity
  -> PhoneWindow
      -> DecorView
          -> ViewGroup
              -> TextView / ImageView / RecyclerView / custom View
```

App 调用：

```kotlin
setContentView(R.layout.activity_main)
```

大致链路：

```text
Activity.setContentView
  -> PhoneWindow.setContentView
      -> LayoutInflater.inflate
          -> DecorView.addView
```

源码入口：

- `Activity#setContentView`
- `PhoneWindow#setContentView`
- `LayoutInflater#inflate`
- `ViewRootImpl#setView`

---

## 2. measure / layout / draw

View 绘制三阶段：

```text
measure  决定多大
layout   决定放哪
draw     画到 Canvas
```

自定义 View 示例：

```kotlin
class BatteryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(40, 160, 90)
    }

    private var level: Float = 0.6f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = 160.dp
        val desiredHeight = 64.dp
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val body = RectF(0f, 0f, width * level, height.toFloat())
        canvas.drawRoundRect(body, 12f, 12f, paint)
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).roundToInt()
}
```

触发重绘：

```kotlin
fun setLevel(value: Float) {
    level = value.coerceIn(0f, 1f)
    invalidate()       // 只需要 draw
    // requestLayout() // 尺寸变化才需要
}
```

---

## 3. ViewRootImpl 和 Choreographer

View 真正接入 Window 后，会有 `ViewRootImpl`：

```text
WindowManager.addView
  -> WindowManagerGlobal.addView
      -> ViewRootImpl.setView
          -> requestLayout
              -> scheduleTraversals
                  -> Choreographer.postCallback
```

重点源码：

- `ViewRootImpl#scheduleTraversals`
- `ViewRootImpl#doTraversal`
- `ViewRootImpl#performTraversals`
- `Choreographer#doFrame`

理解这条链路，就能解释为什么 UI 更新不是立刻绘制，而是等下一帧 VSYNC。

---

## 4. 事件分发

触摸事件基本链路：

```text
Activity.dispatchTouchEvent
  -> Window.superDispatchTouchEvent
      -> DecorView.dispatchTouchEvent
          -> ViewGroup.dispatchTouchEvent
              -> child.dispatchTouchEvent
                  -> View.onTouchEvent
```

常用拦截：

```kotlin
class NestedCardLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        return event.actionMasked == MotionEvent.ACTION_MOVE && shouldDrag(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> true
            MotionEvent.ACTION_MOVE -> {
                dragTo(event.x, event.y)
                true
            }
            else -> super.onTouchEvent(event)
        }
    }
}
```

注意：`ACTION_DOWN` 返回 false，后续 MOVE/UP 就收不到。

---

## 5. RecyclerView 要看什么

RecyclerView 不是简单列表，它包含：

- Adapter：数据到 ViewHolder。
- LayoutManager：布局算法。
- ItemAnimator：增删改动画。
- Recycler：复用池。
- DiffUtil/ListAdapter：差量刷新。

推荐写法：

```kotlin
class UserAdapter : ListAdapter<User, UserViewHolder>(UserDiff) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserViewHolder {
        val binding = ItemUserBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return UserViewHolder(binding)
    }

    override fun onBindViewHolder(holder: UserViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}

object UserDiff : DiffUtil.ItemCallback<User>() {
    override fun areItemsTheSame(oldItem: User, newItem: User): Boolean = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: User, newItem: User): Boolean = oldItem == newItem
}
```

源码入口：

- `RecyclerView#dispatchLayout`
- `RecyclerView.Recycler#getViewForPosition`
- `LinearLayoutManager#layoutChunk`
- `DiffUtil#calculateDiff`

---

## 6. WindowInsets 和软键盘

现代 Android 不建议硬编码状态栏/导航栏高度。View 系统可以这样处理：

```kotlin
ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
    val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
    val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
    view.updatePadding(
        left = bars.left,
        top = bars.top,
        right = bars.right,
        bottom = max(bars.bottom, ime.bottom),
    )
    insets
}
```

排查键盘遮挡时看：

- `windowSoftInputMode`
- decorFitsSystemWindows
- Insets 分发链路
- 根布局是否消费了 Insets

---

## 7. 动画和性能

优先使用属性动画：

```kotlin
view.animate()
    .translationY(0f)
    .alpha(1f)
    .setDuration(220)
    .start()
```

性能注意：

- 避免在 `onDraw()` 分配对象。
- 避免过深布局层级。
- 列表里避免同步图片解码。
- 用 `Payload` 局部刷新列表。
- 大量动画要注意硬件层和内存。

---

## 8. 常见问题和源码切入点

| 问题 | 看 App 代码 | 看源码 |
|---|---|---|
| 自定义 View 不显示 | `onMeasure`、宽高、父布局约束 | `View#measure`、`ViewGroup#measureChild` |
| 点击没反应 | `dispatchTouchEvent` 返回值、遮挡 | `ViewGroup#dispatchTouchEvent` |
| 列表闪烁 | Diff 判断、stable id、动画 | `RecyclerView#dispatchLayout` |
| 键盘遮挡 | Insets、softInputMode | `ViewRootImpl` Insets 处理 |
| 掉帧 | 主线程、布局层级、绘制耗时 | `ViewRootImpl#performTraversals` |

---

## 9. 建议阅读顺序

1. `Activity#setContentView`
2. `PhoneWindow#setContentView`
3. `LayoutInflater#inflate`
4. `WindowManagerGlobal#addView`
5. `ViewRootImpl#setView`
6. `ViewRootImpl#performTraversals`
7. `ViewGroup#dispatchTouchEvent`
8. `RecyclerView#dispatchLayout`

View 系统的关键不是记 API，而是能从“App 调了一个 UI 方法”追到“什么时候被测量、布局、绘制、接收输入”。
