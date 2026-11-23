package com.formacraft.client.tool;

import com.formacraft.common.component.ComponentCategory;
import com.formacraft.common.component.socket.ComponentSocket;
import com.formacraft.common.component.socket.SocketContext;
import com.formacraft.common.component.socket.SocketShape;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ComponentToolCaptureFixTest {
    @Test
    void makeIdFallsBackForPureChineseNames() {
        String id = ComponentToolJsonBuilder.makeId(ComponentCategory.DOOR, "中式垂花门");
        assertFalse(id.isBlank());
        assertNotEquals("_", id);
        assertTrue(id.startsWith("door_component_"), id);
        assertEquals(id, ComponentToolJsonBuilder.makeId(ComponentCategory.DOOR, "中式垂花门"));
    }

    @Test
    void makeIdStripsLeadingTrailingUnderscores() {
        String id = ComponentToolJsonBuilder.makeId(ComponentCategory.GENERIC, "___");
        assertNotEquals("_", id);
        assertFalse(id.isBlank());
        assertTrue(id.startsWith("generic_component_"), id);
    }

    @Test
    void makeIdKeepsAsciiNames() {
        assertEquals("my_window", ComponentToolJsonBuilder.makeId(ComponentCategory.WINDOW, "My Window"));
    }

    @Test
    void hasValidSelectionAcceptsExplicitBlocksWithoutAabbTool() {
        ComponentCaptureDraft draft = new ComponentCaptureDraft();
        draft.selection.explicit = true;
        draft.selection.blocks = new HashSet<>(Set.of(new BlockPos(1, 2, 3)));
        assertTrue(ComponentToolCaptureSupport.hasValidSelection(draft));
        assertTrue(draft.hasExplicitSelection());
    }

    @Test
    void clearWorldScopedDropsSelectionAndAnchor() {
        ComponentCaptureDraft draft = new ComponentCaptureDraft();
        draft.selection.explicit = true;
        draft.selection.blocks = new HashSet<>(Set.of(new BlockPos(1, 2, 3)));
        draft.anchor.worldPos = new BlockPos(0, 64, 0);
        draft.clearWorldScoped();
        assertFalse(draft.hasExplicitSelection());
        assertNull(draft.anchor.worldPos);
        assertNull(draft.selection.blocks);
    }

    @Test
    void captureVolumeCapIsBounded() {
        assertEquals(64L * 64L * 64L, ComponentToolJsonBuilder.MAX_CAPTURE_VOLUME);
    }

    @Test
    void socketBuilderHonorsPanelContextAndTag() {
        // Mirrors ComponentTool.addSocket context/size/tag mapping (no client toast).
        SocketContext ctx = SocketContext.ROOF;
        int w = 2, h = 2;
        ComponentSocket s = ComponentSocket.builder("socket_1")
                .shape(SocketShape.RECT)
                .context(ctx)
                .size(com.formacraft.common.component.socket.SizeConstraint.rect(w, h, w, h))
                .tag("roof")
                .build();
        assertEquals(SocketContext.ROOF, s.context);
        assertTrue(s.hasTag("roof"));
        assertFalse(s.hasTag("opening"));
    }

    @Test
    void groundSocketUsesHorizontalFootprint() {
        SocketContext ctx = SocketContext.GROUND;
        int w = 3, d = 3;
        var size = switch (ctx) {
            case GROUND -> com.formacraft.common.component.socket.SizeConstraint.rect(w, d, w, d);
            case EDGE -> com.formacraft.common.component.socket.SizeConstraint.line(w, w);
            default -> com.formacraft.common.component.socket.SizeConstraint.rect(w, 1, w, 1);
        };
        ComponentSocket s = ComponentSocket.builder("g1")
                .shape(SocketShape.RECT)
                .context(ctx)
                .size(size)
                .tag("ground")
                .build();
        assertEquals(SocketContext.GROUND, s.context);
        assertArrayEquals(new int[]{3, 3}, s.size.min);
    }
}
