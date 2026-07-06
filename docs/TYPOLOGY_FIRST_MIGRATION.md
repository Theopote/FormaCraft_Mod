# Typology-First 架构迁移方案

> **状态**：Phase 0–5 已完成（2026-07-06）  
> **政策**：冻结新地标专用 Generator；新中式/古建需求走 **Structural Typology + 参数化解释器**，地标仅作 RAG 比例与风格参考。

## 1. 背景与问题

当前 `StructureGeneratorRegistry` 中约 82% 为「一建筑一 Generator」的 MODULE 路由。该模式导致：

- 每新增一座地标就要写 Java 类 + Blueprint + 注册，无法扩展；
- `MODULE + landmark:<id>` 绕过组合式 LlmPlan，AI 无法按规模/开间/层数参数化规划；
- culture card 的 `landmarkModuleId` 与 archetype `generatorId` 强绑定，混淆了**结构类型**与**实例参考**。

业界对照：BuildingBlock（APT）、T2BM interlayer、Infinigen 规则族——均以有限 **typology 解释器** + 参数为主，实例仅作条件或比例参考。

## 2. 四层本体

```
Skeleton（骨架类型）
  └── VERTICAL_STACK | GRID_BAY | COMPOUND | RADIAL_RING | ...
        └── Structural Typology（结构类型学）
              └── dense_eaves_pagoda | tailiang_timber_hall | ...
                    └── Style（风格层）
                          └── Chinese_Traditional | Tang_Dynasty_Timber | ...
                                └── Instance / Landmark（实例，仅 RAG 参考）
                                      └── famen_pagoda | foguang_temple_hall | ...
```

| 层 | 职责 | 谁决定 | 谁执行 |
|---|---|---|---|
| Skeleton | 空间组织范式 | AI / profile | LlmPlan `layout.skeleton_type` |
| **Typology** | 结构语法 + 参数 schema | AI + RAG | Java **TypologyInterpreter** |
| Style | 材质/色彩/装饰 | AI + culture card | style_profile + decorators |
| Instance | 真实建筑比例锚点 | RAG proportion card | proportion_hints（非硬路由） |

## 3. 核心原则

1. **Typology 优先**：规划阶段输出 `structural_typology` + `typology_params`，而非默认 `landmark_module`。
2. **地标降级为参考**：archetype 标记 `researchOnly`；命中专有名词时绑定 `referenceLandmarkId` 拉取比例卡，不强制 MODULE。
3. **Legacy 适配**：Phase 0–1 通过 `legacyInterpreterId` 将 typology 委托给现有 Generator（如 `FamenPagodaGenerator`），避免大爆炸重写。
4. **组合式兜底**：无 typology 解释器时，退回 `MASS_MAIN + ROOF + ...` 组合，proportion_hints 仍带 typology 字段。

## 4. 注册表 Schema（`structural_typologies_v1.json`）

存放路径：

```
src/main/resources/assets/formacraft/structural_typologies/structural_typologies_v1.json
```

### 4.1 顶层字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `version` | int | 当前为 `1` |
| `schema` | object | 自描述 JSON Schema 片段（文档用） |
| `typologies` | object[] | 结构类型定义 |
| `migrationMap` | object | 旧 `landmarkModuleId` → 新 typology 映射 |

### 4.2 Typology 对象

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | ✓ | 稳定主键，如 `dense_eaves_pagoda` |
| `displayName` | `{zh, en}` | ✓ | 展示名 |
| `skeletonType` | string | ✓ | 对应 LlmPlan skeleton |
| `styleFamilies` | string[] | | 推荐 style_profile 族 |
| `matchKeywords` | string[] | ✓ | 检索关键词（含通用类型词） |
| `negativeKeywords` | string[] | | 负向词，防串味 |
| `interpreterId` | string | | Java 解释器 id（Phase 1+） |
| `legacyInterpreterId` | string | | Phase 0 委托的旧 generator id |
| `routingPolicy` | string | | `typology_first` \| `legacy_module_fallback` \| `compositional_only` |
| `defaultParams` | object | | 默认 typology 参数 |
| `paramSchema` | object | | 参数约束（min/max/ideal/desc） |
| `referenceLandmarks` | object[] | | 实例参考列表 |
| `proportionCardIds` | string[] | | 关联比例卡 |
| `cultureCardIds` | string[] | | 关联文化卡 |
| `llmPlanGuidance` | string | | 注入 LLM 的自然语言指引 |

### 4.3 Reference Landmark 对象

| 字段 | 说明 |
|---|---|
| `archetypeId` | `archetypes_v1.json` 中的 id |
| `role` | `primary` \| `secondary` |
| `notes` | 研究备注 |

### 4.4 Migration Map 条目

```json
"famen_pagoda": {
  "typologyId": "dense_eaves_pagoda",
  "phase": "typology_first",
  "legacyModuleId": "famen_pagoda",
  "deprecatedAfter": "2026-Q4"
}
```

## 5. Famen / Foguang 迁移映射

### 5.1 法门寺舍利塔

| 维度 | 旧 | 新 |
|---|---|---|
| 路由 | `MODULE + landmark:famen_pagoda` | `structural_typology: dense_eaves_pagoda` |
| archetype | `generatorId=famen_pagoda` | `typologyId=dense_eaves_pagoda`, `researchOnly=true` |
| culture card | `landmarkModuleId=famen_pagoda` | `structuralTypologyId=dense_eaves_pagoda` |
| proportion | `typology=famen_pagoda` | `typology=dense_eaves_pagoda`（id 仍为 `famen_pagoda` 卡） |
| Java | `FamenPagodaGenerator` | `legacyInterpreterId=famen_pagoda` 直至通用解释器就绪 |

**关键参数**（`dense_eaves_pagoda`）：

- `levels`, `height`, `baseWidth`, `footprint=octagon`, `setback_ratio`
- `niche_rhythm`（券窗按层序轮转八面）
- `detailLevel`, `facing`

**通用触发词**：密檐塔、唐代砖塔、dense eaves pagoda（无需说「法门寺」）

### 5.2 佛光寺东大殿

| 维度 | 旧 | 新 |
|---|---|---|
| 路由 | culture 卡 `landmarkModuleId`（archetype 已 researchOnly） | `structural_typology: tailiang_timber_hall` |
| archetype | `researchOnly=true` | 增加 `typologyId=tailiang_timber_hall` |
| culture card | `landmarkModuleId=foguang_temple_hall` | `structuralTypologyId=tailiang_timber_hall` |
| proportion | `typology=foguang_temple_hall` | `typology=tailiang_timber_hall` |
| Java | `FoguangTempleHallGenerator` | `legacyInterpreterId=foguang_temple_hall` |

**关键参数**（`tailiang_timber_hall`）：

- `baysX`, `baysZ`, `width`, `depth`, `hallHeight`
- `includeSubEaves`, `puzuoProfile`, `roofType=wudian`
- `referenceLandmarkId=foguang_temple_hall`（可选，拉取七开间比例）

**通用触发词**：抬梁式大殿、唐代木构、七开间庑殿顶、tailiang hall

### 5.3 大慈恩寺大雁塔（Phase 3）

| 维度 | 旧 | 新 |
|---|---|---|
| 路由 | `MODULE + landmark:giant_wild_goose_pagoda` | `structural_typology: dense_eaves_pagoda` + `footprint=square` |
| archetype | `generatorId=giant_wild_goose_pagoda` | `typologyId=dense_eaves_pagoda`, `researchOnly=true` |
| culture card | 无专用卡 | `structuralTypologyId=dense_eaves_pagoda` |
| proportion | 无专用卡 | `typology=dense_eaves_pagoda`（id 为 `giant_wild_goose_pagoda` 卡） |
| Java | `GiantWildGoosePagodaGenerator` | `DenseEavesPagodaBuilder` square 分支 + `TypologyReferencePresets` |

**关键参数**（`dense_eaves_pagoda` + `reference_landmark=giant_wild_goose_pagoda`）：

- `footprint=square`, `levels=7`, `height≈41`, `baseWidth≈17`
- `niche_rhythm=none`（无八面券窗轮转）
- `detailLevel=aesthetic`, `facing`

**通用触发词**：大雁塔、大慈恩寺、dayanta、giant wild goose pagoda

## 6. 数据流（Phase 0）

```
用户输入
  ↓
keyword_culture_retriever → structuralTypologyId + proportionCard
  ↓
typology_retriever.resolve_typology_for_intent()
  ↓
building_research_agent.finalize_profile_minecraft_strategy()
  → minecraft_strategy.structural_typology = dense_eaves_pagoda
  → landmark_module = null（typology_first 政策）
  ↓
ai_planner prompt 注入 TYPOLOGY GUIDANCE + proportion_hints.typology
  ↓
LlmPlan: layout.skeleton_type + proportion_hints +（Phase 0 仍可 MODULE fallback）
  ↓
Java StructuralTypologyRegistry → legacyInterpreterId → 现有 Generator
```

## 7. 分阶段 rollout

| Phase | 内容 | 状态 |
|---|---|---|
| **0** | Schema + migrationMap + RAG/plan 优先 typology；legacy MODULE 作 fallback | **已完成** |
| **1** | `TypologyInterpreterRegistry` + `TypologyComponentRouter`；STRUCTURE/typology 构件路由；legacy 委托 Famen/Foguang | **已完成** |
| **2** | `DenseEavesPagodaBuilder` / `TailiangTimberHallBuilder` + 原生解释器；`ChineseTypologyDetailUtil` 迁入 typology 包 | **已完成** |
| **3** | 大雁塔等迁入 `dense_eaves_pagoda`（`footprint=square`）；`GiantWildGoosePagodaGenerator` 瘦身为 Builder 委托 | **已完成** |
| **4** | `TypologyRoutingMetrics` 遥测；LLM MODULE→STRUCTURE 自动修复；Java 路由优先原生 typology builder | **已完成** |
| **5** | 天坛迁入 `radial_terrace_hall`（`RADIAL_RING`）；`TempleOfHeavenGenerator` 瘦身为 Builder 委托 | **已完成** |
| **6** | 收紧 legacy MODULE：migrationMap 内地标禁止/降级 MODULE 硬路由；Java/Python 双侧阻断 | **已完成** |
| **7** | 移除 Phase-0 fallback 文案；删除 deprecated generator 委托链（`LegacyDelegatingTypologyInterpreter` + 4 个瘦身材质 Generator） | **已完成** |

### Phase 7 细节（删除 deprecated 委托链）

| 删除项 | 替代路径 |
|---|---|
| `FamenPagodaGenerator` / `GiantWildGoosePagodaGenerator` | `TypologyBackedStructureGenerator("dense_eaves_pagoda")` → `DenseEavesPagodaBuilder` |
| `FoguangTempleHallGenerator` | `TypologyBackedStructureGenerator("tailiang_timber_hall")` |
| `TempleOfHeavenGenerator` | `TypologyBackedStructureGenerator("radial_terrace_hall")` |
| `LegacyDelegatingTypologyInterpreter` | 原生 `*Interpreter` 已注册，不再 bootstrap legacy 委托 |
| culture/proportion 卡 `Phase-0 fallback` 文案 | 已移除；culture 卡删除 `landmarkModuleId` |
| `structural_typologies_v1.json` `legacyInterpreterId` | 已移除；`interpreterId` 直连 typology builder |

### Phase 6 细节（legacy MODULE 收紧）

| 层级 | 行为 |
|---|---|
| Python RAG | `landmarkModuleId` 不再注入 prompt（migrated 地标） |
| Python 路由 | `resolve_landmark_module_routing` → `STRUCTURE + typology:*` + `FORBIDDEN MODULE` |
| Plan sanitize | 任意 migrated `MODULE` 剥离/修复；禁止注入 migrated `landmark_module` |
| Java prompt | `LandmarkModuleRegistry` 排除 researchOnly + migrationMap 条目 |
| Java 路由 | `LandmarkRoutingPolicy` 跳过 migrated 指名强制 MODULE |
| Java 构件 | `TypologyComponentRouter` 从 `landmark:` feature 解析 typology；`StructureGeneratorAdaptor` 不写 `extra.landmark` |

**仍保留 MODULE 的地标**（未迁入 migrationMap）：`birds_nest_stadium`, `golden_gate_bridge`, `gothic_cathedral` 等。

### Phase 5 细节（天坛 / 祈年殿）

| 维度 | 旧 | 新 |
|---|---|---|
| 路由 | `MODULE + landmark:temple_of_heaven` | `structural_typology: radial_terrace_hall` |
| archetype | `generatorId=temple_of_heaven` | `typologyId=radial_terrace_hall`, `researchOnly=true` |
| culture card | `landmarkModuleId` only | `structuralTypologyId=radial_terrace_hall` |
| proportion | `typology=temple_of_heaven` | `typology=radial_terrace_hall` |
| Java | `TempleOfHeavenGenerator` | `RadialTerraceHallBuilder` + `RadialTerraceHallInterpreter` |

**关键参数**：`baseRadius`, `tiers`, `height`, `hallRadius`, `facing`；`layout.skeleton_type=RADIAL_RING`

### Phase 4 细节

| 能力 | 实现 |
|---|---|
| 遥测 | `[TypologyMetrics]` — `typology_structure_hit` / `deprecated_module_use` / `legacy_redirect` |
| Plan 修复 | `typology_plan_repair.py` — migrationMap 内 landmark MODULE 自动转为 `STRUCTURE + typology:*` |
| 路由优先 | `GeneratorRouter.routeByTypology` 走 `interpreterId`（`TypologyBackedStructureGenerator`） |
| Few-shot | `dayanta_dense_eaves_typology.json` 注入大雁塔 culture 卡 |

## 8. 冻结政策

- **禁止**新增 `StructureGeneratorRegistry.register("<新地标>", ...)` 除非 ADR 豁免。
- 新需求流程：先扩 `structural_typologies_v1.json` → proportion/culture 卡 → 解释器参数化。
- `landmarkModuleId` 字段标记 **deprecated**；新卡只写 `structuralTypologyId`。

## 9. 相关文件索引

| 用途 | 路径 |
|---|---|
| Typology 注册表 | `structural_typologies/structural_typologies_v1.json` |
| Python 加载 | `python_backend/app/services/typology_registry.py` |
| Python 检索 | `python_backend/app/services/typology_retriever.py` |
| Java 加载 | `com.formacraft.common.typology.StructuralTypologyRegistry` |
| Java 解释器 | `com.formacraft.common.typology.TypologyInterpreterRegistry` |
| Java 构件路由 | `com.formacraft.common.typology.TypologyComponentRouter` |
| Java 解释器实现 | `server.generation.typology.interpreter.*` |
| Java 参数化 Builder | `server.generation.typology.builder.*` |
| 细节工具 | `com.formacraft.common.typology.detail.ChineseTypologyDetailUtil` |
| Culture 卡 | `culture_cards/famen_pagoda.json`, `foguang_temple_hall.json`, `giant_wild_goose_pagoda.json`, `temple_of_heaven.json` |
| 测试 | `python_backend/tests/test_typology_migration.py`, `test_typology_plan_repair.py`, `test_legacy_module_tightening.py` |
| 遥测 | `com.formacraft.common.network.metrics.TypologyRoutingMetrics` |

## 10. LlmPlan 目标形状（typology-first few-shot）

```json
{
  "layout": { "skeleton_type": "VERTICAL_STACK" },
  "proportion_hints": {
    "typology": "dense_eaves_pagoda",
    "floor_count": 13,
    "slenderness": 4.7
  },
  "components": [{
    "component_type": "STRUCTURE",
    "features": ["typology:dense_eaves_pagoda"],
    "params": {
      "typology_id": "dense_eaves_pagoda",
      "reference_landmark": "famen_pagoda",
      "levels": 13,
      "baseWidth": 10,
      "height": 47
    }
  }]
}
```

Phase 6 起：LLM 若仍输出 `MODULE + landmark:<migrated>`，Python sanitize 剥离并 repair；Java 构件路由经 `TypologyComponentRouter` 解析 typology，不再写入 `extra.landmark`。
