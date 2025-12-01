package com.formacraft.common.builder;

import com.formacraft.common.lang.StructureData;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class StructureBuilder {
	public static void generate(World world, BlockPos start, StructureData data) {
		if (world == null || start == null) return;

		Block block = resolveBlock(data);
		String type = data != null ? data.type : "cube";
		int towers = data != null ? Math.max(0, data.towers) : 0;
		String style = data != null ? data.style : "default";
		int width = data != null && data.width > 0 ? data.width : 0;
		int height = data != null && data.height > 0 ? data.height : 0;
		int depth = data != null && data.depth > 0 ? data.depth : 0;

		if ("tower".equalsIgnoreCase(type)) {
			int towerHeight = height > 0 ? height : 8 + towers * 2;
			buildTower(world, start, block, towerHeight);
		} else if ("castle".equalsIgnoreCase(type)) {
			int size = width > 0 ? width : 6 + towers * 2;
			buildCastle(world, start, block, size, style);
		} else if ("house".equalsIgnoreCase(type)) {
			int houseWidth = width > 0 ? width : 6;
			int houseDepth = depth > 0 ? depth : 5;
			int houseHeight = height > 0 ? height : 4;
			buildHouse(world, start, block, style, houseWidth, houseDepth, houseHeight);
		} else {
			int size = width > 0 ? width : 5;
			buildCube(world, start, block, size);
		}
	}

	private static Block resolveBlock(StructureData data) {
		String material = data != null ? data.material : null;
		if (material == null) return Blocks.STONE;
		material = material.toLowerCase();
		if (material.contains("stone")) return Blocks.STONE;
		if (material.contains("wood") || material.contains("oak")) return Blocks.OAK_PLANKS;
		if (material.contains("brick")) return Blocks.BRICKS;
		if (material.contains("glass")) return Blocks.GLASS;
		return Blocks.STONE;
	}

	private static void buildCube(World world, BlockPos start, Block block, int size) {
		for (int x = 0; x < size; x++)
			for (int y = 0; y < size; y++)
				for (int z = 0; z < size; z++)
					world.setBlockState(start.add(x, y, z), block.getDefaultState());
	}

	private static void buildTower(World world, BlockPos start, Block block, int height) {
		int radius = 2;
		for (int y = 0; y < height; y++) {
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (dx * dx + dz * dz <= radius * radius) {
						world.setBlockState(start.add(dx, y, dz), block.getDefaultState());
					}
				}
			}
		}
	}

	private static void buildCastle(World world, BlockPos start, Block block, int size, String style) {
		int height = 5;
		// 主体方形城墙
		for (int x = 0; x < size; x++) {
			for (int z = 0; z < size; z++) {
				boolean isWall = x == 0 || x == size - 1 || z == 0 || z == size - 1;
				if (!isWall) continue;
				for (int y = 0; y < height; y++) {
					world.setBlockState(start.add(x, y, z), block.getDefaultState());
				}
			}
		}
		// 四角塔楼
		buildTower(world, start, block, height + 3);
		buildTower(world, start.add(size - 1, 0, 0), block, height + 3);
		buildTower(world, start.add(0, 0, size - 1), block, height + 3);
		buildTower(world, start.add(size - 1, 0, size - 1), block, height + 3);
	}

	private static void buildHouse(World world, BlockPos start, Block block, String style, int width, int depth, int height) {
		// 墙体
		for (int x = 0; x < width; x++) {
			for (int z = 0; z < depth; z++) {
				boolean isWall = x == 0 || x == width - 1 || z == 0 || z == depth - 1;
				if (!isWall) continue;
				for (int y = 0; y < height; y++) {
					world.setBlockState(start.add(x, y, z), block.getDefaultState());
				}
			}
		}
		// 简单斜屋顶
		Block roofBlock = Blocks.DARK_OAK_PLANKS;
		for (int y = 0; y < 3; y++) {
			for (int x = -y; x < width + y; x++) {
				for (int z = 0; z < depth; z++) {
					world.setBlockState(start.add(x, height + y, z), roofBlock.getDefaultState());
				}
			}
		}
	}
}
