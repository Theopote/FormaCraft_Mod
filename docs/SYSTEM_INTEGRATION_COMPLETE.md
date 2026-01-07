# Formacraft 系统完整集成方案

## 📋 问题诊断

经过系统检查，发现以下新建文件未被完整集成：

### ✅ 已集成的组件
1. **ComponentPlanCompiler** - 已在 `FormaCraftNetworking` 中使用 ✅
2. **ProgramPresetResolver** - 已在 `PromptAssembler` 中使用 ✅
3. **LayoutSiteHudOverlay** - 已在 `ClientInitializer` 中注册 ✅

### ⚠️ 未完整集成的组件
1. **PathClusterLayoutPlanner** - 存在但未在服务端调用
2. **ToolSnapshot** - 存在但未在布局规划中使用
3. **ToolLayoutConstraints** - 存在但未在布局规划中使用
4. **LayoutSitePreviewState** - 已注册但未被触发设置站点
5. **PathZoningPlanner** - 存在但未在构建流程中使用
6. **ZonedSlot** - 存在但未在构建流程中使用

## 🔧 集成方案

### 1. 服务端：PathTool → LayoutSite 生成

**位置**：`src/main/java/com/formacraft/server/build/PathLayoutService.java`（新建）

**功能**：
- 从 `FormaRequest` 中提取 PathTool 数据（通过 `requestText` 中的路径信息）
- 使用 `PathClusterLayoutPlanner` 生成 `LayoutSite` 列表
- 使用 `ToolSnapshot` 和 `ToolLayoutConstraints` 应用约束
- 发送站点预览到客户端

### 2. 客户端：触发站点预览

**位置**：修改 `src/main/java/com/formacraft/common/network/FormaCraftNetworking.java`

**功能**：
- 在收到服务端响应时，如果包含站点信息，设置到 `LayoutSitePreviewState`
- 触发 HUD 预览显示

### 3. PromptAssembler：集成 PathTool 数据

**位置**：修改 `src/main/java/com/formacraft/ai/prompt/PromptAssembler.java`

**功能**：
- 如果检测到 PathTool，在 prompt 中包含站点信息
- 使用 `ProgramPresetResolver` 解析预设信息

### 4. 服务端：ZonedSlot 生成

**位置**：修改 `src/main/java/com/formacraft/server/networking/BuildRequestHandler.java`

**功能**：
- 如果检测到 PathTool，生成 `PathClusterLayout`
- 使用 `PathZoningPlanner` 分配功能
- 使用 `PathClusterLayoutToZonedSlots` 转换为 `ZonedSlot`
- 将 `ZonedSlot` 信息传递给 AI

## 🚀 实施步骤

1. 创建 `PathLayoutService` 服务端服务
2. 修改 `FormaCraftNetworking` 处理站点预览
3. 修改 `BuildRequestHandler` 集成路径布局
4. 确保 `GeneratorRegistry` 包含所有组件生成器
5. 测试完整流程

