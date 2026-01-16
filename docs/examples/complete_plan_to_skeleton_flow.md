# 完整的 PlanProgram → Skeleton 转换流程示例

## 🔄 完整流水线

```
用户输入 / LLM 生成
  ↓
PlanProgram.json        ← AI 的建筑思考（功能关系）
  ↓
PlanProgramToPlanSkeletonConverter.convert()
  ↓
PlanSkeleton.json       ← 2D 几何语义
  ↓
PlanSkeletonToStructuralSkeletonConverter.convert()
  ↓
StructuralSkeleton      ← 3D 结构骨架
  ↓
StructuralSkeletonToExecutablePlanConverter.convert()
  ↓
List<ExecutableSkeletonPlan>  ← 可执行的骨架计划
  ↓
SkeletonGenerator.generate()
  ↓
List<BlockPatch>        ← 最终方块修改
  ↓
SkeletonSocketGenerator.generateSockets()
  ↓
SocketProvider → Socket 列表
  ↓
AutoAssembler → Component 装配
```

## 📊 示例：简单住宅（主体 + 两翼）

### 输入：PlanProgram

```json
{
  "schema": "formacraft.plan_program.v1",
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

### 第一步转换：PlanSkeleton

```json
{
  "schema": "formacraft.plan_skeleton.v1",
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
      "zones": ["wing_a", "core", "wing_b"]
    }
  ]
}
```

### 第二步转换：StructuralSkeleton

```json
{
  "schema": "formacraft.structural_skeleton.v1",
  "floor_plate": {
    "polygon_xz": [ /* 10x10 矩形 */ ],
    "base_y": 0
  },
  "wall_segments": [
    {
      "id": "wall_1",
      "kind": "INTERNAL",
      "base_line": [ /* core 和 wing_a 之间的墙基线 */ ],
      "height": 5,
      "normal": null,
      "zone_ids": ["core", "wing_a"]
    },
    {
      "id": "wall_2",
      "kind": "INTERNAL",
      "base_line": [ /* core 和 wing_b 之间的墙基线 */ ],
      "height": 5,
      "normal": null,
      "zone_ids": ["core", "wing_b"]
    }
  ],
  "courtyard_voids": [],
  "alignment_constraints": [
    {
      "axis_id": "main_axis",
      "role": "primary",
      "zone_ids": ["wing_a", "core", "wing_b"],
      "preferences": {
        "straightness": 1.0,
        "orthogonal": true,
        "symmetry": false
      }
    }
  ]
}
```

### 第三步转换：ExecutableSkeletonPlan

```java
List<ExecutableSkeletonPlan> plans = [
  {
    type: PERIMETER_LOOP,
    length: 5,
    height: 5,
    width: 1,
    params: {
      "points": [ /* wall_1 的 baseLine */ ],
      "wall_kind": "INTERNAL",
      "wall_zones": ["core", "wing_a"]
    }
  },
  {
    type: PERIMETER_LOOP,
    length: 5,
    height: 5,
    width: 1,
    params: {
      "points": [ /* wall_2 的 baseLine */ ],
      "wall_kind": "INTERNAL",
      "wall_zones": ["core", "wing_b"]
    }
  }
]
```

### 最终：Socket 自动生成

**WallSegment (INTERNAL) → Socket：**
- WALL_SURFACE (interior) - 内墙表面
- WALL_OPENING - 内部门窗开口

## 💻 代码使用示例

```java
// 1. 解析 PlanProgram
PlanProgram program = PlanProgramParser.parseAndValidate(programJson);

// 2. PlanProgram → PlanSkeleton
PlanSkeleton planSkeleton = PlanProgramToPlanSkeletonConverter.convert(program);

// 3. PlanSkeleton → StructuralSkeleton
StructuralSkeleton structural = PlanSkeletonToStructuralSkeletonConverter.convert(planSkeleton);

// 4. StructuralSkeleton → ExecutableSkeletonPlan
List<ExecutableSkeletonPlan> executablePlans = 
    StructuralSkeletonToExecutablePlanConverter.convert(structural);

// 5. 生成 BlockPatch（使用现有的 Generator 系统）
for (ExecutableSkeletonPlan plan : executablePlans) {
    ISkeletonGenerator generator = SkeletonGeneratorRegistry.getGenerator(plan.type);
    List<BlockPatch> patches = generator.generate(ctx, plan);
    // ...
}

// 6. 生成 Socket（使用现有的 SocketProvider 系统）
List<Socket> sockets = SkeletonSocketGenerator.generateSockets(executablePlans, world, origin);
```

## 🎯 关键映射表（快速参考）

| 输入层 | 输出层 | 关键转换 |
| ----- | ----- | -------- |
| PlanProgram.zones + adjacency | PlanSkeleton.zones + edges | 功能关系 → 几何语义 |
| PlanSkeleton.edges | StructuralSkeleton.wallSegments | 2D 边 → 3D 墙段 |
| StructuralSkeleton.wallSegments | ExecutableSkeletonPlan | 结构元素 → 可执行计划 |
| ExecutableSkeletonPlan | Socket | 自动触发 SocketProvider |

## ✅ 验证要点

1. **PlanProgram → PlanSkeleton**：检查 adjacency 是否正确转换为 edges
2. **PlanSkeleton → StructuralSkeleton**：检查所有 edges 是否都有对应的 wallSegments
3. **StructuralSkeleton → ExecutableSkeletonPlan**：检查 wall_kind 是否正确传递
4. **Socket 生成**：验证 WALL_SURFACE、WALL_OPENING 是否正确生成
