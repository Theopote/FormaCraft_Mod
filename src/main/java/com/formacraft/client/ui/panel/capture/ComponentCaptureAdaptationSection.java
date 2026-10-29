package com.formacraft.client.ui.panel.capture;

import com.formacraft.client.tool.ComponentTool;
import com.formacraft.client.ui.UiTheme;
import com.formacraft.client.ui.toast.HudToast;
import com.formacraft.client.ui.widget.HudClickSupport;
import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.model.ComponentModelApi;
import com.formacraft.common.component.query.ComponentQuery;
import com.formacraft.common.component.variant.CaptureAdaptationSettings;
import com.formacraft.common.component.variant.CapturedAxisLayout;
import com.formacraft.common.json.JsonUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** Author-controlled middle bands and a read-only adapted preview. */
public final class ComponentCaptureAdaptationSection {
    private final ButtonWidget[] modeButtons = new ButtonWidget[3];
    private final ButtonWidget[] edgeButtons = new ButtonWidget[3];
    private final ButtonWidget[] endEdgeButtons = new ButtonWidget[3];
    private final ButtonWidget[] targetButtons = new ButtonWidget[3];
    private final ButtonWidget materialButton;
    private final ButtonWidget previewButton;
    private final ComponentCaptureThumbnailService thumbnails = new ComponentCaptureThumbnailService();
    private final int[] offsets = {0, 0, 0};
    private String status;
    private boolean hasPreview;
    private int visibleTop, visibleBottom;
    private net.minecraft.util.math.Direction displayedFacing;

    public ComponentCaptureAdaptationSection() {
        for (int i = 0; i < 3; i++) {
            final int axis = i;
            modeButtons[i] = button(() -> {
                var settings = settings();
                var values = CaptureAdaptationSettings.Mode.values();
                settings.modes[axis] = values[(settings.modes[axis].ordinal() + 1) % values.length];
                changed();
            });
            edgeButtons[i] = button(() -> {
                var settings = settings();
                settings.setFixedEdge(axis, false, settings.fixedEdges[axis] % 3 + 1);
                changed();
            });
            endEdgeButtons[i] = button(() -> {
                var settings = settings();
                settings.setFixedEdge(axis, true, settings.fixedEnd(axis) % 3 + 1);
                changed();
            });
            targetButtons[i] = button(() -> {
                offsets[axis] = switch (offsets[axis]) { case 0 -> 1; case 1 -> 2; case 2 -> -1; case -1 -> -2; default -> 0; };
                invalidate();
            });
        }
        materialButton = button(() -> { settings().allowMaterialSwap = !settings().allowMaterialSwap; changed(); });
        previewButton = button(this::preview);
    }

    private static ButtonWidget button(Runnable action) {
        return ButtonWidget.builder(Text.empty(), b -> action.run()).dimensions(0, 0, 100, 16).build();
    }

    private static CaptureAdaptationSettings settings() {
        return ComponentTool.INSTANCE.getState().captureDraft.adaptation;
    }

    private void changed() {
        ComponentTool.INSTANCE.getState().captureDraft.dirty = true;
        invalidate();
    }

    public void invalidate() {
        thumbnails.invalidate();
        status = null;
        hasPreview = false;
    }

    public void hide() {
        for (var b : modeButtons) b.visible = false;
        for (var b : edgeButtons) b.visible = false;
        for (var b : endEdgeButtons) b.visible = false;
        for (var b : targetButtons) b.visible = false;
        materialButton.visible = previewButton.visible = false;
    }

    public int draw(DrawContext ctx, MinecraftClient client, int mouseX, int mouseY,
                    int x, int y, int width, int clipTop, int clipBottom) {
        visibleTop = clipTop;
        visibleBottom = clipBottom;
        ctx.fill(x, y, x + width, y + 1, UiTheme.DIVIDER_SUBTLE);
        y += 5;
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("构件变化规则"), x, y, 0xFFFFFFFF);
        y += 13;
        var facing = ComponentTool.INSTANCE.getState().captureDraft.orientation.facing;
        if (displayedFacing != facing) { invalidate(); displayedFacing = facing; }
        var axes = CapturedAxisLayout.forFacing(facing);
        var settings = settings();
        for (int dimension = 0; dimension < 3; dimension++) {
            int i = axes.worldAxis(dimension);
            String mode = switch (settings.modes[i]) {
                case FIXED -> "锁定"; case REPEAT -> "重复中段"; case TRIM -> "缩短中段";
            };
            drawButton(ctx, modeButtons[i], x, y, width,
                    axes.label(dimension) + "：" + mode, mouseX, mouseY);
            y += 20;
            edgeButtons[i].active = endEdgeButtons[i].active = settings.modes[i] != CaptureAdaptationSettings.Mode.FIXED;
            int half = (width - 2) / 2;
            String[] starts = {"西端", "底部", "北端"};
            String[] ends = {"东端", "顶部", "南端"};
            drawButton(ctx, edgeButtons[i], x, y, half,
                    starts[i] + "保留 " + settings.fixedEdges[i], mouseX, mouseY);
            drawButton(ctx, endEdgeButtons[i], x + half + 2, y, half,
                    ends[i] + "保留 " + settings.fixedEnd(i), mouseX, mouseY);
            y += 20;
        }
        y = wrapped(ctx, client, "两端可分别保留 1–3 层，只增减中段。东西南北为世界方向；更改朝向不会旋转规则。", x, y, width);
        drawButton(ctx, materialButton, x, y, width,
                "允许安全换材：" + (settings.allowMaterialSwap ? "开" : "关"), mouseX, mouseY);
        y += 20;
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("试算尺寸（相对原件）"), x, y, 0xFFAAAAAA);
        y += 13;
        int cell = (width - 4) / 3;
        for (int dimension = 0; dimension < 3; dimension++) {
            int i = axes.worldAxis(dimension);
            String offset = offsets[i] == 0 ? "原长" : (offsets[i] > 0 ? "+" : "") + offsets[i];
            drawButton(ctx, targetButtons[i], x + dimension * (cell + 2), y, cell,
                    new String[]{"宽", "高", "厚"}[dimension] + " " + offset, mouseX, mouseY);
        }
        y += 20;
        drawButton(ctx, previewButton, x, y, width, "校验并预览变体", mouseX, mouseY);
        y += 20;
        if (status != null) y = wrapped(ctx, client, status, x, y, width);
        if (hasPreview) {
            int size = Math.min(width, 100);
            thumbnails.drawSnapshot(ctx, client, x + (width - size) / 2, y, size);
            y += size + 4;
        }
        return y + 6;
    }

    private int wrapped(DrawContext ctx, MinecraftClient client, String message, int x, int y, int width) {
        for (var line : client.textRenderer.wrapLines(Text.literal(message), width)) {
            ctx.drawTextWithShadow(client.textRenderer, line, x, y, 0xFFAAAAAA);
            y += 10;
        }
        return y + 4;
    }

    private static void drawButton(DrawContext ctx, ButtonWidget b, int x, int y, int width,
                                   String label, int mouseX, int mouseY) {
        b.setPosition(x, y);
        b.setWidth(width);
        b.setMessage(Text.literal(label));
        b.visible = true;
        b.render(ctx, mouseX, mouseY, 0f);
    }

    public boolean click(Click click, double mouseY) {
        if (mouseY < visibleTop || mouseY >= visibleBottom) return false;
        for (var group : new ButtonWidget[][]{modeButtons, edgeButtons, endEdgeButtons, targetButtons})
            for (var b : group) if (b.visible && HudClickSupport.click(b, click)) return true;
        return materialButton.visible && HudClickSupport.click(materialButton, click)
                || previewButton.visible && HudClickSupport.click(previewButton, click);
    }

    private void preview() {
        MinecraftClient client = MinecraftClient.getInstance();
        invalidate();
        String json = ComponentTool.INSTANCE.buildCurrentComponentJson(client,
                ComponentTool.INSTANCE.getState().captureDraft);
        if (json == null) { HudToast.show("请先完成选区和锚点设置", true); return; }
        ComponentDefinition base = JsonUtil.fromJson(json, ComponentDefinition.class);
        String ruleError = com.formacraft.common.component.variant.CapturedComponentRuleValidator.validate(base);
        if (ruleError != null) { status = ruleError; return; }
        ComponentQuery query = new ComponentQuery();
        query.geometry.openingWidth = base.size.w + offsets[0];
        query.geometry.openingHeight = base.size.h + offsets[1];
        query.geometry.targetDepth = base.size.d + offsets[2];
        if (query.geometry.openingWidth <= 0 || query.geometry.openingHeight <= 0 || query.geometry.targetDepth <= 0) {
            status = "目标尺寸必须至少为 1 格";
            return;
        }
        ComponentDefinition result = ComponentModelApi.fitToQuery(base, query, null);
        if (result == null) {
            status = "无法适配：检查锁定轴、固定边厚度或成对方块；可改选其他尺寸。";
            return;
        }
        var axes = CapturedAxisLayout.forFacing(ComponentTool.INSTANCE.getState().captureDraft.orientation.facing);
        status = "校验通过（宽×高×厚）：" + axes.dimensionSize(0, result.size.w, result.size.h, result.size.d)
                + "×" + result.size.h + "×" + axes.dimensionSize(2, result.size.w, result.size.h, result.size.d)
                + "，" + result.blocks.size() + " 方块。仅预览，不放置。";
        hasPreview = true;
        thumbnails.generateSnapshot(client, result);
    }
}
