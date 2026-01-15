package com.formacraft.client.component;

import com.formacraft.common.component.ComponentDefinition;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

/**
 * 构件缩略图生成器（客户端）：
 * - 使用增强的等轴测投影渲染
 * - 包含环境光遮蔽（AO）、边缘轮廓和深度排序
 * - 生成清晰、有立体感的缩略图
 */
public final class ComponentThumbnailGenerator {
    private ComponentThumbnailGenerator() {}

    private static final int THUMB_SIZE = 256; // 提高分辨率以获得清晰的缩略图
    private static final Map<Block, Integer> COLOR_CACHE = new HashMap<>();

    /**
     * 体素数据（用于深度排序）
     */
    private static class Voxel {
        int x, y, z;
        BlockState state;
        int color;
        
        Voxel(int x, int y, int z, BlockState state, int color) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.state = state;
            this.color = color;
        }
        
        // 深度值（用于排序）
        double depth() {
            return -y + (x + z) * 0.5;
        }
    }

    /**
     * 从构件定义生成缩略图
     */
    @Nullable
    public static BufferedImage generateThumbnail(ComponentDefinition def) {
        if (def == null || def.blocks == null || def.blocks.isEmpty()) {
            return null;
        }

        // 解析方块数据，找到边界
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        List<Voxel> voxels = new ArrayList<>();
        Map<String, BlockState> blockMap = new HashMap<>();
        
        for (var entry : def.blocks) {
            if (entry == null) continue;
            int x = entry.dx;
            int y = entry.dy;
            int z = entry.dz;
            
            BlockState state = parseBlockState(entry.block);
            if (state == null || state.isAir()) continue;
            
            int color = getBlockColor(state);
            if ((color & 0xFF000000) == 0) continue;
            
            voxels.add(new Voxel(x, y, z, state, color));
            blockMap.put(x + "," + y + "," + z, state);
            
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }

        if (voxels.isEmpty()) {
            return null;
        }

        // 按深度排序（画家算法）
        voxels.sort(Comparator.comparingDouble(Voxel::depth));

        // 计算尺寸
        int sizeX = maxX - minX + 1;
        int sizeY = maxY - minY + 1;
        int sizeZ = maxZ - minZ + 1;

        // 创建图像（使用抗锯齿）
        BufferedImage img = new BufferedImage(THUMB_SIZE, THUMB_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = img.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        
        // 透明背景
        g2d.setComposite(AlphaComposite.Clear);
        g2d.fillRect(0, 0, THUMB_SIZE, THUMB_SIZE);
        g2d.setComposite(AlphaComposite.SrcOver);

        // 计算缩放和偏移
        double maxDim = Math.max(sizeX + sizeZ, sizeY + (sizeX + sizeZ) / 2.0);
        double scale = (THUMB_SIZE * 0.75) / maxDim; // 使用 0.75 平衡填充度和边距
        
        // 方块尺寸
        int blockPixelSize = Math.max(5, (int) Math.ceil(scale * 1.3));
        
        int centerX = THUMB_SIZE / 2;
        int centerY = THUMB_SIZE / 2;

        // 渲染每个体素
        for (Voxel voxel : voxels) {
            int rx = voxel.x - minX;
            int ry = voxel.y - minY;
            int rz = voxel.z - minZ;

            // 等轴测投影
            double screenX = (rx - rz) * scale;
            double screenY = ry * scale - (rx + rz) * scale / 2.0;

            int px = (int) Math.round(centerX + screenX);
            int py = (int) Math.round(centerY - screenY);

            // 计算环境光遮蔽（AO）
            float ao = calculateAO(blockMap, voxel.x, voxel.y, voxel.z);
            
            // 绘制方块的三个可见面
            drawIsometricBlock(g2d, px, py, blockPixelSize, voxel.color, ao, ry, sizeY);
        }

        g2d.dispose();
        return img;
    }

    /**
     * 绘制等轴测方块（三个面）- 紧密连接，无间隙
     */
    private static void drawIsometricBlock(Graphics2D g2d, int x, int y, int size, 
                                          int baseColor, float ao, int height, int maxHeight) {
        // 顶面（最亮）
        float topBrightness = 1.0f;
        // 左面（中等）
        float leftBrightness = 0.65f;
        // 右面（较暗）
        float rightBrightness = 0.8f;
        
        // 高度渐变
        float heightFactor = 0.75f + 0.25f * (height / (float) Math.max(1, maxHeight));
        
        // 应用环境光遮蔽
        topBrightness *= ao * heightFactor;
        leftBrightness *= ao * heightFactor;
        rightBrightness *= ao * heightFactor;

        // 提取颜色通道
        int alpha = (baseColor >> 24) & 0xFF;
        int red = (baseColor >> 16) & 0xFF;
        int green = (baseColor >> 8) & 0xFF;
        int blue = baseColor & 0xFF;

        // 等轴测坐标（标准计算）
        int halfSize = size / 2;
        int quarterSize = size / 4;
        
        // 顶面（菱形）
        int[] topX = {x, x + halfSize, x, x - halfSize};
        int[] topY = {y, y + quarterSize, y + halfSize, y + quarterSize};
        g2d.setColor(applyBrightness(red, green, blue, alpha, topBrightness));
        g2d.fillPolygon(topX, topY, 4);
        
        // 绘制顶面细节（简单的几个高亮点）
        if (size >= 6) {
            g2d.setColor(applyBrightness(red, green, blue, alpha, topBrightness * 1.1f));
            for (int i = 0; i < 2; i++) {
                int tx = x - size/4 + i * size/2;
                int ty = y + size/4;
                g2d.fillRect(tx, ty, 1, 1);
            }
        }

        // 左面（平行四边形）
        int[] leftX = {x - halfSize, x, x, x - halfSize};
        int[] leftY = {y + quarterSize, y + halfSize, y + size, y + size*3/4};
        g2d.setColor(applyBrightness(red, green, blue, alpha, leftBrightness));
        g2d.fillPolygon(leftX, leftY, 4);

        // 右面（平行四边形）
        int[] rightX = {x, x + halfSize, x + halfSize, x};
        int[] rightY = {y + halfSize, y + quarterSize, y + size*3/4, y + size};
        g2d.setColor(applyBrightness(red, green, blue, alpha, rightBrightness));
        g2d.fillPolygon(rightX, rightY, 4);
    }

    /**
     * 计算环境光遮蔽（AO）
     */
    private static float calculateAO(Map<String, BlockState> blockMap, int x, int y, int z) {
        int neighbors = 0;
        int total = 0;
        
        // 检查周围 6 个方向
        int[][] directions = {
            {1, 0, 0}, {-1, 0, 0},  // X
            {0, 1, 0}, {0, -1, 0},  // Y
            {0, 0, 1}, {0, 0, -1}   // Z
        };
        
        for (int[] dir : directions) {
            total++;
            String key = (x + dir[0]) + "," + (y + dir[1]) + "," + (z + dir[2]);
            if (blockMap.containsKey(key)) {
                neighbors++;
            }
        }
        
        // 被遮挡越多，越暗
        float occlusion = 1.0f - (neighbors / (float) total) * 0.3f;
        return Math.max(0.5f, occlusion);
    }

    /**
     * 应用亮度到颜色
     */
    private static Color applyBrightness(int r, int g, int b, int a, float brightness) {
        brightness = Math.max(0, Math.min(1, brightness));
        r = (int) (r * brightness);
        g = (int) (g * brightness);
        b = (int) (b * brightness);
        r = Math.min(255, Math.max(0, r));
        g = Math.min(255, Math.max(0, g));
        b = Math.min(255, Math.max(0, b));
        return new Color(r, g, b, a);
    }

    /**
     * 解析方块状态字符串
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
            if (block == Blocks.AIR) return null;
            return block.getDefaultState();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 获取方块颜色（增强版）
     */
    private static int getBlockColor(BlockState state) {
        Block block = state.getBlock();
        
        if (COLOR_CACHE.containsKey(block)) {
            return COLOR_CACHE.get(block);
        }

        int color = 0xFFAAAAAA;
        String blockName = Registries.BLOCK.getId(block).getPath().toLowerCase();
        
        // 石头类
        if (blockName.contains("stone") && !blockName.contains("sandstone") && !blockName.contains("brick")) {
            color = 0xFF7F7F7F;
        } else if (blockName.contains("cobblestone") || blockName.contains("cobble")) {
            color = 0xFF7F7F7F;
        } else if (blockName.contains("andesite")) {
            color = 0xFF838383;
        } else if (blockName.contains("diorite")) {
            color = 0xFFC4C4C4;
        } else if (blockName.contains("granite")) {
            color = 0xFF9F5D56;
        } else if (blockName.contains("deepslate") && !blockName.contains("brick")) {
            color = 0xFF4F4F4F;
        } else if (blockName.contains("blackstone") && !blockName.contains("brick")) {
            color = 0xFF2A2A2A;
        }
        // 泥土和草
        else if (blockName.contains("dirt") || blockName.contains("soil")) {
            color = 0xFF8B5A3C;
        } else if (blockName.contains("grass_block")) {
            color = 0xFF5C8A3E;
        } else if (blockName.contains("podzol")) {
            color = 0xFF5E4230;
        } else if (blockName.contains("mycelium")) {
            color = 0xFF6F6176;
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
        } else if (blockName.contains("mangrove") && blockName.contains("planks")) {
            color = 0xFF733B33;
        } else if (blockName.contains("cherry") && blockName.contains("planks")) {
            color = 0xFFE4ACA3;
        } else if (blockName.contains("bamboo") && blockName.contains("planks")) {
            color = 0xFFC5A955;
        } else if (blockName.contains("crimson") && blockName.contains("planks")) {
            color = 0xFF7E3A56;
        } else if (blockName.contains("warped") && blockName.contains("planks")) {
            color = 0xFF398382;
        }
        // 原木
        else if (blockName.contains("log") || blockName.contains("stem")) {
            if (blockName.contains("oak")) color = 0xFF6F4E37;
            else if (blockName.contains("spruce")) color = 0xFF4A352A;
            else if (blockName.contains("birch")) color = 0xFFD7D7D7;
            else if (blockName.contains("jungle")) color = 0xFF6F4E37;
            else if (blockName.contains("acacia")) color = 0xFF6A6A6A;
            else if (blockName.contains("dark_oak")) color = 0xFF3E2912;
            else if (blockName.contains("crimson")) color = 0xFF4A2338;
            else if (blockName.contains("warped")) color = 0xFF2C3F3F;
            else color = 0xFF6F4E37;
        }
        // 叶子
        else if (blockName.contains("leaves")) {
            if (blockName.contains("azalea")) color = 0xFF4F7849;
            else color = 0xFF5C8A3E;
        }
        // 玻璃
        else if (blockName.contains("glass")) {
            color = 0xBBDDDDDD; // 较透明
        }
        // 砖类
        else if (blockName.equals("bricks")) {
            color = 0xFF9A5A3C;
        } else if (blockName.contains("stone_brick")) {
            color = 0xFF7A7A7A;
        } else if (blockName.contains("nether_brick") && !blockName.contains("red")) {
            color = 0xFF2C1418;
        } else if (blockName.contains("red_nether_brick")) {
            color = 0xFF441316;
        } else if (blockName.contains("end_stone_brick")) {
            color = 0xFFE0E8B0;
        }
        // 沙子和砂岩
        else if (blockName.contains("sand") && !blockName.contains("stone")) {
            if (blockName.contains("red")) color = 0xFFBF6D3C;
            else color = 0xFFDBD3A0;
        } else if (blockName.contains("sandstone")) {
            if (blockName.contains("red")) color = 0xFFBF6D3C;
            else color = 0xFFC9B181;
        }
        // 羊毛
        else if (blockName.contains("wool")) {
            if (blockName.contains("white")) color = 0xFFEEEEEE;
            else if (blockName.contains("orange")) color = 0xFFF9801D;
            else if (blockName.contains("magenta")) color = 0xFFC74EBD;
            else if (blockName.contains("light_blue")) color = 0xFF3AB3DA;
            else if (blockName.contains("yellow")) color = 0xFFFED83D;
            else if (blockName.contains("lime")) color = 0xFF80C71F;
            else if (blockName.contains("pink")) color = 0xFFF38BAA;
            else if (blockName.contains("gray") && !blockName.contains("light")) color = 0xFF474F52;
            else if (blockName.contains("light_gray")) color = 0xFF9D9D97;
            else if (blockName.contains("cyan")) color = 0xFF169C9C;
            else if (blockName.contains("purple")) color = 0xFF8932B8;
            else if (blockName.contains("blue") && !blockName.contains("light")) color = 0xFF3C44AA;
            else if (blockName.contains("brown")) color = 0xFF835432;
            else if (blockName.contains("green") && !blockName.contains("lime")) color = 0xFF5E7C16;
            else if (blockName.contains("red")) color = 0xFFB02E26;
            else if (blockName.contains("black")) color = 0xFF1D1D21;
            else color = 0xFFDDDDDD;
        }
        // 混凝土
        else if (blockName.contains("concrete") && !blockName.contains("powder")) {
            if (blockName.contains("white")) color = 0xFFE0E0E0;
            else if (blockName.contains("orange")) color = 0xFFE06100;
            else if (blockName.contains("magenta")) color = 0xFF922AAD;
            else if (blockName.contains("light_blue")) color = 0xFF2389C6;
            else if (blockName.contains("yellow")) color = 0xFFF0AF15;
            else if (blockName.contains("lime")) color = 0xFF5EA817;
            else if (blockName.contains("pink")) color = 0xFFD5698A;
            else if (blockName.contains("gray") && !blockName.contains("light")) color = 0xFF373A3E;
            else if (blockName.contains("light_gray")) color = 0xFF818388;
            else if (blockName.contains("cyan")) color = 0xFF157788;
            else if (blockName.contains("purple")) color = 0xFF64209C;
            else if (blockName.contains("blue") && !blockName.contains("light")) color = 0xFF2C2E8F;
            else if (blockName.contains("brown")) color = 0xFF603B1F;
            else if (blockName.contains("green") && !blockName.contains("lime")) color = 0xFF495B23;
            else if (blockName.contains("red")) color = 0xFF8E2121;
            else if (blockName.contains("black")) color = 0xFF080A0F;
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
        } else if (blockName.contains("netherite_block")) {
            color = 0xFF443A3B;
        } else if (blockName.contains("copper")) {
            if (blockName.contains("oxidized")) color = 0xFF52A596;
            else if (blockName.contains("weathered")) color = 0xFF6A9A87;
            else if (blockName.contains("exposed")) color = 0xFF9B7F6F;
            else color = 0xFFB77860;
        }
        // 矿石
        else if (blockName.contains("coal_ore")) {
            color = 0xFF3D3D3D;
        } else if (blockName.contains("iron_ore")) {
            color = 0xFFA87C69;
        } else if (blockName.contains("gold_ore")) {
            color = 0xFFE6CD67;
        } else if (blockName.contains("diamond_ore")) {
            color = 0xFF77BFCA;
        } else if (blockName.contains("emerald_ore")) {
            color = 0xFF47B556;
        }
        // 其他常见方块
        else if (blockName.contains("quartz")) {
            color = 0xFFECE6DE;
        } else if (blockName.contains("prismarine")) {
            if (blockName.contains("dark")) color = 0xFF325641;
            else color = 0xFF5C957A;
        } else if (blockName.contains("obsidian")) {
            color = 0xFF0F0D1A;
        } else if (blockName.contains("terracotta") || blockName.contains("clay")) {
            color = 0xFF9E5B40;
        }

        COLOR_CACHE.put(block, color);
        return color;
    }
}
