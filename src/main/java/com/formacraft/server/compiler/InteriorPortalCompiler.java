package com.formacraft.server.compiler;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.generation.component.util.*;
import java.util.*;
/** A declared through-passage into an adjacent building must open the second shell too. */
final class InteriorPortalCompiler {
    static void apply(List<Component> parts,Map<String,Slot> slots) {
        var added=new ArrayList<Component>();
        for(int i=0;i<parts.size();i++) {
            var door=parts.get(i);
            if(!"ENTRANCE".equals(door.componentType()) || door.params()==null || !Boolean.TRUE.equals(door.params().get("clear_passage")))continue;
            var host=parts.stream().filter(c->MassComponentKinds.isCirculationMass(c) && c.params()!=null
                    && Objects.equals(door.params().get("host_id"),c.params().get("component_id"))).findFirst().orElse(null);
            if(host==null)continue;
            var hb=ComponentFootprintUtil.bounds(host);var ha=CourtyardAnchorCompiler.anchor(slots,host.slotId());
            String facing=String.valueOf(door.params().get("facing")).toUpperCase(Locale.ROOT);
            Component other=null;String opposite=null;
            for(var target:parts) {
                if(target==host || !MassComponentKinds.isCirculationMass(target) || target.params()==null)continue;
                var tb=ComponentFootprintUtil.bounds(target);var ta=CourtyardAnchorCompiler.anchor(slots,target.slotId());
                int gap=switch(facing) {
                    case "WEST" -> hb.minX()+ha.x()-tb.maxX()-ta.x();
                    case "EAST" -> tb.minX()+ta.x()-hb.maxX()-ha.x();
                    case "NORTH" -> hb.minZ()+ha.z()-tb.maxZ()-ta.z();
                    case "SOUTH" -> tb.minZ()+ta.z()-hb.maxZ()-ha.z();default -> -1;};
                boolean xAxis=Set.of("EAST","WEST").contains(facing);
                int span=xAxis?Math.min(hb.maxZ()+ha.z(),tb.maxZ()+ta.z())-Math.max(hb.minZ()+ha.z(),tb.minZ()+ta.z())
                        :Math.min(hb.maxX()+ha.x(),tb.maxX()+ta.x())-Math.max(hb.minX()+ha.x(),tb.minX()+ta.x());
                if(gap<0 || gap>1 || span<4 || hb.minY()+ha.y()!=tb.minY()+ta.y())continue;
                if(other!=null) {other=null;break;}other=target;
                opposite=switch(facing){case "WEST"->"EAST";case "EAST"->"WEST";case "NORTH"->"SOUTH";default->"NORTH";};
            }
            if(other==null)continue;
            var b=ComponentFootprintUtil.bounds(other);var a=CourtyardAnchorCompiler.anchor(slots,other.slotId());
            boolean xAxis=Set.of("EAST","WEST").contains(facing);
            int center=xAxis?(Math.max(hb.minZ()+ha.z(),b.minZ()+a.z())+Math.min(hb.maxZ()+ha.z(),b.maxZ()+a.z()))/2
                    :(Math.max(hb.minX()+ha.x(),b.minX()+a.x())+Math.min(hb.maxX()+ha.x(),b.maxX()+a.x()))/2;
            int x=xAxis?("EAST".equals(opposite)?b.maxX()-1:b.minX()):center-a.x()-1;
            int z=xAxis?center-a.z()-1:("SOUTH".equals(opposite)?b.maxZ()-1:b.minZ());
            var params=new HashMap<String,Object>(door.params());
            params.put("entrance_world_facing",true);
            params.put("target_host_id",other.params().get("component_id"));params.put("door_block","minecraft:air");
            params.put("entrance_type","archway");params.put("door_width",2);params.put("door_height",3);
            var dp=door.relativePosition();
            // Keep the floor blocks under the passage; the opening starts one cell above them.
            var doorway=new Vec3i(dp.x(),Math.max(dp.y(),hb.minY()+1),dp.z());
            parts.set(i,new Component(door.componentType(),door.slotId(),doorway,door.dimensions(),List.of(),params));
            var p=new HashMap<String,Object>(params);p.put("host_id",other.params().get("component_id"));
            p.put("building_id",other.params().get("component_id"));
            p.put("target_host_id",host.params().get("component_id"));p.put("component_id",params.get("component_id")+"#other_shell");
            p.put("facing",opposite);p.put("anchor_mode","min_corner");
            added.add(new Component("ENTRANCE",other.slotId(),new Vec3i(x,b.minY()+1,z),new Dimensions(xAxis?1:2,xAxis?2:1,3),List.of(),p));
        }
        parts.addAll(added);
    }
}
