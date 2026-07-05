"""Visual analysis from reference images / URLs / pre-computed JSON (PR-4)."""

from __future__ import annotations

import json
import logging
import os
import re
from typing import Any, Callable, Dict, List, Optional

from ..models.building_profile import BuildingProfile
from ..models.reference_blueprint import (
    REFERENCE_BLUEPRINT_VISION_PROMPT,
    ReferenceBlueprint,
    parse_reference_blueprint,
)
from ..models.request import BuildRequest, ReferenceInput
from .url_safety import UnsafeUrlError, safe_http_get, validate_reference_url

logger = logging.getLogger(__name__)

_VISION_ENABLED = lambda: (os.getenv("VISION_REFERENCE") or "on").strip().lower() not in (
    "off", "0", "false", "no",
)
_VISION_TIMEOUT_SEC = lambda: float(os.getenv("VISION_REFERENCE_TIMEOUT_SEC", "30"))
_VISION_MAX_BYTES = int(os.getenv("VISION_MAX_BYTES", "4000000"))
_VISION_MAX_TOKENS = int(os.getenv("VISION_MAX_TOKENS", "4096"))


class VisualAnalysis:
    """Vision / URL / JSON 分析结果。"""

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
        reference_blueprint: Optional[ReferenceBlueprint] = None,
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
        self.reference_blueprint = reference_blueprint

    def to_dict(self) -> Dict[str, Any]:
        out: Dict[str, Any] = {
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
        if self.reference_blueprint is not None:
            out["reference_blueprint"] = self.reference_blueprint.to_prompt_dict()
        return out


def is_vision_reference_enabled() -> bool:
    return _VISION_ENABLED()


def _resolve_vision_model(req: Optional[BuildRequest]) -> str:
    override = (os.getenv("VISION_MODEL") or "").strip()
    if override:
        return override
    if req and getattr(req, "model", None):
        m = str(req.model)
        if any(k in m.lower() for k in ("gpt-4o", "vision", "gemini", "claude", "deepseek")):
            return m
    return "gpt-4o-mini"


def _fetch_web_page_snippet(url: str, max_chars: int = 600) -> str:
    try:
        safe_url = validate_reference_url(url)
        resp = safe_http_get(
            safe_url,
            timeout=8,
            max_bytes=_VISION_MAX_BYTES,
            headers={"User-Agent": "FormaCraft/1.0 (architecture research)"},
        )
        resp.raise_for_status()
        ctype = (resp.headers.get("content-type") or "").lower()
        if "image" in ctype:
            return f"[image content at {safe_url}]"
        text = resp.text[:8000]
        title_m = re.search(r"<title[^>]*>([^<]+)</title>", text, re.I)
        title = title_m.group(1).strip() if title_m else safe_url
        body = re.sub(r"<script[^>]*>.*?</script>", " ", text, flags=re.I | re.S)
        body = re.sub(r"<style[^>]*>.*?</style>", " ", body, flags=re.I | re.S)
        body = re.sub(r"<[^>]+>", " ", body)
        body = re.sub(r"\s+", " ", body).strip()
        snippet = body[:max_chars]
        return f"{title}: {snippet}"
    except UnsafeUrlError as e:
        logger.warning("Blocked unsafe reference URL %r: %s", url, e)
        return ""
    except Exception as e:
        logger.warning("Web URL fetch failed for %s: %s", url, e)
        return ""


def _visual_from_blueprint(blueprint: ReferenceBlueprint, *, source: str = "reference_json") -> VisualAnalysis:
    meta = blueprint.metadata or {}
    dims = blueprint.dimensions()
    name = str(meta.get("project_name") or "").replace("_", " ").strip() or None
    style = blueprint.style_tag()
    features = blueprint.distinctive_features()
    materials: List[str] = []
    for group in (blueprint.block_palette or {}).values():
        if isinstance(group, dict):
            materials.extend(str(k) for k in group.keys())

    return VisualAnalysis(
        building_name=name,
        style=style,
        form_observations=[str((blueprint.structural_backbone or {}).get("description") or "")].copy()
        if blueprint.structural_backbone
        else [],
        massing=[layer.get("layer_id", "") for layer in (blueprint.architectural_layers or []) if isinstance(layer, dict)],
        materials=materials[:12],
        distinctive_elements=features,
        typical_width_blocks=dims.get("width_x"),
        typical_depth_blocks=dims.get("depth_z"),
        typical_height_blocks=dims.get("height_y"),
        confidence=0.85,
        notes=blueprint.summary_for_notes(),
        sources=[{"title": source, "url": ""}],
        reference_blueprint=blueprint,
    )


def _rule_based_visual_from_caption(caption: str, url: str = "") -> VisualAnalysis:
    text = f"{caption} {url}".lower()
    elements: List[str] = []
    for kw in (
        "dome", "tower", "spire", "courtyard", "facade", "glass", "steel",
        "curve", "arch", "pagoda", "steampunk", "cyberpunk",
        "圆顶", "塔", "庭院", "玻璃", "曲线", "飞檐", "工业",
    ):
        if kw in text:
            elements.append(kw)
    return VisualAnalysis(
        distinctive_elements=elements,
        confidence=0.35 if elements else 0.2,
        notes=caption or url or None,
    )


def _visual_from_llm_json(data: Dict[str, Any], image_urls: List[str]) -> VisualAnalysis:
    blueprint = parse_reference_blueprint(data)
    if blueprint is not None:
        visual = _visual_from_blueprint(blueprint, source="vision_llm")
        if image_urls:
            visual.sources = [{"title": "vision", "url": image_urls[0]}]
        return visual

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
        sources=[{"title": "vision", "url": image_urls[0]}] if image_urls else [],
    )


def analyze_with_vision_llm(
    client: Any,
    model: str,
    image_urls: List[str],
    user_text: str,
    *,
    call_with_timeout: Optional[Callable] = None,
    timeout_sec: float = 30.0,
) -> Optional[VisualAnalysis]:
    if not client or not image_urls:
        return None

    prompt = (
        f"{REFERENCE_BLUEPRINT_VISION_PROMPT}\n\n"
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
            max_tokens=_VISION_MAX_TOKENS,
        )

    try:
        if call_with_timeout:
            response = call_with_timeout(_call, timeout_sec)
        else:
            response = _call()
        raw = response.choices[0].message.content
        data = json.loads(raw) if isinstance(raw, str) else {}
        return _visual_from_llm_json(data, image_urls)
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
    分析 references[]：reference_json 直传、Vision LLM 图像、或规则 fallback。
    """
    if not references or not is_vision_reference_enabled():
        return None

    image_urls: List[str] = []
    web_snippets: List[str] = []
    captions: List[str] = []
    blueprint_refs: List[ReferenceBlueprint] = []

    for ref in references:
        if ref.caption:
            captions.append(ref.caption)
        json_text = ref.parsed_reference_blueprint_content()
        if json_text:
            bp = parse_reference_blueprint(json_text)
            if bp is not None:
                blueprint_refs.append(bp)
                continue
        img = ref.normalized_image_url()
        if img:
            if img.startswith(("http://", "https://")):
                try:
                    validate_reference_url(img)
                except UnsafeUrlError as e:
                    logger.warning("Skipping unsafe image URL reference: %s", e)
                    continue
            image_urls.append(img)
        elif ref.is_web_page():
            raw_url = ref.content.strip()
            try:
                validate_reference_url(raw_url)
            except UnsafeUrlError as e:
                logger.warning("Skipping unsafe web_url reference: %s", e)
                continue
            snippet = _fetch_web_page_snippet(raw_url)
            if snippet:
                web_snippets.append(snippet)

    if blueprint_refs:
        visual = _visual_from_blueprint(blueprint_refs[0], source="reference_json")
        if len(blueprint_refs) > 1:
            visual.notes = ((visual.notes or "") + f" (+{len(blueprint_refs)-1} more blueprint refs)").strip()
        merged_notes = "\n".join(web_snippets + captions).strip()
        if merged_notes:
            visual.notes = ((visual.notes or "") + "\n" + merged_notes).strip()
        return visual

    merged_notes = "\n".join(web_snippets + captions).strip()

    visual: Optional[VisualAnalysis] = None
    if image_urls and req is not None:
        try:
            from ..services.llm_client import get_client

            client = get_client(req)
            if client:
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

    if visual and merged_notes and not visual.notes:
        visual.notes = merged_notes
    elif visual and merged_notes:
        visual.notes = ((visual.notes or "") + "\n" + merged_notes).strip()

    return visual


def merge_visual_into_profile(
    profile: BuildingProfile,
    visual: VisualAnalysis,
) -> BuildingProfile:
    """将 VisualAnalysis（含 ReferenceBlueprint）合并进 BuildingProfile。"""
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

    if visual.reference_blueprint is not None:
        data["reference_blueprint"] = visual.reference_blueprint.to_prompt_dict()
        mc = data.get("minecraft_strategy") or {}
        mc["landmark_module"] = None
        mc["notes"] = (
            "Follow reference_blueprint architectural_layers and block_palette; "
            "compositional MASS/ROOF/FACADE, not generic landmark MODULE."
        )
        data["minecraft_strategy"] = mc
        if visual.reference_blueprint.style_tag():
            data["identity"]["style"] = data["identity"].get("style") or visual.reference_blueprint.style_tag()

    notes = (data.get("research_notes") or "") + "\n[Visual] " + (visual.notes or "")
    data["research_notes"] = notes.strip()
    if visual.sources:
        data["sources"] = (data.get("sources") or []) + visual.sources
    merged = BuildingProfile.model_validate(data)
    if visual.building_name and not merged.query:
        merged.query = visual.building_name
    return merged
