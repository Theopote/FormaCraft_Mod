# PlanToSkeletonCompiler 核心模块文档

## 🎯 设计目标

**PlanToSkeletonCompiler 不是：**
- ❌ 自动生成构件
- ❌ 负责风格
- ❌ 负责材质

**它只做一件事：**
把 PlanSkeleton（2D 语义骨架）编译成一组可被 Socket / Skeleton 系统消费的 3D Skeleton

## 📋 整体架构位置

```
LLM
  ↓
PlanProgram
  ↓
PlanSkeleton
  ↓
PlanToSkeletonCompiler   ← ★ 本次设计
  ↓
CompiledSkeleton (ExecutableSkeletonPlan + SkeletonGraph)
  ↓
SocketProvider
  ↓
AssemblyPlanner / AutoAssembler
```

👉 这是 AI → 几何 → 构件 的关键编译节点。

## 🧩 核心接口

### PlanToSkeletonCompiler

**位置：** `src/main/java/com/formacraft/common/llm/compiler/PlanToSkeletonCompiler.java`

**接口定义：**
```java
public interface PlanToSkeletonCompiler {
    CompiledSkeleton compile(
        PlanSkeleton planSkeleton,
        PlanCompileContext context
    );
}
```

**设计说明：**
- 单一入口
- 不暴露中间步骤
- 便于以后：多实现（v1 / v2）、调试 / 可视化

## 📦 输出结构：CompiledSkeleton

**位置：** `src/main/java/com/formacraft/common/llm/compiler/CompiledSkeleton.java`

**包含：**
- `skeletons`: ExecutableSkeletonPlan 列表（与现有系统兼容）
- `graph`: SkeletonGraph（用于调试、AI 修正、UI 高亮）

**SkeletonGraph 的用途：**
- 哪面墙属于哪个 zone
- 哪些 skeleton 是相邻的
- 哪些是 courtyard / exterior / shared

👉 这是调试、AI 修正、UI 高亮的基础

## 📥 输入结构：PlanCompileContext

**位置：** `src/main/java/com/formacraft/common/llm/compiler/PlanCompileContext.java`

**核心思想：** AI 不决定"怎么高"，系统策略决定。

**包含：**
- `terrain`: 地形上下文（可选）
- `heightStrategy`: 高度策略（FLAT / ADAPTIVE / STEPPED / SLOPED）
- `roofStrategy`: 屋顶策略（UNIFIED / PARTITIONED / VARIED）
- `defaultWallHeight`: 默认墙高
- `defaultFloorThickness`: 默认地板厚度

**创建方法：**
```java
// 默认上下文
PlanCompileContext context = PlanCompileContext.createDefault();

// 带地形的上下文
PlanCompileContext context = PlanCompileContext.createWithTerrain(world, origin);
```

## 🔄 内部步骤（管线式结构）

### Step 1: PlanNormalizationStep（规范化）

**位置：** `src/main/java/com/formacraft/common/llm/compiler/steps/PlanNormalizationStep.java`

**作用：**
- 合并重复 edges
- 补齐缺失连接
- 修正不闭合轮廓

👉 非常适合处理 LLM 的"半正确输出"

### Step 2: StructuralExtractionStep（结构提取）

**位置：** `src/main/java/com/formacraft/common/llm/compiler/steps/StructuralExtractionStep.java`

**作用：** 将 PlanSkeleton 的"2D 几何语义"转换为 StructuralSkeleton 的"3D 结构骨架"

StructuralSkeleton 是 PlanSkeleton → 3D 之前的"最后一层纯语义结构"

### Step 3: SkeletonGenerationStep（骨架生成）

**位置：** `src/main/java/com/formacraft/common/llm/compiler/steps/SkeletonGenerationStep.java`

**作用：** 将 StructuralSkeleton 转换为 ExecutableSkeletonPlan 列表

与现有系统强耦合：使用 ExecutableSkeletonPlan 和 SkeletonType

### Step 4: SkeletonPostProcessStep（后处理）

**位置：** `src/main/java/com/formacraft/common/llm/compiler/steps/SkeletonPostProcessStep.java`

**v1 可做的事：**
- 合并共线墙段
- 标注 corner / edge
- 添加 heightLevel tag（GROUND / MID / TOP）

## 🏗️ 默认实现：PlanToSkeletonCompilerV1

**位置：** `src/main/java/com/formacraft/common/llm/compiler/PlanToSkeletonCompilerV1.java`

**内部步骤：**
```java
PlanSkeleton
  ↓
PlanNormalizationStep.normalize()
  ↓
StructuralExtractionStep.extract()
  ↓
SkeletonGenerationStep.generateSkeletons()
  ↓
SkeletonPostProcessStep.postProcess()
  ↓
SkeletonGraphBuilder.build()
  ↓
CompiledSkeleton
```

👉 非常清晰、非常可维护、非常适合长期演进项目

## 📊 SkeletonGraph（关系图）

**位置：** `src/main/java/com/formacraft/common/llm/compiler/SkeletonGraph.java`

**包含：**
- `adjacency`: 邻接关系（skeleton → 相邻的 skeletons）
- `zoneMapping`: Zone 映射（skeleton → zone id）
- `roles`: 角色映射（skeleton → EXTERNAL / INTERNAL / COURTYARD）

**用途：**
- AI 后处理修正
- UI 高亮（外墙 / 内墙 / 庭院墙）
- 构件放置 bias
- Debug & replay

## 🔗 与现有系统的连接

你已经有的：
- `SkeletonSocketProfile.forWall()`
- `SkeletonComponentRules.defaultMedieval()`
- `AutoAssembler.assembleOnSockets(...)`

全部可以无缝接上：

```java
CompiledSkeleton compiled = compiler.compile(planSkeleton, context);

// 生成 Socket
for (ExecutableSkeletonPlan skeleton : compiled.getSkeletons()) {
    List<Socket> sockets = SkeletonSocketGenerator.generateSockets(
        skeleton, world, origin
    );
    // ...
}

// 自动装配
AutoAssembler.assembleOnSockets(
    sockets,
    rules,
    styleProfile,
    materialTone,
    random
);
```

## 💻 使用示例

```java
// 1. 创建编译器
PlanToSkeletonCompiler compiler = new PlanToSkeletonCompilerV1();

// 2. 创建上下文
PlanCompileContext context = PlanCompileContext.createWithTerrain(world, origin);

// 3. 编译
CompiledSkeleton compiled = compiler.compile(planSkeleton, context);

// 4. 使用结果
List<ExecutableSkeletonPlan> skeletons = compiled.getSkeletons();
SkeletonGraph graph = compiled.getGraph();

// 5. 生成 Socket 和装配（使用现有系统）
for (ExecutableSkeletonPlan skeleton : skeletons) {
    List<Socket> sockets = SkeletonSocketGenerator.generateSockets(skeleton, world, origin);
    // ...
}
```

## ✅ 系统能力

完成这一步后，你的系统具备：

- ✔ AI 输出的是建筑逻辑，不是几何
- ✔ 用户可以随时介入平面
- ✔ Skeleton / Socket 完全复用
- ✔ 你可以逐步升级 compiler，而不动 AI Prompt

## 🔮 未来扩展

1. **更智能的规范化**：合并共线墙段、优化边缘
2. **更精确的几何推断**：从 outline 生成精确 polygon
3. **多层级支持**：多层建筑的 floor/roof
4. **复杂屋顶**：分区、错动、坡屋顶
5. **性能优化**：缓存、并行处理
