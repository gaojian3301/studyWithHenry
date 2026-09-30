# Java 核心机制复习详解 — 从泛型擦除到动态代理与并发模型

> 定位：**这不是入门教材，是一份"老手回检清单"。**  
> 如果你写了十年 Java、最近几年转 Kotlin，那么你的 Java 记忆不是"没学"，而是"沉在底下、需要钩子捞上来"。所以本文每一节都&#x4ECE;**"你现在可能卡住的那个问题"**&#x5F00;始，而不是从定义开始。
>
> 阅读方式：
>
> - 想快速查漏 → 直接看每节的「回忆钩子」，30 秒判断自己是否会。
> - 想补细节 → 看「关键语义」和「代码」。
> - 想知道现在怎么写 → 看「Kotlin 对照」。
> - 想知道会不会踩版本坑 → 看「版本差异」。

---

## 目录

- [第 0 章 坐标系：这份文档覆盖什么](#第-0-章-坐标系这份文档覆盖什么)
- [第 1 章 泛型与类型擦除（你点名要复习的）](#第-1-章-泛型与类型擦除你点名要复习的)
- [第 2 章 反射：运行期看穿自己](#第-2-章-反射运行期看穿自己)
- [第 3 章 动态代理：一个类装成另一个类（你点名要复习的）](#第-3-章-动态代理一个类装成另一个类你点名要复习的)
- [第 4 章 注解与运行时处理](#第-4-章-注解与运行时处理)
- [第 5 章 类加载与双亲委派](#第-5-章-类加载与双亲委派)
- [第 6 章 equals / hashCode / Comparable 契约](#第-6-章-equals--hashcode--comparable-契约)
- [第 7 章 集合框架里真正容易记错的细节](#第-7-章-集合框架里真正容易记错的细节)
- [第 8 章 String、不可变与常量池](#第-8-章-string不可变与常量池)
- [第 9 章 内部类、闭包与 Lambda](#第-9-章-内部类闭包与-lambda)
- [第 10 章 异常体系](#第-10-章-异常体系)
- [第 11 章 IO 与 NIO](#第-11-章-io-与-nio)
- [第 12 章 并发基础：JMM、volatile、synchronized](#第-12-章-并发基础jmmsynchronized)
- [第 13 章 并发进阶：AQS、锁、线程池、CompletableFuture](#第-13-章-并发进阶aqs锁线程池completablefuture)
- [第 14 章 JDK 版本演进：8 → 11 → 17 → 21](#第-14-章-jdk-版本演进8--11--17--21)
- [附录 A Java ↔ Kotlin 速查表](#附录-a-java--kotlin-速查表)
- [附录 B 一图总结](#附录-b-一图总结)

---


## 第 0 章 坐标系：这份文档覆盖什么

Java 的知识可以粗分成四层。先确认你在哪一层卡住，再去对应章节：

```
┌─────────────────────────────────────────────────────────────┐
│  第四层  并发与内存模型                                       │
│  JMM / volatile / synchronized / AQS / 线程池 / JUC          │  第 12、13 章
├─────────────────────────────────────────────────────────────┤
│  第三层  对象契约与常用库                                     │
│  equals·hashCode / 集合 / String / 异常 / IO·NIO             │  第 6～11 章
├─────────────────────────────────────────────────────────────┤
│  第二层  运行期魔法（"Java 为什么能这么玩"）                   │
│  反射 / 动态代理 / 注解 / 类加载                              │  第 2～5 章
├─────────────────────────────────────────────────────────────┤
│  第一层  类型系统                                            │
│  泛型 / 类型擦除 / 通配符 / 桥接方法                          │  第 1 章
└─────────────────────────────────────────────────────────────┘
```

**为什么把泛型放在第一层？** 因为「类型擦除」是 Java 与 Kotlin 差异的**根**。你在 Kotlin 里写 `inline fun <reified T>` 能拿 `.javaClass`，在 Java 里做不到；你写 `List<String>` 和 `List<Integer>` 在运行期是同一个类——这类"别扭感"全部来自这一层。理解了擦除，第二层的反射和动态代理就顺了（反射本来就是"擦除之后的补救手段"）。

**关于「我是不是白学了」这个隐忧**：Java 的演进策略是"不破坏兼容"，所以你做 Android / 服务端积累的那些直觉——接口、继承、异常、集合 API——一条都没失效。真正变化大的是**书写风格**（`var`、`record`、`switch` 表达式、模式匹配）和**并发原语**（虚拟线程）。这两块在第 13、14 章集中处理。

---

## 第 1 章 泛型与类型擦除（你点名要复习的）

### 1.1 回忆钩子

试着不开 IDE 回答这几个问题。答不上来的，看对应小节：

| 问题                                                       | 小节  |
| -------------------------------------------------------- | --- |
| `List<String>` 和 `List<Integer>` 的 `getClass()` 相不相等？    | 1.2 |
| 既然擦除了，为什么反射还能拿到 `List<String>` 里的 `String`？              | 1.3 |
| `? extends` 和 `? super` 到底谁读谁写？PECS 的后半句是什么？             | 1.5 |
| `List<?>`、`List<Object>`、`List<? extends Object>` 有什么区别？ | 1.6 |
| 编译后的 class 里那个多出来的 `compare(Object,Object)` 方法是什么？       | 1.7 |
| 为什么不能 `new T[]`、不能 `new ArrayList<String>[10]`？          | 1.8 |
| `@SafeVarargs` 是干什么的？                                    | 1.9 |

### 1.2 类型擦除：到底擦掉了什么

**一句话**：泛型是**编译期的语法糖**，编译后类型参数被替换成它的**上界**（无上界就是 `Object`），并在必要处插入**强制转换**。

```java
List<String> a = new ArrayList<>();
List<Integer> b = new ArrayList<>();
System.out.println(a.getClass() == b.getClass());  // true，都是 class java.util.ArrayList
System.out.println(a.getClass().getName());         // java.util.ArrayList，没有 <String>
```

编译期帮你插了什么：

```java
// 你写的
List<String> list = new ArrayList<>();
list.add("hello");
String s = list.get(0);

// 编译后（概念上）
List list = new ArrayList();
list.add("hello");
String s = (String) list.get(0);   // 编译器插的强制转换
```

**所以 `List<String>` 的类型安全是"编译器帮你数着"，运行期没有任何保护。** 这也是下面这段能编译通过、运行时才炸的原因：

```java
List<String> list = new ArrayList<>();
List raw = list;            // 原始类型，绕开检查（unchecked 警告）
raw.add(123);
String s = list.get(0);     // ClassCastException: Integer cannot be cast to String
```

这就是**堆污染（heap pollution）**：声明说里面是 `String`，实际混进了 `Integer`。

### 1.3 关键反转：擦除不等于"信息全没了"

**很多人记错的一点**：说"泛型在运行期完全消失"是不准确的。准确说法是：

| 位置                                                  | 泛型信息                          | 运行期能否反射拿到                             |
| --------------------------------------------------- | ----------------------------- | ------------------------------------- |
| 类的字段类型                                              | 保留在 class 文件的 `Signature` 属性里 | ✅ `Field.getGenericType()`            |
| 方法的参数/返回值                                           | 同上                            | ✅ `Method.getGenericParameterTypes()` |
| 类的继承/实现的接口                                          | 同上                            | ✅ `Class.getGenericInterfaces()`      |
| 类的 type parameter 定义                                | 同上                            | ✅ `Class.getTypeParameters()`         |
| **局部变量的泛型**                                         | **不保留**                       | ❌                                     |
| **实际类型参数的值**（`new ArrayList<String>()` 里的 `String`） | **不保留**                       | ❌                                     |

前四行是"声明（declaration）上的泛型"，后两行是"使用（use site）上的泛型"。**擦除擦掉的是后者**——这也是 Gson / Jackson / Retrofit 能工作的原因：

```java
// 为什么 Gson 需要 TypeToken：因为 new ArrayList<Foo>() 里的 Foo 会被擦掉
List<Foo> list = gson.fromJson(json, new TypeToken<List<Foo>>(){}.getType());
//                                              ^^^^^^^^^^^^^^^^^^^
//                            匿名子类把 <List<Foo>> 写进了"父类的泛型实参"位置，
//                            这个位置属于"声明"，会保留在 Signature 里，所以能读到
```

**记住这条判据**：泛型信息"写在类/字段/方法签名上"就留得住，"写在 `new` 表达式或局部变量上"就留不住。

### 1.4 一个反直觉的坑：重载冲突

擦除会导致两个"看起来不同"的方法签名在擦除后变得相同：

```java
// ❌ 编译错误：name clash，both methods have same erasure
public void handle(List<String> list) {}
public void handle(List<Integer> list) {}
```

同理，这个也不行：

```java
// ❌ 编译错误
public void process(Pair<String, Integer> p) {}
public void process(Pair<Integer, String> p) {}
```

**但反过来有个著名的"能编译"版本**（因为返回值不参与重载决议，但擦除后签名确实一样，javac 会报 bridge 冲突）：

```java
// ❌ 编译错误：both methods have same erasure, yet neither overrides the other
public List<String> get() { return null; }
public List<Integer> get() { return null; }
```


### 1.5 通配符与 PECS

这是当年背过、现在最容易只记得半句的地方。完整版：

> **PECS = Producer Extends, Consumer Super**
>
> - 你**从里面读**（它是生产者）→ 用 `? extends T`
> - 你**往里面写**（它是消费者）→ 用 `? super T`

为什么必须是这个方向？核心是一句话：**协变读安全，逆变写安全。**

```java
// ? extends Number —— 只读
List<? extends Number> src = new ArrayList<Integer>();
Number n = src.get(0);      // ✅ 读出来一定是 Number 的子类，安全
src.add(1);                 // ❌ 编译错误！编译器不知道实际是 Integer 还是 Double

// ? super Integer —— 只写
List<? super Integer> dst = new ArrayList<Number>();
dst.add(1);                 // ✅ 写进去 Integer 一定放得下
Object o = dst.get(0);      // ✅ 能读，但只能当 Object 用（上界是 Object）
```

**记忆锚点**：`extends` 的时候编译器**不敢让你写**（怕类型不对），`super` 的时候编译器**不敢让你按具体类型读**（只知道是 Object）。**"哪个操作被禁掉了"就是识别方向的最快方法。**

标准用法（`Collections.copy` 的签名）：

```java
public static <T> void copy(List<? super T> dest, List<? extends T> src)
//                          ^^^^^^^^^^^^^ 写          ^^^^^^^^^^^^^ 读
```

一个直觉类比：`List<? extends Number>` 像是"我手上有一篓**标着 Number 的**水果，我保证每颗都能当 Number 吃，但我不告诉你具体是啥，所以你别往里塞"。`List<? super Integer>` 像是"我这篓的规格**至少能装 Integer**，你随便塞 Integer，但我捞出来只敢说是 Object"。

### 1.6 `List<?>` vs `List<Object>` vs `List<? extends Object>`

| 写法                       | 能读           | 能写              | 接受 `List<String>` 吗 |
| ------------------------ | ------------ | --------------- | ------------------- |
| `List<Object>`           | 元素是 `Object` | ✅ 能 add 任意对象    | ❌ **不能**（泛型不变）      |
| `List<?>`                | 元素是 `Object` | ❌ 只能 add `null` | ✅ 能                 |
| `List<? extends Object>` | 元素是 `Object` | ❌ 只能 add `null` | ✅ 能                 |

**最容易记错的一条**：`List<String>` **不是** `List<Object>` 的子类型。Java 泛型是**不变（invariant）**&#x7684;。这正是 Kotlin 要引入 `out` / `in` 的原因。

```java
void f(List<Object> l) {}
f(new ArrayList<String>());   // ❌ 编译错误
```

如果只需要"读"，用 `List<?>`；如果确实要"写任意类型"，才用 `List<Object>` 但会牺牲调用便利。

**`List<?>` 和 `List<? extends Object>` 的区别**：后者多了一条——不能把 `List<?>` 传给需要 `List<? extends Object>` 的地方？实际上 `List<?>` 等价于 `List<? extends Object>`。日常只用 `List<?>`，后者只是显式写法。


### 1.7 桥接方法（Bridge Method）

**回忆钩子**：为什么 `class MyComparator implements Comparator<Integer>` 编译后，`javap` 会多出一个 `compare(Object, Object)` 方法？

因为**泛型接口的擦除版本是 `compare(Object, Object)`**，而你的类实现的是 `compare(Integer, Integer)`。如果只有后者，多态就断了——通过接口引用调用时，JVM 找的是 `compare(Object,Object)`。所以编译器自动生成一个桥接方法，把调用转发给你的实现：

```java
// 你写的
public class MyComparator implements Comparator<Integer> {
    @Override
    public int compare(Integer a, Integer b) { return a - b; }
}

// 编译器补的（概念上）
public int compare(Object a, Object b) {          // bridge
    return compare((Integer) a, (Integer) b);     // 桥接 + 强制转换
}
```

验证命令：

```bash
javac MyComparator.java
javap -p -c MyComparator.class
# 输出里会看到两个 compare：
#   public int compare(java.lang.Integer, java.lang.Integer);
#   public int compare(java.lang.Object, java.lang.Object);   ← bridge
# 桥接方法的字节码是：checkcast Integer → invokevirtual compare(Integer,Integer)
```

**桥接方法为什么重要？** 两个真实影响：

1. **反射时方法数会多一个**，`getDeclaredMethods()` 会返回桥接方法。判断方法是否"真的"覆盖了父类/接口，要用 `Method.isBridge()` / `isSynthetic()` 过滤：
   ```java
   for (Method m : obj.getClass().getDeclaredMethods()) {
       if (m.isBridge() || m.isSynthetic()) continue;   // 跳过编译器生成物
       // ...
   }
   ```
2. **Kotlin 的 `@JvmName` 和 Java 的桥接方法会撞车**，这是 Kotlin 与 Java 互操作时"platform declaration clash"报错的来源之一。

### 1.8 泛型做不到的事（"擦除税"清单）

这一节是最值得背的——**面试和实际踩坑高频**。

| 做不到                         | 原因                           | 变通                                                                             |
| --------------------------- | ---------------------------- | ------------------------------------------------------------------------------ |
| `new T()`                   | 擦除后不知道 T 是什么                 | 传 `Class<T>` 参数，反射 `clazz.getDeclaredConstructor().newInstance()`              |
| `new T[10]`                 | 同上                           | 传 `Class<T>` + `Array.newInstance(clazz, n)`                                   |
| `new ArrayList<String>[10]` | 数组是协变的，泛型是不变的，两者结合会破坏类型安全    | 用 `List<List<String>>`，或 `(List<String>[]) new List[10]` + `@SuppressWarnings` |
| `instanceof List<String>`   | 运行期没有类型实参                    | `instanceof List` + 逐元素检查                                                      |
| `List<int>`                 | 基本类型不能做类型参数                  | `List<Integer>`（自动装箱）                                                          |
| 泛型类的静态字段用 T                 | 静态成员属于类不属于实例，T 的实参在每个实例上可以不同 | 静态泛型方法自己声明 `<T>`                                                               |
| 泛型方法里 `catch (T e)`         | 异常类型不能是类型变量                  | 用 `Class<T>` 或 `Supplier`                                                      |
| 基本类型 + 泛型运算                 | 装箱开销                         | 用专门的原生集合（如 `fastutil`）或 Kotlin 的 `IntArray`                                    |

**"为什么 `new T[]` 不行但要单独说"**：因为数组是**协变**的（`Object[] o = new String[1]` 合法），运行期会检查存入元素的实际类型。泛型擦除后这个检查会失效，所以 Java 干脆禁止创建泛型数组。

```java
// 这段能编译（因为只是声明，没真的 new 泛型数组）
List<String>[] arr = (List<String>[]) new List[10];   // unchecked 警告
```

### 1.9 可变参数与 `@SafeVarargs`

**回忆钩子**：为什么写 `@SafeVarargs` 能消掉泛型可变参数的警告？

因为**可变参数在底层就是数组**，`void foo(T... args)` 擦除后是 `void foo(Object[] args)`。传入 `foo("a", "b")` 时编译器要**现场创建一个数组**——如果 T 是泛型，这个数组的类型在运行期无法确定，于是又回到"不能创建泛型数组"的问题。Java 的妥协是：**允许但给 `unchecked` 警告**。

```java
@SafeVarargs                                  // 只有 static / final / private 方法能用
public static <T> List<T> of(T... items) {
    return new ArrayList<>(Arrays.asList(items));   // 只读不改，不会堆污染
}

// ❌ 危险版本：如果方法体里对这个数组做了写入/暴露，就可能堆污染
@SafeVarargs
public static <T> void bad(T... items) {
    Object[] raw = items;      // 擦除后 T[] 就是 Object[]
    raw[0] = 123;              // 往"应该是 String[]"的数组里塞了 Integer
}
```

**版本差异**：`@SafeVarargs` 在 Java 8 只能用在 `static` / `final` 方法上；**Java 9 起可以用在 `private` 实例方法上**。

### 1.10 Kotlin 对照

| Java                           | Kotlin                                                       | 差异根源                                                   |
| ------------------------------ | ------------------------------------------------------------ | ------------------------------------------------------ |
| `List<String>` 不变              | `List<String>` 协变（`List<out E>`）                             | Kotlin 在 `List` 声明处就标了 `out`                           |
| `? extends T`                  | `out T`（使用处型变）                                               | Kotlin 叫"使用处型变（use-site variance）"，语法一样是 `List<out T>` |
| `? super T`                    | `in T`                                                       | 同上                                                     |
| `List<?>`                      | `List<*>`（星投影）                                               | 语义几乎一致                                                 |
| 反射拿 `Class<T>` 才能实例化           | `inline fun <reified T>` 直接 `T::class`                       | Kotlin 用 inline 把类型实参**内联到调用点**，等于绕过了擦除                |
| 无法检查 `instanceof List<String>` | `if (list is List<String>)` 编译警告，但 `if (list is List<*>)` OK | Kotlin 也会擦除，只是更早提示                                     |

**关键理解**：Kotlin 的 `reified` 不是"Kotlin 有真泛型"，而是**编译器把函数体复制到调用点后，把 `T` 直接替换成了具体类型**——本质是语法层面的擦除规避，不是 JVM 层面。

```kotlin
inline fun <reified T> fromJson(json: String): T {
    return gson.fromJson(json, T::class.java)   // T::class 在调用点被替换成具体 Class
}
```

---

## 第 2 章 反射：运行期看穿自己

### 2.1 回忆钩子

| 问题                                           | 小节  |
| -------------------------------------------- | --- |
| `getMethods()` 和 `getDeclaredMethods()` 差在哪？ | 2.2 |
| `setAccessible(true)` 现在还一定能用吗？              | 2.4 |
| 反射为什么慢？JDK 18 之后有什么变化？                       | 2.5 |
| 怎么用反射往 `List<String>` 里塞 `Integer`？          | 2.6 |

### 2.2 三类入口

```java
Class<?> c1 = String.class;                  // 类字面量，不触发初始化之外的副作用，最快
Class<?> c2 = "hi".getClass();               // 实例.getClass()
Class<?> c3 = Class.forName("java.lang.String");  // 会执行静态初始化块（initialize=true）
Class<?> c4 = loader.loadClass("java.lang.String"); // 不初始化
```

**一个容易忘的区别**：`Class.forName(name)` 默认 `initialize=true`，会触发静态代码块——**JDBC 驱动注册就是靠这个行为**（`Class.forName("com.mysql.cj.jdbc.Driver")`）。`loadClass` 不初始化。

成员查找：

| 方法                                    | 范围   | 继承     | 非 public            |
| ------------------------------------- | ---- | ------ | ------------------- |
| `getMethods()`                        | 所有   | ✅ 含继承  | ✅ 含（但只有 public 才返回） |
| `getDeclaredMethods()`                | 本类   | ❌ 不含继承 | ✅ 包含全部              |
| `getFields()` / `getDeclaredFields()` | 同上规律 |        |                     |

**记法**：带 `Declared` = "只看我自己声明的"。

### 2.3 反射的基本操作

```java
Class<?> clazz = Class.forName("com.example.Foo");

// 实例化（Java 9+ 推荐用 getDeclaredConstructor，newInstance() 已废弃）
Constructor<?> ctor = clazz.getDeclaredConstructor(String.class);
Foo foo = (Foo) ctor.newInstance("arg");

// 字段
Field f = clazz.getDeclaredField("secret");
f.setAccessible(true);                 // 突破 private
Object old = f.get(foo);
f.set(foo, "new value");

// 方法
Method m = clazz.getDeclaredMethod("compute", int.class);
m.setAccessible(true);
Object result = m.invoke(foo, 42);     // 静态方法第一个参数传 null

// 泛型信息（见 1.3 节）
Type t = f.getGenericType();           // 可能是 ParameterizedType
if (t instanceof ParameterizedType pt) {
    System.out.println(Arrays.toString(pt.getActualTypeArguments()));
}

// 注解
Deprecated d = clazz.getAnnotation(Deprecated.class);
```

**`invoke` 里 `this` 参数的位置**：实例方法传对象，静态方法传 `null`。


### 2.4 强封装（Strong Encapsulation）：现代 JDK 的真实限制

这是**十年前的直觉会失效的地方**，必须更新：

- **Java 8**：`setAccessible(true)` 几乎万能，JDK 内部类（`sun.*`）随便改。
- **Java 9 引入模块系统（JPMS）**：开始对 JDK 内部 API 做封装。
- **Java 16 起，默认强制封装**（JEP 396，`--illegal-access=deny` 成为默认），访问 JDK 内部非公开成员会直接抛：

```
java.lang.reflect.InaccessibleObjectException:
  Unable to make field private final byte[] java.lang.String.value accessible:
  module java.base does not "opens java.lang" to unnamed module @1b6d3586
```

**变通（也是"怎么改启动参数"的标准答案）**：

```bash
# 单个包开放给所有模块
--add-opens java.base/java.lang=ALL-UNNAMED
# 多个一起写
--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED
```

**Android 视角**：Android 有自己的等价物。从 **Android 9（API 28）** 开始，对非 SDK 接口（hidden API）做限制，反射调用会被拦：

```java
// Android 9+ 反射调用 hidden API 的报错
// NoSuchMethodException / NoSuchFieldException，或返回 null
```

这不是"没有这个方法"，而是 ART 在 greylist / blacklist 拦截。开发期的变通是 `adb shell settings put global hidden_api_policy 1`（仅调试，别进生产）。**你在车机 ROM 里做系统集成时，能直接引用 framework 的 `@hide` 类（因为是 vendor 编译），比反射干净得多。**

### 2.5 反射为什么慢，以及现在的状况

慢的四个来源：

1. **无法内联**：JIT 看不到目标方法，无法做内联优化。
2. **参数装箱 + 数组包装**：`invoke(Object, Object...)` 每次调用都要创建 `Object[]`。
3. **访问检查**：每次调用都要做可见性校验。
4. **自动装箱**：基本类型参数要装箱成 `Object`。

**关键的版本更新**：

- **Java 8 时代**：有个"反射膨胀（inflation）"机制——前 15 次调用走 native、纯解释的 `NativeMethodAccessorImpl`，超过阈值后生成字节码版 `GeneratedMethodAccessorN`（这也是"反射调用预热后才快"说法的来源）。
- **JDK 18（JEP 416）起**：这套 inflation 机制被**移除**，`Method.invoke` 改为直接基于 `MethodHandle` 实现。所以"前 15 次慢、之后变快"这个经验在 JDK 18+ 已经不准了——**基本是稳定的中等开销**。

**更快的替代（知道有这层就够）**：

| 手段                     | 相对速度   | 代价                                    |
| ---------------------- | ------ | ------------------------------------- |
| 直接调用                   | 1x     | —                                     |
| `MethodHandle`         | ~2-5x  | 需 `MethodType`，`invokeExact` 要求签名严格匹配 |
| `Method.invoke`        | ~5-20x | 简单                                    |
| `VarHandle`（Java 9+）   | 接近直接访问 | 字段/数组访问专用，替代 `Unsafe`                 |
| 生成字节码（ByteBuddy / ASM） | 接近直接调用 | 复杂，"代理类缓存"是标准做法                       |

**实践建议**：反射热点（比如序列化框架的字段读写）标准做法是**一次性扫描 + 缓存 `Method`/`Field` 对象 + 生成访问器**。自己写业务代码时，反射只在初始化阶段用，别放进循环。

### 2.6 反射的"越界能力"

```java
// 用反射突破泛型——这是"擦除"最直观的证据
List<String> list = new ArrayList<>();
Method add = List.class.getMethod("add", Object.class);
add.invoke(list, 123);            // 成功塞进 Integer
System.out.println(list.get(0));  // 123（但下面这行会炸）
// String s = list.get(0);        // ClassCastException（强制转换在调用点）

// 改 final 字段
Field f = clazz.getDeclaredField("CONST");
f.setAccessible(true);
f.set(null, "changed");   // 静态 final 字段
```

**注意**：`static final` 的**编译期常量**（`String`、基本类型的字面量初始化）会被**内联到调用点**，改了字段也读不到新值——JVM 根本没有"去读字段"这个动作。这个坑值得记住。

### 2.7 Kotlin 对照

| Java                                      | Kotlin                                                                 |
| ----------------------------------------- | ---------------------------------------------------------------------- |
| `clazz.getDeclaredMethod("f", int.class)` | `clazz.getDeclaredMethod("f", Int::class.java)`                        |
| `m.invoke(obj, 42)`                       | `m.invoke(obj, 42)`                                                    |
| 类型用 `Class<?>`                            | 用 `KClass<*>`，靠 `.java` 互转`clazz.kotlin` / `kclass.java`               |
| —                                         | `T::class`（仅 `reified` 内可用）                                            |
| —                                         | `obj::class.members`（Kotlin 反射，需 `kotlin-reflect` 依赖，比 Java 反射更声明式但更慢） |

**Android 上的现实**：Kotlin 反射库会显著增大 APK（几百 KB），车机项目里通常仍用 Java 反射或直接生成代码。

---

## 第 3 章 动态代理：一个类装成另一个类（你点名要复习的）

### 3.1 回忆钩子

| 问题                                                         | 小节  |
| ---------------------------------------------------------- | --- |
| JDK 动态代理**为什么只能代理接口**？                                     | 3.3 |
| `InvocationHandler.invoke` 的第一个参数 `proxy` 和 `this` 是不是一回事？ | 3.4 |
| 生成出来的类叫什么？父类是谁？                                            | 3.3 |
| `equals` / `hashCode` / `toString` 会被转发到 handler 吗？        | 3.4 |
| CGLIB 和 JDK 代理的本质区别？Spring AOP 默认用哪个？                      | 3.6 |
| Retrofit 是怎么把一个只有声明没有实现的接口变成 HTTP 请求的？                     | 3.8 |

### 3.2 一句话本质

**动态代理 = 运行期生成一个新类，它实现（或继承）目标类型，把每个方法调用都转交给你写的一段统一逻辑。**

它解决的核心问题是：**我想在"不修改也不认识原始实现"的前提下，给一批方法统一加上前置/后置逻辑**——日志、耗时统计、事务、权限校验、重试、懒加载。

和继承的关系：

```
静态代理：手写一个 AProxy implements A，每个方法里包一层       → 方法多了会累死
动态代理：让 JVM 在运行期生成 AProxy，方法体全部指向一个 handler → 一次编写，批量生效
```


### 3.3 JDK 动态代理：为什么必须传接口

```java
// 1. 你要代理的接口
public interface OrderService {
    String create(String sku, int qty);
    void cancel(String orderId);
}

// 2. 你的统一逻辑
public class TimingHandler implements InvocationHandler {
    private final Object target;                       // 真实实现

    public TimingHandler(Object target) { this.target = target; }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        long t0 = System.nanoTime();
        try {
            return method.invoke(target, args);        // 转发给真实对象
        } finally {
            System.out.println(method.getName() + " cost=" + (System.nanoTime() - t0) / 1_000_000 + "ms");
        }
    }
}

// 3. 生成代理
OrderService real = new OrderServiceImpl();
OrderService proxy = (OrderService) Proxy.newProxyInstance(
        OrderService.class.getClassLoader(),   // 类加载器
        new Class<?>[]{ OrderService.class },  // 要实现的接口列表
        new TimingHandler(real));              // 调用处理器
proxy.create("SKU-1", 2);                     // 走 handler
```

**为什么必须传接口？** 关键在这一句：**JDK 生成的代理类是 `Proxy` 的子类**。

```java
public final class $Proxy0 extends Proxy implements OrderService { ... }
//                       ^^^^^^^^^^^^^^
```

Java 是**单继承**。`$Proxy0` 已经 `extends Proxy` 了，就没法再 `extends OrderServiceImpl`。所以它能做的只有"实现接口"，用接口来获得需要代理的方法列表。

**推论（都能从这一句推出来）**：

1. **没有接口就没法用 JDK 动态代理**——这也是 Spring AOP 需要 CGLIB 的原因。
2. 代理类中的方法都是 `final`，**无法被再次代理（不能叠加）**。
3. 一个 handler 可以同时代理多个接口：`new Class<?>[]{ A.class, B.class }`，`$Proxy0` 会 implements 两个。

**看一眼生成的真实字节码**（强烈建议自己做一遍）：

```bash
# JDK 9+ 用这个系统属性（JDK 8 是 sun.misc.ProxyGenerator.saveGeneratedFiles）
java -Djdk.proxy.ProxyGenerator.saveGeneratedFiles=true MyApp

# 生成的文件在当前目录的 com/sun/proxy/$Proxy0.class
javap -p -c com/sun/proxy/\$Proxy0.class
```

你会看到 `$Proxy0` 的样子（简化）：

```java
public final class $Proxy0 extends Proxy implements OrderService {
    private static Method m3;   // create
    private static Method m4;   // cancel

    public $Proxy0(InvocationHandler h) {
        super(h);               // Proxy 里保存了 handler
    }

    public final String create(String sku, int qty) {
        try {
            return (String) super.h.invoke(this, m3, new Object[]{ sku, qty });
            //                                ^^^^ 注意这里是 this，即代理对象本身
        } catch (RuntimeException | Error e) { throw e; }
        catch (Throwable t) { throw new UndeclaredThrowableException(t); }
    }
}
```

**目录结构说明为什么没有接口不行**：`$Proxy0` 的 `extends Proxy` 是硬性的（`Proxy` 提供了 `h` 字段和 `newProxyInstance` 的配套机制）。


### 3.4 三个最容易忘的细节

**（1）`invoke` 的第一个参数是代理对象，不是目标对象**

```java
public Object invoke(Object proxy, Method method, Object[] args)
//                   ^^^^^^^^^^^ 这是 $Proxy0 实例，不是 target
```

**在 handler 内部调用 `proxy.xxx()` 会造成无限递归**（因为又会走到 handler）。要调真实实现，必须用你注入的 `target`：

```java
// ❌ 死循环 → StackOverflowError
public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    return method.invoke(proxy, args);
}

// ✅
return method.invoke(target, args);
```

**（2）`equals` / `hashCode` / `toString` 也会被转发**

`$Proxy0` 里除了接口方法，还覆写了 `equals` / `hashCode` / `toString` 三个来自 `Object` 的方法，它们的调用同样会走进你的 handler。所以：

```java
@Override
public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    String name = method.getName();
    if ("equals".equals(name) || "hashCode".equals(name) || "toString".equals(name)) {
        // 常规做法：直接按 Object 语义处理，不转发给 target
        return switch (name) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals"   -> proxy == args[0];
            default         -> "Proxy@" + System.identityHashCode(proxy);
        };
    }
    return method.invoke(target, args);
}
```

**踩坑场景**：把代理对象放进 `HashMap` 当 key、打日志时 `log.info("{}", proxy)`，都会触发。如果 handler 里又调了 `target.toString()` 而 target 是懒加载的，可能直接触发初始化。

**（3）受检异常的包装**

`InvocationHandler.invoke` 声明的是 `throws Throwable`，但代理方法的签名可能不声明任何受检异常。JDK 生成的代码在捕获到 handler 抛出的受检异常时，会包装成：

```java
java.lang.reflect.UndeclaredThrowableException
```

这是"动态代理 + 受检异常"的经典报错，看到它要想到：**底层抛了一个接口没声明的受检异常**。`getCause()` 才是真正的异常。


### 3.5 手写一个能用的动态代理工具

把上面的知识合起来，做一个"给任意接口实现加统一重试"的工具：

```java
public final class RetryProxy {

    @SuppressWarnings("unchecked")
    public static <T> T wrap(T target, Class<T> iface, int maxAttempts) {
        return (T) Proxy.newProxyInstance(
                iface.getClassLoader(),
                new Class<?>[]{ iface },
                (proxy, method, args) -> {
                    // 1. Object 的三个方法按本尊语义处理，不转发
                    String name = method.getName();
                    if (name.equals("hashCode")) return System.identityHashCode(proxy);
                    if (name.equals("equals"))   return proxy == args[0];
                    if (name.equals("toString")) return "RetryProxy(" + iface.getSimpleName() + ")";
                    // 2. 默认方法（接口 default）直接放行给 target
                    if (method.isDefault()) return method.invoke(target, args);

                    Throwable last = null;
                    for (int i = 1; i <= maxAttempts; i++) {
                        try {
                            return method.invoke(target, args);
                        } catch (InvocationTargetException e) {
                            last = e.getCause();     // 剥掉反射包装
                            System.out.println(name + " 第" + i + "次失败: " + last);
                        }
                    }
                    throw last;
                });
    }

    private RetryProxy() {}
}
```

注意这里两个技巧：**剥掉 `InvocationTargetException`**（反射调用真实实现时，target 抛的异常会被包装一层）和 **`method.isDefault()` 放行**（代理接口的 default 方法时，不转发的话默认实现会被绕过）。


### 3.6 CGLIB：靠继承实现

|                | JDK 动态代理                             | CGLIB                                         |
| -------------- | ------------------------------------ | --------------------------------------------- |
| 实现方式           | 生成 `Proxy` 的**子类**，`implements` 目标接口 | 生成目标类的**子类**，`extends` 目标类                    |
| 前提条件           | 必须有接口                                | 类**不能是 final**，方法**不能是 final / private**      |
| 覆盖范围           | 只覆盖接口方法                              | 覆盖所有**可覆写**方法                                 |
| 代理 `Object` 方法 | 会转发 equals/hashCode/toString         | 也覆盖                                           |
| 构造器            | 无需                                   | **会调用父类构造器**（不能代理只有有参构造器的类，除非用 `Enhancer` 指定） |
| 生成技术           | `ProxyGenerator` 手写字节码               | ASM                                           |
| 性能             | 生成快，调用略慢                             | 生成慢（要解析字节码），调用快                               |
| 依赖             | JDK 内置                               | 需额外依赖（Spring 已内嵌 shaded 版）                    |

CGLIB 用法（了解即可，现代项目一般用 ByteBuddy 或 Spring 封装的 `ProxyFactory`）：

```java
Enhancer enhancer = new Enhancer();
enhancer.setSuperclass(OrderServiceImpl.class);
enhancer.setCallback((MethodInterceptor) (obj, method, args, proxy) -> {
    long t0 = System.nanoTime();
    try {
        return proxy.invokeSuper(obj, args);   // 注意是 invokeSuper，调父类实现
    } finally {
        System.out.println(method.getName() + " " + (System.nanoTime() - t0) + "ns");
    }
});
OrderServiceImpl proxy = (OrderServiceImpl) enhancer.create();
```

**Spring AOP 的选择规则**（这是"十年前和现在不一样"的点）：

- **Spring 4 / Spring Boot 1.x**：目标类有接口 → JDK 代理；无接口 → CGLIB。
- **Spring Boot 2.x 起**：`spring.aop.proxy-target-class` 默认 `true`，**一律用 CGLIB**。原因是 JDK 代理只能注入接口类型，而很多代码用 `@Autowired OrderServiceImpl` 直接注入实现类，用 JDK 代理会启动失败。

**这句"默认改成 CGLIB"值得单独记住**——它解释了大量"为什么我没接口也能用 `@Transactional`"的现象。

**Android 视角**：Android 上 CGLIB / ByteBuddy 这类运行期字节码生成受 `DexFile` 限制，一般用不了（Dex 不是 JVM class 文件）。**Android 上的 AOP 方案是编译期/构建期织入**：AspectJ（Gradle 插件）、字节码变换（Transform API / ASM 改 class → 再 dex）、或注解处理器生成代码。**这就是"为什么 Spring 那套 AOP 直觉搬到 Android 会失效"的根本原因**，值得记住。

### 3.7 动态代理在现实框架里的位置

| 框架                          | 代理什么            | 用什么                  | 干什么                      |
| --------------------------- | --------------- | -------------------- | ------------------------ |
| **Spring `@Transactional`** | Service 类/接口    | CGLIB（Boot 2+）       | 方法前后开/提交/回滚事务            |
| **Spring `@Async`**         | 同上              | 同上                   | 把调用丢到线程池                 |
| **Spring 依赖注入**             | Bean            | 同上                   | 循环依赖时提前暴露代理              |
| **MyBatis Mapper**          | `XxxMapper` 接口  | **JDK 动态代理**         | 方法名 → SQL（`MapperProxy`） |
| **Retrofit**                | `ApiService` 接口 | **JDK 动态代理**         | 方法 + 注解 → HTTP 请求        |
| **Hibernate 懒加载**           | 实体类             | CGLIB / ByteBuddy    | 访问字段时才查库                 |
| **Mockito（mock 接口）**        | 接口              | JDK 动态代理 / ByteBuddy | 打桩                       |
| **Kotlin 的 `by` 委托**        | 接口              | 编译期生成委托方法            | 编译期方案，非动态代理              |

**Android 侧的对应物**：`android.hardware.camera2` 的 `CameraCaptureSession`、`androidx` 里的 `LifecycleObserver`… 大多不是动态代理。**Android 真正用 JDK 动态代理的地方**主要是：Retrofit（网络）、`Retrofit` 的 CallAdapter、部分 DI 框架（Hilt/Dagger 走编译期）、`androidx.test` 的 mock。


### 3.8 拆解 Retrofit：动态代理最漂亮的应用

这是"理解动态代理"最好的实例，因为 Retrofit 把一个**纯声明接口**变成了完整的 HTTP 调用：

```java
public interface GitHubApi {
    @GET("users/{user}/repos")
    Call<List<Repo>> listRepos(@Path("user") String user);
}
```

**没有实现类**，但下面这行能跑：

```java
GitHubApi api = retrofit.create(GitHubApi.class);
api.listRepos("octocat");   // 真的发 HTTP 请求
```

`retrofit.create()` 内部做的就是：

```java
public <T> T create(final Class<T> service) {
    // 1. 校验：必须是接口、不能继承其他接口
    validateServiceInterface(service);

    // 2. 生成代理
    return (T) Proxy.newProxyInstance(service.getClassLoader(),
        new Class<?>[]{ service },
        new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if (method.getDeclaringClass() == Object.class) {
                    return method.invoke(this, args);        // Object 方法不拦截
                }
                // 3. 把 Method + 注解 + 实参 解析成一个 ServiceMethod（含 URL、HTTP 方法、Header、Body）
                return loadServiceMethod(method).invoke(args);
                //            ^^^^^^^^^^^^^^^^ 带缓存，同一个 Method 只解析一次
            }
        });
}
```

**几个值得注意的设计决策，正好对应前面讲的知识**：

1. **`loadServiceMethod` 有缓存**（`ConcurrentHashMap<Method, ServiceMethod>`）——因为方法解析（读注解、拼 URL）比 HTTP 调用还贵，而动态代理每次调用都要过 handler，所以必须缓存。这是"反射/代理开销要用缓存摊平"的教科书案例。
2. **`method.getDeclaringClass() == Object.class` 过滤**——正是 3.4 节说的 `equals/hashCode/toString` 会被转发的问题，Retrofit 显式排除。
3. **只能代理接口**——所以 Retrofit 的 ApiService 必须是 interface，这个约束的根因就是 3.3 节。

### 3.9 Kotlin 对照

Kotlin 里没有"动态代理"这个语法概念，但用起来更省：

```kotlin
// 生成代理：几乎一样，只是 lambda 可以直接当 InvocationHandler（SAM 转换）
val proxy = Proxy.newProxyInstance(
    iface.classLoader,
    arrayOf(iface)
) { _, method, args ->
    method.invoke(target, *(args ?: emptyArray()))
} as OrderService
```

**Kotlin 的替代方案（更好用，优先考虑）**：

```kotlin
// 1. 委托（by）：编译期生成，零反射开销，性能最好
class OrderServiceProxy(private val delegate: OrderService) : OrderService by delegate {
    override fun create(sku: String, qty: Int): String {
        val t0 = System.nanoTime()
        return delegate.create(sku, qty).also {
            println("create cost ${System.nanoTime() - t0}ns")
        }
    }
}

// 2. 委托属性（by lazy / by observable）：也是编译期
```

**选择建议**：能编译期解决就别用动态代理。动态代理的价值在&#x4E8E;**"编译期不知道有哪些类型"**&#x7684;场景（框架、Retrofit 这种），业务代码里 `by` 委托通常更好。

---

## 第 4 章 注解与运行时处理

### 4.1 回忆钩子

| 问题                                              | 小节  |
| ----------------------------------------------- | --- |
| 注解到底是什么？是接口吗？                                   | 4.2 |
| `SOURCE` / `CLASS` / `RUNTIME` 三种保留策略，运行时能读到哪些？ | 4.3 |
| 注解里为什么能写 `String[] value()` 这种"方法"？             | 4.2 |
| `@Inherited` 有什么用？                              | 4.4 |
| Lombok 为什么会"污染"IDE？                             | 4.6 |

### 4.2 本质：注解是一个特殊的接口

`@interface` 编译后就是一个 `extends java.lang.annotation.Annotation` 的接口。你写：

```java
public @interface Route {
    String value();
    String method() default "GET";
    int[] versions() default {};
}
```

编译后约等于：

```java
public interface Route extends Annotation {
    String value();
    String method();
    int[] versions();
}
// 只是这些方法不能有参数、不能抛异常、返回类型受限
```

**"注解里的方法"其实是"属性声明"**，`@Route("/a")` 等价于给 `value` 赋值。当只有一个属性且叫 `value` 时，可以省略属性名——这就是为什么 `@GET("x")` 不用写 `value =`。

**允许的属性类型**（就这几类，记不全没关系，IDE 会拦）：

- 基本类型、`String`、`Class`、枚举、注解、以及它们的**数组**
- ❌ 不能是 `null`、不能是任意对象、不能是 `Integer`（要用 `int`）

### 4.3 保留策略：`@Retention`

```java
@Retention(RetentionPolicy.SOURCE)     // 编译后丢弃，只给编译器/工具看
@Retention(RetentionPolicy.CLASS)      // 写进 class 文件，但 JVM 运行时读不到（默认值）
@Retention(RetentionPolicy.RUNTIME)    // 写进 class 文件，且反射可读
```

| 策略        | 在 class 文件里 | 反射可读 | 典型用途                                                    |
| --------- | ----------- | ---- | ------------------------------------------------------- |
| `SOURCE`  | ❌           | ❌    | `@Override`、`@SuppressWarnings`、Lombok                  |
| `CLASS`   | ✅           | ❌    | 字节码工具（ASM / AspectJ）读取                                  |
| `RUNTIME` | ✅           | ✅    | Spring 的 `@Autowired`、Retrofit 的 `@GET`、JUnit 的 `@Test` |

**最容易记错的一条**：**默认是 `CLASS`，不是 `RUNTIME`。** 自己写注解时忘了写 `@Retention(RUNTIME)`，反射读不到——这是极高频的"为什么我的注解不生效"。

### 4.4 元注解一览

| 元注解                    | 作用                                                                                             |
| ---------------------- | ---------------------------------------------------------------------------------------------- |
| `@Retention`           | 保留到哪个阶段                                                                                        |
| `@Target`              | 能贴在哪些位置（`TYPE`/`METHOD`/`FIELD`/`PARAMETER`/`CONSTRUCTOR`/`ANNOTATION_TYPE`/`TYPE_PARAMETER`…） |
| `@Documented`          | 是否进 javadoc                                                                                    |
| `@Inherited`           | 子类**自动获得**父类上的该注解。**只对类有效，对接口无效，对方法无效**                                                        |
| `@Repeatable`（Java 8+） | 同一位置可重复标注                                                                                      |

**`@Inherited` 的三个限制**（这是当年最容易记混的）：

1. **只对 `class` 继承生效**，不适用于接口（实现类不会继承接口上的 `@Inherited` 注解）
2. **不适用于方法**（子类覆写方法不会继承父类方法上的注解）
3. 只有 `getAnnotation()`（会走父类链）能看到，`getDeclaredAnnotation()` 看不到

```java
@Inherited @Retention(RUNTIME) @interface Marker {}

@Marker class Parent {}
class Child extends Parent {}

Child.class.getAnnotation(Marker.class);         // ✅ 拿得到（因为 @Inherited）
Child.class.getDeclaredAnnotation(Marker.class); // ❌ null
```

**为什么 Spring 的 `@Transactional` 不用 `@Inherited` 也能在子类生效？** 因为 Spring 自己实现了**注解合并（MergedAnnotations）**&#x903B;辑，逐层往上去找，不依赖 JDK 的 `@Inherited`。这是个好例子：**框架会自己造一套更强的注解查找规则。**

### 4.5 运行期读取注解

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Route { String value(); }

@Route("/user")
public class UserController {}

// 读取
Route route = UserController.class.getAnnotation(Route.class);
System.out.println(route.value());   // /user

// 方法参数上的注解（Retrofit 的 @Path / @Query）
Method m = GitHubApi.class.getMethod("listRepos", String.class);
Annotation[][] paramAnns = m.getParameterAnnotations();   // 二维：第1维是参数下标
for (Annotation a : paramAnns[0]) {
    if (a instanceof Path p) System.out.println(p.value());
}
```

### 4.6 两条技术路线：反射读取 vs 编译期生成

这是"注解要配合什么用"的决策点：

|      | 运行期反射                 | 编译期注解处理器（APT）                             |
| ---- | --------------------- | ----------------------------------------- |
| 代表   | Spring、Retrofit、JUnit | Dagger/Hilt、Room、ButterKnife(旧)、AutoValue |
| 时机   | 运行时扫描 + 反射            | `javac` 编译期间生成 Java 源码                    |
| 开销   | 运行时有反射成本，需缓存          | **运行时零反射**，直接调生成的方法                       |
| 报错时机 | 运行时                   | **编译期报错**（最大优势）                           |
| 依赖   | 无                     | 需 `annotationProcessor` 依赖                |

**Android 视角**：这正是 Android 生态从 ButterKnife（反射）转向 ViewBinding / Hilt / Room（APT）的原因——**启动时的反射扫描会显著拖慢冷启动**，而且 Android 上有 hidden API 和 R8 混淆的额外麻烦。做车机项目时，**开机时间敏感**，APT 方案优先。

**Lombok 为什么特殊**：它走的是 **`SOURCE` 策略 + 编译器内部的注解处理器直接改写 AST**。所以：

- 运行期完全没有 Lombok 的痕迹（没有反射开销）
- 但 **IDE 必须装插件**才能"看见"生成的 getter/setter，否则满屏红
- 用 `RUNTIME` 反射去读 Lombok 生成的 getter 是能读到的（因为它们真的被写进了 class）

### 4.7 Kotlin 对照

| Java                                  | Kotlin                                             |
| ------------------------------------- | -------------------------------------------------- |
| `@Retention(RetentionPolicy.RUNTIME)` | `@Retention(AnnotationRetention.RUNTIME)`          |
| `@Target(ElementType.METHOD)`         | `@Target(AnnotationTarget.FUNCTION)`               |
| 反射读 `clazz.getAnnotation(X.class)`    | `clazz.getAnnotation(X::class.java)`               |
| 注解处理                                  | KSP（Kotlin Symbol Processing）替代 APT，**比 KAPT 快很多** |
| —                                     | `@JvmField` / `@JvmStatic` 是控制"给 Java 暴露什么"的注解     |

**Android 新项目的现实**：新库基本都在往 **KSP** 迁移（Room、Moshi、Hilt 都支持）。如果你要写注解处理，优先 KSP。

---

## 第 5 章 类加载与双亲委派

### 5.1 回忆钩子

| 问题                                           | 小节  |
| -------------------------------------------- | --- |
| 类加载的三个阶段是什么？                                 | 5.2 |
| 双亲委派"委派失败"之后会发生什么？                           | 5.3 |
| 为什么需要破坏双亲委派？                                 | 5.4 |
| ClassLoader 为什么会导致内存泄漏？                      | 5.5 |
| `Class.forName` 和 `loadClass` 差在哪？（2.2 节出现过） | 5.2 |


### 5.2 生命周期

```
加载 → 验证 → 准备 → 解析 → 初始化 → 使用 → 卸载
Loading  Verification  Preparation  Resolution  Initialization
```

| 阶段      | 干了什么                                        | 容易忘的点                                            |
| ------- | ------------------------------------------- | ------------------------------------------------ |
| **加载**  | 读 class 字节流 → 方法区中的运行时数据结构 + 堆中的 `Class` 对象 | 字节流来源不限于文件（网络、JAR、动态生成）                          |
| **验证**  | 字节码合法性（魔数、语法、语义、符号引用）                       | ——                                               |
| **准备**  | 为**静态变量**分配内存并给**零值**                       | **不是给代码里的初值**！`static int a = 10;` 在准备阶段 `a = 0` |
| **解析**  | 符号引用 → 直接引用                                 | 可能延迟到首次使用时                                       |
| **初始化** | 执行 `<clinit>`，即静态变量赋真值和静态代码块                | `static final` 常量在**准备**阶段就赋值（编译期常量）             |

**"准备 vs 初始化"这个区别值得单独记**：`static int a = 10;` 准备阶段是 `0`，初始化阶段才是 `10`；但 `static final int B = 10;` 作为编译期常量，直接内联，准备阶段就是 `10`。

**触发初始化的六个时机**（"主动引用"）：

1. `new`、读/写静态字段（非 final 常量）、调用静态方法
2. 反射 `Class.forName(...)`
3. 初始化子类时先初始化父类
4. 虚拟机启动时的主类（`main` 所在类）
5. `MethodHandle` / `VarHandle` 解析到的类
6. 接口的 default 方法所在接口被实现类初始化时

**不触发初始化的（"被动引用"）**——这是考点：

```java
// 1. 通过子类访问父类的静态字段，只初始化父类
System.out.println(SubClass.parentStaticValue);   // 不初始化 SubClass

// 2. 通过数组定义引用类
Parent[] arr = new Parent[10];                    // 不初始化 Parent

// 3. 引用编译期常量
System.out.println(ConstClass.VALUE);             // 不初始化 ConstClass（常量被内联）
```


### 5.3 双亲委派：模型与代码

```java
protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    synchronized (getClassLoadingLock(name)) {
        Class<?> c = findLoadedClass(name);          // 1. 先看自己加载过没
        if (c == null) {
            try {
                if (parent != null) {
                    c = parent.loadClass(name, false);   // 2. 交给父加载器
                } else {
                    c = findBootstrapClassOrNull(name);  // 3. 父为 null → 启动类加载器
                }
            } catch (ClassNotFoundException e) {
                // 父加载器找不到，忽略，继续往下
            }
            if (c == null) {
                c = findClass(name);                     // 4. 自己找
            }
        }
        return c;
    }
}
```

**"双亲"其实是"父"，没有"母"**——这个词是翻译遗留（`parent` 单数）。委派链是：

```
Bootstrap ClassLoader（C++ 实现，加载 java.*，getClassLoader() 返回 null）
      ↑
Extension / Platform ClassLoader（JDK 9 起改名 Platform，加载 java.se 之外的扩展）
      ↑
Application ClassLoader（加载 classpath）
      ↑
自定义 ClassLoader
```

**关键理解**：加载请求**先往上问**，上面找不到才自己加载。这保证了：

1. **同一个类不会被重复加载**（父加载器已加载的直接返回）
2. **核心类不可被篡改**——你自己写个 `java.lang.String` 放 classpath，永远不会被加载（因为 `java.lang.String` 一定是 Bootstrap 加载的）

**验证方式**：

```java
System.out.println(String.class.getClassLoader());          // null ← Bootstrap
System.out.println(com.example.Foo.class.getClassLoader()); // AppClassLoader
```

**判断"两个类是不是同一个类"的判据**：`类全名 + 加载它的 ClassLoader` 都相同。所以同一个 class 文件被两个不同 ClassLoader 加载，会产生两个互不兼容的 `Class` 对象，`instanceof` / `equals` 都会失败。**这是热修复、插件化、Tomcat 多应用隔离的技术基础。**


### 5.4 破坏双亲委派：什么时候需要

三种典型破坏：

1. **SPI / JDBC**：`java.sql.Driver` 在 `rt.jar`（Bootstrap 加载），但具体驱动（`com.mysql.cj.jdbc.Driver`）在 classpath。Bootstrap 的加载器看不到 classpath，怎么办？→ **线程上下文类加载器（Thread Context ClassLoader）**：

```java
Thread.currentThread().setContextClassLoader(myLoader);
// 之后 ServiceLoader.load(Driver.class) 会用它来加载实现类
```

这是"**父加载器请求子加载器**"的反向委派，也是面试题"为什么需要 TCCL"的标准答案。

1. **热部署 / OSGi**：每个模块一个 ClassLoader，实现类的热替换。要替换类就得换 ClassLoader（因为类加载后不能卸载单个类，只能卸载整个 ClassLoader）。
2. **Android 的 ClassLoader 体系**（见下）。

**Android 视角**（这块你天天在用）：

```
BootClassLoader（加载 framework 的 /system/framework/*.jar，即 android.* 类）
      ↑
PathClassLoader  ← 加载 APK 自己的 dex、/data/app/xxx/base.apk
      ↑
DexClassLoader   ← 可指定任意 dex/jar 路径，是插件化、热修复的基础
```

- Android 的"双亲委派"是**实现过的**：`BaseDexClassLoader.findClass` 先看 `parent`，再找自己的 dexElements。
- **热修复的原理**：通过反射把补丁 dex 插到 `dexElements` 数组的**最前面**，这样查找时先命中补丁类（同时也解释了为什么 Android 10+ 后这条路越来越难走，`hidden_api_policy` 收紧了）。
- **`PathClassLoader` vs `DexClassLoader`**：唯一差别是 `DexClassLoader` 多了 `optimizedDirectory` 参数（Android 8 后此参数已无效），所以"哪个能加载外部 dex"这个老说法现在已经不成立了——**两者都能，区别主要在图省事**。

### 5.5 ClassLoader 泄漏：Android 上最经典的泄漏

```java
// ❌ 非静态内部类 / 匿名内部类持有外部类的引用
public class MainActivity extends Activity {
    private static Handler handler = new Handler() {   // 静态字段持有匿名内部类实例
        @Override public void handleMessage(Message msg) { /* 隐式持有 MainActivity.this */ }
    };
}
```

**为什么这算"类加载器泄漏"**：`Activity` 的 `Class` 对象被它的 `ClassLoader`（`PathClassLoader`）持有，`ClassLoader` 持有该 APK 里**所有** `Class` 对象 → 只要有一个 `Class` 对象活着，**整个 APK 的类都卸不掉**，连带所有静态字段、资源引用。

**排查命令**：

```bash
# 抓 hprof
adb shell am dumpheap com.example.app /data/local/tmp/dump.hprof
adb pull /data/local/tmp/dump.hprof
# 用 MAT / LeakCanary 分析，搜 "ClassLoader" 看 PathClassLoader 是否被意外持有
```

**这条值得记**：**Android 上的内存泄漏，从"类加载器"这个高度看才看得清全貌**——单个 Activity 泄漏之所以严重，正是因为它拖着整个 ClassLoader 的资源。

### 5.6 Kotlin 对照

Kotlin 里基本不需要碰 ClassLoader，唯一要注意的是：

```kotlin
// 反射/序列化库在 R8 混淆后会因为反射找不到类而失败
// → 需要 keep 规则，或改用 KSP / 代码生成
```

---

## 第 6 章 equals / hashCode / Comparable 契约

### 6.1 回忆钩子

| 问题                                                              | 小节  |
| --------------------------------------------------------------- | --- |
| `equals` 必须满足的五个性质？                                             | 6.2 |
| `hashCode` 和 `equals` 的"互推关系"到底是什么方向？                           | 6.3 |
| `a.equals(b)` 为 true，`a.hashCode()` 一定等于 `b.hashCode()` 吗？反过来呢？ | 6.3 |
| `Comparable` 和 `Comparator` 各是什么？                               | 6.5 |
| 为什么放进 `HashSet` 后改字段会导致"找不到了"？                                  | 6.4 |
| `Arrays.asList()` 返回的东西能 add 吗？                                 | 7.4 |


### 6.2 `equals` 的五条契约

来自 `Object.equals` 的 javadoc，任何实现都必须满足：

| 性质                 | 含义                                 |
| ------------------ | ---------------------------------- |
| **自反性** Reflexive  | `x.equals(x)` 必须 true              |
| **对称性** Symmetric  | `x.equals(y)` 与 `y.equals(x)` 必须一致 |
| **传递性** Transitive | `x=y`、`y=z` ⇒ `x=z`                |
| **一致性** Consistent | 只要比较用到的信息没变，多次调用结果不变               |
| **非空性**            | `x.equals(null)` 必须 false（不能抛 NPE） |

**对称性最经典的破坏案例**：

```java
// 假设 Point 有 x,y；ColorPoint extends Point 加了 color
@Override public boolean equals(Object o) {
    if (!(o instanceof ColorPoint cp)) return false;
    return super.equals(o) && cp.color.equals(color);
}
// 结果：point.equals(colorPoint) 可能 true，反向 false → 违反对称性
```

标准修复手法是**用 `getClass()` 代替 `instanceof`**（代价是丧失跨子类的相等性），或者用**组合代替继承**。这条在书里叫"复合优先于继承"。

**`Objects.equals` 快速写法**（现代写法，不用手写 NPE 判断）：

```java
@Override
public boolean equals(Object o) {
    if (this == o) return true;                       // 1. 同一引用，短路（性能关键）
    if (o == null || getClass() != o.getClass()) return false;  // 2. null 和类型
    User user = (User) o;
    return age == user.age                              // 3. 逐字段
            && Objects.equals(name, user.name);
}
```


### 6.3 `hashCode` 契约：方向别搞反

**javadoc 里的两条**：

1. 如果 `a.equals(b)` 为 true，那么 **`a.hashCode()` 必须等于 `b.hashCode()`**
2. 如果 `a.equals(b)` 为 false，**`a.hashCode()` 可以相等也可以不等**（但不相等能提升哈希表性能）

**记忆方向**：**"相等则哈希必等"，反过来不成立。**

由此推出三个"不算错但要避免"的情况：

| 情况                         | 是否违反契约     | 后果                                         |
| -------------------------- | ---------- | ------------------------------------------ |
| `equals` 相等、`hashCode` 不等  | ❌ **严重违反** | `HashMap` / `HashSet` 直接失效（同一个 key 存在多个位置） |
| `equals` 不等、`hashCode` 相等  | ✅ 合法       | 全部落到同一个桶，退化为链表，性能从 O(1) 到 O(n)             |
| 只覆写 `equals` 忘了 `hashCode` | ❌ 常见错误     | 两个"相等"的对象 `get` 不到                         |

```java
// ❌ 只写了 equals
class Key {
    String id;
    @Override public boolean equals(Object o) { /* ... */ }
    // 忘了 hashCode → 默认的 Object.hashCode 是地址相关，两个"相等"的对象哈希不同
}
Set<Key> set = new HashSet<>();
set.add(new Key("a"));
set.contains(new Key("a"));   // false ← 明明 equals 相等
```

**`hashCode` 实现要点**（现在一般不用手写，IDEA 生成或 `record` 自动）：

```java
@Override
public int hashCode() {
    return Objects.hash(name, age);   // 便捷写法；缺点是每次装箱 + 创建数组，热点代码里手写 31 乘法更快
    // 传统手写版：
    // int result = name != null ? name.hashCode() : 0;
    // result = 31 * result + age;
    // return result;
}
```

**为什么是 31？** 31 是奇素数，`31 * i == (i << 5) - i` 可以被 JIT 优化成移位减法；且 31 够小不易溢出、够大不易碰撞。这是"约定俗成都用 31"的由来。

### 6.4 可变对象做 key：最隐蔽的坑

```java
Set<Key> set = new HashSet<>();
Key k = new Key("a", 1);
set.add(k);
k.setId("b");                  // 改了参与 hashCode 的字段
set.contains(k);               // false ← 哈希值变了，落在不同的桶里
set.remove(k);                 // 也删不掉，永久泄漏在集合里
```

**结论**：**放进哈希容器（`HashSet`/`HashMap`）的对象，其参与 `hashCode` 的字段必须不可变。** 这就是为什么 `String`、包装类、`record` 适合做 key。

**Kotlin 视角**：`data class` 只把**主构造器参数**纳入 `equals`/`hashCode`，所以：

```kotlin
data class Key(var id: String)     // ⚠️ id 是 var，放进 Set 后修改会踩同样的坑
data class Key(val id: String)     // ✅ 推荐
```

### 6.5 `Comparable` vs `Comparator`

|    | `Comparable<T>`                       | `Comparator<T>`         |
| -- | ------------------------------------- | ----------------------- |
| 包  | `java.lang`                           | `java.util`             |
| 方法 | `int compareTo(T o)`                  | `int compare(T a, T b)` |
| 位置 | **比较逻辑写在元素类里**（自然排序）                  | **比较逻辑写在外部**（策略模式）      |
| 优点 | 简单，`TreeMap` 自动可用                     | 可定义多套排序、不改原类、符合开闭原则     |
| 契约 | 建议与 `equals` 一致（不一致时 `TreeSet` 会表现怪异） | ——                      |

```java
// Comparable：自然排序
class User implements Comparable<User> {
    @Override public int compareTo(User o) { return Integer.compare(age, o.age); }
}

// Comparator：外部策略（现代写法，链式 + 静态工厂）
users.sort(Comparator
        .comparingInt(User::getAge)
        .thenComparing(User::getName, Comparator.reverseOrder()));
```

**返回值语义**：负数=前小、0=相等、正数=前大。**不要用 `a - b`**（整数溢出）：

```java
// ❌ 经典 bug：a = Integer.MIN_VALUE, b = 1 时溢出
return a - b;
// ✅
return Integer.compare(a, b);
```

**`TreeMap`/`TreeSet` 的坑**：它们用 `compareTo` 判等，**不用 `equals`**。所以如果 `compareTo` 返回 0 但 `equals` 为 false，`TreeSet` 会认为"重复"，直接丢弃元素。

### 6.6 Kotlin 对照

| Java                   | Kotlin                                         |
| ---------------------- | ---------------------------------------------- |
| 手写 `equals`/`hashCode` | `data class` 自动生成                              |
| `Objects.equals(a, b)` | `a == b`（自动空安全，调 `equals`）                     |
| 引用比较 `==`              | `===`                                          |
| `Arrays.equals(a, b)`  | `a.contentEquals(b)`（数组的 `==` 在 Kotlin 也是引用比较） |
| `Comparator`           | `compareBy { }` / `sortedBy { }`               |

**注意**：Kotlin 里数组的 `==` 也是**引用比较**（和 Java 一样踩坑），必须用 `contentEquals` / `contentDeepEquals`。

---

## 第 7 章 集合框架里真正容易记错的细节

### 7.1 回忆钩子

| 问题                                           | 小节  |
| -------------------------------------------- | --- |
| `HashMap` 容量为什么是 2 的幂？                       | 7.2 |
| 树化阈值 8 和退化阈值 6 为什么不一样？                       | 7.2 |
| `HashMap` 扩容时元素怎么迁移？                         | 7.2 |
| `ConcurrentHashMap` 从 Java 7 到 8 改了什么？为什么？   | 7.3 |
| `Arrays.asList()` 能 add 吗？`List.of()` 呢？     | 7.4 |
| `subList` 和原 list 是什么关系？                     | 7.4 |
| `Iterator.remove()` 和 `list.remove()` 有什么区别？ | 7.5 |


### 7.2 HashMap：容量、树化、扩容

**（1）容量为什么必须是 2 的幂**

因为计算桶下标用的是**位运算**而不是取模：

```java
// JDK 代码
int index = (n - 1) & hash;   // n 是容量
//        ^^^^^^^^^^^^^^^^ 
// 当 n 是 2 的幂时，(n-1) 的二进制是全 1（如 15 = 1111），
// 这等价于 hash % n，但快得多，且不会因为负数取模出问题
```

**顺带解释了**：`tableSizeFor(int cap)` 会把任意容量向上取整到最近的 2 的幂（`new HashMap<>(10)` 实际容量是 16）。也解释了为什么扩容是"翻倍"——翻倍后仍是 2 的幂。

**（2）hash 扰动函数**

```java
static final int hash(Object key) {
    int h;
    return (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> 16);
    //                                        ^^^^^^^^^^^^^^^
    // 高 16 位异或到低 16 位，让高位也参与下标计算
}
```

**为什么要扰动**：下标只取低几位（`(n-1) & hash`，n 小时只用到低 4-5 位）。如果 key 的 `hashCode` 只有高位有差异、低位相同，就会全部撞到一个桶。把高位"折"到低位能显著减少碰撞。

**（3）树化与退化阈值**

| 参数                            | 值  | 说明                             |
| ----------------------------- | -- | ------------------------------ |
| 链表转红黑树 `TREEIFY_THRESHOLD`    | 8  | 单个桶里链表长度 ≥ 8 且**表容量 ≥ 64** 才树化 |
| 红黑树退化 `UNTREEIFY_THRESHOLD`   | 6  | 树节点数 ≤ 6 时退回链表                 |
| 最小树化容量 `MIN_TREEIFY_CAPACITY` | 64 | 容量不足时**先扩容**而不是树化              |

**"为什么 8 和 6 之间留一个空档"**（这是高频考点）：如果阈值都是 7，那么长度在 7 上下浮动时会发生"链表↔树"的反复转换，代价很高。**留一个缓冲带（7 是死区）避免抖动。** 这是哈希碰撞频率近似泊松分布下 ≥8 概率约 0.00000006 的统计结果——正常情况下几乎不会树化。

**（4）扩容时的元素迁移（JDK 8 的巧妙之处）**

JDK 8 利用"容量翻倍 = 多了一位"的性质，**不需要重新计算 hash**：

```java
// 老下标：index = hash & (oldCap - 1)
// 新下标：index = hash & (newCap - 1) = hash & (2*oldCap - 1)
// 差别只在"新多出来的那一位"：
//   if ((e.hash & oldCap) == 0)  → 留在原位
//   else                          → 移到 "原位 + oldCap"
```

所以扩容变成"把每个桶的链表拆成两条"（lo 链和 hi 链），而不是逐个重算。JDK 7 用的是头插法（多线程下会形成环形链表导致死循环），**JDK 8 改成尾插法**——这也是"为什么 JDK 8 的 HashMap 不会死循环"的原因（但仍不是线程安全的）。

**（5）JDK 8 的具体变化汇总**

| 项    | JDK 7     | JDK 8                                     |
| ---- | --------- | ----------------------------------------- |
| 冲突处理 | 数组 + 链表   | 数组 + 链表 + **红黑树**                         |
| 插入方式 | 头插法       | **尾插法**（避免并发环形链表）                         |
| 扩容迁移 | 逐个 rehash | 用 `(hash & oldCap)` 判断，**原位置或原位置+oldCap** |
| 最坏查找 | O(n)      | **O(log n)**                              |


### 7.3 ConcurrentHashMap 的演进

**这是"面试必问 + 实际写并发代码必懂"的一块。**

|        | Java 7                            | Java 8+                                       |
| ------ | --------------------------------- | --------------------------------------------- |
| 结构     | **Segment 数组 + HashEntry 数组**，分段锁 | 数组 + 链表/红黑树                                   |
| 锁粒度    | 段（默认 16 段，等于最多 16 个线程并发写）         | **CAS + synchronized 锁单个桶头节点**                |
| 并发度    | 固定为 segment 数                     | 等于桶数量（更高）                                     |
| 扩容     | 每个 Segment 独立扩容                   | **多线程协助扩容（`transfer` + `sizeCtl` 标记）**        |
| size() | 累加各 Segment 的 count               | `baseCount` + `CounterCell[]`（类似 `LongAdder`） |
| 空值     | 不允许 null key/value                | 同左                                            |

**关键变化的原因**：Segment 分段锁的并发度上限被固定死在 16，桶数量却可以远大于 16。JDK 8 改成"锁桶头"后，并发度随容量增长，且避免了 Segment 这一层间接。

**几个值得记住的实现细节**：

- **`sizeCtl` 一个字段身兼数职**：负数表示正在初始化/扩容（值是扩容标记），正数表示下次扩容阈值。
- **扩容是"协作式"的**：正在扩容时，其他写线程会先帮忙迁移 `transfer`，再处理自己的操作。
- **读操作完全无锁**：`get` 只依赖 `volatile` 保证的可见性（`Node.val` 和 `Node.next` 都是 `volatile`）。
- **`putVal` 对空桶用 CAS，对非空桶用 `synchronized (f)`**：`f` 是桶的头节点。

```java
// 只读一次就能看出设计思路（简化）
final V putVal(K key, V value, boolean onlyIfAbsent) {
    if (key == null || value == null) throw new NullPointerException();  // 不允许 null
    int hash = spread(key.hashCode());
    for (Node<K,V>[] tab = table;;) {
        Node<K,V> f; int n, i, fh;
        if (tab == null || (n = tab.length) == 0) tab = initTable();
        else if ((f = tabAt(tab, i = (n - 1) & hash)) == null) {
            if (casTabAt(tab, i, null, new Node<K,V>(hash, key, value)))
                break;                                   // ← 空桶：无锁 CAS
        }
        else if ((fh = f.hash) == MOVED) tab = helpTransfer(tab, f);  // ← 协助扩容
        else {
            synchronized (f) { /* ← 非空桶：只锁头节点 */ }
        }
    }
}
```

**注意根因**：`ConcurrentHashMap` 不允许 `null`，而 `HashMap` 允许。**原因**：并发场景下 `get` 返回 `null` 无法区分"key 不存在"和"value 就是 null"，无法用 `containsKey` 二次确认（因为两次调用之间可能被改）。这是个很好的"为什么这么设计"的案例。


### 7.4 那些"返回视图"的坑

**`Arrays.asList` 的三个限制**：

```java
List<String> list = Arrays.asList("a", "b");
list.set(0, "x");            // ✅ 可以改元素
list.add("c");               // ❌ UnsupportedOperationException（固定大小）
list.remove(0);              // ❌ UnsupportedOperationException
// 原因：返回的是 Arrays 内部的静态类 ArrayList（不是 java.util.ArrayList），
//       底层直接指向原数组，所以没有 add/remove 的实现
```

**修正方式**：

```java
List<String> mutable = new ArrayList<>(Arrays.asList("a", "b"));  // ✅ 真正的可变副本
List<String> immutable = List.of("a", "b");                        // ✅ Java 9+，不可变（见下）
```

**`List.of` / `Map.of` / `Set.of`（Java 9+）** 是**完全不可变**的：

```java
List.of("a").add("b");        // ❌ UnsupportedOperationException
List.of("a", null);           // ❌ NullPointerException（不允许 null）
Map.of("k1", 1, "k2", 2);     // 最多 10 对；更多要用 Map.ofEntries(...)
```

**`subList` 返回的是视图，不是副本**（这是超级大坑）：

```java
List<String> list = new ArrayList<>(List.of("a", "b", "c", "d"));
List<String> sub = list.subList(1, 3);      // ["b", "c"]，是视图
sub.set(0, "X");                            // 原 list 也变成 ["a", "X", "c", "d"]
list.add("e");                              // ⚠️ 结构性修改后
sub.get(0);                                 // ❌ ConcurrentModificationException
```

**结论**：`subList` 只在"立即用完就丢"的场景安全。要持久持有请 `new ArrayList<>(list.subList(...))`。

**`Iterators` 与 fail-fast**：

```java
for (String s : list) {
    if (s.equals("b")) list.remove(s);      // ❌ ConcurrentModificationException
}
// 原因：增强 for 底层是 Iterator，ArrayList.remove 会 modCount++，
//       而 Iterator.next() 会检查 modCount != expectedModCount
```

正确做法：

```java
// 1. 用 Iterator.remove()（它会同步更新 expectedModCount）
Iterator<String> it = list.iterator();
while (it.hasNext()) { if (it.next().equals("b")) it.remove(); }

// 2. Java 8+ removeIf（推荐，最简洁）
list.removeIf("b"::equals);

// 3. 反过来遍历（只对"按索引删"有效）
for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).equals("b")) list.remove(i);
```

**注意 `modCount` 是"尽力而为"的**：`ConcurrentModificationException` 不保证一定能检出（多线程下不可靠），它是**bug 检测机制而不是并发控制机制**。想并发就用 `CopyOnWriteArrayList` 或 `ConcurrentHashMap`。


### 7.5 集合选型速查

| 需求       | 选择                                                                                   | 关键特性                            |
| -------- | ------------------------------------------------------------------------------------ | ------------------------------- |
| 一般键值存储   | `HashMap`                                                                            | 非线程安全，允许 null                   |
| 并发键值存储   | `ConcurrentHashMap`                                                                  | 不允许 null，高并发                    |
| 并发 + 有序  | `ConcurrentSkipListMap`                                                              | 基于跳表，O(log n)                   |
| 读多写极少    | `CopyOnWriteArrayList`                                                               | 写时复制整个数组，写代价高、读无锁               |
| 需要保留插入顺序 | `LinkedHashMap`                                                                      | 也可做 LRU（覆写 `removeEldestEntry`） |
| 需要排序     | `TreeMap`                                                                            | 红黑树，O(log n)，靠 `compareTo` 判等   |
| 高频计数/累加  | `LongAdder`                                                                          | 比 `AtomicLong` 更快（分散热点）         |
| 无重复 + 去重 | `HashSet` / `LinkedHashSet`                                                          | 基于对应 Map 的 key                  |
| 队列（并发）   | `ArrayBlockingQueue`（有界）/ `LinkedBlockingQueue`（默认 `Integer.MAX_VALUE`，**注意这是无界近似**） | 生产者消费者                          |
| 延时队列     | `DelayQueue`                                                                         | 定时任务                            |
| 线程间传递    | `SynchronousQueue`                                                                   | 无容量，直接交接                        |

**LRU 缓存的最小实现**（`LinkedHashMap` 的经典用法，值得记住因为工作里常要）：

```java
public class LruCache<K, V> extends LinkedHashMap<K, V> {
    private final int capacity;

    public LruCache(int capacity) {
        super(capacity, 0.75f, true);   // accessOrder=true ← 关键：按访问顺序排序
        this.capacity = capacity;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > capacity;       // 超容量时自动淘汰最久未访问的
    }
}
```

**`LinkedHashMap(capacity, loadFactor, accessOrder)`**：第三个参数为 `true` 时是**访问顺序**（get 也会把节点移到链表尾），这正是 LRU 所需；默认 `false` 是插入顺序。

### 7.6 Kotlin 对照

| Java                              | Kotlin                                        |
| --------------------------------- | --------------------------------------------- |
| `List.of(...)`                    | `listOf(...)`（不可变接口，但底层可能是可变实现）               |
| `new ArrayList<>()`               | `mutableListOf()` 或 `arrayListOf()`           |
| `Collections.emptyList()`         | `emptyList()`                                 |
| `Collections.unmodifiableList(l)` | `l.toList()`（**产生副本**，不是视图！）                  |
| `Arrays.asList(...)`              | `listOf(...)` / `array.toList()`              |
| 类型 `List<T>`                      | `List<T>` 只读 / `MutableList<T>` 可变（**编译期区分**） |

**一个容易记错的 Kotlin 差异**：`Collections.unmodifiableList` 是**视图**（原集合变了它也变），而 Kotlin 的 `toList()` 是**副本**。语义不同，迁移代码时要注意。

---

## 第 8 章 String、不可变与常量池

### 8.1 回忆钩子

| 问题                                        | 小节  |
| ----------------------------------------- | --- |
| `new String("a") == "a"` 是 true 还是 false？ | 8.3 |
| `intern()` 现在还有什么用？                       | 8.3 |
| `String` 为什么不可变？不可变有什么好处？                 | 8.2 |
| 字符串拼接在循环里为什么慢？                            | 8.4 |
| `String` 底层从 `char[]` 变成 `byte[]` 是哪个版本？  | 8.5 |

### 8.2 不可变的实现与收益

```java
public final class String implements java.io.Serializable, Comparable<String>, CharSequence {
    private final byte[] value;      // JDK 9+ 是 byte[] + coder；JDK 8 是 char[]
    private final byte coder;        // 0 = LATIN1（单字节），1 = UTF16
    private int hash;                // 缓存 hashCode，第一次算完就存下来
}
```

**三重不可变**：类 `final`（不能被继承覆写）、字段 `final`（赋值后不能改）、`value` 数组**不对外暴露**（没有任何方法返回它，构造时如果传入数组会 `Arrays.copyOf`）。

**不可变带来的收益**（这四条值得记住，因为解释了为什么这么多设计依赖它）：

| 收益               | 说明                                       |
| ---------------- | ---------------------------------------- |
| **线程安全**         | 无状态可改，天然可安全共享，不需要同步                      |
| **可缓存 hashCode** | 哈希值算一次即可，使 `String` 成为 `HashMap` 的理想 key |
| **常量池可共享**       | 相同字面量可指向同一个对象，省内存                        |
| **安全**           | 类名、文件路径、URL、SQL 不会被中途改掉（否则可能被提权/注入）      |

**为什么 `String` 的 hashCode 字段不是 final**？因为它**懒计算**（首次调用才算并缓存），这是"不可变对象也可以有非 final 的缓存字段"的标准案例（只要该字段对逻辑语义透明）。


### 8.3 常量池与 `intern()`

```java
String a = "abc";              // 字面量 → 常量池
String b = "abc";              // 复用常量池中的同一个对象
String c = new String("abc");  // 堆上新对象（但 "abc" 字面量仍在常量池）

System.out.println(a == b);            // true
System.out.println(a == c);            // false  ← 不同对象
System.out.println(a == c.intern());   // true   ← intern 返回常量池中的那个
System.out.println(a.equals(c));       // true
```

**编译期常量折叠**：

```java
final String A = "a";
final String B = "b";
String ab = "ab";
System.out.println((A + B) == ab);     // true  ← 编译期折叠成 "ab"，直接进常量池

String x = "a";                        // 非 final
String y = x + "b";
System.out.println(y == "ab");         // false ← 运行期用 StringBuilder 拼，堆上新对象
```

**"什么时候是编译期常量"的判据**：`static final` / `final` 修饰 + 初始化表达式本身是编译期常量表达式。这就是 2.6 节提到"改 `static final` 常量读不到新值"的同一个机制。

**JDK 版本变化（重要）**：

| 版本          | 常量池位置                | 说明                                                |
| ----------- | -------------------- | ------------------------------------------------- |
| JDK 6 及以前   | **永久代（PermGen）**     | 大量 `intern()` 会 `OutOfMemoryError: PermGen space` |
| **JDK 7 起** | **移到堆里**             | 字符串常量池随堆一起 GC，`intern` 不再撑爆永久代                    |
| JDK 8       | 永久代被元空间（Metaspace）取代 | 字符串常量池本来就在堆，不受影响                                  |

**`intern()` 的实际价值**：在**存在大量重复字符串**的场景（如解析大文件里的枚举值、日志字段名）用 `intern()` 做去重能显著省内存。但它有代价——`intern` 表是全局的，大量不同的字符串会污染它。**替代方案**：`-XX:+UseStringDeduplication`（G1 的字符串去重，更省心），或者显式用 `ConcurrentHashMap<String,String>` 做池化。

### 8.4 拼接：从 `+` 到 `invokedynamic`

**编译期**：`+` 会被编译成 `StringBuilder`（JDK 8 及以前）：

```java
String s = a + b + c;
// 编译为：new StringBuilder().append(a).append(b).append(c).toString()

// 但循环里的坑：
String result = "";
for (int i = 0; i < 10000; i++) {
    result += i;              // ❌ 每次循环 new 一个 StringBuilder → O(n²)
}
// ✅ 手动提出去
StringBuilder sb = new StringBuilder();
for (int i = 0; i < 10000; i++) sb.append(i);
```

**JDK 9 起的变化（JEP 280）**：`+` 编译成 **`invokedynamic` + `StringConcatFactory`**，运行时会生成高度优化的拼接方法，性能优于 `StringBuilder`，**而且循环外的写法效率也上去了**。不过**循环内累加仍然是 O(n²)**，还是要手动提 `StringBuilder`。

**`StringBuilder` vs `StringBuffer`**：后者所有方法 `synchronized`，几乎总该用前者。

### 8.5 内存优化：`char[]` → `byte[]`

JDK 9 引入**紧凑字符串（Compact Strings，JEP 254）**：

|              | JDK 8              | JDK 9+                        |
| ------------ | ------------------ | ----------------------------- |
| 底层           | `char[]`（每字符 2 字节） | `byte[] value` + `byte coder` |
| 纯 Latin-1 内容 | 2 字节/字符（浪费一半）      | **1 字节/字符**                   |
| 含非 Latin-1   | 2 字节/字符            | 自动切成 UTF-16（`coder=1`）        |

**收益**：大量只含 ASCII 的字符串（日志、JSON key、英文标识）堆占用减半，GC 压力下降。这是一个"用户完全无感知但效果显著"的优化，值得知道。

### 8.6 常用 API 的坑

```java
// 1. split 的参数是正则，不是字面量
"a.b.c".split(".");           // ❌ 返回空数组！"." 是正则的"任意字符"
"a.b.c".split("\\.");         // ✅ ["a","b","c"]
"a.b.c".split(Pattern.quote("."));   // ✅ 更安全的写法

// 2. split 默认丢弃末尾空串
"a,b,,".split(",");           // ["a","b"]    长度 2
"a,b,,".split(",", -1);       // ["a","b","",""]  长度 4 ← 限制参数为负数才保留

// 3. 不要用 == 比较内容（见 8.3）
// 4. substring 在 JDK 6 会共享底层 char[] 导致内存泄漏，JDK 7+ 已改为复制
String big = readHugeString();          // 1MB
String small = big.substring(0, 3);     // JDK 6: small 持有整个 1MB 数组 → 泄漏
                                        // JDK 7+: 复制出 3 个字符，无泄漏

// 5. 大小写比较要用 equalsIgnoreCase，注意 locale
"İ".toLowerCase();                      // 土耳其语的 I 问题 → 用 toLowerCase(Locale.ROOT)

// 6. 判空用 StringUtils / Kotlin 的 isNullOrBlank
```

**第 4 条特别值得记住**：这是"老代码里 `substring` 导致 OOM"的著名历史 bug，**修复版本必须记住是 JDK 7**（很多资料写得含糊）。

### 8.7 Kotlin 对照

| Java                           | Kotlin                                                                 |
| ------------------------------ | ---------------------------------------------------------------------- |
| `"abc".length()`               | `"abc".length`                                                         |
| `String.format("%s=%d", k, v)` | `"$k=$v"`（模板，编译期转 StringBuilder）                                       |
| `s.isEmpty()`                  | `s.isEmpty()` / `s.isBlank()`（**空 vs 全空白**，Kotlin 区分得更清楚）              |
| `s.split("\\.")`               | `s.split(".")`（Kotlin 的 `split(String)` 是**字面量**，要正则得用 `split(Regex)`） |
| `StringBuilder`                | 一样，或者 `buildString { }`                                                |
| `s.trim()`                     | `s.trim()` / `s.trimStart()` / `s.trimEnd()`                           |

**注意 Kotlin 的 `split` 语义和 Java 相反**——`"a.b".split(".")` 在 Kotlin 里返回 `["a","b"]`，在 Java 里返回空。跨语言移植代码时是高频 bug。

---

## 第 9 章 内部类、闭包与 Lambda

### 9.1 回忆钩子

| 问题                          | 小节  |
| --------------------------- | --- |
| 四种内部类的区别？为什么需要内部类？          | 9.2 |
| 静态内部类和普通内部类，哪个会泄漏 Activity？ | 9.3 |
| 匿名内部类里为什么必须 `final` 的局部变量？  | 9.4 |
| Lambda 和匿名内部类有什么本质区别？       | 9.5 |
| `this` 在这三者里分别指谁？           | 9.6 |

### 9.2 四种内部类

| 类型             | 声明                                       | 依赖外部实例 | 能访问外部私有成员 | 能有静态成员         |
| -------------- | ---------------------------------------- | ------ | --------- | -------------- |
| **成员内部类**（非静态） | `class Outer { class Inner {} }`         | ✅ 是    | ✅         | ❌ (Java 16+ ✅) |
| **静态嵌套类**      | `class Outer { static class Nested {} }` | ❌ 否    | ✅         | ✅              |
| **局部内部类**      | 方法体内定义                                   | ✅      | ✅         | ❌              |
| **匿名内部类**      | `new Interface() { ... }`                | ✅      | ✅         | ❌              |
| （Lambda）       | `() -> {}`                               | 视捕获而定  | —         | ❌              |

**编译产物文件名**（排错时会看到）：

```
Outer.class
Outer$Inner.class          ← 成员内部类
Outer$Nested.class         ← 静态嵌套类（名字上没区别！）
Outer$1.class              ← 第一个匿名内部类
Outer$1Local.class         ← 局部内部类
```

**语法上的"外部实例引用"**：

```java
Outer outer = new Outer();
Outer.Inner inner = outer.new Inner();       // 必须先有外部实例（注意这个 new 的位置）
Outer.Nested nested = new Outer.Nested();    // 静态嵌套类不需要外部实例
```

**"为什么需要内部类"**：三个理由——① 逻辑上从属（`Map.Entry`）；② 实现多重继承的效果（一个类可以有多个内部类分别继承不同类）；③ 见 9.3 的隐式引用。


### 9.3 隐式引用：Android 泄漏的头号来源

**非静态内部类会持有一个隐藏的 `this$0` 字段指向外部实例：**

```java
public class Outer {
    private int x = 1;
    class Inner {
        void f() { System.out.println(x); }   // 这里的 x 是 Outer.this.x
    }
}

// 编译后（概念上）
public class Outer$Inner {
    final Outer this$0;                       // ← 编译器自动加的外围实例引用
    Outer$Inner(Outer outer) { this.this$0 = outer; }
    void f() { System.out.println(this$0.x); }
}
```

**Android 上的经典泄漏**：

```java
// ❌ 泄漏：静态字段（生命周期无限）持有非静态内部类实例 → 持有 Activity
public class MyActivity extends Activity {
    private static Handler handler = new Handler() {              // 匿名内部类
        @Override public void handleMessage(Message m) { /* 用 Activity 的东西 */ }
    };
    private static Runnable task = new Runnable() {               // 同样泄漏
        @Override public void run() { }
    };
}

// ✅ 修复 1：改成静态嵌套类 + WeakReference
public class MyActivity extends Activity {
    private static class SafeHandler extends Handler {
        private final WeakReference<MyActivity> ref;
        SafeHandler(MyActivity a) { ref = new WeakReference<>(a); }
        @Override public void handleMessage(Message m) {
            MyActivity a = ref.get();
            if (a == null) return;
            // ...
        }
    }
}
```

**判据**：**"静态字段 + 非静态内部类" 这个组合就是泄漏信号**（因为静态字段活到进程结束，它间接钉住了外部实例）。

**Kotlin 侧的对应物**：

| Java  | Kotlin                | 是否持有外部实例 |
| ----- | --------------------- | -------- |
| 成员内部类 | `inner class`         | ✅ 持有     |
| 静态嵌套类 | `class`（嵌套类，默认就是静态语义） | ❌ 不持有    |

**注意 Kotlin 的默认相反**：Kotlin 里嵌套类默认不持有外部引用，要持有必须显式加 `inner`。**这是 Kotlin 更安全的一个设计**，所以到 Kotlin 后这类泄漏少了很多。

### 9.4 匿名内部类为什么要 `final` 局部变量

```java
void f() {
    int x = 1;                       // Java 8 起可以省略 final，但必须"effectively final"
    Runnable r = new Runnable() {
        @Override public void run() { System.out.println(x); }   // 捕获 x
    };
    // x = 2;   // ❌ 编译错误：被捕获的变量必须有效 final
}
```

**原因**：局部变量在**栈**上，匿名内部类对象的生命周期可能**超出方法调用**。编译器没法让内部类持有栈上的变量，于是采用**值拷贝**：把 `x` 的值复制成内部类的一个字段（`val$x`）。既然是拷贝，两边不同步就会产生歧义——所以干脆规定"不能改"。

```java
// 编译后（概念上）
class Outer$1 implements Runnable {
    private final int val$x;          // ← 拷贝快照
    Outer$1(int x) { this.val$x = x; }
    public void run() { System.out.println(this.val$x); }
}
```

**验证命令**：

```bash
javap -p Outer.class | grep 'val\$'      # 看到编译器生成的 $ 字段
```

**Kotlin 里没有这个限制**（Kotlin 用 `Ref` 包装对象来捕获可变变量），而且 Kotlin 的 `val`/`var` 语义本身就是"捕获时拷贝引用"。**但是**：Kotlin 里捕获 `var` 会有性能开销（多一层 `Ref` 对象），高频路径上要留意。


### 9.5 Lambda vs 匿名内部类：本质区别

这是 Java 8 最重要的变化，两个"看起来一样"的写法实际上机制完全不同：

|                 | 匿名内部类                    | Lambda                                                           |
| --------------- | ------------------------ | ---------------------------------------------------------------- |
| 编译产物            | 一个真实的 `Outer$1.class` 文件 | 通常**不生成**独立 class（用 `invokedynamic` + `LambdaMetafactory` 运行期生成） |
| 一个 `this`       | 有，`this` 指内部类实例          | **没有自己的 `this`**，`this` 指外围类的实例                                  |
| 能否实现多方法接口       | ✅                        | ❌ 只能是函数式接口（单抽象方法）                                                |
| 能否有字段/状态        | ✅                        | ❌                                                                |
| 实例化开销           | 每次 `new` 都创建对象           | 无状态 lambda 通常**只创建一次并缓存**（可作为单例复用）                               |
| 反射 `getClass()` | `Outer$1`                | `Outer$$Lambda$1/0x...`（名字带随机数）                                  |
| 序列化             | 可以                       | 需要函数式接口继承 `Serializable`，且实际少用                                   |

```java
// ❌ 匿名内部类里 this 是内部类实例
executor.submit(new Runnable() {
    @Override public void run() { System.out.println(this); }   // Outer$1
});

// ✅ lambda 里 this 是外围实例
executor.submit(() -> System.out.println(this));                // Outer
```

**这个"`this` 归属不同"是最容易踩的坑**——尤其写 Android 的 `View.OnClickListener` 时，用匿名内部类的 `this` 和用 lambda 的 `this` 完全不是一个东西。

**"无状态 lambda 会被缓存"的实证**：

```java
Runnable a = () -> {};
Runnable b = () -> {};
System.out.println(a == b);        // true！同一个缓存实例
// 原因：不捕获任何变量的 lambda 被编译成静态方法 + 启动时缓存一个实例
```

**如果 lambda 捕获了变量**，每次执行到那行代码都会创建一个新的实例（因为要携带捕获值）。这也&#x662F;**"lambda 里别乱捕获"**&#x8FD9;个性能建议的来源：**在循环/热路径里创建捕获型 lambda 会产生大量短命对象**。

### 9.6 `this` 的三种身份（一张表理清）

| 写法         | `this` 指                     |
| ---------- | ---------------------------- |
| 匿名内部类内     | 匿名内部类实例（Java 里唯一的"内部类 this"） |
| 匿名内部类内要指外部 | `Outer.this`                 |
| lambda 内   | **外围类实例**（lambda 没有自己的 this） |
| 成员内部类内     | 内部类实例；`Outer.this` 取外部       |

**记忆**：**只有"类"才有 `this`，lambda 不是类，所以没有 `this`。**

### 9.7 Kotlin 对照

| Java                          | Kotlin                                            |
| ----------------------------- | ------------------------------------------------- |
| `new Runnable() { run() {} }` | `Runnable { }`（SAM 转换，**仅对 Java 接口**）             |
| 捕获局部变量需 effectively final     | `var` 也能捕获（编译器包一层 `Ref`）                          |
| 匿名内部类 `this` 指内部类             | `object : X { }` 里 `this` 指该匿名对象；`this@Outer` 取外部 |
| 成员内部类                         | `inner class`                                     |
| 静态嵌套类                         | `class`                                           |
| —                             | 函数类型的 `this` 与接收者：`{ this@Outer }`、带接收者的 lambda   |

**Kotlin 的两个便利点**：① Kotlin 的 lambda 对 Java 接口自动做 SAM 转换（写 `Runnable { }`）；② Kotlin 自己的函数类型不是 SAM，直接是 `FunctionN` 对象。

---

## 第 10 章 异常体系

### 10.1 回忆钩子

| 问题                                     | 小节   |
| -------------------------------------- | ---- |
| `Error` 和 `Exception` 的分界？             | 10.2 |
| 受检异常到底是好设计还是坏设计？                       | 10.3 |
| `try-with-resources` 里 close 抛异常会怎么处理？ | 10.4 |
| `finally` 里 return 会发生什么？              | 10.5 |
| 异常为什么慢？                                | 10.6 |


### 10.2 体系

```
Throwable
├── Error                        ← 程序不该处理的严重问题
│   ├── OutOfMemoryError
│   ├── StackOverflowError
│   ├── NoClassDefFoundError
│   └── AssertionError
└── Exception
    ├── RuntimeException         ← 非受检（unchecked）
    │   ├── NullPointerException
    │   ├── ClassCastException
    │   ├── ArithmeticException
    │   ├── IllegalArgumentException
    │   ├── IndexOutOfBoundsException
    │   └── IllegalStateException
    └── 其他                     ← 受检（checked），必须处理或声明
        ├── IOException
        ├── SQLException
        ├── ClassNotFoundException
        └── InterruptedException
```

**分界线**：`RuntimeException` 及其子类 + `Error` 及其子类是**非受检**；`Throwable` 的其他子类是**受检**。

**`NoClassDefFoundError` vs `ClassNotFoundException`**（Android 上经常遇到，值得单独记）：

|      | `ClassNotFoundException`                  | `NoClassDefFoundError`             |
| ---- | ----------------------------------------- | ---------------------------------- |
| 类型   | 受检异常                                      | `Error`                            |
| 时机   | **首次加载**失败（`Class.forName` / `loadClass`） | 首次成功、**再次加载**时失败（类初始化失败后被缓存为错误）    |
| 常见原因 | 依赖缺失、类名写错、ClassLoader 不对                  | 静态初始化块抛过异常、多 dex 缺失、**R8 混淆把类裁掉了** |

**Android 视角**：`NoClassDefFoundError` 在 Android 上极常见，两大原因：① 方法数超限导致 multidex 未生效（主 dex 里没有这个类）；② R8/ProGuard 把反射用到的类裁掉或混淆了（需要 `keep` 规则）。**排查第一步是看 APK 里有没有这个类**：

```bash
adb shell pm path com.example.app                  # 拿到 base.apk 路径
adb pull <base.apk> && unzip -l base.apk | grep classes   # 看 dex 数量
# 用 dexdump / jadx 反查类是否真的在某个 dex 里
```

### 10.3 受检异常之争

**受检异常的初衷**：让编译器强制你处理"可恢复的外部故障"（IO、网络、SQL）。

**为什么现代主流框架都抛弃它**：

| 问题             | 说明                                                                          |
| -------------- | --------------------------------------------------------------------------- |
| **破坏函数式风格**    | `Supplier` / `Function` / `Stream` 的签名不含受检异常，导致 lambda 里不能直接抛 `IOException` |
| **诱发空 catch**  | 大量 `try { } catch (Exception e) { }`，比不写还危险                                 |
| **接口污染**       | 底层加一个 `throws`，整条调用链都要改签名                                                   |
| **不该重试的也强制处理** | `SQLException` 里大部分是不可恢复的                                                   |

**现代替代方案**：把受检异常包成非受检（`UncheckedIOException`、`RuntimeException`），或者干脆用 Kotlin（**Kotlin 没有受检异常**，全部等同 unchecked）。

**Kotlin 的取舍**：Kotlin 完全不要受检异常——编译器不强制 `try/catch`，也不要求 `throws`。这个决定在"写业务代码"上体验很好，代价是"忘了处理 IO 异常"的 bug 要到运行时才暴露。

**实际建议（工作里的取舍）**：

- 自己写的新模块 → 用非受检异常（`IllegalArgumentException` / `IllegalStateException` 包住根因）
- 必须与老接口对接时 → 受检异常用 `throw new RuntimeException("...", e)` 包装并**保留 cause**
- **永远不要空 catch**（至少 `log` + `rethrow` 或注释说明为什么忽略）


### 10.4 `try-with-resources`

```java
// Java 7+ 的写法：资源自动关闭（按声明的逆序）
try (InputStream in = new FileInputStream(path);
     OutputStream out = new FileOutputStream(dst)) {
    in.transferTo(out);
} catch (IOException e) {
    // ...
}

// 旧写法（容易写错的 finally 版）
InputStream in = null;
try {
    in = new FileInputStream(path);
    // ...
} finally {
    if (in != null) {
        try { in.close(); } catch (IOException ignored) { }   // ← 这里吞掉异常是常见缺陷
    }
}
```

**`try-with-resources` 的两个关键点**：

1. **异常抑制（suppressed exceptions）**：如果 `try` 块抛了异常，`close()` 也抛异常，**`close()` 的异常会被挂到主异常的 `getSuppressed()` 上**，主异常继续往外抛。这比旧写法好得多（旧写法里 `finally` 的异常会覆盖掉主异常，丢失真实原因）。
   ```java
   catch (IOException e) {
       for (Throwable s : e.getSuppressed()) {
           log.warn("关闭资源时也出错", s);
       }
   }
   ```
2. **能放进 `try()` 的条件**：实现了 `AutoCloseable`（`Closeable` 是它的子接口，`close()` 声明抛 `IOException`）。**Java 9 起可以传已声明的 effective final 变量**：
   ```java
   // Java 9+
   InputStream in = new FileInputStream(path);
   try (in) { /* 直接用已声明的 in */ }
   ```

**Android 视角**：Android 上 `try-with-resources` 在旧版（API < 19）需要 desugar，现代 `minSdk 21+` 直接可用。**Cursor 是 Android 上最容易泄漏的资源**——`ContentResolver.query` 返回的 `Cursor` 必须关闭，用 `try (Cursor c = ...)` 是最省心的写法。

### 10.5 `finally` 的陷阱

```java
// ❌ finally 里 return 会吞掉异常！
static int f() {
    try {
        throw new RuntimeException("boom");
    } finally {
        return 1;              // 异常被丢弃，方法正常返回 1
    }
}

// ❌ finally 里改返回值也是无效的（值已拷贝）
static int g() {
    int x = 1;
    try {
        return x;              // 这里已经拷贝了 1
    } finally {
        x = 2;                 // 改不动返回值
    }
}
```

**规则**：**`finally` 里只做清理（关资源、释放锁、还原状态），绝不 `return` / `break` / `continue` / `throw`。** 现代编译器/静态分析工具（ErrorProne、SonarQube、Android Lint）会对这类写法报警告。

**`finally` 会不会执行**：

| 情况                                    | finally 执行？  |
| ------------------------------------- | ------------ |
| 正常结束 / 抛异常                            | ✅            |
| `return` / `break` / `continue`       | ✅（在跳转前执行）    |
| `System.exit()`                       | ❌            |
| JVM 崩溃 / `Runtime.halt()` / 被 kill -9 | ❌            |
| 守护线程里的代码                              | 可能不执行（进程退出时） |
| `finally` 所在线程被 `stop()`（已废弃）         | ❌            |

**Android 注意**：`System.exit(0)` 在 Android 上通常只是杀掉自己这个进程，但不建议用；`finally` 里的清理逻辑在进程被系统回收（LMK）时也可能不执行，**所以关键状态要落盘而不是只靠 `finally`**。

### 10.6 异常为什么慢

四个成本来源：

1. **构造时抓栈（`fillInStackTrace`）**：遍历整个调用栈，是最贵的部分。栈越深越慢。
2. **异常对象的构造与抛出**：走的是非常规路径。
3. **JIT 优化受阻**：`try` 块内的代码优化会变得保守。
4. **异常不应该用于流程控制**——不只是性能问题，也是可读性问题。

**性能优化手段（知道就好）**：

```java
// 1. 覆写 fillInStackTrace 让它变便宜（适合"用异常做控制流"的场景，慎用）
public class FastException extends RuntimeException {
    @Override public synchronized Throwable fillInStackTrace() { return this; }
}

// 2. Java 7+ 提供可开关的栈追踪
new RuntimeException("msg", null, false, true);   // writableStackTrace=false
```

**结论**：**异常只用于"异常情况"**。高频路径上的"找不到"应该返回 `Optional` / null / 状态码，而不是抛异常。**但也不要为了性能省略栈信息**——排障时栈比什么都重要。

### 10.7 Kotlin 对照

| Java                                         | Kotlin                           |
| -------------------------------------------- | -------------------------------- |
| 受检异常必须处理或声明                                  | **没有受检异常**，编译器不强制                |
| `Integer.parseInt` 抛 `NumberFormatException` | `toIntOrNull()` 返回 null（**更安全**） |
| `try/catch` 语句                               | `try` 是**表达式**，有返回值              |
| `try-with-resources`                         | `use { }` 扩展函数                   |
| `catch (Exception e) {}`                     | 一样允许（但 Lint 会提示）                 |

```kotlin
// Kotlin 的三个便利点
val n = "42".toIntOrNull() ?: 0                    // 不抛异常
val r = try { risky() } catch (e: Exception) { 0 } // try 作为表达式
File(path).inputStream().use { it.readBytes() }    // 自动关闭
```

---

## 第 11 章 IO 与 NIO

### 11.1 回忆钩子

| 问题                                                 | 小节   |
| -------------------------------------------------- | ---- |
| BIO / NIO / AIO 的本质区别？                             | 11.2 |
| NIO 的三大组件是什么？                                      | 11.3 |
| `Buffer` 的 `position` / `limit` / `capacity` 怎么变化？ | 11.4 |
| `flip()` 到底做了什么？                                   | 11.4 |
| 零拷贝有几种实现？                                          | 11.6 |
| 为什么 `Files.readAllBytes` 不能读大文件？                   | 11.5 |

### 11.2 BIO / NIO / AIO

|         | BIO（阻塞 IO）                     | NIO（非阻塞 IO / 多路复用）                | AIO（异步 IO）                                 |
| ------- | ------------------------------ | --------------------------------- | ------------------------------------------ |
| 代表 API  | `InputStream` / `OutputStream` | `Channel` / `Buffer` / `Selector` | `AsynchronousChannel`                      |
| 模型      | 一个连接一个线程，读不到就阻塞                | 事件驱动，一个线程管多个连接                    | 回调/`Future`，内核完成后通知                        |
| 适用      | 连接数少、吞吐要求不高                    | **连接数多、数据量小**（典型：服务端）             | 连接数多且处理耗时长                                 |
| Java 版本 | 1.0                            | 1.4                               | 1.7（`java.nio.channels` 的 `Asynchronous*`） |
| 现实      | 简单                             | **Netty 是事实标准**                   | Linux 上实现是"伪异步"（用线程池模拟），用得少                |

**关键概念澄清**：

- **"NIO = New IO"** 是官方说法，也有人说是 **Non-blocking IO**。实际上 `java.nio` 包含的是"**面向缓冲区 + 通道**"这套模型，**阻塞/非阻塞是 `Channel` 可选的配置**（`configureBlocking(false)`），不只是"非阻塞"。
- **`InputStream` 是"流"模型**：只能顺序读，不能回退。**`Channel` 是"通道"模型**：可以往 `Buffer` 里读/写，能反复读写同一块区域。
- **`Selector` 是"多路复用"的核心**：一个线程通过 `select()` 同时监听多个 `Channel` 的可读/可写事件。**这是"一个线程处理上万连接"的关键，也是 Netty / Redis / Nginx 的共同基础。**


### 11.3 NIO 三大组件与一个完整示例

```
Channel  ← 数据的通道（File / Socket，双工）
Buffer   ← 数据的容器（读写都通过它）
Selector ← 事件分发器（只对网络 Channel 有意义，FileChannel 不能注册）
```

```java
// 一个最小的 NIO 服务端（单线程处理多连接）
ServerSocketChannel server = ServerSocketChannel.open();
server.bind(new InetSocketAddress(8080));
server.configureBlocking(false);              // ← 关键：非阻塞
Selector selector = Selector.open();
server.register(selector, SelectionKey.OP_ACCEPT);

ByteBuffer buf = ByteBuffer.allocate(1024);
while (true) {
    selector.select();                        // 阻塞直到有事件就绪
    Iterator<SelectionKey> it = selector.selectedKeys().iterator();
    while (it.hasNext()) {
        SelectionKey key = it.next();
        it.remove();                          // ⚠️ 必须手动移除，否则会重复处理
        if (key.isAcceptable()) {
            SocketChannel client = ((ServerSocketChannel) key.channel()).accept();
            client.configureBlocking(false);
            client.register(selector, SelectionKey.OP_READ);
        } else if (key.isReadable()) {
            SocketChannel client = (SocketChannel) key.channel();
            buf.clear();
            int n = client.read(buf);
            if (n == -1) { client.close(); continue; }   // -1 表示对端关闭
            buf.flip();                            // ← 切换为读模式
            client.write(buf);
        }
    }
}
```


**四个必须记住的点**：① `configureBlocking(false)`；② 处理完的 `SelectionKey` **必须从 selectedKeys 里 remove**；③ `read` 返回 `-1` 表示对端关闭；④ **写也可能写不完**（`write` 返回 0 是正常的，要注册 `OP_WRITE` 重试）——**第 ④ 点是自研 NIO 最容易写的 bug，用 Netty 就没这个问题。**

**Android 视角**：Android 上有 `android.os.ParcelFileDescriptor`、`LocalSocket`（Unix domain socket），以及必须用线程/`Handler` 处理的网络。**`StrictMode` 会直接拦住主线程上的磁盘 IO 和网络**——这是 Android 特有的约束：

```java
// 严格模式会抛：NetworkOnMainThreadException / disk write on main thread
StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
        .detectDiskReads().detectDiskWrites().detectNetwork()
        .penaltyLog()
        .build());
```

**车机场景**：`FileChannel` 的 `transferTo` 在拷贝大文件（地图数据、OTA 包）时很有用（见 11.6 零拷贝）。

### 11.4 Buffer：三个指针的游戏

```
allocate(8) 之后：
  0 1 2 3 4 5 6 7
  ↑
 position=0, limit=8, capacity=8

write 4 个字节后：
  0 1 2 3 4 5 6 7
          ↑       ↑
      position=4  limit=8

flip() 之后（切换到读模式）：
  0 1 2 3 4 5 6 7
  ↑       ↑
position=0 limit=4      ← position 归零，limit 变成刚才的 position

read 4 个字节后：
  0 1 2 3 4 5 6 7
          ↑ ↑
   position=4 limit=4   ← 两者相等，hasRemaining() == false

clear() 之后（准备重新写）：
  0 1 2 3 4 5 6 7
  ↑               ↑
position=0     limit=8
```

| 方法                   | 干什么                              | 记忆点                      |
| -------------------- | -------------------------------- | ------------------------ |
| `flip()`             | `limit = position; position = 0` | **写→读 必调**               |
| `clear()`            | `position = 0; limit = capacity` | **读→写 必调**（数据没被清，只是标记重置） |
| `compact()`          | 把未读数据移到头部，然后切到写模式                | 部分消费后继续写时用               |
| `rewind()`           | `position = 0`（limit 不变）         | **重读同一批数据**              |
| `mark()` / `reset()` | 打标记 / 回到标记                       | ——                       |
| `remaining()`        | `limit - position`               | ——                       |

**最高频的 bug**：忘记 `flip()`。症状是"写进去的数据读出来是 0 字节"或"读到的都是垃圾"。

**`ByteBuffer` 的两个大坑**：① 没有 `flip` 之外的"自动模式切换"，全靠手工；② **`ByteBuffer` 不是线程安全的**，而且 `position` 是共享状态，所以**多线程不能共用一个 Buffer 读写**。

**替代品**：Netty 的 `ByteBuf` 用 `readerIndex` / `writerIndex` 双指针，不需要 `flip`——这是对 JDK `ByteBuffer` 设计缺陷的直接改进。

### 11.5 `Files` / `Path`：现代 IO（Java 7+）

**`java.io.File` 的时代基本过去了**，新代码优先用 `java.nio.file`：

| 需求     | 旧（`File`）                   | 新（`Files`）                                                        |
| ------ | --------------------------- | ----------------------------------------------------------------- |
| 读全部文本  | 手写流循环                       | `Files.readString(path)`（Java 11+，默认 UTF-8）                       |
| 读全部字节  | 手写流循环                       | `Files.readAllBytes(path)`                                        |
| 写文本    | `FileWriter`                | `Files.writeString(path, s)`（Java 11+）                            |
| 按行读    | `BufferedReader`            | `Files.lines(path)` → `Stream<String>`（**记得 try-with-resources**） |
| 复制     | 手写循环                        | `Files.copy(src, dst, REPLACE_EXISTING)`                          |
| 移动     | `renameTo`（不可靠）             | `Files.move(src, dst, ATOMIC_MOVE)`                               |
| 删除     | `file.delete()`（返回 boolean） | `Files.delete(path)`（**失败抛异常，更容易发现问题**）                           |
| 遍历目录   | `listFiles()`               | `Files.list()` / `Files.walk()`（Stream）                           |
| 临时文件   | `File.createTempFile`       | `Files.createTempFile(prefix, suffix)`                            |
| 判断是否存在 | `file.exists()`             | `Files.exists(path)`（可指定 `LinkOption.NOFOLLOW_LINKS`）             |
| 符号链接   | 无法区分                        | `Files.isSymbolicLink(path)`                                      |

**`Files.readAllBytes` 为什么不能读大文件**：它把整个文件读进**一个 byte 数组**，数组长度上限约 `Integer.MAX_VALUE - 8`（约 2GB），而且 JVM 需要一块**连续**的堆空间。所以"读 100MB 日志文件"应该用 `Files.lines()`（流式）而不是 `readAllBytes`。

**异常差异（值得记）**：`File.delete()` 返回 `boolean`（静默失败），`Files.delete()` 抛 `IOException`（能被发现）。**"静默失败"是旧 API 最大的设计问题**——这也是为什么新代码都该用 `Files`。

### 11.6 零拷贝

**"零拷贝（zero-copy）"到底零的是什么**：减少数据在**内核态和用户态之间的拷贝次数**。

传统 `read` + `write`（4 次上下文切换 + 4 次拷贝）：

```
磁盘 → 内核缓冲区 → 用户缓冲区 → Socket 内核缓冲区 → 网卡
        [DMA拷贝]   [CPU拷贝]     [CPU拷贝]        [DMA拷贝]
```

**三种优化方案**：

| 方案                   | API                      | 减少到           | 适用                  |
| -------------------- | ------------------------ | ------------- | ------------------- |
| **`mmap` + `write`** | `FileChannel.map`        | 3 次拷贝         | 小文件、需要修改数据          |
| **`sendfile`**       | `FileChannel.transferTo` | 3 次拷贝（可到 2 次） | **大文件发送（Kafka 用它）** |
| **`splice`**         | Linux 特有                 | 2 次拷贝         | 管道转发                |

```java
// transferTo / transferFrom：Java 里的零拷贝
try (FileChannel in = FileChannel.open(src, READ);
     FileChannel out = FileChannel.open(dst, WRITE, CREATE)) {
    long pos = 0;
    long size = in.size();
    while (pos < size) {
        pos += in.transferTo(pos, size - pos, out);   // 返回实际传输字节数，可能 < 请求值
    }
}
```

**注意 `transferTo` 的返回值坑**：它**不保证一次传完**（不同平台上限不同，Linux 上单次通常不超过 2GB），所以**必须循环**，不能只调一次。这是真实项目里踩过的 bug。

**其他"零拷贝"语境**（同一个词被用于不同层面，别混淆）：

- **堆外内存（DirectByteBuffer）**：让 IO 数据直接从堆外走，免去"堆内→堆外"的一次拷贝。代价是分配/回收慢，要用 `Cleaner` 或池化。
- **Android 的 `ashmem` / 共享内存**：进程间大数据传递（如 SurfaceFlinger 的图形缓冲）用共享内存避免拷贝。
- **车机场景**：OTA 包、地图数据的传输与校验，`transferTo` 循环是标准做法。

### 11.7 序列化

**`Serializable`**（Java 原生）的几个要点：

```java
public class User implements Serializable {
    private static final long serialVersionUID = 1L;   // ← 显式声明，别让编译器生成
    private transient String password;                 // 不参与序列化
}
```

| 要点                 | 说明                                                                                  |
| ------------------ | ----------------------------------------------------------------------------------- |
| `serialVersionUID` | 不显式声明时由编译器按类结构计算，**改一个字段就会变**，导致反序列化 `InvalidClassException`                        |
| `transient`        | 跳过该字段（反序列化后是默认值）                                                                    |
| `static` 字段        | 不参与（属于类不属于实例）                                                                       |
| 自定义                | `writeObject` / `readObject` / `writeReplace` / `readResolve`（单例防破坏用 `readResolve`） |
| **安全问题**           | **原生序列化是反序列化漏洞的重灾区**，不要反序列化不受信任的数据                                                  |
| 性能                 | 比 JSON/Protobuf 慢、体积大                                                               |
| Android 上的替代       | **Parcelable**（Android 专用，性能远好于 Serializable）                                       |

**Android 视角（这条你一定会用到）**：

|    | `Serializable` | `Parcelable`                          |
| -- | -------------- | ------------------------------------- |
| 用途 | Java 通用序列化     | Android IPC / Intent 传值               |
| 实现 | 反射 + 流         | 手写读写（或用 `@Parcelize` 插件自动生成）          |
| 性能 | 差（大量反射 + 临时对象） | 好（直接读写内存）                             |
| 适用 | 持久化到文件/网络      | **`Intent` / `Bundle` / `Binder` 传参** |

**`Intent` 传大数据会 `TransactionTooLargeException`**（Binder 事务缓冲区约 1MB，且是所有并发事务共享的）——所以**大数据要落盘或用 `ContentProvider` 共享**，而不是塞进 `Intent`。这是车机 HMI 与系统服务交互时的经典坑。

### 11.8 Kotlin 对照

| Java                 | Kotlin                                                                      |
| -------------------- | --------------------------------------------------------------------------- |
| `InputStream` 循环读    | `inputStream.readBytes()` / `.bufferedReader().readText()` / `.readLines()` |
| `try-with-resources` | `.use { }`                                                                  |
| 手写循环                 | `forEachLine { }` / `useLines { }`（后者不把整个文件读进内存）                            |
| `Files.readAllBytes` | `File.readBytes()`                                                          |
| `Serializable`       | `data class` + 显式 `: Serializable`（一样要写 `serialVersionUID`）                 |

```kotlin
// Kotlin 里最常用的三个
File("a.txt").writeText("hello")                        // 写
File("a.txt").readText()                                // 读（小文件）
File("big.log").useLines { it.filter { l -> l.contains("ERROR") }.take(10).toList() }  // 流式
```

---

## 第 12 章 并发基础：JMM、volatile、synchronized

### 12.1 回忆钩子

| 问题                           | 小节   |
| ---------------------------- | ---- |
| JMM 定义的三大特性是什么？              | 12.2 |
| `volatile` 保证原子性吗？           | 12.3 |
| 双重检查锁（DCL）为什么必须加 `volatile`？ | 12.3 |
| `synchronized` 锁的是什么对象？      | 12.4 |
| 锁升级的四个状态？                    | 12.5 |
| `wait` 和 `sleep` 的区别？        | 12.6 |

### 12.2 JMM：三个特性 + happens-before

**Java 内存模型（Java Memory Model, JMM）** 是一套**规范**，规定了多线程读写共享变量时"什么结果是允许的"。它要保证三件事：

| 特性                 | 含义              | 靠什么保证                                    |
| ------------------ | --------------- | ---------------------------------------- |
| **原子性** Atomicity  | 一个操作不可分割        | `synchronized`、`Atomic*`、`LongAdder`     |
| **可见性** Visibility | 一个线程的修改，其他线程能看见 | `volatile`、`synchronized`、`final`        |
| **有序性** Ordering   | 禁止特定重排序         | `volatile`、`synchronized`、happens-before |

**为什么需要 JMM**：因为**现代 CPU 和编译器都会优化**——CPU 有缓存（L1/L2/L3）和写缓冲（store buffer），编译器/CPU 会重排序指令。**没有 JMM 的话，程序正确性完全无法推理**。JMM 的作用是"给出一个足够强的保证，同时允许实现做足够的优化"。

**抽象模型**（JMM 定义的"主内存 + 工作内存"）：

```
   线程 A                 线程 B
┌──────────┐          ┌──────────┐
│ 工作内存  │          │ 工作内存  │   ← 每个线程独有（对应 CPU 缓存/寄存器）
│ x 的副本  │          │ x 的副本  │
└────┬─────┘          └─────┬────┘
     │   read/write          │
     └────────┬──────────────┘
         ┌────▼────┐
         │ 主内存   │                ← 所有线程共享（对应堆）
         │  x = 0  │
         └─────────┘
```

**JMM 没有规定"必须用缓存"**——它只规定了 `read`/`load`/`use`/`assign`/`store`/`write` 等一系列**动作之间的偏序关系**，具体实现随意。理解这一点就不会纠结"工作内存到底是什么"。

**happens-before（先行发生原则）**：判断"一个操作的结果对另一个操作是否可见"的规则。**如果 A happens-before B，那么 A 的结果对 B 可见，且 A 排在 B 之前。**

八条规则（记住最常用的前四条就够）：

| 规则                | 内容                              |
| ----------------- | ------------------------------- |
| **程序顺序**          | 同一线程内，前面的操作 hb 后面的操作            |
| **监视器锁**          | 对同一个锁的 `unlock` hb 之后对它的 `lock` |
| **`volatile` 变量** | 对 `volatile` 变量的写 hb 之后对它的读     |
| **线程启动**          | `Thread.start()` hb 该线程中的所有操作   |
| **线程终止**          | 线程中的所有操作 hb 其他线程从 `join()` 返回   |
| **中断**            | `interrupt()` hb 被中断线程检测到中断     |
| **对象终结**          | 对象构造完成 hb 它的 `finalize()` 开始    |
| **传递性**           | A hb B，B hb C ⇒ A hb C          |

**"happens-before"不等于"时间上先发生"**——它是**可见性保证**，不是时钟顺序。这是最容易理解错的一点。

### 12.3 `volatile`：可见性 + 禁止重排序，不保证原子性

**两个语义**：

1. **可见性**：写 `volatile` 变量时立即刷新到主内存，读时立即从主内存重新读取（本质是插入内存屏障）。
2. **禁止重排序**：禁止 `volatile` 写与之前的读写重排，禁止 `volatile` 读与之后的读写重排（形成"屏障"）。

**插入的屏障（了解即可）**：

| 屏障                   | 位置                          |
| -------------------- | --------------------------- |
| StoreStore           | `volatile` 写之前              |
| StoreLoad            | `volatile` 写之后（最贵，因为要清空写缓冲） |
| LoadLoad / LoadStore | `volatile` 读之后              |

**"不保证原子性"的经典证据**：

```java
private volatile int count = 0;

public void increment() { count++; }    // ❌ 不是原子操作
// count++ 实际是三步：读 → 加 1 → 写
// 多线程下两个线程可能都读到 5，都写 6

// ✅ 正确做法
private final AtomicInteger count = new AtomicInteger(0);
public void increment() { count.incrementAndGet(); }
```

**`volatile` 只保证"单个变量的读写是原子的"**（其实 `long`/`double` 不加 `volatile` 在 32 位 JVM 上甚至可能读到"半个值"，因为 JMM 允许非 volatile 的 64 位读写拆成两次 32 位操作——**Java 8 规范已要求 `long`/`double` 的非 volatile 读写也应是原子的**，但实践上仍建议对共享的 `long`/`double` 加 `volatile`）。

**双重检查锁定（DCL）为什么必须 `volatile`**——这是 Java 并发最著名的案例：

```java
public class Singleton {
    private static volatile Singleton instance;    // ← 必须加 volatile！

    public static Singleton getInstance() {
        if (instance == null) {                     // 第一次检查：避免每次都加锁
            synchronized (Singleton.class) {
                if (instance == null) {             // 第二次检查：防止重复创建
                    instance = new Singleton();     // ← 这行不是原子的！
                }
            }
        }
        return instance;
    }
}
```

**`new Singleton()` 的三步**：

```java
memory = allocate();            // 1. 分配内存
ctorInstance(memory);           // 2. 初始化对象
instance = memory;              // 3. instance 指向内存地址
```

**2 和 3 之间允许重排序**（因为单线程内看不到差别）。重排后：

```
线程 A：1 → 3 → 2          （3 先执行，instance 已非 null 但对象还没初始化）
线程 B：第一次检查发现 instance != null → 直接返回 → 拿到一个"半成品"对象
```

**这就是"对象逸出"（unsafe publication）**。加 `volatile` 禁止 2、3 重排即可解决。

**现代更推荐的写法（不用管 DCL 这些细节）**：

```java
// 1. 静态内部类（利用类加载的线程安全性，延迟加载）
public class Singleton {
    private Singleton() {}
    private static class Holder { static final Singleton INSTANCE = new Singleton(); }
    public static Singleton getInstance() { return Holder.INSTANCE; }
}

// 2. 枚举（Effective Java 推荐，还能防序列化/反射破坏）
public enum Singleton { INSTANCE; }

// 3. Kotlin
object Singleton          // 天然线程安全 + 延迟初始化
val lazy by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { create() }
```

**Android 视角**：`volatile` 在 Android 上有额外意义——**跨进程/跨线程的标志位**。但 ART 的 JIT/AOT 优化可能导致"看起来没生效"，尤其是把变量放在寄存器里缓存。**用 `volatile` 声明所有"被多线程读写的标志位"是最低要求。**

### 12.4 `synchronized`：锁的是什么

**这是最容易记混的地方**——"锁对象"到底是谁：

| 写法                                  | 锁的对象                      |
| ----------------------------------- | ------------------------- |
| 实例方法 `synchronized void f()`        | **`this`**（当前实例）          |
| 静态方法 `static synchronized void g()` | **`Xxx.class`**（Class 对象） |
| 代码块 `synchronized (obj) { }`        | **`obj` 引用的对象**           |
| 代码块 `synchronized (this) { }`       | 当前实例                      |
| 代码块 `synchronized (Xxx.class) { }`  | Class 对象                  |

```java
// ⚠️ 三个"看起来都加了锁"但互不互斥的例子

// 1. 锁不同对象 → 不互斥
synchronized (new Object()) { }        // ❌ 每次都锁一个新对象，等于没锁

// 2. 实例方法 vs 静态方法 → 锁不同（this vs Class），不互斥
public synchronized void a() {}
public static synchronized void b() {}   // a 和 b 可以同时执行

// 3. 字符串常量 → 可能意外互斥（常量池共享）
synchronized ("LOCK") { }              // ⚠️ 整个 JVM 里所有用 "LOCK" 的地方都互斥
```

**`synchronized` 保证的三件事**：**原子性 + 可见性 + 有序性**（进入同步块读取最新值，退出前刷新）。

**锁的可重入**：同一个线程可以重复获取同一个锁（靠 AQS 的 `state` 计数，或对象头的重入计数）：

```java
public synchronized void a() { b(); }     // 可重入
public synchronized void b() { }
```

**"锁的粒度"建议（实践）**：**锁尽量锁小范围，但不要让锁保护的状态被绕过**。

```java
// ❌ 锁整个方法（包括不需要同步的部分）
public synchronized void process() {
    String data = loadFromDisk();       // 慢 IO 不该在锁里
    counter++;
}

// ✅ 只锁共享状态
public void process() {
    String data = loadFromDisk();
    synchronized (this) { counter++; }
}
```

**但注意**：如果 `loadFromDisk` 依赖 `counter` 的一致性，拆开就会引入竞态。**"锁保护的是不变式（invariant），不是代码块"**——这是并发编程最重要的心法。

### 12.5 锁升级：从偏向锁到重量级

**对象头的 Mark Word** 是理解锁实现的关键：

```
64 位 JVM 的 Mark Word（各状态下的布局不同）
┌──────────────────────────────────────────────────────┐
│ 无锁      │ hash(31) │ age(4) │ 0 │ 01               │
│ 偏向锁    │ thread(54) │ epoch(2) │ age(4) │ 1 │ 01   │
│ 轻量级锁  │ 指向栈中锁记录的指针(62) │ 00            │
│ 重量级锁  │ 指向 Monitor 的指针(62)  │ 10            │
│ GC 标记   │ 空(62)                   │ 11            │
└──────────────────────────────────────────────────────┘
```

**四个状态（单向升级，几乎不降级）**：

| 状态       | 触发条件       | 实现代价                           | 适用场景     |
| -------- | ---------- | ------------------------------ | -------- |
| **无锁**   | 初始         | ——                             | ——       |
| **偏向锁**  | 只有一个线程反复进入 | 只需在 Mark Word 记线程 ID，**几乎零开销** | 单线程访问同步块 |
| **轻量级锁** | 有第二个线程竞争   | CAS 自旋（用户态），不阻塞                | 竞争时间极短   |
| **重量级锁** | 自旋失败/竞争激烈  | 操作系统互斥量，**线程阻塞 + 上下文切换**       | 竞争激烈     |

**关键理解**：

- **偏向锁（Biased Locking）**：当只有一个线程访问时，把线程 ID 记在 Mark Word 里，之后同一线程进入同步块**连 CAS 都不用**。这是"绝大多数锁其实没有竞争"这个观察的产物。
- **轻量级锁**：靠 CAS 把 Mark Word 指向线程栈中的 Lock Record。失败则自旋。
- **自旋（spin）**：不立刻阻塞，而是空转一会儿等锁释放（因为阻塞/唤醒的成本比空转几百个 cycle 高）。JDK 6 起有**自适应自旋**（根据历史成功率决定自旋次数）。
- **重量级锁的 Monitor**：这才是 `ObjectMonitor`（`wait`/`notify` 就在这里），有 EntryList、WaitSet。

**重要的版本变化（这块"十年前的知识"已经过时了）**：

| 版本         | 变化                                                 |
| ---------- | -------------------------------------------------- |
| **JDK 15** | **默认禁用偏向锁**（`-XX:-UseBiasedLocking`），JEP 374 标记为废弃 |
| **JDK 18** | **偏向锁实现被彻底移除**                                     |

**原因**：偏向锁的实现让对象头和其他组件的代码极其复杂，而现代应用（尤其有 `hashCode` 调用、大量并发）下收益已不明显，维护成本远超收益。

**所以"偏向锁 → 轻量级锁 → 重量级锁"这个升级链在 JDK 18+ 实际上是"轻量级 → 重量级"了。** 这是必须更新的知识点。

### 12.6 `wait` / `notify` / `sleep` / `join`

|           | `wait()`                 | `sleep(ms)`            | `join()`               | `yield()`    |
| --------- | ------------------------ | ---------------------- | ---------------------- | ------------ |
| 所属        | `Object`                 | `Thread`（静态）           | `Thread`               | `Thread`（静态） |
| **是否释放锁** | ✅ **释放**                 | ❌ **不释放**              | ——                     | ——           |
| 唤醒条件      | `notify`/`notifyAll` 或超时 | 超时                     | 目标线程结束                 | ——           |
| 必须持锁      | ✅ 必须在 `synchronized` 块内  | ❌                      | ❌                      | ❌            |
| 抛中断异常     | `InterruptedException`   | `InterruptedException` | `InterruptedException` | ❌            |

**"`wait` 释放锁、`sleep` 不释放"** 是必须记住的区别。原因很直觉：`wait` 的目的是"等条件成立，让别人去改变条件"，所以必须让出锁；`sleep` 只是"我歇会儿"。

**标准等待-通知模式**（必须用 `while` 而不是 `if`）：

```java
// 生产者
synchronized (queue) {
    while (queue.isFull()) queue.wait();   // ✅ 循环判断
    queue.add(item);
    queue.notifyAll();                     // 唤醒所有等待者（不只 notify）
}

// 消费者
synchronized (queue) {
    while (queue.isEmpty()) queue.wait();
    Item item = queue.remove();
    queue.notifyAll();
}
```

**为什么必须用 `while` 循环**（三个理由，全部要记住）：

1. **虚假唤醒（spurious wakeup）**：JVM 规范**允许** `wait` 在没有 `notify` 的情况下返回。这是规范故意留的口子（对应 POSIX 的 `pthread_cond_wait`）。
2. **被唤醒后条件可能又不成立**：`notifyAll` 唤醒多个线程，抢到锁的那个消费完后，其他的醒来条件已不成立。
3. **多个条件共用一个锁**（如"非空"和"非满"）：`notifyAll` 会唤醒不相关的等待者。

**为什么用 `notifyAll` 而不是 `notify`**：`notify` 只唤醒一个，**如果唤醒的是"条件不满足"的那个，就会死锁**（其他等待者永远收不到通知）。`notifyAll` 效率略低但正确。**除非你能严格证明"任意一个被唤醒者都能推进"，否则一律 `notifyAll`。**

**现代替代**：`java.util.concurrent` 里的 `BlockingQueue`、`CountDownLatch`、`Condition`（`ReentrantLock.newCondition()`，支持多条件队列——这是对"一个锁只能有一个 WaitSet"的改进）已经把这些封装好了。**`wait`/`notify` 现在只该出现在维护老代码时。**

### 12.7 中断机制

**Java 没有"安全地强制停止线程"的机制**（`Thread.stop()` 已废弃，因为它会在任意点释放锁、破坏不变式）。**唯一正确的方式是协作式中断**：

```java
// 生产者线程
while (!Thread.currentThread().isInterrupted()) {     // 检查中断标志
    try {
        doWork();
        Thread.sleep(100);
    } catch (InterruptedException e) {
        // ⚠️ 捕获后必须处理：要么退出，要么恢复中断标志
        Thread.currentThread().interrupt();          // 恢复标志位（推荐）
        break;
    }
}

// 外部
thread.interrupt();     // 设置中断标志；如果线程在 sleep/wait/join 中，会抛 InterruptedException
```

**三个关键点**：

1. **`interrupt()` 只是设置标志位**，不强制停止。目标线程自己决定怎么响应。
2. **阻塞方法（`sleep`/`wait`/`join`/`take`/`get`）收到中断会"清除标志位 + 抛 `InterruptedException`"**——所以**捕获后如果还要继续，必须重新 `Thread.currentThread().interrupt()` 恢复标志**，否则外层的 `isInterrupted()` 检查会失效。**这是最常见的并发 bug 之一。**
3. **`isInterrupted()` vs `Thread.interrupted()`**：前者**不清除**标志，后者**清除**标志。`Thread.interrupted()` 是静态方法，作用于当前线程——名字看着像"查询"，实际有副作用，容易写错。

**Android 视角**：`AsyncTask.cancel()`、`HandlerThread.quit()`、`ThreadPoolExecutor.shutdownNow()` 都是这套协作式中断的封装。`shutdownNow` 会 `interrupt` 所有工作线程，但**不保证任务真的停止**——必须任务自己响应中断。**这就是"为什么 UI 线程之外的耗时任务必须检查中断"** 的原因。

---

## 第 13 章 并发进阶：AQS、锁、线程池、CompletableFuture

### 13.1 回忆钩子

| 问题                                                       | 小节   |
| -------------------------------------------------------- | ---- |
| AQS 的核心是什么？`state` 怎么用？                                  | 13.2 |
| `ReentrantLock` 相比 `synchronized` 多了什么？                  | 13.3 |
| 线程池七个参数，哪个最容易设错？                                         | 13.4 |
| 线程池为什么"不能只用无界队列"？                                        | 13.4 |
| `CompletableFuture` 的 `thenApply` 和 `thenCompose` 有什么区别？ | 13.6 |
| `CountDownLatch` 和 `CyclicBarrier` 的区别？                  | 13.5 |

### 13.2 AQS：`java.util.concurrent` 的地基

**AQS（AbstractQueuedSynchronizer）** 是 `ReentrantLock`、`CountDownLatch`、`Semaphore`、`ReentrantReadWriteLock` 的共同基类。

**核心两件东西**：

```java
public abstract class AbstractQueuedSynchronizer {
    private volatile int state;                  // 1. 同步状态（含义由子类定义）
    // 2. CLH 双向队列（FIFO），存等待的线程节点 Node
}
```

**`state` 的含义完全由子类决定**（这是 AQS 设计的精髓——"模板方法 + 状态语义开放"）：

| 实现                       | `state` 的含义             |
| ------------------------ | ----------------------- |
| `ReentrantLock`          | 0=未锁，n=被同一线程重入 n 次      |
| `Semaphore`              | 剩余许可数                   |
| `CountDownLatch`         | 剩余未完成的计数                |
| `ReentrantReadWriteLock` | 高 16 位=读锁计数，低 16 位=写锁计数 |

**子类要实现的五个方法**（`tryAcquire` / `tryRelease` / `tryAcquireShared` / `tryReleaseShared` / `isHeldExclusively`），AQS 负责"排队、阻塞、唤醒"这套通用逻辑。

**两种模式**：

- **独占（Exclusive）**：同一时刻只有一个线程能获取，如 `ReentrantLock`
- **共享（Shared）**：多个线程可同时获取，如 `Semaphore`、`CountDownLatch`

**获取锁的流程（`ReentrantLock` 非公平）**：

```
tryAcquire(arg) 成功 → 直接执行（非公平锁会"插队"）
       ↓ 失败
addWaiter(Node.EXCLUSIVE)  →  加入 CLH 队尾
       ↓
acquireQueued()  →  自旋 + 判断前驱是否 head → 是则再 tryAcquire
                    → 不是则 LockSupport.park() 阻塞
       ↓ 被前驱 unpark
重新尝试获取
```

**为什么要 CLH 队列**：`park`/`unpark` 是相对昂贵的操作，CLH 队列保证"只有队首的下一个节点会被唤醒"，避免"惊群"（所有等待者同时醒来抢锁）。

**为什么 `LockSupport.park` 不认识"中断抛异常"**：`park` 只是阻塞，中断时它**返回**而不抛异常（`isInterrupted()` 为 true）。这就是为什么 AQS 里要显式检查 `Thread.interrupted()` 并抛 `InterruptedException`。

### 13.3 锁家族对比

|       | `synchronized`  | `ReentrantLock`                  | `ReentrantReadWriteLock` | `StampedLock`(JDK 8+) |
| ----- | --------------- | -------------------------------- | ------------------------ | --------------------- |
| 实现    | JVM 内建（Monitor） | AQS                              | AQS                      | 自定义 + 乐观读             |
| 释放    | 自动（出块）          | **必须手动 `unlock()`（放 `finally`）** | 同左                       | 同左                    |
| 可中断获取 | ❌               | ✅ `lockInterruptibly()`          | ✅                        | ✅                     |
| 超时获取  | ❌               | ✅ `tryLock(timeout)`             | ✅                        | ✅                     |
| 公平性   | 非公平（不可选）        | **可选公平/非公平**                     | 可选                       | 非公平                   |
| 多条件队列 | 单个 WaitSet      | ✅ 多个 `Condition`                 | ✅                        | ❌                     |
| 读并发   | ❌               | ❌                                | ✅ 读读不互斥                  | ✅ 读读不互斥 + 乐观读         |
| 死锁检测  | 靠 `jstack`      | 靠 `jstack`                       | 靠 `jstack`               | 靠 `jstack`            |

**"为什么 `unlock` 必须放 `finally`"**：

```java
Lock lock = new ReentrantLock();
lock.lock();
try {
    // 业务代码
} finally {
    lock.unlock();       // ✅ 必须放 finally，否则业务抛异常就永久泄漏锁
}
```

**`Condition` 相比 `wait`/`notify` 的优势**（多条件队列，解决 12.6 节提的"多条件共用 WaitSet"问题）：

```java
ReentrantLock lock = new ReentrantLock();
Condition notFull  = lock.newCondition();
Condition notEmpty = lock.newCondition();

// 生产者只等 notFull，消费者只等 notEmpty → 不会"唤醒错的线程"
public void put(Item x) throws InterruptedException {
    lock.lock();
    try {
        while (queue.isFull()) notFull.await();
        queue.add(x);
        notEmpty.signal();
    } finally { lock.unlock(); }
}
```

**`StampedLock` 的乐观读**（JDK 8 引入，读性能更好但要小心）：

```java
long stamp = lock.tryOptimisticRead();     // 不阻塞，不真加锁
int cur = value;                            // 读数据（可能读到不一致的）
if (!lock.validate(stamp)) {                // 校验：期间有没有人写过
    stamp = lock.readLock();                // 有写过 → 退化为悲观读
    try { cur = value; } finally { lock.unlockRead(stamp); }
}
```

**注意 `StampedLock` 的三个坑**：① 不可重入；② 不支持 `Condition`；③ **不能用 `interrupt`，也没有公平策略**。**所以它只在"读极多、写极少、且读能容忍重试"的热点场景用。**

**`synchronized` 还是 `Lock`？现代建议**：**优先 `synchronized`**（JVM 会持续优化，代码更简洁不出错），只有在需要**超时、可中断、公平、多条件、读写分离**时才用 `Lock`。这是"十年前建议用 Lock、现在建议用 synchronized"的一个翻转，值得注意（JDK 6 之后 `synchronized` 的优化让它性价比大幅提升）。

### 13.4 线程池：七个参数与拒绝策略

```java
public ThreadPoolExecutor(
    int corePoolSize,                    // 1. 核心线程数（常驻，即使空闲）
    int maximumPoolSize,                 // 2. 最大线程数
    long keepAliveTime,                  // 3. 非核心线程空闲存活时间
    TimeUnit unit,                       // 4. 时间单位
    BlockingQueue<Runnable> workQueue,   // 5. 任务队列
    ThreadFactory threadFactory,         // 6. 线程工厂（起名、设优先级、守护）
    RejectedExecutionHandler handler     // 7. 拒绝策略
)
```

**执行流程（这个顺序必须记住，因为它违反直觉）**：

```
提交任务
   │
   ├─ 1. 当前线程数 < corePoolSize  →  直接创建新线程执行（哪怕有空闲线程！）
   │
   ├─ 2. 核心池满了  →  尝试放入 workQueue
   │        └─ 队列没满  →  入队，等空闲线程来取
   │
   ├─ 3. 队列也满了  →  创建新线程直到 maximumPoolSize
   │
   └─ 4. 线程数也到 max  →  执行拒绝策略
```

**"违反直觉"的地方**：**队列满了才会扩容到 max**。所以：

```java
// ❌ 这个池永远只有 1 个线程在跑，maximumPoolSize=100 完全没用上！
new ThreadPoolExecutor(1, 100, 0L, TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>());      // 无界队列，永远不会满
// 后果：任务无限堆积 → OOM（堆内存被队列里的任务对象撑爆）
```

**这是一个真实的线上事故模式**：`Executors.newFixedThreadPool(n)` 用的就是这个配置（无界 `LinkedBlockingQueue`），**只创建 n 个线程，队列无上限**。所以**阿里规约明确禁止用 `Executors` 创建线程池**。

**`Executors` 四个工厂方法的隐藏问题**：

| 方法                          | 队列                        | 隐患                                           |
| --------------------------- | ------------------------- | -------------------------------------------- |
| `newFixedThreadPool(n)`     | `LinkedBlockingQueue`（无界） | **任务无限堆积 → OOM**                             |
| `newSingleThreadExecutor()` | 同上                        | 同上                                           |
| `newCachedThreadPool()`     | `SynchronousQueue`（不排队）   | `max = Integer.MAX_VALUE` → **线程无限创建 → OOM** |
| `newScheduledThreadPool(n)` | `DelayedWorkQueue`        | `max = Integer.MAX_VALUE`                    |
| `newWorkStealingPool()`     | 无队列（工作窃取）                 | 相对安全                                         |

**结论**：**手动 `new ThreadPoolExecutor`，显式指定有界队列 + 有意义的拒绝策略 + 带名字的线程工厂。**

**四种拒绝策略**：

| 策略                    | 行为                             |
| --------------------- | ------------------------------ |
| `AbortPolicy`（默认）     | 抛 `RejectedExecutionException` |
| `CallerRunsPolicy`    | **由提交任务的线程自己执行**（形成天然的背压，不丢任务） |
| `DiscardPolicy`       | 静默丢弃新任务（**危险，任务消失无痕迹**）        |
| `DiscardOldestPolicy` | 丢掉队列里最老的，重试提交                  |

**怎么设 `corePoolSize` / `maximumPoolSize`**（常见经验，但要按实际压测调整）：

| 任务类型       | 经验公式                                      |
| ---------- | ----------------------------------------- |
| **CPU 密集** | `N + 1`（N = CPU 核数）                       |
| **IO 密集**  | `2N` 或 `N / (1 - 阻塞系数)`，阻塞系数 = 阻塞时间 / 总时间 |

```java
int cores = Runtime.getRuntime().availableProcessors();
```

**必须自己起线程名**（否则线上 dump 出来全是 `pool-1-thread-1`，没法排障）：

```java
ThreadFactory tf = new ThreadFactory() {
    private final AtomicInteger n = new AtomicInteger(1);
    @Override public Thread newThread(Runnable r) {
        Thread t = new Thread(r, "order-worker-" + n.getAndIncrement());
        t.setDaemon(false);
        t.setUncaughtExceptionHandler((th, e) -> log.error("线程异常退出 " + th.getName(), e));
        return t;
    }
};
```

**`UncaughtExceptionHandler` 为什么重要**：**线程池里的任务抛出未捕获异常，异常会被吞掉**（线程池内部 `catch` 后 `afterExecute` 处理），既不打日志也不影响其他任务。**"任务静默失败"是线程池最常见的排障盲区**：

```java
// 两种防护
// 1. 给线程工厂设 UncaughtExceptionHandler（见上）
// 2. submit() 会返回 Future，异常包装在 ExecutionException 里（必须 get() 才抛出）
//    → submit 的任务如果没人 get()，异常也是被吞的
//    → execute() 则直接走 UncaughtExceptionHandler
```

**线程池参数的动态调整**：`setCorePoolSize` / `setMaximumPoolSize` 可以运行时改（`coreSize` 调大可立即生效，调小要等线程空闲回收）。队列容量**不可改**，所以如果需要动态调整队列，得自己实现或用 `ResizableCapacityLinkedBlockingQueue` 这类方案。

**`shutdown()` vs `shutdownNow()`**：

|       | `shutdown()` | `shutdownNow()`              |
| ----- | ------------ | ---------------------------- |
| 新任务   | 拒绝           | 拒绝                           |
| 已入队任务 | **会执行完**     | **返回未执行的列表**                 |
| 在跑的任务 | 等它跑完         | **`interrupt` 它**（是否响应看任务自己） |
| 用途    | 优雅关闭         | 快速关闭                         |

**为什么 `shutdown()` 之后 `awaitTermination` 可能永不返回**：如果任务里有 `while(true)` 或阻塞在无超时的 IO 上，`shutdown` 无济于事。**所以必须给任务加中断响应（见 12.7）**。

**Android 视角**：

|     | `AsyncTask`             | `ThreadPoolExecutor` | `HandlerThread` | `Coroutine` |
| --- | ----------------------- | -------------------- | --------------- | ----------- |
| 状态  | **已废弃（API 30）**         | 推荐                   | 推荐（串行任务）        | **首选**      |
| 线程数 | 默认串行（`SERIAL_EXECUTOR`） | 自己控                  | 1               | 调度器决定       |

**"不要用 `AsyncTask`"这个结论值得记住**：它已废弃，而且内部有个 128 个任务的全局队列、`execute`/`executeOnExecutor` 行为不一致、`onPostExecute` 可能因 Activity 销毁而闪烁。**现代的替代是 `ExecutorService` + 主线程 `Handler`，或 `Coroutine`/`Flow`。**

### 13.5 同步工具类速查

| 工具                  | 作用                         | 一次性？    |
| ------------------- | -------------------------- | ------- |
| `CountDownLatch`    | **一个/多个线程等 N 个事件完成**（计数的减） | ✅ 不可重用  |
| `CyclicBarrier`     | **N 个线程互相等**，到齐后一起继续（计数的加） | ❌ 可循环重用 |
| `Semaphore`         | 限流（许可数控制并发量）               | 可释放归还   |
| `Phaser`            | 更灵活的屏障（可动态注册/注销参与者）        | 多阶段     |
| `Exchanger`         | 两个线程交换数据                   | ——      |
| `CompletableFuture` | 异步编排                       | ——      |

```java
// CountDownLatch：等 3 个初始化任务全部完成
CountDownLatch latch = new CountDownLatch(3);
for (int i = 0; i < 3; i++) {
    executor.submit(() -> {
        try { initPart(i); } finally { latch.countDown(); }   // ⚠️ finally 里 countDown
    });
}
latch.await(5, TimeUnit.SECONDS);      // 可带超时，返回 boolean
// latch.await();                       // 不带超时会一直等（可能永久阻塞）

// Semaphore：限制同时最多 10 个请求
Semaphore sem = new Semaphore(10);
sem.acquire();
try { doRequest(); } finally { sem.release(); }
```

**`CountDownLatch` vs `CyclicBarrier` 的记忆法**：

- `CountDownLatch`：**"等别人做完"**——主线程等 N 个 worker（1 等 N）
- `CyclicBarrier`：**"互等"**——N 个线程互相等，到齐后一起走（N 等 N），还能在到齐时执行一个 `Runnable`

**`CountDownLatch` 的 `countDown()` 一定要放 `finally`**，否则任务中途抛异常就永远不会减到 0，主线程永久阻塞。

### 13.6 `CompletableFuture`：异步编排

**核心问题**：`Future` 只能 `get()` 阻塞等待，无法"完成后自动做下一步"。`CompletableFuture`（JDK 8+）解决了这个。

**三组最容易混的方法**：

| 你觉得是"接着做"               | 实际语义                                    | 选择                                       |
| ----------------------- | --------------------------------------- | ---------------------------------------- |
| `thenApply(f)`          | `f` 返回**值**，结果被包装                       | `T → U` 的**同步转换**（类似 `map`）              |
| `thenCompose(f)`        | `f` 返回**新的 `CompletableFuture`**，会被"展平" | `T → CompletableFuture<U>`（类似 `flatMap`） |
| `thenCombine(other, f)` | 等两个独立 future 都完成，用 `f` 合并               | 并行 → 合并                                  |

```java
// ❌ 用 thenApply 会得到 CompletableFuture<CompletableFuture<User>>
CompletableFuture<CompletableFuture<User>> bad =
        fetchId().thenApply(id -> fetchUser(id));

// ✅ 用 thenCompose 展平
CompletableFuture<User> good = fetchId().thenCompose(this::fetchUser);
```

**"为什么一定要有 `Async` 后缀"**：

| 方法                  | 执行线程                               |
| ------------------- | ---------------------------------- |
| `thenApply(f)`      | **调用 `thenApply` 的那个线程**（或完成它的线程）  |
| `thenApplyAsync(f)` | 从 `ForkJoinPool.commonPool()` 里挑一个 |

**这是最容易踩的坑**：`thenApply` 里的代码可能跑在**你自己想不到的线程**上。如果 `thenApply` 里做了耗时操作，会阻塞那个线程（可能是主线程！）。

```java
// ⚠️ 危险：如果 future 已经完成，这里会直接在"当前线程"（可能是 UI 线程）执行
future.thenApply(v -> heavyCompute(v));

// ✅ 明确的写法
future.thenApplyAsync(v -> heavyCompute(v), myExecutor);
```

**Android 视角：`CompletableFuture` 在 Android 上的现实问题**

| 问题                          | 说明                                                                                              |
| --------------------------- | ----------------------------------------------------------------------------------------------- |
| `ForkJoinPool.commonPool()` | **Android 上不推荐**——分辨率低（`availableProcessors` 在 Android 上返回的核数可能不合理）、线程无名字、和 Android 的线程优先级管理不配合 |
| API 级别                      | `CompletableFuture` 需要 **API 24+**；低版本要 desugar（`coreLibraryDesugaring`）                        |
| 替代                          | **`Coroutine` + `Flow`** 在 Android 上是明显更好的选择（结构化并发、可取消、调度器可控）                                   |

```kotlin
// 同样的"并行 → 合并"，Kotlin 的写法（更简洁且可取消）
coroutineScope {
    val a = async { fetchA() }
    val b = async { fetchB() }
    combine(a.await(), b.await()) { x, y -> x + y }
}
```

**结论**：**Android 上新代码用 `Coroutine`**；`CompletableFuture` 主要在对接 Java 服务端库或已有 Java 代码时用。

### 13.7 其他 JUC 组件

| 组件                             | 用途                 | 关键点                                                                                                           |
| ------------------------------ | ------------------ | ------------------------------------------------------------------------------------------------------------- |
| `AtomicInteger` / `AtomicLong` | 原子操作               | **底层 CAS 自旋**，高竞争下会大量空转                                                                                       |
| `LongAdder` / `DoubleAdder`    | 高并发计数              | **分段累加**，比 `AtomicLong` 快得多；但 `sum()` 不是瞬时快照                                                                  |
| `AtomicReference`              | 原子引用               | 配合 `compareAndSet` 做无锁更新                                                                                      |
| `AtomicStampedReference`       | **带版本号的原子引用**      | **解决 ABA 问题**                                                                                                 |
| `AtomicIntegerFieldUpdater`    | 原子更新某个 volatile 字段 | 省内存（不用包成对象）                                                                                                   |
| `LongAccumulator`              | 自定义累加函数            | 比 `LongAdder` 更灵活                                                                                             |
| `ConcurrentHashMap`            | 并发 Map             | 见 7.3                                                                                                         |
| `CopyOnWriteArrayList`         | 读多写极少              | 写时复制，**遍历不需要加锁**，但写慢且内存翻倍                                                                                     |
| `ConcurrentLinkedQueue`        | 无锁并发队列             | CAS 实现                                                                                                        |
| `BlockingQueue` 家族             | 生产者消费者             | `ArrayBlockingQueue`（有界）/ `LinkedBlockingQueue` / `SynchronousQueue` / `PriorityBlockingQueue` / `DelayQueue` |

**CAS 的 ABA 问题**（必须知道）：

```
线程 A 读到值 100
线程 B 把 100 改成 200，又改回 100
线程 A 的 CAS 成功（以为没人动过） ← 实际上被改过两轮
```

**解决**：`AtomicStampedReference`（加版本号）或 `AtomicMarkableReference`（加 boolean 标记）。**但 ABA 在很多场景下无害**（如计数器），只在"值相同但含义已变"（如链表节点被回收复用）时才致命。

**`AtomicLong` vs `LongAdder` 的选择**：

|      | `AtomicLong`   | `LongAdder`                   |
| ---- | -------------- | ----------------------------- |
| 累加方式 | 单个变量 CAS 自旋    | **分散到多个 cell，最后求和**           |
| 高并发写 | 差（大量 CAS 失败重试） | **好**                         |
| 精确读取 | ✅ 精确           | ⚠️ `sum()` 期间可能有写入，**不是精确快照** |
| 适用   | 低竞争、要求精确读      | 高竞争、只关心最终统计（**监控指标首选**）       |

---

## 第 14 章 JDK 版本演进：8 → 11 → 17 → 21

> 这一章的目的：**让你知道"哪些老写法现在有更好的替代"**，以及升级时要注意什么。Android 项目要特别留意 desugaring 和 `minSdk`。

### 14.1 版本节奏与 LTS

| 版本     | 类型      | 发布日期      | 关键内容                                                                                                                      |
| ------ | ------- | --------- | ------------------------------------------------------------------------------------------------------------------------- |
| **8**  | **LTS** | 2014-03   | Lambda、Stream、`Optional`、`CompletableFuture`、接口 default 方法、`java.time`                                                    |
| 9      | 特性      | 2017-09   | **模块系统 JPMS**、集合工厂方法、`var` 的铺垫（`_` 弃用）、私有接口方法                                                                             |
| 10     | 特性      | 2018-03   | `var`（局部变量类型推断）                                                                                                           |
| **11** | **LTS** | 2018-09   | `String.isBlank/strip/repeat/lines`、`Files.readString/writeString`、**HTTP Client 标准化**、`var` 在 lambda 参数中可用、移除 Java EE 模块 |
| 12-16  | 特性      | 2019-2021 | switch 预览、`instanceof` 模式匹配（16 正式）、**record（16 正式）**、文本块（15 正式）、`Stream.toList()`（16）                                     |
| **17** | **LTS** | 2021-09   | **sealed class（正式）**、**`instanceof` 模式匹配（正式）**、移除 SecurityManager 弃用、强封装 JDK 内部（16 起默认）                                   |
| 18-20  | 特性      | 2022-2023 | 虚拟线程预览、`record` 模式匹配、**JDK 18 移除偏向锁、反射 inflation 移除**                                                                     |
| **21** | **LTS** | 2023-09   | **虚拟线程（正式）**、**`record` 模式（正式）**、**switch 模式匹配（正式）**、`SequencedCollection`、`String.length` 之外的分代式 ZGC                     |

**LTS（长期支持）版本**：8、11、17、21、25。**生产项目选 LTS**，非 LTS 只有半年支持。

**Android 的现实**（这条最重要）：

|                             | 情况                                                                                       |
| --------------------------- | ---------------------------------------------------------------------------------------- |
| **AGP 8.0+**                | 需要 JDK 17 编译                                                                             |
| **AGP 8.2+**                | 需要 JDK 17                                                                                |
| **源码语言级别**                  | 可以设到 `JavaVersion.VERSION_11` / `VERSION_17`，**但可以用 desugar 让低版本设备也支持部分 API**            |
| **`coreLibraryDesugaring`** | 让 `java.time`、`Optional`、`stream`（API 24+）等在低版本跑                                         |
| **实际限制**                    | 语言特性（lambda、`var`、`record`）可以用；**API（`List.of`、`Stream.toList`）需要 desugar 或提高 `minSdk`** |

**车机项目的现实**：如果是 **Android 10+（API 29+）** 的车机，`java.time`、`Optional`、`Stream` 全部原生可用，**不用担心 desugar**。这让 Java 8 风格代码可以放心写。

### 14.2 Java 8：你现在最可能用到的（也是 Android 支持最好的）

```java
// 1. Lambda + 函数式接口
list.forEach(s -> System.out.println(s));
list.sort((a, b) -> a.compareTo(b));

// 2. Stream（注意：Stream 是一次性的，用完就废）
Map<String, List<User>> byCity = users.stream()
        .filter(u -> u.getAge() >= 18)
        .collect(Collectors.groupingBy(User::getCity));

// 3. Optional（⚠️ 不要用 isPresent + get，那是"穿了马甲的 null 检查"）
String name = Optional.ofNullable(user)
        .map(User::getName)
        .orElse("unknown");
// ❌ 反面教材
if (opt.isPresent()) { use(opt.get()); }     // 和 if (x != null) 没区别

// 4. 接口 default / static 方法（这是"为什么 Java 能加新方法不破坏兼容"的答案）
public interface Comparator<T> {
    default Comparator<T> reversed() { /* ... */ }      // 不破坏已有实现类
    static <T> Comparator<T> comparingInt(ToIntFunction<T> f) { /* ... */ }
}

// 5. java.time（线程安全，替代 Date/Calendar）
LocalDateTime now = LocalDateTime.now();
Instant i = now.atZone(ZoneId.of("Asia/Shanghai")).toInstant();
Duration d = Duration.between(start, end);
```

**`java.time` 为什么值得记**：

|      | `Date` / `Calendar`              | `java.time`                              |
| ---- | -------------------------------- | ---------------------------------------- |
| 线程安全 | ❌ 可变                             | ✅ **全部不可变**                              |
| 月份   | 0-based（`Calendar.JANUARY == 0`） | 1-based（`Month.JANUARY.getValue() == 1`） |
| 时区   | 隐式，容易错                           | 显式 `ZonedDateTime`                       |
| 格式化  | `SimpleDateFormat`（**线程不安全**）    | `DateTimeFormatter`（**线程安全**）            |
| 设计   | 混乱                               | 清晰的 `Instant`/`Local*`/`Zoned*` 分层       |

**`SimpleDateFormat` 是线程不安全的**——这是最经典的多线程 bug 之一。**如果你在 10 年前的代码里见过 `static final SimpleDateFormat`，那就是个定时炸弹**，应换成 `DateTimeFormatter` 或 `ThreadLocal`。

### 14.3 Java 9-16：值得回补的实用特性

```java
// 集合工厂方法（Java 9）—— 完全不可变
List<String> l = List.of("a", "b");
Set<String> s = Set.of("a", "b");
Map<String, Integer> m = Map.of("a", 1, "b", 2);
Map<String, Integer> m2 = Map.ofEntries(Map.entry("a", 1));   // 超过 10 对用这个

// var（Java 10）—— 只能用于局部变量，且有初始化表达式
var list = new ArrayList<String>();          // 推断为 ArrayList<String>
var stream = list.stream();
// ❌ var x;           没有初始化
// ❌ var f = () -> {};  lambda 没有目标类型
// ❌ void f(var x)     不能用于方法参数

// String 新方法（Java 11）
" ".isBlank();               // true
"  x  ".strip();             // "x"（strip 是 Unicode 感知的，trim 只认 <= U+0020）
"ab".repeat(3);              // "ababab"
"a\nb".lines();              // Stream<String>

// Files 新方法（Java 11）
Files.readString(path);                        // 默认 UTF-8
Files.writeString(path, "content");
Files.writeString(path, "c", CREATE, APPEND);   // 追加

// HTTP Client（Java 11 标准化）—— 替代 HttpURLConnection
HttpClient client = HttpClient.newHttpClient();
HttpResponse<String> resp = client.send(
        HttpRequest.newBuilder(URI.create("https://example.com")).GET().build(),
        HttpResponse.BodyHandlers.ofString());

// 文本块（Java 15 正式）—— 多行字符串，SQL / JSON 神器
String sql = """
        SELECT id, name
        FROM users
        WHERE city = ?
        """;
// ⚠️ 结尾 """ 的位置决定是否保留换行；每行会剥掉最小的公共缩进

// instanceof 模式匹配（Java 16 正式）—— 不用再强转
if (obj instanceof String s && s.length() > 3) {      // 变量在 && 右边就能用
    System.out.println(s.toUpperCase());
}

// record（Java 16 正式）—— 不可变数据载体
public record Point(int x, int y) {}                   // 自动生成构造器/getter/equals/hashCode/toString
// 注意：getter 是 x() 和 y()，不是 getX()（和 Bean 规范不一致，Jackson 需要配置）
```

**`record` 与 Lombok `@Data` 的对比**（值得记住，因为你是从 Lombok 时代过来的）：

|           | `record`                | Lombok `@Data`       |
| --------- | ----------------------- | -------------------- |
| 不可变       | ✅ 字段天然 `final`          | ❌ 默认可变（要配 `@Value`）  |
| 需要插件      | ❌ 语言特性                  | ✅ 需要 IDE 插件 + APT 依赖 |
| 支持继承      | ❌ 不能继承（隐式 `final`）      | ✅                    |
| 支持附加字段    | ❌ 只能有构造器参数对应的字段（静态字段除外） | ✅                    |
| getter 命名 | `x()`                   | `getX()`             |
| 适用        | **纯数据载体（DTO、值对象）**      | 需要可变/继承/Builder 时    |

**结论**：**新代码里"纯值对象"优先用 `record`**；需要 Builder、可变、或继承老类时才用 Lombok。

### 14.4 Java 17：密封类 + 模式匹配

```java
// sealed（密封类）—— 限定谁能继承我
public sealed interface Shape permits Circle, Rectangle, Triangle {}
public record Circle(double r) implements Shape {}
public record Rectangle(double w, double h) implements Shape {}
public final class Triangle implements Shape {}   // 非 record 也行，但必须是 final/sealed/non-sealed

// 子类必须三选一：final（不能再继承）、sealed（继续限定）、non-sealed（开放继承）
```

**`sealed` 的真正价值在 Java 21 才完全体现**——配合 switch 模式匹配做**穷尽性检查**（编译器知道只有三个实现，所以 switch 不用写 default）：

```java
// Java 21
double area(Shape s) {
    return switch (s) {
        case Circle c        -> Math.PI * c.r() * c.r();
        case Rectangle r     -> r.w() * r.h();
        case Triangle t      -> 0;      // 编译器知道这是最后一个，不用 default
    };
}
```

**为什么这很重要**：**"新增一个实现类，编译器会报错告诉你漏了分支"**——这是代数数据类型（ADT）的核心好处。**Kotlin 的 `sealed class` + `when` 早就有了这个能力**，Java 到 21 才追上。

### 14.5 Java 21：虚拟线程（最大的变化）

**先理解问题**：平台线程（Platform Thread）1:1 映射到操作系统线程，**一个线程约 1MB 栈**，所以"线程数上限"很低（几千个就到顶了）。而**阻塞是常态**（IO、锁、sleep）——一个线程阻塞时它占着 OS 线程空等。

**虚拟线程（Virtual Thread）**：

```
       虚拟线程（成千上万，由 JVM 调度）
    ┌────┬────┬────┬────┬────┬────┐
    │ V1 │ V2 │ V3 │ V4 │ ... │ Vn │      ← 用户态，栈是堆上的对象
    └────┴────┴────┴────┴────┴────┘
              ↓ 挂载/卸载（mount/unmount）
    ┌──────────┬──────────┐
    │ 载体线程  │ 载体线程  │  ...        ← ForkJoinPool，数量 ≈ CPU 核数
    └──────────┴──────────┘
              ↓
         操作系统线程
```

**关键机制**：虚拟线程执行阻塞操作（IO）时，JVM 把它从载体线程**卸载**（`unmount`），载体线程去跑别的虚拟线程；IO 完成后再**挂载**回来。**所以阻塞不再是浪费。**

```java
// 1. 每任务一个虚拟线程
Thread.startVirtualThread(() -> handle(request));

// 2. 按任务量的执行器（推荐写法）
try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
    for (var req : requests) ex.submit(() -> handle(req));
}
// try-with-resources 退出时自动 close + 等待完成

// 3. 工厂
Thread.ofVirtual().name("vt-", 0).factory();
```

**对使用方式的影响：几乎为零。** 虚拟线程不需要学新的异步 API——**写同步阻塞代码就能获得高并发**。这是它相比响应式（Reactor/RxJava）最大的优势：**不用把代码写成回调链**。

**四个限制（必须知道）**：

| 限制                             | 说明                                                                                                                                   |
| ------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------ |
| **不是更快**                       | 虚拟线程不提升 CPU 计算速度，只提升"IO 密集 + 高并发"的吞吐                                                                                                 |
| **`synchronized` 会 pin 住载体线程** | 在 `synchronized` 块里阻塞会导致载体线程无法卸载（**JDK 21 仍有此问题**，JDK 24 起 `synchronized` 的 pinning 才被解决）。**对策：用 `ReentrantLock` 代替 `synchronized`** |
| **`ThreadLocal` 慎用**           | 虚拟线程数量巨大，每个都带 `ThreadLocal` 会内存爆炸。用 `ScopedValue`（JDK 21 预览）替代                                                                       |
| **CPU 密集任务无收益**                | 用平台线程池                                                                                                                               |

**Android 的现实**：**Android 上没有虚拟线程**（ART 不实现），而且 Android 的运行时是 ART 不是 HotSpot。**但概念是相通的**——Kotlin 协程就是"用户态轻量级线程"在语言层的实现：

|         | 虚拟线程（Java 21）        | 协程（Kotlin）               |
| ------- | -------------------- | ------------------------ |
| 层面      | JVM 运行时              | 语言 + 编译器 + 库             |
| 写法      | **同步阻塞式**（改了运行时不改代码） | `suspend` 函数（要改签名）       |
| 取消      | `interrupt`          | **结构化并发 + `Job` 树（更完善）** |
| 调度      | ForkJoinPool         | `Dispatchers`（可自定义）      |
| Android | ❌ 不可用                | ✅ **首选**                 |

**这个对照值得记住**：它们是"同一个问题的两种不同层级的解法"。理解虚拟线程的挂载/卸载机制，**回头再看协程的 `suspend` 点在哪、为什么 `suspend` 函数必须由 `suspend` 调用**，会清晰很多。

### 14.6 升级时的"踩雷清单"

| 变更                                               | 版本           | 影响                                                                    |
| ------------------------------------------------ | ------------ | --------------------------------------------------------------------- |
| **JPMS 强封装**                                     | 16 默认        | 反射访问 `sun.*` / JDK 内部 → `InaccessibleObjectException`，需 `--add-opens` |
| **反射 inflation 移除**                              | 18           | `Method.invoke` 统一走 MethodHandle，性能特征变化                               |
| **偏向锁移除**                                        | 18           | 锁升级链变短（12.5 节）                                                        |
| **`sun.misc.Unsafe` 内存方法废弃**                     | 9 起警告，后续移除   | 改用 `VarHandle` / `MemorySegment`                                      |
| **`SecurityManager` 弃用**                         | 17           | 别再用它做沙箱                                                               |
| **`finalize()` 弃用**                              | 9 起，18 起标记移除 | 改用 `Cleaner` / try-with-resources                                     |
| **Nashorn JS 引擎移除**                              | 15           | 用 GraalVM JS                                                          |
| **`Thread.stop` / `suspend` / `resume`**         | 长期废弃         | 用中断（12.7 节）                                                           |
| **`Date` / `Calendar` 的 getter 弃用**              | 17           | 用 `java.time`                                                         |
| **默认字符集变更**                                      | 18           | **文件读写默认 UTF-8**（以前依赖平台编码）——**这会改变行为！**                               |
| **`java.util.concurrent` 的 `ThreadLocalRandom`** | ——           | 替代 `Random`（并发下性能更好）                                                  |

**"默认 UTF-8"（JEP 400，JDK 18）特别值得记住**：以前 `new FileReader(f)` 用平台默认编码（Windows 上是 GBK），JDK 18 起统一 UTF-8。**在老项目升级到 18+ 时，读写中文文件可能突然乱码**。这是升级时最容易忽略的行为变更。

**`finalize()` 与 `Cleaner`**（值得记，因为 Android 项目里也有资源清理问题）：

```java
// ❌ finalize 的问题：调用时机不确定、可能根本不调用、慢、可能复活对象
@Override protected void finalize() { resource.close(); }

// ✅ Cleaner（Java 9+）
public class Resource implements AutoCloseable {
    private static final Cleaner CLEANER = Cleaner.create();
    private final Cleaner.Cleanable cleanable;
    private final NativeHandle handle;

    Resource() {
        this.handle = new NativeHandle();
        this.cleanable = CLEANER.register(this, new CleaningAction(handle));
    }
    @Override public void close() { cleanable.clean(); }
}
```

**Android 上的对应物**：`androidx.core.content.FileProvider` 之类的资源管理、`ViewBinding` 的 `unbind()`、`Cursor` 的 `close()`、`Bitmap.recycle()`。**核心原则一样：显式释放 > 靠 GC。**

---

## 附录 A Java ↔ Kotlin 速查表

> 用法：用你现在的 Kotlin 直觉去反查 Java 写法。这张表按"你会卡住的位置"组织。

### A.1 类型与声明

| Java                             | Kotlin                                     | 备注                                 |
| -------------------------------- | ------------------------------------------ | ---------------------------------- |
| `final String s = "x";`          | `val s = "x"`                              | Kotlin 的 `val` 是"只读引用"非"不可变对象"     |
| `String s = "x";`                | `var s = "x"`                              | 类型可省略                              |
| `static final int N = 1;`        | `companion object { const val N = 1 }`     | 编译期常量                              |
| `static void f()`                | `companion object { fun f() }` 或顶层函数       |                                    |
| `class A extends B implements C` | `class A : B(), C`                         | 冒号统一                               |
| `@Override`                      | `override`                                 | Kotlin 是关键字，**必须写**                |
| `List<String>`                   | `List<String>`（只读） / `MutableList<String>` | Kotlin 编译期区分                       |
| `void`                           | `Unit`                                     |                                    |
| `T` 上界 `T extends Number`        | `T : Number`                               |                                    |
| `List<? extends T>`              | `List<out T>`                              |                                    |
| `List<? super T>`                | `List<in T>`                               |                                    |
| `Class<?>`                       | `KClass<*>`, 通过 `.java` 交互                 |                                    |
| `Object`                         | `Any`                                      | 非空 `Any` / 可空 `Any?`               |
| `Integer`                        | `Int`（自动装箱，无区分）                            | Kotlin 无原始类型/包装类型之分                |
| `void f(String... a)`            | `fun f(vararg a: String)`                  |                                    |
| `String[] arr`                   | `Array<String>` / `IntArray`               | Kotlin 有专门的基本类型数组                  |
| `T get()` 方法                     | 属性 `val x: T`                              | Kotlin 属性访问语法                      |
| `enum class E { A, B }`          | `enum class E { A, B }`                    | 基本一致                               |
| 无                                | `data class`                               | 自动 equals/hashCode/copy/componentN |
| 无                                | `sealed class/interface`                   | Java 17 才有 `sealed`                |
| 无                                | `object`（单例）                               | Java 要写枚举或 DCL                     |

### A.2 控制流与表达式

| Java                              | Kotlin                                         |
| --------------------------------- | ---------------------------------------------- |
| `switch` 语句（穿透）                   | `when` 表达式（**不穿透，必须有 `else` 或穷尽**）             |
| `switch` 表达式（14+，`->` 不带 `break`） | `when { }`                                     |
| `a == b`（等价 `equals`）             | `a == b`（**等价 `equals`**，自动空安全）                |
| `a.equals(b)`                     | `a == b`                                       |
| `a == b`（**引用比较**，仅对象）            | `a === b`                                      |
| `a != null ? a : "x"`             | `a ?: "x"`（Elvis）                              |
| `x != null ? f(x) : null`         | `x?.let { f(it) }`                             |
| `if (x != null) { use(x) }`       | `x?.let { use(it) }`                           |
| 受检异常强制 try/catch                  | 无受检异常                                          |
| `try { } catch { }` 语句            | `try` 是**表达式**                                 |
| `for (int i = 0; i < n; i++)`     | `for (i in 0 until n)`                         |
| `for (String s : list)`           | `for (s in list)`                              |
| —                                 | `for (i in n downTo 0 step 2)`                 |
| —                                 | `list.forEach { }` / `map` / `filter`（标准库直接可用） |

### A.3 空安全（Java 完全没有）

| Java 的常见做法                         | Kotlin                                   |
| ---------------------------------- | ---------------------------------------- |
| `@Nullable` / `@NonNull` 注解（靠工具检查） | `String?` / `String`（**编译器强制**）          |
| `if (x != null) x.f()`             | `x?.f()`                                 |
| `x != null ? x : def`              | `x ?: def`                               |
| 直接 `x.f()`（可能 NPE）                 | 编译不通过，除非 `x!!.f()`（**显式危险**）             |
| `Objects.requireNonNull(x)`        | `requireNotNull(x)` / `x!!`              |
| —                                  | `x!!.f()` 抛 `KotlinNullPointerException` |
| —                                  | 平台类型 `String!`（Java 来的，**编译器不管**，见姊妹篇）   |

### A.4 集合与函数式

| Java                                          | Kotlin                                   |
| --------------------------------------------- | ---------------------------------------- |
| `list.stream().filter(...).collect(toList())` | `list.filter { }`                        |
| `list.stream().map(...)`                      | `list.map { }`                           |
| `list.stream().flatMap(...)`                  | `list.flatMap { }`                       |
| `Collectors.groupingBy`                       | `groupBy { }`                            |
| `Collectors.toMap`                            | `associate { }` / `associateBy { }`      |
| `Collectors.joining(", ")`                    | `joinToString(", ")`                     |
| `list.forEach(x -> {})`                       | `list.forEach { }`                       |
| `Collections.sort(list, cmp)`                 | `list.sortedWith(cmp)`                   |
| `Collections.emptyList()`                     | `emptyList()`                            |
| `List.of(...)`                                | `listOf(...)`                            |
| `Arrays.asList(...)`                          | `listOf(...)` / `array.toList()`         |
| `new HashMap<>()`                             | `mutableMapOf()` / `hashMapOf()`         |
| `map.getOrDefault(k, v)`                      | `map.getOrDefault(k, v)` / `map[k] ?: v` |
| `map.computeIfAbsent`                         | `map.getOrPut(k) { }`                    |
| `String.join(",", list)`                      | `list.joinToString(",")`                 |
| `String.format("%s-%d", a, b)`                | `"$a-$b"`                                |
| 原始类型 `int[]` + `Arrays.sort`                  | `IntArray` + `sorted()`                  |

### A.5 并发

| Java                              | Kotlin                                                  |
| --------------------------------- | ------------------------------------------------------- |
| `synchronized (obj) { }`          | `synchronized(obj) { }`（同）                              |
| `ReentrantLock` + `try/finally`   | `lock.withLock { }`（**自动释放**）                           |
| `Thread` / `ExecutorService`      | `Thread` / `ExecutorService`（可用）+ **协程**                |
| `CompletableFuture<T>`            | `Deferred<T>` / `suspend fun`                           |
| `CompletableFuture.allOf(...)`    | `coroutineScope { async { } }` + `awaitAll()`           |
| `Future.get()`（阻塞）                | `Deferred.await()`（挂起，不阻塞线程）                            |
| `CountDownLatch`                  | `CountDownLatch` / `Job` + `join()` / `Mutex`           |
| `AtomicInteger.incrementAndGet()` | `AtomicInteger` 同（或用 `Mutex`）                           |
| `volatile int x;`                 | `@Volatile var x: Int`                                  |
| `ThreadLocal<T>`                  | `ThreadLocal<T>`（Android 上协程里要用 `ThreadContextElement`） |
| 无                                 | `Mutex` / `Semaphore` / `Channel` / `Flow`              |

### A.6 反射与元编程

| Java                           | Kotlin                                  |
| ------------------------------ | --------------------------------------- |
| `obj.getClass()`               | `obj::class` / `obj.javaClass`          |
| `Foo.class`                    | `Foo::class` / `Foo::class.java`        |
| `Class.forName("...")`         | `Class.forName("...")`（同）               |
| `Method.invoke(obj, args)`     | 同                                       |
| `clazz.getAnnotation(A.class)` | `clazz.getAnnotation(A::class.java)`    |
| `clazz.getDeclaredField("f")`  | 同                                       |
| 注解处理器 APT                      | **KSP**（推荐）/ KAPT（兼容旧）                  |
| `Serializable`                 | `Serializable`（同，需显式）                   |
| `Parcelable`（Android）          | `@Parcelize`（kotlin-parcelize 插件）       |
| `java.lang.reflect.Proxy`      | `java.lang.reflect.Proxy`（同，lambda 更简洁） |
| `interface + 委托`               | `by` 关键字（**编译期生成，无反射**）                 |

---

## 附录 B 一图总结

```
┌──────────────────────────────────────────────────────────────────────────┐
│                     Java 核心机制：一张图串起来                             │
└──────────────────────────────────────────────────────────────────────────┘

【第一层 类型系统 —— 一切的根源】
   泛型 → 编译期语法糖 → 运行期擦除
        ├─ 擦除到上界（无上界→Object），并在调用点插入强转
        ├─ 但"声明位置"的泛型留在 Signature 属性里 ← 反射能读到
        ├─ 副作用：桥接方法（编译器补的转发方法）、重载冲突、不能 new T[]
        └─ PECS：Producer Extends（只读），Consumer Super（只写）
                └─ 判据：extends 禁用"写"，super 禁用"按具体类型读"

【第二层 运行期魔法 —— 靠"读元数据"实现】
   反射 ──→ 逃逸擦除、突破封装
        ├─ Declared = 只看自己；不带的 = 含继承
        ├─ JDK 16+ 强封装：sun.* 访问需 --add-opens
        └─ JDK 18 起：inflation 机制移除，改走 MethodHandle

   动态代理 ──→ "运行期生成类 + 统一转发"
        ├─ JDK 代理：$Proxy0 extends Proxy implements 接口
        │     └─ 因为单继承 + 已 extends Proxy ⇒ 【只能是接口】
        ├─ CGLIB：extends 目标类 ⇒ 不能是 final 类/方法
        │     └─ Spring Boot 2+ 默认全用 CGLIB（要能按类注入）
        ├─ 三个坑：proxy 参数 ≠ target（会递归）；
        │          equals/hashCode/toString 也被转发；
        │          受检异常被包成 UndeclaredThrowableException
        └─ 典型应用：Retrofit / MyBatis Mapper / Spring AOP / 事务

   注解 ──→ 元数据，默认 CLASS 策略（反射读不到，必须 RUNTIME）
   类加载 ──→ 双亲委派（先问父，再自己找）
        └─ 判"同一个类"= 类名 + ClassLoader 都相同
              └─ Android：BootClassLoader → PathClassLoader → DexClassLoader
                        热修复 = 往 dexElements 前面插补丁

【第三层 对象契约与库】
   equals/hashCode：相等 ⇒ 哈希必等（反向不成立）
        └─ 可变对象做 key = 永久丢失在哈希表里
   HashMap：容量 2 的幂（为了 (n-1)&hash）、树化阈值 8 / 退化 6（留缓冲防抖动）
   ConcurrentHashMap：JDK 7 分段锁 → JDK 8 CAS + 锁桶头 + 协作扩容
   String：不可变 ⇒ 可缓存 hash + 常量池共享；JDK 9 起 byte[] 紧凑存储
   内部类：非静态内部类隐式持有外部 this$0 ⇒ 静态字段 + 匿名类 = Activity 泄漏
   Lambda：与匿名内部类本质不同（无自己的 this、通常不生成 class 文件）
   异常：受检异常现代框架基本抛弃；finally 里别 return
   IO：NIO 三件套 Channel/Buffer/Selector；Buffer 的 flip/clear 手工切换
        零拷贝：transferTo/sendfile，注意要循环调用

【第四层 并发】
   JMM 三特性：原子性 / 可见性 / 有序性，靠 happens-before 规则推理
   volatile：可见 + 禁重排，【不保证原子性】
        └─ DCL 必须 volatile（防止"对象逸出"看到半成品）
   synchronized：锁 this / Class / 指定对象；锁升级（JDK 18 已去掉偏向锁）
   wait/notify：wait 释放锁、sleep 不释放；必须 while 循环 + notifyAll
   中断：唯一正确的停止方式，捕获 InterruptedException 后要恢复标志

   AQS：state + CLH 队列 → ReentrantLock / Semaphore / CountDownLatch
   线程池：核心 → 队列 → 最大 → 拒绝（【队列满了才扩容！】）
        └─ 别用 Executors（无界队列 → OOM）
        └─ 任务抛异常会被静默吞掉 ⇒ 必须设 UncaughtExceptionHandler
   CompletableFuture：thenApply（map）vs thenCompose（flatMap）
        └─ 不带 Async 后缀 ⇒ 可能跑在"当前线程"（Android 上可能是 UI 线程）
   LongAdder > AtomicLong（高竞争）；AtomicStampedReference 解决 ABA

【版本演进 —— 需要更新的直觉】
   JDK 8  → Lambda/Stream/Optional/java.time（Android 支持最好）
   JDK 11 → String 新方法、Files.readString、HTTP Client
   JDK 17 → sealed、instanceof 模式匹配、强封装 JDK 内部
   JDK 18 → 偏向锁移除、inflation 移除、【默认 UTF-8】（老项目升级会乱码！）
   JDK 21 → 虚拟线程（同步阻塞写法 + 高并发）、switch 模式匹配
        └─ 概念对照：虚拟线程 ≈ JVM 版协程（挂载/卸载 vs suspend 点）
```

**一句话记忆链**：

> 泛型擦除 → 所以需要**反射** → 反射批量转发就成了**动态代理** → 代理和反射都靠**注解**驱动 → 类的边界由**类加载器**划 → 对象的身份由 **equals/hashCode** 定 → 集合和 String 的优化都依赖**不可变** → 内部类和 lambda 差一个 **this** → 异常决定错误怎么传 → IO 决定数据怎么流 → 并发靠 **JMM + happens-before** 推理 → `volatile` 管可见性、`synchronized` 管原子性 → 高并发靠 **AQS + 线程池** → 而 JDK 21 的**虚拟线程**把"线程"这个概念的成本重新定义了一遍。

---

## 关联阅读

同目录 / 姊妹篇：

| 文档                                                            | 为什么读它                                                                                                                       |
| ------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| `../kotlin/kotlin-Java互操作详解.md`                               | **本文的补充：反向视角**。这篇讲"Kotlin 调 Java 时平台类型 `String!`、`@JvmStatic`、SAM 转换"等边界问题；本文讲 Java 本身的机制。**两篇合起来才构成完整的 Java/Kotlin 双语视角。** |
| `../kotlin/kotlin-泛型out-in与reified详解.md`                      | 第 1 章的 Kotlin 对照展开版：声明处型变、星投影、`reified` 的 inline 原理                                                                         |
| `../kotlin/kotlin-委托与by关键字详解.md`                              | 第 3.9 节的展开：Kotlin 用 `by` 在编译期做代理，是动态代理的"零成本"替代                                                                              |
| `../kotlin/kotlin-coroutines-guide.md`                        | 第 14.5 节虚拟线程的对照：协程的 `suspend` 与调度器                                                                                          |
| `../androidOthers/Android虚拟机详解-从Dalvik到ART与代码形态演变.md`         | 第 5 章的 Android 侧展开：ART 的类加载、dexElements、AOT/JIT                                                                             |
| `../androidOthers/AndroidNDK与JNI详解-从跨语言调用到so加载与Native崩溃排查.md` | 第 10、11 章的边界延伸：JNI 里怎么处理 Java 异常、引用与线程                                                                                      |
| `../androidOthers/Android构建系统详解-从Soong到AOSP整机集成.md`           | 第 14 章 Android 侧的落地：AGP / desugar / R8 混淆与 keep 规则                                                                          |
| `../androidFrameworks/11_WMS机制详解-从窗口添加到显示管理.md`               | 并发与 IPC 在 Framework 层的实战：`synchronized`/`Handler`/Binder 线程池怎么用                                                             |
