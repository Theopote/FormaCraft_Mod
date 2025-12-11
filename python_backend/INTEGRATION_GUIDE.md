# FormaCraft Java-Python 集成指南

## 数据格式对齐

### Java → Python 请求格式

Java 端的 `FormaRequest` 使用扁平结构：

```json
{
  "requestText": "帮我建一个中世纪塔楼",
  "playerPos": {"x": 100, "y": 64, "z": 200},
  "facing": "NORTH",
  "dimension": "minecraft:overworld",
  "biome": "minecraft:plains",
  "selectionMin": {"x": 90, "y": 64, "z": 190},
  "selectionMax": {"x": 120, "y": 80, "z": 220},
  "sessionId": "optional-session-id",
  "chatHistory": ["Player: 之前说的话", "AI: 之前的回复"]
}
```

Python 端的 `BuildRequest` 使用嵌套结构：

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
  "requestText": "帮我建一个中世纪塔楼",
  "sessionId": "optional-session-id",
  "chatHistory": ["Player: 之前说的话", "AI: 之前的回复"]
}
```

**适配器会自动处理转换**：`FormaRequestAdapter` 会将 Java 端的扁平格式转换为 Python 端的嵌套格式。

### Python → Java 响应格式

Python 端返回的 `BuildingSpec` 与 Java 端完全对齐：

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

Java 端的 `JsonUtil.fromJson()` 会自动将 JSON 转换为 `BuildingSpec` 对象。

## BlockPos 序列化

Java 端的 `BlockPos` 通过 Gson 序列化时，会生成如下格式：

```json
{
  "x": 100,
  "y": 64,
  "z": 200
}
```

Python 端的 `Vec3i` 模型完全匹配这个格式，适配器会自动处理。

## 测试流程

### 1. 启动 Python 后端

```bash
cd python_backend
pip install -r requirements.txt
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

### 2. 测试 API

使用 curl 测试：

```bash
curl -X POST http://localhost:8000/build \
  -H "Content-Type: application/json" \
  -d '{
    "requestText": "建一个简单的房子",
    "playerPos": {"x": 0, "y": 64, "z": 0},
    "facing": "NORTH",
    "dimension": "minecraft:overworld",
    "biome": "minecraft:plains"
  }'
```

### 3. 在 Minecraft 中测试

1. 启动 Minecraft 服务器（或客户端）
2. 打开 FormaCraft UI
3. 输入建筑请求
4. 查看日志确认请求发送和响应接收

## 故障排查

### Python 后端无法启动

- 检查依赖：`pip install -r requirements.txt`
- 检查端口：确保 8000 端口未被占用
- 查看日志：检查 uvicorn 输出

### Java 端无法连接 Python 后端

- 检查 Python 后端是否运行：`curl http://localhost:8000/docs`
- 检查防火墙设置
- 查看 Java 端日志：`FormacraftMod.LOGGER` 会输出连接状态

### JSON 解析错误

- 检查字段名是否匹配（注意大小写）
- 检查 BlockPos 序列化格式
- 查看 Python 端日志：FastAPI 会自动记录请求和错误

### AI 生成失败

- 检查 `OPENAI_API_KEY` 环境变量
- 如果没有 API Key，系统会使用规则基础的回退方案
- 查看 `ai_planner.py` 中的日志输出

## 下一步

1. **配置 OpenAI API Key**：在 `.env` 文件中设置 `OPENAI_API_KEY`
2. **测试完整流程**：从 Minecraft UI 到 Python 后端再到建筑生成
3. **优化 Prompt**：根据实际效果调整 `ai_planner.py` 中的提示词
4. **添加更多建筑类型**：在 `BuildingType` 枚举中添加新类型

