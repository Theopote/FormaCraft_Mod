package com.formacraft.server.asset;

import com.formacraft.server.build.PlannedBlock;
import com.formacraft.server.material.PaletteResolver;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * AssetLibrary: 预制件库加载器和放置系统
 * 
 * 功能：
 * 1. 从 assets/formacraft/asset_library/ 加载预制件
 * 2. 支持材质变量替换（$primary_log -> 调色板中的实际方块）
 * 3. 支持锚点系统（BOTTOM_CENTER, TOP_CENTER 等）
 * 4. 支持朝向转换
 */
public final class AssetLibrary {
    private static final String MOD_ID = "formacraft";
    private static final Gson GSON = new GsonBuilder().create();
    private static final Map<String, AssetDefinition> ASSETS = new HashMap<>();
    private static boolean loaded = false;
    
    private AssetLibrary() {}
    
    /**
     * 加载所有预制件（在 mod 初始化时调用）
     */
    public static synchronized void loadAssets() {
        if (loaded) return;
        
        try {
            Path assetDir = findAssetLibraryDir();
            if (assetDir == null || !Files.exists(assetDir)) {
                com.formacraft.FormacraftMod.LOGGER.info("Asset library directory not found, skipping");
                loaded = true;
                return;
            }
            
            // 递归加载所有 JSON 文件
            loadAssetsRecursive(assetDir);
            
            com.formacraft.FormacraftMod.LOGGER.info("Asset library loaded: {} assets", ASSETS.size());
            loaded = true;
        } catch (Exception e) {
            com.formacraft.FormacraftMod.LOGGER.error("Failed to load asset library", e);
            loaded = true; // 标记为已加载，避免重复尝试
        }
    }
    
    private static void loadAssetsRecursive(Path dir) throws Exception {
        try (var stream = Files.list(dir)) {
            for (Path path : stream.toList()) {
                if (Files.isDirectory(path)) {
                    loadAssetsRecursive(path);
                } else if (path.getFileName().toString().toLowerCase().endsWith(".json")) {
                    loadAssetFile(path);
                }
            }
        }
    }
    
    /**
     * Find asset_library dir under assets/formacraft/asset_library.
     * Prefers Fabric mod container resources; falls back to project filesystem for build tools.
     */
    private static Path findAssetLibraryDir() {
        // 1) Fabric runtime path (in game / in dev run)
        try {
            ModContainer mod = FabricLoader.getInstance().getModContainer(MOD_ID).orElse(null);
            if (mod != null) {
                Optional<Path> p = mod.findPath("assets/" + MOD_ID + "/asset_library");
                if (p.isPresent() && Files.exists(p.get()) && Files.isDirectory(p.get())) {
                    return p.get();
                }
            }
        } catch (Throwable ignored) {}
        
        // 2) Build-tool fallback (gradle JavaExec)
        try {
            Path p = Path.of("src/main/resources/assets/" + MOD_ID + "/asset_library");
            if (Files.exists(p) && Files.isDirectory(p)) return p;
        } catch (Throwable ignored) {}
        
        return null;
    }
    
    private static void loadAssetFile(Path file) {
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<?, ?> json = GSON.fromJson(reader, Map.class);
            AssetDefinition asset = parseAsset(json);
            if (asset != null && asset.assetId != null) {
                ASSETS.put(asset.assetId, asset);
            }
        } catch (Exception e) {
            com.formacraft.FormacraftMod.LOGGER.warn("Failed to load asset from {}: {}", file, e.getMessage());
        }
    }
    
    private static AssetDefinition parseAsset(Map<?, ?> json) {
        try {
            String assetId = str(json.get("asset_id"));
            String version = str(json.get("version"), "1.0");
            
            List<String> tags = new ArrayList<>();
            if (json.get("tags") instanceof List<?> tagList) {
                for (Object t : tagList) {
                    if (t != null) tags.add(String.valueOf(t).trim());
                }
            }
            
            String category = str(json.get("category"), "FILLER").toUpperCase();
            if (!Arrays.asList("CONNECTOR", "FILLER", "TERMINATOR", "FIXTURE").contains(category)) {
                category = "FILLER";
            }
            
            int[] size = new int[]{1, 1, 1};
            if (json.get("size") instanceof List<?> sizeList && sizeList.size() >= 3) {
                size[0] = intValue(sizeList.get(0), 1);
                size[1] = intValue(sizeList.get(1), 1);
                size[2] = intValue(sizeList.get(2), 1);
            }
            
            String anchor = str(json.get("anchor"), "BOTTOM_CENTER").toUpperCase();
            boolean isFlexible = bool(json.get("is_flexible"), false);
            String generatorClass = str(json.get("generator_class"), null);
            
            Map<String, String> materialVariables = new HashMap<>();
            if (json.get("material_variables") instanceof Map<?, ?> vars) {
                for (Map.Entry<?, ?> entry : vars.entrySet()) {
                    String key = String.valueOf(entry.getKey()).trim();
                    String value = String.valueOf(entry.getValue()).trim();
                    if (!key.isEmpty() && !value.isEmpty()) {
                        materialVariables.put(key, value);
                    }
                }
            }
            
            List<AssetDefinition.AssetBlock> blocks = new ArrayList<>();
            if (json.get("blocks") instanceof List<?> blockList) {
                for (Object b : blockList) {
                    if (b instanceof Map<?, ?>) {
                        AssetDefinition.AssetBlock block = parseBlock((Map<?, ?>) b);
                        if (block != null) blocks.add(block);
                    }
                }
            }
            
            return new AssetDefinition(
                assetId, version, tags, category, size, anchor, isFlexible,
                generatorClass, materialVariables, blocks
            );
        } catch (Exception e) {
            com.formacraft.FormacraftMod.LOGGER.warn("Failed to parse asset: {}", e.getMessage());
            return null;
        }
    }
    
    @SuppressWarnings("unchecked")
    private static AssetDefinition.AssetBlock parseBlock(Map<?, ?> json) {
        try {
            int[] pos = new int[]{0, 0, 0};
            if (json.get("pos") instanceof List<?> posList && posList.size() >= 3) {
                pos[0] = intValue(posList.get(0), 0);
                pos[1] = intValue(posList.get(1), 0);
                pos[2] = intValue(posList.get(2), 0);
            }
            
            String type = str(json.get("type"));
            if (type.isEmpty()) return null;
            
            Map<String, Object> state = new HashMap<>();
            if (json.get("state") instanceof Map<?, ?>) {
                state = (Map<String, Object>) json.get("state");
            } else if (json.get("data") instanceof Map<?, ?>) {
                state = (Map<String, Object>) json.get("data");
            }
            
            return new AssetDefinition.AssetBlock(pos, type, state);
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * 放置预制件到指定位置
     * 
     * @param assetId 预制件 ID
     * @param anchor 锚点位置（世界坐标）
     * @param facing 朝向
     * @param paletteId 调色板 ID（用于材质变量替换）
     * @param world 世界对象
     * @return 生成的方块列表
     */
    public static List<PlannedBlock> placeAsset(
        String assetId,
        BlockPos anchor,
        Direction facing,
        String paletteId,
        ServerWorld world
    ) {
        AssetDefinition asset = ASSETS.get(assetId);
        if (asset == null) {
            return List.of();
        }
        
        List<PlannedBlock> blocks = new ArrayList<>();
        
        // 计算锚点偏移（将预制件的锚点对齐到指定位置）
        int[] anchorOffset = computeAnchorOffset(asset.anchor, asset.size);
        
        for (AssetDefinition.AssetBlock blockDef : asset.blocks) {
            // 计算相对位置
            int dx = blockDef.pos[0] - anchorOffset[0];
            int dy = blockDef.pos[1] - anchorOffset[1];
            int dz = blockDef.pos[2] - anchorOffset[2];
            
            // 根据朝向旋转位置
            int[] rotated = rotatePosition(dx, dy, dz, facing);
            BlockPos pos = anchor.add(rotated[0], rotated[1], rotated[2]);
            
            // 解析方块类型（支持材质变量替换）
            String blockType = blockDef.type;
            BlockState state = null;
            
            if (blockType.startsWith("$")) {
                // 材质变量：通过 materialVariables 映射到调色板语义名称
                String variable = blockType;
                String semanticPart = asset.materialVariables.get(variable);
                if (semanticPart != null && paletteId != null) {
                    state = PaletteResolver.pick(world, paletteId, semanticPart, pos, 0L, null);
                }
            }
            
            if (state == null) {
                // 直接方块 ID
                state = PaletteResolver.stateFromId(world, blockType);
            }
            
            if (state == null) {
                continue; // 跳过无法解析的方块
            }
            
            // 应用方块状态属性（如 facing, axis 等）
            state = applyBlockStateProperties(state, blockDef.state, facing);
            
            blocks.add(new PlannedBlock(pos, state));
        }
        
        return blocks;
    }
    
    /**
     * 计算锚点偏移（将锚点位置转换为相对坐标原点）
     */
    private static int[] computeAnchorOffset(String anchor, int[] size) {
        int w = size[0];
        int h = size[1];
        int d = size[2];
        
        return switch (anchor.toUpperCase()) {
            case "BOTTOM_CENTER" -> new int[]{w / 2, 0, d / 2};
            case "TOP_CENTER" -> new int[]{w / 2, h - 1, d / 2};
            case "CENTER" -> new int[]{w / 2, h / 2, d / 2};
            case "BOTTOM_LEFT" -> new int[]{0, 0, 0};
            case "TOP_LEFT" -> new int[]{0, h - 1, 0};
            default -> new int[]{w / 2, 0, d / 2}; // 默认 BOTTOM_CENTER
        };
    }
    
    /**
     * 根据朝向旋转位置
     */
    private static int[] rotatePosition(int dx, int dy, int dz, Direction facing) {
        return switch (facing) {
            case NORTH -> new int[]{dx, dy, -dz};
            case SOUTH -> new int[]{-dx, dy, dz};
            case EAST -> new int[]{dz, dy, dx};
            case WEST -> new int[]{-dz, dy, -dx};
            default -> new int[]{dx, dy, dz};
        };
    }
    
    /**
     * 应用方块状态属性
     */
    @SuppressWarnings("unchecked")
    private static BlockState applyBlockStateProperties(BlockState state, Map<String, Object> properties, Direction facing) {
        if (properties == null || properties.isEmpty()) {
            return state;
        }
        
        BlockState result = state;
        
        try {
            // facing 属性（水平朝向）
            Object facingObj = properties.get("facing");
            if (facingObj != null) {
                Direction propFacing = parseDirection(String.valueOf(facingObj));
                if (propFacing != null) {
                    // 根据预制件的朝向旋转
                    Direction rotatedFacing = rotateDirection(propFacing, facing);
                    if (result.contains(Properties.HORIZONTAL_FACING)) {
                        result = result.with(Properties.HORIZONTAL_FACING, rotatedFacing);
                    } else if (result.contains(Properties.FACING)) {
                        result = result.with(Properties.FACING, rotatedFacing);
                    }
                }
            }
            
            // axis 属性（轴）
            Object axisObj = properties.get("axis");
            if (axisObj != null && result.contains(Properties.AXIS)) {
                String axisStr = String.valueOf(axisObj).toUpperCase();
                try {
                    net.minecraft.util.math.Direction.Axis axis = net.minecraft.util.math.Direction.Axis.valueOf(axisStr);
                    result = result.with(Properties.AXIS, axis);
                } catch (IllegalArgumentException ignored) {}
            }
            
            // half 属性（楼梯、台阶的半边）
            Object halfObj = properties.get("half");
            if (halfObj != null && result.contains(Properties.BLOCK_HALF)) {
                String halfStr = String.valueOf(halfObj).toUpperCase();
                try {
                    BlockHalf half = BlockHalf.valueOf(halfStr);
                    result = result.with(Properties.BLOCK_HALF, half);
                } catch (IllegalArgumentException ignored) {}
            }
            
            // type 属性（台阶的类型）
            Object typeObj = properties.get("type");
            if (typeObj != null && result.contains(Properties.SLAB_TYPE)) {
                String typeStr = String.valueOf(typeObj).toUpperCase();
                try {
                    net.minecraft.block.enums.SlabType type = net.minecraft.block.enums.SlabType.valueOf(typeStr);
                    result = result.with(Properties.SLAB_TYPE, type);
                } catch (IllegalArgumentException ignored) {}
            }
        } catch (Exception e) {
            // 如果应用属性失败，返回原状态
            com.formacraft.FormacraftMod.LOGGER.debug("Failed to apply block state properties: {}", e.getMessage());
        }
        
        return result;
    }
    
    private static Direction parseDirection(String str) {
        if (str == null) return null;
        try {
            return Direction.valueOf(str.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
    
    /**
     * 根据朝向旋转方向（简化实现：只在水平面上旋转）
     */
    private static Direction rotateDirection(Direction dir, Direction facing) {
        if (dir == null || facing == null) return dir;
        if (facing == Direction.NORTH) return dir;
        if (facing == Direction.SOUTH) {
            return switch (dir) {
                case NORTH -> Direction.SOUTH;
                case SOUTH -> Direction.NORTH;
                case EAST -> Direction.WEST;
                case WEST -> Direction.EAST;
                default -> dir;
            };
        }
        if (facing == Direction.EAST) {
            return switch (dir) {
                case NORTH -> Direction.EAST;
                case SOUTH -> Direction.WEST;
                case EAST -> Direction.SOUTH;
                case WEST -> Direction.NORTH;
                default -> dir;
            };
        }
        if (facing == Direction.WEST) {
            return switch (dir) {
                case NORTH -> Direction.WEST;
                case SOUTH -> Direction.EAST;
                case EAST -> Direction.NORTH;
                case WEST -> Direction.SOUTH;
                default -> dir;
            };
        }
        return dir;
    }
    
    /**
     * 获取预制件定义
     */
    public static AssetDefinition getAsset(String assetId) {
        return ASSETS.get(assetId);
    }
    
    /**
     * 列出所有已加载的预制件 ID
     */
    public static Set<String> listAssets() {
        return new HashSet<>(ASSETS.keySet());
    }
    
    // 辅助方法
    private static String str(Object o) {
        return o != null ? String.valueOf(o).trim() : "";
    }
    
    private static String str(Object o, String def) {
        String s = str(o);
        return s.isEmpty() ? def : s;
    }
    
    private static int intValue(Object o, int def) {
        if (o == null) return def;
        if (o instanceof Number) return ((Number) o).intValue();
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return def;
        }
    }
    
    private static boolean bool(Object o, boolean def) {
        if (o == null) return def;
        if (o instanceof Boolean) return (Boolean) o;
        return Boolean.parseBoolean(String.valueOf(o));
    }
}

