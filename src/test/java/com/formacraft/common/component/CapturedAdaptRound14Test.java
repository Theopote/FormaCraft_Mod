package com.formacraft.common.component;

import com.formacraft.common.component.placement.AttachmentType;
import com.formacraft.common.component.placement.ComponentPlacementSpec;
import com.formacraft.common.component.socket.ComponentSocket;
import com.formacraft.common.component.socket.SizeConstraint;
import com.formacraft.common.component.socket.SocketContext;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CapturedAdaptRound14Test {
    @BeforeAll
    static void bootstrap() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void emptyCapturedGeometryRejectedUnlessTagged() {
        ComponentDefinition def = wallHost("empty_host");
        def.blocks = List.of();
        ResolvedComponentGeometry geo = ComponentGeometryResolver.emit(
                def, 0, 0, 0, Direction.SOUTH, Mirror.NONE, false, null, 1L);
        assertEquals(VariantCompileResult.Status.TEMPLATE_MISSING, geo.status());
        assertFalse(geo.ok());

        def.tags = List.of("empty_geometry_ok");
        // still blocks_empty before compile — resolver fails on empty blocks list first
        assertFalse(ComponentGeometryResolver.emit(
                def, 0, 0, 0, Direction.SOUTH, Mirror.NONE, false, null, 1L).ok());
    }

    @Test
    void successEmptyRejectedWithoutTagOnSuccessFactory() {
        ComponentDefinition def = wallHost("x");
        ResolvedComponentGeometry rejected = ResolvedComponentGeometry.success(def, List.of());
        assertEquals(VariantCompileResult.Status.TEMPLATE_MISSING, rejected.status());
        def.tags = List.of("empty_geometry_ok");
        ResolvedComponentGeometry allowed = ResolvedComponentGeometry.success(def, List.of());
        assertEquals(VariantCompileResult.Status.EMPTY_ALLOWED, allowed.status());
        assertTrue(allowed.ok());
    }

    @Test
    void previewResultExposesTemplateMissing() {
        ComponentDefinition def = wallHost("prev");
        def.blocks = List.of();
        ComponentGenerationOutcome o = ComponentPatchPreviewBuilder.fromComponentDefinitionResult(
                def, Direction.SOUTH, Mirror.NONE, false, null, 1L);
        assertEquals(ComponentGenerationOutcome.Status.TEMPLATE_MISSING, o.status());
        assertTrue(o.patches().isEmpty());
        assertFalse(o.ok() && !o.patches().isEmpty());
    }

    @Test
    void mountRequiredDefaultsTrue() {
        Map<String, Object> req = new HashMap<>();
        assertTrue(PlayerComponentExpander.isMountRequired(req));
        req.put("mount_required", false);
        assertFalse(PlayerComponentExpander.isMountRequired(req));
    }

    @Test
    void carveEnvelopeFailureYieldsFailedVolume() {
        ComponentDefinition host = wallHost("host");
        ComponentSocket socket = openingSocket("opening", 2, 3);
        SocketMask oversized = new SocketMask(8, 3, 1);
        ResolvedComponentGeometry hostGeo = ComponentGeometryResolver.emit(
                host, Direction.SOUTH, new ComponentTransform(Direction.SOUTH, Mirror.NONE),
                0, 0, 0, false, null, 1L);
        assertTrue(hostGeo.ok());
        ResolvedCarveVolume vol = PlayerComponentExpander.resolveCarveVolume(
                host, socket, oversized, BlockPos.ORIGIN,
                Direction.SOUTH, new ComponentTransform(Direction.SOUTH, Mirror.NONE),
                0, 0, 0, Direction.SOUTH, hostGeo.patches(), "opening");
        assertFalse(vol.ok());
        assertTrue(vol.toRemovePatches().isEmpty());
    }

    @Test
    void carveHitsProtectedPillarFails() {
        ComponentDefinition host = wallHost("host");
        // Opening origin is (1,0,0); put protected log inside authorized volume.
        for (var b : host.blocks) {
            if (b.dx == 1 && b.dy == 0) {
                b.block = "minecraft:oak_log[axis=y]";
            }
        }
        ComponentSocket socket = openingSocket("opening", 2, 3);
        SocketMask mask = new SocketMask(2, 3, 1);
        ComponentTransform t = new ComponentTransform(Direction.SOUTH, Mirror.NONE);
        ResolvedComponentGeometry hostGeo = ComponentGeometryResolver.emit(
                host, Direction.SOUTH, t, 0, 0, 0, false, null, 1L);
        assertTrue(hostGeo.patches().stream().anyMatch(p ->
                p.targetBlock() != null && p.targetBlock().contains("log")));
        ResolvedCarveVolume vol = PlayerComponentExpander.resolveCarveVolume(
                host, socket, mask, new BlockPos(1, 0, 0),
                Direction.SOUTH, t, 0, 0, 0, Direction.SOUTH, hostGeo.patches(), "opening");
        assertFalse(vol.ok(), "expected protected carve failure, got: " + vol.message());
        assertTrue(vol.message().contains("protected"));
    }

    @Test
    void legalCarveSucceedsWithoutRemovesOnAtomicGateInputs() {
        ComponentDefinition host = wallHost("host");
        ComponentSocket socket = openingSocket("opening", 2, 3);
        SocketMask mask = new SocketMask(2, 3, 1);
        ComponentTransform t = new ComponentTransform(Direction.SOUTH, Mirror.NONE);
        ResolvedComponentGeometry hostGeo = ComponentGeometryResolver.emit(
                host, Direction.SOUTH, t, 0, 0, 0, false, null, 1L);
        ResolvedCarveVolume vol = PlayerComponentExpander.resolveCarveVolume(
                host, socket, mask, new BlockPos(1, 0, 0),
                Direction.SOUTH, t, 0, 0, 0, Direction.SOUTH, hostGeo.patches(), "opening");
        assertTrue(vol.ok(), vol.message());
        assertEquals(6, vol.cells().size());
    }

    @Test
    void hostAndMountEmitAtomicMergeShape() {
        ComponentDefinition host = wallHost("host_wall");
        ComponentDefinition mount = doorMount("door");
        ComponentTransform ht = new ComponentTransform(Direction.SOUTH, Mirror.NONE);
        ComponentTransform mt = new ComponentTransform(Direction.SOUTH, Mirror.NONE);
        ResolvedComponentGeometry hostGeo = ComponentGeometryResolver.emit(
                host, Direction.SOUTH, ht, 0, 0, 0, false, null, 1L);
        ResolvedComponentGeometry mountGeo = ComponentGeometryResolver.emit(
                mount, Direction.SOUTH, mt, 1, 0, 0, false, null, 1L);
        assertTrue(hostGeo.ok());
        assertTrue(mountGeo.ok());
        ComponentSocket socket = openingSocket("opening", 2, 3);
        ResolvedCarveVolume carve = PlayerComponentExpander.resolveCarveVolume(
                host, socket, new SocketMask(2, 3, 1), new BlockPos(1, 0, 0),
                Direction.SOUTH, ht, 0, 0, 0, Direction.SOUTH, hostGeo.patches(), "opening");
        assertTrue(carve.ok());
        List<BlockPatch> merged = new ArrayList<>();
        merged.addAll(hostGeo.patches());
        merged.addAll(carve.toRemovePatches());
        merged.addAll(mountGeo.patches());
        assertTrue(merged.stream().anyMatch(p -> BlockPatch.PLACE.equals(p.action())));
        assertTrue(merged.stream().anyMatch(p -> BlockPatch.REMOVE.equals(p.action())));
        assertTrue(merged.stream().anyMatch(p -> p.targetBlock() != null && p.targetBlock().contains("door")));
    }

    @Test
    void emptyHostEmitFailsWithoutMountPatches() {
        ComponentDefinition host = wallHost("empty");
        host.blocks = List.of();
        ResolvedComponentGeometry hostGeo = ComponentGeometryResolver.emit(
                host, Direction.SOUTH, new ComponentTransform(Direction.SOUTH, Mirror.NONE),
                0, 0, 0, false, null, 1L);
        assertFalse(hostGeo.ok());
        // mount would not be merged in expandMountResult when host fails
        ComponentDefinition mount = doorMount("door");
        ResolvedComponentGeometry mountGeo = ComponentGeometryResolver.emit(
                mount, Direction.SOUTH, new ComponentTransform(Direction.SOUTH, Mirror.NONE),
                0, 0, 0, false, null, 1L);
        assertTrue(mountGeo.ok());
        // Atomic policy: caller must not merge when hostGeo fails
        assertTrue(hostGeo.patches().isEmpty());
    }

    @Test
    void placementSpecIncompatibleIsDetectable() {
        ComponentPlacementSpec window = new ComponentPlacementSpec();
        window.attachment = AttachmentType.WALL_OPENING;
        assertFalse(com.formacraft.common.component.placement.AttachmentRecognizer.isCompatible(
                window, AttachmentType.ROOF_SURFACE));
        assertTrue(com.formacraft.common.component.placement.AttachmentRecognizer.isCompatible(
                window, AttachmentType.WALL_OPENING));
    }

    private static ComponentDefinition wallHost(String id) {
        var def = new ComponentDefinition();
        def.id = id;
        def.sourceOrigin = ComponentDefinition.ORIGIN_CAPTURED;
        def.anchor = new ComponentDefinition.Anchor();
        def.anchor.facing = "SOUTH";
        def.size = new ComponentDefinition.Size();
        def.size.w = 5;
        def.size.h = 3;
        def.size.d = 1;
        def.blocks = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 3; y++) {
                var b = new ComponentDefinition.BlockEntry();
                b.dx = x;
                b.dy = y;
                b.block = "minecraft:stone_bricks";
                def.blocks.add(b);
            }
        }
        def.sockets = List.of(openingSocket("opening", 2, 3));
        var p = new ComponentDefinition.SocketPlacement();
        p.id = "opening";
        p.dx = 1;
        p.dy = 0;
        p.dz = 0;
        p.facing = "SOUTH";
        def.socketPlacements = List.of(p);
        return def;
    }

    private static ComponentDefinition doorMount(String id) {
        var def = new ComponentDefinition();
        def.id = id;
        def.anchor = new ComponentDefinition.Anchor();
        def.anchor.facing = "SOUTH";
        def.size = new ComponentDefinition.Size();
        def.size.w = 2;
        def.size.h = 2;
        def.size.d = 1;
        def.placementSpec = new ComponentPlacementSpec();
        def.placementSpec.attachment = AttachmentType.WALL_OPENING;
        def.blocks = new ArrayList<>();
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                var b = new ComponentDefinition.BlockEntry();
                b.dx = x;
                b.dy = y;
                String half = y == 0 ? "lower" : "upper";
                b.block = "minecraft:oak_door[facing=south,half=" + half + ",hinge=left,open=false]";
                def.blocks.add(b);
            }
        }
        return def;
    }

    private static ComponentSocket openingSocket(String id, int w, int h) {
        return new ComponentSocket(
                id, SocketRole.PROVIDER, null, SocketContext.WALL, null,
                new SizeConstraint(new int[]{w, h}, new int[]{w, h}),
                Set.of("door"));
    }
}
