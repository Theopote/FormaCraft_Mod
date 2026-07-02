package com.formacraft.client.ui.panel;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingType;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.model.build.Materials;
import com.formacraft.common.model.build.Features;
import com.formacraft.common.model.build.Footprint;
import com.formacraft.common.network.FormaCraftNetworking;
import com.formacraft.client.preview.BuildingPreviewState;
import com.formacraft.client.preview.OutlinePreviewState;
import com.formacraft.client.preview.PatchPreviewState;
import com.formacraft.client.preview.PromptModeState;
import com.formacraft.client.preview.PreviewModalState;
import com.formacraft.client.preview.SkeletonPreviewState;
import com.formacraft.client.tool.ProtectedZoneTool;
import com.formacraft.client.patch.filter.ToolPatchFilter;
import com.formacraft.client.buildcontext.BuildContextResolver;
import com.formacraft.common.patch.filter.PatchFilterResult;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
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
    
    private final MinecraftClient client = MinecraftClient.getInstance();
    
    private boolean visible = false;
    private BuildingSpec spec;
    private BlockPos patchOrigin;
    private java.util.List<BlockPatch> patchList;
    private java.util.List<BlockPatch> rejectedPatchList;
    private java.util.List<String> patchWarnings;
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
    
    private BuildConfirmPanel() {}

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
        undoPatchButton = ButtonWidget.builder(Text.translatable("formacraft.preview.patch.undo"), b -> FormaCraftNetworking.sendPatchUndo())
                .dimensions(0, 0, 70, 16)
                .build();
        redoPatchButton = ButtonWidget.builder(Text.translatable("formacraft.preview.patch.redo"), b -> FormaCraftNetworking.sendPatchRedo())
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
        PreviewModalState.lockBuild();
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

        PatchPreviewState.setPreview(this.patchOrigin, this.patchList, this.rejectedPatchList);
        PreviewModalState.lockPatch();
    }

    /** @deprecated 客户端本地生成预览已废弃，请走服务端 PreviewTicket 流程 */
    @Deprecated
    public void showPatchPreview(BlockPos origin, java.util.List<BlockPatch> patches) {
        this.mode = Mode.PATCH;
        this.spec = null;
        this.buildId = UUID.randomUUID();
        this.visible = true;

        this.patchOrigin = origin != null ? origin : BlockPos.ORIGIN;
        java.util.List<BlockPatch> raw = (patches != null) ? new java.util.ArrayList<>(patches) : new java.util.ArrayList<>();

        // PatchFilter：只读 BuildContext（含主约束+禁区+restrictToSelection）
        boolean restrict = PromptModeState.restrictToSelection();
        var bc = BuildContextResolver.resolve(restrict);
        if (bc != null) bc = bc.withOrigin(this.patchOrigin);
        PatchFilterResult r = ToolPatchFilter.filter(bc, this.patchOrigin, raw);
        this.patchList = new java.util.ArrayList<>(r.accepted);
        this.rejectedPatchList = new java.util.ArrayList<>(r.rejected);
        this.patchWarnings = new java.util.ArrayList<>(r.warnings);

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
        if (mode == Mode.PREVIEW) {
            runPreviewCommand("forma_confirm");
            hide();
            return;
        }
        onConfirm();
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
        PatchPreviewState.clear();
        FormaCraftNetworking.sendPatchConfirm(previewTicketId);
        hide();
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
            return;
        }
        
        int centerX = screenW / 2;
        int centerY = screenH / 2;
        
        int x0 = centerX - width / 2;
        int y0 = centerY - height / 2;
        int x1 = x0 + width;
        int y1 = y0 + height;
        
        // 半透明背景
        int bgColor = 0xDD000000;
        context.fill(x0, y0, x1, y1, bgColor);
        
        // 手动画边框
        int borderColor = 0xFFFFFFFF;
        context.fill(x0, y0, x1, y0 + 1, borderColor);       // 上
        context.fill(x0, y1 - 1, x1, y1, borderColor);       // 下
        context.fill(x0, y0, x0 + 1, y1, borderColor);       // 左
        context.fill(x1 - 1, y0, x1, y1, borderColor);       // 右
        
        // 标题
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

            // warnings（最多显示 3 行，避免遮挡按钮）
            if (patchWarnings != null && !patchWarnings.isEmpty()) {
                int shown = 0;
                for (String w : patchWarnings) {
                    if (w == null || w.isBlank()) continue;
                    context.drawTextWithShadow(
                            client.textRenderer,
                            Text.literal("⚠ " + w),
                            textX, infoY, 0xFFAAAAAA
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
            applyPatchButton.active = true;
            applyPatchButton.render(context, (int) mouseX, (int) mouseY, 0.0f);

            undoPatchButton.setPosition(undoX, btnY);
            undoPatchButton.setWidth(72);
            undoPatchButton.visible = true;
            undoPatchButton.active = true;
            undoPatchButton.render(context, (int) mouseX, (int) mouseY, 0.0f);

            redoPatchButton.setPosition(redoX, btnY);
            redoPatchButton.setWidth(72);
            redoPatchButton.visible = true;
            redoPatchButton.active = true;
            redoPatchButton.render(context, (int) mouseX, (int) mouseY, 0.0f);

            cancelButton.setPosition(cancelX, btnY);
            cancelButton.setWidth(90);
            cancelButton.visible = true;
            cancelButton.active = true;
            cancelButton.render(context, (int) mouseX, (int) mouseY, 0.0f);
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
            if (confirmButton.mouseClicked(click, false)) return true;
            cancelButton.mouseClicked(click, false);
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
            applyPatchButton.active = true;
            if (applyPatchButton.mouseClicked(click, false)) return true;

            undoPatchButton.setPosition(undoX, patchBtnY);
            undoPatchButton.setWidth(72);
            undoPatchButton.visible = true;
            undoPatchButton.active = true;
            if (undoPatchButton.mouseClicked(click, false)) return true;

            redoPatchButton.setPosition(redoX, patchBtnY);
            redoPatchButton.setWidth(72);
            redoPatchButton.visible = true;
            redoPatchButton.active = true;
            if (redoPatchButton.mouseClicked(click, false)) return true;

            cancelButton.setPosition(patchCancelX, patchBtnY);
            cancelButton.setWidth(90);
            cancelButton.visible = true;
            cancelButton.active = true;
            cancelButton.mouseClicked(click, false);
            return true;
        }

        confirmButton.setPosition(confirmX, btnY);
        confirmButton.setWidth(btnW);
        confirmButton.visible = true;
        confirmButton.active = true;
        if (confirmButton.mouseClicked(click, false)) return true;

        cancelButton.setPosition(cancelX, btnY);
        cancelButton.setWidth(btnW);
        cancelButton.visible = true;
        cancelButton.active = true;
        cancelButton.mouseClicked(click, false);

        return true; // 点击面板内部其他位置，也阻止传递到世界
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
    
    private void onConfirm() {
        if (client.player != null && spec != null) {
            BlockPos pos = BuildingPreviewState.getOrigin();
            if (pos == null) pos = client.player.getBlockPos();
            int[] origin = new int[]{pos.getX(), pos.getY(), pos.getZ()};
            FormaCraftNetworking.sendConfirmBuild(spec, origin);
        }
        hide();
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
        } catch (Throwable ignored) {}

        try {
            java.lang.reflect.Method m2 = nh.getClass().getMethod("sendChatMessage", String.class);
            m2.invoke(nh, "/" + cmd);
        } catch (Throwable ignored) {}
    }
}
