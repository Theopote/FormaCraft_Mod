package com.formacraft.server.compiler;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.assembly.AssemblyCirculationConstraints.Flight;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class FloorAccessAuditTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    @Test void finalWritesAndClearanceDetermineObservedLevelsWithSlotOffset() {
        var mass=new Component("MASS_MAIN","house",new Vec3i(0,0,0),new Dimensions(15,13,11),List.of(),
                Map.of("component_id","house","anchor_mode","min_corner","floor_count",2,"floor_height",5));
        var offset=new BlockPos(100,20,-30);
        var a=offset.add(3,0,3); var b=offset.add(8,5,3);
        var flight=new Flight(Set.of(a,b),Set.of(a.up(),a.up(2),b.up(),b.up(2)));
        var patches=new ArrayList<BlockPatch>();
        for(var pos:List.of(a,b)) {
            patches.add(new BlockPatch(BlockPatch.PLACE,pos.getX(),pos.getY(),pos.getZ(),"minecraft:spruce_planks"));
            for(int dy=1;dy<=2;dy++) patches.add(new BlockPatch(BlockPatch.REMOVE,pos.getX(),pos.getY()+dy,pos.getZ(),"minecraft:air"));
        }
        var ok=FloorAccessAudit.inspect(mass,offset,patches,List.of(flight)).orElseThrow();
        assertEquals(List.of(1,2),ok.observedFloors()); assertEquals("not_assessed",ok.connectivity());
        patches.add(new BlockPatch(BlockPatch.PLACE,b.getX(),b.getY()+2,b.getZ(),"minecraft:stone"));
        assertEquals(List.of(2),FloorAccessAudit.inspect(mass,offset,patches,List.of(flight)).orElseThrow().missingFloors());
        patches.add(new BlockPatch(BlockPatch.REMOVE,b.getX(),b.getY()+2,b.getZ(),"minecraft:air"));
        patches.add(new BlockPatch(BlockPatch.REMOVE,b.getX(),b.getY(),b.getZ(),"minecraft:air"));
        assertEquals(List.of(2),FloorAccessAudit.inspect(mass,offset,patches,List.of(flight)).orElseThrow().missingFloors());
        assertEquals(List.of(1,2),FloorAccessAudit.inspect(mass,offset,patches,List.of()).orElseThrow().missingFloors());
    }
}
