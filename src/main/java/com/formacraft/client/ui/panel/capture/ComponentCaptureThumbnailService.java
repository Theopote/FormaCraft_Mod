package com.formacraft.client.ui.panel.capture;

import com.formacraft.FormacraftMod;
import com.formacraft.client.component.ComponentThumbnailGenerator;
import com.formacraft.client.tool.ComponentTool;
import com.formacraft.client.tool.SelectionTool;
import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.json.JsonUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

/**
 * 构件捕获面板的缩略图生成、缓存与展示。
 */
public final class ComponentCaptureThumbnailService {
    private static final boolean DEBUG = false;

    private volatile BufferedImage cachedThumbnail;
    private BlockPos lastSelectionMin;
    private BlockPos lastSelectionMax;
    private volatile boolean isGenerating;

    public void invalidate() {
        cachedThumbnail = null;
        lastSelectionMin = null;
        lastSelectionMax = null;
    }

    public boolean isGenerating() {
        return isGenerating;
    }

    public void drawPreview(DrawContext ctx, MinecraftClient client, int x, int y, int displaySize) {
        ctx.fill(x - 1, y - 1, x + displaySize + 1, y + displaySize + 1, 0xFF444444);
        ctx.fill(x, y, x + displaySize, y + displaySize, 0xFF1A1A1A);

        BlockPos min = SelectionTool.INSTANCE.getMin();
        BlockPos max = SelectionTool.INSTANCE.getMax();
        if (max != null && min != null
                && (cachedThumbnail == null || !min.equals(lastSelectionMin) || !max.equals(lastSelectionMax))) {
            if (!isGenerating) {
                regenerateAsync(client);
                lastSelectionMin = min;
                lastSelectionMax = max;
            }
        }

        if (cachedThumbnail != null) {
            com.formacraft.client.ui.render.ImageRenderer.renderCentered(
                    ctx, cachedThumbnail, x, y, displaySize, displaySize);
        } else {
            ctx.drawTextWithShadow(
                    client.textRenderer,
                    Text.literal(isGenerating ? "生成中..." : "等待选区"),
                    x + displaySize / 2 - 24,
                    y + displaySize / 2,
                    0xFFAAAAAA
            );
        }
    }

    public byte[] encodePng(ComponentDefinition def) {
        if (def == null) {
            return null;
        }
        try {
            BufferedImage thumb = ComponentThumbnailGenerator.generateThumbnail(def);
            if (thumb == null) {
                return null;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(thumb, "PNG", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            FormacraftMod.LOGGER.warn("Failed to encode component thumbnail PNG", e);
            return null;
        }
    }

    private void regenerateAsync(MinecraftClient client) {
        if (isGenerating) {
            if (DEBUG) {
                FormacraftMod.LOGGER.debug("[ThumbnailService] generation already in progress");
            }
            return;
        }

        cachedThumbnail = null;
        isGenerating = true;

        new Thread(() -> {
            try {
                if (client == null || client.world == null) {
                    return;
                }
                if (!SelectionTool.INSTANCE.hasSelection()) {
                    return;
                }
                var st = ComponentTool.INSTANCE.getState();
                if (st.captureDraft.anchor.worldPos == null) {
                    return;
                }

                String json = ComponentTool.INSTANCE.buildCurrentComponentJson(client, st.captureDraft);
                if (json == null) {
                    return;
                }
                ComponentDefinition def = JsonUtil.fromJson(json, ComponentDefinition.class);
                if (def == null) {
                    return;
                }
                BufferedImage thumb = ComponentThumbnailGenerator.generateThumbnail(def);
                if (thumb != null) {
                    cachedThumbnail = thumb;
                }
            } catch (Exception e) {
                FormacraftMod.LOGGER.error("[ThumbnailService] async generation failed", e);
            } finally {
                isGenerating = false;
            }
        }, "ComponentCaptureThumbnail").start();
    }
}
