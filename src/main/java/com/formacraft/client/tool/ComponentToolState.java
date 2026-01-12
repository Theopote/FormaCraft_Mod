package com.formacraft.client.tool;

import com.formacraft.common.component.ComponentCategory;
import com.formacraft.common.component.transform.Mirror;
import com.formacraft.common.semantic.SemanticPart;
import com.formacraft.common.component.socket.SocketType;
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
    /** 语义部位（semanticSkin=true 时用于选取材质）；null 表示 AUTO（按方块类型/位置自动猜测）。 */
    public SemanticPart semanticPart = SemanticPart.WALL;
    /** SemanticStyleProfile id（semanticSkin=true 时用于材质规则）。 */
    public String semanticStyleId = "DEFAULT";

    /**
     * 保存时是否写入每个 block 的 semantic（AUTO 标注）。
     * - 打开：构件入库即具备“基因接口”，后续可任意风格换皮
     * - 关闭：仅在 semanticSkin=true 时才写 semantic（旧行为）
     */
    public boolean semanticTagOnSave = true;

    /** 放置来源：false=当前选区；true=从构件库加载的构件。 */
    public boolean useLibrary = false;
    /** 构件库中当前选中的构件 id（仅 useLibrary=true 有意义）。 */
    public String librarySelectedId = null;
    /** 构件库中当前选中的构件 name（仅用于 UI 展示）。 */
    public String librarySelectedName = null;

    /** UI 状态：正在选择 anchor */
    public boolean pickingAnchor = false;

    // ===== Socket 编辑（v1）=====
    /** 已添加 socket 数量（仅用于 UI 反馈/自动命名）。 */
    public int socketCount = 0;
    /** 当前 socket 类型（循环切换）。 */
    public SocketType socketType = SocketType.DOOR;
    /** 当前 socket 朝向（循环切换）。 */
    public Direction socketFacing = Direction.SOUTH;
    /** 当前 socket 尺寸（w/h/d）。 */
    public int socketW = 2, socketH = 3, socketD = 1;
    /** 当前 socket 原点（相对 anchor 的局部坐标），null 表示未设置。 */
    public BlockPos socketOriginLocal = null;
    /** UI 状态：正在点选 socket 原点 */
    public boolean pickingSocket = false;
}

