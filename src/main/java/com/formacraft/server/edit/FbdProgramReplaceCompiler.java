package com.formacraft.server.edit;

import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.component.transform.BlockStateStringUtil;
import com.formacraft.common.dsl.FbdCompiler;
import com.formacraft.common.dsl.FbdLimits;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.server.assembly.MetaAssemblyEngine;
import com.formacraft.server.build.StructureSupportAnalysis;
import com.formacraft.server.dsl.FbdAssemblyAdapter;
import com.formacraft.server.dsl.FbdExecutionBudget;
import com.formacraft.server.dsl.FbdPlanLowering;
import com.formacraft.server.dsl.FbdSourcePlan;
import com.formacraft.server.network.PlanPatchConverter;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Diff old vs new FBD geometry into BlockPatches relative to building origin. */
public final class FbdProgramReplaceCompiler {
    public record CompileResult(
            List<BlockPatch> patches,
            Set<BlockPos> allowedCells,
            Map<BlockPos, String> targetWorldStates,
            List<PlannedBlock> newBlocks,
            String sanitizedNewPlanJson,
            List<String> refusals
    ) {
        public boolean ok() {
            return refusals.isEmpty() && patches != null && !patches.isEmpty()
                    && sanitizedNewPlanJson != null;
        }
    }

    private FbdProgramReplaceCompiler() {}

    public static CompileResult compile(
            BlockPos buildingOrigin,
            String storedPlanJson,
            LlmPlan newPlan,
            Map<BlockPos, String> liveWorldStates
    ) {
        Map<BlockPos, String> live = liveWorldStates == null ? Map.of() : liveWorldStates;
        return compile(buildingOrigin, storedPlanJson, newPlan, live::get);
    }

    public static CompileResult compile(
            BlockPos buildingOrigin,
            String storedPlanJson,
            LlmPlan newPlan,
            java.util.function.Function<BlockPos, String> liveLookup
    ) {
        List<String> refusals = new ArrayList<>();
        if (buildingOrigin == null) {
            return fail(List.of("building_origin_missing"));
        }
        LlmPlan oldPlan = FbdSourcePlan.parse(storedPlanJson);
        if (oldPlan == null || !FbdSourcePlan.hasProgram(oldPlan)) {
            return fail(List.of("stored_fbd_plan_missing_or_invalid"));
        }
        if (newPlan == null || !FbdSourcePlan.hasProgram(newPlan)) {
            return fail(List.of("new_fbd_plan_missing"));
        }
        String sanitized = FbdSourcePlan.sanitizeToJson(newPlan);
        if (sanitized == null) {
            return fail(List.of("new_fbd_plan_sanitize_failed"));
        }
        LlmPlan sanitizedPlan = FbdSourcePlan.parse(sanitized);
        if (sanitizedPlan == null) {
            return fail(List.of("new_fbd_plan_reparse_failed"));
        }

        List<PlannedBlock> oldBlocks = materialize(oldPlan, buildingOrigin, refusals, "old");
        if (!refusals.isEmpty()) return fail(refusals);
        List<PlannedBlock> newBlocks = materialize(sanitizedPlan, buildingOrigin, refusals, "new");
        if (!refusals.isEmpty()) return fail(refusals);

        Map<BlockPos, BlockState> oldFinal = StructureSupportAnalysis.finalStates(oldBlocks);
        Map<BlockPos, BlockState> newFinal = StructureSupportAnalysis.finalStates(newBlocks);

        Set<BlockPos> universe = new LinkedHashSet<>();
        universe.addAll(oldFinal.keySet());
        universe.addAll(newFinal.keySet());

        List<BlockPatch> patches = new ArrayList<>();
        Map<BlockPos, String> targets = new LinkedHashMap<>();
        Set<BlockPos> allowed = new LinkedHashSet<>();
        Map<BlockPos, String> expectedLive = new LinkedHashMap<>();
        java.util.function.Function<BlockPos, String> lookup =
                liveLookup == null ? p -> null : liveLookup;

        for (BlockPos world : universe) {
            if (world == null) continue;
            BlockState before = oldFinal.getOrDefault(world, Blocks.AIR.getDefaultState());
            BlockState after = newFinal.getOrDefault(world, Blocks.AIR.getDefaultState());
            if (Objects.equals(before, after)
                    || (before.isAir() && after.isAir())) {
                continue;
            }
            String liveState = lookup.apply(world);
            if (liveState == null || liveState.isBlank()) {
                refusals.add("live_state_missing@" + world.toShortString());
                continue;
            }
            BlockPos imm = world.toImmutable();
            allowed.add(imm);
            expectedLive.put(imm, liveState);
            int dx = world.getX() - buildingOrigin.getX();
            int dy = world.getY() - buildingOrigin.getY();
            int dz = world.getZ() - buildingOrigin.getZ();
            if (after.isAir()) {
                patches.add(new BlockPatch(BlockPatch.REMOVE, dx, dy, dz, "minecraft:air"));
                targets.put(imm, "minecraft:air");
            } else {
                String target = BlockStateStringUtil.fromState(after);
                patches.add(new BlockPatch(BlockPatch.PLACE, dx, dy, dz, target));
                targets.put(imm, target);
            }
        }
        if (!refusals.isEmpty()) return fail(refusals);
        if (patches.isEmpty()) {
            return fail(List.of("no_effective_fbd_change"));
        }
        return new CompileResult(List.copyOf(patches), Set.copyOf(allowed), Map.copyOf(targets),
                List.copyOf(newBlocks), sanitized, List.of());
    }

    private static List<PlannedBlock> materialize(LlmPlan plan, BlockPos origin, List<String> refusals, String label) {
        var previous = AssemblyCompileDiagnostics.get();
        try {
            AssemblyCompileDiagnostics.set(null);
            var lowered = FbdPlanLowering.lower(plan.components());
            if (AssemblyCompileDiagnostics.hasGap()) {
                refusals.add(label + "_fbd_compile_failed:" + AssemblyCompileDiagnostics.get().code());
                return List.of();
            }
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
                        FbdAssemblyAdapter.finalPatches(geometry), origin).blocks());
            }
            if (blocks.isEmpty()) {
                refusals.add(label + "_fbd_empty_geometry");
            }
            return blocks;
        } catch (Exception e) {
            refusals.add(label + "_fbd_materialize_error:" + e.getClass().getSimpleName());
            return List.of();
        } finally {
            AssemblyCompileDiagnostics.set(previous);
        }
    }

    private static CompileResult fail(List<String> refusals) {
        return new CompileResult(List.of(), Set.of(), Map.of(), List.of(), null, List.copyOf(refusals));
    }
}
