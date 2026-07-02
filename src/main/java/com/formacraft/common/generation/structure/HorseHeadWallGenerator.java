package com.formacraft.common.generation.structure;

import com.formacraft.common.logging.FcaLog;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.server.build.PlannedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;
import java.util.Map;

/**
 * 马头墙生成器（动态参数化生成）
 * 
 * 从 HouseGenerator 中拆分出来的独立工具类，用于生成江南/徽派风格的阶梯状山墙（马头墙）。
 * 支持参数化配置，可根据建筑尺寸自动计算阶梯数量和高度。
 * 
 * 使用方式：
 * 1. 作为工具类：直接调用 generate() 方法
 * 2. 未来可扩展为独立的 StructureGenerator（如果需要独立生成马头墙结构）
 */
public class HorseHeadWallGenerator {

    private static final FcaLog LOG = FcaLog.of("HorseHeadWallGenerator");
    
    /**
     * 生成马头墙（动态参数化）
     * 
     * @param blocks 输出方块列表
     * @param origin 建筑原点
     * @param width 建筑宽度
     * @param depth 建筑深度
     * @param height 建筑主体高度
     * @param roofHeight 屋顶高度
     * @param wall 墙体材质
     * @param trim 装饰材质（用于顶部收边）
     * @param cap 顶盖材质（用于阶梯顶部）
     * @param facing 建筑朝向（用于确定山墙位置）
     */
    public static void generate(List<PlannedBlock> blocks, BlockPos origin,
                                int width, int depth, int height, int roofHeight,
                                BlockState wall, BlockState trim, BlockState cap,
                                Direction facing) {
        if (blocks == null || origin == null || wall == null) return;
        if (width < 3 || depth < 3 || height < 2) return; // 最小尺寸检查
        
        // 根据朝向确定山墙位置（gable ends）
        // 对于双坡屋顶，山墙通常在 depth 方向的两端
        int[] zEnds;
        if (facing == Direction.NORTH || facing == Direction.SOUTH) {
            // 如果朝向是南北，山墙在东西两端（z 方向）
            zEnds = new int[]{-1, depth};
        } else {
            // 如果朝向是东西，山墙在南北两端（x 方向）
            // 注意：这里需要调整坐标计算逻辑，当前实现假设 depth 方向为山墙
            zEnds = new int[]{-1, depth};
        }
        
        int maxTop = height + roofHeight + 2;
        
        // 阶梯步进算法：每 stepWidth 个方块创建一个"马头"阶梯
        int stepWidth = 2; // 可配置参数
        int stepHeight = 2; // 每个阶梯的高度增量
        
        for (int ze : zEnds) {
            for (int x = 0; x < width; x++) {
                // 计算距离边缘的距离（对称）
                int i = Math.min(x, width - 1 - x);
                // 计算阶梯数
                int step = (i / stepWidth);
                // 计算该位置的顶部高度
                int top = Math.min(maxTop, height + step * stepHeight + 2);
                // 确保边缘仍有足够高度
                if (top < height + 2) top = height + 2;
                
                // 填充阶梯状山墙
                for (int y = height; y <= top; y++) {
                    BlockState s = wall;
                    // 顶部收边
                    if (y == top) {
                        s = cap != null ? cap : trim;
                    } else if (y == top - 1) {
                        s = trim;
                    }
                    blocks.add(new PlannedBlock(origin.add(x, y, ze), s));
                }
            }
        }
        
        // 在屋脊中心附近添加"缺口"（notches），避免顶部完全平直
        int mid = width / 2;
        for (int ze : zEnds) {
            blocks.add(new PlannedBlock(origin.add(mid, maxTop, ze), trim));
            if (mid - 1 >= 0) {
                blocks.add(new PlannedBlock(origin.add(mid - 1, maxTop - 1, ze), trim));
            }
            if (mid + 1 < width) {
                blocks.add(new PlannedBlock(origin.add(mid + 1, maxTop - 1, ze), trim));
            }
        }
    }
    
    /**
     * 生成马头墙（简化版本，使用默认朝向 SOUTH）
     * 
     * 保持与 HouseGenerator 中原方法的兼容性
     */
    public static void generate(List<PlannedBlock> blocks, BlockPos origin,
                                int width, int depth, int height, int roofHeight,
                                BlockState wall, BlockState trim, BlockState cap) {
        generate(blocks, origin, width, depth, height, roofHeight, wall, trim, cap, Direction.SOUTH);
    }
    
    /**
     * 生成马头墙（参数化版本）
     * 
     * @param blocks 输出方块列表
     * @param origin 建筑原点
     * @param width 建筑宽度
     * @param depth 建筑深度
     * @param height 建筑主体高度
     * @param roofHeight 屋顶高度
     * @param wall 墙体材质
     * @param trim 装饰材质
     * @param cap 顶盖材质
     * @param facing 建筑朝向
     * @param stepWidth 阶梯宽度（每 stepWidth 个方块创建一个阶梯）
     * @param stepHeight 阶梯高度增量（每个阶梯的高度增加量）
     */
    public static void generate(List<PlannedBlock> blocks, BlockPos origin,
                                int width, int depth, int height, int roofHeight,
                                BlockState wall, BlockState trim, BlockState cap,
                                Direction facing, int stepWidth, int stepHeight) {
        if (blocks == null || origin == null || wall == null) return;
        if (width < 3 || depth < 3 || height < 2) return;
        if (stepWidth < 1) stepWidth = 2;
        if (stepHeight < 1) stepHeight = 2;
        
        int[] zEnds;
        if (facing == Direction.NORTH || facing == Direction.SOUTH) {
            zEnds = new int[]{-1, depth};
        } else {
            zEnds = new int[]{-1, depth};
        }
        
        int maxTop = height + roofHeight + 2;
        
        for (int ze : zEnds) {
            for (int x = 0; x < width; x++) {
                int i = Math.min(x, width - 1 - x);
                int step = (i / stepWidth);
                int top = Math.min(maxTop, height + step * stepHeight + 2);
                if (top < height + 2) top = height + 2;
                
                for (int y = height; y <= top; y++) {
                    BlockState s = wall;
                    if (y == top) {
                        s = cap != null ? cap : trim;
                    } else if (y == top - 1) {
                        s = trim;
                    }
                    blocks.add(new PlannedBlock(origin.add(x, y, ze), s));
                }
            }
        }
        
        // 添加缺口
        int mid = width / 2;
        for (int ze : zEnds) {
            blocks.add(new PlannedBlock(origin.add(mid, maxTop, ze), trim));
            if (mid - 1 >= 0) {
                blocks.add(new PlannedBlock(origin.add(mid - 1, maxTop - 1, ze), trim));
            }
            if (mid + 1 < width) {
                blocks.add(new PlannedBlock(origin.add(mid + 1, maxTop - 1, ze), trim));
            }
        }
    }
    
    /**
     * 检查是否为徽派风格
     * 
     * @param spec 建筑规格
     * @param paletteId 调色板 ID
     * @return 如果是徽派风格返回 true
     */
    public static boolean isHuizhouStyle(BuildingSpec spec, String paletteId) {
        try {
            if (spec != null && spec.getExtra() != null) {
                Object spid = spec.getExtra().get("styleProfileId");
                if (spid != null) {
                    String s = String.valueOf(spid).trim();
                    if (s.equals("Chinese_Vernacular_Huizhou")) return true;
                    // 也可以检查江南风格
                    if (s.contains("Jiangnan") || s.contains("Huizhou")) return true;
                }
            }
        } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }
        if (paletteId == null) return false;
        String pid = paletteId.trim();
        return pid.equalsIgnoreCase("PALETTE_HUIZHOU_WHITE_BLACK_A") 
            || pid.equalsIgnoreCase("PALETTE_JIANGNAN_WATERTOWN_A");
    }
}

