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

BuildExecutionService 将 registerBuilding 成功返回的 UUID 保存到对应 UndoEntry；失败登记或部分建造仍保留方块历史，但不产生记忆关联。UndoEntry 的旧构造器保留，memoryUuid 默认为 null。UndoService 为非空最终事务生成 transactionId，并与该玩家的最多十条历史同步维护，空净差异不推进关联。

普通 Undo 在原世界身份通过后，向关联 UUID 写入 BuildUndoMemoryUpdate。totalPositions 是事务中不同的实际变化位置数，restoredPositions = totalPositions - remaining，是已确认达到撤销目标的位置数，包含原本已是目标的位置；不是本次写入数量，也不是整栋建筑方块总数。partial/重试使用同一个 transactionId，写入绝对进度，不累加计数。

元数据字段：build_undo_transaction、build_undo_state（pending/partial/restored）、build_undo_restored_positions、build_undo_total_positions。restored 仅表示该次建造实际变化位置全部达到 before 状态，不能推断整栋建筑消失。记忆不删除、不缩边界，已有 roof_modified 等 Patch 信息保留，原始生成 gene/bounds 不伪装成当前几何。提示词明确这些是登记设计记录，包含撤销进度，需核验当前世界。

MemoryManager.recordBuildUndo 先核验 UUID/维度，保存失败恢复缓存元数据和 lastModified 并返回 false。UndoService 留下最新待保存进度；每次同一玩家调用撤销入口都会重试，即使已经没有方块历史。保存失败也不重复恢复已经完成的方块，并向玩家提示记忆未保存。没有后台周期重试；缺失记录不会自动重建。服务停止时清空历史及待保存记录，因此同步失败应在退出前重试或核对，尚未实现持久化补偿队列。

世界写入、历史和 Memory 磁盘提交仍不是原子事务；普通 Undo 只保存进度，没有逆向恢复 gene，也未覆盖方块实体/邻居更新或直接修改后的当前建筑重建。详见 [方块执行契约](WORLD_MUTATION_CONTRACT.md)。

## 验证

MemoryDimensionTest 覆盖重叠坐标隔离、跨维度最近建筑、删除某维度索引不影响另一维度、缺失维度、Patch 目标识别、无归属删除和错误维度 mutation 拒绝。测试使用真实内存索引，不启动游戏、不访问玩家存档；新建记忆磁盘写入及真实游戏仍需验收。

第十一批补充 UndoServiceTest、BuildUndoMemoryTest 和 MemoryStorageTest：关联正确性、限额与玩家隔离、空事务、部分重试、保存失败/异常、缓存回退、Patch 元数据保留、存档路径隔离及临时文件清理。临时目录文件测试不访问玩家存档。
