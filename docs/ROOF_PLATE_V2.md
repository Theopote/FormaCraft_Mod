# RoofPlate v2 规则体系

## 🎯 核心定义

**v2 Roof = 在 v1 RoofPlate 的基础上，引入「轴线 + 体量关系」驱动的脊线与坡向规则。**

## 📋 v1 vs v2 的本质差异

| 层级 | v1 | v2 |
|------|-----|-----|
| 输入 | EffectiveFootprint | EffectiveFootprint + Axes |
| 屋顶类型 | FLAT | GABLED / HIP / AXIAL |
| 几何 | 单一 extrusion | 脊线 + 坡面 |
| Courtyard | 自动绕开 | 自动绕开 |
| 多体量 | 一个 RoofPlate | 多 RoofPlate + 主从关系 |
| Socket | ROOF_SURFACE / EDGE | + RIDGE / EAVE |

👉 **v2 的新增信息几乎全部来自你已经有的 AxisConstraint**

这是这套设计最"优雅"的地方。

## 🧩 v2 RoofPlate 数据结构（向后兼容）

### 扩展字段

```java
public static class RoofPlate {
    // v1 字段（保持不变）
    public final List<Polygon2D> roofFootprints;
    public final double baseY;
    public final double thickness;
    public final RoofType type;

    // v2 新增
    public final RoofForm form;           // 屋顶形式
    public final List<RidgeLine> ridges;  // 脊线列表
    public final List<RoofSlope> slopes;  // 坡面列表
}
```

### RoofForm（屋顶"结构语义"）

```java
public enum RoofForm {
    FLAT,           // 平屋顶（v1）
    GABLED,         // 人字（两坡）
    HIP,            // 四坡
    AXIAL_GABLED,   // 沿主轴的人字（中式 / 教堂）
    AXIAL_HIP       // 沿主轴的四坡
}
```

⚠️ **RoofForm 是"策略选择"，不是几何**。真正的几何在 Ridge / Slope 里。

### RidgeLine（屋脊线）

```java
public class RidgeLine {
    public final Line2D lineXZ;     // 脊线（XZ 平面）
    public final double heightY;    // 脊线高度（Y 坐标）
    public final RidgeRole role;    // 脊线角色（MAIN / SECONDARY）
}
```

### RoofSlope（屋顶坡面）

```java
public class RoofSlope {
    public final Polygon2D area;    // 坡面区域（XZ 平面投影）
    public final Vec3 normal;       // 坡面法向（3D）
    public final double pitch;      // 坡度角（度）
}
```

## 🔧 核心驱动力：Axis → Ridge

### 规则 1：Primary Axis → 主脊线（Main Ridge）

```java
if (axis.role == PRIMARY) {
    generateMainRidge(axis);
}
```

**主脊线生成规则（v2）：**
1. 投影 Primary Axis 到 roofFootprint
2. 取 footprint 中最长连续覆盖段
3. 脊线高度：`ridgeY = baseY + wallHeight + ridgeLift`

**ridgeLift 策略：**
- v2：常数（如 2.0）
- v3：style 决定

### 没有 Axis 怎么办？

**fallback 策略（非常重要）：**

```java
if (no axis) {
    if (footprint elongated) {
        generate ridge along long axis
    } else {
        fallback to HIP
    }
}
```

👉 **这样 AI 忘记给 axis，也不会翻车。**

## 🏗️ Slope（坡面）生成规则（v2 精华）

### 从 Ridge 到 Slope 的几何规则

**以 GABLED 为例：**
```
      /\
     /  \
```

**步骤：**
1. 用 RidgeLine 将 footprint 一分为二
2. 对每一侧：
   - 生成 slope polygon
   - `normal = 从 ridge 向外 + 上`
   - `pitch = 默认值（如 30°）`

```java
Vector3 normal = normalize(
    horizontalNormal * cos(pitch) + UP * sin(pitch)
);
```

### HIP Roof（四坡）

```
    /\ 
   /  \
  /____\
```

**规则：**
- 无明显主轴，或 footprint 接近正方
- ridge 较短（甚至退化为点）
- slope 数量 ≥ 4
- 每条外边界 → 一个坡面

👉 **这对 Courtyard 完全透明**（因为 footprint 已经被 Boolean 处理过）

## 🔗 多体量 Roof v2

### 体量来源

多体量来自：
- Boolean 后多个 roofFootprints
- zone 明确拆分

### v2 的主从 Roof 策略（非常建筑学）

| 屋顶 | 行为 |
|------|------|
| 主（largest footprint） | 决定主脊 |
| 次 | 脊线对齐主屋顶高度 |
| 次 | 可降低 ridgeLift |

👉 **这会自然产生：翼楼、配殿、回廊屋顶**

### 多 RoofPlate 的组织方式

```java
List<RoofPlate> roofs = new ArrayList<>();

for (Polygon2D fp : roofFootprints) {
    roofs.add(generateRoofPlate(fp, context));
}
```

每个 RoofPlate 都：
- 自己算 ridge
- 自己算 slope
- 但可读取：global axis、main roof ridge height

## 📊 v2 Roof → Skeleton 映射

### Slope → Skeleton

```java
Skeleton slopeSkeleton = Skeleton.builder()
    .kind("ROOF_SLOPE")
    .geometry(extrudeSlope(slope))
    .context(SkeletonContext.EXTERIOR)
    .tags(Set.of("roof", "slope"))
    .build();
```

### Ridge → Skeleton

```java
Skeleton ridgeSkeleton = Skeleton.builder()
    .kind("ROOF_RIDGE")
    .geometry(extrudeRidge(ridge))
    .context(SkeletonContext.EXTERIOR)
    .tags(Set.of("roof", "ridge"))
    .build();
```

👉 **这一步让你第一次可以：**
- 自动放屋脊饰
- 自动放瓦当
- 自动生成檐口系统

## 🎨 Debug Overlay（v2 必须加的）

**新增层：**
- `STRUCT_ROOF_RIDGE` - 脊线
- `STRUCT_ROOF_SLOPE` - 坡面

**怎么画：**
- Ridge：粗红线（XZ）+ 高度标注
- Slope：半透明斜面

**你会非常直观地看到：**
- ✅ 脊线有没有跑偏
- ✅ 坡面是否对称
- ✅ Courtyard 是否完全敞开

## ⚠️ v2 常见坑（请你一定避）

- ❌ 在 v2 做斗拱 / 飞檐
- ❌ 从 Wall 反推 Roof
- ❌ 忽略 Axis
- ❌ 屋顶直接 block 化

✔ **屋顶是 结构语义 → 构件装配的中间层**

## ✅ 你现在的系统，已经到什么水平？

说一句实话：你现在这套 **Plan → Roof v2 → Socket → Component** 已经在「生成式建筑系统」里是非常罕见的成熟度。

你已经可以自然支持：

- ✅ **中式歇山**（v3 只差构件）
- ✅ **教堂人字顶**
- ✅ **回字形院落**
- ✅ **主殿 + 配殿体系**
- ✅ **AI + 人类协作建筑**

## 📚 相关文档

- `docs/ROOF_PLATE_V1.md` - RoofPlate v1 规则
- `docs/FLOOR_COURTYARD_BOOLEAN_V1.md` - Boolean 几何规则
- `docs/DEBUG_OVERLAY_V1.md` - Debug Overlay 系统
