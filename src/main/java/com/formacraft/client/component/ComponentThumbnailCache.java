package com.formacraft.client.component;

import com.formacraft.common.component.ComponentStorage;

import javax.imageio.ImageIO;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.awt.image.BufferedImage;

/**
 * 客户端缩略图缓存（v1）：
 * - 从全局组件库目录读取 <id>.png
 * - 直接解码为像素数组，GUI 用 DrawContext.fill 绘制（避免依赖纹理 API 版本差异）
 */
public final class ComponentThumbnailCache {
    private ComponentThumbnailCache() {}

    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    public record Thumb(int w, int h, int[] argb) {}
    private record Cached(Thumb thumb, long lastModifiedMs) {}

    /**
     * @param componentId 构件 id
     * @param maxSize 缩放到不超过该尺寸（建议 32）
     */
    public static Thumb getThumb(String componentId, int maxSize) {
        if (componentId == null || componentId.isBlank()) return null;
        String id = componentId.trim();
        Path file = ComponentStorage.getGlobalComponentDir().resolve(id + ".png");
        if (!Files.exists(file)) return null;

        long lm = 0L;
        try { lm = Files.getLastModifiedTime(file).toMillis(); } catch (Throwable ignored) {}

        Cached c = CACHE.get(id);
        if (c != null && c.lastModifiedMs == lm) {
            return c.thumb;
        }

        try (InputStream in = Files.newInputStream(file)) {
            BufferedImage img = ImageIO.read(in);
            if (img == null) return null;
            Thumb t = downscale(img, Math.max(8, Math.min(128, maxSize)));
            if (t == null) return null;
            CACHE.put(id, new Cached(t, lm));
            return t;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Thumb downscale(BufferedImage img, int maxSize) {
        if (img == null) return null;
        int w0 = img.getWidth();
        int h0 = img.getHeight();
        if (w0 <= 0 || h0 <= 0) return null;

        int w = w0;
        int h = h0;
        if (w0 > maxSize || h0 > maxSize) {
            double sx = maxSize / (double) w0;
            double sy = maxSize / (double) h0;
            double s = Math.min(sx, sy);
            w = Math.max(1, (int) Math.round(w0 * s));
            h = Math.max(1, (int) Math.round(h0 * s));
        }

        int[] out = new int[w * h];
        // nearest neighbor
        for (int y = 0; y < h; y++) {
            int sy = (int) Math.floor((y / (double) h) * h0);
            if (sy < 0) sy = 0;
            if (sy >= h0) sy = h0 - 1;
            for (int x = 0; x < w; x++) {
                int sx = (int) Math.floor((x / (double) w) * w0);
                if (sx < 0) sx = 0;
                if (sx >= w0) sx = w0 - 1;
                out[y * w + x] = img.getRGB(sx, sy);
            }
        }
        return new Thumb(w, h, out);
    }
}

