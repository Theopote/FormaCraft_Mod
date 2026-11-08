package com.formacraft.server.compiler;
import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
public class LiveDiagDumpTest {
  @BeforeAll static void init(){MinecraftRegistryTestBootstrap.initialize();}
  @Test void dump() throws Exception {
    var plan = LlmPlanParser.parse(Files.readString(Path.of("src/test/resources/regressions/live-0058-pavilions.json")));
    var repaired = PavilionOpenFrameRepair.apply(plan);
    for (var c: repaired.components()) if ("MASS_MAIN".equals(c.componentType()) || "ROOF".equals(c.componentType()))
      System.out.println(c.componentType()+" id="+c.params().get("component_id")+" pos="+c.relativePosition()+" dims="+c.dimensions()+" open="+com.formacraft.common.generation.component.util.OpenFramePolicy.isOpen(c.params())+" anchor="+c.params().get("anchor_mode")+" wall="+c.params().get("wall_type"));
    AssemblyCompileDiagnostics.clear();
    var patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
    Map<BlockPos,String> cells=new HashMap<>();
    for (var p: patches) cells.put(new BlockPos(p.dx(),p.dy(),p.dz()), p.action()+":"+p.targetBlock());
    for (var c: plan.components()) if ("MASS_MAIN".equals(c.componentType())) {
      var a=plan.layout().slots().stream().filter(t->t.slotId().equals(c.slotId())).findFirst().orElseThrow().anchor();
      System.out.println("check id="+c.params().get("component_id")+" slot="+c.slotId()+" a="+a
        +" cell="+cells.get(new BlockPos(a.x(),a.y()+1,a.z()))
        +" deep="+patches.stream().filter(p->p.targetBlock()!=null && p.targetBlock().contains("deepslate") && p.dy()>=a.y()+4 && Math.abs(p.dx()-a.x())<=3 && Math.abs(p.dz()-a.z())<=3).count());
    }
  }
}