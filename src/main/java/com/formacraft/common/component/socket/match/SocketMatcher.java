package com.formacraft.common.component.socket.match;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.placement.ComponentPlacementSpec;
import com.formacraft.common.component.placement.AttachmentType;
import com.formacraft.common.component.placement.SpatialContext;
import com.formacraft.common.component.placement.FacingPolicy;
import com.formacraft.common.component.socket.Socket;
import com.formacraft.common.component.socket.SocketType;

import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * SocketMatcher（Socket 匹配器 v1）：核心匹配逻辑。
 * <p>
 * 这是 v1 的"心脏"。
 * <p>
 * 在一堆 Socket 中，找出"最适合当前 Component（或 AI 需求）"的那些 Socket，并给出可解释的评分。
 * <p>
 * 这一步一旦完成，门/窗/栏杆/柱子/装饰都会"自动插对位置"，这是 Formacraft 从"能用"到"像专业建筑师"的关键跃迁。
 */
public final class SocketMatcher {
    /** v1 权重（以后可以做成 config / style profile） */
    private static final double W_TYPE     = 5.0;
    private static final double W_SIZE     = 3.0;
    private static final double W_FACING   = 2.0;
    private static final double W_CONTEXT  = 2.0;
    private static final double W_DISTANCE = 1.0;

    private SocketMatcher() {}

    /**
     * 匹配 Socket 列表与 ComponentPlacementSpec
     * 
     * @param sockets Socket 列表
     * @param spec 构件放置规格
     * @param focus 焦点位置（可选，用于距离评分）
     * @return 排好序的 MatchResult 列表（合法优先，其次按总分降序）
     */
    public static List<SocketMatchResult> match(
            List<Socket> sockets,
            ComponentPlacementSpec spec,
            Vec3d focus
    ) {
        if (sockets == null || spec == null) {
            return List.of();
        }

        List<SocketMatchResult> results = new ArrayList<>();

        for (Socket socket : sockets) {
            results.add(matchOne(socket, spec, focus));
        }

        // 排序：合法优先，其次 total score
        results.sort((a, b) -> {
            if (a.valid != b.valid) return a.valid ? -1 : 1;
            return Double.compare(b.score.total(), a.score.total());
        });

        return results;
    }

    /**
     * 匹配 Socket 列表与 ComponentDefinition
     * 
     * @param sockets Socket 列表
     * @param component 构件定义
     * @param focus 焦点位置（可选，用于距离评分）
     * @return 排好序的 MatchResult 列表
     */
    public static List<SocketMatchResult> match(
            List<Socket> sockets,
            ComponentDefinition component,
            Vec3d focus
    ) {
        if (component == null || component.placementSpec == null) {
            return List.of();
        }

        return match(sockets, component.placementSpec, focus);
    }

    /**
     * 匹配单个 Socket
     */
    private static SocketMatchResult matchOne(
            Socket socket,
            ComponentPlacementSpec spec,
            Vec3d focus
    ) {
        EnumSet<SocketMatchReason> reasons = EnumSet.noneOf(SocketMatchReason.class);
        SocketMatchScore score = SocketMatchScore.zero();

        // 1️⃣ Occupied
        if (socket.occupied) {
            reasons.add(SocketMatchReason.OCCUPIED);
            return invalid(socket, score, reasons);
        }

        // 2️⃣ SocketType × AttachmentType / allowedSockets
        if (!typeCompatible(socket.type, spec)) {
            reasons.add(SocketMatchReason.TYPE_MISMATCH);
            return invalid(socket, score, reasons);
        }
        score.typeScore = W_TYPE;

        // 3️⃣ Context（Interior / Exterior / Edge）
        if (!contextCompatible(socket, spec)) {
            reasons.add(SocketMatchReason.CONTEXT_MISMATCH);
            return invalid(socket, score, reasons);
        }
        score.contextScore = W_CONTEXT;

        // 4️⃣ FacingPolicy
        if (!facingCompatible(socket.normal, spec.facingPolicy)) {
            reasons.add(SocketMatchReason.FACING_NOT_ALLOWED);
            return invalid(socket, score, reasons);
        }
        score.facingScore = W_FACING;

        // 5️⃣ Size（粗略：用 bounds）
        double sizeScore = sizeScore(socket, spec);
        if (sizeScore <= 0) {
            reasons.add(SocketMatchReason.SIZE_TOO_SMALL);
            return invalid(socket, score, reasons);
        }
        score.sizeScore = sizeScore * W_SIZE;

        // 6️⃣ Distance（越接近 focus 越好）
        if (focus != null) {
            double d = socket.center().distanceTo(focus);
            score.distanceScore = W_DISTANCE * (1.0 / (1.0 + d));
        }

        reasons.add(SocketMatchReason.OK);
        return new SocketMatchResult(socket, true, score, reasons);
    }

    // ---------------------------------------------------------------------

    /**
     * 检查 Socket 类型是否兼容
     */
    private static boolean typeCompatible(SocketType socketType, ComponentPlacementSpec spec) {
        // 优先使用 allowedSockets（如果已设置）
        if (spec.allowedSockets != null && !spec.allowedSockets.isEmpty()) {
            return spec.allowedSockets.contains(socketType);
        }

        // 否则从 AttachmentType 推断
        return attachmentCompatible(socketType, spec.attachment);
    }

    /**
     * 检查 AttachmentType 是否兼容
     */
    private static boolean attachmentCompatible(SocketType socketType, AttachmentType attachment) {
        if (attachment == null) {
            return true; // 没有 attachment 要求，允许所有类型
        }

        return switch (attachment) {
            case WALL_SURFACE -> socketType == SocketType.WALL_SURFACE;
            case WALL_OPENING -> socketType == SocketType.WALL_OPENING;
            case EDGE -> socketType == SocketType.EDGE_OUTER;
            case ROOF_SURFACE -> socketType == SocketType.ROOF_SLOPE;
            case ROOF_EDGE -> socketType == SocketType.ROOF_RIDGE;
            case ROOF_RIDGE -> socketType == SocketType.ROOF_RIDGE;
            case FLOOR -> socketType == SocketType.FLOOR_SURFACE;
            case CORNER -> socketType == SocketType.EDGE_OUTER;
            case NONE -> socketType == SocketType.FREE_ATTACH || socketType == SocketType.FLOOR_SURFACE;
        };
    }

    /**
     * 检查上下文是否兼容
     */
    private static boolean contextCompatible(Socket socket, ComponentPlacementSpec spec) {
        // 检查 requireExterior
        if (spec.requireExterior) {
            if (!socket.isExterior()) {
                return false;
            }
        }

        // 检查 forbidInterior
        if (spec.constraints != null && spec.constraints.forbidInterior) {
            if (!socket.isExterior()) {
                return false;
            }
        }

        // 检查 SpatialContext
        if (spec.spatialContext != null && spec.spatialContext != SpatialContext.ANY) {
            boolean isExterior = socket.isExterior();
            if (spec.spatialContext == SpatialContext.EXTERIOR && !isExterior) {
                return false;
            }
            if (spec.spatialContext == SpatialContext.INTERIOR && isExterior) {
                return false;
            }
        }

        // 检查 requiresOpening
        if (spec.requiresOpening && socket.type != SocketType.WALL_OPENING) {
            return false;
        }

        // 检查 requireEdge
        if (spec.requireEdge && socket.type != SocketType.EDGE_OUTER) {
            return false;
        }

        return true;
    }

    /**
     * 检查 FacingPolicy 是否兼容
     */
    private static boolean facingCompatible(Direction socketNormal, FacingPolicy policy) {
        if (policy == null || policy == FacingPolicy.NONE) {
            return true; // 没有 facing 要求
        }

        if (socketNormal == null) {
            return false; // Socket 必须有 normal
        }

        return switch (policy) {
            case NONE, USER_DEFINED -> true; // 用户定义，允许
            case DERIVED_FROM_HOST, OUTWARD_NORMAL -> socketNormal != null; // 需要 normal
            case ALONG_EDGE -> socketNormal != null; // 需要 normal
        };
    }

    /**
     * 计算尺寸评分
     */
    private static double sizeScore(Socket socket, ComponentPlacementSpec spec) {
        double w = socket.bounds.getXLength();
        double h = socket.bounds.getYLength();
        double d = socket.bounds.getZLength();

        // v1：只看最小包围尺寸
        // 如果 spec 有 constraints，使用 constraints 的 minHeight / maxHeight
        if (spec.constraints != null) {
            if (spec.constraints.minHeight != null && h < spec.constraints.minHeight) {
                return 0.0; // 高度太小
            }
            if (spec.constraints.maxHeight != null && h > spec.constraints.maxHeight * 2) {
                return 0.5; // 太大，允许但降分
            }
        }

        // v1：简化处理，只要尺寸合理就给满分
        // 未来可以根据构件的实际尺寸（ComponentDefinition.size）进行更精确的匹配
        return 1.0;
    }

    /**
     * 创建无效的匹配结果
     */
    private static SocketMatchResult invalid(Socket socket,
                                             SocketMatchScore score,
                                             EnumSet<SocketMatchReason> reasons) {
        return new SocketMatchResult(socket, false, score, reasons);
    }
}
