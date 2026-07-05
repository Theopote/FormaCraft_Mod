package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import net.minecraft.util.math.Direction;

import java.util.BitSet;
import java.util.Locale;
import java.util.Map;

/**
 * Story 分界檐口：在楼层顶圈外墙替换为 inverted stairs（half=top），形成线脚。
 */
public final class ComponentFloorCorniceDecorator {

    private ComponentFloorCorniceDecorator() {}

    public static boolean shouldApply(LlmPlan plan) {
        if (plan == null) {
            return false;
        }
        Map<String, Object> hints = plan.proportionHints();
        if (hints != null) {
            if (isDisabled(hints.get("floor_cornice")) || isDisabled(hints.get("floorCornice"))) {
                return false;
            }
            if (isEnabled(hints.get("floor_cornice")) || isEnabled(hints.get("floorCornice"))) {
                return true;
            }
            String typology = String.valueOf(hints.getOrDefault("typology", "")).toLowerCase(Locale.ROOT);
            if (typology.contains("classical") || typology.contains("monument")
                    || typology.contains("palace") || typology.contains("castle")) {
                return true;
            }
        }
        if (plan.components() != null) {
            for (Component c : plan.components()) {
                if (c == null || c.params() == null) {
                    continue;
                }
                Object v = c.params().get("floor_cornice");
                if (v == null) {
                    v = c.params().get("floorCornice");
                }
                if (isDisabled(v)) {
                    return false;
                }
                if (isEnabled(v)) {
                    return true;
                }
                String profile = getParamString(c.params(), "facade_profile", "facadeProfile");
                if (profile != null) {
                    String fp = profile.toLowerCase(Locale.ROOT);
                    if (fp.contains("pilaster") || fp.contains("colonnade") || fp.contains("classical")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static int resolveFloorHeight(LlmPlan plan, int buildingHeight) {
        int fromPlan = readFloorHeightFromPlan(plan);
        if (fromPlan > 0) {
            return fromPlan;
        }
        if (buildingHeight >= 12) {
            return 4;
        }
        if (buildingHeight >= 8) {
            return 4;
        }
        return 3;
    }

    /** 每层顶圈 Y（不含屋顶尖），例如 floorHeight=4,height=12 → {3,7,11} */
    public static BitSet computeFloorBoundaryYs(int height, int floorHeight) {
        BitSet ys = new BitSet();
        if (height <= 2 || floorHeight <= 1) {
            return ys;
        }
        for (int y = floorHeight - 1; y < height - 1; y += floorHeight) {
            ys.set(y);
        }
        return ys;
    }

    public static boolean isFloorBoundary(int y, int height, int floorHeight) {
        return computeFloorBoundaryYs(height, floorHeight).get(y);
    }

    public static boolean isPerimeter(int x, int z, int minX, int maxX, int minZ, int maxZ) {
        return x == minX || x == maxX || z == minZ || z == maxZ;
    }

    public static Direction outwardFacing(int x, int z, int minX, int maxX, int minZ, int maxZ) {
        if (z == minZ) {
            return Direction.SOUTH;
        }
        if (z == maxZ) {
            return Direction.NORTH;
        }
        if (x == minX) {
            return Direction.WEST;
        }
        if (x == maxX) {
            return Direction.EAST;
        }
        return Direction.SOUTH;
    }

    public static boolean isCorniceCandidateBlock(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return false;
        }
        String lower = blockId.toLowerCase(Locale.ROOT);
        if (lower.contains("glass") || lower.contains("air") || lower.contains("iron_bars")) {
            return false;
        }
        if (lower.contains("door") || lower.contains("gate")) {
            return false;
        }
        return !lower.contains("stairs") && !lower.contains("slab");
    }

    public static String corniceStairBlock(String trimOrWallBlockId, Direction outward) {
        String stairBase = inferStairsBlock(trimOrWallBlockId);
        String facing = outward != null ? outward.name().toLowerCase(Locale.ROOT) : "south";
        return stairBase + "[facing=" + facing + ",half=top]";
    }

    public static String inferStairsBlock(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return "minecraft:stone_brick_stairs";
        }
        String base = blockId.trim();
        int bracket = base.indexOf('[');
        if (bracket > 0) {
            base = base.substring(0, bracket);
        }
        if (base.endsWith("_stairs")) {
            return base;
        }
        if (!base.startsWith("minecraft:")) {
            return "minecraft:stone_brick_stairs";
        }
        String name = base.substring("minecraft:".length());
        if (name.endsWith("_slab")) {
            name = name.substring(0, name.length() - "_slab".length()) + "_stairs";
        } else if (name.endsWith("_planks")) {
            name = name.substring(0, name.length() - "_planks".length()) + "_stairs";
        } else if (name.equals("smooth_sandstone")) {
            name = "sandstone_stairs";
        } else if (name.equals("cut_sandstone") || name.equals("chiseled_sandstone")) {
            name = "sandstone_stairs";
        } else if (name.equals("smooth_quartz") || name.equals("quartz_block")) {
            name = "quartz_stairs";
        } else if (name.equals("deepslate_tiles") || name.equals("deepslate_bricks")) {
            name = "deepslate_tile_stairs";
        } else if (name.endsWith("_bricks")) {
            String prefix = name.substring(0, name.length() - "_bricks".length());
            name = "quartz".equals(prefix) ? "quartz_stairs" : prefix + "_brick_stairs";
        } else if (name.equals("bricks")) {
            name = "brick_stairs";
        } else if (name.endsWith("_tiles")) {
            name = name.substring(0, name.length() - "_tiles".length()) + "_tile_stairs";
        } else if (!name.endsWith("_stairs")) {
            name = name + "_stairs";
        }
        return "minecraft:" + name;
    }

    private static int readFloorHeightFromPlan(LlmPlan plan) {
        if (plan.proportionHints() != null) {
            Object hint = plan.proportionHints().get("floor_height");
            if (hint == null) {
                hint = plan.proportionHints().get("floorHeight");
            }
            if (hint instanceof Number n && n.intValue() > 0) {
                return n.intValue();
            }
        }
        if (plan.components() == null) {
            return 0;
        }
        for (Component c : plan.components()) {
            if (c == null || c.params() == null) {
                continue;
            }
            int fh = ComponentParamParsers.intParam(c.params(), 0, "floor_height", "floorHeight");
            if (fh > 0) {
                return fh;
            }
        }
        return 0;
    }

    private static boolean isEnabled(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v == null) {
            return false;
        }
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        return "true".equals(s) || "yes".equals(s) || "on".equals(s);
    }

    private static boolean isDisabled(Object v) {
        if (v instanceof Boolean b) {
            return !b;
        }
        if (v == null) {
            return false;
        }
        return "false".equalsIgnoreCase(String.valueOf(v).trim());
    }

    private static String getParamString(Map<String, Object> params, String... keys) {
        if (params == null) {
            return null;
        }
        for (String key : keys) {
            Object v = params.get(key);
            if (v == null) {
                continue;
            }
            String s = String.valueOf(v).trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return null;
    }
}
