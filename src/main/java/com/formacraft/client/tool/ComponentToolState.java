package com.formacraft.client.tool;

import com.formacraft.common.component.ComponentCategory;
import com.formacraft.common.component.transform.Mirror;
import com.formacraft.common.semantic.SemanticPart;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.HashSet;
import java.util.Set;

/**
 * ComponentTool（v1）状态：面板字段 + anchor/facing。
 */
public class ComponentToolState {
    public ComponentCategory category = ComponentCategory.GENERIC;
    public String name = "New Component";
    public Set<String> tags = new HashSet<>();

    /** 世界坐标 anchor（必须落在选区内）；null 则默认选区 min。 */
    public BlockPos anchorWorld = null;
    /** 构件正面朝向（用于后续旋转/匹配）。 */
    public Direction facing = Direction.SOUTH;
    /** 构件镜像模式（v1）。 */
    public Mirror mirror = Mirror.NONE;

    /** 材质模式：是否使用语义调色板进行“换皮”。 */
    public boolean semanticSkin = false;
    /** 语义部位（semanticSkin=true 时用于选取材质）。 */
    public SemanticPart semanticPart = SemanticPart.WALL;
    /** SemanticStyleProfile id（semanticSkin=true 时用于材质规则）。 */
    public String semanticStyleId = "DEFAULT";

    /** UI 状态：正在选择 anchor */
    public boolean pickingAnchor = false;
}

