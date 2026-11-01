package com.formacraft.common.llm.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;

/**
 * Accepts orchestrator size/anchor shapes:
 * {@code {x,y,z}}, {@code {w,h,d}}, {@code {width,height,depth}}.
 */
public final class Vec3iDeserializer extends StdDeserializer<Vec3i> {
    public Vec3iDeserializer() {
        super(Vec3i.class);
    }

    @Override
    public Vec3i deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode n = p.getCodec().readTree(p);
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isArray() && n.size() >= 3) {
            return new Vec3i(n.get(0).asInt(), n.get(1).asInt(), n.get(2).asInt());
        }
        int x = firstInt(n, "x", "w", "width");
        int y = firstInt(n, "y", "h", "height");
        int z = firstInt(n, "z", "d", "depth");
        return new Vec3i(x, y, z);
    }

    private static int firstInt(JsonNode n, String... keys) {
        for (String key : keys) {
            JsonNode v = n.get(key);
            if (v != null && v.isNumber()) {
                return v.asInt();
            }
            if (v != null && v.isTextual()) {
                try {
                    return Integer.parseInt(v.asText().trim());
                } catch (NumberFormatException ignored) {
                    // try next alias
                }
            }
        }
        return 0;
    }
}
