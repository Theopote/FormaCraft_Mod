package com.formacraft.common.mass.derived;

import com.formacraft.common.mass.BuildingMassComposition;
import com.formacraft.common.mass.MassFilledChecker;
import com.formacraft.common.mass.MassRole;
import net.minecraft.util.math.BlockPos;

/**
 * SocketRefinementRules（Socket 细化规则）
 * <p>
 * 🎯 核心定位（架构校准 2026-01-14）：
 * Socket ≠ 洞
 * Socket = "允许发生变化的位置"
 * <p>
 * Socket 不直接破坏体量
 * Socket 只是给后续：Component、Block rule、AI decision 提供"入口"
 * <p>
 * 三大类 Socket 细化规则：
 * 1. DOOR Socket（通行）
 * 2. WINDOW Socket（采光 / 立面节奏）
 * 3. BALCONY Socket（体量外扩）
 */
public final class SocketRefinementRules {

    private SocketRefinementRules() {}

    /**
     * DOOR Socket 派生规则
     * <p>
     * Door Socket = 允许"人 / 实体 / 空间"穿过体量边界的位置
     * <p>
     * 派生来源（必须满足其一）：
     * 1. Interface Skeleton（首选）
     * 2. Exterior Skeleton（受限）
     */
    public static class DoorRules {
        /**
         * 检查是否可以派生 DOOR Socket（Interface）
         * <p>
         * 判定条件：
         * - skeleton.context == INTERIOR / CONNECTION
         * - y == baseFloorY + 1（地面层）
         * - 连续宽度 ≥ 2 block
         * - 上方至少 2 block 高度
         */
        public static boolean canCreateDoorAt(
                MassDerivedSkeleton skeleton,
                BlockPos pos,
                int baseFloorY
        ) {
            if (skeleton.context != MassDerivedSkeleton.SkeletonContext.INTERIOR &&
                skeleton.context != MassDerivedSkeleton.SkeletonContext.CONNECTION) {
                return false;
            }

            // 检查是否在地面层
            if (pos.getY() != baseFloorY + 1) {
                return false;
            }

            // v1 简化：检查是否在高度范围内
            if (pos.getY() < skeleton.minY || pos.getY() > skeleton.maxY) {
                return false;
            }

            return true;
        }

        /**
         * 检查是否可以派生 DOOR Socket（Exterior，受限）
         * <p>
         * Exterior Door 只能生成在：
         * - PRIMARY mass
         * - 且位于 Plan 的"入口侧"（如果有 axis / facing）
         * <p>
         * v1 简化：只检查是否是 PRIMARY 相关的 Skeleton
         */
        public static boolean canCreateExteriorDoorAt(
                MassDerivedSkeleton skeleton,
                BlockPos pos,
                BuildingMassComposition composition
        ) {
            if (skeleton.context != MassDerivedSkeleton.SkeletonContext.EXTERIOR) {
                return false;
            }

            // v1 简化：Exterior Door 需要特殊标记或检查
            // 未来：需要检查是否是入口侧
            return false; // v1 默认不允许 Exterior Door，除非明确标记
        }

        /**
         * 获取 Door Socket 的默认尺寸
         */
        public static DoorSize getDefaultSize(boolean isMainDoor) {
            return isMainDoor
                    ? new DoorSize(3, 4) // 大门
                    : new DoorSize(2, 3); // 普通门
        }

        public record DoorSize(int width, int height) {}
    }

    /**
     * WINDOW Socket 派生规则
     * <p>
     * Window Socket = 允许"光 / 视线 / 节奏"穿过外立面的地方
     * <p>
     * 派生来源（只能来自）：Exterior Skeleton
     */
    public static class WindowRules {
        /**
         * 检查是否可以派生 WINDOW Socket
         * <p>
         * 基本判定：
         * - skeleton.context == EXTERIOR
         * - y >= baseFloorY + 2（不在底层）
         * - y <= topFloorY - 1（不在顶层）
         * - notAtCorner（不在角落）
         */
        public static boolean canCreateWindowAt(
                MassDerivedSkeleton skeleton,
                BlockPos pos,
                int baseFloorY,
                int topFloorY
        ) {
            if (skeleton.context != MassDerivedSkeleton.SkeletonContext.EXTERIOR) {
                return false;
            }

            // 检查高度范围（不在底层和顶层）
            int y = pos.getY();
            if (y < baseFloorY + 2 || y > topFloorY - 1) {
                return false;
            }

            // v1 简化：暂不检查是否在角落
            // 未来：需要检查相邻位置是否为空

            return true;
        }

        /**
         * 检查是否满足水平节奏规则
         * <p>
         * 窗不是"能开就开"，而是"按节奏开"
         * <p>
         * v1 推荐：every N blocks → one window
         * spacing = mass.role == PRIMARY ? 3 : 4;
         */
        public static boolean matchesWindowSpacing(
                BlockPos pos,
                MassRole massRole,
                int spacingOffset
        ) {
            int spacing = massRole == MassRole.PRIMARY ? 3 : 4;
            // v1 简化：使用 X+Z 坐标和来模拟节奏
            int coordinateSum = pos.getX() + pos.getZ();
            return (coordinateSum + spacingOffset) % spacing == 0;
        }

        /**
         * 获取 Window Socket 的默认尺寸
         */
        public static WindowSize getDefaultSize(boolean isLarge) {
            return isLarge
                    ? new WindowSize(2, 2) // 大窗
                    : new WindowSize(1, 2); // 普通窗
        }

        public record WindowSize(int width, int height) {}
    }

    /**
     * BALCONY Socket 派生规则
     * <p>
     * Balcony Socket = 允许"体量向外延伸"的接口
     * <p>
     * ⚠️ 它不是窗，也不是门
     * ⚠️ 它一定和 CANTILEVER 或悬空条件绑定
     */
    public static class BalconyRules {
        /**
         * 检查是否可以派生 BALCONY Socket
         * <p>
         * 派生来源（必须同时满足）：
         * 1. Exterior Skeleton
         * 2. Skeleton 下方为空（悬空）
         * 3. 对应的 BuildingMass.role == CANTILEVER 或 SECONDARY
         */
        public static boolean canCreateBalconyAt(
                MassDerivedSkeleton skeleton,
                BlockPos pos,
                BuildingMassComposition composition,
                MassRole massRole
        ) {
            if (skeleton.context != MassDerivedSkeleton.SkeletonContext.EXTERIOR) {
                return false;
            }

            // 检查 Mass Role
            if (massRole != MassRole.CANTILEVER && massRole != MassRole.SECONDARY) {
                return false;
            }

            // 检查下方是否为空（悬空）
            BlockPos below = pos.down();
            boolean belowIsAir = !MassFilledChecker.isFilled(composition, below.getX(), below.getY(), below.getZ());

            return belowIsAir;
        }

        /**
         * 检查阳台深度是否在限制内
         * <p>
         * balconyDepth <= cantilever.maxOverhang
         */
        public static boolean isBalconyDepthValid(int balconyDepth, int maxOverhang) {
            return balconyDepth <= maxOverhang;
        }

        /**
         * 获取 Balcony Socket 的默认尺寸
         */
        public static BalconySize getDefaultSize() {
            return new BalconySize(2, 1, 1); // width, depth, height
        }

        public record BalconySize(int width, int depth, int height) {}
    }

    /**
     * Socket 冲突与优先级规则
     * <p>
     * 推荐优先级（v1）：
     * DOOR > BALCONY > WINDOW
     * <p>
     * 也就是说：
     * - 有门 → 不再生成窗
     * - 有阳台 → 窗转为阳台门
     * - 窗是最低优先级
     */
    public enum SocketPriority {
        /** 最高优先级 */
        DOOR,
        /** 中等优先级 */
        BALCONY,
        /** 最低优先级 */
        WINDOW
    }

    /**
     * 根据优先级选择 Socket 类型
     * <p>
     * 如果同一位置满足多个条件，返回优先级最高的
     */
    public static SocketPriority selectSocketType(
            boolean canCreateDoor,
            boolean canCreateBalcony,
            boolean canCreateWindow
    ) {
        if (canCreateDoor) {
            return SocketPriority.DOOR;
        }
        if (canCreateBalcony) {
            return SocketPriority.BALCONY;
        }
        if (canCreateWindow) {
            return SocketPriority.WINDOW;
        }
        return null; // 不创建 Socket
    }
}
