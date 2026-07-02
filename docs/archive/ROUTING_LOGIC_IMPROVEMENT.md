# 路由逻辑改进说明

## 📋 改进内容

### 1. 添加明确的输出格式字段

**Java 端** (`FormaRequest.java`)：
- 添加 `outputFormat` 字段（可选）
- 支持值：
  - `"llmplan"`: 强制使用 LlmPlan 格式
  - `"buildingspec"`: 强制使用 BuildingSpec 格式
  - `"auto"`: 自动决定（默认）

**Python 端** (`request.py`, `request_adapter.py`)：
- 在 `BuildRequest` 和 `FormaRequestAdapter` 中添加 `outputFormat` 字段
- 支持相同的值

### 2. Java 端自动设置输出格式

**ChatPanel.java**：
- 因为 `PromptAssembler` 总是生成 LlmPlan 格式的 prompt，所以默认设置 `outputFormat = "llmplan"`
- 用户可以通过 `outputFormat` 字段覆盖这个行为

### 3. 改进 Python 后端路由逻辑

**build.py**：
- **优先级 1**：如果明确指定了 `outputFormat`，使用指定的格式
- **优先级 2**：如果 `outputFormat` 是 "auto" 或未设置，使用智能路由：
  - 检查 `requestText` 是否包含 LlmPlan 格式的特征
  - 检查是否需要生成城市或复合结构
  - 默认生成 BuildingSpec

## ✅ 改进效果

### 之前的问题

1. **路由不够准确**：基于关键词匹配，可能误判
2. **系统选择逻辑分散**：Java 端和 Python 端都有判断逻辑
3. **难以控制**：无法明确指定使用哪个系统

### 改进后

1. **明确控制**：可以通过 `outputFormat` 字段明确指定输出格式
2. **自动优化**：Java 端根据 `PromptAssembler` 自动设置合适的默认值
3. **智能路由**：Python 后端仍支持自动路由，但更准确
4. **向后兼容**：如果不设置 `outputFormat`，行为与之前相同

## 🎯 使用场景

### 场景 1：使用 LlmPlan（默认）

```java
FormaRequest req = new FormaRequest();
req.setRequestText(PromptAssembler.assemble(userInput, PromptMode.BUILD));
req.setOutputFormat("llmplan"); // 自动设置，也可手动指定
```

### 场景 2：强制使用 BuildingSpec

```java
FormaRequest req = new FormaRequest();
req.setRequestText(userInput);
req.setOutputFormat("buildingspec"); // 明确指定
```

### 场景 3：自动路由（向后兼容）

```java
FormaRequest req = new FormaRequest();
req.setRequestText(userInput);
// outputFormat 不设置或设置为 "auto"，Python 后端会智能路由
```

## 📝 注意事项

1. **默认行为**：`ChatPanel` 会自动设置 `outputFormat = "llmplan"`，因为 `PromptAssembler` 总是生成 LlmPlan 格式的 prompt
2. **向后兼容**：如果不设置 `outputFormat`，Python 后端会使用之前的逻辑（关键词匹配）
3. **明确优先**：如果明确指定了 `outputFormat`，会忽略其他判断逻辑
