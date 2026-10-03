# Memory 查询与事务契约

状态：维护中；核查：2026-10-03（第十批）。

## 维度是空间身份的一部分

SpatialIndex.findAt/findNearest、MemoryManager.findAtPosition/findNearest、PatchDiffAnalyzer.analyze 和 MemoryManager.applyMutation 必须显式传入 Identifier dimension；不再提供只传坐标或默认主世界的入口。区块候选索引仍按坐标组织，返回结果前严格过滤 SpatialBounds.dimension。相同坐标在不同维度可各有建筑，不应相互修改。

PatchHistoryManager 从执行的 ServerWorld 获取维度，正向、undo、redo 分析和 mutation 写入使用同一个维度。applyMutation 对已有 UUID 再次检查维度，拒绝跨维度或缺失维度的记录；新建 Patch 记忆使用执行维度，不再固定主世界。

PromptMemorySections 从当前客户端世界获取维度：空间与最近查询传入维度，关键词语义搜索的最终候选也按维度过滤，再截取五条。无法取得客户端世界时不注入记忆。该路径仍依赖客户端及单例服务器 MemoryManager；尚未实现独立多人服务器的记忆传输，也未解决异步规划期间切换世界的问题。

## 旧数据与删除语义

本批不重写磁盘旧记忆。已有明确维度字段按原值使用；维度缺失、无效或不匹配的记录不会被空间查询和提示词当作当前世界建筑，mutation 也拒绝修改。历史版本把下界建筑错误标成主世界的记录无法仅凭坐标自动判定，需要核对原始世界后修复。语义检索底层仍可查到这些记录，调用者必须根据用途过滤。

删除未登记位置的方块不产生 CREATE 影响，避免拆除后凭空新增建筑记忆。已登记建筑的局部删除仍标记 REMOVE/partially_removed；没有根据少量移除方块就删除整栋记忆。当前重叠建筑仍选择第一个匹配结果，扩建边界归属也待完善。

## 普通建造撤销的后续接入

普通建造 Undo 尚未更新 Memory。本批先消除维度串扰；后续应把 registerBuilding 返回的 UUID 与对应建造事务关联，区分未登记的部分建造、局部撤销、完整恢复以及后续 Patch 修改。撤销不能只凭包围盒删除所有附近记忆，也不能把 restored count 当作整栋建筑被移除的证据。

世界写入、历史和 Memory 磁盘提交仍不是原子事务。详见 [方块执行契约](WORLD_MUTATION_CONTRACT.md)。

## 验证

MemoryDimensionTest 覆盖重叠坐标隔离、跨维度最近建筑、删除某维度索引不影响另一维度、缺失维度、Patch 目标识别、无归属删除和错误维度 mutation 拒绝。测试使用真实内存索引，不启动游戏、不访问玩家存档；新建记忆磁盘写入及真实游戏仍需验收。
