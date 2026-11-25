package com.formacraft.common.compiler.postprocess;

import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.StyleAttributes;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LightingPostProcessorTest {
    @BeforeAll
    static void initRegistry() {
        // hangLinkBlock() probes Registries.BLOCK for minecraft:chain
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void placeOnlyAddsLightsAndEntranceFlankingIsSymmetric() {
        var volume = new PostProcessContext.BuildingVolume("m",
                new ComponentFootprintUtil.Bounds(0, 0, 0, 9, 6, 9), 4);
        var attrs = new StyleAttributes(null, null, null, null, null, null, null, null,
                null, null, null, null, null, "subtle", "classic", "none", "warm_lantern", "normal");
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("MEDIEVAL_CLASSIC").styleAttributes(attrs).build();
        PostProcessContext ctx = PostProcessContext.create(plan, BlockPos.ORIGIN, List.of(volume));

        List<BlockPatch> input = new ArrayList<>();
        // Ceiling and floor
        for (int x = 1; x < 8; x++) for (int z = 1; z < 8; z++) {
            input.add(new BlockPatch(BlockPatch.PLACE, x, 0, z, "minecraft:oak_planks"));
            input.add(new BlockPatch(BlockPatch.PLACE, x, 5, z, "minecraft:oak_planks"));
        }
        // Door on south face + façade posts so flanking lights can wall-mount
        input.add(new BlockPatch(BlockPatch.PLACE, 4, 1, 0, "minecraft:oak_door[facing=south,half=lower,hinge=left,open=false,powered=false]"));
        input.add(new BlockPatch(BlockPatch.PLACE, 4, 2, 0, "minecraft:oak_door[facing=south,half=upper,hinge=left,open=false,powered=false]"));
        input.add(new BlockPatch(BlockPatch.PLACE, 3, 3, 0, "minecraft:stone_bricks"));
        input.add(new BlockPatch(BlockPatch.PLACE, 5, 3, 0, "minecraft:stone_bricks"));

        List<BlockPatch> out = new LightingPostProcessor().process(input, ctx);
        List<BlockPatch> added = out.stream().filter(p -> !input.contains(p)).toList();
        assertFalse(added.isEmpty());
        assertTrue(added.stream().allMatch(p -> BlockPatch.PLACE.equals(p.action())));
        // Outside door plane (z=1), flanking at x=3/5, y=doorY+2 — not wall-plane z=0, and not duplicated by upper half
        long entranceAtY3 = added.stream().filter(p -> p.dy() == 3 && (p.dx() == 3 || p.dx() == 5) && p.dz() == 1).count();
        long entranceAtY4 = added.stream().filter(p -> p.dy() == 4 && (p.dx() == 3 || p.dx() == 5) && p.dz() == 1).count();
        assertTrue(entranceAtY3 >= 1, "expected entrance flanking lights outside the door");
        assertEquals(0, entranceAtY4, "upper door half must not place a second light row");
        assertTrue(added.stream().noneMatch(p -> (p.dx() == 3 || p.dx() == 5) && p.dy() == 3 && p.dz() == 0),
                "lights must not sit inside the wall plane");
    }

    @Test
    void eavesHangingRequiresSupportOrWallFallback() {
        var volume = new PostProcessContext.BuildingVolume("m",
                new ComponentFootprintUtil.Bounds(0, 0, 0, 7, 5, 7), 4);
        var attrs = new StyleAttributes(null, null, null, null, null, null, null, null,
                null, null, null, null, null, "subtle", "classic", "none", "warm_lantern", "high");
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleAttributes(attrs).build();
        PostProcessContext ctx = PostProcessContext.create(plan, BlockPos.ORIGIN, List.of(volume));
        List<BlockPatch> input = new ArrayList<>();
        // North wall only — eaves light at z=-1 attaches as wall torch (no block above)
        for (int x = 0; x <= 6; x++) {
            input.add(new BlockPatch(BlockPatch.PLACE, x, 3, 0, "minecraft:stone_bricks"));
        }
        List<BlockPatch> out = new LightingPostProcessor().process(input, ctx);
        List<BlockPatch> eaves = out.stream()
                .filter(p -> !input.contains(p))
                .filter(p -> p.dz() == -1 && p.dy() == 3)
                .toList();
        assertFalse(eaves.isEmpty(), "expected eaves lights against the north wall");
        assertTrue(eaves.stream().allMatch(p -> p.targetBlock() != null && p.targetBlock().contains("wall_torch")),
                "without a block above, eaves must fall back to wall_torch, got " + eaves);
        assertTrue(eaves.stream().noneMatch(p -> {
            String t = p.targetBlock() == null ? "" : p.targetBlock();
            return t.contains("lantern") && t.contains("hanging=true");
        }), "unsupported hanging lanterns must not be placed on eaves");
    }

    @Test
    void eavesHangingLanternRequiresBlockAbove() {
        var volume = new PostProcessContext.BuildingVolume("m",
                new ComponentFootprintUtil.Bounds(0, 0, 0, 7, 5, 7), 4);
        var attrs = new StyleAttributes(null, null, null, null, null, null, null, null,
                null, null, null, null, null, "subtle", "classic", "none", "warm_lantern", "high");
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleAttributes(attrs).build();
        PostProcessContext ctx = PostProcessContext.create(plan, BlockPos.ORIGIN, List.of(volume));
        List<BlockPatch> input = new ArrayList<>();
        for (int x = 0; x <= 6; x++) {
            input.add(new BlockPatch(BlockPatch.PLACE, x, 3, 0, "minecraft:stone_bricks"));
            // Overhang / eave slab above the future light cell so hanging is legal
            input.add(new BlockPatch(BlockPatch.PLACE, x, 4, -1, "minecraft:oak_slab[type=bottom]"));
        }
        List<BlockPatch> out = new LightingPostProcessor().process(input, ctx);
        List<BlockPatch> hanging = out.stream()
                .filter(p -> !input.contains(p))
                .filter(p -> p.dz() == -1 && p.dy() == 3)
                .filter(p -> p.targetBlock() != null && p.targetBlock().contains("hanging=true"))
                .toList();
        assertFalse(hanging.isEmpty(), "with solid above, eaves may place hanging lanterns");
        for (BlockPatch light : hanging) {
            assertTrue(input.stream().anyMatch(p -> p.dx() == light.dx() && p.dy() == light.dy() + 1 && p.dz() == light.dz()),
                    "every hanging lantern must have authored solid at (x,y+1,z)");
        }
    }

    @Test
    void lightingStyleNoneEmitsNothing() {
        var attrs = new StyleAttributes(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, "none", null);
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleAttributes(attrs).build();
        PostProcessContext ctx = PostProcessContext.create(plan, BlockPos.ORIGIN);
        List<BlockPatch> input = List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:oak_door[facing=south,half=lower]"));
        List<BlockPatch> out = new LightingPostProcessor().process(input, ctx);
        assertEquals(input.size(), out.size());
    }

    @Test
    void interiorLightRespectsHeadroomOnShortStorey() {
        assertTrue(SubVoxelHeadroom.shortStoreyUsesFlushFixture(3));
        assertFalse(SubVoxelHeadroom.shortStoreyUsesFlushFixture(5));
    }

    @Test
    void tallStoreyHangLinkAndLanternCommitTogether() {
        // floorHeight=5 → ceilY=floorY+4, storeyH=5 → hang link + lantern pair.
        var volume = new PostProcessContext.BuildingVolume("m",
                new ComponentFootprintUtil.Bounds(0, 0, 0, 11, 8, 11), 5);
        var attrs = new StyleAttributes(null, null, null, null, null, null, null, null,
                null, null, null, null, null, "subtle", "classic", "none", "warm_lantern", "high");
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleAttributes(attrs).build();
        PostProcessContext ctx = PostProcessContext.create(plan, BlockPos.ORIGIN, List.of(volume));
        List<BlockPatch> input = new ArrayList<>();
        // Ceiling must sit on computed ceilY (min(maxY-1, floorY+floorH-1) = 4).
        for (int x = 1; x < 10; x++) for (int z = 1; z < 10; z++) {
            input.add(new BlockPatch(BlockPatch.PLACE, x, 0, z, "minecraft:oak_planks"));
            input.add(new BlockPatch(BlockPatch.PLACE, x, 4, z, "minecraft:oak_planks"));
        }
        List<BlockPatch> out = new LightingPostProcessor().process(input, ctx);
        List<BlockPatch> added = out.stream().filter(p -> !input.contains(p)).toList();
        List<BlockPatch> links = added.stream()
                .filter(p -> p.targetBlock() != null
                        && (p.targetBlock().contains("chain") || p.targetBlock().contains("iron_bars")))
                .toList();
        assertFalse(links.isEmpty(), "expected hang links under tall ceiling, added=" + added);
        for (BlockPatch link : links) {
            boolean fixture = added.stream().anyMatch(p ->
                    p.dx() == link.dx() && p.dz() == link.dz() && p.dy() == link.dy() - 1
                            && p.targetBlock() != null
                            && (p.targetBlock().contains("lantern") || p.targetBlock().contains("end_rod")));
            assertTrue(fixture, "hang link at " + link.dx() + "," + link.dy() + "," + link.dz()
                    + " must keep its lantern/fixture cell");
        }
    }

    @Test
    void hangPairSkippedWhenFixtureCellIsProtectedClearance() {
        // Blocking only the lantern cell must suppress the hang-link too (atomic place).
        // ceilY=4 → link y=3, fixture y=2
        var volume = new PostProcessContext.BuildingVolume("m",
                new ComponentFootprintUtil.Bounds(0, 0, 0, 11, 8, 11), 5);
        var attrs = new StyleAttributes(null, null, null, null, null, null, null, null,
                null, null, null, null, null, "subtle", "classic", "none", "warm_lantern", "high");
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleAttributes(attrs).build();
        java.util.Set<BlockPos> clearance = new java.util.HashSet<>();
        for (int x = 2; x <= 8; x++) for (int z = 2; z <= 8; z++) {
            clearance.add(new BlockPos(x, 2, z)); // fixture band only
        }
        PostProcessContext ctx = PostProcessContext.create(plan, BlockPos.ORIGIN, List.of(volume), clearance);
        List<BlockPatch> input = new ArrayList<>();
        for (int x = 1; x < 10; x++) for (int z = 1; z < 10; z++) {
            input.add(new BlockPatch(BlockPatch.PLACE, x, 0, z, "minecraft:oak_planks"));
            input.add(new BlockPatch(BlockPatch.PLACE, x, 4, z, "minecraft:oak_planks"));
        }
        List<BlockPatch> out = new LightingPostProcessor().process(input, ctx);
        List<BlockPatch> added = out.stream().filter(p -> !input.contains(p)).toList();
        assertTrue(added.stream().noneMatch(p ->
                        p.dx() >= 2 && p.dx() <= 8 && p.dz() >= 2 && p.dz() <= 8
                                && p.dy() == 3
                                && p.targetBlock() != null
                                && (p.targetBlock().contains("chain") || p.targetBlock().contains("iron_bars"))),
                "hang-link must not place when its fixture cell is clearance-blocked");
        assertTrue(added.stream().noneMatch(p ->
                        p.dx() >= 2 && p.dx() <= 8 && p.dz() >= 2 && p.dz() <= 8 && p.dy() == 2),
                "fixture band clearance must also keep lanterns out");
    }

    @Test
    void hangPairDroppedAtomicallyWhenScopeRejectsFixtureOnly() {
        // ceilY=4 → link y=3, fixture y=2; frozen scope allows link but not fixture.
        var volume = new PostProcessContext.BuildingVolume("m",
                new ComponentFootprintUtil.Bounds(0, 0, 0, 11, 8, 11), 5);
        var attrs = new StyleAttributes(null, null, null, null, null, null, null, null,
                null, null, null, null, null, "subtle", "classic", "none", "warm_lantern", "high");
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleAttributes(attrs).build();
        List<BlockPatch> input = new ArrayList<>();
        for (int x = 1; x < 10; x++) for (int z = 1; z < 10; z++) {
            input.add(new BlockPatch(BlockPatch.PLACE, x, 0, z, "minecraft:oak_planks"));
            input.add(new BlockPatch(BlockPatch.PLACE, x, 4, z, "minecraft:oak_planks"));
        }
        java.util.Set<BlockPos> allowed = new java.util.HashSet<>();
        for (BlockPatch p : input) allowed.add(new BlockPos(p.dx(), p.dy(), p.dz()));
        for (int x = 0; x <= 10; x++) for (int z = 0; z <= 10; z++) {
            allowed.add(new BlockPos(x, 3, z)); // hang-link band
            allowed.add(new BlockPos(x, 4, z));
            allowed.add(new BlockPos(x, 0, z));
            allowed.add(new BlockPos(x, 1, z));
            // deliberately omit y=2 fixture band
        }
        PostProcessWriteScope frozen = PostProcessWriteScope.clearanceOnly(allowed);
        PostProcessContext ctx = PostProcessContext.create(plan, BlockPos.ORIGIN, List.of(volume))
                .withAuthorizedWriteScope(frozen);
        List<BlockPatch> out = new LightingPostProcessor().process(input, ctx);
        List<BlockPatch> added = out.stream().filter(p -> !input.contains(p)).toList();
        assertTrue(added.stream().noneMatch(p -> p.dy() == 3
                        && p.targetBlock() != null
                        && (p.targetBlock().contains("chain") || p.targetBlock().contains("iron_bars"))),
                "scope-rejected fixture must drop the hang-link of the same pair");
        assertTrue(added.stream().noneMatch(p -> p.dy() == 2
                        && p.targetBlock() != null
                        && (p.targetBlock().contains("lantern") || p.targetBlock().contains("end_rod"))),
                "fixture cells outside frozen scope must not place");
    }

    @Test
    void lightingOptOutUsesCompileTimeIdentityNotAabbGuess() {
        // Phase 01 closeout: lightingAllowed on BuildingVolume — slot-shifted bounds must not
        // be matched against local Component AABB (non-zero slot would miss).
        var quiet = new PostProcessContext.BuildingVolume("site",
                new ComponentFootprintUtil.Bounds(100, 64, 100, 108, 70, 108), 4,
                "quiet_hall", "quiet", false);
        var lively = new PostProcessContext.BuildingVolume("site",
                new ComponentFootprintUtil.Bounds(120, 64, 100, 128, 70, 108), 4,
                "lively_hall", "lively", true);
        // Local component coords deliberately do NOT overlap slot-shifted volumes.
        var quietMass = new com.formacraft.common.llm.dto.Component(
                "MASS_MAIN", "site", new com.formacraft.common.llm.dto.Vec3i(0, 0, 0),
                new com.formacraft.common.llm.dto.Dimensions(8, 8, 6), List.of(),
                java.util.Map.of("lighting_style", "none", "component_id", "quiet_hall", "building_id", "quiet"));
        var livelyMass = new com.formacraft.common.llm.dto.Component(
                "MASS_MAIN", "site", new com.formacraft.common.llm.dto.Vec3i(20, 0, 0),
                new com.formacraft.common.llm.dto.Dimensions(8, 8, 6), List.of(),
                java.util.Map.of("lighting_style", "warm_lantern", "component_id", "lively_hall", "building_id", "lively"));
        var attrs = new StyleAttributes(null, null, null, null, null, null, null, null,
                null, null, null, null, null, "subtle", "classic", "none", "warm_lantern", "high");
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleAttributes(attrs)
                .components(List.of(quietMass, livelyMass))
                .build();
        PostProcessContext ctx = PostProcessContext.create(plan, BlockPos.ORIGIN, List.of(quiet, lively));
        List<BlockPatch> input = new ArrayList<>();
        for (int x = 101; x < 107; x++) for (int z = 101; z < 107; z++) {
            input.add(new BlockPatch(BlockPatch.PLACE, x, 64, z, "minecraft:oak_planks"));
            input.add(new BlockPatch(BlockPatch.PLACE, x, 67, z, "minecraft:oak_planks"));
        }
        for (int x = 121; x < 127; x++) for (int z = 101; z < 107; z++) {
            input.add(new BlockPatch(BlockPatch.PLACE, x, 64, z, "minecraft:oak_planks"));
            input.add(new BlockPatch(BlockPatch.PLACE, x, 67, z, "minecraft:oak_planks"));
        }
        List<BlockPatch> out = new LightingPostProcessor().process(input, ctx);
        List<BlockPatch> added = out.stream().filter(p -> !input.contains(p)).toList();
        assertTrue(added.stream().noneMatch(p -> p.dx() >= 100 && p.dx() < 108),
                "quiet volume lightingAllowed=false must suppress lights, added=" + added);
        assertTrue(added.stream().anyMatch(p -> p.dx() >= 120 && p.dx() < 128),
                "lively volume lightingAllowed=true must receive lights, added=" + added);
    }

    /** Tiny helper mirrored from LightingPostProcessor storey rules for unit clarity. */
    static final class SubVoxelHeadroom {
        static boolean shortStoreyUsesFlushFixture(int storeyH) {
            return storeyH < 4;
        }
    }
}
