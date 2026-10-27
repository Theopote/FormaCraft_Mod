package com.formacraft.server.compiler;
import com.formacraft.common.llm.dto.*;
import java.util.*;
/** Expand explicit stair-opening guards along the sides, keeping the upper arrival open. */
final class StairOpeningGuardCompiler {
    static void apply(List<Component> parts) {
        var extras=new ArrayList<Component>();
        var handled=new HashSet<Object>();
        for(var it=parts.listIterator();it.hasNext();) {
            var rail=it.next();
            if(rail==null || rail.params()==null || rail.relativePosition()==null
                    || rail.params().get("host_id")==null
                    || !(Boolean.TRUE.equals(rail.params().get("railing")) || "railing".equals(rail.params().get("role")))
                    || !("stair_opening".equals(rail.params().get("placement"))
                         || rail.features()!=null && rail.features().contains("stair_opening_guard"))) continue;
            for(var stair:parts) {
                if(stair==null || stair.params()==null || stair.relativePosition()==null
                        || !Objects.equals(stair.slotId(),rail.slotId())
                        || !Objects.equals(stair.params().get("host_id"),rail.params().get("host_id"))
                        || Boolean.TRUE.equals(stair.params().get("exterior"))
                        || !com.formacraft.server.generation.component.impl.StraightStairComponentGenerator.accepts(stair)
                        || !(stair.params().get("from") instanceof Map<?,?> a)
                        || !(stair.params().get("to") instanceof Map<?,?> b)) continue;
                int ax=n(a,"x"),az=n(a,"z"),bx=n(b,"x"),bz=n(b,"z");
                if(ax!=bx || az==bz) continue; // Z-axis straight flights; other forms retain their authored guard.
                int width=stair.params().get("width") instanceof Number v?v.intValue():3;
                var r=stair.relativePosition();
                int left=r.x()+ax-width/2-1,right=left+width+1;
                int z0=r.z()+Math.min(az,bz),z1=r.z()+Math.max(az,bz);
                if(!handled.add(rail.params().get("host_id"))) {it.remove();break;}
                int y=Math.max(rail.relativePosition().y(),r.y()+Math.max(n(a,"y"),n(b,"y"))+1);
                extras.add(line(rail,"left",left,y,z0,1,z1-z0+1));
                extras.add(line(rail,"right",right,y,z0,1,z1-z0+1));
                int lowZ=n(a,"y")<n(b,"y")?az:bz;
                int highZ=lowZ==az?bz:az;
                int cap=r.z()+lowZ+Integer.signum(lowZ-highZ);
                extras.add(line(rail,"end",left+1,y,cap,width,1));
                it.remove(); break;
            }
        }
        parts.addAll(extras);
    }
    private static Component line(Component source,String suffix,int x,int y,int z,int w,int d) {
        var p=new HashMap<String,Object>(source.params());
        p.put("placement","compiled_stair_guard");p.put("anchor_mode","min_corner");
        p.put("component_id",String.valueOf(p.get("component_id"))+"_"+suffix);
        return new Component("DECOR_DETAIL",source.slotId(),new Vec3i(x,y,z),new Dimensions(w,d,1),List.of(),p);
    }
    private static int n(Map<?,?> p,String k) {return p.get(k) instanceof Number n?n.intValue():0;}
}
