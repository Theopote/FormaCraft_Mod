package com.formacraft.server.phase2;

import java.util.List;
import java.util.Map;

/**
 * phase2/samples/&lt;id&gt;/sample.json
 */
public record Phase2SampleManifest(
        String id,
        String labelZh,
        String labelEn,
        String prompt,
        List<String> focus,
        String planFile,
        Checks checks,
        String goldenPreviewDigest
) {
    public record Checks(
            int minSolidBlocks,
            boolean requireRoofLike,
            boolean requireOpeningLike,
            boolean requireStairLike,
            boolean requireGlassLike,
            int minDistinctBlockIds,
            int minHeightSpan,
            int minOpeningCells,
            int minFloorLayers
    ) {
        public static Checks fromMap(Map<?, ?> raw) {
            if (raw == null) {
                return new Checks(1, false, false, false, false, 1, 1, 0, 0);
            }
            return new Checks(
                    intVal(raw.get("minSolidBlocks"), 1),
                    boolVal(raw.get("requireRoofLike"), false),
                    boolVal(raw.get("requireOpeningLike"), false),
                    boolVal(raw.get("requireStairLike"), false),
                    boolVal(raw.get("requireGlassLike"), false),
                    intVal(raw.get("minDistinctBlockIds"), 1),
                    intVal(raw.get("minHeightSpan"), 1),
                    intVal(raw.get("minOpeningCells"), 0),
                    intVal(raw.get("minFloorLayers"), 0)
            );
        }

        private static int intVal(Object o, int d) {
            if (o instanceof Number n) return n.intValue();
            if (o != null) {
                try {
                    return Integer.parseInt(String.valueOf(o));
                } catch (NumberFormatException ignored) {
                }
            }
            return d;
        }

        private static boolean boolVal(Object o, boolean d) {
            if (o instanceof Boolean b) return b;
            if (o != null) return Boolean.parseBoolean(String.valueOf(o));
            return d;
        }
    }
}
