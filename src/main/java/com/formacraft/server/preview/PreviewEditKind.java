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
    BUILDING_ROOF_MATERIAL,
    /** Phase-2 window insert on an authorized wall envelope. */
    BUILDING_WINDOW_INSERT,
    /** Phase-2 window seal on authorized window cells. */
    BUILDING_WINDOW_SEAL,
    /** FBD program replace — full footprint rebuild under stored source plan authority. */
    BUILDING_FBD_REPLACE;

    public boolean requiresBuildingEditPending() {
        return this != NONE;
    }

    public com.formacraft.server.edit.BuildingEditOperation toOperation() {
        return switch (this) {
            case BUILDING_ROOF_MATERIAL -> com.formacraft.server.edit.BuildingEditOperation.REPLACE_ROOF_MATERIAL;
            case BUILDING_WINDOW_INSERT -> com.formacraft.server.edit.BuildingEditOperation.INSERT_WINDOW;
            case BUILDING_WINDOW_SEAL -> com.formacraft.server.edit.BuildingEditOperation.SEAL_WINDOW;
            case BUILDING_FBD_REPLACE -> com.formacraft.server.edit.BuildingEditOperation.REPLACE_FBD_PROGRAM;
            case NONE -> null;
        };
    }

    public static PreviewEditKind of(com.formacraft.server.edit.BuildingEditOperation operation) {
        if (operation == null) return NONE;
        return switch (operation) {
            case REPLACE_ROOF_MATERIAL -> BUILDING_ROOF_MATERIAL;
            case INSERT_WINDOW -> BUILDING_WINDOW_INSERT;
            case SEAL_WINDOW -> BUILDING_WINDOW_SEAL;
            case REPLACE_FBD_PROGRAM -> BUILDING_FBD_REPLACE;
        };
    }
}
