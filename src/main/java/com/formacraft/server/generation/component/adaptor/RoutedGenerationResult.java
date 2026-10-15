package com.formacraft.server.generation.component.adaptor;

import com.formacraft.common.component.ComponentGenerationOutcome;
import com.formacraft.common.component.GenerationDiagnostic;
import com.formacraft.common.generation.routing.GenerationAuthority;
import com.formacraft.common.patch.BlockPatch;

import java.util.List;

/** Structured outcome of {@link UnifiedGeneratorRouter} for plan compilers. */
public record RoutedGenerationResult(
        ComponentGenerationOutcome.Status status,
        List<BlockPatch> patches,
        List<GenerationDiagnostic> diagnostics,
        ComponentExecutorId executor,
        ComponentPipelineStepId pipelineStep,
        GenerationAuthority authority,
        String routeReason
) {
    public RoutedGenerationResult {
        patches = patches == null ? List.of() : List.copyOf(patches);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        if (status == null) status = ComponentGenerationOutcome.Status.INVALID_INPUT;
        if (executor == null) executor = ComponentExecutorId.NONE;
    }

    public RoutedGenerationResult(
            ComponentGenerationOutcome.Status status,
            List<BlockPatch> patches,
            List<GenerationDiagnostic> diagnostics
    ) {
        this(status, patches, diagnostics, null, null, null, null);
    }

    public RoutedGenerationResult(
            ComponentGenerationOutcome.Status status,
            List<BlockPatch> patches,
            List<GenerationDiagnostic> diagnostics,
            ComponentExecutorId executor,
            ComponentPipelineStepId pipelineStep
    ) {
        this(status, patches, diagnostics, executor, pipelineStep, null, null);
    }

    public static RoutedGenerationResult from(ComponentGenerationOutcome outcome) {
        if (outcome == null) {
            return failure(ComponentGenerationOutcome.Status.INVALID_INPUT, "null_outcome", "outcome_null");
        }
        return new RoutedGenerationResult(outcome.status(), outcome.patches(), outcome.diagnostics());
    }

    public static RoutedGenerationResult success(List<BlockPatch> patches) {
        return new RoutedGenerationResult(ComponentGenerationOutcome.Status.SUCCESS, patches, List.of());
    }

    public static RoutedGenerationResult success(
            List<BlockPatch> patches,
            ComponentExecutorId executor,
            ComponentPipelineStepId pipelineStep
    ) {
        return success(patches, executor, pipelineStep, null, null);
    }

    public static RoutedGenerationResult success(
            List<BlockPatch> patches,
            ComponentExecutorId executor,
            ComponentPipelineStepId pipelineStep,
            GenerationAuthority authority,
            String routeReason
    ) {
        return new RoutedGenerationResult(
                ComponentGenerationOutcome.Status.SUCCESS,
                patches,
                List.of(),
                executor,
                pipelineStep,
                authority,
                routeReason);
    }

    public static RoutedGenerationResult failure(
            ComponentGenerationOutcome.Status status, String code, String message) {
        return failure(status, code, message, null, null);
    }

    public static RoutedGenerationResult failure(
            ComponentGenerationOutcome.Status status,
            String code,
            String message,
            GenerationAuthority authority,
            String routeReason
    ) {
        return new RoutedGenerationResult(
                status,
                List.of(),
                List.of(new GenerationDiagnostic(code, message)),
                ComponentExecutorId.NONE,
                null,
                authority,
                routeReason);
    }

    public RoutedGenerationResult withAttribution(
            ComponentExecutorId executor,
            ComponentPipelineStepId pipelineStep
    ) {
        return new RoutedGenerationResult(status, patches, diagnostics, executor, pipelineStep, authority, routeReason);
    }

    public RoutedGenerationResult withProvenance(GenerationAuthority authority, String routeReason) {
        return new RoutedGenerationResult(status, patches, diagnostics, executor, pipelineStep, authority, routeReason);
    }

    public boolean commitAllowed() {
        return status == ComponentGenerationOutcome.Status.SUCCESS
                || status == ComponentGenerationOutcome.Status.PARTIAL_SUCCESS;
    }

    public boolean isHardFail() {
        return switch (status) {
            case ADAPT_FAILED, LOAD_FAILED, CARVE_FAILED, TEMPLATE_MISSING,
                 INCOMPATIBLE_MOUNT, GEOMETRY_INVALID, EMPTY_REJECTED, INVALID_INPUT -> true;
            default -> false;
        };
    }

    public boolean degraded() {
        return status == ComponentGenerationOutcome.Status.PARTIAL_SUCCESS;
    }
}
