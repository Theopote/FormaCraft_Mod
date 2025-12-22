package com.formacraft.server.generator.path;

import com.formacraft.common.model.path.PathSpec;
import com.formacraft.server.build.GeneratedStructure;
import com.formacraft.server.build.PlannedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 路径生成器
 * 生成连接建筑之间的道路
 * 支持直线、折线、平滑曲线（未来扩展）
 */
public class PathGenerator {

    /**
     * 生成路径
     * @param path 路径规格
     * @param origin 复合结构的原点
     * @param world 服务器世界
     * @return 生成的路径结构
     */
    public GeneratedStructure generate(PathSpec path, BlockPos origin, ServerWorld world) {
        if (path == null || path.getFrom() == null || path.getTo() == null) {
            return new GeneratedStructure(null, origin, "Empty Path", new ArrayList<>());
        }

        PathSpec.Point from = path.getFrom();
        PathSpec.Point to = path.getTo();

        int width = Math.max(1, path.getWidth());
        BlockState material = getState(world, path.getMaterial());

        List<PlannedBlock> blocks = new ArrayList<>();

        // 将 from / to 转换成实际世界坐标
        BlockPos p0 = origin.add(from.x, from.y, from.z);
        BlockPos p1 = origin.add(to.x, to.y, to.z);

        // 根据样式选择生成方式
        String style = path.getStyle() != null ? path.getStyle() : "default";
        
        List<BlockPos> pathPoints = switch (style.toLowerCase()) {
            case "curved" -> generateCurvedPath(p0, p1);
            case "stepped" -> generateSteppedPath(p0, p1, world);
            default -> rasterizeLine(p0, p1);
        };

        // 生成路径的主逻辑
        for (BlockPos p : pathPoints) {
            // 道路宽度（沿 XZ 扩张）
            for (int dx = -width / 2; dx <= width / 2; dx++) {
                for (int dz = -width / 2; dz <= width / 2; dz++) {
                    BlockPos ground = findGround(world, p.add(dx, 0, dz));

                    // 放置道路方块（在地面上方 1 格）
                    BlockPos place = ground.up();
                    blocks.add(new PlannedBlock(place, material));

                    // 清空头顶（预留行走空间）
                    blocks.add(new PlannedBlock(place.up(), Blocks.AIR.getDefaultState()));
                    blocks.add(new PlannedBlock(place.up(2), Blocks.AIR.getDefaultState()));

                    // 可选：边界装饰（未来扩展）
                    if (path.getStyle() != null && path.getStyle().contains("decorated")) {
                        // 在道路边缘放置装饰
                        if (Math.abs(dx) == width / 2 || Math.abs(dz) == width / 2) {
                            BlockState decoration = Blocks.COBBLESTONE.getDefaultState();
                            blocks.add(new PlannedBlock(place, decoration));
                        }
                    }
                }
            }
        }

        String description = String.format("Path (width=%d, style=%s)", width, style);
        return new GeneratedStructure(null, origin, description, blocks);
    }

    /**
     * 直线离散化（3D Bresenham 简化版）
     */
    private List<BlockPos> rasterizeLine(BlockPos start, BlockPos end) {
        List<BlockPos> result = new ArrayList<>();

        int x1 = start.getX();
        int y1 = start.getY();
        int z1 = start.getZ();
        int x2 = end.getX();
        int y2 = end.getY();
        int z2 = end.getZ();

        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        int dz = Math.abs(z2 - z1);

        int sx = x1 < x2 ? 1 : -1;
        int sy = y1 < y2 ? 1 : -1;
        int sz = z1 < z2 ? 1 : -1;

        int err1 = dx - dy;
        int err2 = dx - dz;

        int x = x1, y = y1, z = z1;

        while (true) {
            result.add(new BlockPos(x, y, z));

            if (x == x2 && y == y2 && z == z2) break;

            int e1 = 2 * err1;
            int e2 = 2 * err2;

            if (e1 > -dy) {
                err1 -= dy;
                x += sx;
            }
            if (e1 < dx) {
                err1 += dx;
                y += sy;
            }
            if (e2 > -dz) {
                err2 -= dz;
                z += sz;
            }
            if (e2 < dx) {
                err2 += dx;
            }
        }

        return result;
    }

    /**
     * 生成平滑曲线路径（样条曲线，简化版）
     * 未来可以升级为更复杂的样条算法
     */
    private List<BlockPos> generateCurvedPath(BlockPos start, BlockPos end) {
        // 简化版：使用二次贝塞尔曲线
        List<BlockPos> result = new ArrayList<>();
        
        int x1 = start.getX(), y1 = start.getY(), z1 = start.getZ();
        int x2 = end.getX(), y2 = end.getY(), z2 = end.getZ();
        
        // 控制点（中点偏移）
        int midX = (x1 + x2) / 2;
        int midY = (y1 + y2) / 2;
        int midZ = (z1 + z2) / 2;
        
        // 添加一些随机偏移使路径更自然
        int offsetX = (int) (Math.random() * 5 - 2);
        int offsetZ = (int) (Math.random() * 5 - 2);
        
        int cx = midX + offsetX;
        int cz = midZ + offsetZ;
        
        // 二次贝塞尔曲线采样
        int steps = (int) Math.max(10, Math.sqrt(
            (x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1) + (z2 - z1) * (z2 - z1)
        ));
        
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double u = 1 - t;
            
            // 二次贝塞尔曲线公式：B(t) = (1-t)²P₀ + 2(1-t)tP₁ + t²P₂
            int x = (int) (u * u * x1 + 2 * u * t * cx + t * t * x2);
            int y = (int) (u * u * y1 + 2 * u * t * midY + t * t * y2);
            int z = (int) (u * u * z1 + 2 * u * t * cz + t * t * z2);
            
            result.add(new BlockPos(x, y, z));
        }
        
        return result;
    }

    /**
     * 生成阶梯化路径（处理高度差）
     * 自动在高度差处生成楼梯或缓坡
     */
    private List<BlockPos> generateSteppedPath(BlockPos start, BlockPos end, ServerWorld world) {
        List<BlockPos> result = new ArrayList<>();
        
        // 先获取直线路径
        List<BlockPos> linePath = rasterizeLine(start, end);
        
        if (linePath.isEmpty()) {
            return result;
        }
        
        // 获取起点和终点的实际地面高度
        BlockPos startGround = findGround(world, start);
        BlockPos endGround = findGround(world, end);

        int startY = startGround.getY();
        int endY = endGround.getY();
        int totalHeightDiff = endY - startY;
        int pathLength = linePath.size();
        
        // 如果高度差较大（> 3），使用缓坡策略
        if (Math.abs(totalHeightDiff) > 3 && pathLength > 1) {
            // 计算每步的 Y 增量
            double yStep = (double) totalHeightDiff / (pathLength - 1);
            
            for (int i = 0; i < linePath.size(); i++) {
                BlockPos current = linePath.get(i);
                int targetY = startY + (int) Math.round(yStep * i);
                
                // 确保 Y 在合理范围内
                BlockPos ground = findGround(world, current);
                // 如果目标高度与地面高度差距太大，使用地面高度
                if (Math.abs(targetY - ground.getY()) > 5) {
                    targetY = ground.getY();
                }

                result.add(new BlockPos(current.getX(), targetY, current.getZ()));
            }
        } else {
            // 高度差较小，使用阶梯策略
            for (int i = 0; i < linePath.size(); i++) {
                BlockPos current = linePath.get(i);
                BlockPos ground = findGround(world, current);

                // 检查与前一个点的高度差
                if (i > 0 && !result.isEmpty()) {
                    BlockPos prev = result.getLast();
                    int heightDiff = ground.getY() - prev.getY();

                    // 如果高度差大于 1，添加中间台阶
                    if (Math.abs(heightDiff) > 1) {
                        int steps = Math.abs(heightDiff);
                        int stepDir = heightDiff > 0 ? 1 : -1;

                        for (int s = 1; s < steps; s++) {
                            int stepY = prev.getY() + s * stepDir;
                            result.add(new BlockPos(current.getX(), stepY, current.getZ()));
                        }
                    }
                }

                result.add(new BlockPos(current.getX(), ground.getY(), current.getZ()));
            }
        }
        
        return result;
    }

    /**
     * 找道路应该落在什么高度（向下搜索地形）
     */
    private BlockPos findGround(ServerWorld world, BlockPos pos) {
        int y = pos.getY();
        int bottomY = world.getBottomY();
        
        // 如果起始 Y 太高，向下搜索
        while (y > bottomY) {
            BlockPos check = new BlockPos(pos.getX(), y, pos.getZ());
            BlockState state = world.getBlockState(check);
            
            if (!state.isAir() && state.getFluidState().isEmpty()) {
                return check;
            }
            
            y--;
        }
        
        // 如果找不到，返回原始位置
        return new BlockPos(pos.getX(), bottomY, pos.getZ());
    }

    /**
     * 将字符串 blockId 转为 BlockState
     */
    private BlockState getState(ServerWorld world, String id) {
        if (id == null || id.isEmpty()) {
            return Blocks.GRAVEL.getDefaultState();
        }

        try {
            Identifier identifier;
            if (id.contains(":")) {
                identifier = Identifier.of(id);
            } else {
                identifier = Identifier.of("minecraft", id);
            }

            Block block = Registries.BLOCK.get(identifier);
            return block.getDefaultState();

        } catch (Exception e) {
            return resolveBlockFallback(id);
        }
    }

    /**
     * 回退方案：通过字符串匹配解析常用方块
     */
    private BlockState resolveBlockFallback(String material) {
        if (material == null) return Blocks.GRAVEL.getDefaultState();
        
        String lower = material.toLowerCase();
        
        if (lower.contains("gravel")) {
            return Blocks.GRAVEL.getDefaultState();
        }
        if (lower.contains("stone") && lower.contains("brick")) {
            return Blocks.STONE_BRICKS.getDefaultState();
        }
        if (lower.contains("cobble")) {
            return Blocks.COBBLESTONE.getDefaultState();
        }
        if (lower.contains("dirt")) {
            return Blocks.DIRT.getDefaultState();
        }
        if (lower.contains("grass")) {
            return Blocks.GRASS_BLOCK.getDefaultState();
        }
        
        return Blocks.GRAVEL.getDefaultState();
    }
}

