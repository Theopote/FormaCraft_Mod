# 修复风格提取问题：从 Features 中提取材质信息

## 🐛 问题描述

用户报告生成的建筑完全没有风格，只是大小变化。从日志分析：

1. **LLM 返回了风格信息，但系统没有使用**：
   - `style_profile: "Zaha Hadid parametric architecture"`
   - Features: `["curvilinear_form","parametric_facade","white_concrete","glass_curtain_walls","dynamic_roof"]`

2. **问题根源**：
   - `DynamicPaletteResolver` 只从 `style_attributes` 读取材质信息
   - 但 LLM 可能没有返回 `style_attributes`，而是把材质信息放在了 `features` 中
   - 例如：`"white_concrete"`, `"glass_curtain_walls"` 在 features 中，但没有被提取使用

3. **缺失的组件类型**：
   - `MASS_WING`, `ENTRANCE_CANOPY`, `TERRACE_PLAZA`, `ROOF_STRUCTURE`, `BRIDGE_CONNECTOR` 没有注册生成器
   - 导致回退到 `HouseGenerator`，风格丢失

## ✅ 修复方案

### 1. 从 Features 中提取材质信息

**修改文件**：`MassMainGenerator.java`, `FacadeWindowsGenerator.java`, `EntranceGenerator.java`

**核心逻辑**：
```java
private String getBlockForPart(SemanticPart part, SemanticComponent semantic, Palette palette) {
    // 1. 优先使用动态解析（如果 LlmPlan 有 style_attributes）
    if (semantic.styleAttributes() != null) {
        String block = DynamicPaletteResolver.resolve(part, semantic.styleAttributes());
        if (block != null && !block.isEmpty()) {
            return block;
        }
    }
    
    // 2. 尝试从 features 中提取材质信息（新增）
    Component c = semantic.source();
    if (c != null && c.features() != null) {
        String block = extractBlockFromFeatures(part, c.features());
        if (block != null && !block.isEmpty()) {
            return block;
        }
    }
    
    // 3. 回退到传统 Palette
    // 4. 默认方块
}
```

**提取逻辑**：
```java
private String extractBlockFromFeatures(SemanticPart part, List<String> features) {
    for (String feature : features) {
        String lower = feature.toLowerCase().trim();
        
        // 白色混凝土
        if (lower.contains("white_concrete") || lower.contains("white concrete")) {
            if (part == SemanticPart.WALL || part == SemanticPart.WALL_BASE || part == SemanticPart.WALL_ACCENT) {
                return "minecraft:white_concrete";
            }
        }
        
        // 玻璃幕墙
        if (lower.contains("glass_curtain") || lower.contains("curtain_wall") || 
            lower.contains("glass_pane") || (lower.contains("glass") && lower.contains("curtain"))) {
            if (part == SemanticPart.WINDOW) {
                return "minecraft:glass_pane";
            }
        }
        
        // 黑色混凝土（屋顶）
        if (lower.contains("black_concrete") || lower.contains("black concrete")) {
            if (part == SemanticPart.ROOF || part == SemanticPart.ROOF_SURFACE) {
                return "minecraft:black_concrete";
            }
        }
        
        // 金属/钢铁（装饰）
        if (lower.contains("metal") || lower.contains("steel") || lower.contains("iron")) {
            if (part == SemanticPart.DECOR || part == SemanticPart.WALL_ACCENT) {
                return "minecraft:iron_block";
            }
        }
        
        // ... 更多材质映射
    }
    
    return null;
}
```

### 2. 注册缺失的组件类型

**修改文件**：`GeneratorRegistry.java`

**新增注册**：
```java
// 侧翼生成器（复用 MassMainGenerator）
register("SIDE_WING", new MassMainGenerator());
register("MASS_WING", new MassMainGenerator()); // 扎哈风格侧翼

// 入口和屋顶结构
register("ENTRANCE_CANOPY", new EntranceGenerator()); // 入口顶篷
register("ROOF_STRUCTURE", new RoofGenerator()); // 屋顶结构

// 平台和广场
register("TERRACE_PLAZA", new TerraceGenerator()); // 平台广场
register("PLAZA", new TerraceGenerator()); // 广场

// 连接器
register("BRIDGE_CONNECTOR", new PathGenerator()); // 桥连接器
```

### 3. 改进所有生成器的材质选择

**优先级顺序**：
1. **DynamicPaletteResolver**（如果 `style_attributes` 存在）
2. **从 features 提取**（如果 features 包含材质信息）
3. **传统 Palette**（根据 `styleProfile`）
4. **默认方块**

## 📊 修复后的效果

### 之前
```
LLM 返回：
  style_profile: "Zaha Hadid parametric architecture"
  features: ["white_concrete", "glass_curtain_walls", ...]
  ↓
MassMainGenerator:
  - styleAttributes = null（LLM 没有返回）
  - 回退到传统 Palette（MEDIEVAL_STONE）
  - 使用 stone_bricks（默认材质）
  ↓
结果：生成的建筑是石砖材质，完全没有扎哈风格
```

### 现在
```
LLM 返回：
  style_profile: "Zaha Hadid parametric architecture"
  features: ["white_concrete", "glass_curtain_walls", ...]
  ↓
MassMainGenerator:
  - styleAttributes = null（LLM 没有返回）
  - 从 features 提取：检测到 "white_concrete"
  - 使用 white_concrete（墙体）
  - 检测到 "glass_curtain_walls"
  - 使用 glass_pane（窗户）
  ↓
结果：生成的建筑是白色混凝土 + 玻璃幕墙，符合扎哈风格
```

## 🎯 支持的材质提取

### 墙体材质
- `white_concrete` → `minecraft:white_concrete`
- `gray_concrete` / `grey_concrete` → `minecraft:gray_concrete`
- `black_concrete` → `minecraft:black_concrete`
- `concrete`（默认）→ `minecraft:gray_concrete`

### 窗户材质
- `glass_curtain_walls` / `curtain_wall` → `minecraft:glass_pane`
- `glass_pane` → `minecraft:glass_pane`
- `glass` → `minecraft:glass`
- `lattice` / `iron_bars` → `minecraft:iron_bars`

### 屋顶材质
- `black_concrete` → `minecraft:black_concrete`
- `black_terracotta` → `minecraft:black_terracotta`

### 装饰材质
- `metal` / `steel` / `iron` → `minecraft:iron_block`

## 🔄 工作流程

```
用户："在锚点位置生成扎哈风格的建筑"
  ↓
LLM 分析：
  style_profile: "Zaha Hadid parametric architecture"
  features: ["white_concrete", "glass_curtain_walls", "dynamic_roof"]
  ↓
ComponentPlanCompiler:
  创建 SemanticComponent，包含 features
  ↓
MassMainGenerator.generate():
  1. 检查 styleAttributes → null
  2. 从 features 提取材质：
     - "white_concrete" → 墙体使用 white_concrete
     - "glass_curtain_walls" → 窗户使用 glass_pane
  3. 生成建筑（白色混凝土 + 玻璃幕墙）
  ↓
结果：符合扎哈风格的建筑
```

## 📝 测试建议

1. **测试扎哈风格**：
   - 输入："在锚点位置生成扎哈风格的建筑"
   - 预期：白色混凝土墙体 + 玻璃幕墙

2. **测试其他风格**：
   - 测试哥特式、徽派等，确保 features 中的材质信息被正确提取

3. **检查日志**：
   - 查看 `MassMainGenerator: extracted block from features` 日志
   - 确认材质提取是否成功

4. **验证组件类型**：
   - 确认所有组件类型都有对应的生成器
   - 不再出现 "no generator registered" 警告

## 🎯 关键改进

1. **双重材质来源**：既支持 `style_attributes`，也支持从 `features` 提取
2. **智能材质映射**：自动识别 features 中的材质关键词
3. **完整组件支持**：所有 LLM 返回的组件类型都有对应的生成器
4. **统一材质选择逻辑**：所有生成器使用相同的优先级顺序

