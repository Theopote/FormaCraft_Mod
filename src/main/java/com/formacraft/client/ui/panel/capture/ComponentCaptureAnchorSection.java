package com.formacraft.client.ui.panel.capture;

import com.formacraft.client.tool.ComponentTool;
import com.formacraft.client.tool.SelectionTool;
import com.formacraft.common.component.ComponentCategory;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;


/** 阶段 2：锚点与朝向 */
public final class ComponentCaptureAnchorSection {
    private static final int LABEL_OFFSET = 18;

    private ComponentCaptureAnchorSection() {}

    public static int drawSection(
            DrawContext ctx,
            MinecraftClient client,
            ComponentCaptureSelectionController selectionController,
            ComponentCaptureThumbnailService thumbnailService,
            ComponentCaptureHealthCoordinator healthCoordinator,
            ComponentCaptureHealthDrawer.WrappedTextDrawer textDrawer,
            int mouseX,
            int mouseY,
            boolean phaseCollapsed,
            boolean isPhaseActive,
            boolean isPhaseComplete,
            boolean forceCollapsed,
            ComponentCapturePhaseHeaders phaseHeaders,
            int phaseIndex,
            ButtonWidget pickAnchorButton,
            ButtonWidget clearAnchorButton,
            ButtonWidget hostFaceButton,
            ButtonWidget anchorOutsideButton,
            ButtonWidget autoAnchorButton,
            ButtonWidget facingButton,
            ButtonWidget mirrorButton,

            int x,
            int y,
            int w
    ) {
        var st = ComponentTool.INSTANCE.getState();

        // 如果阶段1未完成，阶段2应该折叠
        if (forceCollapsed) {
        phaseCollapsed = true;
        }

        String phase2Title = (phaseCollapsed ? "▶ " : "▼ ") +
        "② 锚点 & 朝向" + (isPhaseComplete ? "（已完成 ✓）" : (isPhaseActive ? "（当前步骤 ★）" : "（未开始）"));
        int phase2TitleColor = isPhaseActive ? 0xFFFFFF00 : (isPhaseComplete ? 0xFF88FF88 : 0xFF888888);
        int titleY = y;
        y = textDrawer.draw(ctx, Text.literal(phase2Title), x, y, w, phase2TitleColor);
        if (phaseHeaders != null) {
            phaseHeaders.record(phaseIndex, x, titleY, w, client.textRenderer.fontHeight);
        }
        y += 2;

        if (!phaseCollapsed) {
        // 锚点与朝向（阶段2内容）
        String anchorText = st.captureDraft.anchor.worldPos != null
        ? "锚点: (" + st.captureDraft.anchor.worldPos.getX() + ", " + st.captureDraft.anchor.worldPos.getY() + ", " + st.captureDraft.anchor.worldPos.getZ() + ")"
        : "锚点: (未设置，默认为选区最小角)";
        y = textDrawer.draw(ctx, Text.literal(anchorText), x, y, w,
        st.captureDraft.anchor.worldPos != null ? 0xFF66FF66 : 0xFFFFAA00);
        y += 2;

        int half = (w - 4) / 2;

        pickAnchorButton.setMessage(Text.literal(st.pickingAnchor ? "⏹ 取消选择" : "📍 点击选择"));
        pickAnchorButton.setPosition(x, y);
        pickAnchorButton.setWidth(half);
        pickAnchorButton.visible = true;
        pickAnchorButton.active = true;
        pickAnchorButton.render(ctx, mouseX, mouseY, 0f);

        clearAnchorButton.setPosition(x + half + 4, y);
        clearAnchorButton.setWidth(w - half - 4);
        clearAnchorButton.visible = true;
        clearAnchorButton.active = st.captureDraft.anchor.worldPos != null || st.pickingAnchor;
        clearAnchorButton.render(ctx, mouseX, mouseY, 0f);
        y += LABEL_OFFSET;

        String hostFaceText = (st.captureDraft.host.referenceBlock != null && st.captureDraft.host.normal != null)
        ? ("宿主面: (" + st.captureDraft.host.referenceBlock.getX() + ", " + st.captureDraft.host.referenceBlock.getY() + ", " + st.captureDraft.host.referenceBlock.getZ() + ") " + st.captureDraft.host.normal.name())
        : "宿主面: (未设置)";
        y = textDrawer.draw(ctx, Text.literal(hostFaceText), x, y, w,
        st.captureDraft.host.normal != null ? 0xFF66CCFF : 0xFF888888);
        y += 2;

        hostFaceButton.setMessage(Text.literal(st.captureDraft.host.normal != null ? "宿主面：重选" : "选择宿主面"));
        hostFaceButton.setPosition(x, y);
        hostFaceButton.setWidth(half);
        hostFaceButton.visible = true;
        hostFaceButton.active = selectionController.hasValidSelection();
        hostFaceButton.render(ctx, mouseX, mouseY, 0f);

        anchorOutsideButton.setMessage(Text.literal(st.captureDraft.anchor.allowOutsideSelection ? "外侧锚点：开" : "外侧锚点：关"));
        anchorOutsideButton.setPosition(x + half + 4, y);
        anchorOutsideButton.setWidth(w - half - 4);
        anchorOutsideButton.visible = true;
        anchorOutsideButton.active = true;
        anchorOutsideButton.render(ctx, mouseX, mouseY, 0f);
        y += LABEL_OFFSET;

        autoAnchorButton.setPosition(x, y);
        autoAnchorButton.setWidth(w);
        autoAnchorButton.visible = true;
        autoAnchorButton.active = selectionController.hasValidSelection();
        autoAnchorButton.render(ctx, mouseX, mouseY, 0f);
        y += LABEL_OFFSET;

        facingButton.setMessage(Text.literal("朝向：" + st.captureDraft.orientation.facing.name()));
        facingButton.setPosition(x, y);
        facingButton.setWidth(half);
        facingButton.visible = true;
        facingButton.active = true;
        facingButton.render(ctx, mouseX, mouseY, 0f);

        mirrorButton.setMessage(Text.literal("镜像：" + st.captureDraft.orientation.mirror.name()));
        mirrorButton.setPosition(x + half + 4, y);
        mirrorButton.setWidth(w - half - 4);
        mirrorButton.visible = true;
        mirrorButton.active = true;
        mirrorButton.render(ctx, mouseX, mouseY, 0f);
        y += LABEL_OFFSET;

        // 如果构件需要方向性（门/窗/阳台），显示提示
        if (st.category == ComponentCategory.DOOR || st.category == ComponentCategory.WINDOW) {
        y = textDrawer.draw(ctx, Text.literal("⚠ 该构件需要\"内 / 外\"方向"), x, y, w, 0xFFFFAA00);
        y += 2;
        y = textDrawer.draw(ctx, Text.literal("请分别标记："), x, y, w, 0xFFAAAAAA);
        y += 2;
        }

        // 分隔线
        ctx.fill(x, y, x + w, y + 1, 0xFF444444);
        y += 4;
        }

        return y;
    }
}
