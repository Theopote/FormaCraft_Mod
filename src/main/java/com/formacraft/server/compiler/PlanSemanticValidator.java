package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reject authored plans that would become a residential house for tower/pagoda/
 * gate/garden intents. Intent is taken from authoritative request_intent first —
 * never only from LLM-authored distinguishing features.
 */
final class PlanSemanticValidator {
    private PlanSemanticValidator() {}

    static Optional<CapabilityGap> check(LlmPlan plan) {
        return check(plan, null, null);
    }

    /**
     * @param originalRequestIntent authoritative user text (may be null if already on plan_meta)
     * @param compiledGeometrySummary optional post-compile summary (reserved; unused for authored gate)
     */
    static Optional<CapabilityGap> check(
            LlmPlan plan,
            String originalRequestIntent,
            String compiledGeometrySummary
    ) {
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

        // Explicit roof must not be silently replaced by residential defaults —
        // applies to all building classes, including ordinary houses.
        String explicitRoof = explicitRoofType(plan, originalRequestIntent);
        if (explicitRoof != null && roofConflictsWithExplicit(components, explicitRoof)) {
            return Optional.of(new CapabilityGap(
                    "E_ROOF_INTENT_CONFLICT",
                    "计划屋顶与用户明确要求的屋顶类型冲突（要求=" + explicitRoof + "）。",
                    "components[].params.roof_type",
                    List.of("Preserve the requested roof type; do not substitute gable.")));
        }

        IntentClass intent = detectIntent(plan, originalRequestIntent);
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
        } else if (intent == IntentClass.TEMPLE) {
            // Single hall / shrine is valid; do not force garden courtyard composition.
            if (empty || (looksLikeDefaultHouse(components) && !hasTempleHallMorphology(components)
                    && !hasGardenComposition(components))) {
                return Optional.of(new CapabilityGap(
                        "E_SEMANTIC_FIDELITY",
                        "道观/寺庙意图需要殿堂体量（歇山/庑殿等）或院落组合，不能用普通住宅双坡屋顶替代。",
                        "components",
                        List.of("Emit a temple hall with Chinese roof typology, or a courtyard compound.")));
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
        return refusesResidentialAutofill(plan, null);
    }

    static boolean refusesResidentialAutofill(LlmPlan plan, String originalRequestIntent) {
        if (plan == null || plan.hasCapabilityGap()) {
            return false;
        }
        IntentClass intent = detectIntent(plan, originalRequestIntent);
        return intent != IntentClass.GENERIC;
    }

    /** Explicit roof from request_intent / chinese contract / architecture text. */
    static String explicitRoofType(LlmPlan plan, String originalRequestIntent) {
        Map<String, Object> ri = requestIntentMap(plan);
        if (ri != null) {
            if (Boolean.TRUE.equals(ri.get("roof_explicit")) || ri.get("roof_type") != null) {
                Object rt = ri.get("roof_type");
                if (rt != null && !String.valueOf(rt).isBlank()) {
                    return String.valueOf(rt).trim().toLowerCase(Locale.ROOT);
                }
            }
        }
        String corpus = authoritativeCorpus(plan, originalRequestIntent).toLowerCase(Locale.ROOT);
        // Honour negation via shared vocab extractor (不要重檐 must not force double_eave).
        String rawCorpus = authoritativeCorpus(plan, originalRequestIntent);
        if (rawCorpus != null && !rawCorpus.isBlank()) {
            Map<String, Object> fromVocab = RequestIntentInjector.extract(rawCorpus);
            Object rt = fromVocab.get("roof_type");
            if (rt != null && !String.valueOf(rt).isBlank()) {
                return String.valueOf(rt).trim().toLowerCase(Locale.ROOT);
            }
        }
        if (corpus.contains("歇山")) return "xieshan";
        if (corpus.contains("庑殿")) return "wudian";
        if (corpus.contains("攒尖")) return "pyramid";
        if (corpus.contains("悬山")) return "xuanshan";
        if (corpus.contains("硬山")) return "yingshan";
        if (corpus.contains("穹顶") || corpus.contains("dome")) return "dome";
        if (corpus.contains("平顶") || corpus.contains("flat roof")) return "flat";
        // Bare 重檐 only when not negated — extractor already returned null if negated.
        if (corpus.contains("重檐") && !corpus.contains("不要重檐") && !corpus.contains("无重檐")) {
            return "double_eave_hip";
        }
        return null;
    }

    private static boolean hasAlternateGeometry(LlmPlan plan) {
        if (plan.hasValidPlanProgramContent()) {
            return true;
        }
        return plan.patch() != null && plan.patch().blocks() != null && !plan.patch().blocks().isEmpty();
    }

    private enum IntentClass { GENERIC, TOWER_LIKE, GATE, TEMPLE, GARDEN }

    private static IntentClass detectIntent(LlmPlan plan, String originalRequestIntent) {
        Map<String, Object> ri = requestIntentMap(plan);
        if (ri != null) {
            IntentClass fromMeta = classFromToken(String.valueOf(ri.getOrDefault("building_class", "")));
            Object confObj = ri.get("confidence");
            double conf = confObj instanceof Number n ? n.doubleValue() : 1.0;
            // Low-confidence keyword guesses must not force specialized semantic gates.
            if (fromMeta != IntentClass.GENERIC && conf >= 0.5) {
                return fromMeta;
            }
        }
        if (plan.planMeta() != null) {
            IntentClass fromMeta = classFromToken(String.valueOf(plan.planMeta().getOrDefault("building_class", "")));
            if (fromMeta != IntentClass.GENERIC) {
                return fromMeta;
            }
        }
        // Shared vocab path — never treat 重檐 / bare 庭院 as class triggers.
        String corpusText = authoritativeCorpus(plan, originalRequestIntent);
        if (corpusText != null && !corpusText.isBlank()) {
            Map<String, Object> fromVocab = RequestIntentInjector.extract(corpusText);
            IntentClass fromKw = classFromToken(String.valueOf(fromVocab.getOrDefault("building_class", "")));
            Object confObj = fromVocab.get("confidence");
            double conf = confObj instanceof Number n ? n.doubleValue() : 0.0;
            if (fromKw != IntentClass.GENERIC && conf >= 0.5) {
                return fromKw;
            }
        }
        // Last resort: LLM-authored fields only (strong markers; no 重檐 / bare 庭院).
        String weak = weakPlanCorpus(plan).toLowerCase(Locale.ROOT);
        if (containsAny(weak,
                "佛塔", "古塔", "宝塔", "pagoda", "stupa", "dense_eaves_pagoda", "塔刹", "tibetan_stupa")) {
            return IntentClass.TOWER_LIKE;
        }
        if (containsAny(weak, "山门", "牌坊", "paifang", "寺庙山门")) {
            return IntentClass.GATE;
        }
        if (containsAny(weak, "道观", "寺庙", "庙宇", "大殿", "殿堂", "daoist", "temple")) {
            return IntentClass.TEMPLE;
        }
        if (containsAny(weak, "江南园林", "日式庭院", "园林", "garden", "courtyard compound")) {
            return IntentClass.GARDEN;
        }
        return IntentClass.GENERIC;
    }

    private static IntentClass classFromToken(String raw) {
        if (raw == null) return IntentClass.GENERIC;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "tower_like", "tower", "pagoda", "stupa" -> IntentClass.TOWER_LIKE;
            case "gate", "gatehouse", "paifang" -> IntentClass.GATE;
            case "temple", "daoist", "shrine" -> IntentClass.TEMPLE;
            case "garden", "courtyard" -> IntentClass.GARDEN;
            default -> IntentClass.GENERIC;
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requestIntentMap(LlmPlan plan) {
        if (plan == null || plan.planMeta() == null) return null;
        Object ri = plan.planMeta().get("request_intent");
        if (ri instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }

    /** User-owned text only: request_intent + architecture_intent.original_text. */
    private static String authoritativeCorpus(LlmPlan plan, String originalRequestIntent) {
        StringBuilder sb = new StringBuilder();
        if (originalRequestIntent != null && !originalRequestIntent.isBlank()) {
            sb.append(' ').append(originalRequestIntent);
        }
        Map<String, Object> ri = requestIntentMap(plan);
        if (ri != null) {
            Object text = ri.get("original_text");
            if (text != null) sb.append(' ').append(text);
            Object markers = ri.get("markers");
            if (markers instanceof List<?> list) {
                for (Object m : list) {
                    if (m != null) sb.append(' ').append(m);
                }
            }
        }
        if (plan != null && plan.planMeta() != null) {
            Object text = plan.planMeta().get("request_intent_text");
            if (text != null) sb.append(' ').append(text);
        }
        if (plan != null && plan.proportionHints() != null) {
            Object intent = plan.proportionHints().get("architecture_intent");
            if (intent instanceof Map<?, ?> map) {
                Object original = map.get("original_text");
                if (original != null) sb.append(' ').append(original);
            }
        }
        return sb.toString();
    }

    private static String weakPlanCorpus(LlmPlan plan) {
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

    /**
     * Pagoda/stupa must bind STRUCTURE/TOWER to dense_eaves_pagoda / tibetan_stupa.
     * Stacked residential MASS + windows/doors (even with "pagoda" features) is not a tower.
     */
    private static boolean hasTowerMorphology(List<Component> components) {
        boolean hasMass = false;
        boolean hasWindows = false;
        boolean hasEntrance = false;
        boolean nativeTypology = false;
        boolean towerType = false;
        boolean taperWithoutHouseKit = false;
        for (Component c : components) {
            if (c == null || c.componentType() == null) continue;
            String type = c.componentType().trim().toUpperCase(Locale.ROOT);
            if (type.startsWith("MASS") || "MAIN_MASS".equals(type)) hasMass = true;
            if ("FACADE_WINDOWS".equals(type)) hasWindows = true;
            if ("ENTRANCE".equals(type)) hasEntrance = true;
            if (type.startsWith("TOWER")) towerType = true;
            if (type.startsWith("STRUCTURE") || type.startsWith("TOWER")) {
                // Require an actual pagoda/stupa typology bind — feature keywords alone are not enough.
                if (hasNativePagodaTypology(c)) {
                    nativeTypology = true;
                }
            }
            if (hasTierParams(c) && !"FACADE_WINDOWS".equals(type)) {
                taperWithoutHouseKit = true;
            }
        }
        if (hasMass && hasWindows && hasEntrance && !nativeTypology) {
            return false;
        }
        return nativeTypology || towerType || (taperWithoutHouseKit && !hasWindows);
    }

    private static boolean hasNativePagodaTypology(Component c) {
        if (c.params() == null) return false;
        String typology = String.valueOf(c.params().getOrDefault("typology_id",
                c.params().getOrDefault("typology",
                        c.params().getOrDefault("structural_typology", "")))).toLowerCase(Locale.ROOT);
        if (typology.contains("dense_eaves_pagoda") || typology.contains("tibetan_stupa")
                || typology.contains("dense_eaves") || typology.contains("stupa")
                || typology.contains("pagoda")) {
            return true;
        }
        if (c.features() != null) {
            for (String f : c.features()) {
                if (f == null) continue;
                String lower = f.toLowerCase(Locale.ROOT);
                if (lower.contains("typology:dense_eaves") || lower.contains("typology:tibetan")
                        || lower.contains("dense_eaves_pagoda") || lower.contains("tibetan_stupa")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasPagodaCue(Component c) {
        if (c.params() != null) {
            String typology = String.valueOf(c.params().getOrDefault("typology_id",
                    c.params().getOrDefault("typology",
                            c.params().getOrDefault("structural_typology", "")))).toLowerCase(Locale.ROOT);
            if (typology.contains("pagoda") || typology.contains("stupa") || typology.contains("dense_eaves")) {
                return true;
            }
        }
        if (c.features() != null) {
            for (String f : c.features()) {
                if (f == null) continue;
                String lower = f.toLowerCase(Locale.ROOT);
                if (lower.contains("pagoda") || lower.contains("stupa") || lower.contains("dense_eaves")
                        || lower.contains("finial") || lower.contains("spire")
                        || f.contains("佛塔") || f.contains("古塔") || f.contains("塔刹") || f.contains("重檐")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasTierParams(Component c) {
        if (c.params() == null) return false;
        if (c.params().containsKey("tier_index")) return true;
        Object levels = c.params().get("levels");
        if (levels instanceof Number n && n.intValue() >= 2) return true;
        Object eaves = c.params().get("eave_tiers");
        if (eaves == null) eaves = c.params().get("eaveTiers");
        return eaves instanceof Number n && n.intValue() >= 2;
    }

    private static boolean hasGateMorphology(List<Component> components) {
        for (Component c : components) {
            if (c == null) continue;
            String type = c.componentType() == null ? "" : c.componentType().trim().toUpperCase(Locale.ROOT);
            // Executable gate generators — not STRUCTURE-keyword-only.
            if ("GATE".equals(type) || "GATE_STRUCTURE".equals(type)) {
                return true;
            }
            boolean gateCue = false;
            if (c.features() != null) {
                for (String f : c.features()) {
                    if (f == null) continue;
                    String lower = f.toLowerCase(Locale.ROOT);
                    if (lower.contains("gate") || f.contains("山门") || lower.contains("paifang")
                            || lower.contains("temple_gate") || lower.contains("gatehouse")) {
                        gateCue = true;
                        break;
                    }
                }
            }
            boolean throughPassage = false;
            if (c.params() != null) {
                if (c.params().get("passage_axis") != null) {
                    gateCue = true;
                    throughPassage = true;
                }
                Object dw = c.params().get("door_width");
                if (dw == null) dw = c.params().get("doorWidth");
                if (dw instanceof Number n && n.intValue() >= 2) {
                    throughPassage = true;
                }
            }
            boolean wideShallow = false;
            if (c.dimensions() != null) {
                int w = c.dimensions().width();
                int d = c.dimensions().depth();
                wideShallow = w >= d * 1.5 && w >= 10 && d <= Math.max(4, w / 2 + 2);
            }
            if ("STRUCTURE".equals(type) || "ASSEMBLY".equals(type)) {
                if (gateCue || wideShallow) return true;
            }
            // Mass / wing accepted when proportions + cue/passage prove gate morphology.
            if (wideShallow && (gateCue || throughPassage)) {
                return true;
            }
        }
        return false;
    }

    /** Single shrine/hall with Chinese roof or temple cues — not a residential gable kit. */
    private static boolean hasTempleHallMorphology(List<Component> components) {
        for (Component c : components) {
            if (c == null) continue;
            String type = c.componentType() == null ? "" : c.componentType().trim().toUpperCase(Locale.ROOT);
            if ("STRUCTURE".equals(type) || "ASSEMBLY".equals(type) || "GATE".equals(type)) {
                return true;
            }
            if (c.features() != null) {
                for (String f : c.features()) {
                    if (f == null) continue;
                    String lower = f.toLowerCase(Locale.ROOT);
                    if (lower.contains("temple") || lower.contains("daoist") || lower.contains("shrine")
                            || lower.contains("main_hall") || f.contains("道观") || f.contains("殿")
                            || f.contains("庙")) {
                        return true;
                    }
                }
            }
            if (c.params() != null) {
                Object rt = c.params().get("roof_type");
                if (rt == null) rt = c.params().get("roofType");
                if (rt != null) {
                    String roof = String.valueOf(rt).trim().toLowerCase(Locale.ROOT);
                    if (roof.contains("xieshan") || roof.contains("wudian") || roof.contains("xuanshan")
                            || roof.contains("yingshan") || roof.contains("hip") || roof.contains("irimoya")
                            || roof.contains("歇山") || roof.contains("庑殿") || roof.contains("入母屋")) {
                        return true;
                    }
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
                    || type.contains("PAVING") || "ASSEMBLY".equals(type)) {
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
            // CROWN bolted onto a house kit still counts as residential unless tower morphology passed.
            if ("CROWN".equals(type) && mass <= 1) {
                residentialSatellites = true;
            }
        }
        return mass == 1 && (residentialSatellites || components.size() == 1);
    }

    private static boolean roofConflictsWithExplicit(List<Component> components, String explicit) {
        if (explicit == null || explicit.isBlank() || components == null) return false;
        String want = explicit.trim().toLowerCase(Locale.ROOT);
        boolean wantDoubleEave = want.contains("double_eave") || "double_eave_hip".equals(want);
        boolean sawDoubleEave = false;
        boolean sawConflictingRoof = false;
        var wantKind = com.formacraft.common.generation.component.util.RoofTypeCatalog.parseExplicit(want);

        for (Component c : components) {
            if (c == null) continue;
            if (hasDoubleEaveSignal(c)) {
                sawDoubleEave = true;
            }
            String have = componentRoofType(c);
            if (have == null) continue;
            if (wantDoubleEave) {
                // Double-eave intent is about eave_configuration; a lone residential gable still conflicts.
                if ("gable".equals(have) || "double_gable".equals(have) || "yingshan".equals(have)) {
                    sawConflictingRoof = true;
                }
                continue;
            }
            var haveKind = com.formacraft.common.generation.component.util.RoofTypeCatalog.parseExplicit(have);
            if (wantKind.isPresent() && haveKind.isPresent()) {
                if (wantKind.get() != haveKind.get()) {
                    return true;
                }
                continue;
            }
            // Fallback string match when either side is an uncatalogued token (e.g. double_eave_hip).
            if (!roofTokenCompatible(want, have)) {
                return true;
            }
        }
        if (wantDoubleEave) {
            return !sawDoubleEave && sawConflictingRoof;
        }
        return false;
    }

    private static String componentRoofType(Component c) {
        if (c.params() != null) {
            Object rt = c.params().get("roof_type");
            if (rt == null) rt = c.params().get("roofType");
            if (rt != null) {
                String have = String.valueOf(rt).trim().toLowerCase(Locale.ROOT);
                if (!have.isEmpty() && !"none".equals(have)) return have;
            }
            Object eave = c.params().get("eave_configuration");
            if (eave == null) eave = c.params().get("eaveConfiguration");
            if (eave != null) {
                String e = String.valueOf(eave).trim().toLowerCase(Locale.ROOT);
                if (e.contains("double")) return "double_eave_hip";
            }
        }
        return null;
    }

    private static boolean hasDoubleEaveSignal(Component c) {
        if (c == null) return false;
        if (c.params() != null) {
            for (String key : List.of(
                    "double_eave", "doubleEave", "double_eaves", "doubleEaves",
                    "eave_configuration", "eaveConfiguration")) {
                Object v = c.params().get(key);
                if (v == null) continue;
                String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
                if ("true".equals(s) || "1".equals(s) || "yes".equals(s) || s.contains("double")) {
                    return true;
                }
            }
            Object rt = c.params().get("roof_type");
            if (rt == null) rt = c.params().get("roofType");
            if (rt != null && String.valueOf(rt).toLowerCase(Locale.ROOT).contains("double_eave")) {
                return true;
            }
        }
        if (c.features() != null) {
            for (String f : c.features()) {
                if (f != null && f.toLowerCase(Locale.ROOT).contains("double_eave")) return true;
            }
        }
        return false;
    }

    private static boolean roofTokenCompatible(String want, String have) {
        if (want.equals(have)) return true;
        if (want.contains(have) || have.contains(want)) return true;
        // Common alias families already covered by RoofTypeKind when both parse.
        return false;
    }
}
