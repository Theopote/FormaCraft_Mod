package com.formacraft.server.assembly;

import com.formacraft.common.llm.dto.CapabilityGap;

/**
 * Thread-local holder for the latest ASSEMBLY compile failure within a plan compile pass.
 */
public final class AssemblyCompileDiagnostics {

    private static final ThreadLocal<CapabilityGap> LAST_GAP = new ThreadLocal<>();

    private AssemblyCompileDiagnostics() {}

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
