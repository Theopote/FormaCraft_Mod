package com.formacraft.server.generation.typology.builder;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.typology.TypologyParamResolver;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
        // True 重檐: lower full-span roof + inset upper roof (see TailiangTimberHallBuilder).
        assertTrue(hasInsetUpperRoofDeck(doubleGen.getBlocks(), 64, 21, 13),
                "double eave must show an inset upper roof deck above a wider lower deck");
        // Single xie_shan must NOT look like two inset decks under the same check.
        assertFalse(hasInsetUpperRoofDeck(singleGen.getBlocks(), 100, 21, 13),
                "single eave must not pass the inset dual-deck signature");
    }

    /**
     * Detects lower full-span roof + inset upper roof: some lower band spans ≈ hall footprint,
     * and a higher band is inset by ≥2 on both axes (generator upperInset).
     */
    private static boolean hasInsetUpperRoofDeck(
            List<com.formacraft.common.build.PlannedBlock> blocks,
            int originY, int hallW, int hallD) {
        if (blocks == null || blocks.isEmpty()) return false;
        Map<Integer, int[]> acc = new HashMap<>(); // y -> minX,maxX,minZ,maxZ,count
        for (var pb : blocks) {
            if (pb == null || pb.getPos() == null || pb.getTargetState() == null) continue;
            // Ignore AIR clear-volume markers.
            if (pb.getTargetState().isAir()) continue;
            String id = net.minecraft.registry.Registries.BLOCK
                    .getId(pb.getTargetState().getBlock()).toString();
            // Roof surfaces use deepslate tile/stair/slab in this typology's default palette.
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
        if (acc.size() < 2) return false;
        int minRoofY = acc.keySet().stream().mapToInt(Integer::intValue).min().orElse(0);
        int maxRoofY = acc.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        int[] lower = null;
        int lowerY = -1;
        for (var e : acc.entrySet()) {
            int y = e.getKey();
            // Lower deck lives in the bottom third of the roof stack.
            if (y > minRoofY + Math.max(1, (maxRoofY - minRoofY) / 3)) continue;
            int[] a = e.getValue();
            int spanX = a[1] - a[0] + 1;
            int spanZ = a[3] - a[2] + 1;
            if (spanX < hallW - 2 || spanZ < hallD - 2 || a[4] < 8) continue;
            if (lower == null || spanX * spanZ > (lower[1] - lower[0] + 1) * (lower[3] - lower[2] + 1)) {
                lower = a;
                lowerY = y;
            }
        }
        if (lower == null) return false;
        for (var e : acc.entrySet()) {
            int y = e.getKey();
            if (y <= lowerY + 1) continue;
            int[] a = e.getValue();
            if (a[4] < 8) continue;
            int insetX = (lower[1] - lower[0]) - (a[1] - a[0]);
            int insetZ = (lower[3] - lower[2]) - (a[3] - a[2]);
            // Generator uses upperInset ≥ 2 on each side ⇒ ≥4 total on an axis.
            if (insetX >= 4 && insetZ >= 4) return true;
        }
        return false;
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
