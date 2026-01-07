# Formacraft 统一系统架构文档

## 🎯 目标

**明确各个系统的作用和职责，避免混乱，确保系统架构清晰、易于维护和扩展**

## 📋 系统概览

Formacraft 模组包含以下主要系统：

1. **输入系统**：用户输入、工具状态、约束条件
2. **AI 规划系统**：LLM 调用、Prompt 组装、响应解析
3. **生成器系统**：组件生成器、结构生成器
4. **编译系统**：组件计划编译、后处理
5. **预览系统**：预览状态、渲染器
6. **执行系统**：建筑执行、撤销
7. **工具系统**：各种工具（路径、选择、轮廓等）

## 🔄 完整数据流

```
用户输入（自然语言）
  ↓
工具状态收集（PathTool, SelectionTool, OutlineTool, ...）
  ↓
PromptAssembler（组装 Prompt）
  ↓
OrchestratorClient（调用 Python 后端）
  ↓
Python 后端（LLM 调用）
  ↓
返回 LlmPlan 或 BuildingSpec
  ↓
┌─────────────────┬─────────────────┐
│   LlmPlan       │   BuildingSpec  │
│   (新系统)      │   (传统系统)    │
└─────────────────┴─────────────────┘
  ↓                    ↓
ComponentPlanCompiler  GeneratorRouter
  ↓                    ↓
ComponentGenerator     StructureGenerator
  ↓                    ↓
List<BlockPatch>       GeneratedStructure
  ↓                    ↓
PostProcessPipeline    BuildExecutionService
  ↓                    ↓
PatchFilterPipeline    ──────┐
  ↓                          │
Preview / Apply ←────────────┘
```

## 📦 系统详细说明

### 1. 输入系统

#### 1.1 用户输入
- **位置**：`com.formacraft.client.ui.panel.ChatPanel`
- **功能**：接收用户的自然语言输入
- **输出**：`String requestText`

#### 1.2 工具状态收集
- **位置**：`com.formacraft.client.tool.*`
- **工具类型**：
  - `PathTool` - 路径工具
  - `SelectionTool` - 选择工具
  - `OutlineTool` - 轮廓工具
  - `SymmetryTool` - 对称工具
  - `ProtectedZoneTool` - 保护区工具
  - `SemanticLabelTool` - 语义标注工具
- **功能**：收集工具状态，用于约束生成

#### 1.3 Prompt 组装
- **位置**：`com.formacraft.ai.prompt.PromptAssembler`
- **功能**：将用户输入和工具状态组装成 LLM Prompt
- **输出**：结构化的 Prompt（包含约束、意图、输出格式）

### 2. AI 规划系统

#### 2.1 LLM 调用
- **位置**：`com.formacraft.server.orchestrator.OrchestratorClient`
- **功能**：调用 Python 后端的 `/build` 端点
- **输出**：`BuildingSpec` 或 `LlmPlan`（JSON 格式）

#### 2.2 响应解析
- **位置**：`com.formacraft.common.llm.dto.LlmPlanParser`
- **功能**：解析 LLM 返回的 JSON
- **输出**：强类型的 `LlmPlan` 或 `BuildingSpec`

### 3. 生成器系统

#### 3.1 组件生成器系统（新系统，K3）

**包路径**：`com.formacraft.common.generator`

**核心接口**：
- `ComponentGenerator` - 组件生成器接口
- `GeneratorRegistry` - 生成器注册表

**输入**：`SemanticComponent`（来自 LLM 的 JSON 输出）

**输出**：`List<BlockPatch>`（相对坐标的方块补丁）

**使用场景**：
- LLM 输出 `LlmPlan` 格式
- 语义组件生成（TOWER, WALL, GATE, ROAD 等）
- 组件级别的灵活组合

**调用链**：
```
LlmPlan → ComponentPlanCompiler → GeneratorRegistry → ComponentGenerator → List<BlockPatch>
```

**已实现的生成器**：
- `TowerGenerator` - 塔楼
- `WallGenerator` - 墙体
- `GateGenerator` - 门/门楼
- `RoadGenerator` - 道路
- `MassMainGenerator` - 主体体块
- `RoofGenerator` - 屋顶
- `CourtyardSpaceGenerator` - 庭院空间
- `GateStructureGenerator` - 门楼结构
- `PathGenerator` - 路径
- `FacadeWindowsGenerator` - 立面窗户
- `BalconyGenerator` - 阳台
- `ChimneyGenerator` - 烟囱
- `FoundationGenerator` - 基础
- `DecorDetailGenerator` - 装饰细节
- 等...

**特点**：
- ✅ 轻量级：只负责单个组件的生成
- ✅ 语义驱动：基于 LLM 的语义描述
- ✅ 可组合：多个组件可以组合成复杂建筑
- ✅ 相对坐标：输出是相对 anchor 的 BlockPatch

#### 3.2 结构生成器系统（传统系统）

**包路径**：`com.formacraft.server.generator`

**核心接口**：
- `StructureGenerator` - 结构生成器接口
- `GeneratorRouter` - 生成器路由器

**输入**：`BuildingSpec`（完整建筑规格）

**输出**：`GeneratedStructure`（绝对坐标的结构）

**使用场景**：
- 传统 `BuildingSpec` 格式
- 地标建筑（如土楼、埃菲尔铁塔）
- 完整建筑生成

**调用链**：
```
BuildingSpec → GeneratorRouter → StructureGeneratorFactory → StructureGenerator → GeneratedStructure
```

**已实现的生成器**：
- `TowerGenerator` - 完整塔楼（~500+ 行）
- `WallGenerator` - 完整墙体（~300+ 行）
- `PathGenerator` - 复杂路径（~650+ 行）
- `CastleCompoundGenerator` - 城堡复合结构
- `HouseGenerator` - 房屋生成器
- 等 50+ 个生成器...

**特点**：
- ✅ 完整功能：生成完整的建筑结构
- ✅ 绝对坐标：输出是绝对世界坐标
- ✅ 专用生成器：每个地标建筑有专门的生成器
- ✅ 传统系统：与 BuildingSpec 系统深度集成

### 4. 编译系统

#### 4.1 组件计划编译器

**位置**：`com.formacraft.common.compiler.ComponentPlanCompiler`

**功能**：
- 将 `LlmPlan` 的 `components[]` 编译为 `List<BlockPatch>`
- 支持后处理步骤（细节装饰、材质变化、地形适应）

**输入**：`LlmPlan`

**输出**：`List<BlockPatch>`

**处理流程**：
```
1. 索引 slots（便于快速查找）
2. 遍历所有 components
3. 创建 SemanticComponent
4. 获取对应的 ComponentGenerator
5. 生成 BlockPatch
6. 后处理步骤（如果提供了 world 和 terrainSampler）
```

#### 4.2 后处理管道

**位置**：`com.formacraft.common.compiler.postprocess.PostProcessPipeline`

**功能**：对生成的 `BlockPatch` 列表进行后处理

**后处理器**：
1. `DetailEnhancementPostProcessor` - 细节装饰增强
2. `MaterialVariationPostProcessor` - 材质变化
3. `TerrainAdaptationPostProcessor` - 地形适应

**执行顺序**：
```
List<BlockPatch>
  ↓
DetailEnhancementPostProcessor（细节装饰增强）
  ↓
MaterialVariationPostProcessor（材质变化）
  ↓
TerrainAdaptationPostProcessor（地形适应）
  ↓
最终的 List<BlockPatch>
```

### 5. 预览系统

#### 5.1 预览状态

**位置**：`com.formacraft.client.preview.*`

**状态类型**：
- `PatchPreviewState` - 补丁预览状态
- `LayoutSitePreviewState` - 布局站点预览状态
- `SkeletonPreviewState` - 骨架预览状态
- `OutlinePreviewState` - 轮廓预览状态

#### 5.2 渲染器

**位置**：`com.formacraft.client.preview.*`

**渲染器类型**：
- `PatchPreviewRenderer` - 补丁预览渲染器
- `LayoutSiteHudOverlay` - 布局站点 HUD 覆盖层
- `SkeletonPreviewRenderer` - 骨架预览渲染器
- `OutlineRenderer` - 轮廓渲染器

### 6. 执行系统

#### 6.1 建筑执行服务

**位置**：`com.formacraft.server.build.BuildExecutionService`

**功能**：
- 分 Tick 执行建造操作
- 避免卡顿
- 支持撤销

#### 6.2 补丁过滤器

**位置**：`com.formacraft.common.patch.filter.*`

**过滤器类型**：
- `ForbiddenZoneFilter` - 禁区过滤器
- `OutlineClipFilter` - 轮廓裁剪过滤器
- `SelectionOnlyFilter` - 选区过滤器
- `SymmetryFilter` - 对称过滤器

**功能**：确保生成的补丁符合工具约束

### 7. 工具系统

#### 7.1 工具管理器

**位置**：`com.formacraft.client.tool.ToolManager`

**功能**：管理所有工具的状态和生命周期

#### 7.2 工具类型

**路径工具**：
- `PathTool` - 路径工具（用于生成沿路径的建筑群）

**选择工具**：
- `SelectionTool` - 选择工具（定义生成区域）

**轮廓工具**：
- `OutlineTool` - 轮廓工具（定义建筑轮廓）

**对称工具**：
- `SymmetryTool` - 对称工具（定义对称轴）

**保护区工具**：
- `ProtectedZoneTool` - 保护区工具（定义禁止建造区域）

**语义标注工具**：
- `SemanticLabelTool` - 语义标注工具（定义语义区域）

## 🔀 系统关系图

```
┌─────────────────────────────────────────────────────────────┐
│                      用户输入层                              │
│  ChatPanel → 工具状态收集 → PromptAssembler                 │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                      AI 规划层                               │
│  OrchestratorClient → Python 后端 → LLM                     │
└─────────────────────────────────────────────────────────────┘
                            ↓
        ┌───────────────────┴───────────────────┐
        ↓                                       ↓
┌──────────────────────┐          ┌──────────────────────┐
│   LlmPlan 系统       │          │  BuildingSpec 系统   │
│  (新系统，K3)        │          │  (传统系统)          │
├──────────────────────┤          ├──────────────────────┤
│ ComponentPlanCompiler│          │ GeneratorRouter       │
│   ↓                  │          │   ↓                  │
│ ComponentGenerator   │          │ StructureGenerator    │
│   ↓                  │          │   ↓                  │
│ List<BlockPatch>     │          │ GeneratedStructure   │
└──────────────────────┘          └──────────────────────┘
        ↓                                       ↓
        └───────────────────┬───────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                      后处理层                                │
│  PostProcessPipeline → PatchFilterPipeline                  │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                      预览层                                  │
│  PreviewState → PreviewRenderer → HUD Overlay               │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                      执行层                                  │
│  BuildExecutionService → 分 Tick 执行 → 撤销系统            │
└─────────────────────────────────────────────────────────────┘
```

## 🎯 系统使用指南

### 场景 1：使用新系统（LlmPlan）

**适用场景**：
- LLM 输出 `LlmPlan` 格式
- 需要组件级别的灵活组合
- 需要语义驱动的生成

**流程**：
```
1. 用户输入 → PromptAssembler
2. OrchestratorClient → Python 后端
3. 返回 LlmPlan
4. ComponentPlanCompiler.compile()
5. ComponentGenerator.generate()
6. PostProcessPipeline.process()
7. PatchFilterPipeline.filter()
8. Preview / Apply
```

### 场景 2：使用传统系统（BuildingSpec）

**适用场景**：
- LLM 输出 `BuildingSpec` 格式
- 需要生成完整建筑
- 需要地标建筑（如土楼、埃菲尔铁塔）

**流程**：
```
1. 用户输入 → PromptAssembler
2. OrchestratorClient → Python 后端
3. 返回 BuildingSpec
4. GeneratorRouter.route()
5. StructureGenerator.generate()
6. BuildExecutionService.enqueueBuild()
7. 分 Tick 执行
```

## ⚠️ 系统选择建议

### 何时使用新系统（LlmPlan）

✅ **推荐使用**：
- LLM 输出 `LlmPlan` 格式
- 需要组件级别的灵活组合
- 需要语义驱动的生成
- 需要快速迭代和测试

### 何时使用传统系统（BuildingSpec）

✅ **推荐使用**：
- LLM 输出 `BuildingSpec` 格式
- 需要生成完整建筑
- 需要地标建筑（如土楼、埃菲尔铁塔）
- 需要复杂的建筑结构

## 📝 系统维护指南

### 添加新的组件生成器

1. 实现 `ComponentGenerator` 接口
2. 在 `GeneratorRegistry` 中注册
3. 确保生成器能够处理 `SemanticComponent` 并输出 `List<BlockPatch>`

### 添加新的结构生成器

1. 实现 `StructureGenerator` 接口
2. 在 `GeneratorRouter` 中注册
3. 确保生成器能够处理 `BuildingSpec` 并输出 `GeneratedStructure`

### 添加新的后处理器

1. 实现 `PostProcessor` 接口
2. 在 `PostProcessPipeline` 中添加
3. 确保后处理器能够处理 `List<BlockPatch>` 并输出处理后的列表

## ✅ 总结

**系统架构清晰**：
- ✅ 新系统（LlmPlan）和传统系统（BuildingSpec）完全独立
- ✅ 各自服务于不同的场景和架构层次
- ✅ 系统职责明确，易于维护和扩展

**数据流向清晰**：
- ✅ 从用户输入到最终建筑的完整链路
- ✅ 每个系统都有明确的输入和输出
- ✅ 系统之间通过标准接口交互

**易于扩展**：
- ✅ 新系统支持插件式扩展（ComponentGenerator）
- ✅ 传统系统支持多级路由（GeneratorRouter）
- ✅ 后处理系统支持链式处理（PostProcessPipeline）

