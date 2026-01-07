package com.formacraft.common.llm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PatchBlock(
        @JsonProperty("action") String action,
        @JsonProperty("dx") int dx,
        @JsonProperty("dy") int dy,
        @JsonProperty("dz") int dz,
        @JsonProperty("targetBlock") String targetBlock
) {}

