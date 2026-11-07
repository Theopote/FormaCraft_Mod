package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.routing.BuildingSpecRoutingPolicy;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.model.request.FormaRequest;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Injects {@code plan_meta.request_intent} from the original user request using the
 * shared vocab JSON. Keywords are auxiliary hints with confidence; LLM
 * {@code declared_intent} (when present on plan_meta) is preferred unless keywords
 * have a strong hit.
 */
public final class RequestIntentInjector {
    private static final Pattern FOOTPRINT = Pattern.compile("(\\d{1,2})\\s*[×xX*]\\s*(\\d{1,2})");
    private static final List<String> CLASS_PRIORITY = List.of("tower_like", "gate", "temple", "garden");

    private RequestIntentInjector() {}

    public static LlmPlan ensure(LlmPlan plan, FormaRequest req) {
        return ensure(plan, BuildingSpecRoutingPolicy.userIntentText(req));
    }

    public static LlmPlan ensure(LlmPlan plan, String userText) {
        if (plan == null) return null;
        String text = userText == null ? "" : userText.trim();
        Map<String, Object> meta = plan.planMeta() == null
                ? new HashMap<>()
                : new HashMap<>(plan.planMeta());
        Object existing = meta.get("request_intent");
        if (existing instanceof Map<?, ?> existingMap
                && existingMap.get("original_text") != null
                && !String.valueOf(existingMap.get("original_text")).isBlank()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> reconciled = new HashMap<>((Map<String, Object>) existingMap);
            String echo = String.valueOf(existingMap.get("original_text")).trim();
            // Server userText is authoritative. Python/LLM echo must not replace it.
            boolean mismatch = !text.isBlank() && !textEqualsIgnoreSpace(text, echo);
            if (mismatch) {
                reconciled.put("parsed_intent_echo", echo);
                reconciled.put("stale_parsed_intent", snapshotStructuredIntent(existingMap));
                reconciled.put("original_text", text);
                reconciled.put("intent_echo_mismatch", Boolean.TRUE);
                FormacraftMod.LOGGER.info(
                        "RequestIntentInjector: INTENT_ECHO_MISMATCH serverText≠plan echo; re-extracting intent");
                echo = text;
                // Full re-extract — class/materials/footprint must not keep stale LLM fields.
                applyKeywordAuthority(reconciled, extractKeyword(echo));
            } else if (!text.isBlank()) {
                reconciled.put("original_text", text);
                echo = text;
                reconcileRoofFromText(reconciled, echo);
            } else {
                reconcileRoofFromText(reconciled, echo);
            }
            meta.put("request_intent", reconciled);
            meta.put("request_intent_text", echo);
            if (reconciled.get("building_class") != null) {
                meta.put("building_class", reconciled.get("building_class"));
            }
            return OpeningConstraintProvenance.stamp(withMeta(plan, meta));
        }
        if (text.isBlank()) {
            return plan;
        }
        Map<String, Object> keyword = extractKeyword(text);
        Map<String, Object> intent = mergeWithLlmDeclared(keyword, meta);
        meta.put("request_intent", intent);
        meta.put("request_intent_text", text);
        meta.put("building_class", intent.get("building_class"));
        return OpeningConstraintProvenance.stamp(withMeta(plan, meta));
    }

    private static boolean textEqualsIgnoreSpace(String a, String b) {
        if (a == null || b == null) return a == b;
        return a.replaceAll("\\s+", "").equals(b.replaceAll("\\s+", ""));
    }

    /** Keyword-only extract (tests / fallback). Prefer {@link #ensure} for full merge. */
    static Map<String, Object> extract(String text) {
        return extractKeyword(text == null ? "" : text);
    }

    static Map<String, Object> extractKeyword(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        ScoredClass scored = scoreClass(text, lower);
        String buildingClass = scored.cls;
        double confidence = scored.confidence;
        List<String> markers = scored.markers;

        RoofResult roof = extractRoof(text, lower);
        List<String> forbids = extractForbids(text, lower);

        Map<String, Object> materials = new HashMap<>();
        JsonObject mats = RequestIntentVocab.root().getAsJsonObject("materials");
        if (mats != null) {
            putMaterial(materials, mats, "wall", text, lower);
            putMaterial(materials, mats, "roof", text, lower);
            putMaterial(materials, mats, "window", text, lower);
        } else {
            if (text.contains("白墙") || lower.contains("white_concrete")) {
                materials.put("wall", "minecraft:white_concrete");
            }
            if (text.contains("黑瓦") || lower.contains("black_terracotta")) {
                materials.put("roof", "minecraft:black_terracotta");
            }
            if (text.contains("黑色染色玻璃") || lower.contains("black_stained_glass")) {
                materials.put("window", "minecraft:black_stained_glass");
            }
        }

        boolean doorWidthExplicit = false;
        JsonObject openingCues = RequestIntentVocab.root().getAsJsonObject("opening_cues");
        if (openingCues != null) {
            List<String> doorCues = RequestIntentVocab.stringList(
                    openingCues.getAsJsonArray("door_width_explicit"));
            doorWidthExplicit = containsAny(text, lower, doorCues);
        } else {
            doorWidthExplicit = containsAny(text, lower, List.of(
                    "双开门", "两扇门", "三扇门", "宽门", "double door", "double doors", "door width"));
        }
        boolean windowMaterialExplicit = materials.containsKey("window");

        int width = 0, depth = 0;
        Matcher m = FOOTPRINT.matcher(text);
        while (m.find()) {
            int a = Integer.parseInt(m.group(1));
            int b = Integer.parseInt(m.group(2));
            if (a >= 4 && b >= 4 && a <= 48 && b <= 48) {
                width = a;
                depth = b;
                break;
            }
        }

        String composition = "single";
        if ("garden".equals(buildingClass)) {
            composition = "compound";
        } else if ("temple".equals(buildingClass)) {
            List<String> compound = RequestIntentVocab.stringList(
                    RequestIntentVocab.root().getAsJsonArray("temple_compound_markers"));
            for (String c : compound) {
                if (text.contains(c)) {
                    composition = "compound";
                    markers.add(c);
                    break;
                }
            }
        }

        String towerSubtype = null;
        if ("tower_like".equals(buildingClass)) {
            JsonObject subtypes = RequestIntentVocab.root().getAsJsonObject("tower_subtypes");
            List<String> tibetan = subtypes == null ? List.of()
                    : RequestIntentVocab.stringList(subtypes.getAsJsonArray("tibetan_stupa"));
            boolean isTibetan = false;
            for (String t : tibetan) {
                if (containsIgnoreCase(text, lower, t)) {
                    isTibetan = true;
                    break;
                }
            }
            towerSubtype = isTibetan ? "tibetan_stupa" : "dense_eaves_pagoda";
        }

        double scaffoldMin = RequestIntentVocab.conf("scaffold_min", 0.75);
        Map<String, Object> intent = new HashMap<>();
        intent.put("schema", "formacraft.request_intent.v1");
        intent.put("source", "keyword_hint");
        intent.put("original_text", text);
        intent.put("building_class", buildingClass);
        intent.put("confidence", confidence);
        intent.put("composition", composition);
        intent.put("tower_subtype", towerSubtype);
        intent.put("markers", markers);
        intent.put("roof_type", roof.roofType);
        intent.put("roof_explicit", roof.roofType != null);
        intent.put("roof_forbids", roof.forbids);
        if (width > 0 && depth > 0) {
            intent.put("footprint", Map.of("width", width, "depth", depth));
        }
        intent.put("forbids", forbids);
        intent.put("materials", materials);
        intent.put("door_width_explicit", doorWidthExplicit);
        intent.put("window_material_explicit", windowMaterialExplicit);
        boolean doorPresenceExplicit = detectDoorPresenceExplicit(text, lower);
        boolean upperEntranceExplicit = detectUpperEntranceExplicit(text, lower);
        intent.put("door_presence_explicit", doorPresenceExplicit);
        intent.put("upper_entrance_explicit", upperEntranceExplicit);
        intent.put("openings", Map.of(
                "door_width_explicit", doorWidthExplicit,
                "window_material_explicit", windowMaterialExplicit,
                "door_presence_explicit", doorPresenceExplicit,
                "upper_entrance_explicit", upperEntranceExplicit
        ));
        intent.put("refuse_residential_autofill",
                !"generic".equals(buildingClass) && confidence >= scaffoldMin);
        intent.put("scaffold_eligible",
                !"generic".equals(buildingClass) && confidence >= scaffoldMin);
        return intent;
    }

    /** Player asked for an entrance/door to exist (not merely a width). */
    private static boolean detectDoorPresenceExplicit(String text, String lower) {
        if (text == null || text.isBlank()) return false;
        return containsAny(text, lower, List.of(
                "入口", "开门", "设门", "装门", "正门", "大门", "侧门", "门洞",
                "entrance", "add a door", "with a door", "front door"));
    }

    /** Player asked for an upper-storey / balcony / stair-connected entrance. */
    private static boolean detectUpperEntranceExplicit(String text, String lower) {
        if (text == null || text.isBlank()) return false;
        boolean storey = text.contains("二层") || text.contains("上层") || text.contains("楼上")
                || lower.contains("second floor") || lower.contains("upper floor")
                || lower.contains("second-storey") || lower.contains("upper storey");
        boolean door = text.contains("入口") || text.contains("门") || lower.contains("entrance")
                || lower.contains("door");
        boolean stairLink = text.contains("楼梯") || text.contains("廊桥") || text.contains("阳台")
                || lower.contains("stair") || lower.contains("balcony") || lower.contains("bridge");
        return (storey && door) || (stairLink && door && storey);
    }

    private static Map<String, Object> snapshotStructuredIntent(Map<?, ?> existing) {
        Map<String, Object> snap = new HashMap<>();
        for (String key : List.of(
                "building_class", "tower_subtype", "composition", "markers", "materials",
                "footprint", "roof_type", "roof_forbids", "forbids", "confidence")) {
            if (existing.containsKey(key)) snap.put(key, existing.get(key));
        }
        return snap;
    }

    /**
     * Overlay keyword-extracted authoritative fields from server user text onto an existing intent.
     * Stale LLM class / materials / footprint must not survive INTENT_ECHO_MISMATCH.
     */
    static void applyKeywordAuthority(Map<String, Object> intent, Map<String, Object> keyword) {
        if (intent == null || keyword == null) return;
        for (String key : List.of(
                "building_class", "tower_subtype", "composition", "markers", "materials",
                "footprint", "roof_type", "roof_forbids", "forbids", "confidence",
                "roof_explicit", "door_width_explicit", "window_material_explicit",
                "door_presence_explicit", "upper_entrance_explicit", "openings",
                "refuse_residential_autofill", "scaffold_eligible", "source")) {
            if (!keyword.containsKey(key)) continue;
            Object v = keyword.get(key);
            if (v != null) intent.put(key, v);
        }
        reconcileRoofFromText(intent, String.valueOf(intent.getOrDefault("original_text", "")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mergeWithLlmDeclared(
            Map<String, Object> keyword, Map<String, Object> meta) {
        Map<String, Object> out = new HashMap<>(keyword);
        Object declared = meta.get("declared_intent");
        if (!(declared instanceof Map<?, ?>)) {
            declared = meta.get("llm_intent");
        }
        if (!(declared instanceof Map<?, ?> llmMap)) {
            return out;
        }
        Object classRaw = llmMap.get("building_class");
        if (classRaw == null) {
            classRaw = llmMap.get("class");
        }
        String llmClass = String.valueOf(classRaw != null ? classRaw : "generic")
                .trim().toLowerCase(Locale.ROOT);
        double llmConf = 0.7;
        try {
            Object c = llmMap.get("confidence");
            if (c instanceof Number n) llmConf = n.doubleValue();
            else if (c != null) llmConf = Double.parseDouble(String.valueOf(c));
        } catch (Exception ignored) {
            llmConf = 0.7;
        }
        String kwClass = String.valueOf(keyword.getOrDefault("building_class", "generic"));
        double kwConf = 0.0;
        try {
            Object c = keyword.get("confidence");
            if (c instanceof Number n) kwConf = n.doubleValue();
        } catch (Exception ignored) {
            kwConf = 0.0;
        }
        double kwOverride = RequestIntentVocab.conf("keyword_override_min", 0.8);
        double llmMin = RequestIntentVocab.conf("llm_preferred_min", 0.55);
        if (kwConf >= kwOverride && !"generic".equals(kwClass)) {
            out.put("source", "keyword_override");
            out.put("llm_declared", declared);
        } else if (llmConf >= llmMin && isValidClass(llmClass)) {
            out.put("building_class", llmClass);
            out.put("confidence", Math.max(llmConf, kwConf * 0.5));
            out.put("source", "llm_declared");
            out.put("keyword_hint_class", kwClass);
            out.put("keyword_hint_confidence", kwConf);
            double scaffoldMin = RequestIntentVocab.conf("scaffold_min", 0.75);
            double finalConf = ((Number) out.get("confidence")).doubleValue();
            out.put("refuse_residential_autofill",
                    !"generic".equals(llmClass) && finalConf >= scaffoldMin);
            out.put("scaffold_eligible",
                    !"generic".equals(llmClass) && finalConf >= scaffoldMin);
        } else {
            out.put("llm_declared", declared);
        }
        return out;
    }

    private static boolean isValidClass(String cls) {
        return "generic".equals(cls) || "tower_like".equals(cls) || "gate".equals(cls)
                || "garden".equals(cls) || "temple".equals(cls);
    }

    private static ScoredClass scoreClass(String text, String lower) {
        String best = "generic";
        double bestScore = 0.0;
        List<String> bestMarkers = new ArrayList<>();
        double strongW = RequestIntentVocab.conf("strong_hit", 0.9);
        double weakW = RequestIntentVocab.conf("weak_hit", 0.45);

        for (String cls : CLASS_PRIORITY) {
            JsonObject spec = RequestIntentVocab.buildingClass(cls);
            if (spec == null) continue;
            List<String> strong = RequestIntentVocab.stringList(spec.getAsJsonArray("strong_markers"));
            List<String> weak = RequestIntentVocab.stringList(spec.getAsJsonArray("weak_markers"));
            List<String> strongHits = positiveHits(text, lower, strong);
            List<String> weakHits = positiveHits(text, lower, weak);
            if (!weakHits.isEmpty()) {
                List<String> suppressors = RequestIntentVocab.stringList(
                        spec.getAsJsonArray("weak_suppressors"));
                if (!suppressors.isEmpty() && containsAny(text, lower, suppressors)) {
                    weakHits = List.of();
                }
            }
            if (strongHits.isEmpty() && weakHits.isEmpty()) continue;
            double score = !strongHits.isEmpty() ? strongW : weakW;
            if (score > bestScore) {
                bestScore = score;
                best = cls;
                bestMarkers = new ArrayList<>();
                bestMarkers.addAll(strongHits);
                bestMarkers.addAll(weakHits);
            }
        }
        if (bestMarkers.size() > 12) {
            bestMarkers = new ArrayList<>(bestMarkers.subList(0, 12));
        }
        return new ScoredClass(best, bestScore, bestMarkers);
    }

    private static RoofResult extractRoof(String text, String lower) {
        List<Map<String, String>> roofs = RequestIntentVocab.roofTypes();
        roofs.sort(Comparator.comparingInt((Map<String, String> r) -> -r.get("phrase").length()));
        String chosen = null;
        List<String> forbidden = new ArrayList<>();
        List<int[]> occupied = new ArrayList<>();
        for (Map<String, String> entry : roofs) {
            String phrase = entry.get("phrase");
            String canonical = entry.get("canonical").toLowerCase(Locale.ROOT);
            int[] span = findPositiveSpan(text, lower, phrase, occupied);
            if (span == null) continue;
            occupied.add(span);
            if (isNegatedAt(text, span[0])) {
                if (!forbidden.contains(canonical)) forbidden.add(canonical);
            } else if (chosen == null) {
                chosen = canonical;
            }
        }
        return new RoofResult(chosen, forbidden);
    }

    private static List<String> extractForbids(String text, String lower) {
        List<String> out = new ArrayList<>();
        JsonObject forbids = RequestIntentVocab.root().getAsJsonObject("forbids");
        JsonObject falseFriends = RequestIntentVocab.root().getAsJsonObject("forbid_false_friends");
        if (forbids == null) return out;
        for (String key : forbids.keySet()) {
            List<String> phrases = RequestIntentVocab.stringList(forbids.getAsJsonArray(key));
            List<String> friends = falseFriends == null ? List.of()
                    : RequestIntentVocab.stringList(falseFriends.getAsJsonArray(key));
            List<int[]> friendSpans = new ArrayList<>();
            for (String friend : friends) {
                int start = 0;
                String fl = friend.toLowerCase(Locale.ROOT);
                while (true) {
                    int idx = indexOf(text, lower, friend, fl, start);
                    if (idx < 0) break;
                    friendSpans.add(new int[]{idx, idx + friend.length()});
                    start = idx + 1;
                }
            }
            for (String phrase : phrases) {
                String pl = phrase.toLowerCase(Locale.ROOT);
                int start = 0;
                boolean matched = false;
                while (true) {
                    int idx = indexOf(text, lower, phrase, pl, start);
                    if (idx < 0) break;
                    int end = idx + phrase.length();
                    boolean insideFriend = false;
                    for (int[] fs : friendSpans) {
                        if (idx >= fs[0] && end <= fs[1]) {
                            insideFriend = true;
                            break;
                        }
                    }
                    if (insideFriend) {
                        start = end;
                        continue;
                    }
                    matched = true;
                    break;
                }
                if (matched) {
                    out.add(key);
                    break;
                }
            }
        }
        return out;
    }

    private static void putMaterial(
            Map<String, Object> materials, JsonObject mats, String slot, String text, String lower) {
        if (materials.containsKey(slot)) return;
        JsonArray entries = mats.getAsJsonArray(slot);
        if (entries == null) return;
        for (var el : entries) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            List<String> phrases = RequestIntentVocab.stringList(o.getAsJsonArray("phrases"));
            String block = o.has("block") ? o.get("block").getAsString() : null;
            if (block == null || phrases.isEmpty()) continue;
            if (containsAny(text, lower, phrases)) {
                materials.put(slot, block);
                return;
            }
        }
    }

    private static List<String> positiveHits(String text, String lower, List<String> markers) {
        List<String> ordered = new ArrayList<>(markers);
        ordered.sort(Comparator.comparingInt((String s) -> -s.length()));
        List<String> hits = new ArrayList<>();
        List<int[]> occupied = new ArrayList<>();
        for (String marker : ordered) {
            int[] span = findPositiveSpan(text, lower, marker, occupied);
            if (span == null) continue;
            if (isNegatedAt(text, span[0])) continue;
            hits.add(marker);
            occupied.add(span);
        }
        return hits;
    }

    private static int[] findPositiveSpan(
            String text, String lower, String needle, List<int[]> occupied) {
        String nl = needle.toLowerCase(Locale.ROOT);
        int start = 0;
        while (true) {
            int idx = indexOf(text, lower, needle, nl, start);
            if (idx < 0) return null;
            int end = idx + needle.length();
            boolean overlap = false;
            for (int[] o : occupied) {
                if (!(end <= o[0] || idx >= o[1])) {
                    overlap = true;
                    break;
                }
            }
            if (overlap) {
                start = end;
                continue;
            }
            return new int[]{idx, end};
        }
    }

    private static final Pattern CJK_NEGATION_SUFFIX = Pattern.compile(
            "(?:不要|别要|无需|不用|不采用|不使用|禁止|避免|无)[的了着过]?$");
    /**
     * 「不要做成歇山」「不要做成歇山大殿」— verb fillers, plus a short compound tail
     * so 大殿 under 「不要做成歇山大殿」is still treated as negated.
     * Bare 「无」stays on {@link #CJK_NEGATION_SUFFIX} so 「无窗的中式古塔」is safe.
     */
    private static final Pattern CJK_NEGATION_WITH_VERB = Pattern.compile(
            "(?:不要|别要|别|无需|不用|禁止|避免)"
                    + "(?:做成|改成|改为|改用|做成了|改成了|用|当|作为)?"
                    + "[\\u4e00-\\u9fffA-Za-z0-9_/·\\-]{0,6}$");
    private static final Pattern ASCII_NEGATION_SUFFIX = Pattern.compile(
            "(?:(?:do\\s+)?not|don't|dont|no|without)\\s+$", Pattern.CASE_INSENSITIVE);

    /**
     * Overlay keyword roof_type / roof_forbids onto an existing request_intent map.
     * Keyword negatives win: a forbidden roof must not remain as roof_type.
     */
    static void reconcileRoofFromText(Map<String, Object> intent, String text) {
        if (intent == null || text == null || text.isBlank()) return;
        Map<String, Object> keyword = extractKeyword(text);
        Object kwType = keyword.get("roof_type");
        Object kwForbids = keyword.get("roof_forbids");
        if (kwForbids instanceof List<?> forbids && !forbids.isEmpty()) {
            intent.put("roof_forbids", forbids);
        }
        Object have = intent.get("roof_type");
        boolean haveForbidden = have != null
                && kwForbids instanceof List<?> forbids
                && roofForbidden(String.valueOf(have), forbids);
        if (kwType != null && !String.valueOf(kwType).isBlank()) {
            // Keyword positive roof wins when LLM left blank or authored a forbidden type.
            if (have == null || String.valueOf(have).isBlank() || haveForbidden
                    || !String.valueOf(have).equalsIgnoreCase(String.valueOf(kwType))) {
                if (have == null || String.valueOf(have).isBlank() || haveForbidden) {
                    intent.put("roof_type", kwType);
                    intent.put("roof_explicit", true);
                }
            }
        } else if (haveForbidden) {
            intent.put("roof_type", null);
            intent.put("roof_explicit", false);
        }
        // Demote temple class when the only hall marker sits under local negation.
        Object cls = intent.get("building_class");
        if ("temple".equals(String.valueOf(cls))
                && "generic".equals(String.valueOf(keyword.getOrDefault("building_class", "generic")))) {
            intent.put("building_class", "generic");
            intent.put("confidence", keyword.getOrDefault("confidence", 0.0));
            intent.put("refuse_residential_autofill", false);
            intent.put("scaffold_eligible", false);
        }
    }

    private static boolean roofForbidden(String roofType, List<?> forbids) {
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

    /** Package-visible for PlanSemanticValidator roof-negation fallbacks. */
    static boolean isNegatedAt(String text, int start) {
        // Only the immediate preceding suffix counts — otherwise
        // 「无窗的中式古塔」would treat 古塔 as negated because 无 appears earlier.
        if (start <= 0) return false;
        String window = text.substring(Math.max(0, start - 16), start);
        String compact = window.replaceAll("\\s+", "");
        if (CJK_NEGATION_WITH_VERB.matcher(compact).find()) return true;
        if (CJK_NEGATION_SUFFIX.matcher(compact).find()) return true;
        return ASCII_NEGATION_SUFFIX.matcher(window.toLowerCase(Locale.ROOT)).find();
    }

    private static boolean containsAny(String text, String lower, List<String> needles) {
        for (String n : needles) {
            if (containsIgnoreCase(text, lower, n)) return true;
        }
        return false;
    }

    private static boolean containsIgnoreCase(String text, String lower, String needle) {
        if (needle == null || needle.isEmpty()) return false;
        return lower.contains(needle.toLowerCase(Locale.ROOT)) || text.contains(needle);
    }

    private static int indexOf(String text, String lower, String needle, String needleLower, int from) {
        int idx = lower.indexOf(needleLower, from);
        if (idx >= 0) return idx;
        return text.indexOf(needle, from);
    }

    private static LlmPlan withMeta(LlmPlan plan, Map<String, Object> meta) {
        return new LlmPlan(
                plan.mode(),
                plan.styleProfile(),
                plan.anchor(),
                plan.globalConstraints(),
                plan.layout(),
                plan.components(),
                plan.genome(),
                plan.styleAttributes(),
                plan.proportionHints(),
                plan.alignmentAndSymmetry(),
                plan.targetSlotId(),
                plan.allowedArea(),
                plan.patch(),
                plan.planProgram(),
                plan.planSkeleton(),
                plan.planStatus(),
                plan.error(),
                plan.capabilityGap(),
                plan.playerFidelityNoticeZh(),
                plan.distinguishingFeatures(),
                plan.enrichmentGuard(),
                meta,
                plan.designSources());
    }

    private record ScoredClass(String cls, double confidence, List<String> markers) {}
    private record RoofResult(String roofType, List<String> forbids) {}
}
