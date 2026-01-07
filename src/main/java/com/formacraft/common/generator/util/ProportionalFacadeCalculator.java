package com.formacraft.common.generator.util;

/**
 * ProportionalFacadeCalculator（比例化立面计算器）
 * 
 * 核心职责：
 * 1. 根据建筑体量、整体尺寸计算合理的立面进退
 * 2. 确保比例协调，符合建筑美学
 * 3. 支持用户自定义要求
 * 
 * 设计原则：
 * - 一个方块 = 1米
 * - 每层建筑最小高度 = 3米（3格）
 * - 立面进退基于比例，而非硬编码
 */
public final class ProportionalFacadeCalculator {

    private ProportionalFacadeCalculator() {}

    /**
     * 立面层配置
     */
    public static class LayerConfig {
        public final int width;
        public final int depth;
        public final int xOffset;
        public final int zOffset;
        public final int floorIndex; // 楼层索引（从0开始）

        public LayerConfig(int width, int depth, int xOffset, int zOffset, int floorIndex) {
            this.width = width;
            this.depth = depth;
            this.xOffset = xOffset;
            this.zOffset = zOffset;
            this.floorIndex = floorIndex;
        }
    }

    /**
     * 计算阶梯式立面配置
     * 
     * @param totalWidth 总宽度（米/格）
     * @param totalDepth 总深度（米/格）
     * @param totalHeight 总高度（米/格）
     * @param floorHeight 每层高度（米/格），如果为0则自动计算
     * @param userSetbackRatio 用户指定的进退比例（0-1），如果为0则自动计算
     * @return 每层的配置数组
     */
    public static LayerConfig[] calculateSteppedFacade(
            int totalWidth,
            int totalDepth,
            int totalHeight,
            int floorHeight,
            double userSetbackRatio
    ) {
        // 1. 计算合理的每层高度
        int actualFloorHeight = calculateFloorHeight(totalHeight, floorHeight);
        
        // 2. 计算楼层数
        int floorCount = Math.max(1, totalHeight / actualFloorHeight);
        
        // 3. 计算每层的尺寸和偏移
        LayerConfig[] layers = new LayerConfig[totalHeight];
        
        // 4. 计算进退比例
        double setbackRatio = calculateSetbackRatio(
                totalWidth, totalDepth, totalHeight, floorCount, userSetbackRatio
        );
        
        // 5. 为每一格计算配置
        for (int y = 0; y < totalHeight; y++) {
            int floorIndex = y / actualFloorHeight;
            
            // 计算当前楼层的尺寸和偏移
            int floorWidth = calculateFloorWidth(totalWidth, floorIndex, floorCount, setbackRatio);
            int floorDepth = calculateFloorDepth(totalDepth, floorIndex, floorCount, setbackRatio);
            int floorXOffset = calculateFloorOffset(totalWidth, floorWidth);
            int floorZOffset = calculateFloorOffset(totalDepth, floorDepth);
            
            layers[y] = new LayerConfig(floorWidth, floorDepth, floorXOffset, floorZOffset, floorIndex);
        }
        
        return layers;
    }

    /**
     * 计算合理的每层高度
     * 
     * @param totalHeight 总高度
     * @param userFloorHeight 用户指定的每层高度，如果<=0则自动计算
     * @return 每层高度（至少3米）
     */
    public static int calculateFloorHeight(int totalHeight, int userFloorHeight) {
        // 如果用户指定了每层高度，验证并返回
        if (userFloorHeight > 0) {
            // 确保至少3米（3格）
            return Math.max(3, userFloorHeight);
        }
        
        // 自动计算：根据总高度智能分配
        // 原则：每层高度应该在3-5米之间，根据总高度调整
        if (totalHeight <= 6) {
            // 小建筑：每层3米
            return 3;
        } else if (totalHeight <= 12) {
            // 中等建筑：每层3-4米
            return Math.max(3, totalHeight / 3);
        } else if (totalHeight <= 24) {
            // 大建筑：每层4-5米
            return Math.max(4, totalHeight / 5);
        } else {
            // 超大建筑：每层5米
            return 5;
        }
    }

    /**
     * 计算进退比例
     * 
     * @param width 宽度
     * @param depth 深度
     * @param height 高度
     * @param floorCount 楼层数
     * @param userRatio 用户指定的比例，如果<=0则自动计算
     * @return 进退比例（0-1）
     */
    private static double calculateSetbackRatio(
            int width, int depth, int height, int floorCount, double userRatio
    ) {
        // 如果用户指定了比例，直接使用
        if (userRatio > 0 && userRatio <= 1.0) {
            return userRatio;
        }
        
        // 自动计算：基于建筑比例
        // 原则：进退比例应该与建筑的长宽高比例协调
        
        // 计算高度与宽度的比例
        double heightToWidthRatio = height / (double) Math.max(width, depth);
        
        // 3. 根据比例计算进退
        // 高瘦建筑（heightToWidthRatio > 1.5）：较大的进退（0.08-0.12）
        // 矮胖建筑（heightToWidthRatio < 0.8）：较小的进退（0.03-0.06）
        // 正常建筑：中等进退（0.05-0.08）
        
        double baseRatio;
        if (heightToWidthRatio > 1.5) {
            baseRatio = 0.10; // 10% 每层
        } else if (heightToWidthRatio < 0.8) {
            baseRatio = 0.04; // 4% 每层
        } else {
            baseRatio = 0.06; // 6% 每层
        }
        
        // 4. 根据楼层数调整（楼层越多，每层进退可以稍小）
        if (floorCount > 5) {
            baseRatio *= 0.8; // 高层建筑，每层进退稍小
        }
        
        // 5. 限制在合理范围内（3%-12%）
        return Math.max(0.03, Math.min(0.12, baseRatio));
    }

    /**
     * 计算当前楼层的宽度
     */
    private static int calculateFloorWidth(int totalWidth, int floorIndex, int floorCount, double setbackRatio) {
        // 每层缩小：totalWidth * (1 - setbackRatio * floorIndex)
        // 但确保最小宽度至少为3米
        double ratio = 1.0 - (setbackRatio * floorIndex);
        int floorWidth = (int) Math.round(totalWidth * ratio);
        return Math.max(3, floorWidth);
    }

    /**
     * 计算当前楼层的深度
     */
    private static int calculateFloorDepth(int totalDepth, int floorIndex, int floorCount, double setbackRatio) {
        // 每层缩小：totalDepth * (1 - setbackRatio * floorIndex)
        // 但确保最小深度至少为3米
        double ratio = 1.0 - (setbackRatio * floorIndex);
        int floorDepth = (int) Math.round(totalDepth * ratio);
        return Math.max(3, floorDepth);
    }

    /**
     * 计算当前楼层的偏移量（居中）
     */
    private static int calculateFloorOffset(int totalSize, int floorSize) {
        // 居中偏移
        return (totalSize - floorSize) / 2;
    }

    /**
     * 验证尺寸合理性
     * 
     * @param width 宽度
     * @param depth 深度
     * @param height 高度
     * @return 是否合理
     */
    public static boolean validateDimensions(int width, int depth, int height) {
        // 最小尺寸：至少3x3x3米
        if (width < 3 || depth < 3 || height < 3) {
            return false;
        }
        
        // 最大尺寸：避免过大（200米）
        if (width > 200 || depth > 200 || height > 200) {
            return false;
        }
        
        // 高度与宽度的比例：避免过于极端
        double heightToWidthRatio = height / (double) Math.max(width, depth);
        if (heightToWidthRatio > 10.0) {
            // 高度是宽度的10倍以上，可能不合理
            return false;
        }
        
        return true;
    }

    /**
     * 从用户输入中提取每层高度要求
     * 
     * @param features 特征列表
     * @return 每层高度（米/格），如果未指定则返回0
     */
    public static int extractFloorHeightFromFeatures(java.util.List<String> features) {
        if (features == null) return 0;
        
        for (String feature : features) {
            if (feature == null) continue;
            String lower = feature.toLowerCase();
            
            // 匹配 "3米"、"3m"、"floor_height_3" 等格式
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    "(?:floor[_-]?height|层高|每层)[:=]?\\s*(\\d+)\\s*(?:米|m|格)?"
            );
            java.util.regex.Matcher matcher = pattern.matcher(lower);
            if (matcher.find()) {
                try {
                    return Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException e) {
                    // 忽略
                }
            }
            
            // 匹配 "三层"、"3层" 等，然后根据总高度计算每层高度
            pattern = java.util.regex.Pattern.compile("(\\d+)\\s*层");
            matcher = pattern.matcher(lower);
            if (matcher.find()) {
                // 这里需要总高度才能计算，暂时返回0
                // 实际使用时，应该结合总高度来计算
            }
        }
        
        return 0;
    }

    /**
     * 从用户输入中提取进退比例要求
     * 
     * @param features 特征列表
     * @return 进退比例（0-1），如果未指定则返回0
     */
    public static double extractSetbackRatioFromFeatures(java.util.List<String> features) {
        if (features == null) return 0.0;
        
        for (String feature : features) {
            if (feature == null) continue;
            String lower = feature.toLowerCase();
            
            // 匹配 "进退5%"、"setback_0.05" 等格式
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    "(?:setback|进退)[:=]?\\s*(\\d+(?:\\.\\d+)?)\\s*%?"
            );
            java.util.regex.Matcher matcher = pattern.matcher(lower);
            if (matcher.find()) {
                try {
                    double value = Double.parseDouble(matcher.group(1));
                    // 如果是百分比，转换为小数
                    if (value > 1.0) {
                        return value / 100.0;
                    }
                    return Math.max(0.0, Math.min(1.0, value));
                } catch (NumberFormatException e) {
                    // 忽略
                }
            }
        }
        
        return 0.0;
    }
}

