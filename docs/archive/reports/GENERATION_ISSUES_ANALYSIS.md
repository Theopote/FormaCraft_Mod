# 建筑生成问题分析与修复方案

## 🔍 问题分析

根据用户反馈和日志分析，发现以下问题：

### 1. 风格映射缺失
**问题**：LLM 输出了 `HUI_STYLE_VILLA`（徽派别墅），但 `PaletteLibrary` 没有对应的风格
- `PaletteLibrary.forStyle()` 只支持：`MEDIEVAL_CLASSIC`, `CYBERPUNK`, `ELVEN`
- 没有 `HUI_STYLE_VILLA` 或 `CHINESE_VILLA` 的映射
- 结果：回退到默认的 `MEDIEVAL_STONE`，导致风格不对

### 2. 内部空间被填充
**问题**：`MassMainGenerator` 没有处理内部空间，会填充整个体积
- `TowerGenerator` 有 `hasInterior` 检查并留空内部
- `MassMainGenerator` 没有类似逻辑
- 结果：建筑内部是实心的，不是空间

### 3. 门窗生成失败
**问题**：虽然 LLM 输出了 `ENTRANCE` 和 `FACADE_WINDOWS` 组件，但可能：
- `MassMainGenerator` 的 features 匹配失败（LLM 输出 "WHITE_WALLS", "BLACK_TILE_ROOF"，但生成器检查 "windows", "door"）
- 或者门窗位置计算有问题
- 或者独立组件被 `MASS_MAIN` 覆盖了

### 4. 风格获取硬编码
**问题**：`getStyleProfile()` 方法硬编码返回 "MEDIEVAL_CLASSIC"
- 没有从 `LlmPlan.styleProfile` 获取
- 所有生成器都使用默认风格

### 5. 门和地板位置不平齐
**问题**：门的位置计算可能有问题
- `isDoorPosition()` 可能计算错误
- 或者门的高度设置不对

## ✅ 修复方案

### 修复 1：添加徽派风格 Palette

```java
// PaletteLibrary.java
static {
    // ... 现有风格 ...
    
    // ========== 徽派风格 ==========
    HUI_STYLE.add(SemanticPart.WALL, "minecraft:white_terracotta", 50);
    HUI_STYLE.add(SemanticPart.WALL, "minecraft:white_concrete", 30);
    HUI_STYLE.add(SemanticPart.WALL, "minecraft:quartz_block", 20);
    
    HUI_STYLE.add(SemanticPart.WALL_BASE, "minecraft:stone_bricks", 60);
    HUI_STYLE.add(SemanticPart.WALL_BASE, "minecraft:polished_blackstone_bricks", 40);
    
    HUI_STYLE.add(SemanticPart.WALL_ACCENT, "minecraft:dark_oak_planks", 50);
    HUI_STYLE.add(SemanticPart.WALL_ACCENT, "minecraft:spruce_planks", 30);
    HUI_STYLE.add(SemanticPart.WALL_ACCENT, "minecraft:oak_planks", 20);
    
    HUI_STYLE.add(SemanticPart.ROOF, "minecraft:black_terracotta", 50);
    HUI_STYLE.add(SemanticPart.ROOF, "minecraft:black_concrete", 30);
    HUI_STYLE.add(SemanticPart.ROOF, "minecraft:gray_terracotta", 20);
    
    HUI_STYLE.add(SemanticPart.FLOOR, "minecraft:stone_bricks", 60);
    HUI_STYLE.add(SemanticPart.FLOOR, "minecraft:polished_andesite", 40);
    
    HUI_STYLE.add(SemanticPart.DECOR, "minecraft:dark_oak_fence", 50);
    HUI_STYLE.add(SemanticPart.DECOR, "minecraft:spruce_fence", 30);
    HUI_STYLE.add(SemanticPart.DECOR, "minecraft:oak_fence", 20);
}

public static Palette forStyle(String styleProfile) {
    // ...
    return switch (s) {
        // ...
        case "HUI_STYLE_VILLA", "HUI_VILLA", "CHINESE_VILLA", "CHINESE_HUI" -> HUI_STYLE;
        default -> MEDIEVAL_STONE;
    };
}
```

### 修复 2：MassMainGenerator 添加内部空间处理

```java
// MassMainGenerator.java
boolean hasInterior = hasFeature(c, "interior", "rooms", "hollow", "courtyard", "central_courtyard");

// 在生成循环中
for (int y = 0; y < height; y++) {
    for (int x = 0; x < width; x++) {
        for (int z = 0; z < depth; z++) {
            // 检查是否是内部空间（留空）
            if (hasInterior && isInteriorSpace(x, z, width, depth)) {
                // 内部空间不放置方块（保持空气）
                continue;
            }
            // ... 其他逻辑 ...
        }
    }
}

private boolean isInteriorSpace(int x, int z, int width, int depth) {
    // 留出边缘（墙体），内部留空
    int wallThickness = 1;
    return x >= wallThickness && x < width - wallThickness &&
           z >= wallThickness && z < depth - wallThickness;
}
```

### 修复 3：改进 features 匹配

```java
// MassMainGenerator.java
// 扩展关键词匹配，支持更多变体
boolean hasWindows = hasFeature(c, "windows", "window", "facade_windows", "lattice", 
                                "opening", "wooden_frames", "lattice_patterns");
boolean hasDoors = hasFeature(c, "door", "doors", "entrance", "entry", "gateway",
                              "double_doors", "stone_steps", "overhang_roof");
```

### 修复 4：从 LlmPlan 获取风格

```java
// MassMainGenerator.java
private String getStyleProfile(SemanticComponent semantic) {
    // 1. 优先从 LlmPlan 获取
    if (semantic.slot() != null) {
        // 可以从 slot 或 plan 获取 styleProfile
        // 需要传递 LlmPlan 到 SemanticComponent
    }
    
    // 2. 从 Component 的 features 推断
    Component c = semantic.source();
    if (c != null && c.features() != null) {
        for (String feature : c.features()) {
            if (feature != null) {
                String lower = feature.toLowerCase();
                if (lower.contains("hui") || lower.contains("chinese")) {
                    return "HUI_STYLE_VILLA";
                }
            }
        }
    }
    
    // 3. 默认返回
    return "MEDIEVAL_CLASSIC";
}
```

### 修复 5：改进门位置计算

```java
// MassMainGenerator.java
private boolean isDoorPosition(int x, int z, int width, int depth, int y) {
    // 门应该在正面（z=0 或 z=depth-1）的中心位置
    // 高度应该在 0-3 之间（门的高度）
    if (y < 0 || y >= 4) return false;
    
    // 检查是否在正面
    boolean isFrontFace = (z == 0 || z == depth - 1);
    if (!isFrontFace) return false;
    
    // 检查是否在中心（允许 ±1 的误差）
    int centerX = width / 2;
    return Math.abs(x - centerX) <= 1;
}
```

## 🎯 优先级

1. **高优先级**：修复风格映射（添加徽派风格）
2. **高优先级**：修复内部空间处理
3. **中优先级**：改进 features 匹配
4. **中优先级**：从 LlmPlan 获取风格
5. **低优先级**：改进门位置计算

## 📝 实施步骤

1. 添加徽派风格 Palette
2. 修复 MassMainGenerator 的内部空间处理
3. 改进 features 匹配逻辑
4. 修复风格获取逻辑
5. 测试验证

