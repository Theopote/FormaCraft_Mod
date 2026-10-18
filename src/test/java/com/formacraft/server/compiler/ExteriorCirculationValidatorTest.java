package com.formacraft.server.compiler;

import com.formacraft.common.compiler.postprocess.PostProcessContext.BuildingVolume;
import com.formacraft.common.generation.component.util.ComponentFootprintUtil.Bounds;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.assembly.AssemblyCirculationConstraints;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import com.formacraft.common.llm.dto.*;
import static org.junit.jupiter.api.Assertions.*;

class ExteriorCirculationValidatorTest {
    @Test void elevatedTerraceOnlyAllowsFlatApproachContact() {
        var stair = new Component("STRUCTURE","s",new Vec3i(0,0,0),new Dimensions(6,3,6),List.of("stair:straight_single_run"),
                Map.of("exterior",true,"width",3,"from",Map.of("x",0,"y",4,"z",0),"to",Map.of("x",5,"y",9,"z",0)));
        var start = new BlockPos(0,4,0);
        var beyond = new BlockPos(3,4,0);
        for (int height : List.of(1,3)) {
            var terrace = new Component("TERRACE","s",new Vec3i(0,4,0),new Dimensions(8,8,height),List.of(),Map.of());
            var index = new PatchOccupancyIndex();
            index.commit(terrace,List.of(stone(start),stone(beyond)));
            var access = ExteriorCirculationValidator.connectorAccess(stair,List.of(),Map.of(),BlockPos.ORIGIN,index);
            assertEquals(height == 1, access.contains(start));
            assertFalse(access.contains(beyond));
        }
    }
    @Test void publicPavingPermissionStopsAtTheGroundApproach() {
        var court = new Component("COURTYARD","s",new Vec3i(0,0,0),new Dimensions(8,8,1),List.of(),Map.of("component_id","court"));
        var wall = new Component("MASS_MAIN","s",court.relativePosition(),new Dimensions(8,8,8),List.of(),Map.of("component_id","other"));
        var stair = new Component("STRUCTURE","s",court.relativePosition(),new Dimensions(6,2,6),List.of("stair:straight_single_run"),
                Map.of("host_id","hall","exterior",true,"width",2,
                        "from",Map.of("x",0,"y",0,"z",0),"to",Map.of("x",5,"y",5,"z",0)));
        var first = new BlockPos(0,0,0);
        var support = new BlockPos(1,0,0);
        var beyond = new BlockPos(3,0,0);
        var foreign = new BlockPos(0,0,-1);
        var index = new PatchOccupancyIndex();
        index.commit(court,List.of(stone(first),stone(support),stone(beyond)));
        index.commit(wall,List.of(stone(foreign)));
        var access = ExteriorCirculationValidator.connectorAccess(stair,List.of(),Map.of(),BlockPos.ORIGIN,index);
        assertTrue(access.containsAll(List.of(first,support)));
        assertFalse(access.contains(beyond));
        assertFalse(access.contains(foreign));
    }
    @Test void partialPlatformJoinIncludesItsSupportAndHeadroomOnly() {
        var platform = new Component("MASS_SECONDARY","s",new Vec3i(0,5,0),new Dimensions(5,4,1),List.of(),
                Map.of("component_id","platform","host_id","hall","extrude_mode","plate",
                        "plate_role","partial_platform","anchor_mode","min_corner"));
        var hall = new Component("MASS_MAIN","s",new Vec3i(0,0,0),new Dimensions(8,8,12),List.of(),Map.of("component_id","hall"));
        var foreign = new Component("MASS_MAIN","s",hall.relativePosition(),hall.dimensions(),List.of(),Map.of("component_id","other"));
        var stair = new Component("STRUCTURE","s",new Vec3i(2,0,0),new Dimensions(3,6,6),List.of("stair:straight_single_run"),
                Map.of("host_id","hall","building_id","hall","exterior",true,"width",3,"clearHeight",2,
                        "from",Map.of("x",0,"y",0,"z",-5),"to",Map.of("x",0,"y",5,"z",0)));
        var support = new BlockPos(2,4,0);
        var head = new BlockPos(2,7,0);
        var unrelated = new BlockPos(3,5,0);
        var outside = new BlockPos(2,4,-2);
        var index = new PatchOccupancyIndex();
        index.commit(hall,List.of(stone(support),stone(head),stone(outside)));
        index.commit(foreign,List.of(stone(unrelated)));
        var access = ExteriorCirculationValidator.connectorAccess(stair,List.of(platform),Map.of(),BlockPos.ORIGIN,index);
        assertTrue(access.containsAll(List.of(support,head)));
        assertFalse(access.contains(unrelated));
        assertFalse(access.contains(outside));
    }

    @Test void midsectionForeignWallNotPermittedWhenEndpointPlatformIs() {
        // Legal to-neighborhood on own terrace; mid-run foreign mass wall must stay unauthorized.
        var platform = new Component("MASS_SECONDARY","hall",new Vec3i(14,5,2),new Dimensions(4,4,1),List.of(),
                Map.of("component_id","hall_terrace","host_id","hall","extrude_mode","plate",
                        "plate_role","terrace","anchor_mode","min_corner"));
        var hall = new Component("MASS_MAIN","hall",new Vec3i(16,0,0),new Dimensions(10,8,10),List.of(),
                Map.of("component_id","hall","anchor_mode","min_corner"));
        var blocker = new Component("MASS_MAIN","blocker",new Vec3i(4,0,0),new Dimensions(6,8,10),List.of(),
                Map.of("component_id","blocker","anchor_mode","min_corner"));
        var stair = new Component("STRUCTURE","hall",new Vec3i(0,0,3),new Dimensions(3,16,8),List.of("stair:straight_single_run"),
                Map.of("component_id","approach_stair","host_id","hall","building_id","hall","exterior",true,
                        "width",2,"clearHeight",2,"landing_length",1,
                        "from",Map.of("x",0,"y",0,"z",0),"to",Map.of("x",15,"y",5,"z",0)));
        var endpoint = new BlockPos(15,5,3);
        var midForeign = new BlockPos(7,2,3);
        var index = new PatchOccupancyIndex();
        index.commit(platform,List.of(stone(endpoint),stone(new BlockPos(15,4,3))));
        index.commit(blocker,List.of(stone(midForeign)));
        var access = ExteriorCirculationValidator.connectorAccess(
                stair, List.of(hall, platform, blocker), Map.of(), BlockPos.ORIGIN, index);
        assertTrue(access.contains(endpoint), "own terrace at to must be connector-accessible");
        assertFalse(access.contains(midForeign), "mid-run foreign wall must not get connectorAccess");
    }
    @Test void declaredBridgeEnclosureAllowsTerminalHeadroomButNotUnrelatedSurfaces() {
        var bridge = new Component("MASS_SECONDARY","s",new Vec3i(0,4,0),new Dimensions(4,8,1),List.of(),
                Map.of("component_id","bridge","host_id","office","target_host_id","workshop",
                        "extrude_mode","plate","plate_role","bridge","anchor_mode","min_corner"));
        var enclosure = new Component("DECOR_DETAIL","s",new Vec3i(0,4,0),new Dimensions(4,4,3),List.of(),
                Map.of("component_id","enclosure","host_id","office","corridor_enclosure",true,"anchor_mode","min_corner"));
        var stair = new Component("STRUCTURE","s",new Vec3i(2,0,-2),new Dimensions(2,5,5),List.of("stair:straight_single_run"),
                Map.of("host_id","workshop","building_id","workshop","exterior",true,"clearHeight",2,
                        "from",Map.of("x",0,"y",0,"z",0),"to",Map.of("x",0,"y",4,"z",4),"width",2));
        var foreign = new Component("MASS_MAIN","s",new Vec3i(0,0,0),new Dimensions(4,4,8),List.of(),Map.of("component_id","foreign"));
        var head = new BlockPos(2,4,0);
        var other = new BlockPos(1,4,0);
        var index = new PatchOccupancyIndex();
        index.commit(enclosure,List.of(stone(head)));
        index.commit(foreign,List.of(stone(other)));
        var access = ExteriorCirculationValidator.connectorAccess(stair,List.of(bridge,enclosure),Map.of(),BlockPos.ORIGIN,index);
        assertTrue(access.contains(head));
        assertFalse(access.contains(other));
        assertTrue(ExteriorCirculationValidator.connectorAccess(stair,List.of(enclosure),Map.of(),BlockPos.ORIGIN,index).isEmpty());
    }
    @Test void externalLandingPermissionIsLimitedToItsConnectorAndEndpoint() {
        MinecraftRegistryTestBootstrap.initialize();
        var corridor = new com.formacraft.common.llm.dto.Component("MASS_SECONDARY", "s",
                new com.formacraft.common.llm.dto.Vec3i(0,0,0),
                new com.formacraft.common.llm.dto.Dimensions(5,5,6), List.of(),
                Map.of("component_id","corridor", "anchor_mode","min_corner"));
        var unrelated = new com.formacraft.common.llm.dto.Component("MASS_MAIN", "s", corridor.relativePosition(),
                corridor.dimensions(), List.of(), Map.of("component_id","other"));
        var stair = new com.formacraft.common.llm.dto.Component("STRUCTURE", "s", corridor.relativePosition(),
                corridor.dimensions(), List.of("stair:straight_single_run"),
                Map.of("exterior",true, "from",Map.of("x",2,"y",0,"z",0),
                        "to",Map.of("x",2,"y",3,"z",3), "width",3));
        var landing = new BlockPos(2,3,3);
        var other = new BlockPos(1,3,3);
        var outside = new BlockPos(2,3,0);
        var index = new PatchOccupancyIndex();
        index.commit(corridor, List.of(stone(landing), stone(outside)));
        index.commit(unrelated, List.of(stone(other)));
        var access = ExteriorCirculationValidator.connectorAccess(stair, List.of(corridor, unrelated), Map.of(), BlockPos.ORIGIN, index);
        assertTrue(access.contains(landing));
        assertFalse(access.contains(other));
        assertFalse(access.contains(outside));
        assertTrue(index.validate(stair,List.of(new BlockPatch("remove",2,3,3,"minecraft:air")),access).isEmpty());
        assertTrue(index.validate(stair,List.of(new BlockPatch("remove",1,3,3,"minecraft:air")),access).isPresent());
    }
    private final List<BuildingVolume> volumes = List.of(
            new BuildingVolume("house", new Bounds(10, 2, 20, 16, 10, 26), 4),
            new BuildingVolume("house", new Bounds(16, 2, 22, 20, 7, 26), 4));
    @Test void stairMayTrimOptionalEavesButCannotOverwriteAnotherRoofCore() {
        var host=new Component("MASS_MAIN","s",new Vec3i(0,0,0),new Dimensions(5,5,5),List.of(),
                Map.of("component_id","other","anchor_mode","min_corner"));
        var roof=new Component("ROOF","s",new Vec3i(0,4,0),new Dimensions(5,5,2),List.of(),
                Map.of("component_id","roof","host_id","other","anchor_mode","min_corner"));
        var stair=new Component("STRUCTURE","s",new Vec3i(0,0,0),new Dimensions(3,7,5),List.of("stair:straight_single_run"),
                Map.of("component_id","stair","host_id","own","exterior",true,"width",3,
                        "from",Map.of("x",0,"y",0,"z",0),"to",Map.of("x",0,"y",4,"z",6)));
        var core=new BlockPos(0,4,4); var eave=new BlockPos(-1,4,4);
        var index=new PatchOccupancyIndex(); index.commit(roof,List.of(stone(core),stone(eave)));
        var access=ExteriorCirculationValidator.connectorAccess(stair,List.of(host,roof),Map.of(),BlockPos.ORIGIN,index);
        assertTrue(access.contains(eave));
        assertFalse(access.contains(core));
    }
    private BlockPatch stone(BlockPos p) {
        return new BlockPatch(BlockPatch.PLACE, p.getX(), p.getY(), p.getZ(), "minecraft:stone_bricks");
    }
    private List<AssemblyCirculationConstraints.Flight> flight(BlockPos p, boolean clear) {
        return List.of(new AssemblyCirculationConstraints.Flight(clear ? Set.of() : Set.of(p),
                clear ? Set.of(p) : Set.of()));
    }
    @Test void rejectsBothTreadsAndClearanceOverExistingExterior() {
        MinecraftRegistryTestBootstrap.initialize();
        var p = new BlockPos(10, 5, 23);
        for (boolean clear : List.of(false, true)) {
            var collision = ExteriorCirculationValidator.findCollision(List.of(stone(p)), flight(p, clear), volumes);
            assertEquals(p, collision.orElseThrow().position());
            assertEquals(clear ? "clearance" : "tread/support", collision.get().role());
        }
    }
    @Test void allowsDoorwayLastWriteAirAndInteriorFloorOpening() {
        MinecraftRegistryTestBootstrap.initialize();
        var door = new BlockPos(10, 3, 23);
        var removal = new BlockPatch(BlockPatch.REMOVE, 10, 3, 23, "minecraft:air");
        assertTrue(ExteriorCirculationValidator.findCollision(List.of(stone(door), removal), flight(door, true), volumes).isEmpty());
        assertTrue(ExteriorCirculationValidator.findCollision(List.of(), flight(door, false), volumes).isEmpty());
        var inside = new BlockPos(12, 6, 23);
        assertTrue(ExteriorCirculationValidator.findCollision(List.of(stone(inside)), flight(inside, true), volumes).isEmpty());
    }
    @Test void usesHeightAwareUnionAndShiftedFlightCoordinates() {
        MinecraftRegistryTestBootstrap.initialize();
        var shared = new BlockPos(15, 4, 23);
        assertTrue(ExteriorCirculationValidator.findCollision(List.of(stone(shared)), flight(shared, true), volumes).isEmpty());
        var upper = new BlockPos(15, 8, 23);
        var local = new AssemblyCirculationConstraints.Flight(Set.of(new BlockPos(5, 6, 3)), Set.of());
        var shifted = AssemblyCirculationConstraints.shift(local, new BlockPos(10, 2, 20));
        assertEquals(upper, ExteriorCirculationValidator.findCollision(List.of(stone(upper)), List.of(shifted), volumes)
                .orElseThrow().position());
        var voidCell = new BlockPos(18, 4, 20);
        assertTrue(ExteriorCirculationValidator.findCollision(List.of(stone(voidCell)), flight(voidCell, true), volumes).isEmpty());
    }
}
