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

        // Only the last operation at a position can contribute to the final decoration.
        var lastOperation = new java.util.HashMap<net.minecraft.util.math.BlockPos, Integer>();
        for (int i = 0; i < patches.size(); i++) {
            BlockPatch patch = patches.get(i);
            if (patch != null) lastOperation.put(position(patch), i);
        }
        List<Region> regions = new ArrayList<>();
        for (var volume : context.buildingVolumes()) {
            var bounds = volume.bounds();
            regions.add(new Region(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX() - 1,
                bounds.maxY() - 1, bounds.maxZ() - 1,
                DetailRuleYResolver.BuildingYContext.fromBounds(bounds.minY(), bounds.maxY() - 1, volume.floorHeight())));
        }
        if (regions.isEmpty()) {
            // Legacy callers have no mass/slot metadata. Infer only from the effective final solids.
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (int index : lastOperation.values()) {
                BlockPatch patch = patches.get(index);
                if (!isSolid(patch)) continue;
                minX = Math.min(minX, patch.dx()); minY = Math.min(minY, patch.dy()); minZ = Math.min(minZ, patch.dz());
                maxX = Math.max(maxX, patch.dx()); maxY = Math.max(maxY, patch.dy()); maxZ = Math.max(maxZ, patch.dz());
            }
            if (minX == Integer.MAX_VALUE) return patches;
            regions.add(new Region(minX, minY, minZ, maxX, maxY, maxZ,
                DetailRuleYResolver.BuildingYContext.fromBounds(plan, minY, maxY)));
        }
        String styleProfile = plan.styleProfile() != null ? plan.styleProfile() : "MEDIEVAL_CLASSIC";
        Palette palette = PaletteLibrary.forStyle(styleProfile);
        List<BlockPatch> out = new ArrayList<>(patches.size());
        int replaced = 0;
        for (int i = 0; i < patches.size(); i++) {
            BlockPatch patch = patches.get(i);
            if (patch == null) continue;
            Region region = null;
            if (isSolid(patch) && lastOperation.get(position(patch)) == i) {
                for (Region candidate : regions) {
                    if (!candidate.contains(patch)) continue;
                    // Overlapping masses have ambiguous ownership: do not guess a facade/floor basis.
                    if (region != null) { region = null; break; }
                    region = candidate;
                }
            }
            DetailRule matched = region != null && replaced < MAX_REPLACEMENTS
                ? findMatchingRule(patch, rules, region.yContext(), region.minX(), region.maxX(),
                    region.minZ(), region.maxZ(), region.minY()) : null;
            if (matched != null) {
                String replacement = buildReplacement(matched, patch, palette,
                    region.minX(), region.maxX(), region.minZ(), region.maxZ());
                out.add(new BlockPatch(BlockPatch.REPLACE, patch.dx(), patch.dy(), patch.dz(), replacement));
                replaced++;
            } else out.add(patch);
        }
        if (replaced > 0) FormacraftMod.LOGGER.debug("DetailRulePostProcessor: applied {} detail rule replacements", replaced);
        return out;
    }

    private record Region(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                          DetailRuleYResolver.BuildingYContext yContext) {
        boolean contains(BlockPatch patch) {
            return patch.dx() >= minX && patch.dx() <= maxX && patch.dy() >= minY && patch.dy() <= maxY
                && patch.dz() >= minZ && patch.dz() <= maxZ;
        }
    }
    private static net.minecraft.util.math.BlockPos position(BlockPatch patch) {
        return new net.minecraft.util.math.BlockPos(patch.dx(), patch.dy(), patch.dz());
    }
    private static boolean isSolid(BlockPatch patch) {
        if (patch == null || !(BlockPatch.PLACE.equals(patch.action()) || BlockPatch.REPLACE.equals(patch.action()))) return false;
        String block = patch.targetBlock();
        if (block == null || block.isBlank()) return false;
        int bracket = block.indexOf('[');
        String base = (bracket < 0 ? block : block.substring(0, bracket)).trim();
        return !base.equals("minecraft:air") && !base.equals("minecraft:cave_air") && !base.equals("minecraft:void_air");
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
