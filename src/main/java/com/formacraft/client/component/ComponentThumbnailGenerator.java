package com.formacraft.client.component;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.mixin.SpriteContentsAccessor;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.*;

/** Baked models and texture pixels are detached on the client thread before worker rasterization. */
public final class ComponentThumbnailGenerator {
    private ComponentThumbnailGenerator() {}
    private static int resourceEpoch;
    public static int resourceEpoch() { return resourceEpoch; }
    public static void resourcesReloaded() { resourceEpoch++; }
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Formacraft-model-thumbnail"); t.setDaemon(true); return t;
    });
    public static CompletableFuture<BufferedImage> generateAsync(ComponentDefinition definition) {
        try {
            var mesh = snapshot(definition);
            return CompletableFuture.supplyAsync(() -> ThumbnailRasterizer.render(mesh, 256), WORKER);
        } catch (Exception e) { return CompletableFuture.failedFuture(e); }
    }
    public static BufferedImage generateThumbnail(ComponentDefinition definition) {
        return ThumbnailRasterizer.render(snapshot(definition), 256);
    }
    private static List<ThumbnailRasterizer.Face> snapshot(ComponentDefinition definition) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread()) throw new IllegalStateException("Model snapshots require the client thread");
        List<ThumbnailRasterizer.Face> faces = new ArrayList<>();
        if (definition == null || definition.blocks == null) return faces;
        Map<String, BlockState> states = new HashMap<>();
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (var block : definition.blocks) {
            if (block == null || block.block == null) continue;
            BlockState state = states.computeIfAbsent(block.block, text -> {
                try { return BlockArgumentParser.block(Registries.BLOCK, text, false).blockState(); }
                catch (Exception e) { return net.minecraft.block.Blocks.AIR.getDefaultState(); }
            });
            if (!state.isAir()) blocks.put(new BlockPos(block.dx, block.dy, block.dz), state);
        }
        Map<net.minecraft.client.texture.SpriteContents, ThumbnailRasterizer.Texture> textures = new IdentityHashMap<>();
        for (var entry : blocks.entrySet()) {
            BlockPos pos = entry.getKey(); BlockState state = entry.getValue();
            if (state.getRenderType() != net.minecraft.block.BlockRenderType.MODEL) continue;
            var parts = client.getBlockRenderManager().getModel(state).getParts(Random.create(state.getRenderingSeed(pos)));
            for (var part : parts) for (int side = -1; side < 6; side++) {
                Direction direction = side < 0 ? null : Direction.values()[side];
                BlockState neighbor = direction == null ? null : blocks.get(pos.offset(direction));
                if (neighbor != null && neighbor.isOpaqueFullCube()) continue;
                for (var quad : part.getQuads(direction)) {
                    var sprite = quad.sprite();
                    var texture = textures.computeIfAbsent(sprite.getContents(), contents -> {
                        var image = ((SpriteContentsAccessor) (Object) contents).formacraft$getImage();
                        int width = contents.getWidth(), height = contents.getHeight();
                        int frame = contents.isAnimated() ? contents.getDistinctFrameCount().findFirst().orElse(0) : 0;
                        int frameX = frame % (image.getWidth() / width) * width;
                        int frameY = frame / (image.getWidth() / width) * height;
                        int[] pixels = new int[width * height];
                        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
                            pixels[y * width + x] = image.getColorArgb(frameX + x, frameY + y);
                        return new ThumbnailRasterizer.Texture(width, height, pixels);
                    });
                    int[] data = quad.vertexData(); int stride = data.length / 4;
                    ThumbnailRasterizer.Vertex[] vertices = new ThumbnailRasterizer.Vertex[4];
                    for (int i = 0; i < 4; i++) {
                        int offset = i * stride;
                        vertices[i] = new ThumbnailRasterizer.Vertex(
                            pos.getX() + Float.intBitsToFloat(data[offset]), pos.getY() + Float.intBitsToFloat(data[offset + 1]),
                            pos.getZ() + Float.intBitsToFloat(data[offset + 2]), sprite.getFrameFromU(Float.intBitsToFloat(data[offset + 4])),
                            sprite.getFrameFromV(Float.intBitsToFloat(data[offset + 5])));
                    }
                    int tint = quad.hasTint() ? client.getBlockColors().getColor(state, null, null, quad.tintIndex()) : 0xFFFFFF;
                    double light = !quad.shade() || quad.lightEmission() > 0 ? 1 : switch (quad.face()) {
                        case UP -> 1; case DOWN -> .5; case SOUTH, NORTH -> .8; default -> .65;
                    };
                    faces.add(new ThumbnailRasterizer.Face(vertices, texture, tint, light));
                }
            }
        }
        return faces;
    }
}
