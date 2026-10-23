package com.formacraft.common.llm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Explicit failure when the plan requests geometry the engine cannot compile.
 * Surfaces to players and orchestrator instead of silent empty patches or HOUSE fallback.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CapabilityGap(
        @JsonProperty("code") String code,
        @JsonProperty("message") String message,
        @JsonProperty("path") String path,
        @JsonProperty("suggestions") List<String> suggestions,
        @JsonProperty("details") @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> details
) {
    public CapabilityGap(String code, String message, String path, List<String> suggestions) {
        this(code, message, path, suggestions, null);
    }
    public String summary() {
        if (message != null && !message.isBlank()) {
            return message;
        }
        return code != null ? code : "ASSEMBLY_CAPABILITY_GAP";
    }
}
