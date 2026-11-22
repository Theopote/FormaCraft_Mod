package com.formacraft.client.ui.panel.capture;

import com.formacraft.client.tool.ComponentTool;
import com.formacraft.client.ui.panel.ComponentCapturePanel;
import com.formacraft.client.ui.widget.HudClickSupport;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.MouseInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

public final class ComponentCaptureActionRouter {
    private ComponentCaptureActionRouter() {}
    public static boolean clickButton(ButtonWidget button, Click click) {
        return button != null && button.visible && HudClickSupport.click(button, click);
    }

    public static boolean mouseClicked(ComponentCapturePanel p, double mouseX, double mouseY, int button) {
        if (p.superMouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0) return false;
        if (!p.isMouseOver(mouseX, mouseY)) return false;

        ComponentCaptureWidgetBuilder.ensureWidgets(p);
        Click click = new Click(mouseX, mouseY, new MouseInput(button, 0));
        var captureState = ComponentTool.INSTANCE.getState();
        if (captureState.isCaptureSavePending()) { p.blurKeyboardFocus(); ComponentCaptureActionRouter.clickButton(p.cancelButton, click); return true; }
        if (p.typeSection.click(mouseX, mouseY, click)) {
            p.nameInput.setFocused(false); p.tagsInput.setFocused(false); p.socketIdInput.setFocused(false); return true;
        }
        if (ComponentCaptureActionRouter.clickButton(p.cancelButton, click)) { p.blurKeyboardFocus(); return true; }
        if (ComponentCaptureActionRouter.clickButton(p.saveButton, click)) { p.blurKeyboardFocus(); return true; }
        if (!com.formacraft.client.tool.ComponentCaptureTypes.ready(captureState)) { p.blurKeyboardFocus(); return true; }
        if (mouseY < p.getContentY() || mouseY >= p.getPanelY() + p.getPanelHeight() - ComponentCapturePanel.STICKY_FOOTER_HEIGHT - 4) {
            p.blurKeyboardFocus();
            return true;
        }

        if (p.adaptationSection.click(click, mouseY)) { p.blurKeyboardFocus(); return true; }

        // 健康检查摘要行点击（展开/折叠抽屉）
        if (p.healthCoordinator.handleSummaryClick(mouseY, p.getPanelY(), p.scrollY)) {
            p.blurKeyboardFocus();
            return true;
        }

        Integer hitPhase = p.phaseHeaders.hitPhase(mouseX, mouseY);
        if (hitPhase != null) {
            p.phaseCollapsed[hitPhase] = !p.phaseCollapsed[hitPhase];
            p.blurKeyboardFocus();
            return true;
        }
        
        // 选择工具按钮点击
        if (ComponentCaptureActionRouter.clickButton(p.boxSelectButton, click)) { p.blurKeyboardFocus(); return true; }
        if (ComponentCaptureActionRouter.clickButton(p.pointSelectButton, click)) { p.blurKeyboardFocus(); return true; }
        if (ComponentCaptureActionRouter.clickButton(p.clearSelectionButton, click)) { p.blurKeyboardFocus(); return true; }
        
        // 输入框优先：点中则获焦，点在外面会自行失焦
        if (p.nameInputBoundsValid) {
            if (p.nameInput.mouseClicked(mouseX, mouseY, p.nameInputX, p.nameInputY, p.nameInputW, p.nameInputH)) {
                p.tagsInput.setFocused(false);
                p.socketIdInput.setFocused(false);
                p.typeSection.unfocus();
                return true;
            }
        } else {
            p.nameInput.setFocused(false);
        }

        if (p.tagsInputBoundsValid) {
            if (p.tagsInput.mouseClicked(mouseX, mouseY, p.tagsInputX, p.tagsInputY, p.tagsInputW, p.tagsInputH)) {
                p.nameInput.setFocused(false);
                p.socketIdInput.setFocused(false);
                p.typeSection.unfocus();
                return true;
            }
        } else {
            p.tagsInput.setFocused(false);
        }

        if (p.socketIdInputBoundsValid) {
            if (p.socketIdInput.mouseClicked(mouseX, mouseY, p.socketIdInputX, p.socketIdInputY, p.socketIdInputW, p.socketIdInputH)) {
                p.nameInput.setFocused(false);
                p.tagsInput.setFocused(false);
                p.typeSection.unfocus();
                return true;
            }
        } else {
            p.socketIdInput.setFocused(false);
        }

        // Phase 3: 语义配置按钮点击
        if (ComponentCaptureActionRouter.clickButton(p.attachmentModeButton, click)) return true;
        if (ComponentCaptureActionRouter.clickButton(p.directionalityButton, click)) return true;
        if (ComponentCaptureActionRouter.clickButton(p.setInsideButton, click)) return true;
        if (ComponentCaptureActionRouter.clickButton(p.setOutsideButton, click)) return true;
        if (ComponentCaptureActionRouter.clickButton(p.setBottomButton, click)) return true;
        if (ComponentCaptureActionRouter.clickButton(p.setTopButton, click)) return true;
        
        // 按钮点击
        if (ComponentCaptureActionRouter.clickButton(p.categoryButton, click)) return true;
        if (ComponentCaptureActionRouter.clickButton(p.advancedOptionsButton, click)) return true;
        if (ComponentCaptureActionRouter.clickButton(p.pickAnchorButton, click)) return true;
        if (ComponentCaptureActionRouter.clickButton(p.clearAnchorButton, click)) return true;
        if (ComponentCaptureActionRouter.clickButton(p.hostFaceButton, click)) return true;
        if (ComponentCaptureActionRouter.clickButton(p.anchorOutsideButton, click)) return true;
        if (ComponentCaptureActionRouter.clickButton(p.autoAnchorButton, click)) return true;
        if (ComponentCaptureActionRouter.clickButton(p.facingButton, click)) return true;
        if (ComponentCaptureActionRouter.clickButton(p.mirrorButton, click)) return true;
        
        if (ComponentCaptureActionRouter.clickButton(p.semanticSkinButton, click)) return true;
        if (ComponentCaptureActionRouter.clickButton(p.semanticTagOnSaveButton, click)) return true;
        if (ComponentCaptureActionRouter.clickButton(p.semanticStyleButton, click)) return true;
        if (ComponentCaptureActionRouter.clickButton(p.semanticPartButton, click)) return true;
        
        if (ComponentCaptureActionRouter.clickButton(p.socketContextButton, click)) return true;
        if (ComponentCaptureActionRouter.clickButton(p.socketPickOriginButton, click)) return true;
        if (ComponentCaptureActionRouter.clickButton(p.socketFacingButton, click)) return true;
        if (ComponentCaptureActionRouter.clickButton(p.socketAddButton, click)) return true;
        if (ComponentCaptureActionRouter.clickButton(p.socketPreviewButton, click)) return true;
        if (ComponentCaptureActionRouter.clickButton(p.socketClearButton, click)) return true;

        if (ComponentCaptureActionRouter.clickButton(p.autoFixButton, click)) return true;
        if (ComponentCaptureActionRouter.clickButton(p.undoAutoFixButton, click)) return true;
        
        if (ComponentCaptureActionRouter.clickButton(p.cancelButton, click)) return true;
        ComponentCaptureActionRouter.clickButton(p.saveButton, click);

        return true; // 消费所有面板内点击
    }

    public static boolean handleWorldClick(ComponentCapturePanel p, BlockHitResult hit, int button) {
        var st = ComponentTool.INSTANCE.getState();
        if (st.isCaptureSavePending() || !com.formacraft.client.tool.ComponentCaptureTypes.ready(st)) return true;
        p.typeSection.unfocus();
        if (hit == null) return false;
        BlockPos pos = hit.getBlockPos();
        if (pos == null) return false;

        if (ComponentCapturePanel.DEBUG_CAPTURE) {
            com.formacraft.FormacraftMod.LOGGER.debug("[ComponentCapturePanel] 世界点击: {}, 按钮: {}, 模式: {}, 标记模式: {}",
                pos, button, p.selectionController.getMode(), p.orientationController.getMarkingMode());
        }

        // Phase 3: 优先处理方向标记模式
        if (p.orientationController.isMarkingActive() && button == 0) {
            return p.orientationController.handleMarkingClick(hit, p.selectionController::isAnchorLocationAllowed);
        }

        // 右键设置锚点
        if (button == 1) {
            p.setAnchor(pos);
            return true;
        }

        return p.selectionController.handleWorldClick(p.client(), hit, button);
    }

    public static void handleWorldRelease(ComponentCapturePanel p, int button) {
        p.selectionController.handleWorldRelease(p.client(), button);
    }

    public static void mouseScrolled(ComponentCapturePanel p, double mouseX, double mouseY, double amount) {
        if (!p.isMouseOver(mouseX, mouseY)) return;
        
        // 滚动步长：每次滚轮滚动 12 像素（与 ToolPanel 和 SettingsPanel 一致）
        int step = 12;
        p.scrollY = (int) Math.round(p.scrollY - amount * step);
        
        // 限制滚动范围
        if (p.scrollY < 0) p.scrollY = 0;
        if (p.scrollY > p.maxScrollY) p.scrollY = p.maxScrollY;
    }

    public static void keyPressed(ComponentCapturePanel p, int keyCode, int scanCode, int modifiers) {
        ComponentCaptureWidgetBuilder.ensureWidgets(p);
        if (ComponentTool.INSTANCE.getState().isCaptureSavePending()) return;
        p.typeSection.keyPressed(keyCode, modifiers);
        if (p.typeSection.focused() || !com.formacraft.client.tool.ComponentCaptureTypes.ready(ComponentTool.INSTANCE.getState())) return;
        p.nameInput.keyPressed(keyCode, modifiers);
        p.tagsInput.keyPressed(keyCode, modifiers);
        p.socketIdInput.keyPressed(keyCode, modifiers);
    }

    public static void charTyped(ComponentCapturePanel p, char chr) {
        if (ComponentTool.INSTANCE.getState().isCaptureSavePending()) return;
        p.typeSection.charTyped(chr);
        if (p.typeSection.focused() || !com.formacraft.client.tool.ComponentCaptureTypes.ready(ComponentTool.INSTANCE.getState())) return;
        ComponentCaptureWidgetBuilder.ensureWidgets(p);
        p.nameInput.charTyped(chr);
        p.tagsInput.charTyped(chr);
        p.socketIdInput.charTyped(chr);
    }
}
