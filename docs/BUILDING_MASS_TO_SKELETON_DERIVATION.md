# BuildingMass → Skeleton / Socket 派生规则

## 🎯 核心定位

**Skeleton 不是"体量的几何外壳"，**

**而是"在体量规则发生变化的地方，生成的装配机会（装配界面）"。**

换句话说：

**哪里体量规则"发生断裂 / 变化 / 暴露"，**

**哪里就派生 Skeleton / Socket。**

## 📋 从 BuildingMass 派生 Skeleton 的三大来源

你只需要记住这三类边界，其余都是衍生。

1. **外暴露边界（Exterior Boundary）**
2. **内接触边界（Interface Boundary）**
3. **顶部边界（Top Boundary）**

这三类，100% 可以从方块规则判断出来。

## 🔧 统一的体量占用判断

### isFilled(x, y, z)

所有 Skeleton / Socket 派生，都只依赖这个判断函数。

```java
boolean isFilled(x, y, z) = composition.allowsBlockAt(x, y, z);
```

## 📊 Skeleton 派生规则

### 规则一：外暴露边界 → Exterior Skeleton

**判定条件：**
- `isFilled(x,y,z) == true`
- `isFilled(x+dir.x, y+dir.y, z+dir.z) == false`

👉 这个"面"就是一个 Exterior Face

**Exterior Skeleton 的语义：**
```java
Skeleton {
    kind = WALL | FACADE
    context = EXTERIOR
    facing = dir
}
```

### 规则二：内接触边界 → Interface Skeleton

**判定条件：**
在 (x,y,z) 处：
- `isFilled(x,y,z) == true`
- `isFilled(x+dir.x, y+dir.y, z+dir.z) == true`
- 但来自不同的 BuildingMass 或高度区间不同

👉 这是一个 Interface Face

**Interface Skeleton 的语义：**
```java
Skeleton {
    kind = WALL | FLOOR | CEILING
    context = INTERIOR | CONNECTION
    role = CONNECTION
}
```

**常见来源：**
- 主楼 ↔ 翼楼
- 主楼 ↔ 悬挑
- 主体 ↔ 中庭（SUBTRACT）

### 规则三：顶部边界 → Top Skeleton

**判定条件：**
- `isFilled(x,y,z) == true`
- `isFilled(x, y+1, z) == false`

👉 这是一个 Top Face

**Top Skeleton 的语义：**
```java
Skeleton {
    kind = ROOF | TERRACE
    context = EXTERIOR
    normal = UP
}
```

**与体量角色的关系：**

| MassRole   | Top Skeleton   |
| ---------- | -------------- |
| PRIMARY    | ROOF           |
| SECONDARY  | ROOF / TERRACE |
| CANTILEVER | TERRACE        |

👉 屋顶 / 露台不是单独设计的，是体量自然给出的

## 🔄 派生顺序（非常重要）

```
1️⃣ isFilled 判定
2️⃣ Skeleton 派生（Exterior / Interface / Top）
3️⃣ Skeleton 合并
4️⃣ Socket 派生
5️⃣ Component 装配
```

## 📚 为什么这套规则非常"Minecraft"

- ✅ 所有判断都是 isFilled
- ✅ 不关心多边形 / 曲面
- ✅ 可 chunk-based
- ✅ 可逐步生成
- ✅ 与方块世界完全一致

## 🏆 你现在已经拥有了什么

到这一步：

- ✅ Plan → 范围
- ✅ BuildingMass → 体量规则
- ✅ Skeleton → 装配界面
- ⏳ Socket → 构件入口（待实现）

这是一条完整、干净、不会走偏的生成链。

---

**实现时间**: 2026-01-14  
**状态**: ✅ Skeleton 派生规则完成，Socket 派生待实现
