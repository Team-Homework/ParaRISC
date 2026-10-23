# Decoder（v110）

实现位于 `src/pararisc/Decode.scala`，参照 CPU 仓库
`single-issue-chisel/reports/area-recovery-2026-10-21/source/Decode.scala`
中的 `Decoded` 和 `Decoder`。保留字段的名字、位宽、声明顺序及译码行为，
包名使用本工程的 `pararisc`。符号扩展函数位于 `Common.scala` 的 `Util.sext`。

本工程基线已从 v109 切换到 v110。核对冻结归档后，两版 `Decode.scala`、
`Common.scala` 及提取的 Decoder RTL 均逐字节一致，因此译码逻辑无需修改。

Decoder 是组合逻辑：输入 `io.inst`，输出 `io.d`。它没有状态或流水线寄存器。

全部作业指令的格式、编码及简短语义见[指令速查](instructions.md)。

| 字段 | 使用方式 |
| --- | --- |
| `rs1 / rs2 / rd` | 从指令固定位置提取的寄存器编号 |
| `use1 / use2` | 是否使用对应源操作数，供依赖判断使用 |
| `writes` | 指令是否具有写目的寄存器的行为；Core 负责屏蔽 x0 的实际写回 |
| `imm` | I/S/B/J 符号扩展或 U 型拼接后的 32 位立即数 |
| `fn` | 通常为 funct3；AUIPC 强制置 0。执行、访存与 MDU 按类别解释它 |
| `sub` | 配合 fn 区分减法和算术右移 |
| `pcA / immB / lui` | 执行操作数选择，以及 LUI 结果选择 |
| `branch / jal / jalr` | 控制流类别 |
| `load / store / muldiv` | 执行路径类别 |
| `illegal` | 供后续 Core 记录的故障标志 |

默认先清零控制信号、提取公共字段，默认立即数为 I 型，`illegal` 置真。
然后按 opcode 分类覆盖立即数和控制信号。`use1/use2` 决定是否读取寄存器，
因此立即数指令中原始 `rs2` 字段的位值不会造成假依赖。

本次保持 v110 的约定：SRAI 的 `imm` 保留完整的 I 型字段（执行时仅取低 5 位
移位量），部分保留 ALU 编码不会置 `illegal`，`0x0f` opcode 按无副作用处理。
被标为非法的指令仍可能带有 `writes/load/store` 等分类位；后续 Core 需要按
基线处理故障。本次没有将它改成更严格的 ISA 合法性检查。

```sh
make decoder  # 生成 build/decoder/rtl/Decoder.sv
```

历史验证：2026-10-21 曾通过 221 个定向用例，覆盖指令类别、源/目的寄存器、
立即数边界、移位、M 扩展、非法 funct3 和基线兼容行为；Yosys 曾对所有
32 位输入证明全部输出与 v110 归档模块一致。自定义测试脚本及参考文件已按
用户要求移除，这些结论仅对应删除前已验证的版本。原始结果和日志仍位于
本机 `build/decoder/`；清理构建目录后不会重新生成这些测试记录。

生成器也支持 `mill --no-server chisel.run --module decoder`，默认输出目录为
`build/decoder/rtl/`。默认 `make rtl` 和原有 `--smoke` 入口继续生成 `student_top`。
CPU 顶层目前仍为骨架，本次只实现独立 Decoder。
