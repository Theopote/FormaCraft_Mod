# 🔄 构件工具迁移计划

## 目标

将 ToolPanel 中的所有构件工具功能迁移到 ComponentCapturePanel，实现完全独立的构件拾取工作流。

## 需要迁移的功能清单

### ✅ 已在 ComponentCapturePanel 中的功能

1. 基础信息
   - 名称输入
   - 分类选择
   - 标签输入

2. 锚点与朝向
   - 锚点选择
   - 朝向设置
   - 镜像模式

3. 保存功能
   - 保存到构件库
   - 自动跳转

### 🔄 需要从 ToolPanel 迁移的功能

#### 1. 构件来源切换
```java
// ToolPanel 中的功能
componentSourceButton // 切换：当前选区 / 构件库
componentLibraryPickButton // 打开构件库
componentLibraryLoadButton // 加载选中的构件
```

**迁移策略：**
- ComponentCapturePanel 专注于"拾取"（从选区创建构件）
- "使用构件"功能保留在 ToolPanel 或移到独立的"构件使用"面板

#### 2. 语义标注功能
```java
componentSkinModeButton // 材质：语义 / 原样
componentSemanticTagOnSaveButton // 存语义：开 / 关
componentSemanticStyleButton // 风格选择
componentSemanticPartButton // 语义部位选择
```

**迁移策略：**
- 全部迁移到 ComponentCapturePanel
- 作为"高级选项"折叠区域

#### 3. Socket 配置
```java
componentSocketTypeButton // SocketContext 选择
componentSocketIdInput // Socket ID 输入
componentSocketPickOriginButton // 选择 Socket 原点
componentSocketFacingButton // Socket 朝向
componentSocketAddButton // 添加 Socket
componentSocketPreviewButton // 预览 Socket
componentSocketClearButton // 清空 Sockets
```

**迁移策略：**
- 全部迁移到 ComponentCapturePanel
- 优化 UI 布局，使其更清晰

#### 4. 预览与放置
```java
componentPreviewButton // 预览放置
componentApplyButton // 放置构件（Patch）
```

**迁移策略：**
- 这些是"使用构件"的功能，不是"拾取构件"
- **不迁移**，保留在 ToolPanel 或移到独立面板

## 新的面板职责划分

### ComponentCapturePanel（构件拾取）
**职责：** 从世界中拾取构件并保存到库

**功能：**
- ✅ 选区预览
- ✅ 基础信息配置
- ✅ 锚点与朝向设置
- ✅ 语义标注配置
- ✅ Socket 配置
- ✅ 智能分析
- ✅ 保存到构件库

### ToolPanel（工具面板）
**职责：** 建造工具和构件使用

**保留功能：**
- 选区工具
- 语义标注工具
- 构件使用（加载、预览、放置）

**删除功能：**
- 构件拾取相关的所有配置
- 构件保存按钮

### ComponentLibraryPanel（构件库）
**职责：** 浏览和选择构件

**功能：**
- 缩略图浏览
- 搜索和过滤
- 排序
- 选择构件

## 实施步骤

### Step 1: 完善 ComponentCapturePanel ✅

添加所有缺失的功能：
- [x] 语义标注选项
- [x] Socket 完整配置
- [x] Socket 预览
- [x] 智能分析（占位）

### Step 2: 从 ToolPanel 删除构件拾取功能

删除以下内容：
- [ ] 构件相关的输入框
- [ ] 构件相关的按钮
- [ ] 构件配置相关的渲染代码
- [ ] 构件相关的鼠标点击处理

保留以下内容：
- [ ] 构件使用功能（加载、预览、放置）
- [ ] 选区工具
- [ ] 语义标注工具

### Step 3: 实现面板联动

- [ ] 从 ComponentCapturePanel 保存后自动跳转到 ComponentLibraryPanel
- [ ] 在 ComponentLibraryPanel 中高亮显示新保存的构件
- [ ] 从 ComponentLibraryPanel 选择构件后，可以在 ToolPanel 中使用

### Step 4: 优化用户体验

- [ ] 添加快捷切换按钮
- [ ] 优化工作流提示
- [ ] 添加教程提示

## 预期效果

### 改进前
```
ToolPanel（混乱）
├─ 选区工具
├─ 语义标注工具
├─ 构件拾取配置（占据大量空间）
│   ├─ 名称、标签、分类
│   ├─ 锚点、朝向、镜像
│   ├─ 语义标注选项
│   ├─ Socket 配置
│   └─ 保存按钮
└─ 构件使用（加载、预览、放置）
```

### 改进后
```
ToolPanel（简洁）
├─ 选区工具
├─ 语义标注工具
└─ 构件使用（加载、预览、放置）

ComponentCapturePanel（专注）
├─ 选区预览
├─ 基础信息
├─ 锚点与朝向
├─ 语义标注选项
├─ Socket 配置
├─ 智能分析
└─ 保存到构件库

ComponentLibraryPanel（浏览）
└─ 构件列表（缩略图、搜索、排序）
```

## 代码变更清单

### 新增文件
- [x] `ComponentCapturePanel.java` - 完整的构件拾取面板

### 修改文件
- [x] `PanelType.java` - 添加 COMPONENT_CAPTURE
- [x] `FormaCraftHudOverlay.java` - 注册新面板
- [x] `InputRouter.java` - 添加路由
- [x] `TabBar.java` - 添加标签
- [ ] `ToolPanel.java` - 删除构件拾取相关代码

### 文档
- [x] `COMPONENT_CAPTURE_PANEL_DESIGN.md` - 设计文档
- [x] `COMPONENT_CAPTURE_MIGRATION_PLAN.md` - 迁移计划（本文档）

---

**文档版本：** v1.0  
**创建时间：** 2026-01  
**状态：** 进行中
