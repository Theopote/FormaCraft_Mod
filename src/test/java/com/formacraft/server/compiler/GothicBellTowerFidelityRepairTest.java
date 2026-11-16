package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.StyleAttributes;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GothicBellTowerFidelityRepairTest {

    @Test
    void forcesIronBarsSteepSpireAndCentersDoor() {
        Component shaft = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 24), List.of(),
                Map.of("component_id", "shaft", "roof_type", "flat"));
        Component windows = new Component(
                "FACADE_WINDOWS", "main", new Vec3i(0, 4, 0),
                new Dimensions(8, 8, 16), List.of(),
                Map.of("host_id", "shaft", "window_style", "rectangular",
                        "glass_block", "minecraft:glass_pane"));
        Component roof = new Component(
                "ROOF", "main", new Vec3i(-1, 24, -1),
                new Dimensions(10, 10, 4), List.of("tower cap"),
                Map.of("component_id", "spire_cap", "host_id", "shaft", "roof_type", "flat"));
        Component entrance = new Component(
                "ENTRANCE", "main", new Vec3i(0, 0, 7),
                new Dimensions(2, 1, 4), List.of(),
                Map.of("host_id", "shaft", "facing", "SOUTH"));
        Map<String, Object> meta = new HashMap<>();
        meta.put("request_intent_text", "生成一座 8x8 格、高 35 格的哥特式四角钟楼。下部混砖，长条形铁栏杆狭窗，尖锐尖顶");
        meta.put("request_intent", Map.of(
                "building_class", "tower_like",
                "confidence", 0.9,
                "original_text", "生成一座 8x8 格、高 35 格的哥特式四角钟楼。下部混砖，长条形铁栏杆狭窗，尖锐尖顶"));
        StyleAttributes style = new StyleAttributes(
                null, "minecraft:deepslate_bricks", null, "minecraft:deepslate_tiles",
                "minecraft:andesite", null, List.of(), null,
                Map.of("glazing", "minecraft:iron_bars", "accent_wall", "minecraft:andesite"),
                null, null, null, null);
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .styleProfile("Gothic_Cathedral")
                .styleAttributes(style)
                .components(List.of(shaft, windows, roof, entrance))
                .planMeta(meta)
                .build();

        LlmPlan out = GothicBellTowerFidelityRepair.apply(plan);
        Component win = out.components().stream()
                .filter(c -> "FACADE_WINDOWS".equals(c.componentType())).findFirst().orElseThrow();
        assertEquals("minecraft:iron_bars", win.params().get("glass_block"));
        assertTrue(String.valueOf(win.params().get("window_style")).contains("bar")
                || String.valueOf(win.params().get("window_style")).contains("slit"));

        Component spire = out.components().stream()
                .filter(c -> "ROOF".equals(c.componentType())).findFirst().orElseThrow();
        assertEquals("pyramid", spire.params().get("roof_type"));

        Component door = out.components().stream()
                .filter(c -> "ENTRANCE".equals(c.componentType())).findFirst().orElseThrow();
        assertEquals(3, door.relativePosition().x(), "door should be centered on 8-wide south face");

        Component mass = out.components().stream()
                .filter(c -> "MASS_MAIN".equals(c.componentType())).findFirst().orElseThrow();
        assertEquals("gradient", mass.params().get("wall_pattern"));
    }

    @Test
    void remapsButtressStructureToMassSecondary() {
        Component shaft = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 21), List.of(),
                Map.of("component_id", "tower_shaft", "roof_type", "flat"));
        Component buttress = new Component(
                "STRUCTURE", "main", new Vec3i(-2, 0, -2),
                new Dimensions(2, 2, 21), List.of("corner buttress"),
                Map.of("component_id", "buttress_nw", "host_id", "tower_shaft"));
        Map<String, Object> meta = new HashMap<>();
        meta.put("request_intent_text", "生成一座 8x8 格、高 35 格的哥特式四角钟楼");
        meta.put("request_intent", Map.of(
                "building_class", "tower_like",
                "confidence", 0.95,
                "original_text", "生成一座 8x8 格、高 35 格的哥特式四角钟楼"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .styleProfile("Gothic_Cathedral")
                .components(List.of(shaft, buttress))
                .planMeta(meta)
                .build();
        LlmPlan out = GothicBellTowerFidelityRepair.apply(plan);
        Component remapped = out.components().stream()
                .filter(c -> c.params() != null && "buttress_nw".equals(c.params().get("component_id")))
                .findFirst().orElseThrow();
        assertEquals("MASS_SECONDARY", remapped.componentType());
        assertEquals("solid", remapped.params().get("extrude_mode"));
    }
}
