package com.formacraft.server.compiler;
import com.formacraft.common.llm.dto.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ExteriorStairEndpointRepairTest {
    @Test void reversedLoggedFlightRisesTowardDoorAndIsIdempotent() {
        var stair=new Component("STRUCTURE","s",new Vec3i(-1,0,4),new Dimensions(2,4,4),List.of("stair:straight_single_run"),
                Map.of("host_id","house","exterior",true,"from",Map.of("x",0,"y",0,"z",0),
                        "to",Map.of("x",0,"y",4,"z",4)));
        var door=new Component("ENTRANCE","s",new Vec3i(-1,4,3),new Dimensions(2,1,3),List.of(),Map.of("host_id","house"));
        var list=new ArrayList<>(List.of(stair,door));
        ExteriorStairEndpointRepair.apply(list);
        assertEquals(0,((Map<?,?>)list.get(0).params().get("to")).get("z"));
        assertEquals(4,((Map<?,?>)list.get(0).params().get("from")).get("z"));
        var fixed=list.get(0); ExteriorStairEndpointRepair.apply(list); assertEquals(fixed,list.get(0));
    }
}
