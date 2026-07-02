package com.formacraft.client.ui.panel.capture;

import com.formacraft.client.tool.ComponentTool;
import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.placement.ComponentPlacementAnalyzer;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.logging.FcaLog;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.List;

/**
 * 构件捕获面板的 AI 语义预览：文化风格、几何原型、放置分析推断与 UI 展示。
 */
public final class ComponentCaptureSemanticPreview {
    private static final FcaLog LOG = FcaLog.of("ComponentCaptureSemanticPreview");

    private static final long DEBOUNCE_MS = 250;
    private static final int LABEL_OFFSET = 18;

    private static final String[] CULTURAL_STYLE_OPTIONS = {
            null, "CHINESE", "JAPANESE", "GOTHIC", "MEDIEVAL", "MODERN", "EUROPEAN", "ISLAMIC", "INDUSTRIAL"
    };
    private static final String[] GEOMETRY_ARCHETYPE_OPTIONS = {
            null, "FLAT_PANEL", "ARCH", "COLUMN", "FRAME", "ORNAMENT", "LINEAR", "VOLUME"
    };

    private long lastRefreshMs;
    private Snapshot snapshot = Snapshot.empty();

    public void invalidate() {
        lastRefreshMs = 0;
    }

    public Snapshot getSnapshot() {
        return snapshot;
    }

    public Snapshot refreshIfNeeded(MinecraftClient client) {
        long now = System.currentTimeMillis();
        if (now - lastRefreshMs < DEBOUNCE_MS) {
            return snapshot;
        }
        lastRefreshMs = now;
        snapshot = Snapshot.empty();

        if (client == null || client.world == null) {
            return snapshot;
        }

        String json = ComponentTool.INSTANCE.buildCurrentComponentJson(
                client, ComponentTool.INSTANCE.getState().captureDraft);
        if (json == null || json.isBlank()) {
            return snapshot;
        }

        try {
            ComponentDefinition def = JsonUtil.fromJson(json, ComponentDefinition.class);
            if (def == null) {
                return snapshot;
            }
            String placementSummary = null;
            String placementHint = null;
            if (def.placementSpec != null) {
                placementSummary = ComponentPlacementAnalyzer.formatSummary(def.placementSpec);
                placementHint = def.placementSpec.aiHint;
            }
            snapshot = new Snapshot(
                    def.culturalStyle,
                    def.geometryArchetype,
                    def.archetypeRef,
                    placementSummary,
                    placementHint
            );
        } catch (Throwable t) {
            LOG.debug("semantic preview snapshot failed", t);
        }
        return snapshot;
    }

    public void cycleCulturalStyle() {
        var st = ComponentTool.INSTANCE.getState();
        st.culturalStyleOverride = nextRingOption(CULTURAL_STYLE_OPTIONS, st.culturalStyleOverride);
        invalidate();
    }

    public void cycleGeometryArchetype() {
        var st = ComponentTool.INSTANCE.getState();
        st.geometryArchetypeOverride = nextRingOption(GEOMETRY_ARCHETYPE_OPTIONS, st.geometryArchetypeOverride);
        invalidate();
    }

    public void appendToExplanations(List<String> explanations, MinecraftClient client) {
        Snapshot data = refreshIfNeeded(client);
        if (data.culturalStyle() != null && !data.culturalStyle().isBlank()) {
            explanations.add("文化风格：" + formatCulturalStyleLabel(data.culturalStyle())
                    + " (" + data.culturalStyle() + ")");
        }
        if (data.geometryArchetype() != null && !data.geometryArchetype().isBlank()) {
            explanations.add("几何原型：" + formatGeometryArchetypeLabel(data.geometryArchetype())
                    + " (" + data.geometryArchetype() + ")");
        }
        if (data.archetypeRef() != null && !data.archetypeRef().isBlank()) {
            explanations.add("原型引用：" + data.archetypeRef());
        }
        if (data.placementSummary() != null && !data.placementSummary().isBlank()) {
            explanations.add("放置分析：" + data.placementSummary());
        }
        if (data.placementHint() != null && !data.placementHint().isBlank()) {
            explanations.add("放置提示：" + data.placementHint());
        }
    }

    public int renderSection(
            DrawContext ctx,
            MinecraftClient client,
            ComponentCaptureHealthDrawer.WrappedTextDrawer textDrawer,
            int x,
            int y,
            int w,
            int mouseX,
            int mouseY,
            ButtonWidget culturalStyleButton,
            ButtonWidget geometryArchetypeButton
    ) {
        var st = ComponentTool.INSTANCE.getState();
        Snapshot data = refreshIfNeeded(client);

        y = textDrawer.draw(ctx, Text.literal("🤖 AI 语义（保存后供检索）"), x, y, w, 0xFFFFFFFF);
        y += 2;

        int halfW = (w - 4) / 2;
        culturalStyleButton.setMessage(Text.literal("文化风格: " + formatCulturalStyleLabel(st.culturalStyleOverride)));
        culturalStyleButton.setPosition(x, y);
        culturalStyleButton.setWidth(halfW);
        culturalStyleButton.visible = true;
        culturalStyleButton.active = true;
        culturalStyleButton.render(ctx, mouseX, mouseY, 0f);

        geometryArchetypeButton.setMessage(Text.literal("几何原型: " + formatGeometryArchetypeLabel(st.geometryArchetypeOverride)));
        geometryArchetypeButton.setPosition(x + halfW + 4, y);
        geometryArchetypeButton.setWidth(w - halfW - 4);
        geometryArchetypeButton.visible = true;
        geometryArchetypeButton.active = true;
        geometryArchetypeButton.render(ctx, mouseX, mouseY, 0f);
        y += LABEL_OFFSET;

        String culturalPreview = data.culturalStyle() != null ? data.culturalStyle() : "（待推断）";
        String geometryPreview = data.geometryArchetype() != null ? data.geometryArchetype() : "（待推断）";
        String archetypePreview = data.archetypeRef() != null ? data.archetypeRef() : "（保存时生成）";
        y = textDrawer.draw(ctx,
                Text.literal("预览 → 风格: " + culturalPreview + "  |  形态: " + geometryPreview),
                x, y, w, 0xFF88CCFF);
        y = textDrawer.draw(ctx, Text.literal("原型引用: " + archetypePreview), x, y, w, 0xFFAAAAAA);

        String placementSummary = data.placementSummary() != null ? data.placementSummary() : "（待分析）";
        y = textDrawer.draw(ctx, Text.literal("放置分析 → " + placementSummary), x, y, w, 0xFFAAFFAA);
        if (data.placementHint() != null && !data.placementHint().isBlank()) {
            y = textDrawer.draw(ctx, Text.literal(data.placementHint()), x, y, w, 0xFF99BB99);
        }
        y += 4;
        return y;
    }

    public static String formatCulturalStyleLabel(String value) {
        if (value == null || value.isBlank()) {
            return "自动";
        }
        return switch (value) {
            case "CHINESE" -> "中式";
            case "JAPANESE" -> "日式";
            case "GOTHIC" -> "哥特";
            case "MEDIEVAL" -> "中世纪";
            case "MODERN" -> "现代";
            case "EUROPEAN" -> "欧式";
            case "ISLAMIC" -> "伊斯兰";
            case "INDUSTRIAL" -> "工业";
            default -> value;
        };
    }

    public static String formatGeometryArchetypeLabel(String value) {
        if (value == null || value.isBlank()) {
            return "自动";
        }
        return switch (value) {
            case "FLAT_PANEL" -> "平面板";
            case "ARCH" -> "拱形";
            case "COLUMN" -> "柱形";
            case "FRAME" -> "框架";
            case "ORNAMENT" -> "装饰件";
            case "LINEAR" -> "线性";
            case "VOLUME" -> "体块";
            default -> value;
        };
    }

    private static String nextRingOption(String[] options, String current) {
        int idx = 0;
        for (int i = 0; i < options.length; i++) {
            if ((options[i] == null && (current == null || current.isBlank()))
                    || (options[i] != null && options[i].equals(current))) {
                idx = i;
                break;
            }
        }
        return options[(idx + 1) % options.length];
    }

    public record Snapshot(
            String culturalStyle,
            String geometryArchetype,
            String archetypeRef,
            String placementSummary,
            String placementHint
    ) {
        public static Snapshot empty() {
            return new Snapshot(null, null, null, null, null);
        }
    }
}
