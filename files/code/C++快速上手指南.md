# C++ 快速上手指南（面向 C / Java / Kotlin 程序员）

> 目标：你已经会 C、Java、Kotlin。但 C 很久没用可能有些生疏，所以本文**先快速复习 C 语言**（重点是指针、字符串、结构体、`malloc`、头文件这些最容易忘的），再讲 **C++ 相对 C 新增的东西**和**现代 C++（C++11 及以后）的核心写法**。读完能看懂大多数 C/C++ 项目代码，也能放心改动。

---

## 目录

1. [核心心智：C++ 到底难在哪](#1-核心心智)
2. [C 语言快速复习](#2-c-语言快速复习)
3. [从 C 到 C++ 的基础差异](#3-从-c-到-c-的基础差异)
4. [类与对象（对比 Java）](#4-类与对象对比-java)
5. [RAII 与内存管理](#5-raii-与内存管理)
6. [模板（对比 Java 泛型）](#6-模板对比-java-泛型)
7. [STL 容器与算法](#7-stl-容器与算法)
8. [现代 C++ 特性](#8-现代-c-特性)
9. [异常处理](#9-异常处理)
10. [编译与链接](#10-编译与链接)
11. [工程实践](#11-工程实践)
12. [易错点速查表](#12-易错点速查表)

---

## 1. 核心心智

先说最重要的认知：**C++ 不是"带类的 C"，而是一门已经进化了 40 年的语言**。读现代 C++ 代码时，你看到的写法和 20 年前的教材差异巨大。本文以 **C++11/14/17** 为主（这是现在大多数项目的基线），也会标注老写法，因为你读老代码时两种都会遇到。

几个核心心智：

- **手动内存管理 → 智能指针**：现代 C++ 用 `unique_ptr`/`shared_ptr`，基本不用 `new`/`delete` 了，但这不代表没有"所有权"概念——反而更强调"谁拥有这块内存"。
- **RAII 是灵魂**：资源获取即初始化（Resource Acquisition Is Initialization）。用**构造函数拿资源、析构函数还资源**，替代 C 里手动的 `open/close`、`malloc/free` 配对。
- **值语义 + 拷贝控制**：Java 对象是引用语义，C++ 默认是**值语义**（赋值就是拷贝）。理解拷贝/移动是读懂 C++ 的关键。
- **模板 ≠ Java 泛型**：C++ 模板是编译期代码生成，能力更强也更"难读"。
- **没有 GC**：没有垃圾回收，但配合 RAII 和智能指针，现代 C++ 已经很少手写 `delete`。

对应关系速记：

| C / Java / Kotlin     | C++                                            |
| --------------------- | ---------------------------------------------- |
| `struct`（C）           | `struct` / `class`（可含方法、继承）                    |
| `char*` / `String`    | `std::string`                                  |
| `malloc`/`free`       | `new`/`delete`，现代用 `make_unique`/`make_shared` |
| Java `class`（引用语义）    | C++ `class`（值语义，默认拷贝）                          |
| Java 接口 / `interface` | 纯虚函数 + 多继承                                     |
| Java 泛型 `<T>`（类型擦除）   | 模板 `template<typename T>`（编译期展开）               |
| Kotlin `val`/`var`    | `const` / 普通变量                                 |
| Java `ArrayList`      | `std::vector`                                  |
| Java `HashMap`        | `std::unordered_map`                           |
| Java `TreeMap`        | `std::map`                                     |
| `for (T x : list)`    | `for (auto& x : vec)`                          |

---

## 2. C 语言快速复习

这一章帮你快速捡回 C 语言。只列核心语法 + 最容易忘的点，不啰嗦。

### 2.1 基本类型与大小

```c
#include <stdio.h>

char c = 'A';            // 1 字节
short s = 1;             // 通常 2 字节
int i = 42;              // 通常 4 字节
long l = 42L;            // 4 或 8 字节（平台相关）
long long ll = 42LL;     // 8 字节
float f = 3.14f;         // 4 字节
double d = 3.14;         // 8 字节
unsigned int u = 10;     // 无符号

printf("%zu\n", sizeof(int));   // sizeof 查类型大小
```

- **没有 `bool`、没有 `string` 类型**（C99 后可用 `<stdbool.h>` 的 `bool`）。
- 类型大小**平台相关**，用 `sizeof` 查，别写死。
- `printf` 常用占位符：`%d`（int）、`%c`（char）、`%s`（字符串）、`%f`（float）、`%lf`（double）、`%x`（十六进制）、`%p`（指针）。

### 2.2 变量、常量与运算符

```c
int x = 10;
const int N = 100;       // 只读变量（运行时确定）
#define PI 3.14159       // 宏：预处理阶段做文本替换，不是真正的常量

int a = 7 / 2;           // 3  整数除法会截断
int b = 7 % 2;           // 1  取余

// 位运算
int m = a & b;           // 与
int n = a | b;           // 或
int o = a ^ b;           // 异或
int p = ~a;              // 取反
int q = a << 1;          // 左移
int r = a >> 1;          // 右移

int t = (a > b) ? a : b; // 三元
i++; ++i; i--; --i;      // 自增自减（注意前置/后置区别）
```

### 2.3 控制流

```c
if (x > 0) { ... }
else if (x == 0) { ... }
else { ... }

switch (x) {
    case 1: ...; break;   // 别忘了 break，否则会贯穿到下一个 case
    case 2: ...; break;
    default: ...;
}

for (int i = 0; i < 10; i++) { ... }
while (cond) { ... }
do { ... } while (cond);
break;      // 跳出循环
continue;   // 跳到下一次迭代
```

### 2.4 数组

```c
int a[5] = {1, 2, 3, 4, 5};
int a2[5] = {0};                       // 全部初始化为 0
a[0] = 10;

int len = sizeof(a) / sizeof(a[0]);    // 算数组长度（数组当参数传时会失效，见 2.5）

int m[2][3] = {{1,2,3}, {4,5,6}};      // 二维数组
m[1][2];                               // 6
```

要点：**数组没有边界检查**，越界不会报错，是未定义行为（这就是 C 系语言内存问题的根源）。

### 2.5 指针（最容易忘，重点）

```c
int x = 10;
int* p = &x;     // & 取地址：p 里存的是 x 的地址
*p = 20;         // * 解引用：通过 p 改 x，x 变成 20
```

**数组和指针的关系**（核心）：

```c
int arr[3] = {1, 2, 3};
int* pa = arr;            // 数组名 = 首元素的地址
pa[0] == arr[0];          // 等价
*(arr + 1) == arr[1];     // 指针运算：arr+1 指向下一个元素
```

**指针作为函数参数**（C 里"让函数改外面的值"的唯一手段）：

```c
void swap(int* a, int* b) {
    int t = *a;
    *a = *b;
    *b = t;
}
swap(&x, &y);   // 传地址
```

**两个容易混淆的写法**：

```c
int* parr[3];      // 指针数组：数组，每个元素是 int*
int (*ap)[3];      // 数组指针：一个指针，指向"含 3 个 int 的数组"
```

读法技巧：**从变量名开始，先看右边再看左边**。`parr` 右边是 `[3]`（是数组），左边是 `int*`（元素是指针）；`ap` 右边是 `)`，先看括号里的 `*ap`（是指针），再看右边 `[3]`（指向数组）。

**函数指针**（回调常用，老代码会遇到）：

```c
int add(int a, int b) { return a + b; }
int (*func)(int, int) = add;   // func 是指向函数的指针
int r = func(1, 2);            // 调用 -> 3
```

### 2.6 函数

```c
int add(int a, int b) { return a + b; }   // 定义
int add(int a, int b);                     // 声明（只有签名，放头文件里）

// 值传递：函数内改参数，不影响外面的实参
void f(int x) { x = 100; }   // 调用后，外部的变量不变
```

**C 里参数都是值传递**。想让函数修改外部变量，必须传指针（见 2.5 的 `swap`）。

### 2.7 字符串

```c
char s[] = "hello";      // 实际上是 {'h','e','l','l','o','\0'}
char* p = "hello";       // 指向只读的字符串字面量，不能修改 p[0]

#include <string.h>
strlen(s);               // 长度（不含结尾的 '\0'）
strcpy(dst, src);        // 拷贝（dst 要够大！）
strcmp(a, b);            // 比较，返回 0 表示相等
strcat(dst, src);        // 拼接（dst 要够大！）
```

要点：**C 的字符串就是 `char` 数组，以 `'\0'` 结尾**。所有字符串函数靠 `'\0'` 判断结束，所以数组必须留出 `'\0'` 的位置（`"hello"` 需要 6 个字节）。这是 C 里最容易出错的地方——缓冲区溢出都源于此。

### 2.8 结构体、typedef、enum

```c
struct Point {
    int x;
    int y;
};
struct Point p = {1, 2};   // 定义变量时要写全 struct Point
p.x = 10;

// typedef 起别名，省去 struct 关键字
typedef struct {
    int x, y;
} Point2;
Point2 p2 = {1, 2};

// enum 枚举，默认从 0 递增
enum Color { RED, GREEN, BLUE };   // 0, 1, 2
enum Color c = RED;
```

### 2.9 内存管理：malloc / free

```c
#include <stdlib.h>

int* p = malloc(sizeof(int) * 10);   // 堆上分配 10 个 int，不初始化
p[0] = 1;
free(p);                             // 必须释放！否则内存泄漏
p = NULL;                            // 释放后置空，防止悬垂指针

int* p2 = calloc(10, sizeof(int));   // 分配并清零
p2 = realloc(p2, sizeof(int) * 20);  // 重新分配（可能移动内存）
```

要点：**`malloc` 和 `free` 必须成对**，忘了 `free` 就是内存泄漏。这是 C 最大的坑，也是 C++ 用 RAII + 智能指针要解决的核心问题（见第 5 章）。

### 2.10 头文件（.h）与多文件编译（重点）

**为什么要有 `.h`？** 因为 C 是"分文件编译"的：每个 `.c` 文件单独编译成目标文件，最后链接。要让多个 `.c` 文件共享函数和类型，就得把**声明**抽到一个公共的 `.h` 头文件里，大家各自 `#include` 它。

**核心规则：声明放 `.h`，定义放 `.c`。**

```c
// foo.h —— 头文件：只放"声明"
#ifndef FOO_H               // 头文件守卫，防止重复包含
#define FOO_H

int add(int a, int b);      // 函数声明（只有签名，没有函数体）
extern int global_count;    // 变量声明（extern = 只声明，不分配内存）
#define MAX_SIZE 100        // 宏定义

typedef struct {            // 类型定义
    int x, y;
} Point;

#endif
```

```c
// foo.c —— 源文件：放"实现/定义"
#include "foo.h"

int global_count = 0;        // 变量定义（真正分配内存，只能有一处）

int add(int a, int b) {      // 函数定义（有函数体）
    return a + b;
}
```

```c
// main.c —— 入口文件
#include <stdio.h>
#include "foo.h"             // 自己的头文件用双引号 ""

int main() {
    int r = add(1, 2);
    printf("%d\n", r);
    return 0;
}
```

编译（多个 `.c` 一起编译链接）：

```bash
gcc main.c foo.c -o main
```

要点总结：

- **声明 vs 定义**：声明只是"告诉编译器有这个东西"，不分配内存；定义才真正分配内存/写实现。一个东西可以**多处声明，但只能一处定义**。`extern` 就是"声明（不定义）"变量的关键字。
- **`#include` 的本质**：把目标文件内容**原样粘贴**到当前文件。所以头文件被多个 `.c` 包含时，如果里面有"定义"（如 `int global_count = 0;`），链接时就会"重复定义"报错。
- **`<>` vs `""`**：`#include <stdio.h>` 用尖括号，从系统目录找；`#include "foo.h"` 用双引号，先从当前目录找。
- **头文件守卫**：`#ifndef FOO_H` / `#define FOO_H` / `#endif` 三件套，保证头文件即使被包含多次也只处理一次，避免重复定义。

### 2.11 预处理器与作用域

```c
#include <stdio.h>        // 包含文件
#define PI 3.14159        // 定义宏（文本替换）
#define MAX(a,b) ((a)>(b)?(a):(b))   // 函数式宏，注意每个参数都要加括号

#ifdef DEBUG             // 条件编译
    printf("调试信息\n");
#endif
#ifndef FOO_H            // 配合头文件守卫
#define FOO_H
#endif

static int counter;      // static：只在当前文件可见，其他 .c 访问不到
extern int global;       // extern：声明一个定义在其他文件里的全局变量
```

`static` 在 C 里有两个意思：修饰全局变量/函数时是"文件内可见"；修饰局部变量时是"只初始化一次、函数退出后值保留"。

---

## 3. 从 C 到 C++ 的基础差异

### 3.1 输入输出与头文件

```cpp
#include <iostream>      // C++ 标准库头文件没有 .h
#include <string>

int main() {
    std::string name = "张三";
    std::cout << "你好，" << name << std::endl;   // cout，不是 printf
    int x;
    std::cin >> x;                                // cin，不是 scanf
    return 0;
}
```

- C++ **标准库**头文件**没有 `.h`**：`<iostream>`、`<string>`、`<vector>`。C 的头文件在 C++ 里用 `<cstdio>`、`<cstring>`（对应原来的 `<stdio.h>`、`<string.h>`）。
- 注意区分：**你自己写的头文件仍然用 `.h` 或 `.hpp`**（如 `#include "foo.h"`），只是 C++ 标准库不用 `.h`。
- 输出用 `std::cout <<`（流式，支持链式 `<<`），输入用 `std::cin >>`。

### 3.2 引用 `&` —— 最常用但最容易混

```cpp
int a = 10;
int& ref = a;      // ref 是 a 的别名，不是新变量
ref = 20;          // a 也变成 20

// 传引用：函数内修改会反映到外面，且没有指针拷贝的开销
void swap(int& x, int& y) {
    int t = x; x = y; y = t;
}

// 常量引用：不拷贝、也不允许修改（传大对象的最佳姿势）
void print(const std::string& s) {
    std::cout << s;   // 不能改 s
}
```

**记忆：`&` 在类型后面是"引用"（别名），在变量前面是"取地址"。**

引用可以理解为"**不能为空的、语法更清爽的指针**"，它替代了 C 里"传指针才能改外部变量"的写法（对比 2.5 的 `swap`）。读代码常见签名模式：`void f(const std::string& s)` —— `const` 表示只读，`&` 表示传引用不拷贝。

### 3.3 `const` 与 `constexpr`

```cpp
const int N = 100;          // 运行期常量
constexpr int M = 100;      // 编译期常量（可用于数组大小、模板参数）

const int* p = &x;          // 指针指向的内容不可改
int* const p2 = &x;         // 指针本身不可改
```

`const` 是"只读"关键字，出现频率极高。读代码看到 `const` 就理解为"这里承诺不修改"。

### 3.4 命名空间

```cpp
namespace mylib {
    int add(int a, int b) { return a + b; }
}

int r = mylib::add(1, 2);   // :: 是作用域解析符，类似 Java 的 .

using namespace std;        // 别在头文件里用！会污染命名空间
```

### 3.5 函数重载与默认参数

```cpp
int add(int a, int b) { return a + b; }
double add(double a, double b) { return a + b; }   // 重载：参数类型不同

void log(const std::string& msg, int level = 0) {  // 默认参数
    ...
}
log("error");            // level = 0
log("error", 2);
```

### 3.6 `auto` 类型推导

```cpp
auto x = 42;                    // int
auto s = std::string("hi");     // std::string
auto it = vec.begin();          // 迭代器类型，省得手写一长串

auto add = [](int a, int b) { return a + b; };  // lambda 必须用 auto 接
```

`auto` 类似 Kotlin 的类型推断，读现代代码到处都是。

### 3.7 范围 for（range-based for）

```cpp
std::vector<int> v = {1, 2, 3};
for (int x : v)          // 按值遍历（拷贝）
    std::cout << x;

for (int& x : v)         // 按引用遍历（能修改）
    x *= 2;

for (const auto& x : v)  // 只读引用遍历（推荐，不拷贝）
    std::cout << x;
```

等价于 Java/Kotlin 的 for-each，但注意 `&` 决定是拷贝还是引用。

### 3.8 `nullptr`

```cpp
int* p = nullptr;   // 现代写法，替代 C 的 NULL 和 0
```

---

## 4. 类与对象（对比 Java）

### 4.1 基本类

```cpp
class Animal {
public:
    // 构造函数
    Animal(const std::string& name) : name_(name) {   // 初始化列表
    }

    void speak() const {            // const 表示不修改成员
        std::cout << name_ << " 在叫\n";
    }

private:
    std::string name_;              // 成员命名常加下划线后缀
};

int main() {
    Animal a("狗");     // 栈上对象，作用域结束自动析构（不是引用，是值本身）
    a.speak();
}
```

和 Java 的关键区别：

1. **值语义**：`Animal a("狗")` 是直接在栈上创建对象（不是引用），离开作用域自动析构。Java 里这是不可能的（必须 `new`）。
2. **访问控制按段声明**：`public:`/`private:`/`protected:` 是"标签"，后面所有成员都属于该段，不是每个成员前面写。
3. **析构函数**：`~Animal()`，对象销毁时自动调用，用于释放资源。Java 没有这个概念。
4. **初始化列表**：`name_(name)` 在进入函数体前就初始化成员（对比 Java 在构造方法体里赋值）。

### 4.2 析构函数

```cpp
class File {
public:
    File(const std::string& path) { /* 打开文件 */ }
    ~File() {                       // 析构函数，自动关闭文件
        /* 关闭文件 */
    }
};
```

**这就是 RAII 的核心**：对象销毁时，析构函数自动清理。所以 C++ 里不需要 `try/finally` 去关闭资源——只要把资源包进对象，离开作用域就自动清理。这正是对 C 里 `open/close`、`malloc/free` 手动配对的革命性改进。

### 4.3 拷贝与移动（C++ 独有的重头戏）

这是读 C++ 代码**最重要、也最费解**的部分，Java 程序员尤其要注意。

```cpp
class Buffer {
public:
    Buffer(size_t size) : data_(new char[size]), size_(size) {}

    // 拷贝构造：b = a 或传值时触发
    Buffer(const Buffer& other) : data_(new char[other.size_]), size_(other.size_) {
        std::copy(other.data_, other.data_ + size_, data_);   // 深拷贝
    }

    // 移动构造：把 other 的资源"偷"过来，不拷贝
    Buffer(Buffer&& other) noexcept : data_(other.data_), size_(other.size_) {
        other.data_ = nullptr;   // other 被掏空
        other.size_ = 0;
    }

    ~Buffer() { delete[] data_; }

private:
    char* data_;
    size_t size_;
};

Buffer a(100);
Buffer b = a;              // 拷贝构造：b 有自己独立的内存
Buffer c = std::move(a);   // 移动构造：a 的资源转移给 c，a 变空
```

要点：

- `const Buffer&` 是**左值引用**（拷贝），`Buffer&&` 是**右值引用**（移动）。
- `std::move(x)` 表示"x 的资源可以搬走"，配合移动构造避免深拷贝，性能优化关键。
- 看到 `Foo(Foo&&)`、`Foo& operator=(Foo&&)` 就是移动构造/移动赋值。

**Rule of Five**（三/五法则）：如果类管理了资源（指针等），通常要定义 5 个函数——析构、拷贝构造、拷贝赋值、移动构造、移动赋值。不过现代 C++ 中，用智能指针后很多时候可以全部 `= default`。

### 4.4 继承与多态

```cpp
class Shape {
public:
    virtual double area() const = 0;   // 纯虚函数 = 抽象方法
    virtual ~Shape() = default;        // 虚析构函数，必须有！
};

class Circle : public Shape {
public:
    explicit Circle(double r) : r_(r) {}
    double area() const override { return 3.14 * r_ * r_; }   // override 明确重写
private:
    double r_;
};

double total(Shape& s) { return s.area(); }   // 基类引用调用派生类方法（多态）
```

和 Java 的区别：

- 继承写 `class Circle : public Shape`（注意 `public`）。
- **只有 `virtual` 函数才是多态**（默认不是！）。Java 方法默认就是虚的，C++ 必须显式 `virtual`。
- 纯虚函数 `= 0` 相当于抽象方法。
- `override` 关键字（C++11）标记"这是重写"，写错签名会编译报错，强烈推荐。
- **虚析构函数**：基类指针/引用析构时，如果不是虚析构，派生类的析构不会执行 → 泄漏。这是经典大坑。

### 4.5 运算符重载

```cpp
class Vector2 {
public:
    Vector2(double x, double y) : x_(x), y_(y) {}

    Vector2 operator+(const Vector2& o) const {   // 重载 +
        return Vector2(x_ + o.x_, y_ + o.y_);
    }

    friend std::ostream& operator<<(std::ostream& os, const Vector2& v) {
        os << "(" << v.x_ << ", " << v.y_ << ")";
        return os;
    }
private:
    double x_, y_;
};

Vector2 a(1, 2), b(3, 4);
Vector2 c = a + b;              // 调用 operator+
std::cout << c;                 // (4, 6)
```

### 4.6 `struct` vs `class`

```cpp
struct Point {   // struct 默认 public
    int x, y;
};
class Point2 {   // class 默认 private
    int x, y;
};
```

两者**几乎一样**，唯一区别是默认访问权限：`struct` 默认 `public`，`class` 默认 `private`。习惯上，纯数据用 `struct`，有逻辑用 `class`。（对比 C 的 `struct`：C 里只能放数据，C++ 里能放方法和继承。）

---

## 5. RAII 与内存管理

### 5.1 智能指针（现代 C++ 不手写 new/delete）

```cpp
#include <memory>

// unique_ptr：独占所有权，不能拷贝，只能移动
std::unique_ptr<Animal> p = std::make_unique<Animal>("狗");
p->speak();
// 作用域结束自动 delete，无需手动释放

// shared_ptr：共享所有权，引用计数，最后一个释放时 delete
std::shared_ptr<Animal> s1 = std::make_shared<Animal>("猫");
auto s2 = s1;               // 引用计数 +1
s1 = nullptr;               // 计数 -1，s2 还在所以不释放

// weak_ptr：不增加计数的"弱引用"，避免循环引用
std::weak_ptr<Animal> w = s2;
```

对应关系：

- `unique_ptr` ≈ Java 的"独占资源"，类似 Kotlin 的普通对象所有权。
- `shared_ptr` ≈ 有引用计数的共享，类似 Java 的共享引用（但明确显示计数语义）。
- 看到 `std::unique_ptr<T>`、`std::shared_ptr<T>` 就是在管理堆内存，**不要手动 `delete`**。

### 5.2 何时用 `new`

现代代码里几乎不用 `new`。如果看到裸 `new`/`delete`，多半是老代码，或是在自定义容器/性能敏感处。优先用 `make_unique`/`make_shared`。

```cpp
// 老写法（尽量避免）：
Animal* p = new Animal("狗");
delete p;              // 忘了就泄漏

// 新写法（推荐）：
auto p = std::make_unique<Animal>("狗");
// 自动释放
```

### 5.3 栈对象 vs 堆对象

```cpp
Animal a("狗");                 // 栈对象：自动析构，优先用这个
auto p = std::make_unique<Animal>("猫");  // 堆对象：需要超出作用域存活时
```

Java 程序员要转变思维：**C++ 默认优先栈对象**（值语义，自动清理），堆只是"需要动态生命周期"时才用。

---

## 6. 模板（对比 Java 泛型）

### 6.1 函数模板

```cpp
template <typename T>
T max(T a, T b) {
    return a > b ? a : b;
}

max(3, 5);              // T 推导为 int
max(3.5, 5.5);          // T 推导为 double
max<int>(3, 5);         // 显式指定
```

和 Java 泛型的本质区别：**C++ 模板是编译期展开**，`max(3,5)` 会生成一份 `int` 版本的函数，`max(3.5,5.5)` 生成一份 `double` 版本。没有类型擦除，运行期无开销。

### 6.2 类模板

```cpp
template <typename T>
class Box {
public:
    explicit Box(T value) : value_(value) {}
    T get() const { return value_; }
private:
    T value_;
};

Box<int> b1(42);
Box<std::string> b2("hi");
```

### 6.3 模板特化

```cpp
// 通用版本
template <typename T>
const char* typeName() { return "未知类型"; }

// 特化版本
template <>
const char* typeName<int>() { return "int"; }
```

### 6.4 读模板代码的建议

模板的编译错误信息极其难读。读模板代码时先看"实例化点"（哪里用 `Box<int>` 这种），再回看模板定义。现代 C++17 有 `if constexpr` 和 concepts（C++20）来简化模板。

---

## 7. STL 容器与算法

### 7.1 `std::vector`（≈ ArrayList，最常用）

```cpp
#include <vector>

std::vector<int> v = {1, 2, 3};
v.push_back(4);          // 尾部插入
v[0] = 10;               // 下标访问（不检查越界）
v.at(0);                 // 下标访问（检查越界，越界抛异常）
v.size();                // 元素个数
v.empty();               // 是否为空
v.pop_back();            // 移除尾部

for (const auto& x : v)  // 遍历
    ...
```

`vector` 是**连续内存**的数组（类似 ArrayList），是 C++ 里默认容器。注意它和 Java 的 `Vector`（已废弃）无关。

### 7.2 常用容器对照

| 容器                        | 说明        | Java 对应        |
| ------------------------- | --------- | -------------- |
| `std::vector<T>`          | 动态数组，连续内存 | `ArrayList<T>` |
| `std::array<T, N>`        | 定长数组，栈上   | `T[N]`         |
| `std::string`             | 字符串       | `String`       |
| `std::map<K,V>`           | 有序映射（红黑树） | `TreeMap`      |
| `std::unordered_map<K,V>` | 哈希映射      | `HashMap`      |
| `std::set<T>`             | 有序集合      | `TreeSet`      |
| `std::unordered_set<T>`   | 哈希集合      | `HashSet`      |
| `std::list<T>`            | 双向链表      | `LinkedList`   |

### 7.3 迭代器

```cpp
auto it = v.begin();        // 指向第一个元素
auto end = v.end();         // 指向"最后一个元素之后"（哨兵）
for (auto it = v.begin(); it != v.end(); ++it)
    std::cout << *it;       // *it 解引用

// find 返回迭代器，找不到返回 end()
auto found = std::find(v.begin(), v.end(), 3);
if (found != v.end()) {
    std::cout << "找到了";
}
```

迭代器就是"泛化指针"，`begin()/end()` 是区间 `[begin, end)`（左闭右开）。C 程序员可以把迭代器理解为安全的指针（对比 2.5 的指针运算）。

### 7.4 算法（`<algorithm>`）

```cpp
#include <algorithm>

std::sort(v.begin(), v.end());                    // 升序
std::sort(v.begin(), v.end(), std::greater<int>()); // 降序

// 自定义排序（lambda 做比较器）
std::sort(v.begin(), v.end(), [](int a, int b) { return a > b; });

auto it = std::find(v.begin(), v.end(), 3);       // 查找
auto it2 = std::find_if(v.begin(), v.end(),
                        [](int x) { return x > 10; });  // 按条件查找

int n = std::count(v.begin(), v.end(), 3);        // 计数
```

算法库的签名统一是 `算法(起始迭代器, 结束迭代器, ...)`，这是 STL 的核心设计，读多了就顺了。

---

## 8. 现代 C++ 特性

### 8.1 lambda 表达式

```cpp
auto add = [](int a, int b) { return a + b; };

// 捕获外部变量
int base = 10;
auto plus = [base](int x) { return x + base; };    // 按值捕获 base
auto plus2 = [&base](int x) { return x + base; };  // 按引用捕获 base
auto all = [=](int x) { ... };   // = 按值捕获所有外部变量
auto all2 = [&](int x) { ... };  // & 按引用捕获所有外部变量
```

语法：`[捕获列表](参数) { 函数体 }`。`[]` 里的内容决定能访问哪些外部变量——这是和 Java/Kotlin lambda 最大的区别（Java 只能捕获 effectively final 变量，C++ 更灵活但也要注意生命周期）。

### 8.2 移动语义与 `std::move`

```cpp
std::string a = "hello";
std::string b = std::move(a);   // a 的内容被"搬"到 b，a 变成空（未指定状态）
```

核心价值：**避免不必要的深拷贝**。比如往 `vector` 里塞大对象时：

```cpp
std::vector<std::string> v;
v.push_back(std::move(bigString));   // 移动，不拷贝
```

看到 `std::move(x)` 就理解为"x 的资源可以转移，之后别再用 x 了"。

### 8.3 `std::optional`（C++17）

```cpp
#include <optional>

std::optional<int> find(const std::vector<int>& v, int target) {
    for (int x : v)
        if (x == target) return x;
    return std::nullopt;      // 空值
}

auto r = find(v, 3);
if (r.has_value()) {
    std::cout << r.value();   // 或 *r
}
```

类似 Kotlin 的可空类型，替代"返回 -1 表示没找到"的老做法。

### 8.4 结构化绑定（C++17）

```cpp
std::map<std::string, int> m = {{"a", 1}};
for (const auto& [key, value] : m) {   // 直接解包 pair
    std::cout << key << value;
}

auto [x, y] = std::make_pair(1, 2);    // 类似 Kotlin 解构
```

### 8.5 `std::variant` 与 `std::any`（C++17，少见）

```cpp
std::variant<int, std::string> v = 42;    // 类型安全的 union，类似 Kotlin sealed 的简化版
```

### 8.6 并发（`<thread>`、`<mutex>`）

```cpp
#include <thread>
#include <mutex>

std::mutex mtx;

void worker(int id) {
    std::lock_guard<std::mutex> lock(mtx);   // RAII 锁，自动解锁
    std::cout << id;
}

std::thread t1(worker, 1);
std::thread t2(worker, 2);
t1.join();
t2.join();
```

`std::lock_guard`（或 `std::scoped_lock`）是 RAII 风格的锁，作用域结束自动解锁，避免忘记 `unlock`。

---

## 9. 异常处理

```cpp
try {
    throw std::runtime_error("出错了");
} catch (const std::runtime_error& e) {   // 按引用捕获，按类型匹配
    std::cout << e.what();                 // 异常消息
} catch (const std::exception& e) {        // 基类兜底
    std::cout << e.what();
} catch (...) {                            // 捕获一切（慎用）
    // ...
}
```

和 Java 区别：

- `throw` 而非 `throws`，**没有受检异常**（方法签名不声明可能抛什么）。
- 自定义异常继承 `std::exception`（或 `std::runtime_error`），重写 `what()`。
- 注意：**析构函数里不要抛异常**，否则可能 `std::terminate`。

---

## 10. 编译与链接

这一章讲清"从源码到可执行文件"的完整过程，是读懂编译报错、排查链接错误的基础。

### 10.1 编译的四个阶段

编译分成四个阶段，前三步常被合称"编译"，最后一步叫"链接"：

```
源代码（.cpp / .c）
   │ ① 预处理：展开 #include、#define、#ifdef 等宏
   ▼
纯代码（.i）
   │ ② 编译：语法检查 + 类型检查，生成汇编
   ▼
汇编代码（.s）
   │ ③ 汇编：汇编 → 机器码，生成目标文件
   ▼
目标文件（.o / .obj）
   │ ④ 链接：多个 .o + 库 → 可执行文件
   ▼
可执行文件
```

日常只需记住两个概念：

- **编译**：单个 `.cpp` → 单个 `.o`。此时对"外部函数"的调用只是**占位符**——编译器只靠头文件里的声明通过检查，还不知道实现在哪。
- **链接**：把所有 `.o` 和库拼起来，把占位符填成真实地址，产出可执行文件。

### 10.2 编译单元（Translation Unit）

一个 `.cpp` 文件 + 它 `#include` 进来的所有头文件 = 一个**编译单元**。编译器**逐个编译单元独立编译**：编译 `main.cpp` 时根本看不到 `foo.cpp` 的内容。

这正是"**声明放头文件**"（见 2.10）的根本原因——每个编译单元都靠头文件里的声明"知道"别的文件有什么函数，但真正的实现要等链接阶段才对接上。

### 10.3 分步编译与常用参数

```bash
# 一步到位（最常用）
g++ -std=c++17 main.cpp foo.cpp -o app

# 分步编译（理解原理用，大型项目由构建工具自动做）
g++ -std=c++17 -c main.cpp -o main.o    # 只编译，不链接
g++ -std=c++17 -c foo.cpp -o foo.o
g++ main.o foo.o -o app                  # 链接
```

常用 `g++` 参数速查：

| 参数 | 作用 |
| --- | --- |
| `-o app` | 指定输出文件名 |
| `-c` | 只编译不链接，生成 `.o` |
| `-std=c++17` | 指定标准（`c++11`/`c++14`/`c++17`/`c++20`） |
| `-Wall -Wextra` | 开启更多警告（强烈推荐） |
| `-g` | 生成调试信息（配合 gdb 调试） |
| `-O0` / `-O2` / `-O3` | 优化级别（调试用 `O0`，发布用 `O2`/`O3`） |
| `-I 目录` | 添加头文件搜索路径 |
| `-L 目录` | 添加库文件搜索路径 |
| `-l 库名` | 链接库（如 `-lm` 链接数学库） |
| `-D 宏名` | 定义宏（等价代码里的 `#define`） |

### 10.4 编译错误 vs 链接错误（读报错必备）

两类错误性质完全不同，排查方向也不同：

**编译错误**：语法/类型/作用域错误，报错带**文件名和行号**：

```bash
main.cpp:5:10: error: expected ';' before '}'          # 第 5 行第 10 列缺分号
main.cpp:7:3:  error: 'foo' was not declared in this scope   # 用了没声明的东西
```

**链接错误**：编译都过了，链接时找不到符号，典型是 `undefined reference`：

```bash
main.cpp:(.text+0x15): undefined reference to `add(int, int)'
```

`undefined reference to 'xxx'` 最常见三种原因：

1. **忘了把实现文件一起编译**：`g++ main.cpp` 漏了 `foo.cpp`。
2. **只声明没定义**：头文件里声明了，但 `.cpp` 里没写函数体。
3. **模板定义放到了 `.cpp` 里**：模板必须放头文件（见 6.4）。

反过来的错误是 `multiple definition of 'xxx'`：同一符号被定义了多次，通常是**头文件里放了定义**（该用 `extern` 声明或加 `inline`），被多个 `.cpp` 包含导致。

### 10.5 头文件搜索路径

```cpp
#include <iostream>      // 尖括号：从系统目录找（/usr/include 等）
#include "myheader.h"    // 双引号：先从当前文件目录找，找不到再走系统目录
```

第三方库头文件不在默认路径时用 `-I`：

```bash
g++ -std=c++17 main.cpp -I./include -o app
```

报错 `fatal error: xxx.h: No such file or directory` 就是头文件找不到，用 `-I` 补路径。

### 10.6 静态库 vs 动态库

把可复用代码打包成库，避免重复编译：

| | 静态库 | 动态库 |
| --- | --- | --- |
| Linux | `.a` | `.so` |
| Windows | `.lib` | `.dll` |
| 链接时机 | 链接时**拷进**可执行文件 | 运行时加载 |
| 可执行文件体积 | 更大（含库代码） | 更小 |
| 更新库 | 要重新编译 | 换 `.so` 即可，不用重编译 |
| 缺点 | 每个程序各存一份 | 运行时必须能找到 `.so` |

```bash
# 制作静态库
g++ -c foo.cpp -o foo.o
ar rcs libfoo.a foo.o              # 打包成 libfoo.a

# 链接静态库（-lfoo 会去找 libfoo.a）
g++ main.cpp -L. -lfoo -o app

# 制作动态库
g++ -shared -fPIC foo.cpp -o libfoo.so

# 链接动态库（运行时还要保证能找到 .so）
g++ main.cpp -L. -lfoo -o app
```

报错 `cannot find -lfoo` 是链接时找不到 `libfoo.a`/`libfoo.so`，用 `-L` 指定路径。

---

## 11. 工程实践

### 11.1 构建工具概览：Makefile 与 CMake

文件多了以后，手动敲 `g++` 不现实，构建工具负责自动化。现代项目（包括 Android NDK）主流用 CMake，老项目可能是 Makefile。二者都是"声明式"描述编译规则，再交给工具执行：

```bash
# 用 CMake 构建
cmake -S . -B build        # 生成构建文件（只需配置一次）
cmake --build build        # 实际编译
```

看到 `CMakeLists.txt` 就是 CMake 脚本，`Makefile` 就是 Make 脚本（配合 `make` 命令）。

### 11.2 CMakeLists.txt 详解

`CMakeLists.txt` 是 CMake 的配置文件，放在项目根目录（每个子目录也可以放一个）。它的作用就是回答三个问题：**编译哪些文件、生成什么产物、依赖什么库**。

一个最小示例：

```cmake
cmake_minimum_required(VERSION 3.10)   # 最低 CMake 版本
project(MyProject)                     # 项目名，同时生成变量 ${PROJECT_NAME}

set(CMAKE_CXX_STANDARD 17)             # 指定 C++ 标准
set(CMAKE_CXX_STANDARD_REQUIRED ON)    # 强制要求，不支持就报错

add_executable(my_app main.cpp foo.cpp) # 生成可执行文件
# add_library(my_lib foo.cpp)           # 生成库
```

**常用命令逐个讲**（读和改 CMakeLists 基本就靠这些）：

| 命令 | 作用 |
| --- | --- |
| `cmake_minimum_required(VERSION x.y)` | 声明最低 CMake 版本，必须第一行 |
| `project(名字)` | 项目名，同时定义 `${PROJECT_NAME}` 等变量 |
| `set(变量 值)` | 定义变量，之后用 `${变量}` 引用 |
| `add_executable(名 源文件...)` | 生成可执行文件 |
| `add_library(名 类型 源文件...)` | 生成库，类型见下 |
| `target_include_directories(目标 范围 目录)` | 给目标加头文件搜索路径 |
| `target_link_libraries(目标 范围 库...)` | 给目标链接库 |
| `find_package(包名)` | 查找第三方库（如 `find_package(OpenCV)`） |
| `find_library(变量 库名)` | 查找系统库，结果存进变量 |
| `file(GLOB 变量 通配符)` | 收集源文件 |
| `add_subdirectory(目录)` | 添加子目录（子目录里有自己的 CMakeLists.txt） |
| `option(变量 "描述" 默认值)` | 定义开关选项 |
| `if() / elseif() / else() / endif()` | 条件判断 |
| `message(STATUS "...")` | 打印信息（调试用） |

**`add_library` 的类型参数**（第二个参数，最容易搞混）：

```cmake
add_library(foo STATIC  foo.cpp)   # 静态库（.a / .lib），链接时拷进目标
add_library(foo SHARED  foo.cpp)   # 动态库（.so / .dll），运行时加载
add_library(foo OBJECT  foo.cpp)   # 只编译成 .o，不打包
add_library(foo INTERFACE)         # 只有头文件的库（header-only）
add_library(foo STATIC IMPORTED)   # 引入预编译好的第三方静态库（不自己编译）
```

**`PUBLIC` / `PRIVATE` / `INTERFACE`（target_xxx 系列的核心概念）**：

```cmake
target_include_directories(myapp PUBLIC include)   # 自己和依赖我的人都能看到
target_include_directories(myapp PRIVATE src)      # 只有自己能看到
target_link_libraries(myapp PUBLIC other_lib)      # 我的依赖会传递给下游
```

- `PRIVATE`：只作用于**自己**（自己编译用，不传染给别人）。
- `INTERFACE`：只作用于**依赖我的人**（自己不直接需要，但用我的人需要）。
- `PUBLIC`：两者都要。

看到 `target_link_libraries(A PUBLIC B)`，意思是"链接 B，并且任何链接 A 的目标也自动获得 B"——依赖传递。

**变量与作用域**：

```cmake
set(MY_VAR "hello")              # 定义变量
message(STATUS "${MY_VAR}")      # ${变量} 引用，注意要加 ${}

# 作用域：默认是"目录作用域"，子目录看不到父目录的普通变量
# set(... PARENT_SCOPE) 可以往上一层传
```

**生成器表达式 `$<...>`**（构建时才求值，条件编译常用）：

```cmake
target_compile_definitions(myapp PRIVATE $<$<CONFIG:Debug>:DEBUG_BUILD>)
# 含义：Debug 构建时定义宏 DEBUG_BUILD，Release 不定义
```

### 11.3 Android NDK + CMake（你实际在用的场景）

Android 里 C/C++ 代码通过 **NDK + CMake** 编译，Android Studio 会调用 CMake 把 `.cpp` 编译成 `.so`，再打进 APK。`CMakeLists.txt` 通常放在 `app/src/main/cpp/` 下。

**Android Studio 自动生成的典型模板**：

```cmake
# app/src/main/cpp/CMakeLists.txt
cmake_minimum_required(VERSION 3.22.1)

project("myapp")

# 1. 生成 native 库（SHARED = 动态库 .so，供 Java 层 System.loadLibrary 加载）
add_library(
        myapp
        SHARED
        native-lib.cpp)

# 2. 查找 Android 系统库（log 对应 liblog.so，用于 __android_log_print 打日志）
find_library(
        log-lib                 # 变量名，之后用 ${log-lib} 引用
        log)                    # 要查找的库名

# 3. 把系统库链接到我们的 native 库
target_link_libraries(
        myapp
        ${log-lib})
```

**对应的 `build.gradle` 配置**（CMake 是 Gradle 通过 `externalNativeBuild` 调起来的）：

```gradle
android {
    defaultConfig {
        externalNativeBuild {
            cmake {
                cppFlags "-std=c++17"        # 编译参数
                arguments "-DANDROID_STL=c++_shared"   # 指定 STL
            }
        }
    }
    externalNativeBuild {
        cmake {
            path "src/main/cpp/CMakeLists.txt"   # 指定 CMakeLists 路径
            version "3.22.1"
        }
    }
}
```

**常用 Android 系统库**（`find_library` 的第二个参数）：

| 库名 | 用途 |
| --- | --- |
| `log` | 打日志（`__android_log_print`） |
| `android` | 访问 `AAssetManager` 等 NDK API |
| `GLESv2` / `EGL` | OpenGL ES 渲染 |
| `OpenSLES` | 音频 |
| `jnigraphics` | 处理 Bitmap 像素 |

**引入第三方预编译库**（比如厂商给的 `.a`/`.so`）：

```cmake
# 引入头文件
include_directories(${CMAKE_SOURCE_DIR}/../include)

# 引入预编译静态库（按 ABI 区分目录）
add_library(thirdparty STATIC IMPORTED)
set_target_properties(thirdparty PROPERTIES
        IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../libs/${ANDROID_ABI}/libthirdparty.a)

target_link_libraries(myapp thirdparty)
```

**ABI 概念**（Android 特有）：同一份 C++ 代码要针对不同 CPU 架构分别编译出多个 `.so`。`${ANDROID_ABI}` 是 CMake 里的变量，取值为 `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64` 之一。第三方 `.so`/`.a` 通常按 ABI 分目录存放（如上例的 `libs/${ANDROID_ABI}/`）。

**几个要点小结**：

- Android 里 native 库**几乎都用 `SHARED`**（生成 `.so`），由 Java 层 `System.loadLibrary("myapp")` 加载。
- `find_library(变量 库名)` + `target_link_libraries` 是链接系统库的标准套路，看到这俩组合就是"链接了某个 Android 系统库"。
- 改 CMakeLists 后要 **Sync**（Gradle Sync）才会重新生成构建，不是改完立刻生效。
- 排查 native 编译问题优先看 CMake 的报错：`undefined reference` 通常是漏了 `target_link_libraries`；头文件找不到通常漏了 `include_directories` 或 `target_include_directories`。

### 11.4 头文件守卫

```cpp
// foo.h
#pragma once              // 或老写法 #ifndef FOO_H / #define FOO_H / #endif

class Foo { ... };
```

`#pragma once` 防止头文件被重复包含（作用等价于 C 里的 `#ifndef` 三件套，见 2.10）。

### 11.5 命名约定

C++ 命名风格不统一，不同项目差异很大，读代码前先看项目的风格：

| 风格        | 类             | 函数/变量                   | 常量         |
| --------- | ------------- | ----------------------- | ---------- |
| 标准库       | `std::string` | `push_back`（snake_case） | —          |
| Google 风格 | `MyClass`     | `my_func`（snake_case）   | `kMyConst` |
| 常见风格      | `MyClass`     | `myFunc`（camelCase）     | `MY_CONST` |

---

## 12. 易错点速查表

| 场景       | 陷阱                           | 正确做法                             |
| -------- | ---------------------------- | -------------------------------- |
| 基类析构     | 非虚析构 → 派生类不析构、泄漏             | 基类写 `virtual ~Base() = default;` |
| 多态       | 忘了 `virtual` → 不重写、调用错       | 想多态就加 `virtual` + `override`     |
| 空指针      | `NULL` / `0`                 | `nullptr`                        |
| 悬垂引用     | 返回局部变量的引用/指针                 | 返回值，或返回智能指针                      |
| 拷贝 vs 移动 | 大对象按值传 → 深拷贝开销               | 传 `const T&`，转移用 `std::move`     |
| 智能指针     | 手写 `delete` 与智能指针混用          | 交给智能指针，别手动 `delete`              |
| 数组越界     | `v[i]` 不检查                   | 需要检查时用 `v.at(i)`                 |
| 迭代器失效    | `push_back` 后旧迭代器失效          | 重新获取，或先 `reserve`                |
| 模板链接错误   | 模板实现放 `.cpp`                 | 模板定义放头文件                         |
| 头文件污染    | `using namespace std;` 写进头文件 | 只在 `.cpp` 里用，或用 `std::`          |
| 隐式转换     | 单参构造隐式转换                     | 加 `explicit`                     |
| C 字符串溢出  | `strcpy`/`strcat` 目标太小         | 用 `strncpy`/`strncat`，或换 `std::string` |

几个经典代码示例：

```cpp
// ❌ 返回局部变量引用（悬垂引用，未定义行为）
int& bad() {
    int x = 10;
    return x;      // x 在函数返回后销毁！
}

// ✅ 返回值（或智能指针）
int good() {
    int x = 10;
    return x;      // 拷贝/移动回来，安全
}
```

```cpp
// ❌ 基类析构不是虚的
class Base { public: ~Base() {} };
class Derived : public Base { public: ~Derived() { /* 清理 */ } };
Base* p = new Derived();
delete p;   // 只调用 Base 的析构，Derived 的析构没跑！

// ✅ 虚析构
class Base { public: virtual ~Base() = default; };
```

```cpp
// ❌ 迭代器失效
std::vector<int> v = {1, 2, 3};
auto it = v.begin();
v.push_back(4);   // 可能触发扩容，it 失效
*it;              // 未定义行为！

// ✅ 扩容后重新获取
v.push_back(4);
auto it2 = v.begin();
```

```c
// ❌ C 语言经典：缓冲区溢出
char dst[5];
strcpy(dst, "hello world");   // "hello world" 需要 12 字节，溢出！

// ✅ 用 strncpy 限制长度，或改用 C++ 的 std::string
char dst[12];
strncpy(dst, "hello world", sizeof(dst) - 1);
dst[11] = '\0';
```

---

## 结语

以你的基础，理解 C++ 的**值语义 + RAII + 移动语义**这套心智模型，再熟悉 STL 和智能指针，就能看懂绝大多数现代 C++ 代码。建议：

1. 先看**类接口**（头文件）：构造/析构、拷贝/移动、`virtual` 方法，判断资源所有权。
2. 遇到 `std::unique_ptr`/`std::shared_ptr`/`std::vector`，对照第 5、7 节。
3. 改动前重点排查第 12 节的三类高危坑：**虚析构、悬垂引用、迭代器失效**（以及 C 代码里的**缓冲区溢出**）。

C++ 的难点不在语法多，而在**同一个语义有多种表达**（裸指针 vs 智能指针、拷贝 vs 移动、值 vs 引用）。把这些"二选一"的关系理清，读代码就通了。
