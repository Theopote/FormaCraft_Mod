package com.formacraft.server.skeleton.gen.palette;

import com.formacraft.common.semantic.*;
import com.formacraft.common.style.*;
import com.formacraft.common.patch.PatchExecutor;
import com.formacraft.test.*;
import net.minecraft.block.*;
import net.minecraft.block.enums.*;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SemanticBlockStateResolverTest {
    @BeforeAll static void initialize() { MinecraftRegistryTestBootstrap.initialize(); }
    @Test void stairsFacingAndAllPalettePropertiesSurvivePatchAndExecution() {
        String id = "TEST_STAIRS_PROPERTY_ROUND_TRIP";
        var selected = Blocks.OAK_STAIRS.getDefaultState().with(Properties.BLOCK_HALF, BlockHalf.TOP)
            .with(Properties.STAIR_SHAPE, StairShape.INNER_LEFT).with(Properties.WATERLOGGED, true);
        SemanticStyleProfileRegistry.register(new SemanticStyleProfile(id).bind(SemanticPart.STAIR_STEP, new PaletteRule().add(selected, 1)));
        var origin = new BlockPos(100, 64, -100); var local = new BlockPos(2, 3, 1);
        var ops = List.of(SemanticPlacementOp.of(origin.add(local), Direction.WEST, SemanticPart.STAIR_STEP));
        var patches = SemanticBlockStateResolver.resolveToPatches(origin, ops, id, new Random(1));
        var access = new FakeBlockMutationAccess();
        assertEquals(1, PatchExecutor.applyToAccess(access, BlockPos.ORIGIN, patches).applied());
        assertEquals(selected.with(Properties.HORIZONTAL_FACING, Direction.WEST), access.states.get(local));
    }
    @Test void nonStairPaletteAndVerticalFacingAreRejected() {
        var plain = new SemanticStyleProfile("test").bind(SemanticPart.STAIR_STEP, new PaletteRule().add(Blocks.STONE.getDefaultState(), 1));
        assertThrows(IllegalArgumentException.class, () -> new SemanticPaletteResolver(plain, new Random(1)).resolve(
            SemanticPlacementOp.of(BlockPos.ORIGIN, Direction.EAST, SemanticPart.STAIR_STEP)));
        assertThrows(IllegalArgumentException.class, () -> new SemanticPaletteResolver(null, new Random(1)).resolve(
            SemanticPlacementOp.of(BlockPos.ORIGIN, Direction.UP, SemanticPart.STAIR_STEP)));
    }
    @Test void missingStairRuleUsesRealStairsWithRequestedFacing() {
        var state = new SemanticPaletteResolver(new SemanticStyleProfile("empty"), new Random(1)).resolve(
            SemanticPlacementOp.of(BlockPos.ORIGIN, Direction.SOUTH, SemanticPart.STAIR_STEP));
        assertInstanceOf(StairsBlock.class, state.getBlock()); assertEquals(Direction.SOUTH, state.get(Properties.HORIZONTAL_FACING));
    }
    @Test void nonStairBlockPropertiesAlsoSurviveSerialization() {
        String id = "TEST_LOG_PROPERTY_ROUND_TRIP";
        var state = Blocks.OAK_LOG.getDefaultState().with(Properties.AXIS, Direction.Axis.X);
        SemanticStyleProfileRegistry.register(new SemanticStyleProfile(id).bind(SemanticPart.BEAM, new PaletteRule().add(state, 1)));
        var patches = SemanticBlockStateResolver.resolveToPatches(BlockPos.ORIGIN,
            List.of(SemanticPlacementOp.of(new BlockPos(0, 1, 0), SemanticPart.BEAM)), id, new Random(1));
        var access = new FakeBlockMutationAccess(); PatchExecutor.applyToAccess(access, BlockPos.ORIGIN, patches);
        assertEquals(state, access.states.get(new BlockPos(0, 1, 0)));
    }
}
