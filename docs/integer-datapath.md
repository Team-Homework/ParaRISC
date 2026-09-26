# 整数执行通路：从 RecursiveCLA32 开始

源码位于 [`chisel/src/RecursiveCLA32.scala`](../chisel/src/RecursiveCLA32.scala) 与 [`chisel/src/IntegerDatapath.scala`](../chisel/src/IntegerDatapath.scala)。模块包名与课程模板一致，均为 `cpu2026`。加法器保留所给的递归分组与进位计算方式；每实例是一个组合 32 位加法器，没有流水寄存器。

| 模块 | 输入 | 输出 | 加法器的用途 |
| --- | --- | --- | --- |
| `IntegerAlu` | `a, b, op` | `result, equal, lessSigned, lessUnsigned` | 一个 CLA 复用作加法、`a + ~b + 1` 减法与大小比较 |
| `BranchUnit` | `pc, a, b, offset, op` | `taken, nextPc, link, targetMisaligned` | 比较器中的减法、目标地址、`pc+4`；JALR 清除目标最低位 |
| `AddressGenerator` | `base, offset, storeData, size` | 有效地址、字对齐地址、字节位置、掩码、移位数据、对齐错误 | `rs1 +` 已扩展的 I/S 立即数 |

译码器负责把 RV32I 的 opcode/funct 字段转换为 `AluOp`、`BranchOp` 或 `AccessSize`，并把立即数扩展到 32 位。`IntegerAlu.b` 是调用者选好的 `rs2` 或立即数；`LUI` 用 `COPY_B`，`AUIPC` 用 `ADD` 且 `a=pc`。`SLT/SLTU` 的比较由同一个减法器的符号与进位给出。比较输出仅在 `SUB/SLT/SLTU` 操作时有效；分支单元以 `SLT` 模式使用它们。移位只看 `b[4:0]`。

`BranchUnit` 是实际控制流运算，没有预测表。调用者应保存预测结果，与 `taken/nextPc` 比较后决定重定向。条件分支不成立时 `nextPc=pc+4`；JAL/JALR 的 `link=pc+4`。RV32IM 无压缩指令，采用跳转目标时若低两位非零，应处理 `targetMisaligned`。JALR 的 bit 0 按 ISA 清零，再检查对齐。模块没有 `valid`，上游发射和下游结果保持由流水线控制。

`AddressGenerator` 输出未经截断的有效地址和供课程 AXI4-Lite 使用的字对齐地址。SB/SH/SW 的 `writeMask` 与 `shiftedStoreData` 采用小端字节位置；HALF 要求 bit 0 为零，WORD 要求低两位为零。`size=3` 时 `sizeSupported=false`，上游不得向内存发请求。该模块不处理 load 返回数据、LSQ 顺序、store 提交或总线握手；这些需要在 LSU 与内存接口中连接。

当前 `StudentTop` 仍是课程提供的空顶层。接入整机时，RS 向 ALU/Branch 提供操作数，AGU 接 LSU 的 base/offset/store data；只有被执行单元接受的请求才可从 RS/LSQ 删除。多条执行结果同拍到达时还需写回仲裁和结果保持。资源预算与跨模块接口见[端口预算](planning/port-budget-and-ipc.md)。

建议首先验证加法进位链（`0xffffffff+1`、cin、随机 32 位加法），再验证减法借位和异号 `SLT/SLTU`，随后验证六种条件分支、JAL/JALR 目标与链接地址，以及 SB/SH/SW 在每个合法字节位置的掩码和数据。功能通过后再测综合面积与关键路径；递归 CLA 的逻辑深度优势需要时序报告确认。

可用 `mill chisel.runMain cpu2026.GenerateDatapath alu build/datapath/alu` 单独生成 ALU；将 `alu` 改为 `adder`、`branch`、`agu` 可生成其他模块。`tests/rtl/` 下有三个 Verilator 测试平台，分别对应生成的 `IntegerAlu.sv`、`BranchUnit.sv`、`AddressGenerator.sv`。例如：

```sh
mill chisel.runMain cpu2026.GenerateDatapath alu build/datapath/alu
verilator --binary --timing --top-module integer_alu_tb --Mdir build/datapath/alu/obj \
  build/datapath/alu/IntegerAlu.sv tests/rtl/integer_alu_tb.sv
build/datapath/alu/obj/Vinteger_alu_tb
```

同理生成 `branch`、`agu` 后，分别以 `branch_unit_tb`、`address_generator_tb` 为顶层运行对应测试。课程的完整 `student_top` 仍需另行连接这些执行模块；单元测试通过只说明叶模块功能通过。
