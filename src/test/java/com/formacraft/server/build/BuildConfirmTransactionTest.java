package com.formacraft.server.build;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.server.preview.PreviewStorage;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-10: enqueue returns transactionId; mismatch cancels only that id;
 * generation CAS + restore refusal are covered at the decision helpers.
 */
class BuildConfirmTransactionTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    private static GeneratedStructure hut(BlockPos origin) {
        return new GeneratedStructure(
                UUID.randomUUID(),
                origin,
                "hut",
                List.of(new PlannedBlock(origin, Blocks.STONE.getDefaultState()))
        );
    }

    @Test
    void mismatchCancelsOnlyBoundTransactionIdNotSibling() {
        UUID txA = UUID.randomUUID();
        UUID txB = UUID.randomUUID();
        Set<UUID> live = new LinkedHashSet<>();
        live.add(txA);
        live.add(txB);

        AtomicReference<UUID> enqueuedTx = new AtomicReference<>();
        BuildConfirmService.Enqueuer enqueuer = (world, structure, fbd) -> {
            enqueuedTx.set(txB);
            return BuildExecutionService.EnqueueResult.ok(txB);
        };
        BuildConfirmService.TaskCanceler canceler = id -> {
            assertEquals(txB, id, "must cancel only the confirm-bound transaction");
            live.remove(id);
            return true;
        };

        // Simulate post-enqueue CAS/consume failure path without a live player.
        var snapshot = new PreviewStorage.PreviewSnapshot(
                hut(new BlockPos(0, 64, 0)),
                "digest-b",
                new BlockPos(0, 64, 0),
                2L,
                null,
                null
        );
        var enqueue = enqueuer.enqueue(null, snapshot.structure(), null);
        assertTrue(enqueue.accepted());
        assertEquals(txB, enqueue.transactionId());

        var consume = BuildConfirmService.evaluateConsume(null, snapshot);
        assertTrue(consume.shouldCancelEnqueuedTask());
        assertTrue(canceler.cancel(enqueue.transactionId()));

        assertTrue(live.contains(txA), "building A must keep running");
        assertFalse(live.contains(txB), "building B must be cancelled");
        assertNotNull(enqueuedTx.get());
    }

    @Test
    void enqueueResultRejectedHasNoTransactionId() {
        var rejected = BuildExecutionService.EnqueueResult.rejected("queue_full");
        assertFalse(rejected.accepted());
        assertNull(rejected.transactionId());
        assertEquals("queue_full", rejected.reason());
    }

    @Test
    void evaluateConsumeRequiresDigestAndOriginNotJustNonNull() {
        var structure = hut(new BlockPos(4, 70, 4));
        String digest = PreviewStorage.digestStructure(structure);
        var snapshot = new PreviewStorage.PreviewSnapshot(
                structure, digest, new BlockPos(4, 70, 4), 9L, null, null);
        assertTrue(BuildConfirmService.evaluateConsume(structure, snapshot).matched());

        var twinLayout = hut(new BlockPos(4, 70, 4));
        // Same blocks/origin ⇒ same digest — generation is enforced by consumeIfGenerationMatches, not here.
        assertEquals(digest, PreviewStorage.digestStructure(twinLayout));
        assertTrue(BuildConfirmService.evaluateConsume(twinLayout, snapshot).matched());

        var moved = hut(new BlockPos(8, 70, 4));
        assertFalse(BuildConfirmService.evaluateConsume(moved, snapshot).matched());
    }

    @Test
    void snapshotMetaMatchRequiresGeneration() {
        var structure = hut(new BlockPos(1, 64, 1));
        String digest = PreviewStorage.digestStructure(structure);
        var snapshot = new PreviewStorage.PreviewSnapshot(
                structure, digest, new BlockPos(1, 64, 1), 5L, null, null);
        var metaSame = new PreviewStorage.PreviewMeta(
                null, new BlockPos(1, 64, 1), digest, System.currentTimeMillis(), 5L);
        var metaOtherGen = new PreviewStorage.PreviewMeta(
                null, new BlockPos(1, 64, 1), digest, System.currentTimeMillis(), 6L);
        assertTrue(snapshot.matchesMeta(metaSame));
        assertFalse(snapshot.matchesMeta(metaOtherGen),
                "identical layout at a new generation must not match the old snapshot");
    }
}
