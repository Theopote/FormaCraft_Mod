package com.formacraft.server.generator;

import com.formacraft.common.model.build.*;
import com.formacraft.common.skeleton.SkeletonParams;
import com.formacraft.common.skeleton.compound.GeneratorBackedPlan;
import com.formacraft.common.skeleton.grid.GridPlan;
import com.formacraft.server.build.GeneratedStructure;
import com.formacraft.server.build.PlannedBlock;
import com.formacraft.common.skeleton.path.PolylinePathPlan;
import com.formacraft.server.skeleton.compound.PlanDispatcher;
import com.formacraft.server.skeleton.grid.GridInterpreter;
import com.formacraft.server.skeleton.grid.GridSkeleton;
import com.formacraft.server.skeleton.path.PathRoadInterpreter;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.profile.StyleProfileRegistry;
import com.formacraft.server.cluster.TerrainFields;
import com.formacraft.server.cluster.layout.BuildArea;
import com.formacraft.server.cluster.layout.BuildingPlacement;
import com.formacraft.server.cluster.layout.BuildingUnit;
import com.formacraft.server.cluster.layout.Candidate;
import com.formacraft.server.cluster.layout.CandidateGenerator;
import com.formacraft.server.cluster.layout.ClusterLayoutConfig;
import com.formacraft.server.cluster.layout.PlacementSolver;
import com.formacraft.server.build.BuildReportContext;
import com.formacraft.server.terrain.TerrainFit;
import com.formacraft.server.terrain.TerrainPolicy;
import com.formacraft.server.terrain.TerrainPolicyResolver;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * OfficeDistrictGenerator (v1):
 * GRID/CLUSTER topology example: repeated office blocks on a grid.
 *
 * Triggered by template routing: spec.extra.template == "office_district".
 */
public class OfficeDistrictGenerator implements StructureGenerator {
    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        Map<String, Object> extra = spec != null ? spec.getExtra() : null;

        int rows = getInt(extra, "rows", 3);
        int cols = getInt(extra, "cols", 4);
        int spacing = getInt(extra, "spacing", 18);

        int blockW = getInt(extra, "blockWidth", 11);
        int blockD = getInt(extra, "blockDepth", 11);
        int blockH = getInt(extra, "blockHeight", 22);

        // build module spec that routes to OfficeBlockGenerator via template
        BuildingSpec module = new BuildingSpec();
        module.setType(BuildingType.HOUSE);
        module.setStyle(BuildingStyle.MODERN);
        module.setFootprint(new Footprint(blockW, blockD));
        module.setHeight(blockH);
        module.setFloors(Math.max(1, blockH / 6));

        Materials m = new Materials();
        m.setWall("minecraft:light_gray_concrete");
        m.setWindow("minecraft:glass_pane");
        m.setFloor("minecraft:smooth_stone");
        m.setRoof("minecraft:smooth_stone");
        module.setMaterials(m);

        Features f = new Features();
        f.setHasWindows(true);
        f.setHasStairs(false);
        f.setHasDoor(true);
        f.setHasRoof(true);
        f.setHasRoofDecoration(false);
        f.setFloorCount(Math.max(1, blockH / 6));
        module.setFeatures(f);

        StyleOptions so = new StyleOptions();
        so.setRoofType("flat");
        so.setWindowRatio(0.6);
        module.setStyleOptions(so);

        module.setExtra(Map.of("template", "office_block"));

        TerrainPolicy terrainPolicy = TerrainPolicyResolver.resolve(extra);
        int padDepth = clamp(getInt(extra, "terrainPadDepth", 2), 0, 6);
        int clearHeight = clamp(getInt(extra, "terrainClearHeight", 6), 0, 16);
        int terrainBudgetBlocks = clamp(getInt(extra, "terrainBudgetBlocks", 8000), 0, 200000);

        GridPlan grid = new GridPlan(new GeneratorBackedPlan(module));
        boolean clusterRequested = ClusterLayoutConfig.isClusterMode(extra != null ? extra.get("layoutMode") : null);
        record FootingParams(int padDepth, int clearHeight, boolean stilt) {}
        Map<Long, FootingParams> footingByRelXZ = new HashMap<>();
        boolean clusterEnabled = false;
        if (clusterRequested) {
            int count = Math.max(1, rows * cols);
            int boxHalfX = Math.max(12, ((cols - 1) * spacing) / 2);
            int boxHalfZ = Math.max(12, ((rows - 1) * spacing) / 2);
            ClusterLayoutConfig cfg = ClusterLayoutConfig.fromExtra(extra, boxHalfX, boxHalfZ, count, spacing);
            BuildArea area = new BuildArea(cfg.halfX, cfg.halfZ);
            TerrainFields fields = TerrainFields.sample(world, origin, boxHalfX, boxHalfZ, 2);

            // Units: same template repeated, importance equal for now (future: primary/secondary).
            BuildingUnit unit = new BuildingUnit("office_block", blockW, blockD, blockH, 5);
            List<BuildingUnit> units = new ArrayList<>(count);
            for (int i = 0; i < count; i++) units.add(unit);

            List<Candidate> cands = CandidateGenerator.generate(unit, area, fields, world, origin, cfg.samples, cfg.maxRange, cfg.maxFlattenCost);
            java.util.Map<String, List<Candidate>> byId = new java.util.HashMap<>();
            byId.put(unit.id, cands);

            List<BuildingPlacement> placed = PlacementSolver.solve(units, byId, cfg.minGap, cfg.maxBacktrack);

            // If placement fails (too constrained), fall back to grid skeleton.
            if (placed.size() >= count) {
                clusterEnabled = true;
                grid.placements.clear();
                for (BuildingPlacement p : placed) {
                    int dx = p.originRel.getX();
                    int dz = p.originRel.getZ();
                    grid.placements.add(com.formacraft.common.skeleton.transform.BlockTransform.translate(dx, 0, dz));

                    // Footing params derived from candidate metrics are not carried in v1; re-evaluate quickly via fields.
                    // This keeps logic consistent and still cheap.
                    int wEff = p.effectiveWidth();
                    int dEff = p.effectiveDepth();
                    TerrainFields.FootprintMetrics fm = fields.rectMetricsFromMinCorner(world, origin.getX() + dx, origin.getZ() + dz, wEff, dEff);
                    int r = fm.range();
                    boolean stilt = r >= 11;
                    int pd = stilt ? 0 : (r <= 6 ? Math.max(1, padDepth) : clamp(Math.max(padDepth, 4), 0, 6));
                    int ch = stilt ? clamp(Math.max(3, clearHeight / 2), 0, 16) : clearHeight;
                    footingByRelXZ.put(relKey(dx, dz), new FootingParams(pd, ch, stilt));
                }
            }
        }
        final boolean clusterEnabledFinal = clusterEnabled;
        if (!clusterEnabledFinal) {
            footingByRelXZ.clear();
            new GridSkeleton(grid).generate(new SkeletonParams()
                    .put("rows", rows)
                    .put("cols", cols)
                    .put("spacingX", spacing)
                    .put("spacingZ", spacing));
        }

        PlanDispatcher dispatcher = (plan, o, wld) -> {
            if (plan instanceof GeneratorBackedPlan gbp) {
                if (gbp.spec == null) return List.of();
                BlockPos origin2 = o;
                List<PlannedBlock> pad = List.of();
                if (terrainPolicy == TerrainPolicy.ADAPTIVE) {
                    origin2 = TerrainFit.snapOrigin(wld, o, gbp.spec);
                    int avg = TerrainFit.averageFootprintHeight(wld, origin2, gbp.spec.getFootprint().getWidth(), gbp.spec.getFootprint().getDepth());
                    int targetY = avg + 1;
                    // use style foundation language if possible
                    BlockState fill = Blocks.COBBLESTONE.getDefaultState();
                    if (spec != null) {
                        BuildingStyle style = (spec.getStyle() != null) ? spec.getStyle() : BuildingStyle.MODERN;
                        StyleProfile profile = StyleProfileRegistry.forStyle(style);
                        String fid = profile != null && profile.palette() != null ? profile.palette().foundation : null;
                        fill = getStateOrDefault(wld, fid, fill);
                    }
                    int pd = padDepth;
                    int ch = clearHeight;
                    if (clusterEnabledFinal) {
                        BlockPos rel = o.subtract(origin);
                        FootingParams fp = footingByRelXZ.get(relKey(rel.getX(), rel.getZ()));
                        if (fp != null) {
                            pd = fp.padDepth;
                            ch = fp.clearHeight;
                            if (fp.stilt) BuildReportContext.addFootingStiltUnit();
                            else BuildReportContext.addFootingPadUnit();
                        }
                    } else {
                        // non-cluster: treat as pad footing by default when ADAPTIVE is enabled
                        if (pd > 0) BuildReportContext.addFootingPadUnit();
                    }
                    BuildReportContext.setTerrainBudgetBlocks(terrainBudgetBlocks);
                    List<PlannedBlock> p0 = TerrainFit.adaptivePad(wld, origin2,
                            gbp.spec.getFootprint().getWidth(),
                            gbp.spec.getFootprint().getDepth(),
                            targetY,
                            fill,
                            pd,
                            ch);
                    if (terrainBudgetBlocks > 0 && p0.size() > terrainBudgetBlocks) {
                        BuildReportContext.addTerrainBudgetDegrade();
                        List<PlannedBlock> p1 = TerrainFit.adaptivePad(wld, origin2,
                                gbp.spec.getFootprint().getWidth(),
                                gbp.spec.getFootprint().getDepth(),
                                targetY,
                                fill,
                                0,
                                Math.max(0, Math.min(6, ch)));
                        if (p1.size() <= terrainBudgetBlocks) pad = p1;
                        else {
                            BuildReportContext.addTerrainBudgetDegrade();
                            List<PlannedBlock> p2 = TerrainFit.adaptivePad(wld, origin2,
                                    gbp.spec.getFootprint().getWidth(),
                                    gbp.spec.getFootprint().getDepth(),
                                    targetY,
                                    fill,
                                    0,
                                    2);
                            if (p2.size() <= terrainBudgetBlocks) pad = p2;
                            else {
                                BuildReportContext.addTerrainBudgetDegrade();
                                pad = List.of();
                            }
                        }
                    } else {
                        pad = p0;
                    }
                } else if (terrainPolicy == TerrainPolicy.FOLLOW) {
                    // no pad, no snap
                    origin2 = o;
                }
                List<PlannedBlock> building = StructureGeneratorFactory.getGenerator(gbp.spec).generate(gbp.spec, origin2, wld).getBlocks();
                if (pad.isEmpty()) return building;
                List<PlannedBlock> merged = new ArrayList<>(pad.size() + building.size());
                merged.addAll(pad);
                merged.addAll(building);
                return merged;
            }
            return List.of();
        };

        List<PlannedBlock> blocks = new ArrayList<>(Math.max(8000, rows * cols * 2500));
        blocks.addAll(new GridInterpreter(dispatcher).interpret(grid, origin, world));

        // Optional road network (PATH_POLYLINE) connecting grid rows/cols
        boolean includeRoads = getBool(extra);
        int roadWidth = Math.max(1, getInt(extra, "roadWidth", 3));
        if (includeRoads) {
            // StyleProfile: let style drive material language (v1). extra can override later.
            BuildingStyle style = (spec != null && spec.getStyle() != null) ? spec.getStyle() : BuildingStyle.MODERN;
            StyleProfile profile = StyleProfileRegistry.forStyle(style);
            String roadId = profile != null && profile.palette() != null ? profile.palette().floor : null;
            String borderId = profile != null && profile.palette() != null ? profile.palette().trim : null;
            BlockState road = getStateOrDefault(world, roadId, Blocks.GRAY_CONCRETE.getDefaultState());
            BlockState border = getStateOrDefault(world, borderId, Blocks.LIGHT_GRAY_CONCRETE.getDefaultState());
            PathRoadInterpreter roadInterp = new PathRoadInterpreter(road, border, true);

            // compute grid origin offsets (must match GridSkeleton)
            int x0 = -((cols - 1) * spacing) / 2;
            int z0 = -((rows - 1) * spacing) / 2;

            // row roads (east-west)
            for (int r = 0; r < rows; r++) {
                int z = z0 + r * spacing;
                BlockPos a = new BlockPos(x0, 0, z);
                BlockPos b = new BlockPos(x0 + (cols - 1) * spacing, 0, z);
                blocks.addAll(roadInterp.interpret(new PolylinePathPlan(List.of(a, b), roadWidth, true, false, 10), origin, world));
            }
            // col roads (north-south)
            for (int c = 0; c < cols; c++) {
                int x = x0 + c * spacing;
                BlockPos a = new BlockPos(x, 0, z0);
                BlockPos b = new BlockPos(x, 0, z0 + (rows - 1) * spacing);
                blocks.addAll(roadInterp.interpret(new PolylinePathPlan(List.of(a, b), roadWidth, true, false, 10), origin, world));
            }
        }
        String desc = String.format("OfficeDistrict (rows=%d, cols=%d, spacing=%d)", rows, cols, spacing);
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    private BlockState getStateOrDefault(ServerWorld world, String id, BlockState def) {
        if (id == null || id.isBlank()) return def;
        try {
            var ident = net.minecraft.util.Identifier.tryParse(id);
            if (ident == null) return def;
            return net.minecraft.registry.Registries.BLOCK.get(ident).getDefaultState();
        } catch (Exception e) {
            return def;
        }
    }

    private static int getInt(Map<String, Object> extra, String key, int def) {
        if (extra == null) return def;
        Object v = extra.get(key);
        if (v == null) return def;
        try {
            if (v instanceof Number n) return n.intValue();
            String s = String.valueOf(v).trim();
            return s.isEmpty() ? def : Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static long relKey(int dx, int dz) {
        return (((long) dx) << 32) ^ (dz & 0xffffffffL);
    }

    private static boolean getBool(Map<String, Object> extra) {
        if (extra == null) return true;
        Object v = extra.get("includeRoads");
        if (v == null) return true;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase();
        if (s.isEmpty()) return true;
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on");
    }
}


