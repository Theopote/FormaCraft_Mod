# Formacraft 完整建筑生成系统设计

## 🎯 核心目标

**让 Formacraft 成为一个完整的、有机的建筑生成系统，而不是生成"半成品"**

### 用户期望的完整流程

```
用户输入（自然语言）
  ↓
AI 空间感知（理解范围、约束、地形）
  ↓
AI 布局规划（确定建筑群位置、朝向、关系）
  ↓
AI 体量设计（确定每个建筑的尺寸、形状）
  ↓
AI 功能部件（门窗洞口、屋顶、入口、虚实区分）
  ↓
AI 细节装饰（装饰元素、材质变化、细节处理）
  ↓
完整建筑（所有部分都已生成）
```

## 🔍 当前系统问题分析

### 问题 1：生成器覆盖不完整

**现状**：
- LLM 可能输出多种组件类型（如 `ROOF`, `COURTYARD_SPACE`, `GATE_STRUCTURE`, `PATH`）
- 但某些组件类型没有对应的生成器
- 导致生成不完整（只有部分组件被生成）

**证据**：
```
[Server thread/WARN] [FormaCraft] No generator for component: ROOF
[Server thread/WARN] [FormaCraft] No generator for component: COURTYARD_SPACE
[Server thread/WARN] [FormaCraft] No generator for component: GATE_STRUCTURE
[Server thread/WARN] [FormaCraft] No generator for component: PATH
```

### 问题 2：生成器功能过于简化

**现状**：
- 新系统的生成器（`common.generation.component`）功能较简单
- 只生成基础结构，缺少细节装饰
- 例如：`TowerGenerator` 只生成圆形塔楼的基础结构，没有窗户、楼梯、内部结构

### 问题 3：系统架构混乱

**现状**：
- 有两个生成器系统（`common.generation.component` 和 `common.generation.structure`）
- 它们服务于不同场景，但可能导致混乱
- 用户不清楚应该使用哪个系统

### 问题 4：工作流程不完整

**现状**：
- LLM 输出 `LlmPlan`（包含 components）
- `ComponentPlanCompiler` 编译 components
- 但可能某些组件没有生成器，导致生成不完整
- 没有后处理步骤（如细节装饰、材质变化）

## ✅ 解决方案

### 方案 1：完善生成器覆盖（立即执行）

**目标**：确保所有常见的组件类型都有对应的生成器

**步骤**：
1. 分析 LLM 可能输出的所有组件类型
2. 为缺失的组件类型创建生成器
3. 确保生成器能够生成完整的组件（不只是基础结构）

**优先级组件类型**：
- ✅ `ROOF` - 已创建
- ✅ `COURTYARD_SPACE` - 已创建
- ✅ `GATE_STRUCTURE` - 已创建
- ✅ `PATH` - 已创建
- ⚠️ `FACADE_WINDOWS` - 需要改进（当前复用 `EntranceGenerator`）
- ⚠️ `BALCONY` - 缺失
- ⚠️ `CHIMNEY` - 缺失
- ⚠️ `FOUNDATION` - 缺失
- ⚠️ `DECOR_DETAIL` - 缺失

### 方案 2：增强生成器功能（中期目标）

**目标**：让生成器能够生成完整的建筑，而不只是基础结构

**步骤**：
1. 增强现有生成器，添加细节装饰功能
2. 例如：`TowerGenerator` 应该生成：
   - 基础结构（墙体）
   - 窗户（根据 `features` 中的 `windows`）
   - 楼梯（根据 `features` 中的 `stairs`）
   - 屋顶（根据 `features` 中的 `roof`）
   - 装饰（根据 `features` 中的 `decor`）

**实现策略**：
- 生成器应该检查 `Component.features` 列表
- 根据 `features` 决定生成哪些细节
- 例如：如果 `features` 包含 `"windows"`，则生成窗户

### 方案 3：建立完整的生成流程（长期目标）

**目标**：确保从 LLM 输出到最终建筑的完整链路

**完整流程**：
```
1. LLM 输出 LlmPlan
   ↓
2. ComponentPlanCompiler 编译 components
   ↓
3. 每个 ComponentGenerator 生成 BlockPatch
   ↓
4. 后处理步骤（可选）：
   - 细节装饰增强
   - 材质变化
   - 地形适应
   ↓
5. PatchFilterPipeline 过滤（禁区、轮廓、选区）
   ↓
6. 预览 / 应用
```

**后处理步骤**：
- **细节装饰增强**：在基础结构上添加装饰元素
- **材质变化**：根据风格配置调整材质
- **地形适应**：根据地形策略调整建筑高度

### 方案 4：统一系统架构（长期目标）

**目标**：明确各个系统的作用，避免混乱

**系统分工**：
- **`common.generation.component`**：用于 LLM 语义组件生成（新系统）
- **`common.generation.structure`**：用于传统 BuildingSpec 生成（传统系统）
- **未来**：创建适配器，让传统生成器可以被新系统调用

## 📋 实施计划

### 阶段 1：完善生成器覆盖（立即执行）

**任务**：
1. ✅ 创建 `RoofGenerator`
2. ✅ 创建 `CourtyardSpaceGenerator`
3. ✅ 创建 `GateStructureGenerator`
4. ✅ 创建 `PathGenerator`
5. ⚠️ 创建 `FacadeWindowsGenerator`（改进 `FACADE_WINDOWS`）
6. ⚠️ 创建 `BalconyGenerator`
7. ⚠️ 创建 `ChimneyGenerator`
8. ⚠️ 创建 `FoundationGenerator`
9. ⚠️ 创建 `DecorDetailGenerator`

### 阶段 2：增强生成器功能（中期目标）

**任务**：
1. 增强 `TowerGenerator`：添加窗户、楼梯、内部结构
2. 增强 `MassMainGenerator`：添加门窗、屋顶、装饰
3. 增强 `WallGenerator`：添加窗户、装饰、细节
4. 增强 `RoofGenerator`：添加檐口、装饰、细节

**实现方式**：
- 检查 `Component.features` 列表
- 根据 `features` 决定生成哪些细节
- 使用 `PaletteLibrary` 获取材质

### 阶段 3：建立完整的生成流程（长期目标）

**任务**：
1. 创建 `PostProcessor` 接口
2. 实现 `DetailEnhancementPostProcessor`
3. 实现 `MaterialVariationPostProcessor`
4. 实现 `TerrainAdaptationPostProcessor`
5. 在 `ComponentPlanCompiler` 中集成后处理步骤

### 阶段 4：统一系统架构（长期目标）

**任务**：
1. 创建 `StructureGeneratorAdapter`（将传统生成器包装为新系统生成器）
2. 在 `ComponentGeneratorRegistry` 中注册适配器
3. 文档说明各个系统的作用

## 🎯 成功标准

### 短期（阶段 1）

- ✅ 所有常见的组件类型都有对应的生成器
- ✅ 不再出现 "No generator for component" 警告
- ✅ 生成的建筑包含所有 LLM 指定的组件

### 中期（阶段 2）

- ✅ 生成器能够生成完整的建筑（包括细节装饰）
- ✅ 生成的建筑质量显著提升
- ✅ 用户满意度提升

### 长期（阶段 3-4）

- ✅ 完整的生成流程（从 LLM 输出到最终建筑）
- ✅ 系统架构清晰，易于维护
- ✅ 生成器功能强大，能够生成各种风格的建筑

## 📝 总结

**当前问题**：
1. 生成器覆盖不完整
2. 生成器功能过于简化
3. 系统架构混乱
4. 工作流程不完整

**解决方案**：
1. 完善生成器覆盖（立即执行）
2. 增强生成器功能（中期目标）
3. 建立完整的生成流程（长期目标）
4. 统一系统架构（长期目标）

**下一步行动**：
1. 立即创建缺失的生成器
2. 增强现有生成器功能
3. 建立完整的生成流程
4. 统一系统架构

