# 屋顶构件自动装配系统完整文档

## 🎯 核心定义

**屋顶构件自动装配 = 在 RoofSkeleton 提供的"语义 Socket"上，按构件 archetype + 文化风格 + 几何条件进行自动匹配与铺设。**

## 📋 系统架构

### 三大生态位

1. **RIDGE（脊）** - 屋脊线
2. **EAVE（檐）** - 檐口线  
3. **SLOPE_SURFACE（坡面）** - 坡面表面

### 完整链路

```
RoofPlate (v2)
  ↓
RoofSocketGenerator.generateRoofSockets()
  ↓
Socket 列表 (RIDGE_LINE / EAVE_LINE / ROOF_SURFACE)
  ↓
ComponentLibrary.queryByArchetype(RoofArchetype)
  ↓
AssemblyPlanner.matchAndPlace()
  ↓
Component Instances
```

## 🔌 Socket 生成规则

### 1. RIDGE_LINE Socket

**来源：** `RoofPlate.ridges`

**SocketType：** `RIDGE_LINE`

**用途：**
- 屋脊兽（RIDGE_DECORATION）
- 屋脊瓦（RIDGE_DECORATION）
- 脊刹 / 端头（RIDGE_DECORATION）

### 2. EAVE_LINE Socket

**来源：** `RoofSlope ∩ 外墙边界`

**SocketType：** `EAVE_LINE`

**用途：**
- 瓦檐（EAVE_DECORATION）
- 飞檐（EAVE_STRUCTURAL）
- 檐口装饰（EAVE_DECORATION）
- 雨槽（EAVE_DECORATION，西式）

### 3. ROOF_SURFACE Socket

**来源：** `RoofSlope.area`

**SocketType：** `ROOF_SURFACE`

**用途：**
- 屋瓦铺设（ROOF_TILE）
- 屋顶板（ROOF_TILE）
- 天窗（ROOF_ORNAMENT，v2.5）

## 🏗️ 构件 Archetype

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

### 映射关系

| 构件 | Archetype | Socket |
|------|-----------|--------|
| 小青瓦 | ROOF_TILE | ROOF_SURFACE |
| 屋脊兽 | RIDGE_DECORATION | RIDGE_LINE |
| 滴水瓦 | EAVE_DECORATION | EAVE_LINE |
| 飞檐斗拱 | EAVE_STRUCTURAL | EAVE_LINE |

## 🔧 自动装配算法

### Ridge 构件装配（线性重复）

**流程：**
1. 从 ComponentLibrary 查询 `RIDGE_DECORATION`
2. 筛选匹配文化风格的构件
3. 沿 `RIDGE_LINE` Socket 重复放置
4. 在端点放置特殊构件（ridge_end / ridge_beast）

```java
repeatAlongLine(socket, component, spacing);
```

👉 **与城墙 / 栏杆连续放置同构**

### Eave 构件装配（边界重复）

**流程：**
1. 从 ComponentLibrary 查询 `EAVE_DECORATION` / `EAVE_STRUCTURAL`
2. 优先选择连续构件
3. 沿 `EAVE_LINE` Socket 重复放置

```java
placeAlongEdge(socket, component);
```

### Roof Tile 装配（坡面填充）

**关键原则：屋瓦不是"铺满面"，而是"沿坡向、按行列铺设"**

**流程：**
1. 从 `slope.normal` 推导主铺设方向（down-slope）
2. 在 `slope.area` 上生成平行条带（strip）
3. 每条 strip 重复 `ROOF_TILE` component

```java
fillSurfaceWithStrips(
    slope.area,
    tileComponent,
    direction = downSlope
);
```

⚠️ **v2 不做裁剪到完美边界**（稍微溢出 → 交给 block-level clipping）

## 🧠 AI Prompt 策略

**System Prompt 示例：**

```
"屋顶构件放置遵循以下语义：

- 屋脊只放 ridge 类构件
- 檐口只放 eave 类构件
- 坡面只放 tile 类构件
- 构件必须与文化风格一致
- 构件必须可重复"
```

这会让 AI 几乎不犯错。

## 🎨 Debug Overlay

**新增层：**
- `ROOF_SOCKET_RIDGE` - 脊线 Socket
- `ROOF_SOCKET_EAVE` - 檐口 Socket
- `ROOF_SOCKET_SURFACE` - 坡面 Socket

**可视化：**
- Ridge Socket：粗红线
- Eave Socket：黄色线
- Surface Socket：半透明面

**验证：**
- ✅ 屋脊有没有断
- ✅ 檐口有没有反
- ✅ 瓦是不是顺坡

## 📊 系统能力对比

| 能力 | v1 | v2 |
|------|-----|-----|
| 屋顶封顶 | ✅ | ✅ |
| 屋脊装饰 | ❌ | ✅ |
| 檐口系统 | ❌ | ✅ |
| 瓦片铺设 | ❌ | ✅ |
| 风格化屋顶 | ❌ | ✅ |

## 🏆 为什么这是 Formacraft 的"分水岭"

因为从这一刻开始：

**屋顶不再是"一个几何体"**

而是：

- ✅ 有语义
- ✅ 有生态位
- ✅ 有构件语法

**AI 开始真正"理解屋顶"。**

## 📚 相关文档

- `docs/ROOF_PLATE_V2.md` - RoofPlate v2 规则
- `docs/ROOF_COMPONENT_ASSEMBLY_V2.md` - 装配系统设计
- `docs/FLOOR_COURTYARD_BOOLEAN_V1.md` - Boolean 几何规则
