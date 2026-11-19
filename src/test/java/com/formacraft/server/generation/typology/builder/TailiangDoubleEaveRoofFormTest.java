package com.formacraft.server.generation.typology.builder;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.typology.TypologyParamResolver;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** double_eave_xieshan must execute as xie_shan + doubleEave — never throw unsupported form. */
class TailiangDoubleEaveRoofFormTest {

    @BeforeAll
    static void bootstrap() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void doubleEaveXieshanDecomposesAndGenerates() {
        Map<String, Object> params = baseParams();
        params.put("roof_type", "double_eave_xieshan");
        Component component = new Component(
                "STRUCTURE", null, new Vec3i(0, 0, 0),
                new Dimensions(21, 13, 20),
                List.of("typology:tailiang_timber_hall"),
                params);
        Map<String, Object> merged = TypologyParamResolver.merge("tailiang_timber_hall", component);
        assertEquals("xie_shan", String.valueOf(merged.get("roofType")));
        assertTrue(Boolean.TRUE.equals(merged.get("doubleEave"))
                || Boolean.TRUE.equals(merged.get("double_eave")));

        assertDoesNotThrow(() -> TailiangTimberHallBuilder.generate(
                merged, new BlockPos(0, 64, 0), null, null));
    }

    @Test
    void doubleEaveXieshanEmitsTwoRoofTiersInBlockPatch() {
        Map<String, Object> params = baseParams();
        params.put("roof_type", "double_eave_xieshan");
        params.put("roofRise", 6);
        params.put("includeSubEaves", false);
        Component component = new Component(
                "STRUCTURE", null, new Vec3i(0, 0, 0),
                new Dimensions(21, 13, 20),
                List.of("typology:tailiang_timber_hall"),
                params);
        Map<String, Object> merged = TypologyParamResolver.merge("tailiang_timber_hall", component);
        var single = new HashMap<>(merged);
        single.put("doubleEave", false);
        single.put("double_eave", false);
        single.put("doubleEaves", false);
        single.put("double_eaves", false);
        single.put("roof_type", "xie_shan");
        single.put("roofType", "xie_shan");

        var doubleGen = TailiangTimberHallBuilder.generate(merged, new BlockPos(0, 64, 0), null, null);
        var singleGen = TailiangTimberHallBuilder.generate(single, new BlockPos(100, 64, 0), null, null);
        assertNotNull(doubleGen);
        assertNotNull(singleGen);

        int doubleMaxY = maxBlockY(doubleGen.getBlocks());
        int singleMaxY = maxBlockY(singleGen.getBlocks());
        int doubleCount = doubleGen.getBlocks() == null ? 0 : doubleGen.getBlocks().size();
        int singleCount = singleGen.getBlocks() == null ? 0 : singleGen.getBlocks().size();
        assertTrue(doubleCount > singleCount,
                "double eave must emit more blocks than single xie_shan ("
                        + doubleCount + " vs " + singleCount + ")");
        assertTrue(doubleMaxY >= singleMaxY,
                "double eave upper tier must reach at least as high as single roof");
        // Distinct Y bands above the timber body (≥2 roof layers).
        assertTrue(distinctHighYBands(doubleGen.getBlocks(), 64 + 10) >= 2,
                "double eave BlockPatch must occupy at least two roof Y bands");
        // singleGen is at (100, 64, 0) — originY is 64, not the X offset 100.
        assertTrue(countRoofMaterial(singleGen.getBlocks(), 64) >= 8,
                "single-eave control must actually collect roof tiles");
        assertTrue(countRoofMaterial(doubleGen.getBlocks(), 64) >= 8,
                "double-eave roof must emit tile material");
        // True 重檐: discrete inset jump from a near-full hall band exceeds single-eave taper.
        int doubleJump = maxFullHallAdjacentSpanDrop(doubleGen.getBlocks(), 64, 21, 13);
        int singleJump = maxFullHallAdjacentSpanDrop(singleGen.getBlocks(), 64, 21, 13);
        assertTrue(doubleJump >= 4,
                "double eave must show an inset jump ≥4 from a full-hall band (got " + doubleJump + ")");
        assertTrue(doubleJump > singleJump,
                "double-eave inset jump (" + doubleJump
                        + ") must exceed single-eave taper (" + singleJump + ")");
    }

    private static int countRoofMaterial(
            List<com.formacraft.common.build.PlannedBlock> blocks, int originY) {
        if (blocks == null) return 0;
        int n = 0;
        for (var pb : blocks) {
            if (pb == null || pb.getPos() == null || pb.getTargetState() == null) continue;
            if (pb.getTargetState().isAir()) continue;
            if (pb.getPos().getY() < originY + 8) continue;
            String id = net.minecraft.registry.Registries.BLOCK
                    .getId(pb.getTargetState().getBlock()).toString();
            if (id.contains("deepslate_tile") || id.contains("deepslate_brick")) n++;
        }
        return n;
    }

    /**
     * Largest min(dropX, dropZ) between adjacent roof Y bands where the lower band still spans
     * ≈ the hall footprint. Double eave jumps from a full lower deck to an inset upper deck;
     * single eave only sees large drops after it has already left the full-hall band.
     */
    private static int maxFullHallAdjacentSpanDrop(
            List<com.formacraft.common.build.PlannedBlock> blocks,
            int originY, int hallW, int hallD) {
        Map<Integer, int[]> acc = roofBandsByY(blocks, originY);
        if (acc.size() < 2) return 0;
        List<Integer> ys = new ArrayList<>(acc.keySet());
        ys.sort(Integer::compareTo);
        int minUpperSpanX = Math.max(5, hallW / 2);
        int minUpperSpanZ = Math.max(5, hallD / 2);
        int best = 0;
        for (int i = 0; i + 1 < ys.size(); i++) {
            int[] a = acc.get(ys.get(i));
            int[] b = acc.get(ys.get(i + 1));
            if (a[4] < 8 || b[4] < 8) continue;
            int spanAX = a[1] - a[0] + 1;
            int spanAZ = a[3] - a[2] + 1;
            int spanBX = b[1] - b[0] + 1;
            int spanBZ = b[3] - b[2] + 1;
            if (spanAX < hallW - 2 || spanAZ < hallD - 2) continue;
            if (spanBX < minUpperSpanX || spanBZ < minUpperSpanZ) continue;
            int drop = Math.min(spanAX - spanBX, spanAZ - spanBZ);
            best = Math.max(best, drop);
        }
        return best;
    }

    private static Map<Integer, int[]> roofBandsByY(
            List<com.formacraft.common.build.PlannedBlock> blocks, int originY) {
        Map<Integer, int[]> acc = new HashMap<>();
        if (blocks == null) return acc;
        for (var pb : blocks) {
            if (pb == null || pb.getPos() == null || pb.getTargetState() == null) continue;
            if (pb.getTargetState().isAir()) continue;
            String id = net.minecraft.registry.Registries.BLOCK
                    .getId(pb.getTargetState().getBlock()).toString();
            if (!(id.contains("deepslate_tile") || id.contains("deepslate_brick"))) continue;
            int y = pb.getPos().getY();
            if (y < originY + 8) continue;
            int x = pb.getPos().getX();
            int z = pb.getPos().getZ();
            int[] a = acc.computeIfAbsent(y, k -> new int[]{x, x, z, z, 0});
            a[0] = Math.min(a[0], x);
            a[1] = Math.max(a[1], x);
            a[2] = Math.min(a[2], z);
            a[3] = Math.max(a[3], z);
            a[4]++;
        }
        return acc;
    }

    private static int maxBlockY(List<com.formacraft.common.build.PlannedBlock> blocks) {
        int max = Integer.MIN_VALUE;
        if (blocks == null) return max;
        for (var pb : blocks) {
            if (pb != null && pb.getPos() != null) {
                max = Math.max(max, pb.getPos().getY());
            }
        }
        return max;
    }

    private static int distinctHighYBands(List<com.formacraft.common.build.PlannedBlock> blocks, int minY) {
        java.util.Set<Integer> ys = new java.util.HashSet<>();
        if (blocks == null) return 0;
        for (var pb : blocks) {
            if (pb == null || pb.getPos() == null) continue;
            int y = pb.getPos().getY();
            if (y >= minY) ys.add(y);
        }
        return ys.size();
    }

    @Test
    void planRoofTypeBeatsTypologyDefaultWudian() {
        Map<String, Object> params = new HashMap<>();
        params.put("roof_type", "xieshan");
        params.put("width", 21);
        params.put("depth", 13);
        Component component = new Component(
                "STRUCTURE", null, new Vec3i(0, 0, 0),
                new Dimensions(21, 13, 20),
                List.of("typology:tailiang_timber_hall"),
                params);
        Map<String, Object> merged = TypologyParamResolver.merge("tailiang_timber_hall", component);
        String roof = String.valueOf(merged.get("roofType"));
        assertTrue(roof.contains("xie") || roof.contains("歇"),
                "user/plan roof_type must beat typology default wudian, got " + roof);
        assertFalse(roof.contains("wu") || roof.contains("庑"),
                "typology soft wudian must not win over plan xieshan");
    }

    private static Map<String, Object> baseParams() {
        Map<String, Object> p = new HashMap<>();
        p.put("typology_id", TailiangTimberHallBuilder.TYPOLOGY_ID);
        p.put("width", 21);
        p.put("depth", 13);
        p.put("baysX", 7);
        p.put("baysZ", 4);
        p.put("facing", "SOUTH");
        p.put("anchorMode", "center");
        p.put("includeSubEaves", false);
        p.put("includeBrackets", false);
        return p;
    }
}
