# Skeleton Generator 三项核心增强实现总结

## 实现状态

### ✅ 已完全实现

建议中的三项核心增强已经全部实现：

1. ✅ **支持朝向（facing）** - 通过 `FacingUtil` 和 `ExecutableSkeletonPlan.facing`
2. ✅ **支持宽度（横向扩展）** - 通过 `ExecutableSkeletonPlan.width`
3. ✅ **支持顺地形/贴地生成（terrain conform）** - 通过 `GenerationContext.getSurfaceY()` 和 `HeightPolicy`

## 实现细节

### 1. ExecutableSkeletonPlan 扩展

**新增字段**：
- `int length` - 基础几何：长度
- `int height` - 基础几何：高度
- `int width` - 横向宽度（单位：block）
- `Direction facing` - 朝向
- `boolean conformTerrain` - 是否贴地/顺地形
- `HeightPolicy heightPolicy` - 高度策略（FLAT/FOLLOW_TERRAIN/STEP_UP/SLOPE）

**便捷方法**：
- `applyParams()` - 从 params Map 中读取并设置所有字段（用于从 LLM 输出解析）

### 2. GenerationContext 地形查询能力

**新增方法**：
- `getSurfaceY(int x, int z)` - 查询地表高度（WORLD_SURFACE）
- `getMotionBlockingY(int x, int z)` - 查询运动阻挡高度（MOTION_BLOCKING_NO_LEAVES）

### 3. FacingUtil 工具类

**功能**：
- `forward(Direction)` - 获取前进方向向量
- `right(Direction)` - 获取右侧方向向量
- `left(Direction)` - 获取左侧方向向量
- `backward(Direction)` - 获取后方方向向量

### 4. LinearPathGenerator 完整版

**支持的特性**：
- ✅ 朝向：任意方向（NORTH/SOUTH/EAST/WEST）
- ✅ 宽度：横向扩展（左右对称）
- ✅ 顺地形：自动查询地表高度
- ✅ 高度策略：
  - `FLAT` - 完全平
  - `FOLLOW_TERRAIN` - 顺地形
  - `STEP_UP` - 台阶（每 4 格上升 1 格）
  - `SLOPE` - 坡道（线性上升）

## 使用示例

### 示例 1：顺地形的道路

```java
ExecutableSkeletonPlan plan = new ExecutableSkeletonPlan(SkeletonType.LINEAR_PATH)
    .put("length", 42)
    .put("width", 5)
    .put("facing", "EAST")
    .put("heightPolicy", "FOLLOW_TERRAIN")
    .put("conformTerrain", true)
    .put("block", "minecraft:gravel");

plan.applyParams(); // 从 params 读取到字段

List<BlockPatch> patches = service.build(world, origin, plan);
```

### 示例 2：台阶式城墙

```java
ExecutableSkeletonPlan plan = new ExecutableSkeletonPlan(SkeletonType.LINEAR_PATH)
    .put("length", 120)
    .put("width", 3)
    .put("facing", "NORTH")
    .put("heightPolicy", "STEP_UP")
    .put("block", "minecraft:stone_bricks");

plan.applyParams();

List<BlockPatch> patches = service.build(world, origin, plan);
```

### 示例 3：坡道

```java
ExecutableSkeletonPlan plan = new ExecutableSkeletonPlan(SkeletonType.LINEAR_PATH)
    .put("length", 50)
    .put("width", 4)
    .put("height", 10) // 总高度差
    .put("facing", "SOUTH")
    .put("heightPolicy", "SLOPE")
    .put("block", "minecraft:cobblestone");

plan.applyParams();

List<BlockPatch> patches = service.build(world, origin, plan);
```

### 示例 4：完全平的路径

```java
ExecutableSkeletonPlan plan = new ExecutableSkeletonPlan(SkeletonType.LINEAR_PATH)
    .put("length", 30)
    .put("width", 3)
    .put("facing", "WEST")
    .put("heightPolicy", "FLAT")
    .put("conformTerrain", false)
    .put("block", "minecraft:white_concrete");

plan.applyParams();

List<BlockPatch> patches = service.build(world, origin, plan);
```

## 设计原则

### ✅ 核心原则（已遵循）

> **Skeleton 只决定"结构拓扑"**
> - ✅ 朝向
> - ✅ 中心线
> - ✅ 宽度
> - ✅ 高度趋势
> 
> ❌ 不决定具体方块
> ❌ 不关心风格
> ❌ 不关心装饰

这些都应该在 Generator + Palette + Tool 约束层完成。

## Prompt 中的使用

### LLM 输出示例

```json
{
  "skeleton": {
    "type": "LINEAR_PATH",
    "length": 42,
    "width": 5,
    "facing": "EAST",
    "heightPolicy": "FOLLOW_TERRAIN",
    "conformTerrain": true
  }
}
```

### 解释

```
Skeleton:
- type: LINEAR_PATH
- length: 42
- width: 5
- facing: EAST
- height_policy: FOLLOW_TERRAIN

Interpretation:
This skeleton represents a main road aligned eastward,
adapting to terrain height naturally.
```

## 已具备的能力

✅ **城墙顺山** - 使用 `FOLLOW_TERRAIN` + `conformTerrain=true`
✅ **道路自动起伏** - 使用 `FOLLOW_TERRAIN`
✅ **桥梁主轴** - 使用 `FLAT` + 固定高度
✅ **建筑群骨架** - 使用 `LINEAR_PATH` + 任意朝向
✅ **AI 可以只管语义，不管地形** - 系统自动处理地形适应

## 总结

✅ **三项核心增强已完全实现**：
- 朝向支持 ✅
- 宽度支持 ✅
- 地形适应 ✅

✅ **设计原则已遵循**：
- Skeleton 只决定结构拓扑
- 不关心具体方块和风格

✅ **工程级可落地**：
- 完整的代码实现
- 类型安全
- 易于扩展

这正是"AI 规划空间，系统负责落地"的工程化版本！

