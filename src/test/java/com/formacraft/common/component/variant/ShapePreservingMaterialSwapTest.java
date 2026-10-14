package com.formacraft.common.component.variant;

import com.formacraft.common.component.semantic.ShapePreservingMaterialSwap;
import com.formacraft.common.patch.BlockPatchTargetResolver;
import net.minecraft.block.*;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShapePreservingMaterialSwapTest {
    @BeforeAll static void initialize() {com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();}
    @Test void stairsRetainShapeFacingAndHalf() {
        var result=ShapePreservingMaterialSwap.apply("minecraft:oak_stairs[facing=east,half=top,shape=inner_left,waterlogged=true]",
                Blocks.STONE_BRICKS.getDefaultState());
        assertEquals(Blocks.STONE_BRICK_STAIRS,result.getBlock());
        assertEquals(Direction.EAST,result.get(Properties.HORIZONTAL_FACING));
        assertTrue(result.get(Properties.WATERLOGGED));
        assertTrue(result.toString().contains("half=top"));
        assertTrue(result.toString().contains("shape=inner_left"));
    }
    @Test void slabsRetainDoubleOrTopState() {
        var result=ShapePreservingMaterialSwap.apply("minecraft:oak_slab[type=top]",Blocks.DEEPSLATE_TILES.getDefaultState());
        assertEquals(Blocks.DEEPSLATE_TILE_SLAB,result.getBlock());assertTrue(result.toString().contains("type=top"));
    }
    @Test void unsupportedShapePreservesOriginal() {
        var original=BlockPatchTargetResolver.parse("minecraft:oak_stairs[facing=east]");
        assertEquals(original,ShapePreservingMaterialSwap.apply("minecraft:oak_stairs[facing=east]",Blocks.GLASS.getDefaultState()));
    }
    @Test void doorsAndLightsKeepFunction() {
        var door=BlockPatchTargetResolver.parse("minecraft:oak_door[half=upper,facing=west]");
        assertEquals(door,ShapePreservingMaterialSwap.apply("minecraft:oak_door[half=upper,facing=west]",Blocks.STONE.getDefaultState()));
        assertEquals(Blocks.LANTERN.getDefaultState(),ShapePreservingMaterialSwap.apply("minecraft:lantern",Blocks.STONE.getDefaultState()));
    }
    @Test void panesNeverBecomeSolidCubes() {
        var result=ShapePreservingMaterialSwap.apply("minecraft:glass_pane[north=true]",Blocks.BLUE_STAINED_GLASS.getDefaultState());
        assertEquals(Blocks.BLUE_STAINED_GLASS_PANE,result.getBlock());assertTrue(result.get(Properties.NORTH));
        assertEquals(Blocks.GLASS_PANE.getDefaultState(),ShapePreservingMaterialSwap.apply("minecraft:glass_pane",Blocks.STONE.getDefaultState()));
    }
    @Test void fencesAndWallsRetainConnections() {
        var fence=ShapePreservingMaterialSwap.apply("minecraft:oak_fence[north=true,east=true]",Blocks.SPRUCE_PLANKS.getDefaultState());
        assertEquals(Blocks.SPRUCE_FENCE,fence.getBlock());
        assertTrue(fence.get(Properties.NORTH));
        assertTrue(fence.get(Properties.EAST));
        var wall=ShapePreservingMaterialSwap.apply("minecraft:cobblestone_wall[north=low,up=true]",Blocks.STONE_BRICKS.getDefaultState());
        assertEquals(Blocks.STONE_BRICK_WALL,wall.getBlock());
        assertTrue(wall.toString().contains("north=low"));
        assertTrue(wall.get(Properties.UP));
    }
    @Test void rotationAlsoTransformsLogAxis() {
        var rotated=com.formacraft.common.component.transform.CapturedBlockStateTransform.apply(
                "minecraft:oak_log[axis=x]",Direction.SOUTH,
                new com.formacraft.common.component.transform.ComponentTransform(Direction.WEST,
                        com.formacraft.common.component.transform.Mirror.NONE));
        assertEquals(Direction.Axis.Z,BlockPatchTargetResolver.parse(rotated).get(Properties.AXIS));
    }
    @Test void fullBlocksMayChangeMaterial() {
        assertEquals(Blocks.STONE_BRICKS.getDefaultState(),ShapePreservingMaterialSwap.apply("minecraft:oak_planks",Blocks.STONE_BRICKS.getDefaultState()));
    }
}
