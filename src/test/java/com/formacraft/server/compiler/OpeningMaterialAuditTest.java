package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.impl.FacadeWindowsGenerator;
import com.formacraft.common.generation.component.util.GeneratedSurfaceCapture;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OpeningMaterialAuditTest {
    private Component component(Map<String,Object> params) {
        return new Component("FACADE_WINDOWS",null,new Vec3i(5,2,7),new Dimensions(13,9,8),
                List.of("wrap"),params);
    }
    private Map<BlockPos,OpeningMaterialAudit.Expectation> expectations(Component c,
            GeneratedSurfaceCapture.Role role,BlockPos pos) {
        var result=new LinkedHashMap<BlockPos,OpeningMaterialAudit.Expectation>();
        OpeningMaterialAudit.capture(result,c,List.of(new GeneratedSurfaceCapture.Cell(pos,role)),BlockPos.ORIGIN);
        return result;
    }
    @Test void realFacadeGeneratorRecordsGlassWindowsIncludingOffset() {
        var c=component(Map.of("glass_block","minecraft:glass","component_id","windows"));
        var cells=new ArrayList<GeneratedSurfaceCapture.Cell>(); List<BlockPatch> patches;
        try(var capture=GeneratedSurfaceCapture.captureTo(cells::add)) {
            patches=new FacadeWindowsGenerator().generate(new SemanticComponent("FACADE_WINDOWS",null,c,"Hui_Style"));
        }
        assertFalse(cells.isEmpty());
        assertTrue(cells.stream().allMatch(cell->cell.role()==GeneratedSurfaceCapture.Role.WINDOW));
        var offset=new BlockPos(20,3,-10);
        var expectations=new LinkedHashMap<BlockPos,OpeningMaterialAudit.Expectation>();
        OpeningMaterialAudit.capture(expectations,c,cells,offset);
        var shifted=patches.stream().map(p->new BlockPatch(p.action(),p.dx()+20,p.dy()+3,p.dz()-10,p.targetBlock())).toList();
        var result=OpeningMaterialAudit.inspect(expectations,shifted);
        assertEquals("matched",result.status());
        assertEquals(cells.size(),result.checkedWindows());
    }
    @Test void finalMaterialReplacementIsDetectedAndLaterGlassRestoresIt() {
        var pos=new BlockPos(1,2,3);
        var expected=expectations(component(Map.of("glass_block","glass")),GeneratedSurfaceCapture.Role.WINDOW,pos);
        var patches=new ArrayList<>(List.of(new BlockPatch(BlockPatch.PLACE,1,2,3,"minecraft:glass"),
                new BlockPatch(BlockPatch.PLACE,1,2,3,"minecraft:iron_bars[east=true]")));
        var result=OpeningMaterialAudit.inspect(expected,patches);
        assertEquals("window_material_changed",result.firstIssue().reason());
        assertEquals("minecraft:iron_bars",result.firstIssue().actual());
        patches.add(new BlockPatch(BlockPatch.PLACE,1,2,3,"minecraft:glass"));
        assertEquals("matched",OpeningMaterialAudit.inspect(expected,patches).status());
    }
    @Test void entranceAllowsAirOrDoorButRejectsBarsAndWall() {
        var expected=expectations(component(Map.of()),GeneratedSurfaceCapture.Role.DOOR_OPENING,BlockPos.ORIGIN);
        for(String material:List.of("minecraft:iron_bars","minecraft:stone_bricks"))
            assertEquals("door_opening_blocked",OpeningMaterialAudit.inspect(expected,List.of(
                    new BlockPatch(BlockPatch.PLACE,0,0,0,material))).firstIssue().reason());
        for(String material:List.of("minecraft:air","minecraft:oak_door[half=lower]"))
            assertEquals("matched",OpeningMaterialAudit.inspect(expected,List.of(
                    new BlockPatch(BlockPatch.PLACE,0,0,0,material))).status());
        assertEquals("matched",OpeningMaterialAudit.inspect(expected,List.of(
                new BlockPatch(BlockPatch.REMOVE,0,0,0,"minecraft:stone"))).status());
    }
    @Test void missingPatchIsUnknownInsteadOfClaimedAnOpenEntrance() {
        var expected=expectations(component(Map.of()),GeneratedSurfaceCapture.Role.DOOR_OPENING,BlockPos.ORIGIN);
        var result=OpeningMaterialAudit.inspect(expected,List.of());
        assertEquals("not_assessed",result.status());
        assertEquals(1,result.unresolvedCells());
    }
    @Test void laterDeclaredWallSupersedesEarlierWindow() {
        var c=component(Map.of("glass_block","glass"));
        var expected=expectations(c,GeneratedSurfaceCapture.Role.WINDOW,BlockPos.ORIGIN);
        OpeningMaterialAudit.capture(expected,c,List.of(new GeneratedSurfaceCapture.Cell(BlockPos.ORIGIN,
                GeneratedSurfaceCapture.Role.WALL)),BlockPos.ORIGIN);
        assertTrue(expected.isEmpty());
    }
    @Test void explicitLatticeIsRespectedAndUnspecifiedMaterialIsNotGuessed() {
        var c=component(Map.of("material","minecraft:iron_bars"));
        var expected=expectations(c,GeneratedSurfaceCapture.Role.WINDOW,BlockPos.ORIGIN);
        assertEquals("matched",OpeningMaterialAudit.inspect(expected,List.of(
                new BlockPatch(BlockPatch.PLACE,0,0,0,"minecraft:iron_bars"))).status());
        assertTrue(expectations(component(Map.of()),GeneratedSurfaceCapture.Role.WINDOW,BlockPos.ORIGIN).isEmpty());
    }
}
