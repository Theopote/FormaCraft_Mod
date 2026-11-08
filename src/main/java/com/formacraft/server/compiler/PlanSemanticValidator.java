package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.HashMap;
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
            // Residential empty plans are recovered later in ComponentSatelliteInference
            // (slot.size / architecture_intent footprint). Non-residential intents still fail closed.
            if (refusesResidentialAutofill(plan, originalRequestIntent)) {
                return Optional.of(new CapabilityGap(
                        "E_EMPTY_COMPONENTS",
                        "建筑计划缺少可编译构件（components 为空）。请重新规划，勿用普通房屋自动补全替代。",
                        "components",
                        List.of(
                                "Emit non-empty components[] for mode=build.",
                                "Or return plan_status=capability_gap when geometry cannot be expressed.")));
            }
        }

        // Explicit roof must not be silently replaced by residential defaults —
        // applies to all building classes, including ordinary houses.
        String explicitRoof = explicitRoofType(plan, originalRequestIntent);
        if (explicitRoof != null && roofConflictsWithExplicit(plan, components, explicitRoof)) {
            return Optional.of(new CapabilityGap(
                    "E_ROOF_INTENT_CONFLICT",
                    "计划屋顶与用户明确要求的屋顶类型冲突（要求=" + explicitRoof + "）。",
                    "components[].params.roof_type",
                    List.of("Preserve the requested roof type; do not substitute gable.")));
        }

        Optional<CapabilityGap> upperDoorGap = UpperStoreyEntranceAudit.check(plan);
        if (upperDoorGap.isPresent()) return upperDoorGap;

        IntentClass intent = detectIntent(plan, originalRequestIntent);
        if (intent == IntentClass.GENERIC) {
            return Optional.empty();
        }
        if (intent == IntentClass.TOWER_LIKE) {
            if (empty || !hasTowerMorphology(components) || looksLikeDefaultHouse(components)) {
                return Optional.of(new CapabilityGap(
                        "E_SEMANTIC_FIDELITY",
                        "塔楼/钟楼/佛塔类意图需要分层塔身、STRUCTURE/typology 或堆叠塔体，不能用单层住宅 MASS + 双坡屋顶替代。",
                        "components",
                        List.of(
                                "Use stacked/tapered masses, typology (gothic_bell_tower / dense_eaves_pagoda), or STRUCTURE.",
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
        // Vocab on the authoritative corpus owns negation (不要做成歇山) and must win over
        // a stale / LLM-authored request_intent.roof_type that ignored the forbid.
        String rawCorpus = authoritativeCorpus(plan, originalRequestIntent);
        Map<String, Object> fromVocab = null;
        if (rawCorpus != null && !rawCorpus.isBlank()) {
            fromVocab = RequestIntentInjector.extract(rawCorpus);
            Object rt = fromVocab.get("roof_type");
            if (rt != null && !String.valueOf(rt).isBlank()) {
                return String.valueOf(rt).trim().toLowerCase(Locale.ROOT);
            }
            // Forbidden-only mentions must not become requirements via naive contains()
            // or a conflicting request_intent.roof_type.
            if (fromVocab.get("roof_forbids") instanceof List<?> forbids && !forbids.isEmpty()) {
                Map<String, Object> ri = requestIntentMap(plan);
                if (ri != null && ri.get("roof_type") != null) {
                    String have = String.valueOf(ri.get("roof_type")).trim().toLowerCase(Locale.ROOT);
                    if (roofTypeForbiddenBy(have, forbids)) {
                        return null;
                    }
                } else {
                    return null;
                }
            }
        }
        Map<String, Object> ri = requestIntentMap(plan);
        if (ri != null) {
            if (Boolean.TRUE.equals(ri.get("roof_explicit")) || ri.get("roof_type") != null) {
                Object rt = ri.get("roof_type");
                if (rt != null && !String.valueOf(rt).isBlank()) {
                    String have = String.valueOf(rt).trim().toLowerCase(Locale.ROOT);
                    if (fromVocab != null
                            && fromVocab.get("roof_forbids") instanceof List<?> forbids
                            && roofTypeForbiddenBy(have, forbids)) {
                        return null;
                    }
                    return have;
                }
            }
        }
        String corpus = rawCorpus == null ? "" : rawCorpus.toLowerCase(Locale.ROOT);
        // 重檐 before plain 庑殿/歇山 so 「重檐庑殿」keeps double-eave intent.
        if (corpus.contains("重檐") && !negatedRoofMention(rawCorpus, "重檐")) {
            if (corpus.contains("歇山") && !negatedRoofMention(rawCorpus, "歇山")) {
                return "double_eave_xieshan";
            }
            return "double_eave_hip";
        }
        if (corpus.contains("歇山") && !negatedRoofMention(rawCorpus, "歇山")) return "xieshan";
        if (corpus.contains("庑殿") && !negatedRoofMention(rawCorpus, "庑殿")) return "wudian";
        if (corpus.contains("攒尖") && !negatedRoofMention(rawCorpus, "攒尖")) return "pyramid";
        if (corpus.contains("悬山") && !negatedRoofMention(rawCorpus, "悬山")) return "xuanshan";
        if (corpus.contains("硬山") && !negatedRoofMention(rawCorpus, "硬山")) return "yingshan";
        if (corpus.contains("穹顶") || corpus.contains("dome")) return "dome";
        if (corpus.contains("马萨") || corpus.contains("mansard") || corpus.contains("折线屋顶")
                || corpus.contains("折线形屋顶")) {
            return "mansard";
        }
        if (corpus.contains("平顶") || corpus.contains("flat roof")) return "flat";
        return null;
    }

    private static boolean roofTypeForbiddenBy(String roofType, List<?> forbids) {
        if (roofType == null || forbids == null) return false;
        String have = roofType.trim().toLowerCase(Locale.ROOT);
        for (Object f : forbids) {
            if (f == null) continue;
            String s = String.valueOf(f).toLowerCase(Locale.ROOT);
            if (have.contains(s) || s.contains(have)
                    || (have.contains("xieshan") && s.contains("xieshan"))
                    || (have.contains("double_eave") && s.contains("double_eave"))) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code token} appears only under local negation (不要做成X). */
    private static boolean negatedRoofMention(String rawCorpus, String token) {
        if (rawCorpus == null || token == null || token.isBlank()) return false;
        Map<String, Object> fromVocab = RequestIntentInjector.extract(rawCorpus);
        String canonical = switch (token) {
            case "歇山" -> "xieshan";
            case "庑殿" -> "wudian";
            case "悬山" -> "xuanshan";
            case "硬山" -> "yingshan";
            case "攒尖" -> "pyramid";
            case "重檐" -> "double_eave_hip";
            default -> token.toLowerCase(Locale.ROOT);
        };
        Object rt = fromVocab.get("roof_type");
        if (rt != null) {
            String have = String.valueOf(rt).toLowerCase(Locale.ROOT);
            if (have.contains(canonical) || canonical.contains(have)
                    || (canonical.contains("xieshan") && have.contains("xieshan"))
                    || (canonical.contains("double_eave") && have.contains("double_eave"))) {
                return false;
            }
        }
        if (fromVocab.get("roof_forbids") instanceof List<?> forbids) {
            for (Object f : forbids) {
                if (f == null) continue;
                String s = String.valueOf(f).toLowerCase(Locale.ROOT);
                if (s.contains(canonical) || canonical.contains(s)
                        || (canonical.contains("xieshan") && s.contains("xieshan"))
                        || (canonical.contains("double_eave") && s.contains("double_eave"))) {
                    return true;
                }
            }
        }
        // Fallback: every occurrence of the surface token is locally negated.
        String lower = rawCorpus.toLowerCase(Locale.ROOT);
        String needle = token.toLowerCase(Locale.ROOT);
        int start = 0;
        boolean saw = false;
        while (true) {
            int idx = lower.indexOf(needle, start);
            if (idx < 0) break;
            saw = true;
            if (!RequestIntentInjector.isNegatedAt(rawCorpus, idx)) {
                return false;
            }
            start = idx + needle.length();
        }
        return saw;
    }

    private static boolean hasAlternateGeometry(LlmPlan plan) {
        if (plan.hasValidPlanProgramContent()) {
            return true;
        }
        return plan.patch() != null && plan.patch().blocks() != null && !plan.patch().blocks().isEmpty();
    }

    private enum IntentClass { GENERIC, TOWER_LIKE, GATE, TEMPLE, GARDEN }

    private static IntentClass detectIntent(LlmPlan plan, String originalRequestIntent) {
        String corpus = authoritativeCorpus(plan, originalRequestIntent);
        IntentClass raw = detectIntentRaw(plan, originalRequestIntent, corpus);
        return demoteMisclassifiedIntent(raw, corpus, plan);
    }

    private static IntentClass detectIntentRaw(LlmPlan plan, String originalRequestIntent, String corpus) {
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
        String corpusText = corpus != null ? corpus : authoritativeCorpus(plan, originalRequestIntent);
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
        // Bare English "temple" matches roman_tholos features — require Chinese hall cues here.
        if (containsAny(weak, "道观", "寺庙", "庙宇", "大殿", "殿堂", "daoist")
                || (weak.contains("temple") && !looksClassicalRomanTemple(corpusText, plan))) {
            return IntentClass.TEMPLE;
        }
        if (containsAny(weak, "江南园林", "日式庭院", "园林", "garden", "courtyard compound")) {
            return IntentClass.GARDEN;
        }
        return IntentClass.GENERIC;
    }

    /**
     * Apply after every class source (request_intent, plan_meta.building_class, vocab, weak).
     * LLM / plan_meta often leave tower_like on 楼阁 or temple on Roman tholos.
     */
    private static IntentClass demoteMisclassifiedIntent(IntentClass raw, String corpus, LlmPlan plan) {
        if (raw == IntentClass.TOWER_LIKE
                && (looksResidentialWithoutPagoda(corpus) || looksPavilionWithoutPagoda(corpus))) {
            return IntentClass.GENERIC;
        }
        if (raw == IntentClass.TEMPLE && looksClassicalRomanTemple(corpus, plan)) {
            return IntentClass.GENERIC;
        }
        return raw == null ? IntentClass.GENERIC : raw;
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

    /** 住宅/公寓 without pagoda markers — not a tower/pagoda semantic gate. */
    private static boolean looksResidentialWithoutPagoda(String corpus) {
        if (corpus == null || corpus.isBlank()) return false;
        String lower = corpus.toLowerCase(Locale.ROOT);
        boolean residential = lower.contains("住宅") || lower.contains("公寓") || lower.contains("高密住宅")
                || lower.contains("别墅") || lower.contains("residence") || lower.contains("apartment")
                || lower.contains("villa") || (lower.contains("house") && !lower.contains("warehouse"));
        if (!residential) return false;
        return !hasPagodaMarkers(lower);
    }

    /** 楼阁 / 废土错落 pavilion — not a dense-eaves pagoda semantic gate. */
    private static boolean looksPavilionWithoutPagoda(String corpus) {
        if (corpus == null || corpus.isBlank()) return false;
        String lower = corpus.toLowerCase(Locale.ROOT);
        boolean pavilion = lower.contains("楼阁") || lower.contains("废土") || lower.contains("非对称")
                || lower.contains("错落") || lower.contains("wasteland") || lower.contains("asymmetric")
                || lower.contains("louge");
        if (!pavilion) return false;
        return !hasPagodaMarkers(lower);
    }

    private static boolean hasPagodaMarkers(String lower) {
        return lower.contains("密檐") || lower.contains("宝塔") || lower.contains("古塔")
                || lower.contains("佛塔") || lower.contains("pagoda") || lower.contains("stupa")
                || lower.contains("舍利塔");
    }

    private static boolean looksClassicalRomanTemple(String corpus, LlmPlan plan) {
        String lower = corpus == null ? "" : corpus.toLowerCase(Locale.ROOT);
        if (lower.contains("罗马") || lower.contains("roman") || lower.contains("tholos")
                || lower.contains("peristyle") || lower.contains("圆形神殿") || lower.contains("半径")) {
            return true;
        }
        String style = plan != null && plan.styleProfile() != null
                ? plan.styleProfile().toLowerCase(Locale.ROOT) : "";
        return style.contains("grecoroman") || style.contains("greco_roman") || style.contains("classical_greco");
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
     * Tower / pagoda / gothic bell plans must bind a tower typology (STRUCTURE or MASS
     * features), a TOWER component, stacked tall masses, or a compositional monument.
     * Lone residential MASS + windows/doors is not a tower.
     */
    private static boolean hasTowerMorphology(List<Component> components) {
        boolean hasMass = false;
        boolean hasWindows = false;
        boolean hasEntrance = false;
        boolean nativeTypology = false;
        boolean towerType = false;
        boolean taperWithoutHouseKit = false;
        boolean compositionalMonument = hasCompositionalTowerMorphology(components);
        boolean stackedTower = hasStackedTowerMasses(components);
        for (Component c : components) {
            if (c == null || c.componentType() == null) continue;
            String type = c.componentType().trim().toUpperCase(Locale.ROOT);
            if (type.startsWith("MASS") || "MAIN_MASS".equals(type)) hasMass = true;
            if ("FACADE_WINDOWS".equals(type)) hasWindows = true;
            if ("ENTRANCE".equals(type)) hasEntrance = true;
            if (type.startsWith("TOWER")) towerType = true;
            if (type.startsWith("STRUCTURE") || type.startsWith("TOWER") || type.startsWith("MASS")) {
                if (hasNativeTowerTypology(c)) {
                    nativeTypology = true;
                }
            }
            if (hasTierParams(c) && !"FACADE_WINDOWS".equals(type)) {
                taperWithoutHouseKit = true;
            }
        }
        if (hasMass && hasWindows && hasEntrance
                && !nativeTypology && !compositionalMonument && !stackedTower && !towerType) {
            return false;
        }
        return nativeTypology || towerType || compositionalMonument || stackedTower
                || (taperWithoutHouseKit && !hasWindows);
    }

    /**
     * Tall stacked MASS kits (gothic clocktower shaft + belfry / spire) count as tower
     * morphology even without a STRUCTURE typology carrier.
     */
    private static boolean hasStackedTowerMasses(List<Component> components) {
        if (components == null || components.isEmpty()) return false;
        int tallMasses = 0;
        int towerish = 0;
        for (Component c : components) {
            if (c == null || c.componentType() == null) continue;
            String type = c.componentType().trim().toUpperCase(Locale.ROOT);
            if (!(type.startsWith("MASS") || "MAIN_MASS".equals(type)
                    || type.startsWith("TOWER") || type.startsWith("STRUCTURE")
                    || "ROOF".equals(type))) {
                continue;
            }
            if (c.dimensions() != null) {
                int w = Math.max(0, c.dimensions().width());
                int d = Math.max(0, c.dimensions().depth());
                int h = Math.max(0, c.dimensions().height());
                int footprint = Math.max(w, d);
                if (footprint > 0 && h >= Math.max(16, footprint * 2)) {
                    tallMasses++;
                }
            }
            String blob = monumentTokenBlob(c);
            if (blob.matches("(?s).*(tower|belfry|spire|钟楼|塔楼|shaft|pinnacle|cantilever|"
                    + "gothic_tower|gothic_bell|clock.?tower|bell_tower|clocktower).*")) {
                towerish++;
            }
        }
        return (tallMasses >= 1 && towerish >= 1) || towerish >= 2;
    }

    /**
     * Live compositional stupas/pagodas: stacked MASS + monument tokens (harmika / bumpa /
     * chorten / 十三天) without a single STRUCTURE typology carrier.
     */
    private static boolean hasCompositionalTowerMorphology(List<Component> components) {
        if (components == null || components.isEmpty()) return false;
        int monumentHits = 0;
        int hostMasses = 0;
        for (Component c : components) {
            if (c == null || c.componentType() == null) continue;
            String type = c.componentType().trim().toUpperCase(Locale.ROOT);
            if (type.startsWith("MASS") || "MAIN_MASS".equals(type) || type.startsWith("TOWER")
                    || type.startsWith("STRUCTURE")) {
                hostMasses++;
            }
            if (monumentTokenBlob(c).matches("(?s).*(stupa|pagoda|chorten|harmika|bumpa|密檐|佛塔|宝塔|相轮|覆钵|十三天).*")) {
                monumentHits++;
            }
        }
        return monumentHits >= 2 && hostMasses >= 2;
    }

    private static String monumentTokenBlob(Component c) {
        StringBuilder sb = new StringBuilder();
        if (c.params() != null) {
            for (String key : List.of("component_id", "componentId", "building_id", "typology_id",
                    "typology", "structural_typology", "component_preset")) {
                Object v = c.params().get(key);
                if (v != null) sb.append(' ').append(v);
            }
        }
        if (c.features() != null) {
            for (String f : c.features()) {
                if (f != null) sb.append(' ').append(f);
            }
        }
        if (c.slotId() != null) sb.append(' ').append(c.slotId());
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean hasNativeTowerTypology(Component c) {
        if (c.params() != null) {
            String typology = String.valueOf(c.params().getOrDefault("typology_id",
                    c.params().getOrDefault("typology",
                            c.params().getOrDefault("structural_typology", "")))).toLowerCase(Locale.ROOT);
            if (isTowerTypologyToken(typology)) {
                return true;
            }
            Object id = c.params().get("component_id");
            if (id == null) id = c.params().get("componentId");
            if (id != null && isTowerTypologyToken(String.valueOf(id).toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        if (c.features() != null) {
            for (String f : c.features()) {
                if (f == null) continue;
                if (isTowerTypologyToken(f.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isTowerTypologyToken(String blob) {
        if (blob == null || blob.isBlank()) return false;
        String lower = blob.toLowerCase(Locale.ROOT);
        return lower.contains("dense_eaves_pagoda") || lower.contains("tibetan_stupa")
                || lower.contains("dense_eaves") || lower.contains("stupa")
                || lower.contains("pagoda") || lower.contains("chorten")
                || lower.contains("gothic_bell_tower") || lower.contains("bell_tower")
                || lower.contains("clock_tower") || lower.contains("clocktower")
                || lower.contains("watchtower") || lower.contains("keep_tower")
                || lower.contains("typology:gothic") || lower.contains("typology:bell")
                || lower.contains("typology:clock");
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
        // Count ENTRANCE satellites per host — deep 山门殿 often has 3 doorways without bays=.
        Map<String, Integer> entrancesByHost = new HashMap<>();
        for (Component c : components) {
            if (c == null || c.componentType() == null || c.params() == null) continue;
            if (!"ENTRANCE".equalsIgnoreCase(c.componentType().trim())) continue;
            Object host = c.params().get("host_id");
            if (host == null) host = c.params().get("hostId");
            if (host == null) continue;
            String key = String.valueOf(host);
            entrancesByHost.merge(key, 1, Integer::sum);
        }

        for (Component c : components) {
            if (c == null) continue;
            String type = c.componentType() == null ? "" : c.componentType().trim().toUpperCase(Locale.ROOT);
            // Executable gate generators — not STRUCTURE-keyword-only.
            if ("GATE".equals(type) || "GATE_STRUCTURE".equals(type)) {
                return true;
            }
            boolean gateCue = false;
            boolean throughPassage = false;
            boolean multiBay = false;
            if (c.features() != null) {
                for (String f : c.features()) {
                    if (f == null) continue;
                    String lower = f.toLowerCase(Locale.ROOT);
                    if (lower.contains("gate") || f.contains("山门") || lower.contains("paifang")
                            || lower.contains("temple_gate") || lower.contains("gatehouse")) {
                        gateCue = true;
                    }
                    // Live LLM plans use three_doorways / central_bay without params.bays.
                    if (lower.contains("three_door") || lower.contains("triple_door")
                            || lower.contains("multi_bay") || lower.contains("三门")
                            || lower.contains("三洞") || lower.startsWith("bay_division")
                            || lower.contains("gate_bay")) {
                        multiBay = true;
                        throughPassage = true;
                        gateCue = true;
                    }
                    if (lower.contains("doorway") || lower.contains("central_bay")
                            || lower.contains("通廊") || lower.contains("穿堂")) {
                        throughPassage = true;
                    }
                }
            }
            String selfId = null;
            if (c.params() != null) {
                Object id = c.params().get("component_id");
                if (id == null) id = c.params().get("componentId");
                if (id != null) {
                    selfId = String.valueOf(id);
                    String idLower = selfId.toLowerCase(Locale.ROOT);
                    if (idLower.contains("gate") || idLower.contains("shanmen")
                            || idLower.contains("paifang") || idLower.contains("山门")) {
                        gateCue = true;
                    }
                }
                if (c.params().get("passage_axis") != null) {
                    gateCue = true;
                    throughPassage = true;
                }
                Object entranceType = c.params().get("entrance_type");
                if (entranceType == null) entranceType = c.params().get("entranceType");
                if (entranceType != null) {
                    String et = String.valueOf(entranceType).toLowerCase(Locale.ROOT);
                    if (et.contains("gate")) {
                        gateCue = true;
                        throughPassage = true;
                    }
                }
                Object dw = c.params().get("door_width");
                if (dw == null) dw = c.params().get("doorWidth");
                if (dw instanceof Number n && n.intValue() >= 2) {
                    throughPassage = true;
                }
                Object bays = c.params().get("bays");
                if (bays == null) bays = c.params().get("bay_count");
                if (bays instanceof Number n && n.intValue() >= 3) {
                    multiBay = true;
                }
            }
            if (selfId != null) {
                int hostedDoors = entrancesByHost.getOrDefault(selfId, 0);
                if (hostedDoors >= 2) {
                    throughPassage = true;
                    gateCue = true;
                }
                if (hostedDoors >= 3) {
                    multiBay = true;
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
            // Deep ceremonial gate-halls (山门殿): gate cues + passage/multi-bay, even when not wide-shallow.
            if (gateCue && (throughPassage || multiBay)
                    && (type.startsWith("MASS") || "STRUCTURE".equals(type) || "HALL".equals(type))) {
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
                            || roof.contains("dome") || roof.contains("hemispher")
                            || roof.contains("歇山") || roof.contains("庑殿") || roof.contains("入母屋")
                            || roof.contains("穹顶")) {
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
                    || type.contains("PAVING") || "ASSEMBLY".equals(type)
                    || "TERRACE".equals(type) || "BALCONY".equals(type)) {
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

    private static boolean roofConflictsWithExplicit(
            LlmPlan plan, List<Component> components, String explicit
    ) {
        if (explicit == null || explicit.isBlank() || components == null) return false;
        String want = explicit.trim().toLowerCase(Locale.ROOT);
        boolean wantDoubleEave = want.contains("double_eave") || "double_eave_hip".equals(want);
        boolean multiBuilding = countCirculationMasses(components) > 1;
        String primaryHost = BuildingHostResolver.primaryMassId(plan, components);
        var wantKind = com.formacraft.common.generation.component.util.RoofTypeCatalog.parseExplicit(want);

        // Compounds often mix roofs (主殿歇山+厢房双坡 / 塔楼穹顶+裙房平顶). Presence of the
        // requested type on any host satisfies the gate; double-eave still binds to primary.
        if (multiBuilding && !wantDoubleEave && planHasRequestedRoof(components, want, wantKind)) {
            return false;
        }

        boolean sawPrimaryBoundRoof = false;
        for (Component c : components) {
            if (c == null) continue;
            if (!isRoofDeclaringComponent(c)) continue;
            if (multiBuilding && !isInScopeForPrimaryRoofIntent(c, primaryHost, true)) {
                continue;
            }
            sawPrimaryBoundRoof = true;
            if (wantDoubleEave) {
                // Per-host: a pavilion double-eave cannot prove the main hall roof.
                if (!hasDoubleEaveSignal(c)) {
                    return true;
                }
                continue;
            }
            String have = componentRoofType(c);
            if (have == null) continue;
            var haveKind = com.formacraft.common.generation.component.util.RoofTypeCatalog.parseExplicit(have);
            if (wantKind.isPresent() && haveKind.isPresent()) {
                if (wantKind.get() != haveKind.get()) {
                    return true;
                }
                continue;
            }
            if (!roofTokenCompatible(want, have)) {
                return true;
            }
        }

        // Compound plans with only unbound accessory roofs: fall back to unbound roofs once.
        if (multiBuilding && !sawPrimaryBoundRoof) {
            for (Component c : components) {
                if (c == null || !isRoofDeclaringComponent(c)) continue;
                if (BuildingHostResolver.roofHostId(c) != null) continue;
                if (wantDoubleEave) {
                    if (!hasDoubleEaveSignal(c)) return true;
                    continue;
                }
                String have = componentRoofType(c);
                if (have == null) continue;
                var haveKind = com.formacraft.common.generation.component.util.RoofTypeCatalog.parseExplicit(have);
                if (wantKind.isPresent() && haveKind.isPresent() && wantKind.get() != haveKind.get()) {
                    return true;
                }
                if (wantKind.isEmpty() && !roofTokenCompatible(want, have)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int countCirculationMasses(List<Component> components) {
        int n = 0;
        for (Component c : components) {
            if (BuildingHostResolver.isHostMass(c)) n++;
        }
        return n;
    }

    private static boolean isRoofDeclaringComponent(Component c) {
        if (c == null) return false;
        String t = c.componentType() == null ? "" : c.componentType().trim().toUpperCase(Locale.ROOT);
        return t.contains("ROOF") || componentRoofType(c) != null || hasDoubleEaveSignal(c);
    }

    /**
     * Multi-building: only roofs/masses bound to the primary host are in scope.
     * Unbound roofs are ignored when {@code requireBound} so an accessory cannot cross-prove.
     */
    private static boolean isInScopeForPrimaryRoofIntent(
            Component c, String primaryHost, boolean requireBound
    ) {
        if (c == null) return false;
        if (!isRoofDeclaringComponent(c)) return false;
        if (primaryHost == null || primaryHost.isBlank()) return true;
        String host = BuildingHostResolver.roofHostId(c);
        if (host == null) {
            // Mass that is itself the primary may declare roof_type on the body.
            String self = BuildingHostResolver.massId(c);
            return primaryHost.equals(self);
        }
        if (requireBound) {
            return primaryHost.equals(host);
        }
        return true;
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
        return want.contains(have) || have.contains(want);
        // Common alias families already covered by RoofTypeKind when both parse.
    }

    private static boolean planHasRequestedRoof(
            List<Component> components,
            String want,
            java.util.Optional<com.formacraft.common.generation.component.util.RoofTypeKind> wantKind
    ) {
        for (Component c : components) {
            if (c == null || !isRoofDeclaringComponent(c)) continue;
            String have = componentRoofType(c);
            if (have == null) continue;
            var haveKind = com.formacraft.common.generation.component.util.RoofTypeCatalog.parseExplicit(have);
            if (wantKind.isPresent() && haveKind.isPresent() && wantKind.get() == haveKind.get()) {
                return true;
            }
            if (wantKind.isEmpty() && roofTokenCompatible(want, have)) {
                return true;
            }
        }
        return false;
    }
}
