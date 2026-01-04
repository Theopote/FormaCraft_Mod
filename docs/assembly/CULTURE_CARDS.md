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
- **`exampleRefs`**: string[]（可选，引用 `assembly_examples/*.json`，用于 Few-shot/对照）
- **`archetypes`**: object[]（必填）
  - **`name`**: string
  - **`exampleRef`**: string（可选，必须存在于 `assembly_examples`）
  - **`macroHint`**: object（可选，建议用 `macro.style` 形状）
  - **`constraints`**: string[]（可选，自然语言约束）

### 构建期校验

- `gradlew validateCultureCards`：检查卡片 shape、必填字段、以及 `exampleRef/exampleRefs` 的引用文件是否存在。


