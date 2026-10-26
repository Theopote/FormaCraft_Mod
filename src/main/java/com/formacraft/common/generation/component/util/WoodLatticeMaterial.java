package com.formacraft.common.generation.component.util;

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
            Object value=params.get(key);if(value!=null&&!value.toString().isBlank()) {wood=value.toString().toLowerCase(Locale.ROOT);break;}
        }
        if(wood.endsWith("_fence"))return wood.contains(":")?wood:"minecraft:"+wood;
        if(wood.contains("dark_oak"))return "minecraft:dark_oak_fence";
        if(wood.contains("spruce"))return "minecraft:spruce_fence";
        return "minecraft:oak_fence";
    }
}
