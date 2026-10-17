package com.formacraft.server.compiler;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.RoofGenerator;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LowRoofQuantizationTest {
    @Test void flattenedCoreOfATallGableCannotPassUsingItsOriginalEaves() {
        var roof = new Component("ROOF","s",new Vec3i(0,6,0),new Dimensions(8,11,5),List.of(),
                Map.of("component_id","damaged_roof","roof_type","gable","roof_height",5,"overhang",1));
        var semantic = new SemanticComponent("ROOF",null,roof);
        var patches = new RoofGenerator().generate(semantic).stream().map(p ->
                p.dx()>=0 && p.dx()<8 ? new BlockPatch(p.action(),p.dx(),8,p.dz(),p.targetBlock()) : p).toList();
        assertEquals("no_ridge_rise",RoofShapeAudit.inspect(semantic,patches,BlockPos.ORIGIN).orElseThrow().reason());
    }
    @Test void lowGableIsVerifiedByItsEavesButFlatteningStillFails() {
        var roof = new Component("ROOF","s",new Vec3i(0,6,0),new Dimensions(4,11,2),List.of(),
                Map.of("component_id","low_roof","roof_type","gable","roof_height",2,"overhang",1));
        var semantic = new SemanticComponent("ROOF",null,roof);
        var patches = new RoofGenerator().generate(semantic);
        assertEquals("matched",RoofShapeAudit.inspect(semantic,patches,BlockPos.ORIGIN).orElseThrow().status());
        var flat = patches.stream().map(p -> new BlockPatch(p.action(),p.dx(),6,p.dz(),p.targetBlock())).toList();
        assertEquals("no_ridge_rise",RoofShapeAudit.inspect(semantic,flat,BlockPos.ORIGIN).orElseThrow().reason());
    }
}
