package com.formacraft.server.assembly.preset;

import java.util.List;
import java.util.Map;

public record AssemblyPresetDefinition(
        String id,
        String displayName,
        String description,
        List<String> matchKeywords,
        Map<String, Object> assemblyTemplate,
        Map<String, ParamSpec> parameters
) {
    public record ParamSpec(double defaultValue, double min, double max, String kind) {}
}
