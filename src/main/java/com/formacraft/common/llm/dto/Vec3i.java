package com.formacraft.common.llm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonDeserialize(using = Vec3iDeserializer.class)
public record Vec3i(
        @JsonProperty("x") int x,
        @JsonProperty("y") int y,
        @JsonProperty("z") int z
) {}

