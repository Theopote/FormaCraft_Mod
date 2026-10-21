package com.formacraft.common.generation.component.util;

import java.util.Map;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/** Explicit wall vocabulary, applied only after opening and roof decisions. */
public final class StyleWallPattern {
    private StyleWallPattern() {}

    public static String apply(String base, String trim, Map<String,Object> params,
            int x,int y,int z,int width,int depth,int floorHeight) {
        if(params==null) return base;
        String pattern=String.valueOf(params.getOrDefault("wall_pattern",params.getOrDefault("wallPattern","uniform")));
        int u=(z==0 || z==depth-1)?x:z;
        int bay=Math.max(3,floorHeight);
        if("timber_frame".equals(pattern) && trim!=null && !trim.isBlank()) {
            int horizontal=Math.floorMod(u,bay), vertical=Math.floorMod(y,bay);
            return horizontal==0 || vertical==0 || horizontal==vertical || horizontal+vertical==bay ? trim:base;
        }
        if("neon_bands".equals(pattern) && Boolean.TRUE.equals(params.get("emissive_enabled"))) {
            String block=String.valueOf(params.getOrDefault("emissive_block","minecraft:sea_lantern"));
            var id=Identifier.tryParse(block);
            if(id==null || !Registries.BLOCK.containsId(id) || Registries.BLOCK.get(id).getDefaultState().getLuminance()==0)
                throw new IllegalArgumentException("E_STYLE_EMISSIVE_INVALID: expected registered luminous block: "+block);
            return y>0 && y%bay==0 ? block:base;
        }
        return base;
    }
}
