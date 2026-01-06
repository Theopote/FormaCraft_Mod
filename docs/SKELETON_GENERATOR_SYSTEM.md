# SkeletonType → Generator 映射表系统

## 概述

实现了完整的 "SkeletonType → Generator" 映射表系统，将骨架类型转换为 BlockPatch 列表。

## 核心设计

### 1. 设计目标

- **SkeletonType 只是"语义标签"**：定义空间组织方式
- **Generator 负责落地**：将 SkeletonPlan → BlockPatch
- **COMPOUND 支持递归**：多个子 skeleton 的组合与递归生成
- **映射表特性**：
  - 类型安全（按 enum 注册）
  - 可替换（不同风格/版本/算法换 generator）
  - 可缺省（没注册时有 fallback）

### 2. 核心组件

#### GenerationContext
生成上下文，包含：
- `ServerWorld world` - 服务器世界
- `BlockPos origin` - 原点位置
- `Random random` - 随机数生成器
- `int maxOps` - 最大操作数（安全预算）

#### ExecutableSkeletonPlan
可执行的骨架计划（数据类）：
- `SkeletonType type` - 骨架类型
- `Map<String, Object> params` - 参数表
- `List<ExecutableSkeletonPlan> children` - 子计划（用于 COMPOUND）

#### ISkeletonGenerator
生成器接口：
```java
List<BlockPatch> generate(GenerationContext ctx, ExecutableSkeletonPlan plan);
```

#### SkeletonGeneratorRegistry
映射表注册表：
- 类型安全的 EnumMap
- 支持注册和获取
- 提供 fallback 机制

## 已实现的 Generator

### 基础骨架
- ✅ `LinearPathGenerator` - 直线路径
- ✅ `PathPolylineGenerator` - 折线路径
- ✅ `ContourFollowGenerator` - 等高线跟随（v1 复用折线）

### 径向骨架
- ✅ `RadialRingGenerator` - 闭合环形
- ✅ `RadialSpokeGenerator` - 中心辐射

### 垂直骨架
- ✅ `VerticalStackGenerator` - 垂直堆叠
- ✅ `VerticalTaperGenerator` - 向上收缩

### 区域骨架
- ✅ `GridGenerator` - 网格布局
- ✅ `CourtyardGenerator` - 中庭围合（v1 简化版）
- ✅ `PerimeterLoopGenerator` - 轮廓闭环
- ✅ `EnclosureGenerator` - 不规则围合（v1 复用轮廓）

### 结构骨架
- ✅ `SpanSuspensionGenerator` - 跨越结构（桥）

### 地形骨架
- ✅ `TerracedGenerator` - 台地式

### 组合骨架
- ✅ `HierarchicalTreeGenerator` - 主从结构（v1 复用 COMPOUND）
- ✅ `CompoundGenerator` - 递归组合

### Fallback
- ✅ `UnsupportedSkeletonGenerator` - 不支持的骨架类型

## 使用示例

### 示例 1：简单直线路径

```java
SkeletonBuildService service = new SkeletonBuildService();

ExecutableSkeletonPlan plan = new ExecutableSkeletonPlan(SkeletonType.LINEAR_PATH)
    .put("end", Map.of("dx", 0, "dy", 0, "dz", 16))
    .put("width", 3)
    .put("block", "minecraft:white_concrete");

List<BlockPatch> patches = service.build(world, origin, plan);
// patches 包含相对 origin 的 BlockPatch 列表
```

### 示例 2：复合结构

```java
ExecutableSkeletonPlan compound = new ExecutableSkeletonPlan(SkeletonType.COMPOUND)
    .addChild(new ExecutableSkeletonPlan(SkeletonType.GRID)
        .put("width", 32)
        .put("depth", 24)
        .put("step", 4)
        .put("block", "minecraft:lime_concrete"))
    .addChild(new ExecutableSkeletonPlan(SkeletonType.RADIAL_RING)
        .put("radius", 10)
        .put("block", "minecraft:purple_concrete"))
    .addChild(new ExecutableSkeletonPlan(SkeletonType.SPAN_SUSPENSION)
        .put("end", Map.of("dx", 0, "dy", 0, "dz", 24))
        .put("deckBlock", "minecraft:light_gray_concrete")
        .put("towerBlock", "minecraft:gray_concrete")
        .put("towerHeight", 12));

List<BlockPatch> patches = service.build(world, origin, compound);
```

### 示例 3：中心辐射（天坛）

```java
ExecutableSkeletonPlan plan = new ExecutableSkeletonPlan(SkeletonType.RADIAL_SPOKE)
    .put("radius", 15)
    .put("spokes", 8)
    .put("block", "minecraft:red_concrete");

List<BlockPatch> patches = service.build(world, origin, plan);
```

### 示例 4：向上收缩（塔）

```java
ExecutableSkeletonPlan plan = new ExecutableSkeletonPlan(SkeletonType.VERTICAL_TAPER)
    .put("height", 20)
    .put("radiusBase", 8)
    .put("radiusTop", 2)
    .put("block", "minecraft:orange_concrete");

List<BlockPatch> patches = service.build(world, origin, plan);
```

## 集成到 Pipeline

### 1. 从 BlockPatch 到预览

```java
List<BlockPatch> patches = service.build(world, origin, plan);

// 转换为预览（使用现有的 PatchPreviewState）
// PatchPreviewState.fromPatches(origin, patches)
```

### 2. 从 BlockPatch 到执行

```java
List<BlockPatch> patches = service.build(world, origin, plan);

// 使用现有的 PatchExecutor
PatchExecutor.apply(world, origin, patches);
```

## 扩展性

### 自定义 Generator

```java
SkeletonGeneratorRegistry registry = new SkeletonGeneratorRegistry(
    new UnsupportedSkeletonGenerator()
);

// 注册自定义生成器
registry.register(SkeletonType.CUSTOM_TYPE, new CustomGenerator());

SkeletonBuildService service = new SkeletonBuildService(registry);
```

### 替换现有 Generator

```java
SkeletonGeneratorRegistry registry = SkeletonGeneratorRegistry.createDefault();

// 替换 GRID 生成器
registry.register(SkeletonType.GRID, new AdvancedGridGenerator());

SkeletonBuildService service = new SkeletonBuildService(registry);
```

## 注意事项

1. **v1 实现**：当前实现是"能跑"的版本，生成的是骨架线条，不是最终建筑
2. **后续升级**：可以将 Generator 升级为生成"语义组件"（墙、塔、道路、桥面等）
3. **安全预算**：所有 Generator 都遵守 `maxOps` 限制，避免服务器崩溃
4. **相对坐标**：所有 BlockPatch 都是相对 `origin` 的偏移

## 总结

✅ 完整的映射表系统已实现
✅ 所有 16 个 SkeletonType 都有对应的 Generator
✅ 支持递归组合（COMPOUND）
✅ 类型安全、可替换、可缺省
✅ 可以直接集成到现有的 Patch 系统

