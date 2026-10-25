package com.formacraft.server.compiler;

import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.BlockPatchTargetResolver;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

class DiagnoseClearanceTest {
    @BeforeAll static void init() { MinecraftRegistryTestBootstrap.initialize(); }

    @Test void dump() throws Exception {
        String name = "oct16_1.normalized";
        var json = Files.readString(Path.of("python_backend/tests/fixtures/complex_build_retest/" + name + ".json"));
        var plan = LlmPlanParser.parse(json);
        var dbg = ComponentPlanCompiler.compileDebug(plan);
        System.out.println("flights=" + dbg.flights().size() + " gap=" + dbg.gap().message());
        for (int i = 0; i < dbg.flights().size(); i++) {
            var f = dbg.flights().get(i);
            System.out.println("flight " + (i+1) + " occ=" + f.occupied().size() + " clr=" + f.clearance().size());
            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            for (var p : f.occupied()) { minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY()); }
            System.out.println("  occY=" + minY + ".." + maxY);
            // sample occ bbox
            int minX=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,minZ=Integer.MAX_VALUE,maxZ=Integer.MIN_VALUE;
            for (var p : f.occupied()) {
                minX=Math.min(minX,p.getX()); maxX=Math.max(maxX,p.getX());
                minZ=Math.min(minZ,p.getZ()); maxZ=Math.max(maxZ,p.getZ());
            }
            System.out.println("  occXZ=[" + minX + ".." + maxX + "," + minZ + ".." + maxZ + "]");
        }
        // Focus blocked cell
        BlockPos blocked = new BlockPos(0, 4, -1); // from first failure message oct16_1
        // actual from earlier run for 5.normalized was -24,4,3; oct16_1 was 0,4,-1
        for (BlockPos b : List.of(new BlockPos(0, 4, -1), new BlockPos(-24, 4, 3))) {
            System.out.println("cell " + b.toShortString());
            for (int i = 0; i < dbg.flights().size(); i++) {
                var f = dbg.flights().get(i);
                if (f.clearance().contains(b)) System.out.println("  clr flight " + (i+1));
                if (f.occupied().contains(b)) System.out.println("  occ flight " + (i+1));
                // nearby treads of this flight
                if (f.clearance().contains(b)) {
                    for (var t : f.occupied()) {
                        if (Math.abs(t.getX()-b.getX())<=1 && Math.abs(t.getZ()-b.getZ())<=1
                                && t.getY() < b.getY() && b.getY() - t.getY() <= 3) {
                            System.out.println("  nearby tread " + t.toShortString() + " dy=" + (b.getY()-t.getY()));
                        }
                    }
                }
            }
            // final patch
            BlockPatch last = null;
            for (var p : dbg.patches()) {
                if (p.dx()==b.getX() && p.dy()==b.getY() && p.dz()==b.getZ()) last = p;
            }
            System.out.println("  final=" + (last==null?"MISSING":last.targetBlock()+" src="+last.source()));
        }
        // Check: for each earlier clr cell filled by later occ, is it standing headroom (directly above some tread)?
        int headroomHits = 0, nonHeadroomHits = 0;
        for (int i = 0; i < dbg.flights().size(); i++) {
            for (int j = i + 1; j < dbg.flights().size(); j++) {
                for (BlockPos pos : dbg.flights().get(i).clearance()) {
                    if (!dbg.flights().get(j).occupied().contains(pos)) continue;
                    boolean aboveTread = false;
                    for (BlockPos t : dbg.flights().get(i).occupied()) {
                        if (t.getX()==pos.getX() && t.getZ()==pos.getZ()
                                && pos.getY() > t.getY() && pos.getY() <= t.getY() + 2) {
                            aboveTread = true; break;
                        }
                    }
                    if (aboveTread) headroomHits++; else nonHeadroomHits++;
                }
            }
        }
        System.out.println("laterOccOnEarlierClr headroom=" + headroomHits + " nonHeadroom=" + nonHeadroomHits);
    }
}