package com.formacraft.common.generation.component.impl;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.ComponentGenerator;
import com.formacraft.common.generation.component.util.ComponentMaterialResolver;
import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.semantic.SemanticPart;

import java.util.ArrayList;
import java.util.List;

/**
 * TowerComponentGenerator（构件层塔楼生成器 v2）
 *
 * 圆形塔楼生成器（显式材质 → DynamicPalette / 风格色板）
 */
public class TowerComponentGenerator implements ComponentGenerator {

    @Override
    public List<BlockPatch> generate(SemanticComponent semantic) {
        List<BlockPatch> out = new ArrayList<>();

        Component c = semantic.source();
        if (c == null || c.dimensions() == null || c.relativePosition() == null) {
            return out;
        }

        Dimensions d = c.dimensions();
        Vec3i rp = c.relativePosition();

        // Fit inside the declared AABB: diameter = 2*r+1 <= min(width,depth).
        int footprint = Math.max(1, Math.min(d.width(), d.depth()));
        int radius = Math.max(1, (footprint - 1) / 2);
        int height = Math.max(1, d.height());
        if (com.formacraft.common.generation.component.util.ComponentFootprintUtil.isCornerAnchor(c.params())) {
            rp = new Vec3i(rp.x() + radius, rp.y(), rp.z() + radius);
        }

        boolean hasWindows = hasFeature(c, "windows", "window");
        boolean hasStairs = hasFeature(c, "stairs", "stair", "spiral_stairs");
        boolean hasInterior = hasFeature(c, "interior", "rooms", "floors")
                || (c.params() != null && Boolean.TRUE.equals(c.params().get("hollow")));
        int floors = ComponentParamParsers.intParam(c.params(), 1, "floor_count", "floorCount");
        int floorHeight = ComponentParamParsers.intParam(c.params(),
                Math.max(3, height / Math.max(1, floors)), "floor_height", "floorHeight");
        boolean storeyed = floors > 1 && (c.params() == null || !Boolean.FALSE.equals(c.params().get("hollow")));
        hasInterior |= storeyed;
        int wallThickness = ComponentParamParsers.intParam(c.params(), 2, "wall_thickness", "wallThickness");
        boolean hasBattlements = hasFeature(c, "battlements", "battlement", "crenelation");
        boolean hasRoof = hasFeature(c, "roof", "spire", "dome");

        String floorFallback = ComponentMaterialResolver.resolve(
                semantic, SemanticPart.FLOOR, "minecraft:stone_bricks", "floor_block", "floorBlock");
        String wallFallback = ComponentMaterialResolver.resolve(
                semantic, SemanticPart.WALL, "minecraft:stone_bricks", "wall_block", "wallBlock");
        String windowBlock = ComponentMaterialResolver.resolve(
                semantic, SemanticPart.WINDOW, "minecraft:glass", "glass_block", "window_block");
        String stairBlockBase = ComponentMaterialResolver.resolve(
                semantic, SemanticPart.STAIR_STEP, "minecraft:stone_brick_stairs", "stair_block", "stairs_block");
        String roofBlock = ComponentMaterialResolver.resolve(
                semantic, SemanticPart.ROOF_SURFACE, "minecraft:stone_bricks", "roof_block", "roofBlock");
        if (roofBlock.equals("minecraft:stone_bricks")) {
            roofBlock = ComponentMaterialResolver.resolve(
                    semantic, SemanticPart.ROOF, "minecraft:stone_bricks", "roof_block", "roofBlock");
        }
        String battlementBlock = ComponentMaterialResolver.resolve(
                semantic, SemanticPart.BATTLEMENT, wallFallback, "wall_block", "accent_block");
        if (battlementBlock.equals(wallFallback)) {
            battlementBlock = ComponentMaterialResolver.resolve(
                    semantic, SemanticPart.WALL_ACCENT, wallFallback, "wall_block", "accent_block");
        }

        for (int y = 0; y < height; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    int distSq = x * x + z * z;
                    if (distSq > radius * radius) continue;

                    if (hasWindows && isWindowPosition(x, z, radius, y, height)) {
                        out.add(place(rp, x, y, z, windowBlock));
                        continue;
                    }

                    if (hasStairs && isSpiralStairCell(x, z, y, radius, wallThickness) && y < height - 1) {
                        out.add(place(rp, x, y, z, withFacing(stairBlockBase, spiralFacing(y))));
                        continue;
                    }

                    // Roof / battlements before interior skip so hollow towers still get a sealed top.
                    if (hasBattlements && isBattlementPosition(x, z, radius, y, height)) {
                        out.add(place(rp, x, y, z, battlementBlock));
                        continue;
                    }

                    if (hasRoof && y >= height - 1) {
                        out.add(place(rp, x, y, z, roofBlock));
                        continue;
                    }

                    if (hasInterior && y > 0 && isInteriorSpace(x, z, radius, Math.max(1, wallThickness))) {
                        if (storeyed && floorHeight > 0 && y % floorHeight == 0 && y / floorHeight < floors
                                && !(hasStairs && isSpiralStairCell(x, z, y, radius, wallThickness))) {
                            out.add(place(rp, x, y, z, floorFallback));
                        }
                        continue;
                    }

                    SemanticPart part = determinePart(y, height, radius, x, z);
                    String block = part == SemanticPart.WALL_BASE
                            ? ComponentMaterialResolver.resolve(semantic, part, floorFallback, "floor_block", "wall_block")
                            : ComponentMaterialResolver.resolve(semantic, part, wallFallback, "wall_block");
                    out.add(place(rp, x, y, z, block));
                }
            }
        }

        return out;
    }

    private static BlockPatch place(Vec3i rp, int x, int y, int z, String block) {
        return new BlockPatch(BlockPatch.PLACE, rp.x() + x, rp.y() + y, rp.z() + z, block);
    }

    /** One continuous spiral tread per Y on the inner ring, with a floor hole. */
    private boolean isSpiralStairCell(int x, int z, int y, int radius, int wall) {
        int ring = Math.max(1, radius - Math.max(1, wall));
        int step = Math.floorMod(y, 8);
        double ang = step * Math.PI / 4.0;
        int sx = (int) Math.round(Math.cos(ang) * ring);
        int sz = (int) Math.round(Math.sin(ang) * ring);
        return x == sx && z == sz;
    }

    private static String spiralFacing(int y) {
        // Face toward the next tread around the spiral.
        return switch (Math.floorMod(y, 8)) {
            case 0, 1 -> "east";
            case 2, 3 -> "south";
            case 4, 5 -> "west";
            default -> "north";
        };
    }

    private static String withFacing(String blockId, String facing) {
        String base = blockId == null || blockId.isBlank() ? "minecraft:stone_brick_stairs" : blockId;
        int bracket = base.indexOf('[');
        if (bracket >= 0) base = base.substring(0, bracket);
        // Palette may resolve STAIR_STEP to a solid block — spiral treads must be stairs.
        if (!base.contains("stairs")) {
            base = "minecraft:stone_brick_stairs";
        }
        return base + "[facing=" + facing + "]";
    }

    private boolean isWindowPosition(int x, int z, int radius, int y, int height) {
        int distSq = x * x + z * z;
        boolean isNearEdge = (distSq >= (radius - 1) * (radius - 1));
        boolean isMiddleHeight = (y > 1 && y < height - 2);
        double angle = Math.atan2(z, x);
        int angleStep = (int) (angle / (Math.PI / 4));
        boolean isWindowAngle = (angleStep % 2 == 0);
        return isNearEdge && isMiddleHeight && isWindowAngle;
    }

    private boolean isInteriorSpace(int x, int z, int radius, int wall) {
        return (x - wall) * (x - wall) + z * z <= radius * radius
                && (x + wall) * (x + wall) + z * z <= radius * radius
                && x * x + (z - wall) * (z - wall) <= radius * radius
                && x * x + (z + wall) * (z + wall) <= radius * radius;
    }

    private boolean isBattlementPosition(int x, int z, int radius, int y, int height) {
        int distSq = x * x + z * z;
        boolean isTop = (y >= height - 2);
        boolean isEdge = (distSq >= (radius - 1) * (radius - 1));
        double angle = Math.atan2(z, x);
        int angleStep = (int) (angle / (Math.PI / 4));
        boolean isBattlementSpacing = (angleStep % 2 == 0);
        return isTop && isEdge && isBattlementSpacing;
    }

    private boolean hasFeature(Component c, String... keywords) {
        if (c.features() == null) return false;
        for (String feature : c.features()) {
            if (feature == null) continue;
            String lower = feature.toLowerCase();
            for (String keyword : keywords) {
                if (lower.contains(keyword.toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }

    private SemanticPart determinePart(int y, int height, int radius, int x, int z) {
        if (y == 0) {
            return SemanticPart.WALL_BASE;
        }
        if (y >= height - 2) {
            return SemanticPart.WALL_ACCENT;
        }
        int distSq = x * x + z * z;
        if (distSq >= (radius - 1) * (radius - 1)) {
            return SemanticPart.WALL_ACCENT;
        }
        return SemanticPart.WALL;
    }
}
