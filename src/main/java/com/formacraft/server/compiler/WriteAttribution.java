package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.GeneratedSurfaceCapture;
import com.formacraft.common.llm.dto.Component;
import net.minecraft.util.math.BlockPos;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Developer-facing per-cell write attribution (Phase 01.7).
 * <p>
 * Traces which compile stage owns a cell when a write is rejected or audited.
 * Intended for logs / {@code CapabilityGap.details} — not player chat.
 */
public final class WriteAttribution {
    public static final String PROTECTED_OPENING_OVERWRITE = "PROTECTED_OPENING_OVERWRITE";
    public static final String UNAUTHORIZED_PLACE_OVERWRITE = "UNAUTHORIZED_PLACE_OVERWRITE";
    public static final String SURFACE_OVERWRITE_FORBIDDEN = "SURFACE_OVERWRITE_FORBIDDEN";
    public static final String RESOLUTION_REJECTED = "rejected";

    private WriteAttribution() {}

    /**
     * Structured attribution for one cell.
     *
     * @param componentId         attempting component identity
     * @param hostId              building / host id when known
     * @param position            plan-local cell
     * @param originalWriter      occupancy / mass writer that first placed the cell
     * @param openingWriter       surface source when the cell is an opening role
     * @param postProcessWriter   POSTPROCESS_* occupancy writer when present
     * @param violation           machine code (e.g. {@link #PROTECTED_OPENING_OVERWRITE})
     * @param resolution          typically {@link #RESOLUTION_REJECTED}
     * @param reason              human-readable ledger / occupancy reason
     */
    public record Report(
            String componentId,
            String hostId,
            BlockPos position,
            String originalWriter,
            String openingWriter,
            String postProcessWriter,
            String violation,
            String resolution,
            String reason
    ) {
        /** Multi-line developer diagnostic matching the Phase 01.7 contract example. */
        public String formatDeveloper() {
            StringBuilder sb = new StringBuilder();
            sb.append("Component: ").append(nullToDash(componentId)).append('\n');
            sb.append("Host: ").append(nullToDash(hostId)).append('\n');
            sb.append("Position: ").append(position == null ? "-" : position.toShortString()).append('\n');
            sb.append('\n');
            sb.append("Original writer: ").append(nullToDash(originalWriter)).append('\n');
            sb.append("Opening writer: ").append(nullToDash(openingWriter)).append('\n');
            sb.append("Post-process writer: ").append(nullToDash(postProcessWriter)).append('\n');
            sb.append("Violation: ").append(nullToDash(violation)).append('\n');
            sb.append("Resolution: ").append(nullToDash(resolution));
            if (reason != null && !reason.isBlank()) {
                sb.append('\n').append("Reason: ").append(reason);
            }
            return sb.toString();
        }

        /** Flatten into CapabilityGap.details (stable keys). */
        public Map<String, Object> toDetails() {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("component_id", nullToDash(componentId));
            d.put("host_id", nullToDash(hostId));
            if (position != null) {
                d.put("position", Map.of("x", position.getX(), "y", position.getY(), "z", position.getZ()));
            }
            d.put("original_writer", nullToDash(originalWriter));
            d.put("opening_writer", nullToDash(openingWriter));
            d.put("postprocess_writer", nullToDash(postProcessWriter));
            d.put("violation", nullToDash(violation));
            d.put("resolution", nullToDash(resolution));
            if (reason != null && !reason.isBlank()) d.put("reason", reason);
            d.put("attribution", formatDeveloper());
            return d;
        }
    }

    static Report fromSurfaceConflict(
            Component writer,
            SurfaceOwnershipLedger.Conflict conflict,
            PatchOccupancyIndex occupancy
    ) {
        BlockPos pos = conflict == null ? null : conflict.position();
        PatchOccupancyIndex.PlaceRecord place = occupancy == null || pos == null ? null : occupancy.placeAt(pos);
        SurfaceOwnershipLedger.Surface surface = conflict == null ? null : conflict.surface();
        String violation = classifySurfaceViolation(surface, conflict == null ? null : conflict.reason());
        return new Report(
                ComponentPlanTypes.paramIdentity(writer),
                ComponentPlanTypes.getParamString(writer == null ? null : writer.params(), "host_id", "hostId", "building_id"),
                pos,
                place == null ? null : place.writerType(),
                surface == null ? null : surface.source(),
                postProcessWriterOf(place),
                violation,
                RESOLUTION_REJECTED,
                conflict == null ? null : conflict.reason()
        );
    }

    static Report fromOccupancyConflict(
            Component writer,
            PatchOccupancyIndex.Conflict conflict,
            SurfaceOwnershipLedger ledger
    ) {
        BlockPos pos = conflict == null ? null : conflict.position();
        SurfaceOwnershipLedger.Surface surface = ledger == null || pos == null ? null : ledger.surfaceAt(pos);
        String violation = classifyOccupancyViolation(conflict == null ? null : conflict.reason(), surface);
        return new Report(
                ComponentPlanTypes.paramIdentity(writer),
                ComponentPlanTypes.getParamString(writer == null ? null : writer.params(), "host_id", "hostId", "building_id"),
                pos,
                conflict == null ? null : conflict.previousWriter(),
                surface == null ? null : surface.source(),
                null,
                violation,
                RESOLUTION_REJECTED,
                conflict == null ? null : conflict.reason()
        );
    }

    /**
     * Snapshot a cell's known writers without asserting a violation
     * (developer probe / quality tooling).
     */
    public static Report trace(
            BlockPos position,
            PatchOccupancyIndex occupancy,
            SurfaceOwnershipLedger ledger,
            Component attemptingWriter
    ) {
        PatchOccupancyIndex.PlaceRecord place = occupancy == null || position == null
                ? null : occupancy.placeAt(position);
        SurfaceOwnershipLedger.Surface surface = ledger == null || position == null
                ? null : ledger.surfaceAt(position);
        return new Report(
                ComponentPlanTypes.paramIdentity(attemptingWriter),
                ComponentPlanTypes.getParamString(
                        attemptingWriter == null ? null : attemptingWriter.params(),
                        "host_id", "hostId", "building_id"),
                position,
                place == null ? null : place.writerType(),
                surface == null ? null : surface.source(),
                postProcessWriterOf(place),
                null,
                null,
                surface == null ? null : String.valueOf(surface.role())
        );
    }

    static String classifySurfaceViolation(SurfaceOwnershipLedger.Surface surface, String reason) {
        if (surface != null) {
            GeneratedSurfaceCapture.Role role = surface.role();
            if (role == GeneratedSurfaceCapture.Role.WINDOW
                    || role == GeneratedSurfaceCapture.Role.DOOR_OPENING
                    || role == GeneratedSurfaceCapture.Role.FACADE_OPENING) {
                return PROTECTED_OPENING_OVERWRITE;
            }
        }
        if (reason != null) {
            String r = reason.toLowerCase(Locale.ROOT);
            if (r.contains("opening") && (r.contains("overwrite") || r.contains("replace") || r.contains("carve"))) {
                return PROTECTED_OPENING_OVERWRITE;
            }
        }
        return SURFACE_OVERWRITE_FORBIDDEN;
    }

    static String classifyOccupancyViolation(String reason, SurfaceOwnershipLedger.Surface surface) {
        if (surface != null) {
            GeneratedSurfaceCapture.Role role = surface.role();
            if (role == GeneratedSurfaceCapture.Role.WINDOW
                    || role == GeneratedSurfaceCapture.Role.DOOR_OPENING
                    || role == GeneratedSurfaceCapture.Role.FACADE_OPENING) {
                return PROTECTED_OPENING_OVERWRITE;
            }
        }
        if (reason != null && reason.toLowerCase(Locale.ROOT).contains("unauthorized")) {
            return UNAUTHORIZED_PLACE_OVERWRITE;
        }
        return UNAUTHORIZED_PLACE_OVERWRITE;
    }

    private static String postProcessWriterOf(PatchOccupancyIndex.PlaceRecord place) {
        if (place == null || place.writerType() == null) return null;
        String t = place.writerType();
        return t.startsWith("POSTPROCESS") ? t : null;
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
