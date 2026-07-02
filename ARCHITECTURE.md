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

### Authoritative (maintained)

- **[ARCHITECTURE.md](ARCHITECTURE.md)** — this document; system overview and data flow
- **[CONTRIBUTING.md](CONTRIBUTING.md)** — contribution guide
- **[README.md](README.md)** — project overview
- **[docs/FORMACRAFT_DEVELOPER_DOCUMENTATION.md](docs/FORMACRAFT_DEVELOPER_DOCUMENTATION.md)** — detailed developer reference

### Module indexes

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

- **Primary:** LlmPlan → `common.compiler` → `common.generator` → `BlockPatch`
- **Structure (landmarks / city / composite):** `BuildingSpec` → `GenerationHub.routeStructure()` → `common.generation.structure`
- **Routing:** `FormaRequest.outputFormat` — client defaults to `"llmplan"`; Python `build.py` mirrors this

---

**Formacraft v1.0**  
*让建筑成为语言，让 AI 成为建筑师。*
