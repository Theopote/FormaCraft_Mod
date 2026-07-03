#!/usr/bin/env python3
"""One-shot: extract FormaCraftServerNetworking from FormaCraftNetworking.java."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src/main/java/com/formacraft/common/network/FormaCraftNetworking.java"
OUT = ROOT / "src/main/java/com/formacraft/server/network/FormaCraftServerNetworking.java"

text = SRC.read_text(encoding="utf-8")

# Extract registerC2S body (between first { after registerC2S and matching closing before shiftStructure)
start = text.index("    public static void registerC2S() {")
end = text.index("    private static com.formacraft.common.build.GeneratedStructure shiftStructure(")
register_body = text[start:end]

# Extract shiftStructure
shift_start = end
shift_end = text.index("    public static void registerS2C() {")
shift_body = text[shift_start:shift_end]

# Extract sendComponentSaveAck
ack_start = text.index("    private static void sendComponentSaveAck(")
ack_end = text.index("    /**\n     * 客户端发送建筑请求")
ack_body = text[ack_start:ack_end]

# Extract server send methods
send_start = text.index("    /** 服务端下发 Patch 预览")
send_end = text.rindex("    public static void sendClearOutline")
send_end = text.index("    }\n}", send_end) + len("    }\n")

send_body = text[send_start:send_end]

payload_refs = [
    "PatchUndoPayload", "PatchRedoPayload", "PatchConfirmPayload", "OutlineSyncPayload",
    "ProtectedZoneSyncPayload", "RequestPatchPreviewPayload", "ComponentSavePayload",
    "ComponentCatalogRequestPayload", "ComponentGetRequestPayload", "PreviewAdjustPayload",
    "ResponseBuildStatusPayload", "ComponentCatalogPayload", "ComponentDefinitionPayload",
    "PatchPreviewPayload", "PreviewOutlinePayload", "PreviewOriginPayload",
    "PreviewSkeletonPayload", "ClearOutlinePayload", "ComponentSaveAckPayload",
]
for name in payload_refs:
    register_body = register_body.replace(f" {name}", f" FormaCraftNetworking.{name}")
    register_body = register_body.replace(f"({name}", f"(FormaCraftNetworking.{name}")
    register_body = register_body.replace(f"new {name}", f"new FormaCraftNetworking.{name}")
    shift_body = shift_body.replace(f"new {name}", f"new FormaCraftNetworking.{name}")
    ack_body = ack_body.replace(f"new {name}", f"new FormaCraftNetworking.{name}")
    send_body = send_body.replace(f"new {name}", f"new FormaCraftNetworking.{name}")

register_body = register_body.replace("registerPayloadTypesC2S()", "FormaCraftNetworking.registerPayloadTypesC2S()")
register_body = register_body.replace("registerPayloadTypesS2C()", "FormaCraftNetworking.registerPayloadTypesS2C()")
register_body = register_body.replace("[FormaCraftNetworking]", "[FormaCraftServerNetworking]")

header = '''package com.formacraft.server.network;

import com.formacraft.FormacraftMod;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.component.ComponentCatalog;
import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.ComponentStorage;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.constraint.ProtectedZone;
import com.formacraft.common.network.ConfirmBuildPacket;
import com.formacraft.common.network.FormaCraftNetworking;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.preview.OutlineBlock;
import com.formacraft.server.build.BuildExecutionService;
import com.formacraft.server.preview.PreviewStorage;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端 C2S 处理器与 S2C 发送辅助方法。
 * Payload 定义与 codec 留在 {@link FormaCraftNetworking}。
 */
public final class FormaCraftServerNetworking {
    private FormaCraftServerNetworking() {}

'''

register_body = register_body.replace(
    "    public static void registerC2S() {",
    "    public static void registerC2S() {",
)

footer = "\n}\n"
content = header + register_body + shift_body + ack_body + send_body + footer
OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(content, encoding="utf-8")
print(f"Wrote {OUT} ({len(content)} chars)")
