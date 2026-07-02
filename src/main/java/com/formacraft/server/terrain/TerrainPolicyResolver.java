package com.formacraft.server.terrain;

import com.formacraft.common.logging.FcaLog;

import java.util.Locale;
import java.util.Map;

/**
 * Resolves TerrainPolicy from spec.extra (data-driven).
 *
 * Supported keys:
 * - extra.terrainPolicy: "FOLLOW" | "ADAPTIVE" | "FLATTEN_AREA" | "TERRAFORM"
 * - extra.terrainSnap: legacy boolean. If false and terrainPolicy not set => FOLLOW
 */
public final class TerrainPolicyResolver {
    private TerrainPolicyResolver() {}

    private static final FcaLog LOG = FcaLog.of("TerrainPolicyResolver");

    public static TerrainPolicy resolve(Map<String, Object> extra) {
        if (extra == null) return TerrainPolicy.ADAPTIVE;

        Object tp = extra.get("terrainPolicy");
        if (tp != null) {
            String s = String.valueOf(tp).trim().toUpperCase(Locale.ROOT);
            if (!s.isEmpty()) {
                try {
                    return TerrainPolicy.valueOf(s);
                } catch (Exception e) {
                    LOG.debug("parse terrainPolicy failed value={}", tp);
                }
                // friendly aliases
                if (s.contains("FOLLOW")) return TerrainPolicy.FOLLOW;
                if (s.contains("ADAPT")) return TerrainPolicy.ADAPTIVE;
                if (s.contains("FLAT")) return TerrainPolicy.FLATTEN_AREA;
                if (s.contains("TERRA")) return TerrainPolicy.TERRAFORM;
            }
        }

        // legacy toggle
        Object snap = extra.get("terrainSnap");
        if (snap != null) {
            boolean b = true;
            if (snap instanceof Boolean bb) b = bb;
            else {
                String s = String.valueOf(snap).trim().toLowerCase(Locale.ROOT);
                if (!s.isEmpty()) b = (s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on"));
            }
            if (!b) return TerrainPolicy.FOLLOW;
        }

        return TerrainPolicy.ADAPTIVE;
    }
}
