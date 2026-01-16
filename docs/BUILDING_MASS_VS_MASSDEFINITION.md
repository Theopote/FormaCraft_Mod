# BuildingMass vs MassDefinition 说明

## 🎯 两个系统的关系

### MassDefinition（旧系统，保留）

**位置：** `src/main/java/com/formacraft/common/mass/MassDefinition.java`

**特点：**
- 使用连续几何（`Box`）
- 用于派生 StructuralSkeleton 的中间表示
- 已经集成到现有系统中

**用途：**
- 作为过渡阶段的实现
- 可以与新的 BuildingMass 系统共存

### BuildingMass（新系统，MVP）

**位置：** `src/main/java/com/formacraft/common/mass/BuildingMass.java`

**特点：**
- ✅ 使用离散方块位置（`AreaMask`）
- ✅ 完全贴合 Minecraft 的方块世界
- ✅ 基于规则的判断，不是几何运算
- ✅ 这是架构校准后的正确方向

**用途：**
- 未来的标准实现
- 应该替代 MassDefinition（逐步迁移）

## 📋 关键差异

| 特性 | MassDefinition | BuildingMass |
|------|---------------|--------------|
| **几何表示** | 连续几何（Box） | 离散方块位置（AreaMask） |
| **高度表示** | double (连续) | int (离散方块坐标) |
| **判断方式** | 几何运算 | 规则判断（contains） |
| **适配 Minecraft** | 需要转换 | 天然适配 |
| **AI 友好** | 中等 | 高（规则描述） |
| **状态** | 旧系统（保留） | 新系统（MVP） |

## 🔄 迁移路径

### 阶段 1：共存（当前）

- ✅ MassDefinition 继续使用（保持现有系统可用）
- ✅ BuildingMass 作为新的 MVP 实现

### 阶段 2：逐步迁移

- ⏳ 新的代码使用 BuildingMass
- ⏳ 旧的代码逐步迁移到 BuildingMass

### 阶段 3：完全迁移

- ⏳ MassDefinition 标记为 deprecated
- ⏳ 所有代码使用 BuildingMass

## 🎯 推荐使用

**新的开发应该使用 BuildingMass（MVP），因为：**
- ✅ 完全贴合 Minecraft 的方块世界
- ✅ 基于离散方块位置的规则判断
- ✅ 更适合 AI 生成
- ✅ 避免连续几何运算

---

**说明时间**: 2026-01-14  
**状态**: ✅ BuildingMass MVP 已实现，与 MassDefinition 共存
