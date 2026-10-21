# Fetch 模块

入口为 `src/pararisc/fetch/Frontend.scala` 的 `Frontend(FetchConfig())`。
逻辑来自 CPU 仓库的单发射 v110 冻结源码
`/home/tangz/CPU/single-issue-chisel/reports/area-recovery-2026-10-21/source/`，
参考提交 `a922b00ea0db34e3bd9f73fd97703d2b43bbb984`。仅迁移前端及必要依赖，
未引入执行、访存、重命名或提交模块。当前 `StudentTop` 仍为 CPU 骨架，
集成时需要连接下述接口。

## 行为与配置

- 复位 PC 为 0，RV32 定长 4 字节取指，单周期最多输出一条指令。
- 默认 4 KiB 直接映射指令缓存、64 字节缓存行，使用课程 `sram_fakeram`。
  缺失时优先读取请求字，随后环绕补齐整行；首字返回即可继续向后端交付。
- 默认 64 项 BTB、256 项二位饱和方向计数器、64 项局部历史，
  使用 1 位历史参与方向预测索引。
- 8 项推测态和提交态返回地址栈，默认使用一级寄存写入及旁路。
- 2 项在途请求元数据队列，8 项带空队列直通的指令输出队列。
- 预解码 JAL、条件分支和返回指令，在取指响应处修正预测并丢弃较年轻请求。

`FetchConfig` 仅保留前端相关参数和合法性检查，默认值对应参考工程。
局部逻辑、队列头缓存和 SRAM 访问时序沿用参考实现。

综合属性使用 Chisel 7 的 `annotate(owner)(Seq(...))` 写法，并使用依赖内置的
`firrtl.AttributeAnnotation(owner.toNamed, "keep_hierarchy = 1")`。
旧的同名自定义注解已移除，避免与 Chisel 7 内置类型冲突。
`LocalLogic` 派生模块仍保留层次；v110 的 `LocalMask`、`LocalWord` 仍允许跨层优化。

与参考实现相比，在途请求采用逐项粘滞失效标记，替代 4 位 epoch，避免
内存响应长期未返回时连续重定向使 epoch 回绕、旧指令重新生效。
外部重定向同周期还会屏蔽 `out.valid`，在时钟沿清空输出队列。

## 后端接口

| 接口 | 方向 | 约定 |
| --- | --- | --- |
| `out` | 输出，Decoupled | `pc`、`inst`、预测下一 PC `next`、预测时的 `history`；后端通过 `ready` 背压 |
| `mem.req` | 输出，Decoupled | `MemReq(addr,data,mask,write)`，指令请求 `write=false`，地址按字对齐 |
| `mem.rsp` | 输入，Decoupled | `MemRsp(data,error)`，必须按请求顺序返回，每个请求恰好一个响应 |
| `redirect` | 输入，Valid | 恢复目标 PC，优先于内部预测修正；使队列和在途旧路径指令失效 |
| `train` | 输入，Valid | 更新 BTB、方向表与局部历史，BTB 更新额外延迟一周期 |
| `commit` | 输入，Valid | 仅按退休顺序更新提交态返回栈，供 redirect 恢复 |

`Training` 包含 `pc,target,taken,conditional,call,ret,history`。
`history` 应回传对应 `FetchPacket.history`，`target` 为实际控制流目标。
`call/ret` 使用 RISC-V 的 x1/x5 链接寄存器约定。提交和重定向同拍时，
恢复后的返回栈包含该次提交。后端发现误预测时应提供 `redirect`。

`mem` 是内部有序请求/响应接口，需经共享内存仲裁器和 AXI4-Lite 桥连接
课程总线；不能直接连接顶层 AXI 引脚。已接受的内存请求在重定向后仍须
完整返回，前端会消费并丢弃失效响应。内存错误触发断言，暂不提供异常包。

## 生成与验证记录

```sh
mill --no-server chisel.compile
mill --no-server chisel.runMain pararisc.fetch.GenerateFetch build/fetch/rtl
```

RTL 生成器输出的 `build/fetch/rtl/filelist.f` 包含全部子模块；Chisel 7 的断言层
单独输出在 `verification/`。后续仿真接入时需要同时考虑模块文件列表和断言层。

自定义前端测试脚本及 C++ 测试程序已按用户要求移除。以下仅记录删除前的
历史验证结果，不能作为后续改动已通过验证的证据：当时使用 Verilator 和课程
SRAM 模型，启用 `--assert` 及队列头一致性检查，覆盖跨缓存行顺序取指、
队列背压、缓存复用、在途缺失期间连续 16 次重定向、输出队列清空、正负 JAL、
返回预测、条件方向训练、提交与恢复同拍的返回栈。

初次迁移使用 Scala 2.13.12 / Chisel 6.5.0 / firtool 1.62.0 验证。
2026-10-21 完成注解及测试入口适配后，已在项目固定的
Scala 2.13.18 / Chisel 7.7.0 / Mill 1.1.2 / firtool 1.139.0 下通过编译和 RTL 生成，
并通过 Verilator 5.020 前端测试。检查了生成 RTL 的模块层次属性，且确认
缓存错误断言和队列头一致性断言已进入仿真模型。
本机保留的复核日志位于 `build/fetch/chisel7-check/`，生成 RTL 和历史仿真器位于
`build/fetch/rtl/`、`build/fetch/obj/`；清理构建目录后不会重新生成这些测试记录。
以上是前端模块测试，不代表完整 CPU 已通过课程正确性或性能测试。
