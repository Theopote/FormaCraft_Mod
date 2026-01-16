# Socket 细化规则（门 / 窗 / 阳台）v1

## 🎯 核心定位

**Socket ≠ 洞**

**Socket = "允许发生变化的位置"**

Socket 不直接破坏体量

Socket 只是给后续：
- Component
- Block rule
- AI decision

提供"入口"

## 📋 Socket 细化的三大类

1. **DOOR Socket（通行）**
2. **WINDOW Socket（采光 / 立面节奏）**
3. **BALCONY Socket（体量外扩）**

它们全部来自 Skeleton，而不是来自 Mass 本身。

## 🔧 DOOR Socket（门）

### 语义定义

Door Socket = 允许"人 / 实体 / 空间"穿过体量边界的位置

### 派生来源（必须满足其一）

1. **Interface Skeleton（首选）**
2. **Exterior Skeleton（受限）**

### 派生规则（Interface）

**判定条件：**
- `skeleton.context == INTERIOR / CONNECTION`
- `y == baseFloorY + 1`（地面层）
- 连续宽度 ≥ 2 block
- 上方至少 2 block 高度

👉 这会自然生成：
- 主楼 ↔ 翼楼 门
- 连廊入口

### 派生规则（Exterior，受限）

Exterior Door 只能生成在：
- PRIMARY mass
- 且位于 Plan 的"入口侧"（如果有 axis / facing）

👉 避免 AI 给你满墙开门

### 默认尺寸

| 用途  | width | height |
| --- | ----- | ------ |
| 普通门 | 2     | 3      |
| 大门  | 3     | 4      |

## 🔧 WINDOW Socket（窗）

### 语义定义

Window Socket = 允许"光 / 视线 / 节奏"穿过外立面的地方

### 派生来源（只能来自）

**Exterior Skeleton**

⚠️ Interior 不能直接生成 Window Socket

### 派生规则（v1）

**基本判定：**
- `skeleton.context == EXTERIOR`
- `y >= baseFloorY + 2`（不在底层）
- `y <= topFloorY - 1`（不在顶层）
- `notAtCorner`（不在角落）

### 水平节奏规则（非常重要）

窗不是"能开就开"，而是"按节奏开"

v1 推荐最小规则：
- `every N blocks → one window`
- `spacing = mass.role == PRIMARY ? 3 : 4`

### 尺寸建议

| 类型  | width | height |
| --- | ----- | ------ |
| 普通窗 | 1     | 2      |
| 大窗  | 2     | 2      |

## 🔧 BALCONY Socket（阳台 / 外挑）

### 语义定义

Balcony Socket = 允许"体量向外延伸"的接口

⚠️ 它不是窗，也不是门
⚠️ 它一定和 CANTILEVER 或悬空条件绑定

### 派生来源（必须同时满足）

1. **Exterior Skeleton**
2. **Skeleton 下方为空（悬空）**
3. **对应的 BuildingMass.role == CANTILEVER 或 SECONDARY**

### 空间限制（非常重要）

`balconyDepth <= cantilever.maxOverhang`

👉 否则禁止生成

### 默认尺寸

| 项目     | 数值  |
| ------ | --- |
| width  | 2~3 |
| depth  | 1~2 |
| height | 1   |

## 🎯 Socket 冲突与优先级规则（必须）

同一位置可能同时满足多个条件，必须有优先级。

### 推荐优先级（v1）

**DOOR > BALCONY > WINDOW**

也就是说：
- 有门 → 不再生成窗
- 有阳台 → 窗转为阳台门
- 窗是最低优先级

## 🔗 Socket 到 Component 的"桥接语义"

Socket 不放方块，只提供约束。

例如：
```java
Socket {
    type = WINDOW
    width = 2
    height = 2
    tags = ["exterior", "upper-floor"]
}
```

Component Ranker 再决定：
- 用木窗
- 用石窗
- 用格栅
- 是否带窗台

## 🎨 Debug Overlay

### Socket 可视化建议

| Socket  | 显示   |
| ------- | ---- |
| DOOR    | 绿色矩形 |
| WINDOW  | 蓝色矩形 |
| BALCONY | 橙色盒子 |

画在：
- Skeleton 面上
- 对齐 block grid

你会非常清楚地看到：
- 门是不是太多
- 窗是不是乱
- 阳台是不是悬空

## 🏆 为什么这套 Socket 细化规则是"安全的"

- ✅ 完全 block-based
- ✅ 派生自体量，不凭空生成
- ✅ AI 很难乱来
- ✅ 用户可以手动删 / 调整
- ✅ 后续风格化非常自由

## 📚 你现在已经拥有的完整能力链

```
Plan → Domain
BuildingMass → 体量
Skeleton → 装配界面
Socket → 门窗阳台入口 ✅
Component → 方块
```

这是一条真正"不会走偏"的 Formacraft 主线。

---

**实现时间**: 2026-01-14  
**状态**: ✅ Socket 细化规则完成，支持门/窗/阳台和优先级处理
