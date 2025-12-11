package com.formacraft.common.model.blueprint;

import com.google.gson.JsonElement;

/**
 * 蓝图数据结构
 * 用于保存和加载 CitySpec、CompositeSpec 或 BuildingSpec
 */
public class Blueprint {
    private String type;          // BuildingSpec | CompositeSpec | CitySpec
    private String name;
    private String description;
    private int formatVersion = 1;
    private JsonElement data;     // Google Gson JsonElement，可存任何 JSON

    public Blueprint() {}

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getFormatVersion() {
        return formatVersion;
    }

    public void setFormatVersion(int formatVersion) {
        this.formatVersion = formatVersion;
    }

    public JsonElement getData() {
        return data;
    }

    public void setData(JsonElement data) {
        this.data = data;
    }
}

