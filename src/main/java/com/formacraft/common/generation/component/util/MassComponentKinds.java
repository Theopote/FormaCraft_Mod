package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Component;

import java.util.Locale;
import java.util.Set;

/** Shared host-mass / circulation-mass classification (avoid duplicated type lists). */
public final class MassComponentKinds {
    private static final Set<String> HOST_MASS_TYPES = Set.of(
            "MASS_MAIN", "MAIN_MASS", "MASS_SECONDARY", "MASS_WING", "SIDE_WING", "TOWER");

    private MassComponentKinds() {}

    public static boolean isHostMass(String type) {
        if (type == null || type.isBlank()) {
            return false;
        }
        return HOST_MASS_TYPES.contains(type.trim().toUpperCase(Locale.ROOT));
    }

    public static boolean isHostMass(Component component) {
        return component != null && isHostMass(component.componentType());
    }

    public static boolean isPlate(Component component) {
        if (component == null || component.params() == null) {
            return false;
        }
        return "plate".equalsIgnoreCase(String.valueOf(component.params().get("extrude_mode")));
    }

    /** Host mass shell that can participate in circulation (not a hosted plate). */
    public static boolean isCirculationMass(Component component) {
        return isHostMass(component) && !isPlate(component) && component.dimensions() != null;
    }

    /** Circulation mass with at least two design storeys. */
    public static boolean isStoreyedCirculationMass(Component component) {
        if (!isCirculationMass(component)) {
            return false;
        }
        int envelope = Math.max(1, component.dimensions().height());
        ResolvedFloorLayout layout = ResolvedFloorLayout.from(component, envelope);
        return layout.floorCount() >= 2 && layout.floorYs().size() >= 2;
    }
}
