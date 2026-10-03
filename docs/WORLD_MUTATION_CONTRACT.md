# 方块执行与历史契约

状态：维护中；核查：2026-10-03（第七批）。适用于 BuildTask 与 PatchExecutor；不保证所有其他世界写入入口已统一。

## 世界访问与结果

BlockMutationAccess 封装高度、区块就绪、读取及写入。生产适配器仍使用 ServerWorld.setBlockState(...,3)，测试使用可控内存世界验证拒绝写入，不伪装成真实游戏服务器。

先检查高度和区块，再读取当前方块。同状态只计跳过；setBlockState 返回 false 时计 failedWrites，不能计 applied/placed，也不能创建 BuildTask 的 BlockChange。成功写入的撤销记录保存读取到的实际 after 状态。

BuildTask 累计统计计划、实际放置、越界、同状态、未加载、非法目标与写入失败。失败不混入跳过计数。完整处理时 total = placed + skippedTotal + failedWrites；部分未放置的摘要不得宣称完整建造成功。只有全部目标已经放置或同状态时，普通建造才注册完整建筑 Memory；部分成功仍保留实际改动的 UndoEntry。

PatchExecutor 只接受 place/replace/remove。显式 minecraft:air 是合法目标；未知方块、未知动作、无效/重复属性、非法属性值及不完整括号全部拒绝，不静默退为空气或默认状态。状态解析不吞 Error。普通运行时写入异常仍向上传播，本轮只明确处理布尔拒绝，尚未提供异常后恢复事务。

ApplyResult 和 BuildApplyResult 保留四参数兼容构造器。Patch 网络载荷仍保留原有四个数字字段，新计数通过 summaryZh 下发；将来需要机器可读字段时应同步修改两端协议，不能把 failedWrites 混记成 skippedIllegal。

## Patch 历史与 Memory 差异

PatchTransaction.fromSnapshots 只保留已加载、有效高度位置的最终 before/after 差异，快照不可变，输出按坐标排序。失败写入、同状态或同一位置先改后恢复的请求不会产生历史事务，因此不会清空已有 redo。

Memory 的正向分析使用最终 after 状态差异，而非原始尝试列表；反向分析使用真实 before 材质与属性。replace 不能简单反转为 remove，否则替换石头为木头后撤销会错误描述为删除木头，而非恢复石头。

这些快照只覆盖明确请求位置，邻居更新波及的方块尚未纳入；同一位置的中间动作不进入最终 Memory 差异。计数可多次应用同一位置，与最终差异位置数不必相等。

## 尚未解决的事务问题

- PatchHistoryManager.restore 与 UndoService 仍忽略写入返回值，部分恢复失败仍可能弹出历史或误报完成；必须下一批处理事务保留、部分结果和重试。
- Patch 历史仍按玩家记录，未携带世界/维度身份；跨维度撤销需要约束。
- Memory 更新异常仍是独立日志告警，世界与 Memory 不具备原子回滚。
- 方块实体 NBT、邻居更新、同位置多次写入及其他玩家的后续修改需要真实世界验收与冲突策略。

上述问题没有因单元测试通过而解决。后续应将执行、历史和 Memory 更新收敛为服务端事务，common 保留结果与差异模型，先保持当前协议兼容。
