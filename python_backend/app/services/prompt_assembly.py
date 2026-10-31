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
                    # Prefer AABB + full vertex count so truncated vertex lists are not
                    # mistaken for the complete HARD outline (review: 12-gon → 8 verts).
                    xs = [getattr(p, "x", 0) for p in vs]
                    zs = [getattr(p, "z", 0) for p in vs]
                    bbox = (
                        f"bboxXZ=[({min(xs)},{min(zs)})-({max(xs)},{max(zs)})]"
                        if xs and zs
                        else "bboxXZ=?"
                    )
                    max_verts = 32
                    shown = vs if len(vs) <= max_verts else vs[:max_verts]
                    head = "; ".join([f"({p.x},{p.y},{p.z})" for p in shown])
                    more = "" if len(vs) <= max_verts else f" ... (+{len(vs) - max_verts} more omitted)"
                    parts.append(
                        f"Outline (HARD): polygon vertices={len(vs)} {bbox} {head}{more}, "
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
        max_zones = 24
        parts.append(
            f"ProtectedZones (HARD, total={len(zones)}): do NOT place blocks inside these AABBs:"
        )
        for z in zones[:max_zones]:
            parts.append(
                f"  - min=({z.min.x},{z.min.y},{z.min.z}) max=({z.max.x},{z.max.y},{z.max.z})"
            )
        if len(zones) > max_zones:
            parts.append(f"  - ... (+{len(zones) - max_zones} more omitted)")

    path = getattr(req, "path", None)
    if path is not None:
        nodes = getattr(path, "nodes", None) or []
        radius = getattr(path, "radius", None)
        if nodes:
            max_nodes = 32
            shown = [n for n in nodes[:max_nodes] if n is not None]
            head = "; ".join([f"({n.x},{n.y},{n.z})" for n in shown])
            more = "" if len(nodes) <= max_nodes else f" ... (+{len(nodes) - max_nodes} more omitted)"
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


def format_library_context(req: BuildRequest | None) -> str:
    """Soft context: player library, groups, nearby memory (server-filled summaries)."""
    if req is None:
        return ""
    parts: list[str] = []
    catalog = (getattr(req, "componentCatalogSummary", None) or "").strip()
    if catalog and "no player components" not in catalog.lower():
        parts.append(catalog)
    groups = (getattr(req, "componentGroupSummary", None) or "").strip()
    if groups and "no component groups" not in groups.lower():
        parts.append("Component groups:\n" + groups if not groups.lower().startswith("component") else groups)
    memory = (getattr(req, "nearbyMemorySummary", None) or "").strip()
    if memory and "no nearby" not in memory.lower() and "unavailable" not in memory.lower():
        parts.append(memory)
    if not parts:
        return ""
    return "LIBRARY / MEMORY CONTEXT (prefer these when relevant):\n" + "\n\n".join(parts)


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

    chunks: list[str] = []
    if user_message:
        chunks.append(user_message)
    context = format_spatial_constraints(req)
    if context:
        chunks.append(context)
    library = format_library_context(req)
    if library:
        chunks.append(library)
    user_prompt = "\n\n".join(chunks)

    return default_system, user_prompt


def research_user_text(req: BuildRequest, user_prompt: str) -> str:
    text = (req.userMessage or "").strip()
    if text:
        return text
    # Strip appended context block if present so research uses the raw intent.
    if user_prompt and "REQUEST CONTEXT (HARD" in user_prompt:
        return user_prompt.split("REQUEST CONTEXT (HARD", 1)[0].strip()
    return (user_prompt or "").strip()
