package com.formacraft.server.generator;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.style.profile.BuildStrategy;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.StyleGenome;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.BlockPos;

/**
 * 房屋生成上下文
 * <p>
 * 包含生成房屋所需的所有参数和配置信息。
 * 这是一个不可变的数据载体，通过 record 自动生成访问器方法。
 * </p>
 * 
 * @param spec 建筑规范
 * @param origin 建筑原点（世界坐标）
 * @param world 服务器世界
 * @param width 建筑宽度（方块数）
 * @param depth 建筑深度（方块数）
 * @param height 建筑高度（方块数）
 * @param floors 楼层数
 * @param style 建筑风格
 * @param genome 风格基因（数据驱动的默认配置）
 * @param profile 风格配置文件
 * @param materials 材质集合
 * @param hasWindows 是否有窗户
 * @param hasDoor 是否有门
 * @param hasRoof 是否有屋顶
 * @param doorStyle 门样式（single/double/arched）
 * @param roofType 屋顶类型（flat/gable/hipped等）
 * @param windowRatio 窗户比例（0.0-1.0）
 * @param wallPattern 墙体花纹模式
 * @param wallStrategy 墙体策略（SOLID_WALL/WINDOWED_WALL）
 * @param floorHeight 每层楼的高度（方块数）
 * @param paletteId 调色板ID（用于材质变化）
 * @param doorSide 门的朝向
 * @param layoutInfo 布局信息（包含庭院等）
 */
public record HouseGenerationContext(
        BuildingSpec spec,
        BlockPos origin,
        ServerWorld world,
        int width,
        int depth,
        int height,
        int floors,
        BuildingStyle style,
        StyleGenome genome,
        StyleProfile profile,
        HouseMaterialResolver.MaterialSet materials,
        boolean hasWindows,
        boolean hasDoor,
        boolean hasRoof,
        String doorStyle,
        String roofType,
        double windowRatio,
        String wallPattern,
        BuildStrategy wallStrategy,
        int floorHeight,
        String paletteId,
        Direction doorSide,
        HouseLayoutGenerator.LayoutInfo layoutInfo
) {
    // Record 自动生成构造函数、访问器方法、equals、hashCode、toString
}
