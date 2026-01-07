# K3.1：LLM JSON 模板系统实现总结

## ✅ 已完全实现

### 1. System Prompt（固定模板）

**位置**：`PromptAssembler.systemRole()`（K3.1 更新）

**核心内容**：
- AI 身份定义：Formacraft Core，Minecraft 建筑规划引擎
- 硬约束：不直接放置方块，只输出结构化 JSON
- 输出格式：完整的 JSON Schema 定义
- 模式支持：build 和 patch 两种模式

**关键规则**：
- 坐标系统：X/Z = 水平面，Y = 垂直高度
- 所有位置相对于 anchor (0,0,0)
- 使用语义组件（TOWER, WALL, ROOF, ENTRANCE, SIGNAGE），不使用方块 ID
- 输出必须是有效的 JSON，无注释，无解释

### 2. Structured JSON Template（结构化 JSON 模板）

**位置**：`PromptAssembler.structuredJsonTemplate()`（K3.1 新增）

**功能**：生成可直接测试大模型的完整 JSON 模板

**包含字段**：
- `mode` - 模式（build/patch）
- `style_profile` - 风格配置 ID
- `anchor` - 锚点坐标
- `global_constraints` - 全局约束（facing, symmetry, terrain_strategy）
- `layout` - 布局信息（skeleton_type, path_based, slots）
- `components` - 组件数组（由 LLM 填充）

**SlotObject 格式**：
```json
{
  "slot_id": "slot_01",
  "anchor": { "x": 6, "y": 0, "z": -4 },
  "facing": "SOUTH",
  "program": "COMMERCIAL",
  "component_preset_id": "PRESET_COMMERCIAL_STREET",
  "component_preset": "Commercial street frontage: lively facade, large shop windows, signage above entrance, frequent street lights and small street furniture."
}
```

**ComponentObject 格式**（由 LLM 填充）：
```json
{
  "component_type": "MASS_MAIN",
  "slot_id": "slot_01",
  "relative_position": { "x": 0, "y": 0, "z": 0 },
  "dimensions": { "width": 8, "depth": 6, "height": 10 },
  "features": [ "SHOP_FRONT", "LARGE_WINDOWS" ]
}
```

### 3. 完整 Prompt 结构

```
1. System Role（固定）
   ↓
2. Spatial Constraints（空间约束块）
   - Anchor / Facing
   - Selection
   - Footprint
   - No-Build Zones
   - Symmetry
   - Semantic Labels
   - Path Block
   - Skeleton Hint
   - Cluster Layout
   - Terrain Strategy
   - Zoning Block（K3）
   - Component Preset Block（K3.1）
   ↓
3. User Intent（玩家原始描述）
   ↓
4. Structured JSON Template（K3.1 新增）
   - 完整的 JSON 结构
   - 所有 slots 信息
   - 所有 preset 信息
   - 等待 LLM 填充 components
```

## 🎯 测试大模型的 5 个关键能力点

### 1️⃣ Slot 感知

**测试点**：
- ✅ 是否理解 slot 是独立建筑单元
- ✅ 是否把商业/住宅/广场区分开
- ✅ 是否每个 slot 生成独立的组件

**期望输出**：
```json
{
  "components": [
    { "component_type": "MASS_MAIN", "slot_id": "slot_01", ... },  // 商业
    { "component_type": "ENTRANCE", "slot_id": "slot_01", ... },
    { "component_type": "SIGNAGE", "slot_id": "slot_01", ... },
    { "component_type": "MASS_MAIN", "slot_id": "slot_03", ... },  // 住宅
    { "component_type": "FENCE_OR_WALL", "slot_id": "slot_03", ... },
    { "component_type": "MASS_MAIN", "slot_id": "slot_04", ... },  // 广场
    { "component_type": "PAVING", "slot_id": "slot_04", ... }
  ]
}
```

### 2️⃣ Program → 组件映射

**测试点**：
- ✅ COMMERCIAL 是否生成：ENTRANCE + FACADE_WINDOWS + SIGNAGE
- ✅ PLAZA 是否生成：PAVING + PLAZA_CORE + BENCHES
- ✅ RESIDENTIAL 是否生成：FENCE_OR_WALL + ENTRANCE + BALCONY

**期望输出**：
- COMMERCIAL slot → MASS_MAIN, ENTRANCE, FACADE_WINDOWS, SIGNAGE, STREET_LIGHTS
- PLAZA slot → PAVING, PLAZA_CORE, GREENERY, BENCHES
- RESIDENTIAL slot → MASS_MAIN, FENCE_OR_WALL, ENTRANCE, FACADE_WINDOWS, BALCONY

### 3️⃣ Facing / Anchor 使用

**测试点**：
- ✅ 是否所有组件都相对 slot.anchor
- ✅ 是否尊重 facing（入口朝向）
- ✅ 是否理解相对坐标系统

**期望输出**：
```json
{
  "slot_id": "slot_01",
  "anchor": { "x": 6, "y": 0, "z": -4 },
  "facing": "SOUTH",
  "components": [
    { "relative_position": { "x": 0, "y": 0, "z": 0 }, ... },  // 相对于 slot anchor
    { "relative_position": { "x": 0, "y": 0, "z": -2 }, ... }  // 入口在 facing 方向
  ]
}
```

### 4️⃣ 组件粒度是否合理

**测试点**：
- ✅ 不直接出现 `stone_bricks` / `concrete`
- ✅ 而是 ENTRANCE / FACADE / SIGNAGE / PAVING
- ✅ 语义级别的组件，不是方块级别

**期望输出**：
```json
{
  "component_type": "ENTRANCE",  // ✅ 语义组件
  "features": [ "CANOPY", "DOORWAY" ]
}
```

**错误输出**：
```json
{
  "component_type": "stone_bricks",  // ❌ 方块级别
  "block_id": "minecraft:stone_bricks"
}
```

### 5️⃣ 是否具备"可回填 Patch"的潜力

**测试点**：
- ✅ component 有清晰 dimensions
- ✅ relative_position 可用于 diff / patch
- ✅ 支持增量修改

**期望输出**：
```json
{
  "component_type": "PLAZA_CORE",
  "slot_id": "slot_04",
  "relative_position": { "x": 0, "y": 0, "z": 0 },
  "dimensions": { "width": 5, "depth": 5, "height": 2 },
  "features": [ "FOUNTAIN", "CENTERED" ]
}
```

## 📋 使用示例

### 场景 1：商业街测试

**输入**：
```
"沿着我画的路径生成一条商业街，夹杂少量住宅，中间有一个小广场"
```

**生成的 JSON 模板**：
```json
{
  "mode": "build",
  "style_profile": "DEFAULT",
  "anchor": { "x": 0, "y": 0, "z": 0 },
  "global_constraints": {
    "facing": "SOUTH",
    "symmetry": "NONE",
    "terrain_strategy": "ADAPTIVE"
  },
  "layout": {
    "skeleton_type": "LINEAR_PATH",
    "path_based": true,
    "slots": [
      {
        "slot_id": "slot_01",
        "anchor": { "x": 6, "y": 0, "z": -4 },
        "facing": "SOUTH",
        "program": "COMMERCIAL",
        "component_preset_id": "PRESET_COMMERCIAL_STREET",
        "component_preset": "Commercial street frontage: lively facade, large shop windows, signage above entrance, frequent street lights and small street furniture."
      },
      {
        "slot_id": "slot_02",
        "anchor": { "x": 18, "y": 0, "z": -4 },
        "facing": "SOUTH",
        "program": "COMMERCIAL",
        "component_preset_id": "PRESET_COMMERCIAL_STREET",
        "component_preset": "Commercial street frontage: lively facade, large shop windows, signage above entrance, frequent street lights and small street furniture."
      },
      {
        "slot_id": "slot_03",
        "anchor": { "x": 30, "y": 0, "z": -4 },
        "facing": "SOUTH",
        "program": "RESIDENTIAL",
        "component_preset_id": "PRESET_RESIDENTIAL_ROW",
        "component_preset": "Residential row: calmer facade rhythm, low boundary wall, porch entrance, smaller windows, optional balconies."
      },
      {
        "slot_id": "slot_04",
        "anchor": { "x": 20, "y": 0, "z": 8 },
        "facing": "SOUTH",
        "program": "PLAZA",
        "component_preset_id": "PRESET_PLAZA_NODE",
        "component_preset": "Plaza node: open paved space, central feature such as fountain or sculpture, benches along edges, greenery around perimeter."
      }
    ]
  },
  "components": []
}
```

**LLM 应该填充**：
```json
{
  "components": [
    // Slot 01 (COMMERCIAL)
    { "component_type": "MASS_MAIN", "slot_id": "slot_01", "relative_position": { "x": 0, "y": 0, "z": 0 }, "dimensions": { "width": 8, "depth": 6, "height": 10 }, "features": [] },
    { "component_type": "ENTRANCE", "slot_id": "slot_01", "relative_position": { "x": 0, "y": 0, "z": -2 }, "dimensions": { "width": 2, "depth": 1, "height": 3 }, "features": ["CANOPY"] },
    { "component_type": "FACADE_WINDOWS", "slot_id": "slot_01", "relative_position": { "x": -3, "y": 1, "z": -2 }, "dimensions": { "width": 3, "depth": 1, "height": 2 }, "features": ["LARGE", "SHOP_WINDOW"] },
    { "component_type": "SIGNAGE", "slot_id": "slot_01", "relative_position": { "x": 0, "y": 3, "z": -2 }, "dimensions": { "width": 4, "depth": 1, "height": 1 }, "features": ["LIGHTBOX"] },
    
    // Slot 03 (RESIDENTIAL)
    { "component_type": "MASS_MAIN", "slot_id": "slot_03", "relative_position": { "x": 0, "y": 0, "z": 0 }, "dimensions": { "width": 6, "depth": 8, "height": 8 }, "features": [] },
    { "component_type": "FENCE_OR_WALL", "slot_id": "slot_03", "relative_position": { "x": 0, "y": 0, "z": -4 }, "dimensions": { "width": 6, "depth": 1, "height": 1 }, "features": ["LOW_WALL"] },
    { "component_type": "ENTRANCE", "slot_id": "slot_03", "relative_position": { "x": 0, "y": 0, "z": -4 }, "dimensions": { "width": 2, "depth": 1, "height": 2 }, "features": ["PORCH"] },
    
    // Slot 04 (PLAZA)
    { "component_type": "PAVING", "slot_id": "slot_04", "relative_position": { "x": -5, "y": 0, "z": -5 }, "dimensions": { "width": 10, "depth": 10, "height": 1 }, "features": [] },
    { "component_type": "PLAZA_CORE", "slot_id": "slot_04", "relative_position": { "x": 0, "y": 0, "z": 0 }, "dimensions": { "width": 3, "depth": 3, "height": 2 }, "features": ["FOUNTAIN"] },
    { "component_type": "BENCHES", "slot_id": "slot_04", "relative_position": { "x": -4, "y": 0, "z": -4 }, "dimensions": { "width": 1, "depth": 1, "height": 1 }, "features": ["FACING_INWARD"] }
  ]
}
```

### 场景 2：Patch 模式测试

**输入**：
```
"把这个广场改大一点，只修改广场内部"
```

**生成的 JSON 模板**：
```json
{
  "mode": "patch",
  "anchor": { "x": 0, "y": 0, "z": 0 },
  "target_slot_id": "slot_04",
  "allowed_area": "OUTLINE_ONLY",
  "components": [
    {
      "component_type": "PLAZA_CORE",
      "slot_id": "slot_04",
      "relative_position": { "x": 0, "y": 0, "z": 0 },
      "dimensions": { "width": 10, "depth": 10, "height": 2 },
      "features": ["EXPAND", "ADD_WATER_FEATURE"]
    }
  ]
}
```

## 🔗 关键设计决策

### 1. 语义组件 vs 方块 ID

- ✅ **使用语义组件**：ENTRANCE, FACADE_WINDOWS, SIGNAGE, PAVING
- ❌ **不使用方块 ID**：stone_bricks, concrete, planks
- **原因**：让 LLM 专注于"设计意图"，而不是"材料选择"

### 2. 相对坐标系统

- ✅ **所有位置相对 anchor**：slot.anchor 是 (0,0,0) 的参考点
- ✅ **组件位置相对 slot.anchor**：component.relative_position 是相对于 slot 的
- **原因**：支持移动、旋转、缩放，便于 patch 操作

### 3. 组件粒度控制

- ✅ **预设决定组件类型**：component_preset 告诉 LLM 应该生成哪些组件
- ✅ **LLM 决定组件细节**：dimensions, features, 具体设计
- **原因**：平衡"算法控制"和"AI 创意"

### 4. Patch 模式支持

- ✅ **增量修改**：只修改指定 slot 或区域
- ✅ **保护机制**：不允许修改 forbidden zones
- **原因**：支持迭代式设计，避免破坏已有结构

## 📝 总结

✅ **K3.1：LLM JSON 模板系统已完全实现**

- System Prompt（固定模板）✅
- Structured JSON Template（结构化模板）✅
- SlotObject 格式 ✅
- ComponentObject 格式 ✅
- Patch 模式支持 ✅

**系统现在可以让大模型成为"建筑师 + 城市规划师"，而 Formacraft 是"施工引擎 + 法规系统"！**

**这是专业 AI 建筑生成系统的核心能力！**

