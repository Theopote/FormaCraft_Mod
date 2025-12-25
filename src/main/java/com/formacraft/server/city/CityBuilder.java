package com.formacraft.server.city;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingType;
import com.formacraft.common.model.city.CitySpec;
import com.formacraft.common.model.path.PathSpec;
import com.formacraft.server.build.GeneratedStructure;
import com.formacraft.server.build.PlannedBlock;
import com.formacraft.server.generator.BridgeGenerator;
import com.formacraft.server.generator.StructureGenerator;
import com.formacraft.server.generator.StructureGeneratorFactory;
import com.formacraft.server.generator.path.PathGenerator;
import com.formacraft.server.terrain.TerrainFit;
import com.formacraft.server.terrain.TerrainPolicy;
import com.formacraft.server.terrain.TerrainPolicyResolver;
import com.formacraft.server.build.BuildReportContext;
import com.formacraft.server.cluster.TerrainFields;
import com.formacraft.server.cluster.layout.BuildArea;
import com.formacraft.server.cluster.layout.BuildingPlacement;
import com.formacraft.server.cluster.layout.BuildingUnit;
import com.formacraft.server.cluster.layout.Candidate;
import com.formacraft.server.cluster.layout.CandidateGenerator;
import com.formacraft.server.cluster.layout.ClusterLayoutConfig;
import com.formacraft.server.cluster.layout.PlacementSolver;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

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
            if (anyMissingOffset) {
                int count = 0;
                int maxW = 10;
                int maxD = 10;
                int maxH = 12;
                for (CitySpec.StructurePlan sp0 : city.getStructures()) {
                    if (sp0 == null || sp0.getSpec() == null || sp0.getOffset() != null) continue;
                    count++;
                    if (sp0.getSpec().getFootprint() != null) {
                        maxW = Math.max(maxW, sp0.getSpec().getFootprint().getWidth());
                        maxD = Math.max(maxD, sp0.getSpec().getFootprint().getDepth());
                    }
                    maxH = Math.max(maxH, sp0.getSpec().getHeight());
                }
                int spacing = Math.max(14, Math.max(maxW, maxD) + 4);
                int boxHalf = Math.max(24, (int) Math.round(Math.sqrt(Math.max(1, count)) * spacing));

                // Use the first spec.extra as config source if available (fallback defaults).
                java.util.Map<String, Object> extra0 = null;
                for (CitySpec.StructurePlan sp0 : city.getStructures()) {
                    if (sp0 != null && sp0.getSpec() != null && sp0.getSpec().getExtra() != null) { extra0 = sp0.getSpec().getExtra(); break; }
                }
                ClusterLayoutConfig cfg = ClusterLayoutConfig.fromExtra(extra0, boxHalf, boxHalf, count, spacing);

                BuildArea area = new BuildArea(cfg.halfX, cfg.halfZ);
                TerrainFields fields = TerrainFields.sample(world, origin, cfg.halfX, cfg.halfZ, 2);

                List<BuildingUnit> units = new ArrayList<>(count);
                java.util.Map<String, List<Candidate>> candidatesById = new java.util.HashMap<>();

                // We treat each missing-offset structure as one unit type (id by index to allow different sizes).
                int idx = 0;
                java.util.List<CitySpec.StructurePlan> missing = new java.util.ArrayList<>();
                for (CitySpec.StructurePlan sp0 : city.getStructures()) {
                    if (sp0 == null || sp0.getSpec() == null || sp0.getOffset() != null) continue;
                    missing.add(sp0);
                }
                for (CitySpec.StructurePlan sp0 : missing) {
                    BuildingSpec bs = sp0.getSpec();
                    int w = (bs.getFootprint() != null && bs.getFootprint().getWidth() > 0) ? bs.getFootprint().getWidth() : 8;
                    int d = (bs.getFootprint() != null && bs.getFootprint().getDepth() > 0) ? bs.getFootprint().getDepth() : 6;
                    int h = Math.max(4, bs.getHeight());
                    String id = "city_unit_" + idx;
                    BuildingUnit u = new BuildingUnit(id, w, d, h, 5);
                    units.add(u);
                    List<Candidate> cands = CandidateGenerator.generate(u, area, fields, world, origin, cfg.samples, cfg.maxRange, cfg.maxFlattenCost);
                    candidatesById.put(id, cands);
                    idx++;
                }

                List<BuildingPlacement> placed = PlacementSolver.solve(units, candidatesById, cfg.minGap, cfg.maxBacktrack);
                java.util.Map<CitySpec.StructurePlan, BlockPos> m = new java.util.HashMap<>();
                for (int i = 0; i < Math.min(missing.size(), placed.size()); i++) {
                    BuildingPlacement p = placed.get(i);
                    // store absolute origin pos (relative min corner)
                    m.put(missing.get(i), origin.add(p.originRel));
                }
                autoOffsets = java.util.Collections.unmodifiableMap(m);
            }

            for (CitySpec.StructurePlan sp : city.getStructures()) {
                if (sp == null || sp.getSpec() == null) {
                    continue;
                }

                // 获取对应的生成器
                StructureGenerator generator = StructureGeneratorFactory.getGenerator(sp.getSpec());

                // 计算建筑的绝对坐标
                BlockPos buildingOrigin;
                if (sp.getOffset() != null) {
                    CitySpec.Point offset = sp.getOffset();
                    buildingOrigin = origin.add(offset.x, offset.y, offset.z);
                } else {
                    // auto placement (if available); fallback to origin
                    buildingOrigin = autoOffsets.getOrDefault(sp, origin);
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
                    // - range small -> pad
                    // - range medium -> deeper pad
                    // - range large -> stilt (minimal fill; rely on H-layer supports)
                    int range = TerrainFit.analyze(world, buildingOrigin2, fpW, fpD).range();
                    boolean stilt = range >= 11;
                    int pd2 = stilt ? 0 : (range <= 6 ? Math.max(1, padDepth) : Math.max(padDepth, 4));
                    int ch2 = stilt ? Math.max(2, clearHeight / 2) : clearHeight;

                    if (stilt) BuildReportContext.addFootingStiltUnit();
                    else if (pd2 > 0) BuildReportContext.addFootingPadUnit();

                    int pdClamped = Math.max(0, Math.min(6, pd2));
                    int chClamped = Math.max(0, Math.min(16, ch2));
                    List<PlannedBlock> p0 = TerrainFit.adaptivePad(world, buildingOrigin2, fpW, fpD, targetY, fillMaterial,
                            pdClamped, chClamped);

                    // Budget control: if too many terrain edits, degrade (reduce pad, then reduce clear, else skip).
                    if (terrainBudgetBlocks > 0 && p0.size() > terrainBudgetBlocks) {
                        BuildReportContext.addTerrainBudgetDegrade();
                        List<PlannedBlock> p1 = TerrainFit.adaptivePad(world, buildingOrigin2, fpW, fpD, targetY, fillMaterial,
                                0, Math.max(0, Math.min(6, chClamped)));
                        if (p1.size() <= terrainBudgetBlocks) {
                            pad = p1;
                        } else {
                            BuildReportContext.addTerrainBudgetDegrade();
                            List<PlannedBlock> p2 = TerrainFit.adaptivePad(world, buildingOrigin2, fpW, fpD, targetY, fillMaterial,
                                    0, 2);
                            if (p2.size() <= terrainBudgetBlocks) pad = p2;
                            else {
                                BuildReportContext.addTerrainBudgetDegrade();
                                pad = List.of();
                            }
                        }
                    } else {
                        pad = p0;
                    }
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
}

