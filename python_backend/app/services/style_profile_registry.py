import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional


def _normalize_text(s: Optional[str]) -> str:
    if not s:
        return ""
    return str(s).strip().lower()


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

_PALETTES_LOADED: bool = False
_PALETTE_IDS: set[str] = set()


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


def has_style_profile(style_id: Optional[str]) -> bool:
    s = _normalize_text(style_id)
    if not s:
        return False
    ensure_loaded()
    # style IDs are case-sensitive in data, but callers may pass exact IDs; keep strict check first.
    if style_id in _CATALOG:
        return True
    # fallback: case-insensitive match (defensive against model output drift)
    sl = s.lower()
    return any(k.lower() == sl for k in _CATALOG.keys())


def default_palette_for_style(style_id: Optional[str]) -> Optional[str]:
    """
    Extract default palette id from style profile defaults.components.
    Supports aliases: palette_id / paletteId / palette
    """
    if not style_id:
        return None
    ensure_loaded()
    d = _CATALOG.get(style_id)
    if d is None:
        # case-insensitive fallback
        sl = _normalize_text(style_id)
        for k, v in _CATALOG.items():
            if k.lower() == sl.lower():
                d = v
                break
    if d is None:
        return None

    try:
        comps = (d.defaults or {}).get("components") or {}
        pid = comps.get("palette_id") or comps.get("paletteId") or comps.get("palette")
        if pid is None:
            return None
        s = str(pid).strip()
        return s if s else None
    except Exception:
        return None


def _default_palette_json_path() -> Path:
    repo_root = Path(__file__).resolve().parents[3]
    return (
        repo_root
        / "src"
        / "main"
        / "resources"
        / "assets"
        / "formacraft"
        / "palettes"
        / "palette_catalog_v1.json"
    )


def ensure_palettes_loaded(path: Optional[Path] = None) -> None:
    global _PALETTES_LOADED, _PALETTE_IDS
    if _PALETTES_LOADED:
        return
    p = path or _default_palette_json_path()
    try:
        raw = json.loads(p.read_text(encoding="utf-8"))
        palettes = raw.get("palettes") or {}
        _PALETTE_IDS = {str(k) for k in palettes.keys() if k is not None and str(k).strip()}
    except Exception:
        _PALETTE_IDS = set()
    finally:
        _PALETTES_LOADED = True


def has_palette(palette_id: Optional[str]) -> bool:
    s = _normalize_text(palette_id)
    if not s:
        return False
    ensure_palettes_loaded()
    if palette_id in _PALETTE_IDS:
        return True
    sl = s.lower()
    return any(k.lower() == sl for k in _PALETTE_IDS)


_ALIAS_GROUPS: list[tuple[list[str], list[str]]] = [
    # 说明：左边是用户可能说的关键词（中英混合），右边是我们希望匹配到 catalog/palette meta 的“canonical tokens”。
    (["赛博", "cyber", "neon", "霓虹"], ["cyber", "cyberpunk", "neon", "futuristic"]),
    (["蒸汽", "steampunk", "齿轮", "铜", "黄铜"], ["steampunk", "industrial", "copper"]),
    (["工业", "factory", "steel", "钢", "铁", "仓库"], ["industrial", "steel", "factory"]),
    (["精灵", "elven", "elf", "森林", "树屋", "自然"], ["elven", "organic", "fantasy", "forest"]),
    (["中式", "中国", "唐", "宋", "明", "清", "宫殿", "牌坊", "四合院", "园林"], ["asian", "chinese", "imperial", "courtyard"]),
    (["日式", "日本", "和风", "神社", "寺", "鸟居"], ["asian", "japanese", "shrine"]),
    (["城堡", "堡垒", "要塞", "castle", "fortress", "medieval", "中世纪"], ["castle", "fortress", "medieval"]),
    (["哥特", "gothic", "教堂", "cathedral"], ["gothic", "cathedral", "medieval"]),
    (["古典", "希腊", "罗马", "classical", "marble", "大理石"], ["classical", "greco", "roman", "marble"]),
    (["现代", "modern", "玻璃", "写字楼", "office", "国际主义"], ["modern", "glass", "international"]),
]


def _expand_query_tokens(query_text: Optional[str]) -> List[str]:
    q = _normalize_text(query_text)
    if not q:
        return []

    tokens = set(re.findall(r"[a-z0-9_]{2,}", q))
    # 额外：把常见中文关键词映射到可匹配的英文 token（避免 catalog/palette 主要为英文时匹配不到）
    for aliases, adds in _ALIAS_GROUPS:
        if any(a.lower() in q for a in aliases):
            for t in adds:
                tokens.add(t.lower())

    # 防止 token 过多导致计算无意义；保留最多 40 个
    out = list(tokens)
    out.sort()
    return out[:40]


def _score_text_match(query_tokens: List[str], hay: str, strong_needles: Optional[List[str]] = None) -> int:
    if not query_tokens or not hay:
        return 0
    h = hay
    score = 0

    # strong needles（例如 style_id / palette_id）命中应显著加分
    if strong_needles:
        for n in strong_needles:
            nn = _normalize_text(n)
            if nn and nn in h:
                score += 10

    for t in query_tokens:
        if t and t in h:
            score += 1
    return score


def catalog_prompt_block(max_items: int = 30, query_text: Optional[str] = None) -> str:
    """
    A compact prompt block that lets the LLM pick a styleProfileId deterministically.
    Keep it short to reduce token pressure; it's meant as a controlled "multiple-choice" list.
    """
    ensure_loaded()
    if not _CATALOG:
        return ""

    items = list(_CATALOG.values())
    qtokens = _expand_query_tokens(query_text)

    if qtokens:
        scored: List[tuple[int, StyleProfileDef]] = []
        for d in items:
            hay = " ".join([
                _normalize_text(d.style_id),
                _normalize_text(d.meta.display_name),
                _normalize_text(d.meta.family),
                _normalize_text(d.meta.description),
                _normalize_text(",".join(d.meta.tags or [])),
            ])
            s = _score_text_match(qtokens, hay, strong_needles=[d.style_id])
            # tags 命中额外加权（更稳定）
            for tg in (d.meta.tags or []):
                tt = _normalize_text(tg)
                if tt and tt in qtokens:
                    s += 2
            scored.append((s, d))

        scored.sort(key=lambda x: (-x[0], x[1].style_id.lower()))
        # 如果全部为 0，则回退到字母序（避免“胡乱筛掉一堆”）
        if scored and scored[0][0] > 0:
            items = [d for s, d in scored[: max(1, max_items)]]
        else:
            items.sort(key=lambda d: d.style_id.lower())
            items = items[: max(1, max_items)]
    else:
        items.sort(key=lambda d: d.style_id.lower())
        items = items[: max(1, max_items)]

    lines: List[str] = []
    lines.append("StyleProfileCatalog (choose at most ONE styleProfileId when relevant):")
    for d in items:
        tags = ",".join(d.meta.tags or [])
        fam = d.meta.family or ""
        dn = d.meta.display_name or ""
        # Default palette hint (lets the model pick styleProfileId and rely on palette fallback deterministically)
        palette_hint = ""
        try:
            comps = (d.defaults or {}).get("components") or {}
            pid = comps.get("palette_id") or comps.get("paletteId") or comps.get("palette")
            if pid is not None:
                pid_s = str(pid).strip()
                if pid_s:
                    palette_hint = pid_s
        except Exception:
            palette_hint = ""

        if palette_hint:
            lines.append(f"- {d.style_id} | {fam} | {dn} | tags=[{tags}] | defaultPalette={palette_hint}")
        else:
            lines.append(f"- {d.style_id} | {fam} | {dn} | tags=[{tags}]")
    lines.append("If you choose one, set: extra.styleProfileId = <style_id> (string).")
    return "\n".join(lines)


def palette_prompt_block(max_items: int = 30, query_text: Optional[str] = None) -> str:
    """
    Provide palette candidates (weighted block palettes) for semantic-part-driven generators.
    """
    # Palette catalog is currently stored in the Java resources tree; load it similarly.
    repo_root = Path(__file__).resolve().parents[3]
    p = (
        repo_root
        / "src"
        / "main"
        / "resources"
        / "assets"
        / "formacraft"
        / "palettes"
        / "palette_catalog_v1.json"
    )
    try:
        raw = json.loads(p.read_text(encoding="utf-8"))
        palettes = raw.get("palettes") or {}
        if not palettes:
            return ""

        ids = [str(k) for k in palettes.keys()]
        qtokens = _expand_query_tokens(query_text)

        if qtokens:
            scored: List[tuple[int, str]] = []
            for pid in ids:
                meta = (palettes.get(pid) or {}).get("meta") or {}
                dn = str(meta.get("display_name") or "")
                tags_list = list(meta.get("tags") or [])
                hay = " ".join([
                    _normalize_text(pid),
                    _normalize_text(dn),
                    _normalize_text(",".join([str(t) for t in tags_list])),
                ])
                s = _score_text_match(qtokens, hay, strong_needles=[pid])
                # tag 命中加权
                for tg in tags_list:
                    tt = _normalize_text(tg)
                    if tt and tt in qtokens:
                        s += 2
                scored.append((s, pid))

            scored.sort(key=lambda x: (-x[0], x[1].lower()))
            if scored and scored[0][0] > 0:
                ids = [pid for s, pid in scored[: max(1, max_items)]]
            else:
                ids = sorted(ids)[: max(1, max_items)]
        else:
            ids = sorted(ids)[: max(1, max_items)]

        lines: List[str] = []
        lines.append("PaletteCatalog (optional): choose at most ONE paletteId when you want material variation/aging.")
        for pid in ids:
            meta = (palettes.get(pid) or {}).get("meta") or {}
            dn = str(meta.get("display_name") or "")
            tags = ",".join(list(meta.get("tags") or []))
            lines.append(f"- {pid} | {dn} | tags=[{tags}]")
        lines.append("If you choose one, set: extra.paletteId = <palette_id> (string).")
        return "\n".join(lines)
    except Exception:
        return ""


