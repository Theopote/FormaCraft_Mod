#!/usr/bin/env python3
"""Rename model.ComponentVariant -> PersistedComponentVariant across Java sources."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src/main/java"

MODEL_IMPORT = "import com.formacraft.common.component.model.ComponentVariant;"
MODEL_IMPORT_NEW = "import com.formacraft.common.component.model.PersistedComponentVariant;"

for path in SRC.rglob("*.java"):
    text = path.read_text(encoding="utf-8")
    original = text

    if MODEL_IMPORT in text:
        text = text.replace(MODEL_IMPORT, MODEL_IMPORT_NEW)

    rel = str(path.relative_to(SRC)).replace("\\", "/")
    in_model = rel.startswith("com/formacraft/common/component/model/")

    if in_model and path.name not in ("PersistedComponentVariant.java",):
        # Same-package model types: unqualified ComponentVariant -> PersistedComponentVariant
        # Avoid touching variant/ runtime class references (none in model pkg except comments)
        import re
        text = re.sub(r"\bComponentVariant\b", "PersistedComponentVariant", text)

    if "com.formacraft.common.component.model.ComponentVariant" in text:
        text = text.replace(
            "com.formacraft.common.component.model.ComponentVariant",
            "com.formacraft.common.component.model.PersistedComponentVariant",
        )

    if path.name in (
        "ComponentVariantCompiler.java",
        "SegmentScaler.java",
        "ComponentVariantAdapter.java",
        "ComponentVoxelizer.java",
        "ComponentPlanCompiler.java",
    ):
        text = text.replace("ComponentVariant variant", "PersistedComponentVariant variant")
        text = text.replace("ComponentVariant oldVariant", "PersistedComponentVariant oldVariant")
        text = text.replace("new ComponentVariant()", "new PersistedComponentVariant()")
        text = text.replace("ComponentVariant.Params", "PersistedComponentVariant.Params")

    if path.name == "ComponentPrototypeStorage.java":
        text = text.replace("ComponentVariant.class", "PersistedComponentVariant.class")
        text = text.replace("ComponentVariant[]", "PersistedComponentVariant[]")

    if text != original:
        path.write_text(text, encoding="utf-8")
        print(f"updated {rel}")
