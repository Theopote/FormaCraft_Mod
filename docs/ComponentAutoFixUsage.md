# Component AutoFix 使用指南

## 📋 概述

Component AutoFix 是一个自动修复系统，用于修复构件定义中的明显错误，而不改变用户的设计意图。

## 🎯 设计原则

### ✅ AutoFix 可以做的

- **缺字段 → 补默认**
  - 缺少 `schema` → 设置为 `"formacraft.component.v1"`
  - 缺少 `category` → 设置为 `GENERIC`
  - 缺少 `tags` → 初始化为空列表

- **明显非法值 → 纠正为最接近合法值**
  - `size.w <= 0` → 修正为 `1`
  - 无效的 `anchor.facing` → 修正为 `"SOUTH"`
  - `anchor` 坐标超出 `size` 范围 → 调整到边界内

- **冗余/冲突字段 → 裁剪**
  - 移除空白 `tags`
  - 移除空白 `semanticTags`
  - 移除 `null` block 条目

- **根据 category / attachment / geometry 推导合理默认**
  - `DOOR/WINDOW` → `attachment = WALL_OPENING`
  - `COLUMN` → `attachment = NONE`
  - `BRACKET/ORNAMENT` → `attachment = WALL_SURFACE`

### ❌ AutoFix 不做的

- ❌ 改 `geometry.blocks` 内容
- ❌ 改 `socket` 结构形态
- ❌ 改用户显式填写的数值（除非非法）
- ❌ 改 `id` / `name` / `tags` 语义

## 🚀 使用方法

### 1. 基本使用

```java
import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.autofix.ComponentAutoFix;
import com.formacraft.common.component.autofix.AutoFixReport;

// 应用自动修复
ComponentDefinition def = ...; // 从 JSON 加载或创建
AutoFixReport report = ComponentAutoFix.apply(def);

// 检查修复内容
if (!report.empty()) {
    System.out.println("应用了 " + report.size() + " 个修复：");
    for (var fix : report.fixes()) {
        System.out.println("  " + fix);
    }
}
```

### 2. 保存时自动修复（已集成）

`ComponentStorage.saveComponent()` 已自动集成 AutoFix：

```java
// 保存时会自动修复
ComponentStorage.saveComponent(worldDir, def);

// 输出示例：
// [ComponentStorage] 保存构件 door_001 时应用了自动修复：
//   [FIX] schema: Defaulted to formacraft.component.v1
//   [FIX] category: Defaulted to GENERIC
//   [FIX] placementSpec.attachment: DOOR → corrected to WALL_OPENING
```

### 3. 加载时自动修复（已集成）

`ComponentStorage.loadComponent()` 已自动集成 AutoFix：

```java
// 加载时会自动修复
ComponentDefinition def = ComponentStorage.loadComponent(worldDir, "door_001");

// 输出示例：
// [ComponentStorage] 加载构件 door_001 时应用了自动修复：
//   [FIX] anchor.facing: Defaulted to SOUTH
```

### 4. 验证前修复（推荐流程）

```java
// 1. 加载构件
ComponentDefinition def = loadFromJson(json);

// 2. 自动修复
AutoFixReport fixReport = ComponentAutoFix.apply(def);

// 3. 验证修复后的构件
ValidationResult validationResult = ComponentValidator.validate(def);

// 4. 检查结果
if (!validationResult.ok()) {
    // 仍有错误（无法自动修复的）
    for (var issue : validationResult.errors()) {
        System.err.println("需要手动修复: " + issue);
    }
} else {
    // 修复成功，可以保存
    ComponentStorage.saveComponent(worldDir, def);
}
```

### 5. AI 输出修复

在 AI 生成构件后自动修复：

```java
// AI 生成的 JSON
String aiJson = "...";
ComponentDefinition def = JsonUtil.fromJson(aiJson, ComponentDefinition.class);

// 自动修复
AutoFixReport fixReport = ComponentAutoFix.apply(def);

// 验证
ValidationResult validationResult = ComponentValidator.validate(def);
if (validationResult.ok()) {
    // 修复成功，可以使用
    return def;
} else {
    // 仍有错误，返回给 AI 修复
    return "AutoFix 应用了 " + fixReport.size() + " 个修复，但仍有错误需要修复：\n" +
           validationResult.errors().stream()
               .map(ValidationResult.Issue::toString)
               .collect(Collectors.joining("\n"));
}
```

## 📊 修复规则

### Identity（身份信息）

| 字段 | 修复规则 | 示例 |
|------|---------|------|
| `schema` | 空白 → `"formacraft.component.v1"` | `[FIX] schema: Defaulted to formacraft.component.v1` |
| `category` | null → `GENERIC` | `[FIX] category: Defaulted to GENERIC` |
| `tags` | null → 空列表，移除空白标签 | `[FIX] tags: Removed blank tags` |
| `name` | 空白但 id 存在 → 使用 id | `[FIX] name: Defaulted to id: door_001` |
| `allowed_facing` | null/空 → `[NORTH, SOUTH, EAST, WEST]` | `[FIX] allowed_facing: Defaulted to [NORTH, SOUTH, EAST, WEST]` |

### Geometry（几何信息）

| 字段 | 修复规则 | 示例 |
|------|---------|------|
| `size` | null → 从 blocks 推导或 `1x1x1` | `[FIX] size: Derived from blocks: 5x3x1` |
| `size.w/h/d` | ≤ 0 → 修正为 `1` | `[FIX] size: Fixed invalid size to 1x1x1` |
| `anchor` | null → `(0,0,0)` facing `SOUTH` | `[FIX] anchor: Created default anchor at (0,0,0) facing SOUTH` |
| `anchor.facing` | 无效 → `SOUTH` | `[FIX] anchor.facing: Invalid facing 'INVALID' → corrected to SOUTH` |
| `anchor` 坐标 | 超出 size 范围 → 调整到边界内 | `[FIX] anchor: Adjusted anchor to (4,2,0) to fit within size` |
| `blocks` | 移除 null 条目 | `[FIX] blocks: Removed 2 null block entries` |

### Placement（放置规格）

| 字段 | 修复规则 | 示例 |
|------|---------|------|
| `placementSpec` | null → 创建默认 | `[FIX] placementSpec: Created default placementSpec` |
| `attachment` | null → 根据 category 推导 | `[FIX] placementSpec.attachment: Defaulted to WALL_OPENING` |
| `facingPolicy` | null → 根据 category 推导 | `[FIX] placementSpec.facingPolicy: Defaulted to DERIVED_FROM_HOST` |
| `spatialContext` | null → `ANY` | `[FIX] placementSpec.spatialContext: Defaulted to ANY` |
| `semanticTags` | 移除空白标签 | `[FIX] placementSpec.semanticTags: Removed blank semantic tags` |
| `aiHint` | 空白 → null | `[FIX] placementSpec.aiHint: Removed blank aiHint` |
| Category 不一致 | 根据 category 修正 | `[FIX] placementSpec.attachment: DOOR → corrected to WALL_OPENING` |

### Sockets（插槽）

| 字段 | 修复规则 | 示例 |
|------|---------|------|
| `sockets` | 移除 null 条目 | `[FIX] sockets: Removed 1 null socket entries` |

### Cross-fixes（跨字段修复）

| 场景 | 修复规则 | 示例 |
|------|---------|------|
| `anchor` 超出 `size` | 调整 anchor 到边界内 | `[FIX] anchor: Adjusted anchor to (4,2,0) to fit within size` |
| `placement_rules` | null → 创建默认 | `[FIX] placement_rules: Created default placement_rules` |

## 🎯 Category 默认值推导

### Attachment 默认值

| Category | 默认 Attachment | 说明 |
|---------|----------------|------|
| `DOOR`, `WINDOW` | `WALL_OPENING` | 门/窗需要墙体洞口 |
| `COLUMN` | `NONE` | 柱子自由放置 |
| `BRACKET`, `ORNAMENT` | `WALL_SURFACE` | 装饰物附着在墙面 |
| `ROOF_DETAIL` | `ROOF_EDGE` | 屋顶细节在屋檐边缘 |
| `ARCH` | `EDGE` | 拱券在边缘 |
| `STAIRS` | `FLOOR` | 楼梯在地面 |
| 其他 | `NONE` | 默认自由放置 |

### FacingPolicy 默认值

| Category | 默认 FacingPolicy | 说明 |
|---------|------------------|------|
| `DOOR`, `WINDOW` | `DERIVED_FROM_HOST` | 从附着对象推导 |
| `BRACKET`, `ORNAMENT` | `OUTWARD_NORMAL` | 朝向外法线 |
| `ARCH`, `STAIRS` | `ALONG_EDGE` | 沿边缘排列 |
| 其他 | `NONE` | 不需要方向 |

## 📝 修复示例

### 示例 1：缺少必需字段

**输入**：
```json
{
  "id": "wooden_door",
  "geometry": { "type": "BLOCK_VOLUME" }
}
```

**AutoFix 后**：
```json
{
  "schema": "formacraft.component.v1",
  "id": "wooden_door",
  "name": "wooden_door",
  "category": "GENERIC",
  "tags": [],
  "size": { "w": 1, "h": 1, "d": 1 },
  "anchor": { "dx": 0, "dy": 0, "dz": 0, "facing": "SOUTH" },
  "allowed_facing": ["NORTH", "SOUTH", "EAST", "WEST"],
  "placementSpec": {
    "attachment": "NONE",
    "facingPolicy": "NONE",
    "spatialContext": "ANY"
  }
}
```

**AutoFixReport**：
```
[FIX] schema: Defaulted to formacraft.component.v1
[FIX] category: Defaulted to GENERIC
[FIX] tags: Initialized empty tag list
[FIX] name: Defaulted to id: wooden_door
[FIX] size: Defaulted to 1x1x1 (no blocks to derive from)
[FIX] anchor: Created default anchor at (0,0,0) facing SOUTH
[FIX] allowed_facing: Defaulted to [NORTH, SOUTH, EAST, WEST]
[FIX] placementSpec: Created default placementSpec
[FIX] placementSpec.attachment: Defaulted to NONE
[FIX] placementSpec.facingPolicy: Defaulted to NONE
[FIX] placementSpec.spatialContext: Defaulted to ANY
[FIX] placement_rules: Created default placement_rules
```

### 示例 2：Category 不一致

**输入**：
```json
{
  "id": "door_001",
  "category": "DOOR",
  "placementSpec": {
    "attachment": "NONE"
  }
}
```

**AutoFix 后**：
```json
{
  "id": "door_001",
  "category": "DOOR",
  "placementSpec": {
    "attachment": "WALL_OPENING"
  }
}
```

**AutoFixReport**：
```
[FIX] placementSpec.attachment: DOOR → corrected to WALL_OPENING
```

### 示例 3：非法值修正

**输入**：
```json
{
  "id": "column_001",
  "size": { "w": -1, "h": 0, "d": 5 },
  "anchor": { "dx": 10, "dy": 10, "dz": 10, "facing": "INVALID" }
}
```

**AutoFix 后**：
```json
{
  "id": "column_001",
  "size": { "w": 1, "h": 1, "d": 5 },
  "anchor": { "dx": 0, "dy": 0, "dz": 0, "facing": "SOUTH" }
}
```

**AutoFixReport**：
```
[FIX] size: Fixed invalid size to 1x1x5
[FIX] anchor.facing: Invalid facing 'INVALID' → corrected to SOUTH
[FIX] anchor: Adjusted anchor to (0,0,0) to fit within size
```

## 🔧 扩展 AutoFix

### 添加新的修复规则

1. **在现有方法中添加**：

```java
// 在 fixIdentity() 中添加
if (def.id != null && def.id.length() > 64) {
    def.id = def.id.substring(0, 64);
    r.add("id", "Truncated id to 64 characters");
}
```

2. **创建新的修复方法**：

```java
// 在 ComponentAutoFix 中添加
private static void fixCustom(ComponentDefinition def, AutoFixReport r) {
    // 自定义修复逻辑
}

// 在 apply() 中调用
fixCustom(def, report);
```

3. **添加跨字段修复**：

```java
// 在 crossFix() 中添加
if (def.category == ComponentCategory.DOOR && def.sockets == null) {
    // 不自动创建 sockets（不改变用户意图）
    // 只记录建议
    r.add("sockets", "DOOR category usually has sockets (suggestion)");
}
```

## ⚠️ 注意事项

### 1. 修复会修改对象

AutoFix 会直接修改 `ComponentDefinition` 对象：

```java
ComponentDefinition def = ...;
AutoFixReport report = ComponentAutoFix.apply(def);
// def 已被修改！
```

如果需要保留原始对象，先克隆：

```java
// 需要实现 clone() 方法或使用序列化/反序列化
ComponentDefinition original = ...;
ComponentDefinition copy = JsonUtil.fromJson(JsonUtil.toJson(original), ComponentDefinition.class);
AutoFixReport report = ComponentAutoFix.apply(copy);
```

### 2. 修复顺序

修复按以下顺序执行：
1. Identity
2. Geometry
3. Placement
4. Sockets
5. Cross-fixes

后续修复可能依赖前面的修复结果。

### 3. 验证后修复

建议在验证前应用 AutoFix：

```java
// ✅ 推荐流程
AutoFixReport fixReport = ComponentAutoFix.apply(def);
ValidationResult validationResult = ComponentValidator.validate(def);

// ❌ 不推荐：先验证再修复
ValidationResult validationResult = ComponentValidator.validate(def);
AutoFixReport fixReport = ComponentAutoFix.apply(def); // 可能修复了验证器报告的问题
```

## ✅ 完成度

| 功能 | 状态 |
|------|------|
| AutoFixReport 模型 | ✅ 完成 |
| ComponentAutoFix 主修复器 | ✅ 完成 |
| Identity 修复 | ✅ 完成 |
| Geometry 修复 | ✅ 完成 |
| Placement 修复 | ✅ 完成 |
| Sockets 修复 | ✅ 完成 |
| Cross-fixes | ✅ 完成 |
| 保存时集成 | ✅ 完成 |
| 加载时集成 | ✅ 完成 |
| 文档 | ✅ 完成 |

---

**创建时间**: 2026-01-14  
**版本**: v1.0  
**状态**: ✅ 核心功能完成，已集成到保存/加载流程
