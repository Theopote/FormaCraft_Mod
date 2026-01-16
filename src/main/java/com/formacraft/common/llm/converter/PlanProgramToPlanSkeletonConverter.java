package com.formacraft.common.llm.converter;

import com.formacraft.common.llm.dto.PlanProgram;
import com.formacraft.common.llm.dto.PlanSkeleton;
import com.formacraft.FormacraftMod;

import java.util.*;

/**
 * PlanProgram → PlanSkeleton 转换器
 * <p>
 * 核心职责：将 PlanProgram 的"功能关系"转换为 PlanSkeleton 的"几何语义"
 * <p>
 * 转换逻辑：
 * 1. zones：PlanProgram.zones → PlanSkeleton.zones（添加 boundary, access, connected_to）
 * 2. edges：根据 adjacency 生成 edges（external_wall / shared_wall）
 * 3. courtyards：检测环形 adjacency 形成庭院
 * 4. axes：根据 circulation 生成轴线
 * 5. outline：默认 generated
 * <p>
 * 设计原则：
 * - 保守转换：优先生成合理的默认值，而不是复杂推断
 * - 可扩展：为未来更复杂的转换逻辑预留接口
 * - 可调试：记录转换过程中的决策
 */
public final class PlanProgramToPlanSkeletonConverter {

    private PlanProgramToPlanSkeletonConverter() {}

    /**
     * 转换 PlanProgram 为 PlanSkeleton
     * 
     * @param program PlanProgram（功能关系）
     * @return PlanSkeleton（几何语义）
     */
    public static PlanSkeleton convert(PlanProgram program) {
        if (program == null) {
            throw new IllegalArgumentException("PlanProgram cannot be null");
        }

        // 1. 转换 zones
        List<PlanSkeleton.PlanZone> skeletonZones = convertZones(program);

        // 2. 构建 adjacency 图（用于后续分析）
        Map<String, Set<String>> adjacencyMap = buildAdjacencyMap(program);

        // 3. 生成 edges（根据 adjacency 和 boundary）
        List<PlanSkeleton.Edge> edges = generateEdges(program, skeletonZones, adjacencyMap);

        // 4. 检测 courtyards（环形 adjacency）
        List<PlanSkeleton.Courtyard> courtyards = detectCourtyards(program, adjacencyMap);

        // 5. 生成 axes（根据 circulation）
        List<PlanSkeleton.Axis> axes = generateAxes(program, skeletonZones);

        // 6. 生成 outline（默认 generated）
        PlanSkeleton.Outline outline = generateOutline(program);

        return new PlanSkeleton(
                "formacraft.plan_skeleton.v1",
                outline,
                skeletonZones,
                edges,
                courtyards,
                axes
        );
    }

    /**
     * 转换 zones：PlanProgram.zones → PlanSkeleton.zones
     * <p>
     * 添加字段：
     * - boundary：根据 importance 和 adjacency 决定
     * - access：根据 role 推断
     * - connected_to：从 adjacency 提取
     */
    private static List<PlanSkeleton.PlanZone> convertZones(PlanProgram program) {
        List<PlanSkeleton.PlanZone> result = new ArrayList<>();

        // 构建 adjacency 图（zone id → 连接的 zone ids）
        Map<String, Set<String>> adjacencyMap = buildAdjacencyMap(program);

        // 构建 zone 索引（id → zone）
        Map<String, PlanProgram.PlanZone> zoneMap = new HashMap<>();
        if (program.zones() != null) {
            for (PlanProgram.PlanZone zone : program.zones()) {
                if (zone != null && zone.id() != null) {
                    zoneMap.put(zone.id(), zone);
                }
            }
        }

        // 转换每个 zone
        for (PlanProgram.PlanZone programZone : zoneMap.values()) {
            String zoneId = programZone.id();
            Set<String> connectedIds = adjacencyMap.getOrDefault(zoneId, new HashSet<>());

            // 决定 boundary
            String boundary = determineBoundary(programZone, connectedIds, zoneMap.size());

            // 决定 access
            String access = determineAccess(programZone);

            // 构建 connected_to 列表
            List<String> connectedTo = new ArrayList<>(connectedIds);

            result.add(new PlanSkeleton.PlanZone(
                    zoneId,
                    boundary,
                    access,
                    connectedTo
            ));
        }

        return result;
    }

    /**
     * 决定 zone 的 boundary 类型
     * <p>
     * 规则：
     * - primary zone + 有连接 → external（主要功能块通常有外墙）
     * - secondary zone + 有连接 → external（翼楼通常有外墙）
     * - support zone + 无连接 → internal（服务区可能被包裹）
     * - 其他情况 → external（保守默认）
     */
    private static String determineBoundary(
            PlanProgram.PlanZone zone,
            Set<String> connectedIds,
            int totalZones
    ) {
        String importance = zone.importance();
        if (importance == null) {
            importance = "secondary"; // 默认
        }

        // primary zone 通常有外墙
        if ("primary".equalsIgnoreCase(importance)) {
            return "external";
        }

        // support zone 且无连接 → 可能是内部区域
        if ("support".equalsIgnoreCase(importance) && connectedIds.isEmpty()) {
            return "internal";
        }

        // 其他情况 → external（保守默认）
        return "external";
    }

    /**
     * 决定 zone 的 access 类型
     * <p>
     * 规则：
     * - role 包含 "main" / "hall" / "public" → public
     * - role 包含 "service" / "storage" → private
     * - 其他 → semi_private
     */
    private static String determineAccess(PlanProgram.PlanZone zone) {
        String role = zone.role();
        if (role == null) {
            return "semi_private";
        }

        String roleLower = role.toLowerCase();
        if (roleLower.contains("main") || roleLower.contains("hall") || roleLower.contains("public")) {
            return "public";
        }
        if (roleLower.contains("service") || roleLower.contains("storage") || roleLower.contains("private")) {
            return "private";
        }
        return "semi_private";
    }

    /**
     * 构建 adjacency 图（zone id → 连接的 zone ids）
     */
    private static Map<String, Set<String>> buildAdjacencyMap(PlanProgram program) {
        Map<String, Set<String>> map = new HashMap<>();

        if (program.adjacency() == null) {
            return map;
        }

        for (List<String> pair : program.adjacency()) {
            if (pair == null || pair.size() != 2) {
                continue;
            }
            String id1 = pair.get(0);
            String id2 = pair.get(1);
            if (id1 == null || id2 == null || id1.equals(id2)) {
                continue;
            }

            map.computeIfAbsent(id1, k -> new HashSet<>()).add(id2);
            map.computeIfAbsent(id2, k -> new HashSet<>()).add(id1);
        }

        return map;
    }

    /**
     * 生成 edges（根据 adjacency 和 boundary）
     * <p>
     * 规则：
     * - 两个 external zone 之间的 adjacency → shared_wall
     * - 单个 external zone 的边界 → external_wall（需要推断，v1 简化）
     * - 庭院相关的 zone → courtyard_wall（在 detectCourtyards 中处理）
     */
    private static List<PlanSkeleton.Edge> generateEdges(
            PlanProgram program,
            List<PlanSkeleton.PlanZone> skeletonZones,
            Map<String, Set<String>> adjacencyMap
    ) {
        List<PlanSkeleton.Edge> edges = new ArrayList<>();
        int edgeCounter = 1;

        // 构建 zone 索引（id → skeleton zone）
        Map<String, PlanSkeleton.PlanZone> zoneMap = new HashMap<>();
        for (PlanSkeleton.PlanZone zone : skeletonZones) {
            zoneMap.put(zone.id(), zone);
        }

        // 为每个 adjacency 生成 shared_wall edge
        if (program.adjacency() != null) {
            Set<String> processedPairs = new HashSet<>();
            for (List<String> pair : program.adjacency()) {
                if (pair == null || pair.size() != 2) {
                    continue;
                }
                String id1 = pair.get(0);
                String id2 = pair.get(1);
                if (id1 == null || id2 == null || id1.equals(id2)) {
                    continue;
                }

                // 避免重复（确保 id1 < id2）
                String pairKey = id1.compareTo(id2) < 0 ? id1 + ":" + id2 : id2 + ":" + id1;
                if (processedPairs.contains(pairKey)) {
                    continue;
                }
                processedPairs.add(pairKey);

                PlanSkeleton.PlanZone zone1 = zoneMap.get(id1);
                PlanSkeleton.PlanZone zone2 = zoneMap.get(id2);
                if (zone1 == null || zone2 == null) {
                    continue;
                }

                // 两个 external zone 之间的 adjacency → shared_wall
                if ("external".equals(zone1.boundary()) && "external".equals(zone2.boundary())) {
                    edges.add(new PlanSkeleton.Edge(
                            "edge_" + edgeCounter++,
                            "shared_wall",
                            List.of(id1, id2),
                            false
                    ));
                }
            }
        }

        // v1 简化：不为单个 zone 生成 external_wall edges
        // 这些 edges 可以在后续 PlanSkeleton → Skeleton（3D）转换时根据几何生成

        return edges;
    }

    /**
     * 检测 courtyards（环形 adjacency）
     * <p>
     * v1 简化规则：
     * - 如果 circulation.connection_style == "ring"，且至少有 3 个 zone 形成环
     * - 或者：massing.preferred_operations 包含 "subtract_courtyard" / "wrap_around_courtyard"
     * <p>
     * 检测逻辑：
     * - 查找所有形成环的 zone 组合（至少 3 个）
     * - 如果找到，创建一个 courtyard
     */
    private static List<PlanSkeleton.Courtyard> detectCourtyards(
            PlanProgram program,
            Map<String, Set<String>> adjacencyMap
    ) {
        List<PlanSkeleton.Courtyard> courtyards = new ArrayList<>();

        // 检查 circulation 是否暗示有庭院
        boolean hasRingCirculation = false;
        if (program.circulation() != null) {
            String style = program.circulation().connectionStyle();
            if ("ring".equalsIgnoreCase(style)) {
                hasRingCirculation = true;
            }
        }

        // 检查 massing 是否暗示有庭院
        boolean hasCourtyardOperation = false;
        if (program.massing() != null && program.massing().rules() != null) {
            List<String> operations = program.massing().rules().preferredOperations();
            if (operations != null) {
                for (String op : operations) {
                    if (op != null && (op.toLowerCase().contains("courtyard") || op.toLowerCase().contains("wrap"))) {
                        hasCourtyardOperation = true;
                        break;
                    }
                }
            }
        }

        // 如果暗示有庭院，尝试检测环形结构
        if (hasRingCirculation || hasCourtyardOperation) {
            List<String> ringZones = findRing(adjacencyMap);
            if (ringZones != null && ringZones.size() >= 3) {
                courtyards.add(new PlanSkeleton.Courtyard(
                        "court_1",
                        ringZones
                ));
            }
        }

        return courtyards;
    }

    /**
     * 查找环形结构（简单的 DFS 查找）
     * <p>
     * v1 简化：只查找第一个找到的环
     */
    private static List<String> findRing(Map<String, Set<String>> adjacencyMap) {
        if (adjacencyMap.isEmpty()) {
            return null;
        }

        // 简单的启发式：如果所有 zone 都互相连接，可能形成环
        // v1 简化：返回所有 zone（如果它们形成连通图）
        Set<String> allZones = new HashSet<>(adjacencyMap.keySet());
        if (allZones.size() >= 3) {
            // 检查是否所有 zone 都有至少 2 个连接（形成环的必要条件）
            boolean allHaveMultipleConnections = true;
            for (String zone : allZones) {
                Set<String> connections = adjacencyMap.get(zone);
                if (connections == null || connections.size() < 2) {
                    allHaveMultipleConnections = false;
                    break;
                }
            }
            if (allHaveMultipleConnections) {
                return new ArrayList<>(allZones);
            }
        }

        return null;
    }

    /**
     * 生成 axes（根据 circulation）
     * <p>
     * 规则：
     * - 如果 circulation.primary_axis 存在，创建一个主轴线
     * - 轴线包含 primary_axis 及其连接的 zone
     */
    private static List<PlanSkeleton.Axis> generateAxes(
            PlanProgram program,
            List<PlanSkeleton.PlanZone> skeletonZones
    ) {
        List<PlanSkeleton.Axis> axes = new ArrayList<>();

        if (program.circulation() == null) {
            return axes;
        }

        String primaryAxisId = program.circulation().primaryAxis();
        if (primaryAxisId == null || primaryAxisId.isBlank()) {
            return axes;
        }

        // 查找 primary_axis 对应的 zone
        PlanSkeleton.PlanZone primaryZone = null;
        for (PlanSkeleton.PlanZone zone : skeletonZones) {
            if (primaryAxisId.equals(zone.id())) {
                primaryZone = zone;
                break;
            }
        }

        if (primaryZone == null) {
            FormacraftMod.LOGGER.warn("PlanProgramToPlanSkeletonConverter: primary_axis zone not found: {}", primaryAxisId);
            return axes;
        }

        // 构建轴线：primary zone + 其连接的 zone
        List<String> axisZones = new ArrayList<>();
        axisZones.add(primaryZone.id());
        if (primaryZone.connectedTo() != null) {
            axisZones.addAll(primaryZone.connectedTo());
        }

        axes.add(new PlanSkeleton.Axis(
                "main_axis",
                "primary",
                axisZones
        ));

        return axes;
    }

    /**
     * 生成 outline（默认 generated）
     */
    private static PlanSkeleton.Outline generateOutline(PlanProgram program) {
        // 根据 constraints.geometry 决定 shape
        String shape = "rectilinear"; // 默认
        if (program.constraints() != null && program.constraints().geometry() != null) {
            List<String> avoidShapes = program.constraints().geometry().avoidShapes();
            if (avoidShapes != null && avoidShapes.contains("perfect_circle")) {
                // 避免圆形，使用多边形
                shape = "polygon";
            }
        }

        return new PlanSkeleton.Outline(
                "generated",
                shape
        );
    }
}
