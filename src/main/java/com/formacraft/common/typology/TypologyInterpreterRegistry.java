package com.formacraft.common.typology;

import com.formacraft.FormacraftMod;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Registry of structural typology interpreters.
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

        // Phase 2: native parametric interpreters (no landmark id injection)
        registerBuiltIn(out, new com.formacraft.server.generation.typology.interpreter.DenseEavesPagodaInterpreter());
        registerBuiltIn(out, new com.formacraft.server.generation.typology.interpreter.TailiangTimberHallInterpreter());
        registerBuiltIn(out, new com.formacraft.server.generation.typology.interpreter.RadialTerraceHallInterpreter());
        registerBuiltIn(out, new com.formacraft.server.generation.typology.interpreter.StadiumBowlInterpreter());
        registerBuiltIn(out, new com.formacraft.server.generation.typology.interpreter.SuspensionBridgeInterpreter());
        registerBuiltIn(out, new com.formacraft.server.generation.typology.interpreter.GothicCathedralHallInterpreter());

        FormacraftMod.LOGGER.info("TypologyInterpreterRegistry bootstrapped {} interpreters", out.size());
        return Map.copyOf(out);
    }

    private static void registerBuiltIn(Map<String, TypologyInterpreter> out, TypologyInterpreter interpreter) {
        if (interpreter == null || interpreter.typologyId() == null) {
            return;
        }
        out.put(interpreter.typologyId().toLowerCase(Locale.ROOT), interpreter);
    }
}
