package com.formacraft.common.component.variant;

/** Half-open slice boundaries shared by the capture HUD and world overlay. */
public record CaptureBandLayout(int length, int start, int end, boolean locked) {
    public static CaptureBandLayout of(CaptureAdaptationSettings settings, int axis, int length) {
        return new CaptureBandLayout(length, settings.fixedEdges[axis], settings.fixedEnd(axis),
                settings.modes[axis] == CaptureAdaptationSettings.Mode.FIXED);
    }
    public int minimumSize() { return locked ? length : start + end + 1; }
    public int middleSize() { return locked ? 0 : length - start - end; }
    public boolean valid() { return length > 0 && (locked || start > 0 && end > 0 && middleSize() > 0); }
    public int middleEnd() { return length - end; }
}
