# 🐛 缩略图调试指南

## 问题：缩略图看不到

已添加详细的调试日志，帮助定位问题。

## 📋 测试步骤

### 1. 测试构件拾取面板缩略图

1. **启动游戏**
   - 启动 Minecraft
   - 加载世界
   - 按 `G` 打开 FormaCraft UI

2. **切换到构件拾取面板**
   - 点击 🎯 构件拾取标签

3. **使用选区工具**
   - 在工具面板选择"选区工具"
   - 框选一些方块（例如 5x5x5 的区域）

4. **观察控制台输出**
   
   **成功的日志应该是**：
   ```
   ✓ 缩略图生成成功: 64x64
   [ImageRenderer] 渲染: 64x64 -> XXxXX at (X,Y)
   ```

   **如果失败，可能的错误**：
   - `✗ buildCurrentComponentJson 返回 null` - 选区工具没有正确工作
   - `✗ 无法解析 ComponentDefinition` - JSON 解析失败
   - `✗ 缩略图生成失败: generateThumbnail 返回 null` - 生成器返回空
   - `✗ 生成缩略图时出错: XXX` - 异常信息

5. **检查面板显示**
   - 面板右上方应该有一个 64x64 的框
   - 应该显示等轴测视图的方块预览
   - 如果显示 "生成中..."，等待几秒
   - 如果一直显示 "生成中..."，说明生成失败（查看控制台）

### 2. 测试构件库面板缩略图

1. **保存一个构件**
   - 在构件拾取面板中
   - 填写构件名称（例如 "test_component"）
   - 点击"保存构件"按钮
   - 应该自动跳转到构件库面板

2. **观察控制台输出（服务器端）**
   
   服务器控制台应该显示：
   ```
   正在保存构件...
   已保存: config/formacraft/components/test_component.png
   ```

3. **检查文件是否存在**
   
   导航到配置目录：
   - Windows: `%appdata%\.minecraft\config\formacraft\components\`
   - Linux/Mac: `~/.minecraft/config/formacraft/components/`
   
   应该看到：
   - `test_component.json` - 构件定义文件
   - `test_component.png` - 缩略图文件
   
   用图片查看器打开 PNG 文件，应该能看到等轴测视图的方块预览。

4. **观察客户端控制台输出**
   
   **成功的日志应该是**：
   ```
   [ComponentThumbnailCache] ✓ 加载成功: test_component (24x24)
   ```
   
   **如果失败，可能的错误**：
   - `[ComponentThumbnailCache] componentId 为空` - ID 传递错误
   - `[ComponentThumbnailCache] PNG 文件不存在: XXX` - PNG 没有保存或路径错误
   - `[ComponentThumbnailCache] ImageIO.read 返回 null` - PNG 文件损坏
   - `[ComponentThumbnailCache] downscale 返回 null` - 缩放失败

5. **检查构件库面板**
   - 应该在网格中看到构件卡片
   - 卡片应该有 42x42 的缩略图
   - 如果是灰色方框，说明 PNG 没有加载成功

## 🔍 常见问题排查

### 问题 1：构件拾取面板显示"生成中..."不消失

**可能原因**：
1. `SelectionTool.INSTANCE.getMin()` 或 `getMax()` 返回 null
2. `ComponentTool.buildCurrentComponentJson()` 返回 null
3. `ComponentThumbnailGenerator.generateThumbnail()` 抛出异常

**排查方法**：
```
查看控制台的错误日志，根据错误信息定位问题
```

### 问题 2：构件库面板显示灰色方框

**可能原因**：
1. PNG 文件没有保存到磁盘
2. PNG 文件路径不正确
3. PNG 文件损坏

**排查方法**：
1. 检查服务器控制台是否有保存错误
2. 检查文件系统中 PNG 是否存在
3. 尝试手动打开 PNG 文件
4. 检查客户端控制台的 `[ComponentThumbnailCache]` 日志

### 问题 3：PNG 文件存在但不显示

**可能原因**：
1. 构件 ID 不匹配
2. PNG 文件格式错误
3. `ComponentThumbnailCache.getThumb()` 没有被调用

**排查方法**：
1. 检查 `catalog.json` 中的构件 ID
2. 检查 PNG 文件名是否与 ID 匹配
3. 查看客户端控制台是否有 `[ComponentThumbnailCache]` 日志

### 问题 4：ImageRenderer 没有渲染

**可能原因**：
1. `BufferedImage` 为 null
2. 区域尺寸无效
3. 图像尺寸无效

**排查方法**：
查看控制台的 `[ImageRenderer]` 日志，应该显示渲染参数。

## 🛠️ 调试技巧

### 1. 启用详细日志

所有关键组件已添加日志输出：
- `[ComponentThumbnailCache]` - 缩略图缓存加载
- `[ImageRenderer]` - 图像渲染
- `✓/✗` - 构件拾取面板缩略图生成

### 2. 检查文件系统

手动检查以下路径：
```
config/formacraft/components/
├── catalog.json
├── <component-id>.json
└── <component-id>.png
```

### 3. 测试最小案例

创建一个最简单的测试案例：
1. 只选择 1 个方块（例如石头）
2. 保存为 "test1"
3. 检查是否生成 PNG
4. 检查是否在构件库中显示

### 4. 对比正常和异常情况

如果部分构件显示，部分不显示：
1. 对比 JSON 文件差异
2. 对比 PNG 文件大小
3. 对比控制台日志

## 📊 预期的完整日志流程

### 构件拾取面板

```
用户框选方块
↓
[regenerateThumbnail] 开始异步生成
↓
✓ 缩略图生成成功: 64x64
↓
[drawThumbnailPreview] 调用 ImageRenderer
↓
[ImageRenderer] 渲染: 64x64 -> XXxXX at (X,Y)
↓
屏幕显示缩略图
```

### 构件库面板

```
用户保存构件
↓
[服务器] 保存 JSON 和 PNG
↓
[客户端] 收到 catalog 更新
↓
[构件库面板] 渲染网格
↓
[ComponentThumbnailCache] PNG 文件不存在: XXX (首次)
  或
[ComponentThumbnailCache] ✓ 加载成功: test_component (24x24)
↓
屏幕显示网格缩略图
```

## 🎯 下一步

如果调试日志显示：
1. **生成成功但不显示** → 检查 `ImageRenderer` 是否被调用
2. **PNG 文件不存在** → 检查服务器保存逻辑
3. **ImageIO.read 返回 null** → 检查 PNG 文件格式
4. **没有任何日志** → 检查代码是否正确编译

---

**创建时间**: 2026-01-14  
**版本**: v1.0
