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
 * Empty {@code faces}/{@code suppress_faces} ⇒ {@link #fullMass()} (whole building).
 * Non-empty faces ⇒ only listed local faces (optionally floored).
 */
public record FacadeScope(boolean fullMass, Set<Region> regions) {

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

    /** Parse satellite / window component params. No faces ⇒ full mass. */
    public static FacadeScope parse(Map<String, Object> params) {
        Set<String> faces = parseFaceNames(params);
        Integer minFloor = intOrNull(params, "min_floor", "minFloor");
        Integer maxFloor = intOrNull(params, "max_floor", "maxFloor");
        if (faces.isEmpty()) return full();
        var regions = new LinkedHashSet<Region>();
        for (String f : faces) {
            regions.add(new Region(f, minFloor, maxFloor));
        }
        return new FacadeScope(false, regions);
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
