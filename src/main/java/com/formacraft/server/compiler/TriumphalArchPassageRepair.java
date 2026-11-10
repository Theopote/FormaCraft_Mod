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
                next = markPassageHost(c);
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

    private static Component markPassageHost(Component host) {
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
        if (!changed) {
            return host;
        }
        return new Component(host.componentType(), host.slotId(), host.relativePosition(),
                host.dimensions(), host.features(), params);
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
        boolean eastWest = facing.equals("EAST") || facing.equals("WEST");
        Dimensions hostDims = host.dimensions();
        Dimensions doorDims = entrance.dimensions();
        int span = Math.max(1, eastWest ? doorDims.depth() : doorDims.width());
        int rise = Math.max(doorDims.height(), Math.min(hostDims.height() - 1,
                Math.max(3, doorDims.height())));
        int through = Math.max(1, eastWest ? hostDims.width() : hostDims.depth());
        int newW = eastWest ? through : span;
        int newD = eastWest ? span : through;
        Vec3i hostOrigin = ComponentFootprintUtil.resolveMinCornerOrigin(host);
        Vec3i doorOrigin = entrance.relativePosition();
        int x;
        int z;
        if (eastWest) {
            x = hostOrigin.x();
            z = doorOrigin.z();
            // Keep door bay centred on host when possible.
            if (doorDims.depth() < hostDims.depth()) {
                z = Math.max(hostOrigin.z(), Math.min(doorOrigin.z(),
                        hostOrigin.z() + hostDims.depth() - newD));
            }
        } else {
            x = doorOrigin.x();
            z = hostOrigin.z();
            if (doorDims.width() < hostDims.width()) {
                x = Math.max(hostOrigin.x(), Math.min(doorOrigin.x(),
                        hostOrigin.x() + hostDims.width() - newW));
            }
        }
        Map<String, Object> params = new HashMap<>(entrance.params());
        params.put("facing", facing);
        if (host.params() != null) {
            params.put("host_id", String.valueOf(host.params().get("component_id")));
        }
        params.put("replace_wall_region", true);
        params.putIfAbsent("door_width", span);
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
