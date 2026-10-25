package com.formacraft.server.preview;

/**
 * Immutable classification stamped on {@link PreviewTicket} at issue time.
 * Building-aware edits must never degrade to unconstrained ordinary patches
 * when their Pending context is missing.
 */
public enum PreviewEditKind {
    /** Ordinary patch / typology preview — no BuildingEdit Pending required. */
    NONE,
    /** Phase-1 roof material replace — BuildingEdit Pending is mandatory at Confirm. */
    BUILDING_ROOF_MATERIAL;

    public boolean requiresBuildingEditPending() {
        return this == BUILDING_ROOF_MATERIAL;
    }
}
