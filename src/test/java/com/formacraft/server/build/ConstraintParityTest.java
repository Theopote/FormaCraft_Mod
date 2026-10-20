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
    private static final BlockPos ORIGIN = new BlockPos(100, 64, -40);
    @BeforeAll static void bootstrap() { MinecraftRegistryTestBootstrap.initialize(); }

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
