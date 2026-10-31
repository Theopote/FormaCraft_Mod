package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.OpenFramePolicy;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Water / garden pavilions often arrive as stilts + enclosed MASS_MAIN (a short house on posts).
 * Force an open frame, octagon cut when requested, thin oak stilts to the deck / lakebed, and
 * keep the roof above the post tops (not sunk into the body).
 */
final class PavilionOpenFrameRepair {
    private static final int MIN_POST_HEIGHT = 3;
    private static final int MAX_POST_HEIGHT = 5;
    private static final int MIN_STILT_HEIGHT = 3;
    /** Plan-space depth below deck; preview also anchors posts to real lakebed. */
    private static final int WATER_STILT_REACH = 12;

    private PavilionOpenFrameRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) return plan;
        boolean planWantsOctagon = looksOctagon(plan);
        boolean planWantsPavilion = looksPavilionPlan(plan);
        boolean planWantsStilts = planWantsPavilion || looksStiltIntent(plan);

        Set<String> stiltHosts = new HashSet<>();
        for (Component c : plan.components()) {
            if (c == null || c.params() == null) continue;
            if (!"FOUNDATION".equalsIgnoreCase(c.componentType())) continue;
            if (!isStilts(c.params())) continue;
            Object host = c.params().get("host_id");
            if (host == null) host = c.params().get("hostId");
            if (host != null) stiltHosts.add(String.valueOf(host));
        }

        List<Component> out = new ArrayList<>(plan.components().size());
        boolean changed = false;
        Map<String, Integer> bodyFloorY = new HashMap<>();
        Set<String> pavilionMassIds = new HashSet<>();
        for (Component c : plan.components()) {
            Component repaired = repairMass(c, stiltHosts, planWantsOctagon, planWantsPavilion || planWantsStilts);
            if (repaired != c) changed = true;
            out.add(repaired);
            if (repaired != null && repaired.params() != null && repaired.relativePosition() != null
                    && ("MASS_MAIN".equalsIgnoreCase(repaired.componentType())
                    || "MAIN_MASS".equalsIgnoreCase(repaired.componentType()))) {
                Object id = repaired.params().get("component_id");
                if (id != null) {
                    bodyFloorY.put(String.valueOf(id), repaired.relativePosition().y());
                    if (OpenFramePolicy.isOpen(repaired.params())
                            || looksPavilion(repaired.features())
                            || planWantsPavilion) {
                        pavilionMassIds.add(String.valueOf(id));
                    }
                }
            }
        }

        Map<String, Component> massById = new HashMap<>();
        for (Component c : out) {
            if (c == null || c.params() == null) continue;
            Object id = c.params().get("component_id");
            if (id != null && ("MASS_MAIN".equalsIgnoreCase(c.componentType())
                    || "MAIN_MASS".equalsIgnoreCase(c.componentType()))) {
                massById.put(String.valueOf(id), c);
            }
        }

        // Convert solid pads / missing foundations into thin oak stilts for pavilion hosts.
        if (planWantsStilts || !pavilionMassIds.isEmpty()) {
            Set<String> hostsNeedingStilts = new HashSet<>(pavilionMassIds);
            if (planWantsStilts) {
                for (Component mass : massById.values()) {
                    Object id = mass.params() == null ? null : mass.params().get("component_id");
                    if (id != null) hostsNeedingStilts.add(String.valueOf(id));
                }
            }
            Set<String> hostsWithStilts = new HashSet<>();
            for (int i = 0; i < out.size(); i++) {
                Component c = out.get(i);
                if (c == null || !"FOUNDATION".equalsIgnoreCase(c.componentType()) || c.params() == null) continue;
                Object host = c.params().get("host_id");
                if (host == null) host = c.params().get("hostId");
                if (host == null || !hostsNeedingStilts.contains(String.valueOf(host))) continue;
                Component forced = forceOakStilts(c, String.valueOf(host), massById.get(String.valueOf(host)));
                if (forced != c) {
                    out.set(i, forced);
                    changed = true;
                }
                hostsWithStilts.add(String.valueOf(host));
            }
            for (String hostId : hostsNeedingStilts) {
                if (hostsWithStilts.contains(hostId)) continue;
                Component mass = massById.get(hostId);
                if (mass == null || mass.dimensions() == null || mass.relativePosition() == null) continue;
                Component injected = injectOakStilts(mass, hostId);
                out.add(injected);
                hostsWithStilts.add(hostId);
                changed = true;
                FormacraftMod.LOGGER.info("PavilionOpenFrameRepair: inject stilts for host={}", hostId);
            }
            stiltHosts.addAll(hostsWithStilts);
        }

        for (int i = 0; i < out.size(); i++) {
            Component c = out.get(i);
            Component stilts = extendStilts(c, bodyFloorY, massById);
            if (stilts != c) {
                out.set(i, stilts);
                changed = true;
            }
        }

        // Roof must sit on top of open-frame posts (not sunk into the body / deck).
        for (int i = 0; i < out.size(); i++) {
            Component c = out.get(i);
            if (c == null || !ComponentPlanTypes.isRoofType(ComponentPlanTypes.normalizeType(c.componentType()))) {
                continue;
            }
            Component lifted = liftPavilionRoof(c, massById, pavilionMassIds);
            if (lifted != c) {
                out.set(i, lifted);
                changed = true;
            }
        }

        return changed ? PlanComponentRewrite.withComponents(plan, out) : plan;
    }

    private static Component repairMass(Component mass, Set<String> stiltHosts,
                                        boolean planWantsOctagon, boolean planWantsPavilion) {
        if (mass == null || mass.params() == null || mass.dimensions() == null) return mass;
        if (!"MASS_MAIN".equalsIgnoreCase(mass.componentType())
                && !"MAIN_MASS".equalsIgnoreCase(mass.componentType())) {
            return mass;
        }
        String id = mass.params().get("component_id") == null ? ""
                : String.valueOf(mass.params().get("component_id")).toLowerCase(Locale.ROOT);
        boolean pavilionNamed = id.contains("pavilion") || id.contains("gazebo") || id.contains("kiosk")
                || id.contains("ting") || id.contains("shuixie") || looksPavilion(mass.features())
                || planWantsPavilion;
        boolean onStilts = stiltHosts.contains(String.valueOf(mass.params().get("component_id")))
                || planWantsPavilion;
        boolean octagon = planWantsOctagon || looksOctagon(mass);
        if (!pavilionNamed && !onStilts && !octagon) return mass;
        if (!pavilionNamed && onStilts && mass.dimensions().height() <= 3 && !octagon
                && OpenFramePolicy.isOpen(mass.params())) {
            return mass;
        }

        Map<String, Object> params = new HashMap<>(mass.params());
        boolean changed = false;
        params.put("open", true);
        params.put("wall_type", "none");
        params.put("wall_style", "none");
        params.put("suppress_windows", true);
        params.put("window_style", "none");
        params.put("suppress_doors", true);
        params.put("open_sides", List.of("N", "E", "S", "W"));
        params.put("hollow", true);
        changed = true;
        if (octagon) {
            for (String key : com.formacraft.common.generation.component.util.FootprintShapeCatalog.SHAPE_KEYS) {
                Object cur = params.get(key);
                if (cur == null && !"shape".equals(key)) continue;
                String s = cur == null ? "" : String.valueOf(cur).trim().toLowerCase(Locale.ROOT);
                if (!com.formacraft.common.generation.component.util.FootprintShapeCatalog.RECTANGLE_TOKENS.contains(s)) {
                    params.put(key, "rectangle");
                    changed = true;
                }
            }
            if (!params.containsKey("shape")) {
                params.put("shape", "rectangle");
                changed = true;
            }
            params.put("plan_type", "octagon");
            int span = Math.min(mass.dimensions().width(), mass.dimensions().depth());
            int cut = Math.max(3, Math.min(span / 2 - 1, (span * 2) / 5));
            params.put("corner_cut", cut);
            params.put("sides", 8);
            changed = true;
        }

        Dimensions dims = mass.dimensions();
        int height = dims.height();
        if (pavilionNamed || onStilts || OpenFramePolicy.isOpen(params)) {
            if (height > MAX_POST_HEIGHT) {
                height = MAX_POST_HEIGHT;
                changed = true;
            } else if (height < MIN_POST_HEIGHT && (pavilionNamed || onStilts)) {
                height = MIN_POST_HEIGHT;
                changed = true;
            }
        }
        if (!changed && height == dims.height()) return mass;
        FormacraftMod.LOGGER.info(
                "PavilionOpenFrameRepair: pavilion id={} height {}→{} stilts={} octagon={}",
                id, dims.height(), height, onStilts, octagon);
        return new Component(mass.componentType(), mass.slotId(), mass.relativePosition(),
                new Dimensions(dims.width(), dims.depth(), height), mass.features(), params);
    }

    private static Component forceOakStilts(Component foundation, String hostId, Component hostMass) {
        Map<String, Object> params = new HashMap<>();
        if (foundation.params() != null) params.putAll(foundation.params());
        params.put("foundation_type", "stilts");
        params.put("material", "minecraft:oak_log");
        params.put("host_id", hostId);
        params.put("anchor_mode", "min_corner");
        params.putIfAbsent("sides", 8);
        if (hostMass != null && hostMass.params() != null) {
            for (String key : List.of("plan_type", "planType", "corner_cut", "cornerCut", "shape", "sides")) {
                if (hostMass.params().get(key) != null) params.put(key, hostMass.params().get(key));
            }
        }
        Vec3i rp = foundation.relativePosition();
        Dimensions dims = foundation.dimensions();
        if (hostMass != null && hostMass.dimensions() != null && hostMass.relativePosition() != null) {
            rp = new Vec3i(hostMass.relativePosition().x(),
                    Math.min(rp == null ? hostMass.relativePosition().y() - WATER_STILT_REACH : rp.y(),
                            hostMass.relativePosition().y() - WATER_STILT_REACH),
                    hostMass.relativePosition().z());
            dims = new Dimensions(hostMass.dimensions().width(), hostMass.dimensions().depth(),
                    Math.max(WATER_STILT_REACH, dims == null ? WATER_STILT_REACH : dims.height()));
        }
        FormacraftMod.LOGGER.info("PavilionOpenFrameRepair: force oak stilts host={}", hostId);
        return new Component("FOUNDATION", foundation.slotId(), rp, dims, foundation.features(), params);
    }

    private static Component injectOakStilts(Component mass, String hostId) {
        Vec3i origin = mass.relativePosition();
        Dimensions dims = mass.dimensions();
        int bottom = origin.y() - WATER_STILT_REACH;
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", hostId + "#stilts");
        params.put("host_id", hostId);
        params.put("foundation_type", "stilts");
        params.put("material", "minecraft:oak_log");
        params.put("anchor_mode", "min_corner");
        params.put("sides", 8);
        if (mass.params() != null) {
            for (String key : List.of("plan_type", "planType", "corner_cut", "cornerCut", "shape", "sides")) {
                if (mass.params().get(key) != null) params.put(key, mass.params().get(key));
            }
        }
        return new Component("FOUNDATION", mass.slotId(),
                new Vec3i(origin.x(), bottom, origin.z()),
                new Dimensions(dims.width(), dims.depth(), WATER_STILT_REACH),
                List.of("stilts", "pavilion posts"), params);
    }

    private static Component liftPavilionRoof(Component roof, Map<String, Component> massById,
                                              Set<String> pavilionMassIds) {
        if (roof.relativePosition() == null || roof.dimensions() == null) return roof;
        Object host = roof.params() == null ? null : roof.params().get("host_id");
        Component mass = host == null ? null : massById.get(String.valueOf(host));
        if (mass == null) {
            for (String id : pavilionMassIds) {
                Component candidate = massById.get(id);
                if (candidate != null && java.util.Objects.equals(candidate.slotId(), roof.slotId())) {
                    mass = candidate;
                    break;
                }
            }
        }
        if (mass == null || mass.relativePosition() == null || mass.dimensions() == null) return roof;
        if (!pavilionMassIds.contains(String.valueOf(mass.params().get("component_id")))
                && !OpenFramePolicy.isOpen(mass.params())) {
            return roof;
        }
        // Fully above post tops — height-1 sunk roofs into open-frame bodies.
        int targetY = mass.relativePosition().y() + mass.dimensions().height();
        if (roof.relativePosition().y() >= targetY) return roof;
        FormacraftMod.LOGGER.info(
                "PavilionOpenFrameRepair: lift roof Y {}→{} above open frame",
                roof.relativePosition().y(), targetY);
        Map<String, Object> params = new HashMap<>();
        if (roof.params() != null) params.putAll(roof.params());
        params.put("anchor_mode", "min_corner");
        return new Component(roof.componentType(), roof.slotId(),
                new Vec3i(roof.relativePosition().x(), targetY, roof.relativePosition().z()),
                roof.dimensions(), roof.features(), params);
    }

    private static Component extendStilts(Component foundation, Map<String, Integer> bodyFloorY,
                                          Map<String, Component> massById) {
        if (foundation == null || foundation.params() == null || foundation.relativePosition() == null
                || foundation.dimensions() == null) {
            return foundation;
        }
        if (!"FOUNDATION".equalsIgnoreCase(foundation.componentType()) || !isStilts(foundation.params())) {
            return foundation;
        }
        Object host = foundation.params().get("host_id");
        if (host == null) host = foundation.params().get("hostId");
        Integer floorY = host == null ? null : bodyFloorY.get(String.valueOf(host));
        Component hostMass = host == null ? null : massById.get(String.valueOf(host));
        Vec3i rp = foundation.relativePosition();
        int height = foundation.dimensions().height();
        int bottomY = rp.y();
        int width = foundation.dimensions().width();
        int depth = foundation.dimensions().depth();
        int x = rp.x();
        int z = rp.z();
        if (hostMass != null && hostMass.dimensions() != null && hostMass.relativePosition() != null) {
            width = hostMass.dimensions().width();
            depth = hostMass.dimensions().depth();
            x = hostMass.relativePosition().x();
            z = hostMass.relativePosition().z();
        }
        if (floorY != null) {
            int targetHeight = Math.max(Math.max(height, MIN_STILT_HEIGHT), WATER_STILT_REACH);
            bottomY = floorY - targetHeight;
            height = floorY - bottomY;
        } else if (height < MIN_STILT_HEIGHT) {
            height = MIN_STILT_HEIGHT;
        }
        Map<String, Object> params = new HashMap<>(foundation.params());
        params.put("foundation_type", "stilts");
        params.put("material", "minecraft:oak_log");
        params.putIfAbsent("sides", 8);
        if (hostMass != null && hostMass.params() != null) {
            for (String key : List.of("plan_type", "planType", "corner_cut", "cornerCut", "shape", "sides")) {
                if (hostMass.params().get(key) != null) {
                    params.put(key, hostMass.params().get(key));
                }
            }
        }
        if (height == foundation.dimensions().height() && bottomY == rp.y()
                && width == foundation.dimensions().width() && depth == foundation.dimensions().depth()
                && x == rp.x() && z == rp.z() && params.equals(foundation.params())) {
            return foundation;
        }
        FormacraftMod.LOGGER.info(
                "PavilionOpenFrameRepair: extend stilts host={} y={}→{} height {}→{}",
                host, rp.y(), bottomY, foundation.dimensions().height(), height);
        return new Component(foundation.componentType(), foundation.slotId(),
                new Vec3i(x, bottomY, z),
                new Dimensions(width, depth, height),
                foundation.features(), params);
    }

    private static boolean isStilts(Map<String, Object> params) {
        return Set.of("stilt", "stilts", "piles", "piling")
                .contains(String.valueOf(params.get("foundation_type")).toLowerCase(Locale.ROOT));
    }

    private static boolean looksPavilionPlan(LlmPlan plan) {
        if (plan.distinguishingFeatures() != null) {
            for (String f : plan.distinguishingFeatures()) {
                if (f != null && looksPavilionToken(f)) return true;
            }
        }
        if (plan.playerFidelityNoticeZh() != null && looksPavilionToken(plan.playerFidelityNoticeZh())) {
            return true;
        }
        if (plan.components() != null) {
            for (Component c : plan.components()) {
                if (c == null) continue;
                if (looksPavilion(c.features())) return true;
                if (c.params() != null && c.params().get("component_id") != null
                        && looksPavilionToken(String.valueOf(c.params().get("component_id")))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean looksStiltIntent(LlmPlan plan) {
        if (plan.distinguishingFeatures() != null) {
            for (String f : plan.distinguishingFeatures()) {
                if (f != null && looksStiltToken(f)) return true;
            }
        }
        if (plan.playerFidelityNoticeZh() != null && looksStiltToken(plan.playerFidelityNoticeZh())) {
            return true;
        }
        return false;
    }

    private static boolean looksPavilion(List<String> features) {
        if (features == null) return false;
        for (String f : features) {
            if (f != null && looksPavilionToken(f)) return true;
        }
        return false;
    }

    private static boolean looksPavilionToken(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        return lower.contains("pavilion") || lower.contains("gazebo") || lower.contains("open bay")
                || lower.contains("open-frame") || lower.contains("colonnade frame")
                || lower.contains("凉亭") || lower.contains("水上亭") || lower.contains("亭子")
                || lower.contains("水榭") || lower.contains("八角亭");
    }

    private static boolean looksStiltToken(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        return lower.contains("stilt") || lower.contains("架空") || lower.contains("木柱")
                || lower.contains("吊脚") || lower.contains("高脚") || lower.contains("pillar");
    }

    private static boolean looksOctagon(Component c) {
        if (c == null) return false;
        if (c.params() != null) {
            for (String key : List.of("shape", "footprint_shape", "plan_type", "planType", "component_id")) {
                Object v = c.params().get(key);
                if (v != null && looksOctagonToken(String.valueOf(v))) return true;
            }
        }
        return looksOctagonFeatures(c.features());
    }

    private static boolean looksOctagon(LlmPlan plan) {
        if (plan == null) return false;
        if (plan.distinguishingFeatures() != null) {
            for (String f : plan.distinguishingFeatures()) {
                if (f != null && looksOctagonToken(f)) return true;
            }
        }
        if (plan.playerFidelityNoticeZh() != null && looksOctagonToken(plan.playerFidelityNoticeZh())) {
            return true;
        }
        if (plan.components() != null) {
            for (Component c : plan.components()) {
                if (looksOctagon(c)) return true;
            }
        }
        return false;
    }

    private static boolean looksOctagonFeatures(List<String> features) {
        if (features == null) return false;
        for (String f : features) {
            if (f != null && looksOctagonToken(f)) return true;
        }
        return false;
    }

    private static boolean looksOctagonToken(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        return lower.contains("octagon") || lower.contains("octagonal")
                || lower.contains("八角") || lower.contains("八角形");
    }
}
