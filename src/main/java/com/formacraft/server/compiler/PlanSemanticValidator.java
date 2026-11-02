package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reject authored plans that would become a residential house for tower/pagoda/
 * gate/garden intents, and refuse empty build components when no alternate geometry exists.
 */
final class PlanSemanticValidator {
    private PlanSemanticValidator() {}

    static Optional<CapabilityGap> check(LlmPlan plan) {
        if (plan == null) {
            return Optional.empty();
        }
        if (plan.hasCapabilityGap()) {
            return Optional.empty();
        }
        String status = plan.planStatus() == null ? "" : plan.planStatus().trim().toLowerCase(Locale.ROOT);
        if ("capability_gap".equals(status) || "error".equals(status)) {
            return Optional.empty();
        }
        if (plan.mode() == LlmPlan.Mode.patch) {
            return Optional.empty();
        }
        List<Component> components = plan.components() == null ? List.of() : plan.components();
        boolean empty = components.isEmpty();
        if (empty && !hasAlternateGeometry(plan)) {
            return Optional.of(new CapabilityGap(
                    "E_EMPTY_COMPONENTS",
                    "建筑计划缺少可编译构件（components 为空）。请重新规划，勿用普通房屋自动补全替代。",
                    "components",
                    List.of(
                            "Emit non-empty components[] for mode=build.",
                            "Or return plan_status=capability_gap when geometry cannot be expressed.")));
        }

        IntentClass intent = detectIntent(plan);
        if (intent == IntentClass.GENERIC) {
            return Optional.empty();
        }
        if (intent == IntentClass.TOWER_LIKE) {
            if (empty || !hasTowerMorphology(components) || looksLikeDefaultHouse(components)) {
                return Optional.of(new CapabilityGap(
                        "E_SEMANTIC_FIDELITY",
                        "佛塔/古塔类意图需要分层塔身或 STRUCTURE/typology，不能用单层住宅 MASS + 双坡屋顶替代。",
                        "components",
                        List.of(
                                "Use stacked/tapered masses, typology:dense_eaves_pagoda, or STRUCTURE.",
                                "Do not emit a lone MASS_MAIN with residential windows and gable roof.")));
            }
        } else if (intent == IntentClass.GATE) {
            if (empty || (!hasGateMorphology(components) && looksLikeDefaultHouse(components))) {
                return Optional.of(new CapabilityGap(
                        "E_SEMANTIC_FIDELITY",
                        "山门/牌坊意图需要宽浅门楼体量或 STRUCTURE/ASSEMBLY，不能用普通房屋组合替代。",
                        "components",
                        List.of("Emit a wide shallow gate mass or STRUCTURE/ASSEMBLY for temple gates.")));
            }
        } else if (intent == IntentClass.GARDEN) {
            if (empty || (looksLikeDefaultHouse(components) && !hasGardenComposition(components))) {
                return Optional.of(new CapabilityGap(
                        "E_SEMANTIC_FIDELITY",
                        "园林/庭院意图需要场地组合（PATH/COURTYARD/ASSEMBLY 等），不能只有一栋住宅。",
                        "components",
                        List.of("Add PATH, COURTYARD, ASSEMBLY or equivalent site components.")));
            }
        }
        return Optional.empty();
    }

    /** True when empty-components recovery / residential satellite inference must be refused. */
    static boolean refusesResidentialAutofill(LlmPlan plan) {
        if (plan == null || plan.hasCapabilityGap()) {
            return false;
        }
        IntentClass intent = detectIntent(plan);
        return intent != IntentClass.GENERIC;
    }

    private static boolean hasAlternateGeometry(LlmPlan plan) {
        if (plan.hasValidPlanProgramContent()) {
            return true;
        }
        return plan.patch() != null && plan.patch().blocks() != null && !plan.patch().blocks().isEmpty();
    }

    private enum IntentClass { GENERIC, TOWER_LIKE, GATE, GARDEN }

    private static IntentClass detectIntent(LlmPlan plan) {
        String corpus = intentCorpus(plan).toLowerCase(Locale.ROOT);
        if (containsAny(corpus,
                "佛塔", "古塔", "宝塔", "藏式", "pagoda", "stupa", "dense_eaves_pagoda",
                "tiered_eave", "finial_spire", "塔刹", "重檐")) {
            return IntentClass.TOWER_LIKE;
        }
        if (containsAny(corpus, "山门", "牌坊", "paifang", "gatehouse", "temple gate", "寺庙山门")) {
            return IntentClass.GATE;
        }
        if (containsAny(corpus, "园林", "庭院", "江南园林", "日式庭院", "garden", "courtyard compound")) {
            return IntentClass.GARDEN;
        }
        if (containsAny(corpus, "道观", "寺庙", "庙宇", "daoist", "shrine compound")) {
            // Temple compounds still refuse empty→house recovery.
            return IntentClass.GARDEN;
        }
        return IntentClass.GENERIC;
    }

    private static String intentCorpus(LlmPlan plan) {
        StringBuilder sb = new StringBuilder();
        if (plan.styleProfile() != null) sb.append(' ').append(plan.styleProfile());
        if (plan.distinguishingFeatures() != null) {
            for (String f : plan.distinguishingFeatures()) {
                if (f != null) sb.append(' ').append(f);
            }
        }
        if (plan.styleAttributes() != null && plan.styleAttributes().decorativeElements() != null) {
            for (String f : plan.styleAttributes().decorativeElements()) {
                if (f != null) sb.append(' ').append(f);
            }
        }
        if (plan.playerFidelityNoticeZh() != null) sb.append(' ').append(plan.playerFidelityNoticeZh());
        if (plan.components() != null) {
            for (Component c : plan.components()) {
                if (c == null) continue;
                if (c.features() != null) {
                    for (String f : c.features()) {
                        if (f != null) sb.append(' ').append(f);
                    }
                }
                if (c.params() != null) {
                    Object typology = c.params().get("typology");
                    if (typology == null) typology = c.params().get("structural_typology");
                    if (typology != null) sb.append(' ').append(typology);
                }
            }
        }
        return sb.toString();
    }

    private static boolean containsAny(String corpus, String... needles) {
        for (String n : needles) {
            if (n != null && !n.isEmpty() && corpus.contains(n.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTowerMorphology(List<Component> components) {
        int massCount = 0;
        int roofCount = 0;
        for (Component c : components) {
            if (c == null || c.componentType() == null) continue;
            String type = c.componentType().trim().toUpperCase(Locale.ROOT);
            if (type.startsWith("MASS") || "MAIN_MASS".equals(type)) massCount++;
            if ("ROOF".equals(type)) roofCount++;
            if (type.startsWith("STRUCTURE") || type.startsWith("TOWER")
                    || "MODULE".equals(type) || "ASSEMBLY".equals(type) || "CROWN".equals(type)) {
                return true;
            }
            if (c.params() != null) {
                if (c.params().containsKey("tier_index") || c.params().containsKey("levels")
                        || c.params().containsKey("eave_tiers")) {
                    return true;
                }
                String typology = String.valueOf(c.params().getOrDefault("typology",
                        c.params().getOrDefault("structural_typology", ""))).toLowerCase(Locale.ROOT);
                if (typology.contains("pagoda") || typology.contains("stupa") || typology.contains("tower")) {
                    return true;
                }
            }
            if (c.features() != null) {
                for (String f : c.features()) {
                    if (f == null) continue;
                    String lower = f.toLowerCase(Locale.ROOT);
                    if (lower.contains("pagoda") || lower.contains("stupa") || lower.contains("tower")
                            || lower.contains("tier") || lower.contains("finial") || lower.contains("spire")
                            || f.contains("佛塔") || f.contains("古塔") || f.contains("塔刹") || f.contains("重檐")) {
                        return true;
                    }
                }
            }
            if ((type.startsWith("MASS") || "MAIN_MASS".equals(type)) && c.dimensions() != null) {
                int w = c.dimensions().width();
                int d = c.dimensions().depth();
                int h = c.dimensions().height();
                if (h >= Math.max(w, d) * 1.5 && h >= 12) {
                    return true;
                }
            }
        }
        return massCount >= 2 && roofCount >= 2;
    }

    private static boolean hasGateMorphology(List<Component> components) {
        for (Component c : components) {
            if (c == null) continue;
            String type = c.componentType() == null ? "" : c.componentType().trim().toUpperCase(Locale.ROOT);
            if ("STRUCTURE".equals(type) || "ASSEMBLY".equals(type)) return true;
            if (c.dimensions() != null) {
                int w = c.dimensions().width();
                int d = c.dimensions().depth();
                if (w >= d * 1.5 && w >= 10) return true;
            }
            if (c.features() != null) {
                for (String f : c.features()) {
                    if (f == null) continue;
                    String lower = f.toLowerCase(Locale.ROOT);
                    if (lower.contains("gate") || f.contains("山门") || lower.contains("paifang")) return true;
                }
            }
        }
        return false;
    }

    private static boolean hasGardenComposition(List<Component> components) {
        for (Component c : components) {
            if (c == null || c.componentType() == null) continue;
            String type = c.componentType().trim().toUpperCase(Locale.ROOT);
            if (type.contains("PATH") || type.contains("COURTYARD") || type.contains("GARDEN")
                    || type.contains("PAVING") || "ASSEMBLY".equals(type) || type.startsWith("STRUCTURE")) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeDefaultHouse(List<Component> components) {
        if (components == null || components.isEmpty()) return true;
        if (hasTowerMorphology(components)) return false;
        int mass = 0;
        boolean residentialSatellites = false;
        for (Component c : components) {
            if (c == null || c.componentType() == null) continue;
            String type = c.componentType().trim().toUpperCase(Locale.ROOT);
            if (type.startsWith("MASS") || "MAIN_MASS".equals(type)) mass++;
            if ("ROOF".equals(type) || "ENTRANCE".equals(type) || "FACADE_WINDOWS".equals(type)
                    || "FOUNDATION".equals(type)) {
                residentialSatellites = true;
            }
        }
        return mass == 1 && (residentialSatellites || components.size() == 1);
    }
}
