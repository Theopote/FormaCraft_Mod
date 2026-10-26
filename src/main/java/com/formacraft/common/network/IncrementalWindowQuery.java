package com.formacraft.common.network;
import com.formacraft.common.llm.dto.Component;
import java.util.*;
/** Convert an explicitly glass window query to the bounded primitive PATCH vocabulary. */
public final class IncrementalWindowQuery {
    private IncrementalWindowQuery() {}
    public static Component normalize(Component c) {
        if(c!=null && c.componentType()!=null && c.params()!=null && Set.of("FACADE_WINDOWS","WINDOW","WINDOWS").contains(c.componentType())) {
            Object material=c.params().get("window_block");
            var dims=c.dimensions();
            Object openingHeight=c.params().get("opening_height"), openingWidth=c.params().get("opening_width");
            boolean singleWindow=dims!=null
                    && (!(openingHeight instanceof Number h) || h.intValue()==dims.height())
                    && (!(openingWidth instanceof Number w) || w.intValue()==Math.max(dims.width(),dims.depth()))
                    && (!(c.params().get("count") instanceof Number n) || n.intValue()==1);
            if(singleWindow && material instanceof String block && block.contains("glass")) {
                var p=new HashMap<String,Object>();
                p.put("kind","box");p.put("material",block);p.put("extrude_mode","solid");
                if(c.params().containsKey("component_id")) p.put("component_id",c.params().get("component_id"));
                if(c.params().containsKey("anchor_mode")) p.put("anchor_mode",c.params().get("anchor_mode"));
                var literal=new Component("PRIMITIVE",c.slotId(),c.relativePosition(),c.dimensions(),List.of(),p);
                if(IncrementalPrimitivePolicy.allows(literal)) return literal;
            }
        }
        if(c==null || !"PRIMITIVE".equalsIgnoreCase(c.componentType()) || c.features()==null) return c;
        for(String f:c.features()) {
            if(f==null || !f.startsWith("component_request:")) continue;
            try {
                var root=com.google.gson.JsonParser.parseString(f.substring("component_request:".length())).getAsJsonObject();
                var q=root.getAsJsonObject("component_query"); var semantic=q.getAsJsonObject("semantic");
                if(!"window".equals(semantic.get("role").getAsString())) continue;
                var tags=semantic.getAsJsonArray("tags"); boolean glass=false;
                for(var tag:tags) if("glass".equals(tag.getAsString())) glass=true;
                if(!glass) continue;
                var p=new HashMap<String,Object>(); if(c.params()!=null) p.putAll(c.params());
                p.put("kind","box");p.put("material","minecraft:glass");p.put("extrude_mode","solid");
                p.remove("host_id"); // Direct patch coordinates resolve against the existing building anchor.
                var normalized=new Component("PRIMITIVE",c.slotId(),c.relativePosition(),c.dimensions(),List.of(),p);
                return IncrementalPrimitivePolicy.allows(normalized)?normalized:c;
            } catch(RuntimeException ignored) { return c; }
        }
        return c;
    }
}
