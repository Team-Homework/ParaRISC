# 作业指令速查

按[课程要求](https://github.com/ACMClassCourse-2025/RISC-V-CPU-2026#architecture--project-requirements)
整理，共 **45 条：RV32I 37 条 + M 扩展 8 条**。CSR、FENCE、FENCE.I、ECALL、EBREAK 不要求。
所有指令均为 32 位；访存采用小端序，只要求自然对齐访问。

编码核对官方 [RV32I 通用编码](https://github.com/riscv/riscv-opcodes/blob/master/extensions/rv_i)、
[RV32I 移位编码](https://github.com/riscv/riscv-opcodes/blob/master/extensions/rv32_i)和
[M 扩展编码](https://github.com/riscv/riscv-opcodes/blob/master/extensions/rv_m)。
以下清单表示实现要求，不表示整机已经支持全部指令。

## 1. 表格记号

| 记号 | 含义 |
| --- | --- |
| `A`、`B`、`D` | `R[rs1]`、`R[rs2]`、`R[rd]`，即寄存器中的值 |
| `s(x)`、`u(x)` | 将同一 32 位值解释为有符号数、无符号数 |
| `imm` | 按指令格式拼接、扩展后的 32 位立即数，即 `d.imm` |
| `imm20` | 汇编中 LUI/AUIPC 的 20 位立即数字段；生成 `imm` 时低位补 12 个 0 |
| `sh`、`shamt` | `B[4:0]`、`inst[24:20]`，取值 0～31 |
| `M8/16/32[addr]` | 从字节地址 `addr` 开始的 8/16/32 位内存数据 |
| `sext(x)`、`zext(x)` | 符号扩展、零扩展到 32 位 |
| `lo32(x)`、`hi32(x)` | 取结果低 32 位、64 位乘积的高 32 位 |

`PC` 是当前指令地址，`next` 是实际下一条指令地址；通常 `next = PC + 4`。
写寄存器和地址运算保留低 32 位；`x0` 恒为 0，写入丢弃。比较结果为 0 或 1。
下文 `xor/or/and` 均为按位运算；`u(x) >> n` 补 0，`s(x) >> n` 补符号位。

## 2. 六种指令格式

下表字段从 `inst[31]` 到 `inst[0]` 排列；`imm[...]` 表示立即数中的位号。
格式和语义依据 [RV32I 规范](https://docs.riscv.org/reference/isa/v20260120/unpriv/rv32.html)。

| 格式 | 从高位到低位的字段 |
| --- | --- |
| R | `funct7 · rs2 · rs1 · funct3 · rd · opcode` |
| I | `imm[11:0] · rs1 · funct3 · rd · opcode` |
| S | `imm[11:5] · rs2 · rs1 · funct3 · imm[4:0] · opcode` |
| B | `imm[12] · imm[10:5] · rs2 · rs1 · funct3 · imm[4:1] · imm[11] · opcode` |
| U | `imm[31:12] · rd · opcode` |
| J | `imm[20] · imm[10:1] · imm[11] · imm[19:12] · rd · opcode` |

| 字段 | 指令位段 | 含义 |
| --- | --- | --- |
| opcode | `inst[6:0]` | 指令大类 |
| rd | `inst[11:7]` | 目标寄存器编号，5 位 |
| funct3 | `inst[14:12]` | 类内运算选择，3 位 |
| rs1 | `inst[19:15]` | 第一源寄存器编号，5 位 |
| rs2 | `inst[24:20]` | 第二源寄存器编号，5 位 |
| funct7 | `inst[31:25]` | R 型或 I 型移位的进一步区分，7 位 |

字段只在对应格式中有该含义，例如 I 型的 `inst[24:20]` 属于立即数。

### 立即数解码

`{...}` 表示按高位到低位拼接。

| 格式 | 解码后的 `imm` |
| --- | --- |
| R | 不使用立即数 |
| I | `sext(inst[31:20])` |
| S | `sext({inst[31:25], inst[11:7]})` |
| B | `sext({inst[31], inst[7], inst[30:25], inst[11:8], 0})` |
| U | `{inst[31:12], 12'b0}` |
| J | `sext({inst[31], inst[19:12], inst[20], inst[30:21], 0})` |

B/J 的 `imm` 已包含末尾的 0，执行时直接 `PC + imm`。U 型也已补零，不再左移。
I 型移位仅使用 `shamt`；其他 I 型立即数均符号扩展，包括 SLTIU 和逻辑立即数。

## 3. 指令清单

opcode 用十六进制，funct3/funct7 用二进制。`—` 表示该位段不参与此条指令的识别。
表中列出标准合法编码；先看 opcode，再按需检查 funct3、funct7。

### 寄存器运算（10 条）

| 汇编格式 | 类型 | opcode | funct3 | funct7 | 简短语义 |
| --- | --- | --- | --- | --- | --- |
| `ADD rd, rs1, rs2` | R | `0x33` | `000` | `0000000` | `D ← A + B` |
| `SUB rd, rs1, rs2` | R | `0x33` | `000` | `0100000` | `D ← A - B` |
| `SLL rd, rs1, rs2` | R | `0x33` | `001` | `0000000` | `D ← A << sh` |
| `SLT rd, rs1, rs2` | R | `0x33` | `010` | `0000000` | `D ← (s(A) < s(B))` |
| `SLTU rd, rs1, rs2` | R | `0x33` | `011` | `0000000` | `D ← (u(A) < u(B))` |
| `XOR rd, rs1, rs2` | R | `0x33` | `100` | `0000000` | `D ← A xor B` |
| `SRL rd, rs1, rs2` | R | `0x33` | `101` | `0000000` | `D ← u(A) >> sh` |
| `SRA rd, rs1, rs2` | R | `0x33` | `101` | `0100000` | `D ← s(A) >> sh` |
| `OR rd, rs1, rs2` | R | `0x33` | `110` | `0000000` | `D ← A or B` |
| `AND rd, rs1, rs2` | R | `0x33` | `111` | `0000000` | `D ← A and B` |

### 立即数运算（9 条）

| 汇编格式 | 类型 | opcode | funct3 | funct7 | 简短语义 |
| --- | --- | --- | --- | --- | --- |
| `ADDI rd, rs1, imm` | I | `0x13` | `000` | — | `D ← A + imm` |
| `SLTI rd, rs1, imm` | I | `0x13` | `010` | — | `D ← (s(A) < s(imm))` |
| `SLTIU rd, rs1, imm` | I | `0x13` | `011` | — | `D ← (u(A) < u(imm))` |
| `XORI rd, rs1, imm` | I | `0x13` | `100` | — | `D ← A xor imm` |
| `ORI rd, rs1, imm` | I | `0x13` | `110` | — | `D ← A or imm` |
| `ANDI rd, rs1, imm` | I | `0x13` | `111` | — | `D ← A and imm` |
| `SLLI rd, rs1, shamt` | I | `0x13` | `001` | `0000000` | `D ← A << shamt` |
| `SRLI rd, rs1, shamt` | I | `0x13` | `101` | `0000000` | `D ← u(A) >> shamt` |
| `SRAI rd, rs1, shamt` | I | `0x13` | `101` | `0100000` | `D ← s(A) >> shamt` |

### 高位立即数（2 条）

| 汇编格式 | 类型 | opcode | funct3 | funct7 | 简短语义 |
| --- | --- | --- | --- | --- | --- |
| `LUI rd, imm20` | U | `0x37` | — | — | `D ← imm` |
| `AUIPC rd, imm20` | U | `0x17` | — | — | `D ← PC + imm` |

### 条件分支（6 条）

语义列是跳转条件：成立时 `next = PC + imm`，否则 `next = PC + 4`，均不写 rd。
汇编中 `offset` 表示相对当前 PC 的字节偏移，可用目标标签表示。

| 汇编格式 | 类型 | opcode | funct3 | funct7 | 简短语义 |
| --- | --- | --- | --- | --- | --- |
| `BEQ rs1, rs2, offset` | B | `0x63` | `000` | — | `A = B` |
| `BNE rs1, rs2, offset` | B | `0x63` | `001` | — | `A ≠ B` |
| `BLT rs1, rs2, offset` | B | `0x63` | `100` | — | `s(A) < s(B)` |
| `BGE rs1, rs2, offset` | B | `0x63` | `101` | — | `s(A) ≥ s(B)` |
| `BLTU rs1, rs2, offset` | B | `0x63` | `110` | — | `u(A) < u(B)` |
| `BGEU rs1, rs2, offset` | B | `0x63` | `111` | — | `u(A) ≥ u(B)` |

### 无条件跳转（2 条）

| 汇编格式 | 类型 | opcode | funct3 | funct7 | 简短语义 |
| --- | --- | --- | --- | --- | --- |
| `JAL rd, offset` | J | `0x6f` | — | — | `D ← PC + 4`；`next ← PC + imm` |
| `JALR rd, imm(rs1)` | I | `0x67` | `000` | — | `D ← PC + 4`；`next ← (A + imm) & 0xfffffffe` |

`PC + 4` 是返回地址。JALR 只清零目标的 bit 0；RV32IM 指令地址仍要求 4 字节对齐。

### 读内存（5 条）

下表及写内存表均使用 `addr = A + imm`。

| 汇编格式 | 类型 | opcode | funct3 | funct7 | 简短语义 |
| --- | --- | --- | --- | --- | --- |
| `LB rd, imm(rs1)` | I | `0x03` | `000` | — | `D ← sext(M8[addr])` |
| `LH rd, imm(rs1)` | I | `0x03` | `001` | — | `D ← sext(M16[addr])` |
| `LW rd, imm(rs1)` | I | `0x03` | `010` | — | `D ← M32[addr]` |
| `LBU rd, imm(rs1)` | I | `0x03` | `100` | — | `D ← zext(M8[addr])` |
| `LHU rd, imm(rs1)` | I | `0x03` | `101` | — | `D ← zext(M16[addr])` |

### 写内存（3 条）

| 汇编格式 | 类型 | opcode | funct3 | funct7 | 简短语义 |
| --- | --- | --- | --- | --- | --- |
| `SB rs2, imm(rs1)` | S | `0x23` | `000` | — | `M8[addr] ← B[7:0]` |
| `SH rs2, imm(rs1)` | S | `0x23` | `001` | — | `M16[addr] ← B[15:0]` |
| `SW rs2, imm(rs1)` | S | `0x23` | `010` | — | `M32[addr] ← B` |

### 乘除法（8 条）

语义依据 [M 扩展规范](https://docs.riscv.org/reference/isa/v20260120/unpriv/m-st-ext.html)。
有符号除法向零取整；`%` 表示相应余数，非零有符号余数的符号与 A 相同。

| 汇编格式 | 类型 | opcode | funct3 | funct7 | 简短语义 |
| --- | --- | --- | --- | --- | --- |
| `MUL rd, rs1, rs2` | R | `0x33` | `000` | `0000001` | `D ← lo32(A × B)` |
| `MULH rd, rs1, rs2` | R | `0x33` | `001` | `0000001` | `D ← hi32(s(A) × s(B))` |
| `MULHSU rd, rs1, rs2` | R | `0x33` | `010` | `0000001` | `D ← hi32(s(A) × u(B))` |
| `MULHU rd, rs1, rs2` | R | `0x33` | `011` | `0000001` | `D ← hi32(u(A) × u(B))` |
| `DIV rd, rs1, rs2` | R | `0x33` | `100` | `0000001` | `D ← s(A) / s(B)` |
| `DIVU rd, rs1, rs2` | R | `0x33` | `101` | `0000001` | `D ← u(A) / u(B)` |
| `REM rd, rs1, rs2` | R | `0x33` | `110` | `0000001` | `D ← s(A) % s(B)` |
| `REMU rd, rs1, rs2` | R | `0x33` | `111` | `0000001` | `D ← u(A) % u(B)` |

| 除法边界 | 商 | 余数 |
| --- | --- | --- |
| B 为 0，有符号或无符号 | `0xffffffff` | A |
| 有符号 `0x80000000 / 0xffffffff`，即最小负数除以 -1 | `0x80000000` | 0 |

## 4. 对应当前 Decoder

实现位于 [Decode.scala](../src/pararisc/Decode.scala)，详细接口见 [Decoder 设计](decoder.md)。
下表仅列置 1 的布尔控制信号，其余默认 0；`sub` 和 `fn` 单独说明。
所有表中合法指令的 `illegal = 0`。

| 指令类 | opcode | 置 1 的控制信号 | `fn` / `sub` | 后续处理 |
| --- | --- | --- | --- | --- |
| 寄存器 ALU | `0x33`，funct7 非 `0000001` | `use1, use2, writes` | `fn=funct3`；`sub=inst[30]` | Execute |
| 立即数 ALU | `0x13` | `use1, immB, writes` | `fn=funct3`；`sub=inst[30] && funct3==101` | Execute |
| LUI | `0x37` | `lui, writes` | `fn` 无关；`sub=0` | Execute 直接输出 imm |
| AUIPC | `0x17` | `pcA, immB, writes` | `fn=000`；`sub=0` | Execute 的 PC 加 imm |
| 条件分支 | `0x63` | `branch, use1, use2` | `fn=funct3`；`sub=0` | Execute 比较并计算目标 |
| JAL | `0x6f` | `jal, writes` | `fn` 无关；`sub=0` | Execute |
| JALR | `0x67` | `jalr, use1, writes` | `fn=000`；`sub=0` | Execute |
| Load | `0x03` | `load, use1, writes` | `fn=funct3`；`sub=0` | 地址计算、访存、数据扩展 |
| Store | `0x23` | `store, use1, use2` | `fn=funct3`；`sub=0` | 地址计算、提交后写内存 |
| M 扩展 | `0x33`，funct7=`0000001` | `muldiv, use1, use2, writes` | `fn=funct3`；`sub=0` | MulDiv |

`rs1/rs2` 是编号；实际 A/B 由 Core 的操作数读取或转发逻辑提供。
Load/Store/JALR 的 `immB=0`：有效地址使用独立的 `A + imm` 路径。
`writes` 表示写寄存器意图，实际写回仍需 Core 检查有效性并屏蔽 x0。

当前 Decoder 对 `0x13/0x33` 的部分保留编码检查较宽松；`0x0f` 按空操作处理。
这些是当前实现行为，不扩充上面的标准指令清单。

## 5. 常见伪指令

伪指令由汇编器展开，不增加硬件指令种类。

| 汇编写法 | 对应基础指令 |
| --- | --- |
| `nop` | `ADDI x0, x0, 0` |
| `mv rd, rs` | `ADDI rd, rs, 0` |
| `j offset` | `JAL x0, offset` |
| `ret` | `JALR x0, 0(x1)` |

执行接口见 [Execute 设计](modules/execute.md)。
