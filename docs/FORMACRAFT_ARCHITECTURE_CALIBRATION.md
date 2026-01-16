# Formacraft 架构校准文档

## 🎯 核心原则（必须严格遵守）

**Formacraft 最终永远只在 Minecraft 世界中放置方块。**

中间过程可以有：
- ✅ 幽灵预览
- ✅ 辅助几何
- ✅ Debug Overlay

但这些都不是"建筑本体"，只是"思考与规划工具"。

## 📋 平面的正确地位

### ❌ 错误理解

把 PlanSkeleton 理解成：
- "建筑的真实几何平面"
- "建筑每一层的真实平面"
- "直接生成的结构"

### ✅ 正确定义

**Formacraft 中的 Plan / 平面 = 一个"建筑活动允许发生的空间域（Domain）"，而不是建筑几何本身。**

它的作用只有三个：

1. **限制范围**
   - 建筑不越界
   - AI 不乱飞

2. **提供参考**
   - 朝向
   - 主体位置
   - 与环境的关系

3. **作为体量组合的"舞台"**
   - 几何体可以：进、出、穿插、悬挑
   - 但总体不脱离这个 domain

## 🏗️ 正确的生成顺序

```
0️⃣ 环境感知（Terrain / Context）
   ↓
1️⃣ 平面范围（Plan = Domain）
   ↓
2️⃣ 体量原型选择（Block / Slab / Tower / Wing）
   ↓
3️⃣ 体量组合（位移 / 穿插 / 悬挑）
   ↓
4️⃣ 派生出事实上的平面 / 立面
   ↓
5️⃣ 在这些"真实结构"上：
   - 开门窗
   - 做阳台
   - 决定屋顶
   ↓
6️⃣ 方块级构件装配
```

**关键点：**
- 👉 **屋顶是在第 5 步才出现的，而不是第 2 步**
- 👉 **建筑不是"一个平面拉起来"，而是多个几何体在范围内组合**

## 🔄 架构层级重新定位

### 原有架构（需要校准）

```
PlanProgram
  ↓
PlanSkeleton (2D 几何语义)
  ↓
StructuralSkeleton (3D 结构骨架)
  ├─ FloorPlate
  ├─ WallSegment
  └─ RoofPlate
  ↓
ExecutableSkeletonPlan
  ↓
Component (方块)
```

### 校准后的架构

```
PlanProgram
  ↓
PlanSkeleton (Site Boundary / Build Domain)
  ├─ 范围限制
  ├─ 主轴
  └─ 空间组织倾向
  ↓
Building Masses（体量组合层）← **新增 / 缺失的一层**
  ├─ 体量原型选择
  ├─ 位移 / 错动 / 穿插
  └─ 悬挑
  ↓
事实上的平面 / 立面变化
  ├─ 凹进
  ├─ 挑出
  └─ 层间变化
  ↓
StructuralSkeleton（候选结构 / 可用语义）
  ├─ FloorPlate（候选）
  ├─ WallSegment（候选）
  └─ RoofPlate（候选）
  ↓
Skeleton / Socket
  ↓
Component（方块）
```

## 🎨 关键差异

### PlanSkeleton 的新角色

**之前（错误）：**
- "真实楼板"
- "真实外墙轮廓"
- "直接生成的结构"

**现在（正确）：**
- ✅ Site Boundary / Build Domain
- ✅ 主轴
- ✅ 主体朝向
- ✅ 空间组织倾向

### StructuralSkeleton / Roof / Ridge 的新角色

**之前（错误）：**
- "必然生成的几何"
- "Plan 的必然结果"

**现在（正确）：**
- ✅ 候选结构
- ✅ 可用语义
- ✅ 只有在 AI 或规则选择了某种体量组合，才会被实例化

**换句话说：**
- 屋顶不是"Plan 的必然结果"
- 而是"某个体量组合的自然后果"

## 🧠 缺失的中间层：Building Mass Assembly

这是 Formacraft 真正该有的一层：

### 体量组合层职责

1. **体量原型选择**
   - Block（方盒子）
   - Slab（平板）
   - Tower（塔）
   - Wing（翼）

2. **体量组合操作**
   - 位移
   - 错动
   - 穿插
   - 悬挑

3. **产生事实上的结构**
   - 凹进
   - 挑出
   - 层间变化
   - 多个"事实上的平面"

## ⚠️ 必须避免的错误

### ❌ 不应该发生的事

1. 把 Roof / Slope / Ridge 当成"独立的几何生成系统"
2. 把 PlanSkeleton 理解成"建筑的真实几何平面"
3. 项目开始"像建模器"
4. 开始在做 Minecraft 并不需要的连续几何
5. 开始和最终"方块落地"脱节

### ✅ 正确的方向

1. Formacraft 是"建筑语义 → 方块装配系统"
2. 所有连续几何都只是"推理工具 / 规划工具 / 预览工具"
3. 最终世界里只有方块
4. 和 Minecraft 的本质完全一致
5. 和 AI 的优势完全一致
6. 和 Component / Socket 思路完全一致

## 📚 之前的 Work 如何"降级"（不是推翻）

### 保留的内容（放在正确位置）

1. **PlanSkeleton**
   - ✅ 保留
   - ✅ 角色：Site Boundary / Build Domain

2. **StructuralSkeleton / Roof / Ridge**
   - ✅ 保留
   - ✅ 角色：候选结构 / 可用语义
   - ✅ 只在体量组合后实例化

3. **Debug Overlay**
   - ✅ 保留
   - ✅ 角色：预览工具 / 调试工具

### 需要新增的内容

1. **Building Mass Assembly（体量组合层）**
   - 这是 Formacraft 真正缺失的核心层
   - 需要设计：体量原型、组合规则、实例化机制

## 🏆 总结

**Formacraft 不是"几何生成器"，而是"建筑语义 → 方块装配系统"。**

所有连续几何都只是"推理工具 / 规划工具 / 预览工具"。

最终世界里只有方块。

这个方向是极其正确的，而且：
- ✅ 和 Minecraft 的本质完全一致
- ✅ 和 AI 的优势完全一致
- ✅ 和之前设计的 Component / Socket 思路完全一致

---

**校准时间**: 2026-01-14  
**状态**: ✅ 架构方向已校准，避免过度几何化
