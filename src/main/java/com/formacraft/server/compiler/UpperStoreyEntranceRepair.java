package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.generation.component.util.MassComponentKinds;
import com.formacraft.common.generation.component.util.ResolvedFloorLayout;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Upper-storey wall doors are only legal when they open onto a balcony / terrace / deck.
 * Ground-level entrances stay; floating second-floor street doors are dropped or tagged.
 */
final class UpperStoreyEntranceRepair {
    private UpperStoreyEntranceRepair() {}

    static void apply(List<Component> parts) {
        if (parts == null || parts.isEmpty()) return;
        List<Component> decks = new ArrayList<>();
        for (Component c : parts) {
            if (c == null || c.componentType() == null) continue;
            String t = c.componentType().trim().toUpperCase(Locale.ROOT);
            if ("BALCONY".equals(t) || "TERRACE".equals(t) || "DECK".equals(t)
                    || "PLATFORM".equals(t) || "GALLERY".equals(t)) {
                decks.add(c);
                continue;
            }
            // MASS_* plates with terrace/platform/bridge roles are exterior landings too —
            // dropping stair/bridge wall openings onto them causes E_CIRCULATION_EXTERIOR_CONFLICT
            // when clerestory glass refills the face (final_5).
            if (MassComponentKinds.isPlate(c) && c.params() != null) {
                String role = String.valueOf(c.params().getOrDefault("plate_role", ""))
                        .trim().toLowerCase(Locale.ROOT);
                if (com.formacraft.common.generation.component.util.PlateRoleCatalog.TERRACE_TOKENS.contains(role)
                        || com.formacraft.common.generation.component.util.HostedPlateOccupancy.isBridgePlate(c.params())) {
                    decks.add(c);
                }
            }
        }
        List<Component> keep = new ArrayList<>(parts.size());
        boolean changed = false;
        for (Component door : parts) {
            if (door == null || !"ENTRANCE".equalsIgnoreCase(door.componentType())) {
                keep.add(door);
                continue;
            }
            if (ComponentSatelliteAligner.isUpperPlatformDoor(door)) {
                Component deckHint = touchingDeck(door, findHost(door, parts), decks);
                Dimensions dims = deckHint != null ? widenPlatformDoor(door, deckHint) : widenPlatformDoorSpan(door);
                if (dims != door.dimensions()) {
                    keep.add(new Component(
                            door.componentType(), door.slotId(), door.relativePosition(),
                            dims, door.features(), door.params()));
                    changed = true;
                } else {
                    keep.add(door);
                }
                continue;
            }
            Component host = findHost(door, parts);
            if (host == null || door.relativePosition() == null || host.dimensions() == null) {
                keep.add(door);
                continue;
            }
            int groundY = hostFloorY(host);
            int secondStoreyY = secondStoreyY(host, groundY);
            int doorY = door.relativePosition().y();
            // Treat anything at/above the second storey band as an upper door.
            if (doorY < secondStoreyY) {
                keep.add(door);
                continue;
            }
            Component deck = touchingDeck(door, host, decks);
            if (deck != null) {
                Map<String, Object> params = new HashMap<>();
                if (door.params() != null) params.putAll(door.params());
                params.put("platform_door", true);
                params.put("canopy_depth", 0);
                if (deck.params() != null && deck.params().get("component_id") != null) {
                    params.put("platform_connection", String.valueOf(deck.params().get("component_id")));
                }
                Dimensions dims = widenPlatformDoor(door, deck);
                keep.add(new Component(
                        door.componentType(), door.slotId(), door.relativePosition(),
                        dims, door.features(), params));
                FormacraftMod.LOGGER.info(
                        "UpperStoreyEntranceRepair: keep upper door as platform opening onto {}",
                        deck.componentType());
                changed = true;
                continue;
            }
            if (isUserExplicitEntrance(door)) {
                // Never drop USER_EXPLICIT upper doors — leave for circulation / stair repair.
                keep.add(door);
                FormacraftMod.LOGGER.info(
                        "UpperStoreyEntranceRepair: keep USER_EXPLICIT upper ENTRANCE without deck (y={})",
                        doorY);
                continue;
            }
            FormacraftMod.LOGGER.info(
                    "UpperStoreyEntranceRepair: drop upper-storey ENTRANCE without balcony/terrace (y={} host={})",
                    doorY,
                    host.params() == null ? host.componentType() : host.params().get("component_id"));
            changed = true;
        }
        if (changed) {
            parts.clear();
            parts.addAll(keep);
        }
    }

    private static Component findHost(Component door, List<Component> parts) {
        if (door.params() == null) return null;
        Object hostId = door.params().get("host_id");
        if (hostId == null) hostId = door.params().get("hostId");
        for (Component c : parts) {
            if (!MassComponentKinds.isCirculationMass(c) || c.params() == null) continue;
            if (hostId != null && Objects.equals(String.valueOf(hostId), String.valueOf(c.params().get("component_id")))
                    && Objects.equals(door.slotId(), c.slotId())) {
                return c;
            }
        }
        // Same-slot mass fallback when host_id is missing.
        for (Component c : parts) {
            if (!MassComponentKinds.isCirculationMass(c)) continue;
            if (Objects.equals(door.slotId(), c.slotId())) return c;
        }
        return null;
    }

    private static int hostFloorY(Component host) {
        var bounds = ComponentFootprintUtil.bounds(host);
        if (bounds != null) return bounds.minY();
        return host.relativePosition() == null ? 0 : host.relativePosition().y();
    }

    private static int secondStoreyY(Component host, int groundY) {
        int envelope = host.dimensions() == null ? 1 : Math.max(1, host.dimensions().height());
        ResolvedFloorLayout layout = ResolvedFloorLayout.from(host, envelope);
        List<Integer> ys = layout.floorYs();
        if (ys.size() >= 2) {
            return groundY + ys.get(1);
        }
        int fh = Math.max(3, ComponentParamParsers.intParam(host.params(), 4, "floor_height", "floorHeight"));
        int ground = ComponentParamParsers.intParam(host.params(), 0, "ground_clear_height", "groundClearHeight");
        return groundY + (ground > 0 ? ground : fh);
    }

    private static Component touchingDeck(Component door, Component host, List<Component> decks) {
        if (decks.isEmpty() || door.dimensions() == null || door.relativePosition() == null) return null;
        var doorBounds = ComponentFootprintUtil.bounds(door);
        if (doorBounds == null) return null;
        String face = door.params() == null ? "SOUTH"
                : String.valueOf(door.params().getOrDefault("facing", "SOUTH")).toUpperCase(Locale.ROOT);
        // Expand both the facing side and the opposite side: exterior stairs often open
        // onto an inward maintenance platform while facing names the outward wall normal
        // (final_5 south-wall door facing NORTH onto a z+1 plate).
        int minX = doorBounds.minX() - 2;
        int maxX = doorBounds.maxX() + 2;
        int minZ = doorBounds.minZ() - 2;
        int maxZ = doorBounds.maxZ() + 2;
        int minY = doorBounds.minY() - 1;
        int maxY = doorBounds.maxY() + 1;
        switch (face) {
            case "NORTH", "SOUTH" -> { /* already ±2 on Z */ }
            case "WEST", "EAST" -> { /* already ±2 on X */ }
            default -> { }
        }
        String doorHost = door.params() == null ? null
                : String.valueOf(door.params().getOrDefault("host_id",
                door.params().getOrDefault("hostId", "")));
        if (doorHost != null && doorHost.isBlank()) doorHost = null;
        for (Component deck : decks) {
            if (doorHost != null && deck.params() != null) {
                Object deckHost = deck.params().get("host_id");
                if (deckHost == null) deckHost = deck.params().get("hostId");
                if (deckHost == null) deckHost = deck.params().get("building_id");
                if (deckHost != null && !doorHost.equals(String.valueOf(deckHost).trim())) {
                    // Foreign host platform — do not count as this door's landing.
                    continue;
                }
            }
            var db = ComponentFootprintUtil.bounds(deck);
            if (db == null) continue;
            boolean overlapXZ = minX < db.maxX() && maxX > db.minX()
                    && minZ < db.maxZ() && maxZ > db.minZ();
            boolean nearY = db.minY() <= maxY && db.maxY() >= minY;
            if (overlapXZ && nearY) return deck;
        }
        return null;
    }

    private static boolean isUserExplicitEntrance(Component door) {
        if (door == null || door.params() == null) return false;
        // Presence / placement must be user-authored — door width alone is not enough.
        if (Boolean.TRUE.equals(door.params().get("door_presence_explicit"))
                || Boolean.TRUE.equals(door.params().get("entrance_explicit"))
                || Boolean.TRUE.equals(door.params().get("upper_entrance_explicit"))
                || Boolean.TRUE.equals(door.params().get("user_explicit"))) {
            return true;
        }
        // Legacy door_explicit without width-only provenance still counts as presence.
        return Boolean.TRUE.equals(door.params().get("door_explicit"))
                && !Boolean.TRUE.equals(door.params().get("door_width_user_explicit"));
    }

    /**
     * 1×1 balcony doors abort with {@code E_ENTRANCE_DOOR_WIDTH}. Grow the wall-parallel
     * span to at least 2 (prefer overlapping balcony width) so EntranceGenerator can emit.
     */
    private static Dimensions widenPlatformDoor(Component door, Component deck) {
        if (deck == null) return widenPlatformDoorSpan(door);
        Dimensions dims = door.dimensions();
        if (dims == null) return dims;
        int w = Math.max(1, dims.width());
        int d = Math.max(1, dims.depth());
        int h = Math.max(1, dims.height());
        // Already a thin opening with span ≥2 along one axis.
        if (w >= 2 || d >= 2) return dims;
        int target = 2;
        if (deck.dimensions() != null) {
            target = Math.max(2, Math.min(3, Math.max(deck.dimensions().width(), deck.dimensions().depth())));
        }
        return growDoorSpan(door, w, d, h, target);
    }

    private static Dimensions widenPlatformDoorSpan(Component door) {
        Dimensions dims = door.dimensions();
        if (dims == null) return dims;
        int w = Math.max(1, dims.width());
        int d = Math.max(1, dims.depth());
        int h = Math.max(1, dims.height());
        if (w >= 2 || d >= 2) return dims;
        return growDoorSpan(door, w, d, h, 2);
    }

    private static Dimensions growDoorSpan(Component door, int w, int d, int h, int target) {
        String face = door.params() == null ? "SOUTH"
                : String.valueOf(door.params().getOrDefault("facing", "SOUTH")).toUpperCase(Locale.ROOT);
        if ("EAST".equals(face) || "WEST".equals(face)) {
            return new Dimensions(w, Math.max(d, target), h);
        }
        // Thin N/S openings are often authored as 1×1 without facing — prefer width growth.
        if (d == 1 && w == 1) {
            return new Dimensions(Math.max(w, target), d, h);
        }
        if (w == 1 && d > 1) {
            return new Dimensions(w, Math.max(d, target), h);
        }
        return new Dimensions(Math.max(w, target), d, h);
    }
}
