# 完整的 Plan → 3D 几何编译管线

## 🎯 核心成就

这已经是一条真正的"AI 建筑编译管线"，从 LLM 的建筑思考到可执行的 3D 几何。

## 🔄 完整链路

```
LLM 输出
  ↓
PlanProgram.json          ← AI 的建筑思考（功能关系）
  ↓
PlanProgramToPlanSkeletonConverter
  ↓
PlanSkeleton.json         ← 2D 几何语义
  ↓
PlanToSkeletonCompiler
  ↓ (内部：PlanSkeleton → StructuralSkeleton)
StructuralSkeleton        ← 3D 结构骨架（使用几何类型）
  ↓ (内部：WallExtrusion)
ExtrudedSolid            ← 3D 几何体（顶点 + 面）
  ↓ (存储在 ExecutableSkeletonPlan 中)
ExecutableSkeletonPlan   ← 可执行的骨架计划
  ↓
SkeletonGenerator        ← 生成 BlockPatch
  ↓
SocketProvider           ← 生成 Socket
  ↓
AutoAssembler            ← 装配 Component
```

## 📦 关键组件

### 1. 数据层（DTOs）

- **PlanProgram** - AI 的建筑思考（功能关系）
- **PlanSkeleton** - 2D 几何语义
- **StructuralSkeleton** - 3D 结构骨架（几何类型）
- **ExtrudedSolid** - 3D 几何体（顶点 + 面）

### 2. 转换器层

- **PlanProgramToPlanSkeletonConverter** - 功能关系 → 几何语义
- **PlanSkeletonToStructuralSkeletonConverter** - 几何语义 → 结构骨架
- **StructuralSkeletonToExecutablePlanConverter** - 结构骨架 → 可执行计划（自动执行 extrusion）
- **WallExtrusion** - WallSegment → ExtrudedSolid

### 3. 编译器层

- **PlanToSkeletonCompiler** - 统一编译接口
- **PlanToSkeletonCompilerV1** - 管线式实现
- **CompiledSkeleton** - 编译结果（skeletons + graph）

### 4. 集成层

- **PlanToSkeletonIntegrationHelper** - 便捷整合入口

## 💻 使用方式

### 方式 1：完整编译流程

```java
// 1. 输入：PlanSkeleton（假设已经从 PlanProgram 转换而来）
PlanSkeleton planSkeleton = ...;

// 2. 编译（一步到位）
CompiledSkeleton compiled = PlanToSkeletonIntegrationHelper.compileFromPlanSkeleton(planSkeleton);

// 3. 使用结果
List<ExecutableSkeletonPlan> skeletons = compiled.getSkeletons();
SkeletonGraph graph = compiled.getGraph();

// 4. 提取 ExtrudedSolid（用于可视化、调试）
List<ExtrudedSolid> solids = PlanToSkeletonIntegrationHelper.extractExtrudedSolids(compiled);

// 5. 获取统计信息
CompilationStats stats = PlanToSkeletonIntegrationHelper.getStats(compiled);
System.out.println("Skeletons: " + stats.skeletonCount());
System.out.println("Extruded Solids: " + stats.extrudedSolidCount());
```

### 方式 2：分步执行（更多控制）

```java
// 1. 创建编译器
PlanToSkeletonCompiler compiler = new PlanToSkeletonCompilerV1();

// 2. 创建上下文
PlanCompileContext context = PlanCompileContext.createWithTerrain(world, origin);

// 3. 编译
CompiledSkeleton compiled = compiler.compile(planSkeleton, context);

// 4. 使用结果（同上）
```

### 方式 3：只执行 Extrusion

```java
// 如果只需要 extrusion 结果
StructuralSkeleton.WallSegment wall = ...;
List<ExtrudedSolid> solids = WallExtrusion.extrude(wall);
```

## 📊 数据流示例

### 输入：PlanSkeleton

```json
{
  "zones": [
    { "id": "core", "boundary": "external", "access": "public" }
  ],
  "edges": [
    {
      "id": "edge_1",
      "type": "external_wall",
      "zones": ["core"],
      "exterior": true
    }
  ]
}
```

### 中间：StructuralSkeleton

```java
WallSegment {
    id: "wall_1",
    type: EXTERNAL,
    baseline: Polyline2D([(0,0), (10,0)]),
    thickness: 1.0,
    heightProfile: HeightProfile(baseY=0, topY=5),
    normal: Vector2(0, 1),
    zones: ["core"]
}
```

### 最终：ExtrudedSolid

```java
ExtrudedSolid {
    vertices: [
        (0, 0, 0.5),   // A0
        (10, 0, 0.5),  // B0
        (10, 0, -0.5), // C0
        (0, 0, -0.5),  // D0
        (0, 5, 0.5),   // A1
        (10, 5, 0.5),  // B1
        (10, 5, -0.5), // C1
        (0, 5, -0.5)   // D1
    ],
    faces: [
        // 外立面、内立面、顶面、底面、端面1、端面2
    ]
}
```

## ✅ 系统能力

完成这一步后，你的系统具备：

- ✔ **AI 输出建筑逻辑**，不是几何
- ✔ **自动几何生成**，从逻辑到 3D 几何
- ✔ **可调试/可视化**，ExtrudedSolid 可直接渲染
- ✔ **完全兼容现有系统**，ExecutableSkeletonPlan 与 Generator/Socket 无缝对接
- ✔ **可扩展**，几何类型支持未来复杂场景

## 🎯 关键设计原则

1. **几何类型独立**：不依赖 Minecraft block
2. **Normal 来自 PlanSkeleton**：不自行推算
3. **折线墙分段处理**：每段独立 extrusion，允许轻微重叠
4. **向后兼容**：ExtrudedSolid 存储在 params 中，不影响现有 Generator

## 🔮 未来扩展

1. **更精确的几何计算**：从 PlanSkeleton 的实际数据生成精确 polygon/polyline
2. **复杂 join**：miter join、round join
3. **高度变化**：支持坡顶、台阶
4. **Boolean 运算**：合并相邻 solid、处理重叠
5. **Debug 可视化**：渲染 ExtrudedSolid 线框

## 📚 相关文档

- `docs/PLAN_PROGRAM_SCHEMA_V1.md` - PlanProgram schema
- `docs/PLAN_SKELETON_SCHEMA_V1.md` - PlanSkeleton schema
- `docs/STRUCTURAL_SKELETON_GEOMETRY_V1.md` - StructuralSkeleton 几何字段
- `docs/WALL_EXTRUSION_V1.md` - WallExtrusion 算法
- `docs/PLAN_TO_SKELETON_COMPILER.md` - 编译器文档
- `docs/examples/complete_plan_to_extrusion_example.md` - 完整示例
