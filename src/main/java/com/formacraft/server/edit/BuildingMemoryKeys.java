package com.formacraft.server.edit;

import com.formacraft.server.memory.ProjectMemory;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Canonical metadata keys for building-aware incremental editing. */
public final class BuildingMemoryKeys {
    public static final String REVISION = "building_revision";
    public static final String ROOF_CELLS = "part_index_roof_cells";
    public static final String ROOF_STATES = "part_index_roof_states";
    public static final String WINDOW_CELLS = "part_index_window_cells";
    public static final String WINDOW_STATES = "part_index_window_states";
    public static final String WALL_CELLS = "part_index_wall_cells";
    public static final String WALL_STATES = "part_index_wall_states";
    public static final String WALL_FACES = "part_index_wall_faces";
    public static final String WINDOW_OPENINGS = "part_index_window_openings";
    /** World Apply succeeded but Memory/Revision commit did not — blocks further incremental edits. */
    public static final String MEMORY_SYNC_PENDING = "building_edit_memory_sync_pending";
    public static final String MEMORY_SYNC_BASE_REVISION = "building_edit_memory_sync_base_revision";
    public static final String MEMORY_SYNC_ROOF_STATES = "building_edit_memory_sync_roof_states";
    public static final String MEMORY_SYNC_DIMENSION = "building_edit_memory_sync_dimension";

    private BuildingMemoryKeys() {}

    public static boolean isMemorySyncBlocked(ProjectMemory memory) {
        if (memory == null || memory.getMetadata() == null) return false;
        Object raw = memory.getMetadata().get(MEMORY_SYNC_PENDING);
        if (raw instanceof Boolean b) return b;
        return raw != null && Boolean.parseBoolean(String.valueOf(raw).trim());
    }

    public static void markMemorySyncFailed(
            ProjectMemory memory,
            Identifier dimension,
            long baseRevision,
            Map<BlockPos, String> pendingRoofStates
    ) {
        if (memory == null) return;
        Map<String, Object> meta = ensureMetadata(memory);
        meta.put(MEMORY_SYNC_PENDING, Boolean.TRUE);
        meta.put(MEMORY_SYNC_BASE_REVISION, Math.max(0L, baseRevision));
        if (dimension != null) {
            meta.put(MEMORY_SYNC_DIMENSION, dimension.toString());
        }
        Map<String, String> packed = new LinkedHashMap<>();
        if (pendingRoofStates != null) {
            for (var e : pendingRoofStates.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                packed.put(cellKey(e.getKey()), e.getValue());
            }
        }
        meta.put(MEMORY_SYNC_ROOF_STATES, packed);
    }

    public static void clearMemorySyncFailed(ProjectMemory memory) {
        if (memory == null || memory.getMetadata() == null) return;
        memory.getMetadata().remove(MEMORY_SYNC_PENDING);
        memory.getMetadata().remove(MEMORY_SYNC_BASE_REVISION);
        memory.getMetadata().remove(MEMORY_SYNC_ROOF_STATES);
        memory.getMetadata().remove(MEMORY_SYNC_DIMENSION);
    }

    @SuppressWarnings("unchecked")
    public static Map<BlockPos, String> memorySyncPendingRoofStates(ProjectMemory memory) {
        if (memory == null || memory.getMetadata() == null) return Map.of();
        Object raw = memory.getMetadata().get(MEMORY_SYNC_ROOF_STATES);
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) return Map.of();
        Map<BlockPos, String> out = new LinkedHashMap<>();
        for (var e : map.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            BlockPos pos = parseCellKey(String.valueOf(e.getKey()));
            if (pos != null) out.put(pos, String.valueOf(e.getValue()));
        }
        return Map.copyOf(out);
    }

    public static long memorySyncBaseRevision(ProjectMemory memory) {
        if (memory == null || memory.getMetadata() == null) return -1L;
        Object raw = memory.getMetadata().get(MEMORY_SYNC_BASE_REVISION);
        if (raw instanceof Number n) return Math.max(0L, n.longValue());
        if (raw != null) {
            try {
                return Math.max(0L, Long.parseLong(String.valueOf(raw).trim()));
            } catch (NumberFormatException ignored) {
                return -1L;
            }
        }
        return -1L;
    }

    private static BlockPos parseCellKey(String key) {
        if (key == null || key.isBlank()) return null;
        String[] parts = key.split(",");
        if (parts.length < 3) return null;
        try {
            return new BlockPos(
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

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
        storePartCells(memory, ROOF_CELLS, ROOF_STATES, cells, states);
    }

    public static void storeWindowCells(ProjectMemory memory, List<BlockPos> cells, Map<BlockPos, String> states) {
        storePartCells(memory, WINDOW_CELLS, WINDOW_STATES, cells, states);
    }

    public static void storeWallCells(ProjectMemory memory, List<BlockPos> cells, Map<BlockPos, String> states) {
        storePartCells(memory, WALL_CELLS, WALL_STATES, cells, states);
    }

    public static void storeWallFaces(ProjectMemory memory, List<BuildingPartIndex.WallFace> faces) {
        if (memory == null) return;
        Map<String, Object> meta = ensureMetadata(memory);
        List<Map<String, Object>> packed = new ArrayList<>();
        if (faces != null) {
            for (BuildingPartIndex.WallFace face : faces) {
                if (face == null) continue;
                packed.add(packIdentity(
                        face.wallId(), "", face.floorIndex(), face.facing(),
                        face.cells(), face.expectedStates()));
            }
        }
        meta.put(WALL_FACES, packed);
    }

    public static void storeWindowOpenings(ProjectMemory memory, List<BuildingPartIndex.WindowOpening> openings) {
        if (memory == null) return;
        Map<String, Object> meta = ensureMetadata(memory);
        List<Map<String, Object>> packed = new ArrayList<>();
        if (openings != null) {
            for (BuildingPartIndex.WindowOpening opening : openings) {
                if (opening == null) continue;
                packed.add(packIdentity(
                        opening.windowId(), opening.hostWallId(), opening.floorIndex(), opening.facing(),
                        opening.cells(), opening.expectedStates()));
            }
        }
        meta.put(WINDOW_OPENINGS, packed);
    }

    public static List<BuildingPartIndex.WallFace> wallFacesRaw(ProjectMemory memory) {
        List<Map<String, Object>> raw = identityListRaw(memory, WALL_FACES);
        List<BuildingPartIndex.WallFace> out = new ArrayList<>();
        for (Map<String, Object> entry : raw) {
            out.add(new BuildingPartIndex.WallFace(
                    str(entry.get("id")),
                    intVal(entry.get("floorIndex")),
                    str(entry.get("facing")),
                    unpackCells(entry.get("cells")),
                    unpackStateMap(entry.get("states"))));
        }
        return List.copyOf(out);
    }

    public static List<BuildingPartIndex.WindowOpening> windowOpeningsRaw(ProjectMemory memory) {
        List<Map<String, Object>> raw = identityListRaw(memory, WINDOW_OPENINGS);
        List<BuildingPartIndex.WindowOpening> out = new ArrayList<>();
        for (Map<String, Object> entry : raw) {
            out.add(new BuildingPartIndex.WindowOpening(
                    str(entry.get("id")),
                    str(entry.get("hostWallId")),
                    intVal(entry.get("floorIndex")),
                    str(entry.get("facing")),
                    unpackCells(entry.get("cells")),
                    unpackStateMap(entry.get("states"))));
        }
        return List.copyOf(out);
    }

    private static Map<String, Object> packIdentity(
            String id,
            String hostWallId,
            int floorIndex,
            String facing,
            Set<BlockPos> cells,
            Map<BlockPos, String> states
    ) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id == null ? "" : id);
        map.put("hostWallId", hostWallId == null ? "" : hostWallId);
        map.put("floorIndex", Math.max(0, floorIndex));
        map.put("facing", facing == null ? "" : facing);
        List<List<Integer>> packedCells = new ArrayList<>();
        Map<String, String> packedStates = new LinkedHashMap<>();
        if (cells != null) {
            for (BlockPos pos : cells) {
                if (pos == null) continue;
                packedCells.add(List.of(pos.getX(), pos.getY(), pos.getZ()));
                if (states != null && states.get(pos) != null) {
                    packedStates.put(cellKey(pos), states.get(pos));
                }
            }
        }
        map.put("cells", packedCells);
        map.put("states", packedStates);
        return map;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> identityListRaw(ProjectMemory memory, String key) {
        if (memory == null || memory.getMetadata() == null) return List.of();
        Object raw = memory.getMetadata().get(key);
        if (!(raw instanceof List<?> list) || list.isEmpty()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                for (var e : map.entrySet()) {
                    if (e.getKey() != null) copy.put(String.valueOf(e.getKey()), e.getValue());
                }
                out.add(copy);
            }
        }
        return List.copyOf(out);
    }

    private static Set<BlockPos> unpackCells(Object raw) {
        Set<BlockPos> out = new java.util.LinkedHashSet<>();
        if (!(raw instanceof List<?> list)) return Set.of();
        for (Object entry : list) {
            int[] coords = parseCell(entry);
            if (coords != null) out.add(new BlockPos(coords[0], coords[1], coords[2]));
        }
        return Set.copyOf(out);
    }

    @SuppressWarnings("unchecked")
    private static Map<BlockPos, String> unpackStateMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) return Map.of();
        Map<BlockPos, String> out = new LinkedHashMap<>();
        for (var e : map.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            BlockPos pos = parseCellKey(String.valueOf(e.getKey()));
            if (pos != null) out.put(pos, String.valueOf(e.getValue()));
        }
        return Map.copyOf(out);
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static int intVal(Object v) {
        if (v instanceof Number n) return Math.max(0, n.intValue());
        try {
            return Math.max(0, Integer.parseInt(String.valueOf(v)));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static void storePartCells(
            ProjectMemory memory,
            String cellsKey,
            String statesKey,
            List<BlockPos> cells,
            Map<BlockPos, String> states
    ) {
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
        meta.put(cellsKey, packed);
        meta.put(statesKey, stateMap);
        if (!meta.containsKey(REVISION)) {
            meta.put(REVISION, 0L);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, String> roofStatesRaw(ProjectMemory memory) {
        return statesRaw(memory, ROOF_STATES);
    }

    public static List<int[]> windowCellsRaw(ProjectMemory memory) {
        return cellsRaw(memory, WINDOW_CELLS);
    }

    public static Map<String, String> windowStatesRaw(ProjectMemory memory) {
        return statesRaw(memory, WINDOW_STATES);
    }

    public static List<int[]> wallCellsRaw(ProjectMemory memory) {
        return cellsRaw(memory, WALL_CELLS);
    }

    public static Map<String, String> wallStatesRaw(ProjectMemory memory) {
        return statesRaw(memory, WALL_STATES);
    }

    @SuppressWarnings("unchecked")
    private static List<int[]> cellsRaw(ProjectMemory memory, String key) {
        if (memory == null || memory.getMetadata() == null) return List.of();
        Object raw = memory.getMetadata().get(key);
        if (!(raw instanceof List<?> list) || list.isEmpty()) return List.of();
        List<int[]> out = new ArrayList<>();
        for (Object entry : list) {
            int[] coords = parseCell(entry);
            if (coords != null) out.add(coords);
        }
        return List.copyOf(out);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> statesRaw(ProjectMemory memory, String key) {
        if (memory == null || memory.getMetadata() == null) return Map.of();
        Object raw = memory.getMetadata().get(key);
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
