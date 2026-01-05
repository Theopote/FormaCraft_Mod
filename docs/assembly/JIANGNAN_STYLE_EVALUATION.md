# 江南民居（Jiangnan Dwelling）风格建议评估

本文档评估关于"江南民居"风格建议的参考价值，分析其在 Formacraft 中的适用性和实施建议。

## 建议内容概览

建议包括：
1. **语义化变量映射表（Palette Map）**：粉墙黛瓦的材质映射
2. **配套构件库配置**：马头墙、美人靠、漏窗等构件
3. **LLM 风格描述引导**：比例、空间、色彩逻辑
4. **JSON 风格包定义**：风格预设结构
5. **马头墙生成算法**：详细的算法实现逻辑

---

## Formacraft 现有江南风格支持状态

### ✅ 已实现的功能

1. **生成器**：`JiangnanWaterTownGenerator.java`
   - 生成运河、石堤、小桥、临水房屋
   - 委托给 `HouseGenerator` 生成房屋

2. **风格配置**：`Chinese_Vernacular_Jiangnan_WaterTown`
   - 位置：`style_profiles/style_profile_catalog_v1.json`
   - 包含材质默认值、组件配置

3. **调色板**：
   - `PALETTE_JIANGNAN_WATERTOWN_A`：江南水乡调色板
   - `PALETTE_HUIZHOU_WHITE_BLACK_A`：徽州白墙黑瓦调色板（与江南风格类似）

### ⚠️ 可能缺失的功能

1. **马头墙（Horse-head Wall）**：未在构件库中找到
2. **美人靠（Meirenkao / Railing）**：未在构件库中找到
3. **漏窗（Leaking Window）**：未在构件库中找到
4. **专门的江南民居单体建筑生成器**：当前 `JiangnanWaterTownGenerator` 主要生成水乡场景，单体建筑由 `HouseGenerator` 处理

---

## 参考价值评估

### ✅ 高价值建议（⭐⭐⭐⭐⭐）

#### 1. 语义化变量映射表（Palette Map）

**建议内容**：
- 详细的材质映射表（`$wall_main` → `white_concrete`，`$roof_main` → `deepslate_tiles` 等）
- 明确视觉功能说明

**参考价值**：⭐⭐⭐⭐⭐

**理由**：
- ✅ **与 Formacraft 高度契合**：Formacraft 已有 `PaletteResolver` 系统和调色板目录系统
- ✅ **可直接应用**：可以作为新的调色板（如 `PALETTE_JIANGNAN_WHITE_BLACK_A`）添加到调色板目录
- ✅ **视觉功能说明有价值**：有助于理解和维护材质选择

**实施建议**：
- 在 `palettes/palette_catalog_v1.json` 中添加 `PALETTE_JIANGNAN_WHITE_BLACK_A` 调色板
- 将建议的材质映射转换为调色板的 `parts` 结构
- 可以与其他中式调色板（如 `PALETTE_CHINESE_IMPERIAL_A`）并存，供用户选择

**与现有系统对比**：
- ✅ Formacraft 已有 `PALETTE_JIANGNAN_WATERTOWN_A` 和 `PALETTE_HUIZHOU_WHITE_BLACK_A` 调色板
- ⚠️ 现有调色板可能不完全匹配建议的映射表（需要检查具体材质选择）
- ✅ 建议的映射表可以作为调色板优化的参考，或创建新的变体（如 `PALETTE_JIANGNAN_WHITE_BLACK_B`）

**检查结果**：
- `PALETTE_JIANGNAN_WATERTOWN_A` 已包含 `white_terracotta`、`deepslate_tiles` 等材质
- 建议的映射表更详细（如 `white_concrete` vs `white_terracotta`），可以作为补充或替代选择

---

#### 2. 配套构件库配置

**建议内容**：
- 马头墙（Horse-head Wall / Firewall）
- 美人靠（Meirenkao / Railing）
- 漏窗（Leaking Window）

**参考价值**：⭐⭐⭐⭐⭐

**理由**：
- ✅ **填补空白**：当前中式构件库缺少这些江南特色构件
- ✅ **高度辨识度**：马头墙是江南建筑的标志性特征
- ✅ **可直接实现**：可以添加到 `asset_library/chinese_traditional/` 目录

**实施建议**：

1. **马头墙（Firewall）**
   - 类别：CONNECTOR 或新建 SPECIAL（特殊构件）
   - 实现方式：
     - 方案 A：作为独立的 asset（静态模板，适合固定尺寸）
     - 方案 B：作为动态生成器（适合不同宽度和高度）
   - 建议：先实现方案 A（静态模板），后续可扩展为动态生成器

2. **美人靠（Railing）**
   - 类别：FIXTURE 或 CONNECTOR
   - 实现：使用楼梯和台阶组合，沿柱子放置
   - 文件：`fillers/meirenkao.json` 或 `fixtures/meirenkao.json`

3. **漏窗（Leaking Window）**
   - 类别：FILLER
   - 实现：使用 `dead_brain_coral_block` 或 `oak_fence` 形成网格
   - 文件：`fillers/leaking_window.json`

---

#### 3. LLM 风格描述引导

**建议内容**：
- 比例逻辑：1-2 层
- 空间逻辑：强调"进深"和"天井"
- 色彩逻辑：黑、白、灰、木四色

**参考价值**：⭐⭐⭐⭐

**理由**：
- ✅ **指导性强**：为 LLM 提供了清晰的风格约束
- ✅ **可集成到系统 Prompt**：可以添加到 `ai_planner.py` 的系统提示中
- ✅ **与现有系统兼容**：Formacraft 已有 `StyleProfile` 系统，可以定义约束

**实施建议**：
- 在 `style_profiles/style_profile_catalog_v1.json` 中添加 `Chinese_Vernacular_Jiangnan` 风格配置
- 在 `python_backend/app/services/ai_planner.py` 的系统提示中添加江南风格指导
- 在 `BuildingSpec.extra.layout` 中支持天井（courtyard）定义

**与现有系统对比**：
- Formacraft 已有 `StyleProfileCatalog` 系统
- 已有 `Chinese_Vernacular_Tulou` 等中式风格配置
- 建议的逻辑可以添加到 `constraints` 和 `algorithm` 字段中

---

#### 4. JSON 风格包定义

**建议内容**：
- 风格预设 JSON 结构
- 包含 palette、constraints、mandatory_assets

**参考价值**：⭐⭐⭐⭐

**理由**：
- ✅ **结构合理**：与 Formacraft 的 `StyleProfileCatalog` 结构类似
- ✅ **可以直接采用**：可以作为 `StyleProfile` 定义的参考
- ⚠️ **字段名需调整**：Formacraft 使用不同的字段名（如 `defaults` 而不是 `palette`，`algorithm` 等）

**与现有系统对比**：
- Formacraft 的 `StyleProfileCatalog` 使用：
  - `meta`：显示名称、标签、描述
  - `defaults`：几何、材质、组件、算法默认值
  - `constraints`：约束条件
- 建议的结构需要映射到 Formacraft 的实际结构

---

#### 5. 马头墙生成算法

**建议内容**：
- 详细的几何逻辑（阶梯步进算法）
- 伪代码实现
- 装饰件逻辑

**参考价值**：⭐⭐⭐⭐⭐

**理由**：
- ✅ **算法思路清晰**：阶梯步进逻辑非常实用
- ✅ **可以直接实现**：可以作为 `HorseHeadWallGenerator` 类的基础
- ✅ **可扩展性强**：可以支持不同参数（步数、步高、装饰级别）

**实施建议**：

**方案 A：作为 Asset Library 构件（推荐先实施）**
- 创建静态模板（如 `connectors/horse_head_wall_3step.json`）
- 支持固定尺寸的马头墙（如 3 叠、5 叠）

**方案 B：作为动态生成器（未来扩展）**
- 实现 `HorseHeadWallGenerator` 类
- 在 `facade_rules.side_wall_type: "HORSE_HEAD_WALL"` 时调用
- 支持参数化生成（步数、步高、装饰级别）

**方案 C：作为 MetaAssembly 操作（长期目标）**
- 添加 `HORSE_HEAD_WALL` 操作到 `MetaAssemblyEngine`
- 在 `facade.sideWallType` 中指定

**当前建议**：先实施方案 A（静态 asset），因为：
- 实施简单，可以快速验证效果
- 覆盖常见的马头墙尺寸（3 叠、5 叠）
- 可以与现有的 asset library 系统无缝集成

---

## 与 Formacraft 现有系统的集成方案

### 1. 调色板集成

**位置**：`src/main/resources/assets/formacraft/palettes/palette_catalog_v1.json`

**建议添加**：
```json
"PALETTE_JIANGNAN_WHITE_BLACK_A": {
  "meta": {
    "display_name": "Jiangnan White-Black Palette",
    "tags": ["Chinese", "Jiangnan", "Vernacular", "White-Black"],
    "description": "粉墙黛瓦：白墙、黑瓦、木构架"
  },
  "parts": {
    "FOUNDATION": [
      {"id": "minecraft:stone_bricks", "weight": 70},
      {"id": "minecraft:polished_andesite", "weight": 30}
    ],
    "PRIMARY_STRUCTURE": [
      {"id": "minecraft:white_concrete", "weight": 80},
      {"id": "minecraft:white_terracotta", "weight": 20}
    ],
    "FACADE_TRIM": [
      {"id": "minecraft:calcite", "weight": 70},
      {"id": "minecraft:quartz_block", "weight": 30}
    ],
    "ROOF_TILE": [
      {"id": "minecraft:deepslate_tiles", "weight": 80},
      {"id": "minecraft:blackstone", "weight": 20}
    ],
    "ROOF_SLOPE": [
      {"id": "minecraft:deepslate_tile_stairs", "weight": 80},
      {"id": "minecraft:blackstone_stairs", "weight": 20}
    ],
    "ROOF_EDGE": [
      {"id": "minecraft:deepslate_tile_stairs", "weight": 70},
      {"id": "minecraft:blackstone_stairs", "weight": 30}
    ],
    "PILLAR": [
      {"id": "minecraft:dark_oak_log", "weight": 80},
      {"id": "minecraft:spruce_log", "weight": 20}
    ],
    "FRAME": [
      {"id": "minecraft:dark_oak_planks", "weight": 80},
      {"id": "minecraft:spruce_planks", "weight": 20}
    ],
    "WINDOW": [
      {"id": "minecraft:white_stained_glass", "weight": 70},
      {"id": "minecraft:iron_bars", "weight": 30}
    ],
    "FLOORING": [
      {"id": "minecraft:spruce_planks", "weight": 70},
      {"id": "minecraft:polished_andesite", "weight": 30}
    ]
  }
}
```

---

### 2. StyleProfile 集成

**位置**：`src/main/resources/assets/formacraft/style_profiles/style_profile_catalog_v1.json`

**建议添加**：
```json
"Chinese_Vernacular_Jiangnan": {
  "meta": {
    "display_name": "Jiangnan Vernacular Architecture",
    "family": "Eastern",
    "tags": ["vernacular", "jiangnan", "water_town", "white_black"],
    "description": "江南民居：粉墙黛瓦、马头墙、天井、临水"
  },
  "defaults": {
    "geometry": {
      "symmetry": "asymmetric",
      "roof": {"type": "gable", "overhang": 1.5},
      "maxLayers": 2
    },
    "materials": {
      "wall": ["minecraft:white_concrete"],
      "roof": ["minecraft:deepslate_tiles"],
      "frame": ["minecraft:dark_oak_planks"],
      "floor": ["minecraft:spruce_planks"]
    },
    "components": {
      "courtyard": true,
      "windows": "lattice",
      "facade_profile": "horse_head_wall",
      "palette_id": "PALETTE_JIANGNAN_WHITE_BLACK_A"
    },
    "algorithm": {
      "generator": "jiangnan_water_town",
      "quality": "high"
    }
  },
  "constraints": {
    "allowed_archetypes": ["dwelling", "courtyard_house"],
    "terrain_policy": "water_adaptive",
    "symmetry_preference": "asymmetric",
    "color_palette": ["white", "black", "gray", "wood"]
  }
}
```

---

### 3. Asset Library 集成

**位置**：`src/main/resources/assets/formacraft/asset_library/chinese_traditional/`

**建议添加的构件**：

1. **马头墙（Horse-head Wall）**
   - 文件：`connectors/horse_head_wall_3step.json`
   - ID：`chinese_horse_head_wall_3step`
   - 类别：CONNECTOR
   - 尺寸：可变（建议先实现固定尺寸：宽度 9-15，高度根据步数）

2. **美人靠（Railing）**
   - 文件：`fixtures/meirenkao.json`
   - ID：`chinese_meirenkao`
   - 类别：FIXTURE
   - 尺寸：1×1×N（沿柱子延伸）

3. **漏窗（Leaking Window）**
   - 文件：`fillers/leaking_window.json`
   - ID：`chinese_leaking_window`
   - 类别：FILLER
   - 尺寸：3×3×1

---

### 4. 生成器集成

**现有状态**：
- ✅ 已有 `JiangnanWaterTownGenerator.java`
- 需要检查是否已实现马头墙等特色构件

**建议**：
- 如果 `JiangnanWaterTownGenerator` 未实现马头墙，可以：
  1. 在生成器中直接实现马头墙逻辑
  2. 或通过 asset library 调用马头墙构件

---

## 实施优先级建议

### Phase 1：快速验证（1-2 周）

1. **添加江南调色板**
   - 在 `palette_catalog_v1.json` 中添加 `PALETTE_JIANGNAN_WHITE_BLACK_A`
   - 实施难度：低
   - 价值：高（立即改善视觉效果）

2. **添加漏窗和美人靠构件**
   - 实施难度：低
   - 价值：中高（增加特色细节）

3. **更新 StyleProfile**
   - 添加 `Chinese_Vernacular_Jiangnan` 配置
   - 实施难度：低
   - 价值：高（完善风格系统）

---

### Phase 2：核心特色（2-4 周）

1. **实现马头墙构件（静态模板）**
   - 创建 3 叠、5 叠马头墙模板
   - 实施难度：中
   - 价值：高（标志性特征）

2. **集成到生成器**
   - 在 `JiangnanWaterTownGenerator` 中调用马头墙构件
   - 实施难度：中
   - 价值：高

---

### Phase 3：算法优化（长期）

1. **动态马头墙生成器**
   - 实现参数化的马头墙生成算法
   - 实施难度：高
   - 价值：高（灵活性）

2. **天井（Courtyard）支持**
   - 在 `extra.layout` 中支持天井定义
   - 实施难度：中
   - 价值：中高

---

## 总结

### 总体评分：⭐⭐⭐⭐⭐（非常有参考价值）

### 核心价值点

1. ✅ **材质映射表**：可以直接转换为调色板，实施简单
2. ✅ **构件建议**：马头墙、美人靠、漏窗都是高价值的特色构件
3. ✅ **算法思路**：马头墙生成算法思路清晰，可实施性强
4. ✅ **风格指导**：LLM 引导逻辑有助于提升生成质量

### 与 Formacraft 的契合度

- ✅ **高度契合**：所有建议都可以在现有系统架构上实施
- ✅ **无需重构**：可以逐步添加，不影响现有功能
- ✅ **互补性强**：填补了江南风格在调色板和构件库方面的空白

### 建议实施顺序

1. **立即实施**：调色板（最简单、效果最明显）
2. **短期实施**：漏窗、美人靠构件（实施简单）
3. **中期实施**：马头墙静态模板（核心特色）
4. **长期优化**：动态马头墙生成器（算法优化）

这些建议对于完善 Formacraft 的江南风格支持具有**很高的参考价值**，建议优先实施调色板和核心构件。

