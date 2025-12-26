import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


@dataclass
class StyleProfileMeta:
    display_name: str = ""
    family: str = ""
    tags: List[str] = None
    description: str = ""


@dataclass
class StyleProfileDef:
    style_id: str
    meta: StyleProfileMeta
    defaults: Dict[str, Any]
    constraints: Dict[str, Any]


_LOADED: bool = False
_CATALOG: Dict[str, StyleProfileDef] = {}


def _default_json_path() -> Path:
    # repo_root/python_backend/app/services -> repo_root
    repo_root = Path(__file__).resolve().parents[3]
    return (
        repo_root
        / "src"
        / "main"
        / "resources"
        / "assets"
        / "formacraft"
        / "style_profiles"
        / "style_profile_catalog_v1.json"
    )


def ensure_loaded(path: Optional[Path] = None) -> None:
    global _LOADED, _CATALOG
    if _LOADED:
        return

    p = path or _default_json_path()
    try:
        raw = json.loads(p.read_text(encoding="utf-8"))
        profiles = raw.get("profiles") or {}
        out: Dict[str, StyleProfileDef] = {}
        for style_id, v in profiles.items():
            if not style_id:
                continue
            meta_in = (v or {}).get("meta") or {}
            meta = StyleProfileMeta(
                display_name=str(meta_in.get("display_name") or ""),
                family=str(meta_in.get("family") or ""),
                tags=list(meta_in.get("tags") or []),
                description=str(meta_in.get("description") or ""),
            )
            defaults = (v or {}).get("defaults") or {}
            constraints = (v or {}).get("constraints") or {}
            out[style_id] = StyleProfileDef(
                style_id=style_id,
                meta=meta,
                defaults=defaults,
                constraints=constraints,
            )
        _CATALOG = out
    except Exception:
        _CATALOG = {}
    finally:
        _LOADED = True


def list_style_profiles() -> List[StyleProfileDef]:
    ensure_loaded()
    return list(_CATALOG.values())


def get_style_profile(style_id: str) -> Optional[StyleProfileDef]:
    ensure_loaded()
    return _CATALOG.get(style_id)


def catalog_prompt_block(max_items: int = 30) -> str:
    """
    A compact prompt block that lets the LLM pick a styleProfileId deterministically.
    Keep it short to reduce token pressure; it's meant as a controlled "multiple-choice" list.
    """
    ensure_loaded()
    if not _CATALOG:
        return ""

    items = list(_CATALOG.values())
    items.sort(key=lambda d: d.style_id.lower())
    items = items[: max(1, max_items)]

    lines: List[str] = []
    lines.append("StyleProfileCatalog (choose at most ONE styleProfileId when relevant):")
    for d in items:
        tags = ",".join(d.meta.tags or [])
        fam = d.meta.family or ""
        dn = d.meta.display_name or ""
        lines.append(f"- {d.style_id} | {fam} | {dn} | tags=[{tags}]")
    lines.append("If you choose one, set: extra.styleProfileId = <style_id> (string).")
    return "\n".join(lines)


