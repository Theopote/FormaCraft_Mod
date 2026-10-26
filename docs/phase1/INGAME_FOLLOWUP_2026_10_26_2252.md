# 2026-10-26 22:52–23:16 实测回归

来源：`run/logs/latest.log`，Player397。六份后端响应保存在 `src/test/resources/regressions/live-2250-*.json`。本轮是新返回方案，不能用此前不同方案的测试通过来替代验收。

## 已定位及修改

1. **22:54 增量开窗失败**：FACADE_WINDOWS 将玻璃写入 `wall_block`，没有 `window_block`。独立 2×1×2 窗继续进入需要主体立面上下文的生成器而输出为空。现在有界单窗识别 `glass_block/glazing_block/window_block/material/wall_block` 中的明确玻璃材质，生成原位置的 4 格补丁。普通原生门窗/屋顶的纯语义 `component_query` 不再触发必需库检索；显式捕获 ID 和 CAPTURED_LIBRARY 仍保留独立契约。
2. **错误误标**：`required_component_empty` 被笼统包装成 `E_COMPONENT_LIBRARY_FAILED`，播放器标题还固定写 ASSEMBLY。原生空结果现在单独归类为 `E_COMPONENT_GENERATION_FAILED`，提示检查原生门窗尺寸/材质/宿主，不要求准备库资产。
3. **22:56 高脚木柱断一格及石柱**：本轮使用 `role=stilt_post`、`material_block=oak_log`。旧逻辑只认 column/stilt_piling，导致装饰三格限高、材质漏取和自动补撑。共享 StructuralSupportPolicy 统一 column/pillar/post/stilt_post/stilt_piling/support_column 及 part=column，原生柱按完整高度及明确柱材生成；地形适应识别已建模支撑，跳过额外补柱。
4. **23:00 木亭封闭**：本轮使用 `open_pavilion=true`、`wall_style=none`、`open_sides`。OpenFramePolicy 已补齐这些声明。角柱使用 pillar_block；开敞连廊沿用体块小范围接合处理，适用于 LINEAR_PATH，不限于 COMPOUND。接合重叠率只统计实体放置，空气清理不再错误计入实体覆盖比例；相邻亭子的地板和柱子保留。
5. **23:10 双塔楼梯断链**：每塔三段都是相同水平起终点、不同高度的同向梯段。StairChainCompiler 将这种明确重复断链的原生梯段排成两条交替梯道，转弯处生成真实平台，保留每段的起止高度及宿主边界。只处理 COMPONENTS 权威，已明确设计的连接楼梯和捕获几何不替换。最终后处理完成后，检查两格净空的步行表面能否从底层逐格走到最高终点；失败报 `E_STAIR_CHAIN_UNCONNECTED`，不再以“观察到楼梯高度”冒充连通性证明。
6. **23:07–23:08 三次封窗一直“记忆未就绪”**：MemoryManager 绑定只写在 DedicatedServerModInitializer，单机不会执行。现在由公共 BuildExecutionService 生命周期在 SERVER_STARTED 初始化，独立服务器删除重复初始化；SERVER_STOPPED 继续清理，避免跨存档引用旧记忆。

## 验证范围

Live2250RegressionTest 覆盖本轮六份返回：4 格开窗、原生门窗脱离语义库查询、实际木柱连续性/支撑识别、三亭四面空气、最新徽派现场窗格封回白墙、双塔最终方块从底到顶的步行 BFS。另有显式捕获 ID 保留和人为删掉转向层后的连通拒绝负例，已加入 constraintParityTest。

这些是代码编译与离线方块回放；本轮未操作玩家世界。需要重启开发客户端，在单机启动日志确认 Memory 初始化，再建一栋新测试建筑验证登记、局部修改和 Undo/Redo。此前因单机初始化遗漏而没有登记的旧测试建筑不会自动获得建筑记忆；本次也不会自动修复旧世界里的错误方块。

最终验证：目标 test 138 项通过，constraintParityTest 152 项通过，userStylePackTest 4 项通过；使用独立 Gradle 项目缓存与输出目录，`git diff --check` 通过。

后端日志还含建筑研究 15 秒超时后跳过的信息；它与本轮已经成功返回、但几何表现不正确的双塔不同，不以延长超时替代几何修复。
