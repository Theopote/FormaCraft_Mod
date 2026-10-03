package com.formacraft.server.memory;

import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MemoryDimensionTest {
    private static final Identifier OVERWORLD = Identifier.of("minecraft:overworld");
    private static final Identifier NETHER = Identifier.of("minecraft:the_nether");
    private ProjectMemory memory(Identifier dimension, int x) {
        var memory = new ProjectMemory();
        memory.setBounds(new ProjectMemory.SpatialBounds(new BlockPos(x, 0, 0), new BlockPos(x + 4, 8, 4), dimension));
        return memory;
    }
    @Test void overlappingCoordinatesAreIsolatedByDimension() {
        var index = new SpatialIndex(); var overworld = memory(OVERWORLD, 0); var nether = memory(NETHER, 0);
        index.addMemory(overworld); index.addMemory(nether);
        assertEquals(List.of(overworld), index.findAt(OVERWORLD, new BlockPos(1, 1, 1)));
        assertEquals(List.of(nether), index.findAt(NETHER, new BlockPos(1, 1, 1)));
        index.removeMemory(overworld.getUuid());
        assertTrue(index.findAt(OVERWORLD, new BlockPos(1, 1, 1)).isEmpty());
        assertEquals(List.of(nether), index.findAt(NETHER, new BlockPos(1, 1, 1)));
    }
    @Test void nearerBuildingInOtherDimensionIsNotSelected() {
        var index = new SpatialIndex(); var overworld = memory(OVERWORLD, 16); var nether = memory(NETHER, 0);
        index.addMemory(overworld); index.addMemory(nether);
        assertSame(overworld, index.findNearest(OVERWORLD, BlockPos.ORIGIN, 32));
        assertSame(nether, index.findNearest(NETHER, BlockPos.ORIGIN, 32));
    }
    @Test void missingLegacyDimensionIsNotGuessedAsCurrentWorld() {
        var index = new SpatialIndex(); var unknown = memory(OVERWORLD, 0);
        unknown.getBounds().setDimension(null); index.addMemory(unknown);
        assertTrue(index.findAt(OVERWORLD, BlockPos.ORIGIN).isEmpty());
        assertNull(index.findNearest(NETHER, BlockPos.ORIGIN, 32));
        assertThrows(NullPointerException.class, () -> index.findAt(null, BlockPos.ORIGIN));
    }
    private MemoryManager manager(SpatialIndex index) {
        return new MemoryManager(null) {
            @Override public List<ProjectMemory> findAtPosition(Identifier dimension, BlockPos pos) {
                return index.findAt(dimension, pos);
            }
            @Override public ProjectMemory getMemory(String uuid) { return index.getMemory(uuid); }
        };
    }
    @Test void patchTargetsOnlyBuildingInExecutionDimension() {
        var index = new SpatialIndex(); var overworld = memory(OVERWORLD, 0); var nether = memory(NETHER, 0);
        index.addMemory(overworld); index.addMemory(nether);
        var impacts = new PatchDiffAnalyzer(manager(index)).analyze(NETHER, BlockPos.ORIGIN,
            List.of(new BlockPatch(BlockPatch.REPLACE, 1, 1, 1, "minecraft:stone")));
        assertEquals(1, impacts.size());
        assertEquals(UUID.fromString(nether.getUuid()), impacts.getFirst().getTargetBuilding());
    }
    @Test void removalWithoutRegisteredBuildingDoesNotCreateMemory() {
        var analyzer = new PatchDiffAnalyzer(manager(new SpatialIndex()));
        assertTrue(analyzer.analyze(NETHER, BlockPos.ORIGIN,
            List.of(new BlockPatch(BlockPatch.REMOVE, 1, 1, 1, "minecraft:air"))).isEmpty());
    }
    @Test void mutationWithWrongDimensionLeavesMemoryUntouched() {
        var index = new SpatialIndex(); var overworld = memory(OVERWORLD, 0); index.addMemory(overworld);
        var mutation = new GeneMutation(UUID.fromString(overworld.getUuid()), "test", Set.of(), Map.of("modified", true));
        assertNull(manager(index).applyMutation(NETHER, mutation, new BlockPos(-10, 0, 0), new BlockPos(10, 8, 4)));
        assertEquals(0, overworld.getBounds().getMinPos().getX());
        assertFalse(overworld.getMetadata().containsKey("modified"));
    }
}
