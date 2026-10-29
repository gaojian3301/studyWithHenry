# C++ 关键字完全详解

> 本手册系统讲解 C++ 的关键字，按功能分类，配有代码示例和注意事项。
> 覆盖 C++11 至 C++20/23 的关键字，标注各关键字引入的版本。

---

## 目录

1. [基本数据类型关键字](#一基本数据类型关键字)
2. [类型修饰符](#二类型修饰符)
3. [类型定义与构造](#三类型定义与构造)
4. [控制流程关键字](#四控制流程关键字)
5. [存储与生命周期](#五存储与生命周期)
6. [面向对象关键字](#六面向对象关键字)
7. [异常处理关键字](#七异常处理关键字)
8. [编译期与特殊关键字](#八编译期与特殊关键字)
9. [C++20/23 新增关键字](#九c2023-新增关键字)
10. [附：完整关键字速查表](#十附完整关键字速查表)

---

## 一、基本数据类型关键字

| 关键字 | 含义 | 典型大小 | 说明 |
|--------|------|----------|------|
| `char` | 字符 | 1 字节 | 通常存储 ASCII 字符 |
| `short` | 短整数 | 2 字节 | 至少 2 字节 |
| `int` | 整数 | 4 字节 | 平台相关，通常 4 字节 |
| `long` | 长整数 | 4 或 8 字节 | 至少 4 字节 |
| `long long` | 长长整数 | 8 字节 | C++11 明确至少 8 字节 |
| `float` | 单精度浮点 | 4 字节 | 约 7 位有效数字 |
| `double` | 双精度浮点 | 8 字节 | 约 15 位有效数字 |
| `bool` | 布尔值 | 1 字节 | `true` / `false` |
| `void` | 无类型 | — | 无返回值的函数、空指针 |
| `wchar_t` | 宽字符 | 平台相关 | 用于多语言字符集 |

### 详细示例

```cpp
#include <iostream>

int main() {
    // 整数类型
    int    n   = 42;                 // 普通整数
    short  s   = 100;                // 短整数，注意范围 -32768~32767
    long   l   = 100000L;            // 长整数，后缀 L 表示 long
    long long ll = 1000000000LL;     // 8 字节，后缀 LL

    // 浮点类型
    float  f   = 3.14f;              // 单精度，后缀 f
    double d   = 3.14159265358979;   // 双精度，默认

    // 字符与布尔
    char   c   = 'A';                // 单个字符
    bool   b   = true;               // true 或 false

    // 输出各类型大小
    std::cout << "sizeof(int)    = " << sizeof(int)    << '\n';
    std::cout << "sizeof(double) = " << sizeof(double) << '\n';
    return 0;
}
```

### 关键注意点

1. **`int` 大小不固定**：在 32 位系统通常 4 字节，在 16 位系统可能是 2 字节。跨平台需要固定宽度类型时，用 `<cstdint>` 的 `int8_t`/`int32_t`/`int64_t` 等。
2. **`void` 的两种用法**：
   - 作为函数返回类型：`void func() { }` 表示无返回值。
   - 空指针：`void*` 表示"指向未知类型的指针"，需要转换后使用。
3. **`bool` 本质**：只有 `true`(1) 和 `false`(0)，任何非零整数转为 `bool` 都是 `true`。
4. **`char` 的有符号性**：`char` 是否带符号取决于实现，若需要明确符号用 `signed char` 或 `unsigned char`。
5. **`wchar_t` 注意**：在 Windows 是 2 字节（UTF-16），在 Linux 是 4 字节。跨平台更推荐 `char16_t`/`char32_t` 或 `std::string`/`std::wstring`。

---

## 二、类型修饰符

修饰符用于改变类型或变量的属性。

### 2.1 `signed` / `unsigned`

指定整数类型的符号性。

```cpp
unsigned int  u = 42;    // 无符号，范围 0 ~ 4294967295
signed int    n = -5;    // 有符号，可表示负数
unsigned char uc = 200;  // 无符号字符 0~255
```

**注意**：
- `unsigned` 单独使用等价于 `unsigned int`。
- 无符号类型做运算可能"溢出回绕"，且与有符号混用时容易出现微妙错误。

### 2.2 `const` — 常量

值在初始化后不可修改。是 C++ 使用频率最高的关键字之一。

```cpp
const int MAX = 100;            // 整型常量
const double PI = 3.14159;      // 浮点常量
const std::string NAME = "Hi";  // 字符串常量

// MAX = 200;  // 错误：不能修改 const 变量

// const 修饰指针的三种情况：
const int* p1;       // 指针指向 const int（指针可变，指向的值不可变）
int* const p2;       // const 指针（指针不可变，指向的值可变）
const int* const p3; // 都不可变

// const 成员函数：承诺不修改对象状态
class Counter {
    int value = 0;
public:
    int get() const { return value; }  // 只能读，不能写 value
    void increment() { ++value; }      // 非 const 函数可修改
};
```

**注意**：`const` 对象只能调用 `const` 成员函数。

### 2.3 `volatile` — 易变

告诉编译器"这个变量可能被外部修改"，每次访问都必须从内存读取，禁止优化到寄存器缓存。

```cpp
volatile int flag;      // 硬件寄存器、中断、多线程共享变量常用

// 场景：等待硬件中断置位
while (flag == 0) {
    // 若无 volatile，编译器可能把 flag 缓存到寄存器，导致死循环
}
```

**注意**：`volatile` 不是线程同步机制！多线程共享变量应使用 `std::atomic` 或互斥锁。

### 2.4 `constexpr`（C++11）— 编译期常量表达式

在编译期就计算出值的表达式或函数，比 `const` 更强大。

```cpp
constexpr int square(int x) { return x * x; }
constexpr int result = square(5);   // 编译期算出 25，无运行时开销

// 可用于数组大小（编译期必须确定）
constexpr int N = 100;
int arr[N];                         // 合法

// constexpr 函数要求：简单到能在编译期求值
constexpr int max(int a, int b) {
    return a > b ? a : b;   // C++14 起允许局部变量和循环
}
```

**C++20 增强**：`constexpr` 函数几乎可以写任何代码（动态分配、虚函数等），称为"约束放宽"。

### 2.5 `mutable` — 可变

允许在 `const` 成员函数中修改被它修饰的成员。

```cpp
class Cache {
    mutable int hitCount = 0;   // 关键：mutable
public:
    // 这是一个 const 方法，但可以修改 hitCount
    int get(int key) const {
        ++hitCount;             // 合法！因为 hitCount 是 mutable
        return key;             // 假实现
    }
};
```

**用途**：缓存计数、懒加载的内部状态、互斥锁等"逻辑上不影响对象状态"的成员。

---

## 三、类型定义与构造

### 3.1 `class` / `struct`

两者几乎相同，唯一区别是**默认访问权限**：`class` 默认私有（`private`），`struct` 默认公有（`public`）。

```cpp
class Dog {            // 默认 private
    int age;           // private
public:
    void bark() {}
};

struct Point {         // 默认 public
    int x, y;          // public
};
```

**实践**：数据集合（纯数据）用 `struct`；有封装逻辑的用 `class`。

### 3.2 `union` — 联合体

所有成员**共享同一块内存**，同一时刻只能使用其中一个成员。节省内存。

```cpp
union Value {
    int    i;
    float  f;
    char   bytes[4];
};
// sizeof(Value) == 4，三者共享这 4 字节

Value v;
v.i = 42;      // 此时使用 int
// v.f = 1.5;  // 若换用 float，则 i 的值会被覆盖
```

**C++11 后**：联合体成员可以是非平凡类型（如 `std::string`），但需要手动管理生命周期，推荐用 `std::variant` 替代大多数场景。

### 3.3 `enum` — 枚举

**旧式枚举（C++98）**：

```cpp
enum Color { RED, GREEN, BLUE };   // RED=0, GREEN=1, BLUE=2
enum Color { RED, GREEN = 10, BLUE }; // BLUE = 11

// 缺点：会污染外层作用域，可能与其他枚举冲突
enum Color { RED };
enum TrafficLight { RED };  // 错误！RED 重复
```

**强类型枚举（C++11）** —— 推荐使用：

```cpp
enum class Color { RED, GREEN, BLUE };
enum class TrafficLight { RED, GREEN };   // 可以同名，互不干扰

Color c = Color::RED;        // 必须带作用域
// int n = Color::RED;       // 错误！不能隐式转为 int
int n = static_cast<int>(Color::RED);  // 需要显式转换
```

**底层类型**：可指定 `enum class Color : uint8_t { ... }` 控制内存大小。

### 3.4 `typedef`（旧）/ `using`（推荐）

定义类型别名。

```cpp
// 旧式（C++98）
typedef unsigned long ulong;
typedef void (*FuncPtr)(int);       // 函数指针别名

// 新式（C++11，更清晰，支持模板）
using ulong = unsigned long;
using FuncPtr = void (*)(int);

// using 在模板别名上的优势（typedef 做不到）：
template <typename T>
using Vec = std::vector<T>;
// typedef std::vector<T> Vec;   // 错误：typedef 不能做模板别名
```

### 3.5 `namespace` — 命名空间

用于组织代码、避免命名冲突。

```cpp
namespace math {
    const double PI = 3.14159;
    double square(double x) { return x * x; }
}

namespace my::inner {        // C++17 嵌套命名空间简写
    int value = 10;
}

// 使用
using namespace math;        // 导入全部（谨慎，可能冲突）
using math::PI;              // 只导入一个
math::square(3.0);           // 直接用作用域限定符

// 匿名命名空间：本文件内部链接（类似 static）
namespace { int internalVar = 0; }
```

**注意**：`using namespace std;` 在头文件里是坏习惯，应避免（会造成污染）。

### 3.6 `template` / `typename`

泛型编程的核心。

```cpp
// 函数模板
template <typename T>
T max(T a, T b) { return a > b ? a : b; }

// 类模板
template <typename T, int N>       // 类型参数 + 非类型参数
class Array {
    T data[N];
public:
    T& at(int i) { return data[i]; }
};

// 调用
int    m = max(3, 5);              // 推导 T = int
double d = max(3.5, 2.1);          // 推导 T = double
Array<double, 10> arr;             // 实例化模板
```

**`typename` 的第二个用途**：表示"依赖类型"（模板参数中嵌套的类型）。

```cpp
template <typename T>
void f() {
    typename T::iterator it;       // T::iterator 是依赖类型，必须加 typename
    // 否则编译器可能把它当成一个静态成员变量名
}
```

### 3.7 `class` 关键字在模板中的用法

`template <class T>` 等价于 `template <typename T>`，只是历史遗留的写法。

---

## 四、控制流程关键字

### 4.1 条件分支

**`if` / `else if` / `else`**

```cpp
int x = 10;
if (x > 0) {
    std::cout << "正数";
} else if (x < 0) {
    std::cout << "负数";
} else {
    std::cout << "零";
}
```

**`switch` / `case` / `default`**

```cpp
switch (level) {
    case 1:  std::cout << "低级"; break;
    case 2:
    case 3:  std::cout << "中级"; break;   // case 2 和 3 走同一逻辑
    case 10: std::cout << "高级"; break;
    default: std::cout << "未知"; break;
}
```

**注意**：
- `switch` 表达式要求整数类型（或可转为整数的枚举）。
- 每个 `case` 末尾记得 `break`，否则会"穿透"到下一个 case（这有时是有意的）。
- `switch` 比一串 `if/else` 效率高（编译器可生成跳转表）。

**C++17 初始化语句**（if/switch 可带初始化）：

```cpp
if (int len = s.size(); len > 10) {   // len 只在 if 内可见
    // ...
}
switch (auto code = parse(); code) {
    case 1: ...
}
```

### 4.2 循环

**`for`**

```cpp
// 传统形式
for (int i = 0; i < 10; ++i) { ... }

// 范围 for（C++11，推荐）
for (const auto& item : vec) { ... }        // 只读
for (auto& item : vec) { item *= 2; }       // 修改
for (auto [k, v] : map) { ... }             // C++17 结构化绑定

// 注意：遍历时修改容器（如删除元素）会失效，需用迭代器
```

**`while`**

```cpp
while (condition) { ... }        // 先判断再执行
int i = 0;
while (i < 5) { ...; ++i; }
```

**`do` / `while`**

```cpp
int i = 0;
do {
    ...;    // 至少执行一次
    ++i;
} while (i < 5);
```

**注意**：`while` 内改变循环条件的代码要小心死循环。

### 4.3 跳转

**`break`** — 跳出当前 `for`/`while`/`do`/`switch`。

```cpp
for (int i = 0; i < 100; ++i) {
    if (i == 50) break;   // 提前结束循环
}
```

**`continue`** — 跳过本次循环剩余部分，进入下一次迭代。

```cpp
for (int i = 0; i < 10; ++i) {
    if (i % 2 == 0) continue;   // 跳过偶数
    std::cout << i;             // 只输出奇数
}
```

**`goto`** — 无条件跳转到标签。现代 C++ 几乎不用，但偶用于多层嵌套的快速退出。

```cpp
for (int i = 0; i < 10; ++i) {
    for (int j = 0; j < 10; ++j) {
        if (someError) goto cleanup;
    }
}
cleanup:
    // 处理错误
```

> 尽量用异常、函数拆分或标志变量代替 `goto`。

**`return`** — 返回函数值（或 void 函数中直接返回）。

```cpp
int add(int a, int b) { return a + b; }
void error() { return; }   // 提前结束 void 函数
```

---

## 五、存储与生命周期

### 5.1 `auto` — 自动类型推导

**C++98**：`auto` 是"自动存储类型"（几乎无意义）。

**C++11 起**：类型推导，根据初始化表达式推断变量类型。

```cpp
auto i = 5;               // int
auto d = 3.14;            // double
auto s = std::string();   // std::string
auto p = new Foo();       // Foo*

// 常见用途
for (const auto& item : container) { ... }   // 遍历
auto it = container.begin();                 // 迭代器类型很长时很实用

auto f = [](int a) { return a * 2; };        // lambda 类型

// 注意：
auto x = 1.5;             // double
auto y = 1.5f;            // float
auto z = 1;               // int
// auto 会去掉引用和顶层 const：
const int ci = 5;
auto a = ci;              // a 是 int（const 被去掉）
auto& b = ci;             // 加 & 才能保留 const 引用
```

**`auto` 配合尾置返回类型**：

```cpp
// C++11：函数返回值类型后置
template <typename T, typename U>
auto add(T a, U b) -> decltype(a + b) { return a + b; }

// C++14：可以省略尾置返回，直接推导
template <typename T, typename U>
auto add(T a, U b) { return a + b; }
```

### 5.2 `register` — 寄存器建议（已废弃）

提示编译器把变量放寄存器（现代编译器忽略此建议，C++17 已移除该关键字）。**不需要使用**。

### 5.3 `static` — 静态（多义）

`static` 有多个完全不同的含义，按上下文区分：

| 位置 | 含义 |
|------|------|
| 函数内部的局部变量 | 静态存储期：初始化一次，多次调用保持值 |
| 全局变量/函数 | 内部链接：仅当前文件可见 |
| 类的数据成员 | 属于类本身，所有对象共享，不随对象创建/销毁 |
| 类的成员函数 | 不依赖具体对象，可直接 `类名::函数` 调用 |

```cpp
// 1. 局部 static：只初始化一次，跨函数调用保持值
int counter() {
    static int count = 0;   // 首次调用初始化，之后保留
    return ++count;
}
// counter() 依次返回 1,2,3...

// 2. 全局 static：仅本文件可见（替代匿名命名空间）
static int fileLocal = 0;

// 3. 类的静态成员
class Config {
public:
    static int maxSize;      // 声明
    static void print() {}   // 静态成员函数
};
int Config::maxSize = 100;   // 定义（必须在类外定义）
Config::print();             // 通过类名直接调用，无需对象

// 4. 静态局部对象（单例模式常用）
class Logger {
public:
    static Logger& instance() {
        static Logger obj;   // 线程安全的局部静态初始化
        return obj;
    }
};
```

**注意**：类的静态成员在类内只是声明，必须在类外（或 C++17 的 `inline`）定义。

```cpp
// C++17：类内定义静态成员
class Config {
    static inline int maxSize = 100;   // 需要 inline
};
```

### 5.4 `extern` — 外部声明

声明变量/函数存在于**其他编译单元**（不在此处定义）。

```cpp
// file1.cpp
int globalVar = 10;

// file2.cpp
extern int globalVar;   // 声明：globalVar 在别处定义
void useIt() { globalVar = 20; }
```

**`extern "C"`** — 指定 C 语言的链接规则（不进行名字修饰），用于调用 C 库。

```cpp
extern "C" {
    #include "some_c_library.h"   // C 头文件
}
```

**`extern` 与 `const`**：`const` 变量默认有内部链接，若想让多个文件共享 `const` 全局，需加 `extern`：

```cpp
// header.h
extern const int SHARED;    // 声明

// shared.cpp
extern const int SHARED = 42;   // 定义
```

### 5.5 `thread_local`（C++11）— 线程本地存储

每个线程都有独立副本，互不干扰。

```cpp
thread_local int tlsCount = 0;   // 每个线程自己的 tlsCount

void worker() {
    ++tlsCount;                  // 线程间互不影响
}

// 输出 0（每个线程都是自己累加后的值，主线程仍是 0）
```

**注意**：`thread_local` 变量在 C 和 C++ 中链接规则不同，跨语言时小心。

### 5.6 `new` / `delete` — 动态内存

```cpp
// 单个对象
int* p = new int(42);      // 分配并初始化为 42
delete p;                  // 释放

// 数组
int* arr = new int[10];    // 分配数组（未初始化）
delete[] arr;              // 注意是 delete[] 不是 delete

// 对象
Foo* f = new Foo();        // 调用构造函数
delete f;                  // 调用析构函数

// 定位 new（placement new）：在指定内存构造对象
char buffer[sizeof(Foo)];
Foo* f2 = new (buffer) Foo();   // 不分配内存，只构造
f2->~Foo();                     // 手动调用析构
```

**重要**：
- `new[]` 必须配 `delete[]`，`new` 必须配 `delete`，混用是未定义行为。
- 现代 C++ **强烈推荐**使用智能指针，避免手动 `delete` 和内存泄漏：

```cpp
#include <memory>
std::unique_ptr<Foo> p = std::make_unique<Foo>();   // 自动释放
std::shared_ptr<Foo> sp = std::make_shared<Foo>();  // 引用计数
```

---

## 六、面向对象关键字

### 6.1 访问控制：`public` / `private` / `protected`

| 访问级别 | 本类 | 友元 | 派生类 | 外部 |
|----------|:----:|:----:|:------:|:----:|
| `public` | ✅ | ✅ | ✅ | ✅ |
| `protected` | ✅ | ✅ | ✅ | ❌ |
| `private` | ✅ | ✅ | ❌ | ❌ |

```cpp
class BankAccount {
public:                       // 外部可访问
    void deposit(double amt);
protected:                    // 派生类可访问，外部不可
    double interestRate;
private:                      // 只有本类和友元可访问
    double balance;
    std::string ownerName;
};
```

**注意**：`class` 默认 `private`，`struct` 默认 `public`。友元（`friend`）可以绕过访问控制。

### 6.2 `virtual` — 虚函数（多态）

允许通过基类指针/引用调用派生类的实现。

```cpp
class Shape {
public:
    virtual double area() const { return 0; }       // 虚函数
    virtual void   draw() = 0;                      // 纯虚函数（抽象类）
    virtual ~Shape() {}                             // 虚析构函数（重要！）
};

class Circle : public Shape {
    double r = 1.0;
public:
    double area() const override { return 3.14 * r * r; }
    void   draw() override { /* ... */ }
};

// 多态调用
Shape* s = new Circle();
std::cout << s->area();    // 调用 Circle 的 area（动态绑定）
delete s;                  // 若基类析构非 virtual，这里不会调用 ~Circle → 资源泄漏
```

**关键点**：
- **基类析构函数应该声明为 `virtual`**，否则通过基类指针 `delete` 派生类对象时，派生类析构不会被调用。
- 纯虚函数（`= 0`）使类成为**抽象类**，不能实例化。
- `virtual` 关键字只出现在基类声明中，派生类覆盖时用 `override`（C++11）。

### 6.3 `friend` — 友元

允许友元访问本类的私有/受保护成员。

```cpp
class Matrix;   // 前向声明

class Vector {
    double x, y;
public:
    friend Matrix operator*(const Matrix& m, const Vector& v);  // 全局函数友元
    friend class MatrixHelper;                                    // 类友元
    friend void debugPrint(const Vector&);                        // 全局函数友元
};
```

**注意**：友元破坏了封装，仅在确实需要时使用（如运算符重载、测试代码）。

### 6.4 `this` — 指向当前对象

在非静态成员函数内部，`this` 指向调用该函数的对象。

```cpp
class Person {
    std::string name;
public:
    Person& set(const std::string& name) {
        this->name = name;    // 区分成员与参数
        return *this;         // 支持链式调用
    }
};
// p.set("A").set("B");   // 链式
```

**注意**：`this` 是**指针**（`Person* const this`），在 `const` 成员函数里是 `const Person* const this`。静态成员函数没有 `this`。

### 6.5 `operator` — 运算符重载

允许为用户自定义类型重载运算符。

```cpp
class Complex {
    double re, im;
public:
    Complex(double r = 0, double i = 0) : re(r), im(i) {}

    Complex operator+(const Complex& o) const {   // +
        return Complex(re + o.re, im + o.im);
    }
    Complex& operator+=(const Complex& o) {        // +=
        re += o.re; im += o.im;
        return *this;
    }
    bool operator==(const Complex& o) const {      // ==
        return re == o.re && im == o.im;
    }
    Complex operator-() const { return Complex(-re, -im); }  // 一元负号
};
```

**可重载的运算符**：算术（`+ - * / %`）、比较（`== < >`）、赋值（`= +=`）、下标（`[]`）、调用（`()`）、指针（`* ->`）、`<< >>`、`++ --`、`new delete` 等。

**注意**：
- 不能重载的运算符：`.` `::` `.*` `?:` `sizeof` `typeid`。
- 建议遵循"运算符语义"惯例，不要重载 `+` 来做减法。
- 若定义了 `operator==`，C++20 建议同时实现 `operator<=>`（三向比较）。

### 6.6 `override`（C++11）

显式标记"我正在覆盖基类的虚函数"。**编译器会检查签名是否匹配**，是重要安全特性。

```cpp
class Derived : public Base {
public:
    void draw() override;         // ✅ 覆盖正确
    // void draw(int x) override; // ❌ 编译错误：签名不匹配
};
```

**好处**：签名写错时，编译器能立即报错，而不是悄悄形成重载导致隐蔽 bug。

### 6.7 `final`（C++11）

防止类被继承 / 虚函数被覆盖。

```cpp
class Base {
public:
    virtual void f() {}
    virtual void g() {}
};

class Derived final : public Base {   // final 类：不能再被继承
    void f() override final {}        // final 函数：派生类不能再覆盖
};

// class Sub : public Derived {};     // ❌ 错误：Derived 是 final
```

### 6.8 `explicit` — 禁止隐式转换

阻止构造函数/转换函数被用于隐式类型转换。

```cpp
class MyInt {
    int value;
public:
    explicit MyInt(int n) : value(n) {}
};

void func(MyInt m) {}

// func(42);          // ❌ 错误：禁止隐式转换
func(MyInt(42));      // ✅ 显式构造
func(static_cast<MyInt>(42));  // ✅ 显式转换

// 对比：没有 explicit 时
class Loose {
public:
    Loose(int n) {}     // 非 explicit
};
Loose l = 5;            // ✅ 隐式转换，容易造成意外
```

**实践**：所有单参数构造函数原则上都应加 `explicit`，除非你有意允许隐式转换。

---

## 七、异常处理关键字

### 7.1 `try` / `catch` / `throw`

```cpp
#include <stdexcept>

double divide(int a, int b) {
    if (b == 0) {
        throw std::runtime_error("除数不能为 0");   // 抛出异常
    }
    return static_cast<double>(a) / b;
}

int main() {
    try {
        double r = divide(10, 0);
    } catch (const std::runtime_error& e) {         // 捕获特定类型
        std::cerr << "异常: " << e.what() << '\n';
    } catch (const std::exception& e) {             // 捕获基类
        std::cerr << "通用异常: " << e.what() << '\n';
    } catch (...) {                                  // 捕获所有（...省略号）
        std::cerr << "未知异常\n";
    }
    return 0;
}
```

**关键规则**：
- `throw` 可以抛出任何类型，但标准做法是抛 `std::exception` 的派生类。
- `catch` 应按"最具体在前、最宽泛在后"排序；`catch(...)` 放最后。
- 异常对象按**引用**捕获（`catch (const T& e)`），避免拷贝开销和切片。

**异常安全的几个原则**：
- 构造函数中抛出异常是安全的（析构自动调用已构造的成员）。
- 析构函数**不要**抛出异常（会导致 `terminate`）。
- `new` 失败默认抛 `std::bad_alloc`，而不是返回 `nullptr`。

### 7.2 `noexcept`（C++11）

声明函数**不会抛出异常**。一旦声明 `noexcept` 却抛出异常，程序直接调用 `std::terminate` 终止。

```cpp
void safe_op() noexcept;         // 不抛异常
void risky_op() noexcept(false); // 可能抛异常（默认行为）

// 条件性 noexcept：只在某条件下不抛
void f(T x) noexcept(noexcept(x.method()));   // 若 x.method() 不抛，则 f 也不抛
```

**好处**：
- 编译器可以优化（不需要维护异常状态）。
- 容器在元素移动构造为 `noexcept` 时才会用移动而非拷贝（性能关键）。

```cpp
std::vector<std::string> v;
// std::string 的移动构造是 noexcept，扩容时才用移动而非拷贝
```

**规则**：
- 析构函数默认 `noexcept`。
- `new`、`delete` 都不抛（内存分配失败抛 `bad_alloc` 除外）。
- `throw` 表达式在 `noexcept` 函数中会导致 `terminate`。

---

## 八、编译期与特殊关键字

### 8.1 `sizeof` — 类型/变量大小

```cpp
sizeof(int);              // 通常是 4
sizeof(char);             // 总是 1
sizeof(double);           // 通常是 8
sizeof(x);                // 变量 x 的大小
sizeof(arr) / sizeof(arr[0]);  // 数组元素个数

// 结构体大小有对齐填充，不一定是成员之和
struct S { char c; int i; };
sizeof(S);                // 通常是 8（4 字节对齐，char 后有 3 字节填充）
```

**注意**：`sizeof` 不计算动态分配的内存，只算对象本身。指针的 `sizeof` 是固定的（如 8 字节 64 位）。

### 8.2 `typeid` — 运行时类型识别

```cpp
#include <typeinfo>

int x;
const std::type_info& t = typeid(x);
std::cout << t.name() << '\n';   // 通常输出 "int"

// 配合多态
class Base { virtual ~Base() = default; };
class Derived : public Base {};

Base* p = new Derived();
std::cout << typeid(*p).name();   // 多态时输出 "Derived"（需虚函数）
```

**注意**：
- 对无虚函数的类型，`typeid` 返回静态类型（编译期确定）。
- 多态类型（有虚函数）才能运行时确定真实类型。
- 需要 `<typeinfo>` 头文件。

### 8.3 `nullptr`（C++11）— 类型安全空指针

替代 C 语言的 `NULL`/`0`。

```cpp
int*  p = nullptr;       // ✅ 明确
int*  q = NULL;          // 旧式（NULL 其实是 0）
int*  r = 0;             // 也可以，但可读性差

// nullptr 的好处：避免重载歧义
void f(int);
void f(int*);

f(0);            // 调用 f(int)
f(nullptr);      // 明确调用 f(int*)，不会歧义
```

**注意**：`nullptr` 有自己的类型 `std::nullptr_t`，可以转为任何指针类型，但不能转为整数。

### 8.4 `static_assert`（C++11）— 编译期断言

编译期检查条件，条件为假则**编译失败**并输出错误信息。

```cpp
static_assert(sizeof(int) >= 4, "int 必须至少 4 字节");
static_assert(sizeof(void*) == 8, "必须 64 位系统");

// C++17 起可以不写消息（从表达式推导）
static_assert(sizeof(long) >= 8);
```

**用途**：在编译期验证假设，防止平台差异带来的隐患。

### 8.5 `decltype`（C++11）— 推导表达式类型

```cpp
int x = 5;
decltype(x)     y = 10;      // y 是 int
decltype(x + 1.5) z = 0.0;   // z 是 double
decltype(&x)    p = &x;      // p 是 int*

// 保留引用和 const（与 auto 不同）
const int& r = x;
decltype(r)     r2 = x;      // r2 是 const int&（保留引用）
auto            r3 = x;      // r3 是 int（去掉了引用和 const）

// 常用场景：模板返回类型推导
template <typename T, typename U>
auto add(T a, U b) -> decltype(a + b) { return a + b; }
```

### 8.6 `alignas` / `alignof`（C++11）— 对齐控制

```cpp
alignas(16) int arr[4];        // 将 arr 按 16 字节对齐
alignas(64) struct CacheLine {};  // 结构体按 64 字节对齐

std::cout << alignof(int);     // int 的对齐要求（通常 4）
std::cout << alignof(double);  // 通常 8
```

**用途**：SIMD 指令要求数据对齐、避免缓存行伪共享、对齐到硬件要求等。

**C++17 的 `std::align`** 可用于在缓冲区中手动对齐分配。

### 8.7 `asm` — 内嵌汇编

在 C++ 中嵌入汇编指令。**平台相关**，不可移植。

```cpp
asm("nop");   // 空操作（常用于延迟或占位）
asm("mfence");// 内存屏障
// 复杂的内嵌汇编语法因编译器而异（GCC/Clang/MSVC 不同）
```

**注意**：绝大多数场景不需要内嵌汇编，优先用标准库或内建函数（如 `__builtin_`）。

### 8.8 `inline` — 内联函数

建议编译器将函数体在调用点展开，避免函数调用开销（栈、跳转）。

```cpp
inline int square(int x) { return x * x; }
```

**现代 C++ 的 `inline` 新含义（C++17）**：
- `inline` 变量允许头文件中定义变量（每个编译单元共享同一个对象）。

```cpp
// header.h
class Config {
    static inline int maxSize = 100;   // C++17，可在头文件内定义
};
inline int globalCounter = 0;          // C++17，头文件内定义的全局变量
```

**重要**：现代编译器会自动决定是否内联（`inline` 只是建议，编译器有权忽略）。`inline` 在头文件中的真正意义是**链接层面**：允许在多个编译单元重复定义而不报错。

### 8.9 `export` — 模板导出

用于"模板的跨编译单元导出"。因实现复杂，几乎被废弃，C++11 移除，C++20 中又计划用于模块系统。

```cpp
export template <typename T>
T identity(T t) { return t; }   // C++20 模块中使用
```

**一般不需要关心**。

---

## 九、C++20/23 新增关键字

### 9.1 `concept` / `requires`（C++20）— 约束模板

对模板参数施加概念约束，让错误信息更清晰。

```cpp
#include <concepts>

// concept 定义：T 必须是可排序的
template <typename T>
concept Sortable = requires(T a, T b) {
    { a < b } -> std::convertible_to<bool>;
};

// 用 concept 约束模板
template <Sortable T>
T my_max(T a, T b) { return a < b ? b : a; }

// 或使用 requires 子句
template <typename T> requires Sortable<T>
void sort(T* begin, T* end);

// 直接约束函数参数
void print(const Sortable auto& value) { ... }
```

### 9.2 `consteval`（C++20）— 强制编译期求值

与 `constexpr`（可在编译期或运行期）不同，`consteval` **强制**编译期求值。

```cpp
consteval int square(int x) { return x * x; }
constexpr int n = square(5);    // ✅ 编译期求值
int runtime_value = 7;
// int m = square(runtime_value);  // ❌ 错误：运行期值不能用于 consteval
```

### 9.3 `constinit`（C++20）— 强制静态初始化

确保变量在静态初始化阶段初始化，防止"静态初始化顺序问题"。

```cpp
constinit int globalX = 42;      // 必须静态初始化
// constinit int globalY = func();  // 若 func 是 constexpr 则可以
```

### 9.4 协程关键字（C++20）：`co_await` / `co_yield` / `co_return`

```cpp
// 协程生成序列
generator<int> count(int upTo) {
    for (int i = 1; i <= upTo; ++i)
        co_yield i;        // 每次产出并挂起
}

// 异步等待
awaitable<int> fetch() {
    co_return co_await asyncHttpGet();   // 挂起直到结果返回
}
```

> 需要相应的协程框架（`std::generator`、`co_await` 机制），C++20 提供基础设施。

### 9.5 `operator` 三向比较（C++20）

`<=>` 空格船运算符，自动生成比较。

```cpp
struct Point {
    int x, y;
    auto operator<=>(const Point&) const = default;  // 自动生成 == < > <= >=
};
```

### 9.6 C++23 相关

C++23 中主要的新关键字不多，多为库特性。计划中的 `deducing this`、`modules` 的扩展等。关键字层面 C++23 新增有限，主要增强现有机制。

---

## 十、附：完整关键字速查表

### C++98 完整关键字（63 个）

```
asm        auto      bool      break     case      catch
char       class     const     const_cast continue  default
delete     do        double    dynamic_cast else      enum
explicit   export    extern    false     float     for
friend     goto      if        inline    int       long
mutable    namespace new       operator  private   protected
public     register  reinterpret_cast return    short     signed
sizeof     static    static_cast struct    switch    template
this       throw     true      try       typedef   typeid
typename   union     unsigned  using     virtual   void
volatile   wchar_t   while
```

### C++11 新增（10 个）

```
alignas    alignof   constexpr decltype  noexcept  nullptr
override   static_assert thread_local  final
```

### C++20 新增

```
concept    requires  consteval constinit co_await co_return co_yield
char8_t (字符类型)   [[likely]] [[unlikely]] 等属性（非关键字但相关）
```

> **说明**：`char8_t` 在 C++20 作为关键字（UTF-8 字符类型）引入；`final` 和 `override` 实际上是上下文关键字（仅在特定位置作为关键字）。

### 上下文关键字（只有特定语境才作为关键字）

- `final`、`override`、`import`、`module`、`export` 等。
- 它们允许作为普通标识符使用，不会与已有代码冲突。

---

## 参考与进一步阅读

- C++ 标准：ISO/IEC 14882（C++98/11/14/17/20/23）
- [cppreference.com](https://en.cppreference.com/w/) — 权威在线参考
- 官方 C++ 关键字列表：[C++ keywords](https://en.cppreference.com/w/cpp/keyword)

---

*文档生成日期：2026-10-29*
