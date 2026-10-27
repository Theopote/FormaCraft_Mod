package com.formacraft.server.edit;

/** Server-authorized incremental edit kinds sharing one building Revision. */
public enum BuildingEditOperation {
    REPLACE_ROOF_MATERIAL,
    INSERT_WINDOW,
    SEAL_WINDOW;

    public boolean isWindowOp() {
        return this == INSERT_WINDOW || this == SEAL_WINDOW;
    }
}
