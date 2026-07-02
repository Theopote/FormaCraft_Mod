# Building Outline Preview（建造前预览线框）完成总结

## 完成的工作

### 1. Outline 数据结构

**客户端：**
- `OutlineBlock.java` - 预览线框方块（包含 BlockPos）
- `OutlinePreviewState.java` - 预览状态管理（缓存线框数据）

**服务端：**
- `OutlineGenerator.java` - 从 PlannedBlock 生成 OutlineBlock
- `PreviewStorage.java` - 存储玩家最后一次生成的建筑结构

### 2. 网络数据包

**数据包定义：**
- `PreviewOutlinePacket.java` - 预览线框数据包（S2C）
- `PreviewOutlinePayload` - 在 FormaCraftNetworking 中定义
- `ClearOutlinePayload` - 清除预览数据包（S2C）

**网络通信：**
- 服务端自动发送预览（在生成结构后）
- 客户端接收并更新预览状态
- 支持清除预览

### 3. 客户端渲染器

**OutlineRenderer.java：**
- 使用 `WorldRenderEvents.AFTER_ENTITIES` 注册
- 渲染每个方块的 12 条边线框
- 使用蓝色半透明线框（RGB: 0.2, 0.8, 1.0, Alpha: 0.9）
- 自动适配相机位置

### 4. 命令体系

**FormaCraftCommands.java：**
- `/forma_preview` - 显示预览线框
- `/forma_confirm` - 确认建造（清除预览并开始施工）
- `/forma_cancel` - 取消预览（仅清除预览）

### 5. 自动预览集成

**FormaCraftNetworking.java：**
- 单个建筑：自动生成预览并发送
- 复合结构：自动生成预览并发送
- 玩家收到预览后可以自由移动查看

## 预览系统流程

```
玩家输入内容
    ↓
AI 返回 BuildingSpec / CompositeSpec
    ↓
服务端生成 GeneratedStructure
    ↓
OutlineGenerator 转换为 OutlineBlock 列表
    ↓
发送预览数据包到客户端
    ↓
客户端渲染线框（蓝色半透明）
    ↓
玩家查看预览
    ↓
/forma_confirm → 开始建造
/forma_cancel → 清除预览
```

## 命令使用

### /forma_preview
显示最后一次生成的建筑预览线框。

### /forma_confirm
确认建造：
1. 清除客户端预览
2. 调用 BuildExecutionService 开始施工
3. 分 Tick 执行建造

### /forma_cancel
取消预览：
1. 清除客户端预览
2. 清除服务端存储的结构

## 预览效果

- **颜色**：蓝色半透明线框（RGB: 0.2, 0.8, 1.0）
- **样式**：每个方块显示 12 条边（完整立方体线框）
- **位置**：自动适配相机位置
- **性能**：只在有预览时渲染，不影响正常游戏性能

## 与现有系统集成

### 单个建筑
```
玩家请求 → BuildingSpec → 生成结构 → 自动预览 → 玩家确认 → 开始建造
```

### 复合结构
```
玩家请求 → CompositeSpec → 生成复合结构 → 自动预览 → 玩家确认 → 开始建造
```

### 预览与建造
- **预览**：仅客户端渲染，不占用实际方块
- **建造**：确认后由 BuildExecutionService 执行
- **无冲突**：预览和建造完全分离

## 数据结构

### OutlineBlock
```java
public class OutlineBlock {
    public final BlockPos pos;
}
```

### OutlinePreviewState
```java
public static List<OutlineBlock> blocks = new ArrayList<>();
public static boolean active = false;
```

### PreviewStorage
```java
// 存储玩家最后一次生成的建筑结构
private static final Map<UUID, GeneratedStructure> lastStructures = new HashMap<>();
private static final Map<UUID, Boolean> hasPreview = new HashMap<>();
```

## 渲染实现

### 线框绘制
每个方块绘制 12 条边：
- 底部 4 条边
- 顶部 4 条边
- 4 条垂直边

### 渲染时机
- 使用 `WorldRenderEvents.AFTER_ENTITIES`
- 在实体渲染之后渲染线框
- 确保线框显示在所有实体之上

## 优势

1. **可视化预览** - 玩家可以直观看到建筑轮廓
2. **避免错误** - 建造前确认位置和大小
3. **自由移动** - 预览时玩家可以自由移动查看
4. **无性能影响** - 仅客户端渲染，不影响服务端
5. **完全分离** - 预览和建造完全独立，无冲突

## 未来扩展

### 1. 预览优化
- 只显示外轮廓（减少线框数量）
- 支持不同颜色（根据建筑类型）
- 支持透明度调节

### 2. 交互增强
- 鼠标悬停显示建筑信息
- 支持预览时调整位置
- 支持预览时旋转建筑

### 3. 多玩家支持
- 每个玩家独立的预览状态
- 支持多人同时预览不同建筑

## 总结

Building Outline Preview 是 FormaCraft 体验层的关键功能。现在系统可以：

1. ✅ 自动生成预览线框
2. ✅ 客户端实时渲染
3. ✅ 支持单个和复合结构
4. ✅ 完整的命令体系
5. ✅ 与建造系统完美集成

玩家现在可以在建造前看到完整的建筑轮廓，大大提升了用户体验！

