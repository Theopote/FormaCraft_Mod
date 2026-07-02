# LlmPlan vs BuildingSpec 迁移进度追踪

> **维护说明**：本文档追踪「构件层（LlmPlan）」与「整栋层（BuildingSpec）」的覆盖关系，**不是**「新系统替代旧系统」的删除清单。  
> 整栋层（`common.generation.structure`）与构件层（`common.generation.component`）均为活跃子系统，统一入口为 `GenerationHub`。  
> 最后更新：**2026-07-02** — 与 `ComponentGeneratorRegistry`、`structure_routes_v1.json`、`archetypes_v1.json` 同步。

## 如何阅读

| 覆盖状态 | 含义 |
|----------|------|
| **构件✅** | `ComponentGeneratorRegistry` 有注册，可走 LlmPlan → `ComponentPlanCompiler` |
| **整栋✅** | `StructureGeneratorRegistry` / `GeneratorRouter` 可路由到专用或 fallback 生成器 |
| **质量：高** | 整栋实现功能完整，生产路径优先（BuildRequestProcessor 注释亦倾向 BuildingSpec 的场景） |
| **质量：中** | 构件/整栋均可用，但构件版较简或行为不一致 |
| **质量：构件简** | 仅有 Palette 驱动的轻量构件实现 |
| **仅整栋** | 地标/模板/蓝图，不适合语义组件化，**合理保留在整栋层** |
| **退役候选** | 构件质量达标后，可考虑缩小整栋 fallback 使用面（**不删除类，先减路由命中**） |

**退役原则（第三步）**：按 **建筑类型 / template / landmark** 逐个评估，**禁止**合并 `TowerComponentGenerator` 与 `TowerGenerator` 为单一上帝类。

---

## 1. 运行时双链路（BuildRequestProcessor）

```
orchestrator.requestBuildingSpec(req)
        │
        ▼
  spec.extra.isLlmPlan ?
        │
   ┌────┴────┐
   │ yes     │ no / 回退
   ▼         ▼
LlmPlanPreviewBuilder   StructureGeneratorFactory
  → ComponentPlanCompiler     → GenerationHub.routeStructure()
  → UnifiedGeneratorRouter    → GeneratorRouter
  → BlockPatch                → GeneratedStructure
```

**仍有意保留双路径的场景**（`BuildingSpecRoutingPolicy` 集中策略）：

- 明清官式四合院：`BuildingSpecRoutingPolicy` 禁用 Composite、跳过 LlmPlan，并默认 `extra.template=mingqing_courtyard`
- 地标、城市、复合结构：`CitySpec` / `CompositeSpec` 走整栋层
- LlmPlan 预览失败时：回退 `GenerationHub.routeStructure()`

实现：`com.formacraft.common.generation.routing.BuildingSpecRoutingPolicy`

### 指标日志（`LlmPlanRoutingMetrics`）

前缀 **`[LlmPlanMetrics]`**，每条日志附带进程内累计快照。

| event | 含义 |
|-------|------|
| `attempt` | `isLlmPlan=true`，进入 LlmPlan 分支 |
| `success` | LlmPlan 预览成功 |
| `error` | 解析/处理失败（已向玩家报错，**不算**整栋回退） |
| `fallback` | 放弃 LlmPlan，原因见 `detail`（`ROUTING_POLICY` / `MISSING_LLM_PLAN_JSON` / `EMPTY_OUTPUT`） |
| `structure_after_fallback` | 回退后实际执行整栋预览 |
| `structure_direct` | 从未标记 LlmPlan，直接整栋预览 |

**回退率** = `fallback / tagged`（见日志尾部 `fallback_rate=…%`）。

```powershell
Select-String "\[LlmPlanMetrics\]" logs/latest.log
```

或运行汇总脚本（fallback 按 `detail` 分布 + 最新 snapshot）：

```powershell
python scripts/analyze_llmplan_metrics.py run/logs/latest.log
```

**解读**（见 §9）：`ROUTING_POLICY` 回退为策略性放弃，不计入「LlmPlan 质量不达标」；评估退役候选时看 **可行动回退率** 与 `success_rate`。

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

**无构件注册的整栋类型**：见下节 template / landmark（LlmPlan 可通过 `StructureGeneratorAdaptor` 显式回退）。

---

## 4. 整栋专用：Template 路由（`structure_routes_v1.json`）

| template id | generatorKey | LlmPlan 构件等价 | 建议 |
|-------------|--------------|------------------|------|
| `mingqing_courtyard` | `mingqing_courtyard` | 无（多栋组合） | **仅整栋**；BuildRequestProcessor 优先 BuildingSpec |
| `castle_compound` | `castle_compound` | 部分组件 | **仅整栋** |
| `office_block` / `office_district` | 各自 | 无 | **仅整栋** |
| `cyberpunk_megablock` | `cyberpunk_megablock` | 无 | **仅整栋** |
| `elven_*` / `steampunk_*` | 各自 | 无 | **仅整栋** |
| `japanese_*` | 各自 | 无 | **仅整栋** |
| `pantheon` / `parthenon` / `gothic_cathedral` | 各自 | 无 | **仅整栋** |
| `modern_*` / `brutalist_*` / `parametric_*` | 各自 | 无 | **仅整栋** |
| `birds_nest_stadium` | `birds_nest_stadium` | 无 | **仅整栋** |
| `jiangnan_water_town` | `jiangnan_water_town` | 无 | **仅整栋** |

---

## 5. 整栋专用：Landmark / Archetype（`archetypes_v1.json`）

| archetype id | generatorKey | 构件层 | 建议 |
|--------------|--------------|--------|------|
| `tulou` | `tulou` | adaptor 可回退 | **仅整栋** |
| `eiffel_tower` | `eiffel_tower` | adaptor | **仅整栋** |
| `temple_of_heaven` | `temple_of_heaven` | adaptor | **仅整栋** |
| `great_wall` | `great_wall` | adaptor | **仅整栋** |
| `golden_gate_bridge` | `golden_gate_bridge` | adaptor | **仅整栋** |
| `giant_wild_goose_pagoda` | `giant_wild_goose_pagoda` | adaptor | **仅整栋** |
| （其余 archetype 见 JSON） | | | **仅整栋** |

实例化：`StructureGeneratorRegistry.create(generatorKey)`（`ArchetypeGeneratorFactory` 已移除）。

---

## 6. BuildingType Fallback（无 template/landmark 时）

| BuildingType | generatorKey | 构件可替代？ |
|--------------|--------------|--------------|
| `TOWER` | `tower` | 部分（质量中） |
| `HOUSE` | `house` | 部分（整栋质量高） |
| `CASTLE` | `house` | 否，应用 `castle_compound` template |
| `BRIDGE` | `bridge` | 弱 |
| `WALL` | `wall` | 部分 |
| `CUSTOM` | `house` | — |

---

## 7. 城市生成：Generator Selector（仅 CityBuilder）

与 LlmPlan/BuildingSpec 主链 **独立**，用于城市片区内逐栋 `BuildingSpec` 补全：

```
generator_selector_rules_v1.json
    → GeneratorSelectorCatalog（JSON 数据模型）
    → GeneratorSelectorRegistry.match(...)
    → RuleBasedGeneratorSelector.apply(spec, ...)   // CityBuilder 调用
    → 写入 extra.template / landmark / type 等
    → GeneratorRouter（与单体 BuildingSpec 相同）
```

| 类 | 职责 |
|----|------|
| `GeneratorSelectorCatalog` | JSON POJO（`Rule` / `When` / `Then`），**不是** Registry 的薄封装 |
| `GeneratorSelectorRegistry` | 加载资源 + 按城市风格/分区/形状匹配规则 |
| `RuleBasedGeneratorSelector` | 将匹配结果合并进 `BuildingSpec`（不覆盖用户/LLM 显式字段）；generator intent 全部由 `generator_selector_rules_v1.json` 驱动（Phase 8 ✅） |

---

## 8. 调度层收口状态

| 原分散入口 | 当前状态 |
|------------|----------|
| `GeneratorRegistry` | 已删除 → `ComponentGeneratorRegistry` |
| `SmartGeneratorRouter` | 已删除 → `UnifiedGeneratorRouter` |
| `ArchetypeGeneratorFactory` | 已删除 → `StructureGeneratorRegistry` |
| `GeneratorRouter` | 活跃，数据驱动 |
| `selector` 三件套 | 活跃，见 §7 |
| `BlueprintCompilerRegistry` | 活跃，蓝图专用 |
| `GenerationHub` | **统一门面** |

---

## 9. 下一步（可勾选）

**当前唯一 P0**：积累 `[LlmPlanMetrics]` 数据，再评估 §2–§6 中的「退役候选」。

- [ ] 按用户场景统计 LlmPlan 预览成功率 vs BuildingSpec 回退率（见 `LlmPlanRoutingMetrics`，日志前缀 `[LlmPlanMetrics]`）
- [ ] 对 §2「质量：中」项制定构件增强计划（不合并类）
- [x] 四合院等场景：在 `BuildingSpecRoutingPolicy` 中显式化「强制 BuildingSpec」（替代仅注释）
- [ ] 某 template 构件化达标后：从 selector / 默认路由降低命中，**而非**删除整栋生成器

### `fallback_rate` 评估指南

| 阶段 | 条件 | 动作 |
|------|------|------|
| 观察 | `tagged` < 50 | 只收集日志，不做路由/退役决策 |
| 初评 | `tagged` ≥ 50 | 按 `detail` 拆分 fallback 原因 |
| 决策 | `tagged` ≥ 200 且连续两周趋势稳定 | 可勾选具体 template / 类型的退役候选 |

**拆分 fallback 原因**（`event=fallback` 的 `detail`）：

| detail | 含义 | 是否反映 LlmPlan 质量 |
|--------|------|------------------------|
| `ROUTING_POLICY` | `BuildingSpecRoutingPolicy` 主动跳过（四合院、地标整栋等） | 否 — 预期行为 |
| `MISSING_LLM_PLAN_JSON` | orchestrator 未产出 `llmPlan` JSON | 否 — 上游/编排问题 |
| `EMPTY_OUTPUT` | LlmPlan 解析成功但构件编译无块 | **是** — 构件层缺口 |

**建议派生指标**（从日志或 `Snapshot` 手算）：

- **可行动回退率** = `(MISSING_LLM_PLAN_JSON + EMPTY_OUTPUT) / tagged` — 驱动构件增强与编译器修复
- **策略回退占比** = `ROUTING_POLICY / fallback` — 高占比说明总 `fallback_rate` 被策略抬高，不宜单独作为退役依据
- **有效成功率** = `success / tagged` — 与 `success_rate` 相同；目标是在非策略路径上稳步上升

**退役候选门槛**（须同时满足，见文首「退役原则」）：

1. 该建筑类型 / template 的 LlmPlan 请求在样本中 `EMPTY_OUTPUT` 占比 < 5%
2. `success_rate` 对该类型 ≥ 80%（且 `tagged` 对该类型 ≥ 30）
3. 构件输出质量经人工抽检不低于整栋 fallback 结果
4. 操作仅为降低 selector / 默认路由命中或缩小 adaptor 回退面 — **不删除**整栋生成器类

```powershell
# 按原因统计 fallback（PowerShell 一行版）
Select-String "\[LlmPlanMetrics\].*event=fallback" logs/latest.log |
  ForEach-Object { if ($_ -match 'detail=(\w+)') { $matches[1] } } |
  Group-Object | Sort-Object Count -Descending

# 推荐：完整汇总（含 snapshot、可行动回退率提示）
python scripts/analyze_llmplan_metrics.py run/logs/latest.log
```

---

## 10. 相关文档

- 路由对照：`docs/GENERATOR_ROUTING_MAP.md`
- 架构入口：`ARCHITECTURE.md` → Generation system target state
- 历史分析：`docs/archive/DUAL_SYSTEM_ANALYSIS.md`（现状描述，以本文档为迁移进度准绳）
