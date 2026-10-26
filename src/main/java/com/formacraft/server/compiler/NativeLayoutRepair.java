package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.*;
import com.formacraft.common.generation.component.util.*;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded repairs supported by explicit ownership, request dimensions, and actual envelopes. */
final class NativeLayoutRepair {
    static void apply(List<Component> parts,LlmPlan plan) {
        repairStiltEnvelope(parts,plan);
        trimCourtyardSeams(parts);
        repairCourtyardDatum(parts);
        trimAttachedMassSeam(parts);
        repairLocalRailing(parts);
    }
    private static void repairStiltEnvelope(List<Component> parts,LlmPlan plan) {
        Object intent=plan.proportionHints()==null?null:plan.proportionHints().get("architecture_intent");
        String text=intent instanceof Map<?,?> m?String.valueOf(m.get("original_text")):"";
        var size=Pattern.compile("(\\d+)\\s*[×xX*]\\s*(\\d+)格").matcher(text);
        if(!size.find()) return;
        int w=Integer.parseInt(size.group(1)),d=Integer.parseInt(size.group(2));
        if(w<5 || d<5 || w>64 || d>64)return;
        if(parts.stream().filter(c->"MASS_MAIN".equals(c.componentType())).count()!=1)return;
        var added=new ArrayList<Component>();
        for(int i=0;i<parts.size();i++) {
            var host=parts.get(i);if(!"MASS_MAIN".equals(host.componentType()) || host.params()==null)continue;
            Object id=host.params().get("component_id");
            var foundation=parts.stream().filter(c->"FOUNDATION".equals(c.componentType()) && c.params()!=null
                && Objects.equals(id,c.params().get("host_id")) && "stilts".equals(c.params().get("foundation_type"))).findFirst().orElse(null);
            if(foundation==null || host.dimensions().height()>=4 && host.dimensions().depth()>=5)continue;
            var old=ComponentFootprintUtil.resolveMinCornerOrigin(host);
            int x=old.x()+host.dimensions().width()/2-w/2,z=old.z()+host.dimensions().depth()/2-d/2;
            int bottom=foundation.relativePosition().y(),floor=Math.max(old.y(),bottom+3);
            var hp=new HashMap<String,Object>(host.params());hp.put("anchor_mode","min_corner");hp.put("floor_height",5);hp.put("floor_count",1);
            parts.set(i,new Component(host.componentType(),host.slotId(),new Vec3i(x,floor,z),new Dimensions(w,d,5),host.features(),hp));
            int fi=parts.indexOf(foundation);
            var fp=new HashMap<String,Object>(foundation.params());fp.put("anchor_mode","min_corner");fp.put("no_platform",true);
            parts.set(fi,new Component("FOUNDATION",foundation.slotId(),new Vec3i(x,bottom,z),new Dimensions(w,d,floor-bottom),foundation.features(),fp));
            boolean hasStair=parts.stream().anyMatch(c->c.params()!=null && Objects.equals(id,c.params().get("host_id"))
                && com.formacraft.server.generation.component.impl.StraightStairComponentGenerator.accepts(c));
            for(int j=0;j<parts.size();j++) {
                var c=parts.get(j);if(c.params()==null || !Objects.equals(id,c.params().get("host_id")))continue;
                var cp=new HashMap<String,Object>(c.params());
                if("ENTRANCE".equals(c.componentType())) {
                    cp.put("facing","EAST");cp.put("anchor_mode","min_corner");cp.put("door_width",2);cp.put("door_height",3);
                    parts.set(j,new Component("ENTRANCE",c.slotId(),new Vec3i(x,floor+1,z+d/2-1),new Dimensions(1,3,3),c.features(),cp));
                }
            }
            if(!hasStair) {
                var sp=new HashMap<String,Object>();sp.put("component_id",id+"#shore_stair");sp.put("host_id",id);
                sp.put("from",Map.of("x",-floor+bottom-1,"y",0,"z",0));sp.put("to",Map.of("x",0,"y",floor-bottom,"z",0));
                sp.put("width",2);sp.put("exterior",true);sp.put("support",false);sp.put("landing_length",1);
                sp.put("stairs","minecraft:oak_stairs");sp.put("floor","minecraft:oak_planks");sp.put("clearHeight",3);
                added.add(new Component("STRUCTURE",host.slotId(),new Vec3i(x,bottom,z+d/2),new Dimensions(floor-bottom+2,2,floor-bottom+1),List.of("stair:straight_single_run"),sp));
            }
        }
        parts.addAll(added);
    }
    private static void trimCourtyardSeams(List<Component> parts) {
        if(parts.stream().noneMatch(c->"COURTYARD_SPACE".equals(c.componentType())))return;
        for(int i=0;i<parts.size();i++) {
            var c=parts.get(i);if(!"MASS_MAIN".equals(c.componentType()) || c.params()==null)continue;
            var b=ComponentFootprintUtil.bounds(c);int minZ=b.minZ(),maxZ=b.maxZ();
            for(var other:parts) {
                if(other==c || !"MASS_MAIN".equals(other.componentType()) || !Objects.equals(c.slotId(),other.slotId()))continue;
                var a=ComponentFootprintUtil.bounds(other);
                // Only a one-cell seam of a shorter flank against a longer cross-wing.
                if(a.width()<=b.width() || a.minY()!=b.minY() || a.minX()>=b.maxX() || b.minX()>=a.maxX())continue;
                if(maxZ-a.minZ()==1 && a.maxZ()>maxZ)maxZ--;
                if(a.maxZ()-minZ==1 && a.minZ()<minZ)minZ++;
            }
            if(minZ==b.minZ() && maxZ==b.maxZ() || maxZ-minZ<3)continue;
            var p=new HashMap<String,Object>(c.params());p.put("anchor_mode","min_corner");
            parts.set(i,new Component(c.componentType(),c.slotId(),new Vec3i(b.minX(),b.minY(),minZ),
                new Dimensions(b.width(),maxZ-minZ,b.height()),c.features(),p));
        }
    }
    private static void repairCourtyardDatum(List<Component> parts) {
        for(int i=0;i<parts.size();i++) {
            var c=parts.get(i);if(!"COURTYARD_SPACE".equals(c.componentType()))continue;
            var bodies=parts.stream().filter(b->"MASS_MAIN".equals(b.componentType()) && Objects.equals(b.slotId(),c.slotId())).toList();
            if(bodies.size()<3)continue;
            var b=ComponentFootprintUtil.bounds(c);int floor=ComponentFootprintUtil.bounds(bodies.getFirst()).minY();
            if(b.minY()>=floor || floor-b.minY()>5 || bodies.stream().anyMatch(h->ComponentFootprintUtil.bounds(h).minY()!=floor))continue;
            boolean onSiteFoundation=parts.stream().anyMatch(f->"FOUNDATION".equals(f.componentType())
                && Objects.equals(f.slotId(),c.slotId()) && ComponentFootprintUtil.bounds(f).minY()==b.minY()
                && ComponentFootprintUtil.bounds(f).contains(b));
            if(!onSiteFoundation)continue;
            int x0=b.minX(),x1=b.maxX(),z0=b.minZ(),z1=b.maxZ();
            for(var body:bodies) {
                var h=ComponentFootprintUtil.bounds(body);
                if(h.minX()<x1 && h.maxX()>x0) {
                    if(z1-h.minZ()==1 && h.maxZ()>z1)z1--;
                    if(h.maxZ()-z0==1 && h.minZ()<z0)z0++;
                }
                if(h.minZ()<z1 && h.maxZ()>z0) {
                    if(x1-h.minX()==1 && h.maxX()>x1)x1--;
                    if(h.maxX()-x0==1 && h.minX()<x0)x0++;
                }
            }
            if(x1-x0<3 || z1-z0<3)continue;
            var p=new HashMap<String,Object>(c.params());p.put("anchor_mode","min_corner");
            parts.set(i,new Component(c.componentType(),c.slotId(),new Vec3i(x0,floor,z0),new Dimensions(x1-x0,z1-z0,b.height()),c.features(),p));
        }
    }
    private static void trimAttachedMassSeam(List<Component> parts) {
        for(int i=0;i<parts.size();i++) {
            var c=parts.get(i);if(!"MASS_SECONDARY".equals(c.componentType()) || c.params()==null
                || "plate".equals(c.params().get("extrude_mode")))continue;
            var host=parts.stream().filter(h->"MASS_MAIN".equals(h.componentType()) && h.params()!=null
                && Objects.equals(c.params().get("host_id"),h.params().get("component_id")) && Objects.equals(c.slotId(),h.slotId())).findFirst().orElse(null);
            if(host==null)continue;
            var a=ComponentFootprintUtil.bounds(host);var b=ComponentFootprintUtil.bounds(c);
            if(a.maxX()-b.minX()!=1 || a.minX()>=b.minX() || a.minZ()>=b.maxZ() || a.maxZ()<=b.minZ() || b.width()<6)continue;
            var p=new HashMap<String,Object>(c.params());p.put("anchor_mode","min_corner");
            parts.set(i,new Component(c.componentType(),c.slotId(),new Vec3i(b.minX()+1,b.minY(),b.minZ()),new Dimensions(b.width()-1,b.depth(),b.height()),c.features(),p));
        }
    }
    private static void repairLocalRailing(List<Component> parts) {
        for(int i=0;i<parts.size();i++) {
            var rail=parts.get(i);if(!"DECOR_DETAIL".equals(rail.componentType()) || rail.params()==null
                || !Boolean.TRUE.equals(rail.params().get("railing")))continue;
            var host=parts.stream().filter(c->MassComponentKinds.isCirculationMass(c) && c.params()!=null
                && Objects.equals(rail.params().get("host_id"),c.params().get("component_id"))).findFirst().orElse(null);
            if(host==null || !Objects.equals(host.slotId(),rail.slotId()))continue;
            var b=ComponentFootprintUtil.bounds(host);var rp=rail.relativePosition();
            if(rp.x()>=b.minX() && rp.x()+rail.dimensions().width()<=b.maxX()
                && rp.z()>=b.minZ() && rp.z()+rail.dimensions().depth()<=b.maxZ())continue;
            var stair=parts.stream().filter(c->c.params()!=null && Objects.equals(rail.params().get("host_id"),c.params().get("host_id"))
                && !Boolean.TRUE.equals(c.params().get("exterior")) && c.params().get("to") instanceof Map<?,?>
                && com.formacraft.server.generation.component.impl.StraightStairComponentGenerator.accepts(c)).findFirst().orElse(null);
            if(stair==null)continue;
            var f=(Map<?,?>)stair.params().get("from");var t=(Map<?,?>)stair.params().get("to");
            int fx=((Number)f.get("x")).intValue(),fz=((Number)f.get("z")).intValue(),tx=((Number)t.get("x")).intValue(),tz=((Number)t.get("z")).intValue();
            int sw=ComponentParamParsers.intParam(stair.params(),3,"width");boolean alongZ=fx==tx;
            int x=stair.relativePosition().x()+(alongZ?tx-sw/2-1:Math.min(fx,tx));
            int z=stair.relativePosition().z()+(alongZ?Math.min(fz,tz):tz-sw/2-1);
            int y=stair.relativePosition().y()+((Number)t.get("y")).intValue()+1;
            int run=alongZ?Math.abs(tz-fz)+1:Math.abs(tx-fx)+1;
            var p=new HashMap<String,Object>(rail.params());p.put("anchor_mode","min_corner");
            parts.set(i,new Component("DECOR_DETAIL",rail.slotId(),new Vec3i(x,y,z),new Dimensions(alongZ?1:run,alongZ?run:1,1),rail.features(),p));
            var q=new HashMap<String,Object>(p);q.put("component_id",p.get("component_id")+"#opposite");
            parts.add(new Component("DECOR_DETAIL",rail.slotId(),new Vec3i(x+(alongZ?sw+1:0),y,z+(alongZ?0:sw+1)),new Dimensions(alongZ?1:run,alongZ?run:1,1),rail.features(),q));
        }
    }
}
