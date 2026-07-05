package com.formacraft.client.ui.panel.capture;

/**
 * 记录各阶段标题行的点击区域，用于折叠/展开。
 */
public final class ComponentCapturePhaseHeaders {
    private static final int PHASE_COUNT = 4;

    private final int[] titleTop = new int[PHASE_COUNT];
    private final int[] titleBottom = new int[PHASE_COUNT];
    private int x;
    private int w;

    public void reset() {
        x = 0;
        w = 0;
        for (int i = 0; i < PHASE_COUNT; i++) {
            titleTop[i] = 0;
            titleBottom[i] = 0;
        }
    }

    public void record(int phaseIndex, int x, int y, int width, int lineHeight) {
        if (phaseIndex < 0 || phaseIndex >= PHASE_COUNT) {
            return;
        }
        this.x = x;
        this.w = width;
        titleTop[phaseIndex] = y;
        titleBottom[phaseIndex] = y + Math.max(lineHeight, 1);
    }

    public Integer hitPhase(double mouseX, double mouseY) {
        if (w <= 0) {
            return null;
        }
        for (int i = 0; i < PHASE_COUNT; i++) {
            if (titleBottom[i] <= titleTop[i]) {
                continue;
            }
            if (mouseX >= x && mouseX <= x + w
                    && mouseY >= titleTop[i] && mouseY <= titleBottom[i]) {
                return i;
            }
        }
        return null;
    }
}
