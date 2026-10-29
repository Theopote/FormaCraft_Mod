# 几何质量反馈与来源追踪

## 已接入

预览流水线沿用“初检 → 确定性修复 → 约束裁剪 → 最终复检”。
`BuildQualityIssue` 现在除 severity、code、message 外，提供：

- `phase`：initial / final；独立调用检查器时为 unspecified。
- `affectedCount`：该问题计数，不是坐标样本数；CHUNK_UNLOADED 按区块计数，其他已定位规则按方块或底层柱计数。
- `positions`：最多 8 个世界坐标 `{x,y,z}`；不包含方块实体 NBT、请求文本、API key。
- `origins`：这些坐标对应的来源，最多 8 项。包含 componentPath、slotId、node、instance、path、producer；无来源时为空。

悬空、世界高度越界、选区/禁区越界、重复写入和未加载区块提供坐标样本。
缺少坐标或状态的非法方块提供数量；尺寸差异等聚合规则暂不提供坐标，affectedCount 为 0。
受影响数量为 0 或空坐标都表示未提供证据，不代表该问题不存在。
FBD 来源由 Java lowering 记录原 components 索引，编译器为每个输出操作附上 node、instance 与原 AST path。
只有携带 FBD 执行预算的服务端编译路线才附加节点来源；普通 Assembly 的同名元数据不会获得 FBD 来源。
服务端保留字段 _server_fbd_source 与 _server_fbd_compilation 都拒绝来自计划的输入。

来源随最终 Patch、slot 偏移、plan 世界坐标转换、整体地形平移、修复去重及约束裁剪保留。
同一坐标取最后写入的来源；后续无来源的写入会清除原来源，不根据位置猜测所有者。
循环与函数复用保留各自 instance，同一 AST 节点的不同实例不会合并成一个来源。
支撑补齐、门补齐、入口净空、地形与落地系统新增方块分别标记系统 producer，不假借 FBD 节点。
来源只供诊断，不授权修改 AST。修改一个函数节点可能同时影响多个实例，仍需独立约束。

BlockPatch 改为不可变类，保留五参数构造、原五个字段的访问器、几何相等语义、哈希与 JSON 字段。
source 是 transient，Jackson 也显式忽略；Gson/Jackson 输入均不能注入来源。
来源不进入 Patch 网络协议或持久化 JSON，服务端预览持有的 PlannedBlock 保存运行时来源。

所有实体统计和尺寸检查以同一坐标最后写入为准，忽略最终 AIR 的几何包围盒。
原始写入的重复次数单独保留。非法方块先诊断，避免后续检查空指针。
全部清空后没有实体方块的建筑结果以 STRUCT_NO_SOLID 拒绝预览。

最终报告保留初检证据，历史项使用 INITIAL_ 代码前缀、initial 阶段和 INFO 严重度。
`activeDiagnostics()` 只返回非 INFO 的当前问题，历史错误不会重新触发强制确认。
最终残余 ERROR 仍要求原有显式 force，FATAL 始终拒绝。

日志示例：

```text
Quality diagnostic: phase=final code=FLOATING severity=ERROR affected=20 positions=[Position[x=0, y=80, z=0], ...]
```

## 修订边界

本阶段补齐几何反馈证据，不调用模型修改几何。已有 Java 编译诊断修订继续运行。
现有确定性支撑修复、净空处理、保护区与预算门禁继续适用。
入口堵塞没有通用可靠检测器；连通性支撑检查也不是承重求解器，不宣称完成这些验证。

## 几何候选修订契约

`FbdGeometryRepairGuard` 约束候选契约；`FbdGeometryRepairOrchestrator` 在预览路径上
对合格 FLOATING 一次试 `ROOF_COVER.y −1` 再 `+1`，经完整重生成复检后若悬空减少，
则用该候选计划重新跑预览（同一请求不再二次几何修订）。仍不调用模型，不改确认/force。
当前唯一候选是：最终 FLOATING 诊断指向一个 ROOF_COVER 节点，其 params.y 为直接整数时，
相对原计划上下 1 格的局部高度。资格通过不代表这个方向能修复问题，也不证明符合玩家设计意图。

- 只接受 final 阶段、带计数的活动诊断。缺少来源、系统新增方块、多个来源混合、来源与编译结果不符均拒绝。
- 重新编译完整 FBD 程序，按原 AST 路径统计全部展开操作；不是根据最多 8 个采样位置推断唯一实例。
  函数或循环共享节点产生多份输出时拒绝，即使诊断恰好只采样到其中一份。
- y 若为变量引用、表达式、缺省值或非整数则拒绝。w/d/h、rise、overhang、材质、循环、seed、frame、
  整体位置和其他语句没有几何修订权限。旧的编译类型修订契约独立保留。
- 许可私有构造并绑定完整计划的规范化 SHA-256 和内部快照。修改计划后旧许可失效；
  不能拿同一许可对新候选继续逐格漂移。
- 候选复制快照，只替换允许的一个叶子，重新编译并再次执行全计划预算检查。
  全部展开操作的数量、顺序、节点、实例和其他参数必须保持一致。
- `evaluate` 需要服务端提供可信的完整重生成/最终质量验证器；它自身不读取或写入世界。
  空结果、无实体计数、验证异常、验证器修改候选、非 final 或未量化诊断都拒绝。
  不得新增活动问题代码、升级严重度或增加任何已量化问题的总数量，且实际悬空柱数量必须严格减少。
  低于警告阈值的残余悬空也计入比较，不能把“没有警告”当成零悬空；问题代码与计数不一致的验证结果拒绝。
  返回 VERIFIED_CANDIDATE_ONLY 仍是候选，不授予确认、force、应用或撤销权限。

日志示例：

```text
FBD geometry repair assessment: eligible=false reason=MULTI_INSTANCE path=- instances=0
FBD geometry repair assessment: eligible=true reason=ELIGIBLE_CANDIDATE_ONLY path=/components/0/params/fbd_program/main/1/params/y instances=1
```

预览路径已贯通：候选经 `FbdGeometryQualityVerifier` 重生成复检后，由
`LlmPlanPreviewBuilder` 用接受的计划重新发预览。缺少来源的普通组件仍只报告坐标，
不猜测 AST 路径。几何资格评估不会调用 LLM、改变已落地建筑或绕过质量门禁。

## 自动化验证

GeometryDiagnosticsTest 覆盖最终写入统计、清空几何、非法状态、选区越界证据、
悬空证据上限与确定顺序、JSON 往返、修复后清除活动诊断。
GeometryHistoryTest 覆盖预览最终报告保留历史坐标、阶段，以及残余错误继续要求 force。
StructureSupportAnalysisTest 保留既有真实程序、方向、地形与修复回归。
游戏中需要重启客户端加载 Java 修改，再观察日志；本次自动化不替代游戏验收。

FbdProvenanceTest 覆盖四朝向、循环函数实例、slot 与世界偏移、整体升降、最终写入/AIR、
诊断 JSON 与历史来源、修复与裁剪、系统新增支撑，以及两种 JSON 实现均拒绝来源注入。

FbdGeometryRepairGuardTest 使用真实 FBD 几何复现屋顶悬空：单格降低后连通性恢复，
升高但无改善时拒绝；另覆盖隐藏的共享实例、表达式、陈旧来源、系统来源、跨字段补丁、
计划漂移、失败/空验证、新错误和验证器越权修改，并保护原计划与外层编译诊断。
