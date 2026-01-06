package com.formacraft.common.terrain;

import java.util.Objects;

/**
 * Policy that constrains how the generator can modify terrain.
 * 
 * This is what PromptAssembler will serialize into prompt JSON.
 * 
 * 地形策略参数 + 作用域
 */
public final class TerrainPolicy {

    /**
     * 地形策略的作用域
     */
    public enum Scope {
        /** 无作用域（仅锚点） */
        NONE,
        /** 仅在选区内 */
        SELECTION,
        /** 仅在轮廓内 */
        OUTLINE,
        /** 仅在路径走廊内 */
        PATH,
        /** 整个建造区域（危险；主要用于明确的用户请求） */
        ALL
    }

    public final TerrainStrategy strategy;
    public final Scope scope;

    /** For ADAPTIVE/TERRACE: how deep we can cut down. */
    public final int maxCutDepth;

    /** For ADAPTIVE/TERRACE: how high we can fill up. */
    public final int maxFillHeight;

    /** Whether bridges are allowed when path crosses gaps/water. */
    public final boolean allowBridges;

    /** Whether stairs/ramps are allowed for connections. */
    public final boolean allowStairs;

    /** Whether local foundations (pads/pillars) are allowed. */
    public final boolean allowFoundations;

    /** Hint: keep overall terrain shape (avoid massive earthwork). */
    public final boolean preserveOverallShape;

    /** When true: "do not flatten whole area" even if some tools exist (default for ADAPTIVE). */
    public final boolean avoidLargeScaleFlatten;

    private TerrainPolicy(Builder b) {
        this.strategy = Objects.requireNonNull(b.strategy, "strategy");
        this.scope = Objects.requireNonNull(b.scope, "scope");
        this.maxCutDepth = b.maxCutDepth;
        this.maxFillHeight = b.maxFillHeight;
        this.allowBridges = b.allowBridges;
        this.allowStairs = b.allowStairs;
        this.allowFoundations = b.allowFoundations;
        this.preserveOverallShape = b.preserveOverallShape;
        this.avoidLargeScaleFlatten = b.avoidLargeScaleFlatten;
    }

    public static Builder builder() { 
        return new Builder(); 
    }

    public static final class Builder {
        private TerrainStrategy strategy = TerrainStrategy.ADAPTIVE; // 默认：ADAPTIVE
        private Scope scope = Scope.NONE;

        private int maxCutDepth = 3;
        private int maxFillHeight = 3;
        private boolean allowBridges = true;
        private boolean allowStairs = true;
        private boolean allowFoundations = true;

        private boolean preserveOverallShape = true;
        private boolean avoidLargeScaleFlatten = true; // 默认：避免大规模平整

        public Builder strategy(TerrainStrategy v) { 
            this.strategy = v; 
            return this; 
        }
        
        public Builder scope(Scope v) { 
            this.scope = v; 
            return this; 
        }
        
        public Builder maxCutDepth(int v) { 
            this.maxCutDepth = v; 
            return this; 
        }
        
        public Builder maxFillHeight(int v) { 
            this.maxFillHeight = v; 
            return this; 
        }
        
        public Builder allowBridges(boolean v) { 
            this.allowBridges = v; 
            return this; 
        }
        
        public Builder allowStairs(boolean v) { 
            this.allowStairs = v; 
            return this; 
        }
        
        public Builder allowFoundations(boolean v) { 
            this.allowFoundations = v; 
            return this; 
        }
        
        public Builder preserveOverallShape(boolean v) { 
            this.preserveOverallShape = v; 
            return this; 
        }
        
        public Builder avoidLargeScaleFlatten(boolean v) { 
            this.avoidLargeScaleFlatten = v; 
            return this; 
        }

        public TerrainPolicy build() { 
            return new TerrainPolicy(this); 
        }
    }
}

