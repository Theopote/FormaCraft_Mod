# PlanProgram → PlanSkeleton 转换器

## 🎯 核心职责

将 PlanProgram 的"功能关系"转换为 PlanSkeleton 的"几何语义"

## 📋 转换逻辑

### 1. Zones 转换

**输入：** PlanProgram.zones（功能关系）
**输出：** PlanSkeleton.zones（几何语义）

**添加字段：**
- `boundary`：根据 importance 和 adjacency 决定
  - primary zone → external
  - support zone + 无连接 → internal
  - 其他 → external（保守默认）
- `access`：根据 role 推断
  - role 包含 "main" / "hall" / "public" → public
  - role 包含 "service" / "storage" → private
  - 其他 → semi_private
- `connected_to`：从 adjacency 提取

### 2. Edges 生成

**规则：**
- 两个 external zone 之间的 adjacency → `shared_wall`
- 单个 external zone 的边界 → `external_wall`（v1 简化，在后续 3D 转换时生成）
- 庭院相关的 zone → `courtyard_wall`（在 detectCourtyards 中处理）

### 3. Courtyards 检测

**检测条件：**
- `circulation.connection_style == "ring"`
- 或 `massing.preferred_operations` 包含 "subtract_courtyard" / "wrap_around_courtyard"

**检测逻辑：**
- 查找所有形成环的 zone 组合（至少 3 个）
- 如果找到，创建一个 courtyard

### 4. Axes 生成

**规则：**
- 如果 `circulation.primary_axis` 存在，创建一个主轴线
- 轴线包含 primary_axis 及其连接的 zone

### 5. Outline 生成

**默认值：**
- `source`: "generated"
- `shape`: "rectilinear"（如果 constraints.geometry.avoid_shapes 包含 "perfect_circle"，则使用 "polygon"）

## 🔄 转换流程

```
PlanProgram
  ↓
1. convertZones() → PlanSkeleton.zones
  ↓
2. buildAdjacencyMap() → adjacency 图
  ↓
3. generateEdges() → PlanSkeleton.edges
  ↓
4. detectCourtyards() → PlanSkeleton.courtyards
  ↓
5. generateAxes() → PlanSkeleton.axes
  ↓
6. generateOutline() → PlanSkeleton.outline
  ↓
PlanSkeleton
```

## 📊 转换示例

### 输入：PlanProgram

```json
{
  "zones": [
    { "id": "core", "role": "main_hall", "importance": "primary", "area_ratio": 0.4 },
    { "id": "wing_a", "role": "living", "importance": "secondary", "area_ratio": 0.3 },
    { "id": "wing_b", "role": "living", "importance": "secondary", "area_ratio": 0.3 }
  ],
  "adjacency": [
    ["core", "wing_a"],
    ["core", "wing_b"]
  ],
  "circulation": {
    "primary_axis": "core",
    "connection_style": "direct"
  }
}
```

### 输出：PlanSkeleton

```json
{
  "zones": [
    {
      "id": "core",
      "boundary": "external",
      "access": "public",
      "connected_to": ["wing_a", "wing_b"]
    },
    {
      "id": "wing_a",
      "boundary": "external",
      "access": "semi_private",
      "connected_to": ["core"]
    },
    {
      "id": "wing_b",
      "boundary": "external",
      "access": "semi_private",
      "connected_to": ["core"]
    }
  ],
  "edges": [
    {
      "id": "edge_1",
      "type": "shared_wall",
      "zones": ["core", "wing_a"],
      "exterior": false
    },
    {
      "id": "edge_2",
      "type": "shared_wall",
      "zones": ["core", "wing_b"],
      "exterior": false
    }
  ],
  "axes": [
    {
      "id": "main_axis",
      "role": "primary",
      "zones": ["core", "wing_a", "wing_b"]
    }
  ]
}
```

## 🎯 设计原则

### 保守转换
- 优先生成合理的默认值，而不是复杂推断
- 当信息不足时，选择最安全的选项

### 可扩展
- 为未来更复杂的转换逻辑预留接口
- 转换规则可以逐步细化

### 可调试
- 记录转换过程中的决策
- 在关键决策点记录警告日志

## ⚠️ v1 限制

1. **External walls**：不为单个 zone 生成 external_wall edges（在后续 3D 转换时根据几何生成）
2. **Courtyard 检测**：使用简化的启发式算法（只检测明显的环形结构）
3. **Axis 生成**：只生成主轴线，不生成次轴线
4. **Shape 推断**：outline.shape 的推断逻辑较简单

## 🔮 未来扩展

1. **更智能的 boundary 推断**：考虑 zone 的实际位置关系
2. **更精确的 courtyard 检测**：使用图论算法检测所有可能的环
3. **多轴线支持**：根据 symmetry 约束生成多个轴线
4. **用户轮廓集成**：如果用户提供了轮廓，直接使用（outline.source = user_drawn）
