package com.formacraft.server.memory;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildUndoMemoryTest {
    private final Identifier dimension = Identifier.of("minecraft:overworld");
    private ProjectMemory memory() {
        var memory = new ProjectMemory();
        memory.setBounds(new ProjectMemory.SpatialBounds(BlockPos.ORIGIN, new BlockPos(4, 8, 4), dimension));
        memory.getMetadata().put("roof_modified", true);
        return memory;
    }
    private MemoryManager manager(ProjectMemory memory, boolean fails) {
        return new MemoryManager(null) {
            @Override public ProjectMemory getMemory(String uuid) { return uuid.equals(memory.getUuid()) ? memory : null; }
            @Override protected void persistMemory(ProjectMemory updated) throws IOException {
                if (fails) throw new IOException("simulated disk failure");
            }
        };
    }
    @Test void progressPreservesOtherMetadataAndDoesNotDeleteOrShrinkBuilding() {
        var memory = memory(); var bounds = memory.getBounds(); var manager = manager(memory, false);
        var transaction = UUID.randomUUID();
        assertTrue(manager.recordBuildUndo(new BuildUndoMemoryUpdate(transaction, memory.getUuid(), dimension, 1, 2, false)));
        assertEquals("partial", memory.getMetadata().get("build_undo_state"));
        var update = new BuildUndoMemoryUpdate(transaction, memory.getUuid(), dimension, 2, 2, true);
        assertTrue(manager.recordBuildUndo(update)); assertTrue(manager.recordBuildUndo(update));
        assertEquals("restored", memory.getMetadata().get("build_undo_state"));
        assertEquals(2, memory.getMetadata().get("build_undo_restored_positions"));
        assertEquals(true, memory.getMetadata().get("roof_modified")); assertSame(bounds, memory.getBounds());
        assertSame(memory, manager.getMemory(memory.getUuid()));
    }
    @Test void diskFailureRestoresCachedMetadataAndModificationTime() {
        var memory = memory(); var previous = new HashMap<>(memory.getMetadata()); var modified = memory.getLastModified();
        assertFalse(manager(memory, true).recordBuildUndo(new BuildUndoMemoryUpdate(UUID.randomUUID(),
            memory.getUuid(), dimension, 2, 2, true)));
        assertEquals(previous, memory.getMetadata()); assertEquals(modified, memory.getLastModified());
    }
    @Test void wrongDimensionOrMissingUuidNeverUpdatesMemory() {
        var memory = memory(); var manager = manager(memory, false);
        assertFalse(manager.recordBuildUndo(new BuildUndoMemoryUpdate(UUID.randomUUID(), memory.getUuid(),
            Identifier.of("minecraft:the_nether"), 2, 2, true)));
        assertFalse(manager.recordBuildUndo(new BuildUndoMemoryUpdate(UUID.randomUUID(), UUID.randomUUID().toString(),
            dimension, 2, 2, true)));
        assertFalse(memory.getMetadata().containsKey("build_undo_state"));
    }
}
