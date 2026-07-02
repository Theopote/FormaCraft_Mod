# Generator Routing Map（Phase 0 对照表）

> 生成器合并 Phase 0 产出。记录当前三套活跃体系的完整路由映射，以及已清理的死代码。
> 最后更新：2026-07-02

## 体系总览（Phase 0 后）

| 包路径 | 文件数 | 接口 | 注册表 | 入口 | 状态 |
|--------|--------|------|--------|------|------|
| `server/generator` | 63 | `StructureGenerator` | `GeneratorRouter` | `BuildRequestProcessor`, `CityBuilder`, commands | **活跃** |
| `common/generator` | 23 | `ComponentGenerator` | `GeneratorRegistry` | `ComponentPlanCompiler` → `SmartGeneratorRouter` | **活跃** |
| `server/skeleton/gen` | 54 | `ISkeletonGenerator` | `SkeletonGeneratorRegistry` | `SkeletonBuildService`, `PlanProgramCompiler` | **活跃** |
| ~~`common/gen`~~ | ~~8~~ | ~~`SkeletonGenerator`~~ | ~~`SkeletonAssemblyRegistry`~~ | — | **已删除（Phase 0）** |

```
BuildingSpec 请求  →  StructureGeneratorFactory  →  GeneratorRouter           →  server.generator.*
LlmPlan 组件       →  ComponentPlanCompiler      →  SmartGeneratorRouter      →  common.generator.*
Skeleton/Plan      →  PlanProgramCompiler        →  SkeletonBuildService      →  server.skeleton.gen.*
```

---

## 1. Component Type → `common.generator`（LlmPlan 构件流）

注册表：`com.formacraft.common.generator.GeneratorRegistry`  
路由：`SmartGeneratorRouter.generate()` — **仅查此表，不回退到 server.generator**

| component_type | 实现类 | 备注 |
|----------------|--------|------|
| `TOWER` | `TowerGenerator` | |
| `TOWER_BASE` | `TowerGenerator` | 复用 |
| `TOWER_MID` | `TowerGenerator` | 复用 |
| `TOWER_TOP` | `TowerGenerator` | 复用 |
| `KEEP` | `KeepGenerator` | |
| `WALL` | `WallGenerator` | |
| `WALL_SEGMENT` | `WallGenerator` | 复用 |
| `FENCE_OR_WALL` | `WallGenerator` | 复用 |
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
| `PATH` | `PathGenerator` | |
| `CONNECTOR` | `PathGenerator` | 临时复用 |
| `BRIDGE` | `PathGenerator` | 临时复用 |
| `BRIDGE_CONNECTOR` | `PathGenerator` | 临时复用 |
| `BALCONY` | `BalconyGenerator` | |
| `TERRACE` | `TerraceGenerator` | |
| `TERRACE_PLAZA` | `TerraceGenerator` | 复用 |
| `PLAZA` | `TerraceGenerator` | 复用 |
| `CHIMNEY` | `ChimneyGenerator` | |
| `FOUNDATION` | `FoundationGenerator` | |
| `DECOR_DETAIL` | `DecorDetailGenerator` | |

**扩展路径**（非 GeneratorRegistry，由 SmartGeneratorRouter 附加处理）：

| feature 前缀 | 处理器 |
|--------------|--------|
| `group_request:` | `PlayerComponentGroupExpander` |
| `component_request:` | `PlayerComponentExpander` |

---

## 2. SkeletonType → `server.skeleton.gen`（骨架流）

注册表：`com.formacraft.server.skeleton.gen.SkeletonGeneratorRegistry.createDefault()`  
入口：`SkeletonBuildService.build()`

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

## 3. BuildingSpec 路由 → `server.generator`（整栋建筑流）

注册表/路由：`com.formacraft.server.generator.router.GeneratorRouter`  
入口：`StructureGeneratorFactory.getGenerator(spec)`

### 3.1 路由优先级（按顺序）

| 优先级 | 触发条件 (`spec.extra`) | 目标 |
|--------|-------------------------|------|
| 1 | `styleProfileId` 匹配 | 见 §3.2 |
| 2 | `assembly` 非空 | `MetaAssemblyGenerator` |
| 3 | `blueprint` + compiler 可解析 | `BlueprintStructureGenerator` |
| 4 | `template` 关键词匹配 | 见 §3.3 |
| 5 | `landmark` / archetype 别名 | 见 §3.4 |
| 6 | `genome.archetype.confidence >= 0.85` | 见 §3.4 |
| 7 | `spec.type` fallback | 见 §3.5 |

### 3.2 styleProfileId

| styleProfileId | Generator |
|----------------|-----------|
| `Chinese_Vernacular_Jiangnan_WaterTown` | `JiangnanWaterTownGenerator` |
| `Gothic_Cathedral` | `GothicCathedralGenerator` |
| `Brutalism` | `BrutalistMegastructureGenerator` |
| `Deconstructivism_Zaha` | `ParametricDeconstructivismGenerator` |

### 3.3 template 关键词（`extra.template`）

| 关键词（任一匹配） | Generator |
|--------------------|-----------|
| `mingqing_courtyard`, `mingqing` | `MingQingCourtyardGenerator` |
| `castle_compound`, `castle` | `CastleCompoundGenerator` |
| `office_district`, `office_park`, `office` | `OfficeDistrictGenerator` |
| `office_block` | `OfficeBlockGenerator` |
| `cyberpunk_megablock`, `cyber_megablock`, `cyber_slum_tower`, `夜城`, `赛博巨构`, `贫民窟塔` | `CyberpunkMegaBlockGenerator` |
| `elven_treehouse`, `treehouse`, `elf_treehouse`, `精灵树屋`, `树屋`, `树上小屋` | `ElvenTreehouseGenerator` |
| `mushroom_house`, `mushroom_hut`, `mushroomhouse`, `蘑菇屋`, `蘑菇房`, `蘑菇小屋` | `ElvenMushroomHouseGenerator` |
| `flower_house`, `flower_hut`, `flowerhome`, `花朵屋`, `花屋`, `花房`, `花朵小屋` | `ElvenFlowerHouseGenerator` |
| `jiangnan_water_town`, `water_town`, `watertown` | `JiangnanWaterTownGenerator` |
| `steampunk_airship`, `airship`, `zeppelin`, `飞艇`, `飞船` | `SteampunkAirshipGenerator` |
| `steampunk_factory`, `factory_steampunk`, `steam_factory`, `蒸汽工厂`, `工厂` | `SteampunkFactoryGenerator` |
| `airship_dock`, `steampunk_dock`, `airship_port`, `dock`, `空港`, `码头`, `飞艇码头` | `SteampunkAirshipDockGenerator` |
| `japanese_shrine`, `shrine`, `jinja`, `torii` | `JapaneseShrineGenerator` |
| `japanese_castle_keep`, `castle_keep`, `tenshu`, `天守` | `JapaneseCastleKeepGenerator` |
| `japanese_tea_house`, `tea_house`, `teahouse`, `chashitsu`, `茶室` | `JapaneseTeaHouseGenerator` |
| `pantheon`, `万神殿`, `dome_temple` | `PantheonGenerator` |
| `parthenon`, `帕特农`, `classical_temple`, `greco_roman_temple` | `ParthenonTempleGenerator` |
| `gothic_cathedral`, `cathedral`, `notre_dame`, `cologne`, `哥特` | `GothicCathedralGenerator` |
| `modern_skyscraper`, `highrise`, `skyscraper`, `摩天`, `摩天楼` | `ModernSkyscraperGenerator` |
| `modern_office_campus`, `office_campus`, `office_park`, `campus`, `园区` | `ModernOfficeCampusGenerator` |
| `bauhaus_rowhouse`, `bauhaus`, `rowhouse`, `townhouse`, `terrace`, `联排`, `包豪斯` | `ModernBauhausRowhouseGenerator` |
| `brutalism_megastructure`, `soviet_megastructure`, `brutalism`, `粗野`, `巨构` | `BrutalistMegastructureGenerator` |
| `deconstructivism`, `parametric`, `zaha`, `gehry`, `guggenheim`, `解构`, `参数化` | `ParametricDeconstructivismGenerator` |

### 3.4 Landmark / Archetype（`archetypes_v1.json` → `ArchetypeGeneratorFactory`）

数据源：`assets/formacraft/archetypes/archetypes_v1.json`  
工厂：`ArchetypeGeneratorFactory.fromGeneratorId()`

| archetype id | generatorId | Generator | 备注 |
|--------------|-------------|-----------|------|
| `tulou` | `tulou` | `TulouGenerator` | |
| `eiffel_tower` | `eiffel_tower` | `EiffelTowerGenerator` | |
| `temple_of_heaven` | `temple_of_heaven` | `TempleOfHeavenGenerator` | |
| `great_wall` | `great_wall` | `GreatWallGenerator` | |
| `golden_gate_bridge` | `golden_gate_bridge` | `GoldenGateBridgeGenerator` | |
| `giant_wild_goose_pagoda` | `giant_wild_goose_pagoda` | `GiantWildGoosePagodaGenerator` | |
| `castle_compound` | `castle_compound` | `CastleCompoundGenerator` | 亦可通过 template 路由 |
| `office_district` | `office_district` | `OfficeDistrictGenerator` | 亦可通过 template 路由 |
| `mingqing_courtyard` | `mingqing_courtyard` | — | **缺口**：JSON 有定义，Factory 未映射；走 template 路由 |
| `birds_nest_stadium` | `birds_nest_stadium` | — | **缺口**：JSON 有定义，无 Generator 实现 |

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
| `PathGenerator` | 组件级 | `server.generator.path.PathGenerator` | **独立实现** |

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

## 6. 已知缺口（Phase 1+ 待修）

1. `ArchetypeGeneratorFactory` 缺少 `mingqing_courtyard`、`birds_nest_stadium` 映射
2. `GeneratorRouter.routeByTemplate()` 30+ 硬编码 if，与 `ArchetypeRegistry` 重复
3. `SmartGeneratorRouter` 与 `GeneratorRouter` 无统一 facade
4. `common/generator` 与 `server/generator` 同名类 3 组，维护成本高

---

## 7. 合并路线图（后续 Phase）

| Phase | 内容 | 风险 |
|-------|------|------|
| **0** ✅ | 删 `common/gen`、产出对照表、标记 adaptor | 低 |
| **1** | 统一骨架层到 `server/skeleton/gen`（或迁到 `common/generation/skeleton`） | 中 |
| **2** | `SmartGeneratorRouter` + `StructureGeneratorAdaptor` → 单一 facade | 中 |
| **3** | `server/generator` 归位 + `GeneratorRouter` 与 `GeneratorRegistry` 合并 | 高 |
