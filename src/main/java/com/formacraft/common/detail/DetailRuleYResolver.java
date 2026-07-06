package com.formacraft.common.detail;

import com.formacraft.common.generation.component.util.ComponentFloorCorniceDecorator;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.BitSet;

/**
 * Resolves {@link DetailRuleYAnchor} to relative Y indices within a building volume.
 */
public final class DetailRuleYResolver {

    private DetailRuleYResolver() {}

    public record BuildingYContext(
            int minY,
            int height,
            int floorHeight,
            BitSet floorBoundaryYs,
            BitSet baseTopYs,
            BitSet roofEaveYs
    ) {
        public static BuildingYContext fromBounds(LlmPlan plan, int minY, int maxY) {
            int height = maxY - minY + 1;
            int floorHeight = ComponentFloorCorniceDecorator.resolveFloorHeight(plan, height);
            BitSet floorBoundary = ComponentFloorCorniceDecorator.computeFloorBoundaryYs(height, floorHeight);
            BitSet baseTop = new BitSet();
            if (height > 2) {
                baseTop.set(1);
            }
            BitSet roofEave = new BitSet();
            if (height > 1) {
                roofEave.set(height - 1);
            }
            return new BuildingYContext(minY, height, floorHeight, floorBoundary, baseTop, roofEave);
        }
    }

    public static boolean matchesY(DetailRule.DetailRuleWhen when, BuildingYContext ctx, int relY) {
        if (when == null || ctx == null || relY < 0 || relY >= ctx.height()) {
            return false;
        }
        DetailRuleYAnchor anchor = when.yAnchor() != null ? when.yAnchor() : DetailRuleYAnchor.ABSOLUTE;
        return switch (anchor) {
            case FLOOR_BOUNDARY -> ctx.floorBoundaryYs().get(relY);
            case BASE_TOP -> ctx.baseTopYs().get(relY);
            case ROOF_EAVE -> ctx.roofEaveYs().get(relY);
            case ABSOLUTE -> relY == when.yOffset();
        };
    }
}
