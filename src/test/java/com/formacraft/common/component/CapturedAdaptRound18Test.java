package com.formacraft.common.component;

import com.formacraft.common.component.group.GroupComponentEntry;
import com.formacraft.common.component.socket.OpeningRegion;
import com.formacraft.common.component.transform.ComponentTransform;
import com.formacraft.common.component.transform.Mirror;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.skeleton.transform.YRotation;
import com.formacraft.server.compiler.PatchConflictValidator;
import com.formacraft.server.compiler.PatchOccupancyIndex;
// Round19: final scan uses occupancy authorization
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CapturedAdaptRound18Test {
    @BeforeAll
    static void bootstrap() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void groupEntryRequiredDefaultsTrue() {
        GroupComponentEntry def = new GroupComponentEntry(
                "a", 0, 0, 0, YRotation.NONE, Mirror.NONE);
        assertTrue(def.isRequired());
        GroupComponentEntry opt = new GroupComponentEntry(
                "b", 0, 0, 0, YRotation.NONE, Mirror.NONE, false);
        assertFalse(opt.isRequired());
        GroupComponentEntry req = new GroupComponentEntry(
                "c", 0, 0, 0, YRotation.NONE, Mirror.NONE, true);
        assertTrue(req.isRequired());
    }

    @Test
    void openingRegionExplicitCellsIgnoreLeftoverBox() {
        OpeningRegion r = OpeningRegion.ofCells(List.of(
                new int[]{0, 0, 0},
                new int[]{1, 0, 0},
                new int[]{0, 1, 0}
        ));
        r.w = 7;
        r.h = 3;
        r.d = 1;
        Set<BlockPos> cells = r.localCells();
        assertEquals(3, cells.size(), "L-shaped cells must not union with leftover AABB");
        assertTrue(r.validate().isEmpty());
    }

    @Test
    void openingRegionBoxFallbackWhenNoCells() {
        OpeningRegion r = OpeningRegion.box(2, 3, 1);
        assertEquals(6, r.localCells().size());
        assertTrue(r.validate().isEmpty());
    }

    @Test
    void groupEmitMatchesStandaloneGeometryResolver() {
        ComponentDefinition def = smallWall("doorish");
        ComponentTransform t = new ComponentTransform(Direction.SOUTH, Mirror.NONE);
        ResolvedComponentGeometry a = ComponentGeometryResolver.emit(
                def, Direction.SOUTH, t, 1, 2, 3, false, null, 42L);
        ResolvedComponentGeometry b = ComponentGeometryResolver.emit(
                def, Direction.SOUTH, t, 1, 2, 3, false, null, 42L);
        assertTrue(a.ok() && b.ok());
        assertEquals(a.patches().size(), b.patches().size());
        for (int i = 0; i < a.patches().size(); i++) {
            BlockPatch p = a.patches().get(i);
            BlockPatch q = b.patches().get(i);
            assertEquals(p.action(), q.action());
            assertEquals(p.dx(), q.dx());
            assertEquals(p.dy(), q.dy());
            assertEquals(p.dz(), q.dz());
            assertEquals(p.targetBlock(), q.targetBlock());
        }
    }

    @Test
    void occupancyRejectsUnauthorizedDecorOverwrite() {
        PatchOccupancyIndex idx = new PatchOccupancyIndex();
        Component mass = new Component("MASS_MAIN", null, new Vec3i(0, 0, 0),
                new Dimensions(4, 4, 4), List.of(), Map.of());
        Component decor = new Component("DECOR_DETAIL", null, new Vec3i(0, 0, 0),
                new Dimensions(1, 1, 1), List.of(), Map.of());
        assertTrue(idx.accept(mass, List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:stone_bricks"))).isEmpty());
        var conflict = idx.accept(decor, List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:oak_planks")));
        assertTrue(conflict.isPresent());
        assertEquals("unauthorized_place_overwrite", conflict.get().reason());
    }

    @Test
    void occupancyAllowsDoorAfterMassAndCarve() {
        PatchOccupancyIndex idx = new PatchOccupancyIndex();
        Component mass = new Component("MASS_MAIN", null, new Vec3i(0, 0, 0),
                new Dimensions(4, 4, 4), List.of(), Map.of());
        Component door = new Component("DOOR", null, new Vec3i(0, 0, 0),
                new Dimensions(1, 2, 1), List.of(), Map.of());
        assertTrue(idx.accept(mass, List.of(
                new BlockPatch(BlockPatch.PLACE, 1, 0, 0, "minecraft:stone_bricks"))).isEmpty());
        assertTrue(idx.accept(door, List.of(
                new BlockPatch(BlockPatch.REMOVE, 1, 0, 0, "minecraft:air"),
                new BlockPatch(BlockPatch.PLACE, 1, 0, 0, "minecraft:oak_door"))).isEmpty());
    }

    @Test
    void finalValidatorUsesOccupancyAuthorization() {
        PatchOccupancyIndex idx = new PatchOccupancyIndex();
        Component mass = new Component("MASS_MAIN", null, new Vec3i(0, 0, 0),
                new Dimensions(4, 4, 4), List.of(), Map.of());
        idx.commit(mass, List.of(new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:stone")));
        var finding = PatchConflictValidator.inspectAuthorized(
                List.of(new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:dirt")), idx);
        assertTrue(finding.isPresent());
        assertEquals("E_PATCH_CONFLICT",
                PatchConflictValidator.toCapabilityGap(finding.get()).get().code());
    }

    private static ComponentDefinition smallWall(String id) {
        var def = new ComponentDefinition();
        def.id = id;
        def.sourceOrigin = ComponentDefinition.ORIGIN_CAPTURED;
        def.anchor = new ComponentDefinition.Anchor();
        def.anchor.facing = "SOUTH";
        def.size = new ComponentDefinition.Size();
        def.size.w = 2;
        def.size.h = 2;
        def.size.d = 1;
        def.blocks = new ArrayList<>();
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                var b = new ComponentDefinition.BlockEntry();
                b.dx = x;
                b.dy = y;
                b.block = "minecraft:stone_bricks";
                def.blocks.add(b);
            }
        }
        return def;
    }
}
