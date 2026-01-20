# 建筑参考资料搜索功能

## 概述

`architecture_researcher.py` 为 FormaCraft 后端添加了网络搜索功能，用于为强类型建筑（如埃菲尔铁塔、中国古建筑等）自动获取参考资料，提升生成质量。

## 功能特性

- **自动检测强类型建筑**：识别用户请求中的地标建筑或建筑风格关键词
- **智能搜索**：自动构建搜索查询，获取建筑结构、设计特点、尺寸规格等信息
- **多搜索源支持**：
  - 优先使用 DuckDuckGo（免费，无需 API key）
  - 可选使用 Bing Search API（需要 API key，结果更准确）
- **结果整合**：将搜索结果格式化为上下文，添加到 LLM prompt 中

## 使用方法

### 1. 安装依赖

```bash
pip install -r requirements.txt
```

依赖包括：
- `requests` - HTTP 请求
- `duckduckgo-search` - DuckDuckGo 搜索库（可选，未安装时会使用 HTTP 回退方案）

### 2. 配置（可选）

如果需要使用 Bing Search API（更准确的结果），设置环境变量：

```bash
export BING_SEARCH_API_KEY="your_bing_api_key"
```

### 3. 自动触发

当用户请求包含以下类型的建筑时，系统会自动搜索参考资料：

**地标建筑：**
- 埃菲尔铁塔、自由女神像、大本钟、悉尼歌剧院
- 金字塔、长城、天坛、故宫、布达拉宫
- 比萨斜塔、泰姬陵、罗马斗兽场等

**建筑风格：**
- 中式、日式、欧式建筑
- 希腊、罗马、拜占庭风格
- 伊斯兰、印度、东南亚建筑等

## 工作流程

1. **检测阶段**：`get_architecture_reference_context()` 检测用户请求是否包含强类型建筑关键词
2. **搜索阶段**：如果检测到关键词，自动构建搜索查询并执行搜索
3. **整合阶段**：将搜索结果格式化为上下文，添加到 LLM prompt 的前面
4. **生成阶段**：LLM 使用参考资料生成更准确的建筑规划

## API 说明

### `get_architecture_reference_context(text: str) -> Optional[str]`

为主要入口函数，检测文本中的建筑关键词并返回格式化的参考资料上下文。

**参数：**
- `text`: 用户请求文本

**返回：**
- 如果找到相关建筑，返回格式化的参考资料上下文字符串
- 如果未找到，返回 `None`

**示例：**

```python
from app.services.architecture_researcher import get_architecture_reference_context

context = get_architecture_reference_context("在锚点位置生埃菲尔铁塔")
if context:
    print(context)  # 输出格式化的参考资料
```

### `search_architecture_reference(query: str, max_results: int = 3) -> List[Dict[str, str]]`

执行搜索并返回原始结果。

**参数：**
- `query`: 搜索查询字符串
- `max_results`: 最大结果数量（默认 3）

**返回：**
- 搜索结果列表，每个结果包含 `title`, `snippet`, `url`

## 扩展关键词

要添加新的建筑类型或风格，编辑 `architecture_researcher.py` 中的关键词列表：

```python
LANDMARK_BUILDINGS = [
    "你的新关键词",
    # ...
]

ARCHITECTURE_STYLES = [
    "你的新风格",
    # ...
]
```

## 注意事项

1. **搜索失败不影响生成**：如果搜索失败（网络问题、API key 缺失等），系统会记录警告但不会阻塞建筑生成
2. **Token 限制**：搜索结果会被截断以避免超过 LLM token 限制
3. **隐私**：搜索查询会发送到外部服务（DuckDuckGo 或 Bing），请确保符合隐私政策
4. **性能**：搜索会增加生成延迟（通常 1-3 秒），但对于强类型建筑，准确性的提升值得这个代价

## 故障排除

### 搜索无结果

- 检查网络连接
- 确认关键词是否正确匹配
- 查看日志中的警告信息

### DuckDuckGo 搜索失败

- 确保已安装 `duckduckgo-search`：`pip install duckduckgo-search`
- 或使用 Bing Search API（需要 API key）

### 搜索结果不相关

- 可以调整搜索查询构建逻辑（在 `get_architecture_reference_context()` 中）
- 可以添加更多关键词到检测列表

## 未来改进

- [ ] 支持更多搜索引擎（Google Custom Search API）
- [ ] 缓存搜索结果，避免重复搜索
- [ ] 支持图像搜索结果（用于建筑外观参考）
- [ ] 智能提取建筑尺寸和比例信息
- [ ] 支持多语言搜索（当前主要支持中英文）
