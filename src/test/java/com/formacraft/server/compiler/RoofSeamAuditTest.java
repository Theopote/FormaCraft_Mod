package com.formacraft.server.compiler;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.RoofGenerator;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RoofSeamAuditTest {
    private Component body() {
        return new Component("MASS_MAIN","body",new Vec3i(5,2,7),new Dimensions(9,11,8),List.of(),
                Map.of("component_id","house","anchor_mode","min_corner"));
    }
    private SemanticComponent roof(String type,int y,Map<String,Object> extra,Slot slot) {
        var params=new HashMap<String,Object>(Map.of("component_id","roof","host_id","house","roof_type",type));
        params.putAll(extra);
        return new SemanticComponent("ROOF",slot,new Component("ROOF","roof",new Vec3i(5,y,7),
                new Dimensions(9,11,4),List.of(),params),"DEFAULT");
    }
    private List<BlockPatch> patches(SemanticComponent roof,BlockPos offset) {
        var out=new ArrayList<BlockPatch>();
        // Known rectangular wall shell; interior deliberately remains empty.
        for(int x=5;x<14;x++) for(int z=7;z<18;z++) if(x==5 || x==13 || z==7 || z==17)
            for(int y=2;y<10;y++) out.add(new BlockPatch(BlockPatch.PLACE,x,y,z,"minecraft:stone_bricks"));
        out.addAll(new RoofGenerator().generate(roof));
        return out.stream().map(p->new BlockPatch(p.action(),p.dx()+offset.getX(),p.dy()+offset.getY(),
                p.dz()+offset.getZ(),p.targetBlock())).toList();
    }
    private RoofSeamAudit.Result inspect(SemanticComponent roof,List<BlockPatch> patches) {
        return RoofSeamAudit.inspect(roof,List.of(body()),Map.of(),null,patches).orElseThrow();
    }
    @Test void realRoofSeamsPassAtOverlapAndAdjacentAttachmentPlanes() {
        for(String type:List.of("flat","gable","double_gable")) for(int y:List.of(9,10)) {
            var roof=roof(type,y,Map.of(),null);
            var audit=inspect(roof,patches(roof,BlockPos.ORIGIN));
            assertEquals("matched",audit.status(),type+"@"+y);
            assertEquals(72,audit.checkedCells());
        }
    }
    @Test void finalRemovalBreaksSeamAndLaterReplacementRepairsIt() {
        var roof=roof("gable",10,Map.of(),null);
        var patches=new ArrayList<>(patches(roof,BlockPos.ORIGIN));
        patches.add(new BlockPatch(BlockPatch.REMOVE,5,9,9,"minecraft:air"));
        var audit=inspect(roof,patches);
        assertEquals("missing_seam_cell",audit.reason());
        assertEquals(new BlockPos(5,9,9),audit.firstMissing());
        assertEquals(1,audit.missingCells());
        patches.add(new BlockPatch(BlockPatch.PLACE,5,9,9,"minecraft:stone_bricks"));
        assertEquals("matched",inspect(roof,patches).status());
    }
    @Test void floatingRoofIsReportedWithoutPretendingAnUnboundRoofPassed() {
        var roof=roof("gable",12,Map.of(),null);
        assertEquals("vertical_alignment",inspect(roof,patches(roof,BlockPos.ORIGIN)).reason());
        assertEquals("unresolved_host",RoofSeamAudit.inspect(roof,List.of(),Map.of(),null,List.of()).orElseThrow().reason());
    }
    @Test void separateSlotsAreResolvedInAllThreeAxes() {
        var bodySlot=new Slot("body",new Vec3i(20,8,-30),null,null,null,null);
        var roofSlot=new Slot("roof",new Vec3i(20,8,-30),null,null,null,null);
        var roof=roof("gable",10,Map.of(),roofSlot);
        assertEquals("matched",RoofSeamAudit.inspect(roof,List.of(body()),Map.of("body",bodySlot),null,
                patches(roof,new BlockPos(20,8,-30))).orElseThrow().status());
        var shifted=new Slot("roof",new Vec3i(21,8,-30),null,null,null,null);
        var wrong=roof("gable",10,Map.of(),shifted);
        assertEquals("unaligned_footprint",RoofSeamAudit.inspect(wrong,List.of(body()),Map.of("body",bodySlot),null,List.of()).orElseThrow().reason());
    }
    @Test void OpenGablesAndOverhangsRemainExplicitlyUnassessed() {
        for(var params:List.of(Map.<String,Object>of("gable_walls","false"),Map.<String,Object>of("overhang",1))) {
            var roof=roof("gable",10,params,null);
            assertEquals("not_assessed",inspect(roof,List.of()).status());
        }
    }
}
