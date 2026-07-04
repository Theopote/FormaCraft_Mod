"""Patch edit eval helpers for golden_eval."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

_ASSETS = None

_RED_TOKENS = frozenset({"red", "crimson", "scarlet", "maroon", "terracotta", "红色", "红"})


def _assets_dir() -> Optional[Path]:
    global _ASSETS
    if _ASSETS is not None:
        return _ASSETS
    here = Path(__file__).resolve()
    for p in [here] + list(here.parents):
        cand = p / "src" / "main" / "resources" / "assets" / "formacraft"
        if cand.is_dir():
            _ASSETS = cand
            return cand
    return None


def load_patch_card(card_id: str) -> Optional[Dict[str, Any]]:
    assets = _assets_dir()
    if not assets:
        return None
    path = assets / "patch_cards" / f"{card_id}.json"
    if not path.is_file():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else None
    except Exception:
        return None


def resolve_patch_card_id(prompt: Optional[str]) -> Optional[str]:
    if not prompt:
        return None
    q = prompt.lower()
    if any(k in q for k in ("换屋顶", "屋顶换成", "改成红色", "换成红色", "红色屋顶", "red roof", "change roof")):
        return "roof_color_change"
    return None


def _components(plan: Dict[str, Any]) -> List[Dict[str, Any]]:
    comps = plan.get("components")
    return [c for c in comps if isinstance(c, dict)] if isinstance(comps, list) else []


def _is_red(value: Any) -> bool:
    if not isinstance(value, str):
        return False
    lower = value.strip().lower()
    return any(tok in lower for tok in _RED_TOKENS)


def evaluate_patch_intent(
    plan: Dict[str, Any],
    prompt: Optional[str] = None,
) -> List[Tuple[str, bool, str]]:
    """Returns list of (name, passed, detail) for mode=patch plans."""
    if plan.get("mode") != "patch":
        return []

    checks: List[Tuple[str, bool, str]] = []
    comps = _components(plan)
    types = [str(c.get("component_type") or "").upper() for c in comps]

    checks.append((
        "patch_mode",
        True,
        "mode=patch",
    ))

    has_block_patch = isinstance(plan.get("patch"), dict) and bool(
        (plan.get("patch") or {}).get("blocks")
    )
    checks.append((
        "patch_has_ops",
        bool(comps) or has_block_patch,
        "components[] or patch.blocks[] present",
    ))

    q = (prompt or "").lower()
    is_roof_edit = any(
        k in q for k in (
            "屋顶", "roof", "换成红色", "改成红色", "red roof", "recolor roof",
        )
    )

    if is_roof_edit:
        has_roof = any(t.startswith("ROOF") or t == "EAVE" for t in types)
        checks.append((
            "patch_targets_roof",
            has_roof or has_block_patch,
            f"roof edit expects ROOF component or patch.blocks; types={types}",
        ))

        style = plan.get("style_attributes") if isinstance(plan.get("style_attributes"), dict) else {}
        roof_color = style.get("roof_color", style.get("roofColor"))
        params_red = False
        for c in comps:
            params = c.get("params") if isinstance(c.get("params"), dict) else {}
            if _is_red(params.get("roof_color")) or _is_red(params.get("color")):
                params_red = True
        wants_red = "红" in q or "red" in q
        if wants_red:
            checks.append((
                "patch_roof_color_red",
                _is_red(roof_color) or params_red,
                f"style_attributes.roof_color={roof_color!r} params_red={params_red}",
            ))

        max_components = 3
        card_id = resolve_patch_card_id(prompt)
        if card_id:
            card = load_patch_card(card_id)
            if card and isinstance(card.get("rules"), dict):
                mc = card["rules"].get("max_components")
                if isinstance(mc, int) and mc > 0:
                    max_components = mc
        checks.append((
            "patch_minimal_scope",
            len(comps) <= max_components,
            f"patch should be incremental: components={len(comps)} max={max_components}",
        ))

        forbidden = ("MASS_MAIN", "ENTRANCE", "FACADE_WINDOWS", "MODULE")
        has_forbidden = any(t in forbidden for t in types)
        checks.append((
            "patch_no_full_rebuild",
            not has_forbidden,
            f"patch should not rebuild full house; forbidden present={has_forbidden} types={types}",
        ))

    return checks
