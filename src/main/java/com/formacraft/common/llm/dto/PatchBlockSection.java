package com.formacraft.common.llm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 可选：当 LLM 直接输出 block patch
 * {
 *   "patch": {
 *     "origin": {"x":..,"y":..,"z":..},
 *     "blocks": [{ "action":"place","dx":..,"dy":..,"dz":..,"targetBlock":"minecraft:stone" }]
 *   }
 * }
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PatchBlockSection(
        @JsonProperty("origin") Vec3i origin,
        @JsonProperty("blocks") List<PatchBlock> blocks
) {}

