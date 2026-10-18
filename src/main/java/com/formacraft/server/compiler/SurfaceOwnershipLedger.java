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

    record Surface(String owner, String source, GeneratedSurfaceCapture.Role role, boolean openingCarvable) {
        Surface(String owner, String source, GeneratedSurfaceCapture.Role role) {
            this(owner, source, role, false);
        }
    }
    record Conflict(BlockPos position, Surface surface, String writer, String reason) {}

    private final Map<BlockPos, Surface> surfaces = new HashMap<>();
    private final Map<BlockPos, BlockPatch> finalPatches = new HashMap<>();
    private final Set<BlockPos> circulationClearance = new HashSet<>();

    void reserveClearance(BlockPos position) {
        circulationClearance.add(position.toImmutable());
    }

    boolean isProtectedFromWindows(Component writer, BlockPos position) {
        if (circulationClearance.contains(position)) return true;
        Surface surface = surfaces.get(position);
        if (surface == null) return false;
        String host = buildingOwner(writer);
        // Flat roofs attach at host maxY-1; same-host facade windows may carve that edge.
        // Pitched roofs stay protected so slope audits are not punctured.
        return surface.role() == GeneratedSurfaceCapture.Role.ROOF
                && !(host.equals(surface.owner()) && surface.openingCarvable())
                || surface.role() == GeneratedSurfaceCapture.Role.WALL && !host.equals(surface.owner())
                || surface.role() == GeneratedSurfaceCapture.Role.DOOR_OPENING
                || surface.role() == GeneratedSurfaceCapture.Role.WINDOW && !surface.source().equals(surface.owner());
    }

    boolean isProtectedFromEntrance(Component writer, BlockPos position) {
        if (circulationClearance.contains(position)) return true;
        Surface surface = surfaces.get(position);
        return surface != null && (surface.role() == GeneratedSurfaceCapture.Role.ROOF
                || surface.role() == GeneratedSurfaceCapture.Role.WALL && !buildingOwner(writer).equals(surface.owner())
                || surface.role() == GeneratedSurfaceCapture.Role.DOOR_OPENING);
    }

    boolean isProtectedFromDecoration(BlockPos position) {
        if (circulationClearance.contains(position)) return true;
        Surface surface = surfaces.get(position);
        return surface != null && Set.of(GeneratedSurfaceCapture.Role.WINDOW,
                GeneratedSurfaceCapture.Role.DOOR_OPENING, GeneratedSurfaceCapture.Role.ROOF).contains(surface.role());
    }

    boolean isProtectedFromDecoration(Component writer, BlockPos position) {
        Surface surface = surfaces.get(position);
        return isProtectedFromDecoration(position) || surface != null
                && surface.role() == GeneratedSurfaceCapture.Role.WALL
                && !buildingOwner(writer).equals(surface.owner());
    }

    Optional<Conflict> check(Component component, List<BlockPatch> patches) {
        String type = component.componentType().toUpperCase(Locale.ROOT);
        boolean decor = type.equals("DECOR_DETAIL");
        boolean opening = Set.of("ENTRANCE", "FACADE_WINDOWS", "FACADE").contains(type);
        if (!decor && !opening) return Optional.empty();

        String writer = identity(component);
        String host = buildingOwner(component);
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
        String owner = buildingOwner(component);
        boolean openingCarvable = isFlatRoof(component);
        if ("ENTRANCE".equalsIgnoreCase(component.componentType())) {
            // Entrance generators can emit a carved hole without capture cells.
            // Reserve both its opening and frame before placing facade windows.
            for (var p : patches) surfaces.put(new BlockPos(p.dx(), p.dy(), p.dz()),
                    new Surface(owner, identity(component), GeneratedSurfaceCapture.Role.DOOR_OPENING));
        }
        for (var cell : cells) {
            BlockPos position = cell.shift(offset).position();
            if (cell.requiresBlock() || cell.role() == GeneratedSurfaceCapture.Role.DOOR_OPENING
                    || cell.role() == GeneratedSurfaceCapture.Role.FACADE_OPENING) {
                boolean carvable = cell.role() == GeneratedSurfaceCapture.Role.ROOF && openingCarvable;
                surfaces.put(position, new Surface(owner, identity(component), cell.role(), carvable));
            }
        }
    }

    private static boolean isFlatRoof(Component component) {
        if (component == null || !"ROOF".equalsIgnoreCase(component.componentType()) || component.params() == null) {
            return false;
        }
        Object type = component.params().getOrDefault("roof_type", component.params().get("roofType"));
        return "flat".equalsIgnoreCase(String.valueOf(type));
    }

    private static String buildingOwner(Component component) {
        String owner = param(component, "building_id");
        if (owner == null) owner = param(component, "host_id");
        return owner == null ? identity(component) : owner;
    }

    /** Classify write intent; null means no ledger-relevant conflict to evaluate. */
    static Op classify(BlockPatch write, BlockPatch existing, Surface surface,
                       boolean openingWriter, boolean decorWriter) {
        boolean writeOccupied = GeneratedSurfaceCapture.occupied(write);
        boolean existingOccupied = GeneratedSurfaceCapture.occupied(existing);
        if (!writeOccupied && existingOccupied) {
            if (openingWriter && surface != null && carvableByOpening(surface))
                return Op.CARVE_OPENING;
            return Op.ERASE;
        }
        if (writeOccupied && existingOccupied) {
            if (openingWriter && surface != null && carvableByOpening(surface)
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
        if ("ENTRANCE".equals(type) && sameHost && host.equals(surface.source())
                && role == GeneratedSurfaceCapture.Role.WINDOW) return null;

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
                // Wall material replace requires an explicit matching host_id/building_id.
                if (decor && role == GeneratedSurfaceCapture.Role.WALL && !sameHost)
                    yield "decoration host_id does not own wall surface";
                if (opening && (role == GeneratedSurfaceCapture.Role.WINDOW
                        || role == GeneratedSurfaceCapture.Role.DOOR_OPENING) && !sameSource)
                    yield "opening cannot replace another's " + role + " surface";
                if (opening && role == GeneratedSurfaceCapture.Role.ROOF
                        && !(sameHost && surface.openingCarvable()))
                    yield "opening cannot replace roof";
                yield null;
            }
            case CARVE_OPENING -> {
                if (role == GeneratedSurfaceCapture.Role.ROOF
                        && !(sameHost && surface.openingCarvable()))
                    yield "opening cannot carve roof";
                if (host != null && !sameHost && role == GeneratedSurfaceCapture.Role.WALL)
                    yield "opening host_id does not own surface";
                if ((role == GeneratedSurfaceCapture.Role.WINDOW
                        || role == GeneratedSurfaceCapture.Role.DOOR_OPENING) && !sameSource)
                    yield "opening cannot overwrite another's " + role;
                // WALL / same-host flat-roof edge carve by ENTRANCE / FACADE_WINDOWS / FACADE
                yield null;
            }
        };
    }

    private static boolean carvableByOpening(Surface surface) {
        return surface.role() == GeneratedSurfaceCapture.Role.WALL
                || surface.role() == GeneratedSurfaceCapture.Role.ROOF && surface.openingCarvable();
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
