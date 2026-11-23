package com.formacraft.client.ui.panel.tool;


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
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.MouseInput;
import com.formacraft.client.ui.widget.HudClickSupport;

import java.util.*;

public final class ToolInteraction {
    private ToolInteraction() {}
    public static boolean mouseClicked(ToolPanel host, double mouseX, double mouseY, int button) {

        if (host.superMouseClicked(mouseX, mouseY, button)) return true;
        if (!host.isMouseOver(mouseX, mouseY)) {
            host.blurKeyboardFocus();
            return false;
        }
        if (button != 0) return false;

        host.ensureWidgets();
        Click click = new Click(mouseX, mouseY, new MouseInput(button, 0));

        // 固定区域（工具列表）：始终可点击，不受滚动影响
        double fixedAreaTop = host.getContentY() + 1;
        double fixedAreaBottom = host.separatorY + 1;
        if (mouseY >= fixedAreaTop && mouseY <= fixedAreaBottom) {
            if (ToolPanel.clickButton(host.noneToolButton, click)) { host.blurKeyboardFocus(); return true; }
            for (ButtonWidget b : host.toolButtons.values()) {
                if (ToolPanel.clickButton(b, click)) { host.blurKeyboardFocus(); return true; }
            }
            host.blurKeyboardFocus();
            return false; // 固定区域只处理工具按钮
        }

        // 滚动区域（工具选项）：只允许点击可见区域
        double optionsAreaTop = host.separatorY + 1;
        double optionsAreaBottom = host.getPanelY() + host.getPanelHeight() - 1;
        if (mouseY < optionsAreaTop || mouseY > optionsAreaBottom) {
            host.blurKeyboardFocus();
            return false;
        }

        // 根据当前激活的工具，处理对应的选项按钮

        if (ToolManager.isActive(WorkPlaneTool.INSTANCE.getId())) {
            if (host.planeInputVisible && host.planeHeightInput.mouseClicked(mouseX, mouseY, host.planeInputX, host.planeInputY, host.planeInputW, 14)) {
                host.labelNameInput.setFocused(false);
                HudClickSupport.clearPending();
                return true;
            }
            if (ToolPanel.clickButton(host.planeApplyButton, click) || ToolPanel.clickButton(host.planeDownButton, click) || ToolPanel.clickButton(host.planeUpButton, click)
                    || ToolPanel.clickButton(host.planeLockButton, click) || ToolPanel.clickButton(host.planeModeButton, click) || ToolPanel.clickButton(host.planeClearButton, click)) {
                host.blurKeyboardFocus();
                return true;
            }
        } else if (ToolManager.isActive(SelectionTool.INSTANCE.getId())) {
            if (ToolPanel.clickButton(host.clearSelectionButton, click)) { host.blurKeyboardFocus(); return true; }
        } else if (ToolManager.isActive(ProtectedZoneTool.INSTANCE.getId())) {
            if (ToolPanel.clickButton(host.clearProtectedZonesButton, click)) { host.blurKeyboardFocus(); return true; }
        } else if (ToolManager.isActive(OutlineTool.INSTANCE.getId())) {
            if (ToolPanel.clickButton(host.outlineModeButton, click)) { host.blurKeyboardFocus(); return true; }
            if (ToolPanel.clickButton(host.clearOutlineButton, click)) { host.blurKeyboardFocus(); return true; }
        } else if (ToolManager.isActive(PathTool.INSTANCE.getId())) {
            if (ToolPanel.clickButton(host.pathDrawingModeButton, click) || ToolPanel.clickButton(host.pathSelectButton, click)
                    || ToolPanel.clickButton(host.pathRemoveButton, click) || ToolPanel.clickButton(host.pathIntentButton, click)
                    || ToolPanel.clickButton(host.pathRadiusMinusButton, click) || ToolPanel.clickButton(host.pathRadiusPlusButton, click)) {
                host.blurKeyboardFocus();
                return true;
            }
            if (ToolPanel.clickButton(host.clearPathsButton, click)) { host.blurKeyboardFocus(); return true; }
        } else if (ToolManager.isActive(BrushTool.INSTANCE.getId())) {
            if (ToolPanel.clickButton(host.brushUndoButton, click) || ToolPanel.clickButton(host.brushRedoButton, click)) { host.blurKeyboardFocus(); return true; }
            if (ToolPanel.clickButton(host.brushModeButton, click)) { host.blurKeyboardFocus(); return true; }
            if (ToolPanel.clickButton(host.brushRadiusMinusButton, click)) { host.blurKeyboardFocus(); return true; }
            if (ToolPanel.clickButton(host.brushRadiusPlusButton, click)) { host.blurKeyboardFocus(); return true; }
            if (ToolPanel.clickButton(host.clearBrushSelectionButton, click)) { host.blurKeyboardFocus(); return true; }
        } else if (ToolManager.isActive(SymmetryTool.INSTANCE.getId())) {
            if (ToolPanel.clickButton(host.symmetryModeButton, click)) { host.blurKeyboardFocus(); return true; }
            if (ToolPanel.clickButton(host.clearSymmetryButton, click)) { host.blurKeyboardFocus(); return true; }
        } else if (ToolManager.isActive(SemanticLabelTool.INSTANCE.getId())) {
            // 标签名输入框（使用 drawContents 缓存的 bounds）
            if (host.labelNameInputBoundsValid) {
                if (host.labelNameInput.mouseClicked(mouseX, mouseY, host.labelNameInputX, host.labelNameInputY, host.labelNameInputW, host.labelNameInputH)) {
                    host.planeHeightInput.setFocused(false);
                    HudClickSupport.clearPending();
                    return true;
                }
            }
            if (host.labelRangeSlider != null && host.labelRangeSlider.visible && host.labelRangeSlider.isMouseOver(mouseX, mouseY)) {
                HudClickSupport.clearPending();
                host.blurKeyboardFocus();
                host.activeSlider = host.labelRangeSlider;
                host.labelRangeSlider.dragWithMouse(mouseX);
                return true;
            }
            if (ToolPanel.clickButton(host.clearLabelsButton, click)) { host.blurKeyboardFocus(); return true; }
        }

        // 锚点按钮（无论是否有激活工具都显示，因为锚点可以在没有工具时设置）
        if (ToolPanel.clickButton(host.clearAnchorButton, click)) { host.blurKeyboardFocus(); return true; }
        host.blurKeyboardFocus();
        return false;
    
    }

    public static boolean mouseDragged(ToolPanel host, double mouseX, double mouseY, int button, double deltaX, double deltaY) {

        host.ensureWidgets();
        if (button != 0) return false;
        if (host.activeSlider instanceof ToolPanel.LabelRangeSlider slider) {
            slider.dragWithMouse(mouseX);
            return true;
        }
        return false;
    
    }

    public static boolean mouseReleased(ToolPanel host, double mouseX, double mouseY, int button) {

        host.ensureWidgets();
        if (button != 0) return false;
        Click click = new Click(mouseX, mouseY, new MouseInput(button, 0));
        boolean handled = HudClickSupport.release(click);
        if (host.activeSlider != null) {
            handled |= host.activeSlider.mouseReleased(click);
            host.activeSlider = null;
        }
        return handled;
    
    }

    public static void mouseScrolled(ToolPanel host, double mouseX, double mouseY, double amount) {

        // 只有在面板内容区内滚动才处理
        if (!host.isMouseOver(mouseX, mouseY)) return;
        // 只在选项区域（滚动区域）滚动，固定区域不滚动
        double optionsAreaTop = host.separatorY + 1;
        double optionsAreaBottom = host.getPanelY() + host.getPanelHeight() - 1;
        if (mouseY < optionsAreaTop || mouseY > optionsAreaBottom) return;
        int step = 12;
        host.scrollY = (int) Math.round(host.scrollY - amount * step);
        if (host.scrollY < 0) host.scrollY = 0;
        if (host.scrollY > host.maxScrollY) host.scrollY = host.maxScrollY;
    
    }

    public static void keyPressed(ToolPanel host, int keyCode, int scanCode, int modifiers) {

        host.ensureWidgets();
        if (ToolManager.isActive(WorkPlaneTool.INSTANCE.getId()) && host.planeHeightInput.isFocused()) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) host.applyPlaneHeight();
            else host.planeHeightInput.keyPressed(keyCode, modifiers);
            return;
        }
        // 仅当输入框 focused 时消费键盘
        if (host.labelNameInput.keyPressed(keyCode, modifiers)) {
            SemanticLabelTool.INSTANCE.setPendingName(host.labelNameInput.getText());
        }
    
    }

    public static void charTyped(ToolPanel host, char chr) {

        host.ensureWidgets();
        if (ToolManager.isActive(WorkPlaneTool.INSTANCE.getId()) && host.planeHeightInput.charTyped(chr)) return;
        if (ToolManager.isActive(SemanticLabelTool.INSTANCE.getId()) && host.labelNameInput.charTyped(chr)) {
            SemanticLabelTool.INSTANCE.setPendingName(host.labelNameInput.getText());
        }
    
    }

    public static boolean drawCustomTooltip(ToolPanel host, DrawContext ctx, double mouseX, double mouseY) {

        host.ensureWidgets();
        if (host.drawWidgetMessageTooltip(ctx, mouseX, mouseY, host.noneToolButton)) return true;
        for (ButtonWidget b : host.toolButtons.values()) {
            if (host.drawWidgetMessageTooltip(ctx, mouseX, mouseY, b)) return true;
        }
        ButtonWidget[] extras = {
                host.clearAnchorButton, host.clearSelectionButton, host.clearProtectedZonesButton,
                host.outlineModeButton, host.clearOutlineButton, host.clearPathsButton,
                host.brushModeButton, host.brushRadiusMinusButton, host.brushRadiusPlusButton,
                host.clearBrushSelectionButton, host.symmetryModeButton, host.clearSymmetryButton,
                host.clearLabelsButton
        };
        for (ButtonWidget b : extras) {
            if (host.drawWidgetMessageTooltip(ctx, mouseX, mouseY, b)) return true;
        }
        return host.labelRangeSlider != null
                && host.drawWidgetTooltip(ctx, mouseX, mouseY, host.labelRangeSlider, ToolPanel.LABEL_RANGE_TOOLTIP);
    
    }
}
