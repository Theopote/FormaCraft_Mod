package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Explicit multi-mass junction design intent (Phase 02.3.3 Task C).
 * <p>
 * Geometry relation ({@link MassDomainRelation}) ≠ design relation. Intent is read from
 * explicit component/plan params only — never inferred from building names or mere CONTACTING.
 * Owner: USER_EXPLICIT / AI_PLANNED; Java executes contact geometry only.
 */
public enum MassJunctionIntent {
    INDEPENDENT,
    SHARED_WALL,
    OPEN_CONNECTION,
    PASSAGE,
    INTENTIONAL_INTERLOCK,
    VERTICAL_CONNECTION;

    public static final String PARAM_INTENT = "junction_intent";
    public static final String PARAM_INTENT_ALT = "mass_junction";
    public static final String PARAM_PEER = "junction_peer_id";
    public static final String PARAM_PEERS = "junction_peer_ids";
    public static final String PARAM_CONNECTION = "connection_intent";

    /** Parse a single intent token; unknown → INDEPENDENT. */
    public static MassJunctionIntent parseToken(Object raw) {
        if (raw == null) return INDEPENDENT;
        String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return INDEPENDENT;
        return switch (s) {
            case "shared_wall", "shared-wall", "sharedwall", "common_wall", "party_wall" -> SHARED_WALL;
            case "open_connection", "open-connection", "open", "open_hall", "open_junction" -> OPEN_CONNECTION;
            case "passage", "passage_connection", "corridor", "link", "gallery" -> PASSAGE;
            case "intentional_interlock", "interlock", "interlocking" -> INTENTIONAL_INTERLOCK;
            case "vertical_connection", "vertical", "stack_connection" -> VERTICAL_CONNECTION;
            case "independent", "none", "separate", "disjoint" -> INDEPENDENT;
            default -> INDEPENDENT;
        };
    }

    /**
     * Pair intent between two masses. Requires an explicit intent key on at least one side
     * that names the other (or a mutual intent without peer when both declare the same kind).
     * Mere adjacency never yields OPEN/SHARED — returns INDEPENDENT.
     */
    public static MassJunctionIntent forPair(Component a, Component b) {
        if (a == null || b == null) return INDEPENDENT;
        String aId = idOf(a);
        String bId = idOf(b);
        if (aId.isEmpty() || bId.isEmpty()) return INDEPENDENT;

        MassJunctionIntent aIntent = intentToward(a, bId);
        MassJunctionIntent bIntent = intentToward(b, aId);
        if (aIntent != INDEPENDENT && bIntent != INDEPENDENT) {
            // Prefer more "open" when both declare (open > passage > shared > interlock > vertical).
            return rank(aIntent) >= rank(bIntent) ? aIntent : bIntent;
        }
        if (aIntent != INDEPENDENT && (peersOf(a).contains(bId) || peersOf(a).isEmpty())) {
            return aIntent;
        }
        if (bIntent != INDEPENDENT && (peersOf(b).contains(aId) || peersOf(b).isEmpty())) {
            return bIntent;
        }
        // intentional_interlock flag / peer ids without junction_intent
        if (truthy(a.params(), "intentional_interlock", "intentionalInterlock")
                && truthy(b.params(), "intentional_interlock", "intentionalInterlock")) {
            return INTENTIONAL_INTERLOCK;
        }
        if (peerIdsInclude(a, bId) && peerIdsInclude(b, aId)) {
            return INTENTIONAL_INTERLOCK;
        }
        return INDEPENDENT;
    }

    private static int rank(MassJunctionIntent i) {
        return switch (i) {
            case OPEN_CONNECTION -> 5;
            case PASSAGE -> 4;
            case SHARED_WALL -> 3;
            case INTENTIONAL_INTERLOCK -> 2;
            case VERTICAL_CONNECTION -> 1;
            case INDEPENDENT -> 0;
        };
    }

    private static MassJunctionIntent intentToward(Component c, String peerId) {
        if (c == null || c.params() == null) return INDEPENDENT;
        Map<String, Object> p = c.params();
        Object raw = first(p, PARAM_INTENT, PARAM_INTENT_ALT, PARAM_CONNECTION, "junctionIntent", "massJunction");
        MassJunctionIntent intent = parseToken(raw);
        if (intent == INDEPENDENT) return INDEPENDENT;
        Set<String> peers = peersOf(c);
        if (!peers.isEmpty() && !peers.contains(peerId)) {
            return INDEPENDENT;
        }
        return intent;
    }

    /** Explicit junction + interlock peer ids (multi-peer aware). */
    public static Set<String> peersOf(Component c) {
        Set<String> out = new LinkedHashSet<>();
        if (c == null || c.params() == null) return out;
        Map<String, Object> p = c.params();
        addPeer(out, p.get(PARAM_PEER));
        addPeer(out, p.get("junctionPeerId"));
        addPeers(out, p.get(PARAM_PEERS));
        addPeers(out, p.get("junctionPeerIds"));
        addPeer(out, p.get("intentional_interlock_peer_id"));
        addPeer(out, p.get("intentionalInterlockPeerId"));
        addPeers(out, p.get("intentional_interlock_peer_ids"));
        addPeers(out, p.get("intentionalInterlockPeerIds"));
        return out;
    }

    public static boolean peerIdsInclude(Component c, String peerId) {
        if (peerId == null || peerId.isBlank()) return false;
        return peersOf(c).contains(peerId.trim());
    }

    public static boolean truthy(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) return false;
        for (String k : keys) {
            Object v = params.get(k);
            if (Boolean.TRUE.equals(v)) return true;
            if (v instanceof String s && ("true".equalsIgnoreCase(s.trim()) || "1".equals(s.trim()))) {
                return true;
            }
        }
        return false;
    }

    private static Object first(Map<String, Object> p, String... keys) {
        for (String k : keys) {
            if (p.containsKey(k) && p.get(k) != null) return p.get(k);
        }
        return null;
    }

    private static void addPeer(Set<String> out, Object raw) {
        if (raw == null) return;
        if (raw instanceof Collection<?> col) {
            addPeers(out, col);
            return;
        }
        String s = String.valueOf(raw).trim();
        if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) out.add(s);
    }

    private static void addPeers(Set<String> out, Object raw) {
        if (raw == null) return;
        if (raw instanceof Collection<?> col) {
            for (Object o : col) addPeer(out, o);
            return;
        }
        if (raw instanceof Object[] arr) {
            for (Object o : arr) addPeer(out, o);
            return;
        }
        String s = String.valueOf(raw).trim();
        if (s.startsWith("[") && s.endsWith("]")) {
            // Tolerate JSON-ish list string
            for (String part : s.substring(1, s.length() - 1).split(",")) {
                addPeer(out, part.replace("\"", "").trim());
            }
            return;
        }
        addPeer(out, s);
    }

    private static String idOf(Component c) {
        if (c == null || c.params() == null) return "";
        Object id = c.params().get("component_id");
        if (id == null) id = c.params().get("componentId");
        return id == null ? "" : String.valueOf(id).trim();
    }

    /** Merge peer id into multi-peer list params (keeps single-key compat). */
    public static void stampPeer(Map<String, Object> params, String peerId) {
        if (params == null || peerId == null || peerId.isBlank()) return;
        Set<String> peers = new LinkedHashSet<>();
        addPeer(peers, params.get("intentional_interlock_peer_id"));
        addPeers(peers, params.get("intentional_interlock_peer_ids"));
        peers.add(peerId.trim());
        params.put("intentional_interlock_peer_id", peerId.trim());
        params.put("intentional_interlock_peer_ids", new ArrayList<>(peers));
        params.put("intentional_interlock_shared_cells", true);
        params.remove("allow_patch_overwrite");
    }

    public static void stampJunction(
            Map<String, Object> params,
            MassJunctionIntent intent,
            String peerId,
            String sharedFace,
            int bandMinY,
            int bandMaxY
    ) {
        if (params == null || intent == null || intent == INDEPENDENT) return;
        params.put(PARAM_INTENT, intent.name().toLowerCase(Locale.ROOT));
        if (peerId != null && !peerId.isBlank()) {
            params.put(PARAM_PEER, peerId.trim());
            Set<String> peers = new LinkedHashSet<>();
            addPeer(peers, params.get(PARAM_PEER));
            addPeers(peers, params.get(PARAM_PEERS));
            peers.add(peerId.trim());
            params.put(PARAM_PEERS, new ArrayList<>(peers));
        }
        if (sharedFace != null && !sharedFace.isBlank()) {
            params.put("shared_wall_face", sharedFace);
            params.put("junction_shared_face", sharedFace);
        }
        if (bandMaxY > bandMinY) {
            params.put("junction_band_min_y", bandMinY);
            params.put("junction_band_max_y", bandMaxY);
        }
        if ((intent == INTENTIONAL_INTERLOCK || intent == SHARED_WALL) && peerId != null) {
            stampPeer(params, peerId);
        }
    }
}
