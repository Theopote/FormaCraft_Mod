package com.formacraft.common.palette.dynamic;

import com.formacraft.common.llm.dto.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Shared stair-component recognition for material fidelity and the straight-stair executor.
 * Classification: FORMAT_REPAIR / HARD_CONSTRAINT identity — not a design router.
 */
public final class StairComponentIdentity {
    private static final Set<String> DIRECT_TYPES = Set.of("STAIR_SYSTEM", "STAIRCASE", "STAIRS");

    private StairComponentIdentity() {}

    /** True when the component is executed as a straight stair / staircase. */
    public static boolean isStairComponent(Component c) {
        if (c == null || c.componentType() == null) return false;
        String type = c.componentType().trim().toUpperCase(Locale.ROOT);
        if (DIRECT_TYPES.contains(type)) return true;
        if (!"STRUCTURE".equals(type)) return false;
        if (c.features() != null
                && c.features().stream().filter(Objects::nonNull).anyMatch(f -> f.startsWith("stair:"))) {
            return true;
        }
        // Same contract as StraightStairComponentGenerator.accepts: from/to + *_stairs id.
        if (c.params() == null) return false;
        if (!(c.params().get("from") instanceof Map<?, ?>) || !(c.params().get("to") instanceof Map<?, ?>)) {
            return false;
        }
        if (c.features() != null && c.features().stream().anyMatch(f -> f != null
                && (f.startsWith("typology:") || f.startsWith("landmark:") || f.startsWith("module:")))) {
            return false;
        }
        return c.params().get("stairs") instanceof String stairs
                && stairs.split("\\[", 2)[0].trim().endsWith("_stairs");
    }
}
