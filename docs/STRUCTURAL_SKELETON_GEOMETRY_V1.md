# StructuralSkeleton v1 几何字段定义

## 🎯 设计原则

在给字段之前，先明确 5 条硬原则（这会让后续所有设计"稳"）：

1. **只描述"连续几何"，不描述 block**
2. **XZ 是主维度，Y 只描述高度策略**
3. **所有元素必须能推导出：边、面、内外法向**
4. **必须能被可视化（debug overlay）**
5. **必须能一对一映射到 Skeleton**

## 📦 基础几何类型

### Vec2（2D 点/向量）
**位置：** `src/main/java/com/formacraft/common/geometry/Vec2.java`

基础 2D 向量，用于 XZ 平面。

**能力：**
- `distanceTo()`, `distanceSquaredTo()` - 距离计算
- `add()`, `subtract()`, `scale()` - 向量运算
- `length()`, `normalize()` - 长度和归一化
- `dot()`, `rotate90()` - 点积和旋转

### Vector2（2D 方向向量）
**位置：** `src/main/java/com/formacraft/common/geometry/Vector2.java`

与 Vec2 相同，但语义上强调"方向"而非"点"。用于表示法线、方向等。

### Line2D（2D 直线）
**位置：** `src/main/java/com/formacraft/common/geometry/Line2D.java`

用于表示轴线、对齐线等。

**能力：**
- `direction()` - 方向向量
- `length()` - 直线长度
- `midpoint()` - 中点
- `distanceToPoint()` - 点到直线距离

### Polyline2D（2D 折线）
**位置：** `src/main/java/com/formacraft/common/geometry/Polyline2D.java`

用于表示墙基线、路径等。

**能力：**
- `getPoints()` - 获取所有点
- `totalLength()` - 计算总长度
- `offset()` - 偏移折线（用于墙的厚度）
- `isLine()` - 是否是直线

**支持：**
- 折线墙
- 锯齿平面
- v1 仍然可以只用 2 点（直线）

### Polygon2D（2D 多边形）
**位置：** `src/main/java/com/formacraft/common/geometry/Polygon2D.java`

用于表示 FloorPlate 的 footprint、CourtyardVoid 的轮廓等。

**能力：**
- `area()` - 计算面积（鞋带公式）
- `centroid()` - 计算质心
- `isClockwise()` - 判断是否是顺时针
- `offset()` - 偏移多边形（向内或向外）
- `getBounds()` - 获取边界框

## 🧩 StructuralSkeleton 结构

### FloorPlate（地面板）

一切的"几何基底"。

```java
public static class FloorPlate {
    Polygon2D footprint;      // 闭合多边形（XZ 平面）
    double baseY;             // 地基参考高度
    double thickness;         // 厚度（结构厚度，不是最终方块）
    GroundingMode groundingMode; // 与 terrain 的贴合方式
}
```

**GroundingMode（地基贴合方式）：**
- `FLAT` - 强制水平
- `FOLLOW_TERRAIN` - 跟随地形
- `STEP_TERRACE` - 台阶化（未来）

👉 这是 Terrain 感知 → 建筑 的第一个连接点。

**注意：** floor plate ≠ 房间，它只是承载墙和体量的"地基"

### WallSegment（墙段）

核心中的核心，90% 的建筑"感觉"来自墙。

```java
public static class WallSegment {
    String id;                    // 唯一 ID（用于 graph / debug）
    WallType type;                // 墙体类型
    Polyline2D baseline;          // XZ 平面中的基线（有方向）
    double thickness;             // 墙厚（结构厚度）
    HeightProfile heightProfile;  // 墙体高度策略
    Vector2 normal;               // 墙体法向（指向 exterior 或 courtyard）
    List<String> zones;           // 关联的 zone（一个或两个）
}
```

**WallType（决定 Socket 行为）：**
- `EXTERNAL` - 外墙
- `INTERNAL` - 内墙
- `COURTYARD` - 庭院墙
- `BOUNDARY` - 用地边界（可选）

⚠️ 这个枚举直接决定：
- 哪些 SocketProfile 被启用
- 哪些构件允许出现

**HeightProfile（墙体高度的关键抽象）：**
```java
public class HeightProfile {
    double baseY;      // 墙底高度（通常 = floorPlate.baseY）
    double topY;       // 墙顶高度
    boolean variable;  // 是否允许变化（坡屋顶 / 台阶）
}
```

👉 v1 可以全部用 `variable = false`，但字段一定要留。

**为什么用 Polyline 而不是 Line：**
- 支持折线墙
- 支持锯齿平面
- v1 仍然可以只用 2 点（直线）

### CourtyardVoid（庭院空洞）

"负体量"的几何实体。

```java
public static class CourtyardVoid {
    Polygon2D footprint;          // 庭院轮廓
    boolean openToSky;            // 是否完全露天
    List<String> adjacentZones;   // 相邻 zone
}
```

**几何语义：**
- footprint 从 FloorPlate 中布尔减去
- 周边自动生成 `WallType.COURTYARD`

👉 这是回字形 / 中式院落 / 修道院的根。

### AxisConstraint（对齐约束）

非几何但强影响。

```java
public static class AxisConstraint {
    String id;                // 轴线 ID
    Line2D axis;              // 轴线（XZ）
    AxisRole role;            // 轴线等级
    List<String> zones;       // 影响的 zones
}
```

**AxisRole：**
- `PRIMARY` - 主轴线：尽量直线、尽量正交
- `SECONDARY` - 次轴线：可偏移

**v1 行为建议：**
| role      | 行为         |
| --------- | ---------- |
| PRIMARY   | 墙尽量对齐 / 对称 |
| SECONDARY | 允许偏移       |

## 🔄 StructuralSkeleton → Skeleton 的几何落点

| Structural    | Skeleton.geometry                               |
| ------------- | ----------------------------------------------- |
| WallSegment   | ExtrudedBox(baseline, thickness, heightProfile) |
| FloorPlate    | ExtrudedPolygon(footprint, thickness)           |
| CourtyardVoid | BooleanSubtract                                 |

## ✅ 为什么这套字段设计是"安全的"

- ✔ 不依赖 Minecraft block
- ✔ 不依赖风格
- ✔ 不依赖构件
- ✔ 能 debug / 可视化
- ✔ 能渐进升级（多层 / 坡屋顶 / 台基）

## 🚀 你现在可以立刻做的三件事

1. **把这些 class 写出来（纯数据，不写算法）** ✅ 已完成
2. **给每个结构写一个 debug renderer**（未来）
3. **用一个简单 PlanSkeleton 测试 → 生成 StructuralSkeleton**（需要更新转换器）
