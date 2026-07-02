package com.formacraft.common.generation.structure.blueprint;

import com.formacraft.common.logging.FcaLog;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingType;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.model.build.Features;
import com.formacraft.common.model.build.Footprint;
import com.formacraft.common.model.build.Materials;
import com.formacraft.common.model.build.StyleOptions;
import com.formacraft.common.skeleton.compound.CompoundPlan;
import com.formacraft.common.skeleton.compound.GeneratorBackedPlan;
import com.formacraft.common.skeleton.rect.RectEnclosurePlan;
import com.formacraft.common.skeleton.transform.BlockTransform;
import net.minecraft.util.math.Direction;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CastleBlueprintCompiler (MVP):
 * - Reads spec.extra.blueprint (map) and compiles semantic components into a CompoundPlan.
 * - Supports KEEP (CUBOID -> HouseGenerator), TOWER (CYLINDER -> TowerGenerator), WALL_CONNECTOR (BOX_FRAME -> RectEnclosurePlan).
 *
 * Coordinate systems:
 * - Default: CORNER coordinates (x,z from 0..overallX/overallZ). We convert into the castle's center anchor convention.
 * - Optional: blueprint.coordinate_system="CENTER" (then coordinates are already centered; see below in code).
 */
public final class CastleBlueprintCompiler {

    private static final FcaLog LOG = FcaLog.of("CastleBlueprintCompiler");
    private CastleBlueprintCompiler() {}

    public static CompoundPlan tryCompile(Map<String, Object> blueprint, BuildingSpec parentSpec, Direction gateSideFallback) {
        if (blueprint == null) return null;

        String coord = asUpper(blueprint.get("coordinate_system"));
        boolean cornerCoords = coord.isBlank() || coord.equals("CORNER");

        Map<String, Object> overall = asMap(blueprint.get("overall_dimensions"));
        int overallX = clampInt(overall != null ? overall.get("x") : null,
                parentSpec != null && parentSpec.getFootprint() != null ? parentSpec.getFootprint().getWidth() : 48,
                16, 256);
        int overallZ = clampInt(overall != null ? overall.get("z") : null,
                parentSpec != null && parentSpec.getFootprint() != null ? parentSpec.getFootprint().getDepth() : 36,
                16, 256);
        int halfX = overallX / 2;
        int halfZ = overallZ / 2;

        Direction gateSide = gateSideFallback != null ? gateSideFallback : Direction.SOUTH;
        int gateWidth = 3;
        int wallThickness = 2;
        if (parentSpec != null && parentSpec.getExtra() != null) {
            Object gw = parentSpec.getExtra().get("gateWidth");
            Object wt = parentSpec.getExtra().get("wallThickness");
            gateWidth = clampInt(gw, gateWidth, 0, 15);
            wallThickness = clampInt(wt, wallThickness, 1, 7);
        }

        // Optional blueprint override for gate config
        Map<String, Object> gate = asMap(blueprint.get("gate"));
        if (gate != null) {
            String side = asUpper(gate.get("side"));
            if (!side.isBlank()) {
                try {
                    gateSide = Direction.valueOf(side);
                } catch (Exception ignored) {
                    // tolerate common words
                    if (side.contains("SOUTH")) gateSide = Direction.SOUTH;
                    else if (side.contains("NORTH")) gateSide = Direction.NORTH;
                    else if (side.contains("EAST")) gateSide = Direction.EAST;
                    else if (side.contains("WEST")) gateSide = Direction.WEST;
                }
            }
            gateWidth = clampInt(gate.get("width"), gateWidth, 0, 31);
        }

        List<Object> comps = asList(blueprint.get("components"));
        if (comps == null || comps.isEmpty()) return null;

        Materials mats = parentSpec != null && parentSpec.getMaterials() != null ? parentSpec.getMaterials() : new Materials();
        BuildingStyle style = parentSpec != null && parentSpec.getStyle() != null ? parentSpec.getStyle() : BuildingStyle.MEDIEVAL;

        CompoundPlan plan = new CompoundPlan();

        for (Object c0 : comps) {
            Map<String, Object> c = asMap(c0);
            if (c == null) continue;

            String type = asUpper(c.get("type"));
            Map<String, Object> rel = asMap(c.get("relative_position"));
            Map<String, Object> dim = asMap(c.get("dimensions"));
            int rx0 = clampInt(rel != null ? rel.get("x") : null, 0, -99999, 99999);
            int ry = clampInt(rel != null ? rel.get("y") : null, 0, -256, 256);
            int rz0 = clampInt(rel != null ? rel.get("z") : null, 0, -99999, 99999);

            // compile per component
            switch (type) {
                case "KEEP", "HALL", "MAIN_BUILDING" -> {
                    int w = clampInt(dim != null ? dim.get("width") : null, 16, 6, 128);
                    int d = clampInt(dim != null ? dim.get("depth") : null, 16, 6, 128);
                    int h = clampInt(dim != null ? dim.get("height") : null, 18, 4, 80);

                    // HouseGenerator uses min-corner origin.
                    int dx = cornerCoords ? (rx0 - halfX) : rx0;
                    int dz = cornerCoords ? (rz0 - halfZ) : rz0;

                    BuildingSpec keep = new BuildingSpec();
                    keep.setType(BuildingType.HOUSE);
                    keep.setStyle(style);
                    keep.setFootprint(new Footprint(w, d));
                    keep.setHeight(h);
                    keep.setFloors(Math.max(1, Math.min(6, h / 5)));
                    keep.setMaterials(mats);
                    keep.setFeatures(defaultFeaturesForKeep());
                    keep.setStyleOptions(defaultStyleOptionsForKeep(style));
                    // pass through extra knobs (styleProfileId/paletteId/terrain policy)
                    keep.setExtra(copyExtraWithoutBlueprint(parentSpec != null ? parentSpec.getExtra() : null));
                    // features (optional): arches/lighting
                    applyHouseFeatures(keep, c);

                    plan.add(new GeneratorBackedPlan(keep), BlockTransform.translate(dx, ry, dz));
                }
                case "GATEHOUSE" -> {
                    int w = clampInt(dim != null ? dim.get("width") : null, 12, 6, 96);
                    int d = clampInt(dim != null ? dim.get("depth") : null, 9, 6, 96);
                    int h = clampInt(dim != null ? dim.get("height") : null, 10, 4, 40);

                    int dx = cornerCoords ? (rx0 - halfX) : rx0;
                    int dz = cornerCoords ? (rz0 - halfZ) : rz0;

                    BuildingSpec gatehouse = new BuildingSpec();
                    gatehouse.setType(BuildingType.HOUSE);
                    gatehouse.setStyle(style);
                    gatehouse.setFootprint(new Footprint(w, d));
                    gatehouse.setHeight(h);
                    gatehouse.setFloors(1);
                    gatehouse.setMaterials(mats);
                    Features f = new Features();
                    f.setHasDoor(true);
                    f.setHasWindows(true);
                    f.setHasStairs(false);
                    f.setHasRoof(true);
                    gatehouse.setFeatures(f);
                    StyleOptions so = new StyleOptions();
                    so.setDoorStyle("arched");
                    so.setRoofType("hipped");
                    so.setWindowRatio(0.15);
                    gatehouse.setStyleOptions(so);
                    // Ensure its door aligns with the gate side.
                    gatehouse.setExtra(copyExtraWithoutBlueprint(parentSpec != null ? parentSpec.getExtra() : null));
                    if (gatehouse.getExtra() != null) {
                        gatehouse.getExtra().put("doorSide", gateSide.name());
                    }
                    applyHouseFeatures(gatehouse, c);

                    plan.add(new GeneratorBackedPlan(gatehouse), BlockTransform.translate(dx, ry, dz));
                }
                case "TOWER" -> {
                    int diameter = clampInt(dim != null ? dim.get("diameter") : null, 7, 5, 41);
                    int radius = Math.max(3, diameter / 2);
                    int h = clampInt(dim != null ? dim.get("height") : null, 15, 8, 90);

                    // TowerGenerator uses center origin.
                    int dx = cornerCoords ? (rx0 + radius - halfX) : rx0;
                    int dz = cornerCoords ? (rz0 + radius - halfZ) : rz0;

                    BuildingSpec tower = new BuildingSpec();
                    tower.setType(BuildingType.TOWER);
                    tower.setStyle(style);
                    tower.setFootprint(new Footprint(radius));
                    tower.setHeight(h);
                    tower.setFloors(Math.max(1, Math.min(8, h / 6)));
                    tower.setMaterials(mats);
                    Features f = new Features();
                    f.setHasWindows(true);
                    f.setHasStairs(true);
                    f.setHasDoor(false);
                    f.setHasRoof(true);
                    tower.setFeatures(f);
                    StyleOptions so = new StyleOptions();
                    so.setRoofType("cone");
                    so.setWindowRatio(0.18);
                    tower.setStyleOptions(so);
                    tower.setExtra(copyExtraWithoutBlueprint(parentSpec != null ? parentSpec.getExtra() : null));

                    // features (optional): accept object or list. MVP supports battlements/flag.
                    Map<String, Object> feat = asMap(c.get("features"));
                    if (feat != null) {
                        if (tower.getExtra() != null) {
                            if (feat.containsKey("battlements"))
                                tower.getExtra().put("battlements", feat.get("battlements"));
                            if (feat.containsKey("flag")) tower.getExtra().put("flag", feat.get("flag"));
                            if (feat.containsKey("banner")) tower.getExtra().put("banner", feat.get("banner"));
                            if (feat.containsKey("bannerColor"))
                                tower.getExtra().put("bannerColor", String.valueOf(feat.get("bannerColor")).trim());
                        }
                    }

                    plan.add(new GeneratorBackedPlan(tower), BlockTransform.translate(dx, ry, dz));
                }
                case "WALL_CONNECTOR", "WALL", "BOX_FRAME" -> {
                    int w = clampInt(dim != null ? dim.get("width") : null, overallX, 7, 256);
                    int d = clampInt(dim != null ? dim.get("depth") : null, overallZ, 7, 256);
                    int h = clampInt(dim != null ? dim.get("height") : null, 8, 2, 32);

                    // RectEnclosurePlan uses center origin.
                    int dx = cornerCoords ? (rx0 + (w / 2) - halfX) : rx0;
                    int dz = cornerCoords ? (rz0 + (d / 2) - halfZ) : rz0;

                    // For the main enclosure, we keep the gate opening (gateWidth >= 1).
                    int gw = gateWidth;
                    // If explicitly marked as no-gate frame, allow gateWidth=0
                    if (type.equals("BOX_FRAME")) gw = 0;

                    boolean battlements = false;
                    int battlementSpacing = 2;
                    boolean banner = false;
                    String bannerColor = "red";
                    Map<String, Object> feat = asMap(c.get("features"));
                    if (feat != null) {
                        Object b = feat.get("battlements");
                        if (b instanceof Boolean bb) battlements = bb;
                        else if (b != null) {
                            String s = String.valueOf(b).trim().toLowerCase(Locale.ROOT);
                            if (!s.isEmpty())
                                battlements = (s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on"));
                        }
                        Object sp = feat.get("spacing");
                        if (sp != null) {
                            try {
                                int v = (sp instanceof Number n) ? n.intValue() : Integer.parseInt(String.valueOf(sp).trim());
                                battlementSpacing = Math.max(1, Math.min(6, v));
                            } catch (Exception e) { LOG.debug("best-effort step failed", e); }
                        }

                        Object bn = feat.get("banner");
                        if (bn instanceof Boolean bb) banner = bb;
                        else if (bn != null) {
                            String s = String.valueOf(bn).trim().toLowerCase(Locale.ROOT);
                            if (!s.isEmpty()) banner = (s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on"));
                        }
                        Object bc = feat.get("bannerColor");
                        if (bc != null) {
                            String s = String.valueOf(bc).trim().toLowerCase(Locale.ROOT);
                            if (!s.isEmpty()) bannerColor = s;
                        }
                    }

                    // Style defaults when features do not explicitly specify (cross-style)
                    try {
                        var prof = com.formacraft.common.style.profile.StyleProfileRegistry.resolve(parentSpec);
                        var det = (prof != null) ? prof.details() : null;
                        if (det != null) {
                            if ((feat == null || !feat.containsKey("battlements")) && det.eavesProfile != null) {
                                battlements = det.eavesProfile.toLowerCase(Locale.ROOT).contains("battlement");
                            }
                            if ((feat == null || !feat.containsKey("banner")) && det.bannerEnabled != null) {
                                banner = Boolean.TRUE.equals(det.bannerEnabled);
                            }
                            if ((feat == null || !feat.containsKey("banner")) && !banner && det.ornamentProfile != null) {
                                String op = det.ornamentProfile.toLowerCase(Locale.ROOT);
                                if (op.contains("banner")) banner = true;
                            }
                            if ((feat == null || !feat.containsKey("bannerColor")) && banner && det.bannerColor != null && !det.bannerColor.isBlank()) {
                                bannerColor = det.bannerColor.trim().toLowerCase(Locale.ROOT);
                            }
                        }
                    } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }

                    RectEnclosurePlan rep = new RectEnclosurePlan(w, d, h, wallThickness, gateSide, gw,
                            battlements, battlementSpacing, banner, bannerColor);
                    plan.add(rep, BlockTransform.translate(dx, ry, dz));
                }
            }

        }

        return plan.components.isEmpty() ? null : plan;
    }

    private static Features defaultFeaturesForKeep() {
        Features f = new Features();
        f.setHasWindows(true);
        f.setHasDoor(true);
        f.setHasStairs(true);
        f.setHasRoof(true);
        f.setHasRoofDecoration(true);
        f.setHasBalcony(false);
        return f;
    }

    private static StyleOptions defaultStyleOptionsForKeep(BuildingStyle style) {
        StyleOptions o = new StyleOptions();
        o.setDoorStyle("arched");
        o.setRoofType(style == BuildingStyle.MEDIEVAL ? "gable" : "flat");
        o.setWindowRatio(0.18);
        o.setWallPattern("random");
        return o;
    }

    private static String asUpper(Object v) {
        if (v == null) return "";
        return String.valueOf(v).trim().toUpperCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        if (v instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object v) {
        if (v instanceof List<?> l) return (List<Object>) l;
        return null;
    }

    private static int clampInt(Object v, int def, int min, int max) {
        int out = def;
        try {
            if (v instanceof Number n) out = n.intValue();
            else if (v != null) out = Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) { LOG.debug("best-effort step failed", e); }
        if (out < min) out = min;
        if (out > max) out = max;
        return out;
    }

    private static void applyHouseFeatures(BuildingSpec s, Map<String, Object> component) {
        if (s == null || component == null) return;
        Map<String, Object> feat = asMap(component.get("features"));
        if (feat == null) return;
        if (s.getExtra() == null) return;

        // arches: if explicitly false, relax door style
        Object arches = feat.get("arches");
        if (arches instanceof Boolean b) {
            if (!b && s.getStyleOptions() != null) {
                s.getStyleOptions().setDoorStyle("single");
            }
        }

        Object lighting = feat.get("lighting");
        if (lighting != null) s.getExtra().put("lighting", String.valueOf(lighting).trim());
        Object lightingType = feat.get("lightingType");
        if (lightingType != null) s.getExtra().put("lightingType", String.valueOf(lightingType).trim());
        Object spacing = feat.get("spacing");
        if (spacing != null) s.getExtra().put("lightingSpacing", spacing);

        Object banner = feat.get("banner");
        if (banner != null) s.getExtra().put("banner", banner);
        Object bannerColor = feat.get("bannerColor");
        if (bannerColor != null) s.getExtra().put("bannerColor", String.valueOf(bannerColor).trim());
    }

    private static Map<String, Object> copyExtraWithoutBlueprint(Map<String, Object> extra) {
        if (extra == null || extra.isEmpty()) return extra;
        java.util.HashMap<String, Object> copy = new java.util.HashMap<>(extra);
        copy.remove("blueprint");
        copy.remove("blueprint_json");
        copy.remove("blueprintJson");
        return copy;
    }
}


