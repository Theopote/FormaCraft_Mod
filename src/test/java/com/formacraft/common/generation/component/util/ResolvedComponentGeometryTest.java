package com.formacraft.common.generation.component.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.MassMainGenerator;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ResolvedComponentGeometryTest {
    @Test void nestedPartsShareBackendFixturesAndEmitTheirActualEnvelope() throws Exception {
        var mapper = new ObjectMapper();
        MinecraftRegistryTestBootstrap.initialize();
        try (var input = getClass().getResourceAsStream("/regressions/multi-mass-geometry.json")) {
            for (var fixture : mapper.readTree(input)) {
                var c = mapper.treeToValue(fixture.get("component"), Component.class);
                var parts = ResolvedMassPart.resolve(c);
                assertEquals(2, parts.size());
                var expected = fixture.get("expected");
                for (int i = 0; i < parts.size(); i++) {
                    assertEquals(expected.get("part_ids").get(i).asText(), parts.get(i).partId());
                    assertEquals(mapper.treeToValue(expected.get("part_origins").get(i), Vec3i.class), parts.get(i).origin());
                    assertEquals(expected.get("roof_ys").get(i).asInt(), parts.get(i).bounds().maxY()-1);
                }
                var union = parts.getFirst().bounds().union(parts.get(1).bounds());
                assertEquals(expected.get("envelope").get("max_x").asInt(), union.maxX());
                assertEquals(expected.get("envelope").get("max_y").asInt(), union.maxY());
                if ("circle".equals(fixture.get("name").asText())) continue;
                var patches = new MassMainGenerator().generate(new SemanticComponent("MASS_MAIN", null, c));
                assertEquals(union.maxX()-1, patches.stream().mapToInt(BlockPatch::dx).max().orElseThrow());
                assertEquals(union.maxY()-1, patches.stream().mapToInt(BlockPatch::dy).max().orElseThrow());
            }
        }
    }
    @Test void sharesBackendGeometryFixtures() throws Exception {
        var mapper = new ObjectMapper();
        try (var input = getClass().getResourceAsStream("/regressions/resolved-geometry.json")) {
            for (var fixture : mapper.readTree(input)) {
                var c = mapper.treeToValue(fixture.get("component"), Component.class);
                var geometry = ResolvedComponentGeometry.resolve(c);
                var expected = fixture.get("expected");
                assertEquals(mapper.treeToValue(expected.get("origin"), Vec3i.class), geometry.origin());
                assertEquals(mapper.treeToValue(expected.get("dimensions"), Dimensions.class), geometry.dimensions());
                assertEquals(expected.get("roof_y").asInt(), geometry.roofY());
                assertEquals(expected.get("floor_layout_fits").asBoolean(), geometry.floorLayoutFits());
                assertEquals(mapper.convertValue(expected.get("floor_ys"), List.class), geometry.floorYs());
                var slot = mapper.treeToValue(fixture.get("slot_anchor"), Vec3i.class);
                var anchor = mapper.treeToValue(fixture.get("plan_anchor"), Vec3i.class);
                assertEquals(mapper.treeToValue(expected.get("plan_origin"), Vec3i.class), geometry.originInPlan(slot));
                assertEquals(mapper.treeToValue(expected.get("world_origin"), Vec3i.class), geometry.originInWorld(slot, anchor));
                assertEquals(geometry.bounds(), ComponentFootprintUtil.bounds(c));
            }
        }
    }

    @Test void emittedBodyUsesResolvedEnvelopeBeforeMassConfigCreation() {
        MinecraftRegistryTestBootstrap.initialize();
        var component = new Component("MASS_MAIN", null, new Vec3i(0, 0, 0),
                new Dimensions(1, 2, 2), List.of(), Map.of("floor_height", 5));
        var geometry = ResolvedComponentGeometry.resolve(component);
        var patches = new MassMainGenerator().generate(new SemanticComponent("MASS_MAIN", null, component));
        assertFalse(patches.isEmpty());
        assertEquals(geometry.origin().x(), patches.stream().mapToInt(BlockPatch::dx).min().orElseThrow());
        assertEquals(geometry.bounds().maxX()-1, patches.stream().mapToInt(BlockPatch::dx).max().orElseThrow());
        assertEquals(geometry.roofY(), patches.stream().mapToInt(BlockPatch::dy).max().orElseThrow());
    }

    @Test void contractedLocalFloorHeightWinsOverResearchHint() {
        var mass = new Component("MASS_MAIN", null, new Vec3i(0, 0, 0), new Dimensions(9, 9, 12),
                List.of(), Map.of("floor_height", 6));
        var plan = LlmPlanTestFixtures.builder().proportionHints(Map.of("floor_height", 3,
                "building_contract", Map.of("schema", "formacraft.building_contract.v1"))).build();
        assertEquals(6, ComponentFloorCorniceDecorator.resolveFloorHeight(plan, mass, 12));
    }
}
