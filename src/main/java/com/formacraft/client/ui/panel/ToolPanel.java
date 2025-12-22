package com.formacraft.client.ui.panel;

import com.formacraft.client.tool.OutlineTool;
import com.formacraft.client.tool.ProtectedZoneTool;
import com.formacraft.client.tool.SemanticLabelTool;
import com.formacraft.client.tool.SelectionTool;
import com.formacraft.client.tool.SymmetryTool;
import com.formacraft.client.tool.ToolManager;
import com.formacraft.client.ui.widget.HudTextInput;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Tools 面板：选择/管理工具（首个工具：区域选区）。
 */
public class ToolPanel extends BasePanel {

    private static final int CONTENT_PADDING = 10;
    private static final int BUTTON_HEIGHT = 16;
    private static final int LABEL_OFFSET = 18; // 与 SettingsPanel 一致的紧凑行距
    private static final int FIELD_SPACING = LABEL_OFFSET * 2;

    private final MinecraftClient client = MinecraftClient.getInstance();

    private ButtonWidget selectionToolButton;
    private ButtonWidget clearSelectionButton;
    private ButtonWidget protectedZoneToolButton;
    private ButtonWidget clearProtectedZonesButton;
    private ButtonWidget outlineToolButton;
    private ButtonWidget outlineModeButton;
    private ButtonWidget clearOutlineButton;
    private ButtonWidget symmetryToolButton;
    private ButtonWidget symmetryModeButton;
    private ButtonWidget clearSymmetryButton;
    private ButtonWidget semanticLabelToolButton;
    private ButtonWidget clearLabelsButton;

    private final HudTextInput labelNameInput = new HudTextInput();

    private void ensureWidgets() {
        if (selectionToolButton != null) return;

        selectionToolButton = ButtonWidget.builder(Text.literal("选区工具"), b -> {
                    ToolManager.setTool(SelectionTool.INSTANCE.getId());
                })
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("两点框选一个区域")))
                .build();

        clearSelectionButton = ButtonWidget.builder(Text.literal("清除选区"), b -> SelectionTool.INSTANCE.clear())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("清除当前已选定的区域")))
                .build();

        protectedZoneToolButton = ButtonWidget.builder(Text.literal("禁区/保护区"), b -> {
                    ToolManager.setTool(ProtectedZoneTool.INSTANCE.getId());
                })
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("两点框选：添加一个禁区（红色斜线）")))
                .build();

        clearProtectedZonesButton = ButtonWidget.builder(Text.literal("清空禁区"), b -> ProtectedZoneTool.INSTANCE.clearZones())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("清空所有已添加的禁区/保护区")))
                .build();

        outlineToolButton = ButtonWidget.builder(Text.literal("轮廓工具"), b -> {
                    ToolManager.setTool(OutlineTool.INSTANCE.getId());
                })
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("绘制建筑轮廓（紫色填充）")))
                .build();

        outlineModeButton = ButtonWidget.builder(Text.literal("模式：POLYGON"), b -> {
                    OutlineTool.INSTANCE.cycleMode();
                })
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("切换轮廓绘制模式")))
                .build();

        clearOutlineButton = ButtonWidget.builder(Text.literal("清空轮廓"), b -> OutlineTool.INSTANCE.clearShape())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("清除当前轮廓")))
                .build();

        symmetryToolButton = ButtonWidget.builder(Text.literal("对称/镜像"), b -> {
                    ToolManager.setTool(SymmetryTool.INSTANCE.getId());
                })
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("定义对称/镜像约束")))
                .build();

        symmetryModeButton = ButtonWidget.builder(Text.literal("模式：NONE"), b -> {
                    SymmetryTool.INSTANCE.cycleMode();
                })
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("切换对称模式（自定义轴线可两点标注）")))
                .build();

        clearSymmetryButton = ButtonWidget.builder(Text.literal("清空对称轴"), b -> SymmetryTool.INSTANCE.clearAxis())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("清除自定义轴线（不影响预设模式）")))
                .build();

        semanticLabelToolButton = ButtonWidget.builder(Text.literal("语义标注"), b -> {
                    ToolManager.setTool(SemanticLabelTool.INSTANCE.getId());
                })
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("左键加点 / 右键结束：创建一个多边形语义区域")))
                .build();

        clearLabelsButton = ButtonWidget.builder(Text.literal("清空标签"), b -> SemanticLabelTool.INSTANCE.clearLabels())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("清空所有区域语义标签")))
                .build();

        labelNameInput.setMaxLength(64);
        labelNameInput.setText("入口");
    }

    @Override
    protected void drawContents(DrawContext ctx) {
        ensureWidgets();

        int x = panelX + CONTENT_PADDING;
        int y = getContentY() + CONTENT_PADDING;
        int w = panelWidth - CONTENT_PADDING * 2;

        // 半透明底
        ctx.fill(panelX + 1, getContentY(), panelX + panelWidth - 1, panelY + panelHeight - 1, 0x80101010);

        ctx.drawTextWithShadow(client.textRenderer, Text.literal("Tools"), x, y, 0xFFFFFFFF);
        y += 20;

        // 选区工具
        selectionToolButton.setPosition(x, y);
        selectionToolButton.setWidth(w);
        selectionToolButton.visible = true;
        selectionToolButton.active = true;
        selectionToolButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);

        y += FIELD_SPACING;

        // 选区状态
        String status;
        if (!ToolManager.isActive(SelectionTool.INSTANCE.getId())) {
            status = "当前工具：无";
        } else if (SelectionTool.INSTANCE.isSelecting()) {
            status = "选区中：点击设置起点/终点";
        } else if (SelectionTool.INSTANCE.hasSelection()) {
            var min = SelectionTool.INSTANCE.getMin();
            var max = SelectionTool.INSTANCE.getMax();
            int dx = (max.getX() - min.getX() + 1);
            int dy = (max.getY() - min.getY() + 1);
            int dz = (max.getZ() - min.getZ() + 1);
            status = "Selection: " + dx + " x " + dy + " x " + dz;
        } else {
            status = "提示：左键点方块设置起点，再点一次设置终点";
        }
        ctx.drawTextWithShadow(client.textRenderer, Text.literal(status), x, y, 0xFFAAAAAA);

        y += LABEL_OFFSET;

        clearSelectionButton.setPosition(x, y);
        clearSelectionButton.setWidth(w);
        clearSelectionButton.visible = true;
        clearSelectionButton.active = true;
        clearSelectionButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);

        // --------------------
        // 禁区/保护区
        // --------------------
        y += FIELD_SPACING;
        protectedZoneToolButton.setPosition(x, y);
        protectedZoneToolButton.setWidth(w);
        protectedZoneToolButton.visible = true;
        protectedZoneToolButton.active = true;
        protectedZoneToolButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);

        y += LABEL_OFFSET;
        ctx.drawTextWithShadow(client.textRenderer,
                Text.literal("已添加禁区：" + ProtectedZoneTool.INSTANCE.getZones().size()),
                x, y, 0xFFAAAAAA);
        y += LABEL_OFFSET;

        clearProtectedZonesButton.setPosition(x, y);
        clearProtectedZonesButton.setWidth(w);
        clearProtectedZonesButton.visible = true;
        clearProtectedZonesButton.active = true;
        clearProtectedZonesButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);

        // --------------------
        // 轮廓工具
        // --------------------
        y += FIELD_SPACING;
        outlineToolButton.setPosition(x, y);
        outlineToolButton.setWidth(w);
        outlineToolButton.visible = true;
        outlineToolButton.active = true;
        outlineToolButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);

        y += LABEL_OFFSET;
        outlineModeButton.setMessage(Text.literal("模式：" + OutlineTool.INSTANCE.getMode().name()));
        outlineModeButton.setPosition(x, y);
        outlineModeButton.setWidth(w);
        outlineModeButton.visible = true;
        outlineModeButton.active = true;
        outlineModeButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);

        y += LABEL_OFFSET;
        ctx.drawTextWithShadow(client.textRenderer,
                Text.literal(OutlineTool.INSTANCE.hasShape() ? "轮廓：已完成（紫色区域）" :
                        (OutlineTool.INSTANCE.isDrafting() ? "轮廓：绘制中（右键结束）" : "轮廓：未设置")),
                x, y, 0xFFAAAAAA);
        y += LABEL_OFFSET;

        clearOutlineButton.setPosition(x, y);
        clearOutlineButton.setWidth(w);
        clearOutlineButton.visible = true;
        clearOutlineButton.active = true;
        clearOutlineButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);

        // --------------------
        // 对称/镜像
        // --------------------
        y += FIELD_SPACING;
        symmetryToolButton.setPosition(x, y);
        symmetryToolButton.setWidth(w);
        symmetryToolButton.visible = true;
        symmetryToolButton.active = true;
        symmetryToolButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);

        y += LABEL_OFFSET;
        symmetryModeButton.setMessage(Text.literal("模式：" + SymmetryTool.INSTANCE.getMode().name()));
        symmetryModeButton.setPosition(x, y);
        symmetryModeButton.setWidth(w);
        symmetryModeButton.visible = true;
        symmetryModeButton.active = true;
        symmetryModeButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);

        y += LABEL_OFFSET;
        clearSymmetryButton.setPosition(x, y);
        clearSymmetryButton.setWidth(w);
        clearSymmetryButton.visible = true;
        clearSymmetryButton.active = true;
        clearSymmetryButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);

        // --------------------
        // 语义标注
        // --------------------
        y += FIELD_SPACING;
        semanticLabelToolButton.setPosition(x, y);
        semanticLabelToolButton.setWidth(w);
        semanticLabelToolButton.visible = true;
        semanticLabelToolButton.active = true;
        semanticLabelToolButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);

        y += LABEL_OFFSET;
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("标签名："), x, y, 0xFFAAAAAA);
        y += LABEL_OFFSET - 2;
        labelNameInput.render(ctx, x, y, w, 14);
        // 让工具实时拿到最新标签名
        SemanticLabelTool.INSTANCE.setPendingName(labelNameInput.getText());

        y += LABEL_OFFSET + 2;
        ctx.drawTextWithShadow(client.textRenderer,
                Text.literal("已添加标签：" + SemanticLabelTool.INSTANCE.getLabels().size()),
                x, y, 0xFFAAAAAA);
        y += LABEL_OFFSET;

        clearLabelsButton.setPosition(x, y);
        clearLabelsButton.setWidth(w);
        clearLabelsButton.visible = true;
        clearLabelsButton.active = true;
        clearLabelsButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);
    }

    private double getScaledMouseX() {
        return client.mouse.getX() / client.getWindow().getScaleFactor();
    }

    private double getScaledMouseY() {
        return client.mouse.getY() / client.getWindow().getScaleFactor();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0) return false;
        if (!isMouseOver(mouseX, mouseY)) return false;

        ensureWidgets();
        int x = panelX + CONTENT_PADDING;
        int y = getContentY() + CONTENT_PADDING + 20;
        int w = panelWidth - CONTENT_PADDING * 2;

        net.minecraft.client.gui.Click click = new net.minecraft.client.gui.Click(mouseX, mouseY, new net.minecraft.client.input.MouseInput(button, 0));

        selectionToolButton.setPosition(x, y);
        selectionToolButton.setWidth(w);
        if (selectionToolButton.mouseClicked(click, false)) return true;

        y += FIELD_SPACING + LABEL_OFFSET;
        clearSelectionButton.setPosition(x, y);
        clearSelectionButton.setWidth(w);

        if (clearSelectionButton.mouseClicked(click, false)) return true;

        // 禁区
        y += FIELD_SPACING;
        protectedZoneToolButton.setPosition(x, y);
        protectedZoneToolButton.setWidth(w);
        if (protectedZoneToolButton.mouseClicked(click, false)) return true;

        y += LABEL_OFFSET * 2;
        clearProtectedZonesButton.setPosition(x, y);
        clearProtectedZonesButton.setWidth(w);
        if (clearProtectedZonesButton.mouseClicked(click, false)) return true;

        // 轮廓
        y += FIELD_SPACING;
        outlineToolButton.setPosition(x, y);
        outlineToolButton.setWidth(w);
        if (outlineToolButton.mouseClicked(click, false)) return true;

        y += LABEL_OFFSET;
        outlineModeButton.setPosition(x, y);
        outlineModeButton.setWidth(w);
        if (outlineModeButton.mouseClicked(click, false)) return true;

        y += LABEL_OFFSET * 2;
        clearOutlineButton.setPosition(x, y);
        clearOutlineButton.setWidth(w);
        if (clearOutlineButton.mouseClicked(click, false)) return true;

        // 对称
        y += FIELD_SPACING;
        symmetryToolButton.setPosition(x, y);
        symmetryToolButton.setWidth(w);
        if (symmetryToolButton.mouseClicked(click, false)) return true;

        y += LABEL_OFFSET;
        symmetryModeButton.setPosition(x, y);
        symmetryModeButton.setWidth(w);
        if (symmetryModeButton.mouseClicked(click, false)) return true;

        y += LABEL_OFFSET;
        clearSymmetryButton.setPosition(x, y);
        clearSymmetryButton.setWidth(w);
        if (clearSymmetryButton.mouseClicked(click, false)) return true;

        // 语义标注
        y += FIELD_SPACING;
        semanticLabelToolButton.setPosition(x, y);
        semanticLabelToolButton.setWidth(w);
        if (semanticLabelToolButton.mouseClicked(click, false)) return true;

        y += LABEL_OFFSET; // “标签名：”
        y += LABEL_OFFSET - 2; // 输入框 y（与 drawContents 一致）
        // labelNameInput
        if (labelNameInput.mouseClicked(mouseX, mouseY, x, y, w, 14)) return true;

        y += LABEL_OFFSET * 2;
        clearLabelsButton.setPosition(x, y);
        clearLabelsButton.setWidth(w);
        return clearLabelsButton.mouseClicked(click, false);
    }

    @Override
    public void keyPressed(int keyCode, int scanCode, int modifiers) {
        ensureWidgets();
        // 仅当输入框 focused 时消费键盘
        if (labelNameInput.keyPressed(keyCode, modifiers)) {
            SemanticLabelTool.INSTANCE.setPendingName(labelNameInput.getText());
        }
    }

    @Override
    public void charTyped(char chr) {
        ensureWidgets();
        if (labelNameInput.charTyped(chr)) {
            SemanticLabelTool.INSTANCE.setPendingName(labelNameInput.getText());
        }
    }

    @Override
    public boolean wantsKeyboardInput() {
        return labelNameInput.isFocused();
    }
}

