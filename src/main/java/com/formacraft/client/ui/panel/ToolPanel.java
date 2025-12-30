package com.formacraft.client.ui.panel;

import com.formacraft.client.tool.FormacraftTool;
import com.formacraft.client.tool.OutlineTool;
import com.formacraft.client.tool.PathTool;
import com.formacraft.client.tool.BrushTool;
import com.formacraft.client.tool.ProtectedZoneTool;
import com.formacraft.client.tool.SemanticLabelTool;
import com.formacraft.client.tool.SelectionTool;
import com.formacraft.client.tool.SymmetryTool;
import com.formacraft.client.tool.ToolManager;
import com.formacraft.client.interaction.AnchorState;
import com.formacraft.client.ui.widget.HudTextInput;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tools 面板：选择/管理工具（首个工具：区域选区）。
 */
public class ToolPanel extends BasePanel {

    private static final int CONTENT_PADDING = 10;
    private static final int BUTTON_HEIGHT = 16;
    private static final int LABEL_OFFSET = 18; // 与 SettingsPanel 一致的紧凑行距
    private static final int FIELD_SPACING = LABEL_OFFSET * 2;

    private final MinecraftClient client = MinecraftClient.getInstance();

    // 工具列表（插件化）：动态生成按钮
    private ButtonWidget noneToolButton;
    private final Map<String, ButtonWidget> toolButtons = new LinkedHashMap<>();
    private final Map<String, Text> toolBaseLabels = new LinkedHashMap<>();
    private int lastToolCount = -1;

    // 内建工具附加操作按钮
    private ButtonWidget clearAnchorButton;
    private ButtonWidget clearSelectionButton;
    private ButtonWidget clearProtectedZonesButton;
    private ButtonWidget outlineModeButton;
    private ButtonWidget clearOutlineButton;
    private ButtonWidget clearPathsButton;
    private ButtonWidget brushModeButton;
    private ButtonWidget brushRadiusMinusButton;
    private ButtonWidget brushRadiusPlusButton;
    private ButtonWidget clearBrushSelectionButton;
    private ButtonWidget symmetryModeButton;
    private ButtonWidget clearSymmetryButton;
    private ButtonWidget clearLabelsButton;

    private final HudTextInput labelNameInput = new HudTextInput();
    private final HudTextInput labelRangeInput = new HudTextInput();

    // 滚动
    private int scrollY = 0;
    private int maxScrollY = 0;

    private void ensureWidgets() {
        // 工具列表按钮：如果工具数量发生变化则重建
        int c = ToolManager.toolCount();
        if (lastToolCount != c) {
            lastToolCount = c;
            toolButtons.clear();
            toolBaseLabels.clear();

            noneToolButton = ButtonWidget.builder(Text.literal("当前工具：无"), b -> ToolManager.setTool(null))
                    .dimensions(0, 0, 0, BUTTON_HEIGHT)
                    .tooltip(Tooltip.of(Text.literal("取消当前工具（恢复为无）")))
                    .build();

            List<FormacraftTool> tools = ToolManager.getTools();
            for (FormacraftTool t : tools) {
                if (t == null) continue;
                String id = t.getId();
                if (id == null) continue;
                Text base = t.getDisplayName();
                if (base == null) base = Text.literal(id);
                ButtonWidget btn = ButtonWidget.builder(t.getDisplayName(), b -> ToolManager.setTool(id))
                        .dimensions(0, 0, 0, BUTTON_HEIGHT)
                        .tooltip(Tooltip.of(Text.literal("激活工具：" + t.getDisplayName().getString())))
                        .build();
                toolButtons.put(id, btn);
                toolBaseLabels.put(id, base);
            }
        }

        if (clearSelectionButton != null) return;

        clearAnchorButton = ButtonWidget.builder(Text.literal("清除锚点"), b -> AnchorState.clear())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("清除当前锚点（右键可重新设置）")))
                .build();

        clearSelectionButton = ButtonWidget.builder(Text.literal("清除选区"), b -> SelectionTool.INSTANCE.clear())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("清除当前已选定的区域")))
                .build();

        clearProtectedZonesButton = ButtonWidget.builder(Text.literal("清空禁区"), b -> ProtectedZoneTool.INSTANCE.clearZones())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("清空所有已添加的禁区/保护区")))
                .build();

        outlineModeButton = ButtonWidget.builder(Text.literal("模式：POLYGON"), b -> OutlineTool.INSTANCE.cycleMode())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("切换轮廓绘制模式")))
                .build();

        clearOutlineButton = ButtonWidget.builder(Text.literal("清空轮廓"), b -> OutlineTool.INSTANCE.clearShape())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("清除当前轮廓")))
                .build();

        clearPathsButton = ButtonWidget.builder(Text.literal("清空路径"), b -> PathTool.INSTANCE.clearAll())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("清除所有已完成路径以及当前草稿")))
                .build();

        brushModeButton = ButtonWidget.builder(Text.literal("笔刷模式：TOP"), b -> BrushTool.INSTANCE.cycleMode())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("切换笔刷高亮模式（顶面/立方体外框）")))
                .build();
        brushRadiusMinusButton = ButtonWidget.builder(Text.literal("半径 -"), b -> BrushTool.INSTANCE.decRadius())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("减小笔刷半径")))
                .build();
        brushRadiusPlusButton = ButtonWidget.builder(Text.literal("半径 +"), b -> BrushTool.INSTANCE.incRadius())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("增大笔刷半径")))
                .build();
        clearBrushSelectionButton = ButtonWidget.builder(Text.literal("清空笔刷选中"), b -> BrushTool.INSTANCE.clearSelected())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("清空笔刷已选中的地表方块（右键也可清空）")))
                .build();

        symmetryModeButton = ButtonWidget.builder(Text.literal("模式：NONE"), b -> SymmetryTool.INSTANCE.cycleMode())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("切换对称模式（自定义轴线可两点标注）")))
                .build();

        clearSymmetryButton = ButtonWidget.builder(Text.literal("清空对称轴"), b -> SymmetryTool.INSTANCE.clearAxis())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("清除自定义轴线（不影响预设模式）")))
                .build();

        clearLabelsButton = ButtonWidget.builder(Text.literal("清空标签"), b -> SemanticLabelTool.INSTANCE.clearLabels())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("清空所有区域语义标签")))
                .build();

        labelNameInput.setMaxLength(64);
        labelNameInput.setText("入口");
        labelRangeInput.setMaxLength(4);
        labelRangeInput.setText("16");
    }

    @Override
    protected void drawContents(DrawContext ctx) {
        ensureWidgets();

        int x = panelX + CONTENT_PADDING;
        int y = getContentY() + CONTENT_PADDING - scrollY;
        int w = panelWidth - CONTENT_PADDING * 2;

        // 半透明底
        ctx.fill(panelX + 1, getContentY(), panelX + panelWidth - 1, panelY + panelHeight - 1, 0x80101010);

        // 内容裁剪（避免滚动时画出边界）
        int sx0 = panelX + 1;
        int sy0 = getContentY() + 1;
        int sx1 = panelX + panelWidth - 1;
        int sy1 = panelY + panelHeight - 1;
        if (sx1 > sx0 && sy1 > sy0) ctx.enableScissor(sx0, sy0, sx1, sy1);
        try {
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("Tools"), x, y, 0xFFFFFFFF);
        y += 20;

        // --------------------
        // 工具列表（插件化）
        // --------------------
        noneToolButton.setMessage(Text.literal(ToolManager.getActiveTool() == null ? "当前工具：无" :
                "当前工具：" + ToolManager.getActiveTool().getDisplayName().getString()));
        noneToolButton.setPosition(x, y);
        noneToolButton.setWidth(w);
        noneToolButton.visible = true;
        noneToolButton.active = true;
        noneToolButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);
        y += LABEL_OFFSET;

        for (Map.Entry<String, ButtonWidget> e : toolButtons.entrySet()) {
            String id = e.getKey();
            ButtonWidget b = e.getValue();
            if (b == null) continue;
            boolean active = ToolManager.isActive(id);
            Text base = toolBaseLabels.get(id);
            if (base == null) base = b.getMessage();
            // 关键：不要基于 b.getMessage() 累积拼接；每帧都从 base 重新生成
            b.setMessage(Text.literal((active ? "▶ " : "") + base.getString()));
            b.setPosition(x, y);
            b.setWidth(w);
            b.visible = true;
            b.active = true;
            b.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);
            y += LABEL_OFFSET;
        }

        y += LABEL_OFFSET;

        // 选区状态
        String status;
        if (!ToolManager.isActive(SelectionTool.INSTANCE.getId())) {
            status = "当前工具：无";
        } else if (SelectionTool.INSTANCE.isSelecting()) {
            status = "选区中：点击设置起点/终点";
        } else if (SelectionTool.INSTANCE.hasSelection()) {
            var min = SelectionTool.INSTANCE.getMin();
            var max = SelectionTool.INSTANCE.getMax();
            int dx = 0;
            if (max != null) {
                if (min != null) {
                    dx = (max.getX() - min.getX() + 1);
                }
            }
            int dy = 0;
            if (max != null) {
                if (min != null) {
                    dy = (max.getY() - min.getY() + 1);
                }
            }
            int dz = 0;
            if (min != null) {
                if (max != null) {
                    dz = (max.getZ() - min.getZ() + 1);
                }
            }
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
        // 路径工具
        // --------------------
        y += FIELD_SPACING;
        String pathStatus = PathTool.INSTANCE.isDrafting()
                ? ("路径：绘制中（草稿点=" + PathTool.INSTANCE.getDraftPointCount() + "，右键结束）")
                : ("路径：已完成 " + PathTool.INSTANCE.getPathCount() + " 条");
        ctx.drawTextWithShadow(client.textRenderer,
                Text.literal(pathStatus),
                x, y, 0xFFAAAAAA);
        y += LABEL_OFFSET;

        clearPathsButton.setPosition(x, y);
        clearPathsButton.setWidth(w);
        clearPathsButton.visible = true;
        clearPathsButton.active = PathTool.INSTANCE.getPathCount() > 0 || PathTool.INSTANCE.getDraftPointCount() > 0;
        clearPathsButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);

        // --------------------
        // 笔刷工具
        // --------------------
        y += FIELD_SPACING;
        ctx.drawTextWithShadow(client.textRenderer,
                Text.literal("笔刷：半径=" + BrushTool.INSTANCE.getRadius()
                        + "  已选中=" + BrushTool.INSTANCE.getSelectedCount()),
                x, y, 0xFFAAAAAA);
        y += LABEL_OFFSET;

        // 模式按钮
        brushModeButton.setMessage(Text.literal("笔刷模式：" + BrushTool.INSTANCE.getMode().name()));
        brushModeButton.setPosition(x, y);
        brushModeButton.setWidth(w);
        brushModeButton.visible = true;
        brushModeButton.active = true;
        brushModeButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);
        y += LABEL_OFFSET;

        // 半径 - / + 两个按钮并排
        int half = (w - 4) / 2;
        brushRadiusMinusButton.setPosition(x, y);
        brushRadiusMinusButton.setWidth(half);
        brushRadiusMinusButton.visible = true;
        brushRadiusMinusButton.active = true;
        brushRadiusMinusButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);

        brushRadiusPlusButton.setPosition(x + half + 4, y);
        brushRadiusPlusButton.setWidth(w - half - 4);
        brushRadiusPlusButton.visible = true;
        brushRadiusPlusButton.active = true;
        brushRadiusPlusButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);
        y += LABEL_OFFSET;

        clearBrushSelectionButton.setPosition(x, y);
        clearBrushSelectionButton.setWidth(w);
        clearBrushSelectionButton.visible = true;
        clearBrushSelectionButton.active = BrushTool.INSTANCE.getSelectedCount() > 0;
        clearBrushSelectionButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);

        // --------------------
        // 对称/镜像
        // --------------------
        y += FIELD_SPACING;
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
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("标签名："), x, y, 0xFFAAAAAA);
        y += LABEL_OFFSET - 2;
        labelNameInput.render(ctx, x, y, w, 14);
        // 让工具实时拿到最新标签名
        SemanticLabelTool.INSTANCE.setPendingName(labelNameInput.getText());

        y += LABEL_OFFSET + 2;
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("作用范围(方块)："), x, y, 0xFFAAAAAA);
        y += LABEL_OFFSET - 2;
        labelRangeInput.render(ctx, x, y, w, 14);
        // 解析范围（非法输入则保持上一次值）
        try {
            String t = labelRangeInput.getText() == null ? "" : labelRangeInput.getText().trim();
            if (!t.isEmpty()) {
                int v = Integer.parseInt(t.replaceAll("[^0-9-]", ""));
                if (v < 0) v = 0;
                if (v > 256) v = 256;
                SemanticLabelTool.INSTANCE.setPendingRange(v);
            }
        } catch (Throwable ignored) {}

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

        // --------------------
        // 锚点状态（置于底部：不挤占工具列表）
        // --------------------
        y += FIELD_SPACING;
        String anchorText = AnchorState.hasAnchor() ? ("锚点：" + AnchorState.get().getX() + "," + AnchorState.get().getY() + "," + AnchorState.get().getZ()
                + "  facing=" + AnchorState.getFacing().name())
                : "锚点：未设置（面板外右键设置）";
        ctx.drawTextWithShadow(client.textRenderer, Text.literal(anchorText), x, y, 0xFFAAAAAA);
        y += LABEL_OFFSET;
        clearAnchorButton.setPosition(x, y);
        clearAnchorButton.setWidth(w);
        clearAnchorButton.visible = true;
        clearAnchorButton.active = AnchorState.hasAnchor();
        clearAnchorButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);

        // 计算最大滚动（基于未滚动起点）
        int contentTop = getContentY() + CONTENT_PADDING;
        int visibleH = getContentHeight() - CONTENT_PADDING * 2;
        int totalH = (y + scrollY) - contentTop + LABEL_OFFSET; // y 是已减 scrollY 的
        maxScrollY = Math.max(0, totalH - visibleH);
        if (scrollY > maxScrollY) scrollY = maxScrollY;
        if (scrollY < 0) scrollY = 0;
        } finally {
            if (sx1 > sx0 && sy1 > sy0) ctx.disableScissor();
        }
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
        int y = getContentY() + CONTENT_PADDING - scrollY + 20;
        int w = panelWidth - CONTENT_PADDING * 2;

        net.minecraft.client.gui.Click click = new net.minecraft.client.gui.Click(mouseX, mouseY, new net.minecraft.client.input.MouseInput(button, 0));

        // 工具列表
        noneToolButton.setPosition(x, y);
        noneToolButton.setWidth(w);
        if (noneToolButton.mouseClicked(click, false)) return true;
        y += LABEL_OFFSET;

        for (ButtonWidget b : toolButtons.values()) {
            if (b == null) continue;
            b.setPosition(x, y);
            b.setWidth(w);
            if (b.mouseClicked(click, false)) return true;
            y += LABEL_OFFSET;
        }
        y += LABEL_OFFSET;

        clearSelectionButton.setPosition(x, y);
        clearSelectionButton.setWidth(w);

        if (clearSelectionButton.mouseClicked(click, false)) return true;

        // 禁区
        y += FIELD_SPACING + LABEL_OFFSET;
        clearProtectedZonesButton.setPosition(x, y);
        clearProtectedZonesButton.setWidth(w);
        if (clearProtectedZonesButton.mouseClicked(click, false)) return true;

        // 轮廓
        y += FIELD_SPACING;
        outlineModeButton.setPosition(x, y);
        outlineModeButton.setWidth(w);
        if (outlineModeButton.mouseClicked(click, false)) return true;

        y += LABEL_OFFSET * 2;
        clearOutlineButton.setPosition(x, y);
        clearOutlineButton.setWidth(w);
        if (clearOutlineButton.mouseClicked(click, false)) return true;

        // 路径
        y += FIELD_SPACING + LABEL_OFFSET; // 路径状态行 + 间距
        clearPathsButton.setPosition(x, y);
        clearPathsButton.setWidth(w);
        clearPathsButton.visible = true;
        clearPathsButton.active = true;
        if (clearPathsButton.mouseClicked(click, false)) return true;

        // 笔刷
        y += FIELD_SPACING; // 笔刷状态行
        y += LABEL_OFFSET;  // 模式按钮
        brushModeButton.setPosition(x, y);
        brushModeButton.setWidth(w);
        brushModeButton.visible = true;
        brushModeButton.active = true;
        if (brushModeButton.mouseClicked(click, false)) return true;

        y += LABEL_OFFSET;
        int half = (w - 4) / 2;
        brushRadiusMinusButton.setPosition(x, y);
        brushRadiusMinusButton.setWidth(half);
        brushRadiusMinusButton.visible = true;
        brushRadiusMinusButton.active = true;
        if (brushRadiusMinusButton.mouseClicked(click, false)) return true;

        brushRadiusPlusButton.setPosition(x + half + 4, y);
        brushRadiusPlusButton.setWidth(w - half - 4);
        brushRadiusPlusButton.visible = true;
        brushRadiusPlusButton.active = true;
        if (brushRadiusPlusButton.mouseClicked(click, false)) return true;

        y += LABEL_OFFSET;
        clearBrushSelectionButton.setPosition(x, y);
        clearBrushSelectionButton.setWidth(w);
        clearBrushSelectionButton.visible = true;
        clearBrushSelectionButton.active = true;
        if (clearBrushSelectionButton.mouseClicked(click, false)) return true;

        // 对称
        y += FIELD_SPACING;
        symmetryModeButton.setPosition(x, y);
        symmetryModeButton.setWidth(w);
        if (symmetryModeButton.mouseClicked(click, false)) return true;

        y += LABEL_OFFSET;
        clearSymmetryButton.setPosition(x, y);
        clearSymmetryButton.setWidth(w);
        if (clearSymmetryButton.mouseClicked(click, false)) return true;

        // 语义标注
        y += FIELD_SPACING;
        y += LABEL_OFFSET; // “标签名：”
        y += LABEL_OFFSET - 2; // 输入框 y（与 drawContents 一致）
        // labelNameInput
        if (labelNameInput.mouseClicked(mouseX, mouseY, x, y, w, 14)) return true;

        // “作用范围(方块)：” + 输入框
        y += LABEL_OFFSET + 2;
        y += LABEL_OFFSET; // “作用范围(方块)：”
        y += LABEL_OFFSET - 2;
        if (labelRangeInput.mouseClicked(mouseX, mouseY, x, y, w, 14)) return true;

        y += LABEL_OFFSET + 2;
        clearLabelsButton.setPosition(x, y);
        clearLabelsButton.setWidth(w);
        if (clearLabelsButton.mouseClicked(click, false)) return true;

        // 锚点
        y += FIELD_SPACING + LABEL_OFFSET;
        clearAnchorButton.setPosition(x, y);
        clearAnchorButton.setWidth(w);
        return clearAnchorButton.mouseClicked(click, false);
    }

    @Override
    public void mouseScrolled(double mouseX, double mouseY, double amount) {
        // 只有在面板内容区内滚动才处理
        if (!isMouseOver(mouseX, mouseY)) return;
        int step = 12;
        scrollY = (int) Math.round(scrollY - amount * step);
        if (scrollY < 0) scrollY = 0;
        if (scrollY > maxScrollY) scrollY = maxScrollY;
    }

    @Override
    public void keyPressed(int keyCode, int scanCode, int modifiers) {
        ensureWidgets();
        // 仅当输入框 focused 时消费键盘
        if (labelNameInput.keyPressed(keyCode, modifiers)) {
            SemanticLabelTool.INSTANCE.setPendingName(labelNameInput.getText());
        }
        if (labelRangeInput.keyPressed(keyCode, modifiers)) {
            // 尽量实时更新（容错：无法 parse 时不更新）
            try {
                String t = labelRangeInput.getText() == null ? "" : labelRangeInput.getText().trim();
                if (!t.isEmpty()) {
                    int v = Integer.parseInt(t.replaceAll("[^0-9-]", ""));
                    if (v < 0) v = 0;
                    if (v > 256) v = 256;
                    SemanticLabelTool.INSTANCE.setPendingRange(v);
                }
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void charTyped(char chr) {
        ensureWidgets();
        if (labelNameInput.charTyped(chr)) {
            SemanticLabelTool.INSTANCE.setPendingName(labelNameInput.getText());
        }
        if (labelRangeInput.charTyped(chr)) {
            try {
                String t = labelRangeInput.getText() == null ? "" : labelRangeInput.getText().trim();
                if (!t.isEmpty()) {
                    int v = Integer.parseInt(t.replaceAll("[^0-9-]", ""));
                    if (v < 0) v = 0;
                    if (v > 256) v = 256;
                    SemanticLabelTool.INSTANCE.setPendingRange(v);
                }
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public boolean wantsKeyboardInput() {
        return labelNameInput.isFocused() || labelRangeInput.isFocused();
    }
}

