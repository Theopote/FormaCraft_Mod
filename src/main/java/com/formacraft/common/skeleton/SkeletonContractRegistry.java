package com.formacraft.common.skeleton;

import java.util.*;

/**
 * SkeletonContract 注册表
 * 为每个 SkeletonType 提供默认的语义约束
 */
public class SkeletonContractRegistry {
    private static final Map<SkeletonType, SkeletonContract> CONTRACTS = new HashMap<>();
    
    static {
        // ===== Linear / Path =====
        register(new SimpleContract(SkeletonType.LINEAR_PATH)
            .requiredAnchors("start", "end")
            .requiresTerrainSampling(false)
            .allowsOverlap(false)
            .prefersSymmetry(false)
            .supportsIrregularShape(false)
            .isMultiLevel(false)
            .requiresCenter(false)
            .supportsBranches(false)
            .description("直线路径，适合道路、长城等线性结构")
        );
        
        register(new SimpleContract(SkeletonType.PATH_POLYLINE)
            .requiredAnchors("start", "end")
            .requiresTerrainSampling(false)
            .allowsOverlap(false)
            .prefersSymmetry(false)
            .supportsIrregularShape(true)
            .isMultiLevel(false)
            .requiresCenter(false)
            .supportsBranches(false)
            .description("折线路径，适合山路、城墙等需要转向的结构")
        );
        
        register(new SimpleContract(SkeletonType.CONTOUR_FOLLOW)
            .requiredAnchors("start", "end")
            .requiresTerrainSampling(true)
            .allowsOverlap(false)
            .prefersSymmetry(false)
            .supportsIrregularShape(true)
            .isMultiLevel(false)
            .requiresCenter(false)
            .supportsBranches(false)
            .description("等高线跟随，适合山路、长城等依地形路径")
        );
        
        // ===== Radial / Center =====
        register(new SimpleContract(SkeletonType.RADIAL_RING)
            .requiredAnchors("center")
            .requiresTerrainSampling(false)
            .allowsOverlap(false)
            .prefersSymmetry(true)
            .supportsIrregularShape(false)
            .isMultiLevel(false)
            .requiresCenter(true)
            .supportsBranches(false)
            .description("闭合环形，适合土楼、圆形要塞等")
        );
        
        register(new SimpleContract(SkeletonType.RADIAL_SPOKE)
            .requiredAnchors("center")
            .requiresTerrainSampling(false)
            .allowsOverlap(false)
            .prefersSymmetry(true)
            .supportsIrregularShape(false)
            .isMultiLevel(false)
            .requiresCenter(true)
            .supportsBranches(true)
            .description("中心辐射，适合天坛、广场、祭坛、交通核心等")
        );
        
        // ===== Vertical =====
        register(new SimpleContract(SkeletonType.VERTICAL_STACK)
            .requiredAnchors("base")
            .requiresTerrainSampling(false)
            .allowsOverlap(true)
            .prefersSymmetry(false)
            .supportsIrregularShape(false)
            .isMultiLevel(true)
            .requiresCenter(false)
            .supportsBranches(false)
            .description("垂直堆叠，适合多层建筑、楼层结构")
        );
        
        register(new SimpleContract(SkeletonType.VERTICAL_TAPER)
            .requiredAnchors("base")
            .requiresTerrainSampling(false)
            .allowsOverlap(false)
            .prefersSymmetry(true)
            .supportsIrregularShape(false)
            .isMultiLevel(true)
            .requiresCenter(true)
            .supportsBranches(false)
            .description("向上收缩，适合塔、尖顶等向上收窄的结构")
        );
        
        // ===== Area / Enclosure =====
        register(new SimpleContract(SkeletonType.GRID)
            .requiredAnchors("origin")
            .requiresTerrainSampling(false)
            .allowsOverlap(false)
            .prefersSymmetry(true)
            .supportsIrregularShape(false)
            .isMultiLevel(false)
            .requiresCenter(false)
            .supportsBranches(false)
            .description("网格布局，适合城市、街区等规则排列")
        );
        
        register(new SimpleContract(SkeletonType.COURTYARD)
            .requiredAnchors("center")
            .requiresTerrainSampling(false)
            .allowsOverlap(false)
            .prefersSymmetry(true)
            .supportsIrregularShape(true)
            .isMultiLevel(false)
            .requiresCenter(true)
            .supportsBranches(false)
            .description("中庭式，适合四合院、修道院等围合结构")
        );
        
        register(new SimpleContract(SkeletonType.PERIMETER_LOOP)
            .requiredAnchors("start")
            .requiresTerrainSampling(false)
            .allowsOverlap(false)
            .prefersSymmetry(false)
            .supportsIrregularShape(true)
            .isMultiLevel(false)
            .requiresCenter(false)
            .supportsBranches(false)
            .description("轮廓闭环，适合城墙、院落等闭合轮廓")
        );
        
        register(new SimpleContract(SkeletonType.ENCLOSURE)
            .requiredAnchors("boundary")
            .requiresTerrainSampling(false)
            .allowsOverlap(false)
            .prefersSymmetry(false)
            .supportsIrregularShape(true)
            .isMultiLevel(false)
            .requiresCenter(false)
            .supportsBranches(false)
            .description("不规则围合，适合中式院落、古城城墙、山地要塞等")
        );
        
        // ===== Span / Structure =====
        register(new SimpleContract(SkeletonType.SPAN_SUSPENSION)
            .requiredAnchors("start", "end")
            .requiresTerrainSampling(true)
            .allowsOverlap(false)
            .prefersSymmetry(false)
            .supportsIrregularShape(false)
            .isMultiLevel(false)
            .requiresCenter(false)
            .supportsBranches(false)
            .description("跨越结构，适合桥梁等跨越障碍的结构")
        );
        
        // ===== Terrain =====
        register(new SimpleContract(SkeletonType.TERRACED)
            .requiredAnchors("base")
            .requiresTerrainSampling(true)
            .allowsOverlap(false)
            .prefersSymmetry(false)
            .supportsIrregularShape(true)
            .isMultiLevel(true)
            .requiresCenter(false)
            .supportsBranches(false)
            .description("台地式，适合梯田、山城等依地形分层结构")
        );
        
        // ===== Composite =====
        register(new SimpleContract(SkeletonType.HIERARCHICAL_TREE)
            .requiredAnchors("root")
            .requiresTerrainSampling(false)
            .allowsOverlap(false)
            .prefersSymmetry(false)
            .supportsIrregularShape(true)
            .isMultiLevel(false)
            .requiresCenter(false)
            .supportsBranches(true)
            .description("主从结构，适合寺庙群、校园、园区等有主从关系的建筑群")
        );
        
        register(new SimpleContract(SkeletonType.COMPOUND)
            .requiresTerrainSampling(false)
            .allowsOverlap(true)
            .prefersSymmetry(false)
            .supportsIrregularShape(true)
            .isMultiLevel(true)
            .requiresCenter(false)
            .supportsBranches(true)
            .description("任意组合，兜底类型，可以组合任意其他骨架类型")
        );
    }
    
    private static void register(SkeletonContract contract) {
        CONTRACTS.put(contract.type(), contract);
    }
    
    public static SkeletonContract getContract(SkeletonType type) {
        return CONTRACTS.getOrDefault(type, createDefaultContract(type));
    }
    
    private static SkeletonContract createDefaultContract(SkeletonType type) {
        return new SimpleContract(type)
            .requiredAnchors("origin")
            .requiresTerrainSampling(false)
            .allowsOverlap(false)
            .prefersSymmetry(false)
            .supportsIrregularShape(false)
            .isMultiLevel(false)
            .requiresCenter(false)
            .supportsBranches(false)
            .description("默认骨架类型：" + type.name());
    }
    
    /**
     * 简单的 Contract 实现（Builder 模式）
     */
    private static class SimpleContract implements SkeletonContract {
        private final SkeletonType type;
        private List<String> requiredAnchors = new ArrayList<>();
        private boolean requiresTerrainSampling = false;
        private boolean allowsOverlap = false;
        private boolean prefersSymmetry = false;
        private boolean supportsIrregularShape = false;
        private boolean isMultiLevel = false;
        private boolean requiresCenter = false;
        private boolean supportsBranches = false;
        private String description = "";
        
        public SimpleContract(SkeletonType type) {
            this.type = type;
        }
        
        public SimpleContract requiredAnchors(String... anchors) {
            this.requiredAnchors = Arrays.asList(anchors);
            return this;
        }
        
        public SimpleContract requiresTerrainSampling(boolean value) {
            this.requiresTerrainSampling = value;
            return this;
        }
        
        public SimpleContract allowsOverlap(boolean value) {
            this.allowsOverlap = value;
            return this;
        }
        
        public SimpleContract prefersSymmetry(boolean value) {
            this.prefersSymmetry = value;
            return this;
        }
        
        public SimpleContract supportsIrregularShape(boolean value) {
            this.supportsIrregularShape = value;
            return this;
        }
        
        public SimpleContract isMultiLevel(boolean value) {
            this.isMultiLevel = value;
            return this;
        }
        
        public SimpleContract requiresCenter(boolean value) {
            this.requiresCenter = value;
            return this;
        }
        
        public SimpleContract supportsBranches(boolean value) {
            this.supportsBranches = value;
            return this;
        }
        
        public SimpleContract description(String desc) {
            this.description = desc;
            return this;
        }
        
        @Override
        public SkeletonType type() {
            return type;
        }
        
        @Override
        public List<String> requiredAnchors() {
            return requiredAnchors;
        }
        
        @Override
        public boolean requiresTerrainSampling() {
            return requiresTerrainSampling;
        }
        
        @Override
        public boolean allowsOverlap() {
            return allowsOverlap;
        }
        
        @Override
        public boolean prefersSymmetry() {
            return prefersSymmetry;
        }
        
        @Override
        public boolean supportsIrregularShape() {
            return supportsIrregularShape;
        }
        
        @Override
        public boolean isMultiLevel() {
            return isMultiLevel;
        }
        
        @Override
        public boolean requiresCenter() {
            return requiresCenter;
        }
        
        @Override
        public boolean supportsBranches() {
            return supportsBranches;
        }
        
        @Override
        public String description() {
            return description;
        }
    }
}

