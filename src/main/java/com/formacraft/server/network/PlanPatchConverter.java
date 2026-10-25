package com.formacraft.server.network;

import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.BlockPatchTargetResolver;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Converts plan-local patches to world placements with graded invalid handling. */
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
    private static final String SOFT_FALLBACK = "minecraft:stone";

    public static Result convert(List<BlockPatch> patches, BlockPos origin) {
        List<PlannedBlock> blocks = new ArrayList<>();
        int invalid = 0;
        int criticalInvalid = 0;
        int softRecovered = 0;
        List<Diagnostic> samples = new ArrayList<>();
        if (patches == null || origin == null) {
            return new Result(blocks, 0, 0, 0, samples);
        }
        for (BlockPatch patch : patches) {
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
                blocks.add(new PlannedBlock(
                        origin.add(patch.dx(), patch.dy(), patch.dz()),
                        Blocks.STONE.getDefaultState()));
                addSample(samples, patch.dx(), patch.dy(), patch.dz(), patch.action(), patch.targetBlock(), false);
            } else {
                criticalInvalid++;
                addSample(samples, patch.dx(), patch.dy(), patch.dz(), patch.action(), patch.targetBlock(), true);
            }
        }
        return new Result(blocks, invalid, criticalInvalid, softRecovered, List.copyOf(samples));
    }

    /** place/replace 解析失败默认关键；remove 或装饰前缀可 soft。 */
    static boolean isCriticalInvalid(BlockPatch patch) {
        if (patch == null) return true;
        String action = patch.action() == null ? "" : patch.action().trim().toLowerCase(Locale.ROOT);
        if (BlockPatch.REMOVE.equals(action) || "air".equals(action)) {
            return false;
        }
        String target = patch.targetBlock() == null ? "" : patch.targetBlock().toLowerCase(Locale.ROOT);
        if (looksDecorative(target)) {
            return false;
        }
        // 结构相关关键词或未知 place/replace → critical
        return true;
    }

    static boolean canSoftRecover(BlockPatch patch) {
        if (patch == null) return false;
        String action = patch.action() == null ? "" : patch.action().trim().toLowerCase(Locale.ROOT);
        if (BlockPatch.REMOVE.equals(action)) return false;
        String target = patch.targetBlock() == null ? "" : patch.targetBlock().toLowerCase(Locale.ROOT);
        return looksDecorative(target);
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
                || target.contains("sign");
    }

    private static void addSample(List<Diagnostic> samples, int dx, int dy, int dz,
                                  String action, String target, boolean critical) {
        if (samples.size() >= MAX_SAMPLES) return;
        samples.add(new Diagnostic(dx, dy, dz, action, target, critical));
    }

    public static String softFallbackBlockId() {
        return SOFT_FALLBACK;
    }
}
