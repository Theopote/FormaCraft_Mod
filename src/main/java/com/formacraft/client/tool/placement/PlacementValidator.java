package com.formacraft.client.tool.placement;

import com.formacraft.common.logging.FcaLog;
import com.formacraft.common.component.placement.AttachmentType;
import com.formacraft.common.component.placement.ComponentPlacementSpec;
import com.formacraft.common.component.placement.PlacementConstraints;
import com.formacraft.common.component.placement.SpatialContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

/**
 * PlacementValidator（v1）：ComponentPlacementSpec 驱动的合法性检测。
 */
public final class PlacementValidator {
    private PlacementValidator() {}

    private static final FcaLog LOG = FcaLog.of("PlacementValidator");

    public static PlacementResult validate(ComponentPlacementSpec spec, PlacementContext ctx, MinecraftClient client) {
        if (spec == null || ctx == null) return PlacementResult.valid();

        // 1) Attachment 匹配
        AttachmentType at = (spec.attachment != null) ? spec.attachment : AttachmentType.NONE;
        if (!attachmentMatches(at, ctx)) {
            return PlacementResult.invalid("Attachment mismatch: need " + at);
        }

        // 1.1) 特殊：WALL_OPENING（门/窗洞口）—— v1 规则：必须在 footprint 外墙边界上，且尽量避开角点
        if (at == AttachmentType.WALL_OPENING) {
            if (!ctx.isWall) {
                return PlacementResult.invalid("Opening requires wall face");
            }
            try {
                if (OutlineFootprintIndex.hasShape()) {
                    if (!OutlineFootprintIndex.isNearEdge(ctx.targetPos)) {
                        return PlacementResult.invalid("Opening must be on footprint edge");
                    }
                    if (OutlineFootprintIndex.isNearCorner(ctx.targetPos)) {
                        return PlacementResult.invalid("Avoid corners for openings");
                    }
                }
            } catch (Throwable t) {
                LOG.debug("footprint edge check failed pos={}", ctx.targetPos, t);
            }
        }

        // 2) 室内/室外
        SpatialContext sc = (spec.spatialContext != null) ? spec.spatialContext : SpatialContext.ANY;
        if (sc == SpatialContext.EXTERIOR && ctx.isInterior) {
            return PlacementResult.invalid("Must be exterior");
        }
        if (sc == SpatialContext.INTERIOR && ctx.isExterior) {
            return PlacementResult.invalid("Must be interior");
        }

        // 3) 约束判断
        PlacementConstraints c = spec.constraints;
        if (c != null) {
            if (c.requiresEdge && !ctx.isEdge) {
                return PlacementResult.invalid("Requires edge");
            }
            if (c.forbidInterior && ctx.isInterior) {
                return PlacementResult.invalid("Forbidden interior");
            }
            if (c.requiresSupportBelow) {
                if (!hasSupportBelow(client, ctx.targetPos)) {
                    return PlacementResult.warn("Needs support below");
                }
            }
        }

        return PlacementResult.valid();
    }

    private static boolean attachmentMatches(AttachmentType type, PlacementContext ctx) {
        if (type == null) return true;
        return switch (type) {
            case FLOOR -> ctx.isFloor;
            case WALL_SURFACE, WALL_OPENING -> ctx.isWall;
            case ROOF_SURFACE, ROOF_EDGE, ROOF_RIDGE -> ctx.isRoof || ctx.isFloor; // v1：简化，把 UP 也允许作为 roof surface
            case EDGE -> ctx.isEdge;
            case CORNER -> ctx.isCorner;
            case NONE -> true;
        };
    }

    private static boolean hasSupportBelow(MinecraftClient client, BlockPos pos) {
        if (client == null || client.world == null || pos == null) return false;
        try {
            return !client.world.getBlockState(pos.down()).isAir();
        } catch (Throwable t) {
            LOG.debug("hasSupportBelow failed pos={}", pos, t);
            return false;
        }
    }
}

