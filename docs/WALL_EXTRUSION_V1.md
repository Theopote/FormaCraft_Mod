# WallSegment → 3D 几何 Extrusion (v1)

## 🎯 核心定义

**Wall extrusion = 在 XZ 平面上对 baseline 做"厚度偏移"，再在 Y 方向拉伸到指定高度**

## 📋 输入与输出

### 输入（来自 WallSegment）
```java
WallSegment {
    Polyline2D baseline;     // XZ 折线（至少 2 点）
    double thickness;        // 墙厚
    HeightProfile height;    // baseY, topY
    Vector2 normal;          // 指向 exterior / courtyard
}
```

### 输出（供 Skeleton 使用）
```java
ExtrudedSolid {
    List<Vec3> vertices;     // 3D 顶点
    List<Face> faces;        // 面（多边形，未三角化）
}
```

⚠️ **注意**：v1 不需要三角化，只要能表示面即可（调试 + Skeleton 都够）

## 🔧 算法步骤

### 最小可用算法（直线墙段）

#### 输入：一条直线
```
P0 -------- P1
```

#### Step 1：计算单位法向
```java
Vector2 n = wall.normal;   // 已由 PlanSkeleton 提供
```

⚠️ **不要自己猜 normal**，PlanSkeleton 已经帮你算好了"朝外方向"

#### Step 2：计算偏移线（墙厚）

假设 `thickness = t`

```
outerLine = baseline + n * (t / 2)
innerLine = baseline - n * (t / 2)
```

得到 4 个 2D 点：
- `A = P0 + n * (t/2)` - 外侧起点
- `B = P1 + n * (t/2)` - 外侧终点
- `C = P1 - n * (t/2)` - 内侧终点
- `D = P0 - n * (t/2)` - 内侧起点

#### Step 3：拉伸到 3D（高度）

```java
double y0 = height.baseY;
double y1 = height.topY;
```

生成 8 个 3D 点：
- `A0 (A.x, y0, A.z)` - 外侧起点底部
- `B0 (B.x, y0, B.z)` - 外侧终点底部
- `C0 (C.x, y0, C.z)` - 内侧终点底部
- `D0 (D.x, y0, D.z)` - 内侧起点底部
- `A1 (A.x, y1, A.z)` - 外侧起点顶部
- `B1 (B.x, y1, B.z)` - 外侧终点顶部
- `C1 (C.x, y1, C.z)` - 内侧终点顶部
- `D1 (D.x, y1, D.z)` - 内侧起点顶部

#### Step 4：构造面（6 个面）

1. **外立面**: A0 → B0 → B1 → A1
2. **内立面**: D0 → C0 → C1 → D1
3. **顶面**: A1 → B1 → C1 → D1
4. **底面**: D0 → C0 → B0 → A0
5. **端面1**: A0 → D0 → D1 → A1
6. **端面2**: B0 → C0 → C1 → B1

👉 这一步已经能完整支撑 v1 的墙 Skeleton

## 🔄 Polyline（折线）WallSegment

### 输入：折线
```
P0 —— P1 —— P2 —— P3
```

### 总体策略

**正确做法**：逐段 extrusion + 节点拼接（bevel join）

### v1 推荐方案（稳定 & 简单）

👉 **使用 Bevel Join（削角）**

每一段独立生成一个矩形体，相邻段：
- 允许轻微重叠
- 后续交给 Boolean Union
- 或直接接受（Minecraft 方块世界完全 OK）

⚠️ **不要 v1 就做 miter / round join**，那是 v2/v3 的事

### 实现伪代码
```java
List<ExtrudedSolid> solids = new ArrayList<>();

for (Segment2D seg : baseline.segments()) {
    ExtrudedSolid solid = extrudeSingleSegment(
        seg.start,
        seg.end,
        wall.thickness,
        wall.heightProfile,
        wall.normal
    );
    solids.add(solid);
}

// v1：直接返回所有 solid
return solids;
```

👉 Skeleton 层可以：
- 接受多个 solid
- 或合并为一个 compound geometry

## 📊 特殊情况处理

### 外墙 vs 内墙

| 类型        | normal | 影响     |
| --------- | ------ | ------ |
| EXTERNAL  | 指向室外   | 外立面正确  |
| INTERNAL  | 任意一致方向 | 两侧都可开门 |
| COURTYARD | 指向庭院   | 内向立面   |

**normal 错 → 全部错**

👉 所以 normal 必须来自 PlanSkeleton

### 高度变化（v1 可锁死）

```java
heightProfile.variable = false
```

意味着：
- 整段墙统一高度
- 不做坡顶、不做台阶

字段已经留好，但算法先别碰

### 底面是否需要？

- 对 Skeleton / Socket：底面不是必须
- 但对 debug / boolean：建议保留

## 🔗 与 Skeleton 系统的连接点

生成的 ExtrudedSolid 会被包进 Skeleton：

```java
Skeleton wallSkeleton = Skeleton.builder()
    .kind("WALL")
    .geometry(extrudedSolid)
    .context(
        wall.type == EXTERNAL
            ? SkeletonContext.EXTERIOR
            : SkeletonContext.INTERIOR
    )
    .tags(Set.of("wall", wall.type.name()))
    .build();
```

👉 接下来现有的：
- SocketProvider
- SocketMatcher
- Component placement

**全部无需修改**

## 🐛 Debug 可视化（强烈建议）

至少支持：
- baseline（2D 线）
- outer / inner offset 线
- extruded box 线框

这会帮你**立刻发现 90% 的 bug**

## ⚠️ v1 常见坑（提前避坑）

❌ **不要**：
- 在 extrusion 阶段考虑方块对齐
- 在 v1 做复杂 join（miter / round）
- 从几何反推 normal
- 把 courtyard 当普通外墙

✔ **应该**：
- normal 来自 PlanSkeleton
- 多段墙可重叠
- 几何先"丑但稳"

## 🔄 完整链路

```
LLM
  → PlanProgram
  → PlanSkeleton
  → StructuralSkeleton
  → WallSegment Extrusion
  → Skeleton
  → Socket
  → Component
```

这已经是一条真正的"AI 建筑编译管线"。
