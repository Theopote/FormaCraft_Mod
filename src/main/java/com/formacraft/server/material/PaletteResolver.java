package com.formacraft.server.material;

import com.formacraft.common.palette.PaletteCatalog;
import com.formacraft.common.palette.PaletteRegistry;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Locale;

/**
 * PaletteResolver: maps semantic parts to concrete BlockState with weighted randomness.
 *
 * Determinism:
 * - Selection is deterministic per (paletteId, part, position, salt) so previews stay stable.
 */
public final class PaletteResolver {
    private PaletteResolver() {}

    public static BlockState pick(ServerWorld world, String paletteId, String part, BlockPos pos, long salt, BlockState fallback) {
        if (paletteId == null || paletteId.isBlank()) return fallback;
        PaletteCatalog.PaletteDef def = PaletteRegistry.get(paletteId);
        if (def == null || def.parts == null) return fallback;

        String key = normalizePart(part);
        List<PaletteCatalog.WeightedBlock> list = def.parts.get(key);
        if (list == null || list.isEmpty()) {
            // also try original key (case-sensitive) for flexibility
            list = def.parts.get(part);
        }
        if (list == null || list.isEmpty()) return fallback;

        int total = 0;
        for (PaletteCatalog.WeightedBlock wb : list) {
            if (wb == null) continue;
            int w = Math.max(0, wb.weight);
            if (w > 0) total += w;
        }
        if (total <= 0) return fallback;

        int r = Math.floorMod(hash(pos, paletteId, key, salt), total);
        int acc = 0;
        for (PaletteCatalog.WeightedBlock wb : list) {
            if (wb == null) continue;
            int w = Math.max(0, wb.weight);
            if (w == 0) continue;
            acc += w;
            if (r < acc) {
                BlockState st = stateFromId(world, wb.id);
                return st != null ? st : fallback;
            }
        }
        return fallback;
    }

    public static String normalizePart(String part) {
        String p = part == null ? "" : part.trim();
        if (p.isEmpty()) return p;
        // normalize common separators/case: Wall_Base -> WALL_BASE
        p = p.replace('-', '_');
        return p.toUpperCase(Locale.ROOT);
    }

    public static BlockState stateFromId(ServerWorld world, String id) {
        if (id == null || id.isBlank()) return null;
        String s = id.trim();
        if (!s.contains(":")) s = "minecraft:" + s;
        try {
            Identifier key = Identifier.tryParse(s);
            if (key == null) return null;
            var block = Registries.BLOCK.get(key);
            return block.getDefaultState();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static long hash(BlockPos pos, String paletteId, String part, long salt) {
        long h = 1469598103934665603L; // FNV-1a offset
        h = fnv(h, pos.getX());
        h = fnv(h, pos.getY());
        h = fnv(h, pos.getZ());
        h = fnv(h, paletteId);
        h = fnv(h, part);
        h = fnv(h, salt);
        return h;
    }

    private static long fnv(long h, int v) {
        h ^= (v * 0x9E3779B9L);
        return h * 1099511628211L;
    }

    private static long fnv(long h, long v) {
        h ^= v;
        return h * 1099511628211L;
    }

    private static long fnv(long h, String s) {
        if (s == null) return fnv(h, 0L);
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 1099511628211L;
        }
        return h;
    }
}


