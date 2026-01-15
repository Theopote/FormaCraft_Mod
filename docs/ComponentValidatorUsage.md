# Component Validator 使用指南

## 📋 概述

Component Validator 是一个结构化的验证系统，用于：

✅ **组件 JSON 导入/保存时校验**  
✅ **AI 输出引用组件时二次校验**（防止脏数据）  
✅ **ComponentPanel 显示"哪里不对"**  
✅ **未来做自动修复（Auto-fix）打基础**

## 🏗️ 架构

### 核心类

1. **`ValidationResult`** - 验证结果容器
   - `Issue` - 单个问题（ERROR/WARN + path + message）
   - `ok()` - 检查是否有错误
   - `errors()` / `warnings()` - 获取分类问题

2. **`ComponentValidator`** - 主验证器
   - `validate(ComponentDefinition)` - 验证入口
   - 按模块组织：Identity, Geometry, Placement, Sockets, Cross-checks

3. **子模块验证器**
   - `GeometryValidator` - size, anchor, blocks
   - `PlacementValidator` - ComponentPlacementSpec
   - `SocketValidator` - ComponentSocket 列表

4. **`EnumUtil`** - 工具类
   - 安全枚举解析
   - 字符串工具

## 🚀 使用方法

### 1. 基本使用

```java
import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.validate.ComponentValidator;
import com.formacraft.common.component.validate.ValidationResult;

// 验证构件
ComponentDefinition def = ...; // 从 JSON 加载或创建
ValidationResult result = ComponentValidator.validate(def);

// 检查是否有错误
if (!result.ok()) {
    // 处理错误
    for (var issue : result.errors()) {
        System.err.println("错误: " + issue);
    }
}

// 显示警告
for (var issue : result.warnings()) {
    System.out.println("警告: " + issue);
}
```

### 2. 保存时验证（已集成）

`ComponentStorage.saveComponent()` 已自动集成验证：

```java
// 保存时会自动验证
ComponentStorage.saveComponent(worldDir, def);

// 验证结果会输出到控制台：
// [ComponentStorage] 保存构件 door_001 时发现验证错误：
//   ERROR @ id: Missing id
// [ComponentStorage] 保存构件 door_001 时发现验证警告：
//   WARN @ name: Missing display name (name)
```

### 3. 加载时验证（已集成）

`ComponentStorage.loadComponent()` 已自动集成验证：

```java
// 加载时会自动验证
ComponentDefinition def = ComponentStorage.loadComponent(worldDir, "door_001");

// 验证结果会输出到控制台
```

### 4. AI 输出验证

在 AI 生成构件后验证：

```java
// AI 生成的 JSON
String aiJson = "...";
ComponentDefinition def = JsonUtil.fromJson(aiJson, ComponentDefinition.class);

// 验证
ValidationResult result = ComponentValidator.validate(def);
if (!result.ok()) {
    // 返回错误给 AI，要求修复
    String errorMsg = result.errors().stream()
        .map(ValidationResult.Issue::toString)
        .collect(Collectors.joining("\n"));
    throw new IllegalArgumentException("AI 生成的构件有错误:\n" + errorMsg);
}
```

### 5. UI 面板显示问题

在 ComponentPanel 中显示验证结果：

```java
ValidationResult result = ComponentValidator.validate(component);

// 显示错误（红色）
for (var issue : result.errors()) {
    // 在 UI 中显示红色错误标记
    drawErrorIcon(issue.path, issue.message);
}

// 显示警告（黄色）
for (var issue : result.warnings()) {
    // 在 UI 中显示黄色警告标记
    drawWarningIcon(issue.path, issue.message);
}

// 鼠标悬停显示详细信息
tooltip = issue.path + ": " + issue.message;
```

## 📊 验证规则

### Identity（身份信息）

| 字段 | 规则 | 严重性 |
|------|------|--------|
| `schema` | 必须是 "formacraft.component.v1" | WARN（不匹配时） |
| `id` | 必填，格式：`[a-z0-9_\-\.]+` | ERROR |
| `name` | 建议填写 | WARN |
| `category` | 建议填写 | WARN |
| `tags` | 数量 ≤ 64，无重复，无空白 | WARN |
| `allowed_facing` | 值必须是 NORTH/SOUTH/EAST/WEST/UP/DOWN | ERROR |

### Geometry（几何信息）

| 字段 | 规则 | 严重性 |
|------|------|--------|
| `size` | 必填，w/h/d > 0，≤ 256 | ERROR/WARN |
| `anchor` | 必填，坐标在 size 范围内 | ERROR/WARN |
| `anchor.facing` | 必须是有效的方向 | ERROR |
| `blocks` | 必填，非空，数量 ≤ 10000 | ERROR/WARN |
| `blocks[].block` | 必填，格式：`namespace:block_id[properties]` | ERROR/WARN |
| `blocks[].pos` | 坐标在 size 范围内 | WARN |

### Placement（放置规格）

| 字段 | 规则 | 严重性 |
|------|------|--------|
| `placementSpec.attachment` | 枚举值（类型安全） | WARN（null 时） |
| `placementSpec.facingPolicy` | 枚举值 | WARN（null 时） |
| `placementSpec.semanticTags` | 数量 ≤ 32，无空白 | WARN |
| `placementSpec.aiHint` | 长度 ≤ 200 | WARN |
| Category 一致性 | 根据 category 检查 attachment 合理性 | WARN |

### Sockets（插槽）

| 字段 | 规则 | 严重性 |
|------|------|--------|
| `sockets[].id` | 必填，格式：`[a-z0-9_\-\.]+`，唯一 | ERROR |
| `sockets[].role` | 枚举值（PROVIDER/CONSUMER） | ERROR |
| `sockets[].shape` | 枚举值 | ERROR |
| `sockets[].context` | 枚举值 | WARN |
| `sockets[].facingPolicy` | 枚举值 | WARN |
| `sockets[].size` | min/max 数组，min ≤ max | ERROR |
| `sockets[].tags` | 数量 ≤ 16，无空白 | WARN |

### Cross-checks（跨字段检查）

- Socket ID 唯一性
- Category 与 Placement 的一致性
- Blocks 坐标与 Size 的一致性

## 🎯 验证示例

### 示例 1：缺少必需字段

```java
ComponentDefinition def = new ComponentDefinition();
// def.id = null; // 未设置

ValidationResult result = ComponentValidator.validate(def);
// result.errors() 包含：
// ERROR @ id: Missing id
// ERROR @ size: Missing size
// ERROR @ anchor: Missing anchor
// ERROR @ blocks: Missing or empty blocks list
```

### 示例 2：格式错误

```java
ComponentDefinition def = new ComponentDefinition();
def.id = "Invalid ID!"; // 包含大写和特殊字符

ValidationResult result = ComponentValidator.validate(def);
// result.errors() 包含：
// ERROR @ id: Invalid id format. Use lowercase, digits, underscore, hyphen, or dot. Got: Invalid ID!
```

### 示例 3：Category 不一致

```java
ComponentDefinition def = new ComponentDefinition();
def.category = ComponentCategory.DOOR;
def.placementSpec = new ComponentPlacementSpec();
def.placementSpec.attachment = AttachmentType.NONE; // 应该是 WALL_OPENING

ValidationResult result = ComponentValidator.validate(def);
// result.warnings() 包含：
// WARN @ placement.attachment: DOOR category usually uses WALL_OPENING attachment
```

### 示例 4：Socket ID 重复

```java
ComponentDefinition def = new ComponentDefinition();
def.sockets = List.of(
    ComponentSocket.builder("door_opening").build(),
    ComponentSocket.builder("door_opening").build() // 重复
);

ValidationResult result = ComponentValidator.validate(def);
// result.errors() 包含：
// ERROR @ sockets[1].id: Duplicate socket id: door_opening
```

## 🔧 扩展验证器

### 添加新的验证规则

1. **在现有验证器中添加**：

```java
// 在 GeometryValidator 中添加
if (def.size != null && def.size.w > 100) {
    out.warn("size.w", "Very wide component (>100). Consider splitting.");
}
```

2. **创建新的验证器**：

```java
public final class CustomValidator {
    public static void validate(ComponentDefinition def, ValidationResult out) {
        // 自定义验证逻辑
    }
}

// 在 ComponentValidator.validate() 中调用
CustomValidator.validate(def, out);
```

3. **添加跨字段检查**：

```java
// 在 ComponentValidator.crossChecks() 中添加
if (def.category == ComponentCategory.DOOR && def.sockets == null) {
    out.warn("sockets", "DOOR category usually has sockets");
}
```

## 📝 Issue 路径格式

Issue 的 `path` 字段使用 JSON 路径格式：

- `$` - 根对象
- `id` - 顶级字段
- `size.w` - 嵌套字段
- `blocks[5]` - 数组元素
- `blocks[5].block` - 数组元素的字段
- `sockets[2].id` - 嵌套数组

## 🚨 错误处理策略

### 保存时

- **当前策略**：记录错误/警告到控制台，但不阻止保存
- **可选策略**：遇到 ERROR 时抛出异常阻止保存

```java
// 在 ComponentStorage.saveComponent() 中
if (!validationResult.ok()) {
    throw new IllegalArgumentException("Invalid component: " + def.id);
}
```

### 加载时

- **当前策略**：记录错误/警告到控制台，但不阻止加载
- **原因**：允许加载旧版本或有问题的构件（向后兼容）

### AI 输出

- **推荐策略**：遇到 ERROR 时拒绝，要求 AI 修复

```java
if (!result.ok()) {
    // 返回错误给 AI
    return "构件验证失败，请修复以下错误：\n" + 
           result.errors().stream()
               .map(ValidationResult.Issue::toString)
               .collect(Collectors.joining("\n"));
}
```

## 🎨 UI 集成建议

### 错误显示

```java
// 在 ComponentPanel 中
ValidationResult result = ComponentValidator.validate(component);

// 错误：红色图标 + 悬停提示
for (var issue : result.errors()) {
    int x = getFieldX(issue.path);
    int y = getFieldY(issue.path);
    drawIcon(x, y, RED_ICON);
    addTooltip(x, y, issue.message);
}

// 警告：黄色图标 + 悬停提示
for (var issue : result.warnings()) {
    int x = getFieldX(issue.path);
    int y = getFieldY(issue.path);
    drawIcon(x, y, YELLOW_ICON);
    addTooltip(x, y, issue.message);
}
```

### 自动修复（未来）

```java
// 未来可以实现自动修复
public static ComponentDefinition autoFix(ComponentDefinition def, ValidationResult result) {
    ComponentDefinition fixed = def.clone();
    
    for (var issue : result.errors()) {
        switch (issue.path) {
            case "id" -> {
                // 自动生成 ID
                fixed.id = generateId(fixed.name);
            }
            case "anchor.facing" -> {
                // 设置默认朝向
                fixed.anchor.facing = "SOUTH";
            }
            // ...
        }
    }
    
    return fixed;
}
```

## ✅ 完成度

| 功能 | 状态 |
|------|------|
| ValidationResult 模型 | ✅ 完成 |
| ComponentValidator 主验证器 | ✅ 完成 |
| GeometryValidator | ✅ 完成 |
| PlacementValidator | ✅ 完成 |
| SocketValidator | ✅ 完成 |
| Cross-checks | ✅ 完成 |
| 保存时集成 | ✅ 完成 |
| 加载时集成 | ✅ 完成 |
| UI 显示（建议） | 📝 待实现 |
| 自动修复 | 📝 未来功能 |

---

**创建时间**: 2026-01-14  
**版本**: v1.0  
**状态**: ✅ 核心功能完成，已集成到保存/加载流程
