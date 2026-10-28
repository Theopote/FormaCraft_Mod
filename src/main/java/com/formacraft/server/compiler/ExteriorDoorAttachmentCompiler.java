package com.formacraft.server.compiler;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.generation.component.util.*;
import java.util.*;
/** Bind a sole exterior approach's terminal edge to its sole authored host door. */
final class ExteriorDoorAttachmentCompiler {
    static void apply(List<Component> parts) {
        for(var stair:parts) {
            if(stair.params()==null || !Boolean.TRUE.equals(stair.params().get("exterior"))
                || !(stair.params().get("to") instanceof Map<?,?> to))continue;
            var host=parts.stream().filter(c->MassComponentKinds.isCirculationMass(c) && c.params()!=null
                && Objects.equals(c.params().get("component_id"),stair.params().get("host_id")) && Objects.equals(c.slotId(),stair.slotId())).findFirst().orElse(null);
            if(host==null)continue;
            // Ground-level authored doorways (modern rectangular openings at landing Y) must stay put.
            // Only elevated hosts (stilts / raised floors) need the sill lifted to stair terminal + 1.
            var bounds=ComponentFootprintUtil.bounds(host);
            if(bounds==null || bounds.minY()<=0)continue;
            var doors=parts.stream().filter(c->"ENTRANCE".equals(c.componentType()) && c.params()!=null
                && Objects.equals(c.params().get("host_id"),stair.params().get("host_id")) && Objects.equals(c.slotId(),stair.slotId())).toList();
            if(doors.size()!=1)continue;
            var door=doors.getFirst();var r=stair.relativePosition();
            int x=r.x()+n(to,"x"),y=r.y()+n(to,"y"),z=r.z()+n(to,"z");
            if(y!=bounds.minY() || Math.abs(door.relativePosition().y()-y)>1)continue;
            String face=null;
            if(x==bounds.minX())face="EAST";else if(x==bounds.maxX()-1)face="WEST";
            else if(z==bounds.minZ())face="SOUTH";else if(z==bounds.maxZ()-1)face="NORTH";
            if(face==null)continue;
            int width=ComponentParamParsers.intParam(stair.params(),3,"width");
            boolean side=Set.of("EAST","WEST").contains(face);
            var p=new HashMap<String,Object>(door.params());p.put("facing",face);p.put("anchor_mode","min_corner");
            p.put("door_width",width);p.put("door_height",2);p.put("stair_connection",stair.params().get("component_id"));p.put("canopy_depth",0);
            // width+2 frame has its opening at offset one for both even and odd widths.
            var position=new Vec3i(side?x:x-width/2-1,y+1,side?z-width/2-1:z);
            parts.set(parts.indexOf(door),new Component("ENTRANCE",door.slotId(),position,
                new Dimensions(side?1:width+2,side?width+2:1,3),List.of(),p));
        }
    }
    private static int n(Map<?,?> p,String k) {return ((Number)p.get(k)).intValue();}
}
