package com.formacraft.server.compiler;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.util.HostedPlateOccupancy;
import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.generation.component.util.GeneratedSurfaceCapture;
import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.server.assembly.AssemblyCirculationConstraints;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

class Oct20FailDiagTest {
    @BeforeAll static void setup() { MinecraftRegistryTestBootstrap.initialize(); }

    @Test void retry3() throws Exception {
        var plan = LlmPlanParser.parse(Files.readString(Path.of(
                "python_backend/tests/fixtures/complex_build_retest/oct20_retry_3.normalized.json")));
        AssemblyCompileDiagnostics.clear();
        var patches = ComponentPlanCompiler.compile(plan);
        System.out.println("retry3 gap=" + AssemblyCompileDiagnostics.get() + " n=" + patches.size());
        long open = patches.stream().filter(p -> p.dx()>=9 && p.dx()<=10 && p.dz()==0 && p.dy()>=5 && p.dy()<=7
                && BlockPatch.REMOVE.equals(p.action())).count();
        System.out.println("terrace removes in bay="+open);
    }

    @Test void roof1() throws Exception {
        var plan = LlmPlanParser.parse(Files.readString(Path.of(
                "python_backend/tests/fixtures/complex_build_retest/oct20_access_1.normalized.json")));
        plan = FlatRoofAttachmentRepair.apply(plan);
        Component roof = null; Component host = null;
        for (var c : plan.components()) {
            if (c == null || c.params() == null) continue;
            String id = String.valueOf(c.params().get("component_id"));
            if ("roof_garden".equals(id)) roof = c;
            if ("tier_5".equals(id)) host = c;
        }
        System.out.println("after snap roof="+roof.relativePosition()+" host="+host.relativePosition()+" h="+host.dimensions().height());
        AssemblyCompileDiagnostics.clear();
        // Full compile but keep patches by calling emit path before coverage check via reflection on prepare
        Method prepare = ComponentPlanCompiler.class.getDeclaredMethod("prepareComponents", LlmPlan.class, boolean.class);
        prepare.setAccessible(true);
        @SuppressWarnings("unchecked")
        var prepared = (List<Component>) prepare.invoke(null, plan, true);
        for (var c : prepared) {
            if (c != null && c.params() != null && "roof_garden".equals(String.valueOf(c.params().get("component_id")))) {
                System.out.println("prepared roof pos="+c.relativePosition()+" dims="+c.dimensions());
                roof = c;
            }
        }
        AssemblyCompileDiagnostics.clear();
        var patches = ComponentPlanCompiler.compile(plan);
        System.out.println("compile gap="+AssemblyCompileDiagnostics.get()+" n="+patches.size());
        // Re-run emit only: compile again is empty. Instead generate roof alone.
        Slot slot = new Slot(roof.slotId(), new Vec3i(0,0,0), GlobalConstraints.Facing.SOUTH, null, null, null);
        var semantic = new SemanticComponent(roof.componentType(), slot, roof, plan.styleProfile(), plan.styleAttributes(), plan.genome());
        var att = FlatRoofCoverageValidator.attachmentMismatch(semantic, prepared, Map.of(slot.slotId(), slot), slot);
        System.out.println("attachment="+att);
        var resolved = FlatRoofCoverageValidator.resolve(semantic, BlockPos.ORIGIN);
        System.out.println("coreSize="+resolved.map(r->r.core().size()).orElse(-1)+" ySample="+resolved.map(r->r.core().iterator().next()).orElse(null));
        var gen = new com.formacraft.common.generation.component.impl.RoofGenerator().generate(semantic);
        System.out.println("roof gen patches="+gen.size());
        Map<BlockPos, BlockPatch> last = new HashMap<>();
        for (var p : gen) last.put(new BlockPos(p.dx(),p.dy(),p.dz()), p);
        int miss=0; BlockPos first=null;
        for (var pos : resolved.get().core()) {
            if (!GeneratedSurfaceCapture.occupied(last.get(pos))) { miss++; if (first==null) first=pos; }
        }
        System.out.println("standalone missing="+miss+" first="+first);
        // Show a few gen Y values
        gen.stream().map(BlockPatch::dy).distinct().sorted().forEach(y -> System.out.println("genY="+y));
    }

    @Test void bridge3() throws Exception {
        var plan = LlmPlanParser.parse(Files.readString(Path.of(
                "python_backend/tests/fixtures/complex_build_retest/oct18_access_3.normalized.json")));
        Map<String, Slot> slots = new HashMap<>();
        for (var s : plan.layout().slots()) slots.put(s.slotId(), s);
        Component plate=null, host=null, target=null;
        for (var c : plan.components()) {
            if (c == null || c.params() == null) continue;
            String id = String.valueOf(c.params().get("component_id"));
            if ("bridge_deck".equals(id)) plate=c;
            if ("workshop_main".equals(id)) host=c;
            if ("dispatch_main".equals(id)) target=c;
        }
        var hostF = ComponentPlanCompiler.inSlotFrame(host, plate.slotId(), slots);
        var targetF = ComponentPlanCompiler.inSlotFrame(target, plate.slotId(), slots);
        System.out.println("plate="+ComponentFootprintUtil.bounds(plate));
        System.out.println("hostF="+ComponentFootprintUtil.bounds(hostF));
        System.out.println("targetF="+ComponentFootprintUtil.bounds(targetF));
        var ph = HostedPlateOccupancy.bridgePortalRemovals(plate, hostF);
        var pt = HostedPlateOccupancy.bridgePortalRemovals(plate, targetF);
        System.out.println("portal host="+ph);
        System.out.println("portal target="+pt);
        System.out.println("touches host="+HostedPlateOccupancy.touchesEndpoint3d(ComponentFootprintUtil.bounds(plate), ComponentFootprintUtil.bounds(hostF), plate.relativePosition().y(), hostF));
        System.out.println("touches target="+HostedPlateOccupancy.touchesEndpoint3d(ComponentFootprintUtil.bounds(plate), ComponentFootprintUtil.bounds(targetF), plate.relativePosition().y(), targetF));
        AssemblyCompileDiagnostics.clear();
        var patches = ComponentPlanCompiler.compile(plan);
        System.out.println("gap="+AssemblyCompileDiagnostics.get()+" n="+patches.size());
        // Did portal removes survive in result before discard? Can't see. Dump contact cells from a manual occupancy if n>0
    }
}