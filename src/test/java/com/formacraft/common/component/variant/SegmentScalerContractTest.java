package com.formacraft.common.component.variant;

import com.formacraft.common.component.model.ComponentPrototype;
import com.formacraft.common.component.model.PersistedComponentVariant;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SegmentScalerContractTest {
    private ComponentPrototype.VariantRules.Scaling rules(String axis, String mode, int edges) {
        var rules = new ComponentPrototype.VariantRules.Scaling();
        rules.axes = new HashMap<>();
        var rule = new ComponentPrototype.VariantRules.Scaling.AxisRule();
        rule.type = mode; rule.fixedStart = edges; rule.fixedEnd = edges;
        rules.axes.put(axis, rule);
        return rules;
    }
    private PersistedComponentVariant.Params.Scale target(int x, int y, int z) {
        var t = new PersistedComponentVariant.Params.Scale(); t.x=x; t.y=y; t.z=z; return t;
    }
    private StructureTemplate line(int size, int min) {
        var voxels = new ArrayList<Voxel>();
        for (int x=0;x<size;x++) voxels.add(new Voxel(min+x,0,0,"slice_"+x));
        return new StructureTemplate(voxels,size,1,1);
    }
    private List<String> states(VoxelGrid grid) {
        return grid.all().stream().sorted(Comparator.comparingInt(Voxel::x)).map(Voxel::blockState).toList();
    }
    @Test void repeatsMultipleSliceMotifToExactLength() {
        var out=SegmentScaler.applyScaling(line(4,0),rules("X","REPEAT",1),target(7,1,1));
        assertEquals(List.of("slice_0","slice_1","slice_2","slice_1","slice_2","slice_1","slice_3"),states(out));
        assertArrayEquals(new int[]{0,6},out.getAxisRange(Voxel.Axis.X));
        assertEquals(7,out.size());
    }
    @Test void shorteningPreservesBothEnds() {
        var out=SegmentScaler.applyScaling(line(6,0),rules("X","TRIM",1),target(4,1,1));
        assertEquals(List.of("slice_0","slice_1","slice_2","slice_5"),states(out));
    }
    @Test void thickCapsAreNeverRepeatedOrTrimmed() {
        var out=SegmentScaler.applyScaling(line(7,0),rules("X","REPEAT",2),target(5,1,1));
        assertEquals(List.of("slice_0","slice_1","slice_2","slice_5","slice_6"),states(out));
    }
    @Test void negativeCoordinatesKeepAnchorStable() {
        var out=SegmentScaler.applyScaling(line(5,-2),rules("X","REPEAT",1),target(7,1,1));
        assertArrayEquals(new int[]{-3,3},out.getAxisRange(Voxel.Axis.X));
        assertEquals("slice_0",out.all().stream().filter(v->v.x()==-3).findFirst().orElseThrow().blockState());
        assertEquals("slice_4",out.all().stream().filter(v->v.x()==3).findFirst().orElseThrow().blockState());
    }
    @Test void twoAxesComposeRatherThanDiscardingFirstAxis() {
        var voxels=new ArrayList<Voxel>();
        for(int x=0;x<3;x++) for(int y=0;y<3;y++) voxels.add(new Voxel(x,y,0,"stone"));
        var rules=rules("X","REPEAT",1);
        rules.axes.put("Y",rules("Y","REPEAT",1).axes.get("Y"));
        var out=SegmentScaler.applyScaling(new StructureTemplate(voxels,3,3,1),rules,target(5,6,1));
        assertEquals(30,out.size());
        assertArrayEquals(new int[]{0,4},out.getAxisRange(Voxel.Axis.X));
        assertArrayEquals(new int[]{0,5},out.getAxisRange(Voxel.Axis.Y));
        assertEquals(30,out.all().stream().map(v->v.x()+","+v.y()+","+v.z()).distinct().count());
    }
    @Test void emptyMiddleSlicesRemainEmpty() {
        var template=new StructureTemplate(List.of(new Voxel(0,0,0,"cap"),new Voxel(1,0,0,"a"),
                new Voxel(3,0,0,"b"),new Voxel(4,0,0,"end")),5,1,1);
        var out=SegmentScaler.applyScaling(template,rules("X","REPEAT",1),target(8,1,1));
        assertFalse(out.all().stream().anyMatch(v->v.x()==2||v.x()==5));
        assertEquals(6,out.size());
    }
    @Test void rejectsImpossibleSizeWithoutRemovingCaps() {
        assertThrows(IllegalArgumentException.class,()->SegmentScaler.applyScaling(line(5,0),rules("X","REPEAT",1),target(2,1,1)));
    }
    @Test void trimCannotEnlarge() {
        assertThrows(IllegalArgumentException.class,()->SegmentScaler.applyScaling(line(5,0),rules("X","TRIM",1),target(6,1,1)));
    }
    @Test void zeroTargetMeansUnspecified() {
        assertEquals(states(SegmentScaler.applyScaling(line(5,0),null,null)),
                states(SegmentScaler.applyScaling(line(5,0),rules("X","REPEAT",1),target(0,0,0))));
    }
    @Test void unchangedTemplateIsDeepCopied() {
        var tpl=line(3,0);
        var out=SegmentScaler.applyScaling(tpl,null,null);
        out.all().getFirst().setBlockState("changed");
        assertEquals("slice_0",tpl.all().getFirst().blockState());
    }
    @Test void rejectsPairedObjectsInMovableBand() {
        var tpl=new StructureTemplate(List.of(new Voxel(0,0,0,"cap"),
                new Voxel(1,0,0,"minecraft:oak_door[half=lower]"),new Voxel(2,0,0,"cap")),3,1,1);
        assertThrows(IllegalArgumentException.class,()->SegmentScaler.applyScaling(tpl,rules("X","REPEAT",1),target(4,1,1)));
    }
    @Test void enforcesSizeBudget() {
        assertThrows(IllegalArgumentException.class,()->SegmentScaler.applyScaling(line(5,0),rules("X","REPEAT",1),target(100000,1,1)));
    }
}
