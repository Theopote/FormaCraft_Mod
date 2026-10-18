package com.formacraft.common.world;

import com.formacraft.common.buildcontext.OutlineShape;
import com.formacraft.common.model.constraint.ProtectedZone;
import com.formacraft.common.tool.OutlineGeometry;
import net.minecraft.util.math.BlockPos;
import java.util.List;

/** Shared hard region constraints for Patch and Build. */
public final class MutationRegionPolicy {
    private MutationRegionPolicy() {}
    public static boolean allow(BlockPos pos, BlockPos min, BlockPos max,
                                OutlineShape outline, List<ProtectedZone> zones) {
        if (pos == null) return false;
        if (zones != null) for (ProtectedZone zone : zones) {
            if (zone != null && zone.contains(pos)) return false;
        }
        if (min != null && max != null
                && (pos.getX() < min.getX() || pos.getX() > max.getX()
                || pos.getY() < min.getY() || pos.getY() > max.getY()
                || pos.getZ() < min.getZ() || pos.getZ() > max.getZ())) return false;
        return outline == null || OutlineGeometry.contains(outline, pos);
    }
}
