package com.formacraft.client.ui.panel;

import com.formacraft.client.preview.*;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.client.network.FormaCraftClientNetworking;
import com.formacraft.common.logging.FcaLog;
import com.formacraft.client.ui.FormaCraftHudOverlay;
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
    private final PatchPanelPlacement patchPlacement = new PatchPanelPlacement();
    private boolean draggingPatch;
    private double patchDragX,patchDragY;

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

    /** 弹出确认条时释放聊天输入焦点，保证 WASD 可绕预览走动。 */
    private static void releaseChatKeyboardFocus() {
        if (FormaCraftHudOverlay.CHAT_PANEL != null) {
            FormaCraftHudOverlay.CHAT_PANEL.blurInput();
        }
    }

    private boolean clickButton(ButtonWidget button, Click click) {
        return button != null && button.visible && HudClickSupport.click(button, click);
    }

    private void layoutBuildButtons(int screenW, int screenH) {
        // 屏幕底部居中；UI 打开时热键栏已隐藏，尽量贴底
        int btnW = 60;
        int btnH = 16;
        int spacing = 10;
        int bottomPad = 8;
        int centerX = screenW / 2;

        int confirmX = centerX - btnW - spacing / 2;
        int cancelX = centerX + spacing / 2;

        int btnY = screenH - btnH - bottomPad;
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
                .tooltip(Tooltip.of(Text.translatable("formacraft.preview.patch.undo.tooltip")))
                .build();
        redoPatchButton = ButtonWidget.builder(Text.translatable("formacraft.preview.patch.redo"), b -> FormaCraftClientNetworking.sendPatchRedo())
                .dimensions(0, 0, 70, 16)
                .tooltip(Tooltip.of(Text.translatable("formacraft.preview.patch.redo.tooltip")))
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
        releaseChatKeyboardFocus();

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
        releaseChatKeyboardFocus();
        PreviewModalState.lockBuild();
    }

    /** 服务端质量摘要含 Error 时标记，供 PREVIEW/PATCH 确认走 force 流程 */
    public void notePreviewQualityError() {
        previewQualityErrorHint = true;
        if (visible) {
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
        this.previewQualityError = previewQualityErrorHint;
        this.previewForceArmed = false;
        // 乐观启用：真实可用性由服务端在首次 apply/undo/redo 后回传校正
        this.patchCanUndo = true;
        this.patchCanRedo = true;

        PatchPreviewState.setPreview(this.patchOrigin, this.patchList, this.rejectedPatchList);
        releaseChatKeyboardFocus();
        PreviewModalState.lockPatch();
    }
    
    /** 隐藏面板 */
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
        if(!visible || client==null || mode==Mode.BUILD && spec==null)return;
        ensureWidgets();
        int sw=client.getWindow().getScaledWidth(),sh=client.getWindow().getScaledHeight();
        int mx=(int)(client.mouse.getX()/client.getWindow().getScaleFactor());
        int my=(int)(client.mouse.getY()/client.getWindow().getScaleFactor());
        if(mode!=Mode.PATCH) {
            layoutBuildButtons(sw,sh);confirmButton.render(context,mx,my,0);cancelButton.render(context,mx,my,0);
            drawFirstMatchingHudTooltip(context,mx,my,new ClickableWidget[]{confirmButton,cancelButton},null);return;
        }
        var box=patchBounds();layoutPatchButtons(box);
        context.fill(box.x(),box.y(),box.x()+box.width(),box.y()+box.height(),0xD9000000);
        UiTheme.drawPanelBorder(context,box.x(),box.y(),box.x()+box.width(),box.y()+box.height());
        context.drawCenteredTextWithShadow(client.textRenderer,Text.translatable("formacraft.preview.patch.title"),box.x()+box.width()/2,box.y()+7,0xFFFFFFFF);
        int place=0,remove=0,replace=0;
        if(patchList!=null)for(var patch:patchList)if(patch!=null&&patch.action()!=null)switch(patch.action().toLowerCase(java.util.Locale.ROOT)) {
            case "place" -> place++;case "remove" -> remove++;case "replace" -> replace++;
        }
        var lines=new java.util.ArrayList<Text>();
        lines.add(Text.translatable("formacraft.preview.patch.drag_hint"));
        if(patchOrigin!=null)lines.add(Text.translatable("formacraft.preview.patch.origin",patchOrigin.getX(),patchOrigin.getY(),patchOrigin.getZ()));
        lines.add(Text.translatable("formacraft.preview.patch.summary",place,remove,replace,rejectedPatchList==null?0:rejectedPatchList.size()));
        if(awaitingPatchApplyResult)lines.add(Text.translatable("formacraft.preview.patch.applying"));
        if(patchWarnings!=null&&!patchWarnings.isEmpty())lines.add(Text.literal(patchWarnings.getFirst()));
        int y=box.y()+24,limit=box.buttonY(0)-4;
        context.enableScissor(box.x()+4,box.y()+22,box.x()+box.width()-4,limit);
        for(Text line:lines)for(var wrapped:client.textRenderer.wrapLines(line,box.width()-16)) {
            if(y+client.textRenderer.fontHeight>limit)break;
            context.drawTextWithShadow(client.textRenderer,wrapped,box.x()+8,y,0xFFCCCCCC);y+=client.textRenderer.fontHeight+1;
        }
        context.disableScissor();
        for(var button:new ButtonWidget[]{applyPatchButton,undoPatchButton,redoPatchButton,cancelButton})button.render(context,mx,my,0);
        drawFirstMatchingHudTooltip(context,mx,my,new ClickableWidget[]{applyPatchButton,undoPatchButton,redoPatchButton,cancelButton},null);
        if(box.contains(mx,my)&&my>=box.y()+22&&my<box.buttonY(0)&&patchWarnings!=null&&!patchWarnings.isEmpty())
            UiTheme.drawTooltip(context,client,patchWarnings.stream().filter(java.util.Objects::nonNull).map(Text::literal).map(t->(Text)t).toList(),mx,my);
    }

    private PatchPanelPlacement.Bounds patchBounds() {
        BasePanel sidebar=switch(FormaCraftHudOverlay.activePanel) {
            case CHAT -> FormaCraftHudOverlay.CHAT_PANEL;case TOOLS -> FormaCraftHudOverlay.TOOL_PANEL;
            case SETTINGS -> FormaCraftHudOverlay.SETTINGS_PANEL;case COMPONENT_LIBRARY -> FormaCraftHudOverlay.COMPONENT_LIBRARY_PANEL;
            case COMPONENT_CAPTURE -> FormaCraftHudOverlay.COMPONENT_CAPTURE_PANEL;default -> null;
        };
        int right=0;if(sidebar!=null) {sidebar.ensureLayout();right=sidebar.getPanelX()+sidebar.getPanelWidth();}
        return patchPlacement.layout(client.getWindow().getScaledWidth(),client.getWindow().getScaledHeight(),right);
    }
    private void layoutPatchButtons(PatchPanelPlacement.Bounds box) {
        var buttons=new ButtonWidget[]{applyPatchButton,undoPatchButton,redoPatchButton,cancelButton};
        for(int i=0;i<buttons.length;i++) {buttons[i].setPosition(box.buttonX(i),box.buttonY(i));buttons[i].setWidth(box.buttonWidth());buttons[i].visible=true;}
        applyPatchButton.active=!awaitingPatchApplyResult&&previewTicketId!=null;
        undoPatchButton.active=patchCanUndo;redoPatchButton.active=patchCanRedo;cancelButton.active=true;
    }
    public boolean capturesPointer(double x,double y) {
        return visible&&client!=null&&mode==Mode.PATCH&&(draggingPatch||patchBounds().contains(x,y));
    }
    public boolean mouseDragged(double x,double y,int button,double dx,double dy) {
        if(!visible||mode!=Mode.PATCH||!draggingPatch||button!=0)return false;
        patchPlacement.move((int)Math.round(x-patchDragX),(int)Math.round(y-patchDragY));patchBounds();return true;
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
    public boolean mouseClicked(double mouseX,double mouseY,int button) {
        if(!visible||client==null)return false;
        ensureWidgets();
        if(mode!=Mode.PATCH) {
            if(button!=0)return true;
            layoutBuildButtons(client.getWindow().getScaledWidth(),client.getWindow().getScaledHeight());
            var click=new Click(mouseX,mouseY,new MouseInput(button,0));
            if(!clickButton(confirmButton,click))clickButton(cancelButton,click);return true;
        }
        var box=patchBounds();if(!box.contains(mouseX,mouseY))return false;
        if(button!=0)return true;
        if(box.title(mouseX,mouseY)) {draggingPatch=true;patchDragX=mouseX-box.x();patchDragY=mouseY-box.y();return true;}
        layoutPatchButtons(box);var click=new Click(mouseX,mouseY,new MouseInput(button,0));
        for(var widget:new ButtonWidget[]{applyPatchButton,undoPatchButton,redoPatchButton,cancelButton})if(clickButton(widget,click))break;
        return true;
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (!visible || client == null || button != 0) return false;
        boolean wasDragging=draggingPatch;draggingPatch=false;
        Click click = new Click(mouseX, mouseY, new MouseInput(button, 0));
        return HudClickSupport.release(click)||wasDragging;
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
