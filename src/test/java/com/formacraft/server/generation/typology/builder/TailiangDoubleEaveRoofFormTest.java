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
