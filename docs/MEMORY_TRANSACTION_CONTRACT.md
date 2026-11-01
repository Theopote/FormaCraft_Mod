# Memory 查询与事务契约

状态：维护中；核查：2026-10-03（第十一批）。

## 维度是空间身份的一部分

SpatialIndex.findAt/findNearest、MemoryManager.findAtPosition/findNearest、PatchDiffAnalyzer.analyze 和 MemoryManager.applyMutation 必须显式传入 Identifier dimension；不再提供只传坐标或默认主世界的入口。区块候选索引仍按坐标组织，返回结果前严格过滤 SpatialBounds.dimension。相同坐标在不同维度可各有建筑，不应相互修改。

PatchHistoryManager 从执行的 ServerWorld 获取维度，正向、undo、redo 分析和 mutation 写入使用同一个维度。applyMutation 对已有 UUID 再次检查维度，拒绝跨维度或缺失维度的记录；新建 Patch 记忆使用执行维度，不再固定主世界。

PromptMemorySections 从当前客户端世界获取维度：空间与最近查询传入维度，关键词语义搜索的最终候选也按维度过滤，再截取五条。无法取得客户端世界时不注入记忆。该路径仍依赖客户端及单例服务器 MemoryManager；尚未实现独立多人服务器的记忆传输，也未解决异步规划期间切换世界的问题。

## 旧数据与删除语义

本批不重写磁盘旧记忆。已有明确维度字段按原值使用；维度缺失、无效或不匹配的记录不会被空间查询和提示词当作当前世界建筑，mutation 也拒绝修改。历史版本把下界建筑错误标成主世界的记录无法仅凭坐标自动判定，需要核对原始世界后修复。语义检索底层仍可查到这些记录，调用者必须根据用途过滤。

删除未登记位置的方块不产生 CREATE 影响，避免拆除后凭空新增建筑记忆。已登记建筑的局部删除仍标记 REMOVE/partially_removed；没有根据少量移除方块就删除整栋记忆。当前重叠建筑仍选择第一个匹配结果，扩建边界归属也待完善。

## 存档隔离与旧全局目录

MemoryStorage 使用 server.getSavePath(WorldSavePath.ROOT)/formacraft/memory。此前实现忽略 server，实际共享进程运行目录 ./formacraft/memory；第十一批已移除这一行为。新路径不读取旧全局目录，也不自动复制到当前存档，避免错误归属。切换存档不应加载另一存档的记忆。

旧记忆需要人工核对归属：停止服务器并备份源目录和目标存档，按建筑 UUID、坐标、维度、名称检查哪些 project_UUID.json 属于目标存档，仅复制确认归属的文件到目标存档的 formacraft/memory。UUID 重复的文件先人工核对，不直接覆盖；缺维度或历史错误维度需另行修复。重启后验证索引和提示词。不改动旧源目录。本批没有迁移、复制或删除玩家的历史记忆。

保存先写同目录临时文件，完成后使用 ATOMIC_MOVE/REPLACE_EXISTING 替换目标；文件系统不支持原子移动时退回普通替换，不保证崩溃时原子性。finally 清理临时文件。失败写入不直接截断原 JSON；该方式不是 fsync 或多文件事务。

## 普通建造撤销的 UUID 关联与进度

BuildExecutionService 将建造事务派生的稳定 memory UUID（UUID.nameUUIDFromBytes）绑定到对应 UndoEntry；该 UUID 与记忆提交记录共用同一身份，记忆写入失败时仍保留关联，撤销可据此阻止迟到的提交复活建筑。失败登记或部分建造仍保留方块历史，但不产生记忆关联。UndoEntry 的旧构造器保留，memoryUuid 默认为 null。UndoService 为非空最终事务生成 transactionId，并与该玩家的最多十条历史同步维护，空净差异不推进关联。

普通 Undo 在原世界身份通过后，向关联 UUID 写入 BuildUndoMemoryUpdate。totalPositions 是事务中不同的实际变化位置数，restoredPositions = totalPositions - remaining，是已确认达到撤销目标的位置数，包含原本已是目标的位置；不是本次写入数量，也不是整栋建筑方块总数。partial/重试使用同一个 transactionId，写入绝对进度，不累加计数。

元数据字段：build_undo_transaction、build_undo_state（pending/partial/restored）、build_undo_restored_positions、build_undo_total_positions。restored 仅表示该次建造实际变化位置全部达到 before 状态，不能推断整栋建筑消失。记忆不删除、不缩边界，已有 roof_modified 等 Patch 信息保留，原始生成 gene/bounds 不伪装成当前几何。提示词明确这些是登记设计记录，包含撤销进度，需核验当前世界。

MemoryManager.recordBuildUndo 先核验 UUID/维度，保存失败恢复缓存元数据和 lastModified 并返回 false。UndoService 留下最新待保存进度；每次同一玩家调用撤销入口都会重试，即使已经没有方块历史。保存失败也不重复恢复已经完成的方块，并向玩家提示记忆未保存。缺失记录不会自动重建。服务停止时清空历史及待保存记录，因此同步失败应在退出前重试或核对。

UndoService 的待保存记忆更新同时由周期性恢复调度驱动：服务端每 200 tick 调用 UndoService.retryAllPendingMemory，player 不再执行任何撤销/重做时同步也不会长期停滞。

## 建筑记忆提交的事务身份（第十二批，2026-10-31）

建造成功但记忆写入失败时，待重试提交不再是内存中的裸记录，而是持久化的 MemoryCommitRecord，落盘在存档目录 formacraft/memory_commits/commit_<memoryUuid>.json。

- 维度绑定：记录携带 dimension（RegistryKey 值）。重试入口按维度分派，只有拥有该维度的世界 tick 才会重试这条提交；下界或末地的 tick 永远不会把主世界建筑登记到别的维度。
- 幂等身份：memoryUuid 由建造事务 ID 派生（UUID.nameUUIDFromBytes），同一次建造的重试始终指向同一条记忆。MemoryManager.commitBuilding 按该 UUID 更新既有记录，不会二次 registerBuilding 生成新建筑。
- 撤销联动：UndoService 在撤销/重做时把进度写入待提交记录（notePendingMemoryUndo）。提交重试前检查记录是否已完整撤销；已完整撤销则标记 CANCELLED 并丢弃提交，不把已撤销建筑登记为正常存在。部分撤销会在提交成功后把撤销进度写回记忆元数据。已取消的提交在玩家 Redo 恢复建筑后重新进入重试队列。
- 跨重启恢复：服务器启动时扫描 memory_commits 目录恢复未完成提交，重试次数归零后继续提交。超过最大重试次数的记录转为 FAILED_NEEDS_ATTENTION 并保留在磁盘与 failedMemoryCommits 列表中，不再静默丢弃；可通过 /formacraft_memory_commit 查看、/formacraft_memory_commit retry 手动重排。

MemoryManager.deleteMemory 改为先完成持久层删除，成功后再移除空间/语义索引条目；文件删除失败时保留索引条目，避免磁盘仍有记录而索引已不可见的矛盾状态。

## 记忆提交的分阶段持久化与执行摘要（第十三批，2026-11-01）

建造成功后的记忆提交不再是"一次写入"，而是显式的多阶段提交；每个阶段独立报告结果，全部必需阶段成功后才允许标记 COMMITTED。

- 补偿记录持久化结果显式返回：MemoryManager.saveCommitRecord 与 MemoryCommitStore.save 改为返回 boolean，只有真正落到磁盘才返回 true。BuildExecutionService.queuePendingMemoryCommit 返回该结果：失败时记录仍在内存队列（本次会话内可重试），但同时输出高优先级错误日志并向在线玩家提示"记忆保存失败且补偿记录未能持久化"，绝不把"仅存在于内存"当作"已可靠排队"。重试循环中的其他 saveCommitRecord 调用点同样在失败时报错。
- 撤销元数据纳入提交条件：commitMemory 的第二阶段调用 memoryManager.recordBuildUndo 并检查其 boolean 返回值。写入失败时记录保持 PENDING、写入 lastError=undo_metadata_persist_failed，不标记 COMMITTED、不删除提交记录，后续重试可继续补齐该阶段。保存失败的建筑记录本身已存在，但其撤销状态尚未持久化，因此不允许视为整体成功。
- 修订号保护：区分"首次提交"与"未完成提交的重试"。判定依据是记忆是否已存在（MemoryManager.hasMemory 同时查内存索引与持久层），而不是记录自身的字段。首次提交初始化建筑身份、屋顶索引与 revision=0；重试只补齐缺失数据（例如屋顶索引为空时才写入），并且不把已有 revision 重新置零，避免旧事务覆盖增量编辑产生的较新修订。
- 执行摘要：MemoryCommitRecord 新增 sourceKind（PLANNED/EXECUTED）、appliedPositions、plannedPositions、executionHash、buildStatus。计划结构仍是建筑语义来源，executionHash 是对实际写入世界的变更集合按位置排序后的 SHA-256 摘要（同一位置多次写入取最终状态），appliedPositions 是实际变化的去重位置数。摘要同时写入记忆元数据（build_execution_source / build_execution_applied_positions / build_execution_planned_positions / build_execution_hash / build_execution_status），可据此判断记录依据的是"规划结果"还是"已确认的实际建造成果"。

## 验证

MemoryDimensionTest 覆盖重叠坐标隔离、跨维度最近建筑、删除某维度索引不影响另一维度、缺失维度、Patch 目标识别、无归属删除和错误维度 mutation 拒绝。测试使用真实内存索引，不启动游戏、不访问玩家存档；新建记忆磁盘写入及真实游戏仍需验收。

第十一批补充 UndoServiceTest、BuildUndoMemoryTest 和 MemoryStorageTest：关联正确性、限额与玩家隔离、空事务、部分重试、保存失败/异常、缓存回退、Patch 元数据保留、存档路径隔离及临时文件清理。临时目录文件测试不访问玩家存档。

第十二批补充 MemoryCommitRetryTest、MemoryCommitIdentityTest 与 MemoryCommitIdempotencyTest：跨维度 tick 不会重试别维度提交、重试保持同一建筑 UUID、已完整撤销的提交被取消而不复活、Redo 后被取消的提交重新生效、重试耗尽转为 FAILED_NEEDS_ATTENTION 且可手动重排、提交记录 JSON 往返保持身份与结构快照、同一 UUID 重复提交只产生一条建筑索引记录。

第十三批在 MemoryCommitRetryTest 中补充：补偿记录写入失败时 queuePendingMemoryCommit 返回 false（不报告为可靠排队）；撤销元数据写入失败时提交保持可重试且不标记 COMMITTED；重试不把较新 revision 归零、首次提交正常初始化 revision=0；执行摘要区分 PLANNED 与 EXECUTED 并经 JSON 往返保持；实际位置计数与执行哈希基于世界写入结果而非计划，且与写入顺序无关。
