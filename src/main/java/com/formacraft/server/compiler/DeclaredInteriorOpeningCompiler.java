package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.*;
import com.formacraft.common.generation.component.util.*;
import com.formacraft.common.patch.*;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/** Lower declared internal openings only where two rectangular shells actually touch. */
final class DeclaredInteriorOpeningCompiler {
    static void apply(List<Component> parts, Map<String,Slot> slots) {
        var added = new ArrayList<Component>();
        var pairs = new HashSet<String>();
        for (var host : List.copyOf(parts)) {
            if (!MassComponentKinds.isCirculationMass(host) || host.params()==null
                    || !(host.params().get("internal_openings") instanceof List<?> declarations)) continue;
            var h=ComponentFootprintUtil.bounds(host); var ha=CourtyardAnchorCompiler.anchor(slots,host.slotId());
            for (var raw : declarations) {
                if (!(raw instanceof Map<?,?> spec)) continue;
                String side=String.valueOf(spec.get("side")).toUpperCase(Locale.ROOT);
                boolean axis=Set.of("EAST","WEST").contains(side);
                if (!Set.of("EAST","WEST","NORTH","SOUTH").contains(side)) continue;
                var candidates=new ArrayList<Component>();
                for(var target:parts) {
                    if(target==host || !MassComponentKinds.isCirculationMass(target)) continue;
                    var t=ComponentFootprintUtil.bounds(target); var ta=CourtyardAnchorCompiler.anchor(slots,target.slotId());
                    int gap=switch(side) {
                        case "WEST" -> h.minX()+ha.x()-t.maxX()-ta.x();
                        case "EAST" -> t.minX()+ta.x()-h.maxX()-ha.x();
                        case "NORTH" -> h.minZ()+ha.z()-t.maxZ()-ta.z();
                        default -> t.minZ()+ta.z()-h.maxZ()-ha.z(); };
                    int span=axis?Math.min(h.maxZ()+ha.z(),t.maxZ()+ta.z())-Math.max(h.minZ()+ha.z(),t.minZ()+ta.z())
                            :Math.min(h.maxX()+ha.x(),t.maxX()+ta.x())-Math.max(h.minX()+ha.x(),t.minX()+ta.x());
                    if(gap==0 && span>=5 && h.minY()+ha.y()==t.minY()+ta.y()) candidates.add(target);
                }
                if(candidates.size()!=1) {
                    fail("声明的内部开口没有唯一相接建筑："+id(host)+"/"+side); return;
                }
                var target=candidates.getFirst(); String key=id(host).compareTo(id(target))<0?id(host)+":"+id(target):id(target)+":"+id(host);
                if(!pairs.add(key)) continue;
                var t=ComponentFootprintUtil.bounds(target);var ta=CourtyardAnchorCompiler.anchor(slots,target.slotId());
                int lo=axis?Math.max(h.minZ()+ha.z(),t.minZ()+ta.z()):Math.max(h.minX()+ha.x(),t.minX()+ta.x());
                int hi=axis?Math.min(h.maxZ()+ha.z(),t.maxZ()+ta.z()):Math.min(h.maxX()+ha.x(),t.maxX()+ta.x());
                int width=spec.get("width") instanceof Number n?n.intValue():2;
                if(width<1 || width>hi-lo-2) {fail("内部开口宽度超出两建筑的相接墙面："+key);return;}
                int height=spec.get("height") instanceof Number n?n.intValue():3;
                int floor=Math.min(ComponentParamParsers.intParam(host.params(),4,"floor_height"),ComponentParamParsers.intParam(target.params(),4,"floor_height"));
                height=Math.min(height,floor-1);
                if(height<2) {fail("内部开口净高不足："+key);return;}
                int start=lo+(hi-lo-width)/2;
                add(added,host,target,side,start,width,height,h,ha,key);
                String opposite=switch(side){case "WEST"->"EAST";case "EAST"->"WEST";case "NORTH"->"SOUTH";default->"NORTH";};
                add(added,target,host,opposite,start,width,height,t,ta,key);
            }
        }
        parts.addAll(added);
    }
    private static void add(List<Component> out,Component host,Component target,String side,int start,int width,int height,
                            ComponentFootprintUtil.Bounds b,Vec3i a,String key) {
        boolean axis=Set.of("EAST","WEST").contains(side);
        int x=axis?(side.equals("EAST")?b.maxX()-1:b.minX()):start-a.x()-1;
        int z=axis?start-a.z()-1:(side.equals("SOUTH")?b.maxZ()-1:b.minZ());
        var p=new HashMap<String,Object>();
        p.put("component_id","internal_portal:"+key+":"+side);p.put("host_id",id(host));p.put("building_id",id(host));
        p.put("target_host_id",id(target));p.put("anchor_mode","min_corner");p.put("facing",side);
        p.put("door_width",width);p.put("door_height",height);p.put("door_block","minecraft:air");
        p.put("entrance_type","doorway");p.put("clear_passage",true);p.put("compiler_internal_portal",true);
        out.add(new Component("ENTRANCE",host.slotId(),new Vec3i(x,b.minY()+1,z),
                new Dimensions(axis?1:width+2,axis?width+2:1,height),List.of(),p));
    }
    static boolean validate(List<Component> parts,List<BlockPatch> patches,Map<String,Slot> slots) {
        var cells=new HashMap<BlockPos,net.minecraft.block.BlockState>();
        for(var p:patches) cells.put(new BlockPos(p.dx(),p.dy(),p.dz()),BlockPatchTargetResolver.resolve(p));
        for(var c:parts) {
            if(c.params()==null || !Boolean.TRUE.equals(c.params().get("compiler_internal_portal"))) continue;
            var a=CourtyardAnchorCompiler.anchor(slots,c.slotId());var r=c.relativePosition();
            boolean axis=Set.of("EAST","WEST").contains(String.valueOf(c.params().get("facing")));
            int width=((Number)c.params().get("door_width")).intValue();
            for(int u=1;u<=width;u++) for(int y=0;y<2;y++) {
                var pos=new BlockPos(a.x()+r.x()+(axis?0:u),a.y()+r.y()+y,a.z()+r.z()+(axis?u:0));
                var state=cells.get(pos);
                if(state!=null && !state.isAir()) {fail("内部通道被后续构件封堵："+id(c)+" "+pos);return false;}
                var floor=cells.get(pos.down(y+1));
                if(floor==null || floor.isAir()) {fail("内部通道缺少连续地板："+id(c));return false;}
            }
        }
        return true;
    }
    private static String id(Component c) {return ComponentPlanTypes.paramIdentity(c);}
    private static void fail(String message) {
        AssemblyCompileDiagnostics.set(new CapabilityGap("E_INTERNAL_PASSAGE",message,"components[].params.internal_openings",
                List.of("将两建筑的底层贴合，并为内部通道保留地板及至少两格净高。")));
    }
}