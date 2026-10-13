package com.formacraft.common.generation.component.util;

/**
 * One roof tier's world origin and mass-local footprint offset.
 * <p>
 * Loop indices on a tier are relative to {@code worldBase*}. Mass-local footprint
 * queries use {@code loopX - overhang + originOffsetX} (and same for Z) so an
 * inset upper eave still tests against the host footprint.
 */
public record RoofLayerFrame(
        int worldBaseX,
        int worldBaseZ,
        int originOffsetX,
        int originOffsetZ,
        int width,
        int depth,
        int overhang
) {
    public static RoofLayerFrame of(int worldBaseX, int worldBaseZ, int width, int depth, int overhang) {
        return new RoofLayerFrame(worldBaseX, worldBaseZ, 0, 0, width, depth, overhang);
    }

    public static RoofLayerFrame inset(RoofLayerFrame outer, int inset, int upperWidth, int upperDepth) {
        return new RoofLayerFrame(
                outer.worldBaseX() + inset,
                outer.worldBaseZ() + inset,
                outer.originOffsetX() + inset,
                outer.originOffsetZ() + inset,
                upperWidth,
                upperDepth,
                outer.overhang()
        );
    }

    /** Mass-local X for footprint checks from a loop index on this tier. */
    public int footprintLocalX(int loopX) {
        return loopX - overhang + originOffsetX;
    }

    /** Mass-local Z for footprint checks from a loop index on this tier. */
    public int footprintLocalZ(int loopZ) {
        return loopZ - overhang + originOffsetZ;
    }
}
