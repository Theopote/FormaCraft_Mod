package com.formacraft.server.compiler;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.RoofGenerator;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class RoofShapeAuditTest {
    private SemanticComponent roof(int w,int d,int overhang) {
        var c=new Component("ROOF",null,new Vec3i(5,10,7),new Dimensions(w,d,4),List.of(),
                Map.of("component_id","roof_a","roof_type","gable","overhang",overhang));
        return new SemanticComponent("ROOF",null,c,"DEFAULT");
    }
    @Test void realGableBothAxesAndOverhangsPass() {
        for(var dims:List.of(new int[]{11,9},new int[]{9,11},new int[]{10,12},new int[]{12,10})) for(int overhang=0;overhang<=2;overhang++) {
            var roof=roof(dims[0],dims[1],overhang);
            var patches=new RoofGenerator().generate(roof);
            assertEquals("matched",RoofShapeAudit.inspect(roof,patches,BlockPos.ORIGIN).orElseThrow().status());
        }
    }
    @Test void flattenedFinalPatchesDoNotPassByParameterName() {
        var roof=roof(11,9,0);
        var patches=new RoofGenerator().generate(roof).stream()
                .map(p->new BlockPatch(p.action(),p.dx(),10,p.dz(),p.targetBlock())).toList();
        assertEquals("mismatch",RoofShapeAudit.inspect(roof,patches,BlockPos.ORIGIN).orElseThrow().status());
    }
    @Test void lastRemovalOnSlopeIsDetected() {
        var roof=roof(11,9,0);
        var patches=new ArrayList<>(new RoofGenerator().generate(roof));
        for(int y=10;y<=16;y++) patches.add(new BlockPatch(BlockPatch.REMOVE,10,y,11,"minecraft:air"));
        assertEquals("missing_slope_column",RoofShapeAudit.inspect(roof,patches,BlockPos.ORIGIN).orElseThrow().reason());
    }
    @Test void complexRoofsAreNotClaimedVerified() {
        var c=new Component("ROOF",null,new Vec3i(0,0,0),new Dimensions(11,9,4),List.of(),
                Map.of("roof_type","gable","roof_dormers","true"));
        var roof=new SemanticComponent("ROOF",null,c,"DEFAULT");
        assertEquals("not_assessed",RoofShapeAudit.inspect(roof,List.of(),BlockPos.ORIGIN).orElseThrow().status());
    }
    @Test void slotOffsetsAreIncluded() {
        var roof=roof(9,11,0); var offset=new BlockPos(30,8,-20);
        var patches=new RoofGenerator().generate(roof).stream().map(p->new BlockPatch(p.action(),
                p.dx()+30,p.dy()+8,p.dz()-20,p.targetBlock())).toList();
        assertEquals("matched",RoofShapeAudit.inspect(roof,patches,offset).orElseThrow().status());
    }
    @Test void offCenterDamageIsDetectedOnBothAxes() {
        for(var dims:List.of(new int[]{11,9},new int[]{9,11})) {
            var roof=roof(dims[0],dims[1],0);
            boolean alongDepth=dims[1]>=dims[0];
            var patches=new ArrayList<>(new RoofGenerator().generate(roof));
            int x=5+(alongDepth?4:1), z=7+(alongDepth?1:4);
            for(int y=10;y<=20;y++) patches.add(new BlockPatch(BlockPatch.REMOVE,x,y,z,"minecraft:air"));
            var audit=RoofShapeAudit.inspect(roof,patches,BlockPos.ORIGIN).orElseThrow();
            assertEquals("missing_slope_column",audit.reason());
            assertEquals(1,audit.sectionIndex());
        }
    }
    @Test void everyInteriorSectionIsCheckedAndReplacementRestoresColumn() {
        var roof=roof(9,11,0);
        var patches=new ArrayList<>(new RoofGenerator().generate(roof));
        var original=patches.stream().filter(p->p.dx()==9 && p.dz()==8).toList();
        for(int y=10;y<=20;y++) patches.add(new BlockPatch(BlockPatch.REMOVE,9,y,8,"minecraft:air"));
        patches.addAll(original);
        var audit=RoofShapeAudit.inspect(roof,patches,BlockPos.ORIGIN).orElseThrow();
        assertEquals("matched",audit.status());
        assertEquals(9,audit.checkedSections());
    }
    @Test void translatedSegmentsAreDetectedInBothDirectionsAndAxes() {
        for(var dims:List.of(new int[]{11,9},new int[]{9,11})) for(boolean raiseStart:List.of(false,true)) {
            var roof=roof(dims[0],dims[1],0);
            boolean alongDepth=dims[1]>=dims[0];
            var patches=new RoofGenerator().generate(roof).stream().map(p->{
                int section=alongDepth?p.dz()-7:p.dx()-5;
                boolean raised=raiseStart?section<4:section>=4;
                return new BlockPatch(p.action(),p.dx(),p.dy()+(raised?2:0),p.dz(),p.targetBlock());
            }).toList();
            var audit=RoofShapeAudit.inspect(roof,patches,BlockPos.ORIGIN).orElseThrow();
            assertEquals("longitudinal_section_shift",audit.reason());
            assertEquals(4,audit.sectionIndex());
        }
    }
    @Test void uniformTranslationOfWholeRoofRemainsValidWithMatchingOffset() {
        var roof=roof(10,12,1); var offset=new BlockPos(-17,3,26);
        var patches=new RoofGenerator().generate(roof).stream().map(p->new BlockPatch(p.action(),
                p.dx()-17,p.dy()+3,p.dz()+26,p.targetBlock())).toList();
        assertEquals("matched",RoofShapeAudit.inspect(roof,patches,offset).orElseThrow().status());
    }
}
