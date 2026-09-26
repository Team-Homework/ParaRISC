# ParaRisc

本仓库包含课程评测框架、Chisel CPU 模板、部分整数执行模块及项目文档；完整 CPU 尚未接入顶层。项目目标与交付要求见[项目需求](docs/project-requirements.md)。

## 仓库结构

```text
.
├── README.md              # 仓库入口
├── chisel/src/            # CPU 模板、CLA 与整数执行模块
├── tests/rtl/             # 整数执行模块的 Verilator 单测
├── docs/
│   ├── project-requirements.md  # 项目目标与验收要求
│   ├── guides/            # Chisel 与硬件设计教程
│   ├── planning/          # 分工、进度、执行清单与端口预算
│   └── research/          # 性能指标研究与旧项目迁移分析
└── reports/               # 待完成的两份交付报告
```

## 文档入口

- [项目目标与验收要求](docs/project-requirements.md)
- [Chisel 入门](docs/guides/chisel-crash-course.md) · [硬件设计指南](docs/guides/cpu-hardware-design-guide.md)
- [双人分工](docs/planning/project-ab-plan.md) · [八周计划](docs/planning/eight-week-two-person-plan.md) · [执行清单](docs/planning/todo.md) · [双发射端口预算](docs/planning/port-budget-and-ipc.md)
- [CPU 性能与评测指标](docs/research/cpu-design-metrics.md) · [旧模拟器迁移分析](docs/research/simulator-to-chisel-design.md)
- [性能参数敏感度报告](reports/parameter-sensitivity.md) · [架构设计探索报告](reports/architecture-exploration.md)

整数执行模块的接口与独立验证方法见[整数执行通路说明](docs/integer-datapath.md)。整机仍需将译码、寄存器、执行、访存与课程总线接口连接起来。
