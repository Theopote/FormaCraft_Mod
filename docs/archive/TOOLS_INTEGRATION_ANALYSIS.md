# Formacraft 工具集成分析报告

## 📋 执行摘要

根据对代码库的全面检查，以下是对 Formacraft 面板上所有工具与建筑生成、对话聊天、AI 结合的详细分析。

**总体评估**：✅ **大部分工具已良好集成，但部分工具（特别是笔刷工具）需要增强**

---

## ✅ 已良好集成的工具

### 1. 选区工具（SelectionTool）✅ **优秀**

**功能**：用户选中一个空间区域，用于限定建造范围

**集成状态**：✅ **已完全集成**

**集成点**：
1. **Prompt 集成**：
   - `SelectionContext.toPromptBlock()` 生成选区约束提示
   - 明确说明："建筑必须完全位于选区内，不得越界"
   - 包含详细坐标和尺寸信息

2. **服务端约束**：
   - `FormaRequest` 包含 `selectionMin` 和 `selectionMax` 字段
   - Python 后端在 `_build_user_prompt()` 中处理选区信息
   - System Prompt 明确要求遵守选区约束

3. **客户端过滤**：
   - `ToolPatchFilter` 使用 `SelectionOnlyRule` 过滤生成的方块
   - 确保生成的方块完全在选区内

4. **BuildContext 解析**：
   - `BuildContextResolver` 优先级：`Outline > Selection > Anchor`
   - 选区被正确解析为 `BuildContext`

**代码位置**：
- `src/main/java/com/formacraft/ai/context/SelectionContext.java`
- `src/main/java/com/formacraft/client/ui/panel/ChatPanel.java:792-793`
- `python_backend/app/services/ai_planner.py:682-686`

**用户场景支持**：✅
- 用户选中区域 → 输入"请在选区内生成中式别墅建筑" → ✅ 完全支持

---

### 2. 禁区/保护区工具（ProtectedZoneTool）✅ **优秀**

**功能**：设置区域禁止放置方块

**集成状态**：✅ **已完全集成**

**集成点**：
1. **Prompt 集成**：
   - `ProtectedZoneContext.toPromptBlock()` 生成禁区提示
   - 明确说明："以下区域内的方块绝对不能被修改"
   - 列出所有禁区的坐标范围

2. **服务端约束**：
   - `FormaRequest` 包含 `protectedZones` 字段
   - Python 后端 System Prompt 明确要求："If protected zones are provided, you MUST NOT place blocks inside any protected zone."

3. **客户端过滤**：
   - `ToolPatchFilter` 使用 `ProtectedZoneRule` 过滤生成的方块
   - 双重保护：AI 生成时遵守 + 客户端过滤

**代码位置**：
- `src/main/java/com/formacraft/ai/context/ProtectedZoneContext.java`
- `src/main/java/com/formacraft/client/ui/panel/ChatPanel.java:798`
- `python_backend/app/services/ai_planner.py:710-714`

**用户场景支持**：✅
- 用户设置保护区 → 输入建造要求 → ✅ 完全支持

---

### 3. 轮廓工具（OutlineTool）✅ **优秀**

**功能**：绘制封闭轮廓，限定建造范围

**集成状态**：✅ **已完全集成**

**集成点**：
1. **Prompt 集成**：
   - `OutlineContext.toPromptBlock()` 生成轮廓约束提示
   - 支持圆形和多边形轮廓
   - 包含高度范围信息

2. **服务端约束**：
   - `FormaRequest` 包含 `outline` 字段
   - Python 后端 System Prompt 明确要求："If an outline is provided, you MUST build ONLY inside the outline."
   - 在 `_build_user_prompt()` 中详细处理轮廓信息

3. **客户端过滤**：
   - `ToolPatchFilter` 使用 `OutlineRule` 过滤生成的方块
   - 双重保护：AI 生成时遵守 + 客户端过滤

4. **BuildContext 优先级**：
   - 轮廓优先级最高：`Outline > Selection > Anchor`
   - 确保轮廓约束被优先遵守

**代码位置**：
- `src/main/java/com/formacraft/ai/context/OutlineContext.java`
- `src/main/java/com/formacraft/client/ui/panel/ChatPanel.java:797`
- `python_backend/app/services/ai_planner.py:690-708`

**用户场景支持**：✅
- 用户绘制轮廓 → 输入"在轮廓范围内生成哥特式教堂" → ✅ 完全支持

---

### 4. 路径工具（PathTool）✅ **优秀**

**功能**：沿路径建造（道路、线性建筑、长城、围墙等）

**集成状态**：✅ **已完全集成且功能强大**

**集成点**：
1. **路径意图识别**：
   - `ToolPromptBuilder.resolvePathIntent()` 从用户输入识别路径意图
   - 支持：WALL（长城/城墙）、BRIDGE（桥）、ROAD（路）、ALONG_PATH_BUILDING（沿路径建筑）

2. **Prompt 集成**：
   - `PromptAssembler.pathBlock()` 生成路径约束提示
   - `PromptAssembler.skeletonHintBlock()` 显式告诉 AI 使用 `PATH_POLYLINE` 骨架类型
   - 路径拓扑是结构事实，AI 不能破坏

3. **生成器系统**：
   - `PathSkeletonGenerator` 根据意图分发到具体生成器
   - `RoadPathGenerator`、`WallAlongPathGenerator`、`BridgePathGenerator` 等

4. **街道和分区配置**：
   - 支持 `StreetProfile`（单排/双排/三排/城墙走廊等）
   - 支持 `ZoningProfile`（功能分区）
   - 支持 `PathClusterLayout`（建筑群布局）

**代码位置**：
- `src/main/java/com/formacraft/ai/prompt/ToolPromptBuilder.java:25-58`
- `src/main/java/com/formacraft/ai/prompt/PromptAssembler.java:556-635`
- `src/main/java/com/formacraft/server/skeleton/gen/path/PathSkeletonGenerator.java`

**用户场景支持**：✅
- 用户画路径 → 输入"沿着这条路径修长城" → ✅ 完全支持
- 用户画路径 → 输入"修一条路" → ✅ 完全支持
- 用户画路径 → 输入"沿路径建造围墙" → ✅ 完全支持

---

### 5. 对称/镜像工具（SymmetryTool）✅ **良好**

**功能**：使生成的建筑根据不同的对称轴对称

**集成状态**：✅ **已集成，但 Prompt 提示可以增强**

**集成点**：
1. **Prompt 集成**：
   - `SymmetryContext.toPromptBlock()` 生成对称约束提示
   - 支持：MIRROR_X、MIRROR_Z、BOTH、CUSTOM_AXIS
   - 包含对称基准信息

2. **服务端处理**：
   - `ToolConstraintBuilder` 构建 `SymmetryProcessor`
   - 支持自定义轴线

3. **路径工具集成**：
   - 如果启用对称且使用路径工具，自动将 `StreetProfile` 设置为对称

**代码位置**：
- `src/main/java/com/formacraft/ai/context/SymmetryContext.java`
- `src/main/java/com/formacraft/ai/prompt/PromptAssembler.java:514-527`
- `src/main/java/com/formacraft/server/skeleton/gen/geometry/ToolConstraintBuilder.java:97-110`

**问题**：
- ⚠️ Prompt 中的对称提示较简单，可以更详细地说明如何应用对称

**用户场景支持**：✅
- 用户启用对称 → 输入建造要求 → ✅ 基本支持，但可以增强

---

### 6. 区域语义标注工具（SemanticLabelTool）✅ **良好**

**功能**：标注不同的功能区域，每个标注点可以设置不同的作用范围

**集成状态**：✅ **已集成，但 AI 理解可以增强**

**集成点**：
1. **Prompt 集成**：
   - `SemanticLabelContext.toPromptBlock()` 生成语义标注提示
   - 包含标签名称、高度范围、多边形顶点
   - 说明："以下标签把自然语言意图与空间区域绑定，请严格遵守"

2. **工具布局约束**：
   - `ToolLayoutConstraints` 使用语义标注自动 tag
   - 站点落在哪个语义区就用那个 label

**代码位置**：
- `src/main/java/com/formacraft/ai/context/SemanticLabelContext.java`
- `src/main/java/com/formacraft/ai/prompt/PromptAssembler.java:532-545`
- `src/main/java/com/formacraft/client/tools/ToolLayoutConstraints.java:92-97`

**问题**：
- ⚠️ Prompt 中的语义区域说明较简单（只有示例：courtyard, sacred, circulation, residential）
- ⚠️ 没有充分利用 `range`（作用范围）字段
- ⚠️ 没有明确告诉 AI 如何使用标签名称指导生成

**用户场景支持**：⚠️
- 用户标注区域 → 输入建造要求 → ✅ 基本支持，但 AI 可能不完全理解标签的含义

---

## ❌ 未完全集成的工具

### 7. 笔刷工具（BrushTool）❌ **未集成到 AI 生成**

**功能**：刷选一片区域，模组可以在这个区域内生成建筑

**集成状态**：❌ **未集成到 AI 生成流程**

**当前实现**：
- ✅ 工具本身已实现（`BrushTool.java`）
- ✅ UI 已实现（`ToolPanel.java`）
- ✅ 渲染已实现（高亮显示选中的地表方块）
- ❌ **未集成到 PromptAssembler**
- ❌ **未集成到 BuildContext**
- ❌ **未传递到 Python 后端**
- ❌ **未在客户端过滤中使用**

**问题**：
- 笔刷选中的区域没有被读取
- 没有 Context 类（如 `BrushContext`）
- `ToolPromptBuilder.buildToolContext()` 中没有处理笔刷
- `BuildContextResolver` 中没有考虑笔刷区域
- Python 后端没有接收笔刷信息

**用户场景支持**：❌
- 用户刷选区域 → 输入建造要求 → ❌ **不支持**，笔刷选中被忽略

---

## 📊 集成完整性评分

| 工具 | 集成状态 | 评分 | 说明 |
|------|---------|------|------|
| 选区工具 | ✅ 完全集成 | 10/10 | 完美集成，三重保护（Prompt + 服务端 + 客户端过滤） |
| 禁区/保护区 | ✅ 完全集成 | 10/10 | 完美集成，双重保护（Prompt + 客户端过滤） |
| 轮廓工具 | ✅ 完全集成 | 10/10 | 完美集成，优先级最高，双重保护 |
| 路径工具 | ✅ 完全集成 | 10/10 | 完美集成，支持意图识别和多场景 |
| 对称/镜像 | ✅ 基本集成 | 7/10 | 已集成，但 Prompt 提示可以更详细 |
| 区域语义标注 | ⚠️ 部分集成 | 6/10 | 已集成，但 AI 理解可以增强 |
| 笔刷工具 | ❌ 未集成 | 0/10 | 工具已实现，但未集成到 AI 生成流程 |

---

## 🔧 改进建议

### 优先级 1（高优先级）：笔刷工具集成 🔴

**问题**：笔刷工具完全未集成到 AI 生成流程

**建议**：
1. **创建 `BrushContext` 类**：
   ```java
   public final class BrushContext {
       public static boolean hasBrushSelection() {
           return BrushTool.INSTANCE.getSelectedCount() > 0;
       }
       
       public static String toPromptBlock() {
           // 生成笔刷选中区域的描述
           // 包括选中的方块数量、边界范围等
       }
   }
   ```

2. **集成到 `ToolPromptBuilder`**：
   ```java
   // 在 buildToolContext() 中添加
   if (BrushContext.hasBrushSelection()) {
       addMultiline(ctx.constraints, BrushContext.toPromptBlock());
       ctx.rules.add("- 建筑必须生成在笔刷选中的区域内");
   }
   ```

3. **集成到 `BuildContextResolver`**：
   - 将笔刷选中区域转换为约束
   - 或者转换为选区（如果笔刷区域是矩形）

4. **传递到 Python 后端**：
   - 在 `FormaRequest` 中添加笔刷区域信息
   - 在 Python 后端处理笔刷约束

5. **客户端过滤**：
   - 创建 `BrushRegionRule` 过滤生成的方块

---

### 优先级 2（中优先级）：增强语义标注工具的 AI 理解 🟡

**问题**：AI 可能不完全理解语义标注的含义

**建议**：
1. **增强 Prompt 提示**：
   - 在 `SemanticLabelContext.toPromptBlock()` 中更详细地说明每个标签的含义
   - 说明标签的作用范围（`range` 字段）
   - 明确告诉 AI 如何在生成中使用标签

2. **提供标签语义映射**：
   - 为常用标签（如"入口"、"庭院"、"住宅"、"商业"等）提供语义说明
   - 告诉 AI 这些标签对应的建筑特征

**示例改进**：
```java
public static String toPromptBlock() {
    // ...
    sb.append("- 标签含义和使用指南：\n");
    for (AreaLabel l : labels) {
        sb.append("  * ").append(l.name()).append(" (范围=").append(l.range()).append("格): ");
        // 根据标签名称提供语义说明
        switch (l.name().toLowerCase()) {
            case "入口", "entrance" -> sb.append("建筑主入口位置，需要门、台阶、装饰");
            case "庭院", "courtyard" -> sb.append("开放庭院空间，不覆盖屋顶，较低高度");
            // ...
        }
        sb.append("\n");
    }
}
```

---

### 优先级 3（低优先级）：增强对称工具的 Prompt 提示 🟢

**问题**：对称约束的 Prompt 提示较简单

**建议**：
1. **详细说明对称类型**：
   - 明确说明 X 轴、Z 轴、双向对称的区别
   - 说明自定义轴线的使用方法

2. **提供对称应用指南**：
   - 告诉 AI 哪些元素应该对称（主要体量、关键装饰等）
   - 哪些元素可以不对称（细节、纹理变化等）

---

## 📝 总结

### 优秀的地方 ✅

1. **选区、禁区、轮廓工具**：三重/双重保护，完美集成
2. **路径工具**：功能强大，支持多场景和意图识别
3. **工具约束系统**：架构清晰，客户端和服务端都有保护

### 需要改进的地方 ⚠️

1. **笔刷工具**：完全未集成，需要立即实现
2. **语义标注工具**：AI 理解可以增强
3. **对称工具**：Prompt 提示可以更详细

### 总体评估

**集成质量**：**8/10**

大部分工具已良好集成，特别是选区、禁区、轮廓、路径工具。主要问题是笔刷工具完全未集成，需要立即实现。

---

## 🎯 推荐实施顺序

1. **立即实施**：笔刷工具集成（优先级 1）
2. **近期实施**：增强语义标注工具的 AI 理解（优先级 2）
3. **可选实施**：增强对称工具的 Prompt 提示（优先级 3）
