package com.formacraft.common.llm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GlobalConstraints(
        @JsonProperty("facing") Facing facing,
        @JsonProperty("symmetry") Symmetry symmetry,
        @JsonProperty("terrain_strategy") TerrainStrategy terrainStrategy
) {
    public enum Facing { NORTH, SOUTH, EAST, WEST }
    public enum Symmetry { NONE, MIRROR_X, MIRROR_Z, RADIAL }
    public enum TerrainStrategy { FOLLOW, PAD_PER_BUILDING, TERRACE, FLATTEN_ALL }
}

