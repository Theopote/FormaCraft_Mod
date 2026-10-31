# 架构审计与重构路线

状态：维护中；当前状态复核：2026-10-31。`PlayerComponentExpander` → `UnifiedGeneratorRouter` 包引用已下沉到 `ExplicitDesignPolicy.isExplicitlyDisabled`；跑 `python scripts/check_architecture.py` 应以 exit 0 为准。下方分批记录保留当时结果，不代表当前债务数量。

第一轮覆盖源码目录、构建和 CI、请求入口、生成主流程、分层依赖及文档入口。第二轮恢复测试编译、修复相关行为并迁移解释器初始化；不代表已经逐类验证建筑几何或完成游戏内验收。初始基线为 1050 个 Java 源文件、88 个 Java 测试文件、57 个 Python 应用文件、46 个 Python 测试文件、223 篇 docs 文档。

## 已核实的问题

| 优先级 | 证据 | 影响与处理 |
|---|---|---|
| P0 | 第一轮测试编译失败；第八批完整执行 345 测试，全部通过 | 编译漂移及基础/立面/屋顶本批拼接问题已修复，冠部层数与楼层顶圈也已修复，本批修复迁移条目的旧 MODULE 提示与八边形凹龛方向，第六批明确半球协议、接入 Fabric JUnit 并修复类型桥接丢失 BlockState 属性；详见 [回归清单](REGRESSION_BASELINE.md) |
| 已完成 | `PatchHistoryManager` 已迁至 `server/build` | 历史编排、账本及 Memory 更新位于服务端；`architecture-debt.json` 为空。包引用门禁以脚本实时结果为准，勿以历史批次「0 新增」代替 |
| P1 | `common/patch/PatchExecutor.java` 仍暴露 ServerWorld 适配入口 | 核心执行已委托 BlockMutationAccess；边界基线清零不代表 common 完全不依赖 Minecraft 服务端类型 |
| P1 | BuildTask/PatchExecutor 原先忽略写入返回值；历史按尝试列表更新 Memory | 本批修复实际应用结果、严格目标解析及最终差异；第八批进一步修复部分恢复保留/重试、世界身份与冲突保护；普通 Undo Memory 更新、异常补偿与 Memory 原子性仍待处理，详见 [方块执行契约](../WORLD_MUTATION_CONTRACT.md) |
| 已修复入口阻塞 | `/build` 同步分发与编码在独立有界线程池执行 | 满额 503，总超时 504，断连丢弃结果；同步线程不能强杀，实际结束前持续占用额度。并发、健康检查、取消及额度恢复有离线回归 |
| P1 | `python_backend/app/services/ai_planner.py` 仍集中大量规划逻辑 | 意图、检索、提示词、调用、修复、回退混在一起；按阶段抽取，保留公开 generate_* 门面，先保持输出和失败语义 |
| P2 | `server/build/PathLayoutService.java` 无源码调用者，extractPathPoints 始终返回 null | 本轮删除不可工作的占位服务；不是删除真实路径工具或路径生成器 |
| P2 | `server/skeleton/gen/geometry/ToolConstraintBuilder.java` 无源码调用者，却读取客户端全局状态 | 本轮移到 `client/skeleton`；服务端未来只接受请求快照，不读取客户端状态 |
| P2 | ARCHITECTURE 将 ComponentPlanCompiler 写成 common.compiler，实际位于 server.compiler | 本轮纠正文档，区分主干与仍存在的兼容入口 |
| P2 | 文档写 agent 环境不能编译，README 写 Gradle 8+，Wrapper 实际为 9.1.0 | 本轮更新；本机基线 compileJava --offline 成功，不能以历史环境限制代替验证 |
| P2 | 历史文档宣称 PathLayoutService 已集成，但代码没有调用者 | 完成报告不能作为运行事实；UNUSED_FILES_ANALYSIS 标记为历史参考，禁止照着旧建议恢复第二套入口 |

## 已实施的边界防回归

`python scripts/check_architecture.py` 检查 common → client/server、server → client 的导入和完全限定引用，忽略注释与字符串。CI 会运行检查器测试与检查。

`config/architecture-debt.json` 当前为空；PatchHistoryManager 已迁至 server/build，原有包引用债务已清零。新增引用导致失败，消除引用后必须同步缩减基线；不得为了通过 CI 扩大基线。它只检查源码包引用，不证明运行时隔离，不覆盖反射、间接依赖、Minecraft 服务端类型或世界写入权限。

## 按依赖顺序推进

1. **建立事实基线**：本轮审计、新人入口、边界门。保留现有建筑生成能力，明确未验证项。
2. **恢复测试并收敛依赖方向**：先修复既存测试 API 漂移；服务端注册 TypologyInterpreter；迁移 Patch 执行和历史；common 留计划、几何、接口与结果。验收：分层基线归零，Java check、patch 与 typology 回归通过。
3. **拆规划器**：划分 intent/retrieval/prompt/inference/normalization/validation/fallback；隔离同步执行；所有入口共用结果判别与错误语义。验收：离线质量门、路由契约、并发请求测试通过。
4. **收敛建筑表达**：LlmPlan 继续作外部主协议；明确 components、plan_program、patch、typology、assembly 的适用范围。优先建立单位、坐标、锚点、方向、版本和能力契约，禁止为了简化类名删除有效表达能力。
5. **建筑质量闭环**：同一代表案例保存描述、约束、计划、编译诊断、最终方块统计和预览图；把建筑关系与形态质量纳入验收。
6. **归档与清理**：逐篇按代码核查活文档。删除类前检查代码、资源/反射注册、存档兼容和测试；历史报告仅供追溯。

## 建筑生成是长期主线

建筑应有层级与关系：场地 → 建筑群 → 单体体量 → 楼层/空间 → 屋顶/立面 → 构件/材质。仅堆组件或匹配建筑关键词无法保证空间与结构正确。

目标是让 AI 负责意图与可解释规划，让确定性编译器负责几何与方块执行。优先复用已有 typology、几何原语、assembly、材质和约束能力，研究新模型/检索方案前先比较现有基线。

质量指标需区分硬约束和偏好：硬约束含选区、禁区、世界高度、尺寸预算与协议合法性；质量含入口可达性、层间通行、屋顶覆盖、构件连接、比例、立面节奏、风格一致性与描述符合度。不能把“JSON 合法”当作“建筑好看且可用”。具体自动判定能力必须随实现逐项标注，不能预先宣称已支持。

最小验收集合：带门窗小屋、多层方塔、四合院、天坛、屋顶增量修改；再加入受限地块、禁区、斜坡和多人并发。每次记录预览、确认落地、撤销/重做、Memory 更新和失败时是否改动世界。结构回归需检查最终几何，不能只看计划或截图。

## 验证与限制

- 主代码 `compileJava --offline`：通过（包含客户端类迁移后的编译）。
- `evalAssemblyCases`：18/18 通过。
- `scripts/test_check_architecture.py`：2/2 通过；边界检查：20 处已登记技术债，0 新增。
- `git diff --check`：通过。
- 第二轮已修复旧构造/枚举/方法与断言导入错误，compileTestJava 通过。33 处直接 LlmPlan 测试构造调用迁到具名 fixture，避免再次发生位置字段漂移。
- 定向回归（对齐、Socket、响应协议、自由几何 Prompt、解释器注册、guard、capability_gap、特征传递、锚点规范化）：通过。
- 第十八批完整 `check --offline`：451 个测试全部通过，无跳过；check 通过，43 份 assembly 示例、23 份文化卡和 37 个生成器键校验均通过。本批新增 9 个塔楼楼层与楼梯案例。当前结果与问题分类见 [回归清单](REGRESSION_BASELINE.md)。没有禁用测试或降低失败门槛。
- Python 默认解释器缺 fastapi，完整后端回归需安装锁定依赖后运行；本轮不把缺依赖记录成代码测试失败。

尚未执行游戏内验收、模型联网生成、多玩家或真实世界存档回归。本轮不宣称完成全面重构或所有文档的逐篇核查。

## 第十一批补充：记忆存储与普通建造历史

已修复 MemoryStorage 忽略 server、使用进程全局目录的问题。记忆按当前存档目录保存；旧全局目录需要核对归属后人工迁移。普通建造历史关联 registerBuilding 返回 UUID，恢复进度以独立元数据保存，失败可在后续命令重试，不用包围盒推断建筑删除。当前保持 common 历史中的 20 处跨层技术债；Memory 磁盘与世界修改不是原子事务，待保存队列不跨重启持久化。详见 [Memory 契约](../MEMORY_TRANSACTION_CONTRACT.md)。

## 第十二批补充：主体几何身份进入后处理

PostProcessContext 新增不可变 BuildingVolume 列表，保存成功生成 MASS 的局部范围、slot 身份及层高，保留旧三参数构造器。DetailRulePostProcessor 使用主体范围定位收边，不再让基础与高屋顶影响该路径的楼层基准；原始 patch 次序仍保留，只有最后候选操作参与替换。完整编译回归同时发现并修复通用装饰覆盖已生成形状、移除位置被旧占用复填及材质变化破坏形状的问题。这个范围模型尚未覆盖每条 patch 的来源、复杂多边形/曲面和非 MASS 路由。实体楼板与 StairAssembler 轨迹是下一步建筑质量重点，不能以线脚定位修复宣称多层建筑已可通行。

## 第十三批补充：楼梯入口的接线证据

注册的 StairAssembler 没有主流程调用，主流程的 Skeleton 语义生成与 assembly STAIR_SYSTEM 是不同入口。修复实际 assembly 梯段和 Skeleton 属性桥接，同时保留旧装配器的局部修复，避免把注册误认为实现了楼层连接。下一步应核验实体楼板、平台、洞口与构件组合次序，再定义共同的通行约束；详见 [楼梯契约](../STAIR_CIRCULATION_CONTRACT.md)。当前没有自动楼层端点解析或全局可达性检查。

## 第十四批补充：主体内部清空与实体楼板

SHELL_BOX 的内部清空原来位于楼板之后，导致中间楼板被删除；扭转包围盒清空同时删除外墙。生成逻辑提取为 AssemblyShellOps，仍由原执行器和原适配器调用；先按每层转换后的内部样本清空，再铺结构，保留真实楼板。四向组件编译组合测试证明后续 STAIR_SYSTEM 能切出局部洞口并保留上层接续踏面。输入顺序仍决定覆盖结果，没有全局构件依赖排序；其他主体生成器与自动平台尚未统一。见 [楼板契约](../SHELL_FLOOR_CONTRACT.md)。

## 第十五批补充：组合约束不能停留在单个构件

同一次 assembly 的梯段先捕获实际占用/净空世界坐标，全部操作完成后按最终状态复查，拒绝被删除的占用与被堵的净空。ASSEMBLY 入口报告专用 capability gap，并修复 PlannedBlock 转 BlockPatch 只保存 ID 的属性丢失问题。校验只约束空气/非空气及同一次执行，尚不覆盖主计划后处理、跨构件覆盖、碰撞形状、方向相容或楼层可达图；后续应把楼层、洞口、平台与梯段身份扩展到完整计划，避免把局部合格误认为整体可达。见 [楼梯契约](../STAIR_CIRCULATION_CONTRACT.md)。

## 第十六批补充：要求进入完整构件计划

捕获作用域适配现有 List<BlockPatch> 生成接口，将成功 assembly 的要求收集到当前编译，slot 偏移一次后进入最终检查和后处理净空保护。修复默认檐口填回楼梯净空、混合计划忽略 ASSEMBLY 失败，以及主预览丢失状态/忽略 remove 的问题。预览和执行共用严格目标解析，避免相同 patch 在不同入口获得不同方块状态。作用域仅覆盖同步生成，长期应改为显式生成结果携带范围与要求；预览后续地形追加、其他生成器与 PlanProgram 路由仍需接入最终要求。详见 [完整计划契约](../PLAN_CIRCULATION_CONTRACT.md)。

## 第十七批补充：显式编译结果到最终预览

Compilation 将 patch 与成功的计划局部梯段要求一起交给主预览；要求在世界偏移一次后传给 BuildPreviewPipeline，在自动修复和硬裁剪之后检查。地坪/地基准备原本就先于建筑操作，这次保留该顺序，补上后续修复/裁剪的最终拒绝规则。没有元数据的路由仍沿用原行为；生成器内部的同步捕获适配尚未替换成通用生成结果。真实地形与碰撞仍需验收，见 [完整计划契约](../PLAN_CIRCULATION_CONTRACT.md)。

## 第十八批补充：整栋塔楼与其他路由（历史检查）

TowerGenerator 修复实际不相邻的螺旋踏步与不可能楼层数，分离 TowerStairBuilder 在结构完成后切出踏面与两格空气。本次生成内部校验不等于最终预览保护：StructureGeneratorAdaptor 的要求坐标桥接仍缺失。PlanProgram 的 SkeletonBuildService 默认语义路由已核对，旧 StairAssembler 仍未被调用；逐 skeleton 失败后继续合并会形成部分结果，下一步需统一这条路径的失败和要求协议。见 [塔楼契约](../TOWER_CIRCULATION_CONTRACT.md)。

## 第十九批补充：整栋适配与骨架失败边界

上述塔楼构件桥接与逐骨架部分输出问题已修复。StructurePatchBridge 显式返回不可变 patch 与局部通行要求，适配器通过嵌套作用域隔离原始入口坐标；完整状态与要求指定的挖空操作共同保留。通用生成器接口仍只返回 patch，捕获适配尚未替换为统一结果协议，独立整栋和 PlanProgram 元数据仍缺失。

PlanProgram 合并改为任一骨架生成异常、空输出或非法目标状态即停止，以具名异常阻止 JSON 兼容回退吞掉执行失败。主预览异常关闭心跳。458 个 Java 测试及完整 check 通过，架构边界仍为 20 处已登记技术债、0 新增；真实世界执行与碰撞仍待验收。

## 第二十批补充：生成结果跨越独立预览边界

StructureGenerationResult 显式保存结构和不可变世界通行要求，在同步生成边界适配捕获。四个独立网络预览入口已接入交付层和末端复查；坐标不再重复偏移。没有发布要求的生成器保持空元数据，通用生成器接口、PlanProgram 要求产出与直接排队路径仍需后续处理。城市 DRAPE 的方块与要求共同转换尚未实现，冲突时拒绝预览。464 个 Java 测试与完整 check 通过，边界检查仍为 20 处既有技术债、0 新增。见 [整栋结果协议](../STRUCTURE_GENERATION_RESULT.md)。

## 第二十一批补充：共用道路几何

语义默认路由和旧 LINEAR_PATH 回退路由改用 LinearPathLayout，消除重复宽度/高度算法，保留各自状态与边饰输出。生成前预算检查取代旧路径截断；高度按实际世界底界与高度尺寸计算顶界。472 个 Java 测试与完整 check 通过，架构仍为 20 处既有技术债、0 新增。共用布局只统一几何，不构成通行保证；后续几何修饰器预算与楼梯产出仍需处理。见 [道路布局契约](../LINEAR_PATH_CONTRACT.md)。

## 第二十二批补充：骨架修饰阶段边界

GeometryModifierPipeline 提供带预算接口，主 SkeletonBuildPipeline 使用它并检查最终高度。覆盖语义按同位置最后操作，输出次序稳定，位置索引消除逐点查找。旧通用接口兼容；主路径不使用对称分支，镜像点默认 WALL 与朝向转换仍是技术债。478 个 Java 测试及完整 check 通过，架构仍为 20 处已登记技术债、0 新增。详见 [骨架几何契约](../SKELETON_GEOMETRY_CONTRACT.md)。

## 第二十三批补充：全计划预算与镜像来源

PlanProgram 合并层建立整份 patch 上限，失败继续采用原子拒绝协议。镜像副本保存源语义、反射朝向，不再默认变成 WALL；显式操作优先，并复查镜像约束。主骨架路径仍没有接入对称处理器，本批修复通用对称分支。486 个 Java 测试和完整 check 通过，架构边界仍为 20 处既有技术债、0 新增。完整方块状态镜像与剩余预算下传仍需处理。

## 第二十四批补充：客户端后端生命周期控制

后端运行控制保持在 client.backend，设置面板新增独立 SettingsBackendSection，不把进程管理放入 UI 绘制。LocalBackendLaunch 集中校验本机地址、端口与参数构造；启动操作使用配置快照，健康检查、探测和停止在后台完成。自动和手动启动共用进程与操作锁，手动停止暂停自动重试；外部健康服务不取得所有权。493 个 Java 回归与完整 check 通过，包边界 20 处既有技术债、0 新增。首次依赖安装、后端分发和真实游戏启停仍需准备或验收。

## 第二十五批补充：构件存储安全与确认契约

审查报告 P0/P1 落地：

- **构件 ID / 路径**：`ComponentIds` 白名单 `[a-z0-9][a-z0-9_-]{0,63}`，`resolveComponentFile` 做 normalize + `startsWith` 边界检查；`ComponentValidator` 与之对齐。加载路径对历史含点号 ID 宽松，保存强制新规则。
- **验证阻断**：`saveComponent` 在副本上 autofix；`ValidationResult` ERROR 抛 `InvalidComponentException`，禁止落盘；原子写（temp + move）；失败向上抛出，网络 ACK 与磁盘一致。
- **载荷限制**：JSON ≤ 2MB、blocks ≤ 10000（与 GeometryValidator 量级一致）；缩略图沿用 1MB。
- **多人命名空间**：Dedicated 非 OP 写入 `components/players/<uuid>/`；OP / 集成服写全局；玩家 ID 与全局冲突则拒绝。Catalog 合并：legacy &lt; global &lt; player。
- **预览确认**：`PreviewStorage` 记录维度 + origin + SHA-256 digest；`/forma_confirm` 校验后 `consumeStructure` 一次性消费。Patch 路径继续用 `PreviewTicketStorage.consume`。
- **过滤统一**：`ServerPatchFilter` 与 Build 共享 `MutationRegionPolicy.allow` 核心区域约束；见 [方块执行契约](../WORLD_MUTATION_CONTRACT.md)「预览过滤 = 建造过滤」。
- **回归测试**：`ComponentIdsTest`、`ComponentStorageSecurityTest`、`PreviewTicketStorageTest`、`PreviewStorageDigestTest`、`AssemblyCompositionBoundaryTest`（组合边界矩阵）。
- **调查**：`BuildPreviewPipeline` / `allowPreview` 失败时返回错误、不自动重新生成；未发现「校验失败转重新生成」旁路。

历史文档中的 `ConfirmBuildPacket` 流程已标注过期；权威确认入口为 `/forma_confirm` 与 Patch Ticket。

## 第二十六批补充：Patch / Build 世界写入一致性

核对 `PatchPreviewService` 与 `BuildExecutionService`：

- **已修**：Patch `confirm` 先做维度/距离门禁再 `consume`（此前太远会吞掉票据）；`PreviewTicket` 绑定签发维度。
- **对齐点**：两边均为「预览裁剪 → 权威快照 → 确认消费 → 写入」；底层写入都走 `BlockMutationAccess`。
- **仍分叉**：过滤入口仍有差异，但禁区/选区/轮廓已共享 `MutationRegionPolicy`；历史双轨（`PatchHistoryManager` vs `UndoService`）；Patch 同步写入、Build 分 Tick。对称只在 Pipeline；笔刷/路径走廊只在 BuildConstraints。见 [方块执行契约](../WORLD_MUTATION_CONTRACT.md)。
- **未改**：`queueBuild(BuildingSpec)` 等管理入口仍可跳过 PreviewStorage 直接排队（需 OP/命令路径约束，不在本批范围）。

## 第二十七批补充：确认契约、离线 Undo、全局 Tick 预算

第二轮审查报告落地：

- **CAS Ticket 消费** + Ticket 绑定 `QualitySnapshot`；确认不再依赖玩家维度质量报告做门禁。
- **BuildQualityGate**：FATAL 禁止、ERROR 需 force；PatchConfirmPayload.force 与 `/forma_confirm force` 对齐。
- **离线 Undo**：完成建造按 owner UUID 记入 `UndoService`，在线才发完成消息；Memory 失败不影响 Undo。
- **全局预算**：`BLOCKS_PER_WORLD_TICK=200` 世界共享 + 轮询公平调度；单世界最多 32 任务。
- **严格 Meta**：缺 `PreviewMeta` 时 `validatePreview` 失败。
- **测试**：`BuildQualityGateTest`、Ticket 并发 consume、`BuildExecutionBudgetTest`、离线 Undo UUID 栈。

## 当前自动验收与剩余边界（2026-10-25）

主 CI 已配置执行 Python 全量测试与 `eval.ci_gate --quick`，每周 live eval 上传报告但不阻断主 CI。已有编译、通行、约束一致性和案例回放回归；下一步补齐真实游戏碰撞、邻居更新、多人交互和预览图验收，而非从零建立质量门。

规划器本轮先抽取独立 `inference_call.py`，通过兼容别名保留 `_call_with_timeout`；其余阶段继续按依赖抽取，保持输出和失败语义。请求执行上限与线程取消边界见 Python 后端 README。

## 构件编译器阶段抽取（2026-10-25）

`DesignValidatorSuite` 集中设计校验，显式区分规范化后作者输入与推断后构件两轮。
保留 Roof → Facade → Mass → HostedPlate → ExplicitConflict 的首错短路顺序；
作者输入轮随后检查全局材料，推断后轮先检查原计划的 generation authority、最后检查构件材料。
套件返回 `Optional<CapabilityGap>`，不读取或写入 `AssemblyCompileDiagnostics`，由原编译器发布诊断并停止编译。

宿主几何检查仍留在 `BuildingGeometryContext` 有效的生成边界；占用策略、生成顺序、
后处理审计与 ThreadLocal 生命周期不变。`DesignValidatorSuiteTest` 固定阶段输入、错误优先级、
材料诊断和诊断隔离；后续抽取准备/生成阶段时继续沿用这些边界。
