package com.formacraft.common.compiler.postprocess;

import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.PatchBlock;
import com.formacraft.common.llm.dto.PatchBlockSection;
import com.formacraft.common.llm.dto.StyleAttributes;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 01.2 — local edit isolation (P1-03): aesthetic expansion requires {@code mode=patch}.
 */
class IncrementalEditScopeTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void buildModeOpeningSatellitesDoNotSuppressAesthetic() {
        LlmPlan build = plan(LlmPlan.Mode.build, List.of(facadeWindows()), null);
        assertFalse(IncrementalEditScope.suppressesAestheticExpansion(build),
                "standalone build-mode façade is not an incremental edit");
        assertFalse(PostProcessPipeline.isOpeningOnlyPatchPlan(build));
    }

    @Test
    void patchModeOpeningSatellitesSuppressAesthetic() {
        LlmPlan patch = plan(LlmPlan.Mode.patch, List.of(facadeWindows()), null);
        assertTrue(IncrementalEditScope.suppressesAestheticExpansion(patch));
        assertTrue(IncrementalEditScope.isOpeningOrFacadeSatelliteEdit(patch));
        assertTrue(PostProcessPipeline.isOpeningOnlyPatchPlan(patch));
    }

    @Test
    void patchModeMassRewriteDoesNotSuppressAesthetic() {
        Component mass = new Component("MASS_MAIN", "s", new Vec3i(0, 0, 0),
                new Dimensions(11, 11, 8), List.of(), Map.of("component_id", "hall"));
        LlmPlan patch = plan(LlmPlan.Mode.patch, List.of(mass), null);
        assertFalse(IncrementalEditScope.suppressesAestheticExpansion(patch));
    }

    @Test
    void patchModeLocalWallRecolorWithMassContextStillSuppressesAndBoundsOmega() {
        // Phase 01 closeout P1-03: MASS context + patch.blocks must not clear Ω / re-enable aesthetic.
        Component mass = new Component("MASS_MAIN", "s", new Vec3i(0, 0, 0),
                new Dimensions(11, 11, 8), List.of(), Map.of("component_id", "hall"));
        var section = new PatchBlockSection(new Vec3i(0, 0, 0), List.of(
                new PatchBlock("place", 2, 1, 0, "minecraft:red_concrete"),
                new PatchBlock("place", 3, 1, 0, "minecraft:red_concrete")));
        LlmPlan patch = plan(LlmPlan.Mode.patch, List.of(mass), section);
        assertTrue(IncrementalEditScope.suppressesAestheticExpansion(patch));
        Set<BlockPos> omega = IncrementalEditScope.authorizedWriteCells(patch);
        assertEquals(Set.of(new BlockPos(2, 1, 0), new BlockPos(3, 1, 0)), omega,
                "Ω must stay patch.blocks only — not cleared by MASS and not full AABB");

        List<BlockPatch> baseline = new ArrayList<>();
        for (int x = 0; x < 6; x++) {
            for (int y = 0; y < 4; y++) {
                baseline.add(new BlockPatch(BlockPatch.PLACE, x, y, 0, "minecraft:stone_bricks"));
            }
        }
        List<BlockPatch> input = new ArrayList<>(baseline);
        input.add(new BlockPatch(BlockPatch.PLACE, 2, 1, 0, "minecraft:red_concrete"));
        input.add(new BlockPatch(BlockPatch.PLACE, 3, 1, 0, "minecraft:red_concrete"));
        var volume = new PostProcessContext.BuildingVolume("s",
                new ComponentFootprintUtil.Bounds(0, 0, 0, 6, 4, 2), 3);
        var attrs = new StyleAttributes(null, null, null, null, null, null, null, null,
                null, null, null, null, null, "deep", "classic", "subtle", "warm_lantern", "high");
        LlmPlan styled = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .mode(LlmPlan.Mode.patch)
                .styleProfile("GOTHIC")
                .styleAttributes(attrs)
                .components(List.of(mass))
                .patch(section)
                .build();
        PostProcessContext ctx = PostProcessContext.create(styled, BlockPos.ORIGIN, List.of(volume));
        List<BlockPatch> out = PostProcessPipeline.createDefault(ctx).process(input, ctx);
        Set<BlockPos> delta = IncrementalEditScope.deltaCells(baseline, out);
        assertTrue(omega.containsAll(delta), "Δ must be ⊆ Ω; delta=" + delta + " omega=" + omega);
    }

    @Test
    void patchModeBoundedGlassBlocksSuppressAesthetic() {
        var section = new PatchBlockSection(new Vec3i(0, 0, 0), List.of(
                new PatchBlock("place", 1, 2, 0, "minecraft:glass"),
                new PatchBlock("place", 2, 2, 0, "minecraft:glass"),
                new PatchBlock("remove", 1, 2, 0, "minecraft:air"),
                new PatchBlock("remove", 2, 2, 0, "minecraft:air")));
        LlmPlan patch = plan(LlmPlan.Mode.patch, List.of(), section);
        assertTrue(IncrementalEditScope.isBoundedOpeningPatchBlocksEdit(patch));
        assertTrue(IncrementalEditScope.suppressesAestheticExpansion(patch));
    }

    @Test
    void patchModeMaterialRecolorIsOwnEditClassAndSuppressesAesthetic() {
        var section = new PatchBlockSection(new Vec3i(0, 0, 0), List.of(
                new PatchBlock("place", 0, 1, 0, "minecraft:red_concrete"),
                new PatchBlock("place", 1, 1, 0, "minecraft:red_concrete")));
        LlmPlan patch = plan(LlmPlan.Mode.patch, List.of(), section);
        assertFalse(IncrementalEditScope.isBoundedOpeningPatchBlocksEdit(patch),
                "wall recolor is a separate edit class — not opening-only");
        assertTrue(IncrementalEditScope.isMaterialRecolorPatchBlocksEdit(patch));
        assertEquals(IncrementalEditScope.EditClass.MATERIAL_RECOLOR, IncrementalEditScope.classify(patch));
        // Phase 01.9: any defined Ω suppresses aesthetic growth under mode=patch.
        assertTrue(IncrementalEditScope.suppressesAestheticExpansion(patch));
        Set<BlockPos> omega = IncrementalEditScope.authorizedWriteCells(patch);
        assertEquals(Set.of(new BlockPos(0, 1, 0), new BlockPos(1, 1, 0)), omega);
    }

    @Test
    void patchModeLocalRemoveAndMixedEditClasses() {
        var removeOnly = new PatchBlockSection(new Vec3i(0, 0, 0), List.of(
                new PatchBlock("remove", 2, 2, 0, "minecraft:air"),
                new PatchBlock("remove", 3, 2, 0, "minecraft:air")));
        LlmPlan removePlan = plan(LlmPlan.Mode.patch, List.of(), removeOnly);
        assertTrue(IncrementalEditScope.isLocalRemovePatchBlocksEdit(removePlan));
        assertEquals(IncrementalEditScope.EditClass.LOCAL_REMOVE, IncrementalEditScope.classify(removePlan));
        assertTrue(IncrementalEditScope.suppressesAestheticExpansion(removePlan));

        var mixed = new PatchBlockSection(new Vec3i(0, 0, 0), List.of(
                new PatchBlock("place", 0, 1, 0, "minecraft:red_concrete"),
                new PatchBlock("remove", 1, 1, 0, "minecraft:air"),
                new PatchBlock("place", 2, 1, 0, "minecraft:glass")));
        LlmPlan mixedPlan = plan(LlmPlan.Mode.patch, List.of(), mixed);
        assertEquals(IncrementalEditScope.EditClass.MIXED, IncrementalEditScope.classify(mixedPlan));
        assertTrue(IncrementalEditScope.suppressesAestheticExpansion(mixedPlan));
    }

    @Test
    void patchModeRecolorDeltaIsSubsetOfAuthorizedOmega() {
        // Baseline wall, then recolor two cells; aesthetic must not write outside Ω.
        List<BlockPatch> baseline = new ArrayList<>();
        for (int x = 0; x < 6; x++) {
            for (int y = 0; y < 4; y++) {
                baseline.add(new BlockPatch(BlockPatch.PLACE, x, y, 0, "minecraft:stone_bricks"));
            }
        }
        var section = new PatchBlockSection(new Vec3i(0, 0, 0), List.of(
                new PatchBlock("place", 2, 1, 0, "minecraft:red_concrete"),
                new PatchBlock("place", 3, 1, 0, "minecraft:red_concrete")));
        LlmPlan patch = plan(LlmPlan.Mode.patch, List.of(), section);
        Set<BlockPos> omega = IncrementalEditScope.authorizedWriteCells(patch);
        assertFalse(omega.isEmpty());

        List<BlockPatch> input = new ArrayList<>(baseline);
        input.add(new BlockPatch(BlockPatch.PLACE, 2, 1, 0, "minecraft:red_concrete"));
        input.add(new BlockPatch(BlockPatch.PLACE, 3, 1, 0, "minecraft:red_concrete"));

        var volume = new PostProcessContext.BuildingVolume("s",
                new ComponentFootprintUtil.Bounds(0, 0, 0, 6, 4, 2), 3);
        var attrs = new StyleAttributes(null, null, null, null, null, null, null, null,
                null, null, null, null, null, "deep", "classic", "subtle", "warm_lantern", "high");
        LlmPlan styled = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .mode(LlmPlan.Mode.patch)
                .styleProfile("GOTHIC")
                .styleAttributes(attrs)
                .patch(section)
                .build();
        PostProcessContext ctx = PostProcessContext.create(styled, BlockPos.ORIGIN, List.of(volume));
        List<BlockPatch> out = PostProcessPipeline.createDefault(ctx).process(input, ctx);

        Map<String, String> before = cellMap(baseline);
        Map<String, String> after = cellMap(out);
        Set<BlockPos> delta = new java.util.HashSet<>();
        for (var e : after.entrySet()) {
            String prev = before.get(e.getKey());
            if (!java.util.Objects.equals(prev, e.getValue())) {
                String[] p = e.getKey().split(",");
                delta.add(new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])));
            }
        }
        for (String key : before.keySet()) {
            if (!after.containsKey(key)) {
                String[] p = key.split(",");
                delta.add(new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])));
            }
        }
        assertTrue(omega.containsAll(delta),
                "Δ must be ⊆ Ω_authorized; delta=" + delta + " omega=" + omega);
        assertTrue(after.values().stream().noneMatch(v -> v != null && (
                        v.contains("lantern") || v.contains("torch") || v.contains("end_rod"))),
                "recolor patch must not grow lanterns outside Ω");
    }

    @Test
    void patchModeWindowEditKeepsOutsideWallCellsUnchangedThroughPostprocess() {
        // Existing façade + a 2×2 glass insert. Aesthetic skip must leave stone cells identical.
        List<BlockPatch> input = new ArrayList<>();
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 6; y++) {
                input.add(new BlockPatch(BlockPatch.PLACE, x, y, 0, "minecraft:stone_bricks"));
            }
        }
        input.add(new BlockPatch(BlockPatch.PLACE, 3, 2, 0, "minecraft:glass"));
        input.add(new BlockPatch(BlockPatch.PLACE, 4, 2, 0, "minecraft:glass"));
        input.add(new BlockPatch(BlockPatch.PLACE, 3, 3, 0, "minecraft:glass"));
        input.add(new BlockPatch(BlockPatch.PLACE, 4, 3, 0, "minecraft:glass"));

        LlmPlan patch = plan(LlmPlan.Mode.patch, List.of(facadeWindows()), null);
        var volume = new PostProcessContext.BuildingVolume("s",
                new ComponentFootprintUtil.Bounds(0, 0, 0, 8, 6, 4), 4);
        // Style that would otherwise add lanterns / relief / weathering.
        var attrs = new StyleAttributes(null, null, null, null, null, null, null, null,
                null, null, null, null, null, "deep", "classic", "subtle", "warm_lantern", "high");
        LlmPlan styled = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .mode(LlmPlan.Mode.patch)
                .styleProfile("GOTHIC")
                .styleAttributes(attrs)
                .components(List.of(facadeWindows()))
                .build();
        PostProcessContext ctx = PostProcessContext.create(styled, BlockPos.ORIGIN, List.of(volume));

        Map<String, String> before = cellMap(input);
        List<BlockPatch> out = PostProcessPipeline.createDefault(ctx).process(input, ctx);
        Map<String, String> after = cellMap(out);

        for (var e : before.entrySet()) {
            if (e.getValue().contains("glass")) continue;
            assertEquals(e.getValue(), after.get(e.getKey()),
                    "cells outside the opening edit must stay unchanged: " + e.getKey());
        }
        assertEquals(4, after.values().stream().filter(v -> v != null && v.contains("glass")).count(),
                "2×2 glass insert must remain four glass cells");
        assertTrue(after.values().stream().noneMatch(v -> v != null && (
                        v.contains("lantern") || v.contains("torch") || v.contains("end_rod"))),
                "patch-mode opening edit must not grow lanterns around the insert");
        assertTrue(out.size() <= input.size() + 2,
                "aesthetic processors must not balloon an opening patch, size=" + out.size());
    }

    @Test
    void buildModeSameSatellitesMayRunAestheticProcessors() {
        List<BlockPatch> input = new ArrayList<>();
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 6; y++) {
                input.add(new BlockPatch(BlockPatch.PLACE, x, y, 0, "minecraft:stone_bricks"));
            }
        }
        for (int x = 1; x < 7; x++) {
            for (int z = 1; z < 4; z++) {
                input.add(new BlockPatch(BlockPatch.PLACE, x, 0, z, "minecraft:oak_planks"));
                input.add(new BlockPatch(BlockPatch.PLACE, x, 5, z, "minecraft:oak_planks"));
            }
        }
        var attrs = new StyleAttributes(null, null, null, null, null, null, null, null,
                null, null, null, null, null, "deep", "classic", "subtle", "warm_lantern", "high");
        LlmPlan build = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .mode(LlmPlan.Mode.build)
                .styleProfile("GOTHIC")
                .styleAttributes(attrs)
                .components(List.of(facadeWindows()))
                .build();
        assertFalse(IncrementalEditScope.suppressesAestheticExpansion(build));
        var volume = new PostProcessContext.BuildingVolume("s",
                new ComponentFootprintUtil.Bounds(0, 0, 0, 8, 6, 4), 4);
        PostProcessContext ctx = PostProcessContext.create(build, BlockPos.ORIGIN, List.of(volume));
        List<BlockPatch> out = PostProcessPipeline.createDefault(ctx).process(input, ctx);
        // Build-mode standalone façade may receive lighting — proving mode gate is live.
        assertTrue(out.size() >= input.size(),
                "build-mode must not use the opening-edit aesthetic skip");
    }

    private static Component facadeWindows() {
        return new Component("FACADE_WINDOWS", "s", new Vec3i(0, 0, 0),
                new Dimensions(8, 4, 1), List.of("wrap"),
                Map.of("component_id", "win", "window_style", "glass"));
    }

    private static LlmPlan plan(LlmPlan.Mode mode, List<Component> components, PatchBlockSection patch) {
        var b = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .mode(mode)
                .styleProfile("DEFAULT")
                .components(components == null ? List.of() : components);
        if (patch != null) {
            b.patch(patch);
        }
        return b.build();
    }

    private static Map<String, String> cellMap(List<BlockPatch> patches) {
        Map<String, String> map = new HashMap<>();
        for (BlockPatch p : patches) {
            if (p == null) continue;
            String action = p.action() == null ? "" : p.action().toLowerCase();
            if (BlockPatch.REMOVE.equals(action)) {
                map.remove(p.dx() + "," + p.dy() + "," + p.dz());
                continue;
            }
            map.put(p.dx() + "," + p.dy() + "," + p.dz(), p.targetBlock());
        }
        return map;
    }
}
