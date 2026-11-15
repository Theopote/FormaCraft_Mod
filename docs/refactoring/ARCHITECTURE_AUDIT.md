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

`config/architecture-debt.json` 中登记的 common 服务端类型引用现已清空；**禁止新增**。包引用 `com.formacraft.server` 在 common 中应保持为 0。

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

类型执行已拆到 server.generation.typology.TypologyGenerationRouter，解释器接口与注册表同迁服务端。common.typology.TypologyComponentRouter 保留类型识别、旧地标兼容和参数元数据逻辑。最后 6 处服务端类型债务已消除，基线为空；ThreadLocal、事务原子性和其他架构未决项仍按上文推进。

后端城市/复合兼容路由分类已从 ai_planner.py 提取至 planning_route_policy.py，build 路由直接引用策略模块；ai_planner 保留导入别名供已有调用者使用。关键词、城市优先级和 BUILD 模板保护沿用原实现。

规模与地形提示推断已提取至 planning_request_hints.py，ai_planner 保留兼容导入。选区优先、闭区间尺寸阈值及中英文关键词匹配顺序保持原实现；新增边界与优先级回归测试。

## 后端兼容规范化拆分（2026-11-15）

BuildingSpec / CompositeSpec 的六个输出规范化函数已从 ai_planner.py 迁至 legacy_spec_normalization.py。旧模块保留导入别名，现有调用方和契约冒烟入口继续可用。字段别名、默认值、样式/调色板目录校验、布局与子结构规范化及原地修改行为保持原实现；LlmPlan 规范化仍沿用原路径。

语义空间计划到地形参数、骨架布局的七个转换函数已迁至 semantic_plan_adapters.py，ai_planner 保留兼容导入。转换算法、预算取整、分区优先级和布局顺序保持原实现。回归记录了轴线预放置在后续 BFS 中被重新安排的现有行为；本批未调整布局算法。

BuildingSpec 的基因补全、材质语义映射及默认构件参数已迁至 legacy_spec_genome.py；递归合并函数继续供 LlmPlan 规范化复用，ai_planner 保留兼容导入。七个迁移函数保持原实现，用户覆盖值与原地修改语义不变。全仓无调用者的 _attach_archetype_genome 已删除。

## 已放弃入口与配置清理（2026-11-15）

删除 ai_planner 中无调用者、未接线的 BuildingSpec _default_schema，移除未使用的 OpenAI 可用性检测和 ARCHITECTURE_SEARCH_TIMEOUT_SEC 读取。研究配置样例改为实际使用的 BUILDING_RESEARCH 系列配置，说明旧 ARCHITECTURE_SEARCH 模式语义已退出。开发入口新增主干与兼容模块职责表。

仍被兼容路由使用的三个离线规则回退集中到 legacy_spec_fallbacks.py，保留 ai_planner 导入别名。新增无客户端路由回归时发现城市回退已不满足现行模型：补齐三个子建筑的稳定唯一 ID，道路改用 CitySpec 要求的 PathSpec 和 Vec3i。LlmPlan 失败处理没有改接规则回退。

历史摘要路由已删除无调用者的 _resolve_api_key、_resolve_model、_get_openai_client 及配套 SDK 检测；实际摘要继续走 llm_client 统一工厂。ai_editor 同步删除未使用的 SDK 检测与 os 导入。回归覆盖空输入、无客户端截断、provider 配置传递、模型解析和调用失败的原有 HTTP 响应。

兼容 ai_editor 的模型参数桥接已改为显式 SimpleNamespace，修复临时类体中 model = model 引发 NameError、导致真实客户端调用前即返回原 spec 的问题。模型解析仍委托 llm_client；新编辑能力继续走 LlmPlan patch。回归覆盖城市/单体编辑成功、无客户端、无效响应及 provider 默认模型解析。

研究启用策略现统一由 building_research_agent.is_building_research_enabled 解析。规划器移除启动时缓存的 _ARCHITECTURE_SEARCH_MODE 外层门控，修复 BUILDING_RESEARCH=on 仍被旧 ARCHITECTURE_SEARCH=off 提前拦截的问题；显式 off 仍关闭研究，未显式设置时保留旧开关回退。回归覆盖配置优先级及真实规划入口到研究阶段的调用。

architecture_researcher 已删除无业务/工具/测试调用者的旧 get_architecture_reference_context 链路、专用地标/风格关键词表和 search_architecture_queries 包装。保留研究代理、search_config 实际调用的搜索 provider、相关性过滤及去重。ARCHITECTURE_RESEARCHER.md 重写为当前 provider 职责与调用链，移除旧主要入口和扩充固定关键词的指导。

DuckDuckGo 搜索依赖由已更名的 duckduckgo-search 迁到 ddgs 9.16.1；直接依赖范围与锁定同步更新，解析确认复用现有 click、primp、lxml，无新增传递依赖。适配显式限定 duckduckgo 后端，保留查询扩写、地区选择、结果字段映射与缺包 HTTP 回退。Google CSE 回归补齐 DuckDuckGo mock，避免断言期间意外触发真实搜索。

research_building_context 仅剩测试调用，已删除。对应回归改为覆盖主干实际采用的 research_building_profile → format_profile_for_prompt 组合。研究文档明确结构化入口、格式化/搜索职责、开关优先级与超时等待的实际限制；修正旧测试中“开关需要 reload”的误导注释。

楼层提取和规则软意图提示迁到现有 planning_request_hints.py，ai_planner 保留导入别名；函数体保持原实现。开发入口与设计决策清单同步职责位置，明确软提示不能替代用户要求。新增楼层中英文表达、匹配优先级、软意图材质顺序与空输入回归。

规划超时预算的六个配置和两个解析函数迁至现有 inference_call.py，集中管理等待策略；ai_planner 保留函数导入别名。默认值、导入时环境读取、DeepSeek/reasoner 规则和任务预算下限保持原实现。新增 provider/任务组合、全局下限与独立进程环境覆盖回归。聊天历史注入开关的过时注释同步修正。

构件类型别名、features 归一和 component/group_request 字符串修复迁至 llm_plan_wire_normalization.py，ai_planner 保留三个导入别名。原有 MAIN_MASS/BUTTRESS 兼容映射和字符串修复算法保留；新增未知类型、features 对象/标量、嵌套花括号与转义引号回归，模块不承担生成路由选择。

选区尺寸、相对边界与位置裁剪的五个函数迁至 planning_selection_bounds.py，ai_planner 保留导入别名，裁剪算法不变。回归覆盖反向端点、闭区间尺寸、选区/笔刷优先级、显式/玩家锚点、两种锚定下奇偶尺寸的边界及无选区引用语义。开发入口明确坐标与服务端权威校验边界。

尺寸默认值、主体尺寸提取、坐标数值及参数别名容错的八个函数和四个类型分组迁至 llm_plan_dimensions.py，ai_planner 保留导入别名。函数与分组保持原实现；回归覆盖各类构件默认尺寸、首个有效主体继承、显式值优先、无效值修复与源数据不变。

布局/骨架/对称性归一的六个函数和四个类型/别名表迁至 llm_plan_layout_normalization.py，ai_planner 保留导入别名。未知骨架 COMPOUND 回退、无效朝向剔除、slot 补全及 genome 对称性容错保持原实现；新增显式对称优先级、别名、源数据不变和布局清理回归。

迁移后的转导出再次核查：ai_planner 删除 31 个无内部业务用途的导入别名（含无调用者的 get_archetype_def、_int_env），保留当前编排实际使用的函数。样式目录行为测试改从 legacy_spec_genome 直接导入；七处仅断言旧别名身份的测试删除，所属模块行为回归保留。前文“保留导入别名”是迁移当批状态，当前以真实引用为准，不恢复无调用者的私有 facade。

地标模块 ID 提取与 _sanitize_landmark_modules 集中到现有 typology_plan_repair.py，规划器只导入实际调用的规范化入口。移除迁移后的自导入，保留原策略与执行顺序；直接测试改从职责模块引用。新增 profile 无模块时剔除模块、预期模块去重/归一回归，开发入口同步规则归属。

_fill_component_params 迁至 llm_plan_component_defaults.py，规划器保留实际调用导入。样式目录、style pack、用户 style pack 及复合请求行为测试改为直接引用职责模块，参数补全函数体保持原实现；未改动样式默认值与显式参数优先级。

LlmPlan 输出规范化主流程与最小角锚点识别迁至 llm_plan_normalization.py；规划器仅保留实际调用导入，行为测试直接引用职责模块。用户合同、能力修复及细节补全顺序不变。

规划器拆分收尾：删除未使用的 List/Union 导入与迁移遗留空段，修正“LlmPlan 原始 JSON 直接返回”“语义规划不等待”等过时说明，显式标记三个 spec 兼容入口。本轮以 Git 索引版本补做上一批规范化函数 AST 对比，两个函数一致；HEAD 仍不可读取，未修改 Git 历史或索引。

语义空间规划提示词构造迁至 semantic_plan_prompts.py，模型调用与 SemanticSpatialPlan 校验保留在原入口。原提示词文本与上下文构造顺序保留，新增选区、规模、地形及真实入口发送消息回归。

城市/复合兼容入口的静态系统提示词集中到现有 building_spec_prompts.py，原入口只调用 builder。提示词文本与 HEAD 基线逐字一致；没有扩展兼容协议或改变语义规划前置阶段。

城市/复合入口重复的语义参数写入收敛为 semantic_plan_adapters.attach_semantic_plan_to_first_structure，保留首个子建筑、显式键（含 None）优先及异常跳过行为。回归覆盖首项写入、缺失 extra、后续子建筑不变和无计划/空集合/无效项。

城市分区到 semanticRole 的确定性补全迁至 semantic_plan_adapters.fill_city_semantic_roles，规划器只调用；PUBLIC/PRIVATE/SERVICE/TRANSITION 映射、未知分区回退、显式角色优先及异常跳过保持原行为。新增各分区映射与未匹配/空角色/显式角色回归。

三个 spec 兼容入口的 SemanticSpatialPlan JSON 提示词拼接收敛至 semantic_plan_prompts.append_semantic_plan_context。保留 by_alias、中文字符、换行及 BuildingSpec 专属布局提示；无计划或序列化失败保留原提示词。回归覆盖文本、别名与失败行为。

BuildingSpec 退出迁移第一步已实施：/build 隐式/auto 请求在所有 promptMode 下统一走 LlmPlan，删除非 BUILD 模式按关键词默认进入 city/composite/buildingspec 的分支。显式 buildingspec 保留原分流供现存消费者迁移。新增格式×模式×文本回归并更新旧路由期望；游戏端验收仍待执行。

旧格式生产调用核查：当前仓库 Java 发送入口与评估脚本显式选择 llmplan，未发现显式发送 buildingspec 的生产请求；旧格式消费者/解析器仍保留。BuildRequest 与 FormaRequestAdapter 共享 PlanOutputFormat 取值并默认 llmplan，保留显式 null/auto/buildingspec；未知格式不再静默进入默认路径，按请求校验失败处理。

## 原则

AI 规划、确定性编译、BlockPatch 唯一写入。完成报告不能代替运行事实。
