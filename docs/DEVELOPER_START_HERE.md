# 开发者从这里开始

状态：维护中；更新：2026-11-15。

FormaCraft 把玩家的语言描述与空间约束变成 Minecraft 建筑。先理解从意图到可撤销世界修改的链路，再阅读具体生成器。

## 阅读顺序

1. [README](../README.md)：启动与构建。
2. [生成流程](GENERATION_PIPELINE.md)：请求、编译、预览、执行的实际分支。
3. [架构概览](../ARCHITECTURE.md)：设计边界。
4. [审计与重构路线](refactoring/ARCHITECTURE_AUDIT.md)：已确认技术债与验收要求。
5. [索引](INDEX.md)：按任务选择子系统文档；archive 只用于追溯。

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

`planning_request_hints.py` 保存规划请求的规模、地形、楼层提取和软意图提示。软提示不替代用户要求或协议校验。`ai_planner.py` 仍保留迁移函数的导入别名，以支持已有调用与测试；这些别名不代表另一份实现。删除兼容入口前须核对 `build.py` 的实际路由和消费者。

联网研究由 `building_research_agent.py` 管理，配置使用 `BUILDING_RESEARCH`、`BUILDING_RESEARCH_MODE`、`BUILDING_RESEARCH_TIMEOUT_SEC`。明确设置的 `BUILDING_RESEARCH=on/off` 优先于旧开关；未明确设置时才参考 `ARCHITECTURE_SEARCH=off`。旧 `landmark/all` 不再选择研究模式，`ARCHITECTURE_SEARCH_TIMEOUT_SEC` 已无作用。

LLM 客户端创建与 provider、模型、密钥解析统一由 `services/llm_client.py` 负责；规划、兼容编辑和历史摘要均使用该工厂。新增调用应复用此入口，不恢复路由内的 OpenAI 专用客户端或独立环境变量解析。

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
