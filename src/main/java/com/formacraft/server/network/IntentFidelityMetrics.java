package com.formacraft.server.network;

import com.formacraft.FormacraftMod;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.server.preview.GenerationRequestContext;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Layered generation outcomes. Placement / HTTP 200 must never alone imply intent fidelity.
 * <ul>
 *   <li>{@code plan_valid} — schema/semantic parse accepted</li>
 *   <li>{@code geometry_success} — non-empty compile geometry / patches</li>
 *   <li>{@code world_apply_success} — blocks applied (or preview-ready apply path)</li>
 *   <li>{@code intent_fidelity_pass} — morphology + semantic match; wrong floors fails even if placed</li>
 * </ul>
 * Log prefix: {@code [IntentFidelityMetrics]}.
 */
public final class IntentFidelityMetrics {

    private static final AtomicLong planValidSuccess = new AtomicLong();
    private static final AtomicLong planValidFail = new AtomicLong();
    private static final AtomicLong geometrySuccess = new AtomicLong();
    private static final AtomicLong geometryFail = new AtomicLong();
    private static final AtomicLong worldApplySuccess = new AtomicLong();
    private static final AtomicLong worldApplyFail = new AtomicLong();
    private static final AtomicLong intentFidelitySuccess = new AtomicLong();
    private static final AtomicLong intentFidelityFail = new AtomicLong();
    private static final AtomicLong autofillEvents = new AtomicLong();

    // Legacy aliases kept for older callers/tests.
    private static final AtomicLong parseSuccess = planValidSuccess;
    private static final AtomicLong parseFail = planValidFail;
    private static final AtomicLong buildSuccess = geometrySuccess;
    private static final AtomicLong buildFail = geometryFail;

    private IntentFidelityMetrics() {}

    public static void resetForTests() {
        planValidSuccess.set(0);
        planValidFail.set(0);
        geometrySuccess.set(0);
        geometryFail.set(0);
        worldApplySuccess.set(0);
        worldApplyFail.set(0);
        intentFidelitySuccess.set(0);
        intentFidelityFail.set(0);
        autofillEvents.set(0);
    }

    public static void recordParse(boolean ok, String requestId, String userText, String reason) {
        if (ok) {
            planValidSuccess.incrementAndGet();
        } else {
            planValidFail.incrementAndGet();
        }
        FormacraftMod.LOGGER.info(
                "[IntentFidelityMetrics] event=plan_valid ok={} requestId={} reason={} text=\"{}\" {}",
                ok, nullToEmpty(requestId), nullToEmpty(reason), truncate(userText, 80), snapshot());
    }

    /**
     * Legacy 3-flag API: {@code buildOk} maps to both geometry and world_apply when the
     * caller has not split them yet.
     */
    public static void recordOutcome(
            boolean parseOk,
            boolean buildOk,
            boolean intentFidelityOk,
            String requestId,
            String userText,
            String intentClass,
            String styleProfile,
            String reason,
            String planSummary,
            String defaultsReason
    ) {
        recordLayeredOutcome(
                parseOk, buildOk, buildOk, intentFidelityOk,
                requestId, userText, intentClass, styleProfile, reason, planSummary, defaultsReason);
    }

    public static void recordLayeredOutcome(
            boolean planValid,
            boolean geometryOk,
            boolean worldApplyOk,
            boolean intentFidelityOk,
            String requestId,
            String userText,
            String intentClass,
            String styleProfile,
            String reason,
            String planSummary,
            String defaultsReason
    ) {
        if (planValid) {
            planValidSuccess.incrementAndGet();
        } else {
            planValidFail.incrementAndGet();
        }
        if (geometryOk) {
            geometrySuccess.incrementAndGet();
        } else {
            geometryFail.incrementAndGet();
        }
        if (worldApplyOk) {
            worldApplySuccess.incrementAndGet();
        } else {
            worldApplyFail.incrementAndGet();
        }
        if (intentFidelityOk) {
            intentFidelitySuccess.incrementAndGet();
        } else {
            intentFidelityFail.incrementAndGet();
        }
        FormacraftMod.LOGGER.info(
                "[IntentFidelityMetrics] event=outcome requestId={} plan_valid={} geometry={} world_apply={} "
                        + "intent_fidelity={} intent={} style={} reason={} defaults={} plan={} text=\"{}\" {}",
                nullToEmpty(requestId),
                planValid,
                geometryOk,
                worldApplyOk,
                intentFidelityOk,
                nullToEmpty(intentClass),
                nullToEmpty(styleProfile),
                nullToEmpty(reason),
                nullToEmpty(defaultsReason),
                nullToEmpty(planSummary),
                truncate(userText, 80),
                snapshot());
    }

    public static void recordAutofill(
            String requestId,
            List<String> autofills,
            String roofType,
            Map<String, ?> materials
    ) {
        autofillEvents.incrementAndGet();
        FormacraftMod.LOGGER.info(
                "[IntentFidelityMetrics] event=autofill requestId={} items={} roof={} materials={} {}",
                nullToEmpty(requestId),
                autofills == null ? List.of() : autofills,
                roofType,
                materials == null ? Map.of() : materials,
                snapshot());
    }

    /**
     * After compile+preview: geometry tracks patches; intent_fidelity fails on morphology /
     * semantic gaps even when geometry_success is true (e.g. wrong pagoda floor count).
     */
    public static void recordCompileAttempt(
            ServerPlayerEntity player,
            FormaRequest req,
            LlmPlan plan,
            boolean parseOk,
            boolean buildOk,
            CapabilityGapLike gap
    ) {
        String requestId = currentRequestId();
        String userText = req == null ? "" : String.valueOf(
                com.formacraft.common.generation.routing.BuildingSpecRoutingPolicy.userIntentText(req));
        String intent = detectIntentLabel(plan, userText);
        String style = plan == null || plan.styleProfile() == null ? "" : plan.styleProfile();
        boolean morphologyFail = gap != null && isMorphologyFailure(gap.code());
        boolean semanticOk = gap == null || (!isSemanticFailure(gap.code()) && !morphologyFail);
        boolean geometryOk = buildOk;
        // Preview path: world apply not yet done — leave world_apply equal to geometry only when
        // there is no morphology failure; morphology gaps mean fidelity fails despite patches.
        boolean worldApplyOk = buildOk && !morphologyFail;
        boolean fidelityOk = parseOk && geometryOk && semanticOk && !hasResidentialAutofillWarning() && !morphologyFail;
        String reason = gap != null ? gap.code() : (fidelityOk ? "ok" : "fidelity_soft_fail");
        recordLayeredOutcome(
                parseOk,
                geometryOk,
                worldApplyOk,
                fidelityOk,
                requestId,
                userText,
                intent,
                style,
                reason,
                summarizePlan(plan),
                "");
        List<String> autofills = collectAutofillCodes();
        if (!autofills.isEmpty()) {
            recordAutofill(requestId, autofills, detectRoofType(plan), Map.of());
        }
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                planValidSuccess.get(),
                planValidFail.get(),
                geometrySuccess.get(),
                geometryFail.get(),
                worldApplySuccess.get(),
                worldApplyFail.get(),
                intentFidelitySuccess.get(),
                intentFidelityFail.get(),
                autofillEvents.get());
    }

    /** Narrow adapter so metrics need not import CapabilityGap into every call site. */
    public interface CapabilityGapLike {
        String code();
    }

    public static CapabilityGapLike fromGap(com.formacraft.common.llm.dto.CapabilityGap gap) {
        if (gap == null) {
            return null;
        }
        return gap::code;
    }

    private static boolean isSemanticFailure(String code) {
        if (code == null) {
            return false;
        }
        String c = code.trim().toUpperCase(Locale.ROOT);
        return c.equals("E_SEMANTIC_FIDELITY")
                || c.equals("E_EMPTY_COMPONENTS")
                || c.startsWith("E_SEMANTIC");
    }

    private static boolean isMorphologyFailure(String code) {
        if (code == null) {
            return false;
        }
        String c = code.trim().toUpperCase(Locale.ROOT);
        return c.equals("E_PAGODA_MORPHOLOGY")
                || c.equals("E_STUPA_MORPHOLOGY")
                || c.contains("MORPHOLOGY")
                || c.equals("TYPOLOGY_FAILED");
    }

    private static boolean hasResidentialAutofillWarning() {
        for (AssemblyCompileDiagnostics.SoftWarning w : AssemblyCompileDiagnostics.softWarnings()) {
            if (w == null || w.code() == null) continue;
            String code = w.code().toUpperCase(Locale.ROOT);
            if (code.startsWith("SKIPPED_RESIDENTIAL") || code.equals("SKIPPED_DEFAULT_GABLE")) {
                continue;
            }
            if (code.contains("INFERRED") || code.contains("AUTOFILL")) {
                return true;
            }
        }
        return false;
    }

    private static List<String> collectAutofillCodes() {
        List<String> out = new ArrayList<>();
        for (AssemblyCompileDiagnostics.SoftWarning w : AssemblyCompileDiagnostics.softWarnings()) {
            if (w != null && w.code() != null && !w.code().isBlank()) {
                out.add(w.code());
            }
        }
        return out;
    }

    private static String detectIntentLabel(LlmPlan plan, String userText) {
        String corpus = (userText == null ? "" : userText);
        if (plan != null) {
            if (plan.playerFidelityNoticeZh() != null) corpus += " " + plan.playerFidelityNoticeZh();
            if (plan.distinguishingFeatures() != null) {
                for (String f : plan.distinguishingFeatures()) {
                    if (f != null) corpus += " " + f;
                }
            }
        }
        String lower = corpus.toLowerCase(Locale.ROOT);
        if (containsAny(lower, corpus, "佛塔", "古塔", "pagoda", "stupa", "藏式")) return "tower_like";
        if (containsAny(lower, corpus, "山门", "牌坊", "paifang")) return "gate";
        if (containsAny(lower, corpus, "园林", "庭院", "garden")) return "garden";
        if (containsAny(lower, corpus, "道观", "寺庙")) return "temple";
        return "generic";
    }

    private static boolean containsAny(String lower, String raw, String... needles) {
        for (String n : needles) {
            if (n == null || n.isEmpty()) continue;
            if (lower.contains(n.toLowerCase(Locale.ROOT)) || raw.contains(n)) return true;
        }
        return false;
    }

    private static String summarizePlan(LlmPlan plan) {
        if (plan == null) return "plan=null";
        List<Component> comps = plan.components() == null ? List.of() : plan.components();
        StringBuilder types = new StringBuilder();
        int n = 0;
        for (Component c : comps) {
            if (c == null || c.componentType() == null) continue;
            if (n > 0) types.append(',');
            types.append(c.componentType());
            if (++n >= 8) break;
        }
        return "components=" + comps.size() + " types=" + types + " style=" + nullToEmpty(plan.styleProfile());
    }

    private static String detectRoofType(LlmPlan plan) {
        if (plan == null || plan.components() == null) return null;
        for (Component c : plan.components()) {
            if (c == null || c.params() == null) continue;
            Object rt = c.params().get("roof_type");
            if (rt == null) rt = c.params().get("roofType");
            if (rt != null) return String.valueOf(rt);
        }
        return null;
    }

    private static String currentRequestId() {
        UUID id = GenerationRequestContext.currentRequestId();
        return id == null ? "" : id.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        String t = text.replace('\n', ' ').trim();
        if (t.length() <= maxLen) return t;
        return t.substring(0, maxLen) + "…";
    }

    public record Snapshot(
            long planValidSuccess,
            long planValidFail,
            long geometrySuccess,
            long geometryFail,
            long worldApplySuccess,
            long worldApplyFail,
            long intentFidelitySuccess,
            long intentFidelityFail,
            long autofillEvents
    ) {
        /** Backward-compatible accessors used by Phase-1 tests. */
        public long parseSuccess() { return planValidSuccess; }
        public long parseFail() { return planValidFail; }
        public long buildSuccess() { return geometrySuccess; }
        public long buildFail() { return geometryFail; }

        @Override
        public String toString() {
            return String.format(
                    Locale.ROOT,
                    "plan_valid_ok=%d plan_valid_fail=%d geometry_ok=%d geometry_fail=%d "
                            + "world_apply_ok=%d world_apply_fail=%d fidelity_ok=%d fidelity_fail=%d autofill=%d",
                    planValidSuccess, planValidFail, geometrySuccess, geometryFail,
                    worldApplySuccess, worldApplyFail,
                    intentFidelitySuccess, intentFidelityFail, autofillEvents);
        }
    }
}
