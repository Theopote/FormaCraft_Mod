package com.formacraft.common.generation.structure;

import com.formacraft.common.generation.structure.util.StructureSpecParsers;
import com.formacraft.common.logging.FcaLog;
import com.formacraft.common.model.build.*;
import com.formacraft.common.skeleton.compound.CompoundPlan;
import com.formacraft.common.skeleton.compound.GeneratorBackedPlan;
import com.formacraft.common.skeleton.path.PolylinePathPlan;
import com.formacraft.common.skeleton.rect.RectEnclosurePlan;
import com.formacraft.common.skeleton.transform.BlockTransform;
import com.formacraft.common.generation.structure.blueprint.CastleBlueprintCompiler;
import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.server.generation.GenerationHub;
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
import com.formacraft.server.material.PaletteResolver;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
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

    private static final FcaLog LOG = FcaLog.of("CastleCompoundGenerator");

    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        Map<String, Object> extra = spec != null ? spec.getExtra() : null;
        boolean includePaths = getBool(extra);
        int pathWidth = clamp(getInt(extra, "pathWidth", 3), 1, 7);
        TerrainPolicy terrainPolicy = TerrainPolicyResolver.resolve(extra);
        int padDepth = clamp(getInt(extra, "terrainPadDepth", 2), 0, 6);
        int clearHeight = clamp(getInt(extra, "terrainClearHeight", 6), 0, 16);
        int terrainBudgetBlocks = clamp(getInt(extra, "terrainBudgetBlocks", 8000), 0, 200000);

        int w = (spec != null && spec.getFootprint() != null) ? Math.max(24, spec.getFootprint().getWidth()) : 48;
        int d = (spec != null && spec.getFootprint() != null) ? Math.max(24, spec.getFootprint().getDepth()) : 36;
        w = clamp(w, 24, 160);
        d = clamp(d, 24, 160);

        int wallHeight = clamp(getIntExtra(spec, "wallHeight", 6), 4, 16);
        int towerHeight = clamp(getIntExtra(spec, "towerHeight", 18), 10, 60);
        int gateWidth = clamp(getIntExtra(spec, "gateWidth", 3), 2, 7);
        int wallThickness = clamp(getIntExtra(spec, "wallThickness", 2), 1, 5);

        Direction gateSide = resolveGateSide(spec);
        String layoutPlan = resolveLayoutPlan(spec);

        Materials mats = (spec != null && spec.getMaterials() != null) ? spec.getMaterials() : new Materials();
        BlockState wallBlock = getStateOrDefault(world, mats.getWall(), Blocks.STONE_BRICKS.getDefaultState());
        // cap resolved from style profile (data-driven); fallback to stone brick slab
        // Prefer per-request styleProfileId when present.
        StyleProfile styleProfile = (spec != null) ? StyleProfileRegistry.resolve(spec) : StyleProfileRegistry.forStyle(BuildingStyle.MEDIEVAL);
        // paletteId for semantic picks (moat/bridge best-effort)
        String paletteId0 = null;
        if (spec != null && spec.getExtra() != null && spec.getExtra().get("paletteId") != null) {
            paletteId0 = String.valueOf(spec.getExtra().get("paletteId")).trim();
        }
        if ((paletteId0 == null || paletteId0.isBlank()) && styleProfile != null && styleProfile.details() != null
                && styleProfile.details().paletteId != null && !styleProfile.details().paletteId.isBlank()) {
            paletteId0 = styleProfile.details().paletteId.trim();
        }
        final String paletteId = paletteId0;

        BlockState capBlock = getStateOrDefault(world,
                styleProfile != null && styleProfile.palette() != null ? styleProfile.palette().cap : null,
                Blocks.STONE_BRICK_SLAB.getDefaultState());
        BlockState cap2Block = getStateOrDefault(world,
                styleProfile != null && styleProfile.palette() != null ? styleProfile.palette().trim : null,
                wallBlock);
        int capLayers = (styleProfile != null && styleProfile.rules() != null) ? styleProfile.rules().capLayers : 1;
        int capOverhang = (styleProfile != null && styleProfile.rules() != null) ? styleProfile.rules().capOverhang : 0;

        // Defensive phenotype: moat + drawbridge (style-driven default for Medieval_Castle when not explicitly provided)
        boolean moatExplicit = extra != null && extra.containsKey("moat");
        boolean moat = getBool(extra, "moat", false);
        boolean drawbridge = getBool(extra, "drawbridge", true);
        if (!moatExplicit && spec != null && spec.getExtra() != null) {
            Object spid = spec.getExtra().get("styleProfileId");
            if (spid != null && String.valueOf(spid).trim().equals("Medieval_Castle")) {
                moat = true;
            }
        }
        int moatWidth = clamp(getInt(extra, "moatWidth", 4), 2, 10);
        int moatDepth = clamp(getInt(extra, "moatDepth", 3), 1, 8);
        int moatGap = clamp(getInt(extra, "moatGap", 1), 0, 4); // gap between wall and moat inner edge

        // child specs
        BuildingSpec towerSpec = makeTowerSpec(towerHeight, mats);
        BuildingSpec gateHouseSpec = makeGateHouseSpec(Math.max(9, w / 4), Math.max(7, d / 6), mats);
        // Propagate style genes + layout hint to gatehouse so its door faces into the courtyard.
        inheritGenesAndLayoutToGateHouse(spec, gateHouseSpec, gateSide.getOpposite(), layoutPlan);

        int halfW = w / 2;
        int halfD = d / 2;
        int inset = 2;

        // corner tower offsets
        int tx = halfW - inset;
        int tz = halfD - inset;

        // Default castle walls should have battlements for strong silhouette.
        boolean wallBattlements = true;
        int wallBattlementSpacing = 2;
        boolean wallBanner = false;
        String wallBannerColor = "red";
        if (extra != null) {
            boolean battlementsExplicit = extra.containsKey("wallBattlements");
            Object wb = extra.get("wallBattlements");
            if (wb instanceof Boolean b) wallBattlements = b;
            else if (wb != null) {
                String s = String.valueOf(wb).trim().toLowerCase(java.util.Locale.ROOT);
                if (!s.isEmpty()) wallBattlements = (s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on"));
            }
            // StyleProfile fallback (only when not explicitly provided)
            if (!battlementsExplicit && styleProfile != null && styleProfile.details() != null
                    && styleProfile.details().eavesProfile != null) {
                String ep = styleProfile.details().eavesProfile.toLowerCase(java.util.Locale.ROOT);
                wallBattlements = ep.contains("battlement");
            }

            Object sp = extra.get("wallBattlementSpacing");
            if (sp != null) {
                try {
                    int v = (sp instanceof Number n) ? n.intValue() : Integer.parseInt(String.valueOf(sp).trim());
                    wallBattlementSpacing = Math.max(1, Math.min(6, v));
                } catch (Exception e) { LOG.debug("best-effort step failed", e); }
            }

            boolean bannerExplicit = extra.containsKey("wallBanner");
            Object wban = extra.get("wallBanner");
            if (wban instanceof Boolean b) wallBanner = b;
            else if (wban != null) {
                String s = String.valueOf(wban).trim().toLowerCase(java.util.Locale.ROOT);
                if (!s.isEmpty()) wallBanner = (s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on"));
            }
            // StyleProfile fallback: banner defaults + ornament hint (only when not explicitly provided)
            if (!bannerExplicit && styleProfile != null && styleProfile.details() != null) {
                if (styleProfile.details().bannerEnabled != null) {
                    wallBanner = Boolean.TRUE.equals(styleProfile.details().bannerEnabled);
                } else if (styleProfile.details().ornamentProfile != null) {
                    String op = styleProfile.details().ornamentProfile.toLowerCase(java.util.Locale.ROOT);
                    if (op.contains("banner")) wallBanner = true;
                }
            }

            boolean bannerColorExplicit = extra.containsKey("wallBannerColor");
            Object wbc = extra.get("wallBannerColor");
            if (wbc != null) {
                String s = String.valueOf(wbc).trim().toLowerCase(java.util.Locale.ROOT);
                if (!s.isEmpty()) wallBannerColor = s;
            }
            if (!bannerColorExplicit && wallBanner && styleProfile != null && styleProfile.details() != null
                    && styleProfile.details().bannerColor != null && !styleProfile.details().bannerColor.isBlank()) {
                wallBannerColor = styleProfile.details().bannerColor.trim().toLowerCase(java.util.Locale.ROOT);
            }
        }
        RectEnclosurePlan enclosure = new RectEnclosurePlan(w, d, wallHeight, wallThickness, gateSide, gateWidth,
                wallBattlements, wallBattlementSpacing, wallBanner, wallBannerColor);

        // gatehouse placed just inside the gate side
        int gateOffZ = (gateSide == Direction.SOUTH ? (halfD - inset - (gateHouseSpec.getDepth() / 2)) :
                (gateSide == Direction.NORTH ? -(halfD - inset - (gateHouseSpec.getDepth() / 2)) : 0));
        int gateOffX = (gateSide == Direction.EAST ? (halfW - inset - (gateHouseSpec.getWidth() / 2)) :
                (gateSide == Direction.WEST ? -(halfW - inset - (gateHouseSpec.getWidth() / 2)) : 0));

        // Optional: blueprint-driven castle composition (semantic components -> CompoundPlan).
        // If present, this overrides the default part layout but keeps existing terrain/path policies.
        CompoundPlan compound = null;
        if (extra != null) {
            Object bp = extra.get("blueprint");
            if (bp instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> bpMap = (Map<String, Object>) m;
                compound = CastleBlueprintCompiler.tryCompile(bpMap, spec, gateSide);
            }
        }
        if (compound == null) {
            compound = new CompoundPlan()
                    .add(enclosure, BlockTransform.identity())
                    .add(new GeneratorBackedPlan(towerSpec), BlockTransform.translate(tx, 0, tz))
                    .add(new GeneratorBackedPlan(towerSpec), BlockTransform.translate(-tx, 0, tz))
                    .add(new GeneratorBackedPlan(towerSpec), BlockTransform.translate(tx, 0, -tz))
                    .add(new GeneratorBackedPlan(towerSpec), BlockTransform.translate(-tx, 0, -tz))
                    .add(new GeneratorBackedPlan(gateHouseSpec), BlockTransform.translate(gateOffX, 0, gateOffZ));
        }

        if (includePaths) {
            // Start just inside the gate opening, then to courtyard center, then towards inner keep area.
            int startX;
            int startZ;
            switch (gateSide) {
                case SOUTH -> { startX = gateOffX; startZ = (d / 2) - 1; }
                case NORTH -> { startX = gateOffX; startZ = -(d / 2) + 1; }
                case EAST -> { startX = (w / 2) - 1; startZ = gateOffZ; }
                case WEST -> { startX = -(w / 2) + 1; startZ = gateOffZ; }
                default -> { startX = 0; startZ = (d / 2) - 1; }
            }
            BlockPos start = new BlockPos(startX, 0, startZ);
            BlockPos center = new BlockPos(0, 0, 0);
            // keep bias towards the interior opposite the gate (so entrance direction changes the axis)
            int keepDist = Math.max(6, d / 4);
            BlockPos keep = switch (gateSide) {
                case SOUTH -> new BlockPos(0, 0, -keepDist);
                case NORTH -> new BlockPos(0, 0, keepDist);
                case EAST -> new BlockPos(-keepDist, 0, 0);
                case WEST -> new BlockPos(keepDist, 0, 0);
                default -> new BlockPos(0, 0, -keepDist);
            };

            compound.add(new PolylinePathPlan(List.of(start, center, keep), pathWidth, true, false, 10), BlockTransform.identity());

            // Branches to corner towers
            BlockPos t1 = new BlockPos(tx, 0, tz);
            BlockPos t2 = new BlockPos(-tx, 0, tz);
            BlockPos t3 = new BlockPos(tx, 0, -tz);
            BlockPos t4 = new BlockPos(-tx, 0, -tz);
            compound.add(new PolylinePathPlan(PathPlanner.orthogonalL(center, t1), pathWidth, true, false, 10), BlockTransform.identity());
            compound.add(new PolylinePathPlan(PathPlanner.orthogonalL(center, t2), pathWidth, true, false, 10), BlockTransform.identity());
            compound.add(new PolylinePathPlan(PathPlanner.orthogonalL(center, t3), pathWidth, true, false, 10), BlockTransform.identity());
            compound.add(new PolylinePathPlan(PathPlanner.orthogonalL(center, t4), pathWidth, true, false, 10), BlockTransform.identity());

            // ring corridor (castle patrol loop): a loop path inset from enclosure walls.
            if ("ring_corridor".equals(layoutPlan)) {
                int ringInset = Math.max(3, wallThickness + 2);
                int x0 = -halfW + ringInset;
                int x1 = halfW - ringInset;
                int z0 = -halfD + ringInset;
                int z1 = halfD - ringInset;
                if (x1 - x0 >= 10 && z1 - z0 >= 10) {
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

        PlanDispatcher dispatcher = (plan, o, wld) -> {
            if (plan instanceof GeneratorBackedPlan gbp) {
                if (gbp.spec == null) return List.of();
                BlockPos origin2 = o;
                List<PlannedBlock> pad = List.of();
                if (terrainPolicy == TerrainPolicy.ADAPTIVE) {
                    origin2 = TerrainFit.snapOrigin(wld, o, gbp.spec);
                    int avg = TerrainFit.averageFootprintHeight(wld, origin2, gbp.spec.getFootprint().getWidth(), gbp.spec.getFootprint().getDepth());
                    int targetY = avg + 1;
                    BlockState fill = getStateOrDefault(wld, mats.getFoundation(), Blocks.COBBLESTONE.getDefaultState());
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
                            fill,
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
                                fill,
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
                                    fill,
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
                List<PlannedBlock> building = GenerationHub.routeStructure(gbp.spec).generate(gbp.spec, origin2, wld).getBlocks();
                if (pad.isEmpty()) return building;
                List<PlannedBlock> merged = new java.util.ArrayList<>(pad.size() + building.size());
                merged.addAll(pad);
                merged.addAll(building);
                return merged;
            }
            if (plan instanceof RectEnclosurePlan rep) {
                StyleProfile profile = (spec != null) ? StyleProfileRegistry.resolve(spec) : StyleProfileRegistry.forStyle(BuildingStyle.MEDIEVAL);
                BlockState pillar = getStateOrDefault(wld, profile != null && profile.palette() != null ? profile.palette().pillar : null, wallBlock);
                boolean openArcade = profile != null && profile.resolve("GATE", Set.of("gate")) == BuildStrategy.OPEN_ARCADE;
                return new RectEnclosureInterpreter(wallBlock, capBlock, cap2Block, capLayers, capOverhang, pillar, openArcade, paletteId).interpret(rep, o, wld);
            }
            if (plan instanceof PolylinePathPlan pp) {
                BlockState road = Blocks.COBBLESTONE.getDefaultState();
                BlockState border = Blocks.STONE_BRICKS.getDefaultState();
                StyleProfile profile = (spec != null) ? StyleProfileRegistry.resolve(spec) : StyleProfileRegistry.forStyle(BuildingStyle.MEDIEVAL);
                var details = profile != null ? profile.details() : null;
                String eavesProfile = details != null ? details.eavesProfile : null;
                String ornamentProfile = details != null ? details.ornamentProfile : null;
                boolean neon = eavesProfile != null && eavesProfile.toLowerCase(java.util.Locale.ROOT).contains("neon");
                boolean cyber = ornamentProfile != null && (ornamentProfile.toLowerCase(java.util.Locale.ROOT).contains("cyber") || ornamentProfile.toLowerCase(java.util.Locale.ROOT).contains("sign"));
                BlockState lamp = neon ? Blocks.SEA_LANTERN.getDefaultState() : Blocks.LANTERN.getDefaultState();
                BlockState post = cyber ? Blocks.IRON_BARS.getDefaultState() : Blocks.COBBLESTONE_WALL.getDefaultState();
                return new PathRoadInterpreter(road, border, true, paletteId, lamp, post, ornamentProfile).interpret(pp, o, wld);
            }
            return List.of();
        };

        List<PlannedBlock> blocks = new CompoundInterpreter(dispatcher).interpret(compound, origin, world);

        // --- Defensive extras: moat + drawbridge (post-process, best-effort) ---
        if (moat) {
            // materials (semantic)
            BlockState lining = Blocks.DEEPSLATE_TILES.getDefaultState();
            BlockState deck = Blocks.SPRUCE_PLANKS.getDefaultState();
            BlockState rail = Blocks.SPRUCE_FENCE.getDefaultState();
            if (paletteId != null && !paletteId.isBlank()) {
                lining = PaletteResolver.pick(world, paletteId, "WALL_FOUNDATION", origin, 0xC45A001L, lining);
                lining = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xC45A002L, lining);
                deck = PaletteResolver.pick(world, paletteId, "BRIDGE_DECK", origin, 0xC45A003L, deck);
                rail = PaletteResolver.pick(world, paletteId, "BRIDGE_RAIL", origin, 0xC45A004L, rail);
                rail = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xC45A005L, rail);
            }

            // moat rectangle (around enclosure, axis-aligned)
            int innerX0 = -halfW - moatGap;
            int innerX1 = halfW + moatGap;
            int innerZ0 = -halfD - moatGap;
            int innerZ1 = halfD + moatGap;
            int outerX0 = innerX0 - moatWidth;
            int outerX1 = innerX1 + moatWidth;
            int outerZ0 = innerZ0 - moatWidth;
            int outerZ1 = innerZ1 + moatWidth;

            // dig + fill water (y from -moatDepth..0). also add a lining at the bottom ring.
            for (int x = outerX0; x <= outerX1; x++) {
                for (int z = outerZ0; z <= outerZ1; z++) {
                    boolean insideInner = (x >= innerX0 && x <= innerX1 && z >= innerZ0 && z <= innerZ1);
                    if (insideInner) continue;
                    for (int y = -moatDepth; y <= 0; y++) {
                        blocks.add(new PlannedBlock(origin.add(x, y, z), Blocks.WATER.getDefaultState()));
                    }
                    // bottom lining
                    blocks.add(new PlannedBlock(origin.add(x, -moatDepth, z), lining));
                }
            }

            // drawbridge on gate side
            if (drawbridge) {
                int cx = 0;
                int cz = 0;
                int deckY = 1; // above water surface
                int halfGate = gateWidth / 2;

                // local bridge direction: from inside to outside (same as gateSide)
                int from = (moatGap + 1);
                int to = (moatGap + moatWidth + 2);

                if (gateSide == Direction.SOUTH || gateSide == Direction.NORTH) {
                    int zWall = (gateSide == Direction.SOUTH) ? halfD : -halfD;
                    int dir = (gateSide == Direction.SOUTH) ? 1 : -1;
                    for (int x = cx - halfGate - 1; x <= cx + halfGate + 1; x++) {
                        for (int i = -from; i <= to; i++) {
                            int z = zWall + (dir * i);
                            blocks.add(new PlannedBlock(origin.add(x, deckY, z), deck));
                            // simple rails on edges
                            if (x == cx - halfGate - 1 || x == cx + halfGate + 1) {
                                blocks.add(new PlannedBlock(origin.add(x, deckY + 1, z), rail));
                            }
                        }
                    }
                } else {
                    int xWall = (gateSide == Direction.EAST) ? halfW : -halfW;
                    int dir = (gateSide == Direction.EAST) ? 1 : -1;
                    for (int z = cz - halfGate - 1; z <= cz + halfGate + 1; z++) {
                        for (int i = -from; i <= to; i++) {
                            int x = xWall + (dir * i);
                            blocks.add(new PlannedBlock(origin.add(x, deckY, z), deck));
                            if (z == cz - halfGate - 1 || z == cz + halfGate + 1) {
                                blocks.add(new PlannedBlock(origin.add(x, deckY + 1, z), rail));
                            }
                        }
                    }
                }
            }
        }

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

        Materials m = getMaterials(mats);
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

    private static @NotNull Materials getMaterials(Materials mats) {
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
        return m;
    }

    private static BuildingSpec makeGateHouseSpec(int w, int d, Materials mats) {
        BuildingSpec s = new BuildingSpec();
        s.setType(BuildingType.HOUSE);
        s.setStyle(BuildingStyle.MEDIEVAL);
        s.setFootprint(new Footprint(w, d));
        s.setHeight(10);
        s.setFloors(1);

        Materials m = getMaterials(mats);
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
        return StructureSpecParsers.extraInt(spec, key, def);
    }

    private static Direction resolveGateSide(BuildingSpec spec) {
        return StructureSpecParsers.resolveEntranceFacing(spec, Direction.SOUTH);
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
        } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }
        return "none";
    }

    private static void inheritGenesAndLayoutToGateHouse(BuildingSpec parent, BuildingSpec gateHouse, Direction entranceFacing, String layoutPlan) {
        if (gateHouse == null) return;
        HashMap<String, Object> ex = new HashMap<>();
        if (gateHouse.getExtra() != null) ex.putAll(gateHouse.getExtra());

        if (parent != null && parent.getExtra() != null) {
            Object spid = parent.getExtra().get("styleProfileId");
            if (spid != null && !String.valueOf(spid).trim().isEmpty()) ex.put("styleProfileId", String.valueOf(spid).trim());
            Object pid = parent.getExtra().get("paletteId");
            if (pid != null && !String.valueOf(pid).trim().isEmpty()) ex.put("paletteId", String.valueOf(pid).trim());
        }

        HashMap<String, Object> layout = new HashMap<>();
        layout.put("entranceFacing", entranceFacing.asString().toUpperCase(java.util.Locale.ROOT));
        // only propagate partition plans; ring_corridor is compound circulation
        if ("front_back".equals(layoutPlan) || "left_right".equals(layoutPlan)) {
            layout.put("plan", layoutPlan);
        }
        ex.put("layout", layout);

        gateHouse.setExtra(ex);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int getInt(Map<String, Object> extra, String key, int def) {
        return StructureSpecParsers.mapInt(extra, key, def);
    }

    private static boolean getBool(Map<String, Object> extra) {
        if (extra == null) return true;
        Object v = extra.get("includePaths");
        if (v == null) return true;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase();
        if (s.isEmpty()) return true;
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on");
    }

    private static boolean getBool(Map<String, Object> extra, String key, boolean def) {
        if (extra == null || key == null) return def;
        Object v = extra.get(key);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase();
        if (s.isEmpty()) return def;
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on");
    }
}


