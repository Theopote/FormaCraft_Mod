package com.formacraft.common.llm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Slot(
        @JsonProperty("slot_id") String slotId,
        @JsonProperty("anchor") Vec3i anchor,
        @JsonProperty("facing") GlobalConstraints.Facing facing,
        @JsonProperty("program") String program,
        @JsonProperty("component_preset_id") String componentPresetId,
        @JsonProperty("component_preset") String componentPreset
) {}

