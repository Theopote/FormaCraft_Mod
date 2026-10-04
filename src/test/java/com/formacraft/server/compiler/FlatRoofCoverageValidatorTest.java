package com.formacraft.server.compiler;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FlatRoofCoverageValidatorTest {
    private Component roof(Map<String,Object> params) {
        return new Component("ROOF", "roof", new Vec3i(0,7,0), new Dimensions(9,9,3), List.of(), params);
    }
    private List<BlockPatch> patches(FlatRoofCoverageValidator.Roof roof, int yShift) {
        return roof.core().stream().map(p -> new BlockPatch(BlockPatch.PLACE, p.getX(), p.getY()+yShift, p.getZ(), "minecraft:stone_bricks")).toList();
    }
    @Test void detectsMissingCoreAndWrongHeightButAllowsExplicitStairClearance() {
        var core = FlatRoofCoverageValidator.resolve(new SemanticComponent("ROOF",null,roof(Map.of("roof_type","flat"))),
                new BlockPos(10,2,20)).orElseThrow();
        var result = new ArrayList<>(patches(core,0));
        assertTrue(FlatRoofCoverageValidator.check(List.of(core), result, Set.of()).isEmpty());
        BlockPos first = core.core().iterator().next();
        result.add(new BlockPatch(BlockPatch.REMOVE, first.getX(),first.getY(),first.getZ(), "minecraft:air"));
        assertEquals(1, FlatRoofCoverageValidator.check(List.of(core),result,Set.of()).orElseThrow().count());
        assertTrue(FlatRoofCoverageValidator.check(List.of(core),result,Set.of(first)).isEmpty());
        assertEquals(81, FlatRoofCoverageValidator.check(List.of(core),patches(core,1),Set.of()).orElseThrow().count());
    }
    @Test void shapeMasksPreserveCircleLShapeAndCourtyardVoids() {
        for (var shape : List.of(Map.<String,Object>of("shape","circle"), Map.<String,Object>of("plan_type","l_shape"),
                Map.<String,Object>of("plan_type","courtyard"))) {
            var params = new HashMap<>(shape); params.put("roof_type","flat");
            var core = FlatRoofCoverageValidator.resolve(new SemanticComponent("ROOF",null,roof(params)), BlockPos.ORIGIN).orElseThrow();
            assertTrue(core.core().size() < 81 && !core.core().isEmpty(), shape.toString());
            assertTrue(FlatRoofCoverageValidator.check(List.of(core),patches(core,0),Set.of()).isEmpty());
        }
        assertTrue(FlatRoofCoverageValidator.resolve(new SemanticComponent("ROOF",null,roof(Map.of("roof_type","gable"))),BlockPos.ORIGIN).isEmpty());
    }
    @Test void explicitAnnexHostUsesOwnHeightAndSlotTranslationOnce() {
        var body = new Component("MASS_MAIN","body",new Vec3i(0,0,0),new Dimensions(6,6,8),List.of(),
                Map.of("component_id","house","anchor_mode","min_corner","masses",List.of(Map.of(
                        "offset",Map.of("x",6,"y",0,"z",2),"dimensions",Map.of("width",4,"depth",4,"height",5)))));
        var roof = new Component("ROOF","roof",new Vec3i(6,4,2),new Dimensions(4,4,1),List.of(),
                Map.of("roof_type","flat","host_id","house","host_part_id","house#mass_1"));
        var slot = new Slot("body",new Vec3i(20,10,30),null,null,null,null);
        assertTrue(FlatRoofCoverageValidator.attachmentMismatch(new SemanticComponent("ROOF",slot,roof),List.of(body),Map.of("body",slot),null).isEmpty());
        var wrong = new Slot("roof",new Vec3i(20,11,30),null,null,null,null);
        var mismatch = FlatRoofCoverageValidator.attachmentMismatch(new SemanticComponent("ROOF",wrong,roof),List.of(body),Map.of("body",slot),null).orElseThrow();
        assertEquals(14,mismatch.expectedY()); assertEquals(15,mismatch.actualY());
    }
}
