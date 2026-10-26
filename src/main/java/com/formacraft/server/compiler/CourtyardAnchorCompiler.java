package com.formacraft.server.compiler;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.generation.component.util.*;
import java.util.*;
/** Resolve an omitted courtyard anchor only when a uniquely enclosed central void proves it. */
final class CourtyardAnchorCompiler {
    static void apply(List<Component> parts, Map<String,Slot> slots) {
        for(int i=0;i<parts.size();i++) {
            var court=parts.get(i);
            if(!Set.of("COURTYARD_SPACE","COURTYARD").contains(court.componentType()) || court.params()==null
                    || court.params().containsKey("anchor_mode") || court.dimensions()==null || court.relativePosition()==null) continue;
            var a=anchor(slots,court.slotId());var p=court.relativePosition();var d=court.dimensions();
            int x=a.x()+p.x()-d.width()/2,z=a.z()+p.z()-d.depth()/2;
            int sides=0;boolean overlap=false,legacyOverlap=false;
            for(var body:parts) if(MassComponentKinds.isCirculationMass(body)) {
                var b=ComponentFootprintUtil.bounds(body);var ba=anchor(slots,body.slotId());if(b==null)continue;
                int x0=b.minX()+ba.x(),x1=b.maxX()+ba.x(),z0=b.minZ()+ba.z(),z1=b.maxZ()+ba.z();
                overlap|=x<x1 && x+d.width()>x0 && z<z1 && z+d.depth()>z0;
                legacyOverlap|=a.x()+p.x()<x1 && a.x()+p.x()+d.width()>x0 && a.z()+p.z()<z1 && a.z()+p.z()+d.depth()>z0;
                if((x1<=x || x0>=x+d.width()) && z<z1 && z+d.depth()>z0) sides++;
                else if((z1<=z || z0>=z+d.depth()) && x<x1 && x+d.width()>x0) sides++;
            }
            if(!overlap && legacyOverlap && sides>=3) {
                var params=new HashMap<String,Object>(court.params());params.put("anchor_mode","center");
                parts.set(i,new Component(court.componentType(),court.slotId(),p,d,court.features(),params));
            }
        }
    }
    static Vec3i anchor(Map<String,Slot> slots,String id) {
        var slot=slots.get(id);return slot==null || slot.anchor()==null?new Vec3i(0,0,0):slot.anchor();
    }
}
