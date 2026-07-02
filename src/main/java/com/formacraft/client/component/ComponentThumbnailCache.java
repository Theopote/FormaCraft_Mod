package com.formacraft.client.component;

import com.formacraft.common.component.ComponentStorage;
import com.formacraft.common.logging.FcaLog;

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

    private static final FcaLog LOG = FcaLog.of("ComponentThumbnailCache");

    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    public record Thumb(int w, int h, int[] argb) {}
    private record Cached(Thumb thumb, long lastModifiedMs) {}

    /**
     * @param componentId 构件 id
     * @param maxSize 缩放到不超过该尺寸（建议 32）
     */
    private static final boolean DEBUG_THUMBNAIL = false; // 调试开关

    public static Thumb getThumb(String componentId, int maxSize) {
        if (componentId == null || componentId.isBlank()) {
            if (DEBUG_THUMBNAIL) {
                com.formacraft.FormacraftMod.LOGGER.debug("[ComponentThumbnailCache] componentId 为空");
            }
            return null;
        }
        
        String id = componentId.trim();
        Path file = ComponentStorage.getGlobalComponentDir().resolve(id + ".png");
        
        if (!Files.exists(file)) {
            // 文件不存在是正常情况（构件可能没有缩略图），只在调试模式下打印
            if (DEBUG_THUMBNAIL) {
                com.formacraft.FormacraftMod.LOGGER.debug("[ComponentThumbnailCache] PNG 文件不存在: {}", file.toAbsolutePath());
            }
            return null;
        }

        long lm = 0L;
        try {
            lm = Files.getLastModifiedTime(file).toMillis();
        } catch (Throwable t) {
            LOG.debug("read thumbnail mtime failed componentId={}", id, t);
        }

        Cached c = CACHE.get(id);
        if (c != null && c.lastModifiedMs == lm) {
            if (DEBUG_THUMBNAIL) {
                com.formacraft.FormacraftMod.LOGGER.debug("[ComponentThumbnailCache] 从缓存加载: {}", id);
            }
            return c.thumb;
        }

        try (InputStream in = Files.newInputStream(file)) {
            BufferedImage img = ImageIO.read(in);
            if (img == null) {
                com.formacraft.FormacraftMod.LOGGER.warn("[ComponentThumbnailCache] ImageIO.read 返回 null: {}", file);
                return null;
            }
            Thumb t = downscale(img, Math.max(8, Math.min(128, maxSize)));
            if (t == null) {
                com.formacraft.FormacraftMod.LOGGER.warn("[ComponentThumbnailCache] downscale 返回 null");
                return null;
            }
            if (DEBUG_THUMBNAIL) {
                com.formacraft.FormacraftMod.LOGGER.debug("[ComponentThumbnailCache] ✓ 加载成功: {} ({}x{})", id, t.w(), t.h());
            }
            CACHE.put(id, new Cached(t, lm));
            return t;
        } catch (Throwable e) {
            com.formacraft.FormacraftMod.LOGGER.error("[ComponentThumbnailCache] 读取失败: {}", file, e);
            return null;
        }
    }

    /**
     * 清除指定构件的缓存
     * @param componentId 构件 id
     */
    public static void clearCache(String componentId) {
        if (componentId == null || componentId.isBlank()) return;
        String id = componentId.trim();
        CACHE.remove(id);
    }

    /**
     * 清除所有缓存
     */
    public static void clearAllCache() {
        CACHE.clear();
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

