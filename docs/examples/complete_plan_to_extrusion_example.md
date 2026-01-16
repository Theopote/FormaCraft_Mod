# 完整的 PlanSkeleton → ExtrudedSolid 流程示例

## 🔄 完整链路

```
PlanSkeleton (2D 语义)
  ↓
PlanSkeletonToStructuralSkeletonConverter
  ↓
StructuralSkeleton (3D 结构骨架，使用几何类型)
  ↓
StructuralSkeletonToExecutablePlanConverter
  ↓ (内部调用 WallExtrusion.extrude)
ExtrudedSolid (3D 几何体)
  ↓
ExecutableSkeletonPlan (包含 ExtrudedSolid，供 Generator/Socket 使用)
```

## 💻 代码示例

### 完整流程

```java
// 1. 输入：PlanSkeleton（假设已经从 PlanProgram 转换而来）
PlanSkeleton planSkeleton = ...; // 包含 edges, zones, courtyards 等

// 2. PlanSkeleton → StructuralSkeleton
StructuralSkeleton structural = PlanSkeletonToStructuralSkeletonConverter.convert(planSkeleton);

// 3. StructuralSkeleton → ExecutableSkeletonPlan（内部自动执行 extrusion）
List<ExecutableSkeletonPlan> plans = StructuralSkeletonToExecutablePlanConverter.convert(structural);

// 4. 提取 ExtrudedSolid（如果需要在应用层使用）
for (ExecutableSkeletonPlan plan : plans) {
    // 获取 extrusion 结果
    ExtrudedSolid solid = plan.get("extruded_solid", null);
    if (solid != null) {
        // 使用 ExtrudedSolid
        List<Vec3> vertices = solid.vertices;
        List<Face> faces = solid.faces;
        
        // 例如：用于可视化、碰撞检测、Socket 生成等
    }
}
```

### 直接使用 WallExtrusion

```java
// 如果你只想执行 extrusion，不经过完整的转换链
StructuralSkeleton.WallSegment wall = ...;

// 执行 extrusion
List<ExtrudedSolid> solids = WallExtrusion.extrude(wall);

// 处理结果
for (ExtrudedSolid solid : solids) {
    System.out.println("Vertices: " + solid.vertexCount());
    System.out.println("Faces: " + solid.faceCount());
    
    // 访问顶点
    for (Vec3 vertex : solid.vertices) {
        System.out.println("  Vertex: " + vertex);
    }
    
    // 访问面
    for (Face face : solid.faces) {
        System.out.println("  Face with " + face.vertexCount() + " vertices");
        System.out.println("    Normal: " + face.normal);
    }
}
```

## 📊 示例：简单直线墙

### 输入：WallSegment

```java
// 创建简单的直线墙
Vec2 p0 = new Vec2(0, 0);
Vec2 p1 = new Vec2(10, 0);
Polyline2D baseline = Polyline2D.line(p0, p1);

HeightProfile heightProfile = HeightProfile.fixed(0.0, 5.0);
Vector2 normal = Vector2.from(new Vec2(0, 1)); // 指向 Z+ 方向（朝外）

StructuralSkeleton.WallSegment wall = new StructuralSkeleton.WallSegment(
    "wall_1",
    WallType.EXTERNAL,
    baseline,
    1.0,  // thickness
    heightProfile,
    normal,
    List.of("core")
);
```

### Extrusion 过程

```
Step 1: 计算偏移（thickness = 1.0）
  P0(0,0) -------- P1(10,0)
  normal = (0, 1)
  offset = 0.5
  
  A = (0, 0.5)   - 外侧起点
  B = (10, 0.5)  - 外侧终点
  C = (10, -0.5) - 内侧终点
  D = (0, -0.5)  - 内侧起点

Step 2: 拉伸到 3D（baseY=0, topY=5）
  底部 4 点：A0, B0, C0, D0 (y=0)
  顶部 4 点：A1, B1, C1, D1 (y=5)

Step 3: 构造 6 个面
  - 外立面（朝 Z+）
  - 内立面（朝 Z-）
  - 顶面
  - 底面
  - 端面1（起点）
  - 端面2（终点）
```

### 输出：ExtrudedSolid

```java
ExtrudedSolid solid = WallExtrusion.extrude(wall).get(0);

// solid 包含：
// - 8 个顶点（底部 4 个 + 顶部 4 个）
// - 6 个面（每个面 4 个顶点）
```

## 📊 示例：折线墙

### 输入：折线墙

```java
// 创建折线墙（L 形）
Vec2 p0 = new Vec2(0, 0);
Vec2 p1 = new Vec2(10, 0);
Vec2 p2 = new Vec2(10, 10);
Polyline2D baseline = new Polyline2D(List.of(p0, p1, p2));

StructuralSkeleton.WallSegment wall = new StructuralSkeleton.WallSegment(
    "wall_L",
    WallType.EXTERNAL,
    baseline,
    1.0,
    HeightProfile.fixed(0.0, 5.0),
    normal,
    List.of("core")
);
```

### Extrusion 过程

```
折线墙会分成两段：
  段1: P0 → P1
  段2: P1 → P2

每段独立 extrusion，生成 2 个 ExtrudedSolid

注意：两段在 P1 处可能重叠（bevel join），v1 接受这种情况
```

### 输出：多个 ExtrudedSolid

```java
List<ExtrudedSolid> solids = WallExtrusion.extrude(wall);

// solids.size() == 2（两个独立 solid）
// 每个 solid 都是独立的矩形体
```

## 🔍 验证要点

### 1. 检查几何正确性

```java
// 验证顶点数量
for (ExtrudedSolid solid : solids) {
    assert solid.vertexCount() == 8 : "直线墙应该有 8 个顶点";
    assert solid.faceCount() == 6 : "应该有 6 个面";
}

// 验证面的顶点数
for (Face face : solid.faces) {
    assert face.vertexCount() == 4 : "每个面应该是 4 个顶点（矩形）";
}
```

### 2. 检查 normal 方向

```java
// 外立面的法线应该指向外侧
Face exteriorFace = solid.faces.get(0); // 外立面
Vector2 expectedNormal = wall.normal.toVec2();
// 验证 normal 方向是否正确
```

### 3. 检查高度

```java
// 所有底部顶点应该在 baseY
for (Vec3 vertex : solid.vertices) {
    if (vertex.y() == wall.heightProfile.baseY) {
        // 底部顶点
    } else if (vertex.y() == wall.heightProfile.topY) {
        // 顶部顶点
    }
}
```

## 🎯 与现有系统的集成

### ExecutableSkeletonPlan 中的 ExtrudedSolid

```java
// 从 ExecutableSkeletonPlan 提取 ExtrudedSolid
ExecutableSkeletonPlan plan = ...;

ExtrudedSolid solid = plan.get("extruded_solid", null);
if (solid != null) {
    // 用于：
    // 1. Debug 可视化
    // 2. Socket 生成（从几何提取 WALL_SURFACE）
    // 3. 碰撞检测
    // 4. 未来：直接几何查询
}
```

### 与 Generator 的兼容性

```java
// ExecutableSkeletonPlan 仍然包含 Generator 需要的参数
plan.put("points", blockPosPoints); // 用于 PERIMETER_LOOP Generator
plan.put("height", height);
plan.put("width", thickness);

// ExtrudedSolid 是额外的几何信息，不影响现有 Generator
// 但为未来的"几何驱动"Generator 预留了接口
```

## 🚀 下一步应用

1. **Debug 可视化**：渲染 ExtrudedSolid 的线框
2. **Socket 生成**：从 ExtrudedSolid 的面提取 WALL_SURFACE sockets
3. **几何查询**：判断点是否在墙内、计算交集等
4. **优化**：合并相邻的 solid、去除重叠部分等
