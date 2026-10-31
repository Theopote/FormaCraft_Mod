package com.formacraft.server.build;

import com.formacraft.server.state.PlayerSpecRepository;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Memory commit must not depend on the owner still being online.
 */
class BuildMemoryCommitTest {

    @Test
    void buildingSpecIsReadableByUuidWithoutOnlinePlayer() {
        UUID id = UUID.randomUUID();
        assertNull(PlayerSpecRepository.getBuildingJson(id));
        // Simulate a cached spec written while the player was online.
        PlayerSpecRepository.setBuildingSpec(id, "b1", "{\"type\":\"HOUSE\"}");
        assertEquals("{\"type\":\"HOUSE\"}", PlayerSpecRepository.getBuildingJson(id));
        assertEquals("b1", PlayerSpecRepository.getBuildingId(id));
        PlayerSpecRepository.clear(id);
        assertNull(PlayerSpecRepository.getBuildingJson(id));
        assertNull(PlayerSpecRepository.getBuildingJson((UUID) null));
    }

    @Test
    void pendingMemoryCommitsStayEmptyWithoutMemoryManager() {
        BuildExecutionService service = BuildExecutionService.getInstance();
        // No MemoryManager bound → commit cannot succeed; retry must be a no-op, not a crash.
        assertDoesNotThrow(() -> service.retryPendingMemoryCommits(null));
        assertEquals(0, service.pendingMemoryCommitCount());
    }
}
