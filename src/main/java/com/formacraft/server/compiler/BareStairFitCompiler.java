package com.formacraft.server.compiler;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.generation.component.util.*;
import java.util.*;
/** Compatibility fit for untagged STRUCTURE flights with explicit stair endpoints. */
final class BareStairFitCompiler {
    static void apply(List<Component> parts) {
        var extras=new ArrayList<Component>();
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
                if(n(to,"y")<=n(from,"y")) continue;
                Component deck=null;
                for(var candidate:parts) if(candidate!=null && candidate.params()!=null && candidate.relativePosition()!=null
                        && "platform".equals(candidate.params().get("plate_role"))
                        && Objects.equals(candidate.params().get("host_id"),body.params().get("component_id"))
                        && Objects.equals(candidate.slotId(),c.slotId())) {deck=candidate;break;}
                if(deck!=null && c.relativePosition().y()+n(to,"y")!=deck.relativePosition().y()
                        && splitToPlatform(parts,i,extras,c,body,deck,b,w,wall)) break;
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
        parts.addAll(extras);
    }
    private static boolean splitToPlatform(List<Component> parts,int index,List<Component> extras,Component c,
                                           Component body,Component deck,Vec3i base,int width,int wall) {
        int rise=deck.relativePosition().y()-base.y(),mid=rise/2;
        int xa=body.dimensions().width()/2-(width+1)/2,xb=xa+width+1;
        int z0=wall+1,zTurn=z0+mid+1,zEnd=zTurn-(rise-mid);
        if(width<1 || rise<4 || xa-width/2<wall || xb+(width-1)/2>=body.dimensions().width()-wall
                || zEnd<wall || zTurn>=body.dimensions().depth()-wall) return false;
        parts.set(index,flight(c,base,body.dimensions(),"",xa,0,z0,xa,mid,zTurn-1));
        extras.add(flight(c,base,body.dimensions(),"_upper",xb,mid,zTurn,xb,rise,zEnd));
        var p=new HashMap<String,Object>(c.params());
        p.put("component_id",String.valueOf(p.get("component_id"))+"_turn");
        p.put("extrude_mode","plate");p.put("anchor_mode","min_corner");p.put("plate_role","interior");
        p.put("floor_block",p.getOrDefault("floor","minecraft:stone_bricks"));
        p.remove("from");p.remove("to");
        int left=xa-width/2,right=xb+(width-1)/2;
        extras.add(new Component("MASS_SECONDARY",c.slotId(),new Vec3i(base.x()+left,base.y()+mid,base.z()+zTurn),
                new Dimensions(right-left+1,1,1),List.of(),p));
        return true;
    }
    private static Component flight(Component source,Vec3i base,Dimensions dims,String suffix,
                                    int x,int y,int z,int tx,int ty,int tz) {
        var p=new HashMap<String,Object>(source.params());
        p.put("component_id",String.valueOf(p.get("component_id"))+suffix);
        p.put("from",Map.of("x",x,"y",y,"z",z));p.put("to",Map.of("x",tx,"y",ty,"z",tz));
        p.put("landing_length",1);p.put("anchor_mode","min_corner");p.put("compiler_fitted_stair",true);
        return new Component("STAIR_SYSTEM",source.slotId(),base,dims,List.of(),p);
    }
    private static int n(Map<?,?> p,String k) {return p.get(k) instanceof Number n?n.intValue():0;}
}
