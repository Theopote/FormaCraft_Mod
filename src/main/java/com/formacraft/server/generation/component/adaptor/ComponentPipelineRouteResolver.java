package com.formacraft.server.generation.component.adaptor;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.Component;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.List;

/** Hint-only sub-decisions for the component pipeline. */
public final class ComponentPipelineRouteResolver {
    private ComponentPipelineRouteResolver() {}

    public static ComponentPipelinePlan resolve(SemanticComponent semantic, ServerWorld world) {
        if (semantic == null || semantic.source() == null) {
            return ComponentPipelinePlan.empty();
        }
        Component c = semantic.source();
        boolean explicitLibrary = ComponentRouteHints.hasExplicitLibraryRequest(c);
        // Window/door requests embedded in a whole typology describe details;
        // they must never replace the building with a single library component.
        if ("STRUCTURE".equalsIgnoreCase(c.componentType()) && ComponentRouteHints.hasTypologyHint(c)
                && ComponentGenerationPipeline.resolveAuthority(c) != com.formacraft.common.generation.routing.GenerationAuthority.CAPTURED_LIBRARY) {
            explicitLibrary = false;
        }
        boolean allowTypologyOverride = c.params() != null
                && Boolean.TRUE.equals(c.params().get("allow_typology_override"));
        boolean skipTypologyWhenLibrary = explicitLibrary && !allowTypologyOverride;

        List<ComponentPipelineStep> steps = new ArrayList<>();
        if (explicitLibrary) {
            steps.add(ComponentPipelineStep.of(ComponentPipelineStepId.LIBRARY_EXPANDER, true));
        }
        steps.add(ComponentPipelineStep.of(
                ComponentPipelineStepId.COMPONENT_REGISTRY,
                ComponentRouteHints.hasRegisteredGenerator(c.componentType())));

        boolean typologyEligible = world != null
                && ComponentRouteHints.hasTypologyHint(c)
                && !skipTypologyWhenLibrary;
        steps.add(ComponentPipelineStep.of(ComponentPipelineStepId.TYPOLOGY, typologyEligible));

        boolean structureEligible = world != null
                && ComponentRouteHints.structureFallbackEligible(semantic)
                && !skipTypologyWhenLibrary;
        steps.add(ComponentPipelineStep.of(ComponentPipelineStepId.STRUCTURE_ADAPTOR, structureEligible));

        return new ComponentPipelinePlan(explicitLibrary, skipTypologyWhenLibrary, List.copyOf(steps));
    }
}
