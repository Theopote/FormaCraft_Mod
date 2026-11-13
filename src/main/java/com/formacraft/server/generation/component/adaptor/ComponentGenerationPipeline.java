package com.formacraft.server.generation.component.adaptor;

import com.formacraft.FormacraftMod;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.component.ComponentGenerationOutcome;
import com.formacraft.common.component.GenerationDiagnostic;
import com.formacraft.common.generation.routing.GenerationAuthority;
import com.formacraft.common.generation.routing.GenerationFallbackMatrix;
import com.formacraft.common.generation.routing.GenerationFallbackPolicy;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.network.metrics.TypologyRoutingMetrics;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.typology.TypologyGenerationException;
import net.minecraft.server.world.ServerWorld;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Executes {@link ComponentPipelinePlan} stages (registry → typology → structure). */
final class ComponentGenerationPipeline {
    private ComponentGenerationPipeline() {}

    static RoutedGenerationResult execute(
            ComponentPipelinePlan plan,
            SemanticComponent semantic,
            ServerWorld world,
            Component c,
            String componentType,
            GenerationAuthority authority
    ) {
        if (authority == GenerationAuthority.CAPTURED_LIBRARY) {
            return executeCapturedLibrary(plan, semantic, world, c, componentType);
        }

        FormacraftMod.LOGGER.debug(
                "ComponentGenerationPipeline: explicitLibraryFirst={} skipTypologyWhenLibrary={} steps={} authority={}",
                plan.explicitLibraryFirst(),
                plan.skipTypologyWhenLibraryCommits(),
                plan.steps(),
                authority);

        boolean explicitLibrary = plan.explicitLibraryFirst();
        List<BlockPatch> base = new ArrayList<>();
        UnifiedGeneratorRouter.ExpanderStage expander;
        List<GenerationDiagnostic> routeDiagnostics = new ArrayList<>();
        ComponentPipelineStepId baseStep = null;

        if (explicitLibrary && plan.isStepEligible(ComponentPipelineStepId.LIBRARY_EXPANDER)) {
            boolean hasGroupRequest = UnifiedGeneratorRouter.hasFeaturePrefix(c, "group_request:");
            boolean hasComponentRequest = UnifiedGeneratorRouter.hasFeaturePrefix(c, "component_request:");
            expander = UnifiedGeneratorRouter.applyExpandersResult(semantic, world, List.of(), hasGroupRequest, hasComponentRequest);
            if (expander.hardFail() != null) {
                // Envelope conflicts are hard policy — never soft-skip into registry/typology.
                if (isEnvelopeLibraryConflict(expander.hardFail())) {
                    return expander.hardFail();
                }
                // Other library misses are optional — soft-skip and continue with registry/typology.
                FormacraftMod.LOGGER.warn(
                        "ComponentGenerationPipeline: library hard-fail soft-skipped status={}",
                        expander.hardFail().status());
                routeDiagnostics.addAll(expander.hardFail().diagnostics());
                expander = new UnifiedGeneratorRouter.ExpanderStage(List.of(), null, expander.diagnostics(), false);
            }
            routeDiagnostics.addAll(expander.diagnostics());
            if (expander.libraryCommitted()) {
                base = expander.patches();
                baseStep = ComponentPipelineStepId.LIBRARY_EXPANDER;
            } else if (plan.isStepEligible(ComponentPipelineStepId.COMPONENT_REGISTRY)) {
                UnifiedGeneratorRouter.ComponentGeneratorAttempt genAttempt =
                        UnifiedGeneratorRouter.tryComponentGeneratorResult(semantic, componentType);
                if (genAttempt.hardFail() != null) {
                    return genAttempt.hardFail();
                }
                base = genAttempt.patches();
                baseStep = ComponentPipelineStepId.COMPONENT_REGISTRY;
                if (base.isEmpty() && !expander.patches().isEmpty()) {
                    base = expander.patches();
                    baseStep = ComponentPipelineStepId.LIBRARY_EXPANDER;
                }
            }
        } else {
            if (plan.isStepEligible(ComponentPipelineStepId.COMPONENT_REGISTRY)) {
                UnifiedGeneratorRouter.ComponentGeneratorAttempt genAttempt =
                        UnifiedGeneratorRouter.tryComponentGeneratorResult(semantic, componentType);
                if (genAttempt.hardFail() != null) {
                    return genAttempt.hardFail();
                }
                base = genAttempt.patches();
                baseStep = ComponentPipelineStepId.COMPONENT_REGISTRY;
            }
            expander = UnifiedGeneratorRouter.applyExpandersResult(semantic, world, base, false, false);
            if (expander.hardFail() != null) {
                return expander.hardFail();
            }
            base = expander.patches();
            routeDiagnostics.addAll(expander.diagnostics());
        }

        GenerationFallbackPolicy policy = GenerationFallbackPolicy.forAuthority(authority);
        boolean allowTypologyOverride = c.params() != null
                && Boolean.TRUE.equals(c.params().get("allow_typology_override"));
        boolean skipTypology = (expander.libraryCommitted() && !allowTypologyOverride);

        Map<String, Object> planMeta = UnifiedGeneratorRouter.planMeta();

        boolean canReplace = policy.allowsTypologyReplace(allowTypologyOverride);
        boolean canTypologyFillEmpty = GenerationFallbackMatrix.effectiveTypologyEmpty(
                authority, policy, c, planMeta);
        boolean canStructureFillEmpty = GenerationFallbackMatrix.effectiveStructureEmpty(
                authority, policy, c, planMeta);

        if (!skipTypology
                && plan.isStepEligible(ComponentPipelineStepId.TYPOLOGY)
                && world != null
                && com.formacraft.common.typology.TypologyComponentRouter.hasTypologyHint(c)
                && (base.isEmpty() ? canTypologyFillEmpty : canReplace)) {
            try {
                List<BlockPatch> typologyPatches = com.formacraft.common.typology.TypologyComponentRouter
                        .tryGenerate(semantic, world);
                if (!typologyPatches.isEmpty()) {
                    FormacraftMod.LOGGER.debug(
                            "ComponentGenerationPipeline: typology for {} authority={} replace={}",
                            componentType, authority, !base.isEmpty());
                    String routeReason = authority + ":" + ComponentPipelineStepId.TYPOLOGY.name();
                    TypologyRoutingMetrics.recordExecutorHit(
                            ComponentExecutorId.COMPONENT_PIPELINE.name(),
                            routeReason,
                            componentType);
                    return UnifiedGeneratorRouter.withDiagnostics(
                            RoutedGenerationResult.success(
                                    typologyPatches,
                                    ComponentExecutorId.COMPONENT_PIPELINE,
                                    ComponentPipelineStepId.TYPOLOGY,
                                    authority,
                                    ComponentPipelineStepId.TYPOLOGY.name()),
                            routeDiagnostics);
                }
            } catch (com.formacraft.common.typology.TypologyGenerationException e) {
                String routeReason = authority + ":" + ComponentPipelineStepId.TYPOLOGY.name();
                List<GenerationDiagnostic> failDiags = getGenerationDiagnostics(e, routeDiagnostics);
                FormacraftMod.LOGGER.warn(
                        "ComponentGenerationPipeline: typology hard-fail type={} code={}",
                        componentType, e.code());
                return new RoutedGenerationResult(
                        ComponentGenerationOutcome.Status.GEOMETRY_INVALID,
                        List.of(),
                        failDiags,
                        ComponentExecutorId.COMPONENT_PIPELINE,
                        ComponentPipelineStepId.TYPOLOGY,
                        authority,
                        routeReason);
            }
        }

        if (!base.isEmpty()) {
            ComponentGenerationOutcome.Status st = expander.degraded()
                    ? ComponentGenerationOutcome.Status.PARTIAL_SUCCESS
                    : ComponentGenerationOutcome.Status.SUCCESS;
            ComponentPipelineStepId step = baseStep != null
                    ? baseStep
                    : ComponentPipelineStepId.COMPONENT_REGISTRY;
            String routeReason = authority + ":" + step.name();
            TypologyRoutingMetrics.recordExecutorHit(
                    ComponentExecutorId.COMPONENT_PIPELINE.name(), routeReason, componentType);
            return new RoutedGenerationResult(
                    st,
                    base,
                    routeDiagnostics,
                    ComponentExecutorId.COMPONENT_PIPELINE,
                    step,
                    authority,
                    step.name());
        }

        if (!skipTypology
                && canStructureFillEmpty
                && plan.isStepEligible(ComponentPipelineStepId.STRUCTURE_ADAPTOR)
                && world != null
                && UnifiedGeneratorRouter.shouldUseStructureFallbackForRouting(semantic)) {
            try {
                List<BlockPatch> structurePatches = StructureGeneratorAdaptor.tryGenerate(semantic, world);
                if (!structurePatches.isEmpty()) {
                    FormacraftMod.LOGGER.debug("ComponentGenerationPipeline: structure adaptor for {}", componentType);
                    String routeReason = authority + ":" + ComponentPipelineStepId.STRUCTURE_ADAPTOR.name();
                    TypologyRoutingMetrics.recordExecutorHit(
                            ComponentExecutorId.COMPONENT_PIPELINE.name(),
                            routeReason,
                            componentType);
                    return UnifiedGeneratorRouter.withDiagnostics(
                            RoutedGenerationResult.success(
                                    structurePatches,
                                    ComponentExecutorId.COMPONENT_PIPELINE,
                                    ComponentPipelineStepId.STRUCTURE_ADAPTOR,
                                    authority,
                                    ComponentPipelineStepId.STRUCTURE_ADAPTOR.name()),
                            routeDiagnostics);
                }
            } catch (com.formacraft.common.typology.TypologyGenerationException e) {
                String routeReason = authority + ":" + ComponentPipelineStepId.STRUCTURE_ADAPTOR.name();
                List<com.formacraft.common.component.GenerationDiagnostic> failDiags =
                        new ArrayList<>(routeDiagnostics);
                failDiags.add(new com.formacraft.common.component.GenerationDiagnostic(
                        e.code(), e.getMessage() == null ? e.code() : e.getMessage()));
                for (String d : e.diagnostics()) {
                    failDiags.add(new com.formacraft.common.component.GenerationDiagnostic("TYPOLOGY_DIAG", d));
                }
                return new RoutedGenerationResult(
                        ComponentGenerationOutcome.Status.GEOMETRY_INVALID,
                        List.of(),
                        failDiags,
                        ComponentExecutorId.COMPONENT_PIPELINE,
                        ComponentPipelineStepId.STRUCTURE_ADAPTOR,
                        authority,
                        routeReason);
            }
        }

        if (base.isEmpty() && UnifiedGeneratorRouter.isRequiredComponentType(componentType)
                && !UnifiedGeneratorRouter.isExplicitlyDisabled(c)) {
            FormacraftMod.LOGGER.error(
                    "ComponentGenerationPipeline: required component empty type={}", componentType);
            return RoutedGenerationResult.failure(
                    ComponentGenerationOutcome.Status.GEOMETRY_INVALID,
                    "required_component_empty",
                    componentType == null ? "required_empty" : componentType,
                    authority,
                    "empty");
        }

        FormacraftMod.LOGGER.debug("ComponentGenerationPipeline: no patches for {}, skipping", componentType);
        return UnifiedGeneratorRouter.withDiagnostics(
                RoutedGenerationResult.success(
                        List.of(),
                        ComponentExecutorId.COMPONENT_PIPELINE,
                        null,
                        authority,
                        "skip"),
                routeDiagnostics);
    }

    /** Envelope protection vs explicit library request — hard policy, not optional miss. */
    private static boolean isEnvelopeLibraryConflict(RoutedGenerationResult fail) {
        if (fail == null || fail.diagnostics() == null) {
            return false;
        }
        for (GenerationDiagnostic d : fail.diagnostics()) {
            if (d != null && "component_request_envelope_conflict".equals(d.code())) {
                return true;
            }
        }
        return false;
    }

    private static @NotNull List<GenerationDiagnostic> getGenerationDiagnostics(TypologyGenerationException e, List<GenerationDiagnostic> routeDiagnostics) {
        List<GenerationDiagnostic> failDiags =
                new ArrayList<>(routeDiagnostics);
        failDiags.add(new GenerationDiagnostic(
                e.code(), e.getMessage() == null ? e.code() : e.getMessage()));
        for (String d : e.diagnostics()) {
            failDiags.add(new GenerationDiagnostic(
                    "TYPOLOGY_DIAG", d));
        }
        return failDiags;
    }

    private static RoutedGenerationResult executeCapturedLibrary(
            ComponentPipelinePlan plan,
            SemanticComponent semantic,
            ServerWorld world,
            Component c,
            String componentType
    ) {
        if (!ComponentRouteHints.hasExplicitLibraryRequest(c)) {
            // Soft: demote to COMPONENTS so registry/typology still run.
            FormacraftMod.LOGGER.info(
                    "ComponentGenerationPipeline: CAPTURED_LIBRARY without request — demote type={}",
                    componentType);
            return execute(
                    nativeFallbackPlan(semantic, world),
                    semantic,
                    world,
                    c,
                    componentType,
                    GenerationAuthority.COMPONENTS);
        }
        boolean hasGroupRequest = UnifiedGeneratorRouter.hasFeaturePrefix(c, "group_request:");
        boolean hasComponentRequest = UnifiedGeneratorRouter.hasFeaturePrefix(c, "component_request:");
        UnifiedGeneratorRouter.ExpanderStage expander = UnifiedGeneratorRouter.applyExpandersResult(
                semantic, world, List.of(), hasGroupRequest, hasComponentRequest);
        if (expander.hardFail() != null && isEnvelopeLibraryConflict(expander.hardFail())) {
            return expander.hardFail();
        }
        if (expander.hardFail() != null || !expander.libraryCommitted()
                || expander.patches() == null || expander.patches().isEmpty()) {
            // Library is additive: miss/fail falls through to native COMPONENTS pipeline.
            FormacraftMod.LOGGER.warn(
                    "ComponentGenerationPipeline: library miss/fail — native fallback type={}",
                    componentType);
            List<GenerationDiagnostic> soft = new ArrayList<>();
            if (expander.hardFail() != null) {
                soft.addAll(expander.hardFail().diagnostics());
            }
            soft.addAll(expander.diagnostics());
            RoutedGenerationResult nativeResult = execute(
                    nativeFallbackPlan(semantic, world),
                    semantic,
                    world,
                    c,
                    componentType,
                    GenerationAuthority.COMPONENTS);
            if (!soft.isEmpty() && nativeResult != null) {
                List<GenerationDiagnostic> merged = new ArrayList<>(soft);
                merged.addAll(nativeResult.diagnostics());
                return new RoutedGenerationResult(
                        nativeResult.status(),
                        nativeResult.patches(),
                        merged,
                        nativeResult.executor(),
                        nativeResult.pipelineStep(),
                        nativeResult.authority(),
                        nativeResult.routeReason() == null
                                ? "library_optional_native_fallback"
                                : nativeResult.routeReason());
            }
            return nativeResult;
        }
        List<BlockPatch> base = expander.patches();
        TypologyRoutingMetrics.recordExecutorHit(
                ComponentExecutorId.COMPONENT_PIPELINE.name(),
                ComponentPipelineStepId.LIBRARY_EXPANDER.name(),
                componentType);
        ComponentGenerationOutcome.Status st = expander.degraded()
                ? ComponentGenerationOutcome.Status.PARTIAL_SUCCESS
                : ComponentGenerationOutcome.Status.SUCCESS;
        return new RoutedGenerationResult(
                st,
                base,
                expander.diagnostics(),
                ComponentExecutorId.COMPONENT_PIPELINE,
                ComponentPipelineStepId.LIBRARY_EXPANDER,
                GenerationAuthority.CAPTURED_LIBRARY,
                ComponentPipelineStepId.LIBRARY_EXPANDER.name());
    }

    /** Registry/typology/structure plan that ignores optional library requests. */
    private static ComponentPipelinePlan nativeFallbackPlan(SemanticComponent semantic, ServerWorld world) {
        if (semantic == null || semantic.source() == null) {
            return ComponentPipelinePlan.empty();
        }
        Component c = semantic.source();
        List<ComponentPipelineStep> steps = new ArrayList<>();
        steps.add(ComponentPipelineStep.of(
                ComponentPipelineStepId.COMPONENT_REGISTRY,
                ComponentRouteHints.hasRegisteredGenerator(c.componentType())));
        boolean typologyEligible = world != null && ComponentRouteHints.hasTypologyHint(c);
        steps.add(ComponentPipelineStep.of(ComponentPipelineStepId.TYPOLOGY, typologyEligible));
        boolean structureEligible = world != null && ComponentRouteHints.structureFallbackEligible(semantic);
        steps.add(ComponentPipelineStep.of(ComponentPipelineStepId.STRUCTURE_ADAPTOR, structureEligible));
        return new ComponentPipelinePlan(false, false, List.copyOf(steps));
    }

    static GenerationAuthority resolveAuthority(Component c) {
        Map<String, Object> planMeta = UnifiedGeneratorRouter.planMeta();
        if (planMeta == null) {
            GenerationAuthority planAuthority = UnifiedGeneratorRouter.planGenerationAuthority();
            if (planAuthority != null) {
                planMeta = new HashMap<>();
                planMeta.put(GenerationAuthority.META_KEY, planAuthority.name());
            }
        }
        if (c != null && "STRUCTURE".equalsIgnoreCase(c.componentType())
                && ComponentRouteHints.hasTypologyHint(c)
                && (c.params() == null || GenerationAuthority.parse(c.params().get(GenerationAuthority.PARAM_KEY)) == null)
                && (planMeta == null || GenerationAuthority.parse(planMeta.get(GenerationAuthority.META_KEY)) == null)) {
            return GenerationAuthority.TYPOLOGY;
        }
        return GenerationAuthority.resolve(c, planMeta);
    }
}
