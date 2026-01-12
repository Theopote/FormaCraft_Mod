package com.formacraft.client.ui.panel;

import com.formacraft.client.tool.FormacraftTool;
import com.formacraft.client.tool.OutlineTool;
import com.formacraft.client.tool.PathTool;
import com.formacraft.client.tool.BrushTool;
import com.formacraft.client.tool.ProtectedZoneTool;
import com.formacraft.client.tool.SemanticLabelTool;
import com.formacraft.client.tool.SelectionTool;
import com.formacraft.client.tool.ComponentTool;
import com.formacraft.client.tool.SymmetryTool;
import com.formacraft.client.tool.ToolManager;
import com.formacraft.client.interaction.AnchorState;
import com.formacraft.client.ui.widget.HudTextInput;
import com.formacraft.client.ui.toast.HudToast;
import com.formacraft.client.preview.ComponentPreviewState;
import com.formacraft.common.network.FormaCraftNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.input.MouseInput;
import net.minecraft.text.Text;
import org.jetbrains.annotations.NotNull;

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
    private final HudTextInput componentNameInput = new HudTextInput();
    private final HudTextInput componentTagsInput = new HudTextInput();
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

    private int componentNameInputX = 0;
    private int componentNameInputY = 0;
    private int componentNameInputW = 0;
    private int componentNameInputH = 0;
    private boolean componentNameInputBoundsValid = false;

    private int componentTagsInputX = 0;
    private int componentTagsInputY = 0;
    private int componentTagsInputW = 0;
    private int componentTagsInputH = 0;
    private boolean componentTagsInputBoundsValid = false;

    // ComponentTool 选项按钮
    private ButtonWidget componentCategoryButton;
    private ButtonWidget componentPickAnchorButton;
    private ButtonWidget componentClearAnchorButton;
    private ButtonWidget componentFacingButton;
    private ButtonWidget componentSaveButton;
    private ButtonWidget componentPreviewButton;

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

        // ComponentTool inputs/buttons
        componentNameInput.setMaxLength(64);
        componentNameInput.setText("New Component");
        componentTagsInput.setMaxLength(256);
        componentTagsInput.setText("Chinese,Traditional,Wood");

        componentCategoryButton = ButtonWidget.builder(Text.literal("分类：GENERIC"), b -> ComponentTool.INSTANCE.cycleCategory())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("循环切换构件分类")))
                .build();
        componentPickAnchorButton = ButtonWidget.builder(Text.literal("选择 Anchor"), b -> ComponentTool.INSTANCE.startPickAnchor())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("下一次左键点击选区内方块作为构件 anchor")))
                .build();
        componentClearAnchorButton = ButtonWidget.builder(Text.literal("清除构件锚点"), b -> ComponentTool.INSTANCE.clearAnchor())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("清除构件 anchor（将回退为选区 min）")))
                .build();
        componentFacingButton = ButtonWidget.builder(Text.literal("正面：SOUTH"), b -> ComponentTool.INSTANCE.cycleFacing())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("循环切换构件正面朝向（N/E/S/W）")))
                .build();
        componentSaveButton = ButtonWidget.builder(Text.literal("保存为构件"), b -> {
                    // v1：Anchor 必须显式选择
                    if (!SelectionTool.INSTANCE.hasSelection()) {
                        HudToast.show("保存失败：请先完成选区", true);
                        return;
                    }
                    if (ComponentTool.INSTANCE.getState().anchorWorld == null) {
                        HudToast.show("保存失败：请先选择 Anchor", true);
                        return;
                    }
                    String nm = ComponentTool.INSTANCE.getState().name;
                    if (nm == null || nm.isBlank()) {
                        HudToast.show("保存失败：名称不能为空", true);
                        return;
                    }

                    String json = ComponentTool.INSTANCE.buildCurrentComponentJson(client);
                    if (json == null || json.isBlank()) {
                        HudToast.show("保存失败：请检查选区/Anchor", true);
                        return;
                    }

                    ComponentTool.INSTANCE.markSavePending(nm);
                    HudToast.show("正在保存构件「" + nm.trim() + "」…");
                    FormaCraftNetworking.sendSaveComponent(json);
                })
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("将选区内容保存到构件库（服务端 world save）")))
                .build();

        componentPreviewButton = ButtonWidget.builder(Text.literal("预览放置"), b -> ComponentTool.INSTANCE.preview(client))
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("在锚点处预览该构件（不真正放置方块）")))
                .build();

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
        componentNameInputBoundsValid = false;
        componentTagsInputBoundsValid = false;

        // 半透明底
        ctx.fill(panelX + 1, getContentY(), panelX + panelWidth - 1, panelY + panelHeight - 1, 0x80101010);

        // ====================
        // 第一部分：固定区域（工具列表，不滚动）
        // ====================
        int fixedY = getContentY() + CONTENT_PADDING;

        // 第一行：当前工具按钮（独占一行）
        noneToolButton.setMessage(Text.literal(ToolManager.getActiveTool() == null ? "当前工具：无" :
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
        int separatorX0 = panelX + 1;
        int separatorX1 = panelX + panelWidth - 1;
        ctx.fill(separatorX0, separatorY, separatorX1, separatorY + 1, 0xFF808080);

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

        if (activeToolId != null) {
            // 显示当前工具名称
            optionsY = drawWrappedText(ctx, 
                    Text.literal("▼ " + activeTool.getDisplayName().getString() + " Options"), 
                    x, optionsY, w, 0xFFFFFFFF);
            optionsY += 2; // 小间距
        } else {
            // 没有激活工具时，显示提示
            optionsY = drawWrappedText(ctx, 
                    Text.literal("请选择一个工具/右键设置锚点"), 
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
        if (ToolManager.isActive(SelectionTool.INSTANCE.getId())) {
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
        } else if (ToolManager.isActive(ComponentTool.INSTANCE.getId())) {
            optionsY = drawComponentToolOptions(ctx, x, optionsY, w);
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
        clearSelectionButton.active = true;
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
        clearProtectedZonesButton.active = true;
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

        y = drawWrappedText(ctx,
                Text.literal(OutlineTool.INSTANCE.hasShape() ? "轮廓：已完成（紫色区域）" :
                        (OutlineTool.INSTANCE.isDrafting() ? "轮廓：绘制中（右键结束）" : "轮廓：未设置")),
                x, y, w, 0xFFAAAAAA);
        y += 2; // 小间距

        clearOutlineButton.setPosition(x, y);
        clearOutlineButton.setWidth(w);
        clearOutlineButton.visible = true;
        clearOutlineButton.active = true;
        clearOutlineButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);
        y += LABEL_OFFSET;

        return y;
    }

    // 绘制 PathTool 的选项
    private int drawPathToolOptions(DrawContext ctx, int x, int y, int w) {
        String pathStatus = PathTool.INSTANCE.isDrafting()
                ? ("路径：绘制中（草稿点=" + PathTool.INSTANCE.getDraftPointCount() + "，右键结束）")
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

    // 绘制 ComponentTool 的选项
    private int drawComponentToolOptions(DrawContext ctx, int x, int y, int w) {
        // 标题
        y = drawWrappedText(ctx, Text.literal("[ Component Tool ]"), x, y, w, 0xFFFFFFFF);
        y += 2;

        // Selection 状态提示
        String status = getString();
        y = drawWrappedText(ctx, Text.literal(status), x, y, w, 0xFFAAAAAA);
        y += 2;

        // 分类按钮
        componentCategoryButton.setMessage(Text.literal("分类：" + ComponentTool.INSTANCE.getState().category.name()));
        componentCategoryButton.setPosition(x, y);
        componentCategoryButton.setWidth(w);
        componentCategoryButton.visible = true;
        componentCategoryButton.active = true;
        componentCategoryButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);
        y += LABEL_OFFSET;

        // 名称输入
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("名称："), x, y, 0xFFAAAAAA);
        int nameInputY = y + LABEL_OFFSET - 2;
        componentNameInput.render(ctx, x, nameInputY, w, 14);
        componentNameInputX = x;
        componentNameInputY = nameInputY;
        componentNameInputW = w;
        componentNameInputH = 14;
        componentNameInputBoundsValid = true;
        String nm = componentNameInput.getText();
        if (nm != null && !nm.isBlank()) {
            ComponentTool.INSTANCE.getState().name = nm.trim();
        }
        y += FIELD_SPACING;

        // Tags 输入
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("Tags（逗号分隔）："), x, y, 0xFFAAAAAA);
        int tagsInputY = y + LABEL_OFFSET - 2;
        componentTagsInput.render(ctx, x, tagsInputY, w, 14);
        componentTagsInputX = x;
        componentTagsInputY = tagsInputY;
        componentTagsInputW = w;
        componentTagsInputH = 14;
        componentTagsInputBoundsValid = true;

        String rawTags = componentTagsInput.getText();
        java.util.Set<String> tags = new java.util.LinkedHashSet<>();
        if (rawTags != null && !rawTags.isBlank()) {
            for (String part : rawTags.split(",")) {
                String t = part.trim();
                if (!t.isEmpty()) tags.add(t);
            }
        }
        ComponentTool.INSTANCE.getState().tags = tags;
        y += FIELD_SPACING;

        // Anchor + Facing 状态
        var st = ComponentTool.INSTANCE.getState();
        String anchorText = st.anchorWorld != null
                ? ("构件锚点：" + st.anchorWorld.getX() + "," + st.anchorWorld.getY() + "," + st.anchorWorld.getZ())
                : "构件锚点：未设置（默认=选区 min）";
        String facingText = "Facing: " + (st.facing != null ? st.facing.name() : "SOUTH");
        y = drawWrappedText(ctx,
                Text.literal(anchorText + "  " + facingText + (st.pickingAnchor ? "（正在点选…）" : "")),
                x, y, w, 0xFFAAAAAA);
        y += 2;

        // Anchor / Facing 一行两个按钮（符合现有“两按钮一行”的风格）
        int half = (w - 4) / 2;
        componentPickAnchorButton.setPosition(x, y);
        componentPickAnchorButton.setWidth(half);
        componentPickAnchorButton.visible = true;
        componentPickAnchorButton.active = SelectionTool.INSTANCE.hasSelection();
        componentPickAnchorButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);

        componentFacingButton.setMessage(Text.literal("Facing: " + (st.facing != null ? st.facing.name() : "SOUTH")));
        componentFacingButton.setPosition(x + half + 4, y);
        componentFacingButton.setWidth(w - half - 4);
        componentFacingButton.visible = true;
        componentFacingButton.active = true;
        componentFacingButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);
        y += LABEL_OFFSET;

        componentClearAnchorButton.setPosition(x, y);
        componentClearAnchorButton.setWidth(w);
        componentClearAnchorButton.visible = true;
        componentClearAnchorButton.active = st.anchorWorld != null || st.pickingAnchor;
        componentClearAnchorButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);
        y += LABEL_OFFSET;

        componentSaveButton.setPosition(x, y);
        componentSaveButton.setWidth(w);
        componentSaveButton.visible = true;
        componentSaveButton.active = ComponentTool.INSTANCE.canSave();
        componentSaveButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);
        y += LABEL_OFFSET;

        componentPreviewButton.setPosition(x, y);
        componentPreviewButton.setWidth(w);
        componentPreviewButton.visible = true;
        boolean canPreview = ComponentTool.INSTANCE.canSave();
        componentPreviewButton.active = canPreview;
        componentPreviewButton.setMessage(Text.literal(ComponentPreviewState.isActive() ? "关闭预览" : "预览放置"));
        componentPreviewButton.render(ctx, (int) getScaledMouseX(), (int) getScaledMouseY(), 0f);
        y += LABEL_OFFSET;

        return y;
    }

    private static @NotNull String getString() {
        String status;
        if (SelectionTool.INSTANCE.isSelecting()) {
            status = "选区中：请完成两点框选（起点/终点）";
        } else if (SelectionTool.INSTANCE.hasSelection()) {
            var min = SelectionTool.INSTANCE.getMin();
            var max = SelectionTool.INSTANCE.getMax();
            int dx = 0, dy = 0, dz = 0;
            if (max != null && min != null) {
                dx = (max.getX() - min.getX() + 1);
                dy = (max.getY() - min.getY() + 1);
                dz = (max.getZ() - min.getZ() + 1);
            }
            status = "构件尺寸： " + dx + " × " + dy + " × " + dz + "（自动）";
        } else {
            status = "请先用“选区工具”框选一个门/窗/装饰等";
        }
        return status;
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

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0) return false;
        if (!isMouseOver(mouseX, mouseY)) return false;

        ensureWidgets();
        Click click = new Click(mouseX, mouseY, new MouseInput(button, 0));

        // 固定区域（工具列表）：始终可点击，不受滚动影响
        double fixedAreaTop = getContentY() + 1;
        double fixedAreaBottom = separatorY + 1;
        if (mouseY >= fixedAreaTop && mouseY <= fixedAreaBottom) {
            if (noneToolButton != null && noneToolButton.visible && noneToolButton.mouseClicked(click, false)) return true;
            for (ButtonWidget b : toolButtons.values()) {
                if (b != null && b.visible && b.mouseClicked(click, false)) return true;
            }
            return false; // 固定区域只处理工具按钮
        }

        // 滚动区域（工具选项）：只允许点击可见区域
        double optionsAreaTop = separatorY + 1;
        double optionsAreaBottom = panelY + panelHeight - 1;
        if (mouseY < optionsAreaTop || mouseY > optionsAreaBottom) return false;

        // 根据当前激活的工具，处理对应的选项按钮

        if (ToolManager.isActive(SelectionTool.INSTANCE.getId())) {
            if (clearSelectionButton != null && clearSelectionButton.visible && clearSelectionButton.mouseClicked(click, false)) return true;
        } else if (ToolManager.isActive(ProtectedZoneTool.INSTANCE.getId())) {
            if (clearProtectedZonesButton != null && clearProtectedZonesButton.visible && clearProtectedZonesButton.mouseClicked(click, false)) return true;
        } else if (ToolManager.isActive(OutlineTool.INSTANCE.getId())) {
            if (outlineModeButton != null && outlineModeButton.visible && outlineModeButton.mouseClicked(click, false)) return true;
            if (clearOutlineButton != null && clearOutlineButton.visible && clearOutlineButton.mouseClicked(click, false)) return true;
        } else if (ToolManager.isActive(PathTool.INSTANCE.getId())) {
            if (clearPathsButton != null && clearPathsButton.visible && clearPathsButton.mouseClicked(click, false)) return true;
        } else if (ToolManager.isActive(BrushTool.INSTANCE.getId())) {
            if (brushModeButton != null && brushModeButton.visible && brushModeButton.mouseClicked(click, false)) return true;
            if (brushRadiusMinusButton != null && brushRadiusMinusButton.visible && brushRadiusMinusButton.mouseClicked(click, false)) return true;
            if (brushRadiusPlusButton != null && brushRadiusPlusButton.visible && brushRadiusPlusButton.mouseClicked(click, false)) return true;
            if (clearBrushSelectionButton != null && clearBrushSelectionButton.visible && clearBrushSelectionButton.mouseClicked(click, false)) return true;
        } else if (ToolManager.isActive(SymmetryTool.INSTANCE.getId())) {
            if (symmetryModeButton != null && symmetryModeButton.visible && symmetryModeButton.mouseClicked(click, false)) return true;
            if (clearSymmetryButton != null && clearSymmetryButton.visible && clearSymmetryButton.mouseClicked(click, false)) return true;
        } else if (ToolManager.isActive(SemanticLabelTool.INSTANCE.getId())) {
            // 标签名输入框（使用 drawContents 缓存的 bounds）
            if (labelNameInputBoundsValid) {
                if (labelNameInput.mouseClicked(mouseX, mouseY, labelNameInputX, labelNameInputY, labelNameInputW, labelNameInputH)) {
                    return true;
                }
            }
            // 标签范围滑条（按 SettingsPanel 逻辑：mouseClicked 命中后记录 activeSlider）
            if (labelRangeSlider != null && labelRangeSlider.visible && labelRangeSlider.mouseClicked(click, false)) {
                labelNameInput.setFocused(false);
                activeSlider = labelRangeSlider;
                return true;
            }
            if (clearLabelsButton != null && clearLabelsButton.visible && clearLabelsButton.mouseClicked(click, false)) return true;
        } else if (ToolManager.isActive(ComponentTool.INSTANCE.getId())) {
            if (componentCategoryButton != null && componentCategoryButton.visible && componentCategoryButton.mouseClicked(click, false)) return true;
            if (componentPickAnchorButton != null && componentPickAnchorButton.visible && componentPickAnchorButton.mouseClicked(click, false)) return true;
            if (componentClearAnchorButton != null && componentClearAnchorButton.visible && componentClearAnchorButton.mouseClicked(click, false)) return true;
            if (componentFacingButton != null && componentFacingButton.visible && componentFacingButton.mouseClicked(click, false)) return true;
            if (componentSaveButton != null && componentSaveButton.visible && componentSaveButton.mouseClicked(click, false)) return true;
            if (componentPreviewButton != null && componentPreviewButton.visible && componentPreviewButton.mouseClicked(click, false)) return true;

            if (componentNameInputBoundsValid) {
                if (componentNameInput.mouseClicked(mouseX, mouseY, componentNameInputX, componentNameInputY, componentNameInputW, componentNameInputH)) {
                    componentTagsInput.setFocused(false);
                    labelNameInput.setFocused(false);
                    return true;
                }
            }
            if (componentTagsInputBoundsValid) {
                if (componentTagsInput.mouseClicked(mouseX, mouseY, componentTagsInputX, componentTagsInputY, componentTagsInputW, componentTagsInputH)) {
                    componentNameInput.setFocused(false);
                    labelNameInput.setFocused(false);
                    return true;
                }
            }
        }

        // 锚点按钮（无论是否有激活工具都显示，因为锚点可以在没有工具时设置）
        return clearAnchorButton != null && clearAnchorButton.visible && clearAnchorButton.mouseClicked(click, false);
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
        // 仅当输入框 focused 时消费键盘
        if (labelNameInput.keyPressed(keyCode, modifiers)) {
            SemanticLabelTool.INSTANCE.setPendingName(labelNameInput.getText());
        }
        componentNameInput.keyPressed(keyCode, modifiers);
        componentTagsInput.keyPressed(keyCode, modifiers);
    }

    @Override
    public void charTyped(char chr) {
        ensureWidgets();
        if (labelNameInput.charTyped(chr)) {
            SemanticLabelTool.INSTANCE.setPendingName(labelNameInput.getText());
        }
        componentNameInput.charTyped(chr);
        componentTagsInput.charTyped(chr);
    }

    @Override
    public boolean wantsKeyboardInput() {
        return labelNameInput.isFocused() || componentNameInput.isFocused() || componentTagsInput.isFocused();
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        ensureWidgets();
        if (button != 0) return false;
        Click click = new Click(mouseX, mouseY, new MouseInput(button, 0));
        if (activeSlider == null) return false;
        if (!activeSlider.isMouseOver(mouseX, mouseY)) return false;
        return activeSlider.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        ensureWidgets();
        if (button != 0) return false;
        Click click = new Click(mouseX, mouseY, new MouseInput(button, 0));
        boolean handled = false;
        if (activeSlider != null) {
            handled = activeSlider.mouseReleased(click);
            activeSlider = null;
        } else {
            // 兜底：如果未记录 activeSlider，也尝试释放，防止残留拖拽状态
            if (labelRangeSlider != null) handled |= labelRangeSlider.mouseReleased(click);
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
        public LabelRangeSlider(int x, int y, int width, int height, Text message, double value) {
            super(x, y, width, height, message, value);
            updateMessage();
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

