package com.formacraft.server.compiler;

import com.formacraft.server.generation.typology.builder.TailiangTimberHallBuilder;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ChineseHallFacadeTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    private static BlockPos at(Direction f, int x, int y, int z) {
        return switch (f) {
            case NORTH -> new BlockPos(-x,y,-z);
            case EAST -> new BlockPos(z,y,-x);
            case WEST -> new BlockPos(-z,y,x);
            default -> new BlockPos(x,y,z);
        };
    }
    private static Map<BlockPos,BlockState> generate(Map<String,Object> params) {
        var states = new HashMap<BlockPos,BlockState>();
        TailiangTimberHallBuilder.generate(params,BlockPos.ORIGIN,null,null).getBlocks()
                .forEach(b -> states.put(b.getPos(),b.getTargetState()));
        return states;
    }
    @Test void projectedCoursesRotateAndPreserveDoorwayAndRoof() {
        for (var f : List.of(Direction.SOUTH,Direction.NORTH,Direction.EAST,Direction.WEST)) {
            var p = new HashMap<String,Object>(Map.of("width",25,"depth",15,"anchorMode","corner",
                    "includeSubEaves",false,"facing",f.name(),"includeFacadeDetails",false));
            var off = generate(p);
            p.put("includeFacadeDetails",true);
            var on = generate(p);
            assertTrue(on.get(at(f,1,4,-1)).isOf(Blocks.DARK_OAK_SLAB));
            assertTrue(on.get(at(f,1,6,-1)).isOf(Blocks.DARK_OAK_SLAB));
            assertTrue(on.get(at(f,2,8,-1)).isOf(Blocks.DARK_OAK_PLANKS));
            for (int x=11;x<=13;x++) for(int y=3;y<=5;y++) for(int z=14;z<=15;z++)
                assertTrue(on.get(at(f,x,y,z)).isAir(),"doorway and projected approach remain open");
            for (var e : off.entrySet()) if (e.getKey().getY()>=9)
                assertEquals(e.getValue(),on.get(e.getKey()),"roof and its empty interior unchanged");
        }
    }
    @Test void windowsToggleAndExplicitTrimControlAddedDetails() {
        var p = new HashMap<String,Object>(Map.of("width",25,"depth",15,"anchorMode","corner",
                "includeSubEaves",false,"trim_block","minecraft:quartz_block","includeFacadeDetails",true));
        var on=generate(p);
        assertTrue(on.get(new BlockPos(1,4,-1)).isOf(Blocks.QUARTZ_BLOCK));
        p.put("includeWindows",false);
        var noWindows=generate(p);
        assertTrue(noWindows.get(new BlockPos(1,4,-1)).isAir());
        assertTrue(noWindows.get(new BlockPos(2,8,-1)).isOf(Blocks.QUARTZ_BLOCK));
    }
}
