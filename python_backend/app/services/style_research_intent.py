"""Style research is independent of named-landmark classification."""
from __future__ import annotations

import re

from .style_identity import canonical_style_id, identity_catalog


def requested_styles(text: str) -> list[str]:
    """Conservative lexical extraction; keep unfamiliar names instead of guessing a preset."""
    names = []
    for identity, aliases in identity_catalog()["lexical_aliases"].items():
        if any(re.search(re.escape(alias), text, re.I) for alias in [identity, *aliases]):
            names.append(identity)
    patterns = (
        r"(?:采用|使用|按照|按|以|建造|生成|建一栋|建一座)\s*[‘“\"']?([^，。；\n‘’“”\"']{1,40}?)(?:建筑)?风格",
        r"[‘“\"']([^‘’“”\"']{1,60})[’”\"']\s*(?:风格|style)",
        r"\b([\wÀ-ž-]+(?:\s+[\wÀ-ž-]+){0,3})[- ]style\b",
    )
    for pattern in patterns:
        for match in re.finditer(pattern, text, re.I):
            name = re.sub(r"^(?:(?:build|create|a|an|in|the)\s+)+", "", match.group(1).strip(), flags=re.I)
            # Aliases already found inside a phrase need no second identity.
            if not any(alias.casefold() in name.casefold()
                       for aliases in identity_catalog()["lexical_aliases"].values()
                       for alias in aliases):
                names.append(canonical_style_id(name))
    return list(dict.fromkeys(names))


def style_research_subject(text: str) -> str:
    from .building_research_agent import _is_edit_or_patch_prompt

    if _is_edit_or_patch_prompt(text):
        return ""
    return "; ".join(requested_styles(text))
