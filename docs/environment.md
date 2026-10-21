# ParaRISC 本地 Chisel 开发环境

已在 Ubuntu 24.04 / WSL2 x86-64 上配置。Chisel 工程使用本机 JDK 21、
Mill 1.1.2、Scala 2.13.18、Chisel 7.7.0 和配套 firtool 1.139.0。
版本由 `.mill-version` 和 `build.mill` 固定；依赖已通过实际生成过程验证。
构建方式参考 [Chisel 官方安装指南](https://www.chisel-lang.org/docs/installation)。

课程工具包位于项目根目录 `cpu2026-tools-x86_64.AppImage`，包含：

- Verilator 5.020
- Yosys 0.63 和 ABC（课程固定提交）
- OpenSTA 3.1.0（课程固定提交）
- ASAP7 7.5-track r28 RVT TT 的五个 NLDM 单元库

工具包从本机已有课程环境复制，SHA-256：

```text
49d9180387701ab2d35d0f706b153dbf06ac81defd22c39e010cd9c7a2020fa6
```

WSL 没有 `/dev/fuse`，因此 `config.mk` 已启用
`APPIMAGE_EXTRACT_AND_RUN=1`。无需安装 Docker 或修改全局 shell 配置。
直接运行工具包时也需要该环境变量，例如：

```sh
APPIMAGE_EXTRACT_AND_RUN=1 ./cpu2026-tools-x86_64.AppImage --version
```

## 开发入口

在项目根目录运行：

```sh
make rtl          # Chisel -> verilog/generated/student_top.sv
make build        # 自动生成 RTL，再构建 build/sim
make              # 自动生成 RTL，并生成 OJ 提交需要的 ./code
make env-check    # 独立验证完整工具链，产物在 build/env-check/
```

`src/pararisc/StudentTop.scala` 提供了符合课程要求的平铺 AXI4-Lite 端口和
空闲输出，是待实现的 CPU 骨架。它还不能执行 RV32IM 程序。
在此基础上添加 CPU 模块后，可运行：

```sh
make test Case=correctness_add_to_100
make test
make perf
make synth
make synth MODE=diagnose
```

`make build`、`make test`、`make perf` 和 `make synth` 使用默认
`verilog/filelist.f` 时会自动调用 Chisel。使用自定义 `FILELIST=...` 时，
由调用方提供 RTL。单独执行 `mill --no-server chisel.compile` 可以检查 Scala 编译。

## 环境自检的含义

`make env-check` 用独立的 Chisel 小模块读取 RAM 地址 0 的值 42，再经
AXI4-Lite 写入课程退出 MMIO。它验证 Verilog 生成、C++ 仿真器编译、
总线读写、退出结果比较、波形和日志输出，并分别验证 opt 和 diagnose
模式下的 ASAP7/ABC 综合及 OpenSTA 时序报告。

该自检使用 `build/env-check/rtl/`，不会替换 CPU 的生成文件；它不是
官方 CPU 正确性测试。报告位于 `build/env-check/synth/{opt,diagnose}/report.txt`。

## 官方测试用例

`testcases` 已初始化到主仓库锁定的提交：

```text
29f980727f7d99a1842a58f34091c7579ba3fe85
```

包含 19 个正确性测试和 6 个性能测试；CPU 实现完成后再运行这些测试。

本机子模块远程地址使用官方 HTTPS 地址，后续可运行：

```sh
git submodule update --init --recursive
```

`build/`、`out/`、`verilog/generated/` 和 AppImage 都已加入忽略规则。
`make clean` 清除仿真及综合产物；`mill --no-server clean` 清除 Mill 编译缓存。
复制项目到另一台机器时，需要另行提供课程 AppImage，或按 `config.mk`
中的说明指定原生工具路径，并安装 JDK 和 Mill。
