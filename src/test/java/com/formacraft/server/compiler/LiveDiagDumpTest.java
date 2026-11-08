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
    // Live0023 doorway
    var p23 = LlmPlanParser.parse(Files.readString(Path.of("src/test/resources/regressions/ingame_20261028_0023/001437.json")));
    AssemblyCompileDiagnostics.clear();
    var patches = ComponentPlanCompiler.compile(p23, BlockPos.ORIGIN, null, null, false);
    System.out.println("0023 gap="+AssemblyCompileDiagnostics.get()+" patches="+patches.size());
    for (var c: p23.components()) if (c!=null && c.params()!=null)
      System.out.println("  "+c.componentType()+" id="+c.params().get("component_id")+" pos="+c.relativePosition()+" dims="+c.dimensions());
    Map<BlockPos,String> cells=new HashMap<>();
    for (var b: patches) cells.put(new BlockPos(b.dx(),b.dy(),b.dz()), b.targetBlock());
    for(int z=2;z<=4;z++) for(int y=4;y<=5;y++)
      System.out.println("door cell (0,"+y+","+z+")="+cells.get(new BlockPos(0,y,z)));
    // ComplexBuild one exterior conflict
    var json = Files.readString(Path.of("python_backend/tests/fixtures/complex_build_retest/oct17_1.json"));
    var plan = LlmPlanParser.parse(json);
    AssemblyCompileDiagnostics.clear();
    var out = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
    System.out.println("oct17_1 gap="+AssemblyCompileDiagnostics.get()+" patches="+out.size());
  }
}
