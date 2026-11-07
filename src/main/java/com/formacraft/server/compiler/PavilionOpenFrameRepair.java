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
            Component repaired = repairMass(c, stiltHosts, planWantsOctagon, planWantsPavilion);
            if (repaired != c) changed = true;
            out.add(repaired);
            if (repaired != null && repaired.params() != null && repaired.relativePosition() != null
                    && ("MASS_MAIN".equalsIgnoreCase(repaired.componentType())
                    || "MAIN_MASS".equalsIgnoreCase(repaired.componentType()))) {
                Object id = repaired.params().get("component_id");
                if (id != null) {
                    String idStr = String.valueOf(id);
                    bodyFloorY.put(idStr, repaired.relativePosition().y());
                    // Only open-frame / pavilion-named masses — never every MASS_MAIN because
                    // the plan happens to contain a pavilion (hall+pavilion compounds).
                    // Hall-like bodies must never enter stilts injection even if open=true leaked in.
                    if (!isHallLikeMass(repaired, idStr.toLowerCase(Locale.ROOT))
                            && (OpenFramePolicy.isOpen(repaired.params())
                            || looksPavilion(repaired.features())
                            || looksPavilionToken(idStr))) {
                        pavilionMassIds.add(idStr);
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

        // Stilts are per-mass (REQUIRED / FORBIDDEN / UNSPECIFIED) — never plan-global bleed.
        if (!pavilionMassIds.isEmpty()) {
            Set<String> hostsNeedingStilts = new HashSet<>();
            for (String hostId : pavilionMassIds) {
                Component mass = massById.get(hostId);
                if (mass != null && stiltIntentForMass(mass, plan) == StiltIntent.REQUIRED) {
                    hostsNeedingStilts.add(hostId);
                }
            }
            if (!hostsNeedingStilts.isEmpty()) {
                Set<String> hostsWithStilts = new HashSet<>();
                for (int i = 0; i < out.size(); i++) {
                    Component c = out.get(i);
                    if (c == null || !"FOUNDATION".equalsIgnoreCase(c.componentType()) || c.params() == null) continue;
                    Object host = c.params().get("host_id");
                    if (host == null) host = c.params().get("hostId");
                    if (host == null || !hostsNeedingStilts.contains(String.valueOf(host))) continue;
                    // Preserve authored stone plinths / non-stilt pads — do not force oak piles.
                    if (!isStilts(c.params()) && looksSolidPlinth(c)) {
                        hostsWithStilts.add(String.valueOf(host));
                        continue;
                    }
                    Component forced = forceOakStilts(c, String.valueOf(host), massById.get(String.valueOf(host)));
                    if (!forced.equals(c)) {
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
                || id.contains("ting") || id.contains("shuixie") || id.contains("亭")
                || looksPavilion(mass.features())
                || isExplicitPavilionMass(mass, id);
        // Plan-level「亭」only tags anonymous masses when this mass itself is pavilion-like
        // (never force open-frame onto every MASS_MAIN in a compound).
        if (planWantsPavilion && !pavilionNamed && looksPavilionToken(id)) {
            pavilionNamed = true;
        }
        boolean hexagon = looksHexagon(mass);
        // Primary halls keep enclosed walls — but never over explicit pavilion / hex-oct footprints.
        if (isHallLikeMass(mass, id) && !pavilionNamed && !hexagon && !looksOctagon(mass)) {
            return mass;
        }
        boolean onStilts = stiltHosts.contains(String.valueOf(mass.params().get("component_id")));
        // Octagon intent is per-mass — never let a sibling 八角亭 rewrite an explicit 六角亭.
        boolean octagon = !hexagon && (looksOctagon(mass) || (planWantsOctagon && pavilionNamed && !hasExplicitNonOctagonShape(mass)));
        if (!pavilionNamed && !onStilts && !octagon && !hexagon) return mass;
        if (!pavilionNamed && onStilts && mass.dimensions().height() <= 3 && !octagon && !hexagon
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
        // Open-frame already skips embedded roofs (MassMainGenerator: hasRoof && !openRoof).
        // Missing roof → soft pyramid default. Verified / hard roof_type=none stays none.
        String existingRoof = params.get("roof_type") == null ? null
                : String.valueOf(params.get("roof_type")).trim();
        if (existingRoof == null || existingRoof.isBlank()) {
            String preferred = preferredPavilionRoofType(mass, hexagon);
            params.put("roof_type", preferred);
            params.put("preferred_roof_type", preferred);
            params.remove("compiler_suppressed_roof");
        } else if ("none".equalsIgnoreCase(existingRoof) && !isHardRoofNone(params, mass)) {
            String preferred = preferredPavilionRoofType(mass, hexagon);
            params.put("roof_type", preferred);
            params.put("preferred_roof_type", preferred);
            params.remove("compiler_suppressed_roof");
        }
        changed = true;
        if (hexagon) {
            params.put("shape", "hexagon");
            params.put("sides", 6);
            Object planType = params.get("plan_type");
            if (planType == null) planType = params.get("planType");
            if (planType != null && looksOctagonToken(String.valueOf(planType))) {
                params.put("plan_type", "none");
            }
            changed = true;
        } else if (octagon) {
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
        boolean hardHeight = isHardPostHeight(params);
        if (pavilionNamed || onStilts || OpenFramePolicy.isOpen(params)) {
            // 3–5 is a soft recommendation — never clamp USER_EXPLICIT / sealed heights.
            if (height > MAX_POST_HEIGHT && !hardHeight) {
                height = MAX_POST_HEIGHT;
                changed = true;
            } else if (height < MIN_POST_HEIGHT && (pavilionNamed || onStilts) && !hardHeight) {
                height = MIN_POST_HEIGHT;
                changed = true;
            }
        }
        if (hardHeight) {
            // Keep params.height aligned with dimensions when the player locked the post height.
            Object ph = params.get("height");
            if (ph instanceof Number n && n.intValue() > 0 && n.intValue() != height) {
                height = n.intValue();
                changed = true;
            } else if (height != dims.height()) {
                params.put("height", height);
            }
        }
        if (!changed && height == dims.height()) return mass;
        FormacraftMod.LOGGER.info(
                "PavilionOpenFrameRepair: pavilion id={} height {}→{} stilts={} octagon={} hardHeight={}",
                id, dims.height(), height, onStilts, octagon, hardHeight);
        if (height != dims.height() && params.get("height") instanceof Number) {
            params.put("height", height);
        }
        return new Component(mass.componentType(), mass.slotId(), mass.relativePosition(),
                new Dimensions(dims.width(), dims.depth(), height), mass.features(), params);
    }

    /** Soft default roof for open pavilions — 攒尖/hex → pyramid. */
    private static String preferredPavilionRoofType(Component mass, boolean hexagon) {
        if (hexagon || looksHexagon(mass)) return "pyramid";
        if (mass.features() != null) {
            for (String f : mass.features()) {
                if (f == null) continue;
                String lower = f.toLowerCase(Locale.ROOT);
                if (lower.contains("攒尖") || lower.contains("pyramid") || lower.contains("pointed")) {
                    return "pyramid";
                }
            }
        }
        return "pyramid";
    }

    private static boolean isHardPostHeight(Map<String, Object> params) {
        if (params == null) return false;
        if (com.formacraft.common.typology.UserConstraintContract.isServerVerified(params, "height")
                || com.formacraft.common.typology.UserConstraintContract.isServerVerified(params, "towerHeight")) {
            return true;
        }
        if (com.formacraft.common.typology.TypologyParamResolver.isUserHardConstraint(params, "height", "towerHeight")) {
            return true;
        }
        Object level = params.get("requirement_level");
        return level != null && "USER_REQUIRED".equalsIgnoreCase(String.valueOf(level).trim());
    }

    /** Per-mass stilts policy — FORBIDDEN always beats automatic REQUIRED inference. */
    enum StiltIntent { REQUIRED, FORBIDDEN, UNSPECIFIED }

    private static StiltIntent stiltIntentForMass(Component mass, LlmPlan plan) {
        if (mass == null) return StiltIntent.UNSPECIFIED;
        // Authored stilts foundation on the mass itself is hard REQUIRED.
        if (mass.params() != null) {
            Object ft = mass.params().get("foundation_type");
            if (ft != null && isStilts(Map.of("foundation_type", ft))) {
                return StiltIntent.REQUIRED;
            }
            Object mode = mass.params().get("foundation_mode");
            if (mode != null) {
                String m = String.valueOf(mode).trim().toUpperCase(Locale.ROOT);
                if (m.equals("STILTS") || m.equals("FLOATING") || m.equals("PILES")) {
                    return StiltIntent.REQUIRED;
                }
                if (m.equals("GROUND") || m.equals("PLINTH") || m.equals("PAD") || m.equals("NONE")) {
                    return StiltIntent.FORBIDDEN;
                }
            }
        }
        String massBlob = massIntentBlob(mass);
        if (hasStiltForbidden(massBlob)) {
            return StiltIntent.FORBIDDEN;
        }
        // Plan-level「不需要架空」forbids auto stilts unless this mass itself is water/stilt named.
        if (hasStiltForbidden(planIntentBlob(plan)) && !massLocalRequiresStilts(mass, massBlob)) {
            return StiltIntent.FORBIDDEN;
        }
        if (massLocalRequiresStilts(mass, massBlob)) {
            return StiltIntent.REQUIRED;
        }
        return StiltIntent.UNSPECIFIED;
    }

    /** Mass-local stilts / water cues only — never plan-global sibling bleed. */
    private static boolean massLocalRequiresStilts(Component mass, String massBlob) {
        if (mass != null && mass.params() != null) {
            Object id = mass.params().get("component_id");
            if (id != null && looksPositiveStiltToken(String.valueOf(id))) return true;
        }
        if (mass != null && mass.features() != null) {
            for (String f : mass.features()) {
                if (f != null && looksPositiveStiltToken(f)) return true;
            }
        }
        String lower = massBlob == null ? "" : massBlob.toLowerCase(Locale.ROOT);
        return lower.contains("水上") || lower.contains("water") || lower.contains("lake")
                || lower.contains("overwater") || lower.contains("pier")
                || lower.contains("吊脚") || lower.contains("高脚");
    }

    private static String massIntentBlob(Component mass) {
        if (mass == null) return "";
        StringBuilder sb = new StringBuilder();
        if (mass.params() != null && mass.params().get("component_id") != null) {
            sb.append(mass.params().get("component_id")).append(' ');
        }
        if (mass.features() != null) {
            for (String f : mass.features()) {
                if (f != null) sb.append(f).append(' ');
            }
        }
        return sb.toString();
    }

    private static String planIntentBlob(LlmPlan plan) {
        if (plan == null) return "";
        StringBuilder sb = new StringBuilder();
        if (plan.distinguishingFeatures() != null) {
            for (String f : plan.distinguishingFeatures()) {
                if (f != null) sb.append(f).append(' ');
            }
        }
        if (plan.playerFidelityNoticeZh() != null) {
            sb.append(plan.playerFidelityNoticeZh()).append(' ');
        }
        if (plan.planMeta() != null) {
            Object intent = plan.planMeta().get("request_intent");
            if (intent instanceof Map<?, ?> map) {
                Object text = map.get("original_text");
                if (text != null) sb.append(text).append(' ');
            }
        }
        return sb.toString();
    }

    private static boolean hasStiltForbidden(String blob) {
        if (blob == null || blob.isBlank()) return false;
        String lower = blob.toLowerCase(Locale.ROOT);
        return lower.contains("不需要架空") || lower.contains("不要架空") || lower.contains("无需架空")
                || lower.contains("不用架空") || lower.contains("不架空")
                || lower.contains("no stilts") || lower.contains("without stilts")
                || lower.contains("not elevated") || lower.contains("no elevated")
                || lower.contains("ground level") || lower.contains("落地");
    }

    private static boolean isHardRoofNone(Map<String, Object> params, Component mass) {
        if (params != null) {
            if (com.formacraft.common.typology.UserConstraintContract.isServerVerified(params, "roof_type")
                    || com.formacraft.common.typology.UserConstraintContract.isServerVerified(params, "roofType")) {
                return true;
            }
            if (com.formacraft.common.typology.TypologyParamResolver.isUserHardConstraint(
                    params, "roof_type", "roofType")) {
                return true;
            }
            Object level = params.get("requirement_level");
            if (level != null && "USER_REQUIRED".equalsIgnoreCase(String.valueOf(level).trim())) {
                return true;
            }
        }
        if (mass != null && mass.features() != null) {
            for (String f : mass.features()) {
                if (f == null) continue;
                String lower = f.toLowerCase(Locale.ROOT);
                if (lower.contains("无屋顶") || lower.contains("无顶") || lower.contains("open colonnade")
                        || lower.contains("roofless") || lower.contains("no roof")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean looksSolidPlinth(Component foundation) {
        if (foundation == null) return false;
        String id = "";
        if (foundation.params() != null) {
            Object raw = foundation.params().get("component_id");
            if (raw != null) id = String.valueOf(raw);
            Object mat = foundation.params().get("material");
            if (mat != null) {
                String m = String.valueOf(mat).toLowerCase(Locale.ROOT);
                if (m.contains("stone") || m.contains("brick") || m.contains("andesite")
                        || m.contains("deepslate")) {
                    return true;
                }
            }
        }
        String lower = id.toLowerCase(Locale.ROOT);
        return lower.contains("plinth") || id.contains("台基") || lower.contains("foundation")
                || lower.contains("base");
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

    private static boolean looksPavilion(List<String> features) {
        if (features == null) return false;
        for (String f : features) {
            if (f != null && looksPavilionToken(f)) return true;
        }
        return false;
    }

    private static boolean looksPavilionToken(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String lower = raw.toLowerCase(Locale.ROOT);
        return lower.contains("pavilion") || lower.contains("gazebo") || lower.contains("open bay")
                || lower.contains("open-frame") || lower.contains("colonnade frame")
                || lower.contains("凉亭") || lower.contains("水上亭") || lower.contains("亭子")
                || lower.contains("水榭")                 || lower.contains("八角亭") || lower.contains("六角亭")
                || lower.contains("四角亭") || lower.contains("攒尖亭") || lower.contains("方亭")
                || (lower.contains("hexagon") && (lower.contains("pavilion") || raw.contains("亭")))
                || "亭".equals(raw.trim());
    }

    /**
     * Primary halls keep enclosed walls. Explicit pavilion role / 亭 tokens win over
     * weak name cues like {@code main} in {@code main_pavilion} or {@code slotId=main}.
     */
    private static boolean isHallLikeMass(Component mass, String idLower) {
        if (isExplicitPavilionMass(mass, idLower)) {
            return false;
        }
        if (mass.params() != null) {
            Object role = mass.params().get("building_role");
            if (role == null) role = mass.params().get("buildingRole");
            if (role != null) {
                String r = String.valueOf(role).toLowerCase(Locale.ROOT);
                if (r.contains("hall") || r.equals("main") || r.contains("primary")
                        || r.contains("殿") || r.contains("堂")) {
                    return true;
                }
            }
            Object typology = mass.params().get("typology_id");
            if (typology == null) typology = mass.params().get("typologyId");
            if (typology != null) {
                String t = String.valueOf(typology).toLowerCase(Locale.ROOT);
                if (t.contains("pavilion") || t.contains("gazebo") || t.contains("ting")) {
                    return false;
                }
            }
        }
        // Bare "main" / slot main is not hall — need hall/house/殿 or *_main hall carriers.
        if (idLower != null && (idLower.contains("hall") || idLower.contains("殿")
                || idLower.contains("堂") || idLower.contains("house")
                || idLower.endsWith("_main") || idLower.equals("main"))) {
            return true;
        }
        if (mass.features() != null) {
            for (String f : mass.features()) {
                if (f == null) continue;
                String lower = f.toLowerCase(Locale.ROOT);
                if (lower.contains("main_hall") || lower.contains("正殿") || lower.contains("主殿")
                        || lower.contains("正房") || (lower.contains("hall") && !looksPavilionToken(f))) {
                    return true;
                }
            }
        }
        String slot = mass.slotId() == null ? "" : mass.slotId().toLowerCase(Locale.ROOT);
        // slotId=main only means primary carrier — not hall typology.
        return slot.contains("hall") || slot.contains("殿") || slot.contains("堂");
    }

    private static boolean isExplicitPavilionMass(Component mass, String idLower) {
        if (idLower != null && (idLower.contains("pavilion") || idLower.contains("gazebo")
                || idLower.contains("kiosk") || idLower.contains("ting") || idLower.contains("shuixie")
                || idLower.contains("亭"))) {
            return true;
        }
        if (mass != null && mass.params() != null) {
            Object role = mass.params().get("building_role");
            if (role == null) role = mass.params().get("buildingRole");
            if (role != null) {
                String r = String.valueOf(role).toLowerCase(Locale.ROOT);
                if (r.contains("pavilion") || r.contains("gazebo") || r.contains("ting") || r.contains("亭")) {
                    return true;
                }
            }
        }
        return mass != null && looksPavilion(mass.features());
    }

    /** Positive stilts / elevated tokens only — 木柱/pillar are post framing, not stilts. */
    private static boolean looksPositiveStiltToken(String raw) {
        if (raw == null || raw.isBlank()) return false;
        if (hasStiltForbidden(raw)) return false;
        String lower = raw.toLowerCase(Locale.ROOT);
        return lower.contains("stilt") || lower.contains("架空")
                || lower.contains("吊脚") || lower.contains("高脚")
                || lower.contains("piling") || lower.contains("piles");
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

    private static boolean looksHexagon(Component c) {
        if (c == null) return false;
        if (c.params() != null) {
            for (String key : List.of("shape", "footprint_shape", "footprintShape", "plan_type", "planType",
                    "component_id", "sides")) {
                Object v = c.params().get(key);
                if (v == null) continue;
                if ("sides".equals(key)) {
                    try {
                        if (Integer.parseInt(String.valueOf(v).trim()) == 6) return true;
                    } catch (NumberFormatException ignored) {
                        // fall through
                    }
                    continue;
                }
                if (looksHexagonToken(String.valueOf(v))) return true;
            }
        }
        if (c.features() != null) {
            for (String f : c.features()) {
                if (f != null && looksHexagonToken(f)) return true;
            }
        }
        return false;
    }

    private static boolean looksHexagonToken(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String lower = raw.toLowerCase(Locale.ROOT);
        return lower.contains("hexagon") || lower.contains("hexagonal")
                || lower.contains("六角") || lower.contains("六角形") || lower.contains("6gon");
    }

    /** True when this mass already authored a non-octagon footprint that must not be remapped. */
    private static boolean hasExplicitNonOctagonShape(Component mass) {
        if (mass == null || mass.params() == null) return false;
        if (looksHexagon(mass)) return true;
        for (String key : com.formacraft.common.generation.component.util.FootprintShapeCatalog.SHAPE_KEYS) {
            Object v = mass.params().get(key);
            if (v == null) continue;
            String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
            if (s.isEmpty() || looksOctagonToken(s)) continue;
            if (com.formacraft.common.generation.component.util.FootprintShapeCatalog.CIRCLE_TOKENS.contains(s)
                    || com.formacraft.common.generation.component.util.FootprintShapeCatalog.ROUNDED_RECT_TOKENS.contains(s)
                    || com.formacraft.common.generation.component.util.FootprintShapeCatalog.HEXAGON_TOKENS.contains(s)) {
                return true;
            }
        }
        return false;
    }
}
