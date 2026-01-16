# Debug Overlay 可视化系统 v1

## 🎯 核心目标

提供分层调试和可视化工具，用于调试 PlanSkeleton → StructuralSkeleton → Skeleton 的转换过程。

## 📋 设计原则

1. **分层渲染**：PlanSkeleton / StructuralSkeleton / Skeleton 三层独立
2. **颜色语义固定**：避免混淆，每个语义有固定颜色
3. **图层开关**：支持独立开关每个图层
4. **统一接口**：DebugOverlayRenderer 接口，便于扩展

## 🎨 颜色语义

| 语义 | 颜色 | 说明 |
|------|------|------|
| Plan Outline | 🟦 蓝色 | AI / 用户给的平面轮廓 |
| Zone 区域 | 🟩 半透明绿 | 功能块 |
| 主轴 Axis | 🟥 红线 | PRIMARY |
| 次轴 Axis | 🟧 橙线 | SECONDARY |
| 外墙 Baseline | 🟨 黄线 | 外边界 |
| 庭院 Baseline | 🟪 紫线 | 内孔边界 |
| 内墙 Baseline | 灰线 | 共享墙 |
| Wall Solid | 🟫 半透明棕 | 墙体体量 |
| Courtyard Void | ⬛ 黑色网格 | 空洞 |
| Skeleton Bounds | 🟦 线框 | Skeleton 边界 |
| Socket | 🟠 点 / 小盒 | Socket 点 |

⚠️ **永远不要复用颜色表示不同语义**

## 🏗️ 架构

### 1. DebugLayer（调试图层枚举）

定义所有可调试的可视化图层，按语义分组：

- **PlanSkeleton 层**：`PLAN_OUTLINE`, `PLAN_ZONES`, `PLAN_AXIS_PRIMARY`, `PLAN_AXIS_SECONDARY`
- **StructuralSkeleton 层**：`STRUCT_WALL_BASELINE_*`, `STRUCT_WALL_SOLID`, `STRUCT_COURTYARD_VOID`
- **Skeleton 层**：`SKELETON_BOUNDS`, `SKELETON_SOCKETS`

### 2. DebugContext（调试上下文）

存储渲染所需的所有上下文信息：

```java
public class DebugContext {
    public final PlanSkeleton planSkeleton;
    public final StructuralSkeleton structuralSkeleton;
    public final CompiledSkeleton compiledSkeleton;
    public final List<ExecutableSkeletonPlan> executablePlans;
    public final double viewY;  // 当前查看的 Y 高度
    public final double scale;  // 渲染比例
}
```

### 3. DebugOverlayRenderer（统一接口）

```java
public interface DebugOverlayRenderer {
    void render(DebugContext ctx, Set<DebugLayer> enabledLayers);
    boolean supportsLayer(DebugLayer layer);
}
```

### 4. 分层 Renderer

- **PlanDebugRenderer**：渲染 PlanSkeleton 层（2D 语义）
- **StructuralDebugRenderer**：渲染 StructuralSkeleton 层（3D 结构）
- **SkeletonDebugRenderer**：渲染 Skeleton 层（可执行骨架）

## 📊 每一层 Overlay 的"画什么 + 怎么画"

### PlanSkeleton Overlay（2D 语义层）

#### Plan Outline
- **画什么**：FloorPlate.footprint、Courtyard.footprint
- **怎么画**：Y = baseY + 0.05（防 z-fight），线框 polyline，不填充，蓝色

#### Zone 区域
- **画什么**：每个 zone 的 polygon（如果有）
- **怎么画**：半透明填充，不同 zone 可随机浅色，绿色

#### Axis
- **画什么**：AxisConstraint.axis
- **怎么画**：无限延伸或限定长度，红/橙，可加文字标签

### StructuralSkeleton Overlay（最关键的一层）

**90% 的几何 bug 在这一层暴露**

#### WallSegment Baseline
- **画什么**：WallSegment.baseline
- **怎么画**：不拉高，XZ 折线，颜色由 type 决定
  - EXTERNAL → 黄色
  - COURTYARD → 紫色
  - INTERNAL → 灰色
- **重要性**：如果这里就不对，后面一律不用看

#### WallSegment Solid（Extrusion 结果）
- **画什么**：ExtrudedSolid（墙体）
- **怎么画**：半透明盒子，或仅画线框（推荐）
- **能看到**：
  - 墙厚是否对
  - 高度是否统一
  - 转角是否爆炸

#### Courtyard Void（负空间）
- **画什么**：CourtyardVoid.footprint、Void 体量
- **怎么画**：地面画深色网格，可加 downward arrow
- **重要性**：这是判断 Boolean 减法是否成功的关键

### Skeleton Overlay（与已有系统接轨）

#### Skeleton Bounds
- **画什么**：每个 Skeleton 的 AABB / OBB
- **怎么画**：线框盒，Skeleton.kind 不同颜色

#### Socket Overlay
- **画什么**：Socket 位置、方向、类型
- **怎么画**：小立方体 + 箭头，颜色按 socket.role
- **验证**：
  - 窗是不是在外墙
  - 门有没有朝里
  - 阳台有没有飞到庭院

## 🔍 调试流程（推荐）

**推荐 Debug 顺序**：

1. 只开 `PLAN_OUTLINE`
2. 加 `STRUCT_WALL_BASELINE`
3. 加 `STRUCT_COURTYARD`
4. 加 `STRUCT_WALL_SOLID`
5. 最后才看 `SKELETON` / `SOCKET`

👉 **不要跳步骤，否则你会误判**

## ⚠️ v1 实现状态

**当前状态**：
- ✅ 接口和架构已定义
- ✅ 颜色语义已固定
- ⚠️ 实际渲染逻辑待客户端实现（标记为 TODO）

**为什么分离**：
- 服务端定义接口和数据结构
- 客户端实现实际渲染（使用 Minecraft 渲染系统）
- 便于测试和维护

## 📚 相关文档

- `docs/FLOOR_COURTYARD_BOOLEAN_V1.md` - Boolean 几何规则
- `docs/STRUCTURAL_SKELETON_GEOMETRY_V1.md` - StructuralSkeleton 几何字段
- `docs/WALL_EXTRUSION_V1.md` - WallExtrusion 算法

## 🔮 未来扩展

1. **客户端渲染实现**：集成到 Minecraft 渲染系统
2. **UI 交互**：ToolPanel 中的 Debug 面板，快捷键支持
3. **性能优化**：大量几何体的渲染优化
4. **导出功能**：导出调试图像/视频
