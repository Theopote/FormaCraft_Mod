# 架构审计与重构路线

状态：维护中；核查日期：2026-10-03。

第一轮覆盖源码目录、构建和 CI、请求入口、生成主流程、分层依赖及文档入口。第二轮恢复测试编译、修复相关行为并迁移解释器初始化；不代表已经逐类验证建筑几何或完成游戏内验收。初始基线为 1050 个 Java 源文件、88 个 Java 测试文件、57 个 Python 应用文件、46 个 Python 测试文件、223 篇 docs 文档。

## 已核实的问题

| 优先级 | 证据 | 影响与处理 |
|---|---|---|
| P0 | 第一轮测试编译失败；第八批完整执行 345 测试，全部通过 | 编译漂移及基础/立面/屋顶本批拼接问题已修复，冠部层数与楼层顶圈也已修复，本批修复迁移条目的旧 MODULE 提示与八边形凹龛方向，第六批明确半球协议、接入 Fabric JUnit 并修复类型桥接丢失 BlockState 属性；详见 [回归清单](REGRESSION_BASELINE.md) |
| P1 | `common/patch/history/PatchHistoryManager.java` 有 20 处服务端限定引用 | 初始另有注册表 10 处引用，第二轮已迁到服务端初始化并缩减基线；下一步把 Memory 更新放到服务端事务服务 |
| P1 | `common/patch/PatchExecutor.java` 与 `common/patch/history/PatchHistoryManager.java` 持有 ServerWorld 并写方块 | common 并非纯模型/算法层；后续迁移执行器与历史事务时保留结果 DTO，核查 apply/undo/redo 与 Memory 的一致性 |
| P1 | BuildTask/PatchExecutor 原先忽略写入返回值；历史按尝试列表更新 Memory | 本批修复实际应用结果、严格目标解析及最终差异；第八批进一步修复部分恢复保留/重试、世界身份与冲突保护；普通 Undo Memory 更新、异常补偿与 Memory 原子性仍待处理，详见 [方块执行契约](../WORLD_MUTATION_CONTRACT.md) |
| P1 | `python_backend/app/routes/build.py` 的 async 入口直接调用同步生成函数 | 规划和外部调用可能阻塞事件循环；后续将请求解析与同步规划隔离，并用并发请求证明健康检查不被阻塞；线程池本身不能保证超时后取消任务 |
| P1 | `python_backend/app/services/ai_planner.py` 为 5380 行 | 意图、检索、提示词、调用、修复、回退混在一起；按阶段抽取，保留公开 generate_* 门面，先保持输出和失败语义 |
| P2 | `server/build/PathLayoutService.java` 无源码调用者，extractPathPoints 始终返回 null | 本轮删除不可工作的占位服务；不是删除真实路径工具或路径生成器 |
| P2 | `server/skeleton/gen/geometry/ToolConstraintBuilder.java` 无源码调用者，却读取客户端全局状态 | 本轮移到 `client/skeleton`；服务端未来只接受请求快照，不读取客户端状态 |
| P2 | ARCHITECTURE 将 ComponentPlanCompiler 写成 common.compiler，实际位于 server.compiler | 本轮纠正文档，区分主干与仍存在的兼容入口 |
| P2 | 文档写 agent 环境不能编译，README 写 Gradle 8+，Wrapper 实际为 9.1.0 | 本轮更新；本机基线 compileJava --offline 成功，不能以历史环境限制代替验证 |
| P2 | 历史文档宣称 PathLayoutService 已集成，但代码没有调用者 | 完成报告不能作为运行事实；UNUSED_FILES_ANALYSIS 标记为历史参考，禁止照着旧建议恢复第二套入口 |

## 已实施的边界防回归

`python scripts/check_architecture.py` 检查 common → client/server、server → client 的导入和完全限定引用，忽略注释与字符串。CI 会运行检查器测试与检查。

`config/architecture-debt.json` 当前记录 PatchHistoryManager 的 20 处既有引用；注册表的 10 处债务已消除。新增引用导致失败，消除引用后必须同步缩减基线；不得为了通过 CI 扩大基线。它只检查源码包引用，不证明运行时隔离，不覆盖反射、间接依赖、Minecraft 服务端类型或世界写入权限。

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
