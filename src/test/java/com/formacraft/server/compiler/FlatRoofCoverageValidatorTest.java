package com.formacraft.server.compiler;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FlatRoofCoverageValidatorTest {
    @Test void hostGapIdentifiesOnlyRoofPiecesAssignedToTheFailingPart() {
        var cell = Set.of(new BlockPos(0, 7, 0));
        var left = new FlatRoofCoverageValidator.Roof("left", cell, cell, "house", "house");
        var right = new FlatRoofCoverageValidator.Roof("right", cell, cell, "house", "house");
        var other = new FlatRoofCoverageValidator.Roof("annex", cell, cell, "house", "house#mass_1");
        var missing = FlatRoofCoverageValidator.checkHost(body(Map.of()), List.of(right, other, left),
                BlockPos.ORIGIN, patches(left, 0), Set.of()).orElseThrow();
        assertEquals(List.of("left", "right"),
                FlatRoofCoverageValidator.hostCoverageGap("house", missing).details().get("roof_sources"));
    }

    private SemanticComponent body(Map<String,Object> extra) {
        var params = new HashMap<String,Object>(Map.of("component_id","house","anchor_mode","min_corner"));
        params.putAll(extra);
        return new SemanticComponent("MASS_MAIN",null,new Component("MASS_MAIN","body",new Vec3i(0,0,0),
                new Dimensions(9,9,8),List.of(),params));
    }
    @Test void completeSmallOrShiftedRoofStillFailsHostCoverageAndOverhangCanCoverHost() {
        for (var roof : List.of(new Component("ROOF","roof",new Vec3i(0,7,0),new Dimensions(5,5,1),List.of(),
                        Map.of("roof_type","flat","host_id","house")),
                new Component("ROOF","roof",new Vec3i(1,7,0),new Dimensions(9,9,1),List.of(),
                        Map.of("roof_type","flat","host_id","house")))) {
            var r = FlatRoofCoverageValidator.resolve(new SemanticComponent("ROOF",null,roof),BlockPos.ORIGIN).orElseThrow();
            assertTrue(FlatRoofCoverageValidator.check(List.of(r),patches(r,0),Set.of()).isEmpty());
            var missing = FlatRoofCoverageValidator.checkHost(body(Map.of()), List.of(r),
                    BlockPos.ORIGIN, patches(r,0), Set.of()).orElseThrow();
            var gap = FlatRoofCoverageValidator.hostCoverageGap("house", missing);
            assertEquals("E_FLAT_ROOF_HOST_COVERAGE", gap.code());
            assertEquals("house", gap.details().get("host_id"));
            assertEquals("house", gap.details().get("host_part_id"));
            assertEquals(roof.relativePosition().x() == 1 ? 9 : 56, gap.details().get("uncovered_count"));
            assertEquals("plan", gap.details().get("coordinate_frame"));
            assertEquals(Map.of("x", 0, "y", 7, "z", roof.relativePosition().x() == 1 ? 0 : 5),
                    gap.details().get("first_uncovered"));
        }
        var roof = new Component("ROOF","roof",new Vec3i(1,7,1),new Dimensions(7,7,1),List.of(),
                Map.of("roof_type","flat","host_id","house","overhang",1));
        var r = FlatRoofCoverageValidator.resolve(new SemanticComponent("ROOF",null,roof),BlockPos.ORIGIN).orElseThrow();
        var blocks = r.coverage().stream().map(p -> new BlockPatch(BlockPatch.PLACE,p.getX(),p.getY(),p.getZ(),"minecraft:stone_bricks")).toList();
        assertTrue(FlatRoofCoverageValidator.checkHost(body(Map.of()),List.of(r),BlockPos.ORIGIN,blocks,Set.of()).isEmpty());
    }
    @Test void matchingCourtyardMaskAndExplicitStairOpeningRemainLegal() {
        var c = roof(Map.of("roof_type","flat","host_id","house","plan_type","courtyard"));
        var r = FlatRoofCoverageValidator.resolve(new SemanticComponent("ROOF",null,c),BlockPos.ORIGIN).orElseThrow();
        assertTrue(FlatRoofCoverageValidator.checkHost(body(Map.of("plan_type","courtyard")),List.of(r),BlockPos.ORIGIN,patches(r,0),Set.of()).isEmpty());
        var plain = FlatRoofCoverageValidator.resolve(new SemanticComponent("ROOF",null,roof(Map.of("roof_type","flat","host_id","house"))),BlockPos.ORIGIN).orElseThrow();
        var opening = new BlockPos(2,7,2);
        var blocks = new ArrayList<>(patches(plain,0));
        blocks.add(new BlockPatch(BlockPatch.REMOVE,2,7,2,"minecraft:air"));
        assertTrue(FlatRoofCoverageValidator.checkHost(body(Map.of()),List.of(plain),BlockPos.ORIGIN,blocks,Set.of(opening)).isEmpty());
    }
    @Test void higherBodyHidesLowerTopWithoutRequiringRoofUnderIt() {
        var body = new Component("MASS_MAIN","body",new Vec3i(0,0,0),new Dimensions(4,4,5),List.of(),
                Map.of("component_id","house","anchor_mode","min_corner","masses",List.of(Map.of(
                        "offset",Map.of("x",2,"y",0,"z",2),"dimensions",Map.of("width",2,"depth",2,"height",8)))));
        var roof = new Component("ROOF","roof",new Vec3i(0,4,0),new Dimensions(4,4,1),List.of(),
                Map.of("roof_type","flat","host_id","house"));
        var offset = new BlockPos(20,3,30);
        var r = FlatRoofCoverageValidator.resolve(new SemanticComponent("ROOF",null,roof),offset).orElseThrow();
        var blocks = patches(r,0).stream().filter(p -> !(p.dx() >= 22 && p.dz() >= 32)).toList();
        assertEquals(12,blocks.size());
        assertTrue(FlatRoofCoverageValidator.checkHost(new SemanticComponent("MASS_MAIN",null,body),List.of(r),offset,blocks,Set.of()).isEmpty());
        // Interior host-top hole (not perimeter eave) must still fail coverage.
        var exposed = new ArrayList<>(blocks);
        exposed.add(new BlockPatch(BlockPatch.REMOVE,21,7,31,"minecraft:air"));
        assertEquals(new BlockPos(21,7,31), FlatRoofCoverageValidator.checkHost(new SemanticComponent("MASS_MAIN",null,body),
                List.of(r),offset,exposed,Set.of()).orElseThrow().position());
    }
    private Component roof(Map<String,Object> params) {
        return new Component("ROOF", "roof", new Vec3i(0,7,0), new Dimensions(9,9,3), List.of(), params);
    }
    private List<BlockPatch> patches(FlatRoofCoverageValidator.Roof roof, int yShift) {
        return roof.core().stream().map(p -> new BlockPatch(BlockPatch.PLACE, p.getX(), p.getY()+yShift, p.getZ(), "minecraft:stone_bricks")).toList();
    }
    @Test void detectsMissingCoreAndWrongHeightButAllowsExplicitStairClearance() {
        var core = FlatRoofCoverageValidator.resolve(new SemanticComponent("ROOF",null,roof(Map.of("roof_type","flat"))),
                new BlockPos(10,2,20)).orElseThrow();
        var result = new ArrayList<>(patches(core,0));
        assertTrue(FlatRoofCoverageValidator.check(List.of(core), result, Set.of()).isEmpty());
        // Punch an interior core cell — perimeter REMOVE/glass is eave fenestration, not a hole.
        BlockPos first = core.core().stream()
                .filter(p -> core.core().contains(p.add(1, 0, 0)) && core.core().contains(p.add(-1, 0, 0))
                        && core.core().contains(p.add(0, 0, 1)) && core.core().contains(p.add(0, 0, -1)))
                .findFirst()
                .orElseThrow();
        result.add(new BlockPatch(BlockPatch.REMOVE, first.getX(),first.getY(),first.getZ(), "minecraft:air"));
        assertEquals(1, FlatRoofCoverageValidator.check(List.of(core),result,Set.of()).orElseThrow().count());
        assertTrue(FlatRoofCoverageValidator.check(List.of(core),result,Set.of(first)).isEmpty());
        assertEquals(81, FlatRoofCoverageValidator.check(List.of(core),patches(core,1),Set.of()).orElseThrow().count());
    }
    @Test void shapeMasksPreserveCircleLShapeAndCourtyardVoids() {
        for (var shape : List.of(Map.<String,Object>of("shape","circle"), Map.<String,Object>of("plan_type","l_shape"),
                Map.<String,Object>of("plan_type","courtyard"))) {
            var params = new HashMap<>(shape); params.put("roof_type","flat");
            var core = FlatRoofCoverageValidator.resolve(new SemanticComponent("ROOF",null,roof(params)), BlockPos.ORIGIN).orElseThrow();
            assertTrue(core.core().size() < 81 && !core.core().isEmpty(), shape.toString());
            assertTrue(FlatRoofCoverageValidator.check(List.of(core),patches(core,0),Set.of()).isEmpty());
        }
        assertTrue(FlatRoofCoverageValidator.resolve(new SemanticComponent("ROOF",null,roof(Map.of("roof_type","gable"))),BlockPos.ORIGIN).isEmpty());
    }
    @Test void independentSecondaryMassHostCoverageIsChecked() {
        var secondary = new SemanticComponent("MASS_SECONDARY", null, new Component(
                "MASS_SECONDARY", "wing", new Vec3i(0, 0, 0), new Dimensions(6, 6, 5), List.of(),
                Map.of("component_id", "wing_a", "anchor_mode", "min_corner")));
        var shortRoof = new Component("ROOF", "roof", new Vec3i(0, 4, 0), new Dimensions(3, 3, 1), List.of(),
                Map.of("roof_type", "flat", "host_id", "wing_a"));
        var fullRoof = new Component("ROOF", "roof", new Vec3i(0, 4, 0), new Dimensions(6, 6, 1), List.of(),
                Map.of("roof_type", "flat", "host_id", "wing_a"));
        var truncated = FlatRoofCoverageValidator.resolve(new SemanticComponent("ROOF", null, shortRoof), BlockPos.ORIGIN).orElseThrow();
        var covering = FlatRoofCoverageValidator.resolve(new SemanticComponent("ROOF", null, fullRoof), BlockPos.ORIGIN).orElseThrow();
        assertTrue(FlatRoofCoverageValidator.checkHost(secondary, List.of(truncated), BlockPos.ORIGIN, patches(truncated, 0), Set.of()).isPresent());
        assertTrue(FlatRoofCoverageValidator.checkHost(secondary, List.of(covering), BlockPos.ORIGIN, patches(covering, 0), Set.of()).isEmpty());
    }

    @Test void openFrameHostCoverageUsesDeckPlaneAbovePostTops() {
        // Open-frame attachment sits at maxY (above posts); enclosed seals at maxY-1.
        var openBody = body(Map.of("open", true, "wall_style", "none"));
        var sunk = new Component("ROOF", "roof", new Vec3i(0, 7, 0), new Dimensions(9, 9, 1), List.of(),
                Map.of("roof_type", "flat", "host_id", "house"));
        var deck = new Component("ROOF", "roof", new Vec3i(0, 8, 0), new Dimensions(9, 9, 1), List.of(),
                Map.of("roof_type", "flat", "host_id", "house"));
        var sunkRoof = FlatRoofCoverageValidator.resolve(new SemanticComponent("ROOF", null, sunk), BlockPos.ORIGIN).orElseThrow();
        var deckRoof = FlatRoofCoverageValidator.resolve(new SemanticComponent("ROOF", null, deck), BlockPos.ORIGIN).orElseThrow();
        assertTrue(FlatRoofCoverageValidator.checkHost(openBody, List.of(sunkRoof), BlockPos.ORIGIN, patches(sunkRoof, 0), Set.of()).isPresent());
        assertTrue(FlatRoofCoverageValidator.checkHost(openBody, List.of(deckRoof), BlockPos.ORIGIN, patches(deckRoof, 0), Set.of()).isEmpty());
    }

    @Test void explicitAnnexHostUsesOwnHeightAndSlotTranslationOnce() {
        var body = new Component("MASS_MAIN","body",new Vec3i(0,0,0),new Dimensions(6,6,8),List.of(),
                Map.of("component_id","house","anchor_mode","min_corner","masses",List.of(Map.of(
                        "offset",Map.of("x",6,"y",0,"z",2),"dimensions",Map.of("width",4,"depth",4,"height",5)))));
        var roof = new Component("ROOF","roof",new Vec3i(6,4,2),new Dimensions(4,4,1),List.of(),
                Map.of("roof_type","flat","host_id","house","host_part_id","house#mass_1"));
        var slot = new Slot("body",new Vec3i(20,10,30),null,null,null,null);
        assertTrue(FlatRoofCoverageValidator.attachmentMismatch(new SemanticComponent("ROOF",slot,roof),List.of(body),Map.of("body",slot),null).isEmpty());
        var wrong = new Slot("roof",new Vec3i(20,11,30),null,null,null,null);
        var mismatch = FlatRoofCoverageValidator.attachmentMismatch(new SemanticComponent("ROOF",wrong,roof),List.of(body),Map.of("body",slot),null).orElseThrow();
        assertEquals(14,mismatch.expectedY()); assertEquals(15,mismatch.actualY());
    }

    @Test
    void unauthorizedPerimeterRemoveIsCoverageHole() {
        // P1-01: perimeter + REMOVE/glass alone must not exempt — needs opening provenance.
        var core = FlatRoofCoverageValidator.resolve(
                new SemanticComponent("ROOF", null, roof(Map.of("roof_type", "flat"))),
                BlockPos.ORIGIN).orElseThrow();
        var result = new ArrayList<>(patches(core, 0));
        BlockPos edge = core.core().stream()
                .filter(p -> !core.core().contains(p.add(1, 0, 0))
                        || !core.core().contains(p.add(-1, 0, 0))
                        || !core.core().contains(p.add(0, 0, 1))
                        || !core.core().contains(p.add(0, 0, -1)))
                .findFirst()
                .orElseThrow();
        result.add(new BlockPatch(BlockPatch.REMOVE, edge.getX(), edge.getY(), edge.getZ(), "minecraft:air"));
        assertTrue(FlatRoofCoverageValidator.check(List.of(core), result, Set.of()).isPresent(),
                "unauthorized perimeter REMOVE must gap");
        assertTrue(FlatRoofCoverageValidator.check(List.of(core), result, Set.of(edge)).isEmpty(),
                "same cell with opening provenance must pass");
    }

    @Test
    void occupiedPerimeterGlassStillCoversDeckWithoutAuthority() {
        // Occupied glass is still a covering cell (GeneratedSurfaceCapture.occupied).
        // P1-01 targets unoccupied REMOVE/air — not solid glazing that seals the deck.
        var core = FlatRoofCoverageValidator.resolve(
                new SemanticComponent("ROOF", null, roof(Map.of("roof_type", "flat"))),
                BlockPos.ORIGIN).orElseThrow();
        var result = new ArrayList<>(patches(core, 0));
        BlockPos edge = core.core().stream()
                .filter(p -> !core.core().contains(p.add(1, 0, 0))
                        || !core.core().contains(p.add(-1, 0, 0))
                        || !core.core().contains(p.add(0, 0, 1))
                        || !core.core().contains(p.add(0, 0, -1)))
                .findFirst()
                .orElseThrow();
        result.removeIf(p -> p.dx() == edge.getX() && p.dy() == edge.getY() && p.dz() == edge.getZ());
        result.add(new BlockPatch(BlockPatch.PLACE, edge.getX(), edge.getY(), edge.getZ(), "minecraft:glass"));
        assertTrue(FlatRoofCoverageValidator.check(List.of(core), result, Set.of()).isEmpty(),
                "occupied glass seals the deck even without opening provenance");
    }

    @Test
    void lShapeInnerEdgeRemoveWithoutAuthorityStillGaps() {
        // Inner re-entrant edges of L/U roofs are also "perimeter" geometrically — provenance required.
        var params = new HashMap<String, Object>();
        params.put("roof_type", "flat");
        params.put("plan_type", "l_shape");
        var core = FlatRoofCoverageValidator.resolve(
                new SemanticComponent("ROOF", null, roof(params)), BlockPos.ORIGIN).orElseThrow();
        assertFalse(core.core().isEmpty());
        BlockPos innerEdge = core.core().stream()
                .filter(p -> {
                    boolean missingNeighbor = !core.core().contains(p.add(1, 0, 0))
                            || !core.core().contains(p.add(-1, 0, 0))
                            || !core.core().contains(p.add(0, 0, 1))
                            || !core.core().contains(p.add(0, 0, -1));
                    boolean hasNeighbor = core.core().contains(p.add(1, 0, 0))
                            || core.core().contains(p.add(-1, 0, 0))
                            || core.core().contains(p.add(0, 0, 1))
                            || core.core().contains(p.add(0, 0, -1));
                    return missingNeighbor && hasNeighbor;
                })
                .findFirst()
                .orElseThrow();
        var result = new ArrayList<>(patches(core, 0));
        result.add(new BlockPatch(BlockPatch.REMOVE, innerEdge.getX(), innerEdge.getY(), innerEdge.getZ(),
                "minecraft:air"));
        assertTrue(FlatRoofCoverageValidator.check(List.of(core), result, Set.of()).isPresent(),
                "L-shape inner-edge REMOVE without opening authority must gap");
    }

    @Test
    void surfaceLedgerAuthorizedOpeningCellsIncludeWindowAndCarvableRoofEdge() {
        var ledger = new SurfaceOwnershipLedger();
        var roof = new Component("ROOF", "s", new Vec3i(0, 7, 0), new Dimensions(5, 5, 1), List.of(),
                Map.of("component_id", "flat_roof", "building_id", "house", "roof_type", "flat",
                        "host_id", "house"));
        var cells = List.of(
                new com.formacraft.common.generation.component.util.GeneratedSurfaceCapture.Cell(
                        new BlockPos(0, 7, 0),
                        com.formacraft.common.generation.component.util.GeneratedSurfaceCapture.Role.ROOF),
                new com.formacraft.common.generation.component.util.GeneratedSurfaceCapture.Cell(
                        new BlockPos(2, 7, 2),
                        com.formacraft.common.generation.component.util.GeneratedSurfaceCapture.Role.ROOF),
                new com.formacraft.common.generation.component.util.GeneratedSurfaceCapture.Cell(
                        new BlockPos(1, 5, 0),
                        com.formacraft.common.generation.component.util.GeneratedSurfaceCapture.Role.WINDOW));
        ledger.accept(roof, List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 7, 0, "minecraft:stone_bricks"),
                new BlockPatch(BlockPatch.PLACE, 2, 7, 2, "minecraft:stone_bricks")),
                cells, BlockPos.ORIGIN);
        Set<BlockPos> auth = ledger.authorizedOpeningCells();
        assertTrue(auth.contains(new BlockPos(1, 5, 0)), "WINDOW role must authorize");
        assertTrue(auth.contains(new BlockPos(0, 7, 0)), "openingCarvable roof edge must authorize");
        assertFalse(auth.contains(new BlockPos(2, 7, 2)), "interior flat roof cell must not authorize");
    }
}
