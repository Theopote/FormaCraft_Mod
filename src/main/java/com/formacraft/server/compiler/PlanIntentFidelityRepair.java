package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.generation.design.DesignProvenance;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.StyleAttributes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lands architecture_intent / distinguishing_features / chinese_roof_contract onto
 * MASS/ROOF/FACADE/ENTRANCE params so empty-component recovery and soft LLM plans
 * still honor roof type, materials, and solid faces.
 */
final class PlanIntentFidelityRepair {
    private PlanIntentFidelityRepair() {}

    /** Matches 「约 12×10」「11x11」「12*10」 style footprints in architecture_intent text. */
    private static final Pattern FOOTPRINT_PATTERN = Pattern.compile(
            "(\\d{1,2})\\s*[×xX*]\\s*(\\d{1,2})");

    record IntentHints(
            String roofType,
            boolean doubleEave,
            boolean preferStairs,
            String wallBlock,
            String roofBlock,
            String floorBlock,
            int floorCount,
            String ridgeAxis,
            boolean doorFlankWindows,
            List<String> solidSides,
            List<String> windowSides,
            List<String> featureSeeds,
            int footprintWidth,
            int footprintDepth
    ) {}

    static IntentHints parse(LlmPlan plan) {
        String corpus = intentCorpus(plan);
        String lower = corpus.toLowerCase(Locale.ROOT);

        String roofType = roofTypeFromChineseContract(plan);
        if (roofType == null) {
            roofType = roofTypeFromCorpus(lower);
        }
        if (roofType == null && plan != null && plan.styleAttributes() != null
                && plan.styleAttributes().roofForm() != null) {
            roofType = roofTypeFromCorpus(plan.styleAttributes().roofForm().toLowerCase(Locale.ROOT));
        }
        boolean doubleEave = lower.contains("重檐")
                || lower.contains("double_eave")
                || lower.contains("double-eave")
                || lower.contains("double_eave_hip")
                || lower.contains("上下两层屋檐")
                || eaveTiers(plan) >= 2;

        boolean preferStairs = lower.contains("楼梯") || lower.contains("stair")
                || lower.contains("坡屋面") || lower.contains("坡屋顶")
                || lower.contains("pitched") || lower.contains("gable")
                || lower.contains("双坡") || lower.contains("人字")
                || lower.contains("歇山") || lower.contains("庑殿") || lower.contains("悬山");

        String wallBlock = wallBlockFromStyle(plan);
        if (wallBlock == null) {
            wallBlock = wallBlockFromCorpus(lower);
        }
        String roofBlock = roofBlockFromStyle(plan);
        if (roofBlock == null) {
            roofBlock = roofBlockFromCorpus(lower);
        }
        if (preferStairs && roofBlock != null && !roofBlock.contains("stairs")) {
            String stairs = com.formacraft.common.palette.component.MaterialBlockFamily.stairs(roofBlock);
            if (stairs != null) roofBlock = stairs;
        }
        String floorBlock = floorBlockFromStyle(plan);

        int floorCount = 0;
        if (lower.contains("两层") || lower.contains("二层") || lower.contains("2层")
                || lower.contains("two-stor") || lower.contains("two stor") || lower.contains("2-stor")) {
            floorCount = 2;
        } else if (lower.contains("三层") || lower.contains("3层") || lower.contains("three-stor")) {
            floorCount = 3;
        } else if (lower.contains("四层") || lower.contains("4层")) {
            floorCount = 4;
        } else if (lower.contains("单层") || lower.contains("single-stor") || lower.contains("single stor")) {
            floorCount = 1;
        }

        String ridgeAxis = null;
        if (lower.contains("屋脊沿东西")
                || (lower.contains("ridge") && lower.contains("east-west"))
                || lower.contains("ridge_axis:x")
                || (lower.contains("南北两坡") && lower.contains("东西"))) {
            ridgeAxis = "x";
        } else if (lower.contains("屋脊沿南北")
                || (lower.contains("ridge") && lower.contains("north-south"))
                || lower.contains("ridge_axis:z")) {
            ridgeAxis = "z";
        } else if (lower.contains("屋脊沿长边")) {
            ridgeAxis = "long";
        }

        boolean doorFlank = lower.contains("一门两窗") || lower.contains("正门两侧窗")
                || lower.contains("两侧窗户") || lower.contains("window on each side")
                || lower.contains("windows on both sides") || lower.contains("door with a window on each");

        List<String> solid = new ArrayList<>();
        List<String> windows = new ArrayList<>();
        applyFacadeIntent(lower, solid, windows);

        List<String> features = new ArrayList<>();
        if (plan != null && plan.distinguishingFeatures() != null) {
            for (String token : plan.distinguishingFeatures()) {
                if (token == null || token.isBlank()) continue;
                features.add(com.formacraft.common.llm.DistinguishingFeaturesBridge.slugify(token));
            }
        }
        if (roofType != null) features.add(roofType);
        if (doubleEave) features.add("double_eave");
        if (preferStairs) features.add("stair_roof");
        if (doorFlank) features.add("door_flank_windows");

        int[] footprint = parseFootprint(corpus);
        return new IntentHints(roofType, doubleEave, preferStairs, wallBlock, roofBlock, floorBlock,
                floorCount, ridgeAxis, doorFlank, solid, windows, features,
                footprint[0], footprint[1]);
    }

    /**
     * @return {@code [width, depth]} from the first plausible WxD token; zeros when absent.
     */
    static int[] parseFootprint(String corpus) {
        if (corpus == null || corpus.isBlank()) return new int[]{0, 0};
        Matcher m = FOOTPRINT_PATTERN.matcher(corpus);
        while (m.find()) {
            int a = Integer.parseInt(m.group(1));
            int b = Integer.parseInt(m.group(2));
            // Building footprints in prompts are typically 4–48; skip tiny ratios like 1x2 stairs.
            if (a >= 4 && b >= 4 && a <= 48 && b <= 48) {
                return new int[]{a, b};
            }
        }
        return new int[]{0, 0};
    }

    static Component enrich(LlmPlan plan, Component component) {
        if (component == null) return null;
        return enrich(plan, component, parse(plan));
    }

    static Component enrich(LlmPlan plan, Component component, IntentHints hints) {
        if (component == null || hints == null) return component;
        String type = ComponentPlanTypes.normalizeType(component.componentType());
        boolean mass = ComponentPlanTypes.isMassType(type);
        boolean roof = ComponentPlanTypes.isRoofType(type);
        boolean facade = "FACADE_WINDOWS".equals(type);
        boolean entrance = "ENTRANCE".equals(type);
        boolean structure = "STRUCTURE".equals(type);
        if (!mass && !roof && !facade && !entrance && !structure) {
            return component;
        }

        Map<String, Object> params = new HashMap<>();
        if (component.params() != null) params.putAll(component.params());
        List<String> features = new ArrayList<>();
        if (component.features() != null) features.addAll(component.features());
        boolean changed = false;

        for (String seed : hints.featureSeeds()) {
            if (seed == null || seed.isBlank()) continue;
            if (features.stream().noneMatch(f -> f != null && f.equalsIgnoreCase(seed))) {
                features.add(seed);
                changed = true;
            }
        }

        if ((mass || roof || structure) && hints.roofType() != null
                && isBlank(ComponentPlanTypes.getParamString(params, "roof_type", "roofType"))) {
            changed |= DesignProvenance.putIfAllowed(plan, params, "roof_type", hints.roofType());
            if (mass) {
                changed |= DesignProvenance.putIfAllowed(plan, params, "preferred_roof_type", hints.roofType());
            }
        }
        if ((mass || roof || structure) && hints.doubleEave()) {
            if (!params.containsKey("double_eave") && !params.containsKey("doubleEave")) {
                changed |= DesignProvenance.putIfAllowed(plan, params, "double_eave", true);
            }
            if (!params.containsKey("eave_tiers") && !params.containsKey("eaveTiers")) {
                changed |= DesignProvenance.putIfAllowed(plan, params, "eave_tiers", 2);
            }
            if (features.stream().noneMatch(f -> f != null && f.toLowerCase(Locale.ROOT).contains("double_eave"))) {
                features.add("double_eave");
                changed = true;
            }
        }

        if ((mass || structure) && hints.wallBlock() != null
                && isBlank(ComponentPlanTypes.getParamString(params, "wall_block", "material", "primary_block"))) {
            changed |= DesignProvenance.putIfAllowed(plan, params, "wall_block", hints.wallBlock());
        }
        if ((mass || roof || structure) && hints.roofBlock() != null
                && isBlank(ComponentPlanTypes.getParamString(params, "roof_block", "roof_material", "roof_stairs_block"))) {
            changed |= DesignProvenance.putIfAllowed(plan, params, "roof_block", hints.roofBlock());
        }
        if ((mass || roof || structure) && hints.preferStairs()) {
            changed |= DesignProvenance.putIfAllowed(plan, params, "prefer_stairs", true);
            // Re-promote non-stair roof blocks to stairs when the prompt asked for 楼梯坡屋面.
            String currentRoof = ComponentPlanTypes.getParamString(params, "roof_block", "roof_material", "material");
            if (currentRoof != null && !currentRoof.toLowerCase(Locale.ROOT).contains("stairs")) {
                String stairs = com.formacraft.common.palette.component.MaterialBlockFamily.stairs(currentRoof);
                if (stairs != null) {
                    changed |= DesignProvenance.putIfAllowed(plan, params, "roof_block", stairs);
                }
            }
            // Drop shallow authored roof_height so normalizeStairRoofRise can lift to ~45°.
            if (roof || mass) {
                changed |= dropShallowRoofHeight(params, component);
            }
        }
        // Even without prefer_stairs, shallow roof_height on a wide span flattens the ridge
        // (RoofShapeAudit no_ridge_rise) — drop so normalizeStairRoofRise can bump rise.
        if ((roof || mass) && !hints.preferStairs()) {
            changed |= dropShallowRoofHeight(params, component);
        }
        if (mass && hints.floorBlock() != null
                && isBlank(ComponentPlanTypes.getParamString(params, "floor_block"))) {
            changed |= DesignProvenance.putIfAllowed(plan, params, "floor_block", hints.floorBlock());
        }
        if (mass && hints.floorCount() > 0
                && ComponentParamParsers.intParam(params, 0, "floor_count", "floorCount") <= 0) {
            changed |= DesignProvenance.putIfAllowed(plan, params, "floor_count", hints.floorCount());
            if (ComponentParamParsers.intParam(params, 0, "floor_height", "floorHeight") <= 0) {
                int bodyH = component.dimensions() != null ? component.dimensions().height() : 0;
                int fh = hints.floorCount() <= 1 ? Math.max(3, bodyH)
                        : Math.max(3, bodyH > 0 ? bodyH / hints.floorCount() : 4);
                changed |= DesignProvenance.putIfAllowed(plan, params, "floor_height", fh);
            }
        }
        if ((mass || roof) && hints.ridgeAxis() != null
                && isBlank(ComponentPlanTypes.getParamString(params, "ridge_axis", "ridgeAxis"))) {
            String axis = hints.ridgeAxis();
            if ("long".equals(axis) && component.dimensions() != null) {
                axis = component.dimensions().width() >= component.dimensions().depth() ? "x" : "z";
            }
            changed |= DesignProvenance.putIfAllowed(plan, params, "ridge_axis", axis);
        }

        if (mass || facade) {
            changed |= applySolidWindowSides(plan, params, features, hints);
            changed |= applyFacadeRatioSides(plan, params);
        }
        if (facade) {
            changed |= applyThinFacadeFace(plan, params, component, features);
        }
        if (facade && hints.doorFlankWindows()) {
            changed |= DesignProvenance.putIfAllowed(plan, params, "entrance_flank_windows", 2);
            changed |= DesignProvenance.putIfAllowed(plan, params, "windows_per_wall", 2);
            changed |= DesignProvenance.putIfAllowed(plan, params, "reserve_entrance_bay", true);
        }
        if (mass && hints.doorFlankWindows()) {
            changed |= DesignProvenance.putIfAllowed(plan, params, "entrance_flank_windows", 2);
            // Keep host entrance-face windows even when other faces are solid.
            if (!params.containsKey("window_sides") && !params.containsKey("windowSides")) {
                changed |= DesignProvenance.putIfAllowed(plan, params, "window_sides", List.of("SOUTH"));
            }
        }

        if (entrance && !params.containsKey("door_width") && !params.containsKey("doorWidth")) {
            int span = entranceSpan(component);
            if (span > 0) {
                int door = symmetricDoorWidth(span);
                changed |= DesignProvenance.putIfAllowed(plan, params, "door_width", door);
            }
        }

        if (!changed) return component;
        return new Component(
                component.componentType(),
                component.slotId(),
                component.relativePosition(),
                component.dimensions(),
                features,
                params);
    }

    static int symmetricDoorWidth(int openingSpan) {
        return com.formacraft.common.generation.component.impl.EntranceGenerator.symmetricDoorWidth(openingSpan);
    }

    static String intentCorpus(LlmPlan plan) {
        if (plan == null) return "";
        StringBuilder sb = new StringBuilder();
        if (plan.distinguishingFeatures() != null) {
            for (String f : plan.distinguishingFeatures()) {
                if (f != null) sb.append(' ').append(f);
            }
        }
        StyleAttributes attrs = plan.styleAttributes();
        if (attrs != null) {
            if (attrs.decorativeElements() != null) {
                for (String e : attrs.decorativeElements()) {
                    if (e != null) sb.append(' ').append(e);
                }
            }
            if (attrs.wallMaterial() != null) sb.append(' ').append(attrs.wallMaterial());
            if (attrs.roofMaterial() != null) sb.append(' ').append(attrs.roofMaterial());
            if (attrs.customAttributes() != null) {
                attrs.customAttributes().forEach((k, v) -> {
                    if (k != null) sb.append(' ').append(k);
                    if (v != null) sb.append(' ').append(v);
                });
            }
        }
        if (plan.styleProfile() != null) sb.append(' ').append(plan.styleProfile());
        if (plan.proportionHints() != null) {
            Object intent = plan.proportionHints().get("architecture_intent");
            appendArchitectureIntent(sb, intent);
            Object notes = plan.proportionHints().get("notes");
            if (notes != null) sb.append(' ').append(notes);
            Object crc = plan.proportionHints().get("chinese_roof_contract");
            if (crc != null) sb.append(' ').append(String.valueOf(crc));
        }
        return sb.toString();
    }

    private static void appendArchitectureIntent(StringBuilder sb, Object intent) {
        if (!(intent instanceof Map<?, ?> map)) return;
        Object original = map.get("original_text");
        if (original != null) sb.append(' ').append(original);
        Object scopes = map.get("scopes");
        if (scopes instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> scope) {
                    Object text = scope.get("text");
                    if (text != null) sb.append(' ').append(text);
                }
            }
        }
        Object identities = map.get("style_identities");
        if (identities instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> id) {
                    Object requested = id.get("requested_id");
                    Object identity = id.get("identity_id");
                    if (requested != null) sb.append(' ').append(requested);
                    if (identity != null) sb.append(' ').append(identity);
                }
            }
        }
    }

    private static String roofTypeFromChineseContract(LlmPlan plan) {
        if (plan == null || plan.proportionHints() == null) return null;
        Object crc = plan.proportionHints().get("chinese_roof_contract");
        if (!(crc instanceof Map<?, ?> map)) return null;
        Object reqs = map.get("requirements");
        if (!(reqs instanceof List<?> list)) return null;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> req)) continue;
            Object property = req.get("property");
            if (property == null) continue;
            String p = String.valueOf(property).toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
            if (!"rooftype".equals(p)) continue;
            String value = String.valueOf(req.get("value")).trim().toLowerCase(Locale.ROOT);
            return canonicalizeRoofType(value);
        }
        return null;
    }

    private static String roofTypeFromCorpus(String lower) {
        if (lower.contains("歇山") || lower.contains("xieshan") || lower.contains("hip_gable")
                || lower.contains("hip-gable") || lower.contains("hip_and_gable")
                || lower.contains("double_eave_hip_gable")) {
            return "xieshan";
        }
        if (lower.contains("庑殿") || lower.contains("wu_dian") || lower.contains("wudian")
                || lower.contains("四坡") || (lower.contains("hip") && !lower.contains("hip_gable")
                && !lower.contains("hip-gable") && !lower.contains("hip_and_gable"))) {
            return "hip";
        }
        if (lower.contains("悬山") || lower.contains("xuanshan")) {
            return "xuanshan";
        }
        if (lower.contains("硬山") || lower.contains("yingshan")) {
            return "yingshan";
        }
        if (lower.contains("双坡") || lower.contains("人字顶") || lower.contains("double_gable")) {
            return "gable";
        }
        return null;
    }

    private static String canonicalizeRoofType(String value) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (v) {
            case "wu_dian", "wudian", "庑殿", "chinese_hip" -> "hip";
            case "xie_shan", "xieshan", "歇山", "hip_and_gable", "hip_gable" -> "xieshan";
            case "xuan_shan", "xuanshan", "悬山" -> "xuanshan";
            case "ying_shan", "yingshan", "硬山" -> "yingshan";
            default -> v;
        };
    }

    private static int eaveTiers(LlmPlan plan) {
        if (plan == null || plan.proportionHints() == null) return 0;
        return ComponentParamParsers.intParam(plan.proportionHints(), 0, "eave_tiers", "eaveTiers");
    }

    private static String wallBlockFromStyle(LlmPlan plan) {
        if (plan == null || plan.styleAttributes() == null) return null;
        StyleAttributes a = plan.styleAttributes();
        String fromPalette = a.resolvePaletteBlock("wall", "primary");
        if (fromPalette != null) return stabilizeBlock(fromPalette);
        if (a.wallMaterial() != null && !a.wallMaterial().isBlank()) {
            return stabilizeBlock(a.wallMaterial());
        }
        return null;
    }

    private static String roofBlockFromStyle(LlmPlan plan) {
        if (plan == null || plan.styleAttributes() == null) return null;
        StyleAttributes a = plan.styleAttributes();
        String fromPalette = a.resolvePaletteBlock("roof", "roof_slope", "roof_stairs", "roof_trim", "roof_ridge");
        if (fromPalette != null) return stabilizeBlock(fromPalette);
        if (a.roofMaterial() != null && !a.roofMaterial().isBlank()) {
            return stabilizeBlock(a.roofMaterial());
        }
        return null;
    }

    private static String floorBlockFromStyle(LlmPlan plan) {
        if (plan == null || plan.styleAttributes() == null) return null;
        StyleAttributes a = plan.styleAttributes();
        String fromPalette = a.resolvePaletteBlock("floor");
        if (fromPalette != null) return stabilizeBlock(fromPalette);
        if (a.floorMaterial() != null && !a.floorMaterial().isBlank()) {
            return stabilizeBlock(a.floorMaterial());
        }
        return null;
    }

    private static String wallBlockFromCorpus(String lower) {
        if (lower.contains("深色橡木墙") || (lower.contains("dark_oak") && lower.contains("wall"))) {
            return "minecraft:dark_oak_planks";
        }
        if (lower.contains("橡木墙") || (lower.contains("oak") && lower.contains("wall")
                && !lower.contains("dark_oak"))) {
            return "minecraft:oak_planks";
        }
        if (lower.contains("白墙") || lower.contains("white_concrete") || lower.contains("white wall")) {
            return "minecraft:white_concrete";
        }
        if (lower.contains("石砖墙") || lower.contains("stone_brick")) {
            return "minecraft:stone_bricks";
        }
        return null;
    }

    private static String roofBlockFromCorpus(String lower) {
        if (lower.contains("石砖楼梯") || (lower.contains("stone_brick") && lower.contains("stair"))) {
            return "minecraft:stone_brick_stairs";
        }
        if (lower.contains("深色橡木楼梯") || (lower.contains("dark_oak") && lower.contains("stair"))) {
            return "minecraft:dark_oak_stairs";
        }
        if (lower.contains("深灰屋顶") || lower.contains("deepslate")) {
            return "minecraft:deepslate_tile_stairs";
        }
        if ((lower.contains("橡木楼梯") || lower.contains("oak stair")) && !lower.contains("深色")
                && !lower.contains("dark_oak") && !lower.contains("dark oak")) {
            return "minecraft:oak_stairs";
        }
        if (lower.contains("深色橡木坡屋顶")
                || (lower.contains("dark_oak_planks") && lower.contains("roof"))) {
            return "minecraft:dark_oak_stairs";
        }
        return null;
    }

    private static void applyFacadeIntent(String lower, List<String> solid, List<String> windows) {
        boolean ewSolid = lower.contains("东西实墙") || lower.contains("东西山墙")
                || lower.contains("east_west_solid") || lower.contains("east_west:no_openings")
                || lower.contains("walls:east_west_solid")
                || (lower.contains("东西") && (lower.contains("实墙") || lower.contains("不要开窗")));
        boolean nsSolid = lower.contains("南北墙尽量实墙") || lower.contains("南北实墙");
        boolean northPrimary = lower.contains("北立面") || lower.contains("north_primary")
                || lower.contains("主要窗户开在北");
        boolean fourSides = lower.contains("四面都开窗") || lower.contains("四面开窗");

        if (fourSides) {
            windows.addAll(List.of("NORTH", "SOUTH", "EAST", "WEST"));
            return;
        }
        if (ewSolid) {
            solid.add("EAST");
            solid.add("WEST");
        }
        if (nsSolid) {
            solid.add("NORTH");
            solid.add("SOUTH");
        }
        if (northPrimary) {
            windows.add("NORTH");
            if (!solid.contains("EAST")) {
                // keep south for entrance-flanked windows unless marked solid
            }
            if (!windows.contains("SOUTH") && !solid.contains("SOUTH")) {
                windows.add("SOUTH");
            }
            solid.add("EAST");
            solid.add("WEST");
        }
        if (lower.contains("北墙少窗") || lower.contains("north:few")) {
            // not fully solid; leave to rhythm — do not force NORTH into solid
        }
        if (lower.contains("只在东墙和西墙开窗") || lower.contains("仅东西开窗")) {
            windows.add("EAST");
            windows.add("WEST");
            solid.add("NORTH");
            solid.add("SOUTH");
        }
    }

    private static boolean applySolidWindowSides(
            LlmPlan plan, Map<String, Object> params, List<String> features, IntentHints hints) {
        boolean changed = false;
        if (!hints.solidSides().isEmpty()
                && !params.containsKey("solid_sides") && !params.containsKey("solidSides")) {
            changed |= DesignProvenance.putIfAllowed(plan, params, "solid_sides", List.copyOf(hints.solidSides()));
        }
        if (!hints.windowSides().isEmpty()
                && !params.containsKey("window_sides") && !params.containsKey("windowSides")
                && !params.containsKey("faces") && !params.containsKey("facade_side")) {
            changed |= DesignProvenance.putIfAllowed(plan, params, "window_sides", List.copyOf(hints.windowSides()));
            changed |= DesignProvenance.putIfAllowed(plan, params, "faces", List.copyOf(hints.windowSides()));
        }
        // Feature tokens like walls:east_west_solid / east_west:no_openings
        Set<String> fromFeatures = new LinkedHashSet<>();
        for (String f : features) {
            if (f == null) continue;
            String fl = f.toLowerCase(Locale.ROOT);
            if (fl.contains("east_west_solid") || fl.contains("east_west:no_openings")
                    || fl.contains("walls:east_west")) {
                fromFeatures.add("EAST");
                fromFeatures.add("WEST");
            }
            if (fl.contains("north_south_solid") || fl.contains("north_south:no_openings")) {
                fromFeatures.add("NORTH");
                fromFeatures.add("SOUTH");
            }
        }
        if (!fromFeatures.isEmpty() && !params.containsKey("solid_sides") && !params.containsKey("solidSides")) {
            changed |= DesignProvenance.putIfAllowed(plan, params, "solid_sides", List.copyOf(fromFeatures));
        }
        return changed;
    }

    /** Thin 1-deep / 1-wide facade strips often omit faces[]; infer from features or shape. */
    private static boolean applyThinFacadeFace(
            LlmPlan plan, Map<String, Object> params, Component component, List<String> features) {
        if (params.containsKey("faces") || params.containsKey("window_sides")
                || params.containsKey("windowSides") || params.containsKey("facade_side")) {
            return false;
        }
        String fromFeature = null;
        for (String f : features) {
            if (f == null) continue;
            String fl = f.toLowerCase(Locale.ROOT);
            if (fl.contains("north")) { fromFeature = "NORTH"; break; }
            if (fl.contains("south")) { fromFeature = "SOUTH"; break; }
            if (fl.contains("east") && !fl.contains("west")) { fromFeature = "EAST"; break; }
            if (fl.contains("west") && !fl.contains("east")) { fromFeature = "WEST"; break; }
        }
        if (fromFeature == null && component.dimensions() != null) {
            int w = component.dimensions().width();
            int d = component.dimensions().depth();
            if (d == 1 && w > 1) fromFeature = "SOUTH";
            else if (w == 1 && d > 1) fromFeature = "EAST";
        }
        if (fromFeature == null) return false;
        boolean changed = DesignProvenance.putIfAllowed(plan, params, "faces", List.of(fromFeature));
        changed |= DesignProvenance.putIfAllowed(plan, params, "facade_side", fromFeature.toLowerCase(Locale.ROOT));
        return changed;
    }

    private static boolean applyFacadeRatioSides(LlmPlan plan, Map<String, Object> params) {
        if (params.containsKey("faces") || params.containsKey("window_sides")
                || params.containsKey("windowSides") || params.containsKey("facade_side")) {
            return false;
        }
        Double n = ComponentParamParsers.doubleOrNull(params, "facade_ratio_north", "facadeRatioNorth");
        Double s = ComponentParamParsers.doubleOrNull(params, "facade_ratio_south", "facadeRatioSouth");
        Double e = ComponentParamParsers.doubleOrNull(params, "facade_ratio_east", "facadeRatioEast");
        Double w = ComponentParamParsers.doubleOrNull(params, "facade_ratio_west", "facadeRatioWest");
        if (n == null && s == null && e == null && w == null) return false;

        List<String> faces = new ArrayList<>();
        List<String> solid = new ArrayList<>();
        addRatioFace(faces, solid, "NORTH", n);
        addRatioFace(faces, solid, "SOUTH", s);
        addRatioFace(faces, solid, "EAST", e);
        addRatioFace(faces, solid, "WEST", w);
        boolean changed = false;
        if (!faces.isEmpty()) {
            changed |= DesignProvenance.putIfAllowed(plan, params, "faces", faces);
            changed |= DesignProvenance.putIfAllowed(plan, params, "window_sides", faces);
        }
        if (!solid.isEmpty() && !params.containsKey("solid_sides")) {
            changed |= DesignProvenance.putIfAllowed(plan, params, "solid_sides", solid);
        }
        return changed;
    }

    private static void addRatioFace(List<String> faces, List<String> solid, String face, Double ratio) {
        if (ratio == null) return;
        if (ratio <= 0.0) solid.add(face);
        else faces.add(face);
    }

    private static int entranceSpan(Component entrance) {
        if (entrance.dimensions() == null) return 0;
        int w = entrance.dimensions().width();
        int d = entrance.dimensions().depth();
        if (w == 1 && d > 1) return d;
        if (d == 1 && w > 1) return w;
        return Math.max(w, d);
    }

    private static String stabilizeBlock(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String id = raw.trim();
        if (!id.contains(":")) id = "minecraft:" + id;
        return id.toLowerCase(Locale.ROOT);
    }

    /** Remove roof_height≤3 on wide footprints so rise can be normalized toward a visible ridge. */
    private static boolean dropShallowRoofHeight(Map<String, Object> params, Component component) {
        if (params == null || component == null || component.dimensions() == null) return false;
        Object rh = params.get("roof_height");
        if (rh == null) rh = params.get("roofHeight");
        if (!(rh instanceof Number n) || n.intValue() <= 0 || n.intValue() > 3) return false;
        if (Math.min(component.dimensions().width(), component.dimensions().depth()) < 8) return false;
        params.remove("roof_height");
        params.remove("roofHeight");
        return true;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank() || "none".equalsIgnoreCase(value)
                || "default".equalsIgnoreCase(value);
    }
}
