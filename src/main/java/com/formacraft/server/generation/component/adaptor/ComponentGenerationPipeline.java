package com.formacraft.server.generation.component.adaptor;

import com.formacraft.FormacraftMod;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.component.ComponentGenerationOutcome;
import com.formacraft.common.component.GenerationDiagnostic;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.List;

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
            } else if (plan.isStepEligible(ComponentPipelineStepId.COMPONENT_REGISTRY)) {
                UnifiedGeneratorRouter.ComponentGeneratorAttempt genAttempt =
                        UnifiedGeneratorRouter.tryComponentGeneratorResult(semantic, componentType);
                if (genAttempt.hardFail() != null) {
                    return genAttempt.hardFail();
                }
                base = genAttempt.patches();
                if (base.isEmpty() && !expander.patches().isEmpty()) {
                    base = expander.patches();
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
            }
            expander = UnifiedGeneratorRouter.applyExpandersResult(semantic, world, base, false, false);
            if (expander.hardFail() != null) {
                return expander.hardFail();
            }
            base = expander.patches();
            routeDiagnostics.addAll(expander.diagnostics());
        }

        boolean allowTypologyOverride = c.params() != null
                && Boolean.TRUE.equals(c.params().get("allow_typology_override"));
        boolean skipTypology = expander.libraryCommitted() && !allowTypologyOverride;

        if (!skipTypology
                && plan.isStepEligible(ComponentPipelineStepId.TYPOLOGY)
                && world != null
                && com.formacraft.common.typology.TypologyComponentRouter.hasTypologyHint(c)) {
            List<BlockPatch> typologyPatches = com.formacraft.common.typology.TypologyComponentRouter
                    .tryGenerate(semantic, world);
            if (!typologyPatches.isEmpty()) {
                FormacraftMod.LOGGER.debug("ComponentGenerationPipeline: typology for {}", componentType);
                return UnifiedGeneratorRouter.withDiagnostics(
                        RoutedGenerationResult.success(typologyPatches), routeDiagnostics);
            }
        }

        if (!base.isEmpty()) {
            ComponentGenerationOutcome.Status st = expander.degraded()
                    ? ComponentGenerationOutcome.Status.PARTIAL_SUCCESS
                    : ComponentGenerationOutcome.Status.SUCCESS;
            return new RoutedGenerationResult(st, base, routeDiagnostics);
        }

        if (!skipTypology
                && plan.isStepEligible(ComponentPipelineStepId.STRUCTURE_ADAPTOR)
                && world != null
                && UnifiedGeneratorRouter.shouldUseStructureFallbackForRouting(semantic)) {
            List<BlockPatch> structurePatches = StructureGeneratorAdaptor.tryGenerate(semantic, world);
            if (!structurePatches.isEmpty()) {
                FormacraftMod.LOGGER.debug("ComponentGenerationPipeline: structure adaptor for {}", componentType);
                return UnifiedGeneratorRouter.withDiagnostics(
                        RoutedGenerationResult.success(structurePatches), routeDiagnostics);
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
        return UnifiedGeneratorRouter.withDiagnostics(RoutedGenerationResult.success(List.of()), routeDiagnostics);
    }
}
