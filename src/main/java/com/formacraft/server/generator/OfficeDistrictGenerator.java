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
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

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

        GridPlan grid = new GridPlan(new GeneratorBackedPlan(module));
        new GridSkeleton(grid).generate(new SkeletonParams()
                .put("rows", rows)
                .put("cols", cols)
                .put("spacingX", spacing)
                .put("spacingZ", spacing));

        PlanDispatcher dispatcher = (plan, o, wld) -> {
            if (plan instanceof GeneratorBackedPlan gbp) {
                if (gbp.spec == null) return List.of();
                return StructureGeneratorFactory.getGenerator(gbp.spec).generate(gbp.spec, o, wld).getBlocks();
            }
            return List.of();
        };

        List<PlannedBlock> blocks = new ArrayList<>(Math.max(8000, rows * cols * 2500));
        blocks.addAll(new GridInterpreter(dispatcher).interpret(grid, origin, world));

        // Optional road network (PATH_POLYLINE) connecting grid rows/cols
        boolean includeRoads = getBool(extra, "includeRoads", true);
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

    private static boolean getBool(Map<String, Object> extra, String key, boolean def) {
        if (extra == null) return def;
        Object v = extra.get(key);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase();
        if (s.isEmpty()) return def;
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on");
    }
}


