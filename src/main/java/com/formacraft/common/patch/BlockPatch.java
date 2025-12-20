package com.formacraft.common.patch;

/**
 * 单个方块的增量修改（相对 origin 的偏移）。
 *
 * action:
 * - "place": 放置为 targetBlock
 * - "replace": 替换为 targetBlock
 * - "remove": 置为空气
 */
public record BlockPatch(String action, int dx, int dy, int dz, String targetBlock) {
    public static final String PLACE = "place";
    public static final String REPLACE = "replace";
    public static final String REMOVE = "remove";
}

