"""Visual analysis from reference images / URLs (PR-4)."""

from __future__ import annotations

import json
import logging
import os
import re
from typing import Any, Callable, Dict, List, Optional

import requests

from ..models.building_profile import BuildingProfile
from ..models.request import BuildRequest, ReferenceInput

logger = logging.getLogger(__name__)

_VISION_ENABLED = lambda: (os.getenv("VISION_REFERENCE") or "on").strip().lower() not in (
    "off", "0", "false", "no",
)
_VISION_TIMEOUT_SEC = lambda: float(os.getenv("VISION_REFERENCE_TIMEOUT_SEC", "20"))
_VISION_MAX_BYTES = int(os.getenv("VISION_MAX_BYTES", "4000000"))


class VisualAnalysis:
    """Vision / URL 分析结果（轻量 dataclass 风格）。"""

    def __init__(
        self,
        *,
        building_name: Optional[str] = None,
        style: Optional[str] = None,
        form_observations: Optional[List[str]] = None,
        massing: Optional[List[str]] = None,
        materials: Optional[List[str]] = None,
        distinctive_elements: Optional[List[str]] = None,
        typical_width_blocks: Optional[int] = None,
        typical_depth_blocks: Optional[int] = None,
        typical_height_blocks: Optional[int] = None,
        confidence: float = 0.5,
        notes: Optional[str] = None,
        sources: Optional[List[Dict[str, str]]] = None,
    ):
        self.building_name = building_name
        self.style = style
        self.form_observations = form_observations or []
        self.massing = massing or []
        self.materials = materials or []
        self.distinctive_elements = distinctive_elements or []
        self.typical_width_blocks = typical_width_blocks
        self.typical_depth_blocks = typical_depth_blocks
        self.typical_height_blocks = typical_height_blocks
        self.confidence = confidence
        self.notes = notes
        self.sources = sources or []

    def to_dict(self) -> Dict[str, Any]:
        return {
            "building_name": self.building_name,
            "style": self.style,
            "form_observations": self.form_observations,
            "massing": self.massing,
            "materials": self.materials,
            "distinctive_elements": self.distinctive_elements,
            "typical_width_blocks": self.typical_width_blocks,
            "typical_depth_blocks": self.typical_depth_blocks,
            "typical_height_blocks": self.typical_height_blocks,
            "confidence": self.confidence,
            "notes": self.notes,
            "sources": self.sources,
        }


def is_vision_reference_enabled() -> bool:
    return _VISION_ENABLED()


def _resolve_vision_model(req: Optional[BuildRequest]) -> str:
    override = (os.getenv("VISION_MODEL") or "").strip()
    if override:
        return override
    if req and getattr(req, "model", None):
        m = str(req.model)
        if any(k in m.lower() for k in ("gpt-4o", "vision", "gemini", "claude")):
            return m
    return "gpt-4o-mini"


def _fetch_web_page_snippet(url: str, max_chars: int = 600) -> str:
    try:
        resp = requests.get(
            url,
            timeout=8,
            headers={"User-Agent": "FormaCraft/1.0 (architecture research)"},
        )
        resp.raise_for_status()
        ctype = (resp.headers.get("content-type") or "").lower()
        if "image" in ctype:
            return f"[image content at {url}]"
        text = resp.text[:8000]
        title_m = re.search(r"<title[^>]*>([^<]+)</title>", text, re.I)
        title = title_m.group(1).strip() if title_m else url
        # strip tags rough
        body = re.sub(r"<script[^>]*>.*?</script>", " ", text, flags=re.I | re.S)
        body = re.sub(r"<style[^>]*>.*?</style>", " ", body, flags=re.I | re.S)
        body = re.sub(r"<[^>]+>", " ", body)
        body = re.sub(r"\s+", " ", body).strip()
        snippet = body[:max_chars]
        return f"{title}: {snippet}"
    except Exception as e:
        logger.warning("Web URL fetch failed for %s: %s", url, e)
        return ""


def _rule_based_visual_from_caption(caption: str, url: str = "") -> VisualAnalysis:
    text = f"{caption} {url}".lower()
    elements: List[str] = []
    for kw in (
        "dome", "tower", "spire", "courtyard", "facade", "glass", "steel",
        "curve", "arch", "pagoda", "圆顶", "塔", "庭院", "玻璃", "曲线",
    ):
        if kw in text:
            elements.append(kw)
    return VisualAnalysis(
        distinctive_elements=elements,
        confidence=0.35 if elements else 0.2,
        notes=caption or url or None,
    )


def analyze_with_vision_llm(
    client: Any,
    model: str,
    image_urls: List[str],
    user_text: str,
    *,
    call_with_timeout: Optional[Callable] = None,
    timeout_sec: float = 20.0,
) -> Optional[VisualAnalysis]:
    if not client or not image_urls:
        return None

    prompt = (
        "Analyze the building(s) in the reference image(s) for Minecraft reconstruction. "
        "Return JSON with keys: building_name, style, form_observations[], massing[], "
        "materials[], distinctive_elements[], typical_width_blocks, typical_depth_blocks, "
        "typical_height_blocks (blocks), confidence (0-1), notes. "
        f"User context: {user_text[:400]}"
    )
    content: List[Dict[str, Any]] = [{"type": "text", "text": prompt}]
    for url in image_urls[:3]:
        content.append({"type": "image_url", "image_url": {"url": url, "detail": "low"}})

    def _call():
        return client.chat.completions.create(
            model=model,
            messages=[{"role": "user", "content": content}],
            response_format={"type": "json_object"},
            temperature=0.2,
            max_tokens=800,
        )

    try:
        if call_with_timeout:
            response = call_with_timeout(_call, timeout_sec)
        else:
            response = _call()
        raw = response.choices[0].message.content
        data = json.loads(raw) if isinstance(raw, str) else {}
        return VisualAnalysis(
            building_name=data.get("building_name"),
            style=data.get("style"),
            form_observations=list(data.get("form_observations") or []),
            massing=list(data.get("massing") or []),
            materials=list(data.get("materials") or []),
            distinctive_elements=list(data.get("distinctive_elements") or []),
            typical_width_blocks=data.get("typical_width_blocks"),
            typical_depth_blocks=data.get("typical_depth_blocks"),
            typical_height_blocks=data.get("typical_height_blocks"),
            confidence=float(data.get("confidence") or 0.65),
            notes=data.get("notes"),
            sources=[{"title": "vision", "url": image_urls[0]}],
        )
    except Exception as e:
        logger.warning("Vision LLM analysis failed: %s", e)
        return None


def analyze_references(
    references: List[ReferenceInput],
    user_text: str = "",
    *,
    req: Optional[BuildRequest] = None,
    call_with_timeout: Optional[Callable] = None,
) -> Optional[VisualAnalysis]:
    """
    分析 references[]；无 LLM 时对 caption/URL 做规则 fallback。
    """
    if not references or not is_vision_reference_enabled():
        return None

    image_urls: List[str] = []
    web_snippets: List[str] = []
    captions: List[str] = []

    for ref in references:
        if ref.caption:
            captions.append(ref.caption)
        img = ref.normalized_image_url()
        if img:
            image_urls.append(img)
        elif ref.is_web_page():
            snippet = _fetch_web_page_snippet(ref.content.strip())
            if snippet:
                web_snippets.append(snippet)

    merged_notes = "\n".join(web_snippets + captions).strip()

    visual: Optional[VisualAnalysis] = None
    if image_urls and req is not None:
        try:
            from ..services.llm_client import get_client, build_config

            client = get_client(req)
            if client:
                cfg = build_config(req, default_model="gpt-4o-mini")
                model = _resolve_vision_model(req)
                visual = analyze_with_vision_llm(
                    client,
                    model,
                    image_urls,
                    user_text,
                    call_with_timeout=call_with_timeout,
                    timeout_sec=_VISION_TIMEOUT_SEC(),
                )
        except Exception as e:
            logger.warning("Vision path unavailable: %s", e)

    if visual is None and merged_notes:
        visual = _rule_based_visual_from_caption(merged_notes)
        visual.sources = [{"title": "web_reference", "url": references[0].content}]

    if visual is None and captions:
        visual = _rule_based_visual_from_caption(" ".join(captions))

    if visual and merged_notes:
        visual.notes = ((visual.notes or "") + "\n" + merged_notes).strip()

    return visual


def merge_visual_into_profile(
    profile: BuildingProfile,
    visual: VisualAnalysis,
) -> BuildingProfile:
    """将 VisualAnalysis 合并进 BuildingProfile。"""
    data = profile.model_dump()
    if visual.building_name and (
        not data["identity"].get("name") or data["identity"]["name"] == "unknown"
    ):
        data["identity"]["name"] = visual.building_name
        profile.query = profile.query or visual.building_name
    if visual.style and not data["identity"].get("style"):
        data["identity"]["style"] = visual.style
    if visual.confidence > float(data["identity"].get("confidence") or 0):
        data["identity"]["confidence"] = max(
            float(data["identity"].get("confidence") or 0),
            visual.confidence,
        )
    if visual.massing:
        data["form"]["massing"] = list(dict.fromkeys(
            (data["form"].get("massing") or []) + visual.massing
        ))
    if visual.form_observations:
        data["form"]["footprint"] = data["form"].get("footprint") or "irregular"
    for field, items in (
        ("distinctive_elements", visual.distinctive_elements),
        ("facade", visual.materials),
    ):
        existing = data["structure"].get(field) or []
        data["structure"][field] = list(dict.fromkeys(existing + items))
    if visual.typical_width_blocks:
        data["scale_hints"]["typical_width_blocks"] = visual.typical_width_blocks
    if visual.typical_depth_blocks:
        data["scale_hints"]["typical_depth_blocks"] = visual.typical_depth_blocks
    if visual.typical_height_blocks:
        data["scale_hints"]["typical_height_blocks"] = visual.typical_height_blocks
    notes = (data.get("research_notes") or "") + "\n[Visual] " + (visual.notes or "")
    data["research_notes"] = notes.strip()
    if visual.sources:
        data["sources"] = (data.get("sources") or []) + visual.sources
    merged = BuildingProfile.model_validate(data)
    if visual.building_name and not merged.query:
        merged.query = visual.building_name
    return merged
