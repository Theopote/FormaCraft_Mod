from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


@dataclass(frozen=True)
class CultureHit:
    id: str
    styleId: str
    score: float
    matchedKeywords: List[str]
    matchedNegatives: List[str]
    exampleRefs: List[str]


def _find_repo_assets_dir() -> Optional[Path]:
    """
    Locate: <repo>/src/main/resources/assets/formacraft
    Works in dev repo checkouts.
    """
    here = Path(__file__).resolve()
    for p in [here] + list(here.parents):
        cand = p / "src" / "main" / "resources" / "assets" / "formacraft"
        if cand.exists() and cand.is_dir():
            return cand
    return None


def _load_json(p: Path) -> Any:
    return json.loads(p.read_text(encoding="utf-8"))


def _str_list(v: Any) -> List[str]:
    if not isinstance(v, list):
        return []
    out: List[str] = []
    for it in v:
        if isinstance(it, str) and it.strip():
            out.append(it.strip())
    return out


def _syn_map(v: Any) -> Dict[str, List[str]]:
    if not isinstance(v, dict):
        return {}
    out: Dict[str, List[str]] = {}
    for k, vv in v.items():
        if not isinstance(k, str) or not k.strip():
            continue
        ls = _str_list(vv)
        if ls:
            out[k.strip()] = ls
    return out


def _double_map(v: Any) -> Dict[str, float]:
    if not isinstance(v, dict):
        return {}
    out: Dict[str, float] = {}
    for k, vv in v.items():
        if not isinstance(k, str) or not k.strip():
            continue
        try:
            out[k.strip()] = float(vv)
        except Exception:
            continue
    return out


def _split_style_tokens(style_id: str) -> List[str]:
    if not style_id:
        return []
    normalized = style_id.replace("_", " ").replace("-", " ").strip()
    if not normalized:
        return []
    out: List[str] = []
    for part in normalized.split():
        cur = ""
        for i, ch in enumerate(part):
            if i > 0 and ch.isupper() and cur:
                out.append(cur)
                cur = ""
            cur += ch
        if cur:
            out.append(cur)
    return out


def _default_vector_for(style_id: str) -> Tuple[float, float, float, float, float]:
    sid = (style_id or "").upper()
    # density, symmetry, verticality, transparency, structureExposure
    if "GOTHIC" in sid:
        return 0.7, 0.75, 0.9, 0.35, 0.8
    if "INDUSTRIAL" in sid:
        return 0.6, 0.7, 0.55, 0.5, 0.95
    if "MODERN" in sid or "INTERNATIONAL" in sid:
        return 0.55, 0.75, 0.55, 0.75, 0.35
    if "JAPANESE" in sid:
        return 0.45, 0.55, 0.5, 0.35, 0.25
    if "COTTAGE" in sid or "RURAL" in sid:
        return 0.5, 0.65, 0.45, 0.25, 0.2
    if "CASTLE" in sid or "MEDIEVAL" in sid:
        return 0.65, 0.75, 0.7, 0.12, 0.85
    if "SIHEYUAN" in sid or "HUIZHOU" in sid or "COURTYARD" in sid:
        return 0.5, 0.85, 0.4, 0.2, 0.25
    if "CLASSICAL" in sid or "GRECOROMAN" in sid:
        return 0.55, 0.8, 0.6, 0.25, 0.4
    return 0.6, 0.6, 0.6, 0.4, 0.5


def _clamp01(x: float) -> float:
    return 0.0 if x < 0 else 1.0 if x > 1 else x


def retrieve(prompt: str, topK: int = 3, fewShotK: int = 2) -> Dict[str, Any]:
    """
    P0 keyword retrieval over culture_cards.
    Returns dict with hits + fewShots(compact) + assemblyDraft.
    """
    assets = _find_repo_assets_dir()
    if not assets:
        return {"hits": [], "fewShots": [], "assemblyDraft": None}

    cards_dir = assets / "culture_cards"
    ex_dir = assets / "assembly_examples"
    if not cards_dir.exists():
        return {"hits": [], "fewShots": [], "assemblyDraft": None}

    q = (prompt or "").strip()
    qn = q.lower()

    hits: List[CultureHit] = []
    for p in sorted(cards_dir.glob("*.json")):
        try:
            c = _load_json(p)
        except Exception:
            continue
        if not isinstance(c, dict):
            continue
        cid = str(c.get("id") or "").strip()
        style_id = str(c.get("styleId") or "").strip()
        if not cid or not style_id:
            continue

        intents = _str_list(c.get("intents"))
        keywords = _str_list(c.get("keywords"))
        synonyms = _syn_map(c.get("synonyms"))
        kw_w = _double_map(c.get("keywordWeights"))
        negs = _str_list(c.get("negativeKeywords"))
        ex_refs = _str_list(c.get("exampleRefs"))

        score = 0.0
        matched: List[str] = []
        matched_neg: List[str] = []

        for kw in keywords:
            w = max(0.1, float(kw_w.get(kw, 3.0)))
            if kw.lower() in qn:
                score += w
                matched.append(kw)
            else:
                for syn in synonyms.get(kw, []):
                    if syn.lower() in qn:
                        score += min(2.0, w)
                        matched.append(syn)
                        break

        for neg in negs:
            if neg.lower() in qn:
                score -= 4.0
                matched_neg.append(neg)

        for it in intents:
            if it.lower() in qn:
                score += 1.0

        for tok in _split_style_tokens(style_id):
            if tok.lower() in qn:
                score += 1.0

        if score > 0.01:
            hits.append(CultureHit(cid, style_id, score, matched, matched_neg, ex_refs))

    hits.sort(key=lambda h: (-h.score, -len(h.matchedKeywords), h.id.lower()))
    hits = hits[: max(1, topK)]

    llmplan_dir = assets / "llmplan_examples"
    llm_plan_few_shots: List[Dict[str, Any]] = []
    landmark_module_id: Optional[str] = None

    # few-shot: union of exampleRefs from hits (dedupe)
    few_shots: List[Dict[str, Any]] = []
    used: set[str] = set()
    left = max(0, fewShotK)
    for h in hits:
        for ex in h.exampleRefs:
            if left <= 0:
                break
            if ex in used:
                continue
            used.add(ex)
            ex_path = ex_dir / ex
            if ex_path.exists():
                try:
                    ex_obj = _load_json(ex_path)
                    few_shots.append(_compact_example(ex_obj))
                    left -= 1
                except Exception:
                    pass
        if left <= 0:
            break

    # draft: styleId from best, sliders fused from topK + prompt evidence
    best = hits[0] if hits else None

    # LlmPlan landmark routing from best culture card
    proportion_card_id: Optional[str] = None
    if best:
        for cp in cards_dir.glob("*.json"):
            try:
                card = _load_json(cp)
            except Exception:
                continue
            if not isinstance(card, dict):
                continue
            if str(card.get("id") or "").strip() != best.id:
                continue
            lm = str(card.get("landmarkModuleId") or "").strip()
            if lm:
                landmark_module_id = lm
            pcid = str(card.get("proportionCardId") or "").strip()
            if pcid:
                proportion_card_id = pcid
            for ref in _str_list(card.get("llmPlanExampleRefs")):
                ex_path = llmplan_dir / ref
                if not ex_path.exists():
                    continue
                try:
                    ex_obj = _load_json(ex_path)
                    llm_plan_few_shots.append(_compact_llmplan_example(ex_obj))
                except Exception:
                    pass
            break

    proportion_card = None
    try:
        from app.services.proportion_retriever import retrieve_proportion_card
        proportion_card = retrieve_proportion_card(q, proportion_card_id=proportion_card_id)
    except Exception:
        proportion_card = None

    style_id = best.styleId if best else "Unknown"
    base = _default_vector_for(style_id)
    ssum = sum(max(0.0, h.score) for h in hits) or 0.0
    if ssum > 1e-6:
        acc = [0.0, 0.0, 0.0, 0.0, 0.0]
        for h in hits:
            w = max(0.0, h.score) / ssum
            v = _default_vector_for(h.styleId)
            for i in range(5):
                acc[i] += v[i] * w
        base = tuple(acc)  # type: ignore

    density, symmetry, verticality, transparency, structure_exposure = base
    if any(k in q for k in ["密集", "繁复", "高密度"]):
        density = _clamp01(density + 0.15)
    if any(k in q for k in ["对称", "轴线"]):
        symmetry = _clamp01(symmetry + 0.2)
    if any(k in q for k in ["高耸", "尖塔", "垂直", "神圣"]):
        verticality = _clamp01(verticality + 0.25)
    if any(k in q for k in ["玻璃", "透明", "幕墙", "采光"]):
        transparency = _clamp01(transparency + 0.25)
    if any(k in q for k in ["结构外露", "桁架", "骨骼", "钢"]):
        structure_exposure = _clamp01(structure_exposure + 0.3)

    assembly_draft = {
        "graph": {
            "components": [
                {
                    "id": "Primary",
                    "type": "SHELL_BOX",
                    "at": {"x": 0, "y": 0, "z": 0},
                    "w": 18,
                    "d": 12,
                    "h": 14,
                }
            ],
            "connections": [],
        },
        "macro": {
            "style": {
                "styleId": style_id,
                "density": round(density, 3),
                "symmetry": round(symmetry, 3),
                "verticality": round(verticality, 3),
                "transparency": round(transparency, 3),
                "structureExposure": round(structure_exposure, 3),
            }
        },
    }

    return {
        "hits": [h.__dict__ for h in hits],
        "fewShots": few_shots,
        "assemblyDraft": assembly_draft,
        "landmarkModuleId": landmark_module_id,
        "llmPlanFewShots": llm_plan_few_shots,
        "proportionCardId": proportion_card_id,
        "proportionCard": proportion_card,
    }


def retrieve_budgeted(
    prompt: str,
    *,
    topK: int = 3,
    fewShotK: int = 2,
    maxItems: int = 2,
    maxExampleChars: int = 1600,
    maxChars: int = 6000,
) -> Dict[str, Any]:
    """
    Budgeted retrieval wrapper for prompt injection:
    - maxItems: cap fewShots count
    - maxExampleChars: cap each fewShot JSON string length (truncate by dropping optional fields)
    - maxChars: cap total CultureRetrieval(JSON) block length (post-serialization)

    Degrade strategy if exceeds maxChars:
    1) shrink fewShots (keep 1, then 0)
    2) shrink hits to top1 and strip matched lists
    3) hard-minify payload (drop everything but a tiny style hint)
    """
    rag = retrieve(prompt, topK=topK, fewShotK=fewShotK)

    # Enforce per-example budget by stripping to only primaryComponent + macro/palette/entranceFacing
    fs = rag.get("fewShots") if isinstance(rag.get("fewShots"), list) else []
    compacted: List[Dict[str, Any]] = []
    for ex in fs:
        if not isinstance(ex, dict):
            continue
        ce = dict(ex)
        s = json.dumps(ce, ensure_ascii=False)
        if len(s) > maxExampleChars:
            # drop macro first, then paletteId/entranceFacing, keep primaryComponent as last resort
            ce.pop("macro", None)
            s = json.dumps(ce, ensure_ascii=False)
        if len(s) > maxExampleChars:
            for k in ["paletteId", "entranceFacing"]:
                ce.pop(k, None)
            s = json.dumps(ce, ensure_ascii=False)
        if len(s) > maxExampleChars:
            # keep only primaryComponent
            pc = ce.get("primaryComponent")
            ce = {"primaryComponent": pc} if isinstance(pc, dict) else {}
        compacted.append(ce)

    if maxItems < 0:
        maxItems = 0
    rag["fewShots"] = compacted[:maxItems]

    # Now cap total serialized length
    def _ser(obj: Any) -> str:
        return json.dumps(obj, ensure_ascii=False)

    s0 = _ser(rag)
    if len(s0) <= maxChars:
        return rag

    # degrade 1: reduce fewShots to 1 then 0
    if isinstance(rag.get("fewShots"), list) and len(rag["fewShots"]) > 1:
        rag["fewShots"] = rag["fewShots"][:1]
        if len(_ser(rag)) <= maxChars:
            return rag
    rag["fewShots"] = []
    if len(_ser(rag)) <= maxChars:
        return rag

    # degrade 2: reduce hits and strip verbose fields
    hits = rag.get("hits")
    if isinstance(hits, list) and hits:
        h0 = hits[0]
        if isinstance(h0, dict):
            h0 = dict(h0)
            h0.pop("matchedKeywords", None)
            h0.pop("matchedNegatives", None)
            h0.pop("exampleRefs", None)
            rag["hits"] = [h0]
        else:
            rag["hits"] = hits[:1]
    else:
        rag["hits"] = []

    # final: if still too big, drop assemblyDraft
    if len(_ser(rag)) > maxChars:
        rag["assemblyDraft"] = None

    # hardcap: if still too big, return a tiny hint (guaranteed small & valid JSON)
    if len(_ser(rag)) > maxChars:
        style_id = None
        try:
            hits = rag.get("hits")
            if isinstance(hits, list) and hits and isinstance(hits[0], dict):
                style_id = hits[0].get("styleId")
        except Exception:
            style_id = None
        tiny = {
            "hits": ([{"styleId": style_id}] if style_id else []),
            "fewShots": [],
            "assemblyDraft": ({"macro": {"style": {"styleId": style_id}}} if style_id else None),
        }
        # still ensure <= maxChars
        if len(_ser(tiny)) <= maxChars:
            return tiny
        # ultimate fallback: empty object
        return {"hits": [], "fewShots": [], "assemblyDraft": None}

    return rag


def _compact_llmplan_example(ex: Any) -> Dict[str, Any]:
    """Keep LlmPlan few-shot compact for prompt injection."""
    if not isinstance(ex, dict):
        return {"raw": ex}
    plan = ex.get("plan") if isinstance(ex.get("plan"), dict) else ex
    if not isinstance(plan, dict):
        return {"raw": ex}
    out: Dict[str, Any] = {
        "mode": plan.get("mode"),
        "style_profile": plan.get("style_profile"),
        "layout": plan.get("layout"),
        "components": plan.get("components"),
    }
    if ex.get("description"):
        out["description"] = ex.get("description")
    if ex.get("promptHints"):
        out["promptHints"] = ex.get("promptHints")
    return out


def resolve_landmark_module_routing(prompt: str) -> Optional[Dict[str, Any]]:
    """
    Resolve landmark MODULE routing tier for LlmPlan output.
    MANDATORY only when user names a landmark; SUGGESTED for typological matches;
    None when user wants 原创/独特/不要地标.
    """
    from app.services.landmark_routing_policy import (
        RoutingTier,
        is_creative_or_original_intent,
        resolve_for_user_intent,
        variation_context_block,
    )

    q = (prompt or "").strip()
    if not q:
        return None

    if is_creative_or_original_intent(q):
        return None

    decision = resolve_for_user_intent(q)
    module_id: Optional[str] = decision.module_id if decision else None
    routing_tier = decision.tier if decision else RoutingTier.SUGGESTED
    source = decision.reason if decision else None

    llm_plan_few_shots: List[Dict[str, Any]] = []
    instruction = None

    rag = retrieve(q, topK=1, fewShotK=0)
    if module_id is None and isinstance(rag.get("landmarkModuleId"), str) and rag["landmarkModuleId"].strip():
        module_id = rag["landmarkModuleId"].strip()
        source = source or "culture_card"
    if isinstance(rag.get("llmPlanFewShots"), list):
        llm_plan_few_shots = [x for x in rag["llmPlanFewShots"] if isinstance(x, dict)]

    kb = retrieve_building_knowledge(q, topK=1)
    if kb:
        kb_lm = str(kb.get("landmarkModuleId") or "").strip()
        if kb_lm and module_id is None:
            module_id = kb_lm
            source = source or "building_knowledge"
        routing = kb.get("llmPlanRouting")
        if isinstance(routing, dict):
            instruction = routing.get("instruction") if isinstance(routing.get("instruction"), str) else instruction

    if not module_id:
        return None

    tier_value = routing_tier.value if isinstance(routing_tier, RoutingTier) else str(routing_tier)
    if tier_value == RoutingTier.MANDATORY.value:
        default_instruction = (
            f"User explicitly named landmark {module_id}. "
            f"MUST output ONE MODULE with features [\"landmark:{module_id}\"]. "
            "Still vary designSeed, facing, dimensions hints, style_attributes."
        )
    else:
        default_instruction = (
            f"Typological match for {module_id}. "
            f"PREFERRED: MODULE with features [\"landmark:{module_id}\"] and varied params "
            "(designSeed, bowlSteepness, facing). "
            "ALTERNATIVE: compositional MASS tiers + PAVING + ROOF for 原创 results. "
            "Do NOT use a plain rectangular MASS box for elliptical stadium requests."
        )

    return {
        "moduleId": module_id,
        "componentType": "MODULE",
        "feature": f"landmark:{module_id}",
        "routingTier": tier_value,
        "source": source,
        "instruction": instruction or default_instruction,
        "variationHints": variation_context_block(),
        "exampleDimensions": {"width": 60, "depth": 80, "height": 28},
        "exampleParams": {
            "module_id": module_id,
            "meshStructure": True,
            "designSeed": 4821,
            "bowlSteepness": 0.32,
            "facing": "SOUTH",
        },
        "llmPlanFewShots": llm_plan_few_shots,
    }


def _compact_example(ex: Any) -> Dict[str, Any]:
    """
    Keep prompt context short:
    - keep: paletteId, entranceFacing
    - keep: macro (if any)
    - keep: graph.components[0] with w/d/h/type and facade (if any)
    """
    if not isinstance(ex, dict):
        return {"raw": ex}
    out: Dict[str, Any] = {}
    for k in ["paletteId", "entranceFacing"]:
        if k in ex:
            out[k] = ex.get(k)
    if "macro" in ex:
        out["macro"] = ex.get("macro")
    graph = ex.get("graph") if isinstance(ex.get("graph"), dict) else None
    if graph and isinstance(graph.get("components"), list) and graph["components"]:
        c0 = graph["components"][0]
        if isinstance(c0, dict):
            keep = {kk: c0.get(kk) for kk in ["id", "type", "w", "d", "h", "r", "at", "facade"] if kk in c0}
            out["primaryComponent"] = keep
    return out


def retrieve_building_knowledge(prompt: str, topK: int = 1) -> Optional[Dict[str, Any]]:
    """
    Retrieve building knowledge from building_knowledge directory.
    Returns the best matching building knowledge entry, or None if no match.
    
    This is used to provide detailed architectural features for specific buildings
    (e.g., Bird's Nest Stadium, Eiffel Tower) to enhance AI understanding.
    """
    assets = _find_repo_assets_dir()
    if not assets:
        return None
    
    knowledge_dir = assets / "building_knowledge"
    if not knowledge_dir.exists():
        return None
    
    q = (prompt or "").strip()
    qn = q.lower()
    
    best_match: Optional[Dict[str, Any]] = None
    best_score = 0.0
    
    for p in sorted(knowledge_dir.glob("*.json")):
        try:
            kb = _load_json(p)
        except Exception:
            continue
        if not isinstance(kb, dict):
            continue
        
        kb_id = str(kb.get("id") or "").strip()
        if not kb_id:
            continue
        
        aliases = _str_list(kb.get("aliases"))
        keywords = _str_list(kb.get("keywords"))
        
        score = 0.0
        matched_aliases: List[str] = []
        
        # Match aliases (higher weight)
        for alias in aliases:
            alias_lower = alias.lower()
            if alias_lower in qn:
                # Longer alias matches get higher score
                score += len(alias) * 2.0
                matched_aliases.append(alias)
        
        # Match keywords (lower weight)
        for kw in keywords:
            kw_lower = kw.lower()
            if kw_lower in qn:
                score += 1.0
        
        if score > best_score:
            best_score = score
            best_match = dict(kb)
            best_match["matchedAliases"] = matched_aliases
    
    return best_match if best_score > 0.01 else None



