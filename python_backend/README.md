# FormaCraft Python Backend

FormaCraft 的 Python 后端服务，负责接收 Minecraft 客户端的建筑请求，调用 AI 生成建筑规格。

## 项目结构

```
python_backend/
├── app/
│   ├── main.py                 # FastAPI 主程序
│   ├── models/
│   │   ├── __init__.py
│   │   ├── building_spec.py    # BuildingSpec 相关模型（与 Java 端对齐）
│   │   ├── request.py          # BuildRequest 模型
│   │   └── request_adapter.py  # 请求适配器（兼容不同格式）
│   ├── services/
│   │   ├── __init__.py
│   │   └── ai_planner.py       # AI 规划服务（调用大模型）
│   └── routes/
│       ├── __init__.py
│       └── build.py            # /build 路由
├── requirements.txt            # Python 依赖
└── README.md                   # 本文件
```

## 安装依赖

```bash
cd python_backend
pip install -r requirements.txt
```

可复现安装（锁定版本）：

```bash
pip install -r requirements.lock
```

## 配置环境变量

复制示例配置并编辑：

```bash
cp .env.example .env
```

主要变量见 `.env.example`（`OPENAI_API_KEY`、`LLM_PROVIDER`、`LLM_BASE_URL` 等）。

如果没有配置 API Key，系统会使用规则基础的回退方案。

## 运行服务

```bash
# 在 python_backend/ 目录下
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

服务将在 `http://localhost:8000` 启动。

## API 端点

### POST /build

接收来自 Minecraft 服务器的建筑请求，返回 AI 生成的建筑规格。

**请求格式**：

```json
{
  "player": {
    "name": "Player",
    "pos": {"x": 100, "y": 64, "z": 200},
    "facing": "NORTH"
  },
  "world": {
    "dimension": "minecraft:overworld",
    "biome": "minecraft:plains"
  },
  "selection": {
    "min": {"x": 90, "y": 64, "z": 190},
    "max": {"x": 120, "y": 80, "z": 220}
  },
  "requestText": "在我前面建一个 20×20 的中世纪塔楼，两层，有窗户"
}
```

**响应格式**：

```json
{
  "type": "TOWER",
  "style": "MEDIEVAL",
  "footprint": {
    "shape": "circle",
    "radius": 6
  },
  "height": 20,
  "floors": 2,
  "materials": {
    "wall": "minecraft:stone_bricks",
    "roof": "minecraft:dark_oak_planks",
    "floor": "minecraft:spruce_planks",
    "window": "minecraft:glass_pane"
  },
  "features": {
    "hasWindows": true,
    "hasStairs": true,
    "hasDoor": true,
    "hasBalcony": false,
    "hasRoof": true,
    "hasRoofDecoration": true,
    "windowCount": 4,
    "floorCount": 2
  },
  "notes": "AI 生成的说明",
  "extra": {
    "roofType": "cone",
    "windowRatio": 0.35
  }
}
```

## 与 Java 端的对接

Java 端的 `OrchestratorClient` 会发送 HTTP POST 请求到 `/build` 端点。

请求和响应的 JSON 格式与 Java 端的 `FormaRequest` 和 `BuildingSpec` 完全对齐。

## 开发说明

### 添加新的建筑类型

1. 在 `app/models/building_spec.py` 的 `BuildingType` 枚举中添加新类型
2. 在 `app/services/ai_planner.py` 的 `_build_system_prompt()` 中更新提示词
3. 在 Java 端的 `BuildingType` 枚举中添加对应类型

### 自定义 AI 模型

修改 `app/services/ai_planner.py` 中的 `generate_building_spec()` 函数：

```python
response = client.chat.completions.create(
    model="your-model-name",  # 修改这里
    ...
)
```

### 回退方案

如果 OpenAI API 不可用或调用失败，系统会自动使用规则基础的回退方案（`_generate_fallback_spec()`），确保服务始终可用。

### 能力现状与路线图（推荐先读）

- `CAPABILITIES_AND_GAPS.md`：LLM 建筑泛化能力的现状、缺口与下一步路线图

## 测试

可以使用 curl 测试 API：

```bash
curl -X POST http://localhost:8000/build \
  -H "Content-Type: application/json" \
  -d '{
    "player": {
      "name": "TestPlayer",
      "pos": {"x": 0, "y": 64, "z": 0},
      "facing": "NORTH"
    },
    "world": {
      "dimension": "minecraft:overworld",
      "biome": "minecraft:plains"
    },
    "requestText": "建一个简单的房子"
  }'
```

### 离线 Smoke（推荐）

当你调整 `ai_planner.py` 的提示词/归一化/回退逻辑（尤其是 `extra.styleProfileId/paletteId`）时，推荐先跑一次离线自检：

```bash
python python_backend/tools/spec_contract_smoke.py
```

该脚本不依赖 LLM/网络，主要验证：
- `styleProfileId` 合法性校验与自动回退（无效则写入 `extra.debugWarnings`）
- `paletteId` 合法性校验与自动回退
- `styleProfileId` 存在默认 palette 且 `paletteId` 缺失时，自动补齐 `paletteId`（并置 `paletteIdAutoFromStyle=true`）

