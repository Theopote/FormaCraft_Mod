package com.formacraft.server.generation.component.adaptor;

import com.formacraft.common.component.GenerationDiagnostic;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.routing.GenerationAuthority;
import com.formacraft.common.llm.dto.Component;
import net.minecraft.server.world.ServerWorld;

import java.util.Optional;

/** Authority-first constraints on hint-based {@link ComponentRouteResolver} decisions. */
public final class GenerationRouteContract {
    private GenerationRouteContract() {}

    public static ComponentExecutorId requiredExecutor(GenerationAuthority authority) {
        if (authority == null) {
            authority = GenerationAuthority.COMPONENTS;
        }
        return switch (authority) {
            case ASSEMBLY -> ComponentExecutorId.ASSEMBLY;
            case SKELETON -> ComponentExecutorId.SKELETON;
            default -> ComponentExecutorId.COMPONENT_PIPELINE;
        };
    }

    public static boolean strictEmptyFailure(GenerationAuthority authority) {
        if (authority == null) {
            return false;
        }
        return authority == GenerationAuthority.CAPTURED_LIBRARY
                || authority == GenerationAuthority.ASSEMBLY
                || authority == GenerationAuthority.SKELETON;
    }

    public static Optional<GenerationDiagnostic> validate(
            GenerationAuthority authority,
            ComponentRouteDecision hint,
            SemanticComponent semantic,
            ServerWorld world
    ) {
        if (authority == null) {
            authority = GenerationAuthority.COMPONENTS;
        }
        Component c = semantic == null ? null : semantic.source();
        if (c == null) {
            return Optional.empty();
        }
        if (ComponentRouteHints.acceptsStair(c)) {
            return Optional.empty();
        }

        ComponentExecutorId required = requiredExecutor(authority);
        ComponentExecutorId hintExecutor = hint == null ? ComponentExecutorId.NONE : hint.executor();

        Optional<GenerationDiagnostic> capability = validateCapability(authority, semantic, world);
        if (capability.isPresent()) {
            return capability;
        }

        if (required == hintExecutor) {
            return Optional.empty();
        }

        if (required == ComponentExecutorId.COMPONENT_PIPELINE
                && (hintExecutor == ComponentExecutorId.SKELETON
                || hintExecutor == ComponentExecutorId.ASSEMBLY)) {
            return Optional.of(new GenerationDiagnostic(
                    "generation_authority_conflict",
                    "generation_authority=" + authority + " requires component pipeline but route hint="
                            + hintExecutor + " (" + (hint == null ? "" : hint.reason()) + ")"));
        }

        if (required == ComponentExecutorId.SKELETON && hintExecutor != ComponentExecutorId.SKELETON) {
            return Optional.of(new GenerationDiagnostic(
                    "generation_authority_conflict",
                    "generation_authority=SKELETON but route hint=" + hintExecutor));
        }

        if (required == ComponentExecutorId.ASSEMBLY && hintExecutor != ComponentExecutorId.ASSEMBLY) {
            return Optional.of(new GenerationDiagnostic(
                    "generation_authority_conflict",
                    "generation_authority=ASSEMBLY but route hint=" + hintExecutor));
        }

        return Optional.empty();
    }

    private static Optional<GenerationDiagnostic> validateCapability(
            GenerationAuthority authority,
            SemanticComponent semantic,
            ServerWorld world
    ) {
        return switch (authority) {
            case SKELETON -> ComponentRouteHints.canRunSkeleton(semantic, world)
                    ? Optional.empty()
                    : Optional.of(new GenerationDiagnostic(
                            "generation_authority_conflict",
                            "generation_authority=SKELETON but skeleton payload is not runnable"));
            case ASSEMBLY -> ComponentRouteHints.canRunAssembly(semantic, world)
                    ? Optional.empty()
                    : Optional.of(new GenerationDiagnostic(
                            "generation_authority_conflict",
                            "generation_authority=ASSEMBLY but assembly payload is not routable"));
            case CAPTURED_LIBRARY -> ComponentRouteHints.hasExplicitLibraryRequest(semantic.source())
                    ? Optional.empty()
                    : Optional.of(new GenerationDiagnostic(
                            "captured_library_miss",
                            "generation_authority=CAPTURED_LIBRARY but no group_request/component_request feature"));
            default -> Optional.empty();
        };
    }

    public static ComponentPipelinePlan capturedLibraryPlan(Component c) {
        boolean hasLibrary = ComponentRouteHints.hasExplicitLibraryRequest(c);
        return new ComponentPipelinePlan(
                hasLibrary,
                true,
                java.util.List.of(
                        ComponentPipelineStep.of(ComponentPipelineStepId.LIBRARY_EXPANDER, hasLibrary),
                        ComponentPipelineStep.of(ComponentPipelineStepId.COMPONENT_REGISTRY, false),
                        ComponentPipelineStep.of(ComponentPipelineStepId.TYPOLOGY, false),
                        ComponentPipelineStep.of(ComponentPipelineStepId.STRUCTURE_ADAPTOR, false)));
    }
}
