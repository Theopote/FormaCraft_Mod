package com.formacraft.client.tool;

import com.formacraft.client.interaction.CursorRaycastHelper;
import com.formacraft.client.ui.input.InputRouter;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

/**
 * 球形笔刷（贴地一层）：
 * - 左键按住涂抹：把“地表一层方块”加入选中集合（高亮边框/顶面）
 * - 右键：清空
 *
 * 说明：
 * - 为了符合“对着地表涂抹”的直觉，这里使用 Heightmap(WORLD_SURFACE) 获取每个 (x,z) 的地表高度，
 *   只选中该列的最上层方块（topY-1）。
 * - 预览（hover）与已选中集合（selected）都会在世界中渲染为参考范围。
 */
public final class BrushTool implements FormacraftTool {
    public static final BrushTool INSTANCE = new BrushTool();

    private BrushTool() {}

    public enum RenderMode {
        OUTLINE_TOP, // 顶面边框
        OUTLINE_BOX  // 立方体外框（更醒目，但更“厚”）
    }

    private int radius = 4;
    private static final int MIN_RADIUS = 1;
    private static final int MAX_RADIUS = 32;

    private RenderMode mode = RenderMode.OUTLINE_TOP;

    // 已选中的方块（以 BlockPos#asLong 编码）
    private final LongOpenHashSet selected = new LongOpenHashSet();

    // 当前 hover 预览（每 tick 重算）
    private final LongArrayList hover = new LongArrayList();


    @Override
    public String getId() {
        return "brush";
    }

    @Override
    public Text getDisplayName() {
        return Text.literal("笔刷工具");
    }

    public int getRadius() {
        return radius;
    }

    public void incRadius() {
        radius = Math.min(MAX_RADIUS, radius + 1);
    }

    public void decRadius() {
        radius = Math.max(MIN_RADIUS, radius - 1);
    }

    public RenderMode getMode() {
        return mode;
    }

    public void cycleMode() {
        mode = switch (mode) {
            case OUTLINE_TOP -> RenderMode.OUTLINE_BOX;
            case OUTLINE_BOX -> RenderMode.OUTLINE_TOP;
        };
    }

    public int getSelectedCount() {
        return selected.size();
    }

    public void clearSelected() {
        selected.clear();
    }

    @Override
    public void onDeactivate() {
        // 不自动清空 selected（用户希望作为参考范围保留）
        hover.clear();
    }

    @Override
    public boolean onMouseClick(double mx, double my, int button) {
        if (button != 0 && button != 1) return false;
        if (button == 1) {
            clearSelected();
            return true;
        }
        // 左键：点一下也应涂抹一次
        paintHoverToSelected();
        return true;
    }

    @Override
    public void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;
        World world = mc.world;
        if (world == null) return;

        updateHover(world);

        boolean leftDown = InputRouter.leftDown;
        if (leftDown) {
            // 按住涂抹：每 tick 合并一次（性能足够，且手感更顺）
            paintHoverToSelected();
        }

    }

    private void updateHover(World world) {
        hover.clear();

        BlockHitResult hit = CursorRaycastHelper.getLastBlockHit();
        if (hit == null) return;
        BlockPos base = hit.getBlockPos();
        if (base == null) return;

        int cx = base.getX();
        int cz = base.getZ();
        int r = radius;

        int r2 = r * r;
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                if (dx * dx + dz * dz > r2) continue;
                int x = cx + dx;
                int z = cz + dz;

                // 只在已加载区块内采样，避免奇怪高度
                try {
                    // 避免使用已弃用的 isChunkLoaded(BlockPos)：改为用 (chunkX, chunkZ) 判断
                    int chunkX = x >> 4;
                    int chunkZ = z >> 4;
                    if (!world.isChunkLoaded(chunkX, chunkZ)) continue;

                    int topY = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
                    int y = topY - 1;
                    // 1.21.10 没有无参 getTopY()；这里只需要保证 y 不低于世界下界即可。
                    // （y 是由 topY 推出来的，本身不可能大于该列的 topY）
                    if (y < world.getBottomY()) continue;
                    hover.add(BlockPos.asLong(x, y, z));
                } catch (Throwable ignored) {
                    // 静默跳过（客户端世界/映射差异等）
                }
            }
        }
    }

    private void paintHoverToSelected() {
        if (hover.isEmpty()) return;
        for (int i = 0; i < hover.size(); i++) {
            selected.add(hover.getLong(i));
        }
    }

    /**
     * 全局渲染：已选中的范围始终显示；hover 仅当当前工具激活时显示。
     */
    public static void renderGlobal(ToolWorldRenderContext ctx) {
        if (ctx == null) return;

        // 已选中：黄色
        renderSet(ctx, INSTANCE.selected, INSTANCE.mode, 255, 235, 60, 220);

        // hover：仅在工具激活时显示，青绿
        if (!ToolManager.isActive(INSTANCE.getId())) return;
        renderList(ctx, INSTANCE.hover, INSTANCE.mode, 80, 255, 180, 180);
    }

    private static void renderSet(ToolWorldRenderContext ctx, LongOpenHashSet set, RenderMode mode,
                                  int r, int g, int b, int a) {
        if (set == null || set.isEmpty()) return;
        for (long packed : set) {
            BlockPos p = BlockPos.fromLong(packed);
            renderOne(ctx, p, mode, r, g, b, a);
        }
    }

    private static void renderList(ToolWorldRenderContext ctx, LongArrayList list, RenderMode mode,
                                   int r, int g, int b, int a) {
        if (list == null || list.isEmpty()) return;
        for (int i = 0; i < list.size(); i++) {
            BlockPos p = BlockPos.fromLong(list.getLong(i));
            renderOne(ctx, p, mode, r, g, b, a);
        }
    }

    private static void renderOne(ToolWorldRenderContext ctx, BlockPos p, RenderMode mode,
                                  int r, int g, int b, int a) {
        if (p == null) return;
        double x0 = p.getX();
        double y0 = p.getY();
        double z0 = p.getZ();
        double x1 = x0 + 1.0;
        double y1 = y0 + 1.0;
        double z1 = z0 + 1.0;

        double eps = 0.02;
        if (mode == RenderMode.OUTLINE_BOX) {
            // 立方体外框：画 12 条边（用 ToolRenderUtil.line 近似）
            // 底面
            ToolRenderUtil.line(ctx, x0, y0 + eps, z0, x1, y0 + eps, z0, r, g, b, a);
            ToolRenderUtil.line(ctx, x1, y0 + eps, z0, x1, y0 + eps, z1, r, g, b, a);
            ToolRenderUtil.line(ctx, x1, y0 + eps, z1, x0, y0 + eps, z1, r, g, b, a);
            ToolRenderUtil.line(ctx, x0, y0 + eps, z1, x0, y0 + eps, z0, r, g, b, a);
            // 顶面
            ToolRenderUtil.line(ctx, x0, y1 + eps, z0, x1, y1 + eps, z0, r, g, b, a);
            ToolRenderUtil.line(ctx, x1, y1 + eps, z0, x1, y1 + eps, z1, r, g, b, a);
            ToolRenderUtil.line(ctx, x1, y1 + eps, z1, x0, y1 + eps, z1, r, g, b, a);
            ToolRenderUtil.line(ctx, x0, y1 + eps, z1, x0, y1 + eps, z0, r, g, b, a);
            // 竖边
            ToolRenderUtil.line(ctx, x0, y0 + eps, z0, x0, y1 + eps, z0, r, g, b, a);
            ToolRenderUtil.line(ctx, x1, y0 + eps, z0, x1, y1 + eps, z0, r, g, b, a);
            ToolRenderUtil.line(ctx, x1, y0 + eps, z1, x1, y1 + eps, z1, r, g, b, a);
            ToolRenderUtil.line(ctx, x0, y0 + eps, z1, x0, y1 + eps, z1, r, g, b, a);
            return;
        }

        // 顶面边框（不画竖边，视觉更“像选中一层地表”）
        double yy = y1 + eps;
        ToolRenderUtil.line(ctx, x0, yy, z0, x1, yy, z0, r, g, b, a);
        ToolRenderUtil.line(ctx, x1, yy, z0, x1, yy, z1, r, g, b, a);
        ToolRenderUtil.line(ctx, x1, yy, z1, x0, yy, z1, r, g, b, a);
        ToolRenderUtil.line(ctx, x0, yy, z1, x0, yy, z0, r, g, b, a);

        // 简单“填充感”：画一条对角线
        ToolRenderUtil.line(ctx, x0, yy, z0, x1, yy, z1, r, g, b, Math.max(60, a / 3));
    }
}


