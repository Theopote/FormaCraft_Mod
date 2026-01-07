package com.formacraft.common.llm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Layout(
        @JsonProperty("skeleton_type") SkeletonType skeletonType,
        @JsonProperty("path_based") boolean pathBased,
        @JsonProperty("slots") List<Slot> slots
) {
    public enum SkeletonType {
        LINEAR_PATH,
        RADIAL_RING,
        GRID,
        COMPOUND,
        PATH_POLYLINE,
        SPAN_SUSPENSION,
        VERTICAL_TAPER,
        VERTICAL_STACK
    }
}

