package com.formacraft.server.city;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.model.build.BuildingType;
import com.formacraft.common.model.city.CitySpec;
import com.formacraft.common.model.path.PathSpec;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.profile.StyleProfileRegistry;
import com.formacraft.server.build.GeneratedStructure;
import com.formacraft.server.build.PlannedBlock;
import com.formacraft.server.generator.BridgeGenerator;
import com.formacraft.server.generator.StructureGenerator;
import com.formacraft.server.generation.GenerationHub;
import com.formacraft.server.generator.path.PathGenerator;
import com.formacraft.server.terrain.TerrainAdaptationEngine;
import com.formacraft.server.terrain.TerrainAdaptationMode;
import com.formacraft.server.terrain.TerrainAdaptationResolver;
import com.formacraft.server.terrain.TerrainAdaptationSpec;
import com.formacraft.server.terrain.TerrainFit;
import com.formacraft.server.terrain.TerrainPolicy;
import com.formacraft.server.terrain.TerrainPolicyResolver;
import com.formacraft.server.terrain.ClusterTerrainStrategy;
import com.formacraft.server.terrain.ZoneTerrainRule;
import com.formacraft.server.terrain.ClusterTerrainPolicy;
import com.formacraft.server.build.BuildReportContext;
import com.formacraft.server.cluster.TerrainFields;
import com.formacraft.server.cluster.layout.BuildArea;
import com.formacraft.server.cluster.layout.AnchoredBuildArea;
import com.formacraft.server.cluster.layout.BuildingPlacement;
import com.formacraft.server.cluster.layout.BuildingUnit;
import com.formacraft.server.cluster.layout.Candidate;
import com.formacraft.server.cluster.layout.CandidateGenerator;
import com.formacraft.server.cluster.layout.ClusterLayoutConfig;
import com.formacraft.server.cluster.layout.PlacementSolver;
import com.formacraft.server.foundation.FoundationPlanner;
import com.formacraft.server.generator.selector.RuleBasedGeneratorSelector;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * 城市生成器
 * 将 CitySpec 转换为实际的方块结构
 */
public class CityBuilder {
    
    private final PathGenerator pathGenerator = new PathGenerator();
    private final BridgeGenerator bridgeGenerator = new BridgeGenerator();

    /**
     * 生成整个城市
     * @param city 城市规格
     * @param origin 城市中心点
     * @param world 服务器世界
     * @return 生成的完整城市结构
     */
    public GeneratedStructure generate(CitySpec city, BlockPos origin, ServerWorld world) {
        if (city == null) {
            return new GeneratedStructure(null, origin, "Empty City", new ArrayList<>());
        }

        List<PlannedBlock> merged = new ArrayList<>();

        // 1. 生成建筑
        if (city.getStructures() != null) {
            // Optional: if any structure has missing offset, auto-place them via cluster layout.
            boolean anyMissingOffset = false;
            for (CitySpec.StructurePlan sp0 : city.getStructures()) {
                if (sp0 != null && sp0.getSpec() != null && sp0.getOffset() == null) { anyMissingOffset = true; break; }
            }

            java.util.Map<CitySpec.StructurePlan, BlockPos> autoOffsets = java.util.Map.of();
            java.util.Map<String, Object> extra0 = null;
            if (anyMissingOffset) {
                int count = 0;
                int maxW = 10;
                int maxD = 10;
                int maxH = 12;
                for (CitySpec.StructurePlan sp0 : city.getStructures()) {
                    if (sp0 == null || sp0.getOffset() != null) continue;
                    count++;
                    if (sp0.getSpec() != null && sp0.getSpec().getFootprint() != null) {
                        maxW = Math.max(maxW, sp0.getSpec().getFootprint().getWidth());
                        maxD = Math.max(maxD, sp0.getSpec().getFootprint().getDepth());
                    }
                    if (sp0.getSpec() != null) maxH = Math.max(maxH, sp0.getSpec().getHeight());
                }
                int spacing = Math.max(14, Math.max(maxW, maxD) + 4);
                int boxHalf = Math.max(24, (int) Math.round(Math.sqrt(Math.max(1, count)) * spacing));

                // Use the first spec.extra as config source if available (fallback defaults).
                for (CitySpec.StructurePlan sp0 : city.getStructures()) {
                    if (sp0 != null && sp0.getSpec() != null && sp0.getSpec().getExtra() != null) { extra0 = sp0.getSpec().getExtra(); break; }
                }
                ClusterLayoutConfig cfg = ClusterLayoutConfig.fromExtra(extra0, boxHalf, boxHalf, count, spacing);

                BuildArea area = new BuildArea(cfg.halfX, cfg.halfZ);
                TerrainFields fields = TerrainFields.sample(world, origin, cfg.halfX, cfg.halfZ, 2);

                // Optional: J-layer skeleton layout anchors (spec.extra.skeletonLayout).
                // Keyed by zoneType (CORE/PUBLIC/PRIVATE/SERVICE/TRANSITION/SEMI_PUBLIC...).
                final java.util.Map<String, BlockPos> skeletonAnchorByZoneType = parseSkeletonAnchorsByZoneType(extra0);
                final java.util.Map<String, SkeletonNodeInfo> skeletonNodeByZoneType = parseSkeletonNodesByZoneType(extra0);

                List<BuildingUnit> units = new ArrayList<>(count);
                java.util.Map<String, List<Candidate>> candidatesById = new java.util.HashMap<>();

                // We treat each missing-offset structure as one unit type (id by index to allow different sizes).
                int idx = 0;
                java.util.List<CitySpec.StructurePlan> missing = new java.util.ArrayList<>();
                for (CitySpec.StructurePlan sp0 : city.getStructures()) {
                    if (sp0 == null || sp0.getOffset() != null) continue;
                    if (sp0.getSpec() == null) {
                        // allow selector to synthesize a minimal spec later
                        sp0.setSpec(new BuildingSpec());
                    }
                    missing.add(sp0);
                }

                // Pick a "main building" by volume proxy (w*d*h) among missing-offset structures.
                int mainIdx = -1;
                long bestVol = -1;
                for (int i = 0; i < missing.size(); i++) {
                    BuildingSpec bs = missing.get(i).getSpec();
                    if (bs == null) continue;
                    int w0 = (bs.getFootprint() != null && bs.getFootprint().getWidth() > 0) ? bs.getFootprint().getWidth() : 8;
                    int d0 = (bs.getFootprint() != null && bs.getFootprint().getDepth() > 0) ? bs.getFootprint().getDepth() : 6;
                    int h0 = Math.max(4, bs.getHeight());
                    long vol = (long) w0 * (long) d0 * (long) h0;
                    if (vol > bestVol) { bestVol = vol; mainIdx = i; }
                }

                for (CitySpec.StructurePlan sp0 : missing) {
                    BuildingSpec bs = sp0.getSpec();
                    String role = inferSemanticRoleForPlan(bs, sp0, city, idx == mainIdx);

                    // Apply K+ selector: fill deterministic template/landmark + minimal defaults from skeleton.
                    try {
                        BuildingStyle cityStyle = null;
                        try {
                            if (city.getStyle() != null && !city.getStyle().isBlank()) {
                                cityStyle = BuildingStyle.valueOf(city.getStyle().trim().toUpperCase(java.util.Locale.ROOT));
                            }
                        } catch (Exception ignored) {}
                        if (cityStyle == null) cityStyle = (bs != null && bs.getStyle() != null) ? bs.getStyle() : BuildingStyle.DEFAULT;

                        SkeletonNodeInfo sk = (skeletonNodeByZoneType != null && role != null) ? skeletonNodeByZoneType.get(role) : null;
                        String skShape = (sk != null) ? sk.shapeUpper : "";
                        int skW = (sk != null) ? sk.width : 0;
                        int skD = (sk != null) ? sk.depth : 0;
                        int skR = (sk != null) ? sk.radius : 0;
                        RuleBasedGeneratorSelector.apply(bs, cityStyle, role, skShape, skW, skD, skR);
                    } catch (Throwable ignored) {}

                    // Prefer explicit footprint; if missing/invalid, fall back to skeleton node dimensions (J-layer).
                    int w = (bs.getFootprint() != null && bs.getFootprint().getWidth() > 0) ? bs.getFootprint().getWidth() : 0;
                    int d = (bs.getFootprint() != null && bs.getFootprint().getDepth() > 0) ? bs.getFootprint().getDepth() : 0;
                    if ((w <= 0 || d <= 0) && skeletonNodeByZoneType != null && role != null) {
                        SkeletonNodeInfo info = skeletonNodeByZoneType.get(role);
                        if (info != null) {
                            if ("CIRCLE".equals(info.shapeUpper) && info.radius > 0) {
                                // approximate circle as square for placement footprint (conservative)
                                w = Math.max(w, info.radius * 2 + 2);
                                d = Math.max(d, info.radius * 2 + 2);
                                // optional: steer generator toward circular topology when spec footprint is missing
                                tryEnsureCircleFootprint(bs, info.radius);
                            } else if ("RECTANGLE".equals(info.shapeUpper) && info.width > 0 && info.depth > 0) {
                                w = Math.max(w, info.width);
                                d = Math.max(d, info.depth);
                                tryEnsureRectFootprint(bs, w, d);
                            }
                        }
                    }
                    if (w <= 0) w = 8;
                    if (d <= 0) d = 6;
                    int h = Math.max(4, bs.getHeight());
                    String id = "city_unit_" + idx;
                    int importance = computeAutoImportance(bs, idx, mainIdx, bestVol);
                    BuildingUnit u = new BuildingUnit(id, w, d, h, importance, role);
                    units.add(u);

                    // If skeleton layout provides an anchor for this role, bias candidate sampling around it.
                    BuildArea areaForUnit = area;
                    try {
                        BlockPos centerRel = (skeletonAnchorByZoneType != null && role != null) ? skeletonAnchorByZoneType.get(role) : null;
                        if (centerRel != null) {
                            int sampleHalf = Math.max(10, Math.min(cfg.halfX, spacing));
                            areaForUnit = new AnchoredBuildArea(centerRel, cfg.halfX, cfg.halfZ, sampleHalf, sampleHalf);
                        }
                    } catch (Throwable ignored) {}

                    List<Candidate> cands = CandidateGenerator.generate(u, areaForUnit, fields, world, origin, cfg);
                    candidatesById.put(id, cands);
                    idx++;
                }

                List<BuildingPlacement> placed = PlacementSolver.solve(units, candidatesById, cfg.minGap, cfg.maxBacktrack, cfg);
                java.util.Map<CitySpec.StructurePlan, BlockPos> m = new java.util.HashMap<>();
                for (int i = 0; i < Math.min(missing.size(), placed.size()); i++) {
                    BuildingPlacement p = placed.get(i);
                    // store absolute origin pos (relative min corner)
                    m.put(missing.get(i), origin.add(p.originRel));
                }
                autoOffsets = java.util.Collections.unmodifiableMap(m);
            }

            // Cluster-scale terrain strategy (policy + total budget + water edit flags)
            ClusterTerrainStrategy clusterTerrain = ClusterTerrainStrategy.fromExtra(extra0);
            java.util.Map<String, ZoneTerrainRule> zoneRules = ZoneTerrainRule.fromExtra(extra0);
            java.util.Map<String, CitySpec.Zone> zonesByName = new java.util.HashMap<>();
            if (city.getZones() != null) {
                for (CitySpec.Zone z : city.getZones()) {
                    if (z == null || z.getName() == null) continue;
                    String name = z.getName().trim();
                    if (!name.isEmpty()) zonesByName.put(name, z);
                }
            }
            java.util.Map<CitySpec.StructurePlan, Integer> allocatedBudgetByPlan = java.util.Map.of();
            if (clusterTerrain.clusterTerrainBudgetBlocks() > 0) {
                java.util.Map<CitySpec.StructurePlan, Integer> alloc = new java.util.HashMap<>();
                int total = clusterTerrain.clusterTerrainBudgetBlocks();
                double sumW = 0.0;
                java.util.Map<CitySpec.StructurePlan, Double> weights = new java.util.HashMap<>();
                for (CitySpec.StructurePlan sp0 : city.getStructures()) {
                    if (sp0 == null || sp0.getSpec() == null) continue;
                    BuildingSpec bs = sp0.getSpec();
                    int imp = computeAutoImportance(bs, 0, -1, 0);
                    int w = (bs.getFootprint() != null && bs.getFootprint().getWidth() > 0) ? bs.getFootprint().getWidth() : 8;
                    int d = (bs.getFootprint() != null && bs.getFootprint().getDepth() > 0) ? bs.getFootprint().getDepth() : 6;
                    int h = Math.max(4, bs.getHeight());
                    long vol = (long) w * (long) d * (long) h;
                    double ww = Math.max(1.0, imp) * (1.0 + Math.sqrt(Math.max(1.0, (double) vol)) / 50.0);
                    weights.put(sp0, ww);
                    sumW += ww;
                }
                if (sumW <= 1e-9) sumW = 1.0;
                int used = 0;
                for (var e : weights.entrySet()) {
                    int b = (int) Math.floor(total * (e.getValue() / sumW));
                    b = Math.max(200, b); // avoid tiny budgets
                    alloc.put(e.getKey(), b);
                    used += b;
                }
                // adjust remainder to stay within total
                int over = used - total;
                if (over > 0) {
                    // subtract evenly but keep minimum 200
                    for (var sp0 : alloc.keySet()) {
                        if (over <= 0) break;
                        int cur = alloc.get(sp0);
                        int dec = Math.min(over, Math.max(0, cur - 200));
                        if (dec > 0) {
                            alloc.put(sp0, cur - dec);
                            over -= dec;
                        }
                    }
                }
                allocatedBudgetByPlan = java.util.Collections.unmodifiableMap(alloc);
            }

            // Optional: auto road planning between main building and others (when roads are not explicitly provided).
            boolean autoRoads = false;
            int autoRoadWidth = 3;
            int autoRoadClearHeight = 2;
            int autoRoadMaxSearch = 12000;
            boolean autoRoadUseBorder = false;
            int autoRoadStepPenalty = 12;
            int autoRoadLocalSlopePenalty = 2;
            int autoRoadBridgePenalty = 6;
            if (extra0 != null) {
                autoRoads = parseBool(extra0.get("autoRoads"), true); // default true when config exists
                autoRoadWidth = clampInt(extra0.get("autoRoadWidth"), 3, 1, 7);
                autoRoadClearHeight = clampInt(extra0.get("autoRoadClearHeight"), 2, 0, 6);
                autoRoadMaxSearch = clampInt(extra0.get("autoRoadMaxSearch"), 12000, 2000, 60000);
                autoRoadUseBorder = parseBool(extra0.get("autoRoadUseBorder"), false);
                // slope-aware: higher => prefer detours to keep road flatter on steep terrain
                autoRoadStepPenalty = clampInt(extra0.get("autoRoadStepPenalty"), 12, 0, 60);
                autoRoadLocalSlopePenalty = clampInt(extra0.get("autoRoadSlopePenalty"), 2, 0, 20);
                autoRoadBridgePenalty = clampInt(extra0.get("autoRoadBridgePenalty"), 6, 0, 60);
            }
            boolean hasRoads = city.getRoads() != null && !city.getRoads().isEmpty();

            // Resolve road materials from city style profile (can be overridden by extra0.* ids).
            BuildingStyle cityStyle = BuildingStyle.DEFAULT;
            if (city.getStyle() != null) {
                try {
                    cityStyle = BuildingStyle.valueOf(city.getStyle().trim().toUpperCase());
                } catch (Exception ignored) {
                    cityStyle = BuildingStyle.DEFAULT;
                }
            }
            StyleProfile profile = StyleProfileRegistry.resolveByExtra(extra0, cityStyle);
            String roadId = profile != null && profile.palette() != null ? profile.palette().floor : null;
            String borderId = profile != null && profile.palette() != null ? profile.palette().trim : null;
            String deckId = profile != null && profile.palette() != null ? profile.palette().floor : null;
            String railId = "minecraft:oak_fence";

            // Style-driven "road language" defaults (non-clobbering): neon/cyber can imply lit borders/rails.
            var details = profile != null ? profile.details() : null;
            String eavesProfile = details != null ? details.eavesProfile : null;
            String ornamentProfile = details != null ? details.ornamentProfile : null;
            boolean neon = eavesProfile != null && eavesProfile.toLowerCase(java.util.Locale.ROOT).contains("neon");
            boolean cyber = ornamentProfile != null && (ornamentProfile.toLowerCase(java.util.Locale.ROOT).contains("cyber") || ornamentProfile.toLowerCase(java.util.Locale.ROOT).contains("sign"));
            if (extra0 != null) {
                Object r0 = extra0.get("autoRoadMaterial");
                Object b0 = extra0.get("autoRoadBorder");
                Object d0 = extra0.get("autoRoadBridgeDeck");
                Object rr0 = extra0.get("autoRoadBridgeRail");
                if (r0 != null) roadId = String.valueOf(r0).trim();
                if (b0 != null) borderId = String.valueOf(b0).trim();
                if (d0 != null) deckId = String.valueOf(d0).trim();
                if (rr0 != null) railId = String.valueOf(rr0).trim();
            }
            if ((extra0 == null || extra0.get("autoRoadUseBorder") == null) && (neon || cyber)) {
                autoRoadUseBorder = true;
            }
            if ((extra0 == null || extra0.get("autoRoadBorder") == null) && (neon || cyber)) {
                borderId = neon ? "minecraft:sea_lantern" : "minecraft:glowstone";
            }
            if ((extra0 == null || extra0.get("autoRoadBridgeRail") == null) && (neon || cyber)) {
                railId = neon ? "minecraft:sea_lantern" : "minecraft:iron_bars";
            }

            // If no explicit roads, try to materialize J-layer CIRCULATION skeleton paths into PathSpec roads.
            // This makes "semantic circulation" become real roads without requiring the LLM to output roads perfectly.
            if (!hasRoads && extra0 != null) {
                try {
                    String skPaletteId = null;
                    if (extra0.get("paletteId") != null) skPaletteId = String.valueOf(extra0.get("paletteId")).trim();
                    if ((skPaletteId == null || skPaletteId.isBlank()) && details != null && details.paletteId != null && !details.paletteId.isBlank()) {
                        skPaletteId = details.paletteId.trim();
                    }
                    java.util.List<PathSpec> skRoads = parseSkeletonRoads(extra0, roadId, skPaletteId);
                    if (skRoads != null && !skRoads.isEmpty()) {
                        city.setRoads(skRoads);
                        hasRoads = true;
                    }
                } catch (Throwable ignored) {}
            }
            BlockState roadMat = stateFromIdOrDefault(roadId, net.minecraft.block.Blocks.GRAVEL.getDefaultState());
            BlockState borderMat = stateFromIdOrDefault(borderId, net.minecraft.block.Blocks.COBBLESTONE.getDefaultState());
            BlockState deckMat = stateFromIdOrDefault(deckId, net.minecraft.block.Blocks.OAK_PLANKS.getDefaultState());
            BlockState railMat = stateFromIdOrDefault(railId, net.minecraft.block.Blocks.OAK_FENCE.getDefaultState());

            record RoadEndpoint(BlockPos pos, ClusterTerrainPolicy policy) {}
            java.util.List<RoadEndpoint> roadEndpoints = new java.util.ArrayList<>();
            int mainRoadIdx = -1;
            long mainRoadVol = -1;

            for (CitySpec.StructurePlan sp : city.getStructures()) {
                if (sp == null || sp.getSpec() == null) {
                    continue;
                }

                // 获取对应的生成器
                StructureGenerator generator = GenerationHub.routeStructure(sp.getSpec());

                // 计算建筑的绝对坐标
                BlockPos buildingOrigin;
                if (sp.getOffset() != null) {
                    CitySpec.Point offset = sp.getOffset();
                    buildingOrigin = origin.add(offset.x, offset.y, offset.z);
                } else {
                    // auto placement (if available); fallback to origin
                    buildingOrigin = autoOffsets.getOrDefault(sp, origin);
                }

                // New terrain adaptation: if spec.extra.terrainAdaptation is provided, prefer it over legacy TerrainPolicy.
                TerrainAdaptationSpec ta = TerrainAdaptationResolver.resolve(sp.getSpec().getExtra());
                if (TerrainAdaptationResolver.hasExplicit(sp.getSpec().getExtra())) {
                    TerrainAdaptationMode mode = ta.mode();
                    if (mode == TerrainAdaptationMode.DEFAULT) mode = TerrainAdaptationMode.ANCHOR;

                    int fpW = (sp.getSpec().getFootprint() != null && sp.getSpec().getFootprint().getWidth() > 0) ? sp.getSpec().getFootprint().getWidth() : 8;
                    int fpD = (sp.getSpec().getFootprint() != null && sp.getSpec().getFootprint().getDepth() > 0) ? sp.getSpec().getFootprint().getDepth() : 6;
                    List<BlockPos> footprintMask = TerrainAdaptationEngine.resolveFootprintPositions(sp.getSpec(), buildingOrigin, true);
                    boolean useFootprintMask = footprintMask != null && !footprintMask.isEmpty();
                    if (useFootprintMask) {
                        int minX = Integer.MAX_VALUE;
                        int minZ = Integer.MAX_VALUE;
                        int maxX = Integer.MIN_VALUE;
                        int maxZ = Integer.MIN_VALUE;
                        for (BlockPos p : footprintMask) {
                            if (p == null) continue;
                            minX = Math.min(minX, p.getX());
                            maxX = Math.max(maxX, p.getX());
                            minZ = Math.min(minZ, p.getZ());
                            maxZ = Math.max(maxZ, p.getZ());
                        }
                        if (minX != Integer.MAX_VALUE && minZ != Integer.MAX_VALUE) {
                            fpW = Math.max(1, maxX - minX + 1);
                            fpD = Math.max(1, maxZ - minZ + 1);
                        }
                    }

                    // Zone override first (if any), then cluster fallback.
                    CitySpec.Zone zone;
                    ZoneTerrainRule zrule = null;
                    if (sp.getZone() != null) {
                        zone = zonesByName.get(sp.getZone().trim());
                        if (zone != null && zone.getType() != null) {
                            String zt = zone.getType().trim().toUpperCase(java.util.Locale.ROOT);
                            zrule = zoneRules.getOrDefault(zt, ZoneTerrainRule.defaultsForZoneType(zt));
                        }
                    }
                    boolean clusterAllowWater = (zrule != null && zrule.allowWaterEdit() != null) ? zrule.allowWaterEdit() : clusterTerrain.allowWaterEdit();
                    boolean clusterAllowLava = (zrule != null && zrule.allowLavaEdit() != null) ? zrule.allowLavaEdit() : clusterTerrain.allowLavaEdit();
                    boolean allowWater = ta.allowWaterEdit() && clusterAllowWater;
                    boolean allowLava = ta.allowLavaEdit() && clusterAllowLava;

                    // Fill material for terrain ops
                    net.minecraft.block.BlockState fillMaterial = net.minecraft.block.Blocks.DIRT.getDefaultState();
                    if (sp.getSpec().getMaterials() != null && sp.getSpec().getMaterials().getFoundation() != null) {
                        try {
                            net.minecraft.util.Identifier id = net.minecraft.util.Identifier.of(sp.getSpec().getMaterials().getFoundation());
                            net.minecraft.block.Block block = net.minecraft.registry.Registries.BLOCK.get(id);
                            fillMaterial = block.getDefaultState();
                        } catch (Exception ignored) {}
                    }

                    TerrainAdaptationEngine.Bounds b = TerrainAdaptationEngine.boundsForCenteredFootprint(sp.getSpec(), buildingOrigin);
                    int baseY = useFootprintMask
                            ? TerrainAdaptationEngine.computeBaseY(world, footprintMask, ta, buildingOrigin.getY())
                            : TerrainAdaptationEngine.computeBaseY(world, b, ta, buildingOrigin.getY());
                    int platformY = baseY + 1;

                    BlockPos buildingOrigin2;
                    List<PlannedBlock> pad = List.of();
                    List<PlannedBlock> pre = List.of();
                    boolean postDrape = false;
                    int drapeBaseY = platformY;

                    if (mode == TerrainAdaptationMode.FLOAT) {
                        int y = (ta.fixedY() != null) ? ta.fixedY() : platformY;
                        buildingOrigin2 = new BlockPos(buildingOrigin.getX(), y, buildingOrigin.getZ());
                    } else if (mode == TerrainAdaptationMode.EMBED) {
                        buildingOrigin2 = new BlockPos(buildingOrigin.getX(), platformY - ta.embedDepth(), buildingOrigin.getZ());
                        pre = TerrainAdaptationEngine.carve(world, b, platformY - ta.embedDepth(), ta.clearHeight());
                    } else {
                        buildingOrigin2 = new BlockPos(buildingOrigin.getX(), platformY, buildingOrigin.getZ());
                        if (mode == TerrainAdaptationMode.ANCHOR) {
                            pad = useFootprintMask
                                    ? TerrainFit.adaptivePad(world, footprintMask, platformY, fillMaterial,
                                            Math.max(0, Math.min(6, ta.padDepth())),
                                            Math.max(0, Math.min(16, ta.clearHeight())),
                                            allowWater,
                                            allowLava)
                                    : TerrainFit.adaptivePad(world, buildingOrigin2, fpW, fpD, platformY, fillMaterial,
                                            Math.max(0, Math.min(6, ta.padDepth())),
                                            Math.max(0, Math.min(16, ta.clearHeight())),
                                            allowWater,
                                            allowLava);
                            if (ta.anchorExtendDown()) {
                                pre = TerrainAdaptationEngine.anchorPillars(world, b, platformY, fillMaterial, ta.anchorMaxDepth(), allowWater, allowLava);
                            }
                        } else if (mode == TerrainAdaptationMode.DRAPE) {
                            pad = useFootprintMask
                                    ? TerrainFit.adaptivePad(world, footprintMask, platformY, fillMaterial,
                                            Math.max(0, Math.min(2, ta.padDepth())),
                                            Math.max(0, Math.min(6, ta.clearHeight())),
                                            allowWater,
                                            allowLava)
                                    : TerrainFit.adaptivePad(world, buildingOrigin2, fpW, fpD, platformY, fillMaterial,
                                            Math.max(0, Math.min(2, ta.padDepth())),
                                            Math.max(0, Math.min(6, ta.clearHeight())),
                                            allowWater,
                                            allowLava);
                            postDrape = true;
                            drapeBaseY = platformY;
                            pre = TerrainAdaptationEngine.drapeFoundationColumns(world, b, ta.foundationDepth(), fillMaterial, allowWater, allowLava);
                        } else if (mode == TerrainAdaptationMode.FLATTEN) {
                            // handled after generation
                        }
                    }

                    GeneratedStructure building = generator.generate(sp.getSpec(), buildingOrigin2, world);

                    BlockPos buildingMin = new BlockPos(b.min().getX(), buildingOrigin2.getY(), b.min().getZ());
                    BlockPos buildingMax = new BlockPos(b.max().getX(), buildingOrigin2.getY() + Math.max(4, sp.getSpec().getHeight()), b.max().getZ());

                    if (mode == TerrainAdaptationMode.FLATTEN) {
                        building = com.formacraft.server.terrain.TerrainShaper.preprocessStructure(world, building, buildingMin, buildingMax, fillMaterial);
                        merged.addAll(building.getBlocks());
                    } else {
                        if (!pad.isEmpty()) merged.addAll(pad);
                        if (!pre.isEmpty()) merged.addAll(pre);
                        if (postDrape) merged.addAll(TerrainAdaptationEngine.drape(world, building.getBlocks(), drapeBaseY, ta.drapeMaxStep(), b));
                        else merged.addAll(building.getBlocks());
                    }

                    // collect endpoints for auto roads (use footprint center; keep y from buildingOrigin2)
                    if (autoRoads && !hasRoads) {
                        BlockPos c = findDoorEndpoint(building);
                        if (c == null) c = buildingOrigin2.add(fpW / 2, 0, fpD / 2);
                        ClusterTerrainPolicy rp = clusterTerrain.policy();
                        if (sp.getZone() != null) {
                            CitySpec.Zone zz = zonesByName.get(sp.getZone().trim());
                            if (zz != null && zz.getType() != null) {
                                String zt = zz.getType().trim().toUpperCase(java.util.Locale.ROOT);
                                ZoneTerrainRule zr = zoneRules.getOrDefault(zt, ZoneTerrainRule.defaultsForZoneType(zt));
                                if (zr != null && zr.policy() != null) rp = zr.policy();
                            }
                        }
                        roadEndpoints.add(new RoadEndpoint(c, rp));
                        long vol = (long) fpW * (long) fpD * (long) Math.max(4, sp.getSpec().getHeight());
                        if (vol > mainRoadVol) {
                            mainRoadVol = vol;
                            mainRoadIdx = roadEndpoints.size() - 1;
                        }
                    }

                    continue;
                }

                // Terrain policy (default ADAPTIVE): don't flatten the whole region unless explicitly requested.
                TerrainPolicy terrainPolicy = TerrainPolicyResolver.resolve(sp.getSpec().getExtra());

                BlockPos buildingOrigin2 = buildingOrigin;
                List<PlannedBlock> pad = List.of();
                if (terrainPolicy == TerrainPolicy.ADAPTIVE) {
                    buildingOrigin2 = TerrainFit.snapOrigin(world, buildingOrigin, sp.getSpec());
                    int fpW = (sp.getSpec().getFootprint() != null && sp.getSpec().getFootprint().getWidth() > 0) ? sp.getSpec().getFootprint().getWidth() : 8;
                    int fpD = (sp.getSpec().getFootprint() != null && sp.getSpec().getFootprint().getDepth() > 0) ? sp.getSpec().getFootprint().getDepth() : 6;
                    int avg = TerrainFit.averageFootprintHeight(world, buildingOrigin2, fpW, fpD);
                    int targetY = avg + 1;
                    int padDepth = 2;
                    int clearHeight = 6;
                    int terrainBudgetBlocks = 8000;
                    if (sp.getSpec().getExtra() != null) {
                        Object pd = sp.getSpec().getExtra().get("terrainPadDepth");
                        Object ch = sp.getSpec().getExtra().get("terrainClearHeight");
                        Object tb = sp.getSpec().getExtra().get("terrainBudgetBlocks");
                        padDepth = clampInt(pd, 2, 0, 6);
                        clearHeight = clampInt(ch, 6, 0, 16);
                        terrainBudgetBlocks = clampInt(tb, 8000, 0, 200000);
                    }

                    // Cluster policy defaults (if unit didn't explicitly override pad/clear)
                    // Apply zone override first (if any), then cluster fallback.
                    CitySpec.Zone zone;
                    ZoneTerrainRule zrule = null;
                    if (sp.getZone() != null) {
                        zone = zonesByName.get(sp.getZone().trim());
                        if (zone != null && zone.getType() != null) {
                            String zt = zone.getType().trim().toUpperCase(java.util.Locale.ROOT);
                            zrule = zoneRules.getOrDefault(zt, ZoneTerrainRule.defaultsForZoneType(zt));
                        }
                    }
                    ClusterTerrainPolicy effPolicy = (zrule != null && zrule.policy() != null) ? zrule.policy() : clusterTerrain.policy();
                    boolean allowWater = (zrule != null && zrule.allowWaterEdit() != null) ? zrule.allowWaterEdit() : clusterTerrain.allowWaterEdit();
                    boolean allowLava = (zrule != null && zrule.allowLavaEdit() != null) ? zrule.allowLavaEdit() : clusterTerrain.allowLavaEdit();

                    if (sp.getSpec().getExtra() == null || !sp.getSpec().getExtra().containsKey("terrainPadDepth")) {
                        switch (effPolicy) {
                            case PRESERVE_DOMINANT -> padDepth = Math.min(padDepth, 1);
                            case ENGINEERED -> padDepth = Math.max(padDepth, 4);
                            default -> {}
                        }
                    }
                    if (sp.getSpec().getExtra() == null || !sp.getSpec().getExtra().containsKey("terrainClearHeight")) {
                        switch (effPolicy) {
                            case PRESERVE_DOMINANT -> clearHeight = Math.min(clearHeight, 4);
                            case ENGINEERED -> clearHeight = Math.max(clearHeight, 8);
                            default -> {}
                        }
                    }

                    // Cluster-level budget allocation (optional): cap per-unit budget to allocated share
                    if (clusterTerrain.clusterTerrainBudgetBlocks() > 0) {
                        Integer alloc = allocatedBudgetByPlan.get(sp);
                        if (alloc != null && alloc > 0) {
                            terrainBudgetBlocks = Math.min(terrainBudgetBlocks, alloc);
                        }
                    }
                    // Zone local budget override (optional): cap again by zone budget
                    if (zrule != null && zrule.localBudgetBlocks() != null && zrule.localBudgetBlocks() > 0) {
                        terrainBudgetBlocks = Math.min(terrainBudgetBlocks, zrule.localBudgetBlocks());
                    }
                    BuildReportContext.setTerrainBudgetBlocks(terrainBudgetBlocks);
                    net.minecraft.block.BlockState fillMaterial = net.minecraft.block.Blocks.DIRT.getDefaultState();
                    if (sp.getSpec().getMaterials() != null && sp.getSpec().getMaterials().getFoundation() != null) {
                        try {
                            net.minecraft.util.Identifier id = net.minecraft.util.Identifier.of(sp.getSpec().getMaterials().getFoundation());
                            net.minecraft.block.Block block = net.minecraft.registry.Registries.BLOCK.get(id);
                            fillMaterial = block.getDefaultState();
                        } catch (Exception ignored) {}
                    }

                    // Minimal FootingPlan (v1):
                    // - choose explicit FoundationType from terrain variance
                    // - derive knobs (padDepth/clearHeight) without flattening a whole region
                    var analysis = TerrainFit.analyze(world, buildingOrigin2, fpW, fpD);
                    FoundationPlanner.Decision fd = FoundationPlanner.decide(sp.getSpec(), analysis, padDepth, clearHeight);
                    if (fd.stilt()) BuildReportContext.addFootingStiltUnit();
                    else if (fd.padDepth() > 0) BuildReportContext.addFootingPadUnit();

                    int pdClamped = Math.max(0, Math.min(6, fd.padDepth()));
                    int chClamped = Math.max(0, Math.min(16, fd.clearHeight()));
                    List<PlannedBlock> p0 = TerrainFit.adaptivePad(world, buildingOrigin2, fpW, fpD, targetY, fillMaterial,
                            pdClamped, chClamped,
                            allowWater,
                            allowLava);

                    // Budget control: if too many terrain edits, degrade (reduce pad, then reduce clear, else skip).
                    int usedPadDepth = pdClamped;
                    int usedClearHeight = chClamped;
                    int foundationDegradeSteps = 0;
                    if (terrainBudgetBlocks > 0 && p0.size() > terrainBudgetBlocks) {
                        BuildReportContext.addTerrainBudgetDegrade();
                        List<PlannedBlock> p1 = TerrainFit.adaptivePad(world, buildingOrigin2, fpW, fpD, targetY, fillMaterial,
                                0, Math.max(0, Math.min(6, chClamped)),
                                allowWater,
                                allowLava);
                        if (p1.size() <= terrainBudgetBlocks) {
                            pad = p1;
                            usedPadDepth = 0;
                            usedClearHeight = Math.max(0, Math.min(6, chClamped));
                            foundationDegradeSteps = 1;
                        } else {
                            BuildReportContext.addTerrainBudgetDegrade();
                            List<PlannedBlock> p2 = TerrainFit.adaptivePad(world, buildingOrigin2, fpW, fpD, targetY, fillMaterial,
                                    0, 2,
                                    allowWater,
                                    allowLava);
                            if (p2.size() <= terrainBudgetBlocks) {
                                pad = p2;
                                usedPadDepth = 0;
                                usedClearHeight = 2;
                                foundationDegradeSteps = 2;
                            }
                            else {
                                BuildReportContext.addTerrainBudgetDegrade();
                                pad = List.of();
                                usedPadDepth = 0;
                                usedClearHeight = 0;
                                foundationDegradeSteps = 3;
                            }
                        }
                    } else {
                        pad = p0;
                    }

                    BuildReportContext.addFoundationExecution(fd.type(), analysis.range(), pdClamped, chClamped,
                            usedPadDepth, usedClearHeight, foundationDegradeSteps);
                }

                // 生成建筑
                GeneratedStructure building = generator.generate(sp.getSpec(), buildingOrigin2, world);
                
                // For explicit flatten/terraform, apply (legacy) preprocessing on the unit area.
                BlockPos buildingMin = buildingOrigin2;
                BlockPos buildingMax;
                
                if (sp.getSpec().getFootprint() != null) {
                    int width = sp.getSpec().getFootprint().getWidth() > 0 ? 
                        sp.getSpec().getFootprint().getWidth() : 8;
                    int depth = sp.getSpec().getFootprint().getDepth() > 0 ? 
                        sp.getSpec().getFootprint().getDepth() : 6;
                    int height = sp.getSpec().getHeight() > 0 ? sp.getSpec().getHeight() : 4;
                    
                    if ("circle".equals(sp.getSpec().getFootprint().getShape()) && 
                        sp.getSpec().getFootprint().getRadius() > 0) {
                        int radius = sp.getSpec().getFootprint().getRadius();
                        buildingMin = buildingOrigin2.add(-radius, 0, -radius);
                        buildingMax = buildingOrigin2.add(radius, height, radius);
                    } else {
                        buildingMax = buildingOrigin2.add(width, height, depth);
                    }
                } else {
                    buildingMax = buildingOrigin2.add(8, 4, 6);
                }

                if (terrainPolicy == TerrainPolicy.FLATTEN_AREA || terrainPolicy == TerrainPolicy.TERRAFORM) {
                    // apply legacy area flattening (strong terrain edit)
                    net.minecraft.block.BlockState fillMaterial = net.minecraft.block.Blocks.DIRT.getDefaultState();
                    if (sp.getSpec().getMaterials() != null &&
                        sp.getSpec().getMaterials().getFoundation() != null) {
                        try {
                            net.minecraft.util.Identifier id = net.minecraft.util.Identifier.of(sp.getSpec().getMaterials().getFoundation());
                            net.minecraft.block.Block block = net.minecraft.registry.Registries.BLOCK.get(id);
                            fillMaterial = block.getDefaultState();
                        } catch (Exception ignored) {}
                    }
                    building = com.formacraft.server.terrain.TerrainShaper.preprocessStructure(
                        world, building, buildingMin, buildingMax, fillMaterial);
                    merged.addAll(building.getBlocks());
                } else {
                    // FOLLOW/ADAPTIVE: no full flattening; ADAPTIVE uses pad + obstacle clear only.
                    if (!pad.isEmpty()) merged.addAll(pad);
                    merged.addAll(building.getBlocks());
                }

                // collect endpoints for auto roads (use footprint center; keep y from buildingOrigin2)
                if (autoRoads && !hasRoads) {
                    int fpW = (sp.getSpec().getFootprint() != null && sp.getSpec().getFootprint().getWidth() > 0) ? sp.getSpec().getFootprint().getWidth() : 8;
                    int fpD = (sp.getSpec().getFootprint() != null && sp.getSpec().getFootprint().getDepth() > 0) ? sp.getSpec().getFootprint().getDepth() : 6;
                    // prefer door-based endpoint if present
                    BlockPos c = findDoorEndpoint(building);
                    if (c == null) c = buildingOrigin2.add(fpW / 2, 0, fpD / 2);
                    // derive an effective policy for this structure from its zone (if any), else fall back to cluster policy
                    ClusterTerrainPolicy rp = clusterTerrain.policy();
                    if (sp.getZone() != null) {
                        CitySpec.Zone zz = zonesByName.get(sp.getZone().trim());
                        if (zz != null && zz.getType() != null) {
                            String zt = zz.getType().trim().toUpperCase(java.util.Locale.ROOT);
                            ZoneTerrainRule zr = zoneRules.getOrDefault(zt, ZoneTerrainRule.defaultsForZoneType(zt));
                            if (zr != null && zr.policy() != null) rp = zr.policy();
                        }
                    }
                    roadEndpoints.add(new RoadEndpoint(c, rp));
                    long vol = (long) fpW * (long) fpD * (long) Math.max(4, sp.getSpec().getHeight());
                    if (vol > mainRoadVol) {
                        mainRoadVol = vol;
                        mainRoadIdx = roadEndpoints.size() - 1;
                    }
                }
            }

            // Build auto roads after all buildings are merged (so preview includes them as well).
            if (autoRoads && !hasRoads && roadEndpoints.size() >= 2) {
                // main endpoint: pick the largest-volume building center
                RoadEndpoint main = roadEndpoints.get(Math.max(0, mainRoadIdx));
                for (int i = 0; i < roadEndpoints.size(); i++) {
                    if (i == mainRoadIdx) continue;
                    RoadEndpoint other = roadEndpoints.get(i);
                    if (other.pos().equals(main.pos())) continue;

                    ClusterTerrainPolicy linkPolicy = roadLinkPolicy(main.policy(), other.policy());
                    int stepP = autoRoadStepPenalty;
                    int slopeP = autoRoadLocalSlopePenalty;
                    int bridgeP = autoRoadBridgePenalty;
                    int maxSearch = autoRoadMaxSearch;
                    if (linkPolicy == ClusterTerrainPolicy.PRESERVE_DOMINANT) {
                        stepP = Math.max(stepP, 24);
                        slopeP = Math.max(slopeP, 4);
                        bridgeP = Math.max(bridgeP, 12);
                        maxSearch = Math.min(60000, Math.max(maxSearch, autoRoadMaxSearch * 2));
                    } else if (linkPolicy == ClusterTerrainPolicy.ENGINEERED) {
                        stepP = Math.min(stepP, 10);
                        slopeP = Math.min(slopeP, 2);
                        bridgeP = Math.min(bridgeP, 6);
                    }

                    com.formacraft.server.road.RoadPlanner.Config cfg = new com.formacraft.server.road.RoadPlanner.Config(
                            autoRoadWidth,
                            autoRoadClearHeight,
                            1,
                            maxSearch,
                            stepP,
                            slopeP,
                            bridgeP,
                            roadMat,
                            borderMat,
                            autoRoadUseBorder,
                            deckMat,
                            railMat
                    );
                    merged.addAll(com.formacraft.server.road.RoadPlanner.build(world, main.pos(), other.pos(), cfg));
                }
            }
        }

        // 2. 生成道路
        if (city.getRoads() != null) {
            for (PathSpec road : city.getRoads()) {
                if (road == null) {
                    continue;
                }
                GeneratedStructure roadStructure = pathGenerator.generate(road, origin, world);
                merged.addAll(roadStructure.getBlocks());
            }
        }

        // 3. 生成桥梁
        if (city.getBridges() != null) {
            for (CitySpec.BridgePlan bp : city.getBridges()) {
                if (bp == null || bp.getFrom() == null || bp.getTo() == null) {
                    continue;
                }

                // 将 BridgePlan 转换为 BuildingSpec
                BuildingSpec bridgeSpec = createBridgeSpecFromPlan(bp, city);

                // 计算桥梁的起点
                CitySpec.Point from = bp.getFrom();
                BlockPos bridgeOrigin = origin.add(from.x, from.y, from.z);

                // 生成桥梁
                GeneratedStructure bridge = bridgeGenerator.generate(bridgeSpec, bridgeOrigin, world);
                merged.addAll(bridge.getBlocks());
            }
        }

        String description = String.format("City: %s (%d structures, %d roads, %d bridges)",
                city.getCityName() != null ? city.getCityName() : "Unnamed",
                city.getStructures() != null ? city.getStructures().size() : 0,
                city.getRoads() != null ? city.getRoads().size() : 0,
                city.getBridges() != null ? city.getBridges().size() : 0);

        return new GeneratedStructure(
                null, // owner 将在 BuildExecutionService 中设置
                origin,
                description,
                merged
        );
    }

    /**
     * 从 BridgePlan 创建 BuildingSpec
     */
    private BuildingSpec createBridgeSpecFromPlan(CitySpec.BridgePlan plan, CitySpec city) {
        BuildingSpec spec = new BuildingSpec();
        spec.setType(BuildingType.BRIDGE);
        
        // 设置风格（从城市风格继承）
        if (city.getStyle() != null) {
            try {
                spec.setStyle(com.formacraft.common.model.build.BuildingStyle.valueOf(city.getStyle().toUpperCase()));
            } catch (IllegalArgumentException e) {
                spec.setStyle(com.formacraft.common.model.build.BuildingStyle.DEFAULT);
            }
        } else {
            spec.setStyle(com.formacraft.common.model.build.BuildingStyle.DEFAULT);
        }

        // 计算桥梁长度
        CitySpec.Point from = plan.getFrom();
        CitySpec.Point to = plan.getTo();
        int dx = Math.abs(to.x - from.x);
        int dz = Math.abs(to.z - from.z);
        int length = (int) Math.sqrt(dx * dx + dz * dz);

        // 设置 Footprint
        com.formacraft.common.model.build.Footprint footprint = new com.formacraft.common.model.build.Footprint();
        footprint.setShape("rectangle");
        footprint.setWidth(5);  // 默认桥梁宽度
        footprint.setDepth(length);
        spec.setFootprint(footprint);

        // 设置高度
        spec.setHeight(5);  // 默认桥梁高度

        // 设置材质
        com.formacraft.common.model.build.Materials materials = new com.formacraft.common.model.build.Materials();
        materials.setWall("minecraft:stone_bricks");
        materials.setRoof("minecraft:oak_planks");
        materials.setFloor("minecraft:oak_planks");
        materials.setWindow("minecraft:glass_pane");
        spec.setMaterials(materials);

        // 设置特性
        com.formacraft.common.model.build.Features features = new com.formacraft.common.model.build.Features();
        features.setHasWindows(false);
        features.setHasStairs(false);
        spec.setFeatures(features);

        // 设置样式选项（桥梁类型）
        com.formacraft.common.model.build.StyleOptions styleOptions = new com.formacraft.common.model.build.StyleOptions();
        styleOptions.setBridgeType(plan.getBridgeType() != null ? plan.getBridgeType() : "flat");
        spec.setStyleOptions(styleOptions);

        return spec;
    }

    private static int clampInt(Object v, int def, int min, int max) {
        if (v == null) return def;
        int n = def;
        try {
            if (v instanceof Number nn) n = nn.intValue();
            else {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty()) n = Integer.parseInt(s);
            }
        } catch (Exception ignored) {}
        return Math.max(min, Math.min(max, n));
    }

    private static boolean parseBool(Object v, boolean def) {
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase(java.util.Locale.ROOT);
        if (s.isEmpty()) return def;
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on");
    }

    private static BlockState stateFromIdOrDefault(String id, BlockState def) {
        if (id == null) return def;
        String s = id.trim();
        if (s.isEmpty()) return def;
        try {
            Identifier identifier = s.contains(":") ? Identifier.of(s) : Identifier.of("minecraft", s);
            Block b = Registries.BLOCK.get(identifier);
            return b.getDefaultState();
        } catch (Exception ignored) {
            return def;
        }
    }

    /**
     * Try to find an outside point near a door as a road endpoint.
     * Works purely from planned blocks (no world scan) for determinism.
     */
    private static BlockPos findDoorEndpoint(GeneratedStructure building) {
        if (building == null || building.getBlocks() == null) return null;
        for (PlannedBlock pb : building.getBlocks()) {
            if (pb == null || pb.getPos() == null || pb.getTargetState() == null) continue;
            BlockState st = pb.getTargetState();
            if (!(st.getBlock() instanceof DoorBlock)) continue;
            if (st.contains(Properties.DOUBLE_BLOCK_HALF) && st.get(Properties.DOUBLE_BLOCK_HALF) != DoubleBlockHalf.LOWER) continue;
            Direction facing = st.contains(Properties.HORIZONTAL_FACING) ? st.get(Properties.HORIZONTAL_FACING) : Direction.NORTH;
            // endpoint one block outside the door
            return pb.getPos().offset(facing);
        }
        return null;
    }

    private static int computeAutoImportance(BuildingSpec bs, int idx, int mainIdx, long bestVol) {
        // 1) explicit override: spec.extra.importance in [1..10]
        if (bs != null && bs.getExtra() != null) {
            Integer ov = tryParseInt(bs.getExtra().get("importance"));
            if (ov != null) return clampInt(ov, 5, 1, 10);
        }

        int imp = 5;

        // 2) main building gets priority
        if (idx == mainIdx) imp = 9;

        // 3) archetype/template hints
        String template = null;
        if (bs != null && bs.getExtra() != null) {
            Object t = bs.getExtra().get("template");
            if (t != null) template = String.valueOf(t).trim().toLowerCase(java.util.Locale.ROOT);
            Object a = bs.getExtra().get("archetypeId");
            if (a != null && !String.valueOf(a).trim().isEmpty()) imp = 10;
        }
        if (template != null) {
            if (template.contains("eiffel") || template.contains("temple_of_heaven") || template.contains("tulou")
                    || template.contains("golden_gate") || template.contains("great_wall") || template.contains("pagoda")
                    || template.contains("mingqing_courtyard") || template.contains("castle_compound")) {
                imp = 10;
            }
        }

        // 4) type heuristics
        if (bs != null && bs.getType() != null) {
            switch (bs.getType()) {
                case TOWER -> imp = Math.max(imp, 9);
                case CASTLE, BRIDGE, WALL -> imp = Math.max(imp, 8);
                default -> {}
            }
        }

        // 5) volume proxy relative to max
        if (bs != null && bestVol > 0) {
            int w = (bs.getFootprint() != null && bs.getFootprint().getWidth() > 0) ? bs.getFootprint().getWidth() : 8;
            int d = (bs.getFootprint() != null && bs.getFootprint().getDepth() > 0) ? bs.getFootprint().getDepth() : 6;
            int h = Math.max(4, bs.getHeight());
            long vol = (long) w * (long) d * (long) h;
            double r = (double) vol / (double) bestVol;
            if (r >= 0.90) imp = Math.max(imp, 9);
            else if (r >= 0.65) imp = Math.max(imp, 7);
            else if (r >= 0.40) imp = Math.max(imp, 6);
        }

        return clampInt(imp, 5, 1, 10);
    }

    private static Integer tryParseInt(Object v) {
        if (v == null) return null;
        try {
            if (v instanceof Number n) return n.intValue();
            String s = String.valueOf(v).trim();
            if (s.isEmpty()) return null;
            return Integer.parseInt(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static ClusterTerrainPolicy roadLinkPolicy(ClusterTerrainPolicy a, ClusterTerrainPolicy b) {
        ClusterTerrainPolicy aa = a != null ? a : ClusterTerrainPolicy.BALANCED;
        ClusterTerrainPolicy bb = b != null ? b : ClusterTerrainPolicy.BALANCED;
        if (aa == ClusterTerrainPolicy.PRESERVE_DOMINANT || bb == ClusterTerrainPolicy.PRESERVE_DOMINANT) return ClusterTerrainPolicy.PRESERVE_DOMINANT;
        if (aa == ClusterTerrainPolicy.ENGINEERED || bb == ClusterTerrainPolicy.ENGINEERED) return ClusterTerrainPolicy.ENGINEERED;
        return ClusterTerrainPolicy.BALANCED;
    }

    private static String inferSemanticRoleForPlan(BuildingSpec bs, CitySpec.StructurePlan sp, CitySpec city, boolean isMain) {
        // explicit override: spec.extra.semanticRole (CORE/PUBLIC/PRIVATE/SERVICE/TRANSITION/SEMI_PUBLIC...)
        if (bs != null && bs.getExtra() != null) {
            Object v = bs.getExtra().get("semanticRole");
            if (v != null) {
                String s = String.valueOf(v).trim().toUpperCase(java.util.Locale.ROOT);
                if (!s.isEmpty()) return s;
            }
        }

        if (isMain) return "CORE";

        // Use zone type as primary hint (CitySpec.Zone.type).
        if (sp != null && sp.getZone() != null && city != null && city.getZones() != null) {
            String zn = sp.getZone().trim();
            for (CitySpec.Zone z : city.getZones()) {
                if (z == null || z.getName() == null) continue;
                if (!z.getName().trim().equals(zn)) continue;
                String t = z.getType() != null ? z.getType().trim().toUpperCase(java.util.Locale.ROOT) : "";
                return switch (t) {
                    case "PLAZA", "MARKET", "COMMERCIAL" -> "PUBLIC";
                    case "RESIDENTIAL" -> "PRIVATE";
                    case "INDUSTRIAL" -> "SERVICE";
                    case "WALL", "GATE" -> "TRANSITION";
                    default -> "SEMI_PUBLIC";
                };
            }
        }

        // Fallback: derive from building type/template hints.
        if (bs != null && bs.getExtra() != null) {
            Object template = bs.getExtra().get("template");
            if (template != null) {
                String t = String.valueOf(template).trim().toLowerCase(java.util.Locale.ROOT);
                if (t.contains("plaza") || t.contains("market")) return "PUBLIC";
                if (t.contains("service") || t.contains("warehouse")) return "SERVICE";
            }
        }
        return "SEMI_PUBLIC";
    }

    /**
     * Parse J-layer skeletonLayout anchors from spec.extra, returning a map keyed by zoneType (CORE/PUBLIC/...)
     * to a rel anchor BlockPos (dx,0,dz) relative to city origin.
     * <p>
     * Expected schema (Python emits):
     * skeletonLayout: { skeletons: [ { zoneType: "CORE", anchor: {x:0,y:0,z:0}, ... }, ... ] }
     */
    private static java.util.Map<String, BlockPos> parseSkeletonAnchorsByZoneType(java.util.Map<String, Object> extra) {
        if (extra == null) return null;
        Object v = extra.get("skeletonLayout");
        if (!(v instanceof java.util.Map<?, ?> m)) return null;
        Object s = m.get("skeletons");
        if (!(s instanceof java.util.List<?> list)) return null;

        java.util.Map<String, BlockPos> out = new java.util.HashMap<>();
        for (Object o : list) {
            if (!(o instanceof java.util.Map<?, ?> sm)) continue;
            Object zt0 = sm.get("zoneType");
            if (zt0 == null) continue;
            String zt = String.valueOf(zt0).trim().toUpperCase(java.util.Locale.ROOT);
            if (zt.isEmpty()) continue;
            Object a0 = sm.get("anchor");
            if (!(a0 instanceof java.util.Map<?, ?> am)) continue;
            int ax = parseIntOrDef(am.get("x"));
            int az = parseIntOrDef(am.get("z"));
            out.putIfAbsent(zt, new BlockPos(ax, 0, az));
        }
        return out.isEmpty() ? null : java.util.Collections.unmodifiableMap(out);
    }

    /**
     * Minimal parsed skeleton node info keyed by zoneType.
     */
        private record SkeletonNodeInfo(String shapeUpper, int width, int depth, int radius) {
    }

    /**
     * Parse skeletonLayout nodes keyed by zoneType (first occurrence wins).
     * Used for footprint fallbacks and placement bias.
     */
    private static java.util.Map<String, SkeletonNodeInfo> parseSkeletonNodesByZoneType(java.util.Map<String, Object> extra) {
        if (extra == null) return null;
        Object v = extra.get("skeletonLayout");
        if (!(v instanceof java.util.Map<?, ?> m)) return null;
        Object s = m.get("skeletons");
        if (!(s instanceof java.util.List<?> list)) return null;

        java.util.Map<String, SkeletonNodeInfo> out = new java.util.HashMap<>();
        for (Object o : list) {
            if (!(o instanceof java.util.Map<?, ?> sm)) continue;
            Object zt0 = sm.get("zoneType");
            if (zt0 == null) continue;
            String zt = String.valueOf(zt0).trim().toUpperCase(java.util.Locale.ROOT);
            if (zt.isEmpty()) continue;
            if (out.containsKey(zt)) continue;

            String shape = sm.get("shape") != null ? String.valueOf(sm.get("shape")).trim().toUpperCase(java.util.Locale.ROOT) : "";
            Object a0 = sm.get("anchor");
            if (!(a0 instanceof java.util.Map<?, ?>)) continue; // anchor required, but we don't consume it in this v0 parser

            int w = parseIntOrDef(sm.get("width"));
            int d = parseIntOrDef(sm.get("depth"));
            int r = parseIntOrDef(sm.get("radius"));

            out.put(zt, new SkeletonNodeInfo(shape, w, d, r));
        }
        return out.isEmpty() ? null : java.util.Collections.unmodifiableMap(out);
    }

    private static void tryEnsureRectFootprint(BuildingSpec bs, int w, int d) {
        if (bs == null) return;
        try {
            if (bs.getFootprint() == null) bs.setFootprint(new com.formacraft.common.model.build.Footprint());
            var fp = bs.getFootprint();
            if (fp.getShape() == null || fp.getShape().isBlank()) fp.setShape("rectangle");
            if (!"rectangle".equalsIgnoreCase(fp.getShape())) fp.setShape("rectangle");
            if (fp.getWidth() <= 0) fp.setWidth(Math.max(3, w));
            if (fp.getDepth() <= 0) fp.setDepth(Math.max(3, d));
        } catch (Throwable ignored) {}
    }

    private static void tryEnsureCircleFootprint(BuildingSpec bs, int radius) {
        if (bs == null) return;
        try {
            if (bs.getFootprint() == null) bs.setFootprint(new com.formacraft.common.model.build.Footprint());
            var fp = bs.getFootprint();
            fp.setShape("circle");
            if (fp.getRadius() <= 0) fp.setRadius(Math.max(3, radius));
        } catch (Throwable ignored) {}
    }

    /**
     * Convert skeletonLayout CIRCULATION / LINEAR nodes into PathSpec list (relative to city origin).
     * Expected skeleton node shape:
     * { zoneType:"CIRCULATION", shape:"LINEAR", points:[{x,y,z}, {x,y,z}, ...] }
     */
    private static java.util.List<PathSpec> parseSkeletonRoads(java.util.Map<String, Object> extra, String materialId, String paletteIdHint) {
        if (extra == null) return java.util.List.of();
        Object v = extra.get("skeletonLayout");
        if (!(v instanceof java.util.Map<?, ?> m)) return java.util.List.of();
        Object s = m.get("skeletons");
        if (!(s instanceof java.util.List<?> list)) return java.util.List.of();

        String mat = (materialId == null || materialId.isBlank()) ? "minecraft:gravel" : materialId.trim();
        String paletteId = null;
        String styleProfileId = null;
        try {
            Object pid = extra.get("paletteId");
            if (pid != null) paletteId = String.valueOf(pid).trim();
            Object sid = extra.get("styleProfileId");
            if (sid != null) styleProfileId = String.valueOf(sid).trim();
        } catch (Throwable ignored) {}
        if ((paletteId == null || paletteId.isBlank()) && paletteIdHint != null && !paletteIdHint.isBlank()) {
            paletteId = paletteIdHint.trim();
        }
        java.util.Map<String, Object> roadExtra = null;
        if (paletteId != null || styleProfileId != null) {
            roadExtra = new java.util.HashMap<>();
            if (paletteId != null) roadExtra.put("paletteId", paletteId);
            if (styleProfileId != null) roadExtra.put("styleProfileId", styleProfileId);
        }
        java.util.List<PathSpec> out = new java.util.ArrayList<>();
        int id = 0;
        for (Object o : list) {
            if (!(o instanceof java.util.Map<?, ?> sm)) continue;
            String zoneType = sm.get("zoneType") != null ? String.valueOf(sm.get("zoneType")).trim().toUpperCase(java.util.Locale.ROOT) : "";
            String shape = sm.get("shape") != null ? String.valueOf(sm.get("shape")).trim().toUpperCase(java.util.Locale.ROOT) : "";
            if (!(zoneType.equals("CIRCULATION") || shape.equals("LINEAR"))) continue;
            Object pts0 = sm.get("points");
            if (!(pts0 instanceof java.util.List<?> pts) || pts.size() < 2) continue;

            // Build segments between consecutive points.
            for (int i = 0; i < pts.size() - 1; i++) {
                Object a0 = pts.get(i);
                Object b0 = pts.get(i + 1);
                if (!(a0 instanceof java.util.Map<?, ?> am) || !(b0 instanceof java.util.Map<?, ?> bm)) continue;
                int ax = parseIntOrDef(am.get("x"));
                int ay = parseIntOrDef(am.get("y"));
                int az = parseIntOrDef(am.get("z"));
                int bx = parseIntOrDef(bm.get("x"));
                int by = parseIntOrDef(bm.get("y"));
                int bz = parseIntOrDef(bm.get("z"));

                PathSpec road = new PathSpec();
                road.setId("sk_road_" + (id++));
                road.setFrom(new PathSpec.Point(ax, ay, az));
                road.setTo(new PathSpec.Point(bx, by, bz));
                road.setWidth(3);
                road.setMaterial(mat);
                road.setStyle("astar");
                if (roadExtra != null) road.setExtra(roadExtra);
                out.add(road);
            }
        }
        return out;
    }

    private static int parseIntOrDef(Object v) {
        if (v == null) return 0;
        try {
            if (v instanceof Number n) return n.intValue();
            String s = String.valueOf(v).trim();
            if (s.isEmpty()) return 0;
            return Integer.parseInt(s);
        } catch (Exception ignored) {
            return 0;
        }
    }
}

