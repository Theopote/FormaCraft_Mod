# Component Variant System（构件变体系统）实现总结

## 📋 实现内容

根据建议，已成功实现 Component Variant System，让"一个构件定义"在 AI 使用时自动变成"很多合理但统一风格的变体"，而且用户完全不需要参与。

## 🎯 核心目标

**核心思想**：
- ✅ 你不是在做「复制粘贴构件」
- ✅ 你是在做 "建筑语义 → 形体族群（Morphological Family）"
- ✅ 同一个构件库，能生成无限变化但"看起来是同一个建筑师设计的东西"

**关键跃迁**：
| 之前 | 现在 |
|------|------|
| AI 说"用一个门" | AI 描述"我需要什么门" |
| 系统被动执行 | 系统拥有选择权 |
| 风格容易漂移 | 风格被结构约束 |
| 无法复用 | 构件成为"基因" |

## 🏗 Variant 系统在 Formacraft 中的位置

### 当前链路（已实现）

```
User Prompt
  ↓
PromptAssembler
  ↓
ComponentQuery  ←（语义）
  ↓
ComponentRanker
  ↓
ComponentDefinition（用户定义的"原型"）
```

### Variant 介入后

```
ComponentDefinition (Archetype)
        ↓
VariantGenerator（自动触发）
        ↓
ComponentVariant（运行时产物，不存盘）
        ↓
Placement / Patch / Generator
```

**关键点**：
- 🔑 AI 只负责"我要什么"
- 🔧 系统负责"怎么变好看"
- 👉 Variant 不应该被存盘
- 👉 Variant 是运行时产物

## ✅ 已实现的组件

### 1. ComponentVariantSpec（变体规格）

**位置**：`src/main/java/com/formacraft/common/component/variant/ComponentVariantSpec.java`

**核心功能**：
- 定义"怎么变"（受控变形）
- 从 ComponentArchetype.VariationSpec 自动创建

**关键字段**：
- `allowScaling` - 是否允许整体缩放
- `scalePolicy` - 轴向缩放策略（NONE / UNIFORM / XZ / XYZ）
- `scaleMin / scaleMax` - 尺寸扰动范围
- `allowSegmentRepeat` - 是否允许分段重复
- `repeatAxis` - 可重复轴（X / Y / Z）
- `allowTrim` - 是否允许裁剪
- `materialPolicy` - 材质替换规则

**关键方法**：
- `fromArchetype(VariationSpec)` - 从 Archetype 创建规格
- `createDefault()` - 创建默认规格（完全固定）

### 2. AxisScalePolicy（轴向缩放策略）

**位置**：`src/main/java/com/formacraft/common/component/variant/AxisScalePolicy.java`

**枚举值**：
- `NONE` - 完全固定（雕塑、脊兽）
- `UNIFORM` - 等比缩放
- `XZ` - X/Z 轴缩放（门 / 窗 / 阳台 - 最常用）
- `XYZ` - X/Y/Z 轴独立缩放（极少用）

### 3. MaterialVariantPolicy（材质替换策略）

**位置**：`src/main/java/com/formacraft/common/component/variant/MaterialVariantPolicy.java`

**枚举值**：
- `NONE` - 不允许材质替换
- `SAME_FAMILY` - 同族材质替换（stone_bricks → cracked / mossy）
- `STYLE_BASED` - 风格内替换（根据 StyleProfile 映射）

**核心思想**：
- 不应该让 AI 直接说"把石头换成深色石头"
- 而是使用 MaterialSemantic（WALL_PRIMARY, ACCENT, FRAME）
- 然后由 MaterialVariantPolicy 自动映射为同族材质

### 4. ComponentVariant（构件变体）

**位置**：`src/main/java/com/formacraft/common/component/variant/ComponentVariant.java`

**核心特性**：
- ✅ 运行时产物，不存盘
- ✅ 基于 ComponentDefinition（Archetype）生成
- ✅ 不改变 AttachmentType、FacingPolicy、Socket 类型
- ✅ 只影响尺寸、材质、局部形体

**关键字段**：
- `base` - 基础构件定义（Archetype）
- `scaleX/Y/Z` - 缩放比例
- `mirrored` - 是否镜像
- `mirrorAxis` - 镜像轴（X 或 Z）
- `repeatCount` - 分段重复次数
- `repeatAxis` - 重复轴
- `materialSemantic` - 材质语义映射
- `trimmedWidth/Height/Depth` - 裁剪后的尺寸

**关键方法**：
- `applyScale(float, AxisScalePolicy)` - 应用缩放
- `applyMirror(Axis)` - 应用镜像
- `applyRepeat(Axis, int)` - 应用分段重复
- `applyTrim(int, int, int)` - 应用裁剪
- `applyMaterialSemantic(String)` - 应用材质语义

### 5. VariantGenerator（变体生成器）

**位置**：`src/main/java/com/formacraft/common/component/variant/VariantGenerator.java`

**核心功能**：
- 自动生成变体（用户完全不需要参与）
- 受控变形（Variant ≠ Random）
- 保持"看起来是同一个建筑师设计的东西"

**生成流程**：
1. **尺寸变体** - 根据查询的几何要求调整缩放
2. **分段重复** - 栏杆 / 窗组（如果 `usageHint.frequency` 是 secondary/decorative）
3. **裁剪** - 适配 opening / edge（如果 `geometry.requiresOpening`）
4. **材质语义替换** - 根据风格推断材质语义
5. **镜像** - 如果允许且随机触发（30% 概率）

**触发时机**：
- Prompt → ComponentQuery
- Rank → Archetype
- **VariantGenerator 自动触发**
- 用户不需要知道它发生过

### 6. ComponentRetriever 集成

**位置**：`src/main/java/com/formacraft/common/component/query/ComponentRetriever.java`

**新增方法**：
- `retrieveBestWithVariant(ComponentQuery, Random)` - 检索最佳匹配并自动生成变体

**使用示例**：
```java
// AI 使用构件的标准流程
VariantResult result = ComponentRetriever.retrieveBestWithVariant(query, random);
ComponentDefinition base = result.base();
ComponentVariant variant = result.variant();
```

### 7. ComponentVariantAdapter（适配器）

**位置**：`src/main/java/com/formacraft/common/compiler/voxel/ComponentVariantAdapter.java`

**核心功能**：
- 将新的 ComponentVariant 适配为旧的 ComponentVariant
- 临时适配层，用于兼容现有的 ComponentVoxelizer 和 ComponentPlanCompiler
- 未来可以逐步迁移到新的 ComponentVariant 系统

## 📊 使用示例

### 示例 1：AI 自动生成变体

```java
// AI 输出 ComponentQuery
ComponentQuery query = parseFromJson(llmOutput.component_query);

// 系统自动检索和生成变体
VariantResult result = ComponentRetriever.retrieveBestWithVariant(query, random);

// 获取基础构件和变体
ComponentDefinition base = result.base();
ComponentVariant variant = result.variant();

// 编译为 Patch
List<BlockPatch> patches = ComponentPlanCompiler.compileWithNewVariant(
    base, variant, ctx, world, styleProfileId
);
```

### 示例 2：变体生成过程

```java
// 1. 基础构件（用户定义）
ComponentDefinition door = ComponentStorage.loadComponent("door_gothic_01");

// 2. 查询条件（AI 输出）
ComponentQuery query = new ComponentQuery();
query.semantic.role = "door";
query.geometry.requiresOpening = true;
query.geometry.openingWidth = 3;
query.geometry.openingHeight = 5;
query.style.styleProfile = "Medieval_Castle";

// 3. 自动生成变体（用户完全不需要参与）
ComponentVariant variant = VariantGenerator.generate(door, query, random);

// 4. 变体结果：
// - scaleX = 1.2（根据 openingWidth 调整）
// - scaleY = 1.5（根据 openingHeight 调整）
// - scaleZ = 1.0（锁定，因为门的厚度不能变）
// - materialSemantic = "FRAME"（根据风格推断）
// - mirrored = false（随机决定）
```

### 示例 3：分段重复（栏杆）

```java
// 查询条件
ComponentQuery query = new ComponentQuery();
query.semantic.role = "railing";
query.usageHint.frequency = "secondary"; // 触发分段重复

// 自动生成变体
ComponentVariant variant = VariantGenerator.generate(railing, query, random);

// 变体结果：
// - repeatCount = 5（随机生成 1-5 个重复单元）
// - repeatAxis = X（沿 X 轴重复）
```

## 🔄 完整数据流

```
[ AI 意图："我要一个哥特式石门" ]
        ↓
[ AI 输出 ComponentQuery ]
        ↓
ComponentRetriever.retrieveBestWithVariant()
        ↓
[ 检索最佳匹配构件 ]
        ↓
VariantGenerator.generate()
        ↓
[ 自动生成变体：缩放、重复、裁剪、材质替换 ]
        ↓
ComponentPlanCompiler.compileWithNewVariant()
        ↓
[ 编译为 BlockPatch ]
        ↓
[ 应用到世界 ]
```

## 🎯 Variant 能做什么

### ✅ 1. 构件主要供 AI 使用（完全成立）

- VariantGenerator 只会在 AI 建造流程中触发
- 用户不需要知道它发生过

### ✅ 2. 构件与世界 / 存档无关（非常正确）

- Variant 不落盘
- 不和世界绑定
- 不影响版本兼容

### ✅ 3. 非等轴缩放（关键高级点）

| 维度 | 是否允许 |
|------|----------|
| X 拉伸 | ✅ |
| Y 拉伸 | ⚠️（受约束） |
| Z 拉伸 | ✅ |
| 镜像 | ⚠️（受 FacingPolicy） |
| 非比例缩放 | ✅ |

### ✅ 4. 智能变体（这是灵魂）

- Variant ≠ Random
- Variant = 受控变形
- 保持"看起来是同一个建筑师设计的东西"

## 🎯 和 Socket / Placement 的关系

**Variant 不改变**：
- AttachmentType
- FacingPolicy
- Socket 类型

**Variant 只影响**：
- 尺寸
- 材质
- 局部形体

这正好为下一步 Socket System（构件如何"长"在建筑上）铺好地基。

## ✅ 完成度

| 组件 | 状态 | 说明 |
|------|------|------|
| ComponentVariantSpec | ✅ 完成 | 定义"怎么变"（受控变形） |
| AxisScalePolicy | ✅ 完成 | 轴向缩放策略（NONE / UNIFORM / XZ / XYZ） |
| MaterialVariantPolicy | ✅ 完成 | 材质替换策略（NONE / SAME_FAMILY / STYLE_BASED） |
| ComponentVariant | ✅ 完成 | 变体数据（运行时产物，不存盘） |
| VariantGenerator | ✅ 完成 | 核心逻辑（自动生成变体） |
| ComponentRetriever 集成 | ✅ 完成 | 检索最佳匹配并自动生成变体 |
| ComponentVariantAdapter | ✅ 完成 | 适配器（兼容现有代码） |

## 🎉 总结

已成功实现 Component Variant System：

- ✅ **ComponentVariantSpec** - 定义"怎么变"（受控变形）
- ✅ **AxisScalePolicy** - 轴向缩放策略
- ✅ **MaterialVariantPolicy** - 材质替换策略
- ✅ **ComponentVariant** - 变体数据（运行时产物，不存盘）
- ✅ **VariantGenerator** - 核心逻辑（自动生成变体）
- ✅ **ComponentRetriever 集成** - 检索最佳匹配并自动生成变体

**这一层完成后，系统立刻获得的能力**：
- ✅ 同一个构件库，能生成无限变化但"看起来是同一个建筑师设计的东西"
- ✅ AI 只负责"我要什么"，系统负责"怎么变好看"
- ✅ 用户完全不需要参与变体生成
- ✅ Variant 是运行时产物，不存盘，不影响版本兼容

**这是 Formacraft 出现"建筑语义 → 形体族群（Morphological Family）"能力的核心跃迁点。**

---

**实现时间**: 2026-01-14  
**版本**: v1.0  
**状态**: ✅ 核心功能完成，已集成到 ComponentRetriever
