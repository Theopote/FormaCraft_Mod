package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.*;
import com.formacraft.common.generation.component.util.*;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import java.util.*;

/** Fits repeated, disconnected native flights into alternating lanes with real turn landings. */
final class StairChainCompiler {
    static void apply(List<Component> parts, LlmPlan plan) {
        var landings=new ArrayList<Component>();
        for(Component host:List.copyOf(parts)) {
            if(host==null || !"MASS_MAIN".equals(host.componentType()) || host.params()==null || host.dimensions()==null) continue;
            Object id=host.params().get("component_id"); if(id==null) continue;
            var indices=new ArrayList<Integer>();
            for(int i=0;i<parts.size();i++) {
                var c=parts.get(i);
                if(c!=null && c.params()!=null && Objects.equals(c.slotId(),host.slotId())
                        && com.formacraft.common.generation.routing.GenerationAuthority.resolve(c,plan)
                            == com.formacraft.common.generation.routing.GenerationAuthority.COMPONENTS
                        && id.equals(c.params().get("host_id")) && !Boolean.TRUE.equals(c.params().get("exterior"))
                        && com.formacraft.server.generation.component.impl.StraightStairComponentGenerator.accepts(c)
                        && c.params().get("from") instanceof Map<?,?> && c.params().get("to") instanceof Map<?,?>) indices.add(i);
            }
            indices.sort(Comparator.comparingInt(i->point(parts.get(i),"from").y()));
            if(indices.size()<2) continue;
            Component first=parts.get(indices.getFirst()); var a=point(first,"from");var b=point(first,"to");
            boolean alongZ=a.x()==b.x() && a.z()!=b.z(), alongX=a.z()==b.z() && a.x()!=b.x();
            if(!alongZ && !alongX) continue;
            // Only the replicated-flight defect is rewritten. Already authored connected turns stay intact.
            boolean repeated=true;
            for(int j=0;j<indices.size();j++) {
                Component c=parts.get(indices.get(j));var f=point(c,"from");var t=point(c,"to");
                repeated &= c.relativePosition().equals(first.relativePosition()) && f.x()==a.x() && f.z()==a.z()
                        && t.x()==b.x() && t.z()==b.z() && t.y()>f.y()
                        && (j==0 || f.y()==point(parts.get(indices.get(j-1)),"to").y());
            }
            if(!repeated) continue;
            int wall=ComponentParamParsers.intParam(host.params(),1,"wall_thickness","wallThickness");
            int width=ComponentParamParsers.intParam(first.params(),3,"width");
            int span=alongZ?host.dimensions().width():host.dimensions().depth();
            int run=alongZ?host.dimensions().depth():host.dimensions().width();
            int authoredRun=Math.abs(alongZ?b.z()-a.z():b.x()-a.x());
            int interiorRun=run-2*wall;
            // Short local flights in a large mass are intentional. Only the full-shaft
            // replicated defect (nearly wall-to-wall run) is rewritten into switchbacks.
            if(authoredRun<Math.max(2,interiorRun-1)) continue;
            int laneA=wall+width/2,laneB=span-wall-1-(width-1)/2;
            if(wall<1 || width<1 || laneB-laneA<width || run-2*wall<2) {
                AssemblyCompileDiagnostics.set(new CapabilityGap("E_STAIR_CHAIN_UNCONNECTED",
                        "楼梯井无法容纳往返梯段和转向平台："+id,"components[]",List.of("Enlarge the stairwell or reduce stair width.")));
                return;
            }
            var origin=ComponentFootprintUtil.resolveMinCornerOrigin(host);
            if(origin==null) continue;
            int direction=Integer.signum(alongZ?b.z()-a.z():b.x()-a.x());
            int low=wall,high=run-wall-1;
            for(int j=0;j<indices.size();j++) {
                int index=indices.get(j);Component source=parts.get(index);
                var from=point(source,"from");var to=point(source,"to");
                int start=(j%2==0?direction:-direction)>0?low:high, end=start==low?high:low;
                int lane=j%2==0?laneA:laneB;
                if(to.y()-from.y()>Math.abs(end-start)) {
                    AssemblyCompileDiagnostics.set(new CapabilityGap("E_STAIR_CHAIN_UNCONNECTED",
                            "梯段坡度无法落入楼梯井："+id,"components[]",List.of("Add more flights to reduce rise per run.")));return;
                }
                int fy=source.relativePosition().y()+from.y()-origin.y(),ty=source.relativePosition().y()+to.y()-origin.y();
                var p=new HashMap<String,Object>(source.params());
                p.put("from",coords(alongZ,lane,fy,start));p.put("to",coords(alongZ,lane,ty,end));
                p.put("landing_length",1);p.put("anchor_mode","min_corner");p.put("support",false);p.put("compiler_connected_stair_chain",true);
                parts.set(index,new Component("STAIR_SYSTEM",source.slotId(),origin,host.dimensions(),List.of(),p));
                if(j+1<indices.size()) {
                    var turn=new HashMap<String,Object>(p);
                    turn.put("component_id",String.valueOf(p.get("component_id"))+"#turn");turn.put("width",1);
                    // Existing endpoint treads already span each flight's width. Fill only
                    // the gap between them, without overwriting the owned stair blocks.
                    int gapLow=laneA+(width-1)/2+1,gapHigh=laneB-width/2-1;
                    turn.put("from",coords(alongZ,j%2==0?gapLow:gapHigh,ty,end));
                    turn.put("to",coords(alongZ,j%2==0?gapHigh:gapLow,ty,end));
                    landings.add(new Component("STAIR_SYSTEM",source.slotId(),origin,host.dimensions(),List.of(),turn));
                }
            }
        }
        parts.addAll(landings);
    }
    private static Map<String,Integer> coords(boolean z,int lane,int y,int run) {
        return Map.of("x",z?lane:run,"y",y,"z",z?run:lane);
    }

    /** Gate the final emitted cells, including every postprocessor's changes. */
    static boolean validateConnectivity(List<Component> parts, List<com.formacraft.common.patch.BlockPatch> patches,
                                        Map<String,Slot> slots) {
        Set<Object> hosts=new HashSet<>();
        for(var c:parts) if(c.params()!=null && Boolean.TRUE.equals(c.params().get("compiler_connected_stair_chain"))) hosts.add(c.params().get("host_id"));
        if(hosts.isEmpty()) return true;
        var cells=new HashMap<net.minecraft.util.math.BlockPos,net.minecraft.block.BlockState>();
        for(var patch:patches) {
            var state=com.formacraft.common.patch.BlockPatchTargetResolver.resolve(patch);
            if(state!=null) cells.put(new net.minecraft.util.math.BlockPos(patch.dx(),patch.dy(),patch.dz()),state);
        }
        for(Object id:hosts) {
            var host=parts.stream().filter(c->c.params()!=null && id.equals(c.params().get("component_id"))).findFirst().orElse(null);
            if(host==null || host.dimensions()==null) return disconnected(id);
            var b=ComponentFootprintUtil.resolveMinCornerOrigin(host);var d=host.dimensions();
            if((long)d.width()*d.depth()*d.height()>250000) return disconnected(id);
            Slot slot=slots.get(host.slotId());Vec3i a=slot==null || slot.anchor()==null?new Vec3i(0,0,0):slot.anchor();
            int top=parts.stream().filter(c->c.params()!=null && id.equals(c.params().get("host_id"))
                    && Boolean.TRUE.equals(c.params().get("compiler_connected_stair_chain")))
                    .mapToInt(c->c.relativePosition().y()+point(c,"to").y()+a.y()).max().orElseThrow();
            var walkable=new HashSet<net.minecraft.util.math.BlockPos>();
            int wall=ComponentParamParsers.intParam(host.params(),1,"wall_thickness","wallThickness");
            for(int x=wall;x<d.width()-wall;x++) for(int z=wall;z<d.depth()-wall;z++) for(int y=0;y<=d.height();y++) {
                var pos=new net.minecraft.util.math.BlockPos(a.x()+b.x()+x,a.y()+b.y()+y,a.z()+b.z()+z);
                var state=cells.get(pos);
                if(state!=null && !state.isAir() && !(state.getBlock() instanceof net.minecraft.block.FenceBlock)
                        && !(state.getBlock() instanceof net.minecraft.block.WallBlock)
                        && !(state.getBlock() instanceof net.minecraft.block.DoorBlock)
                        && cells.getOrDefault(pos.up(),net.minecraft.block.Blocks.AIR.getDefaultState()).isAir()
                        && cells.getOrDefault(pos.up(2),net.minecraft.block.Blocks.AIR.getDefaultState()).isAir()) walkable.add(pos);
            }
            var reached=new HashSet<net.minecraft.util.math.BlockPos>();var queue=new ArrayDeque<net.minecraft.util.math.BlockPos>();
            for(var pos:walkable) if(pos.getY()<=a.y()+b.y()+1) {reached.add(pos);queue.add(pos);}
            while(!queue.isEmpty()) {
                var pos=queue.remove();
                for(int[] dir:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) for(int dy=-1;dy<=1;dy++) {
                    var n=pos.add(dir[0],dy,dir[1]);if(walkable.contains(n) && reached.add(n)) queue.add(n);
                }
            }
            if(reached.stream().noneMatch(pos->pos.getY()>=top)) return disconnected(id);
            com.formacraft.FormacraftMod.LOGGER.info("[StairChainAudit] host={} connectivity=verified top={} reached={}",id,top,reached.size());
        }
        return true;
    }
    private static boolean disconnected(Object id) {
        AssemblyCompileDiagnostics.set(new CapabilityGap("E_STAIR_CHAIN_UNCONNECTED",
                "转向楼梯最终方块未连通底层与顶部："+id,"components[]",List.of("Keep turn landings and two-block walking clearance.")));
        return false;
    }
    private static Vec3i point(Component c,String key) {
        var p=(Map<?,?>)c.params().get(key);
        return new Vec3i(((Number)p.get("x")).intValue(),((Number)p.get("y")).intValue(),((Number)p.get("z")).intValue());
    }
}
