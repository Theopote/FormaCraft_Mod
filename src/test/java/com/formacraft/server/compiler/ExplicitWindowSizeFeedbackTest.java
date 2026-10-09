package com.formacraft.server.compiler;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.FacadeWindowsGenerator;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExplicitWindowSizeFeedbackTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    private Component facade(String wall) {
        return new Component("FACADE_WINDOWS","house",new Vec3i(0,0,0),new Dimensions(15,13,10),List.of("wrap"),
                Map.of("component_id","windows","host_id","house","wall",wall,"window_width",2,
                        "window_height",2,"sill_offset",1,"floor_height",5,"wall_thickness",2,"glass_block","minecraft:glass"));
    }
    private Set<BlockPos> glass(List<BlockPatch> patches) {
        var result=new HashSet<BlockPos>();
        for(var p:patches) if("minecraft:glass".equals(p.targetBlock())) result.add(new BlockPos(p.dx(),p.dy(),p.dz()));
        return result;
    }
    private void assertRectangles(Set<BlockPos> cells) {
        assertFalse(cells.isEmpty());
        var remaining=new HashSet<>(cells);
        while(!remaining.isEmpty()) {
            var start=remaining.iterator().next(); var part=new HashSet<BlockPos>(); var queue=new ArrayDeque<BlockPos>();
            remaining.remove(start); queue.add(start);
            while(!queue.isEmpty()) {
                var pos=queue.remove(); part.add(pos);
                for(var delta:List.of(new BlockPos(1,0,0),new BlockPos(-1,0,0),new BlockPos(0,1,0),
                        new BlockPos(0,-1,0),new BlockPos(0,0,1),new BlockPos(0,0,-1))) {
                    var next=pos.add(delta); if(remaining.remove(next)) queue.add(next);
                }
            }
            assertEquals(4,part.size(),"each window must contain exactly 2x2 glass cells");
            assertEquals(2,part.stream().map(BlockPos::getY).distinct().count());
            assertEquals(2,Math.max(part.stream().map(BlockPos::getX).distinct().count(),part.stream().map(BlockPos::getZ).distinct().count()));
        }
    }
    @Test void explicitFrontWindowsSurviveHostGableOptOut() throws Exception {
        var mass=new Component("MASS_MAIN","house",new Vec3i(0,0,0),new Dimensions(15,13,10),List.of(),
                Map.of("component_id","house","anchor_mode","min_corner","gable_windows",false,"floor_height",5,"floor_count",2));
        var plan=LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).styleProfile("Hui_Style").build();
        var method=ComponentPlanCompiler.class.getDeclaredMethod("alignFacadeToMass",Component.class,Component.class,LlmPlan.class,GlobalConstraints.Facing.class);
        method.setAccessible(true);
        var aligned=(Component)method.invoke(null,facade("front"),mass,plan,GlobalConstraints.Facing.WEST);
        assertFalse(aligned.params().containsKey("excluded_window_axis"));
        var slot=new Slot("house",new Vec3i(0,0,0),GlobalConstraints.Facing.WEST,null,null,null);
        var patches=new FacadeWindowsGenerator().generate(new SemanticComponent("FACADE_WINDOWS",slot,aligned,"Hui_Style"));
        var cells=glass(patches); assertRectangles(cells);
        assertTrue(cells.stream().allMatch(p->p.getX()==14));
        assertTrue(cells.stream().anyMatch(p->p.getZ()<5));
        assertTrue(cells.stream().anyMatch(p->p.getZ()>7));
        assertEquals(Set.of(1,2,6,7),cells.stream().map(BlockPos::getY).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void bothSideWallsHaveExactRectanglesAndThickWallsAreCarved() {
        var slot=new Slot("house",new Vec3i(0,0,0),GlobalConstraints.Facing.WEST,null,null,null);
        var patches=new FacadeWindowsGenerator().generate(new SemanticComponent("FACADE_WINDOWS",slot,facade("left_right"),"Hui_Style"));
        var cells=glass(patches); assertRectangles(cells);
        assertEquals(Set.of(0,12),cells.stream().map(BlockPos::getZ).collect(java.util.stream.Collectors.toSet()));
        for(var p:cells) {
            int inside=p.getZ()==0?1:11;
            assertTrue(patches.stream().anyMatch(q->BlockPatch.REMOVE.equals(q.action()) && q.dx()==p.getX() && q.dy()==p.getY() && q.dz()==inside));
        }
    }
    @Test void dimensionsRemainExactOnBothEntranceAxes() {
        for(var facing:GlobalConstraints.Facing.values()) {
            var slot=new Slot("house",new Vec3i(0,0,0),facing,null,null,null);
            assertRectangles(glass(new FacadeWindowsGenerator().generate(
                    new SemanticComponent("FACADE_WINDOWS",slot,facade("front"),"Hui_Style"))));
        }
    }
    @Test void shortStoreysRetainFullTopStoreyWindows() {
        var base=facade("left_right"); var params=new HashMap<String,Object>(base.params());
        params.put("floor_height",3);
        var c=new Component("FACADE_WINDOWS","house",new Vec3i(0,0,0),new Dimensions(15,13,6),base.features(),params);
        var slot=new Slot("house",new Vec3i(0,0,0),GlobalConstraints.Facing.WEST,null,null,null);
        var cells=glass(new FacadeWindowsGenerator().generate(new SemanticComponent("FACADE_WINDOWS",slot,c,"Hui_Style")));
        assertRectangles(cells);
        assertEquals(Set.of(1,2,4,5),cells.stream().map(BlockPos::getY).collect(java.util.stream.Collectors.toSet()));
    }
}
