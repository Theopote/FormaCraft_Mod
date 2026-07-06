package com.formacraft.server.generation.structure.selector;

import com.formacraft.common.logging.FcaLog;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.model.build.BuildingType;
import com.formacraft.common.model.build.Features;
import com.formacraft.common.model.build.Footprint;
import com.formacraft.common.model.build.Materials;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.profile.StyleProfileRegistry;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * RuleBasedGeneratorSelector (K+ v1):
 * Deterministically selects generator intent (template/landmark) and fills minimal spec defaults
 * based on (semanticRole + skeleton shape/size + city style).
 * Generator intent is fully driven by {@code generator_selector_rules_v1.json} via
 * {@link GeneratorSelectorRegistry}; this class handles footprint/type defaults and StyleProfile extras.
 *
 * Design goals:
 * - AI may omit spec/type/footprint fields; program keeps plans stable.
 * - Never overwrite explicit values set by user/LLM.
 * - Output should align with existing GeneratorRouter keys:
 *   - extra.template -> deterministic generators (office_block/office_district/courtyard_compound/castle_compound...)
 *   - extra.landmark -> legacy landmark routing (tulou/temple_of_heaven/eiffel_tower/...)
 */
public final class RuleBasedGeneratorSelector {

    private static final FcaLog LOG = FcaLog.of("RuleBasedGeneratorSelector");
    private RuleBasedGeneratorSelector() {}

    /**
     * Apply rule-based defaults to a (possibly partial) BuildingSpec.
     * @return true if any change was made
     */
    public static boolean apply(BuildingSpec spec,
                                BuildingStyle cityStyle,
                                String semanticRoleUpper,
                                String skeletonShapeUpper,
                                int skWidth,
                                int skDepth,
                                int skRadius) {
        if (spec == null) return false;

        boolean changed = false;
        BuildingType typeAtEntry = spec.getType();
        String role = (semanticRoleUpper != null ? semanticRoleUpper : "").trim().toUpperCase(Locale.ROOT);
        String shape = (skeletonShapeUpper != null ? skeletonShapeUpper : "").trim().toUpperCase(Locale.ROOT);
        BuildingStyle style = (spec.getStyle() != null) ? spec.getStyle() : (cityStyle != null ? cityStyle : BuildingStyle.DEFAULT);

        // ensure base objects
        if (spec.getMaterials() == null) { spec.setMaterials(new Materials()); changed = true; }
        if (spec.getFeatures() == null) { spec.setFeatures(new Features()); changed = true; }
        if (spec.getStyle() == null) { spec.setStyle(style); changed = true; }

        Map<String, Object> extra = spec.getExtra();
        if (extra == null) { extra = new HashMap<>(); spec.setExtra(extra); changed = true; }

        // Preserve existing semantic role if already present.
        if (!extra.containsKey("semanticRole") && !role.isEmpty()) {
            extra.put("semanticRole", role);
            changed = true;
        }

        // Some roles (PUBLIC/LANDSCAPE/CIRCULATION) are not buildings by default.
        // We keep them as non-building space; downstream can render skeleton, but we avoid generating blocks.
        if (role.equals("PUBLIC") || role.equals("LANDSCAPE") || role.equals("CIRCULATION")) {
            // If the spec already has an explicit type, keep it. Otherwise, mark as CUSTOM so it won't accidentally route to a heavy generator.
            if (spec.getType() == null) {
                spec.setType(BuildingType.CUSTOM);
                // also disable door/windows to reduce accidental structures if any generator gets used
                try {
                    spec.getFeatures().setHasDoor(false);
                    spec.getFeatures().setHasWindows(false);
                } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }
                changed = true;
            }
            // no further intent selection
            return changed;
        }

        // Default building type if missing
        boolean typeInferredHere = false;
        if (spec.getType() == null) {
            BuildingType t = BuildingType.HOUSE;
            if (role.equals("CORE") && shape.equals("CIRCLE")) t = BuildingType.TOWER;
            spec.setType(t);
            typeInferredHere = true;
            changed = true;
        }

        // Fill footprint from skeleton if missing/invalid
        if (spec.getFootprint() == null) {
            spec.setFootprint(new Footprint());
            changed = true;
        }
        Footprint fp = spec.getFootprint();
        if (shape.equals("CIRCLE")) {
            int r = fp.getRadius() > 0 ? fp.getRadius() : Math.max(0, skRadius);
            if (r <= 0) r = Math.max(6, Math.min(18, (Math.max(skWidth, skDepth) / 2)));
            if (!"circle".equalsIgnoreCase(fp.getShape())) { fp.setShape("circle"); changed = true; }
            if (fp.getRadius() <= 0) { fp.setRadius(Math.max(6, r)); changed = true; }
        } else if (shape.equals("RECTANGLE") || shape.isEmpty()) {
            int w = fp.getWidth() > 0 ? fp.getWidth() : skWidth;
            int d = fp.getDepth() > 0 ? fp.getDepth() : skDepth;
            if (w <= 0) w = 10;
            if (d <= 0) d = 8;
            if (fp.getShape() == null || fp.getShape().isBlank() || !"rectangle".equalsIgnoreCase(fp.getShape())) {
                fp.setShape("rectangle");
                changed = true;
            }
            if (fp.getWidth() <= 0) { fp.setWidth(w); changed = true; }
            if (fp.getDepth() <= 0) { fp.setDepth(d); changed = true; }
        }

        // --- Data-driven rule table (K+ v1) ---
        try {
            String cityStyleUpper = style != null ? style.name() : (cityStyle != null ? cityStyle.name() : "DEFAULT");
            int rr = 0;
            if (fp != null && "circle".equalsIgnoreCase(fp.getShape())) rr = Math.max(0, fp.getRadius());
            int ww = 0;
            int dd = 0;
            if (fp != null && "rectangle".equalsIgnoreCase(fp.getShape())) {
                ww = Math.max(0, fp.getWidth());
                dd = Math.max(0, fp.getDepth());
            }
            if (ww <= 0) ww = Math.max(0, skWidth);
            if (dd <= 0) dd = Math.max(0, skDepth);
            GeneratorSelectorCatalog.Rule rule = GeneratorSelectorRegistry.match(cityStyleUpper, role, shape, rr, ww, dd);
            if (rule != null && rule.then != null) {
                GeneratorSelectorCatalog.Then t = rule.then;

                // intent: template/landmark (do not override)
                boolean hasTemplate = extra.get("template") != null && !String.valueOf(extra.get("template")).trim().isEmpty();
                boolean hasLandmark = extra.get("landmark") != null && !String.valueOf(extra.get("landmark")).trim().isEmpty();
                if (!hasTemplate && t.template != null && !t.template.isBlank()) {
                    extra.put("template", t.template.trim());
                    changed = true;
                }
                if (!hasLandmark && t.landmark != null && !t.landmark.isBlank()) {
                    extra.put("landmark", t.landmark.trim());
                    changed = true;
                }

                // building type override (only if missing, CUSTOM, or inferred default above)
                if (t.buildingType != null && !t.buildingType.isBlank()) {
                    try {
                        BuildingType bt = BuildingType.valueOf(t.buildingType.trim().toUpperCase(Locale.ROOT));
                        if (typeAtEntry == null || typeAtEntry == BuildingType.CUSTOM || typeInferredHere) {
                            spec.setType(bt);
                            changed = true;
                        }
                    } catch (Exception e) { LOG.debug("best-effort step failed", e); }
                }

                if (t.floors != null && spec.getFloors() <= 0) { spec.setFloors(Math.max(1, t.floors)); changed = true; }
                if (t.height != null && spec.getHeight() <= 0) { spec.setHeight(Math.max(4, t.height)); changed = true; }

                // footprint overrides if missing
                if (fp != null) {
                    if (t.radius != null && fp.getRadius() <= 0) { fp.setShape("circle"); fp.setRadius(Math.max(3, t.radius)); changed = true; }
                    if (t.width != null && fp.getWidth() <= 0) { fp.setShape("rectangle"); fp.setWidth(Math.max(3, t.width)); changed = true; }
                    if (t.depth != null && fp.getDepth() <= 0) { fp.setShape("rectangle"); fp.setDepth(Math.max(3, t.depth)); changed = true; }
                }

                if (t.extraDefaults != null && !t.extraDefaults.isEmpty()) {
                    for (var e : t.extraDefaults.entrySet()) {
                        if (e.getKey() == null) continue;
                        String k = e.getKey().trim();
                        if (k.isEmpty()) continue;
                        if (!extra.containsKey(k)) {
                            extra.put(k, e.getValue());
                            changed = true;
                        }
                    }
                }

                // helpful landmark defaults
                if (t.landmark != null && t.landmark.toLowerCase(Locale.ROOT).contains("temple_of_heaven")) {
                    // TempleOfHeavenGenerator uses baseRadius/hallRadius defaults from footprint; nudge baseRadius to match footprint radius.
                    try {
                        if (!extra.containsKey("baseRadius") && fp != null && "circle".equalsIgnoreCase(fp.getShape()) && fp.getRadius() > 0) {
                            extra.put("baseRadius", fp.getRadius());
                            changed = true;
                        }
                    } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }
                }
            }
        } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }

        // --- StyleProfile-driven defaults (do not override explicit user/LLM values) ---
        try {
            StyleProfile profile = StyleProfileRegistry.resolve(spec);
            if (profile != null && profile.details() != null) {
                // wall banners (for RectEnclosure / castle walls)
                if (!extra.containsKey("wallBanner") && profile.details().bannerEnabled != null) {
                    extra.put("wallBanner", profile.details().bannerEnabled);
                    changed = true;
                }
                if (!extra.containsKey("wallBannerColor")
                        && Boolean.TRUE.equals(extra.get("wallBanner"))
                        && profile.details().bannerColor != null
                        && !profile.details().bannerColor.isBlank()) {
                    extra.put("wallBannerColor", profile.details().bannerColor);
                    changed = true;
                }

                // building banners (House/Tower generators)
                if (!extra.containsKey("banner") && profile.details().bannerEnabled != null) {
                    extra.put("banner", profile.details().bannerEnabled);
                    changed = true;
                }
                if (!extra.containsKey("bannerColor")
                        && Boolean.TRUE.equals(extra.get("banner"))
                        && profile.details().bannerColor != null
                        && !profile.details().bannerColor.isBlank()) {
                    extra.put("bannerColor", profile.details().bannerColor);
                    changed = true;
                }
            }
        } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }

        return changed;
    }
}


