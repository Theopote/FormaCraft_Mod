package com.formacraft.common.component.thumbnail;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.logging.FcaLog;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * v1 简易缩略图生成器：
 * - 不依赖客户端纹理系统
 * - 用“俯视高度图 + 颜色 hash”生成可辨认缩略图
 * - 用于组件面板快速预览（不追求真实材质）
 */
public final class ComponentThumbnailGenerator {
    private ComponentThumbnailGenerator() {}

    private static final FcaLog LOG = FcaLog.of("ComponentThumbnailGenerator");

    public static void generate(ComponentDefinition def, Path outPng, int sizePx) {
        if (def == null || def.blocks == null || def.blocks.isEmpty()) return;
        if (outPng == null) return;
        int size = Math.max(32, Math.min(512, sizePx <= 0 ? 128 : sizePx));

        // bounds (local)
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (ComponentDefinition.BlockEntry be : def.blocks) {
            if (be == null) continue;
            minX = Math.min(minX, be.dx);
            minY = Math.min(minY, be.dy);
            minZ = Math.min(minZ, be.dz);
            maxX = Math.max(maxX, be.dx);
            maxY = Math.max(maxY, be.dy);
            maxZ = Math.max(maxZ, be.dz);
        }
        if (minX == Integer.MAX_VALUE) return;

        int w = Math.max(1, maxX - minX + 1);
        int d = Math.max(1, maxZ - minZ + 1);
        int h = Math.max(1, maxY - minY + 1);

        // collect per (x,z) topmost
        record Top(int y, String block) {}
        Map<Long, Top> top = new HashMap<>();
        for (ComponentDefinition.BlockEntry be : def.blocks) {
            if (be == null) continue;
            int x = be.dx - minX;
            int z = be.dz - minZ;
            int y = be.dy - minY;
            long k = (((long) x) << 32) ^ (z & 0xffffffffL);
            Top cur = top.get(k);
            if (cur == null || y > cur.y) {
                top.put(k, new Top(y, be.block));
            }
        }

        // fit into image with padding
        int pad = Math.max(2, size / 16);
        int avail = size - pad * 2;
        int cell = Math.max(1, Math.min(avail / Math.max(w, d), 8)); // cap cell size
        int drawW = w * cell;
        int drawD = d * cell;
        int ox = (size - drawW) / 2;
        int oz = (size - drawD) / 2;

        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(new Color(0, 0, 0, 0));
            g.fillRect(0, 0, size, size);

            // background
            g.setColor(new Color(18, 18, 24, 255));
            g.fillRect(0, 0, size, size);

            // draw cells
            for (int z = 0; z < d; z++) {
                for (int x = 0; x < w; x++) {
                    long k = (((long) x) << 32) ^ (z & 0xffffffffL);
                    Top t = top.get(k);
                    if (t == null) continue;
                    float hn = h <= 1 ? 0.5f : (t.y / (float) (h - 1));
                    int base = hashColor(t.block);
                    int r = (base >> 16) & 0xFF;
                    int gg = (base >> 8) & 0xFF;
                    int b = base & 0xFF;

                    // height shading
                    float shade = 0.55f + 0.45f * hn;
                    r = clamp255((int) (r * shade));
                    gg = clamp255((int) (gg * shade));
                    b = clamp255((int) (b * shade));

                    g.setColor(new Color(r, gg, b, 235));
                    int px = ox + x * cell;
                    int py = oz + z * cell;
                    g.fillRect(px, py, cell, cell);
                }
            }

            // border
            g.setColor(new Color(255, 255, 255, 120));
            g.drawRect(ox - 1, oz - 1, drawW + 1, drawD + 1);

            // id hint stripe (optional)
            g.setColor(new Color(0, 0, 0, 90));
            g.fillRect(0, size - Math.max(8, size / 10), size, Math.max(8, size / 10));
        } finally {
            g.dispose();
        }

        try {
            Files.createDirectories(outPng.getParent());
            try (OutputStream os = Files.newOutputStream(outPng)) {
                ImageIO.write(img, "PNG", os);
            }
        } catch (Throwable t) {
            LOG.warn("write thumbnail failed path={}", outPng, t);
        }
    }

    private static int hashColor(String s) {
        if (s == null) return 0x88AACC;
        int h = 0;
        for (int i = 0; i < s.length(); i++) {
            h = (h * 31) ^ s.charAt(i);
        }
        // keep colors not too dark
        int r = 80 + (h & 0x7F);
        int g = 80 + ((h >> 8) & 0x7F);
        int b = 80 + ((h >> 16) & 0x7F);
        return (r << 16) | (g << 8) | b;
    }

    private static int clamp255(int v) {
        if (v < 0) return 0;
        if (v > 255) return 255;
        return v;
    }
}

