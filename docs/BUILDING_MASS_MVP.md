# BuildingMass MVP（最小可用模型）

## 🎯 核心定位

**BuildingMass 不是几何体，不是模型，不是 mesh。**

**BuildingMass = 在某个空间域内，允许方块生成的一段体量规则集合。**

换成 Minecraft 语言就是：

**BuildingMass = 一块"可以放哪些方块、放到多高、哪些地方允许/不允许放"的体积规则。**

## 📋 BuildingMass 在 Formacraft 中的位置

正确的生成层级：

```
Plan (Domain / 范围限制)
   ↓
BuildingMassAssembly（体量规则组合）
   ↓
Derived Surfaces（由体量产生的真实结构面）
   ↓
Skeleton / Socket
   ↓
Component / Block Placement
```

**关键：**
- ❌ 没有任何一步是"生成连续几何模型"
- ✅ 所有东西最终都会回到：方块是否存在、在什么位置、以什么规则存在

## 🏗️ BuildingMass 的核心职责

一个 BuildingMass 只回答 5 个问题：

1. **范围：** 我在哪个区域内生效？（footprint）
2. **高度：** 我在 Y 方向上允许多高？（height）
3. **形态：** 我是实心的？中空的？板状的？（type）
4. **关系：** 我和其他体量是叠加、穿插、相减？（operation）
5. **用途倾向：** 我是主体？附属？挑出？（role）

**它不回答：**
- 用什么方块
- 有没有窗
- 屋顶怎么做
- 立面长什么样

## 📊 BuildingMass 的最小数据模型

### BuildingMass（核心）

```java
public class BuildingMass {
    public final String id;
    public final AreaMask footprint;        // 水平范围（XZ）
    public final HeightRange height;        // 垂直范围（Y）
    public final MassType type;             // 体量类型
    public final MassOperation operation;   // 组合关系
    public final MassRole role;             // 语义角色
}
```

### AreaMask（不是几何！是"是否允许"）

**这是防止走偏成建模的关键设计。**

```java
public interface AreaMask {
    boolean contains(int x, int z);
}
```

**最小实现：**
- `RectMask` - 矩形区域
- `PlanBoundedMask` - 基于 Plan Domain 的离散方块位置集合

**关键特点：**
- ✅ 离散的（基于方块位置）
- ✅ 没有多边形运算
- ✅ 完全贴合 Minecraft 的方块世界

### HeightRange（层高、层数的根）

```java
public class HeightRange {
    public final int baseY;
    public final int topY;
    
    public boolean contains(int y) { ... }
}
```

**直接支持：**
- 层高
- 层数
- 悬挑（baseY > 地面）

### MassType（体量"填充方式"）

```java
public enum MassType {
    SOLID,    // 实体体量（主体）
    HOLLOW,   // 中空体量（回廊、空洞）
    SLAB,     // 板状（平台、屋盖）
    FRAME     // 框架（柱梁系统，v2）
}
```

### MassOperation（体量组合的数学关系）

```java
public enum MassOperation {
    ADD,        // 叠加
    SUBTRACT,   // 相减（天井、中庭）
    INTERSECT   // 穿插（悬挑、嵌入）
}
```

**⚠️ 注意：**
- 这是逻辑关系
- 不是几何布尔运算
- 只决定"这个体量是否覆盖这个方块位置"

### MassRole（AI 和规则真正关心的）

```java
public enum MassRole {
    PRIMARY,     // 主体
    SECONDARY,   // 附属
    CANTILEVER,  // 悬挑
    CORE,        // 核心筒
    SERVICE      // 服务体量
}
```

## 🔧 BuildingMass 的"生成语义"

### 世界中的判断逻辑（核心）

在任何 (x, y, z) 位置：

```java
boolean filled = false;

for (BuildingMass mass : masses) {
    if (mass.footprint.contains(x, z)
        && mass.height.contains(y)) {

        switch (mass.operation) {
            case ADD -> filled = true;
            case SUBTRACT -> filled = false;
            case INTERSECT -> filled = filled && true;
        }
    }
}
```

**关键点：**
- ✅ 没有 mesh
- ✅ 没有 extrusion
- ✅ 只有：方块位置 + 规则判断
- ✅ 这就是 Minecraft 原生的思维方式

## 🔄 从 BuildingMass 到"真实结构面"

门窗、阳台、屋顶、立面都不是直接作用在 Mass 上，而是：

**从 Mass 推导出"结构边界"**

例如：
- (x, z) 在 footprint 内
- (x, y) 在 height 边界
- → 这是一个立面候选点

## 🎯 为什么这个模型不会把项目带偏

### 1️⃣ 它是"规则"，不是"模型"

- ✅ 没有连续几何
- ✅ 没有三角面
- ✅ 没有法线

### 2️⃣ 它天然适配 Minecraft

- ✅ 一切以 BlockPos 为中心
- ✅ 一切可以逐格扫描
- ✅ 一切可以 lazy 生成

### 3️⃣ 它非常适合 AI

LLM 非常擅长输出：

```json
{
  "type": "SOLID",
  "height": "3 floors",
  "role": "PRIMARY",
  "operation": "ADD"
}
```

而不是：

```
"一个 L 形多边形挤出 12.3 米"
```

### 4️⃣ 它给你留下了所有"建筑自由度"

- ✅ 体量可以错动
- ✅ 可以悬挑
- ✅ 可以穿插
- ✅ 可以局部缩进 / 吐出

这些都是 BuildingMass 的组合自然产生的结果。

## 🏆 总结

**建筑生成 ≠ 几何生成**

**建筑生成 = 空间规则 → 方块决策**

Formacraft 应该、也完全可以走这条路。

---

**设计时间**: 2026-01-14  
**状态**: ✅ MVP 数据模型完成，基于离散方块位置的规则系统
