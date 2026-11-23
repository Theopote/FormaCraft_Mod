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

import com.formacraft.client.ui.panel.ToolPanel;
import com.formacraft.client.tool.ToolManager;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.*;

public final class ToolWidgetBuilder {
    private ToolWidgetBuilder() {}
    public static void ensureWidgets(ToolPanel host) {

        // 工具列表按钮：如果工具数量发生变化则重建
        int c = ToolManager.toolCount();
        if (host.lastToolCount != c) {
            host.lastToolCount = c;
            host.toolButtons.clear();
            host.toolBaseLabels.clear();

            host.noneToolButton = ButtonWidget.builder(Text.literal("当前工具：锚点"), b -> ToolManager.setTool(null))
                    .dimensions(0, 0, 0, ToolPanel.BUTTON_HEIGHT)
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
                        .dimensions(0, 0, 0, ToolPanel.BUTTON_HEIGHT)
                        .tooltip(Tooltip.of(Text.literal("激活工具：" + t.getDisplayName().getString())))
                        .build();
                host.toolButtons.put(id, btn);
                host.toolBaseLabels.put(id, base);
            }
        }

        if (host.clearSelectionButton != null) return;

        host.clearAnchorButton = ButtonWidget.builder(Text.literal("清除锚点"), b -> AnchorState.clear())
                .dimensions(0, 0, 0, ToolPanel.BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("清除当前锚点（右键可重新设置）")))
                .build();

        host.clearSelectionButton = ButtonWidget.builder(Text.literal("清除选区"), b -> SelectionTool.INSTANCE.clear())
                .dimensions(0, 0, 0, ToolPanel.BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("清除当前已选定的区域")))
                .build();

        host.clearProtectedZonesButton = ButtonWidget.builder(Text.literal("清空禁区"), b -> { ProtectedZoneTool.INSTANCE.clearZones(); HudToast.show("已清空禁区与未完成框选"); })
                .dimensions(0, 0, 0, ToolPanel.BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("清空所有已添加的禁区/保护区")))
                .build();

        host.outlineModeButton = ButtonWidget.builder(Text.literal("模式：POLYGON"), b -> OutlineTool.INSTANCE.cycleMode())
                .dimensions(0, 0, 0, ToolPanel.BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("切换轮廓绘制模式")))
                .build();

        host.clearOutlineButton = ButtonWidget.builder(Text.literal("清除轮廓"), b -> OutlineTool.INSTANCE.clearShape())
                .dimensions(0, 0, 0, ToolPanel.BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("清除当前轮廓")))
                .build();

        host.pathDrawingModeButton = host.optionButton("新路径：折线", PathTool.INSTANCE::cycleDrawingMode);
        host.pathRadiusMinusButton = host.optionButton("走廊半径 -", () -> PathTool.INSTANCE.changeCorridorRadius(-1));
        host.pathRadiusPlusButton = host.optionButton("走廊半径 +", () -> PathTool.INSTANCE.changeCorridorRadius(1));
        host.pathSelectButton = host.optionButton("切换生成路径", PathTool.INSTANCE::selectNextPath);
        host.pathRemoveButton = host.optionButton("删除当前路径", PathTool.INSTANCE::removeSelectedPath);
        host.pathIntentButton = host.optionButton("用途：自动", PathTool.INSTANCE::cycleIntent);
        host.brushUndoButton = host.optionButton("撤销笔刷（Ctrl+Z）", BrushTool.INSTANCE::undo);
        host.brushRedoButton = host.optionButton("重做笔刷（Ctrl+Y）", BrushTool.INSTANCE::redo);

        host.clearPathsButton = ButtonWidget.builder(Text.literal("清空路径"), b -> PathTool.INSTANCE.clearAll())
                .dimensions(0, 0, 0, ToolPanel.BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("清除所有已完成路径以及当前草稿")))
                .build();

        host.brushModeButton = ButtonWidget.builder(Text.literal("笔刷模式：TOP"), b -> BrushTool.INSTANCE.cycleMode())
                .dimensions(0, 0, 0, ToolPanel.BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("切换笔刷高亮模式（顶面/立方体外框）")))
                .build();
        host.brushRadiusMinusButton = ButtonWidget.builder(Text.literal("半径 -"), b -> BrushTool.INSTANCE.decRadius())
                .dimensions(0, 0, 0, ToolPanel.BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("减小笔刷半径")))
                .build();
        host.brushRadiusPlusButton = ButtonWidget.builder(Text.literal("半径 +"), b -> BrushTool.INSTANCE.incRadius())
                .dimensions(0, 0, 0, ToolPanel.BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("增大笔刷半径")))
                .build();
        host.clearBrushSelectionButton = ButtonWidget.builder(Text.literal("清空笔刷选中"), b -> BrushTool.INSTANCE.clearSelected())
                .dimensions(0, 0, 0, ToolPanel.BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("清空全部笔刷范围；面板外右键只擦除局部")))
                .build();

        host.symmetryModeButton = ButtonWidget.builder(Text.literal("模式：NONE"), b -> SymmetryTool.INSTANCE.cycleMode())
                .dimensions(0, 0, 0, ToolPanel.BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("切换对称模式（自定义轴线可两点标注）")))
                .build();

        host.clearSymmetryButton = ButtonWidget.builder(Text.literal("清空对称轴"), b -> SymmetryTool.INSTANCE.clearAxis())
                .dimensions(0, 0, 0, ToolPanel.BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("清除自定义轴线（不影响预设模式）")))
                .build();

        host.clearLabelsButton = ButtonWidget.builder(Text.literal("清空标签"), b -> SemanticLabelTool.INSTANCE.clearLabels())
                .dimensions(0, 0, 0, ToolPanel.BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("清空所有区域语义标签")))
                .build();

        host.planeHeightInput.setMaxLength(12);
        host.planeLockButton = host.optionButton("锁定平面", () -> WorkPlaneTool.INSTANCE.state().toggleLock());
        host.planeModeButton = host.optionButton("模式：固定高度", () -> WorkPlaneTool.INSTANCE.state().toggleTerrain());
        host.planeDownButton = host.optionButton("高度 -1", () -> WorkPlaneTool.INSTANCE.changeHeight(-1));
        host.planeUpButton = host.optionButton("高度 +1", () -> WorkPlaneTool.INSTANCE.changeHeight(1));
        host.planeApplyButton = host.optionButton("应用 Y 坐标", host::applyPlaneHeight);
        host.planeClearButton = host.optionButton("清除基准平面", WorkPlaneTool.INSTANCE::clear);

        host.labelNameInput.setMaxLength(64);
        host.labelNameInput.setText("入口");


        // Slider（用原版 SliderWidget 渲染/拖拽，手感与 SettingsPanel 一致）
        if (host.labelRangeSlider == null) {
            int initRange = SemanticLabelTool.INSTANCE.getPendingRange();
            if (initRange < ToolPanel.LABEL_RANGE_MIN) initRange = ToolPanel.LABEL_RANGE_MIN;
            if (initRange > ToolPanel.LABEL_RANGE_MAX) initRange = ToolPanel.LABEL_RANGE_MAX;
            host.labelRangeSlider = new ToolPanel.LabelRangeSlider(0, 0, 0, ToolPanel.BUTTON_HEIGHT, Text.empty(), host.rangeToValue(initRange));
            host.labelRangeSlider.setTooltip(Tooltip.of(Text.literal("设置标签作用范围（方块），用于提示 AI 该标签影响的周边区域")));
        }
    
    }
}
