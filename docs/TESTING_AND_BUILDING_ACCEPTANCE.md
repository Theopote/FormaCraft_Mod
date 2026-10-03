# 测试与建筑验收

状态：维护中；更新：2026-10-03（第六批）。

## 可复现的 Java 检查

仓库根执行 `./gradlew.bat check`（Unix 使用 `./gradlew check`）。依赖缓存完整时可加 `--offline`；首次需下载与 loader_version 相同版本的 fabric-loader-junit。该依赖仅用于测试，不改变生产 Loader 版本。

JUnit 通过官方 Fabric Loader JUnit 运行，而非直接用普通 JVM 启动 Minecraft 类。依赖方块注册表的测试使用 MinecraftRegistryTestBootstrap，在 BeforeAll 中调用 SharedConstants.createGameVersion 和 Bootstrap.initialize。初始化失败直接报错，不吞异常或跳过测试。历史普通 JUnit 下的 IllegalAccessError 不能靠重复 Bootstrap 解决。

依据：[Fabric 自动化测试文档](https://docs.fabricmc.net/develop/automatic-testing)。文档当前映射名称可能与 1.21.10 Yarn 不同，应以本仓库能编译运行的 API 为准。

`check` 覆盖 JUnit、assembly 示例校验、文化卡校验、生成器目录冻结及 18 个 assembly 评估案例。报告在 build/reports/tests/test/index.html；CI 保留 build/reports 和 build/test-results，无论构建成功还是失败。Java 分层检查另行执行 python scripts/test_check_architecture.py 和 python scripts/check_architecture.py。

本轮完整 Java 检查通过，资源校验仍有宏展开、styleId 桶等 warning，不等于已经消除所有资源语义漂移。Python 后端质量门及真实世界验收独立执行，不能用 Java check 代替。

## 下一步检查顺序

1. 世界修改与历史事务：状态字符串解析能否保留 facing、half、axis、waterlogged；预览与执行是否一致；setBlockState 失败是否被计作成功；越界和跳过是否准确；撤销/重做与 Memory 更新是否原子一致。优先审查 BuildTask、PatchExecutor、PatchHistoryManager 和服务端历史事务入口。
2. 最终建筑几何：为小屋、多层塔、四合院、带穹顶建筑保存描述、计划、编译诊断和最终方块快照。检查屋顶覆盖、入口可达、层间通行、窗柱冲突、凹龛位置及边界预算，而非仅判断 JSON 合法。
3. 规划与失败语义：比较 Python Research、Java typology、assembly 和兼容 BuildingSpec 的路由；检查同步 AI 规划是否阻塞健康检查、超时后的结果是否仍进入预览；再按阶段拆 ai_planner。

这些是待验收目标，目前不宣称已经具备全部自动检查能力。游戏内测试应先使用专门测试世界；本轮没有启动客户端、修改玩家存档或验收多人场景。

## 第七批执行检查

BuildTask/PatchExecutor 的成功计数、拒绝写入、边界读取与状态解析已建立回归；历史与 Memory 分析改用最终差异。详细行为和仍未解决的部分撤销、跨维度、原子性问题见 [方块执行契约](WORLD_MUTATION_CONTRACT.md)。可控世界测试不模拟真实邻居更新或方块实体。

## 第八批历史检查

Patch 与普通建造恢复共用可重试流程，测试覆盖部分恢复、原世界身份、冲突、重复位置及部分重做的新分支。失败或阻塞位置保留原栈，完成后才移动；Memory 只更新当前 Patch 实际恢复差异。世界停止清理运行时历史，真实游戏邻居更新、方块实体及普通建造 Undo 的 Memory 补偿仍需继续验收。

## 第九批历史异常检查

新增四个异常注入测试：写入前抛异常、写入后抛异常、写入后读取失败及异常 redo 后的新分支。恢复逐位置报告成功/失败，未确认结果在后续读取中补报真实差异。349 个 Java 测试全部通过，完整 check 通过；仍需真实游戏验收。普通建造 Memory 同步应先解决维度过滤与事务归属，不能根据撤销完成就删除整栋记忆。

## 第十批 Memory 维度检查

空间查询、最近查询、Patch 归属与 mutation 校验使用显式维度；提示词关键词候选也过滤维度。未登记方块的 remove 不创建记忆。新增 6 个回归，355 个 Java 测试全部通过，完整 check 通过。游戏验收需在主世界和下界相同坐标各建一栋建筑，检查局部 Patch、撤销/重做及提示词只引用当前维度；本批没有启动游戏或修改旧存档。第十批时普通建造 Undo 的 UUID 关联尚未实现，已在第十一批接入，见 [Memory 契约](MEMORY_TRANSACTION_CONTRACT.md)。

## 第十一批普通建造与存储检查

368 个 Java 测试全部通过，新增 13 项验证普通 Undo 记忆 UUID 关联、绝对进度、部分重试、空事务、历史限额、玩家隔离、保存失败/异常、缓存回退以及存档目录和临时文件清理。完整 check 通过，架构检查仍为 20 处既有技术债、0 新增。旧运行目录记忆的人工迁移规则见 [Memory 契约](MEMORY_TRANSACTION_CONTRACT.md)。

真实游戏待验收：两个存档分别建造后检查记忆隔离；建造后撤销检查同 UUID 的进度；部分恢复重试及保存失败提示；后续 Patch 的信息不会被普通撤销覆盖。单元测试未启动客户端、未修改玩家存档，尚未验收 NBT、邻居更新和多玩家真实世界场景。下一步优先回到建筑质量，检查多层楼板基准、屋顶覆盖、入口及层间通行。

## 第十二批主体楼层收边检查

ComponentPlanCompiler 向后处理提供成功生成的 MASS 范围，包含一次 slot 偏移；DetailRulePostProcessor 按各主体范围及各自层高匹配规则，只装饰位置最后的有效候选。新增回归覆盖低基础/高屋顶、不同主体/层高、主体顶面屋檐、重叠归属、已删除离群点、覆盖操作的预算，以及中心锚定和多主体实际编译后的最终方块。

本批范围为楼层线脚和收边位置。实体楼板、楼梯净空/平台连接和真实 Minecraft 路径搜索尚待验证；StairAssembler 的显式高度、地形跳级及朝向问题已登记到 [坐标契约](COMPONENT_COORDINATE_CONTRACT.md)。后续优先修复楼梯轨迹并补充可达性验收。

完整编译回归还暴露了通用角柱覆盖楼层线脚的问题。本批修复角柱/腰线覆盖楼梯、台阶、玻璃和门，修复已删除/空气位置仍被当作占用，以及材质子串匹配把 cobblestone_stairs/slab/wall 换成整块的问题。使用最终快照检查这些阶段之间的组合效果。

第十二批最终完整 check：381 个 Java 测试全部通过，0 失败、0 跳过；新增 13 项，18 个 assembly 案例及 43/23/37 项资源校验通过。架构检查仍为 20 处既有技术债、0 新增。尚未执行真实游戏通行或存档验收。

## 第十三批楼梯与状态检查

完整 check：403 个 Java 测试全通过，无失败或跳过；新增 22 项，18 个 assembly 案例和 43/23/37 项资源校验通过。架构检查 20 处既有技术债、0 新增，检查器 2 个测试通过。回归覆盖精确宽度、起止踏面、升降与缓坡、旋转、支撑和局部净空、规划校验、完整状态经 PatchExecutor 落地，以及旧装配器的地形/偏移/预算。

仍需在游戏中核验楼板开口、上下层平台、构件叠加后的净空、碰撞和邻居更新。StairAssembler 未接入主流程；当前测试不能证明 Skeleton 自动生成层间连接。契约见 [楼梯生成与通行](STAIR_CIRCULATION_CONTRACT.md)。

## 第十四批箱体与楼梯组合检查

完整 check：414 个 Java 测试全通过，无失败或跳过；新增 11 项。18 个 assembly 案例、43 份 assembly 示例、23 份文化卡和 37 个生成器键校验通过。架构边界检查 20 处已登记技术债、0 新增。

检查实际 SHELL_BOX 几何生成与组件编译后的楼梯组合：楼板/屋顶保留、扭转外墙保留、外部位置不清空、单独相位旋转，以及四向入口的楼板洞口和上层接续踏面。测试使用最终操作快照与可控材质适配器，未启动 ServerWorld 或验收真实玩家通行。输入顺序、其他主体路由、多个梯段相互覆盖、楼层平台自动规划和离散扭转空隙仍待检查。见 [箱体楼板契约](SHELL_FLOOR_CONTRACT.md)。

## 第十五批组合最终约束与状态落地检查

完整 check：425 个 Java 测试全通过，无失败或跳过；新增 11 项（组合约束 8、ASSEMBLY 状态桥接 3）。18 个 assembly 案例、43/23/37 项资源校验通过；架构检查 20 处既有技术债、0 新增，检查器 2 个测试通过。

覆盖上下梯段相互填堵/挖空、合法分开梯段与平台、最终状态恢复、禁用支撑/挖空、重复兼容梯段及旋转世界位置；既有四向箱体组合也运行最终约束。真实 PatchExecutor 配合可控访问验证 ASSEMBLY 方块朝向、倒置、形状、含水、半砖类型、轴向、坐标偏移与 remove 顺序。

测试未启动 ServerWorld；当前约束也不检查跨 ASSEMBLY 构件或后处理的覆盖、完整碰撞形状与全局可达性。ASSEMBLY 入口的 Conflict 到 capability gap 映射由调用链核验，未进行真实服务器入口验收。详见 [楼梯契约](STAIR_CIRCULATION_CONTRACT.md)。

## 第十六批完整计划与主预览转换检查

435 个 Java 测试全通过，无失败或跳过；新增 10 项。完整 check、18 个 assembly 案例及 43/23/37 项资源校验通过。架构检查仍为 20 处已登记技术债、0 新增，检查器 2 项通过。

真实编译与默认后处理配合具名测试生成器，验证跨构件挖空/填堵、不同 slot、一次偏移、恢复后的最终状态、默认檐口避开净空、混合计划拒绝和嵌套作用域恢复。另有后处理追加冲突案例；主转换测试验证完整楼梯属性与真实 PatchExecutor 一致、remove 语义、未知动作/方块及非法属性拒绝。

没有启动真实服务器或玩家网络预览。预览随后添加的地坪/地基、PlanProgram/BuildingMass、其他楼梯生成器、全局碰撞/可达性尚未验收；普通非法预览 patch 仍按计数跳过。边界见 [完整计划契约](PLAN_CIRCULATION_CONTRACT.md)。

## 第十七批最终预览检查

442 个 Java 测试全通过，无失败或跳过；新增 7 项（预览末端 6、显式编译结果 1）。完整 check、18 个 assembly 案例与 43/23/37 项资源校验通过；架构检查 20 处已登记技术债、0 新增，检查器 2 项通过。

回归检查地形准备先于建筑的覆盖顺序与真实无世界去重、模拟后续支撑堵住净空、真实选区裁剪删除踏面/空气、世界锚点一次偏移、无元数据兼容，以及成功/失败编译结果要求不可变且不残留。最终冲突进入 FATAL 质量报告，拒绝交付。未启动 ServerWorld 或真实网络预览，自动补支撑和碰撞仍未游戏验收；不自动调整支撑或放宽裁剪约束。详见 [完整计划契约](PLAN_CIRCULATION_CONTRACT.md)。

## 第十八批塔楼检查

451 个 Java 测试全通过，无失败或跳过；新增 9 项。完整 check、18 个 assembly 案例与 43/23/37 项资源校验通过。架构检查 20 处已登记技术债、0 新增，检查器 2 项通过。

验证楼层数拒绝、地面层与请求楼板数、连续八点环路、转角平台、朝向、两格空气、真实 TowerGenerator 的楼板洞口与屋顶保留，以及关闭楼梯/单层行为。没有启动 ServerWorld，不能证明邻居更新、碰撞或完整入口可达性；塔楼要求还未经过整栋转构件坐标桥接，最终预览保护仍待接入。见 [塔楼契约](TOWER_CIRCULATION_CONTRACT.md)。
