# Tool → PromptAssembler（自动化系统）实现总结

## 实现状态

### ✅ 已完全实现

建议中的 Tool → PromptAssembler（自动化）系统已经全部实现：

1. ✅ **System Role** - AI 身份定义（固定）
2. ✅ **Spatial Constraints Block** - 空间约束块（自动化）
   - ✅ Anchor Block（锚点 + 朝向）
   - ✅ Selection Block（选区约束）
   - ✅ Footprint Block（轮廓约束）
   - ✅ No-Build Block（禁区约束）
   - ✅ Symmetry Block（对称约束）
   - ✅ Semantic Block（区域语义标注）
3. ✅ **User Intent** - 玩家原始描述
4. ✅ **Output Contract** - 输出契约（JSON Schema）

## 核心定位

**一句话定义**：
- PromptAssembler = 把"玩家在世界里做的事"翻译成"AI 能严格遵守的空间约束语言"
- 它不是聊天拼字符串，而是**空间 DSL（领域语言）生成器**

## 输入 / 输出职责

### 输入（来自客户端 / 世界）

```
PromptContext {
    AnchorPoint anchor;                  // 锚点（可能为空）
    FacingDirection facing;              // 朝向（可选）
    SelectionRegion selection;            // 选区（可选）
    FootprintRegion footprint;            // 轮廓（可选）
    List<NoBuildZone> noBuildZones;       // 禁区（可选）
    SymmetryPlane symmetry;               // 对称（可选）
    ToolSemanticTags semanticTags;        // 区域语义
    UserNaturalLanguage userInput;        // 玩家原始输入
}
```

### 输出（给 LLM）

```
SYSTEM PROMPT
CONSTRAINT BLOCK (machine-readable)
USER INTENT (natural language)
OUTPUT CONTRACT (JSON schema)
```

## 核心组件

### 1. System Role（AI 身份，永远固定）

**功能**：
- 定义 AI 的身份和职责
- 明确 AI 不直接放置方块，只输出 JSON 蓝图
- 强调必须遵守约束

**内容**：
```
You are Formacraft Core, a Minecraft architectural planning engine.

You do NOT place blocks directly.
You ONLY output structured JSON building blueprints.

All geometry must obey the spatial constraints below.
If constraints conflict, you must adapt the design, not break constraints.

Output MUST be valid JSON. No explanation text.
```

### 2. Anchor Block（锚点 + 朝向）

**功能**：
- 定义锚点位置和朝向
- 明确所有相对位置以锚点为原点 (0,0,0)

**语义**：
- "不是随便放，而是围绕锚点组织空间"

**格式**：
```
ANCHOR:
- anchor_position: (x, y, z)
- anchor_semantic: build around anchor
- facing: NORTH | SOUTH | EAST | WEST | AUTO

Interpret all relative positions with anchor as origin (0,0,0).
```

### 3. Selection Block（选区约束）

**功能**：
- 定义选区边界约束
- 要求所有建筑组件必须在选区内

**格式**：
```
SELECTION CONSTRAINT:
- All building components MUST be fully inside the selected region.
- You may scale, rotate, or decompose buildings to fit.
- Do NOT place anything outside the region.
```

### 4. Footprint Block（轮廓约束）

**功能**：
- 定义建筑轮廓约束
- 要求所有地面接触方块必须在轮廓内

**格式**：
```
FOOTPRINT CONSTRAINT:
- The building footprint is explicitly defined.
- All ground-contact blocks MUST be inside the footprint.
- Upper floors may overhang ONLY if explicitly reasonable for the style.
```

### 5. No-Build Block（禁区约束）

**功能**：
- 定义禁区约束
- 要求必须避开禁区

**格式**：
```
NO-BUILD ZONES:
- The following areas are strictly forbidden.
- You must route, shape, or omit components to avoid them.
- Never place blocks inside forbidden zones.
```

### 6. Symmetry Block（对称约束）

**功能**：
- 定义对称约束
- 要求设计必须尊重对称轴

**格式**：
```
SYMMETRY CONSTRAINT:
- The design MUST respect X-axis symmetry.
- All major components should be mirrored across the symmetry plane.
```

### 7. Semantic Block（区域语义标注）

**功能**：
- 定义区域语义标签
- 为不同区域提供语义指导

**格式**：
```
SEMANTIC REGIONS:
- courtyard: open, non-roofed, low height
- sacred: central, dominant, vertical emphasis
- circulation: paths, stairs, bridges only
- residential: modular, repeatable units
```

### 8. User Intent（玩家原始描述）

**功能**：
- 传递玩家的自然语言输入
- 保持原始意图

**格式**：
```
USER REQUEST:
[玩家的原始输入文本]
```

### 9. Output Contract（输出契约）

**功能**：
- 定义严格的 JSON 输出格式
- 确保 AI 输出可解析的结构化数据

**格式**：
```
OUTPUT FORMAT (STRICT JSON):

{
  "style_profile": "string",
  "skeleton_type": "string",
  "components": [
    {
      "semantic": "TOWER | WALL | HALL | COURTYARD | BRIDGE | PATH",
      "shape": "CYLINDER | CUBOID | LINE | RING | CURVE",
      "relative_position": {"x": int, "y": int, "z": int},
      "dimensions": {...},
      "notes": "optional"
    }
  ]
}

Rules:
- All positions are relative to anchor (0,0,0)
- Must obey all constraints above
- JSON only
- No explanation text
- No markdown code blocks
- Must be directly parseable
```

## 完整流程

```
玩家：
  框选 / 画轮廓 / 点锚点 / 设对称
        ↓
ToolState
        ↓
PromptAssembler（本章）
        ↓
LLM（稳定输出）
        ↓
Skeleton / Semantic / Geometry
        ↓
Tool Patch Filter（二次裁剪）
        ↓
Patch Preview（红 / 黄 / 蓝）
        ↓
Apply / Undo / Redo
```

## 系统优势

### ✅ 自动化空间约束转换

- Tool 状态自动转换为空间约束语言
- 不需要手动编写约束
- 确保约束的准确性和一致性

### ✅ 结构化 Prompt

- 不是聊天拼字符串
- 是空间 DSL（领域语言）生成器
- 机器可读、可解析

### ✅ 可控的 LLM 输出

- 明确的输出契约
- 严格的 JSON 格式
- 确保输出可解析

### ✅ 完整的闭环

- 从玩家操作到 AI 输出
- 从 AI 输出到建筑生成
- 从建筑生成到预览和应用

### ✅ 真正的"会思考的建造机器"

- 不是"让 AI 生成建筑"
- 而是"让 AI 成为受约束的建筑规划师"
- 所有系统真正拧成一台机器

## 与现有系统的关系

### 现有系统

1. **ToolPromptBuilder** - 工具状态 → PromptContext（已存在）
2. **SelectionContext / OutlineContext / ProtectedZoneContext** - 工具上下文（已存在）
3. **SymmetryContext / SemanticLabelContext** - 对称和语义上下文（已存在）

### 新系统

1. **System Role** - AI 身份定义（已改进）
2. **Spatial Constraints Block** - 空间约束块（已改进）
3. **Output Contract** - 输出契约（已改进）

**集成方式**：
- 新系统完全集成到现有 PromptAssembler
- 保持向后兼容
- 提供更结构化和自动化的约束转换

## 总结

✅ **完整的 Tool → PromptAssembler（自动化）系统已实现**：
- System Role（AI 身份） ✅
- Spatial Constraints Block（空间约束块） ✅
  - Anchor Block ✅
  - Selection Block ✅
  - Footprint Block ✅
  - No-Build Block ✅
  - Symmetry Block ✅
  - Semantic Block ✅
- User Intent（玩家原始描述） ✅
- Output Contract（输出契约） ✅

✅ **设计优势**：
- 自动化空间约束转换
- 结构化 Prompt（空间 DSL）
- 可控的 LLM 输出
- 完整的闭环
- 真正的"会思考的建造机器"

这是把前面所有系统**真正拧成一台"会思考的建造机器"**的关键一环！

