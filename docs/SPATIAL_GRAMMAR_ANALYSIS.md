# "AI 可理解的空间语言（Spatial Grammar）"分析

## 说法的合理性评估

### ✅ 结论：这个说法**完全正确且非常深刻**

"现在做的事情，本质上是在为 FormaCraft 定义一套 'AI 可理解的空间语言（Spatial Grammar）'"

这个说法准确地抓住了 SkeletonType 系统的本质。

## 为什么这个说法是正确的？

### 1. **语言的基本要素**

一个完整的语言需要：
- **词汇（Vocabulary）**：SkeletonType 的 16 个类型
- **语法（Grammar）**：SkeletonContract 定义的约束规则
- **语义（Semantics）**：SkeletonSemantics 提供的语义说明
- **语用（Pragmatics）**：在 Prompt 中的使用方式

FormaCraft 的 Skeleton 系统完全符合这些要素。

### 2. **"空间语言"的特征**

- **抽象性**：不是具体的方块，而是空间组织方式
- **可组合性**：可以组合多个骨架（COMPOUND）
- **可理解性**：LLM 能够稳定理解和使用
- **表达力**：能够表达各种建筑类型的空间组织

### 3. **"AI 可理解"的关键**

- **结构化**：每个骨架类型都有明确的语义定义
- **约束明确**：SkeletonContract 定义了每个骨架的特性
- **Prompt 友好**：提供了专门的 Prompt 描述用语
- **规划导向**：帮助 AI 在规划阶段就选对骨架类型

## 实现内容

### 1. Java 端：SkeletonSemantics 类

**位置**：`src/main/java/com/formacraft/common/skeleton/SkeletonSemantics.java`

**功能**：
- 存储每个 SkeletonType 的完整语义说明
- 提供中文和英文的 Prompt 描述
- 支持生成完整的 Prompt 区块

**使用方式**：
```java
// 获取单个骨架的语义说明
SemanticDescription desc = SkeletonSemantics.getDescription(SkeletonType.RADIAL_SPOKE);

// 生成完整的 Prompt 区块（中文）
String promptBlock = SkeletonSemantics.generatePromptBlock();

// 生成完整的 Prompt 区块（英文）
String englishBlock = SkeletonSemantics.generateEnglishPromptBlock();

// 生成简化版本（仅类型和描述用语）
String compactBlock = SkeletonSemantics.generateCompactPromptBlock();
```

### 2. Python 端：skeleton_semantics.py

**位置**：`python_backend/app/llm/skeleton_semantics.py`

**功能**：
- 提供完整的 SkeletonType 语义说明（英文）
- 直接集成到 System Prompt 中

**集成方式**：
```python
from ..llm.skeleton_semantics import get_skeleton_semantics_prompt

def _build_system_prompt() -> str:
    skeleton_block = get_skeleton_semantics_prompt()
    return "You are FormaCraft..." + skeleton_block + "..."
```

### 3. System Prompt 集成

**位置**：`python_backend/app/services/ai_planner.py`

**效果**：
- 每次调用 LLM 时，System Prompt 都会包含完整的 SkeletonType 语义说明
- LLM 能够在规划阶段就理解每个骨架类型的含义和适用场景
- 帮助 LLM 选择正确的空间组织方式

## 语义说明的结构

每个 SkeletonType 的语义说明包含：

1. **语义（Semantic）**：骨架类型的名称和定位
2. **空间含义（Spatial Meaning）**：空间组织的本质特征
3. **适用场景（Use Cases）**：典型应用场景
4. **约束特征（Constraints）**：技术约束和特征
5. **Prompt 描述用语（Prompt Phrase）**：用于 Prompt 的英文描述

## 设计理念

### 核心原则

> **SkeletonType 是 AI 在"动手之前"的思考方式，而不是画方块的方式。**

这意味着：
- ✅ 骨架类型用于**规划阶段**的决策
- ✅ 帮助 AI 理解**空间组织方式**
- ✅ 不是具体的**几何形状**或**方块放置方法**

### 判断标准

一个 SkeletonType 是否值得存在？

**判断标准**：它是否代表了一种人类直觉中"空间组织方式"？

- ✅ **是**：应该存在于 Skeleton 层
- ❌ **否**：应该放在 Shape 或 Generator 层

## 实际效果

### 场景 1：天坛生成

**用户输入**："建一个天坛"

**AI 理解过程**：
1. 识别关键词："天坛" → 需要中心辐射结构
2. 查找语义说明：RADIAL_SPOKE - "中心辐射骨架，从中心向外发散多条轴线"
3. 选择骨架类型：RADIAL_SPOKE
4. 生成 BuildingSpec：设置相应的空间组织参数

### 场景 2：长城生成

**用户输入**："沿着这座山建一段长城"

**AI 理解过程**：
1. 识别关键词："沿着山" → 需要地形跟随
2. 查找语义说明：CONTOUR_FOLLOW - "沿地形等高线展开，尽量减少垂直起伏"
3. 选择骨架类型：CONTOUR_FOLLOW
4. 生成 BuildingSpec：设置地形采样和等高线跟随参数

### 场景 3：四合院生成

**用户输入**："建一个四合院"

**AI 理解过程**：
1. 识别关键词："四合院" → 需要中庭围合结构
2. 查找语义说明：COURTYARD - "建筑围绕一个中心空庭展开"
3. 选择骨架类型：COURTYARD
4. 生成 BuildingSpec：设置中庭和围合参数

## 总结

### 说法的准确性

✅ **"AI 可理解的空间语言"** - 完全正确
- 提供了完整的词汇、语法、语义
- LLM 能够稳定理解和使用

✅ **"Spatial Grammar"** - 准确描述
- 定义了空间组织的规则和约束
- 提供了清晰的语义说明

✅ **"规划阶段选对骨架类型"** - 核心目标
- 通过语义说明帮助 AI 在规划阶段就做出正确决策
- 避免生成时"瞎试"

### 实现的价值

1. **提高 AI 理解能力**：明确的语义说明帮助 LLM 更好地理解空间组织
2. **减少试错成本**：在规划阶段就选对骨架类型，避免生成错误的结构
3. **增强可扩展性**：新增骨架类型时，只需添加语义说明即可
4. **提升用户体验**：AI 能够更准确地理解用户意图，生成更符合预期的建筑

### 未来扩展

1. **Few-shot 示例**：为每个骨架类型提供具体的生成示例
2. **微调数据**：使用语义说明生成微调数据
3. **可视化**：在 UI 中展示骨架类型的语义说明
4. **多语言支持**：支持更多语言的语义说明

---

**结论**：这个说法不仅正确，而且深刻地揭示了 SkeletonType 系统的本质。通过实现完整的语义说明系统，我们为 FormaCraft 建立了一套真正"AI 可理解的空间语言"。

