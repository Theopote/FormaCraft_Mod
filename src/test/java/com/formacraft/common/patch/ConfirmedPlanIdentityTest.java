package com.formacraft.common.patch;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfirmedPlanIdentityTest {

    @Test
    void patchHashIgnoresListOrder() {
        List<BlockPatch> a = List.of(
                new BlockPatch(BlockPatch.PLACE, 1, 0, 0, "minecraft:stone"),
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:dirt")
        );
        List<BlockPatch> b = List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:dirt"),
                new BlockPatch(BlockPatch.PLACE, 1, 0, 0, "minecraft:stone")
        );
        assertEquals(ConfirmedPlanIdentity.patchHash(a), ConfirmedPlanIdentity.patchHash(b));
    }

    @Test
    void differentPatchesDifferentPlanIdsEvenWithSameContext() {
        String ctx = "ctx1234567890ab";
        List<BlockPatch> a = List.of(new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:stone"));
        List<BlockPatch> b = List.of(new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:dirt"));
        assertNotEquals(
                ConfirmedPlanIdentity.planId(ctx, a),
                ConfirmedPlanIdentity.planId(ctx, b));
        assertNotEquals(
                ConfirmedPlanIdentity.patchHash(a),
                ConfirmedPlanIdentity.patchHash(b));
    }

    @Test
    void nestedMapInsertionOrderDoesNotAffectParamHash() {
        LinkedHashMap<String, Object> nestedA = new LinkedHashMap<>();
        nestedA.put("palette", "stone");
        nestedA.put("trim", "gold");
        LinkedHashMap<String, Object> nestedB = new LinkedHashMap<>();
        nestedB.put("trim", "gold");
        nestedB.put("palette", "stone");

        LinkedHashMap<String, Object> a = new LinkedHashMap<>();
        a.put("materials", nestedA);
        a.put("tiers", 3);
        LinkedHashMap<String, Object> b = new LinkedHashMap<>();
        b.put("tiers", 3);
        b.put("materials", nestedB);

        assertEquals(
                ConfirmedPlanIdentity.hashCanonicalParams(a),
                ConfirmedPlanIdentity.hashCanonicalParams(b));
    }

    @Test
    void nestedArrayOrderIsPreserved() {
        Map<String, Object> a = Map.of("steps", List.of("a", "b"));
        Map<String, Object> b = Map.of("steps", List.of("b", "a"));
        assertNotEquals(
                ConfirmedPlanIdentity.hashCanonicalParams(a),
                ConfirmedPlanIdentity.hashCanonicalParams(b));
    }

    @Test
    void volatileKeysAreSkipped() {
        Map<String, Object> a = Map.of("tiers", 3, "terrainFingerprint", "xxx");
        Map<String, Object> b = Map.of("tiers", 3, "buildContextHash", "yyy");
        assertEquals(
                ConfirmedPlanIdentity.hashCanonicalParams(a),
                ConfirmedPlanIdentity.hashCanonicalParams(b));
    }

    @Test
    void worldStateFingerprintStable() {
        LinkedHashMap<BlockPos, String> a = new LinkedHashMap<>();
        a.put(new BlockPos(1, 2, 3), "minecraft:air");
        a.put(new BlockPos(0, 0, 0), "minecraft:stone");
        LinkedHashMap<BlockPos, String> b = new LinkedHashMap<>();
        b.put(new BlockPos(0, 0, 0), "minecraft:stone");
        b.put(new BlockPos(1, 2, 3), "minecraft:air");
        assertEquals(
                ConfirmedPlanIdentity.worldStateFingerprint(a),
                ConfirmedPlanIdentity.worldStateFingerprint(b));
    }
}
