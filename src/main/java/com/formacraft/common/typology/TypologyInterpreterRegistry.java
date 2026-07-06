package com.formacraft.common.typology;

import com.formacraft.FormacraftMod;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Registry of structural typology interpreters.
 * Bootstraps legacy-delegating interpreters from {@link StructuralTypologyRegistry}.
 */
public final class TypologyInterpreterRegistry {

    private static volatile Map<String, TypologyInterpreter> cached;

    private TypologyInterpreterRegistry() {}

    public static TypologyInterpreter get(String typologyId) {
        if (typologyId == null || typologyId.isBlank()) {
            return null;
        }
        return interpreters().get(typologyId.trim().toLowerCase(Locale.ROOT));
    }

    public static boolean has(String typologyId) {
        return get(typologyId) != null;
    }

    public static void register(TypologyInterpreter interpreter) {
        if (interpreter == null || interpreter.typologyId() == null || interpreter.typologyId().isBlank()) {
            return;
        }
        synchronized (TypologyInterpreterRegistry.class) {
            Map<String, TypologyInterpreter> next = new LinkedHashMap<>(interpreters());
            next.put(interpreter.typologyId().trim().toLowerCase(Locale.ROOT), interpreter);
            cached = Map.copyOf(next);
        }
    }

    private static Map<String, TypologyInterpreter> interpreters() {
        if (cached != null) {
            return cached;
        }
        synchronized (TypologyInterpreterRegistry.class) {
            if (cached != null) {
                return cached;
            }
            cached = bootstrapFromStructuralRegistry();
            return cached;
        }
    }

    private static Map<String, TypologyInterpreter> bootstrapFromStructuralRegistry() {
        Map<String, TypologyInterpreter> out = new LinkedHashMap<>();
        for (StructuralTypologyRegistry.TypologyDef def : StructuralTypologyRegistry.listTypologies()) {
            if (def == null || def.id() == null || def.id().isBlank()) {
                continue;
            }
            String legacy = def.legacyInterpreterId();
            if (legacy == null || legacy.isBlank()) {
                continue;
            }
            out.put(def.id().toLowerCase(Locale.ROOT),
                    new LegacyDelegatingTypologyInterpreter(def.id(), legacy, def.defaultParams()));
        }
        FormacraftMod.LOGGER.info("TypologyInterpreterRegistry bootstrapped {} interpreters", out.size());
        return Map.copyOf(out);
    }
}
