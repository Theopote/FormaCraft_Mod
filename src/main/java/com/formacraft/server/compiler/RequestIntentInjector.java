package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.model.request.FormaRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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


    private RequestIntentInjector() {}

    /** Package-private crown classification (test + allocator compat). */
    enum CrownKind { NONE, STRUCTURAL_CROWN, TOP_PROTRUSION }

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
                reconciled.put("stale_parsed_intent", RequestIntentKeywordExtractor.snapshotStructuredIntent(existingMap));
                reconciled.put("original_text", text);
                reconciled.put("intent_echo_mismatch", Boolean.TRUE);
                FormacraftMod.LOGGER.info(
                        "RequestIntentInjector: INTENT_ECHO_MISMATCH serverText≠plan echo; re-extracting intent");
                echo = text;
            } else if (authorizeFromText) {
                reconciled.put("original_text", text);
                echo = text;
            }
            if (authorizeFromText) {
                RequestIntentKeywordExtractor.applyKeywordAuthority(
                        reconciled, RequestIntentKeywordExtractor.extractKeyword(text));
            } else {
                // Compiler reentry has no original request to resolve again.
                RequestIntentKeywordExtractor.reconcileRoofFromText(reconciled, echo);
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
        Map<String, Object> keyword = RequestIntentKeywordExtractor.extractKeyword(text);
        Map<String, Object> intent = RequestIntentKeywordExtractor.mergeWithLlmDeclared(keyword, meta);
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
        boolean multi = DesignOmitBindingResolver.distinctBuildingEntityCount(hosts) > 1;
        StairAndHeightSealBinder.HeightBindResult heightResult = StairAndHeightSealBinder.resolveHeightBindings(hosts, primary, multi, bindings);
        // Mutable: envelope-scaled storey heights must re-enter the seal pass below.
        Map<String, Integer> heightByMassId = new HashMap<>(heightResult.byMassId());
        DesignOmitBindingResolver.OmitBindResult omitResult = DesignOmitBindingResolver.resolveDesignOmitBindings(plan, hosts, primary, multi, omits);
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
            StackedHeightEnvelopeAllocator.EnvelopeApplyResult envApplied = StackedHeightEnvelopeAllocator.applyStackedHeightEnvelopes(
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
                plan = StackedHeightEnvelopeAllocator.withStackedEnvelopeTargetsMeta(
                        plan,
                        envApplied.envelopeTargetByMass(),
                        envApplied.envelopeFamilyByMass(),
                        envApplied.envelopeModeByMass());
            }
        }
        StairAndHeightSealBinder.StairTreadBindResult stairResult = StairAndHeightSealBinder.resolveStairTreadBindings(plan, stairTreads, authorizeFromText);
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
        return StairAndHeightSealBinder.applyStairTreadSeals(plan, stairResult, authorizeFromText);
    }

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



    // --- package-private test/compat delegates (move-only split) ---

    static DesignOmitBindingResolver.OmitBindResult resolveDesignOmitBindings(
            LlmPlan plan,
            List<Component> hosts,
            String primary,
            boolean multi,
            List<com.formacraft.common.typology.UserConstraintContract.DesignOmitBinding> omits
    ) {
        return DesignOmitBindingResolver.resolveDesignOmitBindings(plan, hosts, primary, multi, omits);
    }

    static String stackedFamilyKey(String componentId) {
        return DesignOmitBindingResolver.stackedFamilyKey(componentId);
    }

    static boolean isStackedFamilyIds(List<String> ids) {
        return DesignOmitBindingResolver.isStackedFamilyIds(ids);
    }

    static int distinctBuildingEntityCount(List<Component> hosts) {
        return DesignOmitBindingResolver.distinctBuildingEntityCount(hosts);
    }

    static java.util.Set<String> wingSidesFromText(String text) {
        return DesignOmitBindingResolver.wingSidesFromText(text);
    }

    static String wingSideHint(String text) {
        return DesignOmitBindingResolver.wingSideHint(text);
    }

    static boolean hostMatchesWingSide(String identityBlob, String side) {
        return DesignOmitBindingResolver.hostMatchesWingSide(identityBlob, side);
    }

    static StackedHeightEnvelopeAllocator.EnvelopeApplyResult applyStackedHeightEnvelopes(
            List<Component> components,
            List<Component> hosts,
            List<StackedHeightEnvelopeAllocator.StackedHeightEnvelope> envelopes
    ) {
        return StackedHeightEnvelopeAllocator.applyStackedHeightEnvelopes(components, hosts, envelopes);
    }

    static StackedHeightEnvelopeAllocator.EnvelopeApplyResult applyStackedHeightEnvelopes(
            List<Component> components,
            List<Component> hosts,
            List<StackedHeightEnvelopeAllocator.StackedHeightEnvelope> envelopes,
            String userText
    ) {
        return StackedHeightEnvelopeAllocator.applyStackedHeightEnvelopes(
                components, hosts, envelopes, userText);
    }

    static StackedHeightEnvelopeAllocator.EnvelopeApplyResult applyStackedHeightEnvelopes(
            LlmPlan plan,
            List<Component> components,
            List<Component> hosts,
            List<StackedHeightEnvelopeAllocator.StackedHeightEnvelope> envelopes,
            String userText
    ) {
        return StackedHeightEnvelopeAllocator.applyStackedHeightEnvelopes(
                plan, components, hosts, envelopes, userText);
    }

    static boolean wantsBuildingEnvelope(String userText) {
        return StackedHeightEnvelopeAllocator.wantsBuildingEnvelope(userText);
    }

    static int estimateInferredRoofRise(List<Component> components, List<Component> shaftMembers) {
        return StackedHeightEnvelopeAllocator.estimateInferredRoofRise(components, shaftMembers);
    }

    static int estimateInferredRoofRise(
            LlmPlan plan, List<Component> components, List<Component> shaftMembers) {
        return StackedHeightEnvelopeAllocator.estimateInferredRoofRise(plan, components, shaftMembers);
    }

    static int roofSeatYOnHost(int hostTop, Map<String, Object> hostParams) {
        return StackedHeightEnvelopeAllocator.roofSeatYOnHost(hostTop, hostParams);
    }

    static CrownKind classifyCrownAttachment(Component c) {
        return StackedHeightEnvelopeAllocator.classifyCrownAttachment(c);
    }

    static boolean sticksToHostTop(Component c) {
        return StackedHeightEnvelopeAllocator.sticksToHostTop(c);
    }

    static boolean countsTowardBuildingEnvelope(Component c) {
        return StackedHeightEnvelopeAllocator.countsTowardBuildingEnvelope(c);
    }

    static Integer relocateSatelliteY(Component c, int[] oldHost, int newHY, int newHH) {
        return StackedHeightEnvelopeAllocator.relocateSatelliteY(c, oldHost, newHY, newHH);
    }

    static Integer relocateSatelliteY(
            Component c, int[] oldHost, int newHY, int newHH, Map<String, Object> hostParams) {
        return StackedHeightEnvelopeAllocator.relocateSatelliteY(c, oldHost, newHY, newHH, hostParams);
    }

    static int[] allocateStackedHeights(int[] oldHeights, int[] minHeights, int target) {
        return StackedHeightEnvelopeAllocator.allocateStackedHeights(oldHeights, minHeights, target);
    }

    static boolean allocateStackedShaftWithGaps(
            int[] oldHeights, int[] gaps, int[] minHeights, int target, int[] outH, int[] outG) {
        return StackedHeightEnvelopeAllocator.allocateStackedShaftWithGaps(
                oldHeights, gaps, minHeights, target, outH, outG);
    }

    static List<String> resolveNamedOrRoleHostIds(
            List<Component> hosts, String roleHint, List<String> namedKeys, String clauseText) {
        return DesignOmitBindingResolver.resolveNamedOrRoleHostIds(
                hosts, roleHint, namedKeys, clauseText);
    }

    static StairAndHeightSealBinder.StairTreadBindResult resolveStairTreadBindings(
            LlmPlan plan,
            List<com.formacraft.common.typology.UserConstraintContract.StairTreadBinding> bindings,
            boolean authorizeFromText) {
        return StairAndHeightSealBinder.resolveStairTreadBindings(plan, bindings, authorizeFromText);
    }

    static List<StairAndHeightSealBinder.StairTargetRef> collectStairTargets(LlmPlan plan) {
        return StairAndHeightSealBinder.collectStairTargets(plan);
    }

    static LlmPlan applyStairTreadSeals(
            LlmPlan plan,
            StairAndHeightSealBinder.StairTreadBindResult result,
            boolean authorizeFromText) {
        return StairAndHeightSealBinder.applyStairTreadSeals(plan, result, authorizeFromText);
    }

    static StairAndHeightSealBinder.HeightBindResult resolveHeightBindings(
            List<Component> hosts,
            String primary,
            boolean multi,
            List<com.formacraft.common.typology.UserConstraintContract.HeightBinding> bindings) {
        return StairAndHeightSealBinder.resolveHeightBindings(hosts, primary, multi, bindings);
    }

    static Map<String, Object> extract(String text) {
        return RequestIntentKeywordExtractor.extract(text);
    }

    static Map<String, Object> extractKeyword(String text) {
        return RequestIntentKeywordExtractor.extractKeyword(text);
    }

    static void reconcileRoofFromText(Map<String, Object> intent, String text) {
        RequestIntentKeywordExtractor.reconcileRoofFromText(intent, text);
    }

    static boolean isNegatedAt(String text, int start) {
        return RequestIntentKeywordExtractor.isNegatedAt(text, start);
    }

    static int roofSpecificity(String canonical) {
        return RequestIntentKeywordExtractor.roofSpecificity(canonical);
    }


    static LlmPlan withMeta(LlmPlan plan, Map<String, Object> meta) {
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

    static LlmPlan withCapabilityGap(
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

}
