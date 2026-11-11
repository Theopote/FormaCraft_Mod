package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.generation.component.util.MassComponentKinds;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Triumphal / multi-bay arch monuments must punch openings through the mass depth.
 * LLM plans often emit a solid MASS_MAIN plus thin façade ENTRANCE shells — expand those
 * entrances to the host depth and mark the host as a through-passage shell.
 */
final class TriumphalArchPassageRepair {
    private TriumphalArchPassageRepair() {}

    private static final List<String> ARCH_CUES = List.of(
            "triumphal", "凯旋", "凯旋门", "arch of", "victory arch",
            "三跨", "三孔", "多跨拱", "multi-bay arch", "triple arch");

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) {
            return plan;
        }
        if (!looksTriumphalArch(plan)) {
            return plan;
        }
        Map<String, Component> hosts = new HashMap<>();
        boolean namedArchHost = false;
        for (Component c : plan.components()) {
            if (c == null || c.params() == null) continue;
            Object id = c.params().get("component_id");
            if (id == null) continue;
            if (MassComponentKinds.isHostMass(c) || "GATE".equalsIgnoreCase(c.componentType())
                    || "STRUCTURE".equalsIgnoreCase(c.componentType())) {
                hosts.put(String.valueOf(id), c);
                if (isArchHost(c)) {
                    namedArchHost = true;
                }
            }
        }
        Component plinth = findPlinth(plan);
        List<Component> out = new ArrayList<>(plan.components().size());
        boolean changed = false;
        for (Component c : plan.components()) {
            Component next = c;
            // Named arch/gate hosts always become passage shells; otherwise fall back to every
            // host mass so a plain MASS_MAIN + thin ENTRANCE still punches through.
            boolean markHost = isArchHost(c)
                    || (!namedArchHost && hosts.containsKey(String.valueOf(
                    c.params() == null ? "" : c.params().get("component_id"))));
            if (markHost) {
                next = markPassageHost(c, plinth);
            } else if ("ENTRANCE".equalsIgnoreCase(c.componentType())) {
                next = expandEntranceThroughHost(c, hosts);
            }
            if (next != c) {
                changed = true;
            }
            out.add(next);
        }
        if (!changed) {
            return plan;
        }
        FormacraftMod.LOGGER.info("TriumphalArchPassageRepair: expanded through-passage openings for triumphal arch");
        return PlanComponentRewrite.withComponents(plan, out);
    }

    private static boolean looksTriumphalArch(LlmPlan plan) {
        String corpus = intentCorpus(plan);
        return containsAny(corpus, ARCH_CUES);
    }

    private static boolean isArchHost(Component c) {
        if (c == null || c.params() == null) return false;
        if (!(MassComponentKinds.isHostMass(c)
                || "GATE".equalsIgnoreCase(c.componentType())
                || "STRUCTURE".equalsIgnoreCase(c.componentType()))) {
            return false;
        }
        Object id = c.params().get("component_id");
        String idLower = id == null ? "" : String.valueOf(id).toLowerCase(Locale.ROOT);
        if (idLower.contains("arch") || idLower.contains("gate") || idLower.contains("凯旋")
                || idLower.contains("triumphal")) {
            return true;
        }
        if (c.features() != null) {
            for (String f : c.features()) {
                if (f == null) continue;
                String lower = f.toLowerCase(Locale.ROOT);
                if (lower.contains("arch") || lower.contains("凯旋") || lower.contains("triumphal")
                        || lower.contains("passage")) {
                    return true;
                }
            }
        }
        return "GATE".equalsIgnoreCase(c.componentType());
    }

    private static Component findPlinth(LlmPlan plan) {
        Component best = null;
        int bestArea = 0;
        for (Component c : plan.components()) {
            if (c == null || c.dimensions() == null || c.relativePosition() == null) continue;
            if (!"FOUNDATION".equalsIgnoreCase(c.componentType())) continue;
            int area = Math.max(1, c.dimensions().width()) * Math.max(1, c.dimensions().depth());
            if (area > bestArea) {
                bestArea = area;
                best = c;
            }
        }
        return best;
    }

    private static Component markPassageHost(Component host, Component plinth) {
        Map<String, Object> params = new HashMap<>();
        if (host.params() != null) {
            params.putAll(host.params());
        }
        boolean changed = false;
        if (!Boolean.TRUE.equals(params.get("hollow"))) {
            params.put("hollow", true);
            changed = true;
        }
        if (!Boolean.TRUE.equals(params.get("passageway"))
                && !Boolean.TRUE.equals(params.get("through_passage"))) {
            params.put("passageway", true);
            params.put("through_passage", true);
            changed = true;
        }
        // Prefer a thin monument shell: suppress default rooms/floors that seal the bay.
        if (!Boolean.TRUE.equals(params.get("suppress_interior_floors"))) {
            params.put("suppress_interior_floors", true);
            changed = true;
        }
        // Drop unknown typology ids such as triumphal_arch that have no interpreter.
        for (String key : List.of(
                "typology_id", "structural_typology", "typologyId", "structuralTypologyId", "typology")) {
            Object v = params.get(key);
            if (v != null && String.valueOf(v).toLowerCase(Locale.ROOT).contains("triumphal")) {
                params.remove(key);
                changed = true;
            }
        }
        Vec3i position = host.relativePosition();
        Dimensions dims = host.dimensions();
        if (plinth != null && plinth.dimensions() != null && plinth.relativePosition() != null
                && dims != null && position != null
                && position.x() == 0 && position.z() == 0
                && plinth.dimensions().width() >= dims.width()
                && plinth.dimensions().depth() >= dims.depth()) {
            Vec3i plinthOrigin = ComponentFootprintUtil.resolveMinCornerOrigin(plinth);
            int insetX = Math.max(0, (plinth.dimensions().width() - dims.width()) / 2);
            int insetZ = Math.max(0, (plinth.dimensions().depth() - dims.depth()) / 2);
            Vec3i snapped = new Vec3i(plinthOrigin.x() + insetX, position.y(), plinthOrigin.z() + insetZ);
            if (!snapped.equals(position)
                    || !"min_corner".equalsIgnoreCase(String.valueOf(params.getOrDefault("anchor_mode", "")))) {
                params.put("anchor_mode", "min_corner");
                position = snapped;
                changed = true;
                FormacraftMod.LOGGER.info(
                        "TriumphalArchPassageRepair: snap arch host {} → min_corner {}",
                        params.get("component_id"), snapped);
            }
        }
        if (!changed) {
            return host;
        }
        return new Component(host.componentType(), host.slotId(), position,
                dims, host.features(), params);
    }

    private static Component expandEntranceThroughHost(Component entrance, Map<String, Component> hosts) {
        if (entrance.params() == null || entrance.dimensions() == null || entrance.relativePosition() == null) {
            return entrance;
        }
        Object hostId = entrance.params().get("host_id");
        if (hostId == null) hostId = entrance.params().get("hostId");
        Component host = hostId == null ? null : hosts.get(String.valueOf(hostId));
        if (host == null || host.dimensions() == null || host.relativePosition() == null) {
            // Fall back: if only one arch host exists, bind to it.
            if (hosts.size() == 1) {
                host = hosts.values().iterator().next();
            } else {
                return entrance;
            }
        }
        String facing = String.valueOf(entrance.params().getOrDefault("facing",
                host.params() == null ? "SOUTH" : host.params().getOrDefault("facing", "SOUTH")))
                .trim().toUpperCase(Locale.ROOT);
        Dimensions hostDims = host.dimensions();
        Dimensions doorDims = entrance.dimensions();
        // Expand the thin authored door axis through the host — never flip W/E openings into a
        // full-width × 1 slab (that erased the three bays and made the monument look like a room).
        boolean throughAlongDepth = doorDims.depth() <= doorDims.width();
        int clearW = Math.max(1, Math.max(doorDims.width(), intParam(entrance.params(),
                "clear_width", "door_width", "doorWidth")));
        int clearD = Math.max(1, Math.max(doorDims.depth(), intParam(entrance.params(),
                "clear_depth", "door_depth", "doorDepth")));
        int span = Math.max(1, throughAlongDepth ? clearW : clearD);
        int rise = Math.max(doorDims.height(), Math.min(hostDims.height() - 1,
                Math.max(3, doorDims.height())));
        int newW = throughAlongDepth ? Math.min(span, hostDims.width()) : hostDims.width();
        int newD = throughAlongDepth ? hostDims.depth() : Math.min(span, hostDims.depth());
        Vec3i hostOrigin = ComponentFootprintUtil.resolveMinCornerOrigin(host);
        Vec3i doorOrigin = entrance.relativePosition();
        int x;
        int z;
        if (throughAlongDepth) {
            x = doorOrigin.x();
            z = hostOrigin.z();
            if (newW < hostDims.width()) {
                x = Math.max(hostOrigin.x(), Math.min(doorOrigin.x(),
                        hostOrigin.x() + hostDims.width() - newW));
            }
        } else {
            x = hostOrigin.x();
            z = doorOrigin.z();
            if (newD < hostDims.depth()) {
                z = Math.max(hostOrigin.z(), Math.min(doorOrigin.z(),
                        hostOrigin.z() + hostDims.depth() - newD));
            }
        }
        Map<String, Object> params = new HashMap<>(entrance.params());
        params.put("facing", facing);
        if (host.params() != null) {
            params.put("host_id", String.valueOf(host.params().get("component_id")));
        }
        params.put("replace_wall_region", true);
        params.putIfAbsent("door_width", throughAlongDepth ? newW : newD);
        params.putIfAbsent("door_height", rise);
        List<String> features = entrance.features() == null
                ? new ArrayList<>()
                : new ArrayList<>(entrance.features());
        if (features.stream().noneMatch(f -> f != null && f.toLowerCase(Locale.ROOT).contains("arch"))) {
            features.add("arched");
            features.add("archway");
        }
        boolean same = doorDims.width() == newW && doorDims.depth() == newD
                && doorDims.height() == rise
                && doorOrigin.x() == x && doorOrigin.z() == z
                && params.equals(entrance.params());
        if (same) {
            return entrance;
        }
        FormacraftMod.LOGGER.info(
                "TriumphalArchPassageRepair: entrance {} → through {}x{}x{} facing={}",
                entrance.params().get("component_id"), newW, newD, rise, facing);
        return new Component(
                entrance.componentType(),
                entrance.slotId(),
                new Vec3i(x, hostOrigin.y(), z),
                new Dimensions(newW, newD, rise),
                features,
                params);
    }

    private static int intParam(Map<String, Object> params, String... keys) {
        if (params == null) return 0;
        for (String key : keys) {
            Object v = params.get(key);
            if (v instanceof Number n) return n.intValue();
            if (v != null) {
                try {
                    return Integer.parseInt(String.valueOf(v).trim());
                } catch (NumberFormatException ignored) {
                    // keep scanning
                }
            }
        }
        return 0;
    }

    private static String intentCorpus(LlmPlan plan) {
        StringBuilder sb = new StringBuilder();
        if (plan.playerFidelityNoticeZh() != null) sb.append(plan.playerFidelityNoticeZh()).append(' ');
        if (plan.styleProfile() != null) sb.append(plan.styleProfile()).append(' ');
        if (plan.distinguishingFeatures() != null) {
            for (String f : plan.distinguishingFeatures()) {
                if (f != null) sb.append(f).append(' ');
            }
        }
        if (plan.planMeta() != null) {
            Object intent = plan.planMeta().get("request_intent");
            if (intent instanceof Map<?, ?> map) {
                Object text = map.get("original_text");
                if (text != null) sb.append(text).append(' ');
            }
        }
        if (plan.components() != null) {
            for (Component c : plan.components()) {
                if (c == null) continue;
                if (c.features() != null) {
                    for (String f : c.features()) {
                        if (f != null) sb.append(f).append(' ');
                    }
                }
                if (c.params() != null && c.params().get("component_id") != null) {
                    sb.append(c.params().get("component_id")).append(' ');
                }
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String corpus, List<String> cues) {
        for (String cue : cues) {
            if (cue != null && !cue.isBlank() && corpus.contains(cue.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
