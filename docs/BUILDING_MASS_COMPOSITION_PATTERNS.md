# BuildingMass 组合范式（Composition Patterns）v1

## 🎯 核心定位

**体量组合 ≠ 形状运算**

**体量组合 = 多个体量在同一世界坐标系中，对"哪些方块允许存在"的规则叠加**

我们关心的是：
- ✅ 相对位置
- ✅ 相对高度
- ✅ 规则优先级
- ✅ 覆盖 / 让位 / 悬空

**而不是"布尔几何"。**

## 📋 三种最小组合范式

### 1. 主体体量（Primary Mass）

**语义：**
- 建筑的"锚点体量"
- 决定：主高度、主朝向、主入口逻辑

**建议规则：**
```java
BuildingMass {
    role = PRIMARY
    operation = ADD
    type = SOLID
}
```

**行为约定：**
- PRIMARY 永远先计算
- 不被其他体量覆盖（除非明确 SUBTRACT）
- 决定默认层高、默认立面节奏（后续）

**Minecraft 层面的直觉：**
"先盖一个完整的大盒子"，其他体量都是在它基础上加减。

### 2. 翼楼体量（Wing Attachment）

**语义：**
- 依附在主体上的"侧向扩展"
- 可以是：翼楼、侧殿、回廊主体

**最小参数：**
```java
BuildingMass {
    role = SECONDARY
    operation = ADD
    type = SOLID
}
```

**关键：相对定位规则**

**WingAttachment：**
```java
class WingAttachment {
    String hostMassId;        // 依附哪个体量
    AttachmentSide side;      // 附着在哪一侧（LEFT, RIGHT, FRONT, BACK）
    int offset;               // 前后错动（block，离散的方块数）
}
```

**⚠️ 注意：**
- 这是离散的 block 偏移
- 没有向量、没有角度

**行为规则：**
- 翼楼 baseY ≤ 主体 baseY
- 翼楼 topY ≤ 主体 topY（通常）
- 翼楼与主体必须至少有一条立面相交

**Minecraft 判断：**
```java
boolean attached = wing.footprint overlaps host.footprint on side;
```

### 3. 悬挑体量（Cantilever）

**语义：**
- 悬挑不是"漂浮"
- 悬挑是"在下方没有体量，但上方允许存在"的规则

**最小参数：**
```java
BuildingMass {
    role = CANTILEVER
    operation = ADD
    type = SOLID
    height.baseY > supportingMass.topY
}
```

**关键点：**
悬挑体量的 baseY 高于其支撑体量的 topY

**CantileverSupport：**
```java
class CantileverSupport {
    String supportMassId;
    int maxOverhang;   // 最大挑出距离（block）
}
```

**规则判断：**
```java
if (distanceFromSupport > maxOverhang) {
    forbid block placement;
}
```

**👉 这是 Minecraft 友好的"伪结构判断"**
你不是在算力学，只是在限制生成。

## 🔄 三种范式如何一起工作

### 统一的体量叠加顺序（非常重要）

```
1. PRIMARY
2. SECONDARY
3. CANTILEVER
4. SUBTRACT（中庭、空洞）
```

这一个顺序，能避免 80% 的冲突问题。

### 世界中某点 (x,y,z) 的最终判定

```java
boolean filled = false;

List<BuildingMass> orderedMasses = getOrderedMasses();

for (BuildingMass mass : orderedMasses) {
    if (mass.appliesTo(x,y,z)) {
        switch (mass.operation) {
            case ADD -> filled = true;
            case SUBTRACT -> filled = false;
        }
    }
}
```

**逻辑非常"方块世界"。**

## 🎁 组合范式对后续步骤的"馈赠"

一旦你有了这三种体量范式：

### 真实结构自然出现

- **体量边界** → 外立面
- **体量交界** → 内墙 / 连廊
- **悬挑下方** → 架空 / 灰空间

### 门窗 / 阳台 / 屋顶才"有意义"

- **阳台** = CANTILEVER + 开口
- **屋顶** = 最上层体量的上边界
- **回廊** = SECONDARY + HOLLOW

## 🏆 为什么这一步"非常 Formacraft"

- ✅ 完全 block-based
- ✅ 没有 mesh / boolean / extrusion
- ✅ 非常适合 LLM 描述
- ✅ 非常适合 Debug Overlay
- ✅ 非常适合用户干预（加一个翼楼、拉长悬挑）

## 📚 使用示例

### 示例 1：主楼 + 翼楼

```java
// 1. 创建主体
BuildingMass primary = BuildingMassBuilder.createRectangularMass(
    "main", 0, 20, 0, 10, 0, 15,
    MassType.SOLID, MassRole.PRIMARY
);

// 2. 创建翼楼
BuildingMass wing = BuildingMassBuilder.createRectangularMass(
    "wing", 20, 30, 2, 8, 0, 12,
    MassType.SOLID, MassRole.SECONDARY
);

// 3. 创建组合
BuildingMassComposition composition = BuildingMassComposition.empty(domain)
    .withMass(primary)
    .withMass(wing)
    .withWingAttachment("wing", new WingAttachment("main", AttachmentSide.RIGHT, 0));
```

### 示例 2：主楼 + 悬挑阳台

```java
// 1. 创建主体
BuildingMass primary = BuildingMassBuilder.createRectangularMass(
    "main", 0, 20, 0, 10, 0, 15,
    MassType.SOLID, MassRole.PRIMARY
);

// 2. 创建悬挑阳台
BuildingMass balcony = BuildingMassBuilder.createRectangularMass(
    "balcony", 6, 14, 10, 13, 16, 16,
    MassType.SLAB, MassRole.CANTILEVER
);

// 3. 创建组合
BuildingMassComposition composition = BuildingMassComposition.empty(domain)
    .withMass(primary)
    .withMass(balcony)
    .withCantileverSupport("balcony", new CantileverSupport("main", 5)); // 最大挑出 5 格
```

## 🔜 下一步

现在你已经有：
- ✅ 平面域（Plan）
- ✅ 体量规则（BuildingMass）
- ✅ 组合范式（主楼 / 翼楼 / 悬挑）

**下一步最自然的是：**

从 BuildingMass 派生"可装配的结构面（Skeleton / Socket）"

也就是把：
- "体量的边界" → Skeleton
- "体量的顶部" → Skeleton
- "体量之间的交线" → Skeleton

变成你已有系统能吃的东西。

---

**设计时间**: 2026-01-14  
**状态**: ✅ 组合范式 v1 完成，支持三种最小范式
