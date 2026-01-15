# SocketProvider v1 实现总结

## 📋 实现内容

根据建议，已成功实现 SocketProvider v1 的完整实现，目标是：

✅ 从 SelectionTool / OutlineTool / PathTool / Anchor 等"骨架/工具输入"生成 sockets

✅ 同时支持 WALL_SURFACE / EDGE_OUTER / FLOOR_SURFACE / WALL_OPENING（洞口）（v1）

✅ 可用于 AI 自动装配（Rank → Variant → Match → Patch）

✅ 也可用于 ComponentTool hover 高亮（显示可插位置）

## 🎯 核心目标

**设计原则**：
- v1 先做到"稳定、好用、可扩展"
- 不追求一次性把屋脊/屋面复杂识别做到完美
- 后面想加 ROOF_RIDGE / ROOF_SLOPE，只要新增 provider，不改旧逻辑

## ✅ 已实现的组件

### 1. Socket（更新版）

**位置**：`src/main/java/com/formacraft/common/component/socket/Socket.java`

**新增字段**：
- `id` - Socket ID（唯一标识，UUID）
- `tangent` - 沿边缘的切线方向（栏杆沿边布置）

**新增方法**：
- `center()` - 获取中心点（Vec3d）
- `centerBlockPos()` - 获取中心点（BlockPos）

**构造函数**：
- 支持多种构造函数（兼容旧代码 + 新功能）

### 2. SocketQueryContext（Socket 查询上下文）

**位置**：`src/main/java/com/formacraft/common/component/socket/SocketQueryContext.java`

**核心功能**：
- 把 Selection/Outline/Path/Anchor 等输入统一封装起来（v1 足够用）

**关键字段**：
- `focus` - 用于"就近收集 sockets"的中心点（通常是鼠标 hit、或 anchor）
- `radius` - 收集半径（方块/近似）
- `selectionMin / selectionMax` - 选区盒（如果存在）
- `outlinePolygon` - 轮廓多边形（世界坐标点，XZ 平面）
- `paths` - 路径（多段 polyline，每段是点序列）
- `includeOpenings` - 是否需要洞口 socket
- `openingMaxScan / openingMinW / openingMinH / openingMaxW / openingMaxH` - 洞口扫描参数

### 3. SocketUtil（Socket 工具类）

**位置**：`src/main/java/com/formacraft/common/component/socket/SocketUtil.java`

**核心功能**：
- 一些几何工具：从 BlockPos 造 Box、沿边采样等

**关键方法**：
- `blockBox(BlockPos)` - 以 blockpos 为单位盒（0..1）
- `thinBox(BlockPos, BlockPos, double)` - 构造一个沿着墙面/边缘的薄盒（厚度 t）
- `samplePolyline(List<Vec3d>, double)` - polyline 采样：按 step（方块）插值出点
- `approxHorizontalDir(Vec3d)` - 从 2D 方向（XZ）推导一个近似 Direction（四向）

### 4. ToolBasedSocketProvider（基于工具的 SocketProvider 接口）

**位置**：`src/main/java/com/formacraft/common/component/socket/providers/ToolBasedSocketProvider.java`

**核心功能**：
- 新的接口，使用 World + SocketQueryContext，而不是 BuildContext
- 用于从 SelectionTool / OutlineTool / PathTool 等工具输入生成 sockets

**关键方法**：
- `provide(World, SocketQueryContext)` - 提供 Socket 列表

### 5. SelectionBoxSocketProvider（选区 → 墙面/边缘/地面 sockets）

**位置**：`src/main/java/com/formacraft/common/component/socket/providers/SelectionBoxSocketProvider.java`

**核心功能**：
- 从选区盒生成 sockets

**产生的 Socket**：
- `FLOOR_SURFACE` - 底面一块（用于柱基、地面装饰）
- `WALL_SURFACE` - 四个侧面（用于门窗装饰、壁龛、雨棚等）
- `EDGE_OUTER` - 顶面外圈（用于栏杆、女儿墙）

### 6. OutlinePolygonSocketProvider（轮廓 → 边缘 sockets）

**位置**：`src/main/java/com/formacraft/common/component/socket/providers/OutlinePolygonSocketProvider.java`

**核心功能**：
- 从轮廓多边形生成边缘 sockets

**v1 策略**：
- outline polygon（XZ）→ 边界 polyline
- 沿边采样点（step=2）
- 每个采样点生成一个 EDGE_OUTER 小 socket（1×1×0.2）

**效果**：
- 这会让栏杆/女儿墙/外边缘装饰"沿轮廓走"

### 7. PathPolylineSocketProvider（路径 → 路边 sockets）

**位置**：`src/main/java/com/formacraft/common/component/socket/providers/PathPolylineSocketProvider.java`

**核心功能**：
- 从路径 polyline 生成路边 sockets

**v1 策略**：
- path polyline 采样（step=2）
- 每个采样点：
  - `FLOOR_SURFACE`（道路中心）
  - 两侧偏移 1 格的 `EDGE_OUTER`（路边，用于路灯/护栏/长城沿路）

**实现**：
- 用"近似水平法线"实现左右偏移

### 8. WallOpeningSocketProvider（墙面 → 洞口 sockets）

**位置**：`src/main/java/com/formacraft/common/component/socket/providers/WallOpeningSocketProvider.java`

**核心功能**：
- 从 WALL_SURFACE 上扫描空气矩形洞口，生成 WALL_OPENING

**v1 简化策略（性能可控）**：
- 先从其他 provider 得到 WALL_SURFACE sockets（本 provider 自己再生成也行，但 v1 复用）
- 对每个 wall surface box：
  - 在 box 投影到网格上做小范围扫描（openingMaxScan 限制）
  - 找到符合 minW/minH 的连续空气矩形
  - 生成一个 opening socket（Box）

**v1 不追求"完美识别所有洞"，只要能识别常见门洞/窗洞即可**

### 9. SocketProviders（Socket 提供者聚合器）

**位置**：`src/main/java/com/formacraft/common/component/socket/SocketProviders.java`

**核心功能**：
- 注册与聚合所有 SocketProvider
- 你以后新增 provider，只要 register()

**默认注册的 Providers**：
- `SelectionBoxSocketProvider`
- `OutlinePolygonSocketProvider`
- `PathPolylineSocketProvider`
- `WallOpeningSocketProvider`

**关键方法**：
- `register(ToolBasedSocketProvider)` - 注册 SocketProvider
- `collect(World, SocketQueryContext)` - 收集所有 Socket（自动裁剪距离 focus 太远的）

### 10. SocketQueryContextBuilder（Socket 查询上下文构建器）

**位置**：`src/main/java/com/formacraft/common/component/socket/SocketQueryContextBuilder.java`

**核心功能**：
- 把 Tool 状态接进 SocketQueryContext（关键）

**关键方法**：
- `fromTools(SelectionTool, OutlineTool, PathTool, Vec3d)` - 从工具实例创建 SocketQueryContext

**使用示例**：
```java
SocketQueryContext ctx = SocketQueryContextBuilder.fromTools(
    SelectionTool.INSTANCE,
    OutlineTool.INSTANCE,
    PathTool.INSTANCE,
    hitPosVec
);
List<Socket> sockets = SocketProviders.collect(world, ctx);
```

## 📊 使用示例

### 示例 1：从工具状态生成 Socket

```java
// 从工具状态创建查询上下文
SocketQueryContext ctx = SocketQueryContextBuilder.fromTools(
    SelectionTool.INSTANCE,
    OutlineTool.INSTANCE,
    PathTool.INSTANCE,
    hitPosVec  // 鼠标 hit 或 anchor
);

// 收集所有 Socket
List<Socket> sockets = SocketProviders.collect(world, ctx);

// 用于 AI 自动装配
List<AssemblyResult> results = AutoAssembler.assembleOnSockets(
    sockets, rules, skeletonKind, styleProfile, materialTone, random
);
```

### 示例 2：ComponentTool hover 高亮

```java
// 在 ComponentTool 的 tick() 方法中
SocketQueryContext ctx = SocketQueryContextBuilder.fromTools(
    SelectionTool.INSTANCE,
    OutlineTool.INSTANCE,
    PathTool.INSTANCE,
    hitPosVec
);

List<Socket> sockets = SocketProviders.collect(client.world, ctx);

// 匹配当前构件的合法 Socket
ComponentVariant variant = ...;
List<Socket> validSockets = SocketMatcher.match(variant, sockets);

// 渲染高亮
SocketHighlighter.renderHighlights(client, validSockets);
```

### 示例 3：Selection 立刻产生 wall/floor/edge sockets

```java
// 用户使用 SelectionTool 选择了一个区域
SelectionTool.INSTANCE.setSelection(min, max);

// 生成 Socket
SocketQueryContext ctx = SocketQueryContextBuilder.fromTools(
    SelectionTool.INSTANCE, null, null, focus
);
List<Socket> sockets = SocketProviders.collect(world, ctx);

// 结果：
// - FLOOR_SURFACE：底面
// - WALL_SURFACE：四个侧面
// - EDGE_OUTER：顶面外圈
```

### 示例 4：Outline 立刻产生边缘 sockets

```java
// 用户使用 OutlineTool 绘制了轮廓
OutlineTool.INSTANCE.finishPolygon(points);

// 生成 Socket
SocketQueryContext ctx = SocketQueryContextBuilder.fromTools(
    null, OutlineTool.INSTANCE, null, focus
);
List<Socket> sockets = SocketProviders.collect(world, ctx);

// 结果：
// - EDGE_OUTER：沿轮廓边缘的 sockets（适合栏杆/围墙/边界）
```

### 示例 5：Path 立刻产生路面/路边 sockets

```java
// 用户使用 PathTool 绘制了路径
PathTool.INSTANCE.finishPath();

// 生成 Socket
SocketQueryContext ctx = SocketQueryContextBuilder.fromTools(
    null, null, PathTool.INSTANCE, focus
);
List<Socket> sockets = SocketProviders.collect(world, ctx);

// 结果：
// - FLOOR_SURFACE：路面中心
// - EDGE_OUTER：两侧路边（适合路灯/护栏/沿路长城）
```

### 示例 6：Opening（洞口）在选区四面扫描

```java
// 用户选择了一个有洞口的墙体区域
SelectionTool.INSTANCE.setSelection(min, max);

// 生成 Socket（包括 openings）
SocketQueryContext ctx = SocketQueryContextBuilder.fromTools(
    SelectionTool.INSTANCE, null, null, focus
);
ctx.includeOpenings = true;
List<Socket> sockets = SocketProviders.collect(world, ctx);

// 结果：
// - WALL_SURFACE：四个侧面
// - WALL_OPENING：识别出的门洞/窗洞（能识别常见门洞/窗洞）
```

## 🔄 完整数据流

```
[ 工具状态：SelectionTool / OutlineTool / PathTool ]
        ↓
SocketQueryContextBuilder.fromTools()
        ↓
[ SocketQueryContext（统一封装）]
        ↓
SocketProviders.collect()
        ↓
[ 各个 Provider 生成 Socket ]
        ↓
[ 自动裁剪（距离 focus 太远的丢掉）]
        ↓
[ Socket 列表 ]
        ↓
[ AI 自动装配 / ComponentTool hover 高亮 ]
```

## ✅ v1 的能力与局限

### ✅ 能力（马上能用）

- **Selection** 立刻产生 wall/floor/edge sockets
- **Outline** 立刻产生边缘 sockets（适合栏杆/围墙/边界）
- **Path** 立刻产生路面/路边 sockets（适合路灯/护栏/沿路长城）
- **Opening（洞口）** 在选区四面扫描，能识别常见门洞/窗洞

### ⚠️ 局限（v1 可接受）

- **Opening 扫描依赖 selection box**（先不做"全世界墙面识别"）
- **Edge/Outline sockets 粒度较粗**（1×1 小块），后续可合并成"段 socket"
- **屋顶 ridge/slope 暂不做**（你后面需要时加 provider）

## ✅ 完成度

| 组件 | 状态 | 说明 |
|------|------|------|
| Socket（更新版） | ✅ 完成 | 添加 tangent 字段和辅助方法 |
| SocketQueryContext | ✅ 完成 | 统一封装工具输入 |
| SocketUtil | ✅ 完成 | 几何工具类 |
| ToolBasedSocketProvider | ✅ 完成 | 基于工具的 SocketProvider 接口 |
| SelectionBoxSocketProvider | ✅ 完成 | 选区 → 墙面/边缘/地面 sockets |
| OutlinePolygonSocketProvider | ✅ 完成 | 轮廓 → 边缘 sockets |
| PathPolylineSocketProvider | ✅ 完成 | 路径 → 路边 sockets |
| WallOpeningSocketProvider | ✅ 完成 | 墙面 → 洞口 sockets |
| SocketProviders | ✅ 完成 | 注册与聚合 |
| SocketQueryContextBuilder | ✅ 完成 | 从工具状态创建查询上下文 |

## 🎉 总结

已成功实现 SocketProvider v1 的完整实现：

- ✅ **Socket（更新版）** - 添加 tangent 字段和辅助方法
- ✅ **SocketQueryContext** - 统一封装工具输入
- ✅ **SocketUtil** - 几何工具类
- ✅ **ToolBasedSocketProvider** - 基于工具的 SocketProvider 接口
- ✅ **SelectionBoxSocketProvider** - 选区 → 墙面/边缘/地面 sockets
- ✅ **OutlinePolygonSocketProvider** - 轮廓 → 边缘 sockets
- ✅ **PathPolylineSocketProvider** - 路径 → 路边 sockets
- ✅ **WallOpeningSocketProvider** - 墙面 → 洞口 sockets
- ✅ **SocketProviders** - 注册与聚合
- ✅ **SocketQueryContextBuilder** - 从工具状态创建查询上下文

**这一层完成后，系统立刻获得的能力**：
- ✅ 从 SelectionTool / OutlineTool / PathTool 等工具输入生成 sockets
- ✅ 同时支持 WALL_SURFACE / EDGE_OUTER / FLOOR_SURFACE / WALL_OPENING（洞口）
- ✅ 可用于 AI 自动装配（Rank → Variant → Match → Patch）
- ✅ 也可用于 ComponentTool hover 高亮（显示可插位置）
- ✅ 可扩展：后面想加 ROOF_RIDGE / ROOF_SLOPE，只要新增 provider，不改旧逻辑

**这是 SocketProvider v1 的完整实现，稳定、好用、可扩展。**

---

**实现时间**: 2026-01-14  
**版本**: v1.0  
**状态**: ✅ 核心功能完成，已集成到 SocketProviders
