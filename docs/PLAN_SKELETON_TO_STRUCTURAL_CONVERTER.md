# PlanSkeleton → StructuralSkeleton → ExecutableSkeletonPlan 转换系统

## 🎯 核心定义

**PlanSkeleton → Skeleton 的过程不是"拉高平面"**，
而是：把 2D 的「空间关系」转译成 3D 的「可生长骨架」。

Skeleton 的职责只有一件事：
**告诉后续系统：哪里有墙、哪里是边、哪里是内外、哪里值得长东西**

## 📋 三层模型

```
PlanSkeleton (2D 语义)
   ↓
StructuralSkeleton (3D 结构骨架)
   ↓
ExecutableSkeletonPlan (可装配骨架，Formacraft Skeleton)
```

## 🔄 转换规则总览

### PlanSkeleton → StructuralSkeleton

| PlanSkeleton 元素           | 生成的 StructuralSkeleton |
| ------------------------- | ---------------------- |
| outline                   | FLOOR_PLATE            |
| zone.boundary = external  | 外围 WALL_SEGMENT（推断）   |
| edge.type = external_wall | 外墙 WALL_SEGMENT (EXTERNAL) |
| edge.type = shared_wall   | 内墙 WALL_SEGMENT (INTERNAL) |
| edge.type = courtyard_wall | 庭院墙 WALL_SEGMENT (COURTYARD) |
| courtyard                 | COURTYARD_VOID         |
| axes                      | AlignmentConstraint（对齐约束） |

### StructuralSkeleton → ExecutableSkeletonPlan

| StructuralSkeleton 元素 | 生成的 ExecutableSkeletonPlan | Socket 影响 |
| ---------------------- | --------------------------- | --------- |
| WallSegment (EXTERNAL) | WALL Skeleton (PERIMETER_LOOP) | WALL_SURFACE, EDGE_OUTER, WALL_OPENING |
| WallSegment (INTERNAL) | WALL Skeleton (PERIMETER_LOOP) | WALL_SURFACE, WALL_OPENING |
| WallSegment (COURTYARD) | WALL Skeleton (PERIMETER_LOOP) | WALL_SURFACE（内窗、回廊） |
| FloorPlate             | （v1 暂不生成独立 Skeleton） | FLOOR_SURFACE（未来：柱、家具） |
| RoofPlate              | （v1 暂不生成独立 Skeleton） | ROOF_SLOPE, ROOF_RIDGE（未来：天窗、脊饰） |

## 🔍 详细映射规则

### 规则 1：outline → FLOOR_PLATE

**PlanSkeleton.outline** → **StructuralSkeleton.floorPlate**

**规则细节：**
- floor plate 是**唯一必定存在的元素**
- 仅定义：XZ 范围、基准高度

**⚠️ 注意：** floor plate ≠ 房间，它只是承载墙和体量的"地基"

**v1 实现：**
- 使用默认矩形（10x10）
- 未来：从 outline 的实际几何数据生成 polygon

### 规则 2：edges → WALL_SEGMENT（核心）

**映射：**
- `external_wall` → `EXTERNAL`
- `shared_wall` → `INTERNAL`
- `courtyard_wall` → `COURTYARD`

**关键点（与 Socket 系统强关联）：**

**EXTERNAL WALL：**
- 必须生成：WALL_SURFACE (exterior)、EDGE_OUTER (top)
- SocketProvider 自动识别并生成 sockets

**INTERNAL WALL：**
- 生成：WALL_SURFACE (interior only)
- 不生成 EDGE_OUTER（v1）

**COURTYARD WALL：**
- 生成：WALL_SURFACE (inward-facing)
- 允许：窗、装饰
- 禁止：阳台、外挑构件（v1）

**👉 这一步直接决定后续"哪里能长窗、哪里不能"**

### 规则 3：courtyard → COURTYARD_VOID

**映射结果：**
- 不生成 FLOOR_PLATE
- 不生成 ROOF
- 但：周围生成 courtyard_wall（inward-facing）

**👉 这是 回字形 / 中式院落 / 修道院 的根。**

### 规则 4：axes → AlignmentConstraint

**axis 不直接生成结构，而是：**
- 调整墙的直线性
- zone 的偏移容忍度
- 对称策略

**v1 简化规则：**
- `primary axis`：尽量直线、尽量正交
- `secondary axis`：可偏移

### 规则 5：roof → ROOF_PLATE

**v1 简化：**
- 统一屋顶（从 floor plate 推断，向内偏移 1 格）
- 未来：分区屋顶、高低错动、坡屋顶

## 🔄 完整转换流程

```
PlanSkeleton
  ↓
PlanSkeletonToStructuralSkeletonConverter.convert()
  ↓
StructuralSkeleton
  ↓
StructuralSkeletonToExecutablePlanConverter.convert()
  ↓
List<ExecutableSkeletonPlan>
  ↓
SkeletonGeneratorRegistry.getGenerator()
  ↓
Generator.generate() → List<BlockPatch>
  ↓
SkeletonSocketGenerator.generateSockets()
  ↓
SocketProvider → Socket 列表
```

## ✅ Socket 自动生成

转换后的 ExecutableSkeletonPlan 会自动触发 SocketProvider：

### WallSegment → Socket

| WallSegment.Kind | Socket 类型 | 说明 |
| ---------------- | --------- | ---- |
| EXTERNAL         | WALL_SURFACE (exterior) | 外墙表面 |
|                  | EDGE_OUTER | 墙顶边缘 |
|                  | WALL_OPENING | 门窗开口 |
| INTERNAL         | WALL_SURFACE (interior) | 内墙表面 |
|                  | WALL_OPENING | 内部门窗 |
| COURTYARD        | WALL_SURFACE (inward) | 内向立面 |
|                  | WALL_OPENING | 内窗 |

### FloorPlate → Socket（未来）

- FLOOR_SURFACE：用于柱、家具、地面装饰

### RoofPlate → Socket（未来）

- ROOF_SLOPE：坡屋顶
- ROOF_RIDGE：屋脊

## 🎯 为什么这套规则非常 Formacraft

1. **没有引入新的"硬编码形状"**：所有复杂性来自"语义拆解"
2. **完全兼容现有的系统**：SocketProvider、SocketMatcher、AutoAssembler
3. **用户可以随时介入**：改 outline、改 zone、改 axis

## ⚠️ v1 限制

1. **几何推断简化**：很多几何数据使用默认值（矩形、5格长度等）
2. **FloorPlate/RoofPlate**：暂不生成独立的 ExecutableSkeletonPlan
3. **复杂几何**：polygon、曲线等需要在后续版本中完善

## 🔮 未来扩展

1. **精确几何计算**：从 outline/zones 的实际数据生成 polygon
2. **多层级结构**：支持多层建筑的 floor/roof
3. **复杂屋顶**：分区、错动、坡屋顶
4. **VERTICAL_CORE**：垂直核心（楼梯、电梯）
