# Adder32

实现位于 `src/pararisc/Adder.scala`，对应 CPU 仓库冻结的
`single-issue-chisel/reports/area-recovery-2026-10-21/source/Adder.scala`。
保留接口、输出位宽、组合功能和层次保留属性，适配包名、当前 Chisel 注解 API
及本工程排版。组内求和使用普通 `+`，等价关系见下文。

| 端口 | 方向 | 含义 |
| --- | --- | --- |
| `a` | 输入，32 位 | 第一个加数 |
| `b` | 输入，32 位 | 第二个加数 |
| `cin` | 输入，1 位 | 最低位的输入进位 |
| `sum` | 输出，32 位 | `(a + b + cin)` 的低 32 位 |

调用者提供两个加数和输入进位，并使用 `sum` 作为计算结果。
接入 Execute 后，输入来自其选择的操作数；减法时第二个加数取反，进位置 1。
需要地址计算等加法时，也可以在使用处直接实例化并连接 Adder32。

模块没有寄存器，也不输出第 33 位进位。`0xffffffff + 1` 的结果为 0。
减法由调用者将第二个输入取反，并令 `cin = true`，实现 `a + ~b + 1`。

例如 `a = 10`、`b = 3`、`cin = false` 时，`sum = 13`；
相同的两个加数配合 `cin = true`，结果为 14。

## 进位结构

每一位先计算 `propagate = a ^ b` 和 `generate = a & b`。
`generate` 表示该位自行产生进位，`propagate` 表示该位能传递输入进位。
随后将 32 位分成 8 个四位组，计算各组的传播和产生信号。

`CarryPrefix` 将相邻高、低范围的信号合并：

| 端口 | 方向、位宽 | 含义 |
| --- | --- | --- |
| `gh` | 输入，1 位 | 高位范围自行产生进位的信号 |
| `ph` | 输入，1 位 | 高位范围可以完整传播进位的信号 |
| `gl` | 输入，1 位 | 低位范围自行产生进位的信号 |
| `pl` | 输入，1 位 | 低位范围可以完整传播进位的信号 |
| `g` | 输出，1 位 | 合并范围自行产生进位的信号 |
| `p` | 输出，1 位 | 合并范围可以完整传播进位的信号 |

输入来自 Adder32 当前前缀层的两个相邻范围，输出交给下一层或组输入进位计算。

```text
g = gh || (ph && gl)
p = ph && pl
```

三层前缀分别跨越 1、2、4 个组，得到各组之前的累计进位信息。
各组使用自己的输入进位完成四位加法，最后按高位到低位拼成 `sum`。
源码中的 Scala 循环在生成电路时展开，运算期间不存在循环次数对应的等待周期。

组内加法使用普通 `+`，最终只取低四位。组间进位已经由前缀逻辑单独计算，
因此局部求和无需额外保留输出进位；其低四位结果与 `+&` 扩位后再截取相同。

`Adder32` 和 `CarryPrefix` 继承 `pararisc.LocalLogic`，通过依赖内置的
`firrtl.AttributeAnnotation` 保留 v110 的 `keep_hierarchy = 1` 属性。
当前前端的 `pararisc.fetch.LocalLogic` 继续服务前端模块。

## 调用与生成

在使用处通过 `Module(new Adder32)` 创建加法器，直接连接 `io.a`、`io.b`、
`io.cin`，从 `io.sum` 读取结果。普通加法将 `cin` 接 0；减法将第二个输入
取反并将 `cin` 接 1。每个实例对应一份加法电路。
Execute 已完成独立执行模块，直接实例化三个 Adder32，分别处理 ALU 加减法、
基址加偏移和 PC 加偏移，见 [Execute 说明](execute.md)。尚未接入完整 CPU。

```sh
mill --no-server chisel.compile
make adder
```

RTL 位于 `build/adder/rtl/`，编译仿真时使用其中的 `filelist.f`，同时包含
`Adder32.sv` 和生成的子模块。也可用 `--module adder --out <目录>` 指定输出位置。

测试源文件和日志放在已被 Git 忽略的 `build/adder/` 中，测试文件单独位于
`build/adder/tests/`。验证结论以该目录的实际运行日志为准；生成 RTL 不代表
Execute 或完整 CPU 已通过验证，也不证明加法器的实际时序收益。

2026-10-22 的本次验证：

- `mill --no-server chisel.compile` 和 `make adder` 通过。
- Verilator 对当前生成的 RTL 完成 30,203 次检查，结果为
  `PASS: 30203 adder checks`，当前普通 `+` 实现的最新日志为
  `build/execute-add/adder-test.log`。
- 用例包括零值、两种输入进位、每一位和四位组边界的进位、溢出回绕、
  以取反加一实现的减法，以及固定种子的 10,000 对随机输入。
- 生成的 `Adder32.sv` 和 `CarryPrefix.sv` 均保留 `keep_hierarchy = 1`。

测试源文件保留在本机时，可这样重新构建并运行：

```sh
cd build/adder/rtl
verilator --cc --exe --build -j 2 --top-module Adder32 \
  --Mdir ../obj -f filelist.f ../tests/adder_test.cpp -CFLAGS '-std=c++17'
../obj/VAdder32
```
