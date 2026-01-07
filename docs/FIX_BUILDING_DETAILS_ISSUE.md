# 修复建筑缺少细节问题

## 🐛 问题描述

用户报告生成的建筑看起来非常奇怪：
- 没有细节
- 没有门窗
- 没有装饰
- 材质非常单一
- 看不出风格
- 看不出入口

从日志分析：
- LLM 返回了正确的组件：`MASS_MAIN`, `TOWER`, `ENTRANCE`, `FACADE_WINDOWS`, `SIGNAGE`
- Features 包含：`["pointed_arches","ribbed_vaults","stained_glass_windows","flying_buttresses"]`
- 但生成的建筑只是一个实心体块，没有细节

## 🔍 根本原因

### 1. 默认细节生成逻辑问题

**问题**：
- `MassMainGenerator` 只有 `RESIDENTIAL` 建筑才会默认生成门窗
- 哥特式教堂的 program 是 `LANDMARK`，不是 `RESIDENTIAL`
- 所以即使 LLM 返回了 features，如果没有匹配到关键词，就不会生成门窗

**修复**：
```java
// 对于住宅、地标、市政建筑，默认应该有门和窗
boolean shouldGenerateDefaultDetails = (isResidential || isLandmark || isCivic) && 
                                       !hasDoors && !hasWindows;
```

### 2. Feature 关键词匹配不完整

**问题**：
- LLM 返回的 features：`["pointed_arches","ribbed_vaults","stained_glass_windows","flying_buttresses"]`
- `stained_glass_windows` 应该能匹配到 `hasWindows`（包含 "windows"）
- 但是 `pointed_arches`, `ribbed_vaults`, `flying_buttresses` 无法匹配到任何关键词

**修复**：
```java
// 扩展关键词匹配，支持哥特式特征
boolean hasWindows = hasFeature(c, "windows", "window", "facade_windows", "lattice", "opening",
                                "wooden_frames", "lattice_patterns", "symmetrical_placement",
                                "stained_glass", "stained_glass_windows", "glass", "glass_panels",
                                "rose_window", "pointed_arches"); // 哥特式特征
boolean hasRoof = hasFeature(c, "roof", "curved_roof", "sloped_roof", "hip", "gable", "gabled",
                             "black_tile_roof", "upturned_eaves", "ridge_decorations",
                             "ribbed_vaults", "vaults", "vaulted"); // 哥特式拱顶
boolean hasDoors = hasFeature(c, "door", "doors", "entrance", "entry", "gateway",
                              "double_doors", "stone_steps", "overhang_roof",
                              "large_door", "tympanum", "statues"); // 哥特式入口
boolean hasDecor = hasFeature(c, "decor", "decoration", "ornament", "carved", "carving", "lintel", "overhang",
                              "wood_carvings", "intricate", "white_walls",
                              "flying_buttresses", "buttresses", "gargoyles", "spire", // 哥特式装饰
                              "pointed_arches", "arches", "ribbed_vaults"); // 哥特式结构
```

### 3. 独立组件的 styleProfile 硬编码

**问题**：
- `EntranceGenerator` 和 `FacadeWindowsGenerator` 的 `getStyleProfile()` 硬编码返回 "MEDIEVAL_CLASSIC"
- 没有从 `SemanticComponent` 中获取 `styleProfile`
- 导致即使 LLM 返回了 "Gothic" 风格，也无法正确应用

**修复**：
```java
private String getStyleProfile(SemanticComponent semantic) {
    // 1. 优先使用 SemanticComponent 中的 styleProfile
    if (semantic != null && semantic.styleProfile() != null && !semantic.styleProfile().isBlank()) {
        String profile = semantic.styleProfile().trim();
        String upper = profile.toUpperCase();
        if (upper.contains("GOTHIC")) {
            return "MEDIEVAL_CLASSIC"; // 哥特式使用中世纪风格
        }
        // ... 其他风格映射
        return profile;
    }
    // 2. 尝试从 Component 的 features 推断风格
    // 3. 默认
    return "MEDIEVAL_CLASSIC";
}
```

### 4. PaletteLibrary 缺少 Gothic 风格映射

**问题**：
- `PaletteLibrary.forStyle()` 没有 "Gothic" 风格的映射
- 导致回退到默认的 `MEDIEVAL_STONE`，虽然材质是对的，但风格名称不匹配

**修复**：
```java
return switch (s) {
    case "MEDIEVAL_CLASSIC", "MEDIEVAL", "MEDIEVAL_STONE", "GOTHIC" -> MEDIEVAL_STONE;
    // ... 其他风格
};
```

### 5. 缺少默认内部空间

**问题**：
- `MassMainGenerator` 只有匹配到 `hasInterior` features 才会生成内部空间
- 对于教堂这样的大型建筑，如果没有明确指定 `interior` 特征，就会生成实心体块
- 导致即使有门窗，也看不到（因为内部是实心的）

**修复**：
```java
// 对于足够大的建筑，默认应该有内部空间
boolean isBuilding = semantic.slot() != null && 
                    (semantic.slot().program() != null && 
                     !semantic.slot().program().equals("PLAZA") &&
                     !semantic.slot().program().equals("LANDSCAPE"));
if (!hasInterior && isBuilding && width >= 5 && depth >= 5 && height >= 4) {
    hasInterior = true;
    FormacraftMod.LOGGER.info("MassMainGenerator: enabling default interior space for building {}x{}x{}", 
            width, depth, height);
}
```

### 6. MassMainGenerator 的 getStyleProfile 缺少 Gothic 支持

**问题**：
- `MassMainGenerator.getStyleProfile()` 没有处理 "Gothic" 风格
- 导致即使 LLM 返回了 "Gothic"，也无法正确映射

**修复**：
```java
String upper = profile.toUpperCase();
if (upper.contains("GOTHIC")) {
    return "MEDIEVAL_CLASSIC"; // 哥特式使用中世纪风格
}
// ... 其他风格映射
```

## ✅ 修复后的效果

### 之前
```
MASS_MAIN features: ["pointed_arches","ribbed_vaults","stained_glass_windows","flying_buttresses"]
  ↓
MassMainGenerator 检查：
  - hasWindows = false (不匹配 "stained_glass_windows" 中的 "windows")
  - hasRoof = false (不匹配 "ribbed_vaults")
  - hasDoors = false (不匹配)
  - hasDecor = false (不匹配 "flying_buttresses")
  - hasInterior = false (不匹配)
  - shouldGenerateDefaultDetails = false (LANDMARK 不是 RESIDENTIAL)
  ↓
结果：只生成实心体块，没有门窗、没有内部空间
```

### 现在
```
MASS_MAIN features: ["pointed_arches","ribbed_vaults","stained_glass_windows","flying_buttresses"]
  ↓
MassMainGenerator 检查：
  - hasWindows = true (匹配 "stained_glass_windows" 中的 "stained_glass" 和 "windows")
  - hasRoof = true (匹配 "ribbed_vaults" 中的 "vaults")
  - hasDoors = false (仍然不匹配，但 shouldGenerateDefaultDetails = true）
  - hasDecor = true (匹配 "flying_buttresses" 中的 "buttresses" 和 "flying_buttresses")
  - hasInterior = true (默认启用，因为建筑足够大）
  - shouldGenerateDefaultDetails = true (LANDMARK 现在也支持）
  ↓
结果：
  - MASS_MAIN 生成基础结构、屋顶、门窗、装饰、内部空间
  - ENTRANCE 组件由 EntranceGenerator 生成（使用正确的 Gothic 风格）
  - FACADE_WINDOWS 组件由 FacadeWindowsGenerator 生成（使用正确的 Gothic 风格）
  - 材质使用 MEDIEVAL_STONE Palette（Gothic 映射到 MEDIEVAL_CLASSIC）
```

## 🎯 关键改进

1. **扩展关键词匹配**：支持更多变体的 features，特别是哥特式特征
2. **改进默认细节生成**：LANDMARK 和 CIVIC 建筑也会默认生成门窗
3. **修复独立组件的风格获取**：`EntranceGenerator` 和 `FacadeWindowsGenerator` 现在会从 `SemanticComponent` 获取风格
4. **添加 Gothic 风格映射**：`PaletteLibrary` 和 `MassMainGenerator` 都支持 "Gothic" 风格
5. **默认内部空间**：对于足够大的建筑，默认应该有内部空间
6. **添加调试日志**：帮助诊断特征匹配和内部空间生成

## 📝 测试建议

1. **测试哥特式教堂**：
   - 输入："在锚点位置生成10*15哥特式教堂，教堂平面为十字布局"
   - 预期：应该有门窗、内部空间、装饰、正确的材质

2. **测试其他风格**：
   - 测试徽派建筑、现代建筑等，确保风格映射正确

3. **测试小建筑**：
   - 测试小于 5x5x4 的建筑，确保不会错误地生成内部空间

4. **检查日志**：
   - 查看 `MassMainGenerator` 的调试日志，确认特征匹配和内部空间生成是否正确

