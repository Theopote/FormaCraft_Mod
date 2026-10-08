package com.formacraft.server.compiler;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.util.*;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/** Non-blocking final-patch audit of explicitly bound, simple rectangular roof seams. */
final class RoofSeamAudit {
    record Result(String roofId, String hostId, String status, String reason,
                  BlockPos firstMissing, int missingCells, int checkedCells) {}

    static Optional<Result> inspect(SemanticComponent roof, List<Component> components,
            Map<String, Slot> slots, Slot fallback, List<BlockPatch> patches) {
        var c=roof.source();
        if(c==null || c.params()==null || c.relativePosition()==null || c.dimensions()==null
                || !"ROOF".equalsIgnoreCase(c.componentType())) return Optional.empty();
        var params=c.params();
        String id=String.valueOf(params.getOrDefault("component_id","unidentified_roof"));
        String host=params.get("host_id")==null?null:params.get("host_id").toString();
        if(host==null) return Optional.of(skipped(id,null,"unbound_roof"));
        String type=String.valueOf(params.getOrDefault("roof_type",params.get("roofType"))).toLowerCase(Locale.ROOT);
        if(!Set.of("flat","gable","double_gable").contains(type)) return Optional.of(skipped(id,host,"unsupported_roof"));
        if(ComponentParamParsers.intParam(params,"overhang","overhang_blocks","eave_overhang")!=0
                || "true".equalsIgnoreCase(String.valueOf(params.get("double_eave")))
                || "true".equalsIgnoreCase(String.valueOf(params.get("roof_dormers")))
                || !"flat".equals(type) && "false".equalsIgnoreCase(String.valueOf(params.get("gable_walls")))
                || c.features()!=null && !c.features().isEmpty())
            return Optional.of(skipped(id,host,"specialized_roof"));
        var bodies=components.stream().filter(b->b!=null && "MASS_MAIN".equalsIgnoreCase(b.componentType())
                && b.params()!=null && host.equals(String.valueOf(b.params().get("component_id")))).toList();
        if(bodies.size()!=1) return Optional.of(skipped(id,host,"unresolved_host"));
        var body=bodies.getFirst();
        String partId=String.valueOf(params.getOrDefault("host_part_id",host));
        var parts=ResolvedMassPart.resolve(body).stream().filter(p->p.partId().equals(partId)).toList();
        if(parts.size()!=1) return Optional.of(skipped(id,host,"unresolved_host_part"));
        var part=parts.getFirst();
        var roofOffset=offset(roof.slot());
        var bodySlot=slots.get(body.slotId());
        if(bodySlot==null) bodySlot=fallback;
        var bodyOffset=offset(bodySlot);
        var rp=c.relativePosition();
        var origin=new BlockPos(rp.x(),rp.y(),rp.z()).add(roofOffset);
        var hostOrigin=new BlockPos(part.origin().x(),part.origin().y(),part.origin().z()).add(bodyOffset);
        int w=c.dimensions().width(),d=c.dimensions().depth();
        if(w<3 || d<3 || origin.getX()!=hostOrigin.getX() || origin.getZ()!=hostOrigin.getZ()
                || w!=part.dimensions().width() || d!=part.dimensions().depth())
            return Optional.of(skipped(id,host,"unaligned_footprint"));
        var mask=ComponentFootprintMask.from(roof,params,w,d);
        var bodyParams=new HashMap<String,Object>(body.params()); bodyParams.putAll(part.sourceParams());
        var bodySemantic=new SemanticComponent("MASS_MAIN",bodySlot,body,roof.styleProfile(),roof.styleAttributes(),roof.genome());
        var bodyMask=ComponentFootprintMask.from(bodySemantic,bodyParams,w,d);
        var layers=ResolvedFacadeLayers.resolve(bodySemantic,w,d,part.dimensions().height());
        for(int x=0;x<w;x++) for(int z=0;z<d;z++) {
            if(!mask.contains(x,z) || !bodyMask.contains(x,z)
                    || !ResolvedFacadeLayers.contains(layers[part.dimensions().height()-1],x,z))
                return Optional.of(skipped(id,host,"specialized_host_footprint"));
        }
        int top=part.bounds().maxY()-1+bodyOffset.getY();
        if(origin.getY()<top || origin.getY()>top+1)
            return Optional.of(new Result(id,host,"mismatch","vertical_alignment",null,0,0));
        var finalBlocks=new HashMap<BlockPos,BlockPatch>();
        for(var p:patches) if(p!=null) finalBlocks.put(new BlockPos(p.dx(),p.dy(),p.dz()),p);
        int missing=0,checked=0; BlockPos first=null;
        for(int x=0;x<w;x++) for(int z=0;z<d;z++) {
            if(x!=0 && x!=w-1 && z!=0 && z!=d-1) continue;
            // Both the roof-base ring and immediately adjacent wall ring must remain occupied.
            for(int y=origin.getY()-1;y<=origin.getY();y++) {
                var pos=new BlockPos(origin.getX()+x,y,origin.getZ()+z); checked++;
                if(!GeneratedSurfaceCapture.occupied(finalBlocks.get(pos))) {
                    missing++; if(first==null) first=pos;
                }
            }
        }
        return Optional.of(new Result(id,host,missing==0?"matched":"mismatch",
                missing==0?"boundary_seam":"missing_seam_cell",first,missing,checked));
    }
    private static BlockPos offset(Slot slot) {
        return slot==null || slot.anchor()==null?BlockPos.ORIGIN:
                new BlockPos(slot.anchor().x(),slot.anchor().y(),slot.anchor().z());
    }
    private static Result skipped(String id,String host,String reason) {
        return new Result(id,host,"not_assessed",reason,null,0,0);
    }
}
