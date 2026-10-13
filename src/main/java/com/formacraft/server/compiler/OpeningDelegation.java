package com.formacraft.server.compiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit opening-delegation scope for MASS built-in windows/doors.
 * <p>
 * Empty regions must NOT mean "full mass" — use {@link #fullMass()} for that.
 */
public record OpeningDelegation(boolean fullMass, Set<FacadeRegion> regions) {

    public record FacadeRegion(String face, Integer minFloor, Integer maxFloor) {
        public FacadeRegion {
            if (face != null) face = face.trim().toUpperCase(Locale.ROOT);
        }

        public boolean coversFloor(int floorIndex) {
            if (minFloor != null && floorIndex < minFloor) return false;
            if (maxFloor != null && floorIndex > maxFloor) return false;
            return true;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (face != null) m.put("face", face);
            if (minFloor != null) m.put("min_floor", minFloor);
            if (maxFloor != null) m.put("max_floor", maxFloor);
            return m;
        }
    }

    public OpeningDelegation {
        regions = regions == null ? Set.of() : Set.copyOf(regions);
    }

    public static OpeningDelegation none() {
        return new OpeningDelegation(false, Set.of());
    }

    public static OpeningDelegation full() {
        return new OpeningDelegation(true, Set.of());
    }

    public static OpeningDelegation faces(Set<String> faces) {
        if (faces == null || faces.isEmpty()) return none();
        var regions = new LinkedHashSet<FacadeRegion>();
        for (String f : faces) {
            if (f != null && !f.isBlank()) regions.add(new FacadeRegion(f, null, null));
        }
        return regions.isEmpty() ? none() : new OpeningDelegation(false, regions);
    }

    public boolean isNone() {
        return !fullMass && regions.isEmpty();
    }

    public boolean isActive() {
        return fullMass || !regions.isEmpty();
    }

    /** Merge rules: full+any→full; region∪region; none+x→x. */
    public OpeningDelegation merge(OpeningDelegation other) {
        if (other == null || other.isNone()) return this;
        if (this.isNone()) return other;
        if (this.fullMass || other.fullMass) return full();
        var merged = new LinkedHashSet<FacadeRegion>(this.regions);
        merged.addAll(other.regions);
        return new OpeningDelegation(false, merged);
    }

    public Set<String> faceNames() {
        if (fullMass) return Set.of();
        var out = new LinkedHashSet<String>();
        for (FacadeRegion r : regions) {
            if (r.face() != null && !r.face().isBlank()) out.add(r.face());
        }
        return out;
    }

    public List<Map<String, Object>> regionsAsMaps() {
        if (fullMass || regions.isEmpty()) return List.of();
        var out = new ArrayList<Map<String, Object>>();
        for (FacadeRegion r : regions) out.add(r.toMap());
        return out;
    }

    public OpeningDelegation mapFaces(java.util.function.UnaryOperator<String> mapper) {
        if (fullMass || regions.isEmpty() || mapper == null) return this;
        var mapped = new LinkedHashSet<FacadeRegion>();
        for (FacadeRegion r : regions) {
            String face = r.face() == null ? null : mapper.apply(r.face());
            mapped.add(new FacadeRegion(face, r.minFloor(), r.maxFloor()));
        }
        return new OpeningDelegation(false, mapped);
    }

    /** Parse one satellite's params into a delegation slice (not yet merged). */
    public static OpeningDelegation fromSatelliteParams(Map<String, Object> params) {
        var scope = com.formacraft.common.generation.component.util.FacadeScope.parse(params);
        if (scope.isFullMass()) return full();
        var regions = new LinkedHashSet<FacadeRegion>();
        for (var r : scope.regions()) {
            regions.add(new FacadeRegion(r.face(), r.minFloor(), r.maxFloor()));
        }
        return new OpeningDelegation(false, regions);
    }

    /** Delegates to {@link com.formacraft.common.generation.component.util.FacadeScope#parseFaceNames}. */
    static Set<String> parseFaceNames(Map<String, Object> params) {
        return com.formacraft.common.generation.component.util.FacadeScope.parseFaceNames(params);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OpeningDelegation that)) return false;
        return fullMass == that.fullMass && Objects.equals(regions, that.regions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fullMass, regions);
    }
}
