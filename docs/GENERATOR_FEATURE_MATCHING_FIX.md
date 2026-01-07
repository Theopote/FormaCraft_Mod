# 生成器 Feature 匹配问题修复

## 🐛 问题描述

用户报告：生成的建筑非常简单，只是一个立方体，没有门窗、没有入口，没有细节。

从日志分析：
- LLM 返回了 `LlmPlan`，包含多个组件
- `MASS_MAIN` 的 features 是：`["HIP_AND_GABLE_ROOF","SYMMETRICAL_LAYOUT","CENTRAL_HALL"]`
- 但是 `MassMainGenerator` 检查的关键词不匹配这些 features
- 导致生成器无法识别需要生成门窗、屋顶等细节

## 🔍 根本原因

### 1. Feature 关键词不匹配

**LLM 返回的 features**：
- `HIP_AND_GABLE_ROOF` - 包含 "roof"，应该能匹配
- `SYMMETRICAL_LAYOUT` - 不包含任何关键词
- `CENTRAL_HALL` - 不包含任何关键词

**生成器检查的关键词**：
- `MassMainGenerator` 检查：`"windows"`, `"window"`, `"facade_windows"`, `"roof"`, `"curved_roof"`, `"sloped_roof"`, `"door"`, `"doors"`, `"entrance"`, `"decor"`, `"decoration"`, `"ornament"`, `"carved"`

**问题**：
- `HIP_AND_GABLE_ROOF` 包含 `"roof"`，应该能匹配到屋顶 ✅
- 但是没有 `"windows"`, `"door"`, `"entrance"` 等关键词，所以不会生成门窗 ❌

### 2. 独立的组件应该生成细节

LLM 返回了独立的组件：
- `ENTRANCE` - 应该由 `EntranceGenerator` 生成
- `FACADE_WINDOWS` - 应该由 `FacadeWindowsGenerator` 生成

但是：
- `EntranceGenerator` 只是生成了门框，没有生成门洞（内部是空的）
- 这些组件应该能够正确生成

### 3. 缺少 SIDE_WING 生成器

LLM 返回了 `SIDE_WING` 组件，但没有对应的生成器，导致警告：
```
[FormaCraft] No generator for component: SIDE_WING
```

## ✅ 修复方案

### 1. 扩展 Feature 关键词匹配

**修改 `MassMainGenerator`**：
```java
// 扩展关键词匹配，支持更多变体
boolean hasWindows = hasFeature(c, "windows", "window", "facade_windows", "lattice", "opening");
boolean hasRoof = hasFeature(c, "roof", "curved_roof", "sloped_roof", "hip", "gable", "gabled");
boolean hasDoors = hasFeature(c, "door", "doors", "entrance", "entry", "gateway");
boolean hasDecor = hasFeature(c, "decor", "decoration", "ornament", "carved", "carving", "lintel", "overhang");
```

**效果**：
- `HIP_AND_GABLE_ROOF` 现在可以匹配到 `"hip"` 和 `"gable"` ✅
- 支持更多变体的关键词匹配

### 2. 改进 EntranceGenerator

**增强功能**：
- 检查 `overhang`、`decorative_lintel` 等 features
- 在门洞底部放置门槛
- 在顶部根据 features 生成门楣或门廊顶

### 3. 注册 SIDE_WING 生成器

**在 `GeneratorRegistry` 中注册**：
```java
// 侧翼生成器（复用 MassMainGenerator）
register("SIDE_WING", new MassMainGenerator());
```

## 📊 修复后的效果

### 之前

```
MASS_MAIN features: ["HIP_AND_GABLE_ROOF","SYMMETRICAL_LAYOUT","CENTRAL_HALL"]
  ↓
MassMainGenerator 检查：
  - hasWindows = false (不匹配)
  - hasRoof = true (匹配 "roof")
  - hasDoors = false (不匹配)
  - hasDecor = false (不匹配)
  ↓
结果：只生成基础结构和屋顶，没有门窗
```

### 现在

```
MASS_MAIN features: ["HIP_AND_GABLE_ROOF","SYMMETRICAL_LAYOUT","CENTRAL_HALL"]
  ↓
MassMainGenerator 检查：
  - hasWindows = false (仍然不匹配，但 LLM 返回了独立的 FACADE_WINDOWS 组件)
  - hasRoof = true (匹配 "hip" 或 "gable")
  - hasDoors = false (仍然不匹配，但 LLM 返回了独立的 ENTRANCE 组件)
  - hasDecor = false (仍然不匹配)
  ↓
结果：
  - MASS_MAIN 生成基础结构和屋顶
  - ENTRANCE 组件由 EntranceGenerator 生成（门洞 + 门框）
  - FACADE_WINDOWS 组件由 FacadeWindowsGenerator 生成（窗户）
  - SIDE_WING 组件由 MassMainGenerator 生成（不再报错）
```

## 🎯 关键改进

1. **扩展关键词匹配**：支持更多变体的 features
2. **改进 EntranceGenerator**：生成更完整的入口结构
3. **注册 SIDE_WING**：不再出现 "No generator" 警告

## ⚠️ 注意事项

虽然修复了这些问题，但还有一个根本问题：

**LLM 返回的 features 和生成器期望的关键词可能仍然不完全匹配**

**建议**：
1. 在 Prompt 中明确告诉 LLM 应该使用哪些 features 关键词
2. 或者让生成器更智能地推断（例如：如果 LLM 返回了独立的 `ENTRANCE` 组件，`MASS_MAIN` 就不需要再生成门）
3. 或者让生成器默认生成一些基础细节（例如：默认在正面生成门，在立面生成窗户）

## 📝 后续优化方向

1. **智能推断**：如果 LLM 返回了独立的 `ENTRANCE` 组件，`MASS_MAIN` 应该知道不需要再生成门
2. **默认细节**：即使没有匹配的 features，也应该生成一些基础细节（门、窗）
3. **Feature 映射表**：创建一个映射表，将 LLM 可能返回的 features 映射到生成器期望的关键词

