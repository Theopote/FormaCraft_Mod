# Component Assembler 建议评估

## 建议概述

建议在 Skeleton → Generator → Semantic 之上增加一层：
- **Component Assembler / Component Graph**
- 决定"城堡/土楼/寺庙/办公楼"由哪些语义组件组成

## 核心思想

```
Skeleton = 空间骨架
Component = 语义器官（墙/塔/中庭/屋顶/门）
Assembler = 把器官挂到骨架上的规则系统
```

## 与现有系统的关系

### 现有系统

1. **MetaAssemblyEngine**：
   - 使用 `AssemblySpec`，包含 `ops` 列表
   - 底层操作序列（CLEAR_BOX, FILL_BOX, ANCHOR_FOOTPRINT 等）
   - 更偏向"如何做"而不是"做什么"

2. **AssemblySpec**：
   - 已经有 `components` 概念
   - 但 components 是更底层的几何体（SHELL_BOX 等）

3. **BuildingGenome**：
   - 描述空间逻辑与生成倾向
   - 但不直接映射到组件装配

### 建议系统的特点

1. **更高级的抽象**：
   - ComponentType 是建筑语义组件（ENCLOSING_WALL, TOWER, COURTYARD）
   - 而不是底层操作或几何体

2. **与 Skeleton 的明确集成**：
   - Component 明确装配到 Skeleton 上
   - Assembler 知道如何将 Component 映射到 Skeleton 的特定位置

3. **清晰的层次结构**：
   ```
   Skeleton (空间骨架)
     ↓
   Component (语义器官)
     ↓
   SemanticPlacementOp (语义操作)
     ↓
   BlockPatch (方块补丁)
   ```

## 价值评估

### ✅ 高价值点

1. **LLM 友好**：
   - LLM 只需要输出组件列表，不需要知道底层实现
   - 例如：`{"skeleton": "RADIAL_RING", "components": ["ENCLOSING_WALL", "INNER_COURTYARD", "ROOF_RING"]}`

2. **可扩展性强**：
   - 新增组件类型只需实现新的 Assembler
   - 不破坏现有系统

3. **与现有系统互补**：
   - 可以与 MetaAssemblyEngine 共存
   - Component Assembler 用于高级语义装配
   - MetaAssemblyEngine 用于底层操作序列

4. **清晰的职责分离**：
   - Skeleton：空间组织
   - Component：建筑语义
   - Palette：风格表现
   - AI：只负责规划

### ⚠️ 需要考虑的问题

1. **与 MetaAssemblyEngine 的关系**：
   - 两者功能有重叠
   - 需要明确使用场景和边界

2. **实现复杂度**：
   - 需要为每个 ComponentType 实现 Assembler
   - 需要处理 Component 与 Skeleton 的映射关系

3. **性能考虑**：
   - 多层抽象可能影响性能
   - 需要优化关键路径

## 建议的实现策略

### 阶段 1：基础实现（推荐）

实现核心组件：
- ComponentType 枚举
- ComponentSpec
- ComponentPlan
- ComponentAssembler 接口
- ComponentAssemblerRegistry
- ComponentAssemblyPipeline

### 阶段 2：示例实现

实现几个关键组件：
- ENCLOSING_WALL（围墙）
- TOWER（塔）
- COURTYARD（中庭）
- ROOF_RING（环形屋顶）

### 阶段 3：集成

- 与现有的 SkeletonBuildPipeline 集成
- 与 SemanticResolver 集成
- 提供 LLM 输出格式规范

## 结论

### ✅ 建议非常有价值

**理由**：
1. 提供了更高级的抽象层，让 LLM 更容易输出合理的建筑结构
2. 与现有的 Skeleton/Semantic 系统完美契合
3. 清晰的层次结构，职责分离明确
4. 可扩展性强，不破坏现有系统

**建议**：
- 实现这个系统
- 与现有 MetaAssemblyEngine 共存，各自负责不同层次
- 逐步实现，先做核心框架，再添加具体组件

**预期收益**：
- LLM 输出更简洁、更易理解
- 支持更复杂的建筑类型（土楼、天坛、城堡等）
- 系统更易扩展和维护

