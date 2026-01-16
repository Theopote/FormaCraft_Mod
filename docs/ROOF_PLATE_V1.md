# RoofPlate v1 规则体系

## 🎯 核心定义

**RoofPlate = 对 FloorPlate 的"有效实心区域"做封顶，而不是对原始平面做封顶。**

这句话，直接解决 Courtyard 问题。

## 📋 设计原则

1. **不在 v1 引入复杂造型学**：只支持 FLAT / GENERIC
2. **不依赖构件**：RoofPlate 是纯几何结构
3. **不依赖风格**：v1 不处理坡度、檐口、脊线
4. **与 Debug Overlay / Skeleton / Socket 完全兼容**

## 🔄 在整体管线中的位置

```
PlanSkeleton
  ↓
StructuralSkeleton
  → FloorPlate (Solid)
  → CourtyardVoid (Void)
    ↓ Boolean (2D)
EffectiveFootprint
    ↓
RoofPlate
    ↓
Roof Skeleton
```

👉 **屋顶永远来自 Boolean 之后的结果，而不是之前。**

## 🧩 RoofPlate 数据结构

```java
public static class RoofPlate {
    /** 封顶区域（XZ 平面，多 polygon） */
    public final List<Polygon2D> roofFootprints;

    /** 屋顶基准高度（通常 = 墙顶） */
    public final double baseY;

    /** 屋顶厚度（结构厚度） */
    public final double thickness;

    /** 屋顶类型（v1 非几何，仅语义） */
    public final RoofType type;
}
```

**RoofType（v1 只做"占位语义"）：**
- `FLAT` - 平屋顶
- `GENERIC` - 占位（以后细化）

⚠️ v1 不要在这里处理坡度、檐口、脊线，这些是 v2 的事。

## 🔧 Courtyard 如何"绕开"（核心规则）

### 回顾 Boolean 结果

在 FloorPlate / Courtyard 那一步，你已经得到：

- **EffectiveFootprint**：
  - 外边界 polygon
  - 内孔（courtyard）polygon(s)

👉 **这一步已经天然解决"屋顶不覆盖庭院"的问题。**

### RoofPlate 生成规则

```java
RoofPlate.roofFootprints = EffectiveFootprint.outerPolygons
```

**非常重要的三点：**

1. **不要包含 hole**
2. **不要重新减 courtyard**
3. **roofFootprints 可以是多个 polygon**

这三点一起，直接解决：
- ✅ 回字形
- ✅ 多中庭
- ✅ 不规则庭院

### 视觉理解（ASCII）

```
原始平面:
┌───────────────┐
│               │
│   ┌───────┐   │
│   │Court  │   │
│   └───────┘   │
│               │
└───────────────┘

EffectiveFootprint:
┌───────────────┐
│               │
│   █████████   │ ← Hole removed
│               │
└───────────────┘

RoofPlate:
┌───────────────┐   ← 只封这里
```

## 🏗️ 多体量（分翼 / 组合）的 Roof 规则

### 多体量的来源（不是 Roof 决定的）

多体量并不是 Roof 的职责，而来自：
- zones
- adjacency
- massing strategy
- courtyard subtraction

所以 Roof 只"顺着体量走"。

### v1 的正确策略：一体 Roof + 多 Polygon

**不要在 v1 做的事：**
- ❌ 每个 zone 一个屋顶
- ❌ 高低错动
- ❌ 跨 zone 自动分脊

**v1 应该做的事：**
```java
RoofPlate.roofFootprints = EffectiveFootprint 的所有外 polygon
```

也就是说：
- L 形 → 一个 L 形屋顶
- 回字形 → 一个"中空"屋顶（实际上是一个 polygon with hole，但我们已展开为 outer polygon）
- 双翼 → 可能是一个多边形，也可能被 Boolean 拆成两个 polygon

### 什么时候会出现多个 roofFootprints？

只有一种情况：**EffectiveFootprint 在 Boolean 后被拆成多个不相连 polygon**

例如：
```
   ██████      ██████
   ██████      ██████
```

👉 这在以下情况会出现：
- 用户画了两个不连通体量
- AI 主动拆解为两栋

这时：`roofFootprints.size() == 2`，每个都独立 extrude。

## 🔨 RoofPlate → 几何 Extrusion

### Extrusion 规则

```java
ExtrudedSolid roofSolid = extrudePolygon(
    roofFootprint,
    baseY,
    baseY + thickness
);
```

**参数建议（v1）：**

| 参数 | 建议 |
|------|------|
| baseY | max(wall.topY) |
| thickness | 0.5 ~ 1.0 |
| type | FLAT |

### Roof 不应该做的事（v1）

- ❌ 不切脊
- ❌ 不对齐墙厚
- ❌ 不考虑挑檐

👉 这些都是构件 / 风格 / v2 的职责。

## 🔗 Roof → Skeleton 的映射

```java
Skeleton roofSkeleton = Skeleton.builder()
    .kind("ROOF")
    .geometry(roofSolid)
    .context(SkeletonContext.EXTERIOR)
    .tags(Set.of("roof", roofPlate.type.name()))
    .build();
```

### Roof 的 Socket 策略（现在就能用）

**v1 推荐：**
只生成：
- `ROOF_SURFACE`
- `ROOF_EDGE`

**以后可以自然支持：**
- 天窗
- 脊饰
- 檐口构件

## 🎨 Debug Overlay 中 Roof 的呈现

**新增 DebugLayer：**
- `STRUCT_ROOF`

**怎么画：**
- 半透明灰色
- 或线框

```java
drawExtrudedSolidWireframe(roofSolid, LIGHT_GRAY);
```

**你会立刻看到：**
- ✅ 屋顶有没有盖住庭院 ❌ / ✅
- ✅ 多体量是否各自封顶
- ✅ 是否和墙顶对齐

## ⚠️ v1 常见错误（提前避坑）

- ❌ 用原始 FloorPlate 生成 Roof
- ❌ 重新对 Courtyard 做 Boolean
- ❌ 按 zone 分屋顶
- ❌ 在 v1 处理坡屋顶

## ✅ 你现在已经具备的建筑能力

做到这一步，你已经完整覆盖：

- ✅ **非矩形平面**
- ✅ **回字形 / 中庭**
- ✅ **多体量组合**
- ✅ **AI + 用户协作**
- ✅ **可解释、可调试生成流程**

这已经是一个"建筑生成系统"，而不是模组小工具了。

## 📚 相关文档

- `docs/FLOOR_COURTYARD_BOOLEAN_V1.md` - Boolean 几何规则
- `docs/DEBUG_OVERLAY_V1.md` - Debug Overlay 系统
- `docs/STRUCTURAL_SKELETON_GEOMETRY_V1.md` - StructuralSkeleton 几何字段
