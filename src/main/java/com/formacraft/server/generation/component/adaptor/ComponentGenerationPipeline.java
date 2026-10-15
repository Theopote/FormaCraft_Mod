package com.formacraft.server.generation.component.adaptor;

import com.formacraft.FormacraftMod;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.component.ComponentGenerationOutcome;
import com.formacraft.common.component.GenerationDiagnostic;
import com.formacraft.common.generation.routing.GenerationAuthority;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.network.metrics.TypologyRoutingMetrics;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.server.world.ServerWorld;

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
            String componentType
    ) {
        FormacraftMod.LOGGER.debug(
                "ComponentGenerationPipeline: explicitLibraryFirst={} skipTypologyWhenLibrary={} steps={}",
                plan.explicitLibraryFirst(),
                plan.skipTypologyWhenLibraryCommits(),
                plan.steps());

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
                FormacraftMod.LOGGER.error(
                        "ComponentGenerationPipeline: library hard-fail status={}",
                        expander.hardFail().status());
                return expander.hardFail();
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

        GenerationAuthority authority = resolveAuthority(c);
        boolean allowTypologyOverride = c.params() != null
                && Boolean.TRUE.equals(c.params().get("allow_typology_override"));
        boolean skipTypology = (expander.libraryCommitted() && !allowTypologyOverride)
                || authority == GenerationAuthority.CAPTURED_LIBRARY && expander.libraryCommitted()
                || authority == GenerationAuthority.ASSEMBLY
                || authority == GenerationAuthority.SKELETON;

        boolean canReplace = authority.allowsTypologyReplace(allowTypologyOverride);
        boolean canFillEmpty = authority.allowsEmptyBaseFallback();

        if (!skipTypology
                && plan.isStepEligible(ComponentPipelineStepId.TYPOLOGY)
                && world != null
                && com.formacraft.common.typology.TypologyComponentRouter.hasTypologyHint(c)
                && (base.isEmpty() ? canFillEmpty : canReplace)) {
            List<BlockPatch> typologyPatches = com.formacraft.common.typology.TypologyComponentRouter
                    .tryGenerate(semantic, world);
            if (!typologyPatches.isEmpty()) {
                FormacraftMod.LOGGER.debug(
                        "ComponentGenerationPipeline: typology for {} authority={} replace={}",
                        componentType, authority, !base.isEmpty());
                TypologyRoutingMetrics.recordExecutorHit(
                        ComponentExecutorId.COMPONENT_PIPELINE.name(),
                        ComponentPipelineStepId.TYPOLOGY.name(),
                        componentType);
                return UnifiedGeneratorRouter.withDiagnostics(
                        RoutedGenerationResult.success(
                                typologyPatches,
                                ComponentExecutorId.COMPONENT_PIPELINE,
                                ComponentPipelineStepId.TYPOLOGY),
                        routeDiagnostics);
            }
        }

        if (!base.isEmpty()) {
            ComponentGenerationOutcome.Status st = expander.degraded()
                    ? ComponentGenerationOutcome.Status.PARTIAL_SUCCESS
                    : ComponentGenerationOutcome.Status.SUCCESS;
            ComponentPipelineStepId step = baseStep != null
                    ? baseStep
                    : ComponentPipelineStepId.COMPONENT_REGISTRY;
            TypologyRoutingMetrics.recordExecutorHit(
                    ComponentExecutorId.COMPONENT_PIPELINE.name(), step.name(), componentType);
            return new RoutedGenerationResult(
                    st, base, routeDiagnostics, ComponentExecutorId.COMPONENT_PIPELINE, step);
        }

        if (!skipTypology
                && canFillEmpty
                && plan.isStepEligible(ComponentPipelineStepId.STRUCTURE_ADAPTOR)
                && world != null
                && UnifiedGeneratorRouter.shouldUseStructureFallbackForRouting(semantic)) {
            List<BlockPatch> structurePatches = StructureGeneratorAdaptor.tryGenerate(semantic, world);
            if (!structurePatches.isEmpty()) {
                FormacraftMod.LOGGER.debug("ComponentGenerationPipeline: structure adaptor for {}", componentType);
                TypologyRoutingMetrics.recordExecutorHit(
                        ComponentExecutorId.COMPONENT_PIPELINE.name(),
                        ComponentPipelineStepId.STRUCTURE_ADAPTOR.name(),
                        componentType);
                return UnifiedGeneratorRouter.withDiagnostics(
                        RoutedGenerationResult.success(
                                structurePatches,
                                ComponentExecutorId.COMPONENT_PIPELINE,
                                ComponentPipelineStepId.STRUCTURE_ADAPTOR),
                        routeDiagnostics);
            }
        }

        if (base.isEmpty() && UnifiedGeneratorRouter.isRequiredComponentType(componentType)
                && !UnifiedGeneratorRouter.isExplicitlyDisabled(c)) {
            FormacraftMod.LOGGER.error(
                    "ComponentGenerationPipeline: required component empty type={}", componentType);
            return RoutedGenerationResult.failure(
                    ComponentGenerationOutcome.Status.GEOMETRY_INVALID,
                    "required_component_empty",
                    componentType == null ? "required_empty" : componentType);
        }

        FormacraftMod.LOGGER.debug("ComponentGenerationPipeline: no patches for {}, skipping", componentType);
        return UnifiedGeneratorRouter.withDiagnostics(
                RoutedGenerationResult.success(List.of(), ComponentExecutorId.COMPONENT_PIPELINE, null),
                routeDiagnostics);
    }

    private static GenerationAuthority resolveAuthority(Component c) {
        Map<String, Object> planMeta = null;
        GenerationAuthority planAuthority = UnifiedGeneratorRouter.planGenerationAuthority();
        if (planAuthority != null) {
            planMeta = new HashMap<>();
            planMeta.put(GenerationAuthority.META_KEY, planAuthority.name());
        }
        return GenerationAuthority.resolve(c, planMeta);
    }
}
