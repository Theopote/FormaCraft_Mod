package com.formacraft.common.llm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Component(
        @JsonProperty("component_type") String componentType,
        @JsonProperty("slot_id") String slotId,
        @JsonProperty("relative_position") Vec3i relativePosition,
        @JsonProperty("dimensions") Dimensions dimensions,
        @JsonProperty("features") List<String> features
) {}

