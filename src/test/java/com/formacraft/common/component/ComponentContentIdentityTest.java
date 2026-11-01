package com.formacraft.common.component;

import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.component.socket.ComponentSocket;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ComponentContentIdentityTest {
    private ComponentDefinition sample() {
        ComponentDefinition d = new ComponentDefinition();
        d.id = "first"; d.name = "Window";
        d.size = new ComponentDefinition.Size(); d.size.w = 2; d.size.h = 1; d.size.d = 1;
        d.anchor = new ComponentDefinition.Anchor(); d.anchor.facing = "NORTH";
        d.blocks = new ArrayList<>();
        for (int x = 0; x < 2; x++) {
            var b = new ComponentDefinition.BlockEntry(); b.dx = x;
            b.block = "minecraft:oak_stairs[facing=north,half=bottom]"; d.blocks.add(b);
        }
        d.sockets = new ArrayList<>(List.of(ComponentSocket.builder("mount").tags(Set.of("wall", "window")).build()));
        var p = new ComponentDefinition.SocketPlacement(); p.id = "mount";
        d.socketPlacements = new ArrayList<>(List.of(p));
        return d;
    }
    private ComponentDefinition copy(ComponentDefinition d) {
        return JsonUtil.fromJson(JsonUtil.toJson(d), ComponentDefinition.class);
    }
    @Test void namesIdsAndDiscoveryMetadataDoNotChangeIdentity() {
        var a = sample(); var b = copy(a);
        b.id = "another"; b.name = "教堂窗"; b.tags = List.of("gothic"); b.culturalStyle = "GOTHIC";
        b.archetypeRef = "local_alias"; b.category = ComponentCategory.GENERIC;
        assertEquals(ComponentContentIdentity.Match.EXACT_CONTENT, ComponentContentIdentity.compare(a, b));
        assertEquals(ComponentContentIdentity.fingerprints(a), ComponentContentIdentity.fingerprints(b));
    }
    @Test void captureOrderAndPropertyOrderAreIrrelevantAndInputIsUnchanged() {
        var a = sample(); var b = copy(a);
        Collections.reverse(b.blocks);
        b.blocks.forEach(block -> block.block = "minecraft:oak_stairs[half=bottom,facing=north]");
        String before = JsonUtil.toJson(b);
        assertEquals(ComponentContentIdentity.Match.EXACT_CONTENT, ComponentContentIdentity.compare(a, b));
        assertEquals(before, JsonUtil.toJson(b));
    }
    @Test void sameNameDifferentBlocksAreDifferent() {
        var a = sample(); var b = copy(a); b.blocks.getFirst().block = "minecraft:stone";
        assertEquals(ComponentContentIdentity.Match.DIFFERENT, ComponentContentIdentity.compare(a, b));
    }
    @Test void socketPositionAndPublicInterfaceNameAreStructural() {
        var a = sample(); var b = copy(a); b.socketPlacements.getFirst().dx = 1;
        assertEquals(ComponentContentIdentity.Match.DIFFERENT, ComponentContentIdentity.compare(a, b));
        b = copy(a); b.socketPlacements.getFirst().id = "new_mount";
        assertEquals(ComponentContentIdentity.Match.DIFFERENT, ComponentContentIdentity.compare(a, b));
    }
    @Test void placementPermissionsAreSeparateConfigurations() {
        var a = sample(); var b = copy(a); b.placement_rules = new ComponentDefinition.PlacementRules();
        assertSameStructure(a, b);
    }
    @Test void adaptationAndCarveRolesAreNotDiscardedByDeduplication() {
        var a = sample(); var b = copy(a); b.socketPlacements.getFirst().adaptMode = "FIXED";
        assertSameStructure(a, b);
        b = copy(a); b.blocks.getFirst().carveRole = "STRUCTURE";
        assertSameStructure(a, b);
    }
    @Test void provenanceGateIsPreservedAndRotationIsNotMerged() {
        var a = sample(); var b = copy(a); b.sourceOrigin = "CAPTURED";
        assertSameStructure(a, b);
        b = copy(a); b.anchor.facing = "SOUTH";
        assertEquals(ComponentContentIdentity.Match.DIFFERENT, ComponentContentIdentity.compare(a, b));
    }
    @Test void malformedDuplicateStatePropertiesAreRejected() {
        var d = sample(); d.blocks.getFirst().block = "minecraft:oak_stairs[facing=north,facing=south]";
        assertThrows(IllegalArgumentException.class, () -> ComponentContentIdentity.fingerprints(d));
    }
    @Test void socketOrderDoesNotChangeBindings() {
        var a = sample();
        a.sockets.add(ComponentSocket.builder("other").build());
        var p = new ComponentDefinition.SocketPlacement(); p.id = "other"; p.dx = 1;
        a.socketPlacements.add(p);
        var b = copy(a); Collections.reverse(b.sockets); Collections.reverse(b.socketPlacements);
        assertEquals(ComponentContentIdentity.Match.EXACT_CONTENT, ComponentContentIdentity.compare(a, b));
        b.socketPlacements.getFirst().dx = 0;
        assertEquals(ComponentContentIdentity.Match.DIFFERENT, ComponentContentIdentity.compare(a, b));
    }
    private void assertSameStructure(ComponentDefinition a, ComponentDefinition b) {
        assertEquals(ComponentContentIdentity.Match.SAME_STRUCTURE, ComponentContentIdentity.compare(a, b));
        assertEquals(ComponentContentIdentity.fingerprints(a).structure(), ComponentContentIdentity.fingerprints(b).structure());
        assertNotEquals(ComponentContentIdentity.fingerprints(a).configuration(), ComponentContentIdentity.fingerprints(b).configuration());
    }
}
