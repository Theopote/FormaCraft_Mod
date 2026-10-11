package com.formacraft.server.generation.typology.builder;

import com.formacraft.server.generation.structure.util.StructureSpecParsers;
import com.formacraft.common.logging.FcaLog;
import com.formacraft.common.model.build.*;
import com.formacraft.common.skeleton.compound.CompoundPlan;
import com.formacraft.common.skeleton.compound.GeneratorBackedPlan;
import com.formacraft.common.skeleton.path.PolylinePathPlan;
import com.formacraft.common.skeleton.rect.RectEnclosurePlan;
import com.formacraft.common.skeleton.transform.BlockTransform;
import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.typology.TypologyParamResolver;
import com.formacraft.server.generation.GenerationHub;
import com.formacraft.server.skeleton.compound.CompoundInterpreter;
import com.formacraft.server.skeleton.compound.PlanDispatcher;
import com.formacraft.server.skeleton.path.PathPlanner;
import com.formacraft.server.skeleton.path.PathRoadInterpreter;
import com.formacraft.server.skeleton.rect.RectEnclosureInterpreter;
import com.formacraft.server.terrain.TerrainPolicy;
import com.formacraft.server.terrain.TerrainPolicyResolver;
import com.formacraft.common.style.profile.BuildStrategy;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.profile.StyleProfileRegistry;
import com.formacraft.common.style.profile.DetailPreferences;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parametric Ming-Qing official courtyard compound builder (typology: {@code courtyard_compound}).
 * COMPOUND 骨架：围墙围合 + 正房/厢房/门楼 GeneratorBackedPlan 组合。
 */
public final class CourtyardCompoundBuilder {

    public static final String TYPOLOGY_ID = "courtyard_compound";

    private static final FcaLog LOG = FcaLog.of("CourtyardCompoundBuilder");

    private CourtyardCompoundBuilder() {}

    public static GeneratedStructure fromBuildingSpec(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        return generate(TypologyParamResolver.fromBuildingSpec(spec, TYPOLOGY_ID), origin, world, spec);
    }

    public static GeneratedStructure generate(
            Map<String, Object> params,
            BlockPos origin,
            ServerWorld world,
            BuildingSpec specHint
    ) {
        BuildingSpec spec = specHint != null ? specHint : new BuildingSpec();
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
        if (spec.getExtra() != null) {
            merged.putAll(spec.getExtra());
        }
        if (params != null) {
            merged.putAll(params);
        }
        spec.setExtra(merged);
        return generateFromSpec(spec, origin, world);
    }

    private static GeneratedStructure generateFromSpec(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        int[] dimensions = resolvedDimensions(spec);
        int w = dimensions[0], d = dimensions[1];

        Map<String, Object> extra = spec != null ? spec.getExtra() : null;
        boolean includePaths = getBool(extra, "includePaths", true);
        int pathWidth = clamp(getInt(extra, "pathWidth", 3), 1, 7);
        TerrainPolicy terrainPolicy = TerrainPolicyResolver.resolve(extra);

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

        Layout layout = resolveLayout(w, d, gateSide, pathWidth);
        Part main = layout.parts().get(0), left = layout.parts().get(1);
        Part right = layout.parts().get(2), gate = layout.parts().get(3);
        int mainW = main.width(), mainD = main.depth();
        int wingW = left.width(), wingD = left.depth();
        int gateW = gate.width(), gateD = gate.depth();
        int halfW = w / 2, halfD = d / 2;
        BlockPos mainCorner = main.corner(), gateCorner = gate.corner();
        BlockPos leftCorner = left.corner(), rightCorner = right.corner();
        Direction inDir = gateSide.getOpposite();
        Direction leftSide = inDir.rotateYCounterclockwise();
        Direction rightSide = inDir.rotateYClockwise();

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
            BlockPos start = insideGatePoint(gateSide, w, d);
            BlockPos center = new BlockPos(0, 0, 0);

            // door points derived from child corners + entrance-facing directions
            BlockPos mainDoor = doorPoint(mainCorner, mainW, mainD, gateSide);
            BlockPos leftDoor = doorPoint(leftCorner, wingW, wingD, rightSide);
            BlockPos rightDoor = doorPoint(rightCorner, wingW, wingD, leftSide);

            // primary axial path
            compound.add(new PolylinePathPlan(List.of(start, center, mainDoor), pathWidth, false, false, 10), BlockTransform.identity());

            // cross paths to wings (orthogonal L to stay "courtyard-like")
            compound.add(new PolylinePathPlan(PathPlanner.orthogonalL(center, leftDoor), pathWidth, false, false, 10), BlockTransform.identity());
            compound.add(new PolylinePathPlan(PathPlanner.orthogonalL(center, rightDoor), pathWidth, false, false, 10), BlockTransform.identity());

            // ring corridor (compound circulation): add a loop path inset from enclosure walls
            if ("ring_corridor".equals(layoutPlan)) {
                int ringInset = Math.max(2, wallThickness + 2);
                int x0 = -halfW + ringInset;
                int x1 = w - halfW - 1 - ringInset;
                int z0 = -halfD + ringInset;
                int z1 = d - halfD - 1 - ringInset;
                // clamp to avoid degenerate loops on tight footprints
                if (x1 - x0 >= 6 && z1 - z0 >= 6) {
                    List<BlockPos> loop = List.of(
                            new BlockPos(x0, 0, z1),
                            new BlockPos(x0, 0, z0),
                            new BlockPos(x1, 0, z0),
                            new BlockPos(x1, 0, z1),
                            new BlockPos(x0, 0, z1)
                    );
                    compound.add(new PolylinePathPlan(loop, Math.max(2, pathWidth), false, false, 10), BlockTransform.identity());
                }
            }
        }

        // dispatcher wires child plan types to interpreters/generators
        PlanDispatcher dispatcher = (plan, o, wld) -> {
            if (plan instanceof GeneratorBackedPlan gbp) {
                if (gbp.spec == null) return List.of();
                var building = new java.util.ArrayList<>(GenerationHub.routeStructure(gbp.spec).generate(gbp.spec, o, wld).getBlocks());
                openCourtyardEntrance(building, gbp.spec, o, floorBlock);
                return building;
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
                return new PathRoadInterpreter(floorBlock, border, true, paletteId, lamp, post, ornamentProfile, true, 2).interpret(pp, o, wld);
            }
            return List.of();
        };

        List<PlannedBlock> blocks = new CompoundInterpreter(dispatcher, true).interpret(compound, origin, world);
        if (world != null && !getBool(extra, "deferToPlanLanding", false)
                && (terrainPolicy == TerrainPolicy.ADAPTIVE || terrainPolicy == TerrainPolicy.FLATTEN_AREA)) {
            var landing = com.formacraft.server.terrain.BuildingLandingPlanner.prepare(blocks,
                    List.of(landingSite(spec, origin)), com.formacraft.server.terrain.BuildingLandingPlanner.ground(world),
                    terrainPolicy == TerrainPolicy.FLATTEN_AREA
                            ? com.formacraft.common.llm.dto.GlobalConstraints.TerrainStrategy.FLATTEN
                            : com.formacraft.common.llm.dto.GlobalConstraints.TerrainStrategy.ADAPTIVE,
                    false, foundationBlock);
            if (landing.problem() != null) throw new IllegalArgumentException(landing.problem());
            blocks = landing.blocks();
            origin = origin.up(landing.dy());
        }

        String desc = String.format("Courtyard Compound (courtyard_compound, w=%d, d=%d, parts=%d, childScale=%d%%)",
                w, d, compound.components.size(), layout.scalePercent());
        if (layout.scalePercent() < 100) LOG.info("courtyard layout resized derived children: w={} d={} scale={} facing={}",
                w, d, layout.scalePercent(), gateSide);
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
        ex.put("anchorMode", "corner");
        ex.put("terrainPolicy", "FOLLOW");
        s.setExtra(ex);
        return s;
    }

    /** Same entrance contract as child generators: layout, doorSide, then facing. */
    public static Direction resolveGateSide(BuildingSpec spec) {
        return StructureSpecParsers.resolveEntranceFacing(spec, Direction.SOUTH);
    }

    public record Part(String role, BlockPos corner, int width, int depth) {
        public int minX() { return corner.getX(); }
        public int minZ() { return corner.getZ(); }
        public int maxX() { return minX() + width - 1; }
        public int maxZ() { return minZ() + depth - 1; }
    }
    public record Layout(List<Part> parts, int scalePercent) {}

    /** Shrink derived child sizes, never the requested enclosure or the number of buildings. */
    public static Layout resolveLayout(int width, int depth, Direction facing, int pathWidth) {
        Direction gateSide = facing == null ? Direction.SOUTH : facing;
        Direction leftSide = gateSide.getOpposite().rotateYCounterclockwise();
        Direction rightSide = leftSide.getOpposite();
        for (int scale = 100; scale >= 0; scale -= 5) {
            double factor = scale / 100.0;
            int mainW = Math.max(9, (int)Math.round(width * .45 * factor));
            int mainD = Math.max(7, (int)Math.round(depth * .22 * factor));
            int wingW = Math.max(7, (int)Math.round(width * .22 * factor));
            int wingD = Math.max(7, (int)Math.round(depth * .18 * factor));
            int gateW = Math.max(7, (int)Math.round(width * .28 * factor));
            int gateD = Math.max(7, (int)Math.round(depth * .16 * factor));
            var parts = List.of(
                    new Part("main_hall", placeCornerOnSide(gateSide.getOpposite(),width,depth,2,mainW,mainD),mainW,mainD),
                    new Part("left_wing", placeCornerOnSide(leftSide,width,depth,2,wingW,wingD),wingW,wingD),
                    new Part("right_wing", placeCornerOnSide(rightSide,width,depth,2,wingW,wingD),wingW,wingD),
                    new Part("gate_house", placeCornerOnSide(gateSide,width,depth,2,gateW,gateD),gateW,gateD));
            if (layoutFits(parts,width,depth,pathWidth)) return new Layout(parts,scale);
        }
        throw new IllegalArgumentException("院落 " + width + "×" + depth
                + " 格无法同时容纳正房、两侧厢房、门楼及通道，请增大占地或改用单体建筑。");
    }

    private static boolean layoutFits(List<Part> parts, int width, int depth, int pathWidth) {
        int x0=-width/2,z0=-depth/2,x1=x0+width-1,z1=z0+depth-1;
        int radius=Math.max(1,pathWidth/2)+1;
        for (int i=0;i<parts.size();i++) {
            Part a=parts.get(i);
            // One block of roof overhang must remain inside the enclosure.
            if(a.minX()-1<=x0 || a.maxX()+1>=x1 || a.minZ()-1<=z0 || a.maxZ()+1>=z1) return false;
            if(a.minX()-1<=radius && a.maxX()+1>=-radius && a.minZ()-1<=radius && a.maxZ()+1>=-radius) return false;
            for(int j=0;j<i;j++) {
                Part b=parts.get(j);
                if(a.minX()-1<=b.maxX()+1 && b.minX()-1<=a.maxX()+1
                        && a.minZ()-1<=b.maxZ()+1 && b.minZ()-1<=a.maxZ()+1) return false;
            }
        }
        return true;
    }

    /** Whole compound stays at one elevation; only authored columns receive earthwork. */
    public static com.formacraft.server.terrain.BuildingLandingPlanner.Site landingSite(BuildingSpec spec, BlockPos origin) {
        int[] size = resolvedDimensions(spec);
        int minX = origin.getX() - size[0] / 2, minZ = origin.getZ() - size[1] / 2;
        return new com.formacraft.server.terrain.BuildingLandingPlanner.Site(
                new com.formacraft.common.network.LlmPlanTerrainBounds.Bounds(minX, origin.getY(), minZ,
                        minX + size[0] - 1, origin.getY() + 20, minZ + size[1] - 1),
                com.formacraft.common.llm.dto.GlobalConstraints.Facing.valueOf(resolveGateSide(spec).name()));
    }

    private static void openCourtyardEntrance(List<PlannedBlock> blocks, BuildingSpec child,
                                              BlockPos corner, BlockState floor) {
        int w = child.getFootprint().getWidth(), d = child.getFootprint().getDepth();
        Direction facing = StructureSpecParsers.resolveEntranceFacing(child, Direction.SOUTH);
        boolean through = "gate_house".equals(child.getExtra().get("courtyardPart"));
        boolean alongZ = facing.getAxis() == Direction.Axis.Z;
        int center = (alongZ ? w : d) / 2;
        int edge = switch (facing) { case NORTH, WEST -> 0; case EAST -> w - 1; default -> d - 1; };
        for (int axis = center - 1; axis <= center; axis++) {
            int start = through ? 0 : edge;
            int end = through ? (alongZ ? d : w) - 1 : edge;
            for (int run = start; run <= end; run++) {
                BlockPos p = corner.add(alongZ ? axis : run, 0, alongZ ? run : axis);
                blocks.add(new PlannedBlock(p, floor));
                blocks.add(new PlannedBlock(p.up(), Blocks.AIR.getDefaultState()));
                blocks.add(new PlannedBlock(p.up(2), Blocks.AIR.getDefaultState()));
            }
        }
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
                    switch (p) {
                        case "none", "no", "false", "0", "off" -> {
                            return "none";
                        }
                        case "front_back", "frontback", "front-back", "front/back", "前后", "前后分区", "前后布局",
                             "前厅后室" -> {
                            return "front_back";
                        }
                        case "left_right", "leftright", "left-right", "left/right", "左右", "左右分区", "左右布局" -> {
                            return "left_right";
                        }
                        case "ring_corridor", "ring", "courtyard_corridor", "gallery", "cloister", "回廊", "环廊",
                             "环形走廊", "围绕中庭", "回字形", "回字布局", "回字走廊" -> {
                            return "ring_corridor";
                        }
                    }
                }
            }
        } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }
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

    private static BlockPos placeCornerOnSide(Direction side, int width, int depth, int inset, int bw, int bd) {
        // Returns a relative corner inside the exact enclosure bounds.
        int halfW = width / 2, halfD = depth / 2;
        int x;
        int z;
        side = (side == null) ? Direction.SOUTH : side;
        switch (side) {
            case NORTH -> { x = -(bw / 2); z = -halfD + inset; }
            case SOUTH -> { x = -(bw / 2); z = depth - halfD - inset - bd; }
            case WEST -> { x = -halfW + inset; z = -(bd / 2); }
            case EAST -> { x = width - halfW - inset - bw; z = -(bd / 2); }
            default -> { x = -(bw / 2); z = depth - halfD - inset - bd; }
        }
        return new BlockPos(x, 0, z);
    }

    private static BlockPos insideGatePoint(Direction gateSide, int width, int depth) {
        // A point just inside the gate opening (relative to compound origin).
        int halfW = width / 2, halfD = depth / 2;
        gateSide = (gateSide == null) ? Direction.SOUTH : gateSide;
        return switch (gateSide) {
            case NORTH -> new BlockPos(0, 0, -halfD + 1);
            case SOUTH -> new BlockPos(0, 0, depth - halfD - 2);
            case WEST -> new BlockPos(-halfW + 1, 0, 0);
            case EAST -> new BlockPos(width - halfW - 2, 0, 0);
            default -> new BlockPos(0, 0, depth - halfD - 2);
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

    private static BlockState getStateOrDefault(ServerWorld world, String id, BlockState def) {
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

    public static int[] resolvedDimensions(BuildingSpec spec) {
        int width=spec!=null && spec.getFootprint()!=null?spec.getFootprint().getWidth():36;
        int depth=spec!=null && spec.getFootprint()!=null?spec.getFootprint().getDepth():44;
        Map<String,Object> extra=spec!=null?spec.getExtra():null;
        return new int[]{clamp(getInt(extra,"width",width),16,96),clamp(getInt(extra,"depth",depth),16,96)};
    }

    private static int getInt(Map<String, Object> extra, String key, int def) {
        return StructureSpecParsers.mapInt(extra, key, def);
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


