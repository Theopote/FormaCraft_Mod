package com.formacraft.common.network;

import com.formacraft.common.llm.dto.Component;
import java.util.Locale;

/** Flat glass inserts only; not a general primitive rebuild escape hatch. */
public final class IncrementalPrimitivePolicy {
    private IncrementalPrimitivePolicy() {}
    public static boolean allows(Component c) {
        if (c == null || !"PRIMITIVE".equalsIgnoreCase(c.componentType())
                || c.dimensions() == null || c.params() == null) return false;
        var d=c.dimensions();
        if (d.width()<1 || d.depth()<1 || d.height()<1 || d.width()>8 || d.depth()>8 || d.height()>8
                || (long)d.width()*d.depth()*d.height()>64 || (d.width()!=1 && d.depth()!=1)) return false;
        var p=c.params();
        if (!"box".equalsIgnoreCase(String.valueOf(p.get("kind")))) return false;
        for (String key : new String[]{"operations","subtract","assembly","component_request","masses"})
            if (p.containsKey(key)) return false;
        for (String key : new String[]{"rotation_x_deg","rotation_y_deg","rotation_z_deg"})
            if (p.containsKey(key) && (!(p.get(key) instanceof Number n) || n.doubleValue()!=0)) return false;
        if (c.features()!=null && !c.features().isEmpty()) return false;
        String material=String.valueOf(p.get("material")).toLowerCase(Locale.ROOT);
        return material.equals("glass") || material.equals("minecraft:glass")
                || material.matches("(?:minecraft:)?[a-z_]+_stained_glass");
    }
}
