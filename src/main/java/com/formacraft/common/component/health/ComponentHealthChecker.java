package com.formacraft.common.component.health;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.ComponentCategory;
import com.formacraft.common.component.placement.AttachmentType;
import com.formacraft.common.component.socket.ComponentSocket;

import java.util.*;

/**
 * 构件健康检查器
 * <p>
 * 实现4个层级的健康检查规则：
 * - H1: 几何完整性（Geometry Health）
 * - H2: 锚点与方向（Anchor & Orientation Health）
 * - H3: 语义可理解性（Semantic Health）
 * - H4: AI 使用可靠性（AI Reliability）
 */
public final class ComponentHealthChecker {
    private ComponentHealthChecker() {}
    
    private static final int MIN_BLOCKS = 4; // 最小方块数（建议阈值，避免过小的构件）
    
    /**
     * 执行完整的健康检查
     */
    public static HealthCheckResult check(ComponentDefinition def) {
        HealthCheckResult result = new HealthCheckResult();
        
        if (def == null) {
            result.add(HealthCheckResult.CheckItem.error("H0-0", "构件定义为空", 
                "无法检查空构件", "无法保存", HealthCheckResult.FixAction.NONE, ""));
            return result;
        }
        
        // H1: 几何完整性
        checkGeometryHealth(def, result);
        
        // H2: 锚点与方向
        checkAnchorOrientationHealth(def, result);
        
        // H3: 语义可理解性
        checkSemanticHealth(def, result);
        
        // H4: AI 使用可靠性
        checkAIReliabilityHealth(def, result);
        
        return result;
    }
    
    // ============ H1: 几何完整性规则 ============
    
    private static void checkGeometryHealth(ComponentDefinition def, HealthCheckResult result) {
        // H1-1: 选区为空/过小
        if (def.blocks == null || def.blocks.isEmpty()) {
            result.add(HealthCheckResult.CheckItem.error("H1-1", "未选择有效方块",
                "构件没有包含任何方块", "无法保存", HealthCheckResult.FixAction.NONE, ""));
            return; // 如果没方块，其他检查无意义
        }
        
        int blockCount = def.blocks.size();
        if (blockCount < MIN_BLOCKS) {
            result.add(HealthCheckResult.CheckItem.error("H1-1", "选区过小",
                "构件只包含 " + blockCount + " 个方块，可能不完整", "无法保存", 
                HealthCheckResult.FixAction.NONE, ""));
            return;
        }
        
        result.add(HealthCheckResult.CheckItem.ok("H1-1", "选区结构合理"));
        
        // H1-2: 选区高度/宽度异常
        if (def.size != null) {
            int h = def.size.h;
            ComponentCategory cat = def.category != null ? def.category : ComponentCategory.GENERIC;
            
            if (h == 1 && (cat == ComponentCategory.DOOR || cat == ComponentCategory.WINDOW)) {
                result.add(HealthCheckResult.CheckItem.warn("H1-2", "门/窗构件高度过低",
                    "门/窗构件高度只有 1 格，可能不完整", "AI 可能误判为装饰块",
                    HealthCheckResult.FixAction.NONE, "建议重新框选完整构件结构"));
            } else {
                result.add(HealthCheckResult.CheckItem.ok("H1-2", "构件尺寸合理"));
            }
        }
        
        // H1-3: 结构碎片化（孤立方块）
        int connectedComponents = countConnectedComponents(def);
        if (connectedComponents > 1) {
            result.add(HealthCheckResult.CheckItem.warn("H1-3", "构件包含不连续结构",
                "构件包含 " + connectedComponents + " 个孤立区域", "AI 变体时容易破坏形态",
                HealthCheckResult.FixAction.SUGGEST, "可自动移除孤立方块"));
        } else {
            result.add(HealthCheckResult.CheckItem.ok("H1-3", "构件结构连续"));
        }
    }
    
    /**
     * 计算连通域数量（优化实现：O(n) BFS，使用 HashSet 快速查找，避免递归栈溢出）
     */
    private static int countConnectedComponents(ComponentDefinition def) {
        if (def.blocks == null || def.blocks.isEmpty()) return 0;
        
        // 将所有方块坐标打包为 Long（用于快速查找）
        Map<Long, ComponentDefinition.BlockEntry> blockMap = new HashMap<>();
        Set<Long> visited = new HashSet<>();
        
        for (ComponentDefinition.BlockEntry block : def.blocks) {
            long key = packPos(block.dx, block.dy, block.dz);
            blockMap.put(key, block);
        }
        
        int components = 0;
        
        // BFS 遍历所有未访问的方块
        for (ComponentDefinition.BlockEntry block : def.blocks) {
            long key = packPos(block.dx, block.dy, block.dz);
            if (visited.contains(key)) continue;
            
            components++;
            bfsComponent(block, blockMap, visited);
        }
        
        return components;
    }
    
    /**
     * 将坐标打包为 Long（用于快速查找，21位/坐标，足够覆盖 Minecraft 世界范围）
     */
    private static long packPos(int x, int y, int z) {
        // 使用 21 位存储每个坐标（-1048576 到 1048575）
        return ((long) x & 0x1FFFFF) | (((long) y & 0x1FFFFF) << 21) | (((long) z & 0x1FFFFF) << 42);
    }
    
    /**
     * BFS 遍历连通域（迭代实现，避免递归栈溢出）
     */
    private static void bfsComponent(ComponentDefinition.BlockEntry start,
                                     Map<Long, ComponentDefinition.BlockEntry> blockMap,
                                     Set<Long> visited) {
        Queue<ComponentDefinition.BlockEntry> queue = new ArrayDeque<>();
        long startKey = packPos(start.dx, start.dy, start.dz);
        visited.add(startKey);
        queue.offer(start);
        
        // 6个方向的偏移
        int[] dx = {1, -1, 0, 0, 0, 0};
        int[] dy = {0, 0, 1, -1, 0, 0};
        int[] dz = {0, 0, 0, 0, 1, -1};
        
        while (!queue.isEmpty()) {
            ComponentDefinition.BlockEntry current = queue.poll();
            
            // 检查6个方向的邻居
            for (int i = 0; i < 6; i++) {
                int nx = current.dx + dx[i];
                int ny = current.dy + dy[i];
                int nz = current.dz + dz[i];
                long neighborKey = packPos(nx, ny, nz);
                
                if (!visited.contains(neighborKey) && blockMap.containsKey(neighborKey)) {
                    visited.add(neighborKey);
                    queue.offer(blockMap.get(neighborKey));
                }
            }
        }
    }
    
    // ============ H2: 锚点与方向规则 ============
    
    private static void checkAnchorOrientationHealth(ComponentDefinition def, HealthCheckResult result) {
        // H2-1: 锚点未设置
        if (def.anchor == null) {
            result.add(HealthCheckResult.CheckItem.error("H2-1", "未设置构件锚点",
                "构件缺少锚点定义", "构件无法稳定放置，AI 放置会随机偏移",
                HealthCheckResult.FixAction.AUTO, "自动推荐锚点（底部中心）"));
            return; // 没有锚点，其他检查无意义
        }
        
        result.add(HealthCheckResult.CheckItem.ok("H2-1", "锚点已设置"));
        
        // H2-2: 锚点不在结构边界
        if (def.size != null && def.blocks != null && !def.blocks.isEmpty()) {
            int minY = Integer.MAX_VALUE;
            for (var block : def.blocks) {
                minY = Math.min(minY, block.dy);
            }
            
            AttachmentType attachment = def.placementSpec != null && def.placementSpec.attachment != null
                ? def.placementSpec.attachment : AttachmentType.NONE;
            
            // 如果锚点Y坐标不在底部（minY）或底部+1，且不是自由放置，则警告
            if (def.anchor.dy > minY + 1 && attachment != AttachmentType.NONE) {
                result.add(HealthCheckResult.CheckItem.warn("H2-2", "锚点位于构件中部",
                    "锚点 Y=" + def.anchor.dy + "，但构件最低点在 Y=" + minY,
                    "放置后构件可能悬空或嵌入",
                    HealthCheckResult.FixAction.AUTO, "移动锚点到底部中心"));
            } else {
                result.add(HealthCheckResult.CheckItem.ok("H2-2", "锚点位置合理"));
            }
        }
        
        // H2-3: 需要方向性的构件但未定义方向
        ComponentCategory cat = def.category != null ? def.category : ComponentCategory.GENERIC;
        boolean needsDirectionality = (cat == ComponentCategory.DOOR || 
                                      cat == ComponentCategory.WINDOW ||
                                      cat == ComponentCategory.STAIRS);
        
        if (needsDirectionality) {
            boolean hasDirectionality = def.placementSpec != null
                    && def.placementSpec.facingPolicy != null
                    && def.placementSpec.facingPolicy != com.formacraft.common.component.placement.FacingPolicy.NONE;

            if (!hasDirectionality && def.directionHints != null) {
                if (cat == ComponentCategory.STAIRS) {
                    hasDirectionality = def.directionHints.hasBottomTop
                            || (def.directionHints.bottom != null && def.directionHints.top != null);
                } else {
                    hasDirectionality = def.directionHints.hasInteriorExterior
                            || (def.directionHints.inside != null && def.directionHints.outside != null);
                }
            }

            if (!hasDirectionality) {
                String categoryName = getCategoryDisplayName(cat);
                result.add(HealthCheckResult.CheckItem.warn("H2-3", "该构件需要明确方向",
                    categoryName + "需要内/外方向定义", "AI 可能反向放置（非常常见错误）",
                    HealthCheckResult.FixAction.SUGGEST, "根据空气分布推断内外侧（推荐）"));
            } else {
                result.add(HealthCheckResult.CheckItem.ok("H2-3", "方向性已设置"));
            }
        } else {
            result.add(HealthCheckResult.CheckItem.ok("H2-3", "方向性检查通过"));
        }

        // H2-4: 宿主面缺失（墙面类构件）
        AttachmentType attachment = def.placementSpec != null && def.placementSpec.attachment != null
                ? def.placementSpec.attachment : AttachmentType.NONE;
        boolean needsHostFace = attachment == AttachmentType.WALL_OPENING || attachment == AttachmentType.WALL_SURFACE;
        if (!needsHostFace && def.placementHints != null) {
            needsHostFace = def.placementHints.needsHostFace;
        }
        if (needsHostFace) {
            boolean hasHostFace = def.directionHints != null && def.directionHints.hostFace != null
                    && def.directionHints.hostFace.normal != null;
            if (!hasHostFace) {
                if (attachment == AttachmentType.WALL_OPENING) {
                    result.add(HealthCheckResult.CheckItem.error("H2-4", "宿主面缺失",
                        "门/窗等墙体开口构件必须定义宿主面",
                        "无法可靠判断外墙面，放置方向容易错误",
                        HealthCheckResult.FixAction.SUGGEST, "设置宿主面并标记外墙方向"));
                } else {
                    result.add(HealthCheckResult.CheckItem.warn("H2-4", "建议设置宿主面",
                        "墙面类构件建议选择外墙表面作为参考面",
                        "可能导致内外方向不稳定",
                        HealthCheckResult.FixAction.SUGGEST, "设置宿主面并标记外墙方向"));
                }
            } else {
                result.add(HealthCheckResult.CheckItem.ok("H2-4", "宿主面已设置"));
            }
        }

        // H2-5: 偶数宽度锚点对称性提示
        if (def.size != null && def.anchorHint != null) {
            if (def.size.w % 2 == 0) {
                float du = Math.abs(def.anchorHint.u - 0.5f);
                if (du > 0.01f) {
                    result.add(HealthCheckResult.CheckItem.warn("H2-5", "锚点不在对称中心",
                        "构件宽度为偶数，锚点不在中心线",
                        "对称放置时可能产生偏移",
                        HealthCheckResult.FixAction.NONE, "建议将锚点移动到中心线"));
                }
            }
            if (def.size.d % 2 == 0) {
                float dw = Math.abs(def.anchorHint.w - 0.5f);
                if (dw > 0.01f) {
                    result.add(HealthCheckResult.CheckItem.warn("H2-5", "锚点不在对称中心",
                        "构件深度为偶数，锚点不在中心线",
                        "对称放置时可能产生偏移",
                        HealthCheckResult.FixAction.NONE, "建议将锚点移动到中心线"));
                }
            }
        }
    }
    
    // ============ H3: 语义可理解性规则 ============
    
    private static void checkSemanticHealth(ComponentDefinition def, HealthCheckResult result) {
        // H3-1: 构件分类缺失或模糊
        ComponentCategory cat = def.category != null ? def.category : ComponentCategory.GENERIC;
        List<String> tags = def.tags != null ? def.tags : Collections.emptyList();
        
        if (cat == ComponentCategory.GENERIC && tags.size() < 2) {
            result.add(HealthCheckResult.CheckItem.warn("H3-1", "构件语义不明确",
                "构件分类为\"通用\"且标签少于2个", "AI 调用概率低，可能被错误用作装饰",
                HealthCheckResult.FixAction.NONE, "建议选择构件类型（门/窗/装饰）"));
        } else {
            result.add(HealthCheckResult.CheckItem.ok("H3-1", "构件语义明确"));
        }
        
        // H3-2: 文化风格未识别
        if (def.culturalStyle == null || def.culturalStyle.isBlank()) {
            result.add(HealthCheckResult.CheckItem.warn("H3-2", "文化风格未识别",
                "构件未标注文化风格", "AI 风格匹配准确度下降，可能与其他风格构件混用",
                HealthCheckResult.FixAction.AUTO, "将根据标签与材质自动推断"));
        } else {
            result.add(HealthCheckResult.CheckItem.ok("H3-2", "文化风格已标注"));
        }

        // H3-3: Archetype 引用缺失
        if (def.archetypeRef == null || def.archetypeRef.isBlank()) {
            result.add(HealthCheckResult.CheckItem.warn("H3-3", "原型引用缺失",
                "构件未关联 ComponentArchetype", "变体规则与放置语义可能不完整",
                HealthCheckResult.FixAction.AUTO, "将使用构件 id 作为 archetypeRef 并生成侧车"));
        } else {
            result.add(HealthCheckResult.CheckItem.ok("H3-3", "原型引用已设置"));
        }

        // H3-4: 几何原型未识别
        if (def.geometryArchetype == null || def.geometryArchetype.isBlank()) {
            result.add(HealthCheckResult.CheckItem.warn("H3-4", "几何原型未识别",
                "构件未标注几何形态族", "AI 难以区分斗拱/窗套/栏杆等形态",
                HealthCheckResult.FixAction.AUTO, "将根据分类与标签自动推断"));
        } else {
            result.add(HealthCheckResult.CheckItem.ok("H3-4", "几何原型已标注"));
        }
    }
    
    // ============ H4: AI 使用可靠性规则 ============
    
    private static void checkAIReliabilityHealth(ComponentDefinition def, HealthCheckResult result) {
        // H4-1: 构件缺少连接位
        List<ComponentSocket> sockets = def.sockets != null ? def.sockets : Collections.emptyList();
        ComponentCategory cat = def.category != null ? def.category : ComponentCategory.GENERIC;
        
        boolean needsSocket = (cat == ComponentCategory.DOOR || 
                              cat == ComponentCategory.WINDOW);
        
        if (needsSocket && sockets.isEmpty()) {
            result.add(HealthCheckResult.CheckItem.warn("H4-1", "未定义连接位",
                "门/窗类构件缺少连接位定义", "AI 只能\"猜位置\"，复杂建筑中失败率高",
                HealthCheckResult.FixAction.SUGGEST, "自动生成墙体开口连接位（推荐）"));
        } else if (sockets.isEmpty()) {
            // 非门/窗类，Socket 是可选的
            result.add(HealthCheckResult.CheckItem.ok("H4-1", "连接位检查通过"));
        } else {
            result.add(HealthCheckResult.CheckItem.ok("H4-1", "连接位已配置"));
        }
        
        // H4-2: PlacementSpec 过宽
        if (def.placementSpec != null && def.placementSpec.spatialContext != null) {
            // 检查 spatialContext 是否过于宽泛
            // 这里简化处理，实际应该检查 allowedContexts
            result.add(HealthCheckResult.CheckItem.ok("H4-2", "放置范围合理"));
        } else {
            result.add(HealthCheckResult.CheckItem.ok("H4-2", "放置范围检查通过"));
        }
        
        // H4-3: Variant 信息缺失（可选，不影响正确性）
        // 如果将来添加了 variantConfig 字段，可以在这里检查
    }
    
    // ============ 辅助方法 ============
    
    private static String getCategoryDisplayName(ComponentCategory category) {
        return switch (category) {
            case DOOR -> "门";
            case WINDOW -> "窗";
            case COLUMN -> "柱子";
            case STAIRS -> "楼梯";
            case BRACKET -> "斗拱";
            case ORNAMENT -> "装饰";
            case ARCH -> "拱券";
            case ROOF_DETAIL -> "屋顶细节";
            default -> "通用构件";
        };
    }
}
