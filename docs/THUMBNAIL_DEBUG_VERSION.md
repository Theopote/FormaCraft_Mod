# 🐛 缩略图调试版本说明

## 📦 当前版本

这是一个包含详细调试日志的版本，用于诊断缩略图无法显示的问题。

## ✅ 已添加的调试功能

### 1. ComponentCapturePanel - 构件拾取面板缩略图

**位置**: `ComponentCapturePanel.java:557-586`

**添加的日志**：
```java
✓ 缩略图生成成功: 64x64              // 生成成功
✗ buildCurrentComponentJson 返回 null  // 获取 JSON 失败
✗ 无法解析 ComponentDefinition         // JSON 解析失败
✗ 缩略图生成失败: generateThumbnail 返回 null  // 生成器返回空
✗ 生成缩略图时出错: XXX               // 异常详情
```

### 2. ComponentThumbnailCache - 缩略图缓存

**位置**: `ComponentThumbnailCache.java:30-57`

**添加的日志**：
```java
[ComponentThumbnailCache] componentId 为空
[ComponentThumbnailCache] PNG 文件不存在: XXX
[ComponentThumbnailCache] 从缓存加载: test_component
[ComponentThumbnailCache] ImageIO.read 返回 null: XXX
[ComponentThumbnailCache] downscale 返回 null
[ComponentThumbnailCache] ✓ 加载成功: test_component (24x24)
[ComponentThumbnailCache] 读取失败: XXX
```

### 3. ImageRenderer - 图像渲染器

**位置**: `ImageRenderer.java:75-117`

**添加的日志**：
```java
[ImageRenderer] image 为 null
[ImageRenderer] 区域尺寸无效: WxH
[ImageRenderer] 图像尺寸无效: WxH
[ImageRenderer] 渲染: 64x64 -> XXxXX at (X,Y)
[ImageRenderer] renderScaled 完成: 渲染了 XXX 个像素
```

## 🎯 如何使用调试版本

### 步骤 1：启动游戏并查看控制台

1. 启动 Minecraft
2. **保持控制台窗口可见**（不要最小化）
3. 加载世界
4. 按 `G` 打开 FormaCraft UI

### 步骤 2：测试构件拾取面板

1. 切换到 **🎯 构件拾取** 面板
2. 激活选区工具
3. 框选一些方块
4. **立即查看控制台**

**如果成功，应该看到**：
```
✓ 缩略图生成成功: 64x64
[ImageRenderer] 渲染: 64x64 -> XXxXX at (X,Y)
[ImageRenderer] renderScaled 完成: 渲染了 XXX 个像素
```

**如果失败，会看到具体错误**，例如：
```
✗ buildCurrentComponentJson 返回 null
```

### 步骤 3：测试构件库面板

1. 在构件拾取面板填写名称
2. 点击 **保存构件** 按钮
3. 自动跳转到构件库面板
4. **立即查看控制台**

**如果成功，应该看到**：
```
[ComponentThumbnailCache] ✓ 加载成功: test_component (24x24)
```

**如果失败，会看到**：
```
[ComponentThumbnailCache] PNG 文件不存在: XXX
```

## 🔍 常见错误和解决方案

### 错误 1: buildCurrentComponentJson 返回 null

**原因**: 选区工具没有正确工作或没有选中方块

**解决方案**:
1. 确保使用了选区工具
2. 确保框选了至少 1 个非空气方块
3. 检查 `SelectionTool.INSTANCE.hasSelection()` 是否为 true

### 错误 2: PNG 文件不存在

**原因**: 服务器端保存失败或路径错误

**解决方案**:
1. 检查服务器控制台是否有保存错误
2. 手动检查文件路径：
   - Windows: `%appdata%\.minecraft\config\formacraft\components\`
   - Linux/Mac: `~/.minecraft/config/formacraft/components/`
3. 检查文件权限

### 错误 3: ImageIO.read 返回 null

**原因**: PNG 文件损坏或格式错误

**解决方案**:
1. 尝试用图片查看器打开 PNG 文件
2. 如果无法打开，删除 PNG 并重新保存构件
3. 检查 PNG 文件大小（不应该是 0 字节）

### 错误 4: renderScaled 完成但像素数为 0

**原因**: 图像全部是透明的

**解决方案**:
1. 检查 `ComponentThumbnailGenerator` 是否正确获取方块颜色
2. 检查选区中是否有非空气方块
3. 检查方块颜色是否全部透明

## 📊 完整的成功日志示例

```
[用户框选方块]

✓ 缩略图生成成功: 64x64
[ImageRenderer] 渲染: 64x64 -> 48x48 at (264,18)
[ImageRenderer] renderScaled 完成: 渲染了 1234 个像素

[用户保存构件]

[服务器] 保存构件: test_component
[服务器] PNG 保存成功: config/formacraft/components/test_component.png

[客户端收到 catalog 更新]

[ComponentThumbnailCache] ✓ 加载成功: test_component (24x24)
```

## 🚀 下一步

根据控制台输出：

### 如果看到成功日志但屏幕上没有显示
- 可能是渲染层级问题
- 检查 `DrawContext` 是否正确
- 检查坐标是否在屏幕内

### 如果完全没有日志
- 代码可能没有正确编译
- 重新运行 `gradlew build`
- 检查 mod 是否正确加载

### 如果有错误日志
- 根据错误信息定位具体问题
- 参考上面的"常见错误和解决方案"
- 如果无法解决，将完整的错误日志提供给开发者

## 📝 报告问题时请提供

1. **完整的控制台日志**（从启动到出错）
2. **具体的操作步骤**（如何重现问题）
3. **游戏版本信息**（Minecraft 版本、Fabric 版本、FormaCraft 版本）
4. **截图**（如果适用）
5. **文件系统状态**（是否存在 PNG 文件、文件大小等）

---

**版本**: v1.0 (调试版)  
**创建时间**: 2026-01-14  
**目的**: 诊断缩略图显示问题
