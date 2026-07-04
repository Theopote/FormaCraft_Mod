package com.formacraft.server.cluster.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.formacraft.common.buildcontext.OutlineShape;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * 确定性回归：C2 {@link ParcelSubdivision} 地块划分 + C3 {@link SiteLayoutPlanner} 场地排布。
 * 仅依赖 {@link BlockPos}（纯数学类），无需 Minecraft 引导。
 */
class SiteLayoutTest {

    private static OutlineShape squarePolygon(int min, int max) {
        List<BlockPos> verts = List.of(
                new BlockPos(min, 0, min),
                new BlockPos(max, 0, min),
                new BlockPos(max, 0, max),
                new BlockPos(min, 0, max));
        return new OutlineShape("polygon", verts, null, 0, 0, 0);
    }

    // ---- ParcelSubdivision (C2) ----

    @Test
    void subdivideProducesContainedParcelsOfMinSize() {
        OutlineShape site = squarePolygon(0, 20);
        List<ParcelSubdivision.Parcel> parcels = ParcelSubdivision.subdivide(site, 4, 0, 2);
        assertFalse(parcels.isEmpty(), "square site should subdivide");
        assertTrue(parcels.size() >= 2, "target=4 should split at least once: " + parcels.size());
        for (ParcelSubdivision.Parcel p : parcels) {
            assertTrue(p.width() >= 2 && p.depth() >= 2, "parcel below min size: " + p);
            assertTrue(ParcelSubdivision.containsXZ(site, p.centerX(), p.centerZ()),
                    "parcel centre must be inside outline: " + p);
            assertTrue(p.minX() >= 0 && p.maxX() <= 20 && p.minZ() >= 0 && p.maxZ() <= 20,
                    "parcel out of bounds: " + p);
        }
    }

    @Test
    void subdivideIsDeterministic() {
        OutlineShape site = squarePolygon(0, 32);
        assertEquals(ParcelSubdivision.subdivide(site, 6, 1, 3).toString(),
                ParcelSubdivision.subdivide(site, 6, 1, 3).toString());
    }

    @Test
    void containsXzPolygonAndCircle() {
        OutlineShape square = squarePolygon(0, 20);
        assertTrue(ParcelSubdivision.containsXZ(square, 10, 10));
        assertFalse(ParcelSubdivision.containsXZ(square, 25, 25));

        OutlineShape circle = new OutlineShape("circle", List.of(), new BlockPos(0, 0, 0), 10, 0, 0);
        assertTrue(ParcelSubdivision.containsXZ(circle, 0, 0));
        assertTrue(ParcelSubdivision.containsXZ(circle, 6, 6)); // dist ~8.49 <= 10
        assertFalse(ParcelSubdivision.containsXZ(circle, 100, 0));
    }

    @Test
    void singleParcelShrinksByMargin() {
        OutlineShape site = squarePolygon(0, 20);
        ParcelSubdivision.Parcel p = ParcelSubdivision.singleParcel(site, 2);
        assertNotNull(p);
        assertTrue(p.minX() >= 2 && p.maxX() <= 18, "single parcel not inset by margin: " + p);
        assertTrue(p.width() >= 1 && p.depth() >= 1);
    }

    // ---- SiteLayoutPlanner (C3) ----

    @Test
    void planPlacesEveryUnitWithinSite() {
        OutlineShape site = squarePolygon(0, 40);
        BlockPos origin = new BlockPos(0, 0, 0);
        List<BuildingUnit> units = new ArrayList<>();
        for (int i = 0; i < 3; i++) units.add(new BuildingUnit("u" + i, 6, 6, 10, 5));

        List<BuildingPlacement> placed = SiteLayoutPlanner.plan(site, origin, units, 2);
        assertEquals(units.size(), placed.size(), "every unit should get a placement");
        for (BuildingPlacement bp : placed) {
            assertNotNull(bp.originRel);
            int x = bp.originRel.getX();
            int z = bp.originRel.getZ();
            assertTrue(x >= 0 && x <= 40 && z >= 0 && z <= 40, "placement out of site: " + bp.originRel);
            assertTrue(bp.rotation == 0 || bp.rotation == 90, "unexpected rotation: " + bp.rotation);
        }
    }

    @Test
    void planIsDeterministic() {
        OutlineShape site = squarePolygon(0, 40);
        BlockPos origin = new BlockPos(0, 0, 0);
        List<BuildingUnit> units = List.of(
                new BuildingUnit("big", 12, 8, 20, 9),
                new BuildingUnit("small", 5, 5, 8, 3));

        List<BuildingPlacement> a = SiteLayoutPlanner.plan(site, origin, units, 2);
        List<BuildingPlacement> b = SiteLayoutPlanner.plan(site, origin, units, 2);
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).originRel, b.get(i).originRel, "originRel diverged at " + i);
            assertEquals(a.get(i).rotation, b.get(i).rotation, "rotation diverged at " + i);
        }
    }

    @Test
    void planSingleReturnsCentredPlacement() {
        OutlineShape site = squarePolygon(0, 30);
        BlockPos origin = new BlockPos(0, 0, 0);
        BuildingPlacement bp = SiteLayoutPlanner.planSingle(site, origin, new BuildingUnit("solo", 8, 8, 12, 5), 0);
        assertNotNull(bp);
        assertTrue(bp.originRel.getX() >= 0 && bp.originRel.getX() <= 30);
        assertTrue(bp.originRel.getZ() >= 0 && bp.originRel.getZ() <= 30);
    }
}
