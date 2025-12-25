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
 * CastleCompoundGenerator (v1):
 * A reusable "fortification compound" generator driven by COMPOUND topology.
 *
 * Parts:
 * - RectEnclosurePlan: perimeter wall with gate opening
 * - 4 corner towers: GeneratorBackedPlan (delegates to TowerGenerator)
 * - Gatehouse: GeneratorBackedPlan (delegates to HouseGenerator)
 *
 * Anchor convention: origin is the center of the whole castle footprint.
 */
public class CastleCompoundGenerator implements StructureGenerator {

    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        Map<String, Object> extra = spec != null ? spec.getExtra() : null;
        boolean includePaths = getBool(extra, "includePaths", true);
        int pathWidth = clamp(getInt(extra, "pathWidth", 3), 1, 7);

        int w = (spec != null && spec.getFootprint() != null) ? Math.max(24, spec.getFootprint().getWidth()) : 48;
        int d = (spec != null && spec.getFootprint() != null) ? Math.max(24, spec.getFootprint().getDepth()) : 36;
        w = clamp(w, 24, 160);
        d = clamp(d, 24, 160);

        int wallHeight = clamp(getIntExtra(spec, "wallHeight", 6), 4, 16);
        int towerHeight = clamp(getIntExtra(spec, "towerHeight", 18), 10, 60);
        int gateWidth = clamp(getIntExtra(spec, "gateWidth", 3), 2, 7);
        int wallThickness = clamp(getIntExtra(spec, "wallThickness", 2), 1, 5);

        Direction gateSide = parseFacing(getStringExtra(spec, "facing", "SOUTH"));

        Materials mats = (spec != null && spec.getMaterials() != null) ? spec.getMaterials() : new Materials();
        BlockState wallBlock = getStateOrDefault(world, mats.getWall(), Blocks.STONE_BRICKS.getDefaultState());
        // cap resolved from style profile (data-driven); fallback to stone brick slab
        StyleProfile styleProfile = StyleProfileRegistry.forStyle(BuildingStyle.MEDIEVAL);
        BlockState capBlock = getStateOrDefault(world,
                styleProfile != null && styleProfile.palette() != null ? styleProfile.palette().cap : null,
                Blocks.STONE_BRICK_SLAB.getDefaultState());
        BlockState cap2Block = getStateOrDefault(world,
                styleProfile != null && styleProfile.palette() != null ? styleProfile.palette().trim : null,
                wallBlock);
        int capLayers = (styleProfile != null && styleProfile.rules() != null) ? styleProfile.rules().capLayers : 1;
        int capOverhang = (styleProfile != null && styleProfile.rules() != null) ? styleProfile.rules().capOverhang : 0;

        // child specs
        BuildingSpec towerSpec = makeTowerSpec(towerHeight, mats);
        BuildingSpec gateHouseSpec = makeGateHouseSpec(Math.max(9, w / 4), Math.max(7, d / 6), mats);

        int halfW = w / 2;
        int halfD = d / 2;
        int inset = 2;

        // corner tower offsets
        int tx = halfW - inset;
        int tz = halfD - inset;

        RectEnclosurePlan enclosure = new RectEnclosurePlan(w, d, wallHeight, wallThickness, gateSide, gateWidth);

        // gatehouse placed just inside the gate side
        int gateOffZ = (gateSide == Direction.SOUTH ? (halfD - inset - (gateHouseSpec.getDepth() / 2)) :
                (gateSide == Direction.NORTH ? -(halfD - inset - (gateHouseSpec.getDepth() / 2)) : 0));
        int gateOffX = (gateSide == Direction.EAST ? (halfW - inset - (gateHouseSpec.getWidth() / 2)) :
                (gateSide == Direction.WEST ? -(halfW - inset - (gateHouseSpec.getWidth() / 2)) : 0));

        CompoundPlan compound = new CompoundPlan()
                .add(enclosure, BlockTransform.identity())
                .add(new GeneratorBackedPlan(towerSpec), BlockTransform.translate(tx, 0, tz))
                .add(new GeneratorBackedPlan(towerSpec), BlockTransform.translate(-tx, 0, tz))
                .add(new GeneratorBackedPlan(towerSpec), BlockTransform.translate(tx, 0, -tz))
                .add(new GeneratorBackedPlan(towerSpec), BlockTransform.translate(-tx, 0, -tz))
                .add(new GeneratorBackedPlan(gateHouseSpec), BlockTransform.translate(gateOffX, 0, gateOffZ));

        if (includePaths) {
            // Start just inside the gate opening, then to courtyard center, then towards inner keep area.
            int startX = 0;
            int startZ = 0;
            switch (gateSide) {
                case SOUTH -> { startX = gateOffX; startZ = (d / 2) - 1; }
                case NORTH -> { startX = gateOffX; startZ = -(d / 2) + 1; }
                case EAST -> { startX = (w / 2) - 1; startZ = gateOffZ; }
                case WEST -> { startX = -(w / 2) + 1; startZ = gateOffZ; }
                default -> { startX = 0; startZ = (d / 2) - 1; }
            }
            BlockPos start = new BlockPos(startX, 0, startZ);
            BlockPos center = new BlockPos(0, 0, 0);
            BlockPos keep = new BlockPos(0, 0, -(d / 4)); // simple bias towards north interior

            compound.add(new PolylinePathPlan(List.of(start, center, keep), pathWidth, false, false, 10), BlockTransform.identity());

            // Branches to corner towers
            BlockPos t1 = new BlockPos(tx, 0, tz);
            BlockPos t2 = new BlockPos(-tx, 0, tz);
            BlockPos t3 = new BlockPos(tx, 0, -tz);
            BlockPos t4 = new BlockPos(-tx, 0, -tz);
            compound.add(new PolylinePathPlan(PathPlanner.orthogonalL(center, t1), pathWidth, false, false, 10), BlockTransform.identity());
            compound.add(new PolylinePathPlan(PathPlanner.orthogonalL(center, t2), pathWidth, false, false, 10), BlockTransform.identity());
            compound.add(new PolylinePathPlan(PathPlanner.orthogonalL(center, t3), pathWidth, false, false, 10), BlockTransform.identity());
            compound.add(new PolylinePathPlan(PathPlanner.orthogonalL(center, t4), pathWidth, false, false, 10), BlockTransform.identity());
        }

        PlanDispatcher dispatcher = (plan, o, wld) -> {
            if (plan instanceof GeneratorBackedPlan gbp) {
                if (gbp.spec == null) return List.of();
                return StructureGeneratorFactory.getGenerator(gbp.spec).generate(gbp.spec, o, wld).getBlocks();
            }
            if (plan instanceof RectEnclosurePlan rep) {
                StyleProfile profile = StyleProfileRegistry.forStyle(BuildingStyle.MEDIEVAL);
                BlockState pillar = getStateOrDefault(wld, profile != null && profile.palette() != null ? profile.palette().pillar : null, wallBlock);
                boolean openArcade = profile != null && profile.resolve("GATE", Set.of("gate")) == BuildStrategy.OPEN_ARCADE;
                return new RectEnclosureInterpreter(wallBlock, capBlock, cap2Block, capLayers, capOverhang, pillar, openArcade).interpret(rep, o, wld);
            }
            if (plan instanceof PolylinePathPlan pp) {
                BlockState road = Blocks.COBBLESTONE.getDefaultState();
                BlockState border = Blocks.STONE_BRICKS.getDefaultState();
                return new PathRoadInterpreter(road, border, true).interpret(pp, o, wld);
            }
            return List.of();
        };

        List<PlannedBlock> blocks = new CompoundInterpreter(dispatcher).interpret(compound, origin, world);
        String desc = String.format("CastleCompound (w=%d,d=%d, towers=4, wallH=%d)", w, d, wallHeight);
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    private static BuildingSpec makeTowerSpec(int height, Materials mats) {
        BuildingSpec s = new BuildingSpec();
        s.setType(BuildingType.TOWER);
        s.setStyle(BuildingStyle.MEDIEVAL);
        s.setFootprint(new Footprint(7, 7));
        s.setHeight(height);
        s.setFloors(Math.max(1, height / 6));

        Materials m = new Materials();
        if (mats != null) {
            m.setWall(mats.getWall());
            m.setRoof(mats.getRoof());
            m.setFloor(mats.getFloor());
            m.setFoundation(mats.getFoundation());
            m.setWindow(mats.getWindow());
        }
        if (m.getWall() == null) m.setWall("minecraft:stone_bricks");
        if (m.getRoof() == null) m.setRoof("minecraft:stone_bricks");
        if (m.getFloor() == null) m.setFloor("minecraft:spruce_planks");
        if (m.getWindow() == null) m.setWindow("minecraft:glass_pane");
        s.setMaterials(m);

        Features f = new Features();
        f.setHasDoor(true);
        f.setHasWindows(true);
        f.setHasRoof(true);
        f.setHasRoofDecoration(true);
        f.setFloorCount(Math.max(1, height / 6));
        s.setFeatures(f);

        StyleOptions so = new StyleOptions();
        so.setRoofType("cone");
        so.setDoorStyle("arched");
        so.setWindowRatio(0.2);
        s.setStyleOptions(so);

        s.setNotes("castle_part:corner_tower");
        s.setExtra(Map.of("castlePart", "tower"));
        return s;
    }

    private static BuildingSpec makeGateHouseSpec(int w, int d, Materials mats) {
        BuildingSpec s = new BuildingSpec();
        s.setType(BuildingType.HOUSE);
        s.setStyle(BuildingStyle.MEDIEVAL);
        s.setFootprint(new Footprint(w, d));
        s.setHeight(10);
        s.setFloors(1);

        Materials m = new Materials();
        if (mats != null) {
            m.setWall(mats.getWall());
            m.setRoof(mats.getRoof());
            m.setFloor(mats.getFloor());
            m.setFoundation(mats.getFoundation());
            m.setWindow(mats.getWindow());
        }
        if (m.getWall() == null) m.setWall("minecraft:stone_bricks");
        if (m.getRoof() == null) m.setRoof("minecraft:stone_bricks");
        if (m.getFloor() == null) m.setFloor("minecraft:spruce_planks");
        if (m.getWindow() == null) m.setWindow("minecraft:glass_pane");
        s.setMaterials(m);

        Features f = new Features();
        f.setHasDoor(true);
        f.setHasWindows(true);
        f.setHasRoof(true);
        f.setHasRoofDecoration(true);
        f.setFloorCount(1);
        s.setFeatures(f);

        StyleOptions so = new StyleOptions();
        so.setRoofType("hipped");
        so.setDoorStyle("arched");
        so.setWindowRatio(0.2);
        s.setStyleOptions(so);

        s.setNotes("castle_part:gate_house");
        s.setExtra(Map.of("castlePart", "gate_house"));
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

    private static int getIntExtra(BuildingSpec spec, String key, int def) {
        if (spec == null) return def;
        Map<String, Object> extra = spec.getExtra();
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

    private static String getStringExtra(BuildingSpec spec, String key, String def) {
        if (spec == null) return def;
        Map<String, Object> extra = spec.getExtra();
        if (extra == null) return def;
        Object v = extra.get(key);
        if (v == null) return def;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }

    private static Direction parseFacing(String s) {
        String v = (s == null ? "" : s).trim().toUpperCase();
        return switch (v) {
            case "N", "NORTH", "北", "朝北" -> Direction.NORTH;
            case "S", "SOUTH", "南", "朝南" -> Direction.SOUTH;
            case "E", "EAST", "东", "朝东" -> Direction.EAST;
            case "W", "WEST", "西", "朝西" -> Direction.WEST;
            default -> Direction.SOUTH;
        };
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


