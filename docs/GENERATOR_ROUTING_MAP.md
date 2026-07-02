# Generator Routing Map（Phase 0 对照表）

> 生成器合并 Phase 0 产出。记录当前三套活跃体系的完整路由映射，以及已清理的死代码。
> 最后更新：2026-07-02

## 体系总览（Phase 1 后）

| 包路径 | 文件数 | 接口 | 注册表 / 门面 | 入口 | 状态 |
|--------|--------|------|---------------|------|------|
| `common/generation/structure` | 65 | `StructureGenerator` | `GeneratorRouter` → `StructureGeneratorRegistry` + `StructureRouteCatalog` | `GenerationHub.routeStructure()` | **活跃** |
| `common/generation/component` | 25 | `ComponentGenerator` | `ComponentGeneratorRegistry` | `ComponentPlanCompiler` → `UnifiedGeneratorRouter` | **活跃（Phase 6b 迁入）** |
| `common/skeleton` | +4 | `SkeletonExecutor` | `SkeletonExecutors` | `PlanProgramCompiler` | **契约层（Phase 1）** |
| `server/skeleton/gen` | 53 | `ISkeletonGenerator` | `SkeletonGeneratorRegistry` | `SkeletonBuildService`（实现 `SkeletonExecutor`） | **活跃** |
| ~~`common/gen`~~ | ~~8~~ | — | — | — | **已删除（Phase 0）** |

### 骨架层双模型（Phase 1 统一契约，执行仍分路径）

| 模型 | 包 | 用途 | 转换 |
|------|-----|------|------|
| `SkeletonPlan` | `common/skeleton/*Plan` | Blueprint / Interpreter 路径 | `SkeletonPlanConverter.toExecutable()` |
| `ExecutableSkeletonPlan` | `common/skeleton` | LLM / Generator 路径 | 直接输入 `SkeletonExecutor` |

```
LlmPlan / PlanProgram  →  ExecutableSkeletonPlan  →  SkeletonExecutors.get()  →  SkeletonBuildService  →  ISkeletonGenerator
Blueprint              →  SkeletonPlan            →  Interpreter（不变）     →  PlannedBlock
Blueprint（未来）       →  SkeletonPlanConverter   →  ExecutableSkeletonPlan  →  SkeletonExecutor（Phase 2 可选接入）
```


---

## 1. Component Type → `common.generation.component`（LlmPlan 构件流）

注册表：`com.formacraft.common.generation.component.ComponentGeneratorRegistry`  
（`GeneratorRegistry` 已弃用，委托至 `ComponentGeneratorRegistry`）  
路由：`UnifiedGeneratorRouter.generate()` — 构件层统一门面（Phase 2）

### 路由优先级

| 优先级 | 条件 | 目标 |
|--------|------|------|
| 1 | `params.skeleton` 或 `skeleton:` feature | `SkeletonExecutors` → `SkeletonBuildService` |
| 2 | `ComponentGeneratorRegistry` 有注册 | `ComponentGenerator` |
| 3 | `group_request:` / `component_request:` feature | `PlayerComponentGroupExpander` / `PlayerComponentExpander` |
| 4 | 显式整栋回退（见下）且上一步无结果 | `StructureGeneratorAdaptor` → `GeneratorRouter` |

**整栋回退触发条件**（已注册组件返回空时不会自动回退）：
- feature：`landmark:`、`structure_generator:`
- params：`landmark`、`template`、`blueprint`、`assembly`、`useStructureGenerator=true`
- `genome.archetype.confidence >= 0.85`
- 未注册且类型为 `HOUSE` / `CASTLE` / `COMPOUND` / `LANDMARK` / `BUILDING`

`SmartGeneratorRouter` 已弃用，委托至 `UnifiedGeneratorRouter`。

| component_type | 实现类 | 备注 |
|----------------|--------|------|
| `TOWER` | `TowerComponentGenerator` | 构件层；整栋层见 `structure.TowerGenerator` |
| `TOWER_BASE` | `TowerComponentGenerator` | 复用 |
| `TOWER_MID` | `TowerComponentGenerator` | 复用 |
| `TOWER_TOP` | `TowerComponentGenerator` | 复用 |
| `KEEP` | `KeepGenerator` | |
| `WALL` | `WallComponentGenerator` | 构件层；整栋层见 `structure.WallGenerator` |
| `WALL_SEGMENT` | `WallComponentGenerator` | 复用 |
| `FENCE_OR_WALL` | `WallComponentGenerator` | 复用 |
| `GATE` | `GateGenerator` | |
| `ROAD` | `RoadGenerator` | |
| `PAVING` | `RoadGenerator` | 临时复用 |
| `MASS_MAIN` | `MassMainGenerator` | |
| `MASS_SECONDARY` | `MassMainGenerator` | 复用 |
| `SIDE_WING` | `MassMainGenerator` | 复用 |
| `MASS_WING` | `MassMainGenerator` | 复用 |
| `ENTRANCE` | `EntranceGenerator` | |
| `ENTRANCE_CANOPY` | `EntranceGenerator` | 复用 |
| `SIGNAGE` | `SignageGenerator` | |
| `FACADE_WINDOWS` | `FacadeWindowsGenerator` | |
| `ROOF` | `RoofGenerator` | |
| `ROOF_STRUCTURE` | `RoofGenerator` | 复用 |
| `COURTYARD_SPACE` | `CourtyardSpaceGenerator` | |
| `COURTYARD` | `CourtyardSpaceGenerator` | 复用 |
| `GATE_STRUCTURE` | `GateStructureGenerator` | |
| `PATH` | `PathComponentGenerator` | 构件层；路径整栋见 `structure.path.PathGenerator` |
| `CONNECTOR` | `PathComponentGenerator` | 临时复用 |
| `BRIDGE` | `PathComponentGenerator` | 临时复用 |
| `BRIDGE_CONNECTOR` | `PathComponentGenerator` | 临时复用 |
| `BALCONY` | `BalconyGenerator` | |
| `TERRACE` | `TerraceGenerator` | |
| `TERRACE_PLAZA` | `TerraceGenerator` | 复用 |
| `PLAZA` | `TerraceGenerator` | 复用 |
| `CHIMNEY` | `ChimneyGenerator` | |
| `FOUNDATION` | `FoundationGenerator` | |
| `DECOR_DETAIL` | `DecorDetailGenerator` | |

**扩展路径**（非 ComponentGeneratorRegistry，由 UnifiedGeneratorRouter 附加处理）：

| feature 前缀 | 处理器 |
|--------------|--------|
| `group_request:` | `PlayerComponentGroupExpander` |
| `component_request:` | `PlayerComponentExpander` |

---

## 2. SkeletonType → `server.skeleton.gen`（骨架流）

注册表：`com.formacraft.server.skeleton.gen.SkeletonGeneratorRegistry.createDefault()`  
入口：`SkeletonExecutors.get().build()` → `SkeletonBuildService`（`server/skeleton/gen`）

| SkeletonType | 实现类 |
|--------------|--------|
| `LINEAR_PATH` | `LinearPathGenerator` |
| `PATH_POLYLINE` | `path.PathSkeletonGenerator` |
| `CONTOUR_FOLLOW` | `ContourFollowGenerator` |
| `RADIAL_RING` | `RadialRingGenerator` |
| `RADIAL_SPOKE` | `RadialSpokeGenerator` |
| `VERTICAL_STACK` | `VerticalStackGenerator` |
| `VERTICAL_TAPER` | `VerticalTaperGenerator` |
| `GRID` | `GridGenerator` |
| `COURTYARD` | `CourtyardGenerator` |
| `PERIMETER_LOOP` | `PerimeterLoopGenerator` |
| `ENCLOSURE` | `EnclosureGenerator` |
| `SPAN_SUSPENSION` | `SpanSuspensionGenerator` |
| `TERRACED` | `TerracedGenerator` |
| `HIERARCHICAL_TREE` | `HierarchicalTreeGenerator` |
| `COMPOUND` | `CompoundGenerator` |
| *(未注册)* | `UnsupportedSkeletonGenerator`（fallback） |

---

## 3. BuildingSpec 路由 → `common.generation.structure`（整栋建筑流）

注册表/路由：`com.formacraft.common.generation.structure.router.GeneratorRouter`  
入口：`StructureGeneratorFactory.getGenerator(spec)`

注册表/路由：`GeneratorRouter` → `StructureRouteCatalog`（JSON）+ `StructureGeneratorRegistry`（实例化）  
统一入口：`com.formacraft.server.generation.GenerationHub.routeStructure(spec)`

### 3.1 路由优先级（按顺序）

| 优先级 | 触发条件 (`spec.extra`) | 解析方式 |
|--------|-------------------------|----------|
| 1 | `styleProfileId` | `structure_routes_v1.json` → `styleProfiles` |
| 2 | `assembly` 非空 | `StructureGeneratorRegistry` → `meta_assembly` |
| 3 | `blueprint` + compiler 可解析 | `blueprint_structure` |
| 4 | `template` 关键词 | `structure_routes_v1.json` → `templateRoutes`（有序匹配） |
| 5 | `landmark` / archetype 别名 | `ArchetypeRegistry` → `generatorKey` |
| 6 | `genome.archetype.confidence >= 0.85` | 同上 |
| 7 | `spec.type` fallback | `structure_routes_v1.json` → `buildingTypeFallback` |

Template / styleProfile 路由数据文件：`assets/formacraft/generation/structure_routes_v1.json`  
新增模板只需编辑 JSON + 在 `StructureGeneratorRegistry` 注册 `generatorKey`（若有新实现类）。

### 3.2 styleProfileId（数据驱动，原硬编码已移除）

| styleProfileId | Generator |
|----------------|-----------|
| `Chinese_Vernacular_Jiangnan_WaterTown` | `jiangnan_water_town` |
| `Gothic_Cathedral` | `gothic_cathedral` |
| `Brutalism` | `brutalist_megastructure` |
| `Deconstructivism_Zaha` | `parametric_deconstructivism` |

### 3.3 template 关键词（`structure_routes_v1.json`，有序匹配）

见 JSON 文件 `assets/formacraft/generation/structure_routes_v1.json`。  
**修复**：`office_block` 现排在 `office` 之前，避免误路由到 `office_district`。

### 3.4 Landmark / Archetype（`archetypes_v1.json` → `StructureGeneratorRegistry`）

数据源：`assets/formacraft/archetypes/archetypes_v1.json`  
工厂：`ArchetypeGeneratorFactory.fromGeneratorId()`

| archetype id | generatorKey | 备注 |
|--------------|--------------|------|
| `tulou` | `tulou` | |
| `eiffel_tower` | `eiffel_tower` | |
| `temple_of_heaven` | `temple_of_heaven` | |
| `great_wall` | `great_wall` | |
| `golden_gate_bridge` | `golden_gate_bridge` | |
| `giant_wild_goose_pagoda` | `giant_wild_goose_pagoda` | |
| `castle_compound` | `castle_compound` | 亦可通过 template 路由 |
| `office_district` | `office_district` | 亦可通过 template 路由 |
| `mingqing_courtyard` | `mingqing_courtyard` | **Phase 3 已修复** Factory 映射 |
| `birds_nest_stadium` | `birds_nest_stadium` | **Phase 4 已实现** |

触发方式：
- `extra.landmark` → `ArchetypeRegistry.matchByKeyword()` → Factory
- `extra.genome.archetype.id`（confidence ≥ 0.85）→ Factory

### 3.5 BuildingType fallback

| BuildingType | Generator |
|--------------|-----------|
| `TOWER` | `TowerGenerator` |
| `HOUSE` | `HouseGenerator` |
| `CASTLE` | `HouseGenerator` |
| `BRIDGE` | `BridgeGenerator` |
| `WALL` | `WallGenerator` |
| `CUSTOM` | `HouseGenerator`（warn） |

### 3.6 Blueprint compiler（`extra.blueprint`）

注册表：`BlueprintCompilerRegistry`

| Compiler id | 类 |
|-------------|-----|
| `castle_v1` | `CastleBlueprintCompiler`（adapter） |
| `tulou` | `TulouBlueprintCompiler` |
| `temple_of_heaven` | `TempleOfHeavenBlueprintCompiler` |
| `great_wall` | `GreatWallBlueprintCompiler` |
| `eiffel_tower` | `EiffelTowerBlueprintCompiler` |
| `golden_gate_bridge` | `GoldenGateBridgeBlueprintCompiler` |
| `giant_wild_goose_pagoda` | `GiantWildGoosePagodaBlueprintCompiler` |

解析后统一由 `BlueprintStructureGenerator` 执行。

---

## 4. 同名类冲突（平行演化，非继承）

| 类名 | common.generator | server.generator | 关系 |
|------|------------------|------------------|------|
| `TowerGenerator` | 组件级，Palette 驱动，`BlockPatch` | 整栋级，`BuildingSpec` 驱动，`GeneratedStructure` | **独立实现** |
| `WallGenerator` | 组件级 | 整栋级 | **独立实现** |
| `PathGenerator` | 组件级 | `common.generation.structure.path.PathGenerator` | **独立实现** |

Phase 2 目标：common 侧保留组件实现，server 侧通过 `StructureGeneratorAdaptor` 暴露，最终合并命名空间。

---

## 5. Phase 0 清理记录

### 已删除：`common/gen`（8 文件，零外部引用）

| 文件 | 替代 |
|------|------|
| `SkeletonGenerator` | `server.skeleton.gen.ISkeletonGenerator` |
| `SkeletonAssemblyRegistry` | `SkeletonGeneratorRegistry` |
| `GeneratorContext` | `GenerationContext` |
| `PaletteResolver` | `palette.SemanticPaletteResolver` |
| `LinearPathRoadGenerator` | `LinearPathGenerator` |
| `PolylineRoadGenerator` | `path.PathSkeletonGenerator` |
| `RadialRingCourtyardGenerator` | `RadialRingGenerator`（功能较弱，server 侧已覆盖） |
| `SimplePaletteResolver` | `SemanticPaletteResolver` |

### 保留待定：`StructureGeneratorAdaptor`

- **决策**：保留，Phase 2 接入 `UnifiedGeneratorRouter`
- **原因**：`SmartGeneratorRouter` 已切断回退（避免整栋生成覆盖组件）；适配器是合并桥梁，不是死代码
- **当前**：无调用方

---

## 6. 已知缺口（后续待修）

1. ~~`common/generator` 包尚未迁至 `common/generation/component`~~ ✅ Phase 6b 完成
2. `GeneratorRegistry` 已弃用，待调用方全部迁移后可删除

---

## 7. GenerationHub（Phase 3 统一入口）

| 方法 | 粒度 | 委托 |
|------|------|------|
| `GenerationHub.routeStructure(spec)` | 整栋 | `StructureGeneratorFactory` |
| `GenerationHub.generateComponent(semantic, world)` | 构件 | `UnifiedGeneratorRouter` |
| `GenerationHub.buildSkeleton(world, origin, plan, palette)` | 骨架 | `SkeletonExecutors` |

---

## 8. 合并路线图

| Phase | 内容 | 风险 |
|-------|------|------|
| **0** ✅ | 删 `common/gen`、产出对照表、标记 adaptor | 低 |
| **1** ✅ | `ExecutableSkeletonPlan` 迁至 common、`SkeletonExecutor` 门面、`SkeletonPlanConverter` | 低 |
| **2** ✅ | `UnifiedGeneratorRouter` + `StructureGeneratorAdaptor` 受控回退 | 中 |
| **3** ✅ | 数据驱动整栋路由 + `GenerationHub` 统一入口 | 中 |
| **4** ✅ | `birds_nest_stadium` 实现 + 主路径接入 `GenerationHub` | 低 |
| **5** ✅ | `server/generator` → `common/generation/structure` 包迁移 | 中 |
| **6** ✅ | `ComponentGeneratorRegistry` + 构件层 `*ComponentGenerator` 消歧 | 低 |
| **6b** ✅ | `common/generator` → `common/generation/component` 包迁移 | 低 |
