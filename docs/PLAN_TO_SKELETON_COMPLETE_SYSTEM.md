# PlanProgram → Skeleton（3D）完整转换系统

## 🎯 系统总览

这个系统实现了从 AI 的"建筑思考"（PlanProgram）到"可建造结构"（Skeleton 3D）的完整转换链。

**核心原则：** PlanSkeleton → Skeleton 的过程不是"拉高平面"，而是把 2D 的「空间关系」转译成 3D 的「可生长骨架」。

## 📋 三层架构模型

```
Layer 1: PlanProgram
  ↓ (功能关系 → 几何语义)
Layer 2: PlanSkeleton
  ↓ (2D语义 → 3D结构)
Layer 3: StructuralSkeleton
  ↓ (结构骨架 → 可执行计划)
Layer 4: ExecutableSkeletonPlan
  ↓ (使用现有 Generator)
Layer 5: Skeleton (3D) + Socket
```

## 🧩 各层职责

### Layer 1: PlanProgram（AI 的建筑思考）
**职责：** 描述"我要哪些空间？它们的关系是什么？"
- `zones` - 功能块定义
- `adjacency` - 拓扑关系
- `massing` - 体量拆解策略
- `circulation` - 流线规划
- `constraints` - 约束条件

**特点：** LLM 友好，低坐标，低数学，语义优先

### Layer 2: PlanSkeleton（2D 几何语义）
**职责：** 描述"这些空间在平面中处于什么位置关系？"
- `zones` - 几何语义化的功能块（boundary, access, connected_to）
- `edges` - 墙的定义（external_wall, shared_wall, courtyard_wall）
- `courtyards` - 庭院空洞
- `axes` - 轴线语义

**特点：** 人类 & AI 都可以理解和修改

### Layer 3: StructuralSkeleton（3D 结构骨架）
**职责：** 描述"墙在哪里？墙是内还是外？墙有多高？"
- `floor_plate` - 地面板
- `wall_segments` - 墙段（EXTERNAL/INTERNAL/COURTYARD）
- `courtyard_voids` - 庭院空洞
- `roof_plate` - 屋顶板
- `alignment_constraints` - 对齐约束

**特点：** 不关心风格、不关心构件，只关心结构

### Layer 4: ExecutableSkeletonPlan（可执行骨架）
**职责：** 与现有 Formacraft Skeleton 系统兼容
- 使用 `SkeletonType.PERIMETER_LOOP` 等现有类型
- 自动触发 `SocketProvider` 的能力

**特点：** 完全兼容现有 Generator 系统

### Layer 5: Skeleton (3D) + Socket（最终输出）
**职责：** 告诉后续系统"哪里有墙、哪里是边、哪里是内外、哪里值得长东西"
- `WALL_SURFACE` - 墙表面
- `WALL_OPENING` - 门窗开口
- `EDGE_OUTER` - 边缘（仅外墙）
- `FLOOR_SURFACE` - 地面（未来）
- `ROOF_SLOPE` - 屋顶（未来）

## 🔄 转换器

### 1. PlanProgramToPlanSkeletonConverter
**位置：** `src/main/java/com/formacraft/common/llm/converter/PlanProgramToPlanSkeletonConverter.java`

**转换规则：**
- zones → zones（添加 boundary, access, connected_to）
- adjacency → edges
- circulation → axes
- massing → courtyards（检测）

### 2. PlanSkeletonToStructuralSkeletonConverter
**位置：** `src/main/java/com/formacraft/common/llm/converter/PlanSkeletonToStructuralSkeletonConverter.java`

**转换规则：**
- outline → FLOOR_PLATE
- edges → WALL_SEGMENT
- courtyards → COURTYARD_VOID
- axes → AlignmentConstraint
- 推断 → ROOF_PLATE

### 3. StructuralSkeletonToExecutablePlanConverter
**位置：** `src/main/java/com/formacraft/common/llm/converter/StructuralSkeletonToExecutablePlanConverter.java`

**转换规则：**
- WallSegment → ExecutableSkeletonPlan (PERIMETER_LOOP)
- FloorPlate → （v1 暂不生成）
- RoofPlate → （v1 暂不生成）

## 📊 关键映射表

| PlanSkeleton 元素 | StructuralSkeleton | ExecutableSkeletonPlan | Socket 输出 |
| ---------------- | ------------------ | ---------------------- | --------- |
| external_wall | WallSegment (EXTERNAL) | PERIMETER_LOOP | WALL_SURFACE, EDGE_OUTER, WALL_OPENING |
| shared_wall | WallSegment (INTERNAL) | PERIMETER_LOOP | WALL_SURFACE, WALL_OPENING |
| courtyard_wall | WallSegment (COURTYARD) | PERIMETER_LOOP | WALL_SURFACE (inward) |
| outline | FloorPlate | （未来） | FLOOR_SURFACE |
| courtyard | CourtyardVoid | （影响 wall 生成） | - |
| axes | AlignmentConstraint | （影响对齐） | - |

## 🎯 使用流程

```java
// 完整转换链
PlanProgram program = PlanProgramParser.parseAndValidate(json);

PlanSkeleton planSkeleton = PlanProgramToPlanSkeletonConverter.convert(program);
StructuralSkeleton structural = PlanSkeletonToStructuralSkeletonConverter.convert(planSkeleton);
List<ExecutableSkeletonPlan> plans = StructuralSkeletonToExecutablePlanConverter.convert(structural);

// 生成 BlockPatch
for (ExecutableSkeletonPlan plan : plans) {
    ISkeletonGenerator generator = SkeletonGeneratorRegistry.getGenerator(plan.type);
    List<BlockPatch> patches = generator.generate(ctx, plan);
    // ...
}

// 生成 Socket
List<Socket> sockets = SkeletonSocketGenerator.generateSockets(plans, world, origin);
```

## ✅ 系统特点

1. **没有引入新的"硬编码形状"**：所有复杂性来自"语义拆解"
2. **完全兼容现有系统**：SocketProvider、SocketMatcher、AutoAssembler
3. **用户可以随时介入**：改 outline、改 zone、改 axis
4. **可扩展**：为未来更复杂的几何推断预留接口

## ⚠️ v1 限制

1. **几何推断简化**：很多几何数据使用默认值
2. **FloorPlate/RoofPlate**：暂不生成独立的 ExecutableSkeletonPlan
3. **复杂几何**：polygon、曲线等需要在后续版本中完善

## 🔮 未来扩展

1. **精确几何计算**：从 outline/zones 的实际数据生成 polygon
2. **多层级结构**：支持多层建筑的 floor/roof
3. **复杂屋顶**：分区、错动、坡屋顶
4. **VERTICAL_CORE**：垂直核心（楼梯、电梯）

## 📚 相关文档

- `docs/PLAN_PROGRAM_SCHEMA_V1.md` - PlanProgram schema
- `docs/PLAN_SKELETON_SCHEMA_V1.md` - PlanSkeleton schema
- `docs/PLAN_PROGRAM_TO_SKELETON_CONVERTER.md` - PlanProgram → PlanSkeleton
- `docs/PLAN_SKELETON_TO_STRUCTURAL_CONVERTER.md` - PlanSkeleton → StructuralSkeleton
- `docs/examples/complete_plan_to_skeleton_flow.md` - 完整流程示例
