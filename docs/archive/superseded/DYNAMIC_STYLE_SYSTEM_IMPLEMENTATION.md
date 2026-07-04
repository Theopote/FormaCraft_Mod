# 动态风格系统实现总结

## 🎯 核心思想

**从硬编码预设 → AI 动态分析风格特征 → 智能材质选择**

### 问题

- **硬编码限制**：Palette 是预设的（MEDIEVAL_STONE, CYBERPUNK, ELVEN, HUI_STYLE）
- **缺乏灵活性**：如果用户描述了一个不在预设中的风格，系统会回退到默认风格
- **无法生成独特建筑**：每个风格都是固定的材质组合，无法根据用户具体描述调整

### 解决方案

**AI 分析用户描述 → 提取风格特征 → 动态材质选择**

## 📋 实现内容

### 1. StyleAttributes DTO

**位置**：`src/main/java/com/formacraft/common/llm/dto/StyleAttributes.java`

**功能**：存储 AI 分析出的风格特征

**字段**：
- `wallColor`: 墙体颜色（white, gray, red, brown, etc.）
- `wallMaterial`: 墙体材质（stone, brick, wood, concrete, terracotta, etc.）
- `roofColor`: 屋顶颜色（black, red, gray, brown, etc.）
- `roofMaterial`: 屋顶材质（tile, shingle, slate, metal, etc.）
- `accentMaterial`: 装饰/强调材质（dark_oak, spruce, stone, etc.）
- `floorMaterial`: 地面材质（stone_bricks, wood_planks, cobblestone, etc.）
- `decorativeElements`: 装饰元素列表（wood_carvings, lattice_windows, columns, etc.）

### 2. LlmPlan 扩展

**修改**：`src/main/java/com/formacraft/common/llm/dto/LlmPlan.java`

**新增字段**：
```java
@JsonProperty("style_attributes") StyleAttributes styleAttributes
```

### 3. PromptAssembler 更新

**修改**：`src/main/java/com/formacraft/ai/prompt/PromptAssembler.java`

**新增内容**：
- 在 System Prompt 中添加了 "STYLE ANALYSIS" 部分
- 要求 AI 分析用户描述，提取风格特征
- 在 Output schema 中添加了 `style_attributes` 字段

### 4. DynamicPaletteResolver

**位置**：`src/main/java/com/formacraft/common/palette/dynamic/DynamicPaletteResolver.java`

**功能**：根据 `style_attributes` 动态选择方块

**核心方法**：
- `resolve(SemanticPart part, StyleAttributes styleAttributes)`: 根据语义部位和风格属性解析方块 ID
- `resolveWall()`: 解析墙体方块（组合颜色和材质）
- `resolveRoof()`: 解析屋顶方块
- `resolveFloor()`: 解析地面方块
- `resolveAccent()`: 解析装饰方块
- `resolveWindow()`: 解析窗户方块

**解析逻辑**：
1. 如果有颜色和材质，组合使用（例如：white + terracotta → white_terracotta）
2. 如果只有材质，使用材质映射
3. 如果只有颜色，使用默认材质（例如：white → white_concrete）
4. 如果都没有，使用默认方块

### 5. SemanticComponent 扩展

**修改**：`src/main/java/com/formacraft/common/compiler/semantic/SemanticComponent.java`

**新增字段**：
```java
StyleAttributes styleAttributes
```

**兼容性**：提供了多个构造函数，保持向后兼容

### 6. ComponentPlanCompiler 更新

**修改**：`src/main/java/com/formacraft/common/compiler/ComponentPlanCompiler.java`

**变更**：传递 `styleAttributes` 到 `SemanticComponent`

### 7. MassMainGenerator 更新

**修改**：`src/main/java/com/formacraft/common/generation/component/impl/MassMainGenerator.java`

**新增方法**：
- `getBlockForPart()`: 优先使用动态解析，回退到传统 Palette

**优先级**：
1. 动态解析（如果 `styleAttributes` 存在）
2. 传统 Palette（如果动态解析失败）
3. 默认方块

## 🔄 完整工作流程

```
用户："在锚点位置生成15*17徽派别墅，高度12"
  ↓
PromptAssembler（要求 AI 分析风格特征）
  ↓
LLM 输出：
{
  "style_profile": "HUI_STYLE_VILLA",
  "style_attributes": {
    "wall_color": "white",
    "wall_material": "terracotta",
    "roof_color": "black",
    "roof_material": "tile",
    "accent_material": "dark_oak",
    "floor_material": "stone_bricks",
    "decorative_elements": ["wood_carvings", "lattice_windows"]
  }
}
  ↓
ComponentPlanCompiler 传递 style_attributes 到 SemanticComponent
  ↓
MassMainGenerator 使用 DynamicPaletteResolver
  ↓
DynamicPaletteResolver.resolve(WALL, styleAttributes)
  → "minecraft:white_terracotta" ✅
  ↓
生成独特的建筑
```

## 🎯 优势

### 1. 灵活性
- ✅ 不再依赖硬编码预设
- ✅ 可以处理任何用户描述的风格
- ✅ 支持任意颜色和材质组合

### 2. 独特性
- ✅ 每个建筑都可以根据用户描述生成独特的材质组合
- ✅ 用户说"红色砖墙、绿色屋顶" → 系统会使用 red_bricks 和 green_concrete
- ✅ 用户说"现代玻璃幕墙" → 系统会使用 glass 和 concrete

### 3. 可扩展性
- ✅ AI 可以分析新的风格特征，系统自动适应
- ✅ 不需要为每个新风格添加硬编码预设
- ✅ 支持自定义属性（custom_attributes）

### 4. 智能性
- ✅ AI 理解用户意图，提取关键特征
- ✅ 自动推断缺失的属性（例如：只有颜色，使用默认材质）
- ✅ 支持装饰元素的智能识别

## 📝 示例

### 示例 1：徽派别墅

**用户输入**："在锚点位置生成15*17徽派别墅，高度12"

**AI 分析**：
```json
{
  "style_attributes": {
    "wall_color": "white",
    "wall_material": "terracotta",
    "roof_color": "black",
    "roof_material": "tile",
    "accent_material": "dark_oak",
    "floor_material": "stone_bricks",
    "decorative_elements": ["wood_carvings", "lattice_windows"]
  }
}
```

**生成结果**：
- 墙体：`white_terracotta`
- 屋顶：`black_terracotta`
- 装饰：`dark_oak_fence`（木雕）
- 窗户：`iron_bars`（格子窗）

### 示例 2：红色砖房

**用户输入**："生成一个红色砖墙的房子"

**AI 分析**：
```json
{
  "style_attributes": {
    "wall_color": "red",
    "wall_material": "brick",
    "roof_color": "gray",
    "roof_material": "shingle"
  }
}
```

**生成结果**：
- 墙体：`red_bricks`（如果不存在，使用 `bricks`）
- 屋顶：`oak_planks`（shingle 映射到木板）

### 示例 3：现代玻璃建筑

**用户输入**："生成一个现代玻璃幕墙建筑"

**AI 分析**：
```json
{
  "style_attributes": {
    "wall_material": "glass",
    "wall_color": "transparent",
    "accent_material": "concrete"
  }
}
```

**生成结果**：
- 墙体：`glass`
- 装饰：`gray_concrete`

## 🔧 技术细节

### 颜色映射

支持的颜色：
- white, black, gray/grey, red, brown, yellow, blue, green
- orange, purple, pink, cyan, lime, magenta

### 材质映射

支持的材质：
- stone, brick, wood, concrete, terracotta, tile
- shingle, slate, metal
- dark_oak, spruce, birch, oak

### 组合规则

1. **颜色 + 材质**：`white` + `terracotta` → `white_terracotta`
2. **只有材质**：`brick` → `bricks`
3. **只有颜色**：`red` → `red_concrete`（默认使用 concrete）

### 回退机制

1. 优先使用动态解析（`DynamicPaletteResolver`）
2. 如果失败，回退到传统 Palette（`PaletteLibrary`）
3. 如果都失败，使用默认方块

## ✅ 总结

**核心价值**：
- ✅ **灵活性**：不再依赖硬编码预设
- ✅ **独特性**：每个建筑都可以根据用户描述生成独特的材质组合
- ✅ **可扩展性**：AI 可以分析新的风格特征，系统自动适应
- ✅ **智能性**：AI 理解用户意图，提取关键特征

**实现方式**：
- ✅ 扩展 `LlmPlan` DTO，添加 `StyleAttributes`
- ✅ 更新 `PromptAssembler`，要求 AI 分析风格特征
- ✅ 创建 `DynamicPaletteResolver`，根据 `style_attributes` 动态选择方块
- ✅ 更新生成器使用动态解析

**效果**：
- ✅ 用户描述任何风格，AI 都能分析并生成对应的材质
- ✅ 不再需要为每个新风格添加硬编码预设
- ✅ 生成的建筑更加独特和符合用户意图

