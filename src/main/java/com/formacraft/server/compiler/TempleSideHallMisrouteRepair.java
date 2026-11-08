package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Classical-monument degrade of 「重檐歇山大雄宝殿 + 左右配殿 + 月台」prompts lands as a
 * single MASS with pilaster DECOR / cupola CROWN and no side halls. Recover the compound:
 * strip classical ornament, force double-eave xieshan on the main hall, inject left/right
 * 配殿 (悬山) and a front 月台, and sane entrance / wrap windows.
 */
final class TempleSideHallMisrouteRepair {
    private TempleSideHallMisrouteRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) {
            return plan;
        }
        String blob = blob(plan);
        if (!looksChineseHall(blob)) {
            return plan;
        }
        boolean wantPeidian = asksForSideHalls(blob);
        boolean wantYuetai = asksForMoonPlatform(blob);
        if (!wantPeidian && !wantYuetai && !looksClassicalDegrade(plan, blob)) {
            return plan;
        }
        boolean hasSide = hasSideHalls(plan);
        boolean hasPlatform = hasMoonPlatform(plan);
        if (hasSide && hasPlatform && !looksClassicalDegrade(plan, blob)) {
            return plan;
        }

        Component main = findPrimaryMass(plan);
        if (main == null || main.dimensions() == null || main.relativePosition() == null) {
            return plan;
        }

        List<Component> out = new ArrayList<>();
        String resolvedMainId = idOf(main);
        if (resolvedMainId.isBlank()) {
            resolvedMainId = "temple_hall";
        }
        final String mainId = resolvedMainId;
        Dimensions md = main.dimensions();
        Vec3i mp = main.relativePosition();
        int hallW = Math.max(9, md.width());
        int hallD = Math.max(7, md.depth());
        int hallH = Math.max(8, md.height());
        boolean doubleEave = blob.contains("重檐") || blob.contains("double_eave");
        String mainRoofType = (blob.contains("歇山") || blob.contains("xieshan")) ? "xieshan" : "hip";
        String sideRoof = blob.contains("硬山") ? "yingshan" : "xuanshan";

        // Drop classical ornament that produces floating cornice / cupola frames.
        for (Component c : plan.components()) {
            if (c == null) continue;
            String t = typeOf(c);
            if ("CROWN".equals(t) || "CUPOLA".equals(t) || "DOME".equals(t)) {
                continue;
            }
            if ("DECOR_DETAIL".equals(t) && isClassicalDecor(c)) {
                continue;
            }
            if (isPrimaryMass(c) || isMainRoof(c, mainId)) {
                out.add(enrichMainOrRoof(c, mainId, mainRoofType, doubleEave));
            } else if ("ENTRANCE".equals(t)) {
                out.add(sanitizeEntrance(c, mainId, hallW));
            } else if ("FACADE_WINDOWS".equals(t) || "FACADE".equals(t)) {
                out.add(sanitizeFacade(c, mainId, hallW, hallD, hallH));
            } else if ("FOUNDATION".equals(t)) {
                out.add(c);
            } else if (wantPeidian && looksLikeSideHall(c)) {
                out.add(c);
            } else if (wantYuetai && looksLikePlatform(c)) {
                out.add(c);
            } else if (!isClassicalDecor(c)) {
                // Keep non-classical satellites (paths, etc.).
                out.add(c);
            }
        }

        // Ensure primary mass + roof exist after filtering.
        if (out.stream().noneMatch(TempleSideHallMisrouteRepair::isPrimaryMass)) {
            Map<String, Object> params = new HashMap<>();
            if (main.params() != null) params.putAll(main.params());
            params.put("component_id", mainId);
            params.put("roof_type", mainRoofType);
            params.put("preferred_roof_type", mainRoofType);
            params.put("anchor_mode", "min_corner");
            if (doubleEave) {
                params.put("double_eave", true);
                params.put("eave_tiers", 2);
            }
            params.remove("facade_profile");
            out.addFirst(new Component(
                    "MASS_MAIN", main.slotId() == null ? "hall" : main.slotId(),
                    new Vec3i(mp.x(), mp.y(), mp.z()),
                    new Dimensions(hallW, hallD, hallH),
                    List.of("main_hall", "temple", "大雄宝殿"),
                    params));
        }
        if (out.stream().noneMatch(c -> isMainRoof(c, mainId))) {
            Map<String, Object> roofParams = new HashMap<>();
            roofParams.put("component_id", mainId + "_roof");
            roofParams.put("host_id", mainId);
            roofParams.put("roof_type", mainRoofType);
            roofParams.put("anchor_mode", "min_corner");
            if (doubleEave) {
                roofParams.put("double_eave", true);
                roofParams.put("eave_tiers", 2);
            }
            out.add(new Component(
                    "ROOF", main.slotId() == null ? "hall" : main.slotId(),
                    new Vec3i(mp.x() - 1, mp.y() + hallH, mp.z() - 1),
                    new Dimensions(hallW + 2, hallD + 2, doubleEave ? 5 : 4),
                    List.of("temple_roof", mainRoofType),
                    roofParams));
        }

        if (wantYuetai && out.stream().noneMatch(TempleSideHallMisrouteRepair::looksLikePlatform)) {
            int platW = Math.max(8, hallW - 2);
            int platD = 6;
            Map<String, Object> plat = new HashMap<>();
            plat.put("component_id", "temple_yuetai");
            plat.put("host_id", mainId);
            plat.put("extrude_mode", "plate");
            plat.put("plate_role", "terrace");
            plat.put("anchor_mode", "min_corner");
            out.add(new Component(
                    "MASS_SECONDARY", "platform",
                    new Vec3i(mp.x() + (hallW - platW) / 2, mp.y(), mp.z() + hallD),
                    new Dimensions(platW, platD, 1),
                    List.of("月台", "moon_platform"),
                    plat));
        }

        if (wantPeidian && !hasSideHalls(PlanComponentRewrite.withComponents(plan, out))) {
            int sideW = Math.max(7, hallW / 2);
            int sideD = Math.max(7, hallD - 2);
            int sideH = Math.max(6, hallH - 2);
            int gap = 3;
            GlobalConstraints.Facing facing = GlobalConstraints.Facing.SOUTH;
            if (plan.globalConstraints() != null && plan.globalConstraints().facing() != null) {
                facing = plan.globalConstraints().facing();
            }
            // Left/right relative to the hall front — not always world east/west.
            int leftX;
            int leftZ;
            int rightX;
            int rightZ;
            switch (facing) {
                case NORTH -> {
                    // Front north → left=west, right=east
                    leftX = mp.x() - gap - sideW;
                    rightX = mp.x() + hallW + gap;
                    leftZ = rightZ = mp.z() + Math.max(0, (hallD - sideD) / 2);
                }
                case EAST -> {
                    // Front east → left=south, right=north
                    leftX = rightX = mp.x() + Math.max(0, (hallW - sideW) / 2);
                    leftZ = mp.z() + hallD + gap;
                    rightZ = mp.z() - gap - sideD;
                }
                case WEST -> {
                    // Front west → left=north, right=south
                    leftX = rightX = mp.x() + Math.max(0, (hallW - sideW) / 2);
                    leftZ = mp.z() - gap - sideD;
                    rightZ = mp.z() + hallD + gap;
                }
                default -> {
                    // Front south → left=east, right=west
                    leftX = mp.x() + hallW + gap;
                    rightX = mp.x() - gap - sideW;
                    leftZ = rightZ = mp.z() + Math.max(0, (hallD - sideD) / 2);
                }
            }
            out.add(sideHall("left_peidian", leftX, mp.y(), leftZ, sideW, sideD, sideH, sideRoof));
            out.add(sideRoofComp("left_peidian", leftX, mp.y() + sideH, leftZ, sideW, sideD, sideRoof));
            out.add(sideHall("right_peidian", rightX, mp.y(), rightZ, sideW, sideD, sideH, sideRoof));
            out.add(sideRoofComp("right_peidian", rightX, mp.y() + sideH, rightZ, sideW, sideD, sideRoof));
        }

        // Ensure a readable central door exists after classical door_width=30 envelopes are sanitized.
        if (out.stream().noneMatch(c -> "ENTRANCE".equals(typeOf(c)))) {
            int doorW = Math.max(3, Math.min(5, hallW / 5));
            Map<String, Object> door = new HashMap<>();
            door.put("component_id", mainId + "_entrance");
            door.put("host_id", mainId);
            door.put("door_width", doorW);
            door.put("door_height", 4);
            door.put("anchor_mode", "min_corner");
            out.add(new Component(
                    "ENTRANCE", main.slotId() == null ? "hall" : main.slotId(),
                    new Vec3i(mp.x() + Math.max(0, (hallW - doorW) / 2), mp.y(), mp.z() + hallD - 1),
                    new Dimensions(doorW + 2, 1, 5),
                    List.of("main_entrance"),
                    door));
        }
        if (out.stream().noneMatch(c -> "FACADE_WINDOWS".equals(typeOf(c)) || "FACADE".equals(typeOf(c)))) {
            Map<String, Object> win = new HashMap<>();
            win.put("component_id", mainId + "_windows");
            win.put("host_id", mainId);
            win.put("wrap", true);
            win.put("faces", "NORTH,SOUTH,EAST,WEST");
            win.put("anchor_mode", "min_corner");
            out.add(new Component(
                    "FACADE_WINDOWS", main.slotId() == null ? "hall" : main.slotId(),
                    new Vec3i(mp.x(), mp.y() + 2, mp.z()),
                    new Dimensions(hallW, hallD, Math.min(hallH, Math.max(4, hallH - 2))),
                    List.of("wrap_windows"),
                    win));
        }

        LlmPlan rewritten = PlanComponentRewrite.withComponents(plan, out);
        rewritten = withChineseStyle(rewritten);
        rewritten = clearClassicalHints(rewritten);
        FormacraftMod.LOGGER.info(
                "TempleSideHallMisrouteRepair: recover chinese hall compound peidian={} yuetai={} components={}",
                wantPeidian, wantYuetai, rewritten.components().size());
        return rewritten;
    }

    private static Component sideHall(
            String id, int x, int y, int z, int w, int d, int h, String roofType
    ) {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", id);
        params.put("building_role", "side_hall");
        params.put("roof_type", roofType);
        params.put("anchor_mode", "min_corner");
        return new Component(
                "MASS_SECONDARY", id,
                new Vec3i(x, y, z),
                new Dimensions(w, d, h),
                List.of("配殿", "side_hall"),
                params);
    }

    private static Component sideRoofComp(
            String hostId, int x, int y, int z, int w, int d, String roofType
    ) {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", hostId + "_roof");
        params.put("host_id", hostId);
        params.put("roof_type", roofType);
        params.put("anchor_mode", "min_corner");
        return new Component(
                "ROOF", hostId,
                new Vec3i(x - 1, y, z - 1),
                new Dimensions(w + 2, d + 2, 3),
                List.of("配殿屋顶", roofType),
                params);
    }

    private static Component enrichMainOrRoof(
            Component c, String mainId, String roofType, boolean doubleEave
    ) {
        Map<String, Object> params = new HashMap<>();
        if (c.params() != null) params.putAll(c.params());
        params.put("roof_type", roofType);
        if (isPrimaryMass(c)) {
            params.put("component_id", mainId);
            params.put("preferred_roof_type", roofType);
            params.remove("facade_profile");
            params.remove("wall_pattern");
            params.remove("floor_cornice");
        } else {
            params.putIfAbsent("host_id", mainId);
        }
        params.put("anchor_mode", "min_corner");
        if (doubleEave) {
            params.put("double_eave", true);
            params.put("eave_tiers", 2);
        }
        List<String> features = new ArrayList<>();
        if (c.features() != null) {
            for (String f : c.features()) {
                if (f == null) continue;
                String fl = f.toLowerCase(Locale.ROOT);
                if (fl.contains("pilaster") || fl.contains("cornice") || fl.contains("cupola")) {
                    continue;
                }
                features.add(f);
            }
        }
        if (doubleEave && features.stream().noneMatch(f -> f.toLowerCase(Locale.ROOT).contains("double_eave"))) {
            features.add("double_eave");
        }
        return new Component(c.componentType(), c.slotId(), c.relativePosition(),
                c.dimensions(), features, params);
    }

    private static Component sanitizeEntrance(Component c, String mainId, int hallW) {
        Map<String, Object> params = new HashMap<>();
        if (c.params() != null) params.putAll(c.params());
        params.put("host_id", mainId);
        params.put("anchor_mode", "min_corner");
        int doorW = ComponentParamParsers.intParam(params, 0, "door_width", "doorWidth");
        int sane = Math.max(3, Math.min(5, Math.max(3, hallW / 5)));
        if (doorW <= 0 || doorW > sane + 2) {
            params.put("door_width", sane);
        }
        int doorH = ComponentParamParsers.intParam(params, 0, "door_height", "doorHeight");
        if (doorH <= 0 || doorH > 6) {
            params.put("door_height", 4);
        }
        // Thin south-face strip rather than whole-footprint envelope.
        Dimensions dims = c.dimensions();
        if (dims != null && dims.depth() > 2 && dims.width() > 8) {
            dims = new Dimensions(Math.min(dims.width(), hallW), 1, Math.min(dims.height(), 5));
        }
        return new Component(c.componentType(), c.slotId(), c.relativePosition(),
                dims, c.features(), params);
    }

    private static Component sanitizeFacade(Component c, String mainId, int hallW, int hallD, int hallH) {
        Map<String, Object> params = new HashMap<>();
        if (c.params() != null) params.putAll(c.params());
        params.put("host_id", mainId);
        params.put("anchor_mode", "min_corner");
        params.put("wrap", true);
        params.put("faces", "NORTH,SOUTH,EAST,WEST");
        params.remove("rhythm_preset");
        params.remove("repeating_pattern");
        if ("CLASSICAL_PILASTER_BAY".equals(String.valueOf(params.get("rhythm_preset")))) {
            params.remove("rhythm_preset");
        }
        Dimensions dims = new Dimensions(hallW, hallD, Math.min(hallH, Math.max(4, hallH - 2)));
        return new Component(c.componentType(), c.slotId(), c.relativePosition(),
                dims, c.features(), params);
    }

    private static LlmPlan withChineseStyle(LlmPlan plan) {
        String style = plan.styleProfile();
        if (style != null && !style.isBlank() && !"DEFAULT".equalsIgnoreCase(style)
                && !style.toLowerCase(Locale.ROOT).contains("classical")) {
            return plan;
        }
        String guard = plan.enrichmentGuard();
        if (guard == null || guard.isBlank()) {
            guard = "traditional_east_asian_context";
        }
        return new LlmPlan(
                plan.mode(), "chinese_temple_imperial", plan.anchor(), plan.globalConstraints(), plan.layout(),
                plan.components(), plan.genome(), plan.styleAttributes(), plan.proportionHints(),
                plan.alignmentAndSymmetry(), plan.targetSlotId(), plan.allowedArea(), plan.patch(),
                plan.planProgram(), plan.planSkeleton(), plan.planStatus(), plan.error(),
                plan.capabilityGap(), plan.playerFidelityNoticeZh(), plan.distinguishingFeatures(),
                guard, plan.planMeta(), plan.designSources());
    }

    /** Drop classical_monument floor-cornice / cupola hints that float bands after emit. */
    private static LlmPlan clearClassicalHints(LlmPlan plan) {
        if (plan.proportionHints() == null || plan.proportionHints().isEmpty()) {
            return plan;
        }
        Map<String, Object> hints = new HashMap<>(plan.proportionHints());
        boolean changed = false;
        for (String key : List.of(
                "floor_cornice", "base_plinth_detail", "crown_assembly", "crown_template",
                "detail_rules", "repeating_pattern")) {
            if (hints.containsKey(key)) {
                hints.remove(key);
                changed = true;
            }
        }
        Object typ = hints.get("typology");
        if (typ != null && String.valueOf(typ).toLowerCase(Locale.ROOT).contains("classical")) {
            hints.put("typology", "chinese_timber_hall");
            changed = true;
        }
        if (!changed) {
            return plan;
        }
        return new LlmPlan(
                plan.mode(), plan.styleProfile(), plan.anchor(), plan.globalConstraints(), plan.layout(),
                plan.components(), plan.genome(), plan.styleAttributes(), hints,
                plan.alignmentAndSymmetry(), plan.targetSlotId(), plan.allowedArea(), plan.patch(),
                plan.planProgram(), plan.planSkeleton(), plan.planStatus(), plan.error(),
                plan.capabilityGap(), plan.playerFidelityNoticeZh(), plan.distinguishingFeatures(),
                plan.enrichmentGuard(), plan.planMeta(), plan.designSources());
    }

    private static boolean looksChineseHall(String blob) {
        // Require temple/hall compound cues — bare 歇山/xieshan also appears on courtyard 正房
        // and must not trigger peidian/yuetai rewrite (duplicate component_id / wrong layout).
        return blob.contains("大雄宝殿") || blob.contains("宝殿") || blob.contains("重檐歇山")
                || blob.contains("道观") || blob.contains("寺庙") || blob.contains("配殿")
                || blob.contains("mahavira") || blob.contains("temple")
                || (blob.contains("庑殿") && (blob.contains("殿") || blob.contains("寺") || blob.contains("观")));
    }

    private static boolean asksForSideHalls(String blob) {
        return blob.contains("配殿") || blob.contains("厢房") || blob.contains("左右各一")
                || blob.contains("side hall") || blob.contains("side halls");
    }

    private static boolean asksForMoonPlatform(String blob) {
        return blob.contains("月台") || blob.contains("前有月台") || blob.contains("moon platform");
    }

    private static boolean looksClassicalDegrade(LlmPlan plan, String blob) {
        if (blob.contains("classical_monument") || blob.contains("classical_cupola")
                || blob.contains("vertical_pilasters") || blob.contains("classical_pilaster")) {
            return true;
        }
        if (plan.proportionHints() != null) {
            Object typ = plan.proportionHints().get("typology");
            if (typ != null && String.valueOf(typ).toLowerCase(Locale.ROOT).contains("classical")) {
                return true;
            }
            if (Boolean.TRUE.equals(plan.proportionHints().get("crown_assembly"))) {
                return true;
            }
        }
        for (Component c : plan.components()) {
            if (c == null) continue;
            if ("CROWN".equals(typeOf(c))) return true;
            if (isClassicalDecor(c)) return true;
            if (c.params() != null) {
                Object fp = c.params().get("facade_profile");
                if (fp != null && String.valueOf(fp).toLowerCase(Locale.ROOT).contains("pilaster")) {
                    return true;
                }
            }
        }
        return "DEFAULT".equalsIgnoreCase(plan.styleProfile()) && asksForSideHalls(blob);
    }

    private static boolean isClassicalDecor(Component c) {
        if (c == null) return false;
        if (!"DECOR_DETAIL".equals(typeOf(c))) return false;
        String feat = featuresOf(c).toLowerCase(Locale.ROOT);
        String id = idOf(c).toLowerCase(Locale.ROOT);
        // East-asian timber ornament must survive temple recovery.
        if (feat.contains("dougong") || feat.contains("斗拱") || feat.contains("carved_beam")
                || feat.contains("雕梁") || feat.contains("花窗") || feat.contains("雀替")
                || id.contains("dougong") || id.contains("carved_beam") || id.contains("雕梁")
                || id.contains("斗拱")) {
            return false;
        }
        if (feat.contains("cornice") || feat.contains("pilaster") || feat.contains("cupola")
                || feat.contains("classical")) {
            return true;
        }
        // Bare "column"/"carved" are too broad — require western classical cues.
        return (feat.contains("column") || feat.contains("carved"))
                && (feat.contains("ionic") || feat.contains("doric") || feat.contains("corinthian")
                || feat.contains("classical") || feat.contains("pilaster") || feat.contains("western"));
    }

    /**
     * True only when at least two side halls sit on the building-local left and right of the
     * primary mass. 「左右」is relative to the main hall front (globalConstraints.facing), not
     * an arbitrary world E-W or N-S pair. Two halls both on the left do not satisfy 「左右配殿」.
     */
    private static boolean hasSideHalls(LlmPlan plan) {
        List<Component> sides = new ArrayList<>();
        for (Component c : plan.components()) {
            if (looksLikeSideHall(c) && c.relativePosition() != null && c.dimensions() != null) {
                sides.add(c);
            }
        }
        if (sides.size() < 2) return false;
        Component main = findPrimaryMass(plan);
        if (main == null || main.relativePosition() == null || main.dimensions() == null) {
            return true;
        }
        int minX = main.relativePosition().x();
        int maxX = minX + main.dimensions().width();
        int minZ = main.relativePosition().z();
        int maxZ = minZ + main.dimensions().depth();
        GlobalConstraints.Facing facing = GlobalConstraints.Facing.SOUTH;
        if (plan.globalConstraints() != null && plan.globalConstraints().facing() != null) {
            facing = plan.globalConstraints().facing();
        }
        boolean left = false;
        boolean right = false;
        for (Component c : sides) {
            int cx = c.relativePosition().x() + c.dimensions().width() / 2;
            int cz = c.relativePosition().z() + c.dimensions().depth() / 2;
            switch (facing) {
                case NORTH -> {
                    // Front north → left=west, right=east
                    if (cx < minX) left = true;
                    if (cx >= maxX) right = true;
                }
                case EAST -> {
                    // Front east → left=south, right=north
                    if (cz >= maxZ) left = true;
                    if (cz < minZ) right = true;
                }
                case WEST -> {
                    // Front west → left=north, right=south
                    if (cz < minZ) left = true;
                    if (cz >= maxZ) right = true;
                }
                default -> {
                    // Front south (default) → left=east, right=west
                    if (cx >= maxX) left = true;
                    if (cx < minX) right = true;
                }
            }
        }
        return left && right;
    }

    private static boolean hasMoonPlatform(LlmPlan plan) {
        for (Component c : plan.components()) {
            if (looksLikePlatform(c)) return true;
        }
        return false;
    }

    private static boolean looksLikeSideHall(Component c) {
        if (c == null || c.params() == null) return false;
        String t = typeOf(c);
        if (!(t.startsWith("MASS") || "STRUCTURE".equals(t))) return false;
        if ("plate".equalsIgnoreCase(String.valueOf(c.params().get("extrude_mode")))) return false;
        String id = idOf(c).toLowerCase(Locale.ROOT);
        String feat = featuresOf(c).toLowerCase(Locale.ROOT);
        String role = String.valueOf(c.params().getOrDefault("building_role", "")).toLowerCase(Locale.ROOT);
        return id.contains("peidian") || id.contains("side") || id.contains("wing") || id.contains("配殿")
                || feat.contains("配殿") || feat.contains("side_hall") || role.contains("side");
    }

    private static boolean looksLikePlatform(Component c) {
        if (c == null) return false;
        String id = idOf(c).toLowerCase(Locale.ROOT);
        String feat = featuresOf(c).toLowerCase(Locale.ROOT);
        if (id.contains("yuetai") || id.contains("月台") || id.contains("moon_platform")
                || feat.contains("月台") || feat.contains("moon_platform")) {
            return true;
        }
        String role = "";
        if (c.params() != null) {
            Object plateRole = c.params().get("plate_role");
            if (plateRole == null) plateRole = c.params().get("plateRole");
            if (plateRole != null) role = String.valueOf(plateRole).trim().toLowerCase(Locale.ROOT);
        }
        // Interior FLOOR plates (main_floor_plate) must not satisfy outdoor 月台.
        boolean interiorFloor = id.contains("floor") || role.contains("floor") || role.contains("interior")
                || role.contains("storey") || feat.contains("floor_plate");
        if (interiorFloor) {
            return false;
        }
        if (role.contains("yuetai") || role.contains("moon") || role.contains("forecourt")
                || "moon_platform".equals(role)) {
            return true;
        }
        // Generic terrace/platform roles need an outdoor cue (月台 features / id).
        if ("terrace".equals(role) || "platform".equals(role)) {
            return feat.contains("月台") || feat.contains("moon") || feat.contains("forecourt")
                    || id.contains("yuetai") || id.contains("platform") || id.contains("terrace");
        }
        return (id.contains("platform") || id.contains("terrace"))
                && (id.contains("moon") || id.contains("forecourt") || feat.contains("月台")
                || feat.contains("moon") || feat.contains("forecourt"));
    }

    private static Component findPrimaryMass(LlmPlan plan) {
        Component best = null;
        int bestVol = -1;
        for (Component c : plan.components()) {
            if (!isPrimaryMass(c) || c.dimensions() == null) continue;
            int vol = Math.max(1, c.dimensions().width())
                    * Math.max(1, c.dimensions().depth())
                    * Math.max(1, c.dimensions().height());
            if (vol > bestVol) {
                bestVol = vol;
                best = c;
            }
        }
        if (best != null) return best;
        for (Component c : plan.components()) {
            if (c != null && "MASS_MAIN".equals(typeOf(c))) return c;
        }
        return null;
    }

    private static boolean isPrimaryMass(Component c) {
        String t = typeOf(c);
        return "MASS_MAIN".equals(t) || "MAIN_MASS".equals(t);
    }

    private static boolean isMainRoof(Component c, String mainId) {
        if (c == null || !ComponentPlanTypes.isRoofType(typeOf(c))) return false;
        if (mainId == null || mainId.isBlank()) return true;
        Object host = c.params() == null ? null : c.params().get("host_id");
        if (host == null && c.params() != null) host = c.params().get("hostId");
        return host != null && mainId.equals(String.valueOf(host));
    }

    private static String typeOf(Component c) {
        return c.componentType() == null ? "" : c.componentType().trim().toUpperCase(Locale.ROOT);
    }

    private static String idOf(Component c) {
        if (c.params() == null) return "";
        Object id = c.params().get("component_id");
        if (id == null) id = c.params().get("componentId");
        return id == null ? "" : String.valueOf(id);
    }

    private static String featuresOf(Component c) {
        if (c.features() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String f : c.features()) {
            if (f != null) sb.append(f).append(' ');
        }
        return sb.toString();
    }

    private static String blob(LlmPlan plan) {
        StringBuilder sb = new StringBuilder();
        if (plan.planMeta() != null) {
            Object ri = plan.planMeta().get("request_intent");
            if (ri instanceof Map<?, ?> map && map.get("original_text") != null) {
                sb.append(map.get("original_text")).append(' ');
            }
            Object rit = plan.planMeta().get("request_intent_text");
            if (rit != null) sb.append(rit).append(' ');
        }
        if (plan.playerFidelityNoticeZh() != null) sb.append(plan.playerFidelityNoticeZh()).append(' ');
        if (plan.distinguishingFeatures() != null) {
            for (String f : plan.distinguishingFeatures()) {
                if (f != null) sb.append(f).append(' ');
            }
        }
        if (plan.proportionHints() != null) {
            Object ai = plan.proportionHints().get("architecture_intent");
            if (ai instanceof Map<?, ?> m && m.get("original_text") != null) {
                sb.append(m.get("original_text")).append(' ');
            }
            Object typ = plan.proportionHints().get("typology");
            if (typ != null) sb.append(typ).append(' ');
        }
        if (plan.styleProfile() != null) sb.append(plan.styleProfile()).append(' ');
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
