package com.formacraft.server.generation.typology.builder;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Full build-plan identity: world/dimension, origin, facing, normalized params,
 * terrain content hash, and material-affecting config.
 * Distinct from {@link PalaceTerrainSnapshot#contentHash()}.
 */
public final class PalaceBuildContextFingerprint {

    public static final int HASH_VERSION = 1;

    private final String worldKey;
    private final BlockPos origin;
    private final Direction facing;
    private final String terrainContentHash;
    private final String paramsHash;
    private final String materialsHash;
    private final String contextHash;

    private PalaceBuildContextFingerprint(
            String worldKey,
            BlockPos origin,
            Direction facing,
            String terrainContentHash,
            String paramsHash,
            String materialsHash,
            String contextHash
    ) {
        this.worldKey = worldKey == null ? "unknown" : worldKey;
        this.origin = origin == null ? BlockPos.ORIGIN : origin.toImmutable();
        this.facing = facing == null ? Direction.SOUTH : facing;
        this.terrainContentHash = terrainContentHash == null ? "" : terrainContentHash;
        this.paramsHash = paramsHash == null ? "" : paramsHash;
        this.materialsHash = materialsHash == null ? "" : materialsHash;
        this.contextHash = contextHash == null ? "" : contextHash;
    }

    public String worldKey() { return worldKey; }
    public BlockPos origin() { return origin; }
    public Direction facing() { return facing; }
    public String terrainContentHash() { return terrainContentHash; }
    public String paramsHash() { return paramsHash; }
    public String materialsHash() { return materialsHash; }
    public String contextHash() { return contextHash; }

    public static PalaceBuildContextFingerprint create(
            String worldKey,
            BlockPos origin,
            Direction facing,
            Map<String, Object> normalizedParams,
            String terrainContentHash,
            String paletteId
    ) {
        String paramsHash = hashCanonicalParams(normalizedParams);
        String materialsHash = hashUtf8(paletteId == null ? "" : paletteId.trim().toLowerCase(Locale.ROOT));
        String ctx = hashContext(worldKey, origin, facing, terrainContentHash, paramsHash, materialsHash);
        return new PalaceBuildContextFingerprint(
                worldKey, origin, facing, terrainContentHash, paramsHash, materialsHash, ctx);
    }

    public static String hashContext(
            String worldKey,
            BlockPos origin,
            Direction facing,
            String terrainContentHash,
            String paramsHash,
            String materialsHash
    ) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            ByteBuffer hdr = ByteBuffer.allocate(4);
            hdr.putInt(HASH_VERSION);
            md.update(hdr.array());
            md.update(safe(worldKey).getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            BlockPos o = origin == null ? BlockPos.ORIGIN : origin;
            ByteBuffer pos = ByteBuffer.allocate(12);
            pos.putInt(o.getX()).putInt(o.getY()).putInt(o.getZ());
            md.update(pos.array());
            md.update(safe(facing == null ? "south" : facing.asString()).getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(safe(terrainContentHash).getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(safe(paramsHash).getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(safe(materialsHash).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest()).substring(0, 16);
        } catch (Exception e) {
            return "ctx-error";
        }
    }

    /**
     * Canonical param hash with recursive Map/List normalization
     * (see {@link com.formacraft.common.patch.ConfirmedPlanIdentity#canonicalizeValue}).
     */
    public static String hashCanonicalParams(Map<String, Object> params) {
        return com.formacraft.common.patch.ConfirmedPlanIdentity.hashCanonicalParams(params);
    }

    public static Map<String, Object> normalizeExtras(Map<String, Object> extras) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        if (extras == null) return out;
        List<String> keys = new ArrayList<>(extras.keySet());
        keys.sort(String.CASE_INSENSITIVE_ORDER);
        for (String k : keys) {
            if (k == null || extras.get(k) == null) continue;
            out.put(k, extras.get(k));
        }
        return out;
    }

    private static String hashUtf8(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(safe(s).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest()).substring(0, 16);
        } catch (Exception e) {
            return "hash-error";
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PalaceBuildContextFingerprint that)) return false;
        return Objects.equals(contextHash, that.contextHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(contextHash);
    }
}
