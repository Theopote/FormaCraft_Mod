package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResolvedFootprintTest {
    @Test void squareTowerShapeUsesItsAuthoredRectangularBounds() {
        var tower=new Component("TOWER","tower",new Vec3i(0,0,0),new Dimensions(7,7,14),List.of(),Map.of("shape","square"));
        var footprint=ResolvedFootprint.resolve(tower,7,7);
        assertEquals(ResolvedFootprint.FootprintShape.RECTANGLE,footprint.shape());
        for(int x=0;x<7;x++) for(int z=0;z<7;z++) assertTrue(footprint.contains(x,z));
        assertFalse(footprint.contains(7,3));
    }

    @Test
    void thickWallCourtyardHoleSharedByMaskAndResolve() {
        Map<String, Object> params = Map.of(
                "plan_type", "courtyard",
                "courtyard_ratio", 0.75,
                "wall_thickness", 6);
        Component mass = new Component("MASS_MAIN", "body", new Vec3i(0, 0, 0),
                new Dimensions(48, 48, 8), List.of("courtyard"), params);
        ResolvedFootprint fp = ResolvedFootprint.resolve(mass, 48, 48);
        ComponentFootprintMask mask = ComponentFootprintMask.from(null, params, 48, 48);

        assertEquals(fp.holeMinX(), mask.footprint().holeMinX());
        assertEquals(fp.holeMaxX(), mask.footprint().holeMaxX());
        assertEquals(fp.holeMinZ(), mask.footprint().holeMinZ());
        assertEquals(fp.holeMaxZ(), mask.footprint().holeMaxZ());

        Set<String> holeCells = new HashSet<>();
        for (int x = 0; x < 48; x++) {
            for (int z = 0; z < 48; z++) {
                assertEquals(fp.contains(x, z), mask.contains(x, z), "occupancy mismatch at " + x + "," + z);
                if (fp.isCourtyardHole(x, z)) {
                    holeCells.add(x + "," + z);
                    assertFalse(fp.contains(x, z));
                    assertTrue(mask.isCourtyardHole(x, z));
                }
            }
        }
        assertFalse(holeCells.isEmpty());
        // With wt=6 and ratio 0.75 on 48: hole = min(36, 48-12) = 36, centered.
        assertEquals(6, fp.holeMinX());
        assertEquals(41, fp.holeMaxX());
        assertEquals(6, fp.holeMinZ());
        assertEquals(41, fp.holeMaxZ());
    }

    @Test
    void explicitCourtyardRatioNotAestheticallyClamped() {
        Map<String, Object> params = Map.of(
                "plan_type", "courtyard",
                "courtyard_ratio", 0.75,
                "wall_thickness", 1);
        ResolvedFootprint fp = ResolvedFootprint.resolve(
                new Component("MASS_MAIN", "body", new Vec3i(0, 0, 0),
                        new Dimensions(20, 20, 8), List.of(), params),
                20, 20);
        assertEquals(0.75, fp.courtyardRatio(), 1e-9);
        int holeW = fp.holeMaxX() - fp.holeMinX() + 1;
        assertEquals(15, holeW);
    }

    @Test
    void illegalExplicitRatioDoesNotFallBackToDefaultHole() {
        Map<String, Object> params = Map.of(
                "plan_type", "courtyard",
                "courtyard_ratio", -0.2,
                "wall_thickness", 1);
        ResolvedFootprint fp = ResolvedFootprint.resolve(
                new Component("MASS_MAIN", "body", new Vec3i(0, 0, 0),
                        new Dimensions(16, 16, 8), List.of(), params),
                16, 16);
        assertEquals(0.0, fp.courtyardRatio(), 1e-9);
        assertFalse(fp.isCourtyardHole(8, 8));
    }

    @Test
    void gothicFeaturesDoNotForceCrossWithoutExplicitPlanType() {
        Map<String, Object> params = Map.of();
        Component mass = new Component("MASS_MAIN", "body", new Vec3i(0, 0, 0),
                new Dimensions(20, 14, 12), List.of("gothic", "cathedral"), params);
        ResolvedFootprint fp = ResolvedFootprint.resolve(mass, 20, 14);
        assertEquals(ResolvedFootprint.PlanPattern.NONE, fp.pattern());
        assertEquals(ResolvedFootprint.FootprintShape.RECTANGLE, fp.shape());
    }

    @Test
    void unsupportedExplicitPlanTypeThrows() {
        Map<String, Object> params = Map.of("plan_type", "u_shape");
        try {
            ResolvedFootprint.resolve(
                    new Component("MASS_MAIN", "body", new Vec3i(0, 0, 0),
                            new Dimensions(16, 16, 8), List.of(), params),
                    16, 16);
            throw new AssertionError("expected E_PLAN_PATTERN_UNSUPPORTED");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().startsWith("E_PLAN_PATTERN_UNSUPPORTED"));
        }
    }

    @Test
    void unsupportedExplicitShapeThrows() {
        Map<String, Object> params = Map.of("shape", "hexagon");
        try {
            ResolvedFootprint.resolve(
                    new Component("MASS_MAIN", "body", new Vec3i(0, 0, 0),
                            new Dimensions(16, 16, 8), List.of(), params),
                    16, 16);
            throw new AssertionError("expected E_FOOTPRINT_SHAPE_UNSUPPORTED");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().startsWith("E_FOOTPRINT_SHAPE_UNSUPPORTED"));
        }
    }

    @Test
    void circleCrossCombinationThrows() {
        Map<String, Object> params = Map.of("shape", "circle", "plan_type", "cross");
        try {
            ResolvedFootprint.resolve(
                    new Component("MASS_MAIN", "body", new Vec3i(0, 0, 0),
                            new Dimensions(16, 16, 8), List.of(), params),
                    16, 16);
            throw new AssertionError("expected E_FOOTPRINT_COMBINATION_UNSUPPORTED");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().startsWith("E_FOOTPRINT_COMBINATION_UNSUPPORTED"));
        }
    }
}
