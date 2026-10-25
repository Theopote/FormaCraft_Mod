package com.formacraft.server.compiler;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.generation.component.util.*;
import java.util.*;
/** Compatibility fit for untagged STRUCTURE flights with explicit stair endpoints. */
final class BareStairFitCompiler {
    static void apply(List<Component> parts) {
        for(int i=0;i<parts.size();i++) {
            var c=parts.get(i); if(c==null || !"STRUCTURE".equals(c.componentType()) || c.params()==null
                    || c.relativePosition()==null || Boolean.TRUE.equals(c.params().get("exterior"))
                    || c.features()!=null && c.features().stream().anyMatch(f->f!=null && f.startsWith("stair:"))
                    || !com.formacraft.server.generation.component.impl.StraightStairComponentGenerator.accepts(c)
                    || !(c.params().get("from") instanceof Map<?,?> from)
                    || !(c.params().get("to") instanceof Map<?,?> to)) continue;
            for(var body:parts) {
                if(body==null || !"MASS_MAIN".equals(body.componentType()) || body.params()==null
                        || body.dimensions()==null || c.params().get("host_id")==null
                        || !c.params().get("host_id").equals(body.params().get("component_id"))
                        || !Objects.equals(c.slotId(),body.slotId())) continue;
                var b=ComponentFootprintUtil.resolveMinCornerOrigin(body); if(b==null) continue;
                int wall=ComponentParamParsers.intParam(body.params(),1,"wall_thickness","wallThickness");
                int w=ComponentParamParsers.intParam(c.params(),3,"width");
                boolean alongX=n(from,"z")==n(to,"z") && n(from,"x")!=n(to,"x");
                boolean alongZ=n(from,"x")==n(to,"x") && n(from,"z")!=n(to,"z");
                if(w<1 || wall<1 || (!alongX && !alongZ)) continue;
                var r=c.relativePosition();
                int low=alongX?b.z()+wall:b.x()+wall;
                int high=alongX?b.z()+body.dimensions().depth()-wall-1:b.x()+body.dimensions().width()-wall-1;
                int center=alongX?r.z()+n(from,"z"):r.x()+n(from,"x");
                int lo=low+w/2,hi=high-(w-1)/2; if(lo>hi) continue;
                int delta=Math.max(lo,Math.min(hi,center))-center;
                int direction=Integer.signum(n(to,alongX?"x":"z")-n(from,alongX?"x":"z"));
                int end=(alongX?r.x():r.z())+n(to,alongX?"x":"z");
                int bound=alongX?(direction>0?b.x()+body.dimensions().width()-wall-1:b.x()+wall)
                        :(direction>0?b.z()+body.dimensions().depth()-wall-1:b.z()+wall);
                int available=(bound-end)*direction;
                int landing=ComponentParamParsers.intParam(c.params(),1,"landing_length");
                if(available<1) continue;
                var p=new HashMap<String,Object>(c.params());p.put("landing_length",Math.min(landing,available));
                p.put("compiler_fitted_stair",true);
                parts.set(i,new Component(c.componentType(),c.slotId(),new Vec3i(r.x()+(alongZ?delta:0),r.y(),r.z()+(alongX?delta:0)),
                        c.dimensions(),c.features(),p));
                break;
            }
        }
    }
    private static int n(Map<?,?> p,String k) {return p.get(k) instanceof Number n?n.intValue():0;}
}
