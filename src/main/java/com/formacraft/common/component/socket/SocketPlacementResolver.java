package com.formacraft.common.component.socket;

import com.formacraft.common.component.ComponentDefinition;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Locale;

/**
 * 解析构件插槽在局部坐标系中的原点与朝向。
 * <p>
 * 优先级：{@link ComponentDefinition#socketPlacements} → 几何启发式 → ORIGIN。
 */
public final class SocketPlacementResolver {
    private SocketPlacementResolver() {}

    public static BlockPos resolveLocalOrigin(ComponentDefinition def, String socketId, ComponentSocket socket) {
        if (def != null && def.socketPlacements != null && socketId != null) {
            for (ComponentDefinition.SocketPlacement sp : def.socketPlacements) {
                if (sp == null || sp.id == null) continue;
                if (socketId.equals(sp.id)) {
                    return new BlockPos(sp.dx, sp.dy, sp.dz);
                }
            }
        }
        return inferLocalOrigin(def, socket);
    }

    public static Direction resolveLocalFacing(ComponentDefinition def, String socketId, ComponentSocket socket) {
        if (def != null && def.socketPlacements != null && socketId != null) {
            for (ComponentDefinition.SocketPlacement sp : def.socketPlacements) {
                if (sp == null || sp.id == null) continue;
                if (socketId.equals(sp.id) && sp.facing != null && !sp.facing.isBlank()) {
                    try {
                        return Direction.valueOf(sp.facing.trim().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }
        if (socket != null && socket.facingPolicy != null) {
            return switch (socket.facingPolicy) {
                case IN_OUT, AXIS -> parseAnchorFacing(def);
                default -> Direction.SOUTH;
            };
        }
        return parseAnchorFacing(def);
    }

    private static BlockPos inferLocalOrigin(ComponentDefinition def, ComponentSocket socket) {
        if (def == null || def.blocks == null || def.blocks.isEmpty()) {
            return BlockPos.ORIGIN;
        }

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (ComponentDefinition.BlockEntry be : def.blocks) {
            if (be == null) continue;
            minX = Math.min(minX, be.dx);
            minY = Math.min(minY, be.dy);
            minZ = Math.min(minZ, be.dz);
            maxX = Math.max(maxX, be.dx);
            maxY = Math.max(maxY, be.dy);
            maxZ = Math.max(maxZ, be.dz);
        }
        if (minX == Integer.MAX_VALUE) {
            return BlockPos.ORIGIN;
        }

        int cx = (minX + maxX) / 2;
        int cy = minY;
        int cz = (minZ + maxZ) / 2;

        Direction facing = parseAnchorFacing(def);
        cz = faceCoord(minZ, maxZ, facing);
        cx = centerOpeningAxis(minX, maxX, socket, 0);

        if (socket != null && socket.size != null && socket.size.min != null && socket.size.min.length >= 2) {
            int openH = Math.max(1, socket.size.min[1]);
            cy = minY + Math.max(0, (maxY - minY + 1 - openH) / 2);
        }

        return new BlockPos(cx, cy, cz);
    }

    private static int faceCoord(int min, int max, Direction facing) {
        if (facing == null) return max;
        return switch (facing) {
            case NORTH -> min;
            case SOUTH -> max;
            case WEST -> min;
            case EAST -> max;
            default -> max;
        };
    }

    private static int centerOpeningAxis(int min, int max, ComponentSocket socket, int axisIndex) {
        int span = max - min + 1;
        if (socket == null || socket.size == null || socket.size.min == null
                || socket.size.min.length <= axisIndex) {
            return (min + max) / 2;
        }
        int open = Math.max(1, socket.size.min[axisIndex]);
        return min + Math.max(0, (span - open) / 2);
    }

    private static Direction parseAnchorFacing(ComponentDefinition def) {
        if (def == null || def.anchor == null || def.anchor.facing == null || def.anchor.facing.isBlank()) {
            return Direction.SOUTH;
        }
        try {
            return Direction.valueOf(def.anchor.facing.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Direction.SOUTH;
        }
    }
}
