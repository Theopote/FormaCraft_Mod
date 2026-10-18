package com.formacraft.server.generation.typology.builder;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PalaceTerrainSnapshotTest {

    @Test
    void contentHashIgnoresInsertionOrder() {
        List<PalaceTerrainSnapshot.Sample> samples = List.of(
                new PalaceTerrainSnapshot.Sample(2, 5, 4, false),
                new PalaceTerrainSnapshot.Sample(0, 1, 3, true),
                new PalaceTerrainSnapshot.Sample(1, 0, 2, false),
                new PalaceTerrainSnapshot.Sample(-1, 8, 4, false)
        );
        List<PalaceTerrainSnapshot.Sample> shuffled = new ArrayList<>(samples);
        Collections.reverse(shuffled);

        PalaceTerrainSnapshot a = PalaceTerrainSnapshot.fromSamples(samples, false);
        PalaceTerrainSnapshot b = PalaceTerrainSnapshot.fromSamples(shuffled, false);
        assertEquals(a.contentHash(), b.contentHash());
        assertEquals(a.contentHash(), PalaceTerrainSnapshot.hashSamplesForTest(shuffled, false));
    }

    @Test
    void schematicFlatHashStableAcrossConstructionPaths() {
        PalaceTerrainSnapshot a = PalaceTerrainSnapshot.schematicFlat(12, 30, 4, 8);
        List<PalaceTerrainSnapshot.Sample> cells = new ArrayList<>(a.cells().values());
        Collections.shuffle(cells);
        PalaceTerrainSnapshot b = PalaceTerrainSnapshot.fromSamples(cells, true);
        assertEquals(a.contentHash(), b.contentHash());
        assertTrue(a.schematicFlat());
        assertTrue(b.schematicFlat());
    }

    @Test
    void contentChangeAltersHash() {
        PalaceTerrainSnapshot base = PalaceTerrainSnapshot.fromSamples(List.of(
                new PalaceTerrainSnapshot.Sample(0, 0, 4, false),
                new PalaceTerrainSnapshot.Sample(0, 1, 3, false)
        ), false);
        PalaceTerrainSnapshot taller = PalaceTerrainSnapshot.fromSamples(List.of(
                new PalaceTerrainSnapshot.Sample(0, 0, 5, false),
                new PalaceTerrainSnapshot.Sample(0, 1, 3, false)
        ), false);
        PalaceTerrainSnapshot wet = PalaceTerrainSnapshot.fromSamples(List.of(
                new PalaceTerrainSnapshot.Sample(0, 0, 4, true),
                new PalaceTerrainSnapshot.Sample(0, 1, 3, false)
        ), false);
        PalaceTerrainSnapshot schematic = PalaceTerrainSnapshot.fromSamples(List.of(
                new PalaceTerrainSnapshot.Sample(0, 0, 4, false),
                new PalaceTerrainSnapshot.Sample(0, 1, 3, false)
        ), true);
        assertNotEquals(base.contentHash(), taller.contentHash());
        assertNotEquals(base.contentHash(), wet.contentHash());
        assertNotEquals(base.contentHash(), schematic.contentHash());
    }

    @Test
    void fingerprintAliasMatchesContentHash() {
        PalaceTerrainSnapshot snap = PalaceTerrainSnapshot.schematicFlat(10, 20, 3, 4);
        assertEquals(snap.contentHash(), snap.fingerprint());
        assertFalse(snap.contentHash().isBlank());
        assertEquals(16, snap.contentHash().length());
    }
}
