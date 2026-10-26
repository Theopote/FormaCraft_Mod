package com.formacraft.server.compiler;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.generation.routing.GenerationAuthority;
import java.util.*;
/** Ordinary native parts do not require a semantic library search. Captured IDs remain opt-in. */
final class NativeBuildingComponentPolicy {
    static Component apply(Component c, LlmPlan plan) {
        if(c==null || c.features()==null || c.componentType()==null
                || GenerationAuthority.resolve(c,plan)!=GenerationAuthority.COMPONENTS
                || !Set.of("FACADE_WINDOWS","WINDOW","WINDOWS","ENTRANCE","DOOR","ROOF").contains(c.componentType())) return c;
        var features=c.features().stream().filter(f->!semanticQuery(f)).toList();
        return features.equals(c.features())?c:new Component(c.componentType(),c.slotId(),c.relativePosition(),c.dimensions(),features,c.params());
    }
    private static boolean semanticQuery(String feature) {
        if(feature==null || !feature.startsWith("component_request:")) return false;
        try {
            var root=com.google.gson.JsonParser.parseString(feature.substring("component_request:".length())).getAsJsonObject();
            return root.size()==1 && root.has("component_query");
        } catch(RuntimeException ignored) {return false;}
    }
}
