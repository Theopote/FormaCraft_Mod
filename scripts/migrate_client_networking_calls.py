#!/usr/bin/env python3
"""Point client callers at FormaCraftClientNetworking for register/send APIs."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src/main/java"

SEND_METHODS = [
    "sendBuildRequest",
    "sendConfirmBuild",
    "sendPatchUndo",
    "sendPatchRedo",
    "sendPatchConfirm",
    "sendRequestPatchPreview",
    "sendOutlineSync",
    "sendProtectedZoneSync",
    "sendPreviewAdjust",
    "sendComponentCatalogRequest",
    "sendSaveComponent",
    "sendComponentGetRequest",
]

for path in SRC.rglob("*.java"):
    rel = path.relative_to(SRC)
    if "com/formacraft/client" not in str(rel).replace("\\", "/"):
        continue
    text = path.read_text(encoding="utf-8")
    original = text

    text = text.replace("FormaCraftNetworking.registerS2C()", "FormaCraftClientNetworking.registerS2C()")
    for m in SEND_METHODS:
        text = text.replace(f"FormaCraftNetworking.{m}", f"FormaCraftClientNetworking.{m}")

    if text == original:
        continue

    if "import com.formacraft.client.network.FormaCraftClientNetworking;" not in text:
        if "import com.formacraft.common.network.FormaCraftNetworking;" in text:
            text = text.replace(
                "import com.formacraft.common.network.FormaCraftNetworking;",
                "import com.formacraft.client.network.FormaCraftClientNetworking;\n"
                "import com.formacraft.common.network.FormaCraftNetworking;",
            )
        else:
            pkg_end = text.index(";") + 1
            text = (
                text[:pkg_end]
                + "\n\nimport com.formacraft.client.network.FormaCraftClientNetworking;"
                + text[pkg_end:]
            )

    path.write_text(text, encoding="utf-8")
    print(f"updated {rel}")
