package com.formacraft.client.ui.render;

import net.minecraft.client.gui.DrawContext;

import java.awt.image.BufferedImage;

/**
 * 图像渲染工具：
 * - 将 BufferedImage 渲染到 Minecraft DrawContext
 * - 使用 fill() 逐像素绘制（避免纹理系统复杂性）
 */
public final class ImageRenderer {
    private ImageRenderer() {}

    /**
     * 渲染 BufferedImage 到指定位置（保持原始尺寸）
     * @param ctx DrawContext
     * @param image 要渲染的图像
     * @param x 左上角 X 坐标
     * @param y 左上角 Y 坐标
     */
    public static void render(DrawContext ctx, BufferedImage image, int x, int y) {
        if (image == null) return;
        
        int width = image.getWidth();
        int height = image.getHeight();
        
        for (int py = 0; py < height; py++) {
            for (int px = 0; px < width; px++) {
                int argb = image.getRGB(px, py);
                // 只绘制不透明的像素
                if ((argb & 0xFF000000) != 0) {
                    ctx.fill(x + px, y + py, x + px + 1, y + py + 1, argb);
                }
            }
        }
    }

    /**
     * 渲染 BufferedImage 到指定位置和尺寸（缩放）
     * @param ctx DrawContext
     * @param image 要渲染的图像
     * @param x 左上角 X 坐标
     * @param y 左上角 Y 坐标
     * @param width 目标宽度
     * @param height 目标高度
     */
    public static void renderScaled(DrawContext ctx, BufferedImage image, int x, int y, int width, int height) {
        if (image == null || width <= 0 || height <= 0) return;
        
        int srcWidth = image.getWidth();
        int srcHeight = image.getHeight();
        
        if (srcWidth <= 0 || srcHeight <= 0) return;
        
        int pixelsRendered = 0;
        
        // 使用最近邻插值进行缩放
        for (int py = 0; py < height; py++) {
            int srcY = (int) ((py / (double) height) * srcHeight);
            if (srcY >= srcHeight) srcY = srcHeight - 1;
            
            for (int px = 0; px < width; px++) {
                int srcX = (int) ((px / (double) width) * srcWidth);
                if (srcX >= srcWidth) srcX = srcWidth - 1;
                
                int argb = image.getRGB(srcX, srcY);
                // 只绘制不透明的像素
                if ((argb & 0xFF000000) != 0) {
                    ctx.fill(x + px, y + py, x + px + 1, y + py + 1, argb);
                    pixelsRendered++;
                }
            }
        }
        
        System.out.println("[ImageRenderer] renderScaled 完成: 渲染了 " + pixelsRendered + " 个像素");
    }

    /**
     * 渲染 BufferedImage 到指定区域（居中，保持宽高比，直接缩放）
     * @param ctx DrawContext
     * @param image 要渲染的图像
     * @param x 区域左上角 X 坐标
     * @param y 区域左上角 Y 坐标
     * @param areaWidth 区域宽度
     * @param areaHeight 区域高度
     */
    public static void renderCentered(DrawContext ctx, BufferedImage image, int x, int y, int areaWidth, int areaHeight) {
        if (image == null) {
            return;
        }
        if (areaWidth <= 0 || areaHeight <= 0) {
            return;
        }
        
        int srcWidth = image.getWidth();
        int srcHeight = image.getHeight();
        
        if (srcWidth <= 0 || srcHeight <= 0) {
            return;
        }
        
        // 简单的缩放策略：直接按区域尺寸缩放（1:1填充）
        // 因为我们在生成时已经使用了 0.85 的填充比例，所以这里直接缩放即可
        int targetWidth = areaWidth;
        int targetHeight = areaHeight;
        
        renderScaled(ctx, image, x, y, targetWidth, targetHeight);
    }
}
