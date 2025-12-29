package com.formacraft.server.generator.path;

import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.model.path.PathSpec;
import com.formacraft.common.skeleton.path.PolylinePathPlan;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.profile.StyleProfileRegistry;
import com.formacraft.server.build.GeneratedStructure;
import com.formacraft.server.build.PlannedBlock;
import com.formacraft.server.road.RoadPlanner;
import com.formacraft.server.skeleton.path.PathRoadInterpreter;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

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
        BlockState explicitMaterial = getState(world, path.getMaterial());

        Map<String, Object> extra = path.getExtra();
        if (extra == null) extra = Map.of();

        // Resolve style profile + palette (best-effort, never overrides explicit block ids)
        BuildingStyle defaultStyle = BuildingStyle.MODERN;
        if (extra.get("buildingStyle") != null) {
            try {
                defaultStyle = BuildingStyle.valueOf(String.valueOf(extra.get("buildingStyle")).trim().toUpperCase(Locale.ROOT));
            } catch (Exception ignored) {}
        }
        StyleProfile profile = StyleProfileRegistry.resolveByExtra(extra, defaultStyle);
        String paletteId = null;
        if (extra.get("paletteId") != null) paletteId = String.valueOf(extra.get("paletteId")).trim();

        var details = profile != null ? profile.details() : null;
        String eavesProfile = details != null ? details.eavesProfile : null;
        String ornamentProfile = details != null ? details.ornamentProfile : null;
        if (extra.get("eavesProfile") != null) eavesProfile = String.valueOf(extra.get("eavesProfile")).trim().toLowerCase(Locale.ROOT);
        if (extra.get("ornamentProfile") != null) ornamentProfile = String.valueOf(extra.get("ornamentProfile")).trim().toLowerCase(Locale.ROOT);

        boolean neon = eavesProfile != null && eavesProfile.toLowerCase(Locale.ROOT).contains("neon");
        boolean cyber = ornamentProfile != null && (ornamentProfile.toLowerCase(Locale.ROOT).contains("cyber") || ornamentProfile.toLowerCase(Locale.ROOT).contains("sign"));

        boolean roadLamps = false;
        if (extra.get("roadLamps") instanceof Boolean b) roadLamps = b;
        else if (extra.get("roadLamps") != null) {
            String s = String.valueOf(extra.get("roadLamps")).trim().toLowerCase(Locale.ROOT);
            if (!s.isEmpty()) roadLamps = s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on");
        } else if (neon || cyber) {
            roadLamps = true;
        }
        int lampInterval = 10;
        if (extra.get("lampInterval") != null) {
            try {
                lampInterval = Integer.parseInt(String.valueOf(extra.get("lampInterval")).trim());
            } catch (Exception ignored) {}
        }
        lampInterval = Math.max(6, lampInterval);

        boolean useBorder = true;
        if (extra.get("useBorder") != null) {
            Object v = extra.get("useBorder");
            if (v instanceof Boolean b) useBorder = b;
            else {
                String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
                if (!s.isEmpty()) useBorder = s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on");
            }
        }

        // 将 from / to 转换成实际世界坐标
        BlockPos p0 = origin.add(from.x, from.y, from.z);
        BlockPos p1 = origin.add(to.x, to.y, to.z);

        // 根据样式选择生成方式
        String style = path.getStyle() != null ? path.getStyle() : "default";

        // Smart road mode (A* + slope-aware), reuses RoadPlanner for better terrain hugging / detours.
        if ("astar".equalsIgnoreCase(style) || "road_planner".equalsIgnoreCase(style) || "smart".equalsIgnoreCase(style)) {
            BlockState road = explicitMaterial != null ? explicitMaterial : Blocks.GRAVEL.getDefaultState();
            BlockState border = Blocks.COBBLESTONE.getDefaultState();
            if (paletteId != null) {
                // Pick a representative border for planner (planner doesn't do per-block semantic picks).
                border = com.formacraft.server.material.PaletteResolver.pick(world, paletteId, "ROAD_BORDER", p0, 0xB0D3L, border);
            }
            RoadPlanner.Config cfg = new RoadPlanner.Config(
                    width,
                    2,
                    1,
                    12000,
                    12,
                    2,
                    6,
                    road,
                    border,
                    useBorder,
                    Blocks.OAK_PLANKS.getDefaultState(),
                    Blocks.OAK_FENCE.getDefaultState()
            );
            List<PlannedBlock> out = RoadPlanner.build(world, p0, p1, cfg);
            String description = String.format("Path (width=%d, style=%s)", width, style);
            return new GeneratedStructure(null, origin, description, out != null ? out : new ArrayList<>());
        }

        // Non-astar: route everything through PolylinePathPlan + PathRoadInterpreter so roads share the same "genes".
        long seed = seedForPath(path, p0, p1);
        List<BlockPos> pathPointsWorld = switch (style.toLowerCase(Locale.ROOT)) {
            case "curved" -> generateCurvedPath(p0, p1, seed);
            case "stepped" -> generateSteppedPath(p0, p1, world);
            default -> rasterizeLine(p0, p1);
        };
        List<BlockPos> rel = new ArrayList<>(pathPointsWorld.size());
        for (BlockPos p : pathPointsWorld) {
            rel.add(new BlockPos(p.getX() - origin.getX(), 0, p.getZ() - origin.getZ()));
        }

        BlockState roadBase = explicitMaterial != null ? explicitMaterial : Blocks.GRAVEL.getDefaultState();
        BlockState borderBase = Blocks.COBBLESTONE.getDefaultState();
        BlockState lamp = neon ? Blocks.SEA_LANTERN.getDefaultState() : Blocks.LANTERN.getDefaultState();
        BlockState post = cyber ? Blocks.IRON_BARS.getDefaultState() : Blocks.COBBLESTONE_WALL.getDefaultState();

        // Allow explicit overrides via extra
        if (extra.get("borderMaterial") != null) borderBase = getState(world, String.valueOf(extra.get("borderMaterial")).trim());
        if (extra.get("lamp") != null) lamp = getState(world, String.valueOf(extra.get("lamp")).trim());
        if (extra.get("lampPost") != null) post = getState(world, String.valueOf(extra.get("lampPost")).trim());

        PolylinePathPlan plan = new PolylinePathPlan(rel, width, true, roadLamps, lampInterval);
        PathRoadInterpreter it = new PathRoadInterpreter(roadBase, borderBase, useBorder, paletteId, lamp, post, ornamentProfile, true, 2);
        List<PlannedBlock> out = it.interpret(plan, origin, world);
        String description = String.format("Path (width=%d, style=%s)", width, style);
        return new GeneratedStructure(null, origin, description, out != null ? out : new ArrayList<>());
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
    private List<BlockPos> generateCurvedPath(BlockPos start, BlockPos end, long seed) {
        // 简化版：使用二次贝塞尔曲线（deterministic）
        List<BlockPos> result = new ArrayList<>();
        
        int x1 = start.getX(), y1 = start.getY(), z1 = start.getZ();
        int x2 = end.getX(), y2 = end.getY(), z2 = end.getZ();
        
        // 控制点（中点偏移）
        int midX = (x1 + x2) / 2;
        int midY = (y1 + y2) / 2;
        int midZ = (z1 + z2) / 2;
        
        // 添加一些确定性偏移使路径更自然（避免每次生成不一致）
        Random rnd = new Random(seed);
        int offsetX = rnd.nextInt(5) - 2;
        int offsetZ = rnd.nextInt(5) - 2;
        
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

    private static long seedForPath(PathSpec path, BlockPos p0, BlockPos p1) {
        long s = 0xC0FFEE42L;
        if (path != null && path.getId() != null) s ^= (long) path.getId().hashCode() * 31L;
        s ^= ((long) p0.getX() * 31L) ^ ((long) p0.getZ() * 17L);
        s ^= ((long) p1.getX() * 131L) ^ ((long) p1.getZ() * 71L);
        s ^= ((long) p0.getY() * 13L) ^ ((long) p1.getY() * 19L);
        return s;
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

