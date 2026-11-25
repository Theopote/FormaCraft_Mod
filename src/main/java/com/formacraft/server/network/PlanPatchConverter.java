package com.formacraft.server.network;

import com.formacraft.FormacraftMod;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.BlockPatchTargetResolver;
import com.formacraft.common.patch.ConfirmedPlanIdentity;
import com.formacraft.common.patch.ConnectedBlockStateResolver;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Converts plan-local patches to world placements with graded invalid handling.
 * <p>
 * Input is normalized to the <b>final per-cell set</b> ({@link ConfirmedPlanIdentity#toFinalPatches})
 * before conversion so preview / digest / apply share one last-write-wins identity.
 * <p>
 * Soft recovery never places bare stone for décor / lighting. Hang-links and fixtures
 * map to a hangable semantic substitute; other optional décor is skipped with a warning.
 */
public final class PlanPatchConverter {
    private PlanPatchConverter() {}

    public record Diagnostic(int dx, int dy, int dz, String action, String targetBlock, boolean critical) {}

    public record Result(
            List<PlannedBlock> blocks,
            int invalid,
            int criticalInvalid,
            int softRecovered,
            List<Diagnostic> samples
    ) {
        public Result(List<PlannedBlock> blocks, int invalid) {
            this(blocks, invalid, 0, 0, List.of());
        }

        public boolean hasCriticalFailure() {
            return criticalInvalid > 0;
        }
    }

    private static final int MAX_SAMPLES = 8;

    public static Result convert(List<BlockPatch> patches, BlockPos origin) {
        List<PlannedBlock> blocks = new ArrayList<>();
        int invalid = 0;
        int criticalInvalid = 0;
        int softRecovered = 0;
        List<Diagnostic> samples = new ArrayList<>();
        if (patches == null || origin == null) {
            return new Result(blocks, 0, 0, 0, samples);
        }
        // Collapse same-cell multi-writes before resolving — matches confirmed-plan identity.
        List<BlockPatch> finalPatches = ConfirmedPlanIdentity.toFinalPatches(patches);
        for (BlockPatch patch : finalPatches) {
            if (patch == null) {
                invalid++;
                criticalInvalid++;
                addSample(samples, 0, 0, 0, null, null, true);
                continue;
            }
            var state = BlockPatchTargetResolver.resolve(patch);
            if (state != null) {
                blocks.add(new PlannedBlock(origin.add(patch.dx(), patch.dy(), patch.dz()), state, patch.source()));
                continue;
            }
            invalid++;
            String action = patch.action() == null ? "" : patch.action().trim().toLowerCase(Locale.ROOT);
            if (BlockPatch.REMOVE.equals(action) || "air".equals(action)) {
                // remove 目标未知：跳过即可，不计 critical
                softRecovered++;
                addSample(samples, patch.dx(), patch.dy(), patch.dz(), patch.action(), patch.targetBlock(), false);
            } else if (canSoftRecover(patch)) {
                softRecovered++;
                BlockState substitute = softSubstitute(patch.targetBlock());
                if (substitute != null) {
                    blocks.add(new PlannedBlock(
                            origin.add(patch.dx(), patch.dy(), patch.dz()),
                            substitute,
                            patch.source()));
                    FormacraftMod.LOGGER.warn(
                            "PlanPatchConverter: soft-substituted unresolved décor {} -> {}",
                            patch.targetBlock(), softSubstituteId(substitute));
                } else {
                    FormacraftMod.LOGGER.warn(
                            "PlanPatchConverter: skipped unresolved optional décor {}",
                            patch.targetBlock());
                }
                addSample(samples, patch.dx(), patch.dy(), patch.dz(), patch.action(), patch.targetBlock(), false);
            } else {
                criticalInvalid++;
                addSample(samples, patch.dx(), patch.dy(), patch.dz(), patch.action(), patch.targetBlock(), true);
            }
        }
        return new Result(ConnectedBlockStateResolver.apply(blocks), invalid, criticalInvalid,
                softRecovered, List.copyOf(samples));
    }

    /** place/replace 解析失败默认关键；remove 或装饰前缀可 soft。 */
    static boolean isCriticalInvalid(BlockPatch patch) {
        if (patch == null) return true;
        String action = patch.action() == null ? "" : patch.action().trim().toLowerCase(Locale.ROOT);
        if (BlockPatch.REMOVE.equals(action) || "air".equals(action)) {
            return false;
        }
        String target = patch.targetBlock() == null ? "" : patch.targetBlock().toLowerCase(Locale.ROOT);
        return !looksDecorative(target);
    }

    static boolean canSoftRecover(BlockPatch patch) {
        if (patch == null) return false;
        String action = patch.action() == null ? "" : patch.action().trim().toLowerCase(Locale.ROOT);
        if (BlockPatch.REMOVE.equals(action)) return false;
        String target = patch.targetBlock() == null ? "" : patch.targetBlock().toLowerCase(Locale.ROOT);
        return looksDecorative(target);
    }

    /**
     * Semantic substitute for unresolved lighting / hang-link ids. Returns {@code null}
     * when the décor is optional and should be omitted (never stone).
     */
    static BlockState softSubstitute(String targetBlock) {
        if (targetBlock == null || targetBlock.isBlank()) return null;
        String t = targetBlock.toLowerCase(Locale.ROOT);
        if (t.contains("chain")) {
            return Blocks.IRON_BARS.getDefaultState();
        }
        if (t.contains("end_rod")) {
            return Blocks.SEA_LANTERN.getDefaultState();
        }
        if (t.contains("lantern")) {
            boolean soul = t.contains("soul");
            boolean hanging = t.contains("hanging=true") || t.contains("hanging");
            BlockState base = soul
                    ? Blocks.SOUL_LANTERN.getDefaultState()
                    : Blocks.LANTERN.getDefaultState();
            if (hanging && base.contains(net.minecraft.block.LanternBlock.HANGING)) {
                return base.with(net.minecraft.block.LanternBlock.HANGING, true);
            }
            return base;
        }
        if (t.contains("torch") && !t.contains("redstone")) {
            if (t.contains("soul")) return Blocks.SOUL_TORCH.getDefaultState();
            return Blocks.TORCH.getDefaultState();
        }
        // flower / carpet / candle / pot / banner / painting / sign → skip
        return null;
    }

    private static boolean looksDecorative(String target) {
        if (target == null || target.isBlank()) return false;
        return target.contains("flower")
                || target.contains("carpet")
                || target.contains("candle")
                || target.contains("lantern")
                || target.contains("pot")
                || target.contains("banner")
                || target.contains("painting")
                || target.contains("torch")
                || target.contains("sign")
                || target.contains("chain")
                || target.contains("end_rod");
    }

    private static String softSubstituteId(BlockState state) {
        if (state == null) return "null";
        return net.minecraft.registry.Registries.BLOCK.getId(state.getBlock()).toString();
    }

    private static void addSample(List<Diagnostic> samples, int dx, int dy, int dz,
                                  String action, String target, boolean critical) {
        if (samples.size() >= MAX_SAMPLES) return;
        samples.add(new Diagnostic(dx, dy, dz, action, target, critical));
    }

    /**
     * @deprecated Phase 01.5 — stone is no longer a soft décor fallback.
     *             Kept for callers that probe the historical constant; returns empty.
     */
    @Deprecated
    public static String softFallbackBlockId() {
        return "";
    }
}
