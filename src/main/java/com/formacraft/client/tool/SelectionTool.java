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
 */
public final class SelectionTool implements FormacraftTool {
    public static final SelectionTool INSTANCE = new SelectionTool();

    private SelectionTool() {}

    private BlockPos start;
    private BlockPos end;
    private boolean selecting = false;
    
    // 锚点拖拽状态
    private Direction draggingFace = null; // 当前正在拖拽的面
    private Vec3d dragStartPos = null; // 拖拽开始时的世界坐标
    private BlockPos dragStartMin = null; // 拖拽开始时的min坐标
    private BlockPos dragStartMax = null; // 拖拽开始时的max坐标
    
    // 锚点大小
    private static final double ANCHOR_SIZE = 0.08; // 缩小锚点
    private static final double ANCHOR_HOVER_DISTANCE = 0.3; // 鼠标悬停检测距离（世界单位）

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

        // 如果正在拖拽，先停止拖拽（通过鼠标释放检测来处理）
        // 这里只处理开始拖拽的逻辑

        // 如果已有选区，检查是否点击在锚点上
        if (hasSelection()) {
            Direction hoveredFace = getHoveredFace();
            if (hoveredFace != null) {
                // 开始拖拽
                draggingFace = hoveredFace;
                HitResult hit = CursorRaycastHelper.getLastHit();
                if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
                    dragStartPos = hit.getPos();
                } else {
                    // 如果没有命中，使用射线与面的交点
                    dragStartPos = getRayIntersectionWithFace(hoveredFace);
                }
                dragStartMin = getMin();
                dragStartMax = getMax();
                return true;
            }
        }

        // 如果正在拖拽，则不应创建新选区
        if (draggingFace != null) {
            return true;
        }

        // 检查是否悬停在锚点上（即使没有选区也要检查，以阻止新选择）
        Direction hoveredFace = getHoveredFace();
        if (hoveredFace != null && hasSelection()) {
            // 如果已经有选区且悬停在锚点上，点击时开始拖拽（已在上面处理）
            return true;
        }
        
        // 如果悬停在锚点上但没有选区，阻止创建新选区
        if (hoveredFace != null) {
            return true; // 阻止选框功能
        }

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
        // 检测鼠标是否释放（停止拖拽）
        if (draggingFace != null) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.getWindow() != null) {
                long window = client.getWindow().getHandle();
                // 检查左键是否已释放
                int leftButtonState = org.lwjgl.glfw.GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                if (leftButtonState == GLFW.GLFW_RELEASE) {
                    // 停止拖拽
                    draggingFace = null;
                    dragStartPos = null;
                    dragStartMin = null;
                    dragStartMax = null;
                }
            }
        }
        
        // 处理拖拽逻辑
        if (draggingFace != null) {
            handleDrag();
        }
        
        // 更新光标样式
        updateCursor();
        
        // 如果鼠标悬停在锚点上或正在拖拽，阻止选框功能
        Direction hoveredFace = getHoveredFace();
        if (hoveredFace != null || draggingFace != null) {
            // 如果正在选择且鼠标移到锚点上，停止选择过程
            if (selecting && hoveredFace != null) {
                selecting = false;
                // 保持当前的选区（不改变start和end）
            }
            // 阻止选框更新
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

        Box worldBox = new Box(
                min.getX(), min.getY(), min.getZ(),
                max.getX() + 1, max.getY() + 1, max.getZ() + 1
        ).expand(0.01);

        Box box = worldBox.offset(-ctx.cameraX, -ctx.cameraY, -ctx.cameraZ);
        VertexRendering.drawBox(ctx.matrices.peek(), ctx.vertexConsumer, box, 0.35f, 0.85f, 1.00f, 0.65f);

        double s = 0.12;
        drawCorner(ctx, min.getX(), min.getY(), min.getZ(), s);
        drawCorner(ctx, max.getX() + 1, min.getY(), min.getZ(), s);
        drawCorner(ctx, min.getX(), max.getY() + 1, min.getZ(), s);
        drawCorner(ctx, max.getX() + 1, max.getY() + 1, min.getZ(), s);
        drawCorner(ctx, min.getX(), min.getY(), max.getZ() + 1, s);
        drawCorner(ctx, max.getX() + 1, min.getY(), max.getZ() + 1, s);
        drawCorner(ctx, min.getX(), max.getY() + 1, max.getZ() + 1, s);
        drawCorner(ctx, max.getX() + 1, max.getY() + 1, max.getZ() + 1, s);
        
        // 只在有选区且不在选择过程中时绘制锚点
        if (hasSelection()) {
            Direction hoveredFace = getHoveredFace();
            drawFaceAnchors(ctx, min, max, hoveredFace);
        }
    }

    private void drawCorner(ToolWorldRenderContext ctx, double wx, double wy, double wz, double size) {
        Box corner = new Box(
                wx - size, wy - size, wz - size,
                wx + size, wy + size, wz + size
        ).offset(-ctx.cameraX, -ctx.cameraY, -ctx.cameraZ);
        VertexRendering.drawBox(ctx.matrices.peek(), ctx.vertexConsumer, corner, 0.85f, 0.95f, 1.00f, 0.95f);
    }
    
    /**
     * 绘制六个面的中心锚点
     */
    private void drawFaceAnchors(ToolWorldRenderContext ctx, BlockPos min, BlockPos max, Direction hoveredFace) {
        double minX = min.getX();
        double minY = min.getY();
        double minZ = min.getZ();
        double maxX = max.getX() + 1;
        double maxY = max.getY() + 1;
        double maxZ = max.getZ() + 1;
        
        // X- 面（最小X面）
        Vec3d pos = new Vec3d(minX, (minY + maxY) / 2.0, (minZ + maxZ) / 2.0);
        boolean isHovered = hoveredFace == Direction.WEST;
        drawAnchor(ctx, pos, isHovered);
        
        // X+ 面（最大X面）
        pos = new Vec3d(maxX, (minY + maxY) / 2.0, (minZ + maxZ) / 2.0);
        isHovered = hoveredFace == Direction.EAST;
        drawAnchor(ctx, pos, isHovered);
        
        // Y- 面（最小Y面）
        pos = new Vec3d((minX + maxX) / 2.0, minY, (minZ + maxZ) / 2.0);
        isHovered = hoveredFace == Direction.DOWN;
        drawAnchor(ctx, pos, isHovered);
        
        // Y+ 面（最大Y面）
        pos = new Vec3d((minX + maxX) / 2.0, maxY, (minZ + maxZ) / 2.0);
        isHovered = hoveredFace == Direction.UP;
        drawAnchor(ctx, pos, isHovered);
        
        // Z- 面（最小Z面）
        pos = new Vec3d((minX + maxX) / 2.0, (minY + maxY) / 2.0, minZ);
        isHovered = hoveredFace == Direction.NORTH;
        drawAnchor(ctx, pos, isHovered);
        
        // Z+ 面（最大Z面）
        pos = new Vec3d((minX + maxX) / 2.0, (minY + maxY) / 2.0, maxZ);
        isHovered = hoveredFace == Direction.SOUTH;
        drawAnchor(ctx, pos, isHovered);
    }
    
    /**
     * 绘制单个锚点（小方块）
     */
    private void drawAnchor(ToolWorldRenderContext ctx, Vec3d pos, boolean isHovered) {
        double s = isHovered ? ANCHOR_SIZE * 1.5 : ANCHOR_SIZE; // 悬停时稍微放大以提供视觉反馈
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
    
    /**
     * 检测鼠标是否悬停在某个面的锚点上
     */
    private Direction getHoveredFace() {
        BlockPos min = getMin();
        BlockPos max = getMax();
        if (min == null || max == null) return null;
        
        Vec3d cameraPos = getCameraPos();
        Vec3d mouseRayDir = getMouseRayDirection();
        
        // 找到最接近鼠标射线的锚点
        double minScore = Double.MAX_VALUE;
        Direction closestFace = null;
        
        double minX = min.getX();
        double minY = min.getY();
        double minZ = min.getZ();
        double maxX = max.getX() + 1;
        double maxY = max.getY() + 1;
        double maxZ = max.getZ() + 1;
        
        Direction[] faces = {Direction.WEST, Direction.EAST, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH};
        Vec3d[] centers = {
            new Vec3d(minX, (minY + maxY) / 2.0, (minZ + maxZ) / 2.0), // X-
            new Vec3d(maxX, (minY + maxY) / 2.0, (minZ + maxZ) / 2.0), // X+
            new Vec3d((minX + maxX) / 2.0, minY, (minZ + maxZ) / 2.0), // Y-
            new Vec3d((minX + maxX) / 2.0, maxY, (minZ + maxZ) / 2.0), // Y+
            new Vec3d((minX + maxX) / 2.0, (minY + maxY) / 2.0, minZ), // Z-
            new Vec3d((minX + maxX) / 2.0, (minY + maxY) / 2.0, maxZ)  // Z+
        };
        
        // 计算每个锚点到鼠标射线的距离
        for (int i = 0; i < faces.length; i++) {
            Vec3d anchorPos = centers[i];
            Vec3d toAnchor = anchorPos.subtract(cameraPos);
            double distToAnchor = toAnchor.length();
            
            if (distToAnchor < 0.1) continue; // 锚点太近，跳过
            
            // 计算锚点在鼠标射线上的投影
            double projectionOnRay = toAnchor.dotProduct(mouseRayDir);
            
            // 如果投影为负，说明锚点在射线后方，跳过
            if (projectionOnRay < 0) continue;
            
            // 计算锚点到射线的垂直距离
            Vec3d projectedPoint = cameraPos.add(mouseRayDir.multiply(projectionOnRay));
            Vec3d perpendicular = anchorPos.subtract(projectedPoint);
            double perpDist = perpendicular.length();
            
            // 使用距离作为评分（距离越近，分数越小，越容易被选中）
            // 同时考虑投影距离，优先选择更靠近相机的
            double score = perpDist * (1.0 + distToAnchor * 0.05);
            
            // 如果距离在阈值内，记录最接近的锚点
            if (perpDist < ANCHOR_HOVER_DISTANCE && score < minScore) {
                minScore = score;
                closestFace = faces[i];
            }
        }
        
        return closestFace;
    }
    
    /**
     * 获取相机位置
     */
    private Vec3d getCameraPos() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.gameRenderer == null) return Vec3d.ZERO;
        return client.gameRenderer.getCamera().getPos();
    }
    
    /**
     * 获取鼠标射线方向（从相机位置到鼠标指向的方向）
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
        Vec3d rayDir = look
                .add(right.multiply(ndcX * tan * aspect))
                .add(up.multiply(ndcY * tan))
                .normalize();
        
        return rayDir;
    }
    
    /**
     * 计算射线与面的交点
     */
    private Vec3d getRayIntersectionWithFace(Direction face) {
        BlockPos min = getMin();
        BlockPos max = getMax();
        if (min == null || max == null) return Vec3d.ZERO;
        
        Vec3d rayStart = getCameraPos();
        Vec3d rayDir = getMouseRayDirection();
        
        double minX = min.getX();
        double minY = min.getY();
        double minZ = min.getZ();
        double maxX = max.getX() + 1;
        double maxY = max.getY() + 1;
        double maxZ = max.getZ() + 1;
        
        double t = Double.MAX_VALUE;
        double planeCoord = 0;
        
        // 根据面计算交点
        switch (face) {
            case WEST -> {
                planeCoord = minX;
                if (Math.abs(rayDir.x) > 1e-6) {
                    t = (planeCoord - rayStart.x) / rayDir.x;
                }
            }
            case EAST -> {
                planeCoord = maxX;
                if (Math.abs(rayDir.x) > 1e-6) {
                    t = (planeCoord - rayStart.x) / rayDir.x;
                }
            }
            case DOWN -> {
                planeCoord = minY;
                if (Math.abs(rayDir.y) > 1e-6) {
                    t = (planeCoord - rayStart.y) / rayDir.y;
                }
            }
            case UP -> {
                planeCoord = maxY;
                if (Math.abs(rayDir.y) > 1e-6) {
                    t = (planeCoord - rayStart.y) / rayDir.y;
                }
            }
            case NORTH -> {
                planeCoord = minZ;
                if (Math.abs(rayDir.z) > 1e-6) {
                    t = (planeCoord - rayStart.z) / rayDir.z;
                }
            }
            case SOUTH -> {
                planeCoord = maxZ;
                if (Math.abs(rayDir.z) > 1e-6) {
                    t = (planeCoord - rayStart.z) / rayDir.z;
                }
            }
        }
        
        if (t > 0 && t < 1000) {
            Vec3d intersection = rayStart.add(rayDir.multiply(t));
            // 将交点投影到面的中心位置（保持在同一平面上）
            Vec3d faceCenter = getFaceCenter(min, max, face);
            Vec3d offset = intersection.subtract(faceCenter);
            // 移除垂直于面的分量
            switch (face) {
                case WEST, EAST -> offset = new Vec3d(0, offset.y, offset.z);
                case DOWN, UP -> offset = new Vec3d(offset.x, 0, offset.z);
                case NORTH, SOUTH -> offset = new Vec3d(offset.x, offset.y, 0);
            }
            return faceCenter.add(offset);
        }
        
        // 如果射线没有与面相交，返回面的中心
        return getFaceCenter(min, max, face);
    }
    
    /**
     * 获取面的中心位置
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
    
    /**
     * 处理拖拽逻辑
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
        Vec3d faceNormal = Vec3d.of(draggingFace.getVector()).normalize();
        double dragDistance = dragDelta.dotProduct(faceNormal);
        
        // 更新对应的面
        BlockPos newMin = dragStartMin;
        BlockPos newMax = dragStartMax;
        
        switch (draggingFace) {
            case WEST -> {
                int newX = (int) Math.round(dragStartMin.getX() + dragDistance);
                newMin = new BlockPos(newX, newMin.getY(), newMin.getZ());
            }
            case EAST -> {
                int newX = (int) Math.round(dragStartMax.getX() + dragDistance);
                newMax = new BlockPos(newX, newMax.getY(), newMax.getZ());
            }
            case DOWN -> {
                int newY = (int) Math.round(dragStartMin.getY() + dragDistance);
                newMin = new BlockPos(newMin.getX(), newY, newMin.getZ());
            }
            case UP -> {
                int newY = (int) Math.round(dragStartMax.getY() + dragDistance);
                newMax = new BlockPos(newMax.getX(), newY, newMax.getZ());
            }
            case NORTH -> {
                int newZ = (int) Math.round(dragStartMin.getZ() + dragDistance);
                newMin = new BlockPos(newMin.getX(), newMin.getY(), newZ);
            }
            case SOUTH -> {
                int newZ = (int) Math.round(dragStartMax.getZ() + dragDistance);
                newMax = new BlockPos(newMax.getX(), newMax.getY(), newZ);
            }
        }
        
        // 确保min <= max
        int finalMinX = Math.min(newMin.getX(), newMax.getX());
        int finalMinY = Math.min(newMin.getY(), newMax.getY());
        int finalMinZ = Math.min(newMin.getZ(), newMax.getZ());
        int finalMaxX = Math.max(newMin.getX(), newMax.getX());
        int finalMaxY = Math.max(newMin.getY(), newMax.getY());
        int finalMaxZ = Math.max(newMin.getZ(), newMax.getZ());
        
        start = new BlockPos(finalMinX, finalMinY, finalMinZ);
        end = new BlockPos(finalMaxX - 1, finalMaxY - 1, finalMaxZ - 1);
    }
    
    // 缓存的当前光标句柄（用于释放资源）
    private long currentCursorHandle = 0;
    
    /**
     * 更新光标样式
     */
    private void updateCursor() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getWindow() == null) return;
        
        long window = client.getWindow().getHandle();
        Direction hoveredFace = getHoveredFace();
        
        if (hoveredFace != null || draggingFace != null) {
            Direction face = draggingFace != null ? draggingFace : hoveredFace;
            // 根据面的方向设置相应的光标样式
            int cursorShape;
            if (face == Direction.UP || face == Direction.DOWN) {
                // 垂直方向，使用垂直双向箭头
                cursorShape = GLFW.GLFW_RESIZE_NS_CURSOR;
            } else {
                // 水平方向，使用水平双向箭头（对于X和Z轴，都使用水平箭头，实际方向会根据视图角度调整）
                cursorShape = GLFW.GLFW_RESIZE_EW_CURSOR;
            }
            
            // 释放旧的光标句柄
            if (currentCursorHandle != 0) {
                GLFW.glfwDestroyCursor(currentCursorHandle);
                currentCursorHandle = 0;
            }
            
            // 创建并设置新光标
            currentCursorHandle = GLFW.glfwCreateStandardCursor(cursorShape);
            GLFW.glfwSetCursor(window, currentCursorHandle);
        } else {
            // 没有悬停或拖拽，恢复默认光标
            if (currentCursorHandle != 0) {
                GLFW.glfwDestroyCursor(currentCursorHandle);
                currentCursorHandle = 0;
                GLFW.glfwSetCursor(window, 0); // 0 表示默认光标
            }
        }
    }

    public boolean hasSelection() {
        return start != null && end != null && !selecting;
    }

    public boolean isSelecting() {
        return selecting;
    }

    public BlockPos getMin() {
        if (start == null || end == null) return null;
        return new BlockPos(
                Math.min(start.getX(), end.getX()),
                Math.min(start.getY(), end.getY()),
                Math.min(start.getZ(), end.getZ())
        );
    }

    public BlockPos getMax() {
        if (start == null || end == null) return null;
        return new BlockPos(
                Math.max(start.getX(), end.getX()),
                Math.max(start.getY(), end.getY()),
                Math.max(start.getZ(), end.getZ())
        );
    }

    public void clear() {
        this.start = null;
        this.end = null;
        this.selecting = false;
        // 停止拖拽并清理光标
        this.draggingFace = null;
        this.dragStartPos = null;
        this.dragStartMin = null;
        this.dragStartMax = null;
        
        // 清理光标句柄
        if (currentCursorHandle != 0) {
            GLFW.glfwDestroyCursor(currentCursorHandle);
            currentCursorHandle = 0;
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.getWindow() != null) {
                GLFW.glfwSetCursor(client.getWindow().getHandle(), 0);
            }
        }
    }
    
    /**
     * 直接设置选区（用于其他工具）
     */
    public void setSelection(BlockPos start, BlockPos end) {
        this.start = start != null ? start.toImmutable() : null;
        this.end = end != null ? end.toImmutable() : null;
        this.selecting = false;
    }
    
    /**
     * 清除选区
     */
    public void clearSelection() {
        clear();
    }
}

