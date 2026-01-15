package com.formacraft.common.component.validate;

import com.formacraft.common.component.ComponentDefinition;

import static com.formacraft.common.component.validate.EnumUtil.isBlank;

/**
 * 几何信息验证器（size, anchor, blocks）。
 */
public final class GeometryValidator {
    private GeometryValidator() {}

    public static void validate(ComponentDefinition def, ValidationResult out) {
        // Size
        if (def.size == null) {
            out.error("size", "Missing size");
        } else {
            if (def.size.w <= 0) {
                out.error("size.w", "Width must be > 0");
            }
            if (def.size.h <= 0) {
                out.error("size.h", "Height must be > 0");
            }
            if (def.size.d <= 0) {
                out.error("size.d", "Depth must be > 0");
            }
            if (def.size.w > 256 || def.size.h > 256 || def.size.d > 256) {
                out.warn("size", "Very large size (>256). Might be heavy.");
            }
        }

        // Anchor
        if (def.anchor == null) {
            out.error("anchor", "Missing anchor");
        } else {
            // anchor 的相对坐标应该在 size 范围内（或允许稍微超出，因为可能是边界）
            if (def.size != null) {
                if (def.anchor.dx < -1 || def.anchor.dx > def.size.w ||
                    def.anchor.dy < -1 || def.anchor.dy > def.size.h ||
                    def.anchor.dz < -1 || def.anchor.dz > def.size.d) {
                    out.warn("anchor", String.format(
                        "Anchor position (%d,%d,%d) is outside or at edge of size bounds (%d,%d,%d)",
                        def.anchor.dx, def.anchor.dy, def.anchor.dz,
                        def.size.w, def.size.h, def.size.d));
                }
            }

            // facing
            if (isBlank(def.anchor.facing)) {
                out.warn("anchor.facing", "Missing anchor.facing (default may be SOUTH)");
            } else {
                String facing = def.anchor.facing.trim().toUpperCase();
                if (!facing.matches("^(NORTH|SOUTH|EAST|WEST|UP|DOWN)$")) {
                    out.error("anchor.facing", "Invalid facing: " + def.anchor.facing + " (expected: NORTH/SOUTH/EAST/WEST/UP/DOWN)");
                }
            }
        }

        // Blocks
        if (def.blocks == null || def.blocks.isEmpty()) {
            out.error("blocks", "Missing or empty blocks list");
        } else {
            if (def.blocks.size() > 10000) {
                out.warn("blocks", "Very large block count (>10000). Might be heavy.");
            }

            for (int i = 0; i < def.blocks.size(); i++) {
                var block = def.blocks.get(i);
                if (block == null) {
                    out.warn("blocks[" + i + "]", "Null block entry");
                    continue;
                }

                // 检查 block 字符串
                if (isBlank(block.block)) {
                    out.error("blocks[" + i + "].block", "Missing block state string");
                } else {
                    // 基本格式检查：应该包含冒号（minecraft:xxx）
                    String blockStr = block.block.trim();
                    if (!blockStr.contains(":")) {
                        out.warn("blocks[" + i + "].block", "Block string should be in format 'namespace:block_id' or 'namespace:block_id[properties]'. Got: " + blockStr);
                    }
                }

                // 坐标检查（相对于 anchor，应该在合理范围内）
                // 注意：这里不检查是否在 size 内，因为 crossChecks 会做
            }
        }
    }
}
