package com.formacraft.common.world;

import com.formacraft.common.buildcontext.OutlineShape;
import com.formacraft.common.model.constraint.ProtectedZone;
import com.formacraft.common.tool.OutlineGeometry;
import net.minecraft.util.math.BlockPos;
import java.util.List;
import java.util.Set;

/** Shared hard region constraints for Patch and Build. */
public final class MutationRegionPolicy {
    private MutationRegionPolicy() {}

    /** The inventory is also the executed rule list; adding a kind requires an implementation. */
    public enum Constraint {
        PROTECTED_ZONES, BOUNDS, OUTLINE;

        private boolean allow(BlockPos pos, BlockPos min, BlockPos max,
                              OutlineShape outline, List<ProtectedZone> zones) {
            return switch (this) {
                case PROTECTED_ZONES -> {
                    if (zones != null) for (ProtectedZone zone : zones) {
                        if (zone != null && zone.contains(pos)) yield false;
                    }
                    yield true;
                }
                case BOUNDS -> min == null || max == null
                        || (pos.getX() >= min.getX() && pos.getX() <= max.getX()
                        && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                        && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ());
                case OUTLINE -> outline == null || OutlineGeometry.contains(outline, pos);
            };
        }
    }

    private static final Constraint[] CHECKS = Constraint.values();
    private static final Set<Constraint> KINDS = Set.of(CHECKS);

    public static Set<Constraint> supportedConstraints() { return KINDS; }

    public static boolean allow(BlockPos pos, BlockPos min, BlockPos max,
                                OutlineShape outline, List<ProtectedZone> zones) {
        if (pos == null) return false;
        for (Constraint constraint : CHECKS) {
            if (!constraint.allow(pos, min, max, outline, zones)) return false;
        }
        return true;
    }
}
