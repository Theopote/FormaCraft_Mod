# 智能生成器路由解决方案

> **当前实现（Phase 2+）**：`UnifiedGeneratorRouter` 为构件层统一门面；`SmartGeneratorRouter` 已弃用并委托至前者。整栋回退经 `StructureGeneratorAdaptor` → `GenerationHub.routeStructure()`。

## 🎯 问题

用户不知道后台有两套系统，输入建造要求时不会考虑如何启用哪套系统。系统应该自动选择最适合的生成器。

## ✅ 解决方案

### 核心设计：智能路由系统

创建一个**智能路由层**，自动选择最适合的生成器，用户完全透明。

### 架构设计

```
用户输入
  ↓
LlmPlan（LLM 输出）
  ↓
ComponentPlanCompiler
  ↓
UnifiedGeneratorRouter（统一路由，SmartGeneratorRouter 已弃用）
  ↓
┌─────────────────┬─────────────────┐
│  新系统优先     │  传统系统回退   │
│  ComponentGen  │  StructureGen   │
│  (轻量级)       │  (完整功能)     │
└─────────────────┴─────────────────┘
  ↓
List<BlockPatch>
```

### 实现方案

#### 1. UnifiedGeneratorRouter（统一路由器）

**位置**：`src/main/java/com/formacraft/common/generation/component/adaptor/UnifiedGeneratorRouter.java`

**功能**：
- 优先使用构件层（`common.generation.component`）的 `ComponentGenerator`
- 受控回退到整栋层（`common.generation.structure`），仅显式请求或未注册整栋类型时触发
- `SmartGeneratorRouter` 保留为弃用委托，不再单独维护路由逻辑

**路由逻辑**：
```java
1. 尝试新系统（ComponentGenerator）
   - 如果存在且生成成功 → 使用新系统
   - 如果不存在或生成失败 → 继续

2. 回退到传统系统（StructureGenerator）
   - 如果 world 可用 → 使用适配器调用传统系统
   - 如果 world 不可用 → 返回空列表
```

#### 2. StructureGeneratorAdaptor（适配器）

**位置**：`src/main/java/com/formacraft/common/generation/component/adaptor/StructureGeneratorAdaptor.java`

**功能**：
- 将传统系统的 `StructureGenerator` 适配为新系统的 `ComponentGenerator`
- 处理输入/输出格式转换：
  - 输入：`SemanticComponent` → `BuildingSpec`
  - 输出：`GeneratedStructure` → `List<BlockPatch>`

**映射规则**：
- `MASS_MAIN`, `MASS_SECONDARY` → `BuildingType.HOUSE`
- `TOWER` → `BuildingType.TOWER`
- `WALL` → `BuildingType.WALL`
- `CASTLE`, `KEEP` → `BuildingType.CASTLE`
- 其他 → `BuildingType.CUSTOM`

#### 3. ComponentPlanCompiler 集成

**修改**：`src/main/java/com/formacraft/common/compiler/ComponentPlanCompiler.java`

**变更**：
- 不再直接调用 `ComponentGeneratorRegistry.getGenerator()`
- 改为调用 `SmartGeneratorRouter.generate()`
- 自动获得智能路由能力

## 🔄 完整流程

### 之前（用户需要知道两套系统）

```
用户输入 → LlmPlan → ComponentPlanCompiler
  ↓
只能使用新系统（common.generation.component）
  ↓
如果新系统没有生成器 → 报错或跳过
```

### 现在（用户完全透明）

```
用户输入 → LlmPlan → ComponentPlanCompiler
  ↓
SmartGeneratorRouter.generate()
  ↓
1. 尝试新系统（ComponentGenerator）
   - 如果成功 → 使用新系统 ✅
   - 如果失败 → 继续
  ↓
2. 回退到传统系统（StructureGenerator）
   - 如果成功 → 使用传统系统 ✅
   - 如果失败 → 返回空列表
```

## 📊 优势

### 1. 用户透明
- ✅ 用户不需要知道有两套系统
- ✅ 系统自动选择最适合的生成器
- ✅ 用户体验一致

### 2. 向后兼容
- ✅ 新系统继续工作（优先使用）
- ✅ 传统系统作为回退（保证功能完整）
- ✅ 不需要修改现有代码

### 3. 渐进式迁移
- ✅ 可以逐步将传统系统的功能迁移到新系统
- ✅ 迁移过程中，传统系统作为回退保证功能
- ✅ 迁移完成后，可以移除传统系统

### 4. 功能完整性
- ✅ 新系统功能简单时，自动使用传统系统
- ✅ 新系统功能完善后，优先使用新系统
- ✅ 确保生成的建筑质量

## 🎯 使用场景示例

### 场景 1：新系统有生成器

```
ComponentType: "TOWER"
  ↓
SmartGeneratorRouter.generate()
  ↓
1. 尝试新系统：ComponentGeneratorRegistry.getGenerator("TOWER")
   - 找到 TowerGenerator ✅
   - 生成成功 ✅
  ↓
使用新系统生成的结果
```

### 场景 2：新系统没有生成器

```
ComponentType: "COMPLEX_HOUSE"
  ↓
SmartGeneratorRouter.generate()
  ↓
1. 尝试新系统：ComponentGeneratorRegistry.getGenerator("COMPLEX_HOUSE")
   - 没有找到生成器 ❌
  ↓
2. 回退到传统系统：StructureGeneratorAdaptor.createFor("COMPLEX_HOUSE")
   - 映射到 BuildingType.HOUSE ✅
   - 使用 HouseGenerator ✅
   - 生成成功 ✅
  ↓
使用传统系统生成的结果
```

### 场景 3：新系统生成失败

```
ComponentType: "MASS_MAIN"
  ↓
SmartGeneratorRouter.generate()
  ↓
1. 尝试新系统：ComponentGeneratorRegistry.getGenerator("MASS_MAIN")
   - 找到 MassMainGenerator ✅
   - 生成时抛出异常 ❌
  ↓
2. 回退到传统系统：StructureGeneratorAdaptor.createFor("MASS_MAIN")
   - 映射到 BuildingType.HOUSE ✅
   - 使用 HouseGenerator ✅
   - 生成成功 ✅
  ↓
使用传统系统生成的结果
```

## 📝 实现细节

### 1. 组件类型映射

```java
MASS_MAIN, MASS_SECONDARY → HOUSE
TOWER → TOWER
WALL → WALL
CASTLE, KEEP → CASTLE
其他 → CUSTOM
```

### 2. 回退策略

- **优先级 1**：新系统（`common.generation.component`）
- **优先级 2**：传统系统（`common.generation.structure`）
- **优先级 3**：返回空列表（记录警告）

### 3. 错误处理

- 新系统失败时，自动尝试传统系统
- 传统系统失败时，记录错误但不中断整个流程
- 所有错误都有日志记录

## ✅ 总结

**核心价值**：
- ✅ **用户透明**：用户不需要知道有两套系统
- ✅ **自动选择**：系统自动选择最适合的生成器
- ✅ **功能完整**：确保生成的建筑质量
- ✅ **向后兼容**：保持现有功能不变

**实现方式**：
- ✅ 创建 `SmartGeneratorRouter`（智能路由层）
- ✅ 创建 `StructureGeneratorAdaptor`（适配器）
- ✅ 集成到 `ComponentPlanCompiler`

**效果**：
- ✅ 用户输入建造要求，系统自动选择最适合的生成器
- ✅ 新系统功能简单时，自动使用传统系统
- ✅ 新系统功能完善后，优先使用新系统
- ✅ 确保生成的建筑质量

