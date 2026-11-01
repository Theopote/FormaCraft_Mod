package com.formacraft.client.tool;

import com.formacraft.common.component.*;
import com.formacraft.client.ui.panel.DirectionalityMode;

/** Editor type selection. Never changes geometry, sockets or author adaptation permissions. */
public final class ComponentCaptureTypes {
    private ComponentCaptureTypes() {}
    public static boolean ready(ComponentToolState state) {
        return state.captureTypeChosen && state.type != null && ComponentTypes.error(state.type, state.category) == null;
    }
    public static void select(ComponentToolState state, ComponentTypes.Preset preset) {
        if (state.isCaptureSavePending()) return;
        state.beginCapture(); state.category = preset.category(); state.type = preset.type();
        state.captureTypeChosen = true; state.customTypeMode = false;
        applySuggestions(state, preset);
    }
    public static void custom(ComponentToolState state) {
        if (state.isCaptureSavePending()) return;
        state.beginCapture(); state.category = ComponentCategory.GENERIC; state.type = null;
        state.captureTypeChosen = true; state.customTypeMode = true; state.customTypeName = "";
        applySuggestions(state, ComponentTypes.base(ComponentCategory.GENERIC));
    }
    public static void customName(ComponentToolState state, String name) {
        if (state.isCaptureSavePending() || !state.customTypeMode) return;
        state.customTypeName = name;
        try { state.type = ComponentTypes.custom(name); } catch (IllegalArgumentException invalid) { state.type = null; }
    }
    public static void customBase(ComponentToolState state, ComponentCategory category) {
        if (state.isCaptureSavePending() || !state.customTypeMode) return;
        state.category = category; applySuggestions(state, ComponentTypes.base(category));
    }
    public static void resetSuggestions(ComponentToolState state) {
        if (state.isCaptureSavePending() || !ready(state)) return;
        state.captureDraft.host.manualAttachment = false; state.captureDraft.orientation.manualDirectionality = false;
        var preset = ComponentTypes.preset(state.type.key());
        applySuggestions(state, preset == null ? ComponentTypes.base(state.category) : preset);
    }
    private static void applySuggestions(ComponentToolState state, ComponentTypes.Preset preset) {
        var draft = state.captureDraft;
        if (!draft.host.manualAttachment) {
            if (draft.host.attachment != preset.attachment()) draft.host.confirmed = false;
            draft.host.attachment = preset.attachment();
        }
        if (!draft.orientation.manualDirectionality) {
            var mode = preset.insideOutside() ? (preset.bottomTop() ? DirectionalityMode.BOTH : DirectionalityMode.INSIDE_OUTSIDE)
                    : (preset.bottomTop() ? DirectionalityMode.BOTTOM_TOP : DirectionalityMode.NONE);
            if (draft.orientation.mode != mode) draft.orientation.confirmed = false;
            draft.orientation.mode = mode; draft.orientation.hasInteriorExterior = mode.needsInsideOutside();
            draft.orientation.hasBottomTop = mode.needsBottomTop();
        }
        state.syncDraftToState();
    }
}
