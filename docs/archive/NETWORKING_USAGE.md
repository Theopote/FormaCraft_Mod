# FormaCraft 网络通信系统使用指南

## 概述

FormaCraft 现在拥有完整的网络通信系统，支持从客户端发送建筑请求到服务端，服务端调用 Python 后端，然后将 AI 生成的建筑规格返回给客户端。

## 完整流程

```
玩家输入文本 (UI)
    ↓
客户端发送 C2S 数据包 (FormaCraftNetworking.sendBuildRequest)
    ↓
服务端接收请求 (FormaCraftNetworking.registerC2S)
    ↓
服务端调用 Python 后端 (OrchestratorClient.requestBuildingSpec)
    ↓
Python 后端生成 BuildingSpec JSON
    ↓
服务端发送 S2C 数据包 (ResponseBuildSpecPayload)
    ↓
客户端接收响应 (FormaCraftNetworking.registerS2C)
    ↓
打开预览 UI (BuildPreviewScreen.show)
    ↓
玩家确认后开始建造
```

## 使用示例

### 1. 客户端发送建筑请求

```java
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.common.network.FormaCraftNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

// 在 UI 或事件处理器中
MinecraftClient client = MinecraftClient.getInstance();
if (client.player != null && client.world != null) {
    BlockPos playerPos = client.player.getBlockPos();
    String facing = client.player.getHorizontalFacing().asString();
    String dimension = client.world.getRegistryKey().getValue().toString();
    String biome = client.world.getBiome(playerPos).getKey().orElse(null).getValue().toString();
    
    FormaRequest request = new FormaRequest(
        "帮我建一个中世纪塔楼，20 格高",  // 请求文本
        playerPos,                        // 玩家位置
        facing,                           // 朝向
        dimension,                        // 维度
        biome,                           // 生物群系
        null,                            // 选择区域最小点（可选）
        null                             // 选择区域最大点（可选）
    );
    
    // 发送请求
    FormaCraftNetworking.sendBuildRequest(request);
}
```

### 2. 服务端处理请求

服务端会自动处理请求（已在 `ServerInitializer` 中注册）：

```java
// 在 ServerInitializer.onInitializeServer() 中已调用
FormaCraftNetworking.registerC2S();
```

服务端会：
1. 接收客户端的 `FormaRequest`
2. 调用 `OrchestratorClient.requestBuildingSpec()` 异步请求 Python 后端
3. 收到响应后发送 `ResponseBuildSpecPayload` 给客户端

### 3. 客户端接收响应

客户端会自动处理响应（已在 `ClientInitializer` 中注册）：

```java
// 在 ClientInitializer.onInitializeClient() 中已调用
FormaCraftNetworking.registerS2C();
```

客户端会：
1. 接收服务端的 `BuildingSpec`
2. 自动打开 `BuildPreviewScreen` 显示建筑预览

### 4. 预览 UI

`BuildPreviewScreen` 会显示：
- 建筑类型（HOUSE, TOWER, BRIDGE 等）
- 建筑风格（MEDIEVAL, MODERN 等）
- 尺寸信息（宽度、深度、高度、半径）
- 材质信息（墙体、屋顶、地板等）
- 功能特性（窗户、楼梯等）
- AI 生成的说明

玩家可以：
- 点击"确认建造"按钮开始建造
- 点击"取消"按钮关闭预览

## 数据结构

### FormaRequest

```java
public class FormaRequest {
    private String requestText;      // 玩家的自然语言请求
    private BlockPos playerPos;      // 玩家位置
    private String facing;            // 朝向 (NORTH, SOUTH, EAST, WEST)
    private String dimension;        // 维度 (minecraft:overworld)
    private String biome;            // 生物群系 (minecraft:plains)
    private BlockPos selectionMin;    // 选择区域最小点（可选）
    private BlockPos selectionMax;    // 选择区域最大点（可选）
    private String sessionId;         // 会话 ID（可选）
    private List<String> chatHistory; // 对话历史（可选）
}
```

### BuildingSpec

参见 `ARCHITECTURE_REFACTOR.md` 中的详细说明。

## 配置

### Python 后端地址

默认地址：`http://localhost:8000`

可以在 `FormaCraftNetworking` 中修改：

```java
private static final OrchestratorClient ORCHESTRATOR = 
    new OrchestratorClient("http://your-backend-url:8000");
```

## 注意事项

1. **异步处理**：所有网络通信都是异步的，不会阻塞游戏主线程
2. **错误处理**：如果 Python 后端不可用或返回错误，会在日志中记录，不会崩溃游戏
3. **预览确认**：默认情况下，建造需要玩家在预览 UI 中确认后才开始执行
4. **向后兼容**：旧的 `ModPacket` 系统仍然保留，不会影响现有功能

## 调试

查看日志以了解网络通信状态：

- 客户端发送请求：`FormaCraftNetworking.sendBuildRequest()`
- 服务端接收请求：`FormaCraftNetworking.registerC2S()` 中的日志
- Python 后端请求：`OrchestratorClient.requestBuildingSpec()` 中的日志
- 客户端接收响应：`FormaCraftNetworking.registerS2C()` 中的日志

所有日志都使用 `FormacraftMod.LOGGER` 输出。

