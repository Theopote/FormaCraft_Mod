package com.formacraft.client.ui.panel.capture;

import com.formacraft.client.tool.ComponentTool;
import com.formacraft.client.tool.SelectionTool;
import com.formacraft.client.ui.widget.HudTextInput;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** 阶段 4：AI 使用保障 */
public final class ComponentCaptureAiGuardSection {
    private static final int LABEL_OFFSET = 18;
    private static final int FIELD_SPACING = 28;

    private ComponentCaptureAiGuardSection() {}

    public static int drawSection(
            DrawContext ctx,
            MinecraftClient client,
            ComponentCaptureAiGuardHost host,
            ComponentCaptureHealthDrawer.WrappedTextDrawer textDrawer,
            HudTextInput socketIdInput,
            int mouseX,
            int mouseY,
            boolean phaseCollapsed,
            boolean isPhaseActive,
            boolean isPhaseComplete,
            boolean forceCollapsed,
            ButtonWidget autoDetectSocketsButton,
            ButtonWidget socketContextButton,
            ButtonWidget socketPickOriginButton,
            ButtonWidget socketFacingButton,
            ButtonWidget socketAddButton,
            ButtonWidget socketPreviewButton,
            ButtonWidget socketClearButton,
            ButtonWidget autoAnalyzeButton,
            int x,
            int y,
            int w
    ) {
        var st = ComponentTool.INSTANCE.getState();

        if (forceCollapsed) {
            phaseCollapsed = true;
        }

        String phase4Title = (phaseCollapsed ? "▶ " : "▼ ") +
                "④ AI 使用保障" + (isPhaseComplete ? "（已完成 ✓）" : (isPhaseActive ? "（当前步骤 ★）" : "（高级，可折叠）"));
        int phase4TitleColor = isPhaseActive ? 0xFFFFFF00 : (isPhaseComplete ? 0xFF88FF88 : 0xFF888888);
        y = textDrawer.draw(ctx, Text.literal(phase4Title), x, y, w, phase4TitleColor);
        y += 2;

        if (!phaseCollapsed) {
            y = textDrawer.draw(ctx, Text.literal("连接位配置"), x, y, w, 0xFFFFFFFF);
            y += 2;

            String so = st.socketOriginLocal != null
                    ? ("原点(local)=" + st.socketOriginLocal.getX() + "," + st.socketOriginLocal.getY() + "," + st.socketOriginLocal.getZ())
                    : "原点(local)=未设置";
            String ss = "尺寸=" + st.socketW + "×" + st.socketH + "×" + st.socketD;
            y = textDrawer.draw(ctx, Text.literal("已添加: " + st.socketCount + " 个  " + so + "  " + ss), x, y, w, 0xFFAAAAAA);
            y += 2;

            autoDetectSocketsButton.setPosition(x, y);
            autoDetectSocketsButton.setWidth(w);
            autoDetectSocketsButton.visible = true;
            autoDetectSocketsButton.active = false;
            autoDetectSocketsButton.render(ctx, mouseX, mouseY, 0f);
            y += LABEL_OFFSET;

            socketContextButton.setMessage(Text.literal("连接位上下文: " + (st.socketContext != null ? st.socketContext.name() : "WALL")));
            socketContextButton.setPosition(x, y);
            socketContextButton.setWidth(w);
            socketContextButton.visible = true;
            socketContextButton.active = SelectionTool.INSTANCE.hasSelection();
            socketContextButton.render(ctx, mouseX, mouseY, 0f);
            y += LABEL_OFFSET;

            ctx.drawTextWithShadow(client.textRenderer, Text.literal("连接位 ID:"), x, y, 0xFFAAAAAA);
            int inputY = y + LABEL_OFFSET - 2;
            socketIdInput.render(ctx, x, inputY, w, 14);
            host.setSocketIdInputBounds(x, inputY, w, 14);
            String sid = socketIdInput.getText();
            if (sid != null && !sid.isBlank()) {
                st.socketIdDraft = sid.trim();
            }
            y += FIELD_SPACING;

            int half = (w - 4) / 2;
            socketPickOriginButton.setPosition(x, y);
            socketPickOriginButton.setWidth(half);
            socketPickOriginButton.visible = true;
            socketPickOriginButton.active = SelectionTool.INSTANCE.hasSelection() && st.captureDraft.anchor.worldPos != null;
            socketPickOriginButton.render(ctx, mouseX, mouseY, 0f);

            socketFacingButton.setMessage(Text.literal("朝向: " + (st.socketFacing != null ? st.socketFacing.name() : "SOUTH")));
            socketFacingButton.setPosition(x + half + 4, y);
            socketFacingButton.setWidth(w - half - 4);
            socketFacingButton.visible = true;
            socketFacingButton.active = true;
            socketFacingButton.render(ctx, mouseX, mouseY, 0f);
            y += LABEL_OFFSET;

            socketAddButton.setPosition(x, y);
            socketAddButton.setWidth(half);
            socketAddButton.visible = true;
            socketAddButton.active = SelectionTool.INSTANCE.hasSelection() && st.socketOriginLocal != null;
            socketAddButton.render(ctx, mouseX, mouseY, 0f);

            socketPreviewButton.setPosition(x + half + 4, y);
            socketPreviewButton.setWidth(w - half - 4);
            socketPreviewButton.visible = true;
            socketPreviewButton.active = st.captureDraft.anchor.worldPos != null && st.socketOriginLocal != null;
            socketPreviewButton.render(ctx, mouseX, mouseY, 0f);
            y += LABEL_OFFSET;

            socketClearButton.setPosition(x, y);
            socketClearButton.setWidth(w);
            socketClearButton.visible = true;
            socketClearButton.active = st.socketCount > 0;
            socketClearButton.render(ctx, mouseX, mouseY, 0f);
            y += LABEL_OFFSET;

            ctx.fill(x, y, x + w, y + 1, 0xFF444444);
            y += 4;

            y = textDrawer.draw(ctx, Text.literal("🧠 智能分析"), x, y, w, 0xFFFFFFFF);
            y += 2;

            autoAnalyzeButton.setPosition(x, y);
            autoAnalyzeButton.setWidth(w);
            autoAnalyzeButton.visible = true;
            autoAnalyzeButton.active = false;
            autoAnalyzeButton.render(ctx, mouseX, mouseY, 0f);
            y += LABEL_OFFSET;

            ctx.fill(x, y, x + w, y + 1, 0xFF444444);
            y += 4;
        }

        return y;
    }
}
