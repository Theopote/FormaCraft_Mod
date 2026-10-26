package com.formacraft.common.generation.component.util;
import java.util.Map;
/** Equivalent explicit spellings of a wall-free frame. */
public final class OpenFramePolicy {
    private OpenFramePolicy() {}
    public static boolean isOpen(Map<String,Object> p) {
        return p!=null && (Boolean.TRUE.equals(p.get("open"))
                || Boolean.TRUE.equals(p.get("open_pavilion"))
                || "open".equalsIgnoreCase(String.valueOf(p.get("wall_style")))
                || "none".equalsIgnoreCase(String.valueOf(p.get("wall_style")))
                    && p.get("open_sides") instanceof java.util.Collection<?> sides && sides.size()>=2);
    }
}
