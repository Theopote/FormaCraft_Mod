package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.MassComponentKinds;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves the primary host mass for compound plans.
 * Prefers explicit {@code primary_building_id} / contract bindings over component order.
 */
final class BuildingHostResolver {
    private BuildingHostResolver() {}

    /** Declared {@code primary_building_id} from plan meta/contract (may be stale). */
    static String declaredPrimaryBuildingId(LlmPlan plan) {
        return explicitPrimaryId(plan);
    }

    static String primaryMassId(LlmPlan plan, List<Component> components) {
        if (components == null || components.isEmpty()) return null;

        String explicit = explicitPrimaryId(plan);
        if (explicit != null) {
            String matched = findMassId(components, explicit);
            // Missing primary_building_id must not become a phantom seal target.
            if (matched != null) return matched;
        }

        Component bestRole = null;
        int bestRoleScore = Integer.MIN_VALUE;
        Component bestVolume = null;
        long bestVol = -1L;

        for (Component c : components) {
            if (!isHostMass(c)) continue;
            String id = massId(c);
            int roleScore = roleScore(c);
            if (roleScore > bestRoleScore) {
                bestRoleScore = roleScore;
                bestRole = c;
            }
            long vol = volume(c);
            // Skip obvious accessory cues when ranking by volume unless no better candidate.
            if (roleScore < 0 && bestVolume != null) continue;
            if (vol > bestVol) {
                bestVol = vol;
                bestVolume = c;
            }
        }

        if (bestRole != null && bestRoleScore >= 2) {
            return massId(bestRole);
        }
        if (bestVolume != null) {
            return massId(bestVolume);
        }
        if (bestRole != null) {
            return massId(bestRole);
        }
        return null;
    }

    /**
     * Broad host probe used by primary resolution / audits.
     * Includes STRUCTURE/GATE for circulation and legacy compound graphs.
     * Design-constraint seals must use {@link #isDesignConstraintHost} instead.
     */
    static boolean isHostMass(Component c) {
        if (c == null || c.componentType() == null) return false;
        String t = c.componentType().trim().toUpperCase(Locale.ROOT);
        return t.startsWith("MASS") || "STRUCTURE".equals(t) || "HALL".equals(t)
                || "GATE".equals(t) || "GATE_STRUCTURE".equals(t) || "TOWER".equals(t);
    }

    /**
     * Building-level design-constraint hosts (height / roof / window / entrance omit).
     * Independent building bodies only: MASS family, TOWER, GATE, HALL without plate/host
     * attachment, plus STRUCTURE shells that carry an explicit typology feature or typology_id.
     * Classification: HARD_CONSTRAINT host inventory — not a design template.
     */
    static boolean isDesignConstraintHost(Component c) {
        if (c == null || c.componentType() == null) return false;
        // Deck/bridge plates and attached satellites are never whole-building constraint hosts.
        if (MassComponentKinds.isPlate(c)) return false;
        if (MassComponentKinds.hostId(c) != null) return false;
        String t = c.componentType().trim().toUpperCase(Locale.ROOT);
        // MASS_ANCILLARY is multipurpose (corridor/quay vs building). Require building identity
        // evidence — never treat bare type as a whole-building host (HARD_CONSTRAINT).
        if ("MASS_ANCILLARY".equals(t)) {
            return hasBuildingIdentityEvidence(c);
        }
        if (MassComponentKinds.isHostMass(c)) return true;
        if ("GATE".equals(t) || "HALL".equals(t) || t.startsWith("MASS")) return true;
        if ("STRUCTURE".equals(t)) {
            return looksIndependentBuildingStructure(c);
        }
        return false;
    }

    /**
     * Explicit use/name identity for multipurpose volumes (MASS_ANCILLARY).
     * Classification: HARD_CONSTRAINT — missing evidence excludes the host (no dual standards).
     * Never scans {@code component_id}: corridor ids like {@code corridor_office_workshop}
     * embed neighbor names and must not become whole-building hosts.
     */
    static boolean hasBuildingIdentityEvidence(Component c) {
        if (c == null || c.params() == null) return false;
        Object scope = first(c.params(), "requirement_scope");
        if (scope != null && String.valueOf(scope).trim().toLowerCase(Locale.ROOT).startsWith("named_")) {
            return true;
        }
        StringBuilder blob = new StringBuilder();
        for (String k : List.of("building_role", "buildingRole", "part", "label")) {
            Object v = c.params().get(k);
            if (v != null && !String.valueOf(v).isBlank()) {
                blob.append(String.valueOf(v).trim().toLowerCase(Locale.ROOT)).append(' ');
            }
        }
        String text = blob.toString();
        if (text.isBlank()) return false;
        for (String key : List.of(
                "main_body", "keep", "tower", "office", "workshop", "boiler", "main_hall",
                "pavilion", "wing", "gatehouse", "mill", "warehouse", "reception",
                "research", "dispatch", "residence", "chapel")) {
            for (String alias : com.formacraft.common.typology.UserConstraintContract.aliasesForNamedKey(key)) {
                if (alias != null && !alias.isBlank() && text.contains(alias.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * STRUCTURE building shell requires verifiable typology identity (allow-list).
     * Bare columns / porch posts without typology: stay accessories.
     */
    private static boolean looksIndependentBuildingStructure(Component c) {
        if (c.params() != null && Boolean.TRUE.equals(c.params().get("exterior"))) {
            return false;
        }
        boolean hasTypology = false;
        if (c.features() != null) {
            for (String f : c.features()) {
                if (f == null) continue;
                String fl = f.toLowerCase(Locale.ROOT);
                if (fl.startsWith("stair") || fl.contains("stair:")
                        || fl.contains("bridge") || fl.contains("fence")
                        || fl.contains("railing") || fl.contains("path")
                        || fl.contains("column") || fl.contains("pillar")
                        || fl.contains("porch")) {
                    return false;
                }
                if (fl.startsWith("typology:")) {
                    hasTypology = true;
                }
            }
        }
        if (!hasTypology && c.params() != null) {
            Object tid = first(c.params(), "typology_id", "typologyId", "structural_typology");
            if (tid != null && !String.valueOf(tid).isBlank()) {
                hasTypology = true;
            }
        }
        return hasTypology;
    }

    /** True when {@code id} is one of the design-constraint host mass ids. */
    static boolean isKnownDesignHostId(List<Component> designHosts, String id) {
        if (id == null || id.isBlank() || designHosts == null) return false;
        for (Component host : designHosts) {
            if (id.equals(massId(host))) return true;
        }
        return false;
    }

    static String massId(Component c) {
        if (c == null || c.params() == null) {
            return c == null || c.componentType() == null
                    ? null
                    : c.componentType().trim().toLowerCase(Locale.ROOT);
        }
        Object id = first(c.params(), "component_id", "componentId", "building_id", "buildingId");
        if (id != null && !String.valueOf(id).isBlank()) {
            return String.valueOf(id).trim();
        }
        return c.componentType() == null ? null : c.componentType().trim().toLowerCase(Locale.ROOT);
    }

    static String roofHostId(Component c) {
        if (c == null || c.params() == null) return null;
        Object host = first(c.params(), "host_id", "hostId", "building_id", "buildingId");
        if (host == null || String.valueOf(host).isBlank()) return null;
        return String.valueOf(host).trim();
    }

    @SuppressWarnings("unchecked")
    private static String explicitPrimaryId(LlmPlan plan) {
        if (plan == null) return null;
        List<Map<String, Object>> maps = new ArrayList<>();
        if (plan.planMeta() != null) {
            maps.add(plan.planMeta());
            Object ri = plan.planMeta().get("request_intent");
            if (ri instanceof Map<?, ?> m) {
                maps.add((Map<String, Object>) m);
            }
        }
        if (plan.proportionHints() != null) {
            maps.add(plan.proportionHints());
            Object contract = plan.proportionHints().get("building_contract");
            if (contract instanceof Map<?, ?> cm) {
                maps.add((Map<String, Object>) cm);
                Object bindings = cm.get("scope_bindings");
                if (bindings instanceof Map<?, ?> bm) {
                    Object named = bm.get("named_main_body");
                    if (named instanceof List<?> list && !list.isEmpty() && list.getFirst() != null) {
                        String id = String.valueOf(list.getFirst()).trim();
                        if (!id.isEmpty()) return id;
                    }
                }
            }
        }
        for (Map<String, Object> map : maps) {
            Object id = first(map,
                    "primary_building_id", "primaryBuildingId",
                    "primary_host_id", "primaryHostId",
                    "main_building_id", "mainBuildingId");
            if (id != null && !String.valueOf(id).isBlank()) {
                return String.valueOf(id).trim();
            }
        }
        return null;
    }

    private static String findMassId(List<Component> components, String want) {
        for (Component c : components) {
            if (!isHostMass(c)) continue;
            String id = massId(c);
            if (want.equals(id)) return id;
            if (c.params() != null) {
                Object bid = first(c.params(), "building_id", "buildingId");
                if (bid != null && want.equals(String.valueOf(bid).trim())) return id;
            }
        }
        return null;
    }

    private static int roleScore(Component c) {
        int score = 0;
        String type = c.componentType() == null ? "" : c.componentType().trim().toUpperCase(Locale.ROOT);
        if ("MASS_MAIN".equals(type) || "MAIN_MASS".equals(type) || "HALL".equals(type)) score += 3;
        if ("STRUCTURE".equals(type)) score += 2;
        if (type.contains("SECONDARY") || type.contains("WING") || type.contains("ANCILLARY")) score -= 2;

        if (c.params() != null) {
            if (Boolean.TRUE.equals(c.params().get("is_primary"))
                    || Boolean.TRUE.equals(c.params().get("primary"))) {
                score += 5;
            }
            Object role = first(c.params(), "building_role", "buildingRole", "mass_role", "massRole");
            if (role != null) {
                String r = String.valueOf(role).trim().toLowerCase(Locale.ROOT);
                if (r.contains("primary") || r.equals("main") || r.contains("hall_main") || r.equals("hall")) {
                    score += 5;
                }
                if (r.contains("pavilion") || r.contains("亭") || r.contains("accessory") || r.contains("secondary")) {
                    score -= 4;
                }
            }
            Object id = first(c.params(), "component_id", "componentId", "building_id", "buildingId");
            if (id != null) {
                String s = String.valueOf(id).toLowerCase(Locale.ROOT);
                if (s.contains("hall") || s.contains("main") || s.contains("殿")) score += 2;
                if (s.contains("pavilion") || s.contains("亭") || s.contains("gate") || s.contains("corridor")) {
                    score -= 3;
                }
            }
        }
        if (c.slotId() != null) {
            String slot = c.slotId().toLowerCase(Locale.ROOT);
            if (slot.contains("pavilion") || slot.contains("亭")) score -= 3;
            if (slot.contains("hall") || slot.contains("main")) score += 1;
        }
        return score;
    }

    private static long volume(Component c) {
        if (c == null || c.dimensions() == null) return 0L;
        return (long) Math.max(0, c.dimensions().width())
                * Math.max(0, c.dimensions().depth())
                * Math.max(0, c.dimensions().height());
    }

    private static Object first(Map<String, Object> map, String... keys) {
        if (map == null) return null;
        for (String key : keys) {
            if (map.containsKey(key) && map.get(key) != null) return map.get(key);
        }
        return null;
    }
}
