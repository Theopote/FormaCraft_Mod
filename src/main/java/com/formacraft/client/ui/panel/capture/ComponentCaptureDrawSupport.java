package com.formacraft.client.ui.panel.capture;

import com.formacraft.client.tool.ComponentTool;
import com.formacraft.client.tool.SelectionTool;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/** Shared draw helpers for component capture phase sections. */
public final class ComponentCaptureDrawSupport {
    private ComponentCaptureDrawSupport() {}

    public static int drawStatusIndicator(
            DrawContext ctx,
            MinecraftClient client,
            ComponentCaptureHealthCoordinator healthCoordinator,
            int x,
            int y,
            int w
    ) {
        var st = ComponentTool.INSTANCE.getState();
        var draft = st.captureDraft;

        // 边框
        ctx.fill(x, y, x + w, y + 1, 0xFF444444);
        y += 2;

        // 选区状态
        boolean hasSelection = SelectionTool.INSTANCE.hasSelection();
        String selectionText = hasSelection ? "OK 选区已设置" : "WARN 选区未设置";
        int selectionColor = hasSelection ? 0xFF00FF00 : 0xFFFFAA00;
        ctx.drawTextWithShadow(client.textRenderer, Text.literal(selectionText), x, y, selectionColor);
        y += client.textRenderer.fontHeight + 2;

        // 锚点状态
        boolean hasAnchor = draft.anchor.worldPos != null;
        int anchorColor = hasAnchor ? 0xFF00FF00 : 0xFFFFAA00;
        String anchorText = hasAnchor
                ? String.format("OK 锚点: (%d, %d, %d)", draft.anchor.worldPos.getX(), draft.anchor.worldPos.getY(), draft.anchor.worldPos.getZ())
                : "WARN 锚点: 未设置";
        ctx.drawTextWithShadow(client.textRenderer, Text.literal(anchorText), x, y, anchorColor);
        y += client.textRenderer.fontHeight + 2;

        String hostText = (draft.host.referenceBlock != null && draft.host.normal != null)
                ? ("INFO 宿主面: " + draft.host.normal.name() + " @ " + draft.host.referenceBlock.toShortString())
                : "INFO 宿主面: 未设置";
        int hostColor = draft.host.normal != null ? 0xFF66CCFF : 0xFF888888;
        ctx.drawTextWithShadow(client.textRenderer, Text.literal(hostText), x, y, hostColor);
        y += client.textRenderer.fontHeight + 2;

        if (draft.orientation.hasInteriorExterior) {
            boolean hasInOut = draft.orientation.insideMarkWorld != null && draft.orientation.outsideMarkWorld != null;
            int inOutColor = hasInOut ? 0xFF00FF00 : 0xFFFFAA00;
            String inOutText = hasInOut ? "OK 内外方向: 已设置" : "WARN 内外方向: 未设置";
            ctx.drawTextWithShadow(client.textRenderer, Text.literal(inOutText), x, y, inOutColor);
            y += client.textRenderer.fontHeight + 2;
        }

        if (draft.orientation.hasBottomTop) {
            boolean hasBottomTop = draft.orientation.bottomMarkWorld != null && draft.orientation.topMarkWorld != null;
            int bottomTopColor = hasBottomTop ? 0xFF00FF00 : 0xFFFFAA00;
            String bottomTopText = hasBottomTop ? "OK 上下方向: 已设置" : "WARN 上下方向: 未设置";
            ctx.drawTextWithShadow(client.textRenderer, Text.literal(bottomTopText), x, y, bottomTopColor);
            y += client.textRenderer.fontHeight + 2;
        }

        if (draft.anchor.allowOutsideSelection) {
            ctx.drawTextWithShadow(client.textRenderer, Text.literal("INFO 外侧锚点: 开启"), x, y, 0xFF88CCFF);
            y += client.textRenderer.fontHeight + 2;
        }

        // 朝向状态
        String facingText = "INFO 朝向: " + draft.orientation.facing.name();
        ctx.drawTextWithShadow(client.textRenderer, Text.literal(facingText), x, y, 0xFF88CCFF);
        y += client.textRenderer.fontHeight + 2;

        // 名称状态
        boolean hasName = st.name != null && !st.name.isEmpty() && !st.name.equals("New Component");
        int nameColor = hasName ? 0xFF00FF00 : 0xFFFFAA00;
        String nameText = hasName ? "OK 名称已填写" : "WARN 名称: 请填写";
        ctx.drawTextWithShadow(client.textRenderer, Text.literal(nameText), x, y, nameColor);
        y += client.textRenderer.fontHeight + 2;

        y = ComponentCaptureHealthDrawer.drawStatusHealth(ctx, client, healthCoordinator, x, y);

        ctx.fill(x, y, x + w, y + 1, 0xFF444444);
        y += 2;

        return y;
    }

    }
}
