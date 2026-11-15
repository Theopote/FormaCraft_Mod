package com.formacraft.server.network.compat;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ACTIVE_COMPAT 回归：旧 BuildingSpec 编辑不得损坏玩家规格，且门禁覆盖预览全过程。
 */
class LegacyEditPreviewScopeTest {

    private static final String VALID_SPEC = """
            {
              "type": "house",
              "width": 8,
              "depth": 6,
              "height": 4
            }
            """;

    @Test
    void invalidSpecIsRejectedWithoutThrowing() {
        assertNull(LegacyEditPreviewScope.parseSpec(null));
        assertNull(LegacyEditPreviewScope.parseSpec(""));
        assertNull(LegacyEditPreviewScope.parseSpec("not json"));
        assertNull(LegacyEditPreviewScope.parseSpec("{ broken"));
    }

    @Test
    void validSpecParses() {
        assertNotNull(LegacyEditPreviewScope.parseSpec(VALID_SPEC));
    }

    @Test
    void failedPreviewDoesNotOverwritePreviousSpec() {
        UUID player = UUID.randomUUID();
        com.formacraft.server.state.PlayerSpecRepository.setBuildingSpec(player, "b1", "{\"type\":\"old\"}");

        boolean committed = LegacyEditPreviewScope.commitSpec(player, "b1", "{\"type\":\"new\"}", false);

        assertFalse(committed, "preview failure must not commit the edited spec");
        assertEquals("{\"type\":\"old\"}", com.formacraft.server.state.PlayerSpecRepository.getBuildingJson(player),
                "previous spec must survive a failed preview");
    }

    @Test
    void deliveredPreviewCommitsEditedSpec() {
        UUID player = UUID.randomUUID();
        com.formacraft.server.state.PlayerSpecRepository.setBuildingSpec(player, "b2", "{\"type\":\"old\"}");

        boolean committed = LegacyEditPreviewScope.commitSpec(player, "b2", "{\"type\":\"new\"}", true);

        assertTrue(committed);
        assertEquals("{\"type\":\"new\"}", com.formacraft.server.state.PlayerSpecRepository.getBuildingJson(player));
    }

    @Test
    void gateIsReleasedOnlyAfterPreviewScopeEnds() {
        AtomicInteger releases = new AtomicInteger();
        LegacyEditPreviewScope scope = new LegacyEditPreviewScope(releases::incrementAndGet);

        assertEquals(0, releases.get(), "gate must stay held while the preview is pending");
        assertFalse(scope.isReleased());
        assertFalse(scope.isPreviewDelivered());

        scope.markPreviewDelivered();
        assertTrue(scope.isPreviewDelivered());
        assertEquals(0, releases.get(), "marking delivery must not release the gate early");

        scope.release();
        assertTrue(scope.isReleased());
        assertEquals(1, releases.get());

        scope.release();
        assertEquals(1, releases.get(), "release must be idempotent");
    }
}
