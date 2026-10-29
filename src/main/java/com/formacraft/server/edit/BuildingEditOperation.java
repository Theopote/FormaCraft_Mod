package com.formacraft.server.edit;

/** Server-authorized incremental edit kinds sharing one building Revision. */
public enum BuildingEditOperation {
    REPLACE_ROOF_MATERIAL,
    INSERT_WINDOW,
    SEAL_WINDOW,
    /** Full footprint replace from a new FBD program (requires stored FBD_SOURCE_PLAN). */
    REPLACE_FBD_PROGRAM;

    public boolean isWindowOp() {
        return this == INSERT_WINDOW || this == SEAL_WINDOW;
    }

    public boolean isFbdReplace() {
        return this == REPLACE_FBD_PROGRAM;
    }
}
