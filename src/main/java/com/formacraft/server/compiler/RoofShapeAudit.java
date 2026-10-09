package com.formacraft.server.compiler;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.util.ComponentFootprintMask;
import com.formacraft.common.generation.component.util.GeneratedSurfaceCapture;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/** Non-blocking audit of simple rectangular gable roofs after final patch processing. */
final class RoofShapeAudit {
    record Result(String componentId, String status, String reason, List<Integer> sectionHeights, int sectionIndex, int checkedSections) {}
    static Optional<Result> inspect(SemanticComponent semantic, List<BlockPatch> patches, BlockPos offset) {
        Component c=semantic.source();
        if(c==null || !"ROOF".equalsIgnoreCase(c.componentType()) || c.params()==null
                || c.relativePosition()==null || c.dimensions()==null) return Optional.empty();
        String type=String.valueOf(c.params().getOrDefault("roof_type",c.params().get("roofType")));
        if(!Set.of("gable","double_gable").contains(type.toLowerCase(Locale.ROOT))) return Optional.empty();
        String id=String.valueOf(c.params().getOrDefault("component_id","unidentified_roof"));
        int w=c.dimensions().width(), d=c.dimensions().depth();
        if(w<3 || d<3) return Optional.of(new Result(id,"not_assessed","small_footprint",List.of(), -1, 0));
        if("true".equalsIgnoreCase(String.valueOf(c.params().get("roof_dormers"))) || "true".equalsIgnoreCase(String.valueOf(c.params().get("double_eave")))
                || c.features()!=null && c.features().stream().anyMatch(f->f!=null &&
                    (f.toLowerCase(Locale.ROOT).contains("dormer") || f.toLowerCase(Locale.ROOT).contains("double_eave"))))
            return Optional.of(new Result(id,"not_assessed","complex_roof",List.of(), -1, 0));
        var mask=ComponentFootprintMask.from(semantic,c.params(),w,d);
        for(int x=0;x<w;x++) for(int z=0;z<d;z++) if(!mask.contains(x,z))
            return Optional.of(new Result(id,"not_assessed","non_rectangular_footprint",List.of(), -1, 0));
        var finalBlocks=new HashMap<BlockPos,BlockPatch>();
        for(var patch:patches) if(patch!=null) finalBlocks.put(new BlockPos(patch.dx(),patch.dy(),patch.dz()),patch);
        var origin=new BlockPos(c.relativePosition().x(),c.relativePosition().y(),c.relativePosition().z()).add(offset);
        boolean alongDepth=d>=w;
        int across=alongDepth?w:d;
        int length=alongDepth?d:w;
        // Index final occupied columns once; respect REMOVE and later replacements.
        var tops=new HashMap<Long,Integer>();
        for(var entry:finalBlocks.entrySet()) {
            var pos=entry.getKey();
            if(pos.getY()>=origin.getY() && GeneratedSurfaceCapture.occupied(entry.getValue()))
                tops.merge(columnKey(pos.getX(),pos.getZ()),pos.getY(),Math::max);
        }
        List<Integer> last=List.of();
        int checked=0;
        // End faces may contain style-specific gable ornaments; audit every interior section.
        for(int section=1;section<length-1;section++) {
            var heights=new ArrayList<Integer>();
            for(int a=0;a<across;a++) {
                int x=origin.getX()+(alongDepth?a:section);
                int z=origin.getZ()+(alongDepth?section:a);
                Integer highest=tops.get(columnKey(x,z));
                if(highest==null) return Optional.of(new Result(id,"mismatch","missing_slope_column",
                        List.copyOf(heights),section,checked+1));
                heights.add(highest);
            }
            checked++;
            int center=across/2, peak=heights.get(center);
            boolean rise=peak>heights.getFirst() && peak>heights.getLast();
            boolean continuous=true;
            for(int i=1;i<=center;i++) continuous &= heights.get(i)>=heights.get(i-1);
            for(int i=center+1;i<across;i++) continuous &= heights.get(i)<=heights.get(i-1);
            if(!rise || !continuous) return Optional.of(new Result(id,"mismatch",
                    !rise?"no_ridge_rise":"non_monotonic_slope",List.copyOf(heights),section,checked));
            if(!last.isEmpty()) {
                int delta=heights.getFirst()-last.getFirst();
                boolean translated=delta!=0;
                for(int i=1;i<across;i++) translated &= heights.get(i)-last.get(i)==delta;
                if(translated) return Optional.of(new Result(id,"mismatch","longitudinal_section_shift",
                        List.copyOf(heights),section,checked));
            }
            last=List.copyOf(heights);
        }
        return Optional.of(new Result(id,"matched","gable_sections",last,length-2,checked));
    }
    private static long columnKey(int x,int z) {
        return ((long)x << 32) ^ (z & 0xffffffffL);
    }
}
