# 路径驱动生成体系实现总结

## ✅ 已实现

### 1. 核心组件

#### PathSkeleton（路径骨架）
- `nodes` - 路径节点（世界坐标）
- `corridorRadius` - 建造影响半径
- `snapToGround` - 是否贴地
- `intent` - 路径意图（ROAD/WALL/BRIDGE/ALONG_PATH_BUILDING/GENERIC）

#### PathTool 扩展
- `toSkeleton()` - 导出为 PathSkeleton
- `getNodes()` - 获取所有路径节点
- `getCorridorRadius()` - 获取走廊半径

#### Path 专用生成器
- `PathSkeletonGenerator` - 路径骨架生成器（根据 intent 分发）
- `BasePathGenerator` - 路径生成器基类（提供通用逻辑）
- `RoadPathGenerator` - 道路生成器
- `WallAlongPathGenerator` - 城墙生成器
- `BridgePathGenerator` - 桥梁生成器
- `LinearBuildingPathGenerator` - 沿路径建筑生成器
- `GenericPathGenerator` - 通用路径生成器

### 2. PromptAssembler 集成

#### Skeleton Hint（骨架提示）
- 显式告诉 AI 路径骨架信息
- 强制使用 `PATH_POLYLINE` 骨架类型
- 路径拓扑是固定的，AI 只能调整风格和细节

#### 路径约束增强
- 包含路径意图信息
- 包含走廊半径
- 强调地形自适应

### 3. TerrainStrategy × Path 强制规则

#### 强制规则表

| TerrainStrategy | 强制行为 |
|----------------|---------|
| PRESERVE | 路径贴地，允许台阶 |
| ADAPTIVE | 局部垫高/削低（默认） |
| TERRACE | 分段平台 + 台阶 |
| FLATTEN | ❌ 禁止（除非用户明确说"完全平整路径"） |

#### 实现逻辑

```java
int computeTargetY(GenerationContext ctx, BlockPos node, TerrainPolicy terrainPolicy) {
    int groundY = ctx.getSurfaceY(node.getX(), node.getZ());
    
    return switch (terrainPolicy.strategy) {
        case PRESERVE -> groundY;  // 路径贴地
        case ADAPTIVE -> clamp(nodeY, groundY - maxCutDepth, groundY + maxFillHeight);
        case TERRACE -> snapToTerraceLevel(groundY);
        case FLATTEN -> {
            // ❌ 禁止（除非用户明确）
            if (terrainPolicy.scope == Scope.ALL) {
                yield groundY;
            } else {
                yield groundY; // 默认不允许
            }
        }
    };
}
```

## 🎯 完整闭环

### 数据流

```
PathTool（玩家画路径）
  ↓
PathSkeleton（结构事实，不依赖 AI）
  ↓
PromptAssembler（显式告诉 AI skeleton_hint）
  ↓
LLM（输出 PATH_POLYLINE + intent）
  ↓
PathSkeletonGenerator（根据 intent 分发）
  ↓
具体生成器（RoadPathGenerator / WallAlongPathGenerator / ...）
  ↓
BasePathGenerator.computeTargetY（地形策略处理）
  ↓
BlockPatch
  ↓
Preview → Apply
```

### 使用示例

#### 场景 1：沿路径修长城

```
1. 玩家用 PathTool 画一条路径
2. 输入："沿着这条路径在山脊上修一段长城"
3. 系统自动：
   - PathTool → PathSkeleton (intent=GENERIC)
   - PromptAssembler → skeleton_hint: PATH_POLYLINE, intent: WALL
   - LLM 输出：skeleton_type=PATH_POLYLINE, intent=WALL
   - PathSkeletonGenerator → WallAlongPathGenerator
   - TerrainStrategy=ADAPTIVE（依山就势）
   - 生成城墙 BlockPatch
4. Preview → Apply
```

#### 场景 2：道路生成

```
1. 玩家用 PathTool 画一条路径
2. 输入："修一条路"
3. 系统自动：
   - PathTool → PathSkeleton (intent=GENERIC)
   - PromptAssembler → skeleton_hint: PATH_POLYLINE, intent: ROAD
   - LLM 输出：skeleton_type=PATH_POLYLINE, intent=ROAD
   - PathSkeletonGenerator → RoadPathGenerator
   - TerrainStrategy=ADAPTIVE（局部调整）
   - 生成道路 BlockPatch
4. Preview → Apply
```

## 🔗 与现有系统的关系

### PathTool → Skeleton

- PathTool 不再是"提示 AI"的工具
- PathTool 直接决定建筑拓扑
- PathSkeleton 是结构事实，AI 不能破坏

### TerrainStrategy × Path

- 强制规则：PATH 作用域下，FLATTEN 被禁止（除非用户非常明确）
- 默认策略：ADAPTIVE（依山就势）
- 地形采样：每个节点独立采样地形高度
- 高度调整：根据策略限制在 maxCutDepth 和 maxFillHeight 内

### Generator 映射

```
SkeletonType.PATH_POLYLINE
  ↓
PathSkeletonGenerator
  ↓
根据 PathIntent 分发：
  - ROAD → RoadPathGenerator
  - WALL → WallAlongPathGenerator
  - BRIDGE → BridgePathGenerator
  - ALONG_PATH_BUILDING → LinearBuildingPathGenerator
  - GENERIC → GenericPathGenerator
```

## 📋 关键设计决策

### 1. PathTool 是一等公民

- PathTool 不只是"提示 AI"
- PathTool 决定建筑拓扑
- PathSkeleton 是结构事实

### 2. 地形策略强制规则

- PATH 作用域下，FLATTEN 被禁止（除非用户非常明确）
- 默认策略：ADAPTIVE（依山就势）
- 每个节点独立处理地形

### 3. 生成器分层

- `PathSkeletonGenerator` - 分发层
- `BasePathGenerator` - 通用逻辑层
- 具体生成器 - 实现层

## 🎯 总结

✅ **路径驱动生成体系已完全实现**

- PathTool → PathSkeleton ✅
- PathSkeleton → PromptAssembler ✅
- PromptAssembler → LLM ✅
- LLM → PathSkeletonGenerator ✅
- PathSkeletonGenerator → 具体生成器 ✅
- TerrainStrategy × Path 强制规则 ✅

**系统现在可以生成"依山就势"的路径结构，而不是"平整地形上的路径"！**

