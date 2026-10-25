package com.formacraft.server.edit;

import com.formacraft.server.memory.ProjectMemory;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Canonical metadata keys for building-aware incremental editing. */
public final class BuildingMemoryKeys {
    public static final String REVISION = "building_revision";
    public static final String ROOF_CELLS = "part_index_roof_cells";
    public static final String ROOF_STATES = "part_index_roof_states";

    private BuildingMemoryKeys() {}

    public static long revisionOf(ProjectMemory memory) {
        if (memory == null || memory.getMetadata() == null) return 0L;
        Object raw = memory.getMetadata().get(REVISION);
        if (raw instanceof Number n) return Math.max(0L, n.longValue());
        if (raw != null) {
            try {
                return Math.max(0L, Long.parseLong(String.valueOf(raw).trim()));
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    public static void setRevision(ProjectMemory memory, long revision) {
        if (memory == null) return;
        ensureMetadata(memory).put(REVISION, Math.max(0L, revision));
    }

    public static long bumpRevision(ProjectMemory memory) {
        long next = revisionOf(memory) + 1L;
        setRevision(memory, next);
        return next;
    }

    @SuppressWarnings("unchecked")
    public static List<int[]> roofCellsRaw(ProjectMemory memory) {
        if (memory == null || memory.getMetadata() == null) return List.of();
        Object raw = memory.getMetadata().get(ROOF_CELLS);
        if (!(raw instanceof List<?> list) || list.isEmpty()) return List.of();
        List<int[]> out = new ArrayList<>();
        for (Object entry : list) {
            int[] coords = parseCell(entry);
            if (coords != null) out.add(coords);
        }
        return List.copyOf(out);
    }

    public static void storeRoofCells(ProjectMemory memory, List<BlockPos> cells, Map<BlockPos, String> states) {
        if (memory == null) return;
        Map<String, Object> meta = ensureMetadata(memory);
        List<List<Integer>> packed = new ArrayList<>();
        Map<String, String> stateMap = new LinkedHashMap<>();
        if (cells != null) {
            for (BlockPos pos : cells) {
                if (pos == null) continue;
                packed.add(List.of(pos.getX(), pos.getY(), pos.getZ()));
                if (states != null && states.containsKey(pos)) {
                    stateMap.put(cellKey(pos), states.get(pos));
                }
            }
        }
        meta.put(ROOF_CELLS, packed);
        meta.put(ROOF_STATES, stateMap);
        if (!meta.containsKey(REVISION)) {
            meta.put(REVISION, 0L);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, String> roofStatesRaw(ProjectMemory memory) {
        if (memory == null || memory.getMetadata() == null) return Map.of();
        Object raw = memory.getMetadata().get(ROOF_STATES);
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (var e : map.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        }
        return Map.copyOf(out);
    }

    public static String cellKey(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static Map<String, Object> ensureMetadata(ProjectMemory memory) {
        Map<String, Object> meta = memory.getMetadata();
        if (meta == null) {
            meta = new LinkedHashMap<>();
            memory.setMetadata(meta);
        }
        return meta;
    }

    private static int[] parseCell(Object entry) {
        if (entry instanceof int[] arr && arr.length >= 3) {
            return new int[]{arr[0], arr[1], arr[2]};
        }
        if (entry instanceof List<?> list && list.size() >= 3) {
            try {
                return new int[]{
                        Integer.parseInt(String.valueOf(list.get(0)).trim()),
                        Integer.parseInt(String.valueOf(list.get(1)).trim()),
                        Integer.parseInt(String.valueOf(list.get(2)).trim())
                };
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (entry instanceof String s) {
            String[] parts = s.split(",");
            if (parts.length >= 3) {
                try {
                    return new int[]{
                            Integer.parseInt(parts[0].trim()),
                            Integer.parseInt(parts[1].trim()),
                            Integer.parseInt(parts[2].trim())
                    };
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }
}
