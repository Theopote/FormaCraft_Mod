package com.formacraft.common.generation.routing;

/**
 * Per-authority fallback rules (separate from {@link GenerationAuthority} identity).
 */
public record GenerationFallbackPolicy(
        boolean allowTypologyReplaceNonEmptyBase,
        boolean allowTypologyWhenBaseEmpty,
        boolean allowStructureWhenBaseEmpty,
        boolean allowRegistryPriming
) {
    public static GenerationFallbackPolicy forAuthority(GenerationAuthority authority) {
        if (authority == null) {
            authority = GenerationAuthority.COMPONENTS;
        }
        return switch (authority) {
            case COMPONENTS -> new GenerationFallbackPolicy(false, true, true, true);
            case TYPOLOGY -> new GenerationFallbackPolicy(true, true, false, true);
            case CAPTURED_LIBRARY -> new GenerationFallbackPolicy(false, false, false, false);
            case ASSEMBLY, SKELETON -> new GenerationFallbackPolicy(false, false, false, false);
        };
    }

    public boolean allowsTypologyReplace(boolean allowTypologyOverride) {
        if (allowTypologyOverride) {
            return true;
        }
        return allowTypologyReplaceNonEmptyBase;
    }
}
