package com.formacraft.server.edit;

import com.formacraft.common.build.GeometrySource;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.BlockPatchTargetResolver;
import com.formacraft.server.memory.MemoryManager;
import com.formacraft.server.memory.ProjectMemory;
import com.formacraft.server.memory.SpatialIndex;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 regressions for building-aware roof material replace.
 * In-game Preview/Confirm/Apply/Undo remain checklist items in docs.
 */
class BuildingAwareIncrementalPhase1Test {
    private static final Identifier OVERWORLD = Identifier.of("minecraft:overworld");

    @BeforeAll
    static void boot() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    private ProjectMemory house(String name, BlockPos min, BlockPos max) {
        var memory = new ProjectMemory();
        memory.setName(name);
        var bounds = new ProjectMemory.SpatialBounds(min, max, OVERWORLD);
        bounds.addAnchor("origin", min);
        memory.setBounds(bounds);
        BuildingMemoryKeys.setRevision(memory, 0L);
        return memory;
    }

    private MemoryManager manager(SpatialIndex index) {
        return new MemoryManager(null) {
            @Override public List<ProjectMemory> findAtPosition(Identifier dimension, BlockPos pos) {
                return index.findAt(dimension, pos);
            }
            @Override public ProjectMemory findNearest(Identifier dimension, BlockPos pos, double maxDistance) {
                return index.findNearest(dimension, pos, maxDistance);
            }
            @Override public ProjectMemory getMemory(String uuid) {
                return index.getMemory(uuid);
            }
            @Override public void updateMemory(ProjectMemory memory) {
                index.removeMemory(memory.getUuid());
                index.addMemory(memory);
            }
        };
    }

    private void attachOakRoof(ProjectMemory memory, BlockPos... cells) {
        Map<BlockPos, String> states = new HashMap<>();
        for (BlockPos cell : cells) {
            states.put(cell, "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]");
        }
        BuildingEditService.attachRoofIndex(memory, BuildingPartIndex.ofRoofCells(List.of(cells), states));
    }

    @Test
    void singleHouseRoofMaterialReplaceSucceeds() {
        var index = new SpatialIndex();
        var house = house("villa", new BlockPos(0, 64, 0), new BlockPos(8, 72, 8));
        BlockPos r1 = new BlockPos(2, 71, 2);
        BlockPos r2 = new BlockPos(3, 71, 2);
        attachOakRoof(house, r1, r2);
        index.addMemory(house);

        var service = new BuildingEditService(manager(index));
        Map<BlockPos, String> live = Map.of(
                r1, "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]",
                r2, "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]");
        var prepared = service.prepareRoofMaterialReplace(
                new BuildingTargetResolver.Query(OVERWORLD, UUID.fromString(house.getUuid()), null, null, null, 16),
                "深色橡木",
                live,
                null);
        assertTrue(prepared.ok(), () -> prepared.refusals().toString());
        assertEquals(2, prepared.patches().size());
        for (BlockPatch patch : prepared.patches()) {
            var state = BlockPatchTargetResolver.resolve(patch);
            assertNotNull(state);
            assertEquals(Blocks.DARK_OAK_STAIRS, state.getBlock());
            assertEquals(Direction.EAST, state.get(Properties.HORIZONTAL_FACING));
            assertEquals(BlockHalf.BOTTOM, state.get(Properties.BLOCK_HALF));
        }
    }

    @Test
    void neighborRoofIsNotModifiedWhenTargetIsExplicit() {
        var index = new SpatialIndex();
        var a = house("a", new BlockPos(0, 64, 0), new BlockPos(5, 70, 5));
        var b = house("b", new BlockPos(20, 64, 0), new BlockPos(25, 70, 5));
        BlockPos aRoof = new BlockPos(2, 69, 2);
        BlockPos bRoof = new BlockPos(22, 69, 2);
        attachOakRoof(a, aRoof);
        attachOakRoof(b, bRoof);
        index.addMemory(a);
        index.addMemory(b);

        var service = new BuildingEditService(manager(index));
        Map<BlockPos, String> live = Map.of(
                aRoof, "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]",
                bRoof, "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]");
        var prepared = service.prepareRoofMaterialReplace(
                new BuildingTargetResolver.Query(OVERWORLD, UUID.fromString(a.getUuid()), null, null, null, 16),
                "minecraft:dark_oak_planks",
                live,
                null);
        assertTrue(prepared.ok());
        for (BlockPatch patch : prepared.patches()) {
            BlockPos world = prepared.building().origin().add(patch.dx(), patch.dy(), patch.dz());
            assertNotEquals(bRoof, world);
            assertTrue(prepared.scope().allows(world));
        }
    }

    @Test
    void ambiguousNearbyBuildingsRefuseEdit() {
        var index = new SpatialIndex();
        var a = house("a", new BlockPos(0, 64, 0), new BlockPos(4, 70, 4));
        var b = house("b", new BlockPos(2, 64, 2), new BlockPos(6, 70, 6));
        attachOakRoof(a, new BlockPos(1, 69, 1));
        attachOakRoof(b, new BlockPos(3, 69, 3));
        index.addMemory(a);
        index.addMemory(b);

        var resolver = new BuildingTargetResolver(manager(index));
        var result = resolver.resolve(new BuildingTargetResolver.Query(
                OVERWORLD, null, null, null, new BlockPos(3, 65, 3), 16));
        assertFalse(result.ok());
        assertEquals(BuildingTargetResolver.Failure.AMBIGUOUS, result.failure());
        assertTrue(result.candidates().size() >= 2);
    }

    @Test
    void highAltitudeRoofDoesNotUseAbsoluteYHeuristic() {
        var index = new SpatialIndex();
        var house = house("high", new BlockPos(0, 100, 0), new BlockPos(6, 110, 6));
        BlockPos roof = new BlockPos(2, 109, 2);
        attachOakRoof(house, roof);
        index.addMemory(house);

        // Absolute-Y classifier would call y>10 "roof" for walls too; part index is authoritative.
        var part = BuildingPartIndex.fromMemory(house);
        assertTrue(part.roof().cells().contains(roof));
        assertFalse(part.roof().cells().contains(new BlockPos(2, 102, 2)));

        var service = new BuildingEditService(manager(index));
        String liveState = "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]";
        var prepared = service.prepareRoofMaterialReplace(
                new BuildingTargetResolver.Query(OVERWORLD, UUID.fromString(house.getUuid()), null, null, null, 16),
                "spruce",
                Map.of(roof, liveState),
                null);
        assertTrue(prepared.ok(), () -> prepared.refusals().toString());
        assertEquals(1, prepared.patches().size());
    }

    @Test
    void unauthorizedWallWriteIsRejectedByScopeValidator() {
        var building = new BuildingInstance(
                UUID.randomUUID(), OVERWORLD, new BlockPos(0, 64, 0),
                new BlockPos(0, 64, 0), new BlockPos(8, 72, 8), 0L, "x");
        var roof = new BuildingPartIndex.RoofPart("roof",
                java.util.Set.of(new BlockPos(2, 71, 2)),
                Map.of(new BlockPos(2, 71, 2), "minecraft:oak_planks"));
        var scope = BuildingEditScope.replaceRoofMaterial(building, roof, roof.recordedStates(), java.util.Set.of());
        var patches = List.of(
                new BlockPatch(BlockPatch.REPLACE, 2, 7, 2, "minecraft:dark_oak_planks"),
                new BlockPatch(BlockPatch.REPLACE, 1, 1, 1, "minecraft:stone") // wall
        );
        var validation = BuildingEditScopeValidator.validate(
                scope, building.origin(), patches, 0L, roof.recordedStates());
        assertFalse(validation.accepted());
        assertTrue(validation.conflicts().stream().anyMatch(c -> c.contains("unauthorized_write")));
    }

    @Test
    void missingRoofIndexRefusesGuessing() {
        var index = new SpatialIndex();
        var house = house("bare", new BlockPos(0, 64, 0), new BlockPos(4, 70, 4));
        index.addMemory(house);
        var service = new BuildingEditService(manager(index));
        var prepared = service.prepareRoofMaterialReplace(
                new BuildingTargetResolver.Query(OVERWORLD, UUID.fromString(house.getUuid()), null, null, null, 16),
                "dark_oak",
                Map.of(),
                null);
        assertFalse(prepared.ok());
        assertTrue(prepared.refusals().getFirst().contains("无法安全定位屋顶"));
    }

    @Test
    void unregisteredBuildingRefusesGlobalGuess() {
        var service = new BuildingEditService(manager(new SpatialIndex()));
        var prepared = service.prepareRoofMaterialReplace(
                new BuildingTargetResolver.Query(OVERWORLD, UUID.randomUUID(), null, null, null, 16),
                "dark_oak",
                Map.of(),
                null);
        assertFalse(prepared.ok());
        assertTrue(prepared.refusals().getFirst().contains("未登记")
                || prepared.refusals().getFirst().contains("Memory"));
    }

    @Test
    void worldConflictBeforeConfirmIsDetected() {
        var index = new SpatialIndex();
        var house = house("conflict", new BlockPos(0, 64, 0), new BlockPos(4, 70, 4));
        BlockPos roof = new BlockPos(1, 69, 1);
        attachOakRoof(house, roof);
        index.addMemory(house);
        var service = new BuildingEditService(manager(index));
        // Player manually replaced the roof cell after registration; live ≠ memory baseline.
        Map<BlockPos, String> live = Map.of(roof, "minecraft:stone");
        var prepared = service.prepareRoofMaterialReplace(
                new BuildingTargetResolver.Query(OVERWORLD, UUID.fromString(house.getUuid()), null, null, null, 16),
                "dark_oak",
                live,
                null);
        assertFalse(prepared.ok(), "manual world drift must block confirmable edit");
        assertTrue(
                prepared.refusals().stream().anyMatch(r ->
                        r.contains("world_conflict") || r.contains("不兼容") || r.contains("不可解析")),
                () -> prepared.refusals().toString());
    }

    @Test
    void commitBumpsRevisionAndPersistsAcrossReloadShape() {
        var index = new SpatialIndex();
        var house = house("rev", new BlockPos(0, 64, 0), new BlockPos(4, 70, 4));
        BlockPos roof = new BlockPos(1, 69, 1);
        attachOakRoof(house, roof);
        index.addMemory(house);
        var mgr = manager(index);
        var service = new BuildingEditService(mgr);
        assertTrue(service.commitSuccessfulRoofEdit(
                UUID.fromString(house.getUuid()),
                OVERWORLD,
                0L,
                Map.of(roof, "minecraft:dark_oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]")));
        var reloaded = BuildingInstance.fromMemory(mgr.getMemory(house.getUuid()));
        assertEquals(1L, reloaded.revision());
        assertEquals("minecraft:dark_oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]",
                BuildingPartIndex.fromMemory(mgr.getMemory(house.getUuid())).roof().recordedStates().get(roof));
    }

    @Test
    void roofProvenanceFromGeometrySourceNotAbsoluteY() {
        var origin = new BlockPos(0, 100, 0);
        var roofPatch = new BlockPatch(BlockPatch.PLACE, 1, 8, 1, "minecraft:oak_stairs",
                GeometrySource.generated("ROOF_GENERATOR"));
        var wallPatch = new BlockPatch(BlockPatch.PLACE, 1, 2, 1, "minecraft:oak_planks",
                GeometrySource.generated("WALL"));
        var part = BuildingPartIndex.fromRoofPatches(origin, List.of(roofPatch, wallPatch));
        assertEquals(1, part.roof().cells().size());
        assertTrue(part.roof().cells().contains(new BlockPos(1, 108, 1)));
    }

    @Test
    void revisionMismatchRejectsConfirmValidation() {
        var building = new BuildingInstance(
                UUID.randomUUID(), OVERWORLD, BlockPos.ORIGIN,
                BlockPos.ORIGIN, new BlockPos(4, 4, 4), 3L, "x");
        var roofCell = new BlockPos(1, 3, 1);
        var roof = new BuildingPartIndex.RoofPart("roof", java.util.Set.of(roofCell),
                Map.of(roofCell, "minecraft:oak_planks"));
        var scope = BuildingEditScope.replaceRoofMaterial(building, roof, roof.recordedStates(), java.util.Set.of());
        var patches = List.of(new BlockPatch(BlockPatch.REPLACE, 1, 3, 1, "minecraft:dark_oak_planks"));
        var validation = BuildingEditScopeValidator.validate(scope, BlockPos.ORIGIN, patches, 2L, roof.recordedStates());
        assertFalse(validation.accepted());
        assertTrue(validation.conflicts().getFirst().contains("revision_mismatch"));
    }
}
