package com.formacraft.server.compiler;
import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
public class LiveDiagDumpTest {
  @BeforeAll static void init(){MinecraftRegistryTestBootstrap.initialize();}
  @Test void dump() throws Exception {
    for (String n : new String[]{"oct17_1","oct16_1.normalized","oct18_2.normalized"}) {
      var plan = LlmPlanParser.parse(Files.readString(Path.of("python_backend/tests/fixtures/complex_build_retest/"+n+".json")));
      AssemblyCompileDiagnostics.clear();
      var out = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
      System.out.println(n+" gap="+AssemblyCompileDiagnostics.get()+" patches="+out.size());
    }
  }
}
