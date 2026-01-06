# Skeleton → Patch → Preview → Apply 完整链路

## 现状分析

### ✅ 已实现的部分

1. **SkeletonBuildService** - 将 ExecutableSkeletonPlan 转换为 BlockPatch
2. **PatchPreviewState** - Patch 预览状态管理（完整实现）
3. **PatchPreviewRenderer** - Patch 预览渲染（红/蓝/黄/紫红）
4. **BuildConfirmPanel.showPatchPreview** - Patch 预览确认面板
5. **PatchExecutor** - Patch 执行器（服务端应用）

### ⚠️ 设计差异

**建议中的 PlacementOp**：
- 建议使用 PlacementOp（绝对坐标）作为中间层
- 然后通过 PlacementOpConverter 转换为 BlockPatch（相对坐标）

**实际实现**：
- Generator 直接生成 BlockPatch（相对坐标）
- 更简单直接，不需要中间层

**结论**：当前实现更简洁，直接生成相对坐标的 BlockPatch，符合 Patch 系统的设计。

## 完整调用链

### 1. Skeleton → BlockPatch

```java
// 创建服务
SkeletonBuildService service = new SkeletonBuildService();

// 创建骨架计划
ExecutableSkeletonPlan plan = new ExecutableSkeletonPlan(SkeletonType.RADIAL_SPOKE)
    .put("radius", 15)
    .put("spokes", 8)
    .put("block", "minecraft:red_concrete");

// 生成 BlockPatch（相对 origin）
List<BlockPatch> patches = service.build(world, origin, plan);
```

### 2. BlockPatch → Preview

```java
// 在客户端调用
BlockPos origin = player.getBlockPos();
List<BlockPatch> patches = ...; // 从服务端获取或本地生成

// 显示 Patch 预览
BuildConfirmPanel.INSTANCE.showPatchPreview(origin, patches);
```

**内部流程**：
1. `BuildConfirmPanel.showPatchPreview()` 被调用
2. 应用 `ToolPatchFilter` 过滤（禁区、选区约束等）
3. 调用 `PatchPreviewState.setPreview(origin, accepted, rejected)`
4. `PatchOutlineBuilder` 生成 outline
5. `PatchPreviewRenderer` 渲染预览（红/蓝/黄）
6. `PreviewModalState.lockPatch()` 锁定输入

### 3. Preview → Apply

```java
// 用户点击 Apply 按钮
// BuildConfirmPanel.applyPatch() 被调用
// 内部流程：
// 1. PatchPreviewState.clear() - 清除预览
// 2. FormaCraftNetworking.sendPatchApply(origin, patches, zones) - 发送到服务端
// 3. hide() - 隐藏面板
```

### 4. Apply → Execute

```java
// 服务端接收 PatchApplyPayload
// 调用 PatchExecutor.apply(world, origin, patches)
// 应用所有 BlockPatch 到世界
```

## 完整示例代码

### 服务端：生成 Skeleton 并发送到客户端

```java
// 在服务端
SkeletonBuildService service = new SkeletonBuildService();

// 从 LLM 或工具获取 ExecutableSkeletonPlan
ExecutableSkeletonPlan plan = parseFromLLMOutput(...);

// 生成 BlockPatch
List<BlockPatch> patches = service.build(world, origin, plan);

// 发送到客户端（通过数据包）
sendPatchPreviewToClient(player, origin, patches);
```

### 客户端：接收并显示预览

```java
// 在客户端接收数据包
// ClientPlayNetworking.registerGlobalReceiver(PatchPreviewPayload.ID, (payload, context) -> {
//     BlockPos origin = payload.origin();
//     List<BlockPatch> patches = payload.patches();
//     
//     // 显示预览
//     BuildConfirmPanel.INSTANCE.showPatchPreview(origin, patches);
// });
```

### 用户交互流程

1. **预览显示**：
   - 世界中显示 Patch 线框（红/蓝/黄）
   - HUD 显示 Apply/Undo/Redo/Cancel 按钮
   - 输入被锁定（PreviewModalState.lockPatch()）

2. **用户操作**：
   - 点击 **Apply** → 发送 PatchApplyPayload → 服务端执行
   - 点击 **Cancel** → 清除预览，解锁输入
   - 点击 **Undo** → 发送 PatchUndoPayload → 撤销上次操作
   - 点击 **Redo** → 发送 PatchRedoPayload → 重做上次操作

3. **执行结果**：
   - 服务端应用 Patch
   - 客户端清除预览
   - 世界中的方块被修改

## 数据流图

```
LLM/工具输出
    ↓
ExecutableSkeletonPlan
    ↓
SkeletonBuildService.build()
    ↓
List<BlockPatch> (相对 origin)
    ↓
[可选] ToolPatchFilter.filter() (过滤禁区/选区)
    ↓
PatchPreviewState.setPreview()
    ↓
PatchOutlineBuilder.build() (生成 outline)
    ↓
PatchPreviewRenderer.render() (渲染预览)
    ↓
用户点击 Apply
    ↓
FormaCraftNetworking.sendPatchApply()
    ↓
服务端接收 PatchApplyPayload
    ↓
PatchExecutor.apply() (应用到世界)
```

## 关键组件说明

### SkeletonBuildService

**位置**：`src/main/java/com/formacraft/server/skeleton/gen/SkeletonBuildService.java`

**功能**：
- 将 ExecutableSkeletonPlan 转换为 BlockPatch 列表
- 直接生成相对坐标的 BlockPatch
- 不需要 PlacementOp 中间层

### PatchPreviewState

**位置**：`src/main/java/com/formacraft/client/preview/PatchPreviewState.java`

**功能**：
- 管理 Patch 预览状态
- 存储 origin 和 patches
- 生成 outline（通过 PatchOutlineBuilder）
- 支持 accepted 和 rejected patches

### PatchPreviewRenderer

**位置**：`src/main/java/com/formacraft/client/preview/PatchPreviewRenderer.java`

**功能**：
- 渲染 Patch 预览（世界级 HUD）
- 颜色编码：
  - Place: 蓝色 (0.25, 0.75, 1.00)
  - Replace: 黄色 (1.00, 0.90, 0.25)
  - Remove: 红色 (1.00, 0.25, 0.25)
  - Rejected: 紫红色 (0.90, 0.20, 1.00)

### BuildConfirmPanel

**位置**：`src/main/java/com/formacraft/client/ui/panel/BuildConfirmPanel.java`

**功能**：
- 显示确认面板（HUD）
- 提供 Apply/Undo/Redo/Cancel 按钮
- 处理用户交互
- 发送网络数据包

### PatchExecutor

**位置**：`src/main/java/com/formacraft/common/patch/PatchExecutor.java`

**功能**：
- 在服务端世界应用 BlockPatch
- 将相对坐标转换为绝对坐标
- 执行 place/replace/remove 操作

## 总结

✅ **完整链路已实现**：
- Skeleton → BlockPatch ✅
- BlockPatch → Preview ✅
- Preview → Apply ✅
- Apply → Execute ✅

✅ **设计优势**：
- 直接生成相对坐标，无需中间层
- 与现有 Patch 系统完美集成
- 支持过滤、预览、执行、撤销

✅ **使用方式**：
```java
// 一行代码完成从 Skeleton 到 Preview
BuildConfirmPanel.INSTANCE.showPatchPreview(
    origin, 
    service.build(world, origin, plan)
);
```

完整的闭环已经建立，可以直接使用！

