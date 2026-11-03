package com.formacraft.common.component.community;

import com.formacraft.common.component.ComponentRecipeLibrary;
import com.formacraft.common.component.ComponentTypes;
import com.formacraft.common.component.variant.SegmentScaler;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static com.formacraft.common.component.community.CommunityJson.*;

/** Bounded game request options; source identity is always resolved by the server. */
public final class CommunityRecipeOptions {
    private CommunityRecipeOptions() {}
    public static final int MAX_BYTES=4096;
    private static final String SCHEMA="formacraft.community.recipe-options.v1";
    public static byte[] encode(ComponentRecipeLibrary.Spec spec) throws IOException {
        if(spec==null || spec.name()==null || spec.name().length()>64) throw new IOException("Missing or oversized recipe name");
        var json=new JsonObject(); json.addProperty("schema",SCHEMA); json.addProperty("name",spec.name());
        json.addProperty("x",spec.x()); json.addProperty("y",spec.y()); json.addProperty("z",spec.z());
        byte[] bytes=json.toString().getBytes(StandardCharsets.UTF_8); decode(bytes); return bytes;
    }
    public static ComponentRecipeLibrary.Spec decode(byte[] bytes) throws IOException {
        var json=read(bytes,MAX_BYTES); CommunityReleaseDetails.exact(json,"schema","name","x","y","z");
        if(!SCHEMA.equals(text(json.get("schema"),64))) throw new IOException("Invalid recipe schema");
        String name=text(json.get("name"),64);
        try { ComponentTypes.custom(name); } catch(IllegalArgumentException invalid) { throw new IOException("Invalid recipe name"); }
        return new ComponentRecipeLibrary.Spec(name,integer(json.get("x"),1,SegmentScaler.MAX_AXIS_SIZE),integer(json.get("y"),1,SegmentScaler.MAX_AXIS_SIZE),integer(json.get("z"),1,SegmentScaler.MAX_AXIS_SIZE));
    }
}
