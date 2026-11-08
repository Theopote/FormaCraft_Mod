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
            Set<String> hostsForbiddingStilts = new HashSet<>();
            for (String hostId : pavilionMassIds) {
                Component mass = massById.get(hostId);
                if (mass == null) continue;
                StiltIntent intent = stiltIntentForMass(mass, plan);
                if (intent == StiltIntent.REQUIRED) {
                    hostsNeedingStilts.add(hostId);
                } else if (intent == StiltIntent.FORBIDDEN) {
                    hostsForbiddingStilts.add(hostId);
                }
            }
            // Player forbid beats LLM stilts foundations / mass foundation_type=stilts.
            if (!hostsForbiddingStilts.isEmpty()) {
                List<Component> stripped = new ArrayList<>(out.size());
                for (Component c : out) {
                    if (c == null) {
                        stripped.add(null);
                        continue;
                    }
                    if ("FOUNDATION".equalsIgnoreCase(c.componentType()) && c.params() != null && isStilts(c.params())) {
                        Object host = c.params().get("host_id");
                        if (host == null) host = c.params().get("hostId");
                        if (host != null && hostsForbiddingStilts.contains(String.valueOf(host))) {
                            FormacraftMod.LOGGER.info(
                                    "PavilionOpenFrameRepair: drop LLM stilts foundation host={}", host);
                            changed = true;
                            continue;
                        }
                    }
                    if (c.params() != null) {
                        Object id = c.params().get("component_id");
                        if (id != null && hostsForbiddingStilts.contains(String.valueOf(id))) {
                            Object ft = c.params().get("foundation_type");
                            if (ft != null && isStilts(Map.of("foundation_type", ft))) {
                                Map<String, Object> params = new HashMap<>(c.params());
                                params.remove("foundation_type");
                                params.put("foundation_mode", "GROUND");
                                c = new Component(c.componentType(), c.slotId(), c.relativePosition(),
                                        c.dimensions(), c.features(), params);
                                massById.put(String.valueOf(id), c);
                                changed = true;
                            }
                        }
                    }
                    stripped.add(c);
                }
                out = stripped;
                stiltHosts.removeIf(hostsForbiddingStilts::contains);
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

        // Raise stilts hosts so the deck clears MIN_STILT_HEIGHT of underfloor posts (y=0..2 void band).
        for (int i = 0; i < out.size(); i++) {
            Component c = out.get(i);
            if (c == null || c.params() == null || c.relativePosition() == null) continue;
            if (!"MASS_MAIN".equalsIgnoreCase(c.componentType())
                    && !"MAIN_MASS".equalsIgnoreCase(c.componentType())) {
                continue;
            }
            Object id = c.params().get("component_id");
            if (id == null || !stiltHosts.contains(String.valueOf(id))) continue;
            int floorY = c.relativePosition().y();
            int needFloor = MIN_STILT_HEIGHT;
            if (floorY >= needFloor) continue;
            int dy = needFloor - floorY;
            Component lifted = new Component(c.componentType(), c.slotId(),
                    new Vec3i(c.relativePosition().x(), floorY + dy, c.relativePosition().z()),
                    c.dimensions(), c.features(), c.params());
            out.set(i, lifted);
            massById.put(String.valueOf(id), lifted);
            bodyFloorY.put(String.valueOf(id), floorY + dy);
            shiftHostedBy(out, String.valueOf(id), 0, dy, 0);
            changed = true;
            FormacraftMod.LOGGER.info(
                    "PavilionOpenFrameRepair: raise stilts host {} by {} so deck clears underfloor posts",
                    id, dy);
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
        // Roman cella / dome temples are enclosed halls — never open-frame height-clamp to 3–5.
        if (looksEnclosedTempleCella(mass, id)) {
            return mass;
        }
        // Hanging gardens / ziggurats / stepped terrace monuments are not 凉亭 — a plan cue like
        // "summit shrine pavilion" must not crush a 35-high body to MAX_POST_HEIGHT.
        if (looksMonumentalSteppedMass(mass, id)) {
            return mass;
        }
        boolean pavilionNamed = idLooksPavilion(id)
                || looksPavilion(mass.features())
                || isExplicitPavilionMass(mass, id);
        // Plan-level「亭」only tags anonymous masses when this mass itself is pavilion-like
        // (never force open-frame onto every MASS_MAIN in a compound).
        if (planWantsPavilion && !pavilionNamed && looksPavilionToken(id)) {
            pavilionNamed = true;
        }
        // Connecting walkways / 廊 in a pavilion compound stay open colonnades (not solid halls).
        boolean galleryNamed = looksOpenGallery(mass, id);
        if (planWantsPavilion && galleryNamed) {
            pavilionNamed = true;
        }
        boolean hexagon = looksHexagon(mass);
        // Primary halls keep enclosed walls — but never over explicit pavilion / hex-oct footprints.
        if (isHallLikeMass(mass, id) && !pavilionNamed && !hexagon && !looksOctagon(mass)) {
            return mass;
        }
        boolean onStilts = stiltHosts.contains(String.valueOf(mass.params().get("component_id")));
        // English "pavilion" on a windowed flat-roof shell (midcentury etc.) is not 凉亭.
        if (pavilionNamed && !hexagon && !looksOctagon(mass) && !onStilts
                && looksEnclosedModernPavilion(mass)) {
            return mass;
        }
        // Octagon intent is per-mass — never let a sibling 八角亭 rewrite an explicit 六角亭.
        boolean octagon = !hexagon && (looksOctagon(mass) || (planWantsOctagon && pavilionNamed && !hasExplicitNonOctagonShape(mass)));
        if (!pavilionNamed && !onStilts && !octagon && !hexagon) return mass;
        // Stilt houses that are not 亭/gazebo keep enclosed walls above the underfloor void.
        // Forcing open-frame here plants body posts onto stilts cells and fails the nine-post contract.
        if (!pavilionNamed && onStilts && !octagon && !hexagon) {
            return repairStiltsHouseDeck(mass);
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
        // Square open frames authored at 0,0 should be centre-anchored so the slot origin is
        // interior air (not a min-corner post). Keep relativePosition so roofs/foundations stay glued.
        if (mass.relativePosition() != null && dims.width() >= 3 && dims.depth() >= 3
                && mass.relativePosition().x() == 0 && mass.relativePosition().z() == 0
                && !com.formacraft.common.generation.component.util.ComponentFootprintUtil.isCornerAnchor(params)
                && !"center".equalsIgnoreCase(String.valueOf(params.getOrDefault("anchor_mode", "")))) {
            params.put("anchor_mode", "center");
            changed = true;
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

    /**
     * Per-mass stilts policy.
     * Priority: player forbid &gt; player/mass require cues &gt; foundation_mode ground &gt; LLM stilts params
     * &gt; water/stilt name cues. LLM {@code foundation_type=stilts} never beats player「不要架空」.
     */
    enum StiltIntent { REQUIRED, FORBIDDEN, UNSPECIFIED }

    private static StiltIntent stiltIntentForMass(Component mass, LlmPlan plan) {
        if (mass == null) return StiltIntent.UNSPECIFIED;
        String massBlob = massIntentBlob(mass);
        // 1. Player explicit forbid (mass features/id or request text) beats LLM stilts params
        //    and weak water-name cues like「水上亭」.
        if (hasStiltForbidden(massBlob) || hasStiltForbidden(planIntentBlob(plan))) {
            return StiltIntent.FORBIDDEN;
        }
        if (mass.params() != null) {
            Object mode = mass.params().get("foundation_mode");
            if (mode != null) {
                String m = String.valueOf(mode).trim().toUpperCase(Locale.ROOT);
                if (m.equals("GROUND") || m.equals("PLINTH") || m.equals("PAD") || m.equals("NONE")) {
                    return StiltIntent.FORBIDDEN;
                }
                if (m.equals("STILTS") || m.equals("FLOATING") || m.equals("PILES")) {
                    return StiltIntent.REQUIRED;
                }
            }
            // 2. LLM stilts type — only after forbid checks.
            Object ft = mass.params().get("foundation_type");
            if (ft != null && isStilts(Map.of("foundation_type", ft))) {
                return StiltIntent.REQUIRED;
            }
        }
        // 3. Mass-local water / stilt name cues (no plan-level sibling bleed).
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
        if (hostMass != null && hostMass.params() != null) {
            for (String key : List.of("plan_type", "planType", "corner_cut", "cornerCut", "shape")) {
                if (hostMass.params().get(key) != null) params.put(key, hostMass.params().get(key));
            }
            // Copy sides only for true angular hosts — never paste sides=8 onto a square stilts pad.
            if (looksOctagon(hostMass) && hostMass.params().get("sides") != null) {
                params.put("sides", hostMass.params().get("sides"));
            }
        }
        int reach = stiltsReach(hostMass, foundation);
        Vec3i rp = foundation.relativePosition();
        Dimensions dims = foundation.dimensions();
        if (hostMass != null && hostMass.dimensions() != null && hostMass.relativePosition() != null) {
            rp = new Vec3i(hostMass.relativePosition().x(),
                    Math.min(rp == null ? hostMass.relativePosition().y() - reach : rp.y(),
                            hostMass.relativePosition().y() - reach),
                    hostMass.relativePosition().z());
            dims = new Dimensions(hostMass.dimensions().width(), hostMass.dimensions().depth(),
                    Math.max(reach, dims == null ? reach : dims.height()));
        }
        FormacraftMod.LOGGER.info("PavilionOpenFrameRepair: force oak stilts host={} reach={}", hostId, reach);
        return new Component("FOUNDATION", foundation.slotId(), rp, dims, foundation.features(), params);
    }

    private static Component injectOakStilts(Component mass, String hostId) {
        Vec3i origin = mass.relativePosition();
        Dimensions dims = mass.dimensions();
        int reach = stiltsReach(mass, null);
        int bottom = origin.y() - reach;
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", hostId + "#stilts");
        params.put("host_id", hostId);
        params.put("foundation_type", "stilts");
        params.put("material", "minecraft:oak_log");
        params.put("anchor_mode", "min_corner");
        if (mass.params() != null) {
            for (String key : List.of("plan_type", "planType", "corner_cut", "cornerCut", "shape")) {
                if (mass.params().get(key) != null) params.put(key, mass.params().get(key));
            }
            if (looksOctagon(mass) && mass.params().get("sides") != null) {
                params.put("sides", mass.params().get("sides"));
            }
        }
        return new Component("FOUNDATION", mass.slotId(),
                new Vec3i(origin.x(), bottom, origin.z()),
                new Dimensions(dims.width(), dims.depth(), reach),
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
            int reach = stiltsReach(hostMass, foundation);
            int targetHeight = Math.max(Math.max(height, MIN_STILT_HEIGHT), reach);
            bottomY = floorY - targetHeight;
            height = floorY - bottomY;
        } else if (height < MIN_STILT_HEIGHT) {
            height = MIN_STILT_HEIGHT;
        }
        Map<String, Object> params = new HashMap<>(foundation.params());
        params.put("foundation_type", "stilts");
        params.put("material", "minecraft:oak_log");
        params.remove("sides");
        if (hostMass != null && hostMass.params() != null) {
            for (String key : List.of("plan_type", "planType", "corner_cut", "cornerCut", "shape")) {
                if (hostMass.params().get(key) != null) {
                    params.put(key, hostMass.params().get(key));
                }
            }
            if (looksOctagon(hostMass) && hostMass.params().get("sides") != null) {
                params.put("sides", hostMass.params().get("sides"));
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
        if (plan.styleAttributes() != null && plan.styleAttributes().decorativeElements() != null) {
            for (String o : plan.styleAttributes().decorativeElements()) {
                if (o != null && looksPavilionToken(o)) return true;
            }
        }
        if (plan.layout() != null && plan.layout().slots() != null) {
            for (var slot : plan.layout().slots()) {
                if (slot != null && slot.slotId() != null && looksPavilionToken(slot.slotId())) {
                    return true;
                }
            }
        }
        if (plan.components() != null) {
            for (Component c : plan.components()) {
                if (c == null) continue;
                if (looksPavilion(c.features())) return true;
                if (c.params() != null && c.params().get("component_id") != null
                        && idLooksPavilion(String.valueOf(c.params().get("component_id")).toLowerCase(Locale.ROOT))) {
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

    /** Covered walkway / 廊 connecting open pavilions — not an enclosed hall. */
    private static boolean looksOpenGallery(Component mass, String idLower) {
        if (idLower != null && (idLower.contains("walk") || idLower.contains("gallery")
                || idLower.contains("corridor") || idLower.contains("colonnade")
                || idLower.contains("cloister") || idLower.contains("廊"))) {
            return true;
        }
        String slot = mass == null || mass.slotId() == null ? "" : mass.slotId().toLowerCase(Locale.ROOT);
        return slot.contains("walk") || slot.contains("gallery") || slot.contains("corridor")
                || slot.contains("colonnade") || slot.contains("cloister") || slot.contains("廊");
    }

    /**
     * Primary halls keep enclosed walls. Explicit pavilion role / 亭 tokens win over
     * weak name cues like {@code main} in {@code main_pavilion} or {@code slotId=main}.
     */
    private static boolean isHallLikeMass(Component mass, String idLower) {
        if (isExplicitPavilionMass(mass, idLower)) {
            return false;
        }
        if (looksOpenGallery(mass, idLower)) {
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

    /**
     * Windowed / flat-roof shells named pavilion (modern visitor centers) keep walls —
     * open-frame repair is for 亭 / gazebo / colonnade frames only.
     */
    private static boolean looksEnclosedModernPavilion(Component mass) {
        if (mass == null || mass.params() == null) return false;
        if (OpenFramePolicy.isOpen(mass.params())) return false;
        if (looksPavilion(mass.features())) return false;
        Object roof = mass.params().get("roof_type");
        if (roof == null) roof = mass.params().get("roofType");
        String roofType = roof == null ? "" : String.valueOf(roof).trim().toLowerCase(Locale.ROOT);
        if (!roofType.isEmpty() && !"none".equals(roofType) && !"flat".equals(roofType)) {
            return false;
        }
        Object wr = mass.params().get("window_ratio");
        if (wr == null) wr = mass.params().get("windowRatio");
        boolean glazed = wr instanceof Number n && n.doubleValue() > 0.05;
        Object facade = mass.params().get("facade_profile");
        if (facade == null) facade = mass.params().get("facadeProfile");
        boolean hasFacade = facade != null && !"none".equalsIgnoreCase(String.valueOf(facade).trim());
        Object voidRatio = mass.params().get("void_ratio");
        if (voidRatio == null) voidRatio = mass.params().get("voidRatio");
        boolean lowVoid = !(voidRatio instanceof Number n) || n.doubleValue() < 0.35;
        return (glazed || hasFacade) && lowVoid;
    }

    /** Pavilion id tokens — {@code ting} must not match inside {@code timber}/{@code casting}. */
    private static boolean idLooksPavilion(String idLower) {
        if (idLower == null || idLower.isBlank()) return false;
        if (idLower.contains("pavilion") || idLower.contains("gazebo") || idLower.contains("kiosk")
                || idLower.contains("shuixie") || idLower.contains("亭")) {
            return true;
        }
        // Fixture / LLM shorthand: pav1_main, pav_center, pav-east — not "paving".
        if (idLower.startsWith("pav") && !idLower.startsWith("pavement") && !idLower.startsWith("paving")
                && !idLower.startsWith("pavilion")) {
            char next = idLower.length() > 3 ? idLower.charAt(3) : '_';
            if (next == '_' || next == '-' || Character.isDigit(next)) return true;
        }
        return containsStandaloneToken(idLower, "ting");
    }

    /**
     * Enclosed stilts hut: square a knife-thin LLM pad to the long span (nine-post stilts contract)
     * and mark a solid deck so wall_thickness cannot hard-fail a 7×2 envelope.
     */
    private static Component repairStiltsHouseDeck(Component mass) {
        if (mass.dimensions() == null || mass.relativePosition() == null || mass.params() == null) {
            return mass;
        }
        Dimensions d = mass.dimensions();
        int span = Math.max(d.width(), d.depth());
        Map<String, Object> params = new HashMap<>(mass.params());
        boolean changed = false;
        Vec3i origin = mass.relativePosition();
        Dimensions dims = d;
        if (span >= 3 && (d.width() != span || d.depth() != span)) {
            int dx = (span - d.width()) / 2;
            int dz = (span - d.depth()) / 2;
            origin = new Vec3i(origin.x() - dx, origin.y(), origin.z() - dz);
            dims = new Dimensions(span, span, d.height());
            params.put("anchor_mode", "min_corner");
            changed = true;
        }
        if (!params.containsKey("void_ratio") && !params.containsKey("voidRatio")) {
            params.put("void_ratio", 0);
            changed = true;
        }
        if (!changed) return mass;
        FormacraftMod.LOGGER.info(
                "PavilionOpenFrameRepair: square stilts deck id={} {}x{} → {}x{}",
                params.get("component_id"), d.width(), d.depth(), dims.width(), dims.depth());
        return new Component(mass.componentType(), mass.slotId(), origin, dims, mass.features(), params);
    }

    /**
     * Lake / 水上亭 stilts reach deep plan-space; land stilts houses keep a short underfloor band
     * ({@link #MIN_STILT_HEIGHT}) so decks sit on y=3 with posts at y=0..2.
     */
    private static int stiltsReach(Component mass, Component foundation) {
        if (wantsDeepWaterStilts(mass) || wantsDeepWaterStilts(foundation)) {
            return WATER_STILT_REACH;
        }
        return MIN_STILT_HEIGHT;
    }

    private static boolean wantsDeepWaterStilts(Component c) {
        if (c == null) return false;
        String blob = massIntentBlob(c);
        String lower = blob.toLowerCase(Locale.ROOT);
        return lower.contains("water") || lower.contains("lake") || lower.contains("pond")
                || lower.contains("水上") || lower.contains("水榭") || lower.contains("湖")
                || lower.contains("overwater") || lower.contains("over_water");
    }

    private static void shiftHostedBy(List<Component> parts, String hostId, int dx, int dy, int dz) {
        if (hostId == null || (dx == 0 && dy == 0 && dz == 0)) return;
        for (int i = 0; i < parts.size(); i++) {
            Component c = parts.get(i);
            if (c == null || c.params() == null || c.relativePosition() == null) continue;
            Object host = c.params().get("host_id");
            if (host == null) host = c.params().get("hostId");
            if (host == null || !hostId.equals(String.valueOf(host))) continue;
            parts.set(i, new Component(c.componentType(), c.slotId(),
                    new Vec3i(c.relativePosition().x() + dx, c.relativePosition().y() + dy,
                            c.relativePosition().z() + dz),
                    c.dimensions(), c.features(), c.params()));
        }
    }

    /**
     * Stepped terrace monuments (ziggurat / hanging gardens) stay enclosed at authored height.
     * Nested {@code masses[]} tiers or large multi-storey envelopes are the tell.
     */
    private static boolean looksMonumentalSteppedMass(Component mass, String idLower) {
        if (mass == null) return false;
        if (idLower != null && (idLower.contains("ziggurat") || idLower.contains("金字塔")
                || idLower.contains("pyramid") || idLower.contains("hanging_garden")
                || idLower.contains("空中花园") || idLower.contains("terrace_tower"))) {
            return true;
        }
        if (mass.params() != null && mass.params().get("masses") instanceof java.util.List<?> tiers
                && tiers.size() >= 3
                && mass.dimensions() != null
                && mass.dimensions().height() >= 12
                && Math.min(mass.dimensions().width(), mass.dimensions().depth()) >= 16) {
            return true;
        }
        if (mass.features() != null) {
            for (String f : mass.features()) {
                if (f == null) continue;
                String lower = f.toLowerCase(Locale.ROOT);
                if (lower.contains("hanging-garden") || lower.contains("hanging garden")
                        || lower.contains("stepped terrace") || lower.contains("ziggurat")
                        || lower.contains("空中花园")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Circular cella / Roman temple body — must keep full authored height and solid walls. */
    private static boolean looksEnclosedTempleCella(Component mass, String idLower) {
        if (mass == null) return false;
        if (idLower != null && (idLower.contains("cella") || idLower.contains("naos")
                || idLower.contains("神殿") || (idLower.contains("temple") && !idLower.contains("pavilion")))) {
            return true;
        }
        if (mass.params() != null) {
            Object roof = mass.params().get("roof_type");
            if (roof == null) roof = mass.params().get("roofType");
            String roofType = roof == null ? "" : String.valueOf(roof).trim().toLowerCase(Locale.ROOT);
            Object shape = mass.params().get("shape");
            String shapeText = shape == null ? "" : String.valueOf(shape).trim().toLowerCase(Locale.ROOT);
            if (("dome".equals(roofType) || "hemisphere".equals(roofType))
                    && (shapeText.contains("circle") || shapeText.contains("circular")
                    || "cylinder".equals(shapeText))) {
                return true;
            }
        }
        if (mass.features() != null) {
            for (String f : mass.features()) {
                if (f == null) continue;
                String lower = f.toLowerCase(Locale.ROOT);
                if (lower.contains("cella") || lower.contains("circular_cella")
                        || lower.contains("roman_temple") || lower.contains("hemispherical")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsStandaloneToken(String haystack, String token) {
        int from = 0;
        while (from <= haystack.length() - token.length()) {
            int i = haystack.indexOf(token, from);
            if (i < 0) return false;
            char before = i == 0 ? '_' : haystack.charAt(i - 1);
            int end = i + token.length();
            char after = end >= haystack.length() ? '_' : haystack.charAt(end);
            if (!Character.isLetterOrDigit(before) && !Character.isLetterOrDigit(after)) {
                return true;
            }
            from = i + 1;
        }
        return false;
    }

    private static boolean isExplicitPavilionMass(Component mass, String idLower) {
        if (idLooksPavilion(idLower)) {
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
