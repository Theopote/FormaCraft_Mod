package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.OpenFramePolicy;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Water / garden pavilions often arrive as stilts + enclosed MASS_MAIN (a short house on posts).
 * Force an open frame and extend stilts up to the deck so the body reads as a pavilion.
 */
final class PavilionOpenFrameRepair {
    private PavilionOpenFrameRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) return plan;
        Set<String> stiltHosts = new HashSet<>();
        for (Component c : plan.components()) {
            if (c == null || c.params() == null) continue;
            if (!"FOUNDATION".equalsIgnoreCase(c.componentType())) continue;
            if (!isStilts(c.params())) continue;
            Object host = c.params().get("host_id");
            if (host == null) host = c.params().get("hostId");
            if (host != null) stiltHosts.add(String.valueOf(host));
        }
        List<Component> out = new ArrayList<>(plan.components().size());
        boolean changed = false;
        Map<String, Integer> bodyFloorY = new HashMap<>();
        for (Component c : plan.components()) {
            Component repaired = repairMass(c, stiltHosts);
            if (repaired != c) changed = true;
            out.add(repaired);
            if (repaired != null && repaired.params() != null && repaired.relativePosition() != null
                    && "MASS_MAIN".equalsIgnoreCase(repaired.componentType())) {
                Object id = repaired.params().get("component_id");
                if (id != null) bodyFloorY.put(String.valueOf(id), repaired.relativePosition().y());
            }
        }
        for (int i = 0; i < out.size(); i++) {
            Component c = out.get(i);
            Component stilts = extendStilts(c, bodyFloorY);
            if (stilts != c) {
                out.set(i, stilts);
                changed = true;
            }
        }
        return changed ? PlanComponentRewrite.withComponents(plan, out) : plan;
    }

    private static Component repairMass(Component mass, Set<String> stiltHosts) {
        if (mass == null || mass.params() == null || mass.dimensions() == null) return mass;
        if (!"MASS_MAIN".equalsIgnoreCase(mass.componentType())
                && !"MAIN_MASS".equalsIgnoreCase(mass.componentType())) {
            return mass;
        }
        if (OpenFramePolicy.isOpen(mass.params())) return mass;
        String id = mass.params().get("component_id") == null ? ""
                : String.valueOf(mass.params().get("component_id")).toLowerCase(Locale.ROOT);
        boolean pavilionNamed = id.contains("pavilion") || id.contains("gazebo") || id.contains("kiosk")
                || id.contains("ting") || looksPavilion(mass.features());
        boolean onStilts = stiltHosts.contains(String.valueOf(mass.params().get("component_id")));
        if (!pavilionNamed && !onStilts) return mass;
        if (!pavilionNamed && onStilts && mass.dimensions().height() <= 3) return mass;
        Map<String, Object> params = new HashMap<>(mass.params());
        params.put("open", true);
        params.put("wall_type", "none");
        params.put("wall_style", "none");
        params.put("suppress_windows", true);
        params.put("window_style", "none");
        // Keep a low open bay rather than a multi-storey enclosed box on stilts.
        Dimensions dims = mass.dimensions();
        int height = dims.height();
        if (onStilts && height > 4) {
            height = 4;
        }
        FormacraftMod.LOGGER.info(
                "PavilionOpenFrameRepair: open-frame mass id={} height {}→{} (stilts={})",
                id, dims.height(), height, onStilts);
        return new Component(mass.componentType(), mass.slotId(), mass.relativePosition(),
                new Dimensions(dims.width(), dims.depth(), height), mass.features(), params);
    }

    private static Component extendStilts(Component foundation, Map<String, Integer> bodyFloorY) {
        if (foundation == null || foundation.params() == null || foundation.relativePosition() == null
                || foundation.dimensions() == null) {
            return foundation;
        }
        if (!"FOUNDATION".equalsIgnoreCase(foundation.componentType()) || !isStilts(foundation.params())) {
            return foundation;
        }
        Object host = foundation.params().get("host_id");
        if (host == null) host = foundation.params().get("hostId");
        Integer floorY = host == null ? null : bodyFloorY.get(String.valueOf(host));
        if (floorY == null) return foundation;
        Vec3i rp = foundation.relativePosition();
        int top = rp.y() + foundation.dimensions().height();
        if (top >= floorY) return foundation;
        int height = floorY - rp.y();
        FormacraftMod.LOGGER.info(
                "PavilionOpenFrameRepair: extend stilts host={} height {}→{}",
                host, foundation.dimensions().height(), height);
        return new Component(foundation.componentType(), foundation.slotId(), rp,
                new Dimensions(foundation.dimensions().width(), foundation.dimensions().depth(), height),
                foundation.features(), foundation.params());
    }

    private static boolean isStilts(Map<String, Object> params) {
        return Set.of("stilt", "stilts", "piles", "piling")
                .contains(String.valueOf(params.get("foundation_type")).toLowerCase(Locale.ROOT));
    }

    private static boolean looksPavilion(List<String> features) {
        if (features == null) return false;
        for (String f : features) {
            if (f == null) continue;
            String lower = f.toLowerCase(Locale.ROOT);
            if (lower.contains("pavilion") || lower.contains("gazebo") || lower.contains("open bay")
                    || lower.contains("open-frame") || lower.contains("colonnade frame")
                    || lower.contains("凉亭") || lower.contains("水上亭") || lower.contains("亭子")) {
                return true;
            }
        }
        return false;
    }
}
