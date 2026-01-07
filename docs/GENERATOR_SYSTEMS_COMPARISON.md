# Formacraft 模组中两个 Generator 系统的区别

## 概述

Formacraft 模组中有两个不同的 generator 文件夹，它们服务于不同的生成场景和架构层次：

1. **`com.formacraft.common.generator`** - **组件生成器系统**（新系统，K3）
2. **`com.formacraft.server.generator`** - **结构生成器系统**（传统系统）

---

## 1. `com.formacraft.common.generator` - 组件生成器系统

### 位置
```
src/main/java/com/formacraft/common/generator/
```

### 核心接口
- **`ComponentGenerator`** - 组件生成器接口
  ```java
  List<BlockPatch> generate(SemanticComponent component);
  ```

### 设计目标
- **处理 LLM 输出的语义组件**（SemanticComponent）
- **将语义组件转换为 BlockPatch 列表**
- **支持组件级别的生成**（TOWER, WALL, GATE, ROAD 等）
- **用于新的 LLM → Component → BlockPatch 流水线**

### 输入/输出
- **输入**：`SemanticComponent`（来自 LLM 的 JSON 输出）
- **输出**：`List<BlockPatch>`（相对坐标的方块补丁）

### 已实现的生成器
- `TowerGenerator` - 塔楼
- `KeepGenerator` - 要塞
- `WallGenerator` - 墙体
- `GateGenerator` - 门/门楼
- `RoadGenerator` - 道路

### 使用场景
- **LLM 输出解析后**：`ComponentPlanCompiler` 使用这些生成器
- **语义组件生成**：将 LLM 描述的组件（如 "一个 5x5 的塔楼"）转换为实际的方块
- **K3 系统**：与 `ComponentPlanCompiler`、`SemanticComponent` 配合使用

### 注册表
- **`GeneratorRegistry`** - 按 `componentType` 字符串注册和查找生成器

### 特点
- ✅ **轻量级**：只负责单个组件的生成
- ✅ **语义驱动**：基于 LLM 的语义描述
- ✅ **可组合**：多个组件可以组合成复杂建筑
- ✅ **相对坐标**：输出是相对 anchor 的 BlockPatch

---

## 2. `com.formacraft.server.generator` - 结构生成器系统

### 位置
```
src/main/java/com/formacraft/server/generator/
```

### 核心接口
- **`StructureGenerator`** - 结构生成器接口
  ```java
  GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world);
  ```

### 设计目标
- **处理完整的建筑规格**（BuildingSpec）
- **生成完整的建筑结构**（GeneratedStructure）
- **支持传统的地标建筑生成**（如埃菲尔铁塔、长城、城堡等）
- **用于传统的 BuildingSpec → GeneratedStructure 流水线**

### 输入/输出
- **输入**：`BuildingSpec`（完整的建筑规格，包含类型、尺寸、风格等）
- **输出**：`GeneratedStructure`（包含所有计划放置的方块，绝对坐标）

### 已实现的生成器（示例）
- `HouseGenerator` - 房屋生成器
- `TowerGenerator` - 塔楼生成器
- `BridgeGenerator` - 桥梁生成器
- `WallGenerator` - 墙体生成器
- `EiffelTowerGenerator` - 埃菲尔铁塔
- `GreatWallGenerator` - 长城
- `CastleCompoundGenerator` - 城堡复合体
- `GothicCathedralGenerator` - 哥特式大教堂
- `TulouGenerator` - 土楼
- `TempleOfHeavenGenerator` - 天坛
- ... 等 50+ 个专用生成器

### 使用场景
- **传统建筑生成**：用户请求 "建一个城堡"，系统使用 `CastleCompoundGenerator`
- **地标建筑**：通过 `GeneratorRouter` 路由到专用生成器
- **Blueprint 系统**：`BlueprintStructureGenerator` 使用这些生成器
- **Assembly 系统**：`MetaAssemblyGenerator` 用于参数化生成

### 路由系统
- **`GeneratorRouter`** - 根据 BuildingSpec 路由到合适的生成器
  - 按 `styleProfileId` 路由
  - 按 `assembly` 路由（MetaAssemblyGenerator）
  - 按 `blueprint` 路由（BlueprintStructureGenerator）
  - 按 `template` 路由
  - 按 `archetype` 路由（地标建筑）
  - 按 `BuildingType` 路由（传统类型）

### 特点
- ✅ **完整建筑**：生成整个建筑结构
- ✅ **绝对坐标**：输出是绝对世界坐标
- ✅ **专用生成器**：每个地标建筑有专门的生成器
- ✅ **传统系统**：与 BuildingSpec 系统深度集成

---

## 对比总结

| 特性 | `common.generator` | `server.generator` |
|------|-------------------|-------------------|
| **系统类型** | 组件生成器（新系统） | 结构生成器（传统系统） |
| **输入** | `SemanticComponent` | `BuildingSpec` |
| **输出** | `List<BlockPatch>`（相对坐标） | `GeneratedStructure`（绝对坐标） |
| **粒度** | 组件级别（TOWER, WALL, GATE） | 建筑级别（完整建筑） |
| **使用场景** | LLM 语义组件生成 | 传统建筑生成、地标建筑 |
| **注册方式** | `GeneratorRegistry`（字符串键） | `GeneratorRouter`（多级路由） |
| **数量** | ~5 个基础组件生成器 | ~50+ 个专用生成器 |
| **架构层次** | K3 系统的一部分 | 传统 BuildingSpec 系统 |

---

## 使用流程对比

### `common.generator` 流程（新系统）
```
LLM JSON
  ↓
LlmPlan (components[])
  ↓
ComponentPlanCompiler
  ↓
SemanticComponent
  ↓
ComponentGenerator.generate()
  ↓
List<BlockPatch> (相对坐标)
  ↓
Preview / Apply
```

### `server.generator` 流程（传统系统）
```
BuildingSpec
  ↓
GeneratorRouter.route()
  ↓
StructureGenerator.generate()
  ↓
GeneratedStructure (绝对坐标)
  ↓
BuildExecutionService
```

---

## 共存关系

**两个系统可以共存**：

1. **新系统**（`common.generator`）用于：
   - LLM 输出的语义组件生成
   - 组件级别的灵活组合
   - K3 流水线

2. **传统系统**（`server.generator`）用于：
   - 传统 BuildingSpec 生成
   - 地标建筑的专用生成器
   - Blueprint 和 Assembly 系统

3. **未来可能整合**：
   - 传统生成器可以包装为 `ComponentGenerator`
   - 新系统可以生成完整的 `BuildingSpec`
   - 两个系统可以互相调用

---

## 总结

- **`common.generator`** = **组件生成器**（新系统，K3，语义驱动，相对坐标）
- **`server.generator`** = **结构生成器**（传统系统，完整建筑，绝对坐标）

两者服务于不同的场景和架构层次，可以共存并互相补充。

