"""Proportion card retrieval — AI 比例研究 → LlmPlan proportion_hints."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, List, Optional


def _find_assets_dir() -> Optional[Path]:
    here = Path(__file__).resolve()
    for p in [here] + list(here.parents):
        cand = p / "src" / "main" / "resources" / "assets" / "formacraft"
        if cand.is_dir():
            return cand
    return None


def _str_list(v: Any) -> List[str]:
    if not isinstance(v, list):
        return []
    return [str(x).strip() for x in v if isinstance(x, str) and str(x).strip()]


def _load_cards() -> List[Dict[str, Any]]:
    assets = _find_assets_dir()
    if not assets:
        return []
    card_dir = assets / "proportion_cards"
    if not card_dir.is_dir():
        return []
    out: List[Dict[str, Any]] = []
    for p in sorted(card_dir.glob("*.json")):
        try:
            data = json.loads(p.read_text(encoding="utf-8"))
            if isinstance(data, dict) and data.get("id"):
                out.append(data)
        except Exception:
            continue
    return out


def retrieve_proportion_card(
    prompt: str,
    *,
    proportion_card_id: Optional[str] = None,
) -> Optional[Dict[str, Any]]:
    """
    Match proportion card by explicit id (from culture card) or keyword scoring.
    """
    cards = _load_cards()
    if not cards:
        return None

    if proportion_card_id:
        pid = proportion_card_id.strip()
        for c in cards:
            if str(c.get("id") or "").strip() == pid:
                return _compact_card(c)

    q = (prompt or "").strip().lower()
    if not q:
        return None

    best: Optional[Dict[str, Any]] = None
    best_score = 0.0
    for c in cards:
        score = 0.0
        for kw in _str_list(c.get("matchKeywords")):
            if kw.lower() in q:
                score += max(2.0, len(kw) * 0.5)
        if score > best_score:
            best_score = score
            best = c

    return _compact_card(best) if best and best_score > 0.01 else None


def _compact_card(card: Dict[str, Any]) -> Dict[str, Any]:
    out: Dict[str, Any] = {
        "id": card.get("id"),
        "typology": card.get("typology"),
        "ratios": card.get("ratios"),
        "openingGrammar": card.get("openingGrammar"),
        "aiInstruction": card.get("aiInstruction"),
    }
    return {k: v for k, v in out.items() if v is not None}


def proportion_prompt_block(card: Optional[Dict[str, Any]]) -> str:
    if not card:
        return ""
    lines = [
        "",
        "========================================",
        "PROPORTION ONTOLOGY (research before dimensions)",
        "========================================",
        f"Typology: {card.get('typology', card.get('id'))}",
    ]
    instr = card.get("aiInstruction")
    if isinstance(instr, str) and instr.strip():
        lines.append(instr.strip())
    lines.append("You MUST output proportion_hints in LlmPlan (numeric targets from ratios below).")
    lines.append("Java will clamp dimensions to ratio ranges.")
    ratios = card.get("ratios")
    if isinstance(ratios, dict):
        lines.append("Ratio targets:")
        for name, spec in ratios.items():
            if isinstance(spec, dict):
                ideal = spec.get("ideal")
                mn = spec.get("min")
                mx = spec.get("max")
                desc = spec.get("desc", "")
                lines.append(f"  - {name}: ideal={ideal} range=[{mn},{mx}] {desc}")
    og = card.get("openingGrammar")
    if isinstance(og, dict):
        lines.append("Opening / enclosure grammar:")
        for k, v in og.items():
            lines.append(f"  - {k}: {v}")
    lines.append("")
    return "\n".join(lines)
