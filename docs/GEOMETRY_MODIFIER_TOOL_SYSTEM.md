# Geometry Modifier × Tool 系统实现总结

> **2026-11-15 核对**：本文原描述的 `NoBuildZone` / `PolygonNoBuildZone` / `NoBuildConstraint` /
> `FootprintRegion` / `PolygonFootprint` / `FootprintConstraint` **均已删除**（PR-5d，无生产调用者）。
> 现行约束表达见文末「现行约束表达」一节；本文其余部分（`GeometryConstraint` /
> `GeometryConstraintPipeline` / `SymmetryPlane` / `SymmetryProcessor`）仍然有效。

## 实现状态

### ✅ 现行有效

1. ✅ **GeometryConstraint** - 几何约束接口
2. ✅ **GeometryConstraintPipeline** - 约束管道（组合多个约束）
3. ✅ **SymmetryPlane** - 对称平面定义
4. ✅ **SymmetryProcessor** - 对称处理器（生成型约束）
5. ✅ **GeometryModifierPipeline 扩展** - 集成约束和对称处理

### ❌ 已删除

原列出的 `NoBuildZone` / `PolygonNoBuildZone` / `NoBuildConstraint` / `FootprintRegion` /
`PolygonFootprint` / `FootprintConstraint` 六个类**已删除**，其"轮廓裁切 / 禁区裁切"能力
改由 `BuildConstraints`（服务端）与 `BuildConstraintClipper` 承担（见文末）。

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
pipeline.add(someConstraint);

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

### 示例 1：约束管道

```java
// 创建约束管道
GeometryConstraintPipeline pipeline = new GeometryConstraintPipeline();
pipeline.add(someConstraint);

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

## 现行约束表达（替代已删除的 footprint / nobuild 类）

工具侧状态不再由 `OutlineContext` / `ProtectedZoneContext` / `SymmetryContext` 等
客户端上下文类传递，而是：

| 阶段 | 载体 | 位置 |
|------|------|------|
| 客户端采集 | `ToolConstraintSnapshot` | `common/tool/ToolConstraintSnapshot.java` |
| 客户端工厂 | `ToolConstraintSnapshotFactory.fromTools(...)` | `client/tool/ToolConstraintSnapshotFactory.java` |
| 服务端权威 | `BuildConstraints`（含 `allow(BlockPos)`） | `server/build/BuildConstraints.java` |
| 服务端裁剪 | `BuildConstraintClipper` | `server/build/BuildConstraintClipper.java` |
| 骨架侧对称 | `SymmetryConstraint` / `SymmetryProcessor` | `common/geometry/tool/symmetry/` |

> `OutlineContext` / `ProtectedZoneContext` / `SymmetryContext` / `ToolSnapshot` 已删除，
> 不得再作为工具上下文入口引用。

## 系统优势

### ✅ 工具不生成建筑，只约束几何结果

- Tool 不决定风格
- Tool 不生成内容
- Tool 只"约束几何结果是否允许存在"

### ✅ 核心安全阀

- Selection → AI → Patch → Preview 闭环
- 轮廓裁切确保不越界
- 禁区保护确保不破坏

### ✅ 对称处理

- 支持中轴对称建筑
- 自动生成镜像点
- 适用于宫殿、城堡等

## 总结

✅ **Geometry Modifier × Tool 系统（现行部分）**：
- GeometryConstraint（几何约束接口） ✅
- GeometryConstraintPipeline（约束管道） ✅
- SymmetryProcessor（对称处理器） ✅
- GeometryModifierPipeline 扩展（集成约束和对称） ✅

⚠️ **已删除**：NoBuildZone / FootprintRegion 及其实现与约束器 —— 改用 `BuildConstraints` +
`BuildConstraintClipper`。

✅ **设计优势**：
- Tool 不生成建筑，只约束几何结果
- 核心安全阀（Selection → AI → Patch → Preview）
- 支持对称、轮廓、禁区等复杂约束

