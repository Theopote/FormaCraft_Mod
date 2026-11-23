package com.formacraft.client.ui.panel.tool;

import com.formacraft.client.ui.toast.HudToast;

import com.formacraft.client.interaction.AnchorState;

import com.formacraft.client.tool.FormacraftTool;

import com.formacraft.client.tool.WorkPlaneTool;

import com.formacraft.client.tool.SymmetryTool;

import com.formacraft.client.tool.SelectionTool;

import com.formacraft.client.tool.SemanticLabelTool;

import com.formacraft.client.tool.ProtectedZoneTool;

import com.formacraft.client.tool.BrushTool;

import com.formacraft.client.tool.PathTool;

import com.formacraft.client.tool.OutlineTool;

import com.formacraft.client.tool.ToolManager;

import com.formacraft.client.ui.panel.ToolPanel;
import com.formacraft.client.ui.UiTheme;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.*;

public final class ToolLayoutRenderer {
    private ToolLayoutRenderer() {}
    public static void drawContents(ToolPanel host, DrawContext ctx) {

        host.ensureWidgets();

        int x = host.getPanelX() + ToolPanel.CONTENT_PADDING;
        int w = host.getPanelWidth() - ToolPanel.CONTENT_PADDING * 2;

        // 每帧重置 bounds（如果本帧没画到输入框，就不允许点击命中它）
        host.labelNameInputBoundsValid = false;
        host.planeInputVisible = false;

        host.drawContentBackgroundPublic(ctx);

        // ====================
        // 第一部分：固定区域（工具列表，不滚动）
        // ====================
        int fixedY = host.getContentY() + ToolPanel.CONTENT_PADDING;

        // 第一行：当前工具按钮（独占一行）
        host.noneToolButton.setMessage(Text.literal(ToolManager.getActiveTool() == null ? "当前工具：锚点" :
                "当前工具：" + ToolManager.getActiveTool().getDisplayName().getString()));
        host.noneToolButton.setPosition(x, fixedY);
        host.noneToolButton.setWidth(w);
        host.noneToolButton.visible = true;
        host.noneToolButton.active = true;
        host.noneToolButton.render(ctx, (int) host.getScaledMouseX(), (int) host.getScaledMouseY(), 0f);
        fixedY += ToolPanel.LABEL_OFFSET;

        // 工具列表（插件化）：每行两个按钮
        int buttonWidth = (w - 4) / 2; // 两个按钮之间的间距为4
        int buttonIndex = 0;
        int currentRowY = fixedY;
        
        for (Map.Entry<String, ButtonWidget> e : host.toolButtons.entrySet()) {
            String id = e.getKey();
            ButtonWidget b = e.getValue();
            if (b == null) continue;
            boolean active = ToolManager.isActive(id);
            Text base = host.toolBaseLabels.get(id);
            if (base == null) base = b.getMessage();
            // 关键：不要基于 b.getMessage() 累积拼接；每帧都从 base 重新生成
            b.setMessage(Text.literal((active ? "▶ " : "") + base.getString()));
            
            // 计算按钮位置：每行两个
            int buttonX = (buttonIndex % 2 == 0) ? x : (x + buttonWidth + 4);
            b.setPosition(buttonX, currentRowY);
            b.setWidth(buttonWidth);
            b.visible = true;
            b.active = true;
            b.render(ctx, (int) host.getScaledMouseX(), (int) host.getScaledMouseY(), 0f);
            
            buttonIndex++;
            // 每两个按钮换一行
            if (buttonIndex % 2 == 0) {
                currentRowY += ToolPanel.LABEL_OFFSET;
            }
        }
        
        // 如果最后一个按钮是奇数个，需要换行
        if (buttonIndex % 2 != 0) {
            currentRowY += ToolPanel.LABEL_OFFSET;
        }
        
        fixedY = currentRowY;
        fixedY += 4; // 减小到分割线的距离
        host.separatorY = fixedY;

        // 绘制分隔线
        int separatorX0 = host.contentScissorLeft();
        int separatorX1 = host.contentScissorRight();
        ctx.fill(separatorX0, host.separatorY, separatorX1, host.separatorY + 1, UiTheme.DIVIDER_SECTION);

        // ====================
        // 第二部分：滚动区域（当前工具选项）
        // ====================
        int optionsStartY = host.separatorY + 1 + ToolPanel.CONTENT_PADDING;
        int optionsY = optionsStartY - host.scrollY;
        int optionsAreaBottom = host.getPanelY() + host.getPanelHeight() - 1;
        int optionsVisibleHeight = optionsAreaBottom - optionsStartY;

        // 内容裁剪（仅裁剪滚动区域）
        int sx0 = host.getPanelX() + 1;
        int sx1 = host.getPanelX() + host.getPanelWidth() - 1;
        if (sx1 > sx0 && optionsAreaBottom > optionsStartY) ctx.enableScissor(sx0, optionsStartY, sx1, optionsAreaBottom);
        try {
        // 根据当前激活的工具，只渲染对应工具的选项
        FormacraftTool activeTool = ToolManager.getActiveTool();
        String activeToolId = activeTool != null ? activeTool.getId() : null;

        String source = OutlineTool.INSTANCE.hasShape() ? "轮廓（优先于选区和锚点）"
                : SelectionTool.INSTANCE.hasSelection() ? "选区底面中心（优先于锚点）"
                : AnchorState.hasAnchor() ? "显式锚点" : "鼠标命中／玩家位置";
        optionsY = host.drawWrappedText(ctx, Text.literal("默认定位：" + source), x, optionsY, w, 0xFF88DDFF);
        if (WorkPlaneTool.INSTANCE.state().fixed()) {
            optionsY = host.drawWrappedText(ctx, Text.literal("固定基准高度：Y=" + WorkPlaneTool.INSTANCE.state().height()
                    + "（新建时生效，修改选区保持原坐标）"), x, optionsY + 2, w, 0xFF66DDFF);
        }
        optionsY = host.drawWrappedText(ctx, Text.literal("保留数据：选区" + (SelectionTool.INSTANCE.hasSelection() ? "有" : "无")
                + " · 轮廓" + (OutlineTool.INSTANCE.hasShape() ? "有" : "无")
                + " · 保护区" + ProtectedZoneTool.INSTANCE.getZones().size()
                + " · 路径" + PathTool.INSTANCE.getPathCount()
                + " · 笔刷" + BrushTool.INSTANCE.getSelectedCount()), x, optionsY + 2, w, 0xFFAAAAAA);
        optionsY = host.drawWrappedText(ctx, Text.literal("切换工具不会清除已完成范围。修改区域模式优先使用选区。"),
                x, optionsY + 2, w, 0xFFAAAAAA);
        optionsY += 6;


        if (activeToolId != null) {
            // 显示当前工具名称
            optionsY = host.drawWrappedText(ctx, 
                    Text.literal("▼ " + activeTool.getDisplayName().getString() + "选项"),
                    x, optionsY, w, 0xFFFFFFFF);
            optionsY += 2; // 小间距
        } else {
            // 没有激活工具时，显示提示
            optionsY = host.drawWrappedText(ctx, 
                    Text.literal("面板外右键设置锚点"),
                    x, optionsY, w, 0xFFAAAAAA);
            optionsY += 2; // 小间距
            
            // 没有工具时也显示锚点选项（因为锚点是在没有工具时设置的）
            optionsY += ToolPanel.FIELD_SPACING;
            String anchorText = AnchorState.hasAnchor() ? ("锚点：" + AnchorState.get().getX() + "," + AnchorState.get().getY() + "," + AnchorState.get().getZ()
                    + "  facing=" + AnchorState.getFacing().name())
                    : "锚点：未设置（面板外右键设置）";
            optionsY = host.drawWrappedText(ctx, Text.literal(anchorText), x, optionsY, w, 0xFFAAAAAA);
            optionsY += 2; // 小间距
            host.clearAnchorButton.setPosition(x, optionsY);
            host.clearAnchorButton.setWidth(w);
            host.clearAnchorButton.visible = true;
            host.clearAnchorButton.active = AnchorState.hasAnchor();
            host.clearAnchorButton.render(ctx, (int) host.getScaledMouseX(), (int) host.getScaledMouseY(), 0f);
            optionsY += ToolPanel.LABEL_OFFSET;
        }

        // 根据激活的工具ID，渲染对应的选项
        if (ToolManager.isActive(WorkPlaneTool.INSTANCE.getId())) {
            optionsY = host.drawWorkPlaneOptions(ctx, x, optionsY, w);
        } else if (ToolManager.isActive(SelectionTool.INSTANCE.getId())) {
            optionsY = host.drawSelectionToolOptions(ctx, x, optionsY, w);
        } else if (ToolManager.isActive(ProtectedZoneTool.INSTANCE.getId())) {
            optionsY = host.drawProtectedZoneToolOptions(ctx, x, optionsY, w);
        } else if (ToolManager.isActive(OutlineTool.INSTANCE.getId())) {
            optionsY = host.drawOutlineToolOptions(ctx, x, optionsY, w);
        } else if (ToolManager.isActive(PathTool.INSTANCE.getId())) {
            optionsY = host.drawPathToolOptions(ctx, x, optionsY, w);
        } else if (ToolManager.isActive(BrushTool.INSTANCE.getId())) {
            optionsY = host.drawBrushToolOptions(ctx, x, optionsY, w);
        } else if (ToolManager.isActive(SymmetryTool.INSTANCE.getId())) {
            optionsY = host.drawSymmetryToolOptions(ctx, x, optionsY, w);
        } else if (ToolManager.isActive(SemanticLabelTool.INSTANCE.getId())) {
            optionsY = host.drawSemanticLabelToolOptions(ctx, x, optionsY, w);
        }

        // 锚点状态（有工具时也显示在底部，作为通用选项）
        if (activeToolId != null) {
            optionsY += ToolPanel.FIELD_SPACING;
            String anchorText = AnchorState.hasAnchor() ? ("锚点：" + AnchorState.get().getX() + "," + AnchorState.get().getY() + "," + AnchorState.get().getZ()
                    + "  facing=" + AnchorState.getFacing().name())
                    : "锚点：未设置（面板外右键设置）";
            optionsY = host.drawWrappedText(ctx, Text.literal(anchorText), x, optionsY, w, 0xFFAAAAAA);
            optionsY += 2; // 小间距
            host.clearAnchorButton.setPosition(x, optionsY);
            host.clearAnchorButton.setWidth(w);
            host.clearAnchorButton.visible = true;
            host.clearAnchorButton.active = AnchorState.hasAnchor();
            host.clearAnchorButton.render(ctx, (int) host.getScaledMouseX(), (int) host.getScaledMouseY(), 0f);
            optionsY += ToolPanel.LABEL_OFFSET;
        }

        // 计算最大滚动（基于选项区域的内容高度）
        int optionsContentHeight = (optionsY + host.scrollY) - optionsStartY;
        host.maxScrollY = Math.max(0, optionsContentHeight - optionsVisibleHeight);
        if (host.scrollY > host.maxScrollY) host.scrollY = host.maxScrollY;
        if (host.scrollY < 0) host.scrollY = 0;
        } finally {
            if (sx1 > sx0 && optionsAreaBottom > optionsStartY) ctx.disableScissor();
        }

        // Toast：不参与滚动，也不被 scissor 裁剪（否则用户会觉得“点了没反应”）
        int toastY = host.getPanelY() + host.getPanelHeight() - ToolPanel.CONTENT_PADDING - host.client().textRenderer.fontHeight - 2;
        HudToast.render(ctx, x, toastY);
    
    }

    public static void applyPlaneHeight(ToolPanel host) {

        try {
            WorkPlaneTool.INSTANCE.setHeight(Integer.parseInt(host.planeHeightInput.getText().trim()));
            host.planeHeightInput.setFocused(false);
            HudToast.show("基准高度已设为 Y=" + WorkPlaneTool.INSTANCE.state().height());
        } catch (NumberFormatException e) {
            HudToast.show("请输入整数 Y 坐标", true);
        }
    
    }

    public static int drawWorkPlaneOptions(ToolPanel host, DrawContext ctx, int x, int y, int w) {

        var plane = WorkPlaneTool.INSTANCE.state();
        y = host.drawWrappedText(ctx, Text.literal("左键点方块顶面取高；右键锁定／解锁。拖动黄色箭头调高，Esc取消拖动。"), x, y, w, 0xFFAAAAAA);
        y = host.drawWrappedText(ctx, Text.literal(plane.hasPlane() ? "平面 Y=" + plane.height()
                + (plane.locked() ? "（已锁定）" : "（未锁定）") : "尚未设置基准平面"), x, y + 3, w, 0xFF66DDFF);
        if (!host.planeHeightInput.isFocused()) host.planeHeightInput.setText(String.valueOf(plane.hasPlane() ? plane.height() : (host.client().player == null ? 64 : host.client().player.getBlockY())));
        host.planeInputX = x; host.planeInputY = y + 2; host.planeInputW = w;
        host.planeInputVisible = true;
        host.planeHeightInput.render(ctx, x, host.planeInputY, w, 14);
        y += 18;
        y = host.drawOptionButton(ctx, host.planeApplyButton, x, y, w, true);
        y = host.drawOptionButton(ctx, host.planeDownButton, x, y, w, plane.hasPlane());
        y = host.drawOptionButton(ctx, host.planeUpButton, x, y, w, plane.hasPlane());
        host.planeLockButton.setMessage(Text.literal(plane.locked() ? "解锁平面" : "锁定平面"));
        y = host.drawOptionButton(ctx, host.planeLockButton, x, y, w, plane.hasPlane());
        host.planeModeButton.setMessage(Text.literal(plane.followsTerrain() ? "定位：跟随地形" : "定位：固定高度"));
        y = host.drawOptionButton(ctx, host.planeModeButton, x, y, w, true);
        y = host.drawOptionButton(ctx, host.planeClearButton, x, y, w, plane.hasPlane());
        if (plane.fixed()) {
            y = host.drawWrappedText(ctx, Text.literal("轮廓、路径和笔刷使用平面交点；即使前方没有方块也能绘制。已有范围不会随平面自动移动。"), x, y, w, 0xFFAAAAAA);
            if (SelectionTool.INSTANCE.hasSelection() && (plane.height() < Objects.requireNonNull(SelectionTool.INSTANCE.getMin()).getY()
                    || plane.height() > Objects.requireNonNull(SelectionTool.INSTANCE.getMax()).getY())) {
                y = host.drawWrappedText(ctx, Text.literal("基准高度超出当前选区，请调整选区高度以免生成被裁剪。"), x, y + 2, w, 0xFFFFAA66);
            }
        }
        return y + 4;
    
    }

    public static int drawSelectionToolOptions(ToolPanel host, DrawContext ctx, int x, int y, int w) {

        String status = host.getString();
        y = host.drawWrappedText(ctx, Text.literal(status), x, y, w, 0xFFAAAAAA);
        y += 2; // 小间距

        host.clearSelectionButton.setPosition(x, y);
        host.clearSelectionButton.setWidth(w);
        host.clearSelectionButton.visible = true;
        host.clearSelectionButton.active = SelectionTool.INSTANCE.hasSelection() || SelectionTool.INSTANCE.isSelecting();
        host.clearSelectionButton.render(ctx, (int) host.getScaledMouseX(), (int) host.getScaledMouseY(), 0f);
        y += ToolPanel.LABEL_OFFSET;

        return y;
    
    }

    public static String getString() {

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
        return status;
    
    }

    public static int drawProtectedZoneToolOptions(ToolPanel host, DrawContext ctx, int x, int y, int w) {

        y = host.drawWrappedText(ctx,
                Text.literal("已添加禁区：" + ProtectedZoneTool.INSTANCE.getZones().size()),
                x, y, w, 0xFFAAAAAA);
        y += 2; // 小间距

        host.clearProtectedZonesButton.setPosition(x, y);
        host.clearProtectedZonesButton.setWidth(w);
        host.clearProtectedZonesButton.visible = true;
        host.clearProtectedZonesButton.active = ProtectedZoneTool.INSTANCE.canClear();
        host.clearProtectedZonesButton.render(ctx, (int) host.getScaledMouseX(), (int) host.getScaledMouseY(), 0f);
        y += ToolPanel.LABEL_OFFSET;

        return y;
    
    }

    public static int drawOutlineToolOptions(ToolPanel host, DrawContext ctx, int x, int y, int w) {

        host.outlineModeButton.setMessage(Text.literal("模式：" + OutlineTool.INSTANCE.getMode().name()));
        host.outlineModeButton.setPosition(x, y);
        host.outlineModeButton.setWidth(w);
        host.outlineModeButton.visible = true;
        host.outlineModeButton.active = true;
        host.outlineModeButton.render(ctx, (int) host.getScaledMouseX(), (int) host.getScaledMouseY(), 0f);
        y += ToolPanel.LABEL_OFFSET;

        String instruction = switch (OutlineTool.INSTANCE.getMode()) {
            case RECTANGLE -> "左键两点框选；右键／Esc取消";
            case CIRCLE -> "左键圆心、边缘；右键／Esc取消";
            case POLYGON -> "左键加点；右键／Enter完成；Backspace退点";
            case FREE_DRAW -> "按住左键绘制，松开完成；Esc取消";
        };
        y = host.drawWrappedText(ctx, Text.literal(instruction), x, y, w, 0xFFAAAAAA);
        String status = OutlineTool.INSTANCE.isDrafting() ? "轮廓：绘制中"
                : OutlineTool.INSTANCE.hasShape() ? "轮廓：已完成（紫色区域）" : "轮廓：未设置";
        y = host.drawWrappedText(ctx, Text.literal(status), x, y + 2, w, 0xFFAAAAAA);
        if (OutlineTool.INSTANCE.hasShape()) {
            var shape = OutlineTool.INSTANCE.getShape();
            y = host.drawWrappedText(ctx, Text.literal("高度：Y=" + shape.minY() + "～" + shape.maxY()
                    + "（有选区时继承，否则延伸至世界顶端）"), x, y + 2, w, 0xFFAAAAAA);
        }
        y += 2;

        host.clearOutlineButton.setPosition(x, y);
        host.clearOutlineButton.setWidth(w);
        host.clearOutlineButton.visible = true;
        host.clearOutlineButton.active = OutlineTool.INSTANCE.hasShape() || OutlineTool.INSTANCE.isDrafting();
        host.clearOutlineButton.render(ctx, (int) host.getScaledMouseX(), (int) host.getScaledMouseY(), 0f);
        y += ToolPanel.LABEL_OFFSET;

        return y;
    
    }

    public static ButtonWidget optionButton(ToolPanel host, String label, Runnable action) {

        return ButtonWidget.builder(Text.literal(label), b -> action.run()).dimensions(0, 0, 0, ToolPanel.BUTTON_HEIGHT).build();
    
    }

    public static int drawOptionButton(ToolPanel host, DrawContext ctx, ButtonWidget button, int x, int y, int w, boolean enabled) {

        button.setPosition(x, y);
        button.setWidth(w);
        button.visible = true;
        button.active = enabled;
        button.render(ctx, (int) host.getScaledMouseX(), (int) host.getScaledMouseY(), 0f);
        return y + ToolPanel.LABEL_OFFSET;
    
    }

    public static int drawPathToolOptions(ToolPanel host, DrawContext ctx, int x, int y, int w) {

        var path = PathTool.INSTANCE;
        host.pathDrawingModeButton.setMessage(Text.literal("新路径：" + (path.isSmooth() ? "平滑曲线" : "折线")));
        y = host.drawOptionButton(ctx, host.pathDrawingModeButton, x, y, w, true);
        host.pathSelectButton.setMessage(Text.literal("生成路径：" + (path.getSelectedPathIndex() + 1)
                + "/" + path.getPathCount() + "（点击切换）"));
        y = host.drawOptionButton(ctx, host.pathSelectButton, x, y, w, path.getPathCount() > 1);
        y = host.drawOptionButton(ctx, host.pathRemoveButton, x, y, w, path.getPathCount() > 0);
        String intent = switch (path.getIntent()) {
            case GENERIC -> "自动";
            case ROAD -> "道路";
            case WALL -> "城墙";
            case BRIDGE -> "桥梁";
            case ALONG_PATH_BUILDING -> "沿线建筑";
        };
        host.pathIntentButton.setMessage(Text.literal("用途：" + intent));
        y = host.drawOptionButton(ctx, host.pathIntentButton, x, y, w, true);
        y = host.drawWrappedText(ctx, Text.literal("走廊半径：" + path.getCorridorRadius() + " 格；每次只使用选中的一条路径"), x, y, w, 0xFFAAAAAA);
        y = host.drawOptionButton(ctx, host.pathRadiusMinusButton, x, y + 2, w, path.getCorridorRadius() > 1);
        y = host.drawOptionButton(ctx, host.pathRadiusPlusButton, x, y, w, path.getCorridorRadius() < 32);

        String pathStatus = PathTool.INSTANCE.isDrafting()
                ? ("路径：绘制中（草稿点=" + PathTool.INSTANCE.getDraftPointCount() + "，右键／Enter完成，Backspace退点，Esc取消）")
                : ("路径：已完成 " + PathTool.INSTANCE.getPathCount() + " 条");
        y = host.drawWrappedText(ctx,
                Text.literal(pathStatus),
                x, y, w, 0xFFAAAAAA);
        y += 2; // 小间距

        host.clearPathsButton.setPosition(x, y);
        host.clearPathsButton.setWidth(w);
        host.clearPathsButton.visible = true;
        host.clearPathsButton.active = PathTool.INSTANCE.getPathCount() > 0 || PathTool.INSTANCE.getDraftPointCount() > 0;
        host.clearPathsButton.render(ctx, (int) host.getScaledMouseX(), (int) host.getScaledMouseY(), 0f);
        y += ToolPanel.LABEL_OFFSET;

        return y;
    
    }

    public static int drawBrushToolOptions(ToolPanel host, DrawContext ctx, int x, int y, int w) {

        y = host.drawOptionButton(ctx, host.brushUndoButton, x, y, w, BrushTool.INSTANCE.canUndo());
        y = host.drawOptionButton(ctx, host.brushRedoButton, x, y, w, BrushTool.INSTANCE.canRedo());

        y = host.drawWrappedText(ctx, Text.literal("左键涂抹；右键局部擦除；清空按钮删除全部"), x, y, w, 0xFFAAAAAA);

        y = host.drawWrappedText(ctx,
                Text.literal("笔刷：半径=" + BrushTool.INSTANCE.getRadius()
                        + "  已选中=" + BrushTool.INSTANCE.getSelectedCount()),
                x, y, w, 0xFFAAAAAA);
        y += 2; // 小间距

        host.brushModeButton.setMessage(Text.literal("笔刷模式：" + BrushTool.INSTANCE.getMode().name()));
        host.brushModeButton.setPosition(x, y);
        host.brushModeButton.setWidth(w);
        host.brushModeButton.visible = true;
        host.brushModeButton.active = true;
        host.brushModeButton.render(ctx, (int) host.getScaledMouseX(), (int) host.getScaledMouseY(), 0f);
        y += ToolPanel.LABEL_OFFSET;

        int half = (w - 4) / 2;
        host.brushRadiusMinusButton.setPosition(x, y);
        host.brushRadiusMinusButton.setWidth(half);
        host.brushRadiusMinusButton.visible = true;
        host.brushRadiusMinusButton.active = true;
        host.brushRadiusMinusButton.render(ctx, (int) host.getScaledMouseX(), (int) host.getScaledMouseY(), 0f);

        host.brushRadiusPlusButton.setPosition(x + half + 4, y);
        host.brushRadiusPlusButton.setWidth(w - half - 4);
        host.brushRadiusPlusButton.visible = true;
        host.brushRadiusPlusButton.active = true;
        host.brushRadiusPlusButton.render(ctx, (int) host.getScaledMouseX(), (int) host.getScaledMouseY(), 0f);
        y += ToolPanel.LABEL_OFFSET;

        host.clearBrushSelectionButton.setPosition(x, y);
        host.clearBrushSelectionButton.setWidth(w);
        host.clearBrushSelectionButton.visible = true;
        host.clearBrushSelectionButton.active = BrushTool.INSTANCE.getSelectedCount() > 0;
        host.clearBrushSelectionButton.render(ctx, (int) host.getScaledMouseX(), (int) host.getScaledMouseY(), 0f);
        y += ToolPanel.LABEL_OFFSET;

        return y;
    
    }

    public static int drawSymmetryToolOptions(ToolPanel host, DrawContext ctx, int x, int y, int w) {

        y = host.drawWrappedText(ctx, Text.literal("X 红 · Y 绿 · Z 蓝；网格表示镜像面。优先以选区为基准，否则使用生成定位点。"), x, y, w, 0xFFAAAAAA);

        host.symmetryModeButton.setMessage(Text.literal("模式：" + SymmetryTool.INSTANCE.getModeLabel()));
        host.symmetryModeButton.setPosition(x, y);
        host.symmetryModeButton.setWidth(w);
        host.symmetryModeButton.visible = true;
        host.symmetryModeButton.active = true;
        host.symmetryModeButton.render(ctx, (int) host.getScaledMouseX(), (int) host.getScaledMouseY(), 0f);
        y += ToolPanel.LABEL_OFFSET;

        host.clearSymmetryButton.setPosition(x, y);
        host.clearSymmetryButton.setWidth(w);
        host.clearSymmetryButton.visible = true;
        host.clearSymmetryButton.active = SymmetryTool.INSTANCE.canClearAxis();
        host.clearSymmetryButton.render(ctx, (int) host.getScaledMouseX(), (int) host.getScaledMouseY(), 0f);
        y += ToolPanel.LABEL_OFFSET;

        return y;
    
    }

    public static int drawSemanticLabelToolOptions(ToolPanel host, DrawContext ctx, int x, int y, int w) {

        ctx.drawTextWithShadow(host.client().textRenderer, Text.literal("标签名："), x, y, 0xFFAAAAAA);
        int nameInputY = y + ToolPanel.LABEL_OFFSET - 2;
        host.labelNameInput.render(ctx, x, nameInputY, w, 14);
        host.labelNameInputX = x;
        host.labelNameInputY = nameInputY;
        host.labelNameInputW = w;
        host.labelNameInputH = 14;
        host.labelNameInputBoundsValid = true;
        SemanticLabelTool.INSTANCE.setPendingName(host.labelNameInput.getText());
        y += ToolPanel.FIELD_SPACING;

        int rangeLabelY = y;
        int rangeSliderY = y + ToolPanel.LABEL_OFFSET;
        ctx.drawTextWithShadow(host.client().textRenderer,
                Text.literal("作用范围（1~40）："),
                x, rangeLabelY, 0xFFAAAAAA);

        host.labelRangeSlider.setPosition(x, rangeSliderY);
        host.labelRangeSlider.setWidth(w);
        host.labelRangeSlider.visible = true;
        host.labelRangeSlider.active = true;
        host.labelRangeSlider.render(ctx, (int) host.getScaledMouseX(), (int) host.getScaledMouseY(), 0f);
        y += ToolPanel.FIELD_SPACING;

        y = host.drawWrappedText(ctx,
                Text.literal("已添加标签：" + SemanticLabelTool.INSTANCE.getLabels().size()),
                x, y, w, 0xFFAAAAAA);
        y += 2; // 小间距

        host.clearLabelsButton.setPosition(x, y);
        host.clearLabelsButton.setWidth(w);
        host.clearLabelsButton.visible = true;
        host.clearLabelsButton.active = true;
        host.clearLabelsButton.render(ctx, (int) host.getScaledMouseX(), (int) host.getScaledMouseY(), 0f);
        y += ToolPanel.LABEL_OFFSET;

        return y;

    
    }

    public static int drawWrappedText(ToolPanel host, DrawContext ctx, Text text, int x, int y, int maxWidth, int color) {

        if (text == null) return y;
        int lineHeight = host.client().textRenderer.fontHeight;
        for (var line : host.client().textRenderer.wrapLines(text, maxWidth)) {
            ctx.drawTextWithShadow(host.client().textRenderer, line, x, y, color);
            y += lineHeight;
        }
        return y;
    
    }

    public static double getScaledMouseX(ToolPanel host) {

        return host.client().mouse.getX() / host.client().getWindow().getScaleFactor();
    
    }

    public static double getScaledMouseY(ToolPanel host) {

        return host.client().mouse.getY() / host.client().getWindow().getScaleFactor();
    
    }
}
