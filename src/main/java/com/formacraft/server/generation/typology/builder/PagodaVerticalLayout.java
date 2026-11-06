package com.formacraft.server.generation.typology.builder;

import com.formacraft.common.generation.component.util.InteriorSpaceDefaults;
import com.formacraft.common.typology.TypologyGenerationException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Shared vertical budget for dense-eaves pagodas — footprint-agnostic.
 * <p>
 * Shaft storeys, eave bands, and finial share one schedule. Octagon vs square
 * only rasterizes the same Y intervals onto different footprints.
 */
public final class PagodaVerticalLayout {

    public static final int FINIAL_HEIGHT_FULL = 10;
    /** Compact finial still yields ≥3 on-axis solids spanning ≥2 for morphology audit. */
    public static final int FINIAL_HEIGHT_COMPACT = 4;

    private PagodaVerticalLayout() {}

    public record Storey(int index, int y0, int height, int half) {}

    public record EaveBand(int levelIndex, int tier, int y) {}

    public record Result(
            PagodaVerticalSemantics semantics,
            List<Storey> storeys,
            List<EaveBand> eaves,
            int finialBaseY,
            int finialHeight,
            int plannedHeight,
            int requiredMinHeight,
            int absoluteMinHeight,
            boolean compressed
    ) {}

    public static int bodyNeedComfortable(int floors) {
        int f = Math.max(1, floors);
        if (f <= 1) return InteriorSpaceDefaults.MIN_CLEAR_HEIGHT;
        return InteriorSpaceDefaults.DEFAULT_GROUND_CLEAR_HEIGHT
                + Math.max(0, f - 1) * InteriorSpaceDefaults.DEFAULT_UPPER_CLEAR_HEIGHT;
    }

    public static int bodyNeedAbsolute(int floors) {
        return Math.max(1, floors) * InteriorSpaceDefaults.MIN_CLEAR_HEIGHT;
    }

    public static int eaveNeed(int floors, int eavesPerLevel) {
        return Math.max(1, floors) * Math.max(1, eavesPerLevel);
    }

    public static int requiredMinHeight(PagodaVerticalSemantics vertical) {
        if (vertical == null) return FINIAL_HEIGHT_FULL + InteriorSpaceDefaults.MIN_CLEAR_HEIGHT;
        return bodyNeedComfortable(vertical.floorCount())
                + eaveNeed(vertical.floorCount(), vertical.eavesPerLevel())
                + FINIAL_HEIGHT_FULL;
    }

    public static int absoluteMinHeight(PagodaVerticalSemantics vertical) {
        if (vertical == null) return FINIAL_HEIGHT_COMPACT + InteriorSpaceDefaults.MIN_CLEAR_HEIGHT;
        return bodyNeedAbsolute(vertical.floorCount())
                + eaveNeed(vertical.floorCount(), vertical.eavesPerLevel())
                + FINIAL_HEIGHT_COMPACT;
    }

    /**
     * Resolve a vertical schedule that preserves authored floor/eave counts.
     *
     * @param softDefaultHeight preferred total when height was not authored (already ≥ requiredMin)
     * @param heightExplicit    true when the caller authored {@code height}/{@code towerHeight}
     * @param baseHalf          ground-storey half-width (odd footprint → width/2)
     */
    public static Result plan(
            String typologyId,
            PagodaVerticalSemantics vertical,
            int softDefaultHeight,
            boolean heightExplicit,
            int authoredHeight,
            int baseHalf
    ) {
        if (vertical == null) {
            vertical = new PagodaVerticalSemantics(3, 1, 3, false);
        }
        int floors = Math.max(1, vertical.floorCount());
        int epl = Math.max(1, vertical.eavesPerLevel());
        int req = requiredMinHeight(vertical);
        int abs = absoluteMinHeight(vertical);
        int half0 = Math.max(2, baseHalf);

        int height;
        if (!heightExplicit) {
            height = Math.max(req, softDefaultHeight);
        } else {
            height = Math.max(1, authoredHeight);
            if (height < abs) {
                // Authored height is a hard constraint — never silently expand it.
                // Callers that only inferred height must leave heightExplicit=false so the
                // soft path above can grow to requiredMin / softDefault.
                List<String> diags = List.of(
                        "height=" + height,
                        "minimum_feasible_height=" + abs,
                        "required_comfortable_height=" + req,
                        "floor_count=" + floors,
                        "eaves_per_level=" + epl
                );
                throw new TypologyGenerationException(
                        typologyId == null ? "dense_eaves_pagoda" : typologyId,
                        "E_PAGODA_SIZE",
                        "Pagoda height " + height + " cannot keep floor_count=" + floors
                                + " eaves_per_level=" + epl
                                + "; minimum feasible height=" + abs,
                        diags);
            }
        }

        boolean compressed = height < req;
        int finialH = compressed ? FINIAL_HEIGHT_COMPACT : FINIAL_HEIGHT_FULL;
        int eaveRows = eaveNeed(floors, epl);
        // Flush eaves share each storey ceiling (advance = h-1+epl, not h+epl). Compensate so
        // body+eaves fill exactly to height-finialH and the finial sits on the top roof deck.
        int bodyBudget = Math.max(
                bodyNeedAbsolute(floors),
                height - eaveRows - finialH + floors);
        // Prefer comfortable clear heights when budget allows.
        int minClear = InteriorSpaceDefaults.MIN_CLEAR_HEIGHT;
        int groundClear = InteriorSpaceDefaults.DEFAULT_GROUND_CLEAR_HEIGHT;
        int upperClear = InteriorSpaceDefaults.DEFAULT_UPPER_CLEAR_HEIGHT;
        // Allow taller storeys when leftover is large (tall soft-default envelopes).
        int maxPer = Math.max(5, (bodyBudget + floors - 1) / floors + 2);

        int[] levelH = new int[floors];
        if (compressed || bodyBudget < bodyNeedComfortable(floors)) {
            // Absolute path: every storey at least min clear; spend leftover on ground then top.
            int base = minClear;
            int used = base * floors;
            int leftover = Math.max(0, bodyBudget - used);
            Arrays.fill(levelH, base);
            if (floors > 1 && leftover > 0 && levelH[0] < groundClear) {
                int bump = Math.min(leftover, groundClear - levelH[0]);
                levelH[0] += bump;
                leftover -= bump;
            }
            for (int i = 0; leftover > 0; leftover--) {
                int idx = (i == 0 && floors > 1) ? 0 : floors - 1;
                if (levelH[idx] >= maxPer && floors > 1) idx = floors - 1;
                levelH[idx]++;
                i++;
            }
        } else {
            int per = Math.max(minClear, Math.min(maxPer, bodyBudget / floors));
            int leftover = Math.max(0, bodyBudget - per * floors);
            Arrays.fill(levelH, per);
            if (floors > 1 && per < groundClear) {
                int bump = Math.min(leftover, groundClear - per);
                levelH[0] += bump;
                leftover -= bump;
            } else if (floors > 1 && leftover > 0 && per + 1 <= maxPer) {
                levelH[0]++;
                leftover--;
            }
            if (leftover > 0) {
                levelH[floors - 1] += leftover;
            }
        }

        List<Storey> storeys = new ArrayList<>(floors);
        List<EaveBand> eaves = new ArrayList<>(eaveRows);
        int y = 0;
        int half = half0;
        for (int lv = 0; lv < floors; lv++) {
            int h = Math.max(minClear, levelH[lv]);
            storeys.add(new Storey(lv, y, h, half));
            // Flush eaves with the storey ceiling plate so the eave ring sits on the wall top
            // (y+h would leave a one-block visual gap between shaft and eave).
            int eaveY = y + h - 1;
            for (int t = 0; t < epl; t++) {
                eaves.add(new EaveBand(lv, t, eaveY + t));
            }
            y = eaveY + epl;
            half = Math.max(2, half - 1);
        }

        // Finial starts on the top roof deck (last eave top + 1 == y). Never float above eaves.
        int finialBaseY = y;
        int targetFinialBase = Math.max(0, height - finialH);
        if (finialBaseY != targetFinialBase) {
            // Safety: if distribution drifted, prefer connection over floating tip.
            finialBaseY = Math.min(finialBaseY, targetFinialBase);
        }

        return new Result(
                vertical,
                List.copyOf(storeys),
                List.copyOf(eaves),
                finialBaseY,
                finialH,
                height,
                req,
                abs,
                compressed
        );
    }
}
