package com.formacraft.server.generation.structure.util;

import com.formacraft.common.logging.FcaLog;
import com.formacraft.common.model.build.BuildingSpec;
import net.minecraft.util.math.Direction;

import java.util.Locale;
import java.util.Map;

/**
 * Shared coercion helpers for structure generators and blueprint compilers.
 * <p>
 * Replaces duplicated {@code getIntExtra}/{@code parseFacing} helpers and empty catch blocks.
 */
public final class StructureSpecParsers {
    private StructureSpecParsers() {}

    private static final FcaLog LOG = FcaLog.of("StructureSpecParsers");

    // ---------- primitives ----------

    public static int intValue(Object raw, int defaultValue) {
        if (raw == null) return defaultValue;
        if (raw instanceof Number n) return n.intValue();
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            LOG.debug("parse int failed value={} def={}", raw, defaultValue);
            return defaultValue;
        }
    }

    public static Integer intValueOrNull(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number n) return n.intValue();
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) return null;
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            LOG.debug("parse int failed value={}", raw);
            return null;
        }
    }

    public static double doubleValue(Object raw, double defaultValue) {
        if (raw == null) return defaultValue;
        if (raw instanceof Number n) return n.doubleValue();
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) return defaultValue;
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            LOG.debug("parse double failed value={} def={}", raw, defaultValue);
            return defaultValue;
        }
    }

    public static boolean boolValue(Object raw, boolean defaultValue) {
        if (raw == null) return defaultValue;
        if (raw instanceof Boolean b) return b;
        String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return defaultValue;
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on");
    }

    public static String stringValue(Object raw, String defaultValue) {
        if (raw == null) return defaultValue;
        String s = String.valueOf(raw).trim();
        return s.isEmpty() ? defaultValue : s;
    }

    // ---------- facing ----------

    public static Direction horizontalFacing(Object raw, Direction defaultFacing) {
        if (raw == null) return defaultFacing;
        return horizontalFacing(String.valueOf(raw), defaultFacing);
    }

    /**
     * Parse N/E/S/W from enum name, short aliases, or Chinese labels.
     */
    public static Direction horizontalFacing(String raw, Direction defaultFacing) {
        if (raw == null) return defaultFacing;
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty()) return defaultFacing;
        Direction alias = switch (s) {
            case "N", "NORTH", "北", "朝北" -> Direction.NORTH;
            case "S", "SOUTH", "南", "朝南" -> Direction.SOUTH;
            case "E", "EAST", "东", "朝东" -> Direction.EAST;
            case "W", "WEST", "西", "朝西" -> Direction.WEST;
            default -> null;
        };
        if (alias != null) return alias;
        try {
            Direction d = Direction.valueOf(s);
            return d.getAxis().isHorizontal() ? d : defaultFacing;
        } catch (Exception e) {
            LOG.debug("parse facing failed value={} def={}", raw, defaultFacing);
            return defaultFacing;
        }
    }

    // ---------- BuildingSpec.extra ----------

    public static int extraInt(BuildingSpec spec, String key, int defaultValue) {
        if (spec == null || spec.getExtra() == null) return defaultValue;
        return intValue(spec.getExtra().get(key), defaultValue);
    }

    /** Missing or invalid values return {@code absentValue} (commonly -1). */
    public static int extraIntOrAbsent(BuildingSpec spec, String key, int absentValue) {
        if (spec == null || spec.getExtra() == null) return absentValue;
        Object raw = spec.getExtra().get(key);
        if (raw == null) return absentValue;
        Integer parsed = intValueOrNull(raw);
        return parsed == null ? absentValue : parsed;
    }

    public static double extraDouble(BuildingSpec spec, String key, double defaultValue) {
        if (spec == null || spec.getExtra() == null) return defaultValue;
        return doubleValue(spec.getExtra().get(key), defaultValue);
    }

    public static boolean extraBool(BuildingSpec spec, String key, boolean defaultValue) {
        if (spec == null || spec.getExtra() == null) return defaultValue;
        return boolValue(spec.getExtra().get(key), defaultValue);
    }

    public static String extraString(BuildingSpec spec, String key, String defaultValue) {
        if (spec == null || spec.getExtra() == null) return defaultValue;
        return stringValue(spec.getExtra().get(key), defaultValue);
    }

    public static int mapInt(Map<String, Object> extra, String key, int defaultValue) {
        if (extra == null) return defaultValue;
        return intValue(extra.get(key), defaultValue);
    }

    /**
     * Priority: extra.layout.entranceFacing → extra.doorSide → extra.facing.
     */
    public static Direction resolveEntranceFacing(BuildingSpec spec, Direction defaultFacing) {
        if (spec == null || spec.getExtra() == null) return defaultFacing;
        try {
            Object layoutObj = spec.getExtra().get("layout");
            if (layoutObj instanceof Map<?, ?> layout) {
                Object ef = layout.get("entranceFacing");
                if (ef != null) {
                    return horizontalFacing(ef, defaultFacing);
                }
            }
        } catch (Throwable t) {
            LOG.debug("read layout.entranceFacing failed", t);
        }
        Object doorSide = spec.getExtra().get("doorSide");
        if (doorSide == null) doorSide = spec.getExtra().get("facing");
        if (doorSide != null) {
            return horizontalFacing(doorSide, defaultFacing);
        }
        return defaultFacing;
    }
}
