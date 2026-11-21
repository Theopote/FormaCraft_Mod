package com.formacraft.common.generation.component.util;

import com.formacraft.common.palette.component.MaterialBlockFamily;
import java.util.*;

/** Shared material contract for the native wooden lattice representation. */
public final class WoodLatticeMaterial {
    private WoodLatticeMaterial() {}
    public static String resolve(Map<String,Object> params) {
        if(params==null)return null;
        Object style=params.getOrDefault("window_style",params.get("windowStyle"));
        if(style==null || !Set.of("wood_lattice","wooden_lattice","timber_lattice")
                .contains(style.toString().toLowerCase(Locale.ROOT)))return null;
        String wood="";
        for(String key:List.of("lattice_material","material","frame_material")) {
            Object value=params.get(key);if(value!=null&&!value.toString().isBlank()) {wood=value.toString().trim();break;}
        }
        String base = wood.split("\\[", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (base.endsWith("_fence")) return base.contains(":") ? wood : "minecraft:" + wood;
        if (base.endsWith("_planks")) {
            String fence = MaterialBlockFamily.fence(base);
            if (fence != null) return fence;
        }
        if(base.contains("dark_oak"))return "minecraft:dark_oak_fence";
        if(base.contains("spruce"))return "minecraft:spruce_fence";
        return "minecraft:oak_fence";
    }
}
