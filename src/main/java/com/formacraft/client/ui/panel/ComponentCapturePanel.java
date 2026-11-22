package com.formacraft.client.ui.panel;

import com.formacraft.client.ui.panel.capture.ComponentCaptureActionRouter;
import com.formacraft.client.ui.panel.capture.ComponentCapturePanelActions;
import com.formacraft.client.ui.panel.capture.ComponentCaptureLayoutRenderer;
import com.formacraft.client.ui.panel.capture.ComponentCaptureSubmitter;

import com.formacraft.client.ui.UiTheme;
import com.formacraft.client.ui.panel.capture.ComponentCaptureAiExplanation;
import com.formacraft.client.ui.panel.capture.ComponentCapturePhaseHeaders;
import com.formacraft.client.ui.panel.capture.ComponentCaptureHealthCoordinator;
import com.formacraft.client.ui.panel.capture.ComponentCaptureOrientationController;
import com.formacraft.client.ui.panel.capture.ComponentCaptureSelectionController;
import com.formacraft.client.ui.panel.capture.ComponentCaptureAiGuardHost;
import com.formacraft.client.ui.panel.capture.ComponentCaptureSemanticHost;
import com.formacraft.client.ui.panel.capture.ComponentCaptureSemanticPreview;
import com.formacraft.client.ui.panel.capture.ComponentCaptureThumbnailService;
import com.formacraft.client.ui.panel.capture.ComponentCaptureWorldOverlay;
import com.formacraft.client.ui.widget.HudTextInput;
import net.minecraft.client.gui.widget.ButtonWidget;

/**
 * 构件拾取面板（独立面板）
 * <p>
 * 职责：
 * - 显示当前选区预览
 * - 配置构件的所有参数（基础信息、锚点朝向、语义标注、连接位）
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
public class ComponentCapturePanel extends BasePanel implements ComponentCaptureSemanticHost, ComponentCaptureAiGuardHost {
    public static final int CONTENT_PADDING = UiTheme.CONTENT_PADDING;
    public static final int LABEL_OFFSET = 18;
    public static final int BUTTON_HEIGHT = 16;
    public static final int THUMBNAIL_SIZE = 120; // 增大显示尺寸以更好地展示构件细节
    public static final int FIELD_SPACING = 28;
    public static final int STICKY_FOOTER_HEIGHT = 22;

    // 输入框
    public final HudTextInput nameInput = new HudTextInput();
    public final HudTextInput tagsInput = new HudTextInput();
    public final HudTextInput socketIdInput = new HudTextInput();
    public final com.formacraft.client.ui.panel.capture.ComponentCaptureTypeSection typeSection =
            new com.formacraft.client.ui.panel.capture.ComponentCaptureTypeSection(this::typeChanged);
    public java.util.List<ButtonWidget> captureButtons = java.util.List.of();

    // 基础信息按钮
    public ButtonWidget categoryButton;
    public ButtonWidget pickAnchorButton;
    public ButtonWidget clearAnchorButton;
    public ButtonWidget hostFaceButton;
    public ButtonWidget anchorOutsideButton;
    public ButtonWidget autoAnchorButton;
    public ButtonWidget facingButton;
    public ButtonWidget mirrorButton;

    // 语义标注按钮
    public ButtonWidget semanticSkinButton;
    public ButtonWidget semanticTagOnSaveButton;
    public ButtonWidget semanticStyleButton;
    public ButtonWidget semanticPartButton;

    // 连接位配置按钮
    public ButtonWidget socketContextButton;
    public ButtonWidget socketPickOriginButton;
    public ButtonWidget socketFacingButton;
    public ButtonWidget socketAddButton;
    public ButtonWidget socketPreviewButton;
    public ButtonWidget socketClearButton;

    // 智能分析按钮
    public ButtonWidget autoFixButton;
    public ButtonWidget undoAutoFixButton;

    // 底部按钮
    public ButtonWidget cancelButton;
    public ButtonWidget saveButton;
    
    // 选择工具按钮
    public ButtonWidget boxSelectButton;
    public ButtonWidget pointSelectButton;
    public ButtonWidget clearSelectionButton;
    
    // Phase 3: 语义配置按钮
    public ButtonWidget advancedOptionsButton;
    public ButtonWidget attachmentModeButton;
    public ButtonWidget directionalityButton;
    public ButtonWidget culturalStyleButton;
    public ButtonWidget geometryArchetypeButton;
    public ButtonWidget setInsideButton;
    public ButtonWidget setOutsideButton;
    public ButtonWidget setBottomButton;
    public ButtonWidget setTopButton;

    // 输入框边界（用于点击检测）
    public int nameInputX, nameInputY, nameInputW, nameInputH;
    public int tagsInputX, tagsInputY, tagsInputW, tagsInputH;
    public int socketIdInputX, socketIdInputY, socketIdInputW, socketIdInputH;
    public boolean nameInputBoundsValid = false;
    public boolean tagsInputBoundsValid = false;
    public boolean socketIdInputBoundsValid = false;

    public final ComponentCaptureSelectionController selectionController = new ComponentCaptureSelectionController();
    public final com.formacraft.client.ui.panel.capture.ComponentCaptureAdaptationSection adaptationSection =
            new com.formacraft.client.ui.panel.capture.ComponentCaptureAdaptationSection();
    public final ComponentCaptureThumbnailService thumbnailService = new ComponentCaptureThumbnailService();
    public final ComponentCaptureOrientationController orientationController = new ComponentCaptureOrientationController();
    public final ComponentCaptureHealthCoordinator healthCoordinator;
    public final ComponentCaptureSemanticPreview semanticPreview = new ComponentCaptureSemanticPreview();
    public final ComponentCaptureAiExplanation aiExplanation;
    public final ComponentCaptureWorldOverlay worldOverlay;

    // 滚动
    public int scrollY = 0;
    public int maxScrollY = 0;
    
    // Phase 3: 语义配置状态
    public com.formacraft.common.component.placement.AttachmentType attachmentMode = 
        com.formacraft.common.component.placement.AttachmentType.NONE;
    
    // 阶段感知状态
    public CapturePhase currentPhase = CapturePhase.SELECTION;
    public boolean[] phaseCollapsed = new boolean[CapturePhase.getTotalPhases()];
    public final ComponentCapturePhaseHeaders phaseHeaders = new ComponentCapturePhaseHeaders();
    public CapturePhase lastAutoCollapsePhase = null;
    public boolean advancedOptionsExpanded = false;
    
    // 调试开关
    public static final boolean DEBUG_CAPTURE = false; // 设置为 true 启用调试日志

    public net.minecraft.client.MinecraftClient client() { return client; }
    public void drawContentBackgroundPublic(net.minecraft.client.gui.DrawContext ctx) { drawContentBackground(ctx); }
    public int contentScissorLeftPublic() { return contentScissorLeft(); }
    public int contentScissorRightPublic() { return contentScissorRight(); }
    public void drawTooltipCompatPublic(net.minecraft.client.gui.DrawContext ctx, java.util.List<net.minecraft.text.Text> lines, int mx, int my) { drawTooltipCompat(ctx, lines, mx, my); }
    public boolean superMouseClicked(double x, double y, int button) { return super.mouseClicked(x, y, button); }
    @Override protected void drawContents(net.minecraft.client.gui.DrawContext ctx) { ComponentCaptureLayoutRenderer.drawContents(this, ctx); }
    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) { return ComponentCaptureActionRouter.mouseClicked(this, mouseX, mouseY, button); }
    @Override public void mouseScrolled(double mouseX, double mouseY, double amount) { ComponentCaptureActionRouter.mouseScrolled(this, mouseX, mouseY, amount); }
    @Override public void keyPressed(int keyCode, int scanCode, int modifiers) { ComponentCaptureActionRouter.keyPressed(this, keyCode, scanCode, modifiers); }
    @Override public void charTyped(char chr) { ComponentCaptureActionRouter.charTyped(this, chr); }
    public boolean handleWorldClick(net.minecraft.util.hit.BlockHitResult hit, int button) { return ComponentCaptureActionRouter.handleWorldClick(this, hit, button); }
    public void handleWorldRelease(int button) { ComponentCaptureActionRouter.handleWorldRelease(this, button); }

    public ComponentCapturePanel() {
        nameInput.setMaxLength(64);
        nameInput.setPlaceholder("输入构件名称（如：橡木门）");
        
        tagsInput.setMaxLength(256);
        tagsInput.setPlaceholder("输入标签，用逗号分隔（如：wood, modern）");
        
        socketIdInput.setMaxLength(64);
        socketIdInput.setPlaceholder("输入连接位 ID（如：door_frame）");

        selectionController.setOnSelectionChanged(() -> { thumbnailService.invalidate(); adaptationSection.invalidate(); });
        healthCoordinator = new ComponentCaptureHealthCoordinator(selectionController);
        aiExplanation = new ComponentCaptureAiExplanation(semanticPreview, orientationController);
        worldOverlay = new ComponentCaptureWorldOverlay(selectionController);
    }

    
    /**
     * 获取附着模式的显示名称
     */
    @Override public String getAttachmentModeDisplay() { return ComponentCapturePanelActions.getAttachmentModeDisplay(this); }
    
    /**
     * 循环切换附着模式
     */
    public void cycleAttachmentMode() { ComponentCapturePanelActions.cycleAttachmentMode(this); }
    
    /**
     * 循环切换方向性模式
     */
    public void cycleDirectionality() { ComponentCapturePanelActions.cycleDirectionality(this); }

    /**
     * 同步 UI 的附着/方向性提示到 ComponentToolState
     */
    public void syncPlacementHintsToState() { ComponentCapturePanelActions.syncPlacementHintsToState(this); }

    /**
     * 设置选择模式
     */
    public void setSelectionMode(ComponentSelectionMode mode) {
        selectionController.setMode(mode);
    }

    public void typeChanged() { ComponentCapturePanelActions.typeChanged(this); }
    
    /**
     * 计算当前应该处于的阶段
     */
    
    /**
     * 检查阶段是否完成
     */

    public void cycleFacing() { ComponentCapturePanelActions.cycleFacing(this); }

    public void cycleMirror() { ComponentCapturePanelActions.cycleMirror(this); }

    @Override
    public com.formacraft.common.component.placement.AttachmentType getAttachmentMode() {
        return attachmentMode;
    }

    @Override
    public void setNameInputBounds(int x, int y, int w, int h) {
        nameInputX = x;
        nameInputY = y;
        nameInputW = w;
        nameInputH = h;
        nameInputBoundsValid = true;
    }

    @Override
    public void setTagsInputBounds(int x, int y, int w, int h) {
        tagsInputX = x;
        tagsInputY = y;
        tagsInputW = w;
        tagsInputH = h;
        tagsInputBoundsValid = true;
    }

    @Override
    public void setSocketIdInputBounds(int x, int y, int w, int h) {
        socketIdInputX = x;
        socketIdInputY = y;
        socketIdInputW = w;
        socketIdInputH = h;
        socketIdInputBoundsValid = true;
    }

    @Override public void updateTagsFromInput() { ComponentCapturePanelActions.updateTagsFromInput(this); }

    @Override
    public boolean isAdvancedOptionsExpanded() {
        return advancedOptionsExpanded;
    }

    public boolean canSaveWithoutHealthCheck() { return ComponentCaptureSubmitter.canSaveWithoutHealthCheck(this); }

    @Override
    public boolean wantsKeyboardInput() {
        return typeSection.focused() || nameInput.isFocused() || tagsInput.isFocused() || socketIdInput.isFocused();
    }

    @Override
    public void blurKeyboardFocus() {
        typeSection.unfocus();
        nameInput.setFocused(false);
        tagsInput.setFocused(false);
        socketIdInput.setFocused(false);
    }

    public int getScaledMouseX() {
        return (int) (client.mouse.getX() / client.getWindow().getScaleFactor());
    }

    public int getScaledMouseY() {
        return (int) (client.mouse.getY() / client.getWindow().getScaleFactor());
    }
    
    // ============ 世界交互方法 ============
    
    /**
     * 处理世界点击（从 InputRouter 调用）
     * @return true 如果事件被处理
     */
    
    /**
     * 处理鼠标释放（从 InputRouter 调用）
     */

    /**
     * 设置锚点
     */
    public void setAnchor(net.minecraft.util.math.BlockPos pos) { ComponentCapturePanelActions.setAnchor(this, pos); }

    public void setAutoAnchor() { ComponentCapturePanelActions.setAutoAnchor(this); }
    
    public void clearSelection() { ComponentCapturePanelActions.clearSelection(this); }

    public void tick() { ComponentCapturePanelActions.tick(this); }

    public void renderWorldSelection(com.formacraft.client.tool.ToolWorldRenderContext ctx) {
        worldOverlay.render(ctx);
    }

    /**
     * 将当前 state 同步到面板本地缓存（用于进入捕获态时回填）。
     */
    public void syncLocalFromState() { ComponentCapturePanelActions.syncLocalFromState(this); }
}
