# 构件库离线链路验收

入口：`scripts/verify-component-assets.ps1 -Offline`，其中 ComponentLibraryAcceptanceTest 使用真实临时 JSON 目录和目录索引，不注入检索候选或批量规划结果。

## 样例资产

| 资产 | 原宽 | 目标宽 | 套系 | 变化规则 |
| --- | --- | --- | --- | --- |
| accept_window_a | 5 | 6 | demo/courtyard | X 重复中段，两端各固定 1 层 |
| accept_railing_a | 5 | 6 | demo/courtyard | 同上 |
| accept_window_other | 6 | 6 | demo/window | 全轴锁定 |
| accept_railing_other | 6 | 6 | demo/railing | 全轴锁定 |

所有资产高 3 格、厚 1 格；两端使用不同材料，以验证变化后端部保留。窗中段包含玻璃。这些是几何与流程验收样例，不是经过美术评审的成品。

## 覆盖流程

1. 在 JUnit 临时目录写入四个真实构件 JSON 和 catalog.json，仅覆盖当前测试的目录入口。
2. 用真实 ComponentRetriever 检索，独立选择时优先命中原尺寸的不同套系构件。
3. 用真实 ComponentLibraryBatchContext 和批量规划器处理同一建筑的窗、栏杆查询，得到协调的 courtyard 套系。
4. 通过 PlayerComponentExpander.resolveSingleRequest 消费预选结果，检查目标宽度、来源标识、套系标签及端部材料。
5. 使用公共几何输出器生成向东的 Patch，与服务端预览输出逐项比较。
6. 校验原件文件内容没有变化。另用宽 2 格的不可适配需求检查 ADAPT_FAILED 不被改为无候选。

测试同时覆盖规则能力元数据的非空值和变体保留 CAPTURED 来源，防止真实检索路径与注入候选测试之间出现缺口。

## 边界

这是磁盘目录、真实检索、适配、协调、展开结果消费和共享 Patch 输出的离线验证，没有启动 ServerWorld，也不写入真实世界。尚不能替代游戏内宿主安装、清洞、碰撞、交互布局和最终审美验收。测试结束恢复目录入口，不向玩家实际构件库安装资源。
最终验证：3 项真实目录验收与其余构件回归共 59 项测试全部通过；首次运行暴露的规则能力空指针已修复。变体来源、原件不变与预览一致性检查均通过。
