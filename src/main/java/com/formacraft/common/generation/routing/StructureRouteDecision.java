package com.formacraft.common.generation.routing;

/** Result of structure routing before instantiating a {@code StructureGenerator}. */
public record StructureRouteDecision(
        GenerationCapability capability,
        String generatorRegistryKey,
        String reasonCode,
        String legacyFallbackRegistryKey
) {
    public boolean hasGeneratorKey() {
        return generatorRegistryKey != null && !generatorRegistryKey.isBlank();
    }
}
