package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.generation.component.util.InteriorSpaceDefaults;
import com.formacraft.common.generation.design.DesignProvenance;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.StyleAttributes;
import com.formacraft.common.llm.dto.Vec3i;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
            /** True when {@link #floorCount} came from player request_intent, not LLM corpus mix. */
            boolean floorCountUserExplicit,
            /** True when wall block came from player text / request_intent materials. */
            boolean wallMaterialUserExplicit,
            /** True when roof block came from player text / request_intent materials. */
            boolean roofMaterialUserExplicit,
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
        String userText = requestIntentOriginalText(plan);

        // Vocab extractor owns negation (不要做成歇山) — prefer it over naive contains().
        // Architecture / player text wins over LLM distinguishing_features (gable_roof, flat roof)
        // that otherwise outrank shorter 歇山/穹顶 tokens by phrase length alone.
        String roofType = null;
        boolean doubleEave = false;
        boolean roofForbidsOnly = false;
        String archText = architectureOriginalText(plan);
        String roofCorpus = !userText.isBlank() ? userText
                : (archText != null && !archText.isBlank() ? archText : corpus);
        if (roofCorpus != null && !roofCorpus.isBlank()) {
            Map<String, Object> fromVocab = RequestIntentInjector.extract(roofCorpus);
            Object rt = fromVocab.get("roof_type");
            if (rt != null && !String.valueOf(rt).isBlank()) {
                String raw = String.valueOf(rt).trim().toLowerCase(Locale.ROOT);
                if (raw.contains("double_eave")) {
                    doubleEave = true;
                    roofType = raw.contains("xieshan") || raw.contains("hip_gable") ? "xieshan" : "hip";
                } else {
                    roofType = canonicalizeRoofType(raw);
                }
            } else if (fromVocab.get("roof_forbids") instanceof List<?> forbids && !forbids.isEmpty()) {
                roofForbidsOnly = true;
            }
        }
        if (roofType == null && corpus != null && !corpus.isBlank()
                && (roofCorpus == null || !roofCorpus.equals(corpus))) {
            Map<String, Object> fromFull = RequestIntentInjector.extract(corpus);
            Object rt = fromFull.get("roof_type");
            if (rt != null && !String.valueOf(rt).isBlank()) {
                String raw = String.valueOf(rt).trim().toLowerCase(Locale.ROOT);
                if (raw.contains("double_eave")) {
                    doubleEave = true;
                    roofType = raw.contains("xieshan") || raw.contains("hip_gable") ? "xieshan" : "hip";
                } else {
                    roofType = canonicalizeRoofType(raw);
                }
            } else if (!roofForbidsOnly
                    && fromFull.get("roof_forbids") instanceof List<?> forbids && !forbids.isEmpty()) {
                roofForbidsOnly = true;
            }
        }
        if (roofType == null) {
            roofType = roofTypeFromChineseContract(plan);
        }
        if (roofType == null && !roofForbidsOnly) {
            roofType = roofTypeFromCorpus(lower);
        }
        if (roofType == null && !roofForbidsOnly && plan != null && plan.styleAttributes() != null
                && plan.styleAttributes().roofForm() != null) {
            roofType = roofTypeFromCorpus(plan.styleAttributes().roofForm().toLowerCase(Locale.ROOT));
        }
        if (!doubleEave) {
            doubleEave = (lower.contains("重檐") && !roofTokenNegated(corpus, "重檐"))
                    || lower.contains("double_eave")
                    || lower.contains("double-eave")
                    || lower.contains("double_eave_hip")
                    || lower.contains("上下两层屋檐")
                    || eaveTiers(plan) >= 2;
        }

        // Avoid typology ids like timber_gable_house falsely implying stair roofs.
        boolean preferStairs = lower.contains("楼梯") || lower.contains("stair")
                || lower.contains("坡屋面") || lower.contains("坡屋顶")
                || lower.contains("pitched") || containsStandaloneToken(lower, "gable")
                || lower.contains("双坡") || lower.contains("人字")
                || (lower.contains("歇山") && !roofTokenNegated(corpus, "歇山"))
                || lower.contains("庑殿") || lower.contains("悬山");

        // Player material cues beat styleAttributes / LLM palette guesses.
        String userMaterialText = !userText.isBlank() ? userText.toLowerCase(Locale.ROOT) : lower;
        String wallFromUser = wallBlockFromRequestIntent(plan);
        if (wallFromUser == null) wallFromUser = wallBlockFromCorpus(userMaterialText);
        String roofFromUser = roofBlockFromRequestIntent(plan);
        if (roofFromUser == null) roofFromUser = roofBlockFromCorpus(userMaterialText);
        boolean wallMaterialUserExplicit = wallFromUser != null;
        boolean roofMaterialUserExplicit = roofFromUser != null;
        String wallBlock = wallFromUser != null ? wallFromUser : wallBlockFromStyle(plan);
        if (wallBlock == null) {
            wallBlock = wallBlockFromCorpus(lower);
        }
        String roofBlock = roofFromUser != null ? roofFromUser : roofBlockFromStyle(plan);
        if (roofBlock == null) {
            roofBlock = roofBlockFromCorpus(lower);
        }
        if (preferStairs && roofBlock != null && !roofBlock.contains("stairs")) {
            String stairs = com.formacraft.common.palette.component.MaterialBlockFamily.stairs(roofBlock);
            if (stairs != null) roofBlock = stairs;
        }
        String floorBlock = floorBlockFromStyle(plan);

        // Player original text wins over LLM-mixed corpus (e.g. 「三层」+「两层」).
        int userFloorCount = parseFloorCount(userText);
        boolean floorCountUserExplicit = userFloorCount > 0;
        int floorCount = floorCountUserExplicit ? userFloorCount : parseFloorCount(lower);

        String ridgeAxis = getString(lower);

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
        // double_eave is stamped only onto the primary host in enrich() — do not seed globally.
        if (preferStairs) features.add("stair_roof");
        if (doorFlank) features.add("door_flank_windows");

        int[] footprint = parseFootprint(corpus);
        return new IntentHints(roofType, doubleEave, preferStairs, wallBlock, roofBlock, floorBlock,
                floorCount, floorCountUserExplicit, wallMaterialUserExplicit, roofMaterialUserExplicit,
                ridgeAxis, doorFlank, solid, windows, features,
                footprint[0], footprint[1]);
    }

    private static @Nullable String getString(String lower) {
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
        return ridgeAxis;
    }

    static int parseFloorCount(String text) {
        if (text == null || text.isBlank()) return 0;
        String lower = text.toLowerCase(Locale.ROOT);
        // Prefer higher explicit counts when both appear (「三层」 beats a later 「两层」 gloss).
        if (lower.contains("九层") || lower.contains("9层") || lower.contains("nine-stor")) return 9;
        if (lower.contains("七层") || lower.contains("7层") || lower.contains("seven-stor")) return 7;
        if (lower.contains("五层") || lower.contains("5层") || lower.contains("five-stor")) return 5;
        if (lower.contains("四层") || lower.contains("4层") || lower.contains("four-stor")) return 4;
        if (lower.contains("三层") || lower.contains("3层") || lower.contains("three-stor")) return 3;
        if (lower.contains("两层") || lower.contains("二层") || lower.contains("2层")
                || lower.contains("two-stor") || lower.contains("two stor") || lower.contains("2-stor")) {
            return 2;
        }
        if (lower.contains("单层") || lower.contains("single-stor") || lower.contains("single stor")) return 1;
        return 0;
    }

    static String requestIntentOriginalText(LlmPlan plan) {
        if (plan == null || plan.planMeta() == null) return "";
        Object ri = plan.planMeta().get("request_intent");
        if (ri instanceof Map<?, ?> map) {
            Object text = map.get("original_text");
            if (text != null && !String.valueOf(text).isBlank()) return String.valueOf(text);
        }
        Object rit = plan.planMeta().get("request_intent_text");
        return rit == null ? "" : String.valueOf(rit);
    }

    /** Architecture contract text only — excludes LLM distinguishing_features. */
    static String architectureOriginalText(LlmPlan plan) {
        if (plan == null || plan.proportionHints() == null) return "";
        Object intent = plan.proportionHints().get("architecture_intent");
        if (!(intent instanceof Map<?, ?> map)) return "";
        Object original = map.get("original_text");
        return original == null ? "" : String.valueOf(original);
    }

    /**
     * @return {@code [width, depth]} for the building base footprint; zeros when absent.
     * Prefers tokens near 占地/底层/footprint cues. Otherwise picks the largest area so an
     * upper-tier aside like「四层 13×13」cannot outrank「底层占地 25×19」from distinguishing features.
     */
    static int[] parseFootprint(String corpus) {
        if (corpus == null || corpus.isBlank()) return new int[]{0, 0};
        Matcher m = FOOTPRINT_PATTERN.matcher(corpus);
        int bestW = 0;
        int bestD = 0;
        int bestScore = Integer.MIN_VALUE;
        while (m.find()) {
            int a = Integer.parseInt(m.group(1));
            int b = Integer.parseInt(m.group(2));
            // Building footprints in prompts are typically 4–48; skip tiny ratios like 1x2 stairs.
            if (a < 4 || b < 4 || a > 48 || b > 48) continue;
            int score = a * b;
            int from = Math.max(0, m.start() - 12);
            int to = Math.min(corpus.length(), m.end() + 12);
            String window = corpus.substring(from, to).toLowerCase(Locale.ROOT);
            if (window.contains("占地") || window.contains("底层") || window.contains("footprint")
                    || window.contains("平面") || window.contains("基地")) {
                score += 10_000;
            }
            if (window.contains("顶层") || window.contains("四层") || window.contains("上层")
                    || window.contains("一半") || window.contains("top tier") || window.contains("upper")) {
                score -= 1_000;
            }
            if (score > bestScore) {
                bestScore = score;
                bestW = a;
                bestD = b;
            }
        }
        return new int[]{bestW, bestD};
    }

    /** Stamp intent onto authored components before design validation can hard-fail. */
    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) {
            return plan;
        }
        IntentHints hints = parse(plan);
        // Also honour PlanSemanticValidator's explicit 重檐* tokens from the raw corpus.
        String corpus = intentCorpus(plan).toLowerCase(Locale.ROOT);
        boolean forceDoubleEave = hints.doubleEave()
                || (corpus.contains("重檐") && !corpus.contains("不要重檐") && !corpus.contains("无重檐"));
        if (!forceDoubleEave && hints.roofType() == null && hints.floorCount() <= 0
                && hints.footprintWidth() <= 0 && hints.wallBlock() == null
                && parseDoorWidthBlocks(corpus) <= 0
                && !corpusContainsOffice(corpus)) {
            return plan;
        }
        IntentHints effective = forceDoubleEave && !hints.doubleEave()
                ? new IntentHints(
                        hints.roofType(), true, hints.preferStairs(), hints.wallBlock(), hints.roofBlock(),
                        hints.floorBlock(), hints.floorCount(), hints.floorCountUserExplicit(),
                        hints.wallMaterialUserExplicit(), hints.roofMaterialUserExplicit(),
                        hints.ridgeAxis(), hints.doorFlankWindows(),
                        hints.solidSides(), hints.windowSides(), hints.featureSeeds(),
                        hints.footprintWidth(), hints.footprintDepth())
                : hints;
        String primaryHost = BuildingHostResolver.primaryMassId(plan, plan.components());
        int hostMassCount = 0;
        for (Component c : plan.components()) {
            if (BuildingHostResolver.isHostMass(c)) hostMassCount++;
        }
        boolean multiBuilding = hostMassCount > 1;
        List<Component> out = new ArrayList<>(plan.components().size());
        boolean changed = false;
        for (Component c : plan.components()) {
            Component enriched = enrich(plan, c, effective, primaryHost, multiBuilding);
            if (enriched != c) {
                changed = true;
            }
            out.add(enriched);
        }
        return changed ? PlanComponentRewrite.withComponents(plan, out) : plan;
    }

    static Component enrich(LlmPlan plan, Component component) {
        if (component == null) return null;
        String primary = plan == null || plan.components() == null
                ? null
                : BuildingHostResolver.primaryMassId(plan, plan.components());
        boolean multi = plan != null && plan.components() != null
                && plan.components().stream().filter(BuildingHostResolver::isHostMass).count() > 1;
        return enrich(plan, component, parse(plan), primary, multi);
    }

    static Component enrich(LlmPlan plan, Component component, IntentHints hints) {
        String primary = plan == null || plan.components() == null
                ? null
                : BuildingHostResolver.primaryMassId(plan, plan.components());
        boolean multi = plan != null && plan.components() != null
                && plan.components().stream().filter(BuildingHostResolver::isHostMass).count() > 1;
        return enrich(plan, component, hints, primary, multi);
    }

    static Component enrich(
            LlmPlan plan, Component component, IntentHints hints, String primaryHost, boolean multiBuilding
    ) {
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
        Dimensions dimsOverride = null;

        for (String seed : hints.featureSeeds()) {
            if (seed == null || seed.isBlank()) continue;
            // Never broadcast double_eave onto accessory masses/roofs via feature seeds.
            if (seed.toLowerCase(Locale.ROOT).contains("double_eave")
                    && !isInPrimaryRoofScope(component, mass, roof, structure, primaryHost, multiBuilding)) {
                continue;
            }
            if (features.stream().noneMatch(f -> f != null && f.equalsIgnoreCase(seed))) {
                features.add(seed);
                changed = true;
            }
        }

        // Explicit user roof intent overrides LLM substitutions (gable/hip for 歇山 etc.).
        boolean inPrimaryRoofScope = isInPrimaryRoofScope(
                component, mass, roof, structure, primaryHost, multiBuilding);
        // Campus specialty roofs (dome on tower) must not be stamped onto the primary reception hall.
        boolean specialtyRoof = isSpecialtyRoofToken(hints.roofType());
        boolean roofStampScope = specialtyRoof
                ? (isSpecialtyRoofCarrier(component, hints.roofType())
                    || (!multiBuilding && inPrimaryRoofScope))
                : inPrimaryRoofScope;
        if ((mass || roof || structure) && hints.roofType() != null && roofStampScope) {
            String current = ComponentPlanTypes.getParamString(params, "roof_type", "roofType");
            String want = hints.roofType();
            if (isBlank(current) || !roofTypesCompatible(want, current)) {
                params.put("roof_type", want);
                if (mass) {
                    params.put("preferred_roof_type", want);
                }
                changed = true;
            }
        }
        // Double-eave intent binds only to the primary host mass / roofs on that host.
        if ((mass || roof || structure) && hints.doubleEave() && inPrimaryRoofScope) {
            Object existing = params.get("double_eave");
            if (existing == null) existing = params.get("doubleEave");
            if (!Boolean.TRUE.equals(existing) && !"true".equalsIgnoreCase(String.valueOf(existing))) {
                params.put("double_eave", true);
                changed = true;
            }
            if (!params.containsKey("eave_tiers") && !params.containsKey("eaveTiers")) {
                params.put("eave_tiers", 2);
                changed = true;
            }
            int epl = ComponentParamParsers.intParam(params, 0, "eaves_per_level", "eavesPerLevel");
            if (epl > 0 && epl < 2) {
                params.put("eaves_per_level", 2);
                changed = true;
            }
            if (features.stream().noneMatch(f -> f != null && f.toLowerCase(Locale.ROOT).contains("double_eave"))) {
                features.add("double_eave");
                changed = true;
            }
        }

        // Material scope mirrors floors/roofs: user-explicit palette binds to primary host only.
        boolean inPrimaryMassScope = !multiBuilding
                || primaryHost == null
                || primaryHost.isBlank()
                || primaryHost.equals(BuildingHostResolver.massId(component));
        if ((mass || structure) && hints.wallBlock() != null) {
            String currentWall = ComponentPlanTypes.getParamString(
                    params, "wall_block", "material", "primary_block", "bodyBlock");
            boolean userWall = hints.wallMaterialUserExplicit();
            if (userWall && inPrimaryMassScope
                    && (isBlank(currentWall) || !materialRoleCompatible(hints.wallBlock(), currentWall))) {
                params.put("wall_block", hints.wallBlock());
                params.put("bodyBlock", hints.wallBlock());
                changed = true;
            } else if (!userWall && isBlank(currentWall) && inPrimaryMassScope) {
                changed |= DesignProvenance.putIfAllowed(plan, params, "wall_block", hints.wallBlock());
            }
        }
        if ((mass || roof || structure) && hints.roofBlock() != null) {
            boolean roofInScope = roof
                    ? isInPrimaryRoofScope(component, mass, roof, structure, primaryHost, multiBuilding)
                    : inPrimaryMassScope;
            String currentRoof = ComponentPlanTypes.getParamString(
                    params, "roof_block", "roof_material", "roof_stairs_block", "eaveBlock");
            boolean userRoof = hints.roofMaterialUserExplicit();
            if (userRoof && roofInScope
                    && (isBlank(currentRoof) || !materialRoleCompatible(hints.roofBlock(), currentRoof))) {
                params.put("roof_block", hints.roofBlock());
                changed = true;
            } else if (!userRoof && isBlank(currentRoof) && roofInScope) {
                changed |= DesignProvenance.putIfAllowed(plan, params, "roof_block", hints.roofBlock());
            }
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
                changed |= dropShallowRoofHeight(plan, params, component);
            }
        }
        // Even without prefer_stairs, shallow roof_height on a wide span flattens the ridge
        // (RoofShapeAudit no_ridge_rise) — drop so normalizeStairRoofRise can bump rise.
        if ((roof || mass) && !hints.preferStairs()) {
            changed |= dropShallowRoofHeight(plan, params, component);
        }
        if (mass && hints.floorBlock() != null
                && isBlank(ComponentPlanTypes.getParamString(params, "floor_block"))) {
            changed |= DesignProvenance.putIfAllowed(plan, params, "floor_block", hints.floorBlock());
        }
        // Global floor hints bind only to the primary host — never force a pavilion to 3 storeys.
        if ((mass || structure) && hints.floorCount() > 0 && inPrimaryMassScope) {
            int existingFloors = ComponentParamParsers.intParam(params, 0, "floor_count", "floorCount");
            boolean shouldWrite = existingFloors <= 0
                    || (hints.floorCountUserExplicit() && existingFloors != hints.floorCount());
            if (shouldWrite) {
                // USER_EXPLICIT floor count overwrites wrong LLM values on the primary host.
                params.put("floor_count", hints.floorCount());
                params.put("levels", hints.floorCount());
                changed = true;
                if (ComponentParamParsers.intParam(params, 0, "floor_height", "floorHeight") <= 0) {
                    int bodyH = component.dimensions() != null ? component.dimensions().height() : 0;
                    int minBand = InteriorSpaceDefaults.minStoreyBand();
                    int fh = hints.floorCount() <= 1 ? Math.max(minBand, bodyH)
                            : Math.max(minBand, bodyH > 0 ? bodyH / hints.floorCount() : minBand);
                    params.put("floor_height", fh);
                }
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

        // 「正门宽三格」 binds to the primary/main entrance only — never side doors.
        int requestedDoor = parseDoorWidthBlocks(requestIntentOriginalText(plan));
        if (requestedDoor <= 0) {
            requestedDoor = parseDoorWidthBlocks(intentCorpus(plan));
        }
        boolean mainEntranceTarget = isPrimaryEntrance(component, entrance, mass, primaryHost);
        if (mainEntranceTarget && (mass || entrance) && requestedDoor > 0
                && ComponentParamParsers.intParam(params, 0, "door_width", "doorWidth") != requestedDoor) {
            params.put("door_width", requestedDoor);
            changed = true;
        }
        if (entrance) {
            int existing = ComponentParamParsers.intParam(params, 0, "door_width", "doorWidth");
            if (mainEntranceTarget && requestedDoor > 0 && existing != requestedDoor) {
                params.put("door_width", requestedDoor);
                changed = true;
            } else if (existing <= 0 && !params.containsKey("door_width") && !params.containsKey("doorWidth")) {
                int span = entranceSpan(component);
                if (span > 0) {
                    // Soft default only; explicit 正门 width stays on mainEntranceTarget above.
                    int door = mainEntranceTarget && requestedDoor > 0
                            ? requestedDoor
                            : symmetricDoorWidth(span);
                    changed |= DesignProvenance.putIfAllowed(plan, params, "door_width", door);
                }
            }
            // Explicit 正门宽三格 needs an opening box of at least door+1 so maxDoor can hold it.
            int doorW = ComponentParamParsers.intParam(params, 0, "door_width", "doorWidth");
            if (mainEntranceTarget && doorW >= 3 && component.dimensions() != null) {
                Dimensions ed = component.dimensions();
                int need = doorW + 1;
                boolean ew = ed.width() == 1 && ed.depth() > 1;
                boolean ns = ed.depth() == 1 && ed.width() > 1;
                if (ew && ed.depth() < need) {
                    dimsOverride = new Dimensions(ed.width(), need, ed.height());
                    changed = true;
                } else if (ns && ed.width() < need) {
                    dimsOverride = new Dimensions(need, ed.depth(), ed.height());
                    changed = true;
                } else if (!ew && !ns && Math.min(ed.width(), ed.depth()) >= 1
                        && Math.max(ed.width(), ed.depth()) < need) {
                    dimsOverride = ed.width() >= ed.depth()
                            ? new Dimensions(need, ed.depth(), ed.height())
                            : new Dimensions(ed.width(), need, ed.height());
                    changed = true;
                }
            }
        }

        Dimensions dims = dimsOverride != null ? dimsOverride : component.dimensions();
        Vec3i position = component.relativePosition();
        if (mass && dims != null) {
            int ownFloors = ComponentParamParsers.intParam(params, 0, "floor_count", "floorCount");
            // Global floor hints inflate only the primary host — accessory pavilions keep own storeys.
            int floors = inPrimaryMassScope
                    ? Math.max(hints.floorCount(), ownFloors)
                    : ownFloors;
            String corpus = intentCorpus(plan).toLowerCase(Locale.ROOT);
            // Residential "glass curtain wall" / Modern_International style is not an office plate cue.
            // Require 办公/office/commercial — bare 幕墙/curtain alone is too broad.
            boolean officeLike = corpusContainsOffice(corpus);
            boolean explicitFootprint = hints.footprintWidth() > 0 && hints.footprintDepth() > 0;
            // Plate-size optimization is office/commercial only — never force 14×12 on towers/halls.
            // Explicit player footprints (e.g. 7×7) are hard constraints for every building class.
            // Footprint force/grow binds to the primary host so top-tier asides and factory halls are
            // not recentered. Office annexes still receive vertical storey-band repair for bridges.
            // Office plate-grow binds to office-named MASS_MAIN bodies — never to workshop/factory
            // primaries (corpus「三层」must not office-stack a sawtooth hall) and never to secondary
            // floor plates / corridor bridges (that recenters campuses and orphans exterior stairs).
            // Exact W×D stays primary-host only so top-tier asides (13×13) cannot crush stepped homes.
            boolean officeBody = looksOfficeBodyMass(component);
            boolean workshopBody = looksWorkshopBodyMass(component);
            if (officeLike && !explicitFootprint && officeBody && !workshopBody) {
                // Annex offices: repair vertical storey bands only. Footprint grow/recenter
                // orphans campus exterior stairs (late_4); bridge contact is recovered by the
                // post-intent HostedPlateLevelRepair stretch instead.
                int nh = applyOfficeStoreyStack(params, corpus, floors, dims.height());
                // floor_count / floor_height params may still update
                if (nh != dims.height()) {
                    dims = new Dimensions(dims.width(), dims.depth(), nh);
                }
                changed = true;
            } else if (officeLike && explicitFootprint && inPrimaryMassScope && !workshopBody) {
                // Honour explicit W×D on the primary host; only repair the vertical storey stack.
                int nw = hints.footprintWidth();
                int nd = hints.footprintDepth();
                int nh = applyOfficeStoreyStack(params, corpus, floors, dims.height());
                changed = true;
                if (nw != dims.width() || nd != dims.depth() || nh != dims.height()) {
                    if (position != null && (nw != dims.width() || nd != dims.depth())
                            && !com.formacraft.common.generation.component.util.ComponentFootprintUtil
                            .isCornerAnchor(params)) {
                        int dx = (nw - dims.width()) / 2;
                        int dz = (nd - dims.depth()) / 2;
                        position = new Vec3i(position.x() - dx, position.y(), position.z() - dz);
                    }
                    dims = new Dimensions(nw, nd, nh);
                    changed = true;
                }
            } else if (floors >= 2 && !officeLike) {
                // Multi-storey non-office: keep ≥3 air clear (storey band ≥4). Honour authored
                // floor_height when already ≥ min band — do not invent a taller lobby that would
                // shift connection_level / plate Y for bridges and mid-floor attachments.
                int nh = dims.height();
                int minBand = InteriorSpaceDefaults.minStoreyBand();
                int authoredFh = ComponentParamParsers.intParam(params, 0, "floor_height", "floorHeight");
                int authoredGround = ComponentParamParsers.intParam(
                        params, 0, "ground_clear_height", "groundClearHeight");
                int upperBand = authoredFh > 0 ? Math.max(minBand, authoredFh) : minBand;
                int groundBand = authoredGround > 0 ? Math.max(minBand, authoredGround) : upperBand;
                int f = Math.max(2, floors);
                // +1 roof / top slab deck above the uppermost storey band.
                int needH = groundBand + Math.max(0, f - 1) * upperBand + 1;
                boolean tooLowBand = authoredFh > 0 && authoredFh < minBand;
                if (nh < needH || tooLowBand || authoredFh <= 0) {
                    nh = Math.max(nh, needH);
                    params.put("floor_count", f);
                    params.put("floor_height", upperBand);
                    if (authoredGround > 0 || groundBand != upperBand) {
                        params.put("ground_clear_height", groundBand);
                    }
                    dims = new Dimensions(dims.width(), dims.depth(), nh);
                    changed = true;
                }
            }
        }

        if (!changed) return component;
        return new Component(
                component.componentType(),
                component.slotId(),
                position,
                dims,
                features,
                params);
    }

    static int symmetricDoorWidth(int openingSpan) {
        return com.formacraft.common.generation.component.impl.EntranceGenerator.symmetricDoorWidth(openingSpan);
    }

    /** Leaf width requested in the plan corpus (「正门宽三格」); 0 when absent. */
    static int requestedDoorWidth(LlmPlan plan) {
        return parseDoorWidthBlocks(intentCorpus(plan));
    }

    /** Parse 「正门宽三格」「门宽3格」「door width 3」 → leaf width in blocks; 0 when absent. */
    static int parseDoorWidthBlocks(String corpus) {
        if (corpus == null || corpus.isBlank()) return 0;
        String lower = corpus.toLowerCase(Locale.ROOT);
        Matcher m = Pattern.compile(
                "(?:正门|大门|门)\\s*宽\\s*([一二三四五六七八九十两\\d])\\s*格"
                        + "|door\\s*width\\s*[:=]?\\s*(\\d)"
                        + "|(\\d)\\s*-?\\s*block\\s*door").matcher(lower);
        if (m.find()) {
            for (int g = 1; g <= m.groupCount(); g++) {
                if (m.group(g) != null) return chineseOrDigit(m.group(g));
            }
        }
        if (lower.contains("宽三格") || lower.contains("三扇门")) return 3;
        if (lower.contains("宽两格") || lower.contains("宽二格") || lower.contains("双开门") || lower.contains("两扇门")) {
            return 2;
        }
        if (lower.contains("宽四格")) return 4;
        if (lower.contains("宽五格")) return 5;
        return 0;
    }

    private static int chineseOrDigit(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        return switch (raw.trim()) {
            case "1", "一" -> 1;
            case "2", "二", "两" -> 2;
            case "3", "三" -> 3;
            case "4", "四" -> 4;
            case "5", "五" -> 5;
            case "6", "六" -> 6;
            case "7", "七" -> 7;
            case "8", "八" -> 8;
            case "9", "九" -> 9;
            default -> {
                try {
                    yield Integer.parseInt(raw.trim());
                } catch (NumberFormatException e) {
                    yield 0;
                }
            }
        };
    }

    private static boolean corpusContainsOffice(String corpus) {
        if (corpus == null || corpus.isBlank()) return false;
        String lower = corpus.toLowerCase(Locale.ROOT);
        // Do not treat style_profile Modern_International / curtain-wall facade copy as office plates.
        return lower.contains("办公") || lower.contains("office")
                || lower.contains("商业") || lower.contains("commercial");
    }

    private static boolean looksOfficeBodyMass(Component component) {
        if (component == null || component.componentType() == null) return false;
        String type = component.componentType().trim().toUpperCase(Locale.ROOT);
        if (!("MASS_MAIN".equals(type) || "MAIN_MASS".equals(type))) return false;
        String id = BuildingHostResolver.massId(component);
        if (id == null) return false;
        String lower = id.toLowerCase(Locale.ROOT);
        return lower.contains("office") || lower.contains("办公");
    }

    private static boolean looksWorkshopBodyMass(Component component) {
        if (component == null) return false;
        String id = BuildingHostResolver.massId(component);
        if (id == null) return false;
        String lower = id.toLowerCase(Locale.ROOT);
        return lower.contains("workshop") || lower.contains("factory") || lower.contains("boiler")
                || lower.contains("车间") || lower.contains("厂房") || lower.contains("sawtooth");
    }

    static String intentCorpus(LlmPlan plan) {
        if (plan == null) return "";
        StringBuilder sb = new StringBuilder();
        // Player text is authoritative — read request_intent before LLM-authored features.
        if (plan.planMeta() != null) {
            Object ri = plan.planMeta().get("request_intent");
            if (ri instanceof Map<?, ?> map) {
                Object text = map.get("original_text");
                if (text != null) sb.append(' ').append(text);
                Object markers = map.get("markers");
                if (markers instanceof List<?> list) {
                    for (Object m : list) {
                        if (m != null) sb.append(' ').append(m);
                    }
                }
                Object cls = map.get("building_class");
                if (cls != null) sb.append(' ').append(cls);
            }
            Object rit = plan.planMeta().get("request_intent_text");
            if (rit != null) sb.append(' ').append(rit);
        }
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
        if (lower.contains("穹顶") || lower.contains("dome") || lower.contains("polyhedral_dome")
                || lower.contains("faceted_dome")) {
            return "dome";
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
            case "wu_dian", "wudian", "庑殿", "chinese_hip", "double_eave_hip" -> "hip";
            case "xie_shan", "xieshan", "歇山", "hip_and_gable", "hip_gable", "double_eave_xieshan" -> "xieshan";
            case "xuan_shan", "xuanshan", "悬山" -> "xuanshan";
            case "ying_shan", "yingshan", "硬山" -> "yingshan";
            default -> v;
        };
    }

    private static boolean roofTypesCompatible(String want, String have) {
        if (want == null || have == null) return want == have;
        String a = want.trim().toLowerCase(Locale.ROOT);
        String b = have.trim().toLowerCase(Locale.ROOT);
        if (a.equals(b)) return true;
        // Do NOT use substring contains — hip ⊆ hip_gable but they are different forms.
        var ak = com.formacraft.common.generation.component.util.RoofTypeCatalog.parseExplicit(a);
        var bk = com.formacraft.common.generation.component.util.RoofTypeCatalog.parseExplicit(b);
        if (ak.isPresent() && bk.isPresent()) {
            return ak.get() == bk.get();
        }
        // Synonym tokens only when catalog cannot parse either side.
        return synonymRoofToken(a).equals(synonymRoofToken(b));
    }

    private static String synonymRoofToken(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        return switch (s) {
            case "hip_roof", "si_po", "庑殿", "庑殿顶" -> "hip";
            case "hip_gable", "xieshan", "歇山", "歇山顶" -> "xieshan";
            case "gable", "gable_roof", "人字", "双坡", "悬山" -> "gable";
            case "pyramid", "pyramidal", "攒尖", "攒尖顶" -> "pyramid";
            case "double_eave_hip", "重檐庑殿" -> "double_eave_hip";
            case "double_eave_xieshan", "重檐歇山" -> "double_eave_xieshan";
            default -> s;
        };
    }

    /** True when extract() marked the roof token as forbidden (不要做成歇山). */
    private static boolean roofTokenNegated(String corpus, String token) {
        if (corpus == null || corpus.isBlank() || token == null) return false;
        Map<String, Object> fromVocab = RequestIntentInjector.extract(corpus);
        if (fromVocab.get("roof_type") != null) {
            // A positive roof choice exists — token may still be forbidden separately.
        }
        String canonical = switch (token) {
            case "歇山" -> "xieshan";
            case "重檐" -> "double_eave";
            default -> token.toLowerCase(Locale.ROOT);
        };
        if (fromVocab.get("roof_forbids") instanceof List<?> forbids) {
            for (Object f : forbids) {
                if (f == null) continue;
                String s = String.valueOf(f).toLowerCase(Locale.ROOT);
                if (s.contains(canonical) || canonical.contains(s)) return true;
            }
        }
        int idx = corpus.toLowerCase(Locale.ROOT).indexOf(token.toLowerCase(Locale.ROOT));
        return idx >= 0 && RequestIntentInjector.isNegatedAt(corpus, idx);
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
        if (lower == null || lower.isBlank()) return null;
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
        // Whole-building body color: 「白色藏式佛塔」— primary shaft, not doors/roof trim.
        if ((lower.contains("白色") || lower.contains("white "))
                && (lower.contains("佛塔") || lower.contains("白塔") || lower.contains("古塔")
                || lower.contains("宝塔") || lower.contains("stupa") || lower.contains("pagoda")
                || lower.contains("塔"))) {
            return "minecraft:white_concrete";
        }
        if (lower.contains("石砖墙") || lower.contains("stone_brick")) {
            return "minecraft:stone_bricks";
        }
        return null;
    }

    private static String roofBlockFromCorpus(String lower) {
        if (lower == null || lower.isBlank()) return null;
        if (lower.contains("黑瓦") || lower.contains("black_terracotta")
                || lower.contains("black tile") || lower.contains("black roof")) {
            return "minecraft:black_terracotta";
        }
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

    @SuppressWarnings("unchecked")
    private static String wallBlockFromRequestIntent(LlmPlan plan) {
        Map<String, Object> mats = requestIntentMaterials(plan);
        if (mats == null) return null;
        Object wall = mats.get("wall");
        if (wall == null || String.valueOf(wall).isBlank()) return null;
        return stabilizeBlock(String.valueOf(wall));
    }

    @SuppressWarnings("unchecked")
    private static String roofBlockFromRequestIntent(LlmPlan plan) {
        Map<String, Object> mats = requestIntentMaterials(plan);
        if (mats == null) return null;
        Object roof = mats.get("roof");
        if (roof == null || String.valueOf(roof).isBlank()) return null;
        return stabilizeBlock(String.valueOf(roof));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requestIntentMaterials(LlmPlan plan) {
        if (plan == null || plan.planMeta() == null) return null;
        Object ri = plan.planMeta().get("request_intent");
        if (!(ri instanceof Map<?, ?> map)) return null;
        Object mats = map.get("materials");
        if (!(mats instanceof Map<?, ?>)) return null;
        return (Map<String, Object>) mats;
    }

    /**
     * True when have is an acceptable substitute for want (same color/family role).
     * Opposite colors (white vs oak/stone brick walls, black tile vs oak stairs) are incompatible.
     */
    static boolean materialRoleCompatible(String want, String have) {
        if (want == null || have == null) return want == have;
        String a = stabilizeBlock(want);
        String b = stabilizeBlock(have);
        if (a.equals(b)) return true;
        if (a.contains("white") || a.contains("quartz")) {
            return b.contains("white") || b.contains("quartz") || b.contains("smooth_quartz");
        }
        if (a.contains("black_terracotta") || a.contains("blackstone") || a.contains("black_")) {
            return b.contains("black") || b.contains("deepslate") || b.contains("dark");
        }
        if (a.contains("dark_oak")) return b.contains("dark_oak");
        if (a.contains("oak") && !a.contains("dark")) {
            return b.contains("oak") && !b.contains("dark");
        }
        return a.equals(b);
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
        Set<String> fromFeatures = getStrings(features);
        if (!fromFeatures.isEmpty() && !params.containsKey("solid_sides") && !params.containsKey("solidSides")) {
            changed |= DesignProvenance.putIfAllowed(plan, params, "solid_sides", List.copyOf(fromFeatures));
        }
        return changed;
    }

    private static @NotNull Set<String> getStrings(List<String> features) {
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
        return fromFeatures;
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

    /**
     * True when this component may receive primary-roof intent (double_eave / roof_type stamping).
     * Accessory pavilions keep their own roof constraints.
     */
    private static boolean isInPrimaryRoofScope(
            Component component,
            boolean mass,
            boolean roof,
            boolean structure,
            String primaryHost,
            boolean multiBuilding
    ) {
        if (primaryHost == null || primaryHost.isBlank()) return true;
        if (mass || structure) {
            String self = BuildingHostResolver.massId(component);
            if (primaryHost.equals(self)) return true;
            // Upper floors / bound plates on the primary host declare roof_type too.
            String host = BuildingHostResolver.roofHostId(component);
            return host != null && primaryHost.equals(host);
        }
        if (roof) {
            String host = BuildingHostResolver.roofHostId(component);
            if (host == null || host.isBlank()) {
                // Multi-building: unbound accessory roofs must NOT inherit primary 重檐.
                return !multiBuilding;
            }
            return primaryHost.equals(host);
        }
        return false;
    }

    private static boolean isSpecialtyRoofToken(String want) {
        if (want == null || want.isBlank()) return false;
        String w = want.trim().toLowerCase(Locale.ROOT);
        return w.contains("dome") || w.contains("pyramid") || w.contains("cone")
                || w.contains("onion") || w.contains("mansard");
    }

    /** Landmark specialty roofs land on tower/observation hosts, not the campus primary hall. */
    private static boolean isSpecialtyRoofCarrier(Component component, String want) {
        if (component == null || !isSpecialtyRoofToken(want)) return false;
        StringBuilder blob = new StringBuilder();
        String self = BuildingHostResolver.massId(component);
        if (self != null) blob.append(' ').append(self);
        String host = BuildingHostResolver.roofHostId(component);
        if (host != null) blob.append(' ').append(host);
        if (component.slotId() != null) blob.append(' ').append(component.slotId());
        if (component.features() != null) {
            for (String f : component.features()) {
                if (f != null) blob.append(' ').append(f);
            }
        }
        if (component.params() != null) {
            for (String key : List.of(
                    "building_role", "buildingRole", "role", "part",
                    "component_id", "componentId", "mass_id", "massId")) {
                Object v = component.params().get(key);
                if (v != null) blob.append(' ').append(v);
            }
        }
        String lower = blob.toString().toLowerCase(Locale.ROOT);
        return lower.contains("tower") || lower.contains("dome") || lower.contains("observation")
                || lower.contains("observatory") || lower.contains("塔") || lower.contains("穹")
                || lower.contains("观景") || lower.contains("观测") || lower.contains("pyramid")
                || lower.contains("spire");
    }

    /** 「正门」 / main entrance — side_entrance and accessory doors stay out of scope. */
    private static boolean isPrimaryEntrance(
            Component component, boolean entrance, boolean mass, String primaryHost
    ) {
        if (mass) {
            if (primaryHost == null || primaryHost.isBlank()) return true;
            return primaryHost.equals(BuildingHostResolver.massId(component));
        }
        if (!entrance || component == null) return false;
        // Host binding first — side-hall main_entrance must not receive hall_main 正门 width.
        if (primaryHost != null && !primaryHost.isBlank()) {
            String host = BuildingHostResolver.roofHostId(component);
            if (host != null && !host.isBlank() && !primaryHost.equals(host)) {
                return false;
            }
        }
        String slot = component.slotId() == null ? "" : component.slotId().toLowerCase(Locale.ROOT);
        if (slot.contains("side") || slot.contains("back") || slot.contains("balcony")
                || slot.contains("侧") || slot.contains("后门") || slot.contains("旁门")) {
            return false;
        }
        if (component.params() != null) {
            Object role = component.params().get("entrance_role");
            if (role == null) role = component.params().get("entranceRole");
            if (role == null) role = component.params().get("door_role");
            if (role != null) {
                String r = String.valueOf(role).toLowerCase(Locale.ROOT);
                if (r.contains("side") || r.contains("secondary") || r.contains("back")
                        || r.contains("balcony") || r.contains("侧") || r.contains("旁")) {
                    return false;
                }
                if (r.contains("main") || r.contains("primary") || r.contains("正门") || r.contains("front")) {
                    return true;
                }
            }
            Object id = component.params().get("component_id");
            if (id == null) id = component.params().get("entrance_id");
            if (id != null) {
                String s = String.valueOf(id).toLowerCase(Locale.ROOT);
                if (s.contains("side") || s.contains("secondary") || s.contains("back")
                        || s.contains("balcony") || s.contains("侧")) {
                    return false;
                }
            }
        }
        if (slot.contains("main") || slot.contains("front") || slot.contains("正门") || slot.isBlank()) {
            return true;
        }
        return slot.contains("entrance") && !slot.contains("side");
    }

    private static int applyOfficeStoreyStack(
            Map<String, Object> params, String corpus, int floors, int nh
    ) {
        boolean wantsRound = corpus.contains("圆形") || corpus.contains("圆楼")
                || corpus.contains("圆柱") || corpus.contains("circle")
                || corpus.contains("circular") || corpus.contains("round plan");
        if (!wantsRound) {
            for (String key : List.of(
                    "shape", "footprint_shape", "footprintShape", "plan_type", "planType")) {
                Object shape = params.get(key);
                if (shape != null) {
                    String s = String.valueOf(shape).toLowerCase(Locale.ROOT);
                    if (s.contains("circle") || s.contains("round") || s.contains("cylinder")
                            || s.contains("圆")) {
                        params.put(key, "rectangle");
                    }
                }
            }
        }
        // Office: prefer 4 air clear on ground (≥3), upper ≥3 air → storey bands clear+1.
        int groundClear = InteriorSpaceDefaults.OFFICE_GROUND_CLEAR_HEIGHT;
        if (corpus.contains("底层") && (corpus.contains("净高") || corpus.contains("明显高于"))) {
            groundClear = Math.max(groundClear, InteriorSpaceDefaults.OFFICE_GROUND_CLEAR_HEIGHT + 1);
        }
        int upperClear = InteriorSpaceDefaults.DEFAULT_UPPER_CLEAR_HEIGHT;
        int groundBand = Math.max(
                InteriorSpaceDefaults.storeyBandForClear(groundClear),
                ComponentParamParsers.intParam(params, 0, "ground_clear_height", "groundClearHeight"));
        // Never leave an authored office ground band below min clear air.
        groundBand = Math.max(groundBand, InteriorSpaceDefaults.minStoreyBand());
        int upperBand = InteriorSpaceDefaults.storeyBandForClear(upperClear);
        int f = Math.max(2, floors > 0 ? floors : 3);
        int needH = groundBand + Math.max(0, f - 1) * upperBand + 1; // +1 roof deck
        if (nh < needH || (nh > needH + 2 && floors > 0)) {
            nh = needH;
        }
        params.put("floor_count", f);
        params.put("floor_height", upperBand);
        params.put("ground_clear_height", groundBand);
        return nh;
    }

    /**
     * Remove roof_height≤3 on wide footprints so rise can be normalized toward a visible ridge.
     * USER_EXPLICIT / design-locked roof_height is never dropped.
     */
    private static boolean dropShallowRoofHeight(LlmPlan plan, Map<String, Object> params, Component component) {
        if (params == null || component == null || component.dimensions() == null) return false;
        Object rh = params.get("roof_height");
        if (rh == null) rh = params.get("roofHeight");
        if (!(rh instanceof Number n) || n.intValue() <= 0 || n.intValue() > 3) return false;
        if (Math.min(component.dimensions().width(), component.dimensions().depth()) < 8) return false;
        if (!DesignProvenance.canMutateParam(plan, params, "roof_height")) {
            return false;
        }
        return DesignProvenance.removeIfAllowed(plan, params, "roof_height", "roofHeight");
    }

    /** True when {@code token} appears bounded by non-alphanumeric chars (not inside typology ids). */
    private static boolean containsStandaloneToken(String haystack, String token) {
        if (haystack == null || token == null || token.isEmpty()) return false;
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank() || "none".equalsIgnoreCase(value)
                || "default".equalsIgnoreCase(value);
    }
}
