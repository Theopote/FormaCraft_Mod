package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Nudge secondary / pavilion masses out of overlapping primary footprints so
 * compound gardens do not trip {@code E_PATCH_CONFLICT} at shared cells.
 */
final class MassFootprintSeparationRepair {
    private static final int GAP = 1;

    private MassFootprintSeparationRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().size() < 2) {
            return plan;
        }
        List<Component> out = new ArrayList<>(plan.components());
        boolean changed = false;
        for (int i = 0; i < out.size(); i++) {
            Component a = out.get(i);
            if (!isMovableMass(a)) continue;
            for (int j = 0; j < out.size(); j++) {
                if (i == j) continue;
                Component b = out.get(j);
                if (!isAnchorMass(b)) continue;
                if (!overlaps(a, b)) continue;
                // Prefer moving the pavilion / smaller body away from the hall / larger body.
                if (priority(a) >= priority(b)) continue;
                Component nudged = nudgeAway(a, b);
                if (nudged != a) {
                    FormacraftMod.LOGGER.info(
                            "MassFootprintSeparationRepair: move {} away from {} → {}",
                            idOf(a), idOf(b), nudged.relativePosition());
                    out.set(i, nudged);
                    a = nudged;
                    changed = true;
                }
            }
        }
        return changed ? PlanComponentRewrite.withComponents(plan, out) : plan;
    }

    private static boolean isMovableMass(Component c) {
        if (c == null || c.dimensions() == null || c.relativePosition() == null) return false;
        String t = c.componentType() == null ? "" : c.componentType().trim().toUpperCase(Locale.ROOT);
        if (!(t.startsWith("MASS") || "TOWER".equals(t) || "GATE".equals(t))) return false;
        if (c.params() != null) {
            // Bridge / hosted plates intentionally touch two hosts — never nudge them apart.
            if (c.params().containsKey("target_host_id") || c.params().containsKey("targetHostId")) {
                return false;
            }
            Object role = c.params().get("plate_role");
            if (role == null) role = c.params().get("plateRole");
            if (role != null && String.valueOf(role).toLowerCase(Locale.ROOT).contains("bridge")) {
                return false;
            }
            if ("plate".equalsIgnoreCase(String.valueOf(c.params().get("extrude_mode")))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAnchorMass(Component c) {
        return isMovableMass(c);
    }

    private static int priority(Component c) {
        String id = idOf(c).toLowerCase(Locale.ROOT);
        int score = volume(c);
        if (id.contains("pavilion") || id.contains("gazebo") || id.contains("ting")
                || id.contains("shuixie") || id.contains("kiosk") || id.contains("water")) {
            score -= 1_000_000;
        }
        if (id.contains("hall") || id.contains("main") || id.contains("殿") || id.contains("堂")) {
            score += 1_000_000;
        }
        Object role = c.params() == null ? null : c.params().get("building_role");
        if (role == null && c.params() != null) role = c.params().get("buildingRole");
        if (role != null) {
            String r = String.valueOf(role).toLowerCase(Locale.ROOT);
            if (r.contains("pavilion")) score -= 500_000;
            if (r.contains("hall") || r.contains("main") || r.contains("primary")) score += 500_000;
        }
        if (c.features() != null) {
            for (String f : c.features()) {
                if (f == null) continue;
                String lower = f.toLowerCase(Locale.ROOT);
                if (lower.contains("pavilion") || f.contains("亭") || lower.contains("水榭")) score -= 250_000;
                if (lower.contains("hall") || f.contains("殿") || lower.contains("main_hall")) score += 250_000;
            }
        }
        return score;
    }

    private static int volume(Component c) {
        Dimensions d = c.dimensions();
        return Math.max(1, d.width()) * Math.max(1, d.depth()) * Math.max(1, d.height());
    }

    private static String idOf(Component c) {
        if (c.params() == null) return c.componentType() == null ? "" : c.componentType();
        Object id = c.params().get("component_id");
        if (id == null) id = c.params().get("componentId");
        return id == null ? String.valueOf(c.componentType()) : String.valueOf(id);
    }

    private static boolean overlaps(Component a, Component b) {
        Vec3i ao = a.relativePosition();
        Vec3i bo = b.relativePosition();
        Dimensions ad = a.dimensions();
        Dimensions bd = b.dimensions();
        int aMaxX = ao.x() + ad.width() - 1;
        int aMaxZ = ao.z() + ad.depth() - 1;
        int bMaxX = bo.x() + bd.width() - 1;
        int bMaxZ = bo.z() + bd.depth() - 1;
        return ao.x() <= bMaxX && aMaxX >= bo.x() && ao.z() <= bMaxZ && aMaxZ >= bo.z();
    }

    private static Component nudgeAway(Component mover, Component anchor) {
        Vec3i mo = mover.relativePosition();
        Vec3i ao = anchor.relativePosition();
        Dimensions md = mover.dimensions();
        Dimensions ad = anchor.dimensions();
        int mMaxX = mo.x() + md.width() - 1;
        int mMaxZ = mo.z() + md.depth() - 1;
        int aMaxX = ao.x() + ad.width() - 1;
        int aMaxZ = ao.z() + ad.depth() - 1;

        int overlapX = Math.min(mMaxX, aMaxX) - Math.max(mo.x(), ao.x()) + 1;
        int overlapZ = Math.min(mMaxZ, aMaxZ) - Math.max(mo.z(), ao.z()) + 1;
        if (overlapX <= 0 || overlapZ <= 0) return mover;

        int dx = 0;
        int dz = 0;
        // Push along the thinner penetration axis; prefer the side the mover already leans toward.
        if (overlapX <= overlapZ) {
            int centerM = mo.x() + md.width() / 2;
            int centerA = ao.x() + ad.width() / 2;
            dx = centerM >= centerA
                    ? (aMaxX + GAP + 1) - mo.x()
                    : (ao.x() - GAP - md.width()) - mo.x();
        } else {
            int centerM = mo.z() + md.depth() / 2;
            int centerA = ao.z() + ad.depth() / 2;
            dz = centerM >= centerA
                    ? (aMaxZ + GAP + 1) - mo.z()
                    : (ao.z() - GAP - md.depth()) - mo.z();
        }
        if (dx == 0 && dz == 0) return mover;

        Map<String, Object> params = new HashMap<>();
        if (mover.params() != null) params.putAll(mover.params());
        params.put("anchor_mode", "min_corner");
        params.put("footprint_separated", true);
        return new Component(
                mover.componentType(),
                mover.slotId(),
                new Vec3i(mo.x() + dx, mo.y(), mo.z() + dz),
                md,
                mover.features(),
                params);
    }
}
