package com.formacraft.server.compiler;
import com.formacraft.common.llm.dto.*;
import net.minecraft.util.math.BlockPos;
import java.util.*;
/** Explicit exterior circulation must remain clear through its deck railing. */
final class DeckRailingAccessCompiler {
    static void apply(List<Component> parts) {
        for(int i=0;i<parts.size();i++) {
            var rail=parts.get(i);if(!"DECOR_DETAIL".equals(rail.componentType()) || rail.params()==null || rail.dimensions()==null
                || !("railing".equals(rail.params().get("role")) || Boolean.TRUE.equals(rail.params().get("railing"))))continue;
            var clear=new HashSet<String>();var r=rail.relativePosition();var d=rail.dimensions();
            for(var stair:parts) {
                if(stair.params()==null || !Boolean.TRUE.equals(stair.params().get("exterior"))
                    || !Objects.equals(stair.slotId(),rail.slotId()) || !Objects.equals(stair.params().get("host_id"),rail.params().get("host_id")))continue;
                for(int x=0;x<d.width();x++)for(int z=0;z<d.depth();z++)for(int y=0;y<d.height();y++)
                    if(PatchOccupancyIndex.withinExteriorStairEnvelope(stair,new BlockPos(r.x()+x,r.y()+y,r.z()+z)))clear.add(x+","+y+","+z);
            }
            if(clear.isEmpty())continue;
            var p=new HashMap<String,Object>(rail.params());p.put("compiler_clear_railing_cells",clear);
            parts.set(i,new Component(rail.componentType(),rail.slotId(),r,d,rail.features(),p));
        }
    }
}
