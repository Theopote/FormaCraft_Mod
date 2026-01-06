# SkeletonType 细化与扩展文档

## 概述

根据架构审查建议，对 `SkeletonType` 进行了扩展和细化，从 v1 的 8 个核心骨架扩展到 v1.1 的 16 个骨架类型，并引入了 `SkeletonContract` 语义约束系统。

## 扩展内容

### 新增骨架类型（6 个）

1. **CONTOUR_FOLLOW** - 等高线跟随
   - 用途：山路、长城、依地形路径
   - 特点：需要地形采样，支持不规则形状

2. **RADIAL_SPOKE** - 中心辐射
   - 用途：天坛、广场、祭坛、交通核心
   - 特点：需要中心点，支持分支，偏好对称

3. **COURTYARD** - 中庭式
   - 用途：四合院、修道院、办公园区中庭
   - 特点：需要中心点，支持不规则形状

4. **PERIMETER_LOOP** - 轮廓闭环
   - 用途：城墙、院落
   - 特点：支持不规则形状，轮廓跟随型

5. **ENCLOSURE** - 不规则围合
   - 用途：中式院落、古城城墙、山地要塞
   - 特点：支持不规则形状，适合轮廓工具

6. **TERRACED** - 台地式
   - 用途：梯田、山城、山地寺庙
   - 特点：需要地形采样，多层结构

7. **HIERARCHICAL_TREE** - 主从结构
   - 用途：寺庙群、校园、工业园、办公园区
   - 特点：支持分支，有明确的主从关系

## 完整骨架列表（v1.1）

### Linear / Path（3 个）
- `LINEAR_PATH` - 直线路径
- `PATH_POLYLINE` - 折线路径
- `CONTOUR_FOLLOW` - 等高线跟随 ⭐ 新增

### Radial / Center（2 个）
- `RADIAL_RING` - 闭合环形
- `RADIAL_SPOKE` - 中心辐射 ⭐ 新增

### Vertical（2 个）
- `VERTICAL_STACK` - 垂直堆叠
- `VERTICAL_TAPER` - 向上收缩

### Area / Enclosure（4 个）
- `GRID` - 网格
- `COURTYARD` - 中庭式 ⭐ 新增
- `PERIMETER_LOOP` - 轮廓闭环 ⭐ 新增
- `ENCLOSURE` - 不规则围合 ⭐ 新增

### Span / Structure（1 个）
- `SPAN_SUSPENSION` - 跨越结构

### Terrain（1 个）
- `TERRACED` - 台地式 ⭐ 新增

### Composite（2 个）
- `HIERARCHICAL_TREE` - 主从结构 ⭐ 新增
- `COMPOUND` - 任意组合

## SkeletonContract 语义约束系统

### 设计目标

为每个 `SkeletonType` 定义语义约束，使：
- **LLM** 能够稳定理解每个骨架的特性
- **Generator** 知道如何落地实现
- **Tool**（轮廓/选区）知道如何约束输入

### 接口定义

```java
public interface SkeletonContract {
    SkeletonType type();
    List<String> requiredAnchors();        // 必需的锚点
    boolean requiresTerrainSampling();     // 是否需要地形采样
    boolean allowsOverlap();                // 是否允许重叠
    boolean prefersSymmetry();              // 是否偏好对称
    boolean supportsIrregularShape();       // 是否支持不规则形状
    boolean isMultiLevel();                 // 是否多层结构
    boolean requiresCenter();               // 是否需要中心点
    boolean supportsBranches();             // 是否支持分支
    String description();                   // 描述信息
}
```

### 使用示例

```java
SkeletonType type = SkeletonType.RADIAL_SPOKE;
SkeletonContract contract = type.getContract();

// 检查是否需要中心点
if (contract.requiresCenter()) {
    // 确保提供了 center 锚点
}

// 检查是否需要地形采样
if (contract.requiresTerrainSampling()) {
    // 读取地形高度、坡度等信息
}

// 获取必需的锚点
List<String> anchors = contract.requiredAnchors();
// 对于 RADIAL_SPOKE: ["center"]
```

## 骨架特性对比表

| 骨架类型 | 需要中心 | 地形采样 | 支持不规则 | 多层 | 支持分支 | 偏好对称 |
|---------|---------|---------|-----------|------|---------|---------|
| LINEAR_PATH | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| PATH_POLYLINE | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ |
| CONTOUR_FOLLOW | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ |
| RADIAL_RING | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ |
| RADIAL_SPOKE | ✅ | ❌ | ❌ | ❌ | ✅ | ✅ |
| VERTICAL_STACK | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ |
| VERTICAL_TAPER | ✅ | ❌ | ❌ | ✅ | ❌ | ✅ |
| GRID | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| COURTYARD | ✅ | ❌ | ✅ | ❌ | ❌ | ✅ |
| PERIMETER_LOOP | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ |
| ENCLOSURE | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ |
| SPAN_SUSPENSION | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ |
| TERRACED | ❌ | ✅ | ✅ | ✅ | ❌ | ❌ |
| HIERARCHICAL_TREE | ❌ | ❌ | ✅ | ❌ | ✅ | ❌ |
| COMPOUND | ❌ | ❌ | ✅ | ✅ | ✅ | ❌ |

## 应用场景

### 场景 1：天坛生成
```java
SkeletonType type = SkeletonType.RADIAL_SPOKE;
SkeletonContract contract = type.getContract();

// 检查约束
assert contract.requiresCenter();  // 需要中心点
assert contract.prefersSymmetry(); // 偏好对称
assert contract.supportsBranches(); // 支持分支（多个方向）

// 生成时：
// 1. 确保提供 center 锚点
// 2. 从中心向多个方向辐射
// 3. 保持对称性
```

### 场景 2：山城生成
```java
SkeletonType type = SkeletonType.TERRACED;
SkeletonContract contract = type.getContract();

// 检查约束
assert contract.requiresTerrainSampling(); // 需要地形采样
assert contract.isMultiLevel();            // 多层结构
assert contract.supportsIrregularShape();  // 支持不规则形状

// 生成时：
// 1. 读取地形高度信息
// 2. 创建多个台地层级
// 3. 依地形调整形状
```

### 场景 3：四合院生成
```java
SkeletonType type = SkeletonType.COURTYARD;
SkeletonContract contract = type.getContract();

// 检查约束
assert contract.requiresCenter();          // 需要中心点（中庭）
assert contract.supportsIrregularShape(); // 支持不规则形状

// 生成时：
// 1. 确保提供 center 锚点（中庭位置）
// 2. 围绕中庭布置建筑
// 3. 可以是不规则形状
```

## 判断标准

一个 `SkeletonType` 是否值得存在？

**判断标准**：它是否代表了一种人类直觉中"空间组织方式"？

- ✅ **是**：应该存在于 Skeleton 层
- ❌ **否**：应该放在 Shape 或 Generator 层

当前所有骨架类型都符合这一标准。

## 下一步建议

### 1. 实现新骨架的 Generator

为新增的骨架类型实现对应的 Generator：
- `RadialSpokeSkeleton` - 中心辐射生成器
- `CourtyardSkeleton` - 中庭式生成器
- `TerraceSkeleton` - 台地式生成器
- `HierarchicalTreeSkeleton` - 主从结构生成器

### 2. 集成到 AI 提示词

在 Python 后端的提示词中，明确说明每个骨架类型的语义约束，帮助 LLM 更好地选择骨架类型。

### 3. Tool 集成

在轮廓工具和选区工具中，根据 `SkeletonContract` 的约束来验证和指导用户输入。

## 总结

通过这次扩展：
- ✅ 从 8 个骨架扩展到 16 个，覆盖更多建筑类型
- ✅ 引入了语义约束系统，使 LLM 和 Generator 能够更好地理解和使用骨架
- ✅ 保持了"空间组织方式"这一核心抽象
- ✅ 为未来的扩展打下了良好的基础

这套设计完全符合"真实世界建筑 + AI 规划能力"的目标。

