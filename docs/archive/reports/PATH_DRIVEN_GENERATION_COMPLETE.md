# 路径驱动生成体系完整实现总结

## ✅ 已完全实现

### 1. 核心数据模型

#### PathSkeleton（路径骨架）
- **位置**：`src/main/java/com/formacraft/common/skeleton/PathSkeleton.java`
- **字段**：
  - `nodes` - 路径节点（世界坐标）
  - `corridorRadius` - 建造影响半径
  - `snapToGround` - 是否贴地
  - `intent` - 路径意图（ROAD/WALL/BRIDGE/ALONG_PATH_BUILDING/GENERIC）

#### PathTool 扩展
- **新增方法**：
  - `getNodes()` - 获取所有路径节点
  - `getCorridorRadius()` - 获取走廊半径
  - `toSkeleton()` - 导出为 PathSkeleton

### 2. PromptAssembler 集成

#### PathSkeleton → PromptContext
- **位置**：`ToolPromptBuilder.buildToolContext()`
- **功能**：从 PathTool 导出 PathSkeleton，并识别路径意图
- **意图识别**：
  - "长城"/"城墙"/"wall" → WALL
  - "桥"/"bridge" → BRIDGE
  - "路"/"road"/"道路" → ROAD
  - "沿街"/"沿线"/"along" → ALONG_PATH_BUILDING
  - 其他 → GENERIC

#### Skeleton Hint（骨架提示）
- **位置**：`PromptAssembler.skeletonHintBlock()`
- **功能**：显式告诉 AI 必须使用 `PATH_POLYLINE` 骨架类型
- **输出**：
  ```
  SKELETON HINT:
  - type: PATH_POLYLINE
  - intent: WALL
  - corridor_radius: 3
  - node_count: 10
  - IMPORTANT: You MUST use PATH_POLYLINE skeleton type
  - The path topology is fixed; you can only adjust style and details
  ```

### 3. 生成器系统

#### PathSkeletonGenerator（路径骨架生成器）
- **位置**：`src/main/java/com/formacraft/server/skeleton/gen/path/PathSkeletonGenerator.java`
- **功能**：根据 PathIntent 分发到具体生成器
- **映射**：
  - `ROAD` → `RoadPathGenerator`
  - `WALL` → `WallAlongPathGenerator`
  - `BRIDGE` → `BridgePathGenerator`
  - `ALONG_PATH_BUILDING` → `LinearBuildingPathGenerator`
  - `GENERIC` → `GenericPathGenerator`

#### BasePathGenerator（路径生成器基类）
- **位置**：`src/main/java/com/formacraft/server/skeleton/gen/path/BasePathGenerator.java`
- **核心方法**：
  - `computeTargetY()` - 根据 TerrainStrategy 计算目标高度
  - `generateCorridor()` - 生成路径走廊

#### 具体生成器实现
- `RoadPathGenerator` - 道路生成
- `WallAlongPathGenerator` - 城墙生成
- `BridgePathGenerator` - 桥梁生成
- `LinearBuildingPathGenerator` - 沿路径建筑生成
- `GenericPathGenerator` - 通用路径生成

### 4. TerrainStrategy × Path 强制规则

#### 强制规则表

| TerrainStrategy | 强制行为 | 实现 |
|----------------|---------|------|
| PRESERVE | 路径贴地，允许台阶 | `groundY` |
| ADAPTIVE | 局部垫高/削低 | `clamp(nodeY, groundY - maxCutDepth, groundY + maxFillHeight)` |
| TERRACE | 分段平台 + 台阶 | `snapToTerraceLevel(groundY)` |
| FLATTEN | ❌ 禁止（除非用户明确） | 默认不允许，除非 `scope=ALL` |

#### 实现位置
- **BasePathGenerator.computeTargetY()** - 核心高度计算逻辑

### 5. 注册表更新

#### SkeletonGeneratorRegistry
- **更新**：`PATH_POLYLINE` 现在映射到 `PathSkeletonGenerator`
- **位置**：`src/main/java/com/formacraft/server/skeleton/gen/SkeletonGeneratorRegistry.java`

## 🎯 完整数据流

```
玩家画路径（PathTool）
  ↓
PathTool.toSkeleton() → PathSkeleton（结构事实）
  ↓
ToolPromptBuilder.buildToolContext() → PromptContext.pathSkeleton
  ↓
ToolPromptBuilder.resolvePathIntent() → 识别意图（WALL/ROAD/BRIDGE/...）
  ↓
PromptAssembler.skeletonHintBlock() → 显式告诉 AI：必须用 PATH_POLYLINE
  ↓
LLM 输出：skeleton_type=PATH_POLYLINE, intent=WALL
  ↓
SkeletonBuildService.build() → SkeletonGeneratorRegistry.get(PATH_POLYLINE)
  ↓
PathSkeletonGenerator.generate() → 根据 intent 分发
  ↓
WallAlongPathGenerator.generate() → 使用 BasePathGenerator.computeTargetY()
  ↓
BasePathGenerator.computeTargetY() → 根据 TerrainStrategy 计算高度
  ↓
BlockPatch（依山就势的路径结构）
  ↓
Preview → Apply
```

## 📋 使用示例

### 场景：沿路径修长城

```
1. 玩家用 PathTool 画一条路径（沿山脊）
2. 输入："沿着这条路径在山脊上修一段长城"
3. 系统自动完成：
   - PathTool.toSkeleton() → PathSkeleton (intent=GENERIC)
   - ToolPromptBuilder.resolvePathIntent() → 识别到"长城" → intent=WALL
   - PromptAssembler → skeleton_hint: PATH_POLYLINE, intent: WALL
   - TerrainPolicyResolver → scope=PATH, strategy=ADAPTIVE
   - LLM 输出：skeleton_type=PATH_POLYLINE, intent=WALL
   - PathSkeletonGenerator → WallAlongPathGenerator
   - BasePathGenerator.computeTargetY() → 依山就势（ADAPTIVE）
   - 生成城墙 BlockPatch（每个节点独立处理地形）
4. Preview → Apply
```

## 🔗 关键设计决策

### 1. PathTool 是一等公民

- ✅ PathTool 不只是"提示 AI"
- ✅ PathTool 决定建筑拓扑
- ✅ PathSkeleton 是结构事实，AI 不能破坏

### 2. 地形策略强制规则

- ✅ PATH 作用域下，FLATTEN 被禁止（除非用户非常明确）
- ✅ 默认策略：ADAPTIVE（依山就势）
- ✅ 每个节点独立处理地形

### 3. 生成器分层

- ✅ `PathSkeletonGenerator` - 分发层
- ✅ `BasePathGenerator` - 通用逻辑层（地形处理）
- ✅ 具体生成器 - 实现层（道路/城墙/桥梁等）

## 🎯 系统能力

### ✅ 现在可以做什么

1. **路径驱动生成**
   - 玩家画路径 → 自动生成沿路径的结构
   - 支持道路、城墙、桥梁、沿街建筑等

2. **地形自适应**
   - 默认 ADAPTIVE 策略（依山就势）
   - 每个节点独立处理地形
   - 不整体平整地形

3. **意图识别**
   - 从用户输入自动识别路径意图
   - 支持中文和英文关键词

4. **完整闭环**
   - PathTool → PathSkeleton → Prompt → LLM → Generator → Patch → Preview → Apply

## 📝 总结

✅ **路径驱动生成体系已完全实现**

- PathTool → PathSkeleton ✅
- PathSkeleton → PromptAssembler ✅
- PromptAssembler → LLM（skeleton_hint）✅
- LLM → PathSkeletonGenerator ✅
- PathSkeletonGenerator → 具体生成器 ✅
- TerrainStrategy × Path 强制规则 ✅

**系统现在可以生成"依山就势"的路径结构，而不是"平整地形上的路径"！**

**这是一个完整的"AI × 工具 × 地形 × 拓扑"闭环！**

