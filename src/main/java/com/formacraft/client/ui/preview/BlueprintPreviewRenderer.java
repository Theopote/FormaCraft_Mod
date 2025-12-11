package com.formacraft.client.ui.preview;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.Footprint;
import com.formacraft.common.model.build.Materials;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * 绘制 BuildingSpec 的缩略图（蓝图预览图）
 * - 顶视图 footprint
 * - 材质颜色
 * - 简单屋顶形状示意
 * - 建筑高度条形示意
 */
public class BlueprintPreviewRenderer {

    private static final MinecraftClient client = MinecraftClient.getInstance();

    // 材质色表（你可以继续扩展）
    private static int wallColor(String mat) {
        if (mat == null) return 0xFF666666;
        
        String matLower = mat.toLowerCase();
        if (matLower.contains("stone")) return 0xFF888888;
        if (matLower.contains("brick")) return 0xFFB04040;
        if (matLower.contains("plank")) {
            if (matLower.contains("spruce") || matLower.contains("dark")) return 0xFF8B5A2B;
            if (matLower.contains("oak")) return 0xFFCCAA66;
            return 0xFFAA8844;
        }
        if (matLower.contains("cobble")) return 0xFF777777;
        if (matLower.contains("wood")) return 0xFF8B5A2B;
        if (matLower.contains("glass")) return 0xFF88CCFF;
        
        return 0xFF777777;
    }

    private static int roofColor(String mat) {
        if (mat == null) return 0xFF444444;
        
        String matLower = mat.toLowerCase();
        if (matLower.contains("stone")) return 0xFF555555;
        if (matLower.contains("brick")) return 0xFF993333;
        if (matLower.contains("plank")) {
            if (matLower.contains("spruce") || matLower.contains("dark")) return 0xFF704420;
            if (matLower.contains("oak")) return 0xFFAA8844;
            return 0xFF996633;
        }
        if (matLower.contains("slab")) return 0xFF666666;
        
        return 0xFF555555;
    }

    /**
     * 在 BlueprintPanel 的一行内绘制一个 64x64 的蓝图预览图。
     */
    public static void drawPreview(DrawContext ctx, BuildingSpec spec,
                                   int px, int py, int w, int h) {

        // 半透明背景
        ctx.fill(px, py, px + w, py + h, 0x33333333);

        if (spec == null || spec.getFootprint() == null) {
            ctx.drawText(client.textRenderer, "No preview",
                    px + 8, py + h / 2 - 5, 0xAAAAAA, false);
            return;
        }

        Footprint fp = spec.getFootprint();

        // ============ 顶视轮廓 =============
        if ("circle".equals(fp.getShape())) {
            drawCircleFootprint(ctx, spec, px, py, w, h);
        } else {
            drawRectFootprint(ctx, spec, px, py, w, h);
        }

        // ============ 高度条 ================
        drawHeightIndicator(ctx, spec, px, py, w, h);

        // 可添加更多元素，比如门、窗、塔楼标记
    }

    // ======================================================
    // 顶视图 - 圆形 footprint
    // ======================================================
    private static void drawCircleFootprint(DrawContext ctx, BuildingSpec spec,
                                            int px, int py, int w, int h) {

        int cx = px + w / 2;
        int cy = py + h / 2;

        Materials materials = spec.getMaterials();
        int wallColor = wallColor(materials != null ? materials.getWall() : null);
        int roofColor = roofColor(materials != null ? materials.getRoof() : null);

        int rPx = Math.min(w, h) / 2 - 4;
        rPx = Math.max(4, Math.min(rPx, 30)); // 限制半径范围

        // 外圈 = 墙体（绘制圆形轮廓）
        drawCircleOutline(ctx, cx, cy, rPx, wallColor);

        // 内部填充 = 屋顶颜色（淡一点）
        for (int dx = -rPx; dx <= rPx; dx++) {
            for (int dy = -rPx; dy <= rPx; dy++) {
                int distSq = dx * dx + dy * dy;
                if (distSq <= rPx * rPx && distSq > (rPx - 2) * (rPx - 2)) {
                    // 只填充边缘区域（墙体）
                    ctx.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, wallColor);
                } else if (distSq <= (rPx - 2) * (rPx - 2)) {
                    // 内部区域（屋顶）
                    ctx.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, roofColor & 0x66FFFFFF);
                }
            }
        }
    }

    // ======================================================
    // 顶视图 - 矩形 footprint
    // ======================================================
    private static void drawRectFootprint(DrawContext ctx, BuildingSpec spec,
                                          int px, int py, int w, int h) {

        int fw = spec.getFootprint().getWidth();
        int fd = spec.getFootprint().getDepth();

        Materials materials = spec.getMaterials();
        int wallColor = wallColor(materials != null ? materials.getWall() : null);
        int roofColor = roofColor(materials != null ? materials.getRoof() : null);

        // 转换 footprint 到像素
        int maxDim = Math.max(fw, fd);
        float scale = (float)(w - 8) / Math.max(maxDim, 1);

        int rw = (int)(fw * scale);
        int rh = (int)(fd * scale);

        int ox = px + (w - rw) / 2;
        int oy = py + (h - rh) / 2;

        // 外框（墙体）
        ctx.fill(ox, oy, ox + rw, oy + 2, wallColor);                // 上
        ctx.fill(ox, oy + rh - 2, ox + rw, oy + rh, wallColor);      // 下
        ctx.fill(ox, oy, ox + 2, oy + rh, wallColor);                // 左
        ctx.fill(ox + rw - 2, oy, ox + rw, oy + rh, wallColor);      // 右

        // 内部填充（屋顶）
        ctx.fill(ox + 2, oy + 2, ox + rw - 2, oy + rh - 2,
                roofColor & 0x55FFFFFF);
    }

    // ======================================================
    // 绘制圆形边界
    // ======================================================
    private static void drawCircleOutline(DrawContext ctx, int cx, int cy, int r, int color) {
        // 使用 Bresenham 圆形算法绘制更平滑的圆
        for (int d = 0; d < 360; d += 3) {
            double rad = Math.toRadians(d);
            int x = cx + (int)(Math.cos(rad) * r);
            int y = cy + (int)(Math.sin(rad) * r);
            ctx.fill(x, y, x + 1, y + 1, color);
        }
    }

    // ======================================================
    // 侧视图 - 建筑高度条
    // ======================================================
    private static void drawHeightIndicator(DrawContext ctx, BuildingSpec spec,
                                            int px, int py, int w, int h) {

        int height = spec.getHeight();
        if (height <= 0) return;

        int barX = px + w - 10;
        int barY = py + 4;
        int barH = h - 8;

        // 背景条
        ctx.fill(barX, barY, barX + 3, barY + barH, 0x55222222);

        // 填充高度比例
        float p = Math.min(1f, height / 80f);  // 80 高度满条，可调整
        int filled = (int)(barH * p);

        // 高度条颜色（根据高度渐变）
        int barColor = height > 40 ? 0xFF66AAFF : (height > 20 ? 0xFF88CC88 : 0xFFFFAA66);
        ctx.fill(barX, barY + barH - filled, barX + 3, barY + barH, barColor);

        // 顶部写高度数值（如果空间足够）
        if (h > 40) {
            ctx.drawText(client.textRenderer, "" + height,
                    barX - 12, barY - 2, 0xAAAAAA, false);
        }
    }
}

