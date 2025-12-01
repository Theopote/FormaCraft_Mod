package com.formacraft.common.lang;

public class StructureData {
	public final String type;
	public final String material;
	public final int towers;
	public final String style;
	public final int width;
	public final int height;
	public final int depth;

	public StructureData(String type, String material, int towers, String style) {
		this(type, material, towers, style, 0, 0, 0);
	}

	public StructureData(String type, String material, int towers, String style, int width, int height, int depth) {
		this.type = type;
		this.material = material;
		this.towers = towers;
		this.style = style;
		this.width = width;
		this.height = height;
		this.depth = depth;
	}
}
