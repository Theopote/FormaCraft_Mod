package com.formacraft.client.tool;

import com.formacraft.client.interaction.CursorRaycastHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

/**
 * 两点框选工具：左键第一次设置起点，第二次设置终点。
 * 支持在选框六个面的中心使用锚点调整大小。
 * 
 * <p>功能特性：
 * <ul>
 *   <li>两点框选创建选区</li>
 *   <li>六个面中心的锚点支持拖拽调整大小</li>
 *   <li>悬停检测和视觉反馈</li>
 *   <li>光标样式自动切换</li>
 * </ul>
 */
public final class SelectionTool implements FormacraftTool {
    public static final SelectionTool INSTANCE = new SelectionTool();

    private SelectionTool() {}

    // ==================== 常量 ====================
    /** 锚点大小（世界单位） */
    private static final double ANCHOR_SIZE = 0.08;
    /** 鼠标悬停检测距离（世界单位，基础值） */
    private static final double ANCHOR_HOVER_DISTANCE_BASE = 0.4; // 适中的基础值
    /** 悬停检测的最大角度阈值（弧度） */
    private static final double ANCHOR_HOVER_ANGLE_THRESHOLD = Math.toRadians(3.0); // 适中的角度阈值
    /** 锚点悬停时放大倍数 */
    private static final double ANCHOR_HOVER_SCALE = 1.5;
    /** 锚点突出距离（防止被方块遮挡） */
    private static final double ANCHOR_OFFSET = 0.05; // 锚点稍微突出于面
    /** 角点大小 */
    private static final double CORNER_SIZE = 0.12;
    /** 射线相交最大距离 */
    private static final double MAX_RAY_DISTANCE = 500.0;
    /** 射线方向分量阈值（避免除零） */
    private static final double RAY_DIR_EPSILON = 1e-6;
    
    // ==================== 选区状态 ====================
    private BlockPos start;
    private BlockPos end;
    private boolean selecting = false;
    
    // ==================== 锚点拖拽状态 ====================
    /** 当前正在拖拽的面 */
    private Direction draggingFace = null;
    /** 拖拽开始时的世界坐标 */
    private Vec3d dragStartPos = null;
    /** 拖拽开始时的min坐标 */
    private BlockPos dragStartMin = null;
    /** 拖拽开始时的max坐标 */
    private BlockPos dragStartMax = null;
    
    // ==================== 缓存数据（每帧更新） ====================
    /** 缓存的悬停面（每帧在tick()中更新，避免重复计算） */
    private Direction cachedHoveredFace = null;
    /** 缓存的相机位置 */
    private Vec3d cachedCameraPos = null;
    /** 缓存的鼠标射线方向 */
    private Vec3d cachedMouseRayDir = null;
    
    // ==================== 光标管理 ====================
    /** 当前光标句柄（用于释放资源） */
    private long currentCursorHandle = 0;
    /** 预创建的垂直双向箭头光标 */
    private static long resizeNsCursor = 0;
    /** 预创建的水平双向箭头光标 */
    private static long resizeEwCursor = 0;
    
    // 静态初始化：预创建光标
    static {
        try {
            resizeNsCursor = GLFW.glfwCreateStandardCursor(GLFW.GLFW_RESIZE_NS_CURSOR);
            resizeEwCursor = GLFW.glfwCreateStandardCursor(GLFW.GLFW_RESIZE_EW_CURSOR);
        } catch (Exception e) {
            // 如果GLFW未初始化，延迟到首次使用时创建
        }
    }

    @Override
    public String getId() {
        return "selection";
    }

    @Override
    public Text getDisplayName() {
        return Text.literal("选区工具");
    }

    @Override
    public boolean onMouseClick(double mx, double my, int button) {
        if (button != 0) {
            return false;
        }

        // 如果已有选区，检查是否点击在锚点上
        if (hasSelection()) {
            // 使用缓存的悬停面，避免重复计算
            if (cachedHoveredFace != null) {
                // 开始拖拽
                draggingFace = cachedHoveredFace;
                HitResult hit = CursorRaycastHelper.getLastHit();
                if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
                    dragStartPos = hit.getPos();
                } else {
                    dragStartPos = getRayIntersectionWithFace(cachedHoveredFace);
                }
                dragStartMin = getMin();
                dragStartMax = getMax();
                return true;
            }
        }

        // 如果正在拖拽锚点，阻止创建新选区
        if (draggingFace != null) {
            return true;
        }

        // 只有在有完整选区时才检查锚点悬停（避免在选择过程中误判）

        BlockHitResult hit = CursorRaycastHelper.getLastBlockHit();
        if (hit == null) return true; // 工具吃掉点击，但没有命中方块

        BlockPos pos = hit.getBlockPos();
        if (!selecting) {
            start = pos;
            end = pos;
            selecting = true;
        } else {
            end = pos;
            selecting = false;
        }
        return true;
    }

    @Override
    public void tick() {
        // 更新缓存：每帧计算一次，避免重复计算
        updateCaches();
        
        // 检测鼠标是否释放（停止拖拽）
        if (draggingFace != null) {
            long window = getClientWindow();
            if (window != 0) {
                int leftButtonState = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                if (leftButtonState == GLFW.GLFW_RELEASE) {
                    stopDragging();
                }
            }
        }
        
        // 处理拖拽逻辑
        if (draggingFace != null) {
            handleDrag();
        }
        
        // 更新光标样式
        updateCursor();
        
        // 如果正在拖拽锚点，阻止选框功能
        if (draggingFace != null) {
            return;
        }
        
        // 只有在有完整选区时才检查锚点悬停（避免在选择过程中误判）
        // 如果正在选择过程中，允许继续选择，不阻止选框更新
        if (hasSelection() && cachedHoveredFace != null) {
            // 有完整选区且悬停在锚点上，阻止选框更新
            return;
        }
        
        if (!selecting) return;

        BlockHitResult hit = CursorRaycastHelper.getLastBlockHit();
        if (hit != null) {
            end = hit.getBlockPos();
        }
    }

    @Override
    public void renderWorld(ToolWorldRenderContext ctx) {
        BlockPos min = getMin();
        BlockPos max = getMax();
        if (min == null || max == null) return;

        // 绘制选区框
        Box worldBox = new Box(
                min.getX(), min.getY(), min.getZ(),
                max.getX() + 1, max.getY() + 1, max.getZ() + 1
        ).expand(0.02);

        Box box = worldBox.offset(-ctx.cameraX, -ctx.cameraY, -ctx.cameraZ);
        VertexRendering.drawBox(ctx.matrices.peek(), ctx.vertexConsumer, box, 0.35f, 0.85f, 1.00f, 0.65f);

        // 绘制八个角点
        drawCorners(ctx, min, max);
        
        // 只在有选区且不在选择过程中时绘制锚点
        if (hasSelection()) {
            drawFaceAnchors(ctx, min, max);
        }
    }

    // ==================== 辅助方法：缓存管理 ====================
    
    /**
     * 更新每帧缓存的数据
     */
    private void updateCaches() {
        cachedCameraPos = getCameraPos();
        cachedMouseRayDir = getMouseRayDirection();
        // 只有在有完整选区时才计算悬停面（避免在选择过程中误判）
        cachedHoveredFace = hasSelection() ? computeHoveredFace() : null;
    }
    
    /**
     * 获取客户端窗口句柄（安全封装）
     * @return 窗口句柄，失败返回0
     */
    private long getClientWindow() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getWindow() == null) return 0;
        return client.getWindow().getHandle();
    }

    // ==================== 辅助方法：渲染 ====================
    
    /**
     * 绘制八个角点
     */
    private void drawCorners(ToolWorldRenderContext ctx, BlockPos min, BlockPos max) {
        double minX = min.getX();
        double minY = min.getY();
        double minZ = min.getZ();
        double maxX = max.getX() + 1;
        double maxY = max.getY() + 1;
        double maxZ = max.getZ() + 1;
        
        drawCorner(ctx, minX, minY, minZ);
        drawCorner(ctx, maxX, minY, minZ);
        drawCorner(ctx, minX, maxY, minZ);
        drawCorner(ctx, maxX, maxY, minZ);
        drawCorner(ctx, minX, minY, maxZ);
        drawCorner(ctx, maxX, minY, maxZ);
        drawCorner(ctx, minX, maxY, maxZ);
        drawCorner(ctx, maxX, maxY, maxZ);
    }
    
    private void drawCorner(ToolWorldRenderContext ctx, double wx, double wy, double wz) {
        Box corner = new Box(
                wx - SelectionTool.CORNER_SIZE, wy - SelectionTool.CORNER_SIZE, wz - SelectionTool.CORNER_SIZE,
                wx + SelectionTool.CORNER_SIZE, wy + SelectionTool.CORNER_SIZE, wz + SelectionTool.CORNER_SIZE
        ).offset(-ctx.cameraX, -ctx.cameraY, -ctx.cameraZ);
        VertexRendering.drawBox(ctx.matrices.peek(), ctx.vertexConsumer, corner, 0.85f, 0.95f, 1.00f, 0.95f);
    }
    
    /**
     * 绘制六个面的中心锚点（优化版：减少重复代码）
     */
    private void drawFaceAnchors(ToolWorldRenderContext ctx, BlockPos min, BlockPos max) {
        // 遍历所有六个轴向方向（WEST, EAST, DOWN, UP, NORTH, SOUTH）
        for (Direction face : Direction.values()) {
            drawFaceAnchor(ctx, face, min, max);
        }
    }
    
    /**
     * 绘制单个面的锚点
     * @param ctx 渲染上下文
     * @param face 面的方向
     * @param min 最小坐标
     * @param max 最大坐标
     */
    private void drawFaceAnchor(ToolWorldRenderContext ctx, Direction face, BlockPos min, BlockPos max) {
        Vec3d pos = getFaceCenter(min, max, face);
        // 让锚点稍微突出于面，防止被方块遮挡
        Vec3d offset = Vec3d.of(face.getVector()).multiply(ANCHOR_OFFSET);
        Vec3d anchorPos = pos.add(offset);
        boolean isHovered = cachedHoveredFace == face;
        drawAnchor(ctx, anchorPos, isHovered);
    }
    
    /**
     * 绘制单个锚点（小方块）
     */
    private void drawAnchor(ToolWorldRenderContext ctx, Vec3d pos, boolean isHovered) {
        double s = isHovered ? ANCHOR_SIZE * ANCHOR_HOVER_SCALE : ANCHOR_SIZE;
        Box anchor = new Box(
                pos.x - s, pos.y - s, pos.z - s,
                pos.x + s, pos.y + s, pos.z + s
        ).offset(-ctx.cameraX, -ctx.cameraY, -ctx.cameraZ);
        
        // 使用橙色系颜色，悬停时更亮更明显
        if (isHovered) {
            VertexRendering.drawBox(ctx.matrices.peek(), ctx.vertexConsumer, anchor, 1.0f, 0.65f, 0.0f, 1.0f); // 亮橙色
        } else {
            VertexRendering.drawBox(ctx.matrices.peek(), ctx.vertexConsumer, anchor, 0.9f, 0.5f, 0.1f, 0.95f); // 橙色
        }
    }

    // ==================== 辅助方法：悬停检测 ====================
    
    /**
     * 计算鼠标是否悬停在某个面的锚点上（改进版：增强远距离检测）
     * 使用角度判断和动态距离阈值，确保远距离也能正常工作
     * @return 悬停的面，如果没有则返回null
     */
    private Direction computeHoveredFace() {
        BlockPos min = getMin();
        BlockPos max = getMax();
        if (min == null || max == null || cachedCameraPos == null || cachedMouseRayDir == null) {
            return null;
        }
        
        double minScore = Double.MAX_VALUE;
        Direction closestFace = null;
        
        // 获取FOV用于动态调整（高FOV/缩放时缩小阈值）
        MinecraftClient client = MinecraftClient.getInstance();
        double fovMultiplier = 1.0;
        if (client != null && client.options != null) {
            try {
                double fov = client.options.getFov().getValue();
                // FOV越大（缩放时），阈值越小
                fovMultiplier = 70.0 / fov; // 70是默认FOV
                fovMultiplier = Math.max(0.3, Math.min(3.0, fovMultiplier)); // 限制范围
            } catch (Exception ignored) {}
        }
        
        // 遍历所有六个轴向面（WEST, EAST, DOWN, UP, NORTH, SOUTH）
        for (Direction face : Direction.values()) {
            Vec3d faceCenter = getFaceCenter(min, max, face);
            // 使用突出后的锚点位置进行检测（与实际渲染位置一致）
            Vec3d offset = Vec3d.of(face.getVector()).multiply(ANCHOR_OFFSET);
            Vec3d anchorPos = faceCenter.add(offset);
            Vec3d toAnchor = anchorPos.subtract(cachedCameraPos);
            double distToAnchor = toAnchor.length();
            
            if (distToAnchor < 0.1) continue; // 锚点太近，跳过
            
            // 计算从相机到锚点的方向（归一化）
            Vec3d toAnchorDir = toAnchor.normalize();
            
            // 计算鼠标射线方向与到锚点方向的夹角（使用点积）
            double dotProduct = cachedMouseRayDir.dotProduct(toAnchorDir);
            
            // 如果角度太大（点积太小），说明鼠标没有指向锚点
            // 使用角度阈值：约5度的圆锥内（增大以提高远距离检测）
            double angleToAnchor = Math.acos(Math.max(-1.0, Math.min(1.0, dotProduct)));
            
            // 根据距离动态调整角度阈值：距离越远，允许的角度越大（但增长较慢）
            double dynamicAngleThreshold = ANCHOR_HOVER_ANGLE_THRESHOLD * (1.0 + distToAnchor * 0.005) * fovMultiplier;
            
            if (angleToAnchor > dynamicAngleThreshold) {
                continue; // 角度太大，跳过
            }
            
            // 计算锚点到射线的垂直距离（用于进一步筛选）
            double projectionOnRay = toAnchor.dotProduct(cachedMouseRayDir);
            if (projectionOnRay < 0) continue; // 锚点在射线后方，跳过
            
            Vec3d projectedPoint = cachedCameraPos.add(cachedMouseRayDir.multiply(projectionOnRay));
            Vec3d perpendicular = anchorPos.subtract(projectedPoint);
            double perpDist = perpendicular.length();
            
            // 根据相机距离动态调整阈值：距离越远，阈值越大（但增长较慢，避免范围过大）
            double dynamicThreshold = ANCHOR_HOVER_DISTANCE_BASE * (1.0 + distToAnchor * 0.15) * fovMultiplier;
            
            // 如果垂直距离在动态阈值内，记录最接近的锚点
            // 使用综合评分：角度越小且距离越近的优先级越高
            if (perpDist < dynamicThreshold) {
                // 评分：角度权重更大，距离作为次要因素
                double angleScore = angleToAnchor * 100.0; // 角度权重
                double distanceScore = perpDist * 0.1; // 距离权重
                double score = angleScore + distanceScore;
                
                if (score < minScore) {
                    minScore = score;
                    closestFace = face;
                }
            }
        }
        
        return closestFace;
    }
    
    // ==================== 辅助方法：相机和射线 ====================
    
    /**
     * 获取相机位置（带缓存）
     */
    private Vec3d getCameraPos() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.gameRenderer == null) return Vec3d.ZERO;
        return client.gameRenderer.getCamera().getPos();
    }
    
    /**
     * 获取鼠标射线方向（从相机位置到鼠标指向的方向，带缓存）
     */
    private Vec3d getMouseRayDirection() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getWindow() == null) return new Vec3d(0, 0, -1);
        
        net.minecraft.entity.Entity camera = client.getCameraEntity();
        if (camera == null) return new Vec3d(0, 0, -1);
        
        // 获取鼠标屏幕坐标
        double scale = client.getWindow().getScaleFactor();
        double mouseX = client.mouse.getX() / scale;
        double mouseY = client.mouse.getY() / scale;
        double w = client.getWindow().getScaledWidth();
        double h = client.getWindow().getScaledHeight();
        
        if (w <= 0 || h <= 0) return new Vec3d(0, 0, -1);
        
        // 转换为 NDC 坐标 [-1, 1]
        double ndcX = (mouseX / w) * 2.0 - 1.0;
        double ndcY = 1.0 - (mouseY / h) * 2.0;
        
        // 获取 FOV
        double fov = 70.0;
        try {
            fov = client.options.getFov().getValue();
        } catch (Throwable ignored) {}
        
        double aspect = w / h;
        double tan = Math.tan(Math.toRadians(fov) / 2.0);
        
        // 计算相机的基础方向
        Vec3d look = camera.getRotationVec(1.0f);
        Vec3d upWorld = new Vec3d(0, 1, 0);
        
        // 计算右向量
        Vec3d right = look.crossProduct(upWorld);
        if (right.lengthSquared() < 1e-6) {
            right = new Vec3d(1, 0, 0);
        } else {
            right = right.normalize();
        }
        
        // 重新正交化上向量
        Vec3d up = right.crossProduct(look).normalize();
        
        // 计算鼠标射线方向
        return look
                .add(right.multiply(ndcX * tan * aspect))
                .add(up.multiply(ndcY * tan))
                .normalize();
    }
    
    /**
     * 计算射线与面的交点
     * @param face 面的方向
     * @return 交点坐标，如果射线不与面相交则返回面的中心
     */
    private Vec3d getRayIntersectionWithFace(Direction face) {
        BlockPos min = getMin();
        BlockPos max = getMax();
        if (min == null || max == null || cachedCameraPos == null || cachedMouseRayDir == null) {
            return Vec3d.ZERO;
        }
        
        double planeCoord = getPlaneCoord(face, min, max);
        double t = getT(face, planeCoord);

        // 检查交点是否有效
        if (t > 0 && t < MAX_RAY_DISTANCE) {
            Vec3d intersection = cachedCameraPos.add(cachedMouseRayDir.multiply(t));
            Vec3d faceCenter = getFaceCenter(min, max, face);
            Vec3d offset = intersection.subtract(faceCenter);
            
            // 移除垂直于面的分量（投影到面上）
            switch (face.getAxis()) {
                case X -> offset = new Vec3d(0, offset.y, offset.z);
                case Y -> offset = new Vec3d(offset.x, 0, offset.z);
                case Z -> offset = new Vec3d(offset.x, offset.y, 0);
            }
            
            return faceCenter.add(offset);
        }
        
        // 如果射线没有与面相交，返回面的中心
        return getFaceCenter(min, max, face);
    }

    private double getT(Direction face, double planeCoord) {
        double t = Double.MAX_VALUE;

        // 根据面计算交点参数t
        switch (face.getAxis()) {
            case X -> {
                if (Math.abs(cachedMouseRayDir.x) > RAY_DIR_EPSILON) {
                    t = (planeCoord - cachedCameraPos.x) / cachedMouseRayDir.x;
                }
            }
            case Y -> {
                if (Math.abs(cachedMouseRayDir.y) > RAY_DIR_EPSILON) {
                    t = (planeCoord - cachedCameraPos.y) / cachedMouseRayDir.y;
                }
            }
            case Z -> {
                if (Math.abs(cachedMouseRayDir.z) > RAY_DIR_EPSILON) {
                    t = (planeCoord - cachedCameraPos.z) / cachedMouseRayDir.z;
                }
            }
        }
        return t;
    }

    /**
     * 获取面的平面坐标（沿轴的位置）
     * @param face 面的方向
     * @param min 最小坐标
     * @param max 最大坐标
     * @return 平面坐标值
     */
    private double getPlaneCoord(Direction face, BlockPos min, BlockPos max) {
        return switch (face) {
            case WEST -> min.getX();
            case EAST -> max.getX() + 1;
            case DOWN -> min.getY();
            case UP -> max.getY() + 1;
            case NORTH -> min.getZ();
            case SOUTH -> max.getZ() + 1;
        };
    }
    
    /**
     * 获取面的中心位置
     * @param min 最小坐标
     * @param max 最大坐标
     * @param face 面的方向
     * @return 面的中心坐标
     */
    private Vec3d getFaceCenter(BlockPos min, BlockPos max, Direction face) {
        double minX = min.getX();
        double minY = min.getY();
        double minZ = min.getZ();
        double maxX = max.getX() + 1;
        double maxY = max.getY() + 1;
        double maxZ = max.getZ() + 1;
        
        return switch (face) {
            case WEST -> new Vec3d(minX, (minY + maxY) / 2.0, (minZ + maxZ) / 2.0);
            case EAST -> new Vec3d(maxX, (minY + maxY) / 2.0, (minZ + maxZ) / 2.0);
            case DOWN -> new Vec3d((minX + maxX) / 2.0, minY, (minZ + maxZ) / 2.0);
            case UP -> new Vec3d((minX + maxX) / 2.0, maxY, (minZ + maxZ) / 2.0);
            case NORTH -> new Vec3d((minX + maxX) / 2.0, (minY + maxY) / 2.0, minZ);
            case SOUTH -> new Vec3d((minX + maxX) / 2.0, (minY + maxY) / 2.0, maxZ);
        };
    }
    
    // ==================== 辅助方法：拖拽处理 ====================
    
    /**
     * 处理拖拽逻辑：根据鼠标位置更新选区大小
     */
    private void handleDrag() {
        if (draggingFace == null || dragStartPos == null || dragStartMin == null || dragStartMax == null) {
            return;
        }
        
        HitResult hit = CursorRaycastHelper.getLastHit();
        Vec3d currentPos;
        if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
            currentPos = hit.getPos();
        } else {
            currentPos = getRayIntersectionWithFace(draggingFace);
        }
        
        // 计算拖拽距离（沿着面的法向量方向）
        Vec3d dragDelta = currentPos.subtract(dragStartPos);
        
        // 获取面的法向量（指向选区外部）
        // 对于每个面，法向量指向外部的方向
        Vec3d faceNormal = switch (draggingFace) {
            case WEST -> new Vec3d(-1, 0, 0);   // 指向负X（向外）
            case EAST -> new Vec3d(1, 0, 0);    // 指向正X（向外）
            case DOWN -> new Vec3d(0, -1, 0);   // 指向负Y（向外）
            case UP -> new Vec3d(0, 1, 0);      // 指向正Y（向外）
            case NORTH -> new Vec3d(0, 0, -1); // 指向负Z（向外）
            case SOUTH -> new Vec3d(0, 0, 1);   // 指向正Z（向外）
            // 这不应该发生，但为了编译通过添加默认值
        };

        // 计算拖拽距离在法向量方向上的投影
        double dragDistance = dragDelta.dotProduct(faceNormal);
        
        // 更新对应的面
        BlockPos newMin = dragStartMin;
        BlockPos newMax = dragStartMax;
        
        // 根据面的方向更新坐标（使用getPlaneCoord获取起始坐标）
        double startCoord = getPlaneCoord(draggingFace, dragStartMin, dragStartMax);
        double newCoord = startCoord + dragDistance;
        
        switch (draggingFace) {
            case WEST -> // WEST面（最小X面）：向外拖动（负X方向）→ 减少minX → 选区变大
                    newMin = new BlockPos((int) Math.round(newCoord), dragStartMin.getY(), dragStartMin.getZ());
            case EAST -> // EAST面（最大X面）：向外拖动（正X方向）→ 增加maxX → 选区变大
                    newMax = new BlockPos((int) Math.round(newCoord), dragStartMax.getY(), dragStartMax.getZ());
            case DOWN -> // DOWN面（最小Y面）：向外拖动（负Y方向）→ 减少minY → 选区变大
                    newMin = new BlockPos(dragStartMin.getX(), (int) Math.round(newCoord), dragStartMin.getZ());
            case UP -> // UP面（最大Y面）：向外拖动（正Y方向）→ 增加maxY → 选区变大
                    newMax = new BlockPos(dragStartMax.getX(), (int) Math.round(newCoord), dragStartMax.getZ());
            case NORTH -> // NORTH面（最小Z面）：向外拖动（负Z方向）→ 减少minZ → 选区变大
                    newMin = new BlockPos(dragStartMin.getX(), dragStartMin.getY(), (int) Math.round(newCoord));
            case SOUTH -> // SOUTH面（最大Z面）：向外拖动（正Z方向）→ 增加maxZ → 选区变大
                    newMax = new BlockPos(dragStartMax.getX(), dragStartMax.getY(), (int) Math.round(newCoord));
        }
        
        // 确保min <= max（防止选区为空或反转）
        int finalMinX = Math.min(newMin.getX(), newMax.getX());
        int finalMinY = Math.min(newMin.getY(), newMax.getY());
        int finalMinZ = Math.min(newMin.getZ(), newMax.getZ());
        int finalMaxX = Math.max(newMin.getX(), newMax.getX());
        int finalMaxY = Math.max(newMin.getY(), newMax.getY());
        int finalMaxZ = Math.max(newMin.getZ(), newMax.getZ());
        
        // 确保选区至少为1x1x1
        if (finalMaxX <= finalMinX || finalMaxY <= finalMinY || finalMaxZ <= finalMinZ) {
            return; // 不更新，保持当前选区
        }
        
        start = new BlockPos(finalMinX, finalMinY, finalMinZ);
        end = new BlockPos(finalMaxX - 1, finalMaxY - 1, finalMaxZ - 1);
    }
    
    /**
     * 停止拖拽并清理状态
     */
    private void stopDragging() {
        draggingFace = null;
        dragStartPos = null;
        dragStartMin = null;
        dragStartMax = null;
    }
    
    // ==================== 辅助方法：光标管理 ====================
    
    /**
     * 更新光标样式：根据悬停的面或拖拽状态切换光标
     */
    private void updateCursor() {
        long window = getClientWindow();
        if (window == 0) return;
        
        // 确保光标已创建
        ensureCursorsCreated();
        
        Direction face = draggingFace != null ? draggingFace : cachedHoveredFace;
        
        if (face != null) {
            // 根据面的方向设置相应的光标样式
            long cursorToUse = (face == Direction.UP || face == Direction.DOWN) 
                    ? resizeNsCursor 
                    : resizeEwCursor;
            
            // 只有当光标改变时才设置（避免频繁调用）
            if (currentCursorHandle != cursorToUse) {
                GLFW.glfwSetCursor(window, cursorToUse);
                currentCursorHandle = cursorToUse;
            }
        } else {
            // 没有悬停或拖拽，恢复默认光标
            if (currentCursorHandle != 0) {
                GLFW.glfwSetCursor(window, 0); // 0 表示默认光标
                currentCursorHandle = 0;
            }
        }
    }
    
    /**
     * 确保光标已创建（延迟初始化）
     */
    private void ensureCursorsCreated() {
        if (resizeNsCursor == 0) {
            resizeNsCursor = GLFW.glfwCreateStandardCursor(GLFW.GLFW_RESIZE_NS_CURSOR);
        }
        if (resizeEwCursor == 0) {
            resizeEwCursor = GLFW.glfwCreateStandardCursor(GLFW.GLFW_RESIZE_EW_CURSOR);
        }
    }

    // ==================== 公共方法 ====================
    
    /**
     * 检查是否有选区
     * @return 如果有有效的选区则返回true
     */
    public boolean hasSelection() {
        return start != null && end != null && !selecting;
    }

    /**
     * 检查是否正在选择
     * @return 如果正在选择过程中则返回true
     */
    public boolean isSelecting() {
        return selecting;
    }

    /**
     * 获取选区的最小坐标
     * @return 最小坐标，如果没有选区则返回null
     */
    public BlockPos getMin() {
        if (start == null || end == null) return null;
        return new BlockPos(
                Math.min(start.getX(), end.getX()),
                Math.min(start.getY(), end.getY()),
                Math.min(start.getZ(), end.getZ())
        );
    }

    /**
     * 获取选区的最大坐标
     * @return 最大坐标，如果没有选区则返回null
     */
    public BlockPos getMax() {
        if (start == null || end == null) return null;
        return new BlockPos(
                Math.max(start.getX(), end.getX()),
                Math.max(start.getY(), end.getY()),
                Math.max(start.getZ(), end.getZ())
        );
    }

    /**
     * 清除选区并清理所有状态
     */
    public void clear() {
        this.start = null;
        this.end = null;
        this.selecting = false;
        stopDragging();
        
        // 清理缓存
        cachedHoveredFace = null;
        cachedCameraPos = null;
        cachedMouseRayDir = null;
        
        // 清理光标句柄
        long window = getClientWindow();
        if (window != 0 && currentCursorHandle != 0) {
            GLFW.glfwSetCursor(window, 0);
            currentCursorHandle = 0;
        }
    }
    
    /**
     * 直接设置选区（用于其他工具）
     * @param start 起始坐标
     * @param end 结束坐标
     */
    public void setSelection(BlockPos start, BlockPos end) {
        if (start == null || end == null) {
            clear();
            return;
        }
        
        // 验证并规范化坐标
        BlockPos min = new BlockPos(
                Math.min(start.getX(), end.getX()),
                Math.min(start.getY(), end.getY()),
                Math.min(start.getZ(), end.getZ())
        );
        BlockPos max = new BlockPos(
                Math.max(start.getX(), end.getX()),
                Math.max(start.getY(), end.getY()),
                Math.max(start.getZ(), end.getZ())
        );
        
        this.start = min.toImmutable();
        this.end = max.toImmutable();
        this.selecting = false;
    }
    
    /**
     * 清除选区（公共API）
     */
    public void clearSelection() {
        clear();
    }
}