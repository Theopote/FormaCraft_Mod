package com.formacraft.server.generator.path;

import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.model.path.PathSpec;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.profile.StyleProfileRegistry;
import com.formacraft.server.build.GeneratedStructure;
import com.formacraft.server.build.BuildConstraintContext;
import com.formacraft.server.build.PlannedBlock;
import com.formacraft.server.material.PaletteResolver;
import com.formacraft.server.road.RoadPlanner;
import com.formacraft.server.road.RoadAStar;
import com.formacraft.server.road.RoadDecorator;
import com.formacraft.server.road.RoadSurfaceAnalyzer;
import com.formacraft.server.terrain.TerrainAdaptationMode;
import com.formacraft.server.terrain.TerrainAdaptationResolver;
import com.formacraft.server.terrain.TerrainAdaptationSpec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

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

        // Terrain adaptation (PATH): default behavior already follows terrain;
        // when LLM provides terrainAdaptation.mode=DRAPE, we "run it real":
        // - use max_step_height for smoothing
        // - use clearHeight for headroom clearing
        // - use foundationDepth to prevent gaps (columns down)
        TerrainAdaptationSpec ta = TerrainAdaptationResolver.resolve(extra);
        boolean drapeExplicit = TerrainAdaptationResolver.hasExplicit(extra) && ta.mode() == TerrainAdaptationMode.DRAPE;
        int taClear = Math.max(0, Math.min(16, ta.clearHeight()));
        int taMaxStep = Math.max(1, Math.min(8, ta.drapeMaxStep()));
        int taFoundationDepth = Math.max(0, Math.min(16, ta.foundationDepth()));
        boolean allowWater = ta.allowWaterEdit();
        boolean allowLava = ta.allowLavaEdit();

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
        if ((paletteId == null || paletteId.isBlank()) && profile != null && profile.details() != null
                && profile.details().paletteId != null && !profile.details().paletteId.isBlank()) {
            paletteId = profile.details().paletteId.trim();
        }

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

        // Optional explicit lamp overrides (applies to both astar and non-astar decoration)
        BlockState lampOverride = null;
        BlockState postOverride = null;
        if (extra.get("lamp") != null) lampOverride = getState(world, String.valueOf(extra.get("lamp")).trim());
        if (extra.get("lampPost") != null) postOverride = getState(world, String.valueOf(extra.get("lampPost")).trim());

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
                border = PaletteResolver.pick(world, paletteId, "ROAD_BORDER", p0, 0xB0D3L, border);
            }
            BlockState bridgeDeck = Blocks.OAK_PLANKS.getDefaultState();
            BlockState bridgeRail = Blocks.OAK_FENCE.getDefaultState();
            if (paletteId != null) {
                bridgeDeck = PaletteResolver.pick(world, paletteId, "BRIDGE_DECK", p0, 0xBDEC11L, bridgeDeck);
                // keep a back-compat fallback path
                bridgeDeck = PaletteResolver.pick(world, paletteId, "FLOORING", p0, 0xBDEC12L, bridgeDeck);
                bridgeRail = PaletteResolver.pick(world, paletteId, "BRIDGE_RAIL", p0, 0xBA111L, bridgeRail);
                bridgeRail = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", p0, 0xBA112L, bridgeRail);
            }
            RoadPlanner.Config cfg = new RoadPlanner.Config(
                    width,
                    drapeExplicit ? taClear : 2,
                    1,
                    12000,
                    12,
                    2,
                    drapeExplicit ? taMaxStep : 6,
                    road,
                    border,
                    useBorder,
                    bridgeDeck,
                    bridgeRail
            );

            // Use the same pipeline as RoadPlanner, but keep the centerline so we can add lamps/signage.
            RoadSurfaceAnalyzer analyzer = new RoadSurfaceAnalyzer(world, cfg.clearHeight(), cfg.maxStep());
            List<BlockPos> center = RoadAStar.findPath(p0, p1, analyzer, cfg.maxSearch(), cfg.stepPenalty(), cfg.localSlopePenalty(), cfg.bridgePenalty());
            List<PlannedBlock> out = center.isEmpty() ? List.of() : RoadDecorator.decorate(world, center, cfg.width(), cfg.clearHeight(), cfg.road(), cfg.border(), cfg.useBorder(), cfg.bridgeDeck(), cfg.bridgeRail(), paletteId);
            if (!center.isEmpty() && drapeExplicit && taFoundationDepth > 0) {
                BlockState foundation = cfg.border(); // use border as a stable "road foundation" material
                ArrayList<PlannedBlock> merged2 = new ArrayList<>(out.size() + center.size() * width * 2);
                merged2.addAll(out);
                merged2.addAll(RoadDecorator.foundationColumns(world, center, width, taFoundationDepth, foundation, allowWater, allowLava));
                out = merged2;
            }

            if (!center.isEmpty() && (roadLamps || (ornamentProfile != null && !ornamentProfile.isBlank()))) {
                ArrayList<PlannedBlock> merged = new ArrayList<>(out.size() + center.size() * 3);
                merged.addAll(out);
                merged.addAll(decorateSmartRoad(world, center, width, paletteId, roadLamps, lampInterval, ornamentProfile, eavesProfile, lampOverride, postOverride));
                out = merged;
            }
            String description = String.format("Path (width=%d, style=%s)", width, style);
            return new GeneratedStructure(null, origin, description, out);
        }

        // Non-astar: keep the same entrypoints, but route to the same concrete decorator so the "terrain genes" are consistent.
        long seed = seedForPath(path, p0, p1);
        List<BlockPos> pathPointsWorld = switch (style.toLowerCase(Locale.ROOT)) {
            case "curved" -> generateCurvedPath(p0, p1, seed);
            case "stepped" -> generateSteppedPath(p0, p1, world);
            default -> rasterizeLine(p0, p1);
        };

        BlockState roadBase = explicitMaterial != null ? explicitMaterial : Blocks.GRAVEL.getDefaultState();
        BlockState borderBase = Blocks.COBBLESTONE.getDefaultState();

        // Allow explicit overrides via extra
        if (extra.get("borderMaterial") != null) borderBase = getState(world, String.valueOf(extra.get("borderMaterial")).trim());

        // "Run it real" for PATH: generate a terrain-snapped centerline, then decorate consistently.
        List<BlockPos> center = snapAndSmooth(world, pathPointsWorld, drapeExplicit ? taMaxStep : 0);

        int clearHeight = drapeExplicit ? taClear : 2;
        BlockState bridgeDeck = Blocks.OAK_PLANKS.getDefaultState();
        BlockState bridgeRail = Blocks.OAK_FENCE.getDefaultState();
        if (paletteId != null) {
            bridgeDeck = PaletteResolver.pick(world, paletteId, "BRIDGE_DECK", p0, 0xBDEC21L, bridgeDeck);
            bridgeDeck = PaletteResolver.pick(world, paletteId, "FLOORING", p0, 0xBDEC22L, bridgeDeck);
            bridgeRail = PaletteResolver.pick(world, paletteId, "BRIDGE_RAIL", p0, 0xBA121L, bridgeRail);
            bridgeRail = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", p0, 0xBA122L, bridgeRail);
        }

        List<PlannedBlock> out = center.isEmpty()
                ? List.of()
                : RoadDecorator.decorate(world, center, width, clearHeight, roadBase, borderBase, useBorder, bridgeDeck, bridgeRail, paletteId);

        if (!center.isEmpty() && drapeExplicit && taFoundationDepth > 0) {
            BlockState foundation = borderBase;
            if (paletteId != null) {
                foundation = PaletteResolver.pick(world, paletteId, "ROAD_BORDER", p0, 0xF01DL, foundation);
                foundation = PaletteResolver.pick(world, paletteId, "WALL_FOUNDATION", p0, 0xF02DL, foundation);
            }
            ArrayList<PlannedBlock> merged2 = new ArrayList<>(out.size() + center.size() * width * 2);
            merged2.addAll(out);
            merged2.addAll(RoadDecorator.foundationColumns(world, center, width, taFoundationDepth, foundation, allowWater, allowLava));
            out = merged2;
        }

        if (!center.isEmpty() && (roadLamps || (ornamentProfile != null && !ornamentProfile.isBlank()))) {
            ArrayList<PlannedBlock> merged3 = new ArrayList<>(out.size() + center.size() * 3);
            merged3.addAll(out);
            merged3.addAll(decorateSmartRoad(world, center, width, paletteId, roadLamps, lampInterval, ornamentProfile, eavesProfile, lampOverride, postOverride));
            out = merged3;
        }

        String description = String.format("Path (width=%d, style=%s)", width, style);
        return new GeneratedStructure(null, origin, description, out);
    }

    /**
     * Snap a polyline path to terrain surface (MOTION_BLOCKING_NO_LEAVES) and optionally smooth by maxStep.
     * If maxStep<=0, only snaps without smoothing.
     */
    private static List<BlockPos> snapAndSmooth(ServerWorld world, List<BlockPos> pts, int maxStep) {
        if (world == null || pts == null || pts.isEmpty()) return List.of();
        int ms = Math.max(0, Math.min(8, maxStep));
        ArrayList<BlockPos> out = new ArrayList<>(pts.size());
        // 1) snap
        for (BlockPos p : pts) {
            int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
            out.add(new BlockPos(p.getX(), y, p.getZ()));
        }
        if (ms <= 0 || out.size() <= 2) return out;
        // 2) forward clamp
        for (int i = 1; i < out.size(); i++) {
            BlockPos prev = out.get(i - 1);
            BlockPos cur = out.get(i);
            int dy = cur.getY() - prev.getY();
            if (dy > ms) cur = new BlockPos(cur.getX(), prev.getY() + ms, cur.getZ());
            else if (dy < -ms) cur = new BlockPos(cur.getX(), prev.getY() - ms, cur.getZ());
            out.set(i, cur);
        }
        // 3) backward clamp (reduces bias)
        for (int i = out.size() - 2; i >= 0; i--) {
            BlockPos next = out.get(i + 1);
            BlockPos cur = out.get(i);
            int dy = cur.getY() - next.getY();
            if (dy > ms) cur = new BlockPos(cur.getX(), next.getY() + ms, cur.getZ());
            else if (dy < -ms) cur = new BlockPos(cur.getX(), next.getY() - ms, cur.getZ());
            out.set(i, cur);
        }
        return out;
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

    private static List<PlannedBlock> decorateSmartRoad(ServerWorld world,
                                                        List<BlockPos> center,
                                                        int width,
                                                        String paletteId,
                                                        boolean roadLamps,
                                                        int lampInterval,
                                                        String ornamentProfile,
                                                        String eavesProfile,
                                                        BlockState lampOverride,
                                                        BlockState postOverride) {
        if (world == null || center == null || center.size() < 2) return List.of();
        int w = Math.max(1, width);
        int half = w / 2;
        int interval = Math.max(6, lampInterval);
        int bridgeInterval = Math.max(4, interval / 2);

        String op = ornamentProfile != null ? ornamentProfile.toLowerCase(Locale.ROOT) : null;
        String ep = eavesProfile != null ? eavesProfile.toLowerCase(Locale.ROOT) : null;
        boolean neon = ep != null && ep.contains("neon");
        boolean cyber = op != null && (op.contains("cyber") || op.contains("sign"));

        boolean signageEnabled = (op != null && !op.isBlank());

        BlockState lampFallback = (lampOverride != null) ? lampOverride : (neon ? Blocks.SEA_LANTERN.getDefaultState() : Blocks.LANTERN.getDefaultState());
        BlockState postFallback = (postOverride != null) ? postOverride : (cyber ? Blocks.IRON_BARS.getDefaultState() : Blocks.COBBLESTONE_WALL.getDefaultState());

        BlockState signFallback = getBlockState(op);

        ArrayList<PlannedBlock> out = new ArrayList<>(Math.max(200, center.size() / interval * 6));
        int step = 0;

        for (int i = 0; i < center.size(); i++) {
            BlockPos p = center.get(i);
            BlockPos prev = (i > 0) ? center.get(i - 1) : null;
            BlockPos next = (i + 1 < center.size()) ? center.get(i + 1) : null;

            int dx = 0, dz = 0;
            if (next != null) {
                dx = Integer.compare(next.getX() - p.getX(), 0);
                dz = Integer.compare(next.getZ() - p.getZ(), 0);
            } else if (prev != null) {
                dx = Integer.compare(p.getX() - prev.getX(), 0);
                dz = Integer.compare(p.getZ() - prev.getZ(), 0);
            }
            if (dx == 0 && dz == 0) { dx = 1; dz = 0; }
            int rx = -dz;
            int rz = dx;

            boolean bridge = isBridgeUnder(world, p);
            int effInterval = bridge ? bridgeInterval : interval;

            if (step % effInterval == 0) {
                // lamps on one side
                if (roadLamps) {
                    int lx = p.getX() + rx * (half + 2);
                    int lz = p.getZ() + rz * (half + 2);
                    BlockPos postPos = new BlockPos(lx, p.getY(), lz);
                    BlockPos lampPos = postPos.up();

                    BlockState post = postFallback;
                    BlockState lamp = lampFallback;
                    if (paletteId != null) {
                        long saltP = ((long) lx * 31L) ^ ((long) lz * 17L) ^ (step * 23L) ^ 0xC0DEL;
                        // Post is structural first (beam/frame), then detail.
                        post = PaletteResolver.pick(world, paletteId, "STRUCTURAL_BEAM", postPos, saltP ^ 0x51EEL, post);
                        post = PaletteResolver.pick(world, paletteId, "FRAME", postPos, saltP ^ 0xF8A1L, post);
                        post = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", postPos, saltP, post);
                        long saltL = ((long) lx * 31L) ^ ((long) lz * 17L) ^ (step * 19L) ^ 0x11A17L;
                        lamp = PaletteResolver.pick(world, paletteId, "ROAD_LIGHT", lampPos, saltL, lamp);
                    }
                    if (BuildConstraintContext.allow(postPos)) out.add(new PlannedBlock(postPos, post));
                    if (BuildConstraintContext.allow(lampPos)) out.add(new PlannedBlock(lampPos, lamp));
                }

                // signage on the opposite side (reduce collisions with lamps)
                if (signageEnabled && paletteId != null) {
                    int sx = p.getX() - rx * (half + 2);
                    int sz = p.getZ() - rz * (half + 2);
                    BlockPos supportPos = new BlockPos(sx, p.getY() + 1, sz);
                    BlockPos signPos = supportPos.up();

                    BlockState support = Blocks.STONE_BRICKS.getDefaultState();
                    long saltS = ((long) sx * 31L) ^ ((long) sz * 17L) ^ (step * 29L) ^ 0x516E0L;
                    support = PaletteResolver.pick(world, paletteId, "STRUCTURAL_BEAM", supportPos, saltS ^ 0x51EEL, support);
                    support = PaletteResolver.pick(world, paletteId, "FRAME", supportPos, saltS ^ 0xF8A1L, support);
                    support = PaletteResolver.pick(world, paletteId, "ROAD_BORDER", supportPos, saltS, support);

                    BlockState sign = signFallback;
                    long saltG = ((long) sx * 31L) ^ ((long) sz * 17L) ^ (step * 31L) ^ 0x516F1L;
                    sign = PaletteResolver.pick(world, paletteId, "ROAD_SIGNAGE", signPos, saltG, sign);

                    if (BuildConstraintContext.allow(supportPos)) out.add(new PlannedBlock(supportPos, support));
                    if (BuildConstraintContext.allow(signPos)) out.add(new PlannedBlock(signPos, sign));
                }
            }

            step++;
        }

        return out;
    }

    private static BlockState getBlockState(String op) {
        BlockState signFallback = Blocks.RED_WOOL.getDefaultState();
        if (op != null) {
            if (op.contains("cyber") || op.contains("sign")) signFallback = Blocks.GLOWSTONE.getDefaultState();
            else if (op.contains("organic") || op.contains("lantern")) signFallback = Blocks.SHROOMLIGHT.getDefaultState();
            else if (op.contains("steam") || op.contains("pipe")) signFallback = Blocks.COPPER_BLOCK.getDefaultState();
            else if (op.contains("plaque") || op.contains("chinese")) signFallback = Blocks.DARK_OAK_PLANKS.getDefaultState();
            else if (op.contains("banner")) signFallback = Blocks.RED_WOOL.getDefaultState();
        }
        return signFallback;
    }

    private static boolean isBridgeUnder(ServerWorld world, BlockPos p) {
        if (world == null || p == null) return false;
        BlockState below = world.getBlockState(p.down());
        return !below.getFluidState().isEmpty() || below.isAir();
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

