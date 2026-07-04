# PlanProgramCompiler 集成指南

## 🎯 概述

`PlanProgramCompiler` 是新的编译管线入口点，用于处理 PlanProgram/PlanSkeleton 模式。

与传统 `ComponentPlanCompiler` 的关系：
- **ComponentPlanCompiler**：传统 `components[]` 模式（向后兼容）
- **PlanProgramCompiler**：新的 PlanProgram → Skeleton 模式（可选使用）

## 📋 LlmPlan 增强

LlmPlan 现在支持可选的 PlanProgram/PlanSkeleton 字段：

```java
public record LlmPlan(
    // ... 现有字段 ...
    
    // 新增：PlanProgram 模式（可选）
    @JsonProperty("plan_program") PlanProgram planProgram,
    @JsonProperty("plan_skeleton") PlanSkeleton planSkeleton
) {
    // 检查是否使用 PlanProgram 模式
    public boolean usesPlanProgramMode();
    
    // 检查是否使用传统 components 模式
    public boolean usesComponentMode();
}
```

## 🔄 使用方式

### 方式 1：从 PlanProgram 编译

```java
PlanProgram planProgram = ...; // 从 JSON 解析或 LLM 生成
BlockPos globalAnchor = ...;
ServerWorld world = ...;

List<BlockPatch> patches = PlanProgramCompiler.compile(
    planProgram,
    globalAnchor,
    world
);
```

### 方式 2：从 PlanSkeleton 编译

```java
PlanSkeleton planSkeleton = ...; // 从 JSON 解析或转换而来
List<BlockPatch> patches = PlanProgramCompiler.compileFromPlanSkeleton(
    planSkeleton,
    globalAnchor,
    world
);
```

### 方式 3：从 JSON 自动检测

```java
String json = ...; // 可能是 PlanProgram 或 PlanSkeleton JSON
List<BlockPatch> patches = PlanProgramCompiler.compileFromJson(
    json,
    globalAnchor,
    world
);
```

## 🔗 与现有系统的集成

### 在 FormaCraftNetworking 中的集成

```java
// 处理 LlmPlan
LlmPlan llmPlan = LlmPlanParser.parseAndValidate(llmPlanJson);

if (llmPlan.usesPlanProgramMode()) {
    // 使用新的 PlanProgram 编译管线
    PlanSkeleton planSkeleton = null;
    
    if (llmPlan.planSkeleton() != null) {
        planSkeleton = llmPlan.planSkeleton();
    } else if (llmPlan.planProgram() != null) {
        planSkeleton = PlanProgramToPlanSkeletonConverter.convert(llmPlan.planProgram());
    }
    
    if (planSkeleton != null) {
        List<BlockPatch> patches = PlanProgramCompiler.compileFromPlanSkeleton(
            planSkeleton,
            planOrigin,
            serverWorld
        );
        // ... 处理 patches
    }
} else if (llmPlan.usesComponentMode()) {
    // 使用传统的 ComponentPlanCompiler
    List<BlockPatch> patches = ComponentPlanCompiler.compile(
        llmPlan,
        planOrigin,
        serverWorld,
        terrainSampler,
        true
    );
    // ... 处理 patches
}
```

## 📊 编译流程

```
LlmPlan
  ↓ (检查 usesPlanProgramMode())
PlanProgram / PlanSkeleton
  ↓
PlanProgramCompiler.compile()
  ↓
PlanSkeleton (如果输入是 PlanProgram)
  ↓
PlanToSkeletonCompiler.compile()
  ↓
CompiledSkeleton (ExecutableSkeletonPlan + ExtrudedSolid)
  ↓ (TODO: 集成 Generator)
Generator.generate()
  ↓
BlockPatch
```

## ⚠️ 当前状态

**v1 限制：**
- ✅ PlanProgram → PlanSkeleton 转换已实现
- ✅ PlanSkeleton → StructuralSkeleton 转换已实现
- ✅ StructuralSkeleton → ExtrudedSolid 转换已实现
- ⚠️ ExecutableSkeletonPlan → Generator → BlockPatch **待集成**

**当前行为：**
- PlanProgramCompiler 会成功执行到 CompiledSkeleton
- 但返回空的 BlockPatch 列表（等待 Generator 集成）

## 🔮 未来集成

### 完成 Generator 集成后

```java
// 在 PlanProgramCompiler.compileFromPlanSkeleton() 中
CompiledSkeleton compiled = ...;

List<BlockPatch> patches = new ArrayList<>();
for (ExecutableSkeletonPlan plan : compiled.getSkeletons()) {
    // 使用现有的 Generator 系统
    ISkeletonGenerator generator = SkeletonGeneratorRegistry.getGenerator(plan.type);
    if (generator != null) {
        GenerationContext ctx = new GenerationContext(world, globalAnchor, random, maxOps);
        List<BlockPatch> planPatches = generator.generate(ctx, plan);
        patches.addAll(planPatches);
    }
}

return patches;
```

## ✅ 优势

1. **向后兼容**：传统 components[] 模式仍然可用
2. **渐进迁移**：可以逐步迁移到 PlanProgram 模式
3. **统一入口**：LlmPlan 可以包含两种模式
4. **灵活切换**：根据 LlmPlan 内容自动选择编译管线
