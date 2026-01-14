package com.formacraft.client.ui.panel;

import com.formacraft.client.component.ComponentThumbnailGenerator;
import com.formacraft.client.tool.ComponentTool;
import com.formacraft.client.tool.SelectionTool;
import com.formacraft.client.ui.FormaCraftHudOverlay;
import com.formacraft.client.ui.toast.HudToast;
import com.formacraft.client.ui.widget.HudTextInput;
import com.formacraft.common.component.ComponentCategory;
import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.network.FormaCraftNetworking;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.MouseInput;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

/**
 * 构件拾取面板（独立面板）
 * <p>
 * 职责：
 * - 显示当前选区预览
 * - 配置构件的所有参数（基础信息、锚点朝向、语义标注、Socket）
 * - 智能分析构件特征
 * - 保存构件到库
 * <p>
 * 工作流：
 * 1. 用户在世界中用 SelectionTool 框选
 * 2. 切换到此面板
 * 3. 配置参数（或点击智能分析）
 * 4. 点击"保存到构件库"
 * 5. 自动跳转到构件库面板并高亮新构件
 */
public class ComponentCapturePanel extends BasePanel {
    private static final int CONTENT_PADDING = 10;
    private static final int LABEL_OFFSET = 18;
    private static final int BUTTON_HEIGHT = 16;
    private static final int THUMBNAIL_SIZE = 120;
    private static final int FIELD_SPACING = 28;

    // 输入框
    private final HudTextInput nameInput = new HudTextInput();
    private final HudTextInput tagsInput = new HudTextInput();
    private final HudTextInput socketIdInput = new HudTextInput();

    // 基础信息按钮
    private ButtonWidget categoryButton;
    private ButtonWidget pickAnchorButton;
    private ButtonWidget clearAnchorButton;
    private ButtonWidget facingButton;
    private ButtonWidget mirrorButton;

    // 语义标注按钮
    private ButtonWidget semanticSkinButton;
    private ButtonWidget semanticTagOnSaveButton;
    private ButtonWidget semanticStyleButton;
    private ButtonWidget semanticPartButton;

    // Socket 配置按钮
    private ButtonWidget socketContextButton;
    private ButtonWidget socketPickOriginButton;
    private ButtonWidget socketFacingButton;
    private ButtonWidget socketAddButton;
    private ButtonWidget socketPreviewButton;
    private ButtonWidget socketClearButton;

    // 智能分析按钮
    private ButtonWidget autoAnalyzeButton;
    private ButtonWidget autoDetectSocketsButton;

    // 底部按钮
    private ButtonWidget cancelButton;
    private ButtonWidget saveButton;
    
    // 选择工具按钮
    private ButtonWidget boxSelectButton;
    private ButtonWidget pointSelectButton;
    private ButtonWidget clearSelectionButton;

    // 输入框边界（用于点击检测）
    private int nameInputX, nameInputY, nameInputW, nameInputH;
    private int tagsInputX, tagsInputY, tagsInputW, tagsInputH;
    private int socketIdInputX, socketIdInputY, socketIdInputW, socketIdInputH;
    private boolean nameInputBoundsValid = false;
    private boolean tagsInputBoundsValid = false;
    private boolean socketIdInputBoundsValid = false;

    // 缩略图缓存
    private BufferedImage cachedThumbnail = null;
    private BlockPos lastSelectionMin = null;
    private BlockPos lastSelectionMax = null;
    private volatile boolean isGeneratingThumbnail = false;

    // 滚动
    private int scrollY = 0;
    private int maxScrollY = 0;
    
    // 选择工具状态
    private ComponentSelectionMode selectionMode = ComponentSelectionMode.BOX_SELECT;
    private java.util.Set<BlockPos> selectedBlocks = new java.util.HashSet<>();
    private BlockPos boxStart = null;
    private BlockPos boxEnd = null;
    private boolean isDragging = false;

    public ComponentCapturePanel() {
        nameInput.setMaxLength(64);
        nameInput.setPlaceholder("输入构件名称（如：橡木门）");
        
        tagsInput.setMaxLength(256);
        tagsInput.setPlaceholder("输入标签，用逗号分隔（如：wood, modern）");
        
        socketIdInput.setMaxLength(64);
        socketIdInput.setPlaceholder("输入 Socket ID（如：door_frame）");
    }

    private void ensureWidgets() {
        if (categoryButton != null) return;

        var st = ComponentTool.INSTANCE.getState();

        // 基础信息按钮
        categoryButton = ButtonWidget.builder(Text.literal("分类：GENERIC"), b -> cycleCategory())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        构件分类
                        ━━━━━━━━━━━━
                        点击循环切换构件的类型分类
                        
                        分类用于：
                        • AI 智能放置时的语义识别
                        • 构件库的分类浏览
                        • 自动检测附着模式
                        
                        常见分类：门、窗、柱子、装饰等""")))
                .build();

        pickAnchorButton = ButtonWidget.builder(Text.literal("📍 点击选择锚点"), b -> ComponentTool.INSTANCE.startPickAnchor())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        设置锚点（原点）
                        ━━━━━━━━━━━━
                        锚点是构件的参考原点
                        
                        使用方法：
                        1. 点击此按钮
                        2. 在世界中右键点击方块
                        3. 该方块将成为锚点
                        
                        建议位置：
                        • 门窗：底部中心
                        • 柱子：底部中心
                        • 装饰：附着点
                        • 家具：前侧底部中心""")))
                .build();

        clearAnchorButton = ButtonWidget.builder(Text.literal("清除锚点"), b -> ComponentTool.INSTANCE.clearAnchor())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        清除锚点
                        ━━━━━━━━━━━━
                        移除当前设置的锚点
                        
                        清除后需要重新设置锚点
                        才能保存构件""")))
                .build();

        facingButton = ButtonWidget.builder(Text.literal("朝向：SOUTH"), b -> cycleFacing())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        构件朝向
                        ━━━━━━━━━━━━
                        定义构件的"正面"朝向
                        
                        用途：
                        • AI 放置时自动旋转
                        • 确保门窗朝向正确
                        • 对称构件的镜像参考
                        
                        点击循环切换：北/东/南/西""")))
                .build();

        mirrorButton = ButtonWidget.builder(Text.literal("镜像：NONE"), b -> cycleMirror())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        镜像模式
                        ━━━━━━━━━━━━
                        控制构件的镜像行为
                        
                        选项：
                        • NONE：不镜像
                        • LEFT_RIGHT：左右镜像
                        • FRONT_BACK：前后镜像
                        
                        用于创建对称变体""")))
                .build();

        // 语义标注按钮
        semanticSkinButton = ButtonWidget.builder(Text.literal("材质：原样"), b -> ComponentTool.INSTANCE.toggleSemanticSkin())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        材质模式（高级）
                        ━━━━━━━━━━━━
                        控制方块材质的处理方式
                        
                        原样方块：
                        • 保留精确的方块类型
                        • 适合特定设计的构件
                        
                        语义换皮：
                        • AI 可根据风格替换材质
                        • 适合可变风格的构件""")))
                .build();

        semanticTagOnSaveButton = ButtonWidget.builder(Text.literal("存语义：开"), b -> st.semanticTagOnSave = !st.semanticTagOnSave)
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        保存语义标注（高级）
                        ━━━━━━━━━━━━
                        保存时为每个方块添加语义信息
                        
                        开启后：
                        • 自动标注方块的功能部位
                        • AI 可更智能地调整构件
                        • 支持风格驱动的材质替换
                        
                        推荐：通用构件开启""")))
                .build();

        semanticStyleButton = ButtonWidget.builder(Text.literal("风格：DEFAULT"), b -> ComponentTool.INSTANCE.cycleSemanticStyle())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        建筑风格（高级）
                        ━━━━━━━━━━━━
                        定义构件的建筑风格
                        
                        风格类型：
                        • DEFAULT：默认/混合
                        • MEDIEVAL：中世纪
                        • MODERN：现代
                        • ASIAN：亚洲风格
                        
                        用于 AI 风格匹配""")))
                .build();

        semanticPartButton = ButtonWidget.builder(Text.literal("语义：AUTO"), b -> ComponentTool.INSTANCE.cycleSemanticPart())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        语义部位（高级）
                        ━━━━━━━━━━━━
                        标注构件的功能部位
                        
                        AUTO：自动检测
                        手动选项：
                        • FRAME：框架/边框
                        • FILL：填充/主体
                        • DETAIL：装饰细节
                        
                        用于智能材质替换""")))
                .build();

        // Socket 配置按钮
        socketContextButton = ButtonWidget.builder(Text.literal("Context: WALL"), b -> ComponentTool.INSTANCE.cycleSocketType())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        Socket 上下文（高级）
                        ━━━━━━━━━━━━
                        定义 Socket 的附着类型
                        
                        类型：
                        • WALL：墙面（门窗）
                        • FLOOR：地板（家具）
                        • CEILING：天花板（吊灯）
                        • EDGE：边缘（栏杆）
                        
                        AI 将根据上下文智能放置""")))
                .build();

        socketPickOriginButton = ButtonWidget.builder(Text.literal("点选原点"), b -> ComponentTool.INSTANCE.startPickSocketOrigin())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        设置 Socket 原点（高级）
                        ━━━━━━━━━━━━
                        Socket 是构件的嵌入位置
                        
                        使用方法：
                        1. 点击此按钮
                        2. 在世界中右键点击
                        3. 设置 Socket 的参考点
                        
                        例如：门框的底部中心""")))
                .build();

        socketFacingButton = ButtonWidget.builder(Text.literal("朝向: SOUTH"), b -> ComponentTool.INSTANCE.cycleSocketFacing())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        Socket 朝向（高级）
                        ━━━━━━━━━━━━
                        定义 Socket 的放置方向
                        
                        确保：
                        • 门窗朝向正确
                        • 附着面对齐
                        • 多个 Socket 协调一致
                        
                        点击循环切换方向""")))
                .build();

        socketAddButton = ButtonWidget.builder(Text.literal("添加 Socket"), b -> ComponentTool.INSTANCE.addSocket())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        添加 Socket（高级）
                        ━━━━━━━━━━━━
                        将当前配置的 Socket 添加到构件
                        
                        一个构件可以有多个 Socket
                        例如：墙体可以有多个门窗位置
                        
                        添加后在列表中显示""")))
                .build();

        socketPreviewButton = ButtonWidget.builder(Text.literal("预览"), b -> ComponentTool.INSTANCE.toggleSocketPreview(client))
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        预览 Socket（高级）
                        ━━━━━━━━━━━━
                        在世界中显示 Socket 的：
                        • 位置标记
                        • 朝向箭头
                        • 大小区域
                        
                        帮助验证 Socket 配置正确""")))
                .build();

        socketClearButton = ButtonWidget.builder(Text.literal("清空 Sockets"), b -> ComponentTool.INSTANCE.clearSockets())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        清空所有 Socket（高级）
                        ━━━━━━━━━━━━
                        移除所有已添加的 Socket
                        
                        注意：此操作不可撤销
                        清空后需要重新配置""")))
                .build();

        // 智能分析按钮
        autoAnalyzeButton = ButtonWidget.builder(Text.literal("🔍 智能分析"), b -> runAutoAnalysis())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        智能分析构件（推荐）
                        ━━━━━━━━━━━━
                        自动分析构件特征并设置参数
                        
                        分析内容：
                        • 构件类型（门/窗/柱子等）
                        • 附着模式（底面/竖边等）
                        • 建筑风格（现代/中世纪等）
                        • 建筑原型（民居/宫殿等）
                        
                        节省手动配置时间！""")))
                .build();

        autoDetectSocketsButton = ButtonWidget.builder(Text.literal("🔍 自动检测"), b -> autoDetectSockets())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        自动检测 Socket（高级）
                        ━━━━━━━━━━━━
                        智能识别构件中的嵌入位置
                        
                        可检测：
                        • 门框开口
                        • 窗户位置
                        • 栏杆连接点
                        • 装饰附着点
                        
                        自动创建 Socket 列表""")))
                .build();

        // 底部按钮
        cancelButton = ButtonWidget.builder(Text.literal("取消"), b -> {
                    ComponentTool.INSTANCE.getState().pickingAnchor = false;
                    FormaCraftHudOverlay.activePanel = PanelType.TOOLS;
                })
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        取消构件拾取
                        ━━━━━━━━━━━━
                        放弃当前构件配置
                        返回工具面板
                        
                        已配置的内容将丢失""")))
                .build();

        saveButton = ButtonWidget.builder(Text.literal("✓ 保存到构件库"), b -> saveComponent())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        保存构件到库
                        ━━━━━━━━━━━━
                        将构件保存到全局构件库
                        
                        保存内容：
                        • 方块数据和结构
                        • 锚点和朝向
                        • 分类和标签
                        • 缩略图预览
                        • Socket 配置（如果有）
                        
                        保存后自动跳转到构件库""")))
                .build();
        
        // 选择工具按钮
        boxSelectButton = ButtonWidget.builder(Text.literal("📦 框选"), b -> setSelectionMode(ComponentSelectionMode.BOX_SELECT))
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        框选模式
                        ━━━━━━━━━━━━
                        拖拽框选区域（有实时预览）
                        
                        使用方法：
                        1. 激活此模式
                        2. 在世界中左键拖拽
                        3. 看到蓝色预览框
                        4. 释放鼠标完成选择
                        
                        提示：适合快速框选完整结构""")))
                .build();
        
        pointSelectButton = ButtonWidget.builder(Text.literal("👆 点选"), b -> setSelectionMode(ComponentSelectionMode.POINT_SELECT))
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        点选模式
                        ━━━━━━━━━━━━
                        单个方块精确选择
                        
                        使用方法：
                        • 点击未选方块 → 加选
                        • 点击已选方块 → 减选
                        • Ctrl+点击 → 强制加选
                        
                        提示：适合精细调整和不规则选区""")))
                .build();
        
        clearSelectionButton = ButtonWidget.builder(Text.literal("🗑️ 清除"), b -> clearSelection())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        清除选区
                        ━━━━━━━━━━━━
                        一键清空当前选区
                        
                        使用场景：
                        • 重新开始选择
                        • 清除错误的选区
                        • 快速重置
                        
                        提示：清除后需要重新选择""")))
                .build();
    }
    
    /**
     * 设置选择模式
     */
    private void setSelectionMode(ComponentSelectionMode mode) {
        this.selectionMode = mode;
        System.out.println("[ComponentCapturePanel] 切换选择模式: " + mode.getDisplayName());
    }

    @Override
    protected void drawContents(DrawContext ctx) {
        ensureWidgets();

        int x = panelX + CONTENT_PADDING;
        int w = panelWidth - CONTENT_PADDING * 2;
        int y = getContentY() + CONTENT_PADDING - scrollY;

        // 重置边界
        nameInputBoundsValid = false;
        tagsInputBoundsValid = false;
        socketIdInputBoundsValid = false;

        // 背景
        ctx.fill(panelX + 1, getContentY(), panelX + panelWidth - 1, panelY + panelHeight - 1, 0x80101010);

        // 启用裁剪
        ctx.enableScissor(panelX, getContentY(), panelX + panelWidth, panelY + panelHeight);

        // 标题
        y = drawWrappedText(ctx, Text.literal("[ 🎯 构件拾取 ]"), x, y, w, 0xFFFFFFFF);
        y += 4;
        
        // 选择工具区域
        y = drawWrappedText(ctx, Text.literal("🔧 选择工具"), x, y, w, 0xFFFFFF00);
        y += 2;
        
        // 选择模式按钮组
        int buttonW = (w - 8) / 3; // 3个按钮平分宽度
        
        // 框选按钮
        boxSelectButton.setMessage(Text.literal(selectionMode == ComponentSelectionMode.BOX_SELECT ? "📦 [框选]" : "📦 框选"));
        boxSelectButton.setPosition(x, y);
        boxSelectButton.setWidth(buttonW);
        boxSelectButton.visible = true;
        boxSelectButton.active = true;
        boxSelectButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
        
        // 点选按钮
        pointSelectButton.setMessage(Text.literal(selectionMode == ComponentSelectionMode.POINT_SELECT ? "👆 [点选]" : "👆 点选"));
        pointSelectButton.setPosition(x + buttonW + 4, y);
        pointSelectButton.setWidth(buttonW);
        pointSelectButton.visible = true;
        pointSelectButton.active = true;
        pointSelectButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
        
        // 清除按钮
        clearSelectionButton.setMessage(Text.literal("🗑️ 清除"));
        clearSelectionButton.setPosition(x + buttonW * 2 + 8, y);
        clearSelectionButton.setWidth(buttonW);
        clearSelectionButton.visible = true;
        clearSelectionButton.active = !selectedBlocks.isEmpty() || SelectionTool.INSTANCE.hasSelection();
        clearSelectionButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
        
        y += LABEL_OFFSET;
        
        // 当前模式提示
        String modeText = "当前模式: " + selectionMode.getDisplayName() + " - " + selectionMode.getHint();
        y = drawWrappedText(ctx, Text.literal(modeText), x, y, w, 0xFF88CCFF);
        y += 4;
        
        // 快捷键说明
        String hint = selectionMode == ComponentSelectionMode.POINT_SELECT 
            ? "提示: 点击方块切换选择 | Ctrl+点击强制加选 | 右键设锚点"
            : "提示: 拖拽可见预览框 | 右键设锚点";
        ctx.drawTextWithShadow(client.textRenderer, Text.literal(hint), x, y, 0xFF666666);
        y += client.textRenderer.fontHeight + 4;
        
        // 分隔线
        ctx.fill(x, y, x + w, y + 1, 0xFF444444);
        y += 4;

        // 检查是否有选区
        if (!SelectionTool.INSTANCE.hasSelection() && selectedBlocks.isEmpty()) {
            y = drawWrappedText(ctx, Text.literal("请使用上面的选择工具框选要拾取的构件"), x, y, w, 0xFFFFAA00);
            y += 4;
            y = drawWrappedText(ctx, Text.literal("提示：点击'框选'，然后在世界中拖拽鼠标"), x, y, w, 0xFFAAAAAA);
            ctx.disableScissor();
            return;
        }

        BlockPos min = SelectionTool.INSTANCE.getMin();
        BlockPos max = SelectionTool.INSTANCE.getMax();

        // 选区预览
        y = drawWrappedText(ctx, Text.literal("当前选区"), x, y, w, 0xFFFFFFFF);
        y += 2;

        int sizeX = 0;
        if (max != null) {
            if (min != null) {
                sizeX = max.getX() - min.getX() + 1;
            }
        }
        int sizeY = 0;
        if (max != null) {
            if (min != null) {
                sizeY = max.getY() - min.getY() + 1;
            }
        }
        int sizeZ = 0;
        if (max != null) {
            if (min != null) {
                sizeZ = max.getZ() - min.getZ() + 1;
            }
        }
        int blockCount = countBlocksInSelection();

        y = drawWrappedText(ctx, Text.literal("尺寸: " + sizeX + "×" + sizeY + "×" + sizeZ), x, y, w, 0xFFAAAAAA);
        y += client.textRenderer.fontHeight;
        y = drawWrappedText(ctx, Text.literal("方块数: " + blockCount), x, y, w, 0xFFAAAAAA);
        y += 6;
        
        // 状态指示器
        y = drawStatusIndicator(ctx, x, y, w);
        y += 4;

        // 缩略图预览
        drawThumbnailPreview(ctx, x + (w - THUMBNAIL_SIZE) / 2, y);
        y += THUMBNAIL_SIZE + 8;

        // 分隔线
        ctx.fill(x, y, x + w, y + 1, 0xFF444444);
        y += 4;

        // 基础信息
        y = drawWrappedText(ctx, Text.literal("📝 基础信息"), x, y, w, 0xFFFFFFFF);
        y += 2;

        var st = ComponentTool.INSTANCE.getState();

        // 名称输入
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("名称:"), x, y, 0xFFAAAAAA);
        int inputY = y + LABEL_OFFSET - 2;
        if (!nameInput.isFocused() && !st.name.equals(nameInput.getText())) {
            nameInput.setText(st.name);
        }
        nameInput.render(ctx, x, inputY, w, 14);
        nameInputX = x; nameInputY = inputY; nameInputW = w; nameInputH = 14;
        nameInputBoundsValid = true;
        st.name = nameInput.getText() != null ? nameInput.getText() : "New Component";
        y += FIELD_SPACING;

        // 分类按钮
        categoryButton.setMessage(Text.literal("分类：" + st.category.name()));
        categoryButton.setPosition(x, y);
        categoryButton.setWidth(w);
        categoryButton.visible = true;
        categoryButton.active = true;
        categoryButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
        y += LABEL_OFFSET;

        // 标签输入
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("标签 (逗号分隔):"), x, y, 0xFFAAAAAA);
        inputY = y + LABEL_OFFSET - 2;
        String currentTags = String.join(", ", st.tags);
        if (!tagsInput.isFocused() && !currentTags.equals(tagsInput.getText())) {
            tagsInput.setText(currentTags);
        }
        tagsInput.render(ctx, x, inputY, w, 14);
        tagsInputX = x; tagsInputY = inputY; tagsInputW = w; tagsInputH = 14;
        tagsInputBoundsValid = true;
        updateTagsFromInput();
        y += FIELD_SPACING;

        // 分隔线
        ctx.fill(x, y, x + w, y + 1, 0xFF444444);
        y += 4;

        // 锚点与朝向
        y = drawWrappedText(ctx, Text.literal("🎯 锚点与朝向"), x, y, w, 0xFFFFFFFF);
        y += 2;

        String anchorText = st.anchorWorld != null 
            ? "锚点: (" + st.anchorWorld.getX() + ", " + st.anchorWorld.getY() + ", " + st.anchorWorld.getZ() + ")"
            : "锚点: (未设置，默认为选区最小角)";
        y = drawWrappedText(ctx, Text.literal(anchorText), x, y, w, 
            st.anchorWorld != null ? 0xFF66FF66 : 0xFFFFAA00);
        y += 2;

        int half = (w - 4) / 2;

        pickAnchorButton.setMessage(Text.literal(st.pickingAnchor ? "⏹ 取消选择" : "📍 点击选择"));
        pickAnchorButton.setPosition(x, y);
        pickAnchorButton.setWidth(half);
        pickAnchorButton.visible = true;
        pickAnchorButton.active = true;
        pickAnchorButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);

        clearAnchorButton.setPosition(x + half + 4, y);
        clearAnchorButton.setWidth(w - half - 4);
        clearAnchorButton.visible = true;
        clearAnchorButton.active = st.anchorWorld != null || st.pickingAnchor;
        clearAnchorButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
        y += LABEL_OFFSET;

        facingButton.setMessage(Text.literal("朝向：" + st.facing.name()));
        facingButton.setPosition(x, y);
        facingButton.setWidth(half);
        facingButton.visible = true;
        facingButton.active = true;
        facingButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);

        mirrorButton.setMessage(Text.literal("镜像：" + st.mirror.name()));
        mirrorButton.setPosition(x + half + 4, y);
        mirrorButton.setWidth(w - half - 4);
        mirrorButton.visible = true;
        mirrorButton.active = true;
        mirrorButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
        y += LABEL_OFFSET;

        // 分隔线
        ctx.fill(x, y, x + w, y + 1, 0xFF444444);
        y += 4;

        // 语义标注
        y = drawWrappedText(ctx, Text.literal("🎨 语义标注"), x, y, w, 0xFFFFFFFF);
        y += 2;

        semanticSkinButton.setMessage(Text.literal(st.semanticSkin ? "材质：语义" : "材质：原样"));
        semanticSkinButton.setPosition(x, y);
        semanticSkinButton.setWidth(half);
        semanticSkinButton.visible = true;
        semanticSkinButton.active = true;
        semanticSkinButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);

        semanticTagOnSaveButton.setMessage(Text.literal(st.semanticTagOnSave ? "存语义：开" : "存语义：关"));
        semanticTagOnSaveButton.setPosition(x + half + 4, y);
        semanticTagOnSaveButton.setWidth(w - half - 4);
        semanticTagOnSaveButton.visible = true;
        semanticTagOnSaveButton.active = true;
        semanticTagOnSaveButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
        y += LABEL_OFFSET;

        semanticStyleButton.setMessage(Text.literal("风格：" + (st.semanticStyleId != null ? st.semanticStyleId : "DEFAULT")));
        semanticStyleButton.setPosition(x, y);
        semanticStyleButton.setWidth(w);
        semanticStyleButton.visible = true;
        semanticStyleButton.active = st.semanticSkin;
        semanticStyleButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
        y += LABEL_OFFSET;

        semanticPartButton.setMessage(Text.literal("语义：" + (st.semanticPart != null ? st.semanticPart.name() : "AUTO")));
        semanticPartButton.setPosition(x, y);
        semanticPartButton.setWidth(w);
        semanticPartButton.visible = true;
        semanticPartButton.active = st.semanticSkin;
        semanticPartButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
        y += LABEL_OFFSET;

        // 分隔线
        ctx.fill(x, y, x + w, y + 1, 0xFF444444);
        y += 4;

        // Socket 配置
        y = drawWrappedText(ctx, Text.literal("🔌 Socket 配置"), x, y, w, 0xFFFFFFFF);
        y += 2;

        String so = st.socketOriginLocal != null
                ? ("原点(local)=" + st.socketOriginLocal.getX() + "," + st.socketOriginLocal.getY() + "," + st.socketOriginLocal.getZ())
                : "原点(local)=未设置";
        String ss = "尺寸=" + st.socketW + "×" + st.socketH + "×" + st.socketD;
        y = drawWrappedText(ctx, Text.literal("已添加: " + st.socketCount + " 个  " + so + "  " + ss), x, y, w, 0xFFAAAAAA);
        y += 2;

        autoDetectSocketsButton.setPosition(x, y);
        autoDetectSocketsButton.setWidth(w);
        autoDetectSocketsButton.visible = true;
        autoDetectSocketsButton.active = true;
        autoDetectSocketsButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
        y += LABEL_OFFSET;

        socketContextButton.setMessage(Text.literal("Context: " + (st.socketContext != null ? st.socketContext.name() : "WALL")));
        socketContextButton.setPosition(x, y);
        socketContextButton.setWidth(w);
        socketContextButton.visible = true;
        socketContextButton.active = SelectionTool.INSTANCE.hasSelection();
        socketContextButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
        y += LABEL_OFFSET;

        // Socket ID 输入
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("Socket ID:"), x, y, 0xFFAAAAAA);
        inputY = y + LABEL_OFFSET - 2;
        socketIdInput.render(ctx, x, inputY, w, 14);
        socketIdInputX = x; socketIdInputY = inputY; socketIdInputW = w; socketIdInputH = 14;
        socketIdInputBoundsValid = true;
        String sid = socketIdInput.getText();
        if (sid != null && !sid.isBlank()) {
            st.socketIdDraft = sid.trim();
        }
        y += FIELD_SPACING;

        socketPickOriginButton.setPosition(x, y);
        socketPickOriginButton.setWidth(half);
        socketPickOriginButton.visible = true;
        socketPickOriginButton.active = SelectionTool.INSTANCE.hasSelection() && st.anchorWorld != null;
        socketPickOriginButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);

        socketFacingButton.setMessage(Text.literal("朝向: " + (st.socketFacing != null ? st.socketFacing.name() : "SOUTH")));
        socketFacingButton.setPosition(x + half + 4, y);
        socketFacingButton.setWidth(w - half - 4);
        socketFacingButton.visible = true;
        socketFacingButton.active = true;
        socketFacingButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
        y += LABEL_OFFSET;

        socketAddButton.setPosition(x, y);
        socketAddButton.setWidth(half);
        socketAddButton.visible = true;
        socketAddButton.active = SelectionTool.INSTANCE.hasSelection() && st.socketOriginLocal != null;
        socketAddButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);

        socketPreviewButton.setPosition(x + half + 4, y);
        socketPreviewButton.setWidth(w - half - 4);
        socketPreviewButton.visible = true;
        socketPreviewButton.active = st.anchorWorld != null && st.socketOriginLocal != null;
        socketPreviewButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
        y += LABEL_OFFSET;

        socketClearButton.setPosition(x, y);
        socketClearButton.setWidth(w);
        socketClearButton.visible = true;
        socketClearButton.active = st.socketCount > 0;
        socketClearButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
        y += LABEL_OFFSET;

        // 分隔线
        ctx.fill(x, y, x + w, y + 1, 0xFF444444);
        y += 4;

        // 智能分析
        y = drawWrappedText(ctx, Text.literal("🧠 智能分析"), x, y, w, 0xFFFFFFFF);
        y += 2;

        autoAnalyzeButton.setPosition(x, y);
        autoAnalyzeButton.setWidth(w);
        autoAnalyzeButton.visible = true;
        autoAnalyzeButton.active = st.anchorWorld != null;
        autoAnalyzeButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
        y += LABEL_OFFSET;

        // 分隔线
        ctx.fill(x, y, x + w, y + 1, 0xFF444444);
        y += 8;

        // 底部按钮
        cancelButton.setPosition(x, y);
        cancelButton.setWidth(half);
        cancelButton.visible = true;
        cancelButton.active = true;
        cancelButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);

        saveButton.setPosition(x + half + 4, y);
        saveButton.setWidth(w - half - 4);
        saveButton.visible = true;
        saveButton.active = canSave();
        saveButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
        y += LABEL_OFFSET;

        // 计算最大滚动
        // y 已经减去了 scrollY，所以需要加回来计算总内容高度
        int contentStartY = getContentY() + CONTENT_PADDING;
        int totalContentHeight = (y + scrollY) - contentStartY;
        int visibleHeight = panelHeight - (contentStartY - panelY) - CONTENT_PADDING;
        maxScrollY = Math.max(0, totalContentHeight - visibleHeight);
        
        // 限制当前滚动位置
        if (scrollY > maxScrollY) scrollY = maxScrollY;
        if (scrollY < 0) scrollY = 0;

        ctx.disableScissor();
    }

    private void drawThumbnailPreview(DrawContext ctx, int x, int y) {
        // 绘制边框
        ctx.fill(x - 1, y - 1, x + THUMBNAIL_SIZE + 1, y + THUMBNAIL_SIZE + 1, 0xFF444444);
        ctx.fill(x, y, x + THUMBNAIL_SIZE, y + THUMBNAIL_SIZE, 0xFF1A1A1A);

        // 检查是否需要重新生成缩略图
        BlockPos min = SelectionTool.INSTANCE.getMin();
        BlockPos max = SelectionTool.INSTANCE.getMax();

        // 防止重复触发生成（防抖）
        if (max != null && min != null && 
            (cachedThumbnail == null || !min.equals(lastSelectionMin) || !max.equals(lastSelectionMax))) {
            // 只有在没有正在生成时才触发新的生成
            if (!isGeneratingThumbnail) {
                regenerateThumbnail();
                lastSelectionMin = min;
                lastSelectionMax = max;
            }
        }

        // 绘制缩略图
        if (cachedThumbnail != null) {
            com.formacraft.client.ui.render.ImageRenderer.renderCentered(ctx, cachedThumbnail, x, y, THUMBNAIL_SIZE, THUMBNAIL_SIZE);
        } else {
            ctx.drawTextWithShadow(client.textRenderer, Text.literal("生成中..."), 
                x + THUMBNAIL_SIZE / 2 - 20, y + THUMBNAIL_SIZE / 2, 0xFFAAAAAA);
        }
    }

    private void regenerateThumbnail() {
        // 防止重复生成
        if (isGeneratingThumbnail) {
            System.out.println("[缩略图] 已有生成任务在进行，跳过");
            return;
        }
        
        // 清空旧缩略图
        cachedThumbnail = null;
        isGeneratingThumbnail = true;
        
        System.out.println("[缩略图] 开始生成...");
        
        // 异步生成缩略图
        new Thread(() -> {
            try {
                // 检查前置条件
                if (client == null || client.world == null) {
                    System.err.println("✗ client 或 client.world 为 null");
                    return;
                }
                
                if (!SelectionTool.INSTANCE.hasSelection()) {
                    System.err.println("✗ 没有选区");
                    return;
                }
                
                BlockPos min = SelectionTool.INSTANCE.getMin();
                BlockPos max = SelectionTool.INSTANCE.getMax();
                if (min == null || max == null) {
                    System.err.println("✗ 选区 min 或 max 为 null");
                    return;
                }
                
                // 检查锚点
                var st = ComponentTool.INSTANCE.getState();
                if (st.anchorWorld == null) {
                    System.err.println("✗ 锚点未设置！请先设置锚点（右键点击方块）");
                    return;
                }
                
                System.out.println("[缩略图] 选区: " + min + " -> " + max);
                System.out.println("[缩略图] 锚点: " + st.anchorWorld);
                
                String json = ComponentTool.INSTANCE.buildCurrentComponentJson(client);
                if (json != null) {
                    ComponentDefinition def = JsonUtil.fromJson(json, ComponentDefinition.class);
                    if (def != null) {
                        java.awt.image.BufferedImage thumb = ComponentThumbnailGenerator.generateThumbnail(def);
                        if (thumb != null) {
                            System.out.println("✓ 缩略图生成成功: " + thumb.getWidth() + "x" + thumb.getHeight());
                            cachedThumbnail = thumb;
                        } else {
                            System.err.println("✗ 缩略图生成失败: generateThumbnail 返回 null");
                        }
                    } else {
                        System.err.println("✗ 无法解析 ComponentDefinition");
                    }
                } else {
                    System.err.println("✗ buildCurrentComponentJson 返回 null（可能是锚点问题）");
                }
            } catch (Exception e) {
                System.err.println("✗ 生成缩略图时出错: " + e.getMessage());
                e.printStackTrace();
            } finally {
                isGeneratingThumbnail = false;
                System.out.println("[缩略图] 生成任务结束");
            }
        }, "ThumbnailGenerator").start();
    }

    private int countBlocksInSelection() {
        if (client == null || client.world == null) return 0;
        if (!SelectionTool.INSTANCE.hasSelection()) return 0;

        BlockPos min = SelectionTool.INSTANCE.getMin();
        BlockPos max = SelectionTool.INSTANCE.getMax();
        int count = 0;

        assert min != null;
        if (max != null) {
            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int y = min.getY(); y <= max.getY(); y++) {
                    for (int z = min.getZ(); z <= max.getZ(); z++) {
                        if (!client.world.getBlockState(new BlockPos(x, y, z)).isAir()) {
                            count++;
                        }
                    }
                }
            }
        }

        return count;
    }

    private void cycleCategory() {
        var st = ComponentTool.INSTANCE.getState();
        ComponentCategory[] values = ComponentCategory.values();
        int idx = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == st.category) {
                idx = i;
                break;
            }
        }
        st.category = values[(idx + 1) % values.length];
    }

    private void cycleFacing() {
        var st = ComponentTool.INSTANCE.getState();
        st.facing = st.facing.rotateYClockwise();
    }

    private void cycleMirror() {
        var st = ComponentTool.INSTANCE.getState();
        var values = com.formacraft.common.component.transform.Mirror.values();
        int idx = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == st.mirror) {
                idx = i;
                break;
            }
        }
        st.mirror = values[(idx + 1) % values.length];
    }

    private void updateTagsFromInput() {
        var st = ComponentTool.INSTANCE.getState();
        String text = tagsInput.getText();
        if (text == null || text.isBlank()) {
            st.tags.clear();
            return;
        }

        st.tags.clear();
        for (String tag : text.split("[,，]")) {
            tag = tag.trim();
            if (!tag.isEmpty()) {
                st.tags.add(tag);
            }
        }
    }

    private void runAutoAnalysis() {
        HudToast.show("正在智能分析构件特征...");
        // TODO: 调用智能分析逻辑
        // ComponentTool.INSTANCE.runAutoAnalysis();
        HudToast.show("智能分析完成！（功能待实现）");
    }

    private void autoDetectSockets() {
        HudToast.show("正在自动检测 Socket...");
        // TODO: 调用自动检测逻辑
        // ComponentTool.INSTANCE.autoDetectSockets();
        HudToast.show("Socket 自动检测完成！（功能待实现）");
    }

    private boolean canSave() {
        var st = ComponentTool.INSTANCE.getState();
        return SelectionTool.INSTANCE.hasSelection() 
            && st.name != null 
            && !st.name.isBlank();
    }

    private void saveComponent() {
        if (!canSave()) {
            HudToast.show("保存失败：请检查选区和名称", true);
            return;
        }

        var st = ComponentTool.INSTANCE.getState();
        String json = ComponentTool.INSTANCE.buildCurrentComponentJson(client);
        if (json == null || json.isBlank()) {
            HudToast.show("保存失败：请检查选区", true);
            return;
        }

        // 生成缩略图
        byte[] thumbnailPng = null;
        try {
            ComponentDefinition def = JsonUtil.fromJson(json, ComponentDefinition.class);
            if (def != null) {
                BufferedImage thumb = ComponentThumbnailGenerator.generateThumbnail(def);
                if (thumb != null) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(thumb, "PNG", baos);
                    thumbnailPng = baos.toByteArray();
                }
            }
        } catch (Throwable t) {
            System.err.println("Failed to generate thumbnail: " + t.getMessage());
        }

        ComponentTool.INSTANCE.markSavePending(st.name);
        HudToast.show("正在保存构件「" + st.name.trim() + "」…");
        FormaCraftNetworking.sendSaveComponent(json, thumbnailPng);

        // 记录新构件 ID，用于跳转后高亮
        String newComponentId = makeId(st.category, st.name);
        
        // 延迟跳转（等待服务器响应）
        new Thread(() -> {
            try {
                Thread.sleep(500); // 等待保存完成
                client.execute(() -> {
                    // 跳转到构件库面板
                    FormaCraftHudOverlay.activePanel = PanelType.COMPONENT_LIBRARY;
                    // 设置选中的构件（高亮显示）
                    st.librarySelectedId = newComponentId;
                    st.librarySelectedName = st.name;
                    HudToast.show("✓ 构件已保存到库");
                });
            } catch (InterruptedException ignored) {}
        }).start();
    }

    private String makeId(ComponentCategory cat, String name) {
        String n = (name == null ? "" : name).toLowerCase(java.util.Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "_");
        if (n.isBlank()) n = "component";
        return n;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0) return false;
        if (!isMouseOver(mouseX, mouseY)) return false;

        ensureWidgets();
        Click click = new Click(mouseX, mouseY, new MouseInput(button, 0));

        // 选择工具按钮点击
        if (boxSelectButton != null && boxSelectButton.visible && boxSelectButton.mouseClicked(click, false)) return true;
        if (pointSelectButton != null && pointSelectButton.visible && pointSelectButton.mouseClicked(click, false)) return true;
        if (clearSelectionButton != null && clearSelectionButton.visible && clearSelectionButton.mouseClicked(click, false)) return true;
        
        // 按钮点击
        if (categoryButton != null && categoryButton.visible && categoryButton.mouseClicked(click, false)) return true;
        if (pickAnchorButton != null && pickAnchorButton.visible && pickAnchorButton.mouseClicked(click, false)) return true;
        if (clearAnchorButton != null && clearAnchorButton.visible && clearAnchorButton.mouseClicked(click, false)) return true;
        if (facingButton != null && facingButton.visible && facingButton.mouseClicked(click, false)) return true;
        if (mirrorButton != null && mirrorButton.visible && mirrorButton.mouseClicked(click, false)) return true;
        
        if (semanticSkinButton != null && semanticSkinButton.visible && semanticSkinButton.mouseClicked(click, false)) return true;
        if (semanticTagOnSaveButton != null && semanticTagOnSaveButton.visible && semanticTagOnSaveButton.mouseClicked(click, false)) return true;
        if (semanticStyleButton != null && semanticStyleButton.visible && semanticStyleButton.mouseClicked(click, false)) return true;
        if (semanticPartButton != null && semanticPartButton.visible && semanticPartButton.mouseClicked(click, false)) return true;
        
        if (socketContextButton != null && socketContextButton.visible && socketContextButton.mouseClicked(click, false)) return true;
        if (socketPickOriginButton != null && socketPickOriginButton.visible && socketPickOriginButton.mouseClicked(click, false)) return true;
        if (socketFacingButton != null && socketFacingButton.visible && socketFacingButton.mouseClicked(click, false)) return true;
        if (socketAddButton != null && socketAddButton.visible && socketAddButton.mouseClicked(click, false)) return true;
        if (socketPreviewButton != null && socketPreviewButton.visible && socketPreviewButton.mouseClicked(click, false)) return true;
        if (socketClearButton != null && socketClearButton.visible && socketClearButton.mouseClicked(click, false)) return true;
        
        if (autoAnalyzeButton != null && autoAnalyzeButton.visible && autoAnalyzeButton.mouseClicked(click, false)) return true;
        if (autoDetectSocketsButton != null && autoDetectSocketsButton.visible && autoDetectSocketsButton.mouseClicked(click, false)) return true;
        
        if (cancelButton != null && cancelButton.visible && cancelButton.mouseClicked(click, false)) return true;
        if (saveButton != null && saveButton.visible && saveButton.mouseClicked(click, false)) return true;

        // 输入框点击
        if (nameInputBoundsValid) {
            if (nameInput.mouseClicked(mouseX, mouseY, nameInputX, nameInputY, nameInputW, nameInputH)) {
                tagsInput.setFocused(false);
                socketIdInput.setFocused(false);
                return true;
            }
        }

        if (tagsInputBoundsValid) {
            if (tagsInput.mouseClicked(mouseX, mouseY, tagsInputX, tagsInputY, tagsInputW, tagsInputH)) {
                nameInput.setFocused(false);
                socketIdInput.setFocused(false);
                return true;
            }
        }

        if (socketIdInputBoundsValid) {
            if (socketIdInput.mouseClicked(mouseX, mouseY, socketIdInputX, socketIdInputY, socketIdInputW, socketIdInputH)) {
                nameInput.setFocused(false);
                tagsInput.setFocused(false);
                return true;
            }
        }

        return true; // 消费所有面板内点击
    }

    @Override
    public void mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!isMouseOver(mouseX, mouseY)) return;
        
        // 滚动步长：每次滚轮滚动 12 像素（与 ToolPanel 和 SettingsPanel 一致）
        int step = 12;
        scrollY = (int) Math.round(scrollY - amount * step);
        
        // 限制滚动范围
        if (scrollY < 0) scrollY = 0;
        if (scrollY > maxScrollY) scrollY = maxScrollY;
    }

    @Override
    public void keyPressed(int keyCode, int scanCode, int modifiers) {
        ensureWidgets();
        nameInput.keyPressed(keyCode, modifiers);
        tagsInput.keyPressed(keyCode, modifiers);
        socketIdInput.keyPressed(keyCode, modifiers);
    }

    @Override
    public void charTyped(char chr) {
        ensureWidgets();
        nameInput.charTyped(chr);
        tagsInput.charTyped(chr);
        socketIdInput.charTyped(chr);
    }

    @Override
    public boolean wantsKeyboardInput() {
        return nameInput.isFocused() || tagsInput.isFocused() || socketIdInput.isFocused();
    }

    private int drawWrappedText(DrawContext ctx, Text text, int x, int y, int maxWidth, int color) {
        if (text == null) return y;
        int lineHeight = client.textRenderer.fontHeight;
        for (var line : client.textRenderer.wrapLines(text, maxWidth)) {
            ctx.drawTextWithShadow(client.textRenderer, line, x, y, color);
            y += lineHeight;
        }
        return y;
    }

    private int getScaledMouseX() {
        return (int) (client.mouse.getX() / client.getWindow().getScaleFactor());
    }

    private int getScaledMouseY() {
        return (int) (client.mouse.getY() / client.getWindow().getScaleFactor());
    }

    /**
     * 绘制状态指示器
     */
    private int drawStatusIndicator(DrawContext ctx, int x, int y, int w) {
        var st = ComponentTool.INSTANCE.getState();
        
        // 边框
        ctx.fill(x, y, x + w, y + 1, 0xFF444444);
        y += 2;
        
        // 选区状态
        String selectionStatus = SelectionTool.INSTANCE.hasSelection() ? "✓" : "⚠";
        int selectionColor = SelectionTool.INSTANCE.hasSelection() ? 0xFF00FF00 : 0xFFFFAA00;
        ctx.drawTextWithShadow(client.textRenderer, Text.literal(selectionStatus + " 选区已设置"), x, y, selectionColor);
        y += client.textRenderer.fontHeight + 2;
        
        // 锚点状态
        boolean hasAnchor = st.anchorWorld != null;
        String anchorStatus = hasAnchor ? "✓" : "⚠";
        int anchorColor = hasAnchor ? 0xFF00FF00 : 0xFFFFAA00;
        String anchorText = hasAnchor ? 
            String.format("✓ 锚点: (%d, %d, %d)", st.anchorWorld.getX(), st.anchorWorld.getY(), st.anchorWorld.getZ()) :
            "⚠ 锚点: 未设置";
        ctx.drawTextWithShadow(client.textRenderer, Text.literal(anchorText), x, y, anchorColor);
        y += client.textRenderer.fontHeight + 2;
        
        // 朝向状态
        String facingText = "➡ 朝向: " + st.facing.name();
        ctx.drawTextWithShadow(client.textRenderer, Text.literal(facingText), x, y, 0xFF88CCFF);
        y += client.textRenderer.fontHeight + 2;
        
        // 名称状态
        boolean hasName = st.name != null && !st.name.isEmpty() && !st.name.equals("New Component");
        String nameStatus = hasName ? "✓" : "⚠";
        int nameColor = hasName ? 0xFF00FF00 : 0xFFFFAA00;
        String nameText = hasName ? "✓ 名称已填写" : "⚠ 名称: 请填写";
        ctx.drawTextWithShadow(client.textRenderer, Text.literal(nameText), x, y, nameColor);
        y += client.textRenderer.fontHeight + 2;
        
        ctx.fill(x, y, x + w, y + 1, 0xFF444444);
        y += 2;
        
        return y;
    }
    
    // ============ 世界交互方法 ============
    
    /**
     * 处理世界点击（从 InputRouter 调用）
     * @return true 如果事件被处理
     */
    public boolean handleWorldClick(net.minecraft.util.math.BlockPos pos, int button) {
        if (pos == null) return false;
        
        System.out.println("[ComponentCapturePanel] 世界点击: " + pos + ", 按钮: " + button + ", 模式: " + selectionMode);
        
        // 右键设置锚点
        if (button == 1) {
            setAnchor(pos);
            return true;
        }
        
        // 左键根据模式处理
        if (button == 0) {
            switch (selectionMode) {
                case BOX_SELECT:
                    // 框选模式：直接使用 SelectionTool 进行框选
                    // SelectionTool 会处理拖拽和渲染预览
                    SelectionTool.INSTANCE.onMouseClick(0, 0, button);
                    System.out.println("[ComponentCapturePanel] 框选: 交给 SelectionTool 处理");
                    return true;
                    
                case POINT_SELECT:
                    // 点选模式：切换方块选择状态
                    boolean isCtrlDown = org.lwjgl.glfw.GLFW.glfwGetKey(
                        client.getWindow().getHandle(), 
                        org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL
                    ) == org.lwjgl.glfw.GLFW.GLFW_PRESS || org.lwjgl.glfw.GLFW.glfwGetKey(
                        client.getWindow().getHandle(), 
                        org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL
                    ) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
                    
                    if (isCtrlDown) {
                        // Ctrl+点击：强制加选
                        addBlockToSelection(pos);
                        System.out.println("[ComponentCapturePanel] Ctrl+点击 强制加选: " + pos);
                    } else {
                        // 普通点击：切换状态
                        if (selectedBlocks.contains(pos.toImmutable())) {
                            // 已选中 → 减选
                            removeBlockFromSelection(pos);
                            System.out.println("[ComponentCapturePanel] 点击减选: " + pos + ", 总数: " + selectedBlocks.size());
                        } else {
                            // 未选中 → 加选
                            addBlockToSelection(pos);
                            System.out.println("[ComponentCapturePanel] 点击加选: " + pos + ", 总数: " + selectedBlocks.size());
                        }
                    }
                    return true;
            }
        }
        
        return false;
    }
    
    /**
     * 处理世界拖拽（从 tick 调用）
     * 框选模式下由 SelectionTool 自动处理
     */
    public void handleWorldDrag(net.minecraft.util.math.BlockPos currentPos) {
        // 框选模式：SelectionTool 自动更新
        // 点选模式：无需拖拽处理
    }
    
    /**
     * 处理鼠标释放（从 InputRouter 调用）
     */
    public void handleWorldRelease(int button) {
        if (button == 0 && selectionMode == ComponentSelectionMode.BOX_SELECT) {
            // 框选模式完成：从 SelectionTool 同步选区
            if (SelectionTool.INSTANCE.hasSelection()) {
                net.minecraft.util.math.BlockPos min = SelectionTool.INSTANCE.getMin();
                net.minecraft.util.math.BlockPos max = SelectionTool.INSTANCE.getMax();
                if (min != null && max != null) {
                    setBoxSelection(min, max);
                    System.out.println("[ComponentCapturePanel] 从 SelectionTool 同步选区: " + min + " -> " + max + ", 方块数: " + selectedBlocks.size());
                }
            }
        }
    }
    
    /**
     * 添加单个方块到选区
     */
    private void addBlockToSelection(net.minecraft.util.math.BlockPos pos) {
        if (pos == null) return;
        selectedBlocks.add(pos.toImmutable());
        updateSelectionToolFromBlocks();
    }
    
    /**
     * 从选区移除单个方块
     */
    private void removeBlockFromSelection(net.minecraft.util.math.BlockPos pos) {
        if (pos == null) return;
        selectedBlocks.remove(pos.toImmutable());
        updateSelectionToolFromBlocks();
    }
    
    /**
     * 设置框选区域
     */
    private void setBoxSelection(net.minecraft.util.math.BlockPos start, net.minecraft.util.math.BlockPos end) {
        if (start == null || end == null) return;
        
        // 清空现有选区
        selectedBlocks.clear();
        
        // 计算边界
        int minX = Math.min(start.getX(), end.getX());
        int minY = Math.min(start.getY(), end.getY());
        int minZ = Math.min(start.getZ(), end.getZ());
        int maxX = Math.max(start.getX(), end.getX());
        int maxY = Math.max(start.getY(), end.getY());
        int maxZ = Math.max(start.getZ(), end.getZ());
        
        // 添加所有方块
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    selectedBlocks.add(new net.minecraft.util.math.BlockPos(x, y, z));
                }
            }
        }
        
        // 同步到 SelectionTool（保持兼容）
        SelectionTool.INSTANCE.setSelection(
            new net.minecraft.util.math.BlockPos(minX, minY, minZ),
            new net.minecraft.util.math.BlockPos(maxX, maxY, maxZ)
        );
    }
    
    /**
     * 更新 SelectionTool 以匹配 selectedBlocks
     */
    private void updateSelectionToolFromBlocks() {
        if (selectedBlocks.isEmpty()) {
            SelectionTool.INSTANCE.clearSelection();
            return;
        }
        
        // 计算边界
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        
        for (net.minecraft.util.math.BlockPos pos : selectedBlocks) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        
        // 更新 SelectionTool
        SelectionTool.INSTANCE.setSelection(
            new net.minecraft.util.math.BlockPos(minX, minY, minZ),
            new net.minecraft.util.math.BlockPos(maxX, maxY, maxZ)
        );
    }
    
    /**
     * 设置锚点
     */
    private void setAnchor(net.minecraft.util.math.BlockPos pos) {
        if (pos == null) return;
        
        var st = ComponentTool.INSTANCE.getState();
        st.anchorWorld = pos.toImmutable();
        st.pickingAnchor = false;
        
        System.out.println("[ComponentCapturePanel] 设置锚点: " + pos);
        com.formacraft.client.ui.toast.HudToast.show("✓ 锚点已设置: " + pos.toShortString());
    }
    
    /**
     * 清除选区
     */
    public void clearSelection() {
        selectedBlocks.clear();
        boxStart = null;
        boxEnd = null;
        isDragging = false;
        SelectionTool.INSTANCE.clearSelection();
        System.out.println("[ComponentCapturePanel] 清除选区");
    }
    
    /**
     * 是否正在选择
     */
    public boolean isSelecting() {
        return isDragging || selectionMode != ComponentSelectionMode.BOX_SELECT;
    }
    
    /**
     * Tick 方法 - 更新 SelectionTool
     */
    public void tick() {
        // 框选模式：让 SelectionTool 处理拖拽
        if (selectionMode == ComponentSelectionMode.BOX_SELECT && SelectionTool.INSTANCE.isSelecting()) {
            SelectionTool.INSTANCE.tick();
        }
    }
    
    /**
     * 渲染世界中的选区预览
     * 从 SelectionBoxRenderMixin 调用
     */
    public void renderWorldSelection(com.formacraft.client.tool.ToolWorldRenderContext ctx) {
        // 渲染 SelectionTool 的选区（框选和已完成的选区）
        SelectionTool.INSTANCE.renderWorld(ctx);
        
        // 渲染点选模式下的单个方块高亮
        if (selectionMode == ComponentSelectionMode.POINT_SELECT && !selectedBlocks.isEmpty()) {
            for (net.minecraft.util.math.BlockPos pos : selectedBlocks) {
                renderBlockHighlight(ctx, pos, 0.0f, 1.0f, 0.0f, 0.3f); // 绿色高亮
            }
        }
    }
    
    /**
     * 渲染单个方块的高亮边框
     */
    private void renderBlockHighlight(com.formacraft.client.tool.ToolWorldRenderContext ctx, 
                                      net.minecraft.util.math.BlockPos pos,
                                      float r, float g, float b, float a) {
        net.minecraft.util.math.Box worldBox = new net.minecraft.util.math.Box(
                pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1
        ).expand(0.01);
        
        net.minecraft.util.math.Box box = worldBox.offset(-ctx.cameraX, -ctx.cameraY, -ctx.cameraZ);
        net.minecraft.client.render.VertexRendering.drawBox(ctx.matrices.peek(), ctx.vertexConsumer, box, r, g, b, a);
    }
}
