package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RoofHostedDormerSupportTest {
    @Test void emissionPassBuildsDormerWallsDownToItsGeneratedRoof() { verifyEmission(false); }
    @Test void emissionPassRejectsDormerWithoutActualRoofCoverage() { verifyEmission(true); }

    private void verifyEmission(boolean unsupported) {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
        com.formacraft.server.assembly.AssemblyCompileDiagnostics.clear();
        var body = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0), new Dimensions(15, 15, 8),
                List.of("hollow"), Map.of("component_id", "body", "anchor_mode", "min_corner",
                        "suppress_windows", true, "suppress_doors", true));
        var roof = new Component("ROOF", "main", new Vec3i(0, 7, 0), new Dimensions(15, 15, 6),
                List.of(), Map.of("component_id", "roof", "host_id", "body", "anchor_mode", "min_corner",
                        "roof_type", "mansard", "roof_dormers", false, "roof_block", "minecraft:stone"));
        var window = ComponentSatelliteInference.normalizeComponent(new Component("MASS_ANCILLARY", "main", new Vec3i(unsupported ? 30 : 2, 15, 2), new Dimensions(3, 3, 3),
                List.of("dormer", "hollow"), Map.of("component_id", "window", "host_id", "roof",
                        "anchor_mode", "min_corner", "wall_block", "minecraft:white_concrete",
                        "suppress_windows", true, "suppress_doors", true)));
        var components = List.of(body, window, roof);
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true).mode(LlmPlan.Mode.build).styleProfile("DEFAULT").components(components).build();
        var result = new ArrayList<BlockPatch>();
        ComponentEmitPass.compileComponents(plan, null, null, false, components, Set.of(), Map.of(), result,
                new ArrayList<>(), new ArrayList<>(), new HashSet<>(), new ArrayList<>(), new HashSet<>(),
                new HashSet<>(), new HashMap<>());
        if (unsupported) {
            assertNotNull(com.formacraft.server.assembly.AssemblyCompileDiagnostics.get());
            assertEquals("E_DORMER_ROOF_SUPPORT", com.formacraft.server.assembly.AssemblyCompileDiagnostics.get().code());
            assertFalse(result.stream().anyMatch(p -> p.dx() >= 30));
            return;
        }
        assertFalse(com.formacraft.server.assembly.AssemblyCompileDiagnostics.hasGap(),
                String.valueOf(com.formacraft.server.assembly.AssemblyCompileDiagnostics.get()));
        var finalAt = new HashMap<BlockPos, BlockPatch>();
        for (var patch : result) finalAt.put(new BlockPos(patch.dx(), patch.dy(), patch.dz()), patch);
        assertTrue(finalAt.values().stream().anyMatch(p -> p.dx() == 3 && p.dz() == 2 && p.dy() < 15
                && "minecraft:white_concrete".equals(p.targetBlock())),
                "The emission pass must generate the new lower wall, not only adjust a helper DTO");
        assertEquals(new Vec3i(2, 15, 2), window.relativePosition(), "Keep the authored plan immutable");
    }

    private Component roof() {
        return new Component("ROOF", "main", new Vec3i(0, 10, 0), new Dimensions(10, 10, 6),
                List.of(), Map.of("component_id", "roof", "host_id", "body"));
    }
    private Component dormer(String host, String slot) {
        return new Component("MASS_ANCILLARY", slot, new Vec3i(1, 18, 2), new Dimensions(3, 3, 3),
                List.of("dormer"), Map.of("component_id", "window", "host_id", host, "anchor_mode", "min_corner"));
    }
    private List<BlockPatch> slope(Vec3i offset) {
        var patches = new ArrayList<BlockPatch>();
        for (int x = 1; x < 4; x++) for (int z = 2; z < 5; z++)
            patches.add(new BlockPatch(BlockPatch.PLACE, x + offset.x(), 10 + z + offset.y(), z + offset.z(), "minecraft:stone"));
        return patches;
    }

    @Test void extendsToRealSlopeAndKeepsAuthoredTopInBothSlotFrames() {
        for (var offset : List.of(new Vec3i(0, 0, 0), new Vec3i(20, 30, -40))) {
            var support = new RoofHostedDormerSupport();
            support.record(roof(), slope(offset));
            var original = dormer("roof", "main");
            assertTrue(support.supportGap(original, offset).isEmpty());
            var result = support.extendToRoof(original, offset);
            assertEquals(new Vec3i(1, 13, 2), result.relativePosition());
            assertEquals(new Dimensions(3, 3, 8), result.dimensions());
            assertEquals(21, result.relativePosition().y() + result.dimensions().height());
            assertEquals(original.params(), result.params());
            assertTrue(ComponentEmitPass.compilationPriority(roof()) < ComponentEmitPass.compilationPriority(original));
        }
    }

    @Test void removedOrAirRoofCellsCannotBeInventedAsSupport() {
        for (var operation : List.of(new BlockPatch(BlockPatch.REMOVE, 1, 12, 2, null),
                new BlockPatch(BlockPatch.PLACE, 1, 12, 2, "minecraft:air"))) {
            var support = new RoofHostedDormerSupport();
            var patches = slope(new Vec3i(0, 0, 0));
            patches.add(operation);
            support.record(roof(), patches);
            var original = dormer("roof", "main");
            assertEquals("E_DORMER_ROOF_SUPPORT", support.supportGap(original, null).orElseThrow().code());
            assertSame(original, support.extendToRoof(original, null));
        }
    }

    @Test void knownRoofWithoutEmittedCellsCannotSupportDormers() {
        var support = new RoofHostedDormerSupport();
        support.record(roof(), List.of());
        assertEquals("E_DORMER_ROOF_SUPPORT", support.supportGap(dormer("roof", "main"), null).orElseThrow().code());
    }

    @Test void doesNotUseOtherRoofsSlotsOrRaiseAlreadyEmbeddedDormers() {
        var support = new RoofHostedDormerSupport();
        support.record(roof(), slope(new Vec3i(0, 0, 0)));
        for (var original : List.of(dormer("missing", "main"), dormer("roof", "other"))) {
            assertSame(original, support.extendToRoof(original, null));
            assertEquals("E_DORMER_ROOF_SUPPORT", support.supportGap(original, null).orElseThrow().code());
        }
        var embedded = new Component("MASS_ANCILLARY", "main", new Vec3i(1, 11, 2), new Dimensions(3, 3, 10),
                List.of("dormer"), dormer("roof", "main").params());
        assertSame(embedded, support.extendToRoof(embedded, null));
    }

    @Test void missingHostRoofIsExplicitSupportGap() {
        var support = new RoofHostedDormerSupport();
        var gap = support.supportGap(dormer("missing", "main"), null).orElseThrow();
        assertEquals("E_DORMER_ROOF_SUPPORT", gap.code());
        assertTrue(gap.message().contains("不存在") || gap.message().contains("host=missing"));
    }

    @Test void hostedDormersDoNotPunchOutTheirOwnSupportSkin() {
        var own = dormer("roof", "main");
        assertTrue(ComponentEmitPass.roofOccluders(roof(), List.of(own), Map.of()).isEmpty());
    }

    @Test void dormerJoinAuthorityIsLimitedToItsNamedRoofAndSlot() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
        var index = new PatchOccupancyIndex();
        var cell = new BlockPatch(BlockPatch.PLACE, 1, 12, 2, "minecraft:stone");
        index.commit(roof(), List.of(cell));
        var replacement = new BlockPatch(BlockPatch.PLACE, 1, 12, 2, "minecraft:bricks");
        var carve = new BlockPatch(BlockPatch.REMOVE, 1, 12, 2, null);
        assertTrue(index.validate(dormer("roof", "main"), List.of(replacement)).isEmpty());
        assertTrue(index.validate(dormer("roof", "main"), List.of(carve)).isEmpty());
        for (var foreign : List.of(dormer("other", "main"), dormer("roof", "other"))) {
            assertTrue(index.validate(foreign, List.of(replacement)).isPresent());
            assertTrue(index.validate(foreign, List.of(carve)).isPresent());
        }
        var ordinary = new Component("MASS_SECONDARY", "main", new Vec3i(1, 12, 2), new Dimensions(3, 3, 3),
                List.of(), Map.of("host_id", "roof"));
        assertTrue(index.validate(ordinary, List.of(replacement)).isPresent());
        assertTrue(index.validate(ordinary, List.of(carve)).isPresent());
    }

    @Test void dormerJoinAuthorityRejectsEditsOutsideDormerEnvelope() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
        var index = new PatchOccupancyIndex();
        // Far along the ridge — same host roof/slot, but outside dormer footprint column.
        var far = new BlockPatch(BlockPatch.PLACE, 8, 12, 8, "minecraft:stone");
        index.commit(roof(), List.of(far));
        var replacement = new BlockPatch(BlockPatch.PLACE, 8, 12, 8, "minecraft:bricks");
        var carve = new BlockPatch(BlockPatch.REMOVE, 8, 12, 8, null);
        var dormer = dormer("roof", "main");
        assertTrue(index.validate(dormer, List.of(replacement)).isPresent());
        assertTrue(index.validate(dormer, List.of(carve)).isPresent());
    }

    @Test void dormerJoinAuthorityHonorsNonZeroSlotOffset() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
        var index = new PatchOccupancyIndex();
        var slot = new BlockPos(20, 30, -40);
        // Local roof cell (1,12,2) shifted by slot → world/plan (21,42,-38)
        var cell = new BlockPatch(BlockPatch.PLACE, 21, 42, -38, "minecraft:stone");
        index.commit(roof(), List.of(cell), Set.of(), slot);
        var replacement = new BlockPatch(BlockPatch.PLACE, 21, 42, -38, "minecraft:bricks");
        var carve = new BlockPatch(BlockPatch.REMOVE, 21, 42, -38, null);
        var hosted = dormer("roof", "main");
        assertTrue(index.validate(hosted, List.of(replacement), Set.of(), slot).isEmpty());
        assertTrue(index.validate(hosted, List.of(carve), Set.of(), slot).isEmpty());
        // Same absolute cell without the slot offset must not match the local dormer envelope.
        assertTrue(index.validate(hosted, List.of(replacement)).isPresent());
    }
}
