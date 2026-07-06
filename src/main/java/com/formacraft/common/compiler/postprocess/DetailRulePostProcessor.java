package com.formacraft.common.compiler.postprocess;

import com.formacraft.FormacraftMod;
import com.formacraft.common.detail.DetailRule;
import com.formacraft.common.detail.DetailRuleBlockResolver;
import com.formacraft.common.detail.DetailRuleFacing;
import com.formacraft.common.detail.DetailRuleParser;
import com.formacraft.common.detail.DetailRuleRegion;
import com.formacraft.common.detail.DetailRuleYResolver;
import com.formacraft.common.generation.component.util.ComponentFloorCorniceDecorator;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.palette.component.Palette;
import com.formacraft.common.palette.component.PaletteLibrary;
import com.formacraft.common.semantic.SemanticPart;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies declarative {@code proportion_hints.detail_rules} plus built-in presets
 * (floor cornice inverted stairs, base plinth slab band).
 */
public class DetailRulePostProcessor implements PostProcessor {

    private static final int MAX_REPLACEMENTS = 2500;

    @Override
    public List<BlockPatch> process(List<BlockPatch> patches, PostProcessContext context) {
        if (patches == null || patches.isEmpty() || context == null || context.plan() == null) {
            return patches;
        }
        LlmPlan plan = context.plan();
        List<DetailRule> rules = DetailRuleParser.resolve(plan);
        if (rules.isEmpty()) {
            return patches;
        }

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (BlockPatch patch : patches) {
            if (patch == null || BlockPatch.REMOVE.equals(patch.action())) {
                continue;
            }
            String target = patch.targetBlock();
            if (target == null || target.isBlank() || "minecraft:air".equals(target)) {
                continue;
            }
            minX = Math.min(minX, patch.dx());
            minY = Math.min(minY, patch.dy());
            minZ = Math.min(minZ, patch.dz());
            maxX = Math.max(maxX, patch.dx());
            maxY = Math.max(maxY, patch.dy());
            maxZ = Math.max(maxZ, patch.dz());
        }

        if (minX == Integer.MAX_VALUE) {
            return patches;
        }

        DetailRuleYResolver.BuildingYContext yCtx =
                DetailRuleYResolver.BuildingYContext.fromBounds(plan, minY, maxY);

        String styleProfile = plan.styleProfile() != null ? plan.styleProfile() : "MEDIEVAL_CLASSIC";
        Palette palette = PaletteLibrary.forStyle(styleProfile);

        List<BlockPatch> out = new ArrayList<>(patches.size());
        int replaced = 0;

        for (BlockPatch patch : patches) {
            if (patch == null) {
                continue;
            }
            if (replaced < MAX_REPLACEMENTS && shouldReplacePatch(
                    patch, rules, yCtx, palette, minX, maxX, minZ, maxZ, minY)) {
                DetailRule matched = findMatchingRule(patch, rules, yCtx, minX, maxX, minZ, maxZ, minY);
                if (matched != null) {
                    String replacement = buildReplacement(
                            matched, patch, palette, minX, maxX, minZ, maxZ);
                    out.add(new BlockPatch(BlockPatch.REPLACE, patch.dx(), patch.dy(), patch.dz(), replacement));
                    replaced++;
                    continue;
                }
            }
            out.add(patch);
        }

        if (replaced > 0) {
            FormacraftMod.LOGGER.debug("DetailRulePostProcessor: applied {} detail rule replacements", replaced);
        }
        return out;
    }

    private static boolean shouldReplacePatch(
            BlockPatch patch,
            List<DetailRule> rules,
            DetailRuleYResolver.BuildingYContext yCtx,
            Palette palette,
            int minX, int maxX, int minZ, int maxZ,
            int minY
    ) {
        return findMatchingRule(patch, rules, yCtx, minX, maxX, minZ, maxZ, minY) != null;
    }

    private static DetailRule findMatchingRule(
            BlockPatch patch,
            List<DetailRule> rules,
            DetailRuleYResolver.BuildingYContext yCtx,
            int minX, int maxX, int minZ, int maxZ,
            int minY
    ) {
        if (BlockPatch.REMOVE.equals(patch.action())) {
            return null;
        }
        int relY = patch.dy() - minY;
        for (DetailRule rule : rules) {
            if (rule == null || !rule.isValid()) {
                continue;
            }
            DetailRule.DetailRuleWhen when = rule.when();
            if (!matchesRegion(when.region(), patch.dx(), patch.dz(), minX, maxX, minZ, maxZ)) {
                continue;
            }
            if (!DetailRuleYResolver.matchesY(when, yCtx, relY)) {
                continue;
            }
            if (!DetailRuleBlockResolver.matchesBlockFilter(patch.targetBlock(), when.blockFilter())) {
                continue;
            }
            return rule;
        }
        return null;
    }

    private static boolean matchesRegion(
            DetailRuleRegion region,
            int x, int z,
            int minX, int maxX, int minZ, int maxZ
    ) {
        if (region == DetailRuleRegion.ALL) {
            return true;
        }
        return ComponentFloorCorniceDecorator.isPerimeter(x, z, minX, maxX, minZ, maxZ);
    }

    private static String buildReplacement(
            DetailRule rule,
            BlockPatch patch,
            Palette palette,
            int minX, int maxX, int minZ, int maxZ
    ) {
        DetailRule.DetailRuleAction action = rule.action();
        SemanticPart part = action.semanticPart() != null ? action.semanticPart() : SemanticPart.WALL_ACCENT;
        String paletteBlock = palette.pick(part);
        if (paletteBlock == null || paletteBlock.isBlank()) {
            paletteBlock = palette.pick(SemanticPart.DECOR);
        }
        if (paletteBlock == null || paletteBlock.isBlank()) {
            paletteBlock = patch.targetBlock();
        }

        Direction outward = Direction.SOUTH;
        if (action.facing() == DetailRuleFacing.OUTWARD) {
            outward = ComponentFloorCorniceDecorator.outwardFacing(
                    patch.dx(), patch.dz(), minX, maxX, minZ, maxZ);
        }
        return DetailRuleBlockResolver.resolveBlock(action, paletteBlock, outward);
    }
}
