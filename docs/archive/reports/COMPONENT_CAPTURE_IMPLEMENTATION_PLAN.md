# 🚀 构件拾取面板 v2.0 实现计划

## 📊 总览

基于用户需求和设计文档，将分阶段实现新的构件拾取系统。

## 🎯 Phase 1: 基础 Tooltip 和 UI 优化（1-2 小时）

### 目标
为现有 UI 添加完善的 Tooltip，提升用户体验。

### 任务清单

#### 1.1 为所有按钮添加 Tooltip
- [ ] 基础信息区
  - [ ] 构件分类按钮
  - [ ] 锚点设置按钮
  - [ ] 清除锚点按钮
  - [ ] 朝向按钮
  - [ ] 镜像按钮

- [ ] 语义标注区
  - [ ] Skin 按钮
  - [ ] 标注保存按钮
  - [ ] Style 按钮
  - [ ] Part 按钮

- [ ] Socket 配置区
  - [ ] Context 按钮
  - [ ] 原点设置按钮
  - [ ] 朝向按钮
  - [ ] 添加/预览/清空按钮

- [ ] 智能分析区
  - [ ] 自动分析按钮
  - [ ] 检测 Socket 按钮

- [ ] 底部按钮
  - [ ] 取消按钮
  - [ ] 保存构件按钮

#### 1.2 为输入框添加 Placeholder 和 Tooltip
- [ ] 构件名称输入框
  - Placeholder: "输入构件名称（如：橡木门）"
  - Tooltip: 使用建议
  
- [ ] 标签输入框
  - Placeholder: "输入标签，用逗号分隔（如：wood, modern）"
  - Tooltip: 标签用途说明

- [ ] Socket ID 输入框
  - Placeholder: "输入 Socket ID（如：door_frame）"
  - Tooltip: Socket 系统说明

#### 1.3 添加状态指示器
```java
// 在 drawContents 方法中添加
private void drawStatusBar(DrawContext ctx, int x, int y) {
    // ✓ 选区: 5×3×4 (60 方块)
    // ✓ 锚点: 已设置 (100, 64, 200)
    // ⚠ 朝向: 未配置
}
```

### 实现文件
- `ComponentCapturePanel.java`：添加 Tooltip 和状态栏

### 预期效果
用户鼠标悬停在任何控件上都能看到清晰的说明。

---

## 🔧 Phase 2: 选择工具集成（3-5 小时）

### 目标
在构件拾取面板内集成选择工具，支持框选、点选加/减。

### 任务清单

#### 2.1 创建选择模式枚举
```java
// 新建文件: ComponentSelectionMode.java
public enum ComponentSelectionMode {
    BOX_SELECT("框选", "拖拽框选区域"),
    ADD_SELECT("点选加", "Shift+点击添加方块"),
    REMOVE_SELECT("点选减", "Ctrl+点击移除方块"),
    ANCHOR_SET("设置锚点", "右键点击设置锚点");
}
```

#### 2.2 扩展 ComponentCapturePanel
- [ ] 添加选择模式切换按钮组
  ```java
  private ButtonWidget boxSelectButton;
  private ButtonWidget addSelectButton;
  private ButtonWidget removeSelectButton;
  private ComponentSelectionMode currentMode = BOX_SELECT;
  ```

- [ ] 添加世界交互处理
  ```java
  public boolean handleWorldClick(double mouseX, double mouseY, int button);
  public boolean handleWorldDrag(double mouseX, double mouseY);
  public boolean handleWorldRelease(double mouseX, double mouseY, int button);
  ```

- [ ] 添加选区管理
  ```java
  private Set<BlockPos> selectedBlocks = new HashSet<>();
  private BlockPos boxStart = null;
  private BlockPos boxEnd = null;
  ```

#### 2.3 创建世界渲染覆盖层
```java
// 新建文件: ComponentCaptureWorldRenderer.java
public class ComponentCaptureWorldRenderer {
    // 渲染选区边框
    public static void renderSelection(MatrixStack matrices, 
                                      Set<BlockPos> blocks,
                                      float tickDelta);
    
    // 渲染锚点标记
    public static void renderAnchor(MatrixStack matrices,
                                   BlockPos anchor,
                                   Direction facing,
                                   float tickDelta);
    
    // 渲染临时框选框
    public static void renderBoxPreview(MatrixStack matrices,
                                       BlockPos start,
                                       BlockPos end,
                                       float tickDelta);
}
```

#### 2.4 集成到输入系统
修改 `InputRouter.java`：
- [ ] 检测是否在构件拾取面板
- [ ] 如果是，转发世界点击到面板
- [ ] 处理键盘修饰键（Shift/Ctrl）

#### 2.5 添加快捷键
```java
// 在 ComponentCapturePanel 中
public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
        // 清除选区
    } else if (keyCode == GLFW.GLFW_KEY_A && hasControlDown()) {
        // 全选
    } else if (keyCode == GLFW.GLFW_KEY_D && hasControlDown()) {
        // 取消选择
    }
}
```

### 实现文件
- `src/main/java/com/formacraft/client/ui/panel/ComponentSelectionMode.java`（新建）
- `src/main/java/com/formacraft/client/render/ComponentCaptureWorldRenderer.java`（新建）
- `src/main/java/com/formacraft/client/ui/panel/ComponentCapturePanel.java`（修改）
- `src/main/java/com/formacraft/client/ui/input/InputRouter.java`（修改）

### 预期效果
- 用户在面板中点击"框选"，然后在世界中拖拽框选
- Shift+点击可以添加单个方块
- Ctrl+点击可以移除单个方块
- 右键点击设置锚点
- 实时看到绿色高亮选区

---

## 🎨 Phase 3: 构件语义系统（4-6 小时）

### 目标
实现构件分类、附着模式和方向性配置。

### 任务清单

#### 3.1 创建枚举和数据结构
```java
// 新建文件: AttachmentMode.java
public enum AttachmentMode {
    BOTTOM_FACE("底面", "底部贴合（如地板、柱子）"),
    TOP_FACE("顶面", "顶部贴合（如天花板）"),
    VERTICAL_EDGE("竖边", "竖直边缘（如门窗）"),
    // ... 其他模式
}

// 新建文件: Directionality.java
public enum Directionality {
    NONE("无"),
    INSIDE_OUTSIDE("内外", new String[]{"内侧", "外侧"}),
    UP_DOWN("上下", new String[]{"下端", "上端"}),
    // ... 其他方向
}

// 新建文件: DirectionMarkers.java
public class DirectionMarkers {
    public BlockPos insideMark;
    public BlockPos outsideMark;
    public BlockPos bottomMark;
    public BlockPos topMark;
    // ...
}
```

#### 3.2 扩展 ComponentCategory
```java
// 修改文件: ComponentCategory.java
public enum ComponentCategory {
    DOOR("门", AttachmentMode.VERTICAL_EDGE, Directionality.INSIDE_OUTSIDE),
    WINDOW("窗户", AttachmentMode.VERTICAL_EDGE, Directionality.INSIDE_OUTSIDE),
    STAIR("楼梯", AttachmentMode.BOTTOM_FACE, Directionality.UP_DOWN),
    // ... 更多分类
    
    private final AttachmentMode defaultAttachment;
    private final Directionality directionality;
}
```

#### 3.3 扩展 ComponentDefinition
```java
// 修改文件: ComponentDefinition.java
public class ComponentDefinition {
    // 新增字段
    public AttachmentMode attachmentMode;
    public Directionality directionality;
    public DirectionMarkers markers;
    
    // 计算朝向向量
    public Vec3d computedDirection() {
        // ...
    }
}
```

#### 3.4 在 UI 中添加配置选项
```java
// 在 ComponentCapturePanel 中添加
private ButtonWidget attachmentModeButton;
private ButtonWidget directionalityButton;
private ButtonWidget setInsideButton;
private ButtonWidget setOutsideButton;
private ButtonWidget setBottomButton;
private ButtonWidget setTopButton;

// 添加模式枚举
private enum MarkingMode {
    NONE,
    MARKING_INSIDE,
    MARKING_OUTSIDE,
    MARKING_BOTTOM,
    MARKING_TOP
}
private MarkingMode markingMode = MarkingMode.NONE;
```

#### 3.5 实现方向标记流程
```java
// 点击"设置内侧"按钮
setInsideButton.onPress = () -> {
    markingMode = MarkingMode.MARKING_INSIDE;
    HudToast.show("请在世界中点击内侧的方块");
};

// 在世界点击处理中
if (markingMode == MarkingMode.MARKING_INSIDE) {
    markers.insideMark = clickedPos;
    markingMode = MarkingMode.NONE;
    HudToast.show("内侧已标记: " + clickedPos);
}
```

#### 3.6 渲染朝向箭头
```java
// 在 ComponentCaptureWorldRenderer 中添加
public static void renderDirectionArrow(MatrixStack matrices,
                                       BlockPos from,
                                       BlockPos to,
                                       String label,
                                       int color);
```

### 实现文件
- `src/main/java/com/formacraft/common/component/AttachmentMode.java`（新建）
- `src/main/java/com/formacraft/common/component/Directionality.java`（新建）
- `src/main/java/com/formacraft/common/component/DirectionMarkers.java`（新建）
- `src/main/java/com/formacraft/common/component/ComponentCategory.java`（修改）
- `src/main/java/com/formacraft/common/component/ComponentDefinition.java`（修改）
- `src/main/java/com/formacraft/client/ui/panel/ComponentCapturePanel.java`（修改）
- `src/main/java/com/formacraft/client/render/ComponentCaptureWorldRenderer.java`（修改）

### 预期效果
- 用户选择"门"分类后，UI 自动显示"内外"配置选项
- 点击"设置内侧"，然后在世界中点击，看到蓝色箭头指向外侧
- 缩略图中也显示朝向箭头

---

## 🎬 Phase 4: 视觉增强（2-3 小时）

### 目标
添加丰富的视觉反馈，提升用户体验。

### 任务清单

#### 4.1 选区呼吸动画
```java
// 在 ComponentCaptureWorldRenderer 中
private static float breathingAlpha = 0.3f;
private static boolean breathing = true;

public static void tick() {
    if (breathing) {
        breathingAlpha += 0.01f;
        if (breathingAlpha >= 0.5f) breathing = false;
    } else {
        breathingAlpha -= 0.01f;
        if (breathingAlpha <= 0.3f) breathing = true;
    }
}
```

#### 4.2 锚点标记增强
- [ ] 3D 球体渲染
- [ ] 十字线
- [ ] 文字标签（坐标）
- [ ] 闪烁动画

#### 4.3 朝向箭头优化
- [ ] 3D 箭头模型
- [ ] 箭头末端标签（"内侧"/"外侧"）
- [ ] 渐变色
- [ ] 脉冲动画

#### 4.4 状态栏增强
```java
┌─────────────────────────────────────┐
│ 步骤 1/4: ✓ 选择方块               │
│ 步骤 2/4: ✓ 设置锚点               │
│ 步骤 3/4: ⚠ 配置朝向 ← 当前        │
│ 步骤 4/4: ⚪ 保存构件               │
└─────────────────────────────────────┘
```

#### 4.5 缩略图箭头叠加
- [ ] 在缩略图上绘制小箭头
- [ ] 标注"内"/"外"或"上"/"下"

### 实现文件
- `src/main/java/com/formacraft/client/render/ComponentCaptureWorldRenderer.java`（修改）
- `src/main/java/com/formacraft/client/ui/panel/ComponentCapturePanel.java`（修改）

### 预期效果
- 选区边框有呼吸动画
- 锚点有明显的 3D 标记
- 朝向箭头清晰可见
- 状态栏显示进度

---

## 🤖 Phase 5: 智能辅助（可选，3-4 小时）

### 目标
使用启发式算法辅助用户配置。

### 任务清单

#### 5.1 自动识别构件类型
```java
// 新建文件: ComponentClassifier.java
public class ComponentClassifier {
    public static ComponentCategory classify(Set<BlockPos> blocks) {
        // 分析形状特征
        // - 高宽比
        // - 是否有开口
        // - 方块材质
        // 返回最可能的分类
    }
}
```

#### 5.2 智能推荐锚点
```java
public static BlockPos suggestAnchor(Set<BlockPos> blocks, 
                                    ComponentCategory category) {
    switch (category) {
        case DOOR:
        case WINDOW:
            return findBottomCenter(blocks);
        case COLUMN:
            return findBottomCenter(blocks);
        case DECORATION:
            return findAttachmentPoint(blocks);
    }
}
```

#### 5.3 智能检测内外侧
```java
public static DirectionMarkers autoDetectDirection(Set<BlockPos> blocks,
                                                   BlockPos anchor,
                                                   World world) {
    // 检测周围空气方块分布
    // 推测内侧和外侧
}
```

#### 5.4 自动优化选区
```java
public static Set<BlockPos> optimizeBounds(Set<BlockPos> blocks) {
    // 移除孤立方块
    // 填充小孔洞
    // 对齐到网格
}
```

### 实现文件
- `src/main/java/com/formacraft/client/component/ComponentClassifier.java`（新建）
- `src/main/java/com/formacraft/client/component/ComponentOptimizer.java`（新建）

### 预期效果
- 点击"自动识别"，系统判断这是一个门
- 点击"智能锚点"，系统自动设置最佳锚点
- 点击"智能朝向"，系统自动标记内外侧

---

## 📦 Phase 6: 数据持久化和网络同步（2-3 小时）

### 目标
保存新增的构件语义数据，并同步到服务器。

### 任务清单

#### 6.1 扩展 JSON 序列化
```java
// 在 ComponentDefinition 中
public JsonObject toJson() {
    JsonObject obj = super.toJson();
    obj.addProperty("attachmentMode", attachmentMode.name());
    obj.addProperty("directionality", directionality.name());
    if (markers != null) {
        obj.add("markers", markers.toJson());
    }
    return obj;
}
```

#### 6.2 扩展网络 Payload
```java
// 修改 ComponentSavePayload
public record ComponentSavePayload(
    String componentId,
    String json,
    byte[] thumbnail,
    AttachmentMode attachmentMode,  // 新增
    Directionality directionality,  // 新增
    DirectionMarkers markers         // 新增
) implements CustomPayload {
    // ...
}
```

#### 6.3 服务器端处理
```java
// 在服务器端保存时也存储新字段
ComponentDefinition def = ComponentDefinition.fromJson(json);
def.attachmentMode = attachmentMode;
def.directionality = directionality;
def.markers = markers;
ComponentStorage.save(def);
```

### 实现文件
- `src/main/java/com/formacraft/common/component/ComponentDefinition.java`（修改）
- `src/main/java/com/formacraft/common/component/DirectionMarkers.java`（修改）
- `src/main/java/com/formacraft/common/network/FormaCraftNetworking.java`（修改）
- `src/main/java/com/formacraft/server/component/ComponentStorage.java`（修改）

### 预期效果
- 保存的构件包含完整的语义信息
- 重新加载后信息不丢失
- 客户端和服务器数据同步

---

## 📊 进度跟踪

| Phase | 任务 | 预计时间 | 优先级 | 状态 |
|-------|------|---------|--------|------|
| 1 | 基础 Tooltip | 1-2h | 🔴 高 | ⚪ 待开始 |
| 2 | 选择工具集成 | 3-5h | 🔴 高 | ⚪ 待开始 |
| 3 | 构件语义系统 | 4-6h | 🟡 中 | ⚪ 待开始 |
| 4 | 视觉增强 | 2-3h | 🟡 中 | ⚪ 待开始 |
| 5 | 智能辅助 | 3-4h | 🟢 低 | ⚪ 待开始 |
| 6 | 数据持久化 | 2-3h | 🔴 高 | ⚪ 待开始 |

**总计**: 15-23 小时

---

## 🎯 里程碑

### Milestone 1: 基础可用（Phase 1+2）
- ✅ 完善的 Tooltip
- ✅ 内置选择工具
- ✅ 基本的视觉反馈
- **预计**: 4-7 小时

### Milestone 2: 完整功能（Phase 1+2+3+6）
- ✅ 构件语义配置
- ✅ 方向标记系统
- ✅ 数据持久化
- **预计**: 10-16 小时

### Milestone 3: 最佳体验（All Phases）
- ✅ 智能辅助功能
- ✅ 丰富的视觉效果
- ✅ 完整的用户引导
- **预计**: 15-23 小时

---

## 🚀 快速开始建议

为了快速看到效果，建议按以下顺序实现：

### Week 1: 快速原型
1. **Day 1**: Phase 1（Tooltip） - 1-2h
2. **Day 2-3**: Phase 2（选择工具） - 3-5h
3. **Day 4**: 测试和修复 Bug - 2h

### Week 2: 核心功能
4. **Day 5-6**: Phase 3（语义系统） - 4-6h
5. **Day 7**: Phase 6（持久化） - 2-3h
6. **Day 8**: 测试和文档 - 2h

### Week 3: 完善优化
7. **Day 9**: Phase 4（视觉增强） - 2-3h
8. **Day 10-11**: Phase 5（智能辅助，可选） - 3-4h
9. **Day 12**: 最终测试和发布 - 2h

---

**版本**: v1.0  
**创建时间**: 2026-01-14  
**预计完成时间**: 2-3 周（按每天 2-3 小时计算）
