package com.formacraft.server.dsl;

import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.dsl.FbdCompiler;
import com.formacraft.common.dsl.FbdLimits;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.server.assembly.MetaAssemblyEngine;
import com.formacraft.server.build.StructureSupportAnalysis;
import com.formacraft.server.build.quality.BuildQualityReport;
import com.formacraft.server.build.quality.GradedQualityChecker;
import com.formacraft.server.network.PlanPatchConverter;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * World-free or world-aware final quality for FBD geometry repair evaluation.
 * Regenerates complete program geometry; does not write the world or issue previews.
 */
public final class FbdGeometryQualityVerifier {
    private FbdGeometryQualityVerifier() {}

    public static BuildQualityReport verify(LlmPlan plan, BlockPos worldOrigin,
                                            Function<BlockPos, BlockState> existing) {
        if (plan == null || worldOrigin == null || existing == null) {
            return null;
        }
        var previous = AssemblyCompileDiagnostics.get();
        try {
            AssemblyCompileDiagnostics.set(null);
            var lowered = FbdPlanLowering.lower(plan.components());
            if (AssemblyCompileDiagnostics.hasGap()) return null;
            List<PlannedBlock> blocks = new ArrayList<>();
            for (var component : lowered) {
                if (component == null || component.params() == null) continue;
                if (!(component.params().get(FbdPlanLowering.COMPILED) instanceof FbdCompiler.Compilation compiled)) {
                    continue;
                }
                var source = (FbdPlanLowering.SourceContext) component.params().get(FbdPlanLowering.SOURCE);
                var geometry = new MetaAssemblyEngine().executeGeometry(
                        FbdAssemblyAdapter.spec(compiled, source),
                        new MetaAssemblyEngine.Context(
                                null, BlockPos.ORIGIN, Direction.SOUTH, null,
                                new FbdExecutionBudget(FbdLimits.DEFAULT)));
                blocks.addAll(PlanPatchConverter.convert(
                        FbdAssemblyAdapter.finalPatches(geometry), worldOrigin).blocks());
            }
            var report = new BuildQualityReport();
            report.stats().totalBlocks = (int) StructureSupportAnalysis.finalStates(blocks).values().stream()
                    .filter(s -> !s.isAir()).count();
            GradedQualityChecker.checkSupport(blocks, existing, report);
            report.phase("final");
            return report;
        } catch (Exception e) {
            return null;
        } finally {
            AssemblyCompileDiagnostics.set(previous);
        }
    }
}
