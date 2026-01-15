# 📐 Formacraft —— AI 驱动的 Minecraft 建筑生成系统

## Developer & Maintainer Documentation v1.0

> **Formacraft is not a building command.**  
> **It is a semantic architecture compiler for Minecraft.**

---

## 目录

- [一、项目愿景与核心理念](#一项目愿景与核心理念)
- [二、整体系统架构（宏观）](#二整体系统架构宏观)
- [三、核心概念总览（必须理解）](#三核心概念总览必须理解)
- [四、工具系统（Tool Layer）](#四工具系统tool-layer)
- [五、PromptAssembler（AI 接口层）](#五promptassemblerai-接口层)
- [六、Memory 系统（Forma-Cortex）](#六memory-系统forma-cortex)
- [七、Preview / Patch 流程](#七preview--patch-流程)
- [八、Socket 系统（H 系列）](#八socket-系统h-系列)
- [九、Component Variant & Archetype](#九component-variant--archetype)
- [十、UI 系统设计原则](#十ui-系统设计原则)
- [十一、扩展指南（给未来开发者）](#十一扩展指南给未来开发者)
- [十二、API 参考](#十二api-参考)
- [十三、开发工作流程](#十三开发工作流程)
- [十四、故障排除](#十四故障排除)
- [十五、Formacraft 的终极定位](#十五formacraft-的终极定位)

---

## 一、项目愿景与核心理念

### 1.1 项目目标

Formacraft 的目标不是"更快地放方块"，而是：

**让玩家用"建筑意图"而不是"操作步骤"来建造世界。**

#### 玩家不再需要：
- ❌ 精确指定每个方块
- ❌ 记忆复杂的 WorldEdit /fill 语法
- ❌ 手动规划复杂空间

#### 而是可以：
- ✅ 描述建筑的 **用途 / 风格 / 关系**
- ✅ 指定 **空间约束**（选区 / 路径 / 轮廓）
- ✅ 利用 **AI + 构件库 + 生成器** 自动完成建造

### 1.2 Formacraft 的设计哲学

| 传统方式 | Formacraft |
|---------|-----------|
| 直接 SetBlock | 生成 Patch |
| 操作导向 | 语义导向 |
| 玩家控制一切 | AI + 规则协作 |
| 一次性操作 | 可预览 / 可撤销 / 可演化 |

**一句话总结：**

> Formacraft 是一个 **"自然语言 → 语义 → 几何 → Patch → 世界状态"** 的编译器系统。

---

## 二、整体系统架构（宏观）

### 2.1 完整数据流

```
┌────────────┐
│   Player   │
└─────┬──────┘
      │ 自然语言 + 工具
      ▼
┌────────────┐
│ Tool Layer │  ← 选区 / 路径 / 轮廓 / 构件
└─────┬──────┘
      ▼
┌────────────┐
│ Prompt     │  ← PromptAssembler + Memory (RAG)
│ Assembler  │
└─────┬──────┘
      ▼
┌────────────┐
│   LLM      │  ← 输出 Blueprint / Components JSON
│ (Python)   │
└─────┬──────┘
      ▼
┌────────────┐
│ Compiler   │  ← Skeleton / Component / Variant
│ Pipeline   │
└─────┬──────┘
      ▼
┌────────────┐
│ Patch      │  ← BlockPatch list
│ Preview    │
└─────┬──────┘
      ▼
┌────────────┐
│ World      │  ← Apply / Undo / Redo
└────────────┘
```

### 2.2 关键模块位置

#### Java 端（Minecraft 模组）
- **入口点**: `com.formacraft.FormacraftMod`
- **客户端**: `com.formacraft.client.ClientInitializer`
- **服务端**: `com.formacraft.server.ServerInitializer`
- **工具系统**: `com.formacraft.client.tool.*`
- **编译系统**: `com.formacraft.common.compiler.*`
- **Patch 系统**: `com.formacraft.common.patch.*`

#### Python 后端
- **入口点**: `python_backend/app/main.py`
- **LLM 服务**: `python_backend/app/services/ai_planner.py`
- **路由**: `python_backend/app/routes/build.py`

---

## 三、核心概念总览（必须理解）

### 3.1 Patch（增量修改）

**定义**: `com.formacraft.common.patch.BlockPatch`

```java
public record BlockPatch(
    String action,   // place / replace / remove
    int dx, dy, dz,  // relative to origin
    String targetBlock
)
```

**Patch 是 Formacraft 的唯一世界写入形式。**

#### 优点：
- ✅ **可预览** - 在应用前查看效果
- ✅ **可撤销** - 完整的 Undo/Redo 支持
- ✅ **可裁剪** - 过滤禁区、选区
- ✅ **可被工具/规则过滤** - 支持复杂约束

#### 执行流程：

```java
// 1. 生成 Patch 列表
List<BlockPatch> patches = compiler.generate(blueprint, context);

// 2. 过滤（可选）
patches = PatchFilterPipeline.filter(patches, constraints);

// 3. 预览（客户端）
PreviewManager.showPreview(patches, origin);

// 4. 应用（服务端）
PatchExecutor.apply(world, origin, patches);

// 5. 记录历史（支持 Undo）
PatchHistoryManager.applyWithHistory(world, playerId, origin, patches);
```

### 3.2 Blueprint（语义蓝图）

**Blueprint 是 LLM 输出的结构化建筑描述，而不是方块列表。**

它包含：
- **建筑风格** (`StyleProfile`)
- **Skeleton**（拓扑骨架）
- **Components**（语义构件）
- **TerrainStrategy**（地形策略）

#### Blueprint JSON 结构：

```json
{
  "mode": "build",
  "style_profile": "CHINESE_VILLA",
  "style_attributes": {
    "wall_color": "white",
    "wall_material": "stone",
    "roof_color": "gray",
    "roof_material": "tile"
  },
  "anchor": { "x": 0, "y": 64, "z": 0 },
  "genome": { /* BuildingGenome */ },
  "global_constraints": {
    "facing": "NORTH",
    "symmetry": "NONE",
    "terrain_strategy": "ADAPTIVE"
  },
  "layout": {
    "skeleton_type": "COMPOUND",
    "slots": [ /* SlotObject[] */ ]
  },
  "components": [ /* ComponentObject[] */ ]
}
```

### 3.3 Skeleton（拓扑骨架）

**Skeleton 描述的是空间关系，而不是形状细节。**

**定义**: `com.formacraft.common.skeleton.SkeletonType`

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

**核心思想**: Skeleton 决定 **"东西怎么连"**，不是 **"长什么样"**。

#### SkeletonPlan 结构：

```java
public abstract class SkeletonPlan {
    public BlockPos anchor;
    public List<BlockPos> points;      // 路径点 / 轮廓点
    public Map<String, Object> params;  // 参数（width, height, radius...）
    
    public abstract SkeletonType type();
}
```

#### Skeleton → Generator 映射：

```java
// 注册生成器
SkeletonGeneratorRegistry.register(
    SkeletonType.LINEAR_PATH,
    new LinearPathGenerator()
);

// 生成 Patch
List<BlockPatch> patches = generator.generate(context, plan);
```

### 3.4 Component（构件）

**Component 是"可复用的建筑语义单元"。**

#### 构件类型示例：
- **门** (`DOOR`)
- **窗** (`WINDOW`)
- **柱** (`COLUMN`)
- **阳台** (`BALCONY`)
- **斗拱** (`BRACKET`)
- **栏杆** (`RAILING`)

#### 核心思想：

> **LLM 不直接放方块，而是选择构件 + 指定语义 + 让系统决定如何放。**

#### ComponentDefinition 结构：

```java
public class ComponentDefinition {
    public String id;
    public String name;
    public ComponentCategory category;
    public List<String> tags;
    public Size size;
    public Anchor anchor;
    public ComponentPlacementSpec placementSpec;  // 关键！
    public List<BlockEntry> blocks;
    public List<ComponentSocket> sockets;
}
```

### 3.5 ComponentPlacementSpec（放置语义）

**这是构件系统的关键创新。**

```java
ComponentPlacementSpec =
  Attachment + Context + FacingPolicy + Constraints
```

#### 解决的问题：
- ✅ 构件是否有内外之分
- ✅ 是否必须贴墙 / 在边缘
- ✅ 是否允许旋转 / 镜像
- ✅ 尺寸约束（最小/最大）

#### 示例：

```java
// 门的放置规格
ComponentPlacementSpec doorSpec = new ComponentPlacementSpec();
doorSpec.attachment = AttachmentType.WALL_OPENING;
doorSpec.spatialContext = SpatialContext.ANY;
doorSpec.facingPolicy = FacingPolicy.DERIVED_FROM_HOST;
doorSpec.hasInteriorExterior = true;
doorSpec.requiresOpening = true;
doorSpec.allowedSockets.add(SocketType.WALL_OPENING);
```

### 3.6 Socket（插槽）

**Socket 是世界中"可被构件附着的位置"。**

#### Socket 来源：
- 墙面 (`WallSocketProvider`)
- 边缘 (`EdgeSocketProvider`)
- 路径点 (`PathSocketProvider`)
- 对称轴 (`SymmetrySocketProvider`)
- 轮廓边界 (`OutlineSocketProvider`)

**核心思想**: Socket = 世界给构件提供的机会点

#### Socket 结构：

```java
public record Socket(
    SocketType type,
    BlockPos center,
    Direction facing,
    Set<String> tags,
    Map<String, Object> metadata
)
```

---

## 四、工具系统（Tool Layer）

### 4.1 核心工具

| 工具 | 作用 | 实现类 |
|-----|------|--------|
| `SelectionTool` | 明确建造范围 | `com.formacraft.client.tool.SelectionTool` |
| `OutlineTool` | 复杂轮廓 | `com.formacraft.client.tool.OutlineTool` |
| `PathTool` | 道路 / 长城 / 连续布局 | `com.formacraft.client.tool.PathTool` |
| `ProtectedZoneTool` | 禁区 / 保护区 | `com.formacraft.client.tool.ProtectedZoneTool` |
| `SymmetryTool` | 对称 / 镜像 | `com.formacraft.client.tool.SymmetryTool` |
| `SemanticLabelTool` | 语义区域标注 | `com.formacraft.client.tool.SemanticLabelTool` |
| `ComponentTool` | 构件定义 / 拾取 | `com.formacraft.client.tool.ComponentTool` |

### 4.2 工具 → Prompt 的原则

**工具不会直接修改世界，而是：**
1. 转译为空间约束
2. 注入 `PromptAssembler`
3. 影响 LLM 的规划结果

#### 工具状态收集：

```java
// PromptAssembler 自动收集工具状态
ToolPromptBuilder.buildToolContext(ctx);

// 生成约束块
String constraints = spatialConstraints(ctx);
// 包含：选区、轮廓、路径、禁区、对称、语义标签
```

### 4.3 工具注册机制

```java
// 工具入口点
@FormacraftToolEntrypoint
public class MyCustomTool implements FormacraftTool {
    @Override
    public void onActivate(PlayerEntity player) { }
    
    @Override
    public void contributeToPrompt(PromptContext ctx) {
        // 将工具状态注入 Prompt
        ctx.addConstraint("my_tool", getMyToolState());
    }
}
```

---

## 五、PromptAssembler（AI 接口层）

### 5.1 职责

**PromptAssembler 是 Formacraft 的"大脑前额叶"。**

它负责：
- ✅ 收集工具状态
- ✅ 读取 Memory（RAG）
- ✅ 拼接 System Prompt + User Prompt
- ✅ 约束 LLM 输出格式

**位置**: `com.formacraft.ai.prompt.PromptAssembler`

### 5.2 Prompt 的职责分工

#### System Prompt（固定）
- 定义 AI 角色
- 定义 JSON Schema
- 定义核心规则

#### Context Prompt（动态）
- 工具状态（选区、路径、轮廓...）
- Memory 上下文（RAG）
- 约束条件

#### User Prompt（玩家输入）
- 自然语言描述

### 5.3 Prompt 组装流程

```java
public static String assemble(String userInput, PromptMode mode) {
    PromptContext ctx = new PromptContext();
    ctx.userMessage = userInput;
    ctx.mode = mode;
    
    // 1. 工具 → 结构化上下文
    ToolPromptBuilder.buildToolContext(ctx);
    
    // 2. 解析地形策略
    ctx.terrainPolicy = TerrainPolicyResolver.resolve(ctx);
    
    // 3. 拼装最终 Prompt
    return buildFinalPrompt(ctx);
}

private static String buildFinalPrompt(PromptContext ctx) {
    StringBuilder sb = new StringBuilder();
    
    // 1. System Role（AI 身份）
    sb.append(systemRole());
    
    // 2. ComponentQuery System Prompt
    sb.append(componentQuerySystemPrompt());
    
    // 3. Socket System Prompt
    sb.append(socketSystemPrompt());
    
    // 4. Memory Context（RAG）
    MemoryContext memory = retrieveMemory(ctx);
    if (memory != null) {
        sb.append(memoryContextBlock(memory));
    }
    
    // 5. Spatial Constraints（空间约束）
    sb.append(spatialConstraints(ctx));
    
    // 6. User Intent（玩家描述）
    sb.append(userIntent(ctx));
    
    // 7. Structured JSON Template
    sb.append(structuredJsonTemplate(ctx));
    
    return sb.toString();
}
```

### 5.4 LLM 输出约束

**关键规则**：
- ✅ 输出必须是 **VALID JSON**
- ✅ 不能包含注释或解释
- ✅ 必须严格遵循 Schema
- ✅ 使用 `null` 而不是省略可选字段

---

## 六、Memory 系统（Forma-Cortex）

### 6.1 为什么需要记忆

**没有记忆**：
- ❌ AI 是一次性工具
- ❌ 无法记住已建建筑
- ❌ 无法修改已有建筑

**有记忆**：
- ✅ AI 是"记得住建筑"的建筑师
- ✅ 支持 RAG（检索增强生成）
- ✅ 支持建筑演化（Mutation）

### 6.2 三层记忆模型

| 层级 | 作用 | 实现 |
|-----|------|------|
| **Spatial Memory** | 建筑在哪 | `SpatialIndex`（基于区块索引） |
| **Semantic Memory** | 它是什么 | `SemanticIndex`（倒排索引） |
| **Genetic Memory** | 它怎么建的 | `ProjectMemory.geneData`（完整 BuildingSpec） |

### 6.3 ProjectMemory 结构

```java
public class ProjectMemory {
    // 基础信息
    private String uuid;
    private String name;
    private String createdAt;
    private String lastModified;
    
    // 1. 空间记忆
    private SpatialBounds bounds;  // 最小/最大坐标
    
    // 2. 语义记忆
    private String description;     // AI 生成的描述
    private List<String> tags;      // 标签列表
    
    // 3. 基因记忆
    private BuildingSpec geneData;  // 完整的 BuildingSpec
    private BuildingGenome genome;  // BuildingGenome（如果存在）
    
    // 4. 关联关系
    private Relations relations;    // 连接的路径、父建筑、子建筑
}
```

### 6.4 Memory → Patch → Memory Update

**修改流程**：

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

#### 实现代码：

```java
// 1. 查找记忆
ProjectMemory memory = memoryManager.findAt(position);

// 2. 提取基因数据
BuildingSpec originalSpec = memory.getGeneData();

// 3. LLM 生成修改（包含原始 spec 作为上下文）
BuildingSpec modifiedSpec = llm.generateModification(
    originalSpec, 
    userRequest
);

// 4. 编译并应用 Patch
List<BlockPatch> patches = compiler.compile(modifiedSpec);
PatchExecutor.apply(world, origin, patches);

// 5. 更新记忆
GeneMutation mutation = analyzeMutation(originalSpec, modifiedSpec);
memoryManager.applyMutation(mutation, newBounds);
```

### 6.5 Memory 存储位置

- **路径**: `formacraft/memory/project_{uuid}.json`
- **管理**: `com.formacraft.server.memory.MemoryStorage`
- **索引**: `SpatialIndex` + `SemanticIndex`

---

## 七、Preview / Patch 流程

### 7.1 BuildConfirmPanel

**功能**：
- ✅ Patch 预览
- ✅ Diff 高亮（place / remove / replace）
- ✅ Apply / Undo / Redo

**位置**: `com.formacraft.client.ui.panel.BuildConfirmPanel`

### 7.2 为什么必须 Preview

> **AI 永远可能出错，Preview 是安全阀。**

#### 预览流程：

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

### 7.3 Patch 过滤管道

**在预览前，Patch 会经过过滤管道**：

```java
List<BlockPatch> filtered = PatchFilterPipeline.filter(
    patches,
    constraints  // 选区、禁区、对称等
);
```

**过滤器类型**：
- `SelectionOnlyFilter` - 仅保留选区内的 Patch
- `ForbiddenZoneFilter` - 过滤禁区
- `SymmetryFilter` - 对称约束
- `TerrainFilter` - 地形策略

### 7.4 BuildExecutionService（分 Tick 执行）

**为什么需要分 Tick 执行？**

> 避免服务器卡顿。大量方块修改如果一次性执行，会导致服务器 Tick 时间过长。

**实现**: `com.formacraft.server.build.BuildExecutionService`

#### 执行流程：

```java
// 1. 入队建造任务
BuildExecutionService.getInstance().queueBuild(
    world,
    origin,
    buildingSpec
);

// 2. 每个 Tick 执行部分方块（默认 200 个/Tick）
// 在 ServerTickEvents.END_WORLD_TICK 中执行
BuildExecutionService.tick(world);

// 3. 任务完成后自动创建 UndoEntry
// 支持玩家使用 /formacraft_undo 撤销
```

#### 配置：

```java
// 修改每 Tick 执行的方块数
public static final int BLOCKS_PER_TICK = 200;  // 可调整
```

### 7.5 Undo/Redo 系统

**实现**: `com.formacraft.common.patch.history.PatchHistoryManager`

#### 核心数据结构：

```java
// PatchTransaction：记录一次操作的前后状态
public record PatchTransaction(
    BlockPos origin,
    List<BlockPatch> patches,
    Map<BlockPos, BlockState> before,  // 修改前的方块状态
    Map<BlockPos, BlockState> after   // 修改后的方块状态
)

// 按玩家维护历史栈
private static final Map<UUID, Stacks> PER_PLAYER;
```

#### 使用流程：

```java
// 1. 应用 Patch 并记录历史
PatchHistoryManager.applyWithHistory(
    world,
    playerId,
    origin,
    patches
);

// 2. 撤销
boolean success = PatchHistoryManager.undo(world, playerId);

// 3. 重做
boolean success = PatchHistoryManager.redo(world, playerId);
```

#### 关键特性：

- ✅ **按玩家隔离** - 每个玩家有独立的历史栈
- ✅ **事务快照** - 记录 before/after 状态，保证可逆
- ✅ **自动清理** - 最多保留 50 个历史记录
- ✅ **Memory 集成** - Undo/Redo 时自动更新 Memory

#### Memory 集成：

```java
// 应用 Patch 时更新 Memory
updateMemoryFromPatch(world, origin, patches);

// Undo 时反向更新 Memory
updateMemoryFromPatchUndo(world, tx);

// Redo 时再次更新 Memory
updateMemoryFromPatch(world, tx.origin(), tx.patches());
```

---

## 八、Socket 系统（H 系列）

### 8.1 SocketProvider

**从世界中生成 Socket**：

```java
public interface SocketProvider {
    List<Socket> provideSockets(BuildContext ctx);
}
```

#### 已实现的 Provider：

- `WallSocketProvider` - 墙面 Socket
- `EdgeSocketProvider` - 边缘 Socket
- `PathPolylineSocketProvider` - 路径点 Socket
- `OutlinePolygonSocketProvider` - 轮廓边界 Socket
- `SelectionBoxSocketProvider` - 选区边界 Socket

### 8.2 SocketMatcher

**根据 ComponentPlacementSpec 过滤合法 Socket**：

```java
public class SocketMatcher {
    public static List<Socket> match(
        List<Socket> sockets,
        ComponentPlacementSpec spec
    ) {
        return sockets.stream()
            .filter(socket -> isCompatible(socket, spec))
            .collect(Collectors.toList());
    }
}
```

**匹配规则**：
- Socket 类型必须匹配
- 朝向必须兼容
- 尺寸约束必须满足
- 上下文必须匹配（内外、边缘等）

### 8.3 Socket → Patch Anchor（H3）

**把"抽象位置"变成"方块原点"**：

```java
// Socket 提供抽象位置
Socket socket = socketProvider.provideSockets(ctx).get(0);

// 转换为构件锚点
ComponentAnchor anchor = SocketAnchorPlacement.place(
    socket,
    componentDefinition
);

// 生成 Patch
List<BlockPatch> patches = ComponentVariantCompiler.compile(
    componentDefinition,
    anchor
);
```

**这是整个系统从"语义"落地到"几何"的关键一步。**

---

## 九、Component Variant & Archetype

### 9.1 构件不是静态模型

**构件支持**：
- ✅ 缩放 / 拉伸
- ✅ 分段重复
- ✅ 材质语义替换
- ✅ 比例变化

**核心思想**: 
> 构件 = 基因，Variant = 表型

### 9.2 ComponentQuery & Ranking

**AI 通过语义查询构件库**：

```json
{
  "component_query": {
    "semantic": {
      "role": "door",
      "tags": ["entry", "main"],
      "importance": ["role", "style"]
    },
    "context": {
      "placement": "wall",
      "side": "exterior",
      "heightLevel": "ground"
    },
    "geometry": {
      "requiresOpening": true,
      "openingWidth": 2,
      "openingHeight": 3
    },
    "style": {
      "styleProfile": "CHINESE_VILLA",
      "materialTone": "wood"
    }
  }
}
```

**查询流程**：

```java
// 1. 解析 ComponentQuery
ComponentQuery query = parseComponentQuery(json);

// 2. 从构件库查询
List<ComponentPrototype> candidates = ComponentCatalog.query(query);

// 3. 排序（匹配度）
candidates.sort(ComponentRanker.byRelevance(query));

// 4. 选择最佳匹配
ComponentPrototype selected = candidates.get(0);

// 5. 生成 Variant
ComponentVariant variant = VariantGenerator.generate(
    selected,
    query.geometry,
    query.style
);
```

### 9.3 Archetype 系统

**Archetype = 构件族（共享语义的原型集合）**

```java
public class Archetype {
    public String id;
    public String name;
    public ComponentCategory category;
    public List<ComponentPrototype> prototypes;  // 同一族的多个变体
    public ArchetypeMetadata metadata;
}
```

**示例**：
- `CHINESE_DOOR_ARCHETYPE` - 中式门族（包含多种门样式）
- `COLUMN_ARCHETYPE` - 柱子族（石柱、木柱、装饰柱...）

---

## 十、UI 系统设计原则

### 10.1 HUD 而非 Screen

**设计原则**：
- ✅ 不暂停游戏
- ✅ 与世界交互并行
- ✅ 输入路由严格控制

**实现**: `com.formacraft.client.ui.FormaCraftHudOverlay`

### 10.2 ToolPanel 重构原则

**工具列表固定**：
- 工具列表在左侧固定显示
- 当前激活工具高亮

**工具选项独立滚动**：
- 每个工具的选项面板独立
- 支持长列表滚动

**当前工具语义清晰可见**：
- 显示工具名称和描述
- 显示当前状态（选区大小、路径点数等）

---

## 十一、扩展指南（给未来开发者）

### 11.1 如何加新工具

#### 步骤 1: 实现 FormacraftTool

```java
public class MyCustomTool implements FormacraftTool {
    @Override
    public void onActivate(PlayerEntity player) {
        // 工具激活时的逻辑
    }
    
    @Override
    public void onDeactivate(PlayerEntity player) {
        // 工具停用时的逻辑
    }
    
    @Override
    public void contributeToPrompt(PromptContext ctx) {
        // 将工具状态注入 Prompt
        ctx.addConstraint("my_tool", getMyToolState());
    }
    
    @Override
    public void render(MatrixStack matrices, Camera camera) {
        // 渲染工具可视化
    }
}
```

#### 步骤 2: 注册工具

```java
// 在 BuiltinToolsEntrypoint 中注册
@FormacraftToolEntrypoint
public class BuiltinToolsEntrypoint {
    public static void register() {
        ToolManager.register(new MyCustomTool());
    }
}
```

#### 步骤 3: 提供 Socket（可选）

```java
public class MyToolSocketProvider implements ToolBasedSocketProvider {
    @Override
    public List<Socket> provide(World world, SocketQueryContext ctx) {
        // 从工具状态生成 Socket
        return generateSocketsFromToolState(ctx);
    }
}
```

### 11.2 如何加新 Skeleton

#### 步骤 1: 扩展 SkeletonType

```java
enum SkeletonType {
    // ... 现有类型
    MY_CUSTOM_SKELETON  // 新增
}
```

#### 步骤 2: 创建 SkeletonPlan 子类

```java
public class MyCustomSkeletonPlan extends SkeletonPlan {
    @Override
    public SkeletonType type() {
        return SkeletonType.MY_CUSTOM_SKELETON;
    }
}
```

#### 步骤 3: 实现 Generator

```java
public class MyCustomSkeletonGenerator implements ISkeletonGenerator {
    @Override
    public List<BlockPatch> generate(
        GenerationContext ctx,
        ExecutableSkeletonPlan plan
    ) {
        // 实现生成逻辑
        List<BlockPatch> patches = new ArrayList<>();
        // ... 生成 Patch
        return patches;
    }
}
```

#### 步骤 4: 注册 Generator

```java
SkeletonGeneratorRegistry.register(
    SkeletonType.MY_CUSTOM_SKELETON,
    new MyCustomSkeletonGenerator()
);
```

#### 步骤 5: 配合 TerrainStrategy

```java
// 在生成器中考虑地形策略
TerrainStrategy strategy = ctx.terrainPolicy.getStrategy();
if (strategy == TerrainStrategy.ADAPTIVE) {
    // 自适应地形
} else if (strategy == TerrainStrategy.FLATTEN) {
    // 平整地形
}
```

### 11.3 如何加新构件

#### 步骤 1: 定义 Component JSON

```json
{
  "schema": "formacraft.component.v1",
  "id": "my_custom_component",
  "name": "我的自定义构件",
  "category": "GENERIC",
  "tags": ["custom", "decorative"],
  "size": { "w": 3, "h": 2, "d": 3 },
  "anchor": { "dx": 1, "dy": 0, "dz": 1, "facing": "NORTH" },
  "placementSpec": {
    "attachment": "WALL",
    "spatialContext": "EXTERIOR",
    "facingPolicy": "DERIVED_FROM_HOST"
  },
  "blocks": [
    { "dx": 0, "dy": 0, "dz": 0, "block": "minecraft:stone" }
  ]
}
```

#### 步骤 2: 提供 ComponentAnchor

```java
// 系统会自动从 Socket 生成 Anchor
// 如果需要自定义，可以实现 ComponentAnchorProvider
```

#### 步骤 3: 配置 PlacementSpec

```java
ComponentPlacementSpec spec = new ComponentPlacementSpec();
spec.attachment = AttachmentType.WALL;
spec.spatialContext = SpatialContext.EXTERIOR;
spec.facingPolicy = FacingPolicy.DERIVED_FROM_HOST;
spec.allowedSockets.add(SocketType.WALL_SURFACE);
```

#### 步骤 4: 自动进入 AI 可用库

**构件定义后，会自动：**
- ✅ 注册到 `ComponentCatalog`
- ✅ 可被 `ComponentQuery` 查询
- ✅ AI 可以在 Blueprint 中使用

---

## 十二、API 参考

### 12.1 核心 API

#### PatchExecutor

```java
public class PatchExecutor {
    // 应用 Patch 到世界
    public static void apply(
        ServerWorld world,
        BlockPos origin,
        List<BlockPatch> patches
    );
}
```

#### PromptAssembler

```java
public class PromptAssembler {
    // 组装 Prompt
    public static String assemble(
        String userInput,
        PromptMode mode
    );
}
```

#### MemoryManager

```java
public class MemoryManager {
    // 注册建筑到记忆
    public ProjectMemory registerBuilding(
        GeneratedStructure structure,
        BuildingSpec spec
    );
    
    // 查找记忆
    public ProjectMemory findAt(BlockPos position);
    public ProjectMemory findByName(String name);
    
    // 更新记忆
    public void updateMemory(ProjectMemory memory);
    public ProjectMemory applyMutation(
        GeneMutation mutation,
        BlockPos minPos,
        BlockPos maxPos
    );
}
```

#### BuildExecutionService

```java
public class BuildExecutionService {
    // 入队建造任务
    public void queueBuild(
        ServerWorld world,
        BlockPos origin,
        BuildingSpec spec
    );
    
    // 获取 UndoService（用于撤销）
    public UndoService getUndoService();
}
```

#### PatchHistoryManager

```java
public class PatchHistoryManager {
    // 应用 Patch 并记录历史
    public static void applyWithHistory(
        ServerWorld world,
        UUID playerId,
        BlockPos origin,
        List<BlockPatch> patches
    );
    
    // 撤销
    public static boolean undo(ServerWorld world, UUID playerId);
    
    // 重做
    public static boolean redo(ServerWorld world, UUID playerId);
    
    // 检查是否可以撤销/重做
    public static boolean canUndo(UUID playerId);
    public static boolean canRedo(UUID playerId);
}
```

#### ComponentCatalog

```java
public class ComponentCatalog {
    // 查询构件
    public static List<ComponentPrototype> query(ComponentQuery query);
    
    // 获取构件
    public static ComponentPrototype getById(String id);
    
    // 注册构件
    public static void register(ComponentDefinition definition);
}
```

### 12.2 网络协议

#### C2S（客户端到服务端）

- `BuildRequestPayload` - 建造请求
- `PatchApplyPayload` - 应用 Patch
- `PatchUndoPayload` - 撤销
- `PatchRedoPayload` - 重做

#### S2C（服务端到客户端）

- `PreviewPayload` - 预览数据
- `BuildResponsePayload` - 建造响应

**位置**: `com.formacraft.common.network.FormaCraftNetworking`

### 12.3 Python 后端 API

#### POST /build

```python
# 请求
{
  "prompt": "在锚点位置生成12*15中式别墅",
  "anchor": {"x": 0, "y": 64, "z": 0},
  "constraints": { /* 空间约束 */ }
}

# 响应
{
  "building_spec": { /* BuildingSpec */ },
  "status": "success"
}
```

**位置**: `python_backend/app/routes/build.py`

---

## 十三、开发工作流程

### 13.1 本地开发环境

#### 前置要求
- Java 21+
- Python 3.12+
- Gradle 8.0+
- Minecraft 1.21.10

#### 启动步骤

1. **启动 Python 后端**：
```bash
cd python_backend
pip install -r requirements.txt
uvicorn app.main:app --reload
```

2. **构建 Java 模组**：
```bash
./gradlew build
```

3. **运行 Minecraft（开发环境）**：
```bash
./gradlew runClient
```

### 13.2 调试技巧

#### 查看 Prompt

```java
// 在 PromptAssembler 中添加日志
FormacraftMod.LOGGER.info("Generated Prompt:\n{}", prompt);
```

#### 查看 Patch

```java
// 在编译器中添加日志
FormacraftMod.LOGGER.info("Generated {} patches", patches.size());
for (BlockPatch patch : patches) {
    FormacraftMod.LOGGER.debug("Patch: {}", patch);
}
```

#### 查看 Memory

```java
// 在 MemoryManager 中添加日志
ProjectMemory memory = memoryManager.findAt(position);
FormacraftMod.LOGGER.info("Found memory: {}", memory);
```

### 13.3 测试流程

1. **单元测试**：
```bash
./gradlew test
```

2. **集成测试**：
- 启动开发服务器
- 使用工具创建建筑
- 验证 Patch 生成和应用

3. **端到端测试**：
- 完整流程：输入 → LLM → Blueprint → Patch → 世界

---

## 十四、故障排除

### 14.1 常见问题

#### 问题 1: LLM 返回无效 JSON

**症状**: 解析 BuildingSpec 失败

**解决方案**:
1. 检查 `PromptAssembler` 的 JSON Schema 是否完整
2. 在 Python 后端添加 JSON 验证
3. 使用更严格的 LLM 提示

#### 问题 2: Patch 没有应用

**症状**: 预览正常，但确认后世界没有变化

**检查清单**:
- ✅ 服务端是否收到 `PatchApplyPayload`
- ✅ `PatchExecutor.apply()` 是否被调用
- ✅ 世界是否为 `ServerWorld`
- ✅ 是否有权限检查阻止了应用

#### 问题 3: Memory 没有更新

**症状**: 建筑生成后，Memory 中没有记录

**检查清单**:
- ✅ `MemoryManager.registerBuilding()` 是否被调用
- ✅ `MemoryStorage.saveMemory()` 是否成功
- ✅ 文件系统权限是否正确

#### 问题 4: 构件找不到匹配的 Socket

**症状**: AI 选择了构件，但没有地方放置

**解决方案**:
1. 检查 `SocketProvider` 是否生成了足够的 Socket
2. 检查 `ComponentPlacementSpec` 是否过于严格
3. 添加 fallback 机制（如果没有匹配 Socket，使用默认位置）

#### 问题 5: 建造任务没有执行

**症状**: 确认建造后，世界没有变化

**检查清单**:
- ✅ `BuildExecutionService.registerTickHandler()` 是否被调用
- ✅ 是否在主线程中入队任务
- ✅ 检查日志是否有错误信息
- ✅ 确认 `BLOCKS_PER_TICK` 配置是否合理

#### 问题 6: Undo/Redo 不工作

**症状**: 使用撤销命令后没有效果

**检查清单**:
- ✅ `PatchHistoryManager.applyWithHistory()` 是否被调用
- ✅ 玩家 UUID 是否正确
- ✅ 历史栈是否为空（使用 `canUndo()` 检查）
- ✅ 世界是否为 `ServerWorld`

### 14.2 日志位置

- **Java 日志**: `.minecraft/logs/latest.log`
- **Python 日志**: 控制台输出（如果使用 uvicorn）

### 14.3 性能优化

#### Patch 生成优化

```java
// 使用并行流处理大量 Patch
List<BlockPatch> patches = components.parallelStream()
    .flatMap(comp -> compileComponent(comp).stream())
    .collect(Collectors.toList());
```

#### Memory 查询优化

```java
// 使用空间索引加速查询
SpatialIndex index = memoryManager.getSpatialIndex();
List<ProjectMemory> results = index.findNear(position, radius);
```

---

## 十五、Formacraft 的终极定位

### 15.1 这不是一个"建房模组"

**它是**：
- ✅ **Minecraft 的建筑 DSL** - 用语义描述建筑
- ✅ **AI 的空间操作系统** - AI 理解空间并操作
- ✅ **玩家与世界之间的"建筑语言层"** - 翻译意图为方块

### 15.2 设计目标回顾

> **让 AI 理解"建筑是什么"，而不是"方块怎么放"。**

### 15.3 长期演化

**这套系统是为长期演化而设计的：**
- ✅ 允许错误（Preview + Undo）
- ✅ 允许修正（Memory + Mutation）
- ✅ 允许成长（可扩展的工具、构件、生成器）

---

## 📌 结语

如果你读到这里，说明你已经理解了 Formacraft 的本质：

> **让 AI 理解"建筑是什么"，而不是"方块怎么放"。**

### 下一步

1. **阅读代码** - 从 `FormacraftMod` 开始，追踪完整流程
2. **运行示例** - 使用工具创建建筑，观察数据流
3. **扩展系统** - 添加新工具、新构件、新生成器
4. **贡献文档** - 完善这份文档，帮助未来的开发者

### 相关文档

- `docs/SKELETON_GENERATOR_SYSTEM.md` - Skeleton 生成器系统
- `docs/COMPONENT_SYSTEM.md` - 构件系统
- `docs/MEMORY_SYSTEM_IMPLEMENTATION.md` - Memory 系统实现
- `docs/PROMPT_ASSEMBLER_AUTOMATION.md` - Prompt 组装系统

---

**Formacraft v1.0**  
*让建筑成为语言，让 AI 成为建筑师。*
