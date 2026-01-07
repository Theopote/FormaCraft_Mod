package com.formacraft.common.gen;

import com.formacraft.client.tool.PathTool;
import com.formacraft.common.patch.filter.PatchFilterContext;
import com.formacraft.common.terrain.TerrainStrategySampler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.world.World;

/**
 * GeneratorContext（生成器上下文）
 * 
 * 核心职责：把 Palette + Tools + TerrainStrategy + PathTool 都带进来
 * 
 * 设计理念：
 * - 之后 K1/K2/K3 都靠这个上下文做"沿路径布局"
 * - 所有生成器共享同一个上下文
 * - 上下文包含所有必要的工具状态和策略
 */
public class GeneratorContext {

    public final World world;
    public final MinecraftClient client;

    // 风格 / 选材（你可以替换为你已有的 StyleProfile/Palette）
    public final PaletteResolver palette;

    // 工具状态（Selection/Outline/Symmetry/Forbidden 等）
    public final PatchFilterContext toolContext;

    // 新增：路径工具（道路/长城/沿路径布局）
    public final PathTool pathTool;

    // 新增：地形策略采样器（平整程度、台阶、桥等）
    public final TerrainStrategySampler terrainStrategy;

    public GeneratorContext(
            World world,
            MinecraftClient client,
            PaletteResolver palette,
            PatchFilterContext toolContext,
            PathTool pathTool,
            TerrainStrategySampler terrainStrategy
    ) {
        this.world = world;
        this.client = client;
        this.palette = palette;
        this.toolContext = toolContext;
        this.pathTool = pathTool;
        this.terrainStrategy = terrainStrategy;
    }
}

