package com.formacraft.server.generator;

import com.formacraft.common.model.build.*;
import com.formacraft.common.skeleton.compound.CompoundPlan;
import com.formacraft.common.skeleton.compound.GeneratorBackedPlan;
import com.formacraft.common.skeleton.path.PolylinePathPlan;
import com.formacraft.common.skeleton.rect.RectEnclosurePlan;
import com.formacraft.common.skeleton.transform.BlockTransform;
import com.formacraft.server.build.GeneratedStructure;
import com.formacraft.server.build.PlannedBlock;
import com.formacraft.server.skeleton.compound.CompoundInterpreter;
import com.formacraft.server.skeleton.compound.PlanDispatcher;
import com.formacraft.server.skeleton.path.PathPlanner;
import com.formacraft.server.skeleton.path.PathRoadInterpreter;
import com.formacraft.server.skeleton.rect.RectEnclosureInterpreter;
import com.formacraft.common.style.profile.BuildStrategy;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.profile.StyleProfileRegistry;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MingQingCourtyardGenerator (v1):
 * A COMPOUND-based courtyard complex composed from reusable parts:
 * - RectEnclosurePlan (walls + gate opening)
 * - Main hall / side rooms / gatehouse as GeneratorBackedPlan (delegates to existing generators)
 *
 * Anchor convention: origin is the center of the whole complex.
 */
public class MingQingCourtyardGenerator implements StructureGenerator {

    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        int w = (spec != null && spec.getFootprint() != null) ? Math.max(16, spec.getFootprint().getWidth()) : 20;
        int d = (spec != null && spec.getFootprint() != null) ? Math.max(16, spec.getFootprint().getDepth()) : 20;
        w = clamp(w, 16, 96);
        d = clamp(d, 16, 96);

        Map<String, Object> extra = spec != null ? spec.getExtra() : null;
        boolean includePaths = getBool(extra, "includePaths", true);
        int pathWidth = clamp(getInt(extra, "pathWidth", 3), 1, 7);

        Materials mats = (spec != null && spec.getMaterials() != null) ? spec.getMaterials() : new Materials();

        // overall palette (use spec materials as defaults)
        BlockState wallBlock = getStateOrDefault(world, mats.getWall(), Blocks.RED_TERRACOTTA.getDefaultState());
        BlockState roofBlock = getStateOrDefault(world, mats.getRoof(), Blocks.DEEPSLATE_TILES.getDefaultState());
        BlockState floorBlock = getStateOrDefault(world, mats.getFloor(), Blocks.POLISHED_ANDESITE.getDefaultState());
        BlockState foundationBlock = getStateOrDefault(world, mats.getFoundation(), Blocks.STONE_BRICKS.getDefaultState());

        // courtyard parameters
        int wallHeight = 3;
        int wallThickness = 1;
        int gateWidth = 3;
        Direction gateSide = Direction.SOUTH;

        // derived building sizes (v1 heuristics)
        int mainW = clamp((int) Math.round(w * 0.45), 9, w - 6);
        int mainD = clamp((int) Math.round(d * 0.22), 7, d - 8);

        int wingW = clamp((int) Math.round(w * 0.22), 7, w - 10);
        int wingD = clamp((int) Math.round(d * 0.18), 7, d - 10);

        int gateW = clamp((int) Math.round(w * 0.28), 7, w - 8);
        int gateD = clamp((int) Math.round(d * 0.16), 7, d - 10);

        // placement offsets (origin is center). Z+: south.
        int halfD = d / 2;
        int halfW = w / 2;
        int inset = 2;

        int mainOffZ = -(halfD - inset - (mainD / 2));
        int gateOffZ = (halfD - inset - (gateD / 2));

        int wingOffX = (halfW - inset - (wingW / 2));
        int wingOffZ = 0;

        // child specs (delegated generators)
        BuildingSpec mainHall = makeHallSpec(mainW, mainD, roofBlock, wallBlock, floorBlock, foundationBlock, "main_hall");
        BuildingSpec westWing = makeHallSpec(wingW, wingD, roofBlock, wallBlock, floorBlock, foundationBlock, "west_wing");
        BuildingSpec eastWing = makeHallSpec(wingW, wingD, roofBlock, wallBlock, floorBlock, foundationBlock, "east_wing");
        BuildingSpec gateHouse = makeHallSpec(gateW, gateD, roofBlock, wallBlock, floorBlock, foundationBlock, "gate_house");

        // enclosure plan
        RectEnclosurePlan enclosure = new RectEnclosurePlan(w, d, wallHeight, wallThickness, gateSide, gateWidth);

        CompoundPlan compound = new CompoundPlan()
                .add(enclosure, BlockTransform.identity())
                .add(new GeneratorBackedPlan(mainHall), BlockTransform.translate(0, 0, mainOffZ))
                .add(new GeneratorBackedPlan(westWing), BlockTransform.translate(-wingOffX, 0, wingOffZ))
                .add(new GeneratorBackedPlan(eastWing), BlockTransform.translate(wingOffX, 0, wingOffZ))
                .add(new GeneratorBackedPlan(gateHouse), BlockTransform.translate(0, 0, gateOffZ));

        // simple courtyard paths (v1): gate -> center -> main hall; center -> wings
        if (includePaths) {
            // start just inside the south wall opening
            BlockPos start = new BlockPos(0, 0, (d / 2) - 1);
            BlockPos center = new BlockPos(0, 0, 0);
            int mainDoorZ = mainOffZ + (mainD / 2) + 1;
            BlockPos mainDoor = new BlockPos(0, 0, mainDoorZ);

            compound.add(new PolylinePathPlan(List.of(start, center, mainDoor), pathWidth, false, false, 10), BlockTransform.identity());

            int wingDoorZ = (wingD / 2) + 1;
            BlockPos westDoor = new BlockPos(-wingOffX, 0, wingDoorZ);
            BlockPos eastDoor = new BlockPos(wingOffX, 0, wingDoorZ);
            compound.add(new PolylinePathPlan(PathPlanner.orthogonalL(center, westDoor), pathWidth, false, false, 10), BlockTransform.identity());
            compound.add(new PolylinePathPlan(PathPlanner.orthogonalL(center, eastDoor), pathWidth, false, false, 10), BlockTransform.identity());
        }

        // dispatcher wires child plan types to interpreters/generators
        PlanDispatcher dispatcher = (plan, o, wld) -> {
            if (plan instanceof GeneratorBackedPlan gbp) {
                if (gbp.spec == null) return List.of();
                return StructureGeneratorFactory.getGenerator(gbp.spec).generate(gbp.spec, o, wld).getBlocks();
            }
            if (plan instanceof RectEnclosurePlan rep) {
                StyleProfile profile = StyleProfileRegistry.forStyle(BuildingStyle.ASIAN);
                BlockState pillar = getStateOrDefault(wld, profile != null && profile.palette() != null ? profile.palette().pillar : null, wallBlock);
                boolean openArcade = profile != null && profile.resolve("GATE", Set.of("gate")) == BuildStrategy.OPEN_ARCADE;
                BlockState cap = getStateOrDefault(wld,
                        profile != null && profile.palette() != null ? profile.palette().cap : null,
                        Blocks.DARK_OAK_SLAB.getDefaultState());
                BlockState cap2 = getStateOrDefault(wld,
                        profile != null && profile.palette() != null ? profile.palette().trim : null,
                        wallBlock);
                int capLayers = (profile != null && profile.rules() != null) ? profile.rules().capLayers : 1;
                int capOverhang = (profile != null && profile.rules() != null) ? profile.rules().capOverhang : 0;
                return new RectEnclosureInterpreter(wallBlock, cap, cap2, capLayers, capOverhang, pillar, openArcade).interpret(rep, o, wld);
            }
            if (plan instanceof PolylinePathPlan pp) {
                // use floor as paving + stone brick border
                BlockState border = Blocks.STONE_BRICKS.getDefaultState();
                return new PathRoadInterpreter(floorBlock, border, true).interpret(pp, o, wld);
            }
            return List.of();
        };

        List<PlannedBlock> blocks = new CompoundInterpreter(dispatcher).interpret(compound, origin, world);

        String desc = String.format("MingQingCourtyard (w=%d,d=%d, compoundParts=%d)", w, d, compound.components.size());
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    private static BuildingSpec makeHallSpec(int w, int d, BlockState roof, BlockState wall, BlockState floor, BlockState foundation, String role) {
        BuildingSpec s = new BuildingSpec();
        s.setType(BuildingType.HOUSE);
        s.setStyle(BuildingStyle.ASIAN);
        s.setFootprint(new Footprint(w, d));
        s.setHeight(10);
        s.setFloors(1);

        Materials m = new Materials();
        m.setRoof(stateId(roof));
        m.setWall(stateId(wall));
        m.setFloor(stateId(floor));
        m.setFoundation(stateId(foundation));
        m.setWindow("minecraft:glass_pane");
        s.setMaterials(m);

        Features f = new Features();
        f.setHasDoor(true);
        f.setHasRoof(true);
        f.setHasWindows(true);
        f.setHasRoofDecoration(true);
        f.setFloorCount(1);
        s.setFeatures(f);

        StyleOptions so = new StyleOptions();
        so.setRoofType("hipped");
        so.setDoorStyle("double");
        so.setWindowRatio(0.18);
        so.setWindowStyle("fence");
        s.setStyleOptions(so);

        // role hint for future refinements
        s.setNotes("mingqing_part:" + role);
        s.setExtra(Map.of("courtyardPart", role));
        return s;
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

    private static String stateId(BlockState s) {
        // store block id without properties (v1)
        return net.minecraft.registry.Registries.BLOCK.getId(s.getBlock()).toString();
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int getInt(Map<String, Object> extra, String key, int def) {
        if (extra == null) return def;
        Object v = extra.get(key);
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
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


