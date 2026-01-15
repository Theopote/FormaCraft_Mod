# ComponentTool 验证和修复功能整合总结

## 📋 整合内容

根据建议，已成功整合验证和修复功能到 ComponentTool 中，采纳了以下核心思想：

1. ✅ **状态模型** - 在 ComponentToolState 中添加验证和修复状态
2. ✅ **自动验证** - 加载构件时自动验证
3. ✅ **手动验证/修复** - 提供手动触发的方法
4. ✅ **AI 接口保护** - PromptAssembler 中检查构件有效性

## ✅ 已实现的功能

### 1. 扩展 ValidationResult

- ✅ 添加 `hasErrors()` 方法
- ✅ 添加 `hasWarnings()` 方法

### 2. 扩展 ComponentToolState

**新增字段（transient，不序列化）**：
- `componentForValidation` - 当前验证的构件
- `validationResult` - 验证结果
- `autoFixReport` - 自动修复报告
- `componentDirty` - 是否被修改
- `validated` - 是否已运行验证
- `autoFixed` - 是否已运行 AutoFix

**新增方法**：
- `setComponentForValidation()` - 设置验证目标
- `getValidationResult()` - 获取验证结果
- `getAutoFixReport()` - 获取修复报告
- `isComponentValid()` - 检查是否有效
- `validateComponent()` - 运行验证
- `autoFixComponent()` - 运行自动修复（修复后自动重新验证）

### 3. 扩展 ComponentTool

**新增方法**：
- `getLoadedComponent()` - 获取当前加载的构件（用于验证和 AI 接口）
- `validateLoadedComponent()` - 手动触发验证（用于 UI 按钮）
- `autoFixLoadedComponent()` - 手动触发自动修复（用于 UI 按钮）
- `isLoadedComponentValid()` - 检查当前加载的构件是否有效（用于 AI 接口保护）

**自动验证**：
- ✅ 加载构件时自动验证（`onComponentDefinitionFromServer()`）
- ✅ 显示验证结果（错误/警告/通过）

### 4. PromptAssembler AI 接口保护

- ✅ 在 `componentLibraryBlock()` 中检查当前加载的构件
- ✅ 如果构件无效，在 Prompt 中添加警告
- ✅ 提示 AI 不要使用无效的构件

## 🔄 数据流

### 加载构件时的自动流程

```
[ 加载构件 JSON ]
        ↓
onComponentDefinitionFromServer()
        ↓
state.setComponentForValidation(def)
        ↓
state.validateComponent()
        ↓
[ 显示验证结果 ]
- 有错误 → Toast 显示错误数量
- 有警告 → Toast 显示警告数量
- 无问题 → Toast 显示"验证通过"
```

### 手动验证/修复流程

```
[ 用户点击 Validate 按钮 ]
        ↓
validateLoadedComponent()
        ↓
state.validateComponent()
        ↓
[ 显示验证结果 ]

[ 用户点击 AutoFix 按钮 ]
        ↓
autoFixLoadedComponent()
        ↓
state.autoFixComponent()
        ↓
[ 应用修复 ]
        ↓
[ 自动重新验证 ]
        ↓
[ 显示修复报告和验证结果 ]
```

### AI 接口保护流程

```
[ PromptAssembler 生成 Prompt ]
        ↓
componentLibraryBlock()
        ↓
检查 ComponentTool.getLoadedComponent()
        ↓
检查 isLoadedComponentValid()
        ↓
如果无效 → 在 Prompt 中添加警告
        ↓
[ AI 看到警告，不使用无效构件 ]
```

## 🎯 核心设计思想采纳

### 1. 状态模型（已采纳）

**建议**：先补状态模型，否则 UI 一定乱

**实现**：
- ✅ 在 `ComponentToolState` 中添加验证和修复状态
- ✅ 使用 `transient` 字段，不序列化
- ✅ 提供清晰的状态查询方法

### 2. 自动验证（已采纳）

**建议**：加载构件时自动运行 Validator

**实现**：
- ✅ `onComponentDefinitionFromServer()` 中自动验证
- ✅ 显示验证结果给用户

### 3. 自动修复后重新验证（已采纳）

**建议**：AutoFix 后必须立刻 re-validate，否则 UI 会出现"假绿灯"

**实现**：
- ✅ `autoFixComponent()` 修复后自动调用 `validateComponent()`
- ✅ 确保状态一致性

### 4. AI 接口保护（已采纳）

**建议**：PromptAssembler 只能接受 `state.isValid() == true`

**实现**：
- ✅ `isLoadedComponentValid()` 方法
- ✅ PromptAssembler 中检查并添加警告
- ✅ 防止 AI 使用无效构件

## 📊 使用示例

### 示例 1：加载构件时自动验证

```java
// 用户从构件库加载构件
ComponentTool.INSTANCE.requestLoadSelectedComponent();

// 服务端返回 JSON
ComponentTool.INSTANCE.onComponentDefinitionFromServer(json);

// 自动执行：
// 1. 解析 JSON → ComponentDefinition
// 2. 设置验证目标
// 3. 运行验证
// 4. 显示结果：
//    - "已加载构件，但有 2 个错误" (如果有错误)
//    - "已加载构件，有 1 个警告" (如果只有警告)
//    - "已加载构件（验证通过）" (如果无问题)
```

### 示例 2：手动验证

```java
// 用户点击"验证"按钮
ComponentTool.INSTANCE.validateLoadedComponent();

// 执行：
// 1. 检查是否有加载的构件
// 2. 运行验证
// 3. 显示结果：
//    - "验证失败：发现 2 个错误"
//    - "验证通过，但有 1 个警告"
//    - "验证通过 ✓"
```

### 示例 3：手动修复

```java
// 用户点击"自动修复"按钮
ComponentTool.INSTANCE.autoFixLoadedComponent();

// 执行：
// 1. 检查是否有加载的构件
// 2. 运行自动修复
// 3. 显示修复报告："已应用 3 个自动修复"
// 4. 自动重新验证
// 5. 显示验证结果：
//    - "修复后仍有 1 个错误"
//    - "修复后仍有 1 个警告"
//    - "修复完成，验证通过 ✓"
```

### 示例 4：AI 接口保护

```java
// PromptAssembler 生成 Prompt 时
String prompt = PromptAssembler.assemble(userInput, mode);

// 在 componentLibraryBlock() 中：
// 1. 检查 ComponentTool.getLoadedComponent()
// 2. 如果存在且无效，添加警告：
//    "⚠️ WARNING: Currently loaded component has validation errors 
//     and should NOT be used in AI generation:
//      - placement.attachment: Missing attachment
//      - blocks[5].block: Missing block state string
//     Please fix the component before using it in AI generation."
```

## 🎨 UI 集成建议（待实现）

虽然核心功能已实现，但 UI 显示部分需要根据现有 UI 架构集成。建议在以下位置添加：

### 1. ComponentCapturePanel（构件拾取面板）

在保存构件前显示验证结果：

```java
// 在保存按钮附近添加验证状态显示
if (state.getValidationResult() != null) {
    if (state.getValidationResult().hasErrors()) {
        // 显示红色错误图标
        drawErrorIcon(x, y);
    } else if (state.getValidationResult().hasWarnings()) {
        // 显示黄色警告图标
        drawWarningIcon(x, y);
    } else {
        // 显示绿色通过图标
        drawSuccessIcon(x, y);
    }
}
```

### 2. ComponentLibraryPanel（构件库面板）

在加载构件后显示验证结果：

```java
// 在构件详情区域显示
if (ComponentTool.INSTANCE.getState().getValidationResult() != null) {
    var result = ComponentTool.INSTANCE.getState().getValidationResult();
    // 显示验证状态徽章
    drawStatusBadge(x, y, result);
}
```

### 3. ToolPanel（工具面板）

添加验证和修复按钮（如果 ComponentTool 激活）：

```java
// 在 ComponentTool 选项区域
if (ToolManager.isActive(ComponentTool.INSTANCE.getId())) {
    // 验证按钮
    ButtonWidget validateBtn = ButtonWidget.builder(
        Text.literal("验证"),
        b -> ComponentTool.INSTANCE.validateLoadedComponent()
    ).build();
    
    // 自动修复按钮
    ButtonWidget autoFixBtn = ButtonWidget.builder(
        Text.literal("自动修复"),
        b -> ComponentTool.INSTANCE.autoFixLoadedComponent()
    ).build();
    
    // 显示验证结果
    if (ComponentTool.INSTANCE.getState().getValidationResult() != null) {
        renderValidationResults(ctx, x, y, w);
    }
}
```

## 📝 状态徽章设计建议

| 状态 | 条件 | 显示 | 颜色 |
|------|------|------|------|
| ❌ INVALID | `hasErrors()` | 错误数量 | 红色 `0xFFFF5555` |
| ⚠️ WARN | 无错误但有警告 | 警告数量 | 黄色 `0xFFFFAA00` |
| 🛠 FIXED | `isComponentDirty()` | "已修复" | 蓝色 `0xFF66CCFF` |
| ✅ READY | `isComponentValid()` | "就绪" | 绿色 `0xFF55FF55` |

**可以叠加显示**：
- 🛠 FIXED · ⚠️ WARN（修复后仍有警告）
- ✅ READY（修复后验证通过）

## ✅ 完成度

| 功能 | 状态 | 说明 |
|------|------|------|
| ValidationResult 扩展 | ✅ 完成 | hasErrors(), hasWarnings() |
| ComponentToolState 扩展 | ✅ 完成 | 验证和修复状态管理 |
| ComponentTool 扩展 | ✅ 完成 | 验证/修复方法，自动验证 |
| 加载时自动验证 | ✅ 完成 | onComponentDefinitionFromServer() |
| AI 接口保护 | ✅ 完成 | PromptAssembler 中检查 |
| UI 显示 | 📝 待实现 | 根据现有 UI 架构集成 |
| 状态徽章 | 📝 待实现 | 在 UI 中显示验证状态 |

## 🎉 总结

已成功整合验证和修复功能到 ComponentTool：

- ✅ **状态模型** - ComponentToolState 中添加验证和修复状态
- ✅ **自动验证** - 加载构件时自动验证
- ✅ **手动操作** - 提供验证和修复方法
- ✅ **AI 接口保护** - PromptAssembler 中检查构件有效性
- ✅ **不改变架构** - 只扩展现有类，不创建新架构

**核心思想已采纳**：
- ✅ 让用户"看得见 AI 在干什么"，而不是黑盒
- ✅ 系统从"能用"到"可信任"
- ✅ 修复后自动重新验证，确保状态一致
- ✅ AI 接口保护，防止使用无效构件

---

**整合时间**: 2026-01-14  
**版本**: v1.0  
**状态**: ✅ 核心功能完成，UI 显示待根据现有架构集成
