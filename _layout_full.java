package com.formacraft.client.ui.panel.capture;

import com.formacraft.client.tool.ComponentTool;
import com.formacraft.client.ui.panel.ComponentCapturePanel;
import com.formacraft.client.ui.panel.CapturePhase;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import java.util.List;

public final class ComponentCaptureLayoutRenderer {
    private ComponentCaptureLayoutRenderer() {}
    public static void drawContents(ComponentCapturePanel p, DrawContext ctx) {
        ComponentCaptureWidgetBuilder.ensureWidgets(p);
        if (!ComponentTool.INSTANCE.getState().captureActive) {
            ComponentTool.INSTANCE.getState().beginCapture(); p.syncLocalFromState();
        }
        p.captureButtons.forEach(b -> b.visible = false);

        int x = p.getPanelX() + ComponentCapturePanel.CONTENT_PADDING;
        int w = p.getPanelWidth() - ComponentCapturePanel.CONTENT_PADDING * 2;
        int y = p.getContentY() + ComponentCapturePanel.CONTENT_PADDING - p.scrollY;

        p.adaptationSection.hide();
        // 重置边界
        p.nameInputBoundsValid = false;
        p.tagsInputBoundsValid = false;
        p.socketIdInputBoundsValid = false;

        p.drawContentBackgroundPublic(ctx);

        // 启用裁剪（为底部固定按钮留出空间，与内层边框对齐）
        int scrollBottom = p.getPanelY() + p.getPanelHeight() - ComponentCapturePanel.STICKY_FOOTER_HEIGHT;
        ctx.enableScissor(p.contentScissorLeftPublic(), p.getContentY(), p.contentScissorRightPublic(), scrollBottom);

        p.phaseHeaders.reset();

        // 标题
        y = drawWrappedText(ctx, Text.translatable("formacraft.capture.title"), x, y, w, 0xFFFFFFFF);
        y += 4;
        y = p.typeSection.draw(ctx, p.client(), this::drawWrappedText, getScaledMouseX(), getScaledMouseY(), x, y, w, p.getContentY(), scrollBottom);
        var captureState = ComponentTool.INSTANCE.getState();
        if (!com.formacraft.client.tool.ComponentCaptureTypes.ready(captureState) || captureState.isCaptureSavePending()) {
            y = drawWrappedText(ctx, Text.literal(captureState.isCaptureSavePending()
                    ? "正在等待服务端保存结果…" : "先选择类型，再定义选区；现有选区会保留。"), x, y, w, 0xFFAAAAAA);
            finishCaptureLayout(ctx, x, w, y);
            return;
        }
        autoCollapseNonActivePhases();
        
        // 阶段提示（顶部弱引导）
        y = drawWrappedText(
                ctx,
                Text.translatable(
                        "formacraft.capture.phase_progress",
                        p.currentPhase.getPhaseNumber(),
                        CapturePhase.getTotalPhases(),
                        Text.translatable(p.currentPhase.getDescriptionTranslationKey()).getString()
                ),
                x, y, w, 0xFF88FF88);
        y += 6;
        
        // 分隔线
        ctx.fill(x, y, x + w, y + 1, UiTheme.DIVIDER_SUBTLE);
        y += 4;
        
        // ============ 阶段 1：选区定义 ============
        boolean phase1Collapsed = p.phaseCollapsed[0];
        y = ComponentCaptureSelectionSection.drawSection(
                ctx,
                p.client(),
                p.selectionController,
                p.thumbnailService,
                p.healthCoordinator,
                this::drawWrappedText,
                getScaledMouseX(),
                getScaledMouseY(),
                phase1Collapsed,
                p.currentPhase == CapturePhase.SELECTION,
                isPhaseComplete(CapturePhase.SELECTION),
                p.phaseHeaders,
                0,
                p.boxSelectButton,
                p.pointSelectButton,
                p.clearSelectionButton,
                ComponentCapturePanel.THUMBNAIL_SIZE,
                x,
                y,
                w
        );

        syncPlacementHintsToState();

        // ============ 阶段 2：锚点与朝向 ============
        boolean phase2Collapsed = p.phaseCollapsed[1];
        y = ComponentCaptureAnchorSection.drawSection(
                ctx,
                p.client(),
                p.selectionController,
                p.thumbnailService,
                p.healthCoordinator,
                this::drawWrappedText,
                getScaledMouseX(),
                getScaledMouseY(),
                phase2Collapsed,
                p.currentPhase == CapturePhase.ANCHOR_ORIENTATION,
                isPhaseComplete(CapturePhase.ANCHOR_ORIENTATION),
                !isPhaseComplete(CapturePhase.SELECTION),
                p.phaseHeaders,
                1,
                p.pickAnchorButton,
                p.clearAnchorButton,
                p.hostFaceButton,
                p.anchorOutsideButton,
                p.autoAnchorButton,
                p.facingButton,
                p.mirrorButton,
                x,
                y,
                w
        );

        // ============ 阶段 3：构件语义确认 ============
        boolean phase3Collapsed = p.phaseCollapsed[2];
        y = ComponentCaptureSemanticSection.drawSection(
                ctx,
                p.client(),
                this,
                p.orientationController,
                p.semanticPreview,
                this::drawWrappedText,
                p.nameInput,
                p.tagsInput,
                getScaledMouseX(),
                getScaledMouseY(),
                phase3Collapsed,
                p.currentPhase == CapturePhase.SEMANTIC,
                isPhaseComplete(CapturePhase.SEMANTIC),
                !isPhaseComplete(CapturePhase.ANCHOR_ORIENTATION),
                p.phaseHeaders,
                2,
                p.categoryButton,
                p.advancedOptionsButton,
                p.attachmentModeButton,
                p.directionalityButton,
                p.setInsideButton,
                p.setOutsideButton,
                p.setBottomButton,
                p.setTopButton,
                p.culturalStyleButton,
                p.geometryArchetypeButton,
                p.semanticSkinButton,
                p.semanticTagOnSaveButton,
                p.semanticStyleButton,
                p.semanticPartButton,
                x,
                y,
                w
        );

        if (!phase3Collapsed) {
            y = p.adaptationSection.draw(ctx, p.client(), getScaledMouseX(), getScaledMouseY(),
                    x, y, w, p.getContentY(), scrollBottom);
        }

        // ============ 阶段 4：AI 使用保障 ============
        boolean phase4Collapsed = p.phaseCollapsed[3];
        y = ComponentCaptureAiGuardSection.drawSection(
                ctx,
                p.client(),
                this,
                p.selectionController,
                this::drawWrappedText,
                p.socketIdInput,
                getScaledMouseX(),
                getScaledMouseY(),
                phase4Collapsed,
                p.currentPhase == CapturePhase.AI_GUARANTEE,
                isPhaseComplete(CapturePhase.AI_GUARANTEE),
                !isPhaseComplete(CapturePhase.SEMANTIC),
                p.phaseHeaders,
                3,
                p.socketContextButton,
                p.socketPickOriginButton,
                p.socketFacingButton,
                p.socketAddButton,
                p.socketPreviewButton,
                p.socketClearButton,
                x,
                y,
                w
        );
        
        // ============ 构件健康状态检查（新设计：健康条 + 抽屉）============
        if (isPhaseComplete(CapturePhase.ANCHOR_ORIENTATION)) {
            y = ComponentCaptureHealthDrawer.drawSection(
                    ctx,
                    p.healthCoordinator,
                    this::drawWrappedText,
                    p.scrollY,
                    x,
                    y,
                    w,
                    getScaledMouseX(),
                    getScaledMouseY(),
                    p.autoFixButton,
                    p.undoAutoFixButton
            );
        }
        
        // ============ AI 视角解释区 ============
        if (isPhaseComplete(CapturePhase.SEMANTIC)) {
            y = p.aiExplanation.renderSection(ctx, p.client(), this::drawWrappedText, x, y, w);
        }

        finishCaptureLayout(ctx, x, w, y);
    }

    public static void finishCaptureLayout(ComponentCapturePanel p, DrawContext ctx, int x, int w, int y) {
        if (!p.nameInputBoundsValid) p.nameInput.setFocused(false);
        if (!p.tagsInputBoundsValid) p.tagsInput.setFocused(false);
        if (!p.socketIdInputBoundsValid) p.socketIdInput.setFocused(false);
        // 计算最大滚动
        int contentStartY = p.getContentY() + ComponentCapturePanel.CONTENT_PADDING;
        int totalContentHeight = (y + p.scrollY) - contentStartY;
        int visibleHeight = p.getPanelHeight() - (contentStartY - p.getPanelY()) - ComponentCapturePanel.CONTENT_PADDING - ComponentCapturePanel.STICKY_FOOTER_HEIGHT;
        p.maxScrollY = Math.max(0, totalContentHeight - visibleHeight);

        if (p.scrollY > p.maxScrollY) p.scrollY = p.maxScrollY;
        if (p.scrollY < 0) p.scrollY = 0;

        ctx.disableScissor();
        drawStickyFooter(ctx, x, w);
    }

    public static void drawStickyFooter(ComponentCapturePanel p, DrawContext ctx, int x, int w) {
        int footerTop = p.getPanelY() + p.getPanelHeight() - ComponentCapturePanel.STICKY_FOOTER_HEIGHT - 4;
        ctx.fill(p.getPanelX() + 1, footerTop, p.getPanelX() + p.getPanelWidth() - 1, p.getPanelY() + p.getPanelHeight() - 1, 0xE0101010);
        ctx.fill(x, footerTop, x + w, footerTop + 1, UiTheme.DIVIDER_SUBTLE);

        int half = (w - 4) / 2;
        p.cancelButton.setPosition(x, footerTop + 4);
        p.cancelButton.setWidth(half);
        p.cancelButton.visible = true;
        var st = ComponentTool.INSTANCE.getState();
        p.cancelButton.setMessage(Text.literal(st.isCaptureSavePending() ? "取消保存" : "重置"));
        p.cancelButton.active = !st.isCaptureSavePending() || com.formacraft.client.component.ClientComponentImport.canCancelCapture();
        p.cancelButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);

        if (st.isCaptureSavePending() || !com.formacraft.client.tool.ComponentCaptureTypes.ready(st)) {
            p.saveButton.active = false;
            p.saveButton.setMessage(Text.literal(st.isCaptureSavePending() ? "保存中…" : "请先选择类型"));
        } else {
            var healthResult = p.healthCoordinator.check();
            ComponentCaptureHealthDrawer.configureSaveButton(p.saveButton, healthResult, canSaveWithoutHealthCheck());
        }

        p.saveButton.setPosition(x + half + 4, footerTop + 4);
        p.saveButton.setWidth(w - half - 4);
        p.saveButton.visible = true;
        p.saveButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
    }

    public static void autoCollapseNonActivePhases(ComponentCapturePanel p) {
        p.currentPhase = computeCurrentPhase();
        if (p.currentPhase == p.lastAutoCollapsePhase) {
            return;
        }
        p.lastAutoCollapsePhase = p.currentPhase;
        int activeIndex = p.currentPhase.ordinal();
        for (int i = 0; i < p.phaseCollapsed.length; i++) {
            p.phaseCollapsed[i] = i != activeIndex;
        }
    }

    public static CapturePhase computeCurrentPhase(ComponentCapturePanel p) {
        var st = ComponentTool.INSTANCE.getState();
        boolean hasSelection = p.selectionController.hasValidSelection(p.client());
        boolean hasAnchor = st.captureDraft.anchor.worldPos != null;
        boolean hasName = st.name != null && !st.name.isBlank() && !st.name.equals("New Component");

        if (!hasSelection) {
            return CapturePhase.SELECTION;
        }
        if (!hasAnchor) {
            return CapturePhase.ANCHOR_ORIENTATION;
        }
        if (!hasName) {
            return CapturePhase.SEMANTIC;
        }
        return CapturePhase.AI_GUARANTEE;
    }

    public static boolean isPhaseComplete(ComponentCapturePanel p, CapturePhase phase) {
        var st = ComponentTool.INSTANCE.getState();
        return switch (phase) {
            case SELECTION -> p.selectionController.hasValidSelection(p.client());
            case ANCHOR_ORIENTATION -> st.captureDraft.anchor.worldPos != null;
            case SEMANTIC ->
                    com.formacraft.client.tool.ComponentCaptureTypes.ready(st) && st.name != null && !st.name.isBlank();
            case AI_GUARANTEE -> true;
            default -> false;
        };
    }

    public static int drawWrappedText(ComponentCapturePanel p, DrawContext ctx, Text text, int x, int y, int maxWidth, int color) {
        if (text == null) return y;
        int lineHeight = p.client().textRenderer.fontHeight;
        for (var line : p.client().textRenderer.wrapLines(text, maxWidth)) {
            ctx.drawTextWithShadow(p.client().textRenderer, line, x, y, color);
            y += lineHeight;
        }
        return y;
    }
}
