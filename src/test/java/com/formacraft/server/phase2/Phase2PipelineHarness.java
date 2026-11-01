package com.formacraft.server.phase2;

import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.BuildExecutionStatus;
import com.formacraft.common.patch.ConfirmedPlanIdentity;
import com.formacraft.common.patch.WorldPatchTransaction;
import com.formacraft.common.component.transform.BlockStateStringUtil;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.server.compiler.ComponentPlanCompiler;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Offline Preview → Confirm → Apply skeleton for Phase 2 samples.
 * <p>
 * Confirm is modeled as freezing compiled patches; Apply writes them through
 * {@link WorldPatchTransaction} into {@link Phase2UnboundedAccess}.
 */
public final class Phase2PipelineHarness {
    public static final BlockPos ORIGIN = new BlockPos(0, 64, 0);

    private Phase2PipelineHarness() {}

    public record RunResult(
            Phase2SampleManifest manifest,
            List<BlockPatch> patches,
            String previewDigest,
            String appliedDigest,
            Map<BlockPos, String> previewCells,
            Map<BlockPos, String> appliedCells,
            Phase2StructuralChecks.Metrics metrics,
            List<String> diagnostics
    ) {
        public boolean previewApplyAgree() {
            return Objects.equals(previewDigest, appliedDigest);
        }
    }

    public static RunResult run(Phase2SampleManifest manifest) {
        List<String> diagnostics = new ArrayList<>();
        AssemblyCompileDiagnostics.clear();
        LlmPlan plan = Phase2SampleCatalog.loadPlan(manifest);
        if (plan == null) {
            diagnostics.add("plan_parse_null");
            return empty(manifest, diagnostics);
        }

        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, ORIGIN, null, null, false);
        if (patches == null || patches.isEmpty()) {
            var gap = AssemblyCompileDiagnostics.get();
            diagnostics.add("compile_empty"
                    + (gap != null ? (" code=" + gap.code() + " msg=" + gap.message()) : ""));
            return empty(manifest, diagnostics);
        }

        Map<BlockPos, String> previewCells = Phase2StructuralChecks.collapseFinalPlaceCells(patches);
        String previewDigest = ConfirmedPlanIdentity.worldStateFingerprint(previewCells);

        Phase2UnboundedAccess world = new Phase2UnboundedAccess();
        WorldPatchTransaction.Result applied = WorldPatchTransaction.execute(world, ORIGIN, patches);
        if (applied.status() != BuildExecutionStatus.SUCCESS) {
            diagnostics.add("apply_status=" + applied.status() + " " + applied.summaryZh());
        }

        Map<BlockPos, String> appliedCells = new LinkedHashMap<>();
        for (BlockPos rel : previewCells.keySet()) {
            BlockPos worldPos = ORIGIN.add(rel).toImmutable();
            String live = BlockStateStringUtil.fromState(world.getState(worldPos));
            if (live != null && !live.isBlank() && !"minecraft:air".equalsIgnoreCase(stripState(live))) {
                appliedCells.put(rel, live);
            }
        }
        // Also include any extra solids written outside preview map (should be rare)
        for (var e : world.states.entrySet()) {
            BlockPos worldPos = e.getKey();
            BlockPos rel = worldPos.subtract(ORIGIN);
            if (appliedCells.containsKey(rel)) continue;
            String live = BlockStateStringUtil.fromState(e.getValue());
            if (live != null && !live.isBlank() && !"minecraft:air".equalsIgnoreCase(stripState(live))) {
                appliedCells.put(rel, live);
            }
        }

        String appliedDigest = ConfirmedPlanIdentity.worldStateFingerprint(normalizeIds(appliedCells));
        String previewNormDigest = ConfirmedPlanIdentity.worldStateFingerprint(normalizeIds(previewCells));

        // Prefer id-normalized comparison so BlockState string vs patch targetBlock align.
        if (!Objects.equals(previewNormDigest, appliedDigest)) {
            diagnostics.add("preview_apply_mismatch preview=" + previewNormDigest
                    + " applied=" + appliedDigest
                    + " previewCells=" + previewCells.size()
                    + " appliedCells=" + appliedCells.size());
            int diffs = 0;
            for (var e : previewCells.entrySet()) {
                String a = appliedCells.get(e.getKey());
                if (!sameBlock(e.getValue(), a)) {
                    diffs++;
                    if (diffs <= 5) {
                        diagnostics.add("diff@" + e.getKey().toShortString()
                                + " preview=" + e.getValue() + " applied=" + a);
                    }
                }
            }
        }

        var metrics = Phase2StructuralChecks.compute(patches, previewNormDigest);
        return new RunResult(
                manifest,
                List.copyOf(patches),
                previewNormDigest,
                appliedDigest,
                Map.copyOf(previewCells),
                Map.copyOf(appliedCells),
                metrics,
                List.copyOf(diagnostics)
        );
    }

    private static RunResult empty(Phase2SampleManifest manifest, List<String> diagnostics) {
        var metrics = new Phase2StructuralChecks.Metrics(0, 0, 0, 0, 0, 0, 0, 0, 0, "");
        return new RunResult(manifest, List.of(), "", "", Map.of(), Map.of(), metrics, List.copyOf(diagnostics));
    }

    private static Map<BlockPos, String> normalizeIds(Map<BlockPos, String> cells) {
        Map<BlockPos, String> out = new LinkedHashMap<>();
        for (var e : cells.entrySet()) {
            out.put(e.getKey(), stripState(e.getValue()));
        }
        return out;
    }

    private static String stripState(String s) {
        if (s == null) return "";
        int i = s.indexOf('[');
        return (i >= 0 ? s.substring(0, i) : s).trim().toLowerCase(Locale.ROOT);
    }

    private static boolean sameBlock(String preview, String applied) {
        return stripState(preview).equals(stripState(applied));
    }
}
