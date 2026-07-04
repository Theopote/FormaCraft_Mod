package com.formacraft.common.geometry.shape;

import java.util.Locale;

/**
 * M3：PRIMITIVE 挤出模式。
 * <ul>
 *   <li>{@link #SOLID} — 默认，填满 bounding box 内的 3D 形体</li>
 *   <li>{@link #PLATE} — 2D  footprint 单层（y=0），适合铺地/标志/镂空板</li>
 * </ul>
 */
public enum ShapeExtrudeMode {
    SOLID,
    PLATE;

    public static ShapeExtrudeMode parse(Object raw) {
        if (raw == null) {
            return SOLID;
        }
        String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "plate", "2d", "slab", "flat", "footprint" -> PLATE;
            default -> SOLID;
        };
    }
}
