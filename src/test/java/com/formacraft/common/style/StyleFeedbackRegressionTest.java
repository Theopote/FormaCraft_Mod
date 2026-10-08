package com.formacraft.common.style;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.compiler.postprocess.*;
import com.formacraft.common.generation.component.impl.*;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.test.*;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StyleFeedbackRegressionTest {
    @Test void explicitGlassMaterialWinsOverHuiLatticePalette() {
        var c=new Component("FACADE_WINDOWS",null,new Vec3i(0,0,0),new Dimensions(11,1,4),List.of(),
                Map.of("material","minecraft:glass","window_style","regular","reserve_entrance",false));
        var patches=new FacadeWindowsGenerator().generate(new SemanticComponent("FACADE_WINDOWS",null,c,"Chinese_Vernacular_Huizhou"));
        assertFalse(patches.isEmpty());
        assertTrue(patches.stream().allMatch(p->p.targetBlock().equals("minecraft:glass")));
    }
    @Test void decorResolvesGenericConcreteAndPrefersSpecificWallChoice() {
        MinecraftRegistryTestBootstrap.initialize();
        for(var params:List.of(Map.<String,Object>of("material","concrete"),
                Map.<String,Object>of("material","concrete","wall_block","minecraft:white_concrete"))) {
            var c=new Component("DECOR_DETAIL",null,new Vec3i(0,5,0),new Dimensions(11,9,1),List.of(),params);
            var blocks=new DecorDetailGenerator().generate(new SemanticComponent("DECOR_DETAIL",null,c,"DEFAULT"));
            assertFalse(blocks.isEmpty());
            assertTrue(blocks.stream().allMatch(p->p.targetBlock().equals(params.containsKey("wall_block")?
                    "minecraft:white_concrete":"minecraft:gray_concrete")));
        }
    }
    @Test void enhancementDoesNotAddMixedFoundationAndRoofRings() {
        var input=new ArrayList<BlockPatch>();
        for(int x=0;x<13;x++) for(int z=0;z<11;z++) {
            input.add(new BlockPatch(BlockPatch.PLACE,x,0,z,"minecraft:stone_bricks"));
            if(x>=1 && x<12 && z>=1 && z<10) input.add(new BlockPatch(BlockPatch.PLACE,x,6,z,"minecraft:deepslate_tiles"));
        }
        var context=PostProcessContext.create(LlmPlanTestFixtures.builder().styleProfile("DEFAULT").build(),BlockPos.ORIGIN);
        var before=PatchTestSnapshot.blocks(input);
        var after=PatchTestSnapshot.blocks(new DetailEnhancementPostProcessor().process(input,context));
        assertEquals(before.keySet(),after.keySet(),"Implicit decoration must not add rings at column maxima");
    }
    @Test void gableWindowExclusionLeavesSideWindows() {
        var c=new Component("FACADE_WINDOWS",null,new Vec3i(0,0,0),new Dimensions(11,9,6),List.of("wrap"),
                Map.of("excluded_window_axis","x","reserve_entrance",false));
        var patches=new FacadeWindowsGenerator().generate(new SemanticComponent("FACADE_WINDOWS",null,c,"DEFAULT"));
        assertFalse(patches.isEmpty());
        assertTrue(patches.stream().allMatch(p->p.dx()>0 && p.dx()<10));
        assertTrue(patches.stream().anyMatch(p->p.dz()==0 || p.dz()==8));
    }
}
