package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shifts compositional building parts so the primary MASS footprint center sits on plan origin (0,0).
 * Player cursor / request origin is the intended building center.
 */
final class FootprintCenterRepair {
    private FootprintCenterRepair() {}

    static List<Component> apply(List<Component> components) {
        if (components == null || components.isEmpty()) return components;
        Component main = null;
        for (Component c : components) {
            if (c == null || c.dimensions() == null || c.relativePosition() == null) continue;
            String type = ComponentPlanTypes.normalizeType(c.componentType());
            if ("MASS_MAIN".equals(type) || "STRUCTURE".equals(type)) {
                main = c;
                break;
            }
        }
        if (main == null) return components;
        var bounds = ComponentFootprintUtil.bounds(main);
        int centerX = (bounds.minX() + bounds.maxX()) / 2;
        int centerZ = (bounds.minZ() + bounds.maxZ()) / 2;
        if (centerX == 0 && centerZ == 0) return components;

        List<Component> out = new ArrayList<>(components.size());
        for (Component c : components) {
            if (c == null || c.relativePosition() == null) {
                out.add(c);
                continue;
            }
            Vec3i rp = c.relativePosition();
            Map<String, Object> params = c.params() == null ? null : new HashMap<>(c.params());
            if (params != null) params.put("anchor_mode", "min_corner");
            out.add(new Component(
                    c.componentType(),
                    c.slotId(),
                    new Vec3i(rp.x() - centerX, rp.y(), rp.z() - centerZ),
                    c.dimensions(),
                    c.features(),
                    params != null ? params : c.params()));
        }
        return out;
    }
}
