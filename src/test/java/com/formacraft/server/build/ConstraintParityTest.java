package com.formacraft.server.build;

import com.formacraft.common.buildcontext.OutlineShape;
import com.formacraft.common.buildcontext.SelectionBox;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.model.constraint.ProtectedZone;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.filter.*;
import com.formacraft.common.patch.filter.impl.*;
import com.formacraft.common.tool.MirrorSymmetryMode;
import com.formacraft.common.tool.ToolConstraintSnapshot;
import com.formacraft.common.world.MutationRegionPolicy;
import com.formacraft.server.compiler.ComponentPlanCompiler;
import com.formacraft.server.patch.ServerPatchFilter;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Region parity is separate from symmetry expansion and Build-only brush/path inputs. */
class ConstraintParityTest {
    static List<String> stylePlanNames(com.fasterxml.jackson.databind.JsonNode style) {
        var names = new ArrayList<String>();
        for (var name : style.path("plans")) names.add(name.asText());
        for (var variant : style.path("variant_cards"))
            for (var name : variant.path("plans")) names.add(name.asText());
        for (var name : style.path("diversity_plans")) names.add(name.asText());
        return names;
    }

    static java.util.stream.Stream<String> stylePackPlans() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try (var input = ConstraintParityTest.class.getResourceAsStream(
                "/assets/formacraft/style_profiles/style_pack_manifest_v1.json")) {
            assertNotNull(input);
            var names = new ArrayList<String>();
            for (var style : mapper.readTree(input).get("styles"))
                names.addAll(stylePlanNames(style));
            assertFalse(names.isEmpty());
            assertEquals(names.size(), new HashSet<>(names).size(), "duplicate style fixture names");
            return names.stream();
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("stylePackPlans")
    void authoredStylePackPlansCompileAndPreserveMaterialsAcrossClippers(String fixture) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var plan = mapper.readValue(java.nio.file.Path.of("python_backend/eval/fixtures/plans", fixture).toFile(), LlmPlan.class);
        List<BlockPatch> raw;
        var first = plan.components().getFirst();
        var typology = com.formacraft.common.typology.TypologyComponentRouter.extractTypologyId(first);
        if (typology != null) {
            // Native routers require a live ServerWorld. Exercise their pure builders here;
            // compositional plans below exercise the complete offline compiler.
            String builderName = java.util.Arrays.stream(typology.split("_"))
                    .map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1))
                    .collect(java.util.stream.Collectors.joining()) + "Builder";
            var builder = Class.forName("com.formacraft.server.generation.typology.builder." + builderName);
            var generate = builder.getMethod("generate", Map.class, BlockPos.class,
                    net.minecraft.server.world.ServerWorld.class, com.formacraft.common.model.build.BuildingSpec.class);
            var parameters = com.formacraft.common.typology.TypologyParamResolver.merge(typology, first);
            var structure = (com.formacraft.common.build.GeneratedStructure) generate.invoke(null, parameters, ORIGIN, null, null);
            raw = com.formacraft.common.typology.TypologyPatchBridge.toBlockPatches(structure, ORIGIN);
        } else {
            raw = ComponentPlanCompiler.compile(plan, ORIGIN, null, null, false);
        }
        assertFalse(raw.isEmpty(), () -> fixture + ": " + com.formacraft.server.assembly.AssemblyCompileDiagnostics.get());
        try (var input = getClass().getResourceAsStream("/assets/formacraft/style_profiles/style_pack_manifest_v1.json")) {
            for (var style : mapper.readTree(input).get("styles")) {
                if (!stylePlanNames(style).contains(fixture)) continue;
                if (!style.get("typology_id").isNull()) {
                    String typologyId = style.get("typology_id").asText();
                    var definition = com.formacraft.common.typology.StructuralTypologyRegistry.getById(typologyId);
                    assertNotNull(definition, fixture + " missing typology: " + typologyId);
                    if ("compositional_only".equals(definition.routingPolicy())) {
                        assertNull(definition.interpreterId(), fixture + " must not claim a native interpreter");
                        assertNull(typology, fixture + " must not carry native component routing");
                        assertTrue(plan.components().stream().noneMatch(component ->
                                Set.of("STRUCTURE", "MODULE").contains(component.componentType())), fixture);
                    }
                }
                var materials = raw.stream().map(BlockPatch::targetBlock)
                        .map(id -> id.split("\\[")[0]).collect(java.util.stream.Collectors.toSet());
                assertTrue(materials.contains(style.get("expected_wall").asText()), fixture + " wall: " + materials);
                try (var genome = getClass().getResourceAsStream("/assets/formacraft/style_genomes/"
                        + style.get("genome_id").asText() + ".json")) {
                    var palette = mapper.readTree(genome).get("palette");
                    assertEquals(12, palette.size(), fixture + " palette roles");
                    for (var block : palette) {
                        var id = net.minecraft.util.Identifier.of(block.asText());
                        assertTrue(net.minecraft.registry.Registries.BLOCK.containsId(id), fixture + " unregistered material: " + id);
                        assertFalse(net.minecraft.registry.Registries.BLOCK.get(id).getDefaultState().isAir(), fixture + " air material: " + id);
                    }
                    assertInstanceOf(net.minecraft.block.DoorBlock.class, net.minecraft.registry.Registries.BLOCK.get(
                            net.minecraft.util.Identifier.of(palette.get("door").asText())), fixture);
                    assertInstanceOf(net.minecraft.block.StairsBlock.class, net.minecraft.registry.Registries.BLOCK.get(
                            net.minecraft.util.Identifier.of(palette.get("stairs").asText())), fixture);
                    assertInstanceOf(net.minecraft.block.SlabBlock.class, net.minecraft.registry.Registries.BLOCK.get(
                            net.minecraft.util.Identifier.of(palette.get("roof_slab").asText())), fixture);
                    assertTrue(java.util.stream.Stream.of("roof", "stairs", "roof_slab")
                            .anyMatch(role -> materials.contains(palette.get(role).asText())), fixture + " roof: " + materials);
                }
            }
        }
        int minX = raw.stream().mapToInt(BlockPatch::dx).min().orElseThrow();
        int maxX = raw.stream().mapToInt(BlockPatch::dx).max().orElseThrow();
        var min = ORIGIN.add(minX, -512, -512);
        var max = ORIGIN.add(minX + (maxX - minX) / 2, 512, 512);
        var expected = allowed(raw, ORIGIN, new BuildConstraints(min, max, null, List.of()));
        assertFalse(expected.isEmpty(), fixture);
        assertTrue(expected.size() < raw.size(), fixture);
        assertEquals(expected, ServerPatchFilter.filter(ORIGIN, raw, List.of(), null, true, min, max).accepted);
        assertEquals(expected, PatchFilterPipeline.createDefault().apply(raw, ORIGIN,
                new PatchFilterContext(snapshot(new SelectionBox(min, max), null, List.of()))));
    }

    private static final BlockPos ORIGIN = new BlockPos(100, 64, -40);
    @BeforeAll static void bootstrap() { MinecraftRegistryTestBootstrap.initialize(); }

    @ParameterizedTest
    @CsvSource({"13,17", "21,15"})
    void huizhouGablesCompileAsSteppedWallsAndEditsStayOnEndPlanes(int width, int depth) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var file = java.nio.file.Path.of("python_backend/eval/fixtures/plans/huizhou_vernacular_" + width + ".json");
        var enabledPlan = mapper.readValue(file.toFile(), LlmPlan.class);
        var enabled = ComponentPlanCompiler.compile(enabledPlan, ORIGIN, null, null, false);
        assertFalse(enabled.isEmpty(), () -> String.valueOf(com.formacraft.server.assembly.AssemblyCompileDiagnostics.get()));
        var on = com.formacraft.test.PatchTestSnapshot.blocks(enabled);
        var disabledPlan = mapper.readValue(file.toFile(), LlmPlan.class);
        disabledPlan.components().get(1).params().put("horse_head_walls", false);
        var disabled = ComponentPlanCompiler.compile(disabledPlan, ORIGIN, null, null, false);
        assertFalse(disabled.isEmpty());
        var off = com.formacraft.test.PatchTestSnapshot.blocks(disabled);
        boolean alongDepth = depth >= width;
        int span = Math.min(width, depth), length = Math.max(width, depth);
        var tops = new ArrayList<Integer>();
        for (int across = 0; across < span; across++) {
            int x = alongDepth ? across : 0, z = alongDepth ? 0 : across;
            int top = on.keySet().stream().filter(p -> p.x() == x && p.z() == z)
                    .mapToInt(Vec3i::y).max().orElseThrow();
            tops.add(top);
            assertEquals("minecraft:deepslate_tiles", on.get(new Vec3i(x, top, z)));
            assertEquals("minecraft:white_concrete", on.get(new Vec3i(x, top - 1, z)));
            for (int y = 5; y <= top; y++) {
                assertNotNull(on.get(new Vec3i(x, y, z)), "gable wall must remain attached");
                assertEquals(on.get(new Vec3i(x, y, z)), on.get(new Vec3i(
                        alongDepth ? across : length - 1, y, alongDepth ? length - 1 : across)));
            }
        }
        assertTrue(new HashSet<>(tops).size() >= 3, "must have multiple terraces");
        assertTrue(tops.get(span / 2) > tops.getFirst());
        for (int i = 0; i < span; i++) assertEquals(tops.get(i), tops.get(span - 1 - i));
        var all = new HashSet<>(on.keySet()); all.addAll(off.keySet());
        int changed = 0;
        for (var pos : all) if (!Objects.equals(on.get(pos), off.get(pos))) {
            changed++;
            assertTrue(pos.y() >= 5 && (alongDepth ? pos.z() == 0 || pos.z() == depth - 1
                    : pos.x() == 0 || pos.x() == width - 1), "edit escaped gable planes: " + pos);
        }
        assertTrue(changed > 0, "disabling gables must change compiled blocks");
        assertNull(on.get(new Vec3i(width / 2, 6, depth / 2)), "attic interior must remain hollow");
        var min = ORIGIN.add(0, -32, 0); var max = ORIGIN.add(width / 2, 32, depth - 1);
        var expected = allowed(enabled, ORIGIN, new BuildConstraints(min, max, null, List.of()));
        assertFalse(expected.isEmpty()); assertTrue(expected.size() < enabled.size());
        assertEquals(expected, ServerPatchFilter.filter(ORIGIN, enabled, List.of(), null, true, min, max).accepted);
        assertEquals(expected, PatchFilterPipeline.createDefault().apply(enabled, ORIGIN,
                new PatchFilterContext(snapshot(new SelectionBox(min, max), null, List.of()))));
    }

    @Test void allP1GenomeRolesReachTheRuntimeAdapterWithoutFallback() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var ids = new HashSet<>(List.of("default", "rustic_wood", "medieval_stone", "mingqing_official",
                "modern_minimal", "futuristic_clean"));
        try (var input = getClass().getResourceAsStream("/assets/formacraft/style_profiles/style_pack_manifest_v1.json")) {
            for (var style : mapper.readTree(input).get("styles")) ids.add(style.get("genome_id").asText());
        }
        assertTrue(ids.size() >= 20);
        for (String id : ids) {
            var genome = com.formacraft.common.style.StyleGenomeRegistry.load(id);
            assertEquals(id, genome.id);
            var adapter = new com.formacraft.common.style.profile.GenomeStyleProfile(genome,
                    com.formacraft.common.model.build.BuildingStyle.DEFAULT).palette();
            var actual = Map.ofEntries(Map.entry("wall", adapter.wall), Map.entry("roof", adapter.roof),
                    Map.entry("floor", adapter.floor), Map.entry("window", adapter.window),
                    Map.entry("foundation", adapter.foundation), Map.entry("trim", adapter.trim),
                    Map.entry("pillar", adapter.pillar), Map.entry("cap", adapter.cap),
                    Map.entry("door", adapter.door), Map.entry("roof_trim", adapter.roofTrim),
                    Map.entry("stairs", adapter.stairs), Map.entry("roof_slab", adapter.roofSlab));
            try (var input = getClass().getResourceAsStream("/assets/formacraft/style_genomes/" + id + ".json")) {
                var expected = mapper.readTree(input).get("palette");
                assertEquals(12, expected.size(), id);
                for (var role : actual.entrySet()) {
                    assertEquals(expected.get(role.getKey()).asText(), role.getValue(), id + ": " + role.getKey());
                    assertTrue(net.minecraft.registry.Registries.BLOCK.containsId(
                            net.minecraft.util.Identifier.of(role.getValue())), id + ": " + role);
                }
            }
        }
    }

    @Test void mixedGardenAndChapelGoldenCompilesWithSeparateRoofBindings() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var file = java.nio.file.Path.of("python_backend/eval/fixtures/plans/mixed_garden_chapel.json").toFile();
        var plan = mapper.readValue(file, LlmPlan.class);
        var expectedTypes = Map.of("garden", "hip", "chapel", "gable");
        for (var roof : plan.components()) {
            if (!"ROOF".equals(roof.componentType())) continue;
            assertEquals(expectedTypes.get(roof.params().get("host_id")), roof.params().get("roof_type"));
        }
        var raw = ComponentPlanCompiler.compile(plan, ORIGIN, null, null, false);
        assertFalse(raw.isEmpty(), () -> String.valueOf(com.formacraft.server.assembly.AssemblyCompileDiagnostics.get()));
        assertTrue(raw.stream().anyMatch(p -> p.dx() < 20));
        assertTrue(raw.stream().anyMatch(p -> p.dx() >= 30));
        var min = ORIGIN.add(-8, -16, -16);
        var max = ORIGIN.add(24, 64, 32);
        var clipped = allowed(raw, ORIGIN, new BuildConstraints(min, max, null, List.of()));
        assertFalse(clipped.isEmpty());
        assertTrue(clipped.size() < raw.size());
        assertEquals(clipped, ServerPatchFilter.filter(ORIGIN, raw, List.of(), null, true, min, max).accepted);
        assertEquals(clipped, PatchFilterPipeline.createDefault().apply(raw, ORIGIN,
                new PatchFilterContext(snapshot(new SelectionBox(min, max), null, List.of()))));
    }

    private ToolConstraintSnapshot snapshot(SelectionBox selection, OutlineShape outline, List<ProtectedZone> zones) {
        return new ToolConstraintSnapshot(selection, outline, List.of(), zones,
                ToolConstraintSnapshot.SymmetryConstraint.none());
    }

    private List<BlockPatch> probes() {
        List<BlockPatch> result = new ArrayList<>();
        for (int x = -3; x <= 3; x++) for (int y = -1; y <= 5; y++) for (int z = -3; z <= 3; z++)
            result.add(new BlockPatch(BlockPatch.REPLACE, x, y, z, "minecraft:blue_stained_glass"));
        return result;
    }

    private List<BlockPatch> allowed(List<BlockPatch> input, BlockPos origin, BuildConstraints constraints) {
        return input.stream().filter(p -> constraints.allow(origin.add(p.dx(), p.dy(), p.dz()))).toList();
    }

    @Test void defaultPipelineAndBuildExposeExactlyTheSameSharedRules() {
        assertEquals(Set.of(MutationRegionPolicy.Constraint.BOUNDS, MutationRegionPolicy.Constraint.OUTLINE,
                MutationRegionPolicy.Constraint.PROTECTED_ZONES), BuildConstraints.sharedRegionConstraints());
        assertEquals(BuildConstraints.sharedRegionConstraints(), PatchFilterPipeline.createDefault().regionConstraints());
        // The inventory detects a removed filter, and the behavior test also sees its escaped cells.
        var missingBounds = new PatchFilterPipeline().add(new ForbiddenZoneFilter()).add(new OutlineClipFilter());
        assertNotEquals(BuildConstraints.sharedRegionConstraints(), missingBounds.regionConstraints());
        var selection = new SelectionBox(ORIGIN.add(-2, 0, -2), ORIGIN.add(2, 4, 2));
        var context = new PatchFilterContext(snapshot(selection, null, List.of()));
        assertEquals(343, missingBounds.apply(probes(), ORIGIN, context).size());
        assertEquals(125, PatchFilterPipeline.createDefault().apply(probes(), ORIGIN, context).size());
    }

    @Test void clippingMatrixAgreesWithIndependentBoundaryCounts() {
        var selection = new SelectionBox(ORIGIN.add(-2, 0, -2), ORIGIN.add(2, 4, 2));
        var circle = new OutlineShape("circle", List.of(), ORIGIN, 2, 64, 68);
        var polygon = new OutlineShape("polygon", List.of(ORIGIN.add(-2, 0, -2), ORIGIN.add(2, 0, -2),
                ORIGIN.add(2, 0, 2), ORIGIN.add(-2, 0, 2)), null, 0, 64, 68);
        var zone = new ProtectedZone(ORIGIN, ORIGIN.add(0, 4, 0));
        var snapshots = List.of(snapshot(null, null, List.of()), snapshot(selection, null, List.of()),
                snapshot(null, circle, List.of()), snapshot(null, polygon, List.of()),
                snapshot(null, null, List.of(zone)), snapshot(selection, circle, List.of(zone)),
                snapshot(new SelectionBox(selection.max(), selection.min()), null, List.of()),
                snapshot(null, new OutlineShape("polygon", List.of(), null, 0, 64, 68), List.of()));
        int[] expected = {343, 125, 65, 80, 338, 60, 125, 0};
        for (int i = 0; i < snapshots.size(); i++) {
            var input = snapshots.get(i);
            var box = input.selection == null ? null : input.selection.normalized();
            var build = new BuildConstraints(box == null ? null : box.min(), box == null ? null : box.max(),
                    input.outline, input.protectedZones);
            var clipped = PatchFilterPipeline.createDefault().apply(probes(), ORIGIN, new PatchFilterContext(input));
            assertEquals(expected[i], clipped.size(), "case " + i);
            assertEquals(allowed(probes(), ORIGIN, build), clipped, "case " + i);
        }
        assertFalse(new BuildConstraints(null, null, null, List.of()).allow(null));
    }

    @Test void serverPatchModesMatchTheirEffectiveBuildRegion() {
        var selection = new SelectionBox(ORIGIN.add(-2, 0, -2), ORIGIN.add(2, 4, 2));
        var outsideOutline = new OutlineShape("circle", List.of(), ORIGIN.add(50, 0, 0), 2, 64, 68);
        // MODIFY_REGION intentionally ignores the outline, even when it is still present in tool state.
        var patch = ServerPatchFilter.filter(ORIGIN, probes(), List.of(), outsideOutline, true,
                selection.max(), selection.min());
        assertEquals(125, patch.accepted.size());
        assertEquals(allowed(probes(), ORIGIN, new BuildConstraints(selection.min(), selection.max(), null, List.of())),
                patch.accepted);
        var outline = new OutlineShape("circle", List.of(), ORIGIN, 2, 64, 68);
        var outlineMode = ServerPatchFilter.filter(ORIGIN, probes(), List.of(), outline, false,
                selection.min(), selection.max());
        assertEquals(65, outlineMode.accepted.size());
        assertEquals(allowed(probes(), ORIGIN, new BuildConstraints(null, null, outline, List.of())), outlineMode.accepted);
    }

    @ParameterizedTest
    @CsvSource({"Modern_International,flat,pane", "Gothic_Cathedral,gable,stained", "Chinese_Imperial_Official,hip,lattice"})
    void sameStylePlanRetainsIdenticalPatchesAndMaterialsAfterBothClippers(String style, String roof, String window) {
        var body = new Component("MASS_MAIN", "body", new Vec3i(0, 0, 0), new Dimensions(13, 11, 10), List.of(),
                Map.of("component_id", "body", "anchor_mode", "min_corner", "roof_type", roof,
                        "window_style", window, "wall_block", "minecraft:stone_bricks",
                        "roof_block", "minecraft:deepslate_tiles", "window_block", "minecraft:blue_stained_glass",
                        "plan_type", "rectangle", "window_ratio", 0.35));
        var plan = LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).styleProfile(style)
                .anchor(new Vec3i(0, 0, 0)).layout(new Layout(null, false, List.of()))
                .components(List.of(body)).build();
        var raw = ComponentPlanCompiler.compile(plan, ORIGIN, null, null, false);
        assertFalse(raw.isEmpty(), () -> style + ": " + com.formacraft.server.assembly.AssemblyCompileDiagnostics.get());
        var min = ORIGIN.add(1, 0, 1);
        var max = ORIGIN.add(10, 18, 9);
        var zones = List.of(new ProtectedZone(ORIGIN.add(4, 0, 0), ORIGIN.add(6, 18, 11)));
        var build = new BuildConstraints(min, max, null, zones);
        var expected = allowed(raw, ORIGIN, build);
        assertFalse(expected.isEmpty(), style);
        assertTrue(expected.size() < raw.size(), "fixture must exercise clipping: " + style);
        assertEquals(expected, ServerPatchFilter.filter(ORIGIN, raw, zones, null, true, min, max).accepted);
        assertEquals(expected, PatchFilterPipeline.createDefault().apply(raw, ORIGIN,
                new PatchFilterContext(snapshot(new SelectionBox(min, max), null, zones))));
    }

    @Test void symmetryExpansionIsClippedAfterGenerationIncludingItsMirroredCells() {
        var forbidden = new ProtectedZone(ORIGIN.add(-1, 0, -1), ORIGIN.add(-1, 4, 1));
        var snapshot = new ToolConstraintSnapshot(null, null, List.of(), List.of(forbidden),
                new ToolConstraintSnapshot.SymmetryConstraint(MirrorSymmetryMode.MIRROR_X, null, null));
        var original = new BlockPatch(BlockPatch.PLACE, 1, 2, 0, "minecraft:deepslate_tiles");
        var expanded = new SymmetryFilter().filter(List.of(original), ORIGIN, new PatchFilterContext(snapshot));
        assertEquals(2, expanded.size());
        var build = new BuildConstraints(null, null, null, List.of(forbidden));
        assertEquals(List.of(original), allowed(expanded, ORIGIN, build));
        assertEquals(List.of(original), PatchFilterPipeline.createDefault().apply(List.of(original), ORIGIN,
                new PatchFilterContext(snapshot)));
    }

    @Test void buildOnlyBrushFallbackAndCorridorRemainExplicitAndDoNotDisappearFromBuild() {
        var brushMin = ORIGIN.add(-2, 0, -2); var brushMax = ORIGIN.add(2, 4, 2);
        var brush = new BuildConstraints(null, null, null, List.of(), brushMin, brushMax, null, 0);
        assertEquals(125, allowed(probes(), ORIGIN, brush).size());
        var selected = new BuildConstraints(ORIGIN, ORIGIN.add(1, 4, 1), null, List.of(),
                brushMin, brushMax, null, 0);
        assertEquals(20, allowed(probes(), ORIGIN, selected).size());
        var corridor = new BuildConstraints(null, null, null, List.of(), null, null,
                List.of(ORIGIN.add(-2, 0, 0), ORIGIN.add(2, 0, 0)), 1);
        assertTrue(corridor.allow(ORIGIN.add(0, 3, 1)));
        assertFalse(corridor.allow(ORIGIN.add(0, 3, 2)));
    }
}
