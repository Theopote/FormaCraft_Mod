package com.formacraft.client.ui.panel;

import com.formacraft.client.preview.*;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingType;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.model.build.Materials;
import com.formacraft.common.model.build.Features;
import com.formacraft.common.model.build.Footprint;
import com.formacraft.client.network.FormaCraftClientNetworking;
import com.formacraft.common.logging.FcaLog;
import com.formacraft.client.ui.UiTheme;
import com.formacraft.client.ui.widget.HudClickSupport;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.input.MouseInput;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

/**
 * 建造确认浮层（替代 BuildPreviewScreen）
 * 不是 Screen，而是 HUD 上的一个小面板
 */
public class BuildConfirmPanel {
    
    public static final BuildConfirmPanel INSTANCE = new BuildConfirmPanel();

    private static final FcaLog LOG = FcaLog.of("BuildConfirmPanel");
    
    private final MinecraftClient client = MinecraftClient.getInstance();
    
    private boolean visible = false;
    private BuildingSpec spec;
    private BlockPos patchOrigin;
    private java.util.List<BlockPatch> patchList;
    private java.util.List<BlockPatch> rejectedPatchList;
    private java.util.List<String> patchWarnings;
    private boolean awaitingPatchApplyResult = false;
    // 服务端同步的历史可用性（驱动 Undo/Redo 按钮）
    private boolean patchCanUndo = false;
    private boolean patchCanRedo = false;
    private UUID previewTicketId;
    @SuppressWarnings("unused")
    private UUID buildId; // 可选：用于区分不同建造请求（以后拓展）
    
    // 面板尺寸
    private int width = 300;
    private int height = 240;

    // 原版风格按钮（与 SettingsPanel 保持一致：ButtonWidget 渲染）
    private ButtonWidget confirmButton;
    private ButtonWidget cancelButton;
    private ButtonWidget applyPatchButton;
    private ButtonWidget undoPatchButton;
    private ButtonWidget redoPatchButton;

    private enum Mode {
        BUILD,
        PREVIEW,
        PATCH
    }

    private Mode mode = Mode.BUILD;

    /** PREVIEW 模式：质量报告含 Error 时需二次确认（先 /forma_confirm，再 force） */
    private boolean previewQualityErrorHint = false;
    private boolean previewQualityError = false;
    private boolean previewForceArmed = false;
    
    private BuildConfirmPanel() {}

    private boolean clickButton(ButtonWidget button, Click click) {
        return button != null && button.visible && HudClickSupport.click(button, click);
    }

    private void layoutBuildButtons(int screenW, int screenH) {
        // 放在屏幕底部居中，略高于热键栏
        int btnW = 60; // 原来的一半
        int spacing = 10;
        int centerX = screenW / 2;

        int confirmX = centerX - btnW - spacing / 2;
        int cancelX = centerX + spacing / 2;

        int btnY = screenH - 56; // 经验值：避免遮挡热键栏
        if (btnY < 16) btnY = 16;

        confirmButton.setPosition(confirmX, btnY);
        confirmButton.setWidth(btnW);
        confirmButton.visible = true;
        confirmButton.active = true;

        cancelButton.setPosition(cancelX, btnY);
        cancelButton.setWidth(btnW);
        cancelButton.visible = true;
        cancelButton.active = true;

        // BUILD 模式下不显示 patch 按钮
        if (applyPatchButton != null) applyPatchButton.visible = false;
        if (undoPatchButton != null) undoPatchButton.visible = false;
        if (redoPatchButton != null) redoPatchButton.visible = false;
    }

    private void ensureWidgets() {
        if (confirmButton != null) return;
        confirmButton = ButtonWidget.builder(Text.translatable("formacraft.preview.confirm"), b -> confirm())
                .dimensions(0, 0, 120, 16)
                .tooltip(Tooltip.of(Text.translatable("formacraft.preview.confirm.tooltip")))
                .build();
        cancelButton = ButtonWidget.builder(Text.translatable("formacraft.preview.cancel"), b -> cancel())
                .dimensions(0, 0, 120, 16)
                .tooltip(Tooltip.of(Text.translatable("formacraft.preview.cancel.tooltip")))
                .build();

        applyPatchButton = ButtonWidget.builder(Text.translatable("formacraft.preview.patch.apply"), b -> applyPatch())
                .dimensions(0, 0, 90, 16)
                .build();
        undoPatchButton = ButtonWidget.builder(Text.translatable("formacraft.preview.patch.undo"), b -> FormaCraftClientNetworking.sendPatchUndo())
                .dimensions(0, 0, 70, 16)
                .build();
        redoPatchButton = ButtonWidget.builder(Text.translatable("formacraft.preview.patch.redo"), b -> FormaCraftClientNetworking.sendPatchRedo())
                .dimensions(0, 0, 70, 16)
                .build();
    }
    
    /** 请求显示确认面板 */
    public void show(BuildingSpec spec) {
        this.mode = Mode.BUILD;
        this.spec = spec;
        this.patchOrigin = null;
        this.previewTicketId = null;
        this.patchList = null;
        this.buildId = UUID.randomUUID();
        this.visible = true;

        // 激活世界预览（与确认面板生命周期绑定）
        BuildingPreviewState.show(spec);
        PreviewModalState.lockBuild();
    }

    /**
     * 预览确认面板（无 BuildingSpec）：用于 Composite/City/Blueprint 等预览。
     * 通过命令 /forma_confirm 与 /forma_cancel 触发服务端执行。
     */
    public void showPreviewActions() {
        this.mode = Mode.PREVIEW;
        this.spec = null;
        this.patchOrigin = null;
        this.previewTicketId = null;
        this.patchList = null;
        this.buildId = UUID.randomUUID();
        this.visible = true;
        this.previewQualityError = previewQualityErrorHint;
        this.previewForceArmed = false;
        PreviewModalState.lockBuild();
    }

    /** 服务端质量摘要含 Error 时标记，供 PREVIEW 确认走 force 流程 */
    public void notePreviewQualityError() {
        previewQualityErrorHint = true;
        if (visible && mode != Mode.PATCH) {
            previewQualityError = true;
        }
    }

    /** Patch 预览（服务端签发 PreviewTicket）：显示 Apply/Undo/Redo/Cancel */
    public void showPatchPreviewFromServer(
            UUID ticketId,
            BlockPos origin,
            java.util.List<BlockPatch> accepted,
            java.util.List<BlockPatch> rejected
    ) {
        this.mode = Mode.PATCH;
        this.spec = null;
        this.buildId = UUID.randomUUID();
        this.visible = true;
        this.previewTicketId = ticketId;

        this.patchOrigin = origin != null ? origin : BlockPos.ORIGIN;
        this.patchList = (accepted != null) ? new java.util.ArrayList<>(accepted) : new java.util.ArrayList<>();
        this.rejectedPatchList = (rejected != null) ? new java.util.ArrayList<>(rejected) : new java.util.ArrayList<>();
        this.patchWarnings = new java.util.ArrayList<>();
        this.awaitingPatchApplyResult = false;
        // 乐观启用：真实可用性由服务端在首次 apply/undo/redo 后回传校正
        this.patchCanUndo = true;
        this.patchCanRedo = true;

        PatchPreviewState.setPreview(this.patchOrigin, this.patchList, this.rejectedPatchList);
        PreviewModalState.lockPatch();
    }
    
    /** 隐藏面板 */
    public void hide() {
        this.visible = false;
        this.spec = null;
        this.buildId = null;
        this.patchOrigin = null;
        this.previewTicketId = null;
        if (this.patchList != null) this.patchList.clear();
        this.patchList = null;
        if (this.rejectedPatchList != null) this.rejectedPatchList.clear();
        this.rejectedPatchList = null;
        if (this.patchWarnings != null) this.patchWarnings.clear();
        this.patchWarnings = null;
        this.awaitingPatchApplyResult = false;
        this.patchCanUndo = false;
        this.patchCanRedo = false;
        this.previewQualityErrorHint = false;
        this.previewQualityError = false;
        this.previewForceArmed = false;

        BuildingPreviewState.clear();
        OutlinePreviewState.clear(); // 关闭预览线框
        SkeletonPreviewState.clear(); // 关闭骨架预览
        PatchPreviewState.clear();   // 关闭 patch 预览
        PreviewModalState.unlock();
    }
    
    public boolean isVisible() {
        return visible;
    }

    /** 模态：确认建造（供 InputRouter 直接调用） */
    public void confirm() {
        if (mode == Mode.PATCH) {
            applyPatch();
            return;
        }
        // BUILD 与 PREVIEW 统一走 /forma_confirm：服务端从 PreviewStorage 执行（删除 ConfirmBuildPacket 双轨）。
        if (previewQualityError && !previewForceArmed) {
            runPreviewCommand("forma_confirm");
            previewForceArmed = true;
            return;
        }
        runPreviewCommand(previewQualityError ? "forma_confirm force" : "forma_confirm");
        hide();
    }

    /** 模态：取消预览（供 InputRouter 直接调用） */
    public void cancel() {
        if (mode == Mode.PREVIEW) {
            runPreviewCommand("forma_cancel");
            hide();
            return;
        }
        hide();
    }

    private void applyPatch() {
        if (mode != Mode.PATCH) return;
        if (previewTicketId == null) {
            hide();
            return;
        }
        if (awaitingPatchApplyResult) return;
        PatchPreviewState.clear();
        awaitingPatchApplyResult = true;
        if (applyPatchButton != null) applyPatchButton.active = false;
        FormaCraftClientNetworking.sendPatchConfirm(previewTicketId);
    }

    /** 服务端 Patch 应用/撤销/重做完成后的反馈（S2C）。 */
    public void onPatchApplyResult(String operation, String summary, boolean canUndo, boolean canRedo) {
        awaitingPatchApplyResult = false;
        patchCanUndo = canUndo;
        patchCanRedo = canRedo;
        if (summary == null || summary.isBlank()) return;
        if (mode != Mode.PATCH || !visible) return;
        if (patchWarnings == null) patchWarnings = new java.util.ArrayList<>();
        patchWarnings.addFirst(summary);
        // 首次 apply 会消费掉票据，禁止重复应用；undo/redo 不影响票据
        if ("apply".equals(operation)) {
            previewTicketId = null;
            if (applyPatchButton != null) applyPatchButton.active = false;
        }
        if (undoPatchButton != null) undoPatchButton.active = patchCanUndo;
        if (redoPatchButton != null) redoPatchButton.active = patchCanRedo;
    }
    
    /** 在 HUD 渲染时调用 */
    public void render(DrawContext context) {
        if (!visible || client == null) return;
        if (mode == Mode.BUILD && spec == null) return;

        ensureWidgets();
        
        int screenW = client.getWindow().getScaledWidth();
        int screenH = client.getWindow().getScaledHeight();

        // BUILD / PREVIEW 模式：只显示按钮，不画半透明面板（用户体验更轻量）
        if (mode == Mode.BUILD || mode == Mode.PREVIEW) {
            double mouseX = client.mouse.getX() / client.getWindow().getScaleFactor();
            double mouseY = client.mouse.getY() / client.getWindow().getScaleFactor();
            layoutBuildButtons(screenW, screenH);
            confirmButton.render(context, (int) mouseX, (int) mouseY, 0.0f);
            cancelButton.render(context, (int) mouseX, (int) mouseY, 0.0f);
            drawFirstMatchingHudTooltip(context, (int) mouseX, (int) mouseY,
                    new ClickableWidget[]{confirmButton, cancelButton},
                    new Text[]{
                            Text.translatable("formacraft.preview.confirm.tooltip"),
                            Text.translatable("formacraft.preview.cancel.tooltip")
                    });
            return;
        }
        
        int centerX = screenW / 2;
        int centerY = screenH / 2;
        
        int x0 = centerX - width / 2;
        int y0 = centerY - height / 2;
        int x1 = x0 + width;
        int y1 = y0 + height;
        
        // 半透明背景 + 双层 3D 边框（与侧边栏 BasePanel 一致）
        int bgColor = 0xDD000000;
        context.fill(x0, y0, x1, y1, bgColor);
        UiTheme.drawPanelBorder(context, x0, y0, x1, y1);
        
        // 标题（内缩 2px，避开边框）
        int titleY = y0 + 10;
        context.drawCenteredTextWithShadow(
                client.textRenderer,
                mode == Mode.PATCH ? Text.translatable("formacraft.preview.patch.title") : Text.translatable("formacraft.preview.title"),
                centerX,
                titleY,
                0xFFFFFF
        );
        
        int infoY = titleY + 20;
        int lineHeight = 12;
        int textX = x0 + 12;

        // 一行明确提示：避免“面板看起来什么都没显示”
        context.drawTextWithShadow(
                client.textRenderer,
                mode == Mode.PATCH ? Text.translatable("formacraft.preview.patch.hint") : Text.translatable("formacraft.preview.hint"),
                textX, infoY, 0xAAAAAA
        );
        infoY += lineHeight + 2;
        
        if (mode == Mode.PATCH) {
            int place = 0, remove = 0, replace = 0;
            if (patchList != null) {
                for (BlockPatch p : patchList) {
                    if (p == null || p.action() == null) continue;
                    String a = p.action().toLowerCase();
                    switch (a) {
                        case "place" -> place++;
                        case "remove" -> remove++;
                        case "replace" -> replace++;
                    }
                }
            }
            context.drawTextWithShadow(
                    client.textRenderer,
                    Text.translatable("formacraft.preview.patch.origin", patchOrigin.getX(), patchOrigin.getY(), patchOrigin.getZ()),
                    textX, infoY, 0xCCCCCC
            );
            infoY += lineHeight;
            context.drawTextWithShadow(
                    client.textRenderer,
                    Text.translatable(
                            "formacraft.preview.patch.summary",
                            place,
                            remove,
                            replace,
                            (rejectedPatchList != null ? rejectedPatchList.size() : 0)
                    ),
                    textX, infoY, 0xAAAAAA
            );
            infoY += lineHeight;

            if (awaitingPatchApplyResult) {
                context.drawTextWithShadow(
                        client.textRenderer,
                        Text.translatable("formacraft.preview.patch.applying"),
                        textX, infoY, 0xFFCCAA00
                );
                infoY += lineHeight;
            }

            // warnings / apply result（最多显示 3 行，避免遮挡按钮）
            if (patchWarnings != null && !patchWarnings.isEmpty()) {
                int shown = 0;
                for (int i = 0; i < patchWarnings.size(); i++) {
                    String w = patchWarnings.get(i);
                    if (w == null || w.isBlank()) continue;
                    boolean applyResultLine = i == 0 && previewTicketId == null;
                    context.drawTextWithShadow(
                            client.textRenderer,
                            Text.literal(applyResultLine ? "✓ " + w : "⚠ " + w),
                            textX, infoY, applyResultLine ? 0xFFAAFFAA : 0xFFAAAAAA
                    );
                    infoY += lineHeight;
                    if (++shown >= 3) break;
                }
            }

            // 底部按钮（patch）
            int btnY = y1 - 30;
            double mouseX = client.mouse.getX() / client.getWindow().getScaleFactor();
            double mouseY = client.mouse.getY() / client.getWindow().getScaleFactor();

            int applyX = x0 + 12;
            int undoX = applyX + 92 + 8;
            int redoX = undoX + 72 + 6;
            int cancelX = x1 - 90 - 12;

            applyPatchButton.setPosition(applyX, btnY);
            applyPatchButton.setWidth(92);
            applyPatchButton.visible = true;
            applyPatchButton.active = !awaitingPatchApplyResult && previewTicketId != null;
            applyPatchButton.render(context, (int) mouseX, (int) mouseY, 0.0f);

            undoPatchButton.setPosition(undoX, btnY);
            undoPatchButton.setWidth(72);
            undoPatchButton.visible = true;
            undoPatchButton.active = patchCanUndo;
            undoPatchButton.render(context, (int) mouseX, (int) mouseY, 0.0f);

            redoPatchButton.setPosition(redoX, btnY);
            redoPatchButton.setWidth(72);
            redoPatchButton.visible = true;
            redoPatchButton.active = patchCanRedo;
            redoPatchButton.render(context, (int) mouseX, (int) mouseY, 0.0f);

            cancelButton.setPosition(cancelX, btnY);
            cancelButton.setWidth(90);
            cancelButton.visible = true;
            cancelButton.active = true;
            cancelButton.render(context, (int) mouseX, (int) mouseY, 0.0f);
            drawFirstMatchingHudTooltip(context, (int) mouseX, (int) mouseY,
                    new ClickableWidget[]{applyPatchButton, undoPatchButton, redoPatchButton, cancelButton},
                    null);
            return;
        }

        // 建筑类型（build）
        BuildingType type = spec.getType();
        if (type != null) {
            context.drawTextWithShadow(
                    client.textRenderer,
                    Text.translatable("formacraft.preview.type", type.name()),
                    textX, infoY, 0xCCCCCC
            );
            infoY += lineHeight;
        }
        
        // 建筑风格
        BuildingStyle style = spec.getStyle();
        if (style != null) {
            context.drawTextWithShadow(
                    client.textRenderer,
                    Text.translatable("formacraft.preview.style", style.name()),
                    textX, infoY, 0xCCCCCC
            );
            infoY += lineHeight;
        }
        
        // 尺寸信息
        Footprint footprint = spec.getFootprint();
        if (footprint != null) {
            if ("circle".equals(footprint.getShape())) {
                context.drawTextWithShadow(
                        client.textRenderer,
                        Text.translatable("formacraft.preview.radius", footprint.getRadius()),
                        textX, infoY, 0xCCCCCC
                );
            } else {
                context.drawTextWithShadow(
                        client.textRenderer,
                        Text.translatable(
                                "formacraft.preview.size",
                                footprint.getWidth(),
                                footprint.getDepth()
                        ),
                        textX, infoY, 0xCCCCCC
                );
            }
            infoY += lineHeight;
        }
        
        // 高度
        context.drawTextWithShadow(
                client.textRenderer,
                Text.translatable("formacraft.preview.height", spec.getHeight()),
                textX, infoY, 0xCCCCCC
        );
        infoY += lineHeight;
        
        // 材质信息
        Materials materials = spec.getMaterials();
        if (materials != null) {
            infoY += 4;
            context.drawTextWithShadow(
                    client.textRenderer,
                    Text.translatable("formacraft.preview.materials"),
                    textX, infoY, 0xAAAAAA
            );
            infoY += lineHeight;
            
            if (materials.getWall() != null) {
                context.drawTextWithShadow(
                        client.textRenderer,
                        Text.translatable("formacraft.preview.wall", materials.getWall()),
                        textX + 8, infoY, 0x999999
                );
                infoY += lineHeight;
            }
            
            if (materials.getRoof() != null) {
                context.drawTextWithShadow(
                        client.textRenderer,
                        Text.translatable("formacraft.preview.roof", materials.getRoof()),
                        textX + 8, infoY, 0x999999
                );
                infoY += lineHeight;
            }
        }
        
        // 特性信息
        Features features = spec.getFeatures();
        if (features != null) {
            infoY += 4;
            context.drawTextWithShadow(
                    client.textRenderer,
                    Text.translatable("formacraft.preview.features"),
                    textX, infoY, 0xAAAAAA
            );
            infoY += lineHeight;
            
            if (features.hasWindows()) {
                context.drawTextWithShadow(
                        client.textRenderer,
                        Text.translatable("formacraft.preview.has_windows"),
                        textX + 8, infoY, 0x999999
                );
                infoY += lineHeight;
            }
            
            if (features.hasStairs()) {
                context.drawTextWithShadow(
                        client.textRenderer,
                        Text.translatable("formacraft.preview.has_stairs"),
                        textX + 8, infoY, 0x999999
                );
                infoY += lineHeight;
            }
        }
        
        // AI Notes
        if (spec.getNotes() != null && !spec.getNotes().isEmpty()) {
            infoY += 4;
            context.drawTextWithShadow(
                    client.textRenderer,
                    Text.translatable("formacraft.preview.notes"),
                    textX, infoY, 0xAAAAAA
            );
            infoY += lineHeight;
            
            String notes = spec.getNotes();
            int maxWidth = width - 40;
            for (var ordered : client.textRenderer.wrapLines(Text.literal(notes), maxWidth)) {
                context.drawTextWithShadow(
                        client.textRenderer,
                        ordered,
                        textX + 8, infoY, 0x999999
                );
                infoY += lineHeight;
            }
        }
        
        // 底部按钮
        int btnY = y1 - 30;
        int btnW = 120;
        int spacing = 10;
        
        int confirmX = centerX - btnW - spacing / 2;
        int cancelX = centerX + spacing / 2;
        
        double mouseX = client.mouse.getX() / client.getWindow().getScaleFactor();
        double mouseY = client.mouse.getY() / client.getWindow().getScaleFactor();
        
        // Confirm（原版 ButtonWidget 渲染）
        confirmButton.setPosition(confirmX, btnY);
        confirmButton.setWidth(btnW);
        confirmButton.visible = true;
        confirmButton.active = true;
        confirmButton.render(context, (int) mouseX, (int) mouseY, 0.0f);
        
        // Cancel（原版 ButtonWidget 渲染）
        cancelButton.setPosition(cancelX, btnY);
        cancelButton.setWidth(btnW);
        cancelButton.visible = true;
        cancelButton.active = true;
        cancelButton.render(context, (int) mouseX, (int) mouseY, 0.0f);
        drawFirstMatchingHudTooltip(context, (int) mouseX, (int) mouseY,
                new ClickableWidget[]{confirmButton, cancelButton},
                new Text[]{
                        Text.translatable("formacraft.preview.confirm.tooltip"),
                        Text.translatable("formacraft.preview.cancel.tooltip")
                });
    }

    private void drawFirstMatchingHudTooltip(DrawContext ctx, int mouseX, int mouseY,
                                             ClickableWidget[] widgets, Text[] tooltips) {
        if (client.currentScreen != null || widgets == null) {
            return;
        }
        for (int i = 0; i < widgets.length; i++) {
            ClickableWidget widget = widgets[i];
            if (widget == null || !widget.visible || !widget.isMouseOver(mouseX, mouseY)) {
                continue;
            }
            Text tip = (tooltips != null && i < tooltips.length && tooltips[i] != null)
                    ? tooltips[i]
                    : widget.getMessage();
            if (tip == null) {
                continue;
            }
            UiTheme.drawTooltip(ctx, client, java.util.Collections.singletonList(tip), mouseX, mouseY);
            return;
        }
    }
    
    /** 鼠标点击处理。返回 true 表示事件已被消费。 */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || client == null) return false;
        if (button != 0) return true; // 模态：消费掉右键等，避免落到世界

        ensureWidgets();
        
        int screenW = client.getWindow().getScaledWidth();
        int screenH = client.getWindow().getScaledHeight();

        // BUILD / PREVIEW 模式：没有面板区域，任何点击都应被消费（避免点到世界），但允许点击两个按钮
        if (mode == Mode.BUILD || mode == Mode.PREVIEW) {
            layoutBuildButtons(screenW, screenH);
            Click click = new Click(mouseX, mouseY, new MouseInput(button, 0));
            if (clickButton(confirmButton, click)) return true;
            clickButton(cancelButton, click);
            return true;
        }
        
        int centerX = screenW / 2;
        int centerY = screenH / 2;
        
        int x0 = centerX - width / 2;
        int y0 = centerY - height / 2;
        int x1 = x0 + width;
        int y1 = y0 + height;
        
        // 如果点击不在面板范围内，也消费掉，避免点到世界
        if (mouseX < x0 || mouseX > x1 || mouseY < y0 || mouseY > y1) {
            return true;
        }
        
        int btnY = y1 - 30;
        int btnW = 120;
        int spacing = 10;
        
        int confirmX = centerX - btnW - spacing / 2;
        int cancelX = centerX + spacing / 2;
        
        Click click = new Click(mouseX, mouseY, new MouseInput(button, 0));

        if (mode == Mode.PATCH) {
            // 布局需与 render 对齐
            int patchBtnY = y1 - 30;
            int applyX = x0 + 12;
            int undoX = applyX + 92 + 8;
            int redoX = undoX + 72 + 6;
            int patchCancelX = x1 - 90 - 12;

            applyPatchButton.setPosition(applyX, patchBtnY);
            applyPatchButton.setWidth(92);
            applyPatchButton.visible = true;
            applyPatchButton.active = !awaitingPatchApplyResult && previewTicketId != null;
            if (clickButton(applyPatchButton, click)) return true;

            undoPatchButton.setPosition(undoX, patchBtnY);
            undoPatchButton.setWidth(72);
            undoPatchButton.visible = true;
            undoPatchButton.active = patchCanUndo;
            if (clickButton(undoPatchButton, click)) return true;

            redoPatchButton.setPosition(redoX, patchBtnY);
            redoPatchButton.setWidth(72);
            redoPatchButton.visible = true;
            redoPatchButton.active = patchCanRedo;
            if (clickButton(redoPatchButton, click)) return true;

            cancelButton.setPosition(patchCancelX, patchBtnY);
            cancelButton.setWidth(90);
            cancelButton.visible = true;
            cancelButton.active = true;
            clickButton(cancelButton, click);
            return true;
        }

        confirmButton.setPosition(confirmX, btnY);
        confirmButton.setWidth(btnW);
        confirmButton.visible = true;
        confirmButton.active = true;
        if (clickButton(confirmButton, click)) return true;

        cancelButton.setPosition(cancelX, btnY);
        cancelButton.setWidth(btnW);
        cancelButton.visible = true;
        cancelButton.active = true;
        clickButton(cancelButton, click);

        return true; // 点击面板内部其他位置，也阻止传递到世界
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (!visible || client == null || button != 0) return false;
        Click click = new Click(mouseX, mouseY, new MouseInput(button, 0));
        return HudClickSupport.release(click);
    }
    
    /** 键盘支持：Enter 确认，Esc 取消 */
    public boolean keyPressed(int keyCode) {
        if (!visible) return false;
        
        // Esc 取消
        if (keyCode == 256) {
            cancel();
            return true;
        }
        // Enter 确认（GLFW_KEY_ENTER = 257）
        if (keyCode == 257) {
            confirm();
            return true;
        }
        
        return false;
    }
    
    /**
     * 通过客户端发送命令（兼容不同 Yarn/Minecraft 版本：优先 sendChatCommand，其次 sendChatMessage）。
     */
    private void runPreviewCommand(String commandNoSlash) {
        if (client == null) return;
        Object nh = client.getNetworkHandler();
        if (nh == null) return;
        String cmd = (commandNoSlash == null) ? "" : commandNoSlash.trim();
        if (cmd.isEmpty()) return;

        try {
            java.lang.reflect.Method m = nh.getClass().getMethod("sendChatCommand", String.class);
            m.invoke(nh, cmd);
            return;
        } catch (Throwable t) {
            LOG.warn("sendChatCommand failed cmd={}", cmd, t);
        }

        try {
            java.lang.reflect.Method m2 = nh.getClass().getMethod("sendChatMessage", String.class);
            m2.invoke(nh, "/" + cmd);
        } catch (Throwable t) {
            LOG.warn("sendChatMessage fallback failed cmd={}", cmd, t);
        }
    }
}
