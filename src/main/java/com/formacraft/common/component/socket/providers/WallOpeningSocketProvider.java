package com.formacraft.common.component.socket.providers;

import com.formacraft.common.component.socket.Socket;
import com.formacraft.common.component.socket.SocketQueryContext;
import com.formacraft.common.component.socket.SocketType;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * WallOpeningSocketProvider（墙面 → 洞口 sockets）。
 * <p>
 * 这是 v1 的关键：从 WALL_SURFACE 上扫描空气矩形洞口，生成 WALL_OPENING。
 * <p>
 * v1 简化策略（性能可控）：
 * - 先从其他 provider 得到 WALL_SURFACE sockets（本 provider 自己再生成也行，但 v1 复用）
 * - 对每个 wall surface box：
 *   - 在 box 投影到网格上做小范围扫描（openingMaxScan 限制）
 *   - 找到符合 minW/minH 的连续空气矩形
 *   - 生成一个 opening socket（Box）
 * <p>
 * v1 不追求"完美识别所有洞"，只要能识别常见门洞/窗洞即可。
 */
public final class WallOpeningSocketProvider implements ToolBasedSocketProvider {

    @Override
    public List<Socket> provide(World world, SocketQueryContext ctx) {
        List<Socket> out = new ArrayList<>();
        if (!ctx.includeOpenings) return out;

        // v1：我们自己"粗略"从选区生成 wall surfaces 再扫 openings
        // 如果你希望完全复用 SelectionBoxSocketProvider 的 wall sockets，
        // 可以把 SocketProviders.collect(...) 改成两阶段（先面，再洞）。
        if (ctx.selectionMin == null || ctx.selectionMax == null) return out;

        BlockPos min = new BlockPos(
                Math.min(ctx.selectionMin.getX(), ctx.selectionMax.getX()),
                Math.min(ctx.selectionMin.getY(), ctx.selectionMax.getY()),
                Math.min(ctx.selectionMin.getZ(), ctx.selectionMax.getZ())
        );
        BlockPos max = new BlockPos(
                Math.max(ctx.selectionMin.getX(), ctx.selectionMax.getX()),
                Math.max(ctx.selectionMin.getY(), ctx.selectionMax.getY()),
                Math.max(ctx.selectionMin.getZ(), ctx.selectionMax.getZ())
        );

        int x0 = min.getX(), y0 = min.getY(), z0 = min.getZ();
        int x1 = max.getX(), y1 = max.getY(), z1 = max.getZ();

        // 扫四面：west/east/north/south
        scanWallOpenings(world, ctx, out, Direction.WEST,  x0, y0, z0, x0, y1, z1);
        scanWallOpenings(world, ctx, out, Direction.EAST,  x1, y0, z0, x1, y1, z1);
        scanWallOpenings(world, ctx, out, Direction.NORTH, x0, y0, z0, x1, y1, z0);
        scanWallOpenings(world, ctx, out, Direction.SOUTH, x0, y0, z1, x1, y1, z1);

        return out;
    }

    /**
     * 在一个墙平面上找空气矩形洞口。
     * v1：只找"轴对齐矩形洞"。
     */
    private void scanWallOpenings(
            World world,
            SocketQueryContext ctx,
            List<Socket> out,
            Direction normal,
            int ax0, int ay0, int az0,
            int ax1, int ay1, int az1
    ) {
        // 这个面是 x 固定（WEST/EAST）还是 z 固定（NORTH/SOUTH）
        boolean xFixed = (normal == Direction.WEST || normal == Direction.EAST);
        int fixed = xFixed ? ax0 : az0;

        int u0 = xFixed ? az0 : ax0; // u: 沿 z 或 x
        int u1 = xFixed ? az1 : ax1;
        int v0 = ay0;
        int v1 = ay1;

        int maxU = Math.min(u1 - u0 + 1, ctx.openingMaxScan);
        int maxV = Math.min(v1 - v0 + 1, ctx.openingMaxScan);

        // 简单扫描：从低到高，从左到右找"左下角"
        for (int du = 0; du < maxU; du++) {
            for (int dv = 0; dv < maxV; dv++) {

                int u = u0 + du;
                int v = v0 + dv;

                // 以 (u,v) 为潜在左下角
                if (!isAir(world, xFixed, fixed, u, v, normal)) continue;

                // 尝试扩展 w/h
                int w = maxRectWidth(world, xFixed, fixed, u, v, normal, ctx.openingMaxW);
                int h = maxRectHeight(world, xFixed, fixed, u, v, normal, Math.min(ctx.openingMaxH, maxV - dv), w);

                if (w >= ctx.openingMinW && h >= ctx.openingMinH) {
                    // 生成 socket box
                    Box b = openingBox(xFixed, fixed, u, v, w, h, normal);
                    out.add(new Socket(SocketType.WALL_OPENING, b, normal, null));

                    // v1：避免重复识别同一洞口，粗略跳过区域
                    dv += (h - 1);
                    break;
                }
            }
        }
    }

    private boolean isAir(World world, boolean xFixed, int fixed, int u, int v, Direction normal) {
        BlockPos p = xFixed
                ? new BlockPos(fixed, v, u)
                : new BlockPos(u, v, fixed);
        BlockState s = world.getBlockState(p);
        return s.isAir();
    }

    private int maxRectWidth(World world, boolean xFixed, int fixed, int u, int v, Direction normal, int maxW) {
        int w = 0;
        for (int i = 0; i < maxW; i++) {
            int uu = u + i;
            if (!isAir(world, xFixed, fixed, uu, v, normal)) break;
            w++;
        }
        return w;
    }

    private int maxRectHeight(World world, boolean xFixed, int fixed, int u, int v, Direction normal, int maxH, int width) {
        int h = 0;
        for (int j = 0; j < maxH; j++) {
            int vv = v + j;
            // 每一行都必须 width 连续是 air
            boolean ok = true;
            for (int i = 0; i < width; i++) {
                if (!isAir(world, xFixed, fixed, u + i, vv, normal)) {
                    ok = false;
                    break;
                }
            }
            if (!ok) break;
            h++;
        }
        return h;
    }

    private Box openingBox(boolean xFixed, int fixed, int u, int v, int w, int h, Direction normal) {
        // box 要有一点厚度，便于命中/渲染
        double t = 0.15;
        if (xFixed) {
            // x 固定，u 是 z
            int x = fixed;
            int z0 = u;
            int z1 = u + w;
            int y0 = v;
            int y1 = v + h;
            if (normal == Direction.WEST) {
                return new Box(x - t, y0, z0, x + t, y1, z1);
            } else {
                return new Box(x - t, y0, z0, x + t, y1, z1);
            }
        } else {
            // z 固定，u 是 x
            int z = fixed;
            int x0 = u;
            int x1 = u + w;
            int y0 = v;
            int y1 = v + h;
            return new Box(x0, y0, z - t, x1, y1, z + t);
        }
    }
}
