package com.formacraft.common.alignment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BaySpec(
        @JsonProperty("width") int width,
        @JsonProperty("role") String role
) {
    public BaySpec {
        width = Math.max(0, width);
    }
}
