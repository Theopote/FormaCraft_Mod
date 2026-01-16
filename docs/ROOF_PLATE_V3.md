# RoofPlate v3：歇山 / 庑殿 / 多脊系统

## 🎯 核心定义

**Roof v3 = 在 v2 的「轴线 + 脊线 + 坡面」模型上，引入"主脊—垂脊—戗脊—檐线"的层级系统。**

这正是歇山 / 庑殿 / 重檐的本质。

## 📋 中式屋顶的"脊系统语法"

中式屋顶不是"一个屋顶"，而是一个脊的系统。

### RidgeType（屋脊类型）

```java
public enum RidgeType {
    MAIN_RIDGE,        // 正脊
    HIP_RIDGE,         // 垂脊（屋角向下）
    DIAGONAL_RIDGE,    // 戗脊（歇山）
    SECONDARY_RIDGE    // 副脊（重檐、次脊）
}
```

⚠️ **注意：这是结构语义，不是装饰分类。**

### RoofForm v3 扩展

```java
public enum RoofForm {
    FLAT,
    GABLED,        // 硬山
    HIP,           // 庑殿
    XIESHAN,       // 歇山（v3 重点）
    MULTI_EAVE     // 重檐（v3.5）
}
```

## 🏗️ v3 结构总览

### 歇山顶（XIESHAN）结构

```
        ───────        ← 正脊
       /   |   \
      /    |    \
     /     |     \
    └──────┼──────┘
           ↓
        垂脊 + 戗脊
```

**结构拆解为：**
- 正脊（Main Ridge）
- 垂脊（Hip Ridge）：从正脊两端下落
- 戗脊（Diagonal Ridge）：歇山特有，连接垂脊与檐角
- 檐线（Eave Line）：外轮廓

## 🔧 v3 数据结构扩展（向后兼容）

### RidgeLine v3

**v2 字段（保留）：**
- `lineXZ` - 脊线（XZ 平面）
- `heightY` - 脊线高度

**v3 新增字段：**
- `line3D` - 脊线（3D）
- `type` - 脊线类型（RidgeType）

### RoofSlope v3

**v2 字段（保留）：**
- `area` - 坡面区域（XZ 平面投影）

**v3 新增字段：**
- `area3D` - 坡面区域（3D）
- `boundedBy` - 关联的脊线（坡面被哪些脊线包围）

## 🏛️ 庑殿顶（HIP）生成规则

### 判定条件

- `RoofForm == HIP`
- OR `footprint 接近矩形 & 无强主轴`

### 脊系统生成

1. **无长正脊**（或中心短 MAIN_RIDGE）
2. **从中心向四角生成 4 条 HIP_RIDGE**

```
      △
     / \
    △───△
     \ /
      △
```

### 坡面生成

- 每个外边 → 一个坡面
- 坡面向中心汇聚
- normal 指向外下

### 装配结果

- 脊兽放在：4 条 HIP_RIDGE
- 无戗脊

👉 **这是标准庑殿**

## 🏛️ 歇山顶（XIESHAN）生成规则（v3 核心）

这是最复杂、也是最有价值的一种。

### 判定条件

- `RoofForm == XIESHAN`
- OR `GABLED + HIP 混合`

### 脊系统生成步骤

**Step 1：生成正脊（MAIN）**
- 沿 Primary Axis
- 与 v2 相同

**Step 2：生成垂脊（HIP）**
- 从正脊两端向下
- 方向：沿屋顶坡面最陡方向

**Step 3：生成戗脊（DIAGONAL）**
- 戗脊 = 歇山的灵魂
- 规则：每个屋角从 HIP_RIDGE 中段斜向连接至 EAVE_CORNER

### 坡面划分

歇山屋顶的坡面分为：
- 正脊两侧的大坡面（2）
- 四个角的三角坡面（4）

每个坡面都：
- 被 RidgeLine 完全包围
- 有独立 normal

### 几何稳定性原则

- ✔ 所有 RidgeLine 必须首尾相接
- ✔ 不允许悬空脊
- ✔ 坡面必须完全覆盖 footprint
- ✔ Courtyard 已在 v1 被消除，不参与 v3

## 🔗 多脊系统 × 构件装配（v3 爆发点）

### Ridge Socket v3

```java
Socket {
    role = RIDGE_LINE
    ridgeType = MAIN_RIDGE | HIP_RIDGE | DIAGONAL_RIDGE
}
```

**装配策略（AI 极易理解）：**

| 脊类型 | 构件 |
|--------|------|
| MAIN_RIDGE | 主脊兽 / 正吻 |
| HIP_RIDGE | 垂兽 |
| DIAGONAL_RIDGE | 戗兽 |

👉 **中式屋顶立刻"活"了**

### 檐口 × 戗脊联动

- 戗脊终点 = 檐角
- 在该点自动放置：角吻、翘角构件

## 🎨 Debug Overlay v3

**新增层：**
- `ROOF_RIDGE_MAIN` - 正脊（粗红）
- `ROOF_RIDGE_HIP` - 垂脊（橙）
- `ROOF_RIDGE_DIAGONAL` - 戗脊（紫）
- `ROOF_SLOPE_TRI` - 三角坡面（半透明分色）

**可视效果：**
- 正脊：粗红
- 垂脊：橙
- 戗脊：紫
- 坡面：半透明分色

**你会第一次真正"看到"歇山结构。**

## ⚠️ v3 常见错误（一定要避）

- ❌ 把戗脊当装饰
- ❌ 在几何阶段处理斗拱
- ❌ 用屋顶装饰反推结构
- ❌ 忽略 Axis

✔ **脊是结构语言，装饰永远是构件层**

## ✅ 你现在已经站在什么高度？

做到这里，你已经可以：

- ✅ **自动生成 歇山 / 庑殿**
- ✅ **自动放置 脊兽 / 戗兽 / 垂兽**
- ✅ **支持 中式主殿 / 配殿 / 回廊**
- ✅ **用 AI + 规则表达 传统建筑语法**

**这一步，99% 的生成建筑系统做不到。**

## 📚 相关文档

- `docs/ROOF_PLATE_V2.md` - RoofPlate v2 规则
- `docs/ROOF_COMPONENT_ASSEMBLY_V2.md` - 装配系统
- `docs/FLOOR_COURTYARD_BOOLEAN_V1.md` - Boolean 几何规则
