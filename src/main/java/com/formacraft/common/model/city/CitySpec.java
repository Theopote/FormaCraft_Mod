package com.formacraft.common.model.city;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.path.PathSpec;

import java.util.List;

/**
 * 城市规格
 * 用于描述整个城市的布局和结构
 */
public class CitySpec {
    
    /**
     * 三维坐标点
     */
    public static class Point {
        public int x;
        public int y;
        public int z;

        public Point() {}

        public Point(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /**
     * 城市区块
     */
    public static class Zone {
        public String name;
        public int radius;
        public Point center;
        public String type;  // PLAZA / RESIDENTIAL / MARKET / WALL / GATE / INDUSTRIAL / COMMERCIAL

        public Zone() {}

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getRadius() {
            return radius;
        }

        public void setRadius(int radius) {
            this.radius = radius;
        }

        public Point getCenter() {
            return center;
        }

        public void setCenter(Point center) {
            this.center = center;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }
    }

    /**
     * 桥梁规划
     */
    public static class BridgePlan {
        public String id;              // 唯一标识符，如 "bridge_1", "main_bridge"
        public Point from;
        public Point to;
        public String bridgeType;  // flat / arched / suspension

        public BridgePlan() {}

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public Point getFrom() {
            return from;
        }

        public void setFrom(Point from) {
            this.from = from;
        }

        public Point getTo() {
            return to;
        }

        public void setTo(Point to) {
            this.to = to;
        }

        public String getBridgeType() {
            return bridgeType;
        }

        public void setBridgeType(String bridgeType) {
            this.bridgeType = bridgeType;
        }
    }

    /**
     * 建筑规划
     */
    public static class StructurePlan {
        public String id;              // 唯一标识符，如 "tower_1", "house_A"
        public String type;  // HOUSE / TOWER / BRIDGE / WALL / CUSTOM
        public BuildingSpec spec;
        public Point offset;
        public String zone;  // 所属区块名称（可选）

        public StructurePlan() {}

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

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

        public Point getOffset() {
            return offset;
        }

        public void setOffset(Point offset) {
            this.offset = offset;
        }

        public String getZone() {
            return zone;
        }

        public void setZone(String zone) {
            this.zone = zone;
        }
    }

    // 城市元信息
    private String cityName;
    private String style;  // MEDIEVAL / MODERN / ASIAN / FUTURISTIC
    private String size;   // SMALL / MEDIUM / LARGE
    private String biome;  // plains / forest / desert / etc.

    // 城市结构
    private List<Zone> zones;
    private List<StructurePlan> structures;
    private List<PathSpec> roads;
    private List<BridgePlan> bridges;

    public CitySpec() {}

    public String getCityName() {
        return cityName;
    }

    public void setCityName(String cityName) {
        this.cityName = cityName;
    }

    public String getStyle() {
        return style;
    }

    public void setStyle(String style) {
        this.style = style;
    }

    public String getSize() {
        return size;
    }

    public void setSize(String size) {
        this.size = size;
    }

    public String getBiome() {
        return biome;
    }

    public void setBiome(String biome) {
        this.biome = biome;
    }

    public List<Zone> getZones() {
        return zones;
    }

    public void setZones(List<Zone> zones) {
        this.zones = zones;
    }

    public List<StructurePlan> getStructures() {
        return structures;
    }

    public void setStructures(List<StructurePlan> structures) {
        this.structures = structures;
    }

    public List<PathSpec> getRoads() {
        return roads;
    }

    public void setRoads(List<PathSpec> roads) {
        this.roads = roads;
    }

    public List<BridgePlan> getBridges() {
        return bridges;
    }

    public void setBridges(List<BridgePlan> bridges) {
        this.bridges = bridges;
    }
}

