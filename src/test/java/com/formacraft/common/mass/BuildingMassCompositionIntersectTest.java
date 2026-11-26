package com.formacraft.common.mass;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BuildingMassCompositionIntersectTest {

    private static BuildingMass box(String id, int x0, int z0, int x1, int z1, int y0, int y1,
                                    MassOperation op, MassRole role) {
        return new BuildingMass(id, new RectMask(x0, x1, z0, z1), new HeightRange(y0, y1),
                MassType.SOLID, op, role);
    }

    @Test
    void intersectOnlyAllowsOverlapOfAllIntersectors() {
        BuildingMass a = box("a", 0, 0, 7, 7, 0, 5, MassOperation.INTERSECT, MassRole.PRIMARY);
        BuildingMass b = box("b", 4, 4, 11, 11, 0, 5, MassOperation.INTERSECT, MassRole.SECONDARY);
        BuildingMassComposition composition = new BuildingMassComposition(
                null, List.of(a, b), Map.of(), Map.of());

        assertTrue(composition.allowsBlockAt(5, 2, 5), "overlap cell");
        assertFalse(composition.allowsBlockAt(1, 2, 1), "only in A");
        assertFalse(composition.allowsBlockAt(10, 2, 10), "only in B");
    }

    @Test
    void addThenIntersectGatesAdditiveFill() {
        BuildingMass add = box("add", 0, 0, 9, 9, 0, 4, MassOperation.ADD, MassRole.PRIMARY);
        BuildingMass gate = box("gate", 3, 3, 6, 6, 0, 4, MassOperation.INTERSECT, MassRole.SECONDARY);
        BuildingMassComposition composition = new BuildingMassComposition(
                null, List.of(add, gate), Map.of(), Map.of());

        assertTrue(composition.allowsBlockAt(4, 1, 4));
        assertFalse(composition.allowsBlockAt(1, 1, 1), "ADD cell outside INTERSECT gate");
    }

    @Test
    void subtractStillClearsCourt() {
        BuildingMass add = box("add", 0, 0, 9, 9, 0, 4, MassOperation.ADD, MassRole.PRIMARY);
        BuildingMass sub = box("sub", 2, 2, 7, 7, 0, 4, MassOperation.SUBTRACT, MassRole.SECONDARY);
        BuildingMassComposition composition = new BuildingMassComposition(
                null, List.of(add, sub), Map.of(), Map.of());
        assertTrue(composition.allowsBlockAt(0, 1, 0));
        assertFalse(composition.allowsBlockAt(4, 1, 4));
    }
}
