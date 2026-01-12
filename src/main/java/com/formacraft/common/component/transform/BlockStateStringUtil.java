package com.formacraft.common.component.transform;

import net.minecraft.util.math.Direction;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v1：对 blockstate string 做“轻量”朝向修正。
 *
 * 说明：
 * - 我们目前把 blockstate 作为字符串保存（例如 minecraft:oak_stairs[facing=east,half=bottom]）
 * - 这里不引入完整 Codec/NBT，只做尽力而为的 facing=... 替换
 */
public final class BlockStateStringUtil {
    private BlockStateStringUtil() {}

    private static final Pattern FACING_KV =
            Pattern.compile("\\b(facing|horizontal_facing)=(north|south|east|west|up|down)\\b", Pattern.CASE_INSENSITIVE);

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

