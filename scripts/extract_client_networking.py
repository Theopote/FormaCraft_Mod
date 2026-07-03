#!/usr/bin/env python3
"""Extract FormaCraftClientNetworking from FormaCraftNetworking.java."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src/main/java/com/formacraft/common/network/FormaCraftNetworking.java"
OUT = ROOT / "src/main/java/com/formacraft/client/network/FormaCraftClientNetworking.java"

text = SRC.read_text(encoding="utf-8")

start = text.index("    public static void registerS2C() {")
end = text.index("    /** 注册所有 C2S PayloadType")
register_body = text[start:end]

send_start = text.index("    /**\n     * 客户端发送建筑请求")
send_end = text.rindex("    public static void sendComponentGetRequest")
send_end = text.index("    }\n\n}", send_end) + len("    }\n")

send_body = text[send_start:send_end]

payload_refs = [
    "ResponseBuildSpecPayload", "ResponseBuildErrorPayload", "ResponseBuildStatusPayload",
    "PreviewOutlinePayload", "PreviewSkeletonPayload", "PreviewOriginPayload",
    "ClearOutlinePayload", "ComponentCatalogPayload", "ComponentSaveAckPayload",
    "ComponentDefinitionPayload", "PatchPreviewPayload",
    "RequestBuildPayload", "PatchUndoPayload", "PatchRedoPayload", "PatchConfirmPayload",
    "OutlineSyncPayload", "ProtectedZoneSyncPayload", "PreviewAdjustPayload",
    "ComponentCatalogRequestPayload", "ComponentSavePayload", "ComponentGetRequestPayload",
    "RequestPatchPreviewPayload",
]
for name in payload_refs:
    for body in (register_body, send_body):
        body = body.replace(f" {name}", f" FormaCraftNetworking.{name}")
        body = body.replace(f"({name}", f"(FormaCraftNetworking.{name}")
        body = body.replace(f"new {name}", f"new FormaCraftNetworking.{name}")
    register_body = body if body is register_body else register_body
    # fix: apply to both properly
for name in payload_refs:
    register_body = register_body.replace(f" {name}", f" FormaCraftNetworking.{name}")
    register_body = register_body.replace(f"({name}", f"(FormaCraftNetworking.{name}")
    register_body = register_body.replace(f"new {name}", f"new FormaCraftNetworking.{name}")
    send_body = send_body.replace(f" {name}", f" FormaCraftNetworking.{name}")
    send_body = send_body.replace(f"({name}", f"(FormaCraftNetworking.{name}")
    send_body = send_body.replace(f"new {name}", f"new FormaCraftNetworking.{name}")

register_body = register_body.replace("registerPayloadTypesC2S()", "FormaCraftNetworking.registerPayloadTypesC2S()")
register_body = register_body.replace("registerPayloadTypesS2C()", "FormaCraftNetworking.registerPayloadTypesS2C()")
register_body = register_body.replace("[FormaCraftNetworking]", "[FormaCraftClientNetworking]")

header = '''package com.formacraft.client.network;

import com.formacraft.FormacraftMod;
import com.formacraft.common.buildcontext.OutlineShape;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.constraint.ProtectedZone;
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.common.network.ConfirmBuildPacket;
import com.formacraft.common.network.FormaCraftNetworking;
import com.formacraft.common.preview.OutlineBlock;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;

import java.util.List;
import java.util.UUID;

/**
 * 客户端 S2C 接收器与 C2S 发送辅助。
 * Payload 定义见 {@link FormaCraftNetworking}。
 */
public final class FormaCraftClientNetworking {
    private FormaCraftClientNetworking() {}

'''

content = header + register_body + send_body + "\n}\n"
OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(content, encoding="utf-8")
print(f"Wrote {OUT}")

# Trim common file
trim_start = text.index("    public static void registerS2C() {")
trim_end = text.rindex("    public static void sendComponentGetRequest")
trim_end = text.index("    }\n\n}", trim_end) + len("    }\n\n}")
text = text[:trim_start] + text[trim_end:]

for imp in [
    "import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;\n",
    "import net.minecraft.client.MinecraftClient;\n",
    "import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;\n",
    "import com.formacraft.FormacraftMod;\n",
    "import com.formacraft.common.model.build.BuildingSpec;\n",
    "import com.formacraft.common.model.request.FormaRequest;\n",
    "import com.formacraft.common.model.constraint.ProtectedZone;\n",
    "import com.formacraft.common.buildcontext.OutlineShape;\n",
]:
    text = text.replace(imp, "")

text = text.replace(
    " * 服务端 C2S 处理器见 {@link com.formacraft.server.network.FormaCraftServerNetworking}。",
    " * 服务端 C2S 见 {@link com.formacraft.server.network.FormaCraftServerNetworking}；\n"
    " * 客户端 S2C/C2S 发送见 {@link com.formacraft.client.network.FormaCraftClientNetworking}。",
)

SRC.write_text(text, encoding="utf-8")
print(f"Trimmed {SRC}")
