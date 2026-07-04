# BuildingMass MVP 实现总结

## ✅ 已实现的组件

### 1. 核心接口和枚举

**AreaMask** (`src/main/java/com/formacraft/common/mass/AreaMask.java`)
- 区域掩码接口（离散的方块位置判断）
- 实现：`RectMask`、`PlanBoundedMask`

**HeightRange** (`src/main/java/com/formacraft/common/mass/HeightRange.java`)
- 高度范围（离散的整数范围）
- 支持层高、层数、悬挑

**MassType** (`src/main/java/com/formacraft/common/mass/MassType.java`)
- 体量类型（SOLID, HOLLOW, SLAB, FRAME）

**MassOperation** (`src/main/java/com/formacraft/common/mass/MassOperation.java`)
- 体量组合操作（ADD, SUBTRACT, INTERSECT）

**MassRole** (`src/main/java/com/formacraft/common/mass/MassRole.java`)
- 体量角色（PRIMARY, SECONDARY, CANTILEVER, CORE, SERVICE）

### 2. 核心类

**BuildingMass** (`src/main/java/com/formacraft/common/mass/BuildingMass.java`)
- 建筑体量（基于离散方块位置的规则模型）
- `allowsBlockAt(x, y, z)` - 核心判断逻辑

**BuildingMassAssembly** (`src/main/java/com/formacraft/common/mass/BuildingMassAssembly.java`)
- 建筑体量组合（规则组合）
- `allowsBlockAt(x, y, z)` - 组合判断逻辑

**BuildingMassBuilder** (`src/main/java/com/formacraft/common/mass/BuildingMassBuilder.java`)
- 体量构建工具
- `createRectangularMass()` - 创建矩形体量
- `createPlanBoundedMass()` - 创建基于离散位置的体量
- `createHollowMass()` - 创建中空体量
- `createCantileverSlab()` - 创建悬挑板

### 3. 实现类

**RectMask** (`src/main/java/com/formacraft/common/mass/RectMask.java`)
- 矩形掩码实现

**PlanBoundedMask** (`src/main/java/com/formacraft/common/mass/PlanBoundedMask.java`)
- 基于 Plan Domain 的离散方块位置掩码

## 🎯 核心判断逻辑

### BuildingMass.allowsBlockAt()

```java
public boolean allowsBlockAt(int x, int y, int z) {
    if (!footprint.contains(x, z) || !height.contains(y)) {
        return false;
    }
    return switch (operation) {
        case ADD -> true;
        case SUBTRACT -> false;
        case INTERSECT -> true; // 需要在外层处理
    };
}
```

### BuildingMassAssembly.allowsBlockAt()

```java
public boolean allowsBlockAt(int x, int y, int z) {
    boolean filled = false;
    boolean hasIntersect = false;

    for (BuildingMass mass : masses) {
        if (!mass.footprint.contains(x, z) || !mass.height.contains(y)) {
            continue;
        }

        switch (mass.operation) {
            case ADD -> filled = true;
            case SUBTRACT -> return false; // 相减：直接不允许
            case INTERSECT -> hasIntersect = true;
        }
    }

    // 处理 INTERSECT...
    return filled || hasIntersect;
}
```

## 🔄 正确的架构流程（MVP）

```
Plan (Domain / 范围限制)
   ↓
BuildingMassAssembly（体量规则组合）
   ├─ BuildingMass[] (体量列表)
   └─ allowsBlockAt(x, y, z) ← 核心判断逻辑
   ↓
Derived Surfaces（由体量产生的真实结构面）
   ↓
Skeleton / Socket
   ↓
Component / Block Placement
```

## 📋 为什么这个模型不会把项目带偏

### 1️⃣ 它是"规则"，不是"模型"

- ✅ 没有连续几何
- ✅ 没有三角面
- ✅ 没有法线
- ✅ 只有离散的方块位置判断

### 2️⃣ 它天然适配 Minecraft

- ✅ 一切以 BlockPos 为中心
- ✅ 一切可以逐格扫描
- ✅ 一切可以 lazy 生成
- ✅ 完全贴合 Minecraft 的方块世界

### 3️⃣ 它非常适合 AI

LLM 擅长输出规则描述：

```json
{
  "type": "SOLID",
  "height": "3 floors",
  "role": "PRIMARY",
  "operation": "ADD"
}
```

而不是连续几何。

### 4️⃣ 它给你留下了所有"建筑自由度"

- ✅ 体量可以错动
- ✅ 可以悬挑
- ✅ 可以穿插
- ✅ 可以局部缩进 / 吐出

## 📚 使用示例

### 示例 1：创建简单的矩形体量

```java
BuildingMass mainMass = BuildingMassBuilder.createRectangularMass(
    "main",
    0, 20,    // minX, maxX
    0, 10,    // minZ, maxZ
    0, 15,    // baseY, topY (5层，每层3格)
    MassType.SOLID,
    MassRole.PRIMARY
);

BuildingMassAssembly assembly = BuildingMassAssembly.empty(domain)
    .withMass(mainMass);
```

### 示例 2：创建带中庭的体量组合

```java
// 主体（ADD）
BuildingMass mainMass = BuildingMassBuilder.createRectangularMass(
    "main", 0, 20, 0, 10, 0, 15,
    MassType.SOLID, MassRole.PRIMARY
);

// 中庭（SUBTRACT）
BuildingMass courtyard = BuildingMassBuilder.createHollowMass(
    "courtyard", 8, 12, 4, 6, 0, 15
);

BuildingMassAssembly assembly = BuildingMassAssembly.empty(domain)
    .withMass(mainMass)
    .withMass(courtyard); // SUBTRACT 会自动处理
```

### 示例 3：判断方块位置

```java
// 判断 (10, 5, 5) 是否允许放置方块
boolean canPlace = assembly.allowsBlockAt(10, 5, 5);
```

## 🏆 总结

**BuildingMass MVP 已实现完成。**

**核心特点：**
- ✅ 基于离散方块位置的规则模型
- ✅ 完全贴合 Minecraft 的方块世界
- ✅ 没有连续几何运算
- ✅ 非常适合 AI 生成
- ✅ 给出所有建筑自由度

**这是 Formacraft 的正确方向：建筑生成 = 空间规则 → 方块决策**

---

**实现时间**: 2026-01-14  
**状态**: ✅ MVP 数据模型完成，基于离散方块位置的规则系统
