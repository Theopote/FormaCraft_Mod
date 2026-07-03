#!/usr/bin/env python3
"""Strip server-only code from FormaCraftNetworking.java."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src/main/java/com/formacraft/common/network/FormaCraftNetworking.java"
text = SRC.read_text(encoding="utf-8")

# Remove registerC2S through shiftStructure (before registerS2C)
start = text.index("    /**\n     * 注册 C2S 数据包")
end = text.index("    public static void registerS2C() {")
text = text[:start] + text[end:]

# Remove sendComponentSaveAck private method
ack_start = text.index("    private static void sendComponentSaveAck(")
ack_end = text.index("    /**\n     * 客户端发送建筑请求")
text = text[:ack_start] + text[ack_end:]

# Remove server send methods (sendPatchPreview through sendClearOutline)
send_start = text.index("    /** 服务端下发 Patch 预览")
send_end = text.rindex("    public static void sendClearOutline")
send_end = text.index("    }\n}", send_end) + len("    }\n")
text = text[:send_start] + text[send_end:]

# Make payload registration public
text = text.replace("    private static void registerPayloadTypesC2S()", "    public static void registerPayloadTypesC2S()")
text = text.replace("    private static void registerPayloadTypesS2C()", "    public static void registerPayloadTypesS2C()")

# Remove unused server imports
server_imports = [
    "import com.formacraft.server.build.BuildExecutionService;\n",
    "import com.formacraft.server.build.BuildConstraintContext;\n",
    "import com.formacraft.server.build.BuildConstraintClipper;\n",
    "import com.formacraft.server.orchestrator.OrchestratorClient;\n",
    "import com.formacraft.server.compiler.ComponentPlanCompiler;\n",
    "import com.formacraft.server.foundation.FoundationPlanner;\n",
    "import com.formacraft.server.foundation.FoundationType;\n",
    "import com.formacraft.server.preview.PreviewStorage;\n",
    "import com.formacraft.server.terrain.TerrainAdaptationEngine;\n",
    "import com.formacraft.server.terrain.TerrainFit;\n",
    "import com.formacraft.common.build.PlannedBlock;\n",
    "import com.formacraft.common.llm.dto.Component;\n",
    "import com.formacraft.common.llm.dto.Dimensions;\n",
    "import com.formacraft.common.llm.dto.LlmPlan;\n",
    "import com.formacraft.common.llm.dto.Slot;\n",
    "import com.formacraft.common.llm.dto.Vec3i;\n",
    "import com.formacraft.common.llm.parser.LlmPlanParser;\n",
    "import com.formacraft.common.llm.parser.PlanParseException;\n",
    "import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;\n",
    "import net.minecraft.server.network.ServerPlayerEntity;\n",
]
for imp in server_imports:
    text = text.replace(imp, "")

# Remove other unused imports if any
text = text.replace("import java.util.ArrayList;\n", "")
text = text.replace("import java.util.HashMap;\n", "")
text = text.replace("import java.util.Locale;\n", "")
text = text.replace("import java.util.Map;\n", "")
text = text.replace("import java.util.concurrent.CompletableFuture;\n", "")
text = text.replace("import java.util.concurrent.TimeUnit;\n", "")
text = text.replace("import java.util.concurrent.atomic.AtomicBoolean;\n", "")
text = text.replace("import java.util.concurrent.atomic.AtomicReference;\n", "")
text = text.replace("import net.minecraft.block.BlockState;\n", "")
text = text.replace("import net.minecraft.block.Blocks;\n", "")

SRC.write_text(text, encoding="utf-8")
print(f"Trimmed {SRC} -> {len(text)} chars")
