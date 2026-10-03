package com.formacraft.server.generation.structure;

import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.model.build.*;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.*;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TowerCirculationTest {
    @BeforeAll static void initialize() { MinecraftRegistryTestBootstrap.initialize(); }
    private BuildingSpec spec(int height, int floors, boolean stairs) {
        var spec = new BuildingSpec(); spec.setType(BuildingType.TOWER); spec.setHeight(height); spec.setFloors(floors);
        var features = new Features(); features.setHasStairs(stairs); spec.setFeatures(features); return spec;
    }
    private Map<BlockPos, BlockState> snapshot(List<PlannedBlock> blocks) {
        var result = new HashMap<BlockPos, BlockState>(); for (var block : blocks) result.put(block.getPos(), block.getTargetState()); return result;
    }
    @ParameterizedTest @ValueSource(ints = {3, 9, 100})
    void excessiveFloorsAreRejectedBeforeGeneration(int floors) {
        assertThrows(IllegalArgumentException.class, () -> new TowerGenerator().generate(spec(8, floors, true), BlockPos.ORIGIN, null));
    }
    @Test void requestedFloorLevelsIncludeGroundAndNoExtraTopFloor() {
        var result = snapshot(new TowerGenerator().generate(spec(10, 3, false), BlockPos.ORIGIN, null).getBlocks());
        for (int y : new int[]{0, 3, 6}) assertEquals(Blocks.STONE.getDefaultState(), result.get(new BlockPos(0, y, 0)));
        assertTrue(result.get(new BlockPos(0, 9, 0)).isAir());
    }
    @ParameterizedTest @ValueSource(ints = {3, 4, 6})
    void spiralHasAdjacentStepsCornerLandingsAndUpHillFacing(int top) {
        var out = new ArrayList<PlannedBlock>(); var origin = new BlockPos(-20, 64, 30);
        TowerStairBuilder.append(out, origin, top, Blocks.STONE.getDefaultState());
        var result = snapshot(out);
        BlockPos previous = null; int previousY = 0;
        Direction[] directions = {Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.NORTH};
        for (int i = 0; i <= 2 * top; i++) {
            PlannedBlock tread = out.get(i * 3); BlockPos pos = tread.getPos(); int y = pos.getY() - origin.getY();
            if (previous != null) assertEquals(1, Math.abs(pos.getX() - previous.getX()) + Math.abs(pos.getZ() - previous.getZ()));
            assertTrue(y - previousY >= 0 && y - previousY <= 1);
            assertEquals(i % 2 == 1, tread.getTargetState().getBlock() instanceof StairsBlock);
            if (i % 2 == 1) assertEquals(directions[(i % 8) / 2], tread.getTargetState().get(Properties.HORIZONTAL_FACING));
            assertEquals(tread.getTargetState(), result.get(pos));
            assertTrue(result.get(pos.up()).isAir()); assertTrue(result.get(pos.up(2)).isAir());
            previous = pos; previousY = y;
        }
        assertEquals(top, previousY); assertEquals(Blocks.STONE.getDefaultState(), result.get(previous));
    }
    @Test void actualTowerKeepsStairsAndFloorOpeningsAfterRoofGeneration() {
        var origin = new BlockPos(30, 64, -20);
        var result = snapshot(new TowerGenerator().generate(spec(9, 3, true), origin, null).getBlocks());
        assertEquals(Direction.EAST, result.get(origin.add(0, 1, -1)).get(Properties.HORIZONTAL_FACING));
        assertTrue(result.get(origin.add(0, 3, -1)).isAir());
        assertEquals(Blocks.STONE.getDefaultState(), result.get(origin.add(1, 6, 1)));
        assertTrue(result.get(origin.add(1, 7, 1)).isAir()); assertTrue(result.get(origin.add(1, 8, 1)).isAir());
        assertFalse(result.get(origin.add(1, 9, 1)).isAir());
    }
    @Test void noStairsFeatureAndSingleFloorDoNotCreateSpiral() {
        for (var spec : List.of(spec(9, 3, false), spec(9, 1, true))) {
            var result = snapshot(new TowerGenerator().generate(spec, BlockPos.ORIGIN, null).getBlocks());
            assertTrue(result.values().stream().noneMatch(s -> s.getBlock() instanceof StairsBlock));
        }
    }
}
