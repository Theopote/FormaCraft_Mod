# 开发者从这里开始

状态：维护中；更新：2026-11-15。

FormaCraft 把玩家的语言描述与空间约束变成 Minecraft 建筑。先理解从意图到可撤销世界修改的链路，再阅读具体生成器。

## 阅读顺序

1. [README](../README.md)：启动与构建。
2. [生成流程](GENERATION_PIPELINE.md)：请求、编译、预览、执行的实际分支。
3. [架构概览](../ARCHITECTURE.md)：设计边界。
4. [审计与重构路线](refactoring/ARCHITECTURE_AUDIT.md)：未决债、证据基线与**弃用退出清单**。
5. [索引](INDEX.md)：按任务选择子系统文档；UNIFIED / 大手册为历史参考；archive 只用于追溯。

## 从任务定位代码

| 要改什么 | 从哪里开始 |
|---|---|
| 玩家请求、后端结果分流 | `client/ui`、`server/network/BuildRequestProcessor.java`、`server/orchestrator/OrchestratorClient.java` |
| 意图、AI 规划、修复与回退 | `python_backend/app/routes/build.py`、`app/services/ai_planner.py`、`prompt_assembly.py`、`llm_plan_system_prompt.py` |
| 跨语言计划协议 | `python_backend/app/models/llm_plan.py`、`common/llm/dto`；模型、解析器和消费者一起核查 |
| 构件编译 | `server/compiler/ComponentPlanCompiler.java`、`server/compiler/PlanProgramCompiler.java` |
| 路由与建筑几何 | `server/generation/component/adaptor/UnifiedGeneratorRouter.java`、`common/generation/component`、`server/generation/typology` |
| 预览与最终执行 | `server/network/LlmPlanPreviewBuilder.java`、`server/build/BuildExecutionService.java`、`common/patch` |
| 撤销与建筑记忆 | `common/patch/history`、`server/build/UndoService`、`server/memory`；[记忆与存储契约](MEMORY_TRANSACTION_CONTRACT.md)，现有跨层依赖见审计 |
| 建筑质量回归 | `python_backend/eval`、`python_backend/tests`、`src/test`、资源中的 eval_cases |

上表 Java 路径以 `src/main/java/com/formacraft/` 为根；Python app 路径以 `python_backend/` 为根。

路由修改先阅读 [地标与建筑类型契约](ROUTING_AND_TYPOLOGY_CONTRACT.md)，避免按旧模块目录恢复已迁移的固定模板。

## 后端主干与兼容边界

新生成能力沿 `routes/build.py → ai_planner.generate_llm_plan → LlmPlan` 开发。提示词由 `prompt_assembly.py` / `llm_plan_system_prompt.py` 维护；LlmPlan 的响应 schema 位于 `models/llm_plan_json_schema.py`。不要恢复旧 BuildingSpec 的未接线 schema 或另建一套默认生成流程。

以下模块服务于现存兼容调用，保持冻结，不作为新功能入口。

| 模块 | 职责 |
|---|---|
| `legacy_spec_fallbacks.py` | 无 LLM 客户端时的 BuildingSpec / city / composite 规则回退；不承接 LlmPlan 失败 |
| `legacy_spec_normalization.py` | BuildingSpec / CompositeSpec 输出容错与别名归一 |
| `legacy_spec_genome.py` | 兼容 spec 的基因、默认构件参数补全；递归合并仍被 LlmPlan 复用 |
| `semantic_plan_adapters.py` | 兼容城市/复合路径的语义计划转地形参数、骨架布局 |
| `planning_route_policy.py` | 城市/复合兼容路由分类 |
| `ai_editor.py` | `/edit/city`、`/edit/building` 的整份 spec 兼容编辑；新增增量编辑走 LlmPlan patch |

`planning_request_hints.py` 保存规划请求的规模、地形、楼层提取和软意图提示。软提示不替代用户要求或协议校验。迁移 helper 应从所属模块直接导入；`ai_planner.py` 仅保留内部使用和已有消费者需要的导入，无调用者的转导出别名已逐步删除。删除兼容入口前须核对 `build.py` 的实际路由和消费者。

联网研究由 `building_research_agent.py` 管理，配置使用 `BUILDING_RESEARCH`、`BUILDING_RESEARCH_MODE`、`BUILDING_RESEARCH_TIMEOUT_SEC`。明确设置的 `BUILDING_RESEARCH=on/off` 优先于旧开关；未明确设置时才参考 `ARCHITECTURE_SEARCH=off`。旧 `landmark/all` 不再选择研究模式，`ARCHITECTURE_SEARCH_TIMEOUT_SEC` 已无作用。

LLM 客户端创建与 provider、模型、密钥解析统一由 `services/llm_client.py` 负责；规划、兼容编辑和历史摘要均使用该工厂。新增调用应复用此入口，不恢复路由内的 OpenAI 专用客户端或独立环境变量解析。

模型调用等待及规划任务超时预算归 `services/inference_call.py`。现有 `LLM_CALL_TIMEOUT_*` 在模块导入时读取；任务预算与 provider 预算取较大值。等待超时不会强制终止已运行的线程，不能据此假定容量或外部调用已释放。

LlmPlan 构件类型别名、features 表示归一和未转义请求字符串修复归 `services/llm_plan_wire_normalization.py`；该层只处理表示兼容，不选择生成路由。字段合法性仍由 LlmPlan 模型校验，几何和能力约束沿现有规范化、编译链处理。

规划输出的选区尺寸、相对边界和构件位置裁剪归 `services/planning_selection_bounds.py`。选区端点为闭区间世界坐标，裁剪位置相对计划锚点；普通选区优先于笔刷选区。服务端编译与落地校验仍须执行，不以这些后端裁剪 helper 替代权威校验。

构件尺寸默认值、主体尺寸继承、数值及参数别名容错归 `services/llm_plan_dimensions.py`。这里保留当前后端修复策略，不能把默认尺寸视为 Java 生成器能力契约；调整尺寸行为仍需核对服务端消费者与建筑验收。

布局 slot、骨架类型别名和对称性容错归 `services/llm_plan_layout_normalization.py`。这层保留现有回退，不承担骨架执行或类型注册；新增类型仍需同步协议与服务端消费者。

已迁移地标 MODULE 到 typology 的修复、研究 profile 对模块选择的约束及模块去重统一位于 `services/typology_plan_repair.py`。`ai_planner.py` 只调用该策略；不要在规划器内再维护一份地标替换规则。

构件缺失参数的补全归 `services/llm_plan_component_defaults.py`；样式目录默认值仍由 `style_feature_compiler.component_style_defaults` 提供。补全保留显式参数，和研究证据校验、尺寸容错分别维护，避免把后端默认规则误当成生成器已实现能力。

验证细节和下一轮检查顺序见 [测试与建筑验收](TESTING_AND_BUILDING_ACCEPTANCE.md)。

当前产品阶段（Golden Path 稳定化）见 [Phase 1 审计](phase1/GOLDEN_PATH_AUDIT.md) 与 [游戏内验收矩阵](phase1/INGAME_ACCEPTANCE_MATRIX.md)；离线 CI 不能代替矩阵填写。

## 本地验证

仓库根执行 `python scripts/test_check_architecture.py`、`python scripts/check_architecture.py` 和 Windows `./gradlew.bat check`（Unix 用 `./gradlew check`）。使用仓库 Wrapper，不依赖机器上的 Gradle 版本。

后端安装 `python_backend/requirements.lock` 后，在 python_backend 下运行 `python -m eval.ci_gate`。主干改动还需要 [生成流程](GENERATION_PIPELINE.md) 和 [架构概览](../ARCHITECTURE.md) 中的游戏验收案例。静态检查和离线计划质量门不等同于游戏内建筑验收。

## 修改原则

- 新生成能力先接 LlmPlan 主干；兼容 BuildingSpec 仍存在，不能直接删除其协议与调用者。
- 服务端接受请求 DTO/快照，不能读取客户端全局工具状态。
- 区分相对坐标、世界坐标和方块数；沿用实际协议，改单位必须同时更新两端与回归样例。
  具体规则与几何验收见 [构件坐标契约](COMPONENT_COORDINATE_CONTRACT.md)。
- 每次行为改动同步对应活文档和案例；把事实、待接线能力与目标分开写。
- 不根据旧 COMPLETE/SUMMARY 报告判断功能已接入，也不为“现代化”新增同职责的第二套生成流程。

## 测试与初始化约定

测试中使用 `LlmPlanTestFixtures.builder()` 按字段构造计划；不要复制二十多个位置参数。未指定字段保持 null，不默认填充，以保留缺字段场景。

解释器接口及 TypologyInterpreterRegistry 位于 server.generation.typology，不再由 common 持有服务端执行契约。游戏启动由 `FormacraftMod.onInitialize` 调用 `server.init.TypologySystemInitializer.initialize()`；直接运行解释器注册测试时显式初始化。自定义解释器可通过 registry.register 注册，重复初始化不会覆盖已有注册。

模组入口的静态字段不能访问活注册表；RegistryKey 使用 `RegistryKeys.ITEM` 构建。方块注册和世界访问应在运行时生命周期内完成。普通 JUnit 不能代替 Fabric 加载环境，涉及真实 Minecraft 注册表的失败需单独核查。当前剩余回归见 [回归清单](refactoring/REGRESSION_BASELINE.md)。

LlmPlan 输出规范化主流程位于 `services/llm_plan_normalization.py`，按现有顺序组合布局、尺寸、用户合同、能力修复与细节补全。`ai_planner.py` 负责模型调用编排，不另写一套输出修复。

现有语义空间规划阶段的提示词由 `services/semantic_plan_prompts.py` 构造；城市/复合兼容入口使用该阶段，不能把它扩展为第二条默认生成主干。

BuildingSpec、CitySpec、CompositeSpec 兼容入口的系统提示词统一在 `services/building_spec_prompts.py`，不得作为新生成能力入口。

退出 BuildingSpec 的第一步：`/build` 未指定格式或选择 `auto` 时，各 promptMode 统一走 LlmPlan，不再按城市/村庄关键词隐式进入旧 spec 路径。只有显式 `outputFormat="buildingspec"` 保留旧城市/复合/单体分流。旧消费者迁移时删除该显式选择；不得在 LlmPlan 失败时恢复 spec 回退。

后端 BuildRequest 与 FormaRequestAdapter 默认 outputFormat 为 llmplan；合法取值为 llmplan、auto、buildingspec（兼容），显式 null 仍走默认主干。未知字符串按无效请求处理。

旧整份规格编辑的 Java API 明确命名为 `editLegacyCitySpec` / `editLegacyBuildingSpec`。剩余消费者为 `/forma_edit_city`、`/forma_edit_building` 命令和显式 buildingspec 的聊天编辑分支；新编辑调用 `requestAiPlan`，不新增旧编辑 API 消费者。
