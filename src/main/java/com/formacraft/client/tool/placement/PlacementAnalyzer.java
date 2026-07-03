package com.formacraft.client.tool.placement;

import com.formacraft.common.logging.FcaLog;
import com.formacraft.common.placement.PlacementContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * PlacementAnalyzer：几何 + 语义分析（v1 简化版）。
 */
public final class PlacementAnalyzer {
    private PlacementAnalyzer() {}

    private static final FcaLog LOG = FcaLog.of("PlacementAnalyzer");

    public static PlacementContext analyze(MinecraftClient client, BlockPos hitPos, Direction hitFace) {
        PlacementContext ctx = new PlacementContext(hitPos, hitFace);
        if (hitPos == null || hitFace == null) return ctx;

        // 1) 附着类型
        ctx.isWall = hitFace.getAxis().isHorizontal();
        ctx.isFloor = hitFace == Direction.UP;
        // v1：把 DOWN 视为“天花/檐下/屋面 underside”
        ctx.isRoof = hitFace == Direction.DOWN;

        // 2) 内/外
        // - 优先：基于 Footprint Outline（可区分墙的内侧/外侧，适合阳台/雨棚）
        // - fallback：天空可见性（简化）
        boolean hasFootprint = false;
        try {
            hasFootprint = OutlineFootprintIndex.hasShape();
        } catch (Throwable t) {
            LOG.debug("OutlineFootprintIndex.hasShape failed", t);
        }

        if (hasFootprint && ctx.isWall) {
            Boolean exterior = OutlineFootprintIndex.isWallFaceExterior(hitPos, hitFace);
            if (exterior != null) {
                ctx.isExterior = exterior;
                ctx.isInterior = !exterior;
            } else {
                ctx.isExterior = isOpenToSky(client, hitPos);
                ctx.isInterior = !ctx.isExterior;
            }
        } else if (hasFootprint) {
            // 非墙：用 footprint 的内外作为一个稳定语义（floor/furniture 更符合预期）
            boolean inside = OutlineFootprintIndex.contains(hitPos);
            ctx.isInterior = inside;
            ctx.isExterior = !inside;
        } else {
            ctx.isExterior = isOpenToSky(client, hitPos);
            ctx.isInterior = !ctx.isExterior;
        }

        // 3) 外法线
        if (ctx.isWall) {
            Direction out = null;
            try {
                out = OutlineFootprintIndex.inferWallOutwardNormal(hitPos, hitFace);
            } catch (Throwable t) {
                LOG.debug("inferWallOutwardNormal failed pos={} face={}", hitPos, hitFace, t);
            }
            ctx.outwardNormal = (out != null) ? out : hitFace;
        }

        // 4) 边缘检测 + 切线
        ctx.isEdge = EdgeDetector.isEdge(client, hitPos);
        ctx.isCorner = EdgeDetector.isCorner(client, hitPos);
        if (ctx.isEdge) {
            ctx.edgeDirection = EdgeDetector.getEdgeDirection(client, hitPos);
        }

        return ctx;
    }

    private static boolean isOpenToSky(MinecraftClient client, BlockPos pos) {
        if (client == null || client.world == null || pos == null) return false;
        try {
            return client.world.isSkyVisible(pos.up());
        } catch (Throwable t) {
            LOG.debug("isSkyVisible failed pos={}", pos, t);
            return false;
        }
    }
}

