package com.formacraft.server.generation.component.adaptor;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.Component;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.List;

/**
 * Hint-only component routing. Must stay aligned with {@link UnifiedGeneratorRouter#generateResult}
 * early-exit branches (stair / skeleton / assembly / pipeline).
 */
public final class ComponentRouteResolver {
    private ComponentRouteResolver() {}

    public static ComponentRouteDecision resolve(SemanticComponent semantic, ServerWorld world) {
        if (semantic == null || semantic.source() == null) {
            return ComponentRouteDecision.of(ComponentExecutorId.NONE, "null_semantic");
        }
        Component c = semantic.source();
        if (ComponentRouteHints.acceptsStair(c)) {
            return ComponentRouteDecision.of(ComponentExecutorId.STAIR_COMPONENT, "straight_stair");
        }
        if (ComponentRouteHints.canRunSkeleton(semantic, world)) {
            return ComponentRouteDecision.of(ComponentExecutorId.SKELETON, "embedded_skeleton_resolvable");
        }
        if (ComponentRouteHints.canRunAssembly(semantic, world)) {
            return ComponentRouteDecision.of(ComponentExecutorId.ASSEMBLY, "assembly_payload");
        }
        ComponentPipelinePlan pipeline = ComponentPipelineRouteResolver.resolve(semantic, world);
        List<String> forbidden = new ArrayList<>();
        forbidden.add("structure_adaptor_without_explicit_hint");
        return new ComponentRouteDecision(
                ComponentExecutorId.COMPONENT_PIPELINE,
                summarizePipeline(pipeline),
                forbidden,
                pipeline);
    }

    static String summarizePipeline(ComponentPipelinePlan pipeline) {
        if (pipeline == null || pipeline.steps() == null || pipeline.steps().isEmpty()) {
            return "component_pipeline";
        }
        StringBuilder reason = new StringBuilder("component_pipeline");
        for (ComponentPipelineStep step : pipeline.steps()) {
            if (step.eligible()) {
                reason.append(':').append(step.id().name().toLowerCase());
            }
        }
        return reason.toString();
    }
}
