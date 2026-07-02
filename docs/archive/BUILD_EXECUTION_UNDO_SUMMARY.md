# BuildExecutionService + Undo 系统完成总结

## 完成的工作

### 1. 核心数据结构

#### ✅ PlannedBlock.java
- 生成器输出用
- 只关心"要在某个坐标放什么方块"，不关心原始方块

#### ✅ GeneratedStructure.java
- 代表生成器输出的一整个建筑
- 包含 owner (UUID)、origin、description 和 blocks 列表
- 使用不可变列表确保线程安全

#### ✅ BlockChange.java
- 实际执行时记录 from → to
- 用于 Undo 系统恢复原始方块状态

### 2. 任务管理

#### ✅ BuildTask.java
- 一个正在施工中的任务
- 每个 Tick 执行最多 `maxPerTick` 个方块修改
- 自动跟踪进度和完成状态
- 记录所有应用的 BlockChange

### 3. Undo 系统

#### ✅ UndoEntry.java
- 记录一次建造操作的所有方块变更
- 包含 world、origin、description 和 changes

#### ✅ UndoService.java
- 按玩家维护撤销栈
- 每个玩家最多保留 10 个撤销记录
- 支持撤销最后一次建造操作

### 4. 执行服务

#### ✅ BuildExecutionService.java
- 维护全局的 BuildTask 列表
- 每个世界 Tick 时执行一部分方块（默认 200 个/Tick）
- 任务完成后自动将结果交给 UndoService
- 支持按玩家 UUID 关联建造任务

### 5. 命令系统

#### ✅ FormaCraftCommands.java
- 注册 `/formacraft_undo` 命令
- 玩家可以使用此命令撤销最后一次建造操作

### 6. 网络集成

#### ✅ ConfirmBuildPacket.java
- C2S 数据包：客户端确认建造后发送
- 包含 BuildingSpec 和建造原点

#### ✅ FormaCraftNetworking.java
- 注册确认建造数据包处理器
- 接收客户端确认后，使用玩家 UUID 创建建造任务

#### ✅ BuildPreviewScreen.java
- 更新确认按钮，发送确认建造数据包

### 7. 生成器更新

#### ✅ 所有生成器已更新
- `TowerGenerator` - 使用 PlannedBlock
- `RectangularHouseGenerator` - 使用 PlannedBlock
- `BridgeGenerator` - 使用 PlannedBlock

## 完整执行流水线

```
玩家输入文本
    ↓
Minecraft 客户端发送 FormaRequest
    ↓
服务端接收 → 调用 Python 后端
    ↓
Python 后端返回 BuildingSpec
    ↓
服务端发送 BuildingSpec 到客户端
    ↓
客户端预览 UI 显示
    ↓
玩家确认 → 发送 ConfirmBuildPacket
    ↓
服务端接收确认 → 创建 GeneratedStructure (带玩家 UUID)
    ↓
BuildExecutionService.enqueueBuild()
    ↓
每个 Tick 执行部分方块 (200 个/Tick)
    ↓
任务完成 → 自动创建 UndoEntry
    ↓
玩家可以使用 /formacraft_undo 撤销
```

## 关键特性

### 1. 分 Tick 执行
- 每 Tick 最多执行 200 个方块修改
- 避免服务器卡顿
- 可配置（修改 `BLOCKS_PER_TICK` 常量）

### 2. 自动 Undo 记录
- 建造任务完成后自动创建 UndoEntry
- 按玩家 UUID 关联
- 每个玩家最多保留 10 个撤销记录

### 3. 玩家关联
- 建造任务与玩家 UUID 关联
- 只有关联的玩家可以撤销
- 支持无 owner 的建造任务（系统建造）

### 4. 线程安全
- 使用不可变列表
- 所有操作在主线程执行
- 异步操作通过 `server.execute()` 回调

## 使用示例

### 在生成器中使用

```java
List<PlannedBlock> blocks = new ArrayList<>();
// ... 生成方块 ...
blocks.add(new PlannedBlock(pos, blockState));

String description = "Tower (height=20, radius=6)";
GeneratedStructure structure = new GeneratedStructure(
    player.getUuid(),  // owner
    origin,            // origin
    description,       // description
    blocks             // blocks
);

BuildExecutionService.getInstance().enqueueBuild(world, structure);
```

### 撤销建造

```java
// 玩家使用命令
/formacraft_undo

// 或在代码中
BuildExecutionService.getInstance()
    .getUndoService()
    .undoLast(player);
```

## 配置

### 修改每 Tick 执行的方块数

在 `BuildExecutionService.java` 中修改：

```java
private static final int BLOCKS_PER_TICK = 200; // 改为你想要的数值
```

### 修改每个玩家的撤销记录数

在 `UndoService.java` 中修改：

```java
private static final int MAX_UNDO_PER_PLAYER = 10; // 改为你想要的数值
```

## 测试建议

1. **测试基本建造流程**
   - 发送建筑请求
   - 确认建造
   - 观察方块逐步放置

2. **测试 Undo 功能**
   - 完成一次建造
   - 使用 `/formacraft_undo` 命令
   - 验证方块恢复

3. **测试多玩家**
   - 多个玩家同时建造
   - 验证每个玩家的 Undo 栈独立

4. **测试性能**
   - 建造大型建筑
   - 观察服务器 TPS
   - 调整 `BLOCKS_PER_TICK` 以平衡性能和速度

## 下一步

1. **添加进度显示**
   - 在客户端显示建造进度
   - 使用 ActionBar 或 BossBar

2. **添加取消功能**
   - 允许玩家取消正在进行的建造任务

3. **优化性能**
   - 批量处理方块更新
   - 使用更高效的算法

4. **添加更多命令**
   - `/formacraft_status` - 查看当前建造状态
   - `/formacraft_cancel` - 取消当前建造任务

所有核心功能已完成，系统可以正常运行！

