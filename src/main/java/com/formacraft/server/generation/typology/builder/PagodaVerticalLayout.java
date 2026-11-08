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

    /**
     * Dense-eaves storeys place both a floor plate (dy=0) and a ceiling plate (dy=h-1).
     * Clear air between them is {@code band - 2}, so a requested 净高 of N needs band N+2.
     */
    private static int storeyBandForClearAir(int clearAir) {
        return Math.max(InteriorSpaceDefaults.minStoreyBand() + 1, clearAir + 2);
    }

    public static int bodyNeedComfortable(int floors) {
        int f = Math.max(1, floors);
        if (f <= 1) {
            return storeyBandForClearAir(InteriorSpaceDefaults.MIN_CLEAR_HEIGHT);
        }
        return storeyBandForClearAir(InteriorSpaceDefaults.DEFAULT_GROUND_CLEAR_HEIGHT)
                + Math.max(0, f - 1) * storeyBandForClearAir(InteriorSpaceDefaults.DEFAULT_UPPER_CLEAR_HEIGHT);
    }

    public static int bodyNeedAbsolute(int floors) {
        return Math.max(1, floors) * storeyBandForClearAir(InteriorSpaceDefaults.MIN_CLEAR_HEIGHT);
    }

    /**
     * Multi-tier eaves (重檐) stack with 1-block vertical offset inside the storey band.
     * They do not insert empty shaft rows between storeys.
     */
    public static int eaveNeed(int floors, int eavesPerLevel) {
        return 0;
    }

    public static int requiredMinHeight(PagodaVerticalSemantics vertical) {
        if (vertical == null) return FINIAL_HEIGHT_FULL + InteriorSpaceDefaults.MIN_CLEAR_HEIGHT;
        return bodyNeedComfortable(vertical.floorCount()) + FINIAL_HEIGHT_FULL;
    }

    public static int absoluteMinHeight(PagodaVerticalSemantics vertical) {
        if (vertical == null) return FINIAL_HEIGHT_COMPACT + InteriorSpaceDefaults.MIN_CLEAR_HEIGHT;
        return bodyNeedAbsolute(vertical.floorCount()) + FINIAL_HEIGHT_COMPACT;
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
        // Eaves share the ceiling Y (horizontal tiers) — body fills everything below the finial.
        int bodyBudget = Math.max(bodyNeedAbsolute(floors), height - finialH);
        // Prefer comfortable clear heights when budget allows (bands include floor+ceiling plates).
        int minClear = storeyBandForClearAir(InteriorSpaceDefaults.MIN_CLEAR_HEIGHT);
        int groundClear = storeyBandForClearAir(InteriorSpaceDefaults.DEFAULT_GROUND_CLEAR_HEIGHT);
        int upperClear = storeyBandForClearAir(InteriorSpaceDefaults.DEFAULT_UPPER_CLEAR_HEIGHT);
        // Allow taller storeys when leftover is large (tall soft-default envelopes).
        int maxPer = Math.max(minClear + 2, (bodyBudget + floors - 1) / floors + 2);

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
        List<EaveBand> eaves = new ArrayList<>(floors * epl);
        int y = 0;
        int half = half0;
        for (int lv = 0; lv < floors; lv++) {
            int h = Math.max(minClear, levelH[lv]);
            storeys.add(new Storey(lv, y, h, half));
            // Outer/main tier (higher index) sits at ceiling; lower tiers step down by 1 each
            // so 重檐 has real vertical separation without leaving an empty shaft between storeys.
            int eaveY = y + h - 1;
            for (int t = 0; t < epl; t++) {
                int bandY = Math.max(y, eaveY - (epl - 1 - t));
                eaves.add(new EaveBand(lv, t, bandY));
            }
            y = y + h;
            half = Math.max(2, half - 1);
        }

        // Finial sits on the top storey roof deck (ceiling of last storey + 1 = y).
        int finialBaseY = y;

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
