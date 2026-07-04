# FormaCraft 泛化策略

> **目标**：FormaCraft 应具备强大的泛化能力——一般建筑由 LLM 根据自然语言描述组织和策划，经参数化生成器实现；**仅著名地标**保留固定 `StructureGenerator` 精品库。

---

## 1. 核心原则

### 1.1 AI 规划，Java 实现

```
用户描述 → LLM 输出 LlmPlan（语义组件 + 尺寸 + 风格 + 约束）
         → Java ComponentGenerator（参数化几何 + Palette 材质）
         → BlockPatch → 预览 → 确认 → 放置
```

- LLM **不**输出方块 ID，只输出 `component_type`、`dimensions`、`params`、`features`。
- Java **不**硬编码"这是什么建筑"，只根据参数生成几何与材质。
- **dimensions = 方块数**：`width: 15, depth: 20` 表示 15×20 格的平面 footprint。

### 1.2 泛化优先，精品库例外

| 场景 | 路径 | 泛化程度 |
|------|------|----------|
| "盖一个 12×15 中式别墅" | LlmPlan → MassMain + Roof + Facade + Entrance | **高** |
| "盖一座现代玻璃幕墙办公楼" | LlmPlan → 参数化构件组合 | **高** |
| "盖一座天坛" | LlmPlan `MODULE` + `landmark:temple_of_heaven` → StructureGenerator | **低**（固定拓扑） |
| 后端直接返回 BuildingSpec | GeneratorRouter → StructureGenerator | **低** |

**方向**：精品库生成器应逐步注册为 LlmPlan 可调用的命名模块（`LandmarkModuleRegistry`），而非独立的并行 spec 格式。

### 1.3 组合优于单体

建筑 = 多个语义组件的组合（MASS + ROOF + ENTRANCE + FACADE + …），而非单一整栋模板。  
LLM 的组合能力 + Java 的参数化生成器 = 泛化的核心。

---

## 2. 已具备的泛化能力

| 能力 | 实现 | 状态 |
|------|------|------|
| LLM 语义组件组合 | `LlmPlan.components[]` + `layout.slots` | ✅ 主干 |
| 参数化主体体块 | `MassMainGenerator`：shape, plan_type, facade_profile, masses[] | ✅ |
| 参数化立面/屋顶/入口 | `FacadeWindowsGenerator`, `RoofGenerator`, `EntranceGenerator` | ✅ |
| 风格驱动材质 | `DynamicPaletteResolver` + `style_attributes` | ✅ |
| 建筑 DNA | `BuildingGenome`（topology/form/materials/archetype） | ✅ schema 已有 |
| PlanProgram 平面程序 | `PlanProgramCompiler` → 2D 程序 → 挤出 | ✅ 子路径 |
| 玩家构件扩展 | `component_request:` / `group_request:` | ✅ |
| Assembly 宏观立面 | `MetaAssemblyEngine`（哥特/古典/现代大体量） | ✅ 部分风格 |
| 地标模块引用 | `LandmarkModuleRegistry` + prompt 清单 | ✅ 25 个 module_id（2026-07） |
| 质量回归门 | `python_backend/eval/golden_eval.py` | ✅ |
| 锚点 = 建筑地面中心 | `ComponentPlanCompiler.defaultSlot(0,0,0)` + center anchor | ✅ 2026-07 修复 |

---

## 3. 差距清单（限制泛化的部分）

按优先级排列，指导后续代码改造。

### P0 — 影响所有生成的硬编码

| 差距 | 状态 | 说明 |
|------|------|------|
| Compiler 自动推断硬编码风格分支 | **2026-07 已改** | Assembly 立面默认关闭；屋顶/入口/出檐仅 params、features、style_attributes 驱动 |
| 低参数生成器 Keep/Gate | **2026-07 已改** | 接入 `PaletteLibrary.resolveBlock` + door/wall params |
| Palette 覆盖有限 | **2026-07 已改** | 新增 MODERN palette、fuzzy 匹配、`resolveBlock(style_attributes 优先)` |

### P1 — 路由与回退策略

| 差距 | 状态 | 说明 |
|------|------|------|
| 关键词路由表 | 待续 | `structure_routes_v1.json`, `ArchetypeRegistry` — 逐步让 LLM 通过 components 描述 |
| BuildingSpecRoutingPolicy 强制整栋 | **2026-07 已改** | 仅 `outputFormat=buildingspec` 跳过 LlmPlan；明清意图不再 auto-skip |
| 地标 module_id 注册 | **2026-07 已改** | `archetypes_v1.json` 扩至 25 个地标；prompt 注入 `LandmarkModuleRegistry.promptListing()` |
| LlmPlan 预览失败无降级 | 待续 | 泛化不足时直接失败 — 可选降级或提示用户细化 |
| 未注册 component_type 被跳过 | 部分 | `MODULE` 已放行；其它 invented type 仍待 heuristic 映射 |

### P2 — 管线完整性

| 差距 | 位置 | 影响 | 建议 |
|------|------|------|------|
| PatchFilterPipeline 未接线 | `common/patch/filter/PatchFilterPipeline.java` | 禁区/轮廓/对称 filter 设计存在但未调用 | 在预览/应用路径接入 |
| PostProcess 地形在预览时关闭 | `ComponentPlanCompiler` `applyTerrainAdaptation=false` | 预览与最终地形可能不一致 | 评估是否在预览启用轻量地形适应 |
| RAG / Few-shot / Memory 利用不足 | Prompt 组装、Memory 检索 | LLM plan 质量不稳定 | 见 §4 演进思想 |

### P3 — 精品库化进度

| 差距 | 现状 | 目标 |
|------|------|------|
| 30+ StructureGenerator 仅 BuildingSpec 路径 | **部分 2026-07** | 25 个已注册 `module_id`；LlmPlan 可通过 `landmark:` 引用 |
| 地标参数不可调 | 固定拓扑，仅尺寸/材质微调 | 接受 anchor/dimensions/style 参数 |
| archetype confidence ≥ 0.85 触发整栋 | 高置信度直接 bypass 构件 | 改为优先构件 + module 引用 |

---

## 4. 从归档文档抢救的设计思想

以下思想来自 otherwise-outdated 文档，已摘要于此，原文见 `docs/archive/`。

### 4.1 BuildingGenome DNA（`archive/BUILDING_GENOME_V1.md`）

建筑 DNA 作为 LLM 的**选择题**而非方块列表：topology（布局）、form（形式）、materials（材质）、culturalStyle（文化）、archetype（原型 + confidence）。  
**现状**：`plan.genome()` 已在 schema 和 prompt 中；生成器消费程度不均。  
**演进**：让更多生成器读取 genome 字段做默认推断，减少 Compiler 硬编码。

### 4.2 空间语法（`SPATIAL_GRAMMAR_ANALYSIS.md`）

SkeletonType 作为 AI 可理解的空间语言（词汇/语法/语义/语用四层）。  
**演进**：PlanProgram 子路径应成为复杂非矩形体量的首选，而非增加更多固定生成器。

### 4.3 预制件库四维分类（`assembly/ASSET_LIBRARY_DESIGN.md`）

连接件 / 填充件 / 收尾件 / 功能件 + 上下文感知放置。  
**演进**：补当前 facade 细节缺口；与 `component_request` 玩家构件库统一。

### 4.4 体量组合层（`BUILDING_MASS_ASSEMBLY_ARCHITECTURE.md`）

Plan 与 Structural 之间的体量组合（错动/悬挑/穿插）。  
**现状**：`common.mass` 在 quarantine；思想可经 `MassMainGenerator.params.masses[]` 实现。  
**演进**：不恢复 quarantine 代码，而是在 MassMain 参数化路径上增强。

### 4.5 RAG / Few-shot / Memory（`archive/COMPREHENSIVE_ARCHITECTURE_REVIEW.md`）

- 高质量 golden plan 样例注入 prompt（few-shot）
- Memory 检索相关历史建筑辅助增量修改
- `golden_eval.py` 断言扩展覆盖丰富度维度

### 4.6 Forma-Gene 参数化滑块（`assembly/FORMA_GENE_INTEGRATION.md`）

宏观参数（verticality, density, symmetry, structureExposure）→ assembly macro。  
**现状**：`ComponentPlanCompiler.generateAssemblyFacadePatches` 已部分实现。  
**演进**：暴露更多 macro 参数给 LLM schema。

### 4.7 Culture Cards（`assembly/CULTURE_CARDS.md`）

文化知识 → 可检索参数 → assembly/style 默认值。  
**演进**：接入 PromptAssembler RAG，减少 LLM 对文化细节的自由发挥误差。

### 4.8 路由改进（`archive/ROUTING_LOGIC_IMPROVEMENT.md`）

构件/整栋/地标三级路由应透明、可预测；显式 hint 优先于 heuristic。  
**现状**：`UnifiedGeneratorRouter` 已基本实现；需文档化 + 减少隐式 skip。

---

## 5. 演进路线图（建议优先级）

```mermaid
gantt
  title 泛化演进（建议）
  dateFormat YYYY-MM
  section P0
  减少Compiler硬编码风格分支     :2026-07, 1M
  KeepGate参数化                  :2026-07, 2w
  扩展Palette覆盖                 :2026-08, 1M
  section P1
  地标module_id注册（Top10）       :2026-08, 2M
  收窄BuildingSpecRoutingPolicy   :2026-08, 2w
  PatchFilterPipeline接线          :2026-09, 2w
  section P2
  Few-shot golden plans           :2026-09, 1M
  Culture Cards RAG               :2026-10, 1M
  golden_eval断言扩展              :持续, 1M
```

---

## 6. 验收标准

每次泛化相关改动后：

1. **Golden-Path 手测**（见 ARCHITECTURE.md §1.6）：基础单体 / 多层塔 / 院落 / 地标 / patch 编辑
2. **`golden_eval.py`**：schema 合法性 + 可建造性启发式
3. **日志检查**：`flattened terrain area` 应接近建筑 footprint（非 100×100 级异常）
4. **锚点对齐**：建筑地面中心 = 用户锚点（允许小幅地形适应）

---

## 相关文档

- [GENERATION_PIPELINE.md](GENERATION_PIPELINE.md) — 端到端流程
- [ARCHITECTURE.md](../ARCHITECTURE.md) — 系统总览
- [MIGRATION_LLMPLAN_VS_BUILDINGSPEC.md](MIGRATION_LLMPLAN_VS_BUILDINGSPEC.md) — 覆盖矩阵
- [landmarks/](landmarks/) — 精品库规格
