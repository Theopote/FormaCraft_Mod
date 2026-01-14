# 🔧 框选实时更新修复

## 🐛 问题描述

用户反馈：在构件拾取面板上使用框选工具时，预览框**不能实时跟随鼠标移动**。

### 具体表现

**工具面板的框选（正常）**：
1. 点击第一个点
2. 移动鼠标 → ✅ 选框实时跟随
3. 点击第二个点 → 完成

**构件拾取面板的框选（有问题）**：
1. 点击第一个点 → ✅ 看到第一个方块的选框
2. 移动鼠标 → ❌ 选框不移动
3. 点击第二个点 → 选框才更新

## 🔍 问题诊断

### 根本原因

`ComponentCapturePanel.tick()` 方法**从未被调用**！

**问题链条**：

1. `ComponentCapturePanel` 有 `tick()` 方法，负责更新 `SelectionTool`：
   ```java
   public void tick() {
       if (selectionMode == BOX_SELECT && SelectionTool.INSTANCE.isSelecting()) {
           SelectionTool.INSTANCE.tick(); // 更新框选拖拽
       }
   }
   ```

2. `SelectionTool.tick()` 负责实时更新框选的终点：
   ```java
   public void tick() {
       if (!selecting) return;
       BlockHitResult hit = CursorRaycastHelper.getLastBlockHit();
       if (hit != null) {
           end = hit.getBlockPos(); // 实时跟随鼠标
       }
   }
   ```

3. **但是** `ComponentCapturePanel.tick()` **从未被全局调用**！
   - `ToolManager.tick()` 被调用 ✅
   - 但 `ComponentCapturePanel.tick()` 没有被调用 ❌

4. 结果：
   - 第一次点击：`SelectionTool.selecting = true`，`start` 和 `end` 都设置为第一个点
   - 移动鼠标：`SelectionTool.tick()` 没有被调用，`end` 不更新
   - 第二次点击：`end` 设置为第二个点，框选完成

## ✅ 解决方案

在 `InputEventHandler` 的 `ClientTickEvents.END_CLIENT_TICK` 中添加 `ComponentCapturePanel.tick()` 调用。

### 修改代码

**文件**: `src/main/java/com/formacraft/client/ui/InputEventHandler.java`

```java
ClientTickEvents.END_CLIENT_TICK.register(client -> {
    // ... 其他代码 ...
    
    // Tools tick：实时预览/状态更新
    ToolManager.tick();
    
    // ComponentCapturePanel tick：框选工具实时更新 ✨ 新增
    if (FormaCraftHudOverlay.activePanel == PanelType.COMPONENT_CAPTURE) {
        if (FormaCraftHudOverlay.COMPONENT_CAPTURE_PANEL != null) {
            FormaCraftHudOverlay.COMPONENT_CAPTURE_PANEL.tick();
        }
    }
});
```

### 为什么这样做？

1. **只在面板激活时调用**
   - 检查 `activePanel == COMPONENT_CAPTURE`
   - 避免不必要的 tick 调用

2. **每帧更新**
   - `END_CLIENT_TICK` 每帧调用一次
   - 保证框选预览实时跟随鼠标

3. **与 ToolManager 并行**
   - `ToolManager.tick()` 更新工具面板的工具
   - `ComponentCapturePanel.tick()` 更新构件拾取面板的工具
   - 两者互不干扰

## 🔄 修复后的流程

```
每帧（~60fps）：
┌─────────────────────────────────────┐
│  ClientTickEvents.END_CLIENT_TICK   │
└──────────────┬──────────────────────┘
               │
               ├─► ToolManager.tick()
               │   └─► 更新工具面板的工具
               │
               └─► ComponentCapturePanel.tick() ✨ 新增
                   └─► SelectionTool.INSTANCE.tick()
                       └─► end = getLastBlockHit()  // 实时更新
```

## 📊 修复对比

| 场景 | 修复前 | 修复后 |
|------|--------|--------|
| 点击第一个点 | ✅ 显示选框 | ✅ 显示选框 |
| 移动鼠标 | ❌ 选框不动 | ✅ 选框实时跟随 |
| 点击第二个点 | ✅ 选框跳到终点 | ✅ 选框平滑到终点 |
| 用户体验 | ❌ 不直观 | ✅ 流畅自然 |

## 🧪 测试场景

### 测试 1：构件拾取面板框选

1. 打开构件拾取面板
2. 点击"📦 框选"按钮
3. 在世界中左键点击第一个点
4. **慢慢移动鼠标**
5. **预期结果**：✅ 蓝色选框实时跟随鼠标，8个角点动态更新
6. 点击第二个点完成框选

### 测试 2：工具面板框选（对比）

1. 打开工具面板
2. 选择"选区工具"
3. 在世界中左键点击第一个点
4. 移动鼠标
5. **预期结果**：✅ 蓝色选框实时跟随（与构件拾取面板一致）

## 🔧 技术细节

### Tick 调用时机

```
游戏循环（每帧）：
1. START_CLIENT_TICK    // 帧开始
2. ... 游戏逻辑 ...
3. END_CLIENT_TICK      // 帧结束
   ├─ ToolManager.tick()
   └─ ComponentCapturePanel.tick()  ← 在这里调用
4. 渲染
```

### SelectionTool 的状态

```java
// 第一次点击
selecting = true;
start = BlockPos(10, 64, 20);
end = BlockPos(10, 64, 20);  // 与 start 相同

// 每帧 tick（移动鼠标）
end = BlockPos(11, 64, 20);  // 更新
end = BlockPos(12, 64, 20);  // 更新
end = BlockPos(13, 64, 21);  // 更新
// ... 实时跟随

// 第二次点击
selecting = false;
end = BlockPos(15, 65, 25);  // 最终位置
```

### 为什么只检查 BOX_SELECT 模式？

```java
public void tick() {
    // 框选模式：让 SelectionTool 处理拖拽
    if (selectionMode == ComponentSelectionMode.BOX_SELECT 
        && SelectionTool.INSTANCE.isSelecting()) {
        SelectionTool.INSTANCE.tick();
    }
}
```

**原因**：
- ✅ **BOX_SELECT**：需要实时更新拖拽终点
- ❌ **POINT_SELECT**：点击即选，不需要拖拽更新

## 📝 修改文件

| 文件 | 修改内容 | 行数 |
|------|---------|------|
| `InputEventHandler.java` | 添加 ComponentCapturePanel.tick() 调用 | +7 |

**总计**：1 个文件，7 行新增代码

## ✅ 完成度

| 项目 | 状态 |
|------|------|
| 问题诊断 | ✅ 完成 |
| 代码修改 | ✅ 完成 |
| 编译测试 | ✅ 通过 |
| 文档记录 | ✅ 完成 |

## 🎉 修复效果

修复后，构件拾取面板的框选工具体验**与工具面板完全一致**：

- ✅ **流畅的实时预览**
- ✅ **每帧更新（~60fps）**
- ✅ **自然的拖拽体验**

---

**修复时间**: 2026-01-14  
**编译状态**: ✅ 通过  
**问题修复**: ✅ 框选实时跟随鼠标
