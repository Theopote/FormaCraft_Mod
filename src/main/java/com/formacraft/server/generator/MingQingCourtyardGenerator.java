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
import com.formacraft.server.terrain.TerrainFit;
import com.formacraft.server.terrain.TerrainPolicy;
import com.formacraft.server.terrain.TerrainPolicyResolver;
import com.formacraft.server.build.BuildReportContext;
import com.formacraft.server.foundation.FoundationPlanner;
import com.formacraft.common.style.profile.BuildStrategy;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.profile.StyleProfileRegistry;
import com.formacraft.common.style.profile.DetailPreferences;
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
 * <p>
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
        TerrainPolicy terrainPolicy = TerrainPolicyResolver.resolve(extra);
        int padDepth = clamp(getInt(extra, "terrainPadDepth", 2), 0, 6);
        int clearHeight = clamp(getInt(extra, "terrainClearHeight", 6), 0, 16);
        int terrainBudgetBlocks = clamp(getInt(extra, "terrainBudgetBlocks", 8000), 0, 200000);

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
        Direction gateSide = resolveGateSide(spec);
        String layoutPlan = resolveLayoutPlan(spec);

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

        // Compute child building corner positions relative to the compound center (origin).
        // We intentionally keep all child buildings axis-aligned; only their door side changes to face the courtyard.
        BlockPos mainCorner = placeCornerOnSide(gateSide.getOpposite(), halfW, halfD, inset, mainW, mainD);
        BlockPos gateCorner = placeCornerOnSide(gateSide, halfW, halfD, inset, gateW, gateD);
        Direction inDir = gateSide.getOpposite(); // direction from gate into the courtyard
        Direction leftSide = inDir.rotateYCounterclockwise();
        Direction rightSide = inDir.rotateYClockwise();
        BlockPos leftCorner = placeCornerOnSide(leftSide, halfW, halfD, inset, wingW, wingD);
        BlockPos rightCorner = placeCornerOnSide(rightSide, halfW, halfD, inset, wingW, wingD);

        // child specs (delegated generators)
        BuildingSpec mainHall = makeHallSpec(mainW, mainD, roofBlock, wallBlock, floorBlock, foundationBlock, "main_hall");
        BuildingSpec leftWing = makeHallSpec(wingW, wingD, roofBlock, wallBlock, floorBlock, foundationBlock, "left_wing");
        BuildingSpec rightWing = makeHallSpec(wingW, wingD, roofBlock, wallBlock, floorBlock, foundationBlock, "right_wing");
        BuildingSpec gateHouse = makeHallSpec(gateW, gateD, roofBlock, wallBlock, floorBlock, foundationBlock, "gate_house");

        // Propagate style genes + layout hints down to child specs (best-effort).
        // - Child doors face toward the courtyard center.
        // - For ring_corridor (compound circulation), we do NOT force child interior partitions.
        inheritGenesAndLayout(spec, mainHall, gateSide, layoutPlan);
        inheritGenesAndLayout(spec, gateHouse, gateSide.getOpposite(), layoutPlan);
        inheritGenesAndLayout(spec, leftWing, rightSide, layoutPlan);  // left wing sits on leftSide, door faces rightSide
        inheritGenesAndLayout(spec, rightWing, leftSide, layoutPlan); // right wing sits on rightSide, door faces leftSide

        // enclosure plan
        // Style-driven wall expression (extra explicitly overrides)
        BuildingStyle style = (spec != null && spec.getStyle() != null) ? spec.getStyle() : BuildingStyle.ASIAN;
        StyleProfile profile = (spec != null) ? StyleProfileRegistry.resolve(spec) : StyleProfileRegistry.forStyle(style);
        DetailPreferences details = (profile != null) ? profile.details() : null;
        boolean battlements = false;
        int battlementSpacing = 2;
        boolean banner = false;
        String bannerColor = "red";
        if (extra != null) {
            if (extra.containsKey("wallBattlements")) battlements = getBool(extra, "wallBattlements", false);
            if (extra.containsKey("wallBattlementSpacing")) battlementSpacing = clamp(getInt(extra, "wallBattlementSpacing", battlementSpacing), 1, 6);
            if (extra.containsKey("wallBanner")) banner = getBool(extra, "wallBanner", false);
            if (extra.containsKey("wallBannerColor")) bannerColor = String.valueOf(extra.get("wallBannerColor")).trim().toLowerCase(java.util.Locale.ROOT);
        }
        // Defaults from style when not explicitly provided
        if (extra == null || !extra.containsKey("wallBattlements")) {
            if (details != null && details.eavesProfile != null) {
                battlements = details.eavesProfile.toLowerCase(java.util.Locale.ROOT).contains("battlement");
            }
        }
        if (extra == null || !extra.containsKey("wallBanner")) {
            if (details != null && details.bannerEnabled != null) banner = Boolean.TRUE.equals(details.bannerEnabled);
            else if (details != null && details.ornamentProfile != null) {
                String op = details.ornamentProfile.toLowerCase(java.util.Locale.ROOT);
                if (op.contains("banner")) banner = true;
            }
        }
        if ((extra == null || !extra.containsKey("wallBannerColor")) && banner && details != null && details.bannerColor != null && !details.bannerColor.isBlank()) {
            bannerColor = details.bannerColor.trim().toLowerCase(java.util.Locale.ROOT);
        }

        RectEnclosurePlan enclosure = new RectEnclosurePlan(w, d, wallHeight, wallThickness, gateSide, gateWidth,
                battlements, battlementSpacing, banner, bannerColor);

        CompoundPlan compound = new CompoundPlan()
                .add(enclosure, BlockTransform.identity())
                .add(new GeneratorBackedPlan(mainHall), BlockTransform.translate(mainCorner.getX(), 0, mainCorner.getZ()))
                .add(new GeneratorBackedPlan(leftWing), BlockTransform.translate(leftCorner.getX(), 0, leftCorner.getZ()))
                .add(new GeneratorBackedPlan(rightWing), BlockTransform.translate(rightCorner.getX(), 0, rightCorner.getZ()))
                .add(new GeneratorBackedPlan(gateHouse), BlockTransform.translate(gateCorner.getX(), 0, gateCorner.getZ()));

        // simple courtyard paths (v1): gate -> center -> main hall; center -> wings
        if (includePaths) {
            // start just inside the gate opening
            BlockPos start = insideGatePoint(gateSide, halfW, halfD);
            BlockPos center = new BlockPos(0, 0, 0);

            // door points derived from child corners + entrance-facing directions
            BlockPos mainDoor = doorPoint(mainCorner, mainW, mainD, gateSide);
            BlockPos leftDoor = doorPoint(leftCorner, wingW, wingD, rightSide);
            BlockPos rightDoor = doorPoint(rightCorner, wingW, wingD, leftSide);

            // primary axial path
            compound.add(new PolylinePathPlan(List.of(start, center, mainDoor), pathWidth, true, false, 10), BlockTransform.identity());

            // cross paths to wings (orthogonal L to stay "courtyard-like")
            compound.add(new PolylinePathPlan(PathPlanner.orthogonalL(center, leftDoor), pathWidth, true, false, 10), BlockTransform.identity());
            compound.add(new PolylinePathPlan(PathPlanner.orthogonalL(center, rightDoor), pathWidth, true, false, 10), BlockTransform.identity());

            // ring corridor (compound circulation): add a loop path inset from enclosure walls
            if ("ring_corridor".equals(layoutPlan)) {
                int ringInset = Math.max(2, wallThickness + 2);
                int x0 = -halfW + ringInset;
                int x1 = halfW - ringInset;
                int z0 = -halfD + ringInset;
                int z1 = halfD - ringInset;
                // clamp to avoid degenerate loops on tight footprints
                if (x1 - x0 >= 6 && z1 - z0 >= 6) {
                    List<BlockPos> loop = List.of(
                            new BlockPos(x0, 0, z1),
                            new BlockPos(x0, 0, z0),
                            new BlockPos(x1, 0, z0),
                            new BlockPos(x1, 0, z1),
                            new BlockPos(x0, 0, z1)
                    );
                    compound.add(new PolylinePathPlan(loop, Math.max(2, pathWidth), true, false, 10), BlockTransform.identity());
                }
            }
        }

        // dispatcher wires child plan types to interpreters/generators
        PlanDispatcher dispatcher = (plan, o, wld) -> {
            if (plan instanceof GeneratorBackedPlan gbp) {
                if (gbp.spec == null) return List.of();
                BlockPos origin2 = o;
                List<PlannedBlock> pad = List.of();
                if (terrainPolicy == TerrainPolicy.ADAPTIVE) {
                    origin2 = TerrainFit.snapOrigin(wld, o, gbp.spec);
                    int avg = TerrainFit.averageFootprintHeight(wld, origin2, gbp.spec.getFootprint().getWidth(), gbp.spec.getFootprint().getDepth());
                    int targetY = avg + 1;
                    var analysis = TerrainFit.analyze(wld, origin2, gbp.spec.getFootprint().getWidth(), gbp.spec.getFootprint().getDepth());
                    FoundationPlanner.Decision fd = FoundationPlanner.decide(gbp.spec, analysis, padDepth, clearHeight);
                    if (fd.stilt()) BuildReportContext.addFootingStiltUnit();
                    else if (fd.padDepth() > 0) BuildReportContext.addFootingPadUnit();
                    BuildReportContext.setTerrainBudgetBlocks(terrainBudgetBlocks);
                    int plannedPad = Math.max(0, Math.min(6, fd.padDepth()));
                    int plannedClear = Math.max(0, Math.min(16, fd.clearHeight()));
                    List<PlannedBlock> p0 = TerrainFit.adaptivePad(wld, origin2,
                            gbp.spec.getFootprint().getWidth(),
                            gbp.spec.getFootprint().getDepth(),
                            targetY,
                            foundationBlock,
                            plannedPad,
                            plannedClear);
                    int usedPad = plannedPad;
                    int usedClear = plannedClear;
                    int degradeSteps = 0;
                    if (terrainBudgetBlocks > 0 && p0.size() > terrainBudgetBlocks) {
                        BuildReportContext.addTerrainBudgetDegrade();
                        List<PlannedBlock> p1 = TerrainFit.adaptivePad(wld, origin2,
                                gbp.spec.getFootprint().getWidth(),
                                gbp.spec.getFootprint().getDepth(),
                                targetY,
                                foundationBlock,
                                0,
                                Math.min(6, plannedClear));
                        if (p1.size() <= terrainBudgetBlocks) {
                            pad = p1;
                            usedPad = 0;
                            usedClear = Math.min(6, plannedClear);
                            degradeSteps = 1;
                        }
                        else {
                            BuildReportContext.addTerrainBudgetDegrade();
                            List<PlannedBlock> p2 = TerrainFit.adaptivePad(wld, origin2,
                                    gbp.spec.getFootprint().getWidth(),
                                    gbp.spec.getFootprint().getDepth(),
                                    targetY,
                                    foundationBlock,
                                    0,
                                    2);
                            if (p2.size() <= terrainBudgetBlocks) {
                                pad = p2;
                                usedPad = 0;
                                usedClear = 2;
                                degradeSteps = 2;
                            }
                            else {
                                BuildReportContext.addTerrainBudgetDegrade();
                                pad = List.of();
                                usedPad = 0;
                                usedClear = 0;
                                degradeSteps = 3;
                            }
                        }
                    } else {
                        pad = p0;
                    }
                    BuildReportContext.addFoundationExecution(fd.type(), analysis.range(), plannedPad, plannedClear, usedPad, usedClear, degradeSteps);
                }
                List<PlannedBlock> building = StructureGeneratorFactory.getGenerator(gbp.spec).generate(gbp.spec, origin2, wld).getBlocks();
                if (pad.isEmpty()) return building;
                List<PlannedBlock> merged = new java.util.ArrayList<>(pad.size() + building.size());
                merged.addAll(pad);
                merged.addAll(building);
                return merged;
            }
            if (plan instanceof RectEnclosurePlan rep) {
                StyleProfile enclosureProfile = (spec != null) ? StyleProfileRegistry.resolve(spec) : StyleProfileRegistry.forStyle(BuildingStyle.ASIAN);
                BlockState pillar = getStateOrDefault(wld, enclosureProfile != null && enclosureProfile.palette() != null ? enclosureProfile.palette().pillar : null, wallBlock);
                boolean openArcade = enclosureProfile != null && enclosureProfile.resolve("GATE", Set.of("gate")) == BuildStrategy.OPEN_ARCADE;
                BlockState cap = getStateOrDefault(wld,
                        enclosureProfile != null && enclosureProfile.palette() != null ? enclosureProfile.palette().cap : null,
                        Blocks.DARK_OAK_SLAB.getDefaultState());
                BlockState cap2 = getStateOrDefault(wld,
                        enclosureProfile != null && enclosureProfile.palette() != null ? enclosureProfile.palette().trim : null,
                        wallBlock);
                int capLayers = (enclosureProfile != null && enclosureProfile.rules() != null) ? enclosureProfile.rules().capLayers : 1;
                int capOverhang = (enclosureProfile != null && enclosureProfile.rules() != null) ? enclosureProfile.rules().capOverhang : 0;
                String paletteId = null;
                if (spec != null && spec.getExtra() != null) {
                    Object pid = spec.getExtra().get("paletteId");
                    if (pid != null) paletteId = String.valueOf(pid).trim();
                }
                if ((paletteId == null || paletteId.isBlank()) && enclosureProfile != null && enclosureProfile.details() != null
                        && enclosureProfile.details().paletteId != null && !enclosureProfile.details().paletteId.isBlank()) {
                    paletteId = enclosureProfile.details().paletteId.trim();
                }
                return new RectEnclosureInterpreter(wallBlock, cap, cap2, capLayers, capOverhang, pillar, openArcade, paletteId).interpret(rep, o, wld);
            }
            if (plan instanceof PolylinePathPlan pp) {
                // use floor as paving + stone brick border
                BlockState border = Blocks.STONE_BRICKS.getDefaultState();
                StyleProfile roadProfile = (spec != null) ? StyleProfileRegistry.resolve(spec) : StyleProfileRegistry.forStyle(BuildingStyle.ASIAN);
                String paletteId = null;
                if (spec != null && spec.getExtra() != null) {
                    Object pid = spec.getExtra().get("paletteId");
                    if (pid != null) paletteId = String.valueOf(pid).trim();
                }
                if ((paletteId == null || paletteId.isBlank()) && roadProfile != null && roadProfile.details() != null
                        && roadProfile.details().paletteId != null && !roadProfile.details().paletteId.isBlank()) {
                    paletteId = roadProfile.details().paletteId.trim();
                }
                var roadDetails = roadProfile != null ? roadProfile.details() : null;
                String eavesProfile = roadDetails != null ? roadDetails.eavesProfile : null;
                String ornamentProfile = roadDetails != null ? roadDetails.ornamentProfile : null;
                boolean neon = eavesProfile != null && eavesProfile.toLowerCase(java.util.Locale.ROOT).contains("neon");
                boolean cyber = ornamentProfile != null && (ornamentProfile.toLowerCase(java.util.Locale.ROOT).contains("cyber") || ornamentProfile.toLowerCase(java.util.Locale.ROOT).contains("sign"));
                BlockState lamp = neon ? Blocks.SEA_LANTERN.getDefaultState() : Blocks.LANTERN.getDefaultState();
                BlockState post = cyber ? Blocks.IRON_BARS.getDefaultState() : Blocks.COBBLESTONE_WALL.getDefaultState();
                return new PathRoadInterpreter(floorBlock, border, true, paletteId, lamp, post, ornamentProfile).interpret(pp, o, wld);
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
        java.util.Map<String, Object> ex = new java.util.HashMap<>();
        ex.put("courtyardPart", role);
        s.setExtra(ex);
        return s;
    }

    private static Direction resolveGateSide(BuildingSpec spec) {
        // Layout IR: extra.layout.entranceFacing -> which side has the gate opening.
        try {
            if (spec != null && spec.getExtra() != null) {
                Object layoutObj = spec.getExtra().get("layout");
                if (layoutObj instanceof Map<?, ?> m) {
                    Object ef = m.get("entranceFacing");
                    if (ef != null) {
                        String s = String.valueOf(ef).trim().toUpperCase(java.util.Locale.ROOT);
                        return switch (s) {
                            case "N", "NORTH", "北", "朝北" -> Direction.NORTH;
                            case "S", "SOUTH", "南", "朝南" -> Direction.SOUTH;
                            case "E", "EAST", "东", "朝东" -> Direction.EAST;
                            case "W", "WEST", "西", "朝西" -> Direction.WEST;
                            default -> Direction.SOUTH;
                        };
                    }
                }
            }
        } catch (Throwable ignored) {}
        return Direction.SOUTH;
    }

    private static String resolveLayoutPlan(BuildingSpec spec) {
        try {
            if (spec != null && spec.getExtra() != null) {
                Object layoutObj = spec.getExtra().get("layout");
                if (layoutObj instanceof Map<?, ?> m) {
                    Object plan = m.get("plan");
                    if (plan == null) return "none";
                    String p = String.valueOf(plan).trim().toLowerCase(java.util.Locale.ROOT);
                    if (p.isEmpty()) return "none";
                    if (p.equals("none") || p.equals("no") || p.equals("false") || p.equals("0") || p.equals("off")) return "none";
                    if (p.equals("front_back") || p.equals("frontback") || p.equals("front-back") || p.equals("front/back")
                            || p.equals("前后") || p.equals("前后分区") || p.equals("前后布局") || p.equals("前厅后室")) return "front_back";
                    if (p.equals("left_right") || p.equals("leftright") || p.equals("left-right") || p.equals("left/right")
                            || p.equals("左右") || p.equals("左右分区") || p.equals("左右布局")) return "left_right";
                    if (p.equals("ring_corridor") || p.equals("ring") || p.equals("courtyard_corridor") || p.equals("gallery") || p.equals("cloister")
                            || p.equals("回廊") || p.equals("环廊") || p.equals("环形走廊") || p.equals("围绕中庭") || p.equals("回字形") || p.equals("回字布局") || p.equals("回字走廊")) return "ring_corridor";
                }
            }
        } catch (Throwable ignored) {}
        return "none";
    }

    private static void inheritGenesAndLayout(BuildingSpec parent, BuildingSpec child, Direction childEntranceFacing, String layoutPlan) {
        if (child == null) return;
        java.util.Map<String, Object> ex = new java.util.HashMap<>();
        if (child.getExtra() != null) ex.putAll(child.getExtra());

        // style genes
        if (parent != null && parent.getExtra() != null) {
            Object spid = parent.getExtra().get("styleProfileId");
            if (spid != null && !String.valueOf(spid).trim().isEmpty()) ex.put("styleProfileId", String.valueOf(spid).trim());
            Object pid = parent.getExtra().get("paletteId");
            if (pid != null && !String.valueOf(pid).trim().isEmpty()) ex.put("paletteId", String.valueOf(pid).trim());
        }

        // layout hint for child doors (HouseGenerator reads extra.layout.entranceFacing)
        java.util.Map<String, Object> layout = new java.util.HashMap<>();
        layout.put("entranceFacing", childEntranceFacing.asString().toUpperCase(java.util.Locale.ROOT));
        // Only propagate partition plans (front_back/left_right). ring_corridor is a compound circulation plan.
        if ("front_back".equals(layoutPlan) || "left_right".equals(layoutPlan)) {
            layout.put("plan", layoutPlan);
        }
        ex.put("layout", layout);

        child.setExtra(ex);
    }

    private static BlockPos placeCornerOnSide(Direction side, int halfW, int halfD, int inset, int bw, int bd) {
        // Returns a relative corner position (minX,minZ) for an axis-aligned rectangle inside the enclosure.
        int x;
        int z;
        side = (side == null) ? Direction.SOUTH : side;
        switch (side) {
            case NORTH -> { x = -(bw / 2); z = -halfD + inset; }
            case SOUTH -> { x = -(bw / 2); z = halfD - inset - bd; }
            case WEST -> { x = -halfW + inset; z = -(bd / 2); }
            case EAST -> { x = halfW - inset - bw; z = -(bd / 2); }
            default -> { x = -(bw / 2); z = halfD - inset - bd; }
        }
        return new BlockPos(x, 0, z);
    }

    private static BlockPos insideGatePoint(Direction gateSide, int halfW, int halfD) {
        // A point just inside the gate opening (relative to compound origin).
        gateSide = (gateSide == null) ? Direction.SOUTH : gateSide;
        return switch (gateSide) {
            case NORTH -> new BlockPos(0, 0, -halfD + 1);
            case SOUTH -> new BlockPos(0, 0, halfD - 1);
            case WEST -> new BlockPos(-halfW + 1, 0, 0);
            case EAST -> new BlockPos(halfW - 1, 0, 0);
            default -> new BlockPos(0, 0, halfD - 1);
        };
    }

    private static BlockPos doorPoint(BlockPos corner, int bw, int bd, Direction doorSide) {
        // Door point on the perimeter of a child building (relative to compound origin).
        int x0 = corner.getX();
        int z0 = corner.getZ();
        int cx = x0 + (bw / 2);
        int cz = z0 + (bd / 2);
        doorSide = (doorSide == null) ? Direction.SOUTH : doorSide;
        return switch (doorSide) {
            case NORTH -> new BlockPos(cx, 0, z0);
            case SOUTH -> new BlockPos(cx, 0, z0 + bd - 1);
            case WEST -> new BlockPos(x0, 0, cz);
            case EAST -> new BlockPos(x0 + bw - 1, 0, cz);
            default -> new BlockPos(cx, 0, z0 + bd - 1);
        };
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


