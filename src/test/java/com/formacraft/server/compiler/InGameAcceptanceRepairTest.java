package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.impl.FacadeWindowsGenerator;
import com.formacraft.common.generation.component.impl.MassMainGenerator;
import com.formacraft.common.generation.component.util.FacadeScope;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.Layout;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.generation.typology.builder.TailiangTimberHallBuilder;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regressions from Round-12 in-game acceptance (dense columns, missing 重檐, solid_sides ignored, EMPTY_OUTPUT).
 */
class InGameAcceptanceRepairTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void facadeScopeHonorsFacadeSideAndWindowSidesAliases() {
        assertEquals(Set.of("NORTH"),
                FacadeScope.parseStrict(Map.of("facade_side", "north"), 1).faceNames());
        assertEquals(Set.of("EAST", "WEST"),
                FacadeScope.parseStrict(Map.of("window_sides", List.of("east", "west")), 1).faceNames());
    }

    @Test
    void solidSidesSuppressEastWestWindowsOnMass() {
        Map<String, Object> params = new HashMap<>();
        params.put("wall_block", "minecraft:white_concrete");
        params.put("hollow", true);
        params.put("window_style", "square");
        params.put("window_ratio", 0.35);
        params.put("solid_sides", List.of("east", "west"));
        params.put("window_sides", List.of("north", "south"));
        params.put("roof_type", "none");
        params.put("entrance_type", "none");
        Component mass = new Component(
                "MASS_MAIN", "studio", new Vec3i(0, 0, 0),
                new Dimensions(12, 10, 6), List.of(), params);
        Slot slot = new Slot("studio", new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH, null, null, null);
        List<BlockPatch> patches = new MassMainGenerator().generate(new SemanticComponent("MASS_MAIN", slot, mass));
        List<BlockPatch> glass = patches.stream()
                .filter(p -> p.targetBlock() != null && p.targetBlock().contains("glass"))
                .toList();
        assertTrue(glass.stream().noneMatch(p -> p.dx() == 0 || p.dx() == 11),
                "EAST/WEST solid_sides must suppress mass windows");
    }

    @Test
    void facadeSideNorthDoesNotWrapOntoEastWest() {
        Map<String, Object> params = new HashMap<>();
        params.put("facade_side", "north");
        params.put("window_width", 2);
        params.put("window_height", 2);
        params.put("windows_per_wall", 2);
        params.put("floor_count", 1);
        params.put("floor_height", 4);
        params.put("sill_offset", 1);
        params.put("reserve_entrance_bay", false);
        Component c = new Component(
                "FACADE_WINDOWS", "studio", new Vec3i(0, 0, 0),
                new Dimensions(12, 10, 4), List.of(), params);
        Slot slot = new Slot("studio", new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH, null, null, null);
        List<BlockPatch> glass = new FacadeWindowsGenerator()
                .generate(new SemanticComponent("FACADE_WINDOWS", slot, c)).stream()
                .filter(p -> p.targetBlock() != null && p.targetBlock().contains("glass"))
                .toList();
        assertFalse(glass.isEmpty());
        assertTrue(glass.stream().allMatch(p -> p.dz() == 9), "NORTH-only facade_side must stay on maxZ");
        assertTrue(glass.stream().noneMatch(p -> p.dx() == 0 || p.dx() == 11));
    }

    @Test
    void tailiangCapsBayDensityAndDefaultsWithoutInteriorForest() {
        Map<String, Object> params = new HashMap<>();
        params.put("width", 15);
        params.put("depth", 15);
        params.put("baysX", 5);
        params.put("baysZ", 4);
        params.put("anchorMode", "corner");
        params.put("includeSubEaves", false);
        params.put("includeBrackets", false);
        params.put("roofType", "wu_dian");
        var states = new HashMap<BlockPos, net.minecraft.block.BlockState>();
        TailiangTimberHallBuilder.generate(params, new BlockPos(0, 64, 0), null, null)
                .getBlocks().forEach(b -> states.put(b.getPos(), b.getTargetState()));
        // Interior cell near center should stay walkable (no log forest).
        assertTrue(states.getOrDefault(new BlockPos(7, 67, 7), Blocks.AIR.getDefaultState()).isAir()
                        || !states.get(new BlockPos(7, 67, 7)).isOf(Blocks.DARK_OAK_LOG),
                "default hall must not fill interior with columns");
    }

    @Test
    void tailiangDoubleEaveEmitsTwoRoofHeightBands() {
        Map<String, Object> params = new HashMap<>();
        params.put("width", 17);
        params.put("depth", 15);
        params.put("baysX", 3);
        params.put("baysZ", 3);
        params.put("anchorMode", "corner");
        params.put("double_eaves", true);
        params.put("eave_tiers", 2);
        params.put("includeSubEaves", true); // ignored when double_eaves wins
        params.put("includeBrackets", false);
        params.put("includeInteriorColumns", false);
        params.put("roofType", "wu_dian");
        params.put("hallHeight", 8);
        assertTrue(TailiangTimberHallBuilder.wantsDoubleEave(
                new com.formacraft.common.typology.TypologyParams(params)));
        var ys = new HashSet<Integer>();
        TailiangTimberHallBuilder.generate(params, new BlockPos(0, 64, 0), null, null)
                .getBlocks().forEach(b -> {
                    var s = b.getTargetState();
                    if (s.isOf(Blocks.DEEPSLATE_TILES) || s.isOf(Blocks.DEEPSLATE_BRICK_STAIRS)
                            || s.isOf(Blocks.DEEPSLATE_TILE_SLAB) || s.isOf(Blocks.STONE_BRICK_STAIRS)) {
                        ys.add(b.getPos().getY());
                    }
                });
        assertTrue(ys.size() >= 4, "重檐 needs distinct lower+upper roof Y bands, ys=" + ys);
        int min = ys.stream().mapToInt(Integer::intValue).min().orElse(0);
        int max = ys.stream().mapToInt(Integer::intValue).max().orElse(0);
        assertTrue(max - min >= 3, "upper tier should sit above lower eave, span=" + (max - min));
    }

    @Test
    void emptyComponentsRecoverFromSlotSize() {
        Slot slot = new Slot(
                "hall_slot",
                new Vec3i(0, 0, 0),
                GlobalConstraints.Facing.SOUTH,
                null, null, null,
                new Vec3i(12, 8, 10));
        LlmPlan plan = com.formacraft.common.llm.dto.LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .layout(new Layout(Layout.SkeletonType.COMPOUND, false, List.of(slot)))
                .components(List.of())
                .build();
        var prepared = ComponentSatelliteInference.prepareComponents(plan, Map.of("hall_slot", slot), false);
        assertFalse(prepared.components().isEmpty(), "slot.size must recover MASS_MAIN");
        assertTrue(prepared.components().stream()
                .anyMatch(c -> "MASS_MAIN".equalsIgnoreCase(c.componentType())));
    }

    @Test
    void emptyComponentsLandXieshanAndDarkOakFromIntent() {
        Slot slot = new Slot(
                "main",
                new Vec3i(0, 0, 0),
                GlobalConstraints.Facing.SOUTH,
                null, null, null,
                new Vec3i(12, 10, 16));
        Map<String, Object> hints = new HashMap<>();
        hints.put("architecture_intent", Map.of(
                "schema", "formacraft.architecture_intent.v1",
                "scopes", List.of(Map.of(
                        "scope", "global",
                        "text", "盖一座约 12×16 的中式殿堂，深色橡木墙，深色橡木楼梯歇山顶，南向正门"))));
        LlmPlan plan = com.formacraft.common.llm.dto.LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .layout(new Layout(Layout.SkeletonType.COMPOUND, false, List.of(slot)))
                .components(List.of())
                .distinguishingFeatures(List.of("飞檐", "歇山"))
                .proportionHints(hints)
                .build();
        var prepared = ComponentSatelliteInference.prepareComponents(plan, Map.of("main", slot), false);
        Component mass = prepared.components().stream()
                .filter(c -> "MASS_MAIN".equalsIgnoreCase(c.componentType()))
                .findFirst().orElseThrow();
        assertEquals("minecraft:dark_oak_planks", mass.params().get("wall_block"));
        Component roof = prepared.components().stream()
                .filter(c -> "ROOF".equalsIgnoreCase(c.componentType()))
                .findFirst().orElseThrow();
        String roofType = String.valueOf(roof.params().get("roof_type"));
        assertTrue(roofType.toLowerCase().contains("xieshan"), "expected xieshan, got " + roofType);
        assertTrue(String.valueOf(roof.params().get("roof_block")).contains("dark_oak"));
    }

    @Test
    void facadeRatioZeroKeepsEastWestSolid() {
        Map<String, Object> params = new HashMap<>();
        params.put("facade_ratio_north", 0.38);
        params.put("facade_ratio_south", 0.08);
        params.put("facade_ratio_east", 0.0);
        params.put("facade_ratio_west", 0.0);
        params.put("window_width", 2);
        params.put("window_height", 2);
        params.put("windows_per_wall", 2);
        params.put("floor_count", 1);
        params.put("floor_height", 4);
        params.put("sill_offset", 1);
        params.put("reserve_entrance_bay", false);
        assertEquals(Set.of("NORTH", "SOUTH"),
                FacadeScope.parseStrict(params, 1).faceNames());
        Component c = new Component(
                "FACADE_WINDOWS", "studio", new Vec3i(0, 0, 0),
                new Dimensions(12, 9, 4),
                List.of("east_west:no_openings"), params);
        Slot slot = new Slot("studio", new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH, null, null, null);
        List<BlockPatch> glass = new FacadeWindowsGenerator()
                .generate(new SemanticComponent("FACADE_WINDOWS", slot, c)).stream()
                .filter(p -> p.targetBlock() != null && p.targetBlock().contains("glass"))
                .toList();
        assertFalse(glass.isEmpty());
        assertTrue(glass.stream().noneMatch(p -> p.dx() == 0 || p.dx() == 11),
                "facade_ratio east/west=0 must not open E/W");
    }

    @Test
    void symmetricDoorWidthPrefersOddOnOddSpan() {
        assertEquals(1, com.formacraft.common.generation.component.impl.EntranceGenerator.symmetricDoorWidth(3));
        assertEquals(2, com.formacraft.common.generation.component.impl.EntranceGenerator.symmetricDoorWidth(6));
        assertEquals(3, com.formacraft.common.generation.component.impl.EntranceGenerator.symmetricDoorWidth(11));
        assertEquals(1, com.formacraft.common.generation.component.impl.EntranceGenerator.symmetricDoorWidth(5));
    }

    @Test
    void hipRoofFromWudianContract() {
        var hints = PlanIntentFidelityRepair.parse(com.formacraft.common.llm.dto.LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .distinguishingFeatures(List.of("庑殿顶四面落坡", "石砖楼梯瓦面"))
                .proportionHints(Map.of(
                        "architecture_intent", Map.of(
                                "scopes", List.of(Map.of("text", "石砖楼梯庑殿顶（四坡顶），四面落坡"))),
                        "chinese_roof_contract", Map.of(
                                "requirements", List.of(Map.of("property", "roofType", "value", "wu_dian")))))
                .build());
        assertEquals("hip", hints.roofType());
        assertEquals("minecraft:stone_brick_stairs", hints.roofBlock());
        assertTrue(hints.preferStairs());
    }

    @Test
    void slotSizeWhdRecoversMassInsteadOfEmptyOutput() {
        // Orchestrator often emits size:{w,h,d} which must deserialize into Vec3i.
        String json = """
                {"mode":"build","anchor":{"x":0,"y":64,"z":0},
                 "layout":{"slots":[{"slot_id":"main","anchor":{"x":0,"y":0,"z":0},
                   "facing":"SOUTH","size":{"w":12,"h":8,"d":10}}]},
                 "components":[],
                 "proportion_hints":{"architecture_intent":{"scopes":[{"text":
                   "盖一座约12×10的两层砖房，石砖墙，屋顶用双坡"}]}}}
                """;
        LlmPlan plan;
        try {
            plan = com.formacraft.common.llm.parser.LlmPlanParser.parse(json);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertNotNull(plan.layout().slots().getFirst().size());
        assertEquals(12, plan.layout().slots().getFirst().size().x());
        assertEquals(8, plan.layout().slots().getFirst().size().y());
        assertEquals(10, plan.layout().slots().getFirst().size().z());
        var prepared = ComponentSatelliteInference.prepareComponents(
                plan, Map.of("main", plan.layout().slots().getFirst()), false);
        assertFalse(prepared.components().isEmpty(), "w/h/d slot.size must recover MASS_MAIN");
        Component mass = prepared.components().stream()
                .filter(c -> "MASS_MAIN".equalsIgnoreCase(c.componentType()))
                .findFirst().orElseThrow();
        assertEquals(2, ((Number) mass.params().get("floor_count")).intValue());
        assertEquals(0, (mass.relativePosition().x() * 2 + mass.dimensions().width()) / 2,
                "footprint should be centered on origin");
    }

    @Test
    void emptyComponentsWithoutSlotSizeRecoversFromIntentFootprint() {
        // R1/F3 orchestrator regression: components=[] and slot has no size / no target_width.
        String json = """
                {"mode":"build","anchor":{"x":0,"y":71,"z":0},"plan_status":"ok",
                 "layout":{"skeleton_type":"COMPOUND","slots":[
                   {"slot_id":"main","anchor":{"x":0,"y":0,"z":0},"facing":"NORTH"}]},
                 "components":[],
                 "style_attributes":{"palette_roles":{
                   "wall":"minecraft:dark_oak_planks","roof_slope":"minecraft:oak_stairs"}},
                 "proportion_hints":{"architecture_intent":{"scopes":[{"text":
                   "盖一座约 12×10 的单层木构小厅，深色橡木墙，橡木楼梯坡屋顶（双坡/人字顶），屋脊沿东西向，南北两坡用楼梯方块，南侧正门一门两窗。普通建筑，不要地标。"}],
                   "original_text":"盖一座约 12×10 的单层木构小厅"}}}
                """;
        LlmPlan plan;
        try {
            plan = com.formacraft.common.llm.parser.LlmPlanParser.parse(json);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertNull(plan.layout().slots().getFirst().size());
        var prepared = ComponentSatelliteInference.prepareComponents(
                plan, Map.of("main", plan.layout().slots().getFirst()), false);
        assertFalse(prepared.components().isEmpty(), "intent 12×10 must recover MASS_MAIN without slot.size");
        Component mass = prepared.components().stream()
                .filter(c -> "MASS_MAIN".equalsIgnoreCase(c.componentType()))
                .findFirst().orElseThrow();
        assertEquals(12, mass.dimensions().width());
        assertEquals(10, mass.dimensions().depth());
        Component roof = prepared.components().stream()
                .filter(c -> "ROOF".equalsIgnoreCase(c.componentType()))
                .findFirst().orElseThrow();
        assertTrue(String.valueOf(roof.params().get("roof_block")).contains("stairs")
                        || Boolean.TRUE.equals(roof.params().get("prefer_stairs")),
                "stair roof intent must land on roof params");
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(patches.isEmpty(), "R1-like empty plan must compile; gap="
                + com.formacraft.server.assembly.AssemblyCompileDiagnostics.get());
    }

    @Test
    void emptyComponentsTwoStoreySquareRecoversFromIntent() {
        String json = """
                {"mode":"build","anchor":{"x":0,"y":71,"z":0},
                 "layout":{"slots":[{"slot_id":"main","anchor":{"x":0,"y":0,"z":0},"facing":"SOUTH"}]},
                 "components":[],
                 "distinguishing_features":["oak pilasters between window groups","dark oak gable roof"],
                 "proportion_hints":{"architecture_intent":{"scopes":[{"text":
                   "盖一座约 11×11 的两层方厅，橡木墙，四面都开窗，每面约2组窗，窗间保留立柱或实墙段，南向正门预留入口开间，深色橡木坡屋顶。不要玻璃盒子。"}]}}}
                """;
        LlmPlan plan;
        try {
            plan = com.formacraft.common.llm.parser.LlmPlanParser.parse(json);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        var prepared = ComponentSatelliteInference.prepareComponents(
                plan, Map.of("main", plan.layout().slots().getFirst()), false);
        Component mass = prepared.components().stream()
                .filter(c -> "MASS_MAIN".equalsIgnoreCase(c.componentType()))
                .findFirst().orElseThrow();
        assertEquals(11, mass.dimensions().width());
        assertEquals(11, mass.dimensions().depth());
        assertEquals(2, ((Number) mass.params().get("floor_count")).intValue());
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(patches.isEmpty(), "F3-like empty plan must compile; gap="
                + com.formacraft.server.assembly.AssemblyCompileDiagnostics.get());
    }

    @Test
    void slotPrimaryAnchorRepairZerosResidualOffset() {
        Slot slot = new Slot("studio_slot", new Vec3i(-8, 0, 0), GlobalConstraints.Facing.SOUTH,
                null, null, null, new Vec3i(12, 8, 12));
        LlmPlan plan = com.formacraft.common.llm.dto.LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .anchor(new Vec3i(1768, 72, -446))
                .layout(new Layout(Layout.SkeletonType.COMPOUND, false, List.of(slot)))
                .components(List.of())
                .build();
        LlmPlan fixed = SlotPrimaryAnchorRepair.apply(plan);
        assertEquals(0, fixed.layout().slots().getFirst().anchor().x());
        assertEquals(0, fixed.layout().slots().getFirst().anchor().z());
    }

    @Test
    void f4NorthFacadeStudioCompilesWithRidgeRise() throws Exception {
        String json = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of("src/test/resources/regressions/f4_north_facade_studio.json")));
        LlmPlan plan = com.formacraft.common.llm.parser.LlmPlanParser.parse(json);
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(patches.isEmpty(), "F4 must not fail RoofShapeAudit/E_ROOF_SHAPE_FAILED; gap="
                + com.formacraft.server.assembly.AssemblyCompileDiagnostics.get());
        int minY = patches.stream().mapToInt(BlockPatch::dy).min().orElse(0);
        int maxY = patches.stream().mapToInt(BlockPatch::dy).max().orElse(0);
        assertTrue(maxY - minY >= 6, "body+roof should span multiple Y bands, span=" + (maxY - minY));
        // Building footprint center near plan origin (slot -8 must not remain).
        double avgX = patches.stream().mapToInt(BlockPatch::dx).average().orElse(0);
        double avgZ = patches.stream().mapToInt(BlockPatch::dz).average().orElse(0);
        assertTrue(Math.abs(avgX) < 4 && Math.abs(avgZ) < 4,
                "centered on cursor, avgXZ=(" + avgX + "," + avgZ + ")");
    }

    @Test
    void oakStairGableForcedFromIntent() {
        Slot slot = new Slot("hall", new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH,
                null, null, null, new Vec3i(12, 5, 10));
        Map<String, Object> hints = new HashMap<>();
        hints.put("architecture_intent", Map.of("scopes", List.of(Map.of("text",
                "盖一座约12×10的单层木构小厅，深色橡木墙，橡木楼梯坡屋顶（双坡/人字顶），屋脊沿东西向，南北两坡用楼梯方块，南侧正门一门两窗"))));
        LlmPlan plan = com.formacraft.common.llm.dto.LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .layout(new Layout(Layout.SkeletonType.COMPOUND, false, List.of(slot)))
                .components(List.of())
                .distinguishingFeatures(List.of(
                        "gable (double-pitch / 人字) roof built from oak stair blocks",
                        "south facade: one centred door with a window on each side"))
                .styleAttributes(new com.formacraft.common.llm.dto.StyleAttributes(
                        null, null, null, null, null, null, null, null,
                        Map.of("wall", "minecraft:dark_oak_planks", "roof", "minecraft:oak_planks"),
                        null, null, null, null))
                .proportionHints(hints)
                .build();
        var prepared = ComponentSatelliteInference.prepareComponents(plan, Map.of("hall", slot), false);
        Component roof = prepared.components().stream()
                .filter(c -> "ROOF".equalsIgnoreCase(c.componentType()))
                .findFirst().orElseThrow();
        assertTrue(String.valueOf(roof.params().get("roof_block")).contains("stairs"),
                "oak planks roof must promote to stairs, got " + roof.params().get("roof_block"));
        assertEquals("x", String.valueOf(roof.params().get("ridge_axis")));
        Component mass = prepared.components().stream()
                .filter(c -> "MASS_MAIN".equalsIgnoreCase(c.componentType()))
                .findFirst().orElseThrow();
        assertEquals(2, ((Number) mass.params().getOrDefault("entrance_flank_windows", 0)).intValue());
    }

    @Test
    void parseDoorWidthBlocksHonorsZhengmenKuanSanGe() {
        assertEquals(3, PlanIntentFidelityRepair.parseDoorWidthBlocks("生成一座两层小楼，正门宽三格，门右侧加一扇玻璃窗"));
        assertEquals(2, PlanIntentFidelityRepair.parseDoorWidthBlocks("正门宽两格"));
    }

    @Test
    void threeBlockDoorSurvivesEntranceFidelityAndEmit() {
        Map<String, Object> hints = new HashMap<>();
        hints.put("architecture_intent", Map.of(
                "original_text", "生成一座两层小楼，墙体必须用白色混凝土，正门宽三格"));
        Map<String, Object> massParams = new HashMap<>();
        massParams.put("component_id", "house");
        massParams.put("anchor_mode", "min_corner");
        massParams.put("door_width", 2);
        massParams.put("floor_count", 2);
        Map<String, Object> doorParams = new HashMap<>();
        doorParams.put("host_id", "house");
        doorParams.put("facing", "SOUTH");
        doorParams.put("door_width", 2);
        doorParams.put("door_height", 3);
        doorParams.put("anchor_mode", "min_corner");
        Component mass = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(12, 10, 8), List.of(), massParams);
        Component entrance = new Component(
                "ENTRANCE", "main", new Vec3i(4, 0, 0),
                new Dimensions(3, 1, 4), List.of(), doorParams);
        LlmPlan plan = com.formacraft.common.llm.dto.LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .components(List.of(mass, entrance))
                .proportionHints(hints)
                .build();
        LlmPlan repaired = PlanIntentFidelityRepair.apply(plan);
        Component door = repaired.components().stream()
                .filter(c -> "ENTRANCE".equalsIgnoreCase(c.componentType()))
                .findFirst().orElseThrow();
        assertEquals(3, ((Number) door.params().get("door_width")).intValue());
        assertTrue(door.dimensions().width() >= 4,
                "opening box must be ≥ door+1, got " + door.dimensions().width());
        long removes = new com.formacraft.common.generation.component.impl.EntranceGenerator()
                .generate(new SemanticComponent("ENTRANCE", null, door)).stream()
                .filter(p -> BlockPatch.REMOVE.equals(p.action()))
                .count();
        // 3-wide × door_height 3 × depth 1 = 9 air cells when span holds door_width=3.
        assertEquals(9, removes);
    }

    @Test
    void officeDropsInventedCircleAndExactStoreyStack() {
        Map<String, Object> hints = new HashMap<>();
        hints.put("architecture_intent", Map.of(
                "original_text", "生成一座三层玻璃幕墙办公楼"));
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", "office");
        params.put("anchor_mode", "min_corner");
        params.put("shape", "circle");
        params.put("floor_count", 3);
        params.put("floor_height", 4);
        Component mass = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(10, 10, 14), List.of(), params);
        LlmPlan repaired = PlanIntentFidelityRepair.apply(
                com.formacraft.common.llm.dto.LlmPlanTestFixtures.builder()
                        .mode(LlmPlan.Mode.build)
                        .components(List.of(mass))
                        .proportionHints(hints)
                        .build());
        Component out = repaired.components().getFirst();
        assertEquals("rectangle", String.valueOf(out.params().get("shape")));
        assertEquals(3, ((Number) out.params().get("floor_count")).intValue());
        assertEquals(4, ((Number) out.params().get("ground_clear_height")).intValue());
        int needH = 4 + 2 * 3 + 1;
        assertEquals(needH, out.dimensions().height());
        var layout = com.formacraft.common.generation.component.util.ResolvedFloorLayout
                .from(out, out.dimensions().height());
        assertEquals(List.of(0, 4, 7), layout.floorYs());
    }
}
