package com.formacraft.client.tool;

import com.formacraft.common.component.ComponentCategory;
import com.formacraft.common.component.health.HealthCheckResult;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ComponentDraftHealthP2Test {
    @Test
    void evenSymmetryAllowsEitherMiddleCell() {
        // w=4 → geometric center 2.0; cells ax=1 and ax=2 are both 0.5 away
        assertFalse(ComponentDraftHealthChecker.isOffEvenSymmetryCenter(1, 4));
        assertFalse(ComponentDraftHealthChecker.isOffEvenSymmetryCenter(2, 4));
        assertTrue(ComponentDraftHealthChecker.isOffEvenSymmetryCenter(0, 4));
        assertTrue(ComponentDraftHealthChecker.isOffEvenSymmetryCenter(3, 4));
    }

    @Test
    void oddSymmetryNeverFlags() {
        assertFalse(ComponentDraftHealthChecker.isOffEvenSymmetryCenter(2, 5));
        assertFalse(ComponentDraftHealthChecker.isOffEvenSymmetryCenter(0, 5));
    }

    @Test
    void evenWidthCenterAnchorDoesNotWarnH25() {
        ComponentCaptureDraft draft = new ComponentCaptureDraft();
        draft.selection.aabbMin = new BlockPos(0, 64, 0);
        draft.selection.aabbMax = new BlockPos(3, 66, 3); // 4x3x4
        draft.anchor.worldPos = new BlockPos(1, 64, 1); // one of two center cells on X/Z
        ComponentToolState state = new ComponentToolState();
        state.category = ComponentCategory.DOOR;

        HealthCheckResult result = ComponentDraftHealthChecker.check(draft, state, null, null);
        assertTrue(result.getItems().stream().noneMatch(i -> "H2-5".equals(i.ruleId)),
                "even center cell must not warn H2-5");
    }

    @Test
    void missingAnchorHintSaysLeftClick() {
        ComponentCaptureDraft draft = new ComponentCaptureDraft();
        draft.selection.aabbMin = new BlockPos(0, 64, 0);
        draft.selection.aabbMax = new BlockPos(2, 66, 2);
        ComponentToolState state = new ComponentToolState();
        state.category = ComponentCategory.WINDOW;

        HealthCheckResult result = ComponentDraftHealthChecker.check(draft, state, null, null);
        HealthCheckResult.CheckItem h21 = result.getItems().stream()
                .filter(i -> "H2-1".equals(i.ruleId))
                .findFirst()
                .orElseThrow();
        assertTrue(h21.description != null && h21.description.contains("左键"), h21.description);
        assertFalse(h21.description.contains("右键"));
    }

    @Test
    void autoFixSnapsAnchorIntoExplicitSelection() {
        ComponentCaptureDraft draft = new ComponentCaptureDraft();
        // L-shape: AABB center at bottom may be empty
        draft.selection.explicit = true;
        draft.selection.blocks = new HashSet<>(Set.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(0, 64, 1),
                new BlockPos(0, 64, 2)
        ));
        draft.selection.updateAabbFromBlocks();
        // AABB is 0..2 x 0..2 → center (1,64,1) is NOT in the L
        assertFalse(draft.selection.blocks.contains(new BlockPos(1, 64, 1)));

        ComponentToolState state = new ComponentToolState();
        ComponentDraftAutoFix.FixReport report = ComponentDraftAutoFix.apply(draft, state, null, null);

        assertNotNull(draft.anchor.worldPos);
        assertTrue(draft.selection.blocks.contains(draft.anchor.worldPos),
                "autofix anchor must land inside explicit selection: " + draft.anchor.worldPos);
        assertFalse(report.isEmpty());
    }

    @Test
    void nearestBlockPicksClosest() {
        BlockPos target = new BlockPos(5, 10, 5);
        BlockPos a = new BlockPos(0, 10, 0);
        BlockPos b = new BlockPos(4, 10, 5);
        BlockPos c = new BlockPos(8, 10, 8);
        assertEquals(b, ComponentDraftAutoFix.nearestBlock(target, Set.of(a, b, c)));
    }
}
