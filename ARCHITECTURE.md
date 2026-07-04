# 📐 Formacraft – System Architecture Overview

**Formacraft is a semantic architecture compiler for Minecraft.**

---

## 1. Project Overview

Formacraft 是一个基于 **Fabric（Minecraft 1.21.10）** 的 AI 建筑生成模组。

它允许玩家通过 **自然语言 + 空间工具** 描述建筑意图，并由 AI 自动规划、生成、预览并落地方块修改。

### 核心目标

Formacraft 的核心目标不是"自动放方块"，而是：

> **把 Minecraft 中的建筑行为，从"操作级"提升到"语义级"。**

### 技术栈

- **Java 21+** - 模组核心逻辑
- **Fabric API** - Minecraft 模组框架
- **Python 3.12+** - LLM 后端服务（FastAPI）
- **Gradle** - 构建系统

---

## 1.5 唯一主干声明（Canonical Pipeline）— 2026-07 收敛

> **本项目只有一条受支持的"描述 → 建筑"主干：LlmPlan。** 所有新功能只能加在这条主干上；其余路径一律 legacy/quarantine，不得再扩展。

**Canonical Pipeline（唯一主干）：**

```
ChatPanel
  → FormaRequest(outputFormat="llmplan")
  → Python /build → generate_llm_plan()
  → LlmPlan (components[] | plan_program | patch)
  → ComponentPlanCompiler / PlanProgramCompiler
  → 生成器 (MassMainGenerator / Roof / Facade / Entrance ...)
  → BlockPatch → TerrainAdaptation
  → PreviewStorage → outline 预览
  → /forma_confirm → BuildExecutionService
```

**状态分级（收敛目标）：**

- ✅ **主干（唯一支持）**：LlmPlan → `common.compiler` + `common.generation.component` → `BlockPatch`
- 🟡 **精品库（Phase 5 并入主干）**：`server.generation.structure` 地标生成器 —— 目标是改造成"LlmPlan 可调用的命名模块"，而非独立的 BuildingSpec 并行路径
- 🔴 **Quarantine / 待删除**：`common.assembly.AutoAssembler`、`server.cluster.ClusterLayoutPlanner`、`common.layout.PathClusterLayoutPlanner`、`common.mass` 占位实现、`ConfirmBuildPacket` 双轨确认、`com.formacraft.ai` 死 service 子集（保留 `ai.prompt.*` / `ai.context.*`）

**新功能默认路径：** 只往 LlmPlan schema / ComponentPlanCompiler / 生成器上加；需要 bespoke 造型时，改为把生成器注册为 LlmPlan 可引用的模块，禁止新增第二条 spec 格式或第二套确认流程。

**泛化优先：** 一般建筑由 LLM 组合参数化语义构件生成；固定 `StructureGenerator` 仅用于著名地标或显式 `landmark:` 模块引用。详见 [docs/GENERALIZATION_STRATEGY.md](docs/GENERALIZATION_STRATEGY.md)。

### 1.6 Golden-Path 回归清单（手测基准）

当前构建/编译需在开发者本地进行（agent 环境无法编译）。每次涉及主干的改动后，进游戏跑以下代表性 prompt，确认"发请求 → 出预览 → /forma_confirm 落地"三段都正常：

1. **基础单体**：`盖一个 7x7 的小石头房子，带门和窗`
2. **多层竖向**：`盖一座 5 层的方塔，顶部有平台`
3. **院落/复合**：`盖一个带院子的四合院`
4. **地标类**：`盖一座天坛` （验证精品库/landmark 路径）
5. **增量编辑**：先生成一栋，再 `把屋顶换成红色` （验证 patch 主干）

每条记录：是否出现"已发送请求（已等待 N 秒）"、是否出现预览线框、`/forma_confirm` 后是否分 Tick 落地、`latest.log` 中 `Orchestrator /build round-trip took X ms`。

### 1.7 生成质量路线（Phase 5，持续投入）

主干收敛完成后，全部质量投入都压在这一条 LlmPlan 主干上。四条并行工作线（均为**加法**，不再新增并行 spec 格式）：

1. **精品库化（landmark → LlmPlan 可调用模块）**
   - 现状：`server/generation/structure` 下 30+ 硬编码地标生成器（帕特农/长城/天坛/埃菲尔…）是当前"最好看"的产出，但只能走 BuildingSpec 路径。
   - 目标：给 LlmPlan 增加一个"命名模块引用"能力（例如组件 `component_type: "MODULE"` + `module_id: "temple_of_heaven"`，或 slot 上挂 `module_id`），由服务端一个 `LandmarkModuleRegistry` 把 `module_id` 映射到既有 `StructureGenerator`，在预览编译阶段直接调用。
   - 迁移方式：**不删生成器**，而是给它们注册 `module_id` 并让其 `generate()` 能接受 LlmPlan 传入的锚点/尺寸/材质参数（从 `spec.extra` 读取的参数改为从模块调用上下文读取）。bespoke 质量由此并入通用主干。

2. **扩展 LlmPlan schema 表现力**（`python_backend/app/models/llm_plan.py` + Java `common/llm/dto/*`）
   - 新增字段一律**可选 + 向后兼容**（Java `LlmPlanParser` 已 `FAIL_ON_UNKNOWN_PROPERTIES=false`，旧解析器会忽略未知字段）。
   - 目标维度：体量（massing：主体块的层叠/退台）、立面节奏（facade rhythm：开窗间距/壁柱）、屋顶（类型/坡度/出檐）、材质分层（勒脚/墙身/檐口不同材质）。
   - 每加一个字段，必须同步：Python 模型校验 + Java DTO/解析 + `ComponentPlanCompiler`/生成器消费 + 下方 eval 断言。

3. **Prompt 与 few-shot**（Java 端 `com.formacraft.ai.prompt.PromptAssembler` 系列——系统 prompt 在 Java 组装后随 `requestText` 下发）
   - 补高质量 golden 样例（覆盖 1.6 清单的代表场景），提升 LLM plan 的稳定性与几何丰富度。
   - few-shot 必须落在**当前合法 schema** 内，避免诱导 LLM 输出解析器会拒绝的字段。

4. **质量闭环 eval**：`python_backend/eval/golden_eval.py`
   - 复用后端真实 `validate_llm_plan_dict`（schema 合法性）+ 叠加可建造性/丰富度启发式（有门/有窗/有屋顶、无零尺寸、无超大体量、组件数量）。
   - 断言分 `HARD`（不过退出码非 0）与 `SOFT`（趋势告警）。
   - 用法：
     - 离线（推荐，无需 API key）：`python -m eval.golden_eval --plans <捕获的 plan 目录>`
     - 在线冒烟：`python -m eval.golden_eval --live`
   - 每次动 prompt/schema/生成器后，跑一遍 eval 量化影响；新加的质量维度要配套新加断言。

---

## 2. High-Level Architecture

```
Player
  │
  │  Natural Language + Tools
  ▼
Tool Layer
  │   (Selection / Path / Outline / Component / Symmetry)
  ▼
PromptAssembler
  │   (RAG + Constraints + System Prompt)
  ▼
LLM (Python Backend)
  │   (Blueprint / Components / Skeleton JSON)
  ▼
Compiler Pipeline
  │   (Skeleton → Components → Variants)
  ▼
Patch System
  │   (Preview / Diff / Undo / Redo)
  ▼
Minecraft World
```

### 关键原则

- ✅ **AI 永远不直接 setBlock**
- ✅ **世界写入只能通过 BlockPatch**
- ✅ **所有结果都可预览、可裁剪、可撤销**
- ✅ **Memory 系统支持建筑演化**

### 2.1 Package Layering Rules（common / server / client）

2026-07 起，`common` 层对 `server.*` / `client.*` 的依赖已物理清零。新代码必须遵守以下分层，避免边界再次变脏：

| 包 | 允许 | 禁止 |
|----|------|------|
| **`com.formacraft.common.*`** | 纯数据模型、DTO、算法、网络 payload 定义与 codec | `import com.formacraft.server.*`、`import com.formacraft.client.*`；直接访问 `ServerWorld`、玩家实体、UI 组件 |
| **`com.formacraft.server.*`** | 世界写入、服务端网络处理、AI 编排、预览票据 | 依赖 client 渲染或 HUD |
| **`com.formacraft.client.*`** | UI、工具交互、预览渲染、客户端网络发送 | 直接修改服务端世界状态 |

**跨层数据传递**统一走 common 侧 DTO，不在上层之间互相引用实现类：

- 工具约束：`client.tool.ToolConstraintSnapshotFactory` 采集 → `common.tool.ToolConstraintSnapshot` → server/common 过滤管线消费
- Patch 应用结果：`common.patch.PatchExecutor.ApplyResult` → `PatchHistoryManager` 存储 → S2C `PatchApplyResultPayload`（含 `operation`=apply/undo/redo 与 `canUndo`/`canRedo`）→ `BuildConfirmPanel`
- 方块修改单位：`common.patch.BlockPatch`（唯一世界写入载体）

**新增功能时的默认路径：**

1. 在 `common` 定义数据结构 / 算法（如需跨端共享）
2. `client` 采集输入、展示结果；`server` 做权威校验与落盘
3. 若 common 需要 Minecraft 类型（如 `BlockPos`），仅限不可变值对象，不拉入 server/client 生命周期

### 2.2 UI Panel 拆分约定（Panel Decomposition）

HUD 面板一旦超过 ~400 行，必须按「薄编排 + 分区」拆分，禁止把绘制/输入/配置逻辑堆回主类。
参考实现：`client.ui.panel.capture.*`（ComponentCapture）、`client.ui.panel.settings.*`（Settings）。

```
XxxPanel（薄编排：生命周期 + drawContents 编排 + implements Host）
  ├── XxxPanelRenderHost   接口：向 Section 暴露 widget 状态与回调
  ├── XxxPanelDrawSupport  静态绘制工具（label / toast / 缩放鼠标等）
  ├── XxxPanelLayout       常量：行高、间距、颜色
  └── Xxx<Stage>Section    每个 Tab/阶段一个：`drawSection(host, ctx, x, y, w) → 返回新 y`
```

**约束：**

- Section 一律 **static**，通过 `Host` 接口回读状态、回调主类，不持有 Panel 引用字段
- Section 之间不互相调用，只与 `Host` 通信
- 主类实现 `Host` 接口的方法用 `@Override public`，不要再留 `private` 同名方法（会与接口冲突：访问权限收窄 / 调用不明确）
- widget 构建集中在 `WidgetFactory`；鼠标命中/拖拽在 `InputController`；`load/save/sync` 在 `ConfigCoordinator`

### 2.3 预览质量门（Quality Gate）

生成结果经 `server.build.quality.BuildQualityReport` 分级，决定可否预览 / 应用：

| 级别 | 预览 | 默认应用 | 强制应用 |
|------|------|----------|----------|
| **Fatal** | 拒绝 | 拒绝 | 拒绝（不可绕过） |
| **Error** | 允许 | 拒绝 | `/forma_confirm force` |
| **Warning** | 允许 | 允许 + 提示 | — |
| **Info/None** | 允许 | 允许 | — |

- 判定入口：`BuildQualityReport.recommendApply()`（`!hasFatal() && !hasError()`）
- 结构确认：`FormaCraftCommands.executeFormaConfirm(source, force)`；`force=true` 仅放行 Error，Fatal 仍拒绝
- 客户端：`BuildConfirmPanel` PREVIEW 模式收到含 Error 的质量摘要后，第一次确认发 `/forma_confirm`（被拒），第二次发 `/forma_confirm force`

### 2.4 防回退检查清单（PR 自检）

提交涉及世界写入 / 跨层 / 面板的改动前，逐条核对：

- [ ] `common.*` 未 `import` 任何 `server.*` / `client.*`
- [ ] 世界写入只经 `BlockPatch` → `PatchExecutor` / `PatchHistoryManager`，无裸 `setBlockState`
- [ ] 跨端只传 `common` 侧 DTO（如 `ApplyResult`、`ToolConstraintSnapshot`），不传实现类
- [ ] 新增 Panel > 400 行的，已按 §2.2 拆出 Section，主类保持薄编排
- [ ] 新增可应用结果的路径，已接入 §2.3 质量门（Fatal 拒绝 / Error 需 force）

---

## 3. Core Data Flow

### 3.1 Natural Language → Patch

```
1. 玩家输入自然语言
   ↓
2. Tool 状态（选区 / 路径 / 轮廓 / 构件）注入 Prompt
   ↓
3. LLM 输出 Blueprint / Components JSON
   ↓
4. Compiler 将语义结构转译为 Patch
   ↓
5. Patch 进入 Preview
   ↓
6. 玩家确认 → Apply
```

### 3.2 关键模块位置

**Java 端（Minecraft 模组）**：
- 入口点: `com.formacraft.FormacraftMod`
- 客户端: `com.formacraft.client.ClientInitializer`
- 服务端: `com.formacraft.server.ServerInitializer`
- 工具系统: `com.formacraft.client.tool.*`
- 编译系统: `com.formacraft.common.compiler.*`
- Patch 系统: `com.formacraft.common.patch.*`

**Python 后端**：
- 入口点: `python_backend/app/main.py`
- LLM 服务: `python_backend/app/services/ai_planner.py`
- 路由: `python_backend/app/routes/build.py`

---

## 4. Patch System (World Mutation Core)

### 4.1 BlockPatch

```java
public record BlockPatch(
    String action,   // place / replace / remove
    int dx, int dy, int dz,  // relative to origin
    String targetBlock
)
```

**Patch 是 Formacraft 的唯一世界修改单位。**

#### 优势

- ✅ **Preview / Diff** - 应用前可视化
- ✅ **Undo / Redo** - 完整历史支持
- ✅ **工具裁剪** - 禁区 / 轮廓 / 对称
- ✅ **Memory 回溯** - 支持建筑演化

### 4.2 Patch 执行流程

```java
// 1. 生成 Patch
List<BlockPatch> patches = compiler.generate(blueprint);

// 2. 过滤（可选）
patches = PatchFilterPipeline.filter(patches, constraints);

// 3. 预览（客户端）
PreviewManager.showPreview(patches, origin);

// 4. 应用（服务端）
PatchExecutor.apply(world, origin, patches);

// 5. 记录历史（支持 Undo）
PatchHistoryManager.applyWithHistory(world, playerId, origin, patches);
// ApplyResult 存入 PatchHistoryManager，并经 PatchApplyResultPayload 下发客户端
```

### 4.3 BuildExecutionService（分 Tick 执行）

为避免服务器卡顿，Patch 通过 `BuildExecutionService` 分 Tick 执行（默认 200 个方块/Tick）。

**位置**: `com.formacraft.server.build.BuildExecutionService`

---

## 5. Skeleton System (Topology First)

**Skeleton 描述的是空间拓扑关系，而不是具体外形。**

```java
enum SkeletonType {
    LINEAR_PATH,       // 道路 / 长城
    RADIAL_RING,       // 土楼 / 圆形建筑
    VERTICAL_STACK,    // 塔 / 楼
    GRID,              // 建筑群
    SPAN_SUSPENSION,   // 桥
    COMPOUND           // 复合
}
```

### 核心思想

> **Skeleton 决定 "怎么连"，**  
> **Component 决定 "是什么"，**  
> **Palette 决定 "看起来像什么"。**

### Skeleton → Generator 映射

```java
// 注册生成器
SkeletonGeneratorRegistry.register(
    SkeletonType.LINEAR_PATH,
    new LinearPathGenerator()
);

// 生成 Patch
List<BlockPatch> patches = generator.generate(context, plan);
```

**位置**: `com.formacraft.common.skeleton.*`

---

## 6. Component System (Semantic Building Blocks)

### 6.1 What is a Component

**Component 是 AI 可复用的建筑语义构件：**

- 门、窗、柱、斗拱、栏杆
- 阳台、雨棚、飞檐、烟囱

**不是模型，而是"可变基因"**。

### 6.2 ComponentPlacementSpec

```java
ComponentPlacementSpec =
    Attachment + Context + FacingPolicy + Constraints
```

用于描述：

- ✅ 是否有内外之分
- ✅ 是否只能附着在外侧
- ✅ 是否必须在边缘 / 墙面 / 顶部
- ✅ 是否允许旋转 / 镜像

**位置**: `com.formacraft.common.component.placement.ComponentPlacementSpec`

### 6.3 ComponentQuery & Ranking

AI 通过语义查询构件库，而不是直接选择具体构件：

```json
{
  "component_query": {
    "semantic": { "role": "door", "tags": ["entry"] },
    "context": { "placement": "wall", "side": "exterior" },
    "geometry": { "requiresOpening": true, "openingWidth": 2 },
    "style": { "styleProfile": "CHINESE_VILLA" }
  }
}
```

**位置**: `com.formacraft.common.component.query.ComponentQuery`

---

## 7. Socket System (World → Component Interface)

### 7.1 SocketProvider

从世界中提取可附着点：

- 墙面 (`WallSocketProvider`)
- 边缘 (`EdgeSocketProvider`)
- 路径点 (`PathPolylineSocketProvider`)
- 对称轴 (`SymmetrySocketProvider`)
- 轮廓边界 (`OutlinePolygonSocketProvider`)

**位置**: `com.formacraft.common.component.socket.providers.*`

### 7.2 SocketMatcher

根据 `ComponentPlacementSpec` 过滤合法 Socket。

**位置**: `com.formacraft.common.component.socket.SocketMatcher`

### 7.3 Socket → Patch Anchor

将抽象 Socket 转换为具体 Patch 原点（dx, dy, dz）。

**这是整个系统从"语义"落地到"几何"的关键一步。**

**位置**: `com.formacraft.common.component.socket.SocketAnchorPlacement`

---

## 8. Tool Layer (Spatial Constraints)

**工具并不直接改世界，而是约束 AI 的规划空间。**

| Tool | Purpose | 实现类 |
|------|----------|--------|
| `SelectionTool` | 建造范围 | `com.formacraft.client.tool.SelectionTool` |
| `OutlineTool` | 任意轮廓 | `com.formacraft.client.tool.OutlineTool` |
| `PathTool` | 道路 / 长城 / 连续布局 | `com.formacraft.client.tool.PathTool` |
| `ProtectedZoneTool` | 禁区 | `com.formacraft.client.tool.ProtectedZoneTool` |
| `SymmetryTool` | 对称约束 | `com.formacraft.client.tool.SymmetryTool` |
| `SemanticLabelTool` | 区域语义 | `com.formacraft.client.tool.SemanticLabelTool` |
| `ComponentTool` | 构件定义 | `com.formacraft.client.tool.ComponentTool` |

### 工具 → Prompt 的原则

工具不会直接修改世界，而是：

1. 转译为空间约束
2. 注入 `PromptAssembler`
3. 影响 LLM 的规划结果

**位置**: `com.formacraft.client.tool.*`

---

## 9. PromptAssembler (AI Interface)

**PromptAssembler 是系统的"前额叶"：**

- ✅ 汇总工具状态
- ✅ 注入 Memory（RAG）
- ✅ 拼接 System Prompt / User Prompt
- ✅ 严格约束 JSON 输出结构

### Prompt 组装流程

```java
public static String assemble(String userInput, PromptMode mode) {
    PromptContext ctx = new PromptContext();
    
    // 1. 工具 → 结构化上下文
    ToolPromptBuilder.buildToolContext(ctx);
    
    // 2. Memory Context（RAG）
    MemoryContext memory = retrieveMemory(ctx);
    
    // 3. 拼装最终 Prompt
    return buildFinalPrompt(ctx);
}
```

**位置**: `com.formacraft.ai.prompt.PromptAssembler`

---

## 10. Memory System (Forma-Cortex)

### 10.1 Three-Tier Memory

| Layer | Description | 实现 |
|-------|-------------|------|
| **Spatial Memory** | 建筑位置与边界 | `SpatialIndex`（基于区块索引） |
| **Semantic Memory** | 名称 / 标签 / 描述 | `SemanticIndex`（倒排索引） |
| **Genetic Memory** | Blueprint / Gene JSON | `ProjectMemory.geneData` |

### 10.2 Why Memory Matters

- **没有记忆**：AI 是一次性命令
- **有记忆**：AI 是"记得住建筑"的建筑师

### 10.3 Memory → Patch → Memory Update

```
1. 查 Memory 定位建筑
   ↓
2. 提取 gene_data
   ↓
3. LLM 生成修改 Blueprint
   ↓
4. 编译 Patch
   ↓
5. Apply Patch
   ↓
6. 更新 Memory JSON
```

**位置**: `com.formacraft.server.memory.*`

---

## 11. Preview & Safety

**所有 Patch 必须经过：**

- ✅ **Outline / Diff 预览** - 应用前可视化
- ✅ **Apply / Undo / Redo** - 完整历史支持
- ✅ **工具裁剪** - 禁区、选区、轮廓
- ✅ **AI 永远不直接写世界**

### Preview 流程

```java
// 1. 生成 Patch
List<BlockPatch> patches = compiler.generate(blueprint);

// 2. 发送到客户端预览
PreviewPayload payload = new PreviewPayload(origin, patches);
FormaCraftNetworking.sendToClient(player, payload);

// 3. 客户端显示预览
BuildConfirmPanel.showPreview(patches, origin);

// 4. 玩家确认后应用
PatchApplyPayload applyPayload = new PatchApplyPayload(origin, patches);
FormaCraftNetworking.sendToServer(applyPayload);
```

**位置**: `com.formacraft.client.ui.panel.BuildConfirmPanel`

---

## 12. UI Architecture

### 12.1 HUD-based（非 Screen）

- ✅ 游戏不中断
- ✅ 与世界交互并行
- ✅ 输入路由严格区分 UI / World

### 12.2 ToolPanel

- 工具列表固定（左侧）
- 工具选项独立滚动
- 当前工具语义清晰可见

**位置**: `com.formacraft.client.ui.panel.ToolPanel`

---

## 13. Extensibility Guidelines

### 新工具

```
实现 FormacraftTool
  ↓
提供状态 → PromptAssembler
  ↓
（可选）提供 SocketProvider
  ↓
在 ToolPanel 注册
```

### 新 Skeleton

```
扩展 SkeletonType
  ↓
实现 Generator
  ↓
对接 TerrainStrategy
  ↓
注册到 SkeletonGeneratorRegistry
```

### 新构件

```
定义 Component JSON
  ↓
定义 ComponentPlacementSpec
  ↓
提供 Variant 编译逻辑
  ↓
自动进入 AI 构件库
```

### 新风格

```
定义 StyleProfile
  ↓
配置 Palette
  ↓
注册到 StyleProfileRegistry
```

---

## 14. Design Philosophy Summary

> **Formacraft 是一个建筑语言层，而不是编辑器。**

### 核心原则

1. **语义优先** - AI 理解"建筑是什么"，而不是"方块怎么放"
2. **Patch 唯一** - 所有世界修改都通过 BlockPatch
3. **可预览** - 所有结果都可预览、可撤销
4. **可演化** - Memory 系统支持建筑演化
5. **可扩展** - 工具、构件、生成器都可插拔

---

## 15. Related Documentation

### Authoritative (Tier 0 — maintained)

| Document | Description |
|----------|-------------|
| [docs/INDEX.md](docs/INDEX.md) | **Documentation master index** |
| [ARCHITECTURE.md](ARCHITECTURE.md) | This document — system overview and data flow |
| [docs/GENERATION_PIPELINE.md](docs/GENERATION_PIPELINE.md) | End-to-end pipeline truth (LlmPlan → placed blocks) |
| [docs/GENERALIZATION_STRATEGY.md](docs/GENERALIZATION_STRATEGY.md) | Generalization-first principles and gap list |
| [docs/LLMPLAN_SYSTEM_CORE_PHILOSOPHY.md](docs/LLMPLAN_SYSTEM_CORE_PHILOSOPHY.md) | Design philosophy: AI plans, Java implements |
| [docs/DOC_CONVENTIONS.md](docs/DOC_CONVENTIONS.md) | Documentation naming and archive rules |

### Developer reference (Tier 1)

- [docs/FORMACRAFT_DEVELOPER_DOCUMENTATION.md](docs/FORMACRAFT_DEVELOPER_DOCUMENTATION.md) — detailed developer reference
- [CONTRIBUTING.md](CONTRIBUTING.md) — contribution guide
- [docs/MIGRATION_LLMPLAN_VS_BUILDINGSPEC.md](docs/MIGRATION_LLMPLAN_VS_BUILDINGSPEC.md) — component vs structure coverage matrix

### Module indexes (Tier 2)

| Area | Location |
|------|----------|
| Assembly / Forma-Gene | [docs/assembly/](docs/assembly/) |
| Landmarks | [docs/landmarks/](docs/landmarks/) |
| Examples | [docs/examples/](docs/examples/) |
| Python backend | [python_backend/README.md](python_backend/README.md) |
| Historical snapshots | [docs/archive/](docs/archive/) (not maintained) |

### Project scale (2026-07)

| Metric | Count |
|--------|-------|
| Java source files | ~822 |
| Python backend files | ~33 |
| Active markdown (excl. archive) | ~170 |

### Network layer (2026-07 refactor)

| Class | Responsibility |
|-------|----------------|
| `FormaCraftNetworking` | Payload definitions, registration, client send helpers |
| `BuildRequestProcessor` | C2S build request routing (city / composite / LlmPlan / BuildingSpec) |
| `LlmPlanPreviewBuilder` | LlmPlan → preview pipeline |
| `OrchestratorErrorHumanizer` | User-facing orchestrator error messages |
| `BuildStatusHeartbeat` | Long-running request progress heartbeats |
| `LlmPlanTerrainBounds` | Terrain pad footprint math |
| `NetworkOrchestratorProvider` | Lazy `OrchestratorClient` singleton |

### Generation system target state

- **Primary:** LlmPlan → `common.compiler` → `common.generation.component` → `BlockPatch`
- **Structure (landmarks / city / composite):** `BuildingSpec` → `GenerationHub.routeStructure()` → `common.generation.structure`
- **Migration tracking:** `docs/MIGRATION_LLMPLAN_VS_BUILDINGSPEC.md`
- **Routing:** `FormaRequest.outputFormat` — client defaults to `"llmplan"`; Python `build.py` mirrors this

---

**Formacraft v1.0**  
*让建筑成为语言，让 AI 成为建筑师。*
