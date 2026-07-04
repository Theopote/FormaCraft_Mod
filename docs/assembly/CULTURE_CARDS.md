## Culture Cards（文化知识卡）— P0

目标：把“文化知识 → 参数 → assembly”变成可检索、可回归验证、可迭代的数据资产。

### 存放位置

- `src/main/resources/assets/formacraft/culture_cards/*.json`

### P0 Schema（最小稳定形状）

每个文件一个 JSON 对象：

- **`id`**: string（必填，唯一）
- **`styleId`**: string（必填，风格主键/桶名）
- **`intents`**: string[]（可选，建议填写）
- **`keywords`**: string[]（必填，检索关键词）
- **`synonyms`**: map<string, string[]>（可选，同义词扩展，key 为关键词，value 为同义词列表）
- **`keywordWeights`**: map<string, number>（可选，关键词权重，默认 3；可把“玫瑰花窗/飞扶壁”等提升权重）
- **`negativeKeywords`**: string[]（可选，负向词；命中会惩罚分数，用于降低风格串味）
- **`exampleRefs`**: string[]（可选，引用 `assembly_examples/*.json`，用于 Few-shot/对照）
- **`llmPlanExampleRefs`**: string[]（可选，引用 `llmplan_examples/*.json`，用于 LlmPlan MODULE few-shot）
- **`landmarkModuleId`**: string（可选，命中此卡片时强制 LlmPlan 走 `MODULE` + `landmark:<id>`）
- **`archetypes`**: object[]（必填）
  - **`name`**: string
  - **`exampleRef`**: string（可选，必须存在于 `assembly_examples`）
  - **`macroHint`**: object（可选，建议用 `macro.style` 形状）
  - **`constraints`**: string[]（可选，自然语言约束）

### 构建期校验

- `gradlew validateCultureCards`：检查卡片 shape、必填字段、以及 `exampleRef/exampleRefs` 的引用文件是否存在。


