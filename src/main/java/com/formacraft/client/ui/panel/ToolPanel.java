package com.formacraft.client.ui.panel;

import com.formacraft.client.tool.SelectionTool;
import com.formacraft.client.tool.ToolManager;
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

        // 工具选择按钮
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
        return clearSelectionButton.mouseClicked(click, false);
    }
}

