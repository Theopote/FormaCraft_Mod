package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import java.util.*;

/** Correct reversed straight exterior flights only when the low end adjoins their hosted door. */
final class ExteriorStairEndpointRepair {
    static void apply(List<Component> components) {
        for(int i=0;i<components.size();i++) {
            var c=components.get(i); if(c==null || c.params()==null || c.relativePosition()==null) continue;
            if(!com.formacraft.server.generation.component.impl.StraightStairComponentGenerator.accepts(c)) continue;
            var p=c.params();
            if(!Boolean.TRUE.equals(p.get("exterior")) || p.get("host_id")==null
                    || !(p.get("from") instanceof Map<?,?> a) || !(p.get("to") instanceof Map<?,?> b)) continue;
            if(!point(a) || !point(b)) continue;
            int ax=n(a,"x"),az=n(a,"z"),bx=n(b,"x"),bz=n(b,"z");
            if(n(b,"y")<=n(a,"y") || (ax!=bx && az!=bz) || (ax==bx && az==bz)) continue;
            for(var door:components) {
                if(door==null || !"ENTRANCE".equals(door.componentType()) || door.params()==null
                        || !Objects.equals(door.slotId(),c.slotId()) || door.relativePosition()==null
                        || !p.get("host_id").equals(door.params().get("host_id"))) continue;
                var r=c.relativePosition(); var d=door.relativePosition();
                int low=Math.abs(r.x()+ax-d.x())+Math.abs(r.z()+az-d.z());
                int high=Math.abs(r.x()+bx-d.x())+Math.abs(r.z()+bz-d.z());
                if(low>2 || high<=low+1 || Math.abs(r.y()+n(b,"y")-d.y())>1) continue;
                var from=new HashMap<String,Object>(); a.forEach((k,v)->from.put(k.toString(),v));
                var to=new HashMap<String,Object>(); b.forEach((k,v)->to.put(k.toString(),v));
                from.put("x",bx); from.put("z",bz); to.put("x",ax); to.put("z",az);
                var fixed=new HashMap<String,Object>(p); fixed.put("from",from);fixed.put("to",to);
                components.set(i,new Component(c.componentType(),c.slotId(),r,c.dimensions(),c.features(),fixed));
                break;
            }
        }
    }
    private static int n(Map<?,?> p,String key) { return p.get(key) instanceof Number n?n.intValue():0; }
    private static boolean point(Map<?,?> p) {
        return p.get("x") instanceof Number && p.get("y") instanceof Number && p.get("z") instanceof Number;
    }
}
