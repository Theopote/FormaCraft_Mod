package com.formacraft.client.component;

import com.formacraft.common.component.ComponentDefinition;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * 构件缩略图生成器（客户端）：
 * - 从 ComponentDefinition 的体素数据生成 PNG 缩略图
 * - 使用正交投影（等轴测视图）渲染
 * - 简化渲染：使用方块的平均颜色
 */
public final class ComponentThumbnailGenerator {
    private ComponentThumbnailGenerator() {}

    private static final int THUMB_SIZE = 64; // 缩略图尺寸
    private static final Map<Block, Integer> COLOR_CACHE = new HashMap<>();

    /**
     * 从构件定义生成缩略图
     * @param def 构件定义
     * @return BufferedImage 或 null（如果生成失败）
     */
    @Nullable
    public static BufferedImage generateThumbnail(ComponentDefinition def) {
        if (def == null || def.blocks == null || def.blocks.isEmpty()) {
            return null;
        }

        // 解析方块数据，找到边界
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        Map<String, BlockState> blockMap = new HashMap<>();
        for (var entry : def.blocks) {
            if (entry == null) continue;
            int x = entry.dx;
            int y = entry.dy;
            int z = entry.dz;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);

            BlockState state = parseBlockState(entry.block);
            if (state != null) {
                String key = x + "," + y + "," + z;
                blockMap.put(key, state);
            }
        }

        if (blockMap.isEmpty()) return null;

        int sizeX = maxX - minX + 1;
        int sizeY = maxY - minY + 1;
        int sizeZ = maxZ - minZ + 1;

        // 使用等轴测投影（从右上方看）
        // 投影公式：screenX = (x - z) * scale, screenY = y * scale - (x + z) * scale / 2
        BufferedImage img = new BufferedImage(THUMB_SIZE, THUMB_SIZE, BufferedImage.TYPE_INT_ARGB);

        // 计算缩放比例
        double maxDim = Math.max(sizeX + sizeZ, sizeY + (sizeX + sizeZ) / 2.0);
        double scale = (THUMB_SIZE * 0.8) / maxDim;

        // 中心偏移
        int centerX = THUMB_SIZE / 2;
        int centerY = THUMB_SIZE / 2;

        // 先填充透明背景
        for (int y = 0; y < THUMB_SIZE; y++) {
            for (int x = 0; x < THUMB_SIZE; x++) {
                img.setRGB(x, y, 0x00000000);
            }
        }

        // 按照从后到前、从下到上的顺序渲染（简单的画家算法）
        for (int y = minY; y <= maxY; y++) {
            for (int z = maxZ; z >= minZ; z--) {
                for (int x = minX; x <= maxX; x++) {
                    String key = x + "," + y + "," + z;
                    BlockState state = blockMap.get(key);
                    if (state == null || state.isAir()) continue;

                    // 获取方块颜色
                    int color = getBlockColor(state);
                    if ((color & 0xFF000000) == 0) continue; // 跳过完全透明的方块

                    // 计算屏幕坐标（等轴测投影）
                    int rx = x - minX;
                    int ry = y - minY;
                    int rz = z - minZ;

                    double screenX = (rx - rz) * scale;
                    double screenY = ry * scale - (rx + rz) * scale / 2.0;

                    int px = (int) Math.round(centerX + screenX);
                    int py = (int) Math.round(centerY - screenY);

                    // 绘制一个小方块（2x2 像素）
                    for (int dy = 0; dy < 2; dy++) {
                        for (int dx = 0; dx < 2; dx++) {
                            int drawX = px + dx;
                            int drawY = py + dy;
                            if (drawX >= 0 && drawX < THUMB_SIZE && drawY >= 0 && drawY < THUMB_SIZE) {
                                // 简单的深度着色（越高越亮）
                                float brightness = 0.6f + 0.4f * (ry / (float) Math.max(1, sizeY));
                                int shadedColor = applyBrightness(color, brightness);
                                img.setRGB(drawX, drawY, shadedColor);
                            }
                        }
                    }
                }
            }
        }

        return img;
    }

    /**
     * 解析方块状态字符串（格式：blockId 或 blockId[prop=val,...]）
     */
    @Nullable
    private static BlockState parseBlockState(String str) {
        if (str == null || str.isBlank()) return null;
        try {
            int idx = str.indexOf('[');
            String blockId = (idx > 0) ? str.substring(0, idx) : str;
            Identifier id = Identifier.tryParse(blockId);
            if (id == null) return null;
            Block block = Registries.BLOCK.get(id);
            if (block == null || block == Blocks.AIR) return null;
            return block.getDefaultState();
            // 注意：这里忽略了方块属性，只使用默认状态
            // 如果需要更精确的颜色，可以解析属性
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 获取方块的平均颜色（简化版）
     */
    private static int getBlockColor(BlockState state) {
        Block block = state.getBlock();
        
        // 检查缓存
        if (COLOR_CACHE.containsKey(block)) {
            return COLOR_CACHE.get(block);
        }

        int color = 0xFFCCCCCC; // 默认灰色

        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.getBlockColors() != null) {
                BlockColors blockColors = client.getBlockColors();
                // 获取方块颜色（使用默认 tint index 0）
                int tint = blockColors.getColor(state, null, null, 0);
                if (tint != -1) {
                    color = 0xFF000000 | tint; // 添加完全不透明的 alpha
                }
            }
        } catch (Throwable ignored) {}

        // 特殊方块的颜色映射（简化）
        if (block == Blocks.STONE || block == Blocks.COBBLESTONE) {
            color = 0xFF7F7F7F;
        } else if (block == Blocks.DIRT || block == Blocks.COARSE_DIRT) {
            color = 0xFF8B5A3C;
        } else if (block == Blocks.GRASS_BLOCK) {
            color = 0xFF5C8A3E;
        } else if (block == Blocks.OAK_PLANKS) {
            color = 0xFFB8945F;
        } else if (block == Blocks.SPRUCE_PLANKS) {
            color = 0xFF6F4E37;
        } else if (block == Blocks.BIRCH_PLANKS) {
            color = 0xFFD7CB8D;
        } else if (block == Blocks.GLASS) {
            color = 0xCCFFFFFF; // 半透明白色
        } else if (block == Blocks.BRICKS) {
            color = 0xFF9A5A3C;
        } else if (block == Blocks.OAK_LOG || block == Blocks.SPRUCE_LOG) {
            color = 0xFF6F4E37;
        } else if (block == Blocks.OAK_LEAVES || block == Blocks.SPRUCE_LEAVES) {
            color = 0xFF5C8A3E;
        } else if (block == Blocks.SAND) {
            color = 0xFFDBD3A0;
        } else if (block == Blocks.SANDSTONE) {
            color = 0xFFC9B181;
        } else if (block == Blocks.WHITE_WOOL || block.toString().contains("wool")) {
            color = 0xFFEEEEEE;
        }

        COLOR_CACHE.put(block, color);
        return color;
    }

    /**
     * 应用亮度调整
     */
    private static int applyBrightness(int color, float brightness) {
        int a = (color >> 24) & 0xFF;
        int r = (int) (((color >> 16) & 0xFF) * brightness);
        int g = (int) (((color >> 8) & 0xFF) * brightness);
        int b = (int) ((color & 0xFF) * brightness);
        r = Math.min(255, Math.max(0, r));
        g = Math.min(255, Math.max(0, g));
        b = Math.min(255, Math.max(0, b));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
