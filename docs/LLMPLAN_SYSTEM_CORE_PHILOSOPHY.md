# LlmPlan 系统核心思想与工作机制解析

## 🎯 核心思想

### 设计哲学：**"AI 负责规划，Java 负责实现"**

新系统（LlmPlan）的核心思想是**职责分离**：

```
┌─────────────────────────────────────────────────────────┐
│  AI（LLM）的职责                                        │
│  - 理解用户意图                                         │
│  - 进行空间规划                                         │
│  - 描述"想要什么"（语义组件）                           │
│  - 不关心具体方块                                       │
└─────────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────────┐
│  Java 端的职责                                          │
│  - 将语义组件转换为具体方块                             │
│  - 处理几何生成                                         │
│  - 处理材质选择                                         │
│  - 处理地形适应                                         │
│  - 处理约束裁剪                                         │
└─────────────────────────────────────────────────────────┘
```

### 关键原则

1. **LLM 永远不直接 SetBlock**
   - LLM 只输出"语义描述"（如 "一个 12x15 的住宅主体"）
   - 不输出具体的方块 ID（如 "minecraft:stone_bricks"）

2. **语义驱动，而非方块驱动**
   - LLM 描述"是什么"（WALL, ROOF, ENTRANCE）
   - Java 决定"用什么方块"（根据风格、材质、随机性）

3. **结构化输出，而非自由文本**
   - LLM 输出严格的 JSON 格式
   - 包含组件类型、尺寸、位置、特征等结构化信息

## 🔄 完整工作流程

### 阶段 1：用户输入 → Prompt 组装

```
用户输入："在锚点位置生成12*15中式别墅"
  ↓
工具状态收集（PathTool, SelectionTool, OutlineTool, ...）
  ↓
PromptAssembler.assemble()
  ↓
生成结构化 Prompt：
  - SYSTEM PROMPT（AI 角色定义）
  - CONSTRAINT BLOCK（空间约束，机器可读）
  - USER INTENT（用户意图，自然语言）
  - OUTPUT CONTRACT（JSON Schema，输出格式）
```

**核心**：PromptAssembler 将"玩家在游戏中的操作"转换为"AI 可以理解的空间约束语言"

### 阶段 2：LLM 调用 → LlmPlan 生成

```
Prompt → Python 后端 → LLM API
  ↓
LLM 输出 JSON：
{
  "mode": "build",
  "style_profile": "CHINESE_VILLA",
  "anchor": { "x": -969, "y": 64, "z": -671 },
  "global_constraints": {
    "facing": "NORTH",
    "symmetry": "MIRROR_Z",
    "terrain_strategy": "ADAPTIVE"
  },
  "layout": {
    "skeleton_type": "COMPOUND",
    "slots": [
      {
        "slot_id": "main_building",
        "anchor": { "x": 0, "y": 0, "z": 0 },
        "program": "RESIDENTIAL",
        "component_preset_id": "chinese_main_house"
      }
    ]
  },
  "components": [
    {
      "component_type": "MASS_MAIN",
      "slot_id": "main_building",
      "relative_position": { "x": -6, "y": 0, "z": -7 },
      "dimensions": { "width": 12, "depth": 15, "height": 8 },
      "features": ["HIP_AND_GABLE_ROOF", "SYMMETRICAL_LAYOUT"]
    },
    {
      "component_type": "ENTRANCE",
      "slot_id": "main_building",
      "relative_position": { "x": 6, "y": 0, "z": 0 },
      "dimensions": { "width": 3, "depth": 2, "height": 4 },
      "features": ["DOUBLE_DOORS", "OVERHANG"]
    }
  ]
}
```

**核心**：LLM 输出的是"语义蓝图"，不是具体的方块列表

### 阶段 3：LlmPlan 解析 → 语义组件

```
LlmPlan JSON → LlmPlanParser.parseAndValidate()
  ↓
强类型的 LlmPlan 对象
  ↓
ComponentPlanCompiler.compile()
  ↓
遍历 components[]，创建 SemanticComponent：
  - componentType: "MASS_MAIN"
  - slot: Slot 对象（包含 anchor, program, preset）
  - source: Component 对象（原始 LLM 输出）
```

**核心**：将 LLM 的 JSON 转换为强类型的 Java 对象

### 阶段 4：语义组件 → BlockPatch（智能路由）

```
SemanticComponent → SmartGeneratorRouter.generate()
  ↓
┌─────────────────────────────────────────────────────┐
│ 1. 尝试新系统（ComponentGenerator）                │
│    - ComponentGeneratorRegistry.getGenerator("MASS_MAIN")   │
│    - MassMainGenerator.generate(semantic)          │
│    - 输出：List<BlockPatch>                        │
└─────────────────────────────────────────────────────┘
  ↓ (如果失败)
┌─────────────────────────────────────────────────────┐
│ 2. 回退到传统系统（StructureGenerator）            │
│    - StructureGeneratorAdaptor.createFor("MASS_MAIN")│
│    - 映射到 BuildingType.HOUSE                     │
│    - HouseGenerator.generate(spec, origin, world)   │
│    - 转换为 List<BlockPatch>                       │
└─────────────────────────────────────────────────────┘
```

**核心**：智能路由自动选择最适合的生成器，用户完全透明

### 阶段 5：生成器执行 → 基础 BlockPatch

#### 新系统生成器（以 MassMainGenerator 为例）

```java
MassMainGenerator.generate(SemanticComponent semantic) {
  1. 解析 Component：
     - dimensions: { width: 12, depth: 15, height: 8 }
     - relativePosition: { x: -6, y: 0, z: -7 }
     - features: ["HIP_AND_GABLE_ROOF", "SYMMETRICAL_LAYOUT"]
  
  2. 检查 features：
     - hasRoof = true (匹配 "HIP_AND_GABLE_ROOF" 中的 "roof")
     - hasWindows = false (默认生成，因为是 RESIDENTIAL)
     - hasDoors = false (默认生成，因为是 RESIDENTIAL)
  
  3. 生成基础结构：
     - 遍历 12x15x8 的空间
     - 根据位置确定 SemanticPart（WALL_BASE, WALL, WALL_ACCENT）
     - 使用 PaletteLibrary 权重随机选择方块
  
  4. 生成细节：
     - 窗户位置 → 放置 WINDOW 方块
     - 门位置 → 放置 DOORWAY（门洞）
     - 屋顶位置 → 放置 ROOF_SURFACE 方块
     - 装饰位置 → 放置 DECOR 方块
  
  5. 输出：List<BlockPatch>（相对坐标）
}
```

**核心**：生成器根据 features 和位置智能生成细节

### 阶段 6：后处理 → 增强 BlockPatch

```
List<BlockPatch> → PostProcessPipeline.process()
  ↓
1. DetailEnhancementPostProcessor
   - 在基础结构上添加装饰元素
   - 檐口、装饰柱、边缘装饰
  ↓
2. MaterialVariationPostProcessor
   - 根据风格配置调整材质
   - 添加材质变化（裂纹、苔藓等）
  ↓
3. TerrainAdaptationPostProcessor
   - 根据地形策略调整 Y 坐标
   - PRESERVE / ADAPTIVE / TERRACE / FLATTEN
  ↓
增强后的 List<BlockPatch>
```

**核心**：后处理步骤增强建筑质量，使其更加自然和完整

### 阶段 7：约束裁剪 → 最终 BlockPatch

```
List<BlockPatch> → PatchFilterPipeline.filter()
  ↓
1. ForbiddenZoneFilter
   - 移除禁区内的方块
  ↓
2. OutlineClipFilter
   - 移除轮廓外的方块
  ↓
3. SelectionOnlyFilter
   - 只保留选区内的方块
  ↓
4. SymmetryFilter
   - 生成对称的方块
  ↓
最终可应用的 List<BlockPatch>
```

**核心**：确保生成的方块符合玩家的所有约束

### 阶段 8：预览 / 应用

```
List<BlockPatch> → 转换为 PlannedBlock
  ↓
PreviewStorage.storeStructure()
  ↓
客户端预览渲染
  ↓
用户确认 → BuildExecutionService.enqueueBuild()
  ↓
分 Tick 执行建造
```

## 🧠 核心设计理念

### 1. 语义抽象层

**问题**：如果让 LLM 直接输出方块列表，会有什么问题？
- LLM 不知道 Minecraft 的方块 ID
- LLM 无法处理材质变化、随机性
- LLM 无法适应地形
- LLM 无法处理约束

**解决方案**：引入"语义抽象层"

```
用户意图："中式别墅"
  ↓
LLM 理解 → 语义描述：
  - "一个 12x15 的住宅主体（MASS_MAIN）"
  - "一个入口（ENTRANCE）"
  - "立面窗户（FACADE_WINDOWS）"
  - "庭院空间（COURTYARD_SPACE）"
  ↓
Java 实现 → 具体方块：
  - MASS_MAIN → 使用 PaletteLibrary 选择石头/砖块
  - ENTRANCE → 生成门洞 + 门框
  - FACADE_WINDOWS → 生成窗户（玻璃/铁栏杆）
  - COURTYARD_SPACE → 生成铺装（石板/砖块）
```

### 2. 分层决策系统

```
用户输入
  ↓
AI 规划层（LLM）
  - 空间感知
  - 布局规划
  - 体量设计
  - 功能部件
  ↓
语义生成层（ComponentGenerator）
  - 几何生成
  - 细节生成
  - 材质选择
  ↓
后处理层（PostProcessor）
  - 细节增强
  - 材质变化
  - 地形适应
  ↓
约束层（PatchFilter）
  - 禁区裁剪
  - 轮廓裁剪
  - 对称处理
  ↓
最终方块
```

### 3. 可组合性

**核心**：建筑由多个语义组件组合而成

```
LlmPlan.components = [
  MASS_MAIN,      // 主体
  ENTRANCE,       // 入口
  FACADE_WINDOWS, // 窗户
  ROOF,           // 屋顶
  COURTYARD_SPACE // 庭院
]
  ↓
每个组件独立生成
  ↓
合并为完整的建筑
```

**优势**：
- 组件可以复用
- 组件可以组合
- 组件可以独立优化

### 4. 风格驱动

**核心**：风格决定材质，而非硬编码

```
style_profile: "CHINESE_VILLA"
  ↓
PaletteLibrary.forStyle("CHINESE_VILLA")
  ↓
Palette.pick(SemanticPart.WALL)
  ↓
权重随机选择：
  - 70% stone_bricks
  - 15% cracked_stone_bricks
  - 10% mossy_stone_bricks
  - 5% cobblestone
```

**优势**：
- 同一个生成器，不同风格产生不同效果
- 风格可以扩展（添加新的 Palette）
- 材质有自然变化（不是贴图感）

## 📊 数据流详解

### 输入数据结构

#### PromptAssemblerInput
```java
{
  userText: "在锚点位置生成12*15中式别墅",
  anchor: BlockPos(-969, 64, -671),
  facing: NORTH,
  selectionRegion: SelectionBox(...),
  outlineRegion: OutlineShape(...),
  noBuildZones: List<PolygonNoBuildZone>(...),
  symmetryPlane: SymmetryPlane(...),
  terrainPolicy: TerrainPolicy(ADAPTIVE, ...),
  semanticLabels: List<SemanticLabel>(...)
}
```

#### LlmPlan（LLM 输出）
```java
{
  mode: "build",
  style_profile: "CHINESE_VILLA",
  anchor: Vec3i(-969, 64, -671),
  global_constraints: {
    facing: "NORTH",
    symmetry: "MIRROR_Z",
    terrain_strategy: "ADAPTIVE"
  },
  layout: {
    skeleton_type: "COMPOUND",
    slots: [Slot(...), ...]
  },
  components: [Component(...), ...]
}
```

#### SemanticComponent（Java 内部）
```java
{
  componentType: "MASS_MAIN",
  slot: Slot {
    slotId: "main_building",
    anchor: Vec3i(0, 0, 0),
    program: "RESIDENTIAL",
    componentPresetId: "chinese_main_house"
  },
  source: Component {
    component_type: "MASS_MAIN",
    dimensions: { width: 12, depth: 15, height: 8 },
    features: ["HIP_AND_GABLE_ROOF", ...]
  }
}
```

#### BlockPatch（最终输出）
```java
[
  BlockPatch(PLACE, -6, 0, -7, "minecraft:stone_bricks"),
  BlockPatch(PLACE, -5, 0, -7, "minecraft:cracked_stone_bricks"),
  BlockPatch(PLACE, -6, 1, -7, "minecraft:glass"), // 窗户
  ...
]
```

### 转换链

```
PromptAssemblerInput
  ↓ (PromptAssembler)
JSON Prompt String
  ↓ (LLM)
LlmPlan JSON
  ↓ (LlmPlanParser)
LlmPlan Object
  ↓ (ComponentPlanCompiler)
List<SemanticComponent>
  ↓ (SmartGeneratorRouter → ComponentGenerator)
List<BlockPatch>
  ↓ (PostProcessPipeline)
Enhanced List<BlockPatch>
  ↓ (PatchFilterPipeline)
Filtered List<BlockPatch>
  ↓ (转换为 PlannedBlock)
List<PlannedBlock>
  ↓ (BuildExecutionService)
实际放置方块
```

## 🎯 核心优势

### 1. 职责清晰

- **AI 负责**：理解意图、空间规划、语义描述
- **Java 负责**：几何生成、材质选择、约束处理

### 2. 可扩展性强

- **添加新组件类型**：只需实现 `ComponentGenerator`
- **添加新风格**：只需添加新的 `Palette`
- **添加新后处理器**：只需实现 `PostProcessor`

### 3. 质量保证

- **智能路由**：自动选择最适合的生成器
- **后处理增强**：细节装饰、材质变化、地形适应
- **约束裁剪**：确保符合玩家要求

### 4. 用户友好

- **用户透明**：不需要知道后台实现
- **自动选择**：系统自动选择最适合的生成器
- **功能完整**：确保生成的建筑质量

## 📝 总结

### 核心思想

**"AI 负责规划，Java 负责实现"**

- AI 输出"语义蓝图"（想要什么）
- Java 实现"具体方块"（怎么实现）

### 工作流程

1. **用户输入** → Prompt 组装
2. **LLM 调用** → LlmPlan 生成
3. **LlmPlan 解析** → 语义组件
4. **智能路由** → 选择生成器
5. **生成器执行** → 基础 BlockPatch
6. **后处理** → 增强 BlockPatch
7. **约束裁剪** → 最终 BlockPatch
8. **预览/应用** → 实际建造

### 设计优势

- ✅ 职责分离：AI 和 Java 各司其职
- ✅ 语义驱动：不依赖具体方块
- ✅ 可组合性：组件可以复用和组合
- ✅ 风格驱动：风格决定材质
- ✅ 智能路由：自动选择最适合的生成器
- ✅ 用户透明：用户不需要知道后台实现

