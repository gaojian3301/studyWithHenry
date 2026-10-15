# Python 快速上手指南（面向 C / Java / Kotlin 程序员）

> 目标：你已经有编程基础，不需要从 `Hello World` 开始。本文只讲 **Python 和 C/Java/Kotlin 不一样的地方**，以及阅读真实项目代码时**最常遇到**的写法。读完能看懂大部分 Python 代码，也能放心地改动它。

---

## 目录

1. [核心心智：Python 到底哪里不一样](#1-核心心智)
2. [基础语法差异](#2-基础语法差异)
3. [变量与类型系统](#3-变量与类型系统)
4. [核心数据结构](#4-核心数据结构)
5. [控制流](#5-控制流)
6. [函数](#6-函数)
7. [面向对象](#7-面向对象)
8. [模块与导入](#8-模块与导入)
9. [异常处理](#9-异常处理)
10. [文件与 `with`](#10-文件与-with)
11. [高频惯用法（读代码必备）](#11-高频惯用法读代码必备)
12. [工程实践](#12-工程实践)
13. [易错点速查表](#13-易错点速查表)

---

## 1. 核心心智

先用几句话建立对 Python 的整体认知，后面所有细节都是这几点的展开：

- **动态类型**：变量没有类型，只有"值"有类型。同一个变量可以先放 int 再放 str。
- **缩进即作用域**：没有 `{}`，没有 `;`。缩进错误 = 语法错误或逻辑错误。
- **一切皆对象**：`int`、函数、类、模块都是对象，都能赋值、传参、当返回值。
- **没有真正的私有**：没有 `private`/`protected`，靠命名约定（`_x`）。
- **`for` 不是 C 的 `for`**：它遍历的是"可迭代对象"，等价于 Java/Kotlin 的 for-each。
- **惯用写法极多**：推导式、解包、`enumerate`/`zip`、`with`……不认识这些会看不懂代码，但认识之后代码反而更短。

对应关系速记：

| C / Java / Kotlin | Python |
|---|---|
| `{}` 代码块 | 缩进 |
| `ArrayList<T>` / `List<T>` | `list` |
| `HashMap<K,V>` / `Map<K,V>` | `dict` |
| `Set<T>` | `set` |
| 不可变字符串 | `str`（同样不可变） |
| `for (int i=0; i<n; i++)` | `for i in range(n)` |
| `for (T x : list)` | `for x in list` |
| `switch` | `match`（3.10+，老代码多用 `if/elif`） |
| `interface` / 抽象类 | 鸭子类型（duck typing） |
| `try/catch/finally` | `try/except/finally` |

---

## 2. 基础语法差异

### 2.1 缩进、注释、语句

```python
# 单行注释用 #，没有 // 也没有 /* */

def greet(name):
    if name:                    # 冒号表示"下面是一个代码块"
        print(f"Hello, {name}") # 缩进表示属于 if 块
    else:
        print("Hello, world")

greet("张三")
```

要点：
- 语句结尾**不需要** `;`，写了也不报错，但风格上不写。
- 用 **4 个空格**缩进（行业标准），不要混用 Tab 和空格。
- `:` 之后必须换行缩进，缩进层级必须一致，否则 `IndentationError`。

### 2.2 打印

```python
print("a", "b", "c")   # 默认空格分隔，结尾换行
print("x", end="")     # end 指定结尾，默认 '\n'
print("a", "b", sep=",")  # sep 指定分隔符，默认 ' '
```

### 2.3 逻辑与比较

```python
# 逻辑用英文单词，不是 && || !
a and b
a or b
not a

# 比较支持链式写法（Java 里不行）
if 0 < x < 10:        # 等价于 0 < x and x < 10
    print("x in range")

# 相等用 == ，判断"是不是同一个对象"用 is
if x is None:          # 判断 None 请用 is，别用 ==
    ...
```

---

## 3. 变量与类型系统

### 3.1 动态类型

```python
x = 10          # x 是 int
x = "hello"     # 现在 x 是 str，完全合法
```

**没有类型声明**。这带来最大的阅读难点：**看代码时，类型要靠"上下文 + 类型标注 + 文档"推断**。所以现代 Python 项目大量使用类型标注来补救。

### 3.2 类型标注（type hints）

标注**只是提示，运行时完全不强制**，但对阅读代码极其重要：

```python
def add(a: int, b: int) -> int:
    return a + b

from typing import Optional, List

def find(name: str) -> Optional[str]:   # 可能返回 None
    ...

names: List[str] = ["a", "b"]
```

看到 `-> int`、`: str`、`Optional[...]` 这些就是类型标注，读代码时是理解函数签名的最佳线索。

### 3.3 基本类型

```python
n: int = 42
pi: float = 3.14
name: str = "hello"
flag: bool = True       # 注意大写 True/False
nothing: None = None    # None 类似 null
```

- `int` 是**任意精度**的，没有溢出，没有 `long`。
- `float` 是 IEEE 754 双精度。
- `bool` 是 `int` 的子类，`True == 1`、`False == 0`。
- 空值叫 `None`，不叫 `null`/`nil`。

### 3.4 类型转换

```python
int("123")      # 123
str(123)        # "123"
float("3.14")   # 3.14
list("abc")     # ['a', 'b', 'c']
```

---

## 4. 核心数据结构

读 Python 代码，60% 的时间都在和这几种结构打交道。

### 4.1 `list` —— 可变列表（≈ ArrayList）

```python
nums = [1, 2, 3]
nums.append(4)        # 加到最后 -> [1,2,3,4]
nums.insert(0, 0)     # 指定位置插入 -> [0,1,2,3,4]
nums.pop()            # 移除并返回最后一个 -> 4
nums.pop(0)           # 移除并返回下标 0 -> 0
nums.remove(2)        # 按值删除第一个匹配
nums[0] = 99          # 直接下标赋值

len(nums)             # 长度，注意是函数不是方法
2 in nums             # 是否包含，返回 bool
```

**切片（slicing）**是 Python 标志性语法，读代码必会：

```python
a = [0, 1, 2, 3, 4, 5]
a[1:3]     # [1, 2]        左闭右开
a[:3]      # [0, 1, 2]     从头
a[3:]      # [3, 4, 5]     到末尾
a[-1]      # 5            负数索引：从末尾数
a[-2:]     # [4, 5]        最后两个
a[::2]     # [0, 2, 4]     步长 2
a[::-1]    # [5,4,3,2,1,0] 反转
```

记忆口诀：**左闭右开 `[start:stop)`，负数从尾部数，第三个是步长**。

### 4.2 `tuple` —— 不可变列表

```python
point = (1, 2)
point = 1, 2        # 括号可省略
x, y = point        # 解包

point[0] = 9        # 报错！tuple 不可变
```

用途：函数多返回值、作为 dict 的 key、表示"一组固定数据"。

### 4.3 `dict` —— 字典（≈ HashMap）

```python
user = {"name": "张三", "age": 30}
user["name"]        # "张三"
user["city"] = "上海"   # 直接新增
user.get("name")        # "张三"
user.get("email", "无") # 不存在时返回默认值 "无"，不报错（推荐）
"name" in user          # 判断 key 是否存在

for k in user:              # 遍历 key
for k, v in user.items():   # 同时遍历 key 和 value（最常用）
for v in user.values():     # 只遍历 value
```

**`get(key, default)` 是读代码时最常见的"安全取值"写法**，比直接 `[]` 更常见，因为不会抛 `KeyError`。

### 4.4 `set` —— 集合（≈ HashSet）

```python
ids = {1, 2, 3}
ids.add(4)
2 in ids           # 去重 + 判重
set([1, 2, 2, 3])  # {1, 2, 3} 自动去重
```

### 4.5 `str` —— 字符串（不可变）

```python
s = "hello"
s.upper()          # "HELLO"（返回新串，原串不变）
s.replace("l", "L") # "heLLo"
"a,b,c".split(",")  # ['a', 'b', 'c']  拆成 list
"-".join(["a","b"]) # "a-b"  用分隔符拼接 list
"abc" in s          # 子串判断
len(s)              # 长度
s.strip()           # 去掉首尾空白
```

字符串操作**都返回新字符串**，原字符串不变（和 Java 的 String 一样）。

---

## 5. 控制流

### 5.1 `if / elif / else`

```python
if score >= 90:
    grade = "A"
elif score >= 80:
    grade = "B"
else:
    grade = "C"
```

### 5.2 `for` —— 遍历可迭代对象

**Python 没有 C 那种 `for(;;)`**，`for` 就是 for-each：

```python
# 遍历列表
for item in items:
    print(item)

# 遍历带下标（替代 i 循环，最常用）
for i, item in enumerate(items):
    print(i, item)

# 计数循环
for i in range(10):       # 0..9
for i in range(2, 10):    # 2..9
for i in range(0, 10, 2): # 0,2,4,6,8 带步长
```

`range(n)` 是惰性的，不会真的生成 n 个元素的 list（节省内存）。

### 5.3 `while`

```python
i = 0
while i < 5:
    i += 1      # 没有 i++，用 i += 1
```

### 5.4 `for-else` / `while-else`（少见但要知道）

`else` 在**循环没被 `break` 打断时**执行：

```python
for n in nums:
    if n == target:
        print("找到了")
        break
else:
    print("没找到")   # 只有循环正常结束才执行
```

### 5.5 `match`（3.10+，新项目才有）

```python
match code:
    case 200:
        print("OK")
    case 404:
        print("Not Found")
    case _:            # 默认分支，类似 default
        print("Other")
```

老代码里你看到的还是 `if/elif` 链，两者都要认识。

---

## 6. 函数

### 6.1 定义与调用

```python
def greet(name, greeting="你好"):
    return f"{greeting}，{name}"

greet("张三")                    # 你好，张三
greet("张三", greeting="Hi")     # 关键字参数
greet(greeting="Hi", name="张三") # 关键字参数顺序任意
```

要点：
- `def` 定义，`return` 返回；没有返回类型声明（只有标注）。
- **参数可以按名字传**（keyword argument），读代码时看到 `foo(x=1, y=2)` 别困惑。
- **默认参数**必须写在非默认参数之后。

### 6.2 多返回值

```python
def divide(a, b):
    return a // b, a % b

q, r = divide(10, 3)   # q=3, r=1 —— 其实是返回了一个 tuple 再解包
```

### 6.3 `*args` 和 `**kwargs`

```python
def log(*args, **kwargs):
    print(args)     # 位置参数打包成 tuple
    print(kwargs)   # 关键字参数打包成 dict

log(1, 2, name="x", level="info")
# (1, 2)
# {'name': 'x', 'level': 'info'}
```

看到 `*args` 表示"接受任意多个位置参数"，`**kwargs` 表示"任意多个关键字参数"。很多库的封装（wrapper）都这么写。

### 6.4 ⚠️ 可变默认参数陷阱

```python
# 错误写法：默认值是 list，会被所有调用共享
def add(item, buf=[]):
    buf.append(item)
    return buf

add(1)  # [1]
add(2)  # [1, 2]  —— 第二次调用 buf 还是同一个 list！
```

```python
# 正确写法：默认值用 None，在函数内新建
def add(item, buf=None):
    if buf is None:
        buf = []
    buf.append(item)
    return buf
```

这是读代码、改代码时**最容易踩的坑**，看到函数签名里有 `=[]` 或 `={}` 要警惕。

### 6.5 `lambda` 匿名函数

```python
square = lambda x: x * x
sorted(users, key=lambda u: u["age"])   # 按字典某个字段排序，超常见
```

等价于 Java/Kotlin 的 lambda，但语法是 `lambda 参数: 表达式`，**只能写一个表达式**。

### 6.6 装饰器 `@`

```python
import functools

def my_decorator(func):
    @functools.wraps(func)
    def wrapper(*args, **kwargs):
        print("调用前")
        result = func(*args, **kwargs)
        print("调用后")
        return result
    return wrapper

@my_decorator
def say():
    print("hello")
```

看到函数/方法上面一行 `@xxx`，就是"给这个函数套了一层包装"，本质是 `say = my_decorator(say)`。常见于日志、鉴权、缓存、路由注册（如 Flask 的 `@app.route("/")`）。

---

## 7. 面向对象

### 7.1 类与实例

```python
class Animal:
    def __init__(self, name):   # 构造函数，等价于构造方法
        self.name = name        # 实例属性必须用 self.xxx 声明

    def speak(self):
        print(f"{self.name} 在叫")

a = Animal("狗")
a.speak()
```

和 Java/Kotlin 最大的三点区别：
1. **`self` 是显式的**：每个实例方法第一个参数都是 `self`（类似 `this`，但要自己写）。
2. **构造方法是 `__init__`**，不是 `Animal()`。
3. **`self.xxx` 才算实例属性**：没有"先在类里声明字段"这一步，属性可以在 `__init__` 里直接冒出来。

### 7.2 继承与 `super()`

```python
class Dog(Animal):              # 继承用括号
    def __init__(self, name, breed):
        super().__init__(name)  # 调用父类构造
        self.breed = breed

    def speak(self):            # 方法重写，无需 @Override
        print(f"{self.name} 汪汪")
```

支持多继承（`class C(A, B)`），但读代码时尽量少用。

### 7.3 没有私有，只有约定

```python
class T:
    def __init__(self):
        self.public = 1
        self._internal = 2   # 约定：内部使用，别碰
        self.__mangled = 3   # 会做名字改写，但依然不是真的私有

    @property
    def value(self):         # getter，访问时不用加括号
        return self._internal

t = T()
t.value      # 2  —— 像属性一样访问
t._internal  # 能访问，只是"约定"别这么做
```

- `_x`：约定"内部成员，外部不要用"。
- `__x`：触发名称改写（name mangling），目的是避免子类冲突，**不是**为了安全。
- `@property`：把方法伪装成属性，实现 getter/setter，读代码很常见。

### 7.4 魔术方法（dunder）

`__xxx__` 这种双下划线方法是 Python 的"协议"，重写它们能定制对象行为：

```python
class Point:
    def __init__(self, x, y):
        self.x, self.y = x, y

    def __repr__(self):        # 调试打印（给开发者看）
        return f"Point({self.x}, {self.y})"

    def __eq__(self, other):   # == 比较
        return self.x == other.x and self.y == other.y

    def __len__(self):         # len(p)
        return 2

    def __getitem__(self, i):  # p[0] 下标访问
        return (self.x, self.y)[i]
```

常见的有：`__str__`（`print` 用）、`__repr__`（调试用）、`__eq__`、`__len__`、`__getitem__`、`__iter__`（可被 `for` 遍历）、`__enter__/__exit__`（支持 `with`）、`__call__`（对象可被当函数调用）。

### 7.5 鸭子类型

> "如果它走起来像鸭子、叫起来像鸭子，那它就是鸭子。"

Python 不要求"实现某个接口"才能用某个对象，只看它有没有需要的方法：

```python
def make_sound(thing):
    thing.speak()      # 不管 thing 是什么类，只要有 speak() 就行

make_sound(Dog("旺财", "土狗"))
make_sound(Animal("猫"))
```

这替代了 Java 的接口。读代码时，`def f(x)` 里的 `x` 只要"有某个方法"就能传进去。

---

## 8. 模块与导入

### 8.1 导入方式

```python
import os                        # 导入整个模块，用 os.path
from os import path              # 导入其中某个名字，直接用 path
from os import path as p         # 起别名
import numpy as np               # 第三方库常用别名
from . import utils              # 相对导入（包内部用）
```

### 8.2 `__name__ == "__main__"`

```python
def main():
    ...

if __name__ == "__main__":
    main()
```

含义：**当这个文件被直接运行时才执行**，被 `import` 时不会执行。这是脚本入口的固定写法，几乎所有可执行脚本都有。

---

## 9. 异常处理

```python
try:
    result = 10 / 0
except ZeroDivisionError as e:
    print("除零错误:", e)
except (ValueError, TypeError):
    print("其他错误")
except Exception as e:     # 兜底，捕获一切
    print("未知错误:", e)
else:
    print("没异常才执行")   # 少见
finally:
    print("无论如何都执行")
```

主动抛出：

```python
raise ValueError("参数不合法")
```

自定义异常（继承 `Exception`）：

```python
class MyError(Exception):
    pass
```

常见内置异常：`ValueError`、`TypeError`、`KeyError`（字典缺 key）、`IndexError`（越界）、`AttributeError`（属性不存在）、`FileNotFoundError`、`ZeroDivisionError`、`ImportError`。

---

## 10. 文件与 `with`

```python
# 推荐写法：with 自动关闭文件
with open("data.txt", "r", encoding="utf-8") as f:
    content = f.read()        # 读全部
    # 或逐行读：
    for line in f:
        print(line.strip())
```

文件模式：`"r"` 读、`"w"` 写（覆盖）、`"a"` 追加、`"b"` 二进制、`"+"` 读写。记得写 `encoding="utf-8"`，否则 Windows 上中文会乱码。

`with` 是**上下文管理器**，本质是自动帮你调用 `__enter__` 和 `__exit__`（异常时也会执行清理）。不仅用于文件，还用于锁、数据库连接等需要"用完释放"的资源。

---

## 11. 高频惯用法（读代码必备）

这一节是**看懂真实 Python 项目的关键**。以下写法在业务代码里出现的频率远超语法书。

### 11.1 列表推导式

```python
# 传统写法
result = []
for x in nums:
    if x > 0:
        result.append(x * 2)

# 推导式（等价，读代码最常见）
result = [x * 2 for x in nums if x > 0]
```

同样有字典/集合推导式：

```python
d = {k: v for k, v in pairs}
s = {x for x in nums}
```

看到 `[表达式 for x in ... if ...]` 这种一行式，就是推导式，从右往左读。

### 11.2 生成器与 `yield`

```python
def gen():
    yield 1
    yield 2
    yield 3

for x in gen():
    print(x)

# 生成器表达式：把 [] 换成 ()
squares = (x * x for x in range(10))
```

`yield` 让函数变成**生成器**，调用时**不立即执行**，每次 `for` 迭代时才算下一个值，**省内存**。读代码看到 `yield` 就理解为"返回一个惰性序列"。

### 11.3 解包

```python
a, b = 1, 2
a, b = b, a              # 交换，无需临时变量
first, *rest = [1, 2, 3, 4]   # first=1, rest=[2,3,4]
head, *mid, tail = [1, 2, 3, 4]  # head=1, mid=[2,3], tail=4
```

### 11.4 `enumerate` / `zip`

```python
for i, v in enumerate(items):        # 带下标遍历
    ...

for name, score in zip(names, scores):  # 同时遍历两个序列
    ...
```

### 11.5 f-string 格式化

```python
name, age = "张三", 30
s = f"我叫{name}，今年{age}岁"
s = f"{1/3:.2f}"        # 0.33，保留两位小数
s = f"{name:>10}"       # 右对齐，宽度 10
```

`f"..."` 里 `{...}` 是变量/表达式，这是最现代的字符串格式化方式（老代码可能用 `"{}".format(x)` 或 `"%"`）。

### 11.6 条件表达式

```python
# 等价于 Java/Kotlin 的 x ? a : b，但顺序不同
result = a if cond else b
```

### 11.7 真值判断（truthiness）

```python
# 这些都等价于 False：
# None, False, 0, 0.0, "" 空串, [] 空列表, {} 空字典, set() 空集合

if not items:        # 判断列表是否为空（惯用写法）
    ...
```

读代码常看到 `if not x:`、`if x:` 这种不带 `==` 的判断，就是利用真值规则。

### 11.8 `None` 与 `is`

```python
if x is None:       # 判断 None 用 is，不用 ==
if x is not None:
```

### 11.9 字典合并与默认值

```python
config = {**defaults, **overrides}   # 合并两个字典，后者覆盖前者

count = data.get("count", 0)         # 安全取值，给默认值
```

---

## 12. 工程实践

### 12.1 虚拟环境与包管理

```bash
python -m venv .venv          # 创建虚拟环境（隔离依赖）
# 激活（Windows）：
.venv\Scripts\activate
# 安装依赖：
pip install requests
pip install -r requirements.txt
```

看到项目根目录的 `requirements.txt`（老）或 `pyproject.toml`（新），就是依赖清单。

### 12.2 代码风格（PEP 8）

- 缩进 4 空格。
- 每行 ≤ 79~100 字符。
- 命名：类用 `PascalCase`，函数/变量用 `snake_case`，常量用 `UPPER_CASE`。

> 这是和 Java/Kotlin 最大区别之一：**方法名和变量名用下划线 `snake_case`**，不是 `camelCase`。看到 `get_user_name` 别觉得奇怪。

### 12.3 常见项目结构

```
project/
├── package_name/        # 主包
│   ├── __init__.py      # 标记为包（可为空）
│   ├── models.py
│   └── utils.py
├── tests/
│   └── test_utils.py
├── requirements.txt
├── pyproject.toml
└── README.md
```

`__init__.py` 让一个目录变成"包"（Python 3.3+ 也可以省略，但老项目都有）。

---

## 13. 易错点速查表

| 场景 | 陷阱 | 正确做法 |
|---|---|---|
| 可变默认参数 | `def f(x, a=[])` | `def f(x, a=None)` + 内部判空 |
| 判断 None | `x == None` | `x is None` |
| 判断空列表 | `len(x) == 0` | `if not x:` |
| 取字典值 | `d[key]` 会抛 KeyError | `d.get(key, default)` |
| 复制列表 | `b = a`（同一对象） | `b = a.copy()` 或 `b = a[:]` |
| 整数除法 | `/` 得 float | `//` 才是整除 |
| 自增 | `i++` 不存在 | `i += 1` |
| 循环改列表 | 边遍历边删会漏元素 | 遍历副本 `for x in lst[:]:` |
| 类型比较 | 不要用 `type(x) == int` | 用 `isinstance(x, int)` |

几个细节补充：

```python
a = [1, 2, 3]
b = a           # b 和 a 指向同一个 list，改 b 会影响 a！
b = a.copy()    # 这才是复制一份
b = a[:]        # 切片也是一种复制

10 / 3    # 3.3333...  （真除法，得 float）
10 // 3   # 3          （整除）
10 % 3    # 1
2 ** 10   # 1024       （幂运算）
```

---

## 结语

以你的基础，理解 Python 的**心智模型**之后，剩下的就是熟悉它的**惯用写法**。建议的阅读顺序：

1. 先看函数的**类型标注**和**文档字符串**，快速定位它干什么。
2. 遇到不认识的写法，对照本文第 11 节（推导式、解包、`enumerate`/`zip`、f-string）。
3. 改动前留意第 13 节的可变默认参数、`is None`、`dict.get` 这几个坑。

Python 的语法不多，真正的"阅读负担"来自它极简但陌生的惯用法——这些看多了自然就顺了。
