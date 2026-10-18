package com.formacraft.common.patch;

import net.minecraft.util.math.BlockPos;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Shared confirmed-preview identity: context + frozen patches + generator version.
 * Used by PreviewTicket and typology PreviewPlans — not palace-specific.
 */
public final class ConfirmedPlanIdentity {

    public static final int HASH_VERSION = 1;

    /** Bump when patch encoding or plan composition changes. */
    public static final String GENERATOR_VERSION = "formacraft-plan-v1";

    private ConfirmedPlanIdentity() {}

    /** Deterministic hash of the exact block operations the user confirmed. */
    public static String patchHash(List<BlockPatch> patches) {
        List<BlockPatch> sorted = sortedPatches(patches);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            ByteBuffer hdr = ByteBuffer.allocate(8);
            hdr.putInt(HASH_VERSION);
            hdr.putInt(sorted.size());
            md.update(hdr.array());
            ByteBuffer row = ByteBuffer.allocate(16);
            for (BlockPatch p : sorted) {
                String action = safe(p.action()).toLowerCase(Locale.ROOT);
                String target = safe(p.targetBlock()).toLowerCase(Locale.ROOT);
                md.update(action.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                row.clear();
                row.putInt(p.dx()).putInt(p.dy()).putInt(p.dz());
                md.update(row.array(), 0, 12);
                md.update(target.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            return HexFormat.of().formatHex(md.digest()).substring(0, 16);
        } catch (Exception e) {
            return "patch-error";
        }
    }

    /**
     * Plan identity = generator version + contextHash + patchHash.
     * Same inputs and same final patches ⇒ same planId.
     */
    public static String planId(String generatorVersion, String contextHash, String patchHash) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            ByteBuffer hdr = ByteBuffer.allocate(4);
            hdr.putInt(HASH_VERSION);
            md.update(hdr.array());
            md.update(safe(generatorVersion).getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(safe(contextHash).getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(safe(patchHash).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest()).substring(0, 16);
        } catch (Exception e) {
            return "plan-error";
        }
    }

    public static String planId(String contextHash, List<BlockPatch> patches) {
        return planId(GENERATOR_VERSION, contextHash, patchHash(patches));
    }

    /**
     * Recursive canonical serialization for nested params (Map / List / scalars).
     * Object keys sorted; array order preserved; no String.valueOf on nested maps.
     */
    public static String canonicalizeValue(Object value) {
        StringBuilder sb = new StringBuilder();
        appendCanonical(sb, value);
        return sb.toString();
    }

    public static String hashCanonicalParams(Map<String, ?> params) {
        return hashUtf8(canonicalizeValue(params));
    }

    public static String hashUtf8(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(safe(s).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest()).substring(0, 16);
        } catch (Exception e) {
            return "hash-error";
        }
    }

    private static List<BlockPatch> sortedPatches(List<BlockPatch> patches) {
        List<BlockPatch> out = new ArrayList<>();
        if (patches != null) {
            for (BlockPatch p : patches) {
                if (p != null) out.add(p);
            }
        }
        out.sort(Comparator
                .comparingInt(BlockPatch::dx)
                .thenComparingInt(BlockPatch::dy)
                .thenComparingInt(BlockPatch::dz)
                .thenComparing(p -> safe(p.action()).toLowerCase(Locale.ROOT))
                .thenComparing(p -> safe(p.targetBlock()).toLowerCase(Locale.ROOT)));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void appendCanonical(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        if (value instanceof Map<?, ?> map) {
            sb.append('{');
            List<String> keys = new ArrayList<>();
            for (Object k : map.keySet()) {
                if (k != null) keys.add(String.valueOf(k));
            }
            keys.sort(String.CASE_INSENSITIVE_ORDER);
            boolean first = true;
            for (String k : keys) {
                Object v = null;
                for (var e : map.entrySet()) {
                    if (e.getKey() != null && k.equalsIgnoreCase(String.valueOf(e.getKey()))) {
                        v = e.getValue();
                        break;
                    }
                }
                String kl = k.trim().toLowerCase(Locale.ROOT);
                if (kl.isEmpty()
                        || kl.equals("terrainfingerprint")
                        || kl.equals("terraincontenthash")
                        || kl.equals("buildcontexthash")
                        || kl.equals("forcefailcirculation")
                        || kl.equals("schematicflatterrain")
                        || kl.equals("terrainmode")) {
                    continue;
                }
                if (!first) sb.append(',');
                first = false;
                sb.append(kl).append(':');
                appendCanonical(sb, v);
            }
            sb.append('}');
            return;
        }
        if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                appendCanonical(sb, list.get(i));
            }
            sb.append(']');
            return;
        }
        if (value instanceof Object[] arr) {
            sb.append('[');
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(',');
                appendCanonical(sb, arr[i]);
            }
            sb.append(']');
            return;
        }
        if (value instanceof Boolean b) {
            sb.append(b ? "true" : "false");
            return;
        }
        if (value instanceof Number n) {
            // Normalize integers vs floating forms: 3 and 3.0 → same when integral.
            double d = n.doubleValue();
            if (Double.isFinite(d) && d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append((long) d);
            } else {
                sb.append(n.toString().toLowerCase(Locale.ROOT));
            }
            return;
        }
        sb.append(String.valueOf(value).trim().toLowerCase(Locale.ROOT));
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /** Compact fingerprint of preview-time world cells for Apply conflict checks. */
    public static String worldStateFingerprint(Map<BlockPos, String> expectedStates) {
        Objects.requireNonNull(expectedStates, "expectedStates");
        List<Map.Entry<BlockPos, String>> entries = new ArrayList<>(expectedStates.entrySet());
        entries.sort(Comparator
                .comparingInt((Map.Entry<BlockPos, String> e) -> e.getKey().getX())
                .thenComparingInt(e -> e.getKey().getY())
                .thenComparingInt(e -> e.getKey().getZ()));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            ByteBuffer pos = ByteBuffer.allocate(12);
            for (var e : entries) {
                BlockPos p = e.getKey();
                pos.clear();
                pos.putInt(p.getX()).putInt(p.getY()).putInt(p.getZ());
                md.update(pos.array());
                md.update(safe(e.getValue()).toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            return HexFormat.of().formatHex(md.digest()).substring(0, 16);
        } catch (Exception ex) {
            return "world-error";
        }
    }
}
