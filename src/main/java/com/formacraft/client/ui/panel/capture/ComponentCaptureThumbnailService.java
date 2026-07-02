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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 构件捕获面板的缩略图生成、缓存与展示。
 * <p>
 * 世界/客户端状态仅在主线程读取并快照；图像生成在专用后台线程完成。
 */
public final class ComponentCaptureThumbnailService {
    private static final boolean DEBUG = false;

    private static final ExecutorService THUMBNAIL_EXECUTOR = Executors.newSingleThreadExecutor(new ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "ComponentCaptureThumbnail-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });

    private volatile BufferedImage cachedThumbnail;
    private BlockPos lastSelectionMin;
    private BlockPos lastSelectionMax;
    private volatile boolean isGenerating;
    private volatile long generationToken;

    public void invalidate() {
        generationToken++;
        cachedThumbnail = null;
        lastSelectionMin = null;
        lastSelectionMax = null;
        isGenerating = false;
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
                lastSelectionMin = min;
                lastSelectionMax = max;
                regenerateAsync(client);
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

    /**
     * 保存路径：调用方必须在客户端线程，且 {@code def} 已是快照。
     */
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
        if (client == null || isGenerating) {
            if (DEBUG && isGenerating) {
                FormacraftMod.LOGGER.debug("[ThumbnailService] generation already in progress");
            }
            return;
        }

        cachedThumbnail = null;
        isGenerating = true;
        final long token = generationToken;

        client.execute(() -> {
            if (token != generationToken) {
                isGenerating = false;
                return;
            }

            ComponentDefinition snapshot = captureSnapshotOnClientThread(client);
            if (snapshot == null || token != generationToken) {
                isGenerating = false;
                return;
            }

            CompletableFuture
                    .supplyAsync(() -> ComponentThumbnailGenerator.generateThumbnail(snapshot), THUMBNAIL_EXECUTOR)
                    .whenComplete((image, error) -> client.execute(() -> applyGeneratedThumbnail(token, image, error)));
        });
    }

    private ComponentDefinition captureSnapshotOnClientThread(MinecraftClient client) {
        if (client.world == null) {
            return null;
        }
        if (!SelectionTool.INSTANCE.hasSelection()) {
            return null;
        }

        var st = ComponentTool.INSTANCE.getState();
        if (st.captureDraft.anchor.worldPos == null) {
            return null;
        }

        String json = ComponentTool.INSTANCE.buildCurrentComponentJson(client, st.captureDraft);
        if (json == null || json.isBlank()) {
            return null;
        }

        // JSON 往返得到与当前世界状态解耦的快照
        return JsonUtil.fromJson(json, ComponentDefinition.class);
    }

    private void applyGeneratedThumbnail(long token, BufferedImage image, Throwable error) {
        try {
            if (token != generationToken) {
                return;
            }
            if (error != null) {
                FormacraftMod.LOGGER.error("[ThumbnailService] async generation failed", error);
                return;
            }
            if (image != null) {
                cachedThumbnail = image;
            }
        } finally {
            if (token == generationToken) {
                isGenerating = false;
            }
        }
    }
}
