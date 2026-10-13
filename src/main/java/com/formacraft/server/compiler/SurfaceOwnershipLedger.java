package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.GeneratedSurfaceCapture;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;

import java.util.*;

/**
 * Per-compilation surface provenance with ERASE / REPLACE / CARVE_OPENING / DECORATE permissions.
 */
final class SurfaceOwnershipLedger {
    enum Op { ERASE, REPLACE, CARVE_OPENING, DECORATE }

    record Surface(String owner, String source, GeneratedSurfaceCapture.Role role) {}
    record Conflict(BlockPos position, Surface surface, String writer, String reason) {}

    private final Map<BlockPos, Surface> surfaces = new HashMap<>();
    private final Map<BlockPos, BlockPatch> finalPatches = new HashMap<>();

    Optional<Conflict> check(Component component, List<BlockPatch> patches) {
        String type = component.componentType().toUpperCase(Locale.ROOT);
        boolean decor = type.equals("DECOR_DETAIL");
        boolean opening = Set.of("ENTRANCE", "FACADE_WINDOWS", "FACADE").contains(type);
        if (!decor && !opening) return Optional.empty();

        String writer = identity(component);
        String host = param(component, "host_id");
        var writes = index(patches);
        for (var entry : writes.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockPatch write = entry.getValue();
            Surface surface = surfaces.get(pos);
            BlockPatch existing = finalPatches.get(pos);
            Op op = classify(write, existing, surface, opening, decor);
            if (op == null) continue;

            String reason = permissionDenied(op, surface, writer, host, decor, opening, type);
            if (reason != null) {
                return Optional.of(new Conflict(pos,
                        surface != null ? surface : new Surface("?", "?", GeneratedSurfaceCapture.Role.WALL),
                        writer, reason));
            }
        }
        return Optional.empty();
    }

    void accept(Component component, List<BlockPatch> patches, List<GeneratedSurfaceCapture.Cell> cells, BlockPos offset) {
        finalPatches.putAll(index(patches));
        String owner = param(component, "host_id");
        if (owner == null) owner = identity(component);
        for (var cell : cells) {
            BlockPos position = cell.shift(offset).position();
            if (cell.requiresBlock() || cell.role() == GeneratedSurfaceCapture.Role.DOOR_OPENING
                    || cell.role() == GeneratedSurfaceCapture.Role.FACADE_OPENING) {
                surfaces.put(position, new Surface(owner, identity(component), cell.role()));
            }
        }
    }

    /** Classify write intent; null means no ledger-relevant conflict to evaluate. */
    static Op classify(BlockPatch write, BlockPatch existing, Surface surface,
                       boolean openingWriter, boolean decorWriter) {
        boolean writeOccupied = GeneratedSurfaceCapture.occupied(write);
        boolean existingOccupied = GeneratedSurfaceCapture.occupied(existing);
        if (!writeOccupied && existingOccupied) {
            if (openingWriter && surface != null && surface.role() == GeneratedSurfaceCapture.Role.WALL)
                return Op.CARVE_OPENING;
            return Op.ERASE;
        }
        if (writeOccupied && existingOccupied) {
            if (openingWriter && surface != null && surface.role() == GeneratedSurfaceCapture.Role.WALL
                    && isOpeningInstall(write))
                return Op.CARVE_OPENING;
            if (decorWriter) return Op.DECORATE;
            return Op.REPLACE;
        }
        if (writeOccupied && !existingOccupied && decorWriter) return Op.DECORATE;
        return null;
    }

    private static String permissionDenied(Op op, Surface surface, String writer, String host,
                                           boolean decor, boolean opening, String type) {
        if (surface == null) {
            // No registered surface: DECOR place is fine; ERASE/REPLACE of unregistered skipped earlier
            return null;
        }
        GeneratedSurfaceCapture.Role role = surface.role();
        boolean sameHost = host != null && host.equals(surface.owner());
        boolean sameSource = writer.equals(surface.source());

        return switch (op) {
            case ERASE -> {
                if (decor) yield "decoration cannot delete an existing surface";
                if (opening && role == GeneratedSurfaceCapture.Role.ROOF)
                    yield "opening cannot delete roof";
                if (opening && host != null && !sameHost)
                    yield "opening host_id does not own surface";
                yield null;
            }
            case REPLACE, DECORATE -> {
                if (decor && (role == GeneratedSurfaceCapture.Role.WINDOW
                        || role == GeneratedSurfaceCapture.Role.DOOR_OPENING
                        || role == GeneratedSurfaceCapture.Role.ROOF))
                    yield "decoration cannot replace " + role + " surface";
                if (decor && role == GeneratedSurfaceCapture.Role.WALL && !sameHost && host != null)
                    yield "decoration host_id does not own wall surface";
                if (opening && (role == GeneratedSurfaceCapture.Role.WINDOW
                        || role == GeneratedSurfaceCapture.Role.DOOR_OPENING) && !sameSource)
                    yield "opening cannot replace another's " + role + " surface";
                if (opening && role == GeneratedSurfaceCapture.Role.ROOF)
                    yield "opening cannot replace roof";
                yield null;
            }
            case CARVE_OPENING -> {
                if (role == GeneratedSurfaceCapture.Role.ROOF) yield "opening cannot carve roof";
                if (host != null && !sameHost && role == GeneratedSurfaceCapture.Role.WALL)
                    yield "opening host_id does not own surface";
                if ((role == GeneratedSurfaceCapture.Role.WINDOW
                        || role == GeneratedSurfaceCapture.Role.DOOR_OPENING) && !sameSource)
                    yield "opening cannot overwrite another's " + role;
                // WALL carve / install by ENTRANCE / FACADE_WINDOWS / FACADE allowed when host matches or unset
                yield null;
            }
        };
    }

    private static boolean isOpeningInstall(BlockPatch patch) {
        if (patch == null || patch.targetBlock() == null) return false;
        String id = patch.targetBlock().toLowerCase(Locale.ROOT);
        int bracket = id.indexOf('[');
        if (bracket >= 0) id = id.substring(0, bracket);
        return id.contains("glass") || id.contains("iron_bars") || id.endsWith("_door")
                || id.contains("trapdoor");
    }

    private static Map<BlockPos, BlockPatch> index(List<BlockPatch> patches) {
        var result = new LinkedHashMap<BlockPos, BlockPatch>();
        for (var patch : patches) if (patch != null)
            result.put(new BlockPos(patch.dx(), patch.dy(), patch.dz()), patch);
        return result;
    }

    private static String param(Component c, String key) {
        Object value = c.params() == null ? null : c.params().get(key);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private static String identity(Component c) {
        String id = param(c, "component_id");
        return id != null ? id : c.componentType() + "@" + c.slotId() + ":" + c.relativePosition();
    }
}
