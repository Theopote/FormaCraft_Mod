package com.formacraft.client.ui.panel;

import com.formacraft.client.preview.*;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.client.network.FormaCraftClientNetworking;
import com.formacraft.common.logging.FcaLog;
import com.formacraft.client.ui.FormaCraftHudOverlay;
import com.formacraft.client.ui.widget.HudClickSupport;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.input.MouseInput;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;
public class BuildConfirmPanel {
    
    public static final BuildConfirmPanel INSTANCE = new BuildConfirmPanel();

    static final FcaLog LOG = FcaLog.of("BuildConfirmPanel");
    
    final MinecraftClient client = MinecraftClient.getInstance();
    
    boolean visible = false;
    BuildingSpec spec;
    BlockPos patchOrigin;
    java.util.List<BlockPatch> patchList;
    java.util.List<BlockPatch> rejectedPatchList;
    java.util.List<String> patchWarnings;
    boolean awaitingPatchApplyResult = false;
    // 服务端同步的历史可用性（驱动 Undo/Redo 按钮）
    boolean patchCanUndo = false;
    boolean patchCanRedo = false;
    UUID previewTicketId;
    @SuppressWarnings("unused")
    UUID buildId; // 可选：用于区分不同建造请求（以后拓展）
    
    // 面板尺寸
    final PatchPanelPlacement patchPlacement = new PatchPanelPlacement();
    boolean draggingPatch;
    double patchDragX,patchDragY;

    // 原版风格按钮（与 SettingsPanel 保持一致：ButtonWidget 渲染）
    ButtonWidget confirmButton;
    ButtonWidget cancelButton;
    ButtonWidget applyPatchButton;
    ButtonWidget undoPatchButton;
    ButtonWidget redoPatchButton;

    enum Mode {
        BUILD,
        PREVIEW,
        PATCH
    }

    Mode mode = Mode.BUILD;
    boolean previewQualityErrorHint = false;
    boolean previewQualityError = false;
    boolean previewForceArmed = false;
    
    private BuildConfirmPanel() {}
    private static void releaseChatKeyboardFocus() {
        if (FormaCraftHudOverlay.CHAT_PANEL != null) {
            FormaCraftHudOverlay.CHAT_PANEL.blurInput();
        }
    }

    boolean clickButton(ButtonWidget button, Click click) { return BuildConfirmUi.clickButton(button, click); }

    void layoutBuildButtons(int screenW, int screenH) { BuildConfirmUi.layoutBuildButtons(this, screenW, screenH); }

    void ensureWidgets() { BuildConfirmUi.ensureWidgets(this); }
    public void show(BuildingSpec spec) {
        this.mode = Mode.BUILD;
        this.spec = spec;
        this.patchOrigin = null;
        this.previewTicketId = null;
        this.patchList = null;
        this.buildId = UUID.randomUUID();
        this.visible = true;
        releaseChatKeyboardFocus();

        // 激活世界预览（与确认面板生命周期绑定）
        BuildingPreviewState.show(spec);
        PreviewModalState.lockBuild();
    }
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
        releaseChatKeyboardFocus();
        PreviewModalState.lockBuild();
    }
    public void notePreviewQualityError() {
        previewQualityErrorHint = true;
        if (visible) {
            previewQualityError = true;
        }
    }
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
        this.previewQualityError = previewQualityErrorHint;
        this.previewForceArmed = false;
        // 乐观启用：真实可用性由服务端在首次 apply/undo/redo 后回传校正
        this.patchCanUndo = true;
        this.patchCanRedo = true;

        PatchPreviewState.setPreview(this.patchOrigin, this.patchList, this.rejectedPatchList);
        releaseChatKeyboardFocus();
        PreviewModalState.lockPatch();
    }
    public void hide() {
        this.visible = false;
        draggingPatch = false;
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
    public void cancel() {
        if (mode == Mode.PREVIEW) {
            runPreviewCommand("forma_cancel");
            hide();
            return;
        }
        hide();
    }

    void applyPatch() {
        if (mode != Mode.PATCH) return;
        if (previewTicketId == null) {
            hide();
            return;
        }
        if (awaitingPatchApplyResult) return;
        // 与 BUILD 对齐：质量 ERROR 时需二次点击强制确认
        if (previewQualityError && !previewForceArmed) {
            previewForceArmed = true;
            if (patchWarnings == null) patchWarnings = new java.util.ArrayList<>();
            patchWarnings.addFirst("存在质量错误，请再次确认以强制应用。");
            return;
        }
        PatchPreviewState.clear();
        awaitingPatchApplyResult = true;
        if (applyPatchButton != null) applyPatchButton.active = false;
        FormaCraftClientNetworking.sendPatchConfirm(previewTicketId, previewQualityError);
    }
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
    public void render(DrawContext context) { BuildConfirmUi.render(this, context); }

    PatchPanelPlacement.Bounds patchBounds() { return BuildConfirmUi.patchBounds(this); }
    void layoutPatchButtons(PatchPanelPlacement.Bounds box) { BuildConfirmUi.layoutPatchButtons(this, box); }
    public boolean capturesPointer(double x,double y) {
        return visible&&client!=null&&mode==Mode.PATCH&&(draggingPatch||patchBounds().contains(x,y));
    }
    public boolean mouseDragged(double x,double y,int button,double dx,double dy) {
        if(!visible||mode!=Mode.PATCH||!draggingPatch||button!=0)return false;
        patchPlacement.move((int)Math.round(x-patchDragX),(int)Math.round(y-patchDragY));patchBounds();return true;
    }

    void drawFirstMatchingHudTooltip(DrawContext ctx, int mouseX, int mouseY, ClickableWidget[] widgets, Text[] tooltips) { BuildConfirmUi.drawFirstMatchingHudTooltip(this, ctx, mouseX, mouseY, widgets, tooltips); }
    public boolean mouseClicked(double mouseX, double mouseY, int button) { return BuildConfirmUi.mouseClicked(this, mouseX, mouseY, button); }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (!visible || client == null || button != 0) return false;
        boolean wasDragging=draggingPatch;draggingPatch=false;
        Click click = new Click(mouseX, mouseY, new MouseInput(button, 0));
        return HudClickSupport.release(click)||wasDragging;
    }
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
    void runPreviewCommand(String commandNoSlash) { BuildConfirmUi.runPreviewCommand(this, commandNoSlash); }
}
