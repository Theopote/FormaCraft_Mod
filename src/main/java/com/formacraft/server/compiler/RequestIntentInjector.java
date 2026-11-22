package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.model.request.FormaRequest;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
    /** Per-mass shaft envelope target (player height for this stacked family). */
    public static final String SHAFT_ENVELOPE_TARGET_KEY = "_fc_shaft_envelope_target";
    /** Stacked family stem (e.g. pagoda) owning {@link #SHAFT_ENVELOPE_TARGET_KEY}. */
    public static final String SHAFT_ENVELOPE_FAMILY_KEY = "_fc_shaft_envelope_family";
    /**
     * Server-stamped envelope semantics: {@code shaft} (MASS only) or {@code building}
     * (shaft + roofs/finials). Never trust an LLM-forged value without the height seal.
     */
    public static final String ENVELOPE_MODE_KEY = "_fc_envelope_mode";
    /** plan_meta list of {family, mass_ids, target_height, mode}. */
    public static final String STACKED_ENVELOPE_TARGETS_META = "stacked_envelope_targets";
    /** Opaque server seal for {@link #STACKED_ENVELOPE_TARGETS_META} rows. */
    public static final String STACKED_ENVELOPE_TARGETS_SEAL_META = "stacked_envelope_targets_seal";

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
        List<com.formacraft.common.typology.UserConstraintContract.StairTreadBinding> stairTreads =
                authorizeFromText
                        ? com.formacraft.common.typology.UserConstraintContract
                        .parseExplicitStairTreadBindings(text)
                        : List.of();
        List<Component> hosts = new ArrayList<>();
        for (Component c : plan.components()) {
            // Design seals only on building-level masses — never plates / attached / bare STRUCTURE.
            if (BuildingHostResolver.isDesignConstraintHost(c)) hosts.add(c);
        }
        // Rank primary only among design-constraint hosts so plates never steal unscoped seals.
        String primary = BuildingHostResolver.primaryMassId(plan, hosts);
        String declaredPrimary = BuildingHostResolver.declaredPrimaryBuildingId(plan);
        // Stacked pagoda_tier_* count as one building entity — not multi-building ambiguity.
        boolean multi = distinctBuildingEntityCount(hosts) > 1;
        HeightBindResult heightResult = resolveHeightBindings(hosts, primary, multi, bindings);
        // Mutable: envelope-scaled storey heights must re-enter the seal pass below.
        Map<String, Integer> heightByMassId = new HashMap<>(heightResult.byMassId());
        OmitBindResult omitResult = resolveDesignOmitBindings(plan, hosts, primary, multi, omits);
        Map<String, Set<String>> omitByMassId = omitResult.byMassId();
        List<Map<String, Object>> primaryDiags = new ArrayList<>();
        if (authorizeFromText && declaredPrimary != null
                && !BuildingHostResolver.isKnownDesignHostId(hosts, declaredPrimary)
                && (!bindings.isEmpty() || !omits.isEmpty())) {
            Map<String, Object> detail = new HashMap<>();
            detail.put("code", "E_PRIMARY_HOST_MISSING");
            detail.put("declared", declaredPrimary);
            detail.put("recoveredPrimary", primary == null ? "" : primary);
            detail.put("message", "primary_building_id is missing from design-constraint hosts");
            primaryDiags.add(detail);
        }
        List<Map<String, Object>> envelopeDiags = new ArrayList<>();
        boolean envelopeGap = false;
        List<Component> working = plan.components();
        if (authorizeFromText && !heightResult.envelopes().isEmpty()) {
            EnvelopeApplyResult envApplied = applyStackedHeightEnvelopes(
                    plan, working, hosts, heightResult.envelopes(), text);
            working = envApplied.components();
            envelopeDiags.addAll(envApplied.diagnostics());
            envelopeGap = envApplied.capabilityGap();
            if (envApplied.changed()) {
                plan = PlanComponentRewrite.withComponents(plan, working);
            }
            // Seal storey heights even when geometry is unchanged (matched envelope), so
            // FloorLayoutOverflowRepair cannot quietly re-grow the player whole-building height.
            if (envApplied.sealedHeights() != null && !envApplied.sealedHeights().isEmpty()) {
                heightByMassId.putAll(envApplied.sealedHeights());
            }
            // Per-building targets — never a single global height for every tower.
            if (envApplied.envelopeTargetByMass() != null && !envApplied.envelopeTargetByMass().isEmpty()) {
                plan = withStackedEnvelopeTargetsMeta(
                        plan,
                        envApplied.envelopeTargetByMass(),
                        envApplied.envelopeFamilyByMass(),
                        envApplied.envelopeModeByMass());
            }
        }
        StairTreadBindResult stairResult = resolveStairTreadBindings(plan, stairTreads, authorizeFromText);
        if (authorizeFromText && (heightResult.capabilityGap() || omitResult.capabilityGap() || envelopeGap
                || stairResult.capabilityGap()
                || !heightResult.diagnostics().isEmpty() || !omitResult.diagnostics().isEmpty()
                || !stairResult.diagnostics().isEmpty()
                || !envelopeDiags.isEmpty() || !primaryDiags.isEmpty())) {
            Map<String, Object> meta = plan.planMeta() == null
                    ? new HashMap<>()
                    : new HashMap<>(plan.planMeta());
            @SuppressWarnings("unchecked")
            List<Object> diags = meta.get("scope_diagnostics") instanceof List<?> raw
                    ? new ArrayList<>((List<Object>) raw)
                    : new ArrayList<>();
            diags.addAll(primaryDiags);
            diags.addAll(heightResult.diagnostics());
            diags.addAll(envelopeDiags);
            diags.addAll(omitResult.diagnostics());
            diags.addAll(stairResult.diagnostics());
            meta.put("scope_diagnostics", diags);
            plan = withMeta(plan, meta);
            if (heightResult.capabilityGap() || omitResult.capabilityGap() || envelopeGap
                    || stairResult.capabilityGap()) {
                List<Map<String, Object>> allDiags = new ArrayList<>();
                allDiags.addAll(heightResult.diagnostics());
                allDiags.addAll(envelopeDiags);
                allDiags.addAll(omitResult.diagnostics());
                allDiags.addAll(stairResult.diagnostics());
                boolean heightConflict = allDiags.stream().anyMatch(d ->
                        d != null && "E_HEIGHT_HOST_CONFLICT".equals(String.valueOf(d.get("code"))));
                boolean stairScope = stairResult.capabilityGap()
                        && !heightResult.capabilityGap() && !omitResult.capabilityGap() && !envelopeGap;
                String code = envelopeGap
                        ? "E_STACKED_HEIGHT_UNSUPPORTED"
                        : (heightConflict
                        ? "E_HEIGHT_HOST_CONFLICT"
                        : (stairScope
                        ? "E_STAIR_TREAD_HOST_SCOPE"
                        : (heightResult.capabilityGap() && omitResult.capabilityGap()
                        ? "E_DESIGN_CONSTRAINT_HOST_SCOPE"
                        : (heightResult.capabilityGap()
                        ? "E_DESIGN_HEIGHT_HOST_SCOPE"
                        : "E_DESIGN_OMIT_HOST_SCOPE"))));
                plan = withCapabilityGap(plan,
                        new com.formacraft.common.llm.dto.CapabilityGap(
                                code,
                                heightConflict
                                        ? "同一建筑存在互相冲突的高度硬约束"
                                        : (envelopeGap
                                        ? "堆叠塔楼整体高度无法在保留分层关系下执行"
                                        : (stairScope
                                        ? "明确的楼梯踏面材料无法唯一绑定到楼梯构件或 STAIR_SYSTEM 操作"
                                        : "局部高度或设计省略无法唯一绑定宿主，或与计划目标不一致")),
                                "RequestIntentInjector.applyServerConstraintsToComponents",
                                List.of(stairScope
                                        ? "keep a single stair target or name the flight uniquely"
                                        : "clarify building host",
                                        "use ordinal building_N or unique name"),
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
            // Never seal accessories — even when component_id collides with a design host.
            boolean designHost = BuildingHostResolver.isDesignConstraintHost(c);
            Integer boundH = designHost ? heightByMassId.get(mid) : null;
            Set<String> omitDomains = designHost
                    ? omitByMassId.getOrDefault(mid, Set.of())
                    : Set.of();
            boolean needHeight = boundH != null && boundH > 0;
            boolean needOmit = !omitDomains.isEmpty();
            boolean hadParams = c.params() != null && !c.params().isEmpty();
            if (!hadParams && !needHeight && !needOmit) {
                out.add(c);
                continue;
            }
            Map<String, Object> params = hadParams ? new HashMap<>(c.params()) : new HashMap<>();
            // Capture verified keys BEFORE strip — envelope stamps from applyStackedHeightEnvelopes
            // in this same ensure() must survive the final height/omit restamp.
            @SuppressWarnings("unchecked")
            Map<String, Object> priorVerified =
                    (com.formacraft.common.typology.UserConstraintContract.hasServerSeal(params)
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
            }
            // Always re-mint preserved keys (height + envelope family/target/mode) after strip.
            // authorizeFromText height/omit stamps merge via stampVerified when seal is valid.
            if (priorVerified != null) {
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
        if (changed) {
            plan = PlanComponentRewrite.withComponents(plan, out);
        }
        // Stair tread seals run after height/omit strip+restamp so nested op seals survive.
        return applyStairTreadSeals(plan, stairResult, authorizeFromText);
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
        if (bindings == null || bindings.isEmpty()) {
            return new OmitBindResult(out, diagnostics, false);
        }
        if (hosts == null || hosts.isEmpty()) {
            for (var b : bindings) {
                if (b == null || b.domain() == null || b.domain().isBlank()) continue;
                gap = true;
                Map<String, Object> detail = new HashMap<>();
                detail.put("code", "E_DESIGN_HOST_MISSING");
                detail.put("domain", b.domain());
                detail.put("sourceText", b.sourceText() == null ? "" : b.sourceText());
                detail.put("message", "explicit design omit with no design-constraint host");
                diagnostics.add(detail);
            }
            return new OmitBindResult(out, diagnostics, gap);
        }
        for (var b : bindings) {
            if (b == null || b.domain() == null || b.domain().isBlank()) continue;
            String hint = b.roleHint();
            Set<String> javaTargets = new LinkedHashSet<>();
            boolean ambiguous = false;
            boolean scopedIdentity = hint != null && !hint.isBlank();
            // Unscoped omit may bind the sole building body; named/ordinal/multi always verify identity.
            if (!scopedIdentity) {
                if (!multi || hosts.size() == 1) {
                    String one = primary;
                    if (one == null || !BuildingHostResolver.isKnownDesignHostId(hosts, one)) {
                        one = BuildingHostResolver.massId(hosts.getFirst());
                    }
                    if (one != null && BuildingHostResolver.isKnownDesignHostId(hosts, one)) {
                        javaTargets.add(one);
                    } else {
                        ambiguous = true;
                        gap = true;
                        diagnostics.add(Map.of(
                                "code", "E_OMIT_HOST_MISSING",
                                "domain", b.domain(),
                                "message", "omit target is not a design-constraint host"));
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
                        List<String> ids = namedKeyHostIds(hosts, key, b.sourceText());
                        if (ids.isEmpty()) {
                            ambiguous = true;
                            gap = true;
                            Map<String, Object> detail = new HashMap<>();
                            detail.put("code", "E_OMIT_HOST_TIE");
                            detail.put("domain", b.domain());
                            detail.put("roleHint", "multi_named");
                            detail.put("namedKey", key);
                            detail.put("sourceText", b.sourceText() == null ? "" : b.sourceText());
                            detail.put("message", "multi_named omit subject missing or not unique");
                            diagnostics.add(detail);
                            javaTargets.clear();
                            break;
                        }
                        javaTargets.addAll(ids);
                    }
                }
            } else if (hint.startsWith("building_")) {
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
            } else {
                // namedKeys first (wing≠pavilion); roleHint only when no named identity.
                List<String> namedHits = resolveNamedOrRoleHostIds(
                        hosts, hint, b.namedKeys(), b.sourceText());
                if (!namedHits.isEmpty()) {
                    javaTargets.addAll(namedHits);
                } else {
                    ambiguous = true;
                    gap = true;
                    diagnostics.add(Map.of(
                            "code", "E_OMIT_HOST_TIE",
                            "domain", b.domain(),
                            "roleHint", hint == null ? "" : hint,
                            "sourceText", b.sourceText() == null ? "" : b.sourceText(),
                            "message", "omit host role score is not unique or missing"));
                }
            }
            // Text ambiguity: never stamp and never accept plan-supplied target_components.
            if (ambiguous) {
                continue;
            }
            PythonOmitLookup pyLookup = pythonOmitTargetsForDeclaration(plan, b);
            Set<String> targets = javaTargets;
            if (pyLookup.contractPresent() && pyLookup.unresolvedDeclaration()) {
                targets = Set.of();
                gap = true;
                Map<String, Object> detail = new HashMap<>();
                detail.put("code", "E_OMIT_PYTHON_TARGET_UNVERIFIED");
                detail.put("domain", b.domain());
                detail.put("sourceText", b.sourceText() == null ? "" : b.sourceText());
                detail.put("message", "building_contract present but omit declaration could not be matched");
                diagnostics.add(detail);
            } else if (!pyLookup.targets().isEmpty()) {
                if (pyLookup.targets().equals(javaTargets)) {
                    targets = javaTargets;
                } else {
                    targets = Set.of();
                    gap = true;
                    Map<String, Object> detail = new HashMap<>();
                    detail.put("code", "E_OMIT_HOST_MISMATCH");
                    detail.put("domain", b.domain());
                    detail.put("sourceText", b.sourceText() == null ? "" : b.sourceText());
                    detail.put("pythonTargets", List.copyOf(pyLookup.targets()));
                    detail.put("javaTargets", List.copyOf(javaTargets));
                    detail.put("message", "python and java omit hosts disagree for this declaration");
                    diagnostics.add(detail);
                }
            }
            for (String target : targets) {
                if (target == null || target.isBlank()) continue;
                if (!BuildingHostResolver.isKnownDesignHostId(hosts, target)) {
                    gap = true;
                    Map<String, Object> detail = new HashMap<>();
                    detail.put("code", "E_OMIT_HOST_MISSING");
                    detail.put("domain", b.domain());
                    detail.put("target", target);
                    detail.put("message", "omit target is not a design-constraint host");
                    diagnostics.add(detail);
                    continue;
                }
                out.computeIfAbsent(target, k -> new HashSet<>()).add(b.domain());
            }
        }
        return new OmitBindResult(out, diagnostics, gap);
    }

    /**
     * Resolve a named building key via identity aliases — never hall-fallback for office/keep.
     * Stacked storeys of one tower ({@code pagoda_tier_1..N}) count as one building entity and
     * return every storey id so omit/height seals cover the whole shaft.
     */
    private static String uniqueNamedKeyHostId(List<Component> hosts, String namedKey) {
        List<String> ids = namedKeyHostIds(hosts, namedKey, null);
        return ids.size() == 1 ? ids.getFirst() : null;
    }

    /**
     * Prefer explicit {@code namedKeys} (wing/office/…). Side-qualified misses stay fail-closed
     * (西厢房 must not bind 东厢房). Class aliases like 主殿→hall may still resolve via role score
     * when the plan used a generic component_id without the alias string.
     * Role-score-only path runs when the request has no named identity.
     * Classification: HARD_CONSTRAINT host identity.
     */
    static List<String> resolveNamedOrRoleHostIds(
            List<Component> hosts,
            String roleHint,
            List<String> namedKeys,
            String clauseText
    ) {
        if (namedKeys != null && !namedKeys.isEmpty()) {
            List<String> namedHits;
            if (namedKeys.size() == 1) {
                namedHits = namedKeyHostIds(hosts, namedKeys.getFirst(), clauseText);
            } else {
                List<String> all = new ArrayList<>();
                for (String key : namedKeys) {
                    List<String> part = namedKeyHostIds(hosts, key, clauseText);
                    if (part.isEmpty()) {
                        return List.of();
                    }
                    for (String id : part) {
                        if (!all.contains(id)) all.add(id);
                    }
                }
                namedHits = List.copyOf(all);
            }
            if (!namedHits.isEmpty()) return namedHits;
            // Side-qualified named miss (西厢房 / 东厢房 / …) — never role-fallback.
            if (isSideQualifiedNamedMiss(namedKeys, clauseText)) {
                return List.of();
            }
            // Class named key present in text but plan ids lack the alias string (house_main vs 主殿).
            return roleScoreHostIds(hosts, roleHint, clauseText);
        }
        return roleScoreHostIds(hosts, roleHint, clauseText);
    }

    /** True when namedKeys include wing and the clause demands a cardinal side. */
    private static boolean isSideQualifiedNamedMiss(List<String> namedKeys, String clauseText) {
        if (namedKeys == null || namedKeys.isEmpty()) return false;
        boolean hasWing = false;
        for (String key : namedKeys) {
            if (key != null && "wing".equalsIgnoreCase(key.trim())) {
                hasWing = true;
                break;
            }
        }
        return hasWing && !wingSidesFromText(clauseText).isEmpty();
    }

    private static List<String> roleScoreHostIds(
            List<Component> hosts, String roleHint, String clauseText) {
        if (roleHint == null || roleHint.isBlank()) return List.of();
        List<String> hits = namedKeyHostIds(hosts, roleHint, clauseText);
        if (!hits.isEmpty()) return hits;
        String roleTarget = uniqueRoleHostId(hosts, roleHint);
        return roleTarget == null ? List.of() : List.of(roleTarget);
    }

    private static List<String> namedKeyHostIds(List<Component> hosts, String namedKey) {
        return namedKeyHostIds(hosts, namedKey, null);
    }

    private static List<String> namedKeyHostIds(List<Component> hosts, String namedKey, String clauseText) {
        if (namedKey == null || namedKey.isBlank()) return List.of();
        String key = namedKey.trim().toLowerCase(Locale.ROOT);
        List<String> aliases = com.formacraft.common.typology.UserConstraintContract.aliasesForNamedKey(key);
        List<String> hits = new ArrayList<>();
        Map<String, Component> byId = new HashMap<>();
        for (Component host : hosts) {
            String mid = BuildingHostResolver.massId(host);
            if (mid == null) continue;
            String blob = hostIdentityBlob(host);
            boolean matched = mid.equalsIgnoreCase(key);
            if (!matched) {
                for (String alias : aliases) {
                    if (alias != null && !alias.isBlank() && blob.contains(alias.toLowerCase(Locale.ROOT))) {
                        matched = true;
                        break;
                    }
                }
            }
            if (matched) {
                hits.add(mid);
                byId.put(mid, host);
            }
        }
        if (hits.isEmpty()) return List.of();
        if ("wing".equals(key)) {
            Set<String> sides = wingSidesFromText(clauseText);
            if (!sides.isEmpty()) {
                List<String> resolved = new ArrayList<>();
                for (String side : sides) {
                    List<String> sided = new ArrayList<>();
                    for (String id : hits) {
                        Component host = byId.get(id);
                        if (host != null && hostMatchesWingSide(hostIdentityBlob(host), side)) {
                            sided.add(id);
                        }
                    }
                    // Each declared side must uniquely bind; empty/multi → ambiguity.
                    if (sided.size() != 1) return List.of();
                    if (!resolved.contains(sided.getFirst())) {
                        resolved.add(sided.getFirst());
                    }
                }
                return List.copyOf(resolved);
            }
            // Plural role-class without side cue — keep every wing.
            return List.copyOf(hits);
        }
        if (hits.size() == 1) return List.copyOf(hits);
        // Stacked storeys of one tower (pagoda_tier_*) count as one building entity.
        if (isStackedFamilyIds(hits)) {
            return List.copyOf(hits);
        }
        // Singular named alias matching unrelated buildings — fail closed.
        return List.of();
    }

    /** {@code pagoda_tier_3} → {@code pagoda}; non-tier ids → null. */
    static String stackedFamilyKey(String componentId) {
        if (componentId == null || componentId.isBlank()) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(.*?)(?:_tier_?|_level_?|_storey_?)(\\d+)$", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(componentId.trim());
        if (!m.matches()) return null;
        String stem = m.group(1);
        return stem == null || stem.isBlank() ? null : stem.toLowerCase(Locale.ROOT);
    }

    static boolean isStackedFamilyIds(List<String> ids) {
        if (ids == null || ids.size() < 2) return false;
        String family = stackedFamilyKey(ids.getFirst());
        if (family == null) return false;
        return ids.stream().allMatch(id -> family.equals(stackedFamilyKey(id)));
    }

    /** Distinct player-facing buildings; stacked storeys of one shaft count as one. */
    static int distinctBuildingEntityCount(List<Component> hosts) {
        if (hosts == null || hosts.isEmpty()) return 0;
        Set<String> entities = new HashSet<>();
        for (Component host : hosts) {
            String mid = BuildingHostResolver.massId(host);
            if (mid == null || mid.isBlank()) continue;
            String family = stackedFamilyKey(mid);
            entities.add(family != null ? "family:" + family : "id:" + mid.toLowerCase(Locale.ROOT));
        }
        return entities.size();
    }

    private static List<String> stackedFamilyMassIds(List<Component> hosts) {
        if (hosts == null || hosts.isEmpty()) return List.of();
        List<String> ids = new ArrayList<>();
        for (Component host : hosts) {
            String mid = BuildingHostResolver.massId(host);
            if (mid != null && !mid.isBlank()) ids.add(mid);
        }
        return isStackedFamilyIds(ids) ? List.copyOf(ids) : List.of();
    }

    /**
     * All wing sides named in the local clause (west/east/south/north).
     * Joint subjects 「西厢房和东厢房都…」return both; first-only matching is rejected.
     */
    static Set<String> wingSidesFromText(String text) {
        if (text == null || text.isBlank()) return Set.of();
        String t = text.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> sides = new LinkedHashSet<>();
        if (t.matches("(?s).*(?:西(?:厢|翼|侧)|west[_\\s-]?wing|\\bwest\\b|左厢).*")) sides.add("west");
        if (t.matches("(?s).*(?:东(?:厢|翼|侧)|east[_\\s-]?wing|\\beast\\b|右厢).*")) sides.add("east");
        if (t.matches("(?s).*(?:南(?:厢|翼|侧)|south[_\\s-]?wing|\\bsouth\\b).*")) sides.add("south");
        if (t.matches("(?s).*(?:北(?:厢|翼|侧)|north[_\\s-]?wing|\\bnorth\\b).*")) sides.add("north");
        return Set.copyOf(sides);
    }

    /** First wing side when exactly one is present; else null (use {@link #wingSidesFromText}). */
    static String wingSideHint(String text) {
        Set<String> sides = wingSidesFromText(text);
        return sides.size() == 1 ? sides.iterator().next() : null;
    }

    static boolean hostMatchesWingSide(String identityBlob, String side) {
        if (identityBlob == null || side == null) return false;
        String blob = identityBlob.toLowerCase(Locale.ROOT);
        return switch (side) {
            case "west" -> blob.contains("west") || blob.contains("西") || blob.contains("左厢")
                    || blob.contains("west_wing") || blob.contains("wing_west");
            case "east" -> blob.contains("east") || blob.contains("东") || blob.contains("右厢")
                    || blob.contains("east_wing") || blob.contains("wing_east");
            case "south" -> blob.contains("south") || blob.contains("南")
                    || blob.contains("south_wing") || blob.contains("wing_south");
            case "north" -> blob.contains("north") || blob.contains("北")
                    || blob.contains("north_wing") || blob.contains("wing_north");
            default -> false;
        };
    }

    /**
     * Proportionally rescale stacked tier heights and relative Y so the authoritative envelope
     * matches the player height. Building mode reserves crown/roof rise above the shaft.
     * Never copies the envelope onto each tier.
     */
    static EnvelopeApplyResult applyStackedHeightEnvelopes(
            List<Component> components,
            List<Component> hosts,
            List<StackedHeightEnvelope> envelopes
    ) {
        return applyStackedHeightEnvelopes(null, components, hosts, envelopes, null);
    }

    static EnvelopeApplyResult applyStackedHeightEnvelopes(
            List<Component> components,
            List<Component> hosts,
            List<StackedHeightEnvelope> envelopes,
            String userText
    ) {
        return applyStackedHeightEnvelopes(null, components, hosts, envelopes, userText);
    }

    static EnvelopeApplyResult applyStackedHeightEnvelopes(
            LlmPlan plan,
            List<Component> components,
            List<Component> hosts,
            List<StackedHeightEnvelope> envelopes,
            String userText
    ) {
        List<Map<String, Object>> diagnostics = new ArrayList<>();
        if (components == null || envelopes == null || envelopes.isEmpty()) {
            return new EnvelopeApplyResult(
                    components == null ? List.of() : components, diagnostics, false, false,
                    Map.of(), Map.of(), Map.of(), Map.of());
        }
        Map<String, Component> hostById = new HashMap<>();
        for (Component host : hosts == null ? List.<Component>of() : hosts) {
            String mid = BuildingHostResolver.massId(host);
            if (mid != null) hostById.put(mid, host);
        }
        Map<String, Integer> newHeight = new HashMap<>();
        Map<String, Integer> newRelY = new HashMap<>();
        Map<String, Integer> sealedHeights = new HashMap<>();
        Map<String, Integer> envelopeTargetByMass = new HashMap<>();
        Map<String, String> envelopeFamilyByMass = new HashMap<>();
        Map<String, String> envelopeModeByMass = new HashMap<>();
        boolean gap = false;
        for (StackedHeightEnvelope env : envelopes) {
            if (env == null || env.massIds() == null || env.massIds().isEmpty() || env.targetHeight() <= 0) {
                continue;
            }
            List<Component> members = new ArrayList<>();
            for (String id : env.massIds()) {
                Component c = hostById.get(id);
                if (c == null || c.dimensions() == null || c.relativePosition() == null) {
                    gap = true;
                    Map<String, Object> detail = new HashMap<>();
                    detail.put("code", "E_STACKED_HEIGHT_UNSUPPORTED");
                    detail.put("target", id == null ? "" : id);
                    detail.put("message", "stacked height envelope missing geometry for a storey");
                    diagnostics.add(detail);
                    members.clear();
                    break;
                }
                members.add(c);
            }
            if (members.isEmpty()) continue;
            int minY = Integer.MAX_VALUE;
            int maxTop = Integer.MIN_VALUE;
            for (Component c : members) {
                int y = c.relativePosition().y();
                int h = Math.max(1, c.dimensions().height());
                minY = Math.min(minY, y);
                maxTop = Math.max(maxTop, y + h);
            }
            int envelope = maxTop - minY;
            if (envelope <= 0) {
                gap = true;
                diagnostics.add(Map.of(
                        "code", "E_STACKED_HEIGHT_UNSUPPORTED",
                        "blocks", env.targetHeight(),
                        "message", "stacked height envelope is non-positive"));
                continue;
            }
            members.sort(Comparator.comparingInt(c -> c.relativePosition().y()));
            String family = env.familyKey() != null && !env.familyKey().isBlank()
                    ? env.familyKey()
                    : stackedFamilyKey(BuildingHostResolver.massId(members.getFirst()));
            if (family == null) family = "stacked";
            // Per-binding mode from the local height clause — never whole-request text.
            String mode = env.mode() == null ? "shaft" : env.mode();
            boolean buildingMode = "building".equals(mode);
            // Building mode: player target is whole building; shaft budget excludes crown rise
            // above the shaft top (existing roofs/finials AND roofs the compiler will infer).
            // Mid-tier eaves below the shaft top must not suppress inferred top-roof budget.
            int crownRise = 0;
            if (buildingMode) {
                int measured = measureCrownRiseAboveShaft(components, members, maxTop);
                int inferred = estimateInferredRoofRise(plan, components, members);
                crownRise = Math.max(measured, inferred);
            }
            int shaftTarget = buildingMode
                    ? env.targetHeight() - crownRise
                    : env.targetHeight();
            if (shaftTarget <= 0) {
                gap = true;
                diagnostics.add(Map.of(
                        "code", "E_STACKED_HEIGHT_UNSUPPORTED",
                        "blocks", env.targetHeight(),
                        "message", "building-height target cannot reserve roof/finial rise"));
                newHeight.clear();
                newRelY.clear();
                break;
            }
            if (envelope == shaftTarget) {
                boolean infeasible = false;
                for (Component c : members) {
                    int h = Math.max(1, c.dimensions().height());
                    int need = authoredFloorNeed(c);
                    if (need > h) {
                        infeasible = true;
                        String mid = BuildingHostResolver.massId(c);
                        diagnostics.add(Map.of(
                                "code", "E_STACKED_HEIGHT_UNSUPPORTED",
                                "target", mid == null ? "" : mid,
                                "blocks", env.targetHeight(),
                                "message", "storey floor bands exceed height under matched envelope"));
                        break;
                    }
                }
                if (infeasible) {
                    gap = true;
                    newHeight.clear();
                    newRelY.clear();
                    break;
                }
                // Keep current shaft H/Y so the rewrite loop still seats enclosed roofs
                // at hostTop-1 (AlignRoofToMass) even when the shaft already matches.
                for (Component c : members) {
                    String mid = BuildingHostResolver.massId(c);
                    if (mid != null) {
                        int curH = Math.max(1, c.dimensions().height());
                        sealedHeights.put(mid, curH);
                        newHeight.put(mid, curH);
                        newRelY.put(mid, c.relativePosition().y());
                        envelopeTargetByMass.put(mid, env.targetHeight());
                        envelopeFamilyByMass.put(mid, family);
                        envelopeModeByMass.put(mid, mode);
                    }
                }
                continue;
            }
            int n = members.size();
            int[] oldHeights = new int[n];
            int[] gaps = new int[n];
            int[] minHeights = new int[n];
            int minRequiredSum = 0;
            for (int i = 0; i < n; i++) {
                Component c = members.get(i);
                oldHeights[i] = Math.max(1, c.dimensions().height());
                minHeights[i] = minFeasibleStoreyHeight(c);
                minRequiredSum += minHeights[i];
                if (i + 1 < n) {
                    int top = c.relativePosition().y() + oldHeights[i];
                    int nextY = members.get(i + 1).relativePosition().y();
                    gaps[i] = Math.max(0, nextY - top);
                } else {
                    gaps[i] = 0;
                }
                if (gaps[i] > 0) {
                    minRequiredSum += 1;
                }
            }
            if (minRequiredSum > shaftTarget) {
                gap = true;
                diagnostics.add(Map.of(
                        "code", "E_STACKED_HEIGHT_UNSUPPORTED",
                        "blocks", env.targetHeight(),
                        "message", "stacked height target cannot fit minimum storey bands and gaps"));
                newHeight.clear();
                newRelY.clear();
                break;
            }
            int[] scaledHeights = new int[n];
            int[] scaledGaps = new int[n];
            if (!allocateStackedShaftWithGaps(
                    oldHeights, gaps, minHeights, shaftTarget, scaledHeights, scaledGaps)) {
                gap = true;
                diagnostics.add(Map.of(
                        "code", "E_STACKED_HEIGHT_UNSUPPORTED",
                        "blocks", env.targetHeight(),
                        "message", "stacked height scale cannot preserve storey bands and gaps"));
                newHeight.clear();
                newRelY.clear();
                break;
            }
            int yCursor = minY;
            for (int i = 0; i < n; i++) {
                Component c = members.get(i);
                String mid = BuildingHostResolver.massId(c);
                if (mid == null) {
                    gap = true;
                    break;
                }
                newHeight.put(mid, scaledHeights[i]);
                sealedHeights.put(mid, scaledHeights[i]);
                envelopeTargetByMass.put(mid, env.targetHeight());
                envelopeFamilyByMass.put(mid, family);
                envelopeModeByMass.put(mid, mode);
                newRelY.put(mid, yCursor);
                yCursor += scaledHeights[i] + scaledGaps[i];
            }
            if (gap) {
                newHeight.clear();
                newRelY.clear();
                sealedHeights.clear();
                envelopeTargetByMass.clear();
                envelopeFamilyByMass.clear();
                envelopeModeByMass.clear();
                break;
            }
        }
        if (gap) {
            return new EnvelopeApplyResult(
                    components, diagnostics, true, false, Map.of(), Map.of(), Map.of(), Map.of());
        }
        if (newHeight.isEmpty() && newRelY.isEmpty() && envelopeTargetByMass.isEmpty()) {
            return new EnvelopeApplyResult(
                    components, diagnostics, false, false,
                    Map.copyOf(sealedHeights), Map.of(), Map.of(), Map.copyOf(envelopeModeByMass));
        }
        Map<String, int[]> oldHostGeom = new HashMap<>();
        for (Component c : components) {
            if (c == null || c.relativePosition() == null || c.dimensions() == null) continue;
            String mid = BuildingHostResolver.massId(c);
            if (mid == null || (!newHeight.containsKey(mid) && !newRelY.containsKey(mid))) continue;
            oldHostGeom.put(mid, new int[]{c.relativePosition().y(), Math.max(1, c.dimensions().height())});
        }
        List<Component> out = new ArrayList<>(components.size());
        boolean changed = false;
        for (Component c : components) {
            if (c == null) {
                out.add(null);
                continue;
            }
            String mid = BuildingHostResolver.massId(c);
            Integer h = mid == null ? null : newHeight.get(mid);
            Integer y = mid == null ? null : newRelY.get(mid);
            Integer envTarget = mid == null ? null : envelopeTargetByMass.get(mid);
            String envFamily = mid == null ? null : envelopeFamilyByMass.get(mid);
            String envMode = mid == null ? null : envelopeModeByMass.get(mid);
            String hostId = satelliteHostId(c);
            int[] oldHost = hostId == null ? null : oldHostGeom.get(hostId);
            Integer hostNewY = hostId == null ? null : newRelY.get(hostId);
            Integer hostNewH = hostId == null ? null : newHeight.get(hostId);
            boolean satelliteShift = oldHost != null
                    && c.relativePosition() != null
                    && (hostNewY != null || hostNewH != null)
                    && h == null && y == null;
            boolean stampEnvelope = envTarget != null;
            if (h == null && y == null && !satelliteShift && !stampEnvelope) {
                out.add(c);
                continue;
            }
            Map<String, Object> params = c.params() == null ? new HashMap<>() : new HashMap<>(c.params());
            com.formacraft.common.llm.dto.Dimensions dims = c.dimensions();
            Vec3i rel = c.relativePosition();
            if (h != null) {
                com.formacraft.common.typology.UserConstraintContract
                        .applyAuthoritativeHeightValue(params, h);
                if (dims == null) {
                    dims = new com.formacraft.common.llm.dto.Dimensions(8, 8, h);
                } else {
                    dims = new com.formacraft.common.llm.dto.Dimensions(
                            Math.max(1, dims.width()), Math.max(1, dims.depth()), h);
                }
            } else if (stampEnvelope && sealedHeights.containsKey(mid)) {
                com.formacraft.common.typology.UserConstraintContract
                        .applyAuthoritativeHeightValue(params, sealedHeights.get(mid));
            }
            if (stampEnvelope) {
                params.put(SHAFT_ENVELOPE_TARGET_KEY, envTarget);
                if (envFamily != null) params.put(SHAFT_ENVELOPE_FAMILY_KEY, envFamily);
                params.put(ENVELOPE_MODE_KEY, envMode == null ? "shaft" : envMode);
                // Seal envelope fields with the same server digest as height — not height alone.
                com.formacraft.common.typology.UserConstraintContract.stampVerified(
                        params,
                        "height", "towerHeight",
                        SHAFT_ENVELOPE_TARGET_KEY, SHAFT_ENVELOPE_FAMILY_KEY, ENVELOPE_MODE_KEY);
            }
            if (y != null && rel != null && rel.y() != y) {
                rel = new Vec3i(rel.x(), y, rel.z());
            } else if (satelliteShift && rel != null && oldHost != null) {
                int newHY = hostNewY != null ? hostNewY : oldHost[0];
                int newHH = hostNewH != null ? hostNewH : oldHost[1];
                Component hostComp = hostId == null ? null : hostById.get(hostId);
                Map<String, Object> hostParams = hostComp != null ? hostComp.params() : null;
                Integer nextY = relocateSatelliteY(c, oldHost, newHY, newHH, hostParams);
                if (nextY == null) {
                    gap = true;
                    Map<String, Object> detail = new HashMap<>();
                    detail.put("code", "E_STACKED_HEIGHT_UNSUPPORTED");
                    detail.put("target", hostId == null ? "" : hostId);
                    detail.put("message", "satellite cannot fit inside scaled host facade");
                    diagnostics.add(detail);
                    break;
                }
                if (nextY != rel.y()) {
                    rel = new Vec3i(rel.x(), nextY, rel.z());
                }
            }
            changed = true;
            out.add(new Component(c.componentType(), c.slotId(), rel, dims, c.features(), params));
        }
        if (gap) {
            return new EnvelopeApplyResult(
                    components, diagnostics, true, false, Map.of(), Map.of(), Map.of(), Map.of());
        }
        return new EnvelopeApplyResult(
                out, diagnostics, false, changed,
                Map.copyOf(sealedHeights),
                Map.copyOf(envelopeTargetByMass),
                Map.copyOf(envelopeFamilyByMass),
                Map.copyOf(envelopeModeByMass));
    }

    /**
     * Prefer per-binding {@link com.formacraft.common.typology.UserConstraintContract#inferEnvelopeMode}.
     * Kept for focused unit tests that pass a single local clause as the whole string.
     */
    static boolean wantsBuildingEnvelope(String userText) {
        return "building".equals(
                com.formacraft.common.typology.UserConstraintContract.inferEnvelopeMode(userText));
    }

    /**
     * Net roof rise the compiler will actually infer on the top storey (0 when it will not).
     * Reuses {@link ComponentSatelliteInference} roof creation rules, including enclosed
     * top-overlap of 1. Mid-tier roofs do not suppress top-storey budget.
     */
    static int estimateInferredRoofRise(List<Component> components, List<Component> shaftMembers) {
        return estimateInferredRoofRise(null, components, shaftMembers);
    }

    static int estimateInferredRoofRise(
            LlmPlan plan, List<Component> components, List<Component> shaftMembers) {
        if (shaftMembers == null || shaftMembers.isEmpty()) return 0;
        // Without compiler satellites, no independent ROOF will be created later.
        if (plan == null || !plan.allowCompilerSatellites()) return 0;
        Component top = shaftMembers.get(shaftMembers.size() - 1);
        if (top == null || top.dimensions() == null || top.relativePosition() == null) return 0;
        String topId = BuildingHostResolver.massId(top);
        // Only an existing top-hosted ROOF covers the inferred-roof budget. Finials / banners
        // (TOP_PROTRUSION) and non-ROOF crowns still count in measureCrownRiseAboveShaft, but must
        // not suppress satellite roof inference (GEOMETRY — matches ComponentSatelliteInference).
        if (components != null && topId != null) {
            for (Component c : components) {
                if (c == null || c.componentType() == null) continue;
                String type = c.componentType().trim().toUpperCase(Locale.ROOT);
                if (!type.startsWith("ROOF")) continue;
                String host = satelliteHostId(c);
                if (topId.equals(host)) return 0;
            }
        }
        return ComponentSatelliteInference.estimatedInferredRoofNetRise(plan, top);
    }

    /**
     * Extra rise of roofs/finials above the current shaft top (0 when none).
     * Each ROOF is seated on its own host storey — mid-tier eaves never invent rise at shaftTop.
     */
    private static int measureCrownRiseAboveShaft(
            List<Component> components, List<Component> shaftMembers, int shaftTop) {
        if (components == null || shaftMembers == null || shaftMembers.isEmpty()) return 0;
        Set<String> shaftIds = new HashSet<>();
        Map<String, Component> shaftById = new HashMap<>();
        for (Component m : shaftMembers) {
            String id = BuildingHostResolver.massId(m);
            if (id != null) {
                shaftIds.add(id);
                shaftById.put(id, m);
            }
        }
        int maxTop = shaftTop;
        for (Component c : components) {
            if (c == null || c.relativePosition() == null || c.dimensions() == null) continue;
            if (!countsTowardBuildingEnvelope(c)) continue;
            String host = satelliteHostId(c);
            String mid = BuildingHostResolver.massId(c);
            boolean attached = (host != null && shaftIds.contains(host))
                    || (mid != null && shaftIds.contains(mid))
                    || familyMatchesShaft(mid, shaftIds)
                    || familyMatchesShaft(host, shaftIds);
            if (!attached) continue;
            int satH = Math.max(1, c.dimensions().height());
            String type = c.componentType() == null ? "" : c.componentType().toUpperCase(Locale.ROOT);
            // AlignRoofToMass / makeRoofComponent: seat on the roof's own host, not shaftTop.
            if (type.startsWith("ROOF")) {
                Component hostMass = host == null ? null : shaftById.get(host);
                if (hostMass != null
                        && hostMass.relativePosition() != null
                        && hostMass.dimensions() != null) {
                    int hostTop = hostMass.relativePosition().y()
                            + Math.max(1, hostMass.dimensions().height());
                    int seatY = roofSeatYOnHost(hostTop, hostMass.params());
                    maxTop = Math.max(maxTop, seatY + satH);
                } else {
                    // Unknown host: trust authored placement; never lift to shaftTop.
                    maxTop = Math.max(maxTop, c.relativePosition().y() + satH);
                }
                continue;
            }
            maxTop = Math.max(maxTop, c.relativePosition().y() + satH);
        }
        return Math.max(0, maxTop - shaftTop);
    }

    /**
     * Roof bearing Y on a host mass. Open-frame uses host MASS params (column tops);
     * enclosed seals at hostTop-1. Matches {@code alignRoofToMass} / {@code makeRoofComponent}.
     */
    static int roofSeatYOnHost(int hostTop, Map<String, Object> hostParams) {
        if (com.formacraft.common.generation.component.util.OpenFramePolicy.isOpen(hostParams)) {
            return hostTop;
        }
        return hostTop - 1;
    }

    private static boolean familyMatchesShaft(String id, Set<String> shaftIds) {
        if (id == null || shaftIds.isEmpty()) return false;
        String fam = stackedFamilyKey(id);
        if (fam == null) return false;
        for (String sid : shaftIds) {
            if (fam.equals(stackedFamilyKey(sid))) return true;
        }
        return false;
    }

    /**
     * Shared crown / top-protrusion classification for budget, relocate, and integrity.
     * Ordinary facade {@code DECOR_DETAIL} is not a crown; roofs/finials and named top
     * protrusions (spire/banner/flagpole) are.
     */
    enum CrownKind { NONE, STRUCTURAL_CROWN, TOP_PROTRUSION }

    static CrownKind classifyCrownAttachment(Component c) {
        if (c == null) return CrownKind.NONE;
        String type = c.componentType() == null ? "" : c.componentType().toUpperCase();
        if (type.contains("WINDOW") || type.contains("FACADE")
                || type.contains("ENTRANCE") || type.contains("DOOR")
                || type.contains("STAIR") || type.contains("OPENING")
                || "FLOOR_SLAB".equals(type) || type.contains("FLOOR")) {
            return CrownKind.NONE;
        }
        if (type.startsWith("ROOF") || type.contains("CROWN") || "FINIAL".equals(type)) {
            return CrownKind.STRUCTURAL_CROWN;
        }
        String mid = BuildingHostResolver.massId(c);
        String lower = mid == null ? "" : mid.toLowerCase(Locale.ROOT);
        boolean namedCrown = lower.contains("roof") || lower.contains("finial")
                || lower.contains("crown") || lower.contains("spire");
        boolean namedProtrusion = lower.contains("banner") || lower.contains("flag")
                || lower.contains("flagpole") || lower.contains("pole")
                || lower.contains("finial") || lower.contains("spire");
        if (namedCrown) return CrownKind.STRUCTURAL_CROWN;
        if ("DECOR_DETAIL".equals(type) || type.contains("DECOR") || type.contains("ORNAMENT")) {
            return namedProtrusion ? CrownKind.TOP_PROTRUSION : CrownKind.NONE;
        }
        return CrownKind.NONE;
    }

    /** True when the component sticks to the host top after shaft scale. */
    static boolean sticksToHostTop(Component c) {
        CrownKind k = classifyCrownAttachment(c);
        return k == CrownKind.STRUCTURAL_CROWN || k == CrownKind.TOP_PROTRUSION;
    }

    /** True when the component contributes to building-mode envelope height. */
    static boolean countsTowardBuildingEnvelope(Component c) {
        return sticksToHostTop(c);
    }

    /**
     * Relocate a satellite after its host height changes.
     * Only true roofs/finials/top protrusions stick to the new top — never openings/stairs
     * or ordinary facade decor, even when start Y is within 1 of the old host top.
     * Returns null when the satellite cannot fit — caller must emit a capability gap.
     */
    static Integer relocateSatelliteY(Component c, int[] oldHost, int newHY, int newHH) {
        return relocateSatelliteY(c, oldHost, newHY, newHH, null);
    }

    /**
     * @param hostParams host MASS params for open/closed seating — never the orphan ROOF's own
     *                   {@code open} flag (alignRoofToMass keys off the mass).
     */
    static Integer relocateSatelliteY(
            Component c, int[] oldHost, int newHY, int newHH, Map<String, Object> hostParams) {
        if (c == null || c.relativePosition() == null || oldHost == null || oldHost.length < 2) {
            return null;
        }
        int satH = c.dimensions() == null ? 1 : Math.max(1, c.dimensions().height());
        int oldHY = oldHost[0];
        int oldHH = Math.max(1, oldHost[1]);
        int oldTop = oldHY + oldHH;
        int relY = c.relativePosition().y();
        int offsetFromTop = relY - oldTop;
        if (sticksToHostTop(c)) {
            String crownType = c.componentType() == null ? "" : c.componentType().toUpperCase(Locale.ROOT);
            // Match ComponentSatelliteAligner / makeRoofComponent: host MASS open policy.
            if (crownType.startsWith("ROOF")) {
                return roofSeatYOnHost(newHY + newHH, hostParams);
            }
            return newHY + newHH + offsetFromTop;
        }
        // Openings / stairs / floors / facade decor: proportional then clamp inside host.
        int offsetFromBottom = relY - oldHY;
        int nextY = newHY + (int) Math.round(offsetFromBottom * (double) newHH / (double) oldHH);
        int maxStart = newHY + newHH - satH;
        if (maxStart < newHY) {
            return null; // satellite taller than scaled host
        }
        if (nextY < newHY) nextY = newHY;
        if (nextY > maxStart) nextY = maxStart;
        return nextY;
    }

    /**
     * Register a stacked envelope; identical height+mode is deduped; conflicting heights or
     * two explicit contradictory modes at the same height gap.
     * @return false when a hard conflict was recorded
     */
    private static boolean registerStackedEnvelope(
            List<StackedHeightEnvelope> envelopes,
            Map<String, Integer> heightByFamily,
            Map<String, String> modeByFamily,
            Map<String, Boolean> modeExplicitByFamily,
            List<Map<String, Object>> diagnostics,
            List<String> massIds,
            int blocks,
            String mode,
            boolean modeExplicit
    ) {
        if (massIds == null || massIds.isEmpty() || blocks <= 0) return true;
        String family = stackedFamilyKey(massIds.getFirst());
        if (family == null || family.isBlank()) family = "stacked";
        String normMode = com.formacraft.common.typology.UserConstraintContract.normalizeEnvelopeMode(mode);
        Integer prior = heightByFamily.get(family);
        if (prior != null) {
            if (prior != blocks) {
                Map<String, Object> detail = new HashMap<>();
                detail.put("code", "E_HEIGHT_HOST_CONFLICT");
                detail.put("target", family);
                detail.put("blocks", blocks);
                detail.put("priorBlocks", prior);
                detail.put("message", "conflicting stacked height cues for the same tower family");
                diagnostics.add(detail);
                return false;
            }
            String priorMode = modeByFamily.getOrDefault(family, "shaft");
            boolean priorExplicit = Boolean.TRUE.equals(modeExplicitByFamily.get(family));
            if (priorMode.equals(normMode)) {
                if (modeExplicit) modeExplicitByFamily.put(family, true);
                return true;
            }
            // Same numeric target, different modes.
            if (priorExplicit && modeExplicit) {
                Map<String, Object> detail = new HashMap<>();
                detail.put("code", "E_HEIGHT_HOST_CONFLICT");
                detail.put("target", family);
                detail.put("blocks", blocks);
                detail.put("priorMode", priorMode);
                detail.put("mode", normMode);
                detail.put("message",
                        "conflicting shaft vs building height semantics for the same tower family");
                diagnostics.add(detail);
                return false;
            }
            // Explicit declaration overrides a default shaft inference; never demote building→shaft.
            if (modeExplicit) {
                modeByFamily.put(family, normMode);
                modeExplicitByFamily.put(family, true);
                for (int i = 0; i < envelopes.size(); i++) {
                    StackedHeightEnvelope e = envelopes.get(i);
                    if (family.equals(e.familyKey())) {
                        envelopes.set(i, new StackedHeightEnvelope(
                                e.massIds(), e.targetHeight(), e.familyKey(), normMode));
                    }
                }
            }
            return true;
        }
        heightByFamily.put(family, blocks);
        modeByFamily.put(family, normMode);
        modeExplicitByFamily.put(family, modeExplicit);
        envelopes.add(new StackedHeightEnvelope(massIds, blocks, family, normMode));
        return true;
    }

    /** floor_count when authored; 0 means no multi-storey band constraint on this mass. */
    private static int storeyFloorCount(Component c) {
        if (c == null || c.params() == null) return 0;
        return Math.max(0, com.formacraft.common.generation.component.util.ComponentParamParsers
                .intParam(c.params(), "floor_count", "floorCount"));
    }

    /**
     * Minimum clear-air storey band for scale allocation.
     * USER_EXPLICIT / server-sealed floor_height is a hard band; otherwise minStoreyBand.
     * Bare shells (no floor_count) need at least 1.
     */
    private static int minFeasibleStoreyHeight(Component c) {
        int floorCount = storeyFloorCount(c);
        if (floorCount <= 0) return 1;
        int hardFh = hardFloorHeight(c);
        int band = hardFh > 0
                ? hardFh
                : com.formacraft.common.generation.component.util.InteriorSpaceDefaults.minStoreyBand();
        return Math.max(1, floorCount * band);
    }

    /**
     * Authored floor_count × floor_height (hard or minBand) that must fit the current storey height
     * when the whole-building envelope already matches — conflict → capability gap.
     */
    private static int authoredFloorNeed(Component c) {
        int floorCount = storeyFloorCount(c);
        if (floorCount <= 0) return 0;
        int hardFh = hardFloorHeight(c);
        int authoredFh = c.params() == null ? 0
                : com.formacraft.common.generation.component.util.ComponentParamParsers
                .intParam(c.params(), "floor_height", "floorHeight");
        int band = hardFh > 0
                ? hardFh
                : (authoredFh > 0
                ? authoredFh
                : com.formacraft.common.generation.component.util.InteriorSpaceDefaults.minStoreyBand());
        return Math.max(1, floorCount * band);
    }

    /** Player-hard floor_height when server-sealed or _param_sources USER_EXPLICIT. */
    private static int hardFloorHeight(Component c) {
        if (c == null || c.params() == null) return 0;
        int fh = com.formacraft.common.generation.component.util.ComponentParamParsers
                .intParam(c.params(), "floor_height", "floorHeight");
        if (fh <= 0) return 0;
        if (com.formacraft.common.typology.UserConstraintContract
                .isServerVerified(c.params(), "floor_height")
                || com.formacraft.common.typology.UserConstraintContract
                .isServerVerified(c.params(), "floorHeight")) {
            return fh;
        }
        if (com.formacraft.common.typology.TypologyParamResolver
                .isUserHardConstraint(c.params(), "floor_height", "floorHeight")) {
            return fh;
        }
        return 0;
    }

    /**
     * Assign min bands first, then distribute {@code target - sum(mins)} by expandable weight
     * using the largest-remainder method so the sum equals {@code target} exactly.
     * Kept for tightly-stacked shafts (no gaps). Prefer {@link #allocateStackedShaftWithGaps}.
     */
    static int[] allocateStackedHeights(int[] oldHeights, int[] minHeights, int target) {
        int n = oldHeights.length;
        int[] gaps = new int[n];
        int[] outH = new int[n];
        int[] outG = new int[n];
        if (!allocateStackedShaftWithGaps(oldHeights, gaps, minHeights, target, outH, outG)) {
            return minHeights.clone();
        }
        return outH;
    }

    /**
     * Scale storey solids and inter-storey gaps together so eave/transition voids are preserved
     * and excess height is distributed by original slice weight (not dumped on the crown).
     * {@code outHeights}/{@code outGaps} are filled; returns false when infeasible.
     */
    static boolean allocateStackedShaftWithGaps(
            int[] oldHeights,
            int[] gaps,
            int[] minHeights,
            int target,
            int[] outHeights,
            int[] outGaps
    ) {
        int n = oldHeights.length;
        if (n == 0 || outHeights.length != n || outGaps.length != n) return false;
        int[] minGaps = new int[n];
        int minSum = 0;
        for (int i = 0; i < n; i++) {
            minGaps[i] = gaps[i] > 0 ? 1 : 0;
            minSum += minHeights[i] + minGaps[i];
        }
        if (minSum > target) return false;
        // Slice layout: solid0, gap0, solid1, gap1, ... (trailing gap of last is always 0).
        int sliceCount = n * 2;
        int[] oldSlices = new int[sliceCount];
        int[] minSlices = new int[sliceCount];
        for (int i = 0; i < n; i++) {
            oldSlices[i * 2] = oldHeights[i];
            minSlices[i * 2] = minHeights[i];
            oldSlices[i * 2 + 1] = gaps[i];
            minSlices[i * 2 + 1] = minGaps[i];
        }
        int[] outSlices = allocateSlicesLargestRemainder(oldSlices, minSlices, target);
        if (outSlices == null) return false;
        for (int i = 0; i < n; i++) {
            outHeights[i] = outSlices[i * 2];
            outGaps[i] = outSlices[i * 2 + 1];
        }
        return true;
    }

    /** Min-first then weight by (old-min); largest-remainder so sum == target. */
    private static int[] allocateSlicesLargestRemainder(int[] oldSlices, int[] minSlices, int target) {
        int n = oldSlices.length;
        int[] out = new int[n];
        int minSum = 0;
        for (int i = 0; i < n; i++) {
            out[i] = Math.max(0, minSlices[i]);
            minSum += out[i];
        }
        if (minSum > target) return null;
        int remainder = target - minSum;
        if (remainder == 0) return out;
        long weightSum = 0L;
        int[] weights = new int[n];
        for (int i = 0; i < n; i++) {
            weights[i] = Math.max(0, oldSlices[i] - out[i]);
            weightSum += weights[i];
        }
        if (weightSum <= 0) {
            for (int i = 0; i < n && remainder > 0; i++) {
                // Prefer solids (even indices) when spreading with no weight.
                int idx = (i * 2) % n;
                out[idx] += 1;
                remainder--;
            }
            if (remainder > 0) out[0] += remainder;
            return out;
        }
        int[] baseExtra = new int[n];
        double[] frac = new double[n];
        int assigned = 0;
        for (int i = 0; i < n; i++) {
            double exact = remainder * (weights[i] / (double) weightSum);
            baseExtra[i] = (int) Math.floor(exact);
            frac[i] = exact - baseExtra[i];
            assigned += baseExtra[i];
        }
        int left = remainder - assigned;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> {
            int cmp = Double.compare(frac[b], frac[a]);
            return cmp != 0 ? cmp : Integer.compare(a, b);
        });
        for (int k = 0; k < left; k++) {
            baseExtra[order[k % n]] += 1;
        }
        for (int i = 0; i < n; i++) {
            out[i] += baseExtra[i];
        }
        return out;
    }

    private static String satelliteHostId(Component c) {
        if (c == null || c.params() == null) return null;
        Object host = c.params().get("host_id");
        if (host == null) host = c.params().get("hostId");
        if (host == null || String.valueOf(host).isBlank()
                || "null".equalsIgnoreCase(String.valueOf(host))) {
            return null;
        }
        return String.valueOf(host).trim();
    }

    private static String hostIdentityBlob(Component host) {
        StringBuilder sb = new StringBuilder();
        String mid = BuildingHostResolver.massId(host);
        if (mid != null) sb.append(mid).append(' ');
        if (host.slotId() != null) sb.append(host.slotId()).append(' ');
        if (host.params() != null) {
            for (String k : List.of(
                    "component_id", "building_id", "requirement_scope",
                    "building_role", "buildingRole", "role", "part", "label")) {
                Object v = host.params().get(k);
                if (v != null) sb.append(v).append(' ');
            }
        }
        if (host.features() != null) {
            for (String f : host.features()) {
                if (f != null) sb.append(f).append(' ');
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
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

    private record PythonOmitLookup(
            boolean contractPresent,
            boolean unresolvedDeclaration,
            Set<String> targets
    ) {
        static PythonOmitLookup absent() {
            return new PythonOmitLookup(false, false, Set.of());
        }
    }

    /**
     * Targets for the same player omit declaration as {@code binding} (by source span / scope / role).
     * Distinguishes "no building_contract" from "contract present but declaration match failed".
     */
    @SuppressWarnings("unchecked")
    private static PythonOmitLookup pythonOmitTargetsForDeclaration(
            LlmPlan plan,
            com.formacraft.common.typology.UserConstraintContract.DesignOmitBinding binding
    ) {
        Set<String> out = new HashSet<>();
        if (plan == null || binding == null || binding.domain() == null) return PythonOmitLookup.absent();
        String prop = switch (binding.domain()) {
            case "roof" -> "roof_type";
            case "window" -> "window_style";
            case "entrance" -> "entrance_type";
            default -> "";
        };
        if (prop.isEmpty()) return PythonOmitLookup.absent();
        String sourceText = binding.sourceText() == null ? "" : binding.sourceText().trim();
        String hint = binding.roleHint();
        Object hints = plan.proportionHints();
        if (!(hints instanceof Map<?, ?> hintMap)) return PythonOmitLookup.absent();
        Object contract = hintMap.get("building_contract");
        if (!(contract instanceof Map<?, ?> cMap)) return PythonOmitLookup.absent();
        Object reqs = cMap.get("requirements");
        if (!(reqs instanceof List<?> list)) {
            return new PythonOmitLookup(true, true, Set.of());
        }
        boolean sawOmitReq = false;
        Map<?, ?> best = null;
        int bestScore = 0;
        boolean tie = false;
        for (Object raw : list) {
            if (!(raw instanceof Map<?, ?> req)) continue;
            if (!prop.equals(String.valueOf(req.get("property")))) continue;
            if (!"none".equalsIgnoreCase(String.valueOf(req.get("value")))) continue;
            sawOmitReq = true;
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
                tie = false;
            } else if (score > 0 && score == bestScore) {
                best = null;
                tie = true;
            }
        }
        if (!sawOmitReq) {
            // Contract exists but has no omit rows for this domain — treat as absent cross-check.
            return new PythonOmitLookup(true, false, Set.of());
        }
        if (best == null || bestScore <= 0 || tie) {
            return new PythonOmitLookup(true, true, Set.of());
        }
        Object targets = best.get("target_components");
        if (targets instanceof List<?> ids) {
            for (Object id : ids) {
                if (id != null && !String.valueOf(id).isBlank()) {
                    out.add(String.valueOf(id).trim());
                }
            }
        }
        // Matched declaration but empty targets still counts as unresolved when contract had the omit.
        if (out.isEmpty()) {
            return new PythonOmitLookup(true, true, Set.of());
        }
        return new PythonOmitLookup(true, false, out);
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
            case "wing" -> s.contains("wing") || s.contains("厢") || s.equals("named_wing");
            case "tower" -> s.contains("tower") || s.contains("pagoda") || s.contains("塔");
            case "all_main_masses" -> s.equals("all_main_masses") || s.equals("plan");
            case "multi_named" -> s.equals("multi_named");
            default -> s.equals("named_" + r) || s.equals(r) || s.endsWith("_" + r);
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

    /**
     * Unique bind of a player stair-tread material onto one direct stair component or one
     * nested STAIR_SYSTEM op. Ambiguous or missing targets become capability gaps — never guess.
     */
    private record StairSealTarget(int componentIndex, Integer opIndex, String blockId) {}

    private record StairTreadBindResult(
            List<StairSealTarget> targets,
            List<Map<String, Object>> diagnostics,
            boolean capabilityGap
    ) {
        private StairTreadBindResult {
            targets = targets == null ? List.of() : List.copyOf(targets);
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }

    private record StairTargetRef(int componentIndex, Integer opIndex) {}

    static StairTreadBindResult resolveStairTreadBindings(
            LlmPlan plan,
            List<com.formacraft.common.typology.UserConstraintContract.StairTreadBinding> bindings,
            boolean authorizeFromText
    ) {
        if (!authorizeFromText || bindings == null || bindings.isEmpty()) {
            return new StairTreadBindResult(List.of(), List.of(), false);
        }
        LinkedHashSet<String> materials = new LinkedHashSet<>();
        for (var b : bindings) {
            if (b != null && b.blockId() != null && !b.blockId().isBlank()) {
                materials.add(b.blockId().trim());
            }
        }
        List<Map<String, Object>> diags = new ArrayList<>();
        if (materials.size() != 1) {
            Map<String, Object> detail = new HashMap<>();
            detail.put("code", "E_STAIR_TREAD_MATERIAL_CONFLICT");
            detail.put("materials", List.copyOf(materials));
            detail.put("message", "conflicting explicit stair tread materials in the request");
            diags.add(detail);
            return new StairTreadBindResult(List.of(), diags, true);
        }
        String blockId = materials.iterator().next();
        List<StairTargetRef> targets = collectStairTargets(plan);
        if (targets.isEmpty()) {
            Map<String, Object> detail = new HashMap<>();
            detail.put("code", "E_STAIR_TREAD_TARGET_MISSING");
            detail.put("blockId", blockId);
            detail.put("message", "explicit stair tread material has no stair component or STAIR_SYSTEM op");
            diags.add(detail);
            return new StairTreadBindResult(List.of(), diags, true);
        }
        if (targets.size() != 1) {
            Map<String, Object> detail = new HashMap<>();
            detail.put("code", "E_STAIR_TREAD_TARGET_AMBIGUOUS");
            detail.put("blockId", blockId);
            detail.put("targetCount", targets.size());
            detail.put("message", "explicit stair tread material cannot uniquely bind a stair target");
            diags.add(detail);
            return new StairTreadBindResult(List.of(), diags, true);
        }
        StairTargetRef only = targets.getFirst();
        return new StairTreadBindResult(
                List.of(new StairSealTarget(only.componentIndex(), only.opIndex(), blockId)),
                List.of(),
                false);
    }

    static List<StairTargetRef> collectStairTargets(LlmPlan plan) {
        List<StairTargetRef> out = new ArrayList<>();
        if (plan == null || plan.components() == null) return out;
        List<Component> comps = plan.components();
        for (int i = 0; i < comps.size(); i++) {
            Component c = comps.get(i);
            if (c == null) continue;
            if (com.formacraft.common.palette.dynamic.StairComponentIdentity.isStairComponent(c)) {
                out.add(new StairTargetRef(i, null));
                continue;
            }
            if (c.componentType() == null
                    || !"ASSEMBLY".equalsIgnoreCase(c.componentType().trim())
                    || c.params() == null) {
                continue;
            }
            Object assembly = c.params().get("assembly");
            if (!(assembly instanceof Map<?, ?> asm)) continue;
            Object opsObj = asm.get("ops");
            if (!(opsObj instanceof List<?> ops)) continue;
            for (int oi = 0; oi < ops.size(); oi++) {
                Object op = ops.get(oi);
                if (!(op instanceof Map<?, ?> om)) continue;
                String kind = Objects.toString(om.get("op"), Objects.toString(om.get("type"), ""))
                        .trim().toUpperCase(Locale.ROOT);
                if ("STAIR_SYSTEM".equals(kind) || "STAIRS_SYSTEM".equals(kind) || "STAIRCASE".equals(kind)) {
                    out.add(new StairTargetRef(i, oi));
                }
            }
        }
        return out;
    }

    static LlmPlan applyStairTreadSeals(
            LlmPlan plan, StairTreadBindResult stairResult, boolean authorizeFromText) {
        if (!authorizeFromText || plan == null || stairResult == null || stairResult.targets().isEmpty()) {
            return plan;
        }
        List<Component> comps = plan.components();
        if (comps == null || comps.isEmpty()) return plan;
        List<Component> out = new ArrayList<>(comps);
        boolean changed = false;
        for (StairSealTarget target : stairResult.targets()) {
            if (target == null || target.blockId() == null || target.blockId().isBlank()) continue;
            int ci = target.componentIndex();
            if (ci < 0 || ci >= out.size()) continue;
            Component c = out.get(ci);
            if (c == null) continue;
            Map<String, Object> params = c.params() == null
                    ? new HashMap<>()
                    : new HashMap<>(c.params());
            if (target.opIndex() == null) {
                com.formacraft.common.typology.UserConstraintContract
                        .applyVerifiedStairTread(params, target.blockId());
                out.set(ci, new Component(
                        c.componentType(), c.slotId(), c.relativePosition(),
                        c.dimensions(), c.features(), params));
                changed = true;
                continue;
            }
            Object assemblyObj = params.get("assembly");
            if (!(assemblyObj instanceof Map<?, ?> asmRaw)) continue;
            Map<String, Object> assembly = deepCopyMap(asmRaw);
            Object opsObj = assembly.get("ops");
            if (!(opsObj instanceof List<?> opsRaw)) continue;
            List<Object> ops = new ArrayList<>(opsRaw.size());
            for (Object op : opsRaw) {
                if (op instanceof Map<?, ?> om) ops.add(deepCopyMap(om));
                else ops.add(op);
            }
            int oi = target.opIndex();
            if (oi < 0 || oi >= ops.size() || !(ops.get(oi) instanceof Map<?, ?>)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> op = (Map<String, Object>) ops.get(oi);
            com.formacraft.common.typology.UserConstraintContract
                    .applyVerifiedStairTread(op, target.blockId());
            ops.set(oi, op);
            assembly.put("ops", ops);
            params.put("assembly", assembly);
            out.set(ci, new Component(
                    c.componentType(), c.slotId(), c.relativePosition(),
                    c.dimensions(), c.features(), params));
            changed = true;
        }
        return changed ? PlanComponentRewrite.withComponents(plan, out) : plan;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopyMap(Map<?, ?> raw) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            if (e.getKey() == null) continue;
            Object v = e.getValue();
            if (v instanceof Map<?, ?> nested) {
                copy.put(String.valueOf(e.getKey()), deepCopyMap(nested));
            } else if (v instanceof List<?> list) {
                List<Object> copied = new ArrayList<>(list.size());
                for (Object item : list) {
                    if (item instanceof Map<?, ?> nm) copied.add(deepCopyMap(nm));
                    else copied.add(item);
                }
                copy.put(String.valueOf(e.getKey()), copied);
            } else {
                copy.put(String.valueOf(e.getKey()), v);
            }
        }
        return copy;
    }

    /**
     * Whole-building height for stacked storeys — never copy onto each tier's dimensions.height.
     * {@code familyKey} is the stable stacked identity (e.g. pagoda); {@code mode} is shaft|building.
     */
    private record StackedHeightEnvelope(
            List<String> massIds, int targetHeight, String familyKey, String mode) {
        private StackedHeightEnvelope {
            massIds = massIds == null ? List.of() : List.copyOf(massIds);
            mode = com.formacraft.common.typology.UserConstraintContract.normalizeEnvelopeMode(mode);
        }
    }

    private record HeightBindResult(
            Map<String, Integer> byMassId,
            List<StackedHeightEnvelope> envelopes,
            List<Map<String, Object>> diagnostics,
            boolean capabilityGap
    ) {
        private HeightBindResult(Map<String, Integer> byMassId,
                                 List<Map<String, Object>> diagnostics,
                                 boolean capabilityGap) {
            this(byMassId, List.of(), diagnostics, capabilityGap);
        }
    }

    private record EnvelopeApplyResult(
            List<Component> components,
            List<Map<String, Object>> diagnostics,
            boolean capabilityGap,
            boolean changed,
            Map<String, Integer> sealedHeights,
            Map<String, Integer> envelopeTargetByMass,
            Map<String, String> envelopeFamilyByMass,
            Map<String, String> envelopeModeByMass
    ) {
        private EnvelopeApplyResult {
            sealedHeights = sealedHeights == null ? Map.of() : Map.copyOf(sealedHeights);
            envelopeTargetByMass = envelopeTargetByMass == null ? Map.of() : Map.copyOf(envelopeTargetByMass);
            envelopeFamilyByMass = envelopeFamilyByMass == null ? Map.of() : Map.copyOf(envelopeFamilyByMass);
            envelopeModeByMass = envelopeModeByMass == null ? Map.of() : Map.copyOf(envelopeModeByMass);
        }
    }

    /**
     * Map mass ids to player height values by unique role/ordinal host.
     * Stacked storeys receive an envelope binding, not per-tier height copies.
     * Classification: HARD_CONSTRAINT — no area/order guess for unmatched pavilion cues.
     */
    static HeightBindResult resolveHeightBindings(
            List<Component> hosts,
            String primary,
            boolean multi,
            List<com.formacraft.common.typology.UserConstraintContract.HeightBinding> bindings
    ) {
        Map<String, Integer> out = new HashMap<>();
        List<StackedHeightEnvelope> envelopes = new ArrayList<>();
        Map<String, Integer> envelopeHeightByFamily = new HashMap<>();
        Map<String, String> envelopeModeByFamily = new HashMap<>();
        Map<String, Boolean> envelopeModeExplicitByFamily = new HashMap<>();
        List<Map<String, Object>> diagnostics = new ArrayList<>();
        boolean gap = false;
        if (bindings == null || bindings.isEmpty()) {
            return new HeightBindResult(out, envelopes, diagnostics, false);
        }
        if (hosts == null || hosts.isEmpty()) {
            for (var b : bindings) {
                if (b == null || b.blocks() <= 0) continue;
                gap = true;
                Map<String, Object> detail = new HashMap<>();
                detail.put("code", "E_DESIGN_HOST_MISSING");
                detail.put("blocks", b.blocks());
                detail.put("roleHint", b.roleHint() == null ? "" : b.roleHint());
                detail.put("message", "explicit height cue with no design-constraint host");
                diagnostics.add(detail);
            }
            return new HeightBindResult(out, envelopes, diagnostics, gap);
        }
        for (var b : bindings) {
            if (b == null || b.blocks() <= 0) continue;
            String hint = b.roleHint();
            String mode = b.envelopeMode() == null ? "shaft" : b.envelopeMode();
            boolean modeExplicit = b.modeExplicit();
            String target = null;
            if (hint == null || hint.isBlank()) {
                // Unscoped: one building entity (incl. stacked storeys) only.
                if (!multi || hosts.size() == 1) {
                    List<String> stackedIds = stackedFamilyMassIds(hosts);
                    if (stackedIds.size() > 1) {
                        if (!registerStackedEnvelope(
                                envelopes, envelopeHeightByFamily, envelopeModeByFamily,
                                envelopeModeExplicitByFamily,
                                diagnostics, stackedIds, b.blocks(), mode, modeExplicit)) {
                            gap = true;
                        }
                        continue;
                    }
                    target = primary;
                    if (target == null || !BuildingHostResolver.isKnownDesignHostId(hosts, target)) {
                        target = BuildingHostResolver.massId(hosts.getFirst());
                    }
                } else {
                    gap = true;
                    diagnostics.add(Map.of(
                            "code", "E_HEIGHT_HOST_UNSCOPED",
                            "blocks", b.blocks(),
                            "message", "multi-mass height cue without role/ordinal scope"));
                    continue;
                }
            } else if (hint.startsWith("building_")) {
                target = resolveOrdinalOmitHost(hosts, primary, hint);
            } else {
                // namedKeys first (wing≠pavilion); clause text keeps 西厢房 side-local.
                List<String> namedHits = resolveNamedOrRoleHostIds(
                        hosts, hint, b.namedKeys(), b.sourceText());
                if (namedHits.isEmpty()) {
                    gap = true;
                    diagnostics.add(Map.of(
                            "code", "E_HEIGHT_HOST_TIE",
                            "blocks", b.blocks(),
                            "roleHint", hint == null ? "" : hint,
                            "message", "height host role score is not unique or missing"));
                    continue;
                }
                if (isStackedFamilyIds(namedHits)) {
                    for (String id : namedHits) {
                        if (!BuildingHostResolver.isKnownDesignHostId(hosts, id)) {
                            gap = true;
                            Map<String, Object> detail = new HashMap<>();
                            detail.put("code", "E_HEIGHT_HOST_MISSING");
                            detail.put("blocks", b.blocks());
                            detail.put("roleHint", hint == null ? "" : hint);
                            detail.put("target", id);
                            detail.put("message", "height target is not a design-constraint host");
                            diagnostics.add(detail);
                        }
                    }
                    if (!gap) {
                        if (!registerStackedEnvelope(
                                envelopes, envelopeHeightByFamily, envelopeModeByFamily,
                                envelopeModeExplicitByFamily,
                                diagnostics, List.copyOf(namedHits), b.blocks(), mode, modeExplicit)) {
                            gap = true;
                        }
                    }
                    continue;
                }
                for (String id : namedHits) {
                    if (!BuildingHostResolver.isKnownDesignHostId(hosts, id)) {
                        gap = true;
                        Map<String, Object> detail = new HashMap<>();
                        detail.put("code", "E_HEIGHT_HOST_MISSING");
                        detail.put("blocks", b.blocks());
                        detail.put("roleHint", hint == null ? "" : hint);
                        detail.put("target", id);
                        detail.put("message", "height target is not a design-constraint host");
                        diagnostics.add(detail);
                        continue;
                    }
                    Integer prior = out.get(id);
                    if (prior != null && prior != b.blocks()) {
                        gap = true;
                        diagnostics.add(Map.of(
                                "code", "E_HEIGHT_HOST_CONFLICT",
                                "target", id,
                                "blocks", b.blocks(),
                                "priorBlocks", prior,
                                "message", "conflicting height cues for the same host"));
                        continue;
                    }
                    out.put(id, b.blocks());
                }
                continue;
            }
            if (target == null || !BuildingHostResolver.isKnownDesignHostId(hosts, target)) {
                gap = true;
                Map<String, Object> detail = new HashMap<>();
                detail.put("code", target == null ? "E_HEIGHT_HOST_TIE" : "E_HEIGHT_HOST_MISSING");
                detail.put("blocks", b.blocks());
                detail.put("roleHint", hint == null ? "" : hint);
                if (target != null) detail.put("target", target);
                detail.put("message", target == null
                        ? "height host role score is not unique or missing"
                        : "height target is not a design-constraint host");
                diagnostics.add(detail);
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
        return new HeightBindResult(out, List.copyOf(envelopes), diagnostics, gap);
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
        // Pavilion must not score wing hosts; wing has its own identity.
        boolean pavilionLike = lower.contains("pavilion") || lower.contains("亭")
                || lower.contains("廊");
        boolean wingLike = lower.contains("wing") || lower.contains("厢");
        boolean towerLike = lower.contains("tower") || lower.contains("塔")
                || lower.contains("pagoda");
        return switch (roleHint) {
            case "pavilion" -> pavilionLike ? 3 : 0;
            case "wing" -> wingLike ? 3 : 0;
            case "hall" -> {
                if (lower.contains("main_hall") || lower.contains("townhall")
                        || lower.contains("hall") || lower.contains("殿")
                        || lower.contains("正房") || lower.contains("主殿")
                        || lower.contains("正殿") || lower.contains("大殿")
                        || lower.contains("主楼") || lower.contains("主厅")) {
                    yield 3;
                }
                // Weak identity only (never componentType): house_main / *_main without pavilion/tower cues.
                if (!pavilionLike && !wingLike && !towerLike
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

    /** Record per-family envelope targets on plan_meta for compile-time integrity checks. */
    private static LlmPlan withStackedEnvelopeTargetsMeta(
            LlmPlan plan,
            Map<String, Integer> targetByMass,
            Map<String, String> familyByMass,
            Map<String, String> modeByMass
    ) {
        if (plan == null || targetByMass == null || targetByMass.isEmpty()) return plan;
        Map<String, Map<String, Object>> byFamily = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : targetByMass.entrySet()) {
            String massId = e.getKey();
            String family = familyByMass == null ? null : familyByMass.get(massId);
            if (family == null || family.isBlank()) {
                family = stackedFamilyKey(massId);
            }
            if (family == null || family.isBlank()) family = massId;
            String mode = modeByMass == null ? null : modeByMass.get(massId);
            if (!"building".equals(mode)) mode = "shaft";
            String finalMode = mode;
            Map<String, Object> row = byFamily.computeIfAbsent(family, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("family", k);
                m.put("target_height", e.getValue());
                m.put("mode", finalMode);
                m.put("mass_ids", new ArrayList<String>());
                return m;
            });
            row.put("target_height", e.getValue());
            row.put("mode", mode);
            @SuppressWarnings("unchecked")
            List<String> ids = (List<String>) row.get("mass_ids");
            if (!ids.contains(massId)) ids.add(massId);
        }
        List<Map<String, Object>> rows = new ArrayList<>(byFamily.values());
        Map<String, Object> meta = plan.planMeta() == null
                ? new HashMap<>()
                : new HashMap<>(plan.planMeta());
        meta.put(STACKED_ENVELOPE_TARGETS_META, rows);
        meta.put(STACKED_ENVELOPE_TARGETS_SEAL_META,
                com.formacraft.common.typology.UserConstraintContract.sealStackedEnvelopeTargets(rows));
        return withMeta(plan, meta);
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
