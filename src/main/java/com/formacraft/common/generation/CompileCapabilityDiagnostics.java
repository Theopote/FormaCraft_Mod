package com.formacraft.common.generation;

import com.formacraft.common.llm.dto.CapabilityGap;

/**
 * Thread-local holder for hard capability gaps during a plan compile pass.
 * Lives in {@code common} so generators may signal explicit failure without
 * depending on the server assembly package.
 */
public final class CompileCapabilityDiagnostics {

    private static final ThreadLocal<CapabilityGap> LAST_GAP = new ThreadLocal<>();

    private CompileCapabilityDiagnostics() {}

    public static void clear() {
        LAST_GAP.remove();
    }

    public static void set(CapabilityGap gap) {
        if (gap == null) {
            LAST_GAP.remove();
        } else {
            LAST_GAP.set(gap);
        }
    }

    public static CapabilityGap get() {
        return LAST_GAP.get();
    }

    public static boolean hasGap() {
        return LAST_GAP.get() != null;
    }
}
