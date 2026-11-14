# 架构审计 — 当前状态

状态：维护中；复核：2026-11-14。

历史分批记录已移至 [archive/ARCHITECTURE_AUDIT_batches_through_2026-10-25.md](archive/ARCHITECTURE_AUDIT_batches_through_2026-10-25.md)。以本页与门禁脚本为准，不以历史「债务 0」表述为准。

## 当前门禁

| 检查 | 命令 | 期望 |
|---|---|---|
| 包边界 + common 服务端类型冻结 | `python scripts/check_architecture.py` | exit 0；新增引用必须先消债再改基线 |
| StructureGenerator 冻结 | `python scripts/check_structure_generator_freeze.py` | register 调用数 = baseline |
| Panel 行数（软） | `python scripts/check_panel_line_budget.py` | 仅打印超标 |
| 生成入口 | `python scripts/check_generation_entrypoints.py` | exit 0 |

`config/architecture-debt.json` 现登记 common 内既有的 `ServerWorld` / `ServerPlayerEntity` / `MinecraftServer` 引用；**禁止新增**。包引用 `com.formacraft.server` 在 common 中应保持为 0。

## 未决项（优先）

- 编译状态继续由 ThreadLocal 迁至显式参数；路由器的逐字段 setter/clearer 已删除，统一使用 `GenerationCompileContext`。世界只读快照后，计划编译可完全离开主线程
- `ai_planner.py` 继续按阶段拆分
- Panel 超 400 行拆分（Settings / Chat / ComponentCapture）
- 双 JSON 库收敛（新代码优先 `common.json.JsonUtil` / Gson）；服务端硬编码中文文案迁语言文件（建造门控文案已走 lang）
- Memory 落盘与世界修改原子性；Undo 双轨统一（见 WORLD_MUTATION_CONTRACT）

## 已落地（2026-10-31 审查整改）

- `ExplicitDesignPolicy.isExplicitlyDisabled` 下沉 common，架构门禁恢复
- 后端默认 `127.0.0.1` + `FORMACRAFT_ORCHESTRATOR_SECRET` / `X-FormaCraft-Secret`
- `/models` 走 `url_safety`；日志打码 `searchApiKey`；健康检查缓存
- 聊天建造权限 / 限流 / 并发；`GenerationCompileContext` + `PlanCompileWorkers`
- 提示词组装迁至 Python 后端，已删除 Java `PromptAssembler` 和旧地标 BuildingSpec 模板生成函数
- 能力矩阵见 [GENERATION_CAPABILITY_MATRIX.md](GENERATION_CAPABILITY_MATRIX.md)；边界见 [PYTHON_JAVA_PLANNING_BOUNDARY.md](../PYTHON_JAVA_PLANNING_BOUNDARY.md)

## 编译上下文迁移（2026-11-14）

`UnifiedGeneratorRouter` 已移除 `set/clearTypologyExclusivePlan`、`set/clearPlanGenerationAuthority` 和 `set/clearPlanMeta` 六个旧入口。调用方通过 `GenerationCompileContext.runWith` 安装完整上下文；编译器在组件生成结束、能力缺口返回或异常退出时恢复进入前的上下文。当前上下文存储仍使用 ThreadLocal。

回归覆盖路由权限、typology 排他规则，以及真实编译的成功、能力缺口和异常退出后的外层状态恢复。

几何缓存的生产编译入口改用 `BuildingGeometryContext.open()`，通过 try-with-resources 隔离每层缓存并在异常退出后恢复外层状态。旧 begin/end 调用已迁移并删除，缓存底层仍使用 ThreadLocal。

编译上下文已删除无调用者的 withAuthority、withPlanMeta、withTypologyExclusive 和 clear 方法。planMeta 保存独立的顶层 Map 快照，保留空值；嵌套集合仍沿用原引用。回归验证源 Map 修改隔离、只读访问和嵌套异常恢复。

几何缓存已合并为一个可空的线程状态。作用域外的生成只解析几何，不写缓存；最外层作用域关闭时移除线程引用，嵌套关闭仍恢复外层缓存。

## 隔离路径清理（2026-11-14）

已核对旧 AutoAssembler、ClusterLayoutPlanner 和 ConfirmBuildPacket 的删除状态，修正架构概览中的待删除列表。BuildingMassSystemTest 仍由服务端命令调用，已迁至 server.command.diagnostic，保留原诊断行为。common.mass 中的实际调用链继续冻结迁移。

BuildingMassSystemIntegrator 已删除未使用的 ServerWorld 参数，预览入口改为只传计划和世界锚点。对应 common 服务端类型债务减少 3 处；显式启用条件和生成行为沿用原实现。

LandmarkRoutingMetrics、TypologyRoutingMetrics、LlmPlanRoutingMetrics 改为接收玩家名称字符串，服务端调用方负责读取名称。指标类不再依赖 ServerPlayerEntity，保留缺失玩家时的原日志占位符，边界债务再减少 18 处，剩余 65 处。

FcaLog 已删除接收玩家实体的 player 方法，服务端唯一调用点复用 playerId(String)。日志工具不再依赖服务端玩家类型，边界债务再减少 3 处，剩余 62 处。

SocketFinder 的三个空实现查找方法及 SocketProviders.findFromInstances 包装链已删除，无业务调用者。保留工具上下文收集与排序，世界实例扫描仍待实现。对应边界债务减少 8 处，剩余 54 处。

SkeletonSocketGenerator 无运行时、测试或构建调用者，且未从骨架填充查询几何，已删除并标注历史文档。边界债务减少 3 处，剩余 51 处。

地形后处理改为接收世界坐标到地面高度的查询函数，世界读取由服务端编译器提供。common 后处理管道和处理器已移除 ServerWorld 依赖，地形策略与每列采样顺序保留，边界债务减少 6 处，剩余 45 处。

PlanCompileContext.TerrainContext 已移除无读取者的世界字段，创建接口只接收锚点并保存不可变坐标。原有 ADAPTIVE/FLAT 策略选择仍由调用方保留。对应边界债务减少 5 处，剩余 40 处。

真实世界状态/NBT 读写适配器已从 common 接口迁到 server.world.ServerBlockMutationAccess，WorldBuildBounds 同迁服务端。common 保留抽象操作及事务执行，删除无调用者的 PatchExecutor.apply(ServerWorld, ...) 入口。写世界门禁仅允许新的服务端适配器路径；对应债务减少 11 处，剩余 29 处。

PlanProgramCompiler 已迁至 server.compiler，调用方和同包原子性测试同步迁移，更新生成入口清单与活文档。骨架转换、累计预算及失败协议保持原实现。对应边界债务减少 9 处，剩余 20 处。

SkeletonExecutor 和 SkeletonExecutors 已迁至 server.skeleton，服务端编译、路由和启动注册同步更新，骨架计划 DTO 继续位于 common。对应边界债务减少 4 处，剩余 16 处。

PlayerComponentExpander 和 PlayerComponentGroupExpander 改为接收存档目录与随机种子，由服务端路由提取；common 中的几何、挂载和裁剪算法保留。对应边界债务减少 10 处，剩余 6 处。

## 原则

AI 规划、确定性编译、BlockPatch 唯一写入。完成报告不能代替运行事实。
