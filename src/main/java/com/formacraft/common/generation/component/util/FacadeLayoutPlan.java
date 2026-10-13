package com.formacraft.common.generation.component.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Whole-wall facade bay solver: entrance reserve + window starts in one pass.
 * Prefer reallocating windows across sides before declaring {@code E_WINDOW_COUNT_UNFIT}.
 */
public final class FacadeLayoutPlan {
    private FacadeLayoutPlan() {}

    public record Result(int entranceStart, int entranceEnd, List<Integer> windowStarts) {
        public Result {
            windowStarts = windowStarts == null ? List.of() : List.copyOf(windowStarts);
        }

        public int windowCount() {
            return windowStarts.size();
        }
    }

    /**
     * @param span             wall axis length (cells)
     * @param windowWidth      each window width
     * @param requestedCount   total windows on the wall, or -1 for auto densify
     * @param countPerSide     if &gt;= 0, each side must fit exactly this many (strict)
     * @param reserveEntrance  reserve a centered door bay
     * @param reserveWidth     door bay width when reserved
     * @param edgeMargin       cells kept solid at each wall end (usually 1)
     * @param explicitCount    when true, under-capacity throws
     */
    public static Result solve(int span, int windowWidth, int requestedCount, int countPerSide,
                               boolean reserveEntrance, int reserveWidth, int edgeMargin,
                               boolean explicitCount) {
        int edge = Math.max(0, edgeMargin);
        int size = Math.max(1, windowWidth);
        int from = edge;
        int to = Math.max(from, span - edge);
        if (!reserveEntrance || reserveWidth <= 0 || span < reserveWidth + 2 * edge) {
            var starts = new ArrayList<Integer>();
            placeInInterval(starts, from, to, size, requestedCount, explicitCount);
            return new Result(-1, -1, starts);
        }
        int reserve = Math.max(1, Math.min(reserveWidth, to - from));
        int middleStart = from + (to - from - reserve) / 2;
        int middleEnd = middleStart + reserve;
        int leftFrom = from;
        int leftTo = middleStart;
        int rightFrom = middleEnd;
        int rightTo = to;

        if (countPerSide >= 0) {
            var starts = new ArrayList<Integer>();
            placeInInterval(starts, leftFrom, leftTo, size, countPerSide, explicitCount);
            placeInInterval(starts, rightFrom, rightTo, size, countPerSide, explicitCount);
            return new Result(middleStart, middleEnd, starts);
        }

        if (requestedCount < 0) {
            var starts = new ArrayList<Integer>();
            placeInInterval(starts, leftFrom, leftTo, size, -1, false);
            placeInInterval(starts, rightFrom, rightTo, size, -1, false);
            return new Result(middleStart, middleEnd, starts);
        }

        int leftCap = capacity(leftFrom, leftTo, size);
        int rightCap = capacity(rightFrom, rightTo, size);
        int totalCap = leftCap + rightCap;
        if (requestedCount > totalCap) {
            if (explicitCount) {
                throw new IllegalArgumentException(
                        "E_WINDOW_COUNT_UNFIT: requested=" + requestedCount
                                + " fitted=" + totalCap
                                + " span=" + span
                                + " windowWidth=" + size
                                + " entrance=[" + middleStart + ", " + middleEnd + ")");
            }
            requestedCount = totalCap;
        }

        int leftWant = requestedCount / 2 + requestedCount % 2;
        int rightWant = requestedCount / 2;
        // Rebalance when one side cannot hold its half.
        if (leftWant > leftCap) {
            int overflow = leftWant - leftCap;
            leftWant = leftCap;
            rightWant = Math.min(rightCap, rightWant + overflow);
        }
        if (rightWant > rightCap) {
            int overflow = rightWant - rightCap;
            rightWant = rightCap;
            leftWant = Math.min(leftCap, leftWant + overflow);
        }
        int fitted = leftWant + rightWant;
        if (fitted < requestedCount && explicitCount) {
            throw new IllegalArgumentException(
                    "E_WINDOW_COUNT_UNFIT: requested=" + requestedCount
                            + " fitted=" + fitted
                            + " span=" + span
                            + " windowWidth=" + size
                            + " entrance=[" + middleStart + ", " + middleEnd + ")");
        }

        var starts = new ArrayList<Integer>();
        placeInInterval(starts, leftFrom, leftTo, size, leftWant, false);
        placeInInterval(starts, rightFrom, rightTo, size, rightWant, false);
        return new Result(middleStart, middleEnd, starts);
    }

    static int capacity(int from, int to, int size) {
        int available = Math.max(0, to - from);
        if (available < size) return 0;
        return (available + 1) / (size + 1);
    }

    static void placeInInterval(List<Integer> starts, int from, int to, int size, int requested,
                                boolean explicitCount) {
        int available = Math.max(0, to - from);
        if (requested >= 0) {
            int cap = capacity(from, to, size);
            int count = Math.min(requested, cap);
            if (count < requested) {
                if (explicitCount) {
                    throw new IllegalArgumentException(
                            "E_WINDOW_COUNT_UNFIT: requested=" + requested + " fitted=" + count
                                    + " interval=[" + from + ", " + to + ") windowWidth=" + size);
                }
                com.formacraft.FormacraftMod.LOGGER.warn(
                        "[WindowCountLayout] requested={} fitted={} interval=[{}, {}) windowWidth={}",
                        requested, count, from, to, size);
            }
            if (count == 1) starts.add(from + (available - size) / 2);
            else if (count > 1) {
                for (int i = 0; i < count; i++) {
                    starts.add(from + (int) ((long) i * (available - size) / (count - 1)));
                }
            }
            return;
        }
        int count = (available + 2) / (size + 2);
        if (count < 1) return;
        int occupied = count * size + (count - 1) * 2;
        int first = from + (available - occupied) / 2;
        for (int i = 0; i < count; i++) starts.add(first + i * (size + 2));
    }
}
