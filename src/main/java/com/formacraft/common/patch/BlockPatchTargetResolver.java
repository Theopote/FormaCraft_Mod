package com.formacraft.common.patch;

import com.formacraft.common.logging.FcaLog;
import net.minecraft.block.*;
import net.minecraft.registry.Registries;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import java.util.Locale;
import java.util.Optional;

/** Strict shared target resolution for preview, validation and execution. */
public final class BlockPatchTargetResolver {
    private static final FcaLog LOG = FcaLog.of("BlockPatchTargetResolver");
    private BlockPatchTargetResolver() {}

    public static BlockState resolve(BlockPatch patch) {
        if (patch == null) return null;
        String action = patch.action() == null ? "" : patch.action().trim().toLowerCase(Locale.ROOT);
        return switch (action) {
            case BlockPatch.REMOVE -> Blocks.AIR.getDefaultState();
            case BlockPatch.PLACE, BlockPatch.REPLACE -> parse(patch.targetBlock());
            default -> null;
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static BlockState parse(String id) {
        if (id == null || id.isBlank()) return null;
        try {
            String raw = id.trim();
            String baseId = raw;
            String props = null;
            int lb = raw.indexOf('[');
            if (lb >= 0 && (!raw.endsWith("]") || raw.indexOf('[', lb + 1) >= 0)) return null;
            if (lb < 0 && raw.contains("]")) return null;
            if (lb >= 0) {
                baseId = raw.substring(0, lb);
                props = raw.substring(lb + 1, raw.length() - 1);
            }

            Identifier ident = Identifier.tryParse(baseId.trim());
            if (ident == null || !Registries.BLOCK.containsId(ident)) {
                return null;
            }
            Block b = Registries.BLOCK.get(ident);
            BlockState state = b.getDefaultState();

            if (props != null && !props.isBlank()) {
                StateManager<Block, BlockState> sm = b.getStateManager();
                if (sm != null) {
                    String[] kvs = props.split(",", -1);
                    java.util.Set<String> seen = new java.util.HashSet<>();
                    for (String kv : kvs) {
                        if (kv == null) continue;
                        String t = kv.trim();
                        if (t.isEmpty()) return null;
                        int eq = t.indexOf('=');
                        if (eq <= 0 || eq >= t.length() - 1) return null;
                        String key = t.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                        String val = t.substring(eq + 1).trim().toLowerCase(Locale.ROOT);
                        if (key.isEmpty() || val.isEmpty() || !seen.add(key)) return null;

                        Property<?> prop = sm.getProperty(key);
                        if (prop == null) return null;
                        Optional<?> parsed = prop.parse(val);
                        if (parsed.isEmpty()) return null;
                        Object v = parsed.get();
                        if (!(v instanceof Comparable<?>)) return null;
                        state = state.with((Property) prop, (Comparable) v);
                    }
                }
            }

            return state;
        } catch (RuntimeException ex) {
            LOG.debug("resolve block state failed blockId={}", id, ex);
            return null;
        }
    }
}
