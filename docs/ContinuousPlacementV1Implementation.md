# Socket → Continuous Placement 系统（H4）实现总结

## 📋 实现内容

根据建议，已成功实现 Socket → Continuous Placement 系统（H4），这是整个 Formacraft 从「点状构件」迈向「连续建筑器官」的关键跃迁之一。

## 🎯 核心目标

**H4 要解决的问题**：

当 socket 不是"一个点"，而是一条线 / 一圈 / 一条路径时，如何沿它"连续地、规则地、可裁剪地"放置构件。

这正是：
- 城墙
- 栏杆
- 回廊
- 长城

这些建筑形态的共同本质。

**完整流程**：
```
ContinuousSocket
   ↓ sample
[ P0, P1, P2, ... ]
   ↓ segment grouping
[ S0 ][ S1 ][ S2 ]
   ↓ per-segment anchor
[ Anchor0, Anchor1, Anchor2 ]
   ↓ component expansion
BlockPatch[]
```

## ✅ 已实现的组件

### 1. ContinuousSocket（连续插槽接口）

**位置**：`src/main/java/com/formacraft/common/component/socket/continuous/ContinuousSocket.java`

**核心功能**：
- 将"一条路径"抽象为可采样的连续插槽
- 提供采样点、法线、切线等几何信息

**关键方法**：
- `samplePoints(int step)` - 离散后的采样点（世界坐标）
- `normalAt(int index)` - 法线方向（用于朝向/外侧）
- `tangentAt(int index)` - 切线方向（用于沿边缘布置）
- `isClosed()` - 是否闭合（环）
- `getTotalLength()` - 获取路径总长度

**实现类**：
- `PathSocket` - 来自 PathTool
- `OutlineEdgeSocket` - 来自 OutlineTool 的边界
- `PolylineSocket` - 任意折线

### 2. PathSocket（路径连续插槽）

**位置**：`src/main/java/com/formacraft/common/component/socket/continuous/PathSocket.java`

**核心功能**：
- 从 PathTool.Path 创建 PathSocket
- 支持从 PathTool 的所有路径创建多个 PathSocket

**关键方法**：
- `fromPathTool(PathTool.Path)` - 从单个路径创建
- `fromPathTool(PathTool)` - 从 PathTool 的所有路径创建（合并所有路径）

### 3. OutlineEdgeSocket（轮廓边界连续插槽）

**位置**：`src/main/java/com/formacraft/common/component/socket/continuous/OutlineEdgeSocket.java`

**核心功能**：
- 从 OutlineTool.OutlineShape 创建 OutlineEdgeSocket
- 支持 polygon/rectangle/circle/free_draw 模式

### 4. PolylineSocket（折线连续插槽）

**位置**：`src/main/java/com/formacraft/common/component/socket/continuous/PolylineSocket.java`

**核心功能**：
- 任意折线的连续插槽实现
- 支持闭合和开放路径

### 5. ContinuousPlacementPolicy（连续放置策略）

**位置**：`src/main/java/com/formacraft/common/component/socket/continuous/ContinuousPlacementPolicy.java`

**核心功能**：
- 这是 H4 的核心控制器，定义了如何沿连续路径放置构件

**关键字段**：
- `segmentLength` - 单个构件的长度（block）
- `alignToCenter` - 是否居中对齐
- `allowOverlap` - 是否允许少量重叠
- `cornerMode` - 转角处理（CornerHandling）
- `heightPolicy` - 高度策略（HeightPolicy）

**预定义策略（四个典型建筑形态）**：
- `WALL_POLICY` - 城墙策略（3 block 一段，转角插柱，台阶高度）
- `RAILING_POLICY` - 栏杆策略（1 block 一段，45° 斜接，固定高度）
- `COLONNADE_POLICY` - 回廊策略（4 block 一段，平滑过渡，贴地）
- `GREAT_WALL_POLICY` - 长城策略（5 block 一段，转角插柱，自适应底座）

### 6. CornerHandling（转角处理策略）

**位置**：`src/main/java/com/formacraft/common/component/socket/continuous/CornerHandling.java`

**枚举值**：
- `CUT` - 切断（直角）
- `MITER` - 45° 斜接
- `PILLAR` - 转角插柱
- `SMOOTH` - 平滑过渡（回廊）

### 7. HeightPolicy（高度策略）

**位置**：`src/main/java/com/formacraft/common/component/socket/continuous/HeightPolicy.java`

**枚举值**：
- `FOLLOW_TERRAIN` - 贴地
- `STEP_TERRACE` - 台阶
- `FIXED_BASE` - 固定高度
- `ADAPTIVE_FOUNDATION` - 自适应底座（高级）

### 8. Segment（段）

**位置**：`src/main/java/com/formacraft/common/component/socket/continuous/Segment.java`

**核心功能**：
- 连续路径的一个片段，用于放置单个构件

**关键字段**：
- `startIndex / endIndex` - 段的起始/结束点索引
- `points` - 段的采样点列表
- `center` - 段的中心点（用于锚点）
- `direction` - 段的朝向（切线方向）
- `length` - 段的长度（方块单位，近似）

### 9. Segmenter（段切分器）

**位置**：`src/main/java/com/formacraft/common/component/socket/continuous/Segmenter.java`

**核心功能**：
- 将连续路径切分为多个段

**切分规则**：
- 沿路径累计距离
- 达到 segmentLength → 新 segment
- 剩余不足一段 → 末尾裁剪/合并（由 policy 决定）

**关键方法**：
- `split(List<BlockPos> points, int segmentLength)` - 将采样点列表切分为段

### 10. AnchorResolver（锚点解析器）

**位置**：`src/main/java/com/formacraft/common/component/socket/continuous/AnchorResolver.java`

**核心功能**：
- 为段计算锚点位置

**策略**：
- `alignToCenter = true` - 使用段的中心点
- `alignToCenter = false` - 使用段的起始点

### 11. FacingResolver（朝向解析器）

**位置**：`src/main/java/com/formacraft/common/component/socket/continuous/FacingResolver.java`

**核心功能**：
- 从段和连续插槽计算朝向

**实现**：
- 将段的切线方向（Vec3d）转换为 Direction（水平方向）

### 12. CornerHandler（转角处理器）

**位置**：`src/main/java/com/formacraft/common/component/socket/continuous/CornerHandler.java`

**核心功能**：
- 处理转角处的构件放置

**关键方法**：
- `handle(Segment, ComponentDefinition, ContinuousPlacementPolicy)` - 处理转角
- `isCorner(Segment, int, List<Segment>)` - 检查是否是转角（通过方向变化判断）

### 13. ComponentExpander（构件展开器）

**位置**：`src/main/java/com/formacraft/common/component/socket/continuous/ComponentExpander.java`

**核心功能**：
- 将构件展开为 BlockPatch 列表

**实现**：
- 在段的锚点位置放置构件
- 根据高度策略调整 Y 坐标
- 生成所有构件的 BlockPatch

### 14. ContinuousPlacementEngine（连续放置引擎）

**位置**：`src/main/java/com/formacraft/common/component/socket/continuous/ContinuousPlacementEngine.java`

**核心功能**：
- H4 的核心类，完整流程的入口

**完整流程**：
1. 采样点（`socket.samplePoints(1)`）
2. 切分为段（`Segmenter.split(samples, policy.segmentLength())`）
3. 对每个段进行处理：
   - 检查是否是转角 → `CornerHandler.handle()`
   - 普通段 → `ComponentExpander.expand()`
4. 返回 BlockPatch 列表

## 📊 使用示例

### 示例 1：城墙（Wall）

```java
// 从 PathTool 创建连续插槽
List<PathSocket> sockets = PathSocket.fromPathTool(PathTool.INSTANCE);

// 加载墙段构件
ComponentDefinition wallSegment = ComponentStorage.loadComponent("wall_segment_01");

// 使用城墙策略
ContinuousPlacementPolicy policy = ContinuousPlacementPolicy.WALL_POLICY;

// 沿路径放置
for (PathSocket socket : sockets) {
    List<BlockPatch> patches = ContinuousPlacementEngine.place(
        socket, wallSegment, policy
    );
    // 应用 patches...
}
```

### 示例 2：栏杆（Railing）

```java
// 从 OutlineTool 创建连续插槽
OutlineEdgeSocket socket = OutlineEdgeSocket.fromOutlineTool(
    OutlineTool.INSTANCE.getShape()
);

// 加载栏杆构件
ComponentDefinition railing = ComponentStorage.loadComponent("railing_01");

// 使用栏杆策略
ContinuousPlacementPolicy policy = ContinuousPlacementPolicy.RAILING_POLICY;

// 沿边缘放置
List<BlockPatch> patches = ContinuousPlacementEngine.place(
    socket, railing, policy
);
```

### 示例 3：回廊（Colonnade）

```java
// 从 PathTool 创建连续插槽
PathSocket socket = PathSocket.fromPathTool(path);

// 加载柱构件
ComponentDefinition column = ComponentStorage.loadComponent("column_01");

// 使用回廊策略
ContinuousPlacementPolicy policy = ContinuousPlacementPolicy.COLONNADE_POLICY;

// 沿路径放置
List<BlockPatch> patches = ContinuousPlacementEngine.place(
    socket, column, policy
);
```

### 示例 4：长城（Great Wall）

```java
// 从 PathTool 创建连续插槽
List<PathSocket> sockets = PathSocket.fromPathTool(PathTool.INSTANCE);

// 加载长城墙段构件
ComponentDefinition wallSegment = ComponentStorage.loadComponent("great_wall_segment");

// 使用长城策略
ContinuousPlacementPolicy policy = ContinuousPlacementPolicy.GREAT_WALL_POLICY;

// 沿路径放置（高度随山势）
for (PathSocket socket : sockets) {
    List<BlockPatch> patches = ContinuousPlacementEngine.place(
        socket, wallSegment, policy
    );
    // 应用 patches...
}
```

## 🔄 完整数据流

```
[ 工具状态：PathTool / OutlineTool ]
        ↓
ContinuousSocket（PathSocket / OutlineEdgeSocket）
        ↓
samplePoints(1)
        ↓
[ 采样点列表：P0, P1, P2, ... ]
        ↓
Segmenter.split(points, segmentLength)
        ↓
[ 段列表：S0, S1, S2, ... ]
        ↓
[ 对每个段：]
        ↓
[ 检查是否是转角？]
        ↓
[ 是 → CornerHandler.handle() ]
[ 否 → ComponentExpander.expand() ]
        ↓
[ BlockPatch 列表 ]
        ↓
[ 应用到世界 ]
```

## ✅ 完成度

| 组件 | 状态 | 说明 |
|------|------|------|
| ContinuousSocket | ✅ 完成 | 连续插槽接口 |
| PathSocket | ✅ 完成 | 来自 PathTool |
| OutlineEdgeSocket | ✅ 完成 | 来自 OutlineTool |
| PolylineSocket | ✅ 完成 | 任意折线 |
| ContinuousPlacementPolicy | ✅ 完成 | 连续放置策略（包含四个预定义策略） |
| CornerHandling | ✅ 完成 | 转角处理策略 |
| HeightPolicy | ✅ 完成 | 高度策略 |
| Segment | ✅ 完成 | 段数据结构 |
| Segmenter | ✅ 完成 | 段切分算法 |
| AnchorResolver | ✅ 完成 | 锚点解析器 |
| FacingResolver | ✅ 完成 | 朝向解析器 |
| CornerHandler | ✅ 完成 | 转角处理器 |
| ComponentExpander | ✅ 完成 | 构件展开器 |
| ContinuousPlacementEngine | ✅ 完成 | 连续放置引擎（核心类） |

## 🎉 总结

已成功实现 Socket → Continuous Placement 系统（H4）：

- ✅ **ContinuousSocket 接口和实现类** - PathSocket, OutlineEdgeSocket, PolylineSocket
- ✅ **ContinuousPlacementPolicy** - 连续放置策略（包含四个预定义策略：城墙、栏杆、回廊、长城）
- ✅ **Segment 切分算法** - Segmenter
- ✅ **ContinuousPlacementEngine** - 核心类（完整流程）
- ✅ **辅助类** - AnchorResolver, FacingResolver, CornerHandler, ComponentExpander

**这一层完成后，系统立刻获得的能力**：
- ✅ 当 socket 不是"一个点"，而是一条线/一圈/一条路径时，可以沿它"连续地、规则地、可裁剪地"放置构件
- ✅ 支持四个典型建筑形态：城墙、栏杆、回廊、长城
- ✅ 支持多种转角处理策略：切断、斜接、插柱、平滑过渡
- ✅ 支持多种高度策略：贴地、台阶、固定高度、自适应底座
- ✅ 从「放一个东西」升级为「沿空间逻辑生长建筑」

**这是整个 Formacraft 从「点状构件」迈向「连续建筑器官」的关键跃迁之一。**

---

**实现时间**: 2026-01-14  
**版本**: v1.0  
**状态**: ✅ 核心功能完成，已实现完整流程
