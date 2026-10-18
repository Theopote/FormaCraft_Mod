package com.formacraft.server.generation.typology.builder;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PalaceBuildContextFingerprintTest {

    @Test
    void sameTerrainDifferentOriginsProduceDifferentContextHashes() {
        String terrain = "abcd1234abcd1234";
        Map<String, Object> params = Map.of("baseWidth", 25, "tiers", 3);
        PalaceBuildContextFingerprint a = PalaceBuildContextFingerprint.create(
                "minecraft:overworld", new BlockPos(0, 64, 0), Direction.SOUTH, params, terrain, "stone");
        PalaceBuildContextFingerprint b = PalaceBuildContextFingerprint.create(
                "minecraft:overworld", new BlockPos(100, 64, 0), Direction.SOUTH, params, terrain, "stone");
        assertEquals(terrain, a.terrainContentHash());
        assertEquals(terrain, b.terrainContentHash());
        assertNotEquals(a.contextHash(), b.contextHash());
    }

    @Test
    void facingWorldAndMaterialsAffectContext() {
        String terrain = "abcd1234abcd1234";
        Map<String, Object> params = Map.of("baseWidth", 25);
        BlockPos origin = new BlockPos(10, 64, 20);
        PalaceBuildContextFingerprint base = PalaceBuildContextFingerprint.create(
                "minecraft:overworld", origin, Direction.SOUTH, params, terrain, "stone");
        assertNotEquals(base.contextHash(), PalaceBuildContextFingerprint.create(
                "minecraft:the_nether", origin, Direction.SOUTH, params, terrain, "stone").contextHash());
        assertNotEquals(base.contextHash(), PalaceBuildContextFingerprint.create(
                "minecraft:overworld", origin, Direction.NORTH, params, terrain, "stone").contextHash());
        assertNotEquals(base.contextHash(), PalaceBuildContextFingerprint.create(
                "minecraft:overworld", origin, Direction.SOUTH, params, terrain, "gold").contextHash());
    }

    @Test
    void paramOrderAndVolatileKeysDoNotAffectParamsHash() {
        LinkedHashMap<String, Object> a = new LinkedHashMap<>();
        a.put("tiers", 3);
        a.put("baseWidth", 25);
        a.put("terrainFingerprint", "should-ignore");
        a.put("buildContextHash", "also-ignore");
        LinkedHashMap<String, Object> b = new LinkedHashMap<>();
        b.put("baseWidth", 25);
        b.put("tiers", 3);
        b.put("schematicFlatTerrain", true);
        assertEquals(
                PalaceBuildContextFingerprint.hashCanonicalParams(a),
                PalaceBuildContextFingerprint.hashCanonicalParams(b));
    }

    @Test
    void paramChangeAltersContext() {
        String terrain = "abcd1234abcd1234";
        BlockPos origin = new BlockPos(0, 64, 0);
        PalaceBuildContextFingerprint a = PalaceBuildContextFingerprint.create(
                "schematic", origin, Direction.SOUTH, Map.of("tiers", 3), terrain, null);
        PalaceBuildContextFingerprint b = PalaceBuildContextFingerprint.create(
                "schematic", origin, Direction.SOUTH, Map.of("tiers", 4), terrain, null);
        assertNotEquals(a.contextHash(), b.contextHash());
    }
}
