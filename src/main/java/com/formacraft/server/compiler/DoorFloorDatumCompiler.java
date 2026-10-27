package com.formacraft.server.compiler;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.generation.component.util.*;
import java.util.*;
/** Door openings stand above the host floor block; never carve away its threshold. */
final class DoorFloorDatumCompiler {
    static void apply(List<Component> parts) {
        for(int i=0;i<parts.size();i++) {
            var door=parts.get(i);if(!"ENTRANCE".equals(door.componentType()) || door.params()==null || door.relativePosition()==null)continue;
            var host=parts.stream().filter(c->MassComponentKinds.isCirculationMass(c) && c.params()!=null
                && Objects.equals(c.params().get("component_id"),door.params().get("host_id"))
                && Objects.equals(c.slotId(),door.slotId())).findFirst().orElse(null);
            if(host==null)continue;
            var b=ComponentFootprintUtil.bounds(host);var r=door.relativePosition();
            if(r.y()>b.minY())continue;
            parts.set(i,new Component(door.componentType(),door.slotId(),new Vec3i(r.x(),b.minY()+1,r.z()),door.dimensions(),door.features(),door.params()));
        }
    }
}
