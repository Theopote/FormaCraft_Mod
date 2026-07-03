package com.formacraft.server.build;

import com.formacraft.common.buildcontext.OutlineShape;
import com.formacraft.common.model.constraint.ProtectedZone;
import com.formacraft.common.model.request.FormaRequest;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.function.Supplier;

/**
 * ThreadLocal constraints context for "generation-time clipping".
 * <p>
 * We intentionally keep this extremely small and only set it around preview-generation
 * calls (server thread). Interpreters can consult it to avoid emitting out-of-bounds blocks.
 */
public final class BuildConstraintContext {
    private BuildConstraintContext() {}

    private static final ThreadLocal<BuildConstraints> TL = new ThreadLocal<>();

    public static BuildConstraints current() {
        return TL.get();
    }

    public static boolean allow(BlockPos p) {
        BuildConstraints c = TL.get();
        return c == null || c.allow(p);
    }

    public static <T> T withRequest(FormaRequest req, Supplier<T> fn) {
        BuildConstraints prev = TL.get();
        try {
            TL.set(fromRequest(req));
            return fn.get();
        } finally {
            if (prev == null) TL.remove();
            else TL.set(prev);
        }
    }

    public static void withRequest(FormaRequest req, Runnable fn) {
        BuildConstraints prev = TL.get();
        try {
            TL.set(fromRequest(req));
            fn.run();
        } finally {
            if (prev == null) TL.remove();
            else TL.set(prev);
        }
    }

    private static BuildConstraints fromRequest(FormaRequest req) {
        if (req == null) return null;
        BlockPos a = req.getSelectionMin();
        BlockPos b = req.getSelectionMax();
        BlockPos selMin = null;
        BlockPos selMax = null;
        if (a != null && b != null) {
            selMin = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
            selMax = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        }

        // 笔刷 AABB（作为无 selection 时的区域约束）
        BlockPos brushMin = null;
        BlockPos brushMax = null;
        BlockPos ba = req.getBrushMin();
        BlockPos bb = req.getBrushMax();
        if (ba != null && bb != null) {
            brushMin = new BlockPos(Math.min(ba.getX(), bb.getX()), Math.min(ba.getY(), bb.getY()), Math.min(ba.getZ(), bb.getZ()));
            brushMax = new BlockPos(Math.max(ba.getX(), bb.getX()), Math.max(ba.getY(), bb.getY()), Math.max(ba.getZ(), bb.getZ()));
        }

        // 路径走廊
        List<BlockPos> pathNodes = req.getPathNodes();
        int pathRadius = req.getPathRadius() != null ? req.getPathRadius() : 0;

        OutlineShape outline = req.getOutline();
        List<ProtectedZone> zones = req.getProtectedZones() != null ? req.getProtectedZones() : List.of();
        return new BuildConstraints(selMin, selMax, outline, zones, brushMin, brushMax, pathNodes, pathRadius);
    }
}


