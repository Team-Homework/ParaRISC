# Chisel 零基础速通：写给熟悉 C++ 的 CPU 项目组

> 主线：从读懂语法，到独立写出组合逻辑、时序逻辑和可连接的 CPU 小模块。
>
> 前置知识：会 C++ 的变量、分支、循环、函数和类；不要求会 Scala 或 Verilog。
>
> 本文独立于架构讨论。学完再读 [C++ 模拟器迁移到 Chisel](../research/simulator-to-chisel-design.md)，会更容易理解实现取舍。

## 0. 怎么读：先会写，再把硬件想清楚

建议分三轮，每轮都亲手改例子：

| 轮次 | 阅读范围 | 完成标志 |
| --- | --- | --- |
| 第一轮：能读能写 | 第 1～6 节 | 写出加法器、选择器和带使能计数器 |
| 第二轮：会组织模块 | 第 7～11 节 | 写出 ALU、寄存器堆、握手缓冲和状态机 |
| 第三轮：能验证能迁移 | 第 12～16 节 | 跑测试、分析周期、理解循环展开的硬件成本 |

导航：[硬件思维](#s1) · [第一份工程](#s2) · [Scala 最小集](#s3) · [类型与位宽](#s4) · [组合逻辑](#s5) · [时序逻辑](#s6) · [数组与结构体](#s7) · [ALU](#s8) · [寄存器堆](#s9) · [握手与流水线](#s10) · [状态机](#s11) · [存储器](#s12) · [仿真测试](#s13) · [CPU 写法](#s14) · [常见错误](#s15) · [练习与速查](#s16)。

代码约定：标为“完整模块”的代码可以放入 `src/main/scala/Tutorial.scala`，同一文件顶部统一放这两行；未标完整模块的代码是局部片段，需要放进合适的 `Module` 或函数作用域。

```scala
import chisel3._
import chisel3.util._
```

本文采用 Scala 2 风格与 Chisel 7.x 的常用 API。第 2 节给出固定版本的练习配置，不表示仓库已经选定正式工具链。官方文档可能随版本更新，查询日期为 2026-09-20。

<a id="s1"></a>
## 1. 先换一个观念：代码描述的是电路

### 1.1 C++ 在执行算法，Chisel 在构造硬件

先看熟悉的 C++：

```cpp
uint32_t add(uint32_t a, uint32_t b) {
    return a + b;
}
```

调用它时，CPU 执行指令、计算结果。Chisel 则用 Scala 程序**生成一个有输入、有输出、有加法逻辑的电路**：

```scala
// 完整模块：两个 8 位输入，一个 9 位输出。
class Add8 extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt(8.W))
    val b = Input(UInt(8.W))
    val sum = Output(UInt(9.W))
  })

  io.sum := io.a +& io.b
}
```

这几行要读成：

1. `class Add8 extends Module`：定义一种硬件模块。
2. `IO(new Bundle { ... })`：把模块端口打包。
3. `UInt(8.W)`：8 根线组成的无符号数。
4. `Input`、`Output`：相对于当前模块的端口方向。
5. `:=`：连接信号；右边驱动左边。
6. `+&`：保留加法产生的额外进位，因此 `255 + 1 = 256` 能放入 9 位输出。

输入改变后，组合输出经过电路传播延迟随之改变；这里没有存储运算结果的寄存器，也没有“调用一次才执行一次”。`Module` 默认提供时钟和复位端口，但只有用到时序元件时，它们才参与对应状态的更新。

Chisel 是 Scala 中的硬件构造库，其代码要先运行以构造硬件，再交给工具生成 RTL。参见 [官方简介](https://www.chisel-lang.org/docs)。

### 1.2 必须分清的两个时间

```text
生成电路时：Scala 程序运行 → 建立电路结构 → 生成 SystemVerilog
电路运行时：输入变化、组合传播、时钟边沿到来、寄存器更新
```

| 写法 | 什么时候发生 | C++ 使用者可以怎样理解 |
| --- | --- | --- |
| `val width = 32` | 生成电路时 | 普通整数配置，类似用参数决定结构 |
| `if (width == 32)` | 生成电路时 | 选择要生成哪一种电路 |
| `for (i <- 0 until 4)` | 生成电路时 | 重复搭建四份连接或逻辑 |
| `when (io.enable)` | 电路运行时 | 根据一个硬件信号选择动作 |
| `RegInit(0.U(32.W))` | 定义硬件状态 | 构造复位到 0 的 32 位寄存器 |
| `count := count + 1.U` | 定义下一状态逻辑 | 下一拍写入当前值加一 |

**第一条记忆规律：Scala 管“造什么电路”，Chisel 信号管“电路怎样工作”。**

### 1.3 两种电路先记牢

| 电路 | 是否保存跨周期状态 | 例子 |
| --- | --- | --- |
| 组合逻辑 | 否 | 加法、比较、选择器、指令字段提取 |
| 时序逻辑 | 是 | PC、计数器、流水寄存器、队列指针 |

```text
输入 ──→ 组合逻辑 ──→ 输出

当前状态寄存器 ──→ 组合逻辑 ──→ 寄存器的下一状态输入
      ↑                              │
      └──────── 时钟边沿更新 ─────────┘
```

后面每看到一个例子，都问自己：**哪里存状态？哪里算下一状态？一共经过几个时钟边沿？**

<a id="s2"></a>
## 2. 第一份练习工程：把加法器生成出来

这一节是操作配方，暂时看不懂 `object`、`def` 也可以先照写，第 3 节会解释。

### 2.1 工具各自负责什么

| 工具 | 作用 | 与熟悉的 C++ 环境类比 |
| --- | --- | --- |
| JDK | 运行 Scala/JVM 工具 | 工具运行环境 |
| sbt | 获取依赖、编译 Scala、运行生成器和测试 | 构建工具，类似 CMake 加依赖管理 |
| Chisel 和编译器插件 | 构造并检查硬件 | 硬件库和配套编译支持 |
| firtool | 将中间表示降到 RTL | 硬件编译后端 |
| Verilator | 编译并仿真 RTL | 把生成的硬件模型变成可执行仿真程序 |

练习使用 JDK 17、sbt 1.10.7、Scala 2.13.16、Chisel 7.2.0。选固定组合便于复现；更换 Chisel 时应同时核对 Scala、插件和 firtool 的匹配关系。安装方法与 firtool 自动获取机制见 [官方安装说明](https://www.chisel-lang.org/docs/installation)，依赖声明的形式见 [Chisel 仓库说明](https://github.com/chipsalliance/chisel)。

### 2.2 文件布局

在独立的练习目录建立以下文件；本文只提供教程，不会自动创建这些工程文件。

```text
chisel-playground/
├── build.sbt
├── project/
│   └── build.properties
└── src/
    ├── main/scala/
    │   └── Tutorial.scala
    └── test/scala/
        └── TutorialSpec.scala       # 第 13 节再添加
```

`project/build.properties`：

```properties
sbt.version=1.10.7
```

`build.sbt`：

```scala
ThisBuild / scalaVersion := "2.13.16"
ThisBuild / version := "0.1.0"
ThisBuild / organization := "pararisc.learning"

val chiselVersion = "7.2.0"

libraryDependencies ++= Seq(
  "org.chipsalliance" %% "chisel" % chiselVersion,
  "org.scalatest" %% "scalatest" % "3.2.19" % Test
)

addCompilerPlugin(
  "org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full
)

scalacOptions ++= Seq("-deprecation", "-feature", "-language:reflectiveCalls")
```

这里的 `:=` 是 **sbt 配置 DSL 的操作符**，不是在连接电路。符号含义要看作用对象；初学时无需深入 sbt 语法。

`Tutorial.scala` 先放顶部两个 `import` 和第 1 节的 `Add8`，再加：

```scala
object EmitAdd8 {
  def main(args: Array[String]): Unit = {
    circt.stage.ChiselStage.emitSystemVerilogFile(
      new Add8,
      Array("--target-dir", "generated")
    )
  }
}
```

在工程根目录执行：

```bash
java -version
sbt compile
sbt "runMain EmitAdd8"
```

首次运行需要联网下载依赖。成功后检查 `generated/` 中的 `.sv`，寻找 `a`、`b`、`sum` 及它们之间的加法关系。工具可能调整命名、拆文件或优化表达式，重点看电路行为。

**生成成功只证明能生成 RTL。** 是否算对要做仿真；面积和频率还要走综合与时序流程。课程工具对 SystemVerilog 的接受范围，也需要在正式工程中另行确认。

<a id="s3"></a>
## 3. 只学够用的 Scala：从 C++ 对照过来

### 3.1 `val`、`var`、类型、分号

```scala
val width: Int = 32       // 显式类型
val ports = 2             // 推断为 Int
val hasBypass = true       // 推断为 Boolean
var generatedCount = 0     // Scala 变量；不要用它表示硬件寄存器
generatedCount += 1
```

| C++ | Scala | 注意 |
| --- | --- | --- |
| `const int n = 32;` | `val n = 32` | 固定名称绑定 |
| `int n = 32;` | `var n = 32` | 可重新赋值的 Scala 变量 |
| `int f(int x)` | `def f(x: Int): Int` | 类型写在名称后 |
| `class A : public B` | `class A extends B` | 继承写 `extends` |
| `a[i]` | `a(i)` | Scala/Chisel 的索引常用圆括号 |
| `// ...`、`/* ... */` | 相同 | 通常省略语句末尾分号 |

最容易误会的是：

```scala
val count = RegInit(0.U(8.W))
count := count + 1.U
```

`val` 固定的是“`count` 这个名字指向哪个硬件对象”，**不意味着寄存器里的比特不变**。可类比一个不能改指向的句柄；它指向的寄存器仍会在时钟边沿更新。

### 3.2 `=`、`:=`、`===`：三个符号各干一件事

```scala
val a = io.input      // =：Scala 层给对象起名字
io.output := a       // :=：连接硬件信号
val equal = a === 7.U // ===：构造硬件相等比较，结果是 Bool
```

口诀：**一个等号起名字，冒号等号连电路，三个等号比信号。**

- Scala 整数比较用 `width == 32`。
- 硬件相等用 `a === b`，硬件不等用 `a =/= b`。
- 不要把 `==`、`!=` 当作 Chisel 硬件比较；它们属于 Scala 相等性语义。

### 3.3 类参数就是生成电路的参数

```scala
// 完整模块：生成时决定数据位宽。
class AddN(width: Int) extends Module {
  require(width > 0)

  val io = IO(new Bundle {
    val a = Input(UInt(width.W))
    val b = Input(UInt(width.W))
    val sum = Output(UInt((width + 1).W))
  })
  io.sum := io.a +& io.b
}
```

`new AddN(32)` 选择 32 位加法电路，类似 C++ 用模板/构造参数配置结构。这里 `width` 是 Scala `Int`，运行中的电路不能通过改变它切换位宽。

`require(width > 0)` 在生成阶段检查参数；不是电路运行时的硬件断言。

### 3.4 函数、返回值、`object`

```scala
def twice(x: Int): Int = {
  x * 2                   // 最后一个表达式就是返回值
}

def addWord(a: UInt, b: UInt): UInt = {
  a + b                   // 调用时构造组合加法逻辑
}

object Sizes {
  val WordBits = 32
}
```

`object` 定义单例对象，适合集中放常量或程序入口。`Unit` 类似 C++ 的 `void`。`new Bundle { ... }` 是创建匿名子类并定义字段。

**硬件函数不是运行时的函数调用指令。** 两处调用 `addWord` 会描述两处加法表达式；是否被后端合并取决于优化，不能借此假设自动共享一个跨周期加法器。参见 [官方函数抽象说明](https://www.chisel-lang.org/docs/explanations/functional-abstraction)。

### 3.5 `for`、`Seq`、`map`：够看懂项目就行

```scala
val ids = 0 until 4                // 0、1、2、3，不含 4
val inclusiveIds = 0 to 4          // 包含 4
val powers = Seq(1, 2, 4, 8)       // Scala 序列
val doubled = powers.map(x => x * 2)
val sum = powers.reduce(_ + _)     // 逐步合并元素
```

`x => x * 2` 类似 C++ lambda `[](auto x) { return x * 2; }`；`_ + _` 是两个参数相加的简写。

先用普通 `for` 写清楚，再考虑 `map`。Scala 集合负责组织生成过程；需要用硬件地址选元素时，要用后面的 `Vec` 或存储器。

<a id="s4"></a>
## 4. 类型、字面量和位宽：比 C++ 更需要精确

### 4.1 数字加后缀：从软件值进入硬件世界

| 写法 | 含义 | 记忆法 |
| --- | --- | --- |
| `32` | Scala `Int` | 生成器里的普通数 |
| `32.W` | 32 位的宽度描述 | W = Width |
| `32.U` | 值为 32 的无符号硬件常量 | U = Unsigned |
| `32.U(8.W)` | 8 位无符号常量，值为 32 | 值在前，宽度在括号里 |
| `(-3).S(8.W)` | 8 位有符号常量，值为 -3 | S = Signed |
| `true.B` | 硬件布尔真 | B = Bool |
| `"hff".U(8.W)` | 十六进制 `ff` | h = hex |
| `"b1010".U(4.W)` | 二进制 `1010` | b = binary |

```scala
val dataType = UInt(32.W)    // 类型描述，还不是实际导线或寄存器
val data = Wire(UInt(32.W))  // 实际的组合信号
val flag = Wire(Bool())      // 一位布尔信号
val signed = Wire(SInt(32.W))
```

`UInt(32.W)` 类似“32 位无符号数据的规格”，还要通过 `IO`、`Wire`、`Reg` 等构造实际硬件。类型和字面量规则见 [官方数据类型说明](https://www.chisel-lang.org/docs/explanations/data-types)。

### 4.2 常用运算符对照

| 功能 | Chisel | 与 C++ 的区别 |
| --- | --- | --- |
| 普通加减 | `a + b`、`a - b` | 结果位宽有规则，不自动保留额外进位 |
| 扩位加减 | `a +& b`、`a -& b` | 比较大操作数宽度多一位 |
| 位运算 | `a & b`、`a \| b`、`a ^ b`、`~a` | 和 C++ 很像 |
| 布尔逻辑 | `p && q`、`p \|\| q`、`!p` | 用于 `Bool`；构造电路，别依赖软件短路执行 |
| 比较 | `===`、`=/=`、`<`、`<=`、`>`、`>=` | 结果为硬件 `Bool` |
| 移位 | `a << n`、`a >> n` | 右移填充取决于 `UInt` / `SInt` |
| 按位归约 | `a.andR`、`a.orR`、`a.xorR` | 将所有位归约成一位 |
| 条件选择 | `Mux(cond, x, y)` | 类似 `cond ? x : y`，构造选择器 |

运算符索引见 [官方 Operators](https://www.chisel-lang.org/docs/explanations/operators)。

### 4.3 `+` 和 `+&`：目标变宽不能挽回丢掉的进位

假设 `a`、`b` 都是 8 位 `UInt`：

```scala
val low = a + b        // 8 位：255 + 1 得到 0
val full = a +& b      // 9 位：255 + 1 得到 256
val carry = full(8)    // 第 8 位，类型 Bool
val lowByte = full(7, 0)
```

下面仍可能丢进位：

```scala
val out = Wire(UInt(9.W))
out := a + b            // 先做 8 位结果，再扩成 9 位
```

应改为 `out := a +& b`。口诀：**要进位，在运算符上加 `&`；接收端加宽不等于计算过程加宽。**

对于已知宽度的 `UInt`，常用结果宽度如下。完整规则见 [官方位宽推断](https://www.chisel-lang.org/docs/explanations/width-inference)。

| 表达式 | 结果位宽 |
| --- | --- |
| `a + b`、`a - b` | `max(wa, wb)` |
| `a +& b`、`a -& b` | `max(wa, wb) + 1` |
| `a * b` | `wa + wb` |
| `Cat(a, b)` | `wa + wb` |
| `a(hi, lo)` | `hi - lo + 1` |
| `Mux(c, a, b)` | 两分支位宽的较大值 |

`UInt` 减法仍是无符号位向量运算；不能因为用了 `-&` 就把结果当有符号整数。需要带符号的数学差值时，可以先用 `a.zext - b.zext`。

### 4.4 提取、拼接、扩展：译码最常用

```scala
val opcode = inst(6, 0)               // 共 7 位，左右端点都包含
val rd = inst(11, 7)                  // 共 5 位
val sign = inst(31)                   // 单个位，Bool
val joined = Cat(highByte, lowByte)   // 左边放高位，右边放低位
val immI = Cat(Fill(20, inst(31)), inst(31, 20))
```

I 型立即数原来是 12 位，复制符号位 20 次后拼到前面，得到 32 位补码位模式。口诀：**切片高位在前，拼接高位在左，符号扩展复制最高位。**

```scala
val x = "hff".U(8.W)
val signedX = x.asSInt   // 位不变：11111111 按补码解释为 -1
val unsignedX = signedX.asUInt
val nonNegative = x.zext // 9 位 SInt，值为 +255
```

`.asSInt` / `.asUInt` 改解释方式，不会自动把原值变成你想要的数学值；`.zext` 则添加 0 符号位，保住无符号正数。

### 4.5 移位的一个 CPU 陷阱

```scala
val shamt = io.b(4, 0)                  // RV32 的移位量只取低 5 位
val logical = io.a >> shamt             // UInt：高位补 0
val arithmetic = (io.a.asSInt >> shamt).asUInt
val leftWide = io.a << shamt
val leftWord = leftWide(31, 0)
```

`UInt` 右移是逻辑右移，`SInt` 右移是算术右移。动态左移的结果可能比输入宽很多，因此字长结果显式截取低 32 位；限制移位量宽度也能避免描述不必要的大移位网络。

<a id="s5"></a>
## 5. 组合逻辑：`Wire`、`Mux`、`when` 和优先级

### 5.1 `Wire` 不存状态，`WireDefault` 提供默认驱动

```scala
val result = Wire(UInt(32.W))
result := 0.U
when (io.enable) {
  result := io.input
}
```

也可以写：

```scala
val result = WireDefault(0.U(32.W))
when (io.enable) {
  result := io.input
}
```

`WireDefault` 的值是组合逻辑默认分支，不是复位值。这里没有保存“上次的 result”。组合输出或 `Wire` 必须有完整驱动；遗漏分支通常报未初始化错误，不应期待自动获得锁存器。

### 5.2 `Mux` 选值，`when` 写条件连接

```scala
io.out := Mux(io.sel, io.a, io.b)
```

等价的条件连接：

```scala
when (io.sel) {
  io.out := io.a
}.otherwise {
  io.out := io.b
}
```

多条件：

```scala
when (io.flush) {
  nextPc := io.redirect
}.elsewhen (io.stall) {
  nextPc := pc
}.otherwise {
  nextPc := pc + 4.U
}
```

口诀：**选一个值用 `Mux`，同时安排多个信号用 `when`。** `Mux` 的两个数据分支都描述硬件，不要把它理解成只构造被选中的那一支。

### 5.3 `if` 与 `when` 不能互换

```scala
// hasDebug 是 Scala Boolean：生成时决定是否存在这段硬件。
if (hasDebug) {
  printf(p"pc = ${Hexadecimal(pc)}\n")
}

// io.enable 是硬件 Bool：生成一个受 enable 控制的电路。
when (io.enable) {
  count := count + 1.U
}
```

`if (io.enable)` 不成立，因为 Scala `if` 要的是软件 `Boolean`。**参数决定结构用 `if`，端口决定行为用 `when`。**

### 5.4 多次连接：后写的有效连接优先

```scala
io.out := 0.U
when (io.aValid) { io.out := io.a }
when (io.bValid) { io.out := io.b }
```

两者同时为真时，`b` 优先。若改成 `when(aValid) ... .elsewhen(bValid) ...`，则 `a` 优先。

这不是按时间依次写入输出，而是描述一个有优先级的组合网络。有关连接覆盖和默认赋值，见 [官方组合逻辑说明](https://www.chisel-lang.org/docs/explanations/combinational-circuits)。

### 5.5 不要用软件累加方式连接导线

```scala
// 错误示范：不要复制运行。
val sum = WireDefault(0.U(32.W))
for (i <- 0 until 4) {
  sum := sum + io.values(i)
}
```

右边的 `sum` 仍然是同一根导线，加上后连接覆盖，并不形成 C++ 的四步累加，可能构成组合环。

若四个输入都是 8 位 `UInt`，可以明确构造加法树：

```scala
val pair0 = io.values(0) +& io.values(1) // 9 位
val pair1 = io.values(2) +& io.values(3) // 9 位
val total = pair0 +& pair1              // 10 位
io.sum := total
```

每一层都有独立中间结果。这是在本周期内传播的两层组合逻辑，**不是自动执行四个周期**：第一层两个加法器，第二层一个加法器。

<a id="s6"></a>
## 6. 时序逻辑：`Reg` 才是硬件的记忆

### 6.1 `Reg` 家族的记忆方法

| 写法 | 含义 |
| --- | --- |
| `Reg(UInt(32.W))` | 建一个 32 位寄存器，没有指定复位值 |
| `RegInit(0.U(32.W))` | 建寄存器，并指定复位值 0 |
| `RegNext(x)` | 每拍采样 `x`，输出上一拍采样值；未指定复位值 |
| `RegNext(x, 0.U)` | 同上，并指定复位值 |
| `RegEnable(x, en)` | 只在 `en` 时采样 `x`，未使能时保持；未指定复位值 |
| `RegEnable(x, 0.U, en)` | 同上，并指定复位值 |

口诀：**Reg 存，Init 复位，Next 下一拍，Enable 看使能。** `RegInit` 描述复位行为，不是每周期清零，也不保证任何物理实现一上电就自动为该值；仿真与系统需要按约定施加复位。本文按普通同步单时钟设计讲解，异步复位等属于后续专题。

### 6.2 完整例子：带清零和使能的模 N 计数器

```scala
// 完整模块：clear 优先于 enable。
class ModCounter(n: Int) extends Module {
  require(n >= 2)
  private val width = log2Ceil(n)

  val io = IO(new Bundle {
    val clear = Input(Bool())
    val enable = Input(Bool())
    val value = Output(UInt(width.W))
    val last = Output(Bool())
  })

  val count = RegInit(0.U(width.W))

  when (io.clear) {
    count := 0.U
  }.elsewhen (io.enable) {
    count := Mux(count === (n - 1).U, 0.U, count + 1.U)
  }

  io.value := count
  io.last := count === (n - 1).U
}
```

`log2Ceil(n)` 是“至少需要几位才能编码 n 种状态”，例如 `log2Ceil(10) = 4`。计数器的合法值是 `0..n-1`，所以 n 不是 2 的幂时，需要显式在 `n-1` 回到 0。

未命中任何赋值分支时，**寄存器保持原值**；这和 `Wire` 缺少驱动不同。寄存器与下一状态语义见 [官方时序逻辑说明](https://www.chisel-lang.org/docs/explanations/sequential-circuits)。

对应的 C++ 周期模型可以这样想：

```cpp
unsigned next = current;
if (clear) next = 0;
else if (enable) next = current == n - 1 ? 0 : current + 1;
// 时钟边沿到来：
current = next;
```

取 `n = 4`，复位后 `value = 0`：

| 边沿前 value | clear | enable | 边沿后 value |
| ---: | ---: | ---: | ---: |
| 0 | 0 | 1 | 1 |
| 1 | 0 | 0 | 1 |
| 1 | 0 | 1 | 2 |
| 2 | 1 | 1 | 0 |
| 3 | 0 | 1 | 0 |

最后一行单独展示回卷场景，不是紧接上一行的连续轨迹。`last` 是当前计数值等于 `n-1`，不是“本拍一定会回卷”；后者还要看 `enable` 和 `clear`。

### 6.3 两个寄存器同时更新，右边读的是当前值

```scala
val a = RegInit(1.U(8.W))
val b = RegInit(2.U(8.W))
a := b
b := a
```

下个时钟边沿后，`a = 2`、`b = 1`，发生交换。不能套用 C++ 顺序执行后“两者都为 2”的结果。

再看流水线：

```scala
val first = RegNext(io.input, 0.U)
val second = RegNext(first, 0.U)
io.output := second
```

若某个值在第一个采样边沿前到达输入，第一个边沿进入 `first`，第二个边沿进入 `second`。**一个 `Reg` 边界对应一次采样，不看代码写了多少行。**

### 6.4 同一个寄存器写两次，不等于加两次

```scala
when (io.eventA) { count := count + 1.U }
when (io.eventB) { count := count + 1.U }
```

两事件同时发生时，后一个连接覆盖前一个，结果只加一。若确实要统计两事件数量：

```scala
val increment = io.eventA.asUInt +& io.eventB.asUInt
count := count + increment
```

`increment` 是 2 位，能表示 0、1、2。最终计数器是否允许回卷，需要按业务定义。

### 6.5 完整例子：检测一个同步信号的上升沿

```scala
class RisingEdge extends Module {
  val io = IO(new Bundle {
    val level = Input(Bool())
    val pulse = Output(Bool())
  })

  val previous = RegNext(io.level, false.B)
  io.pulse := io.level && !previous
}
```

输入本拍是 1、上拍采样值是 0 时，`pulse` 为真；输入持续为 1 后，`previous` 也变为 1，脉冲结束。这里假定输入来自同一时钟域，是用于说明“记住过去，再与现在比较”的例子；异步外部输入还需要专门的跨时钟域处理。

<a id="s7"></a>
## 7. 用 `Bundle` 和 `Vec` 表达结构体与数组

### 7.1 `Bundle` 像结构体，但字段是硬件

C++ 中的一组执行结果：

```cpp
struct Result {
    uint32_t data;
    uint8_t destination;
    bool valid;
};
```

Chisel 可以写成：

```scala
class Result extends Bundle {
  val data = UInt(32.W)
  val destination = UInt(5.W)
  val valid = Bool()
}
```

这个类描述的是一组字段的类型。使用时再决定它是端口、组合信号还是寄存器：

```scala
val incoming = IO(Input(new Result))
val outgoing = IO(Output(new Result))
val temporary = Wire(new Result)
val saved = Reg(new Result) // 没有指定复位值

temporary := incoming
saved := temporary
outgoing := saved
```

同结构的数据包可整包连接。若使用未复位的数据寄存器，应另有有效位保证未初始化内容不会被当作有效结果使用。

### 7.2 `Vec` 是硬件集合，存不存状态看外层

```scala
val wires = Wire(Vec(4, UInt(8.W)))
val regs = Reg(Vec(4, UInt(8.W)))
val zeroRegs = RegInit(VecInit(Seq.fill(4)(0.U(8.W))))
val constantTable = VecInit(3.U(8.W), 5.U(8.W), 7.U(8.W), 9.U(8.W))
```

分层读法：

```text
UInt(8.W)                         一个元素是 8 位
Vec(4, UInt(8.W))                  有 4 个这种元素
Reg(Vec(4, UInt(8.W)))              用寄存器保存这 4 个元素
```

口诀：**Bundle 把不同字段装一起，Vec 把同类型元素排一排，Reg 才让它们记住值。** 聚合类型用法见 [官方 Bundles and Vecs](https://www.chisel-lang.org/docs/explanations/bundles-and-vecs)。

### 7.3 静态索引和动态索引的成本不同

```scala
val fixed = regs(2)        // 直接取第 2 项
val selected = regs(addr)  // addr 是 UInt：硬件动态选择
```

当 `regs` 是寄存器数组时，动态读取通常需要选择网络；动态写入需要地址译码和各项写使能。两个不同地址的组合读，意味着需要提供两组读选择能力。

`Vec` 长度在生成阶段固定，不能像 `std::vector` 一样运行时 `push_back`。队列要用固定容量加指针、计数器和有效位表示。

### 7.4 循环会展开：四个加法器还是一个加法器

```scala
// 放在具有对应 Vec 输入输出的 Module 中。
for (i <- 0 until 4) {
  io.out(i) := io.a(i) + io.b(i)
}
```

这描述四路同时计算的加法逻辑。若要一个加法器分四拍工作，需要增加索引寄存器、每拍选一组输入、保存结果，并在四拍后报告完成。

**C++ 的循环次数主要影响运行时间；Chisel 的静态展开常常影响硬件数量。** 是否真的形成四份独立单元，还会受常量传播和综合优化影响，但不能假定工具自动把并行逻辑调度成多周期共享资源。

<a id="s8"></a>
## 8. 综合例子一：一个 32 位整数 ALU

这个例子把类型、位宽、默认赋值、移位和 `switch` 放在一起。`op` 是本教程自定义的控制编码，**不是 RISC-V 指令编码**；真实 CPU 需要译码器把指令转换为这些控制值。

```scala
object AluOp {
  val Add  = 0.U(4.W)
  val Sub  = 1.U(4.W)
  val And  = 2.U(4.W)
  val Or   = 3.U(4.W)
  val Xor  = 4.U(4.W)
  val Sll  = 5.U(4.W)
  val Srl  = 6.U(4.W)
  val Sra  = 7.U(4.W)
  val Slt  = 8.U(4.W)
  val Sltu = 9.U(4.W)
}

// 完整模块；同时复制上面的 AluOp。
class TinyAlu extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt(32.W))
    val b = Input(UInt(32.W))
    val op = Input(UInt(4.W))
    val result = Output(UInt(32.W))
    val illegal = Output(Bool())
  })

  val shamt = io.b(4, 0)
  val shiftedLeft = io.a << shamt

  io.result := 0.U
  io.illegal := true.B

  switch (io.op) {
    is (AluOp.Add) {
      io.result := io.a + io.b
      io.illegal := false.B
    }
    is (AluOp.Sub) {
      io.result := io.a - io.b
      io.illegal := false.B
    }
    is (AluOp.And) {
      io.result := io.a & io.b
      io.illegal := false.B
    }
    is (AluOp.Or) {
      io.result := io.a | io.b
      io.illegal := false.B
    }
    is (AluOp.Xor) {
      io.result := io.a ^ io.b
      io.illegal := false.B
    }
    is (AluOp.Sll) {
      io.result := shiftedLeft(31, 0)
      io.illegal := false.B
    }
    is (AluOp.Srl) {
      io.result := io.a >> shamt
      io.illegal := false.B
    }
    is (AluOp.Sra) {
      io.result := (io.a.asSInt >> shamt).asUInt
      io.illegal := false.B
    }
    is (AluOp.Slt) {
      io.result := (io.a.asSInt < io.b.asSInt).asUInt
      io.illegal := false.B
    }
    is (AluOp.Sltu) {
      io.result := (io.a < io.b).asUInt
      io.illegal := false.B
    }
  }
}
```

读代码时注意：

- `switch` / `is` 来自 `chisel3.util._`，没有 C++ 的贯穿执行，也不用 `break`。
- 在 `switch` 前提供默认值，未匹配时输出 0 并置 `illegal`。
- 32 位加减保留低 32 位，符合这里的字长运算目的；算术溢出与非法操作码是两回事。
- `SLT` 把输入位模式解释为有符号数，`SLTU` 按无符号比较。
- 比较结果转为 1 位 `UInt`，连接到 32 位输出时补零，得到 0 或 1。

手算几个结果，再在第 13 节写成测试：

| 操作 | a | b | result |
| --- | --- | --- | --- |
| ADD | `0xffffffff` | `1` | `0x00000000` |
| SLT | `0xffffffff` | `1` | `1`，因为 -1 < 1 |
| SLTU | `0xffffffff` | `1` | `0` |
| SRA | `0x80000000` | `1` | `0xc0000000` |
| SRL | `0x80000000` | `1` | `0x40000000` |

这只是组合 ALU 教学例子，不含乘除法、异常处理或流水控制；也没有自动保证加减共享单元。增加 `*` 或 `/` 只会描述运算，不能由此假设得到符合目标面积和延迟的迭代执行单元。

### 8.1 子模块必须通过 `Module(...)` 实例化

```scala
// 完整模块：用两个 AddN 搭一个三输入加法器。
class AddThree extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt(8.W))
    val b = Input(UInt(8.W))
    val c = Input(UInt(8.W))
    val sum = Output(UInt(10.W))
  })

  val first = Module(new AddN(8))
  val second = Module(new AddN(9))

  first.io.a := io.a
  first.io.b := io.b
  second.io.a := first.io.sum
  second.io.b := io.c        // 8 位 UInt 补零到 9 位
  io.sum := second.io.sum
}
```

`class` 定义模块种类，`Module(new ...)` 在父模块内创建实际实例。这里两个模块串联，但没有寄存器，仍是组合路径；**模块边界不是流水线边界**。

<a id="s9"></a>
## 9. 综合例子二：双读单写寄存器堆

目标：32 个 32 位寄存器、两个组合读端口、一个时钟边沿写端口，`x0` 恒为 0，并显式支持同拍写回旁路。

```scala
// 完整模块：便于学习，复位时把全部寄存器清零。
class RegisterFile extends Module {
  val io = IO(new Bundle {
    val rs1 = Input(UInt(5.W))
    val rs2 = Input(UInt(5.W))
    val rd = Input(UInt(5.W))
    val wen = Input(Bool())
    val wdata = Input(UInt(32.W))
    val rdata1 = Output(UInt(32.W))
    val rdata2 = Output(UInt(32.W))
  })

  val regs = RegInit(VecInit(Seq.fill(32)(0.U(32.W))))
  val writeEnable = io.wen && (io.rd =/= 0.U)

  when (writeEnable) {
    regs(io.rd) := io.wdata
  }

  def readPort(addr: UInt): UInt = {
    Mux(addr === 0.U, 0.U(32.W),
      Mux(writeEnable && (io.rd === addr), io.wdata, regs(addr)))
  }

  io.rdata1 := readPort(io.rs1)
  io.rdata2 := readPort(io.rs2)
}
```

读路径的优先级是：

1. 地址是 0，直接返回 0。
2. 本拍有效写入的地址和读地址相同，组合输出 `wdata`。
3. 其余情况返回数组中的当前值。

例如 `regs(5)` 当前为 10，本拍 `wen=1, rd=5, wdata=99, rs1=5`。在写入边沿之前，旁路已让 `rdata1=99`；到边沿时，99 才进入实际寄存器。

如果删除内层 `Mux`，边沿前读到的就是旧值 10。两种行为都可以被电路实现，但 CPU 的依赖处理必须知道到底采用哪一种。

这里的两个函数调用描述两条读通路，不会让硬件轮流调用一个读函数。完整复位也有硬件成本；正式寄存器堆是否复位全部数据、采用何种实现，要结合架构和工艺决定。**这个结构是 CPU 寄存器堆，不是课程允许用来替代统一主存的方案。**

<a id="s10"></a>
## 10. 接口与流水线：`valid`、`ready`、`fire`

### 10.1 为什么不能把连接当函数调用

软件中 `queue.push(x)` 可以封装等待与分配；硬件中，发送方和接收方每拍同时工作。必须明确“有数据”和“能接收”分别由谁给出。

`Decoupled(UInt(32.W))` 描述一组常见握手信号：

| 信号 | 谁驱动 | 含义 |
| --- | --- | --- |
| `bits` | 发送方 | 数据内容 |
| `valid` | 发送方 | 这份数据有效 |
| `ready` | 接收方 | 本拍能够接收 |
| `fire` | `valid && ready` 的结果 | 本拍满足传输条件，在采样边沿完成交接 |

```scala
val in = Flipped(Decoupled(UInt(32.W))) // 当前模块接收数据
val out = Decoupled(UInt(32.W))        // 当前模块发送数据
```

`Flipped` 翻转各字段方向。`valid` 和 `bits` 同向传输，`ready` 反向传回。接口方向与 `Decoupled` 的定义见 [官方接口说明](https://www.chisel-lang.org/docs/explanations/interfaces-and-connections)。

**本文的缓冲模块约定：一旦给出有效输出，遇到 `!ready` 就保持该数据，直到被接收。** 裸 `Decoupled` 类型本身不强制所有生产者都满足保持约定；连接模块时仍须核对协议。

口诀：**valid 说“我有”，ready 说“我能收”，fire 才是“交接成功”。** 计数、出队和释放资源通常应该依据 `fire`，不能只看 `valid`。

### 10.2 完整例子：一个可停顿的一槽流水缓冲

```scala
// 完整模块：最短延迟一拍；允许同拍送走旧数据并接收新数据。
class Elastic1(width: Int) extends Module {
  require(width > 0)

  val io = IO(new Bundle {
    val in = Flipped(Decoupled(UInt(width.W)))
    val out = Decoupled(UInt(width.W))
  })

  val full = RegInit(false.B)
  val data = Reg(UInt(width.W))

  io.out.valid := full
  io.out.bits := data
  io.in.ready := !full || io.out.ready

  when (io.in.ready) {
    full := io.in.valid
    when (io.in.valid) {
      data := io.in.bits
    }
  }
}
```

逐种情况检查：

| 当前 full | out.ready | in.valid | 边沿后的动作 |
| ---: | ---: | ---: | --- |
| 0 | 任意 | 0 | 保持空 |
| 0 | 任意 | 1 | 接收新数据，变满 |
| 1 | 0 | 任意 | `in.ready=0`，保持旧数据与有效位 |
| 1 | 1 | 0 | 旧数据送走，变空 |
| 1 | 1 | 1 | 旧数据送走，新数据替换，仍满 |

`data` 不复位，但 `full` 复位为假，因此消费者不会把初始未知数据当作有效传输。复位是例外控制过程；上表讨论正常运行。

这个模块满速时每拍可传一个数据，但一个新输入至少跨越一次寄存器采样才出现在有效输出。**延迟一拍和吞吐每拍一个并不矛盾。**

还有一个实现细节：`out.ready` 会组合传到 `in.ready`。很多级串接时，ready 路径可能变长；数据有寄存器不代表所有控制路径都已切断。

### 10.3 连接两个握手接口，先学会手写方向

假设 `producer` 和 `consumer` 是已经实例化的子模块：

```scala
consumer.io.in.bits := producer.io.out.bits
consumer.io.in.valid := producer.io.out.valid
producer.io.out.ready := consumer.io.in.ready
```

对结构、方向匹配的接口，可以用聚合连接：

```scala
consumer.io.in <> producer.io.out
```

在 Chisel 7.x 还会看到 `consumer.io.in :<>= producer.io.out` 等 Connectable 写法。先理解逐字段方向，再读缩写；新连接操作符在类型和截断检查上有不同规则，详见 [官方 Connectable Operators](https://www.chisel-lang.org/docs/explanations/connectable)。

**不能对带反向 ready 的接口，一概用 `:=` 当作“全包同向复制”。**

### 10.4 完整例子：四项队列

```scala
class Queue4 extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(UInt(32.W)))
    val out = Decoupled(UInt(32.W))
  })

  val queue = Module(new Queue(UInt(32.W), entries = 4))
  queue.io.enq <> io.in
  io.out <> queue.io.deq
}
```

`enq` 是 enqueue，`deq` 是 dequeue。标准 `Queue` 把存储、指针和满空管理封装起来。`pipe`、`flow` 等选项会改变握手和直通行为，使用前应针对边界做测试；这里用默认配置，不把它理解为任意状态下都能同拍进出。

<a id="s11"></a>
## 11. 状态机：把“要做几拍”明确写出来

下面实现一个可配置延迟的加一服务。接收请求时保存 `bits + 1`，等待固定拍数，再提供响应；若下游不接收，就保持响应。它用于学习多周期控制，**不是课程统一内存模型，也不是多周期加法器实现**。

```scala
object DelayState extends ChiselEnum {
  val idle, waitResult, respond = Value
}

// 完整模块；同时复制上面的 DelayState。
class DelayedIncrement(latency: Int = 3) extends Module {
  require(latency >= 1)
  private val counterWidth = math.max(1, log2Ceil(latency))

  val io = IO(new Bundle {
    val req = Flipped(Decoupled(UInt(32.W)))
    val resp = Decoupled(UInt(32.W))
  })

  val state = RegInit(DelayState.idle)
  val left = RegInit(0.U(counterWidth.W))
  val result = Reg(UInt(32.W))

  io.req.ready := state === DelayState.idle
  io.resp.valid := state === DelayState.respond
  io.resp.bits := result

  switch (state) {
    is (DelayState.idle) {
      when (io.req.fire) {
        result := io.req.bits + 1.U
        if (latency == 1) {
          state := DelayState.respond
        } else {
          left := (latency - 2).U
          state := DelayState.waitResult
        }
      }
    }
    is (DelayState.waitResult) {
      when (left === 0.U) {
        state := DelayState.respond
      }.otherwise {
        left := left - 1.U
      }
    }
    is (DelayState.respond) {
      when (io.resp.fire) {
        state := DelayState.idle
      }
    }
  }
}
```

这里特意把两种分支写在一起：`if (latency == 1)` 是生成阶段选择结构，`when (io.req.fire)` 是运行时响应握手。

定义采样边沿 `E0` 为请求被接收的边沿，`latency = 3` 时：

| 时刻 | 边沿后的状态 | left | resp.valid |
| --- | --- | --- | --- |
| E0 | waitResult | 1 | 0 |
| E1 | waitResult | 0 | 0 |
| E2 | respond | 0 | 1 |
| E3 | 若 ready 则消费响应并回 idle | 0 | 消费后变 0 |

所以本例的 `latency=3` 指 **请求在 E0 交接，响应最早在 E3 交接**，两次交接相隔 3 个周期。不要把“边沿之后 valid 拉高”与“该边沿已经发生响应交接”混为一谈。

该模块一次只处理一个请求，且响应被接收后才重新接受下一请求；延迟和接受间隔不同。想让多个请求同时在途，需要流水线或更丰富的状态存储，不能只缩短倒计时。

<a id="s12"></a>
## 12. 存储器：数组语法相似，时序必须区分

| 结构 | 典型读取行为 | 适用理解 |
| --- | --- | --- |
| `Reg(Vec(...))` | 组合选出当前项 | 显式寄存器阵列 |
| `Mem(depth, UInt(...))` | 异步读，时钟边沿写 | 异步读存储器描述 |
| `SyncReadMem(depth, UInt(...))` | 同步读、同步写 | 地址在边沿采样，随后给出该次读结果 |

具体能映射到什么 SRAM/FPGA 资源，取决于端口、读写模式和后端；名字含 `Mem` 不等于在项目评分中自动“面积免费”。语义见 [官方 Memories](https://www.chisel-lang.org/docs/explanations/memories)。

### 12.1 完整例子：带读有效位的同步 RAM

```scala
class SyncRam extends Module {
  val io = IO(new Bundle {
    val ren = Input(Bool())
    val raddr = Input(UInt(8.W))
    val rdata = Output(UInt(32.W))
    val rvalid = Output(Bool())
    val wen = Input(Bool())
    val waddr = Input(UInt(8.W))
    val wdata = Input(UInt(32.W))
  })

  val mem = SyncReadMem(256, UInt(32.W))
  val collision = io.wen && (io.waddr === io.raddr)
  val readEnable = io.ren && !collision
  val readData = mem.read(io.raddr, readEnable)

  when (io.wen) {
    mem.write(io.waddr, io.wdata)
  }

  io.rvalid := RegNext(readEnable, false.B)
  io.rdata := readData
}
```

假设地址 7 已写入已知数据；在某个边沿前给 `ren=1, raddr=7`，并且没有同址写冲突。到该边沿采样地址后，读结果与 `rvalid=1` 在接下来的周期对应出现。这条读路径有同步读延迟，不能把 `mem.read` 当成本周期任意地址查询函数。

本例把同拍同址读写定义为“执行写，不产生有效读响应”；如果调用方还需要该次读取，必须重试。这样不依赖默认未定义的同址冲突读值。正式协议也可以选择旁路或其他行为，但必须明确实现。

未写过的存储内容没有保证；`rvalid` 只代表有读操作，不代表该地址已初始化。没有有效读时也不要假设 `rdata` 自动保持前一次结果，需要保持就另加寄存器。

在 ParaRisc 中，课程统一主存应按正式接口接入。这里的 RAM 用于学习 Chisel 存储语法；请求延迟、端口带宽和评分面积不能由这一段代码替代课程规定。

<a id="s13"></a>
## 13. 怎么证明自己写对了：驱动、步进、检查

### 13.1 测试中的代码是在操作仿真器

本文使用 Chisel 自带的 ChiselSim，并通过 ScalaTest 组织测试；需要可用的 Verilator、C++ 编译器和 Make。API 及环境要求见 [官方 Testing](https://www.chisel-lang.org/docs/explanations/testing)。

| 写法 | 含义 | 类比 |
| --- | --- | --- |
| `dut.io.a.poke(3)` | 给输入端口施加 3 | 设置测试输入 |
| `dut.io.out.peek()` | 读取当前端口值 | 观察结果 |
| `dut.io.out.expect(7)` | 检查当前输出为 7 | 带报错信息的检查 |
| `dut.clock.step(1)` | 推进一个时钟周期 | 推动状态更新 |

`dut` 是 design under test，被测模块。这里 `dut => { ... }` 是 Scala lambda，参数就是测试框架提供的模块句柄。

**给寄存器输入 `poke` 不等于寄存器已更新；需要时钟。** 组合输出不必人为多打一拍才检查，测试 API 会处理读取前的仿真求值。

### 13.2 一份可以放进工程的测试文件

将对应的完整模块放入 `Tutorial.scala`，以下代码放入 `src/test/scala/TutorialSpec.scala`。`simulate` 会先执行模块初始化/复位流程；计数器例子再显式复位一次，帮助看懂时序。

```scala
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class TutorialSpec extends AnyFunSuite with ChiselSim {
  test("Add8 keeps the carry") {
    simulate(new Add8) { dut =>
      dut.io.a.poke(255)
      dut.io.b.poke(1)
      dut.io.sum.expect(256)
    }
  }

  test("counter holds, wraps, and gives clear priority") {
    simulate(new ModCounter(4)) { dut =>
      dut.io.clear.poke(false)
      dut.io.enable.poke(false)
      dut.reset.poke(true)
      dut.clock.step(1)
      dut.reset.poke(false)
      dut.io.value.expect(0)

      dut.io.enable.poke(true)
      dut.clock.step(3)
      dut.io.value.expect(3)
      dut.io.last.expect(true)

      dut.io.enable.poke(false)
      dut.clock.step(2)
      dut.io.value.expect(3)

      dut.io.enable.poke(true)
      dut.clock.step(1)
      dut.io.value.expect(0)
      dut.clock.step(1)
      dut.io.value.expect(1)

      dut.io.clear.poke(true) // clear 和 enable 同时为真
      dut.clock.step(1)
      dut.io.value.expect(0)
    }
  }

  test("ALU distinguishes signed and unsigned operations") {
    simulate(new TinyAlu) { dut =>
      // 在测试代码中用 Scala 数字驱动 op，编码对应 AluOp。
      dut.io.a.poke(BigInt("ffffffff", 16))
      dut.io.b.poke(1)
      dut.io.op.poke(0) // ADD
      dut.io.result.expect(0)
      dut.io.illegal.expect(false)

      dut.io.op.poke(8) // SLT
      dut.io.result.expect(1)
      dut.io.op.poke(9) // SLTU
      dut.io.result.expect(0)

      dut.io.a.poke(BigInt("80000000", 16))
      dut.io.op.poke(7) // SRA
      dut.io.result.expect(BigInt("c0000000", 16))
      dut.io.op.poke(6) // SRL
      dut.io.result.expect(BigInt("40000000", 16))

      dut.io.op.poke(15)
      dut.io.illegal.expect(true)
      dut.io.result.expect(0)
    }
  }

  test("register file bypasses a write and protects x0") {
    simulate(new RegisterFile) { dut =>
      dut.io.rs1.poke(5)
      dut.io.rs2.poke(0)
      dut.io.rd.poke(5)
      dut.io.wdata.poke(99)
      dut.io.wen.poke(true)
      dut.io.rdata1.expect(99) // 写入边沿前由旁路给出
      dut.clock.step(1)

      dut.io.wen.poke(false)
      dut.io.rdata1.expect(99) // 此时来自实际保存的数据
      dut.io.rd.poke(0)
      dut.io.wdata.poke(123)
      dut.io.wen.poke(true)
      dut.clock.step(1)
      dut.io.rdata2.expect(0)
    }
  }

  test("buffer holds under backpressure and replaces on transfer") {
    simulate(new Elastic1(8)) { dut =>
      dut.io.in.valid.poke(false)
      dut.io.in.bits.poke(0)
      dut.io.out.ready.poke(false)
      dut.io.out.valid.expect(false)
      dut.io.in.ready.expect(true)

      dut.io.in.valid.poke(true)
      dut.io.in.bits.poke(42)
      dut.clock.step(1) // 输入交接，保存 42
      dut.io.out.valid.expect(true)
      dut.io.out.bits.expect(42)
      dut.io.in.ready.expect(false)

      dut.io.in.bits.poke(99) // 下一个请求；当前不能被接收
      dut.clock.step(2)
      dut.io.out.bits.expect(42)
      dut.io.out.valid.expect(true)

      dut.io.out.ready.poke(true)
      dut.io.in.ready.expect(true)
      dut.clock.step(1) // 同拍送出 42，接收 99
      dut.io.out.valid.expect(true)
      dut.io.out.bits.expect(99)

      dut.io.in.valid.poke(false)
      dut.clock.step(1) // 送出 99，无新输入
      dut.io.out.valid.expect(false)
    }
  }
}
```

运行：

```bash
sbt test
sbt "testOnly TutorialSpec"
```

上面不是完整验证计划，而是五类关键行为的起点。之后增加随机输入、参数变化和连续背压，并让 C++/Scala 参考模型提供期望值。

### 13.3 三种排错工具分别在哪个阶段工作

```scala
require(entries >= 2)        // Scala：生成阶段检查配置
println(s"entries=$entries") // Scala：生成时打印一次

assert(used <= entries.U)    // Chisel：仿真时检查硬件条件
when (io.out.fire) {
  printf(p"send data=0x${Hexadecimal(io.out.bits)}\n")
}
```

这段是模块内部片段，假定已有 `entries`、`used` 和 `io.out`。`s"..."` 插入 Scala 值，`p"..."` 配合 Chisel `printf` 打印运行时信号。

看到错误先分层：

1. **Scala 编译错误**：缺导入、类型不匹配、名字或括号错误。
2. **电路生成错误**：端口未驱动、错误方向连接、非法硬件构造。
3. **RTL 编译/仿真错误**：工具不兼容、组合环、测试断言失败。
4. **功能通过但指标不达标**：看资源数量、组合路径、访存与停顿，语法正确不代表微架构合适。

不要拿 `DontCare` 去“消灭”自己还没弄懂的未连接错误；先弄清该信号在什么条件下应该是什么值。

<a id="s14"></a>
## 14. 把语法迁移到 CPU：三个常见写法

### 14.1 PC 更新：显式写清优先级

```scala
// 模块内部片段：redirect 优先，stall 次之。
val pc = RegInit("h80000000".U(32.W))
when (io.redirectValid) {
  pc := io.redirectPc
}.elsewhen (!io.stall) {
  pc := pc + 4.U
}
io.pc := pc
```

起始地址只是例子，应按课程加载入口配置。这里没有请求握手；接入取指接口后，要根据实际“接收了几条指令”等事件确定 PC 推进，不能在内存等待期间盲目每拍加 4。

### 14.2 流水有效位：flush、接收、停顿

```scala
// 模块内部片段：payload 类型为 Result，accept 表示本级允许更新。
val valid = RegInit(false.B)
val payload = Reg(new Result)

when (io.flush) {
  valid := false.B
}.elsewhen (io.accept) {
  valid := io.inValid
  when (io.inValid) {
    payload := io.inPayload
  }
}

io.outValid := valid
io.outPayload := payload
```

这里 `flush` 优先于接收；不接收且不清空时保持状态。清空有效位通常就足以表示该槽为空，不一定需要把每一位数据也清零。消费者必须只在有效时使用数据。

如果同时使用 `payload.valid` 和外层 `valid`，要避免两个有效位含义不一致；正式设计可把 `Result` 中的 `valid` 拿掉，只让外层协议管理有效性。

### 14.3 从 RS 选出一个就绪条目

```scala
// 完整模块：固定选择编号最小的就绪项。
class ReadySelector(entries: Int) extends Module {
  require(entries >= 2)
  private val indexWidth = log2Ceil(entries)

  val io = IO(new Bundle {
    val valid = Input(Vec(entries, Bool()))
    val src1Ready = Input(Vec(entries, Bool()))
    val src2Ready = Input(Vec(entries, Bool()))
    val found = Output(Bool())
    val index = Output(UInt(indexWidth.W))
  })

  val eligible = Wire(Vec(entries, Bool()))
  for (i <- 0 until entries) {
    eligible(i) := io.valid(i) && io.src1Ready(i) && io.src2Ready(i)
  }

  val mask = eligible.asUInt
  io.found := mask.orR
  io.index := Mux(io.found, PriorityEncoder(mask), 0.U)
}
```

`Vec.asUInt` 将第 0 项放在低位；`PriorityEncoder` 优先最低的置位位置。所有条件并行计算，再选一个编号。全无就绪项时 `index` 被设成 0，但**0 只有在 `found` 为真时才能作为有效选择使用**。参见 [PriorityEncoder API](https://www.chisel-lang.org/api/latest/chisel3/util/PriorityEncoder$.html)。

这是固定下标优先，不是最老指令优先，也不保证公平；持续就绪的低编号可能饿死高编号。若要双发射，第二路还要排除已选项、检查执行单元类型和端口冲突，不能简单复制两个相同的选择器。

<a id="s15"></a>
## 15. C++ 使用者最常踩的坑

| 直觉或错误写法 | 实际问题 | 记住什么 |
| --- | --- | --- |
| `val` 的硬件值不能变 | 混淆名字绑定与寄存器内容 | `val` 固定对象，`Reg` 内容可更新 |
| `var count = 0` 表示计数器 | 只是生成器变量 | 跨周期状态用 `Reg` |
| `UInt(32.W)` 就创建了信号 | 只是硬件类型描述 | 用 `IO` / `Wire` / `Reg` 实例化 |
| 用 `=` 给硬件赋值 | 改 Scala 绑定或直接编译失败 | 硬件连接用 `:=` |
| 用 `==` 比较两个端口 | 没有构造所需的硬件相等比较 | 用 `===` / `=/=` |
| `if (io.enable)` | 硬件 Bool 不是 Scala Boolean | 运行时条件用 `when` |
| `Wire` 某分支不赋值就是保持 | 导线没有记忆 | 完整驱动，或改用明确的寄存器 |
| 输出更宽能保住所有进位 | 进位可能在表达式阶段丢失 | 加法用 `+&` |
| `a := b; b := a` 顺序覆盖 | 寄存器同时采样旧状态 | 按当前值/下一值思考 |
| 两次 `count := count + 1.U` 加二 | 后连接覆盖，通常只加一 | 先合成增量，再更新一次 |
| `for` 自动分多个周期执行 | 生成阶段展开 | 多周期要有状态机与寄存器 |
| 每个模块天然有一拍延迟 | 组合模块没有寄存器边界 | 数 `Reg`，不要数 `Module` |
| 一份硬件函数只有一个共享单元 | 多次调用描述多处逻辑 | 共享资源需要仲裁与接口 |
| `ready` 才拉 `valid` | 双方互等时可能死锁 | 有数据就能声明 valid，fire 再交接 |
| 只看 valid 就出队 | 下游可能还没接收 | 用 fire 表示传输成功 |
| 数组读写同址自动返回新数据 | 依赖具体存储实现和协议 | 显式定义旁路/冲突行为 |
| `log2Ceil(n)` 能当 0..n 的计数宽度 | n 为 2 的幂时少一位 | 表示占用数 0..n 用 `log2Ceil(n + 1)` |
| 任意深度队列都靠自然溢出回卷 | 非 2 的幂时会走进非法下标 | 在 `depth - 1` 显式回到 0 |

调试时还有一个很有效的习惯：为每个信号标上“位宽、是否有符号、组合/寄存器、哪拍有效”。很多“语法问题”实际是这四件事没想清楚。

<a id="s16"></a>
## 16. 练习、参考答案与一页速查

### 16.1 五道题：先自己写，再展开答案

**练习 A：保留进位的加法器。** 两个 16 位输入，输出低 16 位和单独的进位。测试 `65535 + 1`。

<details>
<summary>参考答案：模块内部核心逻辑</summary>

```scala
val sum = io.a +& io.b
io.low := sum(15, 0)
io.carry := sum(16)
```

端口为两个 16 位 `UInt` 输入、16 位 `UInt` 输出 `low` 和 `Bool` 输出 `carry`。期望 `low=0, carry=true`。

</details>

**练习 B：8 位饱和计数器。** 使能时加一，到 255 就保持；清零优先。和自然回卷计数器有什么区别？

<details>
<summary>参考答案：模块内部核心逻辑</summary>

```scala
val count = RegInit(0.U(8.W))
when (io.clear) {
  count := 0.U
}.elsewhen (io.enable && (count =/= 255.U)) {
  count := count + 1.U
}
io.value := count
```

255 时不写寄存器，于是保持；自然回卷会从 255 变成 0。边界测试至少覆盖 254→255→255 和清零。

</details>

**练习 C：两级延迟。** 用两个寄存器传递输入，解释为什么第二级拿不到第一级“刚写的新值”。

<details>
<summary>参考答案：同时采样</summary>

```scala
val stage1 = RegNext(io.in, 0.U)
val stage2 = RegNext(stage1, 0.U)
io.out := stage2
```

同一边沿，第二级采样的是边沿前第一级的输出，新输入要经过两次采样才能到输出。用不同的连续输入，比如 11、22、33，逐拍记录两个寄存器，比重复输入同一个值更容易看出错误。

</details>

**练习 D：握手缓冲。** 用 `Elastic1` 接收 42，让下游连续三拍 `ready=0`，再放行。验证不会丢失、不会重复发送，并测试最后一拍能同时接收 99。

<details>
<summary>参考答案：该检查哪些条件</summary>

- 满且下游不 ready 时，上游看到 `in.ready=false`。
- 停顿期间 `out.valid=true`，`out.bits=42` 始终保持。
- 统计输入和输出 `fire` 次数，不能把每拍 `valid=true` 都算作一次发送。
- 同拍替换后，输出变为 99 且有效位仍为真；再放行一次后为空。

可从第 13 节测试修改停顿长度，并用软件队列记录每次输入交接的数据、逐项比对输出交接。

</details>

**练习 E：10 项环形队列的指针与占用数。** 指针和计数各需要几位？满状态下计数等于多少？如何回卷？

<details>
<summary>参考答案：编码范围比变量名字重要</summary>

指针表示 0..9，需要 `log2Ceil(10)=4` 位。占用数表示 0..10，需要 `log2Ceil(11)=4` 位；满状态是 10。此例两者恰好相同，但深度 16 时，指针是 4 位，占用数是 5 位。

```scala
def nextPointer(ptr: UInt): UInt = {
  Mux(ptr === 9.U, 0.U, ptr + 1.U)
}
```

不能让 4 位指针自然走到 15 再回卷。若同拍入队与出队都成功，占用数应保持，而不是用两条相互覆盖的赋值分别加一和减一。

</details>

### 16.2 十条记忆规律

| 规律 | 看到它就想到 |
| --- | --- |
| **Scala 造结构，Chisel 描行为** | 分清生成时与运行时 |
| **一个 `=` 起名，`:=` 接线，`===` 比较** | 不照搬 C++ 的赋值与相等 |
| **U 无符号，S 有符号，B 布尔，W 宽度** | `值.U(宽度.W)` |
| **Wire 不记，Reg 才记** | 是否跨周期保持 |
| **Init 复位，Next 下一拍，Enable 看使能** | `Reg` 家族按后缀记 |
| **参数用 if，信号用 when，选值用 Mux** | 三种条件表达的边界 |
| **切片高在前，拼接高在左** | `x(31, 20)`、`Cat(high, low)` |
| **要进位用 +&** | 运算位宽先于输出位宽 |
| **for 展开资源，Reg 划分周期** | 循环与时间没有自动对应关系 |
| **valid 有、ready 能收、fire 才交接** | 停顿、出入队和事务计数 |

### 16.3 下一步如何接回 ParaRisc

学完以后，先独立写并验证 `ALU → 寄存器堆 → 有效位流水寄存器 → 请求/响应接口`。每完成一个模块，都给出端口类型、时序表和边界测试，再继续接入译码、PC、RS/ROB 等结构。

已有的 C++ 模拟器可继续承担参考模型的角色，但其函数调用次数、容器查询和循环顺序不会自动变成正确的硬件结构。接下来阅读 [模拟器迁移文档](../research/simulator-to-chisel-design.md)，把本文的寄存器、连接、仲裁和握手概念逐项对应到项目模块。

本文中的代码是教学示例；文档编写时核对了官方 API 与时序语义，但当前仓库尚无 Scala/sbt 工程，本文示例未在本地完成编译或仿真。实际练习时请先用第 2、13 节打通生成和测试，再扩展到 CPU。
