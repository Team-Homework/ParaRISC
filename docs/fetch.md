# Fetch 模块

入口为 `src/pararisc/fetch/Frontend.scala` 的 `Frontend(FetchConfig())`。
逻辑来自相邻 `ParaRisc/single-issue-chisel/src/main/scala/tier2`，参考提交
`a922b00ea0db34e3bd9f73fd97703d2b43bbb984`。仅迁移前端及必要依赖，
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

## 生成与验证

```sh
mill --no-server chisel.compile
mill --no-server chisel.runMain pararisc.fetch.GenerateFetch build/fetch/rtl
python3 tests/test_fetch.py
```

测试使用 Verilator 和课程 SRAM 行为模型，支持 `MILL`、`VERILATOR`
环境变量。已有 RTL 时可用 `python3 tests/test_fetch.py --skip-generate`。
覆盖跨缓存行顺序取指、队列背压、缓存复用、在途缺失期间连续 16 次重定向、
输出队列清空、正负 JAL、返回预测、条件方向训练、提交与恢复同拍的返回栈。

本次环境中 Mill 缺失且下载源连接超时，因此实际编译和 RTL 生成使用缓存的
Scala 2.13.12 / Chisel 6.5.0 / firtool 1.62.0，随后通过 Verilator 5.020 仿真。
项目固定的 Scala 2.13.18 / Chisel 7.7.0 / Mill 1.1.2 尚未在此次环境中验证。
以上是前端模块测试，不代表完整 CPU 已通过课程正确性或性能测试。
