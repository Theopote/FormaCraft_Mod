# FormaCraft 文档索引

> **阅读顺序**：本页 → [ARCHITECTURE.md](../ARCHITECTURE.md) → [GENERATION_PIPELINE.md](GENERATION_PIPELINE.md) → [GENERALIZATION_STRATEGY.md](GENERALIZATION_STRATEGY.md)

最后更新：2026-07

---

## Tier 0 — 权威入口（维护中）

| 文档 | 用途 |
|------|------|
| [ARCHITECTURE.md](../ARCHITECTURE.md) | 系统总览、分层规则、质量门、Canonical Pipeline 声明 |
| [GENERATION_PIPELINE.md](GENERATION_PIPELINE.md) | LlmPlan → 放置方块的**唯一流程真相**（类名、阶段、分支） |
| [GENERALIZATION_STRATEGY.md](GENERALIZATION_STRATEGY.md) | 泛化优先原则、已具备能力、代码差距清单、演进方向 |
| [LLMPLAN_SYSTEM_CORE_PHILOSOPHY.md](LLMPLAN_SYSTEM_CORE_PHILOSOPHY.md) | 设计哲学："AI 规划，Java 实现" |
| [DOC_CONVENTIONS.md](DOC_CONVENTIONS.md) | 文档命名、分层、归档规范 |
| [MIGRATION_LLMPLAN_VS_BUILDINGSPEC.md](MIGRATION_LLMPLAN_VS_BUILDINGSPEC.md) | 构件层 vs 整栋层覆盖矩阵（活跃维护） |

---

## Tier 1 — 开发者手册

| 文档 | 用途 |
|------|------|
| [FORMACRAFT_DEVELOPER_DOCUMENTATION.md](FORMACRAFT_DEVELOPER_DOCUMENTATION.md) | 完整开发者参考（体量大，部分路径需对照 Tier 0 校正） |
| [UNIFIED_SYSTEM_ARCHITECTURE.md](UNIFIED_SYSTEM_ARCHITECTURE.md) | 七子系统职责地图（2026-07；双轨 framing 以 ARCHITECTURE 为准） |
| [CONTRIBUTING.md](../CONTRIBUTING.md) | 贡献指南 |
| [python_backend/README.md](../python_backend/README.md) | Python 后端 API |
| [python_backend/INTEGRATION_GUIDE.md](../python_backend/INTEGRATION_GUIDE.md) | Java ↔ Python 集成 |

---

## Tier 2 — 子系统活文档

### LlmPlan 编译与路由

| 文档 | 用途 |
|------|------|
| [GENERATOR_ROUTING_MAP.md](GENERATOR_ROUTING_MAP.md) | Component / Structure / Skeleton 三套路由对照 |
| [COMPONENT_PLAN_COMPILER_K3_1.md](COMPONENT_PLAN_COMPILER_K3_1.md) | ComponentPlanCompiler 实现要点 |
| [LLM_JSON_TEMPLATE_K3_1.md](LLM_JSON_TEMPLATE_K3_1.md) | LLM 输出 JSON 模板 |
| [LLM_JSON_PARSER_K3_1.md](LLM_JSON_PARSER_K3_1.md) | LLM JSON 解析层 |
| [PATCH_FILTER_PIPELINE_K3_3.md](PATCH_FILTER_PIPELINE_K3_3.md) | PatchFilterPipeline 设计（注：当前 Java 未全线接线） |
| [POST_PROCESS_PIPELINE_IMPLEMENTATION.md](POST_PROCESS_PIPELINE_IMPLEMENTATION.md) | PostProcessPipeline 后处理 |
| [PALETTE_RESOLVER_K3_2.md](PALETTE_RESOLVER_K3_2.md) | 语义材质解析 |
| [PROMPT_ASSEMBLER_AUTOMATION.md](PROMPT_ASSEMBLER_AUTOMATION.md) | Tool → PromptAssembler |
| [TERRAIN_STRATEGY_SYSTEM.md](TERRAIN_STRATEGY_SYSTEM.md) | 地形策略 |
| [GENERATION_QUALITY_PARAMS.md](GENERATION_QUALITY_PARAMS.md) | 生成质量参数 |

### PlanProgram / Skeleton 子路径

| 文档 | 用途 |
|------|------|
| [PLAN_PROGRAM_SCHEMA_V1.md](PLAN_PROGRAM_SCHEMA_V1.md) | PlanProgram schema |
| [PLAN_SKELETON_SCHEMA_V1.md](PLAN_SKELETON_SCHEMA_V1.md) | PlanSkeleton schema |
| [PLAN_TO_SKELETON_COMPILER.md](PLAN_TO_SKELETON_COMPILER.md) | Plan → Skeleton 编译 |
| [SKELETON_TO_PATCH_PIPELINE.md](SKELETON_TO_PATCH_PIPELINE.md) | Skeleton → BlockPatch |
| [SKELETON_GENERATOR_SYSTEM.md](SKELETON_GENERATOR_SYSTEM.md) | ISkeletonGenerator 注册 |
| [SPATIAL_GRAMMAR_ANALYSIS.md](SPATIAL_GRAMMAR_ANALYSIS.md) | SkeletonType 空间语法理论 |
| [examples/complete_plan_to_skeleton_flow.md](examples/complete_plan_to_skeleton_flow.md) | 端到端示例 |

### 立面 / 屋顶 / 体量

| 文档 | 用途 |
|------|------|
| [FACADE_COMPONENT_HIERARCHY.md](FACADE_COMPONENT_HIERARCHY.md) | 立面构件层级 |
| [FACADE_RHYTHM_SYSTEM.md](FACADE_RHYTHM_SYSTEM.md) | 立面韵律 |
| [PROPORTIONAL_FACADE_AND_DIMENSIONS.md](PROPORTIONAL_FACADE_AND_DIMENSIONS.md) | 比例立面与尺寸（**dimensions = 方块数**） |
| [ROOF_PLATE_V3.md](ROOF_PLATE_V3.md) | 屋顶板 v3（当前版本） |
| [WALL_EXTRUSION_V1.md](WALL_EXTRUSION_V1.md) | 墙体挤出 |
| [FLOOR_COURTYARD_BOOLEAN_V1.md](FLOOR_COURTYARD_BOOLEAN_V1.md) | 楼层/庭院布尔 |
| [DYNAMIC_STYLE_RESOLUTION_DESIGN.md](DYNAMIC_STYLE_RESOLUTION_DESIGN.md) | 动态风格解析 |
| [SEMANTIC_COMPONENT_SYSTEM.md](SEMANTIC_COMPONENT_SYSTEM.md) | SemanticPart + Palette |

### 构件 / Socket / 拾取

| 文档 | 用途 |
|------|------|
| [COMPONENT_SYSTEM.md](COMPONENT_SYSTEM.md) | 构件系统总览 |
| [COMPONENT_SOCKET_SYSTEM.md](COMPONENT_SOCKET_SYSTEM.md) | Socket v1 规格 |
| [SOCKET_REFINEMENT_RULES.md](SOCKET_REFINEMENT_RULES.md) | Socket 细化规则 |
| [VARIANT_RULES_SPEC.md](VARIANT_RULES_SPEC.md) | Variant 规则 |
| [ComponentQueryRankingSystemV3.md](ComponentQueryRankingSystemV3.md) | 构件查询排序 v3 |
| [COMPONENT_CAPTURE_DESIGN_V2.md](COMPONENT_CAPTURE_DESIGN_V2.md) | 构件拾取面板 v2 |

### 路径 / 交通 / Memory

| 文档 | 用途 |
|------|------|
| [PATH_DRIVEN_GENERATION_SYSTEM.md](PATH_DRIVEN_GENERATION_SYSTEM.md) | 路径驱动生成 |
| [PATH_FUNCTION_ZONING_K3.md](PATH_FUNCTION_ZONING_K3.md) | 路径功能分区 |
| [TRANSPORT_COMPONENTS_IMPLEMENTATION.md](TRANSPORT_COMPONENTS_IMPLEMENTATION.md) | 道路/桥梁构件 |
| [MEMORY_SYSTEM_IMPLEMENTATION.md](MEMORY_SYSTEM_IMPLEMENTATION.md) | Forma-Cortex Memory |

### Assembly / 文化知识

| 目录 | 用途 |
|------|------|
| [assembly/](assembly/) | 宏观立面、资产库、Culture Cards、Forma-Gene |
| [assembly/ASSET_LIBRARY_DESIGN.md](assembly/ASSET_LIBRARY_DESIGN.md) | 预制件库四维分类（演进参考） |
| [assembly/CULTURE_CARDS.md](assembly/CULTURE_CARDS.md) | 文化知识 schema |

### 地标（固定生成器，精品库）

| 目录 | 用途 |
|------|------|
| [landmarks/](landmarks/) | 9 份地标规格（埃菲尔、天坛、土楼、四合院等） |

---

## Tier 3 — 归档（不维护）

| 目录 | 内容 |
|------|------|
| [archive/](archive/) | 2026-07 前从根目录迁入的历史快照 |
| [archive/reports/](archive/reports/) | 时点报告：`*_SUMMARY`、`*_COMPLETE`、`*_STATUS`、`*_REPORT`、`PHASE*` |
| [archive/fixes/](archive/fixes/) | 点修记录：`*_FIX`、`THUMBNAIL_*`、`SELECTION_*`、`BUG_FIX_*` |
| [archive/superseded/](archive/superseded/) | 被新版本取代的设计/分析稿 |

归档文档可能含宝贵设计思想，已摘要迁入 [GENERALIZATION_STRATEGY.md](GENERALIZATION_STRATEGY.md)。查阅历史决策请用 `git log --follow`。

---

## 文档状态图例

| 标记 | 含义 |
|------|------|
| **维护中** | 与当前代码对齐，改动功能时需同步更新 |
| **参考** | 大体正确，细节以 Tier 0 + 代码为准 |
| **归档** | 历史快照，不保证与代码一致 |
| **Quarantine** | 描述 quarantine 代码（如 Building Mass），勿作为主干扩展依据 |
