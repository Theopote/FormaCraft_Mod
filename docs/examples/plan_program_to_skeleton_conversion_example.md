# PlanProgram → PlanSkeleton 转换示例

## 完整转换流程示例

### 输入：PlanProgram（AI 生成的功能关系）

```json
{
  "schema": "formacraft.plan_program.v1",
  "intent": {
    "building_type": "residential",
    "style_hint": "medieval",
    "scale": "medium"
  },
  "zones": [
    {
      "id": "core",
      "role": "main_hall",
      "importance": "primary",
      "area_ratio": 0.4
    },
    {
      "id": "wing_a",
      "role": "living",
      "importance": "secondary",
      "area_ratio": 0.3
    },
    {
      "id": "wing_b",
      "role": "living",
      "importance": "secondary",
      "area_ratio": 0.3
    }
  ],
  "adjacency": [
    ["core", "wing_a"],
    ["core", "wing_b"]
  ],
  "massing": {
    "strategy": "decompose",
    "rules": {
      "max_zone_area": 100,
      "preferred_operations": ["add_wings"]
    }
  },
  "circulation": {
    "primary_axis": "core",
    "connection_style": "direct"
  },
  "constraints": {
    "terrain": {
      "respect_slope": true,
      "avoid": ["water"]
    },
    "geometry": {
      "avoid_shapes": ["perfect_circle"],
      "prefer_symmetry": "axial"
    }
  }
}
```

### 输出：PlanSkeleton（系统生成的几何语义）

```json
{
  "schema": "formacraft.plan_skeleton.v1",
  "outline": {
    "source": "generated",
    "shape": "polygon"
  },
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
  "courtyards": [],
  "axes": [
    {
      "id": "main_axis",
      "role": "primary",
      "zones": ["core", "wing_a", "wing_b"]
    }
  ]
}
```

## 转换决策说明

### 1. Zones 转换

| PlanProgram Zone | → | PlanSkeleton Zone | 决策依据 |
| ---------------- | - | ----------------- | -------- |
| core (primary, main_hall) | → | boundary: external, access: public | primary zone 通常有外墙；main_hall 是公共空间 |
| wing_a (secondary, living) | → | boundary: external, access: semi_private | secondary zone 有外墙；living 是半私密空间 |
| wing_b (secondary, living) | → | boundary: external, access: semi_private | 同上 |

### 2. Edges 生成

| Adjacency | → | Edge | 决策依据 |
| --------- | - | ---- | -------- |
| [core, wing_a] | → | shared_wall | 两个 external zone 之间的连接 |
| [core, wing_b] | → | shared_wall | 同上 |

### 3. Axes 生成

| Circulation | → | Axis | 决策依据 |
| ----------- | - | ---- | -------- |
| primary_axis: "core" | → | main_axis: [core, wing_a, wing_b] | 包含 primary_axis 及其连接的 zone |

### 4. Courtyards 检测

- `connection_style: "direct"` → 不是环形，不生成 courtyard
- 如果改为 `"ring"`，且 zones 形成环，则会生成 courtyard

## 回字形建筑示例

### 输入：PlanProgram（回字形）

```json
{
  "zones": [
    { "id": "main_hall", "role": "worship", "importance": "primary" },
    { "id": "north_wing", "role": "ancillary", "importance": "secondary" },
    { "id": "south_wing", "role": "ancillary", "importance": "secondary" },
    { "id": "east_wing", "role": "ancillary", "importance": "secondary" },
    { "id": "west_wing", "role": "ancillary", "importance": "secondary" }
  ],
  "adjacency": [
    ["main_hall", "north_wing"],
    ["main_hall", "south_wing"],
    ["main_hall", "east_wing"],
    ["main_hall", "west_wing"],
    ["north_wing", "east_wing"],
    ["north_wing", "west_wing"],
    ["south_wing", "east_wing"],
    ["south_wing", "west_wing"]
  ],
  "circulation": {
    "primary_axis": "main_hall",
    "connection_style": "ring"
  },
  "massing": {
    "strategy": "decompose",
    "rules": {
      "preferred_operations": ["wrap_around_courtyard"]
    }
  }
}
```

### 输出：PlanSkeleton（检测到庭院）

```json
{
  "zones": [...],
  "edges": [
    { "type": "shared_wall", "zones": ["main_hall", "north_wing"] },
    { "type": "shared_wall", "zones": ["main_hall", "south_wing"] },
    { "type": "shared_wall", "zones": ["main_hall", "east_wing"] },
    { "type": "shared_wall", "zones": ["main_hall", "west_wing"] },
    { "type": "shared_wall", "zones": ["north_wing", "east_wing"] },
    { "type": "shared_wall", "zones": ["north_wing", "west_wing"] },
    { "type": "shared_wall", "zones": ["south_wing", "east_wing"] },
    { "type": "shared_wall", "zones": ["south_wing", "west_wing"] }
  ],
  "courtyards": [
    {
      "id": "court_1",
      "adjacent_zones": ["main_hall", "north_wing", "south_wing", "east_wing", "west_wing"]
    }
  ],
  "axes": [
    {
      "id": "main_axis",
      "role": "primary",
      "zones": ["main_hall", "north_wing", "south_wing", "east_wing", "west_wing"]
    }
  ]
}
```

## 使用代码示例

```java
// 1. 解析 PlanProgram
PlanProgram program = PlanProgramParser.parseAndValidate(programJson);

// 2. 转换为 PlanSkeleton
PlanSkeleton skeleton = PlanProgramToPlanSkeletonConverter.convert(program);

// 3. 验证 PlanSkeleton
PlanSkeleton validated = PlanSkeletonParser.parseAndValidate(
    JsonUtil.get().toJson(skeleton)
);

// 4. 后续：PlanSkeleton → Skeleton (3D) → SocketProvider → Component
```
