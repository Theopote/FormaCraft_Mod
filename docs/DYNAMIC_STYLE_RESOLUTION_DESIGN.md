# 动态风格解析系统设计

## 🎯 核心思想

**从硬编码预设 → AI 动态分析风格特征 → 智能材质选择**

### 当前问题

1. **硬编码限制**：
   - Palette 是预设的（MEDIEVAL_STONE, CYBERPUNK, ELVEN, HUI_STYLE）
   - 如果用户描述了一个不在预设中的风格，系统会回退到默认风格
   - 无法根据用户的具体描述动态调整材质

2. **缺乏灵活性**：
   - 用户说"红色砖墙、绿色屋顶" → 系统可能使用默认的中世纪风格
   - 用户说"现代玻璃幕墙" → 系统可能无法识别

### 理想方案

**AI 分析用户描述 → 提取风格特征 → 动态材质选择**

```
用户："在锚点位置生成15*17徽派别墅，高度12"
  ↓
AI 分析：
  - 风格：徽派建筑
  - 特征：
    * 白墙（white walls）
    * 黑瓦屋顶（black tile roof）
    * 木雕装饰（wood carvings）
    * 石板地面（stone paving）
  ↓
LLM 输出 LlmPlan，包含 style_attributes：
{
  "style_profile": "HUI_STYLE_VILLA",
  "style_attributes": {
    "wall_color": "white",
    "wall_material": "terracotta",
    "roof_color": "black",
    "roof_material": "tile",
    "accent_material": "dark_oak_wood",
    "floor_material": "stone_bricks",
    "decorative_elements": ["wood_carvings", "lattice_windows"]
  }
}
  ↓
DynamicPaletteResolver 根据 style_attributes 动态选择方块
  ↓
生成独特的建筑
```

## 📋 设计方案

### 1. 扩展 LlmPlan DTO

```java
public record LlmPlan(
    // ... 现有字段 ...
    
    /** 风格属性（AI 分析用户描述后提取） */
    @JsonProperty("style_attributes") StyleAttributes styleAttributes
) {
    /**
     * 风格属性（颜色、材质、装饰元素等）
     */
    public record StyleAttributes(
        /** 墙体颜色（white, gray, red, brown, etc.） */
        @JsonProperty("wall_color") String wallColor,
        
        /** 墙体材质（stone, brick, wood, concrete, terracotta, etc.） */
        @JsonProperty("wall_material") String wallMaterial,
        
        /** 屋顶颜色（black, red, gray, brown, etc.） */
        @JsonProperty("roof_color") String roofColor,
        
        /** 屋顶材质（tile, shingle, slate, metal, etc.） */
        @JsonProperty("roof_material") String roofMaterial,
        
        /** 装饰/强调材质（dark_oak, spruce, stone, etc.） */
        @JsonProperty("accent_material") String accentMaterial,
        
        /** 地面材质（stone_bricks, wood_planks, cobblestone, etc.） */
        @JsonProperty("floor_material") String floorMaterial,
        
        /** 装饰元素（wood_carvings, lattice_windows, columns, etc.） */
        @JsonProperty("decorative_elements") List<String> decorativeElements,
        
        /** 其他自定义属性 */
        @JsonProperty("custom_attributes") Map<String, String> customAttributes
    ) {}
}
```

### 2. 更新 PromptAssembler

在 System Prompt 中添加风格分析要求：

```java
private static String systemRole() {
    return """
You are Formacraft Core, a Minecraft architectural planning engine.

Your task is to convert structured spatial constraints into a BUILD BLUEPRINT or PATCH PLAN.
You DO NOT place blocks directly.
You ONLY output structured JSON following the schema below.

Core rules:
- Coordinate system: X/Z = horizontal plane, Y = vertical height.
- All positions are relative to the provided anchor (0,0,0).
- Respect all spatial constraints: path, outline, forbidden zones, symmetry, terrain strategy.
- Use semantic components (TOWER, WALL, ROOF, ENTRANCE, SIGNAGE, etc.), NOT blocks.

STYLE ANALYSIS (IMPORTANT):
- Analyze the user's description to extract style characteristics:
  * Colors (wall_color, roof_color, accent_color)
  * Materials (wall_material, roof_material, floor_material)
  * Decorative elements (carvings, lattice, columns, etc.)
- Output these in the "style_attributes" field
- If the user mentions specific colors or materials, use them
- If the user mentions a known style (e.g., "Chinese", "Medieval", "Modern"), infer appropriate attributes
- Be creative and specific: "red brick walls" → wall_color: "red", wall_material: "brick"

Output schema:
{
  "mode": "build | patch",
  "style_profile": "string (optional, for compatibility)",
  "style_attributes": {
    "wall_color": "string",
    "wall_material": "string",
    "roof_color": "string",
    "roof_material": "string",
    "accent_material": "string",
    "floor_material": "string",
    "decorative_elements": ["string"]
  },
  // ... 其他字段 ...
}
""";
}
```

### 3. 创建 DynamicPaletteResolver

```java
package com.formacraft.common.palette.dynamic;

import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.semantic.SemanticPart;
import java.util.*;

/**
 * DynamicPaletteResolver（动态调色板解析器）
 * 
 * 根据 AI 分析的 style_attributes 动态选择方块
 * 不再依赖硬编码的预设
 */
public final class DynamicPaletteResolver {
    
    private DynamicPaletteResolver() {}
    
    /**
     * 根据 style_attributes 解析方块 ID
     * 
     * @param part 语义部位（WALL, ROOF, FLOOR, etc.）
     * @param styleAttributes 风格属性（从 LlmPlan 获取）
     * @return 方块 ID（例如 "minecraft:white_terracotta"）
     */
    public static String resolve(SemanticPart part, LlmPlan.StyleAttributes styleAttributes) {
        if (styleAttributes == null) {
            return getDefaultBlock(part);
        }
        
        return switch (part) {
            case WALL, WALL_BASE, WALL_ACCENT -> resolveWall(styleAttributes);
            case ROOF, ROOF_SURFACE -> resolveRoof(styleAttributes);
            case FLOOR, COURTYARD_FLOOR -> resolveFloor(styleAttributes);
            case DECOR, WALL_ACCENT -> resolveAccent(styleAttributes);
            case WINDOW -> resolveWindow(styleAttributes);
            case DOORWAY -> "minecraft:air"; // 门洞保持空气
            default -> getDefaultBlock(part);
        };
    }
    
    /**
     * 解析墙体方块
     */
    private static String resolveWall(LlmPlan.StyleAttributes attrs) {
        String color = attrs.wallColor();
        String material = attrs.wallMaterial();
        
        // 组合颜色和材质
        return resolveBlock(color, material, "wall");
    }
    
    /**
     * 解析屋顶方块
     */
    private static String resolveRoof(LlmPlan.StyleAttributes attrs) {
        String color = attrs.roofColor();
        String material = attrs.roofMaterial();
        
        return resolveBlock(color, material, "roof");
    }
    
    /**
     * 解析地面方块
     */
    private static String resolveFloor(LlmPlan.StyleAttributes attrs) {
        String material = attrs.floorMaterial();
        
        if (material != null && !material.isBlank()) {
            return mapMaterialToBlock(material);
        }
        
        return "minecraft:stone_bricks"; // 默认
    }
    
    /**
     * 解析装饰/强调方块
     */
    private static String resolveAccent(LlmPlan.StyleAttributes attrs) {
        String accent = attrs.accentMaterial();
        
        if (accent != null && !accent.isBlank()) {
            return mapMaterialToBlock(accent);
        }
        
        return "minecraft:dark_oak_planks"; // 默认
    }
    
    /**
     * 解析窗户方块
     */
    private static String resolveWindow(LlmPlan.StyleAttributes attrs) {
        List<String> decorative = attrs.decorativeElements();
        
        if (decorative != null && decorative.contains("lattice_windows")) {
            return "minecraft:iron_bars"; // 格子窗
        }
        
        return "minecraft:glass"; // 默认玻璃
    }
    
    /**
     * 根据颜色和材质组合解析方块
     */
    private static String resolveBlock(String color, String material, String type) {
        if (color == null || color.isBlank()) {
            color = "default";
        }
        if (material == null || material.isBlank()) {
            material = "default";
        }
        
        // 颜色映射
        Map<String, String> colorMap = Map.of(
            "white", "white",
            "black", "black",
            "gray", "gray",
            "grey", "gray",
            "red", "red",
            "brown", "brown",
            "yellow", "yellow",
            "blue", "blue",
            "green", "green"
        );
        
        // 材质映射
        Map<String, String> materialMap = Map.of(
            "stone", "stone",
            "brick", "bricks",
            "wood", "planks",
            "concrete", "concrete",
            "terracotta", "terracotta",
            "tile", "terracotta",
            "shingle", "planks",
            "slate", "stone"
        );
        
        String normalizedColor = colorMap.getOrDefault(color.toLowerCase(), "default");
        String normalizedMaterial = materialMap.getOrDefault(material.toLowerCase(), "default");
        
        // 组合方块 ID
        if (!normalizedColor.equals("default") && !normalizedMaterial.equals("default")) {
            // 例如：white + terracotta → white_terracotta
            String blockId = normalizedColor + "_" + normalizedMaterial;
            return "minecraft:" + blockId;
        } else if (!normalizedMaterial.equals("default")) {
            // 只有材质，使用默认颜色
            return mapMaterialToBlock(material);
        }
        
        return getDefaultBlock(type.equals("wall") ? SemanticPart.WALL : SemanticPart.ROOF);
    }
    
    /**
     * 材质到方块的映射
     */
    private static String mapMaterialToBlock(String material) {
        if (material == null || material.isBlank()) {
            return "minecraft:stone";
        }
        
        String lower = material.toLowerCase();
        return switch (lower) {
            case "stone", "stone_bricks" -> "minecraft:stone_bricks";
            case "brick", "bricks" -> "minecraft:bricks";
            case "wood", "planks" -> "minecraft:oak_planks";
            case "dark_oak", "dark_oak_wood" -> "minecraft:dark_oak_planks";
            case "spruce", "spruce_wood" -> "minecraft:spruce_planks";
            case "concrete" -> "minecraft:gray_concrete";
            case "terracotta", "tile" -> "minecraft:terracotta";
            case "cobblestone" -> "minecraft:cobblestone";
            default -> "minecraft:stone";
        };
    }
    
    /**
     * 获取默认方块
     */
    private static String getDefaultBlock(SemanticPart part) {
        return switch (part) {
            case WALL, WALL_BASE -> "minecraft:stone_bricks";
            case ROOF, ROOF_SURFACE -> "minecraft:spruce_planks";
            case FLOOR -> "minecraft:stone_bricks";
            case DECOR -> "minecraft:stone_brick_slab";
            default -> "minecraft:stone";
        };
    }
}
```

### 4. 更新生成器使用 DynamicPaletteResolver

```java
// MassMainGenerator.java
private String getBlockForPart(SemanticPart part, SemanticComponent semantic) {
    // 1. 优先使用动态解析（如果 LlmPlan 有 style_attributes）
    if (semantic.styleProfile() != null) {
        // 需要从某个地方获取完整的 LlmPlan
        // 或者将 style_attributes 传递到 SemanticComponent
        // TODO: 传递 style_attributes
    }
    
    // 2. 回退到传统 Palette
    String styleProfile = getStyleProfile(semantic);
    Palette palette = PaletteLibrary.forStyle(styleProfile);
    String block = palette.pick(part);
    
    if (block != null && !block.isEmpty()) {
        return block;
    }
    
    // 3. 默认方块
    return getDefaultBlock(part);
}
```

### 5. 传递 style_attributes 到生成器

修改 `SemanticComponent` 以包含 `styleAttributes`：

```java
public record SemanticComponent(
    String componentType,
    Slot slot,
    Component source,
    String styleProfile,
    LlmPlan.StyleAttributes styleAttributes  // 新增
) {}
```

在 `ComponentPlanCompiler` 中传递：

```java
SemanticComponent semantic = new SemanticComponent(
    c.componentType(),
    slot,
    c,
    plan.styleProfile(),
    plan.styleAttributes()  // 传递 style_attributes
);
```

## 🎯 优势

1. **灵活性**：不再依赖硬编码预设，可以处理任何用户描述的风格
2. **独特性**：每个建筑都可以根据用户描述生成独特的材质组合
3. **可扩展性**：AI 可以分析新的风格特征，系统自动适应
4. **智能性**：AI 理解用户意图，提取关键特征

## 📝 实施步骤

1. ✅ 扩展 `LlmPlan` DTO，添加 `StyleAttributes`
2. ✅ 更新 `PromptAssembler`，要求 AI 分析风格特征
3. ✅ 创建 `DynamicPaletteResolver`
4. ✅ 更新生成器使用动态解析
5. ✅ 传递 `style_attributes` 到生成器

## 🔄 工作流程

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
  → "minecraft:white_terracotta"
  ↓
生成独特的建筑
```

