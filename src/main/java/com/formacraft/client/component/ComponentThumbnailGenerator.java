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

        // 计算缩放比例 - 使每个方块占据更多像素
        double maxDim = Math.max(sizeX + sizeZ, sizeY + (sizeX + sizeZ) / 2.0);
        double scale = (THUMB_SIZE * 0.7) / maxDim; // 稍微放大一点
        
        // 确保每个方块至少有 3 像素大小
        int blockPixelSize = Math.max(3, (int) Math.ceil(scale));

        // 中心偏移
        int centerX = THUMB_SIZE / 2;
        int centerY = (int) (THUMB_SIZE * 0.6); // 稍微向下偏移

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

                    // 绘制一个方块（使用计算的像素大小）
                    // 三个可见面：顶面、左面、右面（等轴测视图）
                    for (int dy = 0; dy < blockPixelSize; dy++) {
                        for (int dx = 0; dx < blockPixelSize; dx++) {
                            int drawX = px + dx;
                            int drawY = py + dy;
                            if (drawX >= 0 && drawX < THUMB_SIZE && drawY >= 0 && drawY < THUMB_SIZE) {
                                // 计算亮度：顶部最亮，两侧较暗
                                float brightness;
                                if (dy < blockPixelSize / 3) {
                                    // 顶面 - 最亮
                                    brightness = 1.0f;
                                } else if (dx < blockPixelSize / 2) {
                                    // 左面 - 中等亮度
                                    brightness = 0.7f;
                                } else {
                                    // 右面 - 稍暗
                                    brightness = 0.85f;
                                }
                                
                                // 根据高度调整整体亮度
                                float heightFactor = 0.7f + 0.3f * (ry / (float) Math.max(1, sizeY));
                                brightness *= heightFactor;
                                
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

        int color = 0xFFAAAAAA; // 默认中灰色
        
        // 使用方块名称进行颜色映射（更可靠）
        String blockName = Registries.BLOCK.getId(block).getPath().toLowerCase();
        
        // 石头类
        if (blockName.contains("stone") && !blockName.contains("sandstone")) {
            color = 0xFF7F7F7F; // 灰色
        } else if (blockName.contains("cobblestone") || blockName.contains("cobble")) {
            color = 0xFF7F7F7F;
        } else if (blockName.contains("andesite")) {
            color = 0xFF838383;
        } else if (blockName.contains("diorite")) {
            color = 0xFFC4C4C4;
        } else if (blockName.contains("granite")) {
            color = 0xFF9F5D56;
        }
        // 泥土和草
        else if (blockName.contains("dirt") || blockName.contains("soil")) {
            color = 0xFF8B5A3C;
        } else if (blockName.contains("grass_block")) {
            color = 0xFF5C8A3E;
        }
        // 木板
        else if (blockName.contains("oak") && blockName.contains("planks")) {
            color = 0xFFB8945F;
        } else if (blockName.contains("spruce") && blockName.contains("planks")) {
            color = 0xFF6F4E37;
        } else if (blockName.contains("birch") && blockName.contains("planks")) {
            color = 0xFFD7CB8D;
        } else if (blockName.contains("jungle") && blockName.contains("planks")) {
            color = 0xFF9F7654;
        } else if (blockName.contains("acacia") && blockName.contains("planks")) {
            color = 0xFFB4684D;
        } else if (blockName.contains("dark_oak") && blockName.contains("planks")) {
            color = 0xFF4F2F1C;
        }
        // 原木
        else if (blockName.contains("log")) {
            color = 0xFF6F4E37;
        }
        // 叶子
        else if (blockName.contains("leaves")) {
            color = 0xFF5C8A3E;
        }
        // 玻璃
        else if (blockName.contains("glass")) {
            color = 0xAADDDDDD; // 半透明
        }
        // 砖类
        else if (blockName.contains("bricks") || blockName.equals("bricks")) {
            color = 0xFF9A5A3C;
        } else if (blockName.contains("stone_brick")) {
            color = 0xFF7A7A7A;
        }
        // 沙子和砂岩
        else if (blockName.contains("sand") && !blockName.contains("stone")) {
            color = 0xFFDBD3A0;
        } else if (blockName.contains("sandstone")) {
            color = 0xFFC9B181;
        }
        // 羊毛
        else if (blockName.contains("white_wool")) {
            color = 0xFFEEEEEE;
        } else if (blockName.contains("wool")) {
            // 尝试根据颜色名称确定
            if (blockName.contains("red")) color = 0xFFB02E26;
            else if (blockName.contains("orange")) color = 0xFFF9801D;
            else if (blockName.contains("yellow")) color = 0xFFFED83D;
            else if (blockName.contains("green") && !blockName.contains("lime")) color = 0xFF5E7C16;
            else if (blockName.contains("lime")) color = 0xFF80C71F;
            else if (blockName.contains("blue") && !blockName.contains("light")) color = 0xFF3C44AA;
            else if (blockName.contains("light_blue")) color = 0xFF3AB3DA;
            else if (blockName.contains("cyan")) color = 0xFF169C9C;
            else if (blockName.contains("purple")) color = 0xFF8932B8;
            else if (blockName.contains("magenta")) color = 0xFFC74EBD;
            else if (blockName.contains("pink")) color = 0xFFF38BAA;
            else if (blockName.contains("black")) color = 0xFF1D1D21;
            else if (blockName.contains("gray") && !blockName.contains("light")) color = 0xFF474F52;
            else if (blockName.contains("light_gray")) color = 0xFF9D9D97;
            else if (blockName.contains("brown")) color = 0xFF835432;
            else color = 0xFFDDDDDD;
        }
        // 混凝土
        else if (blockName.contains("concrete") && !blockName.contains("powder")) {
            if (blockName.contains("white")) color = 0xFFE0E0E0;
            else if (blockName.contains("red")) color = 0xFF8E2121;
            else if (blockName.contains("black")) color = 0xFF080A0F;
            else color = 0xFFAAAAAA;
        }
        // 金属块
        else if (blockName.contains("iron_block")) {
            color = 0xFFD8D8D8;
        } else if (blockName.contains("gold_block")) {
            color = 0xFFF9E865;
        } else if (blockName.contains("diamond_block")) {
            color = 0xFF5CDBD5;
        } else if (blockName.contains("emerald_block")) {
            color = 0xFF17DD62;
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
