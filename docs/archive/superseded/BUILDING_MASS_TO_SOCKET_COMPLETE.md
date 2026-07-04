# BuildingMass → Skeleton → Socket 完整派生系统

## 🎯 系统总览

这是整个 Formacraft 架构里最关键、也最"不可逆"的一层。

一旦 BuildingMass → Skeleton / Socket 派生规则站稳了，后面做的：
- 门窗
- 阳台
- 屋顶
- 立面风格
- AI 构件选择

都会自然落在正确的位置上，而不会把系统拖回"建模器"路线。

## 📋 完整派生流程

```
BuildingMassComposition（体量组合）
  ↓
MassFilledChecker.isFilled(x, y, z)
  ↓
MassToSkeletonDeriver.deriveSkeletons()
  ├─ Exterior Skeleton（外暴露边界）
  ├─ Interface Skeleton（内接触边界）
  └─ Top Skeleton（顶部边界）
  ↓
SkeletonMerger.mergeSkeletons()
  └─ 合并同一方向、连续、同一高度区间的 Skeleton
  ↓
SkeletonToSocketDeriver.deriveSockets()
  ├─ Exterior → WINDOW_SOCKET, DOOR_SOCKET, BALCONY_SOCKET
  ├─ Interface → DOOR_SOCKET, ARCH_SOCKET, OPENING_SOCKET
  └─ Top → ROOF_SOCKET, ROOF_EDGE_SOCKET
  ↓
Component / Block Placement
```

## ✅ 已实现的组件

### 1. MassFilledChecker（体量占用检查器）

**位置：** `src/main/java/com/formacraft/common/mass/MassFilledChecker.java`

**核心方法：**
- `isFilled()` - 基础判断函数
- `isExteriorBoundary()` - 外暴露边界判断
- `isInterfaceBoundary()` - 内接触边界判断
- `isTopBoundary()` - 顶部边界判断

### 2. MassDerivedSkeleton（从体量派生的骨架）

**位置：** `src/main/java/com/formacraft/common/mass/derived/MassDerivedSkeleton.java`

**核心字段：**
- `kind` - 类型（WALL, FACADE, FLOOR, CEILING, ROOF, TERRACE）
- `context` - 上下文（EXTERIOR, INTERIOR, CONNECTION）
- `facing` - 方向
- `positions` - 覆盖的方块位置列表

### 3. MassToSkeletonDeriver（从体量派生骨架）

**位置：** `src/main/java/com/formacraft/common/mass/derived/MassToSkeletonDeriver.java`

**核心方法：**
- `deriveSkeletons()` - 派生所有 Skeleton
- `deriveExteriorSkeletons()` - 派生外暴露边界
- `deriveInterfaceSkeletons()` - 派生内接触边界
- `deriveTopSkeletons()` - 派生顶部边界

### 4. SkeletonMerger（骨架合并器）

**位置：** `src/main/java/com/formacraft/common/mass/derived/SkeletonMerger.java`

**核心方法：**
- `mergeSkeletons()` - 合并 Skeleton 列表

**合并规则：**
- 同一方向
- 连续（相邻）
- 同一高度区间

这一步极大降低后续 Socket 数量。

### 5. SkeletonToSocketDeriver（从 Skeleton 派生 Socket）

**位置：** `src/main/java/com/formacraft/common/mass/derived/SkeletonToSocketDeriver.java`

**核心方法：**
- `deriveSockets()` - 从 Skeleton 列表派生所有 Socket
- `deriveExteriorWallSockets()` - 派生外立面 Socket
- `deriveInterfaceSockets()` - 派生内部 Socket
- `deriveTopSockets()` - 派生顶部 Socket

## 🔄 Socket 派生规则

### Exterior Skeleton → Socket

**墙面 Socket：**
- `WALL_SURFACE` - 墙面（可贴）
- `WALL_OPENING` - 墙洞（门/窗）

**判定示例：**
```java
if (heightInRange && notAtCorner) {
    create WINDOW_SOCKET;
}
```

### Interface Skeleton → Socket

**内部 Socket：**
- `WALL_OPENING` - 门洞
- 未来：`ARCH_SOCKET` - 拱门
- 未来：`OPENING_SOCKET` - 开口

**关键点：**
- 没有 Interface Skeleton → 不允许打洞
- 这条规则会让建筑结构感非常强

### Top Skeleton → Socket

**顶部 Socket：**
- `ROOF_SLOPE` - 屋顶表面
- 未来：`ROOF_EDGE_SOCKET` - 屋顶边缘

**判定示例：**
```java
if (topSkeleton.context == EXTERIOR) {
    create ROOF_SURFACE_SOCKET;
}
```

## 🎯 派生顺序（非常重要）

```
1️⃣ isFilled 判定 ✅
2️⃣ Skeleton 派生（Exterior / Interface / Top）✅
3️⃣ Skeleton 合并 ✅
4️⃣ Socket 派生 ✅
5️⃣ Component 装配 ⏳
```

## 🏆 为什么这套规则非常"Minecraft"

- ✅ 所有判断都是 isFilled
- ✅ 不关心多边形 / 曲面
- ✅ 可 chunk-based
- ✅ 可逐步生成
- ✅ 与方块世界完全一致

## 📚 你现在已经拥有了什么

到这一步：

- ✅ Plan → 范围
- ✅ BuildingMass → 体量规则
- ✅ Skeleton → 装配界面
- ✅ Socket → 构件入口

这是一条完整、干净、不会走偏的生成链。

---

**实现时间**: 2026-01-14  
**状态**: ✅ BuildingMass → Skeleton → Socket 完整派生系统完成
