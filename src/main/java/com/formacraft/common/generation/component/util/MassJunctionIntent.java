package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Explicit multi-mass junction design intent (Phase 02.3.3 / 02.3.3.1 / 02.3.4 A5).
 * <p>
 * Intent is a <strong>pair edge</strong>. Triad: {@link #UNSPECIFIED} (no declaration),
 * explicit {@link #INDEPENDENT} (must stay separate), vs connection intents.
 * Owner: USER_EXPLICIT / AI_PLANNED; Java executes contact geometry only.
 */
public enum MassJunctionIntent {
    /** No declaration on this side — not the same as explicit independent. */
    UNSPECIFIED,
    /** Explicit keep-separate. */
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
    public static final String PARAM_BY_PEER = "junction_by_peer";
    public static final String PARAM_CONNECTION = "connection_intent";
    /** Compiler-derived peer intent map — never overwrites authored {@link #PARAM_INTENT}. */
    public static final String PARAM_COMPILER_BY_PEER = "compiler_junction_by_peer";

    /** Result of resolving a pair edge (may carry conflict). */
    public record EdgeResult(MassJunctionIntent intent, boolean conflict, String conflictDetail) {
        public static EdgeResult unspecified() {
            return new EdgeResult(UNSPECIFIED, false, null);
        }

        public static EdgeResult of(MassJunctionIntent intent) {
            return new EdgeResult(intent == null ? UNSPECIFIED : intent, false, null);
        }

        public static EdgeResult conflict(String detail) {
            return new EdgeResult(UNSPECIFIED, true, detail);
        }

        /** True when no junction geometry should run. */
        public boolean isInactive() {
            return conflict || intent == null || intent == UNSPECIFIED || intent == INDEPENDENT;
        }
    }

    /**
     * Parse a single intent token.
     * {@code null}/blank/unknown → {@link #UNSPECIFIED}; explicit independent tokens → {@link #INDEPENDENT}.
     */
    public static MassJunctionIntent parseToken(Object raw) {
        if (raw == null) return UNSPECIFIED;
        String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return UNSPECIFIED;
        return switch (s) {
            case "shared_wall", "shared-wall", "sharedwall", "common_wall", "party_wall" -> SHARED_WALL;
            case "open_connection", "open-connection", "open", "open_hall", "open_junction" -> OPEN_CONNECTION;
            case "passage", "passage_connection", "corridor", "link", "gallery" -> PASSAGE;
            case "intentional_interlock", "interlock", "interlocking" -> INTENTIONAL_INTERLOCK;
            case "vertical_connection", "vertical", "stack_connection" -> VERTICAL_CONNECTION;
            case "independent", "none", "separate", "disjoint" -> INDEPENDENT;
            case "unspecified", "unset", "default" -> UNSPECIFIED;
            default -> UNSPECIFIED;
        };
    }

    /**
     * Pair-scoped edge intent. Never peerless-broadcasts; never open-wins on conflict.
     * Explicit {@link #INDEPENDENT} vs a connection intent → conflict (02.3.4 A5).
     */
    public static EdgeResult forEdge(LlmPlan plan, Component a, Component b) {
        if (a == null || b == null) return EdgeResult.unspecified();
        String aId = idOf(a);
        String bId = idOf(b);
        if (aId.isEmpty() || bId.isEmpty()) return EdgeResult.unspecified();

        EdgeResult fromList = intentFromJunctionLists(plan, a, b, aId, bId);
        if (fromList != null) {
            return fromList;
        }

        MassJunctionIntent aMap = intentFromByPeerMap(a, bId);
        MassJunctionIntent bMap = intentFromByPeerMap(b, aId);
        if (aMap != UNSPECIFIED || bMap != UNSPECIFIED) {
            return resolveMutual(aMap, bMap, aId, bId);
        }

        MassJunctionIntent aLegacy = intentTowardScoped(a, bId);
        MassJunctionIntent bLegacy = intentTowardScoped(b, aId);
        if (aLegacy != UNSPECIFIED || bLegacy != UNSPECIFIED) {
            return resolveMutual(aLegacy, bLegacy, aId, bId);
        }

        if (truthy(a.params(), "intentional_interlock", "intentionalInterlock")
                && truthy(b.params(), "intentional_interlock", "intentionalInterlock")) {
            return EdgeResult.of(INTENTIONAL_INTERLOCK);
        }
        if (peerIdsInclude(a, bId) && peerIdsInclude(b, aId)) {
            return EdgeResult.of(INTENTIONAL_INTERLOCK);
        }
        return EdgeResult.unspecified();
    }

    /** Compat — inactive edges surface as {@link #INDEPENDENT} for legacy callers. */
    public static MassJunctionIntent forPair(Component a, Component b) {
        EdgeResult e = forEdge(null, a, b);
        if (e.conflict() || e.intent() == UNSPECIFIED) return INDEPENDENT;
        return e.intent();
    }

    public static MassJunctionIntent forPair(LlmPlan plan, Component a, Component b) {
        EdgeResult e = forEdge(plan, a, b);
        if (e.conflict() || e.intent() == UNSPECIFIED) return INDEPENDENT;
        return e.intent();
    }

    private static EdgeResult resolveMutual(
            MassJunctionIntent aIntent,
            MassJunctionIntent bIntent,
            String aId,
            String bId
    ) {
        if (aIntent == UNSPECIFIED) return EdgeResult.of(bIntent);
        if (bIntent == UNSPECIFIED) return EdgeResult.of(aIntent);
        if (aIntent == bIntent) return EdgeResult.of(aIntent);
        // Explicit INDEPENDENT vetoes any connection intent (and vice versa).
        if (aIntent == INDEPENDENT || bIntent == INDEPENDENT) {
            return EdgeResult.conflict(
                    "junction intent conflict " + aId + "=" + aIntent.name().toLowerCase(Locale.ROOT)
                            + " vs " + bId + "=" + bIntent.name().toLowerCase(Locale.ROOT)
                            + " (explicit independent vs connection)");
        }
        return EdgeResult.conflict(
                "junction intent conflict " + aId + "=" + aIntent.name().toLowerCase(Locale.ROOT)
                        + " vs " + bId + "=" + bIntent.name().toLowerCase(Locale.ROOT));
    }

    /**
     * Scan junction lists; conflicting same-pair edges → conflict result.
     * @return null when no list hit; otherwise EdgeResult
     */
    private static EdgeResult intentFromJunctionLists(
            LlmPlan plan, Component a, Component b, String aId, String bId
    ) {
        List<MassJunctionIntent> hits = new ArrayList<>();
        collectJunctionListHits(
                plan == null || plan.planMeta() == null ? null : plan.planMeta().get("junctions"),
                aId, bId, hits);
        collectJunctionListHits(a.params() == null ? null : a.params().get("junctions"), aId, bId, hits);
        collectJunctionListHits(b.params() == null ? null : b.params().get("junctions"), aId, bId, hits);
        if (hits.isEmpty()) return null;
        MassJunctionIntent first = hits.getFirst();
        for (MassJunctionIntent h : hits) {
            if (h != first) {
                return EdgeResult.conflict(
                        "junction list conflict for " + aId + "↔" + bId
                                + ": " + first.name().toLowerCase(Locale.ROOT)
                                + " vs " + h.name().toLowerCase(Locale.ROOT));
            }
        }
        return EdgeResult.of(first);
    }

    private static void collectJunctionListHits(Object raw, String aId, String bId, List<MassJunctionIntent> out) {
        if (!(raw instanceof List<?> list)) return;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            String from = str(m.get("from"));
            if (from.isEmpty()) from = str(m.get("a"));
            String to = str(m.get("to"));
            if (to.isEmpty()) to = str(m.get("b"));
            MassJunctionIntent intent = parseToken(m.get("intent") != null ? m.get("intent") : m.get("type"));
            if (intent == UNSPECIFIED) continue;
            if ((from.equals(aId) && to.equals(bId)) || (from.equals(bId) && to.equals(aId))) {
                out.add(intent);
            }
        }
    }

    private static MassJunctionIntent intentFromByPeerMap(Component c, String peerId) {
        if (c == null || c.params() == null) return UNSPECIFIED;
        Object raw = c.params().get(PARAM_BY_PEER);
        if (raw == null) raw = c.params().get("junctionByPeer");
        if (raw == null) raw = c.params().get(PARAM_COMPILER_BY_PEER);
        if (!(raw instanceof Map<?, ?> map)) return UNSPECIFIED;
        Object v = map.get(peerId);
        if (v == null) return UNSPECIFIED;
        return parseToken(v);
    }

    /** Legacy single intent — requires peer set to contain peerId. */
    private static MassJunctionIntent intentTowardScoped(Component c, String peerId) {
        if (c == null || c.params() == null) return UNSPECIFIED;
        Map<String, Object> p = c.params();
        Object raw = first(p, PARAM_INTENT, PARAM_INTENT_ALT, PARAM_CONNECTION, "junctionIntent", "massJunction");
        if (raw == null) return UNSPECIFIED;
        MassJunctionIntent intent = parseToken(raw);
        if (intent == UNSPECIFIED) return UNSPECIFIED;
        Set<String> peers = junctionPeersOnly(c);
        if (peers.isEmpty() || !peers.contains(peerId)) {
            return UNSPECIFIED;
        }
        return intent;
    }

    public static Set<String> junctionPeersOnly(Component c) {
        Set<String> out = new LinkedHashSet<>();
        if (c == null || c.params() == null) return out;
        Map<String, Object> p = c.params();
        addPeer(out, p.get(PARAM_PEER));
        addPeer(out, p.get("junctionPeerId"));
        addPeers(out, p.get(PARAM_PEERS));
        addPeers(out, p.get("junctionPeerIds"));
        return out;
    }

    public static Set<String> peersOf(Component c) {
        Set<String> out = junctionPeersOnly(c);
        if (c == null || c.params() == null) return out;
        Map<String, Object> p = c.params();
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

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

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

    /**
     * Compiler-derived stamps only — does <strong>not</strong> overwrite authored
     * {@code junction_intent} / {@code junction_by_peer}.
     */
    public static void stampJunction(
            Map<String, Object> params,
            MassJunctionIntent intent,
            String peerId,
            String sharedFace,
            int bandMinY,
            int bandMaxY
    ) {
        if (params == null || intent == null || intent == UNSPECIFIED || intent == INDEPENDENT) return;
        if (peerId != null && !peerId.isBlank()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> derived = params.get(PARAM_COMPILER_BY_PEER) instanceof Map<?, ?> m
                    ? new java.util.LinkedHashMap<>((Map<String, Object>) m)
                    : new java.util.LinkedHashMap<>();
            derived.put(peerId.trim(), intent.name().toLowerCase(Locale.ROOT));
            params.put(PARAM_COMPILER_BY_PEER, derived);
            Set<String> peers = new LinkedHashSet<>();
            addPeer(peers, params.get(PARAM_PEER));
            addPeers(peers, params.get(PARAM_PEERS));
            peers.add(peerId.trim());
            params.put(PARAM_PEERS, new ArrayList<>(peers));
            if (params.get(PARAM_PEER) == null) {
                params.put(PARAM_PEER, peerId.trim());
            }
        }
        if (sharedFace != null && !sharedFace.isBlank()) {
            params.put("compiler_junction_shared_face", sharedFace);
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
