# Building Mass Assembly（体量组合层）架构设计

## 🎯 核心定义

**Building Mass Assembly = 在 Plan（Site Boundary）范围内，通过多个几何体的位移、错动、穿插、悬挑组合成建筑体量。**

这是 Formacraft 真正缺失的核心中间层。

## 📋 问题陈述

### 当前架构的问题

```
PlanSkeleton (Domain)
  ↓
StructuralSkeleton (直接生成 Floor / Wall / Roof)
  ↓
Component (方块)
```

**问题：**
- ❌ 把 Plan 当成"真实平面"
- ❌ 直接生成结构，没有体量组合
- ❌ 无法表达：悬挑、错动、穿插、层间变化

### 正确的架构

```
PlanSkeleton (Site Boundary / Domain)
  ↓
Building Mass Assembly (体量组合层) ← **新增**
  ├─ 体量原型选择
  ├─ 位移 / 错动 / 穿插 / 悬挑
  └─ 产生事实上的结构
  ↓
事实上的平面 / 立面
  ├─ 凹进
  ├─ 挑出
  └─ 层间变化
  ↓
StructuralSkeleton (候选结构 / 可用语义)
  ↓
Skeleton / Socket
  ↓
Component (方块)
```

## 🏗️ Building Mass Assembly 设计

### 1. 体量原型（Mass Prototype）

```java
public enum MassPrototype {
    BLOCK,      // 方盒子
    SLAB,       // 平板
    TOWER,      // 塔
    WING,       // 翼
    PLATFORM    // 平台
}
```

### 2. 体量定义（Mass Definition）

```java
public class MassDefinition {
    public final String id;
    public final MassPrototype prototype;
    public final Box bounds;           // 体量的边界框（相对于 Plan Domain）
    public final Vec3i offset;         // 在 Domain 内的偏移
    public final Rotation rotation;    // 旋转
    public final int floorCount;       // 层数
    public final double floorHeight;   // 层高
}
```

### 3. 体量组合规则（Mass Assembly Rules）

```java
public class MassAssembly {
    public final PlanSkeleton domain;              // Plan 作为 Domain
    public final List<MassDefinition> masses;      // 体量列表
    public final List<MassRelationship> relationships; // 体量间关系
}
```

### 4. 体量关系（Mass Relationship）

```java
public class MassRelationship {
    public enum Type {
        ATTACHED,   // 附着（共用面）
        INTERSECT,  // 穿插
        OVERHANG,   // 悬挑
        OFFSET      // 错动
    }
    
    public final String massA;
    public final String massB;
    public final Type type;
    public final Vec3i offset;
}
```

## 🔄 体量组合后的结构派生

### 从体量组合派生出事实上的结构

**体量组合后，会自然产生：**

1. **事实上的平面**
   - 每个体量的每一层都是"事实上的平面"
   - 不同体量可能有不同的层高

2. **事实上的立面**
   - 体量之间的穿插产生"立面变化"
   - 悬挑产生"挑出立面"
   - 错动产生"错层立面"

3. **事实上的屋顶**
   - 每个体量的顶部才是"真正的屋顶"
   - 不同体量可能有不同的屋顶形式

### 结构实例化规则

```
Mass Assembly
  ↓
For each Mass:
  - 生成 FloorPlate（基于体量的每一层）
  - 生成 WallSegment（基于体量的边界 + 穿插关系）
  - 生成 RoofPlate（基于体量的顶部）
  ↓
StructuralSkeleton（候选结构）
  ↓
根据实际体量组合实例化
  ↓
Skeleton / Socket
  ↓
Component (方块)
```

## 🎨 示例场景

### 场景 1：悬挑阳台

```
Plan (Domain): 矩形 20×10
  ↓
Mass 1: BLOCK, 20×10×5, offset=(0, 0, 0)
Mass 2: SLAB, 8×3×1, offset=(6, 5, 3)  ← 悬挑
  ↓
事实上的结构：
  - 主体有 5 层
  - 3 层有悬挑阳台（Mass 2）
  ↓
StructuralSkeleton:
  - FloorPlate (主体 5 层)
  - WallSegment (主体边界)
  - RoofPlate (主体顶部)
  - 悬挑阳台的 FloorPlate / WallSegment
```

### 场景 2：错动体量

```
Plan (Domain): 矩形 30×15
  ↓
Mass 1: BLOCK, 20×15×6, offset=(0, 0, 0)
Mass 2: BLOCK, 15×10×4, offset=(15, 0, 2)  ← 错动（X 偏移 + 层高偏移）
  ↓
事实上的结构：
  - Mass 1: 6 层
  - Mass 2: 4 层，从 Mass 1 的 2 层开始
  ↓
StructuralSkeleton:
  - 两个独立的 FloorPlate / WallSegment / RoofPlate
  - 连接处需要特殊处理
```

### 场景 3：穿插体量（十字形）

```
Plan (Domain): 不规则形状
  ↓
Mass 1: BLOCK, 20×10×6, offset=(5, 0, 0)
Mass 2: BLOCK, 10×20×6, offset=(0, 5, 0)
Relationship: INTERSECT, offset=(10, 10, 0)
  ↓
事实上的结构：
  - 十字形平面（两个体量穿插）
  - 中间区域重叠
  ↓
StructuralSkeleton:
  - 需要 Boolean 运算处理重叠区域
  - 生成正确的 FloorPlate / WallSegment
```

## 🔧 实现优先级

### Phase 1（MVP）

1. **MassDefinition 数据结构**
   - 基本的体量定义
   - 在 Domain 内的位置 / 尺寸

2. **简单的体量组合**
   - 单个体量（Block）
   - 无穿插 / 悬挑

3. **从体量派生 FloorPlate**
   - 每个体量的每一层 → FloorPlate

### Phase 2

1. **体量关系**
   - ATTACHED（附着）
   - OFFSET（错动）

2. **从体量派生 WallSegment**
   - 基于体量边界
   - 考虑体量关系

3. **从体量派生 RoofPlate**
   - 基于体量顶部

### Phase 3

1. **复杂体量关系**
   - INTERSECT（穿插）
   - OVERHANG（悬挑）

2. **Boolean 运算**
   - 处理重叠区域

3. **层间变化**
   - 不同层有不同的体量组合

## 📚 与现有系统的集成

### PlanSkeleton 的新角色

- ✅ **Site Boundary / Domain**
- ✅ 提供范围限制
- ✅ 提供主轴 / 朝向参考
- ❌ 不再是"真实平面"

### StructuralSkeleton 的新角色

- ✅ **候选结构 / 可用语义**
- ✅ 从体量组合后派生
- ✅ 只在需要时实例化
- ❌ 不再是"Plan 的必然结果"

### Roof / Ridge 系统的新角色

- ✅ **候选屋顶形式**
- ✅ 基于体量顶部实例化
- ✅ 与体量组合联动
- ❌ 不再是"独立的几何生成系统"

## 🏆 总结

**Building Mass Assembly 是 Formacraft 真正缺失的核心中间层。**

有了它，Formacraft 才能真正表达：
- ✅ 悬挑
- ✅ 错动
- ✅ 穿插
- ✅ 层间变化
- ✅ 多体量组合

**这是让 Formacraft 从"平面拉伸"升级到"真正建筑"的关键。**

---

**设计时间**: 2026-01-14  
**状态**: 📋 架构设计完成，待实现
