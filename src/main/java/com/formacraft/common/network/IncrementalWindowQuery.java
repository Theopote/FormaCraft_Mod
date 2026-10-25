package com.formacraft.common.network;
import com.formacraft.common.llm.dto.Component;
import java.util.*;
/** Convert an explicitly glass window query to the bounded primitive PATCH vocabulary. */
public final class IncrementalWindowQuery {
    private IncrementalWindowQuery() {}
    public static Component normalize(Component c) {
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
