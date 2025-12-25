package com.formacraft.server.generator;

import com.formacraft.common.model.build.*;
import com.formacraft.common.skeleton.SkeletonParams;
import com.formacraft.common.skeleton.compound.GeneratorBackedPlan;
import com.formacraft.common.skeleton.grid.GridPlan;
import com.formacraft.server.build.GeneratedStructure;
import com.formacraft.server.build.PlannedBlock;
import com.formacraft.server.skeleton.compound.PlanDispatcher;
import com.formacraft.server.skeleton.grid.GridInterpreter;
import com.formacraft.server.skeleton.grid.GridSkeleton;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;

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

        List<PlannedBlock> blocks = new GridInterpreter(dispatcher).interpret(grid, origin, world);
        String desc = String.format("OfficeDistrict (rows=%d, cols=%d, spacing=%d)", rows, cols, spacing);
        return new GeneratedStructure(null, origin, desc, blocks);
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
}


