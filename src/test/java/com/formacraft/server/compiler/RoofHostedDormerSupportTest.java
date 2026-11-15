package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RoofHostedDormerSupportTest {
    @Test void emissionPassBuildsDormerWallsDownToItsGeneratedRoof() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
        com.formacraft.server.assembly.AssemblyCompileDiagnostics.clear();
        var body = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0), new Dimensions(15, 15, 8),
                List.of("hollow"), Map.of("component_id", "body", "anchor_mode", "min_corner",
                        "suppress_windows", true, "suppress_doors", true));
        var roof = new Component("ROOF", "main", new Vec3i(0, 7, 0), new Dimensions(15, 15, 6),
                List.of(), Map.of("component_id", "roof", "host_id", "body", "anchor_mode", "min_corner",
                        "roof_type", "mansard", "roof_dormers", false, "roof_block", "minecraft:stone"));
        var window = ComponentSatelliteInference.normalizeComponent(new Component("MASS_ANCILLARY", "main", new Vec3i(2, 15, 2), new Dimensions(3, 3, 3),
                List.of("dormer", "hollow"), Map.of("component_id", "window", "host_id", "roof",
                        "anchor_mode", "min_corner", "wall_block", "minecraft:white_concrete",
                        "suppress_windows", true, "suppress_doors", true)));
        var components = List.of(body, window, roof);
        var plan = LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).styleProfile("DEFAULT").components(components).build();
        var result = new ArrayList<BlockPatch>();
        ComponentEmitPass.compileComponents(plan, null, null, false, components, Set.of(), Map.of(), result,
                new ArrayList<>(), new ArrayList<>(), new HashSet<>(), new ArrayList<>(), new HashSet<>(),
                new HashSet<>(), new HashMap<>());
        assertFalse(com.formacraft.server.assembly.AssemblyCompileDiagnostics.hasGap(),
                String.valueOf(com.formacraft.server.assembly.AssemblyCompileDiagnostics.get()));
        var finalAt = new HashMap<BlockPos, BlockPatch>();
        for (var patch : result) finalAt.put(new BlockPos(patch.dx(), patch.dy(), patch.dz()), patch);
        assertTrue(finalAt.values().stream().anyMatch(p -> p.dx() == 2 && p.dz() == 2 && p.dy() < 15
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
            assertSame(original, support.extendToRoof(original, null));
        }
    }

    @Test void doesNotUseOtherRoofsSlotsOrRaiseAlreadyEmbeddedDormers() {
        var support = new RoofHostedDormerSupport();
        support.record(roof(), slope(new Vec3i(0, 0, 0)));
        for (var original : List.of(dormer("missing", "main"), dormer("roof", "other")))
            assertSame(original, support.extendToRoof(original, null));
        var embedded = new Component("MASS_ANCILLARY", "main", new Vec3i(1, 11, 2), new Dimensions(3, 3, 10),
                List.of("dormer"), dormer("roof", "main").params());
        assertSame(embedded, support.extendToRoof(embedded, null));
    }

    @Test void hostedDormersDoNotPunchOutTheirOwnSupportSkin() {
        var own = dormer("roof", "main");
        assertTrue(ComponentEmitPass.roofOccluders(roof(), List.of(own), Map.of()).isEmpty());
    }
}
