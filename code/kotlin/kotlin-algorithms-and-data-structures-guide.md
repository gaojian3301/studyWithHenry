# 常见算法与数据结构掌握清单

这份文档不是为了把所有算法都堆给你，而是帮你建立“哪些是必须掌握的核心项”。
我按数据结构、基础算法、常见题型、复杂度理解来整理，并且代码示例全部用 Kotlin 展示。

## 1. 先建立掌握思路

如果你问“算法和数据结构到底哪些需要掌握”，最实用的答案不是把名字全列一遍，而是分层：

- **第一层：必须会用** —— 数组、链表、栈、队列、哈希、树、图、堆
- **第二层：必须会写** —— 二分、排序、DFS/BFS、递归、滑动窗口、双指针、回溯、动态规划
- **第三层：必须会判断** —— 什么时候该用什么结构、什么时候该用什么思路

**真正重要的不是背定义，而是：** 看见一道题时，你能快速判断“这是哈希问题、双指针问题、树遍历问题，还是 DP 问题”。

## 2. 必掌握的数据结构

### 2.1 数组 / Array / List

这是最基础、最常用的数据结构。你至少要掌握：

- 随机访问快
- 插入删除中间元素成本高
- 适合下标类问题、双指针、滑动窗口、二分查找

```kotlin
val nums = intArrayOf(1, 2, 3, 4)
println(nums[2])

val list = mutableListOf(10, 20, 30)
list.add(40)
list.removeAt(1)
```

### 2.2 链表 / Linked List

链表题不一定开发里天天写，但在面试和算法训练里很常见。重点要掌握：

- 节点结构
- 遍历
- 反转链表
- 快慢指针
- 合并两个有序链表

```kotlin
data class ListNode(var value: Int, var next: ListNode? = null)

fun reverse(head: ListNode?): ListNode? {
    var prev: ListNode? = null
    var cur = head
    while (cur != null) {
        val next = cur.next
        cur.next = prev
        prev = cur
        cur = next
    }
    return prev
}
```

### 2.3 栈 / Stack

后进先出，特别适合：

- 括号匹配
- 表达式处理
- 单调栈
- DFS 的显式写法

```kotlin
val stack = ArrayDeque<Int>()
stack.addLast(1)
stack.addLast(2)
val top = stack.removeLast()
```

### 2.4 队列 / Queue

先进先出，典型应用：

- BFS 广度优先搜索
- 任务调度
- 层序遍历

```kotlin
val queue = ArrayDeque<Int>()
queue.addLast(1)
queue.addLast(2)
val first = queue.removeFirst()
```

### 2.5 哈希表 / HashMap / HashSet

这类结构非常重要，可以说是高频第一名。你必须掌握：

- 快速查找
- 去重
- 计数
- 建立值到下标/对象的映射

```kotlin
val map = hashMapOf<Int, Int>()
map[1] = 10
map[2] = 20
println(map[1])

val set = hashSetOf<Int>()
set.add(3)
set.add(3)
println(set.contains(3))
```

### 2.6 树 / Tree

必须掌握二叉树，因为它几乎是算法面试的核心结构之一。重点包括：

- 前序、中序、后序遍历
- 层序遍历
- 递归写法
- 二叉搜索树 BST

```kotlin
data class TreeNode(
    var value: Int,
    var left: TreeNode? = null,
    var right: TreeNode? = null
)

fun inorder(root: TreeNode?) {
    if (root == null) return
    inorder(root.left)
    println(root.value)
    inorder(root.right)
}
```

### 2.7 堆 / Heap / PriorityQueue

这类结构适合处理“动态维护最值”。比如：

- Top K
- 最小值/最大值优先弹出
- 合并多个有序流

```kotlin
import java.util.PriorityQueue

val pq = PriorityQueue<Int>()
pq.offer(5)
pq.offer(2)
pq.offer(8)
println(pq.poll()) // 2
```

### 2.8 图 / Graph

图比较抽象，但必须掌握基础表示和遍历：

- 邻接表
- DFS
- BFS
- 拓扑排序（进阶）

```kotlin
val graph = hashMapOf(
    1 to listOf(2, 3),
    2 to listOf(4),
    3 to emptyList(),
    4 to emptyList()
)
```

**最低要求：** 数组、哈希、栈、队列、树，是必须非常熟的；链表、堆、图，是必须会做典型题的。

## 3. 必掌握的基础算法

### 3.1 排序

你至少要知道这些排序的思想和复杂度：

- 冒泡排序
- 选择排序
- 插入排序
- 快速排序
- 归并排序
- 堆排序（知道思路即可）

```kotlin
fun bubbleSort(nums: IntArray) {
    for (i in nums.indices) {
        for (j in 0 until nums.size - 1 - i) {
            if (nums[j] > nums[j + 1]) {
                val temp = nums[j]
                nums[j] = nums[j + 1]
                nums[j + 1] = temp
            }
        }
    }
}
```

### 3.2 二分查找

数组有序时，二分几乎是必会。重点要掌握：

- 找某个值
- 找左边界
- 找右边界

```kotlin
fun binarySearch(nums: IntArray, target: Int): Int {
    var left = 0
    var right = nums.lastIndex
    while (left <= right) {
        val mid = left + (right - left) / 2
        when {
            nums[mid] == target -> return mid
            nums[mid] < target -> left = mid + 1
            else -> right = mid - 1
        }
    }
    return -1
}
```

### 3.3 递归

递归是树、回溯、分治、DFS 的基础。你至少要理解：

- 终止条件
- 递归体
- 返回值含义

```kotlin
fun factorial(n: Int): Int {
    if (n <= 1) return 1
    return n * factorial(n - 1)
}
```

### 3.4 DFS / BFS

这是树和图的核心遍历算法。

```kotlin
fun dfs(graph: Map<Int, List<Int>>, node: Int, visited: MutableSet<Int>) {
    if (!visited.add(node)) return
    println(node)
    for (next in graph[node].orEmpty()) {
        dfs(graph, next, visited)
    }
}
```

```kotlin
fun bfs(graph: Map<Int, List<Int>>, start: Int) {
    val visited = mutableSetOf<Int>()
    val queue = ArrayDeque<Int>()
    queue.addLast(start)
    visited.add(start)

    while (queue.isNotEmpty()) {
        val cur = queue.removeFirst()
        println(cur)
        for (next in graph[cur].orEmpty()) {
            if (visited.add(next)) {
                queue.addLast(next)
            }
        }
    }
}
```

### 3.5 动态规划 DP

DP 是很多人最容易卡住的。你至少要掌握：

- 状态定义
- 状态转移方程
- 初始化
- 遍历顺序

```kotlin
fun climbStairs(n: Int): Int {
    if (n <= 2) return n
    val dp = IntArray(n + 1)
    dp[1] = 1
    dp[2] = 2
    for (i in 3..n) {
        dp[i] = dp[i - 1] + dp[i - 2]
    }
    return dp[n]
}
```

### 3.6 贪心

贪心的关键不是“每次取当前最优”，而是你要知道这个局部最优为什么能推出全局最优。

```kotlin
fun maxProfit(prices: IntArray): Int {
    var profit = 0
    for (i in 1 until prices.size) {
        if (prices[i] > prices[i - 1]) {
            profit += prices[i] - prices[i - 1]
        }
    }
    return profit
}
```

### 3.7 回溯

排列、组合、子集类问题经常用回溯。

```kotlin
fun subsets(nums: IntArray): List<List<Int>> {
    val result = mutableListOf<List<Int>>()
    val path = mutableListOf<Int>()

    fun backtrack(start: Int) {
        result.add(path.toList())
        for (i in start until nums.size) {
            path.add(nums[i])
            backtrack(i + 1)
            path.removeAt(path.lastIndex)
        }
    }

    backtrack(0)
    return result
}
```

## 4. 高频解题模式

### 4.1 双指针

适合有序数组、去重、快慢移动等场景。

```kotlin
fun twoSumSorted(nums: IntArray, target: Int): IntArray {
    var left = 0
    var right = nums.lastIndex
    while (left < right) {
        val sum = nums[left] + nums[right]
        when {
            sum == target -> return intArrayOf(left, right)
            sum < target -> left++
            else -> right--
        }
    }
    return intArrayOf(-1, -1)
}
```

### 4.2 滑动窗口

适合连续子数组、子串问题。

```kotlin
fun maxSumOfWindow(nums: IntArray, k: Int): Int {
    var sum = 0
    var ans = Int.MIN_VALUE
    for (i in nums.indices) {
        sum += nums[i]
        if (i >= k) sum -= nums[i - k]
        if (i >= k - 1) ans = maxOf(ans, sum)
    }
    return ans
}
```

### 4.3 前缀和

适合快速求区间和。

```kotlin
fun buildPrefix(nums: IntArray): IntArray {
    val prefix = IntArray(nums.size + 1)
    for (i in nums.indices) {
        prefix[i + 1] = prefix[i] + nums[i]
    }
    return prefix
}
```

### 4.4 单调栈

适合“下一个更大元素”“柱状图面积”这类题。

### 4.5 并查集

如果你学到图连通性问题，建议掌握并查集，尤其是：

- 找根节点
- 合并集合
- 判断是否连通

## 5. Kotlin 里常用写法

如果你用 Kotlin 写算法，下面这些是高频工具：

| 用途 | Kotlin 常用写法 |
| --- | --- |
| 整型数组 | `intArrayOf(...)` |
| 可变列表 | `mutableListOf<Int>()` |
| 哈希表 | `hashMapOf<K, V>()` |
| 哈希集合 | `hashSetOf<T>()` |
| 栈 / 队列 | `ArrayDeque<T>()` |
| 优先队列 | `PriorityQueue<T>()` |
| 遍历区间 | `for (i in 0 until n)` |
| 倒序遍历 | `for (i in nums.lastIndex downTo 0)` |

**一个经验：** 算法题里不要过度追求 Kotlin 花哨写法。优先写清楚、可调试、边界明确的代码。

## 6. 怎么练才有效

最有效的练法不是乱刷，而是按专题练。

先练数据结构对应题

- 数组 / 哈希
- 链表
- 二叉树
- 堆 / 优先队列

再练套路题

- 双指针
- 滑动窗口
- DFS / BFS
- 回溯
- DP

### 6.1 刷题时要问自己 3 个问题

- 这题最适合的数据结构是什么？
- 这题本质是哪个模式？
- 时间复杂度和空间复杂度是多少？

**建议顺序：** 数组/哈希 → 双指针/滑动窗口 → 链表 → 树 → DFS/BFS → 回溯 → DP → 图。

## 7. 掌握清单

| 类别 | 需要掌握的内容 | 掌握程度建议 |
| --- | --- | --- |
| 数组 | 遍历、插入删除特点、双指针、滑动窗口、二分 | 必须很熟 |
| 哈希 | 查找、计数、去重、映射 | 必须很熟 |
| 链表 | 反转、合并、快慢指针 | 必须会典型题 |
| 栈 / 队列 | 括号匹配、BFS、单调栈 | 必须会用 |
| 树 | 遍历、递归、层序遍历、BST | 必须很熟 |
| 堆 | Top K、最值维护 | 必须会典型题 |
| 图 | 邻接表、DFS、BFS | 至少掌握基础 |
| 排序 | 快排、归并、基础排序思想 | 必须知道原理 |
| DP | 爬楼梯、背包、子序列类 | 必须能入门 |
| 回溯 | 排列、组合、子集 | 必须会模板 |

**如果你只想先抓最核心的 8 项：**

- 数组
- 哈希表
- 链表
- 栈和队列
- 二叉树
- 二分查找
- DFS / BFS
- 动态规划

先把这几类吃透，再往堆、图、并查集、单调栈、拓扑排序扩展，节奏会更稳。

常见算法与数据结构掌握清单 · Kotlin 示例版
