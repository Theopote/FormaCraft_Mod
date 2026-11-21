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
        var tinted = ShapePreservingMaterialSwap.apply("minecraft:blue_stained_glass_pane[north=true,waterlogged=true]",
                Blocks.TINTED_GLASS.getDefaultState());
        assertEquals(Blocks.BLUE_STAINED_GLASS_PANE, tinted.getBlock());
        assertTrue(tinted.get(Properties.NORTH));
        assertTrue(tinted.get(Properties.WATERLOGGED));
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
        var redSandstone = ShapePreservingMaterialSwap.apply("minecraft:cobblestone_wall[east=tall,waterlogged=true]",
                Blocks.RED_SANDSTONE_SLAB.getDefaultState());
        assertEquals(Blocks.RED_SANDSTONE_WALL, redSandstone.getBlock());
        assertEquals(net.minecraft.block.enums.WallShape.TALL, redSandstone.get(Properties.EAST_WALL_SHAPE));
        assertTrue(redSandstone.get(Properties.WATERLOGGED));
        assertEquals(wall, ShapePreservingMaterialSwap.apply(
                "minecraft:stone_brick_wall[north=low,up=true]", Blocks.WHITE_CONCRETE.getDefaultState()));
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
