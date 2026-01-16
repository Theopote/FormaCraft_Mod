# 架构校准重构计划

## 🎯 重构目标

将 Formacraft 从"过度几何化"校准到"建筑语义 → 方块装配系统"。

## 📋 重构原则

### 核心原则

1. **Formacraft 最终只在 Minecraft 中放置方块**
   - 所有连续几何只是"推理工具 / 规划工具 / 预览工具"
   - 最终世界里只有方块

2. **Plan = Domain（范围约束），不是真实平面**
   - PlanSkeleton 的角色：Site Boundary / Build Domain
   - 提供范围限制、朝向参考、作为体量组合的"舞台"

3. **StructuralSkeleton = 候选结构，不是必然生成**
   - 只在体量组合后被实例化
   - 屋顶不是"Plan 的必然结果"，而是"体量组合的自然后果"

## 🔄 需要修改的关键点

### 1. PlanSkeleton 文档和注释更新

**文件：** `src/main/java/com/formacraft/common/llm/dto/PlanSkeleton.java`

**修改：**
- ✅ 更新类注释，明确 PlanSkeleton 是"Site Boundary / Domain"
- ✅ 强调它是"范围约束"，不是"真实平面"

### 2. PlanSkeletonToStructuralSkeletonConverter 角色调整

**文件：** `src/main/java/com/formacraft/common/llm/converter/PlanSkeletonToStructuralSkeletonConverter.java`

**当前问题：**
- ❌ 直接生成 StructuralSkeleton（Floor / Wall / Roof）
- ❌ 把 Plan 当成"真实平面"来处理

**修改方案：**
- ✅ 重命名或重构为：`PlanSkeletonDomainValidator` 或 `PlanSkeletonToMassAssemblyHelper`
- ✅ 不直接生成结构，而是：
  - 验证 Plan 作为 Domain 的合理性
  - 提取 Domain 信息（范围、朝向、参考点）
  - 为体量组合提供输入

**或者：**
- ✅ 保留转换器，但改为生成"候选结构模板"
- ✅ 明确标注这些是"候选"，不是"必然实例化"

### 3. StructuralSkeleton 注释更新

**文件：** `src/main/java/com/formacraft/common/llm/dto/StructuralSkeleton.java`

**修改：**
- ✅ 更新类注释，明确 StructuralSkeleton 是"候选结构 / 可用语义"
- ✅ 强调它只在体量组合后被实例化

### 4. 编译器流程调整

**文件：** `src/main/java/com/formacraft/common/llm/compiler/PlanToSkeletonCompiler.java`

**当前问题：**
- ❌ Plan → StructuralSkeleton → ExecutableSkeletonPlan 的直接流程

**修改方案：**
- ✅ 添加注释说明当前流程是"简化版本"
- ✅ 标注未来需要插入 Building Mass Assembly 层
- ✅ 或者：将 StructuralSkeleton 生成标记为"候选生成"，而不是"必然生成"

### 5. 文档更新

**需要更新的文档：**
- `docs/PLAN_SKELETON_SCHEMA_V1.md` - 更新 PlanSkeleton 的定位
- `docs/PLAN_SKELETON_TO_STRUCTURAL_CONVERTER.md` - 更新转换器的角色
- `docs/COMPLETE_ROOF_SYSTEM_V2.md` - 添加架构校准说明

## 📝 重构步骤

### Phase 1：文档和注释更新（低风险）

1. ✅ 更新 PlanSkeleton 注释：明确它是 Domain
2. ✅ 更新 StructuralSkeleton 注释：明确它是候选结构
3. ✅ 更新转换器注释：说明当前是简化流程
4. ✅ 更新相关文档

### Phase 2：代码结构调整（中风险）

1. ⏳ 将 PlanSkeletonToStructuralSkeletonConverter 标记为"候选生成器"
2. ⏳ 添加 Domain 验证逻辑
3. ⏳ 为未来的 Building Mass Assembly 层预留接口

### Phase 3：Building Mass Assembly 实现（高风险，后续）

1. ⏳ 设计 MassDefinition / MassAssembly 数据结构
2. ⏳ 实现简单的体量组合
3. ⏳ 从体量组合派生 StructuralSkeleton

## ⚠️ 注意事项

### 不要做的事

1. ❌ 不要删除现有代码
2. ❌ 不要破坏现有流程
3. ❌ 不要立即实现 Building Mass Assembly（这是大改动）

### 要做的事

1. ✅ 更新注释和文档，明确各层的正确角色
2. ✅ 为未来的 Building Mass Assembly 预留接口
3. ✅ 标记当前流程为"简化版本"或"候选生成"

## 🎯 第一步：文档和注释更新

让我们从最安全、最关键的开始：

1. **PlanSkeleton.java** - 更新注释，明确它是 Domain
2. **StructuralSkeleton.java** - 更新注释，明确它是候选结构
3. **PlanSkeletonToStructuralSkeletonConverter.java** - 更新注释，说明角色调整
4. **相关文档** - 更新架构说明

---

**重构时间**: 2026-01-14  
**状态**: 📋 重构计划制定完成，准备开始 Phase 1
