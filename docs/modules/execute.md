# Execute

Execute 接收已经译码的控制信息和准备好的操作数，负责算术、逻辑、
地址及控制流相关计算。当前已完成普通 ALU、LUI、分支及跳转计算，
四个输出都已连接；该模块仍需由后续 Core 接入。

## 接口

| 端口 | 方向、位宽 | 含义及连接关系 |
| --- | --- | --- |
| `io.d` | 输入，Decoded，当前共 64 位 | Decoder 生成的控制信息，由 Core 随指令保存并送入 |
| `io.pc` | 输入，32 位 | 该指令的地址，由 Core 随指令保存并送入 |
| `io.a` | 输入，32 位 | 第一个源寄存器的值，由 Core 的操作数读取或转发逻辑提供 |
| `io.b` | 输入，32 位 | 第二个源寄存器的值，由 Core 的操作数读取或转发逻辑提供 |
| `io.result` | 输出，32 位 | ALU、LUI 或跳转返回地址结果，交给 Core 的写回路径 |
| `io.next` | 输出，32 位 | 实际下一条指令地址，供 Core 检查预测及处理重定向 |
| `io.taken` | 输出，1 位 | 实际是否跳转，供 Core 处理分支和跳转 |
| `io.addr` | 输出，32 位 | 原始基址加偏移的结果，内部用于 JALR，后续由 Core 按访存方案使用 |

`Decoded` 中的 `rs1/rs2` 是寄存器编号，实际数据通过 `io.a/io.b` 传入。
本步使用的控制字段为：

| 字段 | 位宽 | 作用 |
| --- | --- | --- |
| `d.pcA` | 1 | 为 1 时，第一个 ALU 操作数选 PC，否则选 `io.a` |
| `d.immB` | 1 | 为 1 时，第二个 ALU 操作数选立即数，否则选 `io.b` |
| `d.imm` | 32 | Decoder 已完成拼接和扩展的立即数 |
| `d.sub` | 1 | 加减法中控制取反和进位；右移中为 1 时选择算术右移 |
| `d.fn` | 3 | 在 ALU 中选择运算，在分支中选择比较条件 |
| `d.lui` | 1 | 将立即数直接作为 `result` |
| `d.branch` | 1 | 启用条件分支判断 |
| `d.jal` | 1 | 无条件跳转到 PC 加偏移，并返回 PC+4 |
| `d.jalr` | 1 | 无条件跳转到寄存器加偏移，并返回 PC+4 |

## 实现思路

先选择两个操作数：

```text
a = pcA  ? pc  : io.a
b = immB ? imm : io.b
```

随后直接在 Execute 中实例化 Adder32，连接三个输入并读取结果：

```scala
val adder = Module(new Adder32)
adder.io.a := a
adder.io.b := Mux(d.sub, ~b, b)
adder.io.cin := d.sub

val add = adder.io.sum
```

`d.sub` 同时控制第二个输入取反和输入进位：

```text
sub = 0：add = a + b + 0
sub = 1：add = a + ~b + 1，即 a - b
```

运算保留低 32 位，溢出按 32 位回绕。该路径没有寄存器，
不使用复位、使能或握手信号。外围负责选择有效输入并采样结果。
`alu` 是 32 位内部信号，默认使用 `add`，通过 `switch(d.fn)` 直接选择结果：

| `d.fn`（二进制） | 当前选择 | 表达式 |
| --- | --- | --- |
| `000` | 加减法 | `add` |
| `001` | 左移 | `(a << shiftAmount)(31, 0)` |
| `010` | 有符号小于比较 | `(a.asSInt < b.asSInt).asUInt` |
| `011` | 无符号小于比较 | `(a < b).asUInt` |
| `100` | 按位异或 | `a ^ b` |
| `101` | 逻辑或算术右移 | `right(31, 0)`，由 `sub` 选择补位方式 |
| `110` | 按位或 | `a \| b` |
| `111` | 按位与 | `a & b` |

寄存器形式和立即数形式复用相同表达式：`immB = 1` 时，前面的选择器已经将
`d.imm` 选入 `b`，所以立即数形式不需要另写一套运算。
按位逻辑直接使用 `a`、`b`，不受加法器输入取反控制 `d.sub` 的影响。
比较结果先转成 UInt，再零扩展到 32 位，因此整个 `alu` 的比较结果为 0 或 1。
表中按普通 ALU 指令解释 `fn`；乘除法需要由外围送往独立 MulDiv。

所有移位都使用 `shiftAmount = b(4, 0)`，即低五位指定的 0～31 位。
左移只保留低 32 位。右移使用一个 33 位有符号输入：

```text
rightInput = 将 (sub 且 a的最高位) 拼在 a 前面，再解释为有符号数
right = rightInput 算术右移 shiftAmount 位
```

`sub = 0` 时额外的符号位为 0，右移补 0，实现 SRL；
`sub = 1` 时额外的符号位复制 `a(31)`，右移保持原符号，实现 SRA。
两者共用一个右移器，结果取低 32 位。
SRAI 的 `d.imm` 保留指令的完整 I 型字段，执行时同样只取低五位作为移位量。

### 结果与控制流输出

`result` 按以下优先级选择：

| 条件 | `result` |
| --- | --- |
| `jal` 或 `jalr` | `pc + 4`，作为返回地址 |
| 否则，`lui` | `d.imm`，Decoder 已完成高位拼接 |
| 其他情况 | `alu` |

分支比较使用原始 `io.a/io.b`，而不是选过 PC 或立即数的局部 `a/b`：

| `fn`（二进制） | 分支条件 |
| --- | --- |
| `000` | 相等，BEQ |
| `001` | 不相等，BNE |
| `100` | 有符号小于，BLT |
| `101` | 有符号大于等于，BGE |
| `110` | 无符号小于，BLTU |
| `111` | 无符号大于等于，BGEU |

`010/011` 的条件默认为假。`taken = jal || jalr || (branch && cond)`。

另两个 Adder32 分别直接计算 `io.a + d.imm` 和 `io.pc + d.imm`。
前者连接到 `addr`；后者作为分支和 JAL 的目标。
加上普通 ALU 中的加法器，共有三个并行的 Adder32。
ALU 的输入进位为 `sub`，另两个为 0，三个实例保留层次属性。

| 条件 | `next` |
| --- | --- |
| `taken = 0` | `pc + 4` |
| `taken = 1` 且 `jalr = 1` | `addr` 的最低位清零 |
| 其他跳转 | `pc + imm` |

JALR 只清零最低一位，地址对齐及故障处理留给 Core。
所有通路都是组合逻辑，可以并行计算；模块没有寄存器或内部握手状态。
四个输出始终都有值，由 Core 按指令类型和有效性决定使用哪些输出。

## 简单例子

除特别注明外，控制字段为 `pcA = 0`、`immB = 0`、`sub = 0`、`fn = 000`。

| 运算意图 | 关键输入 | 内部 `alu` |
| --- | --- | --- |
| ADD | `io.a = 10`、`io.b = 3`，`pcA = 0`、`immB = 0`、`sub = 0` | 13 |
| SUB | 同上，`sub = 1` | 7 |
| ADDI | `io.a = 10`、`imm = 8`，`pcA = 0`、`immB = 1`、`sub = 0` | 18 |
| AUIPC | `pc = 0x1000`、`imm = 0x2000`，`pcA = 1`、`immB = 1`、`sub = 0` | `0x3000` |
| XOR | `io.a = 12`、`io.b = 10`，`immB = 0`、`fn = 100` | 6 |
| OR | 同上，`fn = 110` | 14 |
| AND | 同上，`fn = 111` | 8 |
| XORI | `io.a = 12`、`imm = 10`，`immB = 1`、`fn = 100` | 6 |
| SLT | `io.a = 0xffffffff`（-1）、`io.b = 1`，`fn = 010` | 1 |
| SLTU | 相同位模式，`fn = 011`，按无符号解释 | 0 |
| SLL | `io.a = 1`、`io.b = 31`，`fn = 001` | `0x80000000` |
| SRL | `io.a = 0x80000000`、`io.b = 1`，`fn = 101` | `0x40000000` |
| SRA | 相同输入，`fn = 101`、`sub = 1` | `0xc0000000` |
| SRAI | `io.a = 0x80000000`、`imm = 0x401`，`immB = 1`、`fn = 101`、`sub = 1` | `0xc0000000` |

控制流示例均使用 `pc = 0x1000`：

| 指令或控制 | 关键输入 | 输出 |
| --- | --- | --- |
| LUI | `imm = 0x12345000` | `result = 0x12345000`，`taken = 0`，`next = 0x1004` |
| BEQ 成立 | `io.a = io.b = 5`，`imm = 0x20` | `taken = 1`，`next = 0x1020` |
| BEQ 不成立 | `io.a = 5`、`io.b = 7`，`imm = 0x20` | `taken = 0`，`next = 0x1004` |
| JAL | `imm = 0x100` | `result = 0x1004`，`taken = 1`，`next = 0x1100` |
| JALR | `io.a = 0x2000`、`imm = 5` | `result = 0x1004`，`addr = 0x2005`，`next = 0x2004` |

Load 的返回数据来自访存模块，乘除结果来自 MulDiv；实际访存、写回归属、
x0 屏蔽、故障和按序提交由 Core 等外围模块负责。

## 当前完成情况

Execute 独立组合模块已完成，可以直接生成 RTL。尚未接入 Core，
本次验证不代表完整 CPU 的正确性、面积、IPC 或 Fmax 已通过验收。

```sh
mill --no-server chisel.compile
make execute
```

也可运行 `mill --no-server chisel.run --module execute --out <目录>`。
默认 RTL 位于 `build/execute/rtl/`，仿真或综合使用其中的 `filelist.f`。
本步测试直接使用实际 Execute 的四个输出，不再依赖测试子类补齐输出。
测试文件位于已被 Git 忽略的 `build/execute/tests/`，日志位于 `build/execute/`。

2026-10-22 的本次验证：

- 源码编译和 `make execute` 通过，三个 Adder32 及其层次保留属性已检查。
- Verilator 的 138 个四输出用例通过，包含原有 96 个 ALU 用例，以及六种分支、
  LUI、JAL、JALR、负偏移、地址回绕和最低位清零等边界情况。
- 使用 Yosys SAT 与冻结参考 RTL 对照，所有实际参与计算的 138 个输入位组合下，
  四个输出共 97 位均等价。记录为 `equivalence.log` 和 `equivalence/result.json`。
- 参考模块的 `addr` 端口在归档综合时被裁剪，测试副本仅导出已有内部地址线；
  原始冻结 RTL 未修改。未使用的寄存器编号和控制字段不影响本模块输出。
- Decoder、Adder32、普通顶层及环境自检顶层的生成和 RTL 语法检查通过，
  记录为 `generator-regression.json`。
- 源码编译、RTL 生成、仿真构建和用例结果日志分别为该目录下的
  `compile.log`、`generate.log`、`verilator-build.log` 和 `test.log`。

加法器本体的 30,203 次独立检查结果仍保存在 `build/execute-add/adder-test.log`。
此前移除接线辅助函数时的 RTL 对照记录位于
`build/execute-add-simplify/rtl-comparison.json`，仅对应当时的加减法路径。
