package com.formacraft.server.generation.component.adaptor;

/** Ordered stages inside {@link ComponentExecutorId#COMPONENT_PIPELINE}. */
public enum ComponentPipelineStepId {
    LIBRARY_EXPANDER,
    /** World-aware PATH/ROAD A* emit under COMPONENTS authority (Phase 01.6). */
    PATH_SMART_EMIT,
    COMPONENT_REGISTRY,
    TYPOLOGY,
    STRUCTURE_ADAPTOR
}
