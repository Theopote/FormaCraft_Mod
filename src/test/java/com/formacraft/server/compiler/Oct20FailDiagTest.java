package com.formacraft.server.compiler;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.RoofGenerator;
import com.formacraft.common.generation.component.util.GeneratedSurfaceCapture;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

class Oct20FailDiagTest {
    @BeforeAll
    static void setup() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void bridge3() throws Exception {
        var plan = LlmPlanParser.parse(Files.readString(Path.of(
                "python_backend/tests/fixtures/complex_build_retest/oct18_access_3.normalized.json")));
        AssemblyCompileDiagnostics.clear();
        var patches = ComponentPlanCompiler.compile(plan);
        System.out.println("bridge gap=" + AssemblyCompileDiagnostics.get() + " n=" + patches.size());
    }

    @Test
    void roof1() throws Exception {
        var plan = LlmPlanParser.parse(Files.readString(Path.of(
                "python_backend/tests/fixtures/complex_build_retest/oct20_access_1.normalized.json")));
        plan = FlatRoofAttachmentRepair.apply(plan);
        Component roof = null;
        for (var c : plan.components()) {
            if (c != null && c.params() != null
                    && "roof_garden".equals(String.valueOf(c.params().get("component_id")))) {
                roof = c;
            }
        }
        System.out.println("snapped roof=" + roof.relativePosition());
        Slot slot = new Slot(roof.slotId(), new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH, null, null, null);
        var semantic = new SemanticComponent(
                roof.componentType(), slot, roof, plan.styleProfile(), plan.styleAttributes(), plan.genome());
        var resolved = FlatRoofCoverageValidator.resolve(semantic, BlockPos.ORIGIN).orElseThrow();
        var gen = new RoofGenerator().generate(semantic);
        System.out.println("core=" + resolved.core().size() + " gen=" + gen.size());
        Map<BlockPos, BlockPatch> last = new HashMap<>();
        for (var p : gen) {
            last.put(new BlockPos(p.dx(), p.dy(), p.dz()), p);
        }
        int miss = 0;
        BlockPos first = null;
        for (var pos : resolved.core()) {
            if (!GeneratedSurfaceCapture.occupied(last.get(pos))) {
                miss++;
                if (first == null) {
                    first = pos;
                }
                if (miss <= 8) {
                    System.out.println("miss " + pos + " patch=" + last.get(pos));
                }
            }
        }
        System.out.println("standalone missing=" + miss + " first=" + first);
        AssemblyCompileDiagnostics.clear();
        var patches = ComponentPlanCompiler.compile(plan);
        System.out.println("compile gap=" + AssemblyCompileDiagnostics.get() + " n=" + patches.size());
    }
}
