package com.formacraft.common.alignment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Expands {@link BayRhythm} into deterministic bay spans along one axis.
 */
public final class BayGridResolver {

    public record BaySpan(int start, int width, String role) {}

    public record ResolvedAxisGrid(int totalSpan, List<BaySpan> bays) {
        public ResolvedAxisGrid {
            bays = bays == null ? List.of() : List.copyOf(bays);
        }
    }

    private BayGridResolver() {}

    public static ResolvedAxisGrid resolve(BayRhythm rhythm) {
        if (rhythm == null || !rhythm.hasContent()) {
            return new ResolvedAxisGrid(0, List.of());
        }
        List<BaySpan> spans = new ArrayList<>();
        int cursor = 0;
        if (rhythm.sideBays() != null && !rhythm.sideBays().isEmpty()) {
            for (BaySpec bay : rhythm.sideBays()) {
                if (bay == null || bay.width() <= 0) {
                    continue;
                }
                spans.add(new BaySpan(cursor, bay.width(), normalizeRole(bay.role())));
                cursor += bay.width();
            }
            return new ResolvedAxisGrid(cursor, spans);
        }
        int count = rhythm.bayCount() != null ? rhythm.bayCount() : 0;
        int width = rhythm.bayWidth() != null ? rhythm.bayWidth() : 0;
        for (int i = 0; i < count; i++) {
            spans.add(new BaySpan(cursor, width, "regular"));
            cursor += width;
        }
        return new ResolvedAxisGrid(cursor, spans);
    }

    public static boolean isSymmetric(List<BaySpan> bays) {
        if (bays == null || bays.size() < 2) {
            return true;
        }
        int n = bays.size();
        for (int i = 0; i < n / 2; i++) {
            BaySpan left = bays.get(i);
            BaySpan right = bays.get(n - 1 - i);
            if (left.width() != right.width()) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "regular";
        }
        return role.trim().toLowerCase(Locale.ROOT);
    }
}
