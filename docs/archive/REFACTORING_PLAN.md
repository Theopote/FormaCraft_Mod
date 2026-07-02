# FormaCraft 输入系统重构方案

## 问题分析

当前实现有 4 个 Mixin 层，逻辑复杂且容易冲突：
1. **MouseMixin** - 处理鼠标事件（点击、滚轮、移动）
2. **KeyboardMixin** - 处理键盘事件（按键、字符输入）
3. **KeyboardInputMixin** - 拦截玩家移动输入（WASD）
4. **MinecraftClientMixin** - 最后一道防线（handleInputEvents）

每个 Mixin 都有大量反射代码和日志，导致：
- 代码难以维护
- 逻辑混乱，容易出现冲突
- 性能开销（反射 + 日志）

## 参考方案：treefactory

treefactory 使用 **Screen** 而不是 HUD overlay，逻辑更简单：
- 只在 `client.currentScreen instanceof TreeFactoryScreen` 时处理
- 检查 `screen.isMouseOverTreeFactoryGui(x, y)` 判断鼠标位置
- 鼠标在 UI 上 → 拦截；不在 → 允许游戏处理

## 简化方案（保持 HUD Overlay）

### 核心原则
1. **单一职责**：每个 Mixin 只负责一个明确的职责
2. **统一判断**：所有 Mixin 都使用 `InputRouter.isMouseInsidePanel()` 判断
3. **减少反射**：只在必要时使用反射，优先使用 Mixin Shadow
4. **移除冗余**：如果 KeyboardMixin 已经拦截了按键，KeyboardInputMixin 可能不需要

### 建议的架构

```
用户输入
  ↓
MouseMixin (简化)
  ├─ 更新鼠标位置到 InputRouter
  ├─ 面板内 → cancel，转发给 UI
  └─ 面板外 → 允许游戏处理（中键按住才移动视角）
  ↓
KeyboardMixin (简化)
  ├─ 更新鼠标位置到 InputRouter
  ├─ 面板内 → cancel，转发给 UI
  └─ 面板外 → 允许游戏处理
  ↓
MinecraftClientMixin (保留，简化)
  └─ 面板内 → cancel handleInputEvents（最后一道防线）
  ↓
InputRouter (核心判断逻辑)
  └─ isMouseInsidePanel() - 统一判断入口
```

### 具体改进

1. **移除 KeyboardInputMixin**
   - 如果 KeyboardMixin 已经拦截了 WASD 按键，KeyboardInputMixin 拦截 KeyboardInput.tick 是冗余的
   - 测试：如果移除后玩家在面板内仍能移动，再考虑保留

2. **简化 MouseMixin 和 KeyboardMixin**
   - 减少反射代码，优先使用 Mixin Shadow
   - 移除详细日志（只在关键点保留）
   - 统一使用 InputRouter 判断

3. **简化 MinecraftClientMixin**
   - 只作为最后一道防线，逻辑保持简单
   - 面板内 → cancel；面板外 → 不处理

4. **优化 InputRouter**
   - 确保 `isMouseInsidePanel()` 判断准确
   - 减少不必要的日志

## 实施步骤

1. 先测试移除 KeyboardInputMixin，看是否仍有问题
2. 简化 MouseMixin 和 KeyboardMixin 的反射代码
3. 统一日志输出，只在关键点记录
4. 测试各种场景，确保逻辑正确

