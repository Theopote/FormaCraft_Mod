## KeywordCultureRetriever（关键词检索）— P0

目标：把用户自然语言 prompt 映射到：
- 最可能的 `culture_cards`
- few-shot 示例（`assembly_examples` 片段）
- 一个可直接进入 `Normalizer -> Macro -> Validator -> Compiler` 的 `extra.assembly` 草案

### 输入

- `prompt: string`

### 输出（核心字段）

- `hits[]`: 按 score 排序的候选卡片
  - `card.id/styleId/keywords/exampleRefs/...`
  - `score`
  - `matchedKeywords`
- `assemblyDraft`: 一个最小可执行草案（含 `graph.components[Primary]` + `macro.style`）
- `fewShots[]`: 从 `exampleRefs` 里加载的示例 JSON（用于拼到 LLM prompt 里）

### Prompt 预算（防膨胀）

在 Python 侧用于注入 prompt 的入口是 `retrieve_budgeted()`，支持：

- **`maxItems`**：fewShots 最多条数（默认 2）
- **`maxExampleChars`**：每条 fewShot 的 JSON 最大长度（超出会逐步丢字段降级）
- **`maxChars`**：整个 `CultureRetrieval(JSON)` 的最大长度

降级策略（按顺序触发）：

1. 逐步减少 `fewShots`（2 → 1 → 0）
2. 缩减 `hits` 到 top1 并移除 verbose 字段（matchedKeywords 等）
3. 若仍超限，最终丢弃 `assemblyDraft`

### 配置预算（请求/服务端）

- **请求优先**：`BuildRequest.ragBudget`（Python 侧）
  - `topK / fewShotK / maxItems / maxExampleChars / maxChars`
- **环境变量兜底**（服务端全局默认）：
  - `RAG_TOPK`
  - `RAG_FEWSHOTK`
  - `RAG_MAX_ITEMS`
  - `RAG_MAX_EXAMPLE_CHARS`
  - `RAG_MAX_CHARS`

### 资源来源

- `assets/formacraft/culture_cards/*.json`
- `assets/formacraft/assembly_examples/*.json`

### 快速验证（本地）

使用 gradle 任务（不会进入 CI）：

`gradlew demoCultureRetrieve --args="生成一个哥特式大教堂 玫瑰花窗 飞扶壁"`


