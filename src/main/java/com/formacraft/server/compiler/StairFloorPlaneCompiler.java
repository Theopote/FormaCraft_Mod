package com.formacraft.server.compiler;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.generation.component.util.*;
import java.util.*;
/** Normalize the unambiguous standing-air vs floor-block off-by-one on both ends of an interior stair. */
final class StairFloorPlaneCompiler {
    static void apply(List<Component> parts) {
        for(int i=0;i<parts.size();i++) {
            var stair=parts.get(i);
            if(!com.formacraft.server.generation.component.impl.StraightStairComponentGenerator.accepts(stair)
                    || stair.params()==null || stair.relativePosition()==null || Boolean.TRUE.equals(stair.params().get("exterior"))
                    || !(stair.params().get("from") instanceof Map<?,?> from) || !(stair.params().get("to") instanceof Map<?,?> to))continue;
            for(var host:parts) {
                if(!MassComponentKinds.isCirculationMass(host) || host.params()==null
                        || !Objects.equals(stair.slotId(),host.slotId()) || !Objects.equals(stair.params().get("host_id"),host.params().get("component_id")))continue;
                var b=ComponentFootprintUtil.resolveMinCornerOrigin(host);
                int fh=ComponentParamParsers.intParam(host.params(),0,"floor_height","floorHeight");
                int count=ComponentParamParsers.intParam(host.params(),0,"floor_count","floorCount");
                if(b==null || fh<2 || count<2 || !(from.get("y") instanceof Number f) || !(to.get("y") instanceof Number t)) continue;
                int low=stair.relativePosition().y()+Math.min(f.intValue(),t.intValue()),high=stair.relativePosition().y()+Math.max(f.intValue(),t.intValue());
                if(low==b.y()+1 && high>b.y()+1 && (high-b.y()-1)%fh==0 && (high-b.y()-1)/fh<count) {
                    var p=stair.relativePosition();parts.set(i,new Component(stair.componentType(),stair.slotId(),
                            new Vec3i(p.x(),p.y()-1,p.z()),stair.dimensions(),stair.features(),stair.params()));
                }
                break;
            }
        }
    }
}
