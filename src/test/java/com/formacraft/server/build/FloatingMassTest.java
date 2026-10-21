package com.formacraft.server.build;

import com.formacraft.common.skeleton.*;
import com.formacraft.server.skeleton.gen.*;
import com.formacraft.common.llm.dto.LlmPlan;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FloatingMassTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }

    @Test void registryExecutesElevatedDownwardTaperAtTwoScales() {
        for(int size:List.of(13,21)) {
            var plan=ExecutableSkeletonPlan.fromMap(Map.of("type","FLOATING_MASS","params",
                    Map.of("width",size,"depth",size,"height",9,"elevation",12)));
            assertEquals(SkeletonType.FLOATING_MASS,plan.type);
            assertFalse(plan.type.getContract().requiresTerrainSampling());
            var patches=SkeletonGeneratorRegistry.createDefault().get(plan.type).generate(
                    new GenerationContext(null,BlockPos.ORIGIN,100000),plan);
            assertFalse(patches.isEmpty());
            assertEquals(12,patches.stream().mapToInt(p->p.dy()).min().orElseThrow());
            assertEquals(20,patches.stream().mapToInt(p->p.dy()).max().orElseThrow());
            assertTrue(patches.stream().filter(p->p.dy()==20).count()>patches.stream().filter(p->p.dy()==12).count());
            assertEquals(patches.size(),new HashSet<>(patches).size());
        }
    }

    @Test void invalidParametersAndBudgetCannotProduceAPartialIsland() {
        var generator=new FloatingMassGenerator();
        for(Object bad:List.of(0,500,Double.NaN,"nonsense",9.5)) {
            var plan=new ExecutableSkeletonPlan(SkeletonType.FLOATING_MASS).put("width",bad);
            assertThrows(IllegalArgumentException.class,()->generator.generate(new GenerationContext(null,BlockPos.ORIGIN,100000),plan));
        }
        assertThrows(IllegalArgumentException.class,()->generator.generate(new GenerationContext(null,BlockPos.ORIGIN,5),
                new ExecutableSkeletonPlan(SkeletonType.FLOATING_MASS)));
    }

    @Test void solidIslandIsConnectedAndHollowOptionKeepsItsPlatform() {
        var solid=com.formacraft.common.geometry.shape.ShapeLibrary.generate(
                com.formacraft.common.geometry.shape.ShapeSpec.fromParams(21,21,9,Map.of("kind","floating_mass")));
        var remaining=new HashSet<BlockPos>();
        solid.forEach(v->remaining.add(new BlockPos(v.x(),v.y(),v.z())));
        var queue=new ArrayDeque<BlockPos>();
        queue.add(remaining.iterator().next()); remaining.remove(queue.peek());
        while(!queue.isEmpty()) {
            var p=queue.remove();
            for(var direction:net.minecraft.util.math.Direction.values()) {
                var neighbor=p.offset(direction);
                if(remaining.remove(neighbor)) queue.add(neighbor);
            }
        }
        assertTrue(remaining.isEmpty(),"Island must form one connected volume");
        var hollow=com.formacraft.common.geometry.shape.ShapeLibrary.generate(
                com.formacraft.common.geometry.shape.ShapeSpec.fromParams(21,21,9,Map.of("kind","floating_mass","hollow",true)));
        assertTrue(hollow.size()>0 && hollow.size()<solid.size());
        assertEquals(solid.stream().filter(v->v.y()==8).count(),hollow.stream().filter(v->v.y()==8).count());
    }

    @Test void primitivePlansUseTheSameGeometryAndPreserveClippingParity() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        for(int size:List.of(13,21)) {
            var plan=mapper.readValue(java.nio.file.Path.of("python_backend/eval/fixtures/plans/p2_floating_mass_"+size+".json").toFile(),LlmPlan.class);
            var origin=BlockPos.ORIGIN;
            var patches=com.formacraft.server.compiler.ComponentPlanCompiler.compile(plan,origin,null,null,false);
            var skeleton=new ExecutableSkeletonPlan(SkeletonType.FLOATING_MASS).put("width",size).put("depth",size)
                    .put("height",9).put("elevation",12).put("block","minecraft:stone");
            var expected=new FloatingMassGenerator().generate(new GenerationContext(null,origin,100000),skeleton);
            assertEquals(new HashSet<>(expected),new HashSet<>(patches));
            var min=new BlockPos(0,0,0); var max=new BlockPos(size/2,64,size);
            var bounds=new BuildConstraints(min,max,null,List.of());
            var clipped=patches.stream().filter(p->bounds.allow(origin.add(p.dx(),p.dy(),p.dz()))).toList();
            assertFalse(clipped.isEmpty()); assertTrue(clipped.size()<patches.size());
            assertEquals(clipped,com.formacraft.server.patch.ServerPatchFilter.filter(origin,patches,List.of(),null,true,min,max).accepted);
            var snapshot=new com.formacraft.common.tool.ToolConstraintSnapshot(
                    new com.formacraft.common.buildcontext.SelectionBox(min,max),null,List.of(),List.of(),
                    com.formacraft.common.tool.ToolConstraintSnapshot.SymmetryConstraint.none());
            assertEquals(clipped,com.formacraft.common.patch.filter.PatchFilterPipeline.createDefault().apply(patches,origin,
                    new com.formacraft.common.patch.filter.PatchFilterContext(snapshot)));
        }
    }
}
