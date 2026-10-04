package com.formacraft.common.generation.component.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.MassMainGenerator;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ResolvedComponentGeometryTest {
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
        assertEquals(geometry.origin().x(), patches.stream().mapToInt(p -> p.dx()).min().orElseThrow());
        assertEquals(geometry.bounds().maxX()-1, patches.stream().mapToInt(p -> p.dx()).max().orElseThrow());
        assertEquals(geometry.roofY(), patches.stream().mapToInt(p -> p.dy()).max().orElseThrow());
    }

    @Test void contractedLocalFloorHeightWinsOverResearchHint() {
        var mass = new Component("MASS_MAIN", null, new Vec3i(0, 0, 0), new Dimensions(9, 9, 12),
                List.of(), Map.of("floor_height", 6));
        var plan = LlmPlanTestFixtures.builder().proportionHints(Map.of("floor_height", 3,
                "building_contract", Map.of("schema", "formacraft.building_contract.v1"))).build();
        assertEquals(6, ComponentFloorCorniceDecorator.resolveFloorHeight(plan, mass, 12));
    }
}
