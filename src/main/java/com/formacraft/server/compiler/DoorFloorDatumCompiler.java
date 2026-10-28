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
            // Keep openings above the floor plate so wooden thresholds survive. Ground-level modern
            // glass/concrete rectangular voids are authored from Y=minY and must stay put.
            if(r.y()>b.minY())continue;
            if(r.y()==b.minY() && b.minY()<=0 && isModernFlushOpening(door))continue;
            parts.set(i,new Component(door.componentType(),door.slotId(),new Vec3i(r.x(),b.minY()+1,r.z()),door.dimensions(),door.features(),door.params()));
        }
    }

    private static boolean isModernFlushOpening(Component door) {
        String doorBlock=String.valueOf(door.params().getOrDefault("door_block",""));
        String frame=String.valueOf(door.params().getOrDefault("frame_block",""));
        String material=String.valueOf(door.params().getOrDefault("material",""));
        String entranceType=String.valueOf(door.params().getOrDefault("entrance_type",""));
        return doorBlock.contains("glass")
                || frame.contains("concrete") || frame.contains("quartz")
                || material.contains("concrete") || material.contains("quartz") || material.contains("glass")
                || "doorway".equalsIgnoreCase(entranceType)
                || "passable_portal".equalsIgnoreCase(entranceType)
                || "double_door".equalsIgnoreCase(entranceType);
    }
}
