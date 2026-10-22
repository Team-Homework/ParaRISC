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

## 独立分支预测模块

`src/pararisc/fetch/Predictor.scala` 提供 `BranchPredictor(FetchConfig(...))`，
封装 BTB、方向计数器、局部历史、推测态/提交态 RAS 和响应预解码修正。
`PredictorCounter`、`PredictorHistory` 仍作为原有局部逻辑单元复用。
`Frontend` 管理 PC、指令缓存、队列及旧请求失效，并通过以下内部接口连接预测器。
现有后端接口和 `Training`、`FetchPacket` 的字段保持兼容。

| 接口 | 方向 | 位宽与时序 |
| --- | --- | --- |
| `pc` | 输入 | 32 位查询 PC，组合查询，不增加取指流水级 |
| `prediction` | 输出 | `BranchPrediction`：`next[31:0]`、`history[1:0]`、`callApplied`、`returnApplied`、`rasTop[31:0]`、`rasValid` |
| `accept` | 输入 | 1 位事件，仅在缓存请求 `valid && ready` 时拉高，触发预测调用/返回的 RAS 更新 |
| `response` | 输入，Valid | `pc[31:0]`、`inst[31:0]` 和该请求接受时保存的 `prediction`；仅在未失效的缓存响应握手时有效 |
| `corrected` | 输出 | 32 位组合修正目标；由响应指令、保存的历史/RAS 快照及当前方向表计算 |
| `redirect` | 输入 | 1 位恢复事件，恢复提交态 RAS；Frontend 同拍禁止新请求并过滤旧响应 |
| `train` / `commit` | 输入，Valid | 沿用 `Training`，分别更新预测表和提交态 RAS，每个有效时钟沿接受一次 |

Frontend 在接受请求时保存完整 `BranchPrediction`，响应返回后使用该快照。
`accept`、`response.valid` 是已经完成的握手事件，不能用尚未接受的 `valid`
代替；背压和失效响应不会触发 RAS 推测更新。`corrected` 本身不依赖
`response.valid`，因此 Frontend 可先计算目标，再由实际响应握手触发修正。
`rasTop` 只有在 `rasValid` 时才有语义。

方向计数器及局部历史在 `train.valid` 的接受沿更新；BTB 在该沿寄存分组载荷
和写掩码，在下一个沿发布表项，保留参考实现额外一周期的更新延迟。
历史表仅由条件分支训练更新，redirect 不清空历史或 BTB。
RAS 保留 `rasWriteStages=0/1` 两种写入方式及一级写入时的栈顶旁路，
响应只补做预测时尚未执行的 push/pop；redirect 恢复优先于推测更新，
并包含同拍提交的 call/return。上述封装不引入额外状态或流水级，
面积和时序影响仍须由完整 CPU 的实际综合确认。

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

### 2026-10-22：BranchPredictor 封装验证

对照基线为修改前的 `c4b80bd10b5337ad64baf2d45a63126ad9cf6f6a`。
当前机器未找到 Mill，沙箱外重试下载亦连接超时；没有更改项目固定的
Chisel 7.7.0 / Mill 1.1.2 配置。此次使用缓存的 Scala 2.13.12、Chisel 6.5.0、
firtool 1.62.0 和 Verilator 5.020，在忽略的 `build/predictor-20261022/`
中进行兼容验证。仅隔离副本的 `Cache.scala`、`LocalLogic.scala` 层次属性
注解适配为 Chisel 6 API，并加入该版本需要的注解定义；维护源码未作这些改动。
受测 `Frontend.scala`、`Predictor.scala` 与当前源码逐字节一致，
`source-hashes.json` 保存了全部前端源码的 SHA-256。

| 配置 | historyBits | rasWriteStages | btbEntries | 预测器模型对照 | 新旧 Frontend 逐周期对照 |
| --- | --- | --- | --- | --- | --- |
| default | 1 | 1 | 64 | 16,000 周期通过 | 50,000 周期通过，交付 13,417 条指令 |
| no-history | 0 | 0 | 16 | 16,000 周期通过 | 50,000 周期通过，交付 13,574 条指令 |
| two-bit-history | 2 | 1 | 256 | 16,000 周期通过 | 50,000 周期通过，交付 11,758 条指令 |

预测器定向检查覆盖 BTB 发布延迟与索引冲突、方向饱和与局部历史、正负
JAL/分支立即数修正、RAS 背压、推测调用去重、提交与恢复同拍、写入旁路及
栈深度饱和/回绕。Frontend 对照同时检查输出有效信号、全部有效指令包、
内存请求及响应 ready；并通过顺序取指、缓存命中、队列背压、延迟响应期间
连续 16 次 redirect、调用返回和训练场景。比较中未发现周期或数据差异。

临时验证源、生成 RTL、工具命令和原始日志均保存在上述构建目录，未恢复
已删除的受版本控制测试。可在该目录已有基线及验证入口时重新执行：

```sh
python3 build/predictor-20261022/prepare_validation.py
python3 build/predictor-20261022/generate_validation.py
python3 build/predictor-20261022/simulate_validation.py
```

`provenance.json`、`results.json` 和 `logs/` 分别记录来源/工具链、结果及日志。
清理构建目录会移除这些本地证据。此次尚未验证 Chisel 7.7 原生编译；
`StudentTop` 仍为骨架，故未运行完整 CPU 官方正确性、perf 或综合，
没有新的 IPC GEOMEAN、面积或 Fmax 数据。
