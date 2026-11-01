package com.formacraft.common.component.transform;

import net.minecraft.util.math.Direction;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v1：对 blockstate string 做“轻量”朝向修正。
 * <p>
 * 说明：
 * - 我们目前把 blockstate 作为字符串保存（例如 minecraft:oak_stairs[facing=east,half=bottom]）
 * - 这里不引入完整 Codec/NBT，只做尽力而为的 facing=... 替换
 */
public final class BlockStateStringUtil {
    private BlockStateStringUtil() {}

    private static final Pattern FACING_KV =
            Pattern.compile("\\b(facing|horizontal_facing)=(north|south|east|west|up|down)\\b", Pattern.CASE_INSENSITIVE);

    /** 从 blockstate string 中提取 facing/horizontal_facing（若不存在返回 null）。 */
    public static Direction extractFacing(String blockStateString) {
        if (blockStateString == null || blockStateString.isBlank()) return null;
        Matcher m = FACING_KV.matcher(blockStateString);
        if (!m.find()) return null;
        return parseDir(m.group(2));
    }

    public static String withTransformedFacing(String blockStateString, Direction fromFacing, ComponentTransform t) {
        if (blockStateString == null || blockStateString.isBlank()) return blockStateString;
        if (t == null) t = ComponentTransform.IDENTITY;

        Matcher m = FACING_KV.matcher(blockStateString);
        if (!m.find()) return blockStateString;

        String key = m.group(1);
        String val = m.group(2);
        Direction original = parseDir(val);
        if (original == null) return blockStateString;

        Direction next = FacingTransformUtil.transformFacing(original, fromFacing, t);
        if (next == null) return blockStateString;

        String replacement = key + "=" + next.name().toLowerCase(Locale.ROOT);
        // replace only first occurrence (多数方块只有一个 facing；保守处理)
        return m.replaceFirst(Matcher.quoteReplacement(replacement));
    }

    /**
     * Merge or replace a single blockstate property while preserving siblings
     * ({@code half}, {@code shape}, {@code waterlogged}, …).
     * <p>
     * Examples:
     * <ul>
     *   <li>{@code oak_stairs} + facing=east → {@code oak_stairs[facing=east]}</li>
     *   <li>{@code oak_stairs[half=top]} + facing=south → {@code oak_stairs[facing=south,half=top]}</li>
     * </ul>
     */
    public static String withProperty(String blockStateString, String key, String value) {
        if (blockStateString == null || blockStateString.isBlank()) return blockStateString;
        if (key == null || key.isBlank() || value == null || value.isBlank()) return blockStateString;
        String propKey = key.trim().toLowerCase(Locale.ROOT);
        String propVal = value.trim().toLowerCase(Locale.ROOT);

        String id = blockStateString.trim();
        String inner = null;
        int bracket = id.indexOf('[');
        if (bracket >= 0) {
            if (!id.endsWith("]")) return blockStateString;
            inner = id.substring(bracket + 1, id.length() - 1);
            id = id.substring(0, bracket);
        }
        java.util.LinkedHashMap<String, String> props = new java.util.LinkedHashMap<>();
        if (inner != null && !inner.isBlank()) {
            for (String part : inner.split(",")) {
                String token = part.trim();
                if (token.isEmpty()) continue;
                int eq = token.indexOf('=');
                if (eq <= 0) continue;
                String k = token.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                String v = token.substring(eq + 1).trim().toLowerCase(Locale.ROOT);
                if (!k.isEmpty() && !v.isEmpty()) props.put(k, v);
            }
        }
        props.put(propKey, propVal);
        java.util.List<String> keys = new java.util.ArrayList<>(props.keySet());
        keys.sort(String::compareTo);
        StringBuilder sb = new StringBuilder(id);
        sb.append('[');
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) sb.append(',');
            String k = keys.get(i);
            sb.append(k).append('=').append(props.get(k));
        }
        sb.append(']');
        return sb.toString();
    }

    /** Set {@code facing=} (and preserve other properties). */
    public static String withFacing(String blockStateString, String facing) {
        return withProperty(blockStateString, "facing", facing);
    }

    /**
     * 将 BlockState 序列化为 blockstate string（稳定排序属性，便于 diff/存档）。
     * 形如：minecraft:oak_stairs[facing=east,half=bottom]
     */
    public static String fromState(net.minecraft.block.BlockState state) {
        if (state == null) return "minecraft:air";
        String id = net.minecraft.registry.Registries.BLOCK.getId(state.getBlock()).toString();
        if (state.getEntries().isEmpty()) return id;

        java.util.List<java.util.Map.Entry<net.minecraft.state.property.Property<?>, Comparable<?>>> entries =
                new java.util.ArrayList<>(state.getEntries().entrySet());
        entries.sort(java.util.Comparator.comparing(e -> e.getKey().getName()));

        StringBuilder sb = new StringBuilder(id);
        sb.append("[");
        boolean first = true;
        for (java.util.Map.Entry<net.minecraft.state.property.Property<?>, Comparable<?>> e : entries) {
            if (!first) sb.append(",");
            first = false;
            sb.append(e.getKey().getName()).append("=").append(e.getValue());
        }
        sb.append("]");
        return sb.toString();
    }

    private static Direction parseDir(String s) {
        if (s == null) return null;
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            case "up" -> Direction.UP;
            case "down" -> Direction.DOWN;
            default -> null;
        };
    }
}

