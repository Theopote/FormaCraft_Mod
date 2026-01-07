package com.formacraft.common.llm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Dimensions(
        @JsonProperty("width") int width,
        @JsonProperty("depth") int depth,
        @JsonProperty("height") int height
) {}

