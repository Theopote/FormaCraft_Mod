package com.formacraft.client.ui.panel.capture;

import com.formacraft.client.tool.SelectionTool;
import com.formacraft.client.ui.panel.ComponentSelectionMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;


/** 阶段 1：选区定义 */
public final class ComponentCaptureSelectionSection {
    private static final int LABEL_OFFSET = 18;

    private ComponentCaptureSelectionSection() {}

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
            ComponentCapturePhaseHeaders phaseHeaders,
            int phaseIndex,
            ButtonWidget boxSelectButton,
            ButtonWidget pointSelectButton,
            ButtonWidget clearSelectionButton,
            int thumbnailSize,

            int x,
            int y,
            int w
    ) {
        String phase1Title = (phaseCollapsed ? "▶ " : "▼ ") +
        "① 选区定义" + (isPhaseComplete ? "（已完成 ✓）" : (isPhaseActive ? "（当前步骤 ★）" : "（未开始）"));
        int phase1TitleColor = isPhaseActive ? 0xFFFFFF00 : (isPhaseComplete ? 0xFF88FF88 : 0xFF888888);
        int titleY = y;
        y = textDrawer.draw(ctx, Text.literal(phase1Title), x, y, w, phase1TitleColor);
        if (phaseHeaders != null) {
            phaseHeaders.record(phaseIndex, x, titleY, w, client.textRenderer.fontHeight);
        }
        y += 2;

        if (!phaseCollapsed) {

        // 选择模式按钮组
        int buttonW = (w - 8) / 3; // 3个按钮平分宽度

        // 框选按钮
        boxSelectButton.setMessage(Text.literal(selectionController.getMode() == ComponentSelectionMode.BOX_SELECT ? "📦 [框选]" : "📦 框选"));
        boxSelectButton.setPosition(x, y);
        boxSelectButton.setWidth(buttonW);
        boxSelectButton.visible = true;
        boxSelectButton.active = true;
        boxSelectButton.render(ctx, mouseX, mouseY, 0f);

        // 点选按钮
        pointSelectButton.setMessage(Text.literal(selectionController.getMode() == ComponentSelectionMode.POINT_SELECT ? "👆 [点选]" : "👆 点选"));
        pointSelectButton.setPosition(x + buttonW + 4, y);
        pointSelectButton.setWidth(buttonW);
        pointSelectButton.visible = true;
        pointSelectButton.active = true;
        pointSelectButton.render(ctx, mouseX, mouseY, 0f);

        // 清除按钮
        clearSelectionButton.setMessage(Text.literal("🗑️ 清除"));
        clearSelectionButton.setPosition(x + buttonW * 2 + 8, y);
        clearSelectionButton.setWidth(buttonW);
        clearSelectionButton.visible = true;
        clearSelectionButton.active = selectionController.hasAnySelection();
        clearSelectionButton.render(ctx, mouseX, mouseY, 0f);

        y += LABEL_OFFSET;

        // 当前模式提示
        String modeText = "当前模式: " + selectionController.getMode().getDisplayName() + " - " + selectionController.getMode().getHint();
        y = textDrawer.draw(ctx, Text.literal(modeText), x, y, w, 0xFF88CCFF);
        y += 4;

        // 快捷键说明
        String hint = selectionController.getMode() == ComponentSelectionMode.POINT_SELECT
        ? "提示: 点击方块切换选择 | Ctrl+点击强制加选 | 右键设锚点"
        : "提示: 拖拽可见预览框 | 右键设锚点";
        ctx.drawTextWithShadow(client.textRenderer, Text.literal(hint), x, y, 0xFF666666);
        y += client.textRenderer.fontHeight + 4;

        // 分隔线
        ctx.fill(x, y, x + w, y + 1, 0xFF444444);
        y += 4;

        // 检查是否有选区
        if (!selectionController.hasAnySelection()) {
        y = textDrawer.draw(ctx, Text.literal("⚠ 尚未选择任何方块"), x, y, w, 0xFFFFAA00);
        } else {

        BlockPos min = SelectionTool.INSTANCE.getMin();
        BlockPos max = SelectionTool.INSTANCE.getMax();

        // 选区预览
        y = textDrawer.draw(ctx, Text.literal("当前选区"), x, y, w, 0xFFFFFFFF);
        y += 2;

        int sizeX = 0;
        if (max != null) {
        if (min != null) {
        sizeX = max.getX() - min.getX() + 1;
        }
        }
        int sizeY = 0;
        if (max != null) {
        if (min != null) {
        sizeY = max.getY() - min.getY() + 1;
        }
        }
        int sizeZ = 0;
        if (max != null) {
        if (min != null) {
        sizeZ = max.getZ() - min.getZ() + 1;
        }
        }
        int blockCount = selectionController.countBlocks(client);

        y = textDrawer.draw(ctx, Text.literal("尺寸: " + sizeX + "×" + sizeY + "×" + sizeZ), x, y, w, 0xFFAAAAAA);
        y += client.textRenderer.fontHeight;
        y = textDrawer.draw(ctx, Text.literal("方块数: " + blockCount), x, y, w, 0xFFAAAAAA);
        y += 6;

        // 状态指示器
        y = ComponentCaptureDrawSupport.drawStatusIndicator(ctx, client, healthCoordinator, x, y, w);
        y += 4;

        // 缩略图预览
        thumbnailService.drawPreview(ctx, client, x + (w - thumbnailSize) / 2, y, thumbnailSize);
        y += thumbnailSize + 8;

        // 分隔线
        ctx.fill(x, y, x + w, y + 1, 0xFF444444);
        }
        y += 4;
        }

        return y;
    }
}
