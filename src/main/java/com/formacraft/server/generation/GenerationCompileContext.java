package com.formacraft.server.generation;

import com.formacraft.common.generation.routing.GenerationAuthority;
import com.formacraft.common.generation.routing.PlanMetaAccess;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Explicit compile-scoped state formerly split across ThreadLocals on
 * {@code UnifiedGeneratorRouter}. Prefer {@link #runWith}; setters on the router
 * still install/replace the current context for incremental migration.
 */
public final class GenerationCompileContext {
    private static final ThreadLocal<GenerationCompileContext> CURRENT = new ThreadLocal<>();

    private final GenerationAuthority authority;
    private final Map<String, Object> planMeta;
    private final boolean typologyExclusive;

    public GenerationCompileContext(
            GenerationAuthority authority,
            Map<String, Object> planMeta,
            boolean typologyExclusive
    ) {
        this.authority = authority;
        this.planMeta = planMeta == null || planMeta.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(planMeta);
        this.typologyExclusive = typologyExclusive;
    }

    public static GenerationCompileContext fromPlan(LlmPlan plan, boolean typologyExclusive) {
        return new GenerationCompileContext(
                PlanMetaAccess.planGenerationAuthority(plan),
                plan == null ? null : plan.planMeta(),
                typologyExclusive
        );
    }

    public GenerationAuthority authority() {
        return authority;
    }

    public Map<String, Object> planMeta() {
        return planMeta;
    }

    public boolean typologyExclusive() {
        return typologyExclusive;
    }

    public GenerationCompileContext withAuthority(GenerationAuthority next) {
        return new GenerationCompileContext(next, planMeta, typologyExclusive);
    }

    public GenerationCompileContext withPlanMeta(Map<String, Object> next) {
        return new GenerationCompileContext(authority, next, typologyExclusive);
    }

    public GenerationCompileContext withTypologyExclusive(boolean next) {
        return new GenerationCompileContext(authority, planMeta, next);
    }

    public static GenerationCompileContext current() {
        return CURRENT.get();
    }

    public static void install(GenerationCompileContext ctx) {
        if (ctx == null) CURRENT.remove();
        else CURRENT.set(ctx);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static <T> T runWith(GenerationCompileContext ctx, Supplier<T> fn) {
        GenerationCompileContext prev = CURRENT.get();
        try {
            install(ctx);
            return fn.get();
        } finally {
            if (prev == null) CURRENT.remove();
            else CURRENT.set(prev);
        }
    }

    public static void runWith(GenerationCompileContext ctx, Runnable fn) {
        runWith(ctx, () -> {
            fn.run();
            return null;
        });
    }
}
