package com.formacraft.client.ui.panel.capture;

import com.formacraft.client.tool.ComponentTool;
import com.formacraft.client.ui.panel.DirectionalityMode;
import com.formacraft.common.component.ComponentCategory;
import com.formacraft.common.component.placement.AttachmentType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * 构件捕获面板的 AI 视角解释：汇总分类、语义、附着与方向信息供 UI 展示。
 */
public final class ComponentCaptureAiExplanation {
    private final ComponentCaptureSemanticPreview semanticPreview;
    private final ComponentCaptureOrientationController orientationController;

    public ComponentCaptureAiExplanation(
            ComponentCaptureSemanticPreview semanticPreview,
            ComponentCaptureOrientationController orientationController
    ) {
        this.semanticPreview = semanticPreview;
        this.orientationController = orientationController;
    }

    public List<String> build(MinecraftClient client) {
        var explanations = new ArrayList<String>();
        var st = ComponentTool.INSTANCE.getState();
        var draft = st.captureDraft;

        explanations.add("类型：" + formatCategoryName(st.category));
        semanticPreview.appendToExplanations(explanations, client);
        explanations.add("使用场景：" + formatAttachmentUsage(draft.host.attachment));

        if (draft.host.referenceBlock != null && draft.host.normal != null) {
            explanations.add("宿主面：" + draft.host.normal.name() + " @ " + draft.host.referenceBlock.toShortString());
        }

        DirectionalityMode directionality = orientationController.getDirectionalityMode();
        if (directionality == DirectionalityMode.INSIDE_OUTSIDE) {
            explanations.add("朝向规则：内 → 外");
        } else if (directionality == DirectionalityMode.BOTTOM_TOP) {
            explanations.add("朝向规则：下 → 上");
        } else if (directionality == DirectionalityMode.BOTH) {
            explanations.add("朝向规则：内 → 外，下 → 上");
        } else {
            explanations.add("朝向规则：任意方向");
        }

        if (draft.orientation.hasInteriorExterior) {
            explanations.add(draft.orientation.insideMarkWorld != null && draft.orientation.outsideMarkWorld != null
                    ? "内外标记：已设置" : "内外标记：未设置");
        }
        if (draft.orientation.hasBottomTop) {
            explanations.add(draft.orientation.bottomMarkWorld != null && draft.orientation.topMarkWorld != null
                    ? "上下标记：已设置" : "上下标记：未设置");
        }

        if (st.category == ComponentCategory.DOOR || st.category == ComponentCategory.WINDOW) {
            explanations.add("推荐使用高度：首层");
        }

        if (st.category == ComponentCategory.COLUMN || st.category == ComponentCategory.ORNAMENT) {
            explanations.add("可重复：是");
        } else {
            explanations.add("可重复：否");
        }

        return explanations;
    }

    public int renderSection(
            DrawContext ctx,
            MinecraftClient client,
            ComponentCaptureHealthDrawer.WrappedTextDrawer textDrawer,
            int x,
            int y,
            int w
    ) {
        y = textDrawer.draw(ctx, Text.literal("🤖 AI 将如何理解这个构件："), x, y, w, 0xFF88CCFF);
        y += 2;

        for (String explanation : build(client)) {
            y = textDrawer.draw(ctx, Text.literal("- " + explanation), x, y, w, 0xFFAAAAAA);
        }

        y += 4;
        ctx.fill(x, y, x + w, y + 1, 0xFF444444);
        y += 4;
        return y;
    }

    private static String formatCategoryName(ComponentCategory category) {
        return switch (category) {
            case DOOR -> "门（Door）";
            case WINDOW -> "窗（Window）";
            case COLUMN -> "柱子（Column）";
            case STAIRS -> "楼梯（Stairs）";
            case BRACKET -> "斗拱（Bracket）";
            case ORNAMENT -> "装饰（Ornament）";
            case ARCH -> "拱券（Arch）";
            case ROOF_DETAIL -> "屋顶细节（Roof Detail）";
            default -> "通用构件（Generic）";
        };
    }

    private static String formatAttachmentUsage(AttachmentType attachment) {
        if (attachment == null) {
            return "独立放置";
        }
        return switch (attachment) {
            case WALL_OPENING -> "墙体开口";
            case WALL_SURFACE -> "墙面附着";
            case FLOOR -> "地面放置";
            case ROOF_SURFACE -> "屋面附着";
            case ROOF_EDGE -> "屋檐边缘";
            case ROOF_RIDGE -> "屋脊";
            case EDGE -> "边缘放置";
            case CORNER -> "转角放置";
            default -> "独立放置";
        };
    }
}
