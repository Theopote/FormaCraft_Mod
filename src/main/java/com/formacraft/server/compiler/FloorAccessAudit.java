package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.generation.component.util.ResolvedMassPart;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.BlockPatchTargetResolver;
import com.formacraft.server.assembly.AssemblyCirculationConstraints.Flight;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/** Final-patch stair-level observations, not a whole-building pathfinding proof. */
final class FloorAccessAudit {
    record Result(String buildingId, String status, List<Integer> observedFloors,
                  List<Integer> missingFloors, String connectivity) {}
    static Optional<Result> inspect(Component mass, BlockPos offset, List<BlockPatch> patches, List<Flight> flights) {
        int count=ComponentParamParsers.intParam(mass.params(),0,"floor_count","floorCount");
        int height=ComponentParamParsers.intParam(mass.params(),0,"floor_height","floorHeight");
        if(count<=1 || height<=0) return Optional.empty();
        String id=String.valueOf(mass.params().getOrDefault("component_id","unknown"));
        var parts=ResolvedMassPart.resolve(mass);
        if(parts.size()!=1 || count>128) return Optional.of(new Result(id,"not_assessed_complex_geometry",List.of(),List.of(),"not_assessed"));
        var bounds=parts.getFirst().bounds();
        var candidates=new HashSet<BlockPos>();
        for(var flight:flights) for(var pos:flight.occupied()) {
            if(flight.clearance().contains(pos.up()) && flight.clearance().contains(pos.up(2))
                && pos.getX()>=bounds.minX()+offset.getX() && pos.getX()<bounds.maxX()+offset.getX()
                && pos.getZ()>=bounds.minZ()+offset.getZ() && pos.getZ()<bounds.maxZ()+offset.getZ()) candidates.add(pos);
        }
        var relevant=new HashSet<BlockPos>(candidates);
        for(var pos:candidates) { relevant.add(pos.up()); relevant.add(pos.up(2)); }
        var last=new HashMap<BlockPos,BlockPatch>();
        for(var patch:patches) {
            var pos=new BlockPos(patch.dx(),patch.dy(),patch.dz());
            if(relevant.contains(pos)) last.put(pos,patch);
        }
        var observed=new ArrayList<Integer>(); var missing=new ArrayList<Integer>();
        for(int floor=1;floor<=count;floor++) {
            int y=bounds.minY()+offset.getY()+(floor-1)*height;
            boolean found=candidates.stream().anyMatch(pos->pos.getY()==y && solid(last.get(pos))
                    && air(last.get(pos.up())) && air(last.get(pos.up(2))));
            (found?observed:missing).add(floor);
        }
        return Optional.of(new Result(id,missing.isEmpty()?"stair_levels_observed":"stair_levels_not_observed",
                List.copyOf(observed),List.copyOf(missing),"not_assessed"));
    }
    private static boolean air(BlockPatch patch) {
        if(patch==null) return false;
        var state=BlockPatchTargetResolver.resolve(patch);
        return state!=null && state.isAir();
    }
    private static boolean solid(BlockPatch patch) {
        if(patch==null) return false;
        var state=BlockPatchTargetResolver.resolve(patch);
        return state!=null && !state.isAir();
    }
}
