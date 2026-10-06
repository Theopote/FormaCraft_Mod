package com.formacraft.common.style;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Alias normalization only; historical and purpose variants retain their own identity. */
public final class StyleIdentityRegistry {
    private StyleIdentityRegistry() {}
    private static final Map<String,String> ALIASES=load();
    private static Map<String,String> load() {
        Map<String,String> aliases=new LinkedHashMap<>();
        try(var stream=StyleIdentityRegistry.class.getResourceAsStream("/assets/formacraft/style_profiles/style_identity_catalog_v1.json")) {
            if(stream==null) throw new IllegalStateException("Missing architectural style identity catalog");
            JsonObject root=JsonParser.parseString(new String(stream.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
            root.getAsJsonObject("aliases").entrySet().forEach(e->aliases.put(e.getKey(),e.getValue().getAsString()));
            root.getAsJsonObject("lexical_aliases").entrySet().forEach(e->{
                aliases.put(e.getKey(),e.getKey());
                e.getValue().getAsJsonArray().forEach(alias->aliases.put(alias.getAsString(),e.getKey()));
            });
            root.getAsJsonObject("variants").keySet().forEach(id->aliases.put(id,id));
            root.getAsJsonObject("non_style_ids").keySet().forEach(id->aliases.put(id,id));
        } catch(java.io.IOException ex) {throw new IllegalStateException("Cannot load architectural style identities",ex);}
        return Map.copyOf(aliases);
    }
    public static String canonical(String value) {
        if(value==null) return null;
        String key=value.trim();
        for(var entry:ALIASES.entrySet()) if(entry.getKey().equalsIgnoreCase(key)) return entry.getValue();
        return key;
    }
    /** Includes recognized variants and non-style editing intents, not only implemented profiles. */
    public static boolean isKnownIdentity(String value) {
        return value != null && ALIASES.containsValue(canonical(value));
    }
}
