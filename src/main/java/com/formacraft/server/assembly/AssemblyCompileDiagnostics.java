package com.formacraft.server.assembly;

import com.formacraft.common.generation.CompileCapabilityDiagnostics;
import com.formacraft.common.llm.dto.CapabilityGap;

import java.util.ArrayList;
import java.util.List;

/**
 * Thread-local holder for ASSEMBLY compile failure and soft warnings within a plan compile pass.
 * Hard gaps delegate to {@link CompileCapabilityDiagnostics} so common generators can signal
 * without importing this server type.
 */
public final class AssemblyCompileDiagnostics {

    public record SoftWarning(String code, String message, String componentType, String componentId) {}

    private static final ThreadLocal<List<SoftWarning>> SOFT_WARNINGS = ThreadLocal.withInitial(ArrayList::new);

    private AssemblyCompileDiagnostics() {}

    public static void clear() {
        CompileCapabilityDiagnostics.clear();
        SOFT_WARNINGS.remove();
    }

    public static void set(CapabilityGap gap) {
        CompileCapabilityDiagnostics.set(gap);
    }

    public static CapabilityGap get() {
        return CompileCapabilityDiagnostics.get();
    }

    public static boolean hasGap() {
        return CompileCapabilityDiagnostics.hasGap();
    }

    public static void addSoftWarning(String code, String message, String componentType, String componentId) {
        SOFT_WARNINGS.get().add(new SoftWarning(
                code == null ? "SOFT_WARNING" : code,
                message == null ? "" : message,
                componentType,
                componentId
        ));
    }

    /** Restore soft warnings captured on another thread (e.g. off-thread plan compile). */
    public static void replaceSoftWarnings(List<SoftWarning> warnings) {
        SOFT_WARNINGS.set(warnings == null || warnings.isEmpty()
                ? new ArrayList<>()
                : new ArrayList<>(warnings));
    }

    public static List<SoftWarning> softWarnings() {
        return List.copyOf(SOFT_WARNINGS.get());
    }

    public static boolean hasSoftWarnings() {
        return !SOFT_WARNINGS.get().isEmpty();
    }
}
