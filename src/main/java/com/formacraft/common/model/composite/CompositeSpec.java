package com.formacraft.common.model.composite;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.path.PathSpec;

import java.util.List;

/**
 * 复合结构规格
 * 包含多个子结构及其相对坐标
 * 用于生成城市、要塞、村庄等复合建筑
 */
public class CompositeSpec {
    
    /**
     * 子结构定义
     */
    public static class SubStructure {
        private String type;  // TOWER, HOUSE, BRIDGE, WALL, etc.
        private BuildingSpec spec;
        private Offset offset;

        public SubStructure() {}

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public BuildingSpec getSpec() {
            return spec;
        }

        public void setSpec(BuildingSpec spec) {
            this.spec = spec;
        }

        public Offset getOffset() {
            return offset;
        }

        public void setOffset(Offset offset) {
            this.offset = offset;
        }
    }

    /**
     * 三维坐标偏移
     */
    public static class Offset {
        public int x;
        public int y;
        public int z;

        public Offset() {}

        public Offset(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private List<SubStructure> structures;
    private List<PathSpec> paths;  // 路径列表

    public CompositeSpec() {}

    public List<SubStructure> getStructures() {
        return structures;
    }

    public void setStructures(List<SubStructure> structures) {
        this.structures = structures;
    }

    public List<PathSpec> getPaths() {
        return paths;
    }

    public void setPaths(List<PathSpec> paths) {
        this.paths = paths;
    }
}

