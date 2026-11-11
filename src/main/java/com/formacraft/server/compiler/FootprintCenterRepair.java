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
 * <p>
 * Skips when the plan already authors an absolute min-corner layout (approach pads outside the
 * mass, stilts under a raised floor, etc.) — recentering would orphan posts/doors/stairs.
 */
final class FootprintCenterRepair {
    private FootprintCenterRepair() {}

    static List<Component> apply(List<Component> components) {
        if (components == null || components.isEmpty()) return components;
        Component main = null;
        int primaryMasses = 0;
        for (Component c : components) {
            if (c == null || c.dimensions() == null || c.relativePosition() == null) continue;
            String type = ComponentPlanTypes.normalizeType(c.componentType());
            if ("MASS_MAIN".equals(type) || "MAIN_MASS".equals(type) || "STRUCTURE".equals(type)) {
                primaryMasses++;
                if (main == null) main = c;
            }
        }
        // Courtyard / wing compounds author absolute positions between multiple masses.
        if (primaryMasses > 1) return components;
        if (main == null) return components;
        // Prefer min-corner + half size so center matches player cursor even when Bounds max is exclusive.
        Vec3i origin = ComponentFootprintUtil.resolveMinCornerOrigin(main);
        if (origin == null) origin = main.relativePosition();
        int centerX = origin.x() + main.dimensions().width() / 2;
        int centerZ = origin.z() + main.dimensions().depth() / 2;
        if (centerX == 0 && centerZ == 0) return components;
        if (preservesAuthoredAbsoluteLayout(components, main, origin)) {
            return components;
        }

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

    /**
     * True when satellites are authored in the same min-corner frame as a mass already at (0,*,0):
     * outboard approach (negative x/z), stilts under a raised deck, or multi-post supports.
     * Also preserves plain min_corner masses at the origin — eval/fidelity fixtures assert
     * authored cells; recentering by −width/2 would shift every coordinate.
     */
    private static boolean preservesAuthoredAbsoluteLayout(
            List<Component> components, Component main, Vec3i massOrigin) {
        // Explicit min_corner is an absolute authored frame at any origin — recentering
        // orphaned stepped-roof / stilts fixtures that place the mass away from (0,0).
        if (isMinCornerAnchor(main)) {
            return true;
        }
        if (massOrigin == null || massOrigin.x() != 0 || massOrigin.z() != 0) {
            return false;
        }
        int massY = main.relativePosition() == null ? 0 : main.relativePosition().y();
        int supportPosts = 0;
        for (Component c : components) {
            if (c == null || c.relativePosition() == null || c == main) continue;
            String type = ComponentPlanTypes.normalizeType(c.componentType());
            Vec3i rp = c.relativePosition();
            if (rp.x() < 0 || rp.z() < 0) {
                return true;
            }
            String id = c.params() == null || c.params().get("component_id") == null
                    ? "" : String.valueOf(c.params().get("component_id")).toLowerCase();
            if (id.startsWith("post_") || id.startsWith("access_post") || id.contains("approach")
                    || id.contains("stilt") || id.startsWith("access_step")
                    || id.contains("trunk") || id.startsWith("tree_access")) {
                return true;
            }
            if (("PRIMITIVE".equals(type) || "FOUNDATION".equals(type) || "STRUCTURE".equals(type))
                    && massY > 0 && rp.y() < massY) {
                supportPosts++;
                int supportH = c.dimensions() == null ? 1 : Math.max(1, c.dimensions().height());
                // A single full-height trunk/pier under a raised deck is enough.
                if (supportPosts >= 2 || rp.y() + supportH >= massY) return true;
            }
        }
        return false;
    }

    private static boolean isMinCornerAnchor(Component c) {
        if (c == null || c.params() == null) return false;
        Object mode = c.params().get("anchor_mode");
        if (mode == null) mode = c.params().get("anchorMode");
        return mode != null && "min_corner".equalsIgnoreCase(String.valueOf(mode).trim());
    }
}
