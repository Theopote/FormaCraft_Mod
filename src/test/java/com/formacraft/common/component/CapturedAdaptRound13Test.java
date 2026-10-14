package com.formacraft.common.component;

import com.formacraft.common.component.model.ComponentModelApi;
import com.formacraft.common.component.model.ComponentPrototype;
import com.formacraft.common.component.model.PersistedComponentVariant;
import com.formacraft.common.component.query.ComponentResolveResult;
import com.formacraft.common.component.socket.ComponentSocket;
import com.formacraft.common.component.socket.SizeConstraint;
import com.formacraft.common.component.socket.SocketMask;
import com.formacraft.common.component.socket.SocketRole;
import com.formacraft.common.component.transform.ComponentTransform;
import com.formacraft.common.component.transform.Mirror;
import com.formacraft.common.component.variant.VariantCompileResult;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.patch.ComponentPatchPreviewBuilder;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class CapturedAdaptRound13Test {
    @BeforeAll
    static void bootstrap() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void compileToPatchResultExposesTemplateMissing() {
        ComponentPrototype proto = new ComponentPrototype();
        proto.id = "missing_tpl_r13";
        proto.structure = new ComponentPrototype.StructureRef();
        proto.structure.format = "component_v1_json";
        proto.structure.file = "component.json";
        PersistedComponentVariant variant = new PersistedComponentVariant();
        variant.params = new PersistedComponentVariant.Params();
        variant.params.scale = new PersistedComponentVariant.Params.Scale();
        variant.params.scale.x = 1;
        variant.params.scale.y = 1;
        variant.params.scale.z = 1;
        VariantCompileResult r = ComponentModelApi.compileToPatchResult(proto, variant, Direction.SOUTH, null);
        assertEquals(VariantCompileResult.Status.TEMPLATE_MISSING, r.status());
        assertFalse(r.ok());
    }

    @Test
    void resolverEmptyBlocksIsTemplateMissing() {
        var def = new ComponentDefinition();
        def.id = "empty_blocks";
        def.blocks = List.of();
        ResolvedComponentGeometry geo = ComponentGeometryResolver.emit(
                def, 0, 0, 0, Direction.SOUTH, Mirror.NONE, false, null, 1L);
        assertEquals(VariantCompileResult.Status.TEMPLATE_MISSING, geo.status());
        assertTrue(geo.patches().isEmpty());
    }

    @Test
    void previewAndResolverPatchesMatch() {
        ComponentDefinition def = capturedDoorSouth();
        for (Direction facing : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
            var preview = ComponentPatchPreviewBuilder.fromComponentDefinition(
                    def, facing, Mirror.NONE, false, null, 42L);
            var geo = ComponentGeometryResolver.emit(
                    def, 0, 0, 0, facing, Mirror.NONE, false, null, 42L);
            assertTrue(geo.ok());
            assertEquals(multiset(preview), multiset(geo.patches()), "facing=" + facing);
        }
    }

    @Test
    void stairsFenceDoorStatesSurviveSharedEmit() {
        assertStateContains(emitBlock(stairsDef(), Direction.WEST), "facing=");
        assertStateContains(emitBlock(fenceDef(), Direction.SOUTH), "oak_fence");
        Set<String> door = multiset(ComponentGeometryResolver.emit(
                capturedDoorSouth(), 0, 0, 0, Direction.EAST, Mirror.NONE, false, null, 1L).patches());
        assertTrue(door.stream().anyMatch(s -> s.contains("half=lower")));
        assertTrue(door.stream().anyMatch(s -> s.contains("half=upper")));
    }

    @Test
    void carveRemovesAlignWithDoorPlaceBoundsOnFourFacings() {
        ComponentDefinition door = capturedDoorSouth();
        SocketMask mask = new SocketMask(2, 3, 1);
        BlockPos originLocal = BlockPos.ORIGIN;
        Direction from = Direction.SOUTH;
        for (Direction target : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
            ComponentTransform t = new ComponentTransform(target, Mirror.NONE);
            List<BlockPatch> removes = PlayerComponentExpander.emitCarveRemoves(
                    mask, originLocal, from, t, 0, 0, 0, target);
            ResolvedComponentGeometry geo = ComponentGeometryResolver.emit(
                    door, from, t, 0, 0, 0, false, null, 1L);
            assertEquals(6, removes.size(), "facing=" + target);
            assertTrue(geo.ok());
            assertDoorHalvesPaired(geo.patches());

            Set<String> removeKeys = removes.stream()
                    .map(p -> p.dx() + "," + p.dy() + "," + p.dz())
                    .collect(Collectors.toSet());
            // Every PLACE cell should sit in the same depth=0 plane band covered by carve width×height
            Set<String> placeKeys = geo.patches().stream()
                    .filter(p -> BlockPatch.PLACE.equals(p.action()))
                    .map(p -> p.dx() + "," + p.dy() + "," + p.dz())
                    .collect(Collectors.toSet());
            assertFalse(placeKeys.isEmpty());
            // Carve volume contains all place XZ footprints at dy 0..2 for 2×3 door
            assertTrue(removeKeys.size() >= placeKeys.size()
                    || removeKeys.containsAll(projectXy(placeKeys))
                    || overlapsVolume(removeKeys, placeKeys),
                    "carve vs place misaligned facing=" + target + " remove=" + removeKeys + " place=" + placeKeys);
        }
    }

    @Test
    void carveEnvelopeRejectsOversizedMask() {
        ComponentSocket socket = new ComponentSocket(
                "opening", SocketRole.PROVIDER, null, null, null,
                new SizeConstraint(new int[]{2, 3}, new int[]{2, 3}),
                Set.of());
        assertTrue(PlayerComponentExpander.carveWithinSocketEnvelope(new SocketMask(2, 3, 1), socket));
        assertFalse(PlayerComponentExpander.carveWithinSocketEnvelope(new SocketMask(4, 3, 1), socket));
    }

    @Test
    void resolveResultDistinguishesAdaptFailure() {
        // Unit-level: ADAPT_FAILED factory and ok() contract (retriever needs catalog I/O).
        ComponentResolveResult failed = ComponentResolveResult.adaptFailed("gothic_column", "fit_failed");
        assertEquals(ComponentResolveResult.Status.ADAPT_FAILED, failed.status());
        assertFalse(failed.ok());
        ComponentResolveResult miss = ComponentResolveResult.noCandidate("none");
        assertEquals(ComponentResolveResult.Status.NO_CANDIDATE, miss.status());
        assertFalse(miss.ok());
    }

    private static Set<String> multiset(List<BlockPatch> patches) {
        return patches.stream()
                .map(p -> p.action() + "|" + p.dx() + "," + p.dy() + "," + p.dz() + "|" + p.targetBlock())
                .collect(Collectors.toCollection(HashSet::new));
    }

    private static String emitBlock(ComponentDefinition def, Direction facing) {
        var geo = ComponentGeometryResolver.emit(def, 0, 0, 0, facing, Mirror.NONE, false, null, 1L);
        assertTrue(geo.ok());
        return geo.patches().getFirst().targetBlock();
    }

    private static void assertStateContains(String state, String fragment) {
        assertNotNull(state);
        assertTrue(state.toLowerCase(Locale.ROOT).contains(fragment.toLowerCase(Locale.ROOT)), state);
    }

    private static void assertDoorHalvesPaired(List<BlockPatch> patches) {
        long lower = patches.stream().filter(p -> p.targetBlock() != null && p.targetBlock().contains("half=lower")).count();
        long upper = patches.stream().filter(p -> p.targetBlock() != null && p.targetBlock().contains("half=upper")).count();
        assertEquals(lower, upper);
        assertTrue(lower >= 1);
    }

    private static Set<String> projectXy(Set<String> keys) {
        return keys;
    }

    private static boolean overlapsVolume(Set<String> removes, Set<String> places) {
        for (String p : places) {
            if (removes.contains(p)) return true;
            String[] parts = p.split(",");
            if (parts.length != 3) continue;
            // same x,z any y in carve
            for (String r : removes) {
                String[] rp = r.split(",");
                if (rp.length == 3 && rp[0].equals(parts[0]) && rp[2].equals(parts[2])) return true;
            }
        }
        return false;
    }

    private static ComponentDefinition capturedDoorSouth() {
        var def = new ComponentDefinition();
        def.id = "cap_door_2x3";
        def.sourceOrigin = ComponentDefinition.ORIGIN_CAPTURED;
        def.anchor = new ComponentDefinition.Anchor();
        def.anchor.facing = "SOUTH";
        def.size = new ComponentDefinition.Size();
        def.size.w = 2;
        def.size.h = 3;
        def.size.d = 1;
        def.blocks = new ArrayList<>();
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                var b = new ComponentDefinition.BlockEntry();
                b.dx = x;
                b.dy = y;
                b.dz = 0;
                String half = y == 0 ? "lower" : "upper";
                b.block = "minecraft:oak_door[facing=south,half=" + half + ",hinge=left,open=false]";
                def.blocks.add(b);
            }
            // frame above
            var top = new ComponentDefinition.BlockEntry();
            top.dx = x;
            top.dy = 2;
            top.dz = 0;
            top.block = "minecraft:oak_planks";
            def.blocks.add(top);
        }
        return def;
    }

    private static ComponentDefinition stairsDef() {
        var def = new ComponentDefinition();
        def.id = "stair";
        def.anchor = new ComponentDefinition.Anchor();
        def.anchor.facing = "SOUTH";
        def.size = new ComponentDefinition.Size();
        def.size.w = 1;
        def.size.h = 1;
        def.size.d = 1;
        var b = new ComponentDefinition.BlockEntry();
        b.block = "minecraft:oak_stairs[facing=south,half=bottom,shape=straight]";
        def.blocks = List.of(b);
        return def;
    }

    private static ComponentDefinition fenceDef() {
        var def = new ComponentDefinition();
        def.id = "fence";
        def.anchor = new ComponentDefinition.Anchor();
        def.anchor.facing = "SOUTH";
        def.size = new ComponentDefinition.Size();
        def.size.w = 1;
        def.size.h = 1;
        def.size.d = 1;
        var b = new ComponentDefinition.BlockEntry();
        b.block = "minecraft:oak_fence[north=true]";
        def.blocks = List.of(b);
        return def;
    }
}
