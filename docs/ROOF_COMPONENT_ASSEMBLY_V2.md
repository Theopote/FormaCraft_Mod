# 屋顶构件自动装配（Roof × Socket × Component）v2

## 🎯 核心定义

**屋顶构件自动装配 = 在 RoofSkeleton 提供的"语义 Socket"上，按构件 archetype + 文化风格 + 几何条件进行自动匹配与铺设。**

## 📋 屋顶构件生态的三大类

你可以把屋顶构件分成 3 个"生态位"，每一类都有完全不同的放置逻辑：

1. **RIDGE（脊）** - 屋脊线
2. **EAVE（檐）** - 檐口线
3. **SLOPE_SURFACE（坡面）** - 坡面表面

这三类，正好对应你在 v2 Roof 里已经有的结构元素。

## 🔌 RoofSkeleton 必须暴露的 Socket

### 1. Ridge Socket（屋脊）

**语义来源：** `RoofPlate.ridges` → `RidgeSkeleton`

**Socket 定义：**
```java
Socket {
    role = RIDGE_LINE
    context = ROOF
    shape = LINE
    direction = ALONG_LINE
}
```

**用途：**
- 屋脊兽
- 屋脊瓦
- 脊刹 / 端头

👉 **这是中式 / 日式 / 教堂风格的灵魂**

### 2. Eave Socket（檐口）

**语义来源：** `RoofSlope ∩ 外墙边界`

也就是说：**坡面 + 外墙的交线 = 檐口**

**Socket 定义：**
```java
Socket {
    role = EAVE_LINE
    context = ROOF_EDGE
    shape = LINE
    direction = ALONG_EDGE
}
```

**用途：**
- 瓦檐
- 飞檐
- 檐口装饰
- 雨槽（西式）

### 3. Slope Surface Socket（坡面铺设）

**语义来源：** `RoofSlope.area`

**Socket 定义：**
```java
Socket {
    role = ROOF_SURFACE
    context = ROOF
    shape = SURFACE
    normal = slope.normal
}
```

**用途：**
- 屋瓦
- 屋顶板
- 天窗（v2.5）

## 🏗️ 屋顶构件 Archetype（AI 选构件的关键）

你现在的 Component 系统已经有 archetype 概念，这里只需要补齐屋顶部分。

### RoofArchetype 枚举

```java
public enum RoofArchetype {
    ROOF_TILE,           // 屋瓦（坡面铺设）
    RIDGE_DECORATION,    // 屋脊装饰（脊线）
    EAVE_DECORATION,     // 檐口装饰（檐口）
    EAVE_STRUCTURAL,     // 檐口结构（檐口）
    ROOF_ORNAMENT        // 屋顶装饰（通用）
}
```

### 示例

| 构件 | Archetype |
|------|-----------|
| 小青瓦 | ROOF_TILE |
| 屋脊兽 | RIDGE_DECORATION |
| 滴水瓦 | EAVE_DECORATION |
| 飞檐斗拱 | EAVE_STRUCTURAL |

👉 **AI 就是靠这个做"对位思考"的**

## 🔧 自动装配核心算法

### 总流程（与你现有 AssemblyPlanner 高度一致）

```
RoofSkeleton
  ↓
Extract RoofSockets
  ↓
For each Socket:
   Query ComponentLibrary
   Rank Components
   Place / Repeat / Align
```

### Ridge 构件装配（线性重复）

**输入：**
- Socket: `RIDGE_LINE`
- Length: L
- Direction: D

**算法：**
1. 从 ComponentLibrary 选：
   - `archetype = RIDGE_DECORATION`
   - `culturalStyle = building.style`
2. 选一个"可重复"的 ridge component
3. 沿 `socket.line`：
   - repeat
   - align to ridge direction
4. 在端点：放置 `ridge_end` / `ridge_beast`（如果有）

```java
repeatAlongLine(socket, component, spacing);
```

👉 **这一步和你已有的"城墙 / 栏杆连续放置"是同构的**

### Eave 构件装配（边界重复）

**输入：**
- Socket: `EAVE_LINE`
- Normal: outward/downward

**算法：**
1. `archetype = EAVE_DECORATION / EAVE_STRUCTURAL`
2. 优先：连续构件
3. 次要：单元构件 + repeat

```java
placeAlongEdge(socket, component);
```

### Roof Tile 装配（坡面填充，最容易翻车）

**关键原则（非常重要）：**

**屋瓦不是"铺满面"，而是"沿坡向、按行列铺设"。**

**算法（v2 简化版）：**

1. 从 `slope.normal` 推导：
   - 主铺设方向（down-slope）
2. 在 `slope.area` 上生成：
   - 平行条带（strip）
3. 每条 strip：
   - repeat `ROOF_TILE` component

```java
fillSurfaceWithStrips(
    slope.area,
    tileComponent,
    direction = downSlope
);
```

⚠️ **v2 不做裁剪到完美边界**（稍微溢出 → 交给 block-level clipping）

## 🧠 AI 如何"选对屋顶构件"（关键 Prompt 思路）

你未来给 LLM 的 System Prompt 可以是：

```
"屋顶构件放置遵循以下语义：

- 屋脊只放 ridge 类构件
- 檐口只放 eave 类构件
- 坡面只放 tile 类构件
- 构件必须与文化风格一致
- 构件必须可重复"
```

这会让 AI 几乎不犯错。

## 🎨 Debug Overlay（屋顶装配必须可视化）

**新增层：**
- `ROOF_SOCKET_RIDGE` - 脊线 Socket
- `ROOF_SOCKET_EAVE` - 檐口 Socket
- `ROOF_SOCKET_SURFACE` - 坡面 Socket

**怎么画：**
- Ridge Socket：粗红线
- Eave Socket：黄色线
- Surface Socket：半透明面

**你可以一眼看出：**
- ✅ 屋脊有没有断
- ✅ 檐口有没有反
- ✅ 瓦是不是顺坡

## 📊 v1 / v2 装配能力对比

| 能力 | v1 | v2 |
|------|-----|-----|
| 屋顶封顶 | ✅ | ✅ |
| 屋脊装饰 | ❌ | ✅ |
| 檐口系统 | ❌ | ✅ |
| 瓦片铺设 | ❌ | ✅ |
| 风格化屋顶 | ❌ | ✅ |

## 🏆 为什么这一步是 Formacraft 的"分水岭"

因为从这一刻开始：

**屋顶不再是"一个几何体"**

而是：

- ✅ 有语义
- ✅ 有生态位
- ✅ 有构件语法

**AI 开始真正"理解屋顶"。**

## 📚 相关文档

- `docs/ROOF_PLATE_V2.md` - RoofPlate v2 规则
- `docs/ROOF_PLATE_V1.md` - RoofPlate v1 规则
- `docs/FLOOR_COURTYARD_BOOLEAN_V1.md` - Boolean 几何规则
