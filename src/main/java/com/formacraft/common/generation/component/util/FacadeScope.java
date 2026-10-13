package com.formacraft.common.generation.component.util;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Shared facade generation / delegation scope.
 * <p>
 * Absent {@code faces}/{@code suppress_faces} ⇒ {@link #fullMass()}.
 * Explicit empty or invalid faces ⇒ {@code E_FACADE_SCOPE_INVALID} via {@link #parseStrict}.
 */
public record FacadeScope(boolean fullMass, Set<Region> regions) {

    public static final Set<String> VALID_FACES = Set.of("NORTH", "SOUTH", "EAST", "WEST");

    public record Region(String face, Integer minFloor, Integer maxFloor) {
        public Region {
            if (face != null) face = face.trim().toUpperCase(Locale.ROOT);
        }

        public boolean coversFloor(int floorIndex) {
            if (minFloor != null && floorIndex < minFloor) return false;
            if (maxFloor != null && floorIndex > maxFloor) return false;
            return true;
        }
    }

    public FacadeScope {
        regions = regions == null ? Set.of() : Set.copyOf(regions);
    }

    public static FacadeScope full() {
        return new FacadeScope(true, Set.of());
    }

    public static FacadeScope none() {
        return new FacadeScope(false, Set.of());
    }

    /**
     * Lenient parse: missing faces ⇒ full; invalid tokens are dropped.
     * Prefer {@link #parseStrict} for generators and delegation.
     */
    public static FacadeScope parse(Map<String, Object> params) {
        try {
            return parseStrict(params, null);
        } catch (IllegalArgumentException e) {
            // Lenient fallback: ignore strict failures for legacy callers.
            if (params == null || (!params.containsKey("faces") && !params.containsKey("suppress_faces"))) {
                return full();
            }
            Set<String> faces = parseFaceNames(params);
            faces = new LinkedHashSet<>(faces);
            faces.retainAll(VALID_FACES);
            if (faces.isEmpty()) return full();
            Integer minFloor = intOrNull(params, "min_floor", "minFloor");
            Integer maxFloor = intOrNull(params, "max_floor", "maxFloor");
            if (minFloor != null && maxFloor != null && minFloor > maxFloor) return full();
            var regions = new LinkedHashSet<Region>();
            for (String f : faces) regions.add(new Region(f, minFloor, maxFloor));
            return new FacadeScope(false, regions);
        }
    }

    /**
     * Strict parse for generation / compile.
     *
     * @param floorCountHint host floor count (0-based max exclusive), or null to skip range check
     */
    public static FacadeScope parseStrict(Map<String, Object> params, Integer floorCountHint) {
        FacePresence presence = facePresence(params);
        Integer minFloor = intOrNull(params, "min_floor", "minFloor");
        Integer maxFloor = intOrNull(params, "max_floor", "maxFloor");
        if (minFloor != null && maxFloor != null && minFloor > maxFloor) {
            throw new IllegalArgumentException(
                    "E_FACADE_SCOPE_INVALID: min_floor=" + minFloor + " > max_floor=" + maxFloor);
        }
        if (floorCountHint != null && floorCountHint > 0) {
            int maxIndex = floorCountHint - 1;
            if (minFloor != null && minFloor > maxIndex) {
                throw new IllegalArgumentException(
                        "E_FACADE_SCOPE_INVALID: min_floor=" + minFloor
                                + " exceeds floor_count=" + floorCountHint);
            }
            if (maxFloor != null && maxFloor > maxIndex) {
                throw new IllegalArgumentException(
                        "E_FACADE_SCOPE_INVALID: max_floor=" + maxFloor
                                + " exceeds floor_count=" + floorCountHint);
            }
        }
        if (presence == FacePresence.ABSENT) {
            return full();
        }
        if (presence == FacePresence.EXPLICIT_EMPTY) {
            throw new IllegalArgumentException(
                    "E_FACADE_SCOPE_INVALID: faces explicitly empty (refuse fullMass fallback)");
        }
        Set<String> faces = parseFaceNames(params);
        if (faces.isEmpty()) {
            throw new IllegalArgumentException(
                    "E_FACADE_SCOPE_INVALID: faces explicitly empty (refuse fullMass fallback)");
        }
        for (String f : faces) {
            if (!VALID_FACES.contains(f)) {
                throw new IllegalArgumentException(
                        "E_FACADE_SCOPE_INVALID: unknown face='" + f
                                + "' allowed=" + VALID_FACES);
            }
        }
        var regions = new LinkedHashSet<Region>();
        for (String f : faces) {
            regions.add(new Region(f, minFloor, maxFloor));
        }
        return new FacadeScope(false, regions);
    }

    enum FacePresence { ABSENT, EXPLICIT_EMPTY, PRESENT }

    static FacePresence facePresence(Map<String, Object> params) {
        if (params == null) return FacePresence.ABSENT;
        Object raw = params.get("faces");
        if (raw == null) raw = params.get("suppress_faces");
        if (raw == null) return FacePresence.ABSENT;
        if (raw instanceof List<?> list) {
            if (list.isEmpty()) return FacePresence.EXPLICIT_EMPTY;
            boolean any = false;
            for (Object o : list) {
                if (o != null && !o.toString().isBlank()) any = true;
            }
            return any ? FacePresence.PRESENT : FacePresence.EXPLICIT_EMPTY;
        }
        String s = raw.toString().trim();
        if (s.isEmpty()) return FacePresence.EXPLICIT_EMPTY;
        return FacePresence.PRESENT;
    }

    public boolean isFullMass() {
        return fullMass;
    }

    public boolean covers(String localFace, int floorIndex) {
        if (fullMass) return true;
        if (localFace == null || localFace.isBlank()) return false;
        String face = localFace.trim().toUpperCase(Locale.ROOT);
        for (Region r : regions) {
            if (r.face() != null && r.face().equals(face) && r.coversFloor(floorIndex)) return true;
        }
        return false;
    }

    public Set<String> faceNames() {
        if (fullMass) return Set.of();
        var out = new LinkedHashSet<String>();
        for (Region r : regions) {
            if (r.face() != null && !r.face().isBlank()) out.add(r.face());
        }
        return out;
    }

    public static Set<String> parseFaceNames(Map<String, Object> params) {
        if (params == null) return Set.of();
        Object raw = params.get("faces");
        if (raw == null) raw = params.get("suppress_faces");
        if (raw == null) return Set.of();
        var out = new LinkedHashSet<String>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !o.toString().isBlank()) out.add(o.toString().trim().toUpperCase(Locale.ROOT));
            }
        } else {
            String s = raw.toString().trim();
            if (!s.isEmpty()) {
                for (String part : s.split("[,|;\\s]+")) {
                    if (!part.isBlank()) out.add(part.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        return Collections.unmodifiableSet(out);
    }

    private static Integer intOrNull(Map<String, Object> params, String... keys) {
        if (params == null) return null;
        for (String key : keys) {
            Object v = params.get(key);
            if (v == null) continue;
            if (v instanceof Number n) return n.intValue();
            try {
                return Integer.parseInt(v.toString().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FacadeScope that)) return false;
        return fullMass == that.fullMass && Objects.equals(regions, that.regions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fullMass, regions);
    }
}
