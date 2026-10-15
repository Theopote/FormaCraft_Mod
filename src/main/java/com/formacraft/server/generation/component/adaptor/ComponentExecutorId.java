package com.formacraft.server.generation.component.adaptor;

/** Stable executor identifiers for component generation (resolve → execute). */
public enum ComponentExecutorId {
    NONE,
    STAIR_COMPONENT,
    SKELETON,
    ASSEMBLY,
    /** Registry, library expanders, typology, structure adaptor (existing pipeline). */
    COMPONENT_PIPELINE
}
