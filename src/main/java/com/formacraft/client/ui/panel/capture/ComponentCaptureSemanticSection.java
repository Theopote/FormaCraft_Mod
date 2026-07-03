package com.formacraft.client.ui.panel.capture;

import com.formacraft.client.tool.ComponentTool;
import com.formacraft.client.ui.widget.HudTextInput;
import com.formacraft.common.component.ComponentCategory;
import com.formacraft.client.ui.panel.DirectionalityMode;
import com.formacraft.client.ui.panel.DirectionMarkingMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** 阶段 3：构件语义确认 */
public final class ComponentCaptureSemanticSection {
    private static final int LABEL_OFFSET = 18;
    private static final int FIELD_SPACING = 28;

    private ComponentCaptureSemanticSection() {}

    public static int drawSection(
            DrawContext ctx,
            MinecraftClient client,
            ComponentCaptureSemanticHost host,
            ComponentCaptureOrientationController orientationController,
            ComponentCaptureSemanticPreview semanticPreview,
            ComponentCaptureHealthDrawer.WrappedTextDrawer textDrawer,
            HudTextInput nameInput,
            HudTextInput tagsInput,
            int mouseX,
            int mouseY,
            boolean phaseCollapsed,
            boolean isPhaseActive,
            boolean isPhaseComplete,
            boolean forceCollapsed,
            ButtonWidget categoryButton,
            ButtonWidget attachmentModeButton,
            ButtonWidget directionalityButton,
            ButtonWidget setInsideButton,
            ButtonWidget setOutsideButton,
            ButtonWidget setBottomButton,
            ButtonWidget setTopButton,
            ButtonWidget culturalStyleButton,
            ButtonWidget geometryArchetypeButton,
            ButtonWidget semanticSkinButton,
            ButtonWidget semanticTagOnSaveButton,
            ButtonWidget semanticStyleButton,
            ButtonWidget semanticPartButton,
            int x,
            int y,
            int w
    ) {
        var st = ComponentTool.INSTANCE.getState();

        if (forceCollapsed) {
            phaseCollapsed = true;
        }

        String phase3Title = (phaseCollapsed ? "▶ " : "▼ ") +
                "③ 构件语义" + (isPhaseComplete ? "（已完成 ✓）" : (isPhaseActive ? "（当前步骤 ★）" : "（自动 + 可调整）"));
        int phase3TitleColor = isPhaseActive ? 0xFFFFFF00 : (isPhaseComplete ? 0xFF88FF88 : 0xFF888888);
        y = textDrawer.draw(ctx, Text.literal(phase3Title), x, y, w, phase3TitleColor);
        y += 2;

        if (!phaseCollapsed) {
            y = textDrawer.draw(ctx, Text.literal("📝 基础信息"), x, y, w, 0xFFFFFFFF);
            y += 2;

            ctx.drawTextWithShadow(client.textRenderer, Text.literal("名称:"), x, y, 0xFFAAAAAA);
            int inputY = y + LABEL_OFFSET - 2;
            if (!nameInput.isFocused() && !st.name.equals(nameInput.getText())) {
                nameInput.setText(st.name);
            }
            nameInput.render(ctx, x, inputY, w, 14);
            host.setNameInputBounds(x, inputY, w, 14);
            st.name = nameInput.getText() != null ? nameInput.getText() : "New Component";
            y += FIELD_SPACING;

            String categoryEmoji = categoryEmoji(st.category);
            categoryButton.setMessage(Text.literal("你正在定义的是：" + categoryEmoji + " " + host.getCategoryDisplayName(st.category)));
            categoryButton.setPosition(x, y);
            categoryButton.setWidth(w);
            categoryButton.visible = true;
            categoryButton.active = true;
            categoryButton.render(ctx, mouseX, mouseY, 0f);
            y += LABEL_OFFSET;

            String attachmentExplanation = attachmentExplanation(host.getAttachmentMode());
            y = textDrawer.draw(ctx, Text.literal(attachmentExplanation + "（自动）"), x, y, w, 0xFFAAAAAA);
            y += 4;

            if (orientationController.getDirectionalityMode() == DirectionalityMode.INSIDE_OUTSIDE) {
                y = textDrawer.draw(ctx, Text.literal("方向语义：内 → 外（自动）"), x, y, w, 0xFFAAAAAA);
            } else if (orientationController.getDirectionalityMode() == DirectionalityMode.BOTTOM_TOP) {
                y = textDrawer.draw(ctx, Text.literal("方向语义：下 → 上（自动）"), x, y, w, 0xFFAAAAAA);
            } else if (orientationController.getDirectionalityMode() == DirectionalityMode.BOTH) {
                y = textDrawer.draw(ctx, Text.literal("方向语义：内 → 外，下 → 上（自动）"), x, y, w, 0xFFAAAAAA);
            }
            y += 4;

            ctx.drawTextWithShadow(client.textRenderer, Text.literal("标签 (逗号分隔):"), x, y, 0xFFAAAAAA);
            inputY = y + LABEL_OFFSET - 2;
            String currentTags = String.join(", ", st.tags);
            if (!tagsInput.isFocused() && !currentTags.equals(tagsInput.getText())) {
                tagsInput.setText(currentTags);
            }
            tagsInput.render(ctx, x, inputY, w, 14);
            host.setTagsInputBounds(x, inputY, w, 14);
            host.updateTagsFromInput();
            y += FIELD_SPACING;

            y = semanticPreview.renderSection(
                    ctx,
                    client,
                    textDrawer,
                    x,
                    y,
                    w,
                    mouseX,
                    mouseY,
                    culturalStyleButton,
                    geometryArchetypeButton
            );

            y = textDrawer.draw(ctx, Text.literal("🔧 附着与方向性（可调整）"), x, y, w, 0xFFFFFFFF);
            y += 2;

            var draft = st.captureDraft;
            int halfW = (w - 4) / 2;
            boolean manualAttach = draft.host.manualAttachment || draft.host.confirmed;
            String attachLabel = manualAttach
                    ? "附着覆盖: " + host.getAttachmentModeDisplay()
                    : "附着: 自动（见上方分析）";
            attachmentModeButton.setMessage(Text.literal(attachLabel));
            attachmentModeButton.setPosition(x, y);
            attachmentModeButton.setWidth(halfW);
            attachmentModeButton.visible = true;
            attachmentModeButton.active = true;
            attachmentModeButton.render(ctx, mouseX, mouseY, 0f);

            directionalityButton.setMessage(Text.literal("方向: " + orientationController.getDirectionalityMode().getDisplayName()));
            directionalityButton.setPosition(x + halfW + 4, y);
            directionalityButton.setWidth(w - halfW - 4);
            directionalityButton.visible = true;
            directionalityButton.active = true;
            directionalityButton.render(ctx, mouseX, mouseY, 0f);
            y += LABEL_OFFSET;

            if (orientationController.getDirectionalityMode().needsInsideOutside()) {
                setInsideButton.setMessage(Text.literal(draft.orientation.insideMarkWorld != null ? "🏠✓ 内侧" : "🏠 设内侧"));
                setInsideButton.setPosition(x, y);
                setInsideButton.setWidth(halfW);
                setInsideButton.visible = true;
                setInsideButton.active = true;
                setInsideButton.render(ctx, mouseX, mouseY, 0f);

                setOutsideButton.setMessage(Text.literal(draft.orientation.outsideMarkWorld != null ? "🌍✓ 外侧" : "🌍 设外侧"));
                setOutsideButton.setPosition(x + halfW + 4, y);
                setOutsideButton.setWidth(w - halfW - 4);
                setOutsideButton.visible = true;
                setOutsideButton.active = true;
                setOutsideButton.render(ctx, mouseX, mouseY, 0f);
                y += LABEL_OFFSET;
            }

            if (orientationController.getDirectionalityMode().needsBottomTop()) {
                setBottomButton.setMessage(Text.literal(draft.orientation.bottomMarkWorld != null ? "⬇️✓ 底端" : "⬇️ 设底端"));
                setBottomButton.setPosition(x, y);
                setBottomButton.setWidth(halfW);
                setBottomButton.visible = true;
                setBottomButton.active = true;
                setBottomButton.render(ctx, mouseX, mouseY, 0f);

                setTopButton.setMessage(Text.literal(draft.orientation.topMarkWorld != null ? "⬆️✓ 顶端" : "⬆️ 设顶端"));
                setTopButton.setPosition(x + halfW + 4, y);
                setTopButton.setWidth(w - halfW - 4);
                setTopButton.visible = true;
                setTopButton.active = true;
                setTopButton.render(ctx, mouseX, mouseY, 0f);
                y += LABEL_OFFSET;
            }

            if (orientationController.getMarkingMode() != DirectionMarkingMode.NONE) {
                y = textDrawer.draw(ctx, Text.literal("⚡ " + orientationController.getMarkingMode().getHint()), x, y, w, 0xFFFFFF00);
                y += 4;
            }

            ctx.fill(x, y, x + w, y + 1, 0xFF444444);
            y += 4;

            y = textDrawer.draw(ctx, Text.literal("🎨 语义标注（高级）"), x, y, w, 0xFF888888);
            y += 2;

            int half = (w - 4) / 2;
            semanticSkinButton.setMessage(Text.literal(st.semanticSkin ? "材质：语义" : "材质：原样"));
            semanticSkinButton.setPosition(x, y);
            semanticSkinButton.setWidth(half);
            semanticSkinButton.visible = true;
            semanticSkinButton.active = true;
            semanticSkinButton.render(ctx, mouseX, mouseY, 0f);

            semanticTagOnSaveButton.setMessage(Text.literal(st.semanticTagOnSave ? "存语义：开" : "存语义：关"));
            semanticTagOnSaveButton.setPosition(x + half + 4, y);
            semanticTagOnSaveButton.setWidth(w - half - 4);
            semanticTagOnSaveButton.visible = true;
            semanticTagOnSaveButton.active = true;
            semanticTagOnSaveButton.render(ctx, mouseX, mouseY, 0f);
            y += LABEL_OFFSET;

            semanticStyleButton.setMessage(Text.literal("风格：" + (st.semanticStyleId != null ? st.semanticStyleId : "DEFAULT")));
            semanticStyleButton.setPosition(x, y);
            semanticStyleButton.setWidth(w);
            semanticStyleButton.visible = true;
            semanticStyleButton.active = st.semanticSkin;
            semanticStyleButton.render(ctx, mouseX, mouseY, 0f);
            y += LABEL_OFFSET;

            semanticPartButton.setMessage(Text.literal("语义：" + (st.semanticPart != null ? st.semanticPart.name() : "AUTO")));
            semanticPartButton.setPosition(x, y);
            semanticPartButton.setWidth(w);
            semanticPartButton.visible = true;
            semanticPartButton.active = st.semanticSkin;
            semanticPartButton.render(ctx, mouseX, mouseY, 0f);
            y += LABEL_OFFSET;

            ctx.fill(x, y, x + w, y + 1, 0xFF444444);
            y += 4;
        }

        return y;
    }

    private static String categoryEmoji(ComponentCategory category) {
        return switch (category) {
            case DOOR -> "🚪";
            case WINDOW -> "🪟";
            case COLUMN -> "🏛️";
            case STAIRS -> "🪜";
            case BRACKET -> "🏗️";
            case ORNAMENT -> "🧱";
            case ARCH -> "⛩️";
            case ROOF_DETAIL -> "🏠";
            default -> "📦";
        };
    }

    private static String attachmentExplanation(com.formacraft.common.component.placement.AttachmentType attachmentMode) {
        return switch (attachmentMode) {
            case WALL_OPENING -> "📌 这个构件会被\"嵌入到墙体中\"";
            case WALL_SURFACE -> "📌 这个构件会\"附着在墙面上\"";
            case FLOOR -> "📌 这个构件会\"放置在地面上\"";
            case ROOF_SURFACE -> "📌 这个构件会\"附着在屋面上\"";
            case ROOF_EDGE -> "📌 这个构件会\"附着在屋檐边缘\"";
            case ROOF_RIDGE -> "📌 这个构件会\"附着在屋脊上\"";
            case EDGE -> "📌 这个构件会\"沿边缘放置\"";
            case CORNER -> "📌 这个构件会\"放置在转角\"";
            default -> "📌 这个构件是\"独立放置\"";
        };
    }
}
