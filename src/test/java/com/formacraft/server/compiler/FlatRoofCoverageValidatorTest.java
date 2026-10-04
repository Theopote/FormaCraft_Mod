package com.formacraft.server.compiler;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FlatRoofCoverageValidatorTest {
    private SemanticComponent body(Map<String,Object> extra) {
        var params = new HashMap<String,Object>(Map.of("component_id","house","anchor_mode","min_corner"));
        params.putAll(extra);
        return new SemanticComponent("MASS_MAIN",null,new Component("MASS_MAIN","body",new Vec3i(0,0,0),
                new Dimensions(9,9,8),List.of(),params));
    }
    @Test void completeSmallOrShiftedRoofStillFailsHostCoverageAndOverhangCanCoverHost() {
        for (var roof : List.of(new Component("ROOF","roof",new Vec3i(0,7,0),new Dimensions(5,5,1),List.of(),
                        Map.of("roof_type","flat","host_id","house")),
                new Component("ROOF","roof",new Vec3i(1,7,0),new Dimensions(9,9,1),List.of(),
                        Map.of("roof_type","flat","host_id","house")))) {
            var r = FlatRoofCoverageValidator.resolve(new SemanticComponent("ROOF",null,roof),BlockPos.ORIGIN).orElseThrow();
            assertTrue(FlatRoofCoverageValidator.check(List.of(r),patches(r,0),Set.of()).isEmpty());
            assertTrue(FlatRoofCoverageValidator.checkHost(body(Map.of()),List.of(r),BlockPos.ORIGIN,patches(r,0),Set.of()).isPresent());
        }
        var roof = new Component("ROOF","roof",new Vec3i(1,7,1),new Dimensions(7,7,1),List.of(),
                Map.of("roof_type","flat","host_id","house","overhang",1));
        var r = FlatRoofCoverageValidator.resolve(new SemanticComponent("ROOF",null,roof),BlockPos.ORIGIN).orElseThrow();
        var blocks = r.coverage().stream().map(p -> new BlockPatch(BlockPatch.PLACE,p.getX(),p.getY(),p.getZ(),"minecraft:stone_bricks")).toList();
        assertTrue(FlatRoofCoverageValidator.checkHost(body(Map.of()),List.of(r),BlockPos.ORIGIN,blocks,Set.of()).isEmpty());
    }
    @Test void matchingCourtyardMaskAndExplicitStairOpeningRemainLegal() {
        var c = roof(Map.of("roof_type","flat","host_id","house","plan_type","courtyard"));
        var r = FlatRoofCoverageValidator.resolve(new SemanticComponent("ROOF",null,c),BlockPos.ORIGIN).orElseThrow();
        assertTrue(FlatRoofCoverageValidator.checkHost(body(Map.of("plan_type","courtyard")),List.of(r),BlockPos.ORIGIN,patches(r,0),Set.of()).isEmpty());
        var plain = FlatRoofCoverageValidator.resolve(new SemanticComponent("ROOF",null,roof(Map.of("roof_type","flat","host_id","house"))),BlockPos.ORIGIN).orElseThrow();
        var opening = new BlockPos(2,7,2);
        var blocks = new ArrayList<>(patches(plain,0));
        blocks.add(new BlockPatch(BlockPatch.REMOVE,2,7,2,"minecraft:air"));
        assertTrue(FlatRoofCoverageValidator.checkHost(body(Map.of()),List.of(plain),BlockPos.ORIGIN,blocks,Set.of(opening)).isEmpty());
    }
    @Test void higherBodyHidesLowerTopWithoutRequiringRoofUnderIt() {
        var body = new Component("MASS_MAIN","body",new Vec3i(0,0,0),new Dimensions(4,4,5),List.of(),
                Map.of("component_id","house","anchor_mode","min_corner","masses",List.of(Map.of(
                        "offset",Map.of("x",2,"y",0,"z",2),"dimensions",Map.of("width",2,"depth",2,"height",8)))));
        var roof = new Component("ROOF","roof",new Vec3i(0,4,0),new Dimensions(4,4,1),List.of(),
                Map.of("roof_type","flat","host_id","house"));
        var offset = new BlockPos(20,3,30);
        var r = FlatRoofCoverageValidator.resolve(new SemanticComponent("ROOF",null,roof),offset).orElseThrow();
        var blocks = patches(r,0).stream().filter(p -> !(p.dx() >= 22 && p.dz() >= 32)).toList();
        assertEquals(12,blocks.size());
        assertTrue(FlatRoofCoverageValidator.checkHost(new SemanticComponent("MASS_MAIN",null,body),List.of(r),offset,blocks,Set.of()).isEmpty());
        var exposed = new ArrayList<>(blocks);
        exposed.add(new BlockPatch(BlockPatch.REMOVE,20,7,30,"minecraft:air"));
        assertEquals(new BlockPos(20,7,30), FlatRoofCoverageValidator.checkHost(new SemanticComponent("MASS_MAIN",null,body),
                List.of(r),offset,exposed,Set.of()).orElseThrow().position());
    }
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
