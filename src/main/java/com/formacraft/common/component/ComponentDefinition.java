package com.formacraft.common.component;

import com.formacraft.common.component.placement.ComponentPlacementSpec;
import com.formacraft.common.component.socket.ComponentSocket;
import java.util.List;
import java.util.Set;

/**
 * 单个构件定义（v1）。
 * <p>
 * blocks 坐标系：以 anchor 为原点 (0,0,0)，blocks[].dx/dy/dz 为相对坐标。
 * anchor.facing 表示“构件正面”。
 */
public class ComponentDefinition {
    public String schema = "formacraft.component.v1";
    public int version = 1;

    public String id;
    public String name;
    public ComponentCategory category = ComponentCategory.GENERIC;

    public List<String> tags;

    /**
     * 文化风格（可空，例如 CHINESE / GOTHIC / MODERN）。
     * 用于 AI 检索与风格亲和度匹配；旧 JSON 无此字段时保持 null。
     */
    public String culturalStyle;

    /**
     * 指向 {@link com.formacraft.common.component.archetype.ComponentArchetype#id} 的引用（可空）。
     * 未设置时默认与 {@link #id} 相同，由保存流程自动生成侧车原型。
     */
    public String archetypeRef;

    /**
     * 几何原型（可空，{@link com.formacraft.common.component.archetype.GeometryArchetype} 名称）。
     * 例如 ORNAMENT / FRAME / LINEAR，供 AI 形态族匹配。
     */
    public String geometryArchetype;

    public Size size;
    public Anchor anchor;
    /** "NORTH"/"SOUTH"/"EAST"/"WEST" */
    public Set<String> allowed_facing;

    public PlacementRules placement_rules;

    /**
     * v1：语义放置规格（Attachment / Context / FacingPolicy / Constraints）。
     * <p>
     * 注意：这是高层语义，低层 blockstate 的 facing 仍由 transform 在落 patch 时推导/修正。
     */
    public ComponentPlacementSpec placementSpec;

    /**
     * v1.1：方向/宿主面提示（拾取阶段导出，用于后续语义化放置）。
     */
    public DirectionHints directionHints;

    /** v1.1：锚点归一化提示（用于变体/拉伸时的语义稳定）。 */
    public AnchorHint anchorHint;

    /** v1.1：放置提示（用于从语义到几何的桥接）。 */
    public PlacementHints placementHints;

    public List<BlockEntry> blocks;

    /** 可选：构件插槽（用于“安装/开洞”）。 */
    public List<ComponentSocket> sockets;

    public static class Size {
        public int w, h, d;
    }

    public static class Anchor {
        /** anchor 本身在构件内部相对坐标（v1 默认 0,0,0） */
        public int dx, dy, dz;
        /** "NORTH"/"SOUTH"/"EAST"/"WEST" */
        public String facing;
    }

    public static class PlacementRules {
        public boolean requires_ground = true;
        public boolean requires_wall = false;
        public boolean allow_mirror = true;
    }

    public static class DirectionHints {
        public String attachmentMode;
        public boolean hasInteriorExterior = false;
        public boolean hasBottomTop = false;

        public Mark inside;
        public Mark outside;
        public Mark bottom;
        public Mark top;

        public HostFace hostFace;

        public static class Mark {
            public int dx, dy, dz;
        }

        public static class HostFace {
            public int dx, dy, dz;
            public String normal;
            public boolean allowAir = false;
        }
    }

    public static class AnchorHint {
        /** 0..1 相对 bounds 宽度 */
        public float u;
        /** 0..1 相对 bounds 高度 */
        public float v;
        /** 0..1 相对 bounds 深度 */
        public float w;
    }

    public static class PlacementHints {
        /** AttachmentType.name() */
        public String attachment;
        /** 主轴：U/V/W */
        public String primaryAxis;
        /** 是否需要宿主面 */
        public boolean needsHostFace = false;
    }

    public static class BlockEntry {
        public int dx, dy, dz;
        /** v1：可选语义部位（用于风格驱动的材质替换；为空则走 block 字符串） */
        public com.formacraft.common.semantic.SemanticPart semantic;
        /** v1：blockstate string（例如 minecraft:spruce_door[facing=south,half=lower]） */
        public String block;
    }
}

