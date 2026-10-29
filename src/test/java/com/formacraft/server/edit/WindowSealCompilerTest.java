package com.formacraft.server.edit;

import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.*;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WindowSealCompilerTest {
    @BeforeAll static void init() {MinecraftRegistryTestBootstrap.initialize();}
    private Map<BlockPos,BlockState> wall() {
        Map<BlockPos,BlockState> cells=new HashMap<>();
        for(int x=-2;x<=3;x++) for(int y=0;y<=4;y++) for(int z=0;z<=3;z++)
            cells.put(new BlockPos(x,y,z), z==0?Blocks.WHITE_TERRACOTTA.getDefaultState():Blocks.AIR.getDefaultState());
        for(int x=0;x<=1;x++) for(int y=1;y<=2;y++) cells.put(new BlockPos(x,y,0),Blocks.OAK_FENCE.getDefaultState());
        return cells;
    }
    @Test void sealsOnlyActualLatticeUsingExistingWhiteWall() {
        var result=WindowSealCompiler.compile(wall(),BlockPos.ORIGIN,new BlockPos(0,0,5),Set.of(),p->true,true,true);
        assertEquals(4,result.size());
        assertTrue(result.stream().allMatch(b->b.targetBlock().equals("minecraft:white_terracotta") && b.dz()==0 && b.dy()>=1 && b.dy()<=2));
    }
    @Test void exteriorWindowsAndRoofCellsAreNotSealed() {
        assertTrue(WindowSealCompiler.compile(wall(),BlockPos.ORIGIN,new BlockPos(0,0,5),Set.of(),p->false,true,true).isEmpty());
        var cells=wall();Set<BlockPos> roof=new HashSet<>();
        cells.forEach((p,s)->{if(s.getBlock() instanceof FenceBlock) roof.add(p);});
        assertTrue(WindowSealCompiler.compile(cells,BlockPos.ORIGIN,new BlockPos(0,0,5),roof,p->true,true,true).isEmpty());
    }
    @Test void refusesMissingWallEvidenceAndOneHighRailing() {
        var cells=wall();cells.replaceAll((p,s)->s.isOf(Blocks.WHITE_TERRACOTTA)?Blocks.OAK_PLANKS.getDefaultState():s);
        assertTrue(WindowSealCompiler.compile(cells,BlockPos.ORIGIN,BlockPos.ORIGIN,Set.of(),p->true,true,false).isEmpty());
        cells=wall();cells.put(new BlockPos(0,2,0),Blocks.AIR.getDefaultState());cells.put(new BlockPos(1,2,0),Blocks.AIR.getDefaultState());
        assertTrue(WindowSealCompiler.compile(cells,BlockPos.ORIGIN,BlockPos.ORIGIN,Set.of(),p->true,true,false).isEmpty());
    }
    @Test void recognizesActualRequestWithoutInterceptingWindowAddition() {
        assertTrue(WindowSealCompiler.matches("只去掉刚才这座徽派民居朝向天井的木格窗，并用原来的白色墙面封好窗洞。"));
        assertTrue(WindowSealCompiler.matches("去掉刚才的玻璃窗，用墙面封闭补回去，不要重建整栋。"));
        assertFalse(WindowSealCompiler.matches("只在白色墙面增加一扇玻璃窗"));
        assertFalse(WindowSealCompiler.matches("不要去掉白色墙面的木格窗"));
        assertFalse(WindowSealCompiler.matches("去掉白色玻璃窗，保留窗洞"));
    }
}
