# 多层建筑下的 Socket 分层规则

## 🎯 核心定位

这一步决定了 Formacraft 能不能自然地产生"像人建的多层建筑"，而不是"把单层规则机械复制 N 次"。

**一句话总定义：**

多层建筑下的 Socket 分层 = 把"高度"从纯 Y 值，提升为"楼层语义层（Floor Layer）"。

也就是说：

门 / 窗 / 阳台

不再只看 y 值

而是看：这是第几层、是什么层、承担什么角色

## 📋 核心概念

### FloorLayer（楼层语义层）

**最小模型：**
```java
public class FloorLayer {
    public final int index;          // 第几层（0 = ground）
    public final int baseY;           // 层底 Y
    public final int topY;            // 层顶 Y
    public final FloorRole role;      // 语义角色
}
```

**FloorRole（楼层角色）：**
- `GROUND` - 首层
- `STANDARD` - 标准层
- `TOP` - 顶层
- `SERVICE` - 设备 / 次要层（v2）

⚠️ 注意：
- FloorLayer 是"解释层"，不是结构层
- 不生成方块，只参与规则判断

### 从 HeightRange 切分 FloorLayer

**示例：**
```
baseY = 64
topY  = 76
floorHeight = 4

→ 3 层：
Layer 0: 64–67 (GROUND)
Layer 1: 68–71 (STANDARD)
Layer 2: 72–76 (TOP)
```

## 🔧 Socket 分层规则

### 核心思想

**所有 Socket 规则，都必须先绑定到某一层（FloorLayer），再执行。**

Socket 不再"全局派生"，而是"按层派生"

### DOOR Socket 的分层规则

#### Ground 层（Layer 0）

允许：
- Exterior Door（主入口）
- Interface Door（主楼 ↔ 翼楼）

```java
if (layer.role == GROUND) {
    allow DOOR_SOCKET;
}
```

#### 非 Ground 层（Layer ≥ 1）

禁止：
- Exterior Door（直接通向室外）

允许（受限）：
- Interface Door（楼内连通）
- Balcony Door（如果有 BALCONY Socket）

```java
if (layer.role != GROUND
    && skeleton.context == EXTERIOR) {
    forbid DOOR_SOCKET;
}
```

👉 这一条直接避免"二楼开个门通向空气"的灾难

### WINDOW Socket 的分层规则

#### Ground 层 Window（Layer 0）

- 数量：少
- 尺寸：偏小
- 位置：高窗 / 格栅
- 间距：4~5 block

```java
if (layer.role == GROUND) {
    window.height = 1~2;
    spacing = 4~5;
}
```

#### Standard 层 Window（Layer ≥ 1）

- 数量：多
- 尺寸：标准
- 节奏：规则
- 间距：3 block

```java
if (layer.role == STANDARD) {
    window.height = 2;
    spacing = 3;
}
```

#### Top 层 Window（顶层）

- 可选：阁楼窗、高窗、无窗

```java
if (layer.role == TOP) {
    allow WINDOW_SOCKET only if roofClearance;
}
```

### BALCONY Socket 的分层规则

#### 禁止规则

Balcony 绝不允许在 Ground 层

```java
if (layer.role == GROUND) {
    forbid BALCONY_SOCKET;
}
```

#### 推荐层级

Layer 1 / Layer 2

#### 附加条件

- 下方必须是空（悬挑）
- 对应 BuildingMass.role 允许（SECONDARY / CANTILEVER）

```java
if (layer.role >= STANDARD
    && belowIsAir
    && mass.role != PRIMARY) {
    allow BALCONY_SOCKET;
}
```

## 🔄 同一立面在不同楼层的"Socket 继承 / 变化规则"

这是让立面看起来"有层次"的关键。

### 继承规则（v1）

窗位：
- 可以在垂直方向对齐

门位：
- 只存在于 Ground

```java
inherit WINDOW_SOCKET positions vertically
but with variation chance
```

### 变化规则（v1）

```java
if (random < 0.2) {
    shift window by ±1 block
}
```

👉 避免"复制粘贴楼层"的廉价感

## 🎯 Socket 冲突的"按层解决"

当多个 Socket 规则冲突时：

**按层优先 → 按类型优先**

推荐顺序：
```
GROUND 层规则 > STANDARD 层规则 > TOP 层规则
```

## 🎨 Debug Overlay（多层一定要看）

### 新增显示

每层不同颜色的 Socket 框

层号标注

```
[Layer 0] green
[Layer 1] blue
[Layer 2] purple
```

你可以一眼看到：
- 门是不是只在一层
- 阳台是不是在对的高度
- 窗有没有"断层"

## 🏆 为什么这套分层规则非常 Formacraft

- ✅ 不引入楼板建模
- ✅ 不生成连续几何
- ✅ 所有判断仍然是 block + rule
- ✅ 非常适合 AI 描述
- ✅ 非常容易让玩家理解和修改

## 📚 你现在已经补齐了一个"质变点"

到这里，你的系统已经从：

**"在体量上随机开洞"**

升级成：

**"按建筑层级语义生成立面"**

这是建筑感的真正来源。

---

**实现时间**: 2026-01-14  
**状态**: ✅ 多层 Socket 分层规则完成，支持按楼层语义派生
