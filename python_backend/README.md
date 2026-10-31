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

### 建造请求并发与超时

`/build` 的同步规划、路由判断及响应编码在独立线程池执行，避免阻塞 FastAPI
事件循环。`BUILD_MAX_CONCURRENT_REQUESTS` 默认 4（每个后端进程独立计数），
满额立即返回 HTTP 503 和 `Retry-After: 1`，不排队。
`BUILD_REQUEST_TIMEOUT_SEC` 默认 900 秒，是包括研究、规划、修复的请求总等待上限；
到期返回 HTTP 504。客户端断连后丢弃结果（内部状态 499），取消请求任务也不再交付结果。

同步 Python 线程无法安全强杀：超时或断连不会保证取消供应商 HTTP 请求，
后台调用依然可能产生费用，并持续占用并发额度直到实际结束。
已有的 LLM 单次调用超时继续生效；需要严格取消供应商调用时，仍需进一步改造异步客户端。
健康检查使用独立执行资源，不占建造线程池。

## 运行服务

也可从游戏设置面板底部的“本机后端服务”配置后端目录、Python 和端口，然后点击“保存并启动”。首次仍需完成依赖安装；详细操作和验证边界见 [设置面板启动说明](../docs/BACKEND_STARTUP.md)。

```bash
# 在 python_backend/ 目录下 — 默认只监听本机
uvicorn app.main:app --reload --host 127.0.0.1 --port 8000
```

服务将在 `http://localhost:8000` 启动。若要对局域网开放，必须设置环境变量 `FORMACRAFT_ORCHESTRATOR_SECRET`，并在 Minecraft 服务端配置相同密钥（`FORMACRAFT_ORCHESTRATOR_SECRET` / `orchestratorSecret`）；请求头为 `X-FormaCraft-Secret`。

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

## 服务端凭据与调试

专用 Minecraft 服务器不接受客户端 API Key、Provider、Base URL 或 model。
在 Minecraft 服务端进程设置 `FORMACRAFT_API_KEY`、`FORMACRAFT_LLM_PROVIDER`、
`FORMACRAFT_LLM_BASE_URL`、`FORMACRAFT_LLM_MODEL`，或复制仓库中的
`config/formacraft_server.properties.example` 为 `config/formacraft_server.properties`。
环境变量优先，配置修改后重启服务端；未设置的值回落到 Python 后端环境。
管理员 Key 必须搭配管理员控制的 URL，不能单独保留客户端 URL。
单人游戏仍支持设置面板中的本地 Key（本地 JSON 为明文，请保护文件）。

CORS 默认不允许跨域浏览器访问，Minecraft 的 HTTP 客户端不需要 CORS。
本地浏览器调试可在后端进程设置 `FORMACRAFT_CORS_ORIGINS=http://localhost:3000`；
多个源用逗号分隔，不开启 cookie 凭据。CORS 不替代后端访问控制。

LlmPlan 使用独立温度，默认 `0.3`，可通过后端环境 `LLMPLAN_TEMPERATURE` 调整
（限 0–1）；其他规划路径仍按原请求温度工作。降低温度不保证造型质量提升，需实测评估。
