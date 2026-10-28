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
import com.formacraft.client.tool.WorkPlaneTool;
import com.formacraft.client.interaction.AnchorState;
import com.formacraft.client.ui.widget.HudClickSupport;
import com.formacraft.client.ui.widget.HudTextInput;
import com.formacraft.client.ui.UiTheme;
import com.formacraft.client.ui.toast.HudToast;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.input.MouseInput;
import net.minecraft.text.Text;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tools 面板：选择/管理工具（首个工具：区域选区）。
 */
public class ToolPanel extends BasePanel {

    private static final int CONTENT_PADDING = UiTheme.CONTENT_PADDING;
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
    private ButtonWidget pathDrawingModeButton, pathRadiusMinusButton, pathRadiusPlusButton;
    private ButtonWidget pathSelectButton, pathRemoveButton, pathIntentButton;
    private ButtonWidget brushUndoButton, brushRedoButton;
    private ButtonWidget brushModeButton;
    private ButtonWidget brushRadiusMinusButton;
    private ButtonWidget brushRadiusPlusButton;
    private ButtonWidget clearBrushSelectionButton;
    private ButtonWidget symmetryModeButton;
    private ButtonWidget clearSymmetryButton;
    private ButtonWidget clearLabelsButton;

    private final HudTextInput labelNameInput = new HudTextInput();
    private final HudTextInput planeHeightInput = new HudTextInput();
    private ButtonWidget planeLockButton, planeModeButton, planeDownButton, planeUpButton, planeApplyButton, planeClearButton;
    private int planeInputX, planeInputY, planeInputW;
    private boolean planeInputVisible;


    // 标签“作用范围”滑动条（1~40）——参考 SettingsPanel 的 SliderWidget 实现
    private static final int LABEL_RANGE_MIN = 1;
    private static final int LABEL_RANGE_MAX = 40;
    private LabelRangeSlider labelRangeSlider;
    private SliderWidget activeSlider = null; // 只允许同时操作一个滑条（与 SettingsPanel 一致）

    // 由于 HudTextInput 不保存自己的 bounds，我们在 drawContents() 里缓存其位置，mouseClicked() 直接复用，避免布局重复计算造成偏移
    private int labelNameInputX = 0;
    private int labelNameInputY = 0;
    private int labelNameInputW = 0;
    private int labelNameInputH = 0;
    private boolean labelNameInputBoundsValid = false;




    // 滚动（仅用于选项区域）
    private int scrollY = 0;
    private int maxScrollY = 0;
    private int separatorY = 0; // 分隔线的 Y 坐标

    private void ensureWidgets() {
        // 工具列表按钮：如果工具数量发生变化则重建
        int c = ToolManager.toolCount();
        if (lastToolCount != c) {
            lastToolCount = c;
            toolButtons.clear();
            toolBaseLabels.clear();

            noneToolButton = ButtonWidget.builder(Text.literal("当前工具：锚点"), b -> ToolManager.setTool(null))
                    .dimensions(0, 0, 0, BUTTON_HEIGHT)
                    .tooltip(Tooltip.of(Text.literal("切换到锚点：面板外右键定位；已有选区和轮廓仍保留")))
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

        clearProtectedZonesButton = ButtonWidget.builder(Text.literal("清空禁区"), b -> { ProtectedZoneTool.INSTANCE.clearZones(); HudToast.show("已清空禁区与未完成框选"); })
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("清空所有已添加的禁区/保护区")))
                .build();

        outlineModeButton = ButtonWidget.builder(Text.literal("模式：POLYGON"), b -> OutlineTool.INSTANCE.cycleMode())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("切换轮廓绘制模式")))
                .build();

        clearOutlineButton = ButtonWidget.builder(Text.literal("清除轮廓"), b -> OutlineTool.INSTANCE.clearShape())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("清除当前轮廓")))
                .build();

        pathDrawingModeButton = optionButton("新路径：折线", PathTool.INSTANCE::cycleDrawingMode);
        pathRadiusMinusButton = optionButton("走廊半径 -", () -> PathTool.INSTANCE.changeCorridorRadius(-1));
        pathRadiusPlusButton = optionButton("走廊半径 +", () -> PathTool.INSTANCE.changeCorridorRadius(1));
        pathSelectButton = optionButton("切换生成路径", PathTool.INSTANCE::selectNextPath);
        pathRemoveButton = optionButton("删除当前路径", PathTool.INSTANCE::removeSelectedPath);
        pathIntentButton = optionButton("用途：自动", PathTool.INSTANCE::cycleIntent);
        brushUndoButton = optionButton("撤销笔刷（Ctrl+Z）", BrushTool.INSTANCE::undo);
        brushRedoButton = optionButton("重做笔刷（Ctrl+Y）", BrushTool.INSTANCE::redo);

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
                .tooltip(Tooltip.of(Text.literal("清空全部笔刷范围；面板外右键只擦除局部")))
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

        planeHeightInput.setMaxLength(12);
        planeLockButton = optionButton("锁定平面", () -> WorkPlaneTool.INSTANCE.state().toggleLock());
        planeModeButton = optionButton("模式：固定高度", () -> WorkPlaneTool.INSTANCE.state().toggleTerrain());
        planeDownButton = optionButton("高度 -1", () -> WorkPlaneTool.INSTANCE.changeHeight(-1));
        planeUpButton = optionButton("高度 +1", () -> WorkPlaneTool.INSTANCE.changeHeight(1));
        planeApplyButton = optionButton("应用 Y 坐标", this::applyPlaneHeight);
        planeClearButton = optionButton("清除基准平面", () -> WorkPlaneTool.INSTANCE.clear());

        labelNameInput.setMaxLength(64);
        labelNameInput.setText("入口");


        // Slider（用原版 SliderWidget 渲染/拖拽，手感与 SettingsPanel 一致）
        if (labelRangeSlider == null) {
            int initRange = SemanticLabelTool.INSTANCE.getPendingRange();
            if (initRange < LABEL_RANGE_MIN) initRange = LABEL_RANGE_MIN;
            if (initRange > LABEL_RANGE_MAX) initRange = LABEL_RANGE_MAX;
            labelRangeSlider = new LabelRangeSlider(0, 0, 0, BUTTON_HEIGHT, Text.empty(), rangeToValue(initRange));
            labelRangeSlider.setTooltip(Tooltip.of(Text.literal("设置标签作用范围（方块），用于提示 AI 该标签影响的周边区域")));
        }
    }

    @Override
    protected void drawContents(DrawContext ctx) {
        ensureWidgets();

        int x = panelX + CONTENT_PADDING;
        int w = panelWidth - CONTENT_PADDING * 2;

        // 每帧重置 bounds（如果本帧没画到输入框，就不允许点击命中它）
        labelNameInputBoundsValid = false;
        planeInputVisible = false;

        drawContentBackground(ctx);

        // ====================
        // 第一部分：固定区域（工具列表，不滚动）
        // ====================
        int fixedY = getContentY() + CONTENT_PADDING;

        // 第一行：当前工具按钮（独占一行）
        noneToolButton.setMessage(Text.literal(ToolManager.getActiveTool() == null ? "当前工具：锚点" :
                "当前工具：" + ToolManager.getActiveTool().getDisplayName().getString()));
        noneToolButton.setPosition(x, fixedY);
        noneToolButton.setWidth(w);
        noneToolButton.visible = true;
        noneToolButton.active = true;
        noneToolButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);
        fixedY += LABEL_OFFSET;

        // 工具列表（插件化）：每行两个按钮
        int buttonWidth = (w - 4) / 2; // 两个按钮之间的间距为4
        int buttonIndex = 0;
        int currentRowY = fixedY;
        
        for (Map.Entry<String, ButtonWidget> e : toolButtons.entrySet()) {
            String id = e.getKey();
            ButtonWidget b = e.getValue();
            if (b == null) continue;
            boolean active = ToolManager.isActive(id);
            Text base = toolBaseLabels.get(id);
            if (base == null) base = b.getMessage();
            // 关键：不要基于 b.getMessage() 累积拼接；每帧都从 base 重新生成
            b.setMessage(Text.literal((active ? "▶ " : "") + base.getString()));
            
            // 计算按钮位置：每行两个
            int buttonX = (buttonIndex % 2 == 0) ? x : (x + buttonWidth + 4);
            b.setPosition(buttonX, currentRowY);
            b.setWidth(buttonWidth);
            b.visible = true;
            b.active = true;
            b.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);
            
            buttonIndex++;
            // 每两个按钮换一行
            if (buttonIndex % 2 == 0) {
                currentRowY += LABEL_OFFSET;
            }
        }
        
        // 如果最后一个按钮是奇数个，需要换行
        if (buttonIndex % 2 != 0) {
            currentRowY += LABEL_OFFSET;
        }
        
        fixedY = currentRowY;
        fixedY += 4; // 减小到分割线的距离
        separatorY = fixedY;

        // 绘制分隔线
        int separatorX0 = contentScissorLeft();
        int separatorX1 = contentScissorRight();
        ctx.fill(separatorX0, separatorY, separatorX1, separatorY + 1, UiTheme.DIVIDER_SECTION);

        // ====================
        // 第二部分：滚动区域（当前工具选项）
        // ====================
        int optionsStartY = separatorY + 1 + CONTENT_PADDING;
        int optionsY = optionsStartY - scrollY;
        int optionsAreaBottom = panelY + panelHeight - 1;
        int optionsVisibleHeight = optionsAreaBottom - optionsStartY;

        // 内容裁剪（仅裁剪滚动区域）
        int sx0 = panelX + 1;
        int sx1 = panelX + panelWidth - 1;
        if (sx1 > sx0 && optionsAreaBottom > optionsStartY) ctx.enableScissor(sx0, optionsStartY, sx1, optionsAreaBottom);
        try {
        // 根据当前激活的工具，只渲染对应工具的选项
        FormacraftTool activeTool = ToolManager.getActiveTool();
        String activeToolId = activeTool != null ? activeTool.getId() : null;

        String source = OutlineTool.INSTANCE.hasShape() ? "轮廓（优先于选区和锚点）"
                : SelectionTool.INSTANCE.hasSelection() ? "选区底面中心（优先于锚点）"
                : AnchorState.hasAnchor() ? "显式锚点" : "鼠标命中／玩家位置";
        optionsY = drawWrappedText(ctx, Text.literal("默认定位：" + source), x, optionsY, w, 0xFF88DDFF);
        if (WorkPlaneTool.INSTANCE.state().fixed()) {
            optionsY = drawWrappedText(ctx, Text.literal("固定基准高度：Y=" + WorkPlaneTool.INSTANCE.state().height()
                    + "（新建时生效，修改选区保持原坐标）"), x, optionsY + 2, w, 0xFF66DDFF);
        }
        optionsY = drawWrappedText(ctx, Text.literal("保留数据：选区" + (SelectionTool.INSTANCE.hasSelection() ? "有" : "无")
                + " · 轮廓" + (OutlineTool.INSTANCE.hasShape() ? "有" : "无")
                + " · 保护区" + ProtectedZoneTool.INSTANCE.getZones().size()
                + " · 路径" + PathTool.INSTANCE.getPathCount()
                + " · 笔刷" + BrushTool.INSTANCE.getSelectedCount()), x, optionsY + 2, w, 0xFFAAAAAA);
        optionsY = drawWrappedText(ctx, Text.literal("切换工具不会清除已完成范围。修改区域模式优先使用选区。"),
                x, optionsY + 2, w, 0xFFAAAAAA);
        optionsY += 6;


        if (activeToolId != null) {
            // 显示当前工具名称
            optionsY = drawWrappedText(ctx, 
                    Text.literal("▼ " + activeTool.getDisplayName().getString() + "选项"),
                    x, optionsY, w, 0xFFFFFFFF);
            optionsY += 2; // 小间距
        } else {
            // 没有激活工具时，显示提示
            optionsY = drawWrappedText(ctx, 
                    Text.literal("面板外右键设置锚点"),
                    x, optionsY, w, 0xFFAAAAAA);
            optionsY += 2; // 小间距
            
            // 没有工具时也显示锚点选项（因为锚点是在没有工具时设置的）
            optionsY += FIELD_SPACING;
            String anchorText = AnchorState.hasAnchor() ? ("锚点：" + AnchorState.get().getX() + "," + AnchorState.get().getY() + "," + AnchorState.get().getZ()
                    + "  facing=" + AnchorState.getFacing().name())
                    : "锚点：未设置（面板外右键设置）";
            optionsY = drawWrappedText(ctx, Text.literal(anchorText), x, optionsY, w, 0xFFAAAAAA);
            optionsY += 2; // 小间距
            clearAnchorButton.setPosition(x, optionsY);
            clearAnchorButton.setWidth(w);
            clearAnchorButton.visible = true;
            clearAnchorButton.active = AnchorState.hasAnchor();
            clearAnchorButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);
            optionsY += LABEL_OFFSET;
        }

        // 根据激活的工具ID，渲染对应的选项
        if (ToolManager.isActive(WorkPlaneTool.INSTANCE.getId())) {
            optionsY = drawWorkPlaneOptions(ctx, x, optionsY, w);
        } else if (ToolManager.isActive(SelectionTool.INSTANCE.getId())) {
            optionsY = drawSelectionToolOptions(ctx, x, optionsY, w);
        } else if (ToolManager.isActive(ProtectedZoneTool.INSTANCE.getId())) {
            optionsY = drawProtectedZoneToolOptions(ctx, x, optionsY, w);
        } else if (ToolManager.isActive(OutlineTool.INSTANCE.getId())) {
            optionsY = drawOutlineToolOptions(ctx, x, optionsY, w);
        } else if (ToolManager.isActive(PathTool.INSTANCE.getId())) {
            optionsY = drawPathToolOptions(ctx, x, optionsY, w);
        } else if (ToolManager.isActive(BrushTool.INSTANCE.getId())) {
            optionsY = drawBrushToolOptions(ctx, x, optionsY, w);
        } else if (ToolManager.isActive(SymmetryTool.INSTANCE.getId())) {
            optionsY = drawSymmetryToolOptions(ctx, x, optionsY, w);
        } else if (ToolManager.isActive(SemanticLabelTool.INSTANCE.getId())) {
            optionsY = drawSemanticLabelToolOptions(ctx, x, optionsY, w);
        }

        // 锚点状态（有工具时也显示在底部，作为通用选项）
        if (activeToolId != null) {
            optionsY += FIELD_SPACING;
            String anchorText = AnchorState.hasAnchor() ? ("锚点：" + AnchorState.get().getX() + "," + AnchorState.get().getY() + "," + AnchorState.get().getZ()
                    + "  facing=" + AnchorState.getFacing().name())
                    : "锚点：未设置（面板外右键设置）";
            optionsY = drawWrappedText(ctx, Text.literal(anchorText), x, optionsY, w, 0xFFAAAAAA);
            optionsY += 2; // 小间距
            clearAnchorButton.setPosition(x, optionsY);
            clearAnchorButton.setWidth(w);
            clearAnchorButton.visible = true;
            clearAnchorButton.active = AnchorState.hasAnchor();
            clearAnchorButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);
            optionsY += LABEL_OFFSET;
        }

        // 计算最大滚动（基于选项区域的内容高度）
        int optionsContentHeight = (optionsY + scrollY) - optionsStartY;
        maxScrollY = Math.max(0, optionsContentHeight - optionsVisibleHeight);
        if (scrollY > maxScrollY) scrollY = maxScrollY;
        if (scrollY < 0) scrollY = 0;
        } finally {
            if (sx1 > sx0 && optionsAreaBottom > optionsStartY) ctx.disableScissor();
        }

        // Toast：不参与滚动，也不被 scissor 裁剪（否则用户会觉得“点了没反应”）
        int toastY = panelY + panelHeight - CONTENT_PADDING - client.textRenderer.fontHeight - 2;
        HudToast.render(ctx, x, toastY);
    }

    private void applyPlaneHeight() {
        try {
            WorkPlaneTool.INSTANCE.setHeight(Integer.parseInt(planeHeightInput.getText().trim()));
            planeHeightInput.setFocused(false);
            HudToast.show("基准高度已设为 Y=" + WorkPlaneTool.INSTANCE.state().height());
        } catch (NumberFormatException e) {
            HudToast.show("请输入整数 Y 坐标", true);
        }
    }

    private int drawWorkPlaneOptions(DrawContext ctx, int x, int y, int w) {
        var plane = WorkPlaneTool.INSTANCE.state();
        y = drawWrappedText(ctx, Text.literal("左键点方块顶面取高；右键锁定／解锁。拖动黄色箭头调高，Esc取消拖动。"), x, y, w, 0xFFAAAAAA);
        y = drawWrappedText(ctx, Text.literal(plane.hasPlane() ? "平面 Y=" + plane.height()
                + (plane.locked() ? "（已锁定）" : "（未锁定）") : "尚未设置基准平面"), x, y + 3, w, 0xFF66DDFF);
        if (!planeHeightInput.isFocused()) planeHeightInput.setText(String.valueOf(plane.hasPlane() ? plane.height() : (client.player == null ? 64 : client.player.getBlockY())));
        planeInputX = x; planeInputY = y + 2; planeInputW = w;
        planeInputVisible = true;
        planeHeightInput.render(ctx, x, planeInputY, w, 14);
        y += 18;
        y = drawOptionButton(ctx, planeApplyButton, x, y, w, true);
        y = drawOptionButton(ctx, planeDownButton, x, y, w, plane.hasPlane());
        y = drawOptionButton(ctx, planeUpButton, x, y, w, plane.hasPlane());
        planeLockButton.setMessage(Text.literal(plane.locked() ? "解锁平面" : "锁定平面"));
        y = drawOptionButton(ctx, planeLockButton, x, y, w, plane.hasPlane());
        planeModeButton.setMessage(Text.literal(plane.followsTerrain() ? "定位：跟随地形" : "定位：固定高度"));
        y = drawOptionButton(ctx, planeModeButton, x, y, w, true);
        y = drawOptionButton(ctx, planeClearButton, x, y, w, plane.hasPlane());
        if (plane.fixed()) {
            y = drawWrappedText(ctx, Text.literal("轮廓、路径和笔刷使用平面交点；即使前方没有方块也能绘制。已有范围不会随平面自动移动。"), x, y, w, 0xFFAAAAAA);
            if (SelectionTool.INSTANCE.hasSelection() && (plane.height() < SelectionTool.INSTANCE.getMin().getY()
                    || plane.height() > SelectionTool.INSTANCE.getMax().getY())) {
                y = drawWrappedText(ctx, Text.literal("基准高度超出当前选区，请调整选区高度以免生成被裁剪。"), x, y + 2, w, 0xFFFFAA66);
            }
        }
        return y + 4;
    }

    // 绘制 SelectionTool 的选项
    private int drawSelectionToolOptions(DrawContext ctx, int x, int y, int w) {
        String status;
        if (SelectionTool.INSTANCE.isSelecting()) {
            status = "选区中：点击设置起点/终点";
        } else if (SelectionTool.INSTANCE.hasSelection()) {
            var min = SelectionTool.INSTANCE.getMin();
            var max = SelectionTool.INSTANCE.getMax();
            int dx = 0, dy = 0, dz = 0;
            if (max != null && min != null) {
                dx = (max.getX() - min.getX() + 1);
                dy = (max.getY() - min.getY() + 1);
                dz = (max.getZ() - min.getZ() + 1);
            }
            status = "Selection: " + dx + " x " + dy + " x " + dz;
        } else {
            status = "提示：左键点方块设置起点，再点一次设置终点";
        }
        y = drawWrappedText(ctx, Text.literal(status), x, y, w, 0xFFAAAAAA);
        y += 2; // 小间距

        clearSelectionButton.setPosition(x, y);
        clearSelectionButton.setWidth(w);
        clearSelectionButton.visible = true;
        clearSelectionButton.active = SelectionTool.INSTANCE.hasSelection() || SelectionTool.INSTANCE.isSelecting();
        clearSelectionButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);
        y += LABEL_OFFSET;

        return y;
    }

    // 绘制 ProtectedZoneTool 的选项
    private int drawProtectedZoneToolOptions(DrawContext ctx, int x, int y, int w) {
        y = drawWrappedText(ctx,
                Text.literal("已添加禁区：" + ProtectedZoneTool.INSTANCE.getZones().size()),
                x, y, w, 0xFFAAAAAA);
        y += 2; // 小间距

        clearProtectedZonesButton.setPosition(x, y);
        clearProtectedZonesButton.setWidth(w);
        clearProtectedZonesButton.visible = true;
        clearProtectedZonesButton.active = ProtectedZoneTool.INSTANCE.canClear();
        clearProtectedZonesButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);
        y += LABEL_OFFSET;

        return y;
    }

    // 绘制 OutlineTool 的选项
    private int drawOutlineToolOptions(DrawContext ctx, int x, int y, int w) {
        outlineModeButton.setMessage(Text.literal("模式：" + OutlineTool.INSTANCE.getMode().name()));
        outlineModeButton.setPosition(x, y);
        outlineModeButton.setWidth(w);
        outlineModeButton.visible = true;
        outlineModeButton.active = true;
        outlineModeButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);
        y += LABEL_OFFSET;

        String instruction = switch (OutlineTool.INSTANCE.getMode()) {
            case RECTANGLE -> "左键两点框选；右键／Esc取消";
            case CIRCLE -> "左键圆心、边缘；右键／Esc取消";
            case POLYGON -> "左键加点；右键／Enter完成；Backspace退点";
            case FREE_DRAW -> "按住左键绘制，松开完成；Esc取消";
        };
        y = drawWrappedText(ctx, Text.literal(instruction), x, y, w, 0xFFAAAAAA);
        String status = OutlineTool.INSTANCE.isDrafting() ? "轮廓：绘制中"
                : OutlineTool.INSTANCE.hasShape() ? "轮廓：已完成（紫色区域）" : "轮廓：未设置";
        y = drawWrappedText(ctx, Text.literal(status), x, y + 2, w, 0xFFAAAAAA);
        if (OutlineTool.INSTANCE.hasShape()) {
            var shape = OutlineTool.INSTANCE.getShape();
            y = drawWrappedText(ctx, Text.literal("高度：Y=" + shape.minY() + "～" + shape.maxY()
                    + "（有选区时继承，否则延伸至世界顶端）"), x, y + 2, w, 0xFFAAAAAA);
        }
        y += 2;

        clearOutlineButton.setPosition(x, y);
        clearOutlineButton.setWidth(w);
        clearOutlineButton.visible = true;
        clearOutlineButton.active = OutlineTool.INSTANCE.hasShape() || OutlineTool.INSTANCE.isDrafting();
        clearOutlineButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);
        y += LABEL_OFFSET;

        return y;
    }

    private ButtonWidget optionButton(String label, Runnable action) {
        return ButtonWidget.builder(Text.literal(label), b -> action.run()).dimensions(0, 0, 0, BUTTON_HEIGHT).build();
    }

    private int drawOptionButton(DrawContext ctx, ButtonWidget button, int x, int y, int w, boolean enabled) {
        button.setPosition(x, y);
        button.setWidth(w);
        button.visible = true;
        button.active = enabled;
        button.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);
        return y + LABEL_OFFSET;
    }

    // 绘制 PathTool 的选项
    private int drawPathToolOptions(DrawContext ctx, int x, int y, int w) {
        var path = PathTool.INSTANCE;
        pathDrawingModeButton.setMessage(Text.literal("新路径：" + (path.isSmooth() ? "平滑曲线" : "折线")));
        y = drawOptionButton(ctx, pathDrawingModeButton, x, y, w, true);
        pathSelectButton.setMessage(Text.literal("生成路径：" + (path.getSelectedPathIndex() + 1)
                + "/" + path.getPathCount() + "（点击切换）"));
        y = drawOptionButton(ctx, pathSelectButton, x, y, w, path.getPathCount() > 1);
        y = drawOptionButton(ctx, pathRemoveButton, x, y, w, path.getPathCount() > 0);
        String intent = switch (path.getIntent()) {
            case GENERIC -> "自动";
            case ROAD -> "道路";
            case WALL -> "城墙";
            case BRIDGE -> "桥梁";
            case ALONG_PATH_BUILDING -> "沿线建筑";
        };
        pathIntentButton.setMessage(Text.literal("用途：" + intent));
        y = drawOptionButton(ctx, pathIntentButton, x, y, w, true);
        y = drawWrappedText(ctx, Text.literal("走廊半径：" + path.getCorridorRadius() + " 格；每次只使用选中的一条路径"), x, y, w, 0xFFAAAAAA);
        y = drawOptionButton(ctx, pathRadiusMinusButton, x, y + 2, w, path.getCorridorRadius() > 1);
        y = drawOptionButton(ctx, pathRadiusPlusButton, x, y, w, path.getCorridorRadius() < 32);

        String pathStatus = PathTool.INSTANCE.isDrafting()
                ? ("路径：绘制中（草稿点=" + PathTool.INSTANCE.getDraftPointCount() + "，右键／Enter完成，Backspace退点，Esc取消）")
                : ("路径：已完成 " + PathTool.INSTANCE.getPathCount() + " 条");
        y = drawWrappedText(ctx,
                Text.literal(pathStatus),
                x, y, w, 0xFFAAAAAA);
        y += 2; // 小间距

        clearPathsButton.setPosition(x, y);
        clearPathsButton.setWidth(w);
        clearPathsButton.visible = true;
        clearPathsButton.active = PathTool.INSTANCE.getPathCount() > 0 || PathTool.INSTANCE.getDraftPointCount() > 0;
        clearPathsButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);
        y += LABEL_OFFSET;

        return y;
    }

    // 绘制 BrushTool 的选项
    private int drawBrushToolOptions(DrawContext ctx, int x, int y, int w) {
        y = drawOptionButton(ctx, brushUndoButton, x, y, w, BrushTool.INSTANCE.canUndo());
        y = drawOptionButton(ctx, brushRedoButton, x, y, w, BrushTool.INSTANCE.canRedo());

        y = drawWrappedText(ctx, Text.literal("左键涂抹；右键局部擦除；清空按钮删除全部"), x, y, w, 0xFFAAAAAA);

        y = drawWrappedText(ctx,
                Text.literal("笔刷：半径=" + BrushTool.INSTANCE.getRadius()
                        + "  已选中=" + BrushTool.INSTANCE.getSelectedCount()),
                x, y, w, 0xFFAAAAAA);
        y += 2; // 小间距

        brushModeButton.setMessage(Text.literal("笔刷模式：" + BrushTool.INSTANCE.getMode().name()));
        brushModeButton.setPosition(x, y);
        brushModeButton.setWidth(w);
        brushModeButton.visible = true;
        brushModeButton.active = true;
        brushModeButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);
        y += LABEL_OFFSET;

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
        y += LABEL_OFFSET;

        return y;
    }

    // 绘制 SymmetryTool 的选项
    private int drawSymmetryToolOptions(DrawContext ctx, int x, int y, int w) {
        y = drawWrappedText(ctx, Text.literal("X 红 · Y 绿 · Z 蓝；网格表示镜像面。优先以选区为基准，否则使用生成定位点。"), x, y, w, 0xFFAAAAAA);

        symmetryModeButton.setMessage(Text.literal("模式：" + SymmetryTool.INSTANCE.getModeLabel()));
        symmetryModeButton.setPosition(x, y);
        symmetryModeButton.setWidth(w);
        symmetryModeButton.visible = true;
        symmetryModeButton.active = true;
        symmetryModeButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);
        y += LABEL_OFFSET;

        clearSymmetryButton.setPosition(x, y);
        clearSymmetryButton.setWidth(w);
        clearSymmetryButton.visible = true;
        clearSymmetryButton.active = SymmetryTool.INSTANCE.canClearAxis();
        clearSymmetryButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);
        y += LABEL_OFFSET;

        return y;
    }

    // 绘制 SemanticLabelTool 的选项
    private int drawSemanticLabelToolOptions(DrawContext ctx, int x, int y, int w) {
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("标签名："), x, y, 0xFFAAAAAA);
        int nameInputY = y + LABEL_OFFSET - 2;
        labelNameInput.render(ctx, x, nameInputY, w, 14);
        labelNameInputX = x;
        labelNameInputY = nameInputY;
        labelNameInputW = w;
        labelNameInputH = 14;
        labelNameInputBoundsValid = true;
        SemanticLabelTool.INSTANCE.setPendingName(labelNameInput.getText());
        y += FIELD_SPACING;

        int rangeLabelY = y;
        int rangeSliderY = y + LABEL_OFFSET;
        ctx.drawTextWithShadow(client.textRenderer,
                Text.literal("作用范围（1~40）："),
                x, rangeLabelY, 0xFFAAAAAA);

        labelRangeSlider.setPosition(x, rangeSliderY);
        labelRangeSlider.setWidth(w);
        labelRangeSlider.visible = true;
        labelRangeSlider.active = true;
        labelRangeSlider.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);
        y += FIELD_SPACING;

        y = drawWrappedText(ctx,
                Text.literal("已添加标签：" + SemanticLabelTool.INSTANCE.getLabels().size()),
                x, y, w, 0xFFAAAAAA);
        y += 2; // 小间距

        clearLabelsButton.setPosition(x, y);
        clearLabelsButton.setWidth(w);
        clearLabelsButton.visible = true;
        clearLabelsButton.active = true;
        clearLabelsButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);
        y += LABEL_OFFSET;

        return y;

    }

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
    private int drawWrappedText(DrawContext ctx, Text text, int x, int y, int maxWidth, int color) {
        if (text == null) return y;
        int lineHeight = client.textRenderer.fontHeight;
        for (var line : client.textRenderer.wrapLines(text, maxWidth)) {
            ctx.drawTextWithShadow(client.textRenderer, line, x, y, color);
            y += lineHeight;
        }
        return y;
    }

    private double getScaledMouseX() {
        return client.mouse.getX() / client.getWindow().getScaleFactor();
    }

    private double getScaledMouseY() {
        return client.mouse.getY() / client.getWindow().getScaleFactor();
    }

    private boolean clickButton(ButtonWidget button, Click click) {
        return button != null && button.visible && HudClickSupport.click(button, click);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (!isMouseOver(mouseX, mouseY)) {
            labelNameInput.setFocused(false);
            planeHeightInput.setFocused(false);
            return false;
        }
        if (button != 0) return false;

        ensureWidgets();
        Click click = new Click(mouseX, mouseY, new MouseInput(button, 0));

        // 固定区域（工具列表）：始终可点击，不受滚动影响
        double fixedAreaTop = getContentY() + 1;
        double fixedAreaBottom = separatorY + 1;
        if (mouseY >= fixedAreaTop && mouseY <= fixedAreaBottom) {
            if (clickButton(noneToolButton, click)) return true;
            for (ButtonWidget b : toolButtons.values()) {
                if (clickButton(b, click)) return true;
            }
            return false; // 固定区域只处理工具按钮
        }

        // 滚动区域（工具选项）：只允许点击可见区域
        double optionsAreaTop = separatorY + 1;
        double optionsAreaBottom = panelY + panelHeight - 1;
        if (mouseY < optionsAreaTop || mouseY > optionsAreaBottom) return false;

        // 根据当前激活的工具，处理对应的选项按钮

        if (ToolManager.isActive(WorkPlaneTool.INSTANCE.getId())) {
            if (planeInputVisible && planeHeightInput.mouseClicked(mouseX, mouseY, planeInputX, planeInputY, planeInputW, 14)) {
                HudClickSupport.clearPending();
                return true;
            }
            if (clickButton(planeApplyButton, click) || clickButton(planeDownButton, click) || clickButton(planeUpButton, click)
                    || clickButton(planeLockButton, click) || clickButton(planeModeButton, click) || clickButton(planeClearButton, click)) return true;
        } else if (ToolManager.isActive(SelectionTool.INSTANCE.getId())) {
            if (clickButton(clearSelectionButton, click)) return true;
        } else if (ToolManager.isActive(ProtectedZoneTool.INSTANCE.getId())) {
            if (clickButton(clearProtectedZonesButton, click)) return true;
        } else if (ToolManager.isActive(OutlineTool.INSTANCE.getId())) {
            if (clickButton(outlineModeButton, click)) return true;
            if (clickButton(clearOutlineButton, click)) return true;
        } else if (ToolManager.isActive(PathTool.INSTANCE.getId())) {
            if (clickButton(pathDrawingModeButton, click) || clickButton(pathSelectButton, click)
                    || clickButton(pathRemoveButton, click) || clickButton(pathIntentButton, click)
                    || clickButton(pathRadiusMinusButton, click) || clickButton(pathRadiusPlusButton, click)) return true;
            if (clickButton(clearPathsButton, click)) return true;
        } else if (ToolManager.isActive(BrushTool.INSTANCE.getId())) {
            if (clickButton(brushUndoButton, click) || clickButton(brushRedoButton, click)) return true;
            if (clickButton(brushModeButton, click)) return true;
            if (clickButton(brushRadiusMinusButton, click)) return true;
            if (clickButton(brushRadiusPlusButton, click)) return true;
            if (clickButton(clearBrushSelectionButton, click)) return true;
        } else if (ToolManager.isActive(SymmetryTool.INSTANCE.getId())) {
            if (clickButton(symmetryModeButton, click)) return true;
            if (clickButton(clearSymmetryButton, click)) return true;
        } else if (ToolManager.isActive(SemanticLabelTool.INSTANCE.getId())) {
            // 标签名输入框（使用 drawContents 缓存的 bounds）
            if (labelNameInputBoundsValid) {
                if (labelNameInput.mouseClicked(mouseX, mouseY, labelNameInputX, labelNameInputY, labelNameInputW, labelNameInputH)) {
                    HudClickSupport.clearPending();
                    return true;
                }
            }
            if (labelRangeSlider != null && labelRangeSlider.visible && labelRangeSlider.isMouseOver(mouseX, mouseY)) {
                HudClickSupport.clearPending();
                labelNameInput.setFocused(false);
                activeSlider = labelRangeSlider;
                labelRangeSlider.dragWithMouse(mouseX);
                return true;
            }
            if (clickButton(clearLabelsButton, click)) return true;
        }

        // 锚点按钮（无论是否有激活工具都显示，因为锚点可以在没有工具时设置）
        return clickButton(clearAnchorButton, click);
    }

    @Override
    public void mouseScrolled(double mouseX, double mouseY, double amount) {
        // 只有在面板内容区内滚动才处理
        if (!isMouseOver(mouseX, mouseY)) return;
        // 只在选项区域（滚动区域）滚动，固定区域不滚动
        double optionsAreaTop = separatorY + 1;
        double optionsAreaBottom = panelY + panelHeight - 1;
        if (mouseY < optionsAreaTop || mouseY > optionsAreaBottom) return;
        int step = 12;
        scrollY = (int) Math.round(scrollY - amount * step);
        if (scrollY < 0) scrollY = 0;
        if (scrollY > maxScrollY) scrollY = maxScrollY;
    }

    @Override
    public void keyPressed(int keyCode, int scanCode, int modifiers) {
        ensureWidgets();
        if (ToolManager.isActive(WorkPlaneTool.INSTANCE.getId()) && planeHeightInput.isFocused()) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) applyPlaneHeight();
            else planeHeightInput.keyPressed(keyCode, modifiers);
            return;
        }
        // 仅当输入框 focused 时消费键盘
        if (labelNameInput.keyPressed(keyCode, modifiers)) {
            SemanticLabelTool.INSTANCE.setPendingName(labelNameInput.getText());
        }
    }

    @Override
    public void charTyped(char chr) {
        ensureWidgets();
        if (ToolManager.isActive(WorkPlaneTool.INSTANCE.getId()) && planeHeightInput.charTyped(chr)) return;
        if (ToolManager.isActive(SemanticLabelTool.INSTANCE.getId()) && labelNameInput.charTyped(chr)) {
            SemanticLabelTool.INSTANCE.setPendingName(labelNameInput.getText());
        }
    }

    private static final Text LABEL_RANGE_TOOLTIP =
            Text.literal("设置标签作用范围（方块），用于提示 AI 该标签影响的周边区域");

    @Override
    protected boolean drawCustomTooltip(DrawContext ctx, double mouseX, double mouseY) {
        ensureWidgets();
        if (drawWidgetMessageTooltip(ctx, mouseX, mouseY, noneToolButton)) return true;
        for (ButtonWidget b : toolButtons.values()) {
            if (drawWidgetMessageTooltip(ctx, mouseX, mouseY, b)) return true;
        }
        ButtonWidget[] extras = {
                clearAnchorButton, clearSelectionButton, clearProtectedZonesButton,
                outlineModeButton, clearOutlineButton, clearPathsButton,
                brushModeButton, brushRadiusMinusButton, brushRadiusPlusButton,
                clearBrushSelectionButton, symmetryModeButton, clearSymmetryButton,
                clearLabelsButton
        };
        for (ButtonWidget b : extras) {
            if (drawWidgetMessageTooltip(ctx, mouseX, mouseY, b)) return true;
        }
        return labelRangeSlider != null
                && drawWidgetTooltip(ctx, mouseX, mouseY, labelRangeSlider, LABEL_RANGE_TOOLTIP);
    }

    @Override
    public boolean wantsKeyboardInput() {
        return ToolManager.isActive(SemanticLabelTool.INSTANCE.getId()) && labelNameInput.isFocused()
                || ToolManager.isActive(WorkPlaneTool.INSTANCE.getId()) && planeHeightInput.isFocused();
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        ensureWidgets();
        if (button != 0) return false;
        if (activeSlider instanceof LabelRangeSlider slider) {
            slider.dragWithMouse(mouseX);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        ensureWidgets();
        if (button != 0) return false;
        Click click = new Click(mouseX, mouseY, new MouseInput(button, 0));
        boolean handled = HudClickSupport.release(click);
        if (activeSlider != null) {
            handled |= activeSlider.mouseReleased(click);
            activeSlider = null;
        }
        return handled;
    }

    private static double rangeToValue(int range) {
        int clamped = Math.max(LABEL_RANGE_MIN, Math.min(LABEL_RANGE_MAX, range));
        return (clamped - LABEL_RANGE_MIN) / (double) (LABEL_RANGE_MAX - LABEL_RANGE_MIN);
    }

    private static int valueToRange(double value) {
        double v = Math.max(0.0, Math.min(1.0, value));
        return LABEL_RANGE_MIN + (int) Math.round(v * (LABEL_RANGE_MAX - LABEL_RANGE_MIN));
    }

    private static class LabelRangeSlider extends SliderWidget {
        private static final double HANDLE_PAD = 4.0;

        public LabelRangeSlider(int x, int y, int width, int height, Text message, double value) {
            super(x, y, width, height, message, value);
            updateMessage();
        }

        void dragWithMouse(double mouseX) {
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

