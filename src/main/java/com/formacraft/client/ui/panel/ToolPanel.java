package com.formacraft.client.ui.panel;

import com.formacraft.client.tool.SemanticLabelTool;
import com.formacraft.client.tool.ToolManager;
import com.formacraft.client.tool.WorkPlaneTool;
import com.formacraft.client.ui.widget.HudClickSupport;
import com.formacraft.client.ui.widget.HudTextInput;
import com.formacraft.client.ui.UiTheme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tools 面板：选择/管理工具（首个工具：区域选区）。
 */
public class ToolPanel extends BasePanel {
    public boolean superMouseClicked(double x, double y, int button) { return super.mouseClicked(x, y, button); }
    public boolean superMouseReleased(double x, double y, int button) { return super.mouseReleased(x, y, button); }


    public static final int CONTENT_PADDING = UiTheme.CONTENT_PADDING;
    public static final int BUTTON_HEIGHT = 16;
    public static final int LABEL_OFFSET = 18; // 与 SettingsPanel 一致的紧凑行距
    public static final int FIELD_SPACING = LABEL_OFFSET * 2;

    public final MinecraftClient client = MinecraftClient.getInstance();

    // 工具列表（插件化）：动态生成按钮
    public ButtonWidget noneToolButton;
    public final Map<String, ButtonWidget> toolButtons = new LinkedHashMap<>();
    public final Map<String, Text> toolBaseLabels = new LinkedHashMap<>();
    public int lastToolCount = -1;

    // 内建工具附加操作按钮
    public ButtonWidget clearAnchorButton;
    public ButtonWidget clearSelectionButton;
    public ButtonWidget clearProtectedZonesButton;
    public ButtonWidget outlineModeButton;
    public ButtonWidget clearOutlineButton;
    public ButtonWidget clearPathsButton;
    public ButtonWidget pathDrawingModeButton, pathRadiusMinusButton, pathRadiusPlusButton;
    public ButtonWidget pathSelectButton, pathRemoveButton, pathIntentButton;
    public ButtonWidget brushUndoButton, brushRedoButton;
    public ButtonWidget brushModeButton;
    public ButtonWidget brushRadiusMinusButton;
    public ButtonWidget brushRadiusPlusButton;
    public ButtonWidget clearBrushSelectionButton;
    public ButtonWidget symmetryModeButton;
    public ButtonWidget clearSymmetryButton;
    public ButtonWidget clearLabelsButton;

    public final HudTextInput labelNameInput = new HudTextInput();
    public final HudTextInput planeHeightInput = new HudTextInput();
    public ButtonWidget planeLockButton, planeModeButton, planeDownButton, planeUpButton, planeApplyButton, planeClearButton;
    public int planeInputX, planeInputY, planeInputW;
    public boolean planeInputVisible;


    // 标签“作用范围”滑动条（1~40）——参考 SettingsPanel 的 SliderWidget 实现
    public static final int LABEL_RANGE_MIN = 1;
    public static final int LABEL_RANGE_MAX = 40;
    public LabelRangeSlider labelRangeSlider;
    public SliderWidget activeSlider = null; // 只允许同时操作一个滑条（与 SettingsPanel 一致）

    // 由于 HudTextInput 不保存自己的 bounds，我们在 drawContents() 里缓存其位置，mouseClicked() 直接复用，避免布局重复计算造成偏移
    public int labelNameInputX = 0;
    public int labelNameInputY = 0;
    public int labelNameInputW = 0;
    public int labelNameInputH = 0;
    public boolean labelNameInputBoundsValid = false;




    // 滚动（仅用于选项区域）
    public int scrollY = 0;
    public int maxScrollY = 0;
    public int separatorY = 0; // 分隔线的 Y 坐标

    public void ensureWidgets() { com.formacraft.client.ui.panel.tool.ToolWidgetBuilder.ensureWidgets(this); }

    @Override
    protected void drawContents(DrawContext ctx) { com.formacraft.client.ui.panel.tool.ToolLayoutRenderer.drawContents(this, ctx); }

    public void applyPlaneHeight() { com.formacraft.client.ui.panel.tool.ToolLayoutRenderer.applyPlaneHeight(this); }

    public int drawWorkPlaneOptions(DrawContext ctx, int x, int y, int w) { return com.formacraft.client.ui.panel.tool.ToolLayoutRenderer.drawWorkPlaneOptions(this, ctx, x, y, w); }

    // 绘制 SelectionTool 的选项
    public int drawSelectionToolOptions(DrawContext ctx, int x, int y, int w) { return com.formacraft.client.ui.panel.tool.ToolLayoutRenderer.drawSelectionToolOptions(this, ctx, x, y, w); }

    public static @NotNull String getString() { return com.formacraft.client.ui.panel.tool.ToolLayoutRenderer.getString(); }

    // 绘制 ProtectedZoneTool 的选项
    public int drawProtectedZoneToolOptions(DrawContext ctx, int x, int y, int w) { return com.formacraft.client.ui.panel.tool.ToolLayoutRenderer.drawProtectedZoneToolOptions(this, ctx, x, y, w); }

    // 绘制 OutlineTool 的选项
    public int drawOutlineToolOptions(DrawContext ctx, int x, int y, int w) { return com.formacraft.client.ui.panel.tool.ToolLayoutRenderer.drawOutlineToolOptions(this, ctx, x, y, w); }

    public ButtonWidget optionButton(String label, Runnable action) { return com.formacraft.client.ui.panel.tool.ToolLayoutRenderer.optionButton(this, label, action); }

    public int drawOptionButton(DrawContext ctx, ButtonWidget button, int x, int y, int w, boolean enabled) { return com.formacraft.client.ui.panel.tool.ToolLayoutRenderer.drawOptionButton(this, ctx, button, x, y, w, enabled); }

    // 绘制 PathTool 的选项
    public int drawPathToolOptions(DrawContext ctx, int x, int y, int w) { return com.formacraft.client.ui.panel.tool.ToolLayoutRenderer.drawPathToolOptions(this, ctx, x, y, w); }

    // 绘制 BrushTool 的选项
    public int drawBrushToolOptions(DrawContext ctx, int x, int y, int w) { return com.formacraft.client.ui.panel.tool.ToolLayoutRenderer.drawBrushToolOptions(this, ctx, x, y, w); }

    // 绘制 SymmetryTool 的选项
    public int drawSymmetryToolOptions(DrawContext ctx, int x, int y, int w) { return com.formacraft.client.ui.panel.tool.ToolLayoutRenderer.drawSymmetryToolOptions(this, ctx, x, y, w); }

    // 绘制 SemanticLabelTool 的选项
    public int drawSemanticLabelToolOptions(DrawContext ctx, int x, int y, int w) { return com.formacraft.client.ui.panel.tool.ToolLayoutRenderer.drawSemanticLabelToolOptions(this, ctx, x, y, w); }

    /**
     * 绘制可换行的文字（当文字超出指定宽度时自动换行）
     * @param ctx 绘制上下文
     * @param text 要绘制的文字
     * @param x 起始 X 坐标
     * @param y 起始 Y 坐标
     * @param maxWidth 最大宽度（超出此宽度将换行）
     * @param color 文字颜色
     * @return 绘制后的 Y 坐标（最后一行文字的下方）
     */
    public int drawWrappedText(DrawContext ctx, Text text, int x, int y, int maxWidth, int color) { return com.formacraft.client.ui.panel.tool.ToolLayoutRenderer.drawWrappedText(this, ctx, text, x, y, maxWidth, color); }

    public double getScaledMouseX() { return com.formacraft.client.ui.panel.tool.ToolLayoutRenderer.getScaledMouseX(this); }

    public double getScaledMouseY() { return com.formacraft.client.ui.panel.tool.ToolLayoutRenderer.getScaledMouseY(this); }

    public static boolean clickButton(ButtonWidget button, Click click) {
        return button != null && button.visible && HudClickSupport.click(button, click);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) { return com.formacraft.client.ui.panel.tool.ToolInteraction.mouseClicked(this, mouseX, mouseY, button); }

    @Override
    public void mouseScrolled(double mouseX, double mouseY, double amount) { com.formacraft.client.ui.panel.tool.ToolInteraction.mouseScrolled(this, mouseX, mouseY, amount); }

    @Override
    public void keyPressed(int keyCode, int scanCode, int modifiers) { com.formacraft.client.ui.panel.tool.ToolInteraction.keyPressed(this, keyCode, scanCode, modifiers); }

    @Override
    public void charTyped(char chr) { com.formacraft.client.ui.panel.tool.ToolInteraction.charTyped(this, chr); }

    public static final Text LABEL_RANGE_TOOLTIP =
            Text.literal("设置标签作用范围（方块），用于提示 AI 该标签影响的周边区域");

    @Override
    protected boolean drawCustomTooltip(DrawContext ctx, double mouseX, double mouseY) { return com.formacraft.client.ui.panel.tool.ToolInteraction.drawCustomTooltip(this, ctx, mouseX, mouseY); }

    @Override
    public boolean wantsKeyboardInput() {
        return ToolManager.isActive(SemanticLabelTool.INSTANCE.getId()) && labelNameInput.isFocused()
                || ToolManager.isActive(WorkPlaneTool.INSTANCE.getId()) && planeHeightInput.isFocused();
    }

    @Override
    public void blurKeyboardFocus() {
        labelNameInput.setFocused(false);
        planeHeightInput.setFocused(false);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) { return com.formacraft.client.ui.panel.tool.ToolInteraction.mouseDragged(this, mouseX, mouseY, button, deltaX, deltaY); }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) { return com.formacraft.client.ui.panel.tool.ToolInteraction.mouseReleased(this, mouseX, mouseY, button); }

    public static double rangeToValue(int range) {
        int clamped = Math.max(LABEL_RANGE_MIN, Math.min(LABEL_RANGE_MAX, range));
        return (clamped - LABEL_RANGE_MIN) / (double) (LABEL_RANGE_MAX - LABEL_RANGE_MIN);
    }

    public static int valueToRange(double value) {
        double v = Math.max(0.0, Math.min(1.0, value));
        return LABEL_RANGE_MIN + (int) Math.round(v * (LABEL_RANGE_MAX - LABEL_RANGE_MIN));
    }

    public static class LabelRangeSlider extends SliderWidget {
        public static final double HANDLE_PAD = 4.0;

        public LabelRangeSlider(int x, int y, int width, int height, Text message, double value) {
            super(x, y, width, height, message, value);
            updateMessage();
        }

        public void dragWithMouse(double mouseX) {
            double range = Math.max(1.0, this.getWidth() - 2.0 * HANDLE_PAD);
            double v = (mouseX - (this.getX() + HANDLE_PAD)) / range;
            this.value = Math.max(0.0, Math.min(1.0, v));
            applyValue();
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.valueOf(SemanticLabelTool.INSTANCE.getPendingRange())));
        }

        @Override
        protected void applyValue() {
            int r = valueToRange(this.value);
            SemanticLabelTool.INSTANCE.setPendingRange(r);
            updateMessage();
        }
    }
}

