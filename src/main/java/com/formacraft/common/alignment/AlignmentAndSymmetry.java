package com.formacraft.common.alignment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Global facade/grid contract declared before components are placed.
 * Components are expected to fill bays defined here rather than inventing free-form spans.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlignmentAndSymmetry(
        @JsonProperty("symmetry_type") String symmetryType,
        @JsonProperty("center_axis_x") Integer centerAxisX,
        @JsonProperty("center_axis_z") Integer centerAxisZ,
        @JsonProperty("rhythm_x") BayRhythm rhythmX,
        @JsonProperty("rhythm_z") BayRhythm rhythmZ
) {
    public boolean hasContent() {
        return (symmetryType != null && !symmetryType.isBlank())
                || centerAxisX != null
                || centerAxisZ != null
                || (rhythmX != null && rhythmX.hasContent())
                || (rhythmZ != null && rhythmZ.hasContent());
    }

    public boolean isBilateralX() {
        return symmetryType != null && symmetryType.toLowerCase(java.util.Locale.ROOT).contains("bilateral_x");
    }

    public boolean isBilateralZ() {
        return symmetryType != null && symmetryType.toLowerCase(java.util.Locale.ROOT).contains("bilateral_z");
    }
}
