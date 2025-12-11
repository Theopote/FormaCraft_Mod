package com.formacraft.server.generator.composite;

import com.formacraft.common.model.composite.CompositeSpec;
import com.formacraft.common.model.path.PathSpec;
import com.formacraft.server.build.GeneratedStructure;
import com.formacraft.server.build.PlannedBlock;
import com.formacraft.server.generator.*;
import com.formacraft.server.generator.path.PathGenerator;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 复合结构生成器
 * 将多个子结构组合成一个完整的复合建筑
 * 用于生成城市、要塞、村庄等
 */
public class CompositeStructureGenerator {
    
    private final Map<String, StructureGenerator> registry = new HashMap<>();
    private final PathGenerator pathGenerator = new PathGenerator();

    public CompositeStructureGenerator() {
        // 注册已完成的 generators
        registry.put("HOUSE", new HouseGenerator());
        registry.put("TOWER", new TowerGenerator());
        registry.put("BRIDGE", new BridgeGenerator());
        registry.put("WALL", new WallGenerator());
        // PathGenerator 单独管理，不放在 registry 中
    }

    /**
     * 生成复合结构
     * @param spec 复合结构规格
     * @param origin 复合结构的原点
     * @param world 服务器世界
     * @return 生成的复合结构
     */
    public GeneratedStructure generate(CompositeSpec spec, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> merged = new ArrayList<>();

        if (spec == null || spec.getStructures() == null) {
            return new GeneratedStructure(null, origin, "Empty CompositeStructure", merged);
        }

        for (CompositeSpec.SubStructure sub : spec.getStructures()) {
            if (sub == null || sub.getType() == null || sub.getSpec() == null) {
                continue;
            }

            StructureGenerator gen = registry.get(sub.getType().toUpperCase());
            if (gen == null) {
                // 如果找不到生成器，尝试使用 StructureGeneratorFactory
                gen = StructureGeneratorFactory.getGenerator(sub.getSpec());
                if (gen == null) {
                    // 仍然找不到，跳过
                    continue;
                }
            }

            // 计算子结构的绝对坐标
            CompositeSpec.Offset offset = sub.getOffset();
            if (offset == null) {
                offset = new CompositeSpec.Offset(); // 默认 (0, 0, 0)
            }
            
            BlockPos subOrigin = origin.add(offset.x, offset.y, offset.z);

            // 生成子结构
            GeneratedStructure subStructure = gen.generate(sub.getSpec(), subOrigin, world);
            
            // 合并到总列表
            merged.addAll(subStructure.getBlocks());
        }

        // 2. 生成道路（IMPORTANT: 道路覆盖在建筑之后，确保道路在建筑上方）
        if (spec.getPaths() != null && !spec.getPaths().isEmpty()) {
            for (PathSpec path : spec.getPaths()) {
                if (path == null) continue;
                
                GeneratedStructure pathStructure = pathGenerator.generate(path, origin, world);
                merged.addAll(pathStructure.getBlocks());
            }
        }

        String description = String.format("CompositeStructure (%d structures, %d paths)", 
                spec.getStructures() != null ? spec.getStructures().size() : 0,
                spec.getPaths() != null ? spec.getPaths().size() : 0);

        return new GeneratedStructure(
                null, // owner 将在 BuildExecutionService 中设置
                origin,
                description,
                merged
        );
    }
}

