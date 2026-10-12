# 方块执行与历史契约

状态：维护中；核查：2026-10-13（第二十八批）。适用于 BuildTask、PatchExecutor、Patch/普通建造历史；不保证所有其他世界写入入口已统一。

## 预览过滤 = 建造过滤（不变量）

服务端 Patch 预览签发（`PatchPreviewService` → `ServerPatchFilter`）委托 `PatchFilterPipeline.createDefault()`（禁区 / 轮廓 / 选区 / 对称）。确认时消费的是**已过滤**的 `PreviewTicket` 内容，不得在确认路径上再跑一套不同的过滤规则，也不得回退到未过滤的客户端上传补丁。

普通建造预览（`/forma_confirm`）经 `PreviewStorage`：确认前校验维度 + origin + 结构 digest，确认时**一次性消费**结构，禁止二次确认重放。整栋路径在预览阶段用 `BuildConstraintClipper` + `BuildConstraints`（禁区 / 选区 / 轮廓 / 笔刷 / 路径走廊）裁剪；写入阶段 `BuildTask` 不再二次裁剪。

### Patch vs Build 写入路径对照（2026-10-13 核对）

| 项 | Patch（`PatchPreviewService`） | Build（`/forma_confirm` → `BuildExecutionService`） |
|---|---|---|
| 预览裁剪 | `ServerPatchFilter` → `PatchFilterPipeline` | `BuildConstraintClipper` → `BuildConstraints.allow` |
| 确认门禁 | 玩家绑定 + **维度** + 距离 ≤96 + 一次性 Ticket | 玩家预览态 + **维度** + digest + 一次性 consume |
| 世界写入 | `PatchExecutor`（同步） | `BuildTask`（分 Tick） |
| 历史 | `PatchHistoryManager` | `UndoService` |
| Memory | Patch Diff Mutation | `registerBuilding`（仅完整放置） |

**已知分叉（未在本批统一）**：Pipeline 含对称扩展；BuildConstraints 含笔刷/路径走廊，不含对称。两边核心禁区/选区/轮廓应对齐，新增约束须两边同步或抽共享 allow()。

**已修复**：Patch `confirm` 须先校验维度/距离再 `consume`，避免门禁失败丢票据；Ticket 绑定签发维度。

### 第二十七批（2026-10-13）

- **CAS 消费**：`PreviewTicketStorage.consume` 使用 `ConcurrentHashMap.remove(key, value)`；同 Ticket 并发确认仅成功一次。
- **Ticket 级质量**：签发时写入 `QualitySnapshot`；确认走 `BuildQualityGate`（FATAL 禁、ERROR 需 force），与 `/forma_confirm force` 对齐。
- **离线 Undo**：`BuildExecutionService` 完成时按 owner UUID 调用 `UndoService.pushUndo`，不依赖玩家在线；消息仅在线发送。
- **全局 Tick 预算**：每世界每 Tick 共享 `BLOCKS_PER_WORLD_TICK=200`，轮询公平调度；单世界任务上限 32。
- **严格 Meta**：`PreviewStorage.validatePreview` 在缺少 `PreviewMeta` 时拒绝，确认阶段不再自动补齐。

`PatchHistoryManager` 与 `UndoService` 仍并存：维度/UUID/冲突语义各自维护；**不合并执行器**。统一顺序由 `PlayerMutationLedger` + `MutationHistoryFacade` 按时间倒序选择栈。

### 第二十八批（2026-10-13）

- **BuildTask 可靠执行**：未加载进入延迟队列（按 Chunk 分组重试；超时/次数上限后计 `failedUnloaded`）；单方块异常隔离为 `failedWrites`；写后校验。
- **BlockSnapshot**：`BlockState` + 可选 BE NBT；历史与 Undo 按快照比较。BE 序列化/恢复失败时 warn 并仅恢复状态。
- **会话级 Undo**：当前服务器会话内存态，停服清空；UI/摘要提示「会话内历史，停服后清空」。不做跨存档持久化。
- **PatchExecutor**：`attempted`/`accepted`/`verified`；`applied()` 兼容为 `verified`；写后校验失败不计成功。

## 世界访问与结果

BlockMutationAccess 封装高度、区块就绪、读取、写入及快照（含 BE NBT）。生产适配器仍使用 ServerWorld.setBlockState(...,3)，测试使用可控内存世界验证拒绝写入，不伪装成真实游戏服务器。

先检查高度和区块，再读取当前快照。同状态只计跳过；写入拒绝或写后状态不符时计 failedWrites，不能计 applied/placed，也不能进入 BuildTask 的 BlockChange。成功写入的撤销记录保存实际 after 快照。

BuildTask 累计计划、放置、越界、同状态、未加载最终失败、等待延迟、非法目标与写入失败。主队列与延迟队列均排空后才 `isFinished`。部分未放置不得宣称完整成功。仅完整放置时注册 Memory；部分成功仍保留 UndoEntry。

PatchExecutor 只接受 place/replace/remove。显式 minecraft:air 合法；未知方块/动作/属性全部拒绝。BuildTask 对单方块 RuntimeException 隔离；Patch 正向路径写后校验计入 failedWrites。

ApplyResult 和 BuildApplyResult 保留四参数兼容构造器。Patch 网络载荷仍保留原有四个数字字段，新计数通过 summaryZh 下发；不能把 failedWrites 混记成 skippedIllegal。

## Patch 历史与 Memory 差异

PatchTransaction.fromSnapshots 只保留已加载、有效高度位置的最终 before/after 差异，快照不可变，输出按坐标排序。失败写入、同状态或同一位置先改后恢复的请求不会产生历史事务，因此不会清空已有 redo。

Memory 的正向分析使用最终 after 状态差异，而非原始尝试列表；反向分析使用真实 before 材质与属性。replace 不能简单反转为 remove，否则替换石头为木头后撤销会错误描述为删除木头，而非恢复石头。

这些快照只覆盖明确请求位置，邻居更新波及的方块尚未纳入；同一位置的中间动作不进入最终 Memory 差异。计数可多次应用同一位置，与最终差异位置数不必相等。

## 可重试恢复与世界身份

PatchHistoryManager 与 UndoService 共用 PatchReplayHistory。每个事务绑定原 ServerWorld 对象身份；不同维度或另一服务器会话的同名世界不允许恢复，也不消耗历史。历史为内存状态，服务器停止时清理 Patch 历史、普通 Undo、活动建造任务及 MemoryManager 引用；不持久化跨服务器重启撤销。

每次恢复先检查高度与区块，再读取方块。当前状态已是目标时不写；当前状态不同于事务预期源状态时记录冲突，不覆盖后续修改。成功恢复的位置从 pending 移除；写入失败、未加载、越界或冲突留在源栈，仅完整完成后移入另一栈。未完成的恢复不允许反方向重放；重试只处理剩余位置。

Patch Memory 按本次真实变化更新，失败位置及已是目标的位置不产生虚假变化。普通建造同位置重复写入合并为最早 before 与最终 after，恢复只写一次；普通建造 Undo 通过关联 UUID 保存绝对恢复进度，并保留原生成数据；尚未提供 gene 逆向事务。

历史恢复逐位置捕获 RuntimeException，记录日志并计入 failedWrites，继续处理其他位置；Error 仍向上传播。写入前保存实际源状态，直到写入后读取确认才清除。写入已发生但写入/后读抛异常时，位置保留；下一次在原世界且位置可读时，根据实际状态补报差异，不重复写入已到达目标的位置。确认后的 delta 才交给现有 Patch Memory 更新入口；这不是世界与 Memory 的原子提交。重试前无法确定的写入不计 changed。若在未确认的部分 redo 后建立新分支，潜在已写入位置仍保留为可撤销记录，通过目标/源状态检查决定是否需要恢复。

新的非空操作清除 redo 分支。如果 redo 已部分完成，将已恢复部分保留为可撤销事务，丢弃尚未执行的 redo 待办；空事务不影响 redo。现有历史数量上限仍适用。

详细结果包含 changed、alreadyCorrect、blocked、failedWrites、conflicts、remaining 和实际差异。网络/命令使用详细结果与中文摘要，不再把部分恢复说成全部完成；旧 int API 保留，完整返回数量、未完成返回 -2、无历史或错误世界返回 -1。blocked 合并高度/未加载信息，仅通过摘要明确原因，旧网络数值字段尚未扩展为完整结果协议。

## 尚未解决的事务问题

- Patch Memory 更新异常仍是独立日志告警；普通 Undo 增加了可重试保存，世界与 Memory 仍不具备原子回滚。
- 方块实体 NBT 已接入快照路径（单元测试覆盖 Fake 世界）；真实箱子/漏斗等游戏内验收与邻居更新、多人并发修改仍待实机验证。状态比较提供冲突保护，但不记录修改来源/时间。
- 普通建造 Undo 已关联记忆 UUID 并保存恢复进度，保存失败在下次命令重试；待保存队列与 Undo 历史均不跨停服持久化，gene/当前几何没有重建。不能根据恢复数量删除整栋记忆，见 [Memory 契约](MEMORY_TRANSACTION_CONTRACT.md)。
- BuildTask 正向执行已隔离单方块 RuntimeException；PatchExecutor 写后校验失败计入 failedWrites。世界与 Memory 仍非原子事务。

上述问题没有因单元测试通过而解决。后续应将执行、历史和 Memory 更新收敛为服务端事务，common 保留结果与差异模型，先保持当前协议兼容。
