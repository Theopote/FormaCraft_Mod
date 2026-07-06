package com.formacraft.common.alignment;

import com.formacraft.FormacraftMod;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Applies {@link AlignmentAndSymmetry} to MASS components before satellite realignment.
 */
public final class AlignmentContractEnforcer {

    private AlignmentContractEnforcer() {}

    public static List<Component> apply(LlmPlan plan, List<Component> components) {
        AlignmentAndSymmetry contract = AlignmentContractParser.resolve(plan);
        if (contract == null || components == null || components.isEmpty()) {
            return components;
        }
        BayGridResolver.ResolvedAxisGrid gridX = BayGridResolver.resolve(contract.rhythmX());
        BayGridResolver.ResolvedAxisGrid gridZ = BayGridResolver.resolve(contract.rhythmZ());
        if (gridX.totalSpan() <= 0 && gridZ.totalSpan() <= 0
                && contract.centerAxisX() == null && contract.centerAxisZ() == null) {
            return components;
        }

        List<Component> out = new ArrayList<>(components.size());
        int adjusted = 0;
        for (Component component : components) {
            if (component == null || !isMassType(component.componentType())) {
                out.add(component);
                continue;
            }
            Component aligned = applyToMass(component, contract, gridX, gridZ);
            if (aligned != component) {
                adjusted++;
            }
            out.add(aligned);
        }
        if (adjusted > 0) {
            FormacraftMod.LOGGER.info("AlignmentContractEnforcer: applied global alignment contract to {} MASS component(s)", adjusted);
        }
        return out;
    }

    private static Component applyToMass(
            Component mass,
            AlignmentAndSymmetry contract,
            BayGridResolver.ResolvedAxisGrid gridX,
            BayGridResolver.ResolvedAxisGrid gridZ
    ) {
        Dimensions dims = mass.dimensions();
        if (dims == null) {
            return mass;
        }
        int width = dims.width();
        int depth = dims.depth();
        int height = dims.height();
        if (gridX.totalSpan() > 0) {
            width = gridX.totalSpan();
        }
        if (gridZ.totalSpan() > 0) {
            depth = gridZ.totalSpan();
        }

        Vec3i pos = mass.relativePosition() != null ? mass.relativePosition() : new Vec3i(0, 0, 0);
        int centerX = contract.centerAxisX() != null
                ? contract.centerAxisX()
                : pos.x();
        int centerZ = contract.centerAxisZ() != null
                ? contract.centerAxisZ()
                : pos.z();

        if (contract.isBilateralX() || contract.centerAxisX() != null) {
            centerX = contract.centerAxisX() != null ? contract.centerAxisX() : centerX;
        }
        if (contract.isBilateralZ() || contract.centerAxisZ() != null) {
            centerZ = contract.centerAxisZ() != null ? contract.centerAxisZ() : centerZ;
        }

        Vec3i centeredPos = new Vec3i(centerX, pos.y(), centerZ);
        Map<String, Object> params = new HashMap<>();
        if (mass.params() != null) {
            params.putAll(mass.params());
        }
        params.put("alignment_contract_applied", true);
        if (contract.symmetryType() != null) {
            params.put("alignment_symmetry_type", contract.symmetryType());
        }
        params.put("alignment_center_x", centerX);
        params.put("alignment_center_z", centerZ);
        if (gridX.totalSpan() > 0) {
            params.put("bay_grid_x", serializeGrid(gridX));
        }
        if (gridZ.totalSpan() > 0) {
            params.put("bay_grid_z", serializeGrid(gridZ));
        }

        return new Component(
                mass.componentType(),
                mass.slotId(),
                centeredPos,
                new Dimensions(width, depth, height),
                mass.features(),
                params
        );
    }

    private static Map<String, Object> serializeGrid(BayGridResolver.ResolvedAxisGrid grid) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total_span", grid.totalSpan());
        out.put("symmetric", BayGridResolver.isSymmetric(grid.bays()));
        List<Map<String, Object>> bays = new ArrayList<>();
        for (BayGridResolver.BaySpan bay : grid.bays()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("start", bay.start());
            row.put("width", bay.width());
            row.put("role", bay.role());
            bays.add(row);
        }
        out.put("bays", bays);
        return out;
    }

    private static boolean isMassType(String componentType) {
        if (componentType == null) {
            return false;
        }
        String type = componentType.trim().toUpperCase(Locale.ROOT);
        return "MASS_MAIN".equals(type) || "MASS_SECONDARY".equals(type) || "MAIN_MASS".equals(type);
    }
}
