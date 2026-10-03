package com.formacraft.common.typology;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Registry of structural typology interpreters supplied by the runtime.
 * This shared registry never constructs server implementations.
 */
public final class TypologyInterpreterRegistry {

    private static volatile Map<String, TypologyInterpreter> cached = Map.of();

    private TypologyInterpreterRegistry() {}

    public static TypologyInterpreter get(String typologyId) {
        if (typologyId == null || typologyId.isBlank()) {
            return null;
        }
        return cached.get(typologyId.trim().toLowerCase(Locale.ROOT));
    }

    public static boolean has(String typologyId) {
        return get(typologyId) != null;
    }

    public static void register(TypologyInterpreter interpreter) {
        if (interpreter == null || interpreter.typologyId() == null || interpreter.typologyId().isBlank()) {
            return;
        }
        synchronized (TypologyInterpreterRegistry.class) {
            Map<String, TypologyInterpreter> next = new LinkedHashMap<>(cached);
            next.put(interpreter.typologyId().trim().toLowerCase(Locale.ROOT), interpreter);
            cached = Map.copyOf(next);
        }
    }

}
