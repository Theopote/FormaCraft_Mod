package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.model.request.FormaRequest;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    /** Storey cue and entrance cue in the same short clause (not whole-prompt co-occurrence). */
    private static final Pattern UPPER_STOREY_ENTRANCE_CLAUSE = Pattern.compile(
            "(?:二层|二楼|上层).{0,20}(?:入口|大门|正门|门洞)"
                    + "|(?:入口|大门|正门).{0,12}(?:设在|位于|开在|设置在).{0,8}(?:二层|二楼|上层)"
                    + "|second[- ](?:floor|storey).{0,20}entrance"
                    + "|upper[- ](?:floor|storey).{0,20}entrance",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final List<String> CLASS_PRIORITY = List.of("tower_like", "gate", "temple", "garden");

    private RequestIntentInjector() {}

    public static LlmPlan ensure(LlmPlan plan, FormaRequest req) {
        return ensure(plan, com.formacraft.common.model.request.RequestIntentText.resolve(req));
    }

    public static LlmPlan ensure(LlmPlan plan, String userText) {
        if (plan == null) return null;
        String text = userText == null ? "" : userText.trim();
        // Only FormaRequest-backed non-blank text may mint/reissue server seals.
        boolean authorizeFromText = !text.isBlank();
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
            boolean mismatch = authorizeFromText && !textEqualsIgnoreSpace(text, echo);
            if (mismatch) {
                reconciled.put("parsed_intent_echo", echo);
                reconciled.put("stale_parsed_intent", snapshotStructuredIntent(existingMap));
                reconciled.put("original_text", text);
                reconciled.put("intent_echo_mismatch", Boolean.TRUE);
                FormacraftMod.LOGGER.info(
                        "RequestIntentInjector: INTENT_ECHO_MISMATCH serverText≠plan echo; re-extracting intent");
                echo = text;
            } else if (authorizeFromText) {
                reconciled.put("original_text", text);
                echo = text;
            }
            // Keyword class reconciliation may use echo for continuity, but seals need server text.
            String intentText = authorizeFromText ? text : echo;
            if (!intentText.isBlank()) {
                applyKeywordAuthority(reconciled, extractKeyword(intentText), authorizeFromText);
            } else {
                reconcileRoofFromText(reconciled, intentText);
            }
            int explicitH = authorizeFromText
                    ? com.formacraft.common.typology.UserConstraintContract
                    .parseExplicitHeightBlocks(text)
                    : 0;
            if (explicitH > 0) {
                reconciled.put("height_explicit", Boolean.TRUE);
                reconciled.put("explicit_height_blocks", explicitH);
            } else if (authorizeFromText) {
                reconciled.remove("height_explicit");
                reconciled.remove("explicit_height_blocks");
            }
            meta.put("request_intent", reconciled);
            meta.put("request_intent_text", authorizeFromText ? text : echo);
            // Height/design contracts remint only from FormaRequest text — never from plan echo.
            if (authorizeFromText) {
                meta.put(com.formacraft.common.typology.UserConstraintContract.PLAN_META_KEY,
                        com.formacraft.common.typology.UserConstraintContract.buildPlanMetaContract(text));
            }
            if (reconciled.get("building_class") != null) {
                meta.put("building_class", reconciled.get("building_class"));
            }
            LlmPlan withIntent = withMeta(plan, meta);
            // Seals and height writes require FormaRequest text; echo only preserves prior seals.
            withIntent = applyServerConstraintsToComponents(
                    withIntent, authorizeFromText ? text : "", authorizeFromText);
            return OpeningConstraintProvenance.stamp(withIntent);
        }
        if (!authorizeFromText) {
            // No server text and no prior request_intent: preserve existing valid seals only.
            return applyServerConstraintsToComponents(plan, "", false);
        }
        Map<String, Object> keyword = extractKeyword(text);
        Map<String, Object> intent = mergeWithLlmDeclared(keyword, meta);
        int explicitH = com.formacraft.common.typology.UserConstraintContract
                .parseExplicitHeightBlocks(text);
        if (explicitH > 0) {
            intent.put("height_explicit", Boolean.TRUE);
            intent.put("explicit_height_blocks", explicitH);
        }
        meta.put("request_intent", intent);
        meta.put("request_intent_text", text);
        meta.put(com.formacraft.common.typology.UserConstraintContract.PLAN_META_KEY,
                com.formacraft.common.typology.UserConstraintContract.buildPlanMetaContract(text));
        meta.put("building_class", intent.get("building_class"));
        LlmPlan withIntent = withMeta(plan, meta);
        withIntent = applyServerConstraintsToComponents(withIntent, text, true);
        return OpeningConstraintProvenance.stamp(withIntent);
    }

    /**
     * Strip forged verification markers, then bind server-authoritative heights and local design
     * omissions onto host masses. Seals are issued only when {@code authorizeFromText} is true
     * (FormaRequest-backed text). Compile-time {@code ensure(plan, null)} preserves prior seals.
     */
    static LlmPlan applyServerConstraintsToComponents(LlmPlan plan, String userText) {
        return applyServerConstraintsToComponents(plan, userText, userText != null && !userText.isBlank());
    }

    static LlmPlan applyServerConstraintsToComponents(
            LlmPlan plan, String userText, boolean authorizeFromText) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) return plan;
        String text = userText == null ? "" : userText;
        // Height + design-omit seals require authorizeFromText (FormaRequest). Echo cannot rewrite geometry.
        List<com.formacraft.common.typology.UserConstraintContract.HeightBinding> bindings =
                authorizeFromText && !text.isBlank()
                        ? com.formacraft.common.typology.UserConstraintContract
                        .parseExplicitHeightBindings(text)
                        : List.of();
        List<com.formacraft.common.typology.UserConstraintContract.DesignOmitBinding> omits =
                authorizeFromText
                        ? com.formacraft.common.typology.UserConstraintContract
                        .parseDesignOmitBindings(text)
                        : List.of();
        String primary = BuildingHostResolver.primaryMassId(plan, plan.components());
        List<Component> hosts = new ArrayList<>();
        for (Component c : plan.components()) {
            if (BuildingHostResolver.isHostMass(c)) hosts.add(c);
        }
        boolean multi = hosts.size() > 1;
        HeightBindResult heightResult = resolveHeightBindings(hosts, primary, multi, bindings);
        Map<String, Integer> heightByMassId = heightResult.byMassId();
        OmitBindResult omitResult = resolveDesignOmitBindings(plan, hosts, primary, multi, omits);
        Map<String, Set<String>> omitByMassId = omitResult.byMassId();
        if (authorizeFromText && (heightResult.capabilityGap() || omitResult.capabilityGap()
                || !heightResult.diagnostics().isEmpty() || !omitResult.diagnostics().isEmpty())) {
            Map<String, Object> meta = plan.planMeta() == null
                    ? new HashMap<>()
                    : new HashMap<>(plan.planMeta());
            @SuppressWarnings("unchecked")
            List<Object> diags = meta.get("scope_diagnostics") instanceof List<?> raw
                    ? new ArrayList<>((List<Object>) raw)
                    : new ArrayList<>();
            diags.addAll(heightResult.diagnostics());
            diags.addAll(omitResult.diagnostics());
            meta.put("scope_diagnostics", diags);
            plan = withMeta(plan, meta);
            if (heightResult.capabilityGap() || omitResult.capabilityGap()) {
                String code = heightResult.capabilityGap() && omitResult.capabilityGap()
                        ? "E_DESIGN_CONSTRAINT_HOST_SCOPE"
                        : (heightResult.capabilityGap()
                        ? "E_DESIGN_HEIGHT_HOST_SCOPE"
                        : "E_DESIGN_OMIT_HOST_SCOPE");
                List<Map<String, Object>> allDiags = new ArrayList<>();
                allDiags.addAll(heightResult.diagnostics());
                allDiags.addAll(omitResult.diagnostics());
                plan = withCapabilityGap(plan,
                        new com.formacraft.common.llm.dto.CapabilityGap(
                                code,
                                "局部高度或设计省略无法唯一绑定宿主，或与计划目标不一致",
                                "RequestIntentInjector.applyServerConstraintsToComponents",
                                List.of("clarify building host", "use ordinal building_N or unique name"),
                                Map.of("diagnostics", allDiags)));
            }
        }
        List<Component> out = new ArrayList<>(plan.components().size());
        boolean changed = false;
        for (Component c : plan.components()) {
            if (c == null) {
                out.add(null);
                continue;
            }
            String mid = BuildingHostResolver.massId(c);
            Integer boundH = heightByMassId.get(mid);
            Set<String> omitDomains = omitByMassId.getOrDefault(mid, Set.of());
            boolean needHeight = boundH != null && boundH > 0;
            boolean needOmit = !omitDomains.isEmpty();
            boolean hadParams = c.params() != null && !c.params().isEmpty();
            if (!hadParams && !needHeight && !needOmit) {
                out.add(c);
                continue;
            }
            Map<String, Object> params = hadParams ? new HashMap<>(c.params()) : new HashMap<>();
            @SuppressWarnings("unchecked")
            Map<String, Object> priorVerified =
                    (!authorizeFromText
                            && com.formacraft.common.typology.UserConstraintContract.hasServerSeal(params)
                            && params.get(com.formacraft.common.typology.UserConstraintContract
                            .SERVER_VERIFIED_SOURCES_KEY) instanceof Map<?, ?> raw)
                            ? new HashMap<>((Map<String, Object>) raw)
                            : null;
            com.formacraft.common.typology.UserConstraintContract.stripUntrustedMarkers(params);
            com.formacraft.common.llm.dto.Dimensions dims = c.dimensions();
            if (authorizeFromText && needHeight) {
                int explicitH = boundH;
                com.formacraft.common.typology.UserConstraintContract
                        .applyAuthoritativeHeightValue(params, explicitH);
                if (dims == null) {
                    dims = new com.formacraft.common.llm.dto.Dimensions(8, 8, explicitH);
                } else if (dims.height() != explicitH) {
                    dims = new com.formacraft.common.llm.dto.Dimensions(
                            Math.max(1, dims.width()), Math.max(1, dims.depth()), explicitH);
                }
            }
            if (authorizeFromText) {
                for (String domain : omitDomains) {
                    com.formacraft.common.typology.UserConstraintContract
                            .applyVerifiedDesignOmit(params, domain);
                }
            } else if (priorVerified != null) {
                com.formacraft.common.typology.UserConstraintContract
                        .restampPreserved(params, priorVerified);
            }
            boolean paramsChanged = !params.equals(hadParams ? c.params() : Map.of());
            boolean dimsChanged = dims != c.dimensions()
                    && (c.dimensions() == null || dims == null
                    || dims.width() != c.dimensions().width()
                    || dims.depth() != c.dimensions().depth()
                    || dims.height() != c.dimensions().height());
            if (paramsChanged || dimsChanged) {
                changed = true;
                out.add(new Component(
                        c.componentType(), c.slotId(), c.relativePosition(),
                        dims, c.features(), params));
            } else {
                out.add(c);
            }
        }
        return changed ? PlanComponentRewrite.withComponents(plan, out) : plan;
    }

    /**
     * Map mass ids to local design-omit domains using clause role hints.
     * Per-declaration: unique max role score required; ties/unscoped are blocking gaps.
     * Explicit all-mass / multi_named declarations may seal a verified Set of hosts.
     * Python targets are matched per declaration as sets; cannot rescue ties.
     */
    static OmitBindResult resolveDesignOmitBindings(
            LlmPlan plan,
            List<Component> hosts,
            String primary,
            boolean multi,
            List<com.formacraft.common.typology.UserConstraintContract.DesignOmitBinding> bindings
    ) {
        Map<String, Set<String>> out = new HashMap<>();
        List<Map<String, Object>> diagnostics = new ArrayList<>();
        boolean gap = false;
        if (bindings == null || bindings.isEmpty() || hosts == null || hosts.isEmpty()) {
            return new OmitBindResult(out, diagnostics, false);
        }
        for (var b : bindings) {
            if (b == null || b.domain() == null || b.domain().isBlank()) continue;
            String hint = b.roleHint();
            Set<String> javaTargets = new LinkedHashSet<>();
            boolean ambiguous = false;
            if (!multi || hosts.size() == 1) {
                String one = primary != null ? primary : BuildingHostResolver.massId(hosts.getFirst());
                if (one != null) javaTargets.add(one);
            } else if ("all_main_masses".equals(hint)) {
                for (Component host : hosts) {
                    String mid = BuildingHostResolver.massId(host);
                    if (mid != null && !mid.isBlank()) javaTargets.add(mid);
                }
                if (javaTargets.isEmpty()) {
                    ambiguous = true;
                    gap = true;
                    diagnostics.add(Map.of(
                            "code", "E_OMIT_HOST_UNSCOPED",
                            "domain", b.domain(),
                            "roleHint", "all_main_masses",
                            "message", "all-mass omit found no host masses"));
                }
            } else if ("multi_named".equals(hint)) {
                List<String> keys = b.namedKeys() == null ? List.of() : b.namedKeys();
                if (keys.isEmpty()) {
                    ambiguous = true;
                    gap = true;
                    diagnostics.add(Map.of(
                            "code", "E_OMIT_HOST_UNSCOPED",
                            "domain", b.domain(),
                            "roleHint", "multi_named",
                            "message", "multi_named omit missing named_keys"));
                } else {
                    for (String key : keys) {
                        String id = uniqueNamedKeyHostId(hosts, key);
                        if (id == null) {
                            ambiguous = true;
                            gap = true;
                            diagnostics.add(Map.of(
                                    "code", "E_OMIT_HOST_TIE",
                                    "domain", b.domain(),
                                    "roleHint", "multi_named",
                                    "namedKey", key,
                                    "sourceText", b.sourceText() == null ? "" : b.sourceText(),
                                    "message", "multi_named omit subject is not unique"));
                            javaTargets.clear();
                            break;
                        }
                        javaTargets.add(id);
                    }
                    if (!ambiguous && javaTargets.size() != keys.size()) {
                        ambiguous = true;
                        gap = true;
                        javaTargets.clear();
                    }
                }
            } else if (hint != null && hint.startsWith("building_")) {
                String javaTarget = resolveOrdinalOmitHost(hosts, primary, hint);
                if (javaTarget == null) {
                    ambiguous = true;
                    gap = true;
                    diagnostics.add(Map.of(
                            "code", "E_OMIT_HOST_TIE",
                            "domain", b.domain(),
                            "roleHint", hint,
                            "message", "ordinal omit host is not unique"));
                } else {
                    javaTargets.add(javaTarget);
                }
            } else if (hint != null && !hint.isBlank()) {
                String javaTarget = uniqueRoleHostId(hosts, hint);
                if (javaTarget == null) {
                    ambiguous = true;
                    gap = true;
                    diagnostics.add(Map.of(
                            "code", "E_OMIT_HOST_TIE",
                            "domain", b.domain(),
                            "roleHint", hint,
                            "sourceText", b.sourceText() == null ? "" : b.sourceText(),
                            "message", "omit host role score is not unique"));
                } else {
                    javaTargets.add(javaTarget);
                }
            } else {
                ambiguous = true;
                gap = true;
                diagnostics.add(Map.of(
                        "code", "E_OMIT_HOST_UNSCOPED",
                        "domain", b.domain(),
                        "sourceText", b.sourceText() == null ? "" : b.sourceText(),
                        "message", "multi-mass omit without role/ordinal/quantifier scope"));
            }
            // Text ambiguity: never stamp and never accept plan-supplied target_components.
            if (ambiguous) {
                continue;
            }
            Set<String> pythonTargets = pythonOmitTargetsForDeclaration(plan, b);
            Set<String> targets = javaTargets;
            if (!pythonTargets.isEmpty()) {
                if (pythonTargets.equals(javaTargets)) {
                    targets = javaTargets;
                } else {
                    targets = Set.of();
                    gap = true;
                    Map<String, Object> detail = new HashMap<>();
                    detail.put("code", "E_OMIT_HOST_MISMATCH");
                    detail.put("domain", b.domain());
                    detail.put("sourceText", b.sourceText() == null ? "" : b.sourceText());
                    detail.put("pythonTargets", List.copyOf(pythonTargets));
                    detail.put("javaTargets", List.copyOf(javaTargets));
                    detail.put("message", "python and java omit hosts disagree for this declaration");
                    diagnostics.add(detail);
                }
            }
            for (String target : targets) {
                if (target == null || target.isBlank()) continue;
                out.computeIfAbsent(target, k -> new HashSet<>()).add(b.domain());
            }
        }
        return new OmitBindResult(out, diagnostics, gap);
    }

    /** Resolve a Python/Java named building key to a unique host id. */
    private static String uniqueNamedKeyHostId(List<Component> hosts, String namedKey) {
        if (namedKey == null || namedKey.isBlank()) return null;
        String role = switch (namedKey.trim().toLowerCase(Locale.ROOT)) {
            case "main_hall", "main_body", "keep", "residence", "office" -> "hall";
            case "pavilion", "wing" -> "pavilion";
            case "tower" -> "tower";
            default -> null;
        };
        if (role != null) {
            String byRole = uniqueRoleHostId(hosts, role);
            if (byRole != null) return byRole;
        }
        String key = namedKey.trim().toLowerCase(Locale.ROOT);
        List<String> hits = new ArrayList<>();
        for (Component host : hosts) {
            String mid = BuildingHostResolver.massId(host);
            if (mid == null) continue;
            String blob = (mid + " " + (host.slotId() == null ? "" : host.slotId())).toLowerCase(Locale.ROOT);
            if (host.params() != null) {
                for (String k : List.of("component_id", "building_id", "building_role", "buildingRole")) {
                    Object v = host.params().get(k);
                    if (v != null) blob += " " + v;
                }
            }
            blob = blob.toLowerCase(Locale.ROOT);
            if (mid.equalsIgnoreCase(namedKey) || blob.contains(key)) {
                hits.add(mid);
            }
        }
        return hits.size() == 1 ? hits.getFirst() : null;
    }

    /** Unique role-score winner; ties return null (order must not decide). */
    private static String uniqueRoleHostId(List<Component> hosts, String hint) {
        Component best = null;
        int bestScore = 0;
        boolean tie = false;
        for (Component host : hosts) {
            int score = heightRoleScore(host, hint);
            if (score > bestScore) {
                bestScore = score;
                best = host;
                tie = false;
            } else if (score > 0 && score == bestScore) {
                tie = true;
            }
        }
        if (tie || best == null || bestScore <= 0) return null;
        return BuildingHostResolver.massId(best);
    }

    /**
     * Targets for the same player omit declaration as {@code binding} (by source span / scope / role).
     * Never aggregates every roof/window omit in the plan — that false-conflicts multi-declaration texts.
     * Classification: HARD_CONSTRAINT cross-check only; cannot authorize seals by itself.
     */
    @SuppressWarnings("unchecked")
    private static Set<String> pythonOmitTargetsForDeclaration(
            LlmPlan plan,
            com.formacraft.common.typology.UserConstraintContract.DesignOmitBinding binding
    ) {
        Set<String> out = new HashSet<>();
        if (plan == null || binding == null || binding.domain() == null) return out;
        String prop = switch (binding.domain()) {
            case "roof" -> "roof_type";
            case "window" -> "window_style";
            case "entrance" -> "entrance_type";
            default -> "";
        };
        if (prop.isEmpty()) return out;
        String sourceText = binding.sourceText() == null ? "" : binding.sourceText().trim();
        String hint = binding.roleHint();
        Object hints = plan.proportionHints();
        if (!(hints instanceof Map<?, ?> hintMap)) return out;
        Object contract = hintMap.get("building_contract");
        if (!(contract instanceof Map<?, ?> cMap)) return out;
        Object reqs = cMap.get("requirements");
        if (!(reqs instanceof List<?> list)) return out;
        Map<?, ?> best = null;
        int bestScore = 0;
        for (Object raw : list) {
            if (!(raw instanceof Map<?, ?> req)) continue;
            if (!prop.equals(String.valueOf(req.get("property")))) continue;
            if (!"none".equalsIgnoreCase(String.valueOf(req.get("value")))) continue;
            int score = 0;
            Object st = req.get("source_text");
            if (st instanceof List<?> texts && !sourceText.isEmpty()) {
                for (Object t : texts) {
                    if (t != null && sourceText.equals(String.valueOf(t).trim())) {
                        score += 3;
                        break;
                    }
                }
            }
            Object span = req.get("match_span");
            if (span instanceof List<?> sp && sp.size() >= 2
                    && binding.matchStart() >= 0 && binding.matchEnd() >= 0) {
                try {
                    int a = Integer.parseInt(String.valueOf(sp.get(0)));
                    int b = Integer.parseInt(String.valueOf(sp.get(1)));
                    if (a == binding.matchStart() && b == binding.matchEnd()) score += 4;
                } catch (NumberFormatException ignored) {
                    // ignore malformed spans
                }
            }
            String scope = String.valueOf(req.get("scope") == null ? "" : req.get("scope")).trim();
            if (hint != null && !hint.isBlank() && omitScopeMatchesRole(scope, hint)) {
                score += 2;
            }
            if (score > bestScore) {
                bestScore = score;
                best = req;
            } else if (score > 0 && score == bestScore) {
                best = null; // ambiguous declaration match — do not guess
            }
        }
        if (best == null || bestScore <= 0) return out;
        Object targets = best.get("target_components");
        if (targets instanceof List<?> ids) {
            for (Object id : ids) {
                if (id != null && !String.valueOf(id).isBlank()) {
                    out.add(String.valueOf(id).trim());
                }
            }
        }
        return out;
    }

    /** Map Java omit role hints onto Python named_/building_ scopes for per-declaration match. */
    private static boolean omitScopeMatchesRole(String scope, String roleHint) {
        if (scope == null || roleHint == null) return false;
        String s = scope.toLowerCase(Locale.ROOT);
        String r = roleHint.toLowerCase(Locale.ROOT);
        if (r.startsWith("building_")) {
            return s.equals(r);
        }
        return switch (r) {
            case "hall" -> s.contains("main_hall") || s.contains("hall") || s.contains("main_body")
                    || s.contains("正殿") || s.contains("主殿") || s.equals("named_main_hall");
            case "pavilion" -> s.contains("pavilion") || s.contains("亭") || s.equals("named_pavilion");
            case "tower" -> s.contains("tower") || s.contains("pagoda") || s.contains("塔");
            case "all_main_masses" -> s.equals("all_main_masses") || s.equals("plan");
            case "multi_named" -> s.equals("multi_named");
            default -> false;
        };
    }

    private static String resolveOrdinalOmitHost(
            List<Component> hosts, String primary, String buildingHint) {
        List<String> scoped = new ArrayList<>();
        for (Component host : hosts) {
            if (host.params() == null) continue;
            Object scope = host.params().get("requirement_scope");
            if (scope != null && buildingHint.equals(String.valueOf(scope).trim())) {
                String mid = BuildingHostResolver.massId(host);
                if (mid != null) scoped.add(mid);
            }
        }
        if (scoped.size() == 1) return scoped.getFirst();
        if (scoped.size() > 1) return null; // ambiguous — never first-in-list
        int n;
        try {
            n = Integer.parseInt(buildingHint.substring("building_".length()));
        } catch (NumberFormatException ex) {
            return null;
        }
        if (n == 1 && primary != null) return primary;
        if (n == 2) {
            List<String> secondaries = new ArrayList<>();
            for (Component host : hosts) {
                String mid = BuildingHostResolver.massId(host);
                if (mid != null && (primary == null || !primary.equals(mid))) {
                    secondaries.add(mid);
                }
            }
            return secondaries.size() == 1 ? secondaries.getFirst() : null;
        }
        return null;
    }

    private record OmitBindResult(
            Map<String, Set<String>> byMassId,
            List<Map<String, Object>> diagnostics,
            boolean capabilityGap
    ) {}

    private record HeightBindResult(
            Map<String, Integer> byMassId,
            List<Map<String, Object>> diagnostics,
            boolean capabilityGap
    ) {}

    /**
     * Map mass ids to player height values by unique role/ordinal host.
     * Multi-mass: never default a single height cue onto primary; ties/unscoped → gap.
     * Classification: HARD_CONSTRAINT — no area/order guess for unmatched pavilion cues.
     */
    static HeightBindResult resolveHeightBindings(
            List<Component> hosts,
            String primary,
            boolean multi,
            List<com.formacraft.common.typology.UserConstraintContract.HeightBinding> bindings
    ) {
        Map<String, Integer> out = new HashMap<>();
        List<Map<String, Object>> diagnostics = new ArrayList<>();
        boolean gap = false;
        if (bindings == null || bindings.isEmpty() || hosts == null || hosts.isEmpty()) {
            return new HeightBindResult(out, diagnostics, false);
        }
        if (!multi || hosts.size() == 1) {
            String target = primary;
            if (target == null || target.isBlank()) {
                target = BuildingHostResolver.massId(hosts.getFirst());
            }
            if (target != null) {
                out.put(target, bindings.getFirst().blocks());
            }
            return new HeightBindResult(out, diagnostics, false);
        }
        for (var b : bindings) {
            if (b == null || b.blocks() <= 0) continue;
            String hint = b.roleHint();
            String target = null;
            if (hint == null || hint.isBlank()) {
                gap = true;
                diagnostics.add(Map.of(
                        "code", "E_HEIGHT_HOST_UNSCOPED",
                        "blocks", b.blocks(),
                        "message", "multi-mass height cue without role/ordinal scope"));
                continue;
            }
            if (hint.startsWith("building_")) {
                target = resolveOrdinalOmitHost(hosts, primary, hint);
            } else {
                target = uniqueRoleHostId(hosts, hint);
            }
            if (target == null) {
                gap = true;
                diagnostics.add(Map.of(
                        "code", "E_HEIGHT_HOST_TIE",
                        "blocks", b.blocks(),
                        "roleHint", hint,
                        "message", "height host role score is not unique"));
                continue;
            }
            Integer prior = out.get(target);
            if (prior != null && prior != b.blocks()) {
                gap = true;
                diagnostics.add(Map.of(
                        "code", "E_HEIGHT_HOST_CONFLICT",
                        "target", target,
                        "blocks", b.blocks(),
                        "priorBlocks", prior,
                        "message", "conflicting height cues for the same host"));
                continue;
            }
            out.put(target, b.blocks());
        }
        return new HeightBindResult(out, diagnostics, gap);
    }

    /**
     * Identity-only role score. Never includes {@code componentType} (MASS_MAIN contains "main").
     * Classification: HARD_CONSTRAINT host matching — no DESIGN_OVERRIDE from type enums.
     */
    private static int heightRoleScore(Component host, String roleHint) {
        if (roleHint == null || roleHint.isBlank() || host == null) return 0;
        StringBuilder sb = new StringBuilder();
        if (BuildingHostResolver.massId(host) != null) sb.append(BuildingHostResolver.massId(host)).append(' ');
        if (host.slotId() != null) sb.append(host.slotId()).append(' ');
        if (host.params() != null) {
            for (String k : List.of(
                    "component_id", "building_id", "requirement_scope",
                    "building_role", "buildingRole", "role", "part")) {
                Object v = host.params().get(k);
                if (v != null) sb.append(v).append(' ');
            }
        }
        if (host.features() != null) {
            for (String f : host.features()) {
                if (f != null) sb.append(f).append(' ');
            }
        }
        String lower = sb.toString().toLowerCase(Locale.ROOT);
        boolean pavilionLike = lower.contains("pavilion") || lower.contains("亭")
                || lower.contains("廊") || lower.contains("wing");
        boolean towerLike = lower.contains("tower") || lower.contains("塔")
                || lower.contains("pagoda");
        return switch (roleHint) {
            case "pavilion" -> pavilionLike ? 3 : 0;
            case "hall" -> {
                if (lower.contains("main_hall") || lower.contains("townhall")
                        || lower.contains("hall") || lower.contains("殿")
                        || lower.contains("正房") || lower.contains("主殿")
                        || lower.contains("正殿") || lower.contains("大殿")
                        || lower.contains("主楼") || lower.contains("主厅")) {
                    yield 3;
                }
                // Weak identity only (never componentType): house_main / *_main without pavilion/tower cues.
                if (!pavilionLike && !towerLike
                        && (lower.contains("_main") || lower.contains("house"))) {
                    yield 2;
                }
                yield 0;
            }
            case "tower" -> towerLike ? 3 : 0;
            default -> 0;
        };
    }

    /**
     * Stable digest of constraint-affecting geometry so async precompile caches cannot reuse
     * blocks from a plan that was later rewritten by {@link #ensure}.
     */
    public static String constraintFingerprint(LlmPlan plan) {
        if (plan == null) return "null";
        StringBuilder sb = new StringBuilder(128);
        if (plan.planMeta() != null) {
            Object contract = plan.planMeta().get(
                    com.formacraft.common.typology.UserConstraintContract.PLAN_META_KEY);
            sb.append("c=").append(contract);
            Object ri = plan.planMeta().get("request_intent");
            if (ri instanceof Map<?, ?> map) {
                sb.append("|h=").append(map.get("explicit_height_blocks"));
                sb.append("|cls=").append(map.get("building_class"));
            }
        }
        if (plan.components() != null) {
            for (Component c : plan.components()) {
                if (c == null) continue;
                sb.append('|').append(c.componentType()).append('@').append(c.slotId());
                if (c.dimensions() != null) {
                    sb.append(':').append(c.dimensions().width()).append('x')
                            .append(c.dimensions().depth()).append('x')
                            .append(c.dimensions().height());
                }
                if (c.params() != null) {
                    sb.append('#')
                            .append(c.params().get("height")).append('/')
                            .append(c.params().get("towerHeight")).append('/')
                            .append(c.params().get(
                                    com.formacraft.common.typology.UserConstraintContract.SERVER_SEAL_KEY));
                }
            }
        }
        return Integer.toHexString(sb.toString().hashCode());
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
            List<String> denseEaves = subtypes == null ? List.of()
                    : RequestIntentVocab.stringList(subtypes.getAsJsonArray("dense_eaves_pagoda"));
            boolean isTibetan = false;
            for (String t : tibetan) {
                if (containsIgnoreCase(text, lower, t)) {
                    isTibetan = true;
                    break;
                }
            }
            if (isTibetan) {
                towerSubtype = "tibetan_stupa";
            } else {
                boolean isDenseEaves = false;
                for (String t : denseEaves) {
                    if (containsIgnoreCase(text, lower, t)) {
                        isDenseEaves = true;
                        break;
                    }
                }
                // 楼阁 / bare 塔楼 are tower_like but must NOT default to dense_eaves octagon.
                if (isDenseEaves) {
                    towerSubtype = "dense_eaves_pagoda";
                }
            }
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
        // Whole-building white body (白色藏式佛塔) — shaft material, not door/roof trim.
        if (!materials.containsKey("wall")
                && (text.contains("白色") || lower.contains("white "))
                && (text.contains("佛塔") || text.contains("白塔") || text.contains("古塔")
                || text.contains("宝塔") || lower.contains("stupa") || lower.contains("pagoda"))) {
            materials.put("wall", "minecraft:white_concrete");
        }
        intent.put("materials", materials);
        intent.put("door_width_explicit", doorWidthExplicit);
        intent.put("window_material_explicit", windowMaterialExplicit);
        boolean doorPresenceExplicit = detectDoorPresenceExplicit(text, lower);
        boolean upperEntranceExplicit = detectUpperEntranceExplicit(text, lower);
        boolean upperEntranceRequiresAccess = upperEntranceExplicit
                && detectUpperEntranceAccessCue(text, lower);
        intent.put("door_presence_explicit", doorPresenceExplicit);
        intent.put("upper_entrance_explicit", upperEntranceExplicit);
        intent.put("upper_entrance_requires_access", upperEntranceRequiresAccess);
        intent.put("openings", Map.of(
                "door_width_explicit", doorWidthExplicit,
                "window_material_explicit", windowMaterialExplicit,
                "door_presence_explicit", doorPresenceExplicit,
                "upper_entrance_explicit", upperEntranceExplicit,
                "upper_entrance_requires_access", upperEntranceRequiresAccess
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

    /**
     * Player asked for an entrance that sits on the upper storey (not merely a
     * multi-storey building that also has a ground door somewhere in the prompt).
     */
    private static boolean detectUpperEntranceExplicit(String text, String lower) {
        if (text == null || text.isBlank()) return false;
        // Tight phrases first — avoid 「两层…一层入口」false positives.
        if (containsAny(text, lower, List.of(
                "二层入口", "二楼入口", "上层入口", "楼上入口", "楼上的门",
                "阳台门", "露台门", "露台入口", "阳台入口",
                "second floor entrance", "second-floor entrance",
                "upper floor entrance", "upper-floor entrance",
                "upper storey entrance", "upper-storey entrance",
                "balcony door", "balcony entrance"))) {
            return true;
        }
        // 「二层设置一个…入口」/「入口设在二层」— storey and entrance within a short clause.
        return UPPER_STOREY_ENTRANCE_CLAUSE.matcher(text).find()
                || UPPER_STOREY_ENTRANCE_CLAUSE.matcher(lower).find();
    }

    /** Player required the upper entrance to connect via stairs / bridge / balcony. */
    private static boolean detectUpperEntranceAccessCue(String text, String lower) {
        if (text == null || text.isBlank()) return false;
        return text.contains("楼梯") || text.contains("廊桥") || text.contains("阳台")
                || text.contains("平台") || lower.contains("stair") || lower.contains("balcony")
                || lower.contains("bridge") || lower.contains("platform");
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
     * Overlay keyword-extracted fields from server user text onto an existing intent.
     * <p>
     * Explicit literals (materials, footprint, roof forbids, openings) remain authoritative.
     * Strong keyword {@code building_class} is treated as a USER_EXPLICIT marker extract from
     * the player text (repairs wrong LLM class / fills generic). Weak hits cannot demote a
     * confident LLM class. Scaffold templates still require keyword agreement separately.
     */
    static void applyKeywordAuthority(Map<String, Object> intent, Map<String, Object> keyword) {
        applyKeywordAuthority(intent, keyword, true);
    }

    static void applyKeywordAuthority(
            Map<String, Object> intent, Map<String, Object> keyword, boolean clearStaleHardConstraints
    ) {
        if (intent == null || keyword == null) return;
        if (clearStaleHardConstraints) {
            // Drop LLM-guessed hard sizes when the player did not specify them.
            if (!keyword.containsKey("footprint") || keyword.get("footprint") == null) {
                intent.remove("footprint");
            }
        }
        String kwClass = String.valueOf(keyword.getOrDefault("building_class", "generic"));
        String haveClass = String.valueOf(intent.getOrDefault("building_class", "generic"));
        double kwConf = 0.0;
        try {
            Object c = keyword.get("confidence");
            if (c instanceof Number n) kwConf = n.doubleValue();
        } catch (Exception ignored) {
            kwConf = 0.0;
        }
        // Only keyword_override_min (default 0.8) may replace LLM class — weak hits (0.45) cannot.
        double kwOverride = RequestIntentVocab.conf("keyword_override_min", 0.8);
        boolean keywordStrong = !"generic".equals(kwClass) && kwConf >= kwOverride;
        if (keywordStrong && !kwClass.equalsIgnoreCase(haveClass)) {
            intent.put("intent_structure_mismatch", Boolean.TRUE);
            intent.put("stale_building_class", haveClass);
        }
        for (String key : List.of(
                "composition", "markers", "materials",
                "footprint", "roof_type", "roof_forbids", "forbids",
                "roof_explicit", "door_width_explicit", "window_material_explicit",
                "door_presence_explicit", "upper_entrance_explicit",
                "upper_entrance_requires_access", "openings",
                "refuse_residential_autofill", "scaffold_eligible", "source")) {
            if (!keyword.containsKey(key)) continue;
            Object v = keyword.get(key);
            if (v != null) intent.put(key, v);
        }
        // Strong keyword class wins as user-marker extract; weak/generic cannot demote LLM.
        if (keywordStrong) {
            intent.put("building_class", kwClass);
            intent.put("building_class_source",
                    "generic".equalsIgnoreCase(haveClass) ? "keyword_fill" : "keyword_user_marker");
            if (keyword.get("tower_subtype") != null) {
                intent.put("tower_subtype", keyword.get("tower_subtype"));
            }
            if (keyword.get("confidence") != null) {
                intent.put("confidence", keyword.get("confidence"));
            }
        } else if ("generic".equals(kwClass) && !"generic".equalsIgnoreCase(haveClass)) {
            intent.put("building_class_source", "llm_retained");
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
            out.put("building_class_source",
                    "generic".equals(llmClass) ? "keyword_fill" : "keyword_user_marker");
            out.put("llm_declared", declared);
            if (!"generic".equals(llmClass) && !kwClass.equalsIgnoreCase(llmClass)) {
                out.put("intent_structure_mismatch", Boolean.TRUE);
                out.put("stale_building_class", llmClass);
            }
        } else if (llmConf >= llmMin && isValidClass(llmClass)) {
            out.put("building_class", llmClass);
            out.put("confidence", Math.max(llmConf, kwConf * 0.5));
            out.put("source", "llm_declared");
            out.put("building_class_source", "llm_retained");
            out.put("keyword_hint_class", kwClass);
            out.put("keyword_hint_confidence", kwConf);
            double scaffoldMin = RequestIntentVocab.conf("scaffold_min", 0.75);
            double finalConf = ((Number) out.get("confidence")).doubleValue();
            out.put("refuse_residential_autofill",
                    !"generic".equals(llmClass) && finalConf >= scaffoldMin);
            // Scaffold templates require keyword agreement — LLM-only class must not template-replace.
            out.put("scaffold_eligible",
                    !"generic".equals(llmClass)
                            && kwClass.equalsIgnoreCase(llmClass)
                            && kwConf >= scaffoldMin);
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
            } else if (chosen == null || roofSpecificity(canonical) > roofSpecificity(chosen)) {
                // Compounds mix roofs (歇山+双坡 / 穹顶+平顶). Longer LLM feature phrases like
                // "gable_roof" / "flat roof" must not beat shorter architecture tokens 歇山/穹顶.
                chosen = canonical;
            }
        }
        return new RoofResult(chosen, forbidden);
    }

    /**
     * Higher = preferred when multiple roof phrases co-occur. Hall forms (歇山) beat
     * pavilion 攒尖, and both beat generic wing/skirt flat or gable mentions from LLM features.
     */
    static int roofSpecificity(String canonical) {
        if (canonical == null || canonical.isBlank()) return 0;
        String c = canonical.trim().toLowerCase(Locale.ROOT);
        if (c.contains("double_eave")) return 100;
        // Primary Chinese hall roofs outrank accessory pavilion pyramids in compounds.
        if (c.contains("xieshan") || c.contains("hip_gable") || c.contains("irimoya")) return 90;
        if (c.contains("xuanshan") || c.contains("yingshan") || c.contains("wudian")
                || "hip".equals(c)) {
            return 85;
        }
        // Landmark domes beat flat skirts; stay below hall xieshan so courtyard+亭 stays hall-led.
        if (c.contains("dome") || c.contains("onion") || c.contains("mansard")) return 80;
        if (c.contains("pyramid") || c.contains("cone")) return 60;
        if (c.contains("gable") || c.contains("shed") || c.contains("gambrel") || c.contains("sawtooth")) {
            return 40;
        }
        if ("flat".equals(c) || "none".equals(c)) return 10;
        return 50;
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
        // Demote temple only when hall/temple tokens are locally negated (不要做成…大殿).
        // Do not demote when keywords simply miss an uncommon type (清真寺) that LLM classified.
        Object cls = intent.get("building_class");
        if ("temple".equals(String.valueOf(cls))
                && "generic".equals(String.valueOf(keyword.getOrDefault("building_class", "generic")))) {
            boolean negatedHall = false;
            for (String token : List.of("大殿", "殿堂", "正殿", "寺庙", "庙宇", "道观")) {
                int idx = text.indexOf(token);
                if (idx >= 0 && isNegatedAt(text, idx)) {
                    negatedHall = true;
                    break;
                }
            }
            if (negatedHall) {
                intent.put("building_class", "generic");
                intent.put("confidence", keyword.getOrDefault("confidence", 0.0));
                intent.put("refuse_residential_autofill", false);
                intent.put("scaffold_eligible", false);
            }
        }
        // LLM often labels 高密住宅 / apartment as tower_like+dense_eaves — demote when the
        // user text is clearly residential and lacks pagoda/stupa markers.
        if ("tower_like".equals(String.valueOf(cls))
                && looksResidentialWithoutPagoda(text)) {
            intent.put("building_class", "generic");
            intent.put("tower_subtype", null);
            intent.put("confidence", keyword.getOrDefault("confidence", 0.0));
            intent.put("refuse_residential_autofill", false);
            intent.put("scaffold_eligible", false);
            intent.put("building_class_source", "residential_override");
        }
    }

    /** True for 住宅/apartment prompts that are not 密檐/宝塔/佛塔. */
    private static boolean looksResidentialWithoutPagoda(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        boolean residential = containsIgnoreCase(text, lower, "住宅")
                || containsIgnoreCase(text, lower, "公寓")
                || containsIgnoreCase(text, lower, "residence")
                || containsIgnoreCase(text, lower, "apartment")
                || containsIgnoreCase(text, lower, "apartment block")
                || containsIgnoreCase(text, lower, "高密住宅")
                || containsIgnoreCase(text, lower, "别墅")
                || containsIgnoreCase(text, lower, "villa")
                || containsIgnoreCase(text, lower, "house");
        if (!residential) return false;
        return !(containsIgnoreCase(text, lower, "密檐")
                || containsIgnoreCase(text, lower, "宝塔")
                || containsIgnoreCase(text, lower, "古塔")
                || containsIgnoreCase(text, lower, "佛塔")
                || containsIgnoreCase(text, lower, "pagoda")
                || containsIgnoreCase(text, lower, "stupa")
                || containsIgnoreCase(text, lower, "舍利塔"));
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

    private static LlmPlan withCapabilityGap(
            LlmPlan plan, com.formacraft.common.llm.dto.CapabilityGap gap) {
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
                "capability_gap",
                plan.error(),
                gap,
                plan.playerFidelityNoticeZh(),
                plan.distinguishingFeatures(),
                plan.enrichmentGuard(),
                plan.planMeta(),
                plan.designSources());
    }

    private record ScoredClass(String cls, double confidence, List<String> markers) {}
    private record RoofResult(String roofType, List<String> forbids) {}
}
