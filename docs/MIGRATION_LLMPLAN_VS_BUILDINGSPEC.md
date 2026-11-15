# LlmPlan vs BuildingSpec — 构件/结构能力对照

> **警告（2026-11-15）**：本文**不是**现行路由指南。  
> - 已删除：`BuildingSpecRoutingPolicy`、`OrchestratorClient.requestBuildingSpec`。  
> - 现行入口：`requestAiPlan` → `AiPlanResult`；默认 `outputFormat=llmplan`；旧格式仅显式 `buildingspec`。  
> - 权威流程：[GENERATION_PIPELINE.md](GENERATION_PIPELINE.md)、[ARCHITECTURE.md](../ARCHITECTURE.md)。  
> - 兼容退出台账：[ARCHITECTURE_AUDIT.md](refactoring/ARCHITECTURE_AUDIT.md)。  
> 下文表格只回答「某语义在构件层 / 整栋层各有什么执行能力」。

**维护说明**：整栋与构件生成器均可作为**执行器**保留；统一门面 `GenerationHub`。禁止把同名生成器合并为上帝类。`GeneratorRouter.routeDecision()`：未匹配 → `NEEDS_REPLAN`；null → `UNSUPPORTED`；无静默 house/tower 回退。

## 如何阅读

| 覆盖状态 | 含义 |
|----------|------|
| **构件✅** | `ComponentGeneratorRegistry` 有注册，可走 LlmPlan → `ComponentPlanCompiler` |
| **整栋✅** | `StructureGeneratorRegistry` / `GeneratorRouter` 可路由 |
| **质量：高 / 中 / 构件简** | 相对实现完整度（**不**表示应默认走 BuildingSpec） |
| **仅整栋** | 地标/模板，适合经 `MODULE` + `landmark:` 或显式 compat 调用 |
| **退役候选** | 可缩小整栋 fallback 命中面；**不删除类** |

---

## 1. 现行分流（摘要）

```
OrchestratorClient.requestAiPlan(req)  →  POST /build
        │
        ▼
  AiPlanResult
   ├─ LlmPlan      → LlmPlanPreviewBuilder → CPC/PPC → UnifiedGeneratorRouter
   ├─ BuildingSpec → previewBuildingSpec → GenerationHub.routeStructure（显式 compat）
   ├─ Composite/City → 对应 preview*（显式 compat）
   └─ Clarification → 客户端澄清
```

地标在主干上应优先：`LlmPlan` + `MODULE` + `features: ["landmark:<id>"]` → adaptor。

### 指标日志（谨慎解读）

前缀 `[LlmPlanMetrics]`。`structure_after_fallback` 在预览失败路径上仍可能被调用，但**已无假 BuildingSpec 回退**（审计 LEGACY-014，PR-2 修正）。勿把该计数当作「整栋回退率」。

```powershell
Select-String "\[LlmPlanMetrics\]" logs/latest.log
.\scripts\analyze-llmplan-metrics.ps1 run\logs\latest.log
```

---

## 2. 基础类型对照（同名不同实现）

| 语义 | 构件层（LlmPlan） | 整栋层（BuildingSpec） | 构件覆盖 | 整栋覆盖 | 质量对比 | 备注 |
|------|-------------------|------------------------|----------|----------|----------|------|
| 塔楼 | `TowerComponentGenerator` | `TowerGenerator` | ✅ | ✅ | 中 | 构件：Palette 圆塔；整栋：StyleProfile、楼层、楼梯 |
| 墙体 | `WallComponentGenerator` | `WallGenerator` | ✅ | ✅ | 中 | 整栋含地形/风格解析 |
| 路径 | `PathComponentGenerator` | `path.PathGenerator` | ✅ | ✅ | 中 | 整栋含 RoadPlanner/A* |
| 门/道路 | `GateGenerator` / `RoadGenerator` | `BridgeGenerator` 等 | ✅ | 部分 | 构件简 | 桥以整栋为主 |
| 房屋 | `MassMainGenerator` 等组合 | `HouseGenerator` | 部分 | ✅ | 高（整栋） | LlmPlan 靠多组件拼装 |
| 城堡 | 多组件 + adaptor 回退 | `CastleCompoundGenerator` | 部分 | ✅ | 高（整栋） | 复合城堡走 template |

---

## 3. 构件类型全覆盖表（`ComponentGeneratorRegistry`）

来源：`com.formacraft.common.generation.component.ComponentGeneratorRegistry`

| component_type | 实现类 | 整栋等价？ | 覆盖状态 |
|----------------|--------|------------|----------|
| `TOWER` / `TOWER_*` | `TowerComponentGenerator` | `tower` fallback | 构件✅ 整栋✅ |
| `WALL` / `WALL_SEGMENT` / `FENCE_OR_WALL` | `WallComponentGenerator` | `wall` fallback | 构件✅ 整栋✅ |
| `PATH` / `CONNECTOR` / `BRIDGE*` | `PathComponentGenerator` | `bridge` / path | 构件✅ 整栋✅（桥偏弱） |
| `KEEP` | `KeepGenerator` | `castle_compound` 部分 | 构件✅ |
| `GATE` / `GATE_STRUCTURE` | `GateGenerator` / `GateStructureGenerator` | 复合内嵌 | 构件✅ |
| `ROAD` / `PAVING` | `RoadGenerator` | 城市/复合 | 构件✅ |
| `MASS_*` / `SIDE_WING` | `MassMainGenerator` | `house` | 构件✅ 整栋✅ |
| `ENTRANCE` / `ENTRANCE_CANOPY` | `EntranceGenerator` | — | 构件✅ |
| `ROOF` / `ROOF_STRUCTURE` | `RoofGenerator` | House 内嵌 | 构件✅ |
| `FACADE_WINDOWS` | `FacadeWindowsGenerator` | — | 构件✅ |
| `COURTYARD` / `COURTYARD_SPACE` | `CourtyardSpaceGenerator` | `mingqing_courtyard` | 构件✅ 整栋✅ |
| `BALCONY` / `TERRACE` / `PLAZA` | 各自 Generator | — | 构件✅ |
| `CHIMNEY` / `FOUNDATION` / `DECOR_DETAIL` | 各自 Generator | — | 构件✅ |
| `SIGNAGE` | `SignageGenerator` | — | 构件✅ |

**无构件注册的整栋类型**：见下节 template / landmark（LlmPlan 可通过 `StructureGeneratorAdaptor` 显式调用）。

---

## 4. 整栋专用：Template 路由（`structure_routes_v1.json`）

| template id | generatorKey | LlmPlan 构件等价 | 建议 |
|-------------|--------------|------------------|------|
| `mingqing_courtyard` | `mingqing_courtyard` | 优先 `landmark:mingqing_courtyard` MODULE | 执行器保留；非默认 BuildingSpec 规划 |
| `castle_compound` | `castle_compound` | 部分组件 | 执行器 / 显式 landmark |
| `office_block` / `office_district` | 各自 | 无 | 仅整栋执行器 |
| `cyberpunk_megablock` | `cyberpunk_megablock` | 无 | 仅整栋执行器 |
| `elven_*` / `steampunk_*` | 各自 | 无 | 仅整栋执行器 |
| `japanese_*` | 各自 | 无 | 仅整栋执行器 |
| `pantheon` / `parthenon` / `gothic_cathedral` | 各自 | 无 | 仅整栋执行器 |
| `modern_*` / `brutalist_*` / `parametric_*` | 各自 | 无 | 仅整栋执行器 |
| `birds_nest_stadium` | `birds_nest_stadium` | 无 | 仅整栋执行器 |
| `jiangnan_water_town` | `jiangnan_water_town` | 无 | 仅整栋执行器 |

---

## 5. 整栋专用：Landmark / Archetype（`archetypes_v1.json`）

| archetype id | generatorKey | 构件层 | 建议 |
|--------------|--------------|--------|------|
| `tulou` | `tulou` | adaptor | MODULE / landmark 执行器 |
| `eiffel_tower` | `eiffel_tower` | adaptor | 同上 |
| `temple_of_heaven` | `temple_of_heaven` | adaptor | 同上 |
| `great_wall` | `great_wall` | adaptor | 同上 |
| `golden_gate_bridge` | `golden_gate_bridge` | adaptor | 同上 |
| `giant_wild_goose_pagoda` | `giant_wild_goose_pagoda` | adaptor | 同上 |
| （其余 archetype 见 JSON） | | | 同上 |

实例化：`StructureGeneratorRegistry.create(generatorKey)`（`ArchetypeGeneratorFactory` 已移除）。

---

## 6. BuildingType Fallback（无 template/landmark 时）

仅适用于**显式 BuildingSpec compat** 路由，不是 LlmPlan 默认行为。

| BuildingType | generatorKey | 构件可替代？ |
|--------------|--------------|--------------|
| `TOWER` | `tower` | 部分（质量中） |
| `HOUSE` | `house` | 部分 |
| `CASTLE` | `house` | 否，应用 `castle_compound` template |
| `BRIDGE` | `bridge` | 弱 |
| `WALL` | `wall` | 部分 |
| `CUSTOM` | `house` | — |

---

## 7. 城市生成：Generator Selector（仅 CityBuilder）

用于城市片区内逐栋 `BuildingSpec` 补全（compat 城市路径）：

```
generator_selector_rules_v1.json
    → GeneratorSelectorCatalog
    → GeneratorSelectorRegistry.match(...)
    → RuleBasedGeneratorSelector.apply(spec, ...)
    → GeneratorRouter
```

| 类 | 职责 |
|----|------|
| `GeneratorSelectorCatalog` | JSON POJO |
| `GeneratorSelectorRegistry` | 加载 + 匹配 |
| `RuleBasedGeneratorSelector` | 合并进 BuildingSpec（不覆盖显式字段） |

---

## 8. 调度层收口状态

| 原分散入口 | 当前状态 |
|------------|----------|
| `GeneratorRegistry` | 已删除 → `ComponentGeneratorRegistry` |
| `SmartGeneratorRouter` | 已删除 → `UnifiedGeneratorRouter` |
| `ArchetypeGeneratorFactory` | 已删除 → `StructureGeneratorRegistry` |
| `BuildingSpecRoutingPolicy` | **已删除** → `FormaRequest.requestsLegacyBuildingSpec()` |
| `GeneratorRouter` | 活跃（compat / 执行器） |
| `selector` 三件套 | 活跃（CityBuilder） |
| `BlueprintCompilerRegistry` | 活跃，蓝图专用 |
| `GenerationHub` | **统一门面** |

---

## 9. 后续（指向审计，不在此开第二套台账）

- 兼容路径消费者与删除条件：见审计「弃用退出清单」。
- 指标语义修正：`recordStructureAfterFallback` → PR-2。
- 能力增强：按 §2–§5 补构件质量；降低 selector 命中，**不删**整栋类。

---

## 10. 相关文档

- [GENERATION_PIPELINE.md](GENERATION_PIPELINE.md)
- [GENERATOR_ROUTING_MAP.md](GENERATOR_ROUTING_MAP.md)
- [ARCHITECTURE.md](../ARCHITECTURE.md)
- [ARCHITECTURE_AUDIT.md](refactoring/ARCHITECTURE_AUDIT.md)
- 历史分析：`docs/archive/`（只读）
