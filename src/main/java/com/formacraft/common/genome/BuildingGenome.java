package com.formacraft.common.genome;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * BuildingGenome v1：建筑 DNA 标准（跨 AI/后端/前端/执行层共用）
 *
 * 注意：v1 不描述具体 block/patch，只描述空间逻辑与生成倾向。
 * 建议挂载方式：BuildingSpec.extra.genome = BuildingGenome（JSON object）
 */
public class BuildingGenome {
    public String genomeVersion = "1.0";

    public Archetype archetype = new Archetype();
    public Topology topology = new Topology();
    public Structure structure = new Structure();
    public Form form = new Form();
    public Symmetry symmetry = new Symmetry();

    public List<String> modules = new ArrayList<>();
    public Materials materials = new Materials();
    public CulturalStyle culturalStyle = new CulturalStyle();
    public Constraints constraints = new Constraints();
    public AIHints aiHints = new AIHints();

    /** 原型识别（强形象/地标） */
    public static class Archetype {
        public String id = "generic";
        public double confidence = 0.0;
    }

    /** 空间拓扑 */
    public static class Topology {
        public String layout = "rectangular";     // rectangular/circular/linear/radial/freeform
        public String composition = "single";     // single/cluster/chain/grid
        public String axis = "none";              // centered/axial/none
        public String levels = "mixed";           // horizontal/vertical/mixed
    }

    /** 结构逻辑 */
    public static class Structure {
        public String type = "hybrid";            // solid/frame/hybrid/suspended
        public Double massiveness = null;         // 0~1
        public Double voidRatio = null;           // 0~1
        public String supports = null;            // central/distributed
    }

    /** 形态语法 */
    public static class Form {
        public String repetition = null;          // none/horizontal/vertical/radial
        public String progression = null;         // uniform/tapering/stepping/upward
        public String curvature = null;           // straight/curved/mixed
        public String rhythm = null;              // regular/segmented/irregular
    }

    /** 对称 */
    public static class Symmetry {
        public String type = "none";              // none/bilateral/radial/grid
        public Integer order = null;              // for radial
        public Boolean mirror = null;
    }

    /** 材料语义（非具体方块） */
    public static class Materials {
        public String primary = null;             // stone/wood/earth/metal/glass/mixed
        public String secondary = null;
        public String accent = null;
        public String textureBias = null;         // rough/smooth/polished/aged
        public Map<String, Object> extra = null;  // future-proof
    }

    /** 文化/风格标签 */
    public static class CulturalStyle {
        public String region = null;
        public String era = null;
        public List<String> keywords = null;
    }

    /** 硬约束 */
    public static class Constraints {
        public Integer maxHeight = null;
        public Boolean respectTerrain = null;
        public Boolean insideSelectionOnly = null;
        public List<String> noModifyZones = null;
    }

    /** 仅给 AI 看 */
    public static class AIHints {
        public String reference = null;
        public List<String> priority = null;
        public List<String> avoid = null;
        public Map<String, Object> extra = null;
    }
}


