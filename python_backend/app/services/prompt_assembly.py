"""Backend-owned prompt assembly for LlmPlan requests.

Clients send structured fields (userMessage + outline/selection/path/…).
Backend owns the system prompt and injects spatial constraints into the user prompt.
Client-supplied systemPrompt / userPrompt are ignored (injection surface).
"""

from __future__ import annotations

from typing import Tuple

from ..models.request import BuildRequest


def format_spatial_constraints(req: BuildRequest | None) -> str:
    """Compact HARD constraint block from structured FormaRequest fields."""
    if req is None:
        return ""
    parts: list[str] = []

    player = getattr(req, "player", None)
    if player is not None:
        pos = getattr(player, "pos", None)
        facing = getattr(player, "facing", None)
        if pos is not None:
            parts.append(
                f"Anchor/player pos: ({getattr(pos, 'x', '?')}, {getattr(pos, 'y', '?')}, {getattr(pos, 'z', '?')})"
            )
        if facing:
            parts.append(f"Facing: {facing}")

    world = getattr(req, "world", None)
    if world is not None:
        dim = getattr(world, "dimension", None)
        biome = getattr(world, "biome", None)
        if dim:
            parts.append(f"Dimension: {dim}")
        if biome:
            parts.append(f"Biome: {biome}")

    selection = getattr(req, "selection", None)
    if selection is not None and getattr(selection, "min", None) and getattr(selection, "max", None):
        mn, mx = selection.min, selection.max
        parts.append(
            f"Selection AABB (HARD): min=({mn.x},{mn.y},{mn.z}) max=({mx.x},{mx.y},{mx.z})"
        )

    brush = getattr(req, "brushSelection", None)
    if brush is not None and selection is None and getattr(brush, "min", None) and getattr(brush, "max", None):
        mn, mx = brush.min, brush.max
        parts.append(
            f"Brush Selection AABB (HARD): min=({mn.x},{mn.y},{mn.z}) max=({mx.x},{mx.y},{mx.z})"
        )

    outline = getattr(req, "outline", None)
    if outline is not None:
        try:
            shape = (getattr(outline, "shapeType", None) or "").lower()
            if shape == "circle" and getattr(outline, "center", None) is not None and getattr(outline, "radius", None) is not None:
                c = outline.center
                parts.append(
                    f"Outline (HARD): circle center=({c.x},{c.y},{c.z}), radius={outline.radius}, "
                    f"yRange=[{getattr(outline, 'minY', '?')},{getattr(outline, 'maxY', '?')}]"
                )
            else:
                vs = getattr(outline, "vertices", None) or []
                if vs:
                    head = "; ".join([f"({p.x},{p.y},{p.z})" for p in vs[:8]])
                    more = "" if len(vs) <= 8 else f" ... (+{len(vs) - 8} more)"
                    parts.append(
                        f"Outline (HARD): polygon vertices={len(vs)} {head}{more}, "
                        f"yRange=[{getattr(outline, 'minY', '?')},{getattr(outline, 'maxY', '?')}]"
                    )
                else:
                    parts.append(
                        f"Outline (HARD): shapeType={getattr(outline, 'shapeType', '?')}, "
                        f"yRange=[{getattr(outline, 'minY', '?')},{getattr(outline, 'maxY', '?')}]"
                    )
        except Exception:
            parts.append("Outline (HARD): provided (failed to summarize)")

    zones = getattr(req, "protectedZones", None) or []
    if zones:
        parts.append("ProtectedZones (HARD): do NOT place blocks inside these AABBs:")
        for z in zones[:12]:
            parts.append(
                f"  - min=({z.min.x},{z.min.y},{z.min.z}) max=({z.max.x},{z.max.y},{z.max.z})"
            )
        if len(zones) > 12:
            parts.append(f"  - ... (+{len(zones) - 12} more)")

    path = getattr(req, "path", None)
    if path is not None:
        nodes = getattr(path, "nodes", None) or []
        radius = getattr(path, "radius", None)
        if nodes:
            head = "; ".join(
                [f"({n.x},{n.y},{n.z})" for n in nodes[:8] if n is not None]
            )
            more = "" if len(nodes) <= 8 else f" ... (+{len(nodes) - 8} more)"
            parts.append(
                f"Path corridor (HARD): radius={radius if radius is not None else '?'}, "
                f"nodes={len(nodes)} {head}{more}"
            )

    mode = getattr(req, "promptMode", None)
    if mode:
        parts.append(f"Prompt mode: {mode}")

    if not parts:
        return ""
    return "REQUEST CONTEXT (HARD — respect these constraints):\n" + "\n".join(parts)


def resolve_llm_plan_prompts(
    req: BuildRequest,
    *,
    default_system: str,
) -> Tuple[str, str]:
    """
    Returns (system_prompt, user_prompt).

    Backend always owns the system prompt. User text comes from userMessage
    (preferred) or legacy requestText. Client systemPrompt/userPrompt are discarded.
    Spatial constraints from structured fields are appended to the user prompt.
    """
    user_message = (getattr(req, "userMessage", None) or "").strip()
    if not user_message:
        request_text = req.requestText or ""
        # Legacy marker split — only when userMessage is absent (old clients).
        if "USER REQUEST:" in request_text:
            parts = request_text.split("USER REQUEST:", 1)
            user_message = parts[1].strip() if len(parts) > 1 else request_text.strip()
        else:
            user_message = request_text.strip()

    context = format_spatial_constraints(req)
    if context:
        user_prompt = f"{user_message}\n\n{context}" if user_message else context
    else:
        user_prompt = user_message

    return default_system, user_prompt


def research_user_text(req: BuildRequest, user_prompt: str) -> str:
    text = (req.userMessage or "").strip()
    if text:
        return text
    # Strip appended context block if present so research uses the raw intent.
    if user_prompt and "REQUEST CONTEXT (HARD" in user_prompt:
        return user_prompt.split("REQUEST CONTEXT (HARD", 1)[0].strip()
    return (user_prompt or "").strip()
