# ParaRisc

本仓库目前保存 CPU 项目的需求、规划与技术资料，CPU 源码尚未开始提交。项目目标与交付要求见[项目需求](docs/project-requirements.md)。

## 仓库结构

```text
.
├── README.md              # 仓库入口
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

后续 CPU 实现建议按源码、测试、脚本与实验结果分别归档；在出现实际文件前不预设空目录或声称已有可运行实现。
