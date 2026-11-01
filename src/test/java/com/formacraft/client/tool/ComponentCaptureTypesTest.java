package com.formacraft.client.tool;

import com.formacraft.common.component.*;
import com.formacraft.common.component.placement.AttachmentType;
import com.formacraft.client.ui.panel.DirectionalityMode;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ComponentCaptureTypesTest {
    private void select(ComponentToolState state, String key) { ComponentCaptureTypes.select(state, ComponentTypes.preset("formacraft:" + key)); }
    @Test void captureRequiresExplicitTypeChoiceAndGenericDoesNotInheritWindowDefaults() {
        var state = new ComponentToolState(); state.beginCapture(); assertFalse(ComponentCaptureTypes.ready(state));
        select(state, "window"); assertTrue(ComponentCaptureTypes.ready(state));
        assertEquals(AttachmentType.WALL_OPENING, state.captureDraft.host.attachment); assertTrue(state.hasInteriorExterior);
        select(state, "generic"); assertEquals(AttachmentType.NONE, state.attachmentMode);
        assertFalse(state.hasInteriorExterior); assertFalse(state.hasBottomTop);
    }
    @Test void typeChangesPreserveGeometryAnchorAndAuthorAdaptationRules() {
        var state = new ComponentToolState(); select(state, "column");
        state.captureDraft.selection.explicit = true; state.captureDraft.selection.blocks = new HashSet<>(Set.of(BlockPos.ORIGIN));
        state.captureDraft.anchor.worldPos = BlockPos.ORIGIN; state.captureDraft.adaptation.allowMaterialSwap = true;
        state.captureDraft.adaptation.fixedEdges[1] = 3;
        select(state, "ridge"); assertEquals(AttachmentType.ROOF_RIDGE, state.attachmentMode);
        assertEquals(Set.of(BlockPos.ORIGIN), state.captureDraft.selection.blocks); assertEquals(BlockPos.ORIGIN, state.anchorWorld);
        assertTrue(state.adaptation.allowMaterialSwap); assertEquals(3, state.adaptation.fixedEdges[1]);
    }
    @Test void manualMountAndDirectionAreKeptUntilUserResetsSuggestions() {
        var state = new ComponentToolState(); select(state, "door"); var draft = state.captureDraft;
        draft.host.attachment = AttachmentType.ROOF_RIDGE; draft.host.manualAttachment = true;
        draft.orientation.mode = DirectionalityMode.BOTTOM_TOP; draft.orientation.hasInteriorExterior = false;
        draft.orientation.hasBottomTop = true; draft.orientation.manualDirectionality = true;
        select(state, "column"); assertEquals(AttachmentType.ROOF_RIDGE, state.attachmentMode); assertTrue(state.hasBottomTop);
        ComponentCaptureTypes.resetSuggestions(state);
        assertEquals(AttachmentType.FLOOR, state.attachmentMode); assertFalse(state.hasBottomTop);
        assertFalse(draft.host.manualAttachment); assertFalse(draft.orientation.manualDirectionality);
    }
    @Test void customTypeRequiresANameAndAllowsAnIndependentBehaviorBase() {
        var state = new ComponentToolState(); ComponentCaptureTypes.custom(state); assertFalse(ComponentCaptureTypes.ready(state));
        assertEquals(AttachmentType.NONE, state.attachmentMode);
        ComponentCaptureTypes.customName(state, "飞檐挂落"); var type = state.type; assertTrue(ComponentCaptureTypes.ready(state));
        ComponentCaptureTypes.customBase(state, ComponentCategory.RAILING);
        assertEquals(type, state.type); assertEquals(ComponentCategory.RAILING, state.category); assertEquals(AttachmentType.EDGE, state.attachmentMode);
        assertFalse(state.adaptation.allowMaterialSwap);
    }
    @Test void cancellingRestoresClassificationNamesTagsAndManualFlagsWithoutAliasingSnapshots() {
        var state = new ComponentToolState(); state.category = ComponentCategory.DOOR; state.type = ComponentTypes.base(state.category).type();
        state.name = "Before"; state.tags.add("original"); state.manualAttachment = state.manualDirectionality = true;
        state.attachmentMode = AttachmentType.WALL_SURFACE; state.hasBottomTop = true; state.useLibrary = true;
        state.beginCapture(); assertFalse(state.useLibrary);
        ComponentCaptureTypes.custom(state); ComponentCaptureTypes.customName(state, "新类型"); state.name = "Edited"; state.tags.add("changed");
        state.cancelCapture();
        assertEquals(ComponentCategory.DOOR, state.category); assertEquals("Before", state.name); assertEquals(Set.of("original"), state.tags);
        assertTrue(state.manualAttachment); assertTrue(state.manualDirectionality); assertTrue(state.useLibrary); assertFalse(state.captureActive);
    }
    @Test void pendingSaveCommitsFrozenSnapshotOnlyAfterSuccessfulAck() {
        var state = new ComponentToolState(); select(state, "window"); state.name = "Submitted";
        state.captureDraft.selection.explicit = true; state.captureDraft.selection.blocks = new HashSet<>(Set.of(BlockPos.ORIGIN));
        state.beginCaptureSave();
        state.name = "Late edit"; state.type = ComponentTypes.custom("Late type"); state.captureDraft.selection.blocks.add(new BlockPos(1, 0, 0));
        state.commitCapture(); assertTrue(state.captureActive); assertTrue(state.isCaptureSavePending());
        state.completeCaptureSave(true);
        assertFalse(state.captureActive); assertFalse(state.isCaptureSavePending()); assertEquals("Submitted", state.name);
        assertEquals("formacraft:window", state.type.key()); assertEquals(Set.of(BlockPos.ORIGIN), state.captureDraft.selection.blocks);
        state.captureDraft.selection.blocks.add(new BlockPos(2, 0, 0)); assertEquals(Set.of(BlockPos.ORIGIN), state.confirmedDraft.selection.blocks);
    }
    @Test void failedSaveKeepsEditableDraftAndMayBeRetried() {
        var state = new ComponentToolState(); select(state, "chimney"); state.name = "Chimney";
        state.beginCaptureSave(); state.cancelCapture(); assertTrue(state.captureActive);
        assertThrows(IllegalStateException.class, state::beginCaptureSave);
        state.completeCaptureSave(false); assertTrue(state.captureActive); assertTrue(ComponentCaptureTypes.ready(state));
        state.beginCaptureSave(); state.completeCaptureSave(true); assertFalse(state.captureActive);
    }
    @Test void eachNewCaptureRequiresChoiceAndDoesNotCarryPreviousManualOverrides() {
        var state = new ComponentToolState(); select(state, "door"); state.captureDraft.host.manualAttachment = true;
        state.captureDraft.host.attachment = AttachmentType.WALL_OPENING; state.beginCaptureSave(); state.completeCaptureSave(true);
        state.beginCapture(); assertFalse(ComponentCaptureTypes.ready(state)); select(state, "generic");
        assertEquals(AttachmentType.NONE, state.attachmentMode); assertFalse(state.manualAttachment);
    }
}
