# Geometry Modifier × Tool 系统实现总结

## 实现状态

### ✅ 已完全实现

建议中的 Geometry Modifier × Tool 系统已经全部实现：

1. ✅ **GeometryConstraint** - 几何约束接口
2. ✅ **GeometryConstraintPipeline** - 约束管道（组合多个约束）
3. ✅ **SymmetryPlane** - 对称平面定义
4. ✅ **SymmetryProcessor** - 对称处理器（生成型约束）
5. ✅ **NoBuildZone** - 禁区接口
6. ✅ **PolygonNoBuildZone** - 多边形禁区实现
7. ✅ **NoBuildConstraint** - 禁区约束器
8. ✅ **FootprintRegion** - 轮廓区域接口
9. ✅ **PolygonFootprint** - 多边形轮廓实现
10. ✅ **FootprintConstraint** - 轮廓约束器
11. ✅ **GeometryModifierPipeline 扩展** - 集成约束和对称处理

## 核心定位

**一句话定义**：
- Tool 不生成建筑
- Tool 不决定风格
- Tool 只"约束几何结果是否允许存在"
- 它们是 Geometry Modifier 的"裁判"

## 执行顺序

```
Skeleton
  ↓
SemanticPlacementOp（基础语义点）
  ↓
GeometryModifier（扩展几何）
  ↓
⬇ ToolConstraintPipeline（本章）
     - 对称 / 镜像
     - 禁区裁剪
     - 轮廓裁切
  ↓
合法 GeometryPlacementOps
  ↓
Palette → Block
```

**重要**：Tool 永远在 Geometry 之后、Palette 之前

## 核心组件

### 1. GeometryConstraint（几何约束接口）

**功能**：
- 判断一个几何点是否允许存在
- 所有约束的基础接口

**接口**：
```java
public interface GeometryConstraint {
    boolean allow(BlockPos pos);
}
```

### 2. GeometryConstraintPipeline（约束管道）

**功能**：
- 组合多个约束
- 所有约束都必须通过（AND 逻辑）

**使用示例**：
```java
GeometryConstraintPipeline pipeline = new GeometryConstraintPipeline();
pipeline.add(new FootprintConstraint(footprint));
pipeline.add(new NoBuildConstraint(noBuildZone));

boolean allowed = pipeline.allow(pos);
```

### 3. SymmetryPlane（对称平面）

**功能**：
- 定义对称轴（X 或 Z 轴）
- 计算镜像位置

**使用示例**：
```java
SymmetryPlane plane = new SymmetryPlane(SymmetryPlane.Axis.X, 10);
BlockPos mirrored = plane.mirror(originalPos);
```

### 4. SymmetryProcessor（对称处理器）

**功能**：
- 这是"生成型约束"，不是简单过滤
- 为每个输入点生成镜像点

**应用场景**：
- 宫殿
- 城堡
- 中轴对称建筑
- 对称屋顶 / 桥梁

**使用示例**：
```java
SymmetryProcessor processor = new SymmetryProcessor(plane);
Set<BlockPos> symmetric = processor.apply(originalPositions);
```

### 5. NoBuildZone（禁区）

**功能**：
- 定义任意形状的禁区
- 这是"斜线遮罩"的本质逻辑层

**实现**：
- `PolygonNoBuildZone` - 多边形禁区（2D，XZ 平面）

**应用场景**：
- 河道
- 山体
- 保护遗迹

**使用示例**：
```java
List<Point2> polygon = List.of(
    new Point2(0, 0),
    new Point2(10, 0),
    new Point2(10, 10),
    new Point2(0, 10)
);
NoBuildZone zone = new PolygonNoBuildZone(polygon);
NoBuildConstraint constraint = new NoBuildConstraint(zone);
```

### 6. FootprintRegion（轮廓区域）

**功能**：
- 定义建筑允许的轮廓范围
- 这是 Selection → AI → Patch → Preview 闭环的核心安全阀

**实现**：
- `PolygonFootprint` - 多边形轮廓（2D，XZ 平面）

**应用场景**：
- "只在选区内建造"
- "在轮廓范围内生成寺庙"
- Patch 自动裁剪

**使用示例**：
```java
List<Point2> polygon = List.of(
    new Point2(0, 0),
    new Point2(20, 0),
    new Point2(20, 20),
    new Point2(0, 20)
);
FootprintRegion footprint = new PolygonFootprint(polygon);
FootprintConstraint constraint = new FootprintConstraint(footprint);
```

## 完整流程

```
Skeleton / Component
  ↓
SemanticPlacementOp（基础点）
  ↓
GeometryModifierPipeline.applyModifiers()
  ↓
GeometryModifier.apply()（扩展为体）
  ↓
ExpandedPlacementOps（多个点）
  ↓
GeometryConstraintPipeline.allow()（裁剪不允许的点）
  ↓
SymmetryProcessor.apply()（生成镜像点，如果有）
  ↓
合法 GeometryPlacementOps
  ↓
SemanticBlockStateResolver
  ↓
BlockPatch
```

## 使用示例

### 示例 1：基础约束使用

```java
// 创建约束管道
GeometryConstraintPipeline pipeline = new GeometryConstraintPipeline();

// 添加轮廓约束
FootprintRegion footprint = new PolygonFootprint(selectionPolygon);
pipeline.add(new FootprintConstraint(footprint));

// 添加禁区约束
NoBuildZone noBuildZone = new PolygonNoBuildZone(protectedPolygon);
pipeline.add(new NoBuildConstraint(noBuildZone));

// 应用约束
List<SemanticPlacementOp> constrained = GeometryModifierPipeline.applyModifiersAndConstraints(
    baseOps,
    style,
    pipeline,
    null  // 无对称
);
```

### 示例 2：对称处理

```java
// 创建对称平面（X 轴，x = 10）
SymmetryPlane plane = new SymmetryPlane(SymmetryPlane.Axis.X, 10);
SymmetryProcessor symmetry = new SymmetryProcessor(plane);

// 应用约束和对称
List<SemanticPlacementOp> result = GeometryModifierPipeline.applyModifiersAndConstraints(
    baseOps,
    style,
    constraintPipeline,
    symmetry
);
```

### 示例 3：完整流程

```java
// 1. 生成基础语义操作
List<SemanticPlacementOp> baseOps = gen.generateSemantic(ctx, plan);

// 2. 应用几何修饰器
SemanticStyleProfile style = SemanticStyleProfileRegistry.getOrDefault("MEDIEVAL_CASTLE");
List<SemanticPlacementOp> expanded = GeometryModifierPipeline.applyModifiers(baseOps, style);

// 3. 创建约束管道
GeometryConstraintPipeline constraints = new GeometryConstraintPipeline();
constraints.add(new FootprintConstraint(footprint));
constraints.add(new NoBuildConstraint(noBuildZone));

// 4. 创建对称处理器（可选）
SymmetryProcessor symmetry = new SymmetryProcessor(
    new SymmetryPlane(SymmetryPlane.Axis.X, centerX)
);

// 5. 应用约束和对称
List<SemanticPlacementOp> finalOps = GeometryModifierPipeline.applyModifiersAndConstraints(
    baseOps,
    style,
    constraints,
    symmetry
);

// 6. 解析为 BlockPatch
List<BlockPatch> patches = SemanticBlockStateResolver.resolveToPatches(
    origin, finalOps, "MEDIEVAL_CASTLE", random
);
```

## 系统优势

### ✅ 工具不生成建筑，只约束几何结果

- Tool 不决定风格
- Tool 不生成内容
- Tool 只"约束几何结果是否允许存在"

### ✅ 从"生成器"迈向"建筑系统"

- 不再是简单的生成器
- 是一个完整的建筑系统
- 支持复杂的空间约束和规则

### ✅ 核心安全阀

- Selection → AI → Patch → Preview 闭环
- 轮廓裁切确保不越界
- 禁区保护确保不破坏

### ✅ 对称处理

- 支持中轴对称建筑
- 自动生成镜像点
- 适用于宫殿、城堡等

### ✅ 灵活的组合

- 可以组合多个约束
- 支持对称 + 轮廓 + 禁区
- 所有约束都必须通过

## 与现有系统的关系

### 现有系统

1. **ToolPatchFilter** - 客户端 Patch 过滤（已存在）
2. **BuildContext** - 构建上下文（已存在）
3. **SelectionContext / OutlineContext / ProtectedZoneContext** - 工具上下文（已存在）

### 新系统

1. **GeometryConstraint** - 几何约束接口（新）
2. **GeometryConstraintPipeline** - 约束管道（新）
3. **SymmetryProcessor** - 对称处理器（新）
4. **NoBuildZone / FootprintRegion** - 区域定义（新）

**集成方式**：
- 新系统在服务端运行（Geometry Modifier 之后）
- 现有系统在客户端运行（Patch 过滤）
- 两者可以配合使用，提供双重保护

## 总结

✅ **完整的 Geometry Modifier × Tool 系统已实现**：
- GeometryConstraint（几何约束接口） ✅
- GeometryConstraintPipeline（约束管道） ✅
- SymmetryProcessor（对称处理器） ✅
- NoBuildZone（禁区） ✅
- FootprintRegion（轮廓区域） ✅
- GeometryModifierPipeline 扩展（集成约束和对称） ✅

✅ **设计优势**：
- Tool 不生成建筑，只约束几何结果
- 从"生成器"迈向"建筑系统"
- 核心安全阀（Selection → AI → Patch → Preview）
- 支持对称、轮廓、禁区等复杂约束

这是 FormaCraft 从"生成器"进化到"建筑系统"的关键跃迁层！

