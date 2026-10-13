package com.formacraft.client.ui.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import java.awt.image.BufferedImage;
import java.util.*;

/** Upload each immutable thumbnail once, then submit a single textured GUI quad. */
public final class ImageRenderer {
    private ImageRenderer() {}
    private static final Map<BufferedImage, Identifier> TEXTURES = new LinkedHashMap<>(64, .75f, true);
    private static long sequence;
    public static void clear() {
        TEXTURES.values().forEach(id -> MinecraftClient.getInstance().getTextureManager().destroyTexture(id));
        TEXTURES.clear();
    }
    public static void render(DrawContext ctx, BufferedImage image, int x, int y) {
        if (image != null) renderScaled(ctx, image, x, y, image.getWidth(), image.getHeight());
    }
    public static void renderScaled(DrawContext ctx, BufferedImage image, int x, int y, int width, int height) {
        if (image == null || width <= 0 || height <= 0) return;
        Identifier id = TEXTURES.get(image);
        if (id == null) {
            NativeImage nativeImage = new NativeImage(image.getWidth(), image.getHeight(), false);
            for (int py = 0; py < image.getHeight(); py++) for (int px = 0; px < image.getWidth(); px++)
                nativeImage.setColorArgb(px, py, image.getRGB(px, py));
            id = Identifier.of("formacraft", "thumbnail/runtime_" + sequence++);
            MinecraftClient.getInstance().getTextureManager().registerTexture(id, new NativeImageBackedTexture(() -> "Formacraft thumbnail", nativeImage));
            TEXTURES.put(image, id);
            while (TEXTURES.size() > 96) {
                var oldest = TEXTURES.entrySet().iterator(); var entry = oldest.next();
                MinecraftClient.getInstance().getTextureManager().destroyTexture(entry.getValue()); oldest.remove();
            }
        }
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, id, x, y, 0, 0, width, height,
                image.getWidth(), image.getHeight(), image.getWidth(), image.getHeight());
    }
    public static void renderCentered(DrawContext ctx, BufferedImage image, int x, int y, int width, int height) {
        if (image == null || width <= 0 || height <= 0) return;
        double scale = Math.min(width / (double) image.getWidth(), height / (double) image.getHeight());
        int w = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(image.getHeight() * scale));
        renderScaled(ctx, image, x + (width - w) / 2, y + (height - h) / 2, w, h);
    }
}
